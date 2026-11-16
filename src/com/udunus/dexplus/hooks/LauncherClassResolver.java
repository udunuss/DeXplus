package com.udunus.dexplus.hooks;

import android.content.pm.ApplicationInfo;

import com.udunus.dexplus.util.ModConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;

/**
 * Discovers obfuscated One UI Home class and method names at runtime by scanning the APK's DEX
 * class definitions using stable unobfuscated superclasses, interfaces, and field types.
 *
 * <p>Prevents R8/ProGuard renaming across different One UI 8.5 devices or builds from breaking
 * hooks in {@link LauncherDexTaskbarHook} and {@link LauncherCustomDesktopHook}.
 */
public final class LauncherClassResolver {
    private static final String TAG = ModConfig.TAG;

    private static final String T_OBJECT = "Ljava/lang/Object;";
    private static final String T_HOME_VIEW =
            "Lcom/honeyspace/ui/honeypots/homescreen/presentation/HomeView;";
    private static final String T_ON_CONTEXT_CLICK =
            "Landroid/view/View$OnContextClickListener;";
    private static final String T_KEY_GESTURE_HANDLER =
            "Lcom/android/systemui/shared/launcher/InputManagerCompat$KeyGestureEventHandler;";
    private static final String T_TOUCH_CONTROLLER =
            "Lcom/honeyspace/common/interfaces/TouchController;";
    private static final String T_BASE_RESIZABLE_FRAME =
            "Lcom/honeyspace/common/resize/BaseResizableFrame;";
    private static final String T_HONEY_POT =
            "Lcom/honeyspace/common/entity/HoneyPot;";
    private static final String T_TASKBAR_CONFIG_HANDLER =
            "Lcom/honeyspace/ui/common/taskbar/TaskbarConfigurationHandler;";
    private static final String T_SUSPEND_LAMBDA =
            "Lkotlin/coroutines/jvm/internal/SuspendLambda;";
    private static final String T_VIEW_DATA_BINDING =
            "Landroidx/databinding/ViewDataBinding;";
    private static final String T_STATUS_PANEL_VM =
            "Lcom/honeyspace/ui/honeypots/taskbar/viewmodel/StatusPanelViewModel;";
    private static final String T_TASKBAR_VM =
            "Lcom/honeyspace/ui/honeypots/taskbar/viewmodel/TaskbarViewModel;";
    private static final String T_LOG_TAG =
            "Lcom/honeyspace/common/log/LogTag;";
    private static final String T_NOTIFICATION_DATA =
            "Lcom/honeyspace/ui/honeypots/dexpanel/notification/domain/model/NotificationData;";
    private static final String T_COPY_ON_WRITE_LIST =
            "Ljava/util/concurrent/CopyOnWriteArrayList;";
    private static final String T_HONEY_SHARED_DATA =
            "Lcom/honeyspace/sdk/HoneySharedData;";
    private static final String T_DESKTOP_SYSUI =
            "Lcom/honeyspace/common/dex/DesktopSysUi;";
    private static final String T_BATTERY_METER =
            "Lcom/honeyspace/ui/honeypots/taskbar/presentation/battery/BatteryMeterView;";
    private static final String T_DRAWABLE =
            "Landroid/graphics/drawable/Drawable;";

    public static final class ResolvedClasses {
        public String homeContextClickClass;
        public String keyGestureHandlerClass;
        public final Set<String> touchControllerClasses = new LinkedHashSet<String>();
        public String taskbarHoneyPotClass;
        public String taskbarPackagePrefix;
        public final List<String> taskbarPackageClasses = new ArrayList<String>();
        public final List<String> taskbarCoroutineClasses = new ArrayList<String>();
        public String statusPanelBindingClass;
        public String taskbarBindingClass;
        public String notificationRepoClass;
        public String notificationCountMethod;
        public String notificationPublishMethod;
        public String dexPanelManagerClass;
        public String dexPanelEnumClass;
        public final List<String> dexPanelToggleMethods = new ArrayList<String>();
        public String batteryDrawableClass;
        public String batteryCoroutineClass;
        public final Set<String> dexUiPackagePrefixes = new HashSet<String>();
        public volatile String[] dexUiPrefixArray;

