import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

function bool(value) {
  return value === true || String(value ?? "").trim().toUpperCase() === "TRUE" || String(value) === "1";
}

function countByLesson(rows) {
  const counts = new Map();
  for (const row of rows ?? []) {
    const code = String(row.lesson_code ?? "");
    counts.set(code, (counts.get(code) ?? 0) + 1);
  }
  return counts;
}

export function buildLearningCoverage(model) {
  const scopes = new Map();
  for (const row of model.LESSON_SCOPE ?? []) {
    const code = String(row.lesson_code ?? "");
    if (!scopes.has(code)) scopes.set(code, []);
    scopes.get(code).push(row);
  }
  const readiness = new Map((model.READINESS ?? []).map((row) => [String(row.external_key ?? ""), row]));
  const activityCounts = {
    vocabulary_count: countByLesson(model.VOCAB_LESSON_MAP),
    grammar_count: countByLesson(model.GRAMMAR),
    kanji_count: countByLesson(model.KANJI_LESSON_MAP),
    dialogue_count: countByLesson(model.DIALOGUE),
    reading_count: countByLesson(model.READING),
    listening_count: countByLesson(model.LISTENING),
    exercise_count: countByLesson(model.EXERCISE),
  };
  const output = [];
  for (const lesson of model.LESSON ?? []) {
    const lessonCode = String(lesson.lesson_code ?? "");
    const fallbackGoal = lesson.jlpt_level ? "JLPT" : "FOUNDATION";
    const lessonScopes = scopes.get(lessonCode) ?? [{ goal_code: fallbackGoal, topic_code: "", competency_code: "" }];
    for (const scope of lessonScopes) {
      const vocabularyCount = activityCounts.vocabulary_count.get(lessonCode) ?? 0;
      const grammarCount = activityCounts.grammar_count.get(lessonCode) ?? 0;
      const kanjiCount = activityCounts.kanji_count.get(lessonCode) ?? 0;
      const gate = readiness.get(String(lesson.external_key ?? ""));
      const row = {
        goal: scope.goal_code || "JLPT",
        level: lesson.jlpt_level || scope.topic_level || "",
        topic: scope.topic_code || "",
        competency: scope.competency_code || "",
        lesson_code: lessonCode,
        lesson_status: lesson.lifecycle_status || "",
        review_status: lesson.review_status || "",
        vocabulary_count: vocabularyCount,
        flashcard_quiz_required: vocabularyCount >= 5,
        grammar_kanji_count: grammarCount + kanjiCount,
        grammar_count: grammarCount,
        kanji_count: kanjiCount,
        dialogue_count: activityCounts.dialogue_count.get(lessonCode) ?? 0,
        reading_count: activityCounts.reading_count.get(lessonCode) ?? 0,
        listening_count: activityCounts.listening_count.get(lessonCode) ?? 0,
        exercise_count: activityCounts.exercise_count.get(lessonCode) ?? 0,
        ready_for_import: bool(gate?.ready_for_import),
      };
      const blockers = [];
      if (!row.vocabulary_count) blockers.push("VOCABULARY_MISSING");
      if (!row.grammar_kanji_count) blockers.push("GRAMMAR_KANJI_MISSING");
      if (!row.dialogue_count) blockers.push("DIALOGUE_MISSING");
      if (!row.exercise_count && (row.flashcard_quiz_required || row.grammar_kanji_count)) blockers.push("EXERCISE_MISSING");
      if (!row.ready_for_import) blockers.push("READINESS_INCOMPLETE");
      row.blockers = blockers.join("|");
      output.push(row);
    }
  }
  return output;
}

