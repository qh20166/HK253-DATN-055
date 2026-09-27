package com.example.traffigo.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.traffigo.R;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.Task;

import java.util.List;
import java.util.Locale;

public class ShareLocationActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_CHECK_SETTINGS = 214;

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private TextView tvAddress, tvCoordinates;
    private LatLng selectedLatLng;
    private Marker selectionMarker;
    private boolean isFirstLocationLoad = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_location);

        tvAddress = findViewById(R.id.tvAddress);
        tvCoordinates = findViewById(R.id.tvCoordinates);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnCurrentLoc).setOnClickListener(v -> {
            isFirstLocationLoad = true;
            checkPermissionAndEnableGPS();
        });

        findViewById(R.id.btnShareNow).setOnClickListener(v -> {
            if (selectedLatLng != null) {
                shareLocation(selectedLatLng, tvAddress.getText().toString());
            } else {
                Toast.makeText(this, getString(R.string.toast_select_location), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setMapToolbarEnabled(false);

        mMap.setOnMapClickListener(latLng -> {
            isFirstLocationLoad = false;
            updateUI(latLng, true);
        });

        // Vị trí chọn trước khi map ready (nếu có) — vẽ lại để không mất selection
        if (selectedLatLng != null) {
            LatLng pending = selectedLatLng;
            selectedLatLng = null;
            updateUI(pending, true);
        }

        checkPermissionAndEnableGPS();
    }

    private void checkPermissionAndEnableGPS() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (mMap != null) mMap.setMyLocationEnabled(true);
            enableLocationSettings();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void enableLocationSettings() {
        // Tạo request với độ chính xác cao
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(1000);

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);

        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(this, response -> getCurrentLocation());

        task.addOnFailureListener(this, e -> {
            if (e instanceof ResolvableApiException) {
                try {
                    ResolvableApiException resolvable = (ResolvableApiException) e;
                    resolvable.startResolutionForResult(ShareLocationActivity.this, REQUEST_CHECK_SETTINGS);
                } catch (IntentSender.SendIntentException sendEx) { }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void getCurrentLocation() {
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                LatLng current = new LatLng(location.getLatitude(), location.getLongitude());
                if (isFirstLocationLoad) {
                    updateUI(current, true);
                    isFirstLocationLoad = false;
                }
            } else {
                // NẾU NULL -> Ép quét vị trí mới ngay
                requestNewLocationUpdate();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestNewLocationUpdate() {
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setNumUpdates(1); // Chỉ lấy 1 bản tin vị trí duy nhất

        fusedLocationClient.requestLocationUpdates(locationRequest, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLastLocation() != null) {
                    LatLng current = new LatLng(locationResult.getLastLocation().getLatitude(),
                            locationResult.getLastLocation().getLongitude());
                    if (isFirstLocationLoad) {
                        updateUI(current, true);
                        isFirstLocationLoad = false;
                    }
                }
            }
        }, Looper.getMainLooper());
    }

    private void updateUI(LatLng latLng, boolean animate) {
        selectedLatLng = latLng;
        // Map có thể chưa ready khi callback vị trí về sớm (bấm nút hiện tại ngay khi mở màn hình):
        // không crash — lưu selectedLatLng, onMapReady sẽ vẽ lại.
        if (mMap == null) return;
        if (animate) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f));
        } else {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f));
        }

        if (selectionMarker != null) selectionMarker.remove();
        selectionMarker = mMap.addMarker(new MarkerOptions()
                .position(latLng)
                .title(getString(R.string.marker_your_location)));

        tvCoordinates.setText(String.format(Locale.getDefault(), "%.5f, %.5f", latLng.latitude, latLng.longitude));
        fetchAddress(latLng);
    }

    private void fetchAddress(LatLng latLng) {
        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    String fullAddr = addresses.get(0).getAddressLine(0);
                    runOnUiThread(() -> tvAddress.setText(fullAddr));
                }
            } catch (Exception e) {
                runOnUiThread(() -> tvAddress.setText(R.string.share_getting_address));
            }
        }).start();
    }

    private void shareLocation(LatLng latLng, String address) {
        String googleMapsLink = "https://www.google.com/maps/search/?api=1&query=" + latLng.latitude + "," + latLng.longitude;
        String shareBody = getString(R.string.share_my_location_prefix) + "\n" + address
                + "\n\n" + getString(R.string.share_view_on_map) + "\n" + googleMapsLink;

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, shareBody);
        startActivity(Intent.createChooser(intent, "Chia sẻ qua..."));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkPermissionAndEnableGPS();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS && resultCode == RESULT_OK) {
            // Khi bật GPS xong -> Nhảy tới vị trí ngay
            getCurrentLocation();
        }
    }
}