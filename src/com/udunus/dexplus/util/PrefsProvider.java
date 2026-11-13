package com.udunus.dexplus.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.Map;

public class PrefsProvider extends ContentProvider {
    private static final String[] COLUMNS = new String[]{"key", "type", "value"};

    private static void enforceSameUid() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Write access to Dex+ preferences is restricted to module UID");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if (getContext() == null) return cursor;
        SharedPreferences prefs = ModConfig.getLocalPrefs(getContext());
        Map<String, ?> all = prefs.getAll();
        if (all != null) {
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                if (k == null || v == null) continue;
                String type;
                if (v instanceof Boolean) {
                    type = "boolean";
                } else if (v instanceof Integer) {
                    type = "int";
                } else if (v instanceof Float) {
                    type = "float";
                } else {
                    type = "string";
                }
                cursor.addRow(new Object[]{k, type, String.valueOf(v)});
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.udunus.dexplus.prefs";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceSameUid();
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceSameUid();
        if (getContext() == null) return 0;
        ModConfig.getLocalPrefs(getContext()).edit().clear().commit();
        try {
            getContext().getContentResolver().notifyChange(ModConfig.PREFS_URI, null);
        } catch (Throwable ignored) {}
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        enforceSameUid();
        if (getContext() == null || values == null) return 0;
        SharedPreferences prefs = ModConfig.getLocalPrefs(getContext());
        SharedPreferences.Editor editor = prefs.edit();
        if ("__RESET__".equals(selection)) {
            editor.clear();
        }
        for (String key : values.keySet()) {
            Object val = values.get(key);
            if (val instanceof Boolean) {
                editor.putBoolean(key, (Boolean) val);
            } else if (val instanceof Integer) {
                editor.putInt(key, (Integer) val);
            } else if (val instanceof Float) {
                editor.putFloat(key, (Float) val);
            } else if (val instanceof String) {
                editor.putString(key, (String) val);
            }
        }
        editor.commit();
        try {
            getContext().getContentResolver().notifyChange(ModConfig.PREFS_URI, null);
        } catch (Throwable ignored) {}
        return 1;
    }
}
