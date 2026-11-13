package com.udunus.dexplus.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import com.udunus.dexplus.util.DesktopFileProvider;

import java.io.File;

/**
 * Translucent bridge activity that serves a staged APK from DesktopFileProvider
 * and launches Android's package installer cleanly with temporary URI read permission.
 */
public class ApkInstallActivity extends Activity {
    public static final String EXTRA_STAGED_NAME = "staged_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String stagedName = getIntent() != null ? getIntent().getStringExtra(EXTRA_STAGED_NAME) : null;
        if (TextUtils.isEmpty(stagedName)) {
            finish();
            return;
        }

        String safeName = DesktopFileProvider.sanitizeName(stagedName);
        File staged = new File(DesktopFileProvider.getStageDir(this), safeName);
        if (!staged.exists() || !staged.isFile()) {
            Toast.makeText(this, "APK file not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri apkUri = DesktopFileProvider.buildUri(safeName);
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(viewIntent);
        } catch (ActivityNotFoundException firstErr) {
            Intent installPkg = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installPkg.setData(apkUri);
            installPkg.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(installPkg);
            } catch (Throwable secondErr) {
                Toast.makeText(this, "No installer available for " + safeName, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable err) {
            Toast.makeText(this, "Unable to open installer", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
