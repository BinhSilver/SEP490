# Rules & Business Logic — Smart Test + Learning Path

> Tài liệu tổng hợp **toàn bộ rule / logic / nghiệp vụ** của hai mô-đun **Smart Test** và
> **Learning Path** vào một chỗ để dễ kiểm soát. Cập nhật lần cuối: 2026-09-27.
>
> Nguồn tham chiếu: code thật trong `src/main/java/com/jlas/assessment/**` (Smart Test) và
> `src/main/java/com/jlas/learning/**` (Learning Path); quyết định nghiệp vụ đã duyệt trong
> `docs/plan-2026-09-22-smart-test-learning-path-end-to-end.md` §12.
>
> **Nguyên tắc chung:** con số trong đây là *giá trị đã chốt*. Khi code khác với tài liệu này,
> ưu tiên giá trị đã duyệt trong plan §12 rồi mới đến code — và phải ghi lại sự khác biệt.

---

## 1. Smart Test

### 1.1 Mục tiêu (goal) và cấp độ

| Khái niệm | Giá trị | Ghi chú |
|---|---|---|
| Goal (nhu cầu) | `JLPT`, `BA`, `TRAVEL`, `DAILY_LIFE` | `BA` = BrSE (Kỹ sư cầu nối); survey ghi `BRSE` → chuẩn hóa về `BA` |
| `USAGE_GOALS` | `TRAVEL`, `DAILY_LIFE` | Thiên về sử dụng tình huống, không phải thi cử kiến thức |
| `GOALS_WITH_TARGET` | `JLPT`, `BA` | Bắt buộc có `targetLevel`; **N2/N1 chưa phát triển → chặn ở API** |
| `CURRENT_LEVELS` | `FROM_ZERO`, `UNKNOWN`, `N5`, `N4`, `N3`, `N2`, `N1` | `current_level` là **cấp độ**, tách biệt với `goal` |
| Kỹ năng | `KANJI`, `VOCABULARY`, `GRAMMAR`, `READING`, `LISTENING` | 5 skill |

**Chính sách vào bài test (entry policy):**

- `current_level = FROM_ZERO` → **không test**; chọn goal → lộ trình từ số 0.
- `current_level ∈ {UNKNOWN, N5…N1}` → làm Smart Test.
- `TRAVEL` / `DAILY_LIFE` → chỉ test khi user đã có cấp độ JLPT (`current_level ∈ N5…N1`)
  **và** đã học trên 6 tháng; ngược lại chặn với thông báo "cần học trên 6 tháng".
- `BA` → làm cùng JLPT (không hoãn).
- **Không cho user tự chọn skill trước khi test** — phải test đủ skill để có output cho lộ trình.

**Thứ tự kỹ năng theo goal:**

- JLPT / BA: `KANJI → VOCABULARY → GRAMMAR → READING → LISTENING`.
- TRAVEL / DAILY_LIFE: `READING → LISTENING → VOCABULARY → GRAMMAR → KANJI`.

**Ưu tiên tag theo goal (`GOAL_TAG_PRIORITY`) — dùng để chọn câu ưu tiên:**

| Goal | Preferred tags |
|---|---|
| JLPT | `JLPT` |
| BA | `BA` |
| TRAVEL | `TRAVEL`, `DAILY_LIFE` |
| DAILY_LIFE | `DAILY_LIFE` |

Câu khớp goal khi **tag chính** (`knowledge_tag.code`) HOẶC một tag phụ
(`context_json.additional_tags`) nằm trong preferred tags. Không trộn câu của goal khác
(vd JLPT không lẫn BA/DAILY_LIFE).

### 1.2 Chọn câu (selector)

- Ngân hàng câu: `assessment_question` với `is_active = true`, lọc theo `skill` + `jlpt_level`.
- **Randomize trong từng tier ưu tiên goal** (stable sort giữ thứ tự ngẫu nhiên trong mỗi tier).
- Loại: ID đã phát; và với KANJI, loại cả **từ kanji đã phát lặp** (dùng `context_json.lexeme/word`,
  fallback trích từ `explanation`).
- Mỗi batch cần đủ **5 câu khác từ nhau**; thiếu → lỗi "ngân hàng câu chưa đủ".

### 1.3 Adaptive policy (điều chỉnh cấp độ theo kỹ năng)

