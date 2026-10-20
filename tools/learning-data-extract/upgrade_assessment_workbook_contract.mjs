import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const REQUIRED_ADDITIONS = Object.freeze({
  QUESTION: ["knowledge_tag_code"],
  QUESTION_USAGE: ["order_index", "mondai_number", "raw_score"],
});

export function missingColumns(existing, required) {
  const known = new Set(existing.map((value) => String(value ?? "").replace(/\*$/, "").trim()));
  return required.filter((value) => !known.has(value));
}

export function planContractUpgrade(headersBySheet) {
  return Object.fromEntries(Object.entries(REQUIRED_ADDITIONS)
    .map(([sheet, required]) => [sheet, missingColumns(headersBySheet[sheet] ?? [], required)]));
}

function headerValues(workbook, sheetName) {
  return (workbook.worksheets.getItem(sheetName).getUsedRange()?.values?.[3] ?? [])
    .map((value) => String(value ?? "").trim());
}

export async function upgrade({ inputPath, outputPath, previewPath }) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(inputPath));
  const headersBySheet = Object.fromEntries(Object.keys(REQUIRED_ADDITIONS)
    .map((sheet) => [sheet, headerValues(workbook, sheet)]));
  const plan = planContractUpgrade(headersBySheet);
  const changes = [];
  for (const [sheetName, columns] of Object.entries(plan)) {
    const sheet = workbook.worksheets.getItem(sheetName);
    let nextColumn = headersBySheet[sheetName].length;
    for (const column of columns) {
      const cell = sheet.getCell(3, nextColumn);
      cell.values = [[`${column}*`]];
      cell.format.fill = "#24344D";
      cell.format.font = { bold: true, color: "#FFFFFF" };
      cell.format.horizontalAlignment = "center";
      sheet.getRangeByIndexes(3, nextColumn, Math.max(1, sheet.getUsedRange().rowCount - 3), 1).format.columnWidth = 18;
      changes.push({ sheet: sheetName, column, columnIndex: nextColumn + 1 });
      nextColumn += 1;
    }
  }
  const formulaErrors = await workbook.inspect({
    kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!",
    options: { useRegex: true, maxResults: 100 }, summary: "assessment contract upgrade formula scan",
  });
  const hasErrors = String(formulaErrors.ndjson ?? "").split(/\r?\n/).filter(Boolean)
    .some((line) => { try { return JSON.parse(line).kind === "match"; } catch { return false; } });
  if (hasErrors) throw new Error("Formula errors found after Assessment contract upgrade");
  const preview = await workbook.render({ sheetName: "QUESTION_USAGE", range: "A1:M16", scale: 1.25, format: "png" });
  await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
  const exported = await SpreadsheetFile.exportXlsx(workbook);
  await exported.save(outputPath);
  return { changes };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [inputPath, outputPath, previewPath] = process.argv.slice(2).map((value) => path.resolve(value));
  if (!previewPath) throw new Error("Usage: upgrade_assessment_workbook_contract.mjs <input.xlsx> <output.xlsx> <preview.png>");
  console.log(JSON.stringify(await upgrade({ inputPath, outputPath, previewPath })));
}
