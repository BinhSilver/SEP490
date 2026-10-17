import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

function upper(value) {
  return String(value ?? "").trim().toUpperCase();
}

function bool(value) {
  return value === true || upper(value) === "TRUE" || String(value) === "1";
}

export function lessonDisposition(row) {
  if (upper(row.lifecycle_status) !== "PUBLISHED" || upper(row.review_status) === "APPROVED") return null;
  return {
    lifecycle_status: "DRAFT",
    review_status: "NEEDS_REVIEW",
    issue_code: "PUBLISHED_WITHOUT_COMPLETE_REVIEW",
  };
}

export function readinessDisposition(row) {
  const ready = bool(row.content_complete) && bool(row.provenance_complete)
    && bool(row.semantic_reviewed) && bool(row.ready_for_import);
  if (ready || (upper(row.schema_status) === "SCHEMA_PENDING" && !bool(row.ready_for_import))) return null;
  return { schema_status: "SCHEMA_PENDING", ready_for_import: false };
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

function setCell(table, row, field, value) {
  const column = table.headers.indexOf(field);
  if (column < 0) throw new Error(`Missing column ${field}`);
  table.sheet.getCell(row.__rowIndex, column).values = [[value]];
  row[field] = value;
}

function csvEscape(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export async function sanitize({ inputPath, outputPath, logPath, previewPath }) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(inputPath));
  const lessons = model(workbook, "LESSON");
  const readiness = model(workbook, "READINESS");
  const changes = [];

  for (const row of lessons.rows) {
    const disposition = lessonDisposition(row);
    if (!disposition) continue;
    for (const field of ["lifecycle_status", "review_status"]) {
      const oldValue = row[field];
      setCell(lessons, row, field, disposition[field]);
      changes.push({ sheet: "LESSON", row: row.__row, external_key: row.external_key,
        field, old_value: oldValue, new_value: disposition[field], issue_code: disposition.issue_code });
    }
  }

  for (const row of readiness.rows) {
    const disposition = readinessDisposition(row);
    if (!disposition) continue;
    for (const field of ["schema_status", "ready_for_import"]) {
      if (row[field] === disposition[field]) continue;
      const oldValue = row[field];
      setCell(readiness, row, field, disposition[field]);
      changes.push({ sheet: "READINESS", row: row.__row, external_key: row.external_key,
        field, old_value: oldValue, new_value: disposition[field], issue_code: "READINESS_GATES_INCOMPLETE" });
    }
  }

  const formulaErrors = await workbook.inspect({
    kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
    options: { useRegex: true, maxResults: 100 }, summary: "learning master formula error scan",
  });
  const matches = String(formulaErrors.ndjson ?? "").split(/\r?\n/).filter(Boolean)
    .some((line) => { try { return JSON.parse(line).kind === "match"; } catch { return false; } });
  if (matches) throw new Error("Formula errors found after learning master sanitization");

  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  const preview = await workbook.render({ sheetName: "LESSON", range: "A1:L18", scale: 1.25, format: "png" });
  await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
  const exported = await SpreadsheetFile.exportXlsx(workbook);
  await exported.save(outputPath);
  const headers = ["sheet", "row", "external_key", "field", "old_value", "new_value", "issue_code"];
  await fs.writeFile(logPath, `${headers.join(",")}\n${changes.map((row) =>
    headers.map((header) => csvEscape(row[header])).join(",")).join("\n")}\n`, "utf8");
  return { changes: changes.length, lessonRowsDowngraded: changes.filter((row) => row.sheet === "LESSON" && row.field === "lifecycle_status").length };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [inputPath, outputPath, logPath, previewPath] = process.argv.slice(2).map((value) => path.resolve(value));
  if (!previewPath) throw new Error("Usage: sanitize_learning_master_workbook.mjs <input.xlsx> <output.xlsx> <log.csv> <preview.png>");
  console.log(JSON.stringify(await sanitize({ inputPath, outputPath, logPath, previewPath })));
}