- `BATCH_SIZE = 5` câu/batch; `MAX_QUESTIONS_PER_SKILL = 25` câu/kỹ năng.
- Thang: `N5 → N4 → N3 → N2 → N1`.
- Hướng sau mỗi batch (`direction` theo số câu đúng trong batch 5):
  - `≤ 2` đúng → **hạ cấp** (`MOVE_DOWN`).
  - `= 3` đúng → **giữ cấp, cần xác nhận** (`NEEDS_SAME_LEVEL_CONFIRMATION`).
  - `≥ 4` đúng → **tăng cấp** (`MOVE_UP`).
- Các trạng thái kết thúc kỹ năng (`Decision.finish`):
  - `CONFIRMED_SAME_LEVEL`: batch xác nhận đạt 2–3 đúng.
  - `POST_CONFIRMATION_PROBE_PASSED`: probe cấp cao hơn đạt ≥ 4 đúng.
  - `POST_CONFIRMATION_PROBE_FELL_BACK`: probe cao hơn < 4 đúng → giữ cấp đã xác nhận.
  - `POST_CONFIRMATION_DOWNWARD_PROBE_FINISHED`: probe xuống → đóng ở cấp thấp hơn đã test.
  - `ABILITY_RANGE_STABLE`: đổi hướng ngược lại → đóng ở cấp dễ hơn.
  - `LEVEL_BOUNDARY_REACHED` / `MAX_QUESTIONS_REACHED`: hết cấp hoặc hết 25 câu (không trao cấp chưa test).

### 1.4 Chấm điểm & kết quả

- **Per-skill** (`smart_test_skill_result`): `estimatedLevel` (từ adaptive), `masteryScore`
  (điểm đúng/tổng theo %), `confidence` (`LOW`/`MEDIUM`/`HIGH`).
- **Overall**: `overall_mastery_score` (% đúng toàn bộ) + `estimated_overall_level`
  (nội suy từ vị trí các skill, trọng số theo confidence).
- **Confidence**: partial (chưa làm hết câu/skill) → `LOW`; ≤ 2 skill → `LOW`; còn lại theo
  min của các skill (skill ít hơn 5 thì giới hạn `MEDIUM`).
- **Ability band** (nhãn hiển thị): `Căn bản` / `Thành thạo` / `Nhập môn` gắn theo cấp độ + batch cuối.

### 1.5 Gom điểm yếu (weakness) — nguồn cho Learning Path

Ngưỡng kết luận "yếu" (policy `WeakTagPolicy`, `jlas.learning.selection.*`):

| Skill | Đủ bằng chứng | Yếu khi |
|---|---|---|
| KANJI / VOCABULARY | `wrongCount ≥ 1` | `wrongCount ≥ 1` (per-item theo lexeme/từ) |
| GRAMMAR | `assessedCount ≥ minimum-assessed-questions` (mặc định 1) | `wrong/assessed ≥ wrong-ratio-threshold` (mặc định 0.5) — theo `grammarPointId` |
| READING / LISTENING | `assessedCount ≥ 5` (cửa sổ 5) | `wrongCount ≥ 4` (sai 4–5/5) |

- **`NO_RESPONSE` (hết giờ) tính là SAI** ở tầng aggregation.
- **`TECHNICAL_FAILURE` bị loại khỏi mẫu số** trước khi vào policy.
- Sai 3/5 (reading/listening) chỉ là tín hiệu theo dõi, **không** kết luận yếu.
- Không có câu sai **không** đồng nghĩa "đã vững"; chỉ loại nội dung khi có **bằng chứng thành thạo**.

---

## 2. Learning Path

### 2.1 Hai giai đoạn

`WARM_UP` (ôn hổng kiến thức đã học nhưng yếu) → `GOAL_LEARNING` (học nội dung mới tới mục tiêu).
**Đổi phase luôn sang ngày mới** (không gộp cùng ngày).

### 2.2 Chọn tài nguyên (selection)

- **GOAL** chỉ lấy bài **ở trên** trình độ đã kết luận (loại trừ `assessedLevel`):
  `goalMin = min(rank(assessedLevel) + 1, rank(targetLevel))`.
  VD N5→N4 lấy đúng 25 bài N4; N5→N3 lấy N4+N3; N4→N4 giữ N4.
