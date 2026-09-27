package com.example.traffigo.utils;

import android.location.Location;
import com.example.traffigo.models.TrafficSegment;
import com.google.android.gms.maps.model.LatLng;
import java.util.*;

public class PathFinder {
    private final Map<String, List<TrafficSegment>> graph = new HashMap<>();

    public void buildGraph(List<TrafficSegment> segments) {
        graph.clear();
        for (TrafficSegment s : segments) {
            String key = s.lat1 + "," + s.lon1;
            graph.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
    }

    public String findNearestNode(LatLng point, List<TrafficSegment> segments) {
        double minDistance = Double.MAX_VALUE;
        String nearestKey = "";
        for (TrafficSegment s : segments) {
            float[] res = new float[1];
            Location.distanceBetween(point.latitude, point.longitude, s.lat1, s.lon1, res);
            if (res[0] < minDistance) {
                minDistance = res[0];
                nearestKey = s.lat1 + "," + s.lon1;
            }
        }
        return nearestKey;
    }

    public List<LatLng> findFastestPath(String startKey, String endKey) {
        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.time));
        Map<String, Double> minTime = new HashMap<>();
        Map<String, TrafficSegment> backPointer = new HashMap<>();

        pq.add(new Node(startKey, 0));
        minTime.put(startKey, 0.0);

        while (!pq.isEmpty()) {
            Node current = pq.poll();
            if (current.id.equals(endKey)) break;
            if (current.time > minTime.getOrDefault(current.id, Double.MAX_VALUE)) continue;

            List<TrafficSegment> neighbors = graph.get(current.id);
            if (neighbors == null) continue;

            for (TrafficSegment edge : neighbors) {
                String neighborId = edge.lat2 + "," + edge.lon2;
                double weight = calculateWeight(edge);
                double newTime = current.time + weight;

                if (newTime < minTime.getOrDefault(neighborId, Double.MAX_VALUE)) {
                    minTime.put(neighborId, newTime);
                    backPointer.put(neighborId, edge);
                    pq.add(new Node(neighborId, newTime));
                }
            }
        }
        return reconstructPath(endKey, backPointer);
    }

    // F1 - định tuyến né cụm kẹt: phạt tối đa cho đoạn kẹt nặng nhất. 1.6 = né kẹt vừa phải,
    // không dẫn vòng vô lý (đoạn kẹt nặng "đắt" gấp 1.6 lần thời gian đi thực).
    static final double MAX_CONGESTION_PENALTY = 1.6;

    private double calculateWeight(TrafficSegment s) {
        float[] res = new float[1];
        Location.distanceBetween(s.lat1, s.lon1, s.lat2, s.lon2, res);
        double speedMs = (s.speed > 0) ? s.speed * 0.27778 : 0.5;
        double baseTime = res[0] / speedMs;
        return baseTime * congestionPenalty(s.congestionIndex);
    }

    /**
     * Hệ số phạt theo mức kẹt để thuật toán ưu tiên đoạn thuộc cụm thoáng.
     * Quy ước dự án: congestionIndex THẤP = kẹt, CAO = thoáng (xem CLAUDE.md).
     *  - congestionIndex >= 0.85 (thoáng)  -> 1.0 (không phạt)
     *  - congestionIndex <= 0.30 (kẹt nặng) -> {@link #MAX_CONGESTION_PENALTY}
     *  - ở giữa: nội suy tuyến tính.
     * congestionIndex <= 0 nghĩa là KHÔNG rõ dữ liệu -> không phạt (tránh bóp méo tuyến).
     */
    static double congestionPenalty(double congestionIndex) {
        if (congestionIndex <= 0.0) return 1.0;
        double clamped = Math.max(0.30, Math.min(0.85, congestionIndex));
        double heaviness = (0.85 - clamped) / (0.85 - 0.30); // 0 (thoáng) .. 1 (kẹt nặng)
        return 1.0 + (MAX_CONGESTION_PENALTY - 1.0) * heaviness;
    }

    private List<LatLng> reconstructPath(String endId, Map<String, TrafficSegment> backPointer) {
        LinkedList<LatLng> fullPath = new LinkedList<>();
        String current = endId;
        while (backPointer.containsKey(current)) {
            TrafficSegment segment = backPointer.get(current);
            List<LatLng> geo = segment.getGeometryList();
            for (int i = geo.size() - 1; i >= 0; i--) fullPath.addFirst(geo.get(i));
            current = segment.lat1 + "," + segment.lon1;
        }
        return fullPath;
    }

    private static class Node {
        String id; double time;
        Node(String id, double time) { this.id = id; this.time = time; }
    }
}