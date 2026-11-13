package com.udunus.dexplus.util;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XSharedPreferences;

public final class ModConfig {
    public static final String TAG = "MinsOSInternalDex";
    public static final String MODULE_PACKAGE = "com.udunus.dexplus";
    public static final String GROK_MODULE_PACKAGE = "com.minsos.externaldex";
    public static final String PREFS_NAME = "internal_dex_prefs";
    public static final String AUTHORITY = "com.udunus.dexplus.provider";
    public static final Uri PREFS_URI = Uri.parse("content://" + AUTHORITY + "/prefs");
    public static final String ACTION_PREFS_CHANGED = "com.udunus.dexplus.ACTION_PREFS_CHANGED";
    public static final String ACTION_DESKTOP_COMMAND = "com.udunus.dexplus.ACTION_DESKTOP_COMMAND";
    public static final String ACTION_RESTART_HOST = "com.udunus.dexplus.ACTION_RESTART_HOST";
    public static final String PERMISSION_RESTART_HOST = "com.udunus.dexplus.permission.RESTART_HOST";
    public static final String EXTRA_RESTART_TARGET = "target";
    public static final String EXTRA_RESET = "__dexplus_reset__";

    // General
    public static final String KEY_MODULE_ENABLED = "module_enabled";
    public static final String KEY_FORCE_INTERNAL_DEX = "force_internal_dex";
    public static final String KEY_HIDE_PHYSICAL_KB_TOOLBAR = "hide_physical_kb_toolbar";

    // Taskbar & Status Panel (Launcher)
    public static final String KEY_DEX_TASKBAR_ENABLED = "dex_taskbar_enabled";
    public static final String KEY_FIX_EXTERNAL_DEX_DPI = "fix_external_dex_dpi";
    public static final String KEY_TASKBAR_ICON_SCALE = "taskbar_icon_scale";
    public static final String KEY_TASKBAR_HEIGHT_SCALE = "taskbar_height_scale";
    public static final String KEY_STATUS_PANEL_MODE = "status_panel_mode"; // 0 = DeX Panels + Fallback, 1 = System Shade
    public static final String KEY_SHOW_DEX_FINDER_BTN = "show_dex_finder_btn";

    // Native Home Screen & Layout (Launcher)
    public static final String KEY_HIDE_PAGE_ARROWS = "hide_page_arrows";
    public static final String KEY_BLOCK_HOME_GESTURES = "block_home_gestures";
    public static final String KEY_SIDE_MARGIN_PCT = "side_margin_pct";
    public static final String KEY_TOP_MARGIN_PCT = "top_margin_pct";
    public static final String KEY_BOTTOM_MARGIN_PCT = "bottom_margin_pct";
    public static final String KEY_HOME_ICON_SCALE = "home_icon_scale";

    // Optional Custom Desktop Overlay (Launcher)
    public static final String KEY_CUSTOM_DESKTOP_ENABLED = "custom_desktop_enabled";
    public static final String KEY_DESKTOP_GRID_COLS = "desktop_grid_cols";
    public static final String KEY_DESKTOP_GRID_ROWS = "desktop_grid_rows";
    public static final String KEY_DESKTOP_ICON_SCALE = "desktop_icon_scale";
    public static final String KEY_DESKTOP_DOUBLE_CLICK = "desktop_double_click";
    public static final String KEY_DESKTOP_FREEFORM_LAUNCH = "desktop_freeform_launch";

    // Window Decorations & Status Bar (SystemUI)
    public static final String KEY_HIDE_STATUS_BAR = "hide_status_bar";
    public static final String KEY_TOP_EDGE_MAXIMIZE = "top_edge_maximize";
    public static final String KEY_HIDE_FULLSCREEN_BTN = "hide_fullscreen_btn";
    public static final String KEY_HOLD_MAXIMIZE_FULLSCREEN = "hold_maximize_fullscreen";
    public static final String KEY_PIN_BUTTON_ENABLED = "pin_button_enabled";
    public static final String KEY_HIDE_APP_CHEVRON = "hide_app_chevron";
    public static final String KEY_HEADER_MENU_CUSTOM_ENABLED = "header_menu_custom_enabled";
    public static final String KEY_ADD_TRANSPARENCY_SLIDER = "add_transparency_slider";
    public static final String KEY_ADD_APP_INFO_BTN = "add_app_info_btn";
    public static final String KEY_ADD_EXTRA_WINDOW_TOOLS = "add_extra_window_tools";
    public static final String KEY_DEFAULT_WINDOW_OPACITY = "default_window_opacity";
    public static final String KEY_WINDOW_CORNER_RADIUS = "window_corner_radius";

