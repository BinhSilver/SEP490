# Khảo sát gap Ngữ pháp N3–N1 (song song, không chặn [JLAS-W0091])

> Trạng thái: **Khảo sát — chưa chốt quyết định, chưa implement.**
> Ngữ cảnh: OpenJLPT đã phủ từ vựng + kanji (xem runbook catalog), nhưng KHÔNG có grammar.

---

## 1. Hiện trạng (đã đo)

| Yếu tố | Hiện trạng |
|---|---|
| Nguồn OpenJLPT | Chỉ có `vocab-*.csv` + `kanji-*.csv`, **không có grammar** (trang chủ đánh dấu Grammar "🔜 coming soon"). |
| Câu GRAMMAR trong Smart Test | **250 câu** (50/level N5–N1), `grammar_point` nằm trong `context_json` dạng chuỗi surface-form (vd `てください`), kèm `curriculum_basis` (vd "Minna no Nihongo I"). |
| Liên kết câu → điểm ngữ pháp | Chưa có `grammar_point_id` được set (cột đã có sẵn trên `assessment_question`). |
| Bảng `grammar_point` | **Gắn `lesson_id` NOT NULL** (phạm vi bài học), `pattern`/`meaning`/`example`/`note`, unique `(lesson_id, order_index)`. |
| Giáo trình hiện có | Minna no Nihongo I/II — chỉ phủ ~N5–N4. |

**Kết luận gap:** muốn map 250 câu GRAMMAR (nhất là N3–N1) về điểm ngữ pháp cụ thể
(tương tự `vocabulary_entry_id`), thì **không có "từ điển ngữ pháp" toàn cục** — vì
`grammar_point` bị ràng buộc `lesson_id`, còn nguồn N3–N1 chưa có.

---

## 2. Nguồn ứng viên (machine-readable, cho N3–N1)

| Nguồn | Dạng | License | Ghi chú |
|---|---|---|---|
| **[hanabira.org-japanese-content](https://github.com/tristcoil/hanabira.org-japanese-content)** | JSON theo level: `grammar_ja_N{3,2,1}_full_alphabetical_*.json` | Creative Commons, **cần attribution** (link về hanabira.org); cần xác minh đúng biến thể (CC BY / BY-SA) trong repo | Ứng viên tốt nhất: có pattern + meaning + formation + example, ~132 điểm N3 / 245 điểm N1. |
| **jlpt-sensei** (qua [rinem/jlpt-scraper](https://github.com/rinem/jlpt-scraper)) | Scrape → JSON/CSV | **Không rõ license** (web scrape) | Chỉ lấy "basic meaning" + link; rủi ro bản quyền cao. |
| **Bunpro** ([bunpro.jp](https://bunpro.jp/grammar_points)) | Web (grammar points có pattern/meaning/example) | Độc quyền | Chất lượng cao nhưng không mở để bulk. |
| **Tae Kim / Imabi** | Web/sách, không tổ chức theo JLPT level | Miễn phí, không phải dataset JSON | Không phù hợp để seed trực tiếp. |

**Khuyến nghị (để user quyết):** ưu tiên khảo sát sâu `hanabira.org-japanese-content`
(xác minh license + cấu trúc JSON) — là nguồn CC duy nhất có sẵn JSON N3–N1 với
pattern + meaning + example.

---

## 3. Quyết định cần chốt TRƯỚC khi implement (chờ user)

1. **Cấu trúc lưu grammar "từ điển":**
   - (a) Thêm bảng `grammar_entry` (toàn cục, đối xứng `vocabulary_entry`) — sạch, tách
     khỏi `grammar_point` (vốn là lesson-scoped); cần ticket schema mới.
   - (b) Tái dùng `grammar_point` + tạo các `lesson` giả "grammar N3/N2/N1" — không cần
     schema mới nhưng làm lẫn khái niệm "bài học" với "từ điển".
2. **Ngôn ngữ nghĩa** (Anh như vocab, hay Việt?).
3. **License** của nguồn grammar chọn (CC BY vs BY-SA) — ảnh hưởng share-alike.
4. **Phạm vi**: chỉ map N3–N1, hay làm luôn N5–N4 cho đồng bộ 250 câu?

---

## 4. Không thuộc phạm vi này

- Viết logic Warm-up Tầng 1 (Skill) / Tầng 2 (Knowledge) — dùng `grammar_point_id`/
  `vocabulary_entry_id`; là công việc service, tách riêng.
