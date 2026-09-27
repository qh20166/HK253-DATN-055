package com.example.traffigo.utils;

/**
 * F2 - Điểm sức khỏe tuyến đường.
 *
 * Gom các mẫu (sample) dọc tuyến thành 4 mức {@link Level} rồi chấm điểm A–F. Logic thuần
 * (không phụ thuộc Android) để unit test được và tái dùng cho cả 2 nguồn tín hiệu hiện có:
 *  - congestionIndex (ngưỡng 0.7/0.85 theo quy ước dự án) — dùng ở NavigationActivity.
 *  - severityRank của {@link ClusterColorAssigner} (0=kẹt nặng .. 5=thoáng) — dùng ở bản đồ.
 *
 * Lưu ý quy ước dự án: congestionIndex THẤP = kẹt, CAO = thoáng (xem CLAUDE.md).
 */
public final class RouteQualityAnalyzer {

    public enum Level { HEAVY, MODERATE, CLEAR, NO_DATA }

    /** Ánh xạ congestionIndex -> mức, dùng đúng ngưỡng 0.7/0.85 của dự án. */
    public static Level fromCongestionIndex(double congestionIndex) {
        if (congestionIndex < 0.7) return Level.HEAVY;
        if (congestionIndex < 0.85) return Level.MODERATE;
        return Level.CLEAR;
    }

    /** Ánh xạ severityRank (KMeans) -> mức. rank<0 = thiếu dữ liệu. */
    public static Level fromSeverityRank(int severityRank) {
        if (severityRank < 0) return Level.NO_DATA;
        if (severityRank <= 1) return Level.HEAVY;   // rank 0,1: đỏ đậm/đỏ
        if (severityRank <= 3) return Level.MODERATE;// rank 2,3: cam/vàng
        return Level.CLEAR;                          // rank 4,5: xanh
    }

    private int heavy, moderate, clear, noData;

    public void add(Level level) {
        switch (level) {
            case HEAVY: heavy++; break;
            case MODERATE: moderate++; break;
            case CLEAR: clear++; break;
            case NO_DATA: noData++; break;
        }
    }

    public void addCongestionIndex(double congestionIndex) {
        add(fromCongestionIndex(congestionIndex));
    }

    public int totalSamples() {
        return heavy + moderate + clear + noData;
    }

    public Result build() {
        int total = totalSamples();
        int dataSamples = heavy + moderate + clear; // mẫu có dữ liệu thật (loại no-data)

        int score;
        char grade;
        if (dataSamples == 0) {
            score = 0;
            grade = '?'; // toàn tuyến thiếu dữ liệu -> không chấm được
        } else {
            // clear = 1.0đ, moderate = 0.5đ, heavy = 0đ; chuẩn hóa về thang 0–100.
            double weighted = clear * 1.0 + moderate * 0.5;
            score = (int) Math.round(100.0 * weighted / dataSamples);
            grade = gradeFromScore(score);
        }

        return new Result(grade, score,
                pct(clear, total), pct(moderate, total), pct(heavy, total), pct(noData, total),
                total);
    }

    private static char gradeFromScore(int score) {
        if (score >= 85) return 'A';
        if (score >= 70) return 'B';
        if (score >= 55) return 'C';
        if (score >= 40) return 'D';
        if (score >= 25) return 'E';
        return 'F';
    }

    private static int pct(int part, int total) {
        return total == 0 ? 0 : (int) Math.round(100.0 * part / total);
    }

    /** Kết quả chấm điểm tuyến. */
    public static final class Result {
        public final char grade;    // 'A'..'F', hoặc '?' nếu toàn bộ thiếu dữ liệu
        public final int score;     // 0..100 (tính trên mẫu có dữ liệu)
        public final int clearPct, moderatePct, heavyPct, noDataPct;
        public final int samples;

        Result(char grade, int score, int clearPct, int moderatePct, int heavyPct,
               int noDataPct, int samples) {
            this.grade = grade;
            this.score = score;
            this.clearPct = clearPct;
            this.moderatePct = moderatePct;
            this.heavyPct = heavyPct;
            this.noDataPct = noDataPct;
            this.samples = samples;
        }

        public boolean hasGrade() {
            return grade != '?';
        }
    }
}