- Chỉ lấy lesson **published** từ sách **published**, trong dải level yêu cầu; không lấy DRAFT.
- **Warm-up** nhận item **≤ trình độ kết luận**; item sai ở cấp **cao hơn** trình độ kết luận là
  "chưa từng học" → phủ bởi GOAL, **không** warm-up và **không** báo gap.

**Reason code (mỗi resource đã chọn):**

| Reason | Ý nghĩa |
|---|---|
| `FULL_SKILL_REVIEW` | Skill rất yếu (<40% đúng) → ôn lại toàn bộ skill đó ở cấp hiện tại |
| `WEAK_GRAMMAR_POINT` | Ôn đúng mẫu ngữ pháp đã sai |
| `WEAK_KANJI_ITEM` | Ôn đúng từ Kanji đã sai |
| `WEAK_VOCAB_ITEM` | Ôn đúng từ vựng đã sai |
| `WEAK_READING_ITEM` | Luyện đọc cùng cấp sai |
| `WEAK_LISTENING_ITEM` | Luyện nghe cùng cấp sai |
| `WEAK_TAG` | Ôn theo tag bài (fallback khi chưa resolve item) |
| `GOAL_TARGET` | Học theo mục tiêu (phase GOAL) |

**Coverage gap (thiếu học liệu / thiếu bằng chứng):**

| Gap reason | Ý nghĩa |
|---|---|
| `INSUFFICIENT_ASSESSMENT` | Có điểm yếu nhưng chưa đủ mẫu để kết luận |
| `NO_ASSESSMENT_EVIDENCE` | Không có bằng chứng đánh giá nào |
| `MISSING_WARM_UP_RESOURCE` | Có item yếu nhưng catalog thiếu học liệu |
| `CONTENT_MAPPING_PENDING` | Kanji/Vocab thiếu anchor (`vocabularyEntryId` null) — chờ data mapping |

### 2.3 Quyết định ôn theo tỉ lệ đúng từng skill (multi-tier review)

Quyết định dựa trên **% đúng tính trên toàn bộ câu trả lời** (`aggregateSkillScores`, không lọc bỏ câu
đúng như trước — đây là nguồn gốc bug "ôn lại cả N5"). Ba tầng, theo thứ tự ưu tiên:

1. **Pass cấp hiện tại** — tổng `Σ correct / Σ assessed > 0.60` (`PASS_CORRECT_RATIO`): người dùng đã đạt
   cấp tự chọn trong survey → **bỏ warm-up**, vào thẳng GOAL (không ghi nhận điểm yếu).
2. **Skill rất yếu** — skill có `correctRatio < 0.40` (`FULL_REVIEW_CORRECT_RATIO`): ôn **toàn bộ bài của
   skill đó** ở cấp hiện tại, reason `FULL_SKILL_REVIEW`. Chỉ áp dụng cho skill có bài học chuyên biệt
   (`FULL_REVIEWABLE_SKILLS` = GRAMMAR / READING / LISTENING); KANJI / VOCAB **không** full-review mà giữ
   flashcard đúng từ sai (xem §2.4).
3. **Skill 40–60% đúng** — giữ ôn item/điểm cụ thể như bình thường (reason từng skill bên dưới).
   Skill đã full-review hoặc đã pass (correctRatio > 0.60) thì bỏ qua, không ghi nhận yếu.

### 2.4 Xếp lịch (scheduling)

- Ngân sách phút mỗi ngày (`minutesPerDay`, bắt buộc > 0); bài dài hơn ngân sách chiếm nguyên ngày.
- **Trần kỹ năng mỗi ngày** (trên nền ngân sách phút): tối đa **3 mẫu ngữ pháp** +
  **1 bài đọc** + **1 bài nghe** / ngày. Chạm trần → sang ngày mới.
- Không tách một bài ra nhiều ngày.

### 2.5 Idempotency & version

- **Khóa idempotency:** `(userId, assessmentAttemptId, assessmentRevision, policyVersion, status=ACTIVE)`.
- `policyVersion` = `WeakTagPolicy.version()` =
  `jlpt-per-skill-v2:min={minimum-assessed-questions}:ratio={wrong-ratio-threshold}:window=5/4`.
- Retry cùng identity → trả path cũ (không sinh version mới). **Attempt mới → version mới**,
  supersede path ACTIVE cũ (giữ nguyên `LessonProgress` đã hoàn thành).
- Profile phải `finalized`; chưa finalized → `CONFLICT`; sai user → `FORBIDDEN`.
- Không chấp nhận evidence từ request JSON — snapshot chỉ lấy từ profile source nội bộ.

