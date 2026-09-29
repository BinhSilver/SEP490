# Handoff — Interactive Learning Path V2 Task 7 complete; Task 8 not started this run

This session was authorized to implement Task 7 only (Dialogue with trustworthy completion), continuing from
Task 6's completion handoff. **Task 7 is genuinely complete with evidence from this run.** Task 8 was **not
attempted**, per this run's explicit instruction to stop after Task 7 regardless of remaining time/effort.

## 1. Commit and working-tree state

- **Branch:** `codex/interactive-learning-v2`
- **Task 7 commit:** `98a633d` — `feat(JLAS-W0193): add Dialogue trustworthy completion` (23 files, +1498/−5).
- **BASE for Task 7:** `1b2c1fa` (Task 6 completion handoff commit). Range: `1b2c1fa..98a633d`.
- **Working tree after this handoff is added:** clean except this file itself.

## 2. Files changed in Task 7

**Dialogue (new, standalone — not built on `InteractiveStudyEngine`, see §3):**
- `entity/LearningDialogueProgress.java`, `repository/LearningDialogueProgressRepository.java` — one evidence
  row per `DIALOGUE` activity: `accumulatedSeconds`, `lastHeartbeatAt`, `lastHeartbeatToken`,
  `reachedBottomAt`, `completedAt`, `sourceRevision`, `@Version rowVersion`.
- `dto/DialogueStudyState.java`, `dto/DialogueProgressResult.java`, `dto/DialogueHeartbeatRequest.java` — no
  client-settable time, "completed", or score field anywhere in any of these.
- `service/InteractiveDialogueService.java`, `service/impl/InteractiveDialogueServiceImpl.java` — standalone
  state machine: `studyState` (read-only), `heartbeat` (server-computed capped/reset time credit, replay
  guard), `reachedBottom` (dedicated evidence event, requires prior heartbeat).
- `controller/InteractiveDialogueController.java` — `GET /study`, `POST /heartbeat`, `POST /reached-bottom`
  under `/api/learning/v2/activities/{activityId}/dialogue`.
- `controller/LearningPathPageController.java` — new `GET /learning/v2/dialogue` route.
- `templates/learning/interactive-dialogue-study.html`, `static/js/pages/interactive-dialogue-study.js`.
- `static/js/pages/interactive-learning-path.js` — `activityRow` now also maps `DIALOGUE` to its study page.
- `static/css/pages/interactive-study.css` — new Dialogue chat-script/sticky-progress-panel classes appended
  to the shared stylesheet (same file Vocabulary/Kanji/Grammar already use).

**Schema:**
- `jlas_schema.sql` — new `learning_dialogue_progress` table.
- `sql/migrations/014_interactive_dialogue_progress.sql` — the same table as an incremental migration.

**Tests (25 new Java + 17 new/changed frontend):**
- `InteractiveDialogueServiceTest` (18), `InteractiveDialogueControllerTest` (4),
  `InteractiveDialoguePersistenceTest` (3, `@DataJpaTest` H2), `interactive-dialogue-study.test.cjs` (14),
  3 new assertions in `interactive-learning-path.test.cjs`.

**Docs:** `docs/rules-and-business-logic.md` new §2.14; implementation plan Task 7 checked off; SDD ledger
(`progress.md`) has Task 7 rulings and progress appended.

**No Task 4/5/6 code was modified.** Unlike Task 6 (which found and fixed two genuine bugs in Task 5's
scope), this session found no bug in prior tasks' scope — the engine, Grammar's practice-gate pattern, and
the `pageCss` convention were all reused/followed correctly on inspection.

## 3. The service-architecture decision (and why)

Vocabulary/Kanji/Grammar all route through `InteractiveStudyEngine<T>` — a state machine shaped like
"flip-or-practice gate → issue/submit/grade → attempt cycle → cooldown/review". **Dialogue has none of that
shape**: no card, no question, no score, no pass/fail, no attempt limit. The spec is explicit that Dialogue
"là điều kiện hoàn thành, không tham gia điểm" (contributes to completion state only, never to score) — so
forcing it through the engine would have meant inventing a fake, always-trivial "micro-check" purely to reuse
the machinery, fabricating a scored artifact the spec says must not exist.

**Chose a standalone parallel service** (`InteractiveDialogueService`/`InteractiveDialogueServiceImpl`),
following the governing instructions' explicit guidance ("Do not force it into `InteractiveContentAdapter`/
`InteractiveStudyEngine`... A clean parallel service... is the right shape"). Concretely:

