package com.example.traffigo.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.traffigo.R;
import com.example.traffigo.databinding.ActivityVoiceAssistantBinding;
import com.example.traffigo.models.VoiceIntent;
import com.example.traffigo.utils.GeminiClient;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Trợ lý giọng nói: nghe (SpeechRecognizer) → hiểu ý (Gemini) → thực thi lệnh khắp app.
 * Là một overlay trong suốt, gọi được từ nút mic ở mọi màn hình.
 */
public class VoiceAssistantActivity extends AppCompatActivity {

    private static final String TAG = "TraffiGoVoice";

    private ActivityVoiceAssistantBinding binding;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsInitDone = false;
    private PlacesClient placesClient;
    private String geminiKey, geoKey;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean handled = false; // đã thực thi 1 lệnh chưa (tránh chạy 2 lần)

    private ActivityResultLauncher<String> micPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVoiceAssistantBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        geminiKey = readMeta("com.traffigo.GEMINI_API_KEY");
        geoKey = readMeta("com.google.android.geo.API_KEY");

        if (geoKey != null && !geoKey.isEmpty()) {
            if (!Places.isInitialized()) Places.initialize(getApplicationContext(), geoKey);
            placesClient = Places.createClient(this);
        }

        initTts();
        startPulse();

        binding.voiceScrim.setOnClickListener(v -> dismiss());

        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startListening();
                    else {
                        setStatus(getString(R.string.voice_need_mic));
                        finishDelayed(1800);
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    // ===================== NGHE =====================

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.voice_no_recognizer));
            finishDelayed(1800);
            return;
        }
        setStatus(getString(R.string.voice_listening));
        binding.tvVoiceHeard.setText("");

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(intent);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { setStatus(getString(R.string.voice_listening)); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {
            float scale = 1f + Math.max(0, Math.min(rmsdB, 10)) / 25f;
            binding.voicePulse.setScaleX(scale);
            binding.voicePulse.setScaleY(scale);
        }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { setStatus(getString(R.string.voice_processing)); }

        @Override public void onError(int error) {
            if (handled) return;
            String msg = (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    ? getString(R.string.voice_didnt_catch)
                    : getString(R.string.voice_error);
            setStatus(msg);
            speak(msg);
            finishDelayed(2000);
        }

        @Override public void onResults(Bundle results) {
            List<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (texts == null || texts.isEmpty() || texts.get(0).trim().isEmpty()) {
                setStatus(getString(R.string.voice_didnt_catch));
                finishDelayed(1800);
                return;
            }
            String heard = texts.get(0).trim();
            binding.tvVoiceHeard.setText(heard);
            setStatus(getString(R.string.voice_processing));
            interpret(heard);
        }

        @Override public void onPartialResults(Bundle partial) {
            List<String> texts = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (texts != null && !texts.isEmpty()) binding.tvVoiceHeard.setText(texts.get(0));
        }
        @Override public void onEvent(int eventType, Bundle params) {}
    };

    // ===================== HIỂU Ý (GEMINI) =====================

    private void interpret(String userText) {
        if (geminiKey == null || geminiKey.isEmpty()) {
            String m = getString(R.string.voice_no_gemini_key);
            setStatus(m);
            speak(m);
            finishDelayed(2600);
            return;
        }
        new Thread(() -> {
            try {
                VoiceIntent vi = GeminiClient.interpret(geminiKey, userText);
                handler.post(() -> route(vi));
            } catch (Exception e) {
                Log.e(TAG, "Gemini error", e);
                handler.post(() -> {
                    String m = getString(R.string.voice_error);
                    setStatus(m);
                    speak(m);
                    finishDelayed(2200);
                });
            }
        }).start();
    }

    // ===================== THỰC THI =====================

    private void route(VoiceIntent vi) {
        if (handled) return;
        switch (vi.action) {
            case "navigate":
                resolveAndNavigate(vi.getString("destination", ""));
                break;
            case "navigate_saved":
                navigateSaved(vi.getString("place", "home"));
                break;
            case "start_navigation":
                sendNavAction("start_nav", getString(R.string.voice_ack_start_nav));
                break;
            case "stop_navigation":
                sendNavAction("stop_nav", getString(R.string.voice_ack_stop_nav));
                break;
            case "switch_route":
                sendNavAction("switch_route", getString(R.string.voice_ack_switch));
                break;
            case "book_ride":
                sendNavAction("book_ride", getString(R.string.voice_ack_book));
                break;
            case "share_location":
                sendNavAction("share", getString(R.string.voice_ack_share));
                break;
            case "check_traffic":
            case "open_traffic_map":
                openScreen(TrafficMapActivity.class, getString(R.string.voice_ack_map));
                break;
            case "open_news":
                openScreen(TrafficNewsActivity.class, getString(R.string.voice_ack_news));
                break;
            case "read_news":
                readNews(vi.getString("category", "all"));
                break;
            case "open_offline_maps":
                openScreen(OfflineMapsActivity.class, getString(R.string.voice_ack_offline));
                break;
            case "clarify":
                clarify(vi.getString("question", getString(R.string.voice_clarify_default)));
                break;
            default:
                String m = getString(R.string.voice_unknown);
                setStatus(m);
                speak(m);
                finishDelayed(2200);
        }
    }

    private void resolveAndNavigate(String destination) {
        if (destination.isEmpty() || placesClient == null) {
            clarify(getString(R.string.voice_clarify_default));
            return;
        }
        handled = true;
        setStatus(getString(R.string.voice_finding_place, destination));

        FindAutocompletePredictionsRequest req = FindAutocompletePredictionsRequest.builder()
                .setCountries("VN")
                .setQuery(destination)
                .build();

        placesClient.findAutocompletePredictions(req)
                .addOnSuccessListener(resp -> {
                    if (resp.getAutocompletePredictions().isEmpty()) {
                        notFound(destination);
                        return;
                    }
                    String placeId = resp.getAutocompletePredictions().get(0).getPlaceId();
                    List<Place.Field> fields = Arrays.asList(Place.Field.LAT_LNG, Place.Field.NAME, Place.Field.ADDRESS);
                    placesClient.fetchPlace(FetchPlaceRequest.newInstance(placeId, fields))
                            .addOnSuccessListener(fetch -> {
                                Place p = fetch.getPlace();
                                if (p.getLatLng() == null) { notFound(destination); return; }
                                String addr = (p.getAddress() != null) ? p.getAddress()
                                        : (p.getName() != null ? p.getName() : destination);
                                launchNavigate(p.getLatLng(), addr);
                            })
                            .addOnFailureListener(e -> notFound(destination));
                })
                .addOnFailureListener(e -> notFound(destination));
    }

    private void launchNavigate(LatLng dest, String address) {
        speak(getString(R.string.voice_ack_navigate, address));
        Intent intent = new Intent(this, NavigationActivity.class);
        intent.putExtra("voice_dest_lat", dest.latitude);
        intent.putExtra("voice_dest_lng", dest.longitude);
        intent.putExtra("voice_dest_address", address);
        intent.putExtra("voice_auto_route", true);
        intent.putExtra("voice_auto_start", true);
        launchDelayed(intent, 1100);
    }

    private void navigateSaved(String place) {
        handled = true;
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            String m = getString(R.string.voice_need_login);
            setStatus(m); speak(m); finishDelayed(2200);
            return;
        }
        String type = "work".equalsIgnoreCase(place) ? "work" : "home";
        setStatus(getString(R.string.voice_loading_saved));
        FirebaseDatabase.getInstance().getReference("Users")
                .child(auth.getCurrentUser().getUid())
                .child("savedPlaces").child(type)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Double lat = snap.child("lat").getValue(Double.class);
                        Double lng = snap.child("lng").getValue(Double.class);
                        String addr = snap.child("address").getValue(String.class);
                        if (lat == null || lng == null) {
                            String m = getString("work".equals(type)
                                    ? R.string.voice_no_saved_work : R.string.voice_no_saved_home);
                            setStatus(m); speak(m); finishDelayed(2600);
                            return;
                        }
                        launchNavigate(new LatLng(lat, lng), addr != null ? addr : "");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        String m = getString(R.string.voice_error);
                        setStatus(m); speak(m); finishDelayed(2000);
                    }
                });
    }

    private void sendNavAction(String action, String ack) {
        handled = true;
        speak(ack);
        setStatus(ack);
        Intent intent = new Intent(this, NavigationActivity.class);
        intent.putExtra("voice_action", action);
        launchDelayed(intent, 900);
    }

    private void openScreen(Class<?> cls, String ack) {
        handled = true;
        speak(ack);
        setStatus(ack);
        launchDelayed(new Intent(this, cls), 900);
    }

    /** "Đọc báo cho tôi nghe" — mở Tin tức, tự chọn bài đầu tiên của danh mục (mặc định "all") rồi tự đọc luôn. */
    private void readNews(String category) {
        handled = true;
        String ack = getString(R.string.voice_ack_read_news);
        speak(ack);
        setStatus(ack);
        Intent intent = new Intent(this, TrafficNewsActivity.class);
        intent.putExtra("voice_auto_read", true);
        intent.putExtra("voice_category", category);
        launchDelayed(intent, 900);
    }

    private void clarify(String question) {
        setStatus(question);
        binding.tvVoiceHeard.setText("");
        speak(question);
        // nghe lại sau khi đọc xong câu hỏi
        handler.postDelayed(this::startListening, 1600);
    }

    private void notFound(String destination) {
        String m = getString(R.string.voice_place_not_found, destination);
        setStatus(m);
        speak(m);
        handled = false;
        finishDelayed(2600);
    }

    // ===================== TIỆN ÍCH =====================

    private void launchDelayed(Intent intent, long ms) {
        handler.postDelayed(() -> {
            startActivity(intent);
            finish();
        }, ms);
    }

    private void finishDelayed(long ms) {
        handler.postDelayed(this::finish, ms);
    }

    private void dismiss() {
        if (recognizer != null) recognizer.cancel();
        finish();
    }

    private void setStatus(String s) {
        binding.tvVoiceStatus.setText(s);
    }

    private void startPulse() {
        binding.voicePulse.animate().alpha(0.35f).scaleX(1.25f).scaleY(1.25f)
                .setDuration(900)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        binding.voicePulse.animate().alpha(0.8f).scaleX(1f).scaleY(1f)
                                .setDuration(900).withEndAction(this).start();
                    }
                }).start();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsInitDone = true;
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("vi", "VN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                ttsReady = true;
            } else {
                // Init thất bại (engine TTS thường bị tắt trên emulator) — callback chỉ bắn 1
                // lần, không ghi nhận thì speak() im lặng suốt phiên mà không ai biết vì sao.
                setStatus(getString(R.string.voice_tts_unavailable));
            }
        });
    }

    private void speak(String text) {
        if (ttsReady && text != null && !text.isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice");
        }
    }

    private String readMeta(String key) {
        try {
            Bundle b = getPackageManager().getApplicationInfo(getPackageName(),
                    PackageManager.GET_META_DATA).metaData;
            return b != null ? b.getString(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}
