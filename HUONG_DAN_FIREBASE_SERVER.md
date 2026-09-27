# HƯỚNG DẪN SỬA FIREBASE RULES + DEPLOY SERVER ML TRAFFIGO

> Tài liệu thao tác cho ngày test (20–21/09/2026) — đi kèm đồ án HK253-DATN-055.

---

## PHẦN 1 — SỬA FIREBASE RULES (5 PHÚT, BẮT BUỘC)

### Hiện tượng
App hiện lỗi **"Lỗi tải địa chỉ: Permission denied"** khi vào màn Lộ trình/Trang chủ.
Nguyên nhân: **Realtime Database đang bị khóa bởi security rules** (tất cả đọc/ghi đều bị chặn, kể cả người dùng đã đăng nhập). Firebase Auth vẫn sống tốt.

### Các bước sửa

1. Mở trình duyệt, vào **https://console.firebase.google.com**
2. Đăng nhập Google account đang sở hữu project → chọn project **traffigo-1209b**
3. Menu bên trái: **Build → Realtime Database** (nếu menu thu gọn, bấm biểu tượng ⚙ Build)
4. Chọn tab **RULES** (bên cạnh tab "Data")
5. Xóa toàn bộ nội dung trong khung soạn thảo, dán vào:

```json
{
  "rules": {
    "Users": {
      "$uid": {
        ".read": "auth != null && auth.uid === $uid",
        ".write": "auth != null && auth.uid === $uid"
      }
    },
    "incidents": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```

6. Bấm **PUBLISH** (Xuất bản)
7. Mở app TraffiGo → đăng nhập (`testuser@traffigo-app.com` / `TraffiGo2026!`) → kiểm tra:
   - Màn Lộ trình không còn toast "Permission denied"
   - Lưu Nhà/Công ty, địa điểm yêu thích, báo sự cố hoạt động
   - Lịch sử lộ trình ghi được khi xác nhận tuyến

### Ý nghĩa rules (nếu hội đồng hỏi)
- `Users/{uid}`: mỗi người dùng chỉ **đọc/ghi được dữ liệu của chính mình** (`auth.uid === $uid`) — không lộ dữ liệu người khác
- `incidents`: ai đã đăng nhập cũng báo được sự cố và đọc được sự cố của người khác (đúng bản chất cộng đồng)
- **Không** đặt `.read: "auth != null"` ở root — sẽ lộ toàn bộ database

---

## PHẦN 2 — DEPLOY SERVER ML TRAFFIGO

### Kịch bản A — Chạy trên máy tính + emulator MuMu (phổ biến nhất, 3 phút)

```bat
:: 1. Mở Command Prompt
cd /d E:\Android\TraffiGo\server

:: 2. Cài thư viện (chỉ lần đầu)
pip install -r requirements.txt

:: 3. Huấn luyện mô hình (chỉ lần đầu — models\ đã có sẵn thì bỏ qua)
python train_model.py

:: 4. Chạy server
uvicorn app:app --host 0.0.0.0 --port 8000
```

Mở cửa sổ CMD **thứ hai** để nối mạng emulator:
```bat
E:\Android\Sdk\platform-tools\adb.exe reverse tcp:8000 tcp:8000
```

Mở app TraffiGo → vào **Xem bản đồ** → góc phải trên phải thấy chip xanh
**"● Server ML: đang hoạt động"**. Bấm vào một đoạn đường sẽ có thẻ
**"DỰ BÁO ML (SERVER)"**.

Kiểm tra nhanh không cần app: mở `http://localhost:8000/docs` (Swagger UI)
hoặc `http://localhost:8000/api/ml/live-status` (trạng thái refresher 15 phút).

### Kịch bản B — Điện thoại thật (cùng WiFi với máy chạy server)

**Cách 1 — qua cáp USB (nhanh nhất, không cần chỉnh gì):**
1. Bật USB debugging trên điện thoại, cắm cáp vào máy tính
2. `E:\Android\Sdk\platform-tools\adb.exe reverse tcp:8000 tcp:8000`
3. Mở app → chip server xanh. App gọi `127.0.0.1:8000` = server trên máy tính.

**Cách 2 — qua WiFi (không cần cáp):**
1. Chạy server như Kịch bản A (`--host 0.0.0.0`)
2. Mở Windows Firewall cho port 8000 (chạy CMD với quyền Admin):
   ```bat
   netsh advfirewall firewall add rule name="TraffiGoML" dir=in action=allow protocol=TCP localport=8000
   ```
3. Lấy IP máy tính: `ipconfig` → dòng **IPv4 Address** (vd. `192.168.1.10`)
4. Mở app TraffiGo → **Xem bản đồ → NHẤN GIỮ chip trạng thái server** → nhập
   `http://192.168.1.10:8000` → **Lưu**. Chip chuyển xanh nếu kết nối thành công.
   (Địa chỉ lưu trong SharedPreferences, không cần chỉnh lại.)

> Nếu nhập nhầm, nhấn giữ chip lần nữa để sửa. Muốn quay về mặc định: nhập `http://10.0.2.2:8000`.

### Kịch bản C — Deploy lên cloud (khi muốn server chạy 24/7, làm sau nếu cần)

Cách gọn nhất với FastAPI: **Render.com** (free tier):
1. Push thư mục `server/` lên một GitHub repo (bao gồm `models/`, `requirements.txt`, `app.py`; KHÔNG push `local.properties`)
2. render.com → **New → Web Service** → nối repo
3. Build command: `pip install -r requirements.txt`
4. Start command: `uvicorn app:app --host 0.0.0.0 --port $PORT`
5. Nhận URL dạng `https://xxxx.onrender.com`
6. Mở app → nhấn giữ chip server → nhập URL Render
7. Lưu ý free tier **ngủ sau 15 phút khôngtraffic**: dùng cron-job.org ping
   `/health` mỗi 10 phút để giữ ấm; request đầu sau khi ngủ mất ~30–50 giây

### Biến cấu hình server (không cần sửa nếu demo thường)

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `TRAFFIGO_REFRESH_DISABLE` | (trống = bật) | Đặt `1` để tắt refresher |
| `TRAFFIGO_REFRESH_INTERVAL` | 900 | Chu kỳ quét live (giây) |
| `TRAFFIGO_REFRESH_MAX` | 50 | Số đoạn đường giám sát |
| `TOMTOM_KEYS` | đọc từ `local.properties` | Các khóa API xoay vòng |

Kiểm tra server sau khi chạy:
- `GET /health` → `{ "status": "ok", "modelLoaded": true, ... }`
- `GET /api/ml/live-status` → số đoạn OK/FAIL, mốc cập nhật kế tiếp

### Sự cố thường gặp

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Chip luôn "ngoại tuyến" dù server chạy | Thiếu `adb reverse` (MuMu/emulator) | Chạy lệnh adb reverse ở Kịch bản A |
| Chip xanh nhưng thẻ Dự báo không hiện | Bấm chưa trúng đoạn (chưa có dữ liệu) | Zoom ≥ 14 rồi bấm vào đường đang có màu |
| `error while loading model` khi start | Chưa chạy `train_model.py` | Chạy bước 3 Kịch bản A (server vẫn chạy, dùng heuristic) |
| Điện thoại không nối được qua WiFi | Firewall chặn port 8000 | Lệnh netsh ở Kịch bản B |
| Refresher báo FAIL tất cả | Hết quota ngày các khóa TomTom | Đợi reset ngày hoặc thêm khóa mới (loại Mobile/Native) |
