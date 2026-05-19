package com.fongmi.android.tv.server.bridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BridgeQr {

    private static final long UI_TIMEOUT = 15000;

    public static JsonObject captureJarUi() {
        return captureJarUi("");
    }

    public static JsonObject captureJarUi(String provider) {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            JsonObject object = snapshot(true);
            if (bool(object, "ok")) return autoAdvance(provider, object);
            sleep(250);
        }
        return error("jar_ui_missing", "未找到 Android Jar 弹窗，请确认 Jar 登录窗口已出现");
    }

    public static JsonObject snapshot() {
        return snapshot(true);
    }

    public static boolean hasJarUi() {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        runOnMain(() -> result.set(selectRoot(true) != null));
        return result.get();
    }

    public static JsonObject click(String elementId, float x, float y) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        runOnMain(() -> {
            Root root = selectRoot(true);
            if (root == null) return;
            View view = findByPath(root.view, elementId);
            if (view != null) {
                View target = clickableTarget(view);
                if (target != null && target.performClick()) {
                    result.set(true);
                    return;
                }
            }
            if (x >= 0 && y >= 0) result.set(dispatchTap(root.view, x, y));
        });
        sleep(350);
        JsonObject object = actionResult(result.get(), "点按已发送到 Android Jar 弹窗");
        object.addProperty("acted", result.get());
        return object;
    }

    public static JsonObject input(String elementId, String text) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        runOnMain(() -> {
            Root root = selectRoot(true);
            if (root == null) return;
            View view = findByPath(root.view, elementId);
            EditText editText = findEditText(view != null ? view : root.view);
            if (editText == null) return;
            editText.requestFocus();
            editText.setText(text == null ? "" : text);
            editText.setSelection(editText.getText() == null ? 0 : editText.getText().length());
            result.set(true);
        });
        sleep(350);
        JsonObject object = actionResult(result.get(), "输入已发送到 Android Jar 弹窗");
        object.addProperty("acted", result.get());
        return object;
    }

    public static JsonObject submit(String elementId) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        runOnMain(() -> {
            Root root = selectRoot(true);
            if (root == null) return;
            View view = findByPath(root.view, elementId);
            View target = view != null ? view : focusedView(root.view);
            if (target == null) target = root.view;
            if (target instanceof TextView) ((TextView) target).onEditorAction(EditorInfo.IME_ACTION_DONE);
            result.set(dispatchKey(target, KeyEvent.KEYCODE_ENTER));
            if (!result.get() && App.activity() != null) result.set(dispatchKey(App.activity().getWindow().getDecorView(), KeyEvent.KEYCODE_ENTER));
        });
        sleep(350);
        JsonObject object = actionResult(result.get(), "确认已发送到 Android Jar 弹窗");
        object.addProperty("acted", result.get());
        return object;
    }

    public static JsonObject back() {
        boolean acted = dismiss();
        sleep(350);
        JsonObject object = actionResult(acted, "返回已发送到 Android Jar 弹窗");
        object.addProperty("acted", acted);
        return object;
    }

    public static boolean clickOk() {
        return waitAndClick(1500, "OK", "确定");
    }

    private static JsonObject autoAdvance(String provider, JsonObject object) {
        if (hasQr(object)) return object;
        if (!TextUtils.isEmpty(provider) && clickButton(providerAliases(provider))) {
            sleep(700);
            object = snapshot(true);
            if (hasQr(object)) return object;
        }
        if (clickButton("扫码", "二维码")) {
            sleep(1200);
            object = snapshot(true);
        }
        return object;
    }

    private static String[] providerAliases(String provider) {
        switch (provider == null ? "" : provider.toLowerCase(Locale.ROOT)) {
            case "quark": return new String[]{"夸克", "夸父", "quark"};
            case "uc": return new String[]{"优汐", "UC", "uc"};
            case "ali": return new String[]{"阿里", "阿狸", "ali"};
            case "baidu": return new String[]{"百度", "baidu", "bd"};
            case "115": return new String[]{"115"};
            case "123pan": return new String[]{"123", "123盘", "123pan"};
            default: return new String[]{"网盘", "云盘"};
        }
    }

    private static boolean hasQr(JsonObject object) {
        return object != null && object.has("qr") && object.get("qr").isJsonObject();
    }

    public static boolean dismiss() {
        if (clickOk()) return true;
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        runOnMain(() -> {
            if (App.activity() == null) return;
            App.activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            App.activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            result.set(true);
        });
        return result.get();
    }

    private static JsonObject snapshot(boolean dialogOnly) {
        AtomicReference<JsonObject> result = new AtomicReference<>();
        runOnMain(() -> {
            Root root = selectRoot(dialogOnly);
            if (root == null) {
                JsonObject object = error("jar_ui_missing", "当前没有可桥接的 Android Jar 弹窗");
                addTransient(object);
                result.set(object);
                return;
            }
            Bitmap bitmap = draw(root.view);
            if (bitmap == null) {
                JsonObject object = error("jar_ui_empty", "Android Jar 弹窗尺寸无效");
                addTransient(object);
                result.set(object);
                return;
            }
            JsonObject object = new JsonObject();
            object.addProperty("ok", true);
            object.addProperty("mode", "androidJarUi");
            object.addProperty("message", "请在 iOS 上操作 Android Jar 弹窗");
            object.addProperty("image", dataUrl(bitmap));
            object.addProperty("width", root.view.getWidth());
            object.addProperty("height", root.view.getHeight());
            JsonArray elements = new JsonArray();
            collectElements(root.view, root.view, "", elements);
            object.add("elements", elements);
            JsonObject qr = extractQr(root.view, bitmap);
            if (qr != null) object.add("qr", qr);
            addTransient(object);
            result.set(object);
        });
        JsonObject object = result.get();
        return object == null ? error("jar_ui_capture_failed", "Android Jar 弹窗截图失败") : object;
    }

    private static JsonObject extractQr(View root, Bitmap bitmap) {
        if (root == null || bitmap == null) return null;
        List<Rect> candidates = new ArrayList<>();
        collectQrCandidateBounds(root, root, candidates);
        Rect fallbackRect = null;
        Bitmap fallbackCrop = null;
        for (Rect rect : candidates) {
            Bitmap crop = crop(bitmap, rect, true);
            if (crop == null) continue;
            Result result = decodeQr(crop);
            if (result != null) return qrObject(crop, rect, result);
            if (fallbackCrop == null || crop.getWidth() * crop.getHeight() > fallbackCrop.getWidth() * fallbackCrop.getHeight()) {
                fallbackRect = rect;
                fallbackCrop = crop;
            }
        }
        Result result = decodeQr(bitmap);
        if (result == null) return fallbackCrop != null && hasLoginCue(root) ? qrObject(fallbackCrop, fallbackRect, null) : null;
        Rect rect = boundsFromPoints(result.getResultPoints(), bitmap.getWidth(), bitmap.getHeight());
        Bitmap crop = crop(bitmap, rect, true);
        return crop == null ? null : qrObject(crop, rect, result);
    }

    private static JsonObject qrObject(Bitmap bitmap, Rect rect, Result result) {
        JsonObject object = new JsonObject();
        object.addProperty("image", dataUrl(bitmap));
        object.addProperty("width", bitmap.getWidth());
        object.addProperty("height", bitmap.getHeight());
        object.addProperty("x", rect == null ? 0 : rect.left);
        object.addProperty("y", rect == null ? 0 : rect.top);
        object.addProperty("decoded", result != null);
        if (result != null && !TextUtils.isEmpty(result.getText())) object.addProperty("content", result.getText());
        return object;
    }

    private static void collectQrCandidateBounds(View root, View view, List<Rect> candidates) {
        if (view == null || !view.isShown()) return;
        if (view instanceof ImageView && looksLikeQrImage(view)) {
            int left = relativeX(root, view);
            int top = relativeY(root, view);
            candidates.add(new Rect(left, top, left + view.getWidth(), top + view.getHeight()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) collectQrCandidateBounds(root, group.getChildAt(index), candidates);
        }
    }

    private static Result decodeQr(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        try {
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            return reader.decode(binary, hints);
        } catch (ReaderException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Rect boundsFromPoints(ResultPoint[] points, int width, int height) {
        if (points == null || points.length == 0) return new Rect(0, 0, width, height);
        float minX = width;
        float minY = height;
        float maxX = 0;
        float maxY = 0;
        for (ResultPoint point : points) {
            if (point == null) continue;
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
        }
        if (maxX <= minX || maxY <= minY) return new Rect(0, 0, width, height);
        return new Rect((int) minX, (int) minY, (int) maxX, (int) maxY);
    }

    private static Bitmap crop(Bitmap bitmap, Rect rect, boolean square) {
        if (bitmap == null || rect == null) return null;
        Rect expanded = expand(rect, bitmap.getWidth(), bitmap.getHeight(), square);
        int width = expanded.width();
        int height = expanded.height();
        if (width <= 16 || height <= 16) return null;
        try {
            return Bitmap.createBitmap(bitmap, expanded.left, expanded.top, width, height);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Rect expand(Rect rect, int maxWidth, int maxHeight, boolean square) {
        int width = Math.max(rect.width(), 1);
        int height = Math.max(rect.height(), 1);
        int size = square ? Math.max(width, height) : width;
        int margin = Math.max(8, size / 8);
        int centerX = rect.left + width / 2;
        int centerY = rect.top + height / 2;
        int halfWidth = square ? size / 2 + margin : width / 2 + margin;
        int halfHeight = square ? size / 2 + margin : height / 2 + margin;
        return new Rect(
                Math.max(0, centerX - halfWidth),
                Math.max(0, centerY - halfHeight),
                Math.min(maxWidth, centerX + halfWidth),
                Math.min(maxHeight, centerY + halfHeight)
        );
    }

    private static String dataUrl(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private static JsonObject actionResult(boolean acted, String message) {
        JsonObject captured = snapshot();
        if (bool(captured, "ok") || !acted) return captured;
        JsonObject object = new JsonObject();
        if (captured.has("toast")) object.add("toast", captured.get("toast"));
        object.addProperty("ok", true);
        object.addProperty("mode", "androidJarUi");
        object.addProperty("message", message);
        return object;
    }

    private static void collectElements(View root, View view, String path, JsonArray elements) {
        if (view == null || !view.isShown()) return;
        addElement(root, view, path, elements);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                String childPath = path.isEmpty() ? String.valueOf(index) : path + "/" + index;
                collectElements(root, group.getChildAt(index), childPath, elements);
            }
        }
    }

    private static void addElement(View root, View view, String path, JsonArray elements) {
        if (path.isEmpty() || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        String text = text(view);
        String role = role(view, text);
        if (TextUtils.isEmpty(role)) return;
        JsonObject object = new JsonObject();
        object.addProperty("id", path);
        object.addProperty("role", role);
        object.addProperty("text", text);
        object.addProperty("value", value(view));
        object.addProperty("hint", hint(view));
        object.addProperty("className", view.getClass().getName());
        object.addProperty("x", relativeX(root, view));
        object.addProperty("y", relativeY(root, view));
        object.addProperty("width", view.getWidth());
        object.addProperty("height", view.getHeight());
        object.addProperty("enabled", view.isEnabled());
        object.addProperty("focused", view.hasFocus());
        object.addProperty("selected", view.isSelected());
        object.addProperty("clickable", clickableTarget(view) != null);
        object.addProperty("focusable", view.isFocusable());
        elements.add(object);
    }

    private static String role(View view, String text) {
        if (view instanceof EditText) return "input";
        if (view instanceof Button) return "button";
        if (clickableTarget(view) != null && !TextUtils.isEmpty(text)) return "button";
        if (view.isClickable()) return "button";
        if (view instanceof ImageView && looksLikeQrImage(view)) return "image";
        if (view instanceof TextView && !TextUtils.isEmpty(text)) return "text";
        return "";
    }

    private static String text(View view) {
        CharSequence value = null;
        if (view instanceof EditText) value = hint(view);
        if (TextUtils.isEmpty(value) && view instanceof TextView) value = ((TextView) view).getText();
        if (TextUtils.isEmpty(value)) value = view.getContentDescription();
        return value == null ? "" : value.toString().trim();
    }

    private static String value(View view) {
        CharSequence value = view instanceof TextView ? ((TextView) view).getText() : null;
        return value == null ? "" : value.toString().trim();
    }

    private static String hint(View view) {
        CharSequence value = view instanceof TextView ? ((TextView) view).getHint() : null;
        return value == null ? "" : value.toString().trim();
    }

    private static boolean looksLikeQrImage(View view) {
        int width = view.getWidth();
        int height = view.getHeight();
        float ratio = height == 0 ? 0 : (float) width / (float) height;
        return width >= 96 && height >= 96 && ratio >= 0.75f && ratio <= 1.35f;
    }

    private static int relativeX(View root, View view) {
        int[] rootLocation = new int[2];
        int[] viewLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        view.getLocationOnScreen(viewLocation);
        return viewLocation[0] - rootLocation[0];
    }

    private static int relativeY(View root, View view) {
        int[] rootLocation = new int[2];
        int[] viewLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        view.getLocationOnScreen(viewLocation);
        return viewLocation[1] - rootLocation[1];
    }

    private static Bitmap draw(View view) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width < 80 || height < 80) return null;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    private static Root selectRoot(boolean dialogOnly) {
        List<View> roots = roots();
        View activityRoot = App.activity() == null ? null : App.activity().getWindow().getDecorView();
        for (int index = roots.size() - 1; index >= 0; index--) {
            View view = roots.get(index);
            Root root = new Root(view, params(view));
            if (!usableRoot(view) || view == activityRoot || isIgnoredRoot(root, activityRoot)) continue;
            if (dialogOnly && !isJarLoginRoot(root)) continue;
            return root;
        }
        if (dialogOnly) return null;
        if (usableRoot(activityRoot) && (!dialogOnly || hasQrImage(activityRoot))) return new Root(activityRoot, params(activityRoot));
        return null;
    }

    private static Root selectTransientRoot() {
        List<View> roots = roots();
        View activityRoot = App.activity() == null ? null : App.activity().getWindow().getDecorView();
        for (int index = roots.size() - 1; index >= 0; index--) {
            View view = roots.get(index);
            Root root = new Root(view, params(view));
            if (usableRoot(view) && view != activityRoot && isTransientRoot(root)) return root;
        }
        return null;
    }

    private static boolean isIgnoredRoot(Root root, View activityRoot) {
        if (root == null || root.view == activityRoot) return true;
        if (isInputMethodRoot(root)) return true;
        return isTransientRoot(root);
    }

    private static boolean isInputMethodRoot(Root root) {
        if (root.params == null) return false;
        int type = root.params.type;
        if (type == WindowManager.LayoutParams.TYPE_INPUT_METHOD || type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG) return true;
        return containsIgnoreCase(String.valueOf(root.params.getTitle()), "inputmethod");
    }

    private static boolean isJarLoginRoot(Root root) {
        if (root == null || !usableRoot(root.view) || looksLikeMainActivity(root.view)) return false;
        if (root.params != null) {
            int type = root.params.type;
            if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION || type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) return false;
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG || type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL || type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL) return true;
            String title = String.valueOf(root.params.getTitle());
            if (containsIgnoreCase(title, "dialog") || containsIgnoreCase(title, "popup") || containsIgnoreCase(title, "panel")) return true;
        }
        if (isLargeRoot(root.view) && !hasLoginCue(root.view)) return false;
        return hasLoginCue(root.view) || hasEditText(root.view) || (hasQrImage(root.view) && !looksLikeMainActivity(root.view));
    }

    private static boolean looksLikeMainActivity(View view) {
        if (!isLargeRoot(view)) return false;
        String text = readableText(view).toLowerCase(Locale.ROOT);
        return text.contains("vod") && text.contains("search") && text.contains("setting");
    }

    private static boolean isLargeRoot(View view) {
        if (!usableRoot(view)) return false;
        DisplayMetrics metrics = App.get().getResources().getDisplayMetrics();
        int screenArea = Math.max(metrics.widthPixels * metrics.heightPixels, 1);
        int area = view.getWidth() * view.getHeight();
        return area > screenArea * 0.60f && view.getWidth() > metrics.widthPixels * 0.70f && view.getHeight() > metrics.heightPixels * 0.70f;
    }

    private static boolean hasLoginCue(View view) {
        String text = readableText(view).toLowerCase(Locale.ROOT);
        return containsIgnoreCase(text, "登录")
                || containsIgnoreCase(text, "扫码")
                || containsIgnoreCase(text, "二维码")
                || containsIgnoreCase(text, "授权")
                || containsIgnoreCase(text, "验证码")
                || containsIgnoreCase(text, "cookie")
                || containsIgnoreCase(text, "token")
                || containsIgnoreCase(text, "网盘")
                || containsIgnoreCase(text, "夸克")
                || containsIgnoreCase(text, "百度")
                || containsIgnoreCase(text, "阿里")
                || containsIgnoreCase(text, "login")
                || containsIgnoreCase(text, "qr");
    }

    private static boolean hasEditText(View view) {
        if (view == null || !view.isShown()) return false;
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) if (hasEditText(group.getChildAt(index))) return true;
        }
        return false;
    }

    private static boolean isTransientRoot(Root root) {
        if (root == null || root.view == null) return false;
        if (root.params != null) {
            int type = root.params.type;
            if (type == WindowManager.LayoutParams.TYPE_TOAST) return true;
            String title = String.valueOf(root.params.getTitle());
            if (containsIgnoreCase(title, "toast") || containsIgnoreCase(title, "transient")) return true;
        }
        String className = root.view.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("toast")) return true;
        return isSmallTextOnlyRoot(root.view);
    }

    private static boolean isSmallTextOnlyRoot(View view) {
        if (!usableRoot(view) || hasInteractiveContent(view) || hasQrImage(view)) return false;
        DisplayMetrics metrics = App.get().getResources().getDisplayMetrics();
        int screenArea = Math.max(metrics.widthPixels * metrics.heightPixels, 1);
        int area = view.getWidth() * view.getHeight();
        boolean smallArea = area < screenArea * 0.12f;
        boolean shortHeight = view.getHeight() < Math.max(120, metrics.heightPixels * 0.18f);
        return smallArea || shortHeight;
    }

    private static boolean hasInteractiveContent(View view) {
        if (view == null || !view.isShown()) return false;
        if (view instanceof EditText || view instanceof Button) return true;
        if (view.isClickable() && clickableTarget(view) != null) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) if (hasInteractiveContent(group.getChildAt(index))) return true;
        }
        return false;
    }

    private static boolean hasQrImage(View view) {
        if (view == null || !view.isShown()) return false;
        if (view instanceof ImageView && looksLikeQrImage(view)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) if (hasQrImage(group.getChildAt(index))) return true;
        }
        return false;
    }

    private static void addTransient(JsonObject object) {
        Root root = selectTransientRoot();
        if (root == null) return;
        String message = readableText(root.view);
        if (TextUtils.isEmpty(message)) return;
        JsonObject toast = new JsonObject();
        toast.addProperty("type", "toast");
        toast.addProperty("message", message);
        toast.addProperty("durationMs", 2200);
        object.add("toast", toast);
    }

    private static String readableText(View view) {
        StringBuilder builder = new StringBuilder();
        collectReadableText(view, builder, 180);
        return builder.toString().trim();
    }

    private static void collectReadableText(View view, StringBuilder builder, int limit) {
        if (view == null || !view.isShown() || builder.length() >= limit) return;
        String text = text(view);
        if (!TextUtils.isEmpty(text)) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(text);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) collectReadableText(group.getChildAt(index), builder, limit);
        }
        if (builder.length() > limit) builder.setLength(limit);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return !TextUtils.isEmpty(value) && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean usableRoot(View view) {
        return view != null && view.isShown() && view.getWidth() >= 80 && view.getHeight() >= 80;
    }

    private static View findByPath(View root, String path) {
        if (root == null || TextUtils.isEmpty(path)) return null;
        View current = root;
        for (String item : path.split("/")) {
            if (!(current instanceof ViewGroup)) return null;
            try {
                int index = Integer.parseInt(item);
                ViewGroup group = (ViewGroup) current;
                if (index < 0 || index >= group.getChildCount()) return null;
                current = group.getChildAt(index);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return current;
    }

    private static boolean dispatchTap(View root, float x, float y) {
        long now = System.currentTimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
        try {
            return root.dispatchTouchEvent(down) | root.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static boolean dispatchKey(View target, int keyCode) {
        long now = System.currentTimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now + 80, KeyEvent.ACTION_UP, keyCode, 0);
        return target.dispatchKeyEvent(down) | target.dispatchKeyEvent(up);
    }

    private static View focusedView(View view) {
        if (view == null || !view.isShown()) return null;
        if (view.hasFocus()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View focused = focusedView(group.getChildAt(index));
                if (focused != null) return focused;
            }
        }
        return null;
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText editText = findEditText(group.getChildAt(index));
                if (editText != null) return editText;
            }
        }
        return null;
    }

    private static boolean waitAndClick(long timeout, String... texts) {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (clickButton(texts)) return true;
            sleep(250);
        }
        return false;
    }

    private static boolean clickButton(String... texts) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        runOnMain(() -> {
            View button = findButton(texts);
            if (button == null) return;
            result.set(button.performClick());
        });
        return result.get();
    }

    private static View findButton(String... texts) {
        for (View root : roots()) {
            View button = findButton(root, texts);
            if (button != null) return button;
        }
        return null;
    }

    private static View findButton(View view, String... texts) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (matches(value, texts)) {
                View target = clickableTarget(view);
                if (target != null) return target;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View button = findButton(group.getChildAt(index), texts);
                if (button != null) return button;
            }
        }
        return null;
    }

    private static boolean matches(CharSequence value, String... texts) {
        if (value == null) return false;
        String label = value.toString();
        for (String text : texts) if (label.contains(text)) return true;
        return false;
    }

    private static View clickableTarget(View view) {
        View current = view;
        while (current != null) {
            if (current.isShown() && current.isEnabled() && current.isClickable()) return current;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return view instanceof Button ? view : null;
    }

    private static List<View> roots() {
        List<View> roots = new ArrayList<>();
        try {
            Class<?> clazz = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = clazz.getDeclaredMethod("getInstance");
            Object global = getInstance.invoke(null);
            addViewsField(roots, global, clazz);
            if (roots.isEmpty()) addRootViews(roots, global, clazz);
        } catch (Throwable ignored) {
        }
        if (App.activity() != null) add(roots, App.activity().getWindow().getDecorView());
        return roots;
    }

    private static void addRootViews(List<View> roots, Object global, Class<?> clazz) {
        try {
            Method method = clazz.getDeclaredMethod("getRootViews", android.os.IBinder.class);
            method.setAccessible(true);
            addAll(roots, method.invoke(global, new Object[]{null}));
            return;
        } catch (Throwable ignored) {
        }
        try {
            Method method = clazz.getDeclaredMethod("getRootViews");
            method.setAccessible(true);
            addAll(roots, method.invoke(global));
        } catch (Throwable ignored) {
        }
    }

    private static void addViewsField(List<View> roots, Object global, Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField("mViews");
            field.setAccessible(true);
            addAll(roots, field.get(global));
        } catch (Throwable ignored) {
        }
    }

    private static void addAll(List<View> roots, Object value) {
        if (value instanceof View[]) {
            for (View view : (View[]) value) add(roots, view);
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) if (item instanceof View) add(roots, (View) item);
        }
    }

    private static void add(List<View> roots, View view) {
        if (view != null && !roots.contains(view)) roots.add(view);
    }

    private static WindowManager.LayoutParams params(View target) {
        if (target == null) return null;
        try {
            Class<?> clazz = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = clazz.getDeclaredMethod("getInstance");
            Object global = getInstance.invoke(null);
            Field viewsField = clazz.getDeclaredField("mViews");
            Field paramsField = clazz.getDeclaredField("mParams");
            viewsField.setAccessible(true);
            paramsField.setAccessible(true);
            Object views = viewsField.get(global);
            Object params = paramsField.get(global);
            int count = size(views);
            for (int index = 0; index < count; index++) {
                if (viewAt(views, index) != target) continue;
                Object value = itemAt(params, index);
                return value instanceof WindowManager.LayoutParams ? (WindowManager.LayoutParams) value : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int size(Object value) {
        if (value instanceof View[]) return ((View[]) value).length;
        if (value instanceof List) return ((List<?>) value).size();
        return 0;
    }

    private static View viewAt(Object value, int index) {
        Object item = itemAt(value, index);
        return item instanceof View ? (View) item : null;
    }

    private static Object itemAt(Object value, int index) {
        if (value instanceof Object[]) return ((Object[]) value)[index];
        if (value instanceof List) return ((List<?>) value).get(index);
        return null;
    }

    private static void runOnMain(Runnable runnable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        App.post(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("ok", false);
        object.addProperty("code", code);
        object.addProperty("message", message);
        return object;
    }

    private static class Root {
        private final View view;
        private final WindowManager.LayoutParams params;

        private Root(View view, WindowManager.LayoutParams params) {
            this.view = view;
            this.params = params;
        }
    }
}
