# Handoff hoàn thành — Interactive Learning Path V2 Task 4 (Vocabulary study + micro-check)

Bàn giao cho AI thực hiện Task 5–10. Task 4 đã hoàn thành mốc **40%**; mọi exit criteria nghiệp vụ,
ownership/IDOR, tampering, answer-key, idempotency (submit), transaction/concurrency, schema, UI/JS và
kiểm thử đều có bằng chứng. Không bắt đầu Task 5 trong phạm vi này.

## 1. Commit và trạng thái working tree

- **Branch:** `codex/interactive-learning-v2`
- **Commit Task 4 (code + schema + test):** `e4bc44f` — `feat(JLAS-W0193): add vocabulary study and micro-check`
  - 30 files changed, +2138 / −15.
- **Commit Task 4 (docs: rules + completion handoff):** commit docs riêng ngay sau `e4bc44f` (xem `git log`).
- **BASE của Task 4:** `3592d3c` (docs handoff tasks five–ten). Range thực thi: `3592d3c..e4bc44f`.
- **Working tree sau commit:** sạch, ngoại trừ `.superpowers/` (workspace scratch SDD, không commit) và
  không có thay đổi nào khác. `git status --short` chỉ hiện `?? .superpowers/`.

## 2. File chính đã thay đổi

**Domain / persistence (mới):**
- `src/main/java/com/jlas/learning/entity/LearningStudySession.java` — 1 session/activity, gắn `sourceRevision`.
- `src/main/java/com/jlas/learning/entity/LearningStudyCard.java` — flip/remember evidence theo `canonical_id`.
- `src/main/java/com/jlas/learning/entity/LearningMicroCheckState.java` — attempt summary per (activity, batch).
- `src/main/java/com/jlas/learning/repository/LearningStudySessionRepository.java`
- `src/main/java/com/jlas/learning/repository/LearningStudyCardRepository.java`
- `src/main/java/com/jlas/learning/repository/LearningMicroCheckStateRepository.java` (`findForUpdate` PESSIMISTIC_WRITE)
- `src/main/java/com/jlas/learning/entity/LearningQuizSession.java` — thêm cột `batchIndex` (nullable).
- `src/main/java/com/jlas/learning/repository/UserLearningPathItemRepository.java` — thêm `findOwnedByIdForUpdate` (PESSIMISTIC_WRITE).

**Service / DTO / controller (mới):**
- `src/main/java/com/jlas/learning/service/InteractiveVocabularyService.java` (interface)
- `src/main/java/com/jlas/learning/service/impl/InteractiveVocabularyServiceImpl.java` (engine)
- `src/main/java/com/jlas/learning/controller/InteractiveVocabularyController.java`
- `dto/VocabularyStudyState.java`, `dto/MicroCheckResponse.java`, `dto/MicroCheckResult.java`,
  `dto/MicroCheckStateResponse.java`, `dto/MicroCheckSubmitRequest.java`

**Route / UI (mới + sửa):**
- `src/main/java/com/jlas/learning/controller/LearningPathPageController.java` — route `/learning/v2/vocabulary` (đã có).
- `src/main/resources/templates/learning/interactive-study.html`
- `src/main/resources/static/js/pages/interactive-study.js`
- `src/main/resources/static/css/pages/interactive-study.css`
- `src/main/resources/static/js/pages/interactive-learning-path.js` — wrap Vocabulary activity thành link tới `/learning/v2/vocabulary?activity={id}`.
- `src/main/resources/static/css/pages/interactive-learning-path.css` — `.ilp-activity__link`.

**Schema:**
- `sql/migrations/012_interactive_study_and_check.sql` (migration mới).
- `jlas_schema.sql` (bảng/cột tương ứng inline — dòng 1851, 1867, 1874–1924).

