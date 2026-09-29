# Interactive Learning V2 — RC Task 4 Completion Handoff

> Date: 2026-09-29
> Branch: `codex/interactive-learning-v2`
> Worktree: `C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`
> Continue from: `cfd5aac`

This handoff supersedes the 30-percent handoff as the continuation checkpoint. RC Tasks 1–4 are complete. The next AI
must execute Tasks 5–8 continuously and finish the RC handoff; do not stop after Task 5.

## Completed Task 4

Commit:

`cfd5aac feat(JLAS-W0193): add comprehension study and micro-check engine`

Implemented:

- `InteractiveComprehensionService` and `InteractiveComprehensionServiceImpl` for both Reading and Listening.
- Internal Reading/Listening adapters backed only by ready canonical `assessment_question` content.
- Dedicated public study DTOs containing source, question text and options without correct answers or explanations.
- Shared engine partition seam:
  - Vocabulary/Kanji/Grammar retain their historical fixed groups of ten.
  - Reading/Listening use one canonical passage/audio fingerprint per score batch.
- One passed exercise advances to the next exercise; the activity becomes `PASSED` only when every exercise batch passes.
- Immutable activity revision is recomputed from ordered exercise fingerprints/revisions. Canonical drift or mixed member
  revisions returns `BLOCKED_CONTENT` instead of mixing new content into an issued path.
- Issue snapshots keep prompt, options and server-only answer keys in `LearningQuizSession`.
- Existing ownership chain and type isolation remain: user → path → activity → membership/session.
- Existing 70% threshold, first/latest/best, three attempts, submit replay, pessimistic locking and 60-minute cooldown
  are reused from `InteractiveStudyEngine`.
- Reading/Listening review after the third failure requires a new server-timestamped source event. Evidence recorded
  before the failure cannot clear `reviewRequired`.
- Fixed the existing multi-batch transition bug: passing a non-final batch now sets `CHECK_READY` for the next batch
  instead of incorrectly reporting `COOLDOWN`.
- No schema migration or seed change was required for Task 4.

## Task 4 service contract available to Task 5

`InteractiveComprehensionService` exposes:

- `studyState(userId, activityId)`
- `issueMicroCheck(userId, activityId)`
- `submitMicroCheck(userId, activityId, request)`
- `microCheckState(userId, activityId)`
- `recordReviewEvidence(userId, activityId)`
- `completeReview(userId, activityId)`

There are deliberately no web endpoints yet. Task 5 owns controller routes, authentication principal extraction,
existing V2 rate limiter integration, Thymeleaf/JS/CSS and roadmap links.

For Reading, call `recordReviewEvidence` only from the explicit “reached end of passage” interaction. For Listening,
call it only when the approved canonical audio is requested/played. The endpoint accepts no client duration, source ID,
score, batch or completion flag. `completeReview` still enforces that the stored event happened after the third failure;
cooldown expiry remains a separate server-side requirement.

## Verification at `cfd5aac`

- `InteractiveComprehensionServiceTest`: 6 passed.
- Vocabulary/Kanji/Grammar/Comprehension focused engine suites and Warm-up composer: passed.
- Full Maven suite: **485 tests, 0 failures, 0 errors, 22 skipped**.
- Frontend Node suite: **103/103 passed**.
- Speed Recall rules: **4/4 passed**.
- Personalized Path JS: **8/8 passed**.
- `git diff --check`: clean before commit.

## Remaining work: Tasks 5–8

Follow the exact acceptance criteria in:

`docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md`

1. **Task 5:** authenticated comprehension controller/page/API, rate limiting, dedicated Reading/Listening player,
   approved purple/lime responsive UI, source-event wiring, resume/error/review/cooldown/pass states, roadmap links.
2. **Task 6:** package completion, first/latest/best Reading/Listening summary, unresolved batches and honest readiness.
3. **Task 7:** validated Mock Test owner-contract seam only; do not implement the other team's Mock Test product.
4. **Task 8:** security/tampering/replay/concurrency hardening, coverage audit, full regression, rules update and final RC
   handoff for the user's later MySQL/browser acceptance.

Do not merge, push, deploy, operate the user's MySQL database, work on W0127, or consolidate migrations/seeds. Database
convergence remains the user's separate post-RC action documented in
`docs/superpowers/plans/2026-09-29-post-rc-database-convergence-manual.md`.

## Copyable prompt for the next AI

```text
Continue the Interactive Learning Path V2 Release Candidate in:
C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490

Branch: codex/interactive-learning-v2
Start from commit: cfd5aac

Read AGENTS.md first, then read:
docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-rc-task4-completion-handoff.md
and every authoritative input it references, especially:
docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md

RC Tasks 1–4 are complete. Execute Tasks 5, 6, 7 and 8 in order and continue automatically until all four tasks,
their tests, commits and the final RC completion handoff are finished. Do not stop after Task 5 and do not request
intermediate approval for decisions already fixed in AGENTS.md, the plan, design spec, rules or handoff.

Use TDD and separate commits per vertical slice. Preserve principal-derived ownership, immutable snapshots,
server-owned scoring, answer secrecy, submit idempotency, 70% pass, three attempts and 60-minute cooldown. Task 5 must
wire the existing InteractiveComprehensionService to authenticated and rate-limited endpoints and the dedicated
Reading/Listening UI; do not rebuild or duplicate the engine.

Do not merge, push, deploy, touch the user's MySQL database, implement the other team's Mock Test product, work on
W0127, or consolidate/squash migrations and seeds. Preserve other contributors' work and follow all team/git rules.
```
