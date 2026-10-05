package com.lapakfree;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Offline-first geocoding: pehle app ke andar download kiya hua
 * Delhi NCR map database (assets/delhi_ncr.db) search karta hai.
 * Wahan na mile to internet se Nominatim fallback.
 * Hamesha background thread se call karo (pehli baar DB copy hota hai).
 *
 * OSM me sector "Sector 29" jaise naam se hote hain (bina sheher ke),
 * isliye "Gurugram Sector 29" jaise query me sheher ka naam hata ke
 * sheher ke center ke sabse paas wala result chuna jata hai.
 */
public class OfflineGeo {
    private static final String DB_NAME = "delhi_ncr.db";
    private static volatile SQLiteDatabase db;
    private static final Object LOCK = new Object();

    // sheher ke naam jo query se alag kiye ja sakte hain (lambe pehle)
    private static final String[] CITY_WORDS = {
            "greater noida", "new delhi", "gurugram", "gurgaon", "noida",
            "delhi", "faridabad", "ghaziabad", "manesar", "sonipat", "bahadurgarh"};

    /** Jagah ka naam -> [lat, lng]. Na mile to null. */
    public static double[] geocode(Context c, String query) {
        return geocode(c, query, null);
    }

    /**
     * bias = [lat, lng]: ek jaise naam wali jagahon me se bias ke
     * sabse paas wali chuni jayegi (jaise user ki current location).
     */
    public static double[] geocode(Context c, String query, double[] bias) {
        double[] r = searchLocal(c, query, bias);
        if (r != null) return r;
        return GeoUtil.geocode(query); // online fallback
    }

    private static SQLiteDatabase db(Context c) {
        if (db != null) return db;
        synchronized (LOCK) {
            if (db != null) return db;
            try {
                File f = c.getDatabasePath(DB_NAME);
                if (!f.exists()) {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    InputStream in = c.getAssets().open(DB_NAME);
                    OutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                    out.close();
                    in.close();
                }
                db = SQLiteDatabase.openDatabase(f.getPath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                return db;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static double[] searchLocal(Context c, String query, double[] bias) {
        try {
            SQLiteDatabase d = db(c);
            if (d == null) return null;
            String q = norm(query);
            if (q.length() < 3) return null;

            // 1) poora naam exact-ish
            double[] r = queryMatch(d, "\"" + q + "\"", bias);
            if (r != null) return r;

            // 2) saare shabd (aakhri adhura bhi chalega)
            r = queryMatch(d, andQuery(q), bias);
            if (r != null) return r;

            // 3) sheher ka naam hatao ("Gurugram Sector 29" -> "Sector 29"),
            //    phir sheher ke center ke sabse paas wala chuno
            String stripped = stripCities(q);
            String cp = cityPart(q);
            if (!stripped.equals(q) && stripped.length() >= 2 && !cp.isEmpty()) {
                double[] cityBias = cityCenter(d, cp);
                double[] useBias = (cityBias != null) ? cityBias : bias;
                r = queryMatch(d, andQuery(stripped), useBias);
                if (r != null) return r;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sheher ka center (city/town wali row), bias ke liye. */
    private static double[] cityCenter(SQLiteDatabase d, String cityWords) {
        // "gurugram" OSM me "Gurgaon" naam se hai
        String cw = cityWords.replace("gurugram", "gurgaon");
        Cursor cur = null;
        try {
            cur = d.rawQuery(
                    "SELECT lat, lon FROM places WHERE places MATCH ? " +
                    "AND kind IN ('city','town') ORDER BY imp DESC LIMIT 1",
                    new String[]{andQuery(cw)});
            if (cur.moveToFirst()) {
                return new double[]{cur.getDouble(0), cur.getDouble(1)};
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /**
     * Top-8 important results me se bias ke sabse paas wala chunta hai.
     * bias null ho to sabse important wala.
     */
    private static double[] queryMatch(SQLiteDatabase d, String match, double[] bias) {
        Cursor cur = null;
        try {
            cur = d.rawQuery(
                    "SELECT lat, lon FROM places WHERE places MATCH ? ORDER BY imp DESC LIMIT 8",
                    new String[]{match});
            boolean found = false;
            double bestLat = 0, bestLon = 0, bestDist = 0;
            while (cur.moveToNext()) {
                double la = cur.getDouble(0), lo = cur.getDouble(1);
                if (bias == null) {
                    return new double[]{la, lo}; // pehla = sabse important
                }
                double dist = GeoUtil.haversine(bias[0], bias[1], la, lo);
                if (!found || dist < bestDist) {
                    found = true;
                    bestDist = dist;
                    bestLat = la;
                    bestLon = lo;
                }
            }
            return found ? new double[]{bestLat, bestLon} : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (cur != null) cur.close();
        }
    }

    private static String norm(String s) {
        return s.toLowerCase().trim()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String andQuery(String q) {
        String[] toks = q.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toks.length; i++) {
            if (toks[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(" AND ");
            sb.append(toks[i]).append("*");
        }
        return sb.toString();
    }

    private static String stripCities(String q) {
        String s = " " + q + " ";
        for (String cw : CITY_WORDS) {
            s = s.replace(" " + cw + " ", " ");
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String cityPart(String q) {
        String s = " " + q + " ";
        StringBuilder sb = new StringBuilder();
        for (String cw : CITY_WORDS) {
            if (s.contains(" " + cw + " ")) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(cw);
            }
        }
        return sb.toString();
    }
}
