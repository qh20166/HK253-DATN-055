package com.example.traffigo.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {

    private static final String PREF_NAME = "navigation_app";
    private static final String KEY_ONBOARDING = "onboarding_seen";

    private SharedPreferences sharedPreferences;

    public PreferenceManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setOnboardingSeen() {
        sharedPreferences.edit().putBoolean(KEY_ONBOARDING, true).apply();
    }

    public boolean isOnboardingSeen() {
        return sharedPreferences.getBoolean(KEY_ONBOARDING, false);
    }
}