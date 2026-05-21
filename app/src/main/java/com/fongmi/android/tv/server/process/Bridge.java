package com.fongmi.android.tv.server.process;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.ParseJob;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.bridge.BridgeJarUi;
import com.fongmi.android.tv.server.bridge.BridgeSites;
import com.fongmi.android.tv.server.bridge.BridgeTokens;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.AbiUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.Spider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Bridge implements Process {

    private static final String MIME_JSON = "application/json; charset=utf-8";
    private static final String LOCAL_PROXY_PREFIX = "/bridge/local/";
    private static final String MEDIA_PROXY_PREFIX = "/bridge/media/";
    private static final long UI_WAIT_TIMEOUT = 20000;
    private static final long UI_TASK_DONE_GRACE = 12000;
    private static final ConcurrentHashMap<String, MediaTarget> MEDIA_TARGETS = new ConcurrentHashMap<>();
    private FutureTask<Result> uiTask;
    private Future<?> uiFuture;
    private Site uiSite;
    private JsonObject uiBody = new JsonObject();
    private String uiAction = "";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/health") || url.startsWith("/api/v1/") || url.startsWith("/bridge/local") || url.startsWith("/bridge/media");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.OPTIONS) return json(ok());
        try {
            if (url.startsWith("/bridge/local")) return localProxy(session);
            if (url.startsWith("/bridge/media")) return mediaProxy(session);
            if (url.startsWith("/health")) return json(health());
            if (url.equals("/api/v1/config/register")) return json(register(body(files)));
            if (url.matches("/api/v1/config/[^/]+/sites")) return json(sites());
            if (url.startsWith("/api/v1/site/")) return json(site(session, url, body(files)));
            return json(error("not_found", "Unsupported bridge path: " + url));
        } catch (Throwable e) {
            return json(error("bridge_error", safeMessage(e)));
        }
    }

    private JsonObject health() {
        JsonObject object = ok();
        object.addProperty("version", "1");
        object.addProperty("address", Server.get().getAddress(false));
        object.addProperty("port", Proxy.getPort());
        object.add("abi", AbiUtil.toJson());
        JsonObject runtimes = new JsonObject();
        runtimes.addProperty("jarDex", "ready");
        runtimes.addProperty("chaquopy", "ready");
        runtimes.addProperty("quickjs", "ready");
        runtimes.addProperty("nativeP2P", AbiUtil.supportsArmNativeExtractors() ? "available" : "disabled-on-this-abi");
        runtimes.addProperty("tvbus", "dynamic-so");
        object.add("runtimes", runtimes);
        return object;
    }

    private JsonObject register(JsonObject body) {
        ensureSharedStorageReady();
        String configUrl = normalizeUrlForAndroid(string(body, "configUrl"));
        if (!TextUtils.isEmpty(configUrl)) body.addProperty("configUrl", configUrl);
        String configId = BridgeSites.register(body);
        if (!hasRegisteredSites(body)) ensureVodConfigLoaded(configUrl);
        JsonObject object = ok();
        object.addProperty("configId", configId);
        object.add("sites", BridgeSites.toJson(allSites()));
        object.add("runtimes", health().getAsJsonObject("runtimes"));
        return object;
    }

    private JsonObject sites() {
        JsonObject object = ok();
        object.addProperty("configId", BridgeSites.getConfigId());
        object.add("sites", BridgeSites.toJson(allSites()));
        return object;
    }

    private JsonObject site(IHTTPSession session, String url, JsonObject body) throws Exception {
        String[] parts = url.split("/");
        if (parts.length < 6) return error("bad_request", "Expected /api/v1/site/{siteKey}/{action}");
        String key = decode(parts[4]);
        String action = parts[5];
        Site site = BridgeSites.getRegisteredSite(key);
        if (site.isEmpty()) {
            ensureVodConfigLoaded(BridgeSites.getConfigUrl());
            site = site(key);
        }
        if (site.isEmpty()) return error("site_not_found", "Bridge site not found: " + key);
        activateSite(site);

        Result result;
        switch (action) {
            case "home":
                result = SiteApi.homeContent(site);
                return result(result);
            case "category":
                result = category(site, body);
                return result(result);
            case "detail":
                result = detail(site, body);
                return result(result);
            case "search":
                result = search(site, body);
                return result(result);
            case "play":
                return play(session, site, body);
            case "action":
                return action(site, body);
            case "token":
                return BridgeTokens.save(site, body);
            case "uiOpen":
                return uiOpen(site, body);
            case "uiStatus":
                return uiStatus(body);
            case "uiAction":
                return uiAction(body);
            case "uiClose":
                return uiClose();
            default:
                return error("not_found", "Unsupported site action: " + action);
        }
    }

    private Result category(Site site, JsonObject body) throws Exception {
        HashMap<String, String> extend = new HashMap<>(BridgeSites.stringMap(object(body, "filters")));
        extend.putAll(BridgeSites.stringMap(object(body, "extend")));
        String tid = first(body, "categoryId", "tid", "typeId");
        String page = first(body, "page", "pg");
        if (page.isEmpty()) page = "1";
        if (inVodConfig(site)) return SiteApi.categoryContent(site.getKey(), tid, page, bool(body, "filter", true), extend);
        String json = site.recent().spider().categoryContent(tid, page, bool(body, "filter", true), extend);
        return Result.fromJson(json);
    }

    private Result detail(Site site, JsonObject body) throws Exception {
        String id = first(body, "id", "vodId");
        if (inVodConfig(site)) return SiteApi.detailContent(site.getKey(), id);
        String json = site.recent().spider().detailContent(Arrays.asList(id));
        Result result = Result.fromJson(json);
        result.getVod().setFlags();
        return result;
    }

    private Result search(Site site, JsonObject body) throws Exception {
        String keyword = first(body, "keyword", "wd", "key");
        String page = first(body, "page", "pg");
        if (page.isEmpty()) page = "1";
        boolean quick = bool(body, "quick", false);
        if (inVodConfig(site)) return SiteApi.searchContent(site, keyword, quick, page);
        Spider spider = site.recent().spider();
        String json = "1".equals(page) ? spider.searchContent(keyword, quick) : spider.searchContent(keyword, quick, page);
        return Result.fromJson(json);
    }

    private JsonObject play(IHTTPSession session, Site site, JsonObject body) throws Exception {
        ensureSharedStorageReady();
        String flag = string(body, "flag");
        String id = first(body, "id", "url");
        Result result;
        try {
            result = playerContentWithTimeout(site, flag, id);
        } catch (TimeoutException e) {
            return error("play_timeout", "Android Bridge 获取播放地址超时，请稍后重试");
        } catch (Exception e) {
            if (BridgeTokens.shouldPromptOnError(site, body, e)) {
                BridgeTokens.invalidate(site, body);
                return playResult(session, site, body, Result.error("当前网盘播放需要先完成 Android Jar 授权"));
            }
            throw e;
        }
        return playResult(session, site, body, result);
    }

    private Result playerContentWithTimeout(Site site, String flag, String id) throws Exception {
        FutureTask<Result> task = new FutureTask<>(() -> playerContent(site, flag, id));
        Thread thread = new Thread(task, "bridge-play-" + site.getKey());
        thread.start();
        try {
            return task.get(site.getTimeout(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            thread.interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    private Result playerContent(Site site, String flag, String id) throws Exception {
        if (inVodConfig(site)) return SiteApi.playerContent(site.getKey(), flag, id);
        String json = site.recent().spider().playerContent(flag, id, VodConfig.get().getFlags());
        Result result = Result.fromJson(json);
        if (result.getFlag().isEmpty()) result.setFlag(flag);
        result.setUrl(Source.get().fetch(result));
        result.setHeader(site.getHeader());
        result.setKey(site.getKey());
        return result;
    }

    private Result actionContent(Site site, String action) throws Exception {
        if (inVodConfig(site)) return SiteApi.action(site.getKey(), action);
        String json = site.recent().spider().action(action);
        return Result.fromJson(json);
    }

    private JsonObject action(Site site, JsonObject body) throws Exception {
        ensureSharedStorageReady();
        String action = string(body, "action");
        String id = first(body, "id", "vodId");
        if (TextUtils.isEmpty(action) && TextUtils.isEmpty(id)) return error("bad_request", "Bridge action 需要 action 或 id");
        boolean detailUi = !TextUtils.isEmpty(id) && (TextUtils.isEmpty(action) || TextUtils.equals(action, id));
        FutureTask<Result> task = new FutureTask<>(() -> detailUi ? openDetailUi(site, body) : actionContent(site, action));
        return beginUiTask(site, body, task, "bridge-ui-action-" + site.getKey(), detailUi ? id : action);
    }

    private Result openDetailUi(Site site, JsonObject body) throws Exception {
        String id = first(body, "id", "vodId");
        String name = first(body, "name", "vodName", "title");
        String pic = first(body, "pic", "vodPic", "cover");
        String mark = first(body, "mark", "typeName");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        App.post(() -> {
            try {
                Activity activity = App.activity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    failure.set(new IllegalStateException("Android app is not in foreground"));
                    return;
                }
                VideoActivity.start(activity, site.getKey(), id, name, pic, TextUtils.isEmpty(mark) ? null : mark);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(3, TimeUnit.SECONDS)) throw new TimeoutException("打开 Android 原生详情页超时");
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() != null) throw new RuntimeException(failure.get());
        return Result.error("已打开 Android 原生详情页，等待 Jar 弹窗");
    }

    private JsonObject uiOpen(Site site, JsonObject body) {
        JsonObject storageError = sharedStorageError();
        if (storageError != null) return storageError;
        String flag = string(body, "flag");
        String id = first(body, "id", "url");
        String action = string(body, "action");
        FutureTask<Result> task = new FutureTask<>(() -> {
            if (!TextUtils.isEmpty(action)) return actionContent(site, action);
            if (!TextUtils.isEmpty(flag) || !TextUtils.isEmpty(id)) return playerContent(site, flag, id);
            return detail(site, body);
        });
        return beginUiTask(site, body, task, "bridge-ui-open-" + site.getKey(), action);
    }

    private JsonObject beginUiTask(Site site, JsonObject body, FutureTask<Result> task, String threadName, String action) {
        boolean hadTask = uiTask != null;
        cancelUiTask();
        if (hadTask) BridgeJarUi.dismiss();
        uiTask = task;
        uiSite = site;
        uiBody = body == null ? new JsonObject() : body.deepCopy();
        uiAction = action == null ? "" : action;
        BridgeJarUi.bringHostToFront();
        uiFuture = Task.executor().submit(task);
        Thread cleaner = new Thread(() -> {
            try {
                task.get(120, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                task.cancel(true);
                if (uiFuture != null) uiFuture.cancel(true);
            }
        }, "bridge-ui-cleaner-" + site.getKey());
        cleaner.start();
        JsonObject object = waitForUiOrTask(site, uiBody, task);
        object.addProperty("action", uiAction);
        if (!"androidJarUi".equals(string(object, "mode")) || "completed".equals(string(object, "status"))) clearFinishedUiTask(task);
        return object;
    }

    private JsonObject waitForUiOrTask(Site site, JsonObject body, FutureTask<Result> task) {
        long deadline = System.currentTimeMillis() + UI_WAIT_TIMEOUT;
        long doneAt = 0;
        JsonObject taskResult = null;
        while (System.currentTimeMillis() < deadline) {
            JsonObject snapshot = BridgeJarUi.snapshot();
            if (bool(snapshot, "ok", false)) return snapshot;
            if (task.isDone()) {
                if (doneAt == 0) {
                    doneAt = System.currentTimeMillis();
                    taskResult = uiTaskResult(site, body, task);
                }
                if (System.currentTimeMillis() - doneAt >= UI_TASK_DONE_GRACE) return taskResult;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted", "等待 Android Jar 界面时被中断");
            }
        }
        if (task.isDone()) return taskResult != null ? taskResult : uiTaskResult(site, body, task);
        cancelUiTask();
        return error("jar_ui_missing", "Android Jar 没有返回结果，也没有出现可桥接窗口");
    }

    private JsonObject uiTaskResult(Site site, JsonObject body, FutureTask<Result> task) {
        JsonObject object = ok();
        object.addProperty("mode", "androidJarUi");
        object.addProperty("status", "completed");
        object.addProperty("done", true);
        object.addProperty("message", "Android Jar 操作已完成");
        try {
            Result result = task.get();
            if (result == null) return object;
            String convertedUrl = UrlUtil.convert(result.getUrl().v());
            boolean hasCredential = BridgeTokens.markReadyIfCredentialPresent(site, body, result);
            if (BridgeTokens.shouldPrompt(site, body, result, convertedUrl) && !hasCredential) {
                object.addProperty("code", "token_required");
                object.add("prompt", BridgeTokens.prompt(site, body, result));
                return uiStatus(object, "failed", false, result.getMsg().isEmpty() ? "Android Jar 登录未完成" : result.getMsg());
            }
            BridgeTokens.markReadyIfAuthenticated(site, body, result);
            JsonObject resultObject = result(result);
            resultObject.addProperty("status", "completed");
            resultObject.addProperty("done", true);
            if (!resultObject.has("mode")) resultObject.addProperty("mode", "message");
            return resultObject;
        } catch (CancellationException e) {
            return uiStatus(object, "cancelled", false, "Android Jar 操作已取消或超时");
        } catch (ExecutionException e) {
            object.addProperty("code", "jar_ui_failed");
            return uiStatus(object, "failed", false, safeMessage(e.getCause() == null ? e : e.getCause()));
        } catch (Throwable e) {
            object.addProperty("code", "jar_ui_failed");
            return uiStatus(object, "failed", false, safeMessage(e));
        }
    }

    private JsonObject uiStatus(JsonObject body) {
        JsonObject object = ok();
        object.addProperty("mode", "androidJarUi");
        boolean includeUi = bool(body, "includeUi", false);
        FutureTask<Result> task = uiTask;
        if (task == null) return uiStatus(object, "idle", false, "Android Jar 界面桥接未开始");
        if (task.isCancelled()) return maybeCopyUi(uiStatus(object, "cancelled", false, "Android Jar 操作已取消或超时"), includeUi);
        if (BridgeJarUi.hasJarUi()) return maybeCopyUi(uiStatus(object, "waiting", false, "等待 Android Jar 界面操作完成..."), includeUi);
        if (!task.isDone()) return maybeCopyUi(uiStatus(object, "waiting", false, "等待 Android Jar 操作完成..."), includeUi);
        JsonObject result = uiTaskResult(uiSite, uiBody, task);
        clearFinishedUiTask(task);
        return maybeCopyUi(result, includeUi);
    }

    private JsonObject uiStatus(JsonObject object, String status, boolean done, String message) {
        object.addProperty("status", status);
        object.addProperty("done", done);
        object.addProperty("message", message);
        return object;
    }

    private JsonObject uiAction(JsonObject body) {
        String action = string(body, "action");
        JsonObject object;
        switch (action) {
            case "click":
                object = BridgeJarUi.click(string(body, "elementId"), number(body, "x", -1), number(body, "y", -1));
                break;
            case "input":
                object = BridgeJarUi.input(string(body, "elementId"), string(body, "text"));
                break;
            case "submit":
                object = BridgeJarUi.submit(string(body, "elementId"));
                break;
            case "back":
                object = BridgeJarUi.back();
                break;
            case "refresh":
                object = BridgeJarUi.snapshot();
                break;
            case "cancel":
                object = uiClose();
                break;
            default:
                object = error("bad_ui_action", "Unsupported Android Jar UI action: " + action);
                break;
        }
        return object;
    }

    private JsonObject uiClose() {
        JsonObject object = ok();
        object.addProperty("mode", "androidJarUi");
        object.addProperty("dismissed", BridgeJarUi.dismiss());
        object.addProperty("message", "已关闭 Android Jar 界面桥接");
        BridgeTokens.markReadyIfCredentialPresent(uiSite, uiBody, null);
        cancelUiTask();
        return object;
    }

    private JsonObject copyUi(JsonObject object, JsonObject ui) {
        if (ui != null && ui.has("toast")) object.add("toast", ui.get("toast"));
        if (!bool(ui, "ok", false)) return object;
        object.addProperty("image", string(ui, "image"));
        object.addProperty("width", number(ui, "width", 0));
        object.addProperty("height", number(ui, "height", 0));
        if (ui.has("elements")) object.add("elements", ui.get("elements"));
        return object;
    }

    private JsonObject maybeCopyUi(JsonObject object, boolean includeUi) {
        return includeUi ? copyUi(object, BridgeJarUi.snapshot()) : object;
    }

    private void cancelUiTask() {
        if (uiTask != null) uiTask.cancel(true);
        if (uiFuture != null) uiFuture.cancel(true);
        uiTask = null;
        uiFuture = null;
        uiSite = null;
        uiBody = new JsonObject();
        uiAction = "";
    }

    private void clearFinishedUiTask(FutureTask<Result> task) {
        if (task != null && task == uiTask && task.isDone()) {
            uiTask = null;
            uiFuture = null;
            uiSite = null;
            uiBody = new JsonObject();
            uiAction = "";
        }
    }

    private JsonObject playResult(IHTTPSession session, Site site, JsonObject body, Result result) {
        String convertedUrl = UrlUtil.convert(result.getUrl().v());
        Map<String, String> headers = new HashMap<>(result.getHeader());
        boolean needsParse = result.needParse() || result.shouldUseParse();
        String jxFrom = result.getJxFrom();
        JsonObject object = ok();
        if (BridgeTokens.shouldPrompt(site, body, result, convertedUrl)) {
            object.addProperty("mode", "tokenRequired");
            object.add("prompt", BridgeTokens.prompt(site, body, result));
            return objectError(object, "token_required", result.getMsg().isEmpty() ? "当前网盘播放需要先配置 Token 或 Cookie" : result.getMsg());
        }
        if (needsParse) {
            ParseResolution resolution = resolveByAndroidParse(result);
            if (resolution == null || TextUtils.isEmpty(resolution.url)) return objectError(object, "parse_required", "Android 解析器未能产出可播放地址");
            convertedUrl = UrlUtil.convert(resolution.url);
            if (!resolution.headers.isEmpty()) headers = resolution.headers;
            jxFrom = resolution.from;
            needsParse = false;
        }
        boolean hasHeaders = !headers.isEmpty();
        boolean needsHeaders = false;
        String url = hasHeaders ? mediaProxyUrl(session, convertedUrl, headers) : externalize(session, convertedUrl);
        object.addProperty("mode", needsParse || needsHeaders ? "proxyRequired" : "direct");
        object.addProperty("url", url);
        object.add("headers", App.gson().toJsonTree(headers));
        object.addProperty("format", result.getFormat());
        object.addProperty("parse", result.getParse());
        object.addProperty("flag", result.getFlag());
        object.addProperty("jxFrom", jxFrom);
        object.addProperty("expiresAt", 0);
        object.add("subtitles", App.gson().toJsonTree(result.getSubs()));
        object.add("danmakus", App.gson().toJsonTree(result.getDanmaku()));
        if (needsHeaders) return objectError(object, "headers_required", "当前播放结果需要请求头，iOS 第一版播放器尚未接入 headers，请启用 Bridge 媒体代理后播放");
        if (url.isEmpty()) return objectError(object, "empty_url", "播放地址为空");
        BridgeTokens.markReadyIfAuthenticated(site, body, result);
        return object;
    }

    private ParseResolution resolveByAndroidParse(Result result) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParseResolution> parsed = new AtomicReference<>();
        AtomicReference<ParseJob> jobRef = new AtomicReference<>();
        ParseCallback callback = new ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> headers, String url, String from) {
                parsed.set(new ParseResolution(url, headers, from));
                latch.countDown();
            }

            @Override
            public void onParseError() {
                latch.countDown();
            }
        };
        try {
            ParseJob job = ParseJob.create(callback).start(result, result.shouldUseParse());
            jobRef.set(job);
            if (!latch.await(35, TimeUnit.SECONDS)) {
                job.stop();
                return null;
            }
            return parsed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ParseJob job = jobRef.get();
            if (job != null) job.stop();
            return null;
        } catch (Throwable e) {
            ParseJob job = jobRef.get();
            if (job != null) job.stop();
            return null;
        }
    }

    private static class ParseResolution {
        private final String url;
        private final Map<String, String> headers;
        private final String from;

        private ParseResolution(String url, Map<String, String> headers, String from) {
            this.url = url;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
            this.from = TextUtils.isEmpty(from) ? "" : from;
        }
    }

    private JsonObject result(Result result) {
        JsonObject object = JsonParser.parseString(result.toString()).getAsJsonObject();
        object.addProperty("ok", true);
        return object;
    }

    private Site site(String key) {
        Site site = BridgeSites.getRegisteredSite(key);
        if (!site.isEmpty()) return site;
        return VodConfig.get().getSite(key);
    }

    private void activateSite(Site site) {
        Site current = VodConfig.get().getSite(site.getKey());
        if (current.isEmpty()) return;
        if (current.getKey().equals(VodConfig.get().getHome().getKey())) return;
        VodConfig.get().setHome(current);
    }

    private synchronized void ensureVodConfigLoaded(String configUrl) {
        configUrl = normalizeUrlForAndroid(configUrl);
        if (TextUtils.isEmpty(configUrl)) return;
        if (!VodConfig.get().getSites().isEmpty() && (configUrl.equals(BridgeSites.getConfigUrl()) || configUrl.equals(VodConfig.getUrl()))) return;
        VodConfig.get().clear().config(Config.find(configUrl, 0)).ensureLoaded();
    }

    private void ensureSharedStorageReady() {
        try {
            new File("/storage/emulated/0/FM").mkdirs();
            new File("/sdcard/FM").mkdirs();
        } catch (Throwable ignored) {
        }
    }

    private JsonObject sharedStorageError() {
        ensureSharedStorageReady();
        if (!canWriteLegacyStorage()) {
            openStoragePermissionSettings();
            return error("storage_permission_required", "Android 端需要允许 TVBox 管理所有文件，否则网盘 Jar 无法保存登录态。已尝试打开 Android 授权页，请授权后再重试。");
        }
        return null;
    }

    private boolean canWriteLegacyStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return false;
        File probe = new File("/storage/emulated/0/FM", ".bridge_write_probe");
        try {
            if (probe.exists() && !probe.delete()) return false;
            if (!probe.createNewFile()) return false;
            return probe.delete() || !probe.exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openStoragePermissionSettings() {
        App.post(() -> {
            Activity activity = App.activity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + App.get().getPackageName()));
            if (intent.resolveActivity(App.get().getPackageManager()) == null) intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
            } catch (Throwable ignored) {
            }
        });
    }

    private String normalizeUrlForAndroid(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            URL url = new URL(value);
            String host = url.getHost();
            if (TextUtils.isEmpty(host)) return value;
            String asciiHost = IDN.toASCII(host);
            if (host.equals(asciiHost)) return value;
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), asciiHost, url.getPort(), url.getPath(), url.getQuery(), url.getRef());
            return uri.toASCIIString();
        } catch (Throwable ignored) {
            return value;
        }
    }

    private List<Site> allSites() {
        List<Site> sites = BridgeSites.getRegisteredSites();
        for (Site site : VodConfig.get().getSites()) if (!sites.contains(site)) sites.add(site);
        return sites;
    }

    private boolean hasRegisteredSites(JsonObject body) {
        return body != null && body.has("sites") && body.get("sites").isJsonArray() && body.getAsJsonArray("sites").size() > 0;
    }

    private boolean inVodConfig(Site site) {
        return !VodConfig.get().getSite(site.getKey()).isEmpty();
    }

    private JsonObject body(Map<String, String> files) {
        String postData = normalizePostData(files.get("postData"));
        if (TextUtils.isEmpty(postData)) return new JsonObject();
        JsonElement element = JsonParser.parseString(postData);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private String normalizePostData(String value) {
        if (TextUtils.isEmpty(value) || !looksLikeLatin1Utf8(value)) return value;
        return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private boolean looksLikeLatin1Utf8(String value) {
        for (int i = 0; i < value.length() - 1; i++) {
            char lead = value.charAt(i);
            char next = value.charAt(i + 1);
            if (lead >= 0xC2 && lead <= 0xF4 && next >= 0x80 && next <= 0xBF) return true;
        }
        return false;
    }

    private Response json(JsonObject object) {
        Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, object.toString());
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        return response;
    }

    private JsonObject ok() {
        JsonObject object = new JsonObject();
        object.addProperty("ok", true);
        return object;
    }

    private JsonObject error(String code, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("ok", false);
        object.addProperty("code", code);
        object.addProperty("message", message);
        return object;
    }

    private JsonObject objectError(JsonObject object, String code, String message) {
        object.addProperty("ok", false);
        object.addProperty("code", code);
        object.addProperty("message", message);
        return object;
    }

    private JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return new JsonObject();
        return object.getAsJsonObject(key);
    }

    private String first(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private String string(JsonObject object, String key) {
        String decoded = base64String(object, key + "Base64");
        if (!decoded.isEmpty()) return decoded;
        return rawString(object, key);
    }

    private String rawString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String base64String(JsonObject object, String key) {
        String value = rawString(object, key);
        if (value.isEmpty()) return "";
        try {
            return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private float number(JsonObject object, String key, float fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsFloat();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String decode(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    }

    private boolean isBridgeProxy(String url) {
        return url.startsWith("http://127.0.0.1:" + Proxy.getPort() + "/proxy") || isLocalHttp(url);
    }

    private String externalize(IHTTPSession session, String url) {
        String local = "http://127.0.0.1:" + Proxy.getPort();
        if (url.startsWith(local)) return externalBase(session) + url.substring(local.length());
        return isLocalHttp(url) ? externalBase(session) + LOCAL_PROXY_PREFIX + encodeLocalUrl(url) : url;
    }

    private String externalBase(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        return TextUtils.isEmpty(host) ? Server.get().getAddress(false) : "http://" + host;
    }

    private Response localProxy(IHTTPSession session) {
        String target = localProxyTarget(session);
        if (TextUtils.isEmpty(target) || !isLocalHttp(target)) return Nano.error(Status.BAD_REQUEST, "Bridge local proxy only accepts Android localhost HTTP URLs");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) localUrl(target).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod(session.getMethod().name());
            copyRequestHeaders(session, connection);
            return proxyResponse(connection, target);
        } catch (Throwable e) {
            if (connection != null) connection.disconnect();
            return Nano.error(safeMessage(e));
        }
    }

    private String mediaProxyUrl(IHTTPSession session, String target, Map<String, String> headers) {
        if (TextUtils.isEmpty(target)) return "";
        cleanupMediaTargets();
        String token = UUID.randomUUID().toString().replace("-", "");
        MEDIA_TARGETS.put(token, new MediaTarget(target, headers, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(3)));
        return externalBase(session) + MEDIA_PROXY_PREFIX + token;
    }

    private Response mediaProxy(IHTTPSession session) {
        String token = session.getUri().trim().substring(MEDIA_PROXY_PREFIX.length());
        int slash = token.indexOf('/');
        if (slash >= 0) token = token.substring(0, slash);
        MediaTarget target = MEDIA_TARGETS.get(token);
        if (target == null || target.expired()) return Nano.error(Status.NOT_FOUND, "Bridge media proxy target expired");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) localUrl(target.url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod(session.getMethod().name());
            copyRequestHeaders(session, connection);
            for (Map.Entry<String, String> entry : target.headers.entrySet()) {
                if (!TextUtils.isEmpty(entry.getKey()) && !TextUtils.isEmpty(entry.getValue())) connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            return proxyResponse(connection, target.url);
        } catch (Throwable e) {
            if (connection != null) connection.disconnect();
            return Nano.error(safeMessage(e));
        }
    }

    private Response proxyResponse(HttpURLConnection connection, String target) throws IOException {
        int code = connection.getResponseCode();
        String contentRange = connection.getHeaderField("Content-Range");
        if (!TextUtils.isEmpty(contentRange) && code == HttpURLConnection.HTTP_OK) code = HttpURLConnection.HTTP_PARTIAL;
        String mime = inferMime(target, connection.getContentType());
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) stream = new ByteArrayInputStream(new byte[0]);
        stream = new DisconnectingInputStream(stream, connection);
        long length = contentLength(connection, contentRange);
        Status status = Status.lookup(code) == null ? Status.OK : Status.lookup(code);
        Response response = length >= 0
                ? NanoHTTPD.newFixedLengthResponse(status, mime, stream, length)
                : NanoHTTPD.newChunkedResponse(status, mime, stream);
        copyResponseHeaders(connection, response);
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    private long contentLength(HttpURLConnection connection, String contentRange) {
        long rangeLength = contentRangeLength(contentRange);
        return rangeLength >= 0 ? rangeLength : connection.getHeaderFieldLong("Content-Length", -1);
    }

    private long contentRangeLength(String contentRange) {
        if (TextUtils.isEmpty(contentRange)) return -1;
        try {
            int space = contentRange.indexOf(' ');
            int dash = contentRange.indexOf('-', space + 1);
            int slash = contentRange.indexOf('/', dash + 1);
            if (space < 0 || dash < 0 || slash < 0) return -1;
            long start = Long.parseLong(contentRange.substring(space + 1, dash).trim());
            long end = Long.parseLong(contentRange.substring(dash + 1, slash).trim());
            return end >= start ? end - start + 1 : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String inferMime(String target, String mime) {
        String lowerMime = TextUtils.isEmpty(mime) ? "" : mime.toLowerCase(Locale.ROOT);
        if (!TextUtils.isEmpty(mime) && !lowerMime.contains("application/octet-stream") && !lowerMime.contains("application/oct-stream")) return mime;
        String lowerTarget = target == null ? "" : target.toLowerCase(Locale.ROOT);
        int query = lowerTarget.indexOf('?');
        if (query >= 0) lowerTarget = lowerTarget.substring(0, query);
        if (lowerTarget.endsWith(".mp4") || lowerTarget.endsWith(".m4v")) return "video/mp4";
        if (lowerTarget.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (lowerTarget.endsWith(".ts")) return "video/mp2t";
        if (lowerTarget.endsWith(".mov")) return "video/quicktime";
        if (lowerTarget.endsWith(".webm")) return "video/webm";
        if (lowerTarget.endsWith(".mkv")) return "video/x-matroska";
        return TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
    }

    private void cleanupMediaTargets() {
        long now = System.currentTimeMillis();
        MEDIA_TARGETS.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private void copyRequestHeaders(IHTTPSession session, HttpURLConnection connection) {
        for (Map.Entry<String, String> entry : session.getHeaders().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value) || skipRequestHeader(key)) continue;
            connection.setRequestProperty(key, value);
        }
    }

    private boolean skipRequestHeader(String key) {
        String lower = key.toLowerCase();
        return "host".equals(lower) || "connection".equals(lower) || "content-length".equals(lower) || "accept-encoding".equals(lower) || lower.startsWith("http-client") || lower.startsWith("remote-");
    }

    private void copyResponseHeaders(HttpURLConnection connection, Response response) {
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            String key = entry.getKey();
            if (TextUtils.isEmpty(key) || skipResponseHeader(key)) continue;
            for (String value : entry.getValue() == null ? Collections.<String>emptyList() : entry.getValue()) if (!TextUtils.isEmpty(value)) response.addHeader(key, value);
        }
    }

    private boolean skipResponseHeader(String key) {
        String lower = key.toLowerCase();
        return "connection".equals(lower) || "transfer-encoding".equals(lower) || "content-type".equals(lower) || "content-length".equals(lower);
    }

    private boolean isLocalHttp(String url) {
        String scheme = UrlUtil.scheme(url);
        String host = UrlUtil.host(url);
        return "http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host));
    }

    private String localProxyTarget(IHTTPSession session) {
        String uri = session.getUri().trim();
        if (uri.startsWith(LOCAL_PROXY_PREFIX)) return decodeLocalUrl(uri.substring(LOCAL_PROXY_PREFIX.length()));
        String target = session.getParms().get("url");
        return target == null ? "" : target;
    }

    private URL localUrl(String target) throws Exception {
        URL url = new URL(target);
        try {
            return url.toURI().toURL();
        } catch (Throwable ignored) {
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), null, url.getRef());
            String value = uri.toASCIIString();
            if (!TextUtils.isEmpty(url.getQuery())) value += "?" + url.getQuery().replace(" ", "%20");
            return new URL(value);
        }
    }

    private String encodeLocalUrl(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String decodeLocalUrl(String value) {
        try {
            return new String(Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    private String safeMessage(Throwable e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private static class DisconnectingInputStream extends FilterInputStream {

        private final HttpURLConnection connection;

        private DisconnectingInputStream(InputStream input, HttpURLConnection connection) {
            super(input);
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    private static class MediaTarget {
        private final String url;
        private final Map<String, String> headers;
        private final long expiresAt;

        private MediaTarget(String url, Map<String, String> headers, long expiresAt) {
            this.url = url;
            this.headers = new HashMap<>(headers);
            this.expiresAt = expiresAt;
        }

        private boolean expired() {
            return expiresAt < System.currentTimeMillis();
        }
    }
}
