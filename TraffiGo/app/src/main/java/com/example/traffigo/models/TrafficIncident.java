package com.example.traffigo.models;

import java.util.Map;

/**
 * Một báo cáo sự cố giao thông do người dùng gửi (kiểu Waze): kẹt xe, tai nạn, ngập, CSGT, vật cản.
 * Lưu tại Firebase node "incidents/{id}". Có constructor rỗng để Firebase tự map.
 */
public class TrafficIncident {
    public String id;        // key trên Firebase (gán khi đọc về)
    public String type;      // "jam" | "accident" | "flood" | "police" | "hazard"
    public double lat;
    public double lng;
    public long timestamp;   // mốc thời gian báo (millis)
    public String uid;       // người báo
    // uid -> "up" | "down". Map theo uid (không phải bộ đếm rời) để 1 người chỉ tính 1 phiếu dù bấm
    // lại nhiều lần - ghi đè value cũ thay vì cộng dồn. null với các báo cáo cũ trước khi có tính năng
    // này (Firebase bỏ qua field không có, không lỗi).
    public Map<String, String> votes;

    public TrafficIncident() {
    }

    public TrafficIncident(String type, double lat, double lng, long timestamp, String uid) {
        this.type = type;
        this.lat = lat;
        this.lng = lng;
        this.timestamp = timestamp;
        this.uid = uid;
    }

    public int upvoteCount() {
        return countVotes("up");
    }

    public int downvoteCount() {
        return countVotes("down");
    }

    private int countVotes(String value) {
        if (votes == null) return 0;
        int count = 0;
        for (String v : votes.values()) {
            if (value.equals(v)) count++;
        }
        return count;
    }
}
