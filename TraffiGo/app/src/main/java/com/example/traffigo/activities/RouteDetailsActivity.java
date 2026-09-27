package com.example.traffigo.activities;

import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;
import com.example.traffigo.models.TrafficSegment;
import com.example.traffigo.utils.PathFinder;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import java.io.*;
import java.util.*;

public class RouteDetailsActivity extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private LatLng origin, destination;
    private ProgressBar progressBar;
    private TextView tvRouteInfo;
    private List<TrafficSegment> allSegments = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_details);

        progressBar = findViewById(R.id.progressBar);
        tvRouteInfo = findViewById(R.id.tvRouteInfo);

        // Nhận tọa độ gửi từ màn hình Search/Main
        origin = new LatLng(getIntent().getDoubleExtra("origin_lat", 10.7767),
                getIntent().getDoubleExtra("origin_lon", 106.6656));
        destination = new LatLng(getIntent().getDoubleExtra("dest_lat", 10.8398),
                getIntent().getDoubleExtra("dest_lon", 106.6791));

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        findViewById(R.id.btnBackDetail).setOnClickListener(v -> finish());
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.setTrafficEnabled(true);

        // Chạy tiến trình nạp dữ liệu và tính toán
        new RouteTask().execute();
    }

    private class RouteTask extends AsyncTask<Void, Void, List<LatLng>> {
        @Override
        protected void onPreExecute() {
            progressBar.setVisibility(View.VISIBLE);
            tvRouteInfo.setText(R.string.route_loading_data);
        }

        @Override
        protected List<LatLng> doInBackground(Void... voids) {
            // Cả 2 file CSV đều dùng dấu phẩy; cột full_geometry của geometry.csv nằm trong
            // ngoặc kép và chứa cả phẩy lẫn chấm phẩy nên phải split tách biệt theo ngoặc kép.
            Map<String, String> geoCache = new HashMap<>();
            Set<String> seenSegmentIds = new HashSet<>();
            allSegments.clear();

            try {
                // 1. Đọc geometry.csv: street_name(0), long_snode(1), lat_snode(2), long_enode(3),
                //    lat_enode(4), full_geometry(5) — nối với traffic_data.csv qua tên đường.
                BufferedReader brGeo = new BufferedReader(new InputStreamReader(getAssets().open("geometry.csv")));
                String line; brGeo.readLine(); // Skip header
                while ((line = brGeo.readLine()) != null) {
                    String[] g = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                    if (g.length < 6) continue;
                    geoCache.put(g[0], g[5].replace("\"", ""));
                }
                brGeo.close();

                // 2. Đọc traffic_data.csv và kết hợp: segmentId(0), name_vn(1), lat_start(2),
                //    lon_start(3), lat_end(4), lon_end(5), ..., currentSpeed(24), congestionIndex(28).
                BufferedReader brTrf = new BufferedReader(new InputStreamReader(getAssets().open("traffic_data.csv")));
                brTrf.readLine(); // Skip header
                while ((line = brTrf.readLine()) != null) {
                    String[] p = line.split(",");
                    if (p.length < 29) continue;
                    if (!seenSegmentIds.add(p[0])) continue; // bỏ snapshot trùng segmentId
                    String geometry = geoCache.containsKey(p[1])
                            ? geoCache.get(p[1])
                            : p[2] + "," + p[3] + ";" + p[4] + "," + p[5]; // không khớp tên -> đường thẳng
                    allSegments.add(new TrafficSegment(p[0],
                            Double.parseDouble(p[2]), Double.parseDouble(p[3]), // start lat/lon
                            Double.parseDouble(p[4]), Double.parseDouble(p[5]), // end lat/lon
                            Double.parseDouble(p[24]), // currentSpeed
                            Double.parseDouble(p[28]), // congestionIndex
                            geometry));
                }
                brTrf.close();

                // 3. Thuật toán Dijkstra
                PathFinder finder = new PathFinder();
                finder.buildGraph(allSegments);
                String sNode = finder.findNearestNode(origin, allSegments);
                String eNode = finder.findNearestNode(destination, allSegments);

                return finder.findFastestPath(sNode, eNode);

            } catch (Exception e) {
                Log.e("TraffiGo", "Error: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<LatLng> path) {
            progressBar.setVisibility(View.GONE);
            if (path != null && !path.isEmpty()) {
                drawResult(path);
                tvRouteInfo.setText(R.string.route_set_success);
            } else {
                tvRouteInfo.setText(R.string.route_error_no_path);
                Toast.makeText(RouteDetailsActivity.this, getString(R.string.toast_check_csv), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void drawResult(List<LatLng> path) {
        // Marker đầu cuối
        mMap.addMarker(new MarkerOptions().position(path.get(0)).title(getString(R.string.marker_start))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        mMap.addMarker(new MarkerOptions().position(path.get(path.size()-1)).title(getString(R.string.marker_destination)));

        // Vẽ đường đi uốn lượn
        mMap.addPolyline(new PolylineOptions().addAll(path).width(14f)
                .color(Color.parseColor("#5C4FE0")).startCap(new RoundCap()).endCap(new RoundCap()));

        // Zoom Camera
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : path) b.include(p);
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 200));
    }
}