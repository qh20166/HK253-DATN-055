package com.example.traffigo.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Locale;

public class OfflineDetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_detail);

        String path = getIntent().getStringExtra("path");
        String title = getIntent().getStringExtra("title");
        double lat = getIntent().getDoubleExtra("lat", 0);
        double lng = getIntent().getDoubleExtra("lng", 0);

        // Header
        View header = findViewById(R.id.layoutHeader);
        TextView tvHeaderTitle = header.findViewById(R.id.tvPageTitle);
        ImageButton btnBack = header.findViewById(R.id.btnBack);
        if (tvHeaderTitle != null) tvHeaderTitle.setText(title != null ? title : getString(R.string.offline_detail_default_title));
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Coordinates label
        if (lat != 0 && lng != 0) {
            View layoutCoords = findViewById(R.id.layoutCoords);
            TextView tvCoords = findViewById(R.id.tvCoords);
            layoutCoords.setVisibility(View.VISIBLE);
            tvCoords.setText(String.format(Locale.getDefault(), "%.4f, %.4f", lat, lng));
        }

        // Async bitmap load — avoids blocking main thread on large PNG
        PhotoView photoView = findViewById(R.id.imgFullMap);
        if (path != null) {
            new Thread(() -> {
                Bitmap bmp = BitmapFactory.decodeFile(path);
                runOnUiThread(() -> photoView.setImageBitmap(bmp));
            }).start();
        }

        // "Mở trong bản đồ" — jump to saved location in NavigationActivity
        MaterialButton btnOpenInMap = findViewById(R.id.btnOpenInMap);
        btnOpenInMap.setOnClickListener(v -> {
            Intent intent = new Intent(this, NavigationActivity.class);
            if (lat != 0 && lng != 0) {
                intent.putExtra("jump_lat", lat);
                intent.putExtra("jump_lng", lng);
            }
            startActivity(intent);
        });

        // Delete
        MaterialButton btnDelete = findViewById(R.id.btnDeleteMap);
        String finalPath = path;
        String finalTitle = title;
        btnDelete.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_delete_title)
                        .setMessage(getString(R.string.dialog_delete_msg, finalTitle))
                        .setPositiveButton(R.string.btn_delete, (d, w) -> {
                            if (finalPath != null) {
                                new File(finalPath).delete();
                                new File(finalPath.replace(".png", ".meta")).delete();
                            }
                            finish();
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show()
        );
    }
}
