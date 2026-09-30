import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";
import { pathToFileURL } from "node:url";
import {
  CANONICAL_SHEETS,
  CODE_COLUMNS,
  IMPORT_QUESTION_TYPES,
  SHEET_CONTRACTS,
  normalizeHeader,
} from "./lib/workbook-contract.mjs";

function issue(code, sheet, row, message, severity = "ERROR") {
  return { severity, code, sheet, row, message };
}

function readSheet(workbook, sheetName) {
  let sheet;
  try {
    sheet = workbook.worksheets.getItem(sheetName);
  } catch {
    return null;
  }
  const values = sheet.getUsedRange()?.values ?? [];
  const rawHeaders = values[3] ?? [];
  const headers = rawHeaders.map(normalizeHeader);
  const rows = [];
  for (let index = 4; index < values.length; index += 1) {
    const valuesRow = values[index] ?? [];
    if (!valuesRow.some((value) => value !== null && value !== undefined && String(value).trim() !== "")) continue;
    const row = { __row: index + 1 };
    headers.forEach((header, column) => {
      if (header) row[header] = valuesRow[column] ?? null;
    });
    rows.push(row);
  }
  return { headers, rows };
}

export async function loadWorkbookModel(workbookPath) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(workbookPath));
  const model = {};
  for (const sheetName of Object.keys(SHEET_CONTRACTS)) {
    model[sheetName] = readSheet(workbook, sheetName);
  }
  return model;
}

function valuesOf(model, sheet, column) {
  return new Set((model[sheet]?.rows ?? []).map((row) => String(row[column] ?? "").trim()).filter(Boolean));
}

function validateQuestionMapping(sheetName, row, issues) {
  const source = String(row.question_type ?? "").trim();
  const target = String(row.import_question_type ?? "").trim();
  if (!IMPORT_QUESTION_TYPES.includes(target)) {
    issues.push(issue("UNSUPPORTED_IMPORT_QUESTION_TYPE", sheetName, row.__row,
      `import_question_type '${target}' is not supported`));
    return;
  }
  const invalid =
    (source === "TRUE_FALSE" && target !== "MULTIPLE_CHOICE") ||
    (source === "FREE_RESPONSE" && target !== "LESSON_ONLY") ||
    (source === "ORDERING" && !["MATCHING", "LESSON_ONLY"].includes(target)) ||
    (source === "MCQ" && target !== "MULTIPLE_CHOICE") ||
    (source === "FILL_BLANK" && target !== "FILL_BLANK") ||
    (source === "MATCHING" && target !== "MATCHING");
  if (invalid) {
    issues.push(issue("UNSAFE_QUESTION_MAPPING", sheetName, row.__row,
      `${source} cannot be imported as ${target}`));
  }
  if (source === "TRUE_FALSE" && target === "MULTIPLE_CHOICE") {
    const options = [row.option_a, row.option_b].map((value) => String(value ?? "").trim().toLocaleLowerCase("vi"));
    if (!options.includes("đúng") || !options.includes("sai")) {
      issues.push(issue("TRUE_FALSE_OPTIONS_REQUIRED", sheetName, row.__row,
        "TRUE_FALSE requires options Đúng and Sai"));
    }
  }
}

export async function validateWorkbook(workbookPath) {
  const model = await loadWorkbookModel(workbookPath);
  const issues = [];

  // 1. Sheet/header contract.
  for (const [sheetName, requiredHeaders] of Object.entries(SHEET_CONTRACTS)) {
    const sheet = model[sheetName];
    if (!sheet) {
      issues.push(issue("MISSING_SHEET", sheetName, 0, `Missing required sheet ${sheetName}`));
      continue;
    }
    for (const header of requiredHeaders) {
      if (!sheet.headers.includes(header)) {
        issues.push(issue("MISSING_HEADER", sheetName, 4, `Missing required header ${header}`));
      }
    }
  }
  if (issues.length) return issues;

  // 2. Duplicate stable codes.
  for (const [sheetName, codeColumn] of Object.entries(CODE_COLUMNS)) {
    const seen = new Set();
    for (const row of model[sheetName].rows) {
      const code = String(row[codeColumn] ?? "").trim();
      if (!code) continue;
      if (seen.has(code)) issues.push(issue("DUPLICATE_CODE", sheetName, row.__row, `Duplicate ${codeColumn}: ${code}`));
      seen.add(code);
    }
  }

  // 3. Parent and source references.
  const bookCodes = valuesOf(model, "BOOK_CATALOG", "source_book_code");
  const textbookCodes = valuesOf(model, "LP_TEXTBOOK", "textbook_code");
  const unitCodes = valuesOf(model, "LP_UNIT", "unit_code");
  const lessonCodes = valuesOf(model, "LP_LESSON", "lesson_code");
  for (const row of model.LP_TEXTBOOK.rows) {
    if (!bookCodes.has(String(row.source_book_code ?? "").trim())) {
      issues.push(issue("UNKNOWN_SOURCE_BOOK_CODE", "LP_TEXTBOOK", row.__row, `Unknown source book ${row.source_book_code}`));
    }
  }
  for (const row of model.LP_UNIT.rows) {
    if (!textbookCodes.has(String(row.textbook_code ?? "").trim())) {
      issues.push(issue("UNKNOWN_TEXTBOOK_CODE", "LP_UNIT", row.__row, `Unknown textbook ${row.textbook_code}`));
    }
  }
  for (const row of model.LP_LESSON.rows) {
    if (!unitCodes.has(String(row.unit_code ?? "").trim())) {
      issues.push(issue("UNKNOWN_UNIT_CODE", "LP_LESSON", row.__row, `Unknown unit ${row.unit_code}`));
    }
  }
  for (const sheetName of ["LESSON_VOCAB", "LESSON_GRAMMAR", "DIALOGUE", "COMPREHENSION", "EXERCISE"]) {
    for (const row of model[sheetName].rows) {
      const lessonCode = String(row.lesson_code ?? "").trim();
      if (lessonCode && !lessonCodes.has(lessonCode)) {
        issues.push(issue("UNKNOWN_LESSON_CODE", sheetName, row.__row, `Unknown lesson ${lessonCode}`));
      }
    }
  }

  // 4. Canonical resolution state.
  for (const sheetName of CANONICAL_SHEETS) {
    for (const row of model[sheetName].rows) {
      if (String(row.canonical_lookup_status ?? "").trim() === "CONFLICT") {
        issues.push(issue("CANONICAL_CONFLICT", sheetName, row.__row,
          `Canonical lookup is unresolved for ${row[CODE_COLUMNS[sheetName]]}`));
      }
    }
  }

  // 5. Kanji must be evidenced by a vocabulary in the same lesson.
  const vocabByCode = new Map(model.LESSON_VOCAB.rows.map((row) => [String(row.membership_code), row]));
  const kanjiByCode = new Map(model.LESSON_KANJI.rows.map((row) => [String(row.membership_code), row]));
  const compoundKanjiCodes = new Set();
  for (const row of model.KANJI_COMPOUND.rows) {
    const kanji = kanjiByCode.get(String(row.kanji_membership_code));
    const vocab = vocabByCode.get(String(row.vocabulary_membership_code));
    if (!kanji || !vocab) {
      issues.push(issue("UNKNOWN_KANJI_COMPOUND_MEMBER", "KANJI_COMPOUND", row.__row,
        "Kanji or vocabulary membership does not exist"));
      continue;
    }
    if (!kanji.lesson_code || kanji.lesson_code !== vocab.lesson_code) {
      issues.push(issue("KANJI_COMPOUND_LESSON_MISMATCH", "KANJI_COMPOUND", row.__row,
        "Kanji and vocabulary memberships must belong to the same lesson"));
      continue;
    }
    const expression = Array.from(String(row.vocabulary_expression ?? ""));
    const position = Number(row.character_position);
    if (expression[position - 1] !== String(row.character ?? "")) {
      issues.push(issue("KANJI_COMPOUND_POSITION_MISMATCH", "KANJI_COMPOUND", row.__row,
        "character_position does not point to character in vocabulary_expression"));
      continue;
    }
    compoundKanjiCodes.add(String(row.kanji_membership_code));
  }
  for (const row of model.LESSON_KANJI.rows) {
    if (String(row.lesson_code ?? "").trim() && !compoundKanjiCodes.has(String(row.membership_code))) {
      issues.push(issue("KANJI_WITHOUT_LESSON_VOCAB", "LESSON_KANJI", row.__row,
        `Kanji ${row.membership_code} has no compound vocabulary in the same lesson`));
    }
  }

  // 6. Source question form must map explicitly to a supported DB behavior.
  for (const sheetName of ["COMPREHENSION", "EXERCISE"]) {
    for (const row of model[sheetName].rows) validateQuestionMapping(sheetName, row, issues);
  }

  // 7. Required source fields are checked last for deterministic diagnostics.
  for (const [sheetName, requiredHeaders] of Object.entries(SHEET_CONTRACTS)) {
    const codeColumn = CODE_COLUMNS[sheetName];
    for (const row of model[sheetName].rows) {
      if (codeColumn && !String(row[codeColumn] ?? "").trim()) continue;
      for (const header of requiredHeaders) {
        if (row[header] === null || row[header] === undefined || String(row[header]).trim() === "") {
          issues.push(issue("MISSING_REQUIRED_VALUE", sheetName, row.__row, `Missing required value ${header}`));
        }
      }
    }
  }

  return issues;
}

function parseInput(argv) {
  const index = argv.indexOf("--input");
  return index >= 0 ? argv[index + 1] : null;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const input = parseInput(process.argv.slice(2));
  if (!input) {
    console.error("Usage: node validate-learning-workbook.mjs --input <workbook.xlsx>");
    process.exitCode = 2;
  } else {
    const issues = await validateWorkbook(input);
    console.log(JSON.stringify({ input, errors: issues.filter((item) => item.severity === "ERROR").length, issues }, null, 2));
    if (issues.some((item) => item.severity === "ERROR")) process.exitCode = 1;
  }
}
