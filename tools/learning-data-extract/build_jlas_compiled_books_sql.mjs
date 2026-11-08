// [JLAS-W0108] D3-04 (2026-10-24): renders each J-LAS compiled book (N5, N4, N3) into its block of learning_data_<level>.sql — a
// Learning-owned textbook whose chapters link existing dictionary entries / Kanji, carry their own grammar points, copy the
// dialogues of the chapter book of the same number and copy the checked Minna / Speed Master reading & listening rows.
//   node tools/learning-data-extract/build_jlas_compiled_books_sql.mjs . [--check] [--block-out <dir>]
// Ids live in a reserved range per level and come from each item's stable slot, so a rebuild updates rows in place. Rows
// of an item that left the book are parked under a hidden archive lesson first (no DELETE, no order collision).
import { readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { learningDataFile } from './learning_data_files.mjs';
import { CHAPTERS, DIALOGUE_BOOKS } from './build_jlas_compiled_books.mjs';
import { readLearningContent } from './learning_content_store.mjs';

export const COMPILED_LEVELS = ['N5', 'N4', 'N3'];
export const BASE = Object.freeze({ N5: 5_000_000_000, N4: 6_000_000_000, N3: 7_000_000_000 });
/** Offsets inside a level's range, one per kind of row. */
export const OFFSET = Object.freeze({ unit: 0, lesson: 1_000, section: 10_000, vocabularyItem: 100_000_000,
  grammar: 200_000_000, kanji: 300_000_000, dialogue: 400_000_000 });
const ARCHIVE_UNIT = 99;
const ARCHIVE_LESSON = 1_999;
const ARCHIVE_SECTION = 19_999;
const marker = (level) => `data/learning-owned/learning-content.json#sections.jlas_compiled_book_${level.toLowerCase()}`;
const hex = (value) => `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
const nullableHex = (value) => (value == null || value === '' ? 'NULL' : hex(value));
/** Column types of the temporary word tables: dictionary keys compare like vocabulary_entry.normalized_* (as_cs). */
const JA = 'CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs';
const TEXT = 'CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci';
const pad = (n) => String(n).padStart(2, '0');
/** Raw hex literal for a temporary-table text column (its utf8mb4 column type decodes it); NULL when blank. */
const raw = (value) => (value == null || value === '' ? 'NULL' : `X'${Buffer.from(String(value), 'utf8').toString('hex')}'`);

/**
 * The lesson meaning: the first two distinct Vietnamese meanings. "Hộ chiếu" repeats "hộ chiếu", "toà nhà" / "tòa nhà" and
 * "lịch sử" / "lích sử" are one meaning spelt two ways (compared without tone marks; the first spelling is kept), and
 * "khoá lại" is inside "khoá lại, treo lên" (the longer one is kept), so none of them counts as a second meaning.
 */
export function lessonMeaning(meanings) {
  // Whole-word containment, so "ăn" is not taken as inside "băn khoăn".
  const key = (m) => ` ${m.normalize('NFD').replace(/\p{M}/gu, '').replace(/[đĐ]/g, 'd').toLowerCase()
    .replace(/[\s,;/()（）、]+/g, ' ').trim()} `;
  const chosen = [];
  for (const meaning of meanings.flatMap((m) => m.split(/\s*;\s*/))) {
    const k = key(meaning);
    if (!k.trim() || chosen.some((c) => key(c).includes(k))) continue;
    const inside = chosen.findIndex((c) => k.includes(key(c)));
    if (inside >= 0) chosen[inside] = meaning.trim();
    else if (chosen.length < 2) chosen.push(meaning.trim());
  }
  return chosen.join('; ');
}

function validate(book, hash, level) {
  if (!/^[a-f0-9]{64}$/.test(hash ?? '')) throw new Error(`${level} compiled book checksum is missing.`);
  if (book?.level !== level || book.code !== `LP-OWNED-JLAS-${level}-COMPILED` || book.chapters?.length !== CHAPTERS[level]) {
    throw new Error(`${level} compiled book is not the expected shape.`);
  }
  for (const list of [book.vocabulary, book.grammar]) {
    const slots = list.map((x) => x.slot);
    if (!slots.every((s) => Number.isInteger(s) && s > 0 && s < 100_000_000) || new Set(slots).size !== slots.length) {
      throw new Error(`${level} compiled book has invalid slots.`);
    }
  }
  if (book.grammar.some((g) => !g.meaning || !g.example)) throw new Error(`${level} grammar point without meaning or example.`);
  if (book.vocabulary.some((v) => !v.meanings_vi.length || !v.variants.length)) throw new Error(`${level} word without meaning.`);
}

/** Pure: the SQL block of one level. */
export function renderCompiledBookSql(book, hash, level) {
  validate(book, hash, level);
  const B = BASE[level];
  const L = level.toLowerCase();
  const id = (offset, n) => String(B + offset + n);
  const lessonId = (chapter) => id(OFFSET.lesson, chapter);
  const unitOf = (chapter) => Math.floor((chapter - 1) / 5) + 1;
  const vocabSection = (chapter) => id(OFFSET.section, chapter * 10 + 1);
  const byId = (list) => new Map(list.map((x) => [x.id, x]));
  const words = byId(book.vocabulary); const points = byId(book.grammar); const chars = byId(book.kanji);
  const comprehension = byId([...book.reading, ...book.listening]);
  const lines = [
    `-- BEGIN ${marker(level)} SHA256 ${hash}`,
    `-- [JLAS-W0108] D3-04 (owner 2026-10-24): J-LAS ${level} compiled book — every book of the level merged per skill,`,
    '-- deduplicated, easy → hard. Learning-owned rows only; source books, dictionary entries and Kanji are read, never changed',
    '-- (a word missing from the dictionary is added as a DRAFT textbook entry). Generated: do not hand-edit.',
    'DELIMITER $$',
    `DROP PROCEDURE IF EXISTS jlas_assert_compiled_${L}$$`,
    `CREATE PROCEDURE jlas_assert_compiled_${L}()`,
    'BEGIN',
    `  IF EXISTS (SELECT 1 FROM textbook WHERE id=${B} AND source_code<>'${book.code}') THEN`,
    `    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Compiled ${level} textbook id collision';`,
    '  END IF;',
    ...(DIALOGUE_BOOKS[level] ? [
      `  IF (SELECT COUNT(*) FROM lesson l JOIN unit u ON u.id=l.unit_id WHERE u.textbook_id=${DIALOGUE_BOOKS[level]}`,
      `      AND l.lesson_type='CURRICULUM') <> ${CHAPTERS[level]} THEN`,
      `    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Dialogue source book of ${level} changed its chapter count';`,
      '  END IF;'] : []),
    'END$$',
    'DELIMITER ;',
    `CALL jlas_assert_compiled_${L}();`,
    `DROP PROCEDURE jlas_assert_compiled_${L};`,
    `DROP TEMPORARY TABLE IF EXISTS jlas_compiled_word_${L};`,
    `DROP TEMPORARY TABLE IF EXISTS jlas_compiled_variant_${L};`,
    `CREATE TEMPORARY TABLE jlas_compiled_word_${L} (item_id BIGINT NOT NULL PRIMARY KEY, section_id BIGINT NOT NULL, order_index INT NOT NULL,`
      + ` expression VARCHAR(255) ${TEXT} NOT NULL, reading VARCHAR(255) ${TEXT} NOT NULL, normalized_expression VARCHAR(255) ${JA} NOT NULL,`
      + ` normalized_reading VARCHAR(255) ${JA} NOT NULL, meaning TEXT ${TEXT} NOT NULL, example_ja TEXT ${TEXT}, example_vi TEXT ${TEXT},`
      + ' vocab_id BIGINT UNSIGNED);',
    `CREATE TEMPORARY TABLE jlas_compiled_variant_${L} (item_id BIGINT NOT NULL, rank_no INT NOT NULL, normalized_expression VARCHAR(255) ${JA}`
      + ` NOT NULL, normalized_reading VARCHAR(255) ${JA} NOT NULL, PRIMARY KEY (item_id, rank_no));`,
    'START TRANSACTION;',
    `INSERT INTO tag (code,name,tag_type) VALUES ('${book.code}',${hex(book.title)},'KNOWLEDGE') ON DUPLICATE KEY UPDATE name=VALUES(name);`,
    `SET @compiled_${L}_tag=(SELECT id FROM tag WHERE code='${book.code}' LIMIT 1);`,
    `INSERT INTO textbook (id,source_code,title,description,jlpt_level,status,version,is_learning_path_source) VALUES (${B},'${book.code}',`
      + `${hex(book.title)},${hex(`Giáo trình J-LAS ${level} tổng hợp: từ vựng, Kanji, ngữ pháp, đọc, nghe${DIALOGUE_BOOKS[level] ? ', hội thoại' : ''}.`)},'${level}','PUBLISHED',1,1)`
      + ' ON DUPLICATE KEY UPDATE title=VALUES(title),description=VALUES(description),status=VALUES(status),is_learning_path_source=VALUES(is_learning_path_source);',
  ];
  const units = Math.ceil(CHAPTERS[level] / 5);
  for (let u = 1; u <= units; u++) {
    lines.push(`INSERT INTO unit (id,textbook_id,source_code,title,order_index) VALUES (${id(OFFSET.unit, u)},${B},'${book.code}-U${pad(u)}',`
      + `${hex(`Phần ${u}`)},${u}) ON DUPLICATE KEY UPDATE title=VALUES(title),order_index=VALUES(order_index);`);
  }
  lines.push(`INSERT INTO unit (id,textbook_id,source_code,title,order_index) VALUES (${id(OFFSET.unit, ARCHIVE_UNIT)},${B},'${book.code}-ARCHIVE',`
    + `${hex('Lưu trữ (không học)')},${ARCHIVE_UNIT}) ON DUPLICATE KEY UPDATE order_index=VALUES(order_index);`);
  for (const chapter of book.chapters) {
    const n = chapter.number;
    const minutes = Math.max(1, Math.ceil(chapter.vocabulary.length * 0.8 + chapter.kanji.length * 1.05 + chapter.grammar.length * 1.3));
    lines.push(`INSERT INTO lesson (id,unit_id,source_code,knowledge_tag_id,title,order_index,estimated_minutes,lesson_type,skill,is_review,status)`
      + ` VALUES (${lessonId(n)},${id(OFFSET.unit, unitOf(n))},'${book.code}-C${pad(n)}',@compiled_${L}_tag,${hex(`Bài ${n}`)},${((n - 1) % 5) + 1},`
      + `${minutes},'CURRICULUM','GRAMMAR',0,'PUBLISHED') ON DUPLICATE KEY UPDATE unit_id=VALUES(unit_id),title=VALUES(title),`
      + 'order_index=VALUES(order_index),estimated_minutes=VALUES(estimated_minutes),status=VALUES(status);');
    lines.push(`INSERT INTO lesson_section (id,lesson_id,title,section_type,order_index) VALUES (${vocabSection(n)},${lessonId(n)},`
      + `${hex('Từ vựng')},'EXPLANATION',1) ON DUPLICATE KEY UPDATE lesson_id=VALUES(lesson_id),order_index=VALUES(order_index);`);
  }
  lines.push(`INSERT INTO lesson (id,unit_id,source_code,knowledge_tag_id,title,order_index,lesson_type,skill,is_review,status) VALUES`
    + ` (${id(OFFSET.lesson, ARCHIVE_LESSON)},${id(OFFSET.unit, ARCHIVE_UNIT)},'${book.code}-ARCHIVE',@compiled_${L}_tag,${hex('Lưu trữ')},1,`
    + "'REVIEW','GRAMMAR',1,'ARCHIVED') ON DUPLICATE KEY UPDATE status='ARCHIVED';");
  lines.push(`INSERT INTO lesson_section (id,lesson_id,title,section_type,order_index) VALUES (${id(OFFSET.section, ARCHIVE_SECTION)},`
    + `${id(OFFSET.lesson, ARCHIVE_LESSON)},${hex('Lưu trữ')},'EXPLANATION',1) ON DUPLICATE KEY UPDATE lesson_id=VALUES(lesson_id);`);
  // Park every generated row of this book; the upserts below put each current one back in its chapter.
  // [JLAS-W0108] 2026-11-01: only rows that sit in this book are parked. Auto-increment rows inserted after this block (a
  // higher level's data file, or any other module) can land inside the reserved id range and must never be taken over.
  const range = (offset) => `BETWEEN ${B + offset} AND ${B + offset + 99_999_999}`;
  const bookLessons = `(SELECT l.id FROM lesson l JOIN unit u ON u.id=l.unit_id WHERE u.textbook_id=${B})`;
  const bookSections = `(SELECT s.id FROM lesson_section s JOIN lesson l ON l.id=s.lesson_id JOIN unit u ON u.id=l.unit_id WHERE u.textbook_id=${B})`;
  lines.push(`UPDATE content_item SET section_id=${id(OFFSET.section, ARCHIVE_SECTION)},order_index=1000000+(id-${B + OFFSET.vocabularyItem})`
    + ` WHERE id ${range(OFFSET.vocabularyItem)} AND section_id IN ${bookSections};`);
  lines.push(`UPDATE grammar_point SET lesson_id=${id(OFFSET.lesson, ARCHIVE_LESSON)},order_index=1000000+(id-${B + OFFSET.grammar})`
    + ` WHERE id ${range(OFFSET.grammar)} AND lesson_id IN ${bookLessons};`);
  lines.push(`UPDATE lesson_kanji SET lesson_id=${id(OFFSET.lesson, ARCHIVE_LESSON)} WHERE id ${range(OFFSET.kanji)} AND lesson_id IN ${bookLessons};`);

  // Vocabulary, set-based: one temporary-table row per word (raw hex literals), then a few statements add missing dictionary
  // entries and link every word. Far smaller than a statement group per word, so the seed stays under GitHub's 50 MB warning.
  const W = `jlas_compiled_word_${L}`;
  const V = `jlas_compiled_variant_${L}`;
  const wordRows = []; const variantRows = [];
  for (const chapter of book.chapters) {
    chapter.vocabulary.forEach((wordId, index) => {
      const w = words.get(wordId);
      const item = id(OFFSET.vocabularyItem, w.slot);
      wordRows.push(`(${item},${vocabSection(chapter.number)},${index + 1},${raw(w.expression)},${raw(w.reading)},${raw(w.variants[0][0])},`
        + `${raw(w.variants[0][1])},${raw(lessonMeaning(w.meanings_vi))},${raw(w.example_ja)},${raw(w.example_vi)})`);
      // The first variant is the word row itself (copied in SQL below); only the other spellings are listed.
      w.variants.slice(1).forEach(([e, r], rank) => variantRows.push(`(${item},${rank + 2},${raw(e)},${raw(r)})`));
    });
  }
  for (let i = 0; i < wordRows.length; i += 500) {
    lines.push(`INSERT INTO ${W} (item_id,section_id,order_index,expression,reading,normalized_expression,normalized_reading,meaning,example_ja,`
      + `example_vi) VALUES\n${wordRows.slice(i, i + 500).join(',\n')};`);
  }
  for (let i = 0; i < variantRows.length; i += 1000) {
    lines.push(`INSERT INTO ${V} (item_id,rank_no,normalized_expression,normalized_reading) VALUES\n${variantRows.slice(i, i + 1000).join(',\n')};`);
  }
  lines.push(`INSERT INTO ${V} (item_id,rank_no,normalized_expression,normalized_reading) SELECT item_id,1,normalized_expression,normalized_reading`
    + ` FROM ${W} WHERE item_id>0;`);
  const variantMatch = `FROM ${V} v JOIN vocabulary_entry e ON e.normalized_expression=v.normalized_expression`
    + ' AND e.normalized_reading=v.normalized_reading WHERE v.item_id=w.item_id';
  lines.push('INSERT INTO vocabulary_entry (expression,normalized_expression,reading,normalized_reading,meaning,jlpt_level,example_ja,example_vi,'
    + `usage_scope,dictionary_status) SELECT w.expression,w.normalized_expression,w.reading,w.normalized_reading,w.meaning,'${level}',w.example_ja,`
    + `w.example_vi,'TEXTBOOK','DRAFT' FROM ${W} w WHERE NOT EXISTS (SELECT 1 ${variantMatch}) ORDER BY w.section_id,w.order_index;`);
  lines.push(`UPDATE ${W} w SET w.vocab_id=(SELECT e.id ${variantMatch} ORDER BY v.rank_no LIMIT 1) WHERE w.item_id>0;`);
  lines.push(`INSERT INTO content_item (id,section_id,content_type,title,content,order_index) SELECT w.item_id,w.section_id,'READING',w.expression,`
    + `w.expression,w.order_index FROM ${W} w ORDER BY w.item_id ON DUPLICATE KEY UPDATE section_id=VALUES(section_id),title=VALUES(title),`
    + 'content=VALUES(content),order_index=VALUES(order_index);');
  lines.push('INSERT INTO content_vocabulary (content_item_id,vocabulary_id,learning_meaning_vi,learning_example_ja,learning_example_vi)'
    // A word with no example of its own takes its dictionary entry's example pair (the lesson snapshot replaces the entry's).
    + ' SELECT w.item_id,w.vocab_id,w.meaning,IF(w.example_ja IS NULL,e.example_ja,w.example_ja),IF(w.example_ja IS NULL,e.example_vi,w.example_vi)'
    + ` FROM ${W} w JOIN vocabulary_entry e ON e.id=w.vocab_id ORDER BY w.item_id ON DUPLICATE KEY UPDATE`
    + ' learning_meaning_vi=VALUES(learning_meaning_vi),learning_example_ja=VALUES(learning_example_ja),learning_example_vi=VALUES(learning_example_vi);');
  lines.push(`DELETE cv FROM content_vocabulary cv JOIN ${W} w ON w.item_id=cv.content_item_id WHERE cv.vocabulary_id<>w.vocab_id;`);

  const groups = [];
  for (const chapter of book.chapters) {
    const n = chapter.number;
    chapter.grammar.forEach((pointId, index) => {
      const g = points.get(pointId);
      lines.push(`INSERT INTO grammar_point (id,lesson_id,pattern,meaning,example,note,order_index) VALUES (${id(OFFSET.grammar, g.slot)},`
        + `${lessonId(n)},${hex(g.pattern.slice(0, 500))},${hex(g.meaning)},${hex(g.example)},${nullableHex(g.note)},${index + 1}) ON DUPLICATE KEY`
        + ' UPDATE lesson_id=VALUES(lesson_id),pattern=VALUES(pattern),meaning=VALUES(meaning),example=VALUES(example),note=VALUES(note),'
        + 'order_index=VALUES(order_index);');
    });
    // Only a Kanji the dictionary can teach (a meaning or Hán Việt, and a reading) joins its chapter; one it cannot yet
    // teach stays parked until the dictionary has it, and a later re-apply links it.
    chapter.kanji.forEach((kanjiId, index) => {
      const k = chars.get(kanjiId);
      lines.push(`INSERT INTO lesson_kanji (id,lesson_id,kanji_id,order_index) SELECT ${id(OFFSET.kanji, k.character.codePointAt(0))},${lessonId(n)},`
        + `k.id,${index + 1} FROM kanji k WHERE BINARY k.\`character\`=BINARY ${hex(k.character)}`
        + " AND (COALESCE(TRIM(k.meaning_vi),'')<>'' OR COALESCE(TRIM(k.han_viet),'')<>'')"
        + ' AND EXISTS (SELECT 1 FROM kanji_reading r WHERE r.kanji_id=k.id) ON DUPLICATE KEY UPDATE'
        + ' lesson_id=VALUES(lesson_id),order_index=VALUES(order_index);');
    });
    if (chapter.dialogue_source) lines.push(...dialogueCopy(chapter.dialogue_source, n, B, L, lessonId(n)));
    for (const [skill, ids] of [['READ', chapter.reading], ['LISTEN', chapter.listening]]) {
      ids.forEach((sourceId, index) => {
        const { source_group: source, labels } = comprehension.get(sourceId);
        // §2.32 T2: the store labels travel with every copy (chapter_hint is the chapter it is copied into).
        const labelsJson = labels ? `,'$.labels',CAST(${hex(JSON.stringify({ ...labels, chapter_hint: n }))} AS JSON)` : '';
        const group = `${book.code.replace('-COMPILED', '')}-C${pad(n)}-${skill}-P${index + 1}`;
        groups.push(group);
        lines.push(`-- COMPREHENSION ${group} <- ${source}`);
        lines.push(`INSERT INTO assessment_question (source_code,conversion_rule_id,knowledge_tag_id,topic_tag_id,outcome_id,grammar_point_id,`
          + 'vocabulary_entry_id,skill,jlpt_level,difficulty,question_type,title,passage_html,audio_url,image_url,question_text,answer_text,'
          + 'option_1,option_2,option_3,option_4,correct_option,explanation,context_json,quiz_id,order_index,is_active)'
          + ` SELECT CONCAT('${group}',SUBSTRING(q.source_code,${source.length + 1})),q.conversion_rule_id,q.knowledge_tag_id,q.topic_tag_id,`
          + 'q.outcome_id,q.grammar_point_id,q.vocabulary_entry_id,q.skill,q.jlpt_level,q.difficulty,q.question_type,q.title,q.passage_html,'
          + 'q.audio_url,q.image_url,q.question_text,q.answer_text,q.option_1,q.option_2,q.option_3,q.option_4,q.correct_option,q.explanation,'
          + `JSON_SET(COALESCE(q.context_json,'{}'),'$.compiled_from',q.source_code${labelsJson}),q.quiz_id,q.order_index,1 FROM assessment_question q`
          + ` WHERE q.source_code LIKE '${source}-%' AND q.is_active=1 ON DUPLICATE KEY UPDATE knowledge_tag_id=VALUES(knowledge_tag_id),`
          + 'skill=VALUES(skill),jlpt_level=VALUES(jlpt_level),difficulty=VALUES(difficulty),question_type=VALUES(question_type),title=VALUES(title),'
          + 'passage_html=VALUES(passage_html),audio_url=VALUES(audio_url),image_url=VALUES(image_url),question_text=VALUES(question_text),'
          + 'answer_text=VALUES(answer_text),option_1=VALUES(option_1),option_2=VALUES(option_2),option_3=VALUES(option_3),'
          + 'option_4=VALUES(option_4),correct_option=VALUES(correct_option),explanation=VALUES(explanation),context_json=VALUES(context_json),'
          + 'is_active=1;');
      });
    }
  }
  // A copy stays active only while its group is still in the book and its source question is active. Done last, so an
  // unchanged copy is never touched (re-applying leaves updated_at as it was).
  const prefix = book.code.replace('-COMPILED', '');
  lines.push(`UPDATE assessment_question c LEFT JOIN assessment_question s ON s.source_code=JSON_UNQUOTE(JSON_EXTRACT(c.context_json,'$.compiled_from'))`
    + ` AND s.is_active=1 SET c.is_active=0 WHERE c.source_code LIKE '${prefix}-C%' AND c.is_active=1 AND (s.id IS NULL OR`
    + ` SUBSTRING_INDEX(c.source_code,'-',7) NOT IN (${groups.map((g) => `'${g}'`).join(',') || "''"}));`);
  lines.push('COMMIT;', `DROP TEMPORARY TABLE IF EXISTS ${W};`, `DROP TEMPORARY TABLE IF EXISTS ${V};`, `-- END ${marker(level)}`, '');
  return lines.join('\n');
}

