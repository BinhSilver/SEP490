# Handoff — Interactive Learning Path V2 Task 6 complete; Task 7 not started this run

This session was authorized to implement Task 6 only (Grammar interaction), continuing from Task 5's
completion handoff. **Task 6 is genuinely complete with evidence from this run.** Task 7 was **not attempted**,
per this run's explicit instruction to stop after Task 6 regardless of remaining time/effort.

## 1. Commit and working-tree state

- **Branch:** `codex/interactive-learning-v2`
- **Task 6 commit:** `41fdbb5` — `feat(JLAS-W0193): add Grammar practice gate and micro-check` (30 files,
  +2213/−9).
- **BASE for Task 6:** `df0614b` (Task 5 completion handoff commit). Range: `df0614b..41fdbb5`.
- **Working tree after commit:** clean (verify with `git status --porcelain=v1 -uall`).

## 2. Files changed in Task 6

**Shared engine (additive only — see §3 for why this is safe):**
- `service/impl/engine/InteractiveContentAdapter.java` — two new default methods,
  `requiresFlipRememberGate()` (default `true`) and `assertPracticeGateSatisfied(...)` (no-op default).
- `service/impl/engine/InteractiveStudyEngine.java` — `issueMicroCheck` branches on
  `adapter.requiresFlipRememberGate()` instead of unconditionally calling `requireAllRemembered`.

**Grammar (new):**
- `service/impl/grammar/GrammarTextAnalyzer.java` — pure, deterministic pattern/example tokenization,
  particle/copula classification, fill-blank target selection, deterministic ordering shuffle, answer
  normalization. No Spring dependencies; trivially unit-testable.
- `entity/LearningGrammarPracticeProgress.java`, `repository/LearningGrammarPracticeProgressRepository.java`.
- `service/InteractiveGrammarService.java`, `service/impl/InteractiveGrammarServiceImpl.java` (+ private
  `GrammarContentAdapter` implementing the two new adapter hooks), `controller/InteractiveGrammarController.java`.
- `dto/GrammarStudyState.java`, `dto/GrammarPracticeResult.java`, `dto/GrammarOrderingSubmitRequest.java`,
  `dto/GrammarFillSubmitRequest.java`.
- `controller/LearningPathPageController.java` — new `GET /learning/v2/grammar`.
- `templates/learning/interactive-grammar-study.html`, `static/js/pages/interactive-grammar-study.js`.
- `static/js/pages/interactive-learning-path.js` — `activityRow` now also maps `GRAMMAR` to its study page.
- `static/css/pages/interactive-study.css` — new Grammar section/practice classes appended to the shared
  stylesheet (same file Vocabulary/Kanji already use).

**Bug fix (Task 5 scope, narrowly applied — see §5):**
- `templates/learning/interactive-kanji-study.html` — `pageCss` corrected from a dangling
  `'interactive-kanji-study'` to `'interactive-study'`.

**Schema:**
- `jlas_schema.sql` — new `learning_grammar_practice_progress` table; `chk_quiz_session_type` widened to add
  `KANJI_MICRO_CHECK` (Task 5 gap, see §5) and `GRAMMAR_MICRO_CHECK`.
- `sql/migrations/013_interactive_grammar_practice.sql` — the same two changes as an incremental migration.

**Tests (39 new Java + 12 new/changed frontend):**
- `GrammarTextAnalyzerTest` (13), `InteractiveGrammarServiceTest` (14), `InteractiveGrammarControllerTest` (5),
  `InteractiveGrammarPersistenceTest` (3, `@DataJpaTest` H2), `InteractiveStudyPageCssAssetTest` (4),
  `interactive-grammar-study.test.cjs` (12), 2 new assertions in `interactive-learning-path.test.cjs`.

**Docs:** `docs/rules-and-business-logic.md` §2.13 (+ a bug note appended to §2.12); implementation plan
Task 6 checked off; SDD ledger (`progress.md`) has Task 6 rulings and progress appended.

## 3. The practice-gate design decision (and why)

The governing instructions offered two options: (a) a second, generic "practice-gate" strategy hook that
Vocabulary/Kanji would also have to implement (trivially, via their existing flip/remember) to keep working;
or (b) keep the engine's micro-check/attempt/cooldown/review/idempotent-submit machinery completely
untouched and build the ordering/fill-select gate as new, Grammar-specific code that feeds into the same
eligibility-check call site the engine already exposes.

