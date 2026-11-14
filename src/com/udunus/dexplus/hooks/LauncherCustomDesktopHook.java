package com.udunus.dexplus.hooks;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.webkit.MimeTypeMap;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private static final String FILE_ENTRY_PREFIX = "file:";
    private static final String CUSTOM_SHORTCUT_DIR = "dexplus_shortcuts";
    private static final String DEXSC_EXT = ".dexsc";
    private static final String URL_EXT = ".url";
    private static final String DOTFILE_INIT = ".dexplus_initialized";
    private static final String DOTFILE_LAYOUT = ".dexplus_layout";
    private static final String DOTFILE_ORDER = ".dexplus_order";
    private static final ExecutorService sThumbExecutor = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> sThumbCache = new LruCache<>(64);

    private static volatile FrameLayout sActiveDesktopContainer;
    private static volatile ViewGroup sActiveWorkspaceContainer;
    private static volatile String sLastRedirectedKey;
    private static volatile long sLastRedirectedTime;
    private static volatile Object sLastHoneyDragInfo;
    private static volatile long sLastHoneyDragTime;
    private static volatile int sLastViewWidth;
    private static volatile int sLastViewHeight;

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
                && LauncherDexTaskbarHook.isInternalDexActive(ctx, null);
    }

    private static final class DesktopGridMetrics {
        final int cols;
        final int rows;
        final int padLeft;
        final int padTop;
        final int padRight;
        final int padBottom;
        final int cellW;
        final int cellH;
        final int iconSizePx;

        DesktopGridMetrics(
                int cols,
                int rows,
                int padLeft,
                int padTop,
                int padRight,
                int padBottom,
                int cellW,
                int cellH,
                int iconSizePx) {
            this.cols = cols;
            this.rows = rows;
            this.padLeft = padLeft;
            this.padTop = padTop;
            this.padRight = padRight;
            this.padBottom = padBottom;
            this.cellW = cellW;
            this.cellH = cellH;
            this.iconSizePx = iconSizePx;
        }
    }

    private static DesktopGridMetrics computeDesktopGridMetrics(Context ctx, int widthHint, int heightHint) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int totalW = widthHint > 0 ? widthHint : (sLastViewWidth > 0 ? sLastViewWidth : dm.widthPixels);
        int totalH = heightHint > 0 ? heightHint : (sLastViewHeight > 0 ? sLastViewHeight : dm.heightPixels);
        int padLeft = dpToPx(ctx, 14);
        int padTop = dpToPx(ctx, 14);
        int padRight = dpToPx(ctx, 14);
        int taskbarPx = LauncherDexTaskbarHook.getScaledTaskbarHeightPx(ctx);
        int padBottom = taskbarPx > 0 ? taskbarPx + dpToPx(ctx, 10) : dpToPx(ctx, 68);

        int availW = Math.max(dpToPx(ctx, 200), totalW - padLeft - padRight);
        int availH = Math.max(dpToPx(ctx, 180), totalH - padTop - padBottom);
        int iconScale = Math.max(65, Math.min(150, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_ICON_SCALE, 100)));
        float scaleFactor = iconScale / 100.0f;
        int desiredIconPx = Math.round(dpToPx(ctx, 50) * scaleFactor);
        int minCellW = desiredIconPx + Math.round(dpToPx(ctx, 44) * (0.85f + 0.15f * scaleFactor));
        int minCellH = desiredIconPx + Math.round(dpToPx(ctx, 60) * (0.90f + 0.10f * scaleFactor));

        int cols = Math.max(2, availW / Math.max(1, minCellW));
        int rows = Math.max(2, availH / Math.max(1, minCellH));
        int cellW = availW / cols;
        int cellH = availH / rows;
        int iconSizePx = Math.max(dpToPx(ctx, 20),
                Math.min(desiredIconPx, Math.min(cellW - dpToPx(ctx, 16), cellH - dpToPx(ctx, 44))));
        return new DesktopGridMetrics(cols, rows, padLeft, padTop, padRight, padBottom, cellW, cellH, iconSizePx);
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
                    boolean[] addedOut = new boolean[1];
                    String resolvedKey = addShortcutToDesktopWithKey(ctx, flat, label, null, addedOut);
                    boolean added = addedOut[0];
                    String effectiveKey = resolvedKey != null ? resolvedKey : flat;
                    if (targetCol != null && targetRow != null) {
                        assignOrSwapSlot(ctx, effectiveKey, targetCol, targetRow);
                    }
                    syncDesktopVisibilityOnMainThread();
                    if (showToast) {
                        showToastOnMain(ctx, added
                                ? "Added \"" + label + "\" to workspace"
                                : (targetCol != null ? "Moved \"" + label + "\"" : "\"" + label + "\" is already on workspace"));
                    }
                    return effectiveKey;
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

                String effectiveKey = saveAndAddCustomShortcutWithKey(ctx, key, label, intent, icon, pkg);
                boolean added = effectiveKey != null;
                if (effectiveKey == null) effectiveKey = key;
                if (targetCol != null && targetRow != null) {
                    assignOrSwapSlot(ctx, effectiveKey, targetCol, targetRow);
                }
                syncDesktopVisibilityOnMainThread();
                if (showToast) {
                    showToastOnMain(ctx, added
                            ? "Added \"" + label + "\" to workspace"
                            : "Updated \"" + label + "\" on workspace");
                }
                return effectiveKey;
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
        private final MarqueeOverlayView mMarqueeOverlay;
        private final View mPopupAnchor;
        private final List<View> mCellViews = new ArrayList<>();
        private final Map<View, int[]> mSlotMap = new HashMap<>();
        private final LinkedHashSet<String> mSelectedKeys = new LinkedHashSet<>();
        private final LinkedHashSet<String> mPreMarqueeSelection = new LinkedHashSet<>();
        private String mSelectionAnchorKey = null;
        private View mHighlightedDropCell;
        private BroadcastReceiver mCommandReceiver;
        private FileObserver mDesktopFileObserver;
        private String mObservedDesktopPath = null;
        private final Handler mUiHandler = new Handler(Looper.getMainLooper());
        private final Runnable mDebouncedFileRefresh = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                if (mInlineRenameEditor != null) {
                    mUiHandler.removeCallbacks(this);
                    mUiHandler.postDelayed(this, 300L);
                    return;
                }
                rebuildGrid();
            }
        };
        private long mLastClickTime = 0L;
        private String mLastClickedKey = null;
        private float mLastTouchX = 200f;
        private float mLastTouchY = 200f;
        private boolean mConsumingSecondaryClick = false;
        private long mLastSecondaryMenuTime = 0L;
        private int mCurrentCols = 12;
        private int mCurrentRows = 7;
        private int mCurrentCellW = 0;
        private int mCurrentCellH = 0;
        private int mPendingAddCol = -1;
        private int mPendingAddRow = -1;
        private boolean mCtrlDown = false;
        private boolean mShiftDown = false;
        private boolean mLastPointerCtrl = false;
        private boolean mLastPointerShift = false;
        private boolean mMarqueeCandidate = false;
        private boolean mMarqueeActive = false;
        private int mMarqueeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        private float mMarqueeDownX = 0f;
        private float mMarqueeDownY = 0f;
        private EditText mInlineRenameEditor = null;
        private DesktopShortcutItem mInlineRenameItem = null;
        private TextView mInlineRenameTargetLabel = null;
        private String mInlineRenameInitialName = null;
        private boolean mFinishingInlineRename = false;
        private int mSavedDescendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS;

        private static final class DragSession {
            final DesktopShortcutItem item;
            final View sourceCell;
            final List<String> draggedKeys;
            final Map<String, int[]> startSlots;
            final float startGridX;
            final float startGridY;
            final boolean openMenuIfStationary;
            float maxDistSq = 0f;
            boolean handledDrop = false;

            DragSession(
                    DesktopShortcutItem item,
                    View sourceCell,
                    List<String> draggedKeys,
                    Map<String, int[]> startSlots,
                    float startGridX,
                    float startGridY,
                    boolean openMenuIfStationary) {
                this.item = item;
                this.sourceCell = sourceCell;
                this.draggedKeys = draggedKeys;
                this.startSlots = startSlots;
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

        private static final class MultiDragShadowBuilder extends View.DragShadowBuilder {
            private final int mCount;
            private final float mDensity;

            MultiDragShadowBuilder(View view, int count) {
                super(view);
                mCount = count;
                mDensity = view.getResources().getDisplayMetrics().density;
            }

            @Override
            public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
                super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint);
            }

            @Override
            public void onDrawShadow(Canvas canvas) {
                super.onDrawShadow(canvas);
                if (mCount <= 1 || canvas == null) return;
                String badgeText = String.valueOf(mCount);
                Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(12f * mDensity);
                textPaint.setFakeBoldText(true);
                textPaint.setTextAlign(Paint.Align.CENTER);

                float radius = Math.max(11f * mDensity, (textPaint.measureText(badgeText) * 0.5f) + 6f * mDensity);
                float cx = getView().getWidth() - radius - 2f * mDensity;
                float cy = radius + 2f * mDensity;

                Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgPaint.setColor(Color.rgb(37, 99, 235));
                canvas.drawCircle(cx, cy, radius, bgPaint);

                Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(1.5f * mDensity);
                borderPaint.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, radius - 0.75f * mDensity, borderPaint);

                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = cy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(badgeText, cx, textY, textPaint);
            }
        }

        private static final class MarqueeOverlayView extends View {
            private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF mRect = new RectF();
            private boolean mVisible = false;

            MarqueeOverlayView(Context context) {
                super(context);
                float d = context.getResources().getDisplayMetrics().density;
                mFillPaint.setStyle(Paint.Style.FILL);
                mFillPaint.setColor(Color.argb(66, 59, 130, 246));
                mStrokePaint.setStyle(Paint.Style.STROKE);
                mStrokePaint.setStrokeWidth(Math.max(1.5f, 1.5f * d));
                mStrokePaint.setColor(Color.argb(215, 96, 165, 250));
            }

            void setMarqueeRect(RectF r) {
                mRect.set(r);
                mVisible = true;
                invalidate();
            }

            void clear() {
                if (mVisible) {
                    mVisible = false;
                    invalidate();
                }
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (mVisible && mRect.width() > 1f && mRect.height() > 1f) {
                    canvas.drawRect(mRect, mFillPaint);
                    canvas.drawRect(mRect, mStrokePaint);
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
            if (Build.VERSION.SDK_INT >= 26) {
                setDefaultFocusHighlightEnabled(false);
            }
            if (Build.VERSION.SDK_INT >= 23) {
                setContextClickable(true);
            }

            mScrollView = new ScrollView(context);
            mScrollView.setFillViewport(true);
            mScrollView.setVerticalScrollBarEnabled(false);
            mScrollView.setClickable(true);
            mScrollView.setLongClickable(true);
            if (Build.VERSION.SDK_INT >= 26) {
                mScrollView.setDefaultFocusHighlightEnabled(false);
            }
            if (Build.VERSION.SDK_INT >= 23) {
                mScrollView.setContextClickable(true);
            }

            mGridLayout = new GridLayout(context);
            mGridLayout.setOrientation(GridLayout.VERTICAL);
            mGridLayout.setClickable(true);
            mGridLayout.setLongClickable(true);
            if (Build.VERSION.SDK_INT >= 26) {
                mGridLayout.setDefaultFocusHighlightEnabled(false);
            }
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

            mMarqueeOverlay = new MarqueeOverlayView(context);
            mMarqueeOverlay.setClickable(false);
            mMarqueeOverlay.setFocusable(false);
            addView(mMarqueeOverlay, new FrameLayout.LayoutParams(
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
                        if (ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true)) {
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
            syncFileObserver(getContext());
        }

        @Override
        protected void onDetachedFromWindow() {
            finishInlineRename(false);
            unregisterDesktopCommandReceiver(getContext());
            stopFileObserver();
            mUiHandler.removeCallbacks(mDebouncedFileRefresh);
            super.onDetachedFromWindow();
        }

        private void syncFileObserver(Context context) {
            if (context == null || !isFileBasedDesktop(context)) {
                stopFileObserver();
                return;
            }
            File dir = getDesktopDirectory(context);
            if (dir == null) {
                stopFileObserver();
                return;
            }
            String path = dir.getAbsolutePath();
            if (mDesktopFileObserver != null && path.equals(mObservedDesktopPath)) {
                return;
            }
            stopFileObserver();
            mObservedDesktopPath = path;
            final int mask = FileObserver.CREATE | FileObserver.DELETE
                    | FileObserver.MOVED_FROM | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE;
            try {
                mDesktopFileObserver = new FileObserver(path, mask) {
                    @Override
                    public void onEvent(int event, String filePath) {
                        if (filePath != null && filePath.startsWith(".")) return;
                        mUiHandler.removeCallbacks(mDebouncedFileRefresh);
                        mUiHandler.postDelayed(mDebouncedFileRefresh, 250L);
                    }
                };
                mDesktopFileObserver.startWatching();
            } catch (Throwable ignored) {
                mDesktopFileObserver = null;
                mObservedDesktopPath = null;
            }
        }

        private void stopFileObserver() {
            if (mDesktopFileObserver != null) {
                try {
                    mDesktopFileObserver.stopWatching();
                } catch (Throwable ignored) {}
                mDesktopFileObserver = null;
            }
            mObservedDesktopPath = null;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
                sLastViewWidth = w;
                sLastViewHeight = h;
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

        private boolean isCtrlHeld(MotionEvent ev) {
            return mCtrlDown || (ev != null && (ev.getMetaState() & KeyEvent.META_CTRL_ON) != 0);
        }

        private boolean isShiftHeld(MotionEvent ev) {
            return mShiftDown || (ev != null && (ev.getMetaState() & KeyEvent.META_SHIFT_ON) != 0);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev != null && mInlineRenameEditor != null && mInlineRenameEditor.getParent() == this) {
                Rect editorRect = new Rect();
                mInlineRenameEditor.getHitRect(editorRect);
                if (editorRect.contains((int) ev.getX(), (int) ev.getY())) {
                    mLastTouchX = ev.getX();
                    mLastTouchY = ev.getY();
                    return super.dispatchTouchEvent(ev);
                }
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    finishInlineRename(true);
                }
            }
            if (ev != null) {
                mLastTouchX = ev.getX();
                mLastTouchY = ev.getY();
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                    mLastPointerCtrl = isCtrlHeld(ev);
                    mLastPointerShift = isShiftHeld(ev);
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    android.view.ViewParent p = getParent();
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true);
                        p = p.getParent();
                    }
                    if (isSecondaryButtonEvent(ev)) {
                        mConsumingSecondaryClick = true;
                        mMarqueeCandidate = false;
                        mMarqueeActive = false;
                        mMarqueeOverlay.clear();
                        handleSecondaryMenuAt(ev.getX(), ev.getY());
                        return true;
                    }
                    mConsumingSecondaryClick = false;
                    mMarqueeActive = false;
                    View hit = findCellAt(ev.getX(), ev.getY());
                    if (hit == null) {
                        mMarqueeCandidate = true;
                        mMarqueeDownX = ev.getX();
                        mMarqueeDownY = ev.getY();
                        mMarqueeToolType = ev.getToolType(0);
                    } else {
                        mMarqueeCandidate = false;
                    }
                } else if (mConsumingSecondaryClick) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        mConsumingSecondaryClick = false;
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (mMarqueeCandidate && !mMarqueeActive) {
                        float dx = ev.getX() - mMarqueeDownX;
                        float dy = ev.getY() - mMarqueeDownY;
                        int slop = Math.max(ViewConfiguration.get(getContext()).getScaledTouchSlop(), dpToPx(getContext(), 6));
                        if ((dx * dx + dy * dy) > (slop * slop)) {
                            boolean pointerMouseOrStylus = (mMarqueeToolType == MotionEvent.TOOL_TYPE_MOUSE
                                    || mMarqueeToolType == MotionEvent.TOOL_TYPE_STYLUS);
                            boolean canScroll = mScrollView.canScrollVertically(-1) || mScrollView.canScrollVertically(1);
                            if (pointerMouseOrStylus || !canScroll || Math.abs(dx) > Math.abs(dy) * 1.15f) {
                                mMarqueeActive = true;
                                mPreMarqueeSelection.clear();
                                mPreMarqueeSelection.addAll(mSelectedKeys);
                                requestFocus();
                                MotionEvent cancelEv = MotionEvent.obtain(ev);
                                cancelEv.setAction(MotionEvent.ACTION_CANCEL);
                                super.dispatchTouchEvent(cancelEv);
                                cancelEv.recycle();
                            } else {
                                mMarqueeCandidate = false;
                            }
                        }
                    }
                    if (mMarqueeActive) {
                        RectF box = new RectF(
                                Math.min(mMarqueeDownX, ev.getX()),
                                Math.min(mMarqueeDownY, ev.getY()),
                                Math.max(mMarqueeDownX, ev.getX()),
                                Math.max(mMarqueeDownY, ev.getY()));
                        mMarqueeOverlay.setMarqueeRect(box);
                        updateSelectionFromMarquee(box, isCtrlHeld(ev), isShiftHeld(ev));
                        return true;
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mMarqueeCandidate = false;
                    if (mMarqueeActive) {
                        mMarqueeActive = false;
                        mMarqueeOverlay.clear();
                        if (!mSelectedKeys.isEmpty() && (mSelectionAnchorKey == null || !mSelectedKeys.contains(mSelectionAnchorKey))) {
                            mSelectionAnchorKey = mSelectedKeys.iterator().next();
                        }
                        return true;
                    }
                }
            }
            return super.dispatchTouchEvent(ev);
        }

        private void updateSelectionFromMarquee(RectF viewBox, boolean ctrlHeld, boolean shiftHeld) {
            Set<String> hitKeys = new LinkedHashSet<>();
            float offsetX = mScrollView.getLeft() - mScrollView.getScrollX() + mGridLayout.getLeft();
            float offsetY = mScrollView.getTop() - mScrollView.getScrollY() + mGridLayout.getTop();
            for (View cell : mCellViews) {
                if (cell.getVisibility() != View.VISIBLE || !(cell.getTag() instanceof DesktopShortcutItem)) continue;
                float left = cell.getLeft() + offsetX;
                float top = cell.getTop() + offsetY;
                float right = cell.getRight() + offsetX;
                float bottom = cell.getBottom() + offsetY;
                if (RectF.intersects(viewBox, new RectF(left, top, right, bottom))) {
                    hitKeys.add(((DesktopShortcutItem) cell.getTag()).storageKey);
                }
            }
            mSelectedKeys.clear();
            if (shiftHeld) {
                mSelectedKeys.addAll(mPreMarqueeSelection);
                mSelectedKeys.addAll(hitKeys);
            } else if (ctrlHeld) {
                mSelectedKeys.addAll(mPreMarqueeSelection);
                for (String k : hitKeys) {
                    if (mSelectedKeys.contains(k)) {
                        mSelectedKeys.remove(k);
                    } else {
                        mSelectedKeys.add(k);
                    }
                }
            } else {
                mSelectedKeys.addAll(hitKeys);
            }
            refreshSelectionVisuals();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null) {
                int code = event.getKeyCode();
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT) {
                    mCtrlDown = down;
                } else {
                    mCtrlDown = event.isCtrlPressed();
                }
                if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                    mShiftDown = down;
                } else {
                    mShiftDown = event.isShiftPressed();
                }
            }
            return super.dispatchKeyEvent(event);
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
            if (mInlineRenameEditor != null) {
                return super.onKeyDown(keyCode, event);
            }
            Context ctx = getContext();
            boolean ctrl = mCtrlDown || (event != null && event.isCtrlPressed());
            if (ctrl && keyCode == KeyEvent.KEYCODE_A) {
                selectAllItems();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                clearSelection();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                List<DesktopShortcutItem> selected = getSelectedItems();
                View anchor = getFirstSelectedCell();
                if (!selected.isEmpty() && anchor != null) {
                    showSelectionContextMenu(ctx, anchor, selected);
                } else {
                    showEmptyDesktopMenu(mLastTouchX, mLastTouchY);
                }
                return true;
            }
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                List<DesktopShortcutItem> selected = getSelectedItems();
                if (!selected.isEmpty()) {
                    launchItems(ctx, selected, ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                    return true;
                }
            }
            if ((keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)) {
                List<DesktopShortcutItem> selected = getSelectedItems();
                if (!selected.isEmpty()) {
                    confirmAndDeleteOrRemoveSelection(ctx, selected);
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_F2 && mInlineRenameEditor == null) {
                List<DesktopShortcutItem> selected = getSelectedItems();
                if (selected.size() == 1 && selected.get(0).backingFile != null) {
                    startInlineRename(ctx, selected.get(0));
                    return true;
                }
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
                if (!mSelectedKeys.contains(item.storageKey)) {
                    selectSingle(item.storageKey);
                }
                List<DesktopShortcutItem> selected = getSelectedItems();
                if (selected.isEmpty()) {
                    selected = Collections.singletonList(item);
                }
                showSelectionContextMenu(getContext(), hitCell, selected);
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
            float cellW = mCurrentCellW > 0 ? mCurrentCellW : (availW / (float) cols);
            float cellH = mCurrentCellH > 0 ? mCurrentCellH : (availH / (float) Math.max(1, mCurrentRows));
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
                            } else if ("import_legacy_shortcuts".equals(cmd)) {
                                importNonFileShortcutsToFileDesktop(ctx);
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
            if (mInlineRenameEditor != null && !mFinishingInlineRename) {
                finishInlineRename(false);
            }

            syncFileObserver(ctx);

            Set<String> previousSelection = new LinkedHashSet<>(mSelectedKeys);
            String previousAnchor = mSelectionAnchorKey;
            mSelectedKeys.clear();
            mSelectionAnchorKey = null;
            mHighlightedDropCell = null;
            mCellViews.clear();
            mSlotMap.clear();

            if (getWidth() > 0 && getHeight() > 0) {
                sLastViewWidth = getWidth();
                sLastViewHeight = getHeight();
            }
            DesktopGridMetrics gm = computeDesktopGridMetrics(ctx, getWidth(), getHeight());
            int cols = gm.cols;
            int rows = gm.rows;
            mCurrentCols = cols;
            mCurrentRows = rows;
            mCurrentCellW = gm.cellW;
            mCurrentCellH = gm.cellH;
            boolean freePlacement = ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true);

            mGridLayout.setPadding(gm.padLeft, gm.padTop, gm.padRight, gm.padBottom);

            List<DesktopShortcutItem> items = loadDesktopShortcuts(ctx, cols * rows);
            int cellW = gm.cellW;
            int cellH = gm.cellH;
            int iconSizePx = gm.iconSizePx;

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
                            if (previousSelection.contains(item.storageKey)) {
                                mSelectedKeys.add(item.storageKey);
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
                    if (previousSelection.contains(item.storageKey)) {
                        mSelectedKeys.add(item.storageKey);
                    }
                }
            }

            if (previousAnchor != null && mSelectedKeys.contains(previousAnchor)) {
                mSelectionAnchorKey = previousAnchor;
            } else if (!mSelectedKeys.isEmpty()) {
                mSelectionAnchorKey = mSelectedKeys.iterator().next();
            }
            refreshSelectionVisuals();
        }

        private View createEmptySlotCell(final Context context, final int col, final int row, GridLayout.LayoutParams lp) {
            final FrameLayout slot = new FrameLayout(context);
            slot.setLayoutParams(lp);
            slot.setClickable(true);
            slot.setLongClickable(true);
            if (Build.VERSION.SDK_INT >= 26) {
                slot.setDefaultFocusHighlightEnabled(false);
            }
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
            if (Build.VERSION.SDK_INT >= 26) {
                cell.setDefaultFocusHighlightEnabled(false);
            }
            cell.setLayoutParams(lp);

            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(item.icon);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            iconLp.bottomMargin = dpToPx(context, 5);
            cell.addView(iconView, iconLp);

            if (item.isFileEntry && !item.isDirectory && item.backingFile != null) {
                scheduleAsyncThumbnailIfNeeded(context, item, iconView, iconSizePx);
            }

            TextView labelView = new TextView(context);
            labelView.setId(android.R.id.text1);
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
                    ClassicDexDesktopView.this.requestFocus();
                    boolean ctrl = mCtrlDown || mLastPointerCtrl;
                    boolean shift = mShiftDown || mLastPointerShift;
                    if (shift) {
                        selectRangeTo(item.storageKey, ctrl);
                        return;
                    }
                    if (ctrl) {
                        toggleSelection(item.storageKey);
                        return;
                    }

                    boolean requireDoubleClick = ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_DOUBLE_CLICK, true);
                    long now = System.currentTimeMillis();
                    if (requireDoubleClick) {
                        selectSingle(item.storageKey);
                        if (item.storageKey.equals(mLastClickedKey) && (now - mLastClickTime) < 420L) {
                            mLastClickTime = 0L;
                            launchShortcut(context, item, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                        } else {
                            mLastClickedKey = item.storageKey;
                            mLastClickTime = now;
                        }
                    } else {
                        selectSingle(item.storageKey);
                        launchShortcut(context, item, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                    }
                }
            });

            final float[] lastTouchLocal = new float[]{lp.width * 0.5f, lp.height * 0.5f};

            cell.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (!mSelectedKeys.contains(item.storageKey)) {
                        selectSingle(item.storageKey);
                    }
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
                        if (!mSelectedKeys.contains(item.storageKey)) {
                            selectSingle(item.storageKey);
                        }
                        List<DesktopShortcutItem> selected = getSelectedItems();
                        if (selected.isEmpty()) selected = Collections.singletonList(item);
                        showSelectionContextMenu(context, cell, selected);
                        return true;
                    }
                });
            }

            cell.setOnHoverListener(new OnHoverListener() {
                @Override
                public boolean onHover(View v, MotionEvent event) {
                    if (mSelectedKeys.contains(item.storageKey) || cell == mHighlightedDropCell) return false;
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
                            if (local instanceof DragSession) {
                                if (!((DragSession) local).draggedKeys.contains(item.storageKey)) {
                                    setDropHighlight(cell);
                                }
                            } else if (!item.storageKey.equals(draggedKey)) {
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
                if (!mSelectedKeys.contains(item.storageKey)) {
                    selectSingle(item.storageKey);
                }
                List<String> draggedKeys = new ArrayList<>();
                Map<String, int[]> startSlots = new HashMap<>();
                for (View cv : getVisuallyOrderedCells()) {
                    if (!(cv.getTag() instanceof DesktopShortcutItem)) continue;
                    DesktopShortcutItem di = (DesktopShortcutItem) cv.getTag();
                    if (mSelectedKeys.contains(di.storageKey)) {
                        draggedKeys.add(di.storageKey);
                        int[] pos = mSlotMap.get(cv);
                        if (pos != null) {
                            startSlots.put(di.storageKey, new int[]{pos[0], pos[1]});
                        }
                    }
                }
                if (draggedKeys.isEmpty()) {
                    draggedKeys.add(item.storageKey);
                    int[] pos = mSlotMap.get(cell);
                    if (pos != null) {
                        startSlots.put(item.storageKey, new int[]{pos[0], pos[1]});
                    }
                }

                ClipData clip = ClipData.newPlainText("dexplus_shortcut", item.storageKey);
                View.DragShadowBuilder shadow = new MultiDragShadowBuilder(cell, draggedKeys.size());
                DragSession session = new DragSession(
                        item, cell, draggedKeys, startSlots, startGridX, startGridY, openMenuIfStationary);
                cell.startDragAndDrop(clip, shadow, session, 0);
                for (View cv : mCellViews) {
                    if (cv.getTag() instanceof DesktopShortcutItem
                            && draggedKeys.contains(((DesktopShortcutItem) cv.getTag()).storageKey)) {
                        cv.setAlpha(0.35f);
                    }
                }
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
                                if (!mSelectedKeys.contains(session.item.storageKey)) {
                                    selectSingle(session.item.storageKey);
                                }
                                List<DesktopShortcutItem> selected = getSelectedItems();
                                if (selected.isEmpty()) selected = Collections.singletonList(session.item);
                                showSelectionContextMenu(ctx, session.sourceCell, selected);
                            }
                        }
                    });
                    return true;
                }

                boolean freePlacement = ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true);
                if (session.draggedKeys.size() > 1) {
                    if (freePlacement) {
                        int[] anchorOld = session.startSlots.get(session.item.storageKey);
                        if (anchorOld == null) anchorOld = mSlotMap.get(session.sourceCell);
                        if (anchorOld != null) {
                            int dCol = col - anchorOld[0];
                            int dRow = row - anchorOld[1];
                            if (dCol != 0 || dRow != 0) {
                                moveMultipleSlots(ctx, session.draggedKeys, dCol, dRow);
                                rebuildGrid();
                            }
                        }
                    } else {
                        String targetKey = (targetItem != null && !session.draggedKeys.contains(targetItem.storageKey))
                                ? targetItem.storageKey : null;
                        moveMultipleShortcuts(ctx, session.draggedKeys, targetKey);
                        rebuildGrid();
                    }
                    return true;
                }

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
            boolean freePlacement = ModConfig.getBoolean(ctx, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true);
            return handleExternalDragDrop(ctx, event, col, row, targetItem, freePlacement);
        }

        private void finishDragSession(DragEvent event) {
            for (View cv : mCellViews) {
                cv.setAlpha(1.0f);
                restoreCellBackground(cv);
            }
            Object local = event.getLocalState();
            if (local instanceof DragSession) {
                final DragSession session = (DragSession) local;
                if (session.openMenuIfStationary && !session.handledDrop) {
                    Context ctx = getContext();
                    float stationaryLimit = Math.max(ViewConfiguration.get(ctx).getScaledTouchSlop(), dpToPx(ctx, 10)) * 2.0f;
                    if (session.maxDistSq <= (stationaryLimit * stationaryLimit)) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if (session.sourceCell != null && session.sourceCell.isAttachedToWindow()) {
                                    if (!mSelectedKeys.contains(session.item.storageKey)) {
                                        selectSingle(session.item.storageKey);
                                    }
                                    List<DesktopShortcutItem> selected = getSelectedItems();
                                    if (selected.isEmpty()) selected = Collections.singletonList(session.item);
                                    showSelectionContextMenu(getContext(), session.sourceCell, selected);
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
            if (cell == null) return;
            if (cell.getTag() instanceof DesktopShortcutItem
                    && mSelectedKeys.contains(((DesktopShortcutItem) cell.getTag()).storageKey)) {
                applySelectedBackground(cell);
            } else {
                cell.setBackground(null);
            }
        }

        private void applySelectedBackground(View cell) {
            GradientDrawable selBg = new GradientDrawable();
            selBg.setColor(Color.argb(68, 59, 130, 246));
            selBg.setCornerRadius(dpToPx(getContext(), 10));
            selBg.setStroke(dpToPx(getContext(), 1), Color.argb(175, 147, 197, 253));
            cell.setBackground(selBg);
        }

        private void refreshSelectionVisuals() {
            for (View cell : mCellViews) {
                if (cell == mHighlightedDropCell) continue;
                restoreCellBackground(cell);
            }
        }

        private void selectSingle(String storageKey) {
            mSelectedKeys.clear();
            if (storageKey != null) {
                mSelectedKeys.add(storageKey);
                mSelectionAnchorKey = storageKey;
            } else {
                mSelectionAnchorKey = null;
            }
            refreshSelectionVisuals();
        }

        private void toggleSelection(String storageKey) {
            if (storageKey == null) return;
            if (mSelectedKeys.contains(storageKey)) {
                mSelectedKeys.remove(storageKey);
            } else {
                mSelectedKeys.add(storageKey);
            }
            mSelectionAnchorKey = storageKey;
            refreshSelectionVisuals();
        }

        private void selectRangeTo(String targetKey, boolean additive) {
            if (targetKey == null) return;
            List<View> ordered = getVisuallyOrderedCells();
            int targetIdx = -1;
            int anchorIdx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                DesktopShortcutItem item = (DesktopShortcutItem) ordered.get(i).getTag();
                if (item.storageKey.equals(targetKey)) targetIdx = i;
                if (mSelectionAnchorKey != null && item.storageKey.equals(mSelectionAnchorKey)) anchorIdx = i;
            }
            if (targetIdx < 0) return;
            if (anchorIdx < 0) {
                anchorIdx = targetIdx;
                mSelectionAnchorKey = targetKey;
            }
            if (!additive) {
                mSelectedKeys.clear();
            }
            int from = Math.min(anchorIdx, targetIdx);
            int to = Math.max(anchorIdx, targetIdx);
            for (int i = from; i <= to; i++) {
                DesktopShortcutItem item = (DesktopShortcutItem) ordered.get(i).getTag();
                mSelectedKeys.add(item.storageKey);
            }
            refreshSelectionVisuals();
        }

        private void selectAllItems() {
            mSelectedKeys.clear();
            for (View cell : getVisuallyOrderedCells()) {
                DesktopShortcutItem item = (DesktopShortcutItem) cell.getTag();
                mSelectedKeys.add(item.storageKey);
                if (mSelectionAnchorKey == null) {
                    mSelectionAnchorKey = item.storageKey;
                }
            }
            refreshSelectionVisuals();
        }

        private void clearSelection() {
            if (mSelectedKeys.isEmpty() && mSelectionAnchorKey == null) return;
            mSelectedKeys.clear();
            mSelectionAnchorKey = null;
            refreshSelectionVisuals();
        }

        private List<View> getVisuallyOrderedCells() {
            List<View> ordered = new ArrayList<>();
            for (View cell : mCellViews) {
                if (cell.getTag() instanceof DesktopShortcutItem) {
                    ordered.add(cell);
                }
            }
            Collections.sort(ordered, new Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    int[] pa = mSlotMap.get(a);
                    int[] pb = mSlotMap.get(b);
                    int ca = pa != null ? pa[0] : 0;
                    int cb = pb != null ? pb[0] : 0;
                    if (ca != cb) return Integer.compare(ca, cb);
                    int ra = pa != null ? pa[1] : 0;
                    int rb = pb != null ? pb[1] : 0;
                    return Integer.compare(ra, rb);
                }
            });
            return ordered;
        }

        private List<DesktopShortcutItem> getSelectedItems() {
            List<DesktopShortcutItem> list = new ArrayList<>();
            for (View cell : getVisuallyOrderedCells()) {
                DesktopShortcutItem item = (DesktopShortcutItem) cell.getTag();
                if (mSelectedKeys.contains(item.storageKey)) {
                    list.add(item);
                }
            }
            return list;
        }

        private View getFirstSelectedCell() {
            for (View cell : getVisuallyOrderedCells()) {
                DesktopShortcutItem item = (DesktopShortcutItem) cell.getTag();
                if (mSelectedKeys.contains(item.storageKey)) {
                    return cell;
                }
            }
            return null;
        }

        private void launchItems(Context context, List<DesktopShortcutItem> items, boolean freeform) {
            if (items == null || items.isEmpty()) return;
            int count = Math.min(items.size(), 6);
            for (int i = 0; i < count; i++) {
                launchShortcut(context, items.get(i), freeform);
            }
        }

        private void confirmAndDeleteOrRemoveSelection(final Context context, final List<DesktopShortcutItem> items) {
            if (items == null || items.isEmpty()) return;
            boolean hasRealFiles = false;
            int folderCount = 0;
            int fileCount = 0;
            for (DesktopShortcutItem item : items) {
                if (item.isFileEntry && item.backingFile != null) {
                    hasRealFiles = true;
                    if (item.isDirectory) folderCount++;
                    else fileCount++;
                }
            }
            if (!hasRealFiles) {
                for (DesktopShortcutItem item : items) {
                    removeShortcutFromDesktop(context, item.storageKey);
                }
                clearSelection();
                rebuildGrid();
                return;
            }

            String msg;
            if (items.size() == 1) {
                DesktopShortcutItem single = items.get(0);
                msg = (single.isDirectory ? "Permanently delete folder \"" : "Permanently delete \"")
                        + single.label + "\" and "
                        + (single.isDirectory ? "all of its contents" : "this file")
                        + " from storage?";
            } else {
                msg = "Permanently delete " + items.size() + " selected items"
                        + (folderCount > 0 ? " (" + folderCount + " folder(s), " + fileCount + " file(s))" : "")
                        + " from disk?";
            }

            new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Confirm Delete")
                    .setMessage(msg)
                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            for (DesktopShortcutItem item : items) {
                                removeShortcutFromDesktop(context, item.storageKey);
                            }
                            clearSelection();
                            rebuildGrid();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        private void showSelectionContextMenu(final Context context, View anchor, final List<DesktopShortcutItem> selected) {
            if (selected == null || selected.isEmpty()) return;
            if (selected.size() > 1) {
                final int idOpenAll = 1;
                final int idRemoveAll = 2;
                final int idSelectAll = 3;

                boolean anyReal = false;
                for (DesktopShortcutItem it : selected) {
                    if (it.isFileEntry) {
                        anyReal = true;
                        break;
                    }
                }
                PopupMenu popup = new PopupMenu(context, anchor);
                popup.getMenu().add(0, idOpenAll, 0, "Open selected (" + selected.size() + ")");
                popup.getMenu().add(0, idRemoveAll, 1,
                        (anyReal ? "Delete selected (" : "Remove selected (") + selected.size() + ")");
                popup.getMenu().add(0, idSelectAll, 2, "Select all");
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                        int id = menuItem.getItemId();
                        if (id == idOpenAll) {
                            launchItems(context, selected, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                            return true;
                        } else if (id == idRemoveAll) {
                            confirmAndDeleteOrRemoveSelection(context, selected);
                            return true;
                        } else if (id == idSelectAll) {
                            selectAllItems();
                            return true;
                        }
                        return false;
                    }
                });
                popup.show();
                return;
            }

            final DesktopShortcutItem item = selected.get(0);
            final int idOpenWin = 1;
            final int idOpenFull = 2;
            final int idAppInfo = 3;
            final int idRemove = 4;
            final int idUninstall = 5;
            final int idQuickEdit = 6;
            final int idRename = 7;
            final int idProperties = 8;

            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(0, idOpenWin, 0, "Open in window");
            popup.getMenu().add(0, idOpenFull, 1, "Open fullscreen");
            int order = 2;
            if (item.isFileEntry && !item.isDirectory && isTextLikeFile(item.backingFile, item.mimeType)) {
                popup.getMenu().add(0, idQuickEdit, order++, "Quick view / edit text");
            }
            if (!TextUtils.isEmpty(item.getPackageName())) {
                popup.getMenu().add(0, idAppInfo, order++, "App info");
            }
            if (item.backingFile != null) {
                popup.getMenu().add(0, idRename, order++, "Rename");
            }
            popup.getMenu().add(0, idRemove, order++,
                    item.backingFile != null ? (item.isFileEntry ? "Delete" : "Delete shortcut") : "Remove from desktop");
            if (!item.isCustomShortcut && !item.isFileEntry && item.component != null) {
                popup.getMenu().add(0, idUninstall, order++, "Uninstall");
            }
            if (item.backingFile != null) {
                popup.getMenu().add(0, idProperties, order++, "Properties");
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
                    } else if (id == idQuickEdit && item.backingFile != null) {
                        showQuickTextViewerDialog(context, item.backingFile);
                        return true;
                    } else if (id == idAppInfo) {
                        openAppInfo(context, item.getPackageName());
                        return true;
                    } else if (id == idRename && item.backingFile != null) {
                        postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startInlineRename(context, item);
                            }
                        }, 40L);
                        return true;
                    } else if (id == idRemove) {
                        confirmAndDeleteOrRemoveSelection(context, Collections.singletonList(item));
                        return true;
                    } else if (id == idUninstall && item.component != null) {
                        try {
                            Intent uninstall = new Intent(Intent.ACTION_DELETE,
                                    Uri.fromParts("package", item.component.getPackageName(), null));
                            uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(uninstall);
                        } catch (Throwable ignored) {}
                        return true;
                    } else if (id == idProperties && item.backingFile != null) {
                        showFilePropertiesDialog(context, item);
                        return true;
                    }
                    return false;
                }
            });
            popup.show();
        }

        private void toggleDesktopFreePlacement(Context context, boolean enabled) {
            try {
                Bundle extras = new Bundle();
                extras.putBoolean("enabled", enabled);
                context.getContentResolver().call(
                        ModConfig.PREFS_URI, "set_desktop_free_placement", null, extras);
                ModConfig.reload(context);
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": toggle free placement failed: " + t);
            }
            rebuildGrid();
        }

        private void showEmptyDesktopMenu(float x, float y) {
            final Context context = getContext();
            int[] slot = viewCoordsToGridSlot(x, y);
            mPendingAddCol = slot[0];
            mPendingAddRow = slot[1];
            View anchor = prepareAnchorAt(x, y);
            PopupMenu popup = new PopupMenu(context, anchor);
            boolean fileBased = isFileBasedDesktop(context);
            final boolean freePlacement = ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true);
            int ord = 0;
            if (fileBased) {
                popup.getMenu().add(0, 10, ord++, "New folder");
                popup.getMenu().add(0, 11, ord++, "New text file");
                popup.getMenu().add(0, 12, ord++, "Open Desktop folder in My Files");
            }
            popup.getMenu().add(0, 13, ord++, freePlacement ? "✓ Free grid placement" : "Free grid placement");
            popup.getMenu().add(0, 2, ord++, "Sort icons by name (A–Z)");
            popup.getMenu().add(0, 7, ord++, "Select all");
            popup.getMenu().add(0, 3, ord++, "Refresh desktop");
            popup.getMenu().add(0, 5, ord++, "Wallpaper & style");
            popup.getMenu().add(0, 6, ord++, "DeX+ Settings");
            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    switch (item.getItemId()) {
                        case 10:
                            createDesktopFileAndRenameInline(context, true);
                            return true;
                        case 11:
                            createDesktopFileAndRenameInline(context, false);
                            return true;
                        case 12:
                            File dir = getDesktopDirectory(context);
                            if (dir != null) {
                                openFolderEntry(context, dir, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                            }
                            return true;
                        case 13:
                            toggleDesktopFreePlacement(context, !freePlacement);
                            return true;
                        case 2:
                            sortShortcutsAlphabetically(context);
                            rebuildGrid();
                            return true;
                        case 7:
                            selectAllItems();
                            return true;
                        case 3:
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

        private void createDesktopFileAndRenameInline(final Context context, final boolean folder) {
            File dir = getDesktopDirectory(context);
            if (dir == null) return;
            String baseName = folder ? "New Folder" : "New Text Document.txt";
            File target = uniqueChildFile(dir, baseName);
            boolean ok = false;
            try {
                ok = folder ? target.mkdirs() : target.createNewFile();
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": create desktop file error: " + t);
            }
            if (!ok) {
                Toast.makeText(context, "Could not create item", Toast.LENGTH_SHORT).show();
                return;
            }

            String key = FILE_ENTRY_PREFIX + target.getName();
            List<String> order = readSavedKeyOrder(context);
            if (!order.contains(key)) {
                order.add(key);
                saveShortcutList(context, new LinkedHashSet<>(order));
            }
            if (ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true)
                    && mPendingAddCol >= 0 && mPendingAddRow >= 0) {
                assignOrSwapSlot(context, key, mPendingAddCol, mPendingAddRow);
                mPendingAddCol = -1;
                mPendingAddRow = -1;
            }
            rebuildGrid();
            selectSingle(key);
            mUiHandler.removeCallbacks(mDebouncedFileRefresh);

            View createdCell = findCellByKey(key);
            if (createdCell != null && createdCell.getTag() instanceof DesktopShortcutItem) {
                final DesktopShortcutItem createdItem = (DesktopShortcutItem) createdCell.getTag();
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isAttachedToWindow()) {
                            startInlineRename(context, createdItem);
                        }
                    }
                }, 60L);
            }
        }

        private View findCellByKey(String storageKey) {
            if (storageKey == null) return null;
            for (View cell : mCellViews) {
                if (cell.getTag() instanceof DesktopShortcutItem
                        && storageKey.equals(((DesktopShortcutItem) cell.getTag()).storageKey)) {
                    return cell;
                }
            }
            return null;
        }

        private void adjustInlineEditorForIme(Context context, EditText editor) {
            if (editor == null || !editor.isAttachedToWindow()) return;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    WindowInsets wi = getRootWindowInsets();
                    if (wi != null) {
                        Class<?> typeClass = Class.forName("android.view.WindowInsets$Type");
                        int imeMask = (Integer) typeClass.getMethod("ime").invoke(null);
                        Object imeInsets = XposedHelpers.callMethod(wi, "getInsets", imeMask);
                        int imeBottom = XposedHelpers.getIntField(imeInsets, "bottom");
                        if (imeBottom > 0) {
                            int[] winLoc = new int[2];
                            getLocationInWindow(winLoc);
                            View root = getRootView();
                            int rootH = (root != null && root.getHeight() > 0) ? root.getHeight() : getHeight();
                            int visibleBottomInDesktop = (rootH - imeBottom) - winLoc[1] - dpToPx(context, 8);
                            int editorBottom = editor.getTop() + editor.getHeight();
                            if (visibleBottomInDesktop > 0 && editorBottom > visibleBottomInDesktop) {
                                editor.setTranslationY(visibleBottomInDesktop - editorBottom);
                                return;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            editor.setTranslationY(0f);
        }

        private void startInlineRename(final Context context, final DesktopShortcutItem item) {
            if (context == null || item == null || item.backingFile == null) return;
            if (!isAttachedToWindow() || !isShown() || getWidth() == 0) return;
            if (mInlineRenameEditor != null) {
                finishInlineRename(true);
            }
            View cell = findCellByKey(item.storageKey);
            if (cell == null) return;
            TextView labelView = cell.findViewById(android.R.id.text1);
            if (labelView == null) return;
            if (cell.getWidth() == 0 || labelView.getHeight() == 0) {
                cell.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isAttachedToWindow() && cell.isAttachedToWindow()) {
                            startInlineRename(context, item);
                        }
                    }
                });
                return;
            }

            final boolean isDexsc = item.backingFile.getName().toLowerCase(Locale.ROOT).endsWith(DEXSC_EXT);
            final String initialName = isDexsc ? item.label : item.backingFile.getName();
            int selEnd = initialName.length();
            if (!isDexsc && !item.isDirectory) {
                int dot = initialName.lastIndexOf('.');
                if (dot > 0) {
                    selEnd = dot;
                }
            }

            Rect cellRect = new Rect(0, 0, cell.getWidth(), cell.getHeight());
            Rect labelRect = new Rect(0, 0, labelView.getWidth(), labelView.getHeight());
            try {
                offsetDescendantRectToMyCoords(cell, cellRect);
                offsetDescendantRectToMyCoords(labelView, labelRect);
            } catch (Throwable ignored) {
                return;
            }

            final int marginSide = dpToPx(context, 6);
            int maxAllowedW = Math.max(dpToPx(context, 80), getWidth() - (marginSide * 2));
            int editorW = Math.min(maxAllowedW, Math.max(cellRect.width() + dpToPx(context, 48), dpToPx(context, 168)));
            int left = cellRect.centerX() - (editorW / 2);
            left = Math.max(marginSide, Math.min(left, Math.max(marginSide, getWidth() - editorW - marginSide)));
            int top = Math.max(dpToPx(context, 4), labelRect.top - dpToPx(context, 4));

            final EditText editor = new EditText(context) {
                @Override
                public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
                            finishInlineRename(false);
                        }
                        return true;
                    }
                    return super.onKeyPreIme(keyCode, event);
                }
            };

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(238, 22, 26, 35));
            bg.setCornerRadius(dpToPx(context, 6));
            bg.setStroke(Math.max(2, dpToPx(context, 1.5f)), Color.rgb(96, 165, 250));
            editor.setBackground(bg);
            editor.setTextColor(Color.WHITE);
            editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
            editor.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            editor.setPadding(dpToPx(context, 6), dpToPx(context, 4), dpToPx(context, 6), dpToPx(context, 4));
            editor.setMinHeight(Math.max(labelView.getHeight() + dpToPx(context, 8), dpToPx(context, 26)));
            editor.setMaxLines(3);
            editor.setHorizontallyScrolling(false);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            editor.setHighlightColor(Color.argb(120, 59, 130, 246));
            editor.setFocusable(true);
            editor.setFocusableInTouchMode(true);
            editor.setText(initialName);
            if (selEnd > 0) {
                editor.setSelection(0, Math.min(selEnd, editor.length()));
            }

            final boolean[] gainedFocus = new boolean[]{false};
            final long startedAt = SystemClock.uptimeMillis();
            editor.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        gainedFocus[0] = true;
                    } else if (gainedFocus[0] && (SystemClock.uptimeMillis() - startedAt) > 120L) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if (mInlineRenameEditor == editor) {
                                    finishInlineRename(true);
                                }
                            }
                        });
                    }
                }
            });

            editor.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_DONE
                            || actionId == EditorInfo.IME_ACTION_NEXT
                            || actionId == EditorInfo.IME_NULL) {
                        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                            return true;
                        }
                        finishInlineRename(true);
                        return true;
                    }
                    return false;
                }
            });

            editor.setOnKeyListener(new OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
                            finishInlineRename(true);
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
                            finishInlineRename(false);
                        }
                        return true;
                    }
                    return false;
                }
            });

            editor.addTextChangedListener(new TextWatcher() {
                private boolean updating = false;
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (updating || s == null) return;
                    String str = s.toString();
                    if (str.indexOf('\n') >= 0 || str.indexOf('\r') >= 0) {
                        updating = true;
                        String cleaned = str.replace("\r", "").replace("\n", "");
                        s.replace(0, s.length(), cleaned);
                        updating = false;
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if (mInlineRenameEditor == editor) {
                                    finishInlineRename(true);
                                }
                            }
                        });
                    }
                }
            });

            if (Build.VERSION.SDK_INT >= 30) {
                editor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if (mInlineRenameEditor == editor) {
                                    adjustInlineEditorForIme(context, editor);
                                }
                            }
                        });
                        return v.onApplyWindowInsets(insets);
                    }
                });
            }
            editor.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (mInlineRenameEditor == editor) {
                        adjustInlineEditorForIme(context, editor);
                    }
                }
            });

            mSavedDescendantFocusability = getDescendantFocusability();
            setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

            mInlineRenameEditor = editor;
            mInlineRenameItem = item;
            mInlineRenameTargetLabel = labelView;
            mInlineRenameInitialName = initialName;
            labelView.setVisibility(View.INVISIBLE);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(editorW, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = left;
            lp.topMargin = top;
            addView(editor, lp);
            editor.bringToFront();

            editor.requestFocus();
            post(new Runnable() {
                @Override
                public void run() {
                    if (mInlineRenameEditor != editor || !editor.isAttachedToWindow()) return;
                    editor.requestFocus();
                    try {
                        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        }

        private void finishInlineRename(boolean commit) {
            if (mFinishingInlineRename || mInlineRenameEditor == null) return;
            mFinishingInlineRename = true;
            final Context context = getContext();
            final EditText editor = mInlineRenameEditor;
            final DesktopShortcutItem item = mInlineRenameItem;
            final TextView targetLabel = mInlineRenameTargetLabel;
            final String initialName = mInlineRenameInitialName;
            String rawText = editor.getText() != null ? editor.getText().toString() : "";

            mInlineRenameEditor = null;
            mInlineRenameItem = null;
            mInlineRenameTargetLabel = null;
            mInlineRenameInitialName = null;

            try {
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && editor.getWindowToken() != null) {
                    imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
                }
            } catch (Throwable ignored) {}

            if (targetLabel != null) {
                targetLabel.setVisibility(View.VISIBLE);
            }
            if (editor.getParent() == this) {
                removeView(editor);
            }
            setDescendantFocusability(mSavedDescendantFocusability);
            requestFocus();

            try {
                if (commit && item != null && item.backingFile != null) {
                    String newName = rawText.replace("\r", "").replace("\n", " ").trim();
                    if (!TextUtils.isEmpty(newName) && !newName.equals(initialName)) {
                        if (newName.contains("/") || newName.startsWith(".")) {
                            Toast.makeText(context, "Invalid name", Toast.LENGTH_SHORT).show();
                        } else {
                            String newKey = renameFileBasedItem(context, item, newName);
                            if (newKey != null) {
                                mSelectedKeys.remove(item.storageKey);
                                mSelectedKeys.add(newKey);
                                mSelectionAnchorKey = newKey;
                                rebuildGrid();
                            } else {
                                Toast.makeText(context, "Rename failed (name may already exist)", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
            } finally {
                mFinishingInlineRename = false;
            }
        }

        private void showFilePropertiesDialog(Context context, DesktopShortcutItem item) {
            File f = item.backingFile;
            if (f == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(item.label).append('\n');
            sb.append("File: ").append(f.getName()).append('\n');
            sb.append("Type: ").append(item.isDirectory ? "Folder" : (item.isFileEntry ? item.mimeType : "DeX+ Shortcut (.dexsc)")).append('\n');
            sb.append("Path: ").append(f.getAbsolutePath()).append('\n');
            if (!item.isDirectory) {
                sb.append("Size: ").append(formatByteSize(f.length())).append('\n');
            } else {
                File[] children = f.listFiles();
                sb.append("Items: ").append(children != null ? children.length : 0).append('\n');
            }
            sb.append("Modified: ").append(DateFormat.getDateTimeInstance().format(new Date(f.lastModified())));
            if (item.component != null) {
                sb.append("\nTarget: ").append(item.component.flattenToShortString());
            } else if (!TextUtils.isEmpty(item.customPackage)) {
                sb.append("\nPackage: ").append(item.customPackage);
            }
            new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Properties")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show();
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
        final boolean isFileEntry;
        final File backingFile;
        final boolean isDirectory;
        final String mimeType;

        DesktopShortcutItem(ComponentName component, String label, Drawable icon) {
            this(component.flattenToString(), component, null, component.getPackageName(),
                    label, icon, false, false, null, false, "");
        }

        DesktopShortcutItem(String storageKey, Intent customIntent, String customPackage, String label, Drawable icon) {
            this(storageKey, null, customIntent, customPackage, label, icon, true, false, null, false, "");
        }

        DesktopShortcutItem(
                String storageKey,
                ComponentName component,
                Intent customIntent,
                String customPackage,
                String label,
                Drawable icon,
                boolean isCustomShortcut,
                boolean isFileEntry,
                File backingFile,
                boolean isDirectory,
                String mimeType) {
            this.storageKey = storageKey;
            this.component = component;
            this.customIntent = customIntent;
            this.customPackage = customPackage != null ? customPackage : "";
            this.label = label != null ? label : "";
            this.icon = icon;
            this.isCustomShortcut = isCustomShortcut;
            this.isFileEntry = isFileEntry;
            this.backingFile = backingFile;
            this.isDirectory = isDirectory;
            this.mimeType = mimeType != null ? mimeType : "*/*";
        }

        String getPackageName() {
            if (component != null) return component.getPackageName();
            return customPackage != null ? customPackage : "";
        }
    }

    private static SharedPreferences getDesktopPrefs(Context context) {
        return context.getSharedPreferences(DESKTOP_PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isFileBasedDesktop(Context context) {
        return context != null && ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FILE_BASED, false);
    }

    private static File getDesktopDirectory(Context context) {
        String path = ModConfig.getString(context, ModConfig.KEY_DESKTOP_FOLDER_PATH, ModConfig.DEFAULT_DESKTOP_FOLDER_PATH);
        if (TextUtils.isEmpty(path)) {
            path = ModConfig.DEFAULT_DESKTOP_FOLDER_PATH;
        }
        File dir = new File(path);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        if (dir.isFile()) {
            dir = new File(ModConfig.DEFAULT_DESKTOP_FOLDER_PATH);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static String sanitizeFileName(String name) {
        if (TextUtils.isEmpty(name)) return "Shortcut";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\r\n]", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned.isEmpty() ? "Shortcut" : cleaned;
    }

    private static File uniqueChildFile(File dir, String desiredName) {
        File candidate = new File(dir, desiredName);
        if (!candidate.exists()) return candidate;
        int dot = desiredName.lastIndexOf('.');
        String base = dot > 0 ? desiredName.substring(0, dot) : desiredName;
        String ext = dot > 0 ? desiredName.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    private static List<String> readSavedKeyOrder(Context context) {
        if (isFileBasedDesktop(context)) {
            return readLinesFile(new File(getDesktopDirectory(context), DOTFILE_ORDER));
        }
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

    private static List<String> readLinesFile(File file) {
        List<String> lines = new ArrayList<>();
        if (file == null || !file.exists()) return lines;
        Set<String> seen = new LinkedHashSet<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && seen.add(trimmed)) {
                    lines.add(trimmed);
                }
            }
        } catch (Throwable ignored) {}
        return lines;
    }

    private static void writeLinesFile(File file, Iterable<String> lines) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                if (!TextUtils.isEmpty(line)) {
                    bw.write(line);
                    bw.newLine();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static List<DesktopShortcutItem> loadDesktopShortcuts(Context context, int maxCount) {
        if (isFileBasedDesktop(context)) {
            return loadFileBasedDesktopItems(context, maxCount);
        }
        return loadPrefsDesktopShortcuts(context, maxCount);
    }

    private static List<DesktopShortcutItem> loadPrefsDesktopShortcuts(Context context, int maxCount) {
        SharedPreferences prefs = getDesktopPrefs(context);
        String savedRaw = prefs.getString(KEY_SHORTCUT_LIST, null);
        PackageManager pm = context.getPackageManager();
        List<DesktopShortcutItem> result = new ArrayList<>();

        if (savedRaw != null) {
            if (!savedRaw.trim().isEmpty()) {
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
            }
            return result;
        }

        // Initialize default Desktop with only the System Settings icon
        List<DesktopShortcutItem> allApps = queryAllLaunchableApps(context);
        Set<String> addedKeys = new LinkedHashSet<>();
        for (DesktopShortcutItem item : allApps) {
            if ("com.android.settings".equals(item.getPackageName())) {
                result.add(item);
                addedKeys.add(item.storageKey);
                break;
            }
        }

        savePrefsShortcutList(context, addedKeys);
        return result;
    }

    private static List<DesktopShortcutItem> loadFileBasedDesktopItems(Context context, int maxCount) {
        File dir = getDesktopDirectory(context);
        File initMarker = new File(dir, DOTFILE_INIT);
        if (!initMarker.exists()) {
            seedFileDesktopDefaults(context, dir, maxCount);
        }

        File[] rawFiles = dir.listFiles();
        Map<String, DesktopShortcutItem> byKey = new HashMap<>();
        List<DesktopShortcutItem> discovered = new ArrayList<>();
        if (rawFiles != null) {
            Arrays.sort(rawFiles, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : rawFiles) {
                if (f == null || f.getName().startsWith(".")) continue;
                DesktopShortcutItem item = buildFileDesktopItem(context, f);
                if (item != null) {
                    byKey.put(item.storageKey, item);
                    discovered.add(item);
                }
            }
        }

        List<String> savedOrder = readLinesFile(new File(dir, DOTFILE_ORDER));
        List<DesktopShortcutItem> result = new ArrayList<>();
        Set<String> finalOrder = new LinkedHashSet<>();
        for (String k : savedOrder) {
            DesktopShortcutItem match = byKey.get(k);
            if (match != null && finalOrder.add(k)) {
                result.add(match);
            }
        }
        boolean orderChanged = (finalOrder.size() != savedOrder.size());
        for (DesktopShortcutItem item : discovered) {
            if (finalOrder.add(item.storageKey)) {
                result.add(item);
                orderChanged = true;
            }
        }
        if (orderChanged) {
            writeLinesFile(new File(dir, DOTFILE_ORDER), finalOrder);
        }
        return result;
    }

    private static void seedFileDesktopDefaults(Context context, File dir, int maxCount) {
        try {
            boolean hasAnyDexsc = false;
            File[] existingFiles = dir.listFiles();
            if (existingFiles != null) {
                for (File f : existingFiles) {
                    if (f != null && f.getName().toLowerCase(Locale.ROOT).endsWith(DEXSC_EXT)) {
                        hasAnyDexsc = true;
                        break;
                    }
                }
            }

            if (!hasAnyDexsc) {
                List<DesktopShortcutItem> allApps = queryAllLaunchableApps(context);
                List<String> initialOrder = new ArrayList<>();
                for (DesktopShortcutItem item : allApps) {
                    if ("com.android.settings".equals(item.getPackageName())) {
                        File created = writeDexscShortcutFile(
                                context,
                                dir,
                                null,
                                item.label,
                                item.component,
                                item.customIntent,
                                item.customPackage,
                                item.icon);
                        if (created != null) {
                            initialOrder.add(FILE_ENTRY_PREFIX + created.getName());
                        }
                        break;
                    }
                }
                if (!initialOrder.isEmpty()) {
                    writeLinesFile(new File(dir, DOTFILE_ORDER), initialOrder);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            new File(dir, DOTFILE_INIT).createNewFile();
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": seedFileDesktopDefaults error: " + t);
        }
    }

    private static void importNonFileShortcutsToFileDesktop(Context context) {
        if (context == null) return;
        File dir = getDesktopDirectory(context);
        List<DesktopShortcutItem> legacyItems = loadPrefsDesktopShortcuts(context, 200);
        List<DesktopShortcutItem> existingItems = loadFileBasedDesktopItems(context, 200);

        Set<String> existingComponents = new HashSet<>();
        Set<String> existingIntents = new HashSet<>();
        for (DesktopShortcutItem ex : existingItems) {
            if (ex.component != null) {
                existingComponents.add(ex.component.flattenToString());
            } else if (ex.isCustomShortcut && ex.customIntent != null) {
                existingIntents.add(ex.customIntent.toUri(Intent.URI_INTENT_SCHEME));
            }
        }

        List<String> order = readLinesFile(new File(dir, DOTFILE_ORDER));
        int imported = 0;
        for (DesktopShortcutItem item : legacyItems) {
            if (item.component != null && existingComponents.contains(item.component.flattenToString())) {
                continue;
            }
            if (item.isCustomShortcut && item.customIntent != null
                    && existingIntents.contains(item.customIntent.toUri(Intent.URI_INTENT_SCHEME))) {
                continue;
            }
            File created = writeDexscShortcutFile(
                    context, dir, null, item.label, item.component, item.customIntent, item.customPackage, item.icon);
            if (created != null) {
                String key = FILE_ENTRY_PREFIX + created.getName();
                if (!order.contains(key)) order.add(key);
                if (item.component != null) existingComponents.add(item.component.flattenToString());
                if (item.customIntent != null) existingIntents.add(item.customIntent.toUri(Intent.URI_INTENT_SCHEME));
                imported++;
            }
        }
        if (imported > 0) {
            writeLinesFile(new File(dir, DOTFILE_ORDER), order);
            // Ensure init marker exists so seed doesn't overwrite later
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, DOTFILE_INIT).createNewFile();
            } catch (Throwable ignored) {}
            showToastOnMain(context, "Created " + imported + " shortcut file(s) in " + dir.getName());
        } else {
            showToastOnMain(context, "All classic workspace icons are already in " + dir.getName());
        }
    }

    private static DesktopShortcutItem buildFileDesktopItem(Context context, File f) {
        String name = f.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        String key = FILE_ENTRY_PREFIX + name;
        if (!f.isDirectory() && lower.endsWith(DEXSC_EXT)) {
            DesktopShortcutItem sc = parseDexscFile(context, f, key);
            if (sc != null) return sc;
        }
        if (!f.isDirectory() && lower.endsWith(URL_EXT)) {
            DesktopShortcutItem urlSc = parseUrlShortcutFile(context, f, key);
            if (urlSc != null) return urlSc;
        }
        if (f.isDirectory()) {
            return new DesktopShortcutItem(
                    key, null, null, "", name, createFolderIcon(context),
                    false, true, f, true, "vnd.android.document/directory");
        }
        String ext = getFileExtension(name);
        String mime = guessMimeType(ext);
        Drawable icon = createFileTypeIcon(context, ext, mime);
        return new DesktopShortcutItem(
                key, null, null, "", name, icon,
                false, true, f, false, mime);
    }

    private static File writeDexscShortcutFile(
            Context context,
            File dir,
            File existingTargetFile,
            String label,
            ComponentName component,
            Intent customIntent,
            String pkg,
            Drawable icon) {
        try {
            String safeLabel = !TextUtils.isEmpty(label) ? label.replace('\n', ' ').trim() : "Shortcut";
            File outFile = existingTargetFile;
            if (outFile == null) {
                String base = sanitizeFileName(safeLabel);
                outFile = uniqueChildFile(dir, base + DEXSC_EXT);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[DexShortcut]\n");
            sb.append("name=").append(safeLabel).append('\n');
            if (component != null) {
                sb.append("type=app\n");
                sb.append("component=").append(component.flattenToString()).append('\n');
                sb.append("package=").append(component.getPackageName()).append('\n');
            } else if (customIntent != null) {
                sb.append("type=intent\n");
                sb.append("intent=").append(customIntent.toUri(Intent.URI_INTENT_SCHEME)).append('\n');
                if (!TextUtils.isEmpty(pkg)) {
                    sb.append("package=").append(pkg).append('\n');
                }
            }
            if (icon != null) {
                Bitmap bmp = drawableToBitmap(icon, 192);
                if (bmp != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, baos);
                    String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    sb.append("icon=").append(b64).append('\n');
                }
            }
            try (BufferedWriter bw = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
                bw.write(sb.toString());
            }
            return outFile;
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": writeDexscShortcutFile error: " + t);
            return null;
        }
    }

    private static DesktopShortcutItem parseDexscFile(Context context, File file, String storageKey) {
        try {
            String name = null;
            String type = null;
            String compStr = null;
            String pkg = null;
            String intentStr = null;
            String iconB64 = null;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(eq + 1).trim();
                    if ("name".equals(k)) name = v;
                    else if ("type".equals(k)) type = v;
                    else if ("component".equals(k)) compStr = v;
                    else if ("package".equals(k)) pkg = v;
                    else if ("intent".equals(k)) intentStr = v;
                    else if ("icon".equals(k)) iconB64 = v;
                }
            }

            Drawable icon = null;
            if (!TextUtils.isEmpty(iconB64)) {
                try {
                    byte[] raw = Base64.decode(iconB64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (bmp != null) {
                        icon = new BitmapDrawable(context.getResources(), bmp);
                    }
                } catch (Throwable ignored) {}
            }

            PackageManager pm = context.getPackageManager();
            ComponentName cn = !TextUtils.isEmpty(compStr) ? ComponentName.unflattenFromString(compStr) : null;
            if (("app".equals(type) || cn != null) && cn != null) {
                try {
                    android.content.pm.ActivityInfo ai = pm.getActivityInfo(cn, 0);
                    if (TextUtils.isEmpty(name)) {
                        CharSequence cs = ai.loadLabel(pm);
                        name = cs != null ? cs.toString() : cn.getPackageName();
                    }
                    if (icon == null) {
                        icon = ai.loadIcon(pm);
                    }
                } catch (Throwable ignored) {}
                if (icon == null && !TextUtils.isEmpty(cn.getPackageName())) {
                    try {
                        icon = pm.getApplicationIcon(cn.getPackageName());
                    } catch (Throwable ignored) {}
                }
                if (icon == null) icon = pm.getDefaultActivityIcon();
                if (TextUtils.isEmpty(name)) {
                    name = stripKnownExtension(file.getName(), DEXSC_EXT);
                }
                return new DesktopShortcutItem(
                        storageKey, cn, null, cn.getPackageName(), name, icon,
                        false, false, file, false, "application/x-dexplus-shortcut");
            }

            if (!TextUtils.isEmpty(intentStr)) {
                Intent parsedIntent = Intent.parseUri(intentStr, Intent.URI_INTENT_SCHEME);
                if (icon == null && !TextUtils.isEmpty(pkg)) {
                    try {
                        icon = pm.getApplicationIcon(pkg);
                    } catch (Throwable ignored) {}
                }
                if (icon == null) icon = pm.getDefaultActivityIcon();
                if (TextUtils.isEmpty(name)) {
                    name = stripKnownExtension(file.getName(), DEXSC_EXT);
                }
                return new DesktopShortcutItem(
                        storageKey, null, parsedIntent, pkg, name, icon,
                        true, false, file, false, "application/x-dexplus-shortcut");
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": parseDexscFile error for " + file.getName() + ": " + t);
        }
        return null;
    }

    private static DesktopShortcutItem parseUrlShortcutFile(Context context, File file, String storageKey) {
        try {
            String url = null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.toLowerCase(Locale.ROOT).startsWith("url=")) {
                        url = trimmed.substring(4).trim();
                        break;
                    }
                }
            }
            if (TextUtils.isEmpty(url)) return null;
            Intent viewUrl = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            viewUrl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String label = stripKnownExtension(file.getName(), URL_EXT);
            Drawable icon = createFileTypeIcon(context, "URL", "text/uri-list");
            return new DesktopShortcutItem(
                    storageKey, null, viewUrl, "", label, icon,
                    true, false, file, false, "text/uri-list");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stripKnownExtension(String name, String ext) {
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT))) {
            return name.substring(0, name.length() - ext.length());
        }
        return name;
    }

    private static String renameFileBasedItem(Context context, DesktopShortcutItem item, String newName) {
        if (context == null || item == null || item.backingFile == null) return null;
        File oldFile = item.backingFile;
        File dir = oldFile.getParentFile();
        if (dir == null) return null;

        boolean isDexsc = oldFile.getName().toLowerCase(Locale.ROOT).endsWith(DEXSC_EXT);
        String targetFileName;
        if (isDexsc) {
            String cleanBase = sanitizeFileName(stripKnownExtension(newName, DEXSC_EXT));
            targetFileName = cleanBase + DEXSC_EXT;
        } else {
            targetFileName = newName;
        }

        File newFile = new File(dir, targetFileName);
        if (!newFile.equals(oldFile) && newFile.exists()) {
            return null;
        }
        if (!newFile.equals(oldFile) && !oldFile.renameTo(newFile)) {
            return null;
        }
        if (isDexsc) {
            String displayName = stripKnownExtension(newName, DEXSC_EXT);
            writeDexscShortcutFile(context, dir, newFile, displayName, item.component, item.customIntent, item.getPackageName(), item.icon);
        }

        String oldKey = item.storageKey;
        String newKey = FILE_ENTRY_PREFIX + newFile.getName();
        if (!oldKey.equals(newKey)) {
            List<String> order = readSavedKeyOrder(context);
            List<String> updatedOrder = new ArrayList<>();
            boolean replaced = false;
            for (String k : order) {
                if (k.equals(oldKey)) {
                    updatedOrder.add(newKey);
                    replaced = true;
                } else {
                    updatedOrder.add(k);
                }
            }
            if (!replaced) updatedOrder.add(newKey);
            saveShortcutList(context, new LinkedHashSet<>(updatedOrder));

            Map<String, int[]> positions = loadShortcutPositions(context);
            int[] oldPos = positions.remove(oldKey);
            if (oldPos != null) {
                positions.put(newKey, oldPos);
                saveShortcutPositions(context, positions);
            }
        }
        return newKey;
    }

    private static String getFileExtension(String filename) {
        if (TextUtils.isEmpty(filename)) return "";
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot >= filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String guessMimeType(String ext) {
        if (TextUtils.isEmpty(ext)) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (!TextUtils.isEmpty(mime)) return mime;
        if ("mkv".equals(ext) || "webm".equals(ext) || "avi".equals(ext) || "mov".equals(ext) || "mp4".equals(ext)) return "video/*";
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "webp".equals(ext) || "gif".equals(ext)) return "image/*";
        if ("mp3".equals(ext) || "flac".equals(ext) || "wav".equals(ext) || "m4a".equals(ext) || "ogg".equals(ext)) return "audio/*";
        if ("md".equals(ext) || "log".equals(ext) || "json".equals(ext) || "ini".equals(ext) || "sh".equals(ext)
                || "py".equals(ext) || "java".equals(ext) || "kt".equals(ext) || "js".equals(ext) || "csv".equals(ext)) return "text/plain";
        if ("apk".equals(ext)) return "application/vnd.android.package-archive";
        if ("pdf".equals(ext)) return "application/pdf";
        if ("zip".equals(ext) || "rar".equals(ext) || "7z".equals(ext)) return "application/zip";
        return "application/octet-stream";
    }

    private static boolean isTextLikeFile(File f, String mime) {
        if (mime != null && mime.startsWith("text/")) return true;
        if (f == null) return false;
        String ext = getFileExtension(f.getName());
        return "txt".equals(ext) || "md".equals(ext) || "log".equals(ext) || "json".equals(ext)
                || "xml".equals(ext) || "ini".equals(ext) || "cfg".equals(ext) || "csv".equals(ext)
                || "sh".equals(ext) || "py".equals(ext) || "java".equals(ext) || "kt".equals(ext)
                || "js".equals(ext) || "yml".equals(ext) || "yaml".equals(ext);
    }

    private static String formatByteSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static Drawable createFolderIcon(Context context) {
        int s = 144;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backPaint.setColor(Color.rgb(217, 119, 6));
        Path tabPath = new Path();
        RectF tabRect = new RectF(14, 24, 68, 52);
        tabPath.addRoundRect(tabRect, 10, 10, Path.Direction.CW);
        canvas.drawPath(tabPath, backPaint);

        RectF backBody = new RectF(14, 36, 130, 114);
        canvas.drawRoundRect(backBody, 14, 14, backPaint);

        Paint frontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        frontPaint.setColor(Color.rgb(251, 191, 36));
        RectF frontRect = new RectF(14, 46, 130, 118);
        canvas.drawRoundRect(frontRect, 14, 14, frontPaint);

        Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setColor(Color.argb(95, 255, 255, 255));
        canvas.drawRect(22, 50, 122, 54, highlightPaint);

        return new BitmapDrawable(context.getResources(), bmp);
    }

    private static Drawable createFileTypeIcon(Context context, String ext, String mime) {
        int s = 144;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        int accentColor = Color.rgb(100, 116, 139);
        if (mime.startsWith("video/")) accentColor = Color.rgb(139, 92, 246);
        else if (mime.startsWith("image/")) accentColor = Color.rgb(16, 185, 129);
        else if (mime.startsWith("audio/")) accentColor = Color.rgb(236, 72, 153);
        else if ("application/pdf".equals(mime)) accentColor = Color.rgb(239, 68, 68);
        else if ("application/vnd.android.package-archive".equals(mime)) accentColor = Color.rgb(34, 197, 94);
        else if ("application/zip".equals(mime)) accentColor = Color.rgb(245, 158, 11);
        else if (mime.startsWith("text/") || "text/uri-list".equals(mime)) accentColor = Color.rgb(59, 130, 246);

        Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pagePaint.setColor(Color.rgb(248, 250, 252));
        RectF pageRect = new RectF(24, 14, 120, 130);
        canvas.drawRoundRect(pageRect, 14, 14, pagePaint);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.5f);
        strokePaint.setColor(Color.rgb(203, 213, 225));
        canvas.drawRoundRect(pageRect, 14, 14, strokePaint);

        Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bannerPaint.setColor(accentColor);
        RectF bannerRect = new RectF(32, 74, 112, 112);
        canvas.drawRoundRect(bannerRect, 8, 8, bannerPaint);

        if (mime.startsWith("video/")) {
            Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            triPaint.setColor(accentColor);
            Path play = new Path();
            play.moveTo(62, 30);
            play.lineTo(62, 60);
            play.lineTo(88, 45);
            play.close();
            canvas.drawPath(play, triPaint);
        } else if ("application/vnd.android.package-archive".equals(mime)) {
            drawAndroidRobotHead(canvas, 72f, 52f, 22f, Color.rgb(34, 197, 94), Color.WHITE);
        } else {
            Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(Color.rgb(203, 213, 225));
            canvas.drawRoundRect(new RectF(36, 30, 108, 36), 3, 3, linePaint);
            canvas.drawRoundRect(new RectF(36, 44, 96, 50), 3, 3, linePaint);
            canvas.drawRoundRect(new RectF(36, 58, 84, 64), 3, 3, linePaint);
        }

        String badge = !TextUtils.isEmpty(ext) ? ext.toUpperCase(Locale.ROOT) : "FILE";
        if (badge.length() > 4) badge = badge.substring(0, 4);
        Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        txtPaint.setColor(Color.WHITE);
        txtPaint.setFakeBoldText(true);
        txtPaint.setTextSize(badge.length() <= 3 ? 24f : 19f);
        txtPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = txtPaint.getFontMetrics();
        float ty = bannerRect.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(badge, bannerRect.centerX(), ty, txtPaint);

        return new BitmapDrawable(context.getResources(), bmp);
    }

    private static void drawAndroidRobotHead(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            int robotColor,
            int eyeColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(robotColor);
        p.setStyle(Paint.Style.FILL);
        RectF head = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(head, 180f, 180f, true, p);

        Paint antPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        antPaint.setColor(robotColor);
        antPaint.setStrokeWidth(Math.max(2f, radius * 0.14f));
        antPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - radius * 0.42f, cy - radius * 0.72f, cx - radius * 0.74f, cy - radius * 1.18f, antPaint);
        canvas.drawLine(cx + radius * 0.42f, cy - radius * 0.72f, cx + radius * 0.74f, cy - radius * 1.18f, antPaint);

        Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyePaint.setColor(eyeColor);
        float eyeR = Math.max(1.5f, radius * 0.13f);
        canvas.drawCircle(cx - radius * 0.40f, cy - radius * 0.36f, eyeR, eyePaint);
        canvas.drawCircle(cx + radius * 0.40f, cy - radius * 0.36f, eyeR, eyePaint);
    }

    private static Bitmap makeApkBadgedBitmap(Bitmap appIconBmp, int sizePx) {
        if (appIconBmp == null || sizePx <= 0) return null;
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        float margin = sizePx * 0.04f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(appIconBmp, null, new RectF(margin, margin, sizePx - margin, sizePx - margin), p);

        // Distinct green Android mascot badge in the bottom-right corner
        float badgeR = sizePx * 0.21f;
        float bx = sizePx - badgeR - sizePx * 0.03f;
        float by = sizePx - badgeR - sizePx * 0.03f;

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.rgb(22, 163, 74));
        canvas.drawCircle(bx, by, badgeR, bgPaint);

        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(2f, sizePx * 0.025f));
        ringPaint.setColor(Color.WHITE);
        canvas.drawCircle(bx, by, badgeR - ringPaint.getStrokeWidth() * 0.5f, ringPaint);

        drawAndroidRobotHead(canvas, bx, by + badgeR * 0.26f, badgeR * 0.58f, Color.WHITE, Color.rgb(22, 163, 74));
        return out;
    }

    private static void scheduleAsyncThumbnailIfNeeded(
            final Context context,
            final DesktopShortcutItem item,
            final ImageView iconView,
            final int iconSizePx) {
        if (item == null || item.backingFile == null || iconView == null) return;
        final String mime = item.mimeType != null ? item.mimeType : "";
        final String ext = getFileExtension(item.backingFile.getName());
        final boolean isImage = mime.startsWith("image/");
        final boolean isVideo = mime.startsWith("video/");
        final boolean isApk = "apk".equals(ext) || "application/vnd.android.package-archive".equals(mime);
        if (!isImage && !isVideo && !isApk) return;

        final File f = item.backingFile;
        final String cacheKey = f.getAbsolutePath() + ":" + f.lastModified() + ":" + f.length();
        iconView.setTag(item.storageKey);

        Bitmap cached = sThumbCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            iconView.setImageDrawable(new BitmapDrawable(context.getResources(), cached));
            return;
        }

        final int targetPx = Math.max(96, Math.min(220, iconSizePx * 2));
        sThumbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap generated = null;
                try {
                    if (isImage) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                        int sample = 1;
                        while ((opts.outWidth / sample) > targetPx * 2 && (opts.outHeight / sample) > targetPx * 2) {
                            sample *= 2;
                        }
                        opts.inJustDecodeBounds = false;
                        opts.inSampleSize = sample;
                        Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                        if (decoded != null) {
                            generated = makeRoundedSquareThumb(decoded, targetPx, false);
                            if (decoded != generated) decoded.recycle();
                        }
                    } else if (isVideo) {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        try {
                            mmr.setDataSource(f.getAbsolutePath());
                            Bitmap frame = mmr.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (frame == null) frame = mmr.getFrameAtTime(0L);
                            if (frame != null) {
                                generated = makeRoundedSquareThumb(frame, targetPx, true);
                                if (frame != generated) frame.recycle();
                            }
                        } finally {
                            try {
                                mmr.release();
                            } catch (Throwable ignored) {}
                        }
                    } else if (isApk) {
                        PackageManager pm = context.getPackageManager();
                        PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                        if (pi != null && pi.applicationInfo != null) {
                            ApplicationInfo ai = pi.applicationInfo;
                            ai.sourceDir = f.getAbsolutePath();
                            ai.publicSourceDir = f.getAbsolutePath();
                            Drawable apkIcon = ai.loadIcon(pm);
                            if (apkIcon != null) {
                                Bitmap rawBmp = drawableToBitmap(apkIcon, targetPx);
                                if (rawBmp != null) {
                                    generated = makeApkBadgedBitmap(rawBmp, targetPx);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                if (generated != null && !generated.isRecycled()) {
                    sThumbCache.put(cacheKey, generated);
                    final Bitmap ready = generated;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (item.storageKey.equals(iconView.getTag())) {
                                iconView.setImageDrawable(new BitmapDrawable(context.getResources(), ready));
                            }
                        }
                    });
                }
            }
        });
    }

    private static Bitmap makeRoundedSquareThumb(Bitmap src, int sizePx, boolean drawPlayBadge) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        Path clipPath = new Path();
        RectF bounds = new RectF(2, 2, sizePx - 2, sizePx - 2);
        clipPath.addRoundRect(bounds, sizePx * 0.14f, sizePx * 0.14f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);

        float scale = Math.max(bounds.width() / sw, bounds.height() / sh);
        float dw = sw * scale;
        float dh = sh * scale;
        float dx = bounds.left + (bounds.width() - dw) * 0.5f;
        float dy = bounds.top + (bounds.height() - dh) * 0.5f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(src, null, new RectF(dx, dy, dx + dw, dy + dh), p);
        canvas.restore();

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(2f, sizePx * 0.02f));
        border.setColor(Color.argb(140, 255, 255, 255));
        canvas.drawRoundRect(bounds, sizePx * 0.14f, sizePx * 0.14f, border);

        if (drawPlayBadge) {
            float cx = sizePx * 0.5f;
            float cy = sizePx * 0.5f;
            float r = sizePx * 0.22f;
            Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            circlePaint.setColor(Color.argb(165, 15, 23, 42));
            canvas.drawCircle(cx, cy, r, circlePaint);

            Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            triPaint.setColor(Color.WHITE);
            Path tri = new Path();
            tri.moveTo(cx - r * 0.32f, cy - r * 0.48f);
            tri.lineTo(cx - r * 0.32f, cy + r * 0.48f);
            tri.lineTo(cx + r * 0.56f, cy);
            tri.close();
            canvas.drawPath(tri, triPaint);
        }
        return out;
    }

    private static boolean deleteRecursive(File target) {
        if (target == null || !target.exists()) return false;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return target.delete();
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

    private static String saveAndAddCustomShortcutWithKey(
            Context context,
            String key,
            String label,
            Intent intent,
            Drawable icon,
            String pkg) {
        if (isFileBasedDesktop(context)) {
            File dir = getDesktopDirectory(context);
            List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
            String targetUri = intent != null ? intent.toUri(Intent.URI_INTENT_SCHEME) : "";
            for (DesktopShortcutItem existing : current) {
                if (existing.isCustomShortcut && existing.customIntent != null && existing.backingFile != null
                        && targetUri.equals(existing.customIntent.toUri(Intent.URI_INTENT_SCHEME))) {
                    writeDexscShortcutFile(context, dir, existing.backingFile, label, null, intent, pkg, icon);
                    return existing.storageKey;
                }
            }
            File created = writeDexscShortcutFile(context, dir, null, label, null, intent, pkg, icon);
            if (created != null) {
                String fileKey = FILE_ENTRY_PREFIX + created.getName();
                List<String> keys = readSavedKeyOrder(context);
                if (!keys.contains(fileKey)) {
                    keys.add(fileKey);
                    saveShortcutList(context, new LinkedHashSet<>(keys));
                }
                return fileKey;
            }
            return null;
        }
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
        return addShortcutToDesktop(context, key) ? key : null;
    }

    private static boolean saveAndAddCustomShortcut(
            Context context,
            String key,
            String label,
            Intent intent,
            Drawable icon,
            String pkg) {
        return saveAndAddCustomShortcutWithKey(context, key, label, intent, icon, pkg) != null;
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
                String resolvedKey = addShortcutToDesktopWithKey(ctx, flat, null, null);
                String effectiveKey = resolvedKey != null ? resolvedKey : flat;
                if (targetCol != null && targetRow != null) {
                    assignOrSwapSlot(ctx, effectiveKey, targetCol, targetRow);
                }
                return effectiveKey;
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
                String resolvedKey = saveAndAddCustomShortcutWithKey(ctx, key, label, deep, null, pkg);
                String effectiveKey = resolvedKey != null ? resolvedKey : key;
                if (targetCol != null && targetRow != null) {
                    assignOrSwapSlot(ctx, effectiveKey, targetCol, targetRow);
                }
                return effectiveKey;
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": addClipIntentToWorkspace error: " + t);
        }
        return null;
    }

    private static Map<String, int[]> parsePositionsText(String raw) {
        Map<String, int[]> map = new HashMap<>();
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

    private static String serializePositionsText(Map<String, int[]> positions) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : positions.entrySet()) {
            String key = e.getKey();
            int[] pos = e.getValue();
            if (TextUtils.isEmpty(key) || pos == null || pos.length != 2) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append('\t').append(pos[0]).append(',').append(pos[1]);
        }
        return sb.toString();
    }

    private static Map<String, int[]> loadPrefsShortcutPositions(Context context) {
        String raw = getDesktopPrefs(context).getString(KEY_SHORTCUT_POSITIONS, "");
        return parsePositionsText(raw);
    }

    private static void saveFileShortcutPositions(File dir, Map<String, int[]> positions) {
        File layoutFile = new File(dir, DOTFILE_LAYOUT);
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(layoutFile), StandardCharsets.UTF_8))) {
            bw.write(serializePositionsText(positions));
        } catch (Throwable ignored) {}
    }

    private static Map<String, int[]> loadShortcutPositions(Context context) {
        if (isFileBasedDesktop(context)) {
            File layoutFile = new File(getDesktopDirectory(context), DOTFILE_LAYOUT);
            if (!layoutFile.exists()) return new HashMap<>();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(layoutFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            } catch (Throwable ignored) {}
            return parsePositionsText(sb.toString());
        }
        return loadPrefsShortcutPositions(context);
    }

    private static void saveShortcutPositions(Context context, Map<String, int[]> positions) {
        if (isFileBasedDesktop(context)) {
            saveFileShortcutPositions(getDesktopDirectory(context), positions);
            return;
        }
        getDesktopPrefs(context).edit().putString(KEY_SHORTCUT_POSITIONS, serializePositionsText(positions)).commit();
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
        DesktopGridMetrics gm = computeDesktopGridMetrics(context, 0, 0);
        int cols = gm.cols;
        int rows = gm.rows;
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

    private static void moveMultipleSlots(Context context, List<String> draggedKeys, int rawDCol, int rawDRow) {
        if (context == null || draggedKeys == null || draggedKeys.isEmpty()) return;
        DesktopGridMetrics gm = computeDesktopGridMetrics(context, 0, 0);
        int cols = gm.cols;
        int rows = gm.rows;

        List<DesktopShortcutItem> items = loadDesktopShortcuts(context, cols * rows);
        Map<String, int[]> placements = resolveGridPlacements(context, items, cols, rows);

        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE, minRow = Integer.MAX_VALUE;
        List<String> validDragged = new ArrayList<>();
        for (String k : draggedKeys) {
            int[] p = placements.get(k);
            if (p != null) {
                validDragged.add(k);
                if (p[0] < minCol) minCol = p[0];
                if (p[0] > maxCol) maxCol = p[0];
                if (p[1] < minRow) minRow = p[1];
            }
        }
        if (validDragged.isEmpty()) return;

        int dCol = Math.max(-minCol, Math.min((cols - 1) - maxCol, rawDCol));
        int dRow = Math.max(-minRow, rawDRow);
        if (dCol == 0 && dRow == 0) return;

        Set<String> draggedSet = new HashSet<>(validDragged);
        List<int[]> vacatedSlots = new ArrayList<>();
        Map<String, int[]> newDraggedPositions = new HashMap<>();
        Set<String> targetSlotIds = new HashSet<>();

        for (String k : validDragged) {
            int[] oldP = placements.get(k);
            vacatedSlots.add(new int[]{oldP[0], oldP[1]});
            int[] nextP = new int[]{oldP[0] + dCol, oldP[1] + dRow};
            newDraggedPositions.put(k, nextP);
            targetSlotIds.add(nextP[0] + "," + nextP[1]);
        }

        // Collect stationary occupants sitting on destination slots so they can be relocated into vacated slots
        List<String> displacedKeys = new ArrayList<>();
        Set<String> occupied = new HashSet<>(targetSlotIds);
        for (Map.Entry<String, int[]> e : placements.entrySet()) {
            String k = e.getKey();
            if (draggedSet.contains(k)) continue;
            int[] p = e.getValue();
            if (p == null) continue;
            String id = p[0] + "," + p[1];
            if (targetSlotIds.contains(id)) {
                displacedKeys.add(k);
            } else {
                occupied.add(id);
            }
        }

        for (Map.Entry<String, int[]> e : newDraggedPositions.entrySet()) {
            placements.put(e.getKey(), e.getValue());
        }

        int vacIdx = 0;
        for (String dk : displacedKeys) {
            int[] relocated = null;
            while (vacIdx < vacatedSlots.size()) {
                int[] cand = vacatedSlots.get(vacIdx++);
                String cid = cand[0] + "," + cand[1];
                if (occupied.add(cid)) {
                    relocated = cand;
                    break;
                }
            }
            if (relocated == null) {
                relocated = findFirstFreeSlot(occupied, cols, rows);
                occupied.add(relocated[0] + "," + relocated[1]);
            }
            placements.put(dk, relocated);
        }

        saveShortcutPositions(context, placements);
    }

    private static void resetDesktopShortcuts(Context context) {
        if (isFileBasedDesktop(context)) {
            File dir = getDesktopDirectory(context);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f == null) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (!f.isDirectory() && n.endsWith(DEXSC_EXT)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            new File(dir, DOTFILE_LAYOUT).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, DOTFILE_ORDER).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, DOTFILE_INIT).delete();
        }
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

    private static String addShortcutToDesktopWithKey(
            Context context,
            String componentKey,
            String optionalLabel,
            Drawable optionalIcon,
            boolean[] outAdded) {
        if (outAdded != null && outAdded.length > 0) outAdded[0] = false;
        if (isFileBasedDesktop(context)) {
            if (TextUtils.isEmpty(componentKey)) return null;
            List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
            for (DesktopShortcutItem existing : current) {
                if (existing.component != null && componentKey.equals(existing.component.flattenToString())) {
                    return existing.storageKey;
                }
            }
            ComponentName cn = ComponentName.unflattenFromString(componentKey);
            if (cn == null) return null;
            String label = optionalLabel;
            Drawable icon = optionalIcon;
            try {
                PackageManager pm = context.getPackageManager();
                android.content.pm.ActivityInfo ai = pm.getActivityInfo(cn, 0);
                if (TextUtils.isEmpty(label)) {
                    CharSequence cs = ai.loadLabel(pm);
                    if (cs != null) label = cs.toString();
                }
                if (icon == null) {
                    icon = ai.loadIcon(pm);
                }
            } catch (Throwable ignored) {}
            if (TextUtils.isEmpty(label)) label = cn.getPackageName();

            File dir = getDesktopDirectory(context);
            File created = writeDexscShortcutFile(context, dir, null, label, cn, null, cn.getPackageName(), icon);
            if (created == null) return null;
            String fileKey = FILE_ENTRY_PREFIX + created.getName();
            List<String> keys = readSavedKeyOrder(context);
            if (!keys.contains(fileKey)) {
                keys.add(fileKey);
                saveShortcutList(context, new LinkedHashSet<>(keys));
            }
            if (outAdded != null && outAdded.length > 0) outAdded[0] = true;
            return fileKey;
        }
        boolean added = addShortcutToDesktop(context, componentKey);
        if (outAdded != null && outAdded.length > 0) outAdded[0] = added;
        return componentKey;
    }

    private static String addShortcutToDesktopWithKey(
            Context context,
            String componentKey,
            String optionalLabel,
            Drawable optionalIcon) {
        return addShortcutToDesktopWithKey(context, componentKey, optionalLabel, optionalIcon, null);
    }

    private static boolean addShortcutToDesktop(Context context, String storageKey) {
        if (isFileBasedDesktop(context)) {
            boolean[] added = new boolean[1];
            addShortcutToDesktopWithKey(context, storageKey, null, null, added);
            return added[0];
        }
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
        if (isFileBasedDesktop(context) && storageKey != null && storageKey.startsWith(FILE_ENTRY_PREFIX)) {
            String fileName = storageKey.substring(FILE_ENTRY_PREFIX.length());
            File target = new File(getDesktopDirectory(context), fileName);
            deleteRecursive(target);
        }
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
        moveMultipleShortcuts(context, Collections.singletonList(draggedKey), targetKey);
    }

    private static void moveMultipleShortcuts(Context context, List<String> draggedKeys, String targetKey) {
        if (draggedKeys == null || draggedKeys.isEmpty()) return;
        List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
        List<String> order = new ArrayList<>();
        for (DesktopShortcutItem item : current) {
            order.add(item.storageKey);
        }
        List<String> moving = new ArrayList<>();
        for (String dk : draggedKeys) {
            if (order.contains(dk) && !moving.contains(dk)) {
                moving.add(dk);
            }
        }
        if (moving.isEmpty()) return;

        int firstFromIdx = order.indexOf(moving.get(0));
        int origTargetIdx = targetKey != null ? order.indexOf(targetKey) : -1;
        order.removeAll(moving);
        if (targetKey == null) {
            order.addAll(moving);
        } else {
            int toIdx = order.indexOf(targetKey);
            if (toIdx < 0) {
                order.addAll(moving);
            } else if (origTargetIdx >= 0 && firstFromIdx <= origTargetIdx) {
                order.addAll(Math.min(order.size(), toIdx + 1), moving);
            } else {
                order.addAll(toIdx, moving);
            }
        }
        saveShortcutList(context, new LinkedHashSet<>(order));
    }

    private static void sortShortcutsAlphabetically(Context context) {
        List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
        Collections.sort(current, new Comparator<DesktopShortcutItem>() {
            @Override
            public int compare(DesktopShortcutItem a, DesktopShortcutItem b) {
                if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Set<String> set = new LinkedHashSet<>();
        for (DesktopShortcutItem item : current) {
            set.add(item.storageKey);
        }
        saveShortcutList(context, set);
        if (ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREE_PLACEMENT, true)) {
            int rows = computeDesktopGridMetrics(context, 0, 0).rows;
            Map<String, int[]> sortedPos = new HashMap<>();
            for (int i = 0; i < current.size(); i++) {
                sortedPos.put(current.get(i).storageKey, new int[]{i / rows, i % rows});
            }
            saveShortcutPositions(context, sortedPos);
        }
    }

    private static void savePrefsShortcutList(Context context, Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String f : keys) {
            if (f == null || f.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(f);
        }
        getDesktopPrefs(context).edit().putString(KEY_SHORTCUT_LIST, sb.toString()).commit();
    }

    private static void saveShortcutList(Context context, Set<String> keys) {
        if (isFileBasedDesktop(context)) {
            writeLinesFile(new File(getDesktopDirectory(context), DOTFILE_ORDER), keys);
            return;
        }
        savePrefsShortcutList(context, keys);
    }

    private static void launchShortcut(Context context, DesktopShortcutItem item, boolean freeform) {
        if (item == null) return;
        if (item.isFileEntry && item.backingFile != null) {
            if (item.isDirectory) {
                openFolderEntry(context, item.backingFile, freeform);
            } else {
                openFileEntry(context, item.backingFile, item.mimeType, freeform);
            }
            return;
        }
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

    private static ActivityOptions makeLaunchOptions(boolean freeform) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        if (freeform) {
            try {
                XposedHelpers.callMethod(opts, "setLaunchWindowingMode", 5);
            } catch (Throwable ignored) {}
        }
        return opts;
    }

    private static void openFolderEntry(Context context, File folder, boolean freeform) {
        if (context == null || folder == null) return;
        String absPath = folder.getAbsolutePath();
        if ("/sdcard".equals(absPath)) {
            absPath = "/storage/emulated/0";
        } else if (absPath.startsWith("/sdcard/")) {
            absPath = "/storage/emulated/0/" + absPath.substring("/sdcard/".length());
        }
        ActivityOptions opts = makeLaunchOptions(freeform);

        try {
            Intent myFiles = new Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES");
            myFiles.putExtra("samsung.myfiles.intent.extra.START_PATH", absPath);
            myFiles.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(myFiles, opts.toBundle());
            return;
        } catch (Throwable ignored) {}

        try {
            String rel = absPath;
            if (rel.startsWith("/storage/emulated/0/")) {
                rel = rel.substring("/storage/emulated/0/".length());
            } else if ("/storage/emulated/0".equals(rel)) {
                rel = "";
            }
            Uri docUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"
                    + Uri.encode(rel));
            Intent docsIntent = new Intent(Intent.ACTION_VIEW);
            docsIntent.setDataAndType(docUri, "vnd.android.document/directory");
            docsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(docsIntent, opts.toBundle());
            return;
        } catch (Throwable ignored) {}

        showFolderFallbackDialog(context, folder, freeform);
    }

    private static void showFolderFallbackDialog(final Context context, final File folder, final boolean freeform) {
        File[] children = folder.listFiles();
        final List<File> entries = new ArrayList<>();
        if (children != null) {
            Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File c : children) {
                if (c != null && !c.getName().startsWith(".")) {
                    entries.add(c);
                }
            }
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            File c = entries.get(i);
            labels[i] = (c.isDirectory() ? "[Folder] " : "") + c.getName();
        }
        AlertDialog.Builder b = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(folder.getName())
                .setNegativeButton("Close", null);
        if (labels.length == 0) {
            b.setMessage("This folder is empty.");
        } else {
            b.setItems(labels, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which >= 0 && which < entries.size()) {
                        File picked = entries.get(which);
                        if (picked.isDirectory()) {
                            openFolderEntry(context, picked, freeform);
                        } else {
                            String mime = guessMimeType(getFileExtension(picked.getName()));
                            openFileEntry(context, picked, mime, freeform);
                        }
                    }
                }
            });
        }
        b.show();
    }

    private static void openFileEntry(
            final Context context,
            final File file,
            final String mimeType,
            final boolean freeform) {
        if (context == null || file == null || !file.exists()) return;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(DEXSC_EXT)) {
            DesktopShortcutItem sc = parseDexscFile(context, file, FILE_ENTRY_PREFIX + file.getName());
            if (sc != null && !sc.isFileEntry) {
                launchShortcut(context, sc, freeform);
                return;
            }
        }
        if (lower.endsWith(URL_EXT)) {
            DesktopShortcutItem url = parseUrlShortcutFile(context, file, FILE_ENTRY_PREFIX + file.getName());
            if (url != null) {
                launchShortcut(context, url, freeform);
                return;
            }
        }
        if (lower.endsWith(".apk")) {
            launchApkInstaller(context, file, freeform);
            return;
        }

        sThumbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final Uri contentUri = resolveContentUriForFile(context, file);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (contentUri != null) {
                                Intent view = new Intent(Intent.ACTION_VIEW);
                                view.setDataAndType(contentUri, mimeType);
                                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                context.startActivity(view, makeLaunchOptions(freeform).toBundle());
                                return;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(ModConfig.TAG + ": openFileEntry intent failed: " + t);
                        }
                        if (isTextLikeFile(file, mimeType)) {
                            showQuickTextViewerDialog(context, file);
                        } else {
                            Toast.makeText(context, "No app available to open " + file.getName(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private static void launchApkInstaller(final Context context, final File apkFile, final boolean freeform) {
        sThumbExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final String stagedName = sanitizeFileName(apkFile.getName());
                boolean stagedOk = false;
                try {
                    Uri stageUri = Uri.parse("content://com.udunus.dexplus.provider/apk/" + Uri.encode(stagedName));
                    try (FileInputStream fis = new FileInputStream(apkFile);
                         java.io.OutputStream os = context.getContentResolver().openOutputStream(stageUri, "w")) {
                        if (os != null) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = fis.read(buf)) > 0) {
                                os.write(buf, 0, n);
                            }
                            os.flush();
                            stagedOk = true;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(ModConfig.TAG + ": launchApkInstaller stage error: " + t);
                }

                final boolean ok = stagedOk;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            try {
                                Intent bridge = new Intent();
                                bridge.setComponent(new ComponentName(
                                        ModConfig.MODULE_PACKAGE, "com.udunus.dexplus.ui.ApkInstallActivity"));
                                bridge.putExtra("staged_name", stagedName);
                                bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(bridge, makeLaunchOptions(freeform).toBundle());
                                return;
                            } catch (Throwable t) {
                                XposedBridge.log(ModConfig.TAG + ": ApkInstallActivity launch error: " + t);
                            }
                        }
                        Toast.makeText(context, "Unable to open installer for " + apkFile.getName(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static Uri resolveContentUriForFile(Context context, File file) {
        String absPath = file.getAbsolutePath();
        Uri found = queryMediaStoreUri(context, absPath);
        if (found != null) return found;

        final Uri[] scanned = new Uri[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            MediaScannerConnection.scanFile(
                    context.getApplicationContext(),
                    new String[]{absPath},
                    null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            scanned[0] = uri;
                            latch.countDown();
                        }
                    });
            latch.await(600L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}

        if (scanned[0] != null) return scanned[0];
        return queryMediaStoreUri(context, absPath);
    }

    private static Uri queryMediaStoreUri(Context context, String absPath) {
        Cursor cursor = null;
        try {
            Uri external = MediaStore.Files.getContentUri("external");
            cursor = context.getContentResolver().query(
                    external,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DATA + "=?",
                    new String[]{absPath},
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(external, id);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void showQuickTextViewerDialog(final Context context, final File file) {
        if (context == null || file == null) return;
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int total = 0;
            int read;
            while ((read = br.read(buf)) > 0 && total < 65536) {
                content.append(buf, 0, read);
                total += read;
            }
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to read " + file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }

        final EditText editor = new EditText(context);
        editor.setText(content.toString());
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(8);
        editor.setMaxLines(18);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dpToPx(context, 16), dpToPx(context, 10), dpToPx(context, 16), dpToPx(context, 6));
        wrap.addView(editor);

        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(file.getName())
                .setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try (BufferedWriter bw = new BufferedWriter(
                                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                            bw.write(editor.getText().toString());
                            Toast.makeText(context, "Saved " + file.getName(), Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
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
            Toast.makeText(context, "DeX+ Settings not found", Toast.LENGTH_SHORT).show();
        }
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }
}
