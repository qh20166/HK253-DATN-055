package com.example.traffigo;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.example.traffigo.activities.VoiceAssistantActivity;

/**
 * Gắn nút mic trợ lý giọng nói ở MỌI màn hình tại một chỗ duy nhất:
 * bất kỳ activity nào có view id `btnVoiceMic` sẽ tự được nối sự kiện mở VoiceAssistantActivity.
 */
public class TraffiGoApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Mặc định tiếng Việt cho lần chạy đầu (kể cả khi máy để tiếng Anh);
        // người dùng vẫn có thể tự đổi sang English trong phần tùy chỉnh.
        if (AppCompatDelegate.getApplicationLocales().isEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("vi"));
        }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity activity) {
                if (activity instanceof VoiceAssistantActivity) return;
                View mic = activity.findViewById(R.id.btnVoiceMic);
                if (mic != null) {
                    mic.setOnClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        Intent i = new Intent(activity, VoiceAssistantActivity.class);
                        activity.startActivity(i);
                        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    });
                }
            }

            @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
        });
    }
}
