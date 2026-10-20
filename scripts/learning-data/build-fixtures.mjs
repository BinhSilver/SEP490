import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const outputDir = path.join(import.meta.dirname, "fixtures");
const previewDir = path.join(import.meta.dirname, ".fixture-previews");
const headers = {
  BOOK_CATALOG: ["source_book_code*", "source_file_name*", "jlpt_level*", "source_role*"],
  LP_TEXTBOOK: ["textbook_code*", "source_book_code*", "title*", "description", "jlpt_level*", "status*", "version*", "is_learning_path_source*"],
  LP_UNIT: ["unit_code*", "textbook_code*", "title*", "description", "order_index*", "source_book_code*"],
  LP_LESSON: ["lesson_code*", "unit_code*", "source_book_code*", "source_lesson_ref*", "title*", "description", "order_index*", "estimated_minutes*", "lesson_type*", "primary_skill*", "knowledge_tag_code*", "situation_tag", "is_review*", "status*"],
  LESSON_VOCAB: ["membership_code*", "lesson_code*", "source_book_code*", "source_lesson_ref*", "canonical_vocab_id", "expression*", "reading*", "romaji", "word_type", "han_viet", "meaning_vi*", "example_ja", "example_vi", "order_index*", "is_core*", "canonical_lookup_status*"],
  LESSON_KANJI: ["membership_code*", "lesson_code", "topic_code", "source_book_code*", "source_session_ref*", "canonical_kanji_id", "character*", "unicode_code", "han_viet", "meaning_vi", "stroke_count", "stroke_svg_uri", "on_readings", "kun_readings", "memory_hint", "order_index*", "canonical_lookup_status*"],
  KANJI_COMPOUND: ["link_code*", "kanji_membership_code*", "vocabulary_membership_code*", "character*", "vocabulary_expression*", "vocabulary_reading*", "character_position*", "notes"],
  LESSON_GRAMMAR: ["grammar_code*", "lesson_code*", "source_book_code*", "source_lesson_ref*", "canonical_grammar_id", "knowledge_tag_code", "pattern*", "connection_formula", "meaning_vi*", "context_note", "example_ja", "example_vi", "practice_prompt", "order_index*", "canonical_lookup_status*"],
  DIALOGUE: ["dialogue_code*", "lesson_code", "topic_code", "destination_scope*", "source_book_code*", "source_lesson_ref*", "title*", "turn_index*", "speaker*", "text_ja*", "reading_or_romaji", "translation_vi*", "register*", "is_key_phrase*", "can_do_outcome", "audio_url", "source_page"],
  COMPREHENSION: ["content_code*", "lesson_code", "topic_code", "source_book_code*", "source_lesson_ref*", "skill*", "source_kind*", "title*", "passage_or_script*", "translation_vi", "audio_url", "question_code*", "question_text*", "question_type*", "import_question_type*", "option_a", "option_b", "option_c", "option_d", "correct_answer*", "explanation_vi", "order_index*", "source_page"],
  EXERCISE: ["exercise_code*", "lesson_code", "review_group", "source_book_code*", "source_lesson_ref*", "section_type*", "skill*", "question_type*", "import_question_type*", "prompt*", "option_a", "option_b", "option_c", "option_d", "correct_answer*", "answer_explanation", "answer_source_book_code", "answer_source_page", "difficulty", "order_index*", "source_page"],
  FOUNDATION_CHAR: ["character_code*", "script_type*", "character*", "romaji*", "stroke_order_uri", "audio_url", "example_expression", "example_reading", "example_meaning_vi", "order_index*", "source_book_code", "source_page", "source_status*"],
};

function row(sheet, values) {
  return headers[sheet].map((header) => values[header.replace(/\*$/, "")] ?? null);
}

