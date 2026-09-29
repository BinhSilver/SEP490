# Prompt bàn giao — Interactive Learning Path V2 từ Task 5 đến Task 10

Tiếp nhận và hoàn thành phần còn lại của J-LAS Interactive Learning Path V2 từ **Task 5 đến Task 10** trên branch
`codex/interactive-learning-v2`, worktree:

`C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`

Task 4 đã được AI khác hoàn thành và bàn giao tại mốc 40%:

- `e4bc44f` — `feat(JLAS-W0193): add vocabulary study and micro-check`.
- `05481f7` — `docs(JLAS-W0193): record vocabulary micro-check state machine and Task 4 completion handoff`.
- Baseline đã xác minh: Maven 293 test, 0 failure/error, 22 skipped; frontend 40/40; speed recall 4/4;
  personalized path 8/8.

Không triển khai lại hoặc mở rộng Task 4. Chỉ sửa lỗi Task 4 khi lỗi đó làm Task 5 không thể tích hợp hoặc làm sai
contract đã duyệt; phải có test tái hiện, ghi rõ bằng chứng và giữ bản sửa ở phạm vi hẹp nhất.

Không thao tác checkout chính `D:\FALL26_FPT\SEP490` vì có thể chứa code chưa commit của người khác. Không reset,
clean, stash, rebase, merge, push hoặc force-push khi chưa được người dùng yêu cầu.

## Điều kiện bắt đầu

Trước khi viết code Task 5:

1. Đọc `AGENTS.md` và mọi `AGENTS.md` áp dụng cho file sẽ sửa.
2. Đọc `docs/rules-and-business-logic.md`.
3. Đọc `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`.
4. Đọc `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`.
5. Đọc `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-30-percent-handoff.md`.
6. Đọc đầy đủ
   `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task4-completion-handoff.md`, đặc biệt §5–§11.
7. Xem visual contract trong `docs/superpowers/assets/interactive-learning-v2/`.
8. Kiểm tra `git status`, `git log` và diff của commit Task 4; không đoán mã commit.
9. Xác minh `HEAD` chứa `e4bc44f` và `05481f7`; `git status` chỉ được phép còn `.superpowers/` là workspace scratch.
10. Chạy focused tests liên quan trực tiếp tới Vocabulary để xác nhận baseline trước khi refactor seam dùng chung.

Task 5 chỉ được bắt đầu khi Task 4 đã cung cấp được các contract nền sau:

- Study session/version gắn với membership snapshot và `source_revision`.
- Flip/remember evidence có resume; mọi target phải được nhớ trước khi được issue micro-check.
- Immutable question/answer snapshot; answer key chỉ tồn tại phía server.
- Server-owned eligibility, score, attempt cycle, review requirement và cooldown.
- Pass từ `70%`; tối đa ba submit hợp lệ mỗi cycle; lần ba không đạt khóa 60 phút.
- First/latest/best score; **submit** idempotent; request submit lỗi hoặc trùng không tốn lượt.
- Owner scope đi theo principal → path → package → activity → membership/session.

Các contract này đã được Task 4 xác nhận đạt và không có blocker đầu vào Task 5. Ba giới hạn đã biết phải được hiểu đúng:

- Issue chưa idempotent chặt: double-click có thể tạo snapshot `ISSUED` mồ côi nhưng không tiêu hao attempt; submit đã
  idempotent. Không tuyên bố issue idempotent nếu chưa bổ sung lookup/key và test tương ứng.
- Sau fail lần 1/2, review là soft-nudge và người học có thể practice lại ngay; chỉ sau fail lần 3 mới bắt buộc review
  cùng cooldown 60 phút, theo spec §4.2/§4.3 và ruling đã ghi trong ledger.
- State/data layer content-agnostic nhưng chưa có shared engine class. Task 5 phải extract seam/strategy tối thiểu từ
  `InteractiveVocabularyServiceImpl`, giữ nguyên Vocabulary regression, rồi thêm Kanji strategy; không copy state machine.

Nếu khi kiểm tra lại phát hiện contract khác không đạt, báo **blocker đầu vào Task 5**, đưa test hoặc đoạn code làm bằng
chứng và hoàn thiện mọi phần độc lập không phụ thuộc blocker.

## Phạm vi phải hoàn thành

Mục tiêu của lần tiếp nhận này là hoàn thành tuần tự **Task 5 → 6 → 7 → 8 → 9 → 10**, đưa plan từ mốc 40% lên
100%. Không dừng ở bất kỳ mốc trung gian nào; blocker cục bộ được xử lý theo quy tắc tiếp tục bên dưới.