- Ownership/locking idioms are borrowed from Grammar's practice-gate precedent (`loadActivity` via
  `findOwnedByIdForUpdate` + activity-type rejection as `NOT_FOUND`; a dedicated progress table locked with
  `findForUpdate`; a `lockOrCreateProgress`-shaped helper that resets stale progress on a `sourceRevision`
  bump) — so the *pattern* is consistent with the rest of the codebase even though the *state machine* is new
  and Dialogue-specific.
- Nothing about Vocabulary/Kanji/Grammar's contract, tests, or the shared engine changed at all in this task
  — `InteractiveStudyEngine.java` and `InteractiveContentAdapter.java` have zero diff in this commit.
- Task 8 (package completion) should treat `activity.activityState == COMPLETED` (the terminal state Dialogue
  actually reaches) as its "Dialogue is done" signal — Dialogue never reaches `PASSED`, which is reserved for
  the engine's micro-check-passed activities.

## 4. API / schema changes

**API**, base `/api/learning/v2/activities/{activityId}/dialogue`:

| Method | Path | Purpose |
|---|---|---|
| GET | `/study` | `DialogueStudyState`: ordered script lines (role, scene, bubble side, Japanese text, translation, audio URL — only real approved fields, never fabricated) plus `requiredSeconds`(180)/`accumulatedSeconds`/`remainingSeconds`/`reachedBottom`/`completed`, all read from the persisted progress row (zeroed defaults if none exists yet or the revision has changed). Pure read — does not create a progress row. |
| POST | `/heartbeat` | Body `{heartbeatToken: string}` only. Server measures the gap since the last heartbeat with its own clock, credits `min(gap, 20)`s or resets to 0 credit above a 30s gap or with no prior heartbeat, and re-checks completion. Returns `DialogueProgressResult`. |
| POST | `/reached-bottom` | No body. Persists `reachedBottomAt` once (idempotent on repeat), requires a prior heartbeat to exist, and re-checks completion. Returns `DialogueProgressResult`. |

Page: `GET /learning/v2/dialogue?activity={id}`.

**Schema:** additive only.
- New table `learning_dialogue_progress(id, activity_item_id FK→user_learning_path_item UNIQUE, source_revision,
  accumulated_seconds, last_heartbeat_at, last_heartbeat_token, reached_bottom_at, completed_at, row_version,
  created_at, updated_at)`. One row per activity (unique on `activity_item_id`), same "canonical id, not a
  second source of truth" posture — dialogue script content itself is never copied here, only completion
  evidence.
- No changes to any existing table, entity, or CHECK constraint. `jlas_schema.sql`, the new migration, the
  entity's JPA mapping, and `InteractiveDialoguePersistenceTest` (H2, schema generated from the entity) were
  checked column-by-column to match.

## 5. How heartbeat/time/bottom-reached tampering, replay and concurrent double-completion are prevented

- **No client-supplied time, ever.** `DialogueHeartbeatRequest` has exactly one field, `heartbeatToken`
  (a `@NotBlank @Size(max=80)` string) — no duration, no elapsed-time, no timestamp field exists anywhere in
  any Dialogue request DTO. All timing math is `gapSeconds = Duration.between(persisted lastHeartbeatAt,
  LocalDateTime.now())`, computed entirely server-side. This makes "cap even a heartbeat reporting a huge
  elapsed value" true *by construction* — there is no code path where a client-reported number could reach
  `accumulatedSeconds` — rather than a defensive clamp bolted onto a trusted client value. Proven by
  `heartbeatContributionIsCappedAtTwentySecondsEvenWhenServerMeasuredGapIsLarger` and
  `gapOverThirtySecondsResetsTheStreakInsteadOfCreditingTheWholeGap` (the latter simulates a 600s
  server-measured gap and asserts exactly 0 seconds credited, not a truncated fraction of the gap).
- **Replay:** a heartbeat whose `heartbeatToken` exactly matches the last-accepted token is recognized as a
  retry of the same request and is returned unmodified without crediting time again or writing to the
  database — proven by `duplicateHeartbeatTokenIsRejectedWithoutDoubleCrediting` (asserts
  `accumulatedSeconds` unchanged and `verify(progressRepo, never()).save(any())`).
- **Bottom-reached tampering:** `/reached-bottom` is rejected with `VALIDATION_FAILED` if no
  `learning_dialogue_progress` row exists yet (i.e., no heartbeat has ever been accepted for this activity) —
  proven by `reachedBottomWithoutAnyPriorHeartbeatIsRejectedAsTampering`. Once accepted, `reachedBottomAt` is
  set exactly once and a repeat call is a no-op that does not move the timestamp — proven by
  `reachedBottomAfterHeartbeatEvidenceIsAcceptedAndIdempotent`.