/** Copies the dialogues of chapter `chapter` of the source book (sections, lines, roles, chunks, audio) under the compiled lesson. */
function dialogueCopy({ textbook, chapter }, n, B, L, lesson) {
  const shift = B + OFFSET.dialogue;
  const source = `@dialogue_src_${L}`;
  const inSource = 'JOIN lesson_section ls ON ls.id=ci.section_id';
  return [
    `SET ${source}=(SELECT l.id FROM lesson l JOIN unit u ON u.id=l.unit_id WHERE u.textbook_id=${textbook} AND l.lesson_type='CURRICULUM'`
      + ` AND (u.order_index-1)*5+l.order_index=${chapter} LIMIT 1);`,
    `INSERT INTO dialogue_role (id,lesson_id,code,name_vi,name_ja,description,voice_code) SELECT ${shift}+dr.id,${lesson},dr.code,dr.name_vi,`
      + `dr.name_ja,dr.description,dr.voice_code FROM dialogue_role dr WHERE dr.lesson_id=${source} ON DUPLICATE KEY UPDATE name_vi=VALUES(name_vi),`
      + 'name_ja=VALUES(name_ja),description=VALUES(description),voice_code=VALUES(voice_code);',
    `INSERT INTO lesson_section (id,lesson_id,title,section_type,order_index) SELECT DISTINCT ${shift}+ls.id,${lesson},ls.title,ls.section_type,`
      + `100+ls.order_index FROM lesson_section ls JOIN content_item ci ON ci.section_id=ls.id JOIN dialogue_line_detail d ON d.content_item_id=ci.id`
      + ` WHERE ls.lesson_id=${source} ON DUPLICATE KEY UPDATE title=VALUES(title),order_index=VALUES(order_index);`,
    `INSERT INTO content_item (id,section_id,content_type,topic_tag_id,title,content,translated_text,order_index) SELECT ${shift}+ci.id,`
      + `${shift}+ci.section_id,ci.content_type,ci.topic_tag_id,ci.title,ci.content,ci.translated_text,ci.order_index FROM content_item ci ${inSource}`
      + ` JOIN dialogue_line_detail d ON d.content_item_id=ci.id WHERE ls.lesson_id=${source} ON DUPLICATE KEY UPDATE title=VALUES(title),`
      + 'content=VALUES(content),translated_text=VALUES(translated_text),order_index=VALUES(order_index);',
    `INSERT INTO dialogue_line_detail (content_item_id,dialogue_role_id,scene_code,bubble_side,bubble_x,bubble_y) SELECT ${shift}+d.content_item_id,`
      + `CASE WHEN d.dialogue_role_id IS NULL THEN NULL ELSE ${shift}+d.dialogue_role_id END,d.scene_code,d.bubble_side,d.bubble_x,d.bubble_y`
      + ` FROM dialogue_line_detail d JOIN content_item ci ON ci.id=d.content_item_id ${inSource} WHERE ls.lesson_id=${source}`
      + ' ON DUPLICATE KEY UPDATE dialogue_role_id=VALUES(dialogue_role_id),scene_code=VALUES(scene_code),bubble_side=VALUES(bubble_side);',
    `INSERT INTO dialogue_line_chunk (id,content_item_id,order_index,surface,meaning_vi) SELECT ${shift}+c.id,${shift}+c.content_item_id,`
      + `c.order_index,c.surface,c.meaning_vi FROM dialogue_line_chunk c JOIN content_item ci ON ci.id=c.content_item_id ${inSource}`
      + ` WHERE ls.lesson_id=${source} ON DUPLICATE KEY UPDATE surface=VALUES(surface),meaning_vi=VALUES(meaning_vi);`,
    `INSERT INTO content_audio (id,owner_type,owner_id,voice_code,audio_url,merged_url,start_ms,end_ms) SELECT ${shift}+a.id,a.owner_type,`
      + `${shift}+a.owner_id,a.voice_code,a.audio_url,a.merged_url,a.start_ms,a.end_ms FROM content_audio a JOIN content_item ci`
      + ` ON a.owner_type='CONTENT_ITEM' AND ci.id=a.owner_id JOIN dialogue_line_detail d ON d.content_item_id=ci.id ${inSource}`
      + ` WHERE ls.lesson_id=${source} ON DUPLICATE KEY UPDATE audio_url=VALUES(audio_url),merged_url=VALUES(merged_url);`,
  ];
}

