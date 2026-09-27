package com.example.traffigo.models;

/** Một địa điểm lân cận trả về từ Places Nearby Search (REST). */
public class NearbyPlace {
    public final String name;
    public final String address;
    public final double lat;
    public final double lng;
    public final float distanceMeters;

    public NearbyPlace(String name, String address, double lat, double lng, float distanceMeters) {
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.distanceMeters = distanceMeters;
    }
}
