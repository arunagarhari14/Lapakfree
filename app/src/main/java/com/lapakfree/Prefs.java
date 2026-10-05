package com.lapakfree;

import android.content.Context;
import android.content.SharedPreferences;

/** Simple settings storage. Sab kuch free hai, koi account/subscription nahi. */
public class Prefs {
    private static final String NAME = "lapakfree";
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isMasterOn(Context c) { return sp(c).getBoolean("master", false); }
    public static void setMasterOn(Context c, boolean v) { sp(c).edit().putBoolean("master", v).apply(); }

    public static int getMinFare(Context c) { return sp(c).getInt("minFare", 50); }
    public static void setMinFare(Context c, int v) { sp(c).edit().putInt("minFare", v).apply(); }

    public static float getMaxPickupKm(Context c) { return sp(c).getFloat("maxPickupKm", 5f); }
    public static void setMaxPickupKm(Context c, float v) { sp(c).edit().putFloat("maxPickupKm", v).apply(); }

    public static float getMinTripKm(Context c) { return sp(c).getFloat("minTripKm", 0f); }
    public static void setMinTripKm(Context c, float v) { sp(c).edit().putFloat("minTripKm", v).apply(); }

    public static boolean isAcceptUnknownFare(Context c) { return sp(c).getBoolean("unknownFare", true); }
    public static void setAcceptUnknownFare(Context c, boolean v) { sp(c).edit().putBoolean("unknownFare", v).apply(); }

    // ---- Destination mode ----
    public static String getDestName(Context c) { return sp(c).getString("destName", ""); }
    public static boolean hasDest(Context c) { return sp(c).contains("destLat"); }
    public static double[] getDestLatLng(Context c) {
        android.content.SharedPreferences s = sp(c);
        if (!s.contains("destLat")) return null;
        return new double[]{
                Double.longBitsToDouble(s.getLong("destLat", 0)),
                Double.longBitsToDouble(s.getLong("destLng", 0))};
    }
    public static void setDest(Context c, String name, double lat, double lng) {
        sp(c).edit().putString("destName", name)
                .putLong("destLat", Double.doubleToLongBits(lat))
                .putLong("destLng", Double.doubleToLongBits(lng)).apply();
    }
    public static void clearDest(Context c) {
        sp(c).edit().remove("destName").remove("destLat").remove("destLng").apply();
    }
    public static boolean isDestMode(Context c) { return sp(c).getBoolean("destMode", false); }
    public static void setDestMode(Context c, boolean v) { sp(c).edit().putBoolean("destMode", v).apply(); }

    // Destination mode me max pickup km (default 2)
    public static float getDestMaxPickupKm(Context c) { return sp(c).getFloat("destMaxPickupKm", 2f); }
    public static void setDestMaxPickupKm(Context c, float v) { sp(c).edit().putFloat("destMaxPickupKm", v).apply(); }

    // ---- Google AI (Gemini via Firebase AI Logic) ----
    /** Login ke baad har destination-ride ka faisla AI se karwao. Default ON. */
    public static boolean isAiOn(Context c) { return sp(c).getBoolean("aiOn", true); }
    public static void setAiOn(Context c, boolean v) { sp(c).edit().putBoolean("aiOn", v).apply(); }

    // ---- Google login (OAuth2, bina SDK) ----
    public static String getLoginEmail(Context c) { return sp(c).getString("loginEmail", ""); }
    public static String getLoginName(Context c) { return sp(c).getString("loginName", ""); }
    public static void setLogin(Context c, String email, String name) {
        sp(c).edit().putString("loginEmail", email).putString("loginName", name).apply();
    }
    public static void clearLogin(Context c) {
        sp(c).edit().remove("loginEmail").remove("loginName").apply();
    }
    /** OAuth state+verifier (ek login attempt ke liye). returns {state, verifier} ya null. */
    public static String[] getOauthState(Context c) {
        android.content.SharedPreferences s = sp(c);
        String st = s.getString("oauthState", null);
        String vf = s.getString("oauthVerifier", null);
        return (st == null || vf == null) ? null : new String[]{st, vf};
    }
    public static void setOauthState(Context c, String state, String verifier) {
        sp(c).edit().putString("oauthState", state).putString("oauthVerifier", verifier).apply();
    }
    public static void clearOauthState(Context c) {
        sp(c).edit().remove("oauthState").remove("oauthVerifier").apply();
    }

    // ---- Turant Ride Alerts (notification se, v1.8) ----
    /** Notification aate hi turant % wala card dikhao. Default ON. */
    public static boolean isNotifAlertsOn(Context c) { return sp(c).getBoolean("notifAlerts", true); }
    public static void setNotifAlertsOn(Context c, boolean v) { sp(c).edit().putBoolean("notifAlerts", v).apply(); }
    /** "manual" = % card + Accept/Skip button; "auto" = % threshold par turant auto-accept. */
    public static String getAlertMode(Context c) { return sp(c).getString("alertMode", "manual"); }
    public static void setAlertMode(Context c, String v) { sp(c).edit().putString("alertMode", v).apply(); }
    /** Auto mode me kitne % pass par turant accept ho. Default 30. */
    public static int getAutoPct(Context c) { return sp(c).getInt("autoPct", 30); }
    public static void setAutoPct(Context c, int v) { sp(c).edit().putInt("autoPct", v).apply(); }

    // ---- AI custom instructions (v1.9) ----
    /** Driver ke apne nirdesh — AI har ride ke faisle me inhe sabse pehle maanta hai. */
    public static String getAiInstructions(Context c) { return sp(c).getString("aiInstructions", ""); }
    public static void setAiInstructions(Context c, String v) { sp(c).edit().putString("aiInstructions", v).apply(); }

    // ---- Google OAuth tokens (v2.0: har driver ka apna token, apna free AI quota) ----
    /** @return {accessToken, refreshToken, expiryMillis} ya null */
    public static String[] getOauthTokens(Context c) {
        android.content.SharedPreferences s = sp(c);
        String a = s.getString("oauthAccess", null);
        String r = s.getString("oauthRefresh", null);
        if (a == null && r == null) return null;
        return new String[]{a, r, String.valueOf(s.getLong("oauthExp", 0))};
    }
    public static void saveOauthTokens(Context c, String access, String refresh, long expAt) {
        sp(c).edit().putString("oauthAccess", access)
                .putString("oauthRefresh", refresh)
                .putLong("oauthExp", expAt).apply();
    }
    public static void clearOauthTokens(Context c) {
        sp(c).edit().remove("oauthAccess").remove("oauthRefresh").remove("oauthExp").apply();
    }

    // ---- In-app updater (v2.2) ----
    public static long getLastUpdateCheck(Context c) { return sp(c).getLong("lastUpdateCheck", 0); }
    public static void setLastUpdateCheck(Context c, long v) { sp(c).edit().putLong("lastUpdateCheck", v).apply(); }
}
