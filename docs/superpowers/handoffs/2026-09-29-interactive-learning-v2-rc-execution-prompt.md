# Prompt thực thi một lần — Hoàn thành JLPT Interactive Learning Path V2 Release Candidate

Bạn tiếp nhận và hoàn thành toàn bộ Release Candidate còn lại của JLPT Interactive Learning Path V2 trong một lượt
liên tục.

## Workspace bắt buộc

- Worktree: `C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`
- Branch: `codex/interactive-learning-v2`
- Baseline tối thiểu phải chứa commit `82de10f`.
- Không thao tác checkout chính `D:\FALL26_FPT\SEP490`.

## Đọc trước

1. `AGENTS.md`
2. `docs/rules-and-business-logic.md`
3. `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`
4. `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`
5. `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task10-final-completion-handoff.md`
6. `docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md`
7. Visual contract trong `docs/superpowers/assets/interactive-learning-v2/`

File kế hoạch bắt buộc thực thi là:

`docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md`

## Mục tiêu

Hoàn thành tuần tự toàn bộ Task 1 → Task 8 trong plan RC:

1. Khóa canonical readiness/grouping contract cho Reading/Listening.
2. Thêm persistence contract cho activity/member Reading/Listening.
3. Persist band `0/4/6/8` vào Warm-up B, tối đa hai exercise Reading+Listening mỗi ngày.
4. Tái sử dụng shared micro-check engine qua strategy/partition seam; không copy state machine.
5. Xây player Reading/Listening riêng của Learning Path.
6. Nối completion, summary và readiness evidence.
7. Tạo Mock Test owner-contract seam; chỉ nối route thật nếu contract tồn tại.
8. Release hardening, data-readiness audit artifact, concurrency/security tests và final RC handoff.

Không dừng ở Task 1, Task 4 hay bất kỳ mốc trung gian nào. Sau khi một task đạt exit criteria, tự commit và chuyển
ngay sang task tiếp theo cho tới hết Task 8.

## Chế độ làm việc khi người dùng vắng mặt

Người dùng sẽ quay lại sau khi toàn bộ RC hoàn thành để tự cấu hình MySQL và chạy web nghiệm thu. Đây là ủy quyền thực
hiện toàn bộ phạm vi trong plan mà không cần chờ duyệt trung gian.

- Không hỏi lại quyết định đã có trong spec, rules, handoff hoặc plan.
- Tự giải quyết lựa chọn triển khai thông thường theo codebase và rule team; ghi quyết định quan trọng vào docs.
- Lỗi compile, test, migration, UI hoặc regression có thể tự sửa không phải lý do dừng.
- Nếu một blocker bên ngoài không thể giải quyết (credential, dịch vụ ngoài, owner contract), ghi bằng chứng, giữ
  adapter/feature gate an toàn, hoàn thành mọi phần độc lập và tiếp tục các task sau không phụ thuộc.
- Chỉ tổng hợp blocker trong báo cáo cuối; không bịa dữ liệu, route, contract hoặc test result.

## Quy trình bắt buộc cho mỗi task

1. Đọc code và test liên quan trước khi sửa.
2. Viết test đỏ cho business rule/security boundary.
3. Thực hiện thay đổi hẹp nhất đủ đạt acceptance criteria.
4. Chạy focused tests.
5. Tự review ownership/IDOR, answer leakage, sanitization, tampering, idempotency, transaction/concurrency, clock,
   schema compatibility, legacy compatibility, responsive và accessibility.
6. Cập nhật implementation plan và `docs/rules-and-business-logic.md` khi rule đã được triển khai.
7. Commit riêng theo Conventional Commit và `[JLAS-W0193]`.
8. Tự chuyển sang task kế tiếp.

Không gom nhiều task vào một commit lớn. Không sửa unrelated code để làm test pass.

## Rule sản phẩm cố định

- Reading/Listening dùng câu hỏi canonical active đúng skill và đúng JLPT level.
- Reading group theo passage; Listening group theo audio; không trộn nguồn khác nhau trong một exercise.
- Mỗi exercise có ít nhất hai câu hợp lệ.
- Không fallback sang level khác và không nhân bản một exercise để đủ quota.
- Band từng skill: `>70=0`, `>50–70=4`, `>40–50=6`, `0–40=8`; thiếu evidence là verification, không phải 0%.
- Reading+Listening tối đa hai exercise tổng cộng mỗi ngày và tôn trọng minute budget.
- Pass `>=70%`; fail 1/2 cho practice lại; fail 3 cooldown 60 phút + review sau thời điểm fail.
- First/latest/best tách biệt; unresolved Reading/Listening không được điểm kỹ năng khác che đi.
- Server giữ answer key, score, attempt, cooldown, state và clock. Client chỉ render state.
- Không dùng AI sinh/chấm câu hỏi runtime.
- Target package score vẫn chỉ gồm Vocabulary/Kanji/Grammar; Dialogue và warm-up comprehension không kéo điểm.
- Mock Test thuộc team khác. Nếu không có owner contract thật, giữ `BLOCKED_INTEGRATION` và UI disabled trung thực.
- Feature flag `learning.interactive-v2.enabled` tiếp tục mặc định `false`.

## Rule Git, team, schema và secret

- Không reset, clean, stash, rebase, merge, push hoặc force-push khi chưa được người dùng yêu cầu.
- Không sửa W0127 hoặc module Mock Test của nhóm khác.
- JWT principal là nguồn user ID duy nhất; mọi lookup scope path → package → activity → membership/session.
- REST dùng `ResponseEntity<ApiResponse<T>>`; domain failure dùng `ApiException(ErrorCode)`.
- Nếu RC cần schema mới, cập nhật đồng thời entity, `jlas_schema.sql`, H2 test và tạo incremental migration kế tiếp.
- Không gộp, squash, xóa, đổi số hoặc viết lại migration trong lượt RC.
- Không gộp hoặc viết lại seed trong lượt RC.
- Không áp schema/seed lên database thật.
- Không tạo/sửa `application-local.yaml`, không copy credential và không commit secret.
- Không push/merge/deploy hoặc bật feature flag production.
- Không commit `.superpowers/` scratch.

Việc hội tụ thành một database, rollup migration và seed canonical sẽ do người dùng làm sau RC theo:

`docs/superpowers/plans/2026-09-29-post-rc-database-convergence-manual.md`

Không thực hiện checklist manual đó trong lượt này.

## Baseline verification

Trước khi sửa code:

```powershell
git status --short
git log -5 --oneline
.\mvnw.cmd -q test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
```

Baseline tham chiếu trước RC: Maven `466/0/0/22 skipped`, frontend `103/103`, speed recall `4/4`, personalized path
`8/8`. Đọc Surefire XML; không chỉ báo exit code.

## Final verification

Sau Task 8, chạy lại toàn bộ:

```powershell
.\mvnw.cmd -q test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
git diff --check
git status --short
```

## Điều kiện được dừng

Chỉ dừng sau khi:

- toàn bộ Task 1–8 trong plan RC có bằng chứng đạt hoặc có blocker bên ngoài được chứng minh;
- mọi phần độc lập quanh blocker đã hoàn thành;
- mỗi task có commit riêng;
- full regression xanh;
- entity/schema/migration đồng bộ;
- có read-only data-readiness audit artifact;
- có manual UAT checklist cho người dùng;
- có final RC completion handoff được commit;
- working tree sạch ngoài scratch đã được xác định.

Báo cáo cuối phải ghi commit range, API/route/schema/state changes, migration order, test counts, security evidence,
Mock Test integration status, feature-flag status, dữ liệu/level còn blocked và các bước MySQL/browser người dùng phải
tự chạy. Không tuyên bố production-ready khi chưa có MySQL audit và browser UAT thật.
