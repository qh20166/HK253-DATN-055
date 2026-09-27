# HK253-DATN-055 — Xây dựng giải pháp gom cụm tình trạng giao thông ở TP.HCM

**Đồ án tốt nghiệp — Trường Đại học Bách Khoa — ĐHQG TP.HCM**
Khoa Khoa học và Kỹ thuật Máy tính · Hội đồng 6L Khoa học Máy tính

| | |
|---|---|
| **Mã đồ án** | HK253-DATN-055 |
| **Sinh viên thực hiện** | Trần Quang Huy — 2211288 (KHMT - CQ) |
| **GVHD** | PGS.TS. Trần Minh Quang · ThS. Bùi Tiến Đức |
| **Ngôn ngữ bảo vệ** | Tiếng Việt |

Hệ thống phân tích tình trạng giao thông TP.HCM bằng **gom cụm dữ liệu đa nguồn** (TomTom Traffic, OpenStreetMap, Mapbox, Open-Meteo), đóng gói thành **ứng dụng di động TraffiGo** (Android) và **server Machine Learning** (FastAPI) tích hợp hai chiều.

## Cấu trúc repository

```
HK253-DATN-055/
├── TraffiGo/                          # Ứng dụng di động Android (Java)
│   ├── app/src/main/java/...          #   20 activity: bản đồ 6 màu cụm,
│   │                                  #   chỉ đường né kẹt, tin tức, trợ lý giọng nói...
│   ├── app/src/main/assets/           #   cluster_model.json (K-Means nhúng on-device),
│   │                                  #   geometry.csv (23.417 đoạn đường TP.HCM)
│   └── app/src/test/                  #   Unit test (ClusterColorAssigner, PathFinder...)
├── server/                            # Server Machine Learning (FastAPI)
│   ├── app.py                         #   7 endpoint /api/ml/* + /health + /admin
│   ├── admin_ui.py                    #   Trang quản trị /admin (login + dashboard)
│   ├── train_model.py                 #   Pipeline huấn luyện Random Forest
│   └── models/                        #   speed_rf.joblib + cluster_model.json + meta
├── BaoCao/                            # Báo cáo đồ án (LaTeX) + PDF xuất bản
│   ├── HK253-DATN-055_2211288.pdf     #   Báo cáo chính (nộp Khoa)
│   │   └── *.tex, figures/                #   Nguồn LaTeX + hình minh họa
└── HUONG_DAN_FIREBASE_SERVER.md       # Hướng dẫn sửa Firebase Rules + chạy server
```

## Kết quả chính

| Thành phần | Kết quả |
|---|---|
| Gom cụm | K-Means k=6 nhánh Feature Selection — Silhouette **0,4183**, DBI 0,8063, CH 147.574,6 (5 thuật toán, 149.023 mẫu) |
| Dự đoán tốc độ | Random Forest — **MAE 2,19 km/h**, R² 0,919 (baseline 8,25 km/h) |
| Ứng dụng | ~8.400 dòng Java · 20 màn hình · VI/EN · 18 ca kiểm thử chức năng đạt |
| Server | 7 API ML · dự đoán 66 ms/request · refresher dữ liệu live 15 phút |

## Chạy ứng dụng Android

1. Mở thư mục `TraffiGo/` bằng Android Studio (minSdk 29, compileSdk 36)
2. Đặt khóa TomTom vào `local.properties`: `TOMTOM_KEYS=<khóa 1>,<khóa 2>,...`
3. `./gradlew assembleDebug` hoặc Run trực tiếp
4. Đăng nhập bằng email/mật khẩu hoặc Google (cần SHA-1 keystore đã đăng ký trên Firebase)

## Chạy server ML

```bash
cd server
pip install -r requirements.txt
python train_model.py                        # tùy chọn — models/ đã có sẵn
uvicorn app:app --host 0.0.0.0 --port 8000
```

- Swagger UI: `http://localhost:8000/docs`
- Trang quản trị: `http://localhost:8000/admin` (mật khẩu mặc định `traffigo2026`, đổi qua biến `TRAFFIGO_ADMIN_PASSWORD`)
- Server demo đang chạy: **https://traffigo-server.onrender.com**

Chi tiết vận hành (Firebase Rules, deploy Render, refresher live) xem [HUONG_DAN_FIREBASE_SERVER.md](HUONG_DAN_FIREBASE_SERVER.md).

## Các repository liên quan

| Repository | Nội dung |
|---|---|
| [qh20166/TraffiGo](https://github.com/qh20166/TraffiGo) | Ứng dụng Android (repo phát triển chính) |
| [qh20166/traffigo-ml-server](https://github.com/qh20166/traffigo-ml-server) | Server ML triển khai độc lập (dùng bởi Render) |
| [qh20166/DATN_172](https://github.com/qh20166/DATN_172) | Dữ liệu huấn luyện và pipeline phân tích |

## Dữ liệu

Snapshot giao thông đa nguồn và pipeline dữ liệu nằm tại repository liên quan: [qh20166/DATN_172](https://github.com/qh20166/DATN_172) (thư mục `DATA/`, `DATN/`).
