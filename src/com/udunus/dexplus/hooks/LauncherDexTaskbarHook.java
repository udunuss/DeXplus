package com.udunus.dexplus.hooks;

import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.udunus.dexplus.util.ModConfig;

import java.lang.ref.WeakReference;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final ThreadLocal<Boolean> DEX_UI_BUILTIN = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> IN_TILE_UPDATE = new ThreadLocal<Boolean>();
    private static volatile boolean lastStatusClickBuiltIn = true;
    private static volatile long lastStatusClickMs;
    private static volatile Method motionEventGetDisplayIdMethod;

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
            if (!taskbarModsOn() || DEPTH.get()[0] <= 0 || !builtInDisplay(param.thisObject)) {
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
    private static volatile float scaleFactor = 1f;
    private static volatile float heightScaleFactor = 1f;
    private static volatile int externalScalePercent = SCALE_DEFAULT;
    private static volatile int externalHeightScalePercent = 100;
    private static volatile float externalScaleFactor = 1f;
    private static volatile float externalHeightScaleFactor = 1f;
    private static volatile Method contextGetDisplayMethod;
    private static volatile Method contextGetWindowTypeMethod;
    private static volatile Field windowManagerImplContextField;
    private static volatile Method displayGetTypeMethod;
    private static volatile Method displayManagerGetDisplayMethod;
    private static final ConcurrentHashMap<Integer, Boolean> DIMEN_NON_DEX_CACHE =
            new ConcurrentHashMap<Integer, Boolean>();
    private static volatile boolean modsEnabled = true;
    private static volatile boolean taskbarEnabled = true;
    private static volatile boolean hideStatusBar = true;
    private static volatile boolean showFinderBtn = true;
    private static volatile boolean externalShowFinderBtn = true;
    private static volatile int externalDpiOffset = 0;
    private static volatile boolean externalDpiInitialized = false;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable EXTERNAL_DPI_RESTART_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            XposedBridge.log(TAG + ": restarting One UI Home after external display DPI change");
            Process.killProcess(Process.myPid());
            System.exit(0);
        }
    };
    private static volatile int statusPanelMode = 0;
    private static volatile Object homeUiComponent;
    private static volatile int cachedTaskBarHeightResId = -1;
    private static volatile WeakReference<View> finderButtonRef;
    private static volatile WeakReference<View> externalFinderButtonRef;
    private static String appliedOptions;
    private static long startedAt;
    private static int applied = UNSET;
    private static boolean listening;
    private static boolean restartPosted;
    private static boolean loggedRedirect;
    private static boolean loggedHeight;
    private static boolean loggedWindowType;
    private static boolean loggedDensity;
    private static boolean loggedSuper;
    private static boolean loggedStatus;
    private static boolean loggedBadge;
    private static boolean loggedQsGuard;
    private static boolean loggedBattery;
    private static boolean loggedShade;
    private static boolean settingsLoaded;
    private static boolean taskbarVisFailed;
    private static volatile int notificationCount;
    private static volatile boolean repoCountActive;
    private static volatile Object dexListener;
    private static final WeakHashMap<View, Integer> BATTERY_SIZE = new WeakHashMap<View, Integer>();
    private static boolean loggedNotifAccess;
    private static boolean loggedNotifRefresh;
    private static boolean loggedListener;
    private static boolean rebindRequested;
    private static int notifPublishTries;
    private static final WeakHashMap<View, Integer> BATTERY_FIX_TRIES = new WeakHashMap<View, Integer>();
    private static final WeakHashMap<View, WeakReference<TextView>> NOTIFICATION_BADGES =
            new WeakHashMap<View, WeakReference<TextView>>();
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
    private static volatile Runnable dexStateListener;

    private LauncherDexTaskbarHook() {}

    public static void setDexStateListener(Runnable listener) {
        dexStateListener = listener;
    }

    public static boolean isInternalDexActive() {
        return dexOn();
    }

    public static boolean isInternalDexActive(Context ctx, ClassLoader cl) {
        return dexOn();
    }

    private static boolean taskbarModsOn() {
        return modsEnabled && taskbarEnabled && dexOn();
    }

    /** Returns the effective DeX taskbar height in pixels for workspace bottom padding. */
    public static int getScaledTaskbarHeightPx(Context context) {
        if (context == null) {
            return 0;
        }
        float density = contextDensity(context);
        if (density < 0.5f) {
            density = 1.75f;
        }
        int basePx = Math.round(56f * density);
        try {
            int resId = getTaskBarHeightDexId(context);
            if (resId != 0) {
                int dimenPx = context.getResources().getDimensionPixelSize(resId);
                if (dimenPx > 0) {
                    basePx = dimenPx;
                }
            }
        } catch (Throwable ignored) {}
        if (isBuiltInDisplay(context)) {
            if (taskbarModsOn()) {
                return Math.max(1, Math.round(basePx * heightScaleFactor));
            }
        } else if (modsEnabled && externalHeightScalePercent != 100) {
            return Math.max(1, Math.round(basePx * externalHeightScaleFactor));
        }
        return basePx;
    }

    public static int getContextDisplayId(Context ctx) {
        if (ctx == null) return Display.DEFAULT_DISPLAY;
        try {
            Method m = contextGetDisplayMethod;
            if (m == null) {
                m = Context.class.getMethod("getDisplay");
                contextGetDisplayMethod = m;
            }
            Object d = m.invoke(ctx);
            if (d instanceof Display) {
                return ((Display) d).getDisplayId();
            }
        } catch (Throwable ignored) {}
        return Display.DEFAULT_DISPLAY;
    }

    private static int getTaskBarHeightDexId(Context context) {
        int cached = cachedTaskBarHeightResId;
        if (cached >= 0) {
            return cached;
        }
        if (context == null) {
            return 0;
        }
        try {
            int id = context.getResources().getIdentifier(
                    "task_bar_height_dex", "dimen", context.getPackageName());
            cachedTaskBarHeightResId = id;
            return id;
        } catch (Throwable ignored) {
            cachedTaskBarHeightResId = 0;
            return 0;
        }
    }

    public static boolean isBuiltInDisplay(Context ctx) {
        if (ctx == null) return true;
        try {
            Method m = contextGetDisplayMethod;
            if (m == null) {
                m = Context.class.getMethod("getDisplay");
                contextGetDisplayMethod = m;
            }
            Object d = m.invoke(ctx);
            if (d instanceof Display) {
                return isBuiltInDisplayObject(d);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static boolean isBuiltInView(View view) {
        if (view == null) {
            return false;
        }
        Display d = view.getDisplay();
        if (d != null) {
            return isBuiltInDisplayObject(d);
        }
        return isBuiltInDisplay(view.getContext());
    }

    private static boolean isBuiltInBinding(Object binding) {
        if (binding == null) {
            return true;
        }
        try {
            Object root = binding.getClass().getMethod("getRoot").invoke(binding);
            if (root instanceof View) {
                return isBuiltInView((View) root);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static boolean isBuiltInDisplayObject(Object display) {
        if (display == null) {
            return false;
        }
        int id = displayIdOf(display);
        if (id == Display.DEFAULT_DISPLAY) {
            return true;
        }
        if (id < 0) {
            return false;
        }
        try {
            Method m = displayGetTypeMethod;
            if (m == null) {
                m = display.getClass().getMethod("getType");
                displayGetTypeMethod = m;
            }
            Object type = m.invoke(display);
            if (type instanceof Integer) {
                // Display.TYPE_INTERNAL == 1 (covers foldable cover/main internal screens)
                return ((Integer) type).intValue() == 1;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isBuiltInDisplayId(int id) {
        if (id == Display.DEFAULT_DISPLAY) {
            return true;
        }
        if (id < 0) {
            return false;
        }
        Context context = appContext;
        if (context == null) {
            return false;
        }
        try {
            Object manager = context.getSystemService("display");
            if (manager == null) {
                return false;
            }
            Method m = displayManagerGetDisplayMethod;
            if (m == null) {
                m = manager.getClass().getMethod("getDisplay", int.class);
                displayManagerGetDisplayMethod = m;
            }
            Object display = m.invoke(manager, Integer.valueOf(id));
            return isBuiltInDisplayObject(display);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        LauncherClassResolver.resolve(lpparam.appInfo);
        ClassLoader loader = lpparam.classLoader;
        hookDexSpace(loader, HONEY_SPACE);
        hookDexSpace(loader, COMBINED);
        int wrapped = wrapTaskbar(loader);
        XposedBridge.log(TAG + ": wrapped " + wrapped + " taskbar methods");
        keepTaskbarOnDeviceDisplay(loader);
        hookDexDensity(loader);
        hookSuperKey(loader);
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
                return isBuiltInDisplayId(id);
            }
            if (COMBINED.equals(name)) {
                int id = ((Integer) info.getClass().getMethod("getPrimaryDisplayId").invoke(info)).intValue();
                return isBuiltInDisplayId(id);
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
        LauncherClassResolver.ResolvedClasses resolved = LauncherClassResolver.getCached();
        boolean useResolvedPkg = resolved != null && !resolved.taskbarPackageClasses.isEmpty();
        ArrayList<String> names = new ArrayList<String>();
        if (useResolvedPkg) {
            names.addAll(resolved.taskbarPackageClasses);
        }
        for (String name : TaskbarClasses.NAMES) {
            if (useResolvedPkg && name.startsWith("O6.")) {
                continue;
            }
            names.add(name);
        }
        int count = 0;
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                int flags = method.getModifiers();
                if (Modifier.isAbstract(flags) || Modifier.isNative(flags)
                        || method.isSynthetic() || method.isBridge()
                        || method.getDeclaringClass() == Object.class) {
                    continue;
                }
                String mName = method.getName();
                if ("equals".equals(mName) || "hashCode".equals(mName) || "toString".equals(mName)
                        || "describeContents".equals(mName) || "writeToParcel".equals(mName)) {
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
            Runnable listener = dexStateListener;
            if (listener != null) {
                try {
                    listener.run();
                } catch (Throwable ignored) {}
            }
            if (previous != UNSET && taskbarSeen) {
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
            if (!modsEnabled || !(param.getResult() instanceof Integer)) {
                return;
            }
            int dp = ((Integer) param.getResult()).intValue();
            if (dp <= 0 || param.args == null || param.args.length == 0 || !(param.args[0] instanceof Context)) {
                return;
            }
            Context ctx = (Context) param.args[0];
            boolean builtIn = isBuiltInDisplay(ctx);
            int resId = (param.args.length > 1 && param.args[1] instanceof Integer)
                    ? ((Integer) param.args[1]).intValue()
                    : 0;
            boolean isBarHeight = (resId != 0 && resId == getTaskBarHeightDexId(ctx));
            if (builtIn) {
                if (!taskbarModsOn()) {
                    return;
                }
            } else {
                float extScale = isBarHeight ? externalHeightScaleFactor : externalScaleFactor;
                if (Math.abs(extScale - 1f) < 0.005f) {
                    return;
                }
            }
            if (DEPTH.get()[0] <= 0 && !Boolean.TRUE.equals(DEX_UI.get())) {
                if (isKnownNonDexDimen(ctx, resId) || !calledFromDexUi()) {
                    return;
                }
            }
            int px;
            if (builtIn) {
                float density = contextDensity(ctx);
                if (density < 0.5f) {
                    return;
                }
                float effectiveScale = isBarHeight ? heightScaleFactor : scaleFactor;
                px = Math.round(dp * density * effectiveScale);
            } else {
                float effectiveScale = isBarHeight ? externalHeightScaleFactor : externalScaleFactor;
                px = Math.max(1, Math.round(dp * effectiveScale));
            }
            if (px == dp) {
                return;
            }
            param.setResult(Integer.valueOf(px));
            if (!loggedDensity && builtIn) {
                loggedDensity = true;
                XposedBridge.log(TAG + ": dex size " + dp + "dp -> " + px
                        + "px at device density " + contextDensity(ctx) + " scale " + scalePercent + "%");
            }
        }
    };

    private static float contextDensity(Context context) {
        try {
            return context.getResources().getDisplayMetrics().density;
        } catch (Throwable error) {
            return 0f;
        }
    }

    private static boolean isKnownNonDexDimen(Context ctx, int resId) {
        if (resId == 0) {
            return false;
        }
        Boolean cached = DIMEN_NON_DEX_CACHE.get(Integer.valueOf(resId));
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean nonDex = false;
        try {
            String name = ctx.getResources().getResourceEntryName(resId);
            if (name != null) {
                nonDex = name.startsWith("workspace_")
                        || name.startsWith("applist_")
                        || name.startsWith("apps_")
                        || name.startsWith("folder_")
                        || name.startsWith("open_folder_")
                        || name.startsWith("widget_")
                        || name.startsWith("stacked_widget_")
                        || name.startsWith("page_indicator_")
                        || name.startsWith("minus_one_")
                        || name.startsWith("screen_grid_")
                        || name.startsWith("cell_layout_")
                        || name.startsWith("drag_guide_");
            }
        } catch (Throwable ignored) {}
        DIMEN_NON_DEX_CACHE.putIfAbsent(Integer.valueOf(resId), Boolean.valueOf(nonDex));
        return nonDex;
    }

    private static boolean calledFromDexUi() {
        LauncherClassResolver.ResolvedClasses resolved = LauncherClassResolver.getCached();
        String[] prefixes = resolved != null ? resolved.getDexUiPrefixArray() : null;
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int limit = stack.length < 24 ? stack.length : 24;
        for (int i = 2; i < limit; i++) {
            String name = stack[i].getClassName();
            if (name.indexOf("taskbar") >= 0 || name.indexOf("dexpanel") >= 0) {
                return true;
            }
            if (prefixes != null) {
                for (int j = 0; j < prefixes.length; j++) {
                    if (name.startsWith(prefixes[j])) {
                        return true;
                    }
                }
            } else if (name.startsWith("p4.")
                    || name.startsWith("R6.") || name.startsWith("T6.")
                    || name.startsWith("J6.") || name.startsWith("O6.")) {
                return true;
            }
        }
        return false;
    }

    private static void readSettings(Context context) {
        if (context == null) return;
        ModConfig.ensureInitialized(context);
        int scale = clampScale(ModConfig.getInt(context, ModConfig.KEY_TASKBAR_ICON_SCALE, SCALE_DEFAULT));
        int heightScale = clamp(ModConfig.getInt(context, ModConfig.KEY_TASKBAR_HEIGHT_SCALE, 100), 65, 135);
        int extScale = clampScale(ModConfig.getInt(context, ModConfig.KEY_EXTERNAL_TASKBAR_ICON_SCALE, SCALE_DEFAULT));
        int extHeightScale = clamp(ModConfig.getInt(context, ModConfig.KEY_EXTERNAL_TASKBAR_HEIGHT_SCALE, 100), 65, 135);
        boolean enabled = ModConfig.isModuleEnabled(context);
        boolean taskbar = ModConfig.getBoolean(context, ModConfig.KEY_DEX_TASKBAR_ENABLED, true);
        boolean forceDex = ModConfig.getBoolean(context, ModConfig.KEY_FORCE_INTERNAL_DEX, false);
        boolean status = ModConfig.getBoolean(context, ModConfig.KEY_HIDE_STATUS_BAR, true);
        boolean finder = ModConfig.getBoolean(context, ModConfig.KEY_SHOW_DEX_FINDER_BTN, true);
        boolean extFinder = ModConfig.getBoolean(context, ModConfig.KEY_EXTERNAL_SHOW_FINDER_BTN, true);
        int extDpi = ModConfig.getInt(context, ModConfig.KEY_EXTERNAL_DEX_DPI_OFFSET, 0);
        int panelMode = ModConfig.getInt(context, ModConfig.KEY_STATUS_PANEL_MODE, 0);

        String signature = scale + "/" + heightScale + "/" + (enabled ? 1 : 0)
                + "/" + (taskbar ? 1 : 0) + "/" + (forceDex ? 1 : 0)
                + "/" + extScale + "x" + extHeightScale;
        boolean changed;
        boolean extDpiChanged;
        synchronized (LauncherDexTaskbarHook.class) {
            scalePercent = scale;
            heightScalePercent = heightScale;
            scaleFactor = scale / 100f;
            heightScaleFactor = heightScale / 100f;
            externalScalePercent = extScale;
            externalHeightScalePercent = extHeightScale;
            externalScaleFactor = extScale / 100f;
            externalHeightScaleFactor = extHeightScale / 100f;
            modsEnabled = enabled;
            taskbarEnabled = taskbar;
            forceInternalDex = forceDex;
            hideStatusBar = status;
            showFinderBtn = finder;
            externalShowFinderBtn = extFinder;
            statusPanelMode = panelMode;
            settingsLoaded = true;
            changed = appliedOptions != null && !appliedOptions.equals(signature);
            appliedOptions = signature;
            extDpiChanged = externalDpiInitialized && extDpi != externalDpiOffset;
            externalDpiOffset = extDpi;
            externalDpiInitialized = true;
        }
        XposedBridge.log(TAG + ": settings " + signature);
        applyStatusBar();
        updateFinderButtonVisibility();
        if (changed) {
            restartForScale();
        } else if (extDpiChanged && hasActiveExternalDisplay()) {
            MAIN_HANDLER.removeCallbacks(EXTERNAL_DPI_RESTART_RUNNABLE);
            MAIN_HANDLER.postDelayed(EXTERNAL_DPI_RESTART_RUNNABLE, 550L);
        }
    }

    private static void updateFinderButtonVisibility() {
        applyFinderVisibility(finderButtonRef, (!taskbarModsOn() || showFinderBtn) ? View.VISIBLE : View.GONE);
        applyFinderVisibility(externalFinderButtonRef, (!modsEnabled || externalShowFinderBtn) ? View.VISIBLE : View.GONE);
    }

    private static void applyFinderVisibility(WeakReference<View> ref, final int vis) {
        final View finderView = ref == null ? null : ref.get();
        if (finderView == null) {
            return;
        }
        try {
            finderView.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        finderView.setVisibility(vis);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
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
        boolean taskbarEffects = taskbarModsOn();
        writeEffects(context, effects);
        ensureNotificationAccess(context, taskbarEffects);
        boolean want = taskbarEffects && hideStatusBar;
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
        if (taskbarVisFailed || !taskbarModsOn()) {
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

    private static void hookHomeGestures(ClassLoader loader) {
        // Block Home Screen swipe, edit-mode, pinch, and minus-one page controllers while Desktop is active
        LauncherClassResolver.ResolvedClasses resolved = LauncherClassResolver.getCached();
        if (resolved != null && !resolved.touchControllerClasses.isEmpty()) {
            for (String clsName : resolved.touchControllerClasses) {
                hookGesture(loader, clsName);
            }
            return;
        }
        hookGesture(loader, "Z3.a");
        hookGesture(loader, "G5.n");
        hookGesture(loader, "G5.c");
        hookGesture(loader, "G5.e");
        hookGesture(loader, "G5.h");
        hookGesture(loader, "G5.l");
        hookGesture(loader, "G5.k");
    }

    private static void hookGesture(ClassLoader loader, String className) {
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
                    XposedBridge.hookMethod(method, BLOCK_DESKTOP_HOME_TOUCH);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook gesture " + className + ": " + error);
        }
    }

    private static final XC_MethodHook BLOCK_DESKTOP_HOME_TOUCH = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!modsEnabled || !dexOn()) {
                return;
            }
            if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                int evDisplayId = motionEventDisplayId(param.args[0]);
                if (evDisplayId > 0 && !isBuiltInDisplayId(evDisplayId)) {
                    return;
                }
            }
            if (((Method) param.method).getReturnType() == boolean.class) {
                param.setResult(Boolean.FALSE);
            } else {
                param.setResult(null);
            }
            if (!loggedShade) {
                loggedShade = true;
                XposedBridge.log(TAG + ": home gestures blocked for Desktop");
            }
        }
    };

    private static int motionEventDisplayId(Object event) {
        try {
            Method m = motionEventGetDisplayIdMethod;
            if (m == null) {
                m = event.getClass().getMethod("getDisplayId");
                motionEventGetDisplayIdMethod = m;
            }
            Object id = m.invoke(event);
            return id instanceof Integer ? ((Integer) id).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

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
            LauncherClassResolver.ResolvedClasses resolved = LauncherClassResolver.getCached();
            String keyHandler = (resolved != null && resolved.keyGestureHandlerClass != null)
                    ? resolved.keyGestureHandlerClass : "j2.b";
            XposedHelpers.findAndHookMethod(
                    keyHandler,
                    loader,
                    "handleKeyGestureEvent",
                    "android.hardware.input.KeyGestureEvent",
                    android.os.IBinder.class,
                    SUPER_KEY);
            XposedBridge.log(TAG + ": super key hooked (" + keyHandler + ")");
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

    private static void hookPanelClicks(ClassLoader loader) {
        LauncherClassResolver.ResolvedClasses resolved = LauncherClassResolver.getCached();
        String statusBindingCls = (resolved != null && resolved.statusPanelBindingClass != null)
                ? resolved.statusPanelBindingClass : "J6.f";
        String taskbarBindingCls = (resolved != null && resolved.taskbarBindingClass != null)
                ? resolved.taskbarBindingClass : "J6.b";
        String repoCls = (resolved != null && resolved.notificationRepoClass != null)
                ? resolved.notificationRepoClass : "F4.h";
        String countMethod = (resolved != null && resolved.notificationCountMethod != null)
                ? resolved.notificationCountMethod : "j";
        String publishMethod = (resolved != null && resolved.notificationPublishMethod != null)
                ? resolved.notificationPublishMethod : "f";
        String panelMgrCls = (resolved != null && resolved.dexPanelManagerClass != null)
                ? resolved.dexPanelManagerClass : "p4.f";
        String panelEnumCls = (resolved != null && resolved.dexPanelEnumClass != null)
                ? resolved.dexPanelEnumClass : "p4.c";

        try {
            XposedHelpers.findAndHookMethod(statusBindingCls, loader, "executeBindings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isBuiltInBinding(param.thisObject)) {
                        return;
                    }
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
            XposedHelpers.findAndHookMethod(taskbarBindingCls, loader, "executeBindings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean builtIn = isBuiltInBinding(param.thisObject);
                    if (builtIn) {
                        attachNamedClicks(
                                param.thisObject,
                                new String[] {"c", "f"},
                                new String[] {"v", "w"});
                    }
                    try {
                        View fv = findBindingViewByEntry(param.thisObject, "c", "finder");
                        if (fv != null) {
                            if (isBuiltInView(fv)) {
                                finderButtonRef = new WeakReference<View>(fv);
                                fv.setVisibility((!taskbarModsOn() || showFinderBtn) ? View.VISIBLE : View.GONE);
                            } else {
                                externalFinderButtonRef = new WeakReference<View>(fv);
                                fv.setVisibility((!modsEnabled || externalShowFinderBtn) ? View.VISIBLE : View.GONE);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook finder clicks: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    statusBindingCls, loader, "_internalCallbackOnClick", int.class, View.class, STATUS_CLICK);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch status click: " + error);
        }
        try {
            Class<?> repo = Class.forName(repoCls, false, loader);
            int hooked = 0;
            for (Method method : repo.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;
                String mName = method.getName();
                if (countMethod.equals(mName) && java.util.List.class.isAssignableFrom(params[0])) {
                    XposedBridge.hookMethod(method, NOTIFICATION_COUNT);
                    hooked++;
                } else if (publishMethod.equals(mName) && params[0].isEnum()) {
                    XposedBridge.hookMethod(method, PUBLISH_NOTIFICATIONS);
                    hooked++;
                }
            }
            if (hooked == 0) {
                for (Method method : repo.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1) continue;
                    if ("j".equals(method.getName())) {
                        XposedBridge.hookMethod(method, NOTIFICATION_COUNT);
                        hooked++;
                    } else if ("f".equals(method.getName())) {
                        XposedBridge.hookMethod(method, PUBLISH_NOTIFICATIONS);
                        hooked++;
                    }
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
                } else if ("onNotificationPosted".equals(name)
                        || "onNotificationRemoved".equals(name)
                        || "onNotificationRankingUpdate".equals(name)
                        || "f".equals(name)
                        || (method.getParameterTypes().length == 0
                            && method.getReturnType() == void.class
                            && !name.startsWith("on") && !name.startsWith("get") && !"b".equals(name))) {
                    XposedBridge.hookMethod(method, REFRESH_NOTIFICATION_COUNT);
                }
            }
            requestNotificationRebind();
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch notification listener: " + error);
        }
        hookQuickSettings(loader);
        try {
            Class<?> mgrType = Class.forName(panelMgrCls, false, loader);
            Class<?> enumType = Class.forName(panelEnumCls, false, loader);
            Method toggleMethod = null;
            try {
                toggleMethod = mgrType.getDeclaredMethod("b", enumType);
            } catch (NoSuchMethodException ignored) {
                for (Method candidate : mgrType.getDeclaredMethods()) {
                    Class<?>[] params = candidate.getParameterTypes();
                    if (params.length == 1 && params[0] == enumType && !"a".equals(candidate.getName())) {
                        toggleMethod = candidate;
                        break;
                    }
                }
            }
            if (toggleMethod != null) {
                XposedBridge.hookMethod(toggleMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean builtInPanel = (SystemClock.uptimeMillis() - lastStatusClickMs < 1500L)
                                ? lastStatusClickBuiltIn : true;
                        if (!builtInPanel) {
                            return;
                        }
                        if (taskbarModsOn() && statusPanelMode == 1
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
                        DEX_UI_BUILTIN.set(Boolean.TRUE);
                        fillPanelList(param.thisObject);
                        startPanelServices(param.thisObject);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        DEX_UI.remove();
                        DEX_UI_BUILTIN.remove();
                    }
                });
            }
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
            boolean clickedBuiltIn = true;
            if (param.args.length > 1 && param.args[1] instanceof View) {
                clickedBuiltIn = isBuiltInView((View) param.args[1]);
            }
            lastStatusClickBuiltIn = clickedBuiltIn;
            lastStatusClickMs = SystemClock.uptimeMillis();
            if (!clickedBuiltIn || !taskbarModsOn() || !(param.args[0] instanceof Integer)) {
                return;
            }
            // key 1 = Accessibility, key 2 = ScreenCapture, key 3 = Volume,
            // key 4 = Notification, key 5 = QuickSettings, key 6 = Calendar
            int key = ((Integer) param.args[0]).intValue();
            String entry = (param.args.length > 1 && param.args[1] instanceof View)
                    ? entryName((View) param.args[1]) : null;
            boolean isNotif = entry != null ? entry.contains("notif") : (key == 4);
            boolean isQs = entry != null ? (entry.contains("quick") || entry.contains("setting")) : (key == 5);
            if (statusPanelMode == 1 && (isNotif || isQs)) {
                expandSystemShade(isQs);
                param.setResult(null);
                return;
            }
            if (isNotif) {
                publishDexNotifications();
            }
        }
    };

    private static void closeOpenDexPanels(Object manager) {
        if (manager == null) return;
        try {
            Object map = findPanelMap(manager);
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
            if (!taskbarModsOn()) {
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
            int[] rawOut = new int[1];
            ArrayList<Object> kept = filterDexNotifications(listener, rawOut);
            int raw = rawOut[0];
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
            if (!repoCountActive) {
                publishNotificationCount(countActiveNotifications(kept));
            }
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

    private static ArrayList<Object> filterDexNotifications(Object listener, int[] rawOut) throws Exception {
        Object active = listener.getClass().getMethod("getActiveNotifications").invoke(listener);
        Object[] items = active instanceof Object[] ? (Object[]) active : new Object[0];
        Method allow = null;
        for (Method method : listener.getClass().getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if ("d".equals(method.getName()) && params.length == 1) {
                allow = method;
                allow.setAccessible(true);
                break;
            }
        }
        if (allow == null) {
            for (Method method : listener.getClass().getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && method.getReturnType() == boolean.class
                        && StatusBarNotification.class.isAssignableFrom(params[0])) {
                    allow = method;
                    allow.setAccessible(true);
                    break;
                }
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
        if (rawOut != null && rawOut.length > 0) {
            rawOut[0] = raw;
        }
        return kept;
    }

    private static int countActiveNotifications(ArrayList<Object> items) {
        int count = 0;
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            if (item instanceof StatusBarNotification) {
                Notification notif = ((StatusBarNotification) item).getNotification();
                if (notif != null && (notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                    continue;
                }
            }
            count++;
        }
        return count;
    }

    private static void refreshNotificationCountFromListener(Object listener) {
        if (listener == null || repoCountActive || !taskbarModsOn()) {
            return;
        }
        try {
            ArrayList<Object> kept = filterDexNotifications(listener, null);
            publishNotificationCount(countActiveNotifications(kept));
        } catch (Throwable ignored) {}
    }

    private static final XC_MethodHook REFRESH_NOTIFICATION_COUNT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!taskbarModsOn() || param.thisObject == null) {
                return;
            }
            dexListener = param.thisObject;
            refreshNotificationCountFromListener(param.thisObject);
        }
    };

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
            if (!taskbarModsOn() || param.args.length == 0) {
                return;
            }
            int count = countNotifications(param.args[0]);
            if (count < 0) {
                return;
            }
            repoCountActive = true;
            publishNotificationCount(count);
        }
    };

    private static void publishNotificationCount(int count) {
        if (loggedBadge && notificationCount == count) {
            return;
        }
        notificationCount = count;
        if (!loggedBadge) {
            loggedBadge = true;
            XposedBridge.log(TAG + ": notification badge count -> " + count
                    + (repoCountActive ? " (repo)" : " (listener)"));
        }
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
        if (!taskbarModsOn()) {
            return;
        }
        View button = findBindingViewByEntry(binding, "e", "notif");
        if (!(button instanceof ViewGroup) || !isBuiltInView(button)) {
            return;
        }
        ViewGroup group = (ViewGroup) button;
        synchronized (NOTIFICATION_BADGES) {
            WeakReference<TextView> existing = NOTIFICATION_BADGES.get(group);
            if (existing != null && existing.get() != null) {
                paintNotificationBadges();
                return;
            }
        }
        try {
            Context context = group.getContext();
            TextView badge = new TextView(context);
            badge.setTextSize(10f);
            badge.setPadding(dp(context, 3), 0, dp(context, 3), 0);
            badge.setTextColor(0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(context, 14));
            badge.setMinHeight(dp(context, 14));
            badge.setClickable(false);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFFE53935);
            badge.setBackground(dot);
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layout.gravity = Gravity.TOP | Gravity.END;
            group.addView(badge, layout);
            synchronized (NOTIFICATION_BADGES) {
                NOTIFICATION_BADGES.put(group, new WeakReference<TextView>(badge));
            }
            if (!repoCountActive && dexListener != null) {
                refreshNotificationCountFromListener(dexListener);
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
            Iterator<WeakReference<TextView>> it = NOTIFICATION_BADGES.values().iterator();
            while (it.hasNext()) {
                WeakReference<TextView> ref = it.next();
                TextView badge = ref == null ? null : ref.get();
                if (badge == null) {
                    it.remove();
                    continue;
                }
                try {
                    if (count <= 0) {
                        badge.setVisibility(View.GONE);
                    } else {
                        badge.setText(label);
                        badge.setVisibility(View.VISIBLE);
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
            if (taskbarModsOn()) {
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
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                if ("b".equals(method.getName()) && method.getParameterTypes().length == 3) {
                    XposedBridge.hookMethod(method, SAFE_TILE_UPDATE);
                    hooked++;
                }
            }
            if (hooked == 0) {
                for (Method method : type.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 3 && params[0] == type && params[1].getName().contains("Observable")) {
                        XposedBridge.hookMethod(method, SAFE_TILE_UPDATE);
                        hooked++;
                    }
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
        LauncherClassResolver.ResolvedClasses resolved = LauncherClassResolver.getCached();
        String drawableCls = (resolved != null && resolved.batteryDrawableClass != null)
                ? resolved.batteryDrawableClass : "R6.d";
        String coroutineCls = (resolved != null && resolved.batteryCoroutineClass != null)
                ? resolved.batteryCoroutineClass : "R6.b";
        try {
            Class<?> type = Class.forName(drawableCls, false, loader);
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                if ("g".equals(method.getName()) && method.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(method, WIDEN_BATTERY);
                    hooked++;
                }
            }
            if (hooked == 0) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterTypes().length == 0 && method.getReturnType() == void.class
                            && !Modifier.isStatic(method.getModifiers())
                            && !"getTAG".equals(method.getName())) {
                        XposedBridge.hookMethod(method, WIDEN_BATTERY);
                    }
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook battery drawable: " + error);
        }
        try {
            Class<?> coroutine = Class.forName(coroutineCls, false, loader);
            for (Method method : coroutine.getDeclaredMethods()) {
                if ("invokeSuspend".equals(method.getName())) {
                    XposedBridge.hookMethod(method, RESIZE_BATTERY_COROUTINE);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook battery coroutine: " + error);
        }
        try {
            Class<?> meter = Class.forName(
                    "com.honeyspace.ui.honeypots.taskbar.presentation.battery.BatteryMeterView",
                    false, loader);
            for (Method method : meter.getDeclaredMethods()) {
                String name = method.getName();
                if ("onConfigurationChanged".equals(name) || "onAttachedToWindow".equals(name)) {
                    XposedBridge.hookMethod(method, RESIZE_BATTERY_VIEW);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook battery view: " + error);
        }
    }

    private static final XC_MethodHook WIDEN_BATTERY = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!taskbarModsOn()) {
                return;
            }
            widenBatteryDrawable(param.thisObject);
        }
    };

    private static final XC_MethodHook RESIZE_BATTERY_COROUTINE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!taskbarModsOn()) {
                return;
            }
            Object meter = findFieldByType(
                    param.thisObject,
                    "com.honeyspace.ui.honeypots.taskbar.presentation.battery.BatteryMeterView",
                    "f");
            if (meter instanceof View) {
                scheduleBatterySize((View) meter, true);
            }
        }
    };

    private static final XC_MethodHook RESIZE_BATTERY_VIEW = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!taskbarModsOn()) {
                return;
            }
            if (param.thisObject instanceof View) {
                scheduleBatterySize((View) param.thisObject, false);
            }
        }
    };

    private static void widenBatteryDrawable(Object drawable) {
        try {
            Class<?> type = drawable.getClass();
            Field widthField = null;
            Field heightField = null;
            Field stableField = null;
            try {
                widthField = type.getDeclaredField("r");
                heightField = type.getDeclaredField("s");
                stableField = type.getDeclaredField("u");
            } catch (NoSuchFieldException ignored) {}
            if (widthField == null || widthField.getType() != int.class
                    || heightField == null || heightField.getType() != int.class) {
                int iw = ((Integer) type.getMethod("getIntrinsicWidth").invoke(drawable)).intValue();
                int ih = ((Integer) type.getMethod("getIntrinsicHeight").invoke(drawable)).intValue();
                if (iw <= 0 || ih <= 0) return;
                widthField = null;
                heightField = null;
                stableField = null;
                for (Field f : type.getDeclaredFields()) {
                    if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    int val = f.getInt(drawable);
                    if (val == iw && widthField == null) {
                        widthField = f;
                    } else if (val == ih && heightField == null) {
                        heightField = f;
                    }
                }
                if (widthField == null || heightField == null) return;
            }
            widthField.setAccessible(true);
            heightField.setAccessible(true);
            if (stableField != null) {
                stableField.setAccessible(true);
            }
            int width = widthField.getInt(drawable);
            int height = heightField.getInt(drawable);
            int stable = (stableField != null && stableField.getType() == int.class)
                    ? stableField.getInt(drawable) : 0;
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
        if (!taskbarModsOn()) {
            return;
        }
        View meter = findBatteryMeterView(binding);
        if (meter != null && isBuiltInView(meter)) {
            scheduleBatterySize(meter, false);
        }
    }

    private static void scheduleBatterySize(final View view, boolean immediate) {
        if (immediate && Looper.myLooper() == Looper.getMainLooper()) {
            applyBatterySize(view);
        }
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
        if (!taskbarModsOn() || !isBuiltInView(meter)) {
            return;
        }
        try {
            Object image = findFieldByType(meter, "android.widget.ImageView", "c");
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
            ViewGroup.LayoutParams currentParams = icon.getLayoutParams();
            boolean paramsMismatch = currentParams == null
                    || currentParams.width != wantWidth
                    || currentParams.height != wantHeight;
            int key = wantWidth * 10000 + wantHeight;
            synchronized (BATTERY_SIZE) {
                Integer saved = BATTERY_SIZE.get(icon);
                if (saved != null && saved.intValue() == key && !portrait && !paramsMismatch) {
                    return;
                }
                if (portrait) {
                    Integer prevTries = BATTERY_FIX_TRIES.get(icon);
                    int tries = prevTries == null ? 0 : prevTries.intValue();
                    if (tries >= 6 && !paramsMismatch) {
                        return;
                    }
                    BATTERY_FIX_TRIES.put(icon, Integer.valueOf(tries + 1));
                }
                BATTERY_SIZE.put(icon, Integer.valueOf(key));
            }
            sizeBox(icon, wantWidth, wantHeight);
            if (!loggedBattery) {
                loggedBattery = true;
                XposedBridge.log(TAG + ": battery icon sized to " + wantWidth + "x" + wantHeight
                        + " (intrinsic " + intrinsicWidth + "x" + intrinsicHeight + ")");
            }
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
            Object map = findPanelMap(manager);
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
                Method keyMethod = findEnumGetter(panel.getClass(), "g");
                if (keyMethod == null) {
                    continue;
                }
                Object key = keyMethod.invoke(panel);
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
        if (panelServicesStarted || !taskbarModsOn() || manager == null) {
            return;
        }
        int bound = 0;
        try {
            Object componentServices = field(manager, "componentServices");
            Object running = findServiceMap(manager);
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
                        Method keyOf = findEnumGetter(service.getClass(), "a");
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
        Class<?> fn1Type = null;
        try {
            fn1Type = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        } catch (Throwable ignored) {}

        Field target = null;
        Class<?> callbackType = null;
        // Fast-path: check field "f" first, then scan hierarchy for Function1 field.
        Class<?> type = panel.getClass();
        while (type != null && target == null) {
            try {
                Field f = type.getDeclaredField("f");
                if (fn1Type == null || fn1Type.isAssignableFrom(f.getType())) {
                    Constructor<?> c = find3ArgIntCtor(f.getType());
                    if (c != null) {
                        target = f;
                        callbackType = f.getType();
                        break;
                    }
                }
            } catch (NoSuchFieldException ignored) {}
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (fn1Type != null && fn1Type.isAssignableFrom(f.getType())) {
                    if (find3ArgIntCtor(f.getType()) != null) {
                        target = f;
                        callbackType = f.getType();
                        break;
                    }
                }
            }
            type = type.getSuperclass();
        }
        if (target == null || callbackType == null) {
            return false;
        }
        Constructor<?> ctor = find3ArgIntCtor(callbackType);
        if (ctor == null) {
            return false;
        }
        ctor.setAccessible(true);
        Object callback = ctor.newInstance(Integer.valueOf(5), manager, provider);
        target.setAccessible(true);
        target.set(panel, callback);
        return true;
    }

    private static Constructor<?> find3ArgIntCtor(Class<?> type) {
        if (type == null) return null;
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 3 && params[0] == int.class) {
                return candidate;
            }
        }
        return null;
    }

    private static View findBindingViewByEntry(Object binding, String preferredField, String entrySubstring) {
        Object preferred = field(binding, preferredField);
        if (preferred instanceof View) {
            String entry = entryName((View) preferred);
            if (entry == null || entry.contains(entrySubstring)) {
                return (View) preferred;
            }
        }
        Class<?> type = binding.getClass();
        while (type != null && !"androidx.databinding.ViewDataBinding".equals(type.getName())) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(binding);
                    if (v instanceof View) {
                        String entry = entryName((View) v);
                        if (entry != null && entry.contains(entrySubstring)) {
                            return (View) v;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return preferred instanceof View ? (View) preferred : null;
    }

    private static View findBatteryMeterView(Object binding) {
        Object fast = field(field(binding, "p"), "f");
        if (fast instanceof View && fast.getClass().getName().endsWith("BatteryMeterView")) {
            return (View) fast;
        }
        Class<?> type = binding.getClass();
        while (type != null && !"androidx.databinding.ViewDataBinding".equals(type.getName())) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(binding);
                    if (val instanceof View && val.getClass().getName().endsWith("BatteryMeterView")) {
                        return (View) val;
                    }
                    if (val != null && val.getClass().getSuperclass() != null
                            && "androidx.databinding.ViewDataBinding".equals(val.getClass().getSuperclass().getName())) {
                        for (Field nf : val.getClass().getDeclaredFields()) {
                            if (Modifier.isStatic(nf.getModifiers())) continue;
                            nf.setAccessible(true);
                            Object nv = nf.get(val);
                            if (nv instanceof View && nv.getClass().getName().endsWith("BatteryMeterView")) {
                                return (View) nv;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return fast instanceof View ? (View) fast : null;
    }

    private static Object findPanelMap(Object manager) {
        Object fast = field(manager, "i");
        if (fast instanceof Map) return fast;
        return findNthLinkedHashMap(manager, 0);
    }

    private static Object findServiceMap(Object manager) {
        Object fast = field(manager, "j");
        if (fast instanceof Map) return fast;
        return findNthLinkedHashMap(manager, 1);
    }

    private static Object findNthLinkedHashMap(Object target, int index) {
        int seen = 0;
        for (Field f : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if ("java.util.LinkedHashMap".equals(f.getType().getName())) {
                if (seen == index) {
                    try {
                        f.setAccessible(true);
                        return f.get(target);
                    } catch (Throwable ignored) {}
                }
                seen++;
            }
        }
        return null;
    }

    private static Method findEnumGetter(Class<?> type, String preferredName) {
        Method preferred = noArg(type, preferredName);
        if (preferred != null && preferred.getReturnType().isEnum()) {
            return preferred;
        }
        Class<?> current = type;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType().isEnum()
                        && !Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return preferred;
    }

    private static Object findFieldByType(Object target, String typeName, String preferredField) {
        if (target == null) return null;
        Object fast = field(target, preferredField);
        if (fast != null && isInstanceOfName(fast.getClass(), typeName)) {
            return fast;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v != null && isInstanceOfName(v.getClass(), typeName)) {
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return fast;
    }

    private static boolean isInstanceOfName(Class<?> cls, String targetName) {
        while (cls != null) {
            if (targetName.equals(cls.getName())) return true;
            cls = cls.getSuperclass();
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
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                if ("addView".equals(method.getName())) {
                    XposedBridge.hookMethod(method, ADD_VIEW);
                } else if ("updateViewLayout".equals(method.getName())) {
                    XposedBridge.hookMethod(method, UPDATE_VIEW_LAYOUT);
                }
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
            if (!taskbarModsOn() || param.args == null) {
                return;
            }
            if (!Boolean.TRUE.equals(DEX_UI_BUILTIN.get())) {
                for (int i = 0; i < param.args.length; i++) {
                    Object arg = param.args[i];
                    if (arg != null && "android.view.Display".equals(arg.getClass().getName())
                            && !isBuiltInDisplayObject(arg) && isActiveExternalDisplay(arg)) {
                        return;
                    }
                }
                if (param.thisObject instanceof Context && !isBuiltInDisplay((Context) param.thisObject)) {
                    return;
                }
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
                            if (param.thisObject instanceof View && isBuiltInView((View) param.thisObject)) {
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
        if (!taskbarModsOn() || !(area instanceof ViewGroup) || !isBuiltInView(area)) {
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
            if (!taskbarModsOn() || param.args == null || param.args.length == 0 || param.args[0] == null) {
                return;
            }
            if (isBuiltInDisplayObject(param.args[0]) || displayIdOf(param.args[0]) < 0) {
                return;
            }
            if (!Boolean.TRUE.equals(DEX_UI_BUILTIN.get()) && isActiveExternalDisplay(param.args[0])) {
                return;
            }
            int id = displayIdOf(param.args[0]);
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
            if (!taskbarModsOn() || param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                return;
            }
            int id = ((Integer) param.args[0]).intValue();
            if (isBuiltInDisplayId(id)) {
                return;
            }
            if (!Boolean.TRUE.equals(DEX_UI_BUILTIN.get()) && isActiveExternalDisplayId(id)) {
                return;
            }
            param.args[0] = Integer.valueOf(0);
        }
    };

    private static Context extractWindowManagerContext(Object wmObj) {
        if (wmObj == null) return null;
        try {
            Field f = windowManagerImplContextField;
            if (f != null && f.getDeclaringClass().isInstance(wmObj)) {
                Object val = f.get(wmObj);
                return (val instanceof Context) ? (Context) val : null;
            }
            Class<?> cls = wmObj.getClass();
            while (cls != null && cls != Object.class) {
                try {
                    f = cls.getDeclaredField("mContext");
                    f.setAccessible(true);
                    windowManagerImplContextField = f;
                    Object val = f.get(wmObj);
                    return (val instanceof Context) ? (Context) val : null;
                } catch (NoSuchFieldException ignored) {
                    cls = cls.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int getWindowContextType(Context ctx) {
        if (ctx == null) return -1;
        try {
            Method m = contextGetWindowTypeMethod;
            if (m == null) {
                m = Context.class.getMethod("getWindowType");
                contextGetWindowTypeMethod = m;
            }
            Object res = m.invoke(ctx);
            if (res instanceof Integer) {
                return ((Integer) res).intValue();
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean isPanelWindowContext(Context ctx) {
        return getWindowContextType(ctx) == PANEL_WINDOW_TYPE;
    }

    private static void alignLayoutParamsTypeWithContext(Context wmCtx, Object params) {
        if (wmCtx == null || !(params instanceof WindowManager.LayoutParams)) return;
        int ctxType = getWindowContextType(wmCtx);
        if (ctxType != PANEL_WINDOW_TYPE && ctxType != OVERLAY_WINDOW_TYPE) {
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params;
        if ((lp.type == PANEL_WINDOW_TYPE || lp.type == OVERLAY_WINDOW_TYPE) && lp.type != ctxType) {
            lp.type = ctxType;
        }
    }

    private static final XC_MethodHook UPDATE_VIEW_LAYOUT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length < 2) {
                return;
            }
            // If the view was already added with a specific window type, never let relayout change it.
            if (param.args[0] instanceof View) {
                ViewGroup.LayoutParams existing = ((View) param.args[0]).getLayoutParams();
                if (existing instanceof WindowManager.LayoutParams
                        && param.args[1] instanceof WindowManager.LayoutParams) {
                    int existingType = ((WindowManager.LayoutParams) existing).type;
                    WindowManager.LayoutParams next = (WindowManager.LayoutParams) param.args[1];
                    if ((existingType == PANEL_WINDOW_TYPE || existingType == OVERLAY_WINDOW_TYPE)
                            && (next.type == PANEL_WINDOW_TYPE || next.type == OVERLAY_WINDOW_TYPE)
                            && next.type != existingType) {
                        next.type = existingType;
                        return;
                    }
                }
            }
            Context wmCtx = extractWindowManagerContext(param.thisObject);
            if (wmCtx != null) {
                alignLayoutParamsTypeWithContext(wmCtx, param.args[1]);
            }
        }
    };

    private static boolean hasActiveExternalDisplay() {
        Context context = appContext;
        if (context == null) return false;
        try {
            Object manager = context.getSystemService("display");
            if (manager == null) return false;
            Object displays = manager.getClass().getMethod("getDisplays").invoke(manager);
            if (displays instanceof Object[]) {
                for (Object d : (Object[]) displays) {
                    if (isActiveExternalDisplay(d)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static final XC_MethodHook ADD_VIEW = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(IN_RETRY.get()) || !taskbarModsOn() || param.args == null) {
                return;
            }
            Context wmCtx = extractWindowManagerContext(param.thisObject);
            if (wmCtx != null && !isBuiltInDisplay(wmCtx)) {
                if (param.args.length > 1) {
                    alignLayoutParamsTypeWithContext(wmCtx, param.args[1]);
                }
                return;
            }
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                if (arg != null && "android.view.Display".equals(arg.getClass().getName())
                        && !isBuiltInDisplayObject(arg) && isActiveExternalDisplay(arg)) {
                    return;
                }
            }
            if (!Boolean.TRUE.equals(DEX_UI_BUILTIN.get())) {
                if (param.args.length > 0 && param.args[0] instanceof View) {
                    View v = (View) param.args[0];
                    if (!isBuiltInView(v)) {
                        return;
                    }
                }
            }
            boolean keepPanelType = isPanelWindowContext(wmCtx);
            if (!keepPanelType && param.args.length > 0 && param.args[0] instanceof View) {
                keepPanelType = isPanelWindowContext(((View) param.args[0]).getContext());
            }
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                if (arg == null) {
                    continue;
                }
                if ("android.view.Display".equals(arg.getClass().getName())) {
                    if (!isBuiltInDisplayObject(arg)) {
                        Object replacement = builtInDisplayObject();
                        if (replacement != null) {
                            param.args[i] = replacement;
                        }
                    }
                }
                if (!keepPanelType) {
                    forceOverlayType(arg);
                }
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Throwable error = param.getThrowable();
            if (!Boolean.TRUE.equals(IN_RETRY.get()) && taskbarModsOn() && error == null
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
            if (Boolean.TRUE.equals(IN_RETRY.get()) || !taskbarModsOn() || error == null) {
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
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params;
            if (lp.type == PANEL_WINDOW_TYPE) {
                lp.type = OVERLAY_WINDOW_TYPE;
            }
        }
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

    private static boolean isActiveExternalDisplay(Object display) {
        if (!(display instanceof Display)) {
            return false;
        }
        Display d = (Display) display;
        int id = d.getDisplayId();
        if (id <= Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (!d.isValid()) {
            return false;
        }
        return isActiveExternalDisplayId(id);
    }

    private static boolean isActiveExternalDisplayId(int id) {
        if (id <= Display.DEFAULT_DISPLAY) {
            return false;
        }
        Context context = appContext;
        if (context == null) {
            return false;
        }
        try {
            Object manager = context.getSystemService("display");
            if (manager == null) {
                return false;
            }
            Method m = displayManagerGetDisplayMethod;
            if (m == null) {
                m = manager.getClass().getMethod("getDisplay", int.class);
                displayManagerGetDisplayMethod = m;
            }
            Object display = m.invoke(manager, Integer.valueOf(id));
            return (display instanceof Display) && ((Display) display).isValid()
                    && !isBuiltInDisplayObject(display);
        } catch (Throwable ignored) {
            return false;
        }
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
            if (display != null) {
                return display;
            }
            Object displays = manager.getClass().getMethod("getDisplays").invoke(manager);
            if (displays instanceof Object[]) {
                for (Object d : (Object[]) displays) {
                    if (isBuiltInDisplayObject(d)) {
                        return d;
                    }
                }
            }
            return null;
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
