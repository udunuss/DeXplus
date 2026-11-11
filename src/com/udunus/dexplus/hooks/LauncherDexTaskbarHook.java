package com.udunus.dexplus.hooks;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.udunus.dexplus.util.HomeGrid;
import com.udunus.dexplus.util.ModConfig;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Enables the Samsung DeX desktop taskbar, right-side status icons, live notification badge,
 * DeX notification/quick-settings/calendar/volume/audio panels, and native desktop home screen
 * options on the built-in tablet display while Internal DeX is active.
 */
public final class LauncherDexTaskbarHook {
    private static final String TAG = ModConfig.TAG;
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final String DEX_LISTENER =
            "com.honeyspace.ui.honeypots.dexpanel.notification.external.NotificationListener";
    private static final String INSET_KEY = "externaldex_status_inset";
    private static final String EFFECTS_KEY = "externaldex_effects";
    private static final String HONEY_SPACE = "com.honeyspace.common.data.HoneySpaceInfo";
    private static final String COMBINED = "com.honeyspace.ui.common.dex.CombinedDexInfoImpl";
    private static final String INTERNAL_DEX = "IsInternalDex";
    private static final int ENABLING = 3;
    private static final int ENABLED = 4;
    private static final int DISPLAY_STANDALONE = 101;
    private static final int UNSET = Integer.MIN_VALUE;
    private static final long RESTART_COOLDOWN_MS = 15000L;
    private static final long STARTUP_FALSE_GRACE_MS = 3000L;
    private static final String PREF = "internaldex_state";
    private static final String PREF_ON = "internalDex";
    private static final String PREF_RESTART = "restartAt";
    private static final int SCALE_DEFAULT = 100;
    private static final int PANEL_WINDOW_TYPE = 2009;
    private static final int OVERLAY_WINDOW_TYPE = 2038;
    private static final String STATUS_POLICY = "immersive.status=*";

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    private static final XC_MethodHook DEPTH_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            taskbarSeen = true;
            DEPTH.get()[0]++;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            DEPTH.get()[0]--;
        }
    };

    private static final ThreadLocal<int[]> CREATE_VIEW_CALLS = new ThreadLocal<int[]>();
    private static final ThreadLocal<Boolean> KEEP_REAL_DEX_SPACE = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> IN_RETRY = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> DEX_UI = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> IN_TILE_UPDATE = new ThreadLocal<Boolean>();

    private static final XC_MethodHook DEX_SPACE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(BYPASS.get())) {
                return;
            }
            if (Boolean.TRUE.equals(param.getResult())) {
                return;
            }
            if (Boolean.TRUE.equals(KEEP_REAL_DEX_SPACE.get())) {
                return;
            }
            int[] createViewCalls = CREATE_VIEW_CALLS.get();
            if (createViewCalls != null && createViewCalls[0]++ == 0) {
                return;
            }
            if (!modsEnabled || !dexOn() || DEPTH.get()[0] <= 0 || !builtInDisplay(param.thisObject)) {
                return;
            }
            param.setResult(Boolean.TRUE);
        }
    };

    private static final XC_MethodHook CREATE_VIEW_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            CREATE_VIEW_CALLS.set(new int[1]);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            CREATE_VIEW_CALLS.remove();
            keepTaskbarVisible();
        }
    };

    private static final XC_MethodHook HIDE_FLAG_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            KEEP_REAL_DEX_SPACE.set(Boolean.TRUE);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            KEEP_REAL_DEX_SPACE.remove();
            keepTaskbarVisible();
        }
    };

    private static volatile boolean internalDex;
    private static volatile boolean forceInternalDex;
    private static volatile boolean taskbarSeen;
    private static volatile boolean persistedOn;
    private static volatile Object dexFlow;
    private static volatile Context appContext;
    private static volatile int scalePercent = SCALE_DEFAULT;
    private static volatile int heightScalePercent = 100;
    private static volatile boolean modsEnabled = true;
    private static volatile boolean hideStatusBar = true;
    private static volatile boolean hideArrows = true;
    private static volatile boolean blockHomeGestures = true;
    private static volatile boolean showFinderBtn = true;
    private static volatile int statusPanelMode = 0;
    private static volatile int marginPercent = 40;
    private static volatile int verticalPercent = 0;
    private static volatile int bottomPercent = 100;
    private static volatile int homeIconScale = 100;
    private static volatile Object homeUiComponent;
    private static volatile int targetColumns = 12;
    private static volatile int targetRows = 12;
    private static FileObserver iconSizeObserver;
    private static boolean iconSizeWatching;
    private static String appliedOptions;
    private static long startedAt;
    private static int applied = UNSET;
    private static boolean listening;
    private static boolean restartPosted;
    private static boolean loggedRedirect;
    private static boolean loggedHeight;
    private static boolean loggedWindowType;
    private static boolean loggedDensity;
    private static boolean loggedMargin;
    private static boolean loggedVertical;
    private static boolean loggedBottom;
    private static boolean loggedSuper;
    private static boolean loggedStatus;
    private static boolean loggedBadge;
    private static boolean loggedQsGuard;
    private static boolean loggedBattery;
    private static boolean loggedDrawer;
    private static boolean loggedShade;
    private static boolean settingsLoaded;
    private static boolean taskbarVisFailed;
    private static volatile int notificationCount;
    private static volatile Object dexListener;
    private static final Map<Integer, Integer> MARGIN_NAMES = new HashMap<Integer, Integer>();
    private static final WeakHashMap<View, Integer> BATTERY_SIZE = new WeakHashMap<View, Integer>();
    private static boolean loggedNotifAccess;
    private static boolean loggedNotifRefresh;
    private static boolean loggedListener;
    private static boolean rebindRequested;
    private static int notifPublishTries;
    private static int batteryFixTries;
    private static final WeakHashMap<View, TextView> NOTIFICATION_BADGES =
            new WeakHashMap<View, TextView>();
    private static boolean loggedAddFailure;
    private static boolean loggedClickHook;
    private static boolean loggedPanels;
    private static boolean panelServicesStarted;
    private static final Map<Object, Object> PANEL_PROVIDER_MANAGERS =
            Collections.synchronizedMap(new IdentityHashMap<Object, Object>());
    private static final Set<Class<?>> HOOKED_PROVIDER_GET =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static int cachedDexBarPx;
    private static final WeakHashMap<View, Integer> GROWN_ICONS = new WeakHashMap<View, Integer>();

    private LauncherDexTaskbarHook() {}

    public static boolean isInternalDexActive() {
        return dexOn();
    }

    public static boolean isInternalDexActive(Context ctx, ClassLoader cl) {
        return dexOn();
    }

    public static boolean isBuiltInDisplay(Context ctx) {
        if (ctx == null) return true;
        try {
            Object d = ctx.getClass().getMethod("getDisplay").invoke(ctx);
            if (d instanceof Display) {
                int id = ((Display) d).getDisplayId();
                return id == 0 || id == 1;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        hookDexSpace(loader, HONEY_SPACE);
        hookDexSpace(loader, COMBINED);
        int wrapped = wrapTaskbar(loader);
        XposedBridge.log(TAG + ": wrapped " + wrapped + " taskbar methods");
        keepTaskbarOnDeviceDisplay(loader);
        hookDexDensity(loader);
        hookDesktopOptions(loader);
        hookStatusIcons(loader);
        hookBatteryDrawable(loader);
        hookHomeGestures(loader);
        hookDisplays();
        hookPanelClicks(loader);
        watchInternalDex(loader);
        XposedHelpers.findAndHookMethod(
                Application.class,
                "onConfigurationChanged",
                Configuration.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context current = appContext;
                        if (current != null) {
                            readSettings(current);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                publish((Context) param.args[0]);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                publish(context);
                listen(context);
                ModConfig.registerRestartReceiver(context, "launcher");
            }
        });
        ModConfig.addChangeListener(new Runnable() {
            @Override
            public void run() {
                Context current = appContext;
                if (current != null) {
                    readSettings(current);
                }
            }
        });
    }

    private static void keepTaskbarOnDeviceDisplay(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.ui.common.taskbar.TaskbarControllerImpl",
                    loader,
                    "createView",
                    CREATE_VIEW_HOOK);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not protect taskbar creation: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.ui.common.taskbar.TaskbarVisibilityController",
                    loader,
                    "makeHideFlag",
                    HIDE_FLAG_HOOK);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not protect taskbar visibility: " + error);
        }
    }

    private static void watchInternalDex(ClassLoader loader) {
        try {
            Class<?> shared = Class.forName("com.honeyspace.sdk.HoneySharedData", false, loader);
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.sdk.HoneySharedDataKt",
                    loader,
                    "getState",
                    shared,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!INTERNAL_DEX.equals(param.args[1]) || param.getResult() == null) {
                                return;
                            }
                            dexFlow = param.getResult();
                            Object value = readFlow(dexFlow);
                            if (value instanceof Boolean) {
                                update((Boolean) value);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch IsInternalDex: " + error);
        }
        try {
            Class<?> flow = Class.forName("kotlinx.coroutines.flow.StateFlowImpl", false, loader);
            XposedHelpers.findAndHookMethod(flow, "setValue", Object.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject != dexFlow || !(param.args[0] instanceof Boolean)) {
                        return;
                    }
                    update((Boolean) param.args[0]);
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch IsInternalDex updates: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(COMBINED, loader, "isInternalDex", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof Boolean) {
                        update((Boolean) param.getResult());
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook isInternalDex: " + error);
        }
    }

    private static boolean dexOn() {
        if (forceInternalDex || internalDex) {
            return true;
        }
        Object flow = dexFlow;
        if (flow == null) {
            return false;
        }
        return Boolean.TRUE.equals(readFlow(flow));
    }

    private static Object readFlow(Object flow) {
        try {
            return flow.getClass().getMethod("getValue").invoke(flow);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Boolean readService(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Object manager = context.getSystemService("desktopmode");
            if (manager == null) {
                return null;
            }
            Method state = manager.getClass().getMethod("getDesktopModeState");
            return Boolean.valueOf(standalone(state.invoke(manager)));
        } catch (Throwable error) {
            return null;
        }
    }

    private static void publish(Context context) {
        if (context != null) {
            appContext = context;
        }
        if (startedAt == 0L) {
            startedAt = SystemClock.elapsedRealtime();
            persistedOn = readPersistedOn(context);
            if (persistedOn) {
                internalDex = true;
                applied = 1;
                XposedBridge.log(TAG + ": in-device DeX is on (remembered)");
            }
        }
        if (context != null) {
            ModConfig.ensureInitialized(context);
            readSettings(context);
        }
        Boolean service = readService(context);
        if (service != null) {
            update(service.booleanValue());
        }
    }

    private static boolean readPersistedOn(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(PREF_ON, false);
        } catch (Throwable error) {
            return false;
        }
    }

    private static void persist(boolean on) {
        persistedOn = on;
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, on).apply();
        } catch (Throwable ignored) {}
    }

    private static boolean standalone(Object state) {
        if (state == null) {
            return false;
        }
        try {
            int enabled = ((Integer) state.getClass().getMethod("getEnabled").invoke(state)).intValue();
            int type = ((Integer) state.getClass().getMethod("getDisplayType").invoke(state)).intValue();
            return (enabled == ENABLING || enabled == ENABLED) && type == DISPLAY_STANDALONE;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean builtInDisplay(Object info) {
        if (info == null) {
            return false;
        }
        try {
            String name = info.getClass().getName();
            if (HONEY_SPACE.equals(name)) {
                int id = ((Integer) info.getClass().getMethod("getDisplayId").invoke(info)).intValue();
                return id == 0 || id == 1;
            }
            if (COMBINED.equals(name)) {
                int id = ((Integer) info.getClass().getMethod("getPrimaryDisplayId").invoke(info)).intValue();
                return id == 0 || id == 1;
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": display check failed: " + error);
        }
        return false;
    }

    private static void hookDexSpace(ClassLoader loader, String className) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if ("isDexSpace".equals(method.getName())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(method, DEX_SPACE);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": failed to hook " + className + ": " + error);
        }
    }

    private static int wrapTaskbar(ClassLoader loader) {
        int count = 0;
        for (String name : TaskbarClasses.NAMES) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                int flags = method.getModifiers();
                if (Modifier.isAbstract(flags) || Modifier.isNative(flags)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, DEPTH_HOOK);
                    count++;
                } catch (Throwable ignored) {}
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(constructor, DEPTH_HOOK);
                    count++;
                } catch (Throwable ignored) {}
            }
        }
        return count;
    }

    private static void listen(final Context context) {
        if (listening || context == null) {
            return;
        }
        Object manager;
        try {
            manager = context.getSystemService("desktopmode");
        } catch (Throwable error) {
            return;
        }
        if (manager == null) {
            return;
        }
        try {
            Class<?> listener = Class.forName("com.samsung.android.desktopmode.SemDesktopModeManager$DesktopModeListener");
            Object proxy = Proxy.newProxyInstance(
                    listener.getClassLoader(),
                    new Class<?>[] {listener},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onDesktopModeStateChanged".equals(method.getName())
                                    && args != null
                                    && args.length > 0) {
                                try {
                                    update(standalone(args[0]));
                                } catch (Throwable ignored) {}
                            }
                            return null;
                        }
                    });
            Method register = manager.getClass().getMethod("registerListener", listener);
            register.invoke(manager, proxy);
            listening = true;
            XposedBridge.log(TAG + ": listening for DeX state");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not register DeX listener: " + error);
        }
    }

    private static void update(boolean on) {
        if (!on && persistedOn && withinStartupGrace()) {
            return;
        }
        synchronized (LauncherDexTaskbarHook.class) {
            int next = on ? 1 : 0;
            int previous = applied;
            internalDex = on;
            applied = next;
            if (previous == next) {
                return;
            }
            XposedBridge.log(TAG + ": in-device DeX is " + (on ? "on" : "off"));
            persist(on);
            applyStatusBar();
            boolean gridChanged = false;
            if (on && settingsLoaded && appContext != null) {
                gridChanged = applyGrid(appContext);
            }
            if (gridChanged) {
                restartForScale();
            } else if (previous != UNSET && taskbarSeen) {
                restartLauncher();
            }
        }
    }

    private static boolean withinStartupGrace() {
        return startedAt != 0L
                && SystemClock.elapsedRealtime() - startedAt < STARTUP_FALSE_GRACE_MS;
    }

    private static void hookDexDensity(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "com.honeyspace.common.context.ContextExtensionKt", false, loader);
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!"getDensityDimension".equals(method.getName())
                        || method.getReturnType() != int.class
                        || params.length != 2
                        || params[1] != int.class
                        || !Context.class.isAssignableFrom(params[0])) {
                    continue;
                }
                XposedBridge.hookMethod(method, DENSITY_DIMENSION);
                hooked++;
            }
            XposedBridge.log(TAG + ": density hooks " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook dex density: " + error);
        }
    }

    private static final XC_MethodHook DENSITY_DIMENSION = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn() || !(param.getResult() instanceof Integer)) {
                return;
            }
            int dp = ((Integer) param.getResult()).intValue();
            if (dp <= 0 || param.args == null || param.args.length == 0 || !(param.args[0] instanceof Context)) {
                return;
            }
            if (DEPTH.get()[0] <= 0 && !Boolean.TRUE.equals(DEX_UI.get()) && !calledFromDexUi()) {
                return;
            }
            float density = contextDensity((Context) param.args[0]);
            if (density < 1.05f) {
                return;
            }
            int px = Math.round(dp * density * (scalePercent / 100f));
            if (px == dp) {
                return;
            }
            param.setResult(Integer.valueOf(px));
            if (!loggedDensity) {
                loggedDensity = true;
                XposedBridge.log(TAG + ": dex size " + dp + "dp -> " + px
                        + "px at device density " + density + " scale " + scalePercent + "%");
            }
        }
    };

    private static float contextDensity(Context context) {
        try {
            Object resources = context.getClass().getMethod("getResources").invoke(context);
            Object metrics = resources.getClass().getMethod("getDisplayMetrics").invoke(resources);
            return metrics.getClass().getField("density").getFloat(metrics);
        } catch (Throwable error) {
            return 0f;
        }
    }

    private static boolean calledFromDexUi() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int limit = stack.length < 36 ? stack.length : 36;
        for (int i = 0; i < limit; i++) {
            String name = stack[i].getClassName();
            if (name.indexOf("taskbar") >= 0 || name.indexOf("dexpanel") >= 0 || name.startsWith("p4.")) {
                return true;
            }
        }
        return false;
    }

    private static void readSettings(Context context) {
        if (context == null) return;
        ModConfig.reload(context);
        int scale = clampScale(ModConfig.getInt(context, ModConfig.KEY_TASKBAR_ICON_SCALE, SCALE_DEFAULT));
        int heightScale = clamp(ModConfig.getInt(context, ModConfig.KEY_TASKBAR_HEIGHT_SCALE, 100), 65, 135);
        boolean enabled = ModConfig.isModuleEnabled(context)
                && ModConfig.getBoolean(context, ModConfig.KEY_DEX_TASKBAR_ENABLED, true);
        boolean forceDex = ModConfig.getBoolean(context, ModConfig.KEY_FORCE_INTERNAL_DEX, false);
        boolean status = ModConfig.getBoolean(context, ModConfig.KEY_HIDE_STATUS_BAR, true);
        boolean arrows = ModConfig.getBoolean(context, ModConfig.KEY_HIDE_PAGE_ARROWS, true);
        boolean gestures = ModConfig.getBoolean(context, ModConfig.KEY_BLOCK_HOME_GESTURES, true);
        boolean finder = ModConfig.getBoolean(context, ModConfig.KEY_SHOW_DEX_FINDER_BTN, true);
        int panelMode = ModConfig.getInt(context, ModConfig.KEY_STATUS_PANEL_MODE, 0);
        int margin = clamp(ModConfig.getInt(context, ModConfig.KEY_SIDE_MARGIN_PCT, 40), 0, 100);
        int iconScale = clamp(ModConfig.getInt(context, ModConfig.KEY_HOME_ICON_SCALE, 100), 60, 160);
        homeIconScale = iconScale;
        int[] fit = gridForIconSize(context);
        int columns = fit[0];
        int rows = fit[1];
        int vertical = clamp(ModConfig.getInt(context, ModConfig.KEY_TOP_MARGIN_PCT, 0), 0, 100);
        int bottom = clamp(ModConfig.getInt(context, ModConfig.KEY_BOTTOM_MARGIN_PCT, 100), 0, 100);

        String signature = scale + "/" + heightScale + "/" + (enabled ? 1 : 0) + "/" + (forceDex ? 1 : 0)
                + "/" + (status ? 1 : 0) + "/" + (arrows ? 1 : 0)
                + "/" + margin + "/" + columns + "/" + rows + "/" + vertical + "/" + bottom
                + "/" + iconScale;
        boolean changed;
        synchronized (LauncherDexTaskbarHook.class) {
            scalePercent = scale;
            heightScalePercent = heightScale;
            modsEnabled = enabled;
            forceInternalDex = forceDex;
            hideStatusBar = status;
            hideArrows = arrows;
            blockHomeGestures = gestures;
            showFinderBtn = finder;
            statusPanelMode = panelMode;
            marginPercent = margin;
            verticalPercent = vertical;
            bottomPercent = bottom;
            homeIconScale = iconScale;
            settingsLoaded = true;
            targetColumns = columns;
            targetRows = rows;
            changed = appliedOptions != null && !appliedOptions.equals(signature);
            if (changed) {
                try {
                    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit()
                            .putInt("gridTries", 0)
                            .commit();
                } catch (Throwable ignored) {}
            }
            appliedOptions = signature;
        }
        XposedBridge.log(TAG + ": settings " + signature);
        watchIconSize(context);
        applyStatusBar();
        boolean gridChanged = applyGrid(context);
        if (changed || gridChanged) {
            restartForScale();
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static int clampScale(int percent) {
        if (percent < 50) return 50;
        if (percent > 150) return 150;
        return percent;
    }

    private static void applyStatusBar() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        keepTaskbarVisible();
        boolean effects = modsEnabled && dexOn();
        writeEffects(context, effects);
        ensureNotificationAccess(context, effects);
        boolean want = effects && hideStatusBar;
        writeStatusInset(context, want);
        try {
            String current = Settings.Global.getString(context.getContentResolver(), "policy_control");
            if (!want) {
                if (STATUS_POLICY.equals(current)) {
                    Settings.Global.putString(context.getContentResolver(), "policy_control", null);
                    XposedBridge.log(TAG + ": status bar restored");
                }
                return;
            }
            if (STATUS_POLICY.equals(current)) {
                return;
            }
            if (current != null && current.length() > 0) {
                return;
            }
            Settings.Global.putString(context.getContentResolver(), "policy_control", STATUS_POLICY);
            if (!loggedStatus) {
                loggedStatus = true;
                XposedBridge.log(TAG + ": status bar hidden");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": status bar change failed: " + error);
        }
    }

    private static void writeStatusInset(Context context, boolean collapse) {
        try {
            Settings.Global.putInt(context.getContentResolver(), INSET_KEY, collapse ? 1 : 0);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": status inset flag failed: " + error);
        }
    }

    private static void writeEffects(Context context, boolean on) {
        try {
            Settings.Global.putInt(context.getContentResolver(), EFFECTS_KEY, on ? 1 : 0);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": effects flag failed: " + error);
        }
    }

    private static void ensureNotificationAccess(Context context, boolean on) {
        try {
            String component = LAUNCHER + "/" + DEX_LISTENER;
            Class<?> secure = Class.forName("android.provider.Settings$Secure");
            Class<?> resolver = Class.forName("android.content.ContentResolver");
            Object current = secure.getMethod("getString", resolver, String.class).invoke(
                    null, context.getContentResolver(), "enabled_notification_listeners");
            String now = current instanceof String ? (String) current : "";
            String next = withListener(now, component, on);
            if (next.equals(now)) {
                return;
            }
            Object wrote = secure.getMethod("putString", resolver, String.class, String.class).invoke(
                    null, context.getContentResolver(), "enabled_notification_listeners", next);
            if (!Boolean.TRUE.equals(wrote)) {
                return;
            }
            rebindRequested = false;
            if (!loggedNotifAccess) {
                loggedNotifAccess = true;
                XposedBridge.log(TAG + ": notification listener " + (on ? "enabled" : "removed"));
            }
            if (on) {
                requestNotificationRebind();
            }
        } catch (Throwable ignored) {}
    }

    private static String withListener(String current, String component, boolean on) {
        ArrayList<String> parts = new ArrayList<String>();
        if (current != null && current.length() > 0) {
            for (String part : current.split(":")) {
                if (part.length() > 0 && !part.equals(component)) {
                    parts.add(part);
                }
            }
        }
        if (on) {
            parts.add(component);
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                joined.append(':');
            }
            joined.append(parts.get(i));
        }
        return joined.toString();
    }

    private static void keepTaskbarVisible() {
        if (taskbarVisFailed || !modsEnabled || !dexOn()) {
            return;
        }
        try {
            Class<?> type = Class.forName("com.samsung.android.multiwindow.MultiWindowManager");
            Object manager = type.getMethod("getInstance").invoke(null);
            type.getMethod("updateSystemBarVis", int.class, int.class)
                    .invoke(manager, Integer.valueOf(3), Integer.valueOf(3));
        } catch (Throwable error) {
            taskbarVisFailed = true;
        }
    }

    private static boolean applyGrid(Context context) {
        if (context == null || context.getFilesDir() == null) {
            return false;
        }
        if (modsEnabled && !dexOn()) {
            return false;
        }
        File dir = new File(context.getFilesDir(), "datastore");
        boolean changed = false;
        changed |= applyGridFile(
                context,
                new File(dir, "com.sec.android.app.launcher.prefs.preferences_pb"),
                "gridBaseX",
                "gridBaseY",
                "gridTries");
        changed |= applyGridFile(
                context,
                new File(dir, "com.sec.android.app.launcher.dex.prefs.preferences_pb"),
                "gridDexBaseX",
                "gridDexBaseY",
                "gridDexTries");
        return changed;
    }

    /**
     * Columns and rows are the most that still fit the One UI Home icon size.
     * Samsung stores that size as a fraction of the short screen side: about 0.072
     * at Medium, 0.005 less for Small and 0.005 more for Large. A cell has to keep
     * that icon, its label and a small gap inside 90% of the cell, which is the
     * launcher's own fit check. Tablet grids stay inside 6–16 and phone grids
     * inside 4–7. Values are stored in portrait axes because landscape shows them swapped.
     */
    private static int[] gridForIconSize(Context context) {
        DisplayMetrics metrics;
        try {
            metrics = context.getResources().getDisplayMetrics();
        } catch (Throwable error) {
            return new int[] {targetColumns, targetRows};
        }
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        int shortSide = Math.min(width, height);
        int level = iconSizeLevel(context);
        float fraction = 0.072f + (level - 1) * 0.005f;
        int icon = Math.max(Math.round(32f * metrics.density), Math.round(fraction * shortSide));
        icon = Math.max(1, Math.round(icon * (homeIconScale / 100f)));
        int label = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, metrics));
        int gap = Math.round(6f * metrics.density);
        int minWidth = Math.max(1, Math.round(icon / 0.9f));
        int minHeight = Math.max(1, Math.round((icon + label + gap) / 0.9f));
        // Workspace content is inset from the full display by the taskbar and page padding.
        int availW = Math.max(1, width - Math.round(70f * metrics.density));
        int availH = Math.max(1, height - Math.round(120f * metrics.density));
        boolean tablet = shortSide / metrics.density >= 600f;
        int min = tablet ? 6 : 4;
        int max = tablet ? 16 : 7;
        int screenCols = clamp(availW / minWidth, min, max);
        int screenRows = clamp(availH / minHeight, min, max);
        // CellX/CellY are the portrait axes. In landscape the workspace shows (CellY, CellX).
        boolean landscape = width > height;
        int cellX = landscape ? screenRows : screenCols;
        int cellY = landscape ? screenCols : screenRows;
        return new int[] {cellX, cellY};
    }

    private static int iconSizeLevel(Context context) {
        try {
            File file = new File(
                    context.getFilesDir(),
                    "datastore/com.sec.android.app.launcher.common.prefs.preferences_pb");
            int level = HomeGrid.readInt(file, "pref_item_size_level_key");
            if (level < 0) {
                return 1;
            }
            if (level > 2) {
                return 2;
            }
            return level;
        } catch (Throwable error) {
            return 1;
        }
    }

    private static void watchIconSize(final Context context) {
        if (iconSizeWatching || context == null || context.getFilesDir() == null) {
            return;
        }
        final File file = new File(
                context.getFilesDir(),
                "datastore/com.sec.android.app.launcher.common.prefs.preferences_pb");
        if (!file.isFile()) {
            return;
        }
        iconSizeWatching = true;
        final Handler handler = new Handler(Looper.getMainLooper());
        iconSizeObserver = new FileObserver(file.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Context current = appContext;
                        if (current != null) {
                            readSettings(current);
                        }
                    }
                });
            }
        };
        iconSizeObserver.startWatching();
    }

    private static boolean applyGridFile(
            Context context,
            File file,
            String baseKeyX,
            String baseKeyY,
            String triesKey) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            int currentX = HomeGrid.readInt(file, "Workspace.CellX");
            int currentY = HomeGrid.readInt(file, "Workspace.CellY");
            if (currentX < 0 || currentY < 0) {
                return false;
            }
            int baseX = prefs.getInt(baseKeyX, -1);
            int baseY = prefs.getInt(baseKeyY, -1);
            if (!modsEnabled) {
                if (baseX < 0 || baseY < 0) {
                    return false;
                }
                if (currentX == baseX && currentY == baseY) {
                    prefs.edit().remove(baseKeyX).remove(baseKeyY).putInt(triesKey, 0).commit();
                    return false;
                }
                if (prefs.getInt(triesKey, 0) >= 2) {
                    return false;
                }
                prefs.edit().putInt(triesKey, prefs.getInt(triesKey, 0) + 1).commit();
                HomeGrid.writeInt(file, "Workspace.CellX", baseX);
                HomeGrid.writeInt(file, "Workspace.CellY", baseY);
                XposedBridge.log(TAG + ": home grid restored to " + baseX + "x" + baseY);
                return true;
            }
            if (baseX < 0 || baseY < 0) {
                SharedPreferences grokPrefs = context.getSharedPreferences("externaldex", Context.MODE_PRIVATE);
                int gx = grokPrefs.getInt(baseKeyX, -1);
                int gy = grokPrefs.getInt(baseKeyY, -1);
                if ("gridBaseX".equals(baseKeyX)) {
                    gx = grokPrefs.getInt("gridBaseX", gx);
                    gy = grokPrefs.getInt("gridBaseY", gy);
                }
                baseX = gx > 0 ? gx : currentX;
                baseY = gy > 0 ? gy : currentY;
                prefs.edit().putInt(baseKeyX, baseX).putInt(baseKeyY, baseY).commit();
            }
            int wantX = targetColumns;
            int wantY = targetRows;
            if (currentX == wantX && currentY == wantY) {
                if (prefs.getInt(triesKey, 0) != 0) {
                    prefs.edit().putInt(triesKey, 0).commit();
                }
                return false;
            }
            if (prefs.getInt(triesKey, 0) >= 2) {
                return false;
            }
            prefs.edit().putInt(triesKey, prefs.getInt(triesKey, 0) + 1).commit();
            HomeGrid.writeInt(file, "Workspace.CellX", wantX);
            HomeGrid.writeInt(file, "Workspace.CellY", wantY);
            XposedBridge.log(TAG + ": home grid " + file.getName() + " "
                    + currentX + "x" + currentY + " -> " + wantX + "x" + wantY);
            return true;
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": home grid change failed: " + error);
            return false;
        }
    }

    private static void hookHomeGestures(ClassLoader loader) {
        // Swipe gestures (App Drawer & Notification Shade)
        hookGesture(loader, "Z3.a", false);
        hookGesture(loader, "G5.n", false);
        hookGesture(loader, "G5.c", false);
        hookGesture(loader, "G5.e", false);
        // Workspace long-press edit mode, pinch edit mode, and minus-one page controllers
        // (blocked when Dedicated Workspace is active so long-press/pinch/swipe don't trigger Home Edit mode)
        hookGesture(loader, "G5.h", true);
        hookGesture(loader, "G5.l", true);
        hookGesture(loader, "G5.k", true);
    }

    private static void hookGesture(ClassLoader loader, String className, boolean dedicatedWorkspaceOnly) {
        XC_MethodHook hook = dedicatedWorkspaceOnly ? BLOCK_DEDICATED_WORKSPACE_TOUCH : BLOCK_HOME_SWIPE_GESTURE;
        try {
            Class<?> type = Class.forName(className, false, loader);
            Class<?> event = Class.forName("android.view.MotionEvent");
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                String name = method.getName();
                if (params.length == 1
                        && event.isAssignableFrom(params[0])
                        && ("onControllerInterceptTouchEvent".equals(name)
                        || "onControllerTouchEvent".equals(name)
                        || "onControllerDispatchTouchEvent".equals(name))) {
                    XposedBridge.hookMethod(method, hook);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook gesture " + className + ": " + error);
        }
    }

    private static boolean isDedicatedWorkspaceActive() {
        Context ctx = appContext;
        return ctx != null && ModConfig.getBoolean(ctx, ModConfig.KEY_CUSTOM_DESKTOP_ENABLED, false);
    }

    private static boolean shouldBlockHomeGestures() {
        if (!modsEnabled || !dexOn()) {
            return false;
        }
        return blockHomeGestures || isDedicatedWorkspaceActive();
    }

    private static final XC_MethodHook BLOCK_HOME_SWIPE_GESTURE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!shouldBlockHomeGestures()) {
                return;
            }
            if (((Method) param.method).getReturnType() == boolean.class) {
                param.setResult(Boolean.FALSE);
            } else {
                param.setResult(null);
            }
            if (!loggedDrawer) {
                loggedDrawer = true;
                XposedBridge.log(TAG + ": home swipe gesture ignored");
            }
        }
    };

    private static final XC_MethodHook BLOCK_DEDICATED_WORKSPACE_TOUCH = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn() || !isDedicatedWorkspaceActive()) {
                return;
            }
            if (((Method) param.method).getReturnType() == boolean.class) {
                param.setResult(Boolean.FALSE);
            } else {
                param.setResult(null);
            }
            if (!loggedShade) {
                loggedShade = true;
                XposedBridge.log(TAG + ": workspace edit gesture ignored for dedicated workspace");
            }
        }
    };

    private static void hookDesktopOptions(ClassLoader loader) {
        String[] pages = {
                "com.honeyspace.ui.common.FastRecyclerView",
                "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceFastRecyclerView",
                "com.honeyspace.ui.honeypots.applist.presentation.ApplistFastRecyclerView"
        };
        for (int i = 0; i < pages.length; i++) {
            hookArrowMethods(loader, pages[i]);
        }
        try {
            Class<?> ext = Class.forName(
                    "com.honeyspace.common.context.ContextExtensionKt", false, loader);
            int hooked = 0;
            for (Method method : ext.getDeclaredMethods()) {
                String name = method.getName();
                if (("getFraction".equals(name) || "getFractionValue".equals(name))
                        && method.getParameterTypes().length == 3) {
                    XposedBridge.hookMethod(method, FRACTION_MARGIN);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": page margin hooks " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook page margins: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.ui.common.CellLayoutInfoImpl",
                    loader,
                    "getBottomPadding",
                    Context.class,
                    android.graphics.Point.class,
                    BOTTOM_PADDING);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook bottom padding: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.ui.common.iconview.style.AbsItemStyleFactory",
                    loader,
                    "getItemStyle",
                    android.util.Size.class,
                    "com.honeyspace.sdk.source.entity.StyleOption",
                    android.graphics.Point.class,
                    boolean.class,
                    HOME_ICON_SIZE);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook home icon size: " + error);
        }
        hookSuperKey(loader);
    }

    private static void hookArrowMethods(ClassLoader loader, String className) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if ("needToHidePageNavigation".equals(method.getName())) {
                    XposedBridge.hookMethod(method, HIDE_PAGE_ARROWS);
                } else if ("showPageNavigation".equals(method.getName())) {
                    XposedBridge.hookMethod(method, SKIP_PAGE_ARROWS);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook page arrows on " + className + ": " + error);
        }
    }

    private static boolean arrowsActive() {
        return modsEnabled && hideArrows && dexOn();
    }

    private static final XC_MethodHook HIDE_PAGE_ARROWS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (arrowsActive()) {
                param.setResult(Boolean.TRUE);
            }
        }
    };

    private static final XC_MethodHook SKIP_PAGE_ARROWS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (arrowsActive()) {
                param.setResult(null);
            }
        }
    };

    private static final XC_MethodHook FRACTION_MARGIN = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn()) {
                return;
            }
            if (!(param.args[0] instanceof Context) || !(param.args[1] instanceof Integer)) {
                return;
            }
            int kind = paddingKind((Context) param.args[0], ((Integer) param.args[1]).intValue());
            // Bottom padding is applied as one total in getBottomPadding.
            if (kind == 0 || kind == 3) {
                return;
            }
            int percent = kind == 1 ? marginPercent : verticalPercent;
            if (percent >= 100) {
                return;
            }
            float scale = percent / 100f;
            Object result = param.getResult();
            if (result instanceof Integer) {
                int px = ((Integer) result).intValue();
                if (px <= 0) {
                    return;
                }
                int next = Math.round(px * scale);
                param.setResult(Integer.valueOf(next));
                logMargin(kind, px + "px -> " + next + "px at " + percent + "%");
            } else if (result instanceof Float) {
                float value = ((Float) result).floatValue();
                if (value <= 0f) {
                    return;
                }
                param.setResult(Float.valueOf(value * scale));
                logMargin(kind, value + " -> " + (value * scale) + " at " + percent + "%");
            }
        }
    };

    private static final XC_MethodHook BOTTOM_PADDING = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn() || !(param.getResult() instanceof Integer)) {
                return;
            }
            int px = ((Integer) param.getResult()).intValue();
            if (px <= 0) {
                return;
            }
            float density = 1.75f;
            if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                try {
                    density = ((Context) param.args[0]).getResources().getDisplayMetrics().density;
                } catch (Throwable ignored) {}
            }
            int taskbar = Math.max(1, Math.round(56f * density * heightScalePercent / 100f));
            int next;
            if (px <= taskbar) {
                next = Math.round(px * bottomPercent / 100f);
            } else {
                next = taskbar + Math.round((px - taskbar) * (bottomPercent / 100f));
            }
            if (next == px) {
                return;
            }
            param.setResult(Integer.valueOf(next));
            logMargin(3, px + "px -> " + next + "px at " + bottomPercent + "%");
        }
    };

    private static final XC_MethodHook HOME_ICON_SIZE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            int scale = homeIconScale;
            if (scale == 100 || !modsEnabled || !dexOn() || param.getResult() == null) {
                return;
            }
            if (param.args != null && param.args.length > 0 && param.args[0] instanceof android.util.Size) {
                android.util.Size area = (android.util.Size) param.args[0];
                if (area.getWidth() < 100 && area.getHeight() < 100) {
                    return;
                }
            }
            try {
                int size = XposedHelpers.getIntField(param.getResult(), "itemSize");
                if (size <= 0) {
                    return;
                }
                int next = Math.max(1, Math.round(size * (scale / 100f)));
                if (next != size) {
                    XposedHelpers.setIntField(param.getResult(), "itemSize", next);
                }
            } catch (Throwable ignored) {}
        }
    };

    private static void hookSuperKey(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.ui.common.entity.HoneySpaceUIComponent",
                    loader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            homeUiComponent = param.thisObject;
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch the home UI: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "j2.b",
                    loader,
                    "handleKeyGestureEvent",
                    "android.hardware.input.KeyGestureEvent",
                    android.os.IBinder.class,
                    SUPER_KEY);
            XposedBridge.log(TAG + ": super key hooked");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook the Super key: " + error);
        }
    }

    private static final XC_MethodHook SUPER_KEY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn() || param.args == null || param.args[0] == null) {
                return;
            }
            try {
                int type = ((Integer) XposedHelpers.callMethod(param.args[0], "getKeyGestureType")).intValue();
                // 21 is KEY_GESTURE_TYPE_ALL_APPS, which the Windows key sends.
                if (type != 21) {
                    return;
                }
                if (openDexAppDrawer()) {
                    param.setResult(null);
                }
            } catch (Throwable ignored) {}
        }
    };

    /** Opens the DeX taskbar app drawer instead of the full-screen home app drawer. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean openDexAppDrawer() {
        Object component = homeUiComponent;
        if (component == null) {
            return false;
        }
        try {
            Object shared = XposedHelpers.callMethod(component, "getHoneySharedData");
            if (shared == null) {
                return false;
            }
            ClassLoader loader = component.getClass().getClassLoader();
            Class honeyType = Class.forName("com.honeyspace.sdk.HoneyType", false, loader);
            Class panelState = Class.forName("com.honeyspace.common.data.PanelState", false, loader);
            Class<?> infoType = Class.forName("com.honeyspace.common.data.PanelStateInfo", false, loader);
            Object overlay = Enum.valueOf(honeyType, "OVERLAY_APPS");
            Object toggle = Enum.valueOf(panelState, "TOGGLE");
            Class<?> marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker", false, loader);
            java.lang.reflect.Constructor<?> ctor = infoType.getDeclaredConstructor(
                    honeyType, android.view.View.class, panelState, Object.class, int.class, marker);
            ctor.setAccessible(true);
            Object info = ctor.newInstance(overlay, null, toggle, null, Integer.valueOf(8), null);
            Class<?> sharedKt = Class.forName("com.honeyspace.sdk.HoneySharedDataKt", false, loader);
            Method getEvent = null;
            for (Method method : sharedKt.getDeclaredMethods()) {
                if ("getEvent".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    getEvent = method;
                    break;
                }
            }
            if (getEvent == null) {
                return false;
            }
            getEvent.setAccessible(true);
            Object flow = getEvent.invoke(null, shared, "UpdatePanelWindow");
            if (flow == null) {
                return false;
            }
            Object emitted = flow.getClass().getMethod("tryEmit", Object.class).invoke(flow, info);
            if (!loggedSuper && Boolean.TRUE.equals(emitted)) {
                loggedSuper = true;
                XposedBridge.log(TAG + ": Super key opens the DeX app drawer");
            }
            return Boolean.TRUE.equals(emitted);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": DeX app drawer toggle failed: " + error);
            return false;
        }
    }

    private static void logMargin(int kind, String message) {
        if (kind == 1 && !loggedMargin) {
            loggedMargin = true;
            XposedBridge.log(TAG + ": side margin " + message);
        } else if (kind == 2 && !loggedVertical) {
            loggedVertical = true;
            XposedBridge.log(TAG + ": top margin " + message);
        } else if (kind == 3 && !loggedBottom) {
            loggedBottom = true;
            XposedBridge.log(TAG + ": bottom margin " + message);
        }
    }

    private static int paddingKind(Context context, int id) {
        synchronized (MARGIN_NAMES) {
            Integer cached = MARGIN_NAMES.get(Integer.valueOf(id));
            if (cached != null) {
                return cached.intValue();
            }
        }
        int kind = 0;
        try {
            Object resources = context.getClass().getMethod("getResources").invoke(context);
            String name = (String) resources.getClass()
                    .getMethod("getResourceEntryName", int.class)
                    .invoke(resources, Integer.valueOf(id));
            if (name != null && name.indexOf("apps_") < 0) {
                if (name.indexOf("page_side_padding") >= 0
                        || name.indexOf("page_left_padding") >= 0
                        || name.indexOf("page_right_padding") >= 0) {
                    kind = 1;
                } else if (name.indexOf("page_bottom_padding") >= 0) {
                    kind = 3;
                } else if (name.indexOf("page_top_padding") >= 0) {
                    kind = 2;
                }
            }
        } catch (Throwable ignored) {}
        synchronized (MARGIN_NAMES) {
            if (MARGIN_NAMES.size() < 128) {
                MARGIN_NAMES.put(Integer.valueOf(id), Integer.valueOf(kind));
            }
        }
        return kind;
    }

    private static void hookPanelClicks(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("J6.f", loader, "executeBindings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int attached = attachNamedClicks(
                            param.thisObject,
                            new String[] {"c", "e", "h", "i", "j", "m"},
                            new String[] {"y", "v", "u", "x", "w", "t"});
                    if (!loggedClickHook) {
                        loggedClickHook = true;
                        XposedBridge.log(TAG + ": status clicks attached " + attached);
                    }
                    attachNotificationBadge(param.thisObject);
                    fixBatteryIcon(param.thisObject);
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook status clicks: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod("J6.b", loader, "executeBindings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    attachNamedClicks(
                            param.thisObject,
                            new String[] {"c", "f"},
                            new String[] {"v", "w"});
                    try {
                        Object finderView = field(param.thisObject, "c");
                        if (finderView instanceof View) {
                            ((View) finderView).setVisibility(showFinderBtn ? View.VISIBLE : View.GONE);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook finder clicks: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "J6.f", loader, "_internalCallbackOnClick", int.class, View.class, STATUS_CLICK);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch status click: " + error);
        }
        try {
            Class<?> repo = Class.forName("F4.h", false, loader);
            int hooked = 0;
            for (Method method : repo.getDeclaredMethods()) {
                if ("j".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, NOTIFICATION_COUNT);
                    hooked++;
                } else if ("f".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, PUBLISH_NOTIFICATIONS);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": notification count hooks " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook notification count: " + error);
        }
        try {
            Class<?> listener = Class.forName(
                    "com.honeyspace.ui.honeypots.dexpanel.notification.external.NotificationListener",
                    false, loader);
            for (Method method : listener.getDeclaredMethods()) {
                String name = method.getName();
                if ("onListenerConnected".equals(name) && method.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(method, REMEMBER_NOTIFICATIONS);
                } else if ("onListenerDisconnected".equals(name) && method.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(method, FORGET_NOTIFICATIONS);
                }
            }
            requestNotificationRebind();
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch notification listener: " + error);
        }
        hookQuickSettings(loader);
        try {
            XposedHelpers.findAndHookMethod("p4.f", loader, "b", "p4.c", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (modsEnabled && dexOn() && statusPanelMode == 1
                            && param.args != null && param.args.length > 0 && param.args[0] instanceof Enum) {
                        String panelName = ((Enum<?>) param.args[0]).name();
                        if ("NOTIFICATION".equals(panelName) || "QUICK_SETTINGS".equals(panelName)) {
                            closeOpenDexPanels(param.thisObject);
                            expandSystemShade("QUICK_SETTINGS".equals(panelName));
                            param.setResult(null);
                            return;
                        }
                    }
                    DEX_UI.set(Boolean.TRUE);
                    fillPanelList(param.thisObject);
                    startPanelServices(param.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DEX_UI.remove();
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook panel toggle: " + error);
        }
    }

    private static volatile long lastShadeTriggerMs;

    private static final XC_MethodHook STATUS_CLICK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length == 0) {
                return;
            }
            if (!modsEnabled || !dexOn() || !(param.args[0] instanceof Integer)) {
                return;
            }
            // In J6.f._internalCallbackOnClick:
            // key 1 = Accessibility, key 2 = ScreenCapture, key 3 = Volume (p4.c.h),
            // key 4 = Notification (p4.c.c), key 5 = QuickSettings (p4.c.e), key 6 = Calendar (p4.c.f)
            int key = ((Integer) param.args[0]).intValue();
            if (statusPanelMode == 1 && (key == 4 || key == 5)) {
                expandSystemShade(key == 5);
                param.setResult(null);
                return;
            }
            if (key == 4) {
                publishDexNotifications();
            }
        }
    };

    private static void closeOpenDexPanels(Object manager) {
        if (manager == null) return;
        try {
            Object map = field(manager, "i");
            if (map instanceof Map) {
                for (Object provider : ((Map<?, ?>) map).values()) {
                    if (provider == null) continue;
                    Object panel = provider.getClass().getMethod("get").invoke(provider);
                    if (panel != null) {
                        try {
                            panel.getClass().getMethod("d").invoke(panel);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("WrongConstant")
    private static void expandSystemShade(boolean quickSettings) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastShadeTriggerMs < 150L) {
            return;
        }
        lastShadeTriggerMs = now;
        try {
            Context c = appContext;
            if (c == null) return;
            // Send cross-process command via Settings.Global to SystemUI, which holds
            // android.permission.EXPAND_STATUS_BAR and calls IStatusBarService with barType=0
            // (bypassing StatusBarManager.getBarTypeFromContext() which returns 1 in DeX mode).
            String cmd = (quickSettings ? "qs:" : "notif:") + now;
            android.provider.Settings.Global.putString(
                    c.getContentResolver(), "internaldex_shade_cmd", cmd);
            XposedBridge.log(TAG + ": requested SystemUI shade (" + (quickSettings ? "QS" : "Notifications") + ")");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": expandSystemShade failed: " + error);
        }
    }

    private static final XC_MethodHook PUBLISH_NOTIFICATIONS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn()) {
                return;
            }
            publishDexNotifications();
        }
    };

    private static void publishDexNotifications() {
        Object listener = dexListener;
        if (listener == null) {
            if (!rebindRequested) {
                rebindRequested = true;
                requestNotificationRebind();
            }
            return;
        }
        try {
            Object active = listener.getClass().getMethod("getActiveNotifications").invoke(listener);
            Object[] items = active instanceof Object[] ? (Object[]) active : new Object[0];
            Method allow = null;
            for (Method method : listener.getClass().getDeclaredMethods()) {
                if ("d".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    allow = method;
                    allow.setAccessible(true);
                    break;
                }
            }
            ArrayList<Object> kept = new ArrayList<Object>();
            int raw = 0;
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                raw++;
                if (allow != null && !Boolean.TRUE.equals(allow.invoke(listener, item))) {
                    continue;
                }
                kept.add(item);
            }
            if (kept.isEmpty() && raw > 0) {
                for (Object item : items) {
                    if (item != null) {
                        kept.add(item);
                    }
                }
            }
            if (raw == 0 && notifPublishTries < 4) {
                notifPublishTries++;
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        publishDexNotifications();
                    }
                }, 700);
                return;
            }
            Class<?> sbnType = Class.forName("android.service.notification.StatusBarNotification");
            Object array = java.lang.reflect.Array.newInstance(sbnType, kept.size());
            for (int i = 0; i < kept.size(); i++) {
                java.lang.reflect.Array.set(array, i, kept.get(i));
            }
            Class<?> intentType = Class.forName("android.content.Intent");
            Object intent = intentType.getConstructor(String.class).newInstance(
                    "ACTION_NOTIFICATION_LISTENER_CONNECTED");
            Class<?> parcelable = Class.forName("android.os.Parcelable");
            Class<?> parcelArray = java.lang.reflect.Array.newInstance(parcelable, 0).getClass();
            intentType.getMethod("putExtra", String.class, parcelArray).invoke(intent, "data", array);
            Object ranking = listener.getClass().getMethod("getCurrentRanking").invoke(listener);
            intentType.getMethod("putExtra", String.class, parcelable).invoke(intent, "rank", ranking);
            Object filter = listener.getClass().getMethod("getCurrentInterruptionFilter").invoke(listener);
            intentType.getMethod("putExtra", String.class, int.class).invoke(
                    intent, "interruptionFilter", filter);
            Object app = listener.getClass().getMethod("getApplicationContext").invoke(listener);
            Class<?> broadcasts = Class.forName(
                    "androidx.localbroadcastmanager.content.LocalBroadcastManager",
                    false, listener.getClass().getClassLoader());
            Object manager = broadcasts.getMethod("getInstance", Context.class).invoke(null, app);
            broadcasts.getMethod("sendBroadcast", intentType).invoke(manager, intent);
            if (kept.size() > 0 && !loggedNotifRefresh) {
                loggedNotifRefresh = true;
                XposedBridge.log(TAG + ": notification list reloaded " + kept.size() + " of " + raw);
            }
        } catch (Throwable error) {
            if (!loggedNotifRefresh) {
                loggedNotifRefresh = true;
                XposedBridge.log(TAG + ": notification reload failed: " + error);
            }
        }
    }

    private static void requestNotificationRebind() {
        try {
            Class<?> service = Class.forName("android.service.notification.NotificationListenerService");
            Class<?> component = Class.forName("android.content.ComponentName");
            Object name = component.getConstructor(String.class, String.class).newInstance(
                    LAUNCHER, DEX_LISTENER);
            service.getMethod("requestRebind", component).invoke(null, name);
        } catch (Throwable ignored) {}
    }

    private static final XC_MethodHook NOTIFICATION_COUNT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn() || param.args.length == 0) {
                return;
            }
            int count = countNotifications(param.args[0]);
            if (count < 0) {
                return;
            }
            publishNotificationCount(count);
        }
    };

    private static void publishNotificationCount(int count) {
        if (loggedBadge && notificationCount == count) {
            return;
        }
        notificationCount = count;
        loggedBadge = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                paintNotificationBadges();
            }
        });
    }

    private static int countNotifications(Object arg) {
        if (!(arg instanceof Collection)) {
            return -1;
        }
        int count = 0;
        for (Object item : (Collection<?>) arg) {
            if (item == null) {
                continue;
            }
            try {
                Object summary = item.getClass().getMethod("isGroupSummary").invoke(item);
                if (Boolean.TRUE.equals(summary)) {
                    continue;
                }
            } catch (Throwable ignored) {
                return -1;
            }
            count++;
        }
        return count;
    }

    private static void attachNotificationBadge(Object binding) {
        if (!modsEnabled || !dexOn()) {
            return;
        }
        Object button = field(binding, "e");
        if (!(button instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) button;
        synchronized (NOTIFICATION_BADGES) {
            if (NOTIFICATION_BADGES.containsKey(group)) {
                paintNotificationBadges();
                return;
            }
        }
        try {
            Context context = (Context) group.getClass().getMethod("getContext").invoke(group);
            TextView badge = new TextView(context);
            badge.setTextSize(10f);
            badge.setPadding(dp(context, 3), 0, dp(context, 3), 0);
            badge.getClass().getMethod("setTextColor", int.class).invoke(badge, Integer.valueOf(0xFFFFFFFF));
            badge.getClass().getMethod("setGravity", int.class).invoke(badge, Integer.valueOf(17));
            badge.getClass().getMethod("setMinWidth", int.class).invoke(badge, Integer.valueOf(dp(context, 14)));
            badge.getClass().getMethod("setMinHeight", int.class).invoke(badge, Integer.valueOf(dp(context, 14)));
            badge.getClass().getMethod("setClickable", boolean.class).invoke(badge, Boolean.FALSE);
            Object dot = Class.forName("android.graphics.drawable.GradientDrawable").newInstance();
            dot.getClass().getMethod("setShape", int.class).invoke(dot, Integer.valueOf(1));
            dot.getClass().getMethod("setColor", int.class).invoke(dot, Integer.valueOf(0xFFE53935));
            badge.getClass().getMethod("setBackground", Class.forName("android.graphics.drawable.Drawable"))
                    .invoke(badge, dot);
            Class<?> params = Class.forName("android.widget.FrameLayout$LayoutParams");
            Object layout = params.getConstructor(int.class, int.class)
                    .newInstance(Integer.valueOf(-2), Integer.valueOf(-2));
            params.getField("gravity").setInt(layout, 8388661);
            group.getClass().getMethod("addView", View.class, ViewGroup.LayoutParams.class)
                    .invoke(group, badge, layout);
            synchronized (NOTIFICATION_BADGES) {
                NOTIFICATION_BADGES.put(group, badge);
            }
            paintNotificationBadges();
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": notification badge failed: " + error);
        }
    }

    private static void paintNotificationBadges() {
        int count = notificationCount;
        String label = count > 99 ? "99+" : Integer.toString(count);
        synchronized (NOTIFICATION_BADGES) {
            for (TextView badge : NOTIFICATION_BADGES.values()) {
                if (badge == null) {
                    continue;
                }
                try {
                    if (count <= 0) {
                        badge.getClass().getMethod("setVisibility", int.class)
                                .invoke(badge, Integer.valueOf(8));
                    } else {
                        badge.setText(label);
                        badge.getClass().getMethod("setVisibility", int.class)
                                .invoke(badge, Integer.valueOf(0));
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static int dp(Context context, int value) {
        float density = 2f;
        try {
            Object resources = context.getClass().getMethod("getResources").invoke(context);
            Object metrics = resources.getClass().getMethod("getDisplayMetrics").invoke(resources);
            density = metrics.getClass().getField("density").getFloat(metrics);
        } catch (Throwable ignored) {}
        if (density < 1f) {
            density = 1f;
        }
        return Math.round(value * density);
    }

    private static final XC_MethodHook REMEMBER_NOTIFICATIONS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            dexListener = param.thisObject;
            if (!loggedListener) {
                loggedListener = true;
                XposedBridge.log(TAG + ": notification listener connected");
            }
            if (modsEnabled && dexOn()) {
                publishDexNotifications();
            }
        }
    };

    private static final XC_MethodHook FORGET_NOTIFICATIONS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (dexListener == param.thisObject) {
                dexListener = null;
            }
        }
    };

    private static void hookQuickSettings(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "com.honeyspace.ui.honeypots.dexpanel.quicksettings.viewmodel.QuickSettingViewModel",
                    false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if ("b".equals(method.getName()) && method.getParameterTypes().length == 3) {
                    XposedBridge.hookMethod(method, SAFE_TILE_UPDATE);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not guard quick settings: " + error);
        }
    }

    private static final XC_MethodHook SAFE_TILE_UPDATE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (Boolean.TRUE.equals(IN_TILE_UPDATE.get())) {
                return;
            }
            IN_TILE_UPDATE.set(Boolean.TRUE);
            try {
                try {
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                } catch (InvocationTargetException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof IndexOutOfBoundsException) {
                        if (!loggedQsGuard) {
                            loggedQsGuard = true;
                            XposedBridge.log(TAG + ": ignored empty quick settings update");
                        }
                    } else if (cause != null) {
                        throw cause;
                    } else {
                        throw error;
                    }
                }
                param.setResult(null);
            } finally {
                IN_TILE_UPDATE.remove();
            }
        }
    };

    private static void hookBatteryDrawable(ClassLoader loader) {
        try {
            Class<?> type = Class.forName("R6.d", false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if ("g".equals(method.getName()) && method.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(method, WIDEN_BATTERY);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook battery drawable: " + error);
        }
    }

    private static final XC_MethodHook WIDEN_BATTERY = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn()) {
                return;
            }
            widenBatteryDrawable(param.thisObject);
        }
    };

    private static void widenBatteryDrawable(Object drawable) {
        try {
            Class<?> type = drawable.getClass();
            Field widthField = type.getDeclaredField("r");
            Field heightField = type.getDeclaredField("s");
            Field stableField = type.getDeclaredField("u");
            widthField.setAccessible(true);
            heightField.setAccessible(true);
            stableField.setAccessible(true);
            int width = widthField.getInt(drawable);
            int height = heightField.getInt(drawable);
            int stable = stableField.getInt(drawable);
            if (width <= 0 || height <= 0) {
                return;
            }
            int thick = stable > 0 ? stable : Math.min(width, height);
            int wide = Math.max(Math.max(width, height), thick * 2);
            if (width == wide && height == thick) {
                return;
            }
            widthField.setInt(drawable, wide);
            heightField.setInt(drawable, thick);
            type.getMethod("setBounds", int.class, int.class, int.class, int.class)
                    .invoke(drawable, Integer.valueOf(0), Integer.valueOf(0),
                            Integer.valueOf(wide), Integer.valueOf(thick));
        } catch (Throwable ignored) {}
    }

    private static void fixBatteryIcon(Object binding) {
        if (!modsEnabled || !dexOn()) {
            return;
        }
        Object meter = field(field(binding, "p"), "f");
        if (!(meter instanceof View)) {
            return;
        }
        final View view = (View) meter;
        try {
            view.getClass().getMethod("post", Runnable.class).invoke(view, new Runnable() {
                @Override
                public void run() {
                    applyBatterySize(view);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void applyBatterySize(View meter) {
        if (!modsEnabled || !dexOn()) {
            return;
        }
        try {
            Object image = field(meter, "c");
            if (!(image instanceof View)) {
                return;
            }
            View icon = (View) image;
            Object drawable = icon.getClass().getMethod("getDrawable").invoke(icon);
            if (drawable == null) {
                return;
            }
            widenBatteryDrawable(drawable);
            int intrinsicWidth = ((Integer) drawable.getClass()
                    .getMethod("getIntrinsicWidth").invoke(drawable)).intValue();
            int intrinsicHeight = ((Integer) drawable.getClass()
                    .getMethod("getIntrinsicHeight").invoke(drawable)).intValue();
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                return;
            }
            int wantHeight = Math.min(intrinsicWidth, intrinsicHeight);
            int wantWidth = Math.max(intrinsicWidth, wantHeight * 2);
            int measuredWidth = icon.getWidth();
            int measuredHeight = icon.getHeight();
            boolean portrait = measuredWidth > 0 && measuredWidth <= measuredHeight;
            int key = wantWidth * 10000 + wantHeight;
            synchronized (BATTERY_SIZE) {
                Integer saved = BATTERY_SIZE.get(icon);
                if (saved != null && saved.intValue() == key && !portrait) {
                    return;
                }
                if (portrait) {
                    batteryFixTries++;
                    if (batteryFixTries > 6) {
                        return;
                    }
                }
                BATTERY_SIZE.put(icon, Integer.valueOf(key));
            }
            sizeBox(icon, wantWidth, wantHeight);
        } catch (Throwable ignored) {}
    }

    private static void sizeBox(View view, int width, int height) throws Exception {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && (params.width != width || params.height != height)) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
        view.getClass().getMethod("setMinimumWidth", int.class).invoke(view, Integer.valueOf(width));
        view.getClass().getMethod("setMinimumHeight", int.class).invoke(view, Integer.valueOf(height));
    }

    private static int attachNamedClicks(Object binding, String[] views, String[] listeners) {
        int attached = 0;
        for (int i = 0; i < views.length; i++) {
            Object view = field(binding, views[i]);
            Object listener = field(binding, listeners[i]);
            if (view == null || listener == null) {
                continue;
            }
            try {
                Object has = view.getClass().getMethod("hasOnClickListeners").invoke(view);
                if (Boolean.TRUE.equals(has)) {
                    continue;
                }
                Class<?> click = Class.forName("android.view.View$OnClickListener");
                view.getClass().getMethod("setOnClickListener", click).invoke(view, listener);
                attached++;
            } catch (Throwable ignored) {}
        }
        return attached;
    }

    private static void fillPanelList(Object manager) {
        try {
            Object map = field(manager, "i");
            if (!(map instanceof Map) || !((Map<?, ?>) map).isEmpty()) {
                return;
            }
            Object components = field(manager, "components");
            if (!(components instanceof Map) || ((Map<?, ?>) components).isEmpty()) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> panels = (Map<Object, Object>) map;
            int count = 0;
            for (Object provider : ((Map<?, ?>) components).values()) {
                if (provider == null) {
                    continue;
                }
                Object panel = provider.getClass().getMethod("get").invoke(provider);
                if (panel == null) {
                    continue;
                }
                Object key = panel.getClass().getMethod("g").invoke(panel);
                if (key == null) {
                    continue;
                }
                panels.put(key, provider);
                count++;
            }
            XposedBridge.log(TAG + ": panel list filled " + count);
        } catch (Throwable error) {
            if (!loggedPanels) {
                loggedPanels = true;
                XposedBridge.log(TAG + ": panel list fill failed: " + error);
            }
        }
    }

    private static void startPanelServices(Object manager) {
        if (panelServicesStarted || !modsEnabled || !dexOn() || manager == null) {
            return;
        }
        int bound = 0;
        try {
            Object componentServices = field(manager, "componentServices");
            Object running = field(manager, "j");
            if (componentServices instanceof Map && running instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> dest = (Map<Object, Object>) running;
                for (Object provider : ((Map<?, ?>) componentServices).values()) {
                    if (!isProvider(manager, provider)) {
                        continue;
                    }
                    Object service = provide(provider);
                    if (service == null) {
                        continue;
                    }
                    try {
                        Method keyOf = noArg(service.getClass(), "a");
                        if (keyOf == null) {
                            continue;
                        }
                        Object key = keyOf.invoke(service);
                        if (key != null) {
                            dest.put(key, provider);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            Object components = field(manager, "components");
            if (components instanceof Map) {
                for (Object provider : ((Map<?, ?>) components).values()) {
                    if (!isProvider(manager, provider)) {
                        continue;
                    }
                    watchPanelProvider(manager, provider);
                    Object panel = provide(provider);
                    if (panel == null) {
                        continue;
                    }
                    try {
                        armPanel(manager, panel, provider);
                        Method start = noArg(panel.getClass(), "m");
                        if (start != null) {
                            start.invoke(panel);
                            bound++;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            Object sysui = field(manager, "desktopSysUi");
            if (sysui != null) {
                try {
                    Method register = noArg(sysui.getClass(), "register");
                    if (register != null) {
                        register.invoke(sysui);
                    }
                } catch (Throwable ignored) {}
            }
            panelServicesStarted = bound > 0;
        } catch (Throwable ignored) {}
    }

    private static boolean isProvider(Object manager, Object value) {
        if (value == null || manager == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName("javax.inject.Provider", false, manager.getClass().getClassLoader());
            return type.isInstance(value);
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private static void watchPanelProvider(final Object manager, final Object provider) {
        PANEL_PROVIDER_MANAGERS.put(provider, manager);
        Class<?> type = provider.getClass();
        synchronized (HOOKED_PROVIDER_GET) {
            if (!HOOKED_PROVIDER_GET.add(type)) {
                return;
            }
        }
        try {
            Method get = type.getMethod("get");
            XposedBridge.hookMethod(get, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object owner = PANEL_PROVIDER_MANAGERS.get(param.thisObject);
                    Object panel = param.getResult();
                    if (owner == null || panel == null) {
                        return;
                    }
                    try {
                        armPanel(owner, panel, param.thisObject);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean armPanel(Object manager, Object panel, Object provider) throws Exception {
        ClassLoader loader = manager.getClass().getClassLoader();
        Class<?> callbackType = Class.forName("n8.U", false, loader);
        Constructor<?> ctor = null;
        for (Constructor<?> candidate : callbackType.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 3 && params[0] == int.class) {
                ctor = candidate;
                break;
            }
        }
        if (ctor == null) {
            return false;
        }
        ctor.setAccessible(true);
        Object callback = ctor.newInstance(Integer.valueOf(5), manager, provider);
        Class<?> type = panel.getClass();
        while (type != null) {
            try {
                Field target = type.getDeclaredField("f");
                if (target.getType() == callbackType) {
                    target.setAccessible(true);
                    target.set(panel, callback);
                    return true;
                }
            } catch (NoSuchFieldException ignored) {}
            type = type.getSuperclass();
        }
        return false;
    }

    private static Object provide(Object provider) {
        if (provider == null) {
            return null;
        }
        try {
            return provider.getClass().getMethod("get").invoke(provider);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Method noArg(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(target);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static void hookDisplays() {
        try {
            hookDisplayFactory(Class.forName("android.app.ContextImpl"));
        } catch (Throwable ignored) {}
        try {
            hookWindowType(Class.forName("android.app.ContextImpl"));
        } catch (Throwable ignored) {}
        try {
            Class<?> options = Class.forName("android.app.ActivityOptions");
            for (Method method : options.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if ("setLaunchDisplayId".equals(method.getName())
                        && params.length == 1
                        && params[0] == int.class) {
                    XposedBridge.hookMethod(method, LAUNCH_DISPLAY);
                }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            for (Method method : global.getDeclaredMethods()) {
                if (!"addView".equals(method.getName()) || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, ADD_VIEW);
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> impl = Class.forName("android.view.WindowManagerImpl");
            for (Method method : impl.getDeclaredMethods()) {
                if (!"addView".equals(method.getName()) || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, ADD_VIEW);
            }
        } catch (Throwable ignored) {}
    }

    private static int hookWindowType(Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!"createWindowContext".equals(method.getName()) || Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, WINDOW_TYPE);
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    private static final XC_MethodHook WINDOW_TYPE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!dexOn() || param.args == null) {
                return;
            }
            for (int i = 0; i < param.args.length; i++) {
                if (!(param.args[i] instanceof Integer)) {
                    continue;
                }
                if (((Integer) param.args[i]).intValue() != PANEL_WINDOW_TYPE) {
                    continue;
                }
                param.args[i] = Integer.valueOf(OVERLAY_WINDOW_TYPE);
                if (!loggedWindowType) {
                    loggedWindowType = true;
                    XposedBridge.log(TAG + ": panel windows open on this screen");
                }
            }
        }
    };

    private static void hookStatusIcons(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.honeyspace.ui.honeypots.taskbar.presentation.StatusIconAreaView",
                    loader,
                    "onFinishInflate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof View) {
                                watchStatusIcons((View) param.thisObject);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook status icons: " + error);
        }
    }

    private static void watchStatusIcons(final View area) {
        try {
            Object observer = area.getClass().getMethod("getViewTreeObserver").invoke(area);
            Class<?> listener = Class.forName("android.view.ViewTreeObserver$OnGlobalLayoutListener");
            Object proxy = Proxy.newProxyInstance(
                    listener.getClassLoader(),
                    new Class<?>[] {listener},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onGlobalLayout".equals(method.getName())) {
                                growStatusIcons(area, area.getHeight());
                            }
                            return null;
                        }
                    });
            observer.getClass().getMethod("addOnGlobalLayoutListener", listener).invoke(observer, proxy);
        } catch (Throwable ignored) {}
    }

    private static boolean growStatusIcons(View area, int windowHeight) {
        if (!dexOn() || !(area instanceof ViewGroup)) {
            return false;
        }
        int dex = dexBarPx(area);
        int bar = windowHeight;
        if (bar <= 0 || dex <= 0 || bar <= dex + 1) {
            return false;
        }
        ViewGroup.LayoutParams areaParams = area.getLayoutParams();
        if (areaParams != null && area.getHeight() > 0 && area.getHeight() < bar - 1) {
            areaParams.height = bar;
            area.setLayoutParams(areaParams);
        }
        synchronized (GROWN_ICONS) {
            Integer done = GROWN_ICONS.get(area);
            if (done != null && done.intValue() == bar) {
                return true;
            }
        }
        float factor = bar / (float) dex;
        boolean changed = growIcons((ViewGroup) area, factor, bar);
        if (changed) {
            synchronized (GROWN_ICONS) {
                GROWN_ICONS.put(area, Integer.valueOf(bar));
            }
            XposedBridge.log(TAG + ": status icons scaled by " + factor);
        }
        return changed;
    }

    private static boolean growIcons(ViewGroup group, float factor, int bar) {
        boolean changed = false;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            if (isStatusIcon(child)) {
                int width = child.getWidth();
                int height = child.getHeight();
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null && width > 0 && height > 0 && height < bar - 1) {
                    params.width = Math.max(1, Math.round(width * factor));
                    params.height = Math.max(1, Math.round(height * factor));
                    child.setLayoutParams(params);
                    changed = true;
                }
            } else if (child instanceof ViewGroup) {
                changed |= growIcons((ViewGroup) child, factor, bar);
            }
        }
        return changed;
    }

    private static boolean isStatusIcon(View view) {
        String entry = entryName(view);
        if (entry != null) {
            if (entry.startsWith("btn_")
                    || "clock".equals(entry)
                    || "date".equals(entry)
                    || "date_and_clock".equals(entry)
                    || entry.contains("finder")
                    || entry.contains("battery")) {
                return true;
            }
        }
        String type = view.getClass().getName();
        if (type.contains("Battery") || type.endsWith("TextClock")) {
            return true;
        }
        boolean glyph = type.contains("ImageView")
                || type.contains("ImageButton")
                || type.endsWith("TextView");
        return glyph && insideStatusCluster(view);
    }

    private static boolean insideStatusCluster(View view) {
        Object current = view;
        for (int depth = 0; depth < 6 && current instanceof View; depth++) {
            String type = current.getClass().getName();
            if (type.endsWith("StatusIconAreaView") || type.contains("StatusPanel")) {
                return true;
            }
            String entry = entryName((View) current);
            if ("status_icon_area".equals(entry)
                    || "status_panel_container".equals(entry)
                    || "date_and_clock".equals(entry)) {
                return true;
            }
            try {
                current = current.getClass().getMethod("getParent").invoke(current);
            } catch (Throwable error) {
                return false;
            }
        }
        return false;
    }

    private static String entryName(View view) {
        try {
            Object id = view.getClass().getMethod("getId").invoke(view);
            if (!(id instanceof Integer) || ((Integer) id).intValue() <= 0) {
                return null;
            }
            Object resources = view.getClass().getMethod("getResources").invoke(view);
            Object name = resources.getClass()
                    .getMethod("getResourceEntryName", int.class)
                    .invoke(resources, id);
            return name instanceof String ? (String) name : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static int dexBarPx(View view) {
        if (cachedDexBarPx != 0) {
            return cachedDexBarPx;
        }
        try {
            Object resources = view.getClass().getMethod("getResources").invoke(view);
            Object context = view.getClass().getMethod("getContext").invoke(view);
            String packageName = (String) context.getClass().getMethod("getPackageName").invoke(context);
            Object id = resources.getClass()
                    .getMethod("getIdentifier", String.class, String.class, String.class)
                    .invoke(resources, "task_bar_height_dex", "dimen", packageName);
            if (!(id instanceof Integer) || ((Integer) id).intValue() == 0) {
                cachedDexBarPx = -1;
                return cachedDexBarPx;
            }
            Object px = resources.getClass()
                    .getMethod("getDimensionPixelSize", int.class)
                    .invoke(resources, id);
            if (px instanceof Integer && ((Integer) px).intValue() > 0) {
                cachedDexBarPx = ((Integer) px).intValue();
                return cachedDexBarPx;
            }
        } catch (Throwable ignored) {}
        cachedDexBarPx = -1;
        return cachedDexBarPx;
    }

    private static int hookDisplayFactory(Class<?> type) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            if (!"createDisplayContext".equals(name) && !"createWindowContext".equals(name)) {
                continue;
            }
            if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || !"android.view.Display".equals(params[0].getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, DISPLAY_CONTEXT);
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    private static final XC_MethodHook DISPLAY_CONTEXT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!dexOn() || param.args == null || param.args.length == 0 || param.args[0] == null) {
                return;
            }
            int id = displayIdOf(param.args[0]);
            if (id == 0 || id == 1 || id < 0) {
                return;
            }
            Object replacement = builtInDisplayObject();
            if (replacement == null) {
                return;
            }
            param.args[0] = replacement;
            if (!loggedRedirect) {
                loggedRedirect = true;
                XposedBridge.log(TAG + ": floating window display " + id + " redirected to built-in display");
            }
        }
    };

    private static final XC_MethodHook LAUNCH_DISPLAY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!dexOn() || param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                return;
            }
            int id = ((Integer) param.args[0]).intValue();
            if (id == 0 || id == 1) {
                return;
            }
            param.args[0] = Integer.valueOf(0);
        }
    };

    private static final XC_MethodHook ADD_VIEW = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(IN_RETRY.get()) || !dexOn() || param.args == null) {
                return;
            }
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                if (arg == null) {
                    continue;
                }
                if ("android.view.Display".equals(arg.getClass().getName())) {
                    int id = displayIdOf(arg);
                    if (id > 1) {
                        Object replacement = builtInDisplayObject();
                        if (replacement != null) {
                            param.args[i] = replacement;
                        }
                    }
                }
                forceOverlayType(arg);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Throwable error = param.getThrowable();
            if (!Boolean.TRUE.equals(IN_RETRY.get()) && dexOn() && error == null
                    && param.args != null && param.args.length > 1
                    && param.args[0] instanceof View
                    && param.args[1] instanceof ViewGroup.LayoutParams) {
                View added = (View) param.args[0];
                ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) param.args[1];
                if (!loggedHeight && params.width == -1 && params.height >= 40 && params.height <= 500) {
                    loggedHeight = true;
                    XposedBridge.log(TAG + ": taskbar window view " + added.getClass().getName()
                            + " height " + params.height);
                }
            }
            if (Boolean.TRUE.equals(IN_RETRY.get()) || !dexOn() || error == null) {
                return;
            }
            if (!loggedAddFailure) {
                loggedAddFailure = true;
                XposedBridge.log(TAG + ": panel window failed: " + error);
            }
            if (!invalidDisplay(error) && error.getClass().getName().indexOf("BadToken") < 0
                    && error.getClass().getName().indexOf("InvalidDisplay") < 0) {
                return;
            }
            if (param.args == null || param.args.length < 2) {
                return;
            }
            Context context = appContext;
            if (context == null) {
                return;
            }
            IN_RETRY.set(Boolean.TRUE);
            try {
                Object window = context.getSystemService("window");
                if (window == null) {
                    return;
                }
                Method add = null;
                for (Method method : window.getClass().getMethods()) {
                    if ("addView".equals(method.getName()) && method.getParameterTypes().length == 2) {
                        add = method;
                        break;
                    }
                }
                if (add == null) {
                    return;
                }
                forceOverlayType(param.args[1]);
                add.invoke(window, param.args[0], param.args[1]);
                param.setThrowable(null);
                XposedBridge.log(TAG + ": floating window attached to built-in display");
            } catch (Throwable retryError) {
                XposedBridge.log(TAG + ": floating window retry failed: " + retryError);
            } finally {
                IN_RETRY.remove();
            }
        }
    };

    private static void forceOverlayType(Object params) {
        Class<?> type = params.getClass();
        while (type != null && !"android.view.WindowManager$LayoutParams".equals(type.getName())) {
            type = type.getSuperclass();
        }
        if (type == null) {
            return;
        }
        try {
            Field field = type.getField("type");
            if (field.getInt(params) == PANEL_WINDOW_TYPE) {
                field.setInt(params, OVERLAY_WINDOW_TYPE);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean invalidDisplay(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String name = current.getClass().getName();
            if (name.indexOf("InvalidDisplay") >= 0) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.indexOf("InvalidDisplay") >= 0) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static int displayIdOf(Object display) {
        try {
            Object id = display.getClass().getMethod("getDisplayId").invoke(display);
            if (id instanceof Integer) {
                return ((Integer) id).intValue();
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Object builtInDisplayObject() {
        Context context = appContext;
        if (context == null) {
            return null;
        }
        try {
            Object manager = context.getSystemService("display");
            if (manager == null) {
                return null;
            }
            Method getDisplay = manager.getClass().getMethod("getDisplay", int.class);
            Object display = getDisplay.invoke(manager, Integer.valueOf(0));
            if (display == null) {
                display = getDisplay.invoke(manager, Integer.valueOf(1));
            }
            return display;
        } catch (Throwable error) {
            return null;
        }
    }

    private static void restartForScale() {
        if (restartPosted) {
            return;
        }
        restartPosted = true;
        XposedBridge.log(TAG + ": restarting One UI Home so the taskbar scale is applied");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        });
    }

    private static void restartLauncher() {
        if (restartPosted) {
            return;
        }
        Context context = appContext;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                long last = prefs.getLong(PREF_RESTART, 0L);
                if (now - last < RESTART_COOLDOWN_MS) {
                    return;
                }
                prefs.edit().putLong(PREF_RESTART, now).apply();
            } catch (Throwable ignored) {}
        }
        restartPosted = true;
        XposedBridge.log(TAG + ": restarting One UI Home so the taskbar is created again");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }
        });
    }
}