**Test:**
- `src/test/java/com/jlas/learning/InteractiveVocabularyServiceTest.java` (11 test)
- `src/test/java/com/jlas/learning/InteractiveVocabularyPersistenceTest.java` (4 test, @DataJpaTest H2)
- `src/test/java/com/jlas/learning/InteractiveVocabularyControllerTest.java` (5 test)
- `src/test/frontend/interactive-study.test.cjs` (6 test)

**Docs:**
- `docs/rules-and-business-logic.md` — mục §2.11 mới.
- `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md` — Task 4 `✅ complete`.

## 3. API contract

Base: `/api/learning/v2/activities/{activityId}/vocabulary`. Mọi response là `ApiResponse<T>`.
`activityId` là id của `UserLearningPathItem` thuộc principal; không nhận userId/scope từ body/query.

| Method | Path | Request | Response (`data`) |
|---|---|---|---|
| GET | `/study` | — | `VocabularyStudyState` |
| POST | `/cards/{memberId}/flip` | — (`memberId` = `canonical_id` của membership) | `VocabularyStudyState` |
| POST | `/cards/{memberId}/remember?remembered=true\|false` | — | `VocabularyStudyState` |
| POST | `/micro-check` | — | `MicroCheckResponse` |
| POST | `/micro-check/submit` | `MicroCheckSubmitRequest` (`@Valid`) | `MicroCheckResult` |
| GET | `/micro-check/state` | — | `MicroCheckStateResponse` |
| POST | `/micro-check/review` | — | `MicroCheckStateResponse` |

Page route (Thymeleaf): `GET /learning/v2/vocabulary?activity={id}` → `interactive-study.html`.

**DTO tóm tắt:**
- `VocabularyStudyState`: `activityId, activityType, activityState, sourceRevision, totalCards, flippedCount,
  rememberedCount, reviewRemaining, checkEligible, cards[]`. Mỗi card: `memberId(=canonical_id), orderIndex,
  front(expression), reading, meaning, wordType, jlptLevel, exampleJa, exampleVi, flipped, remembered`.
- `MicroCheckResponse`: `attemptId, activityId, batchIndex, batchCount, totalQuestions, questions[]`
  (`questionId, questionType, questionText, options`). **Không chứa answer key.**
- `MicroCheckSubmitRequest`: `attemptId, answers[]` (`questionId`, `selectedOption` với `@Min(1)`).
- `MicroCheckResult`: `totalQuestions, correctAnswers, scorePercent, passed, passThresholdPercent, attemptsUsed,
  attemptsRemaining, cooldownUntil, reviewRequired, activityState, review[]` (`questionId, correctOption,
  selectedOption, correct, explanation`).
- `MicroCheckStateResponse`: `activityId, activityState, batchCount, currentBatch, attemptsUsed, attemptsRemaining,
  cooldownUntil, reviewRequired, checkEligible, passed, firstScore, latestScore, bestScore`.

**Error codes (`ErrorCode`):**
- `NOT_FOUND` — activity không thuộc owner, hoặc activity type không phải `VOCABULARY` (an toàn, không tiết lộ tồn tại).
- `BLOCKED_CONTENT` — membership `VOCABULARY_ENTRY` rỗng.
- `PREREQUISITE_NOT_MET` — issue micro-check khi chưa nhớ hết thẻ.
- `VALIDATION_FAILED` — `memberId` không thuộc phiên học.
- `CONFLICT` — chưa flip đã remember; issue/submit khi đã `PASSED`; đang cooldown; `completeReview` khi không cần ôn.
- `FORBIDDEN` — submit với `attemptId` không thuộc owner/activity/quiz-type.
- `BAD_REQUEST` — submit session thiếu `batchIndex`.
- Lỗi validation (`@Valid`/`@Min(1)`) → envelope validation do `GlobalApiExceptionHandler`.

## 4. Schema, migration, bảng, index, migration order

Migration mới nhất: **`sql/migrations/012_interactive_study_and_check.sql`** (sau 001–011 hiện có). Thứ tự chuẩn:
tạo `learning_study_session` → `learning_study_card` → `learning_micro_check_state` → `ALTER learning_quiz_session`.

