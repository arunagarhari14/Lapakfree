package com.lapakfree;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Standing orders ka local storage — max 20. */
public class OrderStore {
    private static final String KEY = "standing_orders";
    private static final int MAX = 20;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("lapakfree", Context.MODE_PRIVATE);
    }

    public static synchronized List<StandingOrder> list(Context c) {
        List<StandingOrder> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(StandingOrder.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Sirf active orders. */
    public static List<StandingOrder> active(Context c) {
        List<StandingOrder> out = new ArrayList<>();
        for (StandingOrder o : list(c)) if (o.active) out.add(o);
        return out;
    }

    public static synchronized void add(Context c, StandingOrder o) {
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY, "[]"));
            arr.put(o.toJson());
            while (arr.length() > MAX) arr.remove(0);
            sp(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized boolean remove(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY, "[]"));
            JSONArray keep = new JSONArray();
            boolean gone = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!gone && id.equals(o.optString("id"))) { gone = true; continue; }
                keep.put(o);
            }
            sp(c).edit().putString(KEY, keep.toString()).apply();
            return gone;
        } catch (Exception e) { return false; }
    }

    public static synchronized void clear(Context c) {
        sp(c).edit().remove(KEY).apply();
    }
}
