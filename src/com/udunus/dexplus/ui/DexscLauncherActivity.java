package com.udunus.dexplus.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.widget.Toast;

import com.udunus.dexplus.util.ModConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Opens .dexsc DeX+ shortcut files from anywhere on the device (e.g., My Files,
 * folder browsers, or external file managers) and launches the target app or shortcut.
 */
public class DexscLauncherActivity extends Activity {

    private static final String ACTION_VIEW_DEEP_SHORTCUT_CMD = "launch_deep_shortcut";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        finish();
    }

    private void handleIntent(Intent incoming) {
        if (incoming == null || incoming.getData() == null) {
            return;
        }
        Uri uri = incoming.getData();
        ParsedDexsc sc = readDexscFromUri(uri);
        if (sc == null || (sc.component == null && sc.customIntent == null)) {
            Toast.makeText(this, "Invalid or empty DeX+ shortcut (.dexsc)", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean freeform = true;
        try {
            SharedPreferences sp = ModConfig.getLocalPrefs(this);
            if (sp != null) {
                freeform = sp.getBoolean(ModConfig.KEY_DESKTOP_FREEFORM_LAUNCH, true);
            }
        } catch (Throwable ignored) {}

        launchParsedShortcut(sc, freeform);
    }

    private ParsedDexsc readDexscFromUri(Uri uri) {
        if (uri == null) return null;
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
        } catch (Throwable ignored) {}

        if (is == null && "file".equalsIgnoreCase(uri.getScheme()) && !TextUtils.isEmpty(uri.getPath())) {
            try {
                File f = new File(uri.getPath());
                if (f.exists() && f.isFile()) {
                    is = new FileInputStream(f);
                }
            } catch (Throwable ignored) {}
        }
        if (is == null) return null;

        try (InputStream stream = is;
             BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String name = null;
            String type = null;
            String compStr = null;
            String pkg = null;
            String intentStr = null;

            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                if ("icon".equals(k)) continue;
                String v = line.substring(eq + 1).trim();
                if ("name".equals(k)) name = v;
                else if ("type".equals(k)) type = v;
                else if ("component".equals(k)) compStr = v;
                else if ("package".equals(k)) pkg = v;
                else if ("intent".equals(k)) intentStr = v;
            }

            ComponentName cn = !TextUtils.isEmpty(compStr) ? ComponentName.unflattenFromString(compStr) : null;
            Intent parsedIntent = null;
            if (!TextUtils.isEmpty(intentStr)) {
                parsedIntent = Intent.parseUri(intentStr, Intent.URI_INTENT_SCHEME);
            }
            if (("app".equals(type) || cn != null) && cn != null) {
                return new ParsedDexsc(name, cn, null, cn.getPackageName());
            }
            if (parsedIntent != null) {
                return new ParsedDexsc(name, null, parsedIntent, pkg);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void launchParsedShortcut(ParsedDexsc sc, boolean freeform) {
        if (sc.customIntent != null && sc.customIntent.hasCategory("com.android.launcher3.DEEP_SHORTCUT")) {
            String shortcutId = sc.customIntent.getStringExtra("shortcut_id");
            if (TextUtils.isEmpty(shortcutId)) {
                shortcutId = sc.customIntent.getStringExtra("android.intent.extra.shortcut.ID");
            }
            String scPkg = sc.customIntent.getPackage();
            if (TextUtils.isEmpty(scPkg) && sc.customIntent.getComponent() != null) {
                scPkg = sc.customIntent.getComponent().getPackageName();
            }
            if (TextUtils.isEmpty(scPkg)) {
                scPkg = sc.pkg;
            }
            if (!TextUtils.isEmpty(scPkg) && !TextUtils.isEmpty(shortcutId)) {
                int userId = sc.customIntent.getIntExtra("userid", Process.myUserHandle().hashCode());
                Intent cmd = new Intent(ModConfig.ACTION_DESKTOP_COMMAND);
                cmd.setPackage("com.sec.android.app.launcher");
                cmd.putExtra("command", ACTION_VIEW_DEEP_SHORTCUT_CMD);
                cmd.putExtra("pkg", scPkg);
                cmd.putExtra("shortcut_id", shortcutId);
                if (sc.customIntent.hasExtra("userid")) {
                    cmd.putExtra("userid", userId);
                }
                cmd.putExtra("freeform", freeform);
                sendBroadcast(cmd);
                return;
            }
        }

        ActivityOptions opts = makeLaunchOptions(freeform);
        Intent launchIntent = null;
        if (sc.component != null) {
            launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.setComponent(sc.component);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        } else if (sc.customIntent != null) {
            launchIntent = new Intent(sc.customIntent);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        if (launchIntent == null) {
            Toast.makeText(this, "Unable to launch shortcut", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            startActivity(launchIntent, opts.toBundle());
            return;
        } catch (Throwable ignored) {}

        try {
            startActivity(launchIntent);
            return;
        } catch (Throwable ignored) {}

        if (!TextUtils.isEmpty(sc.pkg)) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage(sc.pkg);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback, opts.toBundle());
                    return;
                }
            } catch (Throwable ignored) {}
        }

        String label = !TextUtils.isEmpty(sc.name) ? sc.name : "shortcut";
        Toast.makeText(this, "Unable to launch " + label, Toast.LENGTH_SHORT).show();
    }

    private static ActivityOptions makeLaunchOptions(boolean freeform) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        if (freeform) {
            try {
                opts.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(opts, 5);
            } catch (Throwable ignored) {}
        }
        return opts;
    }

    private static final class ParsedDexsc {
        final String name;
        final ComponentName component;
        final Intent customIntent;
        final String pkg;

        ParsedDexsc(String name, ComponentName component, Intent customIntent, String pkg) {
            this.name = name;
            this.component = component;
            this.customIntent = customIntent;
            this.pkg = pkg;
        }
    }
}
