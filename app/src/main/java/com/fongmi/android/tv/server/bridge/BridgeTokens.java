package com.fongmi.android.tv.server.bridge;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.preference.PreferenceManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;

public class BridgeTokens {

    private static final String TAG = "BridgeTokens";
    private static final List<String> NAMED_PREFS = Arrays.asList("userData", "bridge_tokens", "tokens");

    public static boolean shouldPromptBeforePlay(Site site, JsonObject request) {
        if (!looksLikeCloud(site, request, "")) return false;
        String provider = provider(site, request, "");
        return !hasBridgeCredential(provider);
    }

    public static boolean shouldPromptOnError(Site site, JsonObject request, Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return looksLikeCloud(site, request, message);
    }

    public static boolean shouldPrompt(Site site, JsonObject request, Result result, String url) {
        if (isTokenText(result.getMsg())) return true;
        if (looksLikeEpisodePayload(url)) return true;
        return TextUtils.isEmpty(url) && looksLikeCloud(site, request, result.getMsg());
    }

    public static JsonObject prompt(Site site, JsonObject request, Result result) {
        String provider = provider(site, request, result.getMsg() + " " + result.getUrl().v());
        String message = result.getMsg();
        if (TextUtils.isEmpty(message)) message = label(provider) + " 需要先配置 Token 或 Cookie";
        return prompt(site, request, provider, message);
    }

    public static JsonObject promptForProvider(Site site, String provider, String message) {
        return promptForProvider(site, provider, message, "");
    }

    public static JsonObject promptForProvider(Site site, String provider, String message, String action) {
        if (TextUtils.isEmpty(message)) message = label(provider) + " 需要先配置 Token 或 Cookie";
        JsonObject request = new JsonObject();
        if (!TextUtils.isEmpty(action)) request.addProperty("action", action);
        return prompt(site, request, provider, message);
    }

    public static JsonArray loginPrompts(Site site) {
        JsonArray prompts = new JsonArray();
        prompts.add(promptForProvider(site, "quark", "请在 Android Jar 界面完成授权，Cookie 会保存到 Android Bridge 运行时", "LoginShow"));
        prompts.add(promptForProvider(site, "uc", "请在 Android Jar 界面完成授权，Cookie 会保存到 Android Bridge 运行时", "LoginShow"));
        prompts.add(promptForProvider(site, "ali", "登录或粘贴 Token 后，会保存到 Android Bridge 运行时", "LoginShow"));
        prompts.add(promptForProvider(site, "baidu", "登录或粘贴 Cookie 后，会保存到 Android Bridge 运行时", "LoginShow"));
        prompts.add(promptForProvider(site, "115", "粘贴 115 Cookie 后，会保存到 Android Bridge 运行时", "LoginShow"));
        prompts.add(promptForProvider(site, "123pan", "粘贴 123 网盘 Token 或 Cookie 后，会保存到 Android Bridge 运行时", "LoginShow"));
        return prompts;
    }

    private static JsonObject prompt(Site site, JsonObject request, String provider, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("provider", provider);
        object.addProperty("title", label(provider) + " Token");
        object.addProperty("message", message);
        object.addProperty("submitPath", "/api/v1/site/" + site.getKey() + "/token");
        String action = first(request, "action", "act");
        if (!TextUtils.isEmpty(action)) object.addProperty("action", action);
        JsonObject login = login(site, provider);
        if (login != null) object.add("login", login);
        JsonArray fields = new JsonArray();
        JsonObject field = new JsonObject();
        field.addProperty("key", "token");
        field.addProperty("label", "Token / Cookie");
        field.addProperty("placeholder", placeholder(provider));
        field.addProperty("secure", true);
        field.addProperty("multiline", true);
        fields.add(field);
        object.add("fields", fields);
        JsonObject retry = new JsonObject();
        retry.addProperty("flag", string(request, "flag"));
        retry.addProperty("id", first(request, "id", "url"));
        retry.addProperty("action", action);
        object.add("retry", retry);
        return object;
    }

