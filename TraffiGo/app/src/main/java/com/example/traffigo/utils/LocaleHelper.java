package com.example.traffigo.utils;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Đổi ngôn ngữ toàn app bằng per-app language API của AppCompat.
 * Hệ thống tự lưu & khôi phục lựa chọn (autoStoreLocales=true trong Manifest).
 */
public class LocaleHelper {

    public static final String VI = "vi";
    public static final String EN = "en";

    /** Đặt ngôn ngữ ("vi" hoặc "en"). Activity sẽ tự recreate. */
    public static void setLanguage(String langTag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTag));
    }

    /** Lấy ngôn ngữ hiện tại; mặc định "vi" nếu chưa đặt. */
    public static String getLanguage() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) return VI;
        String lang = locales.get(0).getLanguage();
        return EN.equals(lang) ? EN : VI;
    }
}
