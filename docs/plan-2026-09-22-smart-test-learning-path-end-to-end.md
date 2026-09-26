# Kế hoạch công việc 2026-09-22 — Hoàn thiện luồng Smart Test → Lộ trình học → Sách số hóa

> Nhánh làm việc: `feat/JLAS-W0090-smart-test-fix`. Người thực hiện: TNguyen47.
> Mục đích: chốt luồng end-to-end trước khi báo cáo mentor, đủ để review + PR + Jira theo quy trình team.

## 0. Tóm tắt 1 dòng cho mentor

Hoàn thiện luồng **learner làm Smart Test → hệ thống sinh lộ trình học 2 giai đoạn (ôn hổng + học theo mục tiêu) → học bằng bài từ sách số hóa qua lesson player**; phần còn lại hôm nay là **nối 3 mô-đun đã xong** + **validate E2E (W0099)**.

## 1. Hiện trạng (đã xác minh trong repo, không phải đoán)

| Mô-đun | Ticket | Trạng thái | Bằng chứng |
|---|---|---|---|
| Smart Test — review từng câu + giải thích | JLAS-W0090 | ✅ Xong | commit `172d3e1`, `ResultView.review` |
| Smart Test — breakdown theo grammar point + bài | JLAS-W0095 | ✅ Xong | commit `f605220`, `GrammarPointResultView` |
| Smart Test — knowledge vs usage (goal gating) | JLAS-W0103 | ✅ Xong | commit `39dd92c`, `9c36200`, `36cfadc` |
| Learning Path — selector 2 giai đoạn | JLAS2-123 | ✅ Xong | commit `68fb7b6` |
| Learning Path — lưu/đọc + migration | JLAS2-124 | ✅ Xong | commit `427586e`, `cb9466f` |
| Learning Path — QA backend + MySQL | JLAS2-127 | ✅ Xong | commit `7b8ea8b` |
| Learning Path — UI 2 giai đoạn | JLAS2-129 | ✅ Xong | commit `eb58bda` |
| Learning Path — sinh + xem (parent) | JLAS-W0108 | ✅ Xong | commit `8ec3830`, `323de6e` |
| Lesson player + progress | JLAS-W0119 / W0120 / W0121 | ✅ Xong | commit `45ba4ab`, `f9b6fd3`, `c927024` |
| Sách số hóa (nội dung bài) | JLAS2-20 / 22 | ✅ Đã merge | API `/api/dictionary/lessons/{id}/content` |
| **Nối luồng (adapter thật Smart Test → Learning Path)** | *(tích hợp, chưa có ticket riêng)* | ❌ **Chưa** | chỉ có `LocalLearningProfileSource` (demo) |
| **Per-tag aggregation cho Learning Path** | *(gap tích hợp)* | ❌ **Chưa** | Smart Test mới có per-skill + per-grammar-point, thiếu per-tag |
| **Validate E2E toàn luồng** | JLAS-W0099 | ❌ **Chưa chạy** | cần bằng chứng browser + API |
| **integration-contract.md** | (design JLAS2-123 yêu cầu) | ❌ **Thiếu file** | `docs/learning/` chưa có |

**Kết luận:** 3 mô-đun đã xong riêng lẻ. Công việc hôm nay = **đóng 3 gap tích hợp** (adapter, per-tag, trigger) rồi **chạy W0099** để có luồng thật, sau đó commit/PR theo rule.

## 2. Gap tích hợp cần đóng (đây là trọng tâm hôm nay)

