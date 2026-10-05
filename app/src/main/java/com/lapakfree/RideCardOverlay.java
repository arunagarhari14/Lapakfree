package com.lapakfree;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Ride ki notification aate hi turant floating card:
 *   "Ola • Karol Bagh → Lajpat Nagar • ₹180"
 *   "65% PASS jayegi"  (hara)  ya  "40% DOOR jayegi" (laal)
 *   [ACCEPT] [SKIP]
 *
 * Ek se zyada rides aayen to card stack hote hain — har ride par alag card,
 * har card par apna % (trigger sab par hota hai).
 *
 * Yeh Service nahi hai — RideNotifyService (jo system se hamesha chalta hai)
 * isko own karta hai, isliye foreground-service ki zaroorat nahi.
 */
public class RideCardOverlay {
    private static final String TAG = "LapakFree";
    private static final long CARD_LIFE_MS = 30000;
    private static final int MAX_CARDS = 4;

    public static class CardData {
        public String key, app, line1, dest;
        public int pct = Integer.MIN_VALUE; // MIN_VALUE = pata nahi chala
        public boolean hasAccept;
    }

    public interface AcceptHandler {
        /** @return true agar accept action dab gaya */
        boolean onAccept(String key, CardData data);
        void onSkip(String key, CardData data);
    }

    private final Context ctx;
    private final AcceptHandler acceptHandler;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout stack;
    private final Map<String, View> cards = new HashMap<>();
    private final Map<String, TextView> noteViews = new HashMap<>();
    private final Map<String, CardData> datas = new HashMap<>();

    public RideCardOverlay(Context ctx, AcceptHandler acceptHandler) {
        this.ctx = ctx;
        this.acceptHandler = acceptHandler;
    }

    /** @return false agar overlay permission nahi hai */
    public boolean init() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "overlay permission nahi — cards band");
            return false;
        }
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            stack = new LinearLayout(ctx);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setPadding(16, 16, 16, 16);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = 40;
            wm.addView(stack, p);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "overlay add fail", e);
            return false;
        }
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        try { if (stack != null && wm != null) wm.removeView(stack); } catch (Exception ignored) {}
        cards.clear();
        noteViews.clear();
        datas.clear();
        stack = null;
    }

    public boolean isReady() {
        return stack != null;
    }

    private void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else handler.post(r);
    }

    public void showCard(CardData d) {
        onMain(() -> showCardMain(d));
    }

    private void showCardMain(CardData d) {
        if (stack == null) return;
        removeCard(d.key);
        if (cards.size() >= MAX_CARDS) {
            removeCard(cards.keySet().iterator().next());
        }
        datas.put(d.key, d);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(24, 20, 24, 20);
        card.setBackgroundColor(0xF01F2937);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cp);

        TextView t1 = new TextView(ctx);
        t1.setText((d.app != null ? d.app : "Ride") + " • " + (d.line1 != null ? d.line1 : ""));
        t1.setTextColor(0xFFD1D5DB);
        t1.setTextSize(13);
        card.addView(t1);

        TextView tPct = new TextView(ctx);
        if (d.pct == Integer.MIN_VALUE) {
            tPct.setText(d.dest != null && !d.dest.isEmpty()
                    ? "Location samajh nahi aayi"
                    : "Destination set karo taaki % dikhe");
            tPct.setTextColor(0xFFFBBF24);
        } else if (d.pct >= 0) {
            tPct.setText(d.pct + "% PASS jayegi");
            tPct.setTextColor(0xFF4ADE80);
        } else {
            tPct.setText((-d.pct) + "% DOOR jayegi");
            tPct.setTextColor(0xFFF87171);
        }
        tPct.setTextSize(22);
        tPct.setTypeface(tPct.getTypeface(), Typeface.BOLD);
        tPct.setPadding(0, 8, 0, 4);
        card.addView(tPct);

        TextView tNote = new TextView(ctx);
        tNote.setTextSize(12);
        tNote.setTextColor(0xFF93C5FD);
        tNote.setVisibility(View.GONE);
        card.addView(tNote);
        noteViews.put(d.key, tNote);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 12, 0, 0);

        Button accept = new Button(ctx);
        accept.setText(d.hasAccept ? "ACCEPT" : "APP KHOLO");
        accept.setTextColor(0xFFFFFFFF);
        accept.setBackgroundColor(0xFF018750);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp.setMargins(0, 0, 8, 0);
        accept.setLayoutParams(bp);
        accept.setOnClickListener(v -> {
            boolean fired = acceptHandler.onAccept(d.key, d);
            if (fired) {
                tPct.setText("ACCEPT ho gaya ✓");
                tPct.setTextColor(0xFF4ADE80);
                handler.postDelayed(() -> removeCard(d.key), 1500);
            } else {
                Toast.makeText(ctx, "Driver app kholo — wahan accept dabao",
                        Toast.LENGTH_SHORT).show();
                try {
                    Intent li = ctx.getPackageManager().getLaunchIntentForPackage(
                            pkgForApp(d.app));
                    if (li != null) {
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(li);
                    }
                } catch (Exception ignored) {}
            }
        });
        row.addView(accept);

        Button skip = new Button(ctx);
        skip.setText("SKIP");
        skip.setTextColor(0xFFFFFFFF);
        skip.setBackgroundColor(0xFF6B7280);
        skip.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        skip.setOnClickListener(v -> {
            acceptHandler.onSkip(d.key, d);
            removeCard(d.key);
        });
        row.addView(skip);
        card.addView(row);

        stack.addView(card, 0);
        cards.put(d.key, card);
        handler.postDelayed(() -> removeCard(d.key), CARD_LIFE_MS);
    }

    public void updateNote(String key, String note) {
        onMain(() -> {
            TextView nv = noteViews.get(key);
            if (nv == null) return;
            nv.setText(note != null ? note : "");
            nv.setVisibility(note != null && !note.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    public void removeCard(String key) {
        onMain(() -> removeCardMain(key));
    }

    private void removeCardMain(String key) {
        if (key == null) return;
        View v = cards.remove(key);
        noteViews.remove(key);
        datas.remove(key);
        if (v != null && stack != null) {
            try { stack.removeView(v); } catch (Exception ignored) {}
        }
    }

    private String pkgForApp(String app) {
        if (app == null) return "";
        if (app.contains("Uber")) return "com.ubercab.driver";
        if (app.contains("Ola")) return "com.olacabs.oladriver";
        if (app.contains("Rapido")) return "com.rapido.rider";
        if (app.contains("Porter")) return "com.theporter.android.driverapp";
        if (app.contains("inDrive")) return "com.indriver.driver";
        return "";
    }
}
