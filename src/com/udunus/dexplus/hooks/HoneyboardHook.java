package com.udunus.dexplus.hooks;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.provider.Settings;

import com.udunus.dexplus.util.ModConfig;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hides Samsung Honeyboard's physical keyboard prediction/toolbar bar while Internal DeX
 * desktop effects are active. The soft-keyboard toolbar is left untouched.
 */
public final class HoneyboardHook {
    private static final String PHYSICAL_TOOLBAR = "SETTINGS_PHYSICAL_KEYBOARD_TOOLBAR";
    private static final String EFFECTS_KEY = "externaldex_effects";
    private static boolean loggedToolbar = false;

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
    }

    private static boolean shouldHideToolbar() {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                Object app = thread.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    context = (Context) app;
                }
            }
            if (context == null) return false;
            if (!ModConfig.getBoolean(context, ModConfig.KEY_HIDE_PHYSICAL_KB_TOOLBAR, true)) {
                return false;
            }
            return Settings.Global.getInt(context.getContentResolver(), EFFECTS_KEY, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }
}