    // Framework / Multi-Window
    public static final String KEY_FORCE_RESIZABLE_WINDOWS = "force_resizable_windows";
    public static final String KEY_FULLSCREEN_INSIDE = "fullscreen_inside_window";

    private static final Map<String, Object> DEFAULTS = createDefaults();
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>(DEFAULTS);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean receiverRegistered = false;
    private static volatile long lastReloadTime = 0L;
    private static XSharedPreferences xPrefs;

    private ModConfig() {}

    private static Map<String, Object> createDefaults() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(KEY_MODULE_ENABLED, true);
        defaults.put(KEY_FORCE_INTERNAL_DEX, false);
        defaults.put(KEY_HIDE_PHYSICAL_KB_TOOLBAR, true);

        defaults.put(KEY_DEX_TASKBAR_ENABLED, true);
        defaults.put(KEY_FIX_EXTERNAL_DEX_DPI, true);
        defaults.put(KEY_TASKBAR_ICON_SCALE, 100);
        defaults.put(KEY_TASKBAR_HEIGHT_SCALE, 100);
        defaults.put(KEY_STATUS_PANEL_MODE, 0);
        defaults.put(KEY_SHOW_DEX_FINDER_BTN, true);

        defaults.put(KEY_HIDE_PAGE_ARROWS, true);
        defaults.put(KEY_BLOCK_HOME_GESTURES, true);
        defaults.put(KEY_SIDE_MARGIN_PCT, 40);
        defaults.put(KEY_TOP_MARGIN_PCT, 0);
        defaults.put(KEY_BOTTOM_MARGIN_PCT, 100);
        defaults.put(KEY_HOME_ICON_SCALE, 100);

        defaults.put(KEY_CUSTOM_DESKTOP_ENABLED, false);
        defaults.put(KEY_DESKTOP_GRID_COLS, 10);
        defaults.put(KEY_DESKTOP_GRID_ROWS, 5);
        defaults.put(KEY_DESKTOP_ICON_SCALE, 100);
        defaults.put(KEY_DESKTOP_DOUBLE_CLICK, false);
        defaults.put(KEY_DESKTOP_FREEFORM_LAUNCH, true);

        defaults.put(KEY_HIDE_STATUS_BAR, true);
        defaults.put(KEY_TOP_EDGE_MAXIMIZE, true);
        defaults.put(KEY_HIDE_FULLSCREEN_BTN, true);
        defaults.put(KEY_HOLD_MAXIMIZE_FULLSCREEN, true);
        defaults.put(KEY_PIN_BUTTON_ENABLED, true);
        defaults.put(KEY_HIDE_APP_CHEVRON, true);
        defaults.put(KEY_HEADER_MENU_CUSTOM_ENABLED, true);
        defaults.put(KEY_ADD_TRANSPARENCY_SLIDER, true);
        defaults.put(KEY_ADD_APP_INFO_BTN, true);
        defaults.put(KEY_ADD_EXTRA_WINDOW_TOOLS, true);
        defaults.put(KEY_DEFAULT_WINDOW_OPACITY, 100);
        defaults.put(KEY_WINDOW_CORNER_RADIUS, -1);

