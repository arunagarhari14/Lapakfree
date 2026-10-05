package com.lapakfree;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI Commander chat — likh ke ya bol ke. AI ka jawab chaho to SUN bhi sakte ho.
 * (v3.0: TextToSpeech reply + speaker toggle + behtar mic)
 */
public class CommanderActivity extends Activity {
    private static final int REQ_SPEECH = 41;

    private ArrayAdapter<String> chatAdapter;
    private ArrayList<String> chatLines;
    private EditText chatInput;
    private Button sendBtn, speakerBtn;
    private TextView ordersStatus;
    private TextToSpeech tts;
    private boolean speakerOn = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_commander);

        chatLines = new ArrayList<>();
        chatAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, chatLines);
        ListView chatList = findViewById(R.id.chatList);
        chatList.setAdapter(chatAdapter);

        chatInput = findViewById(R.id.chatInput);
        sendBtn = findViewById(R.id.sendBtn);
        speakerBtn = findViewById(R.id.speakerBtn);
        ordersStatus = findViewById(R.id.ordersStatus);

        sendBtn.setOnClickListener(v -> {
            animPress(v);
            sendMessage();
        });
        findViewById(R.id.micBtn).setOnClickListener(v -> {
            animPress(v);
            startVoice();
        });
        speakerBtn.setOnClickListener(v -> {
            animPress(v);
            speakerOn = !speakerOn;
            speakerBtn.setText(speakerOn ? "🔊" : "🔇");
            if (!speakerOn && tts != null) tts.stop();
            Toast.makeText(this, speakerOn ? "AI ki awaaz ON" : "AI ki awaaz OFF",
                    Toast.LENGTH_SHORT).show();
        });

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int r = tts.setLanguage(new Locale("hi", "IN"));
                if (r == TextToSpeech.LANG_MISSING_DATA
                        || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setSpeechRate(0.95f);
            }
        });

        addLine("AI", "Namaste! Mujhe bolo kya karna hai. Jaise:\n" +
                "• \"Noida side ki parcel ride Rapido/Uber se aaye to accept kar le\"\n" +
                "• \"Ab ek ride aur de de usi side ki\"\n" +
                "• \"Destination Karol Bagh set kar de\"\n" +
                "• \"Mere orders dikha\" / \"pehla wala hata de\"\n" +
                "Mic dabake bol bhi sakte ho — mera jawab sunna ho to speaker ON rakho.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOrders();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private void refreshOrders() {
        List<StandingOrder> act = OrderStore.active(this);
        if (act.isEmpty()) {
            ordersStatus.setText("Active orders: koi nahi — upar bolo, main yaad rakhunga");
        } else {
            StringBuilder sb = new StringBuilder("Active orders: ");
            for (int i = 0; i < act.size(); i++) {
                sb.append(i + 1).append(". ").append(act.get(i).text).append("  ");
            }
            ordersStatus.setText(sb.toString());
        }
    }

    private void sendMessage() {
        String msg = chatInput.getText().toString().trim();
        if (msg.isEmpty()) return;
        chatInput.setText("");
        sendBtn.setEnabled(false);
        addLine("Tum", msg);
        addLine("AI", "soch raha hoon...");
        AiCommander.chat(this, msg, reply -> {
            if (!chatLines.isEmpty()) chatLines.remove(chatLines.size() - 1);
            addLine("AI", reply);
            sendBtn.setEnabled(true);
            refreshOrders();
            speak(reply);
        });
    }

    /** AI ka jawab sunao (agar speaker ON hai). */
    private void speak(String text) {
        if (!speakerOn || tts == null) return;
        try {
            // markdown/bullet saaf karke natural banao
            String clean = text.replace("•", "").replace("*", "").trim();
            if (clean.length() > 250) clean = clean.substring(0, 250);
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "lapak_reply");
        } catch (Exception ignored) {}
    }

    private void addLine(String who, String text) {
        chatLines.add(who + ": " + text);
        chatAdapter.notifyDataSetChanged();
    }

    /** Chhota press animation. */
    private void animPress(android.view.View v) {
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(80).start())
                .start();
    }

    private void startVoice() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,
                    "Bolo — jaise: Noida side ki parcel ride accept kar le");
            startActivityForResult(i, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Voice support nahi mila — likh ke bhejo",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SPEECH && res == RESULT_OK && data != null) {
            ArrayList<String> t = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);
            if (t != null && !t.isEmpty()) {
                chatInput.setText(t.get(0));
                Toast.makeText(this, "Suna: \"" + t.get(0) + "\"",
                        Toast.LENGTH_SHORT).show();
                sendMessage();
            }
        }
    }
}
