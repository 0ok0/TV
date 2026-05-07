package com.fongmi.android.tv.server.bridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BridgeQr {

    private static final long UI_TIMEOUT = 15000;

    public static JsonObject captureJarUi() {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            JsonObject object = snapshot(true);
            if (bool(object, "ok")) return object;
            sleep(250);
        }
        return error("jar_ui_missing", "未找到 Android Jar 弹窗，请确认 Jar 登录窗口已出现");
    }

    public static JsonObject snapshot() {
        return snapshot(true);
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
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            JsonObject object = new JsonObject();
            object.addProperty("ok", true);
            object.addProperty("mode", "androidJarUi");
            object.addProperty("message", "请在 iOS 上操作 Android Jar 弹窗");
            object.addProperty("image", "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
            object.addProperty("width", root.view.getWidth());
            object.addProperty("height", root.view.getHeight());
            JsonArray elements = new JsonArray();
            collectElements(root.view, root.view, "", elements);
            object.add("elements", elements);
            addTransient(object);
            result.set(object);
        });
        JsonObject object = result.get();
        return object == null ? error("jar_ui_capture_failed", "Android Jar 弹窗截图失败") : object;
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
        object.addProperty("x", relativeX(root, view));
        object.addProperty("y", relativeY(root, view));
        object.addProperty("width", view.getWidth());
        object.addProperty("height", view.getHeight());
        object.addProperty("enabled", view.isEnabled());
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
        if (view instanceof EditText) value = ((EditText) view).getHint();
        if (TextUtils.isEmpty(value) && view instanceof TextView) value = ((TextView) view).getText();
        if (TextUtils.isEmpty(value)) value = view.getContentDescription();
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
            return root;
        }
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
            addRootViews(roots, global, clazz);
            addViewsField(roots, global, clazz);
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