        public String[] getDexUiPrefixArray() {
            String[] cached = dexUiPrefixArray;
            if (cached == null && !dexUiPackagePrefixes.isEmpty()) {
                cached = dexUiPackagePrefixes.toArray(new String[dexUiPackagePrefixes.size()]);
                dexUiPrefixArray = cached;
            }
            return cached;
        }
    }

    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE_NAME = "dexplus_class_cache_v1.json";

    private static volatile ResolvedClasses sCached;
    private static volatile String sCachedApkPath;

    private LauncherClassResolver() {}

    public static ResolvedClasses resolve(ApplicationInfo appInfo) {
        String apkPath = appInfo != null ? appInfo.sourceDir : null;
        return resolve(apkPath, appInfo);
    }

    public static ResolvedClasses resolve(String apkPath) {
        return resolve(apkPath, null);
    }

    private static ResolvedClasses resolve(String apkPath, ApplicationInfo appInfo) {
        ResolvedClasses existing = sCached;
        if (existing != null && (apkPath == null || apkPath.equals(sCachedApkPath))) {
            return existing;
        }
        synchronized (LauncherClassResolver.class) {
            if (sCached != null && (apkPath == null || apkPath.equals(sCachedApkPath))) {
                return sCached;
            }
            File cacheFile = resolveCacheFile(appInfo);
            if (apkPath != null && !apkPath.isEmpty() && cacheFile != null) {
                long cacheStartNs = System.nanoTime();
                ResolvedClasses disk = loadFromDiskCache(cacheFile, apkPath);
                if (disk != null) {
                    long elapsedMs = (System.nanoTime() - cacheStartNs) / 1_000_000L;
                    XposedBridge.log(TAG + ": launcher class cache hit in " + elapsedMs + "ms"
                            + " (taskbarPkg=" + disk.taskbarPackagePrefix
                            + ", panelMgr=" + disk.dexPanelManagerClass + ")");
                    sCachedApkPath = apkPath;
                    sCached = disk;
                    return disk;
                }
            }
            ResolvedClasses result = new ResolvedClasses();
            if (apkPath != null && !apkPath.isEmpty()) {
                long startNs = System.nanoTime();
                try {
                    scanApk(apkPath, result);
                    result.getDexUiPrefixArray();
                    if (cacheFile != null && !result.taskbarPackageClasses.isEmpty()) {
                        saveToDiskCache(cacheFile, apkPath, result);
                    }
                } catch (Throwable error) {
                    XposedBridge.log(TAG + ": launcher DEX scan failed: " + error);
                }
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                XposedBridge.log(TAG + ": launcher DEX resolver finished in " + elapsedMs + "ms"
                        + " (taskbarPkg=" + result.taskbarPackagePrefix
                        + ", statusBinding=" + result.statusPanelBindingClass
                        + ", panelMgr=" + result.dexPanelManagerClass + ")");
            }
            sCachedApkPath = apkPath;
            sCached = result;
            return result;
        }
    }

    private static File resolveCacheFile(ApplicationInfo appInfo) {
        if (appInfo == null) return null;
        String[] candidates = new String[] { appInfo.dataDir, appInfo.deviceProtectedDataDir };
        for (int i = 0; i < candidates.length; i++) {
            String base = candidates[i];
            if (base == null || base.isEmpty()) continue;
            File baseDir = new File(base);
            if (!baseDir.exists() || !baseDir.canWrite()) continue;
            File cacheDir = new File(baseDir, "cache");
            if (!cacheDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }
            if (cacheDir.isDirectory() && cacheDir.canWrite()) {
                return new File(cacheDir, CACHE_FILE_NAME);
            }
        }
        return null;
    }

