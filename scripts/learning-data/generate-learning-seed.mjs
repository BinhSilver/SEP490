import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { loadWorkbookModel, validateWorkbook } from "./validate-learning-workbook.mjs";
import { OUTPUT_FILES, normalizeJapanese, safeVariable } from "./lib/workbook-contract.mjs";
import { sqlBoolean, sqlHeader, sqlNumber, sqlString, upsert } from "./lib/sql-writer.mjs";

function rows(model, sheet) {
  return model[sheet]?.rows ?? [];
}

function byCode(list, column) {
  return new Map(list.map((row) => [String(row[column]), row]));
}

function lessonContext(model) {
  const books = byCode(rows(model, "LP_TEXTBOOK"), "textbook_code");
  const units = byCode(rows(model, "LP_UNIT"), "unit_code");
  return new Map(rows(model, "LP_LESSON").map((lesson) => {
    const unit = units.get(String(lesson.unit_code));
    const book = books.get(String(unit?.textbook_code));
    return [String(lesson.lesson_code), { lesson, unit, book, level: String(book?.jlpt_level ?? "") }];
  }));
}

function catalogSql(model) {
  let sql = sqlHeader("Learning Path curriculum catalog");
  for (const book of rows(model, "LP_TEXTBOOK")) {
    sql += upsert("textbook",
      ["source_code", "title", "description", "jlpt_level", "status", "version", "is_learning_path_source"],
      [sqlString(book.textbook_code), sqlString(book.title), sqlString(book.description), sqlString(book.jlpt_level), sqlString(book.status), sqlNumber(book.version, "1"), sqlBoolean(book.is_learning_path_source, 1)],
      ["title", "description", "jlpt_level", "status", "version", "is_learning_path_source"]);
    sql += `SET @${safeVariable(book.textbook_code, "book")} = (SELECT id FROM textbook WHERE source_code=${sqlString(book.textbook_code)});\n\n`;
  }
  for (const unit of rows(model, "LP_UNIT")) {
    const bookVar = safeVariable(unit.textbook_code, "book");
    sql += upsert("unit", ["textbook_id", "source_code", "title", "description", "order_index"],
      [`@${bookVar}`, sqlString(unit.unit_code), sqlString(unit.title), sqlString(unit.description), sqlNumber(unit.order_index, "0")],
      ["title", "description", "order_index"]);
    sql += `SET @${safeVariable(unit.unit_code, "unit")} = (SELECT id FROM unit WHERE textbook_id=@${bookVar} AND source_code=${sqlString(unit.unit_code)});\n\n`;
  }
  for (const lesson of rows(model, "LP_LESSON")) {
    const tagVar = safeVariable(lesson.knowledge_tag_code, "tag");
    const unitVar = safeVariable(lesson.unit_code, "unit");
    const lessonVar = safeVariable(lesson.lesson_code, "lesson");
    sql += upsert("tag", ["code", "name", "tag_type"],
      [sqlString(lesson.knowledge_tag_code), sqlString(lesson.title), "'KNOWLEDGE'"], ["name"]);
    sql += `SET @${tagVar} = (SELECT id FROM tag WHERE code=${sqlString(lesson.knowledge_tag_code)});\n`;
    sql += upsert("lesson",
      ["unit_id", "source_code", "knowledge_tag_id", "title", "description", "order_index", "estimated_minutes", "lesson_type", "skill", "situation_tag", "is_review", "status"],
      [`@${unitVar}`, sqlString(lesson.lesson_code), `@${tagVar}`, sqlString(lesson.title), sqlString(lesson.description), sqlNumber(lesson.order_index, "0"), sqlNumber(lesson.estimated_minutes), sqlString(lesson.lesson_type), sqlString(lesson.primary_skill), sqlString(lesson.situation_tag), sqlBoolean(lesson.is_review), sqlString(lesson.status)],
      ["knowledge_tag_id", "title", "description", "order_index", "estimated_minutes", "lesson_type", "skill", "situation_tag", "is_review", "status"]);
    sql += `SET @${lessonVar} = (SELECT id FROM lesson WHERE unit_id=@${unitVar} AND source_code=${sqlString(lesson.lesson_code)});\n`;
    sql += `INSERT INTO lesson_section (lesson_id,title,section_type,order_index)\nVALUES (@${lessonVar},'Nội dung','EXPLANATION',1)\nON DUPLICATE KEY UPDATE title=VALUES(title), section_type=VALUES(section_type);\n`;
    sql += `SET @${safeVariable(lesson.lesson_code, "section")} = (SELECT id FROM lesson_section WHERE lesson_id=@${lessonVar} AND order_index=1);\n\n`;
  }
  return sql;
}

