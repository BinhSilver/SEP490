import assert from "node:assert/strict";
import test from "node:test";

import { buildAssessmentCoverage, buildLearningCoverage } from "./build_release_coverage_reports.mjs";

test("learning coverage exposes three lesson parts and fail-closed readiness", () => {
  const model = {
    LESSON: [{ lesson_code: "L1", jlpt_level: "N5", lifecycle_status: "DRAFT", external_key: "lesson:1", review_status: "NEEDS_REVIEW" }],
    LESSON_SCOPE: [], VOCAB_LESSON_MAP: Array.from({ length: 5 }, (_, index) => ({ lesson_code: "L1", vocab_code: `V${index}` })),
    GRAMMAR: [{ lesson_code: "L1" }], KANJI_LESSON_MAP: [], DIALOGUE: [{ lesson_code: "L1" }],
    READING: [], LISTENING: [], EXERCISE: [{ lesson_code: "L1" }],
    READINESS: [{ external_key: "lesson:1", ready_for_import: false }],
  };
  const [row] = buildLearningCoverage(model);
  assert.equal(row.goal, "JLPT");
  assert.equal(row.level, "N5");
  assert.equal(row.vocabulary_count, 5);
  assert.equal(row.flashcard_quiz_required, true);
  assert.equal(row.grammar_kanji_count, 1);
  assert.equal(row.dialogue_count, 1);
  assert.equal(row.ready_for_import, false);
});

test("assessment coverage separates approved and import-ready question counts", () => {
  const model = {
    QUESTION: [
      { question_code: "Q1", jlpt_level: "N5", skill: "VOCABULARY", external_key: "q:1", review_status: "APPROVED" },
      { question_code: "Q2", jlpt_level: "N5", skill: "VOCABULARY", external_key: "q:2", review_status: "NEEDS_REVIEW" },
    ],
    QUESTION_USAGE: [{ question_code: "Q1", active: true }],
    READINESS: [{ external_key: "q:1", ready_for_import: true }, { external_key: "q:2", ready_for_import: false }],
  };
  assert.deepEqual(buildAssessmentCoverage(model), [{
    level: "N5", skill: "VOCABULARY", question_count: 2, approved_count: 1,
    import_ready_count: 1, active_usage_count: 1,
  }]);
});
