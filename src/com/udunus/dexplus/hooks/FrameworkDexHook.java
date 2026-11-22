package com.udunus.dexplus.hooks;

import android.app.ActivityManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import com.udunus.dexplus.util.InputDeviceHelper;
import com.udunus.dexplus.util.ModConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
    private static final String DEXSC_MIME_TYPE = "dexplus/shortcut";
    private static final String LEGACY_DEXSC_MIME_TYPE = "application/x-dexplus-shortcut";
    private static final long GET_RESOLVED_FILTER_FLAG = PackageManager.GET_RESOLVED_FILTER;
    private static final Map<Object, Long> DEXSC_ORIG_FLAGS =
            Collections.synchronizedMap(new IdentityHashMap<Object, Long>());
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
    private static volatile boolean loggedDensityHookError;
    private static volatile boolean powerReceiverRegistered;
    private static volatile boolean displayControllerRegistered;
    private static final String EFFECTS_KEY = "externaldex_effects";
    private static final int MIN_EFFECTIVE_DPI = 120;
    private static final int MAX_EFFECTIVE_DPI = 640;
    private static volatile int cachedInternalDpiScale = ModConfig.DPI_SCALE_DEFAULT;
    private static volatile int cachedExternalDpiScale = ModConfig.DPI_SCALE_DEFAULT;
    private static final ThreadLocal<IdentityHashMap<Object, ActiveDensitySwap>> ACTIVE_DENSITY_SWAPS =
            new ThreadLocal<IdentityHashMap<Object, ActiveDensitySwap>>();

    private static final class ActiveDensitySwap {
        final Object param;
        final int origBaseDpi;

        ActiveDensitySwap(Object param, int origBaseDpi) {
            this.param = param;
            this.origBaseDpi = origBaseDpi;
        }
    }

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        hookVideoFullscreen(cl);
        hookInputMethodMouseKeyboard(cl);
        hookDexscIntentResolution(cl);
        hookAdvancedPowerActions(cl);
        hookDisplayDensityMultiplier(cl);

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
     * File managers often dispatch ACTION_VIEW with an empty MIME type ("") and a
     * content://media/external/file/<id> MediaStore URI for custom extensions like .dexsc,
     * which Android's IntentFilter rejects before matching manifest filters.
     * Rewrites qualifying .dexsc VIEW resolutions in system_server to DeX+'s
     * custom MIME type and excludes wildcard MIME handlers unless they explicitly
     * declare .dexsc support.
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
                                boolean isDexsc = maybeRewriteDexscResolution(param, 0, 1);
                                if (isDexsc && param.args != null && param.args.length > 2
                                        && param.args[2] instanceof Long) {
                                    long origFlags = ((Long) param.args[2]).longValue();
                                    DEXSC_ORIG_FLAGS.put(param, Long.valueOf(origFlags));
                                    if ((origFlags & GET_RESOLVED_FILTER_FLAG) == 0L) {
                                        param.args[2] = Long.valueOf(origFlags | GET_RESOLVED_FILTER_FLAG);
                                    }
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Long origObj = DEXSC_ORIG_FLAGS.remove(param);
                                if (origObj == null) {
                                    return;
                                }
                                long origFlags = origObj.longValue();
                                filterWildcardDexscResults(param, (origFlags & GET_RESOLVED_FILTER_FLAG) != 0L);
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

    private static boolean maybeRewriteDexscResolution(
            XC_MethodHook.MethodHookParam param, int intentIdx, int typeIdx) {
        if (param.args == null || param.args.length <= Math.max(intentIdx, typeIdx)) {
            return false;
        }
        if (!(param.args[intentIdx] instanceof Intent)) {
            return false;
        }
        final Intent intent = (Intent) param.args[intentIdx];
        if (intent.getComponent() != null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        final Object typeObj = param.args[typeIdx];
        final String resolvedType = typeObj instanceof String ? (String) typeObj : null;
        if (DEXSC_MIME_TYPE.equals(resolvedType) || LEGACY_DEXSC_MIME_TYPE.equals(resolvedType)) {
            return isDexscIntentHookEnabled();
        }
        final Uri data = intent.getData();
        if (data == null || !isAmbiguousMimeType(resolvedType)) {
            return false;
        }
        if (Boolean.TRUE.equals(RESOLVING_DEXSC_URI.get())) {
            return false;
        }

        final boolean directDexscPath = isDexscPathUri(data);
        final boolean mediaFileUri = !directDexscPath && isMediaStoreFileUri(data);
        if (!directDexscPath && !mediaFileUri) {
            return false;
        }
        if (!isDexscIntentHookEnabled()) {
            return false;
        }
        if (!directDexscPath && !isMediaStoreDexscUri(data)) {
            return false;
        }

        param.args[typeIdx] = DEXSC_MIME_TYPE;
        intent.setDataAndType(data, DEXSC_MIME_TYPE);
        if (!loggedDexscIntentRewrite) {
            loggedDexscIntentRewrite = true;
            XposedBridge.log(ModConfig.TAG + ": resolved .dexsc VIEW intent to " + DEXSC_MIME_TYPE);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void filterWildcardDexscResults(
            XC_MethodHook.MethodHookParam param, boolean keepResolvedFilter) {
        Object resultObj = param.getResult();
        if (!(resultObj instanceof List<?>)) {
            return;
        }
        List<ResolveInfo> list = (List<ResolveInfo>) resultObj;
        if (list.isEmpty()) {
            return;
        }
        List<ResolveInfo> filtered = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            if (ri == null) {
                continue;
            }
            if (ri.filter != null && !isExplicitDexscFilter(ri.filter)) {
                continue;
            }
            if (!keepResolvedFilter) {
                ri.filter = null;
            }
            filtered.add(ri);
        }
        if (filtered.size() != list.size()) {
            param.setResult(filtered);
        }
    }

    private static boolean isExplicitDexscFilter(IntentFilter filter) {
        int typeCount = filter.countDataTypes();
        for (int i = 0; i < typeCount; i++) {
            String dt = filter.getDataType(i);
            if (DEXSC_MIME_TYPE.equals(dt) || LEGACY_DEXSC_MIME_TYPE.equals(dt)) {
                return true;
            }
        }
        Iterator<PatternMatcher> paths = filter.pathsIterator();
        if (paths != null) {
            while (paths.hasNext()) {
                PatternMatcher pm = paths.next();
                if (pm != null && endsWithIgnoreCase(pm.getPath(), DEXSC_EXT)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Registers a privileged broadcast receiver inside system_server once AMS finishes booting
     * so SystemUI's Advanced Power Menu can trigger protected reboot and soft-reboot actions.
     */
    private static void hookAdvancedPowerActions(final ClassLoader cl) {
        try {
            final Class<?> amsClass = XposedHelpers.findClassIfExists(
                    "com.android.server.am.ActivityManagerService", cl);
            if (amsClass == null) {
                return;
            }
            XposedBridge.hookAllMethods(amsClass, "systemReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (powerReceiverRegistered) {
                        return;
                    }
                    Context amsCtx = null;
                    try {
                        Object raw = XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (raw instanceof Context) {
                            amsCtx = (Context) raw;
                        }
                    } catch (Throwable ignored) {}
                    if (amsCtx == null) {
                        amsCtx = getSystemContext();
                    }
                    if (amsCtx != null) {
                        registerPowerActionReceiver(amsCtx, cl);
                        registerDisplayController(amsCtx, cl);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hookAdvancedPowerActions error: " + t);
        }
    }

    private static void registerPowerActionReceiver(final Context ctx, final ClassLoader cl) {
        synchronized (FrameworkDexHook.class) {
            if (powerReceiverRegistered) {
                return;
            }
            try {
                ModConfig.ensureInitialized(ctx);
                IntentFilter filter = new IntentFilter(ModConfig.ACTION_POWER_ACTION);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverCtx, Intent intent) {
                        if (intent == null) {
                            return;
                        }
                        String action = intent.getStringExtra(ModConfig.EXTRA_POWER_ACTION);
                        if (action == null || action.isEmpty()) {
                            return;
                        }
                        Context cfgCtx = receiverCtx != null ? receiverCtx : ctx;
                        ModConfig.reload(cfgCtx);
                        if (!ModConfig.isModuleEnabled(cfgCtx)
                                || !ModConfig.isAdvancedPowerMenuEnabled(cfgCtx)) {
                            return;
                        }
                        executePowerAction(ctx, cl, action);
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, "android.permission.DEVICE_POWER",
                            null, 2 /* RECEIVER_EXPORTED */);
                } else {
                    ctx.registerReceiver(receiver, filter, "android.permission.DEVICE_POWER", null);
                }
                powerReceiverRegistered = true;
                XposedBridge.log(ModConfig.TAG + ": registered Advanced Power Menu receiver in system_server");
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": registerPowerActionReceiver error: " + t);
            }
        }
    }

    private static void executePowerAction(final Context ctx, final ClassLoader cl, final String action) {
        if ("soft_reboot".equals(action)) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(180L);
                    } catch (InterruptedException ignored) {}
                    try {
                        android.system.Os.class.getMethod("sync").invoke(null);
                    } catch (Throwable ignored) {}
                    XposedBridge.log(ModConfig.TAG + ": executing soft reboot (killing system_server)");
                    Process.killProcess(Process.myPid());
                }
            }, "dexplus-soft-reboot");
            t.start();
            return;
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Class<?> shutdownClass = XposedHelpers.findClassIfExists(
                        "com.android.server.power.ShutdownThread", cl);
                if (shutdownClass == null) {
                    XposedBridge.log(ModConfig.TAG + ": ShutdownThread not found for power action: " + action);
                    return;
                }
                final long token = Binder.clearCallingIdentity();
                try {
                    if ("safemode".equals(action)) {
                        try {
                            XposedHelpers.callStaticMethod(shutdownClass, "rebootSafeMode",
                                    new Class<?>[]{Context.class}, ctx);
                        } catch (Throwable ignored) {
                            XposedHelpers.callStaticMethod(shutdownClass, "reboot",
                                    new Class<?>[]{Context.class, String.class, boolean.class},
                                    ctx, "safemode", false);
                        }
                    } else if ("recovery".equals(action)
                            || "download".equals(action)
                            || "bootloader".equals(action)) {
                        XposedHelpers.callStaticMethod(shutdownClass, "reboot",
                                new Class<?>[]{Context.class, String.class, boolean.class},
                                ctx, action, false);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(ModConfig.TAG + ": executePowerAction(" + action + ") error: " + t);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        });
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

    /**
     * Hijacks DisplayContent.updateDisplayAndOrientation(Configuration) so the active display
     * receives a runtime-multiplied logicalDensityDpi derived from the untouched system base DPI
     * (mBaseDisplayDensity), then immediately restores mBaseDisplayDensity afterward.
     * Because Android's persisted forced-density settings (Settings.Secure / DisplayWindowSettings)
     * and mBaseDisplayDensity are never mutated, DPI cannot compound across reboots.
     */
    private static void hookDisplayDensityMultiplier(ClassLoader cl) {
        try {
            final Class<?> dcClass = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.DisplayContent", cl);
            if (dcClass == null) {
                XposedBridge.log(ModConfig.TAG + ": DisplayContent not found for DPI multiplier hook");
                return;
            }
            XposedBridge.hookAllMethods(dcClass, "updateDisplayAndOrientation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!displayControllerRegistered || param.thisObject == null) {
                        return;
                    }
                    IdentityHashMap<Object, ActiveDensitySwap> active = ACTIVE_DENSITY_SWAPS.get();
                    if (active != null && active.containsKey(param.thisObject)) {
                        return;
                    }
                    try {
                        int displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId");
                        if (displayId < Display.DEFAULT_DISPLAY) {
                            return;
                        }
                        if (displayId > Display.DEFAULT_DISPLAY
                                && isPrivateVirtualDisplayContent(param.thisObject)) {
                            return;
                        }
                        int scalePercent = (displayId == Display.DEFAULT_DISPLAY)
                                ? cachedInternalDpiScale
                                : cachedExternalDpiScale;
                        if (scalePercent == ModConfig.DPI_SCALE_DEFAULT) {
                            return;
                        }
                        int origBaseDpi = XposedHelpers.getIntField(param.thisObject, "mBaseDisplayDensity");
                        if (origBaseDpi <= 0) {
                            return;
                        }
                        int scaledDpi = computeScaledDpi(origBaseDpi, scalePercent);
                        if (scaledDpi == origBaseDpi) {
                            return;
                        }
                        if (active == null) {
                            active = new IdentityHashMap<Object, ActiveDensitySwap>(2);
                            ACTIVE_DENSITY_SWAPS.set(active);
                        }
                        active.put(param.thisObject, new ActiveDensitySwap(param, origBaseDpi));
                        XposedHelpers.setIntField(param.thisObject, "mBaseDisplayDensity", scaledDpi);
                    } catch (Throwable t) {
                        if (!loggedDensityHookError) {
                            loggedDensityHookError = true;
                            XposedBridge.log(ModConfig.TAG
                                    + ": updateDisplayAndOrientation DPI hook error: " + t);
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    IdentityHashMap<Object, ActiveDensitySwap> active = ACTIVE_DENSITY_SWAPS.get();
                    if (active == null) {
                        return;
                    }
                    ActiveDensitySwap swap = active.get(param.thisObject);
                    if (swap == null || swap.param != param) {
                        return;
                    }
                    try {
                        XposedHelpers.setIntField(param.thisObject, "mBaseDisplayDensity", swap.origBaseDpi);
                    } catch (Throwable ignored) {}
                    active.remove(param.thisObject);
                    if (active.isEmpty()) {
                        ACTIVE_DENSITY_SWAPS.remove();
                    }
                }
            });
            XposedBridge.log(ModConfig.TAG
                    + ": hooked DisplayContent.updateDisplayAndOrientation for runtime DPI multiplier");
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": could not hook DisplayContent DPI multiplier: " + t);
        }
    }

    private static boolean isPrivateVirtualDisplayContent(Object dc) {
        try {
            Object info = XposedHelpers.getObjectField(dc, "mDisplayInfo");
            if (info == null) {
                return false;
            }
            int type = XposedHelpers.getIntField(info, "type");
            int flags = XposedHelpers.getIntField(info, "flags");
            // Skip private virtual displays (e.g., screen recorders / media projection)
            // while still applying to external monitors, wireless DeX, simulated displays, and public virtual displays.
            return type == 5 && (flags & Display.FLAG_PRIVATE) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int clampDpiScale(int percent, int min, int max) {
        if (percent < min) return min;
        if (percent > max) return max;
        return percent;
    }

    private static int computeScaledDpi(int baseDpi, int scalePercent) {
        if (baseDpi <= 0) return baseDpi;
        if (scalePercent == ModConfig.DPI_SCALE_DEFAULT) return baseDpi;
        int scaled = Math.round(baseDpi * (scalePercent / 100.0f));
        return Math.max(MIN_EFFECTIVE_DPI, Math.min(MAX_EFFECTIVE_DPI, scaled));
    }

    /**
     * Reads DeX+ DPI multiplier settings and Internal DeX active state outside WMS.mGlobalLock
     * so the updateDisplayAndOrientation hook stays lock-free and never performs Binder IPC.
     */
    private static void refreshCachedDpiSettings(Context ctx) {
        if (ctx == null) return;
        final long token = Binder.clearCallingIdentity();
        try {
            ModConfig.reloadIfStale(ctx, 1000L);
            if (!ModConfig.isModuleEnabled(ctx)) {
                cachedInternalDpiScale = ModConfig.DPI_SCALE_DEFAULT;
                cachedExternalDpiScale = ModConfig.DPI_SCALE_DEFAULT;
                return;
            }
            boolean internalDexOn = ModConfig.getBoolean(ctx, ModConfig.KEY_FORCE_INTERNAL_DEX, false);
            if (!internalDexOn) {
                try {
                    internalDexOn = Settings.Global.getInt(ctx.getContentResolver(), EFFECTS_KEY, 0) == 1;
                } catch (Throwable ignored) {}
            }
            int intScale = clampDpiScale(
                    ModConfig.getInt(ctx, ModConfig.KEY_INTERNAL_DEX_DPI_SCALE, ModConfig.DPI_SCALE_DEFAULT),
                    ModConfig.INTERNAL_DPI_SCALE_MIN,
                    ModConfig.INTERNAL_DPI_SCALE_MAX);
            int extScale = clampDpiScale(
                    ModConfig.getInt(ctx, ModConfig.KEY_EXTERNAL_DEX_DPI_SCALE, ModConfig.DPI_SCALE_DEFAULT),
                    ModConfig.EXTERNAL_DPI_SCALE_MIN,
                    ModConfig.EXTERNAL_DPI_SCALE_MAX);
            cachedInternalDpiScale = internalDexOn ? intScale : ModConfig.DPI_SCALE_DEFAULT;
            cachedExternalDpiScale = extScale;
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": refreshCachedDpiSettings error: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void registerDisplayController(final Context ctx, final ClassLoader cl) {
        synchronized (FrameworkDexHook.class) {
            if (displayControllerRegistered || ctx == null) {
                return;
            }
            try {
                ModConfig.ensureInitialized(ctx);
                refreshCachedDpiSettings(ctx);
                final Handler mainHandler = new Handler(Looper.getMainLooper());
                ModConfig.addChangeListener(new Runnable() {
                    @Override
                    public void run() {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                syncAllDisplayDpis(ctx, cl);
                            }
                        });
                    }
                });
                try {
                    Uri effectsUri = Settings.Global.getUriFor(EFFECTS_KEY);
                    if (effectsUri != null) {
                        ctx.getContentResolver().registerContentObserver(
                                effectsUri, false, new ContentObserver(mainHandler) {
                                    @Override
                                    public void onChange(boolean selfChange) {
                                        syncAllDisplayDpis(ctx, cl);
                                    }
                                });
                    }
                } catch (Throwable ignored) {}

                DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                        @Override
                        public void onDisplayAdded(final int displayId) {
                            if (displayId <= Display.DEFAULT_DISPLAY) return;
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    refreshCachedDpiSettings(ctx);
                                    applyDisplayDpi(ctx, cl, displayId);
                                }
                            }, 250L);
                        }

                        @Override
                        public void onDisplayRemoved(final int displayId) {
                            if (displayId <= Display.DEFAULT_DISPLAY) return;
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    recoverLiveWallpaperAfterDisplayRemoved(ctx, cl);
                                }
                            }, 400L);
                        }

                        @Override
                        public void onDisplayChanged(int displayId) {}
                    }, mainHandler);
                }
                displayControllerRegistered = true;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        syncAllDisplayDpis(ctx, cl);
                    }
                }, 1500L);
                XposedBridge.log(ModConfig.TAG + ": registered per-display DPI multiplier & wallpaper recovery controller");
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": registerDisplayController error: " + t);
            }
        }
    }

    private static void syncAllDisplayDpis(Context ctx, ClassLoader cl) {
        if (ctx == null) return;
        try {
            refreshCachedDpiSettings(ctx);
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            Display[] displays = dm.getDisplays();
            if (displays == null) return;
            for (Display d : displays) {
                if (d != null && d.isValid()) {
                    applyDisplayDpi(ctx, cl, d.getDisplayId());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": syncAllDisplayDpis error: " + t);
        }
    }

    private static void applyDisplayDpi(Context ctx, ClassLoader cl, int displayId) {
        if (ctx == null || displayId < Display.DEFAULT_DISPLAY) return;
        final long token = Binder.clearCallingIdentity();
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal", false, cl);
            Object wm = wmgClass.getMethod("getWindowManagerService").invoke(null);
            if (wm == null) return;

            Object globalLock = XposedHelpers.getObjectField(wm, "mGlobalLock");
            Object root = XposedHelpers.getObjectField(wm, "mRoot");
            if (globalLock == null || root == null) return;

            int scalePercent = (displayId == Display.DEFAULT_DISPLAY)
                    ? cachedInternalDpiScale
                    : cachedExternalDpiScale;
            int origBaseDpi = 0;
            int desiredDpi = 0;
            boolean reconfigured = false;

            Method boostMethod = null;
            Method resetMethod = null;
            try {
                boostMethod = wm.getClass().getMethod("boostPriorityForLockedSection");
                resetMethod = wm.getClass().getMethod("resetPriorityAfterLockedSection");
            } catch (Throwable ignored) {}

            if (boostMethod != null) {
                try { boostMethod.invoke(null); } catch (Throwable ignored) {}
            }
            try {
                synchronized (globalLock) {
                    Object dc = XposedHelpers.callMethod(root, "getDisplayContent", Integer.valueOf(displayId));
                    if (dc == null) {
                        return;
                    }
                    if (displayId > Display.DEFAULT_DISPLAY && isPrivateVirtualDisplayContent(dc)) {
                        return;
                    }
                    origBaseDpi = XposedHelpers.getIntField(dc, "mBaseDisplayDensity");
                    if (origBaseDpi <= 0) {
                        return;
                    }
                    desiredDpi = computeScaledDpi(origBaseDpi, scalePercent);

                    int currentLogicalDpi = 0;
                    Object displayInfo = XposedHelpers.getObjectField(dc, "mDisplayInfo");
                    if (displayInfo != null) {
                        currentLogicalDpi = XposedHelpers.getIntField(displayInfo, "logicalDensityDpi");
                    }
                    int currentConfigDpi = 0;
                    try {
                        Object cfg = XposedHelpers.callMethod(dc, "getConfiguration");
                        if (cfg != null) {
                            currentConfigDpi = XposedHelpers.getIntField(cfg, "densityDpi");
                        }
                    } catch (Throwable ignored) {}

                    if (currentLogicalDpi == desiredDpi
                            && (currentConfigDpi <= 0 || currentConfigDpi == desiredDpi)) {
                        return;
                    }

                    XposedHelpers.callMethod(dc, "reconfigureDisplayLocked");
                    reconfigured = true;
                }
            } finally {
                if (resetMethod != null) {
                    try { resetMethod.invoke(null); } catch (Throwable ignored) {}
                }
            }

            if (reconfigured) {
                XposedBridge.log(ModConfig.TAG + ": display " + displayId + " runtime DPI -> "
                        + desiredDpi + " (systemBase=" + origBaseDpi + ", multiplier=" + scalePercent + "%)");
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": applyDisplayDpi(" + displayId + ") error: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Samsung's GraphicalWallpaperService shares one process across displays; tearing down a
     * secondary display's engine can blank Display 0's wallpaper until reboot. Restarting the
     * live wallpaper process via killUid lets WallpaperManagerService rebind it cleanly on Display 0.
     */
    private static void recoverLiveWallpaperAfterDisplayRemoved(Context ctx, ClassLoader cl) {
        if (ctx == null || !ModConfig.isModuleEnabled(ctx)) return;
        final long token = Binder.clearCallingIdentity();
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            WallpaperInfo info = wm != null ? wm.getWallpaperInfo() : null;
            if (info == null) return;
            String wpPkg = info.getPackageName();
            if (wpPkg == null || wpPkg.isEmpty()) return;
            int uid = ctx.getPackageManager().getPackageUid(wpPkg, 0);
            int appId = uid % 100000;
            int userId = uid / 100000;
            Object amService = Class.forName("android.app.ActivityManager", false, cl)
                    .getMethod("getService").invoke(null);
            if (amService != null) {
                amService.getClass().getMethod("killUid", int.class, int.class, String.class)
                        .invoke(amService, Integer.valueOf(appId), Integer.valueOf(userId),
                                "dexplus_wallpaper_recover");
                XposedBridge.log(ModConfig.TAG + ": recovered live wallpaper (" + wpPkg
                        + ") after secondary display removal");
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": recoverLiveWallpaper error: " + t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
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
