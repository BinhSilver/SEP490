import assert from "node:assert/strict";
import test from "node:test";

import { missingColumns, planContractUpgrade } from "./upgrade_assessment_workbook_contract.mjs";

test("finds only missing contract headers and preserves existing positions", () => {
  assert.deepEqual(missingColumns(["question_code", "external_key"], ["question_code", "knowledge_tag_code", "external_key"]),
    ["knowledge_tag_code"]);
});

test("plans the four DB lookup and assignment columns", () => {
  assert.deepEqual(planContractUpgrade({
    QUESTION: ["question_code", "external_key"],
    QUESTION_USAGE: ["usage_mapping_code", "external_key"],
  }), {
    QUESTION: ["knowledge_tag_code"],
    QUESTION_USAGE: ["order_index", "mondai_number", "raw_score"],
  });
});
