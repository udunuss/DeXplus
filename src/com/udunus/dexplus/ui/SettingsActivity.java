package com.udunus.dexplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.udunus.dexplus.util.ModConfig;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final String STATE_SELECTED_TAB = "selected_tab_index";

    private static final int ICON_OVERVIEW = 0;
    private static final int ICON_WINDOWS = 1;
    private static final int ICON_INT_TASKBAR = 2;
    private static final int ICON_INT_DESKTOP = 3;
    private static final int ICON_INT_DPI = 4;
    private static final int ICON_EXT_TASKBAR = 5;
    private static final int ICON_EXT_DESKTOP = 6;
    private static final int ICON_EXT_DPI = 7;

    private static final int COLOR_OVERVIEW = Color.rgb(59, 130, 246);   // Blue
    private static final int COLOR_SHARED = Color.rgb(139, 92, 246);     // Violet
    private static final int COLOR_INTERNAL = Color.rgb(16, 185, 129);   // Emerald
    private static final int COLOR_EXTERNAL = Color.rgb(245, 158, 11);   // Amber

    private static final class NavEntry {
        final String group;
        final String title;
        final String subtitle;
        final String scopeBadge;
        final int accentColor;
        final int iconKind;

        NavEntry(String group, String title, String subtitle, String scopeBadge, int accentColor, int iconKind) {
            this.group = group;
            this.title = title;
            this.subtitle = subtitle;
            this.scopeBadge = scopeBadge;
            this.accentColor = accentColor;
            this.iconKind = iconKind;
        }
    }

    private static final NavEntry[] NAV_ENTRIES = new NavEntry[]{
            new NavEntry(
                    "OVERVIEW",
                    "Overview",
                    "Status, displays, keyboard, power menu & quick actions",
                    "SYSTEM",
                    COLOR_OVERVIEW,
                    ICON_OVERVIEW),
            new NavEntry(
                    "SHARED",
                    "Window Controls",
                    "Title bar buttons, pinning, fullscreen, corners & window menu",
                    "SHARED · ALL DISPLAYS",
                    COLOR_SHARED,
                    ICON_WINDOWS),
            new NavEntry(
                    "INTERNAL DEX",
                    "Taskbar & Panels",
                    "Built-in display taskbar, panels, search button & scaling",
                    "BUILT-IN DISPLAY",
                    COLOR_INTERNAL,
                    ICON_INT_TASKBAR),
            new NavEntry(
                    "INTERNAL DEX",
                    "Desktop",
                    "Built-in icons, grid placement, file-based folder & shortcuts",
                    "BUILT-IN DISPLAY",
                    COLOR_INTERNAL,
                    ICON_INT_DESKTOP),
            new NavEntry(
                    "INTERNAL DEX",
                    "Display & DPI",
                    "Built-in display density offset for apps, windows & UI",
                    "BUILT-IN DISPLAY",
                    COLOR_INTERNAL,
                    ICON_INT_DPI),
            new NavEntry(
                    "EXTERNAL DEX",
                    "External Taskbar",
                    "Connected monitor taskbar scaling, height & search button",
                    "EXTERNAL DISPLAYS",
                    COLOR_EXTERNAL,
                    ICON_EXT_TASKBAR),
            new NavEntry(
                    "EXTERNAL DEX",
                    "External Desktop",
                    "Independent DeX+ desktop workspace & icon sizing for monitors",
                    "EXTERNAL DISPLAYS",
                    COLOR_EXTERNAL,
                    ICON_EXT_DESKTOP),
            new NavEntry(
                    "EXTERNAL DEX",
                    "External Display & DPI",
                    "Connected monitor density offset for apps, windows & UI",
                    "EXTERNAL DISPLAYS",
                    COLOR_EXTERNAL,
                    ICON_EXT_DPI)
    };

    private SharedPreferences mPrefs;
    private boolean mIsDark;

    private int mBgColor;
    private int mSidebarColor;
    private int mCardColor;
    private int mCardStrokeColor;
    private int mSurfaceElevated;
    private int mTextPrimary;
    private int mTextSecondary;
    private int mAccentColor;
    private int mDividerColor;
    private boolean mIsCompact;

    private int mSelectedTab = 0;
    private final List<LinearLayout> mTabButtons = new ArrayList<>();
    private final List<TextView> mTabTitles = new ArrayList<>();
    private final List<NavIconView> mTabIcons = new ArrayList<>();
    private final List<View> mTabIndicators = new ArrayList<>();
    private final List<View> mTabPages = new ArrayList<>();
    private ScrollView mContentScroll;
    private TextView mPageHeaderTitle;
    private TextView mPageHeaderSubtitle;
    private TextView mPageScopeBadge;

    private static final class SeekBarRowHandle {
        final LinearLayout container;
        final SeekBar seekBar;
        final TextView resetChip;
        final TextView summaryTv;
        final String defaultSummary;

        SeekBarRowHandle(
                LinearLayout container,
                SeekBar seekBar,
                TextView resetChip,
                TextView summaryTv,
                String defaultSummary) {
            this.container = container;
            this.seekBar = seekBar;
            this.resetChip = resetChip;
            this.summaryTv = summaryTv;
            this.defaultSummary = defaultSummary;
        }

        void setSyncedDisabled(boolean disabled, String note) {
            seekBar.setEnabled(!disabled);
            if (resetChip != null) {
                resetChip.setEnabled(!disabled);
                resetChip.setClickable(!disabled);
                resetChip.setAlpha(disabled ? 0.35f : 1.0f);
            }
            container.setAlpha(disabled ? 0.48f : 1.0f);
            summaryTv.setText(disabled && note != null ? note : defaultSummary);
        }
    }

    private SeekBarRowHandle mInternalIconScaleRow;
    private SeekBarRowHandle mExternalIconScaleRow;

    // Hooked by MainHook to return true when active in LSPosed
    public static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mSelectedTab = savedInstanceState.getInt(STATE_SELECTED_TAB, 0);
        }

        mPrefs = ModConfig.getLocalPrefs(this);
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mIsDark = uiMode == Configuration.UI_MODE_NIGHT_YES;

        mBgColor = mIsDark ? Color.rgb(11, 16, 26) : Color.rgb(243, 246, 250);
        mSidebarColor = mIsDark ? Color.rgb(15, 22, 35) : Color.rgb(235, 240, 247);
        mCardColor = mIsDark ? Color.rgb(22, 31, 48) : Color.WHITE;
        mCardStrokeColor = mIsDark ? Color.argb(26, 255, 255, 255) : Color.argb(18, 15, 23, 42);
        mSurfaceElevated = mIsDark ? Color.rgb(31, 43, 64) : Color.rgb(226, 232, 240);
        mTextPrimary = mIsDark ? Color.rgb(244, 247, 252) : Color.rgb(15, 23, 42);
        mTextSecondary = mIsDark ? Color.rgb(148, 163, 184) : Color.rgb(100, 116, 139);
        mAccentColor = COLOR_OVERVIEW;
        mDividerColor = mIsDark ? Color.argb(24, 255, 255, 255) : Color.argb(20, 15, 23, 42);

        mIsCompact = getResources().getConfiguration().screenWidthDp < 620;
        boolean compact = mIsCompact;

        LinearLayout rootSplit = new LinearLayout(this);
        rootSplit.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        rootSplit.setBackgroundColor(mBgColor);

        if (compact) {
            rootSplit.addView(buildCompactTopBar(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View topDivider = new View(this);
            topDivider.setBackgroundColor(mDividerColor);
            rootSplit.addView(topDivider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        } else {
            LinearLayout sidebar = buildSidebar();
            rootSplit.addView(sidebar, new LinearLayout.LayoutParams(
                    dp(268), ViewGroup.LayoutParams.MATCH_PARENT));

            View railDivider = new View(this);
            railDivider.setBackgroundColor(mDividerColor);
            rootSplit.addView(railDivider, new LinearLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setBackgroundColor(mBgColor);

        // Page header bar
        LinearLayout pageHeader = new LinearLayout(this);
        pageHeader.setOrientation(LinearLayout.VERTICAL);
        int hPad = dp(compact ? 18 : 30);
        pageHeader.setPadding(hPad, dp(compact ? 14 : 24), hPad, dp(compact ? 12 : 16));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        mPageHeaderTitle = new TextView(this);
        mPageHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 19f : 23f);
        mPageHeaderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mPageHeaderTitle.setTextColor(mTextPrimary);
        titleRow.addView(mPageHeaderTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mPageScopeBadge = new TextView(this);
        mPageScopeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        mPageScopeBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mPageScopeBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        mPageScopeBadge.setSingleLine(true);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginStart(dp(10));
        titleRow.addView(mPageScopeBadge, badgeLp);

        pageHeader.addView(titleRow);

        mPageHeaderSubtitle = new TextView(this);
        mPageHeaderSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12f : 13f);
        mPageHeaderSubtitle.setTextColor(mTextSecondary);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        pageHeader.addView(mPageHeaderSubtitle, subLp);

        rightPane.addView(pageHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(mDividerColor);
        rightPane.addView(headerDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mContentScroll = new ScrollView(this);
        mContentScroll.setFillViewport(true);

        FrameLayout pageContainer = new FrameLayout(this);
        pageContainer.setPadding(hPad, dp(compact ? 14 : 20), hPad, dp(32));

        mTabPages.clear();
        mTabPages.add(buildOverviewPage());
        mTabPages.add(buildWindowControlsPage());
        mTabPages.add(buildInternalTaskbarPage());
        mTabPages.add(buildInternalDesktopPage());
        mTabPages.add(buildInternalDpiPage());
        mTabPages.add(buildExternalTaskbarPage());
        mTabPages.add(buildExternalDesktopPage());
        mTabPages.add(buildExternalDpiPage());

        for (int i = 0; i < mTabPages.size(); i++) {
            View page = mTabPages.get(i);
            pageContainer.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        mContentScroll.addView(pageContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rightPane.addView(mContentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootSplit.addView(rightPane, compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        selectTab(mSelectedTab);
        refreshSharedDesktopIconSyncState();
        setContentView(rootSplit);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED_TAB, mSelectedTab);
    }

    private int countExternalDisplays() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return 0;
            Display[] displays = dm.getDisplays();
            int ext = 0;
            if (displays != null) {
                for (Display d : displays) {
                    if (d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) {
                        ext++;
                    }
                }
            }
            return ext;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String getAppVersionLabel() {
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (vn != null && !vn.isEmpty()) {
                return "v" + vn;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private LinearLayout buildCompactTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(mSidebarColor);
        bar.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), dp(10));

        TextView appTitle = new TextView(this);
        appTitle.setText("DeX+");
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        appTitle.setTextColor(mTextPrimary);
        header.addView(appTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(createStatusPill(isModuleActive()));
        bar.addView(header);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        mTabButtons.clear();
        mTabTitles.clear();
        mTabIcons.clear();
        mTabIndicators.clear();

        for (int i = 0; i < NAV_ENTRIES.length; i++) {
            final int index = i;
            NavEntry entry = NAV_ENTRIES[i];

            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(11), dp(8), dp(12), dp(8));

            NavIconView icon = new NavIconView(this, entry.iconKind);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
            iconLp.setMarginEnd(dp(6));
            pill.addView(icon, iconLp);

            TextView titleTv = new TextView(this);
            titleTv.setText(entry.title);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            titleTv.setSingleLine(true);
            pill.addView(titleTv);

            View dummyInd = new View(this);
            dummyInd.setVisibility(View.GONE);

            pill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.setMarginEnd(dp(6));
            tabRow.addView(pill, itemLp);

            mTabButtons.add(pill);
            mTabTitles.add(titleTv);
            mTabIcons.add(icon);
            mTabIndicators.add(dummyInd);
        }

        tabScroll.addView(tabRow);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private LinearLayout buildSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(mSidebarColor);
        sidebar.setPadding(dp(14), dp(20), dp(14), dp(16));

        // Brand card header
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(8), dp(2), dp(8), dp(16));

        FrameLayout logoBox = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(59, 130, 246), Color.rgb(124, 58, 237)});
        logoBg.setCornerRadius(dp(12));
        logoBox.setBackground(logoBg);
        TextView logoText = new TextView(this);
        logoText.setText("D+");
        logoText.setTextColor(Color.WHITE);
        logoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        logoText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        logoText.setGravity(Gravity.CENTER);
        logoBox.addView(logoText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        logoLp.setMarginEnd(dp(11));
        brandRow.addView(logoBox, logoLp);

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameLine = new LinearLayout(this);
        nameLine.setOrientation(LinearLayout.HORIZONTAL);
        nameLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("DeX+");
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        appTitle.setTextColor(mTextPrimary);
        nameLine.addView(appTitle);

        String ver = getAppVersionLabel();
        if (!ver.isEmpty()) {
            TextView verTv = new TextView(this);
            verTv.setText(ver);
            verTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            verTv.setTextColor(mTextSecondary);
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vLp.setMarginStart(dp(6));
            nameLine.addView(verTv, vLp);
        }
        brandText.addView(nameLine);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("One UI Desktop Suite");
        appSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        appSubtitle.setTextColor(mTextSecondary);
        brandText.addView(appSubtitle);

        brandRow.addView(brandText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sidebar.addView(brandRow);

        // Grouped navigation list
        ScrollView tabScroll = new ScrollView(this);
        tabScroll.setVerticalScrollBarEnabled(false);
        LinearLayout tabList = new LinearLayout(this);
        tabList.setOrientation(LinearLayout.VERTICAL);

        mTabButtons.clear();
        mTabTitles.clear();
        mTabIcons.clear();
        mTabIndicators.clear();

        String lastGroup = null;
        for (int i = 0; i < NAV_ENTRIES.length; i++) {
            final int index = i;
            NavEntry entry = NAV_ENTRIES[i];

            if (!entry.group.equals(lastGroup)) {
                lastGroup = entry.group;
                TextView groupTv = new TextView(this);
                groupTv.setText(entry.group);
                groupTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                groupTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                groupTv.setTextColor(mTextSecondary);
                groupTv.setLetterSpacing(0.08f);
                LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gLp.topMargin = (i == 0) ? dp(2) : dp(14);
                gLp.bottomMargin = dp(5);
                gLp.leftMargin = dp(10);
                tabList.addView(groupTv, gLp);
            }

            LinearLayout tabItem = new LinearLayout(this);
            tabItem.setOrientation(LinearLayout.HORIZONTAL);
            tabItem.setGravity(Gravity.CENTER_VERTICAL);
            tabItem.setPadding(dp(8), dp(10), dp(10), dp(10));

            View indicator = new View(this);
            GradientDrawable indBg = new GradientDrawable();
            indBg.setCornerRadius(dp(4));
            indBg.setColor(entry.accentColor);
            indicator.setBackground(indBg);
            LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(dp(3.5f), dp(20));
            indLp.setMarginEnd(dp(9));
            tabItem.addView(indicator, indLp);

            NavIconView icon = new NavIconView(this, entry.iconKind);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            iconLp.setMarginEnd(dp(10));
            tabItem.addView(icon, iconLp);

            TextView titleTv = new TextView(this);
            titleTv.setText(entry.title);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            titleTv.setSingleLine(true);
            tabItem.addView(titleTv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            tabItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(3);
            tabList.addView(tabItem, itemLp);

            mTabButtons.add(tabItem);
            mTabTitles.add(titleTv);
            mTabIcons.add(icon);
            mTabIndicators.add(indicator);
        }

        tabScroll.addView(tabList);
        sidebar.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Footer LSPosed badge
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(8), dp(10), dp(8), dp(2));
        footer.addView(createStatusPill(isModuleActive()));
        sidebar.addView(footer);

        return sidebar;
    }

    private TextView createStatusPill(boolean active) {
        TextView badge = new TextView(this);
        badge.setText(active ? "●  LSPosed Active" : "●  LSPosed Inactive");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        int fg = active ? Color.rgb(16, 185, 129) : Color.rgb(245, 158, 11);
        int bg = active
                ? (mIsDark ? Color.argb(38, 16, 185, 129) : Color.argb(26, 16, 185, 129))
                : (mIsDark ? Color.argb(38, 245, 158, 11) : Color.argb(26, 245, 158, 11));
        badge.setTextColor(fg);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(99));
        badge.setBackground(d);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        return badge;
    }

    private void selectTab(int index) {
        if (index < 0 || index >= mTabPages.size()) index = 0;
        mSelectedTab = index;
        NavEntry activeEntry = NAV_ENTRIES[index];

        if (mPageHeaderTitle != null) {
            mPageHeaderTitle.setText(activeEntry.title);
        }
        if (mPageHeaderSubtitle != null) {
            mPageHeaderSubtitle.setText(activeEntry.subtitle);
        }
        if (mPageScopeBadge != null) {
            mPageScopeBadge.setText(activeEntry.scopeBadge);
            mPageScopeBadge.setTextColor(activeEntry.accentColor);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(99));
            badgeBg.setColor(Color.argb(mIsDark ? 42 : 26,
                    Color.red(activeEntry.accentColor),
                    Color.green(activeEntry.accentColor),
                    Color.blue(activeEntry.accentColor)));
            mPageScopeBadge.setBackground(badgeBg);
        }

        for (int i = 0; i < mTabPages.size(); i++) {
            boolean selected = (i == index);
            NavEntry item = NAV_ENTRIES[i];
            mTabPages.get(i).setVisibility(selected ? View.VISIBLE : View.GONE);

            LinearLayout btn = mTabButtons.get(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(11));
            if (selected) {
                bg.setColor(Color.argb(mIsDark ? 44 : 26,
                        Color.red(item.accentColor),
                        Color.green(item.accentColor),
                        Color.blue(item.accentColor)));
            } else {
                bg.setColor(Color.TRANSPARENT);
            }
            btn.setBackground(bg);

            TextView titleTv = mTabTitles.get(i);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", selected ? Typeface.BOLD : Typeface.NORMAL));
            titleTv.setTextColor(selected ? item.accentColor : mTextPrimary);

            if (i < mTabIcons.size()) {
                mTabIcons.get(i).setIconColor(selected ? item.accentColor : mTextSecondary);
            }

            if (i < mTabIndicators.size() && !mIsCompact) {
                mTabIndicators.get(i).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            }
        }

        if (mContentScroll != null) {
            mContentScroll.scrollTo(0, 0);
        }
    }

    private View buildOverviewPage() {
        LinearLayout page = createPageContainer();

        // Hero status & master toggle card
        LinearLayout heroCard = createCard();
        int pad = dp(18);

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        heroTop.setPadding(pad, pad, pad, dp(14));

        LinearLayout heroInfo = new LinearLayout(this);
        heroInfo.setOrientation(LinearLayout.VERTICAL);

        TextView heroTitle = new TextView(this);
        heroTitle.setText("DeX+ Engine");
        heroTitle.setTextColor(mTextPrimary);
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heroInfo.addView(heroTitle);

        int extCount = countExternalDisplays();
        String displaySummary = extCount > 0
                ? ("Built-in Display + " + extCount + " External Display" + (extCount > 1 ? "s" : "") + " detected")
                : "Built-in Display active · No external monitor connected";
        TextView heroSub = new TextView(this);
        heroSub.setText(displaySummary);
        heroSub.setTextColor(extCount > 0 ? COLOR_EXTERNAL : mTextSecondary);
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsLp.topMargin = dp(3);
        heroInfo.addView(heroSub, hsLp);

        heroTop.addView(heroInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch masterSw = new Switch(this);
        masterSw.setChecked(mPrefs.getBoolean(ModConfig.KEY_MODULE_ENABLED, true));
        masterSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mPrefs.edit().putBoolean(ModConfig.KEY_MODULE_ENABLED, isChecked).commit();
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
            }
        });
        heroTop.addView(masterSw);
        heroTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                masterSw.setChecked(!masterSw.isChecked());
            }
        });
        heroCard.addView(heroTop);

        addDivider(heroCard);

        // Quick-jump scope summary chips inside Hero Card
        LinearLayout scopeRow = new LinearLayout(this);
        scopeRow.setOrientation(mIsCompact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        scopeRow.setPadding(pad, dp(12), pad, dp(14));

        addQuickScopeTile(scopeRow, "Shared", "Window controls", COLOR_SHARED, 1, !mIsCompact);
        addQuickScopeTile(scopeRow, "Internal DeX", "Built-in taskbar & desktop", COLOR_INTERNAL, 2, !mIsCompact);
        addQuickScopeTile(scopeRow, "External DeX", "Monitor taskbar & desktop", COLOR_EXTERNAL, 4, false);

        heroCard.addView(scopeRow);
        page.addView(heroCard);

        addSectionLabel(page, "SYSTEM & INPUT", COLOR_OVERVIEW);
        LinearLayout sysCard = createCard();
        addSwitchRow(sysCard,
                "Keep Desktop Mode Always Active",
                "Keep the desktop taskbar and window features active on the built-in display even when DeX mode is turned off.",
                ModConfig.KEY_FORCE_INTERNAL_DEX, false, true);
        addSwitchRow(sysCard,
                "Hide Physical Keyboard Toolbar",
                "Hides the floating on-screen keyboard toolbar when a hardware keyboard is connected.",
                ModConfig.KEY_HIDE_PHYSICAL_KB_TOOLBAR, true, true);
        addSwitchRow(sysCard,
                "Advanced Power Menu",
                "Adds Soft Reboot, Recovery, Download Mode, Bootloader, Safe Mode, and Restart System UI to the power menu.",
                ModConfig.KEY_ADVANCED_POWER_MENU, false, false);
        page.addView(sysCard);

        addSectionLabel(page, "QUICK ACTIONS", COLOR_OVERVIEW);
        LinearLayout actionsCard = createCard();
        actionsCard.setPadding(pad, pad, pad, pad);

        TextView actionsDesc = new TextView(this);
        actionsDesc.setText("Most changes apply immediately. Use Restart Desktop or Restart System UI when a layout or title bar needs a clean reload.");
        actionsDesc.setTextColor(mTextSecondary);
        actionsDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.3f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.bottomMargin = dp(14);
        actionsCard.addView(actionsDesc, descLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(mIsCompact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        Button restartHomeBtn = createStyledButton("Restart Desktop", mSurfaceElevated, mTextPrimary);
        restartHomeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                requestHostRestart("launcher", "com.sec.android.app.launcher");
                Toast.makeText(SettingsActivity.this, "Restarting One UI Home...", Toast.LENGTH_SHORT).show();
            }
        });
        row1.addView(restartHomeBtn, mIsCompact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row1.addView(new View(this), new LinearLayout.LayoutParams(mIsCompact ? dp(1) : dp(10), mIsCompact ? dp(8) : dp(1)));

        Button restartSysUiBtn = createStyledButton("Restart System UI", mSurfaceElevated, mTextPrimary);
        restartSysUiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                requestHostRestart("systemui", "com.android.systemui");
                Toast.makeText(SettingsActivity.this, "Restarting System UI...", Toast.LENGTH_SHORT).show();
            }
        });
        row1.addView(restartSysUiBtn, mIsCompact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row1.addView(new View(this), new LinearLayout.LayoutParams(mIsCompact ? dp(1) : dp(10), mIsCompact ? dp(8) : dp(1)));

        Button resetBtn = createStyledButton("Restore Defaults",
                mIsDark ? Color.rgb(68, 34, 44) : Color.rgb(254, 226, 226),
                mIsDark ? Color.rgb(252, 165, 165) : Color.rgb(185, 28, 28));
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Restore Default Settings")
                        .setMessage("Reset all DeX+ preferences (Shared, Internal, and External) to their default values?")
                        .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ModConfig.resetToDefaults(SettingsActivity.this, mPrefs);
                                Toast.makeText(SettingsActivity.this,
                                        "Default settings restored", Toast.LENGTH_SHORT).show();
                                recreate();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        row1.addView(resetBtn, mIsCompact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        actionsCard.addView(row1);
        page.addView(actionsCard);

        return page;
    }

    private void addQuickScopeTile(
            LinearLayout parent,
            String title,
            String subtitle,
            int color,
            final int targetTab,
            boolean addEndMargin) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.argb(mIsDark ? 30 : 20, Color.red(color), Color.green(color), Color.blue(color)));
        bg.setStroke(dp(1), Color.argb(mIsDark ? 60 : 40, Color.red(color), Color.green(color), Color.blue(color)));
        tile.setBackground(bg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tile.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(mTextSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(2);
        tile.addView(s, sLp);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(targetTab);
            }
        });

        LinearLayout.LayoutParams lp = mIsCompact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (mIsCompact) {
            lp.bottomMargin = dp(8);
        } else if (addEndMargin) {
            lp.setMarginEnd(dp(10));
        }
        parent.addView(tile, lp);
    }

    private View buildWindowControlsPage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "STATUS BAR & WINDOW BEHAVIOR", COLOR_SHARED);
        LinearLayout barCard = createCard();
        addSwitchRow(barCard,
                "Hide Top Status Bar",
                "Collapses the top Android status bar on the built-in display in desktop mode so windows can use full vertical space.",
                ModConfig.KEY_HIDE_STATUS_BAR, true, true);
        addSwitchRow(barCard,
                "Drag Window to Top Edge to Maximize",
                "Dragging a window to the top edge maximizes it instead of switching to full-screen tablet mode.",
                ModConfig.KEY_TOP_EDGE_MAXIMIZE, true, true);
        addSwitchRow(barCard,
                "Fullscreen Inside Window",
                "Videos fill the current window without resizing it or leaving DeX. Turn off to let the system handle fullscreen.",
                ModConfig.KEY_FULLSCREEN_INSIDE, true, true);
        addSwitchRow(barCard,
                "Allow All Apps in Resizable Windows",
                "Lets all installed apps run in freeform multi-window mode and be freely resized on any display.",
                ModConfig.KEY_FORCE_RESIZABLE_WINDOWS, true, false);
        page.addView(barCard);

        addSectionLabel(page, "SHARED TITLE BAR BUTTONS", COLOR_SHARED);
        LinearLayout titleBarCard = createCard();
        addSwitchRow(titleBarCard,
                "Always-on-Top Pin Button",
                "Adds a pin button to window title bars across Internal and External DeX to keep selected windows above others.",
                ModConfig.KEY_PIN_BUTTON_ENABLED, true, true);
        addSwitchRow(titleBarCard,
                "Hide Fullscreen Button on Windowed Apps",
                "Hides the separate fullscreen button on normal and maximized windows to keep title bars compact.",
                ModConfig.KEY_HIDE_FULLSCREEN_BTN, true, true);
        addSwitchRow(titleBarCard,
                "Long-Press Maximize for Fullscreen",
                "Hold Maximize/Restore to toggle immersive fullscreen. Updates hover tooltip to \"Maximize/Fullscreen\".",
                ModConfig.KEY_HOLD_MAXIMIZE_FULLSCREEN, true, true);
        addSwitchRow(titleBarCard,
                "Hide App Icon Dropdown Arrow",
                "Hides the small arrow next to the app icon while keeping the app menu clickable.",
                ModConfig.KEY_HIDE_APP_CHEVRON, true, true);
        addSeekBarRow(titleBarCard,
                "Window Corner Radius",
                "Rounds floating window corners and outlines across displays. 0 is square.",
                ModConfig.KEY_WINDOW_CORNER_RADIUS, 0, 48, systemCornerDp(), " dp", false, true, COLOR_SHARED);
        page.addView(titleBarCard);

        addSectionLabel(page, "SHARED APP ICON WINDOW MENU", COLOR_SHARED);
        LinearLayout menuCard = createCard();
        addSwitchRow(menuCard,
                "Enable Enhanced Window Menu",
                "Adds extra tools when clicking the app icon in a window's title bar.",
                ModConfig.KEY_HEADER_MENU_CUSTOM_ENABLED, true, true);
        addSwitchRow(menuCard,
                "Window Opacity Slider",
                "Adds a live transparency slider (20%–100%) to the app icon menu.",
                ModConfig.KEY_ADD_TRANSPARENCY_SLIDER, true, true);
        addSwitchRow(menuCard,
                "Center & Keep Awake Buttons",
                "Adds quick buttons to center the window on screen and keep the screen awake while visible.",
                ModConfig.KEY_ADD_EXTRA_WINDOW_TOOLS, true, true);
        addSwitchRow(menuCard,
                "Show App Info Shortcut",
                "Replaces Remove from workspace with a shortcut that opens the app's system App Info page.",
                ModConfig.KEY_ADD_APP_INFO_BTN, true, true);
        addSeekBarRow(menuCard,
                "Default Window Opacity",
                "Starting opacity level for newly opened windows (Default: 100%).",
                ModConfig.KEY_DEFAULT_WINDOW_OPACITY, 30, 100, 100, "%", false, true, COLOR_SHARED);
        page.addView(menuCard);

        return page;
    }

    private View buildInternalTaskbarPage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "BUILT-IN TASKBAR & STATUS AREA", COLOR_INTERNAL);
        LinearLayout tbCard = createCard();
        addSwitchRow(tbCard,
                "Enable Desktop Taskbar",
                "Shows the desktop taskbar with running apps, notification badge, quick settings, calendar, and audio controls on the built-in display.",
                ModConfig.KEY_DEX_TASKBAR_ENABLED, true, true);
        addSwitchRow(tbCard,
                "Use Standard Notification & Quick Settings Shade",
                "Clicking notification or quick settings buttons on the built-in taskbar opens the full pull-down system shade instead of compact popups.",
                ModConfig.KEY_STATUS_PANEL_MODE, false, true, true);
        addSwitchRow(tbCard,
                "Show Search Button on Taskbar",
                "Displays the Finder search button in the built-in taskbar status area.",
                ModConfig.KEY_SHOW_DEX_FINDER_BTN, true, false);
        page.addView(tbCard);

        addSectionLabel(page, "BUILT-IN TASKBAR SIZING", COLOR_INTERNAL);
        LinearLayout scaleCard = createCard();
        addSeekBarRow(scaleCard,
                "Taskbar Icon & Text Scale",
                "Adjusts app icons and status indicators on the built-in display taskbar (Default: 100%).",
                ModConfig.KEY_TASKBAR_ICON_SCALE, 70, 140, 100, "%", true, true, COLOR_INTERNAL);
        addSeekBarRow(scaleCard,
                "Taskbar Height",
                "Adjusts the overall height of the built-in display taskbar (Default: 100%).",
                ModConfig.KEY_TASKBAR_HEIGHT_SCALE, 75, 135, 100, "%", false, true, COLOR_INTERNAL);
        page.addView(scaleCard);

        return page;
    }

    private View buildInternalDpiPage() {
        LinearLayout page = createPageContainer();

        addInfoBanner(page,
                "Adjusts the built-in display density while Internal DeX is active. Apps, windows, and system UI scale cleanly from the display's original DPI.",
                COLOR_INTERNAL);

        addSectionLabel(page, "INTERNAL DISPLAY DPI", COLOR_INTERNAL);
        LinearLayout dpiCard = createCard();
        addSeekBarRow(dpiCard,
                "Internal DeX DPI Offset",
                "Adds (+) or subtracts (-) DPI from the built-in display's original density while Internal DeX is active. Affects apps, windows, and UI (0 = unchanged).",
                ModConfig.KEY_INTERNAL_DEX_DPI_OFFSET, -120, 120, 0, " dpi", false, true, COLOR_INTERNAL);
        page.addView(dpiCard);

        return page;
    }

    private View buildInternalDesktopPage() {
        return buildDesktopInstancePage(false);
    }

    private View buildExternalDesktopPage() {
        return buildDesktopInstancePage(true);
    }

    private View buildDesktopInstancePage(final boolean external) {
        LinearLayout page = createPageContainer();
        final int accent = external ? COLOR_EXTERNAL : COLOR_INTERNAL;
        final String scope = external ? "external" : "internal";
        final String label = external ? "External Desktop" : "Internal Desktop";
        final String otherLabel = external ? "Internal Desktop" : "External Desktop";

        final String keyFreeform = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_FREEFORM_LAUNCH : ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH;
        final String keyFreePlacement = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_FREE_PLACEMENT : ModConfig.KEY_DESKTOP_FREE_PLACEMENT;
        final String keyDoubleClick = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_DOUBLE_CLICK : ModConfig.KEY_DESKTOP_DOUBLE_CLICK;
        final String keyFileBased = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_FILE_BASED : ModConfig.KEY_DESKTOP_FILE_BASED;
        final String keyFolderPath = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_FOLDER_PATH : ModConfig.KEY_DESKTOP_FOLDER_PATH;
        final String keyIconScale = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_ICON_SCALE : ModConfig.KEY_DESKTOP_ICON_SCALE;

        if (external) {
            addInfoBanner(page,
                    "External Desktop runs as an independent instance with its own settings and shortcuts. If both desktops use File-Based mode with the same folder, their horizontal grid automatically syncs to the wider column count.",
                    COLOR_EXTERNAL);

            addSectionLabel(page, "EXTERNAL MONITOR DESKTOP", accent);
            LinearLayout enableCard = createCard();
            addSwitchRow(enableCard,
                    "Enable Custom Desktop on External Displays",
                    "Replaces the external monitor's stock One UI desktop with an independent DeX+ Windows-style / file-based desktop workspace.",
                    ModConfig.KEY_EXTERNAL_DESKTOP_ENABLED, false, false);
            page.addView(enableCard);
        }

        addSectionLabel(page, "PROFILE SYNC", accent);
        LinearLayout copyCard = createCard();
        LinearLayout copyRow = new LinearLayout(this);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        copyRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(18);
        copyRow.setPadding(pad, dp(14), pad, dp(14));

        LinearLayout copyTextCol = new LinearLayout(this);
        copyTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView copyTitle = new TextView(this);
        copyTitle.setText("Copy Settings from " + otherLabel);
        copyTitle.setTextColor(mTextPrimary);
        copyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        copyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copyTextCol.addView(copyTitle);

        TextView copySum = new TextView(this);
        copySum.setText("Copies workspace toggles, folder path, \"New\" menu file types, and icon size from " + otherLabel + " into " + label + ".");
        copySum.setTextColor(mTextSecondary);
        copySum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams copySumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copySumLp.topMargin = dp(3);
        copyTextCol.addView(copySum, copySumLp);
        copyRow.addView(copyTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copyBtn = createStyledButton("Copy", accent, Color.WHITE);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Copy from " + otherLabel)
                        .setMessage("Overwrite all " + label + " settings with " + otherLabel + "'s current settings?")
                        .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                copyDesktopSettings(!external);
                                Toast.makeText(SettingsActivity.this,
                                        "Copied settings from " + otherLabel, Toast.LENGTH_SHORT).show();
                                recreate();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        LinearLayout.LayoutParams copyBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyBtnLp.setMarginStart(dp(12));
        copyRow.addView(copyBtn, copyBtnLp);
        copyCard.addView(copyRow);
        page.addView(copyCard);

        addSectionLabel(page, external ? "EXTERNAL DESKTOP WORKSPACE" : "DESKTOP WORKSPACE", accent);
        LinearLayout wsCard = createCard();
        addSwitchRow(wsCard,
                "Open Shortcuts in Resizable Windows",
                "Launches apps from this desktop directly in freeform windows.",
                keyFreeform, true, true);
        addSwitchRow(wsCard,
                "Free Grid Placement",
                "Place and keep shortcuts anywhere on this desktop's grid instead of packing them top-left.",
                keyFreePlacement, true, true);
        addSwitchRow(wsCard,
                "Double-Click to Open Shortcuts",
                "First tap or click selects the shortcut; double-click opens the app.",
                keyDoubleClick, true, true);
        addSwitchRow(wsCard,
                "File-Based Desktop",
                "Uses a real folder on internal storage where each shortcut is a .dexsc file and folders, videos, images, or files appear directly on the desktop.",
                keyFileBased, false, true);
        addFolderPathRow(wsCard,
                "Desktop Folder Path",
                keyFolderPath,
                ModConfig.getDefaultDesktopFolderPath(),
                accent);
        page.addView(wsCard);

        addSectionLabel(page, "\"NEW\" MENU FILE TYPES", accent);
        LinearLayout newTypesCard = createCard();
        addNewFileTypesSection(newTypesCard, external, accent);
        page.addView(newTypesCard);

        addSectionLabel(page, external ? "EXTERNAL ICON SIZE" : "BUILT-IN ICON SIZE", accent);
        LinearLayout gridCard = createCard();
        SeekBarRowHandle iconScaleHandle = addSeekBarRow(gridCard,
                external ? "External Desktop Icon Size" : "Desktop Icon Size",
                external
                        ? "Scales external desktop icons and labels and computes grid columns/rows automatically (Default: 100%)."
                        : "Scales built-in desktop icons and labels and computes grid columns/rows automatically (Default: 100%).",
                keyIconScale, 70, 140, 100, "%", false, true, accent);
        if (external) {
            mExternalIconScaleRow = iconScaleHandle;
        } else {
            mInternalIconScaleRow = iconScaleHandle;
        }
        page.addView(gridCard);

        addSectionLabel(page, "SHORTCUT MANAGEMENT", accent);
        LinearLayout resetCard = createCard();

        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.setGravity(Gravity.CENTER_VERTICAL);
        importRow.setPadding(pad, pad, pad, pad);

        LinearLayout importCol = new LinearLayout(this);
        importCol.setOrientation(LinearLayout.VERTICAL);
        TextView importTitle = new TextView(this);
        importTitle.setText("Create Classic Icons in Desktop Folder");
        importTitle.setTextColor(mTextPrimary);
        importTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        importTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        importCol.addView(importTitle);

        TextView importSum = new TextView(this);
        importSum.setText("Creates .dexsc shortcut files in this desktop's folder from its classic desktop icons.");
        importSum.setTextColor(mTextSecondary);
        importSum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams importSumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importSumLp.topMargin = dp(3);
        importCol.addView(importSum, importSumLp);

        importRow.addView(importCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button importBtn = createStyledButton("Create Files", mSurfaceElevated, mTextPrimary);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cmd = new Intent(ModConfig.ACTION_DESKTOP_COMMAND);
                cmd.putExtra("command", "import_legacy_shortcuts");
                cmd.putExtra("scope", scope);
                sendBroadcast(cmd);
            }
        });
        LinearLayout.LayoutParams importBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importBtnLp.setMarginStart(dp(12));
        importRow.addView(importBtn, importBtnLp);
        resetCard.addView(importRow);

        addDivider(resetCard);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("Reset " + label + " Shortcuts");
        t.setTextColor(mTextPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText("Resets this desktop's shortcuts back to only the System Settings icon.");
        s.setTextColor(mTextSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(3);
        textCol.addView(s, sLp);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button resetShortcutsBtn = createStyledButton("Reset Shortcuts", mSurfaceElevated, mTextPrimary);
        resetShortcutsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Reset " + label + " Shortcuts")
                        .setMessage("Reset " + label + " shortcuts back to only the System Settings icon?")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent cmd = new Intent(ModConfig.ACTION_DESKTOP_COMMAND);
                                cmd.putExtra("command", "reset_shortcuts");
                                cmd.putExtra("scope", scope);
                                sendBroadcast(cmd);
                                Toast.makeText(SettingsActivity.this,
                                        label + " shortcuts reset", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        row.addView(resetShortcutsBtn);
        resetCard.addView(row);
        page.addView(resetCard);

        return page;
    }

    private void copyDesktopSettings(boolean fromExternalToInternal) {
        String srcFreeform = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_FREEFORM_LAUNCH : ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH;
        String dstFreeform = fromExternalToInternal ? ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH : ModConfig.KEY_EXTERNAL_DESKTOP_FREEFORM_LAUNCH;

        String srcFreePlace = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_FREE_PLACEMENT : ModConfig.KEY_DESKTOP_FREE_PLACEMENT;
        String dstFreePlace = fromExternalToInternal ? ModConfig.KEY_DESKTOP_FREE_PLACEMENT : ModConfig.KEY_EXTERNAL_DESKTOP_FREE_PLACEMENT;

        String srcDoubleClick = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_DOUBLE_CLICK : ModConfig.KEY_DESKTOP_DOUBLE_CLICK;
        String dstDoubleClick = fromExternalToInternal ? ModConfig.KEY_DESKTOP_DOUBLE_CLICK : ModConfig.KEY_EXTERNAL_DESKTOP_DOUBLE_CLICK;

        String srcFileBased = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_FILE_BASED : ModConfig.KEY_DESKTOP_FILE_BASED;
        String dstFileBased = fromExternalToInternal ? ModConfig.KEY_DESKTOP_FILE_BASED : ModConfig.KEY_EXTERNAL_DESKTOP_FILE_BASED;

        String srcFolder = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_FOLDER_PATH : ModConfig.KEY_DESKTOP_FOLDER_PATH;
        String dstFolder = fromExternalToInternal ? ModConfig.KEY_DESKTOP_FOLDER_PATH : ModConfig.KEY_EXTERNAL_DESKTOP_FOLDER_PATH;

        String srcNewTypes = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_NEW_FILE_TYPES : ModConfig.KEY_DESKTOP_NEW_FILE_TYPES;
        String dstNewTypes = fromExternalToInternal ? ModConfig.KEY_DESKTOP_NEW_FILE_TYPES : ModConfig.KEY_EXTERNAL_DESKTOP_NEW_FILE_TYPES;

        String srcIconScale = fromExternalToInternal ? ModConfig.KEY_EXTERNAL_DESKTOP_ICON_SCALE : ModConfig.KEY_DESKTOP_ICON_SCALE;
        String dstIconScale = fromExternalToInternal ? ModConfig.KEY_DESKTOP_ICON_SCALE : ModConfig.KEY_EXTERNAL_DESKTOP_ICON_SCALE;

        String defaultFolder = ModConfig.getDefaultDesktopFolderPath();
        SharedPreferences.Editor ed = mPrefs.edit();
        ed.putBoolean(dstFreeform, mPrefs.getBoolean(srcFreeform, true));
        ed.putBoolean(dstFreePlace, mPrefs.getBoolean(srcFreePlace, true));
        ed.putBoolean(dstDoubleClick, mPrefs.getBoolean(srcDoubleClick, true));
        ed.putBoolean(dstFileBased, mPrefs.getBoolean(srcFileBased, false));
        ed.putString(dstFolder, mPrefs.getString(srcFolder, defaultFolder));
        ed.putString(dstNewTypes, mPrefs.getString(srcNewTypes, ModConfig.DEFAULT_DESKTOP_NEW_FILE_TYPES));
        if (mPrefs.contains(srcIconScale)) {
            ed.putInt(dstIconScale, mPrefs.getInt(srcIconScale, 100));
        } else {
            ed.remove(dstIconScale);
        }
        ed.commit();
        ModConfig.broadcastChanges(this, mPrefs);
    }

    private void refreshSharedDesktopIconSyncState() {
        if (mInternalIconScaleRow == null && mExternalIconScaleRow == null) return;
        boolean extEnabled = mPrefs.getBoolean(ModConfig.KEY_EXTERNAL_DESKTOP_ENABLED, false);
        boolean intFileBased = mPrefs.getBoolean(ModConfig.KEY_DESKTOP_FILE_BASED, false);
        boolean extFileBased = mPrefs.getBoolean(ModConfig.KEY_EXTERNAL_DESKTOP_FILE_BASED, false);
        String defPath = ModConfig.getDefaultDesktopFolderPath();
        String intPath = ModConfig.normalizeDesktopFolderPath(mPrefs.getString(ModConfig.KEY_DESKTOP_FOLDER_PATH, defPath));
        String extPath = ModConfig.normalizeDesktopFolderPath(mPrefs.getString(ModConfig.KEY_EXTERNAL_DESKTOP_FOLDER_PATH, defPath));
        boolean sharedFolder = extEnabled && intFileBased && extFileBased && intPath.equals(extPath);

        if (!sharedFolder) {
            if (mInternalIconScaleRow != null) mInternalIconScaleRow.setSyncedDisabled(false, null);
            if (mExternalIconScaleRow != null) mExternalIconScaleRow.setSyncedDisabled(false, null);
            return;
        }

        DisplayMetrics builtInDm = getResources().getDisplayMetrics();
        int intW = builtInDm.widthPixels;
        float intDensity = builtInDm.density;
        int extW = 1920;
        float extDensity = 1.0f;
        try {
            DisplayManager dmgr = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (dmgr != null) {
                Display defDisp = dmgr.getDisplay(Display.DEFAULT_DISPLAY);
                if (defDisp != null) {
                    DisplayMetrics dm0 = new DisplayMetrics();
                    defDisp.getRealMetrics(dm0);
                    if (dm0.widthPixels > 0) {
                        intW = dm0.widthPixels;
                        intDensity = dm0.density;
                    }
                }
                Display[] displays = dmgr.getDisplays();
                if (displays != null) {
                    for (Display d : displays) {
                        if (d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                            DisplayMetrics dme = new DisplayMetrics();
                            d.getRealMetrics(dme);
                            if (dme.widthPixels > 0) {
                                extW = dme.widthPixels;
                                extDensity = dme.density;
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        int intScale = Math.max(65, Math.min(150, mPrefs.getInt(ModConfig.KEY_DESKTOP_ICON_SCALE, 100)));
        int extScale = Math.max(65, Math.min(150, mPrefs.getInt(ModConfig.KEY_EXTERNAL_DESKTOP_ICON_SCALE, 100)));
        int intCols = ModConfig.computeDesktopNaturalCols(intW, intDensity, intScale);
        int extCols = ModConfig.computeDesktopNaturalCols(extW, extDensity, extScale);

        if (intCols < extCols) {
            if (mInternalIconScaleRow != null) {
                mInternalIconScaleRow.setSyncedDisabled(true,
                        "Synced to External Desktop's horizontal grid (" + extCols
                                + " cols) while both desktops share this folder. Your saved size ("
                                + intScale + "%) will apply automatically if you choose a different folder.");
            }
            if (mExternalIconScaleRow != null) {
                mExternalIconScaleRow.setSyncedDisabled(false, null);
            }
        } else if (extCols < intCols) {
            if (mExternalIconScaleRow != null) {
                mExternalIconScaleRow.setSyncedDisabled(true,
                        "Synced to Internal Desktop's horizontal grid (" + intCols
                                + " cols) while both desktops share this folder. Your saved size ("
                                + extScale + "%) will apply automatically if you choose a different folder.");
            }
            if (mInternalIconScaleRow != null) {
                mInternalIconScaleRow.setSyncedDisabled(false, null);
            }
        } else {
            if (mInternalIconScaleRow != null) mInternalIconScaleRow.setSyncedDisabled(false, null);
            if (mExternalIconScaleRow != null) mExternalIconScaleRow.setSyncedDisabled(false, null);
        }
    }

    private View buildExternalTaskbarPage() {
        LinearLayout page = createPageContainer();

        addInfoBanner(page,
                "External DeX settings apply strictly to connected monitors and simulated secondary displays, leaving your built-in display untouched.",
                COLOR_EXTERNAL);

        addSectionLabel(page, "EXTERNAL MONITOR TASKBAR", COLOR_EXTERNAL);
        LinearLayout extTbCard = createCard();
        addSwitchRow(extTbCard,
                "Show Search Button on External Taskbar",
                "Shows or hides the Finder search button on connected external monitors independently of the built-in taskbar.",
                ModConfig.KEY_EXTERNAL_SHOW_FINDER_BTN, true, false);
        page.addView(extTbCard);

        addSectionLabel(page, "EXTERNAL TASKBAR SIZING", COLOR_EXTERNAL);
        LinearLayout extScaleCard = createCard();
        addSeekBarRow(extScaleCard,
                "External Taskbar Icon & Text Scale",
                "Independent icon and indicator scale for external monitors (Default: 100%).",
                ModConfig.KEY_EXTERNAL_TASKBAR_ICON_SCALE, 70, 140, 100, "%", true, true, COLOR_EXTERNAL);
        addSeekBarRow(extScaleCard,
                "External Taskbar Height",
                "Independent taskbar height multiplier for external monitors (Default: 100%).",
                ModConfig.KEY_EXTERNAL_TASKBAR_HEIGHT_SCALE, 75, 135, 100, "%", false, true, COLOR_EXTERNAL);
        page.addView(extScaleCard);

        return page;
    }

    private View buildExternalDpiPage() {
        LinearLayout page = createPageContainer();

        addInfoBanner(page,
                "Adjusts density strictly on connected external monitors and simulated secondary displays without affecting the built-in display. One UI Home automatically refreshes the external workspace after a DPI change.",
                COLOR_EXTERNAL);

        addSectionLabel(page, "EXTERNAL DISPLAY DPI", COLOR_EXTERNAL);
        LinearLayout extDpiCard = createCard();
        addSeekBarRow(extDpiCard,
                "External DeX DPI Offset",
                "Adds (+) or subtracts (-) DPI from the external display's original density. Affects apps, windows, and taskbar independently of the built-in display (0 = unchanged).",
                ModConfig.KEY_EXTERNAL_DEX_DPI_OFFSET, -160, 160, 0, " dpi", false, true, COLOR_EXTERNAL);
        page.addView(extDpiCard);

        return page;
    }

    private void addInfoBanner(LinearLayout parent, String text, int accent) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.argb(mIsDark ? 28 : 20, Color.red(accent), Color.green(accent), Color.blue(accent)));
        bg.setStroke(dp(1), Color.argb(mIsDark ? 65 : 45, Color.red(accent), Color.green(accent), Color.blue(accent)));
        banner.setBackground(bg);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMarginEnd(dp(10));
        banner.addView(dot, dotLp);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(mTextPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        banner.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(banner, lp);
    }

    private LinearLayout createPageContainer() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private void requestHostRestart(String target, String packageName) {
        Intent intent = new Intent(ModConfig.ACTION_RESTART_HOST);
        intent.setPackage(packageName);
        intent.putExtra(ModConfig.EXTRA_RESTART_TARGET, target);
        sendBroadcast(intent);
    }

    private Button createStyledButton(String text, int bgColor, int fgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(fgColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(11));
        btn.setBackground(bg);
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));
        btn.setStateListAnimator(null);
        return btn;
    }

    private void addSectionLabel(LinearLayout parent, String title, int color) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = parent.getChildCount() == 0 ? dp(2) : dp(18);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(6);
        parent.addView(tv, lp);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mCardColor);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), mCardStrokeColor);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    private void addSwitchRow(
            LinearLayout card,
            String title,
            String summary,
            final String key,
            boolean defaultValue,
            boolean showDivider) {
        addSwitchRow(card, title, summary, key, defaultValue, showDivider, false);
    }

    private void addSwitchRow(
            LinearLayout card,
            String title,
            String summary,
            final String key,
            boolean defaultValue,
            boolean showDivider,
            final boolean storeAsInt01) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(18);
        int padV = dp(14);
        row.setPadding(padH, padV, padH, padV);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(mTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(titleTv);

        TextView sumTv = new TextView(this);
        sumTv.setText(summary);
        sumTv.setTextColor(mTextSecondary);
        sumTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(3);
        textCol.addView(sumTv, sumLp);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginEnd(dp(14));
        row.addView(textCol, colLp);

        final Switch sw = new Switch(this);
        boolean currentChecked;
        if (storeAsInt01) {
            currentChecked = mPrefs.getInt(key, defaultValue ? 1 : 0) == 1;
        } else {
            currentChecked = mPrefs.getBoolean(key, defaultValue);
        }
        sw.setChecked(currentChecked);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (storeAsInt01) {
                    mPrefs.edit().putInt(key, isChecked ? 1 : 0).commit();
                } else {
                    mPrefs.edit().putBoolean(key, isChecked).commit();
                }
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                if (ModConfig.KEY_EXTERNAL_DESKTOP_ENABLED.equals(key)
                        || ModConfig.KEY_DESKTOP_FILE_BASED.equals(key)
                        || ModConfig.KEY_EXTERNAL_DESKTOP_FILE_BASED.equals(key)) {
                    refreshSharedDesktopIconSyncState();
                }
            }
        });
        row.addView(sw);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
            }
        });

        card.addView(row);
        if (showDivider) {
            addDivider(card);
        }
    }

    private SeekBarRowHandle addSeekBarRow(
            LinearLayout card,
            String title,
            String summary,
            final String key,
            final int minVal,
            final int maxVal,
            final int defaultVal,
            final String unitSuffix,
            boolean showDivider,
            final boolean allowReset,
            final int accentColor) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(18);
        int padV = dp(14);
        container.setPadding(padH, padV, padH, padV);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(mTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final int clampedDefault = Math.max(minVal, Math.min(maxVal, defaultVal));
        int curVal = mPrefs.getInt(key, clampedDefault);
        curVal = Math.max(minVal, Math.min(maxVal, curVal));
        final boolean usingSystemDefault = allowReset && !mPrefs.contains(key);

        final boolean signed = minVal < 0;
        final TextView valTv = new TextView(this);
        valTv.setText(usingSystemDefault
                ? ("Default (" + formatSeekBarValue(clampedDefault, signed, unitSuffix) + ")")
                : formatSeekBarValue(curVal, signed, unitSuffix));
        valTv.setTextColor(accentColor);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        valTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valTv.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable valBg = new GradientDrawable();
        valBg.setColor(Color.argb(mIsDark ? 36 : 22,
                Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
        valBg.setCornerRadius(dp(99));
        valTv.setBackground(valBg);
        headerRow.addView(valTv);

        final SeekBar sb = new SeekBar(this);
        sb.setMax(maxVal - minVal);
        sb.setProgress(curVal - minVal);

        TextView resetChipRef = null;
        if (allowReset) {
            TextView resetChip = new TextView(this);
            resetChipRef = resetChip;
            resetChip.setText("Reset");
            resetChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            resetChip.setTextColor(mTextSecondary);
            resetChip.setPadding(dp(9), dp(3), dp(9), dp(3));
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setColor(mIsDark ? Color.argb(34, 255, 255, 255) : Color.argb(18, 0, 0, 0));
            chipBg.setCornerRadius(dp(99));
            resetChip.setBackground(chipBg);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.setMarginStart(dp(8));
            resetChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mPrefs.edit().remove(key).commit();
                    sb.setProgress(clampedDefault - minVal);
                    valTv.setText("Default (" + formatSeekBarValue(clampedDefault, signed, unitSuffix) + ")");
                    ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                    if (ModConfig.KEY_DESKTOP_ICON_SCALE.equals(key)
                            || ModConfig.KEY_EXTERNAL_DESKTOP_ICON_SCALE.equals(key)) {
                        refreshSharedDesktopIconSyncState();
                    }
                }
            });
            headerRow.addView(resetChip, chipLp);
        }

        container.addView(headerRow);

        TextView sumTv = new TextView(this);
        sumTv.setText(summary);
        sumTv.setTextColor(mTextSecondary);
        sumTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(4);
        sumLp.bottomMargin = dp(8);
        container.addView(sumTv, sumLp);

        ColorStateList accentTint = ColorStateList.valueOf(accentColor);
        sb.setThumbTintList(accentTint);
        sb.setProgressTintList(accentTint);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser && allowReset && !mPrefs.contains(key)) return;
                int v = minVal + progress;
                valTv.setText(formatSeekBarValue(v, signed, unitSuffix));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int v = minVal + seekBar.getProgress();
                mPrefs.edit().putInt(key, v).commit();
                valTv.setText(formatSeekBarValue(v, signed, unitSuffix));
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                if (ModConfig.KEY_DESKTOP_ICON_SCALE.equals(key)
                        || ModConfig.KEY_EXTERNAL_DESKTOP_ICON_SCALE.equals(key)) {
                    refreshSharedDesktopIconSyncState();
                }
            }
        });
        container.addView(sb);

        card.addView(container);
        if (showDivider) {
            addDivider(card);
        }
        return new SeekBarRowHandle(container, sb, resetChipRef, sumTv, summary);
    }

    private static String formatSeekBarValue(int value, boolean signed, String unitSuffix) {
        if (signed && value > 0) {
            return "+" + value + unitSuffix;
        }
        return value + unitSuffix;
    }

    private void addFolderPathRow(
            LinearLayout card,
            final String title,
            final String key,
            final String defaultPath,
            final int accentColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(mTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(titleTv);

        final TextView pathTv = new TextView(this);
        String current = mPrefs.getString(key, defaultPath);
        if (current == null || current.trim().isEmpty()) {
            current = defaultPath;
        }
        pathTv.setText(current);
        pathTv.setTextColor(accentColor);
        pathTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pathLp.topMargin = dp(2);
        textCol.addView(pathTv, pathLp);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button changeBtn = createStyledButton("Change", mSurfaceElevated, mTextPrimary);
        View.OnClickListener openPicker = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(SettingsActivity.this);
                String cur = mPrefs.getString(key, defaultPath);
                input.setText(cur != null ? cur : defaultPath);
                input.setSingleLine(true);
                input.setSelection(input.getText().length());

                LinearLayout wrap = new LinearLayout(SettingsActivity.this);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.setPadding(dp(20), dp(10), dp(20), dp(4));
                wrap.addView(input);

                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(title)
                        .setMessage("Enter the folder path used when File-Based Desktop is enabled (e.g. /storage/emulated/0/Desktop).")
                        .setView(wrap)
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String raw = input.getText().toString().trim();
                                while (raw.length() > 1 && raw.endsWith("/")) {
                                    raw = raw.substring(0, raw.length() - 1);
                                }
                                if (raw.isEmpty()) {
                                    raw = defaultPath;
                                }
                                mPrefs.edit().putString(key, raw).commit();
                                pathTv.setText(raw);
                                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                                refreshSharedDesktopIconSyncState();
                            }
                        })
                        .setNeutralButton("Reset Default", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mPrefs.edit().remove(key).commit();
                                pathTv.setText(defaultPath);
                                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                                refreshSharedDesktopIconSyncState();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        };
        changeBtn.setOnClickListener(openPicker);
        row.setOnClickListener(openPicker);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(dp(12));
        row.addView(changeBtn, btnLp);
        card.addView(row);
    }

    private void addNewFileTypesSection(LinearLayout card, final boolean external, final int accentColor) {
        final String key = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_NEW_FILE_TYPES
                : ModConfig.KEY_DESKTOP_NEW_FILE_TYPES;
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText("Custom \"New\" File Types");
        titleTv.setTextColor(mTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(titleTv);

        TextView sumTv = new TextView(this);
        sumTv.setText("Templates shown under Desktop right-click → New (Folder is always included).");
        sumTv.setTextColor(mTextSecondary);
        sumTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(2);
        textCol.addView(sumTv, sumLp);

        headerRow.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        final Runnable[] refreshHolder = new Runnable[1];
        refreshHolder[0] = new Runnable() {
            @Override
            public void run() {
                populateNewFileTypeRows(listContainer, external, refreshHolder[0]);
            }
        };

        Button resetBtn = createStyledButton("Reset", mSurfaceElevated, mTextPrimary);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPrefs.edit().remove(key).commit();
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                refreshHolder[0].run();
            }
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.setMarginStart(dp(10));
        headerRow.addView(resetBtn, resetLp);

        Button addBtn = createStyledButton("Add Type", accentColor, Color.WHITE);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddNewFileTypeDialog(external, refreshHolder[0]);
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(dp(8));
        headerRow.addView(addBtn, addLp);

        card.addView(headerRow);
        card.addView(listContainer);
        refreshHolder[0].run();
    }

    private List<ModConfig.NewFileType> loadConfiguredNewFileTypes(boolean external) {
        String key = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_NEW_FILE_TYPES
                : ModConfig.KEY_DESKTOP_NEW_FILE_TYPES;
        String raw = mPrefs.getString(key, ModConfig.DEFAULT_DESKTOP_NEW_FILE_TYPES);
        return ModConfig.parseNewFileTypes(raw);
    }

    private void saveConfiguredNewFileTypes(boolean external, List<ModConfig.NewFileType> types) {
        String key = external
                ? ModConfig.KEY_EXTERNAL_DESKTOP_NEW_FILE_TYPES
                : ModConfig.KEY_DESKTOP_NEW_FILE_TYPES;
        mPrefs.edit()
                .putString(key, ModConfig.serializeNewFileTypes(types))
                .commit();
        ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
    }

    private void populateNewFileTypeRows(LinearLayout container, final boolean external, final Runnable refresh) {
        container.removeAllViews();
        final List<ModConfig.NewFileType> types = loadConfiguredNewFileTypes(external);
        if (types.isEmpty()) {
            addDivider(container);
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No file types configured — only Folder will appear under New.");
            emptyTv.setTextColor(mTextSecondary);
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            emptyTv.setPadding(dp(18), dp(12), dp(18), dp(14));
            container.addView(emptyTv);
            return;
        }
        int removeBg = mIsDark ? Color.rgb(66, 36, 42) : Color.rgb(254, 226, 226);
        int removeFg = mIsDark ? Color.rgb(252, 165, 165) : Color.rgb(185, 28, 28);
        for (int i = 0; i < types.size(); i++) {
            final int index = i;
            final ModConfig.NewFileType entry = types.get(i);
            addDivider(container);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView titleTv = new TextView(this);
            titleTv.setText(entry.displayTitle());
            titleTv.setTextColor(mTextPrimary);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textCol.addView(titleTv);

            TextView subTv = new TextView(this);
            subTv.setText("Creates \"" + entry.defaultFileName() + "\"");
            subTv.setTextColor(mTextSecondary);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            textCol.addView(subTv, subLp);

            row.addView(textCol, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button removeBtn = createStyledButton("Remove", removeBg, removeFg);
            removeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<ModConfig.NewFileType> updated = loadConfiguredNewFileTypes(external);
                    if (index >= 0 && index < updated.size()) {
                        updated.remove(index);
                        saveConfiguredNewFileTypes(external, updated);
                        refresh.run();
                    }
                }
            });
            LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            remLp.setMarginStart(dp(10));
            row.addView(removeBtn, remLp);
            container.addView(row);
        }
    }

    private void showAddNewFileTypeDialog(final boolean external, final Runnable refresh) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(4));

        TextView labelPrompt = new TextView(this);
        labelPrompt.setText("Display name");
        labelPrompt.setTextColor(mTextSecondary);
        labelPrompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        form.addView(labelPrompt);

        final EditText labelInput = new EditText(this);
        labelInput.setHint("e.g. Markdown Document");
        labelInput.setSingleLine(true);
        form.addView(labelInput);

        TextView extPrompt = new TextView(this);
        extPrompt.setText("File extension");
        extPrompt.setTextColor(mTextSecondary);
        extPrompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams extPromptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        extPromptLp.topMargin = dp(10);
        form.addView(extPrompt, extPromptLp);

        final EditText extInput = new EditText(this);
        extInput.setHint("e.g. md or .md");
        extInput.setSingleLine(true);
        form.addView(extInput);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add New File Type")
                .setView(form)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (pos == null) return;
                pos.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String cleanExt = ModConfig.sanitizeExtension(extInput.getText().toString());
                        if (cleanExt.isEmpty()) {
                            Toast.makeText(SettingsActivity.this,
                                    "Enter a file extension (e.g. md)", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String cleanLabel = ModConfig.sanitizeFileTypeLabel(
                                labelInput.getText().toString(), cleanExt);
                        List<ModConfig.NewFileType> types = loadConfiguredNewFileTypes(external);
                        for (int i = 0; i < types.size(); i++) {
                            if (types.get(i).ext.equalsIgnoreCase(cleanExt)) {
                                Toast.makeText(SettingsActivity.this,
                                        "." + cleanExt + " is already in the list",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        types.add(new ModConfig.NewFileType(cleanExt, cleanLabel));
                        saveConfiguredNewFileTypes(external, types);
                        refresh.run();
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    private void addDivider(LinearLayout card) {
        View div = new View(this);
        div.setBackgroundColor(mDividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        card.addView(div, lp);
    }

    /** Samsung's freeform corner size, in dp, so the slider starts on the current look. */
    private int systemCornerDp() {
        int px = 0;
        String[] names = new String[] {"mw_desktop_external_display_corner_radius", "freeform_rounded_corner_radius"};
        for (int i = 0; i < names.length; i++) {
            try {
                int id = getResources().getIdentifier(names[i], "dimen", "android");
                if (id != 0) {
                    px = getResources().getDimensionPixelSize(id);
                    if (px > 0) {
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (px <= 0) {
            int[] fallbackIds = new int[] {0x01050305, 0x010501e9};
            for (int id : fallbackIds) {
                try {
                    px = getResources().getDimensionPixelSize(id);
                    if (px > 0) {
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }
        float density = getResources().getDisplayMetrics().density;
        if (px <= 0 || density <= 0f) {
            return 16;
        }
        int value = Math.round(px / density);
        if (value < 0) return 0;
        if (value > 48) return 48;
        return value;
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

    /** Minimalist vector-style geometric icons drawn on Canvas for the navigation rail. */
    private static final class NavIconView extends View {
        private final int mKind;
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        NavIconView(Context context, int kind) {
            super(context);
            mKind = kind;
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeCap(Paint.Cap.ROUND);
            mStroke.setStrokeJoin(Paint.Join.ROUND);
            mFill.setStyle(Paint.Style.FILL);
            setIconColor(Color.GRAY);
        }

        void setIconColor(int color) {
            mStroke.setColor(color);
            mFill.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float sw = Math.max(1.6f, s * 0.1f);
            mStroke.setStrokeWidth(sw);
            float p = sw * 1.1f;
            float r = s * 0.14f;

            switch (mKind) {
                case ICON_OVERVIEW: {
                    float mid = s * 0.5f;
                    float gap = s * 0.08f;
                    mRect.set(p, p, mid - gap, mid - gap);
                    c.drawRoundRect(mRect, r * 0.7f, r * 0.7f, mStroke);
                    mRect.set(mid + gap, p, s - p, mid - gap);
                    c.drawRoundRect(mRect, r * 0.7f, r * 0.7f, mStroke);
                    mRect.set(p, mid + gap, mid - gap, s - p);
                    c.drawRoundRect(mRect, r * 0.7f, r * 0.7f, mStroke);
                    mRect.set(mid + gap, mid + gap, s - p, s - p);
                    c.drawRoundRect(mRect, r * 0.7f, r * 0.7f, mFill);
                    break;
                }
                case ICON_WINDOWS: {
                    mRect.set(p, p + s * 0.06f, s - p, s - p - s * 0.06f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    float barY = p + s * 0.28f;
                    c.drawLine(p, barY, s - p, barY, mStroke);
                    break;
                }
                case ICON_INT_TASKBAR: {
                    mRect.set(p, p + s * 0.06f, s - p, s - p - s * 0.06f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    float tbTop = s - p - s * 0.26f;
                    mRect.set(p + sw, tbTop, s - p - sw, s - p - s * 0.06f - sw * 0.5f);
                    c.drawRoundRect(mRect, r * 0.4f, r * 0.4f, mFill);
                    break;
                }
                case ICON_INT_DESKTOP: {
                    mRect.set(p, p + s * 0.06f, s - p, s - p - s * 0.06f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    float dot = s * 0.075f;
                    c.drawCircle(s * 0.32f, s * 0.36f, dot, mFill);
                    c.drawCircle(s * 0.32f, s * 0.62f, dot, mFill);
                    c.drawCircle(s * 0.58f, s * 0.36f, dot, mFill);
                    break;
                }
                case ICON_INT_DPI: {
                    mRect.set(p, p + s * 0.06f, s - p, s - p - s * 0.06f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    c.drawLine(s * 0.32f, s * 0.66f, s * 0.68f, s * 0.34f, mStroke);
                    c.drawLine(s * 0.52f, s * 0.34f, s * 0.68f, s * 0.34f, mStroke);
                    c.drawLine(s * 0.68f, s * 0.34f, s * 0.68f, s * 0.50f, mStroke);
                    break;
                }
                case ICON_EXT_TASKBAR: {
                    mRect.set(p, p, s - p, s * 0.74f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    c.drawLine(s * 0.34f, s - p, s * 0.66f, s - p, mStroke);
                    c.drawLine(s * 0.5f, s * 0.74f, s * 0.5f, s - p, mStroke);
                    mRect.set(p + sw, s * 0.56f, s - p - sw, s * 0.70f);
                    c.drawRoundRect(mRect, r * 0.3f, r * 0.3f, mFill);
                    break;
                }
                case ICON_EXT_DESKTOP: {
                    mRect.set(p, p, s - p, s * 0.74f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    c.drawLine(s * 0.34f, s - p, s * 0.66f, s - p, mStroke);
                    c.drawLine(s * 0.5f, s * 0.74f, s * 0.5f, s - p, mStroke);
                    float dot = s * 0.07f;
                    c.drawCircle(s * 0.34f, s * 0.30f, dot, mFill);
                    c.drawCircle(s * 0.34f, s * 0.52f, dot, mFill);
                    c.drawCircle(s * 0.58f, s * 0.30f, dot, mFill);
                    break;
                }
                case ICON_EXT_DPI: {
                    mRect.set(p, p, s - p, s * 0.74f);
                    c.drawRoundRect(mRect, r, r, mStroke);
                    c.drawLine(s * 0.34f, s - p, s * 0.66f, s - p, mStroke);
                    c.drawLine(s * 0.5f, s * 0.74f, s * 0.5f, s - p, mStroke);
                    c.drawLine(s * 0.34f, s * 0.54f, s * 0.66f, s * 0.24f, mStroke);
                    c.drawLine(s * 0.50f, s * 0.24f, s * 0.66f, s * 0.24f, mStroke);
                    c.drawLine(s * 0.66f, s * 0.24f, s * 0.66f, s * 0.40f, mStroke);
                    break;
                }
            }
        }
    }
}
