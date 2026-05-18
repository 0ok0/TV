package com.fongmi.android.tv.server.bridge;

import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BridgeSites {

    private static final ConcurrentHashMap<String, Site> SITES = new ConcurrentHashMap<>();
    private static volatile String configId = "";
    private static volatile String configUrl = "";
    private static volatile String spider = "";

    public static String register(JsonObject object) {
        String url = safeString(object, "configUrl");
        String spiderValue = safeString(object, "spider");
        if (safeBool(object, "replace", false)) {
            SITES.clear();
            configUrl = "";
            spider = "";
        }
        if (!url.isEmpty()) configUrl = url;
        if (!spiderValue.isEmpty()) spider = spiderValue;
        JsonArray sites = object.has("sites") && object.get("sites").isJsonArray() ? object.getAsJsonArray("sites") : new JsonArray();
        for (JsonElement element : sites) {
            Site site = Site.objectFrom(element, spiderValue.isEmpty() ? spider : spiderValue);
            if (!site.isEmpty()) SITES.put(site.getKey(), site);
        }
        String seed = !configUrl.isEmpty() ? configUrl : object.toString();
        configId = "md5:" + Util.md5(seed);
        return configId;
    }

    public static Site getRegisteredSite(String key) {
        Site site = SITES.get(key);
        return site == null ? new Site() : site;
    }

    public static List<Site> getRegisteredSites() {
        return new ArrayList<>(SITES.values());
    }

    public static String getConfigId() {
        return configId;
    }

    public static String getConfigUrl() {
        return configUrl;
    }

    public static JsonArray toJson(List<Site> sites) {
        JsonArray array = new JsonArray();
        for (Site site : sites) array.add(toJson(site));
        return array;
    }

    public static JsonObject toJson(Site site) {
        JsonObject object = new JsonObject();
        object.addProperty("key", site.getKey());
        object.addProperty("name", site.getName());
        object.addProperty("api", site.getApi());
        object.addProperty("type", site.getType());
        object.addProperty("searchable", site.getSearchable());
        object.addProperty("filterable", site.getChangeable());
        object.addProperty("quickSearch", site.getQuickSearch());
        object.addProperty("status", site.getType() == 3 ? "ready" : "local");
        return object;
    }

    public static Map<String, String> stringMap(JsonObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        if (object == null) return result;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) result.put(entry.getKey(), value.getAsString());
            else result.put(entry.getKey(), value.toString());
        }
        return result;
    }

    private static String safeString(JsonObject object, String key) {
        String decoded = safeBase64String(object, key + "Base64");
        if (!decoded.isEmpty()) return decoded;
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeBase64String(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            String value = object.get(key).getAsString().trim();
            if (value.isEmpty()) return "";
            return new String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean safeBool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
