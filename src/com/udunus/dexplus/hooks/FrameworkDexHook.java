package com.udunus.dexplus.hooks;

import android.os.IBinder;

import com.udunus.dexplus.util.ModConfig;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FrameworkDexHook {
    private static final int FULLSCREEN_MODE_REQUEST_ENTER = 1;
    private static volatile boolean loggedInside;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        hookVideoFullscreen(cl);

        try {
            final Class<?> activityRecordClass = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityRecord", cl);
            if (activityRecordClass != null) {
                XposedBridge.hookAllMethods(activityRecordClass, "isResizeable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });

                XposedBridge.hookAllMethods(activityRecordClass, "supportsFreeform", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });
            }

            final Class<?> taskClass = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.Task", cl);
            if (taskClass != null) {
                XposedBridge.hookAllMethods(taskClass, "isResizeable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });

                XposedBridge.hookAllMethods(taskClass, "supportsFreeformInDisplayArea", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.getResult()) && forceResizableEnabled()) {
                            param.setResult(true);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": FrameworkDexHook error: " + t);
        }
    }

    /**
     * Video fullscreen asks the system to change the task to fullscreen windowing mode.
     * That removes it from the desk and closes DeX. Drop that request while the task
     * stays freeform. Do not report success: that makes the browser expand the page
     * instead of leaving the video overlay in the window. The browser already shows
     * that overlay itself.
     */
    private static void hookVideoFullscreen(ClassLoader cl) {
        try {
            final Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityClientController", cl);
            final Class<?> activityRecord = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityRecord", cl);
            if (controller == null || activityRecord == null) {
                return;
            }
            XposedBridge.hookAllMethods(controller, "requestMultiwindowFullscreenLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!insideWindow() || param.args == null || param.args.length < 2) {
                                return;
                            }
                            if (!(param.args[0] instanceof IBinder)) {
                                return;
                            }
                            int request = param.args[1] instanceof Integer
                                    ? ((Integer) param.args[1]).intValue() : -1;
                            if (request != FULLSCREEN_MODE_REQUEST_ENTER) {
                                return;
                            }
                            try {
                                if (!isDesktopTask(activityRecord, (IBinder) param.args[0])) {
                                    return;
                                }
                            } catch (Throwable error) {
                                XposedBridge.log(ModConfig.TAG + ": desktop fullscreen check failed: " + error);
                                return;
                            }
                            param.setResult(null);
                            if (!loggedInside) {
                                loggedInside = true;
                                XposedBridge.log(ModConfig.TAG
                                        + ": kept video fullscreen inside the window");
                            }
                        }
                    });
            XposedBridge.hookAllMethods(controller, "executeMultiWindowFullscreenRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!insideWindow() || param.args == null || param.args.length < 2
                                    || param.args[1] == null) {
                                return;
                            }
                            Object task = param.args[1];
                            if (bool(task, "isDeskRootTask") || bool(task, "isDefaultDisplayDesktop")
                                    || bool(task, "inFreeformWindowingMode")) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(ModConfig.TAG + ": could not keep video fullscreen in the window: " + error);
        }
    }

    private static boolean forceResizableEnabled() {
        try {
            ModConfig.reloadIfStale(null, 2000L);
        } catch (Throwable ignored) {}
        return ModConfig.isModuleEnabled(null)
                && ModConfig.getBoolean(null, ModConfig.KEY_FORCE_RESIZABLE_WINDOWS, true);
    }

    private static boolean insideWindow() {
        try {
            ModConfig.reloadIfStale(null, 2000L);
        } catch (Throwable ignored) {}
        return ModConfig.isModuleEnabled(null)
                && ModConfig.getBoolean(null, ModConfig.KEY_FULLSCREEN_INSIDE, true);
    }

    private static boolean isDesktopTask(Class<?> activityRecord, IBinder token) {
        Object record = XposedHelpers.callStaticMethod(activityRecord, "forTokenLocked", token);
        if (record == null) {
            return false;
        }
        Object root = XposedHelpers.callMethod(record, "getRootTask");
        if (root == null) {
            return false;
        }
        return bool(root, "isDeskRootTask") || bool(root, "isDefaultDisplayDesktop");
    }

    private static boolean bool(Object target, String method) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(target, method));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
