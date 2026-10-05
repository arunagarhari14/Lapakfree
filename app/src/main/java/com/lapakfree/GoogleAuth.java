package com.lapakfree;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Google Sign-In — bina kisi SDK ke, seedha OAuth2 + PKCE se. (v2.0)
 *
 * Har DRIVER apne Google account se login karta hai. Login ke time AI wali
 * permission (generative-language scope) bhi li jati hai, isliye AI us
 * driver ke APNE free quota se chalta hai — koi shared key/project nahi,
 * developer ka account kahin involve nahi.
 *
 * access_token 1 ghante me expire hota hai; refresh_token se chup-chaap
 * naya token le liya jata hai (user ko dobara login nahi karna padta).
 */
public class GoogleAuth {
    private static final String TAG = "LapakFree";

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REDIRECT_URI = "com.lapakfree:/oauth2callback";
    /** identity + Gemini Developer API (driver ke apne free quota se — peruserquota scope) */
    private static final String SCOPE =
            "openid email profile https://www.googleapis.com/auth/generative-language.peruserquota";

    /** access token expiry se itne pehle refresh kar lo */
    private static final long REFRESH_SKEW_MS = 120_000;

    public interface AuthCallback {
        void onResult(boolean ok, String msg);
    }

    /** OAuth client ID — app ke dedicated Google account ke Cloud project se. */
    public static String oauthClientId(Context c) {
        try {
            int id = c.getResources().getIdentifier(
                    "google_oauth_client_id", "string", c.getPackageName());
            if (id != 0) {
                String s = c.getString(id);
                if (s != null && !s.isEmpty() && !s.contains("PLACEHOLDER")) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean isOAuthReady(Context c) {
        return oauthClientId(c) != null;
    }

    public static boolean isSignedIn(Context c) {
        String e = Prefs.getLoginEmail(c);
        return e != null && !e.isEmpty();
    }

    public static String userEmail(Context c) {
        return Prefs.getLoginEmail(c);
    }

    /** AI call kar sakte hain? (login + AI scope wala token maujood/refreshable) */
    public static boolean isAiReady(Context c) {
        if (!isSignedIn(c)) return false;
        String[] t = Prefs.getOauthTokens(c);
        return t != null && t[1] != null && !t[1].isEmpty(); // refresh token hai
    }

    /** Login shuru: browser khulta hai. Wapasi onNewIntent/onCreate me aati hai. */
    public static void startSignIn(Activity a) {
        String clientId = oauthClientId(a);
        if (clientId == null) {
            toast(a, "Google setup baaki hai — thodi der me dobara try karo");
            return;
        }
        try {
            String verifier = randomString(64);
            String challenge = pkceChallenge(verifier);
            String state = randomString(24);
            Prefs.setOauthState(a, state, verifier);

            String url = AUTH_URL
                    + "?client_id=" + enc(clientId)
                    + "&redirect_uri=" + enc(REDIRECT_URI)
                    + "&response_type=code"
                    + "&scope=" + enc(SCOPE)
                    + "&code_challenge=" + enc(challenge)
                    + "&code_challenge_method=S256"
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&state=" + enc(state);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            a.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "signin start fail", e);
            toast(a, "Browser nahi khul paya");
        }
    }

    /**
     * OAuth callback: MainActivity.onNewIntent/onCreate se call karo.
     * @return true agar yeh hamara oauth callback tha (chahe success ho ya fail)
     */
    public static boolean handleCallback(Activity a, Intent intent, AuthCallback cb) {
        Uri uri = intent != null ? intent.getData() : null;
        if (uri == null || !"com.lapakfree".equals(uri.getScheme())) return false;

        String err = uri.getQueryParameter("error");
        if (err != null) {
            cb.onResult(false, "Google login cancel/fail ho gaya");
            return true;
        }
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        String[] saved = Prefs.getOauthState(a);
        if (code == null || saved == null || !saved[0].equals(state)) {
            cb.onResult(false, "Login verify nahi hua — dobara try karo");
            return true;
        }
        Prefs.clearOauthState(a);
        new Thread(() -> {
            try {
                JSONObject tok = exchangeCode(a, code, saved[1]);
                saveTokens(a, tok);
                String idToken = tok.optString("id_token", "");
                String email = emailFromIdToken(idToken);
                String name = nameFromIdToken(idToken);
                if (email.isEmpty()) throw new Exception("email nahi mila");
                Prefs.setLogin(a, email, name);
                runOnUi(a, () -> cb.onResult(true,
                        "Login ho gaya: " + email + " — AI tumhare free quota se connect ho gaya"));
            } catch (Exception e) {
                Log.w(TAG, "token exchange fail", e);
                runOnUi(a, () -> cb.onResult(false, "Login poora nahi hua — dobara try karo"));
            }
        }).start();
        return true;
    }

    /**
     * AI call ke liye valid access token. Hamesha background thread se call karo.
     * @return token ya null (login nahi / refresh fail)
     */
    public static String getValidAccessToken(Context c) {
        try {
            String[] t = Prefs.getOauthTokens(c);
            if (t == null) return null;
            String access = t[0];
            long exp = Long.parseLong(t[2]);
            if (access != null && !access.isEmpty()
                    && System.currentTimeMillis() < exp - REFRESH_SKEW_MS) {
                return access;
            }
            // refresh karo
            String refresh = t[1];
            if (refresh == null || refresh.isEmpty()) return null;
            JSONObject tok = refreshAccessToken(c, refresh);
            saveTokens(c, tok);
            return tok.optString("access_token", null);
        } catch (Exception e) {
            Log.w(TAG, "getValidAccessToken fail", e);
            return null;
        }
    }

    /** 401 aaye to token saaf karo — user ko dobara login karna hoga. */
    public static void clearTokens(Context c) {
        Prefs.clearOauthTokens(c);
    }

    public static void signOut(Context c) {
        Prefs.clearLogin(c);
        Prefs.clearOauthTokens(c);
    }

    // ---------- private ----------

    private static void saveTokens(Context c, JSONObject tok) {
        String access = tok.optString("access_token", "");
        String refresh = tok.optString("refresh_token", "");
        long expiresIn = tok.optLong("expires_in", 3600);
        long expAt = System.currentTimeMillis() + expiresIn * 1000;
        // refresh response me refresh_token nahi aata — purana rakho
        if (refresh.isEmpty()) {
            String[] old = Prefs.getOauthTokens(c);
            if (old != null && old[1] != null) refresh = old[1];
        }
        Prefs.saveOauthTokens(c, access, refresh, expAt);
    }

    private static JSONObject exchangeCode(Context c, String code, String verifier) throws Exception {
        String body = "code=" + enc(code)
                + "&client_id=" + enc(oauthClientId(c))
                + "&code_verifier=" + enc(verifier)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&grant_type=authorization_code";
        return postToken(body);
    }

    private static JSONObject refreshAccessToken(Context c, String refreshToken) throws Exception {
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(oauthClientId(c));
        return postToken(body);
    }

    private static JSONObject postToken(String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            String resp = readAll(con);
            if (con.getResponseCode() != 200) throw new Exception("token http " + con.getResponseCode());
            return new JSONObject(resp);
        } finally {
            con.disconnect();
        }
    }

    private static String emailFromIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return "";
            String payload = new String(Base64.decode(parts[1],
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), StandardCharsets.UTF_8);
            return new JSONObject(payload).optString("email", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String nameFromIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return "";
            String payload = new String(Base64.decode(parts[1],
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(payload);
            String n = o.optString("name", "");
            return n.isEmpty() ? o.optString("email", "") : n;
        } catch (Exception e) {
            return "";
        }
    }

    private static String pkceChallenge(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(d, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String randomString(int len) {
        SecureRandom r = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String readAll(HttpURLConnection con) throws Exception {
        InputStream in = con.getResponseCode() < 400 ? con.getInputStream() : con.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static void runOnUi(Activity a, Runnable r) {
        a.runOnUiThread(r);
    }

    private static void toast(Context c, String s) {
        android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
