package com.example.traffigo.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.traffigo.R;
import com.example.traffigo.adapters.PlacePredictionAdapter;
import com.example.traffigo.databinding.ActivitySearchPlaceBinding;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompleteSessionToken;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Màn hình tìm địa điểm tùy biến (thay giao diện Autocomplete mặc định của Google).
 * Trả kết quả về qua Intent extras: tên, địa chỉ, lat, lng.
 */
public class SearchPlaceActivity extends AppCompatActivity {

    public static final String EXTRA_NAME = "extra_place_name";
    public static final String EXTRA_ADDRESS = "extra_place_address";
    public static final String EXTRA_LAT = "extra_place_lat";
    public static final String EXTRA_LNG = "extra_place_lng";

    private ActivitySearchPlaceBinding binding;
    private PlacesClient placesClient;
    private AutocompleteSessionToken sessionToken;
    private PlacePredictionAdapter adapter;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchPlaceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ensurePlacesInitialized();
        placesClient = Places.createClient(this);
        sessionToken = AutocompleteSessionToken.newInstance();

        adapter = new PlacePredictionAdapter(prediction -> fetchAndReturn(prediction.getPlaceId()));
        binding.rvPredictions.setLayoutManager(new LinearLayoutManager(this));
        binding.rvPredictions.setAdapter(adapter);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnClear.setOnClickListener(v -> binding.etSearch.setText(""));

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim();
                binding.btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                scheduleSearch(query);
            }
        });

        binding.etSearch.requestFocus();
        updateEmptyState();
    }

    private void ensurePlacesInitialized() {
        if (Places.isInitialized()) return;
        try {
            ApplicationInfo ai = getPackageManager()
                    .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String key = ai.metaData != null ? ai.metaData.getString("com.google.android.geo.API_KEY") : null;
            if (key != null && !key.isEmpty()) {
                Places.initialize(getApplicationContext(), key);
            }
        } catch (Exception ignored) {
        }
    }

    private void scheduleSearch(String query) {
        if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
        if (query.isEmpty()) {
            adapter.setPredictions(new ArrayList<>());
            updateEmptyState();
            return;
        }
        pendingSearch = () -> runSearch(query);
        debounceHandler.postDelayed(pendingSearch, 250);
    }

    private void runSearch(String query) {
        FindAutocompletePredictionsRequest request = FindAutocompletePredictionsRequest.builder()
                .setCountries("VN")
                .setSessionToken(sessionToken)
                .setQuery(query)
                .build();

        placesClient.findAutocompletePredictions(request)
                .addOnSuccessListener(response -> {
                    adapter.setPredictions(response.getAutocompletePredictions());
                    updateEmptyState();
                })
                .addOnFailureListener(e -> {
                    adapter.setPredictions(new ArrayList<>());
                    updateEmptyState();
                });
    }

    private void fetchAndReturn(String placeId) {
        List<Place.Field> fields = Arrays.asList(
                Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS);
        FetchPlaceRequest request = FetchPlaceRequest.builder(placeId, fields)
                .setSessionToken(sessionToken)
                .build();

        placesClient.fetchPlace(request)
                .addOnSuccessListener(response -> {
                    Place place = response.getPlace();
                    LatLng latLng = place.getLatLng();
                    if (latLng == null) {
                        finish();
                        return;
                    }
                    Intent data = new Intent();
                    data.putExtra(EXTRA_NAME, place.getName());
                    data.putExtra(EXTRA_ADDRESS, place.getAddress());
                    data.putExtra(EXTRA_LAT, latLng.latitude);
                    data.putExtra(EXTRA_LNG, latLng.longitude);
                    setResult(Activity.RESULT_OK, data);
                    finish();
                })
                .addOnFailureListener(e -> finish());
    }

    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0;
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvPredictions.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
        super.onDestroy();
    }
}
