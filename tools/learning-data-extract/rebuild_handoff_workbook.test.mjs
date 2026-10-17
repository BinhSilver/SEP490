import assert from "node:assert/strict";
import test from "node:test";

import { matrixFromAuditRows, selectAuditedWorkbook } from "./rebuild_handoff_workbook.mjs";

test("rebuilds sparse audited rows without changing source row numbers", () => {
  assert.deepEqual(matrixFromAuditRows([[1, ["title"]], [4, ["code", "value"]], [6, ["A", 1]]]), [
    ["title", null], [null, null], [null, null], ["code", "value"], [null, null], ["A", 1],
  ]);
});

test("selects one audited workbook by required sheet contract", () => {
  const learning = { LESSON: [], VOCABULARY: [], AUDIO: [], LESSON_SCOPE: [] };
  const selected = selectAuditedWorkbook({ "assessment.xlsx": { QUESTION: [] }, "learning.xlsx": learning },
    ["LESSON", "VOCABULARY", "AUDIO", "LESSON_SCOPE"]);
  assert.equal(selected.name, "learning.xlsx");
  assert.equal(selected.workbook, learning);
});

test("rejects multiple divergent workbooks instead of choosing by filename", () => {
  const first = { LESSON: [[1, ["A"]]], VOCABULARY: [], AUDIO: [], LESSON_SCOPE: [] };
  const second = { LESSON: [[1, ["B"]]], VOCABULARY: [], AUDIO: [], LESSON_SCOPE: [] };
  assert.throws(() => selectAuditedWorkbook({ a: first, b: second }, ["LESSON", "VOCABULARY", "AUDIO", "LESSON_SCOPE"]), /divergent/i);
});
