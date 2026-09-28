# Handoff — Interactive Learning Path V2 Task 9 complete (with one honest scope split); Task 10 not started this run

This session was authorized to implement Task 9 only (Warm-up B personalization), continuing from Task 8's
completion handoff. **Vocabulary/Kanji/Grammar warm-up composition, provenance, dynamic-package completion,
and two genuine Task 3 integration bugs are complete and verified with evidence from this run.**
**Reading/Listening banding + daily-cap/time-budget packing logic is complete and verified as a
standalone, tested component — it is not yet wired into a persisted, playable activity**, a deliberate,
disclosed scope decision explained in §5 below, not an oversight. Task 10 was **not attempted**, per this
run's explicit instruction to stop after Task 9 regardless of remaining time/effort.

## 1. Commit and working-tree state

- **Branch:** `codex/interactive-learning-v2`
- **BASE for Task 9:** `e38a36e` (Task 8 completion handoff commit).
- Commit(s) for this task are listed by content below; see `git log` for the exact hash(es) once committed
  in this session.

## 2. What Task 9 scope was completed vs. deferred, and why

**Completed and verified:**

1. Weakness evidence (Vocabulary/Kanji/Grammar) → item-scoped V2 activities, never a whole lesson.
2. Vocabulary/Kanji weak items → their own activity with membership scoped to exactly those canonical ids.
3. Grammar weak items → the real canonical grammar point, capped at 3/day, overflow spills into
   subsequent warm-up-only days.
4. Reading/Listening banding *decision logic* (0/4/6/8, missing-evidence → verification) — real, tested,
   reusing the approved V1 boundary function verbatim.
5. Reading/Listening 2/day cap + time-budget packing *logic* — real, tested, deterministic.
6. V2 has no warm-up gate/daily/lesson/stage-final quiz; legacy V1 records/endpoints are untouched and
   still exercised by the unchanged legacy test suite (verified via full regression, not assumed).
7. Provenance from weakness evidence to activity, surfaced through the API.
8. Two genuine, previously-undiscovered integration bugs in Task 3's composer, found and fixed narrowly
   with regression tests (see §9).

**Deferred, explicitly and honestly:** Reading/Listening warm-up is **not** a playable, persisted V2
activity this session. There is no `READING`/`LISTENING` value in `LearningActivityType`/
`LearningMemberType`, the `chk_learning_path_item_activity_type` CHECK constraint doesn't allow it, and
there is no Reading/Listening study/practice/micro-check page anywhere in the codebase. Building all of
that — a new content-adapter-equivalent, a media-aware practice UI, transcript/hint assistance-state
tracking (design spec §5.4), and its own schema slice — is comparable in size to Task 4 or Task 6 by
itself, not a small addition on top of Vocabulary/Kanji/Grammar. Rushing it in the time remaining risked
exactly the "claim 90% without real evidence" outcome the governing instructions explicitly warn against.
Instead, the *decision logic* (which the design spec actually specifies in detail — the banding table and
the packing rules) is built as real, deterministic, fully boundary-tested code
(`ComprehensionWarmUpPlanner`), ready for a future session to wire into a new activity type without
redesigning the rules themselves.

**Bottom line on the "90%" figure:** Task 9 is genuinely ~90% for Vocabulary/Kanji/Grammar/legacy-compat/
provenance, and ~50% for Reading/Listening (decision logic done; persistence/UI not). This handoff states
that split explicitly rather than rounding up.

## 3. Files changed in Task 9

**Backend (new):**
- `service/WarmUpBComposer.java` — interface, one method (`composeWarmUp(path, profile)`).
- `service/impl/WarmUpBComposerImpl.java` (~310 lines) — the actual weakness-evidence-to-membership
  composer for Vocabulary/Kanji/Grammar.
- `service/impl/ComprehensionWarmUpPlanner.java` — pure, static Reading/Listening banding + daily packing.
  Not called from any composer/controller yet.

**Backend (changed):**
- `repository/LearningPathPackageRepository.java` — new `existsByLearningPathIdAndPhase(pathId, phase)`.
- `dto/InteractiveLearningActivityResponse.java` — additive `reasonCode`/`reason` fields.
- `service/impl/InteractiveLearningPathServiceImpl.java` — `refresh(...)` branches GOAL_LEARNING/WARM_UP
  completion gates; new private `allWarmUpActivitiesDone`; `mapPackage` passes the two new DTO fields
  through from the entity's existing `reasonCode`/`reason` columns.
