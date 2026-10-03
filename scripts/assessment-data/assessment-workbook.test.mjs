import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { validateAssessmentModel } from "./validate-assessment-workbook.mjs";
import { generateAssessmentSeedFromModel } from "./generate-assessment-seed.mjs";
import { SHEET_CONTRACTS } from "./lib/assessment-workbook-contract.mjs";

function validModel() {
  return {
    BLUEPRINT: [{ blueprint_code: "JLPT-N5-V1", display_name: "JLPT N5", usage_code: "JLPT_MOCK_TEST",
      jlpt_level: "N5", policy_version: "OWNER_POLICY_V1", pass_percent: 60, min_section_percent: 30, active: true,
      external_key: "exam:jlpt:n5:v1", review_status: "APPROVED" }],
    SECTION: [{ section_code: "JLPT-N5-V1-VOCAB", blueprint_code: "JLPT-N5-V1", section_order: 1,
      skill: "VOCABULARY", question_count: 1, time_limit_seconds: 600, weight_percent: 100, required: true,
      external_key: "exam:jlpt:n5:v1:vocab", review_status: "APPROVED" }],
    QUESTION: [{ question_code: "Q-N5-1", source_code: "JLPT-SOURCE", skill: "VOCABULARY",
      jlpt_level: "N5", question_type: "MULTIPLE_CHOICE", prompt_ja: "問題", correct_answer: "A",
      explanation_vi: "Giải thích", external_key: "q:n5:1", confidence: "HIGH",
      knowledge_tag_code: "N5-B01", review_status: "APPROVED", lifecycle_status: "PUBLISHED" }],
    OPTION: [
      { option_code: "Q-N5-1-O1", question_code: "Q-N5-1", option_order: 1, option_text: "A", is_correct: true, external_key: "q:n5:1:o1", review_status: "APPROVED" },
      { option_code: "Q-N5-1-O2", question_code: "Q-N5-1", option_order: 2, option_text: "B", is_correct: false, external_key: "q:n5:1:o2", review_status: "APPROVED" },
      { option_code: "Q-N5-1-O3", question_code: "Q-N5-1", option_order: 3, option_text: "C", is_correct: false, external_key: "q:n5:1:o3", review_status: "APPROVED" },
      { option_code: "Q-N5-1-O4", question_code: "Q-N5-1", option_order: 4, option_text: "D", is_correct: false, external_key: "q:n5:1:o4", review_status: "APPROVED" },
    ],
    PASSAGE: [], MEDIA: [],
    QUESTION_USAGE: [{ usage_mapping_code: "U-N5-1", question_code: "Q-N5-1", usage_code: "JLPT_MOCK_TEST",
      blueprint_code: "JLPT-N5-V1", section_code: "JLPT-N5-V1-VOCAB", active: true,
      order_index: 1, mondai_number: 1, raw_score: 1,
      external_key: "u:n5:1", review_status: "APPROVED" }],
    READINESS: [{ entity_type: "QUESTION", external_key: "q:n5:1", schema_status: "IMPORT_READY",
      answer_key_verified: true, provenance_complete: true, semantic_reviewed: true,
      ready_for_import: true, reviewer: "reviewer", reviewed_at: "2026-10-03" }],
  };
}

test("accepts a complete approved N5 pilot", () => {
  assert.deepEqual(validateAssessmentModel(validModel()), []);
});

test("workbook contract carries the stable DB lookup and assignment fields", () => {
  assert.ok(SHEET_CONTRACTS.QUESTION.includes("knowledge_tag_code"));
  assert.ok(SHEET_CONTRACTS.QUESTION_USAGE.includes("order_index"));
  assert.ok(SHEET_CONTRACTS.QUESTION_USAGE.includes("mondai_number"));
  assert.ok(SHEET_CONTRACTS.QUESTION_USAGE.includes("raw_score"));
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

test("active blueprint assignment requires stable tag and scoring metadata", () => {
  const model = validModel();
  delete model.QUESTION[0].knowledge_tag_code;
  delete model.QUESTION_USAGE[0].order_index;
  delete model.QUESTION_USAGE[0].raw_score;
  const codes = validateAssessmentModel(model).map((item) => item.code);
  assert.ok(codes.includes("KNOWLEDGE_TAG_CODE_REQUIRED"));
  assert.ok(codes.includes("QUESTION_ASSIGNMENT_METADATA_REQUIRED"));
});

test("active blueprint requires approved scoring and section timing", () => {
  const model = validModel();
  model.BLUEPRINT[0].pass_percent = null;
  model.BLUEPRINT[0].min_section_percent = 120;
  model.SECTION[0].time_limit_seconds = 0;
  const codes = validateAssessmentModel(model).map((item) => item.code);
  assert.ok(codes.includes("BLUEPRINT_SCORING_POLICY_INVALID"));
  assert.ok(codes.includes("SECTION_TIMING_INVALID"));
});

test("generates dependency-ordered deterministic assessment seeds", async () => {
  const first = await fs.mkdtemp(path.join(os.tmpdir(), "jlas-assessment-seed-a-"));
  const second = await fs.mkdtemp(path.join(os.tmpdir(), "jlas-assessment-seed-b-"));
  try {
    const manifestA = await generateAssessmentSeedFromModel(validModel(), first);
    const manifestB = await generateAssessmentSeedFromModel(validModel(), second);
    assert.deepEqual(manifestA.files, ["seed_assessment_questions.sql", "seed_jlpt_blueprints.sql"]);
    assert.deepEqual(manifestA.sha256, manifestB.sha256);
    const questionsSql = await fs.readFile(path.join(first, manifestA.files[0]), "utf8");
    const blueprintSql = await fs.readFile(path.join(first, manifestA.files[1]), "utf8");
    assert.ok(questionsSql.indexOf("INSERT INTO assessment_question") < questionsSql.indexOf("INSERT INTO assessment_question_option"));
    assert.ok(blueprintSql.indexOf("INSERT INTO jlpt_exam") < blueprintSql.indexOf("INSERT INTO jlpt_exam_section"));
    assert.ok(blueprintSql.indexOf("INSERT INTO assessment_quiz") < blueprintSql.indexOf("INSERT INTO assessment_quiz_question"));
    assert.match(blueprintSql, /INSERT INTO assessment_question_usage/);
    assert.match(questionsSql, /SELECT id FROM tag WHERE code = 'N5-B01'/);
  } finally {
    await fs.rm(first, { recursive: true, force: true });
    await fs.rm(second, { recursive: true, force: true });
  }
});

test("does not modify seed outputs when validation fails", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "jlas-assessment-seed-invalid-"));
  const existing = path.join(directory, "seed_assessment_questions.sql");
  await fs.writeFile(existing, "owner-content", "utf8");
  const model = validModel();
  model.QUESTION[0].review_status = "NEEDS_REVIEW";
  try {
    await assert.rejects(generateAssessmentSeedFromModel(model, directory), /not import-ready/);
    assert.equal(await fs.readFile(existing, "utf8"), "owner-content");
    await assert.rejects(fs.access(path.join(directory, "seed_jlpt_blueprints.sql")));
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});
