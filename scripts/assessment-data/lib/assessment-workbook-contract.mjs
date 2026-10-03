export const USAGE_CODES = Object.freeze(["JLPT_PLACEMENT", "JLPT_MOCK_TEST", "BRSE_FOUNDATION", "PRACTICE_ONLY"]);
export const REVIEW_STATUSES = Object.freeze(["NOT_REVIEWED", "NEEDS_REVIEW", "APPROVED", "REJECTED"]);

export const SHEET_CONTRACTS = Object.freeze({
  BLUEPRINT: ["blueprint_code", "display_name", "usage_code", "jlpt_level", "policy_version", "active", "external_key", "review_status"],
  SECTION: ["section_code", "blueprint_code", "section_order", "skill", "question_count", "required", "external_key", "review_status"],
  QUESTION: ["question_code", "source_code", "skill", "jlpt_level", "question_type", "prompt_ja", "correct_answer", "explanation_vi", "external_key", "confidence", "review_status", "lifecycle_status"],
  OPTION: ["option_code", "question_code", "option_order", "option_text", "is_correct", "external_key", "review_status"],
  PASSAGE: ["passage_code", "source_code", "passage_ja", "jlpt_level", "external_key", "confidence", "review_status"],
  MEDIA: ["audio_code", "source_code", "file_name", "external_key", "review_status"],
  QUESTION_USAGE: ["usage_mapping_code", "question_code", "usage_code", "active", "external_key", "review_status"],
  READINESS: ["entity_type", "external_key", "schema_status", "answer_key_verified", "provenance_complete", "semantic_reviewed", "ready_for_import"],
});

export function asBoolean(value) {
  return value === true || String(value ?? "").trim().toUpperCase() === "TRUE" || String(value) === "1";
}

export function semanticReady(row) {
  return row && String(row.schema_status ?? "").trim().toUpperCase() === "IMPORT_READY"
    && asBoolean(row.answer_key_verified) && asBoolean(row.provenance_complete)
    && asBoolean(row.semantic_reviewed) && asBoolean(row.ready_for_import)
    && Boolean(String(row.reviewer ?? "").trim()) && Boolean(String(row.reviewed_at ?? "").trim());
}
