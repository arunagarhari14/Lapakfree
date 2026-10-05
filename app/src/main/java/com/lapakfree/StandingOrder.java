package com.lapakfree;

import org.json.JSONObject;

/**
 * Driver ka AI ko diya hua standing order — jaise:
 * "Noida side ki parcel ride Rapido/Uber se aaye to accept kar le".
 * AI (AiCommander) driver ki baat samajh ke isko structured rule me badalta hai,
 * phir OrderMatcher ride aate hi LOCAL turant match karta hai (network wait nahi).
 */
public class StandingOrder {
    public String id;
    public String text;       // driver ke apne shabd
    public String direction;  // jagah ka naam, "" = koi bhi direction
    public double dirLat = Double.NaN, dirLng = Double.NaN;
    public String apps = "";  // "rapido,uber" ya "" = koi bhi app
    public String kinds = ""; // "parcel", "ride" ya "" = dono
    public long createdAt;
    public boolean active = true;

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("text", text); o.put("direction", direction);
            o.put("dirLat", dirLat); o.put("dirLng", dirLng);
            o.put("apps", apps); o.put("kinds", kinds);
            o.put("createdAt", createdAt); o.put("active", active);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    public static StandingOrder fromJson(JSONObject o) {
        StandingOrder s = new StandingOrder();
        s.id = o.optString("id", String.valueOf(System.currentTimeMillis()));
        s.text = o.optString("text", "");
        s.direction = o.optString("direction", "");
        s.dirLat = o.optDouble("dirLat", Double.NaN);
        s.dirLng = o.optDouble("dirLng", Double.NaN);
        s.apps = o.optString("apps", "");
        s.kinds = o.optString("kinds", "");
        s.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        s.active = o.optBoolean("active", true);
        return s;
    }
}