function levelSql(model, level, contexts) {
  let sql = sqlHeader(`Learning Path ${level} canonical content and lesson links`);
  const inLevel = (row) => contexts.get(String(row.lesson_code))?.level === level;
  for (const context of contexts.values()) {
    if (context.level !== level) continue;
    const lessonVar = safeVariable(context.lesson.lesson_code, "lesson");
    const sectionVar = safeVariable(context.lesson.lesson_code, "section");
    sql += `SET @${lessonVar} = (SELECT l.id FROM lesson l JOIN unit u ON u.id=l.unit_id JOIN textbook t ON t.id=u.textbook_id WHERE l.source_code=${sqlString(context.lesson.lesson_code)} AND u.source_code=${sqlString(context.unit.unit_code)} AND t.source_code=${sqlString(context.book.textbook_code)});\n`;
    sql += `SET @${sectionVar} = (SELECT id FROM lesson_section WHERE lesson_id=@${lessonVar} AND order_index=1);\n`;
  }
  sql += "\n";

  for (const vocab of rows(model, "LESSON_VOCAB").filter(inLevel)) {
    const normalizedExpression = normalizeJapanese(vocab.expression);
    const normalizedReading = normalizeJapanese(vocab.reading);
    const vocabVar = safeVariable(vocab.membership_code, "vocab");
    const sectionVar = safeVariable(vocab.lesson_code, "section");
    const itemVar = safeVariable(vocab.membership_code, "content");
    sql += upsert("vocabulary_entry",
      ["expression", "normalized_expression", "reading", "normalized_reading", "reading_romaji", "meaning", "word_type", "jlpt_level", "example_ja", "example_vi", "usage_scope", "dictionary_status"],
      [sqlString(vocab.expression), sqlString(normalizedExpression), sqlString(vocab.reading), sqlString(normalizedReading), sqlString(vocab.romaji), sqlString(vocab.meaning_vi), sqlString(vocab.word_type), sqlString(level), sqlString(vocab.example_ja), sqlString(vocab.example_vi), "'BOTH'", "'PUBLISHED'"],
      ["expression", "reading", "reading_romaji", "meaning", "word_type", "jlpt_level", "example_ja", "example_vi"]);
    sql += `SET @${vocabVar} = (SELECT id FROM vocabulary_entry WHERE normalized_expression=${sqlString(normalizedExpression)} AND normalized_reading=${sqlString(normalizedReading)});\n`;
    const contentOrder = 1000 + Number(vocab.order_index || 0);
    sql += `INSERT INTO content_item (section_id,content_type,title,content,translated_text,order_index)\nVALUES (@${sectionVar},'READING',${sqlString(vocab.expression)},${sqlString(vocab.example_ja || vocab.expression)},${sqlString(vocab.example_vi || vocab.meaning_vi)},${contentOrder})\nON DUPLICATE KEY UPDATE title=VALUES(title), content=VALUES(content), translated_text=VALUES(translated_text);\n`;
    sql += `SET @${itemVar} = (SELECT id FROM content_item WHERE section_id=@${sectionVar} AND order_index=${contentOrder});\n`;
    sql += `INSERT IGNORE INTO content_vocabulary (content_item_id,vocabulary_id) VALUES (@${itemVar},@${vocabVar});\n\n`;
  }

  for (const grammar of rows(model, "LESSON_GRAMMAR").filter(inLevel)) {
    const lessonVar = safeVariable(grammar.lesson_code, "lesson");
    const note = [
      ["CONNECTION", grammar.connection_formula], ["CONTEXT", grammar.context_note],
      ["EXAMPLE_VI", grammar.example_vi], ["PRACTICE", grammar.practice_prompt],
    ].filter(([, value]) => value).map(([label, value]) => `${label}: ${value}`).join("\n");
    sql += `INSERT INTO grammar_point (lesson_id,pattern,meaning,example,note,order_index)\nVALUES (@${lessonVar},${sqlString(grammar.pattern)},${sqlString(grammar.meaning_vi)},${sqlString(grammar.example_ja)},${sqlString(note)},${sqlNumber(grammar.order_index, "0")})\nON DUPLICATE KEY UPDATE pattern=VALUES(pattern), meaning=VALUES(meaning), example=VALUES(example), note=VALUES(note);\n\n`;
  }

  const kanjiRows = rows(model, "LESSON_KANJI").filter(inLevel);
  for (const kanji of kanjiRows) {
    const kanjiVar = safeVariable(kanji.membership_code, "kanji");
    const unicodeCode = kanji.unicode_code || Array.from(String(kanji.character))[0]?.codePointAt(0).toString(16).toUpperCase();
    sql += upsert("kanji", ["character", "unicode_code", "stroke_count", "han_viet", "meaning_vi", "jlpt_level", "stroke_svg_uri"],
      [sqlString(kanji.character), sqlString(unicodeCode), sqlNumber(kanji.stroke_count), sqlString(kanji.han_viet), sqlString(kanji.meaning_vi), sqlString(level), sqlString(kanji.stroke_svg_uri)],
      ["stroke_count", "han_viet", "meaning_vi", "jlpt_level", "stroke_svg_uri"]);
    sql += `SET @${kanjiVar} = (SELECT id FROM kanji WHERE character=${sqlString(kanji.character)});\n\n`;
  }
  for (const link of rows(model, "KANJI_COMPOUND")) {
    const kanji = rows(model, "LESSON_KANJI").find((row) => String(row.membership_code) === String(link.kanji_membership_code));
    if (!kanji || !inLevel(kanji)) continue;
    sql += `INSERT INTO vocabulary_kanji (vocabulary_id,kanji_id,character_position)\nVALUES (@${safeVariable(link.vocabulary_membership_code, "vocab")},@${safeVariable(link.kanji_membership_code, "kanji")},${sqlNumber(link.character_position)})\nON DUPLICATE KEY UPDATE character_position=VALUES(character_position);\n\n`;
  }

  for (const line of rows(model, "DIALOGUE").filter(inLevel)) {
    const lessonVar = safeVariable(line.lesson_code, "lesson");
    const sectionVar = safeVariable(line.lesson_code, "section");
    const roleVar = safeVariable(`${line.lesson_code}-${line.speaker}`, "role");
    const itemVar = safeVariable(line.dialogue_code, "dialogue");
    sql += `INSERT INTO dialogue_role (lesson_id,code,name_vi)\nVALUES (@${lessonVar},${sqlString(line.speaker)},${sqlString(line.speaker)})\nON DUPLICATE KEY UPDATE name_vi=VALUES(name_vi);\n`;
    sql += `SET @${roleVar} = (SELECT id FROM dialogue_role WHERE lesson_id=@${lessonVar} AND code=${sqlString(line.speaker)});\n`;
    const order = 3000 + Number(line.turn_index || 0);
    sql += `INSERT INTO content_item (section_id,content_type,title,content,translated_text,order_index)\nVALUES (@${sectionVar},'LISTENING',${sqlString(line.title)},${sqlString(line.text_ja)},${sqlString(line.translation_vi)},${order})\nON DUPLICATE KEY UPDATE title=VALUES(title), content=VALUES(content), translated_text=VALUES(translated_text);\n`;
    sql += `SET @${itemVar} = (SELECT id FROM content_item WHERE section_id=@${sectionVar} AND order_index=${order});\n`;
    sql += `INSERT INTO dialogue_line_detail (content_item_id,dialogue_role_id,scene_code)\nVALUES (@${itemVar},@${roleVar},${sqlString(line.dialogue_code)})\nON DUPLICATE KEY UPDATE dialogue_role_id=VALUES(dialogue_role_id), scene_code=VALUES(scene_code);\n\n`;
  }

  for (const content of rows(model, "COMPREHENSION").filter(inLevel)) {
    const sectionVar = safeVariable(content.lesson_code, "section");
    const order = 4000 + Number(content.order_index || 0);
    sql += `INSERT INTO content_item (section_id,content_type,title,content,translated_text,order_index)\nVALUES (@${sectionVar},${sqlString(content.skill)},${sqlString(content.title)},${sqlString(content.passage_or_script)},${sqlString(content.translation_vi)},${order})\nON DUPLICATE KEY UPDATE title=VALUES(title), content=VALUES(content), translated_text=VALUES(translated_text);\n\n`;
  }
  return sql;
}

