package com.example.traffigo.utils;

import com.example.traffigo.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gọi TomTom Flow Segment Data API để lấy tốc độ hiện tại/tốc độ thông thoáng tại 1 điểm.
 * Xoay qua key kế tiếp trong danh sách khi key hiện tại hết quota ngày (HTTP 403/429).
 */
public class TomTomTrafficClient {

    private static final String FLOW_URL =
            "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json";

    private static final List<String> KEYS = new ArrayList<>();
    private static final AtomicInteger keyIndex = new AtomicInteger(0);

    static {
        String raw = BuildConfig.TOMTOM_KEYS;
        if (raw != null && !raw.trim().isEmpty()) {
            for (String k : raw.split(",")) {
                if (!k.trim().isEmpty()) KEYS.add(k.trim());
            }
        }
    }

    public static class FlowResult {
        public final double currentSpeed;
        public final double freeFlowSpeed;
        public final double congestionIndex;

        FlowResult(double currentSpeed, double freeFlowSpeed) {
            this.currentSpeed = currentSpeed;
            this.freeFlowSpeed = freeFlowSpeed;
            this.congestionIndex = freeFlowSpeed > 0 ? currentSpeed / freeFlowSpeed : 1.0;
        }
    }

    /** Tọa độ 2 điểm trong 1 lần gọi để tính currentSpeed/freeFlowSpeed trung bình đoạn. */
    public static FlowResult fetchFlowAveraged(double lat1, double lon1, double lat2, double lon2) {
        FlowResult a = fetchFlow(lat1, lon1);
        FlowResult b = fetchFlow(lat2, lon2);
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        return new FlowResult((a.currentSpeed + b.currentSpeed) / 2.0, (a.freeFlowSpeed + b.freeFlowSpeed) / 2.0);
    }

    /** Gọi đồng bộ - luôn chạy trên background thread. Trả về null nếu tất cả key đều lỗi. */
    public static FlowResult fetchFlow(double lat, double lon) {
        if (KEYS.isEmpty()) return null;

        int attempts = KEYS.size();
        for (int i = 0; i < attempts; i++) {
            String key = KEYS.get(keyIndex.get() % KEYS.size());
            try {
                String urlStr = String.format(Locale.US, "%s?point=%f,%f&unit=KMPH&key=%s",
                        FLOW_URL, lat, lon, key);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code == 403 || code == 429) {
                    // Key hết quota ngày -> xoay sang key kế tiếp và thử lại
                    keyIndex.incrementAndGet();
                    conn.disconnect();
                    continue;
                }
                if (code != 200) {
                    conn.disconnect();
                    return null;
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject root = new JSONObject(sb.toString());
                JSONObject flow = root.optJSONObject("flowSegmentData");
                if (flow == null) return null;

                double currentSpeed = flow.optDouble("currentSpeed", 0);
                double freeFlowSpeed = flow.optDouble("freeFlowSpeed", currentSpeed);
                return new FlowResult(currentSpeed, freeFlowSpeed);
            } catch (Exception e) {
                keyIndex.incrementAndGet();
            }
        }
        return null;
    }
}
