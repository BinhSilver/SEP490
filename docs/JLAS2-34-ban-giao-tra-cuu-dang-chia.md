# JLAS2-34: Bàn giao tra cứu dạng chia và làm sạch dữ liệu Nhật-Việt

Cập nhật: 30/10/2026. Nhánh: `feat/JLAS2-34-dictionary-search`.

Tài liệu này ghi lại những gì đã làm ở phiên 28–30/10 và những việc còn tồn đọng, để làm tiếp
trên máy khác. Cách cài dự án từ đầu xem [HUONG-DAN-CHAY-DU-AN.md](HUONG-DAN-CHAY-DU-AN.md).

## Bắt đầu trên máy mới

1. Lấy code và dữ liệu:

   ```powershell
   git fetch origin
   git checkout feat/JLAS2-34-dictionary-search
   git pull
   git lfs pull
   ```

2. Nạp seed nếu máy chưa có database `jlas_dictionary_test`. Làm theo bước 3–6 trong
   [HUONG-DAN-CHAY-DU-AN.md](HUONG-DAN-CHAY-DU-AN.md).

3. **Bắt buộc:** chạy migration 007, vì seed `20261016` chưa có bản sửa này:

   ```powershell
   Get-Content sql/migrations/007_drop_mangled_vietnamese_glosses.sql |
     mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test
   ```

   Kiểm tra: `SELECT COUNT(*) FROM vocabulary_gloss;` phải ra **347139** (trước khi chạy là
   347161). Chạy lại nhiều lần vẫn an toàn.

4. Chạy app bằng `mvn spring-boot:run`, mở <http://localhost:8080/login>. Tài khoản test:
   `tester@jlas.local` / `Test@12345`.

## Đã làm

### 1. Tra theo dạng chia ra thể từ điển

Trước đây, gõ 行きます, 行った hay 食べます thì không ra 行く, 食べる. Code chỉ có các kiểu khớp
nguyên chuỗi, khớp phần đầu và tách cụm, không có bước đưa dạng chia về thể từ điển.

| Gõ vào | Kết quả bây giờ |
|---|---|
| `行き` | 行き (danh từ), ngay sau là **行く**, rồi tới 行き来… |
| `行きます` / `行った` / `行かない` | **行く** đứng đầu (`行った` ra thêm 行う, vì có thể đọc là おこなった) |
| `食べませんでした`, `たべます` | 食べる |
| `飲みました`, `書いて`, `来ます` | 飲む, 書く, 来る |
| `勉強しました`, `高かった` | 勉強, 高い |

- [VerbDeinflector.java](../src/main/java/com/jlas/dictionary/util/VerbDeinflector.java) là bộ
  luật hậu tố, kiểu Yomitan. Nó bóc dần từng lớp chia, ví dụ 食べています → 食べてい → 食べている
  → 食べて → 食べる.
- Trong [DictionaryServiceImpl.java](../src/main/java/com/jlas/dictionary/service/impl/DictionaryServiceImpl.java),
  hàm `collectDictionaryForms` chỉ nhận một ứng viên khi từ đó có trong từ điển **và** có từ loại
  khớp (v1, v5*, vk, vs*, adj-i). Việc kiểm tra dùng một truy vấn IN
  (`findPartsOfSpeechByExpressionIn` / `findPartsOfSpeechByReadingIn` trong `VocabularyEntryRepository`).
- Thứ tự kết quả: từ trùng khớp → thể từ điển (tối đa 10 từ) → khớp phần đầu → chứa → tách cụm.
- Hai trường hợp không chạy bước này:
  - Query 1 chữ Kanji.
  - Query kana khi đã có từ trùng khớp, để `まえ` không ra `まう`.
- Phần này chỉ đổi cách tìm kiếm, **không** sửa dữ liệu trong DB.

### 2. Xoá 22 nghĩa bị lỗi mã hoá

Dữ liệu nguồn Mazii có một số nghĩa bị lặp lại với chữ có dấu biến thành `?`, ví dụ `l? xo` nằm
ngay cạnh `lò xo`. Có **22 nghĩa trong 17 mục**, toàn là mục bắt đầu bằng 発: 発条, 発信, 発情,
発酵…

- **Số liệu đã sửa lại:** con số 69 báo lúc đầu là sai. Phần lớn trong đó là câu hỏi thật
  ("bạn khỏe không?", "what?") và đã được giữ nguyên.
- **Tiêu chí:** một nghĩa chỉ bị xoá khi giống một nghĩa có dấu khác trong cùng mục, sau khi thay
  chữ có dấu bằng `?` (độ giống ≥ 0,8).
- **Migration:** [007_drop_mangled_vietnamese_glosses.sql](../sql/migrations/007_drop_mangled_vietnamese_glosses.sql)
  xoá các nghĩa lỗi, đánh số lại `sense_order` và tính lại cột tóm tắt `meaning`. Đã chạy trên DB
  của máy cũ.
- **Importer:** hàm `drop_mangled_meanings` trong
  [yomitan.py](../scripts/dictionary_import/jlas_import/yomitan.py) lọc các nghĩa này ngay lúc
  import, nên import lại không sinh ra nữa.

### 3. Danh sách mục là dạng chia hoặc là câu (chờ duyệt, chưa xoá gì)

File [data/dictionary-review/muc-dang-chia-va-cau.csv](../data/dictionary-review/muc-dang-chia-va-cau.csv)
có 314 dòng, mở được bằng Excel. Cách tạo lại xem [scripts/dictionary_review/README.md](../scripts/dictionary_review/README.md).

| Nhóm | Số mục | Gợi ý |
|---|---:|---|
| Dạng chia của từ đã có | 148 | 13 mục dạng ます (貸します, 食べません…) gợi ý **Xoá**, còn lại **Xem lại** |
| Thể khả năng / bị động / sai khiến | 71 | Xem lại |
| Câu / cụm lịch sự (ありがとうございます, いくらですか…) | 95 | Xem lại |

Lưu ý khi duyệt:

- Nhiều mục `〜した` mang nghĩa tính từ riêng (傑出した "nổi bật", 充実した "đầy đủ"). Xoá đi sẽ
  mất nghĩa đó.
- 恐れ入ります là câu cố định, dù nằm trong nhóm gợi ý xoá.
- Nhóm câu còn lẫn vài danh từ viết kana có đuôi ません, như だるません (sà lan) và てんません (thuyền).

### 4. Khôi phục file bị hỏng trên máy cũ

- `data/dictionary-seed/jlas-dictionary-test-seed-20261016.sql.gz` bị một công cụ mở như file văn
  bản rồi lưu lại. Mọi byte không hợp lệ bị thay bằng `U+FFFD`, nên file phình từ 37 MB lên 68 MB
  và không giải nén được nữa. Đã khôi phục bản gốc từ LFS, checksum `8fd4ae81…` khớp `manifest.json`.
- `huong-dan-content-audio.md` bị xoá vào Thùng rác. Đã chép lại từ bản giống hệt trong
  `Downloads`. File này **không được commit**: nó chưa từng nằm trong Git và không thuộc phần
  việc này. Nếu cần dùng trên máy mới thì chép tay.

## Còn tồn đọng

### Việc cần làm tiếp

1. **Duyệt CSV, rồi viết migration 008** để xoá các mục đã chốt. Có thể theo khuôn của 007: liệt
   kê id cụ thể, kèm điều kiện an toàn, chạy lại nhiều lần được. Chỉ cần
   `DELETE FROM vocabulary_entry WHERE id IN (...)`: mọi khoá ngoại tới `vocabulary_entry` và
   `vocabulary_sense` đều là `ON DELETE CASCADE`, nên sense, gloss, từ loại, `vocabulary_kanji`,
   `dictionary_favorite`, `content_vocabulary`… tự xoá theo. Riêng `dictionary_search_history`
   được đặt về NULL. Cần kiểm tra trước: mục nào có trong `content_vocabulary` (giáo trình) thì
   không nên xoá.
2. **Xuất lại seed** sau khi chạy 007 và 008, để cả nhóm nạp được bản sạch, không phải chạy
   migration tay. Dùng `python scripts/dictionary_import/export_seed.py --snapshot <tên>`; công cụ
   này cập nhật luôn `manifest.json`. Sau đó sửa tên file seed trong `HUONG-DAN-CHAY-DU-AN.md`.
