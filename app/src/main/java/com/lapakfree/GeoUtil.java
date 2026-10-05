package com.lapakfree;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/** Distance math + free geocoding (OpenStreetMap Nominatim, koi key nahi chahiye). */
public class GeoUtil {
    private static final Map<String, double[]> CACHE = new HashMap<>();

    /** Do points ke beech doori, meter me. */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /**
     * Drop manjil ki taraf kitne % jaa rahi hai.
     * @return +65 matlab 65% pass, -40 matlab 40% door. null = compute nahi hua.
     */
    public static Integer towardPct(double curLat, double curLon,
                                    double dropLat, double dropLon,
                                    double destLat, double destLon) {
        double d1 = haversine(curLat, curLon, destLat, destLon);
        if (d1 < 50) return null; // manjil bahut paas hai, % ka matlab nahi
        double d2 = haversine(dropLat, dropLon, destLat, destLon);
        return (int) Math.round((d1 - d2) / d1 * 100);
    }

    /** Cache ke saath geocode. Hamesha background thread se call karo. */
    public static synchronized double[] geocodeCached(String query) {
        String k = query.toLowerCase().trim();
        if (CACHE.containsKey(k)) return CACHE.get(k);
        double[] r = geocode(query);
        if (r != null) CACHE.put(k, r);
        return r;
    }

    /** Jagah ka naam -> [lat, lng]. Na mile to null. */
    public static double[] geocode(String query) {
        HttpURLConnection c = null;
        try {
            String q = URLEncoder.encode(query + ", India", "UTF-8");
            URL url = new URL("https://nominatim.openstreetmap.org/search?q=" + q
                    + "&format=json&limit=1&countrycodes=in&addressdetails=0");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("User-Agent", "LapakFree/1.0 (Android driver app)");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONArray arr = new JSONArray(sb.toString());
            if (arr.length() == 0) return null;
            JSONObject o = arr.getJSONObject(0);
            return new double[]{o.getDouble("lat"), o.getDouble("lon")};
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
