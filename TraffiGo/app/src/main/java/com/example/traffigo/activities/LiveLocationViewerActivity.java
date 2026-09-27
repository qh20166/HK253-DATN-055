package com.example.traffigo.activities;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;
import com.example.traffigo.databinding.ActivityLiveLocationViewerBinding;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Mở qua deep link traffigo://live/{sessionId} khi ai đó bấm link được chia sẻ
 * từ tính năng "Chia sẻ vị trí trực tiếp" trong NavigationActivity.
 * Lắng nghe Firebase liên tục để cập nhật vị trí người chia sẻ theo thời gian thực.
 */
public class LiveLocationViewerActivity extends AppCompatActivity implements OnMapReadyCallback {

    private ActivityLiveLocationViewerBinding binding;
    private GoogleMap map;
    private Marker liveMarker;
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;
    private boolean firstUpdate = true;
    // Vị trí/tên mới nhất nhận từ Firebase; giữ lại để vẽ được cả khi dữ liệu về trước lúc map sẵn sàng
    private LatLng lastPos;
    private String lastName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLiveLocationViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.layoutHeader.tvPageTitle.setText(R.string.live_viewer_title);
        binding.layoutHeader.btnBack.setOnClickListener(v -> finish());
        binding.layoutHeader.btnShareContainer.setVisibility(View.GONE);
        binding.layoutHeader.btnVoiceMicContainer.setVisibility(View.GONE);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapLiveLocation);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        String sessionId = extractSessionId();
        if (sessionId == null) {
            Toast.makeText(this, getString(R.string.live_viewer_not_found), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        listenToSession(sessionId);
    }

    private String extractSessionId() {
        Uri data = getIntent().getData();
        if (data == null) return null;
        return data.getLastPathSegment();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        // Dữ liệu có thể đã về trước khi map sẵn sàng (cache Firebase) → vẽ lại vị trí đã nhận
        if (lastPos != null) renderMarker(lastPos, lastName);
    }

    private void listenToSession(String sessionId) {
        sessionRef = FirebaseDatabase.getInstance().getReference("LiveShares").child(sessionId);
        sessionListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(LiveLocationViewerActivity.this,
                            getString(R.string.live_viewer_not_found), Toast.LENGTH_LONG).show();
                    return;
                }
                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);
                String name = snapshot.child("name").getValue(String.class);
                String address = snapshot.child("address").getValue(String.class);
                Boolean active = snapshot.child("active").getValue(Boolean.class);
                Long updatedAt = snapshot.child("updatedAt").getValue(Long.class);
                if (lat == null || lng == null) return;

                updateUi(new LatLng(lat, lng), name, address,
                        active != null && active, updatedAt != null ? updatedAt : 0);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* mất kết nối tạm thời, giữ nguyên UI cuối cùng */ }
        };
        sessionRef.addValueEventListener(sessionListener);
    }

    private void updateUi(LatLng pos, String name, String address, boolean active, long updatedAt) {
        binding.tvLiveName.setText(name != null && !name.isEmpty() ? name : getString(R.string.live_viewer_default_name));
        binding.tvLiveAddress.setText(address != null ? address : "");

        if (active) {
            binding.tvLiveStatus.setText(R.string.live_viewer_status_live);
            binding.tvLiveStatus.setTextColor(0xFFD32F2F);
        } else {
            binding.tvLiveStatus.setText(R.string.live_viewer_status_stopped);
            binding.tvLiveStatus.setTextColor(0xFF9691AC);
        }

        long secondsAgo = (System.currentTimeMillis() - updatedAt) / 1000;
        binding.tvLiveUpdatedAt.setText(secondsAgo < 5
                ? getString(R.string.live_viewer_updated_now)
                : getString(R.string.live_viewer_updated_seconds, (int) secondsAgo));

        // Lưu lại để onMapReady vẽ được nếu dữ liệu về trước lúc map sẵn sàng
        lastPos = pos;
        lastName = name;
        renderMarker(pos, name);
    }

    /** Vẽ/di chuyển marker theo vị trí mới nhất. An toàn khi map chưa sẵn sàng (bỏ qua, onMapReady sẽ vẽ lại). */
    private void renderMarker(LatLng pos, String name) {
        if (map == null) return;
        if (liveMarker == null) {
            liveMarker = map.addMarker(new MarkerOptions().position(pos)
                    .title(name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        } else {
            liveMarker.setPosition(pos);
        }
        if (firstUpdate) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f));
            firstUpdate = false;
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(pos));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
        }
    }
}
