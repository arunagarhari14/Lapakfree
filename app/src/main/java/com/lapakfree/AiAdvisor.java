package com.lapakfree;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Har ride offer par Google ke AI (Gemini) se 1 faisla: ACCEPT ya SKIP.
 *
 * Bina kisi SDK ke — seedha Gemini Developer API par HTTPS call, driver ke APNE
 * Google account ke OAuth token se (v2.0: koi shared key nahi, har driver ka
 * apna free quota).
 *
 * Flow: AutoAcceptService offer padhta hai → saare numbers (pickup km, direction %,
 * fare/km) LOCAL nikaalta hai → yahan sirf chhota prompt bhej ke AI se haan/na
 * poochta hai. Jawab na aaye (timeout/error/limit) to null milta hai aur purana
 * RuleEngine faisla karta hai — ride kabhi AI ke bharose atakti nahi.
 */
public class AiAdvisor {
    private static final String TAG = "LapakFree";
    /**
     * "latest" alias — Google purane flash models hata deta hai (2.0/2.5 band ho gaye),
     * alias hamesha current model par point karta hai, code badalna nahi padta.
     */
    private static final String MODEL = "gemini-flash-latest";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final long TIMEOUT_MS = 3500;

    /** Ride ke tayaar numbers — sab local compute hote hain, AI sirf judge karta hai. */
    public static class Facts {
        public String app = "";
        public String pickupName = "";
        public double pickupKm = -1;
        public double maxPickupKm = 2;
        public String dropName = "";
        /** drop manjil se kitna % kareeb hua (direction sahi hone par positive) */
        public int towardPct = 0;
        public String destName = "";
        public double fare = -1;
        public double tripKm = -1;
        public double farePerKm = -1;
        public double minFarePerKm = 15;
    }

    public interface DecideCallback {
        /**
         * @param accept true=ACCEPT, false=SKIP, null=AI jawab nahi de paya → rule se faisla
         * @param reason chhota Hinglish reason (history me dikhega)
         */
        void onVerdict(Boolean accept, String reason);
    }

    /** Hamesha background thread se call karo. Callback main thread par aata hai. */
    public static void decide(Context ctx, Facts f, DecideCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        DecideCallback once = (a, r) -> {
            if (!done[0]) { done[0] = true; h.post(() -> cb.onVerdict(a, r)); }
        };
        new Thread(() -> {
            try {
                // v2.0: driver ke APNE Google account ka token — uska apna free AI quota
                String token = GoogleAuth.getValidAccessToken(ctx);
                if (token == null) {
                    once.onVerdict(null, "AI login nahi hai — rule se faisla");
                    return;
                }
                String instr = Prefs.getAiInstructions(ctx);
                String json = askGemini(token, f, instr, ctx);
                if (json == null) {
                    once.onVerdict(null, "AI jawab nahi de paya — rule se faisla");
                    return;
                }
                boolean accept = parseAccept(json);
                String reason = parseReason(json);
                once.onVerdict(accept, "AI: " + reason);
            } catch (Exception e) {
                Log.w(TAG, "ai decide fail", e);
                once.onVerdict(null, "AI error — rule se faisla");
            }
        }).start();
        // safety: timeout par rule-engine ko mauka do, latko mat
        h.postDelayed(() -> once.onVerdict(null, "AI slow tha — rule se faisla"), TIMEOUT_MS + 500);
    }

    private static String askGemini(String token, Facts f, String instructions, Context ctx) throws Exception {
        return rawCallWithToken(ctx, token, buildPrompt(f, instructions, ctx), 120, TIMEOUT_MS);
    }

    /**
     * Generic Gemini call — prompt bhejo, JSON wala text jawab pao (ya null).
     * Hamesha background thread se call karo. Token khud nikala jata hai.
     */
    public static String rawCall(Context ctx, String prompt, int maxTokens, long timeoutMs) {
        try {
            String token = GoogleAuth.getValidAccessToken(ctx);
            if (token == null) return null;
            return rawCallWithToken(ctx, token, prompt, maxTokens, timeoutMs);
        } catch (Exception e) {
            Log.w(TAG, "rawCall fail", e);
            return null;
        }
    }

    private static String rawCallWithToken(Context ctx, String token, String prompt,
                                           int maxTokens, long timeoutMs) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", prompt));
        content.put("parts", parts);
        contents.put(content);
        body.put("contents", contents);
        JSONObject genCfg = new JSONObject();
        genCfg.put("responseMimeType", "application/json");
        genCfg.put("maxOutputTokens", maxTokens);
        body.put("generationConfig", genCfg);

