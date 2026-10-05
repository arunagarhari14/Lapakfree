package com.lapakfree;

import android.Manifest;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Driver apps ke NOTIFICATION aate hi turant react karta hai — screen khulne ka
 * wait nahi karta, isliye 1-2 second ka delay nahi hai.
 *
 * Notification me se pickup/drop padh ke OFFLINE map se % nikaalta hai
 * (yeh local math hai, millisecond me hota hai):
 *   +65% = manjil ki taraf 65% pass jayegi
 *   -40% = manjil se 40% door jayegi
 *
 * Phir mode ke hisaab se:
 *   manual → floating card: "% pass/door" + Accept/Skip button
 *   auto   → % threshold par turant accept (notification ke accept action se)
 *
 * Ek se zyada notifications aayen to sab par trigger hota hai — har ride ka
 * apna card, apna %.
 */
public class RideNotifyService extends NotificationListenerService {
    private static final String TAG = "LapakFree";

    private static final Set<String> DRIVER_PKGS = new HashSet<>(Arrays.asList(
            "com.ubercab.driver", "com.ubercab.partner",
            "com.olacabs.oladriver",
            "com.rapido.rider",
            "com.theporter.android.driverapp",
            "com.indriver.driver"
    ));

    private static final String[] RIDE_WORDS = {"ride", "trip", "booking", "request",
            "pickup", "pick up", "new order", "order request", "trip request"};
    private static final String[] NOT_RIDE = {"completed", "cancelled", "canceled", "payment",
            "rating", "rate your", "arrived", "otp", "reached", "invoice", "receipt",
            "you're online", "you are online", "offline", "incentive", "offer for you"};

    /** key → notification (accept action dabane ke liye rakha hai) */
    private static final ConcurrentHashMap<String, StatusBarNotification> LIVE =
            new ConcurrentHashMap<>();
    static RideNotifyService instance;

    private RideCardOverlay overlay;

    @Override
    public void onListenerConnected() {
        instance = this;
        overlay = new RideCardOverlay(this, new RideCardOverlay.AcceptHandler() {
            @Override public boolean onAccept(String key, RideCardOverlay.CardData d) {
                boolean fired = tryAccept(key);
                Offer o = new Offer();
                o.app = d.app != null ? d.app : "";
                HistoryStore.add(RideNotifyService.this, o,
                        fired ? "ACCEPT" : "ACCEPT-FAIL",
                        (d.line1 != null ? d.line1 : "") + " [card se turant]");
                sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
                if (fired) LIVE.remove(key);
                return fired;
            }
            @Override public void onSkip(String key, RideCardOverlay.CardData d) {
                Offer o = new Offer();
                o.app = d.app != null ? d.app : "";
                HistoryStore.add(RideNotifyService.this, o, "SKIP",
                        (d.line1 != null ? d.line1 : "") + " [card se]");
                sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
                LIVE.remove(key);
            }
        });
        if (!overlay.init()) overlay = null;
        Log.i(TAG, "notification listener connected");
    }

    @Override
    public void onDestroy() {
        if (overlay != null) { overlay.destroy(); overlay = null; }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!Prefs.isMasterOn(this) || !Prefs.isNotifAlertsOn(this)) return;
        if (sbn == null || sbn.getNotification() == null) return;
        String pkg = sbn.getPackageName();
        if (!DRIVER_PKGS.contains(pkg)) return;

        String title = textOf(sbn, Notification.EXTRA_TITLE);
        String text = textOf(sbn, Notification.EXTRA_TEXT);
        String big = textOf(sbn, Notification.EXTRA_BIG_TEXT);
        String sub = textOf(sbn, Notification.EXTRA_SUB_TEXT);
        String low = (title + " " + text + " " + big + " " + sub).toLowerCase(Locale.US);
        if (!looksLikeRideRequest(low)) return;

        String key = sbn.getKey();
        LIVE.put(key, sbn);
        boolean hasAcceptAction = findAcceptAction(sbn) != null;