1. **Per-tag aggregation.** Learning Path cần `TagAssessmentResult{tagCode, assessedCount, wrongCount}` (theo *knowledge tag* = tag bài, vd `N5-B07`). Smart Test hiện chỉ trả per-skill + per-grammar-point (`GrammarPointResultView.lessonCode` là mã bài của *mẫu*, không phải tag bài tổng hợp). → Cần Smart Test emit thêm breakdown per-tag, hoặc adapter roll-up từ `lessonCode`/`review` về tag. **Đây là việc phải chốt, không phải chỉ nối dây.**
2. **Production profile adapter.** Viết `SmartTestLearningProfileSource` (impl `LearningProfileSource`) đọc `assessmentAttemptId` thật → `LearningProfileSnapshot` (ownership, finalized, assessedLevel, targetLevel, goalCode, minutesPerDay, tagResults). `LocalLearningProfileSource` giữ làm demo local-seed, không dùng production.
3. **Trigger + CTA.** Kết quả Smart Test → nút "Tạo lộ trình" → gọi `POST /api/learning/paths` (endpoint đã có). Idempotency đã chốt: retry cùng identity trả `200` path cũ, attempt mới → version mới.

## 3. Kế hoạch chi tiết theo thời gian

### Buổi sáng (9:00 – 12:00) — ổn định + đối chiếu contract

- [ ] **9:00** Xác nhận baseline: `git status` sạch (chỉ còn 3 file đã biết: `jlas_schema.sql` line-ending, `TagRepository.java` fix bean, `application.yaml` local). App boot qua `ddl-auto: validate`.
- [ ] **9:15** Đồng bộ DB local với `jlas_schema.sql` (chạy câu `ALTER` còn thiếu: `uk_flashcard_session_creation`, `idx_flashcard_session_history`, cập nhật `chk_flashcard_session_type` thêm `SPEED_RECALL`) — xong phần đang dở từ sáng.
- [ ] **9:30** Đối chiếu contract: đọc `SmartTestResponse.ResultView` thực tế ↔ `LearningProfileSnapshot` cần gì. Viết `docs/learning/integration-contract.md` (design JLAS2-123 đã yêu cầu): liệt kê field mapping + 2 ví dụ input/output, ghi rõ **per-tag aggregation lấy từ đâu**.
- [ ] **10:30** Chốt cách lấy per-tag: (a) Smart Test emit thêm `List<TagResultView>` per knowledge tag, hay (b) adapter roll-up từ `grammarPoints.lessonCode` + `review`. Ghi quyết định vào contract. *(Nếu chưa chắc, đưa ra 2 phương án để hỏi mentor/team — không tự chọn ngầm.)*
- [ ] **11:00** Bắt tay viết adapter thật `SmartTestLearningProfileSource` + test contract (ownership, chưa finalized, sai user → 403, thiếu revision).

### Buổi chiều (13:00 – 17:00) — đóng gap + nối luồng

- [ ] **13:00** Hoàn thiện per-tag aggregation phía Smart Test (theo quyết định 10:30) + test.
- [ ] **14:00** Nối trigger: result page → CTA "Tạo lộ trình" → `POST /api/learning/paths`; verify idempotency (retry không sinh version mới).
- [ ] **14:30** Catalog mapping: xác minh `JpaLearningCatalogSource` chỉ lấy lesson **published** từ sách số hóa (tag, level, duration, order), không lấy DRAFT.
- [ ] **15:00** Lesson link: path item → lesson player (W0119/120/121) + `/api/dictionary/lessons/{id}/content`; không render link chết (rule JLAS2-129).
- [ ] **15:30** Chạy `mvn test` + `node --test src/test/frontend/learning-path.test.cjs` + `git diff --check`.

### Cuối ngày (17:00 – 18:00) — W0099 E2E + bàn giao

- [ ] **17:00** W0099 E2E thật trên browser: survey → Smart Test → result → tạo path → reload giữ nguyên 2 phase → mở lesson → progress; chụp bằng chứng.
- [ ] **17:30** Commit từng phần theo W-code, push nhánh, mở PR (base `develop`).
- [ ] **17:45** Tóm tắt tiến độ + việc cần chốt để báo cáo mentor.

## 4. Phân công theo W-code (bản đồ task → việc hôm nay)