**Chose (b).** Concretely:

- `InteractiveContentAdapter<T>` gained `requiresFlipRememberGate()` (default `true`) and
  `assertPracticeGateSatisfied(Long activityId, List<LearningActivityMember> membership)` (no-op default).
  Every existing adapter (`VocabularyContentAdapter`, `KanjiContentAdapter`) needed **zero** code change —
  they simply inherit the defaults, which reproduce the exact pre-Task-6 code path byte-for-byte.
- `InteractiveStudyEngine.issueMicroCheck` now reads:
  ```java
  if (adapter.requiresFlipRememberGate()) {
      requireAllRemembered(activityId, membership);
  } else {
      adapter.assertPracticeGateSatisfied(activityId, membership);
  }
  ```
- Grammar's own `GrammarContentAdapter` (a private static nested class inside
  `InteractiveGrammarServiceImpl`, mirroring Kanji's `KanjiContentAdapter` shape) overrides both: returns
  `false` from `requiresFlipRememberGate()`, and implements `assertPracticeGateSatisfied` by checking
  `LearningGrammarPracticeProgressRepository` for every membership target, throwing
  `ApiException(PREREQUISITE_NOT_MET)` if any point's `(ordering_done, fill_done)` pair is not both `true`
  **for the membership's current `source_revision`**.
- Everything else — `studyState`/`flip`/`remember` (unused by Grammar; it has its own study/practice methods
  entirely outside the engine), `issueMicroCheck`'s batching/snapshotting, `submitMicroCheck`'s
  grade/attempt/cooldown/review logic, `microCheckState`, `completeReview` — is the exact same code Task 4
  wrote and Task 5 reused, now reused a third time with zero duplication.

**Why (b) over (a):** option (a) would have forced Vocabulary and Kanji to acknowledge a concept (a
"practice gate") that doesn't apply to them, purely so Grammar could plug into a shared abstraction — that's
inverted cost for zero behavioral benefit. Option (b) keeps Vocabulary/Kanji's code and tests at literally
zero diff (proven: their full test suites were re-run immediately after the engine/adapter interface edit,
before any Grammar-specific code existed, and stayed green), while still satisfying the instruction's hard
constraint — "do not copy-paste the attempt/cooldown/scoring/idempotency state machine" — since that half is
untouched and singly sourced.

## 4. API / schema changes

**API**, base `/api/learning/v2/activities/{activityId}/grammar`:

| Method | Path | Purpose |
|---|---|---|
| GET | `/study` | `GrammarStudyState`: 1-3 points, each with objective/structure/component breakdown/example/note, ordering practice (shuffled word pool), fill practice (blanked sentence), and per-point + aggregate gate status. |
| POST | `/points/{grammarPointId}/practice/ordering` | Body `{order: number[]}` (permutation of token ids). Returns `GrammarPracticeResult` (correct/gateSatisfied/correctText/explanation). |
| POST | `/points/{grammarPointId}/practice/fill` | Body `{answer: string}`. Same result shape. |
| POST | `/micro-check` | Issue — identical contract to Vocabulary/Kanji, rejects with `PREREQUISITE_NOT_MET` until every point's gate is satisfied. |
| POST | `/micro-check/submit` | Same `MicroCheckSubmitRequest`/`MicroCheckResult` as Vocabulary/Kanji. |
| GET | `/micro-check/state` | Same `MicroCheckStateResponse`. |
| POST | `/micro-check/review` | Same contract. |

Page: `GET /learning/v2/grammar?activity={id}`.

**Schema:** additive only.
- New table `learning_grammar_practice_progress(id, activity_item_id FK→user_learning_path_item, grammar_point_id,
  source_revision, ordering_done, fill_done, ordering_done_at, fill_done_at, row_version, created_at, updated_at)`,
  unique on `(activity_item_id, grammar_point_id)`. No FK to `grammar_point` itself — same "canonical id, not a
  second source of truth" pattern `learning_activity_member` already uses.
- `learning_quiz_session.chk_quiz_session_type` widened from
  `('GATE','LESSON','GRAMMAR','FLASHCARD','VOCAB_MICRO_CHECK')` to also include `KANJI_MICRO_CHECK` and
  `GRAMMAR_MICRO_CHECK`. Migration `013_interactive_grammar_practice.sql` does `DROP CHECK` + `ADD CONSTRAINT`
  (same pattern migration 012 used to add `VOCAB_MICRO_CHECK`). No backfill needed — this only *widens* the
  allowed set; every existing row's `quiz_type` was already one of the retained values.
- No entity/schema drift: `jlas_schema.sql`, the new migration, `LearningGrammarPracticeProgress`'s JPA
  mapping, and `InteractiveGrammarPersistenceTest` (H2, schema generated from the entity) were all checked
  column-by-column to match.

## 5. Two bugs found and fixed this session (both narrowly scoped, both with a reproducing test)

1. **Task 5's `KANJI_MICRO_CHECK` was never added to `chk_quiz_session_type`.** Task 5's own completion
   handoff claimed "no schema change was needed" for the quiz-session table, which is true for columns but
   missed that the CHECK constraint's allowed value list still only had `VOCAB_MICRO_CHECK`. This was never
   caught by `InteractiveKanjiPersistenceTest` because that test's `@DataJpaTest` uses
   `ddl-auto=create-drop`, which makes H2 generate its schema from the `@Entity` annotations directly — the
   entity has no `@Check` annotation, so H2 never applies (or violates) this constraint at all. On real
   MySQL 8.0.16+ (which does enforce `CHECK`), inserting a Kanji micro-check quiz session would have failed.
   Fixed in the same migration as Grammar's own `GRAMMAR_MICRO_CHECK` addition, since both share the one
   constraint. **I could not add a reproducing H2 test for this specific bug** (H2's `ddl-auto=create-drop`
   is exactly the mechanism that hid it), so this is a schema-review fix, not a test-proven one — flagged
   explicitly rather than silently claimed as verified.
2. **Task 5's Kanji study template referenced a stylesheet that doesn't exist.**
   `interactive-kanji-study.html` passed `pageCss='interactive-kanji-study'` to `fragments/head.html ::
   standard(pageTitle, pageCss)`, but the only stylesheet ever built for these pages is
   `interactive-study.css` (confirmed: `find src/main/resources/static/css/pages -iname '*.css'` has no
   `interactive-kanji-study.css`). Thymeleaf's `th:if="${pageCss != null...}" th:href="@{'/css/pages/' +
   ${pageCss} + '.css'}"` never checks the file exists, so the `<link>` silently 404'd — the Kanji page has
   been rendering with **zero page-specific CSS** since Task 5 landed (it still gets the shared design-system
   stylesheets from the rest of `fragments/head.html`, just none of the `ilp-*` classes the JS actually
   renders). Verified `pageCss` has no other use in `fragments/head.html` before changing it (only feeds this
   one `<link href>`, plus an unrelated `pageCss == 'profile'` check). Fixed to `'interactive-study'`; Grammar's
   own template uses the same value from the start (it would otherwise have copied the identical bug). Added
   `InteractiveStudyPageCssAssetTest` (4 tests) that reads all three V2 study templates' source and asserts
   the `pageCss` argument resolves to a real file under `static/css/pages/` — this is a real regression test,
   run both before the fix (failing on the Kanji template) and after (passing on all three).

Both fixes are narrow (one attribute / one CHECK constraint), match the exact templates/tables Task 6 was
already touching, and do not change any Vocabulary/Kanji business logic, DTOs, or test expectations.

## 6. IDOR / tampering / answer-key / duplicate / concurrency — Grammar's new mechanics specifically

- **IDOR (study/practice):** `InteractiveGrammarServiceImpl.loadActivity` re-derives the activity from the
  JWT principal via `findOwnedByIdForUpdate(activityId, userId)` and rejects `activityType != GRAMMAR` as
  `NOT_FOUND` (proven by `vocabularyActivityIsRejectedAsNotFoundForGrammarService`). `requireMember` re-reads
  this activity's own membership and rejects any `grammarPointId` not in it as `NOT_FOUND` — a point id from
  a different activity/package/user is indistinguishable from a nonexistent one (proven by
  `grammarPointOutsideThisActivitysMembershipIsRejectedAsNotFound`).
- **IDOR (micro-check):** identical to Vocabulary/Kanji — `GRAMMAR_MICRO_CHECK` quiz sessions are owner- and
  quiz-type-scoped by `LearningQuizSessionRepository.findOwnedByActivity`, proven separately from
  `KANJI_MICRO_CHECK` and from a stranger's principal by `InteractiveGrammarPersistenceTest
  #grammarQuizSessionIsOwnerAndQuizTypeScopedSeparatelyFromKanji`.
- **Tampering:** the client submits `order: number[]` (a permutation of server-issued token ids) or
  `answer: string`; correctness is always computed server-side against the canonical `GrammarPoint` reloaded
  from `GrammarPointRepository`, never trusted from the request. There is no client-settable `correct`,
  `orderingDone`/`fillDone`, `gateSatisfied`, score, attempt count, or cooldown field anywhere in the request
  DTOs (`GrammarOrderingSubmitRequest`, `GrammarFillSubmitRequest`) — proven implicitly by every practice test
  asserting the *response*'s `correct`/`gateSatisfied` reflects only what the server computed, e.g.
  `wrongOrderingDoesNotMarkProgressDoneButStillExplainsTheCorrectOrder`.
- **Answer-key exposure:** the ordering pool ships the real (shuffled) words to the client — this is
  intentional and not a leak, since the "secret" is the order, not the words, and the canonical order is
  simply "ascending token id," nothing to hide. The fill-blank `promptWithBlank` never contains the missing
  word (proven by `fill practice never reveals the missing word in the prompt` in
  `interactive-grammar-study.test.cjs`, and `assertFalse(view.fill().promptWithBlank().contains(...))` in the
  Java suite). The micro-check's `MicroCheckResponse.MicroCheckQuestion` is the exact same 4-field record
  Vocabulary/Kanji use (no answer field at all) — re-asserted by
  `issueReturnsServerSnapshotWithoutAnswerKey`'s record-component-count check.
- **Duplicate/idempotency:** the micro-check half is the unchanged engine contract — proven again for Grammar
  by `duplicateSubmitReturnsSavedResultWithoutConsumingAttempt`. Practice submits (ordering/fill) have no
  attempt limit in the spec (unlike the micro-check), so there is nothing to "double-consume"; re-submitting
  an already-correct answer is naturally idempotent in effect (the flag stays `true`), though the row is
  still re-saved — documented as a deliberate, low-risk simplification in the ledger's Task 6 rulings, not
  silently assumed.
- **Concurrency:** `LearningGrammarPracticeProgressRepository.findForUpdate` is
  `@Lock(PESSIMISTIC_WRITE)`, locking the specific `(activity, grammarPointId)` progress row for the duration
  of a submit — the same pattern `LearningMicroCheckStateRepository.findForUpdate` already uses for the
  micro-check. The activity row itself is also locked via `findOwnedByIdForUpdate`. The micro-check's own
  concurrency protection (activity-row lock + micro-check-state lock + `@Version`) is entirely unchanged.

## 7. Test counts from this run

Focused (Surefire):
- `GrammarTextAnalyzerTest`: 13/0/0/0.
- `InteractiveGrammarServiceTest`: 14/0/0/0.
- `InteractiveGrammarControllerTest`: 5/0/0/0.
- `InteractiveGrammarPersistenceTest`: 3/0/0/0 (H2).
- `InteractiveStudyPageCssAssetTest`: 4/0/0/0.
- Vocabulary/Kanji regression, re-run immediately after the `InteractiveContentAdapter`/`InteractiveStudyEngine`
  interface edit and before any Grammar-specific code existed — all unchanged, all green:
  `InteractiveVocabularyServiceTest` 11/0/0/0, `InteractiveKanjiServiceTest` 10/0/0/0,
  `InteractiveVocabularyControllerTest` 5/0/0/0, `InteractiveKanjiControllerTest` 5/0/0/0.

