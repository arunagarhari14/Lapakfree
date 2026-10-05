package com.lapakfree;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app updater — bina Play Store ke, bina uninstall ke.
 *
 * Kaam: version.json (stable URL) me naya versionCode dikhe to driver ko dialog
 * dikhao → Download → install prompt. Same signature hai isliye purane ke upar
 * install hota hai — koi data nahi udta, uninstall ki zaroorat kabhi nahi.
 *
 * version.json format:
 *   {"versionCode": 13, "versionName": "2.2",
 *    "apkUrl": "https://.../lapakfree-debug.apk", "notes": "kya naya hai"}
 */
public class UpdateChecker {
    private static final String TAG = "LapakFree";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // din me 1 baar

    public static class UpdateInfo {
        public int versionCode;
        public String versionName = "";
        public String notes = "";
        public String apkUrl = "";
    }

    public interface UpdateCallback {
        /** info null = koi update nahi (ya check fail) */
        void onResult(UpdateInfo info);
    }

    public interface ProgressCallback {
        void onProgress(int percent);
        void onDone(File apk);
        void onError(String msg);
    }

    /** Update URL build time par app/update-url.txt se aata hai. */
    public static String updateUrl(Context c) {
        try {
            int id = c.getResources().getIdentifier(
                    "update_url", "string", c.getPackageName());
            if (id != 0) {
                String s = c.getString(id);
                if (s != null && !s.isEmpty() && !s.contains("PLACEHOLDER")
                        && s.startsWith("http")) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Background se call karo. Throttle: 24 ghante me 1 baar (force=true se abhi). */
    public static void check(Context ctx, boolean force, UpdateCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            UpdateInfo info = null;
            try {
                String url = updateUrl(ctx);
                if (url == null) {
                    h.post(() -> cb.onResult(null));
                    return;
                }
                if (!force) {
                    long last = Prefs.getLastUpdateCheck(ctx);
                    if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
                        h.post(() -> cb.onResult(null));
                        return;
                    }
                }
                Prefs.setLastUpdateCheck(ctx, System.currentTimeMillis());
                String json = httpGet(url, 10000);
                if (json != null) {
                    JSONObject o = new JSONObject(json);
                    int remote = o.optInt("versionCode", 0);
                    int local = localVersionCode(ctx);
                    if (remote > local) {
                        info = new UpdateInfo();
                        info.versionCode = remote;
                        info.versionName = o.optString("versionName", "");
                        info.notes = o.optString("notes", "");
                        info.apkUrl = o.optString("apkUrl", "");
                        if (info.apkUrl.isEmpty()) info = null;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "update check fail", e);
            }
            final UpdateInfo f = info;
            h.post(() -> cb.onResult(f));
        }).start();
    }

    /** APK download karo (progress ke saath), phir install prompt kholo. */
    public static void downloadAndInstall(Activity a, UpdateInfo info, ProgressCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File dir = a.getExternalFilesDir("updates");
                if (dir != null && !dir.exists()) dir.mkdirs();
                File out = new File(dir, "lapakfree-update.apk");
                HttpURLConnection con = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(20000);
                con.setReadTimeout(30000);
                int total = con.getContentLength();
                try (InputStream in = con.getInputStream();
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[32768];
                    int n;
                    long done = 0;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int p = pct;
                                h.post(() -> cb.onProgress(p));
                            }
                        }
                    }
                } finally {
                    con.disconnect();
                }
                h.post(() -> cb.onDone(out));
                // install prompt
                h.post(() -> openInstall(a));
            } catch (Exception e) {
                Log.w(TAG, "update download fail", e);
                h.post(() -> cb.onError("Download fail ho gaya — dobara try karo"));
            }
        }).start();
    }

    /** System installer kholo — same signature = purane ke upar update, data safe. */
    public static void openInstall(Activity a) {
        try {
            Uri uri = Uri.parse("content://" + ApkFileProvider.AUTHORITY + "/apk/lapakfree-update.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "install intent fail", e);
        }
    }

    private static int localVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String httpGet(String url, int timeout) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setConnectTimeout(timeout);
            con.setReadTimeout(timeout);
            if (con.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
