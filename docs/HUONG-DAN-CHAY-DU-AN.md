# Hướng dẫn chạy J-LAS với từ điển Nhật-Việt

Tài liệu cho thành viên nhóm muốn chạy dự án trên máy mình với đầy đủ chức năng tra cứu:
tra từ Nhật-Việt theo bố cục kiểu Mazii (`/search/word/...`), chi tiết Hán tự có hoạt ảnh thứ
tự nét, viết tay, tra theo bộ thủ, từ trái nghĩa, trọng âm, ý kiến đóng góp và phát âm.
Làm lần lượt từ bước 1 đến bước 8; lần chạy sau chỉ cần bước 8.

## 1. Cài đặt trên máy

| Phần mềm | Phiên bản | Kiểm tra |
|---|---|---|
| Git và Git LFS | mới nhất | `git --version`, `git lfs version` |
| JDK | 21 | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| MySQL Server + MySQL client | 8.0 | `mysql --version` |
| Python | 3.11+ | `python --version` |
| Node.js (chỉ để chạy test viết tay) | 18+ | `node --version` |

MySQL Server phải đang chạy. Trên máy local có thể dùng tài khoản `root`.

Nếu lệnh `mysql` báo "not recognized", dùng đường dẫn đầy đủ
`& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'` thay cho `mysql` ở các bước dưới.

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
| `jlas-dictionary-test-seed-20261016.sql.gz` | Schema + dữ liệu từ điển | ~37 MB |
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

## 5. Cập nhật schema mới (bắt buộc)

File seed được tạo ngày 16/10, chưa có 3 bảng mà các tính năng mới cần (từ trái nghĩa, ý kiến
đóng góp, trọng âm). Thiếu bước này thì app báo lỗi `Schema-validation` khi khởi động.
Chạy lần lượt (mỗi lệnh hỏi mật khẩu MySQL):

```powershell
Get-Content sql/migrations/008_vocabulary_antonym.sql | mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test
Get-Content sql/migrations/009_dictionary_contributions.sql | mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test
Get-Content sql/migrations/010_vocabulary_pitch_accent.sql | mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test
```

Các file migration chạy lại nhiều lần không lỗi. Sau bước này các bảng mới còn trống: từ trái
nghĩa và trọng âm chỉ hiện khi đã nạp dữ liệu ở bước 9 (tuỳ chọn).

## 6. Tạo file cấu hình local

`src/main/resources/application-local.yaml` chứa mật khẩu và khoá API nên **không có trong Git**
(đã nằm trong `.gitignore`, đừng commit). Tạo file đó với nội dung mẫu bên dưới, rồi sửa:

- `spring.datasource.password`: mật khẩu MySQL của bạn.
- `jlas.auth.jwt-secret` và `app.jwt.secret`: chuỗi ngẫu nhiên bất kỳ, **ít nhất 32 ký tự**.

Các khoá API để trống vẫn chạy được; khi đó tính năng tương ứng không hoạt động hoặc dùng dữ
liệu demo. Xin trưởng nhóm khoá thật nếu cần thử:

| Khoá | Dùng cho | Để trống thì |
|---|---|---|
| `azure.speech.pronunciation.key` | Nút loa phát âm ở trang tra cứu (Azure Neural TTS) và chấm phát âm Shadowing | Bấm loa báo "Chưa cấu hình dịch vụ phát âm" |
| `openrouter.api.key` | Các tính năng AI | Tắt tính năng AI |
| `deepgram.api.key` | Phụ đề video Shadowing | Không tạo được phụ đề |
| `spring.mail.*` | Gửi mã OTP qua mail | Không gửi được mail |
| `fish.audio.api.key` | Giọng đọc AI Call | Tắt giọng đọc AI Call |

<details>
<summary>Nội dung mẫu <code>application-local.yaml</code> (bấm để mở)</summary>

