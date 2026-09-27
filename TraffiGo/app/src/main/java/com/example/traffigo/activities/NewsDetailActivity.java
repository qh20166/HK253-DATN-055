package com.example.traffigo.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.traffigo.R;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc bài báo bằng TextToSpeech: trích nội dung từ WebView qua JS injection (nhiều lớp fallback vì
 * mỗi báo có cấu trúc DOM khác nhau và có thể đổi bất kỳ lúc nào: selector riêng cho
 * VnExpress/Thanh Niên -> thẻ <article> chung -> mọi đoạn <p> đủ dài), rồi chia câu và đọc tuần tự,
 * đồng thời tự cuộn WebView theo đúng đoạn đang đọc. RSS của app chỉ có tóm tắt (không có toàn văn)
 * nên phải lấy trực tiếp từ trang đang mở. Đọc xong 1 bài (không phải do user bấm dừng) thì tự
 * chuyển sang bài kế tiếp trong danh sách nhận từ TrafficNewsActivity — kiểu autoplay podcast.
 */
public class NewsDetailActivity extends AppCompatActivity {

    private static final int CHUNK_MAX_CHARS = 300;
    private static final String UTTERANCE_PREFIX = "news_";
    private static final long EXTRACT_TIMEOUT_MS = 6000; // trang nhiều JS quảng cáo có thể trì hoãn evaluateJavascript
    // Cùng tên file/khóa với TrafficNewsActivity (nơi người dùng chỉnh) — đọc lại mỗi lần khởi tạo TTS.
    private static final String NEWS_PREFS = "NewsPrefs";
    private static final String KEY_TTS_RATE = "tts_rate";

    private WebView webView;
    private ExtendedFloatingActionButton btnListen;
    private ProgressBar progressListenLoading;
    private TextView tvSource;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    // true ngay sau khi initTts's callback đã chạy XONG (dù thành công hay lỗi) — callback này của
    // TextToSpeech chỉ bắn ĐÚNG 1 LẦN, không lặp lại. Nếu chỉ dựa vào ttsReady để quyết định "chờ rồi
    // tự đọc", trường hợp init THẤT BẠI xảy ra trước khi user bấm nút (rất dễ xảy ra vì init xong
    // trong vài chục ms) sẽ khiến nút treo vĩnh viễn vì không còn callback nào tới để đánh thức nó.
    private boolean ttsInitDone = false;
    private boolean isSpeaking = false;
    private int pendingChunks = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Tăng mỗi lần bắt đầu 1 lượt trích xuất mới; dùng để callback/timeout cũ (nếu bắn muộn) tự
    // nhận ra mình đã lỗi thời và không đụng vào UI nữa (tránh 2 kết quả cùng chỉnh 1 nút).
    private int extractionRequestId = 0;
    // Nút "Nghe bài viết" chỉ phụ thuộc WebView tải xong (onPageFinished), độc lập với việc TTS
    // engine đã init xong chưa (TextToSpeech(this, listener) là async, có thể chưa xong khi user
    // bấm) -> nếu bấm lúc chưa sẵn sàng, ghi nhớ để tự đọc ngay khi TTS init xong thay vì báo lỗi sai.
    private boolean listenRequestedBeforeReady = false;

    // ===== Cuộn theo tiến trình đọc =====
    // Mỗi chunk (đoạn được gửi cho TTS) biết mình thuộc đoạn <p> nào (paragraph index) trên trang,
    // để khi TTS bắt đầu đọc chunk đó thì cuộn WebView tới đúng đoạn <p> tương ứng.
    private List<Integer> chunkParagraphIndex = new ArrayList<>();
    private int lastScrolledParagraph = -1;
    // Tăng mỗi lượt speakArticle() mới / mỗi lần user bấm dừng, để phân biệt utterance của lượt đọc
    // NÀO đang gửi callback — utterance của lượt cũ bắn muộn (sau khi đã dừng/chuyển bài) sẽ bị bỏ qua.
    private int ttsSessionId = 0;
    private boolean stoppedByUser = false;