export function resolveCorrectOption(row) {
  const answer = String(row.correct_answer ?? "").trim().toUpperCase();
  const index = ["A", "B", "C", "D"].indexOf(answer);
  if (index >= 0) return index + 1;
  if (/^[1-4]$/.test(answer)) return Number(answer);
  const textIndex = [row.option_a, row.option_b, row.option_c, row.option_d]
    .findIndex((option) => String(option ?? "").trim().toUpperCase() === answer);
  return textIndex >= 0 ? textIndex + 1 : null;
}

function exercisesSql(model, contexts) {
  let sql = sqlHeader("Learning Path assessment questions and lesson quizzes");
  for (const context of contexts.values()) {
    const lessonVar = safeVariable(context.lesson.lesson_code, "lesson");
    const tagVar = safeVariable(context.lesson.knowledge_tag_code, "tag");
    sql += `SET @${lessonVar} = (SELECT l.id FROM lesson l JOIN unit u ON u.id=l.unit_id JOIN textbook t ON t.id=u.textbook_id WHERE l.source_code=${sqlString(context.lesson.lesson_code)} AND u.source_code=${sqlString(context.unit.unit_code)} AND t.source_code=${sqlString(context.book.textbook_code)});\n`;
    sql += `SET @${tagVar} = (SELECT id FROM tag WHERE code=${sqlString(context.lesson.knowledge_tag_code)});\n`;
  }
  sql += "\n";
  for (const sheetName of ["COMPREHENSION", "EXERCISE"]) {
    for (const row of rows(model, sheetName)) {
      if (row.import_question_type === "LESSON_ONLY") continue;
      const context = contexts.get(String(row.lesson_code));
      if (!context) continue;
      const sourceCode = sheetName === "COMPREHENSION" ? row.question_code : row.exercise_code;
      const prompt = sheetName === "COMPREHENSION" ? row.question_text : row.prompt;
      const explanation = sheetName === "COMPREHENSION" ? row.explanation_vi : row.answer_explanation;
      const lessonVar = safeVariable(row.lesson_code, "lesson");
      const tagVar = safeVariable(context.lesson.knowledge_tag_code, "tag");
      const questionVar = safeVariable(sourceCode, "question");
      const quizVar = safeVariable(row.lesson_code, "quiz");
      const contextJson = JSON.stringify({
        sourceBookCode: row.source_book_code,
        sourceLessonRef: row.source_lesson_ref,
        sourcePage: row.source_page ?? null,
        sourceQuestionType: row.question_type,
        answerSourceBookCode: row.answer_source_book_code ?? null,
        answerSourcePage: row.answer_source_page ?? null,
      });
      const option = resolveCorrectOption(row);
      sql += upsert("assessment_question",
        ["source_code", "knowledge_tag_id", "skill", "jlpt_level", "difficulty", "question_type", "title", "passage_html", "audio_url", "question_text", "answer_text", "option_1", "option_2", "option_3", "option_4", "correct_option", "explanation", "context_json", "order_index", "is_active"],
        [sqlString(sourceCode), `@${tagVar}`, sqlString(row.skill), sqlString(context.level), sqlString(row.difficulty || "MEDIUM"), sqlString(row.import_question_type), sqlString(row.title), sqlString(row.passage_or_script), sqlString(row.audio_url), sqlString(prompt), row.import_question_type === "FILL_BLANK" ? sqlString(row.correct_answer) : "NULL", sqlString(row.option_a), sqlString(row.option_b), sqlString(row.option_c), sqlString(row.option_d), option ? String(option) : "NULL", sqlString(explanation), sqlString(contextJson), sqlNumber(row.order_index, "0"), "1"],
        ["knowledge_tag_id", "skill", "jlpt_level", "difficulty", "question_type", "title", "passage_html", "audio_url", "question_text", "answer_text", "option_1", "option_2", "option_3", "option_4", "correct_option", "explanation", "context_json", "order_index", "is_active"]);
      sql += `SET @${questionVar} = (SELECT id FROM assessment_question WHERE source_code=${sqlString(sourceCode)});\n`;
      if (row.import_question_type === "MATCHING") {
        const pairs = [[row.option_a, row.option_b], [row.option_c, row.option_d]].filter(([left, right]) => left && right);
        pairs.forEach(([left, right], index) => {
          sql += `INSERT INTO assessment_question_pair (question_id,left_text,right_text,order_index)\nVALUES (@${questionVar},${sqlString(left)},${sqlString(right)},${index + 1})\nON DUPLICATE KEY UPDATE left_text=VALUES(left_text), right_text=VALUES(right_text);\n`;
        });
      }
      sql += `INSERT INTO assessment_quiz (lesson_id,title,quiz_type,status)\nSELECT @${lessonVar},${sqlString(`${context.lesson.title} - Luyện tập`)},'LESSON','DRAFT'\nWHERE NOT EXISTS (SELECT 1 FROM assessment_quiz WHERE lesson_id=@${lessonVar} AND quiz_type='LESSON');\n`;
      sql += `SET @${quizVar} = (SELECT id FROM assessment_quiz WHERE lesson_id=@${lessonVar} AND quiz_type='LESSON' ORDER BY id LIMIT 1);\n`;
      sql += `INSERT INTO assessment_quiz_question (quiz_id,question_id,order_index)\nVALUES (@${quizVar},@${questionVar},${sqlNumber(row.order_index, "0")})\nON DUPLICATE KEY UPDATE order_index=VALUES(order_index);\n\n`;
    }
  }
  return sql;
}

