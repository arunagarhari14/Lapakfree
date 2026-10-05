package com.lapakfree;

import android.content.Context;

/** Rules check karke ACCEPT ya SKIP ka faisla deta hai. */
public class RuleEngine {

    public static class Verdict {
        public final boolean accept;
        public final String reason;
        Verdict(boolean accept, String reason) { this.accept = accept; this.reason = reason; }
    }

    public static Verdict check(Context c, Offer o) {
        int minFare = Prefs.getMinFare(c);
        float maxPickup = Prefs.getMaxPickupKm(c);
        float minTrip = Prefs.getMinTripKm(c);

        if (o.fare < 0) {
            if (!Prefs.isAcceptUnknownFare(c)) {
                return new Verdict(false, "fare nahi dikha");
            }
        } else if (o.fare < minFare) {
            return new Verdict(false, "fare ₹" + (int) o.fare + " < ₹" + minFare);
        }

        if (o.pickupKm >= 0 && o.pickupKm > maxPickup) {
            return new Verdict(false, "pickup " + o.pickupKm + "km door hai");
        }

        if (o.tripKm >= 0 && o.tripKm < minTrip) {
            return new Verdict(false, "trip chhoti hai (" + o.tripKm + "km)");
        }

        return new Verdict(true, "rule me fit — " + o.toString());
    }
}