| Ticket | Việc hôm nay | Đầu ra |
|---|---|---|
| JLAS-W0095 | Thêm per-tag aggregation (nếu chọn phương án a) | field + test trong `SmartTestResponse` |
| JLAS2-124 (tích hợp) | `SmartTestLearningProfileSource` + bỏ demo khỏi production | impl + contract test |
| JLAS2-123 (tích hợp) | `integration-contract.md` + mapping | doc + 2 ví dụ |
| JLAS-W0108 (tích hợp) | Trigger CTA + nối result→path | endpoint được gọi thật |
| JLAS2-129 (tích hợp) | Lesson link đến player thật | không link chết |
| JLAS-W0099 | E2E toàn luồng + bằng chứng | browser/API evidence |
| *(mới)* | Adapter thật + per-tag | xem mục 2 |

## 5. Nguyên tắc team / git / chuyên nghiệp (nhắc lại, bắt buộc tuân thủ)

- **Nhánh:** chỉ làm trên feature branch (`feat/JLAS-W0090-smart-test-fix`), **không commit/push/merge vào `develop`**; thay đổi lên develop qua PR do maintainer merge. Pull từ develop thì được.
- **Commit:** tiếng Anh, **1 Jira ID / commit**, dạng `fix(JLAS-W####): ...` hoặc `feat(JLAS2-###): ...`; **không kèm `Co-Authored-By`**.
- **Schema:** sửa trực tiếp `jlas_schema.sql` kèm note `[JLAS-XXXX]`; feature **chưa hoàn thiện → để migration riêng `sql/migrations/NNN_*.sql`**, chỉ gộp vào schema chung khi hoàn thiện; seed data để file riêng.
- **Bí mật:** `application-local.yaml` (password DB/mail/API key), `db/local/` là gitignored — **tuyệt đối không push**. `application.yaml` đang có sửa local (2 dòng trắng) — không commit.
- **Không đụng task người khác** trừ khi cần; làm từng phần nhỏ, chạy web xác nhận từng phần.
- **`ddl-auto: validate`**: mọi entity được `@EntityScan` đều phải có bảng/cột, kể cả tính năng chưa dùng — migration phải apply đủ trước khi start.

## 6. Định nghĩa hoàn thành (Definition of Done — hôm nay)

1. Learner làm survey → Smart Test → kết quả **thật** (không demo) tạo được lộ trình 2 giai đoạn.
2. Adapter production đọc đúng ownership/finalized/revision; sai user → 403; chưa finalized → 409.
3. Retry cùng identity không sinh version mới; attempt mới SUPERSEDE path cũ.
4. Path item trỏ đúng lesson **published**, mở được lesson player + nội dung sách số hóa.
5. `mvn test` + JS test + `git diff --check` xanh; có bằng chứng browser cho W0099.
6. Commit theo W-code, push, PR mở (base `develop`), không có file bí mật nào trong diff.

## 7. Rủi ro & việc cần chốt với mentor/team

- **Per-tag aggregation nguồn ở đâu** (Smart Test emit thêm field vs adapter roll-up) — ảnh hưởng phạm vi W0095, cần chốt sớm.
- **Trigger tự động vs người dùng bấm** — đã chốt "bấm tạo" theo design 2026-09-15, giữ nguyên.
- **Scope chương trình JLPT** (sách nào làm curriculum cho GOAL_LEARNING) — design ghi do `LearningScopeSource` resolve, production impl là dependency ngoài; nếu chưa có, hôm nay dùng local-seed scope cố định và **ghi rõ giới hạn** khi báo cáo.
- **Jira tenant đang `suspended-inactivity`** (`twg` không fetch được) — tiến độ Jira cập nhật thủ công qua web nếu cần, không chặn việc code.

---

## 8. Đối chiếu board 35 (24/09) — task còn thiếu, bổ sung vào plan

