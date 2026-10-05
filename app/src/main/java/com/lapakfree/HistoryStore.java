package com.lapakfree;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Aakhri 100 ride events ka hisaab. (org.json Android me built-in hai.) */
public class HistoryStore {
    private static final String KEY = "history";
    private static final int MAX = 100;

    public static class Entry {
        public String time, day, app, detail, verdict, reason;
    }

    public static void add(Context c, Offer o, String verdict, String reason) {
        try {
            SharedPreferences sp = c.getSharedPreferences("lapakfree", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            Date now = new Date();
            JSONObject e = new JSONObject();
            e.put("t", new SimpleDateFormat("HH:mm:ss", Locale.US).format(now));
            e.put("day", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now));
            e.put("app", o.app);
            e.put("d", o.toString());
            e.put("v", verdict);
            e.put("r", reason);
            arr.put(e);
            while (arr.length() > MAX) arr.remove(0);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static List<Entry> get(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences("lapakfree", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject e = arr.getJSONObject(i);
                Entry en = new Entry();
                en.time = e.optString("t"); en.day = e.optString("day", "");
                en.app = e.optString("app");
                en.detail = e.optString("d"); en.verdict = e.optString("v");
                en.reason = e.optString("r");
                out.add(en);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void clear(Context c) {
        c.getSharedPreferences("lapakfree", Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
