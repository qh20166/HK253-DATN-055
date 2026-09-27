package com.example.traffigo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.traffigo.utils.RouteQualityAnalyzer;
import com.example.traffigo.utils.RouteQualityAnalyzer.Level;

import org.junit.Test;

/** Unit test cho F2 - chấm điểm sức khỏe tuyến đường. Logic thuần, không cần thiết bị. */
public class RouteQualityAnalyzerTest {

    @Test
    public void fromCongestionIndex_dungNguong070va085() {
        assertEquals(Level.HEAVY, RouteQualityAnalyzer.fromCongestionIndex(0.5));
        assertEquals(Level.HEAVY, RouteQualityAnalyzer.fromCongestionIndex(0.69));
        assertEquals(Level.MODERATE, RouteQualityAnalyzer.fromCongestionIndex(0.7));
        assertEquals(Level.MODERATE, RouteQualityAnalyzer.fromCongestionIndex(0.84));
        assertEquals(Level.CLEAR, RouteQualityAnalyzer.fromCongestionIndex(0.85));
        assertEquals(Level.CLEAR, RouteQualityAnalyzer.fromCongestionIndex(1.0));
    }

    @Test
    public void fromSeverityRank_anhXaDungNhom() {
        assertEquals(Level.NO_DATA, RouteQualityAnalyzer.fromSeverityRank(-1));
        assertEquals(Level.HEAVY, RouteQualityAnalyzer.fromSeverityRank(0));
        assertEquals(Level.HEAVY, RouteQualityAnalyzer.fromSeverityRank(1));
        assertEquals(Level.MODERATE, RouteQualityAnalyzer.fromSeverityRank(2));
        assertEquals(Level.MODERATE, RouteQualityAnalyzer.fromSeverityRank(3));
        assertEquals(Level.CLEAR, RouteQualityAnalyzer.fromSeverityRank(4));
        assertEquals(Level.CLEAR, RouteQualityAnalyzer.fromSeverityRank(5));
    }

    @Test
    public void tuyenThongThoang_diemA_100() {
        RouteQualityAnalyzer a = new RouteQualityAnalyzer();
        for (int i = 0; i < 10; i++) a.add(Level.CLEAR);
        RouteQualityAnalyzer.Result r = a.build();
        assertEquals('A', r.grade);
        assertEquals(100, r.score);
        assertEquals(100, r.clearPct);
    }

    @Test
    public void tuyenKetNang_diemF_0() {
        RouteQualityAnalyzer a = new RouteQualityAnalyzer();
        for (int i = 0; i < 10; i++) a.add(Level.HEAVY);
        RouteQualityAnalyzer.Result r = a.build();
        assertEquals('F', r.grade);
        assertEquals(0, r.score);
        assertEquals(100, r.heavyPct);
    }

    @Test
    public void tuyenHonHop_diemVua() {
        // 5 clear + 5 moderate => score = 100*(5*1.0 + 5*0.5)/10 = 75 => B
        RouteQualityAnalyzer a = new RouteQualityAnalyzer();
        for (int i = 0; i < 5; i++) a.add(Level.CLEAR);
        for (int i = 0; i < 5; i++) a.add(Level.MODERATE);
        RouteQualityAnalyzer.Result r = a.build();
        assertEquals(75, r.score);
        assertEquals('B', r.grade);
    }

    @Test
    public void mauThieuDuLieu_khongLamHongDiem() {
        // 4 clear + 6 no-data => điểm chỉ tính trên 4 clear => 100 = A,
        // nhưng % vẫn phản ánh 60% thiếu dữ liệu.
        RouteQualityAnalyzer a = new RouteQualityAnalyzer();
        for (int i = 0; i < 4; i++) a.add(Level.CLEAR);
        for (int i = 0; i < 6; i++) a.add(Level.NO_DATA);
        RouteQualityAnalyzer.Result r = a.build();
        assertEquals('A', r.grade);
        assertEquals(100, r.score);
        assertEquals(60, r.noDataPct);
        assertEquals(40, r.clearPct);
        assertTrue(r.hasGrade());
    }

    @Test
    public void toanBoThieuDuLieu_khongChamDuoc() {
        RouteQualityAnalyzer a = new RouteQualityAnalyzer();
        for (int i = 0; i < 5; i++) a.add(Level.NO_DATA);
        RouteQualityAnalyzer.Result r = a.build();
        assertEquals('?', r.grade);
        assertFalse(r.hasGrade());
        assertEquals(100, r.noDataPct);
    }
}
