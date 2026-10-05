package com.lapakfree;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/**
 * Ride aate hi LOCAL turant match — koi network/AI wait nahi.
 * AI ka kaam sirf order banate time hota hai (samajhna); execution yahan,
 * millisecond me hota hai.
 */
public class OrderMatcher {
    /** Drop itna % order-direction ki taraf ho to "usi side" mano. */
    private static final int DIR_MATCH_PCT = 15;

    private static final String[] PARCEL_WORDS = {
            "parcel", "package", "courier", "goods", "luggage", "cargo", "shipment"};

    /**
     * @return matching active order, ya null
     */
    public static StandingOrder match(Context c, String app, double[] dropLL,
                                      double[] curLL, String notifText) {
        List<StandingOrder> orders = OrderStore.active(c);
        if (orders.isEmpty()) return null;
        String lowApp = app != null ? app.toLowerCase(Locale.US) : "";
        String lowText = notifText != null ? notifText.toLowerCase(Locale.US) : "";
        boolean isParcel = isParcel(lowText);
        for (StandingOrder o : orders) {
            if (!appOk(o, lowApp)) continue;
            if (!kindOk(o, isParcel)) continue;
            if (!dirOk(c, o, dropLL, curLL)) continue;
            return o;
        }
        return null;
    }

    private static boolean appOk(StandingOrder o, String lowApp) {
        if (o.apps == null || o.apps.trim().isEmpty()) return true;
        for (String a : o.apps.split(",")) {
            String t = a.trim().toLowerCase(Locale.US);
            if (!t.isEmpty() && (lowApp.contains(t) || t.contains(lowApp))) return true;
        }
        return false;
    }

    private static boolean kindOk(StandingOrder o, boolean isParcel) {
        if (o.kinds == null || o.kinds.trim().isEmpty()) return true;
        String k = o.kinds.toLowerCase(Locale.US);
        if (isParcel) return k.contains("parcel");
        return k.contains("ride");
    }

    private static boolean dirOk(Context c, StandingOrder o, double[] dropLL, double[] curLL) {
        if (o.direction == null || o.direction.trim().isEmpty()) return true; // koi bhi side
        if (dropLL == null || curLL == null) return false;
        double dLat = o.dirLat, dLng = o.dirLng;
        if (Double.isNaN(dLat) || Double.isNaN(dLng)) {
            double[] g = OfflineGeo.geocode(c, o.direction, curLL);
            if (g == null) return false;
            dLat = g[0]; dLng = g[1];
        }
        Integer pct = GeoUtil.towardPct(curLL[0], curLL[1], dropLL[0], dropLL[1], dLat, dLng);
        return pct != null && pct >= DIR_MATCH_PCT;
    }

    private static boolean isParcel(String lowText) {
        for (String w : PARCEL_WORDS) if (lowText.contains(w)) return true;
        return false;
    }
}