        new Thread(() -> handleRide(key, pkg, title, text, big, hasAcceptAction)).start();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        LIVE.remove(sbn.getKey());
        if (overlay != null) overlay.removeCard(sbn.getKey());
    }

    /** Notification ke accept action ko dabao. True = dab gaya. */
    public static boolean tryAccept(String key) {
        try {
            StatusBarNotification sbn = LIVE.get(key);
            if (sbn == null) return false;
            Notification.Action act = findAcceptAction(sbn);
            if (act == null || act.actionIntent == null) return false;
            act.actionIntent.send();
            Log.i(TAG, "accept action fired for " + key);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "accept action fail", e);
            return false;
        }
    }

    private static Notification.Action findAcceptAction(StatusBarNotification sbn) {
        try {
            Notification.Action[] acts = sbn.getNotification().actions;
            if (acts == null) return null;
            for (Notification.Action a : acts) {
                CharSequence t = a.title;
                if (t != null && t.toString().toLowerCase(Locale.US).contains("accept")) return a;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void handleRide(String key, String pkg, String title, String text,
                            String big, boolean hasAcceptAction) {
        String app = shortAppName(pkg);
        List<String> tl = new ArrayList<>();
        if (!title.isEmpty()) tl.add(title);
        if (!text.isEmpty()) tl.add(text);
        if (!big.isEmpty()) tl.add(big);

        // fare/km nikaalo (wahi parser jo screen ke liye hai)
        Offer offer = OfferParser.parse(tl);
        offer.app = app;

        String dropName = extractDropName(tl);
        String pickName = extractPickName(tl);
        String routeLine = buildRouteLine(pickName, dropName, offer);

        // % turant — sab local (offline map + math, millisecond me)
        Integer pct = null;
        double[] cur = getCurrentLocation();
        double[] dest = Prefs.getDestLatLng(this);
        double[] dropLL = null;
        if (cur != null && dropName != null) {
            dropLL = OfflineGeo.geocode(this, dropName, cur);
            if (dropLL != null && dest != null) {
                pct = GeoUtil.towardPct(cur[0], cur[1], dropLL[0], dropLL[1], dest[0], dest[1]);
            }
        }
        final Integer fpct = pct;

        // AI Commander ke standing orders — sabse pehle, LOCAL turant match.
        // Driver ne bola tha "aisi ride aaye to accept kar le" = standing permission.
        String notifAll = title + " " + text + " " + big;
        StandingOrder hit = OrderMatcher.match(this, app, dropLL, cur, notifAll);
        if (hit != null) {
            boolean fired = tryAccept(key);
            HistoryStore.add(this, offer, fired ? "ACCEPT" : "ACCEPT-FAIL",
                    routeLine + " [AI order: " + hit.text + "]");
            sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
            if (fired) {
                LIVE.remove(key);
                return; // order pura ho gaya
            }
            // accept action nahi mila → card dikhao taaki driver khud dabaye
            if (overlay != null) {
                RideCardOverlay.CardData d = new RideCardOverlay.CardData();
                d.key = key;
                d.app = app;
                d.line1 = routeLine;
                d.pct = fpct != null ? fpct : Integer.MIN_VALUE;
                d.hasAccept = hasAcceptAction;
                d.dest = Prefs.getDestName(this);
                overlay.showCard(d);
                overlay.updateNote(key, "AI order: " + hit.text + " — khud Accept dabao");
            }
            return;
        }

        String destName = Prefs.getDestName(this);
        boolean auto = "auto".equals(Prefs.getAlertMode(this));

        if (auto && fpct != null && fpct >= Prefs.getAutoPct(this)) {
            // TURANT auto-accept — AI ka wait nahi, notification action se
            boolean fired = tryAccept(key);
            HistoryStore.add(this, offer, fired ? "ACCEPT" : "ACCEPT-FAIL",
                    routeLine + " • " + pctText(fpct)
                            + (fired ? " [turant auto]" : " [turant auto — action nahi mila]"));
            sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
            if (fired) {
                LIVE.remove(key);
                return; // accept ho gaya, card ki zaroorat nahi
            }
            // action nahi mila to neeche card dikhega taaki driver khud dabaye
        }

        // Manual mode, ya auto me action nahi mila → % card dikhao
        if (overlay != null) {
            RideCardOverlay.CardData d = new RideCardOverlay.CardData();
            d.key = key;
            d.app = app;
            d.line1 = routeLine;
            d.pct = fpct != null ? fpct : Integer.MIN_VALUE;
            d.hasAccept = hasAcceptAction;
            d.dest = destName != null ? destName : "";
            overlay.showCard(d);
        }

        // AI parallel me — card par hint update hoga, faisla nahi rokega
        if (fpct != null && Prefs.isAiOn(this) && GoogleAuth.isSignedIn(this)) {
            AiAdvisor.Facts f = new AiAdvisor.Facts();
            f.app = app;
            f.pickupName = pickName != null ? pickName : "";
            f.pickupKm = offer.pickupKm;
            f.dropName = dropName != null ? dropName : "";
            f.towardPct = fpct;
            f.destName = destName != null ? destName : "";
            f.fare = offer.fare;
            f.tripKm = offer.tripKm;
            f.farePerKm = (offer.tripKm > 0 && offer.fare > 0) ? offer.fare / offer.tripKm : -1;
            AiAdvisor.decide(this, f, (aiAccept, reason) -> {
                if (aiAccept != null && overlay != null) overlay.updateNote(key, reason);
            });
        }
    }

    private String pctText(int pct) {
        return pct >= 0 ? pct + "% pass jayegi" : (-pct) + "% door jayegi";
    }

    private boolean looksLikeRideRequest(String low) {
        boolean hit = false;
        for (String w : RIDE_WORDS) {
            if (low.contains(w)) { hit = true; break; }
        }
        if (!hit) return false;
        for (String w : NOT_RIDE) {
            if (low.contains(w)) return false;
        }
        return true;
    }

    private String textOf(StatusBarNotification sbn, String key) {
        try {
            Bundle b = sbn.getNotification().extras;
            CharSequence cs = b.getCharSequence(key);
            return cs != null ? cs.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** "A → B" ya "to B" / "drop B" se drop ka naam nikaalo. */
    private String extractDropName(List<String> texts) {
        Pattern arrow = Pattern.compile("→\\s*(.+)|->\\s*(.+)");
        Pattern toPat = Pattern.compile(
                "(?i)\\b(?:drop(?:\\s?off)?|destination|dest|to)\\b\\s*[:\\-–—>→]?\\s*(.+)");
        for (String t : texts) {
            String s = t.trim();
            if (s.length() < 3 || s.length() > 120) continue;
            Matcher m = arrow.matcher(s);
            if (m.find()) {
                String c = clean(m.group(1) != null ? m.group(1) : m.group(2));
                if (c.length() >= 3) return c;
            }
            Matcher m2 = toPat.matcher(s);
            if (m2.find()) {
                String c = clean(m2.group(1));
                if (c.length() >= 3 && !c.contains("₹")) return c;
            }
        }
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = texts.get(i).trim();
            if (s.length() > 8 && s.length() < 90 && !s.contains("₹")
                    && s.toLowerCase(Locale.US).matches("[a-z .,'-]+")) {
                return clean(s);
            }
        }
        return null;
    }

    private String extractPickName(List<String> texts) {
        Pattern pickPat = Pattern.compile(
                "(?i)\\b(?:pick\\s?up|from)\\b\\s*[:\\-–—]?\\s*(.+)");
        for (String t : texts) {
            String s = t.trim();
            if (s.length() < 3 || s.length() > 120) continue;
            int ai = s.indexOf('→');
            if (ai > 0) {
                String c = clean(s.substring(0, ai));
                if (c.length() >= 3) return c;
            }
            Matcher m = pickPat.matcher(s);
            if (m.find()) {
                String c = clean(m.group(1));
                if (c.length() >= 3) return c;
            }
        }
        return null;
    }

    private String buildRouteLine(String pick, String drop, Offer offer) {
        StringBuilder sb = new StringBuilder();
        if (pick != null) sb.append(pick).append(" → ");
        sb.append(drop != null ? drop : "ride aayi hai");
        if (offer.fare > 0) sb.append(" • ₹").append((int) offer.fare);
        if (offer.tripKm > 0) sb.append(" • ").append(offer.tripKm).append("km");
        if (offer.pickupKm >= 0) sb.append(" • pickup ").append(offer.pickupKm).append("km");
        return sb.toString();
    }

    private String clean(String s) {
        s = s.trim();
        int pipe = s.indexOf('|');
        if (pipe > 0) s = s.substring(0, pipe).trim();
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl).trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
    }

    private double[] getCurrentLocation() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return null;
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            if (l == null) return null;
            return new double[]{l.getLatitude(), l.getLongitude()};
        } catch (Exception e) {
            return null;
        }
    }

    private String shortAppName(String pkg) {
        if (pkg.contains("uber")) return "Uber";
        if (pkg.contains("ola")) return "Ola";
        if (pkg.contains("rapido")) return "Rapido";
        if (pkg.contains("porter")) return "Porter";
        if (pkg.contains("indriver")) return "inDrive";
        return pkg;
    }
}
