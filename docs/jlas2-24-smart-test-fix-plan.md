# Kế hoạch sửa Smart Test — "vừa bài vừa điểm ngữ pháp" (JLAS2-24)

> Trạng thái: **chờ duyệt**. Thiết kế "mẫu chính + mẫu phụ" đã được chốt với người dùng.

## 1. Mục tiêu

Câu hỏi Smart Test hiện chỉ gắn tag thô (`N4-GRAMMAR`, `N5-VOCAB`…) nên kết quả chỉ
nói "yếu ngữ pháp N4". Cần gắn thêm 2 chiều cụ thể để lộ trình ôn tập chính xác:

- **Bài** (lesson): câu thuộc bài nào (`N5-B07`…).
- **Điểm ngữ pháp** (grammar point): câu test đúng mẫu nào (`～あげます`…).

→ Kết quả test trả về "yếu mẫu nào, trong bài nào" thay vì "yếu N4".

## 2. Thiết kế đã chốt

- Mỗi câu có **1 mẫu ngữ pháp CHÍNH** (cái câu hỏi thực sự test) + **nhiều mẫu PHỤ**
  (chỉ xuất hiện trong câu làm nền, không phải trọng tâm).
- **v1 chỉ chấm mẫu chính**; mẫu phụ chỉ phục vụ hiển thị/giải thích, không ảnh hưởng điểm.
- **Bài** chỉ áp dụng N5/N4 (bộ 50 bài Minna). N3/N2/N1 không có "bài", chỉ có điểm ngữ pháp.

Ví dụ câu N2: *"経済の状況が改善する＿＿、失業率も下がるはずだ。"*
- Mẫu chính: `～に伴って` (đề test).
- Mẫu phụ: `～はずだ`, `～する` (chỉ là nền trong câu).

## 3. Trạng thái hiện tại (đã xác minh trong code)

- `assessment_question` đã có `knowledge_tag_id` (NOT NULL → `tag`) = "tag chính".
  Hiện trỏ tới 15 tag thô (`N4-GRAMMAR`, `N5-VOCAB`, `PARTICLE`, `VERB`, `N1..N3-GRAMMAR/READING/LISTENING`, `N5-KANJI`…).
- Bảng `question_tag` (bridge `question_id, tag_id`) đã có = chỗ gắn "tag phụ".
- `grammar_point` (282 dòng) có `id, lesson_id, tag_id (NULL), pattern, meaning, example, note`.
  Đây là entity riêng, KHÔNG phải tag → cần liên kết riêng.
- `lesson.knowledge_tag_id` → tag bài (`N5-B01`..`N5-B25`, `N4-B26`..`N4-B50`).
- `content_item.grammar_point_id` (W0070) đã là tiền lệ liên kết nội dung → grammar point.
- Kết quả Smart Test hiện tính "on-the-fly" trong `AssessmentServiceImpl.toResult`
  từ `AssessmentAnswer` + `AssessmentQuestion`; không cần bảng kết quả mới.
- Ngân hàng câu hỏi: ~50 câu, tất cả đang gắn 15 tag thô (chưa có bài/điểm ngữ pháp).

## 4. Thay đổi schema

1. **Mẫu chính** — thêm cột `grammar_point_id` (nullable, chỉ GRAMMAR có) vào `CREATE TABLE assessment_question`:
   - cột `grammar_point_id BIGINT UNSIGNED NULL`
   - key `idx_assessment_question_grammar`
   - FK `fk_assessment_question_grammar → grammar_point(id) ON DELETE SET NULL`

2. **Mẫu phụ** — bảng bridge mới, theo đúng pattern `question_tag`:
   ```sql
   CREATE TABLE question_grammar_point (
       question_id     BIGINT UNSIGNED NOT NULL,
       grammar_point_id BIGINT UNSIGNED NOT NULL,
       PRIMARY KEY (question_id, grammar_point_id),
       KEY idx_question_grammar_point_gp (grammar_point_id),
       CONSTRAINT fk_qgp_question FOREIGN KEY (question_id)
           REFERENCES assessment_question(id) ON DELETE CASCADE,
       CONSTRAINT fk_qgp_grammar FOREIGN KEY (grammar_point_id)
           REFERENCES grammar_point(id) ON DELETE CASCADE
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
   ```

