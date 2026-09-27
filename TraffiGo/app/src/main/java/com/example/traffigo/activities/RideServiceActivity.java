package com.example.traffigo.activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;

public class RideServiceActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String PKG_GRAB = "com.grabtaxi.passenger";
    private static final String PKG_XANHSM = "com.gsm.customer";
    private static final String PKG_BE = "xyz.be.customer";

    private double originLat, originLng, destLat, destLng;
    private String originAddress, destAddress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_service);

        originLat = getIntent().getDoubleExtra("origin_lat", 0);
        originLng = getIntent().getDoubleExtra("origin_lng", 0);
        destLat = getIntent().getDoubleExtra("dest_lat", 0);
        destLng = getIntent().getDoubleExtra("dest_lng", 0);
        originAddress = getIntent().getStringExtra("origin_address");
        destAddress = getIntent().getStringExtra("dest_address");
        if (originAddress == null) originAddress = getString(R.string.ride_default_origin);
        if (destAddress == null) destAddress = getString(R.string.ride_default_dest);

        View header = findViewById(R.id.layoutHeader);
        TextView tvTitle = header.findViewById(R.id.tvPageTitle);
        ImageButton btnBack = header.findViewById(R.id.btnBack);
        if (tvTitle != null) tvTitle.setText(R.string.ride_title);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        TextView tvDest = findViewById(R.id.tvRideDestination);
        tvDest.setText(getString(R.string.ride_destination_prefix, destAddress));

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapRide);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        findViewById(R.id.cardGrab).setOnClickListener(v -> openGrab());
        findViewById(R.id.cardXanhSm).setOnClickListener(v -> openRideApp(PKG_XANHSM, null));
        findViewById(R.id.cardBe).setOnClickListener(v -> openRideApp(PKG_BE, null));
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);

        LatLng origin = new LatLng(originLat, originLng);
        LatLng dest = new LatLng(destLat, destLng);

        map.addMarker(new MarkerOptions().position(origin).title(getString(R.string.ride_default_origin))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        map.addMarker(new MarkerOptions().position(dest).title(getString(R.string.ride_default_dest))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE)));

        LatLngBounds bounds = new LatLngBounds.Builder().include(origin).include(dest).build();
        // Padding lớn ở dưới để marker không bị card che
        map.setPadding(0, 220, 0, 520);
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120));
    }

    private void openGrab() {
        // Deeplink chính thức của Grab: điền sẵn điểm đón + điểm đến
        String url = "grab://open?screenType=BOOKING"
                + "&pickUpLatitude=" + originLat
                + "&pickUpLongitude=" + originLng
                + "&pickUpAddress=" + Uri.encode(originAddress)
                + "&dropOffLatitude=" + destLat
                + "&dropOffLongitude=" + destLng
                + "&dropOffAddress=" + Uri.encode(destAddress);
        openRideApp(PKG_GRAB, url);
    }

    /**
     * Thử mở app theo thứ tự: deeplink (nếu có) → mở app bằng package → CH Play.
     */
    private void openRideApp(String packageName, @Nullable String deeplink) {
        PackageManager pm = getPackageManager();

        if (deeplink != null) {
            Intent dl = new Intent(Intent.ACTION_VIEW, Uri.parse(deeplink));
            dl.setPackage(packageName);
            if (dl.resolveActivity(pm) != null) {
                startActivity(dl);
                return;
            }
        }

        Intent launch = pm.getLaunchIntentForPackage(packageName);
        if (launch != null) {
            startActivity(launch);
            return;
        }

        // Chưa cài → mở CH Play
        Toast.makeText(this, getString(R.string.toast_app_not_installed), Toast.LENGTH_SHORT).show();
        openPlayStore(packageName);
    }

    private void openPlayStore(String packageName) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
        }
    }
}
