package com.example.traffigo.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.traffigo.R;
import com.example.traffigo.adapters.NearbyPlaceAdapter;
import com.example.traffigo.models.NearbyPlace;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tìm địa điểm lân cận theo danh mục (xăng, ATM, ăn uống, bệnh viện, bãi đỗ xe).
 * Dùng Places Nearby Search (REST, rankby=distance) để trả về đúng POI GẦN NHẤT trước,
 * tối đa 20 kết quả — thay cho Autocomplete (xếp theo độ nổi tiếng + khớp chữ, chỉ ~5 kết
 * quả rải rác). Gọi qua HttpURLConnection với Maps key sẵn có (giống các REST call khác),
 * không cần thêm dependency hay API key mới.
 */
public class NearbyPlacesActivity extends AppCompatActivity {

    private static final String NEARBY_URL =
            "https://maps.googleapis.com/maps/api/place/nearbysearch/json";
    private static final LatLng DEFAULT_CENTER = new LatLng(10.7767, 106.6656); // fallback: trung tâm HCMC
    private static final int[] CHIP_IDS = {
            R.id.chipGas, R.id.chipAtm, R.id.chipFood, R.id.chipHospital, R.id.chipParking};

    private NearbyPlaceAdapter adapter;
    private ProgressBar progressBar;
    private View layoutEmpty;
    private RecyclerView rvNearby;
    private LatLng currentCenter = DEFAULT_CENTER;
    private String apiKey;
    private int searchToken = 0; // đánh số mỗi lần tìm để bỏ kết quả của lần bấm cũ (chống race khi bấm nhanh)
    private String lastType = null; // danh mục đang xem, để tìm lại khi vị trí GPS về sau lần bấm đầu

    private ActivityResultLauncher<String> locationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby_places);

        setupHeader();
        apiKey = readApiKey();

        rvNearby = findViewById(R.id.rvNearby);
        rvNearby.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NearbyPlaceAdapter(this::onPlaceSelected);
        rvNearby.setAdapter(adapter);

        progressBar = findViewById(R.id.progressNearby);
        layoutEmpty = findViewById(R.id.layoutEmpty);

        setupCategoryChips();

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) fetchCurrentLocation();
                });
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fetchCurrentLocation();
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Mở lên là hiện sẵn danh mục mặc định (Xăng) thay vì màn trống; khi có GPS sẽ tự tìm lại cho đúng.
        selectCategory(R.id.chipGas, "gas_station");
    }

    private void setupHeader() {
        View header = findViewById(R.id.layoutHeader);
        ((android.widget.TextView) header.findViewById(R.id.tvPageTitle)).setText(R.string.nearby_title);
        header.findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private String readApiKey() {
        try {
            ApplicationInfo ai = getPackageManager()
                    .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            return ai.metaData != null ? ai.metaData.getString("com.google.android.geo.API_KEY") : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchCurrentLocation() {
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(this);
        // Ưu tiên fix GPS mới, chính xác cao -> tâm tìm kiếm & khoảng cách đúng.
        // Nếu chưa có fix mới thì lấy tạm vị trí biết gần nhất.
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        updateCenter(new LatLng(loc.getLatitude(), loc.getLongitude()));
                    } else {
                        client.getLastLocation().addOnSuccessListener(last -> {
                            if (last != null) updateCenter(new LatLng(last.getLatitude(), last.getLongitude()));
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    // GPS tắt / chưa có fix: task fail ngay, không fallback thì danh sách
                    // "gần đây" cứ quanh tâm mặc định HCMC mà không có báo lỗi nào.
                    client.getLastLocation().addOnSuccessListener(last -> {
                        if (last != null) updateCenter(new LatLng(last.getLatitude(), last.getLongitude()));
                    });
                });
    }

    /** Cập nhật tâm tìm kiếm; nếu người dùng đã bấm 1 danh mục trước khi có vị trí (đang dùng tâm mặc
     *  định HCMC) thì tự tìm lại quanh vị trí thật cho gần & đúng hơn. */
    private void updateCenter(LatLng c) {
        currentCenter = c;
        if (lastType != null) searchCategory(lastType);
    }

    private void setupCategoryChips() {
        // Mỗi chip là một place type chuẩn của Google (Nearby Search) -> kết quả đúng loại,
        // dày đặc, không khớp nhầm tên công ty có chứa cụm từ khóa.
        bindChip(R.id.chipGas, "gas_station");
        bindChip(R.id.chipAtm, "atm");
        bindChip(R.id.chipFood, "restaurant");
        bindChip(R.id.chipHospital, "hospital");
        bindChip(R.id.chipParking, "parking");
    }

    private void bindChip(int chipId, String type) {
        Chip chip = findViewById(chipId);
        chip.setOnClickListener(v -> selectCategory(chipId, type));
    }

    /** Highlight chip đang chọn rồi tìm danh mục đó. */
    private void selectCategory(int chipId, String type) {
        for (int id : CHIP_IDS) {
            Chip c = findViewById(id);
            boolean sel = id == chipId;
            c.setChipBackgroundColor(ColorStateList.valueOf(sel ? 0xFF5C4FE0 : 0xFFFFFFFF));
            c.setTextColor(sel ? 0xFFFFFFFF : 0xFF212121);
        }
        searchCategory(type);
    }

    private void searchCategory(String type) {
        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.nearby_no_results), Toast.LENGTH_SHORT).show();
            return;
        }
        lastType = type; // nhớ danh mục đang xem để tìm lại khi có vị trí chính xác hơn
        progressBar.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        rvNearby.setVisibility(View.GONE);

        final LatLng center = currentCenter;
        final int token = ++searchToken;
        new Thread(() -> {
            List<NearbyPlace> places = new ArrayList<>();
            boolean error = false;
            HttpURLConnection conn = null;
            try {
                // rankby=distance -> gần nhất trước (bắt buộc có type và KHÔNG kèm radius).
                String urlStr = NEARBY_URL
                        + "?location=" + center.latitude + "," + center.longitude
                        + "&rankby=distance"
                        + "&type=" + type
                        + "&language=vi"
                        + "&key=" + apiKey;
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                String status = root.optString("status");
                if ("OK".equals(status) || "ZERO_RESULTS".equals(status)) {
                    JSONArray results = root.optJSONArray("results");
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject r = results.getJSONObject(i);
                            JSONObject loc = r.getJSONObject("geometry").getJSONObject("location");
                            double lat = loc.getDouble("lat"), lng = loc.getDouble("lng");
                            float[] d = new float[1];
                            Location.distanceBetween(center.latitude, center.longitude, lat, lng, d);
                            places.add(new NearbyPlace(r.optString("name"),
                                    r.optString("vicinity", ""), lat, lng, d[0]));
                        }
                    }
                } else {
                    error = true;
                }
            } catch (Exception e) {
                error = true;
            } finally {
                if (conn != null) conn.disconnect();
            }
            final boolean hadError = error;
            runOnUiThread(() -> {
                // Bỏ nếu activity đã thoát, hoặc đã có lần tìm mới hơn (kết quả cũ về trễ)
                if (isFinishing() || isDestroyed() || token != searchToken) return;
                progressBar.setVisibility(View.GONE);
                adapter.setPlaces(places);
                showResults(places.isEmpty());
                if (hadError) {
                    Toast.makeText(this, getString(R.string.nearby_no_results), Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void showResults(boolean empty) {
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvNearby.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /**
     * Chọn 1 kết quả -> mở NavigationActivity với đích đã điền sẵn, tái dùng đúng cơ chế
     * "Đi đến đây" (extras voice_dest_lat/lng, voice_auto_route). Nearby Search đã trả sẵn
     * toạ độ nên không cần gọi thêm fetchPlace.
     */
    private void onPlaceSelected(NearbyPlace place) {
        Intent intent = new Intent(this, NavigationActivity.class);
        intent.putExtra("voice_dest_lat", place.lat);
        intent.putExtra("voice_dest_lng", place.lng);
        intent.putExtra("voice_dest_address", place.name);
        intent.putExtra("voice_auto_route", true);
        startActivity(intent);
        finish();
    }
}
