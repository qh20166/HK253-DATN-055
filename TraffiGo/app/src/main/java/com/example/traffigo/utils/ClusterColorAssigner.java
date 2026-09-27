package com.example.traffigo.utils;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Gán mỗi segment traffic vào 1 trong 6 cụm KMeans (đã train offline trong
 * DATN/clustering.py, xuất centroid+scaler qua DATN/export_cluster_model.py)
 * bằng cách chuẩn hóa đặc trưng rồi tìm centroid gần nhất (Euclidean).
 *
 * Màu được sắp theo cluster_severity_rank: rank 0 = cụm có congestionIndex
 * trung bình thấp nhất (kẹt nặng nhất theo quy ước dự án), rank 5 = thông thoáng nhất.
 */
public class ClusterColorAssigner {

    private static final int[] SEVERITY_COLORS = new int[]{
            Color.argb(220, 183, 28, 28),  // rank 0: kẹt nặng nhất - đỏ đậm
            Color.argb(220, 229, 57, 53),  // rank 1: đỏ
            Color.argb(220, 255, 112, 67), // rank 2: cam đậm
            Color.argb(220, 255, 179, 0),  // rank 3: vàng/cam
            Color.argb(220, 156, 204, 101),// rank 4: xanh nhạt
            Color.argb(220, 56, 168, 82),  // rank 5: thông thoáng nhất - xanh đậm
    };

    // F3 (lớp thành thật dữ liệu): màu xám cho đoạn KHÔNG có tín hiệu live thật.
    // Khi train, ~12% đoạn rơi vào "cụm thiếu dữ liệu" (currentSpeed/freeFlowSpeed = 0) và
    // trước đây bị fallback thành xanh -> hiểu nhầm "đường thoáng". Xem CLAUDE.md.
    public static final int NO_DATA_COLOR = Color.argb(200, 158, 158, 158);

    private String[] featureColumns;
    private double[] scalerMean;
    private double[] scalerScale;
    private Map<Integer, double[]> centroids = new HashMap<>();
    private Map<Integer, Integer> severityRank = new HashMap<>();
    private boolean loaded = false;

    public static class Features {
        public double speedLimitRatio;
        public double crossTime;
        public double trafficVolume;
        public double congestionIndex;
        public double occupancy;
        public double relativeCongestionIndex;
        public double freeFlowSpeed;
        public double lengthKm;
        public double curvatureIndex;

        double byName(String name) {
            switch (name) {
                case "speedLimitRatio": return speedLimitRatio;
                case "crossTime": return crossTime;
                case "trafficVolume": return trafficVolume;
                case "congestionIndex": return congestionIndex;
                case "occupancy": return occupancy;
                case "relativeCongestionIndex": return relativeCongestionIndex;
                case "freeFlowSpeed": return freeFlowSpeed;
                case "lengthKm": return lengthKm;
                case "curvatureIndex": return curvatureIndex;
                default: return 0.0;
            }
        }
    }

    /** Đọc cluster_model.json từ assets. Gọi 1 lần lúc khởi tạo, trên background thread. */
    public void loadFromAssets(Context context) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open("cluster_model.json")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            loadFromJson(sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            loaded = false;
        }
    }

    /**
     * Nạp model từ chuỗi JSON. Tách riêng khỏi {@link #loadFromAssets(Context)} để unit test
     * (chạy trên JVM, không có Android Context) nạp được cluster_model.json thật.
     */
    public void loadFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);

            JSONArray featuresArr = root.getJSONArray("feature_columns");
            featureColumns = new String[featuresArr.length()];
            for (int i = 0; i < featuresArr.length(); i++) featureColumns[i] = featuresArr.getString(i);

            scalerMean = jsonArrayToDoubles(root.getJSONArray("scaler_mean"));
            scalerScale = jsonArrayToDoubles(root.getJSONArray("scaler_scale"));

            JSONObject centroidsObj = root.getJSONObject("centroids");
            for (java.util.Iterator<String> it = centroidsObj.keys(); it.hasNext(); ) {
                String key = it.next();
                centroids.put(Integer.parseInt(key), jsonArrayToDoubles(centroidsObj.getJSONArray(key)));
            }

            JSONObject rankObj = root.getJSONObject("cluster_severity_rank");
            for (java.util.Iterator<String> it = rankObj.keys(); it.hasNext(); ) {
                String key = it.next();
                severityRank.put(Integer.parseInt(key), rankObj.getInt(key));
            }

            loaded = true;
        } catch (Exception e) {
            e.printStackTrace();
            loaded = false;
        }
    }

    private static double[] jsonArrayToDoubles(JSONArray arr) throws Exception {
        double[] out = new double[arr.length()];
        for (int i = 0; i < arr.length(); i++) out[i] = arr.getDouble(i);
        return out;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Kết quả phân loại 1 segment: cụm gần nhất, mức nghiêm trọng (rank), có phải "thiếu dữ liệu"
     * không, và màu tương ứng. Dùng chung cho tô màu bản đồ (F3) lẫn chấm điểm tuyến (F2).
     */
    public static class ClusterResult {
        public final int clusterId;     // -1 nếu chưa load / thiếu dữ liệu
        public final int severityRank;  // 0=kẹt nặng nhất .. 5=thoáng nhất; -1 nếu thiếu dữ liệu
        public final boolean noData;
        public final int color;

        ClusterResult(int clusterId, int severityRank, boolean noData, int color) {
            this.clusterId = clusterId;
            this.severityRank = severityRank;
            this.noData = noData;
            this.color = color;
        }
    }

    /**
     * F3 - lớp thành thật dữ liệu: segment không có tín hiệu live thật khi TomTom không trả được
     * tốc độ dòng tự do. Không suy đoán trạng thái (không tô xanh), đánh dấu "thiếu dữ liệu".
     */
    public static boolean isNoData(Features f) {
        return f.freeFlowSpeed <= 0.0;
    }

    /** Phân loại segment thành {@link ClusterResult}. */
    public ClusterResult classify(Features f) {
        if (isNoData(f)) return new ClusterResult(-1, -1, true, NO_DATA_COLOR);
        if (!loaded) return new ClusterResult(-1, 5, false, SEVERITY_COLORS[5]);

        double[] scaled = new double[featureColumns.length];
        for (int i = 0; i < featureColumns.length; i++) {
            double raw = f.byName(featureColumns[i]);
            scaled[i] = scalerScale[i] != 0 ? (raw - scalerMean[i]) / scalerScale[i] : 0.0;
        }

        int bestCluster = -1;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<Integer, double[]> entry : centroids.entrySet()) {
            double dist = squaredDistance(scaled, entry.getValue());
            if (dist < bestDist) {
                bestDist = dist;
                bestCluster = entry.getKey();
            }
        }

        Integer rank = severityRank.get(bestCluster);
        if (rank == null) return new ClusterResult(bestCluster, 5, false, SEVERITY_COLORS[5]);
        return new ClusterResult(bestCluster, rank, false, SEVERITY_COLORS[rank]);
    }

    /** Trả về màu ARGB theo cụm gần nhất (giữ tương thích với code gọi cũ). */
    public int colorFor(Features f) {
        return classify(f).color;
    }

    private static double squaredDistance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }
}
