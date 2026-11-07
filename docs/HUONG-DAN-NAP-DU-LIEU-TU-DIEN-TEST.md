# Hướng dẫn nạp dữ liệu Dictionary/Kanji vào MySQL để chạy J-LAS

Tài liệu này dành cho thành viên nhận source code từ Git và cần một database
có đầy đủ dữ liệu Dictionary/Kanji để phát triển hoặc kiểm thử các chức năng
khác. Dữ liệu là **từ điển Nhật-Việt** (Mazii, 278.131 từ có âm Hán Việt) và
13.033 Kanji có âm Hán Việt/nghĩa tiếng Việt. Không cần chạy lại các bước import
trong `docs/dictionary-import.md`.

> **Đã từng nạp bản Nhật-Anh cũ (snapshot `JLAS2-19-2026-09-16`)?** Database
> `jlas_dictionary_test` cũ không khớp snapshot mới nên bootstrap sẽ báo
> `dictionary database is partially populated`. Chạy một lần với `--replace`
> (xem mục 12) để xóa và nạp lại bản Nhật-Việt.

## 0. Chạy nhanh sau khi `git pull`

Sau khi pull nhánh này về, chỉ cần chạy **đúng một lệnh** để có đủ dữ liệu:

```powershell
git lfs pull

python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/dictionary_import/requirements.txt

$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<mật khẩu MySQL local>'

.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py
```

Lệnh cuối cùng, `seed_bootstrap.py`, là file duy nhất cần chạy. Các file
`data/dictionary-seed/jlas-dictionary-test-seed-20261016.sql.gz`,
`data/dictionary-seed/kanjivg-r20250816.zip` và `data/dictionary-seed/stroke-order-extra.zip` chỉ là **dữ liệu đầu vào** mà
script này tự đọc, tự verify checksum, tự giải nén và tự import — không tự tay
`mysql < ...` file `.sql.gz`, không tự giải nén file `.zip` bằng tay. Ba dòng
đầu (`python -m venv`, `pip install`) chỉ cần làm một lần trên mỗi máy; các
lần sau chỉ cần set lại biến môi trường `DB_PASSWORD` rồi chạy lại
`seed_bootstrap.py`.

Kết quả mong đợi là `"status": "INSTALLED"` (lần đầu) hoặc
`"status": "ALREADY_INSTALLED"` (nếu chạy lại và dữ liệu đã đủ). Chi tiết từng
bước, cách kiểm tra và xử lý lỗi nằm ở các mục bên dưới.

## 1. Kết quả sau khi hoàn thành

Sau khi làm hết các bước, máy local sẽ có:

- Database MySQL `jlas_dictionary_test`.
- 111 bảng và 4 view của ứng dụng.
- Dữ liệu đầy đủ của 18 bảng Dictionary/Kanji (gồm cả dữ liệu tra Kanji theo bộ thủ).
- 11.661 file KanjiVG SVG tại
  `src/main/resources/static/assets/kanjivg/kanji`.
- Không có dữ liệu tài khoản, hồ sơ người dùng, tiến trình học hoặc dữ liệu
  nghiệp vụ của người khác.

Ứng dụng Spring Boot có thể kết nối vào `jlas_dictionary_test` để phát triển
các chức năng cần dữ liệu Kanji và từ vựng.

## 2. Dữ liệu được phân phối như thế nào

Repository chứa ba artifact thông qua Git LFS:

| Artifact | Nội dung | Kích thước mong đợi |
|---|---|---:|
| `data/dictionary-seed/jlas-dictionary-test-seed-20261016.sql.gz` | Schema ứng dụng và dữ liệu của 18 bảng Dictionary/Kanji | 37.374.389 byte |
| `data/dictionary-seed/kanjivg-r20250816.zip` | 11.661 file KanjiVG SVG | 22.803.150 byte |
| `data/dictionary-seed/stroke-order-extra.zip` | SVG thứ tự nét cho 113 Kanji hiếm KanjiVG không có | 157.108 byte |

Thông tin checksum, phiên bản nguồn và số dòng mong đợi nằm trong
`data/dictionary-seed/manifest.json`.