    public static JsonObject clear(String provider) {
        Set<String> removedKeys = new LinkedHashSet<>();
        for (String key : keys(provider)) {
            remove(key);
            removedKeys.add(key);
        }
        for (String key : credentialKeys(provider)) {
            remove(key);
            removedKeys.add(key);
        }
        for (String key : cookieCredentialKeys(provider)) {
            remove(key);
            removedKeys.add(key);
        }
        remove(markerKey(provider));
        removedKeys.add(markerKey(provider));
        clearWebCookies(provider);
        cleanupGenericCloudCredentials();
        BaseLoader.get().clearSync();

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("mode", "message");
        result.addProperty("provider", provider);
        result.addProperty("message", label(provider) + "授权已从 Android Bridge 运行时清除");
        JsonArray keys = new JsonArray();
        for (String key : removedKeys) keys.add(key);
        result.add("keys", keys);
        return result;
    }

    public static JsonObject save(Site site, JsonObject body) {
        String provider = string(body, "provider");
        if (TextUtils.isEmpty(provider)) provider = provider(site, body, "");
        String token = first(body, "token", "cookie", "value");
        JsonObject values = object(body, "values");
        if (TextUtils.isEmpty(token) && values.entrySet().isEmpty()) return error("empty_token", "Token 不能为空");
        if (isKnownProvider(provider) && !hasUsableSubmittedCredential(provider, token, values)) {
            return error("invalid_token", label(provider) + " Cookie 无效或未完成登录，请重新完成授权后再提取");
        }
        if (isKnownProvider(provider)) cleanupGenericCloudCredentials();

        Set<String> savedKeys = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(token)) {
            for (String key : keys(provider)) {
                write(key, token);
                savedKeys.add(key);
            }
            for (CookiePart part : cookieParts(token)) {
                write(part.name, part.value);
                savedKeys.add(part.name);
            }
            writeWebCookies(provider, token);
        }
        for (String key : values.keySet()) {
            String value = string(values, key);
            if (TextUtils.isEmpty(value)) continue;
            if (isKnownProvider(provider) && isGenericCredentialKey(key)) continue;
            write(key, value);
            savedKeys.add(key);
            if (looksLikeCookie(value)) {
                for (CookiePart part : cookieParts(value)) {
                    write(part.name, part.value);
                    savedKeys.add(part.name);
                }
                writeWebCookies(provider, value);
            }
        }
        Log.i(TAG, "saved provider=" + provider + " keys=" + savedKeys.size());
        write(markerKey(provider), "1");
        savedKeys.add(markerKey(provider));

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("provider", provider);
        result.addProperty("message", "Token 已写入 Android Bridge 运行时");
        JsonArray keys = new JsonArray();
        for (String key : savedKeys) keys.add(key);
        result.add("keys", keys);
        BaseLoader.get().clearSync();
        return result;
    }

    public static void invalidate(Site site, JsonObject request) {
        String provider = provider(site, request, "");
        if (isKnownProvider(provider)) clear(provider);
        else remove(markerKey(provider));
    }

    public static void markReady(String provider) {
        if (TextUtils.isEmpty(provider)) return;
        write(markerKey(provider), "1");
        BaseLoader.get().clearSync();
    }

    public static void markJarUiReady(String provider) {
        if (TextUtils.isEmpty(provider)) return;
        write(markerKey(provider), "android_jar_ui");
        BaseLoader.get().clearSync();
    }

    private static boolean hasBridgeCredential(String provider) {
        String marker = bridgeMarker(provider);
        if ("android_jar_ui".equals(marker)) return true;
        return hasSavedCredential(provider) || "1".equals(marker);
    }

    private static String bridgeMarker(String provider) {
        String key = markerKey(provider);
        for (SharedPreferences pref : prefs()) {
            String value = read(pref, key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String markerKey(String provider) {
        return "bridge_auth_ready_" + provider;
    }

    private static boolean hasSavedCredential(String provider) {
        for (SharedPreferences pref : prefs()) {
            for (String key : credentialKeys(provider)) {
                String value = read(pref, key);
                if (isUsableCredential(provider, value)) {
                    expandCredential(provider, value);
                    return true;
                }
            }
        }
        return false;
    }

    private static void expandCredential(String provider, String value) {
        if (!looksLikeCookie(value)) return;
        for (CookiePart part : cookieParts(value)) write(part.name, part.value);
        writeWebCookies(provider, value);
    }

    private static String read(SharedPreferences pref, String key) {
        try {
            return pref.getString(key, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void write(String key, String value) {
        Prefers.put(key, value);
        for (SharedPreferences pref : prefs()) pref.edit().putString(key, value).commit();
    }

    private static void remove(String key) {
        Prefers.remove(key);
        for (SharedPreferences pref : prefs()) pref.edit().remove(key).commit();
    }

    private static void cleanupGenericCloudCredentials() {
        for (SharedPreferences pref : prefs()) {
            for (String key : Arrays.asList("token", "Token", "cookie", "Cookie", "value")) {
                String value = read(pref, key);
                if (looksLikeCloudCookie(value)) remove(key);
            }
        }
    }

    private static List<SharedPreferences> prefs() {
        List<SharedPreferences> prefs = new ArrayList<>();
        prefs.add(PreferenceManager.getDefaultSharedPreferences(App.get()));
        for (String name : NAMED_PREFS) prefs.add(App.get().getSharedPreferences(name, 0));
        return prefs;
    }

    private static List<String> keys(String provider) {
        switch (provider) {
            case "quark":
                return Arrays.asList("quark_cookie", "quarkCookie", "QuarkCookie", "quark", "Quark", "quark_token", "quarkToken", "QuarkToken");
            case "uc":
                return Arrays.asList("uc_cookie", "ucCookie", "UCCookie", "UC_COOKIE", "UC Cookie", "UC cookie", "uc", "UC", "uc_token", "ucToken", "UCToken", "UC_TOKEN", "UC Token", "refresh_token", "refreshToken");
            case "ali":
                return Arrays.asList("ali_token", "aliToken", "AliToken", "access_token", "accessToken", "refresh_token", "refreshToken");
            case "115":
                return Arrays.asList("115_cookie", "115Cookie", "115 cookie");
            case "123pan":
                return Arrays.asList("123pan_token", "123panCookie", "123pan_cookie");
            case "baidu":
                return Arrays.asList("baidu_cookie", "baiduCookie", "BaiduCookie", "bd_cookie", "BdCookie", "BDUSS", "STOKEN");
            default:
                return Arrays.asList("token", "cookie", "refresh_token", "refreshToken", "access_token", "accessToken");
        }
    }

    private static List<String> credentialKeys(String provider) {
        switch (provider) {
            case "quark":
                return Arrays.asList("quark_cookie", "quarkCookie", "QuarkCookie", "quark", "Quark", "quark_token", "quarkToken", "QuarkToken");
            case "uc":
                return Arrays.asList("uc_cookie", "ucCookie", "UCCookie", "UC_COOKIE", "UC Cookie", "UC cookie", "uc", "UC", "uc_token", "ucToken", "UCToken", "UC_TOKEN", "UC Token");
            case "ali":
                return Arrays.asList("ali_token", "aliToken", "AliToken");
            case "115":
                return Arrays.asList("115_cookie", "115Cookie", "115 cookie");
            case "123pan":
                return Arrays.asList("123pan_token", "123panCookie", "123pan_cookie");
            case "baidu":
                return Arrays.asList("baidu_cookie", "baiduCookie", "BaiduCookie", "bd_cookie", "BdCookie", "BDUSS", "STOKEN");
            default:
                return Arrays.asList("token", "cookie", "refresh_token", "refreshToken", "access_token", "accessToken");
        }
    }

    private static List<String> cookieCredentialKeys(String provider) {
        switch (provider) {
            case "quark":
            case "uc":
                return Arrays.asList("__puus", "__pus", "__uid", "__kp", "__kps", "_UP_A4A_11_", "_up_a4a_11_");
            case "ali":
                return Arrays.asList("refresh_token", "refreshToken", "access_token", "accessToken");
            case "baidu":
                return Arrays.asList("BDUSS", "STOKEN", "BAIDUID", "PANWEB", "Hm_lvt", "Hm_lpvt");
            default:
                return new ArrayList<>();
        }
    }

    private static boolean hasUsableSubmittedCredential(String provider, String token, JsonObject values) {
        if (isUsableCredential(provider, token)) return true;
        for (String key : values.keySet()) if (isUsableCredential(provider, string(values, key))) return true;
        return false;
    }

    private static boolean isUsableCredential(String provider, String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        switch (provider) {
            case "quark":
            case "uc":
                return lower.contains("__puus=") || lower.contains("__pus=");
            case "ali":
                return lower.contains("refresh_token") || lower.contains("access_token") || value.length() > 80;
            case "baidu":
                return lower.contains("bduss=") || lower.contains("stoken=") || lower.contains("baiduid=") || lower.contains("panweb=");
            case "115":
            case "123pan":
                return looksLikeCookie(value) || value.length() > 32;
            default:
                return !value.isEmpty();
        }
    }

    private static boolean isGenericCredentialKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT).trim();
        return "token".equals(lower) || "cookie".equals(lower) || "value".equals(lower);
    }

    private static boolean looksLikeCloudCookie(String value) {
        if (!looksLikeCookie(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("__puus=") || lower.contains("__pus=") || lower.contains("__kp=") || lower.contains("__kps=") || lower.contains("_up_a4a_11_=") || lower.contains("refresh_token=") || lower.contains("access_token=") || lower.contains("bduss=") || lower.contains("stoken=") || lower.contains("baiduid=");
    }

    private static JsonObject login(Site site, String provider) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "androidJarUi");
        object.addProperty("title", label(provider) + " Android Jar 登录");
        object.addProperty("url", loginUrl(provider));
        object.addProperty("cookieKey", "token");
        JsonArray domains = new JsonArray();
        for (String domain : loginDomains(provider)) domains.add(domain);
        object.add("domains", domains);
        return object;
    }

    private static String loginUrl(String provider) {
        switch (provider) {
            case "quark": return "https://pan.quark.cn/";
            case "uc": return "https://drive.uc.cn/";
            case "ali": return "https://www.alipan.com/";
            case "baidu": return "https://pan.baidu.com/";
            default: return "";
        }
    }

    private static List<String> loginDomains(String provider) {
        switch (provider) {
            case "quark": return Arrays.asList("quark.cn", "pan.quark.cn", "drive-pc.quark.cn");
            case "uc": return Arrays.asList("uc.cn", "drive.uc.cn");
            case "ali": return Arrays.asList("alipan.com", "aliyundrive.com");
            case "baidu": return Arrays.asList("baidu.com", "pan.baidu.com");
            default: return new ArrayList<>();
        }
    }

    private static boolean looksLikeCookie(String value) {
        return !TextUtils.isEmpty(value) && value.contains("=") && value.contains(";");
    }

    private static List<CookiePart> cookieParts(String cookie) {
        List<CookiePart> parts = new ArrayList<>();
        if (TextUtils.isEmpty(cookie)) return parts;
        for (String item : cookie.split(";")) {
            String pair = item.trim();
            int index = pair.indexOf('=');
            if (index <= 0) continue;
            String name = pair.substring(0, index).trim();
            String value = pair.substring(index + 1).trim();
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if ("path".equals(lower) || "domain".equals(lower) || "expires".equals(lower) || "max-age".equals(lower) || "secure".equals(lower) || "httponly".equals(lower) || "samesite".equals(lower)) continue;
            parts.add(new CookiePart(name, value));
        }
        return parts;
    }

    private static void writeWebCookies(String provider, String cookie) {
        if (!looksLikeCookie(cookie)) return;
        try {
            CookieManager manager = CookieManager.getInstance();
            for (String url : cookieUrls(provider)) for (CookiePart part : cookieParts(cookie)) manager.setCookie(url, part.name + "=" + part.value + "; Path=/");
            manager.flush();
        } catch (Throwable e) {
            Log.w(TAG, "write web cookies failed: " + e.getClass().getSimpleName());
        }
    }

    private static void clearWebCookies(String provider) {
        try {
            CookieManager manager = CookieManager.getInstance();
            for (String url : cookieUrls(provider)) {
                for (String key : cookieCredentialKeys(provider)) {
                    manager.setCookie(url, key + "=; Path=/; Max-Age=0");
                }
            }
            manager.flush();
        } catch (Throwable e) {
            Log.w(TAG, "clear web cookies failed: " + e.getClass().getSimpleName());
        }
    }

    private static List<String> cookieUrls(String provider) {
        switch (provider) {
            case "quark": return Arrays.asList("https://pan.quark.cn/", "https://drive-pc.quark.cn/", "https://quark.cn/");
            case "uc": return Arrays.asList("https://drive.uc.cn/", "https://uc.cn/");
            case "ali": return Arrays.asList("https://www.alipan.com/", "https://www.aliyundrive.com/");
            case "baidu": return Arrays.asList("https://pan.baidu.com/", "https://www.baidu.com/");
            default: return new ArrayList<>();
        }
    }

    private static boolean looksLikeCloud(Site site, JsonObject request, String message) {
        String text = joined(site, request, message);
        return text.contains("网盘") || text.contains("云盘") || looksLikeQuark(text) || looksLikeUc(text) || text.contains("阿里") || text.contains("ali") || text.contains("百度") || text.contains("baidu") || text.contains("115") || text.contains("123pan") || text.contains("pan.") || text.contains("drive.");
    }

    private static boolean isTokenText(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String value = text.toLowerCase(Locale.ROOT);
        return value.contains("token") || value.contains("cookie") || value.contains("登录") || value.contains("扫码") || value.contains("授权") || value.contains("配置") || value.contains("失效");
    }

    private static boolean looksLikeEpisodePayload(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = url.trim().toLowerCase(Locale.ROOT);
        if (!(value.startsWith("{") || value.startsWith("["))) return false;
        return value.contains("\"fid\"") || value.contains("\"sid\"") || value.contains("\"stoken\"") || value.contains("\"share_fid_token\"") || value.contains("\"flag\"");
    }

    private static boolean isKnownProvider(String provider) {
        return "quark".equals(provider) || "uc".equals(provider) || "ali".equals(provider) || "115".equals(provider) || "123pan".equals(provider) || "baidu".equals(provider);
    }

    private static String provider(Site site, JsonObject request, String message) {
        String text = joined(site, request, message);
        if (looksLikeUc(text)) return "uc";
        if (looksLikeQuark(text)) return "quark";
        if (text.contains("ali") || text.contains("阿里") || text.contains("alipan") || text.contains("aliyundrive")) return "ali";
        if (text.contains("百度") || text.contains("baidu") || text.contains("pan.baidu")) return "baidu";
        if (text.contains("115")) return "115";
        if (text.contains("123pan") || text.contains("123盘")) return "123pan";
        return "cloud";
    }

    private static boolean looksLikeQuark(String text) {
        return text.contains("quark") || text.contains("夸克") || text.contains("夸父") || text.contains("\"sid\"") || text.contains("\"stoken\"") || text.contains("\"share_fid_token\"") || text.contains("\"pdir_fid\"");
    }

    private static boolean looksLikeUc(String text) {
        return text.contains("drive.uc.cn") || text.contains("uc网盘") || text.contains("uc 网盘") || text.contains("uc盘") || text.contains("uc原") || text.contains("uc智") || text.contains("uc_cookie") || text.contains("uccookie") || text.contains("uc_token");
    }

    private static String joined(Site site, JsonObject request, String message) {
        return (site.getKey() + " " + site.getName() + " " + site.getApi() + " " + string(request, "flag") + " " + first(request, "id", "url") + " " + message).toLowerCase(Locale.ROOT);
    }

    private static String label(String provider) {
        switch (provider) {
            case "quark": return "夸克网盘";
            case "uc": return "UC 网盘";
            case "ali": return "阿里云盘";
            case "baidu": return "百度网盘";
            case "115": return "115 网盘";
            case "123pan": return "123 网盘";
            default: return "网盘";
        }
    }

    private static String placeholder(String provider) {
        switch (provider) {
            case "uc": return "粘贴 UC Token 或 Cookie";
            case "quark": return "粘贴夸克 Cookie 或 Token";
            case "ali": return "粘贴阿里 refresh_token / access_token";
            case "baidu": return "粘贴百度 Cookie";
            default: return "粘贴网盘 Token / Cookie";
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("ok", false);
        object.addProperty("code", code);
        object.addProperty("message", message);
        return object;
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return new JsonObject();
        return object.getAsJsonObject(key);
    }

    private static String first(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String string(JsonObject object, String key) {
        String decoded = base64String(object, key + "Base64");
        if (!decoded.isEmpty()) return decoded;
        return rawString(object, key);
    }

    private static String rawString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement element = object.get(key);
        try {
            return element.getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String base64String(JsonObject object, String key) {
        String value = rawString(object, key);
        if (TextUtils.isEmpty(value)) return "";
        try {
            return new String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static class CookiePart {
        private final String name;
        private final String value;

        private CookiePart(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
