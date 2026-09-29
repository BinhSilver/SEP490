# Interactive Learning V2 — RC 30% Handoff (Tasks 1–3 complete)

> Date: 2026-09-29
> Branch: `codex/interactive-learning-v2`
> Worktree: `C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490`
> Continue from: `125354c`

## 1. Required execution instruction for the next AI

Read this handoff and every authoritative document below before editing. Continue the Release Candidate plan from
**Task 4 through Task 8 in order**. Do not stop after one task and do not ask for intermediate approval for decisions
already fixed in the plan, design spec, business rules, AGENTS.md, or this handoff. Use TDD for each behavior change,
commit each completed vertical slice separately, run the full backend/frontend regression gates after Task 8, and
write the final RC completion handoff. Stop only for a real blocker that cannot be resolved from repository evidence.

Do not merge branches, push, deploy, operate the user's MySQL database, or consolidate/squash schema migrations and
seed files. The user will perform post-RC database convergence separately using the manual checklist already in the
repository.

## 2. Authoritative inputs

Read in this order:

1. `AGENTS.md`
2. `docs/rules-and-business-logic.md`
3. `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`
4. `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`
5. `docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md`
6. `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task10-final-completion-handoff.md`
7. This handoff
8. Visual references under `docs/superpowers/assets/interactive-learning-v2/`

The post-RC manual-only database work is documented at
`docs/superpowers/plans/2026-09-29-post-rc-database-convergence-manual.md`. It is context, not executable scope for
Tasks 4–8.

## 3. Completed RC scope

### Task 1 — canonical comprehension readiness

Commit: `a003130 feat(JLAS-W0193): define comprehension exercise readiness`

- Added a read-only `ComprehensionExerciseCatalog` backed by active, exact-skill, exact-level
  `assessment_question` rows.
- Reading groups by sanitized passage + level + outcome/topic identity.
- Listening groups by normalized safe HTTP(S) or root-relative audio URL + level + outcome/topic identity.
- Requires at least two valid MCQs per exercise. Invalid options, duplicate normalized answers, invalid answer index,
  missing source, missing identity, and insufficient group size become explicit catalog gaps.
- Exercise/question ordering is deterministic. Fingerprint and source revision use SHA-256.
- Correct answer and explanation remain in the internal server model and use `@JsonIgnore`; learner-facing JSON cannot
  serialize them accidentally.
- The catalog performs no remote media fetch.

### Task 2 — Reading/Listening persistence

Commit: `6cfec43 feat(JLAS-W0193): add comprehension activity persistence`

- Added `READING` and `LISTENING` to `LearningActivityType`.
- Added `COMPREHENSION_QUESTION` to `LearningMemberType`; `canonical_id` is an
  `assessment_question.id`.
- Updated canonical `jlas_schema.sql` checks.
- Added incremental `sql/migrations/016_interactive_comprehension_activities.sql`.
- Proved H2 entity persistence, deterministic membership order, and legacy enum compatibility.

Do not renumber, delete, merge, or squash migration 016 during Tasks 4–8.

### Task 3 — persisted Warm-up B banding

Commit: `125354c feat(JLAS-W0193): compose playable comprehension warm-ups`

- `WarmUpBComposerImpl` reads only finalized server-side `LearningProfileSnapshot.skillScores`.
- Applies the approved Reading and Listening bands independently: `>70%=0`, `>50–70%=4`, `>40–50%=6`,
  `0–40%=8`; absent/zero evidence yields `NEEDS_VERIFICATION`.
- Resolves exact-level ready exercises from the catalog, never duplicates an exercise to fill a quota, and emits
  `CONTENT_COVERAGE_INSUFFICIENT` with ready/required counts when the bank is short.
- Packs at most two Reading/Listening exercise groups per day and respects the daily minute budget using the approved
  15-minute estimate.
- A day can persist one Reading activity, one Listening activity, or one of each. Members are ordered by exercise then
  question and share one combined immutable activity revision.
- Existing Vocabulary/Kanji/Grammar composition, path-wide package order, item order, and retry idempotency remain.
- Fixed an enum-expansion regression by keeping GOAL_LEARNING's required target set explicitly limited to
  Vocabulary/Kanji/Grammar/Dialogue. Comprehension activities belong to dynamic WARM_UP packages.

## 4. Important state and limits at this checkpoint

- Reading/Listening activities are persisted but are **not playable yet**. This is expected at 30%; Task 4 adds the
  engine strategies and Task 5 adds the player/API/UI.
- Existing V2 paths that already have any WARM_UP package are not backfilled. Composer idempotency intentionally returns
  the existing warm-up result. Verify with a newly composed V2 path during final UAT.
- Exercise boundaries are not copied into a new table. They are reconstructed from canonical question source identity;
  the membership order is exercise-then-question and every member carries the same combined activity revision.
- Catalog readiness is strict and exact-level. A weak skill with insufficient canonical groups becomes a coverage gap;
  it never falls back to another JLPT level.
- The current source sanitizer converts Reading HTML to safe normalized plain text for grouping/display input. Task 5
  must render that source as text unless a separately reviewed allowlist renderer is introduced.
- No AI generates runtime questions. Gemini remains narrative/editorial only.
- No seed data was added or changed in this checkpoint.

## 5. Continue with Tasks 4–8

### Task 4 — comprehension strategies in the shared engine

Implement Reading and Listening adapters/strategies around the existing `InteractiveStudyEngine`. Preserve ownership
chain `principal -> path -> activity -> membership`, immutable issue snapshots, server-only answer keys, submit
idempotency, 70% pass threshold, three attempts, and fail-three review + 60-minute cooldown. Reconstruct exercise
boundaries from the catalog and reject source-revision drift instead of silently mixing canonical versions.

Do not duplicate the Vocabulary/Kanji state machine. Add the smallest content strategy/batch seam needed. Reading and
Listening batches must not cross an exercise boundary.

### Task 5 — API and dedicated comprehension player

Add authenticated Reading/Listening endpoints, DTOs without answer leakage, Thymeleaf pages and vanilla JS tests.
Reading shows the sanitized passage and questions; Listening uses the approved audio source and does not expose a
transcript or answer before submit. Follow the approved purple/lime, container-light reference, with clear progress,
loading, empty, retry, pass, review and cooldown states. Do not reuse the textbook page.

Update roadmap links for unlocked Reading/Listening warm-up activities. Locked items must remain non-navigable.

### Task 6 — completion, summary and readiness

Include real in-path Reading/Listening first/latest/best evidence and unresolved batches. Remove the current
Smart-Test-only disclaimer when real path evidence exists, but preserve honest missing-evidence states. A high average
must never hide one unresolved comprehension skill. Ensure comprehension-only warm-up packages complete and unlock the
next package generically.

### Task 7 — Mock Test owner-contract seam

Do not implement or edit the other team's Mock Test product. Add only the owner-owned route/config contract required by
the RC plan. Validate the destination rather than inventing a URL. Keep the button disabled with a clear reason when the
contract is unavailable.

### Task 8 — hardening and final RC handoff

Run security/ownership/tampering/replay/concurrency tests, schema/entity parity, coverage audit and the complete test
matrix. Update `docs/rules-and-business-logic.md` with final accepted behavior. Produce a final handoff that lists exact
commits, API/error contracts, migration order, known data gaps, manual MySQL/browser UAT steps, and the separate manual
post-RC convergence step.

## 6. Verification evidence at `125354c`

- Task 1 focused: `JpaComprehensionExerciseCatalogTest` — 7 passed.
- Task 3 focused: `WarmUpBComposerTest` 21, `ComprehensionWarmUpPlannerTest` 14,
  `InteractiveWarmUpPersistenceTest` 3 — 38 passed.
- Enum regression: `InteractiveLearningPathServiceTest` — 19 passed.
- Full Maven suite: **479 tests, 0 failures, 0 errors, 22 skipped**. The 22 skips remain the pre-existing
  `LessonContentSeedTests` behavior.
- Frontend Node suite: **103/103 passed**.
- Speed recall rules: **4/4 passed**.
- Personalized path JS: **8/8 passed**.
- `git diff --check 954a8a6..125354c`: clean.

## 7. Copyable prompt for the next AI

```text
Continue Interactive Learning Path V2 Release Candidate work in:
C:\Users\LAPTOP LENOVO\.codex\worktrees\interactive-learning-v2\SEP490

Branch: codex/interactive-learning-v2
Start from commit: 125354c

First read AGENTS.md and:
docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-rc-30-percent-handoff.md
Then read every authoritative input listed in §2 of that handoff.

Tasks 1–3 are complete and verified. Execute Tasks 4, 5, 6, 7 and 8 of
docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md
in order. Continue automatically through Task 8; do not stop after one task and do not request intermediate approval
for decisions already fixed in the plan/spec/rules/handoff. Use TDD, commit each vertical slice separately, preserve
principal-derived ownership and server-owned scoring, run the full backend/frontend verification matrix, and write the
final RC completion handoff.

Do not merge, push, deploy, touch the user's MySQL database, implement the other team's Mock Test product, work on
W0127, or consolidate/squash migrations and seeds. The user handles database convergence separately after accepting
the RC. Preserve other contributors' code and follow team/git rules.
```
