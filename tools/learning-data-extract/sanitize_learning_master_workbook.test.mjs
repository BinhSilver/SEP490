import assert from "node:assert/strict";
import test from "node:test";

import { lessonDisposition, readinessDisposition } from "./sanitize_learning_master_workbook.mjs";

test("downgrades a published lesson that has no completed review", () => {
  assert.deepEqual(lessonDisposition({ lifecycle_status: "PUBLISHED", review_status: "NOT_REVIEWED" }), {
    lifecycle_status: "DRAFT",
    review_status: "NEEDS_REVIEW",
    issue_code: "PUBLISHED_WITHOUT_COMPLETE_REVIEW",
  });
});

test("does not change a draft lesson or a fully approved published lesson", () => {
  assert.equal(lessonDisposition({ lifecycle_status: "DRAFT", review_status: "NOT_REVIEWED" }), null);
  assert.equal(lessonDisposition({ lifecycle_status: "PUBLISHED", review_status: "APPROVED" }), null);
});

test("readiness remains fail-closed until every gate is true", () => {
  assert.deepEqual(readinessDisposition({
    schema_status: "IMPORT_READY", content_complete: false, provenance_complete: false,
    semantic_reviewed: false, ready_for_import: false,
  }), { schema_status: "SCHEMA_PENDING", ready_for_import: false });
  assert.equal(readinessDisposition({
    schema_status: "IMPORT_READY", content_complete: true, provenance_complete: true,
    semantic_reviewed: true, ready_for_import: true,
  }), null);
});
