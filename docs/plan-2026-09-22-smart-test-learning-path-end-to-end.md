# Kế hoạch công việc 2026-09-22 — Hoàn thiện luồng Smart Test → Lộ trình học → Sách số hóa

> **Đầu mối mới ngày 27/09/2026:** xem [Plan nghiệp vụ JLPT N5–N1](superpowers/plans/2026-09-27-jlpt-n5-n1-business-master-plan.md) để tiếp tục. Bản mới đối chiếu working tree, bổ sung hướng A, quiz từng hoạt động warm-up/bài target, chặng N5–N1, readiness dữ liệu và thứ tự P0–P8. Tài liệu này giữ lịch sử; trạng thái code/test cũ không phải nghiệm thu cho policy mới. Quy tắc đề xuất chưa chốt được ghi riêng trong bản mới.

> Nhánh làm việc: `feat/JLAS-W0090-smart-test-fix`. Người thực hiện: TNguyen47.
> Mục đích: chốt luồng end-to-end trước khi báo cáo mentor, đủ để review + PR + Jira theo quy trình team.

> **Cách đọc bản cập nhật:** §12 là quyết định nghiệp vụ mới nhất, được người dùng đồng ý ngày 26/09/2026; ưu tiên §12 khi khác với thiết kế cũ ở §0–11 hoặc tài liệu triển khai trước đó. Phạm vi sprint giữ theo §11.5. Các trạng thái hoàn thành trong phần lịch sử không thay thế việc nghiệm thu theo rule mới.

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

## 11. (26/09) Đối chiếu business spec "full learning journey" — gap chiến lược

> Nguồn: `docs/superpowers/plans/2026-09-24-full-learning-journey-business-spec.md` (spec do mình soạn, hiện ở nhánh chưa commit). Spec nêu 5 nhóm vấn đề ở tầm toàn learning journey: quiz tương tác, script readiness, foundation vs warm-up, khác biệt theo goal, quiz→roadmap feedback. Đối chiếu để plan không bỏ sót gap chiến lược — **chưa phải việc làm hôm nay**, chỉ ghi lại hướng cho các sprint sau.

### 11.1 Đối chiếu spec ↔ plan hiện tại

| Vấn đề trong spec | §spec | Plan hiện tại | Kết luận |
|---|---|---|---|
| Warm-up = kiến thức đã học nhưng yếu, ôn trước bài mới | §17 | Lát A/B (flashcard + content-item) | ✅ Đã phủ |
| Quiz result quay lại điều chỉnh lộ trình | §20, §22-R7 | W0122 replan (proposal + confirm) | ✅ Đã phủ ở mức lộ trình |
| Quiz không chỉ MCQ, mà theo skill | §3–9 | W0113 đang MCQ thuần | ❌ **Gap 1 — Interactive Quiz System** |
| Foundation ≠ Warm-up (prerequisite ≠ weakness) | §17 | Plan chỉ có Warm-up | ❌ **Gap 2 — Foundation stage** |
| Người mới chưa biết Kana → Script Readiness | §12 | Chưa có | ❌ **Gap 2 — Script Readiness** |
| Foundation khác theo goal (Travel/Daily vs JLPT/BRSE) | §13–16 | Lát D goal personalization (dang dở) | ❌ **Gap 3 — goal-based Foundation** |
| Roadmap có bước 0 (Readiness/Foundation) trước Warm-up | §18, §21 | Roadmap hiện: Warm-up → Main → gate | ⚠️ Thiếu bước 0 |

### 11.2 Ba gap chiến lược (chưa có story trên board — cần triage Jira)

**Gap 1 — Interactive Quiz System (quiz type theo skill).** Hiện `assessment_question` + W0113 chỉ là MCQ. Spec §3–9 đề xuất `Skill × Context × Interaction Type`: Vocabulary V1–V5, Kanji K1–K4, Grammar G1–G4, Reading (đọc đoạn/T-F/tìm ý), Listening L1–L5, Speaking S1–S2, Situation. Điều này cần mở rộng **model** quiz (thêm `interaction_type`, `context`, `media`) chứ không chỉ thêm câu hỏi. **MVP (spec §23):** MCQ, Matching, Sentence Ordering, Listening→Choose, Listen&Repeat, Situation→Response; Dictation/Image/Conversation/Adaptive để sau.

**Gap 2 — Script Readiness + Foundation (người mới hoàn toàn).** Spec §12: 3 mức NONE / BASIC_RECOGNITION / KANA_READY. §14–16: 3 con đường Foundation — Quick Start (Travel/Daily), Kana Lite (Hiragana+Katakana nhẹ), Full Foundation (JLPT/BRSE: Hiragana→Katakana→Dakuten/Handakuten→拗音→長音→促音→đọc). Cần: readiness check trước khi sinh roadmap, và Foundation stage chèn trước Warm-up khi prerequisite thiếu.

**Gap 3 — Goal-based Foundation depth.** Spec §13 + memory `learning-path-multi-goal-design` ("non-JLPT path = feature riêng"): Travel/Daily **không ép Full Kana** (Quick Start + Kana Lite), JLPT/BRSE **bắt buộc Full Foundation**. Đây là mở rộng của Lát D nhưng ở tầng Foundation, không chỉ tầng tài nguyên.

### 11.3 Điểm mấu chốt (để không lặp lỗi)

- Foundation = "chưa đủ điều kiện học" (prerequisite); Warm-up = "đủ điều kiện nhưng yếu" (weakness) — §17, đừng nhầm hai khái niệm.
- Không sinh roadmap chỉ từ Goal + Target Level (§22-R1): cần thêm Current Level, Script Readiness, Assessment Result, Learning Time, Context.
- Quiz type phụ thuộc loại kiến thức + mục tiêu (§22-R4); Travel/Daily ưu tiên situational practice (§22-R5); JLPT gần dạng đề thi (§22-R6).

### 11.4 Hướng triển khai (theo thứ tự, không phải hôm nay)

1. Hoàn tất Lát A/B/C/D + W0113/W0122/W0127/W0132 (plan §8.2/§10.3) — đây là nền để gap 1–3 đứng lên.
2. **Gap 1** — nâng cấp quiz model + sinh quiz theo skill, sau khi W0113 chạy ổn; chốt schema `interaction_type` với team.
3. **Gap 2 + 3** — mở rộng "non-JLPT path = feature riêng"; thêm readiness check + Foundation stage; chốt scope Foundation theo goal với mentor trước khi sinh story.

### 11.5 (26/09) Chốt scope — sprint này vs sprint sau

> Quyết định đã chốt với user: các gap chiến lược **và** phần state machine còn lại đều để sprint sau, không phải việc hoàn thiện luồng hiện tại. **Chi tiết rule từng việc + thứ tự nghiệm thu xem §12 (duyệt 26/09); §12.11 là thứ tự thực hiện chuẩn.**

**Sprint này — hoàn thiện luồng hiện tại (4 việc):**
1. **Lát D** — goal personalization: chọn tài nguyên ở mức **kiến thức/hoạt động học** (không lấy nguyên bài), trình bày thành **buổi học có tên + mục tiêu + thời lượng**, giữ kiến thức chưa đánh giá, chỉ rút gọn phần đã vững có bằng chứng (§12.1–12.2).
2. **Data content-side R/L** — nạp `content_item.topic_tag_id` + `resource_mapping`, kèm báo cáo khớp/chưa khớp/mơ hồ + người duyệt (§12.3/§12.6).
3. **W0127** — Daily Goal + Credit Points + Streak, đối chiếu rule module gamification chung trước khi nối sự kiện (§12.8).
4. **W0132** — Learning Path E2E (§8.2; bổ sung tình huống thiếu bằng chứng/thiếu học liệu/nộp lặp/hết lượt theo §12.11.4).

**Sprint sau (5 việc, chưa có story):**
5. **Gap 1** — Interactive Quiz System (§11.2, §12.9).
6. **Gap 2** — Script Readiness + Foundation stage (§11.2, §12.9).
7. **Gap 3** — goal-based Foundation depth (§11.2, §12.9).
8. **Lát 4** — Travel/Daily Life path (§12.9).
9. **State machine còn lại** — Progress Quiz mỗi 20% + mastery projection + Final Mock + Certificate (§12.9).

## 12. (26/09) Quyết định nghiệp vụ sau khi duyệt định hướng toàn hành trình

**Nguồn xác nhận:** người dùng đồng ý toàn bộ hướng đề xuất trong phiên trao đổi và yêu cầu cập nhật vào plan. Mục này ghi lại các hướng đã được duyệt; những câu hỏi chưa có giá trị cụ thể được liệt kê riêng ở §12.10, không tự coi là đã chốt con số hay thuật toán.

**Phạm vi:** cập nhật kế hoạch, chưa phải xác nhận code đã đáp ứng. Không mở rộng sprint hiện tại vượt bốn việc ở §11.5; các rule liên quan được dùng để nghiệm thu hoặc ghi nhận gap, không mặc nhiên kéo toàn bộ state machine/quiz mới vào sprint này.

### 12.1 Bằng chứng kiến thức: chưa đủ bằng chứng / cần ôn / đã vững

- Phân biệt tối thiểu ba trạng thái nghiệp vụ: **chưa đủ bằng chứng**, **cần ôn**, **đã vững**. Đây là trạng thái nghiệp vụ, chưa ấn định tên enum/schema.
- Không có câu sai không đồng nghĩa đã vững; không được bỏ kiến thức chỉ vì chưa từng được đánh giá.
- **MVP: giữ kiến thức chưa đủ bằng chứng trong lộ trình.** Kiểm tra nhanh để được bỏ qua là khả năng bổ sung sau, không thuộc MVP hiện tại.
- Chỉ loại/rút ngắn nội dung dựa trên bằng chứng đã vững. Rule xác định điểm yếu và rule xác nhận thành thạo được định nghĩa riêng.
- Giữ rule điểm yếu đã chốt: Kanji/từ vựng theo item; ngữ pháp theo mẫu; ngữ pháp và đọc/nghe dùng cửa sổ đánh giá hợp lệ theo spec (sai 4–5/5); bỏ trống tính sai, lỗi kỹ thuật loại khỏi mẫu, thiếu mẫu ghi PARTIAL/MISSING.
- Ví dụ: chưa kiểm tra mẫu A → vẫn giữ A; trả lời sai từ B → đưa B vào ôn; chỉ bỏ phần C khi có bằng chứng thành thạo theo policy đã xác nhận.
- Cần đặc tả tiếp ngưỡng thành thạo, lượng bằng chứng, cách kết hợp kết quả cũ/mới và thời hạn hiệu lực (§12.10).

### 12.2 Đơn vị chọn nội dung và cách trình bày lộ trình — đầu vào Lát D

- Chọn tài nguyên ở mức **mục kiến thức hoặc hoạt động học**; không mặc định lấy nguyên bài giáo trình cho mọi người.
- Trình bày thành **buổi học có tên, mục tiêu và thời lượng**. Giáo trình là nguồn nội dung và căn cứ thứ tự kiến thức tiên quyết.
- Một bài có phần đã vững và phần chưa vững: bỏ/rút ngắn phần có bằng chứng đã vững, giữ phần còn thiếu; không bỏ toàn bài chỉ vì một phần đã đạt.
- Phối hợp tài nguyên phù hợp với mục tiêu, sắp theo prerequisite, phân bổ theo thời gian học mỗi ngày.
- Ví dụ cách hiển thị: “Hỏi đường: ôn từ vựng, học mẫu chỉ vị trí, luyện nghe hội thoại”; số lượng tài nguyên phải theo dữ liệu và thời lượng thực tế, không cố định theo ví dụ.
- Nghiệm thu Lát D: hai hồ sơ có bằng chứng khác nhau có thể nhận nội dung khác nhau; kiến thức chưa đánh giá vẫn được giữ; tên mục thể hiện nội dung; không loại phần chưa vững khi rút gọn bài.

### 12.3 Warm-up phải xử lý đúng điểm yếu

- Kanji/từ vựng ôn đúng item sai bằng bộ flashcard tương ứng, tái sử dụng module flashcard hiện có.
- Ngữ pháp phải có hoạt động luyện trực tiếp mẫu yếu; chỉ cùng cấp độ là chưa đủ.
- Đọc/nghe khớp kỹ năng, cấp độ và mục tiêu luyện; khớp chủ đề khi mục tiêu học yêu cầu chủ đề.
- **5 bài đọc + 5 bài nghe áp dụng cho nhánh ôn có điểm yếu tương ứng**, không bắt mọi người làm cả hai kỹ năng khi không có nhu cầu tương ứng. Khi đủ học liệu, mỗi nhánh tương ứng có 5 bài; thiếu học liệu xử lý theo §12.6.
- Số bài đã luyện không tự chứng minh điểm yếu đã được khắc phục. Quiz sau ôn kiểm tra lại kiến thức cần cải thiện bằng câu khác câu đã dùng để đánh giá/luyện.
- Ví dụ: chỉ yếu nghe → không tự thêm 5 bài đọc chỉ để đủ bộ; yếu mẫu điều kiện → phải luyện mẫu đó, không thay bằng bài cùng cấp nhưng không kiểm tra mẫu đó.
- Mục này điều chỉnh cách hiểu quy tắc “5 đọc + 5 nghe” ở §10.1/§10.3.

### 12.4 Tách thực hiện hoạt động, đạt kiểm tra và đủ điều kiện đi tiếp

- **Đã thực hiện hoạt động:** hoàn tất yêu cầu học/xem/luyện của hoạt động.
- **Đạt kiểm tra:** đạt tiêu chí đánh giá kiến thức tương ứng.
- **Đủ điều kiện đi tiếp:** đáp ứng các yêu cầu bắt buộc của giai đoạn.
- Không tự chuyển “đã xem hết nội dung/lật hết thẻ” thành “đã vững”.
- Người không có điểm yếu **và có đủ bằng chứng đánh giá** được bỏ warm-up. Người thiếu bằng chứng không được tự động coi là đủ năng lực; giữ kiến thức chưa đánh giá theo §12.1.
- Nghiệm thu: việc mở/xem học liệu không tự tạo bằng chứng thành thạo; chuyển giai đoạn phải theo rule gate được xác định, không chỉ theo trạng thái giao diện.
- Điều kiện hoàn thành flashcard, yêu cầu quiz bắt buộc và ngưỡng gate theo tổng điểm/từng kỹ năng còn cần đặc tả (§12.10); không tự thay ngưỡng hiện có bằng một con số mới.

### 12.5 Vai trò quiz và hạn mức lượt

- Phân biệt **quiz luyện tập** với **đánh giá để chuyển giai đoạn/xác nhận năng lực**. Kết quả luyện có giá trị phản hồi nhưng không mặc định có cùng trọng lượng với đánh giá bằng câu chưa gặp.
- Không dùng điểm cao do làm lại cùng câu như bằng chứng duy nhất để bỏ kiến thức khỏi lộ trình.
- Giữ hạn mức đã chốt: **5 lượt/ngày dùng chung cho gate quiz và quiz bài target**. Việc tách vai trò quiz không tự thay đổi phạm vi hạn mức này.
- **Tính lượt khi bài nộp được xử lý hợp lệ**; retry cùng bài nộp không trừ thêm lượt. Lỗi kỹ thuật không được bị tính như một lượt nộp hợp lệ; cần cơ chế xử lý/khôi phục rõ.
- Hết lượt kiểm tra vẫn được tiếp tục ôn bằng học liệu/flashcard; lượt kiểm tra tiếp theo theo ngày áp dụng.
- Ví dụ: mất kết nối sau khi server đã ghi nhận bài → gửi lại trả kết quả đã có, không cộng thêm một lượt.
- Cần chốt múi giờ đổi ngày, cách đổi đề/độ khó, trọng số kết quả và quy trình lỗi âm thanh/mất mạng trước khi hoàn thiện policy.

### 12.6 Thiếu học liệu và chất lượng mapping — đầu việc dữ liệu đọc/nghe

Thứ tự tìm tài nguyên:

1. Khớp kiến thức/kỹ năng, cấp độ và mục tiêu luyện; chủ đề theo yêu cầu mục tiêu học.
2. Nội dung tương đương về mục tiêu luyện trong phạm vi thay thế đã được quy định.
3. Nguồn dự phòng từ ngân hàng câu hỏi theo quyết định §10, vẫn phải đáp ứng mục tiêu luyện và rule không lặp câu đánh giá.
4. Nếu vẫn thiếu: báo thiếu học liệu và chưa xác nhận hoàn thành phần đó.

- Không tự đổi cấp độ chỉ để đủ số bài. Khi chưa có rule cho phép nới điều kiện, không tự mở rộng điều kiện khớp.
- Mapping mơ hồ phải có trạng thái chờ duyệt; kết quả khớp tự động không mặc định là dữ liệu đã xác nhận.
- Đầu ra data task: mapping content-side, báo cáo khớp/chưa khớp/mơ hồ, nguồn dữ liệu và kết quả duyệt; xác định người phụ trách duyệt trước khi sử dụng mapping mơ hồ.
- Nghiệm thu: có trường hợp đủ nội dung, dùng nguồn dự phòng và thiếu nội dung; không lấy sai cấp để bù số lượng; không đánh dấu đạt vì thiếu tài nguyên.

### 12.7 Replan thích ứng nhưng bảo toàn bằng chứng

- Giữ luồng **đề xuất → xem trước lý do/thay đổi → người học xác nhận → áp dụng**; xem trước không thay đổi path.
- Giữ ngưỡng gợi ý hiện có: >90% đề xuất tăng tốc, <60% đề xuất ôn bổ sung. Đây là ngưỡng gợi ý, không tự chứng minh từng kiến thức đã vững.
- Giữ thời gian học mỗi ngày làm giới hạn mặc định. Khi thêm phần ôn, điều chỉnh thời gian dự kiến hoàn thành thay vì tự tăng khối lượng hằng ngày.
- Bỏ/rút ngắn nội dung chỉ dựa trên bằng chứng đã vững; tổng điểm một quiz không đủ để tùy ý bỏ các kiến thức chưa được đánh giá.
- Bảo toàn phần đã hoàn thành, lịch sử attempt, CP và streak; áp dụng lặp không tạo phiên bản hay thưởng trùng.
- Phân biệt thay đổi lịch học với làm lại đánh giá đầu vào. Rule khóa Survey/Smart Test khi path ACTIVE không được mặc định diễn giải thành khóa mọi điều chỉnh thời gian học; thay đổi lịch nếu được hỗ trợ phải đi qua luồng riêng có xác nhận.
- Cần đặc tả tiếp mức thay đổi tối đa từ một kết quả, cách đề xuất lại sau khi bị từ chối và thao tác đổi thời gian học.

### 12.8 Daily Goal, CP và streak — W0127

- Streak gắn với hoạt động học đủ điều kiện, không chỉ đăng nhập hoặc mở trang.
- CP áp dụng rule chung của module gamification hiện có; không xây một bộ mức thưởng mâu thuẫn trong Learning Path.
- Chống cộng lặp theo sự kiện và nội dung theo policy thưởng. Retry hoặc replan không tự cấp lại thưởng cho cùng hoạt động đã ghi nhận.
- Phân biệt tiến độ hoạt động với bằng chứng thành thạo: có hoạt động được ghi nhận không đồng nghĩa được bỏ nội dung trong roadmap.
- Trước khi nối W0127, đối chiếu với người phụ trách module: đơn vị Daily Goal, sự kiện đủ điều kiện, mức thưởng, thưởng khi luyện lại, điều kiện streak và múi giờ thống nhất.
- Nghiệm thu: hoạt động đủ điều kiện cập nhật đúng mục tiêu/CP/streak; sự kiện lặp không cộng lại; replan bảo toàn số liệu; kiểm tra ranh giới ngày theo múi giờ được chọn.

### 12.9 Sprint sau: thứ tự thiết kế và ranh giới

- **Script Readiness/Foundation:** chốt bộ đánh giá Kana, ngưỡng phân loại, điều kiện bỏ qua và hoàn thành. Giữ Foundation là bổ sung prerequisite, Warm-up là ôn kiến thức yếu.
- **Foundation theo goal:** giữ Quick Start/Kana Lite cho Travel/Daily; Full Foundation theo yêu cầu JLPT/BRSE, đồng thời cần xác định cách công nhận nền tảng đã có.
- **Travel/Daily Life:** chốt danh sách tình huống MVP và đầu ra thực hành; tự xác định mức/đánh giá tùy chọn, phản hồi tiến bộ, không cơ chế đỗ/trượt hay chứng nhận JLPT.
- **Quiz tương tác:** chốt dạng bài MVP, cách chấm mỗi dạng và hợp đồng tích hợp module của thành viên khác trước khi mở rộng schema; không mặc nhiên triển khai mọi dạng cùng lúc.
- **Checkpoint mỗi 20%:** phải chốt mẫu số tiến độ và trọng số hoạt động trước khi triển khai. Không mặc định một flashcard và một bài đọc dài có cùng trọng số; quy định cách giữ mốc khi replan.
- **Final Mock/Certificate:** cần chốt cấu trúc bài, ngưỡng đạt, thi lại và ý nghĩa chứng nhận của hệ thống trước khi viết acceptance criteria cuối cùng.

### 12.10 Chi tiết còn cần đặc tả — không tự suy diễn từ việc duyệt hướng

| Nhóm | Chi tiết chưa có giá trị chốt | Thời điểm cần làm rõ |
|---|---|---|
| Thành thạo | Ngưỡng, số bằng chứng, kết hợp cũ/mới, hết hạn; nguồn bằng chứng được công nhận | Trước khi dùng để loại nội dung trong Lát D; thiếu policy thì giữ nội dung |
| Hoàn thành/gate | Hoàn thành flashcard, quiz bắt buộc, tổng điểm hay từng kỹ năng | Trước thay đổi hành vi hoàn thành/gate |
| Quiz | Đổi đề, độ khó, chọn kết quả, múi giờ, quy trình phục hồi lỗi | Trước thay đổi policy quiz/counter |
| Mapping | Phạm vi tương đương được phép và người duyệt | Trước áp dụng mapping mơ hồ hoặc nới điều kiện |
| Replan | Mức thay đổi, đề xuất lại, UX đổi thời gian học | Trước mở rộng replan |
| Daily Goal/CP/Streak | Đơn vị đo, sự kiện, mức thưởng, luyện lại, điều kiện ngày hợp lệ | Trước tích hợp W0127; đối chiếu module chung |
| Sprint sau | Readiness, Foundation, quiz tương tác, trọng số checkpoint, Final Mock/Certificate | Khi thiết kế sprint sau |

Việc người dùng duyệt hướng ở §12 không biến các câu hỏi trong bảng này thành thông số đã được lựa chọn. Ưu tiên lấy rule đã xác nhận từ module/tài liệu hiện có; nếu không có thì chốt trước phần triển khai phụ thuộc.

### 12.11 Thứ tự thực hiện và truy vết nghiệm thu

1. **Lát D:** áp dụng §12.1–12.2; giữ kiến thức chưa đánh giá, chỉ rút gọn phần đã vững có bằng chứng.
2. **Data đọc/nghe:** áp dụng §12.3/§12.6; mapping đúng mục tiêu, có nguồn dự phòng và thông báo thiếu.
3. **W0127:** áp dụng §12.8; thống nhất rule với module chung rồi nối sự kiện.
4. **W0132:** kiểm chứng luồng Smart Test → điểm yếu → roadmap → học/quiz → CP/streak → replan; thêm tình huống thiếu bằng chứng, thiếu học liệu, nộp lặp, hết lượt và bảo toàn dữ liệu. Gap ngoài sprint được ghi lại, không tự kéo vào scope.
5. **Sprint sau:** đặc tả §12.9 theo dependency: trạng thái kiến thức/tiến độ → điều kiện/gate/quiz → Foundation/đa mục tiêu → checkpoint/Final Mock/Certificate.

Mỗi policy cụ thể cần ghi rõ: **rule, ví dụ bình thường, trường hợp ngoại lệ, acceptance criteria và nguồn quyết định**. Không gắn trạng thái “đã triển khai” chỉ vì đã cập nhật plan.

## 13. (27/09) Sửa warm-up flashcard theo phản hồi thực tế

Quyết định mới thay phần “hai bộ Kanji/từ vựng” ở §10: **một bộ flashcard gộp Kanji + từ vựng cho warm-up của lộ trình**, hiển thị một hoạt động duy nhất thay vì nhiều mục từ đều mở cùng bộ.

- Lật thẻ rồi chọn “Đã nhớ”/“Chưa thuộc” phải lưu tiến độ từng thẻ; tải lại tiếp tục từ thẻ chưa nhớ.
- Chỉ tự hoàn thành **hoạt động ôn flashcard** khi tất cả thẻ đã được đánh dấu nhớ và lưu thành công. Thẻ chưa thuộc tiếp tục vòng ôn; lỗi lưu giữ nguyên thẻ và cho thử lại.
- Hoàn thành flashcard không hoàn thành bài giáo trình nguồn, không xác nhận mastery của Learning Path và không tự vượt gate quiz.
- Nút “Tiếp tục học” và mục trong roadmap phải mở cùng đúng tài nguyên.
- Tái sử dụng progress API/service của module flashcard; không tạo schema tiến độ song song. Get-or-create khóa path để tránh sinh bộ trùng khi mở đồng thời; bộ mới khử trùng theo expression + reading.
- Lộ trình cũ được gộp ở API read model, giữ nguyên item nguồn và lịch sử dữ liệu; không yêu cầu tạo lại path. Bộ đã tồn tại được giữ nguyên để bảo toàn tiến độ.
- Kiểm thử: grouping/tổng số hoạt động, tách completion khỏi bài nguồn và gate, lưu trạng thái đúng user/set/card, chống tạo bộ lặp, resume và lỗi mạng; xác nhận giao diện trên bản ứng dụng chạy lại sau build vẫn là bước kiểm chứng thực tế.

## 14. (27/09) Lượng ôn đọc/nghe theo điểm đầu vào — quyết định mới nhất

Theo trao đổi mới: chỉ tách ngữ pháp theo mẫu; Kanji/từ vựng gom các mục sai thành một bộ flashcard. Đọc/nghe chọn các passage/audio nguyên vẹn từ ngân hàng, đúng cấp và năng lực cần luyện; bài luyện khác bài kiểm tra.

Người dùng chốt >50–70% đúng ôn 4 bài, >70% đúng bỏ ôn; cho phép giảm tải nhóm thấp. Chọn phân bố hài hòa như sau, tính riêng từng kỹ năng:

| Tỷ lệ đúng | Bài ôn | Số ngày tối thiểu nếu chỉ ôn một kỹ năng |
|---|---:|---:|
| >70% | 0 | 0 |
| >50% đến 70% | 4 | 2 |
| >40% đến 50% | 6 | 3 |
| 0–40% | 8 | 4 |

- Tối đa 2 bài đọc/nghe **tổng cộng** mỗi ngày, giảm thêm nếu vượt ngân sách phút; không dồn 4 bài/ngày khi yếu cả hai kỹ năng.
- Điểm đúng 40/50/70% lần lượt nhận 8/6/4 bài. Thiếu bằng chứng không quy thành 0%.
- Không tự giao học lại toàn giáo trình chỉ vì điểm đọc hoặc nghe thấp; phần học lại diện rộng cần policy riêng đủ bằng chứng.
- Bảng này thay số lượng cố định 5 đọc + 5 nghe ở §10/§12.3; các rule khác về nguồn phù hợp, thiếu học liệu và quyền truy cập giữ nguyên.
- Ngưỡng giao bài đầu vào khác ngưỡng đạt đầu ra. Không áp bảng mastery ≥85% để bắt người >70% quay lại ôn đọc/nghe.
- Chi tiết, ví dụ phối lịch và test biên: [hướng dẫn triển khai §8.1](superpowers/plans/2026-09-27-knowledge-based-learning-path-implementation.md#81-giao-bài-ôn-đọcnghe-từ-điểm-đầu-vào--cập-nhật-sau-cùng). Đây là cập nhật nghiệp vụ/tài liệu, chưa triển khai selector/scheduler theo ngưỡng mới.