        HttpURLConnection con = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout((int) TIMEOUT_MS);
            con.setReadTimeout((int) TIMEOUT_MS);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("Authorization", "Bearer " + token);
            String proj = userProject(ctx);
            if (proj != null) con.setRequestProperty("x-goog-user-project", proj);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = con.getOutputStream()) {
                os.write(payload);
            }
            int code = con.getResponseCode();
            String resp = readAll(con);
            if (code == 401) {
                // token revoke/expire — dobara login chahiye
                GoogleAuth.clearTokens(ctx);
                Log.w(TAG, "gemini 401 — token saaf, dobara login chahiye");
                return null;
            }
            if (code == 429) {
                Log.w(TAG, "gemini quota khatam (429) — rule se faisla");
                return null;
            }
            if (code != 200) {
                Log.w(TAG, "gemini http " + code + ": " + resp);
                return null;
            }
            JSONObject o = new JSONObject(resp);
            JSONArray cands = o.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject c0 = cands.getJSONObject(0).optJSONObject("content");
            if (c0 == null) return null;
            JSONArray ps = c0.optJSONArray("parts");
            if (ps == null || ps.length() == 0) return null;
            // kayi parts ho sakte hain (thought + text) — wahi part lo jo JSON hai
            for (int i = 0; i < ps.length(); i++) {
                String text = ps.getJSONObject(i).optString("text", "").trim();
                if (text.isEmpty()) continue;
                try {
                    JSONObject j = new JSONObject(text);
                    if (j.has("accept") || j.has("action")) return text;
                } catch (Exception ignored) {}
            }
            return null;
        } finally {
            con.disconnect();
        }
    }

    private static String buildPrompt(Facts f, String instructions, Context ctx) {
        // chhota prompt = tez jawab (target <2 sec)
        StringBuilder sb = new StringBuilder();
        String instr = instructions != null ? instructions.trim() : "";
        if (!instr.isEmpty()) {
            // driver ke custom nirdesh — sabse upar, sabse important
            sb.append("Driver ke nirdesh (sabse pehle inhe mano, inke khilaaf ride kabhi accept mat karo): ");
            sb.append(safe(instr)).append(" ");
        }
        // AI ko context: destination + aaj ki history + active standing orders
        sb.append(aiContext(ctx)).append(" ");
        sb.append(String.format(Locale.US,
                "You decide for an Indian taxi driver. Facts: app=%s, pickup \"%s\" %.1fkm away " +
                "(limit %.1fkm), drop \"%s\" is %d%% closer to driver destination \"%s\", " +
                "fare Rs.%.0f for %.1fkm = Rs.%.1f/km (need >=Rs.%.0f/km). " +
                "Agar ride driver ke nirdesh ya standing orders ke khilaaf hai to accept=false karo. " +
                "Reply ONLY JSON, no other text: {\"accept\": true, \"reason\": \"short Hinglish under 12 words\"}",
                safe(f.app), safe(f.pickupName), f.pickupKm, f.maxPickupKm,
                safe(f.dropName), f.towardPct, safe(f.destName),
                f.fare, f.tripKm, f.farePerKm, f.minFarePerKm));
        return sb.toString();
    }

    /** AI ko yaad rahe: destination, aaj ki history, active standing orders. */
    static String aiContext(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            String dest = Prefs.getDestName(ctx);
            sb.append("Driver ka destination: ").append(safe(dest.isEmpty() ? "(set nahi hai)" : dest)).append(". ");
            List<StandingOrder> orders = OrderStore.active(ctx);
            if (!orders.isEmpty()) {
                sb.append("Active standing orders (driver ne AI ko diye, inhe follow karo): ");
                for (int i = 0; i < orders.size(); i++) {
                    StandingOrder o = orders.get(i);
                    sb.append(i + 1).append(". ").append(safe(o.text)).append("; ");
                }
            }
            List<HistoryStore.Entry> h = HistoryStore.get(ctx);
            if (!h.isEmpty()) {
                sb.append("Aaj ki history (nayi se purani): ");
                int n = 0;
                for (HistoryStore.Entry e : h) {
                    if (n++ >= 6) break;
                    sb.append(safe(e.time)).append(" ").append(safe(e.app)).append(" ")
                      .append(safe(e.verdict)).append(" (").append(safe(e.detail)).append("); ");
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\"", "").replace("\n", " ").trim();
    }

    private static boolean parseAccept(String json) {
        String low = json.toLowerCase(Locale.US);
        int i = low.indexOf("\"accept\"");
        if (i < 0) return false;
        String tail = low.substring(i, Math.min(low.length(), i + 30));
        return tail.contains("true");
    }

    private static String parseReason(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String r = o.optString("reason", "").trim();
            if (!r.isEmpty()) return r.length() > 80 ? r.substring(0, 80) : r;
        } catch (Exception ignored) {}
        return "faisla ho gaya";
    }

    /** OAuth client jis Cloud project me bana hai uska project id (quota attribution). */
    private static String userProject(Context c) {
        try {
            int id = c.getResources().getIdentifier(
                    "google_cloud_project", "string", c.getPackageName());
            if (id != 0) {
                String s = c.getString(id);
                if (s != null && !s.isEmpty() && !s.contains("PLACEHOLDER")) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String readAll(HttpURLConnection con) throws Exception {
        InputStream in = con.getResponseCode() < 400 ? con.getInputStream() : con.getErrorStream();
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