    private static ResolvedClasses loadFromDiskCache(File cacheFile, String apkPath) {
        if (cacheFile == null || !cacheFile.isFile()) return null;
        try {
            File apkFile = new File(apkPath);
            long mtime = apkFile.lastModified();
            long len = apkFile.length();
            if (mtime <= 0L || len <= 0L) return null;

            long size = cacheFile.length();
            if (size <= 0L || size > 1_048_576L) return null;
            byte[] buf = new byte[(int) size];
            int pos = 0;
            FileInputStream in = new FileInputStream(cacheFile);
            try {
                while (pos < buf.length) {
                    int n = in.read(buf, pos, buf.length - pos);
                    if (n < 0) break;
                    pos += n;
                }
            } finally {
                in.close();
            }
            JSONObject json = new JSONObject(new String(buf, 0, pos, StandardCharsets.UTF_8));
            if (json.optInt("version", -1) != CACHE_VERSION) return null;
            if (!apkPath.equals(json.optString("apkPath", ""))) return null;
            if (json.optLong("apkMtime", -1L) != mtime || json.optLong("apkSize", -1L) != len) {
                return null;
            }
            ResolvedClasses out = new ResolvedClasses();
            out.homeContextClickClass = emptyToNull(json.optString("homeContextClickClass", null));
            out.keyGestureHandlerClass = emptyToNull(json.optString("keyGestureHandlerClass", null));
            readStringCollection(json.optJSONArray("touchControllerClasses"), out.touchControllerClasses);
            out.taskbarHoneyPotClass = emptyToNull(json.optString("taskbarHoneyPotClass", null));
            out.taskbarPackagePrefix = emptyToNull(json.optString("taskbarPackagePrefix", null));
            readStringCollection(json.optJSONArray("taskbarPackageClasses"), out.taskbarPackageClasses);
            readStringCollection(json.optJSONArray("taskbarCoroutineClasses"), out.taskbarCoroutineClasses);
            out.statusPanelBindingClass = emptyToNull(json.optString("statusPanelBindingClass", null));
            out.taskbarBindingClass = emptyToNull(json.optString("taskbarBindingClass", null));
            out.notificationRepoClass = emptyToNull(json.optString("notificationRepoClass", null));
            out.notificationCountMethod = emptyToNull(json.optString("notificationCountMethod", null));
            out.notificationPublishMethod = emptyToNull(json.optString("notificationPublishMethod", null));
            out.dexPanelManagerClass = emptyToNull(json.optString("dexPanelManagerClass", null));
            out.dexPanelEnumClass = emptyToNull(json.optString("dexPanelEnumClass", null));
            readStringCollection(json.optJSONArray("dexPanelToggleMethods"), out.dexPanelToggleMethods);
            out.batteryDrawableClass = emptyToNull(json.optString("batteryDrawableClass", null));
            out.batteryCoroutineClass = emptyToNull(json.optString("batteryCoroutineClass", null));
            readStringCollection(json.optJSONArray("dexUiPackagePrefixes"), out.dexUiPackagePrefixes);
            out.getDexUiPrefixArray();
            if (out.taskbarPackageClasses.isEmpty()) return null;
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void saveToDiskCache(File cacheFile, String apkPath, ResolvedClasses res) {
        try {
            File apkFile = new File(apkPath);
            long mtime = apkFile.lastModified();
            long len = apkFile.length();
            if (mtime <= 0L || len <= 0L) return;

            JSONObject json = new JSONObject();
            json.put("version", CACHE_VERSION);
            json.put("apkPath", apkPath);
            json.put("apkMtime", mtime);
            json.put("apkSize", len);
            json.put("homeContextClickClass", res.homeContextClickClass);
            json.put("keyGestureHandlerClass", res.keyGestureHandlerClass);
            json.put("touchControllerClasses", new JSONArray(res.touchControllerClasses));
            json.put("taskbarHoneyPotClass", res.taskbarHoneyPotClass);
            json.put("taskbarPackagePrefix", res.taskbarPackagePrefix);
            json.put("taskbarPackageClasses", new JSONArray(res.taskbarPackageClasses));
            json.put("taskbarCoroutineClasses", new JSONArray(res.taskbarCoroutineClasses));
            json.put("statusPanelBindingClass", res.statusPanelBindingClass);
            json.put("taskbarBindingClass", res.taskbarBindingClass);
            json.put("notificationRepoClass", res.notificationRepoClass);
            json.put("notificationCountMethod", res.notificationCountMethod);
            json.put("notificationPublishMethod", res.notificationPublishMethod);
            json.put("dexPanelManagerClass", res.dexPanelManagerClass);
            json.put("dexPanelEnumClass", res.dexPanelEnumClass);
            json.put("dexPanelToggleMethods", new JSONArray(res.dexPanelToggleMethods));
            json.put("batteryDrawableClass", res.batteryDrawableClass);
            json.put("batteryCoroutineClass", res.batteryCoroutineClass);
            json.put("dexUiPackagePrefixes", new JSONArray(res.dexUiPackagePrefixes));

            File tmp = new File(cacheFile.getParentFile(), CACHE_FILE_NAME + ".tmp");
            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(cacheFile)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
    }

    private static void readStringCollection(JSONArray arr, java.util.Collection<String> dest) {
        if (arr == null || dest == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null && !s.isEmpty()) {
                dest.add(s);
            }
        }
    }

    public static ResolvedClasses getCached() {
        return sCached;
    }

    private static void scanApk(String apkPath, ResolvedClasses out) throws Exception {
        List<DexImage> dexes = new ArrayList<DexImage>();
        ZipFile zip = new ZipFile(apkPath);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            List<ZipEntry> dexEntries = new ArrayList<ZipEntry>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex") && name.indexOf('/') < 0) {
                    dexEntries.add(entry);
                }
            }
            for (ZipEntry entry : dexEntries) {
                byte[] bytes = readEntry(zip, entry);
                DexImage img = DexImage.parse(bytes);
                if (img != null) {
                    dexes.add(img);
                }
            }
        } finally {
            zip.close();
        }

