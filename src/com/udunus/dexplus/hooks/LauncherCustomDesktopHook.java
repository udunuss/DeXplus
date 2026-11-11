package com.udunus.dexplus.hooks;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.udunus.dexplus.util.ModConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LauncherCustomDesktopHook {
    private static final String TAG_DEX_DESKTOP_VIEW = "minsos_custom_dex_desktop";
    private static final String DESKTOP_PREFS = "minsos_dex_desktop_shortcuts";
    private static final String KEY_SHORTCUT_LIST = "shortcut_components_v1";

    private static volatile FrameLayout sActiveDesktopContainer;
    private static volatile ViewGroup sActiveWorkspaceContainer;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        hookWorkspaceContainer(cl);
        hookInternalDexTransition(cl);

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
                        if (ctx != null && LauncherDexTaskbarHook.isBuiltInDisplay(ctx)
                                && ModConfig.isModuleEnabled(ctx)
                                && ModConfig.getBoolean(ctx, ModConfig.KEY_CUSTOM_DESKTOP_ENABLED, false)
                                && LauncherDexTaskbarHook.isInternalDexActive(ctx, null)) {
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
        boolean showCustomDesktop = ModConfig.isModuleEnabled(ctx)
                && ModConfig.getBoolean(ctx, ModConfig.KEY_CUSTOM_DESKTOP_ENABLED, false)
                && LauncherDexTaskbarHook.isInternalDexActive(ctx, null);

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
     * when enabled.
     */
    private static final class ClassicDexDesktopView extends FrameLayout {
        private final GridLayout mGridLayout;
        private final View mPopupAnchor;
        private View mSelectedCell;
        private long mLastClickTime = 0L;
        private String mLastClickedComponent = null;
        private float mLastTouchX = 200f;
        private float mLastTouchY = 200f;

        ClassicDexDesktopView(final Context context) {
            super(context);
            setClipToPadding(false);
            setClickable(true);
            setLongClickable(true);

            // 1x1 invisible anchor view positioned at the exact mouse/touch coordinates for context menus
            mPopupAnchor = new View(context);
            FrameLayout.LayoutParams anchorLp = new FrameLayout.LayoutParams(1, 1);
            mPopupAnchor.setVisibility(View.INVISIBLE);
            addView(mPopupAnchor, anchorLp);

            mGridLayout = new GridLayout(context);
            mGridLayout.setOrientation(GridLayout.VERTICAL); // Top-to-bottom, then left-to-right
            FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            addView(mGridLayout, gridLp);

            // Empty desktop click clears selection; long-press / right-click opens Workspace context menu
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearSelection();
                }
            });
            setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    showEmptyDesktopMenu(mLastTouchX, mLastTouchY);
                    return true;
                }
            });
            setOnGenericMotionListener(new OnGenericMotionListener() {
                @Override
                public boolean onGenericMotion(View v, MotionEvent event) {
                    mLastTouchX = event.getX();
                    mLastTouchY = event.getY();
                    if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                            && event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                        showEmptyDesktopMenu(event.getX(), event.getY());
                        return true;
                    }
                    return false;
                }
            });

            registerDesktopCommandReceiver(context);
            rebuildGrid();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev != null) {
                mLastTouchX = ev.getX();
                mLastTouchY = ev.getY();
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    android.view.ViewParent p = getParent();
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(true);
                        p = p.getParent();
                    }
                }
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent ev) {
            if (ev != null) {
                mLastTouchX = ev.getX();
                mLastTouchY = ev.getY();
            }
            return super.dispatchGenericMotionEvent(ev);
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
            try {
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                IntentFilter filter = new IntentFilter();
                filter.addAction(ModConfig.ACTION_DESKTOP_COMMAND);
                BroadcastReceiver r = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        if (intent == null) return;
                        if (ModConfig.ACTION_DESKTOP_COMMAND.equals(intent.getAction())) {
                            String cmd = intent.getStringExtra("command");
                            if ("reset_shortcuts".equals(cmd)) {
                                getDesktopPrefs(ctx).edit().remove(KEY_SHORTCUT_LIST).commit();
                            }
                            rebuildGrid();
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    appCtx.registerReceiver(r, filter, 2 /* RECEIVER_EXPORTED */);
                } else {
                    appCtx.registerReceiver(r, filter);
                }
            } catch (Throwable ignored) {}
        }

        void rebuildGrid() {
            Context ctx = getContext();
            if (ctx == null) return;

            int cols = Math.max(6, Math.min(16, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_GRID_COLS, 10)));
            int rows = Math.max(4, Math.min(10, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_GRID_ROWS, 5)));
            int iconScale = Math.max(65, Math.min(150, ModConfig.getInt(ctx, ModConfig.KEY_DESKTOP_ICON_SCALE, 100)));

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int padLeft = dpToPx(ctx, 22);
            int padTop = dpToPx(ctx, 26);
            int padRight = dpToPx(ctx, 22);
            int padBottom = dpToPx(ctx, 76); // Leave room for Internal DeX Taskbar at bottom
            mGridLayout.setPadding(padLeft, padTop, padRight, padBottom);

            mGridLayout.removeAllViews();
            mGridLayout.setColumnCount(cols);
            mGridLayout.setRowCount(rows);

            int availW = Math.max(300, dm.widthPixels - padLeft - padRight);
            int availH = Math.max(250, dm.heightPixels - padTop - padBottom);
            int cellW = availW / cols;
            int cellH = availH / rows;
            int iconSizePx = Math.round(dpToPx(ctx, 50) * (iconScale / 100.0f));
            iconSizePx = Math.min(iconSizePx, Math.min(cellW - dpToPx(ctx, 16), cellH - dpToPx(ctx, 28)));

            List<DesktopShortcutItem> items = loadDesktopShortcuts(ctx, cols * rows);
            int maxItems = Math.min(items.size(), cols * rows);
            for (int i = 0; i < maxItems; i++) {
                final DesktopShortcutItem item = items.get(i);
                View cell = createDesktopShortcutCell(ctx, item, cellW, cellH, iconSizePx);
                mGridLayout.addView(cell);
            }
        }

        private View createDesktopShortcutCell(
                final Context context,
                final DesktopShortcutItem item,
                int cellW,
                int cellH,
                int iconSizePx) {
            final LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            int pad = dpToPx(context, 6);
            cell.setPadding(pad, dpToPx(context, 8), pad, pad);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellH;
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
                        selectCell(cell);
                        if (item.componentFlat.equals(mLastClickedComponent) && (now - mLastClickTime) < 420L) {
                            mLastClickTime = 0L;
                            launchShortcut(context, item, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                        } else {
                            mLastClickedComponent = item.componentFlat;
                            mLastClickTime = now;
                        }
                    } else {
                        selectCell(cell);
                        launchShortcut(context, item, ModConfig.getBoolean(context, ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true));
                    }
                }
            });

            cell.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    selectCell(cell);
                    showShortcutContextMenu(context, cell, item);
                    return true;
                }
            });

            cell.setOnGenericMotionListener(new OnGenericMotionListener() {
                @Override
                public boolean onGenericMotion(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                            && event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                        selectCell(cell);
                        showShortcutContextMenu(context, cell, item);
                        return true;
                    }
                    return false;
                }
            });

            return cell;
        }

        private void selectCell(View cell) {
            clearSelection();
            mSelectedCell = cell;
            if (cell != null) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setColor(Color.argb(65, 59, 130, 246));
                selBg.setCornerRadius(dpToPx(getContext(), 10));
                selBg.setStroke(dpToPx(getContext(), 1), Color.argb(160, 147, 197, 253));
                cell.setBackground(selBg);
            }
        }

        private void clearSelection() {
            if (mSelectedCell != null) {
                mSelectedCell.setBackground(null);
                mSelectedCell = null;
            }
        }

        private void showShortcutContextMenu(final Context context, View anchor, final DesktopShortcutItem item) {
            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(0, 1, 0, "Open in window");
            popup.getMenu().add(0, 2, 1, "Open fullscreen");
            popup.getMenu().add(0, 3, 2, "App info");
            popup.getMenu().add(0, 4, 3, "Remove from workspace");
            popup.getMenu().add(0, 5, 4, "Uninstall");
            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem menuItem) {
                    int id = menuItem.getItemId();
                    if (id == 1) {
                        launchShortcut(context, item, true);
                        return true;
                    } else if (id == 2) {
                        launchShortcut(context, item, false);
                        return true;
                    } else if (id == 3) {
                        openAppInfo(context, item.component.getPackageName());
                        return true;
                    } else if (id == 4) {
                        removeShortcutFromDesktop(context, item.componentFlat);
                        rebuildGrid();
                        return true;
                    } else if (id == 5) {
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
                            getDesktopPrefs(context).edit().remove(KEY_SHORTCUT_LIST).commit();
                            rebuildGrid();
                            return true;
                        case 5:
                            try {
                                Intent wp = new Intent(Intent.ACTION_SET_WALLPAPER);
                                wp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(wp);
                            } catch (Throwable ignored) {}
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
                CharSequence[] labels = new CharSequence[allApps.size()];
                for (int i = 0; i < allApps.size(); i++) {
                    labels[i] = allApps.get(i).label;
                }
                new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Add App Shortcut to Workspace")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which >= 0 && which < allApps.size()) {
                                    addShortcutToDesktop(context, allApps.get(which).componentFlat);
                                    rebuildGrid();
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Throwable t) {
                XposedBridge.log(ModConfig.TAG + ": showAddAppShortcutDialog error: " + t);
            }
        }
    }

    private static final class DesktopShortcutItem {
        final ComponentName component;
        final String componentFlat;
        final String label;
        final Drawable icon;

        DesktopShortcutItem(ComponentName component, String label, Drawable icon) {
            this.component = component;
            this.componentFlat = component.flattenToString();
            this.label = label;
            this.icon = icon;
        }
    }

    private static SharedPreferences getDesktopPrefs(Context context) {
        return context.getSharedPreferences(DESKTOP_PREFS, Context.MODE_PRIVATE);
    }

    private static List<DesktopShortcutItem> loadDesktopShortcuts(Context context, int maxCount) {
        SharedPreferences prefs = getDesktopPrefs(context);
        String savedRaw = prefs.getString(KEY_SHORTCUT_LIST, null);
        PackageManager pm = context.getPackageManager();
        List<DesktopShortcutItem> result = new ArrayList<>();

        if (savedRaw != null && !savedRaw.trim().isEmpty()) {
            String[] parts = savedRaw.split(";");
            for (String flat : parts) {
                if (flat == null || flat.isEmpty()) continue;
                ComponentName cn = ComponentName.unflattenFromString(flat);
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

        Set<String> addedFlats = new LinkedHashSet<>();
        for (String prefPkg : preferredPackages) {
            for (DesktopShortcutItem item : allApps) {
                if (prefPkg.equals(item.component.getPackageName()) && !addedFlats.contains(item.componentFlat)) {
                    result.add(item);
                    addedFlats.add(item.componentFlat);
                    break;
                }
            }
        }

        for (DesktopShortcutItem item : allApps) {
            if (result.size() >= Math.min(16, maxCount)) break;
            if (!addedFlats.contains(item.componentFlat)
                    && !"com.sec.android.app.launcher".equals(item.component.getPackageName())) {
                result.add(item);
                addedFlats.add(item.componentFlat);
            }
        }

        saveShortcutList(context, addedFlats);
        return result;
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

    private static void addShortcutToDesktop(Context context, String componentFlat) {
        SharedPreferences prefs = getDesktopPrefs(context);
        String raw = prefs.getString(KEY_SHORTCUT_LIST, "");
        Set<String> set = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(";")) {
                if (!s.isEmpty()) set.add(s);
            }
        }
        set.add(componentFlat);
        saveShortcutList(context, set);
    }

    private static void removeShortcutFromDesktop(Context context, String componentFlat) {
        SharedPreferences prefs = getDesktopPrefs(context);
        String raw = prefs.getString(KEY_SHORTCUT_LIST, "");
        Set<String> set = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(";")) {
                if (!s.isEmpty() && !s.equals(componentFlat)) set.add(s);
            }
        }
        saveShortcutList(context, set);
    }

    private static void sortShortcutsAlphabetically(Context context) {
        List<DesktopShortcutItem> current = loadDesktopShortcuts(context, 100);
        Collections.sort(current, new Comparator<DesktopShortcutItem>() {
            @Override
            public int compare(DesktopShortcutItem a, DesktopShortcutItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Set<String> set = new LinkedHashSet<>();
        for (DesktopShortcutItem item : current) {
            set.add(item.componentFlat);
        }
        saveShortcutList(context, set);
    }

    private static void saveShortcutList(Context context, Set<String> flats) {
        StringBuilder sb = new StringBuilder();
        for (String f : flats) {
            if (f == null || f.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(f);
        }
        getDesktopPrefs(context).edit().putString(KEY_SHORTCUT_LIST, sb.toString()).commit();
    }

    private static void launchShortcut(Context context, DesktopShortcutItem item, boolean freeform) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(item.component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

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
