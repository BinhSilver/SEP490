export const SHEET_CONTRACTS = Object.freeze({
  BOOK_CATALOG: ["source_book_code", "jlpt_level", "source_role"],
  LP_TEXTBOOK: ["textbook_code", "source_book_code", "title", "jlpt_level", "status", "version", "is_learning_path_source"],
  LP_UNIT: ["unit_code", "textbook_code", "title", "order_index", "source_book_code"],
  LP_LESSON: ["lesson_code", "unit_code", "source_book_code", "source_lesson_ref", "title", "order_index", "estimated_minutes", "lesson_type", "primary_skill", "knowledge_tag_code", "is_review", "status"],
  LESSON_VOCAB: ["membership_code", "lesson_code", "source_book_code", "source_lesson_ref", "expression", "reading", "meaning_vi", "order_index", "is_core", "canonical_lookup_status"],
  LESSON_KANJI: ["membership_code", "source_book_code", "source_session_ref", "character", "order_index", "canonical_lookup_status"],
  KANJI_COMPOUND: ["link_code", "kanji_membership_code", "vocabulary_membership_code", "character", "vocabulary_expression", "vocabulary_reading", "character_position"],
  LESSON_GRAMMAR: ["grammar_code", "lesson_code", "source_book_code", "source_lesson_ref", "pattern", "meaning_vi", "order_index", "canonical_lookup_status"],
  DIALOGUE: ["dialogue_code", "destination_scope", "source_book_code", "source_lesson_ref", "title", "turn_index", "speaker", "text_ja", "translation_vi", "register", "is_key_phrase"],
  COMPREHENSION: ["content_code", "source_book_code", "source_lesson_ref", "skill", "source_kind", "title", "passage_or_script", "question_code", "question_text", "question_type", "import_question_type", "correct_answer", "order_index"],
  EXERCISE: ["exercise_code", "source_book_code", "source_lesson_ref", "section_type", "skill", "question_type", "import_question_type", "prompt", "correct_answer", "order_index"],
  FOUNDATION_CHAR: ["character_code", "script_type", "character", "romaji", "order_index", "source_status"],
});

export const CODE_COLUMNS = Object.freeze({
  BOOK_CATALOG: "source_book_code",
  LP_TEXTBOOK: "textbook_code",
  LP_UNIT: "unit_code",
  LP_LESSON: "lesson_code",
  LESSON_VOCAB: "membership_code",
  LESSON_KANJI: "membership_code",
  KANJI_COMPOUND: "link_code",
  LESSON_GRAMMAR: "grammar_code",
  DIALOGUE: "dialogue_code",
  COMPREHENSION: "question_code",
  EXERCISE: "exercise_code",
  FOUNDATION_CHAR: "character_code",
});

export const CANONICAL_SHEETS = Object.freeze([
  "LESSON_VOCAB",
  "LESSON_KANJI",
  "LESSON_GRAMMAR",
]);

export const IMPORT_QUESTION_TYPES = Object.freeze([
  "MULTIPLE_CHOICE",
  "FILL_BLANK",
  "MATCHING",
  "LESSON_ONLY",
]);

export const FORBIDDEN_SENTINELS = new Set([
  "-",
  "dummy",
  "placeholder",
  "tbd",
  "undefined",
  "null",
  "n/a",
]);

export function isForbiddenSentinel(value) {
  const normalized = String(value ?? "").trim().toLocaleLowerCase("en");
  return FORBIDDEN_SENTINELS.has(normalized)
    || /(?:^|[-_:])undefined$/.test(normalized)
    || /(?:^|[-_:])dummy$/.test(normalized)
    || /(?:^|[-_:])placeholder$/.test(normalized);
}

export const OUTPUT_FILES = Object.freeze([
  "seed_learning_path_catalog.sql",
  "seed_learning_path_n5.sql",
  "seed_learning_path_n4.sql",
  "seed_learning_path_exercises.sql",
]);

export function normalizeHeader(value) {
  return String(value ?? "").trim().replace(/\*$/, "");
}

export function normalizeJapanese(value) {
  return String(value ?? "").normalize("NFKC").replace(/[ァ-ヶ]/g, (character) =>
    String.fromCodePoint(character.codePointAt(0) - 0x60));
}

export function safeVariable(value, prefix = "row") {
  const normalized = String(value ?? "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  return `${prefix}_${normalized || "unknown"}`;
}