## Chế độ thực thi liên tục khi người dùng vắng mặt

Người dùng sẽ treo máy và chỉ quay lại xem kết quả, chạy web, nghiệm thu sau khi toàn bộ lượt triển khai kết thúc. Đây
là ủy quyền rõ ràng để thực hiện trọn Task 5–10 trong một lượt:

- Không dừng để xin duyệt kế hoạch, thiết kế, migration, API, UI, test, commit hoặc mốc phần trăm trung gian đã nằm trong
  spec/plan/handoff này.
- Không gửi câu hỏi hoặc chờ phản hồi chỉ vì vừa hoàn thành một task. Sau khi test và commit Task 5, tự bắt đầu Task 6;
  tiếp tục như vậy đến khi hoàn thành Task 10.
- Tự giải quyết lựa chọn triển khai thông thường theo codebase, `AGENTS.md`, spec và business rules đã duyệt. Ghi quyết
  định đáng kể vào rules/plan/handoff để người dùng review sau.
- Một test fail, lỗi compile, lỗi migration, lỗi UI hoặc regression là việc phải điều tra và sửa trong lượt làm; không
  dùng lỗi có thể tự sửa làm lý do dừng sớm.
- Mỗi task vẫn phải chạy focused tests, full regression, tự review và commit riêng. Việc người dùng sẽ test web sau
  không thay thế nghĩa vụ kiểm thử của AI.
- Không mở PR, merge, push, deploy, bật feature flag production hoặc thay đổi dữ liệu thật nếu các hành động đó chưa
  được người dùng yêu cầu. Hoàn thiện code và bằng chứng reviewable trên branch hiện tại.

Nếu gặp blocker bên ngoài thật sự như thiếu credential, dịch vụ ngoài không khả dụng hoặc contract thuộc team khác chưa
tồn tại, không dừng toàn bộ lượt làm: ghi blocker cùng bằng chứng, giữ adapter/feature gate an toàn, hoàn thành mọi phần
độc lập của task hiện tại và tiếp tục mọi task sau không phụ thuộc blocker. Chỉ báo tổng hợp ở cuối. Không bịa contract,
dữ liệu hoặc kết quả kiểm thử và không tuyên bố 100% cho acceptance criterion chưa được chứng minh.

Sau mỗi task:

1. Chạy focused tests trước, sau đó chạy toàn bộ regression commands trong plan.
2. Tự review nghiệp vụ, ownership/IDOR, tampering, answer-key exposure, idempotency, transaction/concurrency, clock,
   schema compatibility, legacy compatibility, responsive và accessibility.
3. Cập nhật checklist, phần trăm và bằng chứng trong implementation plan.
4. Cập nhật `docs/rules-and-business-logic.md` khi business rule đã được triển khai.
5. Commit riêng theo Conventional Commit; schema/business changes phải gắn `[JLAS-W0193]` theo rule dự án.
6. Khi task đạt exit criteria, tự chuyển sang task kế tiếp.

Nếu phát hiện thiếu quyết định nghiệp vụ có thể làm thay đổi kết quả người học, giữ hành vi bảo thủ theo spec hiện tại,
ghi assumption/rủi ro và tiếp tục các phần không phụ thuộc. Khi kết thúc Task 10, nêu bằng chứng, phần chưa thể chứng minh
và phương án hẹp nhất để xử lý. Không tự bịa dữ liệu, API, đáp án hoặc contract.

## Task 5 — Kanji study và micro-check (50%)

Tái sử dụng state/data contract Task 4 bằng cách extract abstraction/strategy chung tối thiểu từ
`InteractiveVocabularyServiceImpl`; không copy-paste thành một state machine Kanji riêng. Giữ nguyên hành vi và toàn bộ
Vocabulary tests trong lúc refactor.

### Nghiệp vụ

1. Đọc đúng membership `KANJI_ENTRY` của activity/package/path thuộc principal; không nhận danh sách Kanji từ client.
2. Study session và evidence của Kanji tách biệt hoàn toàn với Vocabulary dù cùng dùng engine.
3. Flashcard hiển thị Kanji, nghĩa, On/Kun reading và ví dụ canonical đã được composer snapshot; chỉ hiển thị trường có
   dữ liệu thật đã duyệt.
