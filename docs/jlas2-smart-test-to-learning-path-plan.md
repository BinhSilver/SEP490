# Kế hoạch hoàn thiện luồng: Smart Test → Lộ trình học → Sách số hóa

> Nhánh hiện tại: `feat/JLAS2-24-smart-test`. Ticket Smart Test: **JLAS-W0090 / W0095 / W0099 / W0103** (nhận lại để sửa). Lộ trình học = task riêng của user (JLAS2-28). Sách số hóa = JLAS2-20/22 (đã merge).

## 1. Mục tiêu

Hoàn thiện luồng end-to-end: learner làm **Smart Test** → hệ thống sinh **lộ trình học cá nhân** → learner học theo **bài từ sách đã số hóa**. Kết quả test nói "yếu mẫu ngữ pháp nào, trong bài nào" để lộ trình ôn đúng chỗ.

## 2. Quyết định đã chốt (2026-09-19)

1. **Nhánh**: tách nhánh mới từ `feat/JLAS2-24-smart-test`, giữ smart test nguyên bản làm backup. Tên nhánh/commit theo 4 task W-code (KHÔNG dùng "28" — trùng mã). Commit kiểu `fix(JLAS-W####): ...`.
2. **Điểm yếu**: thêm `grammar_point_id` vào `user_knowledge_weakness` (vừa bài vừa mẫu).
3. **Bỏ chọn kỹ năng** khi Smart Test → test toàn bộ 5 kỹ năng.
4. **Phạm vi**: làm hết các goal, **JLPT trước**; **BA làm cùng JLPT** (không defer).
5. **FROM_ZERO** = cấp độ hiện tại (không phải nhu cầu) → không test.

## 3. Mô hình goal/level thống nhất

Hai chiều tách bạch:

- **`current_level`** (cấp độ hiện tại): `FROM_ZERO` / `UNKNOWN` / `N5` / `N4` / `N3` / `N2` / `N1`.
- **`goal`** (nhu cầu): `JLPT` / `BA` (= BrSe) / `TRAVEL` / `DAILY_LIFE`.

**Luật vào test:**
- `FROM_ZERO` → **không test**; chọn nhu cầu → lộ trình từ đầu.
- `UNKNOWN` / `N5…N1` → **test**. Survey thêm câu "đã từng học tiếng Nhật chưa?" để chọn bộ câu hỏi phù hợp.
- `TRAVEL` và `DAILY_LIFE` → chỉ test khi có level JLPT (N5…N1); nội dung lộ trình 2 goal này khác nhau một chút.

**Thay đổi cần báo:** `BRSE` → `BA`; `FROM_ZERO` ra khỏi `goal`, dùng trường `current_level` riêng.

## 4. Hiện trạng

- **Smart Test**: re-tag 10 câu + breakdown theo mẫu chính đã xong (schema + seed + code, chưa commit).
- **Sách số hóa**: đã merge, API `/api/dictionary/lessons/{id}/content` đầy đủ.
- **Lộ trình học**: chỉ có entity + schema (rỗng service/controller); canonical ở remote `feat/JLAS2-28-personalized-learning-path`.

## 5. Luồng end-to-end

1. Learner khai `current_level` (+ câu "đã từng học chưa") + chọn `goal`.
2. `FROM_ZERO` → bỏ qua test; còn lại → Smart Test toàn bộ kỹ năng.
3. Test trả kết quả: per-skill + per-grammar-point (điểm yếu theo mẫu kèm bài).
4. Trình sinh lộ trình đọc survey + kết quả test (`assessment_attempt_id`): **REVIEW** (bài chứa mẫu/tag yếu) + **CURRICULUM** (bài tiến tới goal/level, lấy từ sách số hóa).
5. Item trỏ `lesson_id` → learner học qua `/api/dictionary/lessons/{id}/content`.

## 6. Phân công theo 4 task W-code

| Task | Nội dung |
|---|---|
| **W0090** Start + Select by Metadata | Bỏ chọn kỹ năng; taxonomy goal/level; câu survey "đã học chưa"; chọn câu theo metadata |
| **W0095** Score by Skill/Level/Tags | Re-tag + breakdown `grammar_point_id` (đã làm); điểm yếu vừa bài vừa mẫu |
| **W0103** Knowledge vs Usage Test | Knowledge test (JLPT) vs usage ability test (TRAVEL/DAILY_LIFE) |
| **W0099** Validate E2E | Chạy app, verify luồng start→score→path |

## 7. Công việc cần làm

### A. Nhánh (trước tiên)
- Tạo nhánh mới từ `feat/JLAS2-24-smart-test` (mang theo thay đổi re-tag chưa commit).
- Commit từng phần theo W-code (`fix(JLAS-W####): ...`); nhánh smart test giữ nguyên tại `804cc39`.

### B. Smart Test (W0090 + W0095 + W0103)
1. **W0090**: bỏ `skills` khỏi `SmartTestStartRequest`/survey; test đủ 5 kỹ năng; đổi `BRSE`→`BA`; tách `current_level`; thêm câu survey "đã từng học chưa".
2. **W0095**: đã xong phần re-tag + `grammarPointResults`; commit.
3. **W0103**: phân biệt knowledge test vs usage ability test theo goal/level.
4. Chạy app xác nhận `ddl-auto: validate`.

### C. Lộ trình học (task riêng của user — mình hỗ trợ)
1. **Schema**: thêm `assessment_attempt_id` vào `user_learning_path` + `grammar_point_id` vào `user_knowledge_weakness` (sửa `jlas_schema.sql` kèm note `[JLAS2-28]`).
2. **Code**: `LearningService` 4 tầng (ProfileSource → ScopeSource → Selector → Scheduler).
3. **Controller/Mapper**: endpoint `/api/learning`.

### D. FE
- Dùng chung FE lộ trình user vừa làm; không tạo FE riêng.

## 8. Nguyên tắc làm việc (user nhắc)

- Làm **từng phần nhỏ**, user chạy web xác nhận từng phần rồi mới tiếp.
- Không đụng task của người khác (ngoài task của mình + task mới nhận) trừ khi cần.
- Sửa file SQL chung phải **note vào** (xem shared-schema-report-policy).
