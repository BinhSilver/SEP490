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

## Làm tiếp Task 4 — Vocabulary study và micro-check

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

Khi xong Task 4, báo contract/API/schema, cách chống IDOR và gian lận, test count, commit, giới hạn còn lại. Task 4 chỉ đưa plan lên 40%; không tuyên bố toàn bộ luồng đã hoàn thành.
