package com.lapakfree;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Driver app ki screen ke text se fare aur km nikalta hai. */
public class OfferParser {
    private static final Pattern FARE = Pattern.compile("₹\\s?([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern FARE_RS = Pattern.compile("(?i)\\brs\\.?\\s?([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern KM = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s?km");

    public static Offer parse(List<String> texts) {
        Offer o = new Offer();
        List<Double> fares = new ArrayList<>();
        List<Double> kms = new ArrayList<>();
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = FARE.matcher(t);
            while (m.find()) fares.add(num(m.group(1)));
            Matcher m2 = FARE_RS.matcher(t);
            while (m2.find()) fares.add(num(m2.group(1)));
            Matcher m3 = KM.matcher(t.toLowerCase());
            while (m3.find()) kms.add(num(m3.group(1)));
        }
        if (!fares.isEmpty()) {
            double max = 0;
            for (double f : fares) if (f > max) max = f;
            o.fare = max; // offer me sabse bada amount hi fare hota hai
        }
        if (!kms.isEmpty()) {
            double min = Double.MAX_VALUE, max = 0;
            for (double k : kms) { if (k < min) min = k; if (k > max) max = k; }
            if (kms.size() >= 2) {
                o.pickupKm = min; // chhoti distance = pickup ki doori
                o.tripKm = max;   // badi distance = trip ki lambai
            } else {
                o.tripKm = max;   // ek hi km dikhe to trip maano, pickup unknown
            }
        }
        return o;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s.replace(",", "")); }
        catch (Exception e) { return -1; }
    }
}