File SQL không chứa dữ liệu tài khoản hoặc dữ liệu nghiệp vụ. KanjiVG là file
ảnh nên được lưu trong ZIP, không nhét binary vào MySQL.

## 3. Yêu cầu trên máy

Cần cài trước:

- Git.
- Git LFS.
- Python 3.11 trở lên.
- MySQL Server 8.0 và MySQL command-line client.
- Java 21 và Maven nếu muốn chạy ứng dụng.

Kiểm tra nhanh trên PowerShell:

```powershell
git --version
git lfs version
python --version
mysql --version
java --version
mvn --version
```

MySQL Server phải đang chạy. Tài khoản dùng để bootstrap cần quyền tạo database,
bảng, view và insert dữ liệu. Trên máy phát triển local có thể dùng `root`.

## 4. Lấy đúng nhánh từ Git

Nếu chưa clone repository:

```powershell
git lfs install
git clone https://github.com/BinhSilver/SEP490.git
cd SEP490
git checkout feat/JLAS2-34-dictionary-search
git lfs pull
```

Nếu đã clone repository:

```powershell
git lfs install
git fetch origin
git checkout feat/JLAS2-34-dictionary-search
git pull origin feat/JLAS2-34-dictionary-search
git lfs pull
```

Kiểm tra Git LFS đã tải đủ ba artifact:

```powershell
git lfs ls-files
```

Kết quả phải chứa:

```text
data/dictionary-seed/jlas-dictionary-test-seed-20261016.sql.gz
data/dictionary-seed/kanjivg-r20250816.zip
data/dictionary-seed/stroke-order-extra.zip
```

## 5. Kiểm tra file tải về

### 5.1. Kiểm tra kích thước

```powershell
(Get-Item 'data/dictionary-seed/jlas-dictionary-test-seed-20261016.sql.gz').Length
(Get-Item 'data/dictionary-seed/kanjivg-r20250816.zip').Length
```

Kết quả mong đợi:

```text
37374389
22803150
```

Nếu file SQL chỉ khoảng 130 byte thì đó mới là Git LFS pointer, chưa phải file
dữ liệu thật. Chạy lại:

```powershell
git lfs pull
```

### 5.2. Kiểm tra SHA-256

```powershell
Get-FileHash `
  'data/dictionary-seed/jlas-dictionary-test-seed-20261016.sql.gz' `
  -Algorithm SHA256

Get-FileHash `
  'data/dictionary-seed/kanjivg-r20250816.zip' `
  -Algorithm SHA256
```

Checksum mong đợi:

```text
SQL:     ABEED9B0F70A0071ED92C4A0DD391EFFF39A6B0D6DF9EC9672781AADDFB9173F
KanjiVG: 0BC52E07D13F74F74753CDB63E8991381352CFC59C33504A35E2B7C1EACF98D2
```

Bootstrap cũng tự kiểm tra lại kích thước và checksum trước khi kết nối MySQL.

## 6. Tạo Python virtual environment

Đứng tại thư mục gốc repository và chạy:

```powershell
python -m venv .venv

.\.venv\Scripts\python.exe -m pip install `
  -r scripts/dictionary_import/requirements.txt
```

Không cần activate virtual environment vì các lệnh trong tài liệu gọi trực tiếp
Python bên trong `.venv`.

## 7. Khai báo kết nối MySQL

Thiết lập biến môi trường trong cửa sổ PowerShell hiện tại:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<mật khẩu MySQL local>'
```

Thay `<mật khẩu MySQL local>` bằng mật khẩu thật. Không ghi mật khẩu vào source
code, commit Git hoặc truyền qua tham số command line.

Thử kết nối trước nếu cần:

```powershell
mysql -h $env:DB_HOST -P $env:DB_PORT -u $env:DB_USERNAME -p
```

Nhập mật khẩu khi MySQL yêu cầu rồi chạy `exit` để thoát.

## 8. Nạp dữ liệu vào MySQL

