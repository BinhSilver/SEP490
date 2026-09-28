# Prompt bàn giao — Interactive Learning Path V2 tại mốc 30%

Tiếp tục J-LAS Learning Path V2 trên branch `codex/interactive-learning-v2`, worktree:

`C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`

Không thao tác lên checkout chính `D:\FALL26_FPT\SEP490` vì có thể chứa code chưa commit của người khác. Không reset, clean, stash, rebase, merge, push hoặc force-push khi chưa được người dùng yêu cầu.

## Đọc trước

1. `AGENTS.md`
2. `docs/rules-and-business-logic.md`
3. `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`
4. `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`
5. Visual contract trong `docs/superpowers/assets/interactive-learning-v2/`
6. Các commit `3b6bb77`, `09e0312`, `14f827d` và test liên quan.

## Đã hoàn thành 30%

- Task 1: versioned package/activity persistence.
- Task 2: owner-scoped V2 API, server resume và roadmap.
- Task 3: canonical package composer, readiness gate, membership snapshot và feature flag.

Task 3 dùng nguồn thật: lesson/textbook, content vocabulary, vocabulary-kanji, Kanji readings, grammar points/examples, dialogue lines và content audio. Một target package chỉ được compose khi:

- Lesson và textbook đều `PUBLISHED`.
- Cấp JLPT khớp candidate được scheduler chọn.
- Vocabulary đã publish, có reading/meaning và không trùng normalized identity.
- Có Kanji gắn theo vocabulary, có meaning và reading canonical.
- Có 1–3 grammar point với pattern, meaning và example.
- Có dialogue script và audio cho từng line.

Thiếu dữ liệu trả coverage-gap code và không ghi bất kỳ package nào trong batch. Composer không fallback lesson khác và không gọi AI. Membership lưu ID canonical, thứ tự và SHA-256 revision; không sao chép nội dung sách.

Feature flag:

```yaml
learning.interactive-v2.enabled: false
```

hoặc env `LEARNING_INTERACTIVE_V2_ENABLED`. Mặc định phải giữ `false` cho đến khi Task 4–8 hoàn thành. Khi bật, pipeline tạo path dùng composer V2; lỗi readiness làm transaction rollback nên active legacy path không bị supersede nửa chừng.

Migration mới: `sql/migrations/011_interactive_package_membership.sql`. `source_lesson_id` và `content_revision` tạm nullable để DB đã có package V2 cũ nâng cấp an toàn; service chặn package thiếu snapshot. Chỉ siết `NOT NULL` sau khi audit/backfill dữ liệu thật.

## Phạm vi bàn giao — hoàn thành toàn bộ Task 4 đến Task 10

Mục tiêu của lần tiếp nhận này là hoàn thành toàn bộ phần còn lại của
`docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`, từ Task 4 đến Task 10 và đưa
Learning Path V2 từ mốc 30% lên 100%. Task 4 là lát triển khai đầu tiên, không phải điểm dừng của phạm vi bàn giao.

Thực hiện tuần tự Task 4 → 5 → 6 → 7 → 8 → 9 → 10 vì các task sau phụ thuộc state, evidence và scoring contract
của task trước. Sau mỗi task:

1. Chạy focused tests của lát vừa làm và toàn bộ regression commands trong plan.
2. Tự review ownership/IDOR, tính bất biến của snapshot, idempotency, transaction/concurrency, schema compatibility,
   UI responsive/accessibility và ảnh hưởng tới legacy.
3. Cập nhật checklist, phần trăm và bằng chứng kiểm thử trong plan; cập nhật
   `docs/rules-and-business-logic.md` nếu có business rule đã triển khai.
4. Commit riêng bằng Conventional Commit có `[JLAS-W0193]` trong nội dung thay đổi/schema theo rule dự án.
5. Nếu exit criteria đạt và không có blocker thật, tự chuyển sang task kế tiếp, không dừng để xin xác nhận chỉ vì vừa
   hoàn thành một mốc phần trăm.

Chỉ dừng và báo người dùng khi gặp một trong các blocker không thể giải quyết an toàn từ repository: thiếu quyết định
nghiệp vụ làm thay đổi kết quả người học, thiếu contract do team khác sở hữu, migration không thể triển khai tương thích,
hoặc dữ liệu thật không đạt readiness và không có nguồn canonical hợp lệ. Khi báo blocker, nêu bằng chứng, phần độc lập
đã hoàn thành và phương án hẹp nhất để tiếp tục. Không tự bịa dữ liệu, API, đáp án hoặc contract để vượt blocker.