4. Người học phải lật thẻ trước khi chọn `Đã nhớ` hoặc `Chưa nhớ`; thẻ chưa nhớ quay lại hàng ôn.
5. Mọi Kanji membership phải từng được lưu `Đã nhớ` trong revision hiện tại trước khi server mở micro-check.
6. Micro-check phủ toàn bộ target theo batch tối đa 10, phối hợp Kanji→reading, reading→Kanji, Kanji→meaning và matching.
7. Distractor phải lấy từ canonical membership/approved bank phù hợp, không gây nhiều đáp án đúng và không sinh runtime
   bằng AI.
8. Giữ nguyên contract Task 4: snapshot bất biến, pass `>=70%`, first/latest/best, ba lượt mỗi cycle, soft-nudge review
   sau fail 1/2, bắt buộc review + cooldown 60 phút sau fail 3, submit idempotency và server clock.
9. Hoàn thành Kanji activity khi micro-check đạt; điểm Kanji không bù hoặc ghi đè điểm Vocabulary.
10. UI dùng visual contract: thẻ lớn, tiến độ rõ, trạng thái lật, số thẻ còn ôn, trạng thái retry/cooldown và CTA do
    server eligibility điều khiển; hỗ trợ keyboard, mobile, contrast và reduced motion.

### Kiểm thử tối thiểu

- Owner đọc/resume được; user khác nhận not-found an toàn.
- Package/activity/membership chéo bị từ chối.
- Client không chèn hoặc bỏ Kanji ID khỏi scope.
- Chưa lật không thể remember; unresolved card quay lại; reload resume đúng revision.
- Revision thay đổi không trộn evidence hoặc câu hỏi cũ.
- Check chỉ mở sau khi mọi thẻ đã được nhớ.
- Bốn dạng câu hỏi dùng đúng target; mỗi target được phủ; batch không quá 10.
- Không lộ answer key; sửa score/attempt/state/cooldown từ client không có tác dụng.
- Biên 69% fail, 70% pass; first/latest/best đúng.
- Duplicate issue không tạo attempt; duplicate submit trả kết quả cũ và không tạo thêm attempt; request lỗi không tốn lượt.
- Fail lần 1/2 trả target sai và cho practice lại; fail lần ba tạo cooldown 60 phút, server clock chặn sớm và review
  target sai là bắt buộc.
- Concurrent submit không tăng attempt hai lần hoặc phát thưởng hai lần.
- UI resume, empty-safe rendering, keyboard/mobile/reduced-motion hoạt động.

Nếu Task 5 chọn bổ sung issue idempotency chặt cho shared engine, phải áp dụng đồng thời cho Vocabulary và Kanji, có
idempotency-conflict semantics, cleanup/migration phù hợp và regression test. Đây là hardening có ích, không phải blocker
để bắt đầu Task 5.

Hoàn tất Task 5 bằng commit riêng, cập nhật plan lên 50%, rồi tiếp tục Task 6.

## Task 6 — Grammar interaction (60%)

1. Dùng 1–3 grammar point đã được composer snapshot; không bê nguyên trang giáo trình vào Learning Path.
2. Mỗi point có mục tiêu, cấu trúc, phân tích thành phần, ví dụ canonical và luyện trực tiếp trong trang.
3. Người học phải hoàn thành ordering cùng fill/select practice cho từng point trước micro-check.
4. Micro-check có ít nhất hai câu approved mới cho mỗi point và không lặp nguyên văn practice vừa làm.
5. Phản hồi ngay phải giải thích vì sao đúng/sai bằng nội dung đã duyệt; AI không sinh câu hoặc chấm runtime.
6. Áp dụng cùng attempt/score/cooldown, soft-nudge review sau fail 1/2, bắt buộc review sau fail 3 và submit-idempotency
   contract của Task 4.
7. Giao diện bám `grammar-reference.png`, containerless/borderless ở bố cục chính và chỉ dùng card cho thành phần tương tác
   hoặc dày thông tin.
8. Test coverage từng point, sentence ordering integrity, normalized fill/select answer, snapshot, ownership, tampering,
   duplicate/concurrency, resume và accessibility.

Hoàn tất Task 6 bằng commit riêng, cập nhật plan lên 60%, rồi tiếp tục Task 7.

## Task 7 — Dialogue completion đáng tin cậy (70%)

1. Tạo trang Dialogue riêng trong Learning Path, đọc qua adapter canonical ở chế độ read-only; không redirect hoặc gắn
   trực tiếp trang sách.