Chạy lệnh bootstrap:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py
```

Mặc định lệnh sẽ tạo database `jlas_dictionary_test`. Quá trình có thể mất
khoảng 1-3 phút tùy cấu hình máy và ổ đĩa.

Kết quả thành công có dạng:

```json
{
  "status": "INSTALLED",
  "database": "jlas_dictionary_test",
  "snapshot": "JLAS2-34-ja-vi-2026-10-16",
  "dictionary_tables": 16,
  "kanjivg_files": 11661,
  "stroke_order_extra_files": 115,
  "asset_target": ".../src/main/resources/static/assets/kanjivg"
}
```

Chỉ khi toàn bộ kiểm tra sau thành công, bootstrap mới trả `INSTALLED`:

- Hai artifact đúng kích thước và SHA-256.
- Database có đúng 115 schema object: 111 bảng và 4 view.
- 18 bảng Dictionary/Kanji có đúng số dòng trong manifest.
- Các bảng nghiệp vụ ngoài Dictionary/Kanji có 0 dòng.
- KanjiVG có đúng 11.661 SVG và không chứa đường dẫn ZIP không an toàn.

Bootstrap chỉ chấp nhận tên database kết thúc bằng `_test`. Guardrail này giúp
tránh ghi đè nhầm database đang được sử dụng.

### Trường hợp `mysql` không nằm trong `PATH`

Trên Windows với MySQL cài ở vị trí mặc định:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py `
  --mysql-bin 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
```

### Dùng tên database test khác

Tên vẫn phải kết thúc bằng `_test`:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py `
  --database jlas_feature_test
```

## 9. Kiểm tra dữ liệu sau khi nạp

Mở MySQL:

```powershell
mysql -u root -p jlas_dictionary_test
```

Kiểm tra các bảng quan trọng:

```sql
SELECT COUNT(*) AS kanji_count FROM kanji;
SELECT COUNT(*) AS kanji_reading_count FROM kanji_reading;
SELECT COUNT(*) AS vocabulary_count FROM vocabulary_entry;
SELECT COUNT(*) AS vocabulary_sense_count FROM vocabulary_sense;
SELECT COUNT(*) AS vocabulary_gloss_count FROM vocabulary_gloss;
SELECT COUNT(*) AS vocabulary_kanji_count FROM vocabulary_kanji;
```

Kết quả mong đợi:

| Bảng | Số dòng |
|---|---:|
| `dictionary_source` | 5 |
| `dictionary_import_batch` | 17 |
| `radical` | 214 |
| `kanji` | 13.033 |
| `kanji_reading` | 40.397 |
| `vocabulary_entry` | 278.131 |
| `vocabulary_external_reference` | 278.131 |
| `vocabulary_reading` | 0 |
| `vocabulary_variant` | 0 |
| `vocabulary_sense` | 347.161 |
| `vocabulary_gloss` | 347.161 |
| `dictionary_part_of_speech` | 85 |
| `sense_part_of_speech` | 237.963 |
| `dictionary_domain` | 0 |
| `sense_domain` | 0 |
| `vocabulary_kanji` | 505.412 |
| `kanji_component` | 253 |
| `kanji_component_map` | 54.321 |

Xem thử nội dung thật:

```sql
SELECT * FROM kanji LIMIT 10;
SELECT * FROM vocabulary_entry LIMIT 10;
SELECT * FROM vocabulary_sense LIMIT 10;
SELECT * FROM vocabulary_gloss LIMIT 10;
```

Thoát MySQL:

```sql
exit
```

## 10. Chạy lại để kiểm tra tính idempotent

Chạy lại cùng lệnh:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py
```

Nếu database đang đúng và đầy đủ, kết quả sẽ là:

```json
{
  "status": "ALREADY_INSTALLED",
  "database": "jlas_dictionary_test",
  "dictionary_tables": 16,
  "kanjivg_files": 11661
}
```

Bootstrap không import trùng dữ liệu.

## 11. Cấu hình ứng dụng sử dụng database test

Trong cùng cửa sổ PowerShell, thiết lập datasource cho Spring Boot:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/jlas_dictionary_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh'
$env:SPRING_DATASOURCE_USERNAME = $env:DB_USERNAME
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
```

Chạy ứng dụng:

```powershell
mvn spring-boot:run
```

Các biến môi trường trên chỉ có hiệu lực trong cửa sổ PowerShell hiện tại.
Đóng terminal sẽ xóa chúng khỏi session.

Ứng dụng lúc này đọc dữ liệu từ `jlas_dictionary_test`, không phải database
`jlas` trên máy của người tạo snapshot.

## 12. Làm mới database test khi cần

Nếu bootstrap báo database có dữ liệu một phần, phương án an toàn nhất là chọn
một database `_test` mới:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py `
  --database jlas_dictionary_new_test
