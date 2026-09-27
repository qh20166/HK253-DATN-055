package com.example.traffigo.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.example.traffigo.R;
import com.example.traffigo.adapters.OnboardingAdapter;
import com.example.traffigo.models.OnboardingItem;
import com.tbuonomo.viewpagerdotsindicator.DotsIndicator;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 onboardingViewPager;
    private Button btnNext;
    private TextView btnSignIn, tvSkip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        // Ánh xạ View
        onboardingViewPager = findViewById(R.id.viewPager);
        btnNext = findViewById(R.id.btnNext);
        btnSignIn = findViewById(R.id.btnSignIn);
        tvSkip = findViewById(R.id.tvSkip); // Nếu bạn có nút Skip ở góc trên

        DotsIndicator dotsIndicator = findViewById(R.id.dots_indicator);

        // Chuẩn bị dữ liệu
        List<OnboardingItem> onboardingItems = new ArrayList<>();
        onboardingItems.add(new OnboardingItem(
                R.drawable.onboarding_route,
                getString(R.string.onboard_title_1),
                getString(R.string.onboard_desc_1)
        ));
        onboardingItems.add(new OnboardingItem(
                R.drawable.onboarding_traffic,
                getString(R.string.onboard_title_2),
                getString(R.string.onboard_desc_2)
        ));
        onboardingItems.add(new OnboardingItem(
                R.drawable.onboarding_journey,
                getString(R.string.onboard_title_3),
                getString(R.string.onboard_desc_3)
        ));

        // Cài đặt Adapter
        OnboardingAdapter adapter = new OnboardingAdapter(onboardingItems);
        onboardingViewPager.setAdapter(adapter);

        // Kết nối DotsIndicator với ViewPager2 (Chỉ dùng cái này, bỏ TabLayoutMediator)
        dotsIndicator.attachTo(onboardingViewPager);

        // Xử lý nút Continue (Next)
        btnNext.setOnClickListener(v -> {
            int currentItem = onboardingViewPager.getCurrentItem();
            if (currentItem < adapter.getItemCount() - 1) {
                onboardingViewPager.setCurrentItem(currentItem + 1);
            } else {
                navigateToLogin();
            }
        });

        // Xử lý nút Sign In
        btnSignIn.setOnClickListener(v -> navigateToLogin());

        // Nút Skip đang hiển thị nhưng trước đây không gắn listener -> bấm không có tác dụng
        tvSkip.setOnClickListener(v -> navigateToLogin());

        // Thay đổi text nút khi đến trang cuối (Tùy chọn)
        onboardingViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position == adapter.getItemCount() - 1) {
                    btnNext.setText(R.string.onboard_get_started);
                } else {
                    btnNext.setText(R.string.onboard_continue);
                }
            }
        });
    }

    private void navigateToLogin() {
        Intent intent = new Intent(OnboardingActivity.this, LoginActivity.class);
        startActivity(intent);
        finish(); // Đóng luôn màn hình Onboarding
    }
}