# Interactive Learning Path V2 Implementation Plan

> **For agentic workers:** Execute task by task. Read `AGENTS.md`, `docs/rules-and-business-logic.md`, the approved spec and visual contract before editing. Keep legacy paths readable until consumers and historical data are audited.

**Goal:** Replace lesson-copy paths with a secure, evidence-based interactive path made from resumable learning packages while preserving legacy behavior during rollout.

**Architecture:** Add `INTERACTIVE_V2` beside `LEGACY_V1`. A V2 path owns ordered packages; each target package owns exactly four ordered activities: Vocabulary, Kanji, Grammar and Dialogue. The server owns state, access, score and unlock decisions. Browser code only renders server state.

**Tech stack:** Java 21, Spring Boot 4, Spring Data JPA, MySQL/H2, Thymeleaf, vanilla JS/CSS, JUnit 5, MockMvc and Node `node:test`.

**Approved sources:**

- `docs/superpowers/specs/2026-09-28-interactive-learning-path-v2-design.md`
- `docs/superpowers/assets/interactive-learning-v2/roadmap-reference.png`
- `docs/superpowers/assets/interactive-learning-v2/grammar-reference.png`
- `docs/superpowers/assets/interactive-learning-v2/micro-check-reference.png`

## Global constraints

- Mark schema/business changes `[JLAS-W0193]`; do not implement W0127.
- Do not alter Smart Test, Flashcard, Textbook, Kaiwa or Mock Test ownership contracts without a `learning` adapter.
- Principal is the only user identity. Scope every package/activity/session query through path ownership.
- Use `ApiResponse<T>` and `ApiException(ErrorCode)`; never expose answer keys or trust client score/time/state.
- Gemini is optional editorial tooling only; no runtime question generation, scoring or path decisions.
- Update `jlas_schema.sql` and an incremental migration. Keep seeds separate.
- Render only real contract data. Use the approved purple/lime, spacious, mostly borderless design and meet keyboard, contrast, reduced-motion and responsive acceptance.

## Delivery map

### Task 1 — Versioned package/activity persistence (10%) — ✅ complete (`3b6bb77`)

**Files:** `jlas_schema.sql`, `sql/migrations/009_interactive_learning_path_v2.sql`, `UserLearningPath`, `UserLearningPathItem`, new `LearningPathPackage`, four V2 enums, repositories, and `InteractiveLearningPersistenceTest`.

- [ ] Test legacy default, package order, activity order and owner-scoped lookup first.
- [ ] Add `experience_version` defaulting to `LEGACY_V1`.
- [ ] Add package table with owner path, phase/day/order/title/status/minutes, timestamps and optimistic version.
- [ ] Add nullable `package_id`, `activity_type`, `activity_state` to path items so legacy rows remain valid.
- [ ] Enforce one package per path/day and one activity type per package.
- [ ] Run `./mvnw test -Dtest=InteractiveLearningPersistenceTest`.
- [ ] Commit `feat(JLAS-W0193): add versioned learning packages`.

### Task 2 — Owned V2 roadmap and server-owned resume (20% checkpoint) — ✅ complete (`09e0312`)

**Files:** new V2 activity/package/path response records, `InteractiveLearningPathService` + implementation, controller, page route, `interactive-path.html`, page CSS/JS, service/controller tests and `interactive-learning-path.test.cjs`.

**Contract:**

```text
GET  /api/learning/v2/paths/current
GET  /api/learning/v2/packages/{packageId}
POST /api/learning/v2/packages/{packageId}/start
GET  /learning/path/v2
```

- [ ] Test V2-only current lookup, package order, exactly-four target activity validation, IDOR-safe not-found, locked rejection and idempotent start.
- [ ] Return only server-derived progress, `resumeActivityType`, lock reason and genuine actions.
- [ ] Lock the owned package row for start. `AVAILABLE → IN_PROGRESS`; first activity `LOCKED → STUDYING`; retry returns the same state.
- [ ] Keep legacy `/learning/path` and endpoints unchanged; add a separate V2 route during rollout.
- [ ] Node-test ordered days, four-step rail, omission of absent optional data and absence of lesson/daily/final quiz labels.
- [ ] Implement mission header, progress, phase switcher, active day, four activity rows and current goal following the approved reference. Omit unsupported XP/download/timer/notes.
- [ ] Run focused tests, then every baseline Maven and Node suite.
- [ ] Commit `feat(JLAS-W0193): add secure interactive path roadmap`.

**20% exit criteria:** backward-compatible schema; only owners can read/start V2 packages; resume is server-derived and idempotent; legacy behavior is unchanged; roadmap follows the visual contract; all tests pass. V2 generation remains disabled until canonical coverage is validated in Task 3.

