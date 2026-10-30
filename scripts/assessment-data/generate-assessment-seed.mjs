import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { asBoolean } from "./lib/assessment-workbook-contract.mjs";
import { sha256, sqlBoolean, sqlFooter, sqlHeader, sqlNumber, sqlString } from "./lib/sql-writer.mjs";
import { loadAssessmentWorkbook, validateAssessmentModel } from "./validate-assessment-workbook.mjs";

function upper(value) {
  return String(value ?? "").trim().toUpperCase();
}

function groupBy(rows, field) {
  const grouped = new Map();
  for (const row of rows ?? []) {
    const key = String(row[field] ?? "");
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(row);
  }
  return grouped;
}

function sectionType(skill) {
  const normalized = upper(skill);
  if (normalized === "READING" || normalized === "LISTENING") return normalized;
  return "VOCAB_GRAMMAR";
}

function questionSql(model) {
  const lines = [sqlHeader("questions")];
  const optionsByQuestion = groupBy(model.OPTION, "question_code");
  const passageByCode = new Map((model.PASSAGE ?? []).map((row) => [String(row.passage_code), row]));
  const mediaByCode = new Map((model.MEDIA ?? []).map((row) => [String(row.audio_code), row]));
  const activeCodes = new Set((model.QUESTION_USAGE ?? []).filter((row) => asBoolean(row.active)).map((row) => String(row.question_code)));
  const questions = (model.QUESTION ?? []).filter((row) => activeCodes.has(String(row.question_code)))
    .sort((left, right) => String(left.question_code).localeCompare(String(right.question_code)));

  questions.forEach((question, index) => {
    const number = index + 1;
    const options = [...(optionsByQuestion.get(String(question.question_code)) ?? [])]
      .sort((left, right) => Number(left.option_order) - Number(right.option_order));
    const correct = options.findIndex((row) => asBoolean(row.is_correct)) + 1;
    const passage = passageByCode.get(String(question.passage_code ?? ""));
    const media = mediaByCode.get(String(question.audio_code ?? ""));
    const audioUrl = media?.storage_uri || media?.file_name || null;
    lines.push(`SET @knowledge_tag_${number} := (SELECT id FROM tag WHERE code = ${sqlString(question.knowledge_tag_code)} AND tag_type = 'KNOWLEDGE' LIMIT 1);`);
    lines.push(`INSERT INTO assessment_question (source_code, knowledge_tag_id, skill, jlpt_level, difficulty, question_type, title, passage_html, audio_url, question_text, answer_text, option_1, option_2, option_3, option_4, correct_option, explanation, context_json, is_active)`);
    lines.push(`VALUES (${sqlString(question.question_code)}, @knowledge_tag_${number}, ${sqlString(upper(question.skill))}, ${sqlString(upper(question.jlpt_level))}, ${sqlString(upper(question.difficulty || "MEDIUM"))}, ${sqlString(upper(question.question_type))}, ${sqlString(question.question_code)}, ${sqlString(passage?.passage_ja)}, ${sqlString(audioUrl)}, ${sqlString(question.prompt_ja)}, ${sqlString(question.correct_answer)}, ${sqlString(options[0]?.option_text)}, ${sqlString(options[1]?.option_text)}, ${sqlString(options[2]?.option_text)}, ${sqlString(options[3]?.option_text)}, ${correct || "NULL"}, ${sqlString(question.explanation_vi)}, ${sqlString(question.context_json)}, 1)`);
    lines.push(`ON DUPLICATE KEY UPDATE knowledge_tag_id = VALUES(knowledge_tag_id), skill = VALUES(skill), jlpt_level = VALUES(jlpt_level), difficulty = VALUES(difficulty), question_type = VALUES(question_type), title = VALUES(title), passage_html = VALUES(passage_html), audio_url = VALUES(audio_url), question_text = VALUES(question_text), answer_text = VALUES(answer_text), option_1 = VALUES(option_1), option_2 = VALUES(option_2), option_3 = VALUES(option_3), option_4 = VALUES(option_4), correct_option = VALUES(correct_option), explanation = VALUES(explanation), context_json = VALUES(context_json), is_active = VALUES(is_active);`);
    lines.push(`SET @question_${number} := (SELECT id FROM assessment_question WHERE source_code = ${sqlString(question.question_code)} LIMIT 1);`);
    for (const option of options) {
      lines.push(`INSERT INTO assessment_question_option (question_id, content, is_correct, order_index) VALUES (@question_${number}, ${sqlString(option.option_text)}, ${sqlBoolean(option.is_correct)}, ${sqlNumber(option.option_order)}) ON DUPLICATE KEY UPDATE content = VALUES(content), is_correct = VALUES(is_correct);`);
    }
    lines.push("");
  });
  lines.push(sqlFooter());
  return lines.join("\n");
}

