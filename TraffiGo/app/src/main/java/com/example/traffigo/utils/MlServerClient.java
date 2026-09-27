package com.example.traffigo.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client gọi TraffiGo ML Server (FastAPI) — server dự đoán giao thông bằng ML được
 * xây dựng theo báo cáo đồ án DATN_172 (Bảng 17: /api/ml/predict, /recommend-route, ...).
 *
 * Server KHÔNG bắt buộc: mọi lời gọi đều bất đồng bộ với timeout ngắn và callback
 * onError — nơi gọi tự quyết định fallback về logic on-device khi server không online.
 *
 * Địa chỉ server lưu SharedPreferences "TraffiGoPrefs/ml_server_url".
 * Mặc định 10.0.2.2:8000 = loopback máy dev nhìn từ Android emulator; máy thật
 * thì đổi thành IP LAN của máy chạy server.
 */
public class MlServerClient {

    private static final String TAG = "MlServerClient";
    public static final String PREFS_NAME = "TraffiGoPrefs";
    public static final String KEY_SERVER_URL = "ml_server_url";
    /** Ứng viên mặc định theo thứ tự thử: server cloud Render (24/7), 10.0.2.2 (emulator
     *  gốc/LDPlayer chạy server local), 127.0.0.1 (MuMu / khi dùng `adb reverse tcp:8000 tcp:8000`). */
    private static final String[] DEFAULT_SERVER_URLS = {
            "https://traffigo-server.onrender.com",
            "http://10.0.2.2:8000",
            "http://127.0.0.1:8000",
    };
    public static final String DEFAULT_SERVER_URL = DEFAULT_SERVER_URLS[0];

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .writeTimeout(2000, TimeUnit.MILLISECONDS)
            .build();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface MlCallback {
        void onSuccess(JSONObject response);

        void onError(String message);
    }

    public static String getServerUrl(Context context) {
        if (context == null) return DEFAULT_SERVER_URL;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).trim();
        // người dùng hay dán thừa "/" cuối (vd ...onrender.com/) -> ghép path sẽ thành "//health" (404)
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static void setServerUrl(Context context, String url) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER_URL, url == null ? DEFAULT_SERVER_URL : url.trim()).apply();
    }

    /** POST một payload JSON tới endpoint /api/ml/... bất đồng bộ (an toàn gọi từ UI thread).
     *  Nếu người dùng chưa cấu hình URL riêng, lần lượt thử từng ứng viên mặc định
     *  (10.0.2.2 rồi 127.0.0.1) — emu nào cũng gọi được server mà không cần chỉnh gì. */
    public static void post(Context context, String endpoint, JSONObject body, MlCallback callback) {
        String configured = getServerUrl(context);
        String[] candidates = isConfiguredUrl(context)
                ? new String[]{configured}
                : DEFAULT_SERVER_URLS;
        postCandidates(candidates, 0, endpoint, body, callback);
    }

    private static boolean isConfiguredUrl(Context context) {
        if (context == null) return false;
        String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, null);
        return saved != null && !saved.trim().isEmpty();
    }

    private static void postCandidates(String[] urls, int index, String endpoint, JSONObject body, MlCallback callback) {
        if (index >= urls.length) {
            callback.onError("server offline");
            return;
        }
        String url = urls[index] + endpoint;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                Log.d(TAG, "ML server không truy cập được (" + url + "): " + e.getMessage());
                postCandidates(urls, index + 1, endpoint, body, callback);
            }

            @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                try (response) {
                    String text = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(text);
                    mainHandler.post(() -> callback.onSuccess(json));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("parse: " + e.getMessage()));
                }
            }
        });
    }

    /** Client riêng cho wake-up: read timeout dài 90s để GIỮ connection xuyên suốt
     *  cold start 30-50s của Render free (probe timeout ngắn bị hủy không đánh thức được). */
    private static final OkHttpClient wakeClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * Đánh thức server Render (free tier ngủ sau 15 phút): bắn GET /health với timeout
     * dài, giữ connection tới khi server tỉnh (30-50s). Chỉ chạy 1 lần mỗi lần mở app
     * (onCreate của MainActivity) nên không spam; lỗi bỏ qua im lặng.
     */
    public static void wakeUp(Context context) {
        String configured = getServerUrl(context);
        String[] candidates = isConfiguredUrl(context)
                ? new String[]{configured}
                : DEFAULT_SERVER_URLS;
        wakeCandidates(candidates, 0);
    }

    private static void wakeCandidates(String[] urls, int index) {
        if (index >= urls.length) return;
        Request request = new Request.Builder().url(urls[index] + "/health").get().build();
        wakeClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                wakeCandidates(urls, index + 1);
            }

            @Override public void onResponse(Call call, Response response) {
                response.close(); // đã đánh thức, không cần nội dung
            }
        });
    }

    /** GET một endpoint (vd /health) bất đồng bộ — dùng cho kiểm tra trạng thái server. */
    public static void get(Context context, String path, MlCallback callback) {
        String configured = getServerUrl(context);
        String[] candidates = isConfiguredUrl(context)
                ? new String[]{configured}
                : DEFAULT_SERVER_URLS;
        getCandidates(candidates, 0, path, callback);
    }

    private static void getCandidates(String[] urls, int index, String path, MlCallback callback) {
        if (index >= urls.length) {
            callback.onError("server offline");
            return;
        }
        Request request = new Request.Builder().url(urls[index] + path).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                getCandidates(urls, index + 1, path, callback);
            }

            @Override public void onResponse(Call call, Response response) throws java.io.IOException {
                try (response) {
                    String text = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(text);
                    mainHandler.post(() -> callback.onSuccess(json));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("parse: " + e.getMessage()));
                }
            }
        });
    }

    // ===================== Helper dựng payload =====================

    /** Dựng 1 "sample" (đặc trưng 1 đoạn đường) khớp schema SegmentFeatures của server. */
    public static JSONObject sample(double currentSpeed, double freeFlowSpeed, double lengthKm,
                                    Integer speedLimitKmh, String roadType,
                                    int hourOfDay, int dayOfWeek) {
        JSONObject o = new JSONObject();
        try {
            if (currentSpeed > 0) o.put("currentSpeed", currentSpeed);
            o.put("freeFlowSpeed", freeFlowSpeed > 0 ? freeFlowSpeed : 50.0);
            o.put("lengthKm", Math.max(0.05, lengthKm));
            if (speedLimitKmh != null && speedLimitKmh > 0) o.put("speedLimit", speedLimitKmh);
            if (roadType != null && !roadType.isEmpty()) o.put("roadType", roadType);
            o.put("hourOfDay", hourOfDay);
            o.put("dayOfWeek", dayOfWeek);
            o.put("dayType", (dayOfWeek == 0 || dayOfWeek == 6) ? "weekend" : "weekday");
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Dựng route candidate cho /recommend-route hoặc /compare-routes. */
    public static JSONObject routeCandidate(String id, int durationSec, int distanceMeters,
                                            JSONArray samples) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("durationSec", durationSec);
            o.put("distanceMeters", distanceMeters);
            o.put("samples", samples);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Giờ/ngày hiện tại theo quy ước server (dayOfWeek: 0=CN..6=Thứ 7). */
    public static int todayDayOfWeek() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        // Calendar: 1=CN..7=Thứ 7 -> 0=CN..6=Thứ 7
        return (c.get(java.util.Calendar.DAY_OF_WEEK) + 6) % 7;
    }

    public static int currentHour() {
        return java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
    }

    /** Lấy mảng "scores" từ response /recommend-route (nếu có). */
    public static JSONArray scoresOf(JSONObject response) {
        return response.optJSONArray("scores");
    }
}
