package com.udunus.dexplus.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves staged Desktop files (such as .apk installers) with grantUriPermissions support
 * so external system handlers like PackageInstaller can read them safely.
 */
public class DesktopFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.udunus.dexplus.files";
    private static final String STAGE_DIR_NAME = "desktop_apk";

    public static File getStageDir(Context context) {
        File dir = new File(context.getCacheDir(), STAGE_DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri buildUri(String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(fileName)
                .build();
    }

    public static String sanitizeName(String rawName) {
        if (TextUtils.isEmpty(rawName)) return "package.apk";
        String base = new File(rawName).getName();
        String cleaned = base.replaceAll("[\\\\/:*?\"<>|\r\n]", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned.isEmpty() ? "package.apk" : cleaned;
    }

    private File resolveTargetFile(Uri uri) {
        Context ctx = getContext();
        if (ctx == null || uri == null) return null;
        String segment = uri.getLastPathSegment();
        if (TextUtils.isEmpty(segment)) return null;
        String safeName = sanitizeName(segment);
        return new File(getStageDir(ctx), safeName);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String seg = uri != null ? uri.getLastPathSegment() : null;
        if (seg != null && seg.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolveTargetFile(uri);
        String[] cols = projection;
        if (cols == null || cols.length == 0) {
            cols = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        if (f != null && f.exists()) {
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = f.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = f.length();
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveTargetFile(uri);
        if (f == null || !f.exists() || !f.isFile()) {
            throw new FileNotFoundException("Staged file not found");
        }
        if (!"r".equals(mode)) {
            throw new SecurityException("Read-only provider");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
