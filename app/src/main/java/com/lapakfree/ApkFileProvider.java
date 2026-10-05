package com.lapakfree;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Minimal FileProvider — bina androidx ke. Sirf downloaded update APK serve karta hai.
 * Authority: com.lapakfree.fileprovider, path: /apk/lapakfree-update.apk
 */
public class ApkFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.lapakfree.fileprovider";
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "apk/*", 1);
    }

    private File apkFile() {
        File dir = getContext().getExternalFilesDir("updates");
        return new File(dir, "lapakfree-update.apk");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (MATCHER.match(uri) != 1) return null;
        File f = apkFile();
        MatrixCursor c = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{"lapakfree-update.apk", f.exists() ? f.length() : 0});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        if (MATCHER.match(uri) == 1) return "application/vnd.android.package-archive";
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (MATCHER.match(uri) != 1) return null;
        try {
            return ParcelFileDescriptor.open(apkFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
