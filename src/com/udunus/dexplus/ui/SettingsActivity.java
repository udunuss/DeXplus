package com.udunus.dexplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
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

    private SharedPreferences mPrefs;
    private boolean mIsDark;

    private int mBgColor;
    private int mSidebarColor;
    private int mCardColor;
    private int mTextPrimary;
    private int mTextSecondary;
    private int mAccentColor;
    private int mDividerColor;

    private int mSelectedTab = 0;
    private final List<LinearLayout> mTabButtons = new ArrayList<>();
    private final List<TextView> mTabTitles = new ArrayList<>();
    private final List<TextView> mTabSubtitles = new ArrayList<>();
    private final List<View> mTabIndicators = new ArrayList<>();
    private final List<View> mTabPages = new ArrayList<>();
    private ScrollView mContentScroll;
    private TextView mPageHeaderTitle;
    private TextView mPageHeaderSubtitle;

    private static final String[] TAB_NAMES = new String[]{
            "General",
            "Taskbar & Panels",
            "Home Screen",
            "Dedicated Workspace",
            "Window Controls"
    };

    private static final String[] TAB_SUMMARIES = new String[]{
            "Activation, keyboard options & quick actions",
            "Desktop taskbar, notification panels & scaling",
            "Icon-fit grid, workspace margins & gesture locks",
            "Full-screen shortcut grid & launch behavior",
            "Title bar buttons, pinning, menu tools & sizing"
    };

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

        mBgColor = mIsDark ? Color.rgb(12, 16, 24) : Color.rgb(242, 244, 248);
        mSidebarColor = mIsDark ? Color.rgb(17, 22, 33) : Color.rgb(233, 237, 244);
        mCardColor = mIsDark ? Color.rgb(23, 30, 44) : Color.WHITE;
        mTextPrimary = mIsDark ? Color.rgb(243, 246, 250) : Color.rgb(17, 24, 39);
        mTextSecondary = mIsDark ? Color.rgb(154, 164, 178) : Color.rgb(100, 116, 139);
        mAccentColor = Color.rgb(59, 130, 246);
        mDividerColor = mIsDark ? Color.argb(28, 255, 255, 255) : Color.argb(22, 0, 0, 0);

        boolean compact = getResources().getConfiguration().screenWidthDp < 600;

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
            // Left navigation rail (Sideways tabs)
            LinearLayout sidebar = buildSidebar();
            rootSplit.addView(sidebar, new LinearLayout.LayoutParams(
                    dp(260), ViewGroup.LayoutParams.MATCH_PARENT));

            // Vertical divider between sidebar and content
            View railDivider = new View(this);
            railDivider.setBackgroundColor(mDividerColor);
            rootSplit.addView(railDivider, new LinearLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Right content area
        LinearLayout rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setBackgroundColor(mBgColor);

        // Page header bar
        LinearLayout pageHeader = new LinearLayout(this);
        pageHeader.setOrientation(LinearLayout.VERTICAL);
        int hPad = dp(compact ? 18 : 28);
        pageHeader.setPadding(hPad, dp(compact ? 14 : 22), hPad, dp(compact ? 10 : 14));

        mPageHeaderTitle = new TextView(this);
        mPageHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 19f : 22f);
        mPageHeaderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mPageHeaderTitle.setTextColor(mTextPrimary);
        pageHeader.addView(mPageHeaderTitle);

        mPageHeaderSubtitle = new TextView(this);
        mPageHeaderSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12f : 13f);
        mPageHeaderSubtitle.setTextColor(mTextSecondary);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
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
        pageContainer.setPadding(hPad, dp(compact ? 14 : 20), hPad, dp(28));

        mTabPages.clear();
        mTabPages.add(buildGeneralPage());
        mTabPages.add(buildTaskbarPage());
        mTabPages.add(buildHomeScreenPage());
        mTabPages.add(buildDedicatedWorkspacePage());
        mTabPages.add(buildWindowControlsPage());

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
        setContentView(rootSplit);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED_TAB, mSelectedTab);
    }

    private LinearLayout buildCompactTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(mSidebarColor);
        bar.setPadding(dp(14), dp(14), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), dp(10));

        TextView appTitle = new TextView(this);
        appTitle.setText("Dex+");
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        appTitle.setTextColor(mTextPrimary);
        header.addView(appTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean active = isModuleActive();
        TextView statusBadge = new TextView(this);
        statusBadge.setText(active ? "●  Active in LSPosed" : "●  Not Active in LSPosed");
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBadge.setTextColor(active ? Color.rgb(16, 185, 129) : Color.rgb(245, 158, 11));
        header.addView(statusBadge);
        bar.addView(header);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        mTabButtons.clear();
        mTabTitles.clear();
        mTabSubtitles.clear();
        mTabIndicators.clear();

        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.VERTICAL);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(12), dp(8), dp(12), dp(6));

            TextView titleTv = new TextView(this);
            titleTv.setText(TAB_NAMES[i]);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            titleTv.setSingleLine(true);
            pill.addView(titleTv);

            View indicator = new View(this);
            GradientDrawable indBg = new GradientDrawable();
            indBg.setCornerRadius(dp(2));
            indBg.setColor(mAccentColor);
            indicator.setBackground(indBg);
            LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(dp(20), dp(3));
            indLp.topMargin = dp(4);
            pill.addView(indicator, indLp);

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
            mTabIndicators.add(indicator);
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
        sidebar.setPadding(dp(14), dp(22), dp(14), dp(18));

        // App Title & Status
        LinearLayout brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.VERTICAL);
        brandBox.setPadding(dp(10), 0, dp(10), dp(16));

        TextView appTitle = new TextView(this);
        appTitle.setText("Dex+");
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        appTitle.setTextColor(mTextPrimary);
        brandBox.addView(appTitle);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("Desktop Environment for One UI 8.5");
        appSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        appSubtitle.setTextColor(mTextSecondary);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        brandBox.addView(appSubtitle, subLp);

        boolean active = isModuleActive();
        TextView statusBadge = new TextView(this);
        statusBadge.setText(active ? "●  Active in LSPosed" : "●  Not Active in LSPosed");
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        int statusColor = active ? Color.rgb(16, 185, 129) : Color.rgb(245, 158, 11);
        statusBadge.setTextColor(statusColor);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(8);
        brandBox.addView(statusBadge, badgeLp);

        sidebar.addView(brandBox);

        // Scrollable tab list
        ScrollView tabScroll = new ScrollView(this);
        tabScroll.setVerticalScrollBarEnabled(false);
        LinearLayout tabList = new LinearLayout(this);
        tabList.setOrientation(LinearLayout.VERTICAL);

        mTabButtons.clear();
        mTabTitles.clear();
        mTabSubtitles.clear();
        mTabIndicators.clear();

        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            LinearLayout tabItem = new LinearLayout(this);
            tabItem.setOrientation(LinearLayout.HORIZONTAL);
            tabItem.setGravity(Gravity.CENTER_VERTICAL);
            tabItem.setPadding(dp(10), dp(11), dp(12), dp(11));

            View indicator = new View(this);
            GradientDrawable indBg = new GradientDrawable();
            indBg.setCornerRadius(dp(4));
            indBg.setColor(mAccentColor);
            indicator.setBackground(indBg);
            LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(dp(4), dp(26));
            indLp.setMarginEnd(dp(10));
            tabItem.addView(indicator, indLp);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView titleTv = new TextView(this);
            titleTv.setText(TAB_NAMES[i]);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            textCol.addView(titleTv);

            TextView subTv = new TextView(this);
            subTv.setText(TAB_SUMMARIES[i]);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            subTv.setSingleLine(true);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(1);
            textCol.addView(subTv, sLp);

            tabItem.addView(textCol, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            tabItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(4);
            tabList.addView(tabItem, itemLp);

            mTabButtons.add(tabItem);
            mTabTitles.add(titleTv);
            mTabSubtitles.add(subTv);
            mTabIndicators.add(indicator);
        }

        tabScroll.addView(tabList);
        sidebar.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return sidebar;
    }

    private void selectTab(int index) {
        if (index < 0 || index >= mTabPages.size()) index = 0;
        mSelectedTab = index;

        if (mPageHeaderTitle != null) {
            mPageHeaderTitle.setText(TAB_NAMES[index]);
        }
        if (mPageHeaderSubtitle != null) {
            mPageHeaderSubtitle.setText(TAB_SUMMARIES[index]);
        }

        for (int i = 0; i < mTabPages.size(); i++) {
            boolean selected = (i == index);
            mTabPages.get(i).setVisibility(selected ? View.VISIBLE : View.GONE);

            LinearLayout btn = mTabButtons.get(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            if (selected) {
                bg.setColor(mIsDark ? Color.argb(45, 59, 130, 246) : Color.argb(30, 59, 130, 246));
            } else {
                bg.setColor(Color.TRANSPARENT);
            }
            btn.setBackground(bg);

            TextView titleTv = mTabTitles.get(i);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", selected ? Typeface.BOLD : Typeface.NORMAL));
            titleTv.setTextColor(selected ? mAccentColor : mTextPrimary);

            if (i < mTabSubtitles.size()) {
                TextView subTv = mTabSubtitles.get(i);
                subTv.setTextColor(mTextSecondary);
            }

            View ind = mTabIndicators.get(i);
            ind.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }

        if (mContentScroll != null) {
            mContentScroll.scrollTo(0, 0);
        }
    }

    private View buildGeneralPage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "DESKTOP MODE");
        LinearLayout mainCard = createCard();
        addSwitchRow(mainCard,
                "Enable Dex+",
                "Master switch for all desktop taskbar, workspace, status bar, and window controls.",
                ModConfig.KEY_MODULE_ENABLED, true, true);
        addSwitchRow(mainCard,
                "Keep Desktop Mode Always Active",
                "Keep the desktop taskbar and window features active on the built-in display even when DeX mode is turned off.",
                ModConfig.KEY_FORCE_INTERNAL_DEX, false, true);
        addSwitchRow(mainCard,
                "Hide Physical Keyboard Toolbar",
                "Hides the floating on-screen keyboard toolbar when a hardware keyboard is connected.",
                ModConfig.KEY_HIDE_PHYSICAL_KB_TOOLBAR, true, false);
        page.addView(mainCard);

        addSectionLabel(page, "SYSTEM ACTIONS");
        LinearLayout actionsCard = createCard();
        int pad = dp(18);
        actionsCard.setPadding(pad, pad, pad, pad);

        TextView actionsDesc = new TextView(this);
        actionsDesc.setText("Changes apply as soon as you toggle them. Restart Home or System UI when a layout or the title bar needs a fresh start.");
        actionsDesc.setTextColor(mTextSecondary);
        actionsDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.bottomMargin = dp(14);
        actionsCard.addView(actionsDesc, descLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button restartHomeBtn = createStyledButton("Restart Home Screen",
                mIsDark ? Color.rgb(42, 54, 74) : Color.rgb(226, 232, 240), mTextPrimary);
        restartHomeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                requestHostRestart("launcher", "com.sec.android.app.launcher");
                Toast.makeText(SettingsActivity.this, "Restarting One UI Home...", Toast.LENGTH_SHORT).show();
            }
        });
        row1.addView(restartHomeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View sp1 = new View(this);
        row1.addView(sp1, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button restartSysUiBtn = createStyledButton("Restart System UI",
                mIsDark ? Color.rgb(42, 54, 74) : Color.rgb(226, 232, 240), mTextPrimary);
        restartSysUiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
                requestHostRestart("systemui", "com.android.systemui");
                Toast.makeText(SettingsActivity.this, "Restarting System UI...", Toast.LENGTH_SHORT).show();
            }
        });
        row1.addView(restartSysUiBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View sp2 = new View(this);
        row1.addView(sp2, new LinearLayout.LayoutParams(dp(10), dp(1)));

        Button resetBtn = createStyledButton("Restore Defaults",
                mIsDark ? Color.rgb(68, 38, 48) : Color.rgb(254, 226, 226),
                mIsDark ? Color.rgb(252, 165, 165) : Color.rgb(185, 28, 28));
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Restore Default Settings")
                        .setMessage("Reset all Dex+ preferences to their default values?")
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
        row1.addView(resetBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        actionsCard.addView(row1);
        page.addView(actionsCard);

        return page;
    }

    private View buildTaskbarPage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "TASKBAR & STATUS AREA");
        LinearLayout tbCard = createCard();
        addSwitchRow(tbCard,
                "Enable Desktop Taskbar",
                "Shows the desktop taskbar with running apps, notification badge, quick settings, calendar, and audio controls on the built-in display.",
                ModConfig.KEY_DEX_TASKBAR_ENABLED, true, true);
        addSwitchRow(tbCard,
                "Use Standard Notification & Quick Settings Shade",
                "When enabled, clicking the notification or quick settings buttons on the taskbar opens the full pull-down system shade instead of compact popups.",
                ModConfig.KEY_STATUS_PANEL_MODE, false, true, true);
        addSwitchRow(tbCard,
                "Show Search Button on Taskbar",
                "Displays the Finder search button in the taskbar status area.",
                ModConfig.KEY_SHOW_DEX_FINDER_BTN, true, true);
        addSwitchRow(tbCard,
                "Apply Scaling to External Displays",
                "Applies the taskbar icon and density scale to connected external monitors as well.",
                ModConfig.KEY_FIX_EXTERNAL_DEX_DPI, true, false);
        page.addView(tbCard);

        addSectionLabel(page, "TASKBAR SIZING");
        LinearLayout scaleCard = createCard();
        addSeekBarRow(scaleCard,
                "Taskbar Icon & Text Scale",
                "Adjusts the size of app icons and status indicators on the taskbar (Default: 100%).",
                ModConfig.KEY_TASKBAR_ICON_SCALE, 70, 140, 100, "%", true);
        addSeekBarRow(scaleCard,
                "Taskbar Height",
                "Adjusts the overall height of the desktop taskbar (Default: 100%).",
                ModConfig.KEY_TASKBAR_HEIGHT_SCALE, 75, 135, 100, "%", false);
        page.addView(scaleCard);

        return page;
    }

    private View buildHomeScreenPage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "HOME SCREEN BEHAVIOR");
        LinearLayout behaviorCard = createCard();
        addSwitchRow(behaviorCard,
                "Hide Page Navigation Arrows",
                "Hides the left and right page indicator arrows on the home screen.",
                ModConfig.KEY_HIDE_PAGE_ARROWS, true, true);
        addSwitchRow(behaviorCard,
                "Lock Home Screen Gestures",
                "Prevents upward app drawer swipes and two-finger pinch edit gestures while in desktop mode.",
                ModConfig.KEY_BLOCK_HOME_GESTURES, true, false);
        page.addView(behaviorCard);

        addSectionLabel(page, "ICON SIZE");
        LinearLayout iconCard = createCard();
        addSeekBarRow(iconCard,
                "Icon size",
                "Size of the icons on the home screen. The grid keeps as many as still fit at this size (Default: 100%).",
                ModConfig.KEY_HOME_ICON_SCALE, 60, 160, 100, "%", false);
        page.addView(iconCard);

        addSectionLabel(page, "WORKSPACE MARGINS");
        LinearLayout marginCard = createCard();
        addSeekBarRow(marginCard,
                "Side Margins",
                "Horizontal padding on the left and right edges of the home screen (Default: 40%).",
                ModConfig.KEY_SIDE_MARGIN_PCT, 0, 100, 40, "%", true);
        addSeekBarRow(marginCard,
                "Top Margin",
                "Top padding above the home screen grid (Default: 0%).",
                ModConfig.KEY_TOP_MARGIN_PCT, 0, 100, 0, "%", true);
        addSeekBarRow(marginCard,
                "Bottom Margin",
                "Extra space between the home screen icons and the taskbar. 0% sits the grid on the taskbar (Default: 100%).",
                ModConfig.KEY_BOTTOM_MARGIN_PCT, 0, 100, 100, "%", false);
        page.addView(marginCard);

        return page;
    }

    private View buildDedicatedWorkspacePage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "DEDICATED WORKSPACE");
        LinearLayout wsCard = createCard();
        addSwitchRow(wsCard,
                "Enable Dedicated Workspace",
                "Replaces the standard home screen pages and widgets with a clean, full-screen desktop shortcut grid. Right-click or long-press anywhere to add or remove shortcuts.",
                ModConfig.KEY_CUSTOM_DESKTOP_ENABLED, false, true);
        addSwitchRow(wsCard,
                "Open Shortcuts in Resizable Windows",
                "Launches apps from the dedicated workspace directly in freeform desktop windows.",
                ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true, true);
        addSwitchRow(wsCard,
                "Double-Click to Open Shortcuts",
                "First tap or click selects the shortcut; double-click opens the app.",
                ModConfig.KEY_DESKTOP_DOUBLE_CLICK, false, false);
        page.addView(wsCard);

        addSectionLabel(page, "WORKSPACE GRID & ICON SIZE");
        LinearLayout gridCard = createCard();
        addSeekBarRow(gridCard,
                "Workspace Columns",
                "Number of shortcut columns across the dedicated workspace (Default: 10).",
                ModConfig.KEY_DESKTOP_GRID_COLS, 6, 16, 10, " cols", true);
        addSeekBarRow(gridCard,
                "Workspace Rows",
                "Number of shortcut rows from top to bottom (Default: 5).",
                ModConfig.KEY_DESKTOP_GRID_ROWS, 4, 10, 5, " rows", true);
        addSeekBarRow(gridCard,
                "Shortcut Icon Size",
                "Scales shortcut icons and labels on the dedicated workspace (Default: 100%).",
                ModConfig.KEY_DESKTOP_ICON_SCALE, 70, 140, 100, "%", false);
        page.addView(gridCard);

        addSectionLabel(page, "SHORTCUT MANAGEMENT");
        LinearLayout resetCard = createCard();
        int pad = dp(18);
        resetCard.setPadding(pad, pad, pad, pad);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("Reset Workspace Shortcuts");
        t.setTextColor(mTextPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText("Restores the default set of app shortcuts on the dedicated workspace.");
        s.setTextColor(mTextSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(3);
        textCol.addView(s, sLp);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button resetShortcutsBtn = createStyledButton("Reset Shortcuts",
                mIsDark ? Color.rgb(42, 54, 74) : Color.rgb(226, 232, 240), mTextPrimary);
        resetShortcutsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Reset Workspace Shortcuts")
                        .setMessage("Restore the default set of app shortcuts on the dedicated workspace?")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent cmd = new Intent(ModConfig.ACTION_DESKTOP_COMMAND);
                                cmd.putExtra("command", "reset_shortcuts");
                                sendBroadcast(cmd);
                                Toast.makeText(SettingsActivity.this,
                                        "Workspace shortcuts reset to defaults", Toast.LENGTH_SHORT).show();
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

    private View buildWindowControlsPage() {
        LinearLayout page = createPageContainer();

        addSectionLabel(page, "STATUS BAR & SNAPPING");
        LinearLayout barCard = createCard();
        addSwitchRow(barCard,
                "Hide Top Status Bar",
                "Collapses the top Android status bar in desktop mode so windows can use the full vertical screen area.",
                ModConfig.KEY_HIDE_STATUS_BAR, true, true);
        addSwitchRow(barCard,
                "Drag Window to Top Edge to Maximize",
                "Dragging a window to the top edge of the screen maximizes it instead of switching to full-screen tablet mode.",
                ModConfig.KEY_TOP_EDGE_MAXIMIZE, true, true);
        addSwitchRow(barCard,
                "Fullscreen inside the window",
                "A video fills the window it is already in. The window is not resized, and DeX stays open. Turn this off to let the system handle fullscreen.",
                ModConfig.KEY_FULLSCREEN_INSIDE, true, true);
        addSwitchRow(barCard,
                "Allow All Apps in Resizable Windows",
                "Lets all installed apps run in freeform multi-window mode and be freely resized.",
                ModConfig.KEY_FORCE_RESIZABLE_WINDOWS, true, false);
        page.addView(barCard);

        addSectionLabel(page, "TITLE BAR BUTTONS");
        LinearLayout titleBarCard = createCard();
        addSwitchRow(titleBarCard,
                "Always-on-Top Pin Button",
                "Adds a pin button to window title bars to keep selected windows floating above others.",
                ModConfig.KEY_PIN_BUTTON_ENABLED, true, true);
        addSwitchRow(titleBarCard,
                "Hide Fullscreen Button on Windowed Apps",
                "Hides the separate fullscreen button on normal/maximized windows to keep the title bar compact.",
                ModConfig.KEY_HIDE_FULLSCREEN_BTN, true, true);
        addSwitchRow(titleBarCard,
                "Long-Press Maximize for Fullscreen",
                "Hold down the Maximize/Restore button to toggle immersive fullscreen mode for that window.",
                ModConfig.KEY_HOLD_MAXIMIZE_FULLSCREEN, true, true);
        addSwitchRow(titleBarCard,
                "Hide App Icon Dropdown Arrow",
                "Hides the small downward arrow next to the app icon while keeping the icon menu clickable.",
                ModConfig.KEY_HIDE_APP_CHEVRON, true, true);
        addSeekBarRow(titleBarCard,
                "Window corner radius",
                "Rounds window corners. 0 is square.",
                ModConfig.KEY_WINDOW_CORNER_RADIUS, 0, 48, systemCornerDp(), " dp", false);
        page.addView(titleBarCard);

        addSectionLabel(page, "APP ICON WINDOW MENU");
        LinearLayout menuCard = createCard();
        addSwitchRow(menuCard,
                "Enable Enhanced Window Menu",
                "Adds extra window tools when clicking the app icon in a window's title bar.",
                ModConfig.KEY_HEADER_MENU_CUSTOM_ENABLED, true, true);
        addSwitchRow(menuCard,
                "Window Opacity Slider",
                "Adds a live transparency slider (20%–100%) to the app icon menu.",
                ModConfig.KEY_ADD_TRANSPARENCY_SLIDER, true, true);
        addSwitchRow(menuCard,
                "Center & Keep Awake Buttons",
                "Adds quick buttons to center the window on screen and keep the display awake while the window is visible.",
                ModConfig.KEY_ADD_EXTRA_WINDOW_TOOLS, true, true);
        addSwitchRow(menuCard,
                "Show App Info Shortcut",
                "Replaces Remove from workspace with a button that opens the application's system App Info page.",
                ModConfig.KEY_ADD_APP_INFO_BTN, true, false);
        addSeekBarRow(menuCard,
                "Default Window Opacity",
                "Starting opacity level for newly opened windows (Default: 100%).",
                ModConfig.KEY_DEFAULT_WINDOW_OPACITY, 30, 100, 100, "%", false);
        page.addView(menuCard);

        return page;
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
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));
        return btn;
    }

    private void addSectionLabel(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(mAccentColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
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
        bg.setCornerRadius(dp(16));
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

    private void addSeekBarRow(
            LinearLayout card,
            String title,
            String summary,
            final String key,
            final int minVal,
            final int maxVal,
            int defaultVal,
            final String unitSuffix,
            boolean showDivider) {
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

        int curVal = mPrefs.getInt(key, defaultVal);
        curVal = Math.max(minVal, Math.min(maxVal, curVal));

        final TextView valTv = new TextView(this);
        valTv.setText(curVal + unitSuffix);
        valTv.setTextColor(mAccentColor);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        valTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        headerRow.addView(valTv);

        container.addView(headerRow);

        TextView sumTv = new TextView(this);
        sumTv.setText(summary);
        sumTv.setTextColor(mTextSecondary);
        sumTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(2);
        sumLp.bottomMargin = dp(6);
        container.addView(sumTv, sumLp);

        SeekBar sb = new SeekBar(this);
        sb.setMax(maxVal - minVal);
        sb.setProgress(curVal - minVal);
        ColorStateList accentTint = ColorStateList.valueOf(mAccentColor);
        sb.setThumbTintList(accentTint);
        sb.setProgressTintList(accentTint);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = minVal + progress;
                valTv.setText(v + unitSuffix);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int v = minVal + seekBar.getProgress();
                mPrefs.edit().putInt(key, v).commit();
                ModConfig.broadcastChanges(SettingsActivity.this, mPrefs);
            }
        });
        container.addView(sb);

        card.addView(container);
        if (showDivider) {
            addDivider(card);
        }
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
        if (value < 0) {
            return 0;
        }
        if (value > 48) {
            return 48;
        }
        return value;
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }
}