> Đối chiếu toàn bộ 279 issue board 35 với code + plan hiện tại. Kết luận: **Smart Test đủ, Learning Path còn 4 story, tra từ (look up) còn 2 story.**

### 8.1 Smart Test (epic JLAS-W0084) — ✅ không còn task

W0090 / W0095 / W0099 / W0103 đều đã làm (đã xác minh ở mục 1). W0085 (Weakness Notebook) **assignee Phạm Đức Minh**, không thuộc phần mình; entity `UserKnowledgeWeakness`/`UserLessonRecommendation` đã có sẵn trong repo nhưng phần "hiển thị notebook" là scope người khác — chỉ theo dõi, không đụng.

### 8.2 Learning Path (epic JLAS-W0107) — còn 4 story

| Ticket | Story | Sprint | Trạng thái | Subtasks |
|---|---|---|---|---|
| **JLAS-W0113** | Complete Lesson Quiz + review từng đáp án | S4 (10–17/10) | ❌ Chưa làm | W0114 BE attempt/validate/submit · W0115 FE quiz UI · W0116 QA · W0117 BE chấm điểm + chi tiết đáp án |
| **JLAS-W0122** | Replan lộ trình còn lại | S5 (18–31/10) | ❌ Chưa làm | W0123 AI/Data rules · W0124 FE · W0125 BE replan · W0126 QA |
| **JLAS-W0127** | Daily Goal + Credit Points + Streak | S4 (10–17/10) | ⚠️ Có module rời, chưa nối lesson | W0128 BE ledger/streak · W0129 FE widget · W0130 QA · W0131 BE daily-goal events |
| **JLAS-W0132** | Learning Path E2E | S6 (01–14/11) | ❌ Chưa làm | W0133 QA kịch bản · W0134 DOC · W0135 QA replan |

**W0113 scope:** load quiz theo lesson → hiển thị câu hỏi/lựa chọn/tiến độ/trạng thái nộp → chấm bằng answer key (AI **không** quyết định đúng sai) → hiển thị đúng/sai + đáp án + giải thích từng câu → retry theo rule → ghi wrong item vào Weakness Notebook → cập nhật lesson + path state → sinh input cho Replan.

**W0122 scope:** consume lesson progress + quiz result + wrong item + weakness + daily goal + time → **bảo toàn** item đã hoàn thành/attempt/CP/streak → tạo proposal có giải thích + cần learner xác nhận → version path, chống replan trùng.

**W0127 lưu ý:** repo đã có module `mission` (`UserStreaks`, `UserDailyProgress`, `UserLearningTargets`) và `CreditController` `/api/credits`. Cần đối chiếu xem đó là Daily Missions (W0193, epic gamification của người khác) hay chính W0127; phần **chưa có** là nối event `LESSON_STARTED/PROGRESS_UPDATED/COMPLETED/QUIZ_COMPLETED` → central CP ledger + chống cộng lặp theo timezone. Không đụng module người khác; chỉ làm phần integration thuộc scope mình.

**W0132 scope:** chạy đủ Smart Test → weakness → path → lesson progress → quiz → CP/Streak → replan → needs-based path; verify navigation/authorization/idempotency/data integrity; có bằng chứng API + UI. Đây là bản E2E **đầy đủ hơn** W0099 (W0099 chỉ là Smart Test E2E).

### 8.3 Tra từ / Look up (epic JLAS-W0136) — còn 2 story

| Ticket | Story | Sprint | Assignee | Trạng thái | Subtasks |
|---|---|---|---|---|---|
| **JLAS-W0137** | Search Vocabulary/Kanji + Global Search | S4 (10–17/10) | Binh Nguyen | ❌ Chưa làm | W0138 BE global search + lọc JLPT + sort · W0139 BE search kanji/kana/romaji + autocomplete · W0140 FE kết quả + chi tiết · W0141 QA |
| **JLAS-W0142** | Favorites + Lookup History + Inline Dictionary + Furigana | S6 (01–14/11) | **Thảo Nguyên (mình)** | ❌ Chưa làm | W0143 FE inline + furigana + quick-save · W0144 BE favorites/history/quick-save API · W0145 QA |

**W0137 scope:** search theo Kanji/Kana/Romaji → normalize + rank exact trước từ ghép liên quan → hiển thị nghĩa/reading/kanji chi tiết + pagination → tra đơn kanji + từ ghép → dùng data Dictionary/Kanji đã import, không trùng hiển thị. ⚠️ **Assignee Binh Nguyen** — cần confirm trước khi đụng (rule "không đụng code người khác"); nhưng là dependency của W0142.

**W0142 scope:** chọn text trong lesson → inline lookup → hiển thị furigana + nghĩa không rời lesson → add/remove favorite → view/delete history → ownership + idempotent. Entity `DictionaryFavorite`/`DictionarySearchHistory` **đã có sẵn**, chỉ thiếu API + UI. Phụ thuộc W0137.

**Bằng chứng code:** `DictionaryController` chỉ có textbooks/topics/lesson-content/status; `DictionaryService` dòng 18 còn ghi `TODO: Thêm các phương thức search, lookup, favorite...`.

### 8.4 Thứ tự triển khai gợi ý (theo dependency)

1. W0113 Lesson Quiz (S4) — song song với W0127 Daily Goal/CP/Streak (W0127 nối event sau khi W0113/W0118 có event).
2. W0137 Search (nếu được làm) → W0142 Favorites/History/Inline (S6, phụ thuộc W0137 + lesson player).
3. W0122 Replan (S5) — cần quiz + progress + weakness + daily goal làm input.
4. W0132 E2E (S6) — chạy sau khi W0113/W0122/W0127 xong.

---

## 9. (25/09) Sửa lỗi lộ trình — phân tích root cause + plan sửa (Lát 2.5)

> Phản hồi từ chạy web thật (nhánh `feat/JLAS-W0108-jlpt-path-full-coverage`). Bốn lỗi ghi nhận:
> (1) warm-up lấy nguyên bài "Bài 7" của sách số hóa, qua target thì mất bài 7;
> (2) warm-up lấy theo bài chứ không gom toàn bộ kiến thức yếu rồi chia theo ngày;
> (3) ai cũng bị gen 20 bài N5 trong sách (không cá nhân hóa);
> (4) UI hiện canonical ID thô (`VOCABULARY:後ろ` → "Học liệu ôn tập đang được bổ sung") và `JLPT`/`TRAVEL` → "Chưa đủ học liệu ôn tập".

### 9.1 Root cause — một gốc, nhiều triệu chứng

**Gốc duy nhất: selection đang ở hạt nhân "bài học" (lesson), trong khi spec §2.2 yêu cầu hạt nhân "mục nội dung" (content item).**

| Triệu chứng | Nguyên nhân trực tiếp | File |
|---|---|---|
| Warm-up lấy nguyên "Bài 7", target mất bài 7 | `LearningResourceCandidate` chỉ có `lessonId/textbookId/unitId` — mỗi resource = 1 bài nguyên; selector khớp weak tag bằng knowledge-tag của **cả bài** | `LearningResourceCandidate.java`; `LearningResourceSelectionServiceImpl.java:60-66` |
| Không cá nhân hóa (20 bài N5 cho mọi người) | Goal = **toàn bộ** lesson của goal textbook trong dải [assessed..target], không lọc kiến thức đã vững, không sắp prerequisite; scope trả mọi textbook published trong dải level | `LearningResourceSelectionServiceImpl.java:76-82`; `JpaLearningScopeSource` (file protected — không sửa) |
| Warm-up không gom-chia-ngày | Không phải lỗi scheduler — `LearningScheduleServiceImpl` đã pack theo `minutesPerDay`; vấn đề là "1 item" hiện đang = "1 bài nguyên" nên chia ngày trên cục bài chứ không trên kiến thức yếu | `LearningScheduleServiceImpl.java:39-43` |
| UI hiện `VOCABULARY:後ろ` + copy sai | Kanji/Vocab/Reading/Listening weak evidence có `canonicalId` nhưng `tagCode == null` → selector trả gap `CONTENT_MAPPING_PENDING`; UI không có nhãn đọc được cho canonical ID, và `INSUFFICIENT_ASSESSMENT`/`NO_ASSESSMENT_EVIDENCE` dùng chung copy "Chưa đủ học liệu ôn tập" | `LearningResourceSelectionServiceImpl.java:53-57`; mapper/UI |

### 9.2 Plan sửa (Lát 2.5)

- **2.5a — Mở rộng resource model sang content-item (foundation).** Thêm loại candidate/resource mới trỏ tới `content_item` (kèm `content_type`) và identity `grammar_point`/kanji/vocab; giữ `LearningResourceCandidate` (lesson) làm fallback. `SelectedLearningResource` thêm resource-kind + canonical label.
- **2.5b — Content-item catalog cho GRAMMAR / KANJI / VOCAB.** Method mới trong catalog source: từ weak identity + skill trả đúng mục nội dung (không phải cả bài):
  - GRAMMAR `GP:<id>` → `content_item` có `grammar_point_id = :id` (ví dụ khác, ưu tiên theo `order_index`).
  - KANJI `<char>` / VOCAB `<lexeme>` → `content_item` nối qua `content_vocabulary` → `vocabulary_entry` khớp kanji/vocab.
  - READING/LISTENING → tra `resource_mapping` (chờ 2.5e — data).
- **2.5c — Cá nhân hóa selector theo §3.4/§3.5.** Thay khớp tagCode→cả bài bằng thứ tự warm-up spec: (1) kanji/vocab đúng item, (2) grammar point ví dụ khác, (3) reading/listening passage khác cùng outcome/topic, (4) prerequisite. Goal: curriculum sắp theo prerequisite từ level đã xác nhận → target, **bỏ** lesson người dùng đã vững — không còn "toàn bộ 20 bài".
- **2.5d — UI gap đọc được.** Map canonical ID → nhãn người (`KANJI:<c>`→"Chữ Hán <c>", `VOCAB:<c>`→"Từ vựng <c>", `GP:<id>`→"Ngữ pháp <id>", `READ`/`LISTEN`→"Đọc/Hiểu theo chủ đề"). Copy đúng theo `reasonCode` (INSUFFICIENT_ASSESSMENT / CONTENT_MAPPING_PENDING / MISSING_WARM_UP_RESOURCE / NO_ASSESSMENT_EVIDENCE).
- **2.5e — (DATA, đầu việc riêng — tiền đề Reading/Listening) content-side mapping.** Nạp `content_item.topic_tag_id` + dòng `resource_mapping` (skill, situation, outcome, level, topic → content_item, reason_code). Đã ghi chú sẵn ở migration 006. Không nằm trong code Lát 2.5; làm riêng.
- **2.5f — Test + E2E.** Cập nhật `LearningResourceSelectionServiceTest` / `LearningCatalogSourceTest` + chạy web verify 4 lỗi hết.

### 9.3 Công việc tiếp theo

Bắt đầu bằng **Lát 2.5a + 2.5b + 2.5c cho riêng GRAMMAR** — slice nhỏ nhất thấy được ngay trên web: weak grammar point trả về "ví dụ ngữ pháp trọng tâm" thay vì "cả Bài 7", và goal không còn đổ 20 bài. KANJI/VOCAB làm tiếp trong 2.5b; Reading/Listening chờ 2.5e (data).

### 9.4 (25/09) Đã hoàn thành — Lát 2.5a + 2.5b + 2.5c cho riêng GRAMMAR

> Slice GRAMMAR đầu tiên của Lát 2.5. Weak grammar point giờ resolve về đúng mẫu ngữ pháp đã sai (không còn cả bài).

**Đã đổi:**
- Migration `sql/migrations/009_grammar_point_warm_up.sql` — thêm cột `user_learning_path_item.grammar_point_id` (nullable, FK → `grammar_point`).
- `LearningGrammarPointRepository` (learning package) — `findWithLesson(id)` join lesson/unit/textbook/knowledge-tag; không đụng `dictionary.GrammarPointRepository`.
- `LearningResourceCandidate` + trường `grammarPointId` (thứ 10); `LearningCatalogSource.findGrammarPoint(id)` default `Optional.empty()` (giữ nguyên mọi catalog-less test cũ).
- `JpaLearningCatalogSource.findGrammarPoint` — resolve grammar point published → candidate (title = pattern, 10 phút).
- `WeaknessEvidence.grammarPointId()` — parse `GP:<id>`.
- `LearningResourceSelectionServiceImpl` — nhánh GRAMMAR warm-up theo point (fallback về tagCode lesson khi không có trong catalog).
- `UserLearningPathItem` + `grammarPoint` @ManyToOne; repository `left join fetch`; `LearningPathItemDto` +3 field (`grammarPointId/Pattern/Meaning`); `LearningMapper.toItem` (title = pattern khi có point); `LearningServiceImpl.create` persist reference qua `em.getReference`.
- `learning-path.js` — nhãn "Ngữ pháp" + nghĩa khi `item.grammarPointId` có.

**Test:** `LearningResourceSelectionServiceTest` (34) + `LearningCatalogSourceTest` (7) + toàn bộ package `com.jlas.learning` = 72 test pass. Thêm 3 test mới: grammar warm-up resolve đúng point, findGrammarPoint trả pattern + bỏ unpublished.

**Còn lại (2.5b/c/d/e/f):** KANJI/VOCAB content-item; cá nhân hóa goal (bỏ lesson đã vững + prerequisite); UI gap canonical label; Reading/Listening (chờ data 2.5e); E2E verify.

## 10. (25/09) Thiết kế lại warm-up + target theo phản hồi web test — Lát A/B/C/D

> Phản hồi web test lần 2 (nhánh `feat/JLAS-W0108-jlpt-path-full-coverage`), đã chốt với mentor. Ba vấn đề:
> (1) Warm-up "Bắt đầu" dẫn về trang giáo trình, không hiện đúng từ/kanji/ngữ pháp đã sai;
> (2) Giai đoạn target vẫn lấy title "Bài 1, 2, 3…" (chưa phối tài nguyên);
> (3) "Kiểm tra" có code nhưng không khớp ý (gate quiz chỉ hiện sau khi warm-up 100%, quiz lấy theo knowledge tag chứ không theo đúng item sai).

### 10.1 Quyết định đã chốt (nguồn sự thật duy nhất)

| # | Quyết định | Giá trị đã chốt |
|---|---|---|
| 1 | KANJI + VOCAB sai | Tự sinh **2 bộ flashcard** (1 kanji, 1 vocab) cho ôn lại trong warm-up, rồi quiz để pass |
| 2 | GRAMMAR + READING + LISTENING sai | Luyện **5 bài đọc + 5 bài nghe** cùng cấp sai, rồi quiz chứa cả nghe+đọc để qua target |
| 3 | Fail quiz | Cho **quiz lại** HOẶC quay về warm-up flashcard luyện rồi quiz lại |
| 4 | Giới hạn quiz | **5 lượt/ngày**, áp cho **cả gate quiz lẫn quiz từng bài target** |
| 5 | Tái dùng flashcard | Gọi `FlashcardService.create(SetRequest, userId)` — **không đụng code module flashcard của bạn khác** |
| 6 | Nguồn reading/listening | **Cả hai, ưu tiên `content_item`** (fallback về question bank) |
| 7 | Target "Bài 1,2,3" | Chưa tới việc (deferred) — chính là Lát D (goal personalization) |

### 10.2 Bảng gộp plan cũ ↔ lát mới

| Plan cũ (Lát 2.5) | Trạng thái | Lát mới | Ghi chú |
|---|---|---|---|
| 2.5a resource model content-item | ✅ xong | — | nền tảng giữ nguyên |
| 2.5b GRAMMAR → content_item | ✅ xong | (Lát B) | giữ, thêm màn xem item |
| 2.5b KANJI/VOCAB → content_item | ✅ xong phần resolve (`vocabularyEntryId`) | **Lát A** | **đổi hướng**: không dẫn content_item, sinh flashcard |
| 2.5c cá nhân hóa goal | ❌ dang dở | **Lát D** | giữ nguyên ý |
| 2.5d UI gap canonical label | ⚠️ một phần | (gộp A/B) | nhãn theo cách mới |
| 2.5e Reading/Listening data | ❌ dang dở | **Lát B** | nguồn "5 đọc/5 nghe" |
| 2.5f Test + E2E | ❌ | (theo từng lát) | giữ |

Luồng gộp tự nhiên: việc đã làm session trước (resolve KANJI/VOCAB → `vocabularyEntryId`, migration 010) **chính là input cho Lát A** — để sinh flashcard cần biết đúng từ nào sai, đã có qua `vocabularyEntryId`.

### 10.3 Bốn lát (thứ tự A → B → C → D)

- **Lát A — Warm-up KANJI/VOCAB bằng flashcard.** Gom `vocabularyEntryId` sai (KANJI+VOCAB) → `FlashcardService.create` sinh 2 set (kanji / vocab). Warm-up item mở đúng set flashcard (không còn redirect `/learning/lesson/{id}` → giáo trình). `learning-path.js` (`actionForLesson`/`renderLesson`) nhận diện `vocabularyEntryId` → nút mở flashcard.
- **Lát B — Warm-up GRAMMAR/READING/LISTENING qua content_item.** GRAMMAR đã resolve đúng point (session trước) → thêm màn xem item. READING/LISTENING → resolve `content_item` (ưu tiên content_item, fallback question bank) ra 5 đọc + 5 nghe cùng cấp sai. Phụ thuộc 2.5e (data mapping) cho nhánh content_item; fallback question bank chạy ngay.
- **Lát C — Gate quiz nghe+đọc + giới hạn 5 lượt/ngày.** Gate quiz thêm câu listening+reading (hiện chỉ lấy theo warm-up knowledge tag). Migration + bảng counter mới đếm lượt quiz/ngày (dùng chung cho gate quiz + quiz từng bài target), chặn ở lượt thứ 6. Luồng fail: quiz lại hoặc về flashcard.
- **Lát D — Goal phase personalization (tài nguyên phối vào).** Bỏ title "Bài 1,2,3"; phối tài nguyên theo §3.4/§3.5 (sắp prerequisite, bỏ kiến thức đã vững, trộn loại resource).

**Phụ thuộc:** A và B độc lập (A phụ thuộc resolve vocab đã xong; B phụ thuộc 2.5e data cho nhánh content_item nhưng có fallback). C độc lập về counter nhưng gắn quiz "để pass" của A/B. D độc lập, làm sau vì là vấn đề (2) riêng.

### 10.4 Ghi chú (để lần sau không lặp)

- Lỗi (3) "Kiểm tra chưa có" **không phải chưa làm** — W0113 đã cài đủ backend + frontend (`lesson-quiz.js`, `LearningQuizPageController`, `get/submitGateQuiz`). Gate quiz chỉ hiện sau `warmUpCompleted === true` (`learning-path.js:127`), và quiz lấy câu theo knowledge tag chứ không theo đúng item sai → "có nhưng không khớp ý". Lát C sửa đúng chỗ này.
