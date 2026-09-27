# JLAS-W0132 — Learning Path E2E · QA kịch bản + kết quả + findings

> Nhánh `feat/JLAS-W0108-jlpt-path-full-coverage`. Gộp 3 subtask: **W0133** QA kịch bản,
> **W0134** DOC, **W0135** QA replan. Ngày chạy: 2026-09-27.
> Đây là bản E2E đầy đủ hơn W0099 (Smart Test E2E): Smart Test → weakness → path → warm-up
> flashcard → lesson quiz → gate quiz → replan. Chân CP/Streak nằm ngoài scope (chuyển W0193).

---

## 1. Setup (W0133)

- Demo learner: `learner@jlas.local` / `Jlas@123` (id 9001, role LEARNER).
- Seed Smart Test idempotent: `db/2026-09-27-w0132-e2e-seed.sql` (survey + `assessment_attempt 19001`
  SUBMITTED 42% + `smart_test_session` + 12 `assessment_answer`, câu sai đủ 5 skill).
- Auth: `POST /api/auth/login` → cookie `access_token`; mọi API dùng `Authorization: Bearer <token>`.
- App chạy localhost:8080 (MySQL `jlas`, `ddl-auto: validate`).

---

## 2. Kịch bản E2E đã chạy + bằng chứng (W0133 / W0134)

| # | Bước | Endpoint | Kết quả |
|---|---|---|---|
| 1 | Smart Test → kết luận điểm yếu | (seed) | 3 weakness: KANJI `家族`, GRAMMAR `GP:145`, VOCABULARY `予定` (OPEN/WEAK) |
| 2 | Tạo path | `POST /api/learning/paths` | **201** — 1 warm-up + 5 goal items |
| 3 | Warm-up flashcard | `GET /learning/warm-up/flashcard` | **200** — set id 1 "Kanji & Từ vựng sai Smart Test", card `家族`/かぞく/gia đình |
| 4 | Lesson quiz (lấy đề) | `GET /api/learning/lessons/9203/quiz` | **200** — 5 câu N4-grammar |
| 5 | Lesson quiz (nộp đúng) | `POST .../quiz/submit` | 5/5 → 100%, `passed=true`, `lessonCompleted=true` |
| 6 | Gate quiz (lấy đề) | `GET /api/learning/paths/gate-quiz` | **200** — 13 câu (3 KANJI + 5 READING + 5 LISTENING) |
| 7 | Gate quiz (nộp đúng) | `POST .../gate-quiz/submit` | 13/13 → 100%, `passed=true`; DB `gate_passed_at` = 00:21:58 |
| 8 | Replan preview | `GET .../replan/preview?mode=REMEDIATE` | **200** — `changesApply=true`, `remediationAdded=1`, `newTotalLessons=7` |
| 9 | Replan preview | `GET .../replan/preview?mode=ACCELERATE` | **200** — no-op (0 mastered, explanation "không có mục đạt mastery") |
| 10 | Replan apply | `POST /api/learning/paths/replan` | ❌ **500 — lỗi unique constraint** (xem §3.1) |

**Bằng chứng DB** (sau bước 5–7):

```
user_knowledge_weakness (user 9001): KANJI:家族 · GP:145 · VOCABULARY:予定  (OPEN/WEAK)
user_learning_path id=1: ACTIVE v1, gate_passed_at=2026-09-27 00:21:58, warm_up_flashcard_set_id=1
user_learning_path_item: 1× WARM_UP(WEAK_KANJI_ITEM, vocab 42) + 5× GOAL_LEARNING
```

---

## 3. Findings

### 3.1 ❌ (chặn) Replan `apply` — vi phạm unique constraint `uk_learning_path_generation`

**Hiện tượng:** `POST /api/learning/paths/replan {mode:REMEDIATE, confirmed:true}` trả 500:

```
Duplicate entry '9001-19001-smart-test-v1-jlpt-per-skill-v2:min=1:ratio=0.5:windo'
for key 'user_learning_path.uk_learning_path_generation'
```

