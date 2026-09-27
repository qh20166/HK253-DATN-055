package com.example.traffigo.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.traffigo.R;
import com.example.traffigo.adapters.HeroCarouselAdapter;
import com.example.traffigo.databinding.ActivityMainBinding;
import com.example.traffigo.utils.AppearanceHelper;
import com.example.traffigo.utils.LocaleHelper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String PKG_GRAB = "com.grabtaxi.passenger";
    private static final String PKG_BE = "xyz.be.customer";
    private static final String PKG_XANHSM = "com.gsm.customer";

    private ActivityMainBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private HeroCarouselAdapter heroAdapter;
    private ActivityResultLauncher<Intent> addFavoriteLauncher;

    private Double homeLat, homeLng;
    private Double workLat, workLng;
    private String homeAddress, workAddress;

    // ===== Đỗ xe ở đây =====
    private ActivityResultLauncher<String> parkingPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Làm Status Bar trong suốt
        makeStatusBarTransparent();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Pre-warm Maps GL renderer trước khi user mở map activity
        MapsInitializer.initialize(getApplicationContext());

        // Đánh thức ML server (Render free ngủ sau 15 phút): bắn /health nền,
        // server tỉnh sau ~30-50s trước khi user kịp bấm vào bản đồ dùng ML
        com.example.traffigo.utils.MlServerClient.wakeUp(this);

        // 2. Khởi tạo Firebase
        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        // 3. Áp dụng giao diện và hiệu ứng
        setupHeroCarousel();
        applyCustomAppearance();
        updateUI(currentUser);
        setupListeners();
        setupBottomNav();
        loadSavedPlaces();
        setupAddFavoriteLauncher();
        loadFavoritePlaces();

        parkingPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) saveCurrentLocationAsParking();
                    else Toast.makeText(this, getString(R.string.parking_permission_needed), Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Làm mới tên + avatar khi quay lại (vd. sau khi đổi trong Profile) mà không cần restart app
        if (currentUser != null) {
            currentUser.reload().addOnCompleteListener(t -> {
                FirebaseUser refreshed = mAuth.getCurrentUser();
                if (refreshed != null) {
                    currentUser = refreshed;
                    updateUI(currentUser);
                }
            });
        }
        // Bottom nav chỉ là lối tắt (mở Activity riêng), không phải tab thường trú -> luôn về lại "Trang chủ"
        if (binding.bottomNav.getSelectedItemId() != R.id.navHome) {
            binding.bottomNav.setSelectedItemId(R.id.navHome);
        }
    }

    private void makeStatusBarTransparent() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    private void updateUI(FirebaseUser user) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greeting = (hour < 12) ? getString(R.string.greeting_morning)
                : (hour < 18) ? getString(R.string.greeting_afternoon)
                : getString(R.string.greeting_evening);
        binding.tvGreeting.setText(greeting);

        // getEmail() có thể null (vd. đăng nhập chỉ bằng SĐT) -> tránh NPE khi cả displayName lẫn email đều rỗng
        String displayName = user.getDisplayName();
        String email = user.getEmail();
        String name;
        if (displayName != null && !displayName.isEmpty()) {
            name = displayName;
        } else if (email != null && email.contains("@")) {
            name = email.split("@")[0];
        } else {
            name = "";
        }
        binding.tvUserName.setText(name);

        // Avatar: load ảnh (Base64 trong Realtime DB) vào nút tài khoản.
        // Không reset về ic_user ở đây để tránh "chớp" ảnh mặc định khi refresh (đã có cache).
        if (binding.btnAccount != null) {
            com.example.traffigo.utils.AvatarUtils.loadInto(user.getUid(), binding.btnAccount);
        }
    }

    private void setupListeners() {
        // Avatar mở thẳng hồ sơ cá nhân (không qua bottom sheet trung gian nữa)
        binding.btnAccount.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, ProfileActivity.class)); });
        binding.btnSearch.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, SearchPlaceActivity.class)); });
        binding.btnNotifications.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, NotificationActivity.class)); });

        // Tiện ích nhanh
        binding.btnQuickVoice.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, VoiceAssistantActivity.class)); });
        binding.btnQuickHeatmap.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, TrafficMapActivity.class)); });
        binding.btnQuickOffline.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, OfflineMapsActivity.class)); });
        binding.btnQuickNearby.setOnClickListener(v -> { haptic(v); startActivity(new Intent(this, NearbyPlacesActivity.class)); });
        binding.btnQuickParking.setOnClickListener(v -> { haptic(v); onQuickParkingClick(); });

        // Gọi xe nhanh
        binding.btnRideGrab.setOnClickListener(v -> { haptic(v); openRideApp(PKG_GRAB); });
        binding.btnRideBe.setOnClickListener(v -> { haptic(v); openRideApp(PKG_BE); });
        binding.btnRideXanhSm.setOnClickListener(v -> { haptic(v); openRideApp(PKG_XANHSM); });
    }

    /** Rung phản hồi nhẹ khi chạm (tôn trọng cài đặt haptic của hệ thống). */
    private void haptic(View v) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ===== Carousel hero: Chỉ đường / Về nhà / Đến công ty =====

    private void setupHeroCarousel() {
        List<HeroCarouselAdapter.HeroItem> items = new ArrayList<>();
        items.add(new HeroCarouselAdapter.HeroItem(
                R.drawable.ic_location, getString(R.string.card_nav_title), getString(R.string.card_nav_subtitle),
                () -> startActivity(new Intent(this, NavigationActivity.class))));
        items.add(new HeroCarouselAdapter.HeroItem(
                R.drawable.ic_home, getString(R.string.hero_home_title), getString(R.string.hero_home_subtitle_empty),
                () -> openSavedPlace("home"), R.drawable.bg_hero_blue));
        items.add(new HeroCarouselAdapter.HeroItem(
                R.drawable.ic_office, getString(R.string.hero_work_title), getString(R.string.hero_work_subtitle_empty),
                () -> openSavedPlace("work"), R.drawable.bg_hero_orange));

        int heroRes = AppearanceHelper.getCustomBackground(this, R.drawable.bg_hero_purple);
        heroAdapter = new HeroCarouselAdapter(items, heroRes);
        binding.heroPager.setAdapter(heroAdapter);
        binding.heroDots.setViewPager2(binding.heroPager);

        // Hiệu ứng chuyển trang: thẻ bên cạnh thu nhỏ + mờ nhiều, chỉ hé lộ một góc nhỏ
        // (kiểu "zoom out" chuẩn của Google) thay vì hiện rõ chữ khi đang vuốt dở.
        final float minScale = 0.85f;
        final float minAlpha = 0.45f;
        binding.heroPager.setPageTransformer((page, position) -> {
            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();
            if (position < -1 || position > 1) {
                page.setAlpha(0f);
            } else {
                float scaleFactor = Math.max(minScale, 1 - Math.abs(position));
                float vertMargin = pageHeight * (1 - scaleFactor) / 2;
                float horzMargin = pageWidth * (1 - scaleFactor) / 2;
                page.setTranslationX(position < 0 ? horzMargin - vertMargin / 2 : -horzMargin + vertMargin / 2);
                page.setScaleX(scaleFactor);
                page.setScaleY(scaleFactor);
                page.setAlpha(minAlpha + (scaleFactor - minScale) / (1 - minScale) * (1 - minAlpha));
            }
        });
    }

    /** Đọc địa chỉ Nhà/Công ty đã lưu (Users/{uid}/savedPlaces) để cập nhật tiêu đề phụ carousel. */
    private void loadSavedPlaces() {
        if (currentUser == null) return;
        FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid()).child("savedPlaces")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        applySavedPlace(snapshot.child("home"), 1, true);
                        applySavedPlace(snapshot.child("work"), 2, false);
                        heroAdapter.refresh();
                    }

                    @Override
                    public void onCancelled(DatabaseError error) { /* giữ subtitle mặc định */ }
                });
    }

    private void applySavedPlace(DataSnapshot placeSnap, int heroIndex, boolean isHome) {
        if (!placeSnap.exists()) return;
        String address = placeSnap.child("address").getValue(String.class);
        Double lat = placeSnap.child("lat").getValue(Double.class);
        Double lng = placeSnap.child("lng").getValue(Double.class);
        if (isHome) { homeLat = lat; homeLng = lng; homeAddress = address; }
        else { workLat = lat; workLng = lng; workAddress = address; }
        if (address != null && !address.isEmpty()) {
            heroAdapter.getItem(heroIndex).subtitle = address;
        }
    }

    /** Đã có địa chỉ lưu sẵn -> hỏi "Đi từ đây" hay "Đi đến đây"; chưa có -> mở màn hình chỉ đường để lưu. */
    private void openSavedPlace(String type) {
        boolean isHome = "home".equals(type);
        Double lat = isHome ? homeLat : workLat;
        Double lng = isHome ? homeLng : workLng;
        String address = isHome ? homeAddress : workAddress;

        if (lat == null || lng == null) {
            startActivity(new Intent(this, NavigationActivity.class));
            return;
        }
        String title = isHome ? getString(R.string.hero_home_title) : getString(R.string.hero_work_title);
        int iconRes = isHome ? R.drawable.ic_home : R.drawable.ic_office;
        showGoFromToDialog(title, iconRes, lat, lng, address);
    }

    /** Dialog "Đi từ đây / Đi đến đây / Xem trên bản đồ" — dùng chung cho Nhà, Công ty và Địa điểm yêu thích. */
    private void showGoFromToDialog(String title, int iconRes, double lat, double lng, String address) {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_hero_choice, null);
        dialog.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvChoiceTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvChoiceSubtitle);
        ImageView ivIcon = view.findViewById(R.id.ivChoiceIcon);
        tvTitle.setText(title);
        tvSubtitle.setText(address != null ? address : "");
        ivIcon.setImageResource(iconRes);

        view.findViewById(R.id.btnGoFrom).setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, NavigationActivity.class);
            intent.putExtra("preset_origin", true);
            intent.putExtra("preset_origin_lat", lat);
            intent.putExtra("preset_origin_lng", lng);
            intent.putExtra("preset_origin_address", address);
            startActivity(intent);
        });

        view.findViewById(R.id.btnGoTo).setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, NavigationActivity.class);
            intent.putExtra("voice_auto_route", true);
            intent.putExtra("voice_dest_lat", lat);
            intent.putExtra("voice_dest_lng", lng);
            intent.putExtra("voice_dest_address", address);
            startActivity(intent);
        });

        view.findViewById(R.id.btnViewOnMap).setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, NavigationActivity.class);
            intent.putExtra("jump_lat", lat);
            intent.putExtra("jump_lng", lng);
            startActivity(intent);
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundResource(android.R.color.transparent);
        }
        dialog.show();
    }

    // ===== Địa điểm yêu thích: thêm/xem/xoá địa điểm tự đặt tên =====

    private static class FavoritePlace {
        final String key, name, address;
        final double lat, lng;

        FavoritePlace(String key, String name, String address, double lat, double lng) {
            this.key = key;
            this.name = name;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private void setupAddFavoriteLauncher() {
        addFavoriteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        double lat = data.getDoubleExtra(SearchPlaceActivity.EXTRA_LAT, Double.NaN);
                        double lng = data.getDoubleExtra(SearchPlaceActivity.EXTRA_LNG, Double.NaN);
                        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                            String address = data.getStringExtra(SearchPlaceActivity.EXTRA_ADDRESS);
                            showNameFavoriteDialog(lat, lng, address);
                        }
                    }
                });
    }

    private void loadFavoritePlaces() {
        if (currentUser == null) return;
        FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid()).child("favoritePlaces")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        List<FavoritePlace> places = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            String name = child.child("name").getValue(String.class);
                            String address = child.child("address").getValue(String.class);
                            Double lat = child.child("lat").getValue(Double.class);
                            Double lng = child.child("lng").getValue(Double.class);
                            if (name == null || lat == null || lng == null) continue;
                            places.add(new FavoritePlace(child.getKey(), name, address, lat, lng));
                        }
                        renderFavorites(places);
                    }

                    @Override
                    public void onCancelled(DatabaseError error) { /* giữ danh sách rỗng */ }
                });
    }

    private void renderFavorites(List<FavoritePlace> places) {
        binding.favoritesContainer.removeAllViews();
        for (FavoritePlace place : places) {
            View chip = getLayoutInflater().inflate(R.layout.item_favorite_place, binding.favoritesContainer, false);
            TextView tvName = chip.findViewById(R.id.tvFavoriteName);
            tvName.setText(place.name);
            chip.setOnClickListener(v ->
                    showGoFromToDialog(place.name, R.drawable.ic_place, place.lat, place.lng, place.address));
            chip.setOnLongClickListener(v -> {
                confirmDeleteFavorite(place.key, place.name);
                return true;
            });
            binding.favoritesContainer.addView(chip);
        }

        // Chip "+" thêm địa điểm mới, luôn ở cuối
        View addChip = getLayoutInflater().inflate(R.layout.item_favorite_place, binding.favoritesContainer, false);
        ImageView ivAddIcon = addChip.findViewById(R.id.ivFavoriteIcon);
        TextView tvAddName = addChip.findViewById(R.id.tvFavoriteName);
        ivAddIcon.setImageResource(R.drawable.ic_add);
        tvAddName.setText(R.string.favorites_add);
        addChip.setOnClickListener(v -> addFavoriteLauncher.launch(new Intent(this, SearchPlaceActivity.class)));
        binding.favoritesContainer.addView(addChip);
    }

    private void showNameFavoriteDialog(double lat, double lng, String address) {
        EditText input = new EditText(this);
        input.setHint(getString(R.string.favorites_name_hint));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.favorites_dialog_title)
                .setMessage(address)
                .setView(input)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, getString(R.string.toast_name_required), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveFavoritePlace(name, address, lat, lng);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void saveFavoritePlace(String name, String address, double lat, double lng) {
        if (currentUser == null) return;
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid()).child("favoritePlaces").push();
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("address", address);
        data.put("lat", lat);
        data.put("lng", lng);
        ref.setValue(data).addOnSuccessListener(v -> loadFavoritePlaces());
    }

    private void confirmDeleteFavorite(String key, String name) {
        if (currentUser == null || key == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.favorites_delete_title)
                .setMessage(getString(R.string.favorites_delete_msg, name))
                .setPositiveButton(R.string.btn_delete, (d, w) ->
                        FirebaseDatabase.getInstance().getReference("Users")
                                .child(currentUser.getUid()).child("favoritePlaces").child(key)
                                .removeValue().addOnSuccessListener(v -> loadFavoritePlaces()))
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ===== Gọi xe nhanh: mở app đã cài, chưa có thì đưa ra CH Play =====

    private void openRideApp(String packageName) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(packageName);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        Toast.makeText(this, getString(R.string.toast_app_not_installed), Toast.LENGTH_SHORT).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
        }
    }

    // ===== Bottom nav: lối tắt tới các màn hình chính (không phải tab thường trú) =====

    private void setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.navHome) {
                return true;
            } else if (id == R.id.navDirections) {
                startActivity(new Intent(this, NavigationActivity.class));
            } else if (id == R.id.navHistory) {
                startActivity(new Intent(this, RouteHistoryActivity.class));
            } else if (id == R.id.navNews) {
                startActivity(new Intent(this, TrafficNewsActivity.class));
            } else if (id == R.id.navSettings) {
                showCustomizeBottomSheet();
            }
            return true;
        });
    }

    // Suy ra màu nút mic từ gradient hero đang chọn, để đồng bộ accent trên toàn màn hình
    private int fabColorForHero(int heroRes) {
        if (heroRes == R.drawable.bg_hero_blue) return Color.parseColor("#3D7CE0");
        if (heroRes == R.drawable.bg_hero_green) return Color.parseColor("#34B37E");
        if (heroRes == R.drawable.bg_hero_orange) return Color.parseColor("#F5824A");
        return Color.parseColor("#5C4FE0"); // Tím (mặc định)
    }

    // Áp dụng một màu chủ đạo (accent): đổi gradient thẻ hero + màu nút mic
    private void applyThemePreset(int heroRes) {
        AppearanceHelper.saveCustomBackground(this, heroRes);
        applyCustomAppearance();
    }

    private void applyCustomAppearance() {
        // Carousel hero: gradient cả 3 thẻ theo màu chủ đạo đã chọn
        int heroRes = AppearanceHelper.getCustomBackground(this, R.drawable.bg_hero_purple);
        if (heroAdapter != null) heroAdapter.setBackgroundRes(heroRes);

        // Màu chữ (áp dụng cho lời chào + tên)
        String savedTextColorHex = AppearanceHelper.getCustomTextColor(this, "#201B3D");
        int textColor = Color.parseColor(savedTextColorHex);
        int subTextColor = Color.argb(150, Color.red(textColor), Color.green(textColor), Color.blue(textColor));
        binding.tvGreeting.setTextColor(subTextColor);
        binding.tvUserName.setTextColor(textColor);

        // Cỡ chữ
        float fontScale = AppearanceHelper.getFontScale(this);
        binding.tvGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * fontScale);
        binding.tvUserName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * fontScale);
    }

    private void showCustomizeBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialogTheme);
        View sheetView = getLayoutInflater().inflate(R.layout.layout_bottom_sheet_customize, null);
        dialog.setContentView(sheetView);

        sheetView.findViewById(R.id.btnLogoutFromSettings).setOnClickListener(v -> {
            dialog.dismiss();
            performLogout();
        });

        // Màu chủ đạo (accent): đổi gradient thẻ hero + màu nút mic
        sheetView.findViewById(R.id.btnAccentPurple).setOnClickListener(v ->
                applyThemePreset(R.drawable.bg_hero_purple));
        sheetView.findViewById(R.id.btnAccentBlue).setOnClickListener(v ->
                applyThemePreset(R.drawable.bg_hero_blue));
        sheetView.findViewById(R.id.btnAccentGreen).setOnClickListener(v ->
                applyThemePreset(R.drawable.bg_hero_green));
        sheetView.findViewById(R.id.btnAccentOrange).setOnClickListener(v ->
                applyThemePreset(R.drawable.bg_hero_orange));

        // Màu chữ (lời chào + tên người dùng)
        sheetView.findViewById(R.id.btnTextColorNavy).setOnClickListener(v -> {
            AppearanceHelper.saveCustomTextColor(this, "#201B3D");
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnTextColorBlack).setOnClickListener(v -> {
            AppearanceHelper.saveCustomTextColor(this, "#000000");
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnTextColorPurple).setOnClickListener(v -> {
            AppearanceHelper.saveCustomTextColor(this, "#4B36A8");
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnTextColorTeal).setOnClickListener(v -> {
            AppearanceHelper.saveCustomTextColor(this, "#0F766E");
            applyCustomAppearance();
        });

        // Cỡ chữ
        sheetView.findViewById(R.id.btnFontSmall).setOnClickListener(v -> {
            AppearanceHelper.saveFontScale(this, 0.85f);
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnFontNormal).setOnClickListener(v -> {
            AppearanceHelper.saveFontScale(this, 1.0f);
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnFontLarge).setOnClickListener(v -> {
            AppearanceHelper.saveFontScale(this, 1.2f);
            applyCustomAppearance();
        });

        // Bo góc thẻ
        sheetView.findViewById(R.id.btnCornerSharp).setOnClickListener(v -> {
            AppearanceHelper.saveCornerRadius(this, 12f);
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnCornerRound).setOnClickListener(v -> {
            AppearanceHelper.saveCornerRadius(this, 32f);
            applyCustomAppearance();
        });
        sheetView.findViewById(R.id.btnCornerPill).setOnClickListener(v -> {
            AppearanceHelper.saveCornerRadius(this, 56f);
            applyCustomAppearance();
        });

        // Ngôn ngữ
        MaterialButton btnLangVi = sheetView.findViewById(R.id.btnLangVi);
        MaterialButton btnLangEn = sheetView.findViewById(R.id.btnLangEn);
        highlightLanguageButtons(btnLangVi, btnLangEn);
        btnLangVi.setOnClickListener(v -> LocaleHelper.setLanguage(LocaleHelper.VI));
        btnLangEn.setOnClickListener(v -> LocaleHelper.setLanguage(LocaleHelper.EN));

        if (dialog.getWindow() != null) {
            dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundResource(android.R.color.transparent);
        }
        // Mở rộng hết cỡ để nội dung dài (đủ tới phần Ngôn ngữ) không bị che, cuộn được
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> b =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
                b.setSkipCollapsed(true);
                b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        dialog.show();
    }

    private void highlightLanguageButtons(MaterialButton btnVi, MaterialButton btnEn) {
        boolean isVi = LocaleHelper.VI.equals(LocaleHelper.getLanguage());
        int purple = Color.parseColor("#5C4FE0");
        int white = Color.WHITE;
        int transparent = Color.TRANSPARENT;

        btnVi.setBackgroundTintList(ColorStateList.valueOf(isVi ? purple : transparent));
        btnVi.setTextColor(isVi ? white : purple);
        btnVi.setStrokeColor(ColorStateList.valueOf(purple));
        btnVi.setStrokeWidth(isVi ? 0 : 3);

        btnEn.setBackgroundTintList(ColorStateList.valueOf(!isVi ? purple : transparent));
        btnEn.setTextColor(!isVi ? white : purple);
        btnEn.setStrokeColor(ColorStateList.valueOf(purple));
        btnEn.setStrokeWidth(!isVi ? 0 : 3);
    }

    private void performLogout() {
        com.example.traffigo.utils.AvatarUtils.clearCache();
        mAuth.signOut();
        Intent intent = new Intent(this, OnboardingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ===== Đỗ xe ở đây: lưu vị trí hiện tại làm điểm đỗ xe, dẫn đường lại khi cần =====
    // Lưu tại Users/{uid}/parkingSpot (1 slot duy nhất, giống savedPlaces/home) chứ không phải
    // danh sách — mỗi lần lưu mới sẽ ghi đè điểm cũ.

    private void onQuickParkingClick() {
        if (currentUser == null) {
            Toast.makeText(this, getString(R.string.toast_login_required), Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid()).child("parkingSpot")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        Double lat = snapshot.child("lat").getValue(Double.class);
                        Double lng = snapshot.child("lng").getValue(Double.class);
                        Long ts = snapshot.child("timestamp").getValue(Long.class);
                        if (lat != null && lng != null) {
                            showParkingBottomSheet(lat, lng, snapshot.child("address").getValue(String.class),
                                    ts != null ? ts : 0L);
                        } else {
                            requestSaveParking();
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        requestSaveParking();
                    }
                });
    }

    private void requestSaveParking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            saveCurrentLocationAsParking();
        } else {
            parkingPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    @SuppressLint("MissingPermission")
    private void saveCurrentLocationAsParking() {
        Toast.makeText(this, getString(R.string.parking_locating), Toast.LENGTH_SHORT).show();
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(this);
        // Lấy fix GPS mới, chính xác cao (getLastLocation dễ null/lệch -> tìm sai chỗ xe).
        // Nếu không có fix mới thì fallback vị trí biết gần nhất.
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        geocodeAndPersistParking(loc.getLatitude(), loc.getLongitude());
                    } else {
                        client.getLastLocation().addOnSuccessListener(last -> {
                            if (last != null) geocodeAndPersistParking(last.getLatitude(), last.getLongitude());
                            else Toast.makeText(this, getString(R.string.parking_save_failed), Toast.LENGTH_SHORT).show();
                        }).addOnFailureListener(e ->
                                Toast.makeText(this, getString(R.string.parking_save_failed), Toast.LENGTH_SHORT).show());
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.parking_save_failed), Toast.LENGTH_SHORT).show());
    }

    /** Reverse-geocode ở luồng nền rồi lưu điểm đỗ (Geocoder chặn luồng nên không chạy trên main). */
    private void geocodeAndPersistParking(double lat, double lng) {
        new Thread(() -> {
            String address = String.format(Locale.US, "%.5f, %.5f", lat, lng);
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty() && addresses.get(0).getAddressLine(0) != null) {
                    address = addresses.get(0).getAddressLine(0);
                }
            } catch (Exception ignored) {
            }
            final String finalAddress = address;
            runOnUiThread(() -> persistParkingSpot(lat, lng, finalAddress));
        }).start();
    }

    private void persistParkingSpot(double lat, double lng, String address) {
        if (currentUser == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("lat", lat);
        data.put("lng", lng);
        data.put("address", address);
        data.put("timestamp", System.currentTimeMillis());
        FirebaseDatabase.getInstance().getReference("Users").child(currentUser.getUid())
                .child("parkingSpot").setValue(data)
                .addOnSuccessListener(v ->
                        Toast.makeText(this, getString(R.string.parking_saved), Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.parking_save_failed), Toast.LENGTH_SHORT).show());
    }

    /** Bottom sheet tái dùng layout_dialog_saved_place (giống dialog Nhà/Công ty ở NavigationActivity). */
    @SuppressLint("MissingPermission")
    private void showParkingBottomSheet(double lat, double lng, String address, long timestamp) {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.layout_dialog_saved_place, null);
        dialog.setContentView(view);

        ((ImageView) view.findViewById(R.id.ivPlaceIcon)).setImageResource(R.drawable.ic_parking);
        ((TextView) view.findViewById(R.id.tvDialogTitle)).setText(R.string.parking_title);
        ((TextView) view.findViewById(R.id.tvDialogAddress)).setText(address != null ? address : "");

        // Nhãn phụ: "Đã đỗ lúc HH:mm"; nếu lấy được vị trí hiện tại thì thêm "· cách bạn ~X".
        TextView tvLabel = view.findViewById(R.id.tvDialogLabel);
        String parkedTime = formatParkedTime(timestamp);
        tvLabel.setText(parkedTime != null
                ? getString(R.string.parking_parked_at, parkedTime)
                : getString(R.string.parking_label));
        appendDistanceToCar(tvLabel, parkedTime, lat, lng);

        MaterialButton btnPrimary = view.findViewById(R.id.btnPrimary);
        btnPrimary.setText(R.string.parking_navigate);
        btnPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, NavigationActivity.class);
            intent.putExtra("voice_dest_lat", lat);
            intent.putExtra("voice_dest_lng", lng);
            intent.putExtra("voice_dest_address", address);
            intent.putExtra("voice_auto_route", true);
            startActivity(intent);
        });

        MaterialButton btnSecondary = view.findViewById(R.id.btnSecondary);
        btnSecondary.setText(R.string.parking_clear);
        btnSecondary.setOnClickListener(v -> {
            dialog.dismiss();
            clearParkingSpot();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void clearParkingSpot() {
        if (currentUser == null) return;
        FirebaseDatabase.getInstance().getReference("Users").child(currentUser.getUid())
                .child("parkingSpot").removeValue()
                .addOnSuccessListener(v ->
                        Toast.makeText(this, getString(R.string.parking_removed), Toast.LENGTH_SHORT).show());
    }

    /** "HH:mm" nếu đỗ hôm nay, kèm "dd/MM" nếu khác ngày. Trả null khi không có timestamp. */
    private String formatParkedTime(long timestamp) {
        if (timestamp <= 0) return null;
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(timestamp);
        boolean sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        String pattern = sameDay ? "HH:mm" : "HH:mm dd/MM";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(timestamp));
    }

    /** Nếu có quyền vị trí, lấy vị trí hiện tại và thêm "· cách bạn ~X" vào nhãn giờ đỗ (bất đồng bộ). */
    @SuppressLint("MissingPermission")
    private void appendDistanceToCar(TextView tvLabel, String parkedTime, double carLat, double carLng) {
        if (parkedTime == null) return; // không có mốc giờ thì nhãn đang là "Vị trí xe", không chèn khoảng cách
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        LocationServices.getFusedLocationProviderClient(this).getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc == null) return;
                    float[] result = new float[1];
                    Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), carLat, carLng, result);
                    String dist = result[0] < 1000
                            ? Math.round(result[0]) + " m"
                            : String.format(Locale.US, "%.1f km", result[0] / 1000f);
                    // Ghép bằng dấu cách trong code: khoảng trắng đầu chuỗi trong strings.xml bị Android trim.
                    tvLabel.setText(getString(R.string.parking_parked_at, parkedTime)
                            + " " + getString(R.string.parking_distance_suffix, dist));
                });
    }
}