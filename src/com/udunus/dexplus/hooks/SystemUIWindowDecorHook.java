package com.udunus.dexplus.hooks;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.udunus.dexplus.util.ModConfig;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Customizes SystemUI freeform/DeX window decorations and status bar behavior:
 *  - Collapses status bar height & desktop/IME top inset in Internal DeX
 *  - Remaps top-edge window drag indicator from Exit DeX / Fullscreen to Maximize Window
 *  - Keeps maximize click inside DeX and makes long-press Maximize toggle Fullscreen/Immersive
 *  - Hides redundant Enter Fullscreen button in freeform/maximized mode while keeping Restore in fullscreen
 *  - Hides caption app chevron while keeping the app icon menu clickable
 *  - Adds Pin (Always on Top) button using SurfaceControl.Transaction.setLayer(1000000+) instead of
 *    WindowContainerTransaction.setAlwaysOnTop (which caused DeX to minimize other windows!)
 *  - Customizes DexFreeformPopupButtonPolicy App Menu: replaces "Remove from workspace" with "App info",
 *    adds a live "Transparency" slider, and optional "Center" / "Keep Awake" tools.
 */
public final class SystemUIWindowDecorHook {
    private static final String TAG = ModConfig.TAG;
    private static final String INSET_KEY = "externaldex_status_inset";
    private static final String EFFECTS_KEY = "externaldex_effects";

    private static final int IDX_MAXIMIZE_BUTTON = 0;
    private static final int IDX_TOGGLE_FREEFORM = 1;
    private static final int IDX_TOGGLE_IMMERSIVE = 2;
    private static final int IDX_FULLSCREEN_BUTTON = 3;
    private static final int IDX_EXPAND_MENU_BUTTON = 4;
    private static final int IDX_REMOVE_FROM_WORKSPACE = 5;
    private static final int IDX_MINIMIZE_WINDOW = 6;
    private static final int IDX_APP_ICON = 7;
    private static final int IDX_OPEN_MENU_BUTTON = 8;
    private static final int IDX_CLOSE_WINDOW = 9;

    private static final String[] SYSUI_ID_NAMES = {
            "maximize_window",
            "toggle_freeform_window",
            "toggle_immersive_window",
            "fullscreen_button",
            "expand_menu_button",
            "remove_from_workspace",
            "minimize_window",
            "application_icon",
            "open_menu_button",
            "close_window"
    };
    private static final int[] SYSUI_ID_FALLBACKS = {
            0x7e0a075d, 0x7e0a0e72, 0x7e0a0e73, 0x7e0a0505, 0x7e0a0491,
            0x7e0a0a8e, 0x7e0a07bb, 0x7e0a0131, 0x7e0a093e, 0x7e0a02e9
    };
    private static volatile int[] cachedSysUiIds;
    private static final String PIN_TAG = "internaldex.pin";
    private static final String BACK_TAG = "internaldex.back";
    private static final String SLIDER_TAG = "internaldex.opacity";
    private static final String TOOLS_TAG = "internaldex.tools";
    private static final float MIN_WINDOW_ALPHA = 0.2f;

    private static final WeakHashMap<View, Boolean> HIDDEN_MAXIMIZE = new WeakHashMap<View, Boolean>();
    private static final WeakHashMap<View, Boolean> HIDDEN_FULLSCREEN = new WeakHashMap<View, Boolean>();
    private static final WeakHashMap<View, Boolean> HIDDEN_CHEVRON = new WeakHashMap<View, Boolean>();
    private static final Set<Integer> PINNED_IDS = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Map<Integer, TaskChrome> TASK_CHROME = Collections.synchronizedMap(new HashMap<Integer, TaskChrome>());
    private static final Map<Integer, Float> WINDOW_ALPHA = Collections.synchronizedMap(new HashMap<Integer, Float>());

    private static final Set<Integer> ALPHA_MANUAL = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Set<Integer> CLEARED_ALWAYS_ON_TOP = Collections.synchronizedSet(new HashSet<Integer>());
    private static final WeakHashMap<View, WeakReference<Object>> PIN_DECORS = new WeakHashMap<View, WeakReference<Object>>();
    private static final WeakHashMap<View, WeakReference<Object>> BACK_DECORS = new WeakHashMap<View, WeakReference<Object>>();
    private static final WeakHashMap<View, Boolean> MENU_SHRUNK = new WeakHashMap<View, Boolean>();

    private static final ThreadLocal<Boolean> PIN_STAMPING = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> DROP_STATUS_INSET = new ThreadLocal<Boolean>();

    private static volatile boolean pinOrderPosted;
    private static volatile Object pinOrderCover;
    private static int nextFrontRank;
    private static Constructor<?> transactionCtor;
    private static Method relativeLayerMethod;
    private static Method layerMethod;
    private static Method alphaMethod;
    private static Bitmap infoIcon;
    private static Bitmap pinIcon;
    private static Bitmap unpinIcon;
    private static Bitmap backIcon;
    private static boolean loggedBack;
    private static boolean loggedBackFocus;
    private static volatile boolean collapseStatus;
    private static boolean insetWatching;
    private static Object statusBarController;
    private static ClassLoader shellLoader;
    private static Field forceHideStatusField;
    private static boolean forcedStatusHide;
    private static int statusBarsType;
    private static int imeType;
    private static Context systemUiContext;

    private static final Map<Integer, Rect> LAST_FREEFORM_BOUNDS = new ConcurrentHashMap<Integer, Rect>();
    private static final Set<Integer> CLIENT_FULLSCREEN_TASKS =
            Collections.synchronizedSet(new HashSet<Integer>());

    private static boolean loggedDesktopInset;
    private static boolean loggedDesktopFlag;
    private static boolean loggedTypingInset;
    private static boolean loggedTypingTop;
    private static boolean loggedFreeRegion;
    private static boolean loggedIndicator;
    private static boolean loggedIndicatorMiss;
    private static boolean loggedCaption;
    private static boolean loggedDragRestore;
    private static boolean loggedCaptionMiss;
    private static boolean loggedImmersive;
    private static boolean loggedPin;
    private static boolean loggedPinMiss;
    private static boolean loggedPinRaise;
    private static boolean loggedFullscreenGuard;
    private static boolean loggedSystemFullscreenKept;
    private static boolean loggedMaximizeHide;
    private static boolean loggedFullscreenHide;
    private static boolean loggedMenu;
    private static boolean loggedAppInfo;
    private static boolean loggedAlpha;
    private static boolean loggedStatusHold;