export function buildAssessmentCoverage(model) {
  const readiness = new Map((model.READINESS ?? []).map((row) => [String(row.external_key ?? ""), row]));
  const activeUsages = new Map();
  for (const usage of (model.QUESTION_USAGE ?? []).filter((row) => bool(row.active))) {
    const code = String(usage.question_code ?? "");
    activeUsages.set(code, (activeUsages.get(code) ?? 0) + 1);
  }
  const groups = new Map();
  for (const question of model.QUESTION ?? []) {
    const key = `${question.jlpt_level}|${question.skill}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(question);
  }
  return [...groups.entries()].map(([key, questions]) => {
    const [level, skill] = key.split("|");
    return {
      level, skill, question_count: questions.length,
      approved_count: questions.filter((row) => String(row.review_status).toUpperCase() === "APPROVED").length,
      import_ready_count: questions.filter((row) => bool(readiness.get(String(row.external_key ?? ""))?.ready_for_import)).length,
      active_usage_count: questions.reduce((sum, row) => sum + (activeUsages.get(String(row.question_code ?? "")) ?? 0), 0),
    };
  }).sort((left, right) => `${left.level}|${left.skill}`.localeCompare(`${right.level}|${right.skill}`));
}

function workbookModel(workbook, sheetNames) {
  const model = {};
  for (const sheetName of sheetNames) {
    const sheet = workbook.worksheets.getItem(sheetName);
    const values = sheet.getUsedRange()?.values ?? [];
    const headers = (values[3] ?? []).map((value) => String(value ?? "").trim().replace(/\*$/, ""));
    model[sheetName] = [];
    for (let rowIndex = 4; rowIndex < values.length; rowIndex += 1) {
      const source = values[rowIndex] ?? [];
      if (!source.some((value) => value !== null && value !== undefined && String(value).trim() !== "")) continue;
      const row = { __row: rowIndex + 1 };
      headers.forEach((header, column) => { if (header) row[header] = source[column] ?? null; });
      model[sheetName].push(row);
    }
  }
  return model;
}

function csvEscape(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function csv(rows) {
  const headers = rows.length ? Object.keys(rows[0]) : [];
  return `${headers.join(",")}\n${rows.map((row) => headers.map((header) => csvEscape(row[header])).join(",")).join("\n")}\n`;
}

function semanticSampling(model) {
  const rows = [];
  const policies = {
    GRAMMAR: "TRANSLATION_AND_EXAMPLE_100_PERCENT",
    READING: "PASSAGE_AND_ANSWER_KEY_100_PERCENT",
    LISTENING: "MEDIA_TRANSCRIPT_AND_ANSWER_KEY_100_PERCENT",
    DIALOGUE: "TRANSLATION_AND_AUDIO_100_PERCENT",
    EXERCISE: "ANSWER_KEY_100_PERCENT",
  };
  for (const [sheet, policy] of Object.entries(policies)) {
    for (const row of model[sheet] ?? []) {
      const externalKey = row.external_key ?? row[Object.keys(row).find((key) => key.endsWith("_code"))];
      rows.push({ sheet, source_row: row.__row, external_key: externalKey ?? "", review_status: row.review_status ?? "", policy,
        sampled: false, reviewer: "", reviewed_at: "", decision: "PENDING" });
    }
  }
  return rows;
}

export async function buildReports({ learningPath, assessmentPath, outputDirectory }) {
  const learning = await SpreadsheetFile.importXlsx(await FileBlob.load(learningPath));
  const assessment = await SpreadsheetFile.importXlsx(await FileBlob.load(assessmentPath));
  const learningModel = workbookModel(learning, ["LESSON", "LESSON_SCOPE", "VOCAB_LESSON_MAP", "GRAMMAR", "KANJI_LESSON_MAP", "DIALOGUE", "READING", "LISTENING", "EXERCISE", "READINESS"]);
  const assessmentModel = workbookModel(assessment, ["QUESTION", "QUESTION_USAGE", "READINESS"]);
  const learningCoverage = buildLearningCoverage(learningModel);
  const assessmentCoverage = buildAssessmentCoverage(assessmentModel);
  const sampling = semanticSampling(learningModel);
  await fs.mkdir(outputDirectory, { recursive: true });
  await fs.writeFile(path.join(outputDirectory, "content-coverage.csv"), csv(learningCoverage), "utf8");
  await fs.writeFile(path.join(outputDirectory, "blueprint-coverage.csv"), csv(assessmentCoverage), "utf8");
  await fs.writeFile(path.join(outputDirectory, "semantic-sampling.csv"), csv(sampling), "utf8");
  return { learningRows: learningCoverage.length, assessmentRows: assessmentCoverage.length, semanticRows: sampling.length };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [learningPath, assessmentPath, outputDirectory] = process.argv.slice(2).map((value) => path.resolve(value));
  if (!outputDirectory) throw new Error("Usage: build_release_coverage_reports.mjs <learning.xlsx> <assessment.xlsx> <output-dir>");
  console.log(JSON.stringify(await buildReports({ learningPath, assessmentPath, outputDirectory })));
}