2. Chỉ dùng dialogue membership của activity thuộc principal; script/audio phải đến từ snapshot/canonical source.
3. Browser gửi visible heartbeat; server tự tính active time, cap mỗi heartbeat tối đa 20 giây và reset chuỗi khi gap
   lớn hơn 30 giây.
4. Persist bottom-reached evidence; client không thể tự gửi tổng giây hoặc cờ completed để được tin trực tiếp.
5. Chỉ hoàn thành khi có ít nhất 180 giây active-visible và đã cuộn tới cuối nội dung.
6. Khi rời sớm, trả remaining time và lời nhắc thân thiện “Hãy nán lại thêm để luyện hội thoại”.
7. Dialogue chỉ đóng góp trạng thái hoàn thành, không tham gia mẫu số điểm.
8. Test hidden tab, heartbeat replay/out-of-order, gap reset, cap, bottom tampering, reload resume, ownership,
   idempotency và concurrent completion.

Hoàn tất Task 7 bằng commit riêng, cập nhật plan lên 70%, rồi tiếp tục Task 8.

## Task 8 — Package completion và hành động kế tiếp (80%)

1. Transactionally hoàn thành package chỉ khi Vocabulary, Kanji và Grammar đã pass, Dialogue đã hoàn thành.
2. `Điểm bài = (Vocabulary + Kanji + Grammar) / số kỹ năng có điểm hợp lệ`. Dialogue không vào tử số hoặc mẫu số.
   Với target package chuẩn hiện tại composer yêu cầu đủ ba kỹ năng có điểm, nhưng implementation vẫn phải tính từ dữ
   liệu thực thay vì hard-code chia ba.
3. Reward, streak và completion event chỉ phát đúng một lần dù retry hoặc concurrent request.
4. Mở package kế tiếp theo server state. “Học vượt” chỉ khả dụng khi next package hợp lệ; không làm thay đổi ngày lịch
   hoặc quota bằng dữ liệu client.
5. Hiển thị popup ba hành động: `Trở về lộ trình`, `Học vượt`, `Nghỉ ngơi`. Package cuối chuyển tới summary.
6. Test gate, calculation, missing-score safety, duplicate/concurrent completion, exactly-once event/reward, unlock và
   cả ba nhánh điều hướng.

Hoàn tất Task 8 bằng commit riêng, cập nhật plan lên 80%, rồi tiếp tục Task 9.

## Task 9 — Warm-up B cá nhân hóa (90%)

1. Chuyển weakness evidence đã được chứng minh từ Smart Test thành activity cấp knowledge item; không bắt học lại cả
   lesson và không sao chép nguyên giáo trình.
2. Vocabulary/Kanji sai trở thành tập flashcard + micro-check có membership cụ thể.
3. Grammar sai trỏ tới grammar point canonical, practice và approved question bank.
4. Reading/Listening dùng ngân hàng approved và áp dụng band:
   - `>70%`: 0 bài.
   - `>50%–70%`: 4 bài.
   - `>40%–50%`: 6 bài.
   - `0%–40%`: 8 bài.
   - Thiếu evidence: tạo bước verification, không kết luận yếu.
5. Reading và Listening tối đa hai activity tổng cộng mỗi ngày, đồng thời tôn trọng time budget người học.
6. V2 không có warm-up gate, daily quiz, lesson quiz hoặc stage-final quiz; legacy record/endpoint vẫn đọc được.
7. Lưu provenance từ weakness evidence tới membership/activity để giải thích “vì sao tôi học nội dung này”.
8. Test đầy đủ boundary của mọi band, thiếu evidence, time-budget packing, dedupe, deterministic generation,
   ownership và legacy compatibility.

Hoàn tất Task 9 bằng commit riêng, cập nhật plan lên 90%, rồi tiếp tục Task 10.

## Task 10 — Summary, certificate và release hardening (100%)

1. Summary hiển thị first/latest/best theo từng kỹ năng, dimension còn yếu, Dialogue completion và readiness confidence.
2. Không dùng trung bình tổng để che một kỹ năng yếu; readiness phải giải thích evidence và confidence.
3. Chứng nhận chỉ mang tên **“Chứng nhận hoàn thành lộ trình J-LAS”**, không tuyên bố người dùng đã đỗ JLPT.
4. Chỉ cấp chứng nhận khi mọi package bắt buộc hoàn thành; issuance phải idempotent và owner-scoped.
5. Nút thi thử chỉ điều hướng qua W0193 Mock Test owner contract có thật; không sửa chức năng thi thử của nhóm khác và
   không làm W0127.
6. Bổ sung rate limit hợp lý cho issue/submit/heartbeat và kiểm tra IDOR, replay, concurrent submit/completion,
   server-clock boundaries, answer-key leakage và auditability.
7. Audit consumer trước khi quyết định retirement legacy. Giữ migration/backward compatibility và ghi rollback rõ.
8. Chạy audit dữ liệu thật bằng read-only query khi có cấu hình hợp lệ; không dùng seed/demo để tuyên bố production ready.
9. Chạy E2E luồng resume → study → check → cooldown/review → pass → dialogue → completion → summary; chụp và kiểm tra
   desktop/mobile theo visual contract, keyboard, screen-reader labels, contrast và reduced motion.
10. Chỉ bật mặc định `learning.interactive-v2.enabled=true` khi toàn bộ release gate và dữ liệu thật đạt. Nếu chưa đạt,
    giữ `false` và ghi chính xác điều kiện rollout còn thiếu.

## Rule kỹ thuật và bảo mật cố định

- Java 21, Spring Boot 4, Thymeleaf và vanilla JS; giữ vertical slice trong `learning`.
- Mọi REST response dùng `ResponseEntity<ApiResponse<T>>`; domain failure dùng `ApiException(ErrorCode)`.
- JWT stateless; principal là nguồn user ID duy nhất. Không nhận user ID từ body/query để quyết định ownership.
- Scope mọi lookup theo path → package → activity → membership/session thuộc principal; IDOR trả not-found an toàn.
- Browser chỉ render server state; server quyết định eligibility, score, attempts, unlock, active time và cooldown.
- Không lộ answer key, JWT, API key, stack trace hoặc dữ liệu nhạy cảm.
- Gemini/AI chỉ hỗ trợ editorial draft offline có duyệt; không sinh/chấm quiz hoặc quyết định path tại runtime.
- Không sửa ngầm Smart Test, Flashcard, Textbook, Kaiwa hoặc Mock Test; tích hợp qua adapter/owner contract.
- Không dùng seed/demo/fake fallback; không commit secret hoặc `application-local.yaml`.
- Sửa `jlas_schema.sql` trực tiếp và thêm incremental migration cho schema mới; seed để file riêng. Migration phải tương
  thích dữ liệu cũ, có audit/backfill trước khi siết `NOT NULL`.
- Dùng TDD cho state/security/business rules; không viết test chỉ lặp lại implementation.
- UI dùng dữ liệu thật, bỏ thành phần không có contract. Giữ phong cách light-tech purple/lime, nhiều khoảng thở,
  containerless/borderless ở layout chính; card dành cho tương tác hoặc thông tin dày.

## Verification bắt buộc

```bash
.\mvnw.cmd -q test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
git diff --check
```

Không chỉ dựa vào exit code Maven nếu output bị rút gọn; tổng hợp số test/failure/error/skipped từ Surefire reports.
Sau thay đổi schema, kiểm tra đồng bộ giữa entity, `jlas_schema.sql`, migration và H2 tests.

## Kết quả bàn giao cuối

Chỉ gửi báo cáo kết thúc cho người dùng sau khi đã đi hết Task 5–10, chạy các verification khả dụng và tạo handoff cuối.
Không dùng báo cáo tiến độ trung gian làm điểm dừng. Khi Task 10 hoàn thành, tạo handoff cuối và báo:

- Commit range Task 5–10 và commit riêng của từng task.
- File/API/schema/state-machine đã thay đổi.
- Migration order, backfill/audit và rollback.
- Cách chống IDOR, tampering, answer-key leakage, duplicate và race condition.
- Kết quả focused tests, full Maven/Node suites và E2E.
- Bằng chứng UI desktop/mobile/accessibility.
- Trạng thái feature flag và điều kiện rollout.
- Kết quả audit dữ liệu thật hoặc giới hạn chính xác nếu chưa có quyền/cấu hình DB.
- Contract còn phụ thuộc team khác và phần nào đã được adapter bảo vệ.
- Hướng dẫn ngắn để người dùng chạy ứng dụng, mở đúng trang và nghiệm thu toàn bộ luồng trên web sau khi quay lại.

Chỉ tuyên bố 100% khi mọi checkbox Task 5–10 có bằng chứng đạt. Phần trăm là vertical slice đã chạy đúng nghiệp vụ và
qua kiểm thử, không phải tỷ lệ số dòng code đã viết.