        Set<String> touchBases = new LinkedHashSet<String>();
        Set<String> enumDescs = new HashSet<String>();
        Set<String> statusBindingBases = new LinkedHashSet<String>();
        Set<String> taskbarBindingBases = new LinkedHashSet<String>();
        Set<String> batteryFieldCandidates = new LinkedHashSet<String>();
        List<DexClassCandidate> dexPanelCandidates = new ArrayList<DexClassCandidate>();
        String taskbarHoneyPotDesc = null;
        String taskbarPkgDescPrefix = null;

        // Pass 1: inspect classes matching stable unobfuscated superclasses or interfaces.
        for (DexImage dex : dexes) {
            for (int i = 0; i < dex.classDefsSize; i++) {
                int base = dex.classDefsOff + i * 32;
                int classIdx = dex.buf.getInt(base);
                int superIdx = dex.buf.getInt(base + 8);
                int ifacesOff = dex.buf.getInt(base + 12);
                int classDataOff = dex.buf.getInt(base + 24);

                String clsDesc = dex.getType(classIdx);
                String superDesc = superIdx == -1 ? null : dex.getType(superIdx);
                if ("Ljava/lang/Enum;".equals(superDesc)) {
                    enumDescs.add(clsDesc);
                }

                boolean checkIfaces = ifacesOff != 0;
                boolean implContextClick = false;
                boolean implKeyGesture = false;
                boolean implTouchCtrl = false;
                boolean implTaskbarCfg = false;
                boolean implLogTag = false;
                if (checkIfaces) {
                    int cnt = dex.buf.getInt(ifacesOff);
                    for (int k = 0; k < cnt; k++) {
                        int tid = dex.buf.getShort(ifacesOff + 4 + k * 2) & 0xFFFF;
                        String iface = dex.getType(tid);
                        if (T_ON_CONTEXT_CLICK.equals(iface)) {
                            implContextClick = true;
                        } else if (T_KEY_GESTURE_HANDLER.equals(iface)) {
                            implKeyGesture = true;
                        } else if (T_TOUCH_CONTROLLER.equals(iface)) {
                            implTouchCtrl = true;
                        } else if (T_TASKBAR_CONFIG_HANDLER.equals(iface)) {
                            implTaskbarCfg = true;
                        } else if (T_LOG_TAG.equals(iface)) {
                            implLogTag = true;
                        }
                    }
                }

                if (implKeyGesture && out.keyGestureHandlerClass == null) {
                    out.keyGestureHandlerClass = descriptorToClassName(clsDesc);
                }
                if (implTouchCtrl && !T_BASE_RESIZABLE_FRAME.equals(clsDesc)) {
                    touchBases.add(clsDesc);
                    out.touchControllerClasses.add(descriptorToClassName(clsDesc));
                }
                if (implTaskbarCfg && T_HONEY_POT.equals(superDesc) && out.taskbarHoneyPotClass == null) {
                    taskbarHoneyPotDesc = clsDesc;
                    out.taskbarHoneyPotClass = descriptorToClassName(clsDesc);
                    int slash = clsDesc.lastIndexOf('/');
                    if (slash > 1) {
                        taskbarPkgDescPrefix = clsDesc.substring(0, slash + 1);
                        out.taskbarPackagePrefix = packagePrefixFromDescriptor(clsDesc);
                        addPackagePrefix(out, clsDesc);
                    }
                }

                if (classDataOff == 0) {
                    continue;
                }

                boolean isViewBindingBase = T_VIEW_DATA_BINDING.equals(superDesc);
                boolean isBatteryMeter = T_BATTERY_METER.equals(clsDesc);
                boolean isSuspendLambda = T_SUSPEND_LAMBDA.equals(superDesc);

                if (!(implContextClick || (implLogTag && !clsDesc.startsWith("Lcom/honeyspace/"))
                        || isViewBindingBase || isBatteryMeter || isSuspendLambda)) {
                    continue;
                }

                ClassMembers members = dex.readMembers(classDataOff);
                if (implContextClick && out.homeContextClickClass == null
                        && members.hasFieldType(T_HOME_VIEW)) {
                    out.homeContextClickClass = descriptorToClassName(clsDesc);
                }

                if (isViewBindingBase) {
                    if (members.hasFieldType(T_STATUS_PANEL_VM)) {
                        statusBindingBases.add(clsDesc);
                        if (members.hasMethod("_internalCallbackOnClick")) {
                            out.statusPanelBindingClass = descriptorToClassName(clsDesc);
                            addPackagePrefix(out, clsDesc);
                        }
                    }
                    if (members.hasFieldType(T_TASKBAR_VM)) {
                        taskbarBindingBases.add(clsDesc);
                        if (members.hasMethod("_internalCallbackOnClick")) {
                            out.taskbarBindingClass = descriptorToClassName(clsDesc);
                            addPackagePrefix(out, clsDesc);
                        }
                    }
                }

                if (implLogTag && !clsDesc.startsWith("Lcom/honeyspace/")) {
                    if (out.notificationRepoClass == null
                            && members.hasFieldType(T_NOTIFICATION_DATA)
                            && members.hasFieldType(T_COPY_ON_WRITE_LIST)
                            && members.hasFieldType(T_HONEY_SHARED_DATA)) {
                        out.notificationRepoClass = descriptorToClassName(clsDesc);
                        addPackagePrefix(out, clsDesc);
                        for (MethodSig m : members.methods) {
                            if (m.params.length == 1 && "V".equals(m.ret)) {
                                if ("Ljava/util/List;".equals(m.params[0]) && out.notificationCountMethod == null) {
                                    out.notificationCountMethod = m.name;
                                } else if (!m.params[0].startsWith("Ljava/")
                                        && !m.params[0].startsWith("Landroid/")
                                        && !m.params[0].startsWith("Lcom/honeyspace/")
                                        && !m.params[0].startsWith("Lkotlinx/")
                                        && out.notificationPublishMethod == null) {
                                    out.notificationPublishMethod = m.name;
                                }
                            }
                        }
                    }
                    if (members.hasFieldType(T_DESKTOP_SYSUI)
                            && members.hasFieldType("Ljava/util/Map;")
                            && members.hasFieldType("Ljava/util/LinkedHashMap;")) {
                        dexPanelCandidates.add(new DexClassCandidate(clsDesc, members));
                    }
                }

                if (isBatteryMeter) {
                    for (String fType : members.fieldTypes) {
                        if (fType.startsWith("L") && !fType.startsWith("Landroid/")
                                && !fType.startsWith("Ljava/") && !fType.startsWith("Lcom/honeyspace/")) {
                            batteryFieldCandidates.add(fType);
                        }
                    }
                }

                if (isSuspendLambda) {
                    if (out.batteryCoroutineClass == null && members.hasFieldType(T_BATTERY_METER)) {
                        out.batteryCoroutineClass = descriptorToClassName(clsDesc);
                        addPackagePrefix(out, clsDesc);
                    }
                    if (members.hasFieldType(T_STATUS_PANEL_VM) || members.hasFieldType(T_TASKBAR_VM)) {
                        addPackagePrefix(out, clsDesc);
                    }
                }
            }
        }

