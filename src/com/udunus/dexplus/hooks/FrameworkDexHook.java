package com.udunus.dexplus.hooks;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

import com.udunus.dexplus.util.InputDeviceHelper;
import com.udunus.dexplus.util.ModConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FrameworkDexHook {
    private static final int FULLSCREEN_MODE_REQUEST_ENTER = 1;
    private static final int HW_KB_USB_OR_POGO_MASK = 0x04 | 0x10 | 0x20 | 0x40;
    private static final String DEXSC_EXT = ".dexsc";
    private static final String DEXSC_MIME_TYPE = "application/x-dexplus-shortcut";
    private static final long MEDIA_DEXSC_CACHE_TTL_MS = 10_000L;
    private static final int MEDIA_DEXSC_CACHE_MAX_ENTRIES = 64;
    private static final ThreadLocal<Boolean> RESOLVING_DEXSC_URI = new ThreadLocal<>();
    private static final LinkedHashMap<String, CachedUriResult> MEDIA_DEXSC_CACHE =
            new LinkedHashMap<String, CachedUriResult>(MEDIA_DEXSC_CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedUriResult> eldest) {
                    return size() > MEDIA_DEXSC_CACHE_MAX_ENTRIES;
                }
            };
    private static volatile boolean loggedInside;
    private static volatile boolean loggedMouseKeyboard;
    private static volatile boolean loggedDexscIntentRewrite;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        hookVideoFullscreen(cl);
        hookInputMethodMouseKeyboard(cl);
        hookDexscIntentResolution(cl);

        try {
            final Class<?> activityRecordClass = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityRecord", cl);
            if (activityRecordClass != null) {
                XposedBridge.hookAllMethods(activityRecordClass, "isResizeable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });

                XposedBridge.hookAllMethods(activityRecordClass, "supportsFreeform", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });
            }

            final Class<?> taskClass = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.Task", cl);
            if (taskClass != null) {
                XposedBridge.hookAllMethods(taskClass, "isResizeable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });

                XposedBridge.hookAllMethods(taskClass, "supportsFreeformInDisplayArea", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": FrameworkDexHook error: " + t);
        }
    }

    /**
     * Video fullscreen asks the system to change the task to fullscreen windowing mode.
     * That removes it from the desk and closes DeX. Drop that request while the task
     * stays freeform. Do not report success: that makes the browser expand the page
     * instead of leaving the video overlay in the window. The browser already shows
     * that overlay itself.
     */
    private static void hookVideoFullscreen(ClassLoader cl) {
        try {
            final Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityClientController", cl);
            final Class<?> activityRecord = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityRecord", cl);
            if (controller == null || activityRecord == null) {
                return;
            }
            XposedBridge.hookAllMethods(controller, "requestMultiwindowFullscreenLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!insideWindow() || param.args == null || param.args.length < 2) {
                                return;
                            }
                            if (!(param.args[0] instanceof IBinder)) {
                                return;
                            }
                            int request = param.args[1] instanceof Integer
                                    ? ((Integer) param.args[1]).intValue() : -1;
                            if (request != FULLSCREEN_MODE_REQUEST_ENTER) {
                                return;
                            }
                            try {
                                if (!isDesktopTask(activityRecord, (IBinder) param.args[0])) {
                                    return;
                                }
                            } catch (Throwable error) {
                                XposedBridge.log(ModConfig.TAG + ": desktop fullscreen check failed: " + error);
                                return;
                            }
                            param.setResult(null);
                            if (!loggedInside) {
                                loggedInside = true;
                                XposedBridge.log(ModConfig.TAG
                                        + ": kept video fullscreen inside the window");
                            }
                        }
                    });
            XposedBridge.hookAllMethods(controller, "executeMultiWindowFullscreenRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!insideWindow() || param.args == null || param.args.length < 2
                                    || param.args[1] == null) {
                                return;
                            }
                            Object task = param.args[1];
                            if (bool(task, "isDeskRootTask") || bool(task, "isDefaultDisplayDesktop")
                                    || bool(task, "inFreeformWindowingMode")) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(ModConfig.TAG + ": could not keep video fullscreen in the window: " + error);
        }
    }

    private static void hookInputMethodMouseKeyboard(ClassLoader cl) {
        try {
            Class<?> immsClass = XposedHelpers.findClassIfExists(
                    "com.android.server.inputmethod.InputMethodManagerService", cl);
            if (immsClass == null) {
                return;
            }
            XposedBridge.hookAllMethods(immsClass, "checkBlocklistUsbKeyboardConnected",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (shouldClearMouseAccessoryResult(param.thisObject, param.getResult(), false)) {
                                param.setResult(Integer.valueOf(0));
                            }
                        }
                    });
            XposedBridge.hookAllMethods(immsClass, "isAccessoryKeyboard",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (shouldClearMouseAccessoryResult(param.thisObject, param.getResult(), true)) {
                                param.setResult(Integer.valueOf(0));
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(ModConfig.TAG + ": could not hook IMMS mouse keyboard check: " + error);
        }
    }

    private static boolean shouldClearMouseAccessoryResult(
            Object imms, Object result, boolean checkHwState) {
        if (!(result instanceof Integer) || ((Integer) result).intValue() == 0 || imms == null) {
            return false;
        }
        try {
            ModConfig.reloadIfStale(null, 2000L);
        } catch (Throwable ignored) {}
        if (!ModConfig.isModuleEnabled(null)) {
            return false;
        }
        try {
            if (checkHwState) {
                Object hwKb = XposedHelpers.getObjectField(imms, "mSamsungIMMSHWKeyboard");
                if (hwKb != null) {
                    int state = XposedHelpers.getIntField(hwKb, "keyboardState");
                    if ((state & HW_KB_USB_OR_POGO_MASK) != 0) {
                        return false;
                    }
                }
            }
            Object ctxObj = XposedHelpers.getObjectField(imms, "mContext");
            if (!(ctxObj instanceof Context)) {
                return false;
            }
            Object listObj = XposedHelpers.getObjectField(imms, "mKeyboardTypeMouseIdList");
            List<?> allowList = listObj instanceof List<?> ? (List<?>) listObj : null;
            if (InputDeviceHelper.hasOnlyMouseAlphabeticKeyboards((Context) ctxObj, allowList)) {
                if (!loggedMouseKeyboard) {
                    loggedMouseKeyboard = true;
                    XposedBridge.log(ModConfig.TAG
                            + ": cleared accessory keyboard state for mouse-only setup");
                }
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean forceResizableEnabled() {
        try {
            ModConfig.reloadIfStale(null, 2000L);
        } catch (Throwable ignored) {}
        return ModConfig.isModuleEnabled(null)
                && ModConfig.getBoolean(null, ModConfig.KEY_FORCE_RESIZABLE_WINDOWS, true);
    }

    private static boolean insideWindow() {
        try {
            ModConfig.reloadIfStale(null, 2000L);
        } catch (Throwable ignored) {}
        return ModConfig.isModuleEnabled(null)
                && ModConfig.getBoolean(null, ModConfig.KEY_FULLSCREEN_INSIDE, true);
    }

    private static boolean isDesktopTask(Class<?> activityRecord, IBinder token) {
        Object record = XposedHelpers.callStaticMethod(activityRecord, "forTokenLocked", token);
        if (record == null) {
            return false;
        }
        Object root = XposedHelpers.callMethod(record, "getRootTask");
        if (root == null) {
            return false;
        }
        return bool(root, "isDeskRootTask") || bool(root, "isDefaultDisplayDesktop");
    }

    private static boolean bool(Object target, String method) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(target, method));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Samsung My Files sends ACTION_VIEW with an empty MIME type ("") and a
     * content://media/external/file/<id> URI for unknown extensions like .dexsc,
     * which Android's IntentFilter rejects before matching manifest filters.
     * Rewrites qualifying .dexsc VIEW resolutions in system_server to DeX+'s
     * explicit MIME type so both queryIntentActivities and startActivity match.
     */
    private static void hookDexscIntentResolution(ClassLoader cl) {
        try {
            final Class<?> computerEngine = XposedHelpers.findClassIfExists(
                    "com.android.server.pm.ComputerEngine", cl);
            if (computerEngine != null) {
                XposedBridge.hookAllMethods(computerEngine, "queryIntentActivitiesInternal",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                maybeRewriteDexscResolution(param, 0, 1);
                            }
                        });
            }

            final Class<?> resolveHelper = XposedHelpers.findClassIfExists(
                    "com.android.server.pm.ResolveIntentHelper", cl);
            if (resolveHelper != null) {
                XposedBridge.hookAllMethods(resolveHelper, "resolveIntentInternal",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                maybeRewriteDexscResolution(param, 1, 2);
                            }
                        });
            }

            final Class<?> supervisor = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityTaskSupervisor", cl);
            if (supervisor != null) {
                XposedBridge.hookAllMethods(supervisor, "resolveIntent",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                maybeRewriteDexscResolution(param, 0, 1);
                            }
                        });
            }
        } catch (Throwable error) {
            XposedBridge.log(ModConfig.TAG + ": could not hook dexsc intent resolution: " + error);
        }
    }

    private static void maybeRewriteDexscResolution(
            XC_MethodHook.MethodHookParam param, int intentIdx, int typeIdx) {
        if (param.args == null || param.args.length <= Math.max(intentIdx, typeIdx)) {
            return;
        }
        if (!(param.args[intentIdx] instanceof Intent)) {
            return;
        }
        final Intent intent = (Intent) param.args[intentIdx];
        if (intent.getComponent() != null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        final Uri data = intent.getData();
        if (data == null) {
            return;
        }
        final Object typeObj = param.args[typeIdx];
        final String resolvedType = typeObj instanceof String ? (String) typeObj : null;
        if (!isAmbiguousMimeType(resolvedType)) {
            return;
        }
        if (Boolean.TRUE.equals(RESOLVING_DEXSC_URI.get())) {
            return;
        }

        final boolean directDexscPath = isDexscPathUri(data);
        final boolean mediaFileUri = !directDexscPath && isMediaStoreFileUri(data);
        if (!directDexscPath && !mediaFileUri) {
            return;
        }
        if (!isDexscIntentHookEnabled()) {
            return;
        }
        if (!directDexscPath && !isMediaStoreDexscUri(data)) {
            return;
        }

        param.args[typeIdx] = DEXSC_MIME_TYPE;
        intent.setDataAndType(data, DEXSC_MIME_TYPE);
        if (!loggedDexscIntentRewrite) {
            loggedDexscIntentRewrite = true;
            XposedBridge.log(ModConfig.TAG + ": resolved .dexsc VIEW intent to " + DEXSC_MIME_TYPE);
        }
    }

    private static boolean isDexscIntentHookEnabled() {
        try {
            ModConfig.reloadIfStale(null, 2000L);
        } catch (Throwable ignored) {}
        return ModConfig.isModuleEnabled(null);
    }

    private static boolean isAmbiguousMimeType(String type) {
        return type == null
                || type.isEmpty()
                || "application/octet-stream".equals(type)
                || "*/*".equals(type);
    }

    private static boolean isDexscPathUri(Uri data) {
        return endsWithIgnoreCase(data.getPath(), DEXSC_EXT)
                || endsWithIgnoreCase(data.getLastPathSegment(), DEXSC_EXT);
    }

    private static boolean isMediaStoreFileUri(Uri data) {
        if (!"content".equals(data.getScheme())) {
            return false;
        }
        final String auth = data.getAuthority();
        if (auth == null || (!"media".equals(auth) && !auth.endsWith("@media"))) {
            return false;
        }
        final String path = data.getPath();
        if (path == null || !path.contains("/file/")) {
            return false;
        }
        return isAllAsciiDigits(data.getLastPathSegment());
    }

    private static boolean isMediaStoreDexscUri(Uri data) {
        final String key = data.toString();
        final long now = SystemClock.uptimeMillis();
        synchronized (MEDIA_DEXSC_CACHE) {
            final CachedUriResult cached = MEDIA_DEXSC_CACHE.get(key);
            if (cached != null && (now - cached.checkedAtMs) < MEDIA_DEXSC_CACHE_TTL_MS) {
                return cached.isDexsc;
            }
        }

        final Context ctx = getSystemContext();
        if (ctx == null) {
            return false;
        }

        boolean isDexsc = false;
        RESOLVING_DEXSC_URI.set(Boolean.TRUE);
        final long token = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(
                    data, new String[] {"_data"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                isDexsc = endsWithIgnoreCase(cursor.getString(0), DEXSC_EXT);
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {}
            }
            Binder.restoreCallingIdentity(token);
            RESOLVING_DEXSC_URI.remove();
        }

        synchronized (MEDIA_DEXSC_CACHE) {
            MEDIA_DEXSC_CACHE.put(key, new CachedUriResult(isDexsc, SystemClock.uptimeMillis()));
        }
        return isDexsc;
    }

    private static Context getSystemContext() {
        try {
            final Object at = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentActivityThread");
            if (at != null) {
                final Object ctx = XposedHelpers.callMethod(at, "getSystemContext");
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        if (value == null || value.length() < suffix.length()) {
            return false;
        }
        return value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static boolean isAllAsciiDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static final class CachedUriResult {
        final boolean isDexsc;
        final long checkedAtMs;

        CachedUriResult(boolean isDexsc, long checkedAtMs) {
            this.isDexsc = isDexsc;
            this.checkedAtMs = checkedAtMs;
        }
    }
}
