package com.example.traffigo.models;

import com.google.android.gms.maps.model.LatLng;

/** Một bước rẽ trong lộ trình (turn-by-turn), parse từ Google Routes API legs.steps. */
public class NavStep {
    public final String instruction;   // VD: "Rẽ phải vào Lê Lợi"
    public final String maneuver;      // VD: "TURN_RIGHT", "DEPART", "STRAIGHT"
    public final LatLng endLatLng;     // điểm kết thúc bước này
    public final int distanceMeters;   // độ dài bước này

    public NavStep(String instruction, String maneuver, LatLng endLatLng, int distanceMeters) {
        this.instruction = instruction;
        this.maneuver = maneuver;
        this.endLatLng = endLatLng;
        this.distanceMeters = distanceMeters;
    }
}
