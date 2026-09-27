package com.example.traffigo.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class AppearanceHelper {
    private static final String PREF_NAME = "AppCustomizePrefs";
    // Key riêng cho gradient hero (Bento). Không tái dùng KEY_BG cũ vì máy người dùng có thể
    // đã lưu sẵn giá trị cũ (vd. bg_white_main từ theme trước) không hợp lệ cho ý nghĩa mới.
    private static final String KEY_HERO_ACCENT = "hero_accent_res";
    private static final String KEY_CARD_COLOR = "custom_card_color";
    private static final String KEY_TEXT_COLOR = "custom_text_color";
    private static final String KEY_FONT_SCALE = "font_scale";
    private static final String KEY_CORNER_RADIUS = "corner_radius";

    public static void saveCustomBackground(Context context, int resId) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HERO_ACCENT, resId).apply();
    }

    public static int getCustomBackground(Context context, int defaultResId) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_HERO_ACCENT, defaultResId);
    }

    public static void saveCustomCardColor(Context context, String hexColor) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_CARD_COLOR, hexColor).apply();
    }

    public static String getCustomCardColor(Context context, String defaultHexColor) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CARD_COLOR, defaultHexColor);
    }

    public static void saveCustomTextColor(Context context, String hexColor) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TEXT_COLOR, hexColor).apply();
    }

    public static String getCustomTextColor(Context context, String defaultHexColor) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TEXT_COLOR, defaultHexColor);
    }

    // Font scale: 0.85 = nhỏ, 1.0 = mặc định, 1.2 = lớn
    public static void saveFontScale(Context context, float scale) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_FONT_SCALE, scale).apply();
    }

    public static float getFontScale(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_FONT_SCALE, 1.0f);
    }

    // Corner radius dp: 16 = góc nhọn, 32 = bo tròn (mặc định), 48 = bo nhiều
    public static void saveCornerRadius(Context context, float dp) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_CORNER_RADIUS, dp).apply();
    }

    public static float getCornerRadius(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_CORNER_RADIUS, 32f);
    }
}
