package com.example.traffigo.models;

public class NewsItem {
    public String title, link, imageUrl, pubDate, description;

    public NewsItem(String title, String link, String imageUrl, String pubDate, String description) {
        this.title = title;
        this.link = link;
        this.imageUrl = imageUrl;
        this.pubDate = pubDate;
        this.description = description;
    }
}