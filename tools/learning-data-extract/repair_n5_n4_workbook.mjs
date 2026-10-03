import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const SENTINELS = new Set(["", "-", "dummy", "placeholder", "undefined", "null", "n/a"]);

function key(value) {
  return String(value ?? "").normalize("NFKC").trim();
}

function isSentinel(value) {
  return SENTINELS.has(key(value).toLocaleLowerCase("en"));
}

export function buildUniqueIndex(rows, field) {
  const index = new Map();
  for (const row of rows) {
    const value = key(row[field]);
    if (!value) continue;
    if (!index.has(value)) index.set(value, []);
    index.get(value).push(row);
  }
  return index;
}

export function resolveUniqueField(index, lookup, field) {
  const values = [...new Set((index.get(key(lookup)) ?? [])
    .map((row) => key(row[field]))
    .filter((value) => !isSentinel(value)))];
  return values.length === 1 ? values[0] : null;
}

function japaneseComparable(value) {
  return key(value).replace(/[^\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}ー々]/gu, "");
}

export function resolveContainedLessonReading(expression, lessonRows) {
  const compact = japaneseComparable(expression);
  if (!compact) return null;
  const candidates = lessonRows.filter((row) => {
    const master = japaneseComparable(row.expression);
    return master && (compact.includes(master) || master.endsWith(compact));
  });
  const expressions = new Set(candidates.map((row) => japaneseComparable(row.expression)));
  const readings = new Set(candidates.map((row) => key(row.reading)).filter((reading) => !isSentinel(reading)));
  return expressions.size === 1 && readings.size === 1 ? [...readings][0] : null;
}

export function compactLessonFromMaster(lessonCode) {
  const match = String(lessonCode ?? "").match(/^LESSON-(\d+)$/);
  if (!match) return null;
  const number = Number(match[1]);
  if (number >= 1 && number <= 25) return `N5-L${String(number).padStart(2, "0")}`;
  if (number >= 26 && number <= 47) return `N4-L${String(number - 25).padStart(2, "0")}`;
  return null;
}

export function findExistingCompoundCandidate(kanji, vocabularyRows) {
  const character = String(kanji.character ?? "");
  const matches = vocabularyRows.filter((row) =>
    row.lesson_code === kanji.lesson_code
      && String(row.expression ?? "").includes(character)
      && !isSentinel(row.reading));
  if (matches.length !== 1) return null;
  const row = matches[0];
  return {
    membershipCode: row.membership_code,
    expression: row.expression,
    reading: row.reading,
    characterPosition: Array.from(String(row.expression)).indexOf(character) + 1,
  };
}

export function hasFormulaErrors(ndjson) {
  return String(ndjson ?? "").split(/\r?\n/).some((line) => {
    if (!line.trim()) return false;
    try {
      return JSON.parse(line).kind === "match";
    } catch {
      return false;
    }
  });
}

function model(workbook, sheetName) {
  const sheet = workbook.worksheets.getItem(sheetName);
  const values = sheet.getUsedRange()?.values ?? [];
  const headers = (values[3] ?? []).map((value) => String(value ?? "").trim().replace(/\*$/, ""));
  const rows = [];
  for (let rowIndex = 4; rowIndex < values.length; rowIndex += 1) {
    const source = values[rowIndex] ?? [];
    if (!source.some((value) => value !== null && value !== undefined && String(value).trim() !== "")) continue;
    const row = { __rowIndex: rowIndex, __row: rowIndex + 1 };
    headers.forEach((header, column) => { if (header) row[header] = source[column] ?? null; });
    rows.push(row);
  }
  return { sheet, headers, rows };
}

function columnIndex(headers, name) {
  const index = headers.indexOf(name);
  if (index < 0) throw new Error(`Missing column ${name}`);
  return index;
}

function setCell(modelValue, row, field, value) {
  modelValue.sheet.getCell(row.__rowIndex, columnIndex(modelValue.headers, field)).values = [[value]];
  row[field] = value;
}

function csvEscape(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function csv(rows) {
  const headers = ["sheet", "row", "field", "old_value", "new_value", "source_locator", "reason", "review_status"];
  return `${headers.join(",")}\n${rows.map((row) => headers.map((header) => csvEscape(row[header])).join(",")).join("\n")}\n`;
}

function logChange(log, { sheet, row, field, oldValue, newValue, source, reason }) {
  log.push({
    sheet,
    row,
    field,
    old_value: oldValue,
    new_value: newValue,
    source_locator: source,
    reason,
    review_status: "NEEDS_REVIEW",
  });
}

async function repair({ compactPath, masterPath, outputPath, logPath, previewPath }) {
  const compact = await SpreadsheetFile.importXlsx(await FileBlob.load(compactPath));
  const master = await SpreadsheetFile.importXlsx(await FileBlob.load(masterPath));
  const vocab = model(compact, "LESSON_VOCAB");
  const compounds = model(compact, "KANJI_COMPOUND");
  const grammar = model(compact, "LESSON_GRAMMAR");
  const kanji = model(compact, "LESSON_KANJI");
  const masterVocab = model(master, "VOCABULARY").rows;
  const masterVocabMap = model(master, "VOCAB_LESSON_MAP").rows;
  const masterGrammar = model(master, "GRAMMAR").rows;
  const vocabIndex = buildUniqueIndex(masterVocab, "expression");
  const masterVocabByCode = new Map(masterVocab.map((row) => [String(row.vocab_code), row]));
  const vocabByCompactLesson = new Map();
  for (const mapping of masterVocabMap) {
    const compactLesson = compactLessonFromMaster(mapping.lesson_code);
    const source = masterVocabByCode.get(String(mapping.vocab_code));
    if (!compactLesson || !source) continue;
    if (!vocabByCompactLesson.has(compactLesson)) vocabByCompactLesson.set(compactLesson, []);
    vocabByCompactLesson.get(compactLesson).push(source);
  }
  const repairLog = [];

  for (const row of vocab.rows) {
    if (!isSentinel(row.reading)) continue;
    const exactReading = resolveUniqueField(vocabIndex, row.expression, "reading");
    const containedReading = exactReading ?? resolveContainedLessonReading(
      row.expression, vocabByCompactLesson.get(row.lesson_code) ?? []);
    const reading = containedReading;
    if (!reading) continue;
    const sourceRows = vocabIndex.get(key(row.expression));
    setCell(vocab, row, "reading", reading);
    logChange(repairLog, {
      sheet: "LESSON_VOCAB", row: row.__row, field: "reading", oldValue: "-", newValue: reading,
      source: (sourceRows ?? vocabByCompactLesson.get(row.lesson_code) ?? [])
        .filter((sourceRow) => key(sourceRow.reading) === reading)
        .map((sourceRow) => sourceRow.external_key).filter(Boolean).join("|"),
      reason: exactReading ? "UNIQUE_MASTER_EXPRESSION_READING" : "UNIQUE_SAME_LESSON_CONTAINED_EXPRESSION",
    });
  }

  const vocabByCode = new Map(vocab.rows.map((row) => [String(row.membership_code), row]));
  for (const row of compounds.rows) {
    if (!isSentinel(row.vocabulary_reading)) continue;
    const referenced = vocabByCode.get(String(row.vocabulary_membership_code));
    const reading = referenced && !isSentinel(referenced.reading)
      ? String(referenced.reading)
      : resolveUniqueField(vocabIndex, row.vocabulary_expression, "reading");
    if (!reading) continue;
    setCell(compounds, row, "vocabulary_reading", reading);
    logChange(repairLog, {
      sheet: "KANJI_COMPOUND", row: row.__row, field: "vocabulary_reading", oldValue: "-", newValue: reading,
      source: referenced?.membership_code ?? `master:vocabulary:${row.vocabulary_expression}`,
      reason: referenced ? "REFERENCED_LESSON_VOCAB_READING" : "UNIQUE_MASTER_EXPRESSION_READING",
    });
  }

  const grammarByLesson = new Map();
  for (const row of masterGrammar) {
    const lessonCode = compactLessonFromMaster(row.lesson_code);
    if (!lessonCode) continue;
    if (!grammarByLesson.has(lessonCode)) grammarByLesson.set(lessonCode, []);
    grammarByLesson.get(lessonCode).push(row);
  }
  for (const rows of grammarByLesson.values()) rows.sort((left, right) => Number(left.grammar_code.replace(/\D/g, "")) - Number(right.grammar_code.replace(/\D/g, "")));

  for (const row of grammar.rows) {
    if (!isSentinel(row.pattern) && !isSentinel(row.meaning_vi)) continue;
    const candidate = (grammarByLesson.get(row.lesson_code) ?? [])[Number(row.order_index) - 1];
    if (!candidate || isSentinel(candidate.pattern) || isSentinel(candidate.meaning_vi)) continue;
    for (const field of ["pattern", "meaning_vi"]) {
      if (!isSentinel(row[field])) continue;
      const oldValue = row[field];
      setCell(grammar, row, field, candidate[field]);
      logChange(repairLog, {
        sheet: "LESSON_GRAMMAR", row: row.__row, field, oldValue, newValue: candidate[field],
        source: candidate.external_key ?? candidate.grammar_code,
        reason: "MASTER_LESSON_ORDER_CANDIDATE",
      });
    }
  }

  const linkedKanji = new Set(compounds.rows.map((row) => String(row.kanji_membership_code)));
  const appendRows = [];
  for (const row of kanji.rows) {
    if (!row.lesson_code || linkedKanji.has(String(row.membership_code))) continue;
    const candidate = findExistingCompoundCandidate(row, vocab.rows);
    if (!candidate) continue;
    const linkCode = `N4-KC-REPAIR-${String(row.membership_code).replace(/^N4-KANJI-/, "")}`;
    const outputRow = new Array(compounds.headers.length).fill(null);
    const values = {
      link_code: linkCode,
      kanji_membership_code: row.membership_code,
      vocabulary_membership_code: candidate.membershipCode,
      character: row.character,
      vocabulary_expression: candidate.expression,
      vocabulary_reading: candidate.reading,
      character_position: candidate.characterPosition,
    };
    for (const [field, value] of Object.entries(values)) outputRow[columnIndex(compounds.headers, field)] = value;
    appendRows.push(outputRow);
    logChange(repairLog, {
      sheet: "KANJI_COMPOUND", row: compounds.rows.length + appendRows.length + 4, field: "new_row",
      oldValue: "", newValue: linkCode, source: candidate.membershipCode,
      reason: "UNIQUE_EXISTING_SAME_LESSON_VOCAB",
    });
  }
  if (appendRows.length) {
    compounds.sheet.getRangeByIndexes(compounds.sheet.getUsedRange().rowCount, 0, appendRows.length, compounds.headers.length).values = appendRows;
  }

  const errors = await compact.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
    options: { useRegex: true, maxResults: 100 },
    summary: "repair formula error scan",
  });
  if (hasFormulaErrors(errors.ndjson)) throw new Error(`Formula errors after repair: ${errors.ndjson}`);

  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  const preview = await compact.render({ sheetName: "KANJI_COMPOUND", range: "A1:H25", scale: 1.5, format: "png" });
  await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
  const output = await SpreadsheetFile.exportXlsx(compact);
  await output.save(outputPath);
  await fs.writeFile(logPath, csv(repairLog), "utf8");
  return { changes: repairLog.length, appendedCompoundRows: appendRows.length };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [compactPath, masterPath, outputPath, logPath, previewPath] = process.argv.slice(2).map((value) => path.resolve(value));
  if (!previewPath) throw new Error("Usage: repair_n5_n4_workbook.mjs <compact.xlsx> <master.xlsx> <output.xlsx> <repair.csv> <preview.png>");
  console.log(JSON.stringify(await repair({ compactPath, masterPath, outputPath, logPath, previewPath })));
}
