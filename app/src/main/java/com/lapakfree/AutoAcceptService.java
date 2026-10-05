package com.lapakfree;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Driver apps par nazar rakhta hai. Nayi ride offer aate hi:
 * 1. offer ka text padhta hai (fare, km, drop location)
 * 2. destination mode ON ho to check karta hai: ride manjil ki taraf jaa rahi hai?
 * 3. rules check karta hai
 * 4. sab fit ho to Accept dabata hai (tap ya swipe)
 */
public class AutoAcceptService extends AccessibilityService {
    private static final String TAG = "LapakFree";

    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "com.ubercab.driver", "com.ubercab.partner",
            "com.olacabs.oladriver",
            "com.rapido.rider",
            "com.theporter.android.driverapp",
            "com.indriver.driver"
    ));

    private static final String[] NOISE = {"swipe", "slide", "accept", "decline", "cancel",
            "ignore", "navigate", "online", "offline", "rating", "min", "cash", "upi"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastScan = 0;
    private String lastSig = "";
    private long lastSigTime = 0;
    private String currentApp = "";

    @Override
    public void onServiceConnected() {
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        if (!Prefs.isMasterOn(this)) return;
        if (!TARGETS.contains(pkg)) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < 1200) return; // debounce
        lastScan = now;
        currentApp = pkg.toString();
        handler.post(this::scanAndAct);
    }

    @Override
    public void onInterrupt() {}

    private void scanAndAct() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<String> texts = new ArrayList<>();
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            walk(root, texts, all);
            String joined = join(texts);
            String low = joined.toLowerCase(Locale.US);

            if (isTripScreen(low)) return; // ride already chal rahi hai

            String sig = currentApp + "#" + joined.hashCode();
            long now = System.currentTimeMillis();
            if (sig.equals(lastSig) && now - lastSigTime < 25000) return; // same offer dobara mat dekho

            if (!looksLikeOffer(low)) return;
            lastSig = sig;
            lastSigTime = now;

            Offer offer = OfferParser.parse(texts);
            offer.app = shortAppName(currentApp);

            // Destination mode: pehle direction check (background thread, network lagta hai)
            if (Prefs.isDestMode(this) && Prefs.hasDest(this)) {
                final List<String> t2 = new ArrayList<>(texts);
                final List<AccessibilityNodeInfo> a2 = new ArrayList<>(all);
                final String lowCopy = low;
                new Thread(() -> destinationCheck(t2, a2, lowCopy, offer)).start();
                return;
            }

            RuleEngine.Verdict v = RuleEngine.check(this, offer);
            if (v.accept) {
                boolean acted = pressAccept(all, low);
                HistoryStore.add(this, offer, acted ? "ACCEPT" : "ACCEPT-FAIL", v.reason);
                if (acted) {
                    buzz();
                    handler.postDelayed(this::confirmAccept, 4000);
                }
            } else {
                HistoryStore.add(this, offer, "SKIP", v.reason);
            }
            sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
        } catch (Exception e) {
            Log.w(TAG, "scan err", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Destination mode ka faisla, 2 step me:
     * 1) PICKUP paas hona chahiye (max 2km, setting se badal sakte ho)
     * 2) DROP manjil ki taraf jaana chahiye (thoda bhi kareeb to chalega)
     */
    private void destinationCheck(List<String> texts, List<AccessibilityNodeInfo> all,
                                  String low, Offer offer) {
        double[] cur = getCurrentLocation();
        if (cur == null) {
            destResult(offer, "SKIP", "location nahi mili (permission do)");
            return;
        }
        double[] dest = Prefs.getDestLatLng(this);
        if (dest == null) {
            destResult(offer, "SKIP", "destination set nahi hai");
            return;
        }

        // ---- STEP 1: pickup kitna door hai ----
        float maxPickKm = Prefs.getDestMaxPickupKm(this);
        List<String> pcands = findPickupCandidates(texts);
        double[] pick = null;
        String pickName = "";
        for (String q : pcands) {
            pick = OfflineGeo.geocode(this, q, cur);
            if (pick != null) { pickName = q; break; }
        }
        double pickKm = -1;
        if (pick != null) {
            pickKm = GeoUtil.haversine(cur[0], cur[1], pick[0], pick[1]) / 1000.0;
            if (pickKm > maxPickKm) {
                destResult(offer, "SKIP", "pickup " + round1(pickKm) + "km door hai (max "
                        + round1(maxPickKm) + "km) — " + pickName);
                return;
            }
        } else if (offer.pickupKm >= 0) {
            // screen par "X km" likha tha to usi se check karo
            if (offer.pickupKm > maxPickKm) {
                destResult(offer, "SKIP", "pickup " + offer.pickupKm + "km door hai (max "
                        + round1(maxPickKm) + "km)");
                return;
            }
            pickKm = offer.pickupKm;
            pickName = "screen par likha tha";
        } else {
            destResult(offer, "SKIP", "pickup location pata nahi chali");
            return;
        }
        final String fPickName = pickName;
        final double fPickKm = pickKm;

        // ---- STEP 2: drop manjil ki taraf? ----
        List<String> cands = findDropCandidates(texts);
        double[] drop = null;
        String usedName = "";
        for (String q : cands) {
            drop = OfflineGeo.geocode(this, q, cur);
            if (drop != null) { usedName = q; break; }
        }
        if (drop == null) {
            destResult(offer, "SKIP", "drop location padh nahi paya");
            return;
        }
        double d1 = GeoUtil.haversine(cur[0], cur[1], dest[0], dest[1]);
        double d2 = GeoUtil.haversine(drop[0], drop[1], dest[0], dest[1]);
        int pct = d1 > 50 ? (int) Math.round((d1 - d2) / d1 * 100) : 0;
        final String dropName = usedName;
        final int fpct = pct;

        if (d2 < d1) {
            // direction sahi — ab fare ke rules bhi check karo
            RuleEngine.Verdict v = RuleEngine.check(this, offer);
            if (!v.accept) {
                destResult(offer, "SKIP", "direction sahi thi, par " + v.reason);
                return;
            }
            // Google login + AI ON hai to Gemini se final faisla (aam taur par 1-2 sec).
            // AI jawab na de to purane rule se hi accept hota hai — ride atakti nahi.
            if (Prefs.isAiOn(this) && GoogleAuth.isSignedIn(this)) {
                AiAdvisor.Facts facts = new AiAdvisor.Facts();
                facts.app = offer.app;
                facts.pickupName = fPickName;
                facts.pickupKm = fPickKm;
                facts.maxPickupKm = Prefs.getDestMaxPickupKm(this);
                facts.dropName = dropName;
                facts.towardPct = fpct;
                facts.destName = Prefs.getDestName(this);
                facts.fare = offer.fare;
                facts.tripKm = offer.tripKm;
                facts.farePerKm = (offer.tripKm > 0 && offer.fare > 0)
                        ? offer.fare / offer.tripKm : -1;
                AiAdvisor.decide(this, facts, (aiAccept, reason) -> {
                    if (aiAccept == null) {
                        doDestAccept(all, low, offer, fPickName, fPickKm, fpct, dropName,
                                "rule (" + reason + ")");
                    } else if (aiAccept) {
                        doDestAccept(all, low, offer, fPickName, fPickKm, fpct, dropName, reason);
                    } else {
                        destResult(offer, "SKIP", reason + " — " + dropName);
                    }
                });
                return;
            }
            doDestAccept(all, low, offer, fPickName, fPickKm, fpct, dropName, "rule");
        } else {
            destResult(offer, "SKIP", "manjil se door jaa rahi (" + fpct + "%) — " + usedName);
        }
    }

    /** Destination-ride accept: button dabao + history likho. Main thread par chalao. */
    private void doDestAccept(List<AccessibilityNodeInfo> all, String low, Offer offer,
                              String pickName, double pickKm, int pct, String dropName, String how) {
        handler.post(() -> {
            boolean acted = pressAccept(all, low);
            HistoryStore.add(this, offer, acted ? "ACCEPT" : "ACCEPT-FAIL",
                    "pickup " + pickName + " (" + round1(pickKm) + "km), manjil ki taraf ("
                            + pct + "%) — " + dropName + " [" + how + "]");
            if (acted) {
                buzz();
                handler.postDelayed(this::confirmAccept, 4000);
            }
            sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
        });
    }

    private String round1(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private void destResult(Offer offer, String verdict, String reason) {
        handler.post(() -> {
            HistoryStore.add(this, offer, verdict, reason);
            sendBroadcast(new Intent("com.lapakfree.HISTORY_UPDATED"));
        });
    }

    /** Offer screen ke text se PICKUP location ke naam nikalta hai. */
    private List<String> findPickupCandidates(List<String> texts) {
        List<String> explicit = new ArrayList<>();
        List<String> addrLike = new ArrayList<>();
        Pattern pickPat = Pattern.compile(
                "(?i)\\b(?:pick\\s?up|from)\\b\\s*[:\\-–—>→]\\s*(.+)");
        for (String t : texts) {
            if (t == null) continue;
            String s = t.trim();
            if (s.length() < 3 || s.length() > 90) continue;
            String low = s.toLowerCase(Locale.US);
            if (low.contains("₹") || low.contains("rs.") || low.contains(" rs ")
                    || low.contains("km")) continue;
            if (low.contains("drop")) continue; // drop wali line nahi
            boolean noisy = false;
            for (String n : NOISE) {
                if (low.contains(n)) { noisy = true; break; }
            }
            if (noisy) continue;
            Matcher m = pickPat.matcher(s);
            if (m.find()) {
                String c = cleanPlace(m.group(1));
                if (c.length() >= 3) explicit.add(c);
                continue;
            }
            // arrow se PEHLE wala hissa = pickup (pickup → drop)
            int ai = s.indexOf('→');
            if (ai > 0) {
                String c = cleanPlace(s.substring(0, ai));
                if (c.length() >= 3) explicit.add(c);
                continue;
            }
            int ar = s.indexOf("->");
            if (ar > 0) {
                String c = cleanPlace(s.substring(0, ar));
                if (c.length() >= 3) explicit.add(c);
                continue;
            }
            if (s.contains(",") || (s.length() > 10 && low.matches("[a-z .,'-]+"))) {
                addrLike.add(cleanPlace(s));
            }
        }
        List<String> out = new ArrayList<>();
        for (String c : explicit) if (!out.contains(c)) out.add(c);
        // pickup aksar UPAR hota hai → address-like me pehle wali pehle
        for (String c : addrLike) {
            if (c.length() >= 3 && !out.contains(c)) out.add(c);
        }
        if (out.size() > 4) return out.subList(0, 4);
        return out;
    }

    /** Offer screen ke text se drop location ke naam nikalta hai. */
    private List<String> findDropCandidates(List<String> texts) {
        List<String> explicit = new ArrayList<>();
        List<String> addrLike = new ArrayList<>();
        Pattern dropPat = Pattern.compile(
                "(?i)\\b(?:drop(?:\\s?off)?|destination|dest|to)\\b\\s*[:\\-–—>→]\\s*(.+)");
        for (String t : texts) {
            if (t == null) continue;
            String s = t.trim();
            if (s.length() < 3 || s.length() > 90) continue;
            String low = s.toLowerCase(Locale.US);
            if (low.contains("₹") || low.contains("rs.") || low.contains(" rs ")
                    || low.contains("km")) continue;
            if (low.contains("pickup") || low.contains("pick up") || low.contains("pick-up")) continue;
            boolean noisy = false;
            for (String n : NOISE) {
                if (low.contains(n)) { noisy = true; break; }
            }
            if (noisy) continue;
            Matcher m = dropPat.matcher(s);
            if (m.find()) {
                String c = cleanPlace(m.group(1));
                if (c.length() >= 3) explicit.add(c);
                continue;
            }
            int ai = s.indexOf('→');
            if (ai > 0 && ai < s.length() - 1) {
                String c = cleanPlace(s.substring(ai + 1));
                if (c.length() >= 3) explicit.add(c);
                continue;
            }
            int ar = s.indexOf("->");
            if (ar > 0 && ar < s.length() - 2) {
                String c = cleanPlace(s.substring(ar + 2));
                if (c.length() >= 3) explicit.add(c);
                continue;
            }
            if (s.contains(",") || (s.length() > 10 && low.matches("[a-z .,'-]+"))) {
                addrLike.add(cleanPlace(s));
            }
        }
        List<String> out = new ArrayList<>();
        for (String c : explicit) if (!out.contains(c)) out.add(c);
        // address jaisi lines ulta: neeche wali pehle (drop aksar neeche hota hai)
        for (int i = addrLike.size() - 1; i >= 0; i--) {
            String c = addrLike.get(i);
            if (c.length() >= 3 && !out.contains(c)) out.add(c);
        }
        if (out.size() > 4) return out.subList(0, 4);
        return out;
    }

    private String cleanPlace(String s) {
        s = s.trim();
        int pipe = s.indexOf('|');
        if (pipe > 0) s = s.substring(0, pipe).trim();
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

    /** Accept dabne ke 4 sec baad check karo ki ride lagi ya nahi. */
    private void confirmAccept() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<String> texts = new ArrayList<>();
            walk(root, texts, new ArrayList<AccessibilityNodeInfo>());
            String low = join(texts).toLowerCase(Locale.US);
            if (isTripScreen(low)) {
                Log.i(TAG, "ride confirmed on trip screen");
            }
        } catch (Exception ignored) {
        } finally {
            root.recycle();
        }
    }

    private boolean looksLikeOffer(String low) {
        boolean hasRupee = low.contains("₹") || low.contains("rs.") || low.contains("rs ");
        boolean hasKm = low.contains("km");
        boolean hasAcceptWord = low.contains("accept") || low.contains("swipe") || low.contains("slide");
        return (hasRupee && (hasKm || hasAcceptWord)) || (hasAcceptWord && hasKm);
    }

    private boolean isTripScreen(String low) {
        return low.contains("navigate") || low.contains("complete trip") || low.contains("end trip")
                || low.contains("drop off") || low.contains("arrived at pickup")
                || low.contains("cancel trip") || low.contains("trip started");
    }

    /** Accept button dhoondh ke dabao. Swipe-slider ho to swipe karo. */
    private boolean pressAccept(List<AccessibilityNodeInfo> all, String low) {
        AccessibilityNodeInfo best = null;
        boolean swipeMode = low.contains("swipe") || low.contains("slide");
        for (AccessibilityNodeInfo n : all) {
            String t = nodeText(n).toLowerCase(Locale.US);
            if (t.contains("accept") || t.contains("swipe") || t.contains("slide")) {
                if (t.length() < 40) { best = n; break; }
                if (best == null) best = n;
            }
        }
        if (best == null) return false;
        try {
            if (swipeMode) return swipeAcross(best);
            if (best.isClickable() && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo p = best.getParent();
            int depth = 0;
            while (p != null && depth < 4) {
                if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                p = p.getParent();
                depth++;
            }
            return tapNode(best);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tapNode(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        final boolean[] done = {false};
        dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { done[0] = true; }
        }, null);
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        return done[0];
    }

    private boolean swipeAcross(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        Path p = new Path();
        int y = r.centerY();
        p.moveTo(r.left + 30, y);
        p.lineTo(r.right - 30, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 450)).build();
        final boolean[] done = {false};
        dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { done[0] = true; }
        }, null);
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        return done[0];
    }

    private void walk(AccessibilityNodeInfo n, List<String> texts, List<AccessibilityNodeInfo> all) {
        if (n == null) return;
        all.add(n);
        CharSequence t = n.getText();
        if (t != null && t.length() > 0 && t.length() < 200) texts.add(t.toString().trim());
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0 && d.length() < 200) texts.add(d.toString().trim());
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) { walk(c, texts, all); }
        }
    }

    private String nodeText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        CharSequence t = n.getText();
        if (t != null) sb.append(t);
        CharSequence d = n.getContentDescription();
        if (d != null) sb.append(' ').append(d);
        return sb.toString().trim();
    }

    private String join(List<String> texts) {
        StringBuilder sb = new StringBuilder();
        for (String s : texts) sb.append(s).append(" | ");
        return sb.toString();
    }

    private String shortAppName(String pkg) {
        if (pkg.contains("uber")) return "Uber";
        if (pkg.contains("ola")) return "Ola";
        if (pkg.contains("rapido")) return "Rapido";
        if (pkg.contains("porter")) return "Porter";
        if (pkg.contains("indriver")) return "inDrive";
        return pkg;
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(200);
            }
        } catch (Exception ignored) {}
    }
}