- `learning_study_session(id PK, activity_item_id, source_revision VARCHAR(64) NOT NULL, row_version, created/updated_at)`
  — `UNIQUE uk_learning_study_session_activity(activity_item_id)`; FK→`user_learning_path_item(id)` ON DELETE CASCADE.
- `learning_study_card(id PK, study_session_id, canonical_id BIGINT UNSIGNED, order_index, flipped, remembered,
  flipped_at, remembered_at)` — `UNIQUE uk_learning_study_card_identity(study_session_id, canonical_id)`,
  `KEY idx_learning_study_card_order(study_session_id, order_index)`; FK→`learning_study_session(id)` CASCADE.
- `learning_micro_check_state(id PK, activity_item_id, batch_index, cycle_number, attempts_used, passed, first_score,
  latest_score, best_score, cooldown_until, review_required, row_version, created/updated_at)` —
  `UNIQUE uk_learning_micro_check_activity_batch(activity_item_id, batch_index)`,
  `CHECK chk_learning_micro_check_attempts(attempts_used <= 3)`; FK→`user_learning_path_item(id)` CASCADE.
- `learning_quiz_session` thêm `batch_index INT UNSIGNED NULL` (legacy = NULL), và `chk_quiz_session_type` nới rộng
  còn `('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK')`.

`jlas_schema.sql` đã chứa cùng bảng/cột (dòng 1851, 1867, 1874–1924). Entity ↔ schema ↔ H2 tests đồng bộ
(`ddl-auto=validate` prod; H2 `create-drop` test). Không seed lẫn vào schema.

## 5. State machine và transaction/locking boundaries

State hoạt động (`UserLearningPathItem.activityState`, quyết định chỉ phía server):
`STUDYING` → (mọi thẻ đã nhớ) `CHECK_READY` → (mọi batch ≥70%) `PASSED` | (fail lần 3) `COOLDOWN`
→ (đã review + đủ 60 phút) mở cycle mới 3 lượt → `CHECK_READY`.

Micro-check state (`LearningMicroCheckState`) theo (activity, batch): `attempts_used ≤ 3`/cycle; `cycle_number`
tăng khi hết cycle; `passed`, `first_score` (không ghi đè), `latest_score`, `best_score`, `cooldown_until`,
`review_required`.

**Locking / concurrency:**
- Mọi method mutating mở `findOwnedByIdForUpdate(activityId, userId)` = `SELECT ... FOR UPDATE` trên activity row →
  serialize mọi thao tác study/micro-check của cùng activity (chống double-spend attempt, double session, double pass).
- `submitMicroCheck` còn `findForUpdate(activityId, batchIndex)` = PESSIMISTIC_WRITE trên `learning_micro_check_state`
  trước khi tăng `attempts_used` (chống 2 submit song song cùng batch).
- `@Version row_version` trên `LearningStudySession` và `LearningMicroCheckState` (optimistic) bổ trợ; `LearningStudyCard`
  được bảo vệ bởi activity lock.
- Snapshot `learning_quiz_session` ghi một lần (questionsJson + answerKeyJson), `ISSUED → SUBMITTED`; submit trùng
  (`status = SUBMITTED`) trả lại `reconstructResult` từ `resultJson` — không tăng attempt, không chấm lại, không
  hoàn thành/phát thưởng hai lần.

## 6. Chống IDOR, tampering, answer-key leakage, duplicate, race

- **IDOR:** principal (JWT) là nguồn user id duy nhất; mọi lookup `item.learningPath.user.id = :userId`. MemberId là
  `canonical_id` nằm trong membership đã đọc từ server, không phải id do client gửi để mở scope. Sai owner/type → `NOT_FOUND`.
- **Tampering:** client không gửi được score, attempt count, activity state, cooldown hay completion flag; mọi giá trị
  đều do server tính. Submit chấm theo answer key snapshot đã issue, không đọc lại nội dung mutable.
