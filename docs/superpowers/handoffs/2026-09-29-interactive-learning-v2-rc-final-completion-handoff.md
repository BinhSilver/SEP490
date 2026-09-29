# Interactive Learning Path V2 — RC final completion handoff [JLAS-W0193]

> This is the last document in the whole "Interactive Learning Path V2" effort's RC plan
> (`docs/superpowers/plans/2026-09-29-interactive-learning-v2-release-candidate-completion.md`). RC Tasks 1-7
> were completed and handed off in earlier sessions; this document closes RC Task 8 (release-candidate
> hardening and data-readiness evidence) and stands alone as "what the whole RC shipped" per the plan's own
> "Final handoff contents" section — read it together with, not instead of,
> `docs/rules-and-business-logic.md` §2.11-§2.19 (the living spec) for implementation detail below the
> summary level.

## 1. Commit range and commit-per-task

Baseline before RC Task 1: `a8ec6a4` (the original V2 plan's Task 10 final handoff commit).

| Task | Commit | Subject |
|---|---|---|
| RC Task 1 | `a003130` | `feat(JLAS-W0193): define comprehension exercise readiness` |
| RC Task 2 | `6cfec43` | `feat(JLAS-W0193): add comprehension activity persistence` |
| RC Task 3 | `125354c` | `feat(JLAS-W0193): compose playable comprehension warm-ups` |
| — | `f48801d` | `docs(JLAS-W0193): hand off RC after comprehension composition` |
| RC Task 4 | `cfd5aac` | `feat(JLAS-W0193): add comprehension study and micro-check engine` |
| — | `3ca2460` | `docs(JLAS-W0193): hand off RC after comprehension engine` |
| RC Task 5 | `1a4dcac` | `feat(JLAS-W0193): add interactive comprehension player` |
| RC Task 6 | `af5babf` | `feat(JLAS-W0193): include comprehension evidence in path completion` |
| RC Task 7 | `872dbc5` | `feat(JLAS-W0193): add mock-test handoff contract seam` |
| RC Task 8 | *(this session's commit — see below)* | `test(JLAS-W0193): harden interactive v2 release candidate` |

Verified directly against `git log --oneline a8ec6a4..872dbc5` at the start of this session; HEAD matched the
dispatcher's expected `872dbc5` with a clean working tree before any RC Task 8 work began.

## 2. API/routes/DTOs and state-machine changes across the whole RC

All of the below sit behind `learning.interactive-v2.enabled` (default `false`) and behind Spring Security's
`.anyRequest().authenticated()` catch-all (re-verified this session — see §5).

- **Reading/Listening (RC Tasks 1-5):**
  `ComprehensionExerciseCatalog`/`JpaComprehensionExerciseCatalog` (readiness/grouping, read-only over
  `assessment_question`), `InteractiveComprehensionService`/`InteractiveComprehensionServiceImpl`
  (study/issue/submit/state/review, delegating to the shared `InteractiveStudyEngine<ComprehensionContent>`
  via `AssessmentComprehensionContentAdapter`), `InteractiveComprehensionController` exposing
  `GET/POST /api/learning/v2/activities/{id}/{reading|listening}/**` (study, micro-check issue/submit/state,
  review-evidence, complete-review), pages `GET /learning/v2/reading|listening?activity={id}`.
- **Dialogue (RC Task 7 of the original plan, referenced here since Tasks 5-7 hardening covers it):**
  `InteractiveDialogueService`/`InteractiveDialogueServiceImpl`, a standalone server-timed state machine (not
  built on `InteractiveStudyEngine`), `POST .../dialogue/heartbeat` and `.../dialogue/reached-bottom`, page
  `GET /learning/v2/dialogue?activity={id}`.
- **Path completion/summary (RC Task 6):** no new REST endpoint — `GET /api/learning/v2/packages/{id}` and
  `GET /api/learning/v2/paths/current` now also perform the idempotent completion/unlock transition inline;
  `InteractivePathSummaryResponse` gained `comprehensionInPath` (in-path Reading/Listening evidence, kept
  strictly separate from the Smart-Test-baseline `comprehension` field).
- **Mock Test seam (RC Task 7):** `MockTestDestinationProvider`/`ConfiguredMockTestDestinationProvider`
  (`com.jlas.learning.service`), consumed only by `InteractivePathSummaryServiceImpl.summary()` and surfaced as
  `InteractivePathSummaryResponse.mockTest`; no new controller route (it rides the existing summary API).
- **State machines:** every Vocabulary/Kanji/Grammar/Reading/Listening activity shares one state machine in
  `InteractiveStudyEngine<T>` (`LOCKED → STUDYING → CHECK_READY → PASSED`, or `→ COOLDOWN` after a 3rd failure
  in a cycle). Dialogue has its own two-flag state machine (`accumulatedSeconds >= 180` AND
  `reachedBottomAt != null` ⇒ `COMPLETED`). Package completion is a third, package-level state machine
  (`AVAILABLE/IN_PROGRESS → COMPLETED`, cascading unlock of the next package) layered on top of both.

## 3. Schema change and incremental migration order

`sql/migrations/010` through `sql/migrations/017` cover the whole V2+RC effort; the RC-specific ones are:

| Migration | RC Task | Change |
|---|---|---|
| `011_interactive_package_membership.sql` | (pre-RC, original plan) | `learning_activity_member` |
| `012_interactive_study_and_check.sql` | (pre-RC) | study/micro-check tables |
| `013_interactive_grammar_practice.sql` | (pre-RC) | `learning_grammar_practice_progress` + widened `chk_quiz_session_type` (Kanji/Grammar) |
| `014_interactive_dialogue_progress.sql` | (pre-RC) | `learning_dialogue_progress` |
| `015_interactive_package_completion.sql` | (pre-RC) | `learning_path_package.score_percent` |
| `016_interactive_comprehension_activities.sql` | RC Task 2 | widens `chk_learning_path_item_activity_type` (+`READING`,`LISTENING`) and `chk_learning_activity_member_type` (+`COMPREHENSION_QUESTION`) |
| `017_interactive_comprehension_quiz_type.sql` | RC Task 6 | widens `chk_quiz_session_type` (+`READING_MICRO_CHECK`,`LISTENING_MICRO_CHECK`) — RC Task 2's migration 016 had widened activity/member type but missed this constraint |

RC Task 8 added **no new migration** (a read-only hardening/audit pass does not change schema). All of
016/017 are already mirrored into `jlas_schema.sql` per this project's convention (edit the canonical schema
file directly, tag with the ticket); re-verified this session that `chk_learning_path_item_activity_type`,
`chk_learning_activity_member_type`, and `chk_quiz_session_type` in `jlas_schema.sql` list exactly the same
values as `LearningActivityType`/`LearningMemberType` and every `InteractiveStudyEngine`-issued `quizType`
respectively (see §4, "H2/schema parity").

## 4. Reading/Listening grouping/readiness rules and coverage gaps

Rules (unchanged by this hardening pass, re-verified): exact skill + exact JLPT level only, no level fallback;
active canonical `assessment_question` rows only; a valid MCQ needs 2-4 distinct non-blank options and
`correct_option` in range; grouped into one exercise by (skill, level, outcome/topic identity, sanitized
passage-or-safe-audio); at least 2 valid questions per group to count as one playable exercise; gap codes
`PASSAGE_MISSING`/`AUDIO_MISSING`/`QUESTION_INVALID`/`QUESTION_COUNT_INSUFFICIENT`/`LEVEL_MISMATCH`/
`SOURCE_AMBIGUOUS`.

**New read-only audit artifact:** `sql/audits/2026-09-29-interactive-v2-content-readiness-audit.sql` — seven
SELECT-only sections covering exactly the plan's required categories: active canonical questions;
invalid/ambiguous rows; missing passage/missing-or-unsafe audio; exact-level ready Reading/Listening exercises
plus insufficient (single-question) groups; requested (worst-case 8) vs. available quota per level/skill; GOAL
target package coverage (published Vocabulary/Kanji/Grammar/Dialogue content per level); and a final
releasable-vs-blocked rollup with machine-readable gap codes (`QUOTA_AT_RISK_READING`,
`QUOTA_AT_RISK_LISTENING`, `GOAL_TARGET_CONTENT_MISSING`, `RELEASABLE`).

**Honest limitation, stated plainly:** this worktree has no `application-local.yaml` and no reachable MySQL
server, and touching the user's real database is explicitly out of scope for the whole RC effort. **This audit
script has NOT been run against any real content database and this handoff makes no claim about real-data
coverage at any level.** What was actually done: every one of the script's 7 sections was smoke-tested
end-to-end against a throwaway H2 instance in `MODE=MySQL` compatibility, loaded with a hand-built minimal
schema and a handful of synthetic rows chosen to exercise every branch — this caught and fixed one real
portability bug (H2 rejects `SUM(<boolean expression>)`/`bool + bool` without an explicit
`CASE WHEN ... THEN 1 ELSE 0 END`, unlike MySQL's implicit boolean-to-integer coercion; every aggregate in the
script now uses the explicit form). This is evidence the SQL is well-formed and internally consistent with the
Java readiness rules — it is not evidence about what content actually exists in any real database. **The user
must run this script against their own real MySQL database before trusting any level's `RELEASABLE` verdict.**

## 5. Ownership, sanitization, answer-key, idempotency, cooldown, and concurrency evidence

### 5a. Real multi-threaded concurrency evidence (new this session)

Every prior RC task in this series disclosed "pessimistic-locked, proven by sequential-call tests" as its own
stated limit for concurrency. RC Task 8 closes that for the three cases the plan named, with real H2-backed
Spring beans (no mocked repositories) and genuine thread overlap forced by a `CountDownLatch` barrier
(`ready`/`go`, both threads started and made to wait before releasing them simultaneously) — not simulated,
not annotation-only:

- **Duplicate micro-check submit** — `InteractiveComprehensionConcurrencyTest`
  (`concurrentDuplicateSubmitOfTheSameAttemptSpendsExactlyOneAttempt`): two threads call
  `InteractiveComprehensionServiceImpl.submitMicroCheck` with the *same* issued `attemptId` for a real Reading
  activity backed by two persisted `assessment_question` rows. Result: both calls return, neither throws;
  `LearningMicroCheckState.attemptsUsed == 1` (not 2) after both complete, and exactly one
  `SUBMITTED`+`passed=true` `LearningQuizSession` row exists — proving the pessimistic write lock on the
  activity row (`UserLearningPathItemRepository#findOwnedByIdForUpdate`) genuinely serializes the two racing
  transactions rather than merely being present in the code.
- **Package completion** — `InteractivePackageCompletionConcurrencyTest`
  (`concurrentPackageDetailReadsCompleteThePackageExactlyOnce`): two threads call
  `InteractiveLearningPathServiceImpl.packageDetail` for the same already-eligible package (all four
  Vocabulary/Kanji/Grammar/Dialogue activities already terminal, three real passing `LearningQuizSession`
  rows). Result: exactly one of the two responses has `justCompleted() == true`; the persisted package ends
  `COMPLETED` with a real (non-null, >0) computed score; the next package is unlocked to `AVAILABLE` exactly
  once — proving `LearningPathPackageRepository#findOwnedByIdForUpdate` plus the `status != COMPLETED` guard
  is what actually prevents a double-completion race, not just sequential test logic asserting it once.
- **Dialogue completion** — `InteractiveDialogueConcurrencyTest`
  (`concurrentHeartbeatsCrossingTheThresholdCreditBothAndCompleteExactlyOnce`): a Dialogue activity seeded at
  170/180 accumulated seconds with reached-bottom evidence already present; two threads send two *different*
  heartbeats (distinct tokens, so neither is treated as a replay) concurrently. Result: both heartbeats'
  time contributions are credited (final `accumulatedSeconds >= 180` — no lost update under real concurrency),
  `completedAt` is set exactly once, and the activity ends `COMPLETED` — proving the activity row's
  `FOR UPDATE` lock (acquired at the very start of `heartbeat()`) is what actually serializes the two calls.

All three tests are real `@DataJpaTest`-backed (H2, `ddl-auto=create-drop`), constructing the real
`InteractiveComprehensionServiceImpl`/`InteractiveLearningPathServiceImpl`/`InteractiveDialogueServiceImpl`
beans against real repositories — never a mock repository standing in for the database.

### 5b. Rate-limit boundary verification

`InteractiveRateLimitService` already had an injectable `Clock` seam (added at Task 10 of the original plan;
no code change was needed this session). This session added the boundary cases that seam's own existing tests
never reached: exact-edge rejection (`+59_999ms` still rejected), exact-edge acceptance (`+60_000ms` frees
capacity — the limiter's own eviction predicate is `>=`, not `>`), a true sliding-window proof (capacity frees
one slot at a time as individual requests age out, not the whole bucket resetting together), and a genuine
multi-threaded proof that 40 concurrent callers against a 20-request bucket yield exactly 20 accepted and 20
rejected (real thread-pool overlap via `ExecutorService`, not sequential calls) — 5 new tests in
`InteractiveRateLimitServiceTest`, all passing.

**Distributed-limiter caveat, stated plainly (unchanged from Task 10, re-confirmed):** this is a per-JVM,
in-memory `ConcurrentHashMap`-backed limiter. The new concurrency test above proves the in-process
synchronization is real, but it says nothing about correctness across multiple app instances — a
multi-instance deployment sharing no state would let each instance independently allow its own N requests,
effectively multiplying the limit by the instance count. This remains an accepted, disclosed limitation
identical to the two existing limiters (`PronunciationRateLimitService`, `ModerationRateLimitService`) this one
already copies; fixing it would mean a shared store (e.g. Redis), which is out of this RC's scope.

### 5c. Route authentication/ownership re-verification

Re-read `SecurityConfig.java` fresh (not trusting RC Task 5's own self-report) and confirmed: none of
`/learning/v2/reading|listening|dialogue`, `/learning/path/v2/summary`, `/learning/v2/certificate`, or any
`/api/learning/v2/**` route appears in any `permitAll()` matcher — every one falls through to the single
`.anyRequest().authenticated()` catch-all. New regression test `SecurityConfigInteractiveV2RouteTest` parses
`SecurityConfig.java`'s real `authorizeHttpRequests` chain with a real `AntPathMatcher` (not a naive substring
check) and asserts no `permitAll` pattern covers any of these routes, and that the chain still ends in
`anyRequest().authenticated()` — a durable guard against a future edit accidentally exposing one of them.
Owner-scoping itself (every service method re-derives the activity/package from the JWT principal via
`findOwnedByIdForUpdate`/`findOwnedById`, never trusting a client-supplied owner id) was already covered by
each task's own cross-user/not-found tests and was not re-litigated here beyond confirming the pattern still
holds in the code read during this session's other work.

### 5d. Sanitization, audio safety, answer-key — one genuine bug found and fixed

- **Genuine, previously-undisclosed bug found and fixed (narrowly, with a reproducing test):**
  `JpaComprehensionExerciseCatalog#sanitizePassage` stripped literal `<script>`/`<style>` blocks and all
  remaining literal tags **before** unescaping HTML entities. An entity-encoded tag arriving from canonical
  content as `&lt;script&gt;...&lt;/script&gt;` sailed through both tag-stripping regexes untouched (they only
  match a literal `<`) and only became a real, literal `<script>` tag once unescaped at the very end —
  reappearing in the value this method returns as "sanitized". Reproduced with new test
  `passageSanitizationDoesNotLetEntityEncodedTagsSurviveViaLateUnescape` (failed before the fix, confirmed by
  running it: the returned "sanitized" source contained a literal `<script>alert(1)</script>`), fixed by
  unescaping entities **first**, then stripping script/style blocks and remaining tags. A second new test,
  `passageSanitizationStripsLiteralTagsAndScriptContent`, covers the already-safe literal-tag case for
  regression. **Not end-to-end exploitable through today's only consumer**
  (`interactive-comprehension-study.js` independently HTML-escapes the passage again via its own `esc()` before
  any `innerHTML` insertion — confirmed by reading the file, which even carries a code comment saying so), but
  the catalog's own contract ("a Reading exercise must have a nonblank sanitized passage") means the value it
  returns must be safe on its own, not merely safe because of a second layer elsewhere.
- **Audio URL safety** — added `listeningRejectsProtocolRelativeUrlsThatWouldResolveToAnExternalHost`,
  confirming `normalizeAudioUrl` already correctly rejects a protocol-relative `//host/...` URL (no scheme, so
  a naive "is it absolute" check alone would treat it as internal; a browser instead silently resolves it
  against the current page's scheme, reaching an external host) — the same class of gap
  `MockTestDestinationProvider` (RC Task 7) already guards against explicitly for its own redirect target.
  This was already correctly handled by existing code; the test closes a coverage gap, not a bug.
- **No answer-key serialization** — re-verified, unchanged: `ComprehensionQuestionSpec`'s `correctOption`/
  `explanation` are `@JsonIgnore`d (existing `internalAnswerIsIgnoredByJsonProjection` test), and every
  public-facing question DTO (`ComprehensionStudyQuestion`, `MicroCheckResponse.MicroCheckQuestion`) is a record
  with only id/prompt/options fields — there is no field to leak an answer through even by mistake.

### 5e. Migration/schema parity

`chk_learning_path_item_activity_type` and `chk_learning_activity_member_type` in `jlas_schema.sql` were
manually re-verified this session to list exactly the same values as `LearningActivityType`/
`LearningMemberType`, and a new regression test (`LearningActivityAndMemberTypeConstraintTest`, mirroring the
existing `QuizSessionTypeConstraintTest` precedent from RC Task 6) now guards this — the same disclosed
H2-`ddl-auto=create-drop`-derives-from-entities limitation applies (a persistence test alone cannot catch a raw
`CHECK` constraint drifting from the Java enum). **Real MySQL check remains pending the user's own
environment** — this worktree has no `application-local.yaml` (confirmed absent, consistent with every prior
session in this series), so no real MySQL connection is reachable from here at all.

## 6. Focused/full test counts (this run, Surefire-parsed)

Full backend suite (`.\mvnw.cmd -q test`), parsed from `target/surefire-reports/*.xml` across all 70 test
classes: **538 / 0 / 0 / 22 skipped** (tests/failures/errors/skipped) — at/above the RC Task 7 baseline of
523/0/0/22. Frontend: **140/140**. `scripts/speed-recall-rules.test.mjs`: **4/4**.
`src/test/js/personalized-path.test.cjs`: **8/8**. `git diff --check`: clean (only benign LF→CRLF line-ending
notices on `git add`, no actual whitespace/conflict-marker errors). `git status --short` after this task's own
commit: clean.

RC Task 8 added 16 new test methods across 6 files (3 real multi-threaded concurrency tests, 5 rate-limit
boundary/concurrency tests, 2 security-route regression tests, 3 CHECK-constraint parity regression tests, 3
sanitization/audio-safety tests), one of which (the entity-encoding sanitization test) reproduced a genuine bug
before the fix and now guards it.

## 7. Feature-flag state and itemized remaining release conditions

`learning.interactive-v2.enabled` **remains `false`** by default (`${LEARNING_INTERACTIVE_V2_ENABLED:false}`
in `application.yaml`) — not touched by this session, per the hard boundary against enabling it. Itemized,
this is what is still missing before it could honestly be flipped to `true` for real users:

1. **Real MySQL schema/migration apply-and-verify** (`sql/migrations/010`-`017` in order, on the user's own
   database) — genuinely cannot happen from this worktree (no `application-local.yaml`, no reachable MySQL).
2. **Real content-readiness audit** — run `sql/audits/2026-09-29-interactive-v2-content-readiness-audit.sql`
   against the real database for every JLPT level the product intends to release, and resolve any
   `QUOTA_AT_RISK_*`/`GOAL_TARGET_CONTENT_MISSING`/`RELEASABLE=false` result before releasing that level.
3. **Real browser UAT** — the manual checklist in
   `docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-rc-manual-uat-checklist.md`, run against a
   real account (never demo/seed) and a real browser, covering all 8 numbered sections including the fail-3
   cooldown timing (which genuinely takes 60 real minutes to observe end-to-end) and cross-device/keyboard/
   reduced-motion checks this session could not perform without a browser.
4. **Migration/seed consolidation** — explicitly the user's own separate post-RC action per
   `docs/superpowers/plans/2026-09-29-post-rc-database-convergence-manual.md`; this RC (including Task 8) never
   touches that.
5. **A decision on the distributed rate-limiter caveat** (§5b) if/when this ever runs multi-instance — not a
   blocker for a single-instance release, but should be a conscious decision, not an oversight, before scaling
   out.
6. Only after 1-4 succeed should the flag be flipped, and only by the user, outside this RC's tooling.

## 8. Mock Test integration status

**`BLOCKED_INTEGRATION`** — unchanged from RC Task 7's own finding, re-confirmed this session by re-reading
`ConfiguredMockTestDestinationProvider` and `application.yaml`: `learning.mock-test.destination-url` is empty
by default, and no real Mock Test module/route/contract exists anywhere in this codebase (re-grepped `src/`
for `mock`/`thi thử` and equivalents; the only pre-existing reference is a doc comment on `AssessmentQuestion`
about a conceptually shared future question bank, not an actual module). The summary page renders an honestly
disabled "Thi thử JLPT (sắp có)" button. This is the correct, honest state — not an error to fix, and not
something RC Task 8 attempted to fabricate a workaround for.

## 9. Real-DB/browser items intentionally left for the user

- No real MySQL connection was made anywhere in this RC, including this Task 8 session — every persistence
  test in the whole RC (existing and new) runs against ephemeral H2 (`ddl-auto=create-drop`), never a real
  database, and this worktree has no `application-local.yaml` to make one possible.
- No seed script was run, no `application-local.yaml` was created, no credentials were touched or copied — all
  explicitly out of scope per this RC's hard boundaries, honored throughout including this final task.
- No browser/manual UI verification was performed by this session — everything about visual layout, real
  60-minute cooldown timing, real screen-reader behavior, and real missing-audio network conditions is captured
  as concrete steps in the manual UAT checklist (§link below) for the user to run themselves.
- The content-readiness audit script (§4) has only ever been smoke-tested against synthetic H2 fixtures, never
  against real content — the user must run it for real before trusting any level's release verdict.

## 10. Manual UAT checklist

`docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-rc-manual-uat-checklist.md` — 8 numbered
sections matching the plan's required coverage (create path → 0/4/6/8 allocation → Reading/Listening
fail-1/2/3 cooldown+review → resume/no-duplication → package completion popup → path completion/summary/
certificate wording → Mock Test honest-disabled state → desktop/mobile/keyboard/reduced-motion/missing-media),
each with concrete URLs and explicit pass/fail "Expected" lines.

---

## Bottom line

RC Task 8 — the last task of the whole RC plan — is complete: a read-only content-readiness audit artifact
(honestly caveatted as unverified against real data), real multi-threaded concurrency proof for the three named
cases (closing every prior task's disclosed "lock-protected but not race-tested" gap for at least these three),
rate-limit boundary tests with the distributed-limiter caveat kept explicit, re-verified route
authentication/ownership, one genuine sanitization-order bug found and fixed with a reproducing test, re-verified
migration/schema parity, a full green regression run at 538/0/0/22 (above the 523 baseline), and this final
handoff plus the manual UAT checklist. **This RC is not production-ready** — that claim requires the real
MySQL audit and real browser UAT items in §9/§10, which this environment cannot perform. The feature flag stays
`false`. No push, no PR, no merge, no deploy, and no migration/seed consolidation were performed, per this RC's
hard boundaries. This is the end of the RC plan and of this whole AI-executed effort; what remains is entirely
the user's own real-database and real-browser acceptance work.
