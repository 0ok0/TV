package com.fongmi.android.tv.utils;

import android.os.Build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class AbiUtil {

    public static boolean isX86_64() {
        return supports("x86_64");
    }

    public static boolean supportsArmNativeExtractors() {
        return supports("arm64-v8a") || supports("armeabi-v7a");
    }

    public static JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("primary", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI);
        object.add("supported", supported());
        object.addProperty("x86_64", isX86_64());
        object.addProperty("armNativeExtractors", supportsArmNativeExtractors());
        JsonArray disabled = new JsonArray();
        if (!supportsArmNativeExtractors()) {
            disabled.add("forcetech");
            disabled.add("jianpian");
            disabled.add("thunder");
        }
        object.add("disabledExtractors", disabled);
        return object;
    }

    private static JsonArray supported() {
        JsonArray array = new JsonArray();
        for (String abi : Build.SUPPORTED_ABIS) array.add(abi);
        return array;
    }

    private static boolean supports(String abi) {
        for (String value : Build.SUPPORTED_ABIS) if (abi.equals(value)) return true;
        return abi.equals(Build.CPU_ABI) || abi.equals(Build.CPU_ABI2);
    }
}