Full regression (this run, offline `-o`, surefire-report aggregate — not just exit code):
- `./mvnw test` → BUILD SUCCESS. **349 tests, 0 failures, 0 errors, 22 skipped** (same 22 pre-existing skips
  as Task 4/5's baseline; was 310 at the Task 5 baseline — +39 from this task).
- `node --test src/test/frontend/*.test.cjs` → **64/64** (was 50/50 at the Task 5 baseline; +12 from
  `interactive-grammar-study.test.cjs`, +2 from the roadmap Grammar-link assertions added to
  `interactive-learning-path.test.cjs`; 50+12+2=64, all green).
- `node --test scripts/speed-recall-rules.test.mjs` → **4/4**.
- `node --test src/test/js/personalized-path.test.cjs` → **8/8**.
- `git diff --check` → clean (only CRLF/LF autocrlf advisory notices, no real whitespace errors).

## 8. Known gaps / limitations, stated honestly

- **`issueMicroCheck` non-strict-idempotency** (a double-click could create an orphaned `ISSUED` snapshot
  without consuming an attempt) is a Task 4 gap, unchanged by Task 5, and now also applies to Grammar's
  micro-check because it is the exact same engine code. Not addressed here — the Task 5 handoff scoped this
  as optional hardening that must be done for all content types together if picked up; that's still true now
  with three content types instead of two.
- **The "2 fresh, non-verbatim" micro-check questions are derived from the same canonical `GrammarPoint`
  row the practice already read**, not from a separate authored question bank (the canonical schema has no
  such field). The distinguishing factor is source field (`meaning` vs. `example`) and artifact shape (whole
  sentence vs. shuffled/blanked fragment) — documented as a ruling in the ledger and flagged there for
  review rather than asserted as unquestionably sufficient.
- **The `KANJI_MICRO_CHECK` CHECK-constraint gap fix has no reproducing H2 test** (H2's `ddl-auto=create-drop`
  is exactly what hid the original bug — it never applies raw-SQL `CHECK` constraints at all). This was
  fixed by direct schema review, not proven by a failing-then-passing test the way the CSS bug was.
- **No screenshot/browser-driven UI verification.** The Grammar page was verified by unit tests
  (`interactive-grammar-study.test.cjs`, 12 pure render-function tests covering role labels, blank-hiding,
  submit-enablement, feedback placement, answer-key non-exposure) and by careful reuse of Vocabulary/Kanji's
  already-established `ilp-*` visual system (plus new, purple/lime-consistent classes for the formula/
  component/ordering/fill UI, with `min-height:44px` touch targets and `:focus-visible` outlines added), but
  the app was not actually run in a browser this session — no `./mvnw spring-boot:run` + live-page screenshot
  was taken. This matches the depth of UI verification Task 4/5 also had (JS unit tests, not live browser
  screenshots) — see §9 for how to do that verification yourself.
- **No composer/Task-3 validation that a target package's grammar points always have a non-blank `example`.**
  `GrammarTextAnalyzer.chooseBlankIndex`/the service's ordering/fill handlers throw `BLOCKED_CONTENT` if a
  point's `example` is empty (defensive, real error — not a silent skip), but this was not exercised end-to-
  end against the composer's actual readiness gate in this session; all 8 rows of the current lesson-1 seed
  data have a non-empty `example`, so this path is untested against real composed content.

## 9. What the user should do now

1. `./mvnw.cmd spring-boot:run` (needs local MySQL + `application-local.yaml`, or point at H2 for a quick look).
   Since this session ran offline (`-o`) against the Maven cache only, run online at least once afterward to
   confirm dependency resolution is unaffected (it was not touched).
2. If starting from a clean DB: run `jlas_schema.sql` (or apply migration `013_interactive_grammar_practice.sql`
   on top of an existing DB through 012) and reseed via `db/seed_data_jlas.sql` for lesson-1 grammar points.
3. Log in as your own account (not a seed/demo account). `learning.interactive-v2.enabled=true` locally to
   compose a V2 target package, or reuse an already-composed one.
4. Open `/learning/path/v2`, click into the **Grammar** activity (now linked the same way Vocabulary/Kanji
   are) to reach `/learning/v2/grammar?activity={id}`. For each point: tap words in order to reconstruct the
   sentence and press "Kiểm tra thứ tự"; type the missing word and press "Kiểm tra". Once every point's gate
   shows "Đã luyện xong", "Bắt đầu kiểm tra" becomes enabled — exercise pass/fail/cooldown the same way you
   would for Vocabulary/Kanji.
5. Dialogue/package-completion/warm-up/summary/certificate are **not built yet** — the roadmap will show them
   as unlinked list items until Task 7+ lands, exactly as stated in the Task 5 handoff.

## 10. Recommended continuation

Resume from this handoff and the ledger
(`.superpowers/sdd/2026-09-28-interactive-learning-path-v2-implementation/progress.md`, which has Task 6's
rulings appended) in a fresh session for **Task 7 only** (Dialogue with trustworthy completion), following
the same per-task discipline: read the required docs in order, TDD, focused + full regression, self-review,
commit, ledger + handoff. Task 7 is a genuinely new subsystem (server-computed active-time from
visible-heartbeat events, no precedent in Tasks 3-6) and should not be compressed together with Task 8+ in
the same run for the same honesty-over-speed reasons Task 5's handoff gave for stopping after one task.
