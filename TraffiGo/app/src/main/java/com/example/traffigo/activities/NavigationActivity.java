package com.example.traffigo.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.traffigo.R;
import com.example.traffigo.databinding.ActivityNavigationBinding;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.CameraPosition;
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
import com.google.android.gms.tasks.Task;

// --- THÊM IMPORT CỦA PLACES API ---
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.widget.Autocomplete;
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode;

import com.example.traffigo.models.TrafficSegment;
import com.example.traffigo.models.TrafficIncident;
import com.example.traffigo.models.NavStep;
import com.example.traffigo.utils.MlServerClient;
import com.example.traffigo.utils.RouteQualityAnalyzer;
import com.example.traffigo.utils.TomTomTrafficClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.google.android.libraries.places.widget.AutocompleteActivity;
import com.google.android.gms.common.api.Status;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
public class NavigationActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int REQUEST_CHECK_SETTINGS = 1001;

    private ActivityNavigationBinding binding;
    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private int currentStep = 1; // 1=chọn điểm đi, 2=chọn điểm đến, 3=hiện lộ trình, 4=đang dẫn đường
    private LatLng originPoint, destPoint, currentSelectedLatLng;
    private String lastOriginAddress; // địa chỉ điểm đi, dùng để lưu Lịch sử lộ trình khi tuyến được xác nhận
    private boolean isReroute = false; // true khi route đến từ auto-reroute lệch tuyến -> không lưu lại lịch sử
    private boolean isLiveSharing = false;
    private String liveShareSessionId;
    private double pendingJumpLat = 0, pendingJumpLng = 0;
    private Intent pendingVoiceIntent; // lệnh giọng nói chờ map sẵn sàng
    private Marker startMarker, endMarker;
    // true đúng 1 lần ngay trước khi moveToLatLng() được gọi từ kết quả GPS thật (nút "Vị trí hiện
    // tại") -> updateSelection() đọc rồi tự reset về false ngay sau đó (dùng 1 lần). Mục đích: bỏ vẽ
    // startMarker (pin) đè lên đúng vị trí chấm xanh "vị trí của bạn" (setMyLocationEnabled) vốn đã có
    // sẵn — 2 marker chồng nhau ở cùng 1 điểm nhìn như 1 hình lạ, không phải do device/emulator lỗi.
    private boolean nextSelectionIsCurrentLocation = false;

    private final List<Polyline> routePolylines = new ArrayList<>();
    private final List<TrafficSegment> allSegments = new ArrayList<>();
    private boolean segmentsLoaded = false;
    // Điểm dừng trung gian (multi-stop) — chỉ dùng cho lần tìm đường thủ công ở bước 2
    private final List<LatLng> waypoints = new ArrayList<>();
    private final List<Marker> waypointMarkers = new ArrayList<>();
    // congestionIndex live (TomTom) theo index trong allSegments, fetch theo route đang xét.
    // ConcurrentHashMap: ghi từ các thread fetch, đọc (getOrDefault/containsKey) từ UI thread và
    // thread nền khác — đồng bộ nửa vời bằng synchronized cũ vẫn để hở phần đọc → dễ hỏng bảng băm.
    private final Map<Integer, Double> liveCongestionByIndex = new java.util.concurrent.ConcurrentHashMap<>();
    // Thời điểm fetch mỗi entry (elapsedRealtime): điều kiện live cũ quá 3 phút sẽ bị fetch lại,
    // nếu không màu tuyến/điểm số dùng dữ liệu hàng giờ tuổi trong các phiên dài.
    private final Map<Integer, Long> liveCongestionFetchedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LIVE_CONGESTION_TTL_MS = 3 * 60 * 1000L;
    // Cache điểm->segment gần nhất (allSegments tĩnh sau khi preload nên mapping không bao giờ stale);
    // findNearestSegmentIndex quét tuyến tính toàn bộ segment, gọi mỗi điểm mỗi tick GPS thì rất nặng.
    private final Map<LatLng, Integer> nearestSegmentCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService trafficExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);

    private BottomSheetBehavior<View> bottomSheetBehavior;
    private List<List<LatLng>> savedPaths = new ArrayList<>();
    private List<Integer> savedDurations = new ArrayList<>();
    private List<Integer> savedDistances = new ArrayList<>();
    private List<List<NavStep>> savedSteps = new ArrayList<>();
    private int currentChosenIdx = 0;
    // Tăng mỗi lần reset/tìm tuyến mới; callback fetch trả về muộn sẽ so số này để bỏ kết quả stale
    private int routeGeneration = 0;
    // Hành động đang chờ cấp quyền vị trí: "current_loc" | "start_nav"
    private String pendingPermissionAction = null;

    // ===== Trạng thái chế độ dẫn đường turn-by-turn =====
    private boolean isNavigating = false;
    private List<NavStep> navSteps = new ArrayList<>();
    private List<LatLng> navPath = new ArrayList<>();
    private int currentStepIdx = 0;
    private boolean announcedApproach = false;   // đã nhắc trước (~150m) cho bước hiện tại chưa
    private int offRouteCount = 0;               // số lần đọc GPS liên tiếp bị lệch tuyến
    private boolean autoStartNavAfterReroute = false; // tự vào lại nav sau khi reroute xong
    private int lastTrimIndex = 0;               // điểm xa nhất trên navPath xe đã đi qua (để xoá đoạn cũ)
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean voiceEnabled = true;            // nút bật/tắt giọng nói dẫn đường
    // false sau onDestroy: chặn các callback nền (mạng/GPS) đụng view/map đã bị hủy → tránh crash
    private volatile boolean isActive = true;

    // ===== Đồng hồ tốc độ =====
    private static final String NAV_PREFS = "NavPrefs";
    private static final int[] SPEED_LIMIT_PRESETS = {40, 60, 80, 100};
    private int speedLimitKmh = 60; // ngưỡng cảnh báo mặc định (đường đô thị VN); chạm giữ đồng hồ để đổi
    private boolean overLimitWarned = false; // đã cảnh báo cho lần vượt hiện tại chưa (tránh nói lặp lại)
    private static final float ARRIVE_THRESHOLD_M = 30f;   // tới đích
    private static final float ADVANCE_THRESHOLD_M = 25f;  // chuyển bước rẽ
    private static final float APPROACH_THRESHOLD_M = 150f; // nhắc trước bước rẽ
    private static final float OFFROUTE_THRESHOLD_M = 50f;  // lệch tuyến → reroute
    private static final int OFFROUTE_TRIGGER = 3;          // số lần lệch liên tiếp để reroute

    // ===== Sự cố cộng đồng (Firebase "incidents") hiển thị + cảnh báo trên tuyến =====
    private static final long INCIDENT_EXPIRY_MS = 2 * 60 * 60 * 1000L; // ẩn báo cáo cũ hơn 2 giờ (giống TrafficMap)
    private static final int INCIDENT_DOWNVOTE_HIDE = 2;                 // ≥2 downvote & > upvote thì ẩn
    private static final float INCIDENT_ON_ROUTE_M = 50f;               // ≤50m so với polyline = nằm trên tuyến
    private static final float INCIDENT_WARN_AHEAD_M = 300f;            // báo khi còn cách ~300m
    private DatabaseReference navIncidentsRef;
    private ValueEventListener navIncidentsListener;
    private final Map<Marker, TrafficIncident> navIncidentMarkers = new HashMap<>();
    private final List<TrafficIncident> activeIncidents = new ArrayList<>();
    private final List<RouteIncident> onRouteIncidents = new ArrayList<>();
    private final Set<String> warnedIncidentIds = new HashSet<>();

    /** Một sự cố nằm trên tuyến + vị trí (index) gần nhất của nó trên navPath, để biết còn ở phía trước không. */
    private static class RouteIncident {
        final TrafficIncident inc;
        final int pathIdx;
        RouteIncident(TrafficIncident inc, int pathIdx) { this.inc = inc; this.pathIdx = pathIdx; }
    }

    // ===== Giới hạn tốc độ + đường một chiều theo đoạn đường (từ tag OSM trong geometry.csv) =====
    // Chỉ cảnh báo "một chiều" cho đường nhỏ — motorway/trunk/primary/secondary trên OSM phần lớn là
    // 2 way riêng biệt của 1 đại lộ chia dải phân cách (quy ước vẽ bản đồ), oneway=yes ở đó không phải
    // "phố cấm ngược chiều" theo nghĩa thông thường, cảnh báo sẽ chỉ gây nhiễu. Liệt kê theo tên (không
    // theo kiểu loại trừ) để nếu sau này geometry.csv có thêm residential/unclassified thì tự chạy đúng.
    private static final Set<String> ONEWAY_WARN_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
            "tertiary", "tertiary_link", "residential", "unclassified"));
    private int lastRoadInfoSegmentIdx = -1; // debounce: chỉ cập nhật badge/nói khi đổi sang đoạn khác

    // ===== Loại phương tiện =====
    // "car" -> travelMode DRIVE | "motorbike" -> travelMode TWO_WHEELER | "walk" -> WALK (Routes API).
    // Đổi qua 3 nút Ô tô/Xe máy/Đi bộ trong bottom sheet lúc chưa có tuyến, lưu lại cho lần sau (NavPrefs).
    private String vehicleType = "car";

    // ===== Ước tính chi phí chuyến đi =====
    // Mức tiêu hao trung bình (L/100km) và giá xăng - hằng số ước lượng, CHƯA cho người dùng tự chỉnh
    // (giữ tính năng đơn giản cho bản đầu). Đi bộ không tốn nhiên liệu nên không có trong bảng này.
    private static final Map<String, Double> FUEL_CONSUMPTION_L_PER_100KM = new HashMap<String, Double>() {{
        put("car", 7.0);
        put("motorbike", 2.2);
    }};
    private static final double FUEL_PRICE_VND_PER_LITER = 21000.0;
    private boolean avoidTolls = false;

    private FirebaseAuth mAuth;
    private String savingPlaceType = null;

    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private ActivityResultLauncher<Intent> autocompleteLauncher;
    private ActivityResultLauncher<Intent> savedPlaceLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNavigationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // KHỞI TẠO PLACES API TRƯỚC KHI XÀI
        initPlacesApi();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        binding.layoutHeader.tvPageTitle.setText(R.string.nav_title);
        binding.layoutHeader.btnShare.setVisibility(View.VISIBLE);
        binding.layoutHeader.btnShareContainer.setVisibility(View.VISIBLE);
        binding.layoutHeader.btnBack.setOnClickListener(v -> navigateToMain());

        binding.layoutHeader.btnShare.setOnClickListener(v -> showShareOptions());

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(binding.map.getId());
        if (mapFragment != null) mapFragment.getMapAsync(this);

        mAuth = FirebaseAuth.getInstance();
        speedLimitKmh = getSharedPreferences(NAV_PREFS, MODE_PRIVATE).getInt("speed_limit_kmh", 60);
        vehicleType = getSharedPreferences(NAV_PREFS, MODE_PRIVATE).getString("vehicle_type", "car");
        avoidTolls = getSharedPreferences(NAV_PREFS, MODE_PRIVATE).getBoolean("avoid_tolls", false);
        binding.cbAvoidTolls.setChecked(avoidTolls);
        setupPermissionLauncher();
        setupAutocompleteLauncher();
        setupSavedPlaceLauncher();
        setupListeners();
        updateVehicleTypeVisuals();
        preloadSegments();
        initTts();

        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        // Jump to location if launched from offline map detail
        pendingJumpLat = getIntent().getDoubleExtra("jump_lat", 0);
        pendingJumpLng = getIntent().getDoubleExtra("jump_lng", 0);
        handleVoiceExtras(getIntent());
    }

    private void initPlacesApi() {
        String apiKey = getApiKeyFromManifest();
        if (!Places.isInitialized() && apiKey != null && !apiKey.isEmpty()) {
            Places.initialize(getApplicationContext(), apiKey);
        }
    }

    private String getApiKeyFromManifest() {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;
            return bundle.getString("com.google.android.geo.API_KEY");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getRoutesApiKey() {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;
            return bundle.getString("com.traffigo.ROUTES_API_KEY");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void setupAutocompleteLauncher() {
        autocompleteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        double lat = data.getDoubleExtra(SearchPlaceActivity.EXTRA_LAT, Double.NaN);
                        double lng = data.getDoubleExtra(SearchPlaceActivity.EXTRA_LNG, Double.NaN);
                        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                            LatLng latLng = new LatLng(lat, lng);
                            moveToLatLng(latLng); // Tự động di chuyển bản đồ tới đó
                            String name = data.getStringExtra(SearchPlaceActivity.EXTRA_NAME);
                            String address = data.getStringExtra(SearchPlaceActivity.EXTRA_ADDRESS);
                            binding.tvAddress.setText(name != null ? name : address);
                        }
                    }
                }
        );
    }

    private void setupSavedPlaceLauncher() {
        savedPlaceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && savingPlaceType != null) {
                        Intent data = result.getData();
                        double lat = data.getDoubleExtra(SearchPlaceActivity.EXTRA_LAT, Double.NaN);
                        double lng = data.getDoubleExtra(SearchPlaceActivity.EXTRA_LNG, Double.NaN);
                        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                            String address = data.getStringExtra(SearchPlaceActivity.EXTRA_ADDRESS);
                            if (address == null) address = data.getStringExtra(SearchPlaceActivity.EXTRA_NAME);
                            saveSavedPlace(savingPlaceType, address, lat, lng);
                            moveToLatLng(new LatLng(lat, lng), address);
                        }
                    }
                    savingPlaceType = null;
                }
        );
    }

    private void showSavedPlaceDialog(String type) {
        if (mAuth.getCurrentUser() == null) {
            Toast.makeText(this, getString(R.string.toast_login_required), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isHome = "home".equals(type);
        String typeLabel = isHome ? getString(R.string.place_home) : getString(R.string.place_work);
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(mAuth.getCurrentUser().getUid())
                .child("savedPlaces")
                .child(type);

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String savedAddress = snapshot.child("address").getValue(String.class);
                Double savedLat = snapshot.child("lat").getValue(Double.class);
                Double savedLng = snapshot.child("lng").getValue(Double.class);

                if (savedAddress != null && savedLat != null && savedLng != null) {
                    LatLng savedLatLng = new LatLng(savedLat, savedLng);
                    showPlaceBottomSheet(type, isHome, savedAddress,
                            () -> moveToLatLng(savedLatLng, savedAddress),
                            () -> {
                                String currentAddr = binding.tvAddress.getText().toString();
                                boolean hasCurrentPos = currentSelectedLatLng != null
                                        && !currentAddr.equals(getString(R.string.nav_address_placeholder));
                                if (hasCurrentPos) {
                                    saveSavedPlace(type, currentAddr,
                                            currentSelectedLatLng.latitude,
                                            currentSelectedLatLng.longitude);
                                } else {
                                    openSavedPlaceSearch(type);
                                }
                            });
                } else {
                    String currentAddr = binding.tvAddress.getText().toString();
                    boolean hasCurrentPos = currentSelectedLatLng != null
                            && !currentAddr.equals(getString(R.string.nav_address_placeholder));

                    if (hasCurrentPos) {
                        showPlaceBottomSheet(type, isHome,
                                getString(R.string.confirm_save_place, typeLabel, currentAddr),
                                () -> saveSavedPlace(type, currentAddr,
                                        currentSelectedLatLng.latitude,
                                        currentSelectedLatLng.longitude),
                                () -> openSavedPlaceSearch(type));
                    } else {
                        showEmptyPlaceBottomSheet(type, isHome, typeLabel);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(NavigationActivity.this, getString(R.string.toast_load_addr_error) + ": " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPlaceBottomSheet(String type, boolean isHome, String address,
                                      Runnable onPrimary, Runnable onSecondary) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.layout_dialog_saved_place, null);
        dialog.setContentView(view);

        ((android.widget.ImageView) view.findViewById(R.id.ivPlaceIcon))
                .setImageResource(isHome ? R.drawable.ic_home_red : R.drawable.ic_office_red);
        ((android.widget.TextView) view.findViewById(R.id.tvDialogLabel))
                .setText(isHome ? getString(R.string.saved_home_label) : getString(R.string.saved_work_label));
        ((android.widget.TextView) view.findViewById(R.id.tvDialogTitle))
                .setText(isHome ? getString(R.string.saved_home_title) : getString(R.string.saved_work_title));
        ((android.widget.TextView) view.findViewById(R.id.tvDialogAddress)).setText(address);
        ((MaterialButton) view.findViewById(R.id.btnPrimary))
                .setText(onSecondary != null ? getString(R.string.btn_use) : getString(R.string.btn_save_location));

        view.findViewById(R.id.btnPrimary).setOnClickListener(v -> {
            dialog.dismiss();
            onPrimary.run();
        });
        if (onSecondary != null) {
            view.findViewById(R.id.btnSecondary).setOnClickListener(v -> {
                dialog.dismiss();
                onSecondary.run();
            });
        } else {
            view.findViewById(R.id.btnSecondary).setVisibility(View.GONE);
        }

        if (dialog.getWindow() != null) {
            dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void showEmptyPlaceBottomSheet(String type, boolean isHome, String typeLabel) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.layout_dialog_saved_place, null);
        dialog.setContentView(view);

        ((android.widget.ImageView) view.findViewById(R.id.ivPlaceIcon))
                .setImageResource(isHome ? R.drawable.ic_home_red : R.drawable.ic_office_red);
        ((android.widget.TextView) view.findViewById(R.id.tvDialogLabel))
                .setText(isHome ? getString(R.string.saved_home_label) : getString(R.string.saved_work_label));
        ((android.widget.TextView) view.findViewById(R.id.tvDialogTitle))
                .setText(isHome ? getString(R.string.saved_home_title) : getString(R.string.saved_work_title));
        ((android.widget.TextView) view.findViewById(R.id.tvDialogAddress))
                .setText(getString(R.string.no_saved_addr, typeLabel));
        ((MaterialButton) view.findViewById(R.id.btnPrimary)).setText(R.string.btn_add_address);
        view.findViewById(R.id.btnSecondary).setVisibility(View.GONE);

        view.findViewById(R.id.btnPrimary).setOnClickListener(v -> {
            dialog.dismiss();
            openSavedPlaceSearch(type);
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void openSavedPlaceSearch(String type) {
        savingPlaceType = type;
        Intent intent = new Intent(this, SearchPlaceActivity.class);
        savedPlaceLauncher.launch(intent);
    }

    private void saveSavedPlace(String type, String address, double lat, double lng) {
        if (mAuth.getCurrentUser() == null) return;
        String typeLabel = "home".equals(type) ? getString(R.string.place_home) : getString(R.string.place_work);
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(mAuth.getCurrentUser().getUid())
                .child("savedPlaces")
                .child(type);

        Map<String, Object> data = new HashMap<>();
        data.put("address", address);
        data.put("lat", lat);
        data.put("lng", lng);

        ref.setValue(data)
                .addOnSuccessListener(aVoid ->
                        Toast.makeText(this, getString(R.string.toast_place_saved, typeLabel), Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.toast_save_addr_error) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void setupListeners() {
        binding.btnMainAction.setOnClickListener(v -> handleFlow());
        binding.btnReset.setOnClickListener(v -> resetNavigation());
        binding.tvRouteAlternative.setOnClickListener(v -> switchToNextRoute());
        binding.btnBookService.setOnClickListener(v -> openRideService());
        binding.btnStartNav.setOnClickListener(v -> startNavigationMode());
        binding.btnStopNav.setOnClickListener(v -> stopNavigationMode());
        binding.btnToggleVoice.setOnClickListener(v -> toggleVoice());
        binding.btnAddStop.setOnClickListener(v -> addWaypoint());
        binding.cardSpeedometer.setOnLongClickListener(v -> { cycleSpeedLimit(); return true; });
        binding.btnVehicleCar.setOnClickListener(v -> selectVehicleType("car"));
        binding.btnVehicleMotorbike.setOnClickListener(v -> selectVehicleType("motorbike"));
        binding.btnVehicleWalk.setOnClickListener(v -> selectVehicleType("walk"));
        binding.cbAvoidTolls.setOnCheckedChangeListener((btn, checked) -> {
            avoidTolls = checked;
            getSharedPreferences(NAV_PREFS, MODE_PRIVATE).edit().putBoolean("avoid_tolls", checked).apply();
        });

        binding.btnCurrentLoc.setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                pendingPermissionAction = "current_loc";
                locationPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            } else {
                checkGPSSettingsAndGetLocation();
            }
        });

        binding.btnHomeShortcut.setOnClickListener(v -> showSavedPlaceDialog("home"));
        binding.btnWorkShortcut.setOnClickListener(v -> showSavedPlaceDialog("work"));

        // --- SỰ KIỆN MỞ MÀN HÌNH TÌM KIẾM KHI BẤM VÀO ĐỊA CHỈ ---
        binding.tvAddress.setOnClickListener(v -> openSearchPlaceUI());
    }

    // Mở màn hình tìm địa điểm tùy biến (thay UI mặc định của Google)
    private void openSearchPlaceUI() {
        Intent intent = new Intent(this, SearchPlaceActivity.class);
        autocompleteLauncher.launch(intent);
    }

    // Các hàm setup Permission và Location giữ nguyên không đổi...
    private void setupPermissionLauncher() {
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (Boolean.TRUE.equals(result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false))) {
                        enableMyLocation();
                        // Tiếp tục đúng hành động ban đầu đã xin quyền, nếu không người dùng
                        // phải bấm lại lần nữa sau khi cấp quyền (tap đầu bị "rơi vào hư không").
                        String action = pendingPermissionAction;
                        pendingPermissionAction = null;
                        if ("current_loc".equals(action)) {
                            checkGPSSettingsAndGetLocation();
                        } else if ("start_nav".equals(action)) {
                            startNavigationMode();
                        }
                    }
                }
        );
    }

    private void checkGPSSettingsAndGetLocation() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build();
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest);
        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(this, locationSettingsResponse -> startLocationUpdates());
        task.addOnFailureListener(this, e -> {
            if (e instanceof ResolvableApiException) {
                try {
                    ((ResolvableApiException) e).startResolutionForResult(NavigationActivity.this, REQUEST_CHECK_SETTINGS);
                } catch (IntentSender.SendIntentException ignored) {}
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == RESULT_OK) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, getString(R.string.toast_gps_off), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        Toast.makeText(this, getString(R.string.toast_locating), Toast.LENGTH_SHORT).show();
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setWaitForAccurateLocation(true)
                .setMaxUpdates(1)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLastLocation() != null) {
                    nextSelectionIsCurrentLocation = true;
                    moveToLatLng(new LatLng(locationResult.getLastLocation().getLatitude(), locationResult.getLastLocation().getLongitude()));
                    fusedLocationClient.removeLocationUpdates(locationCallback);
                }
            }
        };
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.setOnMapClickListener(this::updateSelection);
        // Chạm đúng vào icon/tên địa điểm (bệnh viện, quán ăn...) trên bản đồ -> Maps SDK tự nhận diện
        // và trả tên thật (poi.name) thay vì gọi setOnMapClickListener chung chung. Dùng luôn tên này
        // làm knownAddress cho updateSelection() - khỏi phải gọi Geocoder (chỉ trả địa chỉ đường, vd
        // "202 Đ. Tản Đà", không có tên POI) cho những điểm đã có sẵn tên rõ ràng trên bản đồ.
        mMap.setOnPoiClickListener(poi -> updateSelection(poi.latLng, flattenPoiName(poi.name)));

        SharedPreferences camPrefs = getSharedPreferences("MapCameraPrefs", MODE_PRIVATE);
        double lat = Double.longBitsToDouble(camPrefs.getLong("last_lat", Double.doubleToLongBits(10.7767)));
        double lng = Double.longBitsToDouble(camPrefs.getLong("last_lng", Double.doubleToLongBits(106.6656)));
        float zoom = camPrefs.getFloat("last_zoom", 15f);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom));
        enableMyLocation();

        if (pendingJumpLat != 0 && pendingJumpLng != 0) {
            moveToLatLng(new LatLng(pendingJumpLat, pendingJumpLng));
            pendingJumpLat = 0;
            pendingJumpLng = 0;
        }

        if (pendingVoiceIntent != null) {
            Intent vi = pendingVoiceIntent;
            pendingVoiceIntent = null;
            handleVoiceExtras(vi);
        }

        listenForNavIncidents();
    }

    /**
     * poi.name đôi khi chứa ký tự xuống dòng ẩn ngay trong chuỗi (Maps SDK trả nguyên văn nhãn 2 dòng
     * hiện trên bản đồ, vd "The Coffee House - \nNguyễn Tri Phương" hay "FamilyMart ABC\nCửa hàng tiện
     * lợi") — nhét thẳng vào ô địa chỉ 1 dòng sẽ hiện tách rời trông như 2 câu không liên quan. Gộp
     * lại thành 1 dòng, ngăn cách bằng dấu phẩy cho tự nhiên (bỏ luôn dấu "-" thừa nếu đứng ngay trước
     * chỗ xuống dòng, kiểu "Tên - \nChi nhánh" -> "Tên, Chi nhánh").
     */
    private String flattenPoiName(String name) {
        if (name == null) return null;
        return name.replaceAll("\\s*-\\s*\n\\s*", ", ")
                .replaceAll("\\s*\n\\s*", ", ")
                .trim();
    }

    private void updateSelection(LatLng latLng) {
        updateSelection(latLng, null);
    }

    private void updateSelection(LatLng latLng, @Nullable String knownAddress) {
        // Chặn từ bước 3 (đang xem tuyến): bấm nhầm bản đồ lúc này sẽ đổi điểm đi/đến và xoá sạch
        // tuyến đang hiển thị. Đang dẫn đường (bước 4) còn nguy hiểm hơn: camera khoá theo xe nên
        // chạm gần tâm màn hình = đích bị dời tới trước mũi xe vài chục mét -> app báo "đã đến nơi".
        if (currentStep >= 3) return;
        currentSelectedLatLng = latLng;
        if (currentStep == 1) {
            originPoint = latLng;
            boolean isCurrentLocation = nextSelectionIsCurrentLocation;
            nextSelectionIsCurrentLocation = false;
            setOriginMarker(latLng, isCurrentLocation);
        } else {
            destPoint = latLng;
            if (endMarker != null) endMarker.remove();
            endMarker = mMap.addMarker(new MarkerOptions().position(latLng).title(getString(R.string.marker_destination))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE)));
        }
        if (knownAddress != null) {
            binding.tvAddress.setText(knownAddress);
        } else {
            updateAddressText(latLng);
        }
    }

    /**
     * Đặt marker điểm đi. Khi điểm đi trùng vị trí GPS thật của người dùng, KHÔNG vẽ pin — chấm xanh
     * "vị trí của bạn" (mMap.setMyLocationEnabled(), bật trong enableMyLocation()) đã hiện sẵn đúng
     * chỗ đó rồi, vẽ thêm pin đè lên trông như 2 marker dính vào nhau (kỳ, giống 1 hình lạ) thay vì 1
     * chấm định vị bình thường. Trường hợp điểm đi là toạ độ khác (tự chọn trên bản đồ/tìm kiếm/địa
     * điểm đã lưu, hoặc GPS fallback về tâm bản đồ khi không lấy được vị trí thật) vẫn vẽ pin như cũ.
     */
    private void setOriginMarker(LatLng point, boolean isCurrentLocation) {
        if (startMarker != null) { startMarker.remove(); startMarker = null; }
        if (isCurrentLocation) return;
        startMarker = mMap.addMarker(new MarkerOptions().position(point).title(getString(R.string.marker_origin))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
    }

    private void updateAddressText(LatLng latLng) {
        // Hiện toạ độ ngay để ô địa chỉ luôn cập nhật (kể cả khi Geocoder rỗng/offline)
        String fallback = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude);
        binding.tvAddress.setText(fallback);

        new Thread(() -> {
            String result = fallback;
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
                if (addresses != null && !addresses.isEmpty() && addresses.get(0).getAddressLine(0) != null) {
                    result = addresses.get(0).getAddressLine(0);
                }
            } catch (Exception ignored) {
            }
            final String finalResult = result;
            runOnUiThread(() -> { if (isActive) binding.tvAddress.setText(finalResult); });
        }).start();
    }

    private void handleFlow() {
        if (currentStep == 1) {
            if (originPoint == null) { Toast.makeText(this, getString(R.string.toast_select_origin), Toast.LENGTH_SHORT).show(); return; }
            lastOriginAddress = binding.tvAddress.getText().toString();
            currentStep = 2;
            binding.tvStepTitle.setText(R.string.nav_step_choose_dest);
            binding.tvAddress.setText(R.string.nav_address_placeholder);
            binding.btnMainAction.setText(R.string.nav_confirm_destination);
            binding.btnMainAction.setBackgroundTintList(getColorStateList(R.color.brand_primary));
            binding.btnAddStop.setVisibility(View.VISIBLE);
        } else if (currentStep == 2) {
            if (destPoint == null) { Toast.makeText(this, getString(R.string.toast_select_destination), Toast.LENGTH_SHORT).show(); return; }
            fetchAndDrawRoute(originPoint, destPoint, waypoints);
        } else {
            resetNavigation();
        }
    }

    /**
     * Thêm điểm đang chọn làm điểm dừng trung gian rồi cho chọn điểm kế tiếp; điểm cuối cùng khi
     * "Xác nhận điểm đến" sẽ là đích. Các điểm dừng được gửi qua "intermediates" của Routes API.
     */
    private void addWaypoint() {
        if (currentStep != 2 || currentSelectedLatLng == null || mMap == null) {
            Toast.makeText(this, getString(R.string.toast_select_stop), Toast.LENGTH_SHORT).show();
            return;
        }
        waypoints.add(currentSelectedLatLng);
        Marker m = mMap.addMarker(new MarkerOptions().position(currentSelectedLatLng)
                .title(getString(R.string.nav_stop_label, waypoints.size()))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)));
        if (m != null) waypointMarkers.add(m);

        // Xoá lựa chọn đích tạm để người dùng chạm chọn điểm kế tiếp (điểm cuối mới là đích)
        destPoint = null;
        currentSelectedLatLng = null;
        if (endMarker != null) { endMarker.remove(); endMarker = null; }
        binding.tvAddress.setText(R.string.nav_address_placeholder);
        binding.btnAddStop.setText(getString(R.string.nav_add_stop_count, waypoints.size()));
        Toast.makeText(this, getString(R.string.nav_stop_added, waypoints.size()), Toast.LENGTH_SHORT).show();
    }

    private void resetNavigation() {
        if (isNavigating) stopNavigationMode();
        routeGeneration++; // huỷ mọi callback fetch tuyến đang chờ về muộn
        nearestSegmentCache.clear();
        currentStep = 1;
        originPoint = null;
        destPoint = null;
        if (startMarker != null) { startMarker.remove(); startMarker = null; }
        if (endMarker != null) { endMarker.remove(); endMarker = null; }
        for (Marker m : waypointMarkers) m.remove();
        waypointMarkers.clear();
        waypoints.clear();
        binding.btnAddStop.setVisibility(View.GONE);
        binding.btnAddStop.setText(R.string.nav_add_stop);
        for (Polyline p : routePolylines) p.remove();
        routePolylines.clear();
        savedPaths.clear();
        savedSteps.clear();
        savedDurations.clear();
        savedDistances.clear();
        binding.btnStartNav.setVisibility(View.GONE);
        binding.layoutRouteInfo.setVisibility(View.GONE);
        binding.tvStepTitle.setText(R.string.nav_step_start);
        binding.tvAddress.setText(R.string.nav_address_placeholder);
        binding.btnMainAction.setVisibility(View.VISIBLE);
        binding.btnMainAction.setText(R.string.nav_confirm_origin);
        binding.btnMainAction.setEnabled(true);
        binding.progressFindingRoute.setVisibility(View.GONE);
        binding.layoutVehicleType.setVisibility(View.VISIBLE);
        binding.btnMainAction.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#5C4FE0")));
        binding.btnReset.setVisibility(View.GONE);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private void switchToNextRoute() {
        if (savedPaths.size() <= 1) return;
        switchToRouteIndex((currentChosenIdx + 1) % savedPaths.size());
    }

    /**
     * Chuyển sang xem tuyến thứ target (bước 3). Đang dẫn đường thì chặn: navPath/navSteps đã
     * gắn chặt tuyến cũ, chuyển tuyến giữa chừng phải qua reroute thay vì chỉ đổi polyline.
     */
    private void switchToRouteIndex(int target) {
        if (target < 0 || target >= savedPaths.size()) return;
        if (isNavigating) return;
        currentChosenIdx = target;
        final int genAtClick = routeGeneration;
        binding.btnStartNav.setVisibility(
                (currentChosenIdx < savedSteps.size() && !savedSteps.get(currentChosenIdx).isEmpty())
                        ? View.VISIBLE : View.GONE);
        for (Polyline p : routePolylines) p.remove();
        routePolylines.clear();
        for (int i = 0; i < savedPaths.size(); i++) {
            if (i != currentChosenIdx) {
                routePolylines.add(mMap.addPolyline(new PolylineOptions()
                        .addAll(savedPaths.get(i)).width(10f)
                        .color(Color.argb(80, 158, 158, 158)).geodesic(true)));
            }
        }
        List<LatLng> chosenPath = savedPaths.get(currentChosenIdx);
        prefetchCongestionForPath(chosenPath, () -> {
            if (genAtClick != routeGeneration) return; // đã reset giữa chừng, bỏ kết quả cũ
            drawColoredRoute(chosenPath);
            int durMin = savedDurations.get(currentChosenIdx) / 60;
            float distKm = savedDistances.get(currentChosenIdx) / 1000f;
            String congText = getCongestionInfo(chosenPath);
            String altText = getString(R.string.route_alt_pattern, currentChosenIdx + 1, savedPaths.size());
            updateRouteInfo(durMin, distKm, congText, altText);
        });
    }

    /**
     * Hỏi TraffiGo ML Server (/api/ml/recommend-route) chấm điểm lại các tuyến ứng viên bằng
     * mô hình dự đoán tốc độ đã train trong đồ án (RF + KMeans k=6). Server chọn tuyến khác
     * tuyến đang hiển thị thì tự chuyển kèm Toast thông báo; server offline thì bỏ qua im lặng
     * — tuyến đã chọn bằng điểm số TomTom on-device vẫn là phương án an toàn mặc định.
     */
    private void requestMlRouteRecommendation() {
        if (!segmentsLoaded || savedPaths.size() <= 1 || isNavigating) return;
        try {
            final int genAtCall = routeGeneration;
            final int hourOfDay = MlServerClient.currentHour();
            final int dayOfWeek = MlServerClient.todayDayOfWeek();
            JSONArray routesArr = new JSONArray();
            int sampleCount = 10;
            for (int r = 0; r < savedPaths.size(); r++) {
                List<LatLng> path = savedPaths.get(r);
                int dist = r < savedDistances.size() ? savedDistances.get(r) : 0;
                int dur = r < savedDurations.size() ? savedDurations.get(r) : 0;
                JSONArray samples = new JSONArray();
                double sampleLenKm = Math.max(0.1, (dist / 1000.0) / sampleCount);
                for (int s = 0; s < sampleCount && !path.isEmpty(); s++) {
                    LatLng point = path.get((int) ((long) (s + 0.5) * path.size() / sampleCount));
                    int segIdx = findNearestSegmentIndex(point);
                    double currentSpeed = 0;
                    double freeFlow = 50;
                    Integer speedLimit = null;
                    String roadType = null;
                    if (segIdx != -1) {
                        TrafficSegment seg = allSegments.get(segIdx);
                        Double cong = liveCongestionByIndex.get(segIdx);
                        if (seg.speedLimitKmh != null && seg.speedLimitKmh > 0) {
                            freeFlow = seg.speedLimitKmh;
                            speedLimit = seg.speedLimitKmh;
                        }
                        if (seg.highwayType != null && !seg.highwayType.isEmpty()) roadType = seg.highwayType;
                        if (cong != null && cong > 0.05) currentSpeed = cong * freeFlow;
                    }
                    samples.put(MlServerClient.sample(currentSpeed, freeFlow, sampleLenKm,
                            speedLimit, roadType, hourOfDay, dayOfWeek));
                }
                routesArr.put(MlServerClient.routeCandidate(String.valueOf(r), dur, dist, samples));
            }
            JSONObject body = new JSONObject().put("routes", routesArr);
            MlServerClient.post(this, "/api/ml/recommend-route", body, new MlServerClient.MlCallback() {
                @Override public void onSuccess(JSONObject response) {
                    if (!isActive || genAtCall != routeGeneration) return;
                    String bestId = response.optString("recommendedId", "");
                    int bestIdx;
                    try { bestIdx = Integer.parseInt(bestId); } catch (NumberFormatException e) { return; }
                    if (bestIdx < 0 || bestIdx >= savedPaths.size() || bestIdx == currentChosenIdx
                            || isNavigating) return;
                    Toast.makeText(NavigationActivity.this,
                            getString(R.string.nav_ml_pick, bestIdx + 1), Toast.LENGTH_LONG).show();
                    switchToRouteIndex(bestIdx);
                }

                @Override public void onError(String message) {
                    // Server offline: giữ nguyên tuyến đã chọn bằng điểm số on-device.
                }
            });
        } catch (Exception e) {
            Log.e("TraffiGo", "ML recommend error: " + e.getMessage());
        }
    }

    // ===================== CHẾ ĐỘ DẪN ĐƯỜNG TURN-BY-TURN =====================

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("vi", "VN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                ttsReady = true;
            }
        });
    }

    private void speak(String text) {
        if (voiceEnabled && ttsReady && text != null && !text.isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav");
        }
    }

    /** Bật/tắt giọng nói dẫn đường (nút loa trên banner). */
    private void toggleVoice() {
        voiceEnabled = !voiceEnabled;
        binding.btnToggleVoice.setImageResource(
                voiceEnabled ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        if (!voiceEnabled && tts != null) tts.stop();
        Toast.makeText(this, getString(voiceEnabled ? R.string.nav_voice_on : R.string.nav_voice_off),
                Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("MissingPermission")
    private void startNavigationMode() {
        if (currentChosenIdx >= savedPaths.size()) return;
        navPath = savedPaths.get(currentChosenIdx);
        navSteps = (currentChosenIdx < savedSteps.size())
                ? savedSteps.get(currentChosenIdx) : new ArrayList<>();
        if (navSteps.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_steps), Toast.LENGTH_SHORT).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = "start_nav";
            locationPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            return;
        }

        isNavigating = true;
        currentStep = 4;
        currentStepIdx = 0;
        announcedApproach = false;
        offRouteCount = 0;
        lastTrimIndex = 0;
        computeOnRouteIncidents(); // lọc sự cố nằm trên tuyến để cảnh báo khi tới gần

        binding.layoutHeader.getRoot().setVisibility(View.GONE);
        binding.layoutNavBanner.setVisibility(View.VISIBLE);
        binding.btnStopNav.setVisibility(View.VISIBLE);
        binding.cardNavTripInfo.setVisibility(View.VISIBLE);
        binding.cardSpeedometer.setVisibility(View.VISIBLE);
        overLimitWarned = false;
        lastRoadInfoSegmentIdx = -1;
        binding.tvSpeedLimitBadge.setVisibility(View.GONE);
        binding.tvOnewayBadge.setVisibility(View.GONE);
        binding.tvNavGpsWarning.setVisibility(View.GONE);
        binding.btnToggleVoice.setImageResource(
                voiceEnabled ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        bottomSheetBehavior.setHideable(true);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        refreshBannerForCurrentStep(navSteps.get(0).distanceMeters);
        speak(stripHtml(navSteps.get(0).instruction));

        startNavLocationUpdates();
    }

    @SuppressLint("MissingPermission")
    private void startNavLocationUpdates() {
        if (locationCallback != null) fusedLocationClient.removeLocationUpdates(locationCallback);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(1000)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null && isNavigating) onNavLocationUpdate(loc);
            }

            @Override
            public void onLocationAvailability(@NonNull com.google.android.gms.location.LocationAvailability availability) {
                // Hiện/ẩn cảnh báo "đang tìm GPS" khi tín hiệu vị trí gián đoạn (vào hầm, mất sóng…)
                if (!isNavigating || !isActive) return;
                boolean ok = availability.isLocationAvailable();
                binding.tvNavGpsWarning.setVisibility(ok ? View.GONE : View.VISIBLE);
            }
        };
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
    }

    private void onNavLocationUpdate(Location loc) {
        LatLng pos = new LatLng(loc.getLatitude(), loc.getLongitude());

        // Chia sẻ vị trí trực tiếp: đẩy toạ độ mới lên Firebase để người xem link cập nhật theo
        if (isLiveSharing && liveShareSessionId != null) {
            Map<String, Object> update = new HashMap<>();
            update.put("lat", pos.latitude);
            update.put("lng", pos.longitude);
            update.put("updatedAt", System.currentTimeMillis());
            FirebaseDatabase.getInstance().getReference("LiveShares").child(liveShareSessionId).updateChildren(update);
        }

        // Camera bám xe (nghiêng + xoay theo hướng đi)
        if (mMap != null) {
            float bearing = loc.hasBearing() ? loc.getBearing() : mMap.getCameraPosition().bearing;
            CameraPosition cp = new CameraPosition.Builder()
                    .target(pos).zoom(17.5f).tilt(50f).bearing(bearing).build();
            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 800, null);
        }

        // Xoá đoạn đường xe đã đi qua, chỉ giữ lại phần phía trước cho đỡ rối mắt
        trimTraveledRoute(pos);

        // Cập nhật thời gian/quãng đường còn lại + giờ đến (ETA)
        updateTripInfo();
        updateSpeedometer(loc);
        updateRoadInfoBadges(pos);

        // Cảnh báo sự cố cộng đồng phía trước trên tuyến
        checkIncidentAhead(pos);

        // Đã tới đích?
        if (destPoint != null && distanceMeters(pos, destPoint) < ARRIVE_THRESHOLD_M) {
            speak(getString(R.string.nav_arrived));
            Toast.makeText(this, getString(R.string.nav_arrived), Toast.LENGTH_LONG).show();
            stopNavigationMode();
            return;
        }

        // Lệch tuyến → đếm, đủ ngưỡng thì tự tìm lại đường
        float distToRoute = distanceToPath(pos, navPath);
        if (distToRoute > OFFROUTE_THRESHOLD_M) {
            offRouteCount++;
            if (offRouteCount >= OFFROUTE_TRIGGER) {
                triggerReroute(pos);
                return;
            }
        } else {
            offRouteCount = 0;
        }

        // Cập nhật bước rẽ
        if (currentStepIdx < navSteps.size()) {
            NavStep step = navSteps.get(currentStepIdx);
            float distToTurn = distanceMeters(pos, step.endLatLng);
            refreshBannerForCurrentStep(Math.round(distToTurn));

            // Nhắc trước khi tới điểm rẽ kế tiếp
            if (!announcedApproach && distToTurn < APPROACH_THRESHOLD_M
                    && currentStepIdx + 1 < navSteps.size()) {
                String next = stripHtml(navSteps.get(currentStepIdx + 1).instruction);
                speak(getString(R.string.nav_in_meters, Math.round(distToTurn)) + ". " + next);
                announcedApproach = true;
            }

            // Tới điểm rẽ → chuyển bước
            if (distToTurn < ADVANCE_THRESHOLD_M) {
                currentStepIdx++;
                announcedApproach = false;
                if (currentStepIdx < navSteps.size()) {
                    refreshBannerForCurrentStep(navSteps.get(currentStepIdx).distanceMeters);
                }
            }
        }
    }

    // Xoá phần polyline đã đi qua: tìm điểm gần nhất trên navPath (chỉ tìm về phía trước để
    // tránh nhảy lùi khi có đường song song gần đó), rồi vẽ lại chỉ đoạn còn phía trước.
    private void trimTraveledRoute(LatLng pos) {
        if (navPath == null || navPath.size() < 2) return;
        int searchEnd = Math.min(navPath.size() - 1, lastTrimIndex + 60);
        int bestIdx = lastTrimIndex;
        double bestDist = Double.MAX_VALUE;
        for (int i = lastTrimIndex; i < searchEnd; i++) {
            double d = distToSegment(pos, navPath.get(i), navPath.get(i + 1));
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        // Cửa sổ cục bộ (+60 điểm) không tìm được điểm nào đủ gần: thường do bắt đầu dẫn đường khi
        // xe đã ở giữa tuyến (điểm đi đặt sẵn/đi lại từ lịch sử/GPS lệch điểm đầu), khiến vị trí xe
        // nằm ngoài cửa sổ → kẹt ở index cũ, đoạn đã đi phía sau không bao giờ bị xoá. Quét toàn
        // tuyến còn lại để bắt lại đúng vị trí (vẫn yêu cầu < ngưỡng lệch nên không cắt nhầm khi off-route).
        if (bestDist > OFFROUTE_THRESHOLD_M) {
            for (int i = searchEnd; i < navPath.size() - 1; i++) {
                double d = distToSegment(pos, navPath.get(i), navPath.get(i + 1));
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }
        }
        if (bestIdx > lastTrimIndex && bestDist < OFFROUTE_THRESHOLD_M) {
            lastTrimIndex = bestIdx;
            for (Polyline p : routePolylines) p.remove();
            routePolylines.clear();
            drawColoredRoute(new ArrayList<>(navPath.subList(lastTrimIndex, navPath.size())));
        }
    }

    /**
     * Cập nhật thẻ dưới màn hình khi đang dẫn đường: thời gian còn lại (ước tính bằng tổng thời gian
     * Google nhân theo tỉ lệ quãng đường còn lại), quãng đường còn lại (đo dọc navPath từ điểm đã đi
     * tới cuối), và giờ đến dự kiến (ETA).
     */
    private void updateTripInfo() {
        if (navPath == null || navPath.size() < 2) return;

        double remKm = 0;
        int from = Math.max(0, Math.min(lastTrimIndex, navPath.size() - 1));
        for (int i = from; i < navPath.size() - 1; i++) {
            remKm += haversineKm(navPath.get(i).latitude, navPath.get(i).longitude,
                    navPath.get(i + 1).latitude, navPath.get(i + 1).longitude);
        }
        int remMeters = (int) Math.round(remKm * 1000);

        int totalSec = (currentChosenIdx < savedDurations.size()) ? savedDurations.get(currentChosenIdx) : 0;
        int totalMeters = (currentChosenIdx < savedDistances.size()) ? savedDistances.get(currentChosenIdx) : 0;
        int remSec = (totalMeters > 0) ? (int) Math.round(totalSec * (remMeters / (double) totalMeters)) : totalSec;
        int remMin = Math.max(1, (remSec + 59) / 60);

        java.util.Calendar eta = java.util.Calendar.getInstance();
        eta.add(java.util.Calendar.SECOND, remSec);
        String clock = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(eta.getTime());

        binding.tvNavEta.setText(getString(R.string.nav_minutes, remMin));
        binding.tvNavRemaining.setText(formatDistance(remMeters) + " · " + getString(R.string.nav_arrive_at, clock));
    }

    /**
     * Cập nhật đồng hồ tốc độ từ Location.getSpeed() (m/s, chỉ tin cậy khi hasSpeed()==true).
     * Vượt ngưỡng tự chọn (mặc định 60km/h, đổi được bằng chạm giữ) -> đổi màu đỏ + cảnh báo giọng
     * nói MỘT LẦN cho mỗi lần vượt (không lặp lại liên tục), reset khi tốc độ về dưới ngưỡng.
     */
    private void updateSpeedometer(Location loc) {
        int speedKmh = loc.hasSpeed() ? Math.round(loc.getSpeed() * 3.6f) : 0;
        binding.tvSpeedValue.setText(String.valueOf(speedKmh));

        boolean overLimit = speedKmh > speedLimitKmh;
        binding.cardSpeedometer.setCardBackgroundColor(
                overLimit ? Color.parseColor("#FFEBEE") : Color.WHITE);
        binding.tvSpeedValue.setTextColor(
                overLimit ? Color.parseColor("#D32F2F") : Color.parseColor("#212121"));

        if (overLimit && !overLimitWarned) {
            overLimitWarned = true;
            speak(getString(R.string.speed_overlimit_warning));
        } else if (!overLimit) {
            overLimitWarned = false;
        }
    }

    /**
     * Hiện badge giới hạn tốc độ + đường một chiều của đoạn gần vị trí hiện tại nhất (dữ liệu tag OSM
     * trong geometry.csv, xem TrafficSegment.speedLimitKmh/oneway/highwayType). Chỉ cập nhật/nói khi
     * đổi sang đoạn khác (lastRoadInfoSegmentIdx) để không nói lặp lại mỗi lần có GPS mới. Đoạn không
     * có dữ liệu (segmentsLoaded=false, không tìm thấy đoạn gần, hoặc OSM không gắn tag) -> ẩn badge,
     * không đoán bừa.
     */
    private void updateRoadInfoBadges(LatLng pos) {
        if (!segmentsLoaded) return;
        int idx = findNearestSegmentIndex(pos);
        if (idx == lastRoadInfoSegmentIdx) return;
        lastRoadInfoSegmentIdx = idx;

        TrafficSegment seg = (idx != -1) ? allSegments.get(idx) : null;
        Integer limit = (seg != null) ? seg.speedLimitKmh : null;
        boolean isOneway = seg != null && "yes".equals(seg.oneway)
                && ONEWAY_WARN_HIGHWAY_TYPES.contains(seg.highwayType);

        if (limit != null) {
            binding.tvSpeedLimitBadge.setText(String.valueOf(limit));
            binding.tvSpeedLimitBadge.setVisibility(View.VISIBLE);
        } else {
            binding.tvSpeedLimitBadge.setVisibility(View.GONE);
        }
        binding.tvOnewayBadge.setVisibility(isOneway ? View.VISIBLE : View.GONE);

        StringBuilder toSpeak = new StringBuilder();
        if (limit != null) toSpeak.append(getString(R.string.nav_speed_limit_voice, limit));
        if (isOneway) {
            if (toSpeak.length() > 0) toSpeak.append(". ");
            toSpeak.append(getString(R.string.nav_oneway_voice));
        }
        if (toSpeak.length() > 0) speak(toSpeak.toString());
    }

    /** Chạm giữ đồng hồ tốc độ để đổi ngưỡng cảnh báo qua 4 mốc phổ biến (40/60/80/100 km/h), lưu lại cho lần sau. */
    private void cycleSpeedLimit() {
        int idx = 0;
        for (int i = 0; i < SPEED_LIMIT_PRESETS.length; i++) {
            if (SPEED_LIMIT_PRESETS[i] == speedLimitKmh) { idx = i; break; }
        }
        speedLimitKmh = SPEED_LIMIT_PRESETS[(idx + 1) % SPEED_LIMIT_PRESETS.length];
        getSharedPreferences(NAV_PREFS, MODE_PRIVATE).edit().putInt("speed_limit_kmh", speedLimitKmh).apply();
        overLimitWarned = false;
        Toast.makeText(this, getString(R.string.speed_limit_changed, speedLimitKmh), Toast.LENGTH_SHORT).show();
    }

    /** Đổi loại phương tiện dùng để tính tuyến; áp dụng cho lần fetchAndDrawRoute() kế tiếp. */
    private void selectVehicleType(String type) {
        if (type.equals(vehicleType)) return;
        vehicleType = type;
        getSharedPreferences(NAV_PREFS, MODE_PRIVATE).edit().putString("vehicle_type", type).apply();
        updateVehicleTypeVisuals();
    }

    private void updateVehicleTypeVisuals() {
        boolean isCar = "car".equals(vehicleType);
        boolean isMotorbike = "motorbike".equals(vehicleType);
        boolean isWalk = "walk".equals(vehicleType);
        highlightVehicleButton(binding.btnVehicleCar, isCar);
        highlightVehicleButton(binding.btnVehicleMotorbike, isMotorbike);
        highlightVehicleButton(binding.btnVehicleWalk, isWalk);
        // Google ghi rõ: chỉ đường xe máy (TWO_WHEELER) VÀ đi bộ (WALK) đều đang beta, có thể thiếu
        // làn/đường dành riêng - bắt buộc hiển thị cảnh báo cho cả 2, không riêng xe máy.
        binding.tvVehicleBetaNote.setVisibility(isCar ? View.GONE : View.VISIBLE);
        // Đi bộ không có khái niệm phí cầu đường - ẩn hẳn checkbox thay vì để bấm được nhưng vô nghĩa.
        binding.cbAvoidTolls.setVisibility(isWalk ? View.GONE : View.VISIBLE);
    }

    /** "car" -> DRIVE (mặc định) | "motorbike" -> TWO_WHEELER | "walk" -> WALK. */
    private String travelModeFor(String vehicleType) {
        if ("motorbike".equals(vehicleType)) return "TWO_WHEELER";
        if ("walk".equals(vehicleType)) return "WALK";
        return "DRIVE";
    }

    private void highlightVehicleButton(MaterialButton button, boolean selected) {
        int bg = Color.parseColor(selected ? "#5C4FE0" : "#F0EEFF");
        int fg = Color.parseColor(selected ? "#FFFFFF" : "#5C4FE0");
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        button.setTextColor(fg);
        button.setIconTint(android.content.res.ColorStateList.valueOf(fg));
    }

    // Banner hiển thị maneuver SẮP TỚI (instruction của bước kế) + khoảng cách còn lại
    private void refreshBannerForCurrentStep(int distToTurn) {
        String instr, man;
        if (currentStepIdx + 1 < navSteps.size()) {
            NavStep next = navSteps.get(currentStepIdx + 1);
            instr = stripHtml(next.instruction);
            man = next.maneuver;
        } else {
            instr = getString(R.string.nav_arriving);
            man = "DESTINATION";
        }
        binding.tvNavDistance.setText(formatDistance(distToTurn));
        binding.tvNavInstruction.setText(instr);
        binding.ivNavManeuver.setImageResource(maneuverIcon(man));
    }

    private void triggerReroute(LatLng from) {
        offRouteCount = 0;
        isNavigating = false; // tránh xử lý update trong lúc tìm tuyến mới
        if (locationCallback != null) fusedLocationClient.removeLocationUpdates(locationCallback);
        speak(getString(R.string.nav_rerouting));
        Toast.makeText(this, getString(R.string.nav_rerouting), Toast.LENGTH_SHORT).show();
        autoStartNavAfterReroute = true;
        originPoint = from;
        isReroute = true;
        fetchAndDrawRoute(from, destPoint);
    }

    private void stopNavigationMode() {
        isNavigating = false;
        autoStartNavAfterReroute = false;
        if (isLiveSharing) stopLiveSharing(); // hết di chuyển thì không còn gì để chia sẻ trực tiếp nữa
        if (locationCallback != null) fusedLocationClient.removeLocationUpdates(locationCallback);
        if (tts != null) tts.stop();
        binding.layoutNavBanner.setVisibility(View.GONE);
        binding.btnStopNav.setVisibility(View.GONE);
        binding.cardNavTripInfo.setVisibility(View.GONE);
        binding.cardSpeedometer.setVisibility(View.GONE);
        binding.tvNavGpsWarning.setVisibility(View.GONE);
        binding.layoutHeader.getRoot().setVisibility(View.VISIBLE);
        bottomSheetBehavior.setHideable(false);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        if (mMap != null) {
            CameraPosition cp = new CameraPosition.Builder()
                    .target(mMap.getCameraPosition().target).zoom(16f).tilt(0f).bearing(0f).build();
            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cp));
        }
        currentStep = 3;
    }

    private float distanceMeters(LatLng a, LatLng b) {
        float[] r = new float[1];
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r);
        return r[0];
    }

    // Khoảng cách ngắn nhất từ điểm tới polyline (point-to-segment)
    private float distanceToPath(LatLng p, List<LatLng> path) {
        if (path == null || path.isEmpty()) return Float.MAX_VALUE;
        if (path.size() == 1) return distanceMeters(p, path.get(0));
        double min = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i++) {
            double d = distToSegment(p, path.get(i), path.get(i + 1));
            if (d < min) min = d;
        }
        return (float) min;
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

    private double distToSegment(LatLng p, LatLng a, LatLng b) {
        double mPerDegLat = 111320.0;
        double mPerDegLng = 111320.0 * Math.cos(Math.toRadians(p.latitude));
        double px = p.longitude * mPerDegLng, py = p.latitude * mPerDegLat;
        double ax = a.longitude * mPerDegLng, ay = a.latitude * mPerDegLat;
        double bx = b.longitude * mPerDegLng, by = b.latitude * mPerDegLat;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = (len2 == 0) ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    private String formatDistance(int meters) {
        if (meters >= 1000) return String.format(Locale.US, "%.1f km", meters / 1000f);
        return getString(R.string.nav_meters, meters);
    }

    private String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").trim();
    }

    private int maneuverIcon(String m) {
        if (m == null) return R.drawable.ic_straight;
        String u = m.toUpperCase(Locale.US);
        if (u.contains("DESTINATION")) return R.drawable.ic_navigation;
        // Bắt "LEFT"/"RIGHT" bao trùm mọi biến thể của Routes API: TURN_LEFT, SLIGHT_LEFT, SHARP_LEFT,
        // RAMP_LEFT, FORK_LEFT, UTURN_LEFT, ROUNDABOUT_LEFT, MERGE_LEFT… và tương tự cho phải.
        if (u.contains("LEFT")) return R.drawable.ic_turn_left;
        if (u.contains("RIGHT")) return R.drawable.ic_turn_right;
        // STRAIGHT, DEPART, MERGE, NAME_CHANGE, ROUNDABOUT không rõ hướng… → mũi tên đi thẳng
        return R.drawable.ic_straight;
    }

    private void openRideService() {
        if (originPoint == null || destPoint == null) {
            Toast.makeText(this, getString(R.string.toast_no_route_for_ride), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, RideServiceActivity.class);
        intent.putExtra("origin_lat", originPoint.latitude);
        intent.putExtra("origin_lng", originPoint.longitude);
        intent.putExtra("dest_lat", destPoint.latitude);
        intent.putExtra("dest_lng", destPoint.longitude);
        intent.putExtra("dest_address", binding.tvAddress.getText().toString());
        startActivity(intent);
    }

    private void shareLocation(LatLng latLng, String address) {
        String googleMapsLink = "https://www.google.com/maps/search/?api=1&query=" + latLng.latitude + "," + latLng.longitude;
        String shareBody = getString(R.string.share_location_prefix) + "\n" + address
                + "\n\n" + getString(R.string.share_view_on_map) + "\n" + googleMapsLink;

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, shareBody);
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)));
    }

    // ===================== CHIA SẺ VỊ TRÍ TRỰC TIẾP (LIVE) =====================

    private void showShareOptions() {
        if (currentSelectedLatLng == null) {
            Toast.makeText(this, getString(R.string.toast_select_location_share), Toast.LENGTH_SHORT).show();
            return;
        }
        String liveLabel = isLiveSharing ? getString(R.string.share_live_stop) : getString(R.string.share_live_start);
        CharSequence[] options = {getString(R.string.share_static), liveLabel};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_dialog_title)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        shareLocation(currentSelectedLatLng, binding.tvAddress.getText().toString());
                    } else if (isLiveSharing) {
                        stopLiveSharing();
                    } else {
                        startLiveSharing();
                    }
                })
                .show();
    }

    private void startLiveSharing() {
        if (mAuth.getCurrentUser() == null || currentSelectedLatLng == null) return;
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("LiveShares").push();
        liveShareSessionId = ref.getKey();

        Map<String, Object> data = new HashMap<>();
        data.put("uid", mAuth.getCurrentUser().getUid());
        data.put("name", mAuth.getCurrentUser().getDisplayName());
        data.put("lat", currentSelectedLatLng.latitude);
        data.put("lng", currentSelectedLatLng.longitude);
        data.put("address", binding.tvAddress.getText().toString());
        data.put("active", true);
        data.put("updatedAt", System.currentTimeMillis());

        ref.setValue(data).addOnSuccessListener(v -> {
            isLiveSharing = true;
            Toast.makeText(this, getString(R.string.toast_live_share_started), Toast.LENGTH_SHORT).show();

            String link = "traffigo://live/" + liveShareSessionId;
            String body = getString(R.string.share_live_prefix) + "\n" + link;
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser)));
        });
    }

    private void stopLiveSharing() {
        if (liveShareSessionId == null) return;
        FirebaseDatabase.getInstance().getReference("LiveShares").child(liveShareSessionId)
                .child("active").setValue(false);
        isLiveSharing = false;
        liveShareSessionId = null;
        Toast.makeText(this, getString(R.string.toast_live_share_stopped), Toast.LENGTH_SHORT).show();
    }

    private void moveToLatLng(LatLng latLng) {
        if (mMap != null) mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f));
        updateSelection(latLng);
    }

    private void moveToLatLng(LatLng latLng, String address) {
        if (mMap != null) mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f));
        updateSelection(latLng, address);
    }

    @SuppressLint("MissingPermission")
    private void enableMyLocation() {
        if (mMap != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            mMap.getUiSettings().setMyLocationButtonEnabled(false);
        }
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String voiceAction = intent.getStringExtra("voice_action");
        boolean autoRoute = intent.getBooleanExtra("voice_auto_route", false);

        // Lệnh giọng nói thao tác trong màn hình hiện tại (start/stop/switch/book/share):
        // KHÔNG reset lộ trình đang có.
        if (voiceAction != null && !autoRoute) {
            handleVoiceExtras(intent);
            return;
        }

        resetNavigation();
        double jumpLat = intent.getDoubleExtra("jump_lat", 0);
        double jumpLng = intent.getDoubleExtra("jump_lng", 0);
        if (jumpLat != 0 && jumpLng != 0) {
            if (mMap != null) {
                moveToLatLng(new LatLng(jumpLat, jumpLng));
            } else {
                pendingJumpLat = jumpLat;
                pendingJumpLng = jumpLng;
            }
        }
        handleVoiceExtras(intent);
    }

    // ===================== NHẬN LỆNH TỪ TRỢ LÝ GIỌNG NÓI =====================

    private void handleVoiceExtras(Intent intent) {
        if (intent == null) return;
        String voiceAction = intent.getStringExtra("voice_action");
        boolean autoRoute = intent.getBooleanExtra("voice_auto_route", false);
        boolean presetOrigin = intent.getBooleanExtra("preset_origin", false);
        boolean replayRoute = intent.getBooleanExtra("replay_route", false);
        if (voiceAction == null && !autoRoute && !presetOrigin && !replayRoute) return;

        if (mMap == null) { pendingVoiceIntent = intent; return; }

        if (replayRoute) {
            double oLat = intent.getDoubleExtra("replay_origin_lat", 0);
            double oLng = intent.getDoubleExtra("replay_origin_lng", 0);
            double dLat = intent.getDoubleExtra("replay_dest_lat", 0);
            double dLng = intent.getDoubleExtra("replay_dest_lng", 0);
            String oAddr = intent.getStringExtra("replay_origin_address");
            String dAddr = intent.getStringExtra("replay_dest_address");
            intent.removeExtra("replay_route");
            if (oLat != 0 && dLat != 0) {
                replayRoute(new LatLng(oLat, oLng), new LatLng(dLat, dLng), oAddr, dAddr);
            }
            return;
        }

        if (autoRoute) {
            double lat = intent.getDoubleExtra("voice_dest_lat", 0);
            double lng = intent.getDoubleExtra("voice_dest_lng", 0);
            String addr = intent.getStringExtra("voice_dest_address");
            boolean autoStart = intent.getBooleanExtra("voice_auto_start", false);
            // tránh xử lý lại khi Activity tái tạo
            intent.removeExtra("voice_auto_route");
            if (lat != 0 && lng != 0) startVoiceNavigate(lat, lng, addr, autoStart);
            return;
        }

        if (presetOrigin) {
            double lat = intent.getDoubleExtra("preset_origin_lat", 0);
            double lng = intent.getDoubleExtra("preset_origin_lng", 0);
            String addr = intent.getStringExtra("preset_origin_address");
            intent.removeExtra("preset_origin");
            if (lat != 0 && lng != 0) applyPresetOrigin(new LatLng(lat, lng), addr);
            return;
        }

        intent.removeExtra("voice_action");
        switch (voiceAction) {
            case "start_nav":
                if (currentStep == 3) startNavigationMode();
                else Toast.makeText(this, getString(R.string.toast_no_route_to_start), Toast.LENGTH_SHORT).show();
                break;
            case "stop_nav":
                if (isNavigating) stopNavigationMode();
                break;
            case "switch_route":
                switchToNextRoute();
                break;
            case "book_ride":
                openRideService();
                break;
            case "share":
                if (currentSelectedLatLng != null) {
                    shareLocation(currentSelectedLatLng, binding.tvAddress.getText().toString());
                } else {
                    Toast.makeText(this, getString(R.string.toast_select_location_share), Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    @SuppressLint("MissingPermission")
    private void startVoiceNavigate(double lat, double lng, String address, boolean autoStart) {
        final LatLng dest = new LatLng(lat, lng);
        resetNavigation();
        currentStep = 2;
        destPoint = dest;
        currentSelectedLatLng = dest;
        if (endMarker != null) endMarker.remove();
        endMarker = mMap.addMarker(new MarkerOptions().position(dest)
                .title(getString(R.string.marker_destination))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE)));
        if (address != null && !address.isEmpty()) binding.tvAddress.setText(address);

        boolean hasPerm = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (hasPerm) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
                // loc==null: không lấy được vị trí thật, đành tạm dùng tâm bản đồ -> KHÔNG phải vị trí
                // hiện tại thật của người dùng, vẫn cần vẽ pin để họ biết điểm đi đang được đặt ở đâu.
                LatLng origin = (loc != null)
                        ? new LatLng(loc.getLatitude(), loc.getLongitude())
                        : mMap.getCameraPosition().target;
                beginVoiceRoute(origin, dest, autoStart, loc != null);
            }).addOnFailureListener(e -> beginVoiceRoute(mMap.getCameraPosition().target, dest, autoStart, false));
        } else {
            beginVoiceRoute(mMap.getCameraPosition().target, dest, autoStart, false);
        }
    }

    private void beginVoiceRoute(LatLng origin, LatLng dest, boolean autoStart, boolean isCurrentLocation) {
        originPoint = origin;
        lastOriginAddress = getString(R.string.marker_current_location);
        setOriginMarker(origin, isCurrentLocation);
        if (autoStart) autoStartNavAfterReroute = true;
        fetchAndDrawRoute(origin, dest);
    }

    // Đặt sẵn điểm đi (vd. từ thẻ "Về nhà"/"Công ty" trên Trang chủ, chọn "Đi từ đây"),
    // chuyển thẳng sang bước chọn điểm đến thay vì bắt chạm chọn lại trên bản đồ.
    private void applyPresetOrigin(LatLng origin, String address) {
        resetNavigation();
        lastOriginAddress = address;
        moveToLatLng(origin, address); // currentStep vẫn =1 lúc này nên set đúng làm originPoint
        currentStep = 2;
        binding.tvStepTitle.setText(R.string.nav_step_choose_dest);
        binding.btnMainAction.setText(R.string.nav_confirm_destination);
        binding.btnMainAction.setBackgroundTintList(getColorStateList(R.color.brand_primary));
        // resetNavigation() ẩn nút "Thêm điểm dừng" — flow này cũng đang ở bước 2 nên phải hiện lại
        // như handleFlow() làm, không thì multi-stop bị mất hết khi đi từ thẻ "Về nhà"/"Công ty".
        binding.btnAddStop.setVisibility(View.VISIBLE);
    }

    // ===================== LỊCH SỬ LỘ TRÌNH =====================

    private void saveRouteToHistory(LatLng origin, LatLng dest, String originAddress, String destAddress) {
        if (mAuth.getCurrentUser() == null) return;
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Users")
                .child(mAuth.getCurrentUser().getUid()).child("routeHistory").push();
        Map<String, Object> entry = new HashMap<>();
        entry.put("originAddress", originAddress);
        entry.put("originLat", origin.latitude);
        entry.put("originLng", origin.longitude);
        entry.put("destAddress", destAddress);
        entry.put("destLat", dest.latitude);
        entry.put("destLng", dest.longitude);
        entry.put("timestamp", System.currentTimeMillis());
        ref.setValue(entry);
    }

    /** Đi lại y hệt 1 tuyến trong Lịch sử: đặt sẵn cả điểm đi + điểm đến rồi tìm đường ngay, không cần chọn lại. */
    private void replayRoute(LatLng origin, LatLng dest, String originAddress, String destAddress) {
        resetNavigation();
        lastOriginAddress = originAddress;
        originPoint = origin;
        destPoint = dest;
        // Điểm đi ở đây là toạ độ CŨ đã lưu trong lịch sử, không phải vị trí hiện tại thật của người
        // dùng lúc này (họ có thể đang ở nơi khác) -> luôn vẽ pin, không bỏ qua như setOriginMarker
        // làm với vị trí GPS thật.
        setOriginMarker(origin, false);
        if (endMarker != null) endMarker.remove();
        endMarker = mMap.addMarker(new MarkerOptions().position(dest)
                .title(getString(R.string.marker_destination))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE)));
        binding.tvAddress.setText(destAddress != null ? destAddress : getString(R.string.nav_address_placeholder));
        isReroute = true; // đã có sẵn trong lịch sử, tránh lưu trùng lặp
        fetchAndDrawRoute(origin, dest);
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

    // ===================== SỰ CỐ CỘNG ĐỒNG TRÊN TUYẾN =====================

    /** Lắng nghe realtime "incidents", vẽ marker trên bản đồ dẫn đường (bỏ báo cáo >2h hoặc bị downvote). */
    private void listenForNavIncidents() {
        navIncidentsRef = FirebaseDatabase.getInstance().getReference("incidents");
        navIncidentsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (mMap == null || !isActive) return;
                for (Marker m : navIncidentMarkers.keySet()) m.remove();
                navIncidentMarkers.clear();
                activeIncidents.clear();
                long now = System.currentTimeMillis();
                for (DataSnapshot child : snapshot.getChildren()) {
                    TrafficIncident inc = child.getValue(TrafficIncident.class);
                    if (inc == null) continue;
                    inc.id = child.getKey();
                    if (now - inc.timestamp > INCIDENT_EXPIRY_MS) continue;
                    if (isIncidentDownvotedAway(inc)) continue;
                    activeIncidents.add(inc);
                    Marker m = mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(inc.lat, inc.lng))
                            .title(incidentLabel(inc.type))
                            .icon(BitmapDescriptorFactory.defaultMarker(incidentHue(inc.type)))
                            .zIndex(2f));
                    if (m != null) navIncidentMarkers.put(m, inc);
                }
                if (isNavigating) computeOnRouteIncidents();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* giữ marker hiện có */ }
        };
        navIncidentsRef.addValueEventListener(navIncidentsListener);
    }

    private boolean isIncidentDownvotedAway(TrafficIncident inc) {
        int down = inc.downvoteCount();
        return down >= INCIDENT_DOWNVOTE_HIDE && down > inc.upvoteCount();
    }

    /** Lọc ra các sự cố nằm ≤50m so với polyline tuyến hiện tại, kèm index gần nhất trên navPath. */
    private void computeOnRouteIncidents() {
        onRouteIncidents.clear();
        if (navPath == null || navPath.size() < 2) return;
        for (TrafficIncident inc : activeIncidents) {
            LatLng at = new LatLng(inc.lat, inc.lng);
            double best = Double.MAX_VALUE;
            int bestIdx = 0;
            for (int i = 0; i < navPath.size() - 1; i++) {
                double d = distToSegment(at, navPath.get(i), navPath.get(i + 1));
                if (d < best) { best = d; bestIdx = i; }
            }
            if (best <= INCIDENT_ON_ROUTE_M) onRouteIncidents.add(new RouteIncident(inc, bestIdx));
        }
    }

    /** Khi đang dẫn đường: nếu sắp tới (≤300m) một sự cố còn ở phía trước trên tuyến → cảnh báo + đọc TTS (1 lần/sự cố). */
    private void checkIncidentAhead(LatLng pos) {
        if (!isNavigating || onRouteIncidents.isEmpty()) return;
        for (RouteIncident ri : onRouteIncidents) {
            if (ri.inc.id == null || warnedIncidentIds.contains(ri.inc.id)) continue;
            if (ri.pathIdx < lastTrimIndex) continue; // đã đi qua điểm này rồi
            float d = distanceMeters(pos, new LatLng(ri.inc.lat, ri.inc.lng));
            if (d < INCIDENT_WARN_AHEAD_M) {
                warnedIncidentIds.add(ri.inc.id);
                String plain = incidentPlainLabel(ri.inc.type);
                speak(getString(R.string.nav_incident_ahead_tts, plain));
                Toast.makeText(this, getString(R.string.nav_incident_ahead, plain, Math.round(d)),
                        Toast.LENGTH_SHORT).show();
            }
        }
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

    /** Nhãn không kèm emoji, dùng cho TTS/Toast (emoji đọc lên nghe kỳ). */
    private String incidentPlainLabel(String type) {
        if (type == null) return "";
        switch (type) {
            case "jam": return getString(R.string.incident_label_jam);
            case "accident": return getString(R.string.incident_label_accident);
            case "flood": return getString(R.string.incident_label_flood);
            case "police": return getString(R.string.incident_label_police);
            case "hazard": return getString(R.string.incident_label_hazard);
            default: return "";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActive = false;
        if (navIncidentsRef != null && navIncidentsListener != null) {
            navIncidentsRef.removeEventListener(navIncidentsListener);
        }
        if (isLiveSharing && liveShareSessionId != null) {
            FirebaseDatabase.getInstance().getReference("LiveShares").child(liveShareSessionId)
                    .child("active").setValue(false);
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        trafficExecutor.shutdownNow();
    }

    // ===================== ROUTING - GOOGLE ROUTES API + CLUSTER COLORING =====================

    private void fetchAndDrawRoute(LatLng origin, LatLng dest) {
        fetchAndDrawRoute(origin, dest, null);
    }

    private void fetchAndDrawRoute(LatLng origin, LatLng dest, List<LatLng> intermediates) {
        String apiKey = getApiKeyFromManifest();
        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_api_key), Toast.LENGTH_SHORT).show();
            return;
        }
        // Chụp lại danh sách điểm dừng để dùng an toàn trong thread nền
        final List<LatLng> interSnapshot = (intermediates != null && !intermediates.isEmpty())
                ? new ArrayList<>(intermediates) : null;
        final String destAddressSnapshot = binding.tvAddress.getText().toString();
        final String originAddressSnapshot = (lastOriginAddress != null && !lastOriginAddress.isEmpty())
                ? lastOriginAddress
                : String.format(Locale.US, "%.5f, %.5f", origin.latitude, origin.longitude);
        // Chụp lại loại phương tiện + tuỳ chọn tránh phí cầu đường tại thời điểm bấm tìm đường, tránh
        // đổi giữa chừng (dù nút đã ẩn lúc đang fetch) ảnh hưởng tới request đang chạy trên thread nền.
        final String travelModeSnapshot = travelModeFor(vehicleType);
        final boolean avoidTollsSnapshot = avoidTolls && !"WALK".equals(travelModeSnapshot);

        binding.tvStepTitle.setText(R.string.nav_finding);
        binding.btnMainAction.setEnabled(false);
        binding.progressFindingRoute.setVisibility(View.VISIBLE);

        final int genAtStart = routeGeneration; // reset giữa chừng -> kết quả về muộn phải bị bỏ
        new Thread(() -> {
            try {
                URL url = new URL("https://routes.googleapis.com/directions/v2:computeRoutes");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("X-Goog-Api-Key", apiKey);
                conn.setRequestProperty("X-Goog-FieldMask",
                        "routes.polyline.encodedPolyline,routes.duration,routes.distanceMeters,"
                                + "routes.legs.steps.navigationInstruction,"
                                + "routes.legs.steps.distanceMeters,"
                                + "routes.legs.steps.endLocation.latLng");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                JSONObject body = buildRouteRequestJson(origin, dest, interSnapshot, travelModeSnapshot, avoidTollsSnapshot);
                java.io.OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                if (code == 200) {
                    JSONArray routes = new JSONObject(sb.toString()).optJSONArray("routes");
                    if (routes == null || routes.length() == 0) {
                        runOnUiThread(() -> {
                            if (!isActive) return;
                            Toast.makeText(this, getString(R.string.toast_no_route_found), Toast.LENGTH_SHORT).show();
                            binding.tvStepTitle.setText(R.string.nav_not_found_title);
                            binding.btnMainAction.setEnabled(true);
                            binding.progressFindingRoute.setVisibility(View.GONE);
                            notifyRerouteFailedIfPending();
                        });
                        return;
                    }

                    List<List<LatLng>> allPaths = new ArrayList<>();
                    List<Integer> durations = new ArrayList<>();
                    List<Integer> distances = new ArrayList<>();
                    List<List<NavStep>> allSteps = new ArrayList<>();
                    for (int i = 0; i < routes.length(); i++) {
                        JSONObject r = routes.getJSONObject(i);
                        allPaths.add(decodePoly(r.getJSONObject("polyline").getString("encodedPolyline")));
                        String durStr = r.optString("duration", "0s").replace("s", "");
                        try { durations.add(Integer.parseInt(durStr)); } catch (Exception e) { durations.add(0); }
                        distances.add(r.optInt("distanceMeters", 0));
                        allSteps.add(parseSteps(r.optJSONArray("legs")));
                    }

                    // Fetch TomTom (đồng bộ, đang ở background thread) cho tất cả route
                    // trước khi so sánh điểm số - scoreRoute cần congestion thật để chọn đúng.
                    if (segmentsLoaded) {
                        for (List<LatLng> p : allPaths) prefetchCongestionForPathSync(p);
                    }

                    int bestIdx = 0;
                    if (segmentsLoaded && allPaths.size() > 1) {
                        double bestAdjustedDuration = Double.MAX_VALUE;
                        for (int i = 0; i < allPaths.size(); i++) {
                            double adjusted = estimateAdjustedDurationSec(allPaths.get(i), durations.get(i));
                            if (adjusted < bestAdjustedDuration) { bestAdjustedDuration = adjusted; bestIdx = i; }
                        }
                    }

                    final int chosen = bestIdx;
                    final List<List<LatLng>> paths = allPaths;
                    final List<Integer> durs = durations;
                    final List<Integer> dists = distances;
                    final List<List<NavStep>> steps = allSteps;

                    runOnUiThread(() -> {
                        if (!isActive || mMap == null || genAtStart != routeGeneration) return;
                        for (Polyline p : routePolylines) p.remove();
                        routePolylines.clear();

                        for (int i = 0; i < paths.size(); i++) {
                            if (i != chosen) {
                                routePolylines.add(mMap.addPolyline(new PolylineOptions()
                                        .addAll(paths.get(i)).width(10f)
                                        .color(Color.argb(80, 158, 158, 158)).geodesic(true)));
                            }
                        }

                        drawColoredRoute(paths.get(chosen));

                        LatLngBounds.Builder bb = new LatLngBounds.Builder();
                        for (LatLng pt : paths.get(chosen)) bb.include(pt);
                        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bb.build(), 150));

                        int durMin = durs.get(chosen) / 60;
                        float distKm = dists.get(chosen) / 1000f;
                        String congText = getCongestionInfo(paths.get(chosen));
                        String altText = (paths.size() > 1 && chosen != 0)
                                ? getString(R.string.route_traffigo_pick)
                                : (paths.size() > 1 ? getString(R.string.route_alt_count, paths.size() - 1) : "");
                        updateRouteInfo(durMin, distKm, congText, altText);

                        // TraffiGo ML Server chấm điểm lại bằng mô hình đồ án (nếu server online)
                        requestMlRouteRecommendation();

                        savedPaths = paths;
                        savedDurations = durs;
                        savedDistances = dists;
                        savedSteps = steps;
                        currentChosenIdx = chosen;

                        currentStep = 3;
                        if (!isReroute) {
                            saveRouteToHistory(origin, dest, originAddressSnapshot, destAddressSnapshot);
                        }
                        isReroute = false;
                        binding.tvStepTitle.setText(R.string.nav_step_route);
                        binding.progressFindingRoute.setVisibility(View.GONE);
                        binding.btnMainAction.setVisibility(View.GONE);
                        binding.layoutVehicleType.setVisibility(View.GONE);
                        // Đã có tuyến (bước 3) - "Thêm điểm dừng" chỉ áp dụng lúc đang chọn điểm đến
                        // (bước 2), để hiện tiếp sẽ bấm được nhưng addWaypoint() chặn currentStep!=2 và
                        // báo Toast vô nghĩa ("chạm chọn 1 điểm trước") dù tuyến đã xong rồi.
                        binding.btnAddStop.setVisibility(View.GONE);
                        binding.btnReset.setVisibility(View.VISIBLE);
                        binding.btnStartNav.setVisibility(
                                steps.get(chosen).isEmpty() ? View.GONE : View.VISIBLE);
                        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

                        if (autoStartNavAfterReroute) {
                            autoStartNavAfterReroute = false;
                            startNavigationMode();
                        }
                    });

                } else {
                    String errMsg = "Lỗi " + code;
                    try {
                        JSONObject err = new JSONObject(sb.toString()).optJSONObject("error");
                        if (err != null) {
                            errMsg += ": " + err.optString("message", "");
                            if (code == 403) errMsg += "\n→ Kiểm tra Routes API đã bật + billing trong project mới";
                        }
                    } catch (Exception ignored) {}
                    Log.e("TraffiGo", "Routes API error " + code + ": " + sb);
                    final String msg = errMsg;
                    runOnUiThread(() -> {
                        if (!isActive) return;
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        binding.tvStepTitle.setText(R.string.nav_find_failed);
                        binding.btnMainAction.setEnabled(true);
                        binding.progressFindingRoute.setVisibility(View.GONE);
                        notifyRerouteFailedIfPending();
                    });
                }
            } catch (Exception e) {
                Log.e("TraffiGo", "Network error", e);
                runOnUiThread(() -> {
                    if (!isActive) return;
                    Toast.makeText(this, getString(R.string.toast_connection_error) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    binding.btnMainAction.setEnabled(true);
                    binding.progressFindingRoute.setVisibility(View.GONE);
                    notifyRerouteFailedIfPending();
                });
            }
        }).start();
    }

    /**
     * Khi đang dẫn đường mà auto-reroute tính lại đường thất bại (mất mạng, API lỗi…): trước đây
     * nav lặng lẽ dừng, người dùng không biết. Giờ báo rõ bằng giọng + toast để họ chủ động thử lại.
     */
    private void notifyRerouteFailedIfPending() {
        if (autoStartNavAfterReroute) {
            autoStartNavAfterReroute = false;
            speak(getString(R.string.nav_reroute_failed));
            Toast.makeText(this, getString(R.string.nav_reroute_failed), Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject buildRouteRequestJson(LatLng origin, LatLng dest, List<LatLng> intermediates,
                                              String travelMode, boolean avoidTolls) throws Exception {
        JSONObject originObj = new JSONObject().put("location",
                new JSONObject().put("latLng", latLngJson(origin)));
        JSONObject destObj = new JSONObject().put("location",
                new JSONObject().put("latLng", latLngJson(dest)));

        JSONObject body = new JSONObject();
        body.put("origin", originObj);
        body.put("destination", destObj);
        body.put("travelMode", travelMode);
        // Routes API bỏ qua computeAlternativeRoutes khi có intermediates (chỉ trả về 1 tuyến đi
        // qua đủ các điểm dừng theo đúng thứ tự) — logic chọn/vẽ tuyến bên dưới vẫn xử lý đúng
        // với paths.size()==1 nên không cần nhánh riêng.
        body.put("computeAlternativeRoutes", intermediates == null || intermediates.isEmpty());
        // routingPreference (TRAFFIC_AWARE) chỉ được Routes API hỗ trợ cho DRIVE/TWO_WHEELER - gửi kèm
        // field này với travelMode=WALK có thể bị API từ chối, nên bỏ hẳn khi đi bộ.
        if (!"WALK".equals(travelMode)) {
            body.put("routingPreference", "TRAFFIC_AWARE");
        }
        if (avoidTolls) {
            body.put("routeModifiers", new JSONObject().put("avoidTolls", true));
        }

        if (intermediates != null && !intermediates.isEmpty()) {
            JSONArray inter = new JSONArray();
            for (LatLng wp : intermediates) {
                inter.put(new JSONObject().put("location", new JSONObject().put("latLng", latLngJson(wp))));
            }
            body.put("intermediates", inter);
        }
        return body;
    }

    private JSONObject latLngJson(LatLng p) throws Exception {
        return new JSONObject().put("latitude", p.latitude).put("longitude", p.longitude);
    }

    // Parse các bước rẽ (turn-by-turn) từ legs[].steps[] của Routes API
    private List<NavStep> parseSteps(JSONArray legs) {
        List<NavStep> result = new ArrayList<>();
        if (legs == null) return result;
        for (int l = 0; l < legs.length(); l++) {
            JSONObject leg = legs.optJSONObject(l);
            if (leg == null) continue;
            JSONArray steps = leg.optJSONArray("steps");
            if (steps == null) continue;
            for (int s = 0; s < steps.length(); s++) {
                JSONObject st = steps.optJSONObject(s);
                if (st == null) continue;
                JSONObject nav = st.optJSONObject("navigationInstruction");
                String instr = nav != null ? nav.optString("instructions", "") : "";
                String man = nav != null ? nav.optString("maneuver", "") : "";
                int dist = st.optInt("distanceMeters", 0);
                LatLng end = null;
                JSONObject endLoc = st.optJSONObject("endLocation");
                if (endLoc != null) {
                    JSONObject ll = endLoc.optJSONObject("latLng");
                    if (ll != null) end = new LatLng(ll.optDouble("latitude"), ll.optDouble("longitude"));
                }
                if (end != null) result.add(new NavStep(instr, man, end, dist));
            }
        }
        return result;
    }

    // Preload hình học đường (tĩnh). Tốc độ/độ kẹt xe lấy live từ TomTom theo route,
    // xem prefetchCongestionForPath().
    private void preloadSegments() {
        new Thread(() -> {
            try {
                BufferedReader gr = new BufferedReader(new InputStreamReader(getAssets().open("geometry.csv")));
                gr.readLine();
                String line;
                while ((line = gr.readLine()) != null) {
                    String[] g = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                    if (g.length < 6) continue;
                    String name = g[0].trim();
                    String fullGeom = g[5].replace("\"", "");
                    TrafficSegment seg = new TrafficSegment(name,
                            Double.parseDouble(g[2]), Double.parseDouble(g[1]),
                            Double.parseDouble(g[4]), Double.parseDouble(g[3]),
                            35.0, 1.0, fullGeom);
                    // Cột 7/8/9 (maxspeed_kmh/oneway/highway_type) chỉ có ở geometry.csv bản mới
                    // (xuất từ OSM qua export_geometry_osm.py) — file cũ chỉ có 6 cột, bỏ qua an toàn.
                    if (g.length >= 9) {
                        String maxspeedStr = g[6].trim();
                        if (!maxspeedStr.isEmpty()) {
                            try { seg.speedLimitKmh = Integer.parseInt(maxspeedStr); } catch (NumberFormatException ignored) {}
                        }
                        seg.oneway = g[7].trim();
                        seg.highwayType = g[8].trim();
                    }
                    allSegments.add(seg);
                }
                gr.close();
                segmentsLoaded = true;
                Log.d("TraffiGo", "Preloaded " + allSegments.size() + " segments for cluster coloring");
            } catch (Exception e) {
                Log.e("TraffiGo", "Error preloading segments: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Lấy ~40 điểm sample dọc path, tìm segment gần nhất mỗi điểm, gọi TomTom song song
     * cho các segment đó (nếu chưa có trong cache liveCongestionByIndex), rồi chạy callback
     * trên UI thread khi xong. Callback luôn được gọi (kể cả khi không fetch được gì).
     */
    private void prefetchCongestionForPath(List<LatLng> path, Runnable onDone) {
        if (!segmentsLoaded || path.isEmpty()) {
            runOnUiThread(onDone);
            return;
        }
        trafficExecutor.execute(() -> {
            fetchCongestionBlocking(path);
            runOnUiThread(() -> { if (isActive) onDone.run(); });
        });
    }

    /** Phiên bản blocking - gọi khi đã đang chạy trên 1 background thread khác (không tạo thread UI callback). */
    private void prefetchCongestionForPathSync(List<LatLng> path) {
        if (!segmentsLoaded || path.isEmpty()) return;
        fetchCongestionBlocking(path);
    }

    /** Lấy segment gần các điểm sample trên path, gọi TomTom song song cho segment chưa có cache (hoặc đã quá TTL), chờ xong. */
    private void fetchCongestionBlocking(List<LatLng> path) {
        Set<Integer> indicesToFetch = new HashSet<>();
        int step = Math.max(1, path.size() / 40);
        for (int i = 0; i < path.size(); i += step) {
            int idx = findNearestSegmentIndex(path.get(i));
            if (idx != -1 && isLiveCongestionStale(idx)) indicesToFetch.add(idx);
        }
        if (indicesToFetch.isEmpty()) return;

        List<Thread> threads = new ArrayList<>();
        for (int idx : indicesToFetch) {
            Thread t = new Thread(() -> {
                TrafficSegment seg = allSegments.get(idx);
                TomTomTrafficClient.FlowResult result = TomTomTrafficClient.fetchFlowAveraged(
                        seg.lat1, seg.lon1, seg.lat2, seg.lon2);
                if (result != null) {
                    liveCongestionByIndex.put(idx, result.congestionIndex);
                    liveCongestionFetchedAt.put(idx, android.os.SystemClock.elapsedRealtime());
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
    }

    private boolean isLiveCongestionStale(int idx) {
        Long fetchedAt = liveCongestionFetchedAt.get(idx);
        return fetchedAt == null
                || android.os.SystemClock.elapsedRealtime() - fetchedAt > LIVE_CONGESTION_TTL_MS;
    }

    // Tô màu tuyến đường theo cluster congestion
    private void drawColoredRoute(List<LatLng> path) {
        if (path.isEmpty()) return;
        if (!segmentsLoaded) {
            routePolylines.add(mMap.addPolyline(new PolylineOptions()
                    .addAll(path).width(14f).color(Color.parseColor("#4285F4")).geodesic(true)));
            return;
        }
        List<LatLng> group = new ArrayList<>();
        group.add(path.get(0));
        int groupColor = getColorForPoint(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            int color = getColorForPoint(path.get(i));
            if (color != groupColor) {
                if (group.size() >= 2) {
                    routePolylines.add(mMap.addPolyline(new PolylineOptions()
                            .addAll(group).width(14f).color(groupColor).geodesic(true)));
                }
                group = new ArrayList<>();
                group.add(path.get(i - 1));
            }
            group.add(path.get(i));
            groupColor = color;
        }
        if (group.size() >= 2) {
            routePolylines.add(mMap.addPolyline(new PolylineOptions()
                    .addAll(group).width(14f).color(groupColor).geodesic(true)));
        }
    }

    private int getColorForPoint(LatLng point) {
        int idx = findNearestSegmentIndex(point);
        if (idx == -1) return Color.parseColor("#4285F4");
        double congestionIndex = liveCongestionByIndex.getOrDefault(idx, 1.0);
        if (congestionIndex < 0.7) return Color.RED;
        if (congestionIndex < 0.85) return Color.parseColor("#FF9800");
        return Color.parseColor("#4CAF50");
    }

    private int findNearestSegmentIndex(LatLng point) {
        Integer cached = nearestSegmentCache.get(point);
        if (cached != null) return cached;
        double minDist = 0.008; // ~800m trong độ lat/lon
        int nearestIdx = -1;
        for (int i = 0; i < allSegments.size(); i++) {
            TrafficSegment s = allSegments.get(i);
            double midLat = (s.lat1 + s.lat2) / 2;
            double midLon = (s.lon1 + s.lon2) / 2;
            double dist = Math.abs(point.latitude - midLat) + Math.abs(point.longitude - midLon);
            if (dist < minDist) { minDist = dist; nearestIdx = i; }
        }
        nearestSegmentCache.put(point, nearestIdx);
        return nearestIdx;
    }

    /**
     * Ước tính thời gian di chuyển thực tế (giây) = duration Google (đã tính traffic riêng của
     * Google) điều chỉnh theo hệ số trễ trung bình quan sát được từ TomTom dọc tuyến.
     *
     * Lý do đổi so với công thức cũ (điểm phạt tùy ý 3/1/0, sample cố định 40 điểm):
     * - Sample theo khoảng cách thực (mỗi ~300m/điểm) thay vì luôn đúng 40 điểm, nên route dài
     *   không bị "pha loãng" độ chi tiết so với route ngắn.
     * - Hệ số trễ = 1/congestionIndex (congestionIndex thấp = kẹt theo quy ước dự án -> hệ số > 1
     *   = đi chậm hơn dự kiến). Nhân với duration Google ra con số có đơn vị "giây thực tế dự
     *   kiến", so sánh được trực tiếp giữa các route khác độ dài, thay vì chỉ so được thứ tự hơn-kém.
     */
    private double estimateAdjustedDurationSec(List<LatLng> path, int googleDurationSec) {
        if (path.size() < 2) return googleDurationSec;

        final double SAMPLE_INTERVAL_KM = 0.3; // 1 điểm mẫu mỗi ~300m
        double totalWeightedDelay = 0.0;
        double totalLengthKm = 0.0;
        int sampled = 0;

        double accumulatedKm = 0.0;
        double nextSampleAtKm = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            double segLenKm = haversineKm(path.get(i).latitude, path.get(i).longitude,
                    path.get(i + 1).latitude, path.get(i + 1).longitude);
            totalLengthKm += segLenKm;

            while (accumulatedKm + segLenKm >= nextSampleAtKm) {
                int idx = findNearestSegmentIndex(path.get(i));
                double congestionIndex = idx != -1 ? liveCongestionByIndex.getOrDefault(idx, 1.0) : 1.0;
                // Hệ số trễ tại điểm mẫu: thông thoáng (>=0.85) -> ~1.0x, kẹt nặng (~0.3) -> ~3.3x
                double delayFactor = 1.0 / Math.max(0.3, congestionIndex);
                totalWeightedDelay += delayFactor;
                sampled++;
                nextSampleAtKm += SAMPLE_INTERVAL_KM;
            }
            accumulatedKm += segLenKm;
        }

        if (sampled == 0 || totalLengthKm <= 0) return googleDurationSec;

        double avgDelayFactor = totalWeightedDelay / sampled;
        return googleDurationSec * avgDelayFactor;
    }

    private String getCongestionInfo(List<LatLng> path) {
        if (!segmentsLoaded || path.isEmpty()) return "";
        // F2: lấy mẫu dọc tuyến -> RouteQualityAnalyzer để vừa suy ra câu trạng thái (như cũ)
        // vừa chấm điểm sức khỏe tuyến A–F. Điểm chỉ tính trên mẫu có dữ liệu thật (loại no-data).
        RouteQualityAnalyzer analyzer = new RouteQualityAnalyzer();
        int step = Math.max(1, path.size() / 40);
        for (int i = 0; i < path.size(); i += step) {
            int idx = findNearestSegmentIndex(path.get(i));
            if (idx != -1) {
                analyzer.addCongestionIndex(liveCongestionByIndex.getOrDefault(idx, 1.0));
            } else {
                analyzer.add(RouteQualityAnalyzer.Level.NO_DATA);
            }
        }
        if (analyzer.totalSamples() == 0) return "";
        RouteQualityAnalyzer.Result q = analyzer.build();

        // Ngưỡng câu trạng thái giữ nguyên như bản cũ (heavyPct/modPct tính trên cùng mẫu số).
        int delay = (q.heavyPct * 3 + q.moderatePct) / 10;
        String base;
        if (q.heavyPct > 5) base = getString(R.string.cong_heavy, delay);
        else if (q.moderatePct > 15) base = getString(R.string.cong_moderate);
        else base = getString(R.string.cong_clear);

        if (q.hasGrade()) base += getString(R.string.route_quality_suffix, String.valueOf(q.grade));
        return base;
    }

    private void updateRouteInfo(int durMin, float distKm, String congText, String altText) {
        binding.layoutRouteInfo.setVisibility(View.VISIBLE);
        binding.tvRouteDuration.setText(getString(R.string.nav_minutes, durMin));
        binding.tvRouteDistance.setText(String.format("%.1f km", distKm));
        binding.tvRouteCongestion.setText(congText);
        binding.tvRouteAlternative.setText(altText);
        updateRouteCostEstimate(distKm);
    }

    /**
     * Ước tính chi phí nhiên liệu = quãng đường x mức tiêu hao trung bình x giá xăng. Dùng hằng số cố
     * định (FUEL_CONSUMPTION_L_PER_100KM/FUEL_PRICE_VND_PER_LITER), CHƯA cho người dùng tự nhập mức
     * tiêu hao xe thật của họ - giữ đơn giản cho bản đầu, luôn ghi rõ "ước tính" để không hiểu nhầm là
     * số chính xác. Đi bộ không tốn nhiên liệu -> ẩn hẳn dòng này.
     */
    private void updateRouteCostEstimate(float distKm) {
        Double consumption = FUEL_CONSUMPTION_L_PER_100KM.get(vehicleType);
        if (consumption == null) {
            binding.tvRouteCost.setVisibility(View.GONE);
            return;
        }
        long costVnd = Math.round(distKm / 100.0 * consumption * FUEL_PRICE_VND_PER_LITER);
        // Locale("vi","VN") -> dấu chấm phân cách hàng nghìn ("21.000"), đúng quy ước VN thay vì dấu
        // phẩy kiểu Mỹ ("21,000") mà Locale.US sẽ cho ra.
        binding.tvRouteCost.setText(getString(R.string.nav_route_cost_estimate,
                String.format(new Locale("vi", "VN"), "%,d", costVnd)));
        binding.tvRouteCost.setVisibility(View.VISIBLE);
    }

    private List<LatLng> decodePoly(String encoded) {
        List<LatLng> poly = new ArrayList<>();
        int index = 0, len = encoded.length(), lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            shift = 0; result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            poly.add(new LatLng((lat / 1E5), (lng / 1E5)));
        }
        return poly;
    }
}