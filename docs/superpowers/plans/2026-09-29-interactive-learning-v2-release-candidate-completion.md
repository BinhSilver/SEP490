# Interactive Learning Path V2 — Release Candidate Completion Plan

> **Execution mode:** Continue from `a8ec6a4` on `codex/interactive-learning-v2`. Execute every task in order,
> test and commit each vertical slice, then continue automatically until the final handoff. Do not stop for
> intermediate approval when the decision is already fixed in this plan/spec/rules.

**Goal:** Close the disclosed gaps after Task 10 so the JLPT Interactive Learning Path V2 has playable
Reading/Listening warm-up activities, an honest Mock Test handoff contract, in-path comprehension evidence,
and a reviewable release-candidate package for the user's later MySQL/browser acceptance.

**Scope:** JLPT V2 only. This plan does not implement BRSE, Travel, Daily Life, W0127, the Mock Test product,
production deployment, or live-database operations.

**Architecture:** Extend V2 with `READING` and `LISTENING` activities whose members reference active canonical
`assessment_question` rows. Group questions deterministically into exercises by shared passage/audio source,
level and outcome/topic identity. Reuse the existing server-owned micro-check attempt/snapshot/score/cooldown
contract through the smallest necessary strategy/batch-partition seam. Warm-up composition persists the already
approved `0/4/6/8` band into at most one Reading and one Listening activity per day. The UI renders a dedicated
comprehension player; no whole textbook page and no runtime AI question generation.

## Authoritative inputs

Read before editing:

1. `AGENTS.md`
2. `docs/rules-and-business-logic.md`
3. `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`, especially §4 and §5.4
4. `docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md`
5. `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task9-completion-handoff.md`
6. `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task10-final-completion-handoff.md`
7. Visual references under `docs/superpowers/assets/interactive-learning-v2/`
8. Commits `2b44062`, `166ea72`, `d2c817e`, `a8ec6a4`

## Fixed business decisions

- Reading/Listening band per skill: `>70%=0`, `>50–70%=4`, `>40–50%=6`, `0–40%=8` exercises.
- Missing/insufficient evidence produces `NEEDS_VERIFICATION`; never infer 0% or weakness.
- Reading + Listening combined: at most two exercises per day and within the learner's minute budget.
- Pass threshold: `>=70%`; unanswered questions are wrong.
- Fail 1/2: return wrong targets and allow immediate practice. Fail 3: 60-minute server cooldown plus required review.
- First/latest/best remain distinct. A high overall score cannot hide an unresolved Reading or Listening activity.
- Runtime questions come only from active canonical bank rows; AI may support offline editorial drafts only.
- A Reading exercise must have a nonblank sanitized passage and valid approved questions.
- A Listening exercise must have a valid approved audio source and valid approved questions. Do not expose a
  transcript/answer before submit unless canonical policy explicitly marks it as post-answer feedback.
- No fallback to a different JLPT level. Insufficient same-level content becomes a machine-readable coverage gap.
- The existing feature flag remains `false` by default until user-run MySQL/browser acceptance succeeds.
- Continue creating normal incremental migration files if schema changes. **Do not consolidate, delete, renumber,
  or squash migrations/seeds in this plan.** The user will handle that separately after the branch is accepted.

## Baseline gate

Before Task 1, prove the checkout is the expected baseline:

```powershell
git status --short
git log -5 --oneline
.\mvnw.cmd -q test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
```

Expected baseline at `a8ec6a4`: Maven `466/0/0/22 skipped`, frontend `103/103`, speed recall `4/4`,
personalized path `8/8`. `.superpowers/` may exist as scratch and must not be committed. If other tracked changes
exist, preserve them and identify ownership before editing.

---

## Task 1 — Lock canonical comprehension readiness and grouping contract

**Purpose:** Make “one Reading/Listening exercise” deterministic before adding persistence or UI.

**Create:**

- `src/main/java/com/jlas/learning/service/ComprehensionExerciseCatalog.java`
- `src/main/java/com/jlas/learning/dto/ComprehensionExercise.java`
- `src/main/java/com/jlas/learning/dto/ComprehensionQuestionSpec.java`
- `src/main/java/com/jlas/learning/service/impl/JpaComprehensionExerciseCatalog.java`
- `src/test/java/com/jlas/learning/JpaComprehensionExerciseCatalogTest.java`

**Modify only when needed:** assessment repositories with read-only, projection-oriented queries; do not change
Assessment scoring or Smart Test ownership.

### Contract

1. Load only `assessment_question.is_active=true`, exact skill and exact JLPT level.
2. A valid MCQ has nonblank question text, 2–4 nonblank distinct options, `correct_option` in range, and no
   ambiguous normalized duplicate answer.
3. Reading groups by a stable fingerprint of sanitized `passage_html` plus level and outcome/topic identity.
4. Listening groups by normalized/validated `audio_url` plus level and outcome/topic identity.
5. Require at least two valid questions per exercise so one guess does not decide an exercise.
6. Sort exercises and questions deterministically; expose canonical question IDs and a SHA-256 source revision.
7. Keep answer data inside the server model. Public DTOs introduced later must never reuse the internal answer shape.
8. Reject/record explicit gaps: `PASSAGE_MISSING`, `AUDIO_MISSING`, `QUESTION_INVALID`,
   `QUESTION_COUNT_INSUFFICIENT`, `LEVEL_MISMATCH`, `SOURCE_AMBIGUOUS`.
9. Do not treat a lesson title or raw question count as readiness. The playable exercise contract is the gate.
10. Validate media URLs through the repository's existing safe media policy/adapter; do not fetch arbitrary URLs.

### Required tests

- Exact-level and exact-skill filtering.
- Inactive/wrong-level rows excluded.
- Same passage/audio grouped; different sources remain separate.
- Topic/outcome collision cannot merge unrelated sources.
- Invalid correct option, duplicate options and one-question groups rejected.
- Stable ordering and revision for identical canonical data.
- Changed canonical content changes revision.
- Internal answer never appears in the public-facing projection.

**Commit:** `feat(JLAS-W0193): define comprehension exercise readiness`

---

## Task 2 — Add V2 Reading/Listening persistence contracts

**Modify:**

- `src/main/java/com/jlas/learning/enums/LearningActivityType.java`
- `src/main/java/com/jlas/learning/enums/LearningMemberType.java`
- `src/main/java/com/jlas/learning/entity/LearningActivityMember.java` only if a discriminator/group field is
  proven necessary after Task 1; prefer deriving the exercise group from canonical data.
- `jlas_schema.sql`
- one new incremental migration after `015_interactive_package_completion.sql`
- persistence/repository tests under `src/test/java/com/jlas/learning/`

### Contract

- Add `READING` and `LISTENING` activity types.
- Add `COMPREHENSION_QUESTION` membership. `canonical_id` is an `assessment_question.id`; activity type determines
  Reading versus Listening.
- Membership order is deterministic and source revision is immutable for the activity snapshot.
- Extend every relevant DB CHECK constraint, JSON enum validation and Java switch exhaustively.
- Legacy rows and V1 endpoints remain readable; all additions are backward-compatible.
- Do not add a second canonical question/content table. Issued attempt snapshots remain historical evidence only.

### Required tests

- Schema/entity/H2 parity.
- Reading and Listening membership persistence/order/revision.
- Unique constraints prevent duplicate member rows.
- Legacy activity rows still load.
- Every activity-type switch has a safe explicit behavior; no accidental default to another skill.

**Commit:** `feat(JLAS-W0193): add comprehension activity persistence`

---

## Task 3 — Persist approved banding in Warm-up B

**Modify:**

- `src/main/java/com/jlas/learning/service/impl/WarmUpBComposerImpl.java`
- `src/main/java/com/jlas/learning/service/impl/ComprehensionWarmUpPlanner.java` only to replace “lesson” naming with
  exercise semantics where needed; preserve approved boundary behavior.
- `src/main/java/com/jlas/learning/service/impl/InteractiveLearningPathServiceImpl.java` only for generic activity
  ordering/unlock support.
- `src/test/java/com/jlas/learning/WarmUpBComposerTest.java`
- `src/test/java/com/jlas/learning/InteractiveWarmUpPersistenceTest.java`
- `src/test/java/com/jlas/learning/ComprehensionWarmUpPlannerTest.java`

### Composition rules

1. Read the same finalized `LearningProfileSnapshot`; never accept score/band from the browser.
2. Resolve exact-level ready exercises from `ComprehensionExerciseCatalog`.
3. Apply each skill's `0/4/6/8` count independently.
4. Missing evidence creates verification status/gap, not a weakness activity.
5. Insufficient ready exercises creates an exact coverage gap and does not duplicate one exercise to fill quota.
6. Pack at most two exercises total per day. A day may contain:
   - one Reading activity holding one or two Reading exercise groups;
   - one Listening activity holding one or two Listening exercise groups;
   - one of each;
   while the total exercise groups for that day remains `<=2`.
7. Persist canonical question memberships ordered by exercise then question. The service/player reconstructs exercise
   boundaries from the canonical fingerprint; do not encode them in client input.
8. Preserve path-wide package/item order spaces, package idempotency and existing Vocabulary/Kanji/Grammar composition.
9. Add learner-facing reason/provenance: skill, Smart Test evidence band, exercise count, level and source revision.
10. If a warm-up package contains only comprehension activities, package unlock/completion still works generically.

### Required tests

- Every boundary: missing, 0, 40, just over 40, 50, just over 50, 70, just over 70.
- Reading and Listening combined never exceed two exercise groups/day.
- Minute budget respected without dropping or duplicating exercises.
- Exact-level shortage returns gaps and writes no partial misleading quota.
- Retry composition is idempotent.
- Existing Vocabulary/Kanji/Grammar warm-up tests stay green.
- Package/item day/order constraints hold with mixed warm-up activities.

**Commit:** `feat(JLAS-W0193): compose playable comprehension warm-ups`

---

## Task 4 — Extend the shared engine with comprehension strategies

**Inspect first:**

- `InteractiveStudyEngine<T>`
- `InteractiveContentAdapter<T>`
- `InteractiveVocabularyServiceImpl`
- `InteractiveKanjiServiceImpl`
- `InteractiveGrammarServiceImpl`

**Create:**

- `InteractiveComprehensionService`
- `InteractiveComprehensionServiceImpl`
- internal Reading and Listening content strategies/adapters
- DTOs for study state, exercise source and micro-check state
- focused service/persistence tests

### Design

- Add the smallest engine seam needed to partition members by canonical exercise group rather than fixed chunks of 10.
  Default partition behavior for Vocabulary/Kanji/Grammar must remain byte-for-byte equivalent in behavior.
- Reading/Listening question specs use canonical prompt/options/answer/explanation. Do not regenerate distractors.
- `LearningQuizSession` stores immutable prompt/option order/answer/source revision per issue.
- Public issue response includes passage or audio source, question text and options, never the correct option.
- One exercise group is one score batch. Every selected exercise must pass; an average cannot hide a failed exercise.
- Preserve `>=70%`, first/latest/best, max three valid submits per cycle, duplicate-submit replay, 60-minute cooldown,
  server clock and row locking.
- Fail 1/2 returns wrong question/outcome information and allows immediate review. Fail 3 requires review after the
  failure plus cooldown before a new cycle.
- Reading review evidence: reopen the exercise and reach the end of the passage after the failed attempt.
- Listening review evidence: request/play the canonical audio after the failed attempt. Record the server-issued review
  event; do not trust client-declared duration or total seconds.
- Same idempotency/security rule as current engine: duplicate submit does not spend an attempt. If strict issue
  idempotency is added, apply and regression-test it for every content type; otherwise preserve the disclosed behavior.
- Rate-limit issue/submit using the existing V2 limiter.

### Required tests

- Owner scope principal → path → package → activity → membership/session.
- Cross-user/type/package IDs return safe not-found.
- Client cannot inject/remove question IDs or alter skill/level/score/state.
- Reading passage/audio response omits answer; Listening response omits transcript/answer before submit.
- Exact source grouping becomes batches; no batch mixes sources.
- 69% fails, 70% passes; all batches must pass.
- First/latest/best and unresolved batches update correctly.
- Duplicate submit, validation error and technical failure do not consume another attempt.
- Fail 1/2 immediate practice; fail 3 cooldown + post-failure review gate.
- Review evidence from before the failed attempt cannot unlock the new cycle.
- Concurrent submit/package refresh cannot double-consume attempts or complete twice.
- Vocabulary/Kanji/Grammar focused suites remain green after the engine seam.

**Commit:** `feat(JLAS-W0193): add comprehension study and micro-check engine`

---

## Task 5 — Build the Reading/Listening player and roadmap integration

**Create:**

- `src/main/resources/templates/learning/interactive-comprehension-study.html`
- `src/main/resources/static/js/pages/interactive-comprehension-study.js`
- `src/main/resources/static/css/pages/interactive-comprehension-study.css`
- page/controller endpoints under `/learning/v2/reading` and `/learning/v2/listening` or one type-safe route
- frontend Node tests and controller tests

**Modify:**

- `interactive-learning-path.js` for server-owned Reading/Listening links
- roadmap CSS only where the two new activity labels/states need support

### UI/UX acceptance

- Use the approved light-tech purple/lime direction, spacious and mostly borderless main layout.
- Reading: readable passage width, Japanese typography, clear question progression and no raw HTML execution.
- Listening: accessible audio controls, replay, source state and fallback message; never fabricate audio/transcript.
- Show “why this exercise” from persisted provenance.
- Show exercise count, question progress, attempt remaining, wrong target feedback, cooldown and review requirement.
- Only server eligibility opens submit/retry. No manual “complete activity” button.
- Reload resumes current exercise/batch and server state.
- Provide one primary CTA at a time. After pass, return to package/next server-owned activity.
- Keyboard navigation, visible focus, screen-reader labels, 44px targets, reduced motion and responsive layouts.
- Empty/missing-media/content-gap state is explicit and cannot be marked complete.

### Required tests

- Correct route for unlocked Reading/Listening; locked activities are not linked.
- Sanitized passage rendering and no answer leakage.
- Audio only renders for an approved URL.
- Resume, multi-exercise navigation, submit, fail, review, cooldown and pass states.
- Error/empty states and retry affordance.
- Keyboard/mobile/reduced-motion assertions at the same level as existing V2 frontend suites.

**Commit:** `feat(JLAS-W0193): add interactive comprehension player`

---

## Task 6 — Integrate comprehension completion, summary and readiness

**Modify:**

- `InteractiveLearningPathServiceImpl`
- `InteractivePathSummaryServiceImpl`
- summary DTO/template/JS
- package-completion and summary tests
- `docs/rules-and-business-logic.md`

### Rules

- Reading/Listening warm-up activities participate in their package completion gate.
- They do not change the target-package score formula `(Vocabulary + Kanji + Grammar) / 3`.
- Summary prefers in-path Reading/Listening attempt evidence when present; Smart Test evidence remains labeled as
  baseline evidence, not silently replaced or averaged.
- Unpassed comprehension batches are named unresolved dimensions.
- Do not claim `HIGH` readiness merely because Reading/Listening now exist. Keep the current conservative cap unless
  an approved readiness policy and representative evidence justify changing it.
- Certificate still depends on required GOAL packages per the approved contract; warm-up remains prerequisite evidence,
  not a new official JLPT claim.
- Path/package completion and reward/event remain exactly once under retry/concurrency.

### Required tests

- Comprehension-only and mixed warm-up packages unlock/complete correctly.
- One failed Reading/Listening batch prevents its activity/package completion.
- Target score unchanged by comprehension activity scores.
- Summary distinguishes Smart Test baseline from in-path latest evidence.
- Unresolved comprehension cannot be hidden by high Vocabulary/Kanji/Grammar results.
- Certificate wording and eligibility remain unchanged.

**Commit:** `feat(JLAS-W0193): include comprehension evidence in path completion`

---

## Task 7 — Add an owner-contract seam for Mock Test handoff

**Create:**

- a small `learning`-owned `MockTestDestinationProvider` interface and response record
- a configuration/adapter implementation that is unavailable by default
- focused provider/summary/controller/frontend tests

### Rules

- First audit the current repository again. If a real owner-provided Mock Test route/contract now exists, adapt to it
  without modifying the Mock Test module.
- If no contract exists, keep the destination unavailable and display the honest disabled state.
- A configured destination must be validated as an internal relative route or approved application origin; never allow
  an arbitrary user-provided redirect URL.
- Server determines availability and destination. Browser does not compose target URLs or trust query parameters.
- Pass only fields the owner contract explicitly accepts, such as target JLPT level. Do not leak path internals or JWT.
- Missing owner contract is `BLOCKED_INTEGRATION`, not a reason to fabricate the exam feature or claim it works.

**Commit:** `feat(JLAS-W0193): add mock-test handoff contract seam`

---

## Task 8 — Release-candidate hardening and data-readiness evidence

**Create/update:**

- read-only, reproducible audit artifact under `sql/audits/` or `scripts/` for V2 content readiness
- focused security/concurrency tests for new Reading/Listening paths
- a manual UAT checklist under `docs/superpowers/handoffs/`
- final RC completion handoff

### Audit report must separate

- active canonical questions;
- exact-level ready Reading exercises;
- exact-level ready Listening exercises;
- invalid/ambiguous question rows;
- missing passage/audio;
- insufficient question groups;
- requested versus available quota for each level/skill;
- GOAL target package coverage (Vocabulary/Kanji/Grammar/Dialogue);
- releaseable versus blocked levels, with machine-readable gap codes.

The audit artifact must be read-only. Do not insert, update, delete, seed or auto-publish content. Do not use demo/seed
counts to declare real-data readiness.

### Hardening

- Add real multi-threaded tests for at least: duplicate micro-check submit, package completion and Dialogue completion.
- Verify rate-limit boundaries with injectable clocks; keep distributed-limiter limitation explicit.
- Verify every new route is authenticated and owner-scoped under central `SecurityConfig`.
- Verify passage sanitization, safe audio URL handling and no answer-key serialization.
- Verify migration on H2 plus schema/entity parity; document the MySQL check as pending user environment if unavailable.
- Run full regression and `git diff --check`.

### Manual UAT checklist for the user

Document exact steps, expected states and URLs for:

1. Create a real V2 path from a finalized Smart Test.
2. Observe correct 0/4/6/8 comprehension allocation.
3. Complete Reading and Listening exercises, including fail 1/2 and fail 3 cooldown/review.
4. Resume after reload and verify no duplicate activities/attempts/rewards.
5. Finish target package and verify popup actions.
6. Finish path and verify summary/certificate wording.
7. Verify Mock Test button enabled only when the owner destination exists.
8. Check desktop/mobile, keyboard, focus, reduced motion and missing-media behavior.

**Commit:** `test(JLAS-W0193): harden interactive v2 release candidate`

---

## Final verification

Run focused tests after each task. At the end run:

```powershell
.\mvnw.cmd -q test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
git diff --check
git status --short
```

Parse Surefire XML for test/failure/error/skipped totals. Do not report only an exit code.

## Final handoff contents

The AI must finish all eight tasks and then create one final handoff containing:

- commit per task and complete commit range;
- API/routes/DTOs and state-machine changes;
- schema change and incremental migration order;
- Reading/Listening grouping/readiness rules and coverage gaps;
- ownership, sanitization, answer-key, idempotency, cooldown and concurrency evidence;
- focused/full test counts;
- feature-flag state and exact remaining release conditions;
- Mock Test integration status (`READY` or `BLOCKED_INTEGRATION`);
- real-DB/browser items intentionally left for the user's later acceptance;
- link to the manual UAT checklist.

Do not claim production readiness without a real MySQL audit and browser run. Do not merge/push/deploy or enable the
feature flag by default. Stop only after the final handoff has been committed.

## Explicitly outside this AI plan

- Consolidating/squashing/deleting migration files.
- Combining or rewriting seed files.
- Applying schema or seed scripts to the user's real database.
- Editing `application-local.yaml` or copying credentials.
- Merging to another branch, opening/merging a PR, deployment or production feature-flag activation.
- BRSE, Travel, Daily Life, Pomodoro/SRS expansion and AI authoring workflow.

The user will perform migration/seed consolidation separately after reviewing and accepting this release candidate.