- **No client `completed`/`totalSeconds` field anywhere.** `DialogueProgressResult` has exactly 7 components,
  all server-computed (`activityId, activityState, requiredSeconds, accumulatedSeconds, remainingSeconds,
  reachedBottom, completed`) — asserted via
  `progressResultNeverExposesAClientSettableCompletedOrScoreField`'s record-component-count check, the same
  pattern Task 6 used for `MicroCheckResponse.MicroCheckQuestion`.
- **Completion is AND-gated and set exactly once.** `applyCompletionIfEligible` requires both
  `accumulatedSeconds >= 180` and `reachedBottomAt != null` before setting `completedAt`, and only if
  `completedAt` is still `null` — proven by `completesOnlyWhenBothTimeAndBottomEvidenceAreSatisfied`,
  `enoughTimeWithoutBottomIsNotComplete`, `bottomReachedBeforeThreeMinutesIsNotComplete`, and
  `completionTimestampIsNeverOverwrittenByALaterHeartbeat` (a later heartbeat with plenty of time/bottom
  evidence already satisfied does not move an already-set `completedAt`).
- **IDOR:** `loadActivity` re-derives the activity from the JWT principal via `findOwnedByIdForUpdate(activityId,
  userId)` and rejects `activityType != DIALOGUE` as `NOT_FOUND` — proven by `studyStateIsOwnerScoped` and
  `vocabularyActivityIsRejectedAsNotFoundForDialogueService`. `DialogueLineDetailRepository.findAllById`/
  `ContentAudioRepository.findByOwnerTypeAndOwnerIdIn` are always called with ids from this activity's own
  `DIALOGUE_LINE` membership, never a client-supplied list — proven at the persistence layer by
  `dialogueMembershipDoesNotLeakIntoGrammarActivityInSamePath` and
  `ownerScopedActivityLookupRejectsAStrangerAsNotFound`.
- **Concurrency:** the activity row is locked `FOR UPDATE` first (via `findOwnedByIdForUpdate`, the same call
  every other Task 4-6 service makes), and the dialogue-progress row is separately locked `FOR UPDATE` via
  `findForUpdate` before any mutation — the identical two-level locking pattern the engine and Grammar's
  practice gate already use. **Honest caveat:** as with Tasks 5/6's own concurrency claims, this session did
  not write an actual multi-threaded race test — the claim rests on the lock annotations plus sequential-logic
  tests, matching the established, previously-accepted depth of concurrency verification in this codebase, not
  a stronger guarantee than that.

## 6. Test counts from this run

Focused (Surefire):
- `InteractiveDialogueServiceTest`: 18/0/0/0.
- `InteractiveDialogueControllerTest`: 4/0/0/0.
- `InteractiveDialoguePersistenceTest`: 3/0/0/0 (H2).
- Vocabulary/Kanji/Grammar regression: unaffected by this task (the shared engine and its adapters were not
  touched at all — `InteractiveStudyEngine.java`/`InteractiveContentAdapter.java` have zero diff in this
  commit), reconfirmed green as part of the full-suite run below.

