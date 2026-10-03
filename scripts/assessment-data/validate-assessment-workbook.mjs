import path from "node:path";
import { pathToFileURL } from "node:url";

import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";
import { asBoolean, REVIEW_STATUSES, semanticReady, SHEET_CONTRACTS, USAGE_CODES } from "./lib/assessment-workbook-contract.mjs";

function issue(code, sheet, row, message) {
  return { severity: "ERROR", code, sheet, row: row?.__row ?? null, message };
}

function mapBy(rows, key) {
  return new Map((rows ?? []).map((row) => [String(row[key] ?? ""), row]));
}

function upper(value) {
  return String(value ?? "").trim().toUpperCase();
}

function rowsFor(workbook, sheetName) {
  const sheet = workbook.worksheets.getItem(sheetName);
  const values = sheet.getUsedRange()?.values ?? [];
  const rawHeaders = values[3] ?? [];
  const headers = rawHeaders.map((value) => String(value ?? "").trim().replace(/\*$/, ""));
  const rows = [];
  for (let rowIndex = 4; rowIndex < values.length; rowIndex += 1) {
    const valuesForRow = values[rowIndex] ?? [];
    if (!valuesForRow.some((value) => value !== null && value !== undefined && String(value).trim() !== "")) continue;
    const row = { __row: rowIndex + 1 };
    headers.forEach((header, column) => { if (header) row[header] = valuesForRow[column] ?? null; });
    rows.push(row);
  }
  return { headers, rows };
}

export async function loadAssessmentWorkbook(inputPath) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(inputPath));
  const model = {};
  const headerIssues = [];
  for (const [sheetName, required] of Object.entries(SHEET_CONTRACTS)) {
    let table;
    try {
      table = rowsFor(workbook, sheetName);
    } catch {
      model[sheetName] = [];
      headerIssues.push(issue("SHEET_REQUIRED", sheetName, null, `Missing sheet ${sheetName}`));
      continue;
    }
    model[sheetName] = table.rows;
    for (const header of required) {
      if (!table.headers.includes(header)) headerIssues.push(issue("COLUMN_REQUIRED", sheetName, null, `Missing column ${header}`));
    }
  }
  return { model, headerIssues };
}

export function validateAssessmentModel(model) {
  const issues = [];
  const blueprints = model.BLUEPRINT ?? [];
  const sections = model.SECTION ?? [];
  const questions = model.QUESTION ?? [];
  const options = model.OPTION ?? [];
  const media = model.MEDIA ?? [];
  const usages = model.QUESTION_USAGE ?? [];
  const readiness = model.READINESS ?? [];
  const blueprintByCode = mapBy(blueprints, "blueprint_code");
  const sectionByCode = mapBy(sections, "section_code");
  const questionByCode = mapBy(questions, "question_code");
  const mediaByCode = mapBy(media, "audio_code");
  const readinessByKey = mapBy(readiness, "external_key");

  for (const [sheetName, rows] of Object.entries(model)) {
    const externalKeys = new Set();
    for (const row of rows ?? []) {
      if ("external_key" in row) {
        const key = String(row.external_key ?? "").trim();
        if (!key) issues.push(issue("EXTERNAL_KEY_REQUIRED", sheetName, row, "Stable external_key is required"));
        else if (externalKeys.has(key)) issues.push(issue("DUPLICATE_EXTERNAL_KEY", sheetName, row, `Duplicate external_key ${key}`));
        else externalKeys.add(key);
      }
      if ("review_status" in row && !REVIEW_STATUSES.includes(upper(row.review_status))) {
        issues.push(issue("REVIEW_STATUS_INVALID", sheetName, row, `Invalid review_status ${row.review_status}`));
      }
    }
  }

  const activeBlueprints = blueprints.filter((row) => asBoolean(row.active));
  for (const blueprint of activeBlueprints) {
    const blueprintSections = sections.filter((row) => String(row.blueprint_code) === String(blueprint.blueprint_code));
    if (!blueprintSections.length) issues.push(issue("BLUEPRINT_SECTION_REQUIRED", "BLUEPRINT", blueprint, `Active blueprint ${blueprint.blueprint_code} has no section`));
    if (!String(blueprint.policy_version ?? "").trim()) issues.push(issue("POLICY_VERSION_REQUIRED", "BLUEPRINT", blueprint, "Active blueprint needs policy_version"));
    if (upper(blueprint.review_status) !== "APPROVED") issues.push(issue("BLUEPRINT_NOT_APPROVED", "BLUEPRINT", blueprint, "Active blueprint must be approved"));
  }

  const assignments = new Set();
  const counts = new Map();
  for (const usage of usages.filter((row) => asBoolean(row.active))) {
    const blueprint = blueprintByCode.get(String(usage.blueprint_code ?? ""));
    const section = sectionByCode.get(String(usage.section_code ?? ""));
    const question = questionByCode.get(String(usage.question_code ?? ""));
    if (!USAGE_CODES.includes(upper(usage.usage_code))) issues.push(issue("USAGE_CODE_INVALID", "QUESTION_USAGE", usage, `Invalid usage ${usage.usage_code}`));
    if (!blueprint || !section || String(section.blueprint_code) !== String(usage.blueprint_code)
      || upper(usage.usage_code) !== upper(blueprint?.usage_code)) {
      issues.push(issue("USAGE_SECTION_MISMATCH", "QUESTION_USAGE", usage, "Usage must match its blueprint and section"));
    }
    if (!question) {
      issues.push(issue("QUESTION_REQUIRED", "QUESTION_USAGE", usage, `Unknown question ${usage.question_code}`));
      continue;
    }
    if (upper(question.review_status) !== "APPROVED" || upper(question.lifecycle_status) !== "PUBLISHED") {
      issues.push(issue("QUESTION_NOT_APPROVED", "QUESTION", question, `Question ${question.question_code} is not approved and published`));
    }
    if (!semanticReady(readinessByKey.get(String(question.external_key)))) {
      issues.push(issue("QUESTION_NOT_IMPORT_READY", "QUESTION", question, `Question ${question.question_code} lacks readiness evidence`));
    }
    if (blueprint && upper(question.jlpt_level) !== upper(blueprint.jlpt_level)) {
      issues.push(issue("QUESTION_LEVEL_MISMATCH", "QUESTION_USAGE", usage, "Question level differs from blueprint level"));
    }
    if (section && upper(question.skill) !== upper(section.skill)) {
      issues.push(issue("QUESTION_SKILL_MISMATCH", "QUESTION_USAGE", usage, "Question skill differs from section skill"));
    }
    const assignmentKey = `${usage.blueprint_code}|${usage.question_code}`;
    if (assignments.has(assignmentKey)) issues.push(issue("DUPLICATE_QUESTION_IN_BLUEPRINT", "QUESTION_USAGE", usage, "Question is assigned twice in one blueprint"));
    assignments.add(assignmentKey);
    const sectionCode = String(usage.section_code ?? "");
    counts.set(sectionCode, (counts.get(sectionCode) ?? 0) + 1);
    if (/^JLCT(?:\b|-)/i.test(String(question.source_code ?? "")) && /^JLPT_/i.test(upper(usage.usage_code))) {
      issues.push(issue("JLCT_NOT_ALLOWED_IN_JLPT_BLUEPRINT", "QUESTION_USAGE", usage, "JLCT is practice-only"));
    }
    if (upper(question.skill) === "LISTENING") {
      const audio = mediaByCode.get(String(question.audio_code ?? ""));
      if (!audio || upper(audio.review_status) !== "APPROVED") {
        issues.push(issue("LISTENING_MEDIA_NOT_APPROVED", "QUESTION", question, "Listening question needs approved media"));
      }
    }
  }

  for (const section of sections) {
    const expected = Number(section.question_count ?? 0);
    if (counts.get(String(section.section_code)) !== undefined && counts.get(String(section.section_code)) !== expected) {
      issues.push(issue("SECTION_QUESTION_COUNT_MISMATCH", "SECTION", section,
        `Expected ${expected}, mapped ${counts.get(String(section.section_code))}`));
    }
  }

  const optionsByQuestion = new Map();
  for (const option of options) {
    const code = String(option.question_code ?? "");
    if (!optionsByQuestion.has(code)) optionsByQuestion.set(code, []);
    optionsByQuestion.get(code).push(option);
  }
  for (const question of questions.filter((row) => upper(row.question_type) === "MULTIPLE_CHOICE")) {
    const questionOptions = optionsByQuestion.get(String(question.question_code)) ?? [];
    const correct = questionOptions.filter((row) => asBoolean(row.is_correct));
    if (questionOptions.length !== 4 || correct.length !== 1 || String(correct[0]?.option_text ?? "") !== String(question.correct_answer ?? "")) {
      issues.push(issue("MCQ_ANSWER_INVALID", "QUESTION", question, "MCQ needs four options and exactly one matching correct answer"));
    }
  }
  return issues;
}

export async function validateAssessmentWorkbook(inputPath) {
  const { model, headerIssues } = await loadAssessmentWorkbook(inputPath);
  return [...headerIssues, ...validateAssessmentModel(model)];
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const inputIndex = process.argv.indexOf("--input");
  if (inputIndex < 0 || !process.argv[inputIndex + 1]) throw new Error("Usage: node validate-assessment-workbook.mjs --input <workbook.xlsx>");
  const input = path.resolve(process.argv[inputIndex + 1]);
  const issues = await validateAssessmentWorkbook(input);
  console.log(JSON.stringify({ input, errors: issues.length, issues }, null, 2));
  process.exitCode = issues.length ? 1 : 0;
}