### 2.6 Quiz (lesson quiz + gate quiz)

| Rule | Giá trị |
|---|---|
| Số câu mỗi quiz | 5 (`LESSON_QUIZ_SIZE`) |
| Ngưỡng đạt | 60% (`LESSON_QUIZ_PASS_PERCENT`) |
| Lượt quiz/ngày (chung lesson + gate) | 5 (`MAX_QUIZ_ATTEMPTS_PER_DAY`) |
| Số lần gate fail trước khi bắt ôn lại | 2 (`MAX_GATE_FAILURES_BEFORE_RELEARN`) |

- **Gate quiz** chỉ xuất hiện **sau khi warm-up hoàn thành**; chỉ hỏi **nội dung đã ôn**
  (anchor → ngân hàng trước → Gemini bù), không còn "vơ cả cấp" 5 đọc + 5 nghe theo `skill+level`.
- **Tính lượt khi bài nộp được xử lý hợp lệ**; retry cùng bài nộp không trừ thêm lượt;
  lỗi kỹ thuật không bị tính là một lượt hợp lệ. Hết lượt vẫn ôn tiếp bằng học liệu/flashcard.
- Fail đủ 2 lần trong ngày → yêu cầu quay về warm-up/flashcard trước khi thử lại.
- Chấm bằng answer key (AI **không** quyết định đúng/sai); câu đánh giá khác câu đã luyện.

### 2.7 Replan (lên kế hoạch lại)

- Luồng: **đề xuất → xem trước lý do/thay đổi → người học xác nhận → áp dụng**; xem trước không đổi path.
- Ngưỡng gợi ý: **> 90%** đề xuất tăng tốc; **< 60%** đề xuất ôn bổ sung.
- Bảo toàn: phần đã hoàn thành, lịch sử attempt, CP, streak; áp dụng lặp không tạo version/thưởng trùng.
- Bỏ/rút ngắn nội dung **chỉ dựa trên bằng chứng đã vững**; thay đổi thời lượng thay vì tự tăng khối lượng/ngày.

### 2.8 Warm-up flashcard (KANJI + VOCAB)

- Một **bộ flashcard gộp Kanji + từ vựng** cho warm-up (không tách 2 bộ); hiển thị một hoạt động duy nhất.
- Lật thẻ → chọn "Đã nhớ"/"Chưa thuộc" phải lưu tiến độ từng thẻ; tải lại tiếp tục từ thẻ chưa nhớ.
- Tự hoàn thành **chỉ khi mọi thẻ đã đánh dấu nhớ và lưu thành công**; thẻ chưa thuộc tiếp tục vòng ôn.
- Hoàn thành flashcard **không** hoàn thành bài nguồn, **không** xác nhận mastery, **không** tự vượt gate.
- Tái dùng `FlashcardService.create` (module flashcard của người khác — không sửa code); get-or-create
  khóa path để chống sinh bộ trùng; bộ mới khử trùng theo `expression + reading`.

### 2.9 Comprehension lesson (đọc/nghe)

Thứ tự resolve bài đọc/nghe: **đúng cấp → bài tổng hợp đúng cấp (comprehensive) → cấp gần nhất**,
**bỏ bài Kana có `jlpt_level = null`**. N5/N4 không có bài READING/LISTENING riêng — mỗi bài GRAMMAR
là bài tổng hợp chứa sẵn phần đọc + nghe.

### 2.10 Bài final cuối chặng & hoàn thành lộ trình

- Bài final gộp **1 phiên**, đủ 5 kỹ năng (Kanji/Từ vựng/Ngữ pháp/Đọc/Nghe), lấy từ ngân hàng
  `assessment_question` theo đúng level của chặng — không tách 5 phiên riêng.
- Mở khi: chặng đang `ACTIVE` **và** mọi item goal-learning của chặng đã `STUDIED`. Chặng
  `PENDING`/`COMPLETED` không phát/nhận nộp được (`CONFLICT`).
- Đạt: **trung bình 5 kỹ năng ≥ 80%** VÀ **từng kỹ năng ≥ 70%** VÀ **≥ 10 câu hợp lệ/kỹ năng**.
  Điểm tổng cao không bù được một kỹ năng dưới ngưỡng.