## Task 4 — Vocabulary study và micro-check

Triển khai đúng plan/spec, không mở rộng sang Kanji/Grammar/Dialogue:

1. Đọc membership `VOCABULARY_ENTRY` của đúng activity/package/owner; không nhận danh sách ID từ client.
2. Tạo study session có membership version bằng `source_revision`; reload resume cùng session.
3. Mỗi thẻ phải được lật trước khi chọn `Đã nhớ`/`Chưa nhớ`. Thẻ chưa nhớ quay lại hàng ôn. Mỗi membership phải từng được lưu `Đã nhớ` trước khi mở check.
4. Micro-check phủ mọi target; batch tối đa 10; phối hợp word→meaning, meaning→word, reading→word và matching.
5. Issue immutable question/answer snapshot; answer key chỉ ở server.
6. Pass `>=70%`; lưu first/latest/best. Tối đa 3 submit hợp lệ mỗi cycle; fail lần 3 cooldown 60 phút và bắt buộc review lại target sai.
7. Idempotency key cho issue/submit; lỗi kỹ thuật hoặc request trùng không tốn lượt.
8. Server quyết định state/unlock/cooldown theo clock; client không tự tính.
9. Làm trang flashcard hoàn chỉnh theo visual contract: vùng thẻ lớn, tiến độ, trạng thái đã lật, số thẻ còn ôn và CTA check chỉ xuất hiện khi server cho phép.
10. Test ownership/IDOR, tampering, reload, duplicate submit, đúng biên 70%, ba lượt, cooldown, review requirement và batch coverage trước khi nối UI.

## Rule cố định

- JWT stateless, principal là nguồn user ID duy nhất; query scope path → package → activity → membership/session.
- Không lộ answer key, JWT, API key hoặc dữ liệu nhạy cảm.
- Không thêm lesson quiz/daily quiz/warm-up gate/stage final vào V2.
- Không sửa ngầm module Flashcard/Textbook/Assessment/Kaiwa/Mock Test; dùng adapter trong `learning`.
- AI không sinh/chấm quiz runtime.
- Không làm W0127; không sửa chức năng thi thử của nhóm khác.
- Không dùng seed/demo/fake fallback, không commit secret hoặc `application-local.yaml`.
- TDD, focused tests, full regression, `git diff --check`, commit nhỏ theo `[JLAS-W0193]`.

Baseline sau Task 3:

```text
Maven: 273 tests, 0 failures, 0 errors, 22 skipped
Frontend: 34/34 pass
Speed recall: 4/4 pass
Personalized path: 8/8 pass
Task 3 commit: 14f827d
```

## Sau Task 4

Tiếp tục triển khai Task 5–10 đúng thứ tự và đầy đủ acceptance criteria trong plan:

- Task 5: Kanji study và micro-check dùng lại engine nhưng giữ evidence/membership riêng.
- Task 6: Grammar interaction và micro-check theo từng grammar point, không sinh câu hỏi runtime bằng AI.
- Task 7: trang Dialogue riêng của Learning Path với 180 giây active-visible và bottom evidence.
- Task 8: package completion, điểm ba kỹ năng và popup ba hành động.
- Task 9: Warm-up B cá nhân hóa từ weakness evidence, gồm quota Reading/Listening đã duyệt.
- Task 10: summary, chứng nhận J-LAS, liên kết Mock Test qua owner contract và release hardening.

Không gom nhiều task vào một commit lớn. Với mỗi task, báo ngắn contract/API/schema, chống IDOR và gian lận, số test,
commit và giới hạn còn lại trong progress ledger/handoff; sau đó tiếp tục. Chỉ tuyên bố toàn bộ luồng hoàn thành khi mọi
checkbox Task 4–10, full regression, release hardening và tiêu chí 100% trong plan đều có bằng chứng đạt.

Khi hoàn tất Task 10, tạo handoff cuối ghi rõ commit range, migration order, feature-flag rollout/rollback, audit dữ liệu
thật còn cần chạy, kết quả desktop/mobile/accessibility và các contract phụ thuộc team khác. Không bật
`learning.interactive-v2.enabled=true` mặc định nếu release gate hoặc dữ liệu thật chưa đạt; ghi rõ điều kiện bật thay vì
che giấu trạng thái chưa sẵn sàng.
