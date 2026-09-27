package com.example.traffigo.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.traffigo.R;
import com.example.traffigo.models.TrafficSegment;
import com.example.traffigo.models.TrafficIncident;
import com.example.traffigo.utils.ClusterColorAssigner;
import com.example.traffigo.utils.MlServerClient;
import com.example.traffigo.utils.TomTomTrafficClient;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
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
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;
import com.google.android.gms.tasks.Task;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.os.Handler;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrafficMapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int REQUEST_CHECK_SETTINGS = 321;

    private static final double GRID_CELL = 0.008; // ~900m per cell

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    // volatile: cấu trúc này được preload 1 lần trên thread nền rồi các thread executor khác đọc
    // (render/tap/fetch) — không có happens-before với camera callbacks chạy trên pool khác.
    private volatile List<TrafficSegment> allSegments = new ArrayList<>();
    // Delta rendering: segment index → live Polyline.
    // ConcurrentHashMap: đọc/ghi từ UI thread (khối runOnUiThread) lẫn executorService (khối delta),
    // dùng HashMap thường sẽ race → ConcurrentModificationException hoặc treo do hỏng cấu trúc bảng băm.
    private final Map<Integer, Polyline> livePolylines = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    // Spatial grid: packed long key → list of segment indices
    private volatile Map<Long, List<Integer>> spatialGrid = new HashMap<>();
    // Debounce
    private final Handler renderHandler = new Handler();
    private final Runnable renderRunnable = this::renderVisibleSegments;

    // Cache dữ liệu live TomTom theo segment index, tránh gọi lại API khi pan nhẹ
    private static final long LIVE_CACHE_TTL_MS = 3 * 60 * 1000L; // 3 phút
    // Truy cập từ nhiều thread của executorService/liveTrafficExecutor → ConcurrentHashMap
    private final Map<Integer, Long> liveFetchedAt = new ConcurrentHashMap<>();
    private final ExecutorService liveTrafficExecutor = Executors.newFixedThreadPool(3);
    // Gán 1 trong 6 cụm KMeans (xem DATN/clustering.py) cho traffic live -> 6 màu
    private final ClusterColorAssigner clusterColorAssigner = new ClusterColorAssigner();
    // lengthKm/curvatureIndex tĩnh theo hình học, tính 1 lần lúc load geometry
    private volatile Map<Integer, double[]> segmentGeomFeatures = new HashMap<>();
    // Màu (6 mức, theo cụm KMeans) đã tính cho mỗi segment; mặc định xanh cho tới khi có dữ liệu live.
    // Ghi trên liveTrafficExecutor, đọc trên executorService → ConcurrentHashMap
    private final Map<Integer, Integer> segmentColor = new ConcurrentHashMap<>();

    private volatile boolean isDataLoaded = false;
    // false sau onDestroy: chặn callback nền (TomTom còn đang chờ HTTP) đụng map/view đã hủy
    private volatile boolean isActive = true;
    // Dưới zoom này chỉ render khung đường, KHÔNG gọi TomTom cho từng segment — zoom out nhìn cả
    // thành phố có hàng nghìn segment, queue fetch sẽ đốt sạch quota ngày và màu cập nhật trễ hàng phút.
    private static final float LIVE_FETCH_MIN_ZOOM = 14f;
    private static final int LIVE_FETCH_MAX_PER_RENDER = 150;
    private float currentZoom = 15f;
    private boolean isHeatmapMode = false;
    private HeatmapTileProvider heatmapProvider;
    private TileOverlay heatmapOverlay;

    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    // ===== Báo cáo sự cố giao thông (cộng đồng) =====
    private static final long INCIDENT_EXPIRY_MS = 2 * 60 * 60 * 1000L; // ẩn báo cáo cũ hơn 2 giờ
    private DatabaseReference incidentsRef;
    private ValueEventListener incidentsListener;
    private final Map<Marker, TrafficIncident> incidentMarkers = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_traffic_map);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        // Đăng ký quyền
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (Boolean.TRUE.equals(result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false))) {
                        enableMyLocationLayer();
                        checkAndEnableGPS();
                    }
                }
        );

        initSearch();
        preloadTrafficData();
        executorService.execute(() -> clusterColorAssigner.loadFromAssets(this));
        setupServerStatusChip();

        // Nút Vị trí hiện tại (Nếu trong XML Huy có id này, nếu không tui dựa theo logic các màn cũ)
        View btnLoc = findViewById(R.id.btnCurrentLoc);
        if (btnLoc != null) {
            btnLoc.setOnClickListener(v -> handleCurrentLocationClick());
        }
        View btnHeatmap = findViewById(R.id.btnHeatmap);
        if (btnHeatmap != null) {
            btnHeatmap.setOnClickListener(v -> toggleHeatmap());
        }
        View btnReport = findViewById(R.id.btnReportIncident);
        if (btnReport != null) {
            btnReport.setOnClickListener(v -> showReportDialog());
        }
        TextView tvTitle = findViewById(R.id.tvPageTitle);
        if (tvTitle != null) {
            tvTitle.setText(R.string.map_title);
        }
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });
    }

    private void initSearch() {
        EditText edtSearch = findViewById(R.id.edtSearch);
        edtSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchLocation(edtSearch.getText().toString());
                return true;
            }
            return false;
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(false);

        SharedPreferences camPrefs = getSharedPreferences("MapCameraPrefs", MODE_PRIVATE);
        double lat = Double.longBitsToDouble(camPrefs.getLong("last_lat", Double.doubleToLongBits(10.8398)));
        double lng = Double.longBitsToDouble(camPrefs.getLong("last_lng", Double.doubleToLongBits(106.6791)));
        float zoom = camPrefs.getFloat("last_zoom", 15f);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom));

        // Không dùng setOnPolylineClickListener: với hàng chục nghìn polyline vẽ dày đặc,
        // vùng dung sai bắt chạm của Maps SDK dễ trúng nhầm đường kề sát hoặc tại giao lộ.
        // Tự bắt điểm bấm thật rồi tìm segment gần nhất bằng khoảng cách điểm-đến-đoạn-thẳng.
        mMap.setOnMapClickListener(this::handleMapTap);

        mMap.setOnCameraMoveListener(() -> {
            float newZoom = mMap.getCameraPosition().zoom;
            if (Math.abs(newZoom - currentZoom) > 0.5) {
                currentZoom = newZoom;
            }
            scheduleRender(150);
        });

        mMap.setOnCameraIdleListener(() -> scheduleRender(0));

        // Kiểm tra quyền khi vào map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
        }

        // Sự cố cộng đồng: bấm vào marker sự cố để xác nhận/gỡ; đồng thời nạp danh sách sự cố
        mMap.setOnMarkerClickListener(marker -> {
            TrafficIncident inc = incidentMarkers.get(marker);
            if (inc != null) {
                showIncidentDialog(inc);
                return true;
            }
            return false;
        });
        // Segment vẽ clickable(true): tap trúng đường bị polyline nuốt nên KHÔNG bao giờ tới
        // onMapClickListener nếu không đăng ký listener này. Tag polyline đã chứa segment.
        mMap.setOnPolylineClickListener(polyline -> {
            if (!isActive) return;
            Object tag = polyline.getTag();
            if (tag instanceof TrafficSegment) {
                TrafficSegment segment = (TrafficSegment) tag;
                String status = getString(R.string.status_free_flow);
                if (segment.congestionIndex < 0.7) status = getString(R.string.status_heavy);
                else if (segment.congestionIndex < 0.85) status = getString(R.string.status_moderate);
                showTrafficBottomSheet(segment, status);
            }
        });
        listenForIncidents();
    }

    private void handleCurrentLocationClick() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
        } else {
            checkAndEnableGPS();
        }
    }

    private void checkAndEnableGPS() {
        LocationRequest locationRequest = LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest);
        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(this, response -> getCurrentLocation());
        task.addOnFailureListener(this, e -> {
            if (e instanceof ResolvableApiException) {
                try {
                    ResolvableApiException resolvable = (ResolvableApiException) e;
                    resolvable.startResolutionForResult(TrafficMapActivity.this, REQUEST_CHECK_SETTINGS);
                } catch (IntentSender.SendIntentException sendEx) { }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS && resultCode == RESULT_OK) {
            getCurrentLocation();
        }
    }

    @SuppressLint("MissingPermission")
    private void getCurrentLocation() {
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), 17f));
            } else {
                requestNewLocationUpdate();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestNewLocationUpdate() {
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setNumUpdates(1);
        fusedLocationClient.requestLocationUpdates(locationRequest, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLastLocation() != null) {
                    LatLng current = new LatLng(locationResult.getLastLocation().getLatitude(), locationResult.getLastLocation().getLongitude());
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, 17f));
                }
            }
        }, Looper.getMainLooper());
    }

    @SuppressLint("MissingPermission")
    private void enableMyLocationLayer() {
        if (mMap != null) mMap.setMyLocationEnabled(true);
    }

    private void searchLocation(String locationName) {
        // Geocoder.getFromLocationName chặn (IPC + geocode phía server) — chạy trên main thread
        // sẽ đơ UI/ANR khi mạng chậm -> đẩy xuống executor, kết quả quay lại qua runOnUiThread.
        executorService.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> list = geocoder.getFromLocationName(locationName, 1);
                if (isActive && !list.isEmpty()) {
                    Address addr = list.get(0);
                    LatLng latLng = new LatLng(addr.getLatitude(), addr.getLongitude());
                    runOnUiThread(() -> {
                        if (!isActive || mMap == null) return;
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f));
                        mMap.addMarker(new MarkerOptions().position(latLng).title(locationName));
                    });
                } else if (isActive) {
                    runOnUiThread(() -> {
                        if (isActive) {
                            Toast.makeText(this, getString(R.string.toast_place_not_found), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void scheduleRender(int delayMs) {
        renderHandler.removeCallbacks(renderRunnable);
        if (delayMs == 0) renderHandler.post(renderRunnable);
        else renderHandler.postDelayed(renderRunnable, delayMs);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /** Bấm vào map -> tìm segment gần điểm bấm nhất (trong bán kính hợp lý) rồi hiện bottom sheet. */
    private void handleMapTap(LatLng tapPoint) {
        if (!isDataLoaded) return;

        executorService.execute(() -> {
            Set<Integer> candidates = new HashSet<>();
            // Quét ô hiện tại + 8 ô lân cận để không bỏ sót segment gần biên ô
            for (double la = tapPoint.latitude - GRID_CELL; la <= tapPoint.latitude + GRID_CELL; la += GRID_CELL) {
                for (double lo = tapPoint.longitude - GRID_CELL; lo <= tapPoint.longitude + GRID_CELL; lo += GRID_CELL) {
                    List<Integer> cell = spatialGrid.get(cellKey(la, lo));
                    if (cell != null) candidates.addAll(cell);
                }
            }

            int bestIdx = -1;
            double bestDistKm = Double.MAX_VALUE;
            for (int idx : candidates) {
                TrafficSegment seg = allSegments.get(idx);
                double dist = distanceToSegmentKm(tapPoint, seg);
                if (dist < bestDistKm) {
                    bestDistKm = dist;
                    bestIdx = idx;
                }
            }

            // Ngưỡng bắt chạm ~25m - vượt quá thì coi như không bấm trúng đường nào
            if (bestIdx == -1 || bestDistKm > 0.025) return;

            TrafficSegment segment = allSegments.get(bestIdx);
            runOnUiThread(() -> {
                String status = getString(R.string.status_free_flow);
                if (segment.congestionIndex < 0.7) status = getString(R.string.status_heavy);
                else if (segment.congestionIndex < 0.85) status = getString(R.string.status_moderate);
                showTrafficBottomSheet(segment, status);
            });
        });
    }

    /** Khoảng cách (km) từ 1 điểm đến polyline của segment - lấy min trên từng đoạn thẳng con. */
    private static double distanceToSegmentKm(LatLng point, TrafficSegment seg) {
        List<LatLng> geom = seg.getGeometryList();
        if (geom.size() < 2) {
            return haversineKm(point.latitude, point.longitude, seg.lat1, seg.lon1);
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < geom.size() - 1; i++) {
            double d = distanceToSegmentKm(point, geom.get(i), geom.get(i + 1));
            if (d < best) best = d;
        }
        return best;
    }

    /** Khoảng cách (km, xấp xỉ phẳng) từ điểm P đến đoạn thẳng A-B. */
    private static double distanceToSegmentKm(LatLng p, LatLng a, LatLng b) {
        // Xấp xỉ phẳng đủ chính xác ở phạm vi vài chục mét (không cần haversine cho từng bước)
        double ax = a.longitude, ay = a.latitude;
        double bx = b.longitude, by = b.latitude;
        double px = p.longitude, py = p.latitude;

        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        double t = lenSq > 0 ? ((px - ax) * dx + (py - ay) * dy) / lenSq : 0;
        t = Math.max(0, Math.min(1, t));

        double projX = ax + t * dx;
        double projY = ay + t * dy;
        return haversineKm(py, px, projY, projX);
    }

    private long cellKey(double lat, double lon) {
        long la = (long) Math.floor(lat / GRID_CELL);
        long lo = (long) Math.floor(lon / GRID_CELL);
        return la * 1_000_000L + lo;
    }

    private void indexSegmentIntoGrid(Map<Long, List<Integer>> grid, int idx, TrafficSegment seg) {
        double minLat = Math.min(seg.lat1, seg.lat2);
        double maxLat = Math.max(seg.lat1, seg.lat2);
        double minLon = Math.min(seg.lon1, seg.lon2);
        double maxLon = Math.max(seg.lon1, seg.lon2);
        for (double la = Math.floor(minLat / GRID_CELL) * GRID_CELL; la <= maxLat + GRID_CELL; la += GRID_CELL) {
            for (double lo = Math.floor(minLon / GRID_CELL) * GRID_CELL; lo <= maxLon + GRID_CELL; lo += GRID_CELL) {
                grid.computeIfAbsent(cellKey(la, lo), k -> new ArrayList<>()).add(idx);
            }
        }
    }

    private void preloadTrafficData() {
        executorService.execute(() -> {
            try {
                // Build xong vào cấu trúc cục bộ rồi mới công bố qua các field volatile — nếu ghi
                // trực tiếp vào field trong lúc load, thread khác có thể đọc thấy trạng thái dở dang.
                List<TrafficSegment> segs = new ArrayList<>();
                Map<Integer, double[]> geomFeatures = new HashMap<>();
                Map<Long, List<Integer>> grid = new HashMap<>();
                // Chỉ đọc hình học đường (tĩnh, không đổi). Tốc độ/độ kẹt xe sẽ lấy live
                // từ TomTom theo khu vực đang xem, xem fetchLiveTrafficFor().
                BufferedReader gr = new BufferedReader(new InputStreamReader(getAssets().open("geometry.csv")));
                gr.readLine();
                String gLine;
                while ((gLine = gr.readLine()) != null) {
                    String[] g = gLine.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                    if (g.length < 6) continue;
                    String name = g[0];
                    String fullGeom = g[5].replace("\"", "");
                    double sLat = Double.parseDouble(g[2]), sLon = Double.parseDouble(g[1]);
                    double eLat = Double.parseDouble(g[4]), eLon = Double.parseDouble(g[3]);
                    segs.add(new TrafficSegment(name, sLat, sLon, eLat, eLon, 35.0, 1.0, fullGeom));

                    int idx = segs.size() - 1;
                    double lengthKm = haversineKm(sLat, sLon, eLat, eLon);
                    double curvatureIndex = Math.abs(sLat - eLat) + Math.abs(sLon - eLon);
                    geomFeatures.put(idx, new double[]{lengthKm, curvatureIndex});
                }
                gr.close();

                for (int i = 0; i < segs.size(); i++) {
                    indexSegmentIntoGrid(grid, i, segs.get(i));
                }

                allSegments = segs;
                segmentGeomFeatures = geomFeatures;
                spatialGrid = grid;
                isDataLoaded = true;
                runOnUiThread(this::renderVisibleSegments);
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void toggleHeatmap() {
        isHeatmapMode = !isHeatmapMode;
        if (isHeatmapMode) {
            for (Polyline p : livePolylines.values()) p.remove();
            livePolylines.clear();
            showHeatmap();
        } else {
            if (heatmapOverlay != null) { heatmapOverlay.remove(); heatmapOverlay = null; }
            Toast.makeText(this, getString(R.string.toast_heatmap_off), Toast.LENGTH_SHORT).show();
            renderVisibleSegments();
        }
    }

    private void showHeatmap() {
        if (!isDataLoaded) {
            Toast.makeText(this, getString(R.string.toast_data_not_ready), Toast.LENGTH_SHORT).show();
            isHeatmapMode = false;
            return;
        }
        List<WeightedLatLng> data = new ArrayList<>();
        for (TrafficSegment seg : allSegments) {
            double midLat = (seg.lat1 + seg.lat2) / 2;
            double midLon = (seg.lon1 + seg.lon2) / 2;
            double weight = Math.max(0.01, 1.0 - seg.congestionIndex);
            data.add(new WeightedLatLng(new LatLng(midLat, midLon), weight));
        }
        if (heatmapOverlay != null) heatmapOverlay.remove();
        heatmapProvider = new HeatmapTileProvider.Builder()
                .weightedData(data)
                .radius(35)
                .build();
        heatmapOverlay = mMap.addTileOverlay(new TileOverlayOptions().tileProvider(heatmapProvider));
        Toast.makeText(this, getString(R.string.toast_heatmap_on), Toast.LENGTH_SHORT).show();
    }

    private void renderVisibleSegments() {
        if (!isDataLoaded || mMap == null || executorService.isShutdown() || isHeatmapMode) return;
        final LatLngBounds bounds = mMap.getProjection().getVisibleRegion().latLngBounds;
        final float lineWidth = Math.max(6f, (currentZoom - 10f) * 2.5f);
        // Đọc camera trên main thread — getCameraPosition/getProjection cấm gọi từ thread nền
        // (Maps SDK throw "Not on the main thread" và crash app).
        final LatLng cameraCenter = mMap.getCameraPosition().target;

        executorService.execute(() -> {
            // Spatial grid query: only check segments in cells that overlap the viewport
            Set<Integer> visibleIndices = new HashSet<>();
            double minLat = bounds.southwest.latitude;
            double maxLat = bounds.northeast.latitude;
            double minLon = bounds.southwest.longitude;
            double maxLon = bounds.northeast.longitude;
            for (double la = Math.floor(minLat / GRID_CELL) * GRID_CELL; la <= maxLat; la += GRID_CELL) {
                for (double lo = Math.floor(minLon / GRID_CELL) * GRID_CELL; lo <= maxLon; lo += GRID_CELL) {
                    List<Integer> cell = spatialGrid.get(cellKey(la, lo));
                    if (cell != null) visibleIndices.addAll(cell);
                }
            }

            fetchLiveTrafficFor(visibleIndices, cameraCenter);

            // Determine adds/removes via delta
            final Set<Integer> toAdd = new HashSet<>();
            final Set<Integer> toRemove = new HashSet<>();
            for (int idx : visibleIndices) {
                if (!livePolylines.containsKey(idx)) toAdd.add(idx);
            }
            for (int idx : livePolylines.keySet()) {
                if (!visibleIndices.contains(idx)) toRemove.add(idx);
            }

            // Build PolylineOptions only for new segments (off-thread)
            final Map<Integer, PolylineOptions> addOptions = new HashMap<>();
            for (int idx : toAdd) {
                TrafficSegment seg = allSegments.get(idx);
                Integer color = segmentColor.get(idx);
                if (color == null) color = Color.argb(220, 56, 168, 82); // mặc định xanh khi chưa có dữ liệu live
                addOptions.put(idx, new PolylineOptions()
                        .color(color)
                        .width(lineWidth)
                        .clickable(true)
                        .startCap(new RoundCap())
                        .endCap(new RoundCap())
                        .zIndex(1f)
                        .addAll(seg.getGeometryList()));
            }

            runOnUiThread(() -> {
                if (mMap == null) return;
                for (int idx : toRemove) {
                    Polyline p = livePolylines.remove(idx);
                    if (p != null) p.remove();
                }
                for (Map.Entry<Integer, PolylineOptions> e : addOptions.entrySet()) {
                    Polyline p = mMap.addPolyline(e.getValue());
                    p.setTag(allSegments.get(e.getKey()));
                    livePolylines.put(e.getKey(), p);
                }
            });
        });
    }

    /**
     * Gọi TomTom cho các segment đang hiển thị mà chưa fetch trong LIVE_CACHE_TTL_MS gần nhất.
     * Chạy trên executor riêng để không tranh chấp với luồng render.
     * Giới hạn: dưới LIVE_FETCH_MIN_ZOOM thì bỏ hẳn (khỏi đốt quota khi nhìn cả thành phố),
     * trên đó thì mỗi lần render tối đa LIVE_FETCH_MAX_PER_RENDER segment gần tâm màn hình nhất.
     */
    private void fetchLiveTrafficFor(Set<Integer> visibleIndices, LatLng cameraCenter) {
        if (mMap == null || currentZoom < LIVE_FETCH_MIN_ZOOM) return;

        List<Integer> stale = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int idx : visibleIndices) {
            Long fetchedAt = liveFetchedAt.get(idx);
            if (fetchedAt != null && now - fetchedAt < LIVE_CACHE_TTL_MS) continue;
            stale.add(idx);
        }
        if (stale.size() > LIVE_FETCH_MAX_PER_RENDER) {
            stale.sort((a, b) -> {
                TrafficSegment sa = allSegments.get(a), sb = allSegments.get(b);
                double da = Math.abs((sa.lat1 + sa.lat2) / 2 - cameraCenter.latitude)
                        + Math.abs((sa.lon1 + sa.lon2) / 2 - cameraCenter.longitude);
                double db = Math.abs((sb.lat1 + sb.lat2) / 2 - cameraCenter.latitude)
                        + Math.abs((sb.lon1 + sb.lon2) / 2 - cameraCenter.longitude);
                return Double.compare(da, db);
            });
            stale = stale.subList(0, LIVE_FETCH_MAX_PER_RENDER);
        }

        for (int idx : stale) {
            liveFetchedAt.put(idx, now);

            liveTrafficExecutor.execute(() -> {
                if (!isActive) return;
                TrafficSegment seg = allSegments.get(idx);
                TomTomTrafficClient.FlowResult result = TomTomTrafficClient.fetchFlowAveraged(
                        seg.lat1, seg.lon1, seg.lat2, seg.lon2);
                if (!isActive) return;
                if (result == null) {
                    // F3: TomTom không có dữ liệu cho đoạn này -> tô xám "thiếu dữ liệu"
                    // thay vì để mặc định xanh (tránh hiểu nhầm "đường thoáng").
                    segmentColor.put(idx, ClusterColorAssigner.NO_DATA_COLOR);
                    runOnUiThread(() -> {
                        if (!isActive) return;
                        Polyline p = livePolylines.get(idx);
                        if (p != null) p.setColor(ClusterColorAssigner.NO_DATA_COLOR);
                    });
                    return;
                }

                seg.speed = result.currentSpeed;
                seg.congestionIndex = result.congestionIndex;

                int color = clusterColorAssigner.isLoaded()
                        ? clusterColorAssigner.colorFor(buildClusterFeatures(idx, result))
                        : Color.argb(220, 56, 168, 82);
                segmentColor.put(idx, color);

                runOnUiThread(() -> {
                    if (!isActive) return;
                    Polyline p = livePolylines.get(idx);
                    if (p == null) return;
                    p.setColor(color);
                });
            });
        }
    }

    /** Tính 9 đặc trưng dùng để gán cụm KMeans, theo đúng công thức trong DATA/src/h.py. */
    private ClusterColorAssigner.Features buildClusterFeatures(int idx, TomTomTrafficClient.FlowResult result) {
        double[] geom = segmentGeomFeatures.get(idx);
        double lengthKm = geom != null ? geom[0] : 0.0;
        double curvatureIndex = geom != null ? geom[1] : 0.0;

        double currentSpeed = result.currentSpeed;
        double freeFlowSpeed = result.freeFlowSpeed;
        double congestionIndex = result.congestionIndex;

        ClusterColorAssigner.Features f = new ClusterColorAssigner.Features();
        f.freeFlowSpeed = freeFlowSpeed;
        f.congestionIndex = congestionIndex;
        f.lengthKm = lengthKm;
        f.curvatureIndex = curvatureIndex;
        // speedLimit không có sẵn từ Flow Segment Data -> xấp xỉ bằng freeFlowSpeed (giống fallback trong h.py)
        f.speedLimitRatio = freeFlowSpeed > 0 ? currentSpeed / freeFlowSpeed : 1.0;
        f.occupancy = freeFlowSpeed > 0 ? 100 * (1 - currentSpeed / freeFlowSpeed) : 0.0;
        f.relativeCongestionIndex = freeFlowSpeed > 0 ? (freeFlowSpeed - currentSpeed) / freeFlowSpeed : 0.0;
        f.crossTime = currentSpeed > 0 ? lengthKm / (currentSpeed / 3.6) : 0.0;
        // trafficVolume: estimate_traffic_volume(h.py) với laneCount mặc định = 1 (không có dữ liệu làn đường)
        double utilization = Math.max(0.05, Math.min(1.0, 1.2 - congestionIndex));
        f.trafficVolume = 1800 * 1 * utilization;
        return f;
    }

    private void showTrafficBottomSheet(TrafficSegment segment, String status) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_traffic, null);

        ((TextView) view.findViewById(R.id.tvStreetName)).setText(segment.name);
        ((TextView) view.findViewById(R.id.tvSpeed)).setText(String.format("%.0f km/h", segment.speed));

        TextView tvStatus = view.findViewById(R.id.tvStatus);
        tvStatus.setText(status);

        int textColor, bgColor;
        if (status.equals(getString(R.string.status_heavy))) {
            textColor = Color.parseColor("#D32F2F");
            bgColor   = Color.parseColor("#FFEBEE");
        } else if (status.equals(getString(R.string.status_moderate))) {
            textColor = Color.parseColor("#E65100");
            bgColor   = Color.parseColor("#FFF3E0");
        } else {
            textColor = Color.parseColor("#2E7D32");
            bgColor   = Color.parseColor("#E8F5E9");
        }
        tvStatus.setTextColor(textColor);
        com.google.android.material.card.MaterialCardView cardStatus = view.findViewById(R.id.cardStatus);
        if (cardStatus != null) cardStatus.setCardBackgroundColor(bgColor);

        requestMlForecastForSegment(view, segment);

        dialog.setContentView(view);
        dialog.show();
    }

    /** Kiểm tra trạng thái server ML ngay khi mở màn hình và cập nhật chip hiển thị,
     *  để người dùng biết hệ thống ML đang online hay offline thay vì im lặng. */
    private void setupServerStatusChip() {
        TextView chip = findViewById(R.id.tvServerStatus);
        if (chip == null) return;
        chip.setText(R.string.ml_status_checking);
        chip.setTextColor(Color.parseColor("#888888"));
        // Giữ lâu chip để nhập địa chỉ server mới (hữu ích khi test trên máy thật qua WiFi)
        chip.setOnLongClickListener(v -> {
            showServerUrlDialog();
            return true;
        });
        checkServerHealth();
    }

    private void checkServerHealth() {
        checkServerHealthAttempt(0);
    }

    /**
     * Kiểm tra server với cơ chế retry dài ~60s: Render free ngủ sau 15 phút và cần
     * 30-50s để đánh thức (cold start) — vượt xa timeout 4s của một lời gọi. Thử lại
     * ở 0s/15s/35s/60s là đủ phủ cửa sổ đánh thức; local server thì lần đầu đã xanh.
     */
    private void checkServerHealthAttempt(final int attempt) {
        TextView chip = findViewById(R.id.tvServerStatus);
        if (chip == null || !isActive) return;
        if (attempt == 0) {
            chip.setText(R.string.ml_status_checking);
            chip.setTextColor(Color.parseColor("#888888"));
        }
        MlServerClient.get(this, "/health", new MlServerClient.MlCallback() {
            @Override public void onSuccess(JSONObject response) {
                if (!isActive) return;
                updateServerStatusChip(true);
            }

            @Override public void onError(String message) {
                if (!isActive) return;
                if (attempt < 3) {
                    long delayMs = attempt == 0 ? 15000 : 20000;
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> checkServerHealthAttempt(attempt + 1), delayMs);
                } else {
                    updateServerStatusChip(false);
                }
            }
        });
    }

    /** Dialog nhập địa chỉ server ML (vd. http://192.168.1.10:8000 khi test qua WiFi). */
    private void showServerUrlDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(MlServerClient.getServerUrl(this));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ml_server_url_title)
                .setMessage(R.string.ml_server_url_hint)
                .setView(input)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        MlServerClient.setServerUrl(this, url);
                        Toast.makeText(this, R.string.ml_server_url_saved, Toast.LENGTH_SHORT).show();
                        checkServerHealth();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /**
     * Gọi TraffiGo ML Server (/api/ml/predict) để dự báo tốc độ + mức kẹt + rủi ro sự cố
     * cho đoạn đang xem, kết quả hiện vào card "DỰ BÁO ML" của bottom sheet. Server offline
     * thì card ẩn đi — mọi thứ vẫn hoạt động bình thường không cần server.
     */
    private void requestMlForecastForSegment(View sheetView, TrafficSegment seg) {
        com.google.android.material.card.MaterialCardView cardMl =
                sheetView.findViewById(R.id.cardMl);
        TextView tvMlSpeed = sheetView.findViewById(R.id.tvMlSpeed);
        TextView tvMlDetail = sheetView.findViewById(R.id.tvMlDetail);

        double freeFlow = seg.congestionIndex > 0.05 ? seg.speed / seg.congestionIndex : 50.0;
        double lengthKm = haversineKm(seg.lat1, seg.lon1, seg.lat2, seg.lon2);
        try {
            JSONObject sample = MlServerClient.sample(
                    seg.speed, freeFlow, lengthKm, seg.speedLimitKmh, seg.highwayType,
                    MlServerClient.currentHour(), MlServerClient.todayDayOfWeek());
            JSONObject body = new JSONObject().put("segment", sample);
            MlServerClient.post(this, "/api/ml/predict", body, new MlServerClient.MlCallback() {
                @Override public void onSuccess(JSONObject response) {
                    if (!isActive) return;
                    updateServerStatusChip(true);
                    JSONObject p = response.optJSONObject("prediction");
                    if (p == null) return;
                    cardMl.setVisibility(View.VISIBLE);
                    String levelKey = p.optString("trafficLevel", "Low");
                    int levelRes = "High".equals(levelKey) ? R.string.ml_level_high
                            : ("Moderate".equals(levelKey) ? R.string.ml_level_moderate : R.string.ml_level_low);
                    tvMlSpeed.setText(getString(R.string.ml_forecast_speed,
                            String.format(Locale.US, "%.0f", p.optDouble("predictedSpeed", 0)),
                            getString(levelRes)));
                    JSONObject inc = p.optJSONObject("incident");
                    String riskKey = inc != null ? inc.optString("risk", "LOW") : "LOW";
                    int riskRes = "HIGH".equals(riskKey) ? R.string.ml_risk_high
                            : ("MEDIUM".equals(riskKey) ? R.string.ml_risk_medium : R.string.ml_risk_low);
                    int cluster = p.optInt("clusterId", -1);
                    tvMlDetail.setText(cluster >= 0
                            ? getString(R.string.ml_forecast_detail, cluster, getString(riskRes))
                            : getString(riskRes));
                }

                @Override public void onError(String message) {
                    if (!isActive) return;
                    updateServerStatusChip(false);
                    tvMlSpeed.setText(R.string.ml_offline);
                    tvMlDetail.setText("");
                }
            });
        } catch (Exception e) {
            Log.e("TraffiGo", "ML forecast error: " + e.getMessage());
        }
    }

    /** Cập nhật chip trạng thái server (xanh = online, xám = offline). */
    private void updateServerStatusChip(boolean online) {
        TextView chip = findViewById(R.id.tvServerStatus);
        if (chip == null) return;
        chip.setText(online ? R.string.ml_status_online : R.string.ml_status_offline);
        chip.setTextColor(Color.parseColor(online ? "#2E7D32" : "#9E9E9E"));
    }

    // ===================== BÁO CÁO SỰ CỐ GIAO THÔNG (CỘNG ĐỒNG) =====================

    /** Chọn loại sự cố (bottom sheet) rồi báo tại TÂM bản đồ (người dùng canh map vào điểm cần báo). */
    private void showReportDialog() {
        if (mMap == null) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, getString(R.string.incident_login_required), Toast.LENGTH_SHORT).show();
            return;
        }
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.layout_bottom_sheet_report_incident, null);
        dialog.setContentView(view);

        view.findViewById(R.id.cardReportJam).setOnClickListener(v -> { dialog.dismiss(); submitIncident("jam"); });
        view.findViewById(R.id.cardReportAccident).setOnClickListener(v -> { dialog.dismiss(); submitIncident("accident"); });
        view.findViewById(R.id.cardReportFlood).setOnClickListener(v -> { dialog.dismiss(); submitIncident("flood"); });
        view.findViewById(R.id.cardReportHazard).setOnClickListener(v -> { dialog.dismiss(); submitIncident("hazard"); });

        if (dialog.getWindow() != null) {
            dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void submitIncident(String type) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || mMap == null) return;
        LatLng at = mMap.getCameraPosition().target;
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("incidents").push();
        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        data.put("lat", at.latitude);
        data.put("lng", at.longitude);
        data.put("timestamp", System.currentTimeMillis());
        data.put("uid", user.getUid());
        ref.setValue(data)
                .addOnSuccessListener(v -> Toast.makeText(this, getString(R.string.incident_reported), Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this,
                        getString(R.string.incident_report_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    // Ẩn sớm (trước khi hết hạn 2h tự nhiên) nếu đủ số người xác nhận "không còn nữa" - cần ít nhất 2
    // downvote VÀ nhiều hơn upvote, để 1 người bấm nhầm/troll không tự ý ẩn được báo cáo hợp lệ.
    private static final int DOWNVOTE_HIDE_THRESHOLD = 2;

    private boolean isDownvotedAway(TrafficIncident inc) {
        int down = inc.downvoteCount();
        return down >= DOWNVOTE_HIDE_THRESHOLD && down > inc.upvoteCount();
    }

    /** Lắng nghe realtime danh sách sự cố, vẽ marker (bỏ qua báo cáo cũ hơn 2 giờ hoặc đã bị downvote đủ ngưỡng). */
    private void listenForIncidents() {
        incidentsRef = FirebaseDatabase.getInstance().getReference("incidents");
        incidentsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (mMap == null) return;
                for (Marker m : incidentMarkers.keySet()) m.remove();
                incidentMarkers.clear();
                long now = System.currentTimeMillis();
                for (DataSnapshot child : snapshot.getChildren()) {
                    TrafficIncident inc = child.getValue(TrafficIncident.class);
                    if (inc == null) continue;
                    inc.id = child.getKey();
                    if (now - inc.timestamp > INCIDENT_EXPIRY_MS) continue;
                    if (isDownvotedAway(inc)) continue;
                    Marker m = mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(inc.lat, inc.lng))
                            .title(incidentLabel(inc.type))
                            .icon(BitmapDescriptorFactory.defaultMarker(incidentHue(inc.type)))
                            .zIndex(2f));
                    if (m != null) incidentMarkers.put(m, inc);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* giữ marker hiện có */ }
        };
        incidentsRef.addValueEventListener(incidentsListener);
    }

    /**
     * Bấm marker sự cố. Báo cáo CỦA CHÍNH MÌNH: "Vẫn còn" (làm mới mốc thời gian) / "Gỡ báo cáo" (xoá
     * hẳn) - như cũ. Báo cáo của NGƯỜI KHÁC: đổi "Gỡ báo cáo" (trước đây ai cũng xoá được ngay report
     * của người khác, không có gì chặn - lỗ hổng dễ bị lạm dụng) thành "Không còn nữa" (downvote, chỉ
     * ẩn marker khi đủ ngưỡng ở isDownvotedAway(), không xoá dữ liệu). Vote lưu theo uid trong
     * incidents/{id}/votes nên 1 người chỉ tính 1 phiếu dù bấm lại nhiều lần (ghi đè, không cộng dồn).
     */
    private void showIncidentDialog(TrafficIncident inc) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        boolean isOwnReport = user != null && user.getUid().equals(inc.uid);

        String message = getString(R.string.incident_reported_ago, timeAgo(inc.timestamp));
        int totalVotes = inc.upvoteCount() + inc.downvoteCount();
        if (totalVotes > 0) {
            message += "\n" + getString(R.string.incident_votes_summary, inc.upvoteCount(), inc.downvoteCount());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(incidentLabel(inc.type))
                .setMessage(message)
                .setPositiveButton(R.string.incident_still_there, (d, w) -> {
                    if (inc.id == null) return;
                    if (user == null) {
                        Toast.makeText(this, getString(R.string.incident_login_required), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    DatabaseReference ref = FirebaseDatabase.getInstance().getReference("incidents").child(inc.id);
                    ref.child("timestamp").setValue(System.currentTimeMillis());
                    if (!isOwnReport) ref.child("votes").child(user.getUid()).setValue("up");
                    Toast.makeText(this, getString(R.string.incident_confirmed), Toast.LENGTH_SHORT).show();
                });

        if (isOwnReport) {
            builder.setNegativeButton(R.string.incident_remove, (d, w) -> {
                FirebaseDatabase.getInstance().getReference("incidents").child(inc.id).removeValue();
                Toast.makeText(this, getString(R.string.incident_removed), Toast.LENGTH_SHORT).show();
            });
        } else {
            builder.setNegativeButton(R.string.incident_downvote, (d, w) -> {
                if (inc.id == null) return;
                if (user == null) {
                    Toast.makeText(this, getString(R.string.incident_login_required), Toast.LENGTH_SHORT).show();
                    return;
                }
                FirebaseDatabase.getInstance().getReference("incidents").child(inc.id)
                        .child("votes").child(user.getUid()).setValue("down");
                Toast.makeText(this, getString(R.string.incident_downvote_recorded), Toast.LENGTH_SHORT).show();
            });
        }

        builder.show();
    }

    private float incidentHue(String type) {
        if (type == null) return BitmapDescriptorFactory.HUE_RED;
        switch (type) {
            case "jam": return BitmapDescriptorFactory.HUE_ORANGE;
            case "flood": return BitmapDescriptorFactory.HUE_AZURE;
            case "police": return BitmapDescriptorFactory.HUE_BLUE;
            case "hazard": return BitmapDescriptorFactory.HUE_YELLOW;
            case "accident":
            default: return BitmapDescriptorFactory.HUE_RED;
        }
    }

    private String incidentLabel(String type) {
        if (type == null) return "";
        switch (type) {
            case "jam": return getString(R.string.incident_type_jam);
            case "accident": return getString(R.string.incident_type_accident);
            case "flood": return getString(R.string.incident_type_flood);
            case "police": return getString(R.string.incident_type_police);
            case "hazard": return getString(R.string.incident_type_hazard);
            default: return "";
        }
    }

    private String timeAgo(long timestamp) {
        long minutes = (System.currentTimeMillis() - timestamp) / 60000;
        if (minutes < 1) return getString(R.string.time_just_now);
        if (minutes < 60) return getString(R.string.time_minutes_ago, (int) minutes);
        return getString(R.string.time_hours_ago, (int) (minutes / 60));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMap != null) {
            com.google.android.gms.maps.model.CameraPosition cam = mMap.getCameraPosition();
            getSharedPreferences("MapCameraPrefs", MODE_PRIVATE).edit()
                    .putLong("last_lat", Double.doubleToLongBits(cam.target.latitude))
                    .putLong("last_lng", Double.doubleToLongBits(cam.target.longitude))
                    .putFloat("last_zoom", cam.zoom)
                    .apply();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActive = false;
        mMap = null;
        executorService.shutdownNow();
        liveTrafficExecutor.shutdownNow();
        if (incidentsRef != null && incidentsListener != null) {
            incidentsRef.removeEventListener(incidentsListener);
        }
    }
}
