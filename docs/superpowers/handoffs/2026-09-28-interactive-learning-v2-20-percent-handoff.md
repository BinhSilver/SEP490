# Prompt bàn giao — Interactive Learning Path V2 từ mốc 20%

Bạn đang tiếp quản J-LAS. Hãy tiếp tục Learning Path tương tác V2 đã được duyệt; không quay lại cách bê nguyên lesson giáo trình hoặc quiz tổng hợp cuối bài/ngày.

## Checkout bắt buộc

- Branch: `codex/interactive-learning-v2`
- Worktree: `C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`
- Commits: `3b6bb77` (persistence) và `09e0312` (secure API/roadmap).
- Checkout chính `D:\FALL26_FPT\SEP490` có thể chứa thay đổi chưa commit của người khác. Không reset, clean, stash, stage hoặc ghi đè checkout đó.

## Đọc trước khi sửa

1. `AGENTS.md`
2. `docs/rules-and-business-logic.md`
3. `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`
4. `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`
5. Ba ảnh trong `docs/superpowers/assets/interactive-learning-v2/`
6. Diff và test của hai commit bàn giao.

Sau khi đọc, phân tích contract theo code thật, xác nhận worktree sạch, nêu ngắn gọn lát sẽ làm rồi triển khai; không chỉ viết lại kế hoạch.

## Đã hoàn thành 20%

- Có boundary `LEGACY_V1` / `INTERACTIVE_V2`; dữ liệu cũ mặc định legacy.
- Có `learning_path_package`, V2 activity fields, schema canonical và migration `010_interactive_learning_path_v2.sql`.
- Repository scope theo owner; start dùng pessimistic lock, chặn package khóa và idempotent.
- Target V2 bị chặn nếu thiếu một trong bốn activity hoặc thiếu state.
- API: `GET /api/learning/v2/paths/current`, `GET /api/learning/v2/packages/{packageId}`, `POST /api/learning/v2/packages/{packageId}/start`.
- Page thử nghiệm: `GET /learning/path/v2`, rail đúng `Từ vựng → Kanji → Ngữ pháp → Hội thoại`.
- Legacy `/learning/path` và API legacy chưa đổi.

Giới hạn có chủ đích: chưa có composer tạo V2 từ canonical data, nên user thật chưa tự nhận V2 path. API trả not-found nếu chưa có record V2. Không thêm seed/demo/fake data để che giới hạn này.

## Làm tiếp Task 3 để đạt 30%

Triển khai **Package composer và data readiness gate** trong plan:

1. Khảo sát nguồn canonical thật cho Vocabulary, Kanji, 1–3 Grammar point và Dialogue. Ghi rõ ID/provenance được tham chiếu hoặc snapshot; không tạo nguồn sự thật thứ hai.
2. Tạo contract composer trong module `learning`; không sửa ngầm Textbook/Flashcard/Kaiwa/Assessment.
3. Chỉ phát package khi đủ bốn component published/approved, đúng level và không duplicate identity.
4. Thiếu dữ liệu phải ghi coverage gap có mã nguyên nhân; không lấy lesson tùy ý, mass-publish hoặc dùng AI runtime để vá.
5. Tạo bốn activity đúng thứ tự, cùng package/path owner; transaction và retry idempotent.
6. Bật tạo V2 qua feature flag rõ ràng; legacy tiếp tục chạy.
7. Viết test trước cho đủ/thiếu component, unpublished, sai level, duplicate, retry, rollback và legacy regression.
8. Nếu đổi schema: cập nhật `jlas_schema.sql`, migration kế tiếp, comment `[JLAS-W0193]`; không sửa seed chưa duyệt.
9. Commit riêng theo Conventional Commits. Không bắt đầu Task 4 trước khi Task 3 đạt exit criteria.

## Rule không được đổi

- JWT stateless; identity từ principal. Mọi read/write scope path → package → activity → owner; ID người khác trả not-found an toàn.
- Server quyết định readiness/state/score/unlock/cooldown/completion; không tin client.
- Không lộ answer key; không log JWT, AI key hoặc dữ liệu nhạy cảm.
- V2 không có lesson quiz, daily quiz, warm-up gate hay stage-final quiz.
- Target bình thường đủ bốn phần; Dialogue chỉ completion, không tính điểm.
- AI chỉ tạo nháp offline qua review; không sinh/chấm runtime.
- Không làm W0127. W0193 chỉ qua owner contract; không sửa thi thử của nhóm khác.
- UI chỉ render contract thật; không bịa XP/reward/timer/download/note/nút chết.
- Không commit secret, `application-local.yaml`, seed chưa duyệt hoặc file ngoài lát.

## Test và Git

- TDD: test lỗi trước, implementation tối thiểu, refactor; không sửa test để hợp thức hóa lỗi.
- Chạy focused tests, rồi full suite. Trước commit chạy `git diff --check` và kiểm staged diff.
- Không merge/rebase/push/force-push nếu chưa được yêu cầu. Không amend hai commit bàn giao.

Baseline:

```text
Maven: 268 tests, 0 failures, 0 errors, 22 skipped
Frontend: 34/34 pass
Speed recall: 4/4 pass
Personalized path: 8/8 pass
Working tree: clean trước khi thêm file handoff này
```

```powershell
.\mvnw.cmd test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
```

Khi xong Task 3, báo nghiệp vụ, schema/migration, readiness gate, feature flag, test count, commit, rủi ro và task kế tiếp. Task 3 chỉ đưa tiến độ lên 30%, không được tuyên bố toàn plan hoàn thành.
