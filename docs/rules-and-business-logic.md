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
- Bug found and fixed while building Task 6 (below): the Kanji study template passed `pageCss='interactive-kanji-study'` to the shared head fragment, but only `interactive-study.css` was ever built — the `<link>` silently 404'd and the Kanji page rendered completely unstyled. Fixed to `pageCss='interactive-study'` (verified `pageCss` only feeds that one `<link href>`, nothing else, in `fragments/head.html`); a regression test (`InteractiveStudyPageCssAssetTest`) now asserts every V2 study template's `pageCss` argument resolves to a real file.

### 2.13 Grammar practice & micro-check (V2 activity, Task 6) [JLAS-W0193]

- 1-3 approved `grammar_point` rows are snapshotted per package (Task 3, `GRAMMAR_POINT` membership). Each point must complete **both** an ordering practice ("xếp từ thành câu hoàn chỉnh") and a fill-blank practice ("điền phần còn thiếu") before that point counts toward the micro-check gate; the micro-check opens only once every point's gate is satisfied.
- Grammar has no flip/remember flashcard step, so it does not reuse the engine's study-card gate. Instead `InteractiveContentAdapter` gained two default methods — `requiresFlipRememberGate()` (default `true`, unchanged behavior) and `assertPracticeGateSatisfied(...)` (no-op default) — and `InteractiveStudyEngine.issueMicroCheck` branches on the former. Vocabulary and Kanji use neither override, so their behavior is byte-identical to Task 5 (proven by re-running their full suites unchanged). Grammar's adapter returns `false`/`false` and instead consults `learning_grammar_practice_progress` (new table, Task 6) for every membership target, throwing `PREREQUISITE_NOT_MET` if any point's `ordering_done`/`fill_done` pair is not both true **for the membership's current `source_revision`** (a re-composition resets stale progress rather than silently carrying it over). The micro-check's own attempt/cooldown/review/idempotent-submit machinery is not duplicated — it is the exact same `InteractiveStudyEngine` code Vocabulary/Kanji use, with `quiz_type = 'GRAMMAR_MICRO_CHECK'`.
- Component breakdown (`A / は / B / です`-style formula), the ordering-practice word pool, and the fill-blank target are all derived **deterministically** from the canonical `grammar_point.pattern`/`example` text — every seed pattern/example sentence is already whitespace-segmented into grammatical units, so a plain split is real segmentation of real approved content, not fabrication. Classification uses a small fixed, reviewed dictionary of Japanese particles/copulas (`GrammarTextAnalyzer`, `com.jlas.learning.service.impl.grammar`) applied identically to every point — never per-item or runtime AI output. The fill-blank prompt never contains the missing word.
- Micro-check questions are deliberately **not** the same artifact as practice: practice uses shuffled/blanked *fragments* of the example sentence, while the micro-check's two question types read from different fields/framing — `MEANING_MATCH` (from `grammar_point.meaning`, never shown during practice) and `USAGE_SELECT` (the *complete*, un-blanked example sentence as a multiple-choice option, never the shuffled/blanked practice artifact). Every point contributes at least these 2 questions (spec minimum), deterministic and no-AI, distractors drawn only from the batch's own canonical rows.
- Fill-blank grading normalizes both sides (trim, collapse whitespace, strip trailing 、。！？, case-fold) before comparing; ordering grading requires an **exact** permutation match to the canonical order (no partial credit — the spec does not grant any).
- Schema: new table `learning_grammar_practice_progress` (`sql/migrations/013_interactive_grammar_practice.sql`), owner-scoped via `activity_item_id → user_learning_path_item → user_learning_path → user`, one row per `(activity_item_id, grammar_point_id)`, pessimistic-locked on submit (`findForUpdate`) so a concurrent double-submit cannot corrupt the two flags. The same migration also widens `learning_quiz_session.chk_quiz_session_type` to include `GRAMMAR_MICRO_CHECK` **and** `KANJI_MICRO_CHECK` — the latter was a genuine Task 5 gap (H2's `ddl-auto=create-drop` derives its schema from JPA entities, not from `jlas_schema.sql`'s raw `CHECK`, so the missing value was never caught by a test) fixed here since both quiz types share the one constraint.
- Pages: `GET /learning/v2/grammar?activity={id}` (Thymeleaf `learning/interactive-grammar-study.html` + `static/js/pages/interactive-grammar-study.js`) calling `/api/learning/v2/activities/{activityId}/grammar/**` (`/study`, `/points/{grammarPointId}/practice/ordering`, `/points/{grammarPointId}/practice/fill`, plus the same `/micro-check/**` shape as Vocabulary/Kanji). The roadmap page now links an unlocked, non-passed Grammar activity to this page.

