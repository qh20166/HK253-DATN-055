package com.example.traffigo.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;

/**
 * Trang thông báo (cảnh báo kẹt xe, cập nhật tuyến đường...).
 * Hiện chưa có nguồn dữ liệu thông báo thật nên chỉ hiển thị trạng thái rỗng,
 * sẵn cấu trúc để gắn dữ liệu thật sau này.
 */
public class NotificationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification);

        View header = findViewById(R.id.layoutHeader);
        TextView tvTitle = header.findViewById(R.id.tvPageTitle);
        ImageButton btnBack = header.findViewById(R.id.btnBack);
        View btnShareContainer = header.findViewById(R.id.btnShareContainer);
        View btnVoiceMicContainer = header.findViewById(R.id.btnVoiceMicContainer);

        tvTitle.setText(R.string.notifications_title);
        btnBack.setOnClickListener(v -> finish());
        if (btnShareContainer != null) btnShareContainer.setVisibility(View.GONE);
        if (btnVoiceMicContainer != null) btnVoiceMicContainer.setVisibility(View.GONE);
    }
}
