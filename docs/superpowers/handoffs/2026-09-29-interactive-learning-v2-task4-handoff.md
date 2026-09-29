# Prompt bàn giao — Interactive Learning Path V2 Task 4

Tiếp nhận và hoàn thành **Task 4 — Vocabulary study và micro-check** của J-LAS Interactive Learning Path V2 trên
branch `codex/interactive-learning-v2`, worktree:

`C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`

Phạm vi của bạn dừng ở Task 4/mốc 40%. Task 5–10 do AI khác tiếp nhận. Bạn phải để lại contract, test và handoff đủ
rõ để AI Task 5 dùng lại engine cho Kanji mà không phải suy đoán hoặc tạo state machine thứ hai.

Không thao tác checkout chính `D:\FALL26_FPT\SEP490` vì có thể chứa code chưa commit của người khác. Không reset,
clean, stash, rebase, merge, push hoặc force-push khi chưa được người dùng yêu cầu.

## Đọc trước khi sửa code

1. `AGENTS.md` và mọi `AGENTS.md` áp dụng cho file sẽ sửa.
2. `docs/rules-and-business-logic.md`.
3. `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`.
4. `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`.
5. `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-30-percent-handoff.md` để hiểu baseline Task 1–3.
6. `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task5-to-task10-handoff.md`, riêng phần “Điều kiện
   bắt đầu”, để biết contract bắt buộc phải bàn giao cho Task 5.
7. Visual contract trong `docs/superpowers/assets/interactive-learning-v2/`, đặc biệt roadmap và micro-check.
8. Code/test trong các commit `3b6bb77`, `09e0312`, `14f827d`; kiểm tra `git log` để lấy các commit tài liệu mới hơn.

Trước khi triển khai, kiểm tra `git status` và đọc code hiện tại. Bảo toàn toàn bộ Task 1–3, legacy behavior và thay đổi
hợp lệ của người khác. Task 3 đã cung cấp `LearningActivityMember`, membership type `VOCABULARY_ENTRY`, canonical ID,
ordinal và source revision. Task 4 phải sử dụng dữ liệu đó; client không được truyền danh sách target để quyết định scope.

## Mục tiêu Task 4

Tạo một vertical slice hoàn chỉnh cho Vocabulary activity:

`owned activity → resumable flashcard study → remembered-all eligibility → immutable micro-check → score/attempt/review/cooldown → activity completion`

Không triển khai Kanji, Grammar, Dialogue, package-completion popup, Warm-up B hoặc summary/certificate trong task này.
Thiết kế phần engine dùng chung ở mức tối thiểu để Task 5 có thể tái sử dụng state/evidence/scoring/cooldown cho Kanji;
Vocabulary renderer và question strategy vẫn thuộc Task 4.

## Contract nghiệp vụ bắt buộc

### 1. Ownership và nguồn dữ liệu

- JWT principal là nguồn user ID duy nhất.
- Mọi lookup đi qua principal → V2 path → package → Vocabulary activity → membership/session.
- ID không thuộc owner, sai package hoặc sai activity trả not-found an toàn, không tiết lộ resource tồn tại.
- Target chỉ lấy từ membership `VOCABULARY_ENTRY` của activity hiện tại, theo ordinal và `source_revision` đã snapshot.
- Không nhận target ID, score, attempt count, activity state, cooldown hoặc completion flag từ client làm sự thật.
- Không dùng seed/demo/fake fallback và không gọi AI để sinh/chấm câu hỏi runtime.

### 2. Study session và flashcard evidence

- Start/resume là idempotent: reload hoặc gọi start lại trả đúng active session của revision hiện tại.
- Session gắn với activity, owner và membership/source revision. Evidence của revision khác không được trộn vào session mới.
- Mỗi target lưu evidence đủ để biết đã hiển thị, đã lật, lựa chọn gần nhất và đã từng được xác nhận `Đã nhớ`.
- Người học phải lật thẻ trước khi chọn `Đã nhớ` hoặc `Chưa nhớ`; server kiểm tra thứ tự này.
- `Chưa nhớ` đưa thẻ trở lại hàng ôn trong cùng session. Không nhân bản target thành nhiều flashcard record độc lập.
- Chỉ mở micro-check khi mọi membership của revision hiện tại đã từng được server lưu `Đã nhớ`.
- Progress/review queue/resume position phải do server suy ra từ evidence, không dựa vào bộ đếm của browser.

### 3. Vocabulary micro-check

- Micro-check phủ mọi target membership. Nếu nhiều hơn 10 target, chia batch tối đa 10 nhưng một cycle phải theo dõi
  coverage toàn bộ target trước khi kết luận kết quả activity.
- Phối hợp các dạng: word→meaning, meaning→word, reading→word và matching.
- Distractor lấy từ canonical membership hoặc approved bank phù hợp; loại normalized duplicate và trường hợp có nhiều
  đáp án đúng. Nếu không đủ distractor hợp lệ, trả coverage/readiness error rõ ràng thay vì bịa phương án.
- Khi issue, lưu immutable question snapshot, option order, target provenance, expected answer và revision. DTO trả client
  không chứa answer key hoặc trường có thể suy ra đáp án trước submit.
- Submit chỉ chấm snapshot đã issue cho đúng owner/session/cycle; không tra lại nội dung mutable để đổi đáp án giữa chừng.
- Pass khi tổng score của cycle `>=70%`; `69%` fail và `70%` pass.
- Lưu first score bất biến, latest score và best score cho activity. Lịch sử attempt phải giữ được để audit.

### 4. Attempt, review và cooldown

- Mỗi cycle có tối đa ba submit hợp lệ.
- Request validation lỗi, lỗi kỹ thuật, replay hoặc duplicate idempotency key không tiêu hao attempt.
- Issue và submit đều hỗ trợ idempotency; cùng key và cùng payload trả lại kết quả cũ. Cùng key với payload khác bị từ chối.
- Sau submit fail lần 1 hoặc 2, tạo review queue từ target sai và buộc hoàn tất review evidence trước lần tiếp theo.
- Sau submit fail lần 3, server đặt cooldown 60 phút và vẫn yêu cầu review lại target sai.
- Cooldown dùng server clock; response trả thời điểm đủ điều kiện/remaining duration để UI render. Client sửa clock không
  làm mở khóa sớm.
- Pass hoàn thành Vocabulary activity đúng một lần. Concurrent hoặc duplicate submit không tăng attempt, hoàn thành hay
  phát event/reward hai lần.

### 5. State machine và khả năng tái sử dụng cho Task 5

- Giữ state transition trong service/domain layer có transaction và locking phù hợp; controller/JS không tự quyết định.
- Tách phần dùng chung tối thiểu cho study session, evidence, attempt, immutable issue snapshot, scoring aggregate,
  idempotency, review gate và cooldown khỏi Vocabulary question/render strategy.
- Không tạo abstraction chung chỉ để dự đoán Grammar/Dialogue. Điểm mở rộng bắt buộc hiện tại là Kanji activity ở Task 5.
- Contract public/internal phải biểu diễn rõ: revision, eligibility, progress, remaining review targets, attempt number,
  cooldown, first/latest/best và completion state.
- Ghi quyết định state machine và invariants đã triển khai vào `docs/rules-and-business-logic.md`.

## API và error contract

Thiết kế endpoint nhất quán dưới `/api/learning/v2/` cho các hành động sau; có thể chọn tên cụ thể phù hợp codebase,
nhưng handoff cuối phải liệt kê chính xác method/path/request/response:

- Lấy/resume Vocabulary study state.
- Start/resume study session.
- Ghi flip evidence.
- Ghi `Đã nhớ`/`Chưa nhớ` evidence.
- Issue/resume micro-check batch.
- Submit micro-check batch/cycle.
- Lấy kết quả, review requirement và cooldown state.

Mọi REST endpoint trả `ResponseEntity<ApiResponse<T>>`. Domain failure dùng `ApiException(ErrorCode)` với thông báo tiếng
Việt phù hợp. Phân biệt rõ not-found an toàn, invalid transition, review required, not eligible, cooldown active,
revision mismatch, idempotency conflict và content-not-ready. Không trả stack trace, entity trực tiếp hoặc answer key.

## Persistence và migration

- Dùng bảng/version phù hợp để lưu session, per-membership study evidence, check cycle/attempt, immutable issued question,
  submitted answer và idempotency record hoặc equivalent có cùng tính chất.
- Ràng buộc unique/index phải bảo vệ một active session theo activity+revision, target evidence, attempt ordering,
  idempotency và owner-scoped query thường dùng.
- Cột JSON chỉ dùng khi schema/query/audit thực sự phù hợp; answer snapshot phải đọc và kiểm chứng được sau này.
- Entity, canonical `jlas_schema.sql`, incremental migration và H2 tests phải đồng bộ.
- Migration phải tương thích dữ liệu cũ. Audit/backfill trước khi siết `NOT NULL`; không lẫn seed vào schema.
- Gắn comment `[JLAS-W0193]` cho schema/business changes theo rule dự án.

## UI/UX bắt buộc

Xây trang Vocabulary riêng trong Learning Path V2, không tái sử dụng nguyên trang giáo trình và không điều hướng sang
module Flashcard cũ.

- Bố cục light-tech purple/lime theo visual contract, nhiều khoảng thở; main layout containerless/borderless.
- Vùng thẻ lớn, typography Nhật/reading/meaning rõ, trạng thái đã lật/chưa lật và affordance lật bằng chuột/bàn phím.
- Hai nút có nhãn đầy đủ `Chưa nhớ` và `Đã nhớ`; chỉ enabled sau khi server ghi nhận flip hợp lệ.
- Hiển thị tiến độ unique target, số thẻ còn ôn, số đã nhớ và trạng thái đồng bộ/resume.
- Khi mọi thẻ đủ điều kiện, tự kết thúc study state và hiển thị CTA chuyển sang kiểm tra; không có nút hoàn thành thủ công.
- Micro-check tương tác cho bốn loại câu hỏi, feedback sau submit, tiến độ batch/cycle, first/latest/best, attempt còn lại,
  review queue và countdown cooldown.
- Có slogan cổ vũ ngắn theo trạng thái nhưng không che điểm yếu hoặc hứa hẹn đỗ JLPT.
- Omit dữ liệu không có contract; không hiển thị XP/timer/reward giả.
- Responsive mobile/desktop, keyboard navigation, visible focus, screen-reader labels, contrast hợp lệ và reduced motion.

## TDD và kiểm thử tối thiểu

Viết test đỏ cho rule trước khi implementation. Ít nhất phải có bằng chứng cho:

1. Owner start/resume đúng Vocabulary activity.
2. User khác nhận not-found; package/activity/membership chéo bị chặn.
3. Client không thể chèn, bỏ hoặc đổi target membership.
4. Start/reload idempotent và resume đúng session/revision.
5. Chưa flip không thể remember.
6. `Chưa nhớ` quay lại review queue mà không tạo card trùng.
7. Mọi target phải từng `Đã nhớ` mới được issue check.
8. Revision mới không trộn evidence/check snapshot cũ.
9. Mỗi target được phủ và batch không vượt quá 10.
10. Bốn question type đúng dữ liệu; distractor duplicate/ambiguous bị loại.
11. Thiếu distractor/content trả readiness error, không fallback giả.
12. Issue snapshot bất biến và response không lộ answer key.
13. Submit không thể giả score/answer/attempt/state.
14. Biên 69% fail và 70% pass.
15. First/latest/best cập nhật đúng qua nhiều attempt.
16. Duplicate issue/submit cùng key không tạo thêm record hoặc tốn lượt.
17. Idempotency key reuse với payload khác bị từ chối.
18. Validation/system failure không tốn lượt.
19. Fail lần 1/2 bắt buộc review target sai trước retry.
20. Fail lần 3 tạo cooldown đúng 60 phút và server clock chặn sớm.
21. Sau cooldown vẫn phải hoàn thành review requirement.
22. Concurrent submit không tăng attempt hoặc complete/event hai lần.
23. Pass complete activity đúng một lần và roadmap/resume phản ánh state mới.
24. Node tests cho flashcard state, recycle queue, eligibility, four question types, cooldown và safe empty/error state.
25. UI keyboard, responsive và reduced-motion acceptance.

Không viết test chỉ lặp lại implementation. Test phải chứng minh business invariant, security boundary hoặc regression
quan trọng.

## Rule cố định

- Java 21, Spring Boot 4, Thymeleaf, vanilla JS và vertical slice trong `learning`.
- Không sửa ngầm Smart Test, Flashcard, Textbook, Kaiwa hoặc Mock Test; dùng adapter trong `learning` nếu cần đọc.
- Không thêm lesson quiz, daily quiz, warm-up gate hoặc stage-final quiz vào V2.
- Không làm W0127 và không sửa chức năng thi thử của nhóm khác.
- Gemini/AI chỉ hỗ trợ editorial draft offline có duyệt; không sinh/chấm quiz hoặc quyết định path runtime.
- Không commit secret, API key hoặc `application-local.yaml`.
- Giữ `learning.interactive-v2.enabled=false` mặc định. Task 4 chưa đủ điều kiện bật V2 production.
- Bảo toàn legacy endpoint/record và schema compatibility.
- Commit nhỏ theo intent; không gom refactor không liên quan vào Task 4.

## Verification bắt buộc

Chạy focused tests trong vòng lặp phát triển, sau đó chạy toàn bộ:

```bash
.\mvnw.cmd -q test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
git diff --check
```

Không chỉ dựa vào exit code Maven nếu output bị rút gọn; tổng hợp số test/failure/error/skipped từ Surefire XML. Sau
schema change, kiểm tra entity, `jlas_schema.sql`, migration và H2 tests khớp nhau.

## Hoàn tất và bàn giao cho AI Task 5

Khi Task 4 đạt toàn bộ exit criteria:

1. Cập nhật Task 4 trong implementation plan thành complete/mốc 40% cùng commit và bằng chứng test.
2. Cập nhật business rules đã triển khai.
3. Tạo commit riêng cho Task 4 theo Conventional Commit và `[JLAS-W0193]`.
4. Tạo file handoff Task 4 completion, không sửa nội dung phạm vi Task 5–10 đã duyệt.
5. Trong handoff ghi chính xác:
   - Commit hash/range và working-tree status.
   - API method/path/request/response/error code.
   - Bảng/cột/index/migration order.
   - State machine và transaction/locking boundaries.
   - Cách owner scope và chống IDOR/tampering/answer leakage.
   - Public/internal engine contract mà Kanji Task 5 phải tái sử dụng.
   - Cách thêm Kanji question strategy mà không copy state machine.
   - Focused/full test counts và lệnh đã chạy.
   - Giới hạn hoặc debt còn lại có bằng chứng.
6. Đối chiếu handoff với phần “Điều kiện bắt đầu” trong
   `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task5-to-task10-handoff.md`.

Dừng sau khi bàn giao Task 4. Không bắt đầu Task 5 trong cùng phạm vi này.
