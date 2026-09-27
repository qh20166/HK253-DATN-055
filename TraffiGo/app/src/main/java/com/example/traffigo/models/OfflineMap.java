package com.example.traffigo.models;

public class OfflineMap {
    public String title;
    public String imagePath;
    public String dateTime;
    public String fileSizeStr;
    public double lat;
    public double lng;
    public float zoom;

    public OfflineMap(String title, String imagePath, String dateTime,
                      String fileSizeStr, double lat, double lng, float zoom) {
        this.title = title;
        this.imagePath = imagePath;
        this.dateTime = dateTime;
        this.fileSizeStr = fileSizeStr;
        this.lat = lat;
        this.lng = lng;
        this.zoom = zoom;
    }
}