### Task 3 — Composer and data readiness gate (30%) — ✅ complete

- [x] Compose versioned membership snapshots from canonical IDs and reject target packages missing any of four published components.
- [x] Return machine-readable coverage gaps; never fill them with arbitrary lessons or runtime AI.
- [x] Activate V2 composition behind `learning.interactive-v2.enabled` (default `false`) and test duplicates, wrong levels, unpublished and incomplete content.

### Task 4 — Vocabulary study and micro-check (40%) — ✅ complete

- [x] Persist versioned membership and flip/remember evidence; unresolved cards re-enter the same session.
- [x] Require every card flipped and remembered before issue.
- [x] Cover every target in batches ≤10 with bidirectional meaning/reading and matching questions.
- [x] Store immutable snapshots, first/latest/best, three attempts, review requirement and 60-minute server cooldown.
- [x] Build the complete large-card UI with counts and an eligibility-controlled check action.

### Task 5 — Kanji study and micro-check (50%) — ✅ complete

- [x] Reuse the engine with distinct Kanji membership/evidence — extracted `InteractiveStudyEngine<T>` + `InteractiveContentAdapter<T>` from Task 4's `InteractiveVocabularyServiceImpl` (no state-machine copy); Vocabulary's own 20 tests stayed green unchanged through the extract.
- [x] Cover Kanji→reading, reading→Kanji, Kanji→meaning and matching — deterministic, no-AI question generation with real-data-only fallbacks (no reading ⇒ falls back to Kanji→meaning; no `meaning_vi` ⇒ matching skipped for that target).
- [x] Show approved contextual reading/examples and preserve resume/cooldown semantics — `KanjiStudyState` renders character/hanViet/meaningVi/jlptLevel/strokeCount/onReadings/kunReadings, omitting (never fabricating) any field without real data; resume/flip/remember/attempt-cycle/cooldown/review/idempotent-submit are the unchanged engine contract, proven by `InteractiveKanjiServiceTest`/`ControllerTest`/`PersistenceTest` (17 new tests) plus `interactive-kanji-study.test.cjs` (9 new frontend tests).

### Task 6 — Grammar interaction (60%)

- [ ] Model 1–3 approved points with objective, structure, component analysis and examples.
- [ ] Require ordering plus fill/select practice for every point before check.
- [ ] Require two fresh approved questions per point and reject verbatim practice reuse.
- [ ] Build the reference grammar page and immediate explanatory feedback without runtime AI.

### Task 7 — Dialogue with trustworthy completion (70%)

- [ ] Add a Learning Path page backed by a read-only canonical dialogue adapter; never redirect to textbooks.
- [ ] Validate visible heartbeats server-side, cap each at 20 seconds, reset gaps >30 seconds and persist bottom evidence.
- [ ] Complete only at 180 active seconds plus bottom reached; test ownership and idempotency.

### Task 8 — Package completion and next action (80%)

- [ ] Transactionally complete only after three passes plus Dialogue completion.
- [ ] Calculate score from Vocabulary/Kanji/Grammar; reward/streak/event exactly once.
- [ ] Implement the three-action popup: return, learn ahead, rest; last package advances to summary.

### Task 9 — Warm-up B personalization (90%)

- [ ] Convert proven Smart Test weaknesses to target-level activities without whole lessons.
- [ ] Apply Reading/Listening bands: `>70=0`, `>50–70=4`, `>40–50=6`, `0–40=8`; missing evidence requires verification.
- [ ] Limit Reading/Listening to two total per day within the learner's time budget.
- [ ] Remove V2 warm-up gate/daily quiz links while retaining legacy records/endpoints.

### Task 10 — Summary, certificate and release hardening (100%)

- [ ] Present first/latest/best, unresolved dimensions, Dialogue completion and readiness confidence without cross-skill compensation.
- [ ] Issue only “Chứng nhận hoàn thành lộ trình J-LAS”; link via the W0193 mock-test owner contract.
- [ ] Add IDOR, concurrency, duplicate, clock, rate-limit, coverage, responsive, accessibility and E2E tests.
- [ ] Audit consumers before legacy retirement; document rollback/data compatibility; run full suites and screenshot desktop/mobile.

## Verification

```bash
./mvnw test
node --test src/test/frontend/*.test.cjs
node --test scripts/speed-recall-rules.test.mjs
node --test src/test/js/personalized-path.test.cjs
```

After each task record branch/commit, changed files, exact test results, schema compatibility, decisions and remaining acceptance scenarios. Percentage means completed vertical slices with passing exit criteria, not changed-line count.