```yaml
# Cấu hình local cho J-LAS. File này bị gitignore: không commit.
jlas.auth.jwt-secret: 'doi-thanh-chuoi-ngau-nhien-it-nhat-32-ky-tu-cho-moi-may'
jlas.auth.access-token-ttl: '7200'
jlas.auth.refresh-token-ttl: '604800'
jlas.auth.verification-token-ttl: '900'
upload.path.avatar: 'uploads/avatar'
logging.level.com.jlas: 'INFO'
server.port: '8080'
spring.mvc.async.request-timeout: '120000'
spring.application.name: 'J-LAS'
server.servlet.encoding.charset: 'UTF-8'
server.servlet.encoding.enabled: 'true'
server.servlet.encoding.force: 'true'
spring.messages.encoding: 'UTF-8'
spring.datasource.url: 'jdbc:mysql://localhost:3306/jlas_dictionary_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8'
spring.datasource.username: 'root'
spring.datasource.password: '<mat-khau-mysql-local>'
spring.datasource.driver-class-name: 'com.mysql.cj.jdbc.Driver'
spring.jpa.hibernate.ddl-auto: 'validate'
spring.jpa.show-sql: 'false'
spring.jpa.open-in-view: 'false'
spring.jpa.properties.hibernate.format_sql: 'true'
spring.servlet.multipart.max-file-size: '100MB'
spring.servlet.multipart.max-request-size: '100MB'
app.jwt.secret: 'doi-thanh-chuoi-ngau-nhien-it-nhat-32-ky-tu-cho-moi-may'
app.jwt.access-token-expiration-ms: '900000'
app.jwt.refresh-token-expiration-ms: '604800000'
app.auth.verification-token-expiration-minutes: '15'
app.auth.reset-token-expiration-minutes: '30'
app.auth.frontend-base-url: 'http://localhost:3000'
app.auth.verification-mode: 'OTP'
app.google.client-id: ''
spring.mail.host: 'smtp.gmail.com'
spring.mail.port: '587'
spring.mail.username: '<gmail-gui-otp>@gmail.com'
spring.mail.password: '<app-password-gmail>'
spring.mail.properties.mail.smtp.auth: 'true'
spring.mail.properties.mail.smtp.starttls.enable: 'true'
spring.mail.properties.mail.smtp.starttls.required: 'true'
app.mail.from: 'J-LAS <no-reply@localhost>'
spring.thymeleaf.cache: 'false'
spring.thymeleaf.prefix: 'classpath:/templates/'
spring.thymeleaf.suffix: '.html'
spring.devtools.restart.enabled: 'true'
spring.devtools.restart.additional-paths: 'src/main/resources'
spring.devtools.livereload.enabled: 'true'
spring.web.resources.cache.period: '0'
spring.web.resources.chain.cache: 'false'
openrouter.api.key: ''
openrouter.api.url: 'https://openrouter.ai/api/v1'
openrouter.site.url: 'http://localhost:8080'
openrouter.site.name: 'J-LAS AI Call'
openrouter.model: 'openai/gpt-5.6-luna'
fish.audio.api.key: ''
fish.audio.api.url: 'https://api.fish.audio'
fish.audio.model: 's2.1-pro-free'
fish.audio.voice.female: 'fe0ebac8f8fd41ccbef176a9ad53eafd'
fish.audio.voice.male: '650cf9da19ec4f758eeedb04ebee4ee5'
azure.speech.pronunciation.key: ''
azure.speech.pronunciation.region: 'japaneast'
azure.speech.pronunciation.locale: 'ja-JP'
azure.speech.pronunciation.max-audio-bytes: '5242880'
azure.speech.pronunciation.max-audio-seconds: '30'
azure.speech.pronunciation.requests-per-minute: '20'
azure.speech.tts.key: '${azure.speech.pronunciation.key}'
azure.speech.tts.region: '${azure.speech.pronunciation.region}'
azure.speech.tts.voice: 'ja-JP-NanamiNeural'
azure.speech.tts.output-format: 'audio-24khz-48kbitrate-mono-mp3'
dictionary.tts.cache-dir: 'uploads/tts-cache'
dictionary.tts.max-chars: '200'
dictionary.tts.requests-per-minute: '30'
pronunciation.storage.provider: 'local'
pronunciation.storage.local.root: 'storage/pronunciation'
video.max.duration.seconds: '0'
processing.ai.max.duration.seconds: '1200'
openrouter.shadowing.model: 'openai/gpt-5.6-luna'
openrouter.shadowing.max-tokens: '8192'
openrouter.shadowing.exercise.model: '${OPENROUTER_SHADOWING_EXERCISE_MODEL:${openrouter.shadowing.model}}'
shadowing.practice.ai.max-subtitles-per-session: '${SHADOWING_PRACTICE_AI_MAX_SUBTITLES:120}'
processing.ai.batch.size: '6'
processing.ai.json.max-retries: '2'
openrouter.temperature: '0'
openrouter.top-p: '0.9'
moderation.enabled: 'true'
moderation.image.enabled: 'true'
moderation.image.model: 'google/gemma-3-12b-it'
moderation.image.fallback-model: 'openai/gpt-4o-mini'
moderation.image.timeout-ms: '45000'
moderation.image.max-tokens: '256'
moderation.video.enabled: 'true'
moderation.video.quick-scan: 'true'
moderation.video.targeted-scan: 'true'
moderation.video.frame-max-width: '768'
moderation.video.temp-dir: 'uploads/moderation-frames/'
moderation.rate-limit.comment-per-minute: '30'
moderation.rate-limit.image-per-hour: '40'
moderation.rate-limit.publish-per-hour: '20'
deepgram.api.key: ''
deepgram.upload.max-retries: '5'
deepgram.upload.delay-between-ms: '2000'
deepgram.upload.split.min-seconds: '60'
deepgram.http.connect-timeout-ms: '60000'
deepgram.http.read-timeout-ms: '600000'
ffmpeg.audio.bitrate: '32k'
ffmpeg.bin.path: 'ffmpeg'
upload.path.video: 'uploads/video/'
upload.path.audio: 'uploads/audio/'
upload.path.thumbnail: 'uploads/thumbnails/'
upload.path.flashcard: 'uploads/flashcard/'
yt-dlp.bin.path: 'yt-dlp'
yt-dlp.js-runtime: "node"
youtube.max.duration.no-subtitle.seconds: '300'
logging.level.admin.jlas: 'INFO'
logging.level.admin.jlas.modules.auth: 'INFO'
logging.level.admin.jlas.modules.auth.security: 'INFO'
logging.level.admin.jlas.modules.amon: 'INFO'
logging.level.admin.jlas.modules.shadowing: 'INFO'
logging.level.admin.jlas.modules.aicall: 'INFO'
aicall.runtime.ttl-minutes: '120'
aicall.runtime.max-sessions: '10000'
logging.level.admin.jlas.modules.flashcard: 'INFO'
logging.level.admin.jlas.modules.retention: 'INFO'
logging.level.org.springframework.security: 'WARN'
logging.level.org.springframework.security.config.annotation.authentication.configuration.InitializeUserDetailsBeanManagerConfigurer: 'ERROR'
retention.zone-id: 'Asia/Ho_Chi_Minh'
retention.streak-reset-cron: '0 5 0 * * *'
retention.ebbinghaus-reminder-cron: '0 0 8 * * *'
data-retention.enabled: '${DATA_RETENTION_ENABLED:true}'
data-retention.dry-run: '${DATA_RETENTION_DRY_RUN:true}'
data-retention.cleanup-cron: '0 30 2 * * *'
data-retention.zone-id: 'Asia/Ho_Chi_Minh'
data-retention.auth-token-grace-days: '7'
data-retention.conversation-session-days: '90'
data-retention.batch-size: '1000'
data-retention.max-batches-per-run: '100'
server.compression.enabled: 'true'
server.compression.mime-types: 'application/json,application/xml,text/html,text/xml,text/plain,application/javascript,text/css'
server.compression.min-response-size: '1024'
game.arena.countdown-seconds: '3'
game.arena.reconnect-grace-seconds: '25'
game.arena.room-idle-timeout-minutes: '30'
game.arena.max-rooms: '5000'
game.arena.sweep-interval-ms: '15000'
game.arena.rate-limit.ready-per-minute: '40'
game.arena.rate-limit.settings-per-minute: '60'
game.arena.rate-limit.start-per-minute: '15'
game.arena.rate-limit.join-per-minute: '30'
game.arena.rate-limit.flip-per-minute: '120'
game.arena.rate-limit.answer-per-minute: '180'
game.arena.max-ws-payload-bytes: '8192'
game.memory.reveal-delay-ms: '900'
game.memory.session-idle-timeout-minutes: '20'
game.memory.max-sessions: '5000'
game.air-defense.session-idle-timeout-minutes: '20'
game.air-defense.max-sessions: '5000'
game.demo.seed-enabled: 'false'
logging.level.admin.jlas.modules.game: 'INFO'
logging.level.org.springframework.web.socket: 'WARN'
mission.dev-tools: 'true'
```