Full regression (this run, offline `-o`, surefire-report aggregate — not just exit code):
- `./mvnw test` → BUILD SUCCESS. **374 tests, 0 failures, 0 errors, 22 skipped** (same 22 pre-existing skips
  as Task 4/5/6's baseline; was 349 at the Task 6 baseline — +25 from this task).
- `node --test src/test/frontend/*.test.cjs` → **81/81** (was 64/64 at the Task 6 baseline; +14 from
  `interactive-dialogue-study.test.cjs`, +3 from the roadmap Dialogue-link assertions added to
  `interactive-learning-path.test.cjs`; 64+14+3=81, all green).
- `node --test scripts/speed-recall-rules.test.mjs` → **4/4**.
- `node --test src/test/js/personalized-path.test.cjs` → **8/8**.
- `git diff --check` → clean (only CRLF/LF autocrlf advisory notices, no real whitespace errors).

## 7. Known gaps / limitations, stated honestly

- **Concurrency is lock-protected but not race-tested** (see §5's honest caveat) — same depth as Tasks 5/6's
  own concurrency claims, not a regression, but not a stronger guarantee either.
- **The "reached-bottom requires a prior heartbeat" rule is a reasonable, testable, but not literally
  DOM-verifying interpretation** of "reject bottom-reached tampering without scroll evidence." The server has
  no way to independently confirm actual browser scroll position; requiring at least one prior heartbeat is
  the closest honest proxy for "some real engagement happened" available without inventing a scroll-percentage
  telemetry protocol the spec does not ask for. Flagged as a ruling in the ledger for review, not asserted as
  airtight. The frontend mitigates the practical edge case (a very fast learner reaching the bottom before the
  first 15s heartbeat interval fires) by sending an immediate heartbeat on page load, before starting the
  periodic interval.
- **No screenshot/browser-driven UI verification.** The Dialogue page was verified by unit tests
  (`interactive-dialogue-study.test.cjs`, 14 pure render-function tests covering line/role/audio rendering,
  bubble-side mapping, percent/remaining-time math, the friendly nudge message, and the AND-gated evidence
  display) and by reusing Vocabulary/Kanji/Grammar's already-established `ilp-*` visual system (plus new,
  purple/lime-consistent chat-bubble and sticky-progress-panel classes, with `min-height:44px` touch targets
  and `:focus-visible` outlines on the audio button), but the app was not actually run in a browser this
  session — no `./mvnw spring-boot:run` + live-page screenshot was taken. This matches the depth of UI
  verification Tasks 4-6 also had.
- **No composer/Task-3 validation exercised end-to-end** against real composed dialogue content this session
  (all dialogue-line fixtures in tests are hand-built, not read from a live composed package) — same caveat
  Task 6's handoff gave for grammar examples.
- **Heartbeat cadence (15s) vs. cap (20s) vs. reset (30s) are hardcoded constants**, matching the spec's exact
  numbers (§3.5: "Client gửi heartbeat mỗi 15 giây... server chỉ chấp nhận tối đa 20 giây... quá 30 giây bắt
  đầu lại"). Not configurable via `application.yaml` — consistent with how Task 4's `PASS_THRESHOLD`/
  `MAX_ATTEMPTS`/`COOLDOWN_MINUTES` are also engine-internal constants, not externalized config.

## 8. What the user should do now

1. `./mvnw.cmd spring-boot:run` (needs local MySQL + `application-local.yaml`, or point at H2 for a quick
   look). Since this session ran offline (`-o`) against the Maven cache only, run online at least once
   afterward to confirm dependency resolution is unaffected (it was not touched).
2. If starting from a clean DB: run `jlas_schema.sql` (or apply migration
   `014_interactive_dialogue_progress.sql` on top of an existing DB through 013) and reseed via
   `db/seed_data_jlas.sql` for lesson-1 dialogue content.
3. Log in as your own account (not a seed/demo account). `learning.interactive-v2.enabled=true` locally to
   compose a V2 target package, or reuse an already-composed one.
4. Open `/learning/path/v2`. Note that in the current app state, only the **first** activity of a package
   (Vocabulary) is ever unlocked automatically — Kanji/Grammar/Dialogue only become `STUDYING` once Task 8's
   intra-package unlock logic exists (not built by any task so far; confirmed by grep — no task before this
   one sets a subsequent activity's state away from `LOCKED`). To manually exercise the Dialogue page before
   Task 8 lands, either update the activity's `activity_state` directly in the DB for testing, or wait for
   Task 8.
5. Once unlocked, open `/learning/v2/dialogue?activity={id}`: the page loads the script, sends an immediate
   heartbeat, then one every 15 seconds while the tab is visible (switch tabs to verify the interval pauses —
   no heartbeat is sent while `document.visibilityState !== 'visible'`). Scroll to the bottom of the script to
   trigger the reached-bottom event once the sentinel intersects. After 180 accumulated seconds and a
   bottom-reached event, the page shows the completion state.
6. Package-completion, popup, next-action, warm-up, summary, and certificate are **not built yet** — those are
   Task 8, 9 and 10.

## 9. Recommended continuation

Resume from this handoff and the ledger
(`.superpowers/sdd/2026-09-28-interactive-learning-path-v2-implementation/progress.md`, which has Task 7's
rulings appended) in a fresh session for **Task 8 only** (Package completion and next action), following the
same per-task discipline: read the required docs in order, TDD, focused + full regression, self-review,
commit, ledger + handoff. Task 8 needs, at minimum: (a) the intra-package activity-unlock logic that does not
exist yet for *any* content type (Vocabulary/Kanji/Grammar/Dialogue activities beyond the first in a package
are currently permanently `LOCKED` with no code path that ever changes that — this is a genuine, expected gap
Task 8 exists to fill, not a bug in Tasks 4-7), (b) a transactional gate reading Vocabulary+Kanji+Grammar's
`PASSED` state and Dialogue's `COMPLETED` state, (c) real (not hardcoded) score aggregation from
`LearningMicroCheckState.latestScore` per activity — `round((vocab + kanji + grammar) / 3)` per spec §2.2,
with Dialogue excluded from both the numerator and denominator as documented in §2.14 of
`docs/rules-and-business-logic.md`, and (d) an exactly-once reward/streak/completion-event guarantee under
retry and concurrency. Task 8 should not be compressed together with Task 9+ in the same run, for the same
honesty-over-speed reasons Tasks 5 and 6's handoffs gave for stopping after one task each.