- Đạt → chặng này `COMPLETED`; còn chặng `PENDING` kế tiếp (theo `stageIndex`) → chặng đó
  `ACTIVE` (item đã lên lịch sẵn từ lúc tạo path, không sinh lại lịch); là chặng cuối →
  `UserLearningPath.status = COMPLETED`, ghi `completed_at`.
- Quota: dùng chung cơ chế 5 lượt nộp hợp lệ/ngày (§2.6), nhưng khóa theo `stage_id` riêng —
  không trừ vào/bị trừ bởi lượt lesson/gate quiz.
- Không cải thiện điểm 2 lần retest liên tiếp (delta ≤ 0) → gợi ý kiểm tra lại kiến thức nền của
  chặng; **không khóa** lượt làm tiếp (khác gate quiz fail-2-lần).
- **Chứng chỉ thành tựu nội bộ:** chỉ cấp khi hoàn thành **toàn bộ lộ trình** (chặng cuối), không
  cấp theo từng chặng. Trang `/learning/certificate/{pathId}`, dựng trực tiếp từ dữ liệu path
  `COMPLETED` (không có bảng "chứng chỉ đã cấp" riêng). Ghi rõ đây là chứng nhận nội bộ, không
  phải điểm/chứng chỉ JLPT chính thức.
- `COMPLETED` là trạng thái cuối của path: `findByUserIdAndStatus(userId, "ACTIVE")` (dùng bởi
  replan và path-creation idempotency) không còn khớp một path đã `COMPLETED`, nên replan trên
  path đã hoàn thành tự nhiên trả `NOT_FOUND` mà không cần logic chặn riêng.

---

## 3. Gemini — cá nhân hóa lộ trình & bài test (JLAS-W0108)

**Vai trò (đã chốt lại 27/09):** Gemini **chỉ viết narrative** (giải thích kế hoạch deterministic),
best-effort, không chặn tạo path/quiz khi lỗi. **Không gọi Gemini sinh câu quiz ở runtime** — mọi câu
quiz đến từ ngân hàng đã duyệt, chấm bằng answer key. Đường "Gemini bù câu" (§3.2 bên dưới) là **mô tả
cũ đã bị bỏ**; `LearningQuizGenerator` + `learning_generated_question` ngừng dùng ở runtime (xem plan
master §7.3). Engine deterministic giữ làm xương sống (ground DB thật, idempotency, chấm điểm).

### 3.1 Route narrative (tăng cường)

- Input (ground): `LearningProfileSnapshot` + `WeaknessEvidence` + `SmartTestSkillResult` +
  tóm tắt lịch đã schedule.
- Output JSON: `{ overview, phases:[{ phase, strategy, focus, tips }] }` → lưu cột
  `user_learning_path.ai_plan_json` (TEXT).
- Gọi **best-effort sau khi schedule**, non-fatal; Gemini lỗi → UI vẫn chạy, không có narrative.
- UI render trên `/learning/path` (khối `.lp-ai-plan`).

### 3.2 Test "ngân hàng trước, Gemini bù" — SUPERSEDED (không dùng ở runtime)

1. Gom anchor "học gì" từ item WARM_UP (`grammarPointId` / `vocabularyEntryId` / `knowledgeTag`).
2. Với mỗi anchor, query `assessment_question` theo anchor (dedup theo question.id) → câu curated.
3. Anchor **thiếu** curated → `LearningQuizGenerator` với prompt ground theo anchor +
   smart test result → JSON `{ questionText, options[4], correctOptionIndex, explanation }`.
4. Lưu câu Gemini vào **bảng riêng** `learning_generated_question` (không đụng ngân hàng Smart Test);
   id `-id` (âm) để không đụng ID câu ngân hàng; idempotency key `(learning_path_id, anchor_type, anchor_id)`.
5. Chấm điểm so `selectedOption` với `correctOption` — self-consistent giữa hai nguồn.
6. **Fallback:** Gemini lỗi → quiz chỉ còn câu curated (vẫn chạy); anchor vừa thiếu vừa không sinh được → bỏ qua.

**Config (đặt trong `application-local.yaml`, env fallback):** `gemini-api-key` (hoặc `GEMINI_API_KEY`),
`gemini.api.url`, `gemini.model`, `gemini.temperature`, `gemini.max-output-tokens`, `gemini.json.max-retries`.

---

## 4. Chính sách dữ liệu & cross-cutting

