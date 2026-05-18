package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.bridge.BridgeQr;
import com.fongmi.android.tv.server.bridge.BridgeSites;
import com.fongmi.android.tv.server.bridge.BridgeTokens;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.Spider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Bridge implements Process {

    private static final String MIME_JSON = "application/json; charset=utf-8";
    private static final String LOCAL_PROXY_PREFIX = "/bridge/local/";
    private static final String MEDIA_PROXY_PREFIX = "/bridge/media/";
    private static final ConcurrentHashMap<String, MediaTarget> MEDIA_TARGETS = new ConcurrentHashMap<>();
    private FutureTask<Result> qrTask;
    private Thread qrThread;
    private String qrProvider = "";

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
        JsonObject runtimes = new JsonObject();
        runtimes.addProperty("jarDex", "ready");
        runtimes.addProperty("chaquopy", "ready");
        runtimes.addProperty("quickjs", "ready");
        object.add("runtimes", runtimes);
        return object;
    }

    private JsonObject register(JsonObject body) {
        String configUrl = string(body, "configUrl");
        ensureVodConfigLoaded(configUrl);
        String configId = BridgeSites.register(body);
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
        ensureVodConfigLoaded(BridgeSites.getConfigUrl());
        String[] parts = url.split("/");
        if (parts.length < 6) return error("bad_request", "Expected /api/v1/site/{siteKey}/{action}");
        String key = decode(parts[4]);
        String action = parts[5];
        Site site = site(key);
        if (site.isEmpty()) return error("site_not_found", "Bridge site not found: " + key);

        Result result;
        switch (action) {
            case "home":
                result = SiteApi.homeContent(site);
                return result(result);
            case "category":
                result = category(site, body);
                return result(result);
            case "detail":
                JsonObject detailAction = detailAction(site, body);
                if (detailAction != null) return detailAction;
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
            case "qrLogin":
                return qrLogin(site, body);
            case "qrStatus":
                return qrStatus(body);
            case "qrAction":
                return qrAction(body);
            case "qrConfirm":
                return qrConfirm(body);
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
        String flag = string(body, "flag");
        String id = first(body, "id", "url");
        if (BridgeTokens.shouldPromptBeforePlay(site, body)) {
            return playResult(session, site, body, Result.error("当前网盘播放需要先配置 Token 或 Cookie"));
        }
        Result result;
        try {
            result = playerContentWithTimeout(site, flag, id);
        } catch (TimeoutException e) {
            BridgeTokens.invalidate(site, body);
            return playResult(session, site, body, Result.error("当前网盘播放需要先完成 Bridge 授权或重新扫码登录"));
        }
        return playResult(session, site, body, result);
    }

    private Result playerContentWithTimeout(Site site, String flag, String id) throws Exception {
        FutureTask<Result> task = new FutureTask<>(() -> playerContent(site, flag, id));
        Thread thread = new Thread(task, "bridge-play-" + site.getKey());
        thread.start();
        try {
            return task.get(12, TimeUnit.SECONDS);
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

    private JsonObject action(Site site, JsonObject body) throws Exception {
        String action = string(body, "action");
        JsonObject bridgeAction = cloudAction(site, action);
        if (bridgeAction != null) return bridgeAction;
        if (inVodConfig(site)) return result(SiteApi.action(site.getKey(), action));
        String json = site.recent().spider().action(action);
        return result(Result.fromJson(json));
    }

    private JsonObject cloudAction(Site site, String action) {
        switch (action) {
            case "LoginShow":
            case "pushCkShow":
                JsonObject login = ok();
                login.addProperty("mode", "cloudLogin");
                login.addProperty("action", action);
                login.addProperty("message", "选择网盘登录方式，登录状态只保存到 Android Bridge");
                login.add("prompts", BridgeTokens.loginPrompts(site));
                return login;
            case "quarkClean":
                return BridgeTokens.clear("quark");
            case "ucClean":
                return BridgeTokens.clear("uc");
            case "aliClean":
                return BridgeTokens.clear("ali");
            case "BdClean":
                return BridgeTokens.clear("baidu");
            default:
                return null;
        }
    }

    private JsonObject qrLogin(Site site, JsonObject body) {
        String provider = provider(body);
        String flag = string(body, "flag");
        String id = first(body, "id", "url");
        boolean hadQrTask = qrTask != null;
        cancelQrTask();
        if (hadQrTask) BridgeQr.dismiss();
        FutureTask<Result> task = new FutureTask<>(() -> playerContent(site, flag, id));
        Thread thread = new Thread(task, "bridge-qr-" + site.getKey());
        qrTask = task;
        qrThread = thread;
        qrProvider = provider;
        thread.start();
        Thread cleaner = new Thread(() -> {
            try {
                task.get(120, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                task.cancel(true);
                thread.interrupt();
            }
        }, "bridge-qr-cleaner-" + site.getKey());
        cleaner.start();
        JsonObject result = BridgeQr.captureJarUi();
        result.addProperty("provider", provider);
        if (!bool(result, "ok", false)) {
            cancelQrTask();
        }
        return result;
    }

    private JsonObject qrStatus(JsonObject body) {
        JsonObject object = ok();
        object.addProperty("mode", "androidQr");
        String provider = activeQrProvider(body);
        object.addProperty("provider", provider);
        boolean includeUi = bool(body, "includeUi", false);
        FutureTask<Result> task = qrTask;
        JsonObject mismatch = qrProviderMismatch(body);
        if (mismatch != null) return mismatch;
        if (task == null) return qrStatus(object, "idle", false, "Android Jar 二维码登录未开始");
        if (task.isCancelled()) return maybeCopyUi(qrStatus(object, "cancelled", false, "Android Jar 二维码登录已取消或超时"), includeUi);
        if (!task.isDone()) return maybeCopyUi(qrStatus(object, "waiting", false, "等待 Android Jar 弹窗操作完成..."), includeUi);
        try {
            Result result = task.get();
            String url = result == null ? "" : UrlUtil.convert(result.getUrl().v());
            if (!TextUtils.isEmpty(url)) {
                BridgeTokens.markQrReady(provider);
                return qrStatus(object, "completed", true, "Android 端已完成扫码登录，正在重试播放...");
            }
            String message = result == null ? "" : result.getMsg();
            if (TextUtils.isEmpty(message)) message = "Android Jar 二维码登录未返回播放地址";
            object.addProperty("code", "qr_login_failed");
            return qrStatus(object, "failed", false, message);
        } catch (CancellationException e) {
            return qrStatus(object, "cancelled", false, "Android Jar 二维码登录已取消或超时");
        } catch (ExecutionException e) {
            object.addProperty("code", "qr_login_failed");
            return qrStatus(object, "failed", false, safeMessage(e.getCause() == null ? e : e.getCause()));
        } catch (Throwable e) {
            object.addProperty("code", "qr_login_failed");
            return qrStatus(object, "failed", false, safeMessage(e));
        }
    }

    private JsonObject qrStatus(JsonObject object, String status, boolean done, String message) {
        object.addProperty("status", status);
        object.addProperty("done", done);
        object.addProperty("message", message);
        return object;
    }

    private JsonObject qrAction(JsonObject body) {
        JsonObject mismatch = qrProviderMismatch(body);
        if (mismatch != null) return mismatch;
        String provider = activeQrProvider(body);
        String action = string(body, "action");
        JsonObject object;
        switch (action) {
            case "click":
                object = BridgeQr.click(string(body, "elementId"), number(body, "x", -1), number(body, "y", -1));
                break;
            case "input":
                object = BridgeQr.input(string(body, "elementId"), string(body, "text"));
                break;
            case "submit":
                object = BridgeQr.submit(string(body, "elementId"));
                break;
            case "back":
                object = BridgeQr.back();
                break;
            case "refresh":
                object = BridgeQr.snapshot();
                break;
            case "cancel":
                object = ok();
                object.addProperty("mode", "androidJarUi");
                object.addProperty("dismissed", BridgeQr.dismiss());
                object.addProperty("message", "已取消 Android Jar 弹窗登录");
                cancelQrTask();
                break;
            default:
                object = error("bad_qr_action", "Unsupported QR UI action: " + action);
                break;
        }
        object.addProperty("provider", provider);
        return object;
    }

    private JsonObject qrConfirm(JsonObject body) {
        String provider = activeQrProvider(body);
        JsonObject mismatch = qrProviderMismatch(body);
        if (mismatch != null) return mismatch;
        if (qrTask == null) return objectError(ok(), "qr_not_started", "Android Jar 弹窗登录未开始");
        JsonObject object = ok();
        object.addProperty("provider", provider);
        object.addProperty("dismissed", BridgeQr.dismiss());
        BridgeTokens.markQrReady(provider);
        cancelQrTask();
        object.addProperty("message", "已确认 Android Jar 二维码登录，准备重试播放");
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
        return includeUi ? copyUi(object, BridgeQr.snapshot()) : object;
    }

    private JsonObject qrProviderMismatch(JsonObject body) {
        String requested = provider(body);
        if (TextUtils.isEmpty(qrProvider) || requested.equals(qrProvider)) return null;
        JsonObject object = error("qr_provider_mismatch", "当前二维码任务属于 " + qrProvider + "，不能用 " + requested + " 确认或轮询");
        object.addProperty("provider", qrProvider);
        object.addProperty("requestedProvider", requested);
        object.addProperty("mode", "androidQr");
        return object;
    }

    private String activeQrProvider(JsonObject body) {
        if (!TextUtils.isEmpty(qrProvider)) return qrProvider;
        return provider(body);
    }

    private String provider(JsonObject body) {
        String provider = string(body, "provider").toLowerCase(Locale.ROOT);
        return TextUtils.isEmpty(provider) ? "quark" : provider;
    }

    private void cancelQrTask() {
        if (qrTask != null) qrTask.cancel(true);
        if (qrThread != null) qrThread.interrupt();
        qrTask = null;
        qrThread = null;
        qrProvider = "";
    }

    private JsonObject detailAction(Site site, JsonObject body) {
        if (!isCloudActionSite(site)) return null;
        String action = actionFromDetailId(first(body, "id", "vodId"));
        if (action.isEmpty()) return null;
        JsonObject bridgeAction = cloudAction(site, action);
        if (bridgeAction != null) return bridgeAction;
        JsonObject object = error("action_required", "当前云盘配置项需要新版客户端通过 Bridge action 执行");
        object.addProperty("action", action);
        return object;
    }

    private boolean isCloudActionSite(Site site) {
        String text = (site.getKey() + " " + site.getName() + " " + site.getApi()).toLowerCase();
        return text.contains("mdrive") || text.contains("mydrive") || text.contains("我的云盘") || text.contains("云盘");
    }

    private String actionFromDetailId(String id) {
        switch (id) {
            case "0000": return "LoginShow";
            case "6666": return "pushCkShow";
            case "3333": return "ucClean";
            case "2222": return "quarkClean";
            case "bddd": return "BdClean";
            case "1111": return "aliClean";
            case "4444": return "panSortShow";
            case "5555": return "panSourceSortShow";
            default: return "";
        }
    }

    private JsonObject playResult(IHTTPSession session, Site site, JsonObject body, Result result) {
        String convertedUrl = UrlUtil.convert(result.getUrl().v());
        Map<String, String> headers = result.getHeader();
        boolean hasHeaders = !headers.isEmpty();
        boolean bridgeProxy = isBridgeProxy(convertedUrl);
        String url = hasHeaders ? mediaProxyUrl(session, convertedUrl, headers) : externalize(session, convertedUrl);
        boolean needsParse = result.needParse() || result.shouldUseParse();
        boolean needsHeaders = false;
        JsonObject object = ok();
        object.addProperty("mode", needsParse || needsHeaders ? "proxyRequired" : "direct");
        object.addProperty("url", url);
        object.add("headers", App.gson().toJsonTree(headers));
        object.addProperty("format", result.getFormat());
        object.addProperty("parse", result.getParse());
        object.addProperty("flag", result.getFlag());
        object.addProperty("expiresAt", 0);
        object.add("subtitles", App.gson().toJsonTree(result.getSubs()));
        object.add("danmakus", App.gson().toJsonTree(result.getDanmaku()));
        if (BridgeTokens.shouldPrompt(site, body, result, url)) {
            object.addProperty("mode", "tokenRequired");
            object.add("prompt", BridgeTokens.prompt(site, body, result));
            return objectError(object, "token_required", result.getMsg().isEmpty() ? "当前网盘播放需要先配置 Token 或 Cookie" : result.getMsg());
        }
        if (needsParse) return objectError(object, "parse_required", "当前播放结果仍需要 Android Web/解析器处理，Bridge 自有解析代理尚未启用");
        if (needsHeaders) return objectError(object, "headers_required", "当前播放结果需要请求头，iOS 第一版播放器尚未接入 headers，请启用 Bridge 媒体代理后播放");
        if (url.isEmpty()) return objectError(object, "empty_url", "播放地址为空");
        return object;
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

    private synchronized void ensureVodConfigLoaded(String configUrl) {
        if (TextUtils.isEmpty(configUrl)) return;
        if (!VodConfig.get().getSites().isEmpty() && (configUrl.equals(BridgeSites.getConfigUrl()) || configUrl.equals(VodConfig.getUrl()))) return;
        VodConfig.get().clear().config(Config.find(configUrl, 0)).ensureLoaded();
    }

    private List<Site> allSites() {
        List<Site> sites = BridgeSites.getRegisteredSites();
        for (Site site : VodConfig.get().getSites()) if (!sites.contains(site)) sites.add(site);
        return sites;
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