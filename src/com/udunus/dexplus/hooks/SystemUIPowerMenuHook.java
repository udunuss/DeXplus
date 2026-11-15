package com.udunus.dexplus.hooks;

import android.app.AndroidAppHelper;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.udunus.dexplus.util.ModConfig;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Adds optional Advanced Power Menu actions directly inside Samsung's GlobalActions menu
 * (Soft Reboot, Restart System UI, Recovery, Download Mode, Bootloader, and Safe Mode).
 */
public final class SystemUIPowerMenuHook {
    private static final String ACTION_PREFIX = "dexplus_";
    private static final String ACT_SOFT_REBOOT = "dexplus_soft_reboot";
    private static final String ACT_RESTART_SYSUI = "dexplus_restart_sysui";
    private static final String ACT_RECOVERY = "dexplus_recovery";
    private static final String ACT_DOWNLOAD = "dexplus_download";
    private static final String ACT_BOOTLOADER = "dexplus_bootloader";
    private static final String ACT_SAFEMODE = "dexplus_safemode";

    private static volatile boolean installed;

    private static final class ActionSpec {
        final String id;
        final String command;
        final String label;
        final String confirmDesc;
        final int circleColor;
        final int viewIndex;

        ActionSpec(String id, String command, String label, String confirmDesc, int circleColor, int viewIndex) {
            this.id = id;
            this.command = command;
            this.label = label;
            this.confirmDesc = confirmDesc;
            this.circleColor = circleColor;
            this.viewIndex = viewIndex;
        }
    }

    private static final ActionSpec[] ACTION_SPECS = new ActionSpec[]{
            new ActionSpec(ACT_SOFT_REBOOT, "soft_reboot", "Soft reboot",
                    "Tap again to soft reboot Android.", Color.rgb(30, 136, 229), 10),
            new ActionSpec(ACT_RESTART_SYSUI, "restart_sysui", "Restart System UI",
                    "Tap again to restart System UI.", Color.rgb(92, 107, 192), 11),
            new ActionSpec(ACT_RECOVERY, "recovery", "Recovery",
                    "Tap again to reboot to Recovery mode.", Color.rgb(245, 124, 0), 12),
            new ActionSpec(ACT_DOWNLOAD, "download", "Download mode",
                    "Tap again to reboot to Download mode.", Color.rgb(0, 151, 167), 13),
            new ActionSpec(ACT_BOOTLOADER, "bootloader", "Bootloader",
                    "Tap again to reboot to Bootloader.", Color.rgb(126, 87, 194), 14),
            new ActionSpec(ACT_SAFEMODE, "safemode", "Safe mode",
                    "Tap again to reboot in Safe mode.", Color.rgb(46, 125, 50), 15),
    };