function baseRows(levels = ["N5"]) {
  const result = Object.fromEntries(Object.keys(headers).map((name) => [name, []]));
  for (const level of levels) {
    const bookCode = `${level}-CORE`;
    const textbookCode = `LP-${level}-CORE`;
    const unitCode = `LP-${level}-U01`;
    const lessonCode = `LP-${level}-L01`;
    result.BOOK_CATALOG.push(row("BOOK_CATALOG", { source_book_code: bookCode, source_file_name: `${level}.pdf`, jlpt_level: level, source_role: "CORE_CURRICULUM" }));
    result.LP_TEXTBOOK.push(row("LP_TEXTBOOK", { textbook_code: textbookCode, source_book_code: bookCode, title: `Learning Path ${level}`, description: "Fixture", jlpt_level: level, status: "DRAFT", version: 1, is_learning_path_source: 1 }));
    result.LP_UNIT.push(row("LP_UNIT", { unit_code: unitCode, textbook_code: textbookCode, title: `${level} Unit 1`, description: "Fixture", order_index: 1, source_book_code: bookCode }));
    result.LP_LESSON.push(row("LP_LESSON", { lesson_code: lessonCode, unit_code: unitCode, source_book_code: bookCode, source_lesson_ref: "Bài 1", title: `${level} Lesson 1`, description: "Fixture", order_index: 1, estimated_minutes: 30, lesson_type: "CURRICULUM", primary_skill: "VOCABULARY", knowledge_tag_code: `JLPT_${level}_LESSON_01`, is_review: 0, status: "DRAFT" }));
  }
  return result;
}

function orphanRows() {
  const result = baseRows(["N5"]);
  result.LP_LESSON[0][headers.LP_LESSON.indexOf("unit_code*")] = "MISSING-UNIT";
  result.LESSON_VOCAB.push(row("LESSON_VOCAB", { membership_code: "N5-V001", lesson_code: "LP-N5-L01", source_book_code: "N5-CORE", source_lesson_ref: "Bài 1", expression: "日本語", reading: "にほんご", meaning_vi: "tiếng Nhật", order_index: 1, is_core: 1, canonical_lookup_status: "CONFLICT" }));
  return result;
}

function invalidKanjiRows() {
  const result = baseRows(["N4"]);
  result.LESSON_VOCAB.push(row("LESSON_VOCAB", { membership_code: "N4-V001", lesson_code: "LP-N4-L01", source_book_code: "N4-CORE", source_lesson_ref: "Bài 1", expression: "時間", reading: "じかん", meaning_vi: "thời gian", order_index: 1, is_core: 1, canonical_lookup_status: "MATCHED" }));
  result.LESSON_KANJI.push(row("LESSON_KANJI", { membership_code: "N4-K001", lesson_code: "LP-N4-L01", source_book_code: "N4-CORE", source_session_ref: "Bài 1", character: "間", unicode_code: "9593", han_viet: "GIAN", meaning_vi: "khoảng", stroke_count: 12, order_index: 1, canonical_lookup_status: "MATCHED" }));
  return result;
}

function validRows() {
  const result = baseRows(["N5", "N4"]);
  result.LESSON_VOCAB.push(
    row("LESSON_VOCAB", { membership_code: "N5-V001", lesson_code: "LP-N5-L01", source_book_code: "N5-CORE", source_lesson_ref: "Bài 1", expression: "日本語", reading: "ニホンゴ", romaji: "nihongo", word_type: "NOUN", meaning_vi: "tiếng Nhật", example_ja: "日本語を勉強します。", example_vi: "Tôi học tiếng Nhật.", order_index: 1, is_core: 1, canonical_lookup_status: "CREATE_CANONICAL" }),
    row("LESSON_VOCAB", { membership_code: "N4-V001", lesson_code: "LP-N4-L01", source_book_code: "N4-CORE", source_lesson_ref: "Bài 1", expression: "時間", reading: "じかん", romaji: "jikan", word_type: "NOUN", meaning_vi: "thời gian", order_index: 1, is_core: 1, canonical_lookup_status: "CREATE_CANONICAL" }),
  );
  result.LESSON_KANJI.push(row("LESSON_KANJI", { membership_code: "N4-K001", lesson_code: "LP-N4-L01", source_book_code: "N4-CORE", source_session_ref: "Bài 1", character: "間", unicode_code: "9593", han_viet: "GIAN", meaning_vi: "khoảng", stroke_count: 12, order_index: 1, canonical_lookup_status: "CREATE_CANONICAL" }));
  result.KANJI_COMPOUND.push(row("KANJI_COMPOUND", { link_code: "N4-KC001", kanji_membership_code: "N4-K001", vocabulary_membership_code: "N4-V001", character: "間", vocabulary_expression: "時間", vocabulary_reading: "じかん", character_position: 2 }));
  result.LESSON_GRAMMAR.push(row("LESSON_GRAMMAR", { grammar_code: "N5-G001", lesson_code: "LP-N5-L01", source_book_code: "N5-CORE", source_lesson_ref: "Bài 1", pattern: "N1 は N2 です", meaning_vi: "N1 là N2", example_ja: "私は学生です。", example_vi: "Tôi là học sinh.", order_index: 1, canonical_lookup_status: "CREATE_CANONICAL" }));
  result.DIALOGUE.push(row("DIALOGUE", { dialogue_code: "N5-D001", lesson_code: "LP-N5-L01", destination_scope: "JLPT_LESSON", source_book_code: "N5-CORE", source_lesson_ref: "Bài 1", title: "Chào hỏi", turn_index: 1, speaker: "A", text_ja: "こんにちは。", translation_vi: "Xin chào.", register: "POLITE", is_key_phrase: 1 }));
  result.COMPREHENSION.push(row("COMPREHENSION", { content_code: "N4-C001", lesson_code: "LP-N4-L01", source_book_code: "N4-CORE", source_lesson_ref: "Bài 1", skill: "READING", source_kind: "TEXT", title: "Đọc ngắn", passage_or_script: "時間があります。", question_code: "N4-Q001", question_text: "何がありますか。", question_type: "MCQ", import_question_type: "MULTIPLE_CHOICE", option_a: "時間", option_b: "本", option_c: "車", option_d: "家", correct_answer: "A", explanation_vi: "Bài đọc nhắc đến thời gian.", order_index: 1, source_page: 1 }));
  result.EXERCISE.push(row("EXERCISE", { exercise_code: "N5-E001", lesson_code: "LP-N5-L01", source_book_code: "N5-CORE", source_lesson_ref: "Bài 1", section_type: "PARTICLE_FILL", skill: "GRAMMAR", question_type: "FILL_BLANK", import_question_type: "FILL_BLANK", prompt: "わたし＿学生です。", correct_answer: "は", answer_explanation: "Trợ từ chủ đề.", difficulty: "EASY", order_index: 1, source_page: 1 }));
  return result;
}

