package com.example.traffigo.models;

import org.json.JSONObject;

/** Kết quả hiểu ý từ Gemini: một hành động + tham số. */
public class VoiceIntent {
    public final String action;      // vd "navigate", "start_navigation", "clarify", "unknown"
    public final JSONObject args;    // tham số kèm theo (có thể rỗng)

    public VoiceIntent(String action, JSONObject args) {
        this.action = action;
        this.args = (args != null) ? args : new JSONObject();
    }

    public String getString(String key, String def) {
        return args.optString(key, def);
    }

    public boolean getBool(String key, boolean def) {
        return args.optBoolean(key, def);
    }
}
