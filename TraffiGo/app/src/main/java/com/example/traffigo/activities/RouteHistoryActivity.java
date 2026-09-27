package com.example.traffigo.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.traffigo.R;
import com.example.traffigo.adapters.RouteHistoryAdapter;
import com.example.traffigo.databinding.ActivityRouteHistoryBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Xem lại các tuyến đường đã đi; bấm vào 1 mục để đi lại y hệt (không cần chọn lại điểm đi/đến). */
public class RouteHistoryActivity extends AppCompatActivity {

    private static final int MAX_ITEMS = 30;

    private ActivityRouteHistoryBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRouteHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.layoutHeader.tvPageTitle.setText(R.string.history_title);
        binding.layoutHeader.btnBack.setOnClickListener(v -> finish());
        if (binding.layoutHeader.btnShare != null) binding.layoutHeader.btnShareContainer.setVisibility(View.GONE);

        binding.rvHistory.setLayoutManager(new LinearLayoutManager(this));
        loadHistory();
    }

    private void loadHistory() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            showEmpty(true);
            return;
        }
        Query query = FirebaseDatabase.getInstance().getReference("Users")
                .child(uid).child("routeHistory")
                .orderByChild("timestamp")
                .limitToLast(MAX_ITEMS);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<RouteHistoryAdapter.HistoryEntry> entries = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String originAddress = child.child("originAddress").getValue(String.class);
                    String destAddress = child.child("destAddress").getValue(String.class);
                    Double originLat = child.child("originLat").getValue(Double.class);
                    Double originLng = child.child("originLng").getValue(Double.class);
                    Double destLat = child.child("destLat").getValue(Double.class);
                    Double destLng = child.child("destLng").getValue(Double.class);
                    Long timestamp = child.child("timestamp").getValue(Long.class);
                    if (originLat == null || originLng == null || destLat == null || destLng == null) continue;
                    entries.add(new RouteHistoryAdapter.HistoryEntry(
                            originAddress != null ? originAddress : "",
                            originLat, originLng,
                            destAddress != null ? destAddress : "",
                            destLat, destLng,
                            formatRelativeTime(timestamp != null ? timestamp : 0)));
                }
                Collections.reverse(entries); // mới nhất lên đầu
                showEmpty(entries.isEmpty());
                RouteHistoryAdapter adapter = new RouteHistoryAdapter(entries, RouteHistoryActivity.this::openReplay);
                binding.rvHistory.setAdapter(adapter);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                showEmpty(true);
            }
        });
    }

    private void openReplay(RouteHistoryAdapter.HistoryEntry entry) {
        Intent intent = new Intent(this, NavigationActivity.class);
        intent.putExtra("replay_route", true);
        intent.putExtra("replay_origin_lat", entry.originLat);
        intent.putExtra("replay_origin_lng", entry.originLng);
        intent.putExtra("replay_origin_address", entry.originAddress);
        intent.putExtra("replay_dest_lat", entry.destLat);
        intent.putExtra("replay_dest_lng", entry.destLng);
        intent.putExtra("replay_dest_address", entry.destAddress);
        startActivity(intent);
    }

    private void showEmpty(boolean empty) {
        binding.layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvHistory.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private String formatRelativeTime(long timestamp) {
        if (timestamp <= 0) return "";
        long diffMs = System.currentTimeMillis() - timestamp;
        long minutes = diffMs / 60000;
        if (minutes < 1) return getString(R.string.time_just_now);
        if (minutes < 60) return getString(R.string.time_minutes_ago, (int) minutes);
        long hours = minutes / 60;
        if (hours < 24) return getString(R.string.time_hours_ago, (int) hours);
        long days = hours / 24;
        return getString(R.string.time_days_ago, (int) days);
    }
}