3. **Bổ sung các dạng chia còn thiếu** vào `VerbDeinflector`. Các dạng sau đã thử và chưa ra thể
   từ điển:

   | Dạng | Ví dụ |
   |---|---|
   | ないで | 食べないで |
   | ちゃう / じゃう | 食べちゃった, 飲んじゃう |
   | すぎる | 食べすぎる |
   | そう (dáng vẻ) | 食べそう |
   | てみる / てもらう / てあげる / てくれる / てくる / ていく | 食べてみる |
   | たがる | 食べたがる |
   | Tính từ đuôi な | 静かでした → 静か |
   | Kính ngữ お〜になる | お読みになる |

   Mỗi dạng chỉ cần thêm luật trong `buildRules()` và một dòng vào `VerbDeinflectorTest`.

### Vấn đề dữ liệu chưa xử lý

- **87.085 mục không có từ loại nào.** Bước tìm theo dạng chia không xác nhận được các mục này là
  động từ, nên chúng không bao giờ được trả về như thể từ điển.
- **Còn sót nghĩa tiếng Anh**, ví dụ 噛ます, であります, 申し訳ございません, 年の行った ("older, elderly").
- **Bản quyền dữ liệu Mazii / KanjiDictVN chưa được xác minh.** Bảng `dictionary_source` đang để
  `license_name` trống.

### Test và môi trường

- **Test:** 149 test Java của phần từ điển chạy được, 148 qua. 60 test Python của importer đều qua
  (1 test bỏ qua). 61 test của `VerbDeinflectorTest` và 4 test của
  `DictionaryServiceImplInflectionSearchTest` đều qua.
- **Test lỗi:** `HandwritingCorpusAccuracyTest.recognitionOverTheWholeIndexIsFastEnoughForRealtime`
  lỗi trên máy cũ ngày 30/10. Trường hợp 20 nét đo được 170–199 ms, trong khi ngưỡng là 100 ms.
  Test này đã qua ngày 28/10, và phần viết tay không có thay đổi nào. Nhiều khả năng đây là test
  đo thời gian nên phụ thuộc tình trạng máy. Cần chạy lại trên máy mới: nếu vẫn lỗi thì xem lại
  ngưỡng hoặc hiệu năng `StrokeMatcher`.
- **Maven và VS Code:** chạy `mvn` song song với VS Code Java extension có thể bị treo hoặc làm hỏng
  `target/classes`. Nếu gặp lỗi "Unresolved compilation problems", xoá `target/classes/com/**/*.class`
  rồi chạy `mvn -o compile`. Nếu app đang chạy bằng devtools, biên dịch sẽ làm app tự khởi động lại.
- **Không mở file `.gz` / `.zip` trong `data/dictionary-seed/` bằng trình soạn thảo**, cũng như
  không chạy script đọc chúng ở chế độ văn bản. Đó là cách seed bị hỏng ở mục 4.
- **Nhánh đang chậm 570 commit so với `origin/develop`.** Develop có nhận diện viết tay riêng, và
  database `jlas` của nhóm được dựng từ develop, chưa có migration 005–007. Việc port phần từ điển
  sang develop và migrate `jlas` vẫn chưa chốt. Chạy nhánh này với database `jlas` sẽ lỗi kiểm tra
  schema.

## Các file liên quan

| File | Vai trò |
|---|---|
| `src/main/java/com/jlas/dictionary/util/VerbDeinflector.java` | Luật đưa dạng chia về thể từ điển |
| `src/main/java/com/jlas/dictionary/service/impl/DictionaryServiceImpl.java` | `collectDictionaryForms`, nối vào tìm kiếm |
| `src/main/java/com/jlas/dictionary/repository/VocabularyEntryRepository.java` | Truy vấn từ loại theo danh sách từ |
| `src/test/java/com/jlas/dictionary/util/VerbDeinflectorTest.java` | 61 test dạng chia |
| `src/test/java/com/jlas/dictionary/service/impl/DictionaryServiceImplInflectionSearchTest.java` | Thứ tự kết quả, lọc theo từ loại, kana |
| `sql/migrations/007_drop_mangled_vietnamese_glosses.sql` | Xoá 22 nghĩa lỗi mã hoá |
| `scripts/dictionary_import/jlas_import/yomitan.py` | `drop_mangled_meanings` trong importer |
| `scripts/dictionary_review/` | Công cụ tạo danh sách duyệt |
| `data/dictionary-review/muc-dang-chia-va-cau.csv` | 314 mục chờ duyệt |
