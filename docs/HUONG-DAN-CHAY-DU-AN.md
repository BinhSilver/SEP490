# Hướng dẫn chạy J-LAS với từ điển Nhật-Việt

Tài liệu cho thành viên nhóm muốn chạy dự án trên máy mình với đầy đủ chức năng từ điển:
tra cứu Nhật-Việt, chi tiết Kanji có hoạt ảnh thứ tự nét, nhận diện Kanji viết tay và tra
Kanji theo bộ thủ. Làm lần lượt từ bước 1 đến bước 7; lần chạy sau chỉ cần bước 7.

## 1. Cài đặt trên máy

| Phần mềm | Phiên bản | Kiểm tra |
|---|---|---|
| Git và Git LFS | mới nhất | `git --version`, `git lfs version` |
| JDK | 21 | `java --version` |
| Maven | 3.9+ (hoặc dùng `mvnw` có sẵn) | `mvn --version` |
| MySQL Server + MySQL client | 8.0 | `mysql --version` |
| Python | 3.11+ | `python --version` |

MySQL Server phải đang chạy. Trên máy local có thể dùng tài khoản `root`.

## 2. Lấy source code và dữ liệu

```powershell
git lfs install
git clone https://github.com/BinhSilver/SEP490.git
cd SEP490
git checkout feat/JLAS2-34-dictionary-search
git lfs pull
```

Đã clone từ trước thì chỉ cần:

```powershell
git fetch origin
git checkout feat/JLAS2-34-dictionary-search
git pull
git lfs pull
```

Thư mục `data/dictionary-seed/` phải có 3 file dữ liệu thật (không phải file con trỏ LFS
khoảng 130 byte):

| File | Nội dung | Kích thước |
|---|---|---:|
| `jlas-dictionary-test-seed-20261016.sql.gz` | Schema + dữ liệu từ điển (18 bảng) | ~37 MB |
| `kanjivg-r20250816.zip` | 11.661 SVG thứ tự nét KanjiVG | ~22 MB |
| `stroke-order-extra.zip` | SVG thứ tự nét cho 113 Kanji hiếm KanjiVG không có | ~0,2 MB |

Nếu file nào chỉ vài trăm byte, chạy lại `git lfs pull`.

## 3. Cài thư viện Python (một lần)

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/dictionary_import/requirements.txt
```

## 4. Nạp dữ liệu từ điển vào MySQL

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<mật khẩu MySQL>'

.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py
```

Lệnh này tự kiểm tra checksum, tạo database `jlas_dictionary_test`, nạp dữ liệu (2–3 phút)
và giải nén SVG vào `src/main/resources/static/assets/kanjivg` và
`src/main/resources/static/assets/stroke-order-extra`. Kết quả đúng:

```json
{"status": "INSTALLED", "database": "jlas_dictionary_test", "dictionary_tables": 18,
 "kanjivg_files": 11661, "stroke_order_extra_files": 115, ...}
```

- Chạy lại khi đã đủ dữ liệu sẽ trả `"status": "ALREADY_INSTALLED"`.
- **Đã từng nạp bản cũ** (từ điển Nhật-Anh) thì sẽ báo `dictionary database is partially
  populated`. Thêm `--replace` để xoá và nạp lại database `jlas_dictionary_test`:
  `.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py --replace`
- Không tìm thấy `mysql`: thêm
  `--mysql-bin 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'`.

## 5. Tạo file cấu hình local

`application-local.yaml` chứa mật khẩu nên không có trong Git. Sao chép từ file mẫu:

```powershell
Copy-Item src/main/resources/application-local.example.yaml src/main/resources/application-local.yaml
```

Mở `src/main/resources/application-local.yaml` và sửa:

- `spring.datasource.password`: mật khẩu MySQL của bạn.
- `jlas.auth.jwt-secret` và `app.jwt.secret`: chuỗi ngẫu nhiên bất kỳ, **ít nhất 32 ký tự**.

Các khoá API (`openrouter.api.key`, `deepgram.api.key`, mail...) để trống vẫn chạy được; khi
đó các tính năng AI, gửi mail OTP... sẽ không hoạt động hoặc dùng dữ liệu demo. Xin trưởng
nhóm các khoá này nếu cần thử những tính năng đó.

## 6. Tạo tài khoản đăng nhập để test (một lần)

Database từ seed không có người dùng, mà trang từ điển yêu cầu đăng nhập:

```powershell
Get-Content sql/seeds/local_test_account.sql | mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test
```

Tài khoản: **`tester@jlas.local` / `Test@12345`**.

## 7. Chạy ứng dụng

```powershell
mvn spring-boot:run
```

Khi log có `Started JlasApplication` và `Đã dựng chỉ mục nét KanjiVG: 6529 Kanji`, mở
<http://localhost:8080/login>, đăng nhập rồi vào mục **Tra cứu**. Kiểm tra nhanh:

| Thử | Kết quả mong đợi |
|---|---|
| Gõ `学校` + Enter | Chi tiết từ: HỌC GIÁO, nghĩa "học đường; học hiệu; nhà trường" |
| Gõ `mèo` | Ra 猫 |
| Bấm "Xem chi tiết" ở phần Cấu tạo Hán tự | Trang Hán tự có hoạt ảnh thứ tự nét |
| Bấm biểu tượng viết tay, vẽ một chữ | Gợi ý Kanji hiện sau mỗi nét |
| Bấm "Tra theo bộ thủ", chọn 亻 rồi 木 | 41 chữ: 休, 体, 保... |

## 8. Chạy test (tuỳ chọn)

```powershell
mvn test
.\.venv\Scripts\python.exe -m unittest discover -s scripts/dictionary_import/tests -t .
node --test scripts/handwriting.test.mjs
```

## 9. Lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `Port 8080 was already in use` | Đang có một bản app khác chạy. Tắt nó, hoặc: `Get-NetTCPConnection -LocalPort 8080 -State Listen \| ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }` |
| `Schema validation: ... jlpt_exam ...` hoặc thiếu cột `han_viet` | `spring.datasource.url` đang trỏ sai database (ví dụ `jlas` dựng từ nhánh khác). Nhánh này dùng `jlas_dictionary_test`. |
| `Unresolved compilation problems` khi khởi động | VS Code (Java extension) ghi đè file `.class`. Xoá thư mục `target/classes/com` rồi chạy lại `mvn spring-boot:run`. |
| `jlas.auth.jwt-secret must contain at least 32 characters` | Sửa `jwt-secret` trong `application-local.yaml` đủ 32 ký tự. |
| Viết tay báo "Tính năng viết tay tạm thời chưa sẵn sàng" | Chưa giải nén SVG: chạy lại bước 4. |
| Trang Hán tự không có hoạt ảnh nét cho chữ hiếm | Thiếu `stroke-order-extra`: chạy lại bước 4. (Khoảng 27 chữ cực hiếm không có dữ liệu nét là bình thường.) |
| Đăng nhập báo sai mật khẩu | Chưa chạy bước 6. |

## Tài liệu liên quan

- `docs/HUONG-DAN-NAP-DU-LIEU-TU-DIEN-TEST.md`: chi tiết về seed và cách kiểm tra dữ liệu.
- `docs/dictionary-import.md`: cách dựng lại dữ liệu từ điển từ nguồn gốc (chỉ người bảo trì dữ liệu cần).