### 2.14 Dialogue trustworthy completion (V2 activity, Task 7) [JLAS-W0193]

- Dialogue is the only V2 activity **not** built on `InteractiveStudyEngine`: it has no flip/remember card, no micro-check, no score and no attempt cycle, so the engine's issue/submit/grade/cooldown shape does not fit. `InteractiveDialogueService`/`InteractiveDialogueServiceImpl` is a standalone, much smaller state machine reading `DIALOGUE_LINE` membership (canonical `content_item` ids from the composer's Task 3 snapshot, same `source_revision` reset-on-recomposition posture as every other activity) and owning exactly one evidence row per activity, `learning_dialogue_progress`.
- The page is read-only and served entirely from Learning Path's own route/template (`GET /learning/v2/dialogue?activity={id}`, `templates/learning/interactive-dialogue-study.html` + `static/js/pages/interactive-dialogue-study.js`); it never redirects to or embeds the textbook/dialogue module's own page. Script text, translation, role and audio URL are read through existing dictionary-module repositories (`DialogueLineDetailRepository`, `ContentAudioRepository`) — read-only, no dictionary-module code changed.
- **Active-time accounting is entirely server-computed.** The client sends a heartbeat (`POST /heartbeat` with only a client-generated `heartbeatToken`, no duration/timestamp/total) while the tab is visible. The server measures the gap since the previous heartbeat with its own clock and credits `min(gapSeconds, 20)` seconds — or `0` if the gap exceeds 30 seconds (the streak resets instead of crediting the whole gap) or there was no previous heartbeat yet (baseline only, first heartbeat of a session credits nothing). A heartbeat whose token exactly matches the last-accepted token is treated as a replay and is not credited again.
- **Bottom-reached evidence is a separate, dedicated event** (`POST /reached-bottom`), persisted once and never cleared. It is rejected with `VALIDATION_FAILED` if no prior heartbeat/progress row exists at all — a bare, out-of-the-blue "I reached the bottom" call with zero engagement evidence is refused rather than trusted.
- **Completion requires both** `accumulated_seconds >= 180` **and** `reached_bottom_at IS NOT NULL`, server-checked after every heartbeat and every reached-bottom call; `completed_at` is set exactly once and is never overwritten by a later call. The client cannot submit `{completed: true}` or `{totalSeconds: N}` anywhere — no such field exists in any Dialogue request DTO.
- Dialogue contributes to **package completion state only** — there is no Dialogue score field anywhere, by design, so Task 8's package score `round((vocabularyLatestScore + kanjiLatestScore + grammarLatestScore) / 3)` has no fourth term to accidentally include. Task 8 should treat `activity.activityState == COMPLETED` (not `PASSED`, which Dialogue never reaches) as its Dialogue-done signal.
- Ownership/locking mirrors Grammar exactly: `loadActivity` re-derives the activity from the JWT principal via `findOwnedByIdForUpdate` and rejects `activityType != DIALOGUE` as `NOT_FOUND`; the activity row's `FOR UPDATE` lock (acquired before the dialogue-progress row is touched) is what actually serializes two concurrent heartbeat/reached-bottom calls for the same activity, same mechanism the engine and Grammar's practice gate already rely on.
- Schema: new table `learning_dialogue_progress` (`sql/migrations/014_interactive_dialogue_progress.sql`), one row per `activity_item_id` (unique), owner-scoped via `activity_item_id → user_learning_path_item → user_learning_path → user`, pessimistic-locked on every write (`findForUpdate`).
- No score, no reward, no completion-event side effect is emitted here — those remain Task 8's responsibility (package-level completion, exactly-once event/reward).