    private SystemUIPowerMenuHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) {
            return;
        }
        installed = true;
        final ClassLoader cl = lpparam != null && lpparam.classLoader != null
                ? lpparam.classLoader
                : SystemUIPowerMenuHook.class.getClassLoader();

        hookPresenter(cl);
        hookItemView(cl);
    }

    private static void hookPresenter(final ClassLoader cl) {
        try {
            final Class<?> presenterCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.SamsungGlobalActionsPresenter", cl);
            final Class<?> actionVmCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.viewmodel.ActionViewModel", cl);
            final Class<?> actionInfoCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.viewmodel.ActionInfo", cl);
            final Class<?> viewTypeCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.viewmodel.ViewType", cl);

            if (presenterCls == null || actionVmCls == null || actionInfoCls == null || viewTypeCls == null) {
                XposedBridge.log(ModConfig.TAG + ": GlobalActions presenter classes not found; skipping power menu hook.");
                return;
            }

            XposedBridge.hookAllMethods(presenterCls, "createActions", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    injectAdvancedActions(param.thisObject, actionVmCls, actionInfoCls, viewTypeCls);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": SystemUIPowerMenuHook presenter error: " + t);
        }
    }

    private static void hookItemView(final ClassLoader cl) {
        try {
            final Class<?> itemViewCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.view.GlobalActionsContentItemView", cl);
            final Class<?> actionVmCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.viewmodel.ActionViewModel", cl);
            final Class<?> resourceFactoryCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.view.ResourceFactory", cl);
            final Class<?> resourceTypeCls = XposedHelpers.findClassIfExists(
                    "com.samsung.android.globalactions.presentation.view.ResourceType", cl);

            if (itemViewCls == null || actionVmCls == null || resourceFactoryCls == null || resourceTypeCls == null) {
                return;
            }

            final Field vmField = findFieldByType(itemViewCls, actionVmCls);
            final Field rfField = findFieldByType(itemViewCls, resourceFactoryCls);
            if (vmField == null || rfField == null) {
                XposedBridge.log(ModConfig.TAG + ": GlobalActionsContentItemView fields missing; custom icons disabled.");
                return;
            }

            XposedBridge.hookAllMethods(itemViewCls, "setViewAttrs", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof View)) {
                        return;
                    }
                    try {
                        Object vm = vmField.get(param.thisObject);
                        if (vm == null) return;
                        Object info = XposedHelpers.callMethod(vm, "getActionInfo");
                        if (info == null) return;
                        String name = (String) XposedHelpers.callMethod(info, "getName");
                        if (name == null || !name.startsWith(ACTION_PREFIX)) {
                            return;
                        }
                        ActionSpec spec = findSpec(name);
                        if (spec == null) return;

                        Object rf = rfField.get(param.thisObject);
                        if (rf == null) return;
                        @SuppressWarnings("unchecked")
                        Enum<?> idIconEnum = Enum.valueOf((Class<? extends Enum>) resourceTypeCls, "ID_ICON");
                        int iconViewId = (Integer) XposedHelpers.callMethod(rf, "get", idIconEnum);
                        View rootView = (View) param.args[0];
                        View iconRaw = rootView.findViewById(iconViewId);
                        if (iconRaw instanceof ImageView) {
                            ImageView iconView = (ImageView) iconRaw;
                            Drawable custom = buildActionIcon(iconView.getContext(), spec);
                            if (custom != null) {
                                iconView.setImageDrawable(custom);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": SystemUIPowerMenuHook itemView error: " + t);
        }
    }

    private static void injectAdvancedActions(
            final Object presenter,
            final Class<?> actionVmCls,
            final Class<?> actionInfoCls,
            final Class<?> viewTypeCls) {
        try {
            final Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return;
            }
            ModConfig.ensureInitialized(ctx);
            if (!ModConfig.isModuleEnabled(ctx) || !ModConfig.isAdvancedPowerMenuEnabled(ctx)) {
                return;
            }

            int fallbackIconResId = android.R.drawable.ic_lock_power_off;
            Object existingList = findPresenterActionsList(presenter);
            if (existingList instanceof List<?>) {
                for (Object item : (List<?>) existingList) {
                    if (item == null) continue;
                    try {
                        Object info = XposedHelpers.callMethod(item, "getActionInfo");
                        if (info == null) continue;
                        String name = (String) XposedHelpers.callMethod(info, "getName");
                        if (name != null && name.startsWith(ACTION_PREFIX)) {
                            return; // already injected
                        }
                        if ("restart".equals(name)) {
                            Integer iconId = (Integer) XposedHelpers.callMethod(info, "getIcon");
                            if (iconId != null && iconId != 0) {
                                fallbackIconResId = iconId;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            Object viewTypeCenter = resolveCenterViewType(viewTypeCls);
            if (viewTypeCenter == null) {
                return;
            }

            for (ActionSpec spec : ACTION_SPECS) {
                Object vm = createActionViewModel(
                        ctx, presenter, actionVmCls, actionInfoCls, viewTypeCenter, spec, fallbackIconResId);
                if (vm != null) {
                    XposedHelpers.callMethod(presenter, "addAction", vm);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": injectAdvancedActions error: " + t);
        }
    }

    private static Object createActionViewModel(
            final Context ctx,
            final Object presenter,
            final Class<?> actionVmCls,
            final Class<?> actionInfoCls,
            final Object viewType,
            final ActionSpec spec,
            final int fallbackIconResId) throws Throwable {

        final Object actionInfo = actionInfoCls.getDeclaredConstructor().newInstance();
        XposedHelpers.callMethod(actionInfo, "setName", spec.id);
        XposedHelpers.callMethod(actionInfo, "setLabel", spec.label);
        XposedHelpers.callMethod(actionInfo, "setDescription", spec.confirmDesc);
        XposedHelpers.callMethod(actionInfo, "setIcon", fallbackIconResId);
        XposedHelpers.callMethod(actionInfo, "setViewIndex", spec.viewIndex);
        XposedHelpers.callMethod(actionInfo, "setViewType", viewType);

        final Object[] holder = new Object[1];
        InvocationHandler handler = new InvocationHandler() {
            private Object currentInfo = actionInfo;
            private Object toggleState = null;
            private BitmapDrawable bitmapIcon = null;
            private String text = "";

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String m = method.getName();
                switch (m) {
                    case "equals":
                        return args != null && args.length > 0 && proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "DexPlusActionViewModel(" + spec.id + ")";
                    case "getActionInfo":
                        return currentInfo;
                    case "setActionInfo":
                        if (args != null && args.length > 0) currentInfo = args[0];
                        return null;
                    case "onPress":
                    case "onPressSecureConfirm":
                        handleActionPress(ctx, presenter, holder[0], spec);
                        return null;
                    case "isAvailableShow":
                        return Boolean.TRUE;
                    case "showBeforeProvisioning":
                        return Boolean.FALSE;
                    case "getIcon":
                        return bitmapIcon;
                    case "setIcon":
                        if (args != null && args.length > 0 && args[0] instanceof BitmapDrawable) {
                            bitmapIcon = (BitmapDrawable) args[0];
                        }
                        return null;
                    case "getText":
                        return text;
                    case "setText":
                        if (args != null && args.length > 0 && args[0] instanceof String) {
                            text = (String) args[0];
                        }
                        return null;
                    case "getState":
                        return toggleState;
                    case "setState":
                        if (args != null && args.length > 0) toggleState = args[0];
                        return null;
                    default:
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == float.class) return 0f;
                        return null;
                }
            }
        };

        Object proxy = Proxy.newProxyInstance(actionVmCls.getClassLoader(), new Class<?>[]{actionVmCls}, handler);
        holder[0] = proxy;
        return proxy;
    }

    private static void handleActionPress(
            final Context ctx,
            final Object presenter,
            final Object selfProxy,
            final ActionSpec spec) {
        try {
            if (isSecureLocked(ctx, presenter)) {
                Toast.makeText(ctx, "Unlock device first to use " + spec.label, Toast.LENGTH_SHORT).show();
                return;
            }
            boolean confirming = Boolean.TRUE.equals(XposedHelpers.callMethod(presenter, "isActionConfirming"));
            if (!confirming) {
                XposedHelpers.callMethod(presenter, "confirmAction", selfProxy);
                return;
            }

            try {
                XposedHelpers.callMethod(presenter, "dismissDialog", false);
            } catch (Throwable ignored) {}

            if ("restart_sysui".equals(spec.command)) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        XposedBridge.log(ModConfig.TAG + ": restarting SystemUI via Advanced Power Menu");
                        Process.killProcess(Process.myPid());
                    }
                }, 150L);
                return;
            }

            Intent intent = new Intent(ModConfig.ACTION_POWER_ACTION);
            intent.setPackage("android");
            intent.putExtra(ModConfig.EXTRA_POWER_ACTION, spec.command);
            ctx.sendBroadcast(intent, "android.permission.DEVICE_POWER");
        } catch (Throwable t) {
            XposedBridge.log(ModConfig.TAG + ": handleActionPress(" + spec.id + ") error: " + t);
        }
    }

    private static boolean isSecureLocked(Context ctx, Object presenter) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked() && km.isKeyguardSecure()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Object resolveCenterViewType(Class<?> viewTypeCls) {
        @SuppressWarnings("unchecked")
        Class<? extends Enum> enumCls = (Class<? extends Enum>) viewTypeCls;
        for (String candidate : new String[]{"CENTER_ICON_3P_VIEW", "CENTER_ICON_CUSTOM_VIEW", "CENTER_ICON_1P_VIEW"}) {
            try {
                return Enum.valueOf(enumCls, candidate);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object findPresenterActionsList(Object presenter) {
        if (presenter == null) return null;
        for (Field f : presenter.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(presenter);
                    if (val instanceof List<?>) {
                        return val;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> owner, Class<?> fieldType) {
        Class<?> cur = owner;
        while (cur != null && cur != Object.class) {
            for (Field f : cur.getDeclaredFields()) {
                if (fieldType.equals(f.getType()) || fieldType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static ActionSpec findSpec(String name) {
        for (ActionSpec spec : ACTION_SPECS) {
            if (spec.id.equals(name)) {
                return spec;
            }
        }
        return null;
    }

    private static Drawable buildActionIcon(Context ctx, ActionSpec spec) {
        try {
            Resources res = ctx != null ? ctx.getResources() : Resources.getSystem();
            float density = res != null ? res.getDisplayMetrics().density : 2f;
            int sizePx = Math.max(72, Math.round(72f * density));

            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            float cx = sizePx * 0.5f;
            float cy = sizePx * 0.5f;
            float r = sizePx * 0.45f;

            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(spec.circleColor);
            bg.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, r, bg);

            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(Math.max(3f, sizePx * 0.068f));

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);

            float g = r * 0.48f; // half glyph radius
            switch (spec.id) {
                case ACT_SOFT_REBOOT:
                    drawRefreshArcGlyph(c, cx, cy, g, stroke, fill, true);
                    break;
                case ACT_RESTART_SYSUI:
                    drawLayersRefreshGlyph(c, cx, cy, g, stroke);
                    break;
                case ACT_RECOVERY:
                    drawWrenchGlyph(c, cx, cy, g, stroke, fill);
                    break;
                case ACT_DOWNLOAD:
                    drawDownloadGlyph(c, cx, cy, g, stroke, fill);
                    break;
                case ACT_BOOTLOADER:
                    drawTerminalGlyph(c, cx, cy, g, stroke);
                    break;
                case ACT_SAFEMODE:
                    drawShieldCheckGlyph(c, cx, cy, g, stroke);
                    break;
                default:
                    drawRefreshArcGlyph(c, cx, cy, g, stroke, fill, false);
                    break;
            }
            return new BitmapDrawable(res, bmp);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void drawRefreshArcGlyph(
            Canvas c, float cx, float cy, float g, Paint stroke, Paint fill, boolean withBolt) {
        RectF oval = new RectF(cx - g, cy - g, cx + g, cy + g);
        c.drawArc(oval, -50f, 290f, false, stroke);
        // Arrowhead at ~ -50 deg
        double rad = Math.toRadians(-50.0);
        float ax = cx + (float) (g * Math.cos(rad));
        float ay = cy + (float) (g * Math.sin(rad));
        float hs = g * 0.36f;
        Path head = new Path();
        head.moveTo(ax + hs * 0.4f, ay - hs * 0.5f);
        head.lineTo(ax - hs * 0.7f, ay - hs * 0.3f);
        head.lineTo(ax + hs * 0.25f, ay + hs * 0.55f);
        head.close();
        c.drawPath(head, fill);
        if (withBolt) {
            Path bolt = new Path();
            bolt.moveTo(cx + g * 0.08f, cy - g * 0.42f);
            bolt.lineTo(cx - g * 0.24f, cy + g * 0.05f);
            bolt.lineTo(cx + g * 0.02f, cy + g * 0.05f);
            bolt.lineTo(cx - g * 0.08f, cy + g * 0.44f);
            bolt.lineTo(cx + g * 0.26f, cy - g * 0.05f);
            bolt.lineTo(cx - g * 0.02f, cy - g * 0.05f);
            bolt.close();
            c.drawPath(bolt, fill);
        }
    }

    private static void drawLayersRefreshGlyph(Canvas c, float cx, float cy, float g, Paint stroke) {
        RectF box = new RectF(cx - g * 0.85f, cy - g * 0.75f, cx + g * 0.85f, cy + g * 0.75f);
        float rad = g * 0.22f;
        c.drawRoundRect(box, rad, rad, stroke);
        // Top status bar divider & center dot/arrows
        c.drawLine(box.left + g * 0.1f, box.top + g * 0.42f, box.right - g * 0.1f, box.top + g * 0.42f, stroke);
        RectF inner = new RectF(cx - g * 0.32f, cy - g * 0.06f, cx + g * 0.32f, cy + g * 0.52f);
        c.drawArc(inner, -30f, 250f, false, stroke);
    }

    private static void drawWrenchGlyph(Canvas c, float cx, float cy, float g, Paint stroke, Paint fill) {
        c.drawLine(cx - g * 0.65f, cy + g * 0.65f, cx + g * 0.35f, cy - g * 0.35f, stroke);
        c.drawCircle(cx + g * 0.45f, cy - g * 0.45f, g * 0.38f, stroke);
        c.drawCircle(cx - g * 0.62f, cy + g * 0.62f, g * 0.14f, fill);
    }

    private static void drawDownloadGlyph(Canvas c, float cx, float cy, float g, Paint stroke, Paint fill) {
        c.drawLine(cx, cy - g * 0.8f, cx, cy + g * 0.35f, stroke);
        Path arrow = new Path();
        arrow.moveTo(cx - g * 0.45f, cy + g * 0.02f);
        arrow.lineTo(cx, cy + g * 0.5f);
        arrow.lineTo(cx + g * 0.45f, cy + g * 0.02f);
        c.drawPath(arrow, stroke);
        Path tray = new Path();
        tray.moveTo(cx - g * 0.78f, cy + g * 0.48f);
        tray.lineTo(cx - g * 0.78f, cy + g * 0.78f);
        tray.lineTo(cx + g * 0.78f, cy + g * 0.78f);
        tray.lineTo(cx + g * 0.78f, cy + g * 0.48f);
        c.drawPath(tray, stroke);
    }

    private static void drawTerminalGlyph(Canvas c, float cx, float cy, float g, Paint stroke) {
        RectF box = new RectF(cx - g * 0.85f, cy - g * 0.72f, cx + g * 0.85f, cy + g * 0.72f);
        c.drawRoundRect(box, g * 0.2f, g * 0.2f, stroke);
        Path chev = new Path();
        chev.moveTo(cx - g * 0.42f, cy - g * 0.25f);
        chev.lineTo(cx - g * 0.1f, cy);
        chev.lineTo(cx - g * 0.42f, cy + g * 0.25f);
        c.drawPath(chev, stroke);
        c.drawLine(cx + g * 0.06f, cy + g * 0.26f, cx + g * 0.46f, cy + g * 0.26f, stroke);
    }

    private static void drawShieldCheckGlyph(Canvas c, float cx, float cy, float g, Paint stroke) {
        Path shield = new Path();
        shield.moveTo(cx, cy - g * 0.85f);
        shield.lineTo(cx + g * 0.72f, cy - g * 0.5f);
        shield.lineTo(cx + g * 0.65f, cy + g * 0.2f);
        shield.quadTo(cx + g * 0.45f, cy + g * 0.68f, cx, cy + g * 0.88f);
        shield.quadTo(cx - g * 0.45f, cy + g * 0.68f, cx - g * 0.65f, cy + g * 0.2f);
        shield.lineTo(cx - g * 0.72f, cy - g * 0.5f);
        shield.close();
        c.drawPath(shield, stroke);

        Path check = new Path();
        check.moveTo(cx - g * 0.3f, cy + g * 0.02f);
        check.lineTo(cx - g * 0.06f, cy + g * 0.28f);
        check.lineTo(cx + g * 0.34f, cy - g * 0.22f);
        c.drawPath(check, stroke);
    }
}