</details>

## 7. Tạo tài khoản đăng nhập để test (một lần)

Database từ seed không có người dùng, mà trang từ điển yêu cầu đăng nhập:

```powershell
Get-Content sql/seeds/local_test_account.sql | mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test
```

Tài khoản: **`tester@jlas.local` / `Test@12345`**.

## 8. Chạy ứng dụng

```powershell
mvn spring-boot:run
```

Khi log có `Started JlasApplication` và `Đã dựng chỉ mục nét KanjiVG`, mở
<http://localhost:8080/login>, đăng nhập rồi vào mục **Tra cứu** (hoặc mở thẳng
<http://localhost:8080/search/word/学校>). Kiểm tra nhanh:

| Thử | Kết quả mong đợi |
|---|---|
| Gõ `学校` + Enter | Thẻ từ chữ xanh, dòng 「がっこう」「HỌC GIÁO」, nghĩa "học đường; học hiệu; nhà trường" |
| Gõ `mèo` | Ra 猫 |
| Bấm tab **Hán tự** khi đang tra `学校` | Trang Hán tự có hoạt ảnh thứ tự nét; từ khoá giữ nguyên |
| Gõ `ひ` rồi bấm tab **Hán tự** | Ra Kanji đọc là ひ (danh sách gồm 火, 日, 比...) |
| Bấm biểu tượng viết tay, vẽ một chữ | Gợi ý Kanji hiện sau mỗi nét |
| Bấm biểu tượng bộ thủ, chọn 亻 rồi 木 | 41 chữ: 休, 体, 保... |
| Ở thẻ từ, gửi một ý kiến đóng góp | Ý kiến hiện trong danh sách, có nút like/dislike |
| Bấm loa | Phát âm giọng Azure (cần khoá Azure ở bước 6) |