- **Answer-key leakage:** `MicroCheckResponse`/question chỉ chứa `questionId, questionType, questionText, options`;
  `answerKeyJson` chỉ nằm server. Option index **1-based** xuyên suốt (`@Min(1)` submit và grading cùng quy ước).
- **Duplicate submit:** `attemptId` = idempotency key; submit trùng trả kết quả đã lưu. Lỗi validation/technical
  không tiêu tốn lượt (attempt chỉ tăng sau khi grade thành công trong cùng transaction).
- **Race:** activity-row lock + batch-state lock + `@Version`; concurrent submit cùng batch không tăng attempt/quá 3,
  không hoàn thành activity hai lần.

## 7. Contract engine Task 5 phải tái sử dụng

**Tầng dữ liệu đã Kanji-agnostic (dùng lại nguyên):**
- `LearningStudySession` (activity + `source_revision`), `LearningStudyCard` (`canonical_id` + flip/remember) —
  `canonical_id` trỏ canonical membership của bất kỳ loại nào (VOCAB/KANJI), không phải field riêng vocab.
- `LearningMicroCheckState` (attempt/score/cooldown/review theo activity+batch) và `LearningQuizSession`
  (`VOCAB_MICRO_CHECK` snapshot bất biến, thêm `batch_index`).

**Tầng state machine đã content-agnostic (tái dùng logic, đổi 3 điểm bên dưới):**
`getOrCreateSession` / `ensureCards` (resume + revision reset), `flip`/`remember` (flip-before-remember + recycle),
`issueMicroCheck`/`submitMicroCheck` (batch ≤10, snapshot, grade, first/latest/best, 3 lượt, cooldown 60p, review gate),
`canAttempt`/`refreshActivityState`/`firstUnpassedBatch`/`allBatchesPassed`/`reconstructResult`/`grade`.

**Điểm gắn Vocabulary (Task 5 phải thay, không copy state machine):**
1. Membership type: `loadMembership` lọc `VOCABULARY_ENTRY` → Task 5 lọc `KANJI_ENTRY` (chung query `findByActivityIdOrderByOrderIndex`).
2. Nguồn nội dung: `loadVocabById` đọc `VocabularyEntry` → Task 5 đọc canonical Kanji (adapter read-only trong `learning`).
3. Question strategy + ánh xạ card: `buildQuestions`/`selectPrompt`/`selectCorrect`/`selectDistractors` (4 dạng vocab)
   và `buildStudyState` (field expression/reading/meaning…) → Task 5 thay bằng Kanji→reading, reading→Kanji,
   Kanji→meaning, matching và field on/kun reading + example.

## 8. Cách thêm Kanji question strategy mà không copy state machine

Giữ nguyên: session/evidence lifecycle, attempt/cooldown/score summary, snapshot, grading, idempotent submit,
ownership scope. Chỉ cung cấp một **strategy** cho content + question:

- Tách `selectPrompt/selectCorrect/selectDistractors` và ánh xạ card thành một interface/strategy nội bộ
  (ví dụ `MicroCheckContentStrategy` với `membershipType()`, `loadContent(members)`, `buildQuestions(batch)`,
  `toStudyCard(member, content, evidence)`).
- `InteractiveVocabularyServiceImpl` đã là reference implementation cho strategy Vocabulary; strategy Kanji chỉ cần
  trả `QuestionSpec`/card view cùng shape, không đụng tới `LearningMicroCheckState`/`LearningQuizSession`/attempt logic.
- **Không** tạo abstraction dự đoán Grammar/Dialogue (chỉ Kanji là điểm mở rộng bắt buộc hiện tại); nếu sau này
  Grammar/Dialogue cần khác biệt, thêm strategy mới thay vì thêm nhánh vào engine.

## 9. Test counts và lệnh đã chạy

