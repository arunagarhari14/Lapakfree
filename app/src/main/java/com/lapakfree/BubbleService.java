package com.lapakfree;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Driver ke kaam ke time screen par chhota ON/OFF bubble.
 * Tap = chalu/band, drag = idhar-udhar karo.
 */
public class BubbleService extends Service {
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private BroadcastReceiver toggleReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setTextSize(14);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(0xFFFFFFFF);
        bubble.setPadding(28, 28, 28, 28);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 300;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = params.x; startY = params.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                        params.x = startX + dx; params.y = startY + dy;
                        wm.updateViewLayout(bubble, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggleMaster();
                        return true;
                }
                return false;
            }
        });

        toggleReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { refresh(); }
        };
        IntentFilter tf = new IntentFilter("com.lapakfree.MASTER_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, tf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(toggleReceiver, tf);
        }

        refresh();
        wm.addView(bubble, params);
    }

    private void toggleMaster() {
        boolean on = !Prefs.isMasterOn(this);
        Prefs.setMasterOn(this, on);
        refresh();
        sendBroadcast(new Intent("com.lapakfree.MASTER_CHANGED"));
    }

    private void refresh() {
        if (bubble == null) return;
        boolean on = Prefs.isMasterOn(this);
        bubble.setText(on ? "ON" : "OFF");
        bubble.setBackgroundColor(on ? 0xFF018750 : 0xFFC0392B);
    }

    private void startFg() {
        String ch = "lapakfree_bubble";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "Lapak Free", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new Notification.Builder(this, ch)
                .setContentTitle("Lapak Free chal raha hai")
                .setContentText("Bubble se ON/OFF karo")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, n);
    }

    @Override
    public void onDestroy() {
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        try { unregisterReceiver(toggleReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
}
