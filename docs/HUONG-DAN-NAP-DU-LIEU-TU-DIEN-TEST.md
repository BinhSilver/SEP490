# Hướng dẫn nạp dữ liệu Dictionary/Kanji vào MySQL để chạy J-LAS

Tài liệu này dành cho thành viên nhận source code từ Git và cần một database
có đầy đủ dữ liệu Dictionary/Kanji để phát triển hoặc kiểm thử các chức năng
khác. Không cần chạy lại quá trình import JMdict kéo dài khoảng 40 phút.

## 1. Kết quả sau khi hoàn thành

Sau khi làm hết các bước, máy local sẽ có:

- Database MySQL `jlas_dictionary_test`.
- 109 bảng và 4 view của ứng dụng.
- Dữ liệu đầy đủ của 16 bảng Dictionary/Kanji.
- 11.661 file KanjiVG SVG tại
  `src/main/resources/static/assets/kanjivg/kanji`.
- Không có dữ liệu tài khoản, hồ sơ người dùng, tiến trình học hoặc dữ liệu
  nghiệp vụ của người khác.

Ứng dụng Spring Boot có thể kết nối vào `jlas_dictionary_test` để phát triển
các chức năng cần dữ liệu Kanji và từ vựng.

## 2. Dữ liệu được phân phối như thế nào

Repository chứa hai artifact thông qua Git LFS:

| Artifact | Nội dung | Kích thước mong đợi |
|---|---|---:|
| `data/dictionary-seed/jlas-dictionary-test-seed-20260916.sql.gz` | Schema ứng dụng và dữ liệu của 16 bảng Dictionary/Kanji | 45.488.556 byte |
| `data/dictionary-seed/kanjivg-r20250816.zip` | 11.661 file KanjiVG SVG | 22.803.150 byte |

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
git checkout feat/JLAS2-19-dictionary-kanji-base-data-import-log
git lfs pull
```

Nếu đã clone repository:

```powershell
git lfs install
git fetch origin
git checkout feat/JLAS2-19-dictionary-kanji-base-data-import-log
git pull origin feat/JLAS2-19-dictionary-kanji-base-data-import-log
git lfs pull
```

Kiểm tra Git LFS đã tải đủ hai artifact:

```powershell
git lfs ls-files
```

Kết quả phải chứa:

```text
data/dictionary-seed/jlas-dictionary-test-seed-20260916.sql.gz
data/dictionary-seed/kanjivg-r20250816.zip
```

## 5. Kiểm tra file tải về

### 5.1. Kiểm tra kích thước

```powershell
(Get-Item 'data/dictionary-seed/jlas-dictionary-test-seed-20260916.sql.gz').Length
(Get-Item 'data/dictionary-seed/kanjivg-r20250816.zip').Length
```

Kết quả mong đợi:

```text
45488556
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
  'data/dictionary-seed/jlas-dictionary-test-seed-20260916.sql.gz' `
  -Algorithm SHA256

Get-FileHash `
  'data/dictionary-seed/kanjivg-r20250816.zip' `
  -Algorithm SHA256
```

Checksum mong đợi:

```text
SQL:     28F4F1EDFAB533684956380338C41C8FAE7A4993893802B39B6D90E43EC430A2
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
  "snapshot": "JLAS2-19-2026-09-16",
  "dictionary_tables": 16,
  "kanjivg_files": 11661,
  "asset_target": ".../src/main/resources/static/assets/kanjivg"
}
```

Chỉ khi toàn bộ kiểm tra sau thành công, bootstrap mới trả `INSTALLED`:

- Hai artifact đúng kích thước và SHA-256.
- Database có đúng 113 schema object: 109 bảng và 4 view.
- 16 bảng Dictionary/Kanji có đúng số dòng trong manifest.
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
| `dictionary_source` | 3 |
| `dictionary_import_batch` | 6 |
| `radical` | 198 |
| `kanji` | 2.136 |
| `kanji_reading` | 9.207 |
| `vocabulary_entry` | 216.403 |
| `vocabulary_external_reference` | 218.779 |
| `vocabulary_reading` | 35.884 |
| `vocabulary_variant` | 54.832 |
| `vocabulary_sense` | 672.010 |
| `vocabulary_gloss` | 1.444.700 |
| `dictionary_part_of_speech` | 83 |
| `sense_part_of_speech` | 916.717 |
| `dictionary_domain` | 96 |
| `sense_domain` | 34.481 |
| `vocabulary_kanji` | 456.010 |

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

## 14. Lệnh cho Linux/macOS

Các bước tương đương trên Bash:

```bash
git lfs install
git checkout feat/JLAS2-19-dictionary-kanji-base-data-import-log
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

- [ ] Checkout đúng nhánh `feat/JLAS2-19-dictionary-kanji-base-data-import-log`.
- [ ] `git lfs pull` chạy thành công.
- [ ] SQL gzip có kích thước 45.488.556 byte.
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
