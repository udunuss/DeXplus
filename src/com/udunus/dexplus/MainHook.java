package com.udunus.dexplus;

import com.udunus.dexplus.hooks.FrameworkDexHook;
import com.udunus.dexplus.hooks.HoneyboardHook;
import com.udunus.dexplus.hooks.LauncherCustomDesktopHook;
import com.udunus.dexplus.hooks.LauncherDexTaskbarHook;
import com.udunus.dexplus.hooks.SystemUIWindowDecorHook;
import com.udunus.dexplus.util.ModConfig;

import java.lang.reflect.Field;
import java.util.Collection;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) return;

        if (ModConfig.MODULE_PACKAGE.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        "com.udunus.dexplus.ui.SettingsActivity",
                        lpparam.classLoader,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                );
            } catch (Throwable ignored) {}
            return;
        }

        // Skip this module when the external DeX module is already loaded in the same process.
        if (isExternalDexModuleLoaded()) {
            XposedBridge.log(ModConfig.TAG + ": com.minsos.externaldex is active in "
                    + lpparam.packageName + "; skipping com.udunus.dexplus hooks.");
            return;
        }

        switch (lpparam.packageName) {
            case "com.sec.android.app.launcher": {
                XposedBridge.log(ModConfig.TAG + ": Hooking One UI Home (com.sec.android.app.launcher)");
                LauncherDexTaskbarHook.install(lpparam);
                LauncherCustomDesktopHook.init(lpparam);
                break;
            }
            case "com.android.systemui": {
                SystemUIWindowDecorHook.install(lpparam);
                break;
            }
            case "com.samsung.android.honeyboard": {
                XposedBridge.log(ModConfig.TAG + ": Hooking Samsung Honeyboard (com.samsung.android.honeyboard)");
                HoneyboardHook.install(lpparam);
                break;
            }
            case "android": {
                XposedBridge.log(ModConfig.TAG + ": Hooking System Framework (android)");
                FrameworkDexHook.init(lpparam);
                break;
            }
        }
    }

    private static boolean isExternalDexModuleLoaded() {
        try {
            Field f = XposedBridge.class.getDeclaredField("sLoadedPackageCallbacks");
            f.setAccessible(true);
            Object callbacksObj = f.get(null);
            if (callbacksObj instanceof Collection) {
                for (Object wrapper : (Collection<?>) callbacksObj) {
                    if (wrapper == null) continue;
                    try {
                        Field instField = wrapper.getClass().getDeclaredField("instance");
                        instField.setAccessible(true);
                        Object inst = instField.get(wrapper);
                        if (inst != null && inst.getClass().getName().startsWith("com.minsos.externaldex.")) {
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