export async function generateSeed(workbookPath, outputDir) {
  const issues = await validateWorkbook(workbookPath);
  const errors = issues.filter((item) => item.severity === "ERROR");
  if (errors.length) {
    const error = new Error(`Workbook validation failed with ${errors.length} error(s)`);
    error.issues = issues;
    throw error;
  }

  const model = await loadWorkbookModel(workbookPath);
  const contexts = lessonContext(model);
  const generated = new Map([
    [OUTPUT_FILES[0], catalogSql(model)],
    [OUTPUT_FILES[1], levelSql(model, "N5", contexts)],
    [OUTPUT_FILES[2], levelSql(model, "N4", contexts)],
    [OUTPUT_FILES[3], exercisesSql(model, contexts)],
  ]);
  await fs.mkdir(outputDir, { recursive: true });
  for (const [fileName, contents] of generated) {
    await fs.writeFile(path.join(outputDir, fileName), contents, "utf8");
  }
  return {
    input: path.resolve(workbookPath),
    outputDir: path.resolve(outputDir),
    files: [...generated.keys()],
    textbookCount: rows(model, "LP_TEXTBOOK").length,
    unitCount: rows(model, "LP_UNIT").length,
    lessonCount: rows(model, "LP_LESSON").length,
    questionCount: [...rows(model, "COMPREHENSION"), ...rows(model, "EXERCISE")]
      .filter((row) => row.import_question_type !== "LESSON_ONLY").length,
  };
}

function option(argv, name) {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : null;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const args = process.argv.slice(2);
  const input = option(args, "--input");
  const output = option(args, "--output");
  if (!input || !output) {
    console.error("Usage: node generate-learning-seed.mjs --input <workbook.xlsx> --output <directory>");
    process.exitCode = 2;
  } else {
    try {
      console.log(JSON.stringify(await generateSeed(input, output), null, 2));
    } catch (error) {
      console.error(JSON.stringify({ message: error.message, issues: error.issues ?? [] }, null, 2));
      process.exitCode = 1;
    }
  }
}
