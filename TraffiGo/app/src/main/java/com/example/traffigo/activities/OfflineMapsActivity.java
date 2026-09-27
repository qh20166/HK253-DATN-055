package com.example.traffigo.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;
import com.example.traffigo.adapters.OfflineMapAdapter;
import com.example.traffigo.models.OfflineMap;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OfflineMapsActivity extends AppCompatActivity {

    private RecyclerView rvOfflineMaps;
    private OfflineMapAdapter adapter;
    private List<OfflineMap> offlineMapList;
    private View tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_maps);

        rvOfflineMaps = findViewById(R.id.rvOfflineMaps);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvOfflineMaps.setLayoutManager(new LinearLayoutManager(this));
        offlineMapList = new ArrayList<>();
        setupHeader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSavedMaps();
    }

    private void setupHeader() {
        View header = findViewById(R.id.layoutHeader);
        TextView tvTitle = header.findViewById(R.id.tvPageTitle);
        ImageButton btnBack = header.findViewById(R.id.btnBack);

        if (tvTitle != null) tvTitle.setText(R.string.offline_title);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        View btnAdd = findViewById(R.id.fabAddOfflineMap);
        if (btnAdd != null) {
            btnAdd.setVisibility(View.VISIBLE);
            btnAdd.setOnClickListener(v -> startActivity(new Intent(this, SaveOfflineMapActivity.class)));
        }
    }

    private void loadSavedMaps() {
        offlineMapList.clear();
        File[] files = getFilesDir().listFiles((dir, name) -> name.startsWith("OFFLINE_") && name.endsWith(".png"));

        if (files != null && files.length > 0) {
            java.util.Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            tvEmpty.setVisibility(View.GONE);

            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
            for (File file : files) {
                String rawName = file.getName();
                String displayName = rawName.substring(rawName.indexOf("_") + 1, rawName.lastIndexOf("."));
                String date = getString(R.string.offline_saved_date, sdf.format(new Date(file.lastModified())));

                long sizeKb = file.length() / 1024;
                String sizeStr = sizeKb > 1024
                        ? String.format(Locale.getDefault(), "%.1f MB", sizeKb / 1024f)
                        : sizeKb + " KB";

                double lat = 0, lng = 0;
                float zoom = 15f;
                File metaFile = new File(getFilesDir(), rawName.replace(".png", ".meta"));
                if (metaFile.exists()) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        BufferedReader br = new BufferedReader(new FileReader(metaFile));
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        JSONObject meta = new JSONObject(sb.toString());
                        lat = meta.getDouble("lat");
                        lng = meta.getDouble("lng");
                        zoom = (float) meta.getDouble("zoom");
                    } catch (Exception ignored) {}
                }

                offlineMapList.add(new OfflineMap(displayName, file.getAbsolutePath(), date, sizeStr, lat, lng, zoom));
            }
        } else {
            tvEmpty.setVisibility(View.VISIBLE);
        }

        if (adapter == null) {
            adapter = new OfflineMapAdapter(offlineMapList,
                    map -> {
                        Intent intent = new Intent(this, OfflineDetailActivity.class);
                        intent.putExtra("path", map.imagePath);
                        intent.putExtra("title", map.title);
                        intent.putExtra("lat", map.lat);
                        intent.putExtra("lng", map.lng);
                        startActivity(intent);
                    },
                    (map, position) -> confirmDelete(map, position));
            rvOfflineMaps.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void confirmDelete(OfflineMap map, int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getString(R.string.dialog_delete_msg, map.title))
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    new File(map.imagePath).delete();
                    new File(map.imagePath.replace(".png", ".meta")).delete();
                    if (position < 0 || position >= offlineMapList.size()) return;
                    offlineMapList.remove(position);
                    adapter.notifyItemRemoved(position);
                    if (offlineMapList.isEmpty()) tvEmpty.setVisibility(View.VISIBLE);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }
}