function blueprintSql(model) {
  const lines = [sqlHeader("JLPT blueprints")];
  const questionsByCode = new Map((model.QUESTION ?? []).map((row) => [String(row.question_code), row]));
  const activeUsages = (model.QUESTION_USAGE ?? []).filter((row) => asBoolean(row.active));
  const usageBySection = groupBy(activeUsages, "section_code");
  const blueprints = (model.BLUEPRINT ?? []).filter((row) => asBoolean(row.active))
    .sort((left, right) => String(left.blueprint_code).localeCompare(String(right.blueprint_code)));

  blueprints.forEach((blueprint, blueprintIndex) => {
    const number = blueprintIndex + 1;
    const sections = (model.SECTION ?? []).filter((row) => String(row.blueprint_code) === String(blueprint.blueprint_code))
      .sort((left, right) => Number(left.section_order) - Number(right.section_order));
    const totalMaxScore = sections.reduce((sum, section) => sum + (usageBySection.get(String(section.section_code)) ?? [])
      .reduce((score, usage) => score + Number(usage.raw_score), 0), 0);
    const passingScore = totalMaxScore * Number(blueprint.pass_percent) / 100;
    const examTitle = `[${blueprint.blueprint_code}] ${blueprint.display_name}`;
    lines.push(`INSERT INTO jlpt_exam (title, jlpt_level, passing_score, policy_version, status) SELECT ${sqlString(examTitle)}, ${sqlString(upper(blueprint.jlpt_level))}, ${sqlNumber(passingScore)}, ${sqlString(blueprint.policy_version)}, 'PUBLISHED' WHERE NOT EXISTS (SELECT 1 FROM jlpt_exam WHERE title = ${sqlString(examTitle)} AND jlpt_level = ${sqlString(upper(blueprint.jlpt_level))});`);
    lines.push(`UPDATE jlpt_exam SET passing_score = ${sqlNumber(passingScore)}, policy_version = ${sqlString(blueprint.policy_version)}, status = 'PUBLISHED' WHERE title = ${sqlString(examTitle)} AND jlpt_level = ${sqlString(upper(blueprint.jlpt_level))};`);
    lines.push(`SET @exam_${number} := (SELECT id FROM jlpt_exam WHERE title = ${sqlString(examTitle)} AND jlpt_level = ${sqlString(upper(blueprint.jlpt_level))} LIMIT 1);`);

    sections.forEach((section, sectionIndex) => {
      const sectionNumber = `${number}_${sectionIndex + 1}`;
      const quizTitle = `[${section.section_code}] ${section.skill}`;
      const assignments = [...(usageBySection.get(String(section.section_code)) ?? [])]
        .sort((left, right) => Number(left.order_index) - Number(right.order_index));
      const maxScore = assignments.reduce((sum, row) => sum + Number(row.raw_score), 0);
      const minScore = maxScore * Number(blueprint.min_section_percent) / 100;
      const duration = Math.max(1, Math.ceil(Number(section.time_limit_seconds || 60) / 60));
      lines.push(`INSERT INTO assessment_quiz (lesson_id, unit_id, title, quiz_type, status) SELECT NULL, NULL, ${sqlString(quizTitle)}, 'JLPT_SECTION', 'PUBLISHED' WHERE NOT EXISTS (SELECT 1 FROM assessment_quiz WHERE title = ${sqlString(quizTitle)} AND quiz_type = 'JLPT_SECTION');`);
      lines.push(`UPDATE assessment_quiz SET status = 'PUBLISHED' WHERE title = ${sqlString(quizTitle)} AND quiz_type = 'JLPT_SECTION';`);
      lines.push(`SET @quiz_${sectionNumber} := (SELECT id FROM assessment_quiz WHERE title = ${sqlString(quizTitle)} AND quiz_type = 'JLPT_SECTION' LIMIT 1);`);
      lines.push(`INSERT INTO jlpt_exam_section (exam_id, quiz_id, section_type, duration_minutes, max_score, min_passing_score, order_index) VALUES (@exam_${number}, @quiz_${sectionNumber}, ${sqlString(sectionType(section.skill))}, ${sqlNumber(duration)}, ${sqlNumber(maxScore)}, ${sqlNumber(minScore)}, ${sqlNumber(section.section_order)}) ON DUPLICATE KEY UPDATE quiz_id = VALUES(quiz_id), section_type = VALUES(section_type), duration_minutes = VALUES(duration_minutes), max_score = VALUES(max_score), min_passing_score = VALUES(min_passing_score);`);
      for (const assignment of assignments) {
        const question = questionsByCode.get(String(assignment.question_code));
        lines.push(`SET @assigned_question := (SELECT id FROM assessment_question WHERE source_code = ${sqlString(question.question_code)} LIMIT 1);`);
        lines.push(`INSERT INTO assessment_quiz_question (quiz_id, question_id, order_index, mondai_number, raw_score) VALUES (@quiz_${sectionNumber}, @assigned_question, ${sqlNumber(assignment.order_index)}, ${sqlNumber(assignment.mondai_number, "NULL")}, ${sqlNumber(assignment.raw_score)}) ON DUPLICATE KEY UPDATE order_index = VALUES(order_index), mondai_number = VALUES(mondai_number), raw_score = VALUES(raw_score);`);
        lines.push(`INSERT INTO assessment_question_usage (question_id, usage_code, blueprint_code, section_code, active, effective_from, effective_to, external_key, review_status) VALUES (@assigned_question, ${sqlString(upper(assignment.usage_code))}, ${sqlString(assignment.blueprint_code)}, ${sqlString(assignment.section_code)}, 1, ${sqlString(assignment.effective_from)}, ${sqlString(assignment.effective_to)}, ${sqlString(assignment.external_key)}, 'APPROVED') ON DUPLICATE KEY UPDATE question_id = VALUES(question_id), usage_code = VALUES(usage_code), blueprint_code = VALUES(blueprint_code), section_code = VALUES(section_code), active = VALUES(active), effective_from = VALUES(effective_from), effective_to = VALUES(effective_to), review_status = VALUES(review_status);`);
      }
      lines.push("");
    });
  });
  lines.push(sqlFooter());
  return lines.join("\n");
}

