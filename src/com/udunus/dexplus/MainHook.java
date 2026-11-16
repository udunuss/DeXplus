package com.udunus.dexplus;

import com.udunus.dexplus.hooks.FrameworkDexHook;
import com.udunus.dexplus.hooks.HoneyboardHook;
import com.udunus.dexplus.hooks.LauncherCustomDesktopHook;
import com.udunus.dexplus.hooks.LauncherDexTaskbarHook;
import com.udunus.dexplus.hooks.SystemUIPowerMenuHook;
import com.udunus.dexplus.hooks.SystemUIWindowDecorHook;
import com.udunus.dexplus.util.ModConfig;

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

        switch (lpparam.packageName) {
            case "com.sec.android.app.launcher": {
                XposedBridge.log(ModConfig.TAG + ": Hooking One UI Home (com.sec.android.app.launcher)");
                LauncherDexTaskbarHook.install(lpparam);
                LauncherCustomDesktopHook.init(lpparam);
                break;
            }
            case "com.android.systemui": {
                SystemUIWindowDecorHook.install(lpparam);
                SystemUIPowerMenuHook.install(lpparam);
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
}