        defaults.put(KEY_FORCE_RESIZABLE_WINDOWS, true);
        defaults.put(KEY_FULLSCREEN_INSIDE, true);
        return Collections.unmodifiableMap(defaults);
    }

    public static SharedPreferences getLocalPrefs(Context context) {
        Context storageCtx = context;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Context dpCtx = context.createDeviceProtectedStorageContext();
                if (dpCtx != null) {
                    storageCtx = dpCtx;
                }
            }
        } catch (Throwable ignored) {}
        try {
            @SuppressWarnings("deprecation")
            SharedPreferences sp = storageCtx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
            return sp;
        } catch (Throwable t) {
            return storageCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static void addChangeListener(Runnable listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void ensureInitialized(Context context) {
        if (context == null) return;
        if (!receiverRegistered) {
            synchronized (ModConfig.class) {
                if (!receiverRegistered) {
                    try {
                        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                        IntentFilter filter = new IntentFilter(ACTION_PREFS_CHANGED);
                        BroadcastReceiver receiver = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context ctx, Intent intent) {
                                boolean changed;
                                if (intent != null && intent.getExtras() != null) {
                                    changed = applyBundleToCache(intent.getExtras());
                                } else {
                                    changed = reloadFromProviderWithDiff(ctx);
                                }
                                if (changed) {
                                    notifyListeners();
                                }
                            }
                        };
                        if (Build.VERSION.SDK_INT >= 33) {
                            appCtx.registerReceiver(receiver, filter, 2 /* RECEIVER_EXPORTED */);
                        } else {
                            appCtx.registerReceiver(receiver, filter);
                        }
                        try {
                            appCtx.getContentResolver().registerContentObserver(
                                    PREFS_URI,
                                    true,
                                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                                        @Override
                                        public void onChange(boolean selfChange) {
                                            if (reloadFromProviderWithDiff(appCtx)) {
                                                notifyListeners();
                                            }
                                        }
                                    });
                        } catch (Throwable ignored) {}
                        receiverRegistered = true;
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to register prefs receiver: " + t);
                    }
                }
            }
            reload(context);
        } else {
            reloadIfStale(context, 4000L);
        }
    }

    public static void reloadIfStale(Context context, long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (now - lastReloadTime > maxAgeMs) {
            reload(context);
        }
    }

    public static void reload(Context context) {
        lastReloadTime = System.currentTimeMillis();
        boolean loaded = false;
        if (context != null) {
            loaded = reloadFromProvider(context);
        }
        if (!loaded) {
            reloadFromXSharedPrefs();
        }
    }

    private static boolean replaceCacheWithDefaultsAnd(Map<String, Object> overrides) {
        Map<String, Object> next = new HashMap<>(DEFAULTS);
        if (overrides != null) {
            for (Map.Entry<String, Object> e : overrides.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    next.put(e.getKey(), e.getValue());
                }
            }
        }
        if (CACHE.equals(next)) {
            return false;
        }
        CACHE.putAll(next);
        CACHE.keySet().retainAll(next.keySet());
        return true;
    }

    private static boolean reloadFromProvider(Context context) {
        return reloadFromProviderInternal(context, new boolean[1]);
    }

    private static boolean reloadFromProviderWithDiff(Context context) {
        boolean[] changed = new boolean[1];
        reloadFromProviderInternal(context, changed);
        return changed[0];
    }

    private static boolean reloadFromProviderInternal(Context context, boolean[] outChanged) {
        if (context == null) return false;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(PREFS_URI, null, null, null, null);
            if (cursor != null) {
                Map<String, Object> loaded = new HashMap<>();
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    String type = cursor.getString(1);
                    String val = cursor.getString(2);
                    if (key == null || type == null || val == null) continue;
                    switch (type) {
                        case "boolean":
                            loaded.put(key, Boolean.parseBoolean(val));
                            break;
                        case "int":
                            loaded.put(key, Integer.parseInt(val));
                            break;
                        case "float":
                            loaded.put(key, Float.parseFloat(val));
                            break;
                        default:
                            loaded.put(key, val);
                            break;
                    }
                }
                outChanged[0] = replaceCacheWithDefaultsAnd(loaded);
                lastReloadTime = System.currentTimeMillis();
                return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static void reloadFromXSharedPrefs() {
        try {
            boolean readable = false;
            if (xPrefs == null) {
                xPrefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
                readable = xPrefs.makeWorldReadable();
            } else {
                if (xPrefs.hasFileChanged()) {
                    xPrefs.reload();
                }
                readable = true;
            }
            try {
                Object file = xPrefs.getClass().getMethod("getFile").invoke(xPrefs);
                if (file instanceof java.io.File) {
                    readable = ((java.io.File) file).canRead();
                }
            } catch (Throwable ignored) {}
            Map<String, ?> all = xPrefs.getAll();
            if (readable || (all != null && !all.isEmpty())) {
                Map<String, Object> loaded = new HashMap<>();
                if (all != null) {
                    for (Map.Entry<String, ?> entry : all.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            loaded.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                replaceCacheWithDefaultsAnd(loaded);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean applyBundleToCache(Bundle bundle) {
        if (bundle == null) return false;
        lastReloadTime = System.currentTimeMillis();
        if (bundle.getBoolean(EXTRA_RESET, false)) {
            Map<String, Object> overrides = new HashMap<>();
            for (String key : bundle.keySet()) {
                if (EXTRA_RESET.equals(key)) continue;
                Object v = bundle.get(key);
                if (v != null) {
                    overrides.put(key, v);
                }
            }
            return replaceCacheWithDefaultsAnd(overrides);
        }
        boolean changed = false;
        for (String key : bundle.keySet()) {
            Object v = bundle.get(key);
            if (v != null && !v.equals(CACHE.get(key))) {
                CACHE.put(key, v);
                changed = true;
            }
        }
        return changed;
    }

    public static void resetToDefaults(Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        try {
            prefs.edit().clear().commit();
            replaceCacheWithDefaultsAnd(null);
            try {
                context.getContentResolver().delete(PREFS_URI, null, null);
            } catch (Throwable ignored) {}
            Intent intent = new Intent(ACTION_PREFS_CHANGED);
            intent.putExtra(EXTRA_RESET, true);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "resetToDefaults error: " + t);
        }
    }

    public static void broadcastChanges(Context context, SharedPreferences prefs) {
        if (context == null || prefs == null) return;
        try {
            ContentValues cv = new ContentValues();
            Intent intent = new Intent(ACTION_PREFS_CHANGED);
            Bundle extras = new Bundle();
            Map<String, ?> all = prefs.getAll();
            if (all != null) {
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    if (v instanceof Boolean) {
                        extras.putBoolean(k, (Boolean) v);
                        cv.put(k, (Boolean) v);
                        CACHE.put(k, v);
                    } else if (v instanceof Integer) {
                        extras.putInt(k, (Integer) v);
                        cv.put(k, (Integer) v);
                        CACHE.put(k, v);
                    } else if (v instanceof Float) {
                        extras.putFloat(k, (Float) v);
                        cv.put(k, (Float) v);
                        CACHE.put(k, v);
                    } else if (v instanceof String) {
                        extras.putString(k, (String) v);
                        cv.put(k, (String) v);
                        CACHE.put(k, v);
                    }
                }
            }
            try {
                context.getContentResolver().update(PREFS_URI, cv, null, null);
            } catch (Throwable ignored) {}
            intent.putExtras(extras);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "broadcastChanges error: " + t);
        }
    }

    private static void notifyListeners() {
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (Throwable ignored) {}
        }
    }

    public static boolean getBoolean(Context context, String key, boolean defVal) {
        if (context != null) ensureInitialized(context);
        Object val = CACHE.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defVal;
    }

    public static int getInt(Context context, String key, int defVal) {
        if (context != null) ensureInitialized(context);
        Object val = CACHE.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Throwable ignored) {}
        }
        return defVal;
    }

    public static boolean isModuleEnabled(Context context) {
        return getBoolean(context, KEY_MODULE_ENABLED, true);
    }

    private static boolean restartRegistered;

    /** Settings cannot use root. The hooked host process exits itself when asked. */
    public static void registerRestartReceiver(Context context, final String host) {
        if (context == null || host == null || restartRegistered) {
            return;
        }
        try {
            final Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            IntentFilter filter = new IntentFilter(ACTION_RESTART_HOST);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || !host.equals(intent.getStringExtra(EXTRA_RESTART_TARGET))) {
                        return;
                    }
                    Process.killProcess(Process.myPid());
                }
            };
            app.registerReceiver(receiver, filter, PERMISSION_RESTART_HOST,
                    new Handler(Looper.getMainLooper()), 2);
            restartRegistered = true;
        } catch (Throwable error) {
            Log.w(TAG, "restart receiver: " + error);
        }
    }
}