- `service/impl/InteractivePackageComposerImpl.java` — two bug fixes (§9): idempotency check scoped to
  `GOAL_LEARNING` phase; package `order_index` continues after any pre-existing packages instead of
  restarting at 1; first-package `AVAILABLE` status now also accounts for pre-existing (warm-up) packages.
- `service/impl/LearningServiceImpl.java` — new optional-autowired `warmUpBComposer` field; the legacy
  per-item persistence loop (used only when V2 is *disabled*, or for the parts V2 doesn't yet compose) now
  also skips `WARM_UP`-phase scheduled resources when V2 is enabled (previously only `GOAL_LEARNING` was
  skipped, so warm-up silently stayed on the V1 model even under V2 — closed now); `warmUpBComposer.composeWarmUp(...)`
  is invoked before the target composer, non-fatally — missing/insufficient evidence never blocks path
  creation, matching design spec §5.1 ("không đủ evidence tạo trạng thái NEEDS_VERIFICATION").

**No schema change.** Warm-up B reuses `learning_path_package`, `user_learning_path_item` (including its
pre-existing, previously-V2-unused `reason`/`reason_code` columns), and `learning_activity_member` exactly
as they already exist. No new migration file.

**Tests (36 new + 1 existing updated for a legitimate behavior change):**
- `WarmUpBComposerTest` (16) — guards, no-evidence, idempotent retry, Vocabulary anchor resolution +
  missing-anchor gap, level-bound exclusion, Kanji single-character resolution + multi-character gap,
  Grammar weak/not-weak threshold, Grammar 3-per-day overflow into a second day, combined day-1 activity
  ordering, cross-evidence dedupe, day/order-index continuation after existing path content, first-package
  AVAILABLE-vs-LOCKED.
- `ComprehensionWarmUpPlannerTest` (14) — all five banding boundaries (exactly 100/71/70/50/40/0%),
  missing-evidence-is-not-0%, 2/day cap, interleaving, minutes-per-day budget below the hard cap, an
  oversized single lesson still taking its own day, zero-input, determinism, invalid-argument guards.
- `InteractiveWarmUpPersistenceTest` (2, H2) — a warm-up package/activity/membership round-trips with a
  null source lesson and carries provenance; two null-source-lesson warm-up packages on the same path
  coexist (proves the `(path, source_lesson_id)` unique index's NULL-distinct behavior on H2 too).
- `InteractiveLearningPathServiceTest` (+3) — warm-up package completes when its own (dynamic) activity set
  is all `PASSED`; does not complete while one is still in progress; a single-activity warm-up package
  completes and unlocks the next (target) package via the existing, unmodified mechanism.
- `InteractivePackageComposerTest` (+1 new regression case, +1 existing case updated) — the new
  `composesTargetPackagesEvenWhenWarmUpPackagesAlreadyExistForThePath` reproduces and proves the fix for
  the idempotency-check bug (§9); `retryReturnsExistingPackagesWithoutRecomposing` was updated to stub the
  now phase-scoped `existsByLearningPathIdAndPhase` instead of the old unscoped `existsByLearningPathId` —
  its assertions (published, count, no re-composition) are unchanged, only the mock setup matches the new,
  correct method being called.

**Docs:** `docs/rules-and-business-logic.md` new §2.16; implementation plan Task 9 updated with the exact
scope split; SDD ledger (`progress.md`) has Task 9 rulings and progress appended.

## 4. How weakness evidence is read and converted into activities

Read exclusively through the **existing** `LearningProfileSource.loadForUser(userId, assessmentAttemptId)`
— Smart Test's own read API (`SmartTestLearningProfileSource`, unmodified). `WarmUpBComposerImpl` never
writes a new query against Smart Test's tables and never accepts weakness data from request JSON; the
`LearningProfileSnapshot` passed to `composeWarmUp` is the exact same server-derived snapshot
`LearningServiceImpl.createPath` already builds for V1 selection.

- **Vocabulary**: `WeaknessEvidence` rows with `skill=VOCABULARY` and `wrongCount>=1` (already guaranteed
  by `SmartTestLearningProfileSource.itemEvidence`'s own pre-filter — `WeakTagPolicy.isWeak` is still
  called for uniformity/defense-in-depth). `evidence.vocabularyEntryId()` is used directly as the
  `VOCABULARY_ENTRY` membership canonical id, after confirming the entry is still `PUBLISHED`.
- **Kanji**: same pre-filtered weak-evidence source, but the canonical id is resolved differently — the
  evidence's lexeme (parsed from `canonicalId = "KANJI:<lexeme>"`) is looked up via
  `KanjiRepository.findByCharacter(lexeme)`. `WeaknessEvidence.vocabularyEntryId()` is **not** used for
  Kanji (it anchors the *containing word's lesson* in V1's model, not a `kanji.id`). A multi-character
  lexeme — or any lookup miss — is recorded as a `CONTENT_MAPPING_PENDING` gap and skipped, never guessed.
- **Grammar**: `evidence.grammarPointId()` (parsed from `canonicalId = "GP:<id>"`), but — unlike Vocabulary/
  Kanji — Grammar's raw evidence (`SmartTestLearningProfileSource.grammarEvidence`) is **not** pre-filtered
  by wrong ratio, so `WeakTagPolicy.isWeak(evidence)` is applied here to exclude grammar points the learner
  actually knows.
- **All three** additionally apply the existing level-bound rule (rules doc §2.2: an item above the
  learner's assessed level is "never learned," belongs to GOAL not warm-up) and dedupe by resolved
  canonical id (not by raw evidence key — two evidence rows resolving to the same canonical id produce one
  membership row).

**Reuse-vs-new-code decision:** Vocabulary/Kanji/Grammar warm-up activities are ordinary `WARM_UP`-phase
`UserLearningPathItem` rows carrying ordinary `learning_activity_member` rows of the existing member types
(`VOCABULARY_ENTRY`/`KANJI`/`GRAMMAR_POINT`). The Task 4/5/6 `InteractiveStudyEngine` and pages needed
**zero code changes** — they already operate purely on an activity's own membership, regardless of how it
was composed. This is the "own composition step feeding a smaller membership set into the existing content
adapters" option from the governing instructions, chosen over copying or forking any state machine.

## 5. Reading/Listening banding logic and why it isn't wired in yet

`ComprehensionWarmUpPlanner.band(SkillScore)` reuses `WeakTagPolicy.comprehensionLessonCount(SkillScore)`
**verbatim** — the exact same `0/4/6/8` function V1 already ships (rules doc §5 "P3"): `p>70 → 0`,
`(50,70] → 4`, `(40,50] → 6`, `[0,40] → 8`, and `assessedCount<=0 → -1` (insufficient evidence). A `-1`
result becomes `ComprehensionBand.needsVerification() == true` with `lessonCount() == 0` — a distinct,
explicit outcome, never silently collapsed into "0% correct." All five boundary values are tested exactly
(100%, 71%, 70%, 50%, 40%, 0%) plus the missing-evidence case.

`ComprehensionWarmUpPlanner.packIntoDays(readingCount, listeningCount, maxPerDay, minutesPerDay,
minutesPerLesson)` packs the banded counts into days, each capped at `maxPerDay` (2, per design spec §5.4)
**and** at `minutesPerDay / minutesPerLesson` when a budget is given, interleaving Reading/Listening within
a day rather than front-loading one skill. A single lesson longer than the remaining budget still takes its
own day (matching the existing V1 precedent in rules doc §2.4), never split. Deterministic — no randomness
anywhere in the packing order, tested by asserting two calls with identical inputs return identical output.

**Why this stops at logic and doesn't reach a persisted activity:** `LearningActivityType` and
`LearningMemberType` have no `READING`/`LISTENING` value; the `chk_learning_path_item_activity_type` CHECK
constraint would need widening; there is no Reading/Listening study/practice/micro-check page, adapter, or
media-handling code anywhere in the codebase to reuse (unlike Vocabulary/Kanji/Grammar, where the Task 4/5/6
engine already existed). Building a genuine, tested Reading/Listening activity type — including the
"transcript/hint assistance-state" tracking design spec §5.4 explicitly calls for, and the media-failure
vs. wrong-answer distinction it also calls for — is a full new vertical slice, not a small extension. I
judged that attempting it in the time remaining would have meant either a shallow, undertested
implementation (violating the TDD/verification discipline every prior task in this series held to) or
running out of budget mid-slice with an inconsistent partial schema change. The decision logic is delivered
and tested so a future session inherits real design work, not a blank page.

## 6. Grammar's 3/day cap and multi-day overflow

Deduped, ascending-sorted weak grammar point ids are chunked into groups of `MAX_GRAMMAR_POINTS_PER_DAY = 3`
(design spec §5.3). The first chunk joins day 1's package alongside Vocabulary/Kanji (if either has weak
items); any remaining chunks each become their own subsequent `WARM_UP`-only package, `day_number`
incrementing, `status = LOCKED` (only day 1's package can be the very-first-in-path `AVAILABLE` one).
Tested by `moreThanThreeWeakGrammarPointsOverflowsIntoASecondDay` (5 weak points → day 1 gets 3, day 2 gets
2, in two separate packages with the correct day numbers and membership-row counts).

## 7. Provenance: how it's stored and surfaced

Stored using the **pre-existing** `user_learning_path_item.reason` (`TEXT`) and `reason_code`
(`VARCHAR(50)`) columns — on the table since JLAS-W0108, simply never populated for V2 activities before
this task (V2's `GOAL_LEARNING` activities, composed from published curriculum rather than weakness
evidence, correctly leave them `null`). `WarmUpBComposerImpl` sets `reasonCode` to the existing V1 codes
(`WEAK_VOCAB_ITEM`/`WEAK_KANJI_ITEM`/`WEAK_GRAMMAR_POINT` — same table as rules doc §2.2, for continuity)
and a Vietnamese `reason` sentence naming the item count, matching the design spec's own example wording
("Bạn gặp khó khăn với 6 từ này trong Smart Test..."). This is an activity-level explanation (one reason
per activity, e.g. "6 words"), matching the design spec's own example, not a separate provenance record per
individual membership item.

Surfaced via two new, additive fields on `InteractiveLearningActivityResponse` (`reasonCode`, `reason`;
both `null` for non-warm-up activities) — the one and only construction site of that record
(`InteractiveLearningPathServiceImpl.mapPackage`) was updated, so every existing consumer of the roadmap/
package-detail API now also receives this data at no extra cost. **No frontend UI change was made to
display it this session** — the existing roadmap page renders activity rows generically and doesn't yet
surface `reason` text; that's presentation polish left for whenever the UI work for Warm-up B's own roadmap
section happens (likely alongside the Reading/Listening UI in a future session).

## 8. Warm-up package completion: dynamic activity set

`InteractiveLearningPathServiceImpl.refresh(...)` now branches on `learningPackage.getPhase()`:
`GOAL_LEARNING` packages keep Task 8's exact `allRequiredActivitiesDone` (fixed four: Vocabulary+Kanji+
Grammar+Dialogue, each its own terminal state). `WARM_UP` packages use a new `allWarmUpActivitiesDone`,
which only requires every activity the package **actually has** — any non-empty subset of Vocabulary/Kanji/
Grammar; Warm-up B never schedules Dialogue — to reach `PASSED`. This was explicitly anticipated by Task
8's own code comment: *"Only GOAL_LEARNING packages are eligible (defensive: warm-up packages, when Task 9
adds them, are not forced through this four-activity gate)."* Closing exactly that flagged gap.

Score aggregation, exactly-once reward/streak (via the same pessimistic-lock + `status != COMPLETED` guard
Task 8 built), and next-package unlock needed **zero changes** — they were already phase-agnostic (score
aggregation already skips any activity type without a scored quiz type; reward/unlock already operate on
the generic `LearningPathPackage`/`UserLearningPathItem` shape, not anything GOAL_LEARNING-specific).

## 9. Two genuine integration bugs found and fixed (Task 3 scope)

While wiring `WarmUpBComposerImpl` to run before `InteractivePackageComposerImpl` in the same
`createPath` call, two real, previously-latent bugs surfaced in Task 3's composer:

1. **Idempotency check was unscoped.** `packages.existsByLearningPathId(path.getId())` returns true the
   moment *any* package exists for the path — including a `WARM_UP` one. Once Warm-up B started composing
   first, this made the target composer wrongly believe target packages already existed and **silently
   compose zero of them**, for every single V2 path that has any warm-up content (the overwhelmingly common
   case, not an edge case). Fixed to `existsByLearningPathIdAndPhase(path.getId(), "GOAL_LEARNING")`.
2. **Package `order_index` numbering unconditionally restarted at 1** every call, assuming it was always
   the first composer to run on an empty package list — true before Task 9, false the moment a warm-up
   package already occupies `order_index = 1`. `uk_learning_package_path_order` (unique per path) would
   have rejected the second package with the same index. Fixed to continue from
   `packages.findByLearningPathIdOrderByOrderIndex(...)`'s existing max `order_index`.

Both were caught by tracing through what `existsByLearningPathId`/`order_index` assignment would do once
warm-up genuinely ran first (not by an existing test — no test previously exercised "warm-up composes
before target," since warm-up composition didn't exist before this task) and are now covered by a new
regression test, `InteractivePackageComposerTest#composesTargetPackagesEvenWhenWarmUpPackagesAlreadyExistForThePath`.
Left unfixed, V2 would have shipped completely broken for any learner with warm-up content — this was the
most consequential single finding of this task, more so than any Warm-up B code itself.

A third, self-caught bug (not a Task 3 issue — a bug in my own first draft of `WarmUpBComposerImpl`):
package `order_index` and activity `order_index` are two independent, path-wide numbering spaces with
separate unique constraints, and an early draft conflated them (using the item-order max as the
package-order base, and resetting the activity-order counter to 0 per package instead of threading it
across every package a single call creates). Caught during self-review before any test was written against
the buggy version; fixed by re-deriving both counters from their own correct repository max queries and
threading them through the whole method. `InteractiveWarmUpPersistenceTest` is the test that would have
caught a shipped regression here (Mockito-based unit tests don't enforce unique constraints; H2 does).

## 10. Confirmation that legacy V1 compatibility was verified, not assumed

- The only production-code change touching the legacy path is one condition in
  `LearningServiceImpl.createPath`'s per-item persistence loop, and it is gated entirely inside
  `interactiveV2Enabled()` — when V2 is disabled (the shipped default), this code path is byte-for-byte
  unchanged.
- The full backend regression suite (423 tests, including `LearningPathApiTest`, `LearningQuizAndGateTest`,
  `StageFinalQuizTest`, `LearningPathRollbackTest`, `LearningPathConcurrencyTest` and every other legacy V1
  integration/unit test) was run and passed with **zero** failures and the exact expected count (387
  baseline + 36 new Task 9 tests = 423), meaning no legacy test needed a single assertion changed.
- No legacy V1 table, entity, repository method, or endpoint was renamed, deleted, or had its shape changed.

## 11. API / schema changes

**API:** `InteractiveLearningActivityResponse` gained two additive, nullable fields:

| Field | Meaning |
|---|---|
| `reasonCode` | Warm-up provenance code (`WEAK_VOCAB_ITEM`/`WEAK_KANJI_ITEM`/`WEAK_GRAMMAR_POINT`); `null` for GOAL_LEARNING activities. |
| `reason` | Human-readable Vietnamese explanation naming the item count; `null` for GOAL_LEARNING activities. |

No new REST endpoint. `GET /api/learning/v2/packages/{packageId}` and `GET /api/learning/v2/paths/current`
now also correctly complete `WARM_UP` packages (previously they silently never would have, since Task 8's
gate was GOAL_LEARNING-only and Task 9 is what actually starts producing WARM_UP packages).

**Schema:** none. Every table/column Task 9 uses already existed (`learning_path_package.source_lesson_id`
nullable, `phase` free-text `'WARM_UP'`/`'GOAL_LEARNING'`, `user_learning_path_item.reason`/`reason_code`,
`learning_activity_member`'s existing member types). No migration file was added.

## 12. Exact test counts from this run

```
.\mvnw.cmd -q test        → 423 run / 0 fail / 0 error / 22 skipped
                             (Task 8 baseline: 387/0/0/22 — +36 new, all passing, zero regressions)
node --test src/test/frontend/*.test.cjs        → 90/90  (unchanged — no frontend files touched)
node --test scripts/speed-recall-rules.test.mjs → 4/4
node --test src/test/js/personalized-path.test.cjs → 8/8
git diff --check → clean (only CRLF/LF autocrlf advisory notices)
```

New test breakdown: `WarmUpBComposerTest` (16), `ComprehensionWarmUpPlannerTest` (14),
`InteractiveWarmUpPersistenceTest` (2, H2), `InteractiveLearningPathServiceTest` (+3),
`InteractivePackageComposerTest` (+1 new regression case).

## 13. Known gaps / limitations, stated honestly

- **Reading/Listening is not a playable persisted activity** — the single largest deferred item, explained
  in full in §5. Not a hidden gap: it's the headline of this handoff.
- **No live end-to-end run** of `createPath` with V2 enabled and real weak evidence — same caveat every
  prior task's handoff gave for the composer/service layer; verified by search that no existing test in the
  repo unit-tests `createPath`/`createFromSurvey` with V2 enabled at all (before or after this task), so
  this isn't a regression in verification depth, just an unaddressed pre-existing gap in the harness.
- **The Kanji single-character-lexeme assumption** (KANJI-skill Smart Test questions always tag exactly one
  kanji character, never a multi-character word) was reasoned from `SmartTestQuestionSelector.lexeme` code
  and seed data shape, not verified against live production data (per the "verify before asserting" memory
  rule's spirit — this session had no access to a live DB to check).
- **Per-item time estimates used for packing** (`MINUTES_PER_VOCAB_ITEM=1`, `MINUTES_PER_KANJI_ITEM=1`,
  `MINUTES_PER_GRAMMAR_POINT=5`, and the caller-supplied `minutesPerLesson` for Reading/Listening) are
  documented assumptions, not spec-derived numbers, flagged for product/business review — same honesty
  posture as Task 8's `PACKAGE_COMPLETION_CREDIT` flag.
- **No frontend UI change** to display the new `reasonCode`/`reason` provenance fields — they are present
  in the API response but not yet rendered anywhere. Left for whenever Warm-up B's own roadmap-page
  presentation is built.
- Concurrency for warm-up package completion relies on the same pessimistic-lock mechanism Task 8 built and
  verified with sequential-logic tests, not an actual multi-threaded race test — identical, disclosed depth
  to every prior task in this series.

## 14. What the user should do now

1. `./mvnw.cmd spring-boot:run` (needs local MySQL + `application-local.yaml`, or H2 for a quick look). This
   session ran offline (`-o`) against the Maven cache only.
2. No schema/migration change this task — a clean DB from `jlas_schema.sql` (through migration 015) already
   supports everything Task 9 added.
3. Set `learning.interactive-v2.enabled=true` locally, log in as your own account (not seed/demo), and
   create/reuse a V2 path from a Smart Test attempt that has some genuinely weak Vocabulary/Kanji/Grammar
   items (i.e., not a clean pass — Warm-up B composes nothing when the learner passed their current level,
   which is correct, not a bug).
4. Open `/learning/path/v2`. If any warm-up packages were composed, the very first package in the roadmap
   should be a `WARM_UP`-phase one (day 1), `AVAILABLE`; the first target package should now start `LOCKED`
   instead of also being `AVAILABLE` (this is the fix from §9, item 1/2 — previously target packages weren't
   composed at all when warm-up existed, so this is new, correct behavior to specifically check).
5. Start the warm-up package, study/check its Vocabulary/Kanji/Grammar activities (same pages as target
   packages — `/learning/v2/vocabulary`, `/learning/v2/kanji`, `/learning/v2/grammar`, unchanged) — passing
   all of a warm-up package's own activities should complete it and unlock the next package (either another
   warm-up day, if >3 weak grammar points existed, or the first target package once warm-up is fully done).
6. Reading/Listening warm-up, the real summary content, and the certificate flow are **not built yet** —
   Reading/Listening decision logic exists (`ComprehensionWarmUpPlanner`) but nothing renders it; summary/
   certificate are Task 10.

## 15. Recommended continuation

Resume from this handoff and the ledger
(`.superpowers/sdd/2026-09-28-interactive-learning-path-v2-implementation/progress.md`, which has Task 9's
rulings appended) in a fresh session for **Task 10 only** (Summary, certificate and release hardening),
following the same per-task discipline: read the required docs in order, TDD, focused + full regression,
self-review, commit, ledger + handoff. Before starting Task 10's own listed scope, that session should
decide explicitly whether to also pick up Reading/Listening's persisted-activity slice (deferred here) as
part of "release hardening," or treat it as a genuinely separate Task 11 — the governing instructions for
this run didn't ask me to make that call, so I'm surfacing it rather than deciding it silently.
