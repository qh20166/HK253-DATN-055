package com.example.traffigo.utils;

import com.example.traffigo.models.VoiceIntent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Gọi Gemini (Generative Language API) bằng REST + function calling để biến câu nói
 * tiếng Việt thành một VoiceIntent {action, args}. Chạy đồng bộ — gọi trên background thread.
 */
public class GeminiClient {

    // gemini-flash-latest: nhanh, đủ cho NLU, nằm trong free tier
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";

    private static final String SYSTEM_PROMPT =
            "Bạn là trợ lý giọng nói của app điều hướng giao thông TraffiGo (tiếng Việt, khu vực TP.HCM). "
            + "Người dùng ra lệnh bằng giọng nói khi đang di chuyển. "
            + "Hãy ánh xạ câu nói thành ĐÚNG MỘT function call phù hợp nhất. "
            + "Với lệnh đi tới một nơi cụ thể, dùng navigate và đặt destination là tên/địa chỉ địa điểm (KHÔNG tự bịa tọa độ). "
            + "Với 'về nhà'/'tới công ty/chỗ làm' dùng navigate_saved. "
            + "Với 'đọc báo'/'đọc tin tức cho tôi nghe' (muốn NGHE bằng giọng nói) dùng read_news; chỉ dùng "
            + "open_news khi người dùng muốn XEM danh sách tin (không nói tới việc đọc/nghe). "
            + "Nếu câu quá mơ hồ hoặc thiếu địa điểm, dùng clarify và đặt question là câu hỏi lại ngắn gọn. "
            + "Nếu không khớp lệnh nào, dùng unknown.";

    public static VoiceIntent interpret(String apiKey, String userText) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("MISSING_KEY");
        }

        JSONObject body = buildRequest(userText);

        URL url = new URL(ENDPOINT + "?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        OutputStream os = conn.getOutputStream();
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

        if (code != 200) {
            throw new RuntimeException("Gemini HTTP " + code + ": " + sb);
        }

        return parseResponse(sb.toString());
    }

    private static JSONObject buildRequest(String userText) throws Exception {
        JSONObject sysPart = new JSONObject().put("text", SYSTEM_PROMPT);
        JSONObject systemInstruction = new JSONObject()
                .put("parts", new JSONArray().put(sysPart));

        JSONObject userPart = new JSONObject().put("text", userText);
        JSONObject content = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(userPart));

        JSONObject tools = new JSONObject()
                .put("function_declarations", buildFunctionDeclarations());

        JSONObject toolConfig = new JSONObject().put("function_calling_config",
                new JSONObject().put("mode", "ANY")); // luôn buộc trả về 1 function call

        return new JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", new JSONArray().put(content))
                .put("tools", new JSONArray().put(tools))
                .put("tool_config", toolConfig);
    }

    private static JSONArray buildFunctionDeclarations() throws Exception {
        JSONArray fns = new JSONArray();

        fns.put(fn("navigate", "Tìm đường và dẫn đường tới một địa điểm người dùng nói",
                obj(
                        prop("destination", "string", "Tên hoặc địa chỉ đích, ví dụ 'Chợ Bến Thành', 'Landmark 81'"),
                        prop("avoid_traffic", "boolean", "true nếu người dùng muốn tránh kẹt xe")
                ), new String[]{"destination"}));

        fns.put(fn("navigate_saved", "Dẫn đường tới địa điểm đã lưu (nhà hoặc công ty)",
                obj(propEnum("place", "Nơi đã lưu", new String[]{"home", "work"})),
                new String[]{"place"}));

        fns.put(fn("start_navigation", "Bắt đầu chế độ dẫn đường turn-by-turn cho lộ trình đang hiện", obj(), null));
        fns.put(fn("stop_navigation", "Dừng/hủy dẫn đường", obj(), null));
        fns.put(fn("switch_route", "Chuyển sang tuyến đường thay thế khác", obj(), null));

        fns.put(fn("check_traffic", "Xem tình trạng kẹt xe của một con đường trên bản đồ giao thông",
                obj(prop("road", "string", "Tên đường cần kiểm tra, ví dụ 'Điện Biên Phủ'")),
                null));

        fns.put(fn("open_traffic_map", "Mở bản đồ giao thông tổng quát", obj(), null));
        fns.put(fn("open_news", "Mở màn hình danh sách tin tức (chỉ hiện danh sách, KHÔNG đọc to)", obj(), null));

        fns.put(fn("read_news", "Đọc to bài báo mới nhất bằng giọng nói — dùng khi người dùng nói kiểu "
                        + "'đọc báo cho tôi nghe', 'đọc tin tức', 'nghe tin giao thông', KHÁC với open_news "
                        + "(open_news chỉ mở danh sách, không tự đọc)",
                obj(propEnum("category", "Danh mục tin muốn nghe. traffic=giao thông, current=thời sự, "
                        + "business=kinh doanh, sports=thể thao, all=tất cả/mặc định khi người dùng không "
                        + "nói rõ danh mục nào (ví dụ chỉ nói 'đọc báo')",
                        new String[]{"traffic", "current", "business", "sports", "all"})),
                null));
        fns.put(fn("book_ride", "Mở đặt dịch vụ gọi xe cho lộ trình hiện tại", obj(), null));
        fns.put(fn("share_location", "Chia sẻ vị trí hiện tại", obj(), null));
        fns.put(fn("open_offline_maps", "Mở/tải bản đồ ngoại tuyến", obj(), null));

        fns.put(fn("clarify", "Hỏi lại khi câu lệnh mơ hồ hoặc thiếu thông tin",
                obj(prop("question", "string", "Câu hỏi lại ngắn gọn cho người dùng")),
                new String[]{"question"}));

        fns.put(fn("unknown", "Không khớp lệnh nào trong app", obj(), null));

        return fns;
    }

    // ---- helpers dựng schema ----

    private static JSONObject fn(String name, String desc, JSONObject properties, String[] required) throws Exception {
        JSONObject params = new JSONObject()
                .put("type", "object")
                .put("properties", properties);
        if (required != null && required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            params.put("required", req);
        }
        return new JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", params);
    }

    private static JSONObject obj(JSONObject... props) throws Exception {
        JSONObject o = new JSONObject();
        for (JSONObject p : props) {
            String key = p.keys().next();
            o.put(key, p.get(key));
        }
        return o;
    }

    private static JSONObject prop(String name, String type, String desc) throws Exception {
        return new JSONObject().put(name,
                new JSONObject().put("type", type).put("description", desc));
    }

    private static JSONObject propEnum(String name, String desc, String[] values) throws Exception {
        JSONArray en = new JSONArray();
        for (String v : values) en.put(v);
        return new JSONObject().put(name,
                new JSONObject().put("type", "string").put("description", desc).put("enum", en));
    }

    private static VoiceIntent parseResponse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return new VoiceIntent("unknown", null);
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null) return new VoiceIntent("unknown", null);
            JSONArray parts = content.optJSONArray("parts");
            if (parts == null) return new VoiceIntent("unknown", null);
            for (int i = 0; i < parts.length(); i++) {
                JSONObject fc = parts.getJSONObject(i).optJSONObject("functionCall");
                if (fc != null) {
                    String name = fc.optString("name", "unknown");
                    JSONObject args = fc.optJSONObject("args");
                    return new VoiceIntent(name, args);
                }
            }
            return new VoiceIntent("unknown", null);
        } catch (Exception e) {
            return new VoiceIntent("unknown", null);
        }
    }
}
