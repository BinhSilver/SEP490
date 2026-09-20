# Plan: Mở rộng catalog Từ vựng & Kanji N5–N1 (nguồn OpenJLPT) + retag câu hỏi Smart Test

> Trạng thái: **Phase 0–5 HOÀN TẤT** (schema + vocab + kanji + supplement + retag,
> verify script 0 unmatched). Còn **Phase 6** (load DB + smoke test web + commit).
> Runbook load/verify: xem `docs/jlas2-smart-test-catalog-runbook.md`.
> Đã gộp seed: các file rời ở Phase 2–5 đã được gộp vào `db/seed_data_jlas.sql` +
> `questionbank.sql` (xem runbook §1) — không còn file seed rời.
> Liên quan: nhánh `feat/JLAS-W0090-smart-test-fix`; task con **[JLAS-W0091] Data: Chuẩn hóa metadata và ngân hàng câu hỏi mẫu**.
> Smart Test → Warm-up (JLAS-W0108 / JLAS2-129).
> Nguồn dữ liệu: [OpenJLPT](https://github.com/evanclan/OpenJLPT) — CC BY-SA 4.0.

---

## 1. Mục tiêu & phạm vi

**Vấn đề:** catalog hiện chỉ phủ N5/N4 (~1.756 từ + 533 kanji), trong khi 1.500 câu
kanji/vocab của Smart Test trải N5–N1. Kết quả là không đủ dữ liệu để map câu hỏi
về "kiến thức cụ thể" cho Warm-up (Tầng 2 – Knowledge Selection).

**Mục tiêu:**
1. Nhập OpenJLPT làm catalog **từ vựng** (`vocabulary_entry`) + **kanji** (`kanji` + `kanji_reading`).
2. Bổ sung **72 từ riêng biệt** còn thiếu (đo được ở mục 2).
3. Retag **1.500 câu** kanji/vocab → liên kết tới từ cụ thể (`vocabulary_entry`).

**Ngoài phạm vi plan này (làm giai đoạn sau):**
- **Ngữ pháp** — OpenJLPT chỉ có 20 mẫu/level; cần nguồn danh sách mẫu JLPT N3–N1 riêng.
- **Logic Warm-up Tầng 1 (Skill) / Tầng 2 (Knowledge)** — plan này chỉ dọn data, không viết service.

---

## 2. Kết quả test khớp (đã đo — `tools/match_lexeme_openjlpt.py`)

Mẫu: **1.500 câu** (750 KANJI + 750 VOCAB, 300 câu/level N5–N1). OpenJLPT: **8.089 từ** riêng biệt.

| Tiêu chí | Số câu | % |
|---|---|---|
| Khớp chính xác (word + reading) | 1.257 | 83,8% |
| Khớp word (reading khác/rỗng) | 21 | 1,4% |
| **Đã phủ** | **1.278** | **85,2%** |
| Không khớp | 222 | 14,8% |

**Độ phủ theo level:** N2 97% · N3 91% · N4 83% · N1 81% · N5 74%.

**72 từ riêng biệt còn thiếu** (222 câu chung 72 từ vì 1 từ test nhiều câu):

| Level | Từ thiếu | Ví dụ |
|---|---|---|
| N5 | 25 | 飲みます・買います・西口・東京・大雨 (dạng ます + từ ghép) |
| N4 | 17 | 主人・朝食・夕食・駅員・会社員 |
| N3 | 8 | ごみ袋・翌日・職場・残業・求人 |
| N2 | 3 | 賃貸・庭園・振込 |
| N1 | 19 | 抜擢・遮断・陥る・示唆・脆弱・折衝 |

3 nhóm: (a) động từ dạng **ます** — khắc phục bằng chuẩn hoá về dạng từ điển;
(b) từ ghép trong suốt; (c) từ N1 thật sự thiếu (danh sách Waller không trọn vẹn).

---

## 3. Quyết định cần chốt TRƯỚC khi làm

| # | Quyết định | Trạng thái |
|---|---|---|
| D1 | Ticket number cho thay đổi schema | ✅ **[JLAS-W0091]** (Data: Chuẩn hóa metadata và ngân hàng câu hỏi mẫu) |
| D2 | Ngôn ngữ nghĩa | ✅ **Giữ tiếng Anh** (từ OpenJLPT); dịch Việt giai đoạn sau |
| D3 | License CC BY-SA 4.0 + ghi attribution trong header seed | ✅ Đồng ý |
| D4 | Cách xử lý 72 từ thiếu | ✅ stem dạng ます → khớp dạng từ điển; từ N1 bổ sung tay |
| D5 | Câu KANJI là *word-level* | ✅ Retag theo `vocabulary_entry` (không phải bảng `kanji`) |

---

## 4. Thiết kế dữ liệu

### 4.1 Schema — thêm 1 cột FK (đối xứng `grammar_point_id`)

`assessment_question` hiện có `grammar_point_id` cho câu GRAMMAR nhưng **chưa có** cột
tương ứng cho câu KANJI/VOCABULARY (từ nằm trong `context_json.lexeme` — không chuẩn,
comment schema ghi rõ "Metadata hiem/khong dung de loc").

Thêm vào `assessment_question`:

```sql
vocabulary_entry_id  BIGINT UNSIGNED NULL,  -- [JLAS2-XX] Từ vựng cụ thể (nullable; chỉ KANJI/VOCABULARY).
```

kèm index + FK (inline, vì `vocabulary_entry` tạo trước `assessment_question`):

```sql
KEY idx_assessment_question_vocab (vocabulary_entry_id),
CONSTRAINT fk_assessment_question_vocabulary FOREIGN KEY (vocabulary_entry_id)
    REFERENCES vocabulary_entry(id) ON DELETE SET NULL,
```

Chuỗi liên kết hoàn chỉnh, đối xứng 2 skill:

- **Grammar**: `assessment_question.grammar_point_id` → `grammar_point.lesson_id` → `lesson`.
- **Kanji/Vocab**: `assessment_question.vocabulary_entry_id` → `content_vocabulary` →
  `content_item` → `lesson_section` → `lesson`.

*(Bảng `content_vocabulary` đã tồn tại sẵn — chuỗi vocab→lesson đã có, chỉ thiếu nốt đầu câu hỏi.)*

### 4.2 Mapping OpenJLPT → schema

**Từ vựng** (`vocab-n1..n5.csv`: `word, reading, meanings, level, example_ja, example_en`):

| OpenJLPT | `vocabulary_entry` | Ghi chú |
|---|---|---|
| `word` | `expression` | |
| `reading` | `reading` | rỗng khi từ thuần kana |
| `word`/`reading` | `normalized_expression`/`normalized_reading` | NFKC (xác nhận hàm ở 5.0) |
| `meanings` | `meaning` | tiếng Anh, nối bằng `; ` |
| `level` | `jlpt_level` | |
| — | `usage_scope` | `'DICTIONARY'` |
| — | `dictionary_status` | `'PUBLISHED'` |
| — | `word_type`, `example_*`, `reading_romaji` | bỏ trống / NULL |

**Kanji** (`kanji-n1..n5.csv`: `character, level, strokes, grade, freq, onyomi, kunyomi, meanings`):

| OpenJLPT | bảng | Ghi chú |
|---|---|---|
| `character` | `kanji.character` | |
| — | `kanji.unicode_code` | `format(ord(ch), '04X')` (vd 間 → `9593`) |
| `strokes` | `kanji.stroke_count` | |
| `level` | `kanji.jlpt_level` | |
| `meanings` | `kanji.meaning_vi` | tiếng Anh (đặt tạm, đổi tên nghĩa sau nếu dịch) |
| `onyomi[]` | `kanji_reading` (type `ON`) | tách theo `; ` |
| `kunyomi[]` | `kanji_reading` (type `KUN`) | tách theo `; ` |
| — | `radical_id`, `han_viet`, `stroke_svg_uri` | NULL |

---

## 5. Các bước thực hiện (mỗi bước có verify riêng)

### Phase 0 — Chuẩn bị
- **Xác nhận hàm chuẩn hoá** ✅ `normalized = katakana→hiragana(NFKC(text))` —
  verified 1755/1755 dòng `vocabulary_entry` hiện có. Không có hàm Java (tính sẵn
  trong seed); script sinh seed phải lặp lại đúng quy tắc. (`ベトナム`→`べとなむ`, `～さん`→`~さん`.)
- Viết 1 script `tools/gen_openjlpt_seed.py` đọc CSV OpenJLPT → sinh SQL idempotent.
- Chốt vị trí lưu dữ liệu nguồn: commit 10 file CSV (`data/csv/vocab-*.csv`, `kanji-*.csv`)
  vào `tools/openjlpt-data/` để tái sinh seed không phụ thuộc clone tạm.

### Phase 1 — Schema
- Sửa `jlas_schema.sql` (thêm cột + index + FK inline, kèm note `[JLAS2-XX]`).
- Tạo `db/jlas_schema_upgrade_JLAS-XX.sql` (`ALTER TABLE ... ADD COLUMN/INDEX/CONSTRAINT`) cho DB đang chạy.
- **Verify:** chạy upgrade trên DB dev; `SHOW COLUMNS` thấy cột mới; trigger
  `trg_assessment_question_legacy_bridge` vẫn hoạt động (kiểm tra không `SELECT *`).

### Phase 2 — Seed từ vựng OpenJLPT
- Sinh `db/seed_openjlpt_vocab.sql` (idempotent `INSERT ... AS new ON DUPLICATE KEY UPDATE`).
- Header file ghi block attribution CC BY-SA 4.0 (OpenJLPT + JMdict/EDRDG + KANJIDIC2 + Waller + Tatoeba).
- **Verify:** `SELECT COUNT(*) FROM vocabulary_entry WHERE usage_scope='DICTIONARY'` ≈ 8.089;
  unique key `(normalized_expression, normalized_reading)` không lỗi; không đè `usage_scope='TEXTBOOK'` cũ.

### Phase 3 — Seed kanji OpenJLPT
- Sinh `db/seed_openjlpt_kanji.sql` (kanji + kanji_reading).
- **Verify:** ~2.211 kanji; `kanji_reading` đủ ON/KUN; `unicode_code` không NULL, unique.

### Phase 4 — Bổ sung 72 từ thiếu
- `db/seed_smart_test_vocab_supplement.sql` (nhỏ, review từng dòng).
- Dạng ます: nếu từ điển đã có (飲む) thì map câu hỏi → từ điển (không thêm 飲みます);
  chỉ thêm mới các từ thật sự không có (N1 + từ ghép).
- **Verify:** chạy lại `tools/match_lexeme_openjlpt.py` → độ phủ ≥ 99%.

### Phase 5 — Retag 1.500 câu
- `db/seed_smart_test_retag_vocab.sql` (idempotent `UPDATE ... JOIN` theo `lexeme`+`reading`
  trong `context_json` → set `vocabulary_entry_id`).
- **Verify:** `SELECT COUNT(*) FROM assessment_question WHERE vocabulary_entry_id IS NOT NULL` ≈ 1.500;
  không câu nào còn `vocabulary_entry_id IS NULL` mà skill ∈ (KANJI, VOCABULARY).

### Phase 6 — Verify tổng + bàn giao
- Đếm FK nguyên vẹn: `COUNT(vocabulary_entry_id IS NOT NULL)` khớp; không orphan.
- Smoke test trên web: chạy Smart Test, xem kết quả/khuyến nghị không lỗi.
- Commit từng file (mỗi phase 1 commit nhỏ, kết thúc bằng `Co-Authored-By: Claude Code <noreply@anthropic.com>`).

---

## 6. Rủi ro & lưu ý

1. **License CC BY-SA 4.0** — bắt buộc attribution + share-alike. Ghi rõ trong header seed.
   Với capstone thì ổn; nếu sau này thương mại hoá phải xem lại điều khoản.
2. **Nghĩa tiếng Anh** — `meaning`/`meaning_vi` sẽ chứa Anh; cần thống nhất D2 trước khi sinh.
3. **Chuẩn hoá ます-form** — có thể sót vài động từ bất quy tắc; chấp nhận (review ở Phase 4).
4. **Kanji N1 phồng** (1.232 chữ) — có chồng jōyō + chữ ngoài chuẩn; không ảnh hưởng retag
   (câu KANJI là word-level), chỉ lưu catalog để dùng sau.
5. **Không commit secret** — `application-local.yaml` gitignored, không seed credential.
6. **Ngữ pháp vẫn hở** — plan này không giải quyết; giữ track riêng để không chặn tiến độ.

---

## 7. Công việc song song (không chặn plan này)

- Khảo sát nguồn **ngữ pháp N3–N1** (danh sách mẫu chuẩn + map surface-form → pattern).
- Thiết kế logic Warm-up Tầng 1/Tầng 2 dùng `vocabulary_entry_id`/`grammar_point_id` vừa tạo.