3. **Áp dụng trực tiếp vào `jlas_schema.sql`** (sql tổng): thêm cột + bảng ngay trong
   các `CREATE TABLE`, kèm note `[JLAS2-24]` ghi rõ thay đổi gì.
4. **Seed data để riêng** trong file seed mới (`db/seed_smart_test_retag.sql`),
   không trộn vào schema.

## 5. Thay đổi entity/JPA

- `AssessmentQuestion`: thêm `@ManyToOne GrammarPoint grammarPoint` (cột `grammar_point_id`)
  + `@OneToMany Set<QuestionGrammarPoint> grammarPoints`.
- Thêm entity `QuestionGrammarPoint` + `QuestionGrammarPointId` (embed, giống `QuestionTag`).
- Thêm repository `QuestionGrammarPointRepository`.

## 6. Re-tag ngân hàng câu hỏi (DATA)

- **Bước 1 — xác định mapping.** Với mỗi câu GRAMMAR, tìm `grammar_point` mà nó test
  (đối chiếu `explanation`/`question_text`/đáp án với `grammar_point.pattern`/`meaning`).
  Tôi viết query/script liệt kê "câu → mẫu ứng viên" để người dùng duyệt/đính chính.
- **Bước 2 — áp dụng mapping** bằng file seed riêng (idempotent):
  - `knowledge_tag_id` → tag bài (với câu N5/N4, suy ra từ `grammar_point.lesson_id → lesson.knowledge_tag_id`).
  - `grammar_point_id` → mẫu chính.
  - `question_grammar_point` → các mẫu phụ (nếu có).
  - Câu N3/N2/N1: `grammar_point_id` = mẫu chính; `knowledge_tag_id` giữ tag cấp độ
    (không có "bài") hoặc chuyển sang tag chi tiết hơn nếu có.
- Câu không phải GRAMMAR (VOCAB/KANJI/READING/LISTENING): chỉ đổi `knowledge_tag_id`
  sang tag bài phù hợp; không có `grammar_point_id`.

> Điểm cần người dùng quyết: mapping mẫu ngữ pháp cho ~22 câu GRAMMAR/PARTICLE/VERB
> sẽ do tôi đề xuất (match nội dung) rồi người dùng xác nhận, hay người dùng tự cung cấp bảng mapping.

## 7. API kết quả

Thêm vào `SmartTestResponse.ResultView` (hoặc `SkillResultView`) phần breakdown mới,
tính on-the-fly, không cần bảng lưu:

- **Per-tag (bài):** mỗi tag bài → `correct / total` (điểm yếu theo bài).
- **Per-grammar-point (mẫu):** mỗi mẫu chính → `correct / total` + `pattern` + `lessonTagCode`
  (điểm yếu theo mẫu, kèm bài của mẫu).
- Chỉ mẫu chính được tính điểm; mẫu phụ không góp vào số liệu.

Ví dụ output: `GRAMMAR` → mẫu `～あげます` (bài N5-B07): 1/3 → "yếu mẫu あげます trong bài N5-B07".

## 8. Các bước triển khai

1. **Schema** — sửa trực tiếp `jlas_schema.sql` (thêm cột + bảng + note `[JLAS2-24]`).
2. **Entity/JPA** — thêm field/entity/repository; xác minh `ddl-auto: validate` không vỡ.
3. **Re-tag data** — script liệt kê mapping → người dùng duyệt → ghi vào seed file riêng (`db/seed_smart_test_retag.sql`).
4. **API kết quả** — mở rộng `toResult`/DTO để xuất breakdown per-tag + per-grammar-point.
5. **Kiểm thử** — chạy Smart Test mẫu, xác minh breakdown đúng với đáp án đã ghi.

## 9. Ngoài phạm vi (follow-up riêng)

- `completionReason` + `evidenceStatus` cho `smart_test_session` (theo dõi lý do kết thúc bài).
- Mở rộng survey: kana, ngày học trong tuần, chủ đề, curriculumId, needs.
- Tag từ vựng chủ đề (chiều "từ vựng chủ đề nào" chưa thuộc phạm vi điểm ngữ pháp này).
