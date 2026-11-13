package com.udunus.dexplus.hooks;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherCustomDesktopHook {
    private static final String TAG_DEX_DESKTOP_VIEW = "minsos_custom_dex_desktop";
    private static final String DESKTOP_PREFS = "minsos_dex_desktop_shortcuts";
    private static final String KEY_SHORTCUT_LIST = "shortcut_components_v1";
    private static final String CUSTOM_SHORTCUT_PREFIX = "sc:";
    private static final String CUSTOM_SHORTCUT_DIR = "dexplus_shortcuts";

    private static volatile FrameLayout sActiveDesktopContainer;
    private static volatile ViewGroup sActiveWorkspaceContainer;
    private static volatile String sLastRedirectedKey;
    private static volatile long sLastRedirectedTime;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        hookWorkspaceContainer(cl);
        hookInternalDexTransition(cl);
        hookAddToHomeRedirection(cl);

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
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": hookWorkspaceContainer error: " + t);
        }
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
        if (ctx == null || baseItem == null) return false;
        String clsName = baseItem.getClass().getName();

        // AppItem -> launchable ComponentName
        if (clsName.endsWith(".AppItem")) {
            try {
                Object compKey = XposedHelpers.callMethod(baseItem, "getComponent");
                ComponentName cn = compKey != null
                        ? (ComponentName) XposedHelpers.callMethod(compKey, "getComponentName") : null;
                if (cn != null) {
                    String flat = cn.flattenToString();
                    if (isRecentlyRedirected(flat)) return true;
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
                    syncDesktopVisibilityOnMainThread();
                    if (showToast) {
                        showToastOnMain(ctx, added
                                ? "Added \"" + label + "\" to workspace"
                                : "\"" + label + "\" is already on workspace");
                    }
                    return true;
                }
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": redirect AppItem error: " + t);
            }
            return false;
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
                if (intent == null) return false;

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
                if (isRecentlyRedirected(key)) return true;

                boolean added = saveAndAddCustomShortcut(ctx, key, label, intent, icon, pkg);
                syncDesktopVisibilityOnMainThread();
                if (showToast) {
                    showToastOnMain(ctx, added
                            ? "Added \"" + label + "\" to workspace"
                            : "Updated \"" + label + "\" on workspace");
                }
                return true;
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": redirect ShortcutItem error: " + t);
            }
        }
        return false;
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
     * when enabled. Supports reliable secondary/right-click, drag-and-drop icon arranging,
     * keyboard navigation, and hover states.
     */
    private static final class ClassicDexDesktopView extends FrameLayout {
        private final ScrollView mScrollView;
        private final GridLayout mGridLayout;
        private final View mPopupAnchor;
        private final LinearLayout mArrangeBar;
        private final List<View> mCellViews = new ArrayList<>();
        private View mSelectedCell;
        private DesktopShortcutItem mSelectedItem;
        private BroadcastReceiver mCommandReceiver;
        private long mLastClickTime = 0L;
        private String mLastClickedKey = null;
        private float mLastTouchX = 200f;
        private float mLastTouchY = 200f;
        private boolean mArrangeMode = false;
        private boolean mConsumingSecondaryClick = false;
        private long mLastSecondaryMenuTime = 0L;

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

            // 1x1 invisible anchor view positioned at the exact mouse/touch coordinates for context menus
            mPopupAnchor = new View(context);
            FrameLayout.LayoutParams anchorLp = new FrameLayout.LayoutParams(1, 1);
            mPopupAnchor.setVisibility(View.INVISIBLE);
            addView(mPopupAnchor, anchorLp);

            mScrollView = new ScrollView(context);
            mScrollView.setFillViewport(true);
            mScrollView.setVerticalScrollBarEnabled(false);

            mGridLayout = new GridLayout(context);
            mGridLayout.setOrientation(GridLayout.VERTICAL); // Top-to-bottom, then left-to-right
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

            // Floating bar shown only while "Arrange icons" mode is active
            mArrangeBar = buildArrangeModeBar(context);
            mArrangeBar.setVisibility(View.GONE);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            barLp.topMargin = dpToPx(context, 14);
            addView(mArrangeBar, barLp);

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
                mGridLayout.setOnContextClickListener(emptyContextClick);
            }

            // Dropping onto empty grid space moves the dragged icon to the end
            mGridLayout.setOnDragListener(new OnDragListener() {
                @Override
                public boolean onDrag(View v, DragEvent event) {
                    if (!(event.getLocalState() instanceof DesktopShortcutItem)) return false;
                    int action = event.getAction();
                    if (action == DragEvent.ACTION_DRAG_STARTED) {
                        return true;
                    } else if (action == DragEvent.ACTION_DROP) {
                        DesktopShortcutItem dragged = (DesktopShortcutItem) event.getLocalState();
                        moveShortcut(context, dragged.storageKey, null);
                        rebuildGrid();
                        return true;
                    }
                    return true;
                }
            });

            rebuildGrid();
        }

        private LinearLayout buildArrangeModeBar(final Context context) {
            LinearLayout bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dpToPx(context, 14);
            int padV = dpToPx(context, 8);
            bar.setPadding(padH, padV, padH, padV);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(232, 24, 28, 38));
            bg.setCornerRadius(dpToPx(context, 22));
            bg.setStroke(dpToPx(context, 1), Color.argb(140, 96, 165, 250));
            bar.setBackground(bg);
            bar.setElevation(dpToPx(context, 8));

            TextView hint = new TextView(context);
            hint.setText("Drag icons to rearrange");
            hint.setTextColor(Color.rgb(241, 245, 249));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.rightMargin = dpToPx(context, 12);
            bar.addView(hint, hintLp);

            bar.addView(createPillAction(context, "Sort A–Z", false, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    sortShortcutsAlphabetically(context);
                    rebuildGrid();
                }
            }));

            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            doneLp.leftMargin = dpToPx(context, 8);
            TextView doneBtn = createPillAction(context, "Done", true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setArrangeMode(false);
                }
            });
            bar.addView(doneBtn, doneLp);

            return bar;
        }

        private TextView createPillAction(Context context, String label, boolean accent, OnClickListener listener) {
            TextView btn = new TextView(context);
            btn.setText(label);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            btn.setTextColor(Color.WHITE);
            btn.setPadding(dpToPx(context, 12), dpToPx(context, 5), dpToPx(context, 12), dpToPx(context, 5));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(accent ? Color.rgb(37, 99, 235) : Color.argb(60, 255, 255, 255));
            pill.setCornerRadius(dpToPx(context, 14));
            btn.setBackground(pill);
            btn.setOnClickListener(listener);
            return btn;
        }

        private void setArrangeMode(boolean enabled) {
            mArrangeMode = enabled;
            mArrangeBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
            if (enabled) {
                mArrangeBar.bringToFront();
            }
            rebuildGrid();
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
            if (keyCode == KeyEvent.KEYCODE_ESCAPE && mArrangeMode) {
                setArrangeMode(false);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private void handleSecondaryMenuAt(float viewX, float viewY) {
            long now = SystemClock.uptimeMillis();
            if (now - mLastSecondaryMenuTime < 180L) return;
            mLastSecondaryMenuTime = now;

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
                if (gx >= child.getLeft() && gx < child.getRight()
                        && gy >= child.getTop() && gy < child.getBottom()) {
                    return child;
                }
            }
            return null;
        }

        private View prepareAnchorAt(float x, float y) {
            int maxX = Math.max(1, getWidth() - dpToPx(getContext(), 8));
            int maxY = Math.max(1, getHeight() - dpToPx(getContext(), 8));
            int px = Math.max(0, Math.min((int) x, maxX));
            int py = Math.max(0, Math.min((int) y, maxY));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPopupAnchor.getLayoutParams();
            if (lp != null) {
                lp.leftMargin = px;
                lp.topMargin = py;
                mPopupAnchor.setLayoutParams(lp);
            }
            mPopupAnchor.layout(px, py, px + 1, py + 1);
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
            mCellViews.clear();

            int cols = Math.max(6, Math.min(16, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_GRID_COLS, 10)));
            int rows = Math.max(4, Math.min(10, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_GRID_ROWS, 5)));
            int iconScale = Math.max(65, Math.min(150, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_ICON_SCALE, 100)));

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int totalW = getWidth() > 0 ? getWidth() : dm.widthPixels;
            int totalH = getHeight() > 0 ? getHeight() : dm.heightPixels;
            int padLeft = dpToPx(ctx, 22);
            int padTop = dpToPx(ctx, mArrangeMode ? 58 : 26);
            int padRight = dpToPx(ctx, 22);
            int taskbarPx = LauncherDexTaskbarHook.getScaledTaskbarHeightPx(ctx);
            int padBottom = taskbarPx > 0 ? taskbarPx + dpToPx(ctx, 16) : dpToPx(ctx, 76);
            mGridLayout.setPadding(padLeft, padTop, padRight, padBottom);

            List<DesktopShortcutItem> items = loadDesktopShortcuts(ctx, cols * rows);
            int effectiveRows = Math.max(rows, (items.size() + cols - 1) / cols);

            mGridLayout.removeAllViews();
            mGridLayout.setColumnCount(cols);
            mGridLayout.setRowCount(effectiveRows);

            int availW = Math.max(300, totalW - padLeft - padRight);
            int availH = Math.max(250, totalH - padTop - padBottom);
            int cellW = availW / cols;
            int cellH = availH / rows;
            int iconSizePx = Math.round(dpToPx(ctx, 50) * (iconScale / 100.0f));
            iconSizePx = Math.max(dpToPx(ctx, 20),
                    Math.min(iconSizePx, Math.min(cellW - dpToPx(ctx, 16), cellH - dpToPx(ctx, 28))));

            for (int i = 0; i < items.size(); i++) {
                final DesktopShortcutItem item = items.get(i);
                View cell = createDesktopShortcutCell(ctx, item, cellW, cellH, iconSizePx);
                mGridLayout.addView(cell);
                mCellViews.add(cell);
                if (previousSelectedKey != null && previousSelectedKey.equals(item.storageKey)) {
                    selectCell(cell, item);
                }
            }
        }

        private View createDesktopShortcutCell(
                final Context context,
                final DesktopShortcutItem item,
                int cellW,
                int cellH,
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

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellH;
            cell.setLayoutParams(lp);

            if (mArrangeMode) {
                applyArrangeIdleBackground(cell);
            }

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
                    if (mArrangeMode) {
                        selectCell(cell, item);
                        return;
                    }
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

            cell.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    selectCell(cell, item);
                    if (mArrangeMode) {
                        startCellDrag(cell, item);
                    } else {
                        showShortcutContextMenu(context, cell, item);
                    }
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
                    if (cell == mSelectedCell) return false;
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

            // Direct mouse/stylus drag or any-finger drag in Arrange Mode
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
                        dragStarted = false;
                        int tool = event.getToolType(0);
                        boolean primaryOnly = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0;
                        allowImmediateDrag = primaryOnly && (mArrangeMode
                                || tool == MotionEvent.TOOL_TYPE_MOUSE
                                || tool == MotionEvent.TOOL_TYPE_STYLUS);
                    } else if (action == MotionEvent.ACTION_MOVE && allowImmediateDrag && !dragStarted) {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if ((dx * dx + dy * dy) > (dragSlop * dragSlop)) {
                            dragStarted = true;
                            startCellDrag(cell, item);
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
                    if (!(event.getLocalState() instanceof DesktopShortcutItem)) return false;
                    DesktopShortcutItem dragged = (DesktopShortcutItem) event.getLocalState();
                    switch (event.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED:
                            return true;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            if (!dragged.storageKey.equals(item.storageKey)) {
                                GradientDrawable dropBg = new GradientDrawable();
                                dropBg.setColor(Color.argb(75, 56, 189, 248));
                                dropBg.setCornerRadius(dpToPx(context, 10));
                                dropBg.setStroke(dpToPx(context, 2), Color.argb(220, 125, 211, 252));
                                cell.setBackground(dropBg);
                            }
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                            restoreCellBackground(cell);
                            return true;
                        case DragEvent.ACTION_DROP:
                            restoreCellBackground(cell);
                            if (!dragged.storageKey.equals(item.storageKey)) {
                                moveShortcut(context, dragged.storageKey, item.storageKey);
                                rebuildGrid();
                            }
                            return true;
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

        private static void startCellDrag(View cell, DesktopShortcutItem item) {
            try {
                ClipData clip = ClipData.newPlainText("dexplus_shortcut", item.storageKey);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(cell);
                cell.startDragAndDrop(clip, shadow, item, 0);
                cell.setAlpha(0.35f);
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": startCellDrag error: " + t);
            }
        }

        private void applyArrangeIdleBackground(View cell) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(24, 255, 255, 255));
            bg.setCornerRadius(dpToPx(getContext(), 10));
            bg.setStroke(dpToPx(getContext(), 1), Color.argb(65, 148, 163, 184));
            cell.setBackground(bg);
        }

        private void restoreCellBackground(View cell) {
            if (cell == mSelectedCell) {
                selectCell(cell, mSelectedItem);
            } else if (mArrangeMode) {
                applyArrangeIdleBackground(cell);
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
            final int idMoveEarlier = 3;
            final int idMoveLater = 4;
            final int idArrange = 5;
            final int idAppInfo = 6;
            final int idRemove = 7;
            final int idUninstall = 8;

            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(0, idOpenWin, 0, "Open in window");
            popup.getMenu().add(0, idOpenFull, 1, "Open fullscreen");
            popup.getMenu().add(0, idMoveEarlier, 2, "Move earlier");
            popup.getMenu().add(0, idMoveLater, 3, "Move later");
            popup.getMenu().add(0, idArrange, 4, mArrangeMode ? "Finish arranging" : "Arrange icons");
            if (!TextUtils.isEmpty(item.getPackageName())) {
                popup.getMenu().add(0, idAppInfo, 5, "App info");
            }
            popup.getMenu().add(0, idRemove, 6, "Remove from workspace");
            if (!item.isCustomShortcut && item.component != null) {
                popup.getMenu().add(0, idUninstall, 7, "Uninstall");
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
                    } else if (id == idMoveEarlier) {
                        stepShortcut(context, item.storageKey, -1);
                        rebuildGrid();
                        return true;
                    } else if (id == idMoveLater) {
                        stepShortcut(context, item.storageKey, 1);
                        rebuildGrid();
                        return true;
                    } else if (id == idArrange) {
                        setArrangeMode(!mArrangeMode);
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
            View anchor = prepareAnchorAt(x, y);
            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(0, 1, 0, "Add app shortcut...");
            popup.getMenu().add(0, 2, 1, mArrangeMode ? "Finish arranging" : "Arrange icons");
            popup.getMenu().add(0, 3, 2, "Sort icons by name (A–Z)");
            popup.getMenu().add(0, 4, 3, "Refresh workspace");
            popup.getMenu().add(0, 5, 4, "Reset default shortcuts");
            popup.getMenu().add(0, 6, 5, "Wallpaper & style");
            popup.getMenu().add(0, 7, 6, "Dex+ Settings");
            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    switch (item.getItemId()) {
                        case 1:
                            showAddAppShortcutDialog(context);
                            return true;
                        case 2:
                            setArrangeMode(!mArrangeMode);
                            return true;
                        case 3:
                            sortShortcutsAlphabetically(context);
                            rebuildGrid();
                            return true;
                        case 4:
                            rebuildGrid();
                            return true;
                        case 5:
                            resetDesktopShortcuts(context);
                            rebuildGrid();
                            return true;
                        case 6:
                            try {
                                Intent wp = new Intent(Intent.ACTION_SET_WALLPAPER);
                                wp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(wp);
                            } catch (Throwable ignored) {}
                            return true;
                        case 7:
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

    private static void resetDesktopShortcuts(Context context) {
        getDesktopPrefs(context).edit().remove(KEY_SHORTCUT_LIST).commit();
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

    private static void stepShortcut(Context context, String storageKey, int delta) {
        List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 200);
        List<String> order = new ArrayList<>();
        for (DesktopShortcutItem item : current) {
            order.add(item.storageKey);
        }
        int idx = order.indexOf(storageKey);
        if (idx < 0) return;
        int nextIdx = Math.max(0, Math.min(order.size() - 1, idx + delta));
        if (nextIdx == idx) return;
        order.remove(idx);
        order.add(nextIdx, storageKey);
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

            ActivityOptions opts = ActivityOptions.makeBasic();
            if (freeform) {
                try {
                    XposedHelpers.callMethod(opts, "setLaunchWindowingMode", 5); // WINDOWING_MODE_FREEFORM = 5
                } catch (Throwable ignored) {}
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