export function replaceBlock(sql, block, level) {
  const newline = sql.includes('\r\n') ? '\r\n' : '\n';
  const formatted = block.replace(/\r?\n/g, newline);
  const begin = `-- BEGIN ${marker(level)} SHA256 `;
  const end = `-- END ${marker(level)}`;
  const start = sql.indexOf(begin);
  if (start < 0) return `${sql.trimEnd()}${newline}${newline}${formatted}`;
  const endStart = sql.indexOf(end, start);
  if (endStart < 0) throw new Error(`Unclosed SQL section: ${marker(level)}`);
  const afterEnd = sql.indexOf('\n', endStart);
  return `${sql.slice(0, start)}${formatted}${afterEnd < 0 ? '' : sql.slice(afterEnd + 1)}`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const outAt = process.argv.indexOf('--block-out');
  // Owner 2026-11-01: each compiled book lives in the data file of its level.
  for (const level of COMPILED_LEVELS) {
  const sqlPath = learningDataFile(root, level);
  const current = await readFile(sqlPath, 'utf8');
  const name = `jlas_compiled_book_${level.toLowerCase()}`;
  const block = renderCompiledBookSql(data.sections[name], data.section_sha256[name], level);
  const updated = replaceBlock(current, block, level);
  if (outAt > 0) await writeFile(path.join(process.argv[outAt + 1], `compiled-${level.toLowerCase()}-block.sql`), block, 'utf8');
  console.log(`${level}: ${block.split('\n').length} lines, ${(block.length / 1e6).toFixed(1)} MB`);
  if (process.argv.includes('--check')) {
    if (updated !== current) throw new Error(`Compiled book ${level} SQL block is stale.`);
  } else {
    // Windows may hold the large seed open for a moment (editor/indexer): write beside it, then swap it in, retrying.
    await writeFile(`${sqlPath}.tmp`, updated, 'utf8');
    for (let attempt = 1; ; attempt++) {
      try {
        await rename(`${sqlPath}.tmp`, sqlPath);
        break;
      } catch (error) {
        if (attempt >= 8 || !['UNKNOWN', 'EBUSY', 'EPERM', 'EACCES'].includes(error.code)) throw error;
        await new Promise((resolve) => setTimeout(resolve, 750 * attempt));
      }
    }
  }
  }
}