export async function generateAssessmentSeedFromModel(model, outputDirectory) {
  const issues = validateAssessmentModel(model);
  if (issues.length) {
    const error = new Error(`Assessment workbook is not import-ready (${issues.length} issues)`);
    error.issues = issues;
    throw error;
  }
  const activeBlueprints = (model.BLUEPRINT ?? []).filter((row) => asBoolean(row.active)
    && upper(row.review_status) === "APPROVED");
  const activeAssignments = (model.QUESTION_USAGE ?? []).filter((row) => asBoolean(row.active));
  if (!activeBlueprints.length || !activeAssignments.length) {
    throw new Error("Assessment workbook has no active approved blueprint and question assignments; refusing to generate a production seed");
  }
  const files = ["seed_assessment_questions.sql", "seed_jlpt_blueprints.sql"];
  const contents = [questionSql(model), blueprintSql(model)];
  await fs.mkdir(outputDirectory, { recursive: true });
  for (let index = 0; index < files.length; index += 1) {
    await fs.writeFile(path.join(outputDirectory, files[index]), contents[index], "utf8");
  }
  return { files, sha256: Object.fromEntries(files.map((file, index) => [file, sha256(contents[index])])) };
}

export async function generateAssessmentSeed(inputPath, outputDirectory) {
  const { model, headerIssues } = await loadAssessmentWorkbook(inputPath);
  if (headerIssues.length) {
    const error = new Error(`Assessment workbook contract is invalid (${headerIssues.length} issues)`);
    error.issues = headerIssues;
    throw error;
  }
  return generateAssessmentSeedFromModel(model, outputDirectory);
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const inputIndex = process.argv.indexOf("--input");
  const outputIndex = process.argv.indexOf("--output");
  if (inputIndex < 0 || outputIndex < 0) throw new Error("Usage: node generate-assessment-seed.mjs --input <workbook.xlsx> --output <directory>");
  console.log(JSON.stringify(await generateAssessmentSeed(path.resolve(process.argv[inputIndex + 1]), path.resolve(process.argv[outputIndex + 1])), null, 2));
}
