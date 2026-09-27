package com.example.traffigo.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.widget.ImageView;

import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Lưu/đọc ảnh đại diện dưới dạng Base64 trong Realtime Database
 * (Users/{uid}/avatarBase64) — tránh phụ thuộc Firebase Storage.
 * Ảnh được thu nhỏ về tối đa 256px và nén JPEG nên chuỗi đủ nhỏ để lưu DB.
 */
public class AvatarUtils {

    private static final int MAX_SIZE = 256; // px, cạnh dài nhất

    public static String encodeToBase64(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        float scale = Math.min(1f, (float) MAX_SIZE / Math.max(w, h));
        Bitmap scaled = (scale < 1f)
                ? Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true)
                : src;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    public static Bitmap decode(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    // Cache bitmap trong bộ nhớ để load lại tức thì, tránh "chớp" ảnh mặc định khi refresh
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    /**
     * Hiển thị avatar của user vào ImageView.
     * Nếu đã có cache thì set ngay (không chớp), rồi vẫn refresh nền từ Database.
     */
    public static void loadInto(String uid, ImageView target) {
        if (uid == null || target == null) return;

        Bitmap cached = CACHE.get(uid);
        if (cached != null) target.setImageBitmap(cached);

        FirebaseDatabase.getInstance().getReference("Users").child(uid).child("avatarBase64")
                .get().addOnSuccessListener(snapshot -> {
                    Bitmap bmp = decode(snapshot.getValue(String.class));
                    if (bmp != null) {
                        CACHE.put(uid, bmp);
                        target.setImageBitmap(bmp);
                    }
                });
    }

    /** Xoá cache (gọi khi đăng xuất để không lẫn avatar giữa các tài khoản). */
    public static void clearCache() {
        CACHE.clear();
    }
}