async function build(name, data) {
  const workbook = Workbook.create();
  for (const [sheetName, sheetHeaders] of Object.entries(headers)) {
    const sheet = workbook.worksheets.add(sheetName);
    sheet.showGridLines = false;
    sheet.getRange("A1").values = [[`${name} fixture`]];
    sheet.getRange("A1").format.font = { name: "Arial", size: 14, bold: true, color: "#24364B" };
    const lastColumn = columnName(sheetHeaders.length - 1);
    sheet.getRange(`A4:${lastColumn}4`).values = [sheetHeaders];
    sheet.getRange(`A4:${lastColumn}4`).format = { fill: "#24364B", font: { name: "Arial", bold: true, color: "#FFFFFF" }, wrapText: true };
    const body = data[sheetName].length ? data[sheetName] : [Array(sheetHeaders.length).fill(null)];
    sheet.getRange(`A5:${lastColumn}${4 + body.length}`).values = body;
    sheet.getRange(`A5:${lastColumn}${4 + body.length}`).format.font = { name: "Arial", size: 10 };
    sheet.getRange(`A5:${lastColumn}${4 + body.length}`).format.fill = "#FFF4CC";
    sheet.getRange(`A4:${lastColumn}${4 + body.length}`).format.autofitColumns();
    sheet.getRange(`A:${lastColumn}`).format.columnWidth = 18;
    sheet.freezePanes.freezeRows(4);
  }
  const inspect = await workbook.inspect({ kind: "table", range: "LP_LESSON!A1:N7", include: "values,formulas", tableMaxRows: 7, tableMaxCols: 14 });
  console.log(inspect.ndjson);
  const errors = await workbook.inspect({ kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!", options: { useRegex: true, maxResults: 50 } });
  console.log(errors.ndjson);
  const preview = await workbook.render({ sheetName: "LP_LESSON", autoCrop: "all", scale: 1, format: "png" });
  await fs.writeFile(path.join(previewDir, `${name}.png`), new Uint8Array(await preview.arrayBuffer()));
  const output = await SpreadsheetFile.exportXlsx(workbook);
  await output.save(path.join(outputDir, `${name}.xlsx`));
}

function columnName(index) {
  let value = index + 1;
  let result = "";
  while (value) {
    result = String.fromCharCode(65 + ((value - 1) % 26)) + result;
    value = Math.floor((value - 1) / 26);
  }
  return result;
}

await fs.mkdir(outputDir, { recursive: true });
await fs.mkdir(previewDir, { recursive: true });
await build("invalid-orphans", orphanRows());
await build("invalid-kanji", invalidKanjiRows());
await build("valid-minimal", validRows());
