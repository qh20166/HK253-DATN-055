package com.example.traffigo.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.traffigo.R;
import com.example.traffigo.models.NewsItem;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TrafficNewsActivity extends AppCompatActivity {

    private RecyclerView rvNews;
    private View layoutEmpty;
    private NewsAdapter adapter;
    private final List<NewsItem> newsList = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(5); // 5 nguồn tải song song

    // Mỗi danh mục ứng với 1 URL RSS riêng của từng báo (không phải 1 feed tổng có filter) —
    // đã verify từng URL trả HTTP 200 thật (không đoán) trước khi đưa vào đây.
    private static final Map<String, String[]> CATEGORY_RSS_URLS = new LinkedHashMap<>();
    static {
        CATEGORY_RSS_URLS.put("traffic", new String[]{
                "https://vnexpress.net/rss/giao-thong.rss",
                "https://thanhnien.vn/rss/xe/xe-giao-thong.rss",
                "https://tuoitre.vn/rss/xe.rss",
                "https://dantri.com.vn/rss/o-to-xe-may.rss",
                "https://vietnamnet.vn/rss/oto-xe-may.rss",
        });
        CATEGORY_RSS_URLS.put("current", new String[]{
                "https://vnexpress.net/rss/thoi-su.rss",
                "https://thanhnien.vn/rss/thoi-su.rss",
                "https://tuoitre.vn/rss/thoi-su.rss",
                "https://dantri.com.vn/rss/thoi-su.rss",
                "https://vietnamnet.vn/thoi-su.rss",
        });
        CATEGORY_RSS_URLS.put("business", new String[]{
                "https://vnexpress.net/rss/kinh-doanh.rss",
                "https://thanhnien.vn/rss/kinh-te.rss",
                "https://tuoitre.vn/rss/kinh-doanh.rss",
                "https://dantri.com.vn/rss/kinh-doanh.rss",
                "https://vietnamnet.vn/kinh-doanh.rss",
        });
        CATEGORY_RSS_URLS.put("sports", new String[]{
                "https://vnexpress.net/rss/the-thao.rss",
                "https://thanhnien.vn/rss/the-thao.rss",
                "https://tuoitre.vn/rss/the-thao.rss",
                "https://dantri.com.vn/rss/the-thao.rss",
                "https://vietnamnet.vn/rss/the-thao.rss",
        });
        CATEGORY_RSS_URLS.put("all", new String[]{
                "https://vnexpress.net/rss/tin-moi-nhat.rss",
                "https://thanhnien.vn/rss/home.rss",
                "https://tuoitre.vn/home.rss",
                "https://dantri.com.vn/rss/home.rss",
                "https://vietnamnet.vn/home.rss",
        });
    }
    private static final String[] CATEGORY_ORDER = {"traffic", "current", "business", "sports", "all"};
    private static final String DEFAULT_CATEGORY = "traffic";

    private String currentCategory = DEFAULT_CATEGORY;
    private Chip[] categoryChips;
    // Tăng mỗi lần đổi danh mục; các callback fetch cũ (nếu về muộn sau khi đã đổi danh mục khác)
    // tự nhận ra mình lỗi thời và bỏ qua, tránh lẫn kết quả 2 danh mục vào cùng 1 danh sách.
    private int fetchGeneration = 0;
    private int pendingSources = 0;
    // Trợ lý giọng nói mở màn này kèm lệnh "đọc báo" (VoiceAssistantActivity -> voice_auto_read=true)
    // -> tự mở bài ĐẦU TIÊN của danh mục (mặc định "all" nếu không nói rõ danh mục nào) và tự đọc
    // luôn, không cần người dùng bấm gì thêm. Cờ này bị tiêu thụ đúng 1 lần ngay khi có kết quả.
    private boolean pendingVoiceAutoRead = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_traffic_news);

        setupHeader();

        rvNews = findViewById(R.id.rvNews);
        layoutEmpty = findViewById(R.id.layoutNewsEmpty);
        rvNews.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NewsAdapter(newsList);
        rvNews.setAdapter(adapter);

        setupCategoryChips();

        boolean voiceAutoRead = getIntent().getBooleanExtra("voice_auto_read", false);
        String startCategory = DEFAULT_CATEGORY;
        if (voiceAutoRead) {
            pendingVoiceAutoRead = true;
            String requested = getIntent().getStringExtra("voice_category");
            startCategory = (requested != null && CATEGORY_RSS_URLS.containsKey(requested)) ? requested : "all";
        }
        currentCategory = startCategory;
        updateChipVisuals();
        fetchNewsForCategory(startCategory);
    }

    private void setupHeader() {
        TextView tvTitle = findViewById(R.id.tvPageTitle);
        if (tvTitle != null) tvTitle.setText(R.string.news_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // Header chỉ có 2 chỗ icon cố định bên phải (mic luôn hiện + 1 slot share dự trữ để giữ tựa
        // đề luôn canh giữa) — thêm hẳn 1 icon thứ 3 làm tựa đề dài ("TIN TỨC TỔNG HỢP") bị đè lên
        // icon. Tin tức không cần nút share nên tận dụng lại đúng slot đó làm nút cài đặt đọc báo,
        // không đụng tới layout_header.xml dùng chung cho các màn khác.
        ImageView btnShare = findViewById(R.id.btnShare);
        View btnShareContainer = findViewById(R.id.btnShareContainer);
        if (btnShare != null && btnShareContainer != null) {
            btnShare.setImageResource(R.drawable.ic_settings);
            btnShare.setContentDescription(getString(R.string.cd_news_settings));
            btnShare.setVisibility(View.VISIBLE);
            btnShareContainer.setVisibility(View.VISIBLE);
            // btnShare (ImageButton) tự nó đã clickable=true và nằm ĐÈ LÊN container nên nuốt luôn cú
            // chạm trước khi tới container — phải gắn listener lên chính nó, gắn ở container là vô ích.
            btnShare.setOnClickListener(v -> showNewsSettingsBottomSheet());
        }
    }

    // ===================== CÀI ĐẶT ĐỌC BÁO (tốc độ + giọng) =====================
    // Đọc thật sự diễn ra ở NewsDetailActivity — lưu lựa chọn vào SharedPreferences chung
    // ("NewsPrefs") để NewsDetailActivity đọc lại mỗi lần khởi tạo TextToSpeech, theo đúng
    // cách NavigationActivity đã lưu ngưỡng tốc độ (NavPrefs) — không cần 1 class wrapper riêng.
    private static final String NEWS_PREFS = "NewsPrefs";
    private static final String KEY_TTS_RATE = "tts_rate";

    // Không có lựa chọn giọng nam/nữ: đã kiểm tra thực tế qua tts.getVoices() trên máy — giọng vi-VN
    // của Google TTS chỉ có 1 giọng gắn nhãn giới tính rõ ràng ("vif" = nữ), không có giọng nam tương
    // ứng; giọng en-US/en-GB cũng không mã hoá giới tính theo tên. Bỏ hẳn thay vì để nút bấm vô tác dụng.
    private void showNewsSettingsBottomSheet() {
        SharedPreferences prefs = getSharedPreferences(NEWS_PREFS, MODE_PRIVATE);

        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.layout_bottom_sheet_news_settings, null);
        dialog.setContentView(view);

        final MaterialButton[] speedButtons = {
                view.findViewById(R.id.btnSpeed075),
                view.findViewById(R.id.btnSpeed100),
                view.findViewById(R.id.btnSpeed125),
                view.findViewById(R.id.btnSpeed150),
        };
        final float[] speedValues = {0.75f, 1.0f, 1.25f, 1.5f};
        float savedRate = prefs.getFloat(KEY_TTS_RATE, 1.0f);
        for (int i = 0; i < speedButtons.length; i++) {
            highlightPillButton(speedButtons[i], Math.abs(speedValues[i] - savedRate) < 0.01f);
        }
        for (int i = 0; i < speedButtons.length; i++) {
            float value = speedValues[i];
            speedButtons[i].setOnClickListener(v -> {
                prefs.edit().putFloat(KEY_TTS_RATE, value).apply();
                for (int j = 0; j < speedButtons.length; j++) {
                    highlightPillButton(speedButtons[j], Math.abs(speedValues[j] - value) < 0.01f);
                }
            });
        }

        dialog.show();
    }

    /**
     * Đổi màu nút pill giữa 2 trạng thái chọn/chưa chọn. Dùng nền tím nhạt #F0EEFF cho trạng thái
     * chưa chọn (giống hệt kiểu chip danh mục ở updateChipVisuals()) thay vì nền trong suốt + viền —
     * tổ hợp transparent+stroke trên MaterialButton style mặc định (không phải OutlinedButton) render
     * sai (nền vẫn giữ màu tím cũ trong khi chữ đã đổi màu, chữ tím chìm trong nền tím không đọc được).
     */
    private void highlightPillButton(MaterialButton button, boolean selected) {
        if (selected) {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#5C4FE0")));
            button.setTextColor(Color.parseColor("#FFFFFF"));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F0EEFF")));
            button.setTextColor(Color.parseColor("#5C4FE0"));
        }
    }

    // ===================== DANH MỤC =====================

    private void setupCategoryChips() {
        categoryChips = new Chip[]{
                findViewById(R.id.chipCatTraffic),
                findViewById(R.id.chipCatCurrent),
                findViewById(R.id.chipCatBusiness),
                findViewById(R.id.chipCatSports),
                findViewById(R.id.chipCatAll),
        };
        for (int idx = 0; idx < categoryChips.length; idx++) {
            String key = CATEGORY_ORDER[idx];
            categoryChips[idx].setOnClickListener(v -> selectCategory(key));
        }
        updateChipVisuals();
    }

    private void selectCategory(String key) {
        if (key.equals(currentCategory)) return;
        currentCategory = key;
        updateChipVisuals();
        fetchNewsForCategory(key);
    }

    private void updateChipVisuals() {
        for (int idx = 0; idx < categoryChips.length; idx++) {
            boolean active = CATEGORY_ORDER[idx].equals(currentCategory);
            categoryChips[idx].setChipBackgroundColor(
                    ColorStateList.valueOf(Color.parseColor(active ? "#5C4FE0" : "#F0EEFF")));
            categoryChips[idx].setTextColor(Color.parseColor(active ? "#FFFFFF" : "#5C4FE0"));
        }
    }

    private String categoryLabel(String key) {
        switch (key) {
            case "current": return getString(R.string.news_cat_current);
            case "business": return getString(R.string.news_cat_business);
            case "sports": return getString(R.string.news_cat_sports);
            case "all": return getString(R.string.news_cat_all);
            case "traffic":
            default: return getString(R.string.news_cat_traffic);
        }
    }

    // ===================== TẢI TIN =====================

    private void fetchNewsForCategory(String category) {
        int myGeneration = ++fetchGeneration;
        newsList.clear();
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(View.GONE);

        String[] urls = CATEGORY_RSS_URLS.get(category);
        if (urls == null || urls.length == 0) {
            layoutEmpty.setVisibility(View.VISIBLE);
            return;
        }
        pendingSources = urls.length;
        for (String url : urls) {
            fetchNewsFromSource(url, myGeneration);
        }
    }

    private void fetchNewsFromSource(String url, int generation) {
        executor.execute(() -> {
            List<NewsItem> parsed = new ArrayList<>();
            try {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build();
                Response response = client.newCall(request).execute();
                if (response.body() != null) {
                    parsed = parseXml(response.body().string());
                }
            } catch (Exception e) {
                e.printStackTrace(); // 1 nguồn lỗi/mất mạng không chặn các nguồn còn lại
            }
            final List<NewsItem> result = parsed;
            runOnUiThread(() -> {
                if (generation != fetchGeneration) return; // đã đổi danh mục, kết quả này lỗi thời
                newsList.addAll(result);
                // Mỗi nguồn trả kết quả ở thời điểm khác nhau -> nếu không sắp xếp lại, tin sẽ bị dồn
                // cục theo từng nguồn (hết tin nguồn này mới tới nguồn khác) thay vì xen kẽ theo thời
                // gian thực. Sắp xếp lại toàn bộ danh sách mỗi lần có nguồn mới trả về (danh sách nhỏ,
                // vài trăm mục là cùng, chi phí không đáng kể).
                Collections.sort(newsList, (a, b) -> Long.compare(parsePubDateMillis(b.pubDate), parsePubDateMillis(a.pubDate)));
                adapter.notifyDataSetChanged();
                pendingSources--;
                // Chỉ đánh giá "rỗng" khi TẤT CẢ nguồn đã phản hồi (thành công hoặc lỗi), tránh
                // nhấp nháy hiện "chưa có tin" ngay khi vừa đổi danh mục rồi lại biến mất.
                if (pendingSources <= 0) {
                    layoutEmpty.setVisibility(newsList.isEmpty() ? View.VISIBLE : View.GONE);
                    if (pendingVoiceAutoRead) {
                        pendingVoiceAutoRead = false;
                        if (!newsList.isEmpty()) {
                            openNewsDetail(newsList.get(0), 0, true);
                        } else {
                            Toast.makeText(this, getString(R.string.news_read_no_articles), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        });
    }

    /** Chỉ parse XML thành danh sách NewsItem cục bộ — không đụng newsList (chạy trên background thread). */
    private List<NewsItem> parseXml(String xml) {
        List<NewsItem> parsed = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml.trim()));

            int eventType = parser.getEventType();
            NewsItem item = null;
            String text = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("item".equalsIgnoreCase(tag)) item = new NewsItem("", "", "", "", "");
                    else if ("enclosure".equalsIgnoreCase(tag) && item != null) {
                        // Tuổi Trẻ không nhúng <img> trong <description>, ảnh đại diện nằm ở đây thay vào đó.
                        String encUrl = parser.getAttributeValue(null, "url");
                        if (encUrl != null && item.imageUrl.isEmpty()) item.imageUrl = encUrl;
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    text = parser.getText();
                } else if (eventType == XmlPullParser.END_TAG && item != null) {
                    switch (tag.toLowerCase()) {
                        case "title": item.title = text; break;
                        case "link": item.link = text; break;
                        case "pubdate": item.pubDate = text.replace(" +0700", ""); break;
                        case "description":
                            // Trích xuất ảnh (các báo thường để ảnh trong thẻ <img> của description).
                            // Dân Trí dùng nháy đơn (src='...') thay vì nháy kép nên phải chấp nhận cả hai.
                            Pattern p = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']");
                            Matcher m = p.matcher(text);
                            if (m.find()) item.imageUrl = m.group(1);
                            item.description = text.replaceAll("<[^>]*>", "").trim();
                            break;
                        case "item":
                            parsed.add(item);
                            break;
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return parsed;
    }

    // Mỗi báo trả pubDate theo 1 định dạng khác nhau: VnExpress/Dân Trí/VietNamNet dùng RFC822 năm 4
    // chữ số, Thanh Niên dùng RFC822 nhưng năm CHỈ 2 chữ số ("13 Jul 26"), còn Tuổi Trẻ lại dùng hẳn
    // định dạng kiểu Mỹ "M/d/yyyy h:mm:ss a" — không phải RSS chuẩn. Thử lần lượt cả 3 pattern; mục
    // nào không khớp pattern nào thì coi như cũ nhất (chìm xuống cuối danh sách) thay vì crash sort.
    private static final SimpleDateFormat[] PUB_DATE_FORMATS = {
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US),
            new SimpleDateFormat("EEE, dd MMM yy HH:mm:ss", Locale.US),
            new SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US),
    };

    private long parsePubDateMillis(String pubDate) {
        if (pubDate == null || pubDate.isEmpty()) return Long.MIN_VALUE;
        for (SimpleDateFormat fmt : PUB_DATE_FORMATS) {
            try {
                return fmt.parse(pubDate).getTime();
            } catch (ParseException ignored) {
            }
        }
        return Long.MIN_VALUE;
    }

    /** Suy ra tên nguồn hiển thị từ domain của link bài viết — trước đây hard-code "VnExpress" cho mọi bài. */
    private String sourceNameFor(String url) {
        if (url == null) return "";
        if (url.contains("vnexpress.net")) return "VnExpress";
        if (url.contains("thanhnien.vn")) return "Thanh Niên";
        if (url.contains("tuoitre.vn")) return "Tuổi Trẻ";
        if (url.contains("dantri.com.vn")) return "Dân Trí";
        if (url.contains("vietnamnet.vn")) return "VietNamNet";
        return "";
    }

    private void openNewsDetail(NewsItem item, int position) {
        openNewsDetail(item, position, false);
    }

    /** autoRead=true: dùng khi trợ lý giọng nói ra lệnh "đọc báo" — tự đọc ngay khi trang tải xong, không cần bấm nút. */
    private void openNewsDetail(NewsItem item, int position, boolean autoRead) {
        Intent intent = new Intent(TrafficNewsActivity.this, NewsDetailActivity.class);
        intent.putExtra("news_url", item.link);
        String[] urls = new String[newsList.size()];
        for (int k = 0; k < newsList.size(); k++) urls[k] = newsList.get(k).link;
        intent.putExtra("news_urls", urls);
        intent.putExtra("news_index", position);
        if (autoRead) intent.putExtra("news_auto_read", true);
        startActivity(intent);
    }

    // --- Adapter ---
    private class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.VH> {
        List<NewsItem> list;
        NewsAdapter(List<NewsItem> list) { this.list = list; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_news, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            NewsItem i = list.get(pos);
            h.t.setText(i.title);
            h.d.setText(i.description);
            h.p.setText(i.pubDate);
            h.src.setText(sourceNameFor(i.link));
            h.cat.setText(categoryLabel(currentCategory));
            Glide.with(TrafficNewsActivity.this).load(i.imageUrl).centerCrop()
                    .placeholder(android.R.color.darker_gray).into(h.i);
            h.itemView.setOnClickListener(v -> openNewsDetail(i, h.getAdapterPosition()));
        }

        @Override
        public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView t, d, p, src, cat; ImageView i;
            VH(View v) {
                super(v);
                t = v.findViewById(R.id.tvNewsTitle);
                d = v.findViewById(R.id.tvNewsDesc);
                p = v.findViewById(R.id.tvPubDate);
                src = v.findViewById(R.id.tvNewsSource);
                cat = v.findViewById(R.id.tvNewsCategoryBadge);
                i = v.findViewById(R.id.imgNews);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fetchGeneration++; // vô hiệu hoá mọi callback fetch còn treo
        executor.shutdown();
    }
}