    private SystemUIWindowDecorHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // Skip secondary SystemUI processes like :edgelighting or dynamically loaded plugin APKs
        // that do not contain SystemUI's wm.shell classes, so shellLoader is never overwritten.
        if (lpparam.processName != null && lpparam.processName.contains(":")) {
            return;
        }
        try {
            Class.forName("com.android.wm.shell.windowdecor.DesktopModeWindowDecoration", false, lpparam.classLoader);
        } catch (Throwable ignored) {
            return;
        }
        XposedBridge.log(TAG + ": Hooking SystemUI (com.android.systemui)");
        shellLoader = lpparam.classLoader;
        ModConfig.addChangeListener(new Runnable() {
            @Override
            public void run() {
                Runnable refresh = new Runnable() {
                    @Override
                    public void run() {
                        applyDefaultOpacityToKnown();
                        applyCornerRadiusToKnown();
                        refreshKnownCaptions();
                    }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    refresh.run();
                } else {
                    new Handler(Looper.getMainLooper()).post(refresh);
                }
            }
        });
        XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "attach", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Context) {
                            Context appCtx = (Context) param.args[0];
                            if (systemUiContext == null) {
                                systemUiContext = appCtx;
                            }
                            ModConfig.ensureInitialized(appCtx);
                            ModConfig.registerRestartReceiver(appCtx, "systemui");
                        }
                    }
                });
        hookStatusBarInset(lpparam.classLoader);
        hookDesktopStableBounds(lpparam.classLoader);
        hookTypingStatusInset(lpparam.classLoader);
        hookDesktopDrop(lpparam.classLoader);
        hookCaptionButtons(lpparam.classLoader);
        hookWindowPin(lpparam.classLoader);
        hookAppMenu(lpparam.classLoader);
        hookClientFullscreen(lpparam.classLoader);
        hookCornerRadius(lpparam.classLoader);
    }

    /** Applies the corner-radius setting to the window and the thin outline around it. */
    private static void hookCornerRadius(ClassLoader loader) {
        try {
            Class<?> decor = Class.forName(
                    "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration", false, loader);
            int hooked = 0;
            for (Method method : decor.getDeclaredMethods()) {
                if (!"updateRelayoutParams".equals(method.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        recordFreeformBounds(param.thisObject, param.args);
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                            return;
                        }
                        Object params = param.args[0];
                        int current;
                        try {
                            current = XposedHelpers.getIntField(params, "mCornerRadius");
                        } catch (Throwable error) {
                            return;
                        }
                        boolean maximized = isTaskMaximizedDecor(param.thisObject, param.args);
                        if (maximized) {
                            if (current != 0) {
                                try {
                                    XposedHelpers.setIntField(params, "mCornerRadius", 0);
                                } catch (Throwable ignored) {}
                            }
                            applyOutlineRadius(param.thisObject, 0);
                            return;
                        }
                        if (current < 0) {
                            return;
                        }
                        Context ctx = contextFrom(param.args);
                        int next = floatingRadiusPx(ctx, current);
                        if (next != current) {
                            try {
                                XposedHelpers.setIntField(params, "mCornerRadius", next);
                            } catch (Throwable ignored) {}
                        }
                        applyOutlineRadius(param.thisObject, next);
                    }
                });
                hooked++;
            }
            Class<?> outline = Class.forName(
                    "com.android.wm.shell.windowdecor.FreeformOutline", false, loader);
            for (Method method : outline.getDeclaredMethods()) {
                if (!"relayout".equals(method.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object decor = field(param.thisObject, "mDecoration");
                        if (param.args != null && param.args.length > 2
                                && isTaskMaximizedDecor(decor, param.args)) {
                            param.args[2] = Integer.valueOf(0);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object decor = field(param.thisObject, "mDecoration");
                        if (isTaskMaximizedDecor(decor, param.args)) {
                            applyOutlineRadius(decor, 0);
                            return;
                        }
                        Object context = field(param.thisObject, "mContext");
                        int passed = -1;
                        if (param.args != null && param.args.length > 2 && param.args[2] instanceof Integer) {
                            passed = ((Integer) param.args[2]).intValue();
                        }
                        Context ctx = context instanceof Context ? (Context) context : null;
                        int px = floatingRadiusPx(ctx, passed);
                        if (px >= 0) {
                            applyOutlineRadius(decor, px);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + ": corner radius hooks " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook corner radius: " + error);
        }
    }

    private static int sysUiId(Context ctx, int idx) {
        int[] ids = cachedSysUiIds;
        if (ids == null) {
            Context source = ctx != null ? ctx : systemUiContext;
            if (source != null) {
                try {
                    android.content.res.Resources res = source.getResources();
                    String pkg = source.getPackageName();
                    int[] resolved = new int[SYSUI_ID_NAMES.length];
                    boolean anyResolved = false;
                    for (int i = 0; i < SYSUI_ID_NAMES.length; i++) {
                        int id = res.getIdentifier(SYSUI_ID_NAMES[i], "id", pkg);
                        if (id == 0) {
                            id = res.getIdentifier(SYSUI_ID_NAMES[i], "id", "com.android.systemui");
                        }
                        if (id != 0) {
                            anyResolved = true;
                            resolved[i] = id;
                        } else {
                            resolved[i] = SYSUI_ID_FALLBACKS[i];
                        }
                    }
                    if (anyResolved) {
                        cachedSysUiIds = resolved;
                    }
                    ids = resolved;
                } catch (Throwable ignored) {}
            }
        }
        return ids != null ? ids[idx] : SYSUI_ID_FALLBACKS[idx];
    }

    private static View findSysUiView(View root, int idx) {
        return root == null ? null : root.findViewById(sysUiId(root.getContext(), idx));
    }

    private static int resolveSystemCornerPx(Context ctx) {
        Context source = ctx != null ? ctx : systemUiContext;
        if (source == null) {
            return -1;
        }
        String[] names = {"mw_desktop_external_display_corner_radius", "freeform_rounded_corner_radius"};
        for (int i = 0; i < names.length; i++) {
            try {
                int id = source.getResources().getIdentifier(names[i], "dimen", "android");
                if (id != 0) {
                    int px = source.getResources().getDimensionPixelSize(id);
                    if (px >= 0) {
                        return px;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static int floatingRadiusPx(Context ctx, int systemPx) {
        Context source = ctx != null ? ctx : systemUiContext;
        if (source != null && !ModConfig.isModuleEnabled(source)) {
            return systemPx;
        }
        int dp = ModConfig.getInt(source, ModConfig.KEY_WINDOW_CORNER_RADIUS, -1);
        if (dp < 0) {
            return systemPx;
        }
        if (dp == 0) {
            return 0;
        }
        float density = 1f;
        if (source != null) {
            density = source.getResources().getDisplayMetrics().density;
        }
        return Math.max(0, Math.round(dp * density));
    }

    /** The thin window stroke is a separate view. Its radius has to match the surface clip. */
    private static void applyOutlineRadius(Object decor, int radiusPx) {
        if (decor == null) {
            return;
        }
        Object outline = field(decor, "mFreeformOutline");
        if (outline == null) {
            return;
        }
        Object view;
        try {
            view = XposedHelpers.callMethod(outline, "getOutlineView");
        } catch (Throwable error) {
            return;
        }
        if (!(view instanceof View)) {
            return;
        }
        try {
            XposedHelpers.setIntField(view, "mRadius", radiusPx);
        } catch (Throwable ignored) {}
        ((View) view).invalidate();
    }

    private static Context contextFrom(Object[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Context) {
                    return (Context) args[i];
                }
            }
        }
        return systemUiContext;
    }

    private static void applyCornerRadiusToKnown() {
        ArrayList<TaskChrome> windows = new ArrayList<TaskChrome>();
        synchronized (TASK_CHROME) {
            windows.addAll(TASK_CHROME.values());
        }
        int fallback = resolveSystemCornerPx(systemUiContext);
        int px = floatingRadiusPx(systemUiContext, fallback);
        if (px < 0) {
            return;
        }
        for (int i = 0; i < windows.size(); i++) {
            TaskChrome chrome = windows.get(i);
            if (chrome == null || !surfaceValid(chrome.task)) {
                continue;
            }
            int target = isTaskMaximizedDecor(chrome.host) ? 0 : px;
            setSurfaceCorner(chrome.task, target);
            applyOutlineRadius(chrome.host, target);
        }
    }

    private static Object runningTaskFromArgs(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && "android.app.ActivityManager$RunningTaskInfo".equals(arg.getClass().getName())) {
                return arg;
            }
        }
        return null;
    }

    private static Rect taskBounds(Object task) {
        if (task == null) {
            return null;
        }
        try {
            Object config = field(task, "configuration");
            Object wc = field(config, "windowConfiguration");
            Object bounds = wc == null ? null : XposedHelpers.callMethod(wc, "getBounds");
            return bounds instanceof Rect ? (Rect) bounds : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isTaskMaximizedDecor(Object decor, Object... args) {
        if (!ModConfig.isModuleEnabled(systemUiContext)) {
            return false;
        }
        Object argTask = runningTaskFromArgs(args);
        Object task = argTask != null ? argTask : field(decor, "mTaskInfo");
        if (task != null) {
            try {
                Object mode = XposedHelpers.callMethod(task, "getWindowingMode");
                if (mode instanceof Integer && ((Integer) mode).intValue() == 1) {
                    return true;
                }
            } catch (Throwable ignored) {}
            if (decor != null) {
                try {
                    Object dc = field(decor, "mDisplayController");
                    if (dc != null) {
                        int displayId = XposedHelpers.getIntField(task, "displayId");
                        Object layout = XposedHelpers.callMethod(dc, "getDisplayLayout", displayId);
                        if (layout != null) {
                            try {
                                Class<?> utils = Class.forName(
                                        "com.android.wm.shell.desktopmode.DesktopModeUtils",
                                        false, decor.getClass().getClassLoader());
                                if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                                        utils, "isTaskMaximized", task, layout))) {
                                    return true;
                                }
                            } catch (Throwable ignored) {}
                            Rect bounds = taskBounds(task);
                            if (bounds != null && !bounds.isEmpty()) {
                                Rect stable = new Rect();
                                try {
                                    XposedHelpers.callMethod(layout, "getStableBoundsForDesktopMode", stable);
                                    if (!stable.isEmpty() && bounds.width() >= stable.width()
                                            && bounds.height() >= stable.height()) {
                                        return true;
                                    }
                                } catch (Throwable ignored) {}
                                try {
                                    int dw = ((Integer) XposedHelpers.callMethod(layout, "width")).intValue();
                                    int dh = ((Integer) XposedHelpers.callMethod(layout, "height")).intValue();
                                    if (dw > 0 && dh > 0 && bounds.width() >= dw && bounds.height() >= dh) {
                                        return true;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (argTask == null && decor != null) {
            try {
                if (Boolean.TRUE.equals(field(decor, "mIsTaskMaximized"))) {
                    return true;
                }
            } catch (Throwable ignored) {}
            try {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(decor, "isTaskMaximized"))) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void recordFreeformBounds(Object decor, Object[] args) {
        Object task = runningTaskFromArgs(args);
        if (task == null) {
            task = field(decor, "mTaskInfo");
        }
        if (task == null) {
            return;
        }
        try {
            Object mode = XposedHelpers.callMethod(task, "getWindowingMode");
            if (!(mode instanceof Integer) || ((Integer) mode).intValue() != 5) {
                return;
            }
            int taskId = XposedHelpers.getIntField(task, "taskId");
            if (CLIENT_FULLSCREEN_TASKS.contains(Integer.valueOf(taskId))) {
                return;
            }
            Rect bounds = taskBounds(task);
            if (bounds == null || bounds.isEmpty()) {
                return;
            }
            Rect full = displayBounds(decor, task);
            if (full != null && bounds.width() >= full.width() && bounds.height() >= full.height()) {
                return;
            }
            LAST_FREEFORM_BOUNDS.put(Integer.valueOf(taskId), new Rect(bounds));
        } catch (Throwable ignored) {}
    }

    private static void refreshKnownCaptions() {
        ArrayList<TaskChrome> windows = new ArrayList<TaskChrome>();
        synchronized (TASK_CHROME) {
            windows.addAll(TASK_CHROME.values());
        }
        for (int i = 0; i < windows.size(); i++) {
            TaskChrome chrome = windows.get(i);
            if (chrome != null && chrome.host != null) {
                try {
                    prepareCaption(chrome.host);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void setSurfaceCorner(Object surface, int radiusPx) {
        try {
            Object txn = newTransaction(surface);
            if (txn == null) {
                return;
            }
            Class<?> surfaceClass = Class.forName(
                    "android.view.SurfaceControl", false, surface.getClass().getClassLoader());
            txn.getClass().getMethod("setCornerRadius", surfaceClass, float.class)
                    .invoke(txn, surface, Float.valueOf(radiusPx));
            PIN_STAMPING.set(Boolean.TRUE);
            try {
                txn.getClass().getMethod("apply").invoke(txn);
            } finally {
                PIN_STAMPING.set(Boolean.FALSE);
            }
        } catch (Throwable ignored) {
            PIN_STAMPING.set(Boolean.FALSE);
        }
    }

    /**
     * A client fullscreen request that still reaches the shell would leave the desk
     * and close DeX. Refuse that enter request. Exit requests and immersive mode
     * are unchanged.
     */
    private static void hookClientFullscreen(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "com.android.wm.shell.desktopmode.clientfullscreenrequest.ClientFullscreenRequestTransitionHandler",
                    false, loader);
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                if ("shouldHandleRequest".equals(method.getName())
                        && method.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(method, KEEP_DESK_ON_ENTER_FULLSCREEN);
                    hooked++;
                }
            }
            Class<?> tasksController = Class.forName(
                    "com.android.wm.shell.desktopmode.DesktopTasksController",
                    false, loader);
            for (Method method : tasksController.getDeclaredMethods()) {
                if ("handleRequest".equals(method.getName())) {
                    XposedBridge.hookMethod(method, KEEP_DESK_ON_SYSTEM_FULLSCREEN);
                    hooked++;
                }
            }
            hooked += hookTaskChangeFullscreenGuards(loader);
            XposedBridge.log(TAG + ": fullscreen desk guard " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not guard desktop fullscreen: " + error);
        }
    }

    private static int hookTaskChangeFullscreenGuards(ClassLoader loader) {
        int hooked = 0;
        String[] observerLambdas = {
                "com.android.wm.shell.freeform.FreeformTaskTransitionObserver$$ExternalSyntheticLambda8",
        };
        for (String name : observerLambdas) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                for (Method method : cls.getDeclaredMethods()) {
                    if ("accept".equals(method.getName())) {
                        XposedBridge.hookMethod(method, MARK_CLIENT_FULLSCREEN_TASK);
                        hooked++;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> listenerCls = Class.forName(
                    "com.android.wm.shell.desktopmode.DesktopTaskChangeListener", false, loader);
            for (Method method : listenerCls.getDeclaredMethods()) {
                if ("onTaskChanging".equals(method.getName())) {
                    XposedBridge.hookMethod(method, MARK_CLIENT_FULLSCREEN_TASK);
                    hooked++;
                } else if ("onTaskClosing".equals(method.getName())) {
                    XposedBridge.hookMethod(method, CLEAR_CLIENT_FULLSCREEN_TASK);
                    hooked++;
                } else if ("removeTask".equals(method.getName())) {
                    XposedBridge.hookMethod(method, BLOCK_CLIENT_FULLSCREEN_TASK_REMOVE);
                    hooked++;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> exitRunCls = Class.forName(
                    "com.android.wm.shell.desktopmode.DesktopTaskChangeListener$onTaskChanging$1",
                    false, loader);
            for (Method method : exitRunCls.getDeclaredMethods()) {
                if ("run".equals(method.getName())) {
                    XposedBridge.hookMethod(method, BLOCK_CLIENT_FULLSCREEN_DESK_EXIT);
                    hooked++;
                }
            }
        } catch (Throwable ignored) {}
        return hooked;
    }

    private static boolean isTrackedClientFullscreen(Object taskInfo) {
        if (taskInfo == null || !ModConfig.isModuleEnabled(systemUiContext) || insideWindowFullscreen()) {
            return false;
        }
        try {
            int taskId = XposedHelpers.getIntField(taskInfo, "taskId");
            if (!CLIENT_FULLSCREEN_TASKS.contains(Integer.valueOf(taskId))) {
                return false;
            }
            Object modeObj = XposedHelpers.callMethod(taskInfo, "getWindowingMode");
            return (modeObj instanceof Integer) && ((Integer) modeObj).intValue() == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final XC_MethodHook MARK_CLIENT_FULLSCREEN_TASK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!ModConfig.isModuleEnabled(systemUiContext) || insideWindowFullscreen()) {
                return;
            }
            try {
                Object taskInfo = null;
                boolean isOnTaskChanging = true;
                if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                    String argType = param.args[0].getClass().getName();
                    if (argType.endsWith("RunningTaskInfo")) {
                        taskInfo = param.args[0];
                    } else if (argType.endsWith("DesktopTaskChangeListener")) {
                        Object opId = field(param.thisObject, "$r8$classId");
                        isOnTaskChanging = (opId instanceof Integer) && ((Integer) opId).intValue() == 2;
                        Object change = field(param.thisObject, "f$0");
                        if (change != null) {
                            taskInfo = XposedHelpers.callMethod(change, "getTaskInfo");
                        }
                    }
                }
                if (isOnTaskChanging && isTrackedClientFullscreen(taskInfo)) {
                    try {
                        XposedHelpers.setBooleanField(taskInfo, "requestFullscreenMode", true);
                    } catch (Throwable ignored) {}
                    param.setResult(null);
                }
            } catch (Throwable error) {
                XposedBridge.log(TAG + ": mark client fullscreen error: " + error);
            }
        }
    };

    private static final XC_MethodHook BLOCK_CLIENT_FULLSCREEN_TASK_REMOVE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!ModConfig.isModuleEnabled(systemUiContext) || insideWindowFullscreen()) {
                return;
            }
            if (param.args == null || param.args.length < 3) {
                return;
            }
            Object taskIdObj = param.args[1];
            Object isClosingObj = param.args[2];
            if ((taskIdObj instanceof Integer)
                    && !Boolean.TRUE.equals(isClosingObj)
                    && CLIENT_FULLSCREEN_TASKS.contains((Integer) taskIdObj)) {
                param.setResult(null);
            }
        }
    };

    private static final XC_MethodHook CLEAR_CLIENT_FULLSCREEN_TASK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                return;
            }
            try {
                int taskId = XposedHelpers.getIntField(param.args[0], "taskId");
                CLIENT_FULLSCREEN_TASKS.remove(Integer.valueOf(taskId));
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook BLOCK_CLIENT_FULLSCREEN_DESK_EXIT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object taskInfo = field(param.thisObject, "$taskInfo");
                if (isTrackedClientFullscreen(taskInfo)) {
                    param.setResult(null);
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook KEEP_DESK_ON_SYSTEM_FULLSCREEN = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!ModConfig.isModuleEnabled(systemUiContext) || insideWindowFullscreen()) {
                return;
            }
            if (param.args == null || param.args.length < 2 || param.args[1] == null) {
                return;
            }
            Object request = param.args[1];
            try {
                Object typeObj = XposedHelpers.callMethod(request, "getType");
                if (!(typeObj instanceof Integer) || ((Integer) typeObj).intValue() != 6) {
                    return;
                }
                Object task = XposedHelpers.callMethod(request, "getTriggerTask");
                if (task == null) {
                    return;
                }
                int taskId = XposedHelpers.getIntField(task, "taskId");
                Object modeObj = XposedHelpers.callMethod(task, "getWindowingMode");
                if (!(modeObj instanceof Integer)) {
                    return;
                }
                int mode = ((Integer) modeObj).intValue();
                Object handler = field(param.thisObject, "clientFullscreenRequestTransitionHandler");
                if (mode == 1 && handler != null) {
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(handler, "shouldHandleRequest", request))) {
                        return;
                    }
                    CLIENT_FULLSCREEN_TASKS.add(Integer.valueOf(taskId));
                    Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction",
                            false, param.thisObject.getClass().getClassLoader());
                    Object wct = wctClass.getDeclaredConstructor().newInstance();
                    Object token = field(task, "token");
                    Rect full = displayBoundsFromController(param.thisObject, task);
                    if (token != null) {
                        if (full != null) {
                            XposedHelpers.callMethod(wct, "setBounds", token, full);
                        }
                        try {
                            XposedHelpers.callMethod(wct, "setForceImmersiveChange", token);
                        } catch (Throwable ignored) {}
                        XposedHelpers.callMethod(wct, "reorder", token, Boolean.TRUE);
                    }
                    param.setResult(wct);
                    if (!loggedSystemFullscreenKept) {
                        loggedSystemFullscreenKept = true;
                        XposedBridge.log(TAG + ": kept system video fullscreen inside DeX");
                    }
                    return;
                }
                if (mode == 5 && CLIENT_FULLSCREEN_TASKS.remove(Integer.valueOf(taskId))) {
                    Object token = field(task, "token");
                    if (token != null) {
                        Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction",
                                false, param.thisObject.getClass().getClassLoader());
                        Object wct = wctClass.getDeclaredConstructor().newInstance();
                        Rect saved = LAST_FREEFORM_BOUNDS.get(Integer.valueOf(taskId));
                        if (saved != null && !saved.isEmpty()) {
                            Rect current = taskBounds(task);
                            Rect full = displayBoundsFromController(param.thisObject, task);
                            if (current == null || current.isEmpty() || (full != null && current.equals(full))) {
                                XposedHelpers.callMethod(wct, "setBounds", token, new Rect(saved));
                            }
                        }
                        // Force a hierarchy lifecycle pass on exit so underlying desktop
                        // windows, Home, and wallpaper hidden during fullscreen become visible again.
                        XposedHelpers.callMethod(wct, "reorder", token, Boolean.TRUE);
                        param.setResult(wct);
                    }
                }
            } catch (Throwable error) {
                XposedBridge.log(TAG + ": system fullscreen guard failed: " + error);
            }
        }
    };

    private static Rect displayBoundsFromController(Object controller, Object task) {
        if (controller == null || task == null) {
            return null;
        }
        try {
            Object dc = field(controller, "displayController");
            if (dc == null) {
                return displayBoundsFromContext(controller);
            }
            int displayId = XposedHelpers.getIntField(task, "displayId");
            Object layout = XposedHelpers.callMethod(dc, "getDisplayLayout", displayId);
            if (layout == null) {
                return displayBoundsFromContext(controller);
            }
            int w = ((Integer) XposedHelpers.callMethod(layout, "width")).intValue();
            int h = ((Integer) XposedHelpers.callMethod(layout, "height")).intValue();
            return w > 0 && h > 0 ? new Rect(0, 0, w, h) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final XC_MethodHook KEEP_DESK_ON_ENTER_FULLSCREEN = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(param.getResult()) || !insideWindowFullscreen()) {
                return;
            }
            if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                return;
            }
            try {
                Object task = param.args[0].getClass().getMethod("getTriggerTask").invoke(param.args[0]);
                if (task == null) {
                    return;
                }
                Object mode = task.getClass().getMethod("getWindowingMode").invoke(task);
                if (!(mode instanceof Integer) || ((Integer) mode).intValue() != 1) {
                    return;
                }
                param.setResult(Boolean.FALSE);
                if (!loggedFullscreenGuard) {
                    loggedFullscreenGuard = true;
                    XposedBridge.log(TAG + ": blocked a video fullscreen from leaving DeX");
                }
            } catch (Throwable ignored) {}
        }
    };

    private static boolean insideWindowFullscreen() {
        try {
            ModConfig.reload(systemUiContext);
        } catch (Throwable ignored) {}
        return ModConfig.isModuleEnabled(systemUiContext)
                && ModConfig.getBoolean(systemUiContext, ModConfig.KEY_FULLSCREEN_INSIDE, true);
    }

    private static void hookStatusBarInset(ClassLoader loader) {
        try {
            Class<?> utils = Class.forName("com.android.internal.policy.SystemBarUtils", false, loader);
            int hooked = 0;
            for (Method method : utils.getDeclaredMethods()) {
                String name = method.getName();
                if ("getStatusBarHeight".equals(name) || "getStatusBarHeightForRotation".equals(name)) {
                    XposedBridge.hookMethod(method, COLLAPSE_STATUS_HEIGHT);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": status height hooks " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook status height: " + error);
        }
        try {
            Class<?> type = Class.forName(
                    "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl", false, loader);
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if ("refreshStatusBarHeight".equals(name)) {
                    XposedBridge.hookMethod(method, REMEMBER_STATUS_BAR);
                } else if ("apply".equals(name) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, KEEP_STATUS_COLLAPSED);
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not watch status bar controller: " + error);
        }
    }

    private static void hookDesktopStableBounds(ClassLoader loader) {
        try {
            Class<?> layout = Class.forName(
                    "com.android.wm.shell.common.DisplayLayout", false, loader);
            int hooked = 0;
            for (Method method : layout.getDeclaredMethods()) {
                String name = method.getName();
                int count = method.getParameterTypes().length;
                if ("stableInsets".equals(name)) {
                    XposedBridge.hookMethod(method, SYNC_DESKTOP_STATUS);
                    hooked++;
                } else if ("getStableBoundsForDesktopMode".equals(name)
                        || "getStableBoundsByInsetsVisibility".equals(name)) {
                    XposedBridge.hookMethod(method, SYNC_DESKTOP_STATUS);
                    XposedBridge.hookMethod(method, CLEAR_DESKTOP_TOP);
                    hooked++;
                } else if ("getStableBounds".equals(name) && count == 2) {
                    XposedBridge.hookMethod(method, SYNC_DESKTOP_STATUS);
                    XposedBridge.hookMethod(method, CLEAR_DESKTOP_TYPED_TOP);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": desktop bounds hooks " + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook desktop bounds: " + error);
        }
    }

    private static void hookTypingStatusInset(ClassLoader loader) {
        int hooked = 0;
        try {
            Class<?> positioner = Class.forName(
                    "com.android.wm.shell.windowdecor.MultiDisplayVeiledResizeTaskPositioner",
                    false, loader);
            for (Method method : positioner.getDeclaredMethods()) {
                if ("setStableBoundsWithIme".equals(method.getName())) {
                    XposedBridge.hookMethod(method, MARK_TYPING_INSET);
                    hooked++;
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook typing bounds: " + error);
        }
        try {
            Class<?> ime = Class.forName(
                    "com.android.wm.shell.freeform.ime.FreeformImeController", false, loader);
            for (Method method : ime.getDeclaredMethods()) {
                if ("getTargetOffsetY".equals(method.getName())) {
                    XposedBridge.hookMethod(method, MARK_TYPING_INSET);
                    hooked++;
                }
            }
            Class<?> layout = Class.forName(
                    "com.android.wm.shell.common.DisplayLayout", false, loader);
            for (Method method : layout.getDeclaredMethods()) {
                if ("getStableBounds".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, CLEAR_TYPING_TOP);
                    hooked++;
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook typing offset: " + error);
        }
        try {
            Class<?> insets = Class.forName("android.view.InsetsState", false, loader);
            for (Method method : insets.getDeclaredMethods()) {
                if ("calculateInsets".equals(method.getName())) {
                    XposedBridge.hookMethod(method, STRIP_STATUS_FROM_IME);
                    hooked++;
                }
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": could not hook inset math: " + error);
        }
        XposedBridge.log(TAG + ": typing inset hooks " + hooked);
    }

    private static final XC_MethodHook SYNC_DESKTOP_STATUS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            syncDesktopStatusHide();
        }
    };

    private static final XC_MethodHook CLEAR_DESKTOP_TOP = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!collapseStatus || param.args == null || param.args.length == 0) {
                return;
            }
            clearRectTop(param.args[0], false);
        }
    };

    private static final XC_MethodHook CLEAR_DESKTOP_TYPED_TOP = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!collapseStatus || param.args == null || param.args.length < 2) {
                return;
            }
            if (!(param.args[1] instanceof Enum)
                    || !"FOR_DESKTOP".equals(((Enum<?>) param.args[1]).name())) {
                return;
            }
            clearRectTop(param.args[0], false);
        }
    };

    private static final XC_MethodHook MARK_TYPING_INSET = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!statusCollapsedNow()) {
                return;
            }
            DROP_STATUS_INSET.set(Boolean.TRUE);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            DROP_STATUS_INSET.remove();
        }
    };

    private static final XC_MethodHook CLEAR_TYPING_TOP = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!collapseStatus || param.args == null || param.args.length == 0) {
                return;
            }
            clearRectTop(param.args[0], true);
        }
    };

    private static final XC_MethodHook CLEAR_FULLSCREEN_REGION = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!topEdgeMaximizeOn()) {
                return;
            }
            Object region = param.getResult();
            if (region == null) {
                return;
            }
            try {
                region.getClass().getMethod("setEmpty").invoke(region);
                if (!loggedFreeRegion) {
                    loggedFreeRegion = true;
                    XposedBridge.log(TAG + ": status strip accepts a floating window");
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook STRIP_STATUS_FROM_IME = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(DROP_STATUS_INSET.get()) || param.args == null) {
                return;
            }
            if (!ensureInsetTypes()) {
                return;
            }
            for (int i = 0; i < param.args.length; i++) {
                if (!(param.args[i] instanceof Integer)) {
                    continue;
                }
                int mask = ((Integer) param.args[i]).intValue();
                if (mask == statusBarsType || (mask & imeType) == 0 || (mask & statusBarsType) == 0) {
                    continue;
                }
                param.args[i] = Integer.valueOf(mask & ~statusBarsType);
                if (!loggedTypingInset) {
                    loggedTypingInset = true;
                    XposedBridge.log(TAG + ": typing status inset dropped");
                }
            }
        }
    };

    private static void syncDesktopStatusHide() {
        ClassLoader loader = shellLoader;
        if (loader == null && forceHideStatusField == null) {
            return;
        }
        boolean collapse = statusCollapsedNow();
        try {
            if (forceHideStatusField == null) {
                Class<?> type = Class.forName(
                        "com.android.wm.shell.windowdecor.CaptionGlobalState", false, loader);
                forceHideStatusField = type.getDeclaredField("FORCE_HIDE_STATUS_BAR_ON_DEFAULT_DISPLAY");
                forceHideStatusField.setAccessible(true);
            }
            boolean current = forceHideStatusField.getBoolean(null);
            if (collapse) {
                if (!current) {
                    forceHideStatusField.setBoolean(null, true);
                    forcedStatusHide = true;
                    if (!loggedDesktopFlag) {
                        loggedDesktopFlag = true;
                        XposedBridge.log(TAG + ": desktop status flag on");
                    }
                }
            } else if (forcedStatusHide) {
                forceHideStatusField.setBoolean(null, false);
                forcedStatusHide = false;
                XposedBridge.log(TAG + ": desktop status flag off");
            }
        } catch (Throwable error) {
            if (!loggedDesktopFlag) {
                loggedDesktopFlag = true;
                XposedBridge.log(TAG + ": desktop status flag failed: " + error);
            }
        }
    }

    private static void clearRectTop(Object rect, boolean typing) {
        if (rect == null) {
            return;
        }
        try {
            Field top = rect.getClass().getField("top");
            int value = top.getInt(rect);
            if (value <= 0) {
                return;
            }
            top.setInt(rect, 0);
            if (typing) {
                if (!loggedTypingTop) {
                    loggedTypingTop = true;
                    XposedBridge.log(TAG + ": typing status inset dropped " + value + "px");
                }
            } else if (!loggedDesktopInset) {
                loggedDesktopInset = true;
                XposedBridge.log(TAG + ": desktop status inset dropped " + value + "px");
            }
        } catch (Throwable ignored) {}
    }

    private static boolean ensureInsetTypes() {
        if (statusBarsType != 0 && imeType != 0) {
            return true;
        }
        try {
            Class<?> type = Class.forName("android.view.WindowInsets$Type");
            statusBarsType = ((Integer) type.getMethod("statusBars").invoke(null)).intValue();
            imeType = ((Integer) type.getMethod("ime").invoke(null)).intValue();
            return statusBarsType != 0 && imeType != 0;
        } catch (Throwable error) {
            statusBarsType = 1;
            imeType = 8;
            return true;
        }
    }

    private static boolean statusCollapsedNow() {
        if (insetWatching) {
            return collapseStatus;
        }
        Context context = anyContext();
        if (context != null) {
            collapseStatus = readStatusInset(context);
        }
        return collapseStatus;
    }

    private static Context anyContext() {
        if (systemUiContext != null) {
            return systemUiContext;
        }
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
            Object current = thread.getMethod("currentActivityThread").invoke(null);
            if (current != null) {
                Object system = current.getClass().getMethod("getSystemContext").invoke(current);
                if (system instanceof Context) {
                    return (Context) system;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void hookDesktopDrop(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "com.android.wm.shell.desktopmode.DesktopModeVisualIndicator", false, loader);
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                if ("calculateIndicatorType".equals(method.getName())) {
                    XposedBridge.hookMethod(method, MAXIMIZE_INSTEAD_OF_EXIT);
                    hooked++;
                } else if ("calculateFullscreenRegion".equals(method.getName())) {
                    XposedBridge.hookMethod(method, CLEAR_FULLSCREEN_REGION);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": desktop drop hooks " + hooked);
        } catch (Throwable error) {
            if (!loggedIndicatorMiss) {
                loggedIndicatorMiss = true;
                XposedBridge.log(TAG + ": could not hook desktop drop: " + error);
            }
        }
    }

    private static void hookCaptionButtons(ClassLoader loader) {
        try {
            Class<?> listener = Class.forName(
                    "com.android.wm.shell.windowdecor.DesktopModeTouchEventListener", false, loader);
            int hooked = 0;
            for (Method method : listener.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 1) {
                    continue;
                }
                if ("onClick".equals(method.getName())) {
                    XposedBridge.hookMethod(method, MAXIMIZE_ON_CLICK);
                    hooked++;
                } else if ("onLongClick".equals(method.getName())) {
                    XposedBridge.hookMethod(method, LONG_PRESS_FULLSCREEN);
                    hooked++;
                }
            }
            for (Method method : listener.getDeclaredMethods()) {
                if ("handleMotionEvent".equals(method.getName())
                        && method.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(method, MAXIMIZED_DRAG_RESTORE);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": caption hooks " + hooked);
            Class<?> immersive = Class.forName(
                    "com.android.wm.shell.desktopmode.DesktopImmersiveController", false, loader);
            for (Method method : immersive.getDeclaredMethods()) {
                if ("moveTaskToImmersive".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, MARK_IMMERSIVE_ENTER);
                }
            }
            Class<?> transaction = Class.forName(
                    "android.window.WindowContainerTransaction", false, loader);
            for (Method method : transaction.getDeclaredMethods()) {
                if ("setBounds".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(method, FORCE_IMMERSIVE_BOUNDS);
                }
            }
        } catch (Throwable error) {
            if (!loggedCaptionMiss) {
                loggedCaptionMiss = true;
                XposedBridge.log(TAG + ": could not hook caption buttons: " + error);
            }
        }
    }

    /**
     * A maximized window should leave maximize once the pointer travels past the
     * touch slop, then keep following the pointer as a floating window. Hiding the
     * status bar makes the shell skip that path, and the shell also requires the
     * drag to move downward. This restores the desktop gesture in any direction.
     */
    private static final ThreadLocal<Boolean> CLEARED_STATUS_HIDE = new ThreadLocal<Boolean>();

    /**
     * Hiding the status bar makes a full-size window fail the shell's maximized
     * check, and it also refuses caption drags on windows it does consider
     * maximized. Clear that flag for the duration of the gesture so a drag past
     * the touch slop restores the window and keeps moving it.
     */
    private static final XC_MethodHook MAXIMIZED_DRAG_RESTORE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            MotionEvent event = motionEvent(param.args);
            View view = param.args != null && param.args.length > 0 && param.args[0] instanceof View
                    ? (View) param.args[0] : null;
            if (event == null || view == null || !modificationsOn(view) || captionControl(view)
                    || touchOnCaptionButton(view, event) || blockDragForImmersive(param.thisObject)) {
                return;
            }
            if (clearStatusHideForDrag()) {
                CLEARED_STATUS_HIDE.set(Boolean.TRUE);
            }
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                return;
            }
            try {
                if (!XposedHelpers.getBooleanField(param.thisObject, "mDragMaximizeTaskAllowed")
                        || XposedHelpers.getBooleanField(param.thisObject, "mIsRestoreAnimRunning")) {
                    return;
                }
                Point down = (Point) XposedHelpers.getObjectField(param.thisObject, "mInputDownPoint");
                if (down == null) {
                    return;
                }
                int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                float dx = event.getRawX() - down.x;
                float dy = event.getRawY() - down.y;
                if (Math.hypot(dx, dy) <= slop) {
                    return;
                }
                down.set((int) event.getRawX(), (int) event.getRawY() - slop - 2);
            } catch (Throwable ignored) {}
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(CLEARED_STATUS_HIDE.get())) {
                CLEARED_STATUS_HIDE.set(null);
                restoreStatusHide();
            }
            MotionEvent event = motionEvent(param.args);
            View view = param.args != null && param.args.length > 0 && param.args[0] instanceof View
                    ? (View) param.args[0] : null;
            if (event == null || view == null || !modificationsOn(view) || captionControl(view)
                    || touchOnCaptionButton(view, event) || blockDragForImmersive(param.thisObject)) {
                return;
            }
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                return;
            }
            try {
                if (!listenerTaskMaximized(param.thisObject)) {
                    return;
                }
                Point down = (Point) XposedHelpers.getObjectField(param.thisObject, "mInputDownPoint");
                if (down != null) {
                    down.set((int) event.getRawX(), (int) event.getRawY());
                }
                XposedHelpers.setBooleanField(param.thisObject, "mIsRestoreAnimRunning", false);
                XposedHelpers.setBooleanField(param.thisObject, "mDragMaximizeTaskAllowed", true);
                Object positioner = XposedHelpers.getObjectField(param.thisObject, "mTaskPositioner");
                if (positioner != null) {
                    XposedHelpers.callMethod(positioner, "updateRestoreAnimationMotionEvent", event);
                }
                if (!loggedDragRestore) {
                    loggedDragRestore = true;
                    XposedBridge.log(TAG + ": maximized windows restore when the title bar is dragged");
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook MARK_IMMERSIVE_ENTER = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            ENTERING_IMMERSIVE.set(Boolean.TRUE);
            IMMERSIVE_BOUNDS.set(displayBounds(param.thisObject, param.args == null ? null : param.args[0]));
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            ENTERING_IMMERSIVE.set(null);
            IMMERSIVE_BOUNDS.set(null);
        }
    };

    /**
     * An empty bounds change is resolved as a maximized window. Immersive needs the
     * full display rectangle and the force-immersive flag, or it settles maximized.
     */
    private static final XC_MethodHook FORCE_IMMERSIVE_BOUNDS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Rect full = IMMERSIVE_BOUNDS.get();
            if (full == null || param.args == null || param.args.length < 2 || !(param.args[1] instanceof Rect)) {
                return;
            }
            Rect requested = (Rect) param.args[1];
            if (requested.isEmpty()) {
                requested.set(full);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(ENTERING_IMMERSIVE.get()) || param.args == null || param.args.length == 0) {
                return;
            }
            try {
                XposedHelpers.callMethod(param.thisObject, "setForceImmersiveChange", param.args[0]);
            } catch (Throwable ignored) {}
        }
    };

    private static Rect displayBounds(Object controller, Object task) {
        try {
            int displayId = task == null ? 0 : task.getClass().getField("displayId").getInt(task);
            Object displayController = field(controller, "displayController");
            if (displayController == null) {
                displayController = field(controller, "mDisplayController");
            }
            if (displayController == null) {
                return null;
            }
            Object layout = XposedHelpers.callMethod(displayController, "getDisplayLayout", displayId);
            if (layout == null) {
                return null;
            }
            int width = ((Integer) XposedHelpers.callMethod(layout, "width")).intValue();
            int height = ((Integer) XposedHelpers.callMethod(layout, "height")).intValue();
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new Rect(0, 0, width, height);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean clearStatusHideForDrag() {
        try {
            if (forceHideStatusField == null) {
                syncDesktopStatusHide();
            }
            if (forceHideStatusField != null && forceHideStatusField.getBoolean(null)) {
                forceHideStatusField.setBoolean(null, false);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void restoreStatusHide() {
        try {
            if (forceHideStatusField != null && statusCollapsedNow()) {
                forceHideStatusField.setBoolean(null, true);
            }
        } catch (Throwable ignored) {}
    }

    private static MotionEvent motionEvent(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof MotionEvent) {
                return (MotionEvent) args[i];
            }
        }
        return null;
    }

    private static boolean listenerTaskMaximized(Object listener) {
        Object finder = XposedHelpers.getObjectField(listener, "mWindowDecorationFinder");
        int taskId = XposedHelpers.getIntField(listener, "mTaskId");
        if (!(finder instanceof java.util.function.Function)) {
            return false;
        }
        Object decor = ((java.util.function.Function) finder).apply(Integer.valueOf(taskId));
        if (decor == null) {
            return false;
        }
        Object maximized = XposedHelpers.callMethod(decor, "isTaskMaximized");
        return Boolean.TRUE.equals(maximized);
    }

    private static final XC_MethodHook MAXIMIZE_ON_CLICK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            View view = captionView(param.args);
            if (view == null
                    || view.getId() != sysUiId(view.getContext(), IDX_MAXIMIZE_BUTTON)
                    || !modificationsOn(view)) {
                return;
            }
            try {
                if (!enterDesktopMaximize(param.thisObject)) {
                    return;
                }
                param.setResult(null);
                if (!loggedCaption) {
                    loggedCaption = true;
                    XposedBridge.log(TAG + ": maximize click stays inside DeX");
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook LONG_PRESS_FULLSCREEN = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            View view = captionView(param.args);
            if (view == null || !modificationsOn(view)) {
                return;
            }
            Context ctx = view.getContext();
            if (!ModConfig.getBoolean(ctx, ModConfig.KEY_HOLD_MAXIMIZE_FULLSCREEN, true)) {
                return;
            }
            int id = view.getId();
            if (id != sysUiId(ctx, IDX_MAXIMIZE_BUTTON)
                    && id != sysUiId(ctx, IDX_TOGGLE_FREEFORM)) {
                return;
            }
            try {
                if (!enterImmersive(param.thisObject)) {
                    return;
                }
                param.setResult(Boolean.TRUE);
                if (!loggedImmersive) {
                    loggedImmersive = true;
                    XposedBridge.log(TAG + ": long press fullscreens inside DeX");
                }
            } catch (Throwable ignored) {}
        }
    };

    private static View captionView(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof View)) {
            return null;
        }
        return (View) args[0];
    }

    private static boolean enterDesktopMaximize(Object listener) throws Exception {
        Object taskId = field(listener, "mTaskId");
        Object actions = field(listener, "mWindowDecorationActions");
        if (!(taskId instanceof Integer) || actions == null) {
            return false;
        }
        ClassLoader loader = listener.getClass().getClassLoader();
        Object source = enumValue(loader,
                "com.android.wm.shell.desktopmode.common.ToggleTaskSizeInteraction$AmbiguousSource",
                "HEADER_BUTTON");
        Object input = enumValue(loader,
                "com.android.wm.shell.desktopmode.DesktopModeEventLogger$Companion$InputMethod",
                "UNKNOWN", "TOUCH", "MOUSE");
        Method maximize = null;
        Class<?> type = actions.getClass();
        while (type != null && maximize == null) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if ("onMaximizeOrRestore".equals(method.getName())
                        && params.length == 3
                        && params[0] == int.class) {
                    maximize = method;
                    break;
                }
            }
            type = type.getSuperclass();
        }
        if (maximize == null || source == null) {
            return false;
        }
        maximize.setAccessible(true);
        maximize.invoke(actions, taskId, source, input);
        return true;
    }

    private static boolean enterImmersive(Object listener) throws Exception {
        Object taskId = field(listener, "mTaskId");
        Object finder = field(listener, "mWindowDecorationFinder");
        Object actions = field(listener, "mWindowDecorationActions");
        if (!(taskId instanceof Integer) || finder == null || actions == null) {
            return false;
        }
        Object decor = finder.getClass().getMethod("apply", Object.class).invoke(finder, taskId);
        if (decor == null) {
            return false;
        }
        Object task = decor.getClass().getMethod("getTaskInfo").invoke(decor);
        if (task == null) {
            return false;
        }
        Method immersive = null;
        Class<?> type = actions.getClass();
        while (type != null && immersive == null) {
            for (Method method : type.getDeclaredMethods()) {
                if ("onImmersiveOrRestore".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    immersive = method;
                    break;
                }
            }
            type = type.getSuperclass();
        }
        if (immersive == null) {
            return false;
        }
        immersive.setAccessible(true);
        immersiveGuardUntil = SystemClock.elapsedRealtime() + 1600L;
        immersive.invoke(actions, task);
        commitImmersive(decor, task);
        return true;
    }

    private static volatile long immersiveGuardUntil;

    /** Drag-to-restore must not pull an immersive window back down to the maximized size. */
    private static boolean blockDragForImmersive(Object listener) {
        if (SystemClock.elapsedRealtime() < immersiveGuardUntil) {
            return true;
        }
        try {
            Object finder = field(listener, "mWindowDecorationFinder");
            Object taskId = field(listener, "mTaskId");
            if (finder == null || !(taskId instanceof Integer)) {
                return false;
            }
            Object decor = finder.getClass().getMethod("apply", Object.class).invoke(finder, taskId);
            if (decor == null) {
                return false;
            }
            Object repos = field(decor, "desktopUserRepositories");
            if (repos == null) {
                repos = field(decor, "mDesktopUserRepositories");
            }
            if (repos == null) {
                return false;
            }
            Object repo = XposedHelpers.callMethod(repos, "getCurrent");
            Object immersive = XposedHelpers.callMethod(repo, "isTaskInFullImmersiveState", taskId);
            return Boolean.TRUE.equals(immersive);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean touchOnCaptionButton(View view, MotionEvent event) {
        if (view == null || event == null) {
            return false;
        }
        View root = view.getRootView();
        if (root == null) {
            return false;
        }
        Context ctx = root.getContext();
        int x = Math.round(event.getRawX());
        int y = Math.round(event.getRawY());
        int[] indices = new int[] {
                IDX_MAXIMIZE_BUTTON, IDX_TOGGLE_FREEFORM, IDX_TOGGLE_IMMERSIVE, IDX_FULLSCREEN_BUTTON,
                IDX_EXPAND_MENU_BUTTON, IDX_MINIMIZE_WINDOW, IDX_OPEN_MENU_BUTTON, IDX_CLOSE_WINDOW, IDX_APP_ICON
        };
        int[] location = new int[2];
        for (int i = 0; i < indices.length; i++) {
            if (hitVisibleView(root.findViewById(sysUiId(ctx, indices[i])), x, y, location)) {
                return true;
            }
        }
        return hitVisibleView(root.findViewWithTag(PIN_TAG), x, y, location)
                || hitVisibleView(root.findViewWithTag(BACK_TAG), x, y, location);
    }

    private static boolean hitVisibleView(View button, int x, int y, int[] location) {
        if (button == null || button.getVisibility() != View.VISIBLE) {
            return false;
        }
        button.getLocationOnScreen(location);
        return x >= location[0] && x < location[0] + button.getWidth()
                && y >= location[1] && y < location[1] + button.getHeight();
    }

    private static void commitImmersive(Object decor, Object task) {
        try {
            Rect full = displayBounds(decor, task);
            if (full == null) {
                full = displayBoundsFromContext(decor);
            }
            Object token = field(task, "token");
            Object organizer = field(decor, "mTaskOrganizer");
            if (full != null && token != null && organizer != null) {
                Class<?> wctClass = Class.forName(
                        "android.window.WindowContainerTransaction", false, decor.getClass().getClassLoader());
                Object wct = wctClass.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(wct, "setBounds", token, full);
                try {
                    XposedHelpers.callMethod(wct, "setForceImmersiveChange", token);
                } catch (Throwable ignored) {}
                XposedHelpers.callMethod(organizer, "applyTransaction", wct);
            }
            Object repos = field(decor, "desktopUserRepositories");
            if (repos == null) {
                repos = field(decor, "mDesktopUserRepositories");
            }
            if (repos != null) {
                Object repo = XposedHelpers.callMethod(repos, "getCurrent");
                int displayId = task.getClass().getField("displayId").getInt(task);
                int taskId = task.getClass().getField("taskId").getInt(task);
                XposedHelpers.callMethod(repo, "setTaskInFullImmersiveState",
                        Integer.valueOf(displayId), Integer.valueOf(taskId), Boolean.TRUE);
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": immersive commit failed: " + error);
        }
    }

    private static Rect displayBoundsFromContext(Object decor) {
        try {
            Context context = (Context) field(decor, "mContext");
            if (context == null) {
                context = systemUiContext;
            }
            if (context == null) {
                return null;
            }
            DisplayMetrics metrics = new DisplayMetrics();
            android.view.WindowManager windowManager =
                    (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
                return null;
            }
            return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object enumValue(ClassLoader loader, String className, String... names) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            if (!type.isEnum()) {
                return null;
            }
            for (int i = 0; i < names.length; i++) {
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object value = Enum.valueOf((Class) type, names[i]);
                    return value;
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void hookWindowPin(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration", false, loader);
            int hooked = 0;
            for (Method method : type.getDeclaredMethods()) {
                if ("relayout".equals(method.getName())) {
                    XposedBridge.hookMethod(method, ADD_PIN_BUTTON);
                    hooked++;
                }
            }
            Class<?> model = Class.forName(
                    "com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel", false, loader);
            for (Method method : model.getDeclaredMethods()) {
                if ("onFocusedTaskChanged".equals(method.getName())
                        && method.getParameterTypes().length >= 1) {
                    XposedBridge.hookMethod(method, RAISE_PINS_ON_FOCUS);
                    hooked++;
                } else if ("onTaskToFront".equals(method.getName())
                        && method.getParameterTypes().length >= 1) {
                    XposedBridge.hookMethod(method, RAISE_PINS_ON_FOCUS);
                    hooked++;
                } else if ("onTaskInfoChanged".equals(method.getName())
                        && method.getParameterTypes().length >= 1) {
                    XposedBridge.hookMethod(method, NOTE_TASK_INFO);
                    hooked++;
                } else if ("onTransitionFinished".equals(method.getName())) {
                    XposedBridge.hookMethod(method, RAISE_PINS_AFTER_TRANSITION);
                    hooked++;
                } else if ("onTaskVanished".equals(method.getName())
                        && method.getParameterTypes().length >= 1) {
                    XposedBridge.hookMethod(method, DROP_PIN_ON_VANISH);
                    hooked++;
                }
            }
            Class<?> txnClass = Class.forName(
                    "android.view.SurfaceControl$Transaction", false, loader);
            for (Method method : txnClass.getDeclaredMethods()) {
                if ("apply".equals(method.getName())) {
                    XposedBridge.hookMethod(method, STAMP_PIN_ON_APPLY);
                    hooked++;
                }
            }
            Class<?> captionButton = Class.forName(
                    "com.android.wm.shell.windowdecor.widget.CaptionButton", false, loader);
            for (Method method : captionButton.getDeclaredMethods()) {
                if ("setTaskFocusState".equals(method.getName())) {
                    XposedBridge.hookMethod(method, MATCH_PIN_COLOR);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": pin hooks " + hooked);
        } catch (Throwable error) {
            if (!loggedPinMiss) {
                loggedPinMiss = true;
                XposedBridge.log(TAG + ": could not hook window pin: " + error);
            }
        }
    }

    private static final XC_MethodHook MATCH_PIN_COLOR = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                View button = (View) param.thisObject;
                if (button.getId() != sysUiId(button.getContext(), IDX_MINIMIZE_WINDOW)
                        || !(button.getParent() instanceof ViewGroup)) {
                    return;
                }
                View pin = findPin((ViewGroup) button.getParent());
                if (pin instanceof ImageView) {
                    matchPinColor(button, (ImageView) pin);
                }
                View back = findTaggedInAncestors(button, BACK_TAG);
                if (back instanceof ImageView) {
                    matchPinColor(button, (ImageView) back);
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook ADD_PIN_BUTTON = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                prepareCaption(param.thisObject);
            } catch (Throwable error) {
                if (!loggedPinMiss) {
                    loggedPinMiss = true;
                    XposedBridge.log(TAG + ": pin button failed: " + error);
                }
            }
        }
    };

    private static final XC_MethodHook RAISE_PINS_ON_FOCUS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length == 0) {
                return;
            }
            try {
                noteFront(param.args[0]);
                if (PINNED_IDS.isEmpty()) {
                    return;
                }
                Integer id = taskId(param.args[0]);
                Object cover = id != null && PINNED_IDS.contains(id) ? null : surfaceFor(id);
                raisePinned(cover);
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook NOTE_TASK_INFO = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length == 0) {
                return;
            }
            Integer id = taskId(param.args[0]);
            if (id == null) {
                return;
            }
            TaskChrome chrome = TASK_CHROME.get(id);
            if (chrome != null) {
                chrome.info = param.args[0];
            }
        }
    };

    private static final XC_MethodHook RAISE_PINS_AFTER_TRANSITION = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (PINNED_IDS.isEmpty()) {
                return;
            }
            raisePinned(pinOrderCover);
        }
    };

    private static final XC_MethodHook STAMP_PIN_ON_APPLY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(PIN_STAMPING.get())) {
                return;
            }
            boolean pins = !PINNED_IDS.isEmpty();
            boolean faded = !WINDOW_ALPHA.isEmpty();
            if (!pins && !faded) {
                return;
            }
            PIN_STAMPING.set(Boolean.TRUE);
            try {
                if (pins) {
                    stampPin(param.thisObject, pinOrderCover);
                }
                if (faded) {
                    stampWindowAlpha(param.thisObject);
                }
            } catch (Throwable ignored) {
            } finally {
                PIN_STAMPING.set(Boolean.FALSE);
            }
        }
    };

    private static final XC_MethodHook DROP_PIN_ON_VANISH = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length == 0) {
                return;
            }
            Integer id = taskId(param.args[0]);
            if (id != null) {
                PINNED_IDS.remove(id);
                TASK_CHROME.remove(id);
                WINDOW_ALPHA.remove(id);
                ALPHA_MANUAL.remove(id);
                if (!PINNED_IDS.isEmpty()) {
                    applyPinOrder(pinOrderCover);
                }
            }
        }
    };

    private static void prepareCaption(Object decor) {
        Object result = field(decor, "mResult");
        Object rootObject = result == null ? null : field(result, "mRootView");
        if (!(rootObject instanceof View)) {
            return;
        }
        View root = (View) rootObject;
        Context ctx = root.getContext();
        boolean on = modificationsOn(root);
        releaseStuckAlwaysOnTop(decor);
        addBackButton(decor, root, on);
        settleMaximizeButton(decor, root, on);
        hideFullscreenButton(root, on && ModConfig.getBoolean(ctx, ModConfig.KEY_HIDE_FULLSCREEN_BTN, true));
        hideAppChevron(root, on && ModConfig.getBoolean(ctx, ModConfig.KEY_HIDE_APP_CHEVRON, true));
        fitOpenMenu(root);
        rememberChrome(decor);
        applyDefaultOpacity(decor, ctx, on);
        View minimize = findSysUiView(root, IDX_MINIMIZE_WINDOW);
        if (minimize == null || !(minimize.getParent() instanceof ViewGroup)) {
            if (on) {
                raisePinned(surfaceFor(taskId(field(decor, "mTaskInfo"))));
            } else {
                resetWindowAlphas();
            }
            return;
        }
        ViewGroup group = (ViewGroup) minimize.getParent();
        View pin = findPin(group);
        boolean wantPin = on && ModConfig.getBoolean(ctx, ModConfig.KEY_PIN_BUTTON_ENABLED, true);
        if (!wantPin) {
            PINNED_IDS.clear();
            if (!on) {
                resetWindowAlphas();
            }
            if (pin != null) {
                pin.setVisibility(View.GONE);
            }
            return;
        }
        if (pin == null) {
            pin = new ImageView(minimize.getContext());
            pin.setTag(PIN_TAG);
            pin.setMinimumWidth(0);
            pin.setMinimumHeight(0);
            pin.setPadding(0, 0, 0, 0);
            int index = group.indexOfChild(minimize);
            if (index < 0) {
                group.addView(pin);
            } else {
                group.addView(pin, index);
            }
            copyPinSize(minimize, pin);
            copyBackground(minimize, pin);
            if (!loggedPin) {
                loggedPin = true;
                XposedBridge.log(TAG + ": pin button added");
            }
        }
        PIN_DECORS.put(pin, new WeakReference<Object>(decor));
        pin.setVisibility(View.VISIBLE);
        Integer id = taskId(field(decor, "mTaskInfo"));
        boolean pinned = id != null && PINNED_IDS.contains(id);
        paintPin(pin, pinned);
        if (pin instanceof ImageView) {
            matchPinColor(minimize, (ImageView) pin);
        }
        pin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                WeakReference<Object> ref = PIN_DECORS.get(view);
                Object target = ref == null ? null : ref.get();
                if (target != null) {
                    togglePin(target, view);
                }
            }
        });
        raisePinned(surfaceFor(id));
    }

    private static void settleMaximizeButton(Object decor, View root, boolean on) {
        View maximize = findSysUiView(root, IDX_MAXIMIZE_BUTTON);
        View freeform = findSysUiView(root, IDX_TOGGLE_FREEFORM);
        boolean freeformShown = on && freeform != null && freeform.getVisibility() == View.VISIBLE;
        if (maximize != null && Boolean.TRUE.equals(HIDDEN_MAXIMIZE.get(maximize)) && !freeformShown) {
            maximize.setVisibility(View.VISIBLE);
            HIDDEN_MAXIMIZE.remove(maximize);
        }
        if (freeformShown && maximize != null && maximize.getVisibility() != View.GONE) {
            maximize.setVisibility(View.GONE);
            HIDDEN_MAXIMIZE.put(maximize, Boolean.TRUE);
            if (!loggedMaximizeHide) {
                loggedMaximizeHide = true;
                XposedBridge.log(TAG + ": hid the extra maximize button");
            }
        }
        View holdTarget = freeformShown ? freeform : maximize;
        if (!on || holdTarget == null
                || !ModConfig.getBoolean(root.getContext(), ModConfig.KEY_HOLD_MAXIMIZE_FULLSCREEN, true)) {
            return;
        }
        Object listener = field(decor, "mOnCaptionLongClickListener");
        if (listener == null) {
            return;
        }
        try {
            Method set = holdTarget.getClass().getMethod(
                    "setOnLongClickListener",
                    Class.forName("android.view.View$OnLongClickListener"));
            set.invoke(holdTarget, listener);
        } catch (Throwable ignored) {}
    }

    private static void hideFullscreenButton(View root, boolean on) {
        View immersive = findSysUiView(root, IDX_TOGGLE_IMMERSIVE);
        View fullscreen = findSysUiView(root, IDX_FULLSCREEN_BUTTON);
        if (!on) {
            // Leave Samsung's own fullscreen and exit buttons alone. Forcing both
            // visible puts an extra control on the title bar and breaks fullscreen.
            releaseFullscreenHide(immersive);
            releaseFullscreenHide(fullscreen);
            return;
        }
        View freeform = findSysUiView(root, IDX_TOGGLE_FREEFORM);
        boolean freeformShown = freeform != null && freeform.getVisibility() == View.VISIBLE;
        if (!freeformShown) {
            showFullscreenRestore(immersive);
        } else {
            hideOneFullscreen(immersive, true, true);
        }
        hideOneFullscreen(fullscreen, true, false);
    }

    private static void hideAppChevron(View root, boolean on) {
        View chevron = findSysUiView(root, IDX_EXPAND_MENU_BUTTON);
        if (chevron == null) {
            return;
        }
        if (!on) {
            if (Boolean.TRUE.equals(HIDDEN_CHEVRON.get(chevron))) {
                chevron.setVisibility(View.VISIBLE);
                HIDDEN_CHEVRON.remove(chevron);
            }
            return;
        }
        if (chevron.getVisibility() == View.GONE) {
            return;
        }
        chevron.setVisibility(View.GONE);
        HIDDEN_CHEVRON.put(chevron, Boolean.TRUE);
    }

    private static void hookAppMenu(ClassLoader loader) {
        try {
            Class<?> policy = Class.forName(
                    "com.android.wm.shell.windowdecor.policy.DexFreeformPopupButtonPolicy",
                    false, loader);
            int hooked = 0;
            for (Method method : policy.getDeclaredMethods()) {
                if ("setupCaptionButtonState".equals(method.getName())) {
                    XposedBridge.hookMethod(method, REWRITE_APP_MENU);
                    hooked++;
                } else if ("calculatePopupHeight".equals(method.getName())) {
                    XposedBridge.hookMethod(method, GROW_MENU_FOR_SLIDER);
                    hooked++;
                }
            }
            XposedBridge.log(TAG + ": app menu hooks " + hooked);
        } catch (Throwable error) {
            if (!loggedMenu) {
                loggedMenu = true;
                XposedBridge.log(TAG + ": could not hook app menu: " + error);
            }
        }
    }

    private static final XC_MethodHook REWRITE_APP_MENU = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                ViewGroup menu = menuRoot(param);
                if (menu == null) {
                    return;
                }
                if (!modificationsOn(menu)) {
                    removeChildByTag(menu, SLIDER_TAG);
                    removeChildByTag(menu, TOOLS_TAG);
                    return;
                }
                Context ctx = menu.getContext();
                if (!ModConfig.getBoolean(ctx, ModConfig.KEY_HEADER_MENU_CUSTOM_ENABLED, true)) {
                    removeChildByTag(menu, SLIDER_TAG);
                    removeChildByTag(menu, TOOLS_TAG);
                    return;
                }
                View removeBtn = findSysUiView(menu, IDX_REMOVE_FROM_WORKSPACE);
                if (ModConfig.getBoolean(ctx, ModConfig.KEY_ADD_APP_INFO_BTN, true)) {
                    rewriteAppInfo(removeBtn, param.thisObject);
                }
                if (ModConfig.getBoolean(ctx, ModConfig.KEY_ADD_TRANSPARENCY_SLIDER, true)) {
                    ensureOpacitySlider(menu, param.thisObject);
                } else {
                    removeChildByTag(menu, SLIDER_TAG);
                }
                if (ModConfig.getBoolean(ctx, ModConfig.KEY_ADD_EXTRA_WINDOW_TOOLS, true)) {
                    ensureExtraTools(menu, param.thisObject);
                } else {
                    removeChildByTag(menu, TOOLS_TAG);
                }
            } catch (Throwable error) {
                if (!loggedMenu) {
                    loggedMenu = true;
                    XposedBridge.log(TAG + ": app menu rewrite failed: " + error);
                }
            }
        }
    };

    private static void removeChildByTag(ViewGroup parent, String tag) {
        View existing = findChildByTag(parent, tag);
        if (existing != null) {
            parent.removeView(existing);
        }
    }

    private static final XC_MethodHook GROW_MENU_FOR_SLIDER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object root = field(param.thisObject, "mRootView");
                if (!(root instanceof ViewGroup)) {
                    return;
                }
                ViewGroup vg = (ViewGroup) root;
                int extra = 0;
                View slider = findChildByTag(vg, SLIDER_TAG);
                if (slider != null) {
                    extra += sliderBlockHeight(slider, param.thisObject);
                }
                View tools = findChildByTag(vg, TOOLS_TAG);
                if (tools != null) {
                    extra += sliderBlockHeight(tools, param.thisObject);
                }
                if (extra <= 0) {
                    return;
                }
                Object result = param.getResult();
                int base = result instanceof Integer ? ((Integer) result).intValue() : 0;
                param.setResult(Integer.valueOf(base + extra));
            } catch (Throwable ignored) {}
        }
    };

    private static ViewGroup menuRoot(XC_MethodHook.MethodHookParam param) {
        if (param.args != null) {
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                if (arg instanceof ViewGroup
                        && findSysUiView((View) arg, IDX_REMOVE_FROM_WORKSPACE) != null) {
                    return (ViewGroup) arg;
                }
            }
        }
        Object root = field(param.thisObject, "mRootView");
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    private static void rewriteAppInfo(View button, final Object policy) {
        if (button == null) {
            return;
        }
        Object text = field(button, "mTextView");
        if (text instanceof TextView) {
            ((TextView) text).setText("App info");
        }
        button.setContentDescription("App info");
        Object icon = field(button, "mIconView");
        if (icon instanceof ImageView) {
            ((ImageView) icon).setImageBitmap(infoBitmap());
        }
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAppInfo(policy, view);
            }
        });
    }

    private static void ensureOpacitySlider(ViewGroup menu, final Object policy) {
        if (findChildByTag(menu, SLIDER_TAG) != null) {
            return;
        }
        Context context = menu.getContext();
        if (context == null) {
            return;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag(SLIDER_TAG);
        int pad = px(context, 12);
        row.setPadding(pad, px(context, 8), pad, px(context, 4));
        TextView label = new TextView(context);
        label.setText("Transparency");
        label.setTextColor(menuTextColor(findSysUiView(menu, IDX_REMOVE_FROM_WORKSPACE)));
        row.addView(label);
        SeekBar bar = new SeekBar(context);
        bar.setMax(100);
        bar.setContentDescription("Window transparency");
        Integer id = taskId(field(policy, "mTaskInfo"));
        Float stored = id == null ? null : WINDOW_ALPHA.get(id);
        bar.setProgress(stored == null ? 100 : progressForAlpha(stored.floatValue()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                Integer task = taskId(field(policy, "mTaskInfo"));
                if (task == null) {
                    return;
                }
                ALPHA_MANUAL.add(task);
                setWindowAlpha(task.intValue(), alphaForProgress(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = px(context, 4);
        row.setLayoutParams(params);
        menu.addView(row, 0);
        if (!loggedMenu) {
            loggedMenu = true;
            XposedBridge.log(TAG + ": app menu shows transparency and app info");
        }
    }

    private static void ensureExtraTools(final ViewGroup menu, final Object policy) {
        if (findChildByTag(menu, TOOLS_TAG) != null) {
            return;
        }
        final Context context = menu.getContext();
        if (context == null) {
            return;
        }
        int textColor = menuTextColor(findSysUiView(menu, IDX_REMOVE_FROM_WORKSPACE));
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setTag(TOOLS_TAG);
        int pad = px(context, 12);
        row.setPadding(pad, px(context, 2), pad, px(context, 6));

        Button centerBtn = createPillToolButton(context, "Center", textColor, dm);
        centerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                centerTaskWindow(policy, context);
            }
        });

        Integer tid = taskId(field(policy, "mTaskInfo"));
        final TaskChrome chrome = tid == null ? null : TASK_CHROME.get(tid);
        final View decorRoot = chrome != null && chrome.host != null
                ? (View) field(field(chrome.host, "mResult"), "mRootView")
                : null;
        boolean initKeep = decorRoot != null && decorRoot.getKeepScreenOn();
        final Button keepAwakeBtn = createPillToolButton(
                context, initKeep ? "Keep Awake: ON" : "Keep Awake", textColor, dm);
        keepAwakeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (decorRoot != null) {
                    boolean next = !decorRoot.getKeepScreenOn();
                    decorRoot.setKeepScreenOn(next);
                    keepAwakeBtn.setText(next ? "Keep Awake: ON" : "Keep Awake");
                    Toast.makeText(context, next ? "Keep Screen Awake enabled" : "Keep Screen Awake disabled", Toast.LENGTH_SHORT).show();
                }
            }
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = px(context, 6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(centerBtn, lp1);
        row.addView(keepAwakeBtn, lp2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = px(context, 4);
        row.setLayoutParams(rowLp);
        int idx = findChildByTag(menu, SLIDER_TAG) != null ? 1 : 0;
        menu.addView(row, idx);
    }

    private static Button createPillToolButton(Context ctx, String title, int textColor, DisplayMetrics dm) {
        Button b = new Button(ctx);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        int padH = px(ctx, 8);
        int padV = px(ctx, 5);
        b.setPadding(padH, padV, padH, padV);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(ctx, 8));
        bg.setColor((textColor & 0x00FFFFFF) | 0x22000000);
        b.setBackground(bg);
        return b;
    }

    private static void centerTaskWindow(Object policy, Context ctx) {
        try {
            Object taskInfo = field(policy, "mTaskInfo");
            Integer id = taskId(taskInfo);
            closeAppMenu(id);
            if (taskInfo == null || id == null) return;
            TaskChrome chrome = TASK_CHROME.get(id);
            if (chrome == null || chrome.host == null) return;
            Object organizer = field(chrome.host, "mTaskOrganizer");
            Object token = field(taskInfo, "token");
            if (organizer == null || token == null) return;
            Object cfg = field(taskInfo, "configuration");
            Object winCfg = field(cfg, "windowConfiguration");
            Rect bounds = (Rect) XposedHelpers.callMethod(winCfg, "getBounds");
            if (bounds == null || bounds.isEmpty()) return;

            int screenW = 0;
            int screenH = 0;
            int stableTop = 0;
            int stableBottom = 0;
            try {
                Object displayController = field(chrome.host, "mDisplayController");
                Integer displayIdObj = (Integer) field(taskInfo, "displayId");
                int displayId = displayIdObj != null ? displayIdObj.intValue() : 0;
                if (displayController != null) {
                    Object displayLayout = XposedHelpers.callMethod(displayController, "getDisplayLayout", displayId);
                    if (displayLayout != null) {
                        screenW = ((Integer) XposedHelpers.callMethod(displayLayout, "width")).intValue();
                        screenH = ((Integer) XposedHelpers.callMethod(displayLayout, "height")).intValue();
                        try {
                            Rect stableBounds = new Rect();
                            XposedHelpers.callMethod(displayLayout, "getStableBounds", stableBounds);
                            if (!stableBounds.isEmpty()) {
                                stableTop = stableBounds.top;
                                stableBottom = Math.max(0, screenH - stableBounds.bottom);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            if (screenW <= 0 || screenH <= 0) {
                try {
                    Context hostCtx = (Context) field(chrome.host, "mContext");
                    if (hostCtx == null) hostCtx = systemUiContext;
                    if (hostCtx == null) hostCtx = ctx;
                    android.view.WindowManager wm = (android.view.WindowManager) hostCtx.getSystemService(Context.WINDOW_SERVICE);
                    DisplayMetrics realDm = new DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(realDm);
                    screenW = realDm.widthPixels;
                    screenH = realDm.heightPixels;
                } catch (Throwable ignored) {}
            }
            if (screenW <= 0 || screenH <= 0) return;

            int w = bounds.width();
            int h = bounds.height();
            int usableH = Math.max(h, screenH - stableTop - stableBottom);
            int left = Math.max(0, (screenW - w) / 2);
            int top = Math.max(stableTop, stableTop + (usableH - h) / 2);
            Rect centered = new Rect(left, top, left + w, top + h);

            if (surfaceValid(chrome.task)) {
                try {
                    Object txn = newTransaction(chrome.task);
                    if (txn != null) {
                        Class<?> surfaceClass = Class.forName(
                                "android.view.SurfaceControl", false, chrome.task.getClass().getClassLoader());
                        txn.getClass().getMethod("setPosition", surfaceClass, float.class, float.class)
                                .invoke(txn, chrome.task, Float.valueOf((float) left), Float.valueOf((float) top));
                        txn.getClass().getMethod("apply").invoke(txn);
                    }
                } catch (Throwable ignored) {}
            }

            Class<?> wctClass = Class.forName(
                    "android.window.WindowContainerTransaction", false, chrome.host.getClass().getClassLoader());
            Object wct = wctClass.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(wct, "setBounds", token, centered);
            try {
                XposedHelpers.callMethod(wct, "reorder", token, true);
            } catch (Throwable ignored) {}
            XposedHelpers.callMethod(organizer, "applyTransaction", wct);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": centerTaskWindow failed: " + t);
        }
    }

    private static View findChildByTag(ViewGroup menu, String tag) {
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (child != null && tag.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    private static int menuTextColor(View button) {
        Object text = button == null ? null : field(button, "mTextView");
        if (text != null) {
            try {
                Object color = text.getClass().getMethod("getCurrentTextColor").invoke(text);
                if (color instanceof Integer) {
                    return ((Integer) color).intValue();
                }
            } catch (Throwable ignored) {}
        }
        return 0xFFFFFFFF;
    }

    private static int sliderBlockHeight(View slider, Object policy) {
        Object width = field(policy, "mPopupWidth");
        int popupWidth = width instanceof Integer ? ((Integer) width).intValue() : 0;
        int measured = measuredHeight(slider, popupWidth);
        if (measured <= 0) {
            Context context = slider.getContext();
            measured = context == null ? 96 : px(context, 72);
        }
        int margin = 0;
        ViewGroup.LayoutParams params = slider.getLayoutParams();
        if (params != null) {
            try {
                margin = params.getClass().getField("bottomMargin").getInt(params);
            } catch (Throwable ignored) {}
        }
        return measured + Math.max(margin, 0);
    }

    private static int measuredHeight(View view, int width) {
        try {
            int widthSpec = width > 0 ? (width & 0x3fffffff) | 0x40000000 : 0;
            view.getClass().getMethod("measure", int.class, int.class)
                    .invoke(view, Integer.valueOf(widthSpec), Integer.valueOf(0));
            Object height = view.getClass().getMethod("getMeasuredHeight").invoke(view);
            if (height instanceof Integer) {
                return ((Integer) height).intValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void openAppInfo(Object policy, View view) {
        try {
            Object info = field(policy, "mTaskInfo");
            Integer id = taskId(info);
            closeAppMenu(id);
            String pkg = taskPackage(info);
            if (pkg == null) {
                return;
            }
            Context context = view == null ? null : view.getContext();
            if (!startAppDetails(context, pkg)) {
                Object fallback = field(policy, "mContext");
                Context second = fallback instanceof Context ? (Context) fallback : systemUiContext;
                if (second != context) {
                    startAppDetails(second, pkg);
                }
            }
            if (!loggedAppInfo) {
                loggedAppInfo = true;
                XposedBridge.log(TAG + ": app info opened");
            }
        } catch (Throwable ignored) {}
    }

    private static void closeAppMenu(Integer id) {
        if (id == null) {
            return;
        }
        TaskChrome chrome = TASK_CHROME.get(id);
        Object host = chrome == null ? null : chrome.host;
        if (host == null) {
            return;
        }
        Class<?> type = host.getClass();
        while (type != null) {
            try {
                Method close = type.getDeclaredMethod("closeHandleMenu");
                close.setAccessible(true);
                close.invoke(host);
                return;
            } catch (NoSuchMethodException missing) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                return;
            }
        }
    }

    private static String taskPackage(Object taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        String[] names = new String[] {"topActivity", "baseActivity", "realActivity", "origActivity"};
        for (int i = 0; i < names.length; i++) {
            String pkg = packageName(field(taskInfo, names[i]));
            if (pkg != null) {
                return pkg;
            }
        }
        Object intent = field(taskInfo, "baseIntent");
        if (intent != null) {
            try {
                Object component = intent.getClass().getMethod("getComponent").invoke(intent);
                String pkg = packageName(component);
                if (pkg != null) {
                    return pkg;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String packageName(Object component) {
        if (component == null) {
            return null;
        }
        try {
            Object pkg = component.getClass().getMethod("getPackageName").invoke(component);
            if (pkg instanceof String && ((String) pkg).length() > 0) {
                return (String) pkg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean startAppDetails(Context context, String pkg) {
        if (context == null || pkg == null) {
            return false;
        }
        try {
            Class<?> intentClass = Class.forName("android.content.Intent");
            Object intent = intentClass.getConstructor(String.class).newInstance(
                    "android.settings.APPLICATION_DETAILS_SETTINGS");
            Class<?> uriType = Class.forName("android.net.Uri");
            Object uri = uriType.getMethod("parse", String.class).invoke(null, "package:" + pkg);
            intentClass.getMethod("setData", uriType).invoke(intent, uri);
            intentClass.getMethod("addFlags", int.class).invoke(intent, Integer.valueOf(0x10000000));
            context.getClass().getMethod("startActivity", intentClass).invoke(context, intent);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static float alphaForProgress(int progress) {
        if (progress < 0) progress = 0;
        else if (progress > 100) progress = 100;
        return MIN_WINDOW_ALPHA + (1f - MIN_WINDOW_ALPHA) * (progress / 100f);
    }

    private static int progressForAlpha(float alpha) {
        float span = 1f - MIN_WINDOW_ALPHA;
        float t = span <= 0f ? 1f : (alpha - MIN_WINDOW_ALPHA) / span;
        if (t < 0f) t = 0f;
        else if (t > 1f) t = 1f;
        return Math.round(t * 100f);
    }

    /** Uses Default Window Opacity for windows the user has not adjusted from the menu. */
    private static void applyDefaultOpacity(Object decor, Context ctx, boolean on) {
        Integer id = taskId(field(decor, "mTaskInfo"));
        if (!on || id == null || ALPHA_MANUAL.contains(id)) {
            return;
        }
        int percent = ModConfig.getInt(ctx, ModConfig.KEY_DEFAULT_WINDOW_OPACITY, 100);
        if (percent > 100) {
            percent = 100;
        } else if (percent < 30) {
            percent = 30;
        }
        float alpha = percent / 100f;
        Float current = WINDOW_ALPHA.get(id);
        if (percent >= 100) {
            if (current != null) {
                setWindowAlpha(id.intValue(), 1f);
            }
            return;
        }
        if (current != null && Math.abs(current.floatValue() - alpha) < 0.01f) {
            return;
        }
        setWindowAlpha(id.intValue(), alpha);
    }

    private static void applyDefaultOpacityToKnown() {
        if (systemUiContext == null) {
            return;
        }
        if (!modificationsOn(null)) {
            resetWindowAlphas();
            return;
        }
        ArrayList<TaskChrome> windows = new ArrayList<TaskChrome>();
        synchronized (TASK_CHROME) {
            windows.addAll(TASK_CHROME.values());
        }
        for (int i = 0; i < windows.size(); i++) {
            TaskChrome chrome = windows.get(i);
            if (chrome == null || chrome.host == null) {
                continue;
            }
            applyDefaultOpacity(chrome.host, systemUiContext, true);
        }
    }

    private static void setWindowAlpha(int taskId, float alpha) {
        Integer key = Integer.valueOf(taskId);
        if (alpha >= 0.999f) {
            WINDOW_ALPHA.remove(key);
            alpha = 1f;
        } else {
            if (alpha < MIN_WINDOW_ALPHA) {
                alpha = MIN_WINDOW_ALPHA;
            }
            WINDOW_ALPHA.put(key, Float.valueOf(alpha));
        }
        TaskChrome chrome = TASK_CHROME.get(key);
        if (chrome == null || !surfaceValid(chrome.task)) {
            return;
        }
        try {
            Object txn = newTransaction(chrome.task);
            if (txn == null || !ensureAlpha(txn)) {
                return;
            }
            PIN_STAMPING.set(Boolean.TRUE);
            try {
                alphaMethod.invoke(txn, chrome.task, Float.valueOf(alpha));
                txn.getClass().getMethod("apply").invoke(txn);
            } finally {
                PIN_STAMPING.set(Boolean.FALSE);
            }
        } catch (Throwable error) {
            PIN_STAMPING.set(Boolean.FALSE);
        }
    }

    private static void stampWindowAlpha(Object txn) {
        if (txn == null || WINDOW_ALPHA.isEmpty() || !ensureAlpha(txn)) {
            return;
        }
        ArrayList<Integer> ids = new ArrayList<Integer>();
        ArrayList<Float> values = new ArrayList<Float>();
        synchronized (WINDOW_ALPHA) {
            for (Map.Entry<Integer, Float> entry : WINDOW_ALPHA.entrySet()) {
                ids.add(entry.getKey());
                values.add(entry.getValue());
            }
        }
        ArrayList<TaskChrome> windows = new ArrayList<TaskChrome>();
        synchronized (TASK_CHROME) {
            windows.addAll(TASK_CHROME.values());
        }
        try {
            for (int i = 0; i < ids.size(); i++) {
                Object surface = null;
                int id = ids.get(i).intValue();
                for (int j = 0; j < windows.size(); j++) {
                    TaskChrome chrome = windows.get(j);
                    if (chrome.id == id && surfaceValid(chrome.task)) {
                        surface = chrome.task;
                        break;
                    }
                }
                if (surface != null) {
                    alphaMethod.invoke(txn, surface, values.get(i));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void resetWindowAlphas() {
        if (WINDOW_ALPHA.isEmpty()) {
            return;
        }
        ArrayList<Integer> ids = new ArrayList<Integer>();
        synchronized (WINDOW_ALPHA) {
            ids.addAll(WINDOW_ALPHA.keySet());
            WINDOW_ALPHA.clear();
        }
        ALPHA_MANUAL.clear();
        for (int i = 0; i < ids.size(); i++) {
            setWindowAlpha(ids.get(i).intValue(), 1f);
        }
    }

    private static boolean ensureAlpha(Object txn) {
        if (alphaMethod != null) {
            return true;
        }
        try {
            ClassLoader loader = txn.getClass().getClassLoader();
            Class<?> surface = Class.forName("android.view.SurfaceControl", false, loader);
            alphaMethod = txn.getClass().getMethod("setAlpha", surface, float.class);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static int px(Context context, int dp) {
        float density = 2f;
        try {
            Object resources = context.getClass().getMethod("getResources").invoke(context);
            Object metrics = resources.getClass().getMethod("getDisplayMetrics").invoke(resources);
            density = metrics.getClass().getField("density").getFloat(metrics);
        } catch (Throwable ignored) {}
        if (density < 1f) {
            density = 1f;
        }
        return Math.round(dp * density);
    }

    private static Bitmap infoBitmap() {
        if (infoIcon != null) {
            return infoIcon;
        }
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.08f);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.30f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(size / 2f, size * 0.38f, size * 0.055f, paint);
        paint.setStrokeWidth(size * 0.09f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(size / 2f, size * 0.50f, size / 2f, size * 0.68f, paint);
        infoIcon = bitmap;
        return bitmap;
    }

    private static void showFullscreenRestore(View button) {
        if (button == null) {
            return;
        }
        HIDDEN_FULLSCREEN.remove(button);
        if (button.getVisibility() != View.VISIBLE) {
            button.setVisibility(View.VISIBLE);
        }
    }

    private static void releaseFullscreenHide(View button) {
        if (button == null || !Boolean.TRUE.equals(HIDDEN_FULLSCREEN.get(button))) {
            return;
        }
        button.setVisibility(View.VISIBLE);
        HIDDEN_FULLSCREEN.remove(button);
    }

    private static void hideOneFullscreen(View button, boolean on, boolean log) {
        if (button == null || !on) {
            return;
        }
        if (button.getVisibility() == View.GONE) {
            return;
        }
        button.setVisibility(View.GONE);
        HIDDEN_FULLSCREEN.put(button, Boolean.TRUE);
        if (log && !loggedFullscreenHide) {
            loggedFullscreenHide = true;
            XposedBridge.log(TAG + ": hid the fullscreen button");
        }
    }

    /** Caption buttons must keep their own clicks. A drag-to-restore on them turns a long-press into the maximize animation. */
    private static boolean captionControl(View view) {
        if (PIN_TAG.equals(view.getTag()) || BACK_TAG.equals(view.getTag())) {
            return true;
        }
        int id = view.getId();
        Context ctx = view.getContext();
        if (id == sysUiId(ctx, IDX_MAXIMIZE_BUTTON) || id == sysUiId(ctx, IDX_TOGGLE_FREEFORM)
                || id == sysUiId(ctx, IDX_TOGGLE_IMMERSIVE) || id == sysUiId(ctx, IDX_FULLSCREEN_BUTTON)
                || id == sysUiId(ctx, IDX_EXPAND_MENU_BUTTON) || id == sysUiId(ctx, IDX_MINIMIZE_WINDOW)
                || id == sysUiId(ctx, IDX_OPEN_MENU_BUTTON) || id == sysUiId(ctx, IDX_CLOSE_WINDOW)
                || id == sysUiId(ctx, IDX_APP_ICON)) {
            return true;
        }
        String name = view.getClass().getName();
        return name.indexOf("CaptionButton") >= 0;
    }

    private static void addBackButton(Object decor, View root, boolean on) {
        View icon = findSysUiView(root, IDX_APP_ICON);
        View menu = findSysUiView(root, IDX_OPEN_MENU_BUTTON);
        View anchor = menu != null ? menu : icon;
        if (!(anchor != null && anchor.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) anchor.getParent();
        View back = findTagged(group, BACK_TAG);
        if (!on) {
            if (back != null) {
                back.setVisibility(View.GONE);
            }
            return;
        }
        if (back == null) {
            ImageView created = new ImageView(anchor.getContext());
            created.setTag(BACK_TAG);
            created.setScaleType(ImageView.ScaleType.CENTER);
            created.setMinimumWidth(0);
            created.setMinimumHeight(0);
            created.setClickable(true);
            created.setFocusable(false);
            int index = group.indexOfChild(anchor);
            group.addView(created, index < 0 ? 0 : index);
            placeBackButton(icon != null ? icon : anchor, created);
            View minimize = findSysUiView(root, IDX_MINIMIZE_WINDOW);
            if (minimize != null) {
                copyBackground(minimize, created);
                matchPinColor(minimize, created);
            }
            created.setImageBitmap(backBitmap());
            created.setContentDescription("Back");
            setPinHover(created, "Back");
            back = created;
            if (!loggedBack) {
                loggedBack = true;
                XposedBridge.log(TAG + ": back button added");
            }
        } else {
            placeBackButton(icon != null ? icon : anchor, back);
        }
        BACK_DECORS.put(back, new WeakReference<Object>(decor));
        back.setVisibility(View.VISIBLE);
        if (back instanceof ImageView) {
            back.setAlpha(1f);
            ((ImageView) back).setImageAlpha(255);
        }
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                WeakReference<Object> ref = BACK_DECORS.get(view);
                Object target = ref == null ? null : ref.get();
                if (target == null) {
                    return;
                }
                sendBackToWindow(target, view);
            }
        });
    }

    /**
     * The menu chip keeps its click listener. Its black plate was wider than the round
     * icon and covered the first tab, so the plate is removed and the control is squared
     * to the icon height.
     */
    private static void fitOpenMenu(View root) {
        View menu = findSysUiView(root, IDX_OPEN_MENU_BUTTON);
        final View icon = findSysUiView(root, IDX_APP_ICON);
        if (!(menu instanceof ViewGroup) || icon == null) {
            return;
        }
        final ViewGroup group = (ViewGroup) menu;
        stripChip(group, icon);
        if (MENU_SHRUNK.containsKey(group)) {
            return;
        }
        MENU_SHRUNK.put(group, Boolean.TRUE);
        group.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                stripChip(group, icon);
            }
        });
    }

    private static void stripChip(ViewGroup group, View icon) {
        group.setMinimumWidth(0);
        group.setPadding(0, 0, 0, 0);
        group.setTouchDelegate(null);
        group.setBackground(null);
        icon.setBackground(null);
        icon.setPadding(0, 0, 0, 0);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == icon || containsView(child, icon)) {
                continue;
            }
            if (child.getVisibility() != View.GONE) {
                child.setVisibility(View.GONE);
            }
        }
        clearHorizontalMargins(icon);
        int side = icon.getHeight();
        if (side <= 0 && icon.getLayoutParams() != null && icon.getLayoutParams().height > 0) {
            side = icon.getLayoutParams().height;
        }
        if (side <= 0) {
            return;
        }
        ViewGroup.LayoutParams iconParams = icon.getLayoutParams();
        if (iconParams != null && iconParams.width != side) {
            iconParams.width = side;
            icon.setLayoutParams(iconParams);
        }
        applyMenuWidth(group, side);
    }

    private static boolean containsView(View parent, View child) {
        android.view.ViewParent current = child.getParent();
        while (current instanceof View) {
            if (current == parent) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static void applyMenuWidth(View menu, int width) {
        if (width <= 0) {
            return;
        }
        ViewGroup.LayoutParams params = menu.getLayoutParams();
        if (params == null || params.width == width) {
            return;
        }
        params.width = width;
        menu.setLayoutParams(params);
    }

    private static void clearHorizontalMargins(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.leftMargin == 0 && margins.rightMargin == 0
                && margins.getMarginStart() == 0 && margins.getMarginEnd() == 0) {
            return;
        }
        margins.leftMargin = 0;
        margins.rightMargin = 0;
        margins.setMarginStart(0);
        margins.setMarginEnd(0);
        view.setLayoutParams(margins);
    }

    private static void placeBackButton(View icon, View back) {
        ViewGroup.LayoutParams dst = back.getLayoutParams();
        if (dst == null) {
            return;
        }
        int side = 0;
        if (icon != null) {
            side = icon.getHeight();
            if (side <= 0 && icon.getLayoutParams() != null) {
                side = icon.getLayoutParams().height;
            }
        }
        if (side <= 0) {
            side = dst.height > 0 ? dst.height : 48;
        }
        dst.width = side;
        dst.height = ViewGroup.LayoutParams.MATCH_PARENT;
        try {
            Field gravity = dst.getClass().getField("gravity");
            gravity.setInt(dst, Gravity.CENTER_VERTICAL);
        } catch (Throwable ignored) {}
        back.setLayoutParams(dst);
    }

    private static Bitmap backBitmap() {
        if (backIcon != null) {
            return backIcon;
        }
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(size * 0.07f);
        float mid = size / 2f;
        canvas.drawLine(size * 0.58f, size * 0.34f, size * 0.40f, mid, paint);
        canvas.drawLine(size * 0.40f, mid, size * 0.58f, size * 0.66f, paint);
        backIcon = bitmap;
        return bitmap;
    }

    private static final ThreadLocal<Boolean> ENTERING_IMMERSIVE = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Rect> IMMERSIVE_BOUNDS = new ThreadLocal<Rect>();

    /** Back goes to this window's task only, after that task is the focused one. */
    private static void sendBackToWindow(Object decor, View button) {
        Object task = field(decor, "mTaskInfo");
        Integer id = taskId(task);
        if (id == null || button == null) {
            return;
        }
        int displayId = displayId(task, button);
        try {
            Class<?> manager = Class.forName("android.app.ActivityTaskManager");
            Object service = manager.getMethod("getService").invoke(null);
            service.getClass().getMethod("setFocusedTask", int.class).invoke(service, id);
        } catch (Throwable error) {
            if (!loggedBackFocus) {
                loggedBackFocus = true;
                XposedBridge.log(TAG + ": window back could not focus its task: " + error);
            }
            return;
        }
        long when = SystemClock.uptimeMillis();
        injectBack(button.getContext(), displayId, KeyEvent.ACTION_DOWN, when);
        injectBack(button.getContext(), displayId, KeyEvent.ACTION_UP, when);
    }

    private static int displayId(Object task, View button) {
        try {
            if (button.getDisplay() != null) {
                return button.getDisplay().getDisplayId();
            }
        } catch (Throwable ignored) {}
        Class<?> type = task == null ? null : task.getClass();
        while (type != null && type != Object.class) {
            try {
                Field found = type.getDeclaredField("displayId");
                found.setAccessible(true);
                return found.getInt(task);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return 0;
    }

    private static boolean injectBack(Context context, int displayId, int action, long when) {
        try {
            KeyEvent event = new KeyEvent(when, when, action, KeyEvent.KEYCODE_BACK, 0);
            try {
                event.getClass().getMethod("setDisplayId", int.class).invoke(event, Integer.valueOf(displayId));
            } catch (Throwable ignored) {}
            InputManager input = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (input == null) {
                return false;
            }
            Class<?> inputEvent = Class.forName("android.view.InputEvent");
            Object injected = input.getClass()
                    .getMethod("injectInputEvent", inputEvent, int.class)
                    .invoke(input, event, Integer.valueOf(0));
            return Boolean.TRUE.equals(injected);
        } catch (Throwable error) {
            return false;
        }
    }

    private static View findTaggedInAncestors(View start, String tag) {
        View current = start;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ViewGroup) {
                View found = findTagged((ViewGroup) current, tag);
                if (found != null) {
                    return found;
                }
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    private static View findTagged(ViewGroup group, String tag) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (tag.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    private static View findPin(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (PIN_TAG.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    private static void togglePin(Object decor, View button) {
        try {
            Integer id = taskId(field(decor, "mTaskInfo"));
            if (id == null) {
                return;
            }
            rememberChrome(decor);
            boolean pinned = PINNED_IDS.contains(id);
            if (pinned) {
                PINNED_IDS.remove(id);
                releasePinLayer(decor);
            } else {
                PINNED_IDS.add(id);
                raisePinned(null);
            }
            paintPin(button, !pinned);
            XposedBridge.log(TAG + ": window pin " + (!pinned ? "on" : "off"));
        } catch (Throwable ignored) {}
    }

    private static void releaseStuckAlwaysOnTop(Object decor) {
        Object task = field(decor, "mTaskInfo");
        Integer id = taskId(task);
        if (id == null || CLEARED_ALWAYS_ON_TOP.contains(id) || !taskIsAlwaysOnTop(task)) {
            return;
        }
        if (setAlwaysOnTop(decor, false)) {
            CLEARED_ALWAYS_ON_TOP.add(id);
            XposedBridge.log(TAG + ": cleared always-on-top");
        }
    }

    private static boolean setAlwaysOnTop(Object decor, boolean alwaysOnTop) {
        try {
            Object task = field(decor, "mTaskInfo");
            Object organizer = field(decor, "mTaskOrganizer");
            Object token = task == null ? null : field(task, "token");
            if (task == null || organizer == null || token == null) {
                return false;
            }
            Class<?> wctClass = Class.forName(
                    "android.window.WindowContainerTransaction", false, decor.getClass().getClassLoader());
            Object wct = wctClass.getDeclaredConstructor().newInstance();
            Method setAlways = null;
            Method[] methods = wctClass.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Class<?>[] params = methods[i].getParameterTypes();
                if ("setAlwaysOnTop".equals(methods[i].getName())
                        && params.length == 2
                        && params[1] == boolean.class) {
                    setAlways = methods[i];
                    break;
                }
            }
            Method apply = null;
            Class<?> orgType = organizer.getClass();
            while (orgType != null && apply == null) {
                Method[] orgMethods = orgType.getDeclaredMethods();
                for (int i = 0; i < orgMethods.length; i++) {
                    if ("applyTransaction".equals(orgMethods[i].getName())
                            && orgMethods[i].getParameterTypes().length == 1) {
                        apply = orgMethods[i];
                        break;
                    }
                }
                orgType = orgType.getSuperclass();
            }
            if (setAlways == null || apply == null) {
                return false;
            }
            setAlways.invoke(wct, token, Boolean.valueOf(alwaysOnTop));
            apply.setAccessible(true);
            apply.invoke(organizer, wct);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean taskIsAlwaysOnTop(Object task) {
        if (task == null) {
            return false;
        }
        try {
            Object config = task.getClass().getMethod("getConfiguration").invoke(task);
            Object window = config == null ? null : field(config, "windowConfiguration");
            if (window == null) {
                return false;
            }
            Object value = window.getClass().getMethod("isAlwaysOnTop").invoke(window);
            return Boolean.TRUE.equals(value);
        } catch (Throwable error) {
            return false;
        }
    }

    private static Integer taskId(Object task) {
        Object value = field(task, "taskId");
        return value instanceof Integer ? (Integer) value : null;
    }

    private static void rememberChrome(Object decor) {
        Integer id = taskId(field(decor, "mTaskInfo"));
        if (id == null) {
            return;
        }
        TaskChrome chrome = TASK_CHROME.get(id);
        if (chrome == null) {
            chrome = new TaskChrome(id.intValue());
            TASK_CHROME.put(id, chrome);
        }
        chrome.host = decor;
        Object info = field(decor, "mTaskInfo");
        if (info != null) {
            chrome.info = info;
        }
        Object task = field(decor, "mTaskSurface");
        if (surfaceValid(task)) {
            chrome.task = task;
        }
        Object container = field(decor, "mDecorationContainerSurface");
        if (surfaceValid(container)) {
            chrome.decor = container;
        }
        Object caption = field(decor, "mCaptionSurface");
        if (surfaceValid(caption)) {
            chrome.caption = caption;
        }
        Object outline = outlineSurface(decor);
        if (surfaceValid(outline)) {
            chrome.outline = outline;
        }
    }

    private static Object outlineSurface(Object decor) {
        Object outline = field(decor, "mFreeformOutline");
        Object host = outline == null ? null : field(outline, "mFreeformOutline");
        return host == null ? null : field(host, "windowSurface");
    }

    private static Object surfaceFor(Integer id) {
        if (id == null) {
            return null;
        }
        TaskChrome chrome = TASK_CHROME.get(id);
        if (chrome == null) {
            return null;
        }
        if (!surfaceValid(chrome.task)) {
            chrome.task = null;
            return null;
        }
        return chrome.task;
    }

    private static boolean surfaceValid(Object surface) {
        if (surface == null) {
            return false;
        }
        try {
            Object value = surface.getClass().getMethod("isValid").invoke(surface);
            return !Boolean.FALSE.equals(value);
        } catch (Throwable error) {
            return true;
        }
    }

    private static void raisePinned(Object preferred) {
        if (PINNED_IDS.isEmpty()) {
            return;
        }
        if (preferred != null) {
            pinOrderCover = preferred;
        }
        applyPinOrder(pinOrderCover);
        if (pinOrderPosted) {
            return;
        }
        pinOrderPosted = true;
        try {
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    applyPinOrder(pinOrderCover);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            pinOrderPosted = false;
                            if (!PINNED_IDS.isEmpty()) {
                                applyPinOrder(pinOrderCover);
                            }
                        }
                    }, 400);
                }
            });
        } catch (Throwable error) {
            pinOrderPosted = false;
        }
    }

    private static void applyPinOrder(Object preferred) {
        if (PINNED_IDS.isEmpty()) {
            return;
        }
        ArrayList<TaskChrome> windows = new ArrayList<TaskChrome>();
        synchronized (TASK_CHROME) {
            windows.addAll(TASK_CHROME.values());
        }
        Object sample = pinnedSurface(windows);
        if (sample == null) {
            return;
        }
        try {
            Object txn = newTransaction(sample);
            if (txn == null || !orderPins(txn, windows)) {
                return;
            }
            txn.getClass().getMethod("apply").invoke(txn);
        } catch (Throwable ignored) {}
    }

    private static void stampPin(Object txn, Object preferred) {
        if (txn == null || PINNED_IDS.isEmpty() || !ensureRelativeLayer(txn)) {
            return;
        }
        ArrayList<TaskChrome> windows = new ArrayList<TaskChrome>();
        synchronized (TASK_CHROME) {
            windows.addAll(TASK_CHROME.values());
        }
        try {
            orderPins(txn, windows);
        } catch (Throwable ignored) {}
    }

    private static void noteFront(Object taskInfo) {
        Integer id = taskId(taskInfo);
        if (id == null || PINNED_IDS.contains(id)) {
            return;
        }
        TaskChrome chrome = TASK_CHROME.get(id);
        if (chrome == null) {
            return;
        }
        chrome.info = taskInfo;
        chrome.front = ++nextFrontRank;
    }

    private static Object pinnedSurface(ArrayList<TaskChrome> windows) {
        for (int i = 0; i < windows.size(); i++) {
            TaskChrome chrome = windows.get(i);
            if (PINNED_IDS.contains(Integer.valueOf(chrome.id)) && surfaceValid(chrome.task)) {
                return chrome.task;
            }
        }
        return null;
    }

    private static boolean orderPins(Object txn, ArrayList<TaskChrome> windows) throws Throwable {
        if (layerMethod == null) {
            return false;
        }
        ArrayList<TaskChrome> pins = new ArrayList<TaskChrome>();
        for (int i = 0; i < windows.size(); i++) {
            TaskChrome chrome = windows.get(i);
            if (PINNED_IDS.contains(Integer.valueOf(chrome.id)) && surfaceValid(chrome.task)) {
                pins.add(chrome);
            }
        }
        if (pins.isEmpty()) {
            return false;
        }
        for (int i = 0; i < pins.size(); i++) {
            for (int j = i + 1; j < pins.size(); j++) {
                if (pins.get(j).id < pins.get(i).id) {
                    TaskChrome swap = pins.get(i);
                    pins.set(i, pins.get(j));
                    pins.set(j, swap);
                }
            }
        }
        int level = 1000000;
        for (int i = 0; i < pins.size(); i++) {
            layerMethod.invoke(txn, pins.get(i).task, Integer.valueOf(level));
            level++;
        }
        if (!loggedPinRaise) {
            loggedPinRaise = true;
            XposedBridge.log(TAG + ": pinned windows kept above");
        }
        return true;
    }

    private static void releasePinLayer(Object decor) {
        try {
            Object task = field(decor, "mTaskSurface");
            Object taskInfo = field(decor, "mTaskInfo");
            Object organizer = field(decor, "mTaskOrganizer");
            Object token = taskInfo != null ? field(taskInfo, "token") : null;

            if (surfaceValid(task)) {
                ensureRelativeLayer(task);
                Object txn = newTransaction(task);
                if (txn != null && layerMethod != null) {
                    // Place just above normal task layers before WM natively reassigns child layers
                    layerMethod.invoke(txn, task, Integer.valueOf(20000));
                    txn.getClass().getMethod("apply").invoke(txn);
                }
            }

            if (organizer != null && token != null) {
                Class<?> wctClass = Class.forName(
                        "android.window.WindowContainerTransaction", false, decor.getClass().getClassLoader());
                // Toggle task index in TaskDisplayArea (bottom -> top) so WindowManagerService
                // marks mLayersChanged=true and natively runs assignChildLayers() for all tasks.
                Object wctDown = wctClass.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(wctDown, "reorder", token, false);
                XposedHelpers.callMethod(organizer, "applyTransaction", wctDown);

                Object wctUp = wctClass.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(wctUp, "reorder", token, true);
                XposedHelpers.callMethod(organizer, "applyTransaction", wctUp);
            }

            if (!PINNED_IDS.isEmpty()) {
                raisePinned(null);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": releasePinLayer failed: " + t);
        }
    }

    private static boolean ensureRelativeLayer(Object sample) {
        if (relativeLayerMethod != null) {
            return true;
        }
        try {
            ClassLoader loader = sample.getClass().getClassLoader();
            Class<?> surfaceClass = Class.forName("android.view.SurfaceControl", false, loader);
            Class<?> txnClass = Class.forName(
                    "android.view.SurfaceControl$Transaction", false, loader);
            relativeLayerMethod = txnClass.getMethod(
                    "setRelativeLayer", surfaceClass, surfaceClass, int.class);
            layerMethod = txnClass.getMethod("setLayer", surfaceClass, int.class);
            if (transactionCtor == null) {
                transactionCtor = txnClass.getDeclaredConstructor();
            }
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static Object newTransaction(Object sample) {
        try {
            if (!ensureRelativeLayer(sample) || transactionCtor == null) {
                return null;
            }
            return transactionCtor.newInstance();
        } catch (Throwable error) {
            return null;
        }
    }

    private static final class TaskChrome {
        final int id;
        Object task;
        Object decor;
        Object caption;
        Object outline;
        Object host;
        Object info;
        int front;

        TaskChrome(int id) {
            this.id = id;
        }
    }

    private static void paintPin(View button, boolean pinned) {
        if (button instanceof ImageView) {
            ((ImageView) button).setImageBitmap(pinBitmap(pinned));
        }
        String label = pinned ? "Unpin" : "Pin";
        button.setContentDescription(label);
        setPinHover(button, label);
    }

    private static void matchPinColor(View source, ImageView pin) {
        try {
            Object tint = source.getClass().getMethod("getImageTintList").invoke(source);
            if (tint == null) {
                return;
            }
            int[] state = (int[]) source.getClass().getMethod("getDrawableState").invoke(source);
            int color = ((Integer) tint.getClass()
                    .getMethod("getColorForState", int[].class, int.class)
                    .invoke(tint, state, Integer.valueOf(0xFFFFFFFF))).intValue();
            Class<?> tintList = Class.forName("android.content.res.ColorStateList");
            Object solid = tintList.getMethod("valueOf", int.class).invoke(null, Integer.valueOf(color));
            pin.getClass().getMethod("setImageTintList", tintList).invoke(pin, solid);
        } catch (Throwable ignored) {}
    }

    private static void setPinHover(View button, String label) {
        Class<?> type = button.getClass();
        while (type != null) {
            try {
                Method tooltip = type.getDeclaredMethod("setTooltipText", CharSequence.class);
                tooltip.setAccessible(true);
                tooltip.invoke(button, label);
                break;
            } catch (NoSuchMethodException missing) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                break;
            }
        }
        type = button.getClass();
        while (type != null) {
            try {
                Method hover = type.getDeclaredMethod("semSetHoverPopupType", int.class);
                hover.setAccessible(true);
                hover.invoke(button, Integer.valueOf(0));
                break;
            } catch (NoSuchMethodException missing) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    private static Bitmap pinBitmap(boolean pinned) {
        Bitmap cached = pinned ? unpinIcon : pinIcon;
        if (cached != null) {
            return cached;
        }
        int size = 64;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(pinned ? Paint.Style.FILL : Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.09f);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.28f, paint);
        if (pinned) {
            unpinIcon = bitmap;
        } else {
            pinIcon = bitmap;
        }
        return bitmap;
    }

    private static void copyPinSize(View from, View to) {
        ViewGroup.LayoutParams src = from.getLayoutParams();
        ViewGroup.LayoutParams dst = to.getLayoutParams();
        if (src == null || dst == null) {
            return;
        }
        dst.width = src.width > 0 ? src.width / 2 : src.width;
        dst.height = src.height > 0 ? src.height / 2 : src.height;
        try {
            String[] margins = new String[] {"leftMargin", "topMargin", "rightMargin", "bottomMargin"};
            for (int i = 0; i < margins.length; i++) {
                Field source = src.getClass().getField(margins[i]);
                Field target = dst.getClass().getField(margins[i]);
                target.setInt(dst, source.getInt(src));
            }
        } catch (Throwable ignored) {}
        try {
            Field gravity = dst.getClass().getField("gravity");
            int value = gravity.getInt(dst);
            if (value < 0) {
                value = 0;
            }
            gravity.setInt(dst, (value & ~0x70) | 0x10);
        } catch (Throwable ignored) {}
        to.setLayoutParams(dst);
    }

    private static void copyBackground(View from, View to) {
        try {
            Object background = from.getClass().getMethod("getBackground").invoke(from);
            if (background == null) {
                return;
            }
            Object state = background.getClass().getMethod("getConstantState").invoke(background);
            Object copy = background;
            if (state != null) {
                copy = state.getClass().getMethod("newDrawable").invoke(state);
            }
            Class<?> drawable = Class.forName("android.graphics.drawable.Drawable");
            to.getClass().getMethod("setBackground", drawable).invoke(to, copy);
        } catch (Throwable ignored) {}
    }

    private static boolean modificationsOn(View view) {
        Context context = null;
        try {
            if (view != null) {
                context = view.getContext();
            }
        } catch (Throwable ignored) {}
        if (context == null) {
            context = systemUiContext;
        }
        if (context == null) {
            return false;
        }
        try {
            if (!ModConfig.isModuleEnabled(context)) {
                return false;
            }
            return Settings.Global.getInt(context.getContentResolver(), EFFECTS_KEY, 0) == 1;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean topEdgeMaximizeOn() {
        return collapseStatus
                && modificationsOn(null)
                && ModConfig.getBoolean(systemUiContext, ModConfig.KEY_TOP_EDGE_MAXIMIZE, true);
    }

    private static final XC_MethodHook MAXIMIZE_INSTEAD_OF_EXIT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!topEdgeMaximizeOn()) {
                return;
            }
            Object result = param.getResult();
            if (!(result instanceof Enum)) {
                return;
            }
            if (!"TO_FULLSCREEN_INDICATOR".equals(((Enum<?>) result).name())) {
                return;
            }
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object maximized = Enum.valueOf((Class) result.getClass(), "TO_DESKTOP_MAXIMIZED_WINDOW");
                param.setResult(maximized);
                if (!loggedIndicator) {
                    loggedIndicator = true;
                    XposedBridge.log(TAG + ": top drop maximizes inside DeX");
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook COLLAPSE_STATUS_HEIGHT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.args.length == 0 || !(param.args[0] instanceof Context)) {
                return;
            }
            systemUiContext = (Context) param.args[0];
            watchStatusInset(systemUiContext);
            if (collapseStatus) {
                param.setResult(Integer.valueOf(0));
            }
        }
    };

    private static final XC_MethodHook REMEMBER_STATUS_BAR = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            statusBarController = param.thisObject;
        }
    };

    private static final XC_MethodHook KEEP_STATUS_COLLAPSED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!collapseStatus) {
                return;
            }
            try {
                Object controller = param.thisObject;
                Field height = controller.getClass().getDeclaredField("mBarHeight");
                height.setAccessible(true);
                height.setInt(controller, 0);
                Object state = field(controller, "mCurrentState");
                clearFlag(state, "mForceStatusBarVisible");
                clearFlag(state, "mOngoingProcessRequiresStatusBarVisible");
            } catch (Throwable ignored) {}
        }
    };

    private static void clearFlag(Object target, String name) {
        if (target == null) {
            return;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                if (!found.getBoolean(target)) {
                    return;
                }
                found.setBoolean(target, false);
                if (!loggedStatusHold) {
                    loggedStatusHold = true;
                    XposedBridge.log(TAG + ": kept the status bar collapsed");
                }
                return;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                return;
            }
        }
    }

    private static void watchStatusInset(final Context context) {
        if (insetWatching || context == null) {
            return;
        }
        insetWatching = true;
        collapseStatus = readStatusInset(context);
        try {
            Uri uri = Settings.Global.getUriFor(INSET_KEY);
            context.getContentResolver().registerContentObserver(
                    uri, false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            boolean next = readStatusInset(context);
                            if (next == collapseStatus) {
                                return;
                            }
                            collapseStatus = next;
                            syncDesktopStatusHide();
                            XposedBridge.log(TAG + ": status inset " + (next ? "collapsed" : "restored"));
                            Object controller = statusBarController;
                            if (controller == null) {
                                return;
                            }
                            try {
                                controller.getClass().getMethod("refreshStatusBarHeight").invoke(controller);
                            } catch (Throwable ignored) {}
                        }
                    });
            syncDesktopStatusHide();
            XposedBridge.log(TAG + ": status inset " + (collapseStatus ? "collapsed" : "watching"));
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": status inset watch failed: " + error);
        }
        try {
            try {
                String existing = Settings.Global.getString(context.getContentResolver(), "internaldex_shade_cmd");
                if (existing != null) {
                    lastHandledShadeCmd = existing;
                }
            } catch (Throwable ignored) {}
            Uri shadeUri = Settings.Global.getUriFor("internaldex_shade_cmd");
            context.getContentResolver().registerContentObserver(
                    shadeUri, false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            handleShadeCommand(context);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": shade command watch failed: " + error);
        }
    }

    private static String lastHandledShadeCmd = "";
    private static int lastExpandedShadeKind = 0; // 0 = closed, 1 = notifications, 2 = quick settings

    private static void handleShadeCommand(Context context) {
        if (context == null) return;
        try {
            String cmd = Settings.Global.getString(context.getContentResolver(), "internaldex_shade_cmd");
            if (cmd == null || cmd.isEmpty() || cmd.equals(lastHandledShadeCmd)) {
                return;
            }
            lastHandledShadeCmd = cmd;
            boolean wantQs = cmd.startsWith("qs:");
            int targetKind = wantQs ? 2 : 1;
            Object sbManager = context.getSystemService("statusbar");
            if (sbManager == null) return;
            Method getService = sbManager.getClass().getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object service = getService.invoke(sbManager);
            if (service == null) return;

            boolean panelExpanded = Boolean.TRUE.equals(
                    service.getClass().getMethod("getPanelExpandStateToType", int.class)
                            .invoke(service, Integer.valueOf(0)));

            if (panelExpanded && lastExpandedShadeKind == targetKind) {
                service.getClass().getMethod("collapsePanelsToType", int.class)
                        .invoke(service, Integer.valueOf(0));
                lastExpandedShadeKind = 0;
                XposedBridge.log(TAG + ": collapsed SystemUI shade");
            } else if (wantQs) {
                service.getClass().getMethod("expandSettingsPanelToType", String.class, int.class)
                        .invoke(service, null, Integer.valueOf(0));
                lastExpandedShadeKind = 2;
                XposedBridge.log(TAG + ": expanded SystemUI Quick Settings shade");
            } else {
                service.getClass().getMethod("expandNotificationsPanelToType", int.class)
                        .invoke(service, Integer.valueOf(0));
                lastExpandedShadeKind = 1;
                XposedBridge.log(TAG + ": expanded SystemUI Notifications shade");
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": handleShadeCommand failed: " + error);
        }
    }

    private static boolean readStatusInset(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), INSET_KEY, 0) == 1;
        } catch (Throwable error) {
            return false;
        }
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
}