### 2.15 Package completion and next action (V2, Task 8) [JLAS-W0193]

- **Intra-package activity unlock is cascading, not one-shot.** Once an activity reaches its own terminal
  state (`PASSED` for Vocabulary/Kanji/Grammar, `COMPLETED` for Dialogue), its successor in the composer's
  declared order transitions `LOCKED → STUDYING`. This walks the whole ordered list on every refresh so a
  learner who studies several activities before reloading still sees every eligible one unlocked in one pass,
  not just the next single step.
- **Package completion gate** requires all four activities present and each at its own terminal state
  (Vocabulary/Kanji/Grammar `PASSED`, Dialogue `COMPLETED`) — partial completion (e.g. three of four) never
  completes the package. Only `GOAL_LEARNING` packages are eligible (defensive: warm-up packages, when Task 9
  adds them, are not forced through this four-activity gate).
- **Score is computed from real data, never hard-coded.** Per activity, the skill score pools
  `Σcorrectcount / Σquestioncount` across every passing micro-check batch for that activity (never an average
  of per-batch percentages — spec §2.2 explicitly rejects that, since it over-weights small batches), read
  from the immutable `learning_quiz_session` snapshots (`status=SUBMITTED, passed=true`). Package score is
  `round((vocabularyScore + kanjiScore + grammarScore) / N)` where `N` is the count of those three skills that
  actually have a valid score in this package (currently always 3, since the composer requires all three —
  but the code does not assume that; an activity with no passing session is excluded from both the numerator
  and denominator, never counted as zero). Dialogue never contributes to either.
- **Reward/streak reuse the existing contract**, not a parallel mechanism: `UserCreditService.earn(userId,
  amount, CreditReason.STUDY_REWARD, "PACKAGE", packageId, note)` (same de-dupe-by-reference-id ledger every
  other reward in the app uses) plus the same streak-advance formula `LearningServiceImpl.advanceStreak`
  already uses for lesson completion (reused directly, not duplicated). Both are optional-autowired, matching
  `LearningServiceImpl`'s own pattern, so slice tests don't need to wire the reward stack.
- **Exactly-once is guaranteed by the caller's pessimistic lock** on the package row (`findOwnedByIdForUpdate`,
  the same idiom every V2 service already uses) plus a `status != COMPLETED` guard before the completion block
  runs — a concurrent or retried request that loses the lock race re-reads `COMPLETED` and skips the block
  entirely. `UserCreditService.earn`'s own duplicate-transaction check is a second line of defense specific to
  the credit ledger.
- **Next package unlock is one step at a time**: completing package N unlocks package N+1 (`LOCKED →
  AVAILABLE`) only, mirroring activity unlock's one-step posture; a package's `day_number` is never changed by
  this — "học vượt" reuses the existing `POST /packages/{id}/start` endpoint on that now-`AVAILABLE` package,
  it does not get a separate endpoint.
- **`justCompleted` is a request-scoped signal, not persisted state**: `InteractiveLearningPackageResponse`
  carries `scorePercent`/`nextAvailablePackageId`/`lastPackage` (always present once known) and `justCompleted`
  (true only on the exact call that performed the `IN_PROGRESS → COMPLETED` transition). This lets the roadmap
  show the three-action popup exactly once per completion (not on every subsequent visit to an already-
  completed package) without needing a separate "acknowledged" table — the completion business action itself
  stays idempotent regardless (spec §3.6 explicitly allows, but does not require, the popup to reappear).
- **Popup and navigation** (`static/js/pages/interactive-learning-path.js`): three actions — `Trở về lộ trình`
  (dismiss, stay on roadmap), `Học vượt` (calls `start` on `nextAvailablePackageId`, disabled when that is
  `null`), `Nghỉ ngơi` (dismiss). When `lastPackage` is true, the primary action becomes `Xem tổng kết chặng`
  and routes to `GET /learning/path/v2/summary` — a placeholder page this task adds routing for only; Task 10
  builds the real first/latest/best/readiness summary content there.