Tab **Mẫu câu**, **Ngữ pháp**, **Nhật - Nhật** hiện "Không có kết quả nào được hiển thị": đang
phát triển, không phải lỗi.

## 9. Nạp từ trái nghĩa và trọng âm (tuỳ chọn)

Không làm bước này app vẫn chạy; chỉ là mục "Từ trái nghĩa" và trọng âm sẽ không hiện. Dữ liệu
nguồn khá lớn nên **không có trong Git**, tải về đặt vào thư mục `data/`:

| Dữ liệu | Tải ở đâu | Đặt vào |
|---|---|---|
| JMdict (từ trái nghĩa) | <https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html>, giải nén bản `JMdict` | `data/JMdict.xml` |
| UniDic 現代書き言葉 2025.12 (trọng âm, nguồn chính) | <https://clrd.ninjal.ac.jp/unidic/>, giải nén | `data/unidic-cwj-202512/` (có file `sys.dic`) |
| Kanjium (trọng âm, bổ sung) | <https://github.com/mifunetoshiro/kanjium>, tải zip và giải nén | `data/kanjium-master/kanjium-master/` |

Các thư mục này đã nằm trong `.gitignore`. Sau khi có dữ liệu (vẫn giữ các biến `DB_*` ở bước 4,
thêm `$env:DB_NAME = 'jlas_dictionary_test'`):

```powershell
cd scripts/dictionary_import
..\..\.venv\Scripts\python.exe import_antonyms.py --file ..\..\data\JMdict.xml
..\..\.venv\Scripts\python.exe import_pitch_accent.py --unidic-dir ..\..\data\unidic-cwj-202512 --kanjium-file ..\..\data\kanjium-master\kanjium-master\data\source_files\raw\accents.txt
cd ..\..
```

Cả hai lệnh chạy lại nhiều lần không tạo dữ liệu trùng. Trọng âm của Kanjium dùng giấy phép
CC BY-SA 4.0, trang tra cứu đã ghi nguồn bên dưới phần trọng âm.

## 10. Chạy test (tuỳ chọn)

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/dictionary_import/tests -t .
node --test scripts/handwriting.test.mjs
```

Thư mục test Java (`src/test`) đã được gỡ khỏi nhánh này, nên `mvn test` không chạy test nào.

## 11. Lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `Port 8080 was already in use` | Đang có một bản app khác chạy. Tắt nó, hoặc: `Get-NetTCPConnection -LocalPort 8080 -State Listen \| ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }` |
| `Schema-validation: missing table [vocabulary_antonym]` (hoặc `content_comment_vote`, `vocabulary_pitch_accent`) | Chưa chạy bước 5. |
| `Schema validation: ... jlpt_exam ...` hoặc thiếu cột `han_viet` | `spring.datasource.url` đang trỏ sai database (ví dụ `jlas` dựng từ nhánh khác). Nhánh này dùng `jlas_dictionary_test`. |
| `Unresolved compilation problems` khi khởi động | VS Code (Java extension) ghi đè file `.class`. Xoá thư mục `target/classes/com` rồi chạy lại `mvn spring-boot:run`. |
| `jlas.auth.jwt-secret must contain at least 32 characters` | Sửa `jwt-secret` trong `application-local.yaml` đủ 32 ký tự. |
| Bấm loa báo "Chưa cấu hình dịch vụ phát âm" | Chưa điền `azure.speech.pronunciation.key` ở bước 6. |
| Viết tay báo "Tính năng viết tay tạm thời chưa sẵn sàng" | Chưa giải nén SVG: chạy lại bước 4. |
| Trang Hán tự không có hoạt ảnh nét cho chữ hiếm | Thiếu `stroke-order-extra`: chạy lại bước 4. (Khoảng 27 chữ cực hiếm không có dữ liệu nét là bình thường.) |
| Đăng nhập báo sai mật khẩu | Chưa chạy bước 7. |
| `import_pitch_accent.py` báo `Apply migration 010 first` | Chưa chạy bước 5. |

## Tài liệu liên quan

- `docs/HUONG-DAN-NAP-DU-LIEU-TU-DIEN-TEST.md`: chi tiết về seed và cách kiểm tra dữ liệu.
- `docs/dictionary-import.md`: cách dựng lại dữ liệu từ điển từ nguồn gốc (chỉ người bảo trì dữ liệu cần).
