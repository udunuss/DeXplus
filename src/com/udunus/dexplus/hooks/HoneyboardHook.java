package com.udunus.dexplus.hooks;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.udunus.dexplus.util.InputDeviceHelper;
import com.udunus.dexplus.util.ModConfig;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hides Samsung Honeyboard's physical keyboard prediction/toolbar bar while Internal DeX
 * desktop effects are active, and prevents mice with keyboard HID descriptors from hiding the
 * soft keyboard when no real physical keyboard is connected.
 */
public final class HoneyboardHook {
    private static final String PHYSICAL_TOOLBAR = "SETTINGS_PHYSICAL_KEYBOARD_TOOLBAR";
    private static final String EFFECTS_KEY = "externaldex_effects";
    private static volatile boolean effectsWatching = false;
    private static volatile boolean cachedEffectsActive = false;
    private static boolean loggedToolbar = false;
    private static boolean loggedMouseFix = false;

    private HoneyboardHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> prefs = Class.forName("android.app.SharedPreferencesImpl", false, lpparam.classLoader);
            Method getBoolean = prefs.getDeclaredMethod("getBoolean", String.class, boolean.class);
            XposedBridge.hookMethod(getBoolean, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !PHYSICAL_TOOLBAR.equals(param.args[0])) {
                        return;
                    }
                    if (!shouldHideToolbar()) {
                        return;
                    }
                    if (!Boolean.FALSE.equals(param.getResult())) {
                        param.setResult(Boolean.FALSE);
                        if (!loggedToolbar) {
                            loggedToolbar = true;
                            XposedBridge.log(ModConfig.TAG + ": physical keyboard toolbar hidden");
                        }
                    }
                }
            });
            XposedBridge.log(ModConfig.TAG + ": physical toolbar hook 1");
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": could not hook physical toolbar: " + t);
        }

        hookMouseAccessoryKeyboard(lpparam.classLoader);
    }

    private static void hookMouseAccessoryKeyboard(ClassLoader classLoader) {
        try {
            Class<?> immClass = XposedHelpers.findClassIfExists(
                    "android.view.inputmethod.InputMethodManager", classLoader);
            if (immClass == null) {
                return;
            }
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    boolean isBool = result instanceof Boolean;
                    boolean isInt = result instanceof Integer;
                    if (isBool && !Boolean.TRUE.equals(result)) {
                        return;
                    }
                    if (isInt && ((Integer) result).intValue() == 0) {
                        return;
                    }
                    if (!isBool && !isInt) {
                        return;
                    }
                    Context context = resolveAppContext();
                    if (context == null || !ModConfig.isModuleEnabled(context)) {
                        return;
                    }
                    if (InputDeviceHelper.hasOnlyMouseAlphabeticKeyboards(context, null)) {
                        param.setResult(isBool ? Boolean.FALSE : Integer.valueOf(0));
                        if (!loggedMouseFix) {
                            loggedMouseFix = true;
                            XposedBridge.log(ModConfig.TAG
                                    + ": suppressed accessory-keyboard flag for mouse-only setup");
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(immClass, "semIsAccessoryKeyboard", hook);
            XposedBridge.hookAllMethods(immClass, "isAccessoryKeyboardState", hook);
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": could not hook semIsAccessoryKeyboard: " + t);
        }
    }

    private static void ensureEffectsObserver(final Context context) {
        if (effectsWatching || context == null) return;
        synchronized (HoneyboardHook.class) {
            if (effectsWatching) return;
            final Context appCtx = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            cachedEffectsActive = readEffects(appCtx);
            try {
                appCtx.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(EFFECTS_KEY),
                        false,
                        new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                cachedEffectsActive = readEffects(appCtx);
                            }
                        });
                effectsWatching = true;
            } catch (Throwable ignored) {}
        }
    }

    private static boolean readEffects(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), EFFECTS_KEY, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context resolveAppContext() {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                Object app = thread.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    context = (Context) app;
                }
            }
            return context;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldHideToolbar() {
        try {
            Context context = resolveAppContext();
            if (context == null) return false;
            if (!ModConfig.isModuleEnabled(context)
                    || !ModConfig.getBoolean(context, ModConfig.KEY_HIDE_PHYSICAL_KB_TOOLBAR, true)) {
                return false;
            }
            ensureEffectsObserver(context);
            return effectsWatching ? cachedEffectsActive : readEffects(context);
        } catch (Throwable t) {
            return false;
        }
    }
}