- **No new REST endpoint was added.** The design spec's §9 behavior group "Lấy package completion và next
  action" is folded into the existing `GET /api/learning/v2/packages/{packageId}` and
  `GET /api/learning/v2/paths/current` reads, which now also perform the idempotent unlock/completion
  transition before mapping — the same "a GET view performs a server-owned idempotent state transition"
  pattern `InteractiveStudyEngine.studyState` already established (`STUDYING → CHECK_READY` on load).
- Schema: `learning_path_package.score_percent` (nullable `INT`, additive) —
  `sql/migrations/015_interactive_package_completion.sql`.

### 2.16 Warm-up B personalization (V2, Task 9, 90%) [JLAS-W0193]

- **Weakness evidence is read exclusively through the existing `LearningProfileSource.loadForUser(...)`**
  (Smart Test's own read API, unchanged) — `WarmUpBComposerImpl` never queries Smart Test's tables
  directly and never accepts weakness data from request JSON. `LearningProfileSnapshot.weaknesses()`
  (`WeaknessEvidence`, pre-existing from JLAS-W0108) is the sole source for Vocabulary/Kanji/Grammar;
  `LearningProfileSnapshot.skillScores()` (`SkillScore`) is the source for Reading/Listening banding.
- **Vocabulary/Kanji/Grammar reuse the Task 4/5/6 engine unchanged via a smaller, weakness-scoped
  membership** — `WarmUpBComposerImpl` is a new, independent composition step (deliberately *not* an
  extension of Task 3's lesson-based `InteractivePackageComposer`, since a warm-up target must never be a
  whole lesson) that writes ordinary `learning_activity_member` rows (`VOCABULARY_ENTRY`/`KANJI`/
  `GRAMMAR_POINT`) under ordinary `WARM_UP`-phase `UserLearningPathItem` activities. The existing
  Vocabulary/Kanji/Grammar study/micro-check pages and `InteractiveStudyEngine` need zero changes — they
  already operate purely on an activity's own membership rows, regardless of how those rows were composed.
  No new schema was needed.
- **Item resolution, never a whole lesson:** Vocabulary uses `WeaknessEvidence.vocabularyEntryId()`
  directly (the assessment question's own vocabulary anchor) after confirming the entry is still
  `PUBLISHED`. Kanji resolves the evidence's lexeme (parsed from `canonicalId = "KANJI:<lexeme>"`) via
  `KanjiRepository.findByCharacter(...)` — only a single-character lexeme can resolve to one `kanji.id`; a
  multi-character lexeme (or any unresolvable anchor) is reported as a `CONTENT_MAPPING_PENDING` gap and
  skipped, never guessed. Grammar uses `WeaknessEvidence.grammarPointId()` after re-applying
  `WeakTagPolicy.isWeak(...)` (grammar evidence is not pre-filtered by wrong ratio the way Kanji/
  Vocabulary evidence already is at the source). All three additionally apply the existing warm-up level
  bound (§2.2: an item above the learner's assessed level is "never learned" and belongs to GOAL, not
  warm-up) and dedupe by resolved canonical id.
- **Reading/Listening banding reuses `WeakTagPolicy.comprehensionLessonCount(SkillScore)` verbatim** — the
  same approved `0/4/6/8` boundary function (`p>70→0`, `(50,70]→4`, `(40,50]→6`, `[0,40]→8`,
  `assessedCount<=0→-1` = insufficient evidence, never treated as 0%) V1 already uses (§5 "P3"). Wrapped by
  the new `ComprehensionWarmUpPlanner.band(SkillScore)`; a `-1` result becomes
  `ComprehensionBand.needsVerification() == true` with `lessonCount() == 0` — a distinct outcome from a
  proven weakness, never silently collapsed into "0% correct."
- **Reading/Listening packing** (`ComprehensionWarmUpPlanner.packIntoDays(...)`) caps at two combined
  Reading+Listening lessons per day (design spec §5.4) and additionally respects `minutesPerDay` via a
  documented per-lesson minute estimate, interleaving Reading/Listening within a day and never splitting a
  single lesson across days (same precedent as §2.4's "a lesson longer than the budget still takes the
  whole day"). **Not yet wired into persisted activities** — see the Task 9 completion handoff for the
  explicit scope decision (needs a new `LearningActivityType`/`LearningMemberType` + dedicated pages, a
  slice comparable in size to Task 4/6 by itself).
- **Grammar's per-day cap (design spec §5.3: at most 3 grammar points per warm-up day)** is enforced by
  chunking the deduped, sorted weak-point id list into groups of 3; the first chunk joins day 1 alongside
  Vocabulary/Kanji, and any remaining chunks each become their own subsequent `WARM_UP` package
  (Grammar-only, `day_number` incrementing) — never all lumped into one activity.
- **Provenance ("vì sao tôi học nội dung này") reuses the pre-existing
  `user_learning_path_item.reason`/`reason_code` columns** (already on the table since W0108, currently
  null for V2 `GOAL_LEARNING` activities) — no schema change. `WarmUpBComposerImpl` sets `reasonCode` to
  the existing V1 reason codes (`WEAK_VOCAB_ITEM`/`WEAK_KANJI_ITEM`/`WEAK_GRAMMAR_POINT`) and a Vietnamese
  `reason` sentence naming the count of items, matching the design spec's own example wording. Surfaced in
  the API via two new fields on `InteractiveLearningActivityResponse` (`reasonCode`, `reason`; both `null`
  for non-warm-up activities).
- **Warm-up package completion is a dynamic-activity-count sibling of Task 8's fixed four-activity gate.**
  `InteractiveLearningPathServiceImpl.refresh(...)` now branches on `learningPackage.getPhase()`:
  `GOAL_LEARNING` keeps Task 8's `allRequiredActivitiesDone` (exactly Vocabulary+Kanji+Grammar+Dialogue,
  all terminal); `WARM_UP` uses a new `allWarmUpActivitiesDone` that only requires every activity the
  package *actually has* (any non-empty subset of Vocabulary/Kanji/Grammar; Warm-up B never schedules
  Dialogue) to reach `PASSED`. This was explicitly anticipated by Task 8's own doc note ("warm-up packages,
  when Task 9 adds them, are not forced through this four-activity gate"). Score aggregation, exactly-once
  reward/streak, and next-package unlock are all reused unchanged — they were already phase-agnostic.
- **Ordering/scheduling relative to target packages:** `WarmUpBComposerImpl` composes before
  `InteractivePackageComposerImpl` in `LearningServiceImpl.createPath`, so warm-up occupies the earliest
  `day_number`/`order_index`, and target packages continue numbering after it (design spec §5.6: warm-up
  and target never share a day). The very first package overall starts `AVAILABLE`; every later package —
  including the first *target* package once any warm-up package exists — starts `LOCKED` and unlocks only
  through the existing next-package-unlock mechanism once its predecessor completes.
- **Two genuine integration bugs, found while wiring this and fixed narrowly (Task 3 scope, not Task 4-8):**
  `InteractivePackageComposerImpl`'s idempotency/retry check (`existsByLearningPathId`) was unscoped, so
  once any `WARM_UP` package existed for a path it made the target composer wrongly believe target packages
  already existed and silently compose zero of them — fixed to check `existsByLearningPathIdAndPhase(...,
  "GOAL_LEARNING")`. Its package `order_index` numbering also unconditionally restarted at 1, which would
  collide with an existing warm-up package's `order_index` (`uk_learning_package_path_order` is unique per
  path) — fixed to continue from the path's existing max package `order_index`. Both are covered by
  regression tests (`InteractivePackageComposerTest#composesTargetPackagesEvenWhenWarmUpPackagesAlreadyExistForThePath`).
- **Legacy V1 compatibility:** the legacy per-lesson-anchor persistence loop in `LearningServiceImpl` now
  also skips `WARM_UP`-phase scheduled resources when V2 is enabled (previously only `GOAL_LEARNING` was
  skipped — warm-up silently kept using the V1 model even under V2). This only changes behavior inside the
  `interactiveV2Enabled()` branch; `LEGACY_V1` path creation, and every legacy warm-up flashcard/gate-quiz
  table/endpoint, is untouched and still exercised unchanged by the existing legacy test suite.
- V2 continues to have no warm-up gate quiz, daily quiz, lesson quiz or stage-final quiz — Warm-up B
  activities complete purely via their own micro-check, same as every other V2 activity.