```

Chỉ khi chắc chắn muốn xóa và tạo lại `jlas_dictionary_test`, mới dùng:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py `
  --database jlas_dictionary_test `
  --replace
```

`--replace` sẽ `DROP` và tạo lại database được chọn. Script từ chối thực hiện
nếu tên database không kết thúc bằng `_test`.

## 13. Lỗi thường gặp

### `artifact size mismatch` hoặc `artifact checksum mismatch`

Artifact Git LFS chưa tải đủ hoặc bị thay đổi:

```powershell
git lfs pull
git status --short
```

Không tự sửa file `.sql.gz` hoặc `.zip`.

### `mysql client not found`

Truyền đường dẫn đầy đủ qua `--mysql-bin` như mục 8, hoặc thêm thư mục `bin`
của MySQL vào `PATH`.

### `Access denied for user`

Kiểm tra lại `DB_USERNAME`, `DB_PASSWORD`, MySQL Server và quyền của tài khoản.
Tài khoản phải có quyền tạo database/schema trong môi trường local.

### `Unknown database` hoặc ứng dụng vẫn kết nối database cũ

Kiểm tra lại biến datasource:

```powershell
$env:SPRING_DATASOURCE_URL
$env:SPRING_DATASOURCE_USERNAME
```

URL phải chứa `jlas_dictionary_test`.

### `dictionary database is partially populated`

Database đã có schema/dữ liệu không khớp snapshot. Dùng database `_test` mới,
hoặc dùng `--replace` nếu đã xác nhận database test cũ có thể bị xóa.

### Không nhìn thấy SVG KanjiVG

Kiểm tra số file:

```powershell
(Get-ChildItem `
  'src/main/resources/static/assets/kanjivg/kanji' `
  -Filter '*.svg' -File).Count
```

Kết quả phải là `11661`. Nếu thiếu, chạy lại bootstrap; quá trình thay thế cây
asset là nguyên tử nên không để lại file cũ hoặc cây dữ liệu dở dang.

Thứ tự nét của 113 Kanji hiếm mà KanjiVG không có được bootstrap giải nén từ
`data/dictionary-seed/stroke-order-extra.zip` vào
`src/main/resources/static/assets/stroke-order-extra`.

## 14. Lệnh cho Linux/macOS

Các bước tương đương trên Bash:

```bash
git lfs install
git checkout feat/JLAS2-34-dictionary-search
git pull
git lfs pull

python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/dictionary_import/requirements.txt

export DB_HOST=localhost
export DB_PORT=3306
export DB_USERNAME=root
export DB_PASSWORD='<mật khẩu MySQL local>'

python scripts/dictionary_import/seed_bootstrap.py

export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/jlas_dictionary_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh'
export SPRING_DATASOURCE_USERNAME="$DB_USERNAME"
export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"

./mvnw spring-boot:run
```

## 15. Checklist bàn giao

- [ ] Checkout đúng nhánh `feat/JLAS2-34-dictionary-search`.
- [ ] `git lfs pull` chạy thành công.
- [ ] SQL gzip có kích thước 37.374.389 byte.
- [ ] KanjiVG ZIP có kích thước 22.803.150 byte.
- [ ] MySQL Server đang chạy.
- [ ] Python dependencies đã được cài trong `.venv`.
- [ ] Bootstrap trả `INSTALLED` hoặc `ALREADY_INSTALLED`.
- [ ] Database `jlas_dictionary_test` có đủ dữ liệu theo bảng ở mục 9.
- [ ] Có đúng 11.661 SVG KanjiVG.
- [ ] `SPRING_DATASOURCE_URL` trỏ đến `jlas_dictionary_test`.
- [ ] Ứng dụng Spring Boot khởi động thành công.

Khi toàn bộ checklist đạt, máy local đã sẵn sàng để phát triển và kiểm thử các
chức năng phụ thuộc dữ liệu Dictionary/Kanji.
