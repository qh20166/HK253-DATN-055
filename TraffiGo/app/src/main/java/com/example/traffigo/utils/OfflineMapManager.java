package com.example.traffigo.utils;

import android.content.Context;
import android.graphics.Bitmap;
import com.google.android.gms.maps.GoogleMap;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class OfflineMapManager {

    // Hàm chụp ảnh bản đồ và lưu lại
    public static void saveMapOffline(Context context, GoogleMap mMap, String title, OnSaveListener listener) {
        mMap.snapshot(new GoogleMap.SnapshotReadyCallback() {
            @Override
            public void onSnapshotReady(Bitmap bitmap) {
                try {
                    // Tạo tên file dựa trên thời gian
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    String fileName = "OFFLINE_" + timeStamp + ".png";

                    // Lưu vào bộ nhớ riêng của App (Internal Storage) - Không cần xin quyền Storage
                    File file = new File(context.getFilesDir(), fileName);
                    FileOutputStream out = new FileOutputStream(file);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
                    out.flush();
                    out.close();

                    // Trả kết quả về cho UI
                    listener.onSuccess(file.getAbsolutePath());
                } catch (Exception e) {
                    listener.onFailure(e.getMessage());
                }
            }
        });
    }

    public interface OnSaveListener {
        void onSuccess(String path);
        void onFailure(String error);
    }
}