# Personalized Learning Path Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax. Execute sequentially; do not start child tasks or delegate automatically merely because this document names them.

**Goal:** Hoàn thiện module tạo và xem lộ trình gồm ôn kiến thức hổng rồi học kiến thức theo nhu cầu/target, kiểm thử được trước khi Smart Test và số hóa sách bàn giao.

**Architecture:** Profile port và catalog port cung cấp dữ liệu nội bộ; selector tạo hai giai đoạn; service xếp lịch/lưu version; UI đọc snapshot. Tái sử dụng persistence/API từ bundle có chọn lọc, thay selector theo nghiệp vụ mới.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring MVC/Thymeleaf, JPA, MySQL, H2, JUnit/MockMvc, vanilla JS và Node test runner.

**Spec:** `docs/learning/personalized-learning-path-design.md`. Đọc cùng plan; mục “Chính sách đề xuất cần chốt” chưa phải yêu cầu đã được phê duyệt.

## Global Constraints

- Mô tả mới của người dùng có ưu tiên cao hơn nghiệp vụ trong tài liệu cũ.
- Giữ nhánh hiện tại của user; kiểm tra HEAD/working tree trước khi thực thi. Không tự push, merge PR hoặc cập nhật Jira.
- Code/comment/commit bằng tiếng Anh; commit có một Jira ID, controller mỏng, interface + impl, ApiResponse/ApiException/CurrentUser.
- Không đưa DTO HTTP thành bằng chứng assessment hoặc nhận userId từ body.
- Không phát triển survey, Smart Test, OCR, policy du lịch hoặc lesson progress trong bốn task này.
- Không dùng BA/WORK để khẳng định đã hỗ trợ tiếng Nhật IT.
- Không nạp seed trên DB hiện tại của user. Demo dùng DB riêng, migration phải chỉ rõ đích.
- Không tạo path trong GET, refresh hoặc template rendering.
- Mỗi task: test failing -> implementation tối thiểu -> test passing -> review -> commit theo task khi thực thi được cho phép.

## Baseline đã kiểm tra ngày 2026-09-15

HEAD nhánh hiện tại: 25b37f3. Bundle: c70c276, base 62fce56, 10 commit. Tích hợp thử trong thư mục tạm không conflict; 39 Java test và 11 JS test pass. Đây là baseline nghiệp vụ cũ, chưa chứng minh thiết kế hai giai đoạn.

Chỉ 3 file của nhánh hiện tại thay đổi sau bundle base: CSS shell, CSS profile, template profile. Giữ chúng. Không cherry-pick cả bundle rồi coi bốn task đã xong. Lấy phần selector/API/tests/UI cần thiết theo từng task và điều chỉnh. Migration SQL cũ nằm ngoài bundle; phải đưa vào repo cùng migration mới.

## Thứ tự và đầu ra

| Thứ tự | Jira | Đầu ra review độc lập |
|---|---|---|
| 1 | JLAS2-123 | Contract và selector hai giai đoạn trên fixture |
| 2 | JLAS2-124 | API lưu/đọc lịch hai giai đoạn, migration, demo adapter |
| 3 | JLAS2-127 | Ma trận QA backend và kết quả MySQL migration |
| 4 | JLAS2-129 | Trang hai giai đoạn, shared nav, JS/MVC/browser test |

QA bắt đầu từ task 123; task 127 tổng hợp và kiểm chứng backend. Task 129 bổ sung browser QA sau khi có UI.

## Task 1 — JLAS2-123: contract và chọn tài nguyên

### Files

- Create `src/main/java/com/jlas/learning/dto/LearningProfileSnapshot.java`.
- Create `src/main/java/com/jlas/learning/dto/TagAssessmentResult.java`.
- Create `src/main/java/com/jlas/learning/dto/LearningResourceCandidate.java`.
- Create `src/main/java/com/jlas/learning/dto/SelectedLearningResource.java`.
- Create `src/main/java/com/jlas/learning/dto/LearningSelectionResult.java`.
- Create `src/main/java/com/jlas/learning/dto/CoverageGap.java`.
- Create `src/main/java/com/jlas/learning/enums/LearningPhase.java`.
- Create `src/main/java/com/jlas/learning/service/LearningProfileSource.java`.
- Create `src/main/java/com/jlas/learning/service/LearningCatalogSource.java`.
- Create `src/main/java/com/jlas/learning/service/LearningResourceSelectionService.java`.
- Create `src/main/java/com/jlas/learning/service/impl/LearningResourceSelectionServiceImpl.java`.
- Create `src/main/java/com/jlas/learning/service/impl/JpaLearningCatalogSource.java`.
- Modify `src/main/java/com/jlas/dictionary/repository/LessonRepository.java`.
- Create `src/test/java/com/jlas/learning/LearningResourceSelectionServiceTest.java`.
- Create `src/test/java/com/jlas/learning/LearningCatalogSourceTest.java`.
- Create `src/test/resources/application-test.yaml`; correct ignore rules so shared test configuration can be tracked.
- Create `docs/learning/integration-contract.md` with snapshot fields from the spec, validation and two input/output examples.

### Interfaces đề xuất

```java
LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId);
List<LearningResourceCandidate> findWarmUpCandidates(Set<String> tagCodes, String assessedLevel);
List<LearningResourceCandidate> findGoalCandidates(String goalCode, String assessedLevel, String targetLevel);
LearningSelectionResult select(Long userId, LearningProfileSnapshot profile);
```

Catalog source trả DTO, không để entity JPA đi qua API hoặc selector contract. Goal query JLPT cần một chương trình/catalog scope được xác định; không lấy tất cả sách cùng level làm lộ trình. Việc chọn scope tự động hay người dùng chọn sách phải chốt trong integration contract trước khi implement query này.

### Steps

- [ ] Chốt trong spec: ngưỡng tag yếu, scope chương trình JLPT, mục tiêu hỗ trợ đợt đầu; ghi quyết định, không tự coi mặc định là đã thống nhất.
- [ ] Tạo test H2 với URL in-memory và profile test riêng, vô hiệu việc include local trong test. Đọc lại cấu hình hiệu lực để bảo đảm không kết nối MySQL.
- [ ] Viết test snapshot ownership, survey chưa xong, kết quả chưa final, count âm/wrongCount > assessedCount, tag trùng và thiếu revision.
- [ ] Viết fixture có T1 sai 4/5, T2 sai 2/5, T3 sai 1/1; bài nền T1 thấp hơn assessed level và bài mục tiêu khác. Theo policy đề xuất, T1 tạo WARM_UP; T3 là thiếu bằng chứng; bài mục tiêu là GOAL_LEARNING.
- [ ] Viết test không có tag yếu, hoàn toàn thiếu tag results, thiếu ứng viên một tag, không có chương trình mục tiêu, bài trùng giữa hai phase, lesson DRAFT, textbook DRAFT, duration <= 0, tag không phải KNOWLEDGE, goal chưa hỗ trợ.
- [ ] Chạy `mvn test "-Dtest=LearningResourceSelectionServiceTest,LearningCatalogSourceTest"`; xác nhận assertion nghiệp vụ fail sau khi contract compile, không dùng lỗi môi trường làm bằng chứng red.
- [ ] Implement validation và selection theo policy đã chốt; reasonCode phân biệt WEAK_TAG và GOAL_TARGET; ghi tag bằng chứng và coverage gap.
- [ ] Thêm test thứ tự ổn định khi đầu vào repository thay đổi thứ tự, một bài đáp ứng nhiều tag và bài ôn thấp hơn assessed level.
- [ ] Chạy lại hai test class; kiểm tra output có phase, reason, coverage đúng và không ghi path vào DB.
- [ ] Review contract với dữ liệu mà Smart Test/catalog có thể cung cấp; lưu hai JSON ví dụ bằng dữ liệu tổng hợp, không giả là kết quả thật.
- [ ] Commit đề xuất `feat(JLAS2-123): select warm-up and goal learning resources`.

### Điều kiện hoàn thành

Selector tạo đúng danh sách hai giai đoạn từ fixture; dữ liệu không hợp lệ bị từ chối; thiếu coverage được thể hiện; không phụ thuộc module upstream đang chưa có. Adapter production vẫn được ghi là dependency ngoài task.

## Task 2 — JLAS2-124: tạo, xếp lịch, lưu và đọc

### Files

- Modify `src/main/java/com/jlas/learning/controller/LearningController.java`.
- Modify `src/main/java/com/jlas/learning/service/LearningService.java`.
- Modify `src/main/java/com/jlas/learning/service/impl/LearningServiceImpl.java`.
- Create `src/main/java/com/jlas/learning/service/LearningScheduleService.java`.
- Create `src/main/java/com/jlas/learning/service/impl/LearningScheduleServiceImpl.java`.
- Create `src/main/java/com/jlas/learning/dto/ScheduledLearningResource.java`.
- Create `src/main/java/com/jlas/learning/dto/CreateLearningPathRequest.java`.
- Modify `src/main/java/com/jlas/learning/dto/LearningPathResponse.java`.
- Modify `src/main/java/com/jlas/learning/entity/UserLearningPath.java` and `UserLearningPathItem.java`.
- Modify `src/main/java/com/jlas/learning/repository/UserLearningPathRepository.java`.
- Create `src/main/java/com/jlas/learning/repository/UserLearningPathItemRepository.java`.
- Modify `src/main/java/com/jlas/learning/repository/LessonProgressRepository.java` only for read queries needed by response.
- Create `src/main/java/com/jlas/learning/service/impl/LocalLearningProfileSource.java` guarded by local-seed.
- Modify `jlas_schema.sql`; create `db/migrations/JLAS2-124-learning-path-schedule.sql` and `db/migrations/JLAS2-124-learning-path-phases.sql`.
- Create `src/test/java/com/jlas/learning/LearningScheduleServiceTest.java`, `LearningPathApiTest.java`, `LearningSourceAvailabilityTest.java`.
- Create `docs/learning/JLAS2-124-api.md` and `docs/learning/local-demo.md`.

### Interfaces

```java
List<ScheduledLearningResource> schedule(List<SelectedLearningResource> resources, int minutesPerDay);
LearningPathResponse create(Long userId, Long assessmentAttemptId);
LearningPathResponse current(Long userId);
```

Input selection is ordered WARM_UP then GOAL_LEARNING. Schedule output adds dayNumber/orderIndex/estimatedMinutes. API request references the attempt only. Fresh creation returns 201; proposed duplicate request returns existing path with 200, requiring a service result that distinguishes created versus reused, to be finalized with the trigger decision.

### Steps

- [ ] Chốt trigger tự động hay xác nhận và idempotency trước khi viết create endpoint. Đề xuất kết quả final -> create, retry không sinh version mới. Ghi khác biệt so với bundle cũ vào API docs.
- [ ] Viết scheduler tests: budget 30, warm-up [10,20,45], goal [15,15] -> days [1,1,2,3,3]; goal bắt đầu ngày mới; lesson dài nguyên vẹn; không có warm-up bắt đầu day 1.
- [ ] Chạy scheduler tests red, implement schedule, chạy green.
- [ ] Viết API tests với adapter test: user khác, thiếu profile source, chưa final, tạo/reload, duplicate same input, attempt mới supersede path cũ, item failure rollback.
- [ ] Khóa row user trước khi cấp version/kiểm tra generation identity; kiểm tra unique key của generation identity và (user,version). Không dùng unique key chỉ trên nullable revision cho bản ghi mới.
- [ ] Tạo schema additive cho phase, references, policy version, reason/tag snapshot và coverage gaps. Bản ghi legacy giữ nullable; code mới phải ghi đủ. Xác định chính xác column type/index name trong migration và schema cùng commit.
- [ ] Tái sử dụng transaction/version/progress từ bundle; lưu cả hai phase và gaps trong một transaction. Fail không supersede path cũ hoặc reset progress.
- [ ] Implement GET từ snapshot, phase totals và overall totals phải khớp; legacy thiếu schedule/phase trả lỗi có mã rõ ràng.
- [ ] Chạy `mvn test "-Dtest=LearningScheduleServiceTest,LearningPathApiTest,LearningSourceAvailabilityTest"`.
- [ ] Thêm local-seed profile và dữ liệu catalog riêng cho hai phase; tạo user qua cơ chế auth hiện có. Không nhận profile giả từ browser trong production.
- [ ] Tài liệu hóa request/response 201/200, 400, 401, 403, 404, 409, 503; policy unsupported có error code cụ thể dùng convention hiện tại.
- [ ] Commit riêng: `feat(JLAS2-124): persist two-phase learning paths`, `chore(JLAS2-124): add learning path phase migration`.

### Điều kiện hoàn thành

API tạo/đọc được hai phase qua fixture, retry/concurrency theo contract, rollback an toàn, lịch và snapshot giữ nguyên khi reload. Không claim tạo được từ Smart Test thật.

## Task 3 — JLAS2-127: QA và migration

### Files

- Extend test classes từ 123/124.
- Modify `src/test/java/com/jlas/JlasApplicationTests.java` to use the isolated test profile.
- Create `docs/learning/JLAS2-127-test-matrix.md` and `docs/learning/mysql-verification.md`.
- Create `src/test/java/com/jlas/learning/LearningProfileSourceContractTest.java`: reusable contract assertions; production adapter chạy cùng assertions khi có.

### Steps

- [ ] Lập matrix requirement -> test -> expected -> actual; không chép mốc 39 tests cũ thành bằng chứng mới.
- [ ] Kiểm tra boundary ngưỡng yếu và minimum sample; 0/0; tag không kiểm tra; nhiều tag cùng lesson; deterministic order; coverage partial; unsupported goal.
- [ ] Sửa test ownership cũ để tạo path thật cho user A rồi GET bằng B phải 404. Test cũ chỉ kiểm tra B chưa có path là chưa đủ.
- [ ] Chạy hai create đồng thời cùng generation identity -> một path; khác attempt/revision -> version nối tiếp và chỉ một ACTIVE theo policy.
- [ ] Inject lỗi sau item flush -> không có path/item dở, ACTIVE cũ còn nguyên; update catalog duration -> lịch lưu không đổi; progress không bị reset.
- [ ] Verify plain `mvn test` dùng H2 ngay cả khi máy có local configuration. Ghi URL hiệu lực, không ghi credential.
- [ ] Chuẩn bị MySQL DB disposable với tên đã kiểm tra; dùng schema đã thay target CREATE DATABASE/USE một cách nhất quán. Không chạy schema gốc có USE jlas vào DB của user.
- [ ] Chạy migration trên schema cũ và chạy lại lần hai; kiểm tra schema mới, legacy null, duplicate version precheck và lưu/đọc path mới. Ghi SQL command và kết quả thực tế.
- [ ] Chạy `mvn test` và `git diff --check`; khắc phục lỗi whitespace và test có liên quan.
- [ ] Commit đề xuất `test(JLAS2-127): verify two-phase learning path behavior`.

### Điều kiện hoàn thành

Matrix backend không có case bắt buộc thiếu bằng chứng. MySQL chưa chạy phải ghi chưa kiểm chứng. Browser tests được bổ sung tại 129. Adapter contract test với fixture không thay thế contract run với adapter thật.

## Task 4 — JLAS2-129: hiển thị lộ trình và demo web

### Files

- Create `src/main/java/com/jlas/learning/controller/LearningWebController.java`.
- Modify `src/main/resources/templates/learning/index.html`.
- Create `src/main/resources/static/js/pages/learning-path.js`.
- Create `src/main/resources/static/css/pages/learning-path.css`.
- Modify `src/main/resources/static/js/jlab-base-shell.js` narrowly for learning navigation.
- Modify `src/main/resources/templates/fragments/nav.html` only if the live page actually consumes it.
- Create `src/test/java/com/jlas/learning/LearningWebControllerTest.java`.
- Create `src/test/js/learning-path.test.cjs`.
- Update `docs/learning/JLAS2-127-test-matrix.md` with browser evidence.

### UI contract

GET /learning serves learning/index. JS reads GET /api/learning/paths/current, displays WARM_UP and GOAL_LEARNING with days, durations, reasons and coverage. It never POSTs on mount. Button/trigger for generation follows 124's agreed contract. While upstream is absent, empty state explains that survey/Smart Test is unavailable; local demo creation is explicitly local-only.

### Steps

- [ ] Viết JS tests cho hai phase, phase rỗng có lý do, totals từ API, coverage warning, escape text, 404 empty, 409 legacy, 503 unavailable, retry và không POST khi mount.
- [ ] Run `node --test src/test/js/learning-path.test.cjs`, xác nhận fail theo behavior; dùng bundled Node nếu node chưa trong PATH.
- [ ] Implement UI dựa trên shared shell, giữ Onyomi và profile; không xây lại layout chung.
- [ ] Bổ sung MVC test render CSS/JS/API URL và anonymous redirect.
- [ ] Chỉ tạo link bài học nếu route/ID contract thực có. Khi chưa có lesson player, render title không có link thay vì dẫn tới 404 hoặc dùng path-item ID đoán là lessonId.
- [ ] Run JS/MVC tests green; không coi pure HTML string tests là browser proof.
- [ ] Chạy app local với DB demo và local-seed; đăng nhập bằng auth thật, tạo demo path, mở /learning, refresh, kiểm tra hai phase/reasons/days giữ nguyên.
- [ ] Browser test empty/error/retry, hết phiên đăng nhập, màn hình hẹp/rộng, menu/profile/Onyomi không hồi quy. Test hai user không thấy path của nhau.
- [ ] Ghi bằng chứng browser và giới hạn demo; chạy `mvn test`, JS tests, `git diff --check` sau thay đổi cuối.
- [ ] Commit đề xuất `feat(JLAS2-129): display warm-up and goal learning phases`; shared-nav change tách commit cùng Jira ID và yêu cầu team review theo rule.

### Điều kiện hoàn thành

Web hiển thị đúng dữ liệu đã lưu và không có CTA/link chết được thêm bởi task. Demo có bằng chứng browser, UI không tự tạo kết quả khảo sát hoặc đánh giá.

## Khi upstream bàn giao

1. Đối chiếu integration-contract với schema thực, viết production profile adapter và catalog mapping trong phạm vi tích hợp đã thống nhất.
2. Chạy contract tests trên dữ liệu thật: ownership, submitted/finalized, revision, tag aggregation, survey và goal.
3. Gắn trigger sau transaction lưu kết quả; test retry và không tạo path trùng.
4. Bật CTA thật, chạy survey -> Smart Test -> result -> path -> reload trên MySQL/browser.
5. Chỉ đánh giá parent hoàn thành sau nghiệm thu tích hợp và các bước PR/review/Jira của team.

## Quyết định cần chốt khi bắt đầu thực thi

Không chặn việc bàn giao plan. Trước phần code phụ thuộc, cần chốt: trigger tự động/xác nhận; ngưỡng tag yếu; scope chương trình JLPT; phạm vi IT đợt đầu; xử lý thiếu coverage; idempotency. Đề xuất cụ thể đã có trong spec, không dùng chúng như quyết định đã được người dùng duyệt.

## Quyết định đã chốt (2026-09-15, người dùng duyệt)

- Ngưỡng tag yếu: assessedCount >= 3 và wrongCount/assessedCount >= 0.5; cấu hình được qua `jlas.learning.selection.*`.
- Trigger: người dùng bấm "Tạo lộ trình" để xác nhận. POST `/api/learning/paths` do frontend gọi; backend không tự trigger, GET/refresh không tạo path.
- Idempotency: retry cùng (userId, attemptId, assessmentRevision, policyVersion) trả về lộ trình hiện có (200), không tạo version mới. Chỉ attempt/revision mới mới tạo version mới và SUPERSEDE path cũ.
- Scope chương trình JLPT: do server resolve qua `LearningScopeSource`; local-seed dùng scope cố định cấu hình, production impl là dependency ngoài task.
- Xử lý thiếu coverage: lưu và hiển thị coverageGaps; thiếu toàn bộ chương trình mục tiêu trả NOT_FOUND.
