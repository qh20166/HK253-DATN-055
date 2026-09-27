package com.example.traffigo.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit test cho F1 - hệ số phạt kẹt trong định tuyến né cụm kẹt.
 *
 * Chỉ test hàm thuần {@link PathFinder#congestionPenalty(double)} (lõi quyết định của tính năng).
 * Vòng Dijkstra ({@code findFastestPath}) phụ thuộc {@code android.location.Location} nên không
 * chạy được trên JVM thuần — xem phần rủi ro/review thủ công trong báo cáo tổng kết.
 */
public class PathFinderTest {

    private static final double EPS = 1e-9;

    @Test
    public void thongThoang_khongPhat() {
        assertEquals(1.0, PathFinder.congestionPenalty(0.85), EPS);
        assertEquals(1.0, PathFinder.congestionPenalty(0.90), EPS);
        assertEquals(1.0, PathFinder.congestionPenalty(1.0), EPS);
    }

    @Test
    public void ketNang_phatToiDa() {
        assertEquals(PathFinder.MAX_CONGESTION_PENALTY, PathFinder.congestionPenalty(0.30), EPS);
        assertEquals(PathFinder.MAX_CONGESTION_PENALTY, PathFinder.congestionPenalty(0.10), EPS);
    }

    @Test
    public void khongRoDuLieu_khongPhat() {
        assertEquals(1.0, PathFinder.congestionPenalty(0.0), EPS);
        assertEquals(1.0, PathFinder.congestionPenalty(-1.0), EPS);
    }

    @Test
    public void diemGiua_noiSuyTuyenTinh() {
        // ci = 0.575 -> heaviness = (0.85-0.575)/0.55 = 0.5 -> penalty = 1.0 + 0.6*0.5 = 1.3
        assertEquals(1.3, PathFinder.congestionPenalty(0.575), 1e-6);
    }

    /** Càng kẹt (congestionIndex càng thấp) thì phạt càng cao -> router né đoạn kẹt. */
    @Test
    public void phatTangKhiCangKet() {
        double p80 = PathFinder.congestionPenalty(0.80);
        double p60 = PathFinder.congestionPenalty(0.60);
        double p40 = PathFinder.congestionPenalty(0.40);
        assertTrue(p40 > p60);
        assertTrue(p60 > p80);
        assertTrue(p80 >= 1.0);
        assertTrue(p40 <= PathFinder.MAX_CONGESTION_PENALTY);
    }
}