        // Pass 2: resolve 2-hop subclasses, taskbar package members, battery drawable, and taskbar coroutines.
        for (DexImage dex : dexes) {
            for (int i = 0; i < dex.classDefsSize; i++) {
                int base = dex.classDefsOff + i * 32;
                int classIdx = dex.buf.getInt(base);
                int superIdx = dex.buf.getInt(base + 8);
                int classDataOff = dex.buf.getInt(base + 24);

                String clsDesc = dex.getType(classIdx);
                String superDesc = superIdx == -1 ? null : dex.getType(superIdx);

                if (taskbarPkgDescPrefix != null && clsDesc.startsWith(taskbarPkgDescPrefix)) {
                    String clsName = descriptorToClassName(clsDesc);
                    out.taskbarPackageClasses.add(clsName);
                    if (T_SUSPEND_LAMBDA.equals(superDesc) && classDataOff != 0 && taskbarHoneyPotDesc != null) {
                        ClassMembers members = dex.readMembers(classDataOff);
                        if (members.hasFieldType(taskbarHoneyPotDesc)) {
                            out.taskbarCoroutineClasses.add(clsName);
                        }
                    }
                }

                if (superDesc != null && touchBases.contains(superDesc)) {
                    out.touchControllerClasses.add(descriptorToClassName(clsDesc));
                }

                if (out.statusPanelBindingClass == null && superDesc != null && statusBindingBases.contains(superDesc)
                        && classDataOff != 0) {
                    ClassMembers members = dex.readMembers(classDataOff);
                    if (members.hasMethod("_internalCallbackOnClick")) {
                        out.statusPanelBindingClass = descriptorToClassName(clsDesc);
                        addPackagePrefix(out, clsDesc);
                    }
                }

                if (out.taskbarBindingClass == null && superDesc != null && taskbarBindingBases.contains(superDesc)
                        && classDataOff != 0) {
                    ClassMembers members = dex.readMembers(classDataOff);
                    if (members.hasMethod("_internalCallbackOnClick")) {
                        out.taskbarBindingClass = descriptorToClassName(clsDesc);
                        addPackagePrefix(out, clsDesc);
                    }
                }

                if (out.batteryDrawableClass == null && T_DRAWABLE.equals(superDesc)
                        && batteryFieldCandidates.contains(clsDesc)) {
                    out.batteryDrawableClass = descriptorToClassName(clsDesc);
                    addPackagePrefix(out, clsDesc);
                }
            }
        }