- **Schema:** sửa trực tiếp `jlas_schema.sql` kèm note `[JLAS-XXXX]`; seed data để file riêng
  (`db/seed_data_jlas.sql`); migration chưa hoàn thiện để `sql/migrations/NNN_*.sql`.
- **`ddl-auto: validate`:** mọi entity `@EntityScan` phải có bảng/cột trước khi start.
- **Chuẩn hóa từ vựng:** `normalized_expression`/`normalized_reading` =
  `katakana → hiragana( NFKC(text) )`; `ー` (U+30FC) và `・` (U+30FB) giữ nguyên;
  `kanji_reading.reading_text` **không** dùng quy tắc này.
- **Bí mật:** `application-local.yaml`, `db/local/` là gitignored — không push; origin đang nhúng
  credential → revoke trước khi push.

---

## 5. Bảng giá trị code hiện tại (quick reference)

> Bảng dưới mô tả hằng số trong code tại thời điểm ghi nhận, không phải toàn bộ policy nghiệp vụ mới đã duyệt. Xem [plan JLPT N5–N1](superpowers/plans/2026-09-27-jlpt-n5-n1-business-master-plan.md) cho ngưỡng đọc/nghe 0/4/6/8, quiz theo activity và các thay đổi dự kiến. Chưa đổi code thì không ghi các giá trị mới thành hành vi đã triển khai.

| Hằng số | Giá trị | Nơi |
|---|---|---|
| Batch adaptive | 5 câu | `SmartTestAdaptivePolicy.BATCH_SIZE` |
| Max câu/kỹ năng | 25 | `SmartTestAdaptivePolicy.MAX_QUESTIONS_PER_SKILL` |
| Hướng batch | ≤2 hạ / =3 giữ / ≥4 tăng | `direction()` |
| Cửa sổ R/L weakness | 5 câu, sai ≥ 4 | `WeakTagPolicy` |
| Threshold GRAMMAR yếu | `wrong/assessed ≥ 0.5` (mặc định) | `jlas.learning.selection.wrong-ratio-threshold` |
| Pass cấp hiện tại | `Σcorrect/Σassessed > 0.60` | `PASS_CORRECT_RATIO` |
| Skill rất yếu (full review) | `correctRatio < 0.40` | `FULL_REVIEW_CORRECT_RATIO` |
| Trần ngày | 3 ngữ pháp + 1 đọc + 1 nghe | `LearningScheduleServiceImpl` |
| Quiz pass | 60% | `LESSON_QUIZ_PASS_PERCENT` |
| Quiz size | 5 câu | `LESSON_QUIZ_SIZE` |
| Lượt quiz/ngày | 5 | `MAX_QUIZ_ATTEMPTS_PER_DAY` |
| Gate fail → ôn lại | 2 | `MAX_GATE_FAILURES_BEFORE_RELEARN` |
| Final pass tổng | 80% | `STAGE_FINAL_PASS_PERCENT` |
| Final pass mỗi kỹ năng | 70% | `STAGE_FINAL_SKILL_PASS_PERCENT` |
| Final số câu/kỹ năng (phát đúng, không dư) | 10 | `STAGE_FINAL_QUESTIONS_PER_SKILL` |
| Replan gợi ý | >90% tăng tốc / <60% ôn bổ sung | §2.7 |

## Interactive Learning Path V2 composition [JLAS-W0193]

- V2 generation is controlled by `learning.interactive-v2.enabled` and remains disabled by default until all activity pages are released.
- A target package is publishable only when its source lesson and textbook are `PUBLISHED`, the expected JLPT level matches, and it has published vocabulary, contextual Kanji with readings, one to three grammar points with examples, and dialogue script plus audio.
- Missing, unpublished, duplicate or wrong-level content produces explicit coverage-gap codes. The composer publishes no package from an incomplete batch and never substitutes arbitrary lessons or runtime AI output.
- Package membership stores canonical IDs and a SHA-256 source revision. Canonical lesson/dictionary content remains owned by the dictionary module; Learning Path does not copy it into a second source of truth.
- Composition is transactional and idempotent per path. It preserves existing warm-up item order/day values, attaches target packages to their stage, and emits exactly four ordered activities: Vocabulary, Kanji, Grammar, Dialogue.

### 2.11 Vocabulary study & micro-check (V2 activity) [JLAS-W0193]

