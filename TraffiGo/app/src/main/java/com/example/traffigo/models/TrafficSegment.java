package com.example.traffigo.models;

import com.google.android.gms.maps.model.LatLng;
import java.util.ArrayList;
import java.util.List;

public class TrafficSegment {
    public String name;
    public LatLng start;
    public LatLng end;
    // Lưu riêng lat/lon để dễ tính toán bounds mà không cần khởi tạo LatLng liên tục
    public double lat1, lon1, lat2, lon2;
    public double speed;
    public double congestionIndex;
    public String fullGeometry;

    // Từ tag OSM (geometry.csv cột 7/8/9) — null/rỗng nếu OSM không khai báo, không suy đoán.
    // Chỉ NavigationActivity.preloadSegments() điền các field này (đọc geometry.csv); các nơi khác
    // dựng TrafficSegment (TrafficMapActivity, RouteDetailsActivity) để mặc định null/rỗng.
    public Integer speedLimitKmh;
    public String oneway = "";
    public String highwayType = "";

    public TrafficSegment(String name, double sLat, double sLon, double eLat, double eLon,
                          double speed, double congestion, String fullGeometry) {
        this.name = name;
        this.lat1 = sLat;
        this.lon1 = sLon;
        this.lat2 = eLat;
        this.lon2 = eLon;
        this.start = new LatLng(sLat, sLon);
        this.end = new LatLng(eLat, eLon);
        this.speed = speed;
        this.congestionIndex = congestion;
        this.fullGeometry = fullGeometry;
    }

    /**
     * Tiện ích: Chuyển chuỗi fullGeometry thành List LatLng để vẽ Polyline ngay lập tức
     */
    public List<LatLng> getGeometryList() {
        List<LatLng> points = new ArrayList<>();
        if (fullGeometry == null || fullGeometry.isEmpty()) return points;

        String[] pairs = fullGeometry.split(";");
        for (String pair : pairs) {
            String[] latLon = pair.split(",");
            if (latLon.length == 2) {
                try {
                    points.add(new LatLng(
                            Double.parseDouble(latLon[0]),
                            Double.parseDouble(latLon[1])
                    ));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }
        }
        return points;
    }
}