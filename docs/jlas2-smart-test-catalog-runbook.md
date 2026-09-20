# Runbook: Load & verify catalog OpenJLPT + retag Smart Test

> Ticket: **[JLAS-W0091] Data: Chuẩn hóa metadata và ngân hàng câu hỏi mẫu**
> Nhánh: `feat/JLAS-W0090-smart-test-fix`
> Trạng thái: **Phase 0–5 hoàn tất; seed đã GỘP vào 2 file canonical** (xem §1).
> Chờ: chạy trên DB + smoke test web (Phase 6).

---

## 1. Cấu trúc file (đã gộp, KHÔNG còn file seed rời)

Toàn bộ dữ liệu của ticket này đã được gộp vào 2 file canonical sẵn có — **không
tạo thêm file SQL mới**. Tổng cộng repo chỉ còn 3 file liên quan:

| # | File | Nội dung |
|---|---|---|
| 1 | `jlas_schema.sql` | Schema (đã có cột `assessment_question.vocabulary_entry_id`). |
| 2 | `db/seed_data_jlas.sql` | Catalog + giáo trình: TEXTBOOK hiện có **+ OpenJLPT vocab (8.333 dòng) + kanji (2.211 kanji, 6.852 reading) + 48 từ bổ sung** (gộp ở cuối file, trước `SET FOREIGN_KEY_CHECKS = 1;`). |
| 3 | `questionbank.sql` | 2.250 câu Smart Test **+ retag `vocabulary_entry_id` cho 1.500 câu KANJI/VOCABULARY** (gộp trước `COMMIT;` cuối). |

Đã xoá các file rời (nội dung đã nằm trong 2 file trên):
`db/seed_openjlpt_vocab.sql`, `db/seed_openjlpt_kanji.sql`,
`db/seed_smart_test_vocab_supplement.sql`, `db/seed_smart_test_retag_vocab.sql`,
`db/jlas_schema_upgrade_JLAS-W0091.sql`, `db/jlas_schema_upgrade_JLAS-W0070.sql`.

---

## 2. Thứ tự load

### Trường hợp A — DB đang chạy (nâng cấp tại chỗ)

```sql
USE jlas;   -- hoặc schema của bạn

SOURCE .../db/seed_data_jlas.sql;   -- catalog + OpenJLPT vocab/kanji (idempotent)
SOURCE .../questionbank.sql;        -- 2.250 câu + retag (idempotent)
```

> `seed_data_jlas.sql` xoá rồi nạp lại toàn bộ catalog (TEXTBOOK + DICTIONARY),
> KHÔNG đụng `users`/`roles`/`assessment_*`. `questionbank.sql` không đụng catalog.

### Trường hợp B — DB mới hoàn toàn

```sql
SOURCE .../jlas_schema.sql;        -- schema (đã có cột vocabulary_entry_id)
SOURCE .../db/seed_data_jlas.sql;  -- catalog TEXTBOOK + OpenJLPT vocab/kanji
SOURCE .../questionbank.sql;       -- 2.250 câu Smart Test + retag 1.500 câu
```

Chỉ 3 file. Tất cả đều idempotent: chạy lại không trùng, không ghi đè dữ liệu thủ công.

---

## 3. Verify (Phase 6)

Chạy tuần tự; các dòng **PHẢI ĐÚNG** mới coi là pass:

```sql
-- 3.1 Không còn câu KANJI/VOCABULARY nào bị bỏ sót (quan trọng nhất)
SELECT COUNT(*) FROM assessment_question
WHERE skill IN ('KANJI','VOCABULARY') AND vocabulary_entry_id IS NULL;
-- Kỳ vọng: 0

-- 3.2 Số câu đã được gán = 1.500
SELECT COUNT(*) FROM assessment_question WHERE vocabulary_entry_id IS NOT NULL;
-- Kỳ vọng: 1500

-- 3.3 Catalog từ vựng DICTIONARY (OpenJLPT + 48 bổ sung)
SELECT COUNT(*) FROM vocabulary_entry WHERE usage_scope='DICTIONARY';
-- Kỳ vọng: ~8.378 (8.330 OpenJLPT sau 3 va chạm chuẩn hoá + 48 bổ sung;
--          thấp hơn nếu trùng từ TEXTBOOK cũ)

-- 3.4 Kanji + reading
SELECT COUNT(*) FROM kanji;                          -- ~2.211
SELECT COUNT(*) FROM kanji_reading;                  -- ~6.852

-- 3.5 FK không orphan (tất cả vocabulary_entry_id phải trỏ tới từ có thật)
SELECT COUNT(*) FROM assessment_question aq
LEFT JOIN vocabulary_entry ve ON ve.id = aq.vocabulary_entry_id
WHERE aq.vocabulary_entry_id IS NOT NULL AND ve.id IS NULL;
-- Kỳ vọng: 0
```

---

## 4. Ghi chú quan trọng

1. **Convention kana rỗng**: OpenJLPT lưu từ thuần kana (たくさん, きれい…) với
   `reading = ''`. Bộ supplement + retag chuẩn hoá theo convention này. TEXTBOOK cũ
   dùng `reading = word` — hai nguồn khác nhau, KHÔNG sửa TEXTBOOK cũ (ngoài phạm vi).
2. **Nghĩa giữ tiếng Anh** (D2) — dịch Việt giai đoạn sau.
3. **License**: nguồn OpenJLPT là CC BY-SA 4.0; attribution nằm trong header mỗi block
   seed đã gộp (giữ nguyên khi tái sử dụng/phân phối).
4. **Không commit secret**: không có file `.local.yaml` hay credential nào được seed/push.
5. **Commit** chỉ khi được yêu cầu; mỗi phase 1 commit nhỏ, kết thúc bằng
   `Co-Authored-By: Claude Code <noreply@anthropic.com>`.

---

## 5. Tái sinh seed (nếu đổi nguồn)

```bash
python tools/gen_openjlpt_seed.py                 # vocab + kanji
python tools/gen_smart_test_supplement_retag.py   # supplement + retag (in ra report khớp)
```

> LƯU Ý: các script `tools/gen_*` hiện sinh ra **block rời** (để dễ đối chiếu). Nếu
> chạy lại, phải **gộp ngược** vào `db/seed_data_jlas.sql` (vocab/kanji/supplement) và
> `questionbank.sql` (retag) theo đúng vị trí ở §1 — không tạo file SQL mới.

`tools/gen_smart_test_supplement_retag.py` in report cuối dòng:
`matched (OJ+supp): 1500 / UNMATCHED: 0` — nếu lệch thì có bug nguồn cần xử lý.