        // Resolve dexPanelManagerClass and its Enum parameter type after enumDescs is complete.
        for (DexClassCandidate cand : dexPanelCandidates) {
            String foundEnumDesc = null;
            for (MethodSig m : cand.members.methods) {
                if (m.params.length == 1 && "V".equals(m.ret) && enumDescs.contains(m.params[0])) {
                    foundEnumDesc = m.params[0];
                    break;
                }
            }
            if (foundEnumDesc != null || out.dexPanelManagerClass == null) {
                out.dexPanelManagerClass = descriptorToClassName(cand.clsDesc);
                addPackagePrefix(out, cand.clsDesc);
                if (foundEnumDesc != null) {
                    out.dexPanelEnumClass = descriptorToClassName(foundEnumDesc);
                    for (MethodSig m : cand.members.methods) {
                        if (m.params.length == 1 && "V".equals(m.ret) && foundEnumDesc.equals(m.params[0])) {
                            out.dexPanelToggleMethods.add(m.name);
                        }
                    }
                    break;
                }
            }
        }

        // Pass 3 (optional): collect TouchController subclasses 1 level deeper if any.
        if (!touchBases.isEmpty()) {
            Set<String> extraDescs = new HashSet<String>();
            for (String c : out.touchControllerClasses) {
                extraDescs.add("L" + c.replace('.', '/') + ";");
            }
            for (DexImage dex : dexes) {
                for (int i = 0; i < dex.classDefsSize; i++) {
                    int base = dex.classDefsOff + i * 32;
                    int classIdx = dex.buf.getInt(base);
                    int superIdx = dex.buf.getInt(base + 8);
                    if (superIdx == -1) continue;
                    String superDesc = dex.getType(superIdx);
                    if (extraDescs.contains(superDesc)) {
                        out.touchControllerClasses.add(descriptorToClassName(dex.getType(classIdx)));
                    }
                }
            }
        }
    }

    private static void addPackagePrefix(ResolvedClasses out, String descriptor) {
        String pkg = packagePrefixFromDescriptor(descriptor);
        if (pkg != null && !pkg.isEmpty()) {
            out.dexUiPackagePrefixes.add(pkg);
        }
    }

    private static String packagePrefixFromDescriptor(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("L")) return null;
        int slash = descriptor.lastIndexOf('/');
        if (slash <= 1) return null;
        return descriptor.substring(1, slash).replace('/', '.') + ".";
    }

    private static String descriptorToClassName(String descriptor) {
        if (descriptor == null) return null;
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return descriptor;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws Exception {
        long size = entry.getSize();
        InputStream in = zip.getInputStream(entry);
        try {
            if (size > 0 && size < 32_000_000) {
                int len = (int) size;
                byte[] exact = new byte[len];
                int pos = 0;
                while (pos < len) {
                    int n = in.read(exact, pos, len - pos);
                    if (n < 0) break;
                    pos += n;
                }
                if (pos == len) {
                    return exact;
                }
                byte[] trimmed = new byte[pos];
                System.arraycopy(exact, 0, trimmed, 0, pos);
                return trimmed;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static final class DexClassCandidate {
        final String clsDesc;
        final ClassMembers members;

        DexClassCandidate(String clsDesc, ClassMembers members) {
            this.clsDesc = clsDesc;
            this.members = members;
        }
    }

    private static final class MethodSig {
        final String name;
        final String[] params;
        final String ret;

        MethodSig(String name, String[] params, String ret) {
            this.name = name;
            this.params = params;
            this.ret = ret;
        }
    }

    private static final class ClassMembers {
        final Set<String> fieldTypes = new LinkedHashSet<String>();
        final List<MethodSig> methods = new ArrayList<MethodSig>();

        boolean hasFieldType(String typeDesc) {
            return fieldTypes.contains(typeDesc);
        }

        boolean hasMethod(String name) {
            for (MethodSig m : methods) {
                if (name.equals(m.name)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class DexImage {
        final byte[] data;
        final ByteBuffer buf;
        final int stringIdsOff;
        final int typeIdsOff;
        final int protoIdsOff;
        final int fieldIdsOff;
        final int methodIdsOff;
        final int classDefsSize;
        final int classDefsOff;
        final String[] typeCache;

        private DexImage(byte[] data, ByteBuffer buf,
                         int stringIdsOff, int typeIdsSize, int typeIdsOff,
                         int protoIdsOff, int fieldIdsOff, int methodIdsOff,
                         int classDefsSize, int classDefsOff) {
            this.data = data;
            this.buf = buf;
            this.stringIdsOff = stringIdsOff;
            this.typeIdsOff = typeIdsOff;
            this.protoIdsOff = protoIdsOff;
            this.fieldIdsOff = fieldIdsOff;
            this.methodIdsOff = methodIdsOff;
            this.classDefsSize = classDefsSize;
            this.classDefsOff = classDefsOff;
            this.typeCache = new String[typeIdsSize];
        }

        static DexImage parse(byte[] bytes) {
            if (bytes == null || bytes.length < 0x70) return null;
            if (bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n') {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int stringIdsOff = buf.getInt(0x3C);
            int typeIdsSize = buf.getInt(0x40);
            int typeIdsOff = buf.getInt(0x44);
            int protoIdsOff = buf.getInt(0x4C);
            int fieldIdsOff = buf.getInt(0x54);
            int methodIdsOff = buf.getInt(0x5C);
            int classDefsSize = buf.getInt(0x60);
            int classDefsOff = buf.getInt(0x64);
            return new DexImage(bytes, buf, stringIdsOff, typeIdsSize, typeIdsOff,
                    protoIdsOff, fieldIdsOff, methodIdsOff, classDefsSize, classDefsOff);
        }

        String getString(int sid) {
            int off = buf.getInt(stringIdsOff + sid * 4);
            int p = off;
            // Skip MUTF-8 char length ULEB128
            while ((data[p] & 0x80) != 0) {
                p++;
            }
            p++;
            int end = p;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            return new String(data, p, end - p, StandardCharsets.UTF_8);
        }

        String getType(int tid) {
            String cached = typeCache[tid];
            if (cached != null) return cached;
            int sid = buf.getInt(typeIdsOff + tid * 4);
            String s = getString(sid);
            typeCache[tid] = s;
            return s;
        }

        ClassMembers readMembers(int classDataOff) {
            ClassMembers out = new ClassMembers();
            int[] pos = new int[] { classDataOff };
            int staticFields = readUleb(pos);
            int instanceFields = readUleb(pos);
            int directMethods = readUleb(pos);
            int virtualMethods = readUleb(pos);

            int fIdx = 0;
            for (int i = 0; i < staticFields; i++) {
                fIdx += readUleb(pos);
                readUleb(pos);
                int fTypeIdx = buf.getShort(fieldIdsOff + fIdx * 8 + 2) & 0xFFFF;
                out.fieldTypes.add(getType(fTypeIdx));
            }
            fIdx = 0;
            for (int i = 0; i < instanceFields; i++) {
                fIdx += readUleb(pos);
                readUleb(pos);
                int fTypeIdx = buf.getShort(fieldIdsOff + fIdx * 8 + 2) & 0xFFFF;
                out.fieldTypes.add(getType(fTypeIdx));
            }
            int mIdx = 0;
            for (int i = 0; i < directMethods; i++) {
                mIdx += readUleb(pos);
                readUleb(pos);
                readUleb(pos);
                addMethod(out, mIdx);
            }
            mIdx = 0;
            for (int i = 0; i < virtualMethods; i++) {
                mIdx += readUleb(pos);
                readUleb(pos);
                readUleb(pos);
                addMethod(out, mIdx);
            }
            return out;
        }

        private void addMethod(ClassMembers out, int mIdx) {
            int base = methodIdsOff + mIdx * 8;
            int protoIdx = buf.getShort(base + 2) & 0xFFFF;
            int nameSid = buf.getInt(base + 4);
            String name = getString(nameSid);
            int pBase = protoIdsOff + protoIdx * 12;
            int retTid = buf.getInt(pBase + 4);
            int paramsOff = buf.getInt(pBase + 8);
            String[] params;
            if (paramsOff == 0) {
                params = new String[0];
            } else {
                int cnt = buf.getInt(paramsOff);
                params = new String[cnt];
                for (int k = 0; k < cnt; k++) {
                    params[k] = getType(buf.getShort(paramsOff + 4 + k * 2) & 0xFFFF);
                }
            }
            out.methods.add(new MethodSig(name, params, getType(retTid)));
        }

        private int readUleb(int[] pos) {
            int result = 0;
            int shift = 0;
            int p = pos[0];
            while (true) {
                int b = data[p++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            pos[0] = p;
            return result;
        }
    }
}