- The Vocabulary activity is owned and gated server-side: only memberships of the activity the user actually owns are ever studied. Client ID lists are never trusted — the server always re-reads `VOCABULARY_ENTRY` membership by activity/package/owner from the principal.
- A study session carries the membership `source_revision` at creation, so a later re-composition does not silently change what the learner is mid-way through. Reloading the activity resumes the same open session rather than starting a new one.
- Each card must be **flipped** before it may be marked *Đã nhớ* (remembered) or *Chưa nhớ* (forgotten). A forgotten card resets its flip and re-enters the review queue; the session is not finished until every membership is remembered.
- The micro-check opens (`CHECK_READY`) only after every card is remembered. It covers **every** target, split into batches of at most 10 questions, mixing word→meaning, meaning→word, reading→word and matching question types.
- Questions and the answer key are written once into an immutable `learning_quiz_session` snapshot (`VOCAB_MICRO_CHECK`). The answer key never leaves the server; option indices are **1-based** everywhere (submit `@Min(1)` and grading use the same convention).
- A batch passes at **≥70%** correct (`70` passes, `69` fails). The server persists **first** score (first valid submit, never overwritten), **latest** score and **best** score per batch, and allows at most **3** valid submits per cycle.
- Failing attempt 1 or 2 shows the missed targets and allows an immediate retry; only the **third** failure imposes a **60-minute** server-side cooldown plus a mandatory review of the missed targets before a new cycle opens.
- Activity state is decided server-side only: `STUDYING` → `CHECK_READY` (every card remembered) → `PASSED` (all batches ≥70%) or `COOLDOWN` (third failure). After the review is completed and the 60 minutes elapse, a fresh cycle of three attempts opens.
- Submit is idempotent — re-submitting the same issued attempt re-returns the stored result — and validation, technical and duplicate errors never consume an attempt. The server — not the client — decides state, score, attempts and cooldown from its own clock.

### 2.12 Kanji study & micro-check (V2 activity, Task 5) [JLAS-W0193]

- Same server-owned contract as §2.11 (owner-scoped membership, flip-before-remember with resume, immutable per-batch snapshot, ≥70% pass, first/latest/best score, max 3 valid submits per cycle, third failure ⇒ 60-minute cooldown + mandatory review, idempotent submit, server clock only). Kanji uses `KANJI` membership/activity type and its own `learning_quiz_session.quiz_type = 'KANJI_MICRO_CHECK'`, entirely separate rows from Vocabulary's — a Kanji score never compensates or overwrites the Vocabulary score of the same package.
- Implementation: the state machine lives once in `InteractiveStudyEngine<T>` (`com.jlas.learning.service.impl.engine`), extracted from the original `InteractiveVocabularyServiceImpl` so Kanji does not duplicate it. Each content type supplies only an `InteractiveContentAdapter<T>` (activity/member type, canonical bulk-load, question generation); `InteractiveVocabularyServiceImpl` and `InteractiveKanjiServiceImpl` are thin adapters that map the engine's generic `EngineStudyState<T>` into their own DTO (`VocabularyStudyState` / `KanjiStudyState`).
- Micro-check questions combine four shapes: Kanji→reading, reading→Kanji, Kanji→meaning and matching (Kanji↔meaning), round-robin per target the same way Vocabulary combines its three select types + matching. Distractors are drawn only from the batch's own canonical `kanji`/`kanji_reading` rows — never generated at runtime.
- A Kanji card only ever renders fields with real, approved data: On/Kun readings are an empty list (never fabricated) when `kanji_reading` has no row of that type for the character; a reading-based question type falls back to Kanji→meaning for that target rather than inventing a reading.
- No schema change was needed: `learning_activity_member`, `learning_study_session/card`, `learning_micro_check_state` and `learning_quiz_session` were already content-agnostic from Task 4 (generic `canonical_id`/`member_type`/`quiz_type` columns), which Task 5 confirmed by reusing them unchanged for `KANJI`.
- Pages: `GET /learning/v2/kanji?activity={id}` (Thymeleaf `learning/interactive-kanji-study.html` + `static/js/pages/interactive-kanji-study.js`, same view-model/render/mount shape as Vocabulary's page) calling `/api/learning/v2/activities/{activityId}/kanji/**`. The roadmap page (`interactive-learning-path.js`) links an unlocked, non-passed Kanji activity to this page the same way it already links Vocabulary.
