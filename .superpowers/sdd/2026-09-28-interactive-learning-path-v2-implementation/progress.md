# SDD ledger — plan: docs/superpowers/plans/2026-09-28-interactive-learning-path-v2-implementation.md

Scope ruling (user, mid-turn): implement ONLY Task 4 (Vocabulary study + micro-check), then stop. Do not proceed to Task 5–10.

Branch: codex/interactive-learning-v2 (worktree C:/Users/LAPTOP LENOVO/.codex/worktrees/interactive-learning-v2/SEP490). BASE for Task 4: 3592d3c (docs handoff tasks five through ten).

Pre-flight: Task 4 consumes from Task 3 (LearningActivityMember membership + source_revision; LearningPathPackage; UserLearningPathItem activity states). Task 4 produces: study-session/card evidence, micro-check state, immutable VOCAB_MICRO_CHECK quiz sessions. Task 5 reuses this engine later (out of scope now).

## Task 4 rulings
- Ruling: reuse `learning_quiz_session` for the immutable micro-check snapshot (per spec §8.2) with a new nullable `batch_index` column and quiz type `VOCAB_MICRO_CHECK`, relaxing `chk_quiz_session_type`. — matches spec "extend quiz type rather than a second grading engine" — cost if wrong: legacy sessions carry a null batch_index and the relaxed CHECK accepts the 4 legacy types unchanged, so none.
- Ruling: study evidence lives in learning-owned tables (`learning_study_session`/`learning_study_card`), NOT the flashcard module's SRS, because flip-before-remember must be server-enforced for tamper-proofing and the flashcard module's progress model is a different concern. — spec §8.3 says "reuse Flashcard via adapter" but the flip/remember server enforcement + membership-version resume is Learning's own contract; content still reads canonical `VocabularyEntry` via the dictionary repo (adapter-style), never copying it. — cost if wrong: two progress stores exist; mitigated because Learning never writes Flashcard tables and only reads canonical vocab.
- Ruling: "Chưa nhớ" resets `flipped=false` so the card must be re-flipped when it re-enters the review queue (spec §3.2 "mỗi thẻ phải được lật trước khi chọn"). — cost if wrong: none beyond a card being slightly easier to re-mark.
- Ruling: fail #1/#2 leave activity `CHECK_READY` (retry allowed immediately; result highlights wrong targets) — spec §4.2 "cho quay lại practice ngay" is a soft nudge, not a hard block. — cost if wrong: a learner can retry without a forced review; acceptable because only micro-check pass moves state to PASSED.
- Ruling: question generation is deterministic per batch (rotating WORD_TO_MEANING / READING_TO_WORD / MEANING_TO_WORD + one MATCHING per member), options deduped + deterministically rotated. No AI, no runtime scoring. — cost if wrong: none; fully testable.

## Progress

Task 4: complete (commits 3592d3c..e4bc44f, tests: `./mvnw test` → 293 run / 0 fail / 0 error / 22 skipped; `node --test src/test/frontend/*.test.cjs` → 40/40; `node --test scripts/speed-recall-rules.test.mjs` → 4/4; `node --test src/test/js/personalized-path.test.cjs` → 8/8). Scope stop: user limited work to Task 4 only; Tasks 5–10 not started.

Supersede note: the 2026-09-29 continuation handoff (`docs/superpowers/handoffs/2026-09-29-interactive-learning-v2-task5-to-task10-handoff.md`) explicitly supersedes the "stop after Task 4" instruction above and authorizes continuous, unattended Task 5→10. Resuming from here; this file is appended to, not recreated.

## Task 5 rulings (Kanji study + micro-check)

- Ruling: extracted the Task 4 state machine verbatim into `com.jlas.learning.service.impl.engine.InteractiveStudyEngine<T>` (plain class, not a Spring bean) plus `InteractiveContentAdapter<T>`/`QuestionSpec`/`EngineStudyState<T>`. `InteractiveVocabularyServiceImpl` keeps its exact original constructor signature (all 8 repos/mapper) and builds the engine internally with an inline `VocabularyContentAdapter`, so `InteractiveVocabularyServiceTest`'s `@BeforeEach` needed zero changes. — cost if wrong: engine bug would affect both content types identically, which is the intended tradeoff of removing duplication; regression proven by re-running all 20 pre-existing Vocabulary tests unchanged (all green) before adding Kanji.
- Ruling: `InteractiveKanjiServiceImpl` reuses `MicroCheckResponse`/`MicroCheckResult`/`MicroCheckStateResponse` as-is (already content-agnostic since Task 4) and only introduces a new `KanjiStudyState` DTO for the study-phase view (different card fields: character/hanViet/meaningVi/jlptLevel/strokeCount/onReadings/kunReadings vs Vocabulary's expression/reading/meaning/wordType/exampleJa/exampleVi). — cost if wrong: none; DTOs are additive, no existing contract touched.
- Ruling: the one behavior change inside the shared engine — `LessonQuizQuestion.skill` is now `adapter.activityType().name()` instead of the hardcoded literal `"VOCABULARY"` — is behavior-preserving for Vocabulary (`LearningActivityType.VOCABULARY.name() == "VOCABULARY"`) and gives Kanji questions the correct `"KANJI"` skill label instead of a wrong inherited one. — cost if wrong: a mislabeled `skill` field on a DTO that is not asserted on by any existing test; verified no test depends on the literal string.
- Ruling: Kanji question set combines 4 shapes (Kanji→reading, reading→Kanji, Kanji→meaning, matching) via the same round-robin-select + one-matching-per-target pattern Vocabulary already uses, so both content types share the identical answer-key/1-based-option/idempotent-submit mechanics. A reading-based question falls back to Kanji→meaning when a Kanji has no reading rows at all, and a matching question is skipped for a target with no `meaning_vi`, rather than fabricating a distractor or a correct answer. — cost if wrong: a batch could have fewer than 2N questions for content-poor Kanji rows; acceptable since only real, approved data is ever shown and this only affects content that itself fails the composer's publish gate (§ "Interactive Learning Path V2 composition" already requires "contextual Kanji with readings").
- Ruling: no schema/migration change for Task 5 — `learning_activity_member`, `learning_study_session/card`, `learning_micro_check_state`, `learning_quiz_session` were already content-agnostic (generic `canonical_id`/`member_type`(`KANJI` already a valid enum value)/`quiz_type`), confirmed by `InteractiveKanjiPersistenceTest` reusing them unchanged against H2.
- Ruling: frontend is a dedicated `interactive-kanji-study.js`/`.html` pair mirroring `interactive-study.js`'s view-model/render/mount shape exactly (same CSS classes `ilp-*`, so it inherits Vocabulary's already-implemented responsive/keyboard/aria/reduced-motion behavior), rather than a shared JS module — the repo has no bundler and each vertical-slice page is already its own file pair, so a shared runtime module would be new architecture, not a "minimal seam." `interactive-learning-path.js`'s `activityRow` now maps both `VOCABULARY` and `KANJI` to their own page.

## Progress

Task 5: complete. New/changed:
- `service/impl/engine/{InteractiveContentAdapter,QuestionSpec,EngineStudyState,InteractiveStudyEngine}.java` (shared engine, extracted from Task 4, no behavior change for Vocabulary).
- `service/impl/InteractiveVocabularyServiceImpl.java` refactored to the engine (same public contract/constructor).
- `service/InteractiveKanjiService.java`, `service/impl/InteractiveKanjiServiceImpl.java`, `controller/InteractiveKanjiController.java`, `dto/KanjiStudyState.java`.
- `controller/LearningPathPageController.java` — new `GET /learning/v2/kanji` route; `templates/learning/interactive-kanji-study.html`; `static/js/pages/interactive-kanji-study.js`.
- `static/js/pages/interactive-learning-path.js` — Kanji activities now link to their study page (was Vocabulary-only).
- Tests: `InteractiveKanjiServiceTest` (10), `InteractiveKanjiControllerTest` (5), `InteractiveKanjiPersistenceTest` (2, H2), `interactive-kanji-study.test.cjs` (9), 2 new assertions in `interactive-learning-path.test.cjs`.
- `docs/rules-and-business-logic.md` §2.12 added.

Verification (this run): `./mvnw test` → 310 run / 0 fail / 0 error / 22 skipped (surefire-report aggregate, not just exit code) — includes the 20 pre-existing Vocabulary tests unchanged plus 17 new Kanji tests. `node --test src/test/frontend/*.test.cjs` → 50/50. `node --test scripts/speed-recall-rules.test.mjs` → 4/4. `node --test src/test/js/personalized-path.test.cjs` → 8/8. `git diff --check` → clean (only CRLF/LF autocrlf notices).

Known gap carried from Task 4, unchanged by Task 5 (per handoff §"Ba giới hạn đã biết"): `issueMicroCheck` is not yet strictly idempotent (a double-click could create an orphaned `ISSUED` snapshot without consuming an attempt); `submitMicroCheck` is idempotent. Not addressed in Task 5 since the handoff explicitly scopes this as optional hardening, not a blocker, and it must be done for both content types together if picked up.
