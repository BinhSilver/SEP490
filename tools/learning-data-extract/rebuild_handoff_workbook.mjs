import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

export function matrixFromAuditRows(rows) {
  const height = Math.max(0, ...(rows ?? []).map(([row]) => Number(row)));
  const width = Math.max(0, ...(rows ?? []).map(([, values]) => values?.length ?? 0));
  const matrix = Array.from({ length: height }, () => new Array(width).fill(null));
  for (const [sourceRow, values] of rows ?? []) {
    for (let column = 0; column < (values?.length ?? 0); column += 1) matrix[Number(sourceRow) - 1][column] = values[column] ?? null;
  }
  return matrix;
}

export function selectAuditedWorkbook(tables, requiredSheets) {
  const candidates = Object.entries(tables).filter(([, workbook]) => requiredSheets.every((sheet) => sheet in workbook));
  if (!candidates.length) throw new Error(`No audited workbook satisfies ${requiredSheets.join(", ")}`);
  const variants = new Map();
  for (const [name, workbook] of candidates) {
    const signature = JSON.stringify(workbook);
    if (!variants.has(signature)) variants.set(signature, []);
    variants.get(signature).push({ name, workbook });
  }
  if (variants.size > 1) throw new Error("Multiple divergent audited workbooks match the requested contract");
  return [...variants.values()][0].sort((left, right) => left.name.localeCompare(right.name))[0];
}

function contract(kind) {
  if (kind === "learning") return ["LESSON", "VOCABULARY", "AUDIO", "LESSON_SCOPE", "READINESS"];
  if (kind === "assessment") return ["QUESTION", "OPTION", "QUESTION_USAGE", "READINESS"];
  throw new Error(`Unsupported workbook kind ${kind}`);
}

export async function rebuild({ auditPath, templatePath, kind, outputPath, previewPath }) {
  const tables = JSON.parse(await fs.readFile(auditPath, "utf8"));
  const selected = selectAuditedWorkbook(tables, contract(kind));
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(templatePath));
  const availableSheets = new Set(workbook.worksheets.items.map((sheet) => sheet.name));
  const written = [];
  for (const [sheetName, rows] of Object.entries(selected.workbook)) {
    if (!availableSheets.has(sheetName)) continue;
    const matrix = matrixFromAuditRows(rows);
    if (!matrix.length || !matrix[0].length) continue;
    workbook.worksheets.getItem(sheetName).getRangeByIndexes(0, 0, matrix.length, matrix[0].length).values = matrix;
    written.push({ sheet: sheetName, rows: matrix.length, columns: matrix[0].length });
  }

  const formulaErrors = await workbook.inspect({
    kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
    options: { useRegex: true, maxResults: 100 }, summary: `${kind} rebuilt workbook formula scan`,
  });
  const hasErrors = String(formulaErrors.ndjson ?? "").split(/\r?\n/).filter(Boolean)
    .some((line) => { try { return JSON.parse(line).kind === "match"; } catch { return false; } });
  if (hasErrors) throw new Error("Formula errors found after rebuilding audited workbook");
  const previewSheet = kind === "learning" ? "LESSON_SCOPE" : "QUESTION";
  const previewRange = kind === "learning" ? "A1:J20" : "A1:S18";
  const preview = await workbook.render({ sheetName: previewSheet, range: previewRange, scale: 1.25, format: "png" });
  await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
  const exported = await SpreadsheetFile.exportXlsx(workbook);
  await exported.save(outputPath);
  return { auditedWorkbook: selected.name, sheetsWritten: written.length, sheets: written };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [auditPath, templatePath, kind, outputPath, previewPath] = process.argv.slice(2);
  if (!previewPath) throw new Error("Usage: rebuild_handoff_workbook.mjs <source-tables.json> <template.xlsx> <learning|assessment> <output.xlsx> <preview.png>");
  console.log(JSON.stringify(await rebuild({
    auditPath: path.resolve(auditPath), templatePath: path.resolve(templatePath), kind,
    outputPath: path.resolve(outputPath), previewPath: path.resolve(previewPath),
  })));
}