    // ===== Tự động chuyển bài kế tiếp =====
    private String[] articleUrls;
    private int currentArticleIndex = 0;
    private boolean autoReadNextOnLoad = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news_detail);

        String url = getIntent().getStringExtra("news_url");
        webView = findViewById(R.id.webViewNews);
        tvSource = findViewById(R.id.tvSource);
        btnListen = findViewById(R.id.btnListenArticle);
        progressListenLoading = findViewById(R.id.progressListenLoading);

        String[] urls = getIntent().getStringArrayExtra("news_urls");
        int index = getIntent().getIntExtra("news_index", -1);
        if (urls != null && index >= 0 && index < urls.length) {
            articleUrls = urls;
            currentArticleIndex = index;
        } else {
            // Gọi trực tiếp không qua danh sách (fallback an toàn) -> không có gì để "next" tới.
            articleUrls = new String[]{url};
            currentArticleIndex = 0;
        }

        updateSourceLabel(url);

        // Trợ lý giọng nói ra lệnh "đọc báo" -> TrafficNewsActivity mở thẳng bài này kèm cờ này, tái
        // dùng đúng cơ chế đã có sẵn cho auto-next (autoReadNextOnLoad): trang tải xong tự bấm nghe
        // luôn, không cần người dùng chạm vào màn hình.
        if (getIntent().getBooleanExtra("news_auto_read", false)) {
            autoReadNextOnLoad = true;
        }

        // Cấu hình WebView để lướt mượt
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true); // Cho phép chạy JS để hiện nội dung
        settings.setDomStorageEnabled(true); // Lưu trữ dữ liệu web
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Hiện nút ngay từ đầu (mờ + khoá bấm) thay vì đợi trang tải xong hẳn mới hiện — trang nhiều
        // quảng cáo có thể mất 10-20s mới bắn onPageFinished, để nút biến mất suốt lúc đó trông như
        // treo/lỗi. Chỉ nội dung trích xuất mới cần đợi tải xong, còn việc "có nút để biết sắp bấm
        // được" thì hiện ngay cho người dùng yên tâm.
        btnListen.setVisibility(View.VISIBLE);
        setListenButtonLoading(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                super.onPageFinished(view, finishedUrl);
                // Chỉ cho nghe sau khi trang tải xong; trích nội dung lúc trang rỗng sẽ ra chuỗi rỗng
                setListenButtonLoading(false);
                if (autoReadNextOnLoad) {
                    autoReadNextOnLoad = false;
                    startListening();
                }
            }
        });
        webView.loadUrl(url);

        findViewById(R.id.btnBackDetail).setOnClickListener(v -> finish());

        initTts();
        btnListen.setOnClickListener(v -> {
            if (isSpeaking) stopListening();
            else startListening();
        });
    }

    /** true = trang/bài kế tiếp đang tải, nút hiện mờ + khoá bấm + xoay vòng loading; false = sẵn sàng bấm nghe. */
    private void setListenButtonLoading(boolean loading) {
        btnListen.setEnabled(!loading);
        btnListen.setAlpha(loading ? 0.5f : 1f);
        progressListenLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** Hiển thị nguồn dựa trên link — dùng chung cho lần mở đầu và mỗi lần tự chuyển sang bài kế tiếp. */
    private void updateSourceLabel(String url) {
        if (url == null) return;
        if (url.contains("thanhnien.vn")) tvSource.setText(R.string.news_source_thanhnien);
        else if (url.contains("vnexpress.net")) tvSource.setText(R.string.news_source_vnexpress);
        else if (url.contains("tuoitre.vn")) tvSource.setText(getString(R.string.news_source_generic, "Tuổi Trẻ"));
        else if (url.contains("dantri.com.vn")) tvSource.setText(getString(R.string.news_source_generic, "Dân Trí"));
        else if (url.contains("vietnamnet.vn")) tvSource.setText(getString(R.string.news_source_generic, "VietNamNet"));
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsInitDone = true;
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("vi", "VN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                applySpeechPrefs();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        int[] parsed = parseUtteranceId(utteranceId);
                        if (parsed == null || parsed[0] != ttsSessionId) return; // lượt đọc cũ, bỏ qua
                        int chunkIdx = parsed[1];
                        if (chunkIdx < 0 || chunkIdx >= chunkParagraphIndex.size()) return;
                        int paragraphIdx = chunkParagraphIndex.get(chunkIdx);
                        if (paragraphIdx == lastScrolledParagraph) return;
                        lastScrolledParagraph = paragraphIdx;
                        runOnUiThread(() -> scrollToParagraph(paragraphIdx));
                    }

                    @Override public void onDone(String utteranceId) {
                        int[] parsed = parseUtteranceId(utteranceId);
                        if (parsed == null || parsed[0] != ttsSessionId) return;
                        pendingChunks--;
                        if (pendingChunks <= 0) runOnUiThread(NewsDetailActivity.this::onReadingFinished);
                    }

                    @Override public void onError(String utteranceId) {
                        int[] parsed = parseUtteranceId(utteranceId);
                        if (parsed == null || parsed[0] != ttsSessionId) return;
                        pendingChunks--;
                        if (pendingChunks <= 0) runOnUiThread(NewsDetailActivity.this::onReadingFinished);
                    }
                });
                ttsReady = true;
                if (listenRequestedBeforeReady) {
                    listenRequestedBeforeReady = false;
                    startListening();
                }
            } else if (listenRequestedBeforeReady) {
                listenRequestedBeforeReady = false;
                Toast.makeText(this, getString(R.string.news_extract_failed), Toast.LENGTH_SHORT).show();
                setListeningUiStopped();
            }
        });
    }

    /**
     * Áp tốc độ đã chọn ở màn Tin tức (SharedPreferences dùng chung "NewsPrefs").
     * Không có lựa chọn giọng nam/nữ: đã kiểm tra thực tế qua tts.getVoices() — giọng vi-VN của Google
     * TTS chỉ có 1 giọng gắn nhãn rõ giới tính ("vif" = nữ), không có giọng nam tương ứng ("vim");
     * giọng en-US/en-GB cũng không mã hoá giới tính theo tên (iom/iol/tpf/... là ID tuỳ ý, không suy
     * ra được nam/nữ). Vì không có cách nào đáng tin cậy để chọn giọng nam nên bỏ hẳn lựa chọn này
     * thay vì để 1 nút bấm vào không có tác dụng gì.
     */
    private void applySpeechPrefs() {
        SharedPreferences prefs = getSharedPreferences(NEWS_PREFS, MODE_PRIVATE);
        tts.setSpeechRate(prefs.getFloat(KEY_TTS_RATE, 1.0f));
    }

    private void scrollToParagraph(int paragraphIdx) {
        String js = "(function(){var el=document.querySelector('[data-tts-p=\"" + paragraphIdx + "\"]');"
                + "if(el)el.scrollIntoView({behavior:'smooth',block:'center'});})()";
        webView.evaluateJavascript(js, null);
    }

    /** utteranceId dạng "news_<session>_<chunkIndex>" -> {session, chunkIndex}, hoặc null nếu không hợp lệ. */
    private int[] parseUtteranceId(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return null;
        String rest = utteranceId.substring(UTTERANCE_PREFIX.length());
        int sep = rest.indexOf('_');
        if (sep < 0) return null;
        try {
            return new int[]{Integer.parseInt(rest.substring(0, sep)), Integer.parseInt(rest.substring(sep + 1))};
        } catch (Exception e) {
            return null;
        }
    }

    // Lớp 1: selector riêng cho từng báo (đúng nhất, nhưng có thể lỗi thời nếu trang đổi giao diện)
    // -> Lớp 2: thẻ <article> chung -> Lớp 3: mọi đoạn <p> đủ dài trên trang (loại menu/footer bằng
    // ngưỡng độ dài) để không hỏng hoàn toàn nếu selector lớp 1/2 sai.
    // Cả 5 báo đều tách riêng đoạn sapo (dẫn đề) ra khỏi khối nội dung chính bằng 1 class riêng
    // (description/detail-sapo/article-sapo/content-detail-sapo) — kể cả VnExpress/Thanh Niên tưởng
    // đã đúng từ trước (chỉ lấy 'article p'), thực ra vẫn thiếu câu dẫn đầu bài vì nó nằm NGOÀI
    // .fck_detail/.detail-content, không phải <p> trong đó. Phải gộp "sapo, nội dung chính" trong 1
    // selector để browser trả về đúng thứ tự xuất hiện trên trang (sapo luôn đứng trước). Dùng đúng
    // tên class thay vì [class*="sapo" i] chung chung vì wildcard đó còn khớp cả sapo của khối "tin
    // liên quan" ở nơi khác trên trang (đã thấy Tuổi Trẻ có sapo của bài liên quan không liên quan gì
    // tới bài đang đọc).
    // Tựa đề (thẻ <h1>, luôn nằm ngoài khối nội dung/sapo nên không trùng lặp) được ghép vào ĐẦU danh
    // sách trước khi đánh số data-tts-p, để "đoạn 0" luôn là tựa đề — nếu đánh số bên trong collect()
    // như trước thì tựa đề thêm vào sau sẽ bị lệch số với các đoạn đã đánh trước đó.
    private static final String EXTRACT_JS =
            "(function(){" +
            "function clean(s){return s.replace(/\\s+/g,' ').trim();}" +
            "function collect(sels){" +
            "for(var i=0;i<sels.length;i++){" +
            "var els=document.querySelectorAll(sels[i]);" +
            "if(els.length>0){" +
            "var arr=[];" +
            "for(var j=0;j<els.length;j++){" +
            "if(clean(els[j].innerText||els[j].textContent||'').length>0)arr.push(els[j]);" +
            "}" +
            "var tot=0;for(var t=0;t<arr.length;t++)tot+=clean(arr[t].innerText||'').length;" +
            "if(tot>80)return arr;" +
            "}}" +
            "return null;}" +
            "var host=location.hostname;var arr=null;" +
            "if(host.indexOf('vnexpress')!==-1){arr=collect(['.description, article.fck_detail p','.description, .fck_detail p']);}" +
            "else if(host.indexOf('thanhnien')!==-1){arr=collect(['.detail-sapo, .detail-content p','.detail-sapo, .afcbc-body p','div[itemprop=\"articleBody\"] p']);}" +
            "else if(host.indexOf('dantri')!==-1){arr=collect(['.article-sapo, .article-content p']);}" +
            "else if(host.indexOf('tuoitre')!==-1){arr=collect(['.detail-sapo, .detail-content p']);}" +
            "else if(host.indexOf('vietnamnet')!==-1){arr=collect(['.content-detail-sapo, .content-detail p']);}" +
            "if(!arr)arr=collect(['article p']);" +
            "if(!arr){" +
            "var all=document.querySelectorAll('p');var buf=[];" +
            "for(var k=0;k<all.length;k++){" +
            "if(clean(all[k].innerText||all[k].textContent||'').length>40)buf.push(all[k]);" +
            "}" +
            "arr=buf;}" +
            // VnExpress: bản mobile mà WebView tải KHÔNG render câu dẫn (.description) thành text nhìn
            // thấy được trên trang — chỉ còn lại trong <meta name="description"> dùng cho SEO/chia sẻ
            // (khác bản desktop, đã verify bằng cách tải cùng URL với User-Agent Android thật). Không
            // có DOM element để gắn data-tts-p (không tồn tại trên trang) nên chỉ đọc dưới dạng text
            // thuần, scrollToParagraph sẽ tự bỏ qua đoạn này (không tìm thấy phần tử, không lỗi).
            "var leadText=null;" +
            "if(host.indexOf('vnexpress')!==-1&&!document.querySelector('.description')){" +
            "var meta=document.querySelector('meta[name=\"description\"]');" +
            // VnExpress luôn nối thêm " - VnExpress" vào cuối nội dung meta description -> bỏ đi,
            // nếu không TTS sẽ đọc thừa "- VnExpress" ở cuối câu dẫn.
            "if(meta&&meta.content){var lt=clean(meta.content).replace(/\\s*-\\s*VnExpress$/,'');if(lt.length>0)leadText=lt;}" +
            "}" +
            "var titleEl=document.querySelector('h1');" +
            "var combined=[];" +
            "if(titleEl&&clean(titleEl.innerText||'').length>0)combined.push({el:titleEl});" +
            "if(leadText)combined.push({text:leadText});" +
            "for(var n=0;n<arr.length;n++)combined.push({el:arr[n]});" +
            "var totalLen=0,result=[];" +
            "for(var m=0;m<combined.length;m++){" +
            "var item=combined[m];" +
            "var txt=item.el?clean(item.el.innerText||item.el.textContent||''):item.text;" +
            "if(!txt||txt.length===0)continue;" +
            "if(item.el)item.el.setAttribute('data-tts-p',result.length);" +
            "result.push(txt);" +
            "totalLen+=txt.length;" +
            "if(totalLen>8000)break;" +
            "}" +
            "return JSON.stringify(result);" +
            "})()";

    private void startListening() {
        if (!ttsReady) {
            if (ttsInitDone) {
                // Đã thử init xong nhưng thất bại (không có TTS engine khả dụng trên máy) — callback
                // của TextToSpeech chỉ bắn 1 lần nên KHÔNG được chờ tiếp, phải báo lỗi ngay, nếu
                // không nút sẽ treo vĩnh viễn vì không còn gì đánh thức listenRequestedBeforeReady nữa.
                Toast.makeText(this, getString(R.string.news_extract_failed), Toast.LENGTH_SHORT).show();
                return;
            }
            // TextToSpeech(this, listener) init là async — nút "Nghe" chỉ phụ thuộc WebView tải
            // xong nên có thể bấm được trước khi engine sẵn sàng. Ghi nhớ để initTts() tự gọi lại
            // startListening() ngay khi xong, thay vì báo nhầm lỗi "không đọc được nội dung".
            listenRequestedBeforeReady = true;
            btnListen.setText(R.string.news_extracting);
            btnListen.setEnabled(false);
            progressListenLoading.setVisibility(View.VISIBLE);
            return;
        }
        btnListen.setText(R.string.news_extracting);
        btnListen.setEnabled(false);
        progressListenLoading.setVisibility(View.VISIBLE);

        final int requestId = ++extractionRequestId;
        // Trang có nhiều JS quảng cáo/tracking chạy nền (thấy rõ ở VnExpress: polling ads liên tục)
        // có thể khiến evaluateJavascript bị trì hoãn rất lâu hoặc không bao giờ trả callback.
        // Không có timeout thì nút sẽ kẹt "Đang lấy nội dung…" vĩnh viễn -> tự phục hồi sau 6s.
        handler.postDelayed(() -> {
            if (requestId == extractionRequestId && !isSpeaking && !btnListen.isEnabled()) {
                // Bump request id: callback evaluateJavascript trả về muộn sau timeout
                // phải bị coi là lỗi thời, nếu không bài sẽ tự bật đọc ngay sau khi báo lỗi.
                extractionRequestId++;
                Toast.makeText(this, getString(R.string.news_extract_failed), Toast.LENGTH_SHORT).show();
                setListeningUiStopped();
            }
        }, EXTRACT_TIMEOUT_MS);

        webView.evaluateJavascript(EXTRACT_JS, raw -> {
            if (requestId != extractionRequestId) return; // đã timeout/huỷ, kết quả này lỗi thời
            btnListen.setEnabled(true);
            List<String> paragraphs = unwrapParagraphs(raw);
            int totalLen = 0;
            for (String s : paragraphs) totalLen += s.length();
            if (paragraphs.isEmpty() || totalLen < 40) {
                Toast.makeText(this, getString(R.string.news_extract_failed), Toast.LENGTH_SHORT).show();
                setListeningUiStopped();
                return;
            }
            speakArticle(paragraphs);
        });
    }

    /** Kết quả evaluateJavascript là chuỗi đã JSON-encode (có ngoặc kép bọc ngoài) -> giải mã lại. */
    private String unwrapJsString(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        try {
            return new JSONArray("[" + raw + "]").getString(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** unwrapJsString rồi parse tiếp thành mảng JSON các đoạn văn (mỗi phần tử = 1 thẻ <p>). */
    private List<String> unwrapParagraphs(String raw) {
        String inner = unwrapJsString(raw);
        List<String> out = new ArrayList<>();
        if (inner == null) return out;
        try {
            JSONArray arr = new JSONArray(inner);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void speakArticle(List<String> paragraphs) {
        List<String> chunks = new ArrayList<>();
        List<Integer> chunkParagraph = new ArrayList<>();
        for (int pIdx = 0; pIdx < paragraphs.size(); pIdx++) {
            for (String part : splitIntoChunks(paragraphs.get(pIdx), CHUNK_MAX_CHARS)) {
                chunks.add(part);
                chunkParagraph.add(pIdx);
            }
        }
        if (chunks.isEmpty()) {
            Toast.makeText(this, getString(R.string.news_extract_failed), Toast.LENGTH_SHORT).show();
            progressListenLoading.setVisibility(View.GONE);
            return;
        }
        isSpeaking = true;
        stoppedByUser = false;
        pendingChunks = chunks.size();
        chunkParagraphIndex = chunkParagraph;
        lastScrolledParagraph = -1;
        int session = ++ttsSessionId;
        btnListen.setText(R.string.news_stop_listening);
        btnListen.setIconResource(R.drawable.ic_volume_off);
        progressListenLoading.setVisibility(View.GONE);
        for (int i = 0; i < chunks.size(); i++) {
            String id = UTTERANCE_PREFIX + session + "_" + i;
            tts.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
        }
    }

    /** Cắt theo câu (giữ nguyên ngữ nghĩa khi đọc) rồi gộp thành đoạn <= maxChars, tránh giới hạn độ dài của TTS. */
    private List<String> splitIntoChunks(String text, int maxChars) {
        List<String> sentences = new ArrayList<>();
        Matcher m = Pattern.compile("[^.!?…]+[.!?…]?").matcher(text);
        while (m.find()) {
            String s = m.group().trim();
            if (!s.isEmpty()) sentences.add(s);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() + s.length() + 1 > maxChars && cur.length() > 0) {
                chunks.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(s).append(' ');
        }
        if (cur.length() > 0) chunks.add(cur.toString().trim());
        return chunks;
    }

    private void stopListening() {
        stoppedByUser = true;
        ttsSessionId++; // vô hiệu hoá callback utterance của lượt đang đọc (nếu bắn muộn)
        extractionRequestId++; // vô hiệu hoá callback/timeout của lượt trích xuất đang chờ (nếu có)
        if (tts != null) tts.stop();
        setListeningUiStopped();
        btnListen.setEnabled(true);
    }

    private void setListeningUiStopped() {
        isSpeaking = false;
        pendingChunks = 0;
        btnListen.setText(R.string.news_listen);
        btnListen.setIconResource(R.drawable.ic_volume_on);
        progressListenLoading.setVisibility(View.GONE);
    }

    /** Đọc xong 1 bài (hết chunk tự nhiên). Nếu không phải do user bấm dừng thì tự chuyển bài kế tiếp. */
    private void onReadingFinished() {
        boolean shouldAdvance = !stoppedByUser;
        setListeningUiStopped();
        if (shouldAdvance) goToNextArticleIfAny();
    }

    private void goToNextArticleIfAny() {
        if (articleUrls == null || currentArticleIndex + 1 >= articleUrls.length) {
            if (articleUrls != null && articleUrls.length > 1) {
                Toast.makeText(this, getString(R.string.news_end_of_playlist), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        currentArticleIndex++;
        String nextUrl = articleUrls[currentArticleIndex];
        autoReadNextOnLoad = true;
        setListenButtonLoading(true);
        updateSourceLabel(nextUrl);
        webView.loadUrl(nextUrl);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        extractionRequestId++; // vô hiệu hoá mọi callback/timeout còn treo sau khi Activity huỷ
        ttsSessionId++;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