**Root cause:** [jlas_schema.sql:1552](jlas_schema.sql#L1552)

```sql
UNIQUE KEY uk_learning_path_generation (user_id, assessment_attempt_id, assessment_revision, policy_version),
```

không bao gồm `version`. `LearningReplanService.createNextVersion()` (dòng ~285) đặt path cũ thành
`SUPERSEDED` rồi **insert bản mới** với cùng `assessmentAttemptId/assessmentRevision/policyVersion`
(copy từ path cũ), chỉ đổi `version` → trùng key với dòng `SUPERSEDED` vẫn còn nằm trong DB.

**Vì sao test H2 không bắt được:** constraint chỉ nằm trong `jlas_schema.sql` (MySQL `validate`),
không nằm ở `@Table(uniqueConstraints=...)` của `UserLearningPath` → H2 `create-drop` không tạo
constraint này, nên `LearningReplanServiceTest` (kỳ vọng `count = 2`, `version = 2`) xanh ở H2 nhưng
đỏ ở MySQL.

**Đề xuất fix (cần chốt trước khi code — thuộc W0122, không phải W0132):**
- **A. Thêm `version` vào unique key** → `(user_id, assessment_attempt_id, assessment_revision, policy_version, version)`.
  Giữ được lịch sử version (đúng thiết kế "tái sinh path không mất lịch sử"). Key `uk_learning_path_user_version
  (user_id, version)` đã tồn tại, nên generation key mở rộng này chỉ thêm mục đích ràng buộc version.
- **B. Bỏ hẳn `uk_learning_path_generation`** (dư thừa — `create()` đã có idempotency guard
  `findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersion` trả về path có sẵn; version key đã đủ).
- Kèm theo: phản chiếu constraint vào `@Table(uniqueConstraints=...)` để H2 test bắt được đúng hành vi.

**Rollback sạch:** sau lỗi, path vẫn `ACTIVE v1` (không orphan, không SUPERSEDED) — `@Transactional` rollback OK.

### 3.2 ⚠️ Warm-up chỉ hiện 1/3 loại điểm yếu (content gap — không phải bug code)

Seed đủ 5 skill sai, nhưng warm-up chỉ sinh **1 item KANJI** (`家族`). Nguyên nhân:
- **GRAMMAR `GP:145` / VOCABULARY `予定`**: `JpaLearningCatalogSource.findGrammarPoint/findVocabularyItem`
  chỉ resolve khi grammar point / từ vựng được dạy trong lesson **PUBLISHED**. Hai mục này chưa có
  lesson PUBLISHED → gap `MISSING_WARM_UP_RESOURCE` (đúng §12.6, là việc content).
- **READING / LISTENING**: `WeakTagPolicy` yêu cầu `assessed ≥ 5` (COMPREHENSION_WINDOW=5) → seed 3 câu/ skill
  chưa đủ bằng chứng → không kết luận weak (đúng policy, không phải lỗi).

### 3.3 ⏸️ CP/Streak — ngoài scope W0132

Không verify (chưa có code nối event lesson → CP/streak). Thuộc **W0193** (nhận 27/09 thay W0127).

### 3.4 ℹ️ Nhỏ

- `GET /api/learning/paths/current` **không** trả `gatePassedAt` (DTO thiếu field) — DB đã ghi đúng.
- ACCELERATE preview trả `changesApply=true` dù `masteredRemoved=0` và explanation "không có gì để tăng tốc"
  (chỉ do lịch tính lại ngày/phút). Gây hiểu nhầm nhẹ ở FE, không chặn luồng.
- Explanation quiz hiển thị đúng (UTF-8); mojibake trước đó chỉ là artifact hiển thị console, DB `HEX` chuẩn.

---

## 4. Kết luận

Luồng lõi chạy đúng từ Smart Test → path → warm-up flashcard → lesson quiz → gate quiz → replan preview.
**Một blocker duy nhất:** replan `apply` (unique constraint) — cần fix ở W0122. Warm-up breadth là gap
nội dung (CONTENT_CREATOR), CP/Streak chờ W0193.
