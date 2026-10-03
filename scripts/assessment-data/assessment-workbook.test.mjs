import assert from "node:assert/strict";
import test from "node:test";

import { validateAssessmentModel } from "./validate-assessment-workbook.mjs";

function validModel() {
  return {
    BLUEPRINT: [{ blueprint_code: "JLPT-N5-V1", display_name: "JLPT N5", usage_code: "JLPT_MOCK_TEST",
      jlpt_level: "N5", policy_version: "OWNER_POLICY_V1", active: true,
      external_key: "exam:jlpt:n5:v1", review_status: "APPROVED" }],
    SECTION: [{ section_code: "JLPT-N5-V1-VOCAB", blueprint_code: "JLPT-N5-V1", section_order: 1,
      skill: "VOCABULARY", question_count: 1, required: true,
      external_key: "exam:jlpt:n5:v1:vocab", review_status: "APPROVED" }],
    QUESTION: [{ question_code: "Q-N5-1", source_code: "JLPT-SOURCE", skill: "VOCABULARY",
      jlpt_level: "N5", question_type: "MULTIPLE_CHOICE", prompt_ja: "問題", correct_answer: "A",
      explanation_vi: "Giải thích", external_key: "q:n5:1", confidence: "HIGH",
      review_status: "APPROVED", lifecycle_status: "PUBLISHED" }],
    OPTION: [
      { option_code: "Q-N5-1-O1", question_code: "Q-N5-1", option_order: 1, option_text: "A", is_correct: true, external_key: "q:n5:1:o1", review_status: "APPROVED" },
      { option_code: "Q-N5-1-O2", question_code: "Q-N5-1", option_order: 2, option_text: "B", is_correct: false, external_key: "q:n5:1:o2", review_status: "APPROVED" },
      { option_code: "Q-N5-1-O3", question_code: "Q-N5-1", option_order: 3, option_text: "C", is_correct: false, external_key: "q:n5:1:o3", review_status: "APPROVED" },
      { option_code: "Q-N5-1-O4", question_code: "Q-N5-1", option_order: 4, option_text: "D", is_correct: false, external_key: "q:n5:1:o4", review_status: "APPROVED" },
    ],
    PASSAGE: [], MEDIA: [],
    QUESTION_USAGE: [{ usage_mapping_code: "U-N5-1", question_code: "Q-N5-1", usage_code: "JLPT_MOCK_TEST",
      blueprint_code: "JLPT-N5-V1", section_code: "JLPT-N5-V1-VOCAB", active: true,
      external_key: "u:n5:1", review_status: "APPROVED" }],
    READINESS: [{ entity_type: "QUESTION", external_key: "q:n5:1", schema_status: "IMPORT_READY",
      answer_key_verified: true, provenance_complete: true, semantic_reviewed: true,
      ready_for_import: true, reviewer: "reviewer", reviewed_at: "2026-10-03" }],
  };
}

test("accepts a complete approved N5 pilot", () => {
  assert.deepEqual(validateAssessmentModel(validModel()), []);
});

test("rejects empty blueprint, invalid usage, unapproved question and JLCT", () => {
  const model = validModel();
  model.SECTION = [];
  model.QUESTION[0].source_code = "JLCT-2026";
  model.QUESTION[0].review_status = "NEEDS_REVIEW";
  model.QUESTION_USAGE[0].usage_code = "JLPT_PLACEMENT";
  const codes = validateAssessmentModel(model).map((item) => item.code);
  assert.ok(codes.includes("BLUEPRINT_SECTION_REQUIRED"));
  assert.ok(codes.includes("USAGE_SECTION_MISMATCH"));
  assert.ok(codes.includes("QUESTION_NOT_APPROVED"));
  assert.ok(codes.includes("JLCT_NOT_ALLOWED_IN_JLPT_BLUEPRINT"));
});

test("rejects cross-level and duplicate question assignment", () => {
  const model = validModel();
  model.QUESTION[0].jlpt_level = "N4";
  model.QUESTION_USAGE.push({ ...model.QUESTION_USAGE[0], usage_mapping_code: "U-N5-2", external_key: "u:n5:2" });
  const codes = validateAssessmentModel(model).map((item) => item.code);
  assert.ok(codes.includes("QUESTION_LEVEL_MISMATCH"));
  assert.ok(codes.includes("DUPLICATE_QUESTION_IN_BLUEPRINT"));
});

test("requires approved listening media and complete readiness", () => {
  const model = validModel();
  model.SECTION[0].skill = "LISTENING";
  model.QUESTION[0].skill = "LISTENING";
  model.QUESTION[0].audio_code = "A-1";
  model.MEDIA = [{ audio_code: "A-1", source_code: "JLPT-SOURCE", file_name: "a.mp3",
    external_key: "audio:1", review_status: "NEEDS_REVIEW" }];
  model.READINESS[0].provenance_complete = false;
  const codes = validateAssessmentModel(model).map((item) => item.code);
  assert.ok(codes.includes("LISTENING_MEDIA_NOT_APPROVED"));
  assert.ok(codes.includes("QUESTION_NOT_IMPORT_READY"));
});
