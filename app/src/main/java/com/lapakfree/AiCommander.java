package com.lapakfree;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * AI Commander — driver AI se baat karke poore app ko manage karta hai.
 *
 * Jaise: "Porter se Noida ki ride le li, ab Rapido/Uber se usi side ki
 * parcel ride aaye to accept kar le" → AI samajh ke standing order banata hai.
 * Uske baad ride aate hi OrderMatcher LOCAL turant match karke accept karta hai.
 *
 * AI ka kaam sirf SAMAJHNA hai (command ke time, ek call). Execution hamesha
 * local hota hai — isliye turant hai, network ka wait nahi.
 */
public class AiCommander {
    private static final String TAG = "LapakFree";
    private static final long TIMEOUT_MS = 8000;

    public interface ChatCallback {
        /** Hamesha main thread par aata hai. */
        void onReply(String reply);
    }

    /** Hamesha background thread se call karo. */
    public static void chat(Context ctx, String message, ChatCallback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String reply;
            try {
                if (!GoogleAuth.isSignedIn(ctx)) {
                    reply = "Pehle Google se login karo — tabhi main tumhari baat samajh paunga.";
                } else {
                    reply = handle(ctx, message.trim());
                }
            } catch (Exception e) {
                Log.w(TAG, "commander fail", e);
                reply = "Kuch gadbad ho gayi — dobara bolo.";
            }
            final String r = reply;
            h.post(() -> cb.onReply(r));
        }).start();
    }

    private static String handle(Context ctx, String message) {
        if (message.isEmpty()) return "Kuch bolo to sahi — jaise \"Noida side ki parcel ride accept kar le\".";
        String json = AiAdvisor.rawCall(ctx, buildPrompt(ctx, message), 300, TIMEOUT_MS);
        if (json == null) return "AI se baat nahi ho payi (net slow ya quota) — thodi der me dobara bolo.";
        try {
            JSONObject o = new JSONObject(json);
            String action = o.optString("action", "reply");
            String reply = o.optString("reply", "Samajh nahi aaya — dobara bolo.");
            switch (action) {
                case "add_order": return doAddOrder(ctx, o, reply);
                case "remove_order": return doRemoveOrder(ctx, o, reply);
                case "clear_orders":
                    OrderStore.clear(ctx);
                    return reply.isEmpty() ? "Saare orders hata diye." : reply;
                case "set_destination": return doSetDestination(ctx, o, reply);
                case "list_orders":
                case "reply":
                default: return reply;
            }
        } catch (Exception e) {
            return "Samajh nahi aaya — dobara bolo.";
        }
    }

    private static String doAddOrder(Context ctx, JSONObject o, String fallbackReply) {
        try {
            JSONObject jo = o.optJSONObject("order");
            if (jo == null) return fallbackReply;
            StandingOrder s = new StandingOrder();
            s.id = String.valueOf(System.currentTimeMillis());
            s.text = jo.optString("text", "");
            if (s.text.isEmpty()) s.text = "custom order";
            s.direction = jo.optString("direction", "").trim();
            s.apps = join(jo.optJSONArray("apps"));
            s.kinds = join(jo.optJSONArray("kinds"));
            s.createdAt = System.currentTimeMillis();
            // direction map me milni chahiye, warna galat accept ka risk
            if (!s.direction.isEmpty()) {
                double[] g = OfflineGeo.geocode(ctx, s.direction, null);
                if (g == null) {
                    return "\"" + s.direction + "\" map me nahi mila — koi aur naam batao, " +
                            "jaise \"Noida Sector 18\" ya \"Gurgaon\".";
                }
                s.dirLat = g[0]; s.dirLng = g[1];
            }
            OrderStore.add(ctx, s);
            StringBuilder conf = new StringBuilder("Ho gaya! ");
            conf.append(s.text);
            if (!s.direction.isEmpty()) conf.append(" (").append(s.direction).append(" side)");
            conf.append(" — aisi ride aate hi turant accept kar lunga.");
            return conf.toString();
        } catch (Exception e) {
            return fallbackReply;
        }
    }

    private static String doRemoveOrder(Context ctx, JSONObject o, String fallbackReply) {
        try {
            int idx = o.optInt("index", -1);
            List<StandingOrder> act = OrderStore.active(ctx);
            if (idx >= 1 && idx <= act.size()) {
                StandingOrder s = act.get(idx - 1);
                OrderStore.remove(ctx, s.id);
                return "Hata diya: \"" + s.text + "\"";
            }
            return "Kaun sa wala? Active orders: " + listText(OrderStore.active(ctx));
        } catch (Exception e) {
            return fallbackReply;
        }
    }

    private static String doSetDestination(Context ctx, JSONObject o, String fallbackReply) {
        try {
            String dest = o.optString("destination", "").trim();
            if (dest.isEmpty()) return fallbackReply;
            double[] g = OfflineGeo.geocode(ctx, dest, null);
            if (g == null) return "\"" + dest + "\" map me nahi mila — sahi naam batao.";
            Prefs.setDest(ctx, dest, g[0], g[1]);
            return "Destination set: " + dest + " — ab usi side ki rides pakadunga.";
        } catch (Exception e) {
            return fallbackReply;
        }
    }

    private static String join(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim().toLowerCase(Locale.US);
            if (!s.isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private static String listText(List<StandingOrder> orders) {
        if (orders.isEmpty()) return "koi active order nahi hai.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orders.size(); i++) {
            sb.append(i + 1).append(". ").append(orders.get(i).text).append("; ");
        }
        return sb.toString();
    }

    private static String buildPrompt(Context ctx, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tum Route Guard app ke AI commander ho — ek Indian taxi driver tumse Hinglish me baat karta hai. ");
        sb.append("Tumhe uski baat samajh ke sirf JSON me jawab dena hai, koi aur text nahi. ");
        sb.append("Actions: add_order (standing order banao), remove_order (index do), clear_orders, ");
        sb.append("list_orders, set_destination (destination field me jagah ka naam), reply (sirf jawab). ");
        sb.append("add_order me order object do: {\"text\": \"driver ke shabdon me order\", ");
        sb.append("\"direction\": \"jagah ka naam ya ''\", \"apps\": [\"rapido\",\"uber\",\"ola\",\"porter\"] ya [], ");
        sb.append("\"kinds\": [\"parcel\",\"ride\"] ya []}. ");
        sb.append("\"usi side\" / \"wahi side\" ka matlab: neeche diye context me destination ya aakhri ride ka drop. ");
        sb.append("\"parcel wali ride\" = kinds [\"parcel\"]. \"ek ride aur\" = pehle wala order dobara nahi banana, ");
        sb.append("agar waisa order pehle se active hai to reply me bata do. ");
        sb.append("reply hamesha chhoti Hinglish me likho (Latin script). ");
        sb.append("JSON format: {\"action\": \"...\", \"reply\": \"...\", \"order\": {...}, \"index\": 1, \"destination\": \"...\"}. ");
        sb.append("Context: ").append(AiAdvisor.aiContext(ctx)).append(" ");
        sb.append("Driver bola: \"").append(message.replace("\"", "")).append("\"");
        return sb.toString();
    }
}
