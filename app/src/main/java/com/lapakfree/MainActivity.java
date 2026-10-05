package com.lapakfree;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Ek hi simple screen: ON/OFF, rules, destination, permissions, history. */
public class MainActivity extends Activity {

    private TextView statusText, historyText, destStatus, loginStatus, instrStatus, ordersStatusMain;
    private Button toggleBtn, accessBtn, overlayBtn, batteryBtn, locBtn, setDestBtn, googleLoginBtn, notifBtn, saveInstrBtn, commanderBtn;
    private EditText minFareInput, maxPickupInput, minTripInput, destInput, destPickupInput, autoPctInput, aiInstrInput;
    private CheckBox unknownFareCheck, destModeCheck, aiCheck, notifAlertsCheck, autoAcceptCheck;
    // v3.0: 4 pages + bottom nav
    private android.view.View[] pages;
    private Button[] navBtns;
    private int currentPage = 0;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshAll(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        historyText = findViewById(R.id.historyText);
        toggleBtn = findViewById(R.id.toggleBtn);
        accessBtn = findViewById(R.id.accessBtn);
        overlayBtn = findViewById(R.id.overlayBtn);
        batteryBtn = findViewById(R.id.batteryBtn);
        locBtn = findViewById(R.id.locBtn);
        minFareInput = findViewById(R.id.minFareInput);
        maxPickupInput = findViewById(R.id.maxPickupInput);
        minTripInput = findViewById(R.id.minTripInput);
        unknownFareCheck = findViewById(R.id.unknownFareCheck);
        destInput = findViewById(R.id.destInput);
        destPickupInput = findViewById(R.id.destPickupInput);
        setDestBtn = findViewById(R.id.setDestBtn);
        destStatus = findViewById(R.id.destStatus);
        destModeCheck = findViewById(R.id.destModeCheck);
        googleLoginBtn = findViewById(R.id.googleLoginBtn);
        loginStatus = findViewById(R.id.loginStatus);
        aiCheck = findViewById(R.id.aiCheck);

        googleLoginBtn.setOnClickListener(v -> {
            if (GoogleAuth.isSignedIn(this)) {
                GoogleAuth.signOut(this);
                toast("Logout ho gaya — ab rules se kaam chalega");
                refreshAll();
            } else {
                GoogleAuth.startSignIn(this);
            }
        });

        aiCheck.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAiOn(this, checked);
            toast(checked ? "AI faisla ON" : "AI faisla OFF — sirf rules chalenge");
        });

        notifBtn = findViewById(R.id.notifBtn);
        notifAlertsCheck = findViewById(R.id.notifAlertsCheck);
        autoAcceptCheck = findViewById(R.id.autoAcceptCheck);
        autoPctInput = findViewById(R.id.autoPctInput);
        aiInstrInput = findViewById(R.id.aiInstrInput);
        saveInstrBtn = findViewById(R.id.saveInstrBtn);
        instrStatus = findViewById(R.id.instrStatus);
        commanderBtn = findViewById(R.id.commanderBtn);
        ordersStatusMain = findViewById(R.id.ordersStatusMain);

        // v3.0: bottom navigation — 4 alag pages
        pages = new android.view.View[]{
                findViewById(R.id.pageHome), findViewById(R.id.pageAi),
                findViewById(R.id.pageHistory), findViewById(R.id.pageSettings)};
        navBtns = new Button[]{
                findViewById(R.id.navHome), findViewById(R.id.navAi),
                findViewById(R.id.navHistory), findViewById(R.id.navSettings)};
        for (int i = 0; i < navBtns.length; i++) {
            final int idx = i;
            navBtns[i].setOnClickListener(v -> switchPage(idx));
        }
        updateNavStyles();
        animPress(toggleBtn);
        animPress(commanderBtn);
        animPress(googleLoginBtn);

        commanderBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, CommanderActivity.class));
        });

        saveInstrBtn.setOnClickListener(v -> {
            String s = aiInstrInput.getText().toString().trim();
            Prefs.setAiInstructions(this, s);
            toast(s.isEmpty() ? "Nirdesh hata diye" : "Nirdesh save ho gaye — AI ab inhe maanega");
            refreshAll();
        });

        notifBtn.setOnClickListener(v -> {
            if (isNotificationListenerOn()) {
                toast("Notification access already hai");
            } else {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception e) {
                    toast("Settings me Notification access do");
                }
            }
        });

        notifAlertsCheck.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setNotifAlertsOn(this, checked);
            toast(checked ? "Turant alerts ON" : "Turant alerts OFF");
        });

        autoAcceptCheck.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAlertMode(this, checked ? "auto" : "manual");
            String ap = autoPctInput.getText().toString().trim();
            if (!ap.isEmpty()) {
                try {
                    int pct = Integer.parseInt(ap);
                    if (pct >= 0 && pct <= 100) Prefs.setAutoPct(this, pct);
                } catch (Exception ignored) {}
            }
            toast(checked ? "Auto-accept ON — % par turant accept hoga"
                    : "Manual mode — har ride par % card aayega");
        });

        toggleBtn.setOnClickListener(v -> {
            boolean on = !Prefs.isMasterOn(this);
            if (on && !isAccessibilityOn()) {
                toast("Pehle Accessibility ON karo (button 1)");
                openAccessibilitySettings();
                return;
            }
            Prefs.setMasterOn(this, on);
            if (on) startBubble(); else stopBubble();
            sendBroadcast(new Intent("com.lapakfree.MASTER_CHANGED"));
            refreshAll();
        });

        accessBtn.setOnClickListener(v -> openAccessibilitySettings());
        overlayBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else toast("Bubble permission already hai");
        });
        batteryBtn.setOnClickListener(v -> {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else toast("Battery optimization already off hai");
        });
        locBtn.setOnClickListener(v -> {
            if (hasLocation()) {
                toast("Location permission already hai");
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
            }
        });

        findViewById(R.id.saveRulesBtn).setOnClickListener(v -> {
            try {
                String mf = minFareInput.getText().toString().trim();
                String mp = maxPickupInput.getText().toString().trim();
                String mt = minTripInput.getText().toString().trim();
                if (!mf.isEmpty()) Prefs.setMinFare(this, Integer.parseInt(mf));
                if (!mp.isEmpty()) Prefs.setMaxPickupKm(this, Float.parseFloat(mp));
                if (!mt.isEmpty()) Prefs.setMinTripKm(this, Float.parseFloat(mt));
                Prefs.setAcceptUnknownFare(this, unknownFareCheck.isChecked());
                toast("Rules save ho gaye");
            } catch (Exception e) {
                toast("Sahi number likho");
            }
        });

        setDestBtn.setOnClickListener(v -> {
            String q = destInput.getText().toString().trim();
            if (q.isEmpty()) { toast("Destination likho"); return; }
            // destination mode wala max pickup bhi yahin save ho jata hai
            String dp = destPickupInput.getText().toString().trim();
            if (!dp.isEmpty()) {
                try { Prefs.setDestMaxPickupKm(this, Float.parseFloat(dp)); }
                catch (Exception ignored) {}
            }
            setDestBtn.setEnabled(false);
            destStatus.setText("Dhoondh raha hoon...");
            new Thread(() -> {
                double[] ll = OfflineGeo.geocode(MainActivity.this, q, null);
                runOnUiThread(() -> {
                    setDestBtn.setEnabled(true);
                    if (ll != null) {
                        Prefs.setDest(MainActivity.this, q, ll[0], ll[1]);
                        toast("Destination set ho gaya");
                    } else {
                        toast("Location nahi mili — aur clear likho");
                    }
                    refreshAll();
                });
            }).start();
        });

        destModeCheck.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !Prefs.hasDest(this)) {
                destModeCheck.setChecked(false);
                toast("Pehle destination set karo");
                return;
            }
            if (checked && !hasLocation()) {
                destModeCheck.setChecked(false);
                toast("Pehle location permission do (button 4)");
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
                return;
            }
            Prefs.setDestMode(this, checked);
            toast(checked ? "Destination mode ON" : "Destination mode OFF");
        });

        findViewById(R.id.clearHistoryBtn).setOnClickListener(v -> {
            HistoryStore.clear(this);
            refreshHistory();
        });

        registerReceiverCompat(new IntentFilter("com.lapakfree.HISTORY_UPDATED"));
        registerReceiverCompat(new IntentFilter("com.lapakfree.MASTER_CHANGED"));

        // agar app band thi aur browser se OAuth callback par khuli hai
        GoogleAuth.handleCallback(this, getIntent(), (ok, msg) -> {
            toast(msg);
            refreshAll();
        });
    }

    /** Android 13+ par flag ke bina registerReceiver crash karta hai. */
    private void registerReceiverCompat(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        checkForUpdate();
    }

    /** v3.0: page badlo — fade + halki slide animation ke saath. */
    private void switchPage(int idx) {
        if (idx == currentPage || pages == null) return;
        final android.view.View oldP = pages[currentPage];
        final android.view.View newP = pages[idx];
        currentPage = idx;
        updateNavStyles();
        oldP.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            oldP.setVisibility(android.view.View.GONE);
            oldP.setAlpha(1f);
            newP.setAlpha(0f);
            newP.setTranslationX(48f);
            newP.setVisibility(android.view.View.VISIBLE);
            newP.animate().alpha(1f).translationX(0f).setDuration(220).start();
        }).start();
    }

    private void updateNavStyles() {
        if (navBtns == null) return;
        for (int i = 0; i < navBtns.length; i++) {
            boolean sel = (i == currentPage);
            navBtns[i].setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            sel ? 0xFF018750 : 0xFFE0E0E0));
            navBtns[i].setTextColor(sel ? 0xFFFFFFFF : 0xFF616161);
        }
    }

    /** Button dabane par halka press animation (click ko rokta nahi). */
    private void animPress(android.view.View v) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            int a = ev.getAction();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start();
            } else if (a == android.view.MotionEvent.ACTION_UP
                    || a == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(70).start();
            }
            return false;
        });
    }

    /** In-app update (v2.2): naya version ho to dialog → download → install. */
    private void checkForUpdate() {
        UpdateChecker.check(this, false, info -> {
            if (info == null || isFinishing()) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Naya update aaya hai — v" + info.versionName)
                    .setMessage((info.notes.isEmpty()
                            ? "Naya version download karun?" : info.notes + "\n\nDownload karun?")
                            + "\n\nUninstall ki zaroorat nahi — purane ke upar update hoga.")
                    .setPositiveButton("Download karo", (d, w) -> downloadUpdate(info))
                    .setNegativeButton("Baad me", null)
                    .show();
        });
    }

    private void downloadUpdate(UpdateChecker.UpdateInfo info) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("Update download ho raha hai");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        UpdateChecker.downloadAndInstall(this, info, new UpdateChecker.ProgressCallback() {
            @Override public void onProgress(int percent) { pd.setProgress(percent); }
            @Override public void onDone(java.io.File apk) {
                pd.dismiss();
                toast("Download ho gaya — install dabao");
            }
            @Override public void onError(String msg) {
                pd.dismiss();
                toast(msg);
            }
        });
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Google OAuth se wapasi
        GoogleAuth.handleCallback(this, intent, (ok, msg) -> {
            toast(msg);
            refreshAll();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 11) {
            toast(hasLocation() ? "Location permission mil gayi" : "Location permission nahi mili");
            refreshAll();
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshAll() {
        boolean on = Prefs.isMasterOn(this);
        boolean acc = isAccessibilityOn();
        if (on && !acc) { // accessibility band ho gayi to master bhi band
            Prefs.setMasterOn(this, false);
            stopBubble();
            on = false;
        }
        statusText.setText(on ? "ON hai — ride pakad raha hai" : "OFF hai");
        statusText.setTextColor(on ? 0xFF018750 : 0xFFC0392B);
        toggleBtn.setText(on ? "BAND KARO" : "CHALU KARO");

        accessBtn.setText(acc ? "1. Accessibility ✓" : "1. Accessibility");
        boolean ov = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        overlayBtn.setText(ov ? "2. Bubble Permission ✓" : "2. Bubble Permission");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean bo = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryBtn.setText(bo ? "3. Battery Optimization ✓" : "3. Battery Optimization");
        locBtn.setText(hasLocation() ? "4. Location Permission ✓" : "4. Location Permission");
        notifBtn.setText(isNotificationListenerOn() ? "5. Notification Access ✓" : "5. Notification Access");

        notifAlertsCheck.setOnCheckedChangeListener(null);
        notifAlertsCheck.setChecked(Prefs.isNotifAlertsOn(this));
        notifAlertsCheck.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setNotifAlertsOn(this, checked);
        });
        autoAcceptCheck.setOnCheckedChangeListener(null);
        autoAcceptCheck.setChecked("auto".equals(Prefs.getAlertMode(this)));
        autoAcceptCheck.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAlertMode(this, checked ? "auto" : "manual");
        });
        autoPctInput.setHint(String.valueOf(Prefs.getAutoPct(this)));

        String instr = Prefs.getAiInstructions(this);
        if (instr != null && !instr.isEmpty()) {
            instrStatus.setText("✓ Nirdesh set hain (" + instr.length() + " akshar) — AI har ride par maanega");
            instrStatus.setTextColor(0xFF018750);
            if (aiInstrInput.getText().toString().isEmpty()) {
                aiInstrInput.setText(instr);
            }
        } else {
            instrStatus.setText("Koi nirdesh nahi diye.");
            instrStatus.setTextColor(0xFF6B7280);
        }

        java.util.List<StandingOrder> act = OrderStore.active(this);
        if (act.isEmpty()) {
            ordersStatusMain.setText("");
        } else {
            StringBuilder sb = new StringBuilder("AI ke active orders (" + act.size() + "): ");
            for (int i = 0; i < act.size(); i++) {
                sb.append(i + 1).append(". ").append(act.get(i).text).append("  ");
            }
            ordersStatusMain.setText(sb.toString());
        }

        minFareInput.setHint(String.valueOf(Prefs.getMinFare(this)));
        maxPickupInput.setHint(String.valueOf(Prefs.getMaxPickupKm(this)));
        minTripInput.setHint(String.valueOf(Prefs.getMinTripKm(this)));
        unknownFareCheck.setChecked(Prefs.isAcceptUnknownFare(this));

        if (Prefs.hasDest(this)) {
            destStatus.setText("📍 " + Prefs.getDestName(this) + " set hai");
            destStatus.setTextColor(0xFF018750);
        } else {
            destStatus.setText("Koi destination set nahi hai.");
            destStatus.setTextColor(0xFF6B7280);
        }
        destModeCheck.setOnCheckedChangeListener(null);
        destModeCheck.setChecked(Prefs.isDestMode(this));
        destModeCheck.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !Prefs.hasDest(this)) {
                destModeCheck.setChecked(false);
                toast("Pehle destination set karo");
                return;
            }
            if (checked && !hasLocation()) {
                destModeCheck.setChecked(false);
                toast("Pehle location permission do (button 4)");
                return;
            }
            Prefs.setDestMode(this, checked);
        });
        destPickupInput.setHint(String.valueOf(Prefs.getDestMaxPickupKm(this)));

        // Google login + AI status (v2.0: har driver ka apna Google account, apna free AI)
        boolean signedIn = GoogleAuth.isSignedIn(this);
        boolean oauthReady = GoogleAuth.isOAuthReady(this);
        boolean aiReady = GoogleAuth.isAiReady(this);
        if (!oauthReady) {
            loginStatus.setText("Google setup baaki hai — thodi der me dobara try karo");
            loginStatus.setTextColor(0xFFC0392B);
            googleLoginBtn.setText("Google se Login karo");
        } else if (signedIn && aiReady) {
            String em = GoogleAuth.userEmail(this);
            loginStatus.setText("✓ " + (em != null ? em : "login hai") + " — AI tumhare free quota se connected");
            loginStatus.setTextColor(0xFF018750);
            googleLoginBtn.setText("Logout karo");
        } else if (signedIn) {
            loginStatus.setText("Login hai par AI permission nahi — dobara login karo");
            loginStatus.setTextColor(0xFFC0392B);
            googleLoginBtn.setText("Logout karo");
        } else {
            loginStatus.setText("Login nahi hua — AI band rahega, rules se kaam chalega");
            loginStatus.setTextColor(0xFF6B7280);
            googleLoginBtn.setText("Google se Login karo");
        }
        aiCheck.setOnCheckedChangeListener(null);
        aiCheck.setChecked(Prefs.isAiOn(this));
        aiCheck.setOnCheckedChangeListener((btn, checked) -> {
            Prefs.setAiOn(this, checked);
        });

        refreshHistory();
    }

    private void refreshHistory() {
        List<HistoryStore.Entry> list = HistoryStore.get(this);
        if (list.isEmpty()) {
            historyText.setText("Abhi koi ride nahi aayi.");
            return;
        }
        // date wise group karo (nayi date pehle)
        java.util.LinkedHashMap<String, java.util.List<HistoryStore.Entry>> groups =
                new java.util.LinkedHashMap<>();
        for (HistoryStore.Entry e : list) {
            String day = (e.day != null && !e.day.isEmpty()) ? e.day : "pehle";
            if (!groups.containsKey(day)) groups.put(day, new java.util.ArrayList<>());
            groups.get(day).add(e);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.List<HistoryStore.Entry>> g : groups.entrySet()) {
            sb.append("── ").append(dayLabel(g.getKey())).append(" ──\n");
            for (HistoryStore.Entry e : g.getValue()) {
                sb.append(e.time).append("  ").append(e.app).append("  ").append(e.verdict)
                  .append("\n").append(e.detail).append(" — ").append(e.reason).append("\n\n");
            }
        }
        historyText.setText(sb.toString().trim());
    }

    /** "2026-10-05" → "Aaj" / "Kal" / "5 Oct 2026" */
    private String dayLabel(String day) {
        if ("pehle".equals(day)) return "Pehle ki rides";
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date d = fmt.parse(day);
            java.util.Calendar c = java.util.Calendar.getInstance();
            String today = fmt.format(c.getTime());
            c.add(java.util.Calendar.DAY_OF_YEAR, -1);
            String yesterday = fmt.format(c.getTime());
            if (day.equals(today)) return "Aaj";
            if (day.equals(yesterday)) return "Kal";
            return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(d);
        } catch (Exception e) {
            return day;
        }
    }

    private boolean isAccessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        // system "package/class" format me store karta hai, ComponentName se exact match karo
        String me = new ComponentName(this, AutoAcceptService.class).flattenToString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    private boolean isNotificationListenerOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(this, RideNotifyService.class).flattenToString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (Exception e) { toast("Settings > Accessibility me jaao"); }
    }

    private void startBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, BubbleService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void stopBubble() {
        stopService(new Intent(this, BubbleService.class));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
