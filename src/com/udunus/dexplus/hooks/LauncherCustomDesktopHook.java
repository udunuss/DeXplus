package com.udunus.dexplus.hooks;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.udunus.dexplus.util.ModConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherCustomDesktopHook {
    private static final String TAG_DEX_DESKTOP_VIEW = "minsos_custom_dex_desktop";
    private static final String DESKTOP_PREFS = "minsos_dex_desktop_shortcuts";
    private static final String KEY_SHORTCUT_LIST = "shortcut_components_v1";
    private static final String KEY_SHORTCUT_POSITIONS = "shortcut_positions_v1";
    private static final String CUSTOM_SHORTCUT_PREFIX = "sc:";
    private static final String CUSTOM_SHORTCUT_DIR = "dexplus_shortcuts";

    private static volatile FrameLayout sActiveDesktopContainer;
    private static volatile ViewGroup sActiveWorkspaceContainer;
    private static volatile String sLastRedirectedKey;
    private static volatile long sLastRedirectedTime;
    private static volatile Object sLastHoneyDragInfo;
    private static volatile long sLastHoneyDragTime;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        hookWorkspaceContainer(cl);
        hookInternalDexTransition(cl);
        hookAddToHomeRedirection(cl);
        hookHomeAndDragSupport(cl);

        ModConfig.addChangeListener(new Runnable() {
            @Override
            public void run() {
                syncDesktopVisibilityOnMainThread();
            }
        });
    }

    private static void hookWorkspaceContainer(final ClassLoader cl) {
        try {
            final Class<?> wsContainerClass = XposedHelpers.findClassIfExists(
                    "com.honeyspace.ui.honeypots.workspace.presentation.WorkspaceContainer", cl);
            if (wsContainerClass == null) return;

            XposedBridge.hookAllMethods(wsContainerClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ViewGroup wsContainer = (ViewGroup) param.thisObject;
                        Context ctx = wsContainer.getContext();
                        if (ctx == null || !LauncherDexTaskbarHook.isBuiltInDisplay(ctx)) return;
                        sActiveWorkspaceContainer = wsContainer;
                        ensureDesktopOverlayAttached(wsContainer);
                        updateWorkspaceAndDesktopVisibility(wsContainer);
                    } catch (Throwable t) {
                        XposedBridge.log(ModConfig.TAG + ": WorkspaceContainer.onFinishInflate error: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(wsContainerClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ViewGroup wsContainer = (ViewGroup) param.thisObject;
                        Context ctx = wsContainer.getContext();
                        if (ctx == null || !LauncherDexTaskbarHook.isBuiltInDisplay(ctx)) return;
                        sActiveWorkspaceContainer = wsContainer;
                        ensureDesktopOverlayAttached(wsContainer);
                        updateWorkspaceAndDesktopVisibility(wsContainer);
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(wsContainerClass, "onWindowFocusChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ViewGroup wsContainer = (ViewGroup) param.thisObject;
                        Context ctx = wsContainer.getContext();
                        if (ctx == null || !LauncherDexTaskbarHook.isBuiltInDisplay(ctx)) return;
                        updateWorkspaceAndDesktopVisibility(wsContainer);
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(wsContainerClass, "onInterceptTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        ViewGroup wsContainer = (ViewGroup) param.thisObject;
                        Context ctx = wsContainer.getContext();
                        if (isDedicatedWorkspaceActive(ctx)) {
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Intercept stock WorkspaceContainer.onDrag so drops from App Drawer route to Dedicated Workspace
            // instead of the hidden stock WorkspaceFastRecyclerView behind it.
            XposedBridge.hookAllMethods(wsContainerClass, "onDrag", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2 || !(param.args[1] instanceof DragEvent)) return;
                        ViewGroup wsContainer = (ViewGroup) param.thisObject;
                        Context ctx = wsContainer.getContext();
                        if (!isDedicatedWorkspaceActive(ctx)) return;
                        if (sActiveDesktopContainer instanceof ClassicDexDesktopView && sActiveDesktopContainer.isShown()) {
                            DragEvent ev = (DragEvent) param.args[1];
                            boolean handled = ((ClassicDexDesktopView) sActiveDesktopContainer).handleHostDragEvent(wsContainer, ev);
                            param.setResult(handled);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hookWorkspaceContainer error: " + t);
        }
    }

    /**
     * Captures HoneySpace DragInfo payloads (App Drawer / OverlayApps drags) and routes HomeView
     * secondary/right-clicks onto Dedicated Workspace when it is active and visible.
     */
    private static void hookHomeAndDragSupport(final ClassLoader cl) {
        try {
            Class<?> dragInfoClass = XposedHelpers.findClassIfExists("com.honeyspace.sdk.DragInfo", cl);
            if (dragInfoClass != null) {
                XposedBridge.hookAllConstructors(dragInfoClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sLastHoneyDragInfo = param.thisObject;
                        sLastHoneyDragTime = SystemClock.uptimeMillis();
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hook DragInfo error: " + t);
        }

        try {
            Class<?> homeViewClass = XposedHelpers.findClassIfExists(
                    "com.honeyspace.ui.honeypots.homescreen.presentation.HomeView", cl);
            if (homeViewClass != null) {
                XposedBridge.hookAllMethods(homeViewClass, "dispatchTouchEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) return;
                            View homeView = (View) param.thisObject;
                            Context ctx = homeView.getContext();
                            if (!isDedicatedWorkspaceActive(ctx)) return;
                            if (!(sActiveDesktopContainer instanceof ClassicDexDesktopView) || !sActiveDesktopContainer.isShown()) {
                                return;
                            }

                            MotionEvent ev = (MotionEvent) param.args[0];
                            ClassicDexDesktopView desktop = (ClassicDexDesktopView) sActiveDesktopContainer;
                            float[] local = mapViewCoordinates(homeView, desktop, ev.getX(), ev.getY());
                            if (local != null) {
                                desktop.recordPointerLocation(local[0], local[1]);
                            }
                            if (ClassicDexDesktopView.isSecondaryButtonEvent(ev)
                                    || (desktop.isConsumingSecondaryClick() && (ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0
                                    && (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL))) {
                                if (ClassicDexDesktopView.isSecondaryButtonEvent(ev) && local != null) {
                                    desktop.markConsumingSecondaryClick(ev.getActionMasked() == MotionEvent.ACTION_DOWN);
                                    desktop.handleSecondaryMenuAt(local[0], local[1]);
                                } else {
                                    desktop.markConsumingSecondaryClick(false);
                                }
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hook HomeView secondary click error: " + t);
        }

        // Prevent One UI Home's HomeView context-click listener (LE5/e -> Customize/Widgets popup)
        // from firing over Dedicated Workspace, and show our empty desktop menu instead.
        try {
            Class<?> homeContextClickClass = XposedHelpers.findClassIfExists("E5.e", cl);
            if (homeContextClickClass != null) {
                XposedBridge.hookAllMethods(homeContextClickClass, "onContextClick", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Context ctx = resolveWorkspaceContext(null);
                            if (!isDedicatedWorkspaceActive(ctx)) return;
                            if (sActiveDesktopContainer instanceof ClassicDexDesktopView && sActiveDesktopContainer.isShown()) {
                                ((ClassicDexDesktopView) sActiveDesktopContainer).handleSecondaryMenuAtLastTouch();
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private static float[] mapViewCoordinates(View from, View to, float x, float y) {
        if (from == null || to == null) return null;
        int[] fromLoc = new int[2];
        int[] toLoc = new int[2];
        from.getLocationInWindow(fromLoc);
        to.getLocationInWindow(toLoc);
        float lx = fromLoc[0] + x - toLoc[0];
        float ly = fromLoc[1] + y - toLoc[1];
        if (lx < 0f || ly < 0f || (to.getWidth() > 0 && lx > to.getWidth()) || (to.getHeight() > 0 && ly > to.getHeight())) {
            return null;
        }
        return new float[]{lx, ly};
    }

    private static void hookInternalDexTransition(final ClassLoader cl) {
        try {
            final Class<?> o6cClass = XposedHelpers.findClassIfExists("O6.C", cl);
            if (o6cClass != null) {
                XposedHelpers.findAndHookMethod(
                        o6cClass,
                        "invokeSuspend",
                        Object.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                syncDesktopVisibilityOnMainThread();
                            }
                        }
                );
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Redirects One UI Home "Add to Home" QuickOption actions and external pinned shortcut
     * requests (ShortcutManager.requestPinShortcut / AddItemActivity) directly into the
     * Dedicated Workspace when Dedicated Workspace mode is active.
     */
    private static void hookAddToHomeRedirection(final ClassLoader cl) {
        // 1. App Drawer / Finder QuickOption "Add to Home"
        try {
            final Class<?> addToHomeClass = XposedHelpers.findClassIfExists(
                    "com.honeyspace.ui.common.quickoption.AddToHome", cl);
            if (addToHomeClass != null) {
                XposedBridge.hookAllMethods(addToHomeClass, "onClick", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            View clicked = param.args != null && param.args.length > 0 && param.args[0] instanceof View
                                    ? (View) param.args[0] : null;
                            Context ctx = resolveWorkspaceContext(clicked != null ? clicked.getContext() : null);
                            if (!isDedicatedWorkspaceActive(ctx)) return;

                            Object anchorInfo = XposedHelpers.callMethod(param.thisObject, "getItemInfo");
                            Object baseItem = anchorInfo != null
                                    ? XposedHelpers.callMethod(anchorInfo, "getItemInfo") : null;
                            if (baseItem != null && redirectBaseItemToWorkspace(ctx, baseItem, true)) {
                                try {
                                    Object closeFn = XposedHelpers.callMethod(param.thisObject, "getClose");
                                    if (closeFn != null) XposedHelpers.callMethod(closeFn, "invoke");
                                } catch (Throwable ignored) {}
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(ModConfig.TAG + ": AddToHome.onClick hook error: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hookAddToHomeRedirection(AddToHome) error: " + t);
        }

        // 2. External apps pinning shortcuts when Home is paused (AddItemActivity -> PendingAddItemOperator)
        try {
            final Class<?> pendingOpClass = XposedHelpers.findClassIfExists(
                    "com.honeyspace.ui.common.model.PendingAddItemOperator", cl);
            if (pendingOpClass != null) {
                XposedBridge.hookAllMethods(pendingOpClass, "addPendingShortcutToHome", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                            Context ctx = resolveOperatorContext(param.thisObject);
                            if (!isDedicatedWorkspaceActive(ctx)) return;

                            if (redirectSharedEventData(ctx, param.args[0], true)) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(ModConfig.TAG + ": PendingAddItemOperator hook error: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hookAddToHomeRedirection(PendingAddItemOperator) error: " + t);
        }

        // 3. Fallback: AddToHomeSharedEventData emitted directly when AddItemActivity runs while Home is resumed
        try {
            final Class<?> eventDataClass = XposedHelpers.findClassIfExists(
                    "com.honeyspace.sdk.source.entity.AddToHomeSharedEventData", cl);
            if (eventDataClass != null) {
                XposedBridge.hookAllConstructors(eventDataClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context ctx = resolveWorkspaceContext(null);
                            if (!isDedicatedWorkspaceActive(ctx)) return;
                            redirectSharedEventData(ctx, param.thisObject, true);
                        } catch (Throwable t) {
                            XposedBridge.log(ModConfig.TAG + ": AddToHomeSharedEventData hook error: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hookAddToHomeRedirection(EventData) error: " + t);
        }
    }

    private static Context resolveOperatorContext(Object operator) {
        if (operator != null) {
            try {
                Object c = XposedHelpers.getObjectField(operator, "context");
                if (c instanceof Context) return resolveWorkspaceContext((Context) c);
            } catch (Throwable ignored) {}
        }
        return resolveWorkspaceContext(null);
    }

    private static Context resolveWorkspaceContext(Context candidate) {
        ViewGroup ws = sActiveWorkspaceContainer;
        if (ws != null && ws.getContext() != null) {
            return ws.getContext();
        }
        if (candidate != null) return candidate;
        return AndroidAppHelper.currentApplication();
    }

    private static boolean isDedicatedWorkspaceActive(Context ctx) {
        if (ctx == null) return false;
        ViewGroup ws = sActiveWorkspaceContainer;
        boolean onBuiltIn = (ws != null && ws.getContext() != null && LauncherDexTaskbarHook.isBuiltInDisplay(ws.getContext()))
                || LauncherDexTaskbarHook.isBuiltInDisplay(ctx);
        return onBuiltIn
                && ModConfig.isModuleEnabled(ctx)
                && ModConfig.getBoolean(ctx, ModConfig.KEY_CUSTOM_DESKTOP_ENABLED, false)
                && LauncherDexTaskbarHook.isInternalDexActive(ctx, null);
    }

    private static boolean redirectSharedEventData(Context ctx, Object eventData, boolean showToast) {
        if (eventData == null) return false;
        Object itemsObj;
        try {
            itemsObj = XposedHelpers.callMethod(eventData, "getItems");
        } catch (Throwable t) {
            return false;
        }
        if (!(itemsObj instanceof List<?>)) return false;

        List<?> wrappers = (List<?>) itemsObj;
        boolean anyHandled = false;
        for (Object wrapper : wrappers) {
            if (wrapper == null) continue;
            Object baseItem = wrapper;
            try {
                Object inner = XposedHelpers.callMethod(wrapper, "getItem");
                if (inner != null) baseItem = inner;
            } catch (Throwable ignored) {}
            if (redirectBaseItemToWorkspace(ctx, baseItem, showToast)) {
                anyHandled = true;
            }
        }
        return anyHandled;
    }

    private static boolean redirectBaseItemToWorkspace(Context ctx, Object baseItem, boolean showToast) {
        return addOrUpdateBaseItem(ctx, baseItem, null, null, showToast, true) != null;
    }

    /**
     * Converts a HoneySpace BaseItem (AppItem or ShortcutItem) into a Dedicated Workspace entry,
     * optionally placing it at a specific free-grid slot or compact position.
     *
     * @return the desktop storageKey if handled, or {@code null} if unsupported.
     */
    private static String addOrUpdateBaseItem(
            Context ctx,
            Object baseItem,
            Integer targetCol,
            Integer targetRow,
            boolean showToast,
            boolean dedupeRecent) {
        if (ctx == null || baseItem == null) return null;
        String clsName = baseItem.getClass().getName();

        // AppItem -> launchable ComponentName
        if (clsName.endsWith(".AppItem")) {
            try {
                Object compKey = XposedHelpers.callMethod(baseItem, "getComponent");
                ComponentName cn = compKey != null
                        ? (ComponentName) XposedHelpers.callMethod(compKey, "getComponentName") : null;
                if (cn != null) {
                    String flat = cn.flattenToString();
                    if (dedupeRecent && isRecentlyRedirected(flat)) return flat;
                    String label = resolveLiveDataText(baseItem, "getLabel");
                    if (TextUtils.isEmpty(label)) {
                        try {
                            PackageManager pm = ctx.getPackageManager();
                            label = pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
                        } catch (Throwable ignored) {
                            label = cn.getClassName();
                        }
                    }
                    boolean added = addShortcutToDesktop(ctx, flat);
                    if (targetCol != null && targetRow != null) {
                        assignOrSwapSlot(ctx, flat, targetCol, targetRow);
                    }
                    syncDesktopVisibilityOnMainThread();
                    if (showToast) {
                        showToastOnMain(ctx, added
                                ? "Added \"" + label + "\" to workspace"
                                : (targetCol != null ? "Moved \"" + label + "\"" : "\"" + label + "\" is already on workspace"));
                    }
                    return flat;
                }
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": addOrUpdate AppItem error: " + t);
            }
            return null;
        }

        // ShortcutItem -> custom pinned shortcut / web bookmark / deep shortcut
        if (clsName.endsWith(".ShortcutItem") || hasMethod(baseItem, "getIntent")) {
            try {
                Intent intent = null;
                try {
                    Object intentObj = XposedHelpers.callMethod(baseItem, "getIntent");
                    if (intentObj instanceof Intent) intent = (Intent) intentObj;
                } catch (Throwable ignored) {}
                if (intent == null) {
                    try {
                        Object rawUri = XposedHelpers.callMethod(baseItem, "get_intent");
                        if (rawUri instanceof String && !((String) rawUri).isEmpty()) {
                            intent = Intent.parseUri((String) rawUri, Intent.URI_INTENT_SCHEME);
                        }
                    } catch (Throwable ignored) {}
                }
                if (intent == null) return null;

                String label = resolveLiveDataText(baseItem, "getLabel");
                if (TextUtils.isEmpty(label)) {
                    label = intent.getPackage() != null ? intent.getPackage() : "Shortcut";
                }

                Drawable icon = null;
                try {
                    Object iconLive = XposedHelpers.callMethod(baseItem, "getIcon");
                    if (iconLive != null) {
                        Object val = XposedHelpers.callMethod(iconLive, "getValue");
                        if (val instanceof Drawable) icon = (Drawable) val;
                    }
                } catch (Throwable ignored) {}
                if (icon == null) {
                    try {
                        Object bmp = XposedHelpers.callMethod(baseItem, "getIconBySoftwareConfig");
                        if (bmp instanceof Bitmap) {
                            icon = new BitmapDrawable(ctx.getResources(), (Bitmap) bmp);
                        }
                    } catch (Throwable ignored) {}
                }

                String pkg = intent.getPackage();
                if (TextUtils.isEmpty(pkg) && intent.getComponent() != null) {
                    pkg = intent.getComponent().getPackageName();
                }

                String key = buildCustomShortcutStorageKey(label, intent);
                if (dedupeRecent && isRecentlyRedirected(key)) return key;

                boolean added = saveAndAddCustomShortcut(ctx, key, label, intent, icon, pkg);
                if (targetCol != null && targetRow != null) {
                    assignOrSwapSlot(ctx, key, targetCol, targetRow);
                }
                syncDesktopVisibilityOnMainThread();
                if (showToast) {
                    showToastOnMain(ctx, added
                            ? "Added \"" + label + "\" to workspace"
                            : "Updated \"" + label + "\" on workspace");
                }
                return key;
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": addOrUpdate ShortcutItem error: " + t);
            }
        }
        return null;
    }

    private static boolean hasMethod(Object obj, String name) {
        try {
            obj.getClass().getMethod(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveLiveDataText(Object target, String getter) {
        try {
            Object live = XposedHelpers.callMethod(target, getter);
            if (live != null) {
                Object val = XposedHelpers.callMethod(live, "getValue");
                if (val != null) return val.toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isRecentlyRedirected(String key) {
        long now = SystemClock.uptimeMillis();
        if (key != null && key.equals(sLastRedirectedKey) && (now - sLastRedirectedTime) < 2000L) {
            return true;
        }
        sLastRedirectedKey = key;
        sLastRedirectedTime = now;
        return false;
    }

    private static void showToastOnMain(final Context ctx, final String msg) {
        if (ctx == null || TextUtils.isEmpty(msg)) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx,
                            msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void syncDesktopVisibilityOnMainThread() {
        final ViewGroup wsContainer = sActiveWorkspaceContainer;
        if (wsContainer == null) return;
        wsContainer.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureDesktopOverlayAttached(wsContainer);
                    updateWorkspaceAndDesktopVisibility(wsContainer);
                    if (sActiveDesktopContainer instanceof ClassicDexDesktopView) {
                        ((ClassicDexDesktopView) sActiveDesktopContainer).rebuildGrid();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void ensureDesktopOverlayAttached(ViewGroup wsContainer) {
        if (wsContainer == null) return;
        View existing = wsContainer.findViewWithTag(TAG_DEX_DESKTOP_VIEW);
        if (existing instanceof ClassicDexDesktopView) {
            sActiveDesktopContainer = (FrameLayout) existing;
            return;
        }
        Context ctx = wsContainer.getContext();
        ClassicDexDesktopView desktopView = new ClassicDexDesktopView(ctx);
        desktopView.setTag(TAG_DEX_DESKTOP_VIEW);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        wsContainer.addView(desktopView, lp);
        sActiveDesktopContainer = desktopView;
    }

    private static void updateWorkspaceAndDesktopVisibility(ViewGroup wsContainer) {
        if (wsContainer == null) return;
        Context ctx = wsContainer.getContext();
        boolean showCustomDesktop = isDedicatedWorkspaceActive(ctx);

        for (int i = 0; i < wsContainer.getChildCount(); i++) {
            View child = wsContainer.getChildAt(i);
            if (TAG_DEX_DESKTOP_VIEW.equals(child.getTag())) {
                if (showCustomDesktop) {
                    if (child.getVisibility() != View.VISIBLE) {
                        child.setVisibility(View.VISIBLE);
                        if (child instanceof ClassicDexDesktopView) {
                            ((ClassicDexDesktopView) child).rebuildGrid();
                        }
                    }
                    child.bringToFront();
                } else {
                    child.setVisibility(View.GONE);
                }
            } else {
                // Hide original launcher home workspace shortcuts & page indicator when DeX desktop is active
                child.setVisibility(showCustomDesktop ? View.INVISIBLE : View.VISIBLE);
            }
        }
    }

    /**
     * Dedicated Workspace Grid View that replaces One UI Home's normal workspace shortcuts
     * when enabled. Supports reliable right-click anywhere, direct mouse/touch drag-and-drop,
     * optional free grid placement, App Drawer drop targets, keyboard navigation, and hover states.
     */
    private static final class ClassicDexDesktopView extends FrameLayout {
        private final ScrollView mScrollView;
        private final GridLayout mGridLayout;
        private final View mPopupAnchor;
        private final List<View> mCellViews = new ArrayList<>();
        private final Map<View, int[]> mSlotMap = new HashMap<>();
        private View mSelectedCell;
        private DesktopShortcutItem mSelectedItem;
        private View mHighlightedDropCell;
        private BroadcastReceiver mCommandReceiver;
        private long mLastClickTime = 0L;
        private String mLastClickedKey = null;
        private float mLastTouchX = 200f;
        private float mLastTouchY = 200f;
        private boolean mConsumingSecondaryClick = false;
        private long mLastSecondaryMenuTime = 0L;
        private int mCurrentCols = 10;
        private int mCurrentRows = 5;
        private int mPendingAddCol = -1;
        private int mPendingAddRow = -1;

        private static final class DragSession {
            final DesktopShortcutItem item;
            final View sourceCell;
            final float startGridX;
            final float startGridY;
            final boolean openMenuIfStationary;
            float maxDistSq = 0f;
            boolean handledDrop = false;

            DragSession(
                    DesktopShortcutItem item,
                    View sourceCell,
                    float startGridX,
                    float startGridY,
                    boolean openMenuIfStationary) {
                this.item = item;
                this.sourceCell = sourceCell;
                this.startGridX = startGridX;
                this.startGridY = startGridY;
                this.openMenuIfStationary = openMenuIfStationary;
            }

            void recordGridPoint(float gx, float gy) {
                float dx = gx - startGridX;
                float dy = gy - startGridY;
                float d2 = dx * dx + dy * dy;
                if (d2 > maxDistSq) {
                    maxDistSq = d2;
                }
            }
        }

        ClassicDexDesktopView(final Context context) {
            super(context);
            setClipToPadding(false);
            setClickable(true);
            setLongClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);
            if (Build.VERSION.SDK_INT >= 23) {
                setContextClickable(true);
            }

            mScrollView = new ScrollView(context);
            mScrollView.setFillViewport(true);
            mScrollView.setVerticalScrollBarEnabled(false);
            mScrollView.setClickable(true);
            mScrollView.setLongClickable(true);
            if (Build.VERSION.SDK_INT >= 23) {
                mScrollView.setContextClickable(true);
            }

            mGridLayout = new GridLayout(context);
            mGridLayout.setOrientation(GridLayout.VERTICAL);
            mGridLayout.setClickable(true);
            mGridLayout.setLongClickable(true);
            if (Build.VERSION.SDK_INT >= 23) {
                mGridLayout.setContextClickable(true);
            }
            mScrollView.addView(mGridLayout, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            addView(mScrollView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            // Invisible (alpha=0) yet View.VISIBLE anchor on top so PopupMenu always has valid layout bounds
            mPopupAnchor = new View(context);
            mPopupAnchor.setAlpha(0f);
            mPopupAnchor.setClickable(false);
            mPopupAnchor.setFocusable(false);
            int anchorPx = Math.max(2, dpToPx(context, 2));
            FrameLayout.LayoutParams anchorLp = new FrameLayout.LayoutParams(anchorPx, anchorPx);
            anchorLp.gravity = Gravity.TOP | Gravity.START;
            mPopupAnchor.setVisibility(View.VISIBLE);
            addView(mPopupAnchor, anchorLp);

            OnClickListener clearClick = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearSelection();
                    requestFocus();
                }
            };
            OnLongClickListener emptyLongClick = new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    showEmptyDesktopMenu(mLastTouchX, mLastTouchY);
                    return true;
                }
            };

            setOnClickListener(clearClick);
            setOnLongClickListener(emptyLongClick);
            mScrollView.setOnClickListener(clearClick);
            mScrollView.setOnLongClickListener(emptyLongClick);
            mGridLayout.setOnClickListener(clearClick);
            mGridLayout.setOnLongClickListener(emptyLongClick);

            if (Build.VERSION.SDK_INT >= 23) {
                OnContextClickListener emptyContextClick = new OnContextClickListener() {
                    @Override
                    public boolean onContextClick(View v) {
                        handleSecondaryMenuAt(mLastTouchX, mLastTouchY);
                        return true;
                    }
                };
                setOnContextClickListener(emptyContextClick);
                mScrollView.setOnContextClickListener(emptyContextClick);
                mGridLayout.setOnContextClickListener(emptyContextClick);
            }

            OnGenericMotionListener secondaryGenericListener = new OnGenericMotionListener() {
                @Override
                public boolean onGenericMotion(View v, MotionEvent event) {
                    if (event == null) return false;
                    float[] desk = mapViewCoordinates(v, ClassicDexDesktopView.this, event.getX(), event.getY());
                    if (desk != null) {
                        mLastTouchX = desk[0];
                        mLastTouchY = desk[1];
                    }
                    int action = event.getActionMasked();
                    if ((action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_DOWN)
                            && isSecondaryButtonEvent(event)) {
                        handleSecondaryMenuAt(mLastTouchX, mLastTouchY);
                        return true;
                    }
                    if (action == MotionEvent.ACTION_BUTTON_RELEASE
                            && Build.VERSION.SDK_INT >= 23
                            && event.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                        return true;
                    }
                    return false;
                }
            };
            mScrollView.setOnGenericMotionListener(secondaryGenericListener);
            mGridLayout.setOnGenericMotionListener(secondaryGenericListener);

            // Accept both internal drags and App Drawer / OverlayApps drags on empty grid areas
            mGridLayout.setOnDragListener(new OnDragListener() {
                @Override
                public boolean onDrag(View v, DragEvent event) {
                    if (!isSupportedDragEvent(event)) return false;
                    int action = event.getAction();
                    if (action == DragEvent.ACTION_DRAG_STARTED) {
                        return true;
                    } else if (action == DragEvent.ACTION_DRAG_LOCATION) {
                        updateDragSessionLocation(event.getLocalState(), event.getX(), event.getY());
                        if (ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, false)) {
                            int[] slot = gridCoordsToSlot(event.getX(), event.getY());
                            View slotView = findViewAtSlot(slot[0], slot[1]);
                            if (slotView != null && slotView.getTag() == null) {
                                setDropHighlight(slotView);
                            } else {
                                clearDropHighlight();
                            }
                        }
                        return true;
                    } else if (action == DragEvent.ACTION_DRAG_EXITED) {
                        clearDropHighlight();
                        return true;
                    } else if (action == DragEvent.ACTION_DROP) {
                        clearDropHighlight();
                        return handleDropOnGrid(event.getX(), event.getY(), null, event);
                    } else if (action == DragEvent.ACTION_DRAG_ENDED) {
                        clearDropHighlight();
                        finishDragSession(event);
                        return true;
                    }
                    return true;
                }
            });

            rebuildGrid();
        }

        void recordPointerLocation(float localX, float localY) {
            mLastTouchX = localX;
            mLastTouchY = localY;
        }

        boolean isConsumingSecondaryClick() {
            return mConsumingSecondaryClick;
        }

        void markConsumingSecondaryClick(boolean consuming) {
            mConsumingSecondaryClick = consuming;
        }

        void handleSecondaryMenuAtLastTouch() {
            handleSecondaryMenuAt(mLastTouchX, mLastTouchY);
        }

        boolean handleHostDragEvent(View host, DragEvent event) {
            if (event == null || !isSupportedDragEvent(event)) return false;
            int action = event.getAction();
            if (action == DragEvent.ACTION_DRAG_STARTED) {
                return true;
            }
            if (action == DragEvent.ACTION_DRAG_LOCATION) {
                float[] gridPos = mapHostEventToGrid(host, event.getX(), event.getY());
                updateDragSessionLocation(event.getLocalState(), gridPos[0], gridPos[1]);
                return true;
            }
            if (action == DragEvent.ACTION_DROP) {
                clearDropHighlight();
                float[] gridPos = mapHostEventToGrid(host, event.getX(), event.getY());
                return handleDropOnGrid(gridPos[0], gridPos[1], null, event);
            }
            if (action == DragEvent.ACTION_DRAG_ENDED) {
                clearDropHighlight();
                finishDragSession(event);
                return true;
            }
            return true;
        }

        private float[] mapHostEventToGrid(View host, float hostX, float hostY) {
            int[] hostLoc = new int[2];
            int[] gridLoc = new int[2];
            host.getLocationInWindow(hostLoc);
            mGridLayout.getLocationInWindow(gridLoc);
            return new float[]{hostLoc[0] + hostX - gridLoc[0], hostLoc[1] + hostY - gridLoc[1]};
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            registerDesktopCommandReceiver(getContext());
        }

        @Override
        protected void onDetachedFromWindow() {
            unregisterDesktopCommandReceiver(getContext());
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        rebuildGrid();
                    }
                });
            }
        }

        private static boolean isSecondaryButtonEvent(MotionEvent ev) {
            if (ev == null) return false;
            if ((ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) return true;
            if (Build.VERSION.SDK_INT >= 23 && ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
                return ev.getActionButton() == MotionEvent.BUTTON_SECONDARY;
            }
            return false;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev != null) {
                mLastTouchX = ev.getX();
                mLastTouchY = ev.getY();
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    android.view.ViewParent p = getParent();
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true);
                        p = p.getParent();
                    }
                    if (isSecondaryButtonEvent(ev)) {
                        mConsumingSecondaryClick = true;
                        handleSecondaryMenuAt(ev.getX(), ev.getY());
                        return true;
                    }
                    mConsumingSecondaryClick = false;
                } else if (mConsumingSecondaryClick) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        mConsumingSecondaryClick = false;
                    }
                    return true;
                }
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent ev) {
            if (ev != null) {
                mLastTouchX = ev.getX();
                mLastTouchY = ev.getY();
                int action = ev.getActionMasked();
                if ((action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_DOWN)
                        && isSecondaryButtonEvent(ev)) {
                    handleSecondaryMenuAt(ev.getX(), ev.getY());
                    return true;
                }
                if (action == MotionEvent.ACTION_BUTTON_RELEASE
                        && Build.VERSION.SDK_INT >= 23
                        && ev.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                    return true;
                }
            }
            return super.dispatchGenericMotionEvent(ev);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            Context ctx = getContext();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (mSelectedCell != null && mSelectedItem != null) {
                    showShortcutContextMenu(ctx, mSelectedCell, mSelectedItem);
                } else {
                    showEmptyDesktopMenu(mLastTouchX, mLastTouchY);
                }
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
                    && mSelectedItem != null) {
                launchShortcut(ctx, mSelectedItem, ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
                    && mSelectedItem != null) {
                removeShortcutFromDesktop(ctx, mSelectedItem.storageKey);
                rebuildGrid();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private void handleSecondaryMenuAt(float viewX, float viewY) {
            long now = SystemClock.uptimeMillis();
            if (now - mLastSecondaryMenuTime < 180L) return;
            mLastSecondaryMenuTime = now;
            mLastTouchX = viewX;
            mLastTouchY = viewY;

            View hitCell = findCellAt(viewX, viewY);
            if (hitCell != null && hitCell.getTag() instanceof DesktopShortcutItem) {
                DesktopShortcutItem item = (DesktopShortcutItem) hitCell.getTag();
                selectCell(hitCell, item);
                showShortcutContextMenu(getContext(), hitCell, item);
            } else {
                clearSelection();
                showEmptyDesktopMenu(viewX, viewY);
            }
        }

        private View findCellAt(float viewX, float viewY) {
            float gx = viewX - mScrollView.getLeft() + mScrollView.getScrollX() - mGridLayout.getLeft();
            float gy = viewY - mScrollView.getTop() + mScrollView.getScrollY() - mGridLayout.getTop();
            for (int i = 0; i < mGridLayout.getChildCount(); i++) {
                View child = mGridLayout.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (!(child.getTag() instanceof DesktopShortcutItem)) continue;
                if (gx >= child.getLeft() && gx < child.getRight()
                        && gy >= child.getTop() && gy < child.getBottom()) {
                    return child;
                }
            }
            return null;
        }

        private int[] viewCoordsToGridSlot(float viewX, float viewY) {
            float gx = viewX - mScrollView.getLeft() + mScrollView.getScrollX() - mGridLayout.getLeft();
            float gy = viewY - mScrollView.getTop() + mScrollView.getScrollY() - mGridLayout.getTop();
            return gridCoordsToSlot(gx, gy);
        }

        private int[] gridCoordsToSlot(float gx, float gy) {
            int cols = Math.max(1, mCurrentCols);
            int padL = mGridLayout.getPaddingLeft();
            int padT = mGridLayout.getPaddingTop();
            int availW = Math.max(1, mGridLayout.getWidth() - padL - mGridLayout.getPaddingRight());
            int availH = Math.max(1, getHeight() - padT - mGridLayout.getPaddingBottom());
            float cellW = availW / (float) cols;
            float cellH = availH / (float) Math.max(1, mCurrentRows);
            int col = Math.max(0, Math.min(cols - 1, (int) ((gx - padL) / Math.max(1f, cellW))));
            int row = Math.max(0, (int) ((gy - padT) / Math.max(1f, cellH)));
            return new int[]{col, row};
        }

        private View findViewAtSlot(int col, int row) {
            for (Map.Entry<View, int[]> entry : mSlotMap.entrySet()) {
                int[] pos = entry.getValue();
                if (pos != null && pos[0] == col && pos[1] == row) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private View prepareAnchorAt(float x, float y) {
            Context ctx = getContext();
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = getWidth() > 0 ? getWidth() : dm.widthPixels;
            int h = getHeight() > 0 ? getHeight() : dm.heightPixels;
            int anchorW = Math.max(2, mPopupAnchor.getWidth() > 0 ? mPopupAnchor.getWidth() : dpToPx(ctx, 2));
            int anchorH = Math.max(2, mPopupAnchor.getHeight() > 0 ? mPopupAnchor.getHeight() : dpToPx(ctx, 2));
            float px = Math.max(0f, Math.min(x, Math.max(0, w - anchorW - dpToPx(ctx, 4))));
            float py = Math.max(0f, Math.min(y, Math.max(0, h - anchorH - dpToPx(ctx, 4))));
            mPopupAnchor.setVisibility(View.VISIBLE);
            mPopupAnchor.setTranslationX(px);
            mPopupAnchor.setTranslationY(py);
            mPopupAnchor.bringToFront();
            return mPopupAnchor;
        }

        private void registerDesktopCommandReceiver(Context context) {
            if (mCommandReceiver != null || context == null) return;
            try {
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                IntentFilter filter = new IntentFilter();
                filter.addAction(ModConfig.ACTION_DESKTOP_COMMAND);
                mCommandReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        if (intent == null) return;
                        if (ModConfig.ACTION_DESKTOP_COMMAND.equals(intent.getAction())) {
                            String cmd = intent.getStringExtra("command");
                            if ("reset_shortcuts".equals(cmd)) {
                                resetDesktopShortcuts(ctx);
                            }
                            rebuildGrid();
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    appCtx.registerReceiver(mCommandReceiver, filter, 2 /* RECEIVER_EXPORTED */);
                } else {
                    appCtx.registerReceiver(mCommandReceiver, filter);
                }
            } catch (Throwable ignored) {
                mCommandReceiver = null;
            }
        }

        private void unregisterDesktopCommandReceiver(Context context) {
            if (mCommandReceiver == null || context == null) return;
            try {
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                appCtx.unregisterReceiver(mCommandReceiver);
            } catch (Throwable ignored) {}
            mCommandReceiver = null;
        }

        void rebuildGrid() {
            Context ctx = getContext();
            if (ctx == null) return;

            String previousSelectedKey = mSelectedItem != null ? mSelectedItem.storageKey : null;
            mSelectedCell = null;
            mSelectedItem = null;
            mHighlightedDropCell = null;
            mCellViews.clear();
            mSlotMap.clear();

            int cols = Math.max(6, Math.min(16, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_GRID_COLS, 10)));
            int rows = Math.max(4, Math.min(10, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_GRID_ROWS, 5)));
            mCurrentCols = cols;
            mCurrentRows = rows;
            int iconScale = Math.max(65, Math.min(150, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_ICON_SCALE, 100)));
            boolean freePlacement = ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, false);

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int totalW = getWidth() > 0 ? getWidth() : dm.widthPixels;
            int totalH = getHeight() > 0 ? getHeight() : dm.heightPixels;
            int padLeft = dpToPx(ctx, 22);
            int padTop = dpToPx(ctx, 26);
            int padRight = dpToPx(ctx, 22);
            int taskbarPx = LauncherDexTaskbarHook.getScaledTaskbarHeightPx(ctx);
            int padBottom = taskbarPx > 0 ? taskbarPx + dpToPx(ctx, 16) : dpToPx(ctx, 76);
            mGridLayout.setPadding(padLeft, padTop, padRight, padBottom);

            List<DesktopShortcutItem> items = loadDesktopShortcuts(ctx, cols * rows);
            int availW = Math.max(300, totalW - padLeft - padRight);
            int availH = Math.max(250, totalH - padTop - padBottom);
            int cellW = availW / cols;
            int cellH = availH / rows;
            int iconSizePx = Math.round(dpToPx(ctx, 50) * (iconScale / 100.0f));
            iconSizePx = Math.max(dpToPx(ctx, 20),
                    Math.min(iconSizePx, Math.min(cellW - dpToPx(ctx, 16), cellH - dpToPx(ctx, 28))));

            mGridLayout.removeAllViews();

            if (freePlacement) {
                Map<String, int[]> placements = resolveGridPlacements(ctx, items, cols, rows);
                int maxRow = rows - 1;
                for (int[] pos : placements.values()) {
                    if (pos[1] > maxRow) maxRow = pos[1];
                }
                int effectiveRows = maxRow + 1;
                mGridLayout.setOrientation(GridLayout.HORIZONTAL);
                mGridLayout.setColumnCount(cols);
                mGridLayout.setRowCount(effectiveRows);

                DesktopShortcutItem[][] matrix = new DesktopShortcutItem[cols][effectiveRows];
                for (DesktopShortcutItem item : items) {
                    int[] pos = placements.get(item.storageKey);
                    if (pos != null && pos[0] >= 0 && pos[0] < cols && pos[1] >= 0 && pos[1] < effectiveRows) {
                        matrix[pos[0]][pos[1]] = item;
                    }
                }

                for (int r = 0; r < effectiveRows; r++) {
                    for (int c = 0; c < cols; c++) {
                        DesktopShortcutItem item = matrix[c][r];
                        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                                GridLayout.spec(r), GridLayout.spec(c));
                        lp.width = cellW;
                        lp.height = cellH;
                        if (item != null) {
                            View cell = createDesktopShortcutCell(ctx, item, c, r, lp, iconSizePx);
                            mGridLayout.addView(cell);
                            mCellViews.add(cell);
                            mSlotMap.put(cell, new int[]{c, r});
                            if (previousSelectedKey != null && previousSelectedKey.equals(item.storageKey)) {
                                selectCell(cell, item);
                            }
                        } else {
                            View emptySlot = createEmptySlotCell(ctx, c, r, lp);
                            mGridLayout.addView(emptySlot);
                            mSlotMap.put(emptySlot, new int[]{c, r});
                        }
                    }
                }
            } else {
                int effectiveRows = Math.max(rows, (items.size() + cols - 1) / cols);
                mGridLayout.setOrientation(GridLayout.VERTICAL);
                mGridLayout.setColumnCount(cols);
                mGridLayout.setRowCount(effectiveRows);

                for (int i = 0; i < items.size(); i++) {
                    final DesktopShortcutItem item = items.get(i);
                    int c = i / effectiveRows;
                    int r = i % effectiveRows;
                    GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                    lp.width = cellW;
                    lp.height = cellH;
                    View cell = createDesktopShortcutCell(ctx, item, c, r, lp, iconSizePx);
                    mGridLayout.addView(cell);
                    mCellViews.add(cell);
                    mSlotMap.put(cell, new int[]{c, r});
                    if (previousSelectedKey != null && previousSelectedKey.equals(item.storageKey)) {
                        selectCell(cell, item);
                    }
                }
            }
        }

        private View createEmptySlotCell(final Context context, final int col, final int row, GridLayout.LayoutParams lp) {
            final FrameLayout slot = new FrameLayout(context);
            slot.setLayoutParams(lp);
            slot.setClickable(true);
            slot.setLongClickable(true);
            if (Build.VERSION.SDK_INT >= 23) {
                slot.setContextClickable(true);
            }

            slot.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearSelection();
                    ClassicDexDesktopView.this.requestFocus();
                }
            });
            slot.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    showEmptyDesktopMenu(mLastTouchX, mLastTouchY);
                    return true;
                }
            });
            if (Build.VERSION.SDK_INT >= 23) {
                slot.setOnContextClickListener(new OnContextClickListener() {
                    @Override
                    public boolean onContextClick(View v) {
                        handleSecondaryMenuAt(mLastTouchX, mLastTouchY);
                        return true;
                    }
                });
            }
            slot.setOnDragListener(new OnDragListener() {
                @Override
                public boolean onDrag(View v, DragEvent event) {
                    if (!isSupportedDragEvent(event)) return false;
                    switch (event.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED:
                            return true;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            setDropHighlight(slot);
                            return true;
                        case DragEvent.ACTION_DRAG_LOCATION:
                            updateDragSessionLocation(event.getLocalState(),
                                    slot.getLeft() + event.getX(), slot.getTop() + event.getY());
                            setDropHighlight(slot);
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                            if (mHighlightedDropCell == slot) {
                                clearDropHighlight();
                            }
                            return true;
                        case DragEvent.ACTION_DROP:
                            clearDropHighlight();
                            return handleDropOnSlot(col, row, null,
                                    slot.getLeft() + event.getX(), slot.getTop() + event.getY(), event);
                        case DragEvent.ACTION_DRAG_ENDED:
                            clearDropHighlight();
                            return true;
                    }
                    return false;
                }
            });
            return slot;
        }

        private View createDesktopShortcutCell(
                final Context context,
                final DesktopShortcutItem item,
                final int slotCol,
                final int slotRow,
                GridLayout.LayoutParams lp,
                int iconSizePx) {
            final LinearLayout cell = new LinearLayout(context);
            cell.setTag(item);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            int pad = dpToPx(context, 6);
            cell.setPadding(pad, dpToPx(context, 8), pad, pad);
            if (Build.VERSION.SDK_INT >= 23) {
                cell.setContextClickable(true);
            }
            cell.setLayoutParams(lp);

            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(item.icon);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            iconLp.bottomMargin = dpToPx(context, 5);
            cell.addView(iconView, iconLp);

            TextView labelView = new TextView(context);
            labelView.setText(item.label);
            labelView.setTextColor(Color.rgb(249, 250, 251));
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
            labelView.setGravity(Gravity.CENTER_HORIZONTAL);
            labelView.setMaxLines(2);
            labelView.setEllipsize(TextUtils.TruncateAt.END);
            labelView.setShadowLayer(4.5f, 0f, 1.5f, Color.argb(225, 0, 0, 0));
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cell.addView(labelView, labelLp);

            cell.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean requireDoubleClick = ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_DOUBLE_CLICK, false);
                    long now = System.currentTimeMillis();
                    if (requireDoubleClick) {
                        selectCell(cell, item);
                        if (item.storageKey.equals(mLastClickedKey) && (now - mLastClickTime) < 420L) {
                            mLastClickTime = 0L;
                            launchShortcut(context, item, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                        } else {
                            mLastClickedKey = item.storageKey;
                            mLastClickTime = now;
                        }
                    } else {
                        selectCell(cell, item);
                        launchShortcut(context, item, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                    }
                }
            });

            final float[] lastTouchLocal = new float[]{lp.width * 0.5f, lp.height * 0.5f};

            cell.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    selectCell(cell, item);
                    // Start drag immediately so finger drag works seamlessly; stationary release opens menu
                    startCellDrag(cell, item, cell.getLeft() + lastTouchLocal[0], cell.getTop() + lastTouchLocal[1], true);
                    return true;
                }
            });

            if (Build.VERSION.SDK_INT >= 23) {
                cell.setOnContextClickListener(new OnContextClickListener() {
                    @Override
                    public boolean onContextClick(View v) {
                        long now = SystemClock.uptimeMillis();
                        if (now - mLastSecondaryMenuTime < 180L) return true;
                        mLastSecondaryMenuTime = now;
                        selectCell(cell, item);
                        showShortcutContextMenu(context, cell, item);
                        return true;
                    }
                });
            }

            cell.setOnHoverListener(new OnHoverListener() {
                @Override
                public boolean onHover(View v, MotionEvent event) {
                    if (cell == mSelectedCell || cell == mHighlightedDropCell) return false;
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_HOVER_ENTER) {
                        GradientDrawable hoverBg = new GradientDrawable();
                        hoverBg.setColor(Color.argb(38, 255, 255, 255));
                        hoverBg.setCornerRadius(dpToPx(context, 10));
                        cell.setBackground(hoverBg);
                    } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                        restoreCellBackground(cell);
                    }
                    return false;
                }
            });

            // Immediate left-button drag for mouse and stylus
            final int dragSlop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), dpToPx(context, 8));
            cell.setOnTouchListener(new OnTouchListener() {
                private float downX, downY;
                private boolean dragStarted = false;
                private boolean allowImmediateDrag = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        downX = event.getRawX();
                        downY = event.getRawY();
                        lastTouchLocal[0] = event.getX();
                        lastTouchLocal[1] = event.getY();
                        dragStarted = false;
                        int tool = event.getToolType(0);
                        boolean primaryOnly = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0;
                        allowImmediateDrag = primaryOnly
                                && (tool == MotionEvent.TOOL_TYPE_MOUSE || tool == MotionEvent.TOOL_TYPE_STYLUS);
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        lastTouchLocal[0] = event.getX();
                        lastTouchLocal[1] = event.getY();
                        if (allowImmediateDrag && !dragStarted) {
                            float dx = event.getRawX() - downX;
                            float dy = event.getRawY() - downY;
                            if ((dx * dx + dy * dy) > (dragSlop * dragSlop)) {
                                dragStarted = true;
                                startCellDrag(cell, item, cell.getLeft() + event.getX(), cell.getTop() + event.getY(), false);
                            }
                        }
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        allowImmediateDrag = false;
                        dragStarted = false;
                    }
                    return false;
                }
            });

            cell.setOnDragListener(new OnDragListener() {
                @Override
                public boolean onDrag(View v, DragEvent event) {
                    if (!isSupportedDragEvent(event)) return false;
                    Object local = event.getLocalState();
                    String draggedKey = (local instanceof DragSession) ? ((DragSession) local).item.storageKey : null;
                    switch (event.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED:
                            return true;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            if (!item.storageKey.equals(draggedKey)) {
                                setDropHighlight(cell);
                            }
                            return true;
                        case DragEvent.ACTION_DRAG_LOCATION:
                            updateDragSessionLocation(local, cell.getLeft() + event.getX(), cell.getTop() + event.getY());
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                            if (mHighlightedDropCell == cell) {
                                clearDropHighlight();
                            }
                            return true;
                        case DragEvent.ACTION_DROP:
                            clearDropHighlight();
                            return handleDropOnSlot(slotCol, slotRow, item,
                                    cell.getLeft() + event.getX(), cell.getTop() + event.getY(), event);
                        case DragEvent.ACTION_DRAG_ENDED:
                            cell.setAlpha(1.0f);
                            restoreCellBackground(cell);
                            return true;
                    }
                    return false;
                }
            });

            return cell;
        }

        private void startCellDrag(
                View cell,
                DesktopShortcutItem item,
                float startGridX,
                float startGridY,
                boolean openMenuIfStationary) {
            try {
                ClipData clip = ClipData.newPlainText("dexplus_shortcut", item.storageKey);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(cell);
                DragSession session = new DragSession(item, cell, startGridX, startGridY, openMenuIfStationary);
                cell.startDragAndDrop(clip, shadow, session, 0);
                cell.setAlpha(0.35f);
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": startCellDrag error: " + t);
            }
        }

        private void updateDragSessionLocation(Object localState, float gx, float gy) {
            if (localState instanceof DragSession) {
                ((DragSession) localState).recordGridPoint(gx, gy);
            }
        }

        private boolean handleDropOnGrid(float gx, float gy, DesktopShortcutItem targetItem, DragEvent event) {
            int[] slot = gridCoordsToSlot(gx, gy);
            View slotView = findViewAtSlot(slot[0], slot[1]);
            DesktopShortcutItem resolvedTarget = targetItem;
            if (resolvedTarget == null && slotView != null && slotView.getTag() instanceof DesktopShortcutItem) {
                resolvedTarget = (DesktopShortcutItem) slotView.getTag();
            }
            return handleDropOnSlot(slot[0], slot[1], resolvedTarget, gx, gy, event);
        }

        private boolean handleDropOnSlot(
                int col,
                int row,
                DesktopShortcutItem targetItem,
                float gx,
                float gy,
                DragEvent event) {
            final Context ctx = getContext();
            Object local = event.getLocalState();

            if (local instanceof DragSession) {
                final DragSession session = (DragSession) local;
                session.recordGridPoint(gx, gy);
                session.handledDrop = true;
                float stationaryLimit = Math.max(ViewConfiguration.get(ctx).getScaledTouchSlop(), dpToPx(ctx, 10)) * 2.0f;
                if (session.openMenuIfStationary
                        && session.maxDistSq <= (stationaryLimit * stationaryLimit)
                        && (targetItem == null || session.item.storageKey.equals(targetItem.storageKey))) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (session.sourceCell != null && session.sourceCell.isAttachedToWindow()) {
                                selectCell(session.sourceCell, session.item);
                                showShortcutContextMenu(ctx, session.sourceCell, session.item);
                            }
                        }
                    });
                    return true;
                }

                boolean freePlacement = ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, false);
                if (freePlacement) {
                    assignOrSwapSlot(ctx, session.item.storageKey, col, row);
                    rebuildGrid();
                    return true;
                }
                if (targetItem != null && !session.item.storageKey.equals(targetItem.storageKey)) {
                    moveShortcut(ctx, session.item.storageKey, targetItem.storageKey);
                    rebuildGrid();
                } else if (targetItem == null) {
                    moveShortcut(ctx, session.item.storageKey, null);
                    rebuildGrid();
                }
                return true;
            }

            // External drop from One UI Home App Drawer / OverlayApps / Finder / OpenFolder
            boolean freePlacement = ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, false);
            return handleExternalDragDrop(ctx, event, col, row, targetItem, freePlacement);
        }

        private void finishDragSession(DragEvent event) {
            Object local = event.getLocalState();
            if (local instanceof DragSession) {
                final DragSession session = (DragSession) local;
                if (session.sourceCell != null) {
                    session.sourceCell.setAlpha(1.0f);
                    restoreCellBackground(session.sourceCell);
                }
                if (session.openMenuIfStationary && !session.handledDrop) {
                    Context ctx = getContext();
                    float stationaryLimit = Math.max(ViewConfiguration.get(ctx).getScaledTouchSlop(), dpToPx(ctx, 10)) * 2.0f;
                    if (session.maxDistSq <= (stationaryLimit * stationaryLimit)) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if (session.sourceCell != null && session.sourceCell.isAttachedToWindow()) {
                                    selectCell(session.sourceCell, session.item);
                                    showShortcutContextMenu(getContext(), session.sourceCell, session.item);
                                }
                            }
                        });
                    }
                }
            } else {
                sLastHoneyDragInfo = null;
            }
        }

        private boolean handleExternalDragDrop(
                Context ctx,
                DragEvent event,
                int targetCol,
                int targetRow,
                DesktopShortcutItem targetItem,
                boolean freePlacement) {
            Object dragInfo = extractHoneyDragInfo(event);
            boolean anyAdded = false;
            int colCursor = targetCol;
            int rowCursor = targetRow;
            int maxCols = Math.max(1, mCurrentCols);

            if (dragInfo != null) {
                try {
                    Object dragItemsObj = XposedHelpers.callMethod(dragInfo, "getDragItems");
                    if (dragItemsObj instanceof List<?>) {
                        List<?> dragItems = (List<?>) dragItemsObj;
                        for (Object dragItem : dragItems) {
                            if (dragItem == null) continue;
                            try {
                                Object viewObj = XposedHelpers.callMethod(dragItem, "getView");
                                if (viewObj instanceof View) {
                                    final View srcView = (View) viewObj;
                                    srcView.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            srcView.setVisibility(View.VISIBLE);
                                            srcView.setAlpha(1.0f);
                                        }
                                    });
                                }
                            } catch (Throwable ignored) {}

                            Object baseItem = null;
                            try {
                                baseItem = XposedHelpers.callMethod(dragItem, "getItem");
                            } catch (Throwable ignored) {}
                            if (baseItem != null) {
                                String addedKey = addOrUpdateBaseItem(
                                        ctx,
                                        baseItem,
                                        freePlacement ? colCursor : null,
                                        freePlacement ? rowCursor : null,
                                        dragItems.size() == 1,
                                        false);
                                if (addedKey != null) {
                                    anyAdded = true;
                                    if (!freePlacement && targetItem != null && !addedKey.equals(targetItem.storageKey)) {
                                        moveShortcut(ctx, addedKey, targetItem.storageKey);
                                    }
                                    colCursor++;
                                    if (colCursor >= maxCols) {
                                        colCursor = 0;
                                        rowCursor++;
                                    }
                                }
                            }
                        }
                    }
                    if (anyAdded) {
                        try {
                            Object cb = XposedHelpers.callMethod(dragInfo, "getSuccessCallback");
                            if (cb != null) XposedHelpers.callMethod(cb, "invoke");
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    XposedBridge.log(ModConfig.TAG + ": handleExternalDragDrop(DragInfo) error: " + t);
                }
                sLastHoneyDragInfo = null;
                if (anyAdded) {
                    rebuildGrid();
                    return true;
                }
            }

            // Fallback: inspect ClipData intents (OverlayApps / add_icon_other_window / external drags)
            ClipData clip = event.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    ClipData.Item ci = clip.getItemAt(i);
                    if (ci == null) continue;
                    Intent clipIntent = ci.getIntent();
                    if (clipIntent != null) {
                        String addedKey = addClipIntentToWorkspace(
                                ctx,
                                clipIntent,
                                freePlacement ? colCursor : null,
                                freePlacement ? rowCursor : null);
                        if (addedKey != null) {
                            anyAdded = true;
                            if (!freePlacement && targetItem != null && !addedKey.equals(targetItem.storageKey)) {
                                moveShortcut(ctx, addedKey, targetItem.storageKey);
                            }
                            colCursor++;
                            if (colCursor >= maxCols) {
                                colCursor = 0;
                                rowCursor++;
                            }
                        }
                    }
                }
                if (anyAdded) {
                    rebuildGrid();
                    return true;
                }
            }
            return false;
        }

        private void setDropHighlight(View target) {
            if (mHighlightedDropCell == target) return;
            clearDropHighlight();
            mHighlightedDropCell = target;
            if (target != null) {
                GradientDrawable dropBg = new GradientDrawable();
                dropBg.setColor(Color.argb(72, 56, 189, 248));
                dropBg.setCornerRadius(dpToPx(getContext(), 10));
                dropBg.setStroke(dpToPx(getContext(), 2), Color.argb(220, 125, 211, 252));
                target.setBackground(dropBg);
            }
        }

        private void clearDropHighlight() {
            View prev = mHighlightedDropCell;
            mHighlightedDropCell = null;
            if (prev != null) {
                restoreCellBackground(prev);
            }
        }

        private void restoreCellBackground(View cell) {
            if (cell == mSelectedCell) {
                selectCell(cell, mSelectedItem);
            } else {
                cell.setBackground(null);
            }
        }

        private void selectCell(View cell, DesktopShortcutItem item) {
            clearSelection();
            mSelectedCell = cell;
            mSelectedItem = item;
            if (cell != null) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setColor(Color.argb(65, 59, 130, 246));
                selBg.setCornerRadius(dpToPx(getContext(), 10));
                selBg.setStroke(dpToPx(getContext(), 1), Color.argb(160, 147, 197, 253));
                cell.setBackground(selBg);
            }
        }

        private void clearSelection() {
            View prev = mSelectedCell;
            mSelectedCell = null;
            mSelectedItem = null;
            if (prev != null) {
                restoreCellBackground(prev);
            }
        }

        private void showShortcutContextMenu(final Context context, View anchor, final DesktopShortcutItem item) {
            final int idOpenWin = 1;
            final int idOpenFull = 2;
            final int idAppInfo = 3;
            final int idRemove = 4;
            final int idUninstall = 5;

            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(0, idOpenWin, 0, "Open in window");
            popup.getMenu().add(0, idOpenFull, 1, "Open fullscreen");
            if (!TextUtils.isEmpty(item.getPackageName())) {
                popup.getMenu().add(0, idAppInfo, 2, "App info");
            }
            popup.getMenu().add(0, idRemove, 3, "Remove from workspace");
            if (!item.isCustomShortcut && item.component != null) {
                popup.getMenu().add(0, idUninstall, 4, "Uninstall");
            }

            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                    int id = menuItem.getItemId();
                    if (id == idOpenWin) {
                        launchShortcut(context, item, true);
                        return true;
                    } else if (id == idOpenFull) {
                        launchShortcut(context, item, false);
                        return true;
                    } else if (id == idAppInfo) {
                        openAppInfo(context, item.getPackageName());
                        return true;
                    } else if (id == idRemove) {
                        removeShortcutFromDesktop(context, item.storageKey);
                        rebuildGrid();
                        return true;
                    } else if (id == idUninstall && item.component != null) {
                        try {
                            Intent uninstall = new Intent(Intent.ACTION_DELETE,
                                    Uri.fromParts("package", item.component.getPackageName(), null));
                            uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(uninstall);
                        } catch (Throwable ignored) {}
                        return true;
                    }
                    return false;
                }
            });
            popup.show();
        }

        private void showEmptyDesktopMenu(float x, float y) {
            final Context context = getContext();
            int[] slot = viewCoordsToGridSlot(x, y);
            mPendingAddCol = slot[0];
            mPendingAddRow = slot[1];
            View anchor = prepareAnchorAt(x, y);
            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(0, 1, 0, "Add app shortcut...");
            popup.getMenu().add(0, 2, 1, "Sort icons by name (A–Z)");
            popup.getMenu().add(0, 3, 2, "Refresh workspace");
            popup.getMenu().add(0, 4, 3, "Reset default shortcuts");
            popup.getMenu().add(0, 5, 4, "Wallpaper & style");
            popup.getMenu().add(0, 6, 5, "Dex+ Settings");
            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    switch (item.getItemId()) {
                        case 1:
                            showAddAppShortcutDialog(context);
                            return true;
                        case 2:
                            sortShortcutsAlphabetically(context);
                            rebuildGrid();
                            return true;
                        case 3:
                            rebuildGrid();
                            return true;
                        case 4:
                            resetDesktopShortcuts(context);
                            rebuildGrid();
                            return true;
                        case 5:
                            openWallpaperAndStyle(context);
                            return true;
                        case 6:
                            openModuleSettings(context);
                            return true;
                    }
                    return false;
                }
            });
            popup.show();
        }

        private void showAddAppShortcutDialog(final Context context) {
            try {
                final List<DesktopShortcutItem> allApps = queryAllLaunchableApps(context);
                if (allApps.isEmpty()) return;

                final Set<String> existingKeys = new LinkedHashSet<>(readSavedKeyOrder(context));

                LinearLayout root = new LinearLayout(context);
                root.setOrientation(LinearLayout.VERTICAL);
                int pad = dpToPx(context, 16);
                root.setPadding(pad, dpToPx(context, 10), pad, 0);

                EditText searchBox = new EditText(context);
                searchBox.setHint("Search apps...");
                searchBox.setSingleLine(true);
                LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                searchLp.bottomMargin = dpToPx(context, 8);
                root.addView(searchBox, searchLp);

                final List<DesktopShortcutItem> filtered = new ArrayList<>(allApps);
                final BaseAdapter adapter = new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return filtered.size();
                    }

                    @Override
                    public DesktopShortcutItem getItem(int position) {
                        return filtered.get(position);
                    }

                    @Override
                    public long getItemId(int position) {
                        return position;
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        LinearLayout row;
                        ImageView iconView;
                        TextView titleView;
                        TextView badgeView;
                        if (convertView instanceof LinearLayout) {
                            row = (LinearLayout) convertView;
                            iconView = (ImageView) row.getChildAt(0);
                            titleView = (TextView) row.getChildAt(1);
                            badgeView = (TextView) row.getChildAt(2);
                        } else {
                            row = new LinearLayout(context);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(dpToPx(context, 4), dpToPx(context, 8), dpToPx(context, 4), dpToPx(context, 8));

                            iconView = new ImageView(context);
                            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dpToPx(context, 32), dpToPx(context, 32));
                            ivLp.rightMargin = dpToPx(context, 12);
                            row.addView(iconView, ivLp);

                            titleView = new TextView(context);
                            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                            row.addView(titleView, tvLp);

                            badgeView = new TextView(context);
                            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                            badgeView.setTextColor(Color.rgb(96, 165, 250));
                            row.addView(badgeView);
                        }
                        DesktopShortcutItem item = getItem(position);
                        iconView.setImageDrawable(item.icon);
                        titleView.setText(item.label);
                        boolean alreadyAdded = existingKeys.contains(item.storageKey);
                        badgeView.setVisibility(alreadyAdded ? View.VISIBLE : View.GONE);
                        badgeView.setText("Added");
                        return row;
                    }
                };

                ListView listView = new ListView(context);
                listView.setAdapter(adapter);
                LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(context, 340));
                root.addView(listView, lvLp);

                searchBox.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        String q = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                        filtered.clear();
                        for (DesktopShortcutItem item : allApps) {
                            if (q.isEmpty() || item.label.toLowerCase(Locale.ROOT).contains(q)
                                    || item.getPackageName().toLowerCase(Locale.ROOT).contains(q)) {
                                filtered.add(item);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                });

                final AlertDialog dialog = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Add App Shortcut to Workspace")
                        .setView(root)
                        .setNegativeButton("Close", null)
                        .create();

                listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        if (position >= 0 && position < filtered.size()) {
                            DesktopShortcutItem picked = filtered.get(position);
                            addShortcutToDesktop(context, picked.storageKey);
                            if (ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, false)
                                    && mPendingAddCol >= 0 && mPendingAddRow >= 0) {
                                assignOrSwapSlot(context, picked.storageKey, mPendingAddCol, mPendingAddRow);
                                mPendingAddCol = -1;
                                mPendingAddRow = -1;
                            }
                            existingKeys.add(picked.storageKey);
                            adapter.notifyDataSetChanged();
                            rebuildGrid();
                            Toast.makeText(context, "Added " + picked.label, Toast.LENGTH_SHORT).show();
                        }
                    }
                });

                dialog.show();
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": showAddAppShortcutDialog error: " + t);
            }
        }
    }

    private static final class DesktopShortcutItem {
        final String storageKey;
        final ComponentName component;
        final Intent customIntent;
        final String customPackage;
        final String label;
        final Drawable icon;
        final boolean isCustomShortcut;

        DesktopShortcutItem(ComponentName component, String label, Drawable icon) {
            this.storageKey = component.flattenToString();
            this.component = component;
            this.customIntent = null;
            this.customPackage = component.getPackageName();
            this.label = label;
            this.icon = icon;
            this.isCustomShortcut = false;
        }

        DesktopShortcutItem(String storageKey, Intent customIntent, String customPackage, String label, Drawable icon) {
            this.storageKey = storageKey;
            this.component = null;
            this.customIntent = customIntent;
            this.customPackage = customPackage != null ? customPackage : "";
            this.label = label;
            this.icon = icon;
            this.isCustomShortcut = true;
        }

        String getPackageName() {
            if (component != null) return component.getPackageName();
            return customPackage != null ? customPackage : "";
        }
    }

    private static SharedPreferences getDesktopPrefs(Context context) {
        return context.getSharedPreferences(DESKTOP_PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> readSavedKeyOrder(Context context) {
        SharedPreferences prefs = getDesktopPrefs(context);
        String raw = prefs.getString(KEY_SHORTCUT_LIST, "");
        List<String> keys = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            Set<String> seen = new LinkedHashSet<>();
            for (String s : raw.split(";")) {
                if (s != null && !s.isEmpty() && seen.add(s)) {
                    keys.add(s);
                }
            }
        }
        return keys;
    }

    private static List<DesktopShortcutItem> loadDesktopShortcuts(Context context, int maxCount) {
        SharedPreferences prefs = getDesktopPrefs(context);
        String savedRaw = prefs.getString(KEY_SHORTCUT_LIST, null);
        PackageManager pm = context.getPackageManager();
        List<DesktopShortcutItem> result = new ArrayList<>();

        if (savedRaw != null && !savedRaw.trim().isEmpty()) {
            String[] parts = savedRaw.split(";");
            for (String key : parts) {
                if (key == null || key.isEmpty()) continue;
                if (key.startsWith(CUSTOM_SHORTCUT_PREFIX)) {
                    DesktopShortcutItem custom = loadCustomShortcut(context, key);
                    if (custom != null) {
                        result.add(custom);
                    }
                    continue;
                }
                ComponentName cn = ComponentName.unflattenFromString(key);
                if (cn == null) continue;
                try {
                    android.content.pm.ActivityInfo ai = pm.getActivityInfo(cn, 0);
                    CharSequence labelCs = ai.loadLabel(pm);
                    String label = labelCs != null ? labelCs.toString() : cn.getClassName();
                    Drawable icon = ai.loadIcon(pm);
                    if (icon != null) {
                        result.add(new DesktopShortcutItem(cn, label, icon));
                    }
                } catch (Throwable ignored) {}
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        // Initialize default Classic DeX Desktop shortcuts
        List<DesktopShortcutItem> allApps = queryAllLaunchableApps(context);
        String[] preferredPackages = new String[]{
                "com.udunus.dexplus",
                "com.sec.android.app.myfiles",
                "com.sec.android.app.sbrowser",
                "com.android.chrome",
                "com.sec.android.gallery3d",
                "com.android.settings",
                "com.android.vending",
                "com.samsung.android.app.notes",
                "com.google.android.youtube",
                "com.sec.android.app.popupcalculator",
                "com.samsung.android.calendar",
                "com.sec.android.app.clockpackage"
        };

        Set<String> addedKeys = new LinkedHashSet<>();
        for (String prefPkg : preferredPackages) {
            for (DesktopShortcutItem item : allApps) {
                if (prefPkg.equals(item.getPackageName()) && !addedKeys.contains(item.storageKey)) {
                    result.add(item);
                    addedKeys.add(item.storageKey);
                    break;
                }
            }
        }

        for (DesktopShortcutItem item : allApps) {
            if (result.size() >= Math.min(16, maxCount)) break;
            if (!addedKeys.contains(item.storageKey)
                    && !"com.sec.android.app.launcher".equals(item.getPackageName())) {
                result.add(item);
                addedKeys.add(item.storageKey);
            }
        }

        saveShortcutList(context, addedKeys);
        return result;
    }

    private static String buildCustomShortcutStorageKey(String label, Intent intent) {
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        long hash = 1125899906842597L;
        String seed = (label != null ? label : "") + "|" + uri;
        for (int i = 0; i < seed.length(); i++) {
            hash = 31L * hash + seed.charAt(i);
        }
        return CUSTOM_SHORTCUT_PREFIX + Long.toHexString(hash);
    }

    private static File getCustomShortcutDir(Context context) {
        File dir = new File(context.getFilesDir(), CUSTOM_SHORTCUT_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static String sanitizeFileId(String key) {
        return key.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static boolean saveAndAddCustomShortcut(
            Context context,
            String key,
            String label,
            Intent intent,
            Drawable icon,
            String pkg) {
        try {
            File dir = getCustomShortcutDir(context);
            String fileId = sanitizeFileId(key);
            File metaFile = new File(dir, fileId + ".meta");
            try (BufferedWriter bw = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(metaFile), StandardCharsets.UTF_8))) {
                bw.write(label != null ? label.replace('\n', ' ') : "Shortcut");
                bw.newLine();
                bw.write(intent.toUri(Intent.URI_INTENT_SCHEME));
                bw.newLine();
                bw.write(pkg != null ? pkg : "");
                bw.newLine();
            }

            if (icon != null) {
                Bitmap bmp = drawableToBitmap(icon, 192);
                if (bmp != null) {
                    File iconFile = new File(dir, fileId + ".png");
                    try (FileOutputStream fos = new FileOutputStream(iconFile)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 95, fos);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": saveAndAddCustomShortcut error: " + t);
        }
        return addShortcutToDesktop(context, key);
    }

    private static DesktopShortcutItem loadCustomShortcut(Context context, String key) {
        try {
            File dir = new File(context.getFilesDir(), CUSTOM_SHORTCUT_DIR);
            String fileId = sanitizeFileId(key);
            File metaFile = new File(dir, fileId + ".meta");
            if (!metaFile.exists()) return null;

            String label;
            String intentUri;
            String pkg;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(metaFile), StandardCharsets.UTF_8))) {
                label = br.readLine();
                intentUri = br.readLine();
                pkg = br.readLine();
            }
            if (TextUtils.isEmpty(intentUri)) return null;
            Intent intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);

            Drawable icon = null;
            File iconFile = new File(dir, fileId + ".png");
            if (iconFile.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                if (bmp != null) {
                    icon = new BitmapDrawable(context.getResources(), bmp);
                }
            }
            if (icon == null && !TextUtils.isEmpty(pkg)) {
                try {
                    icon = context.getPackageManager().getApplicationIcon(pkg);
                } catch (Throwable ignored) {}
            }
            if (icon == null) {
                icon = context.getPackageManager().getDefaultActivityIcon();
            }
            return new DesktopShortcutItem(
                    key,
                    intent,
                    pkg,
                    !TextUtils.isEmpty(label) ? label : "Shortcut",
                    icon
            );
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": loadCustomShortcut error: " + t);
            return null;
        }
    }

    private static void deleteCustomShortcutFiles(Context context, String key) {
        if (context == null || key == null || !key.startsWith(CUSTOM_SHORTCUT_PREFIX)) return;
        try {
            File dir = new File(context.getFilesDir(), CUSTOM_SHORTCUT_DIR);
            String fileId = sanitizeFileId(key);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, fileId + ".meta").delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, fileId + ".png").delete();
        } catch (Throwable ignored) {}
    }

    private static boolean isSupportedDragEvent(DragEvent event) {
        if (event == null) return false;
        Object local = event.getLocalState();
        if (local instanceof ClassicDexDesktopView.DragSession) return true;
        if (extractHoneyDragInfo(event) != null) return true;
        try {
            if (event.getClipData() != null && event.getClipData().getItemCount() > 0) {
                return true;
            }
            if (event.getClipDescription() != null) {
                return event.getClipDescription().hasMimeType("text/vnd.android.intent")
                        || event.getClipDescription().hasMimeType("text/plain");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isHoneyDragInfo(Object obj) {
        return obj != null && "com.honeyspace.sdk.DragInfo".equals(obj.getClass().getName());
    }

    private static Object extractHoneyDragInfo(DragEvent event) {
        if (event != null && isHoneyDragInfo(event.getLocalState())) {
            return event.getLocalState();
        }
        Object cached = sLastHoneyDragInfo;
        if (isHoneyDragInfo(cached) && (SystemClock.uptimeMillis() - sLastHoneyDragTime) < 60000L) {
            return cached;
        }
        return null;
    }

    private static String addClipIntentToWorkspace(Context ctx, Intent clipIntent, Integer targetCol, Integer targetRow) {
        if (ctx == null || clipIntent == null) return null;
        try {
            // 1. Direct ComponentName inside ClipData intent
            ComponentName cn = clipIntent.getComponent();
            if (cn == null) {
                Object compExtra = clipIntent.getParcelableExtra("android.intent.extra.COMPONENT_NAME");
                if (compExtra instanceof ComponentName) cn = (ComponentName) compExtra;
            }

            // 2. Unwrap PendingIntent if Samsung DeX / OverlayApps packed EXTRA_PENDING_INTENT
            if (cn == null && clipIntent.hasExtra("android.intent.extra.PENDING_INTENT")) {
                Object piObj = clipIntent.getParcelableExtra("android.intent.extra.PENDING_INTENT");
                if (piObj instanceof PendingIntent) {
                    PendingIntent pi = (PendingIntent) piObj;
                    try {
                        Object inner = XposedHelpers.callMethod(pi, "getIntent");
                        if (inner instanceof Intent && ((Intent) inner).getComponent() != null) {
                            cn = ((Intent) inner).getComponent();
                        }
                    } catch (Throwable ignored) {}
                    if (cn == null) {
                        String creatorPkg = pi.getCreatorPackage();
                        if (!TextUtils.isEmpty(creatorPkg)) {
                            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(creatorPkg);
                            if (launch != null) cn = launch.getComponent();
                        }
                    }
                }
            }

            if (cn != null) {
                String flat = cn.flattenToString();
                addShortcutToDesktop(ctx, flat);
                if (targetCol != null && targetRow != null) {
                    assignOrSwapSlot(ctx, flat, targetCol, targetRow);
                }
                return flat;
            }

            // 3. Pinned / deep shortcut extras packed in ClipData intent
            String scId = clipIntent.getStringExtra("shortcut_id");
            if (TextUtils.isEmpty(scId)) scId = clipIntent.getStringExtra("android.intent.extra.shortcut.ID");
            String pkg = clipIntent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            if (TextUtils.isEmpty(pkg)) pkg = clipIntent.getPackage();
            if (!TextUtils.isEmpty(scId) && !TextUtils.isEmpty(pkg)) {
                Intent deep = new Intent(Intent.ACTION_MAIN);
                deep.addCategory("com.android.launcher3.DEEP_SHORTCUT");
                deep.setPackage(pkg);
                deep.putExtra("shortcut_id", scId);
                String label = clipIntent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
                if (TextUtils.isEmpty(label)) label = pkg;
                String key = buildCustomShortcutStorageKey(label, deep);
                saveAndAddCustomShortcut(ctx, key, label, deep, null, pkg);
                if (targetCol != null && targetRow != null) {
                    assignOrSwapSlot(ctx, key, targetCol, targetRow);
                }
                return key;
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": addClipIntentToWorkspace error: " + t);
        }
        return null;
    }

    private static Map<String, int[]> loadShortcutPositions(Context context) {
        Map<String, int[]> map = new HashMap<>();
        String raw = getDesktopPrefs(context).getString(KEY_SHORTCUT_POSITIONS, "");
        if (TextUtils.isEmpty(raw)) return map;
        for (String line : raw.split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0 || tab >= line.length() - 1) continue;
            String key = line.substring(0, tab);
            String[] coords = line.substring(tab + 1).split(",");
            if (coords.length != 2) continue;
            try {
                int c = Integer.parseInt(coords[0].trim());
                int r = Integer.parseInt(coords[1].trim());
                if (c >= 0 && r >= 0) {
                    map.put(key, new int[]{c, r});
                }
            } catch (NumberFormatException ignored) {}
        }
        return map;
    }

    private static void saveShortcutPositions(Context context, Map<String, int[]> positions) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : positions.entrySet()) {
            String key = e.getKey();
            int[] pos = e.getValue();
            if (TextUtils.isEmpty(key) || pos == null || pos.length != 2) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append('\t').append(pos[0]).append(',').append(pos[1]);
        }
        getDesktopPrefs(context).edit().putString(KEY_SHORTCUT_POSITIONS, sb.toString()).commit();
    }

    private static Map<String, int[]> resolveGridPlacements(
            Context context,
            List<DesktopShortcutItem> items,
            int cols,
            int minRows) {
        Map<String, int[]> saved = loadShortcutPositions(context);
        Map<String, int[]> resolved = new HashMap<>();
        Set<String> occupied = new HashSet<>();
        boolean dirty = false;

        // First keep any valid saved non-colliding positions
        for (DesktopShortcutItem item : items) {
            int[] pos = saved.get(item.storageKey);
            if (pos != null && pos[0] >= 0 && pos[0] < cols && pos[1] >= 0) {
                String slotId = pos[0] + "," + pos[1];
                if (occupied.add(slotId)) {
                    resolved.put(item.storageKey, new int[]{pos[0], pos[1]});
                } else {
                    dirty = true;
                }
            }
        }

        // Assign first free column-major slots for new or colliding items
        for (DesktopShortcutItem item : items) {
            if (resolved.containsKey(item.storageKey)) continue;
            dirty = true;
            int[] free = findFirstFreeSlot(occupied, cols, minRows);
            occupied.add(free[0] + "," + free[1]);
            resolved.put(item.storageKey, free);
        }

        if (dirty || saved.size() != resolved.size()) {
            saveShortcutPositions(context, resolved);
        }
        return resolved;
    }

    private static int[] findFirstFreeSlot(Set<String> occupied, int cols, int minRows) {
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < minRows; r++) {
                if (!occupied.contains(c + "," + r)) {
                    return new int[]{c, r};
                }
            }
        }
        for (int r = minRows; ; r++) {
            for (int c = 0; c < cols; c++) {
                if (!occupied.contains(c + "," + r)) {
                    return new int[]{c, r};
                }
            }
        }
    }

    private static void assignOrSwapSlot(Context context, String draggedKey, int targetCol, int targetRow) {
        if (context == null || TextUtils.isEmpty(draggedKey) || targetCol < 0 || targetRow < 0) return;
        int cols = Math.max(6, Math.min(16, ModConfig.getInt(context, ModConfig.KEY_DESKTOP_GRID_COLS, 10)));
        int rows = Math.max(4, Math.min(10, ModConfig.getInt(context, ModConfig.KEY_DESKTOP_GRID_ROWS, 5)));
        int clampedCol = Math.min(cols - 1, targetCol);

        List<DesktopShortcutItem> items = loadDesktopShortcuts(context, cols * rows);
        Map<String, int[]> placements = resolveGridPlacements(context, items, cols, rows);

        int[] draggedOldPos = placements.get(draggedKey);
        String occupantKey = null;
        for (Map.Entry<String, int[]> e : placements.entrySet()) {
            int[] pos = e.getValue();
            if (pos != null && pos[0] == clampedCol && pos[1] == targetRow) {
                occupantKey = e.getKey();
                break;
            }
        }

        if (draggedKey.equals(occupantKey)) return;

        if (occupantKey != null) {
            if (draggedOldPos != null) {
                placements.put(occupantKey, new int[]{draggedOldPos[0], draggedOldPos[1]});
            } else {
                Set<String> occupied = new HashSet<>();
                for (Map.Entry<String, int[]> e : placements.entrySet()) {
                    if (!occupantKey.equals(e.getKey())) {
                        occupied.add(e.getValue()[0] + "," + e.getValue()[1]);
                    }
                }
                occupied.add(clampedCol + "," + targetRow);
                placements.put(occupantKey, findFirstFreeSlot(occupied, cols, rows));
            }
        }
        placements.put(draggedKey, new int[]{clampedCol, targetRow});
        saveShortcutPositions(context, placements);
    }

    private static void resetDesktopShortcuts(Context context) {
        getDesktopPrefs(context).edit()
                .remove(KEY_SHORTCUT_LIST)
                .remove(KEY_SHORTCUT_POSITIONS)
                .commit();
        try {
            File dir = new File(context.getFilesDir(), CUSTOM_SHORTCUT_DIR);
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int maxSizePx) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) drawable).getBitmap();
            if (b != null) return b;
        }
        int w = drawable.getIntrinsicWidth() > 0 ? Math.min(maxSizePx, drawable.getIntrinsicWidth()) : maxSizePx;
        int h = drawable.getIntrinsicHeight() > 0 ? Math.min(maxSizePx, drawable.getIntrinsicHeight()) : maxSizePx;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bmp;
    }

    private static List<DesktopShortcutItem> queryAllLaunchableApps(Context context) {
        List<DesktopShortcutItem> list = new ArrayList<>();
        try {
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserHandle user = Process.myUserHandle();
            if (launcherApps != null) {
                List<LauncherActivityInfo> activities = launcherApps.getActivityList(null, user);
                if (activities != null) {
                    for (LauncherActivityInfo info : activities) {
                        ComponentName cn = info.getComponentName();
                        if (cn == null || "com.sec.android.app.launcher".equals(cn.getPackageName())) continue;
                        CharSequence labelCs = info.getLabel();
                        String label = labelCs != null ? labelCs.toString() : cn.getPackageName();
                        Drawable icon = info.getBadgedIcon(0);
                        if (icon != null) {
                            list.add(new DesktopShortcutItem(cn, label, icon));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (list.isEmpty()) {
            try {
                PackageManager pm = context.getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolves = pm.queryIntentActivities(mainIntent, 0);
                for (ResolveInfo ri : resolves) {
                    if (ri.activityInfo == null) continue;
                    ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                    if ("com.sec.android.app.launcher".equals(cn.getPackageName())) continue;
                    CharSequence labelCs = ri.loadLabel(pm);
                    String label = labelCs != null ? labelCs.toString() : cn.getPackageName();
                    Drawable icon = ri.loadIcon(pm);
                    if (icon != null) {
                        list.add(new DesktopShortcutItem(cn, label, icon));
                    }
                }
            } catch (Throwable ignored) {}
        }

        Collections.sort(list, new Comparator<DesktopShortcutItem>() {
            @Override
            public int compare(DesktopShortcutItem a, DesktopShortcutItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    private static boolean addShortcutToDesktop(Context context, String storageKey) {
        List<String> keys = readSavedKeyOrder(context);
        if (keys.isEmpty()) {
            loadDesktopShortcuts(context, 16);
            keys = readSavedKeyOrder(context);
        }
        if (keys.contains(storageKey)) {
            return false;
        }
        keys.add(storageKey);
        saveShortcutList(context, new LinkedHashSet<>(keys));
        return true;
    }

    private static void removeShortcutFromDesktop(Context context, String storageKey) {
        List<String> keys = readSavedKeyOrder(context);
        keys.remove(storageKey);
        saveShortcutList(context, new LinkedHashSet<>(keys));
        Map<String, int[]> positions = loadShortcutPositions(context);
        if (positions.remove(storageKey) != null) {
            saveShortcutPositions(context, positions);
        }
        deleteCustomShortcutFiles(context, storageKey);
    }

    private static void moveShortcut(Context context, String draggedKey, String targetKey) {
        if (draggedKey == null) return;
        List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
        List<String> order = new ArrayList<>();
        for (DesktopShortcutItem item : current) {
            order.add(item.storageKey);
        }
        int fromIdx = order.indexOf(draggedKey);
        if (fromIdx < 0) return;

        order.remove(fromIdx);
        if (targetKey == null) {
            order.add(draggedKey);
        } else {
            int toIdx = order.indexOf(targetKey);
            if (toIdx < 0) {
                order.add(draggedKey);
            } else {
                // Insert before target when dragging backward, or at target's original slot when dragging forward
                if (fromIdx <= toIdx) {
                    order.add(Math.min(order.size(), toIdx + 1), draggedKey);
                } else {
                    order.add(toIdx, draggedKey);
                }
            }
        }
        saveShortcutList(context, new LinkedHashSet<>(order));
    }

    private static void sortShortcutsAlphabetically(Context context) {
        List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
        Collections.sort(current, new Comparator<DesktopShortcutItem>() {
            @Override
            public int compare(DesktopShortcutItem a, DesktopShortcutItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Set<String> set = new LinkedHashSet<>();
        for (DesktopShortcutItem item : current) {
            set.add(item.storageKey);
        }
        saveShortcutList(context, set);
        if (ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, false)) {
            int rows = Math.max(4, Math.min(10, ModConfig.getInt(context, ModConfig.KEY_DESKTOP_GRID_ROWS, 5)));
            Map<String, int[]> sortedPos = new HashMap<>();
            for (int i = 0; i < current.size(); i++) {
                sortedPos.put(current.get(i).storageKey, new int[]{i / rows, i % rows});
            }
            saveShortcutPositions(context, sortedPos);
        }
    }

    private static void saveShortcutList(Context context, Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String f : keys) {
            if (f == null || f.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(f);
        }
        getDesktopPrefs(context).edit().putString(KEY_SHORTCUT_LIST, sb.toString()).commit();
    }

    private static void launchShortcut(Context context, DesktopShortcutItem item, boolean freeform) {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            if (freeform) {
                try {
                    XposedHelpers.callMethod(opts, "setLaunchWindowingMode", 5); // WINDOWING_MODE_FREEFORM = 5
                } catch (Throwable ignored) {}
            }

            if (item.isCustomShortcut && item.customIntent != null) {
                Intent scIntent = item.customIntent;
                if (scIntent.hasCategory("com.android.launcher3.DEEP_SHORTCUT")) {
                    String shortcutId = scIntent.getStringExtra("shortcut_id");
                    if (TextUtils.isEmpty(shortcutId)) {
                        shortcutId = scIntent.getStringExtra("android.intent.extra.shortcut.ID");
                    }
                    String scPkg = scIntent.getPackage();
                    if (TextUtils.isEmpty(scPkg) && scIntent.getComponent() != null) {
                        scPkg = scIntent.getComponent().getPackageName();
                    }
                    if (TextUtils.isEmpty(scPkg)) {
                        scPkg = item.customPackage;
                    }
                    if (!TextUtils.isEmpty(scPkg) && !TextUtils.isEmpty(shortcutId)) {
                        LauncherApps la = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                        if (la != null) {
                            UserHandle user = Process.myUserHandle();
                            if (scIntent.hasExtra("userid")) {
                                int uid = scIntent.getIntExtra("userid", -1);
                                if (uid >= 0) {
                                    try {
                                        user = (UserHandle) XposedHelpers.callStaticMethod(
                                                UserHandle.class, "of", uid);
                                    } catch (Throwable ignored) {}
                                }
                            }
                            try {
                                la.startShortcut(scPkg, shortcutId, null, opts.toBundle(), user);
                                return;
                            } catch (Throwable t) {
                                XposedBridge.log(ModConfig.TAG + ": startShortcut failed for " + scPkg + "/" + shortcutId + ": " + t);
                            }
                        }
                    }
                }
            }

            Intent intent;
            if (item.isCustomShortcut && item.customIntent != null) {
                intent = new Intent(item.customIntent);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(item.component);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }
            context.startActivity(intent, opts.toBundle());
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to launch " + item.label, Toast.LENGTH_SHORT).show();
        }
    }

    private static void openAppInfo(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", pkg, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            try {
                XposedHelpers.callMethod(opts, "setLaunchWindowingMode", 5);
            } catch (Throwable ignored) {}
            context.startActivity(intent, opts.toBundle());
        } catch (Throwable ignored) {}
    }

    private static void openWallpaperAndStyle(Context context) {
        Intent[] candidates = new Intent[]{
                new Intent("com.samsung.intent.action.WALLPAPER_SETTING")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                new Intent()
                        .setComponent(new ComponentName(
                                "com.samsung.android.app.dressroom",
                                "com.samsung.android.app.dressroom.presentation.settings.WallpaperSettingActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper & style")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        };
        for (Intent candidate : candidates) {
            try {
                context.startActivity(candidate);
                return;
            } catch (Throwable ignored) {}
        }
        Toast.makeText(context, "Unable to open Wallpaper & style", Toast.LENGTH_SHORT).show();
    }

    private static void openModuleSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(ModConfig.MODULE_PACKAGE, "com.udunus.dexplus.ui.SettingsActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            try {
                XposedHelpers.callMethod(opts, "setLaunchWindowingMode", 5);
            } catch (Throwable ignored) {}
            context.startActivity(intent, opts.toBundle());
        } catch (Throwable t) {
            Toast.makeText(context, "Dex+ Settings not found", Toast.LENGTH_SHORT).show();
        }
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }
}