Focused (Surefire XML):
- `InteractiveVocabularyServiceTest`: 11 test, 0 fail/error/skip.
- `InteractiveVocabularyPersistenceTest`: 4 test, 0 fail/error/skip.
- `InteractiveVocabularyControllerTest`: 5 test, 0 fail/error/skip.
- **Tổng Task 4: 20 test, 0 fail/error/skip.**

Full regression:
- `./mvnw test` → BUILD SUCCESS (exit 0). Surefire aggregate: **293 tests, 0 failures, 0 errors, 22 skipped**
  (22 skipped là `LessonContentSeedTests` có sẵn, không liên quan Task 4).
- `node --test src/test/frontend/*.test.cjs` → **40/40 pass** (trong đó `interactive-study.test.cjs` = 6).
- `node --test scripts/speed-recall-rules.test.mjs` → **4/4 pass**.
- `node --test src/test/js/personalized-path.test.cjs` → **8/8 pass**.
- `git diff --check` → sạch (không lỗi whitespace).

## 10. Giới hạn / phần chưa đạt (có bằng chứng, không phải blocker Task 5)

1. **Issue idempotency chặt:** `issueMicroCheck` luôn ghi snapshot `VOCAB_MICRO_CHECK` mới (không tái dùng key). Không
   tốn lượt (attempt chỉ tăng khi submit) nên double-click chỉ để lại snapshot `ISSUED` mồ côi. Nếu cần chặt hơn, Task 5
   có thể thêm lookup snapshot chưa submit cho (activity, batch) trước khi tạo mới. Submit đã idempotent đầy đủ.
2. **Review gate sau fail 1/2:** chỉ bắt buộc review sau fail thứ 3 (cooldown), đúng spec §4.2/§4.3 ("hiển thị target
   sai và cho quay lại practice ngay" cho fail 1/2). Wording "buộc review sau fail 1/2" trong task4-handoff đã được
   spec thay thế — đây là quyết định có ghi ruling trong ledger, giữ theo spec.
3. **Readiness khi thiếu distractor:** `optionQuestion` dedup distractor và cap 4 option, nhưng không ném readiness
   error khi còn < 2 option (batch 1 member → câu 1 option). Composer yêu cầu vocabulary đã duyệt nên đây là edge
   suy biến; Task 5 có thể thêm guard `size < 2 → BLOCKED_CONTENT` nếu muốn cứng hơn.
4. **Clock:** dùng `LocalDateTime.now()` trực tiếp (không inject Clock); test cooldown dùng assertion field
   `cooldownUntil` chứ không time-travel. Đủ cho contract, nhưng nếu Task 8/10 cần test boundary server-clock chặt
   hơn thì nên inject `Clock`.
5. **Engine chưa tách class:** phần dùng chung nằm trong `InteractiveVocabularyServiceImpl` (chưa extract thành
   interface strategy riêng). Tầng dữ liệu + state machine đã content-agnostic; xem §7/§8 để biết seam và recipe.

## 11. Đối chiếu "Điều kiện bắt đầu" của task5-to-task10 handoff

- Study session/version gắn membership snapshot + `source_revision` — **đạt** (`LearningStudySession.sourceRevision`).
- Flip/remember evidence có resume; mọi target nhớ trước issue — **đạt** (`LearningStudyCard` + `requireAllRemembered`).
- Immutable question/answer snapshot; answer key chỉ server — **đạt** (`LearningQuizSession` snapshot).
- Server-owned eligibility/score/attempt/review/cooldown — **đạt**.
- Pass ≥70%, tối đa 3 submit/cycle, fail 3 khóa 60 phút — **đạt**.
- First/latest/best + submit idempotency + lỗi/trùng không tốn lượt — **đạt** (submit idempotent; issue idempotency
  chặt là giới hạn §10.1, không tốn lượt).
- Owner scope principal → path → package → activity → membership/session — **đạt** (`findOwnedByIdForUpdate`).

→ Không có blocker đầu vào Task 5. Task 5 có thể bắt đầu; chỉ cần đọc §7/§8 để tái dùng engine qua strategy Kanji.
