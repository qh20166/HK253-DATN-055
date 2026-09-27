package com.example.traffigo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.traffigo.utils.ClusterColorAssigner;
import com.example.traffigo.utils.ClusterColorAssigner.Features;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Unit test cho F3 (lớp thành thật dữ liệu) + logic phân cụm.
 * Nạp cluster_model.json THẬT từ assets để bám sát hành vi trên thiết bị.
 */
public class ClusterColorAssignerTest {

    private ClusterColorAssigner assigner;

    @Before
    public void setUp() throws Exception {
        // Unit test chạy với working dir = thư mục module (app/)
        File model = new File("src/main/assets/cluster_model.json");
        assertTrue("Không tìm thấy cluster_model.json để test", model.exists());
        String json = new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
        assigner = new ClusterColorAssigner();
        assigner.loadFromJson(json);
        assertTrue("Model phải nạp thành công", assigner.isLoaded());
    }

    /** freeFlowSpeed <= 0 nghĩa là TomTom không có dữ liệu -> đánh dấu thiếu dữ liệu. */
    @Test
    public void isNoData_khiKhongCoTocDoDongTuDo() {
        Features noData = new Features();
        noData.freeFlowSpeed = 0.0;
        assertTrue(ClusterColorAssigner.isNoData(noData));

        Features hasData = new Features();
        hasData.freeFlowSpeed = 40.0;
        assertFalse(ClusterColorAssigner.isNoData(hasData));
    }

    /** classify() với đoạn thiếu dữ liệu phải trả màu xám + cờ noData, KHÔNG suy đoán trạng thái. */
    @Test
    public void classify_thieuDuLieu_traMauXam() {
        Features f = new Features();
        f.freeFlowSpeed = 0.0; // no data
        ClusterColorAssigner.ClusterResult r = assigner.classify(f);

        assertTrue(r.noData);
        assertEquals(-1, r.severityRank);
        assertEquals(ClusterColorAssigner.NO_DATA_COLOR, r.color);
    }

    /** Đoạn kẹt nặng (giống profile cụm 2) phải xếp severity thấp hơn đoạn thoáng (cụm 0). */
    @Test
    public void classify_ketNangNghiemTrongHonThongThoang() {
        ClusterColorAssigner.ClusterResult heavy = assigner.classify(heavyFeatures());
        ClusterColorAssigner.ClusterResult clear = assigner.classify(clearFeatures());

        assertFalse(heavy.noData);
        assertFalse(clear.noData);
        // rank nhỏ = kẹt nặng hơn (quy ước dự án). Kẹt phải < thoáng.
        assertTrue("Đoạn kẹt phải có severityRank thấp hơn đoạn thoáng",
                heavy.severityRank < clear.severityRank);
        assertTrue("Đoạn kẹt nặng phải rơi vào nhóm nghiêm trọng (rank <= 1)",
                heavy.severityRank <= 1);
        assertTrue("Đoạn thoáng phải rơi vào nhóm nhẹ (rank >= 4)",
                clear.severityRank >= 4);
    }

    /** Đặc trưng bám theo profile thực của cụm 2 (lõi đô thị kẹt nặng). */
    private static Features heavyFeatures() {
        double cur = 20.0, free = 32.0;
        Features f = new Features();
        f.freeFlowSpeed = free;
        f.congestionIndex = 0.62;
        f.speedLimitRatio = cur / free;               // ~0.40
        f.occupancy = 100 * (1 - cur / free);          // ~37.5
        f.relativeCongestionIndex = (free - cur) / free; // ~0.375
        f.trafficVolume = 1073;
        f.lengthKm = 0.55;
        f.crossTime = 0.10;
        f.curvatureIndex = 0.01;
        return f;
    }

    /** Đặc trưng bám theo profile thực của cụm 0 (đường thông thoáng, tốc độ cao). */
    private static Features clearFeatures() {
        double cur = 42.0, free = 44.0;
        Features f = new Features();
        f.freeFlowSpeed = free;
        f.congestionIndex = 0.97;
        f.speedLimitRatio = cur / free;               // ~0.95
        f.occupancy = 100 * (1 - cur / free);          // ~4.5
        f.relativeCongestionIndex = (free - cur) / free; // ~0.045
        f.trafficVolume = 420;
        f.lengthKm = 0.80;
        f.crossTime = 0.07;
        f.curvatureIndex = 0.01;
        return f;
    }
}
