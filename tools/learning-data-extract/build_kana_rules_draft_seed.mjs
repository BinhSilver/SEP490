import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const BOOK_CODE = 'LP-OWNED-KANA-SOUNDS';
const UNIT_CODE = 'SPECIAL-SOUNDS';
const codePattern = /^KANA-RULE-(\d{2})(?:-(\d{2}))?$/;

function required(value, label) {
  if (typeof value !== 'string' || !value.trim() || value !== value.trim() || value === '-') {
    throw new Error(`${label} is missing or malformed`);
  }
  return value.normalize('NFC');
}

function indexedCode(code, lessonIndex, itemIndex = null) {
  const match = codePattern.exec(code);
  if (!match || Number(match[1]) !== lessonIndex || (itemIndex == null ? match[2] != null
    : Number(match[2]) !== itemIndex)) throw new Error(`Invalid ID sequence: ${code}`);
}

export function buildKanaRuleBundle(source) {
  if (!source || source.status !== 'CHUA_DUYET' || !Array.isArray(source.lessons) || !source.lessons.length) {
    throw new Error('Only a nonempty CHUA_DUYET review draft can be mapped');
  }
  const seen = new Set();
  const lessons = source.lessons.map((lesson, lessonIndex) => {
    const orderIndex = lessonIndex + 1;
    const code = required(lesson.id, 'Lesson ID');
    indexedCode(code, orderIndex);
    if (seen.has(code)) throw new Error(`Duplicate ID: ${code}`);
    seen.add(code);
    if (lesson.review_status !== 'CHUA_DUYET') throw new Error(`${code} is not a review draft`);
    if (!Array.isArray(lesson.examples) || lesson.examples.length < 2 || lesson.examples.length > 15) {
      throw new Error(`${code} needs 2–15 examples per lesson`);
    }
    const items = lesson.examples.map((example, itemIndex) => {
      const itemCode = required(example.id, 'Item ID');
      indexedCode(itemCode, orderIndex, itemIndex + 1);
      if (seen.has(itemCode)) throw new Error(`Duplicate ID: ${itemCode}`);
      seen.add(itemCode);
      if (example.review_status !== 'CHUA_DUYET') throw new Error(`${itemCode} is not a review draft`);
      const written = required(example.written, `${itemCode} written`);
      const romaji = required(example.romaji, `${itemCode} romaji`);
      const meaningVi = required(example.meaning_vi, `${itemCode} meaning`);
      const focus = required(example.focus, `${itemCode} focus`);
      if (!/^[\u3040-\u30ffー]+$/u.test(written) || !/^[a-z]+$/u.test(romaji)) {
        throw new Error(`${itemCode} has invalid Japanese/romaji text`);
      }
      return { code: itemCode, orderIndex: itemIndex + 1, written, romaji, meaningVi, focus };
    });
    return {
      code, orderIndex, title: required(lesson.title, `${code} title`),
      placement: required(lesson.placement, `${code} placement`),
      ruleVi: required(lesson.rule_vi, `${code} rule`),
      estimatedMinutes: Math.ceil(items.length / 2) + 5,
      status: 'DRAFT', items,
    };
  });
  return {
    version: required(source.version, 'Version'), bookCode: BOOK_CODE, unitCode: UNIT_CODE,
    status: 'DRAFT', itemCount: seen.size - lessons.length,
    references: source.references ?? [], lessons,
  };
}

// Dynamic multilingual content is rendered as UTF-8 hex so quotes/backslashes cannot change SQL syntax.
const hexText = value => `CONVERT(0x${Buffer.from(value, 'utf8').toString('hex')} USING utf8mb4)`;
const asciiCode = value => {
  if (!/^[A-Z0-9-]+$/.test(value)) throw new Error(`Unsafe SQL code: ${value}`);
  return `'${value}'`;
};
const guard = condition => `INSERT INTO lp_kana_draft_guard (ok) SELECT IF(${condition},1,0);`;

export function renderDraftKanaRuleSeed(bundle) {
  if (bundle?.status !== 'DRAFT' || !Array.isArray(bundle.lessons) || !bundle.lessons.length) {
    throw new Error('Only a DRAFT Kana rule bundle can produce a seed');
  }
  const sql = [
    '-- [JLAS-W0108] Task-owned Kana sound rules. Scratch/import review only; never publish this draft.',
    '-- Requires jlas_schema.sql and migration 018 (content_source). Run after seed_data_jlas.sql.',
    '-- Additive and same-bundle repeatable. A changed bundle needs explicit reconciliation, not a blind rerun.',
    'SET NAMES utf8mb4;',
    'CREATE TEMPORARY TABLE IF NOT EXISTS lp_kana_draft_guard (ok TINYINT NOT NULL CHECK (ok = 1));',
    'START TRANSACTION;',
    guard(`(SELECT COUNT(*) FROM textbook base WHERE BINARY base.title = BINARY ${hexText('Bảng chữ cái tiếng Nhật')} AND base.status = 'PUBLISHED') = 1`),
    `INSERT INTO textbook (source_code,title,description,jlpt_level,status,is_learning_path_source) VALUES (${asciiCode(bundle.bookCode)},${hexText('Quy tắc đọc Kana')},${hexText('Bản nháp ví dụ âm đặc biệt Kana đang chờ duyệt.')},NULL,'DRAFT',1) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);`,
    'SET @jlasKanaBookId := LAST_INSERT_ID();',
    guard(`EXISTS (SELECT 1 FROM textbook t WHERE t.id = @jlasKanaBookId AND t.source_code = ${asciiCode(bundle.bookCode)} AND t.status = 'DRAFT' AND t.is_learning_path_source = 1 AND BINARY t.title = BINARY ${hexText('Quy tắc đọc Kana')} AND BINARY t.description = BINARY ${hexText('Bản nháp ví dụ âm đặc biệt Kana đang chờ duyệt.')})`),
    guard(`EXISTS (SELECT 1 FROM textbook base WHERE BINARY base.title = BINARY ${hexText('Bảng chữ cái tiếng Nhật')} AND base.status = 'PUBLISHED' AND base.id < @jlasKanaBookId)`),
    `INSERT INTO unit (textbook_id,source_code,title,description,order_index) VALUES (@jlasKanaBookId,${asciiCode(bundle.unitCode)},${hexText('Âm đặc biệt Kana')},${hexText('Bài học ở mức từ; không thêm ký hiệu vào bảng 208 chữ Kana.')},1) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);`,
    'SET @jlasKanaUnitId := LAST_INSERT_ID();',
    guard(`EXISTS (SELECT 1 FROM unit u WHERE u.id = @jlasKanaUnitId AND u.textbook_id = @jlasKanaBookId AND u.source_code = ${asciiCode(bundle.unitCode)} AND u.order_index = 1 AND BINARY u.title = BINARY ${hexText('Âm đặc biệt Kana')})`),
  ];
  for (const lesson of bundle.lessons) {
    sql.push(`INSERT INTO tag (code,name,tag_type) VALUES (${asciiCode(lesson.code)},${hexText(lesson.title)},'KNOWLEDGE') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);`);
    sql.push('SET @jlasKanaTagId := LAST_INSERT_ID();');
    sql.push(guard(`EXISTS (SELECT 1 FROM tag tg WHERE tg.id = @jlasKanaTagId AND tg.code = ${asciiCode(lesson.code)} AND tg.tag_type = 'KNOWLEDGE' AND BINARY tg.name = BINARY ${hexText(lesson.title)})`));
    sql.push(`INSERT INTO lesson (unit_id,source_code,knowledge_tag_id,title,description,order_index,estimated_minutes,lesson_type,skill,situation_tag,is_review,status) VALUES (@jlasKanaUnitId,${asciiCode(lesson.code)},@jlasKanaTagId,${hexText(lesson.title)},${hexText(lesson.ruleVi)},${lesson.orderIndex},${lesson.estimatedMinutes},'CURRICULUM','READING','FOUNDATION',0,'DRAFT') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);`);
    sql.push('SET @jlasKanaLessonId := LAST_INSERT_ID();');
    sql.push(guard(`EXISTS (SELECT 1 FROM lesson l WHERE l.id = @jlasKanaLessonId AND l.unit_id = @jlasKanaUnitId AND l.source_code = ${asciiCode(lesson.code)} AND l.knowledge_tag_id = @jlasKanaTagId AND l.order_index = ${lesson.orderIndex} AND l.estimated_minutes = ${lesson.estimatedMinutes} AND l.status = 'DRAFT' AND BINARY l.title = BINARY ${hexText(lesson.title)} AND BINARY l.description = BINARY ${hexText(lesson.ruleVi)})`));
    sql.push(`INSERT INTO lesson_section (lesson_id,title,section_type,order_index) VALUES (@jlasKanaLessonId,${hexText('Từ ví dụ')},'EXAMPLE',1) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);`);
    sql.push('SET @jlasKanaSectionId := LAST_INSERT_ID();');
    sql.push(guard(`EXISTS (SELECT 1 FROM lesson_section s WHERE s.id = @jlasKanaSectionId AND s.lesson_id = @jlasKanaLessonId AND s.section_type = 'EXAMPLE' AND s.order_index = 1 AND BINARY s.title = BINARY ${hexText('Từ ví dụ')})`));
    for (const item of lesson.items) {
      sql.push(`INSERT INTO content_item (section_id,content_type,title,content,translated_text,order_index) VALUES (@jlasKanaSectionId,'READING',${hexText(item.romaji)},${hexText(item.written)},${hexText(item.meaningVi)},${item.orderIndex}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);`);
      sql.push('SET @jlasKanaItemId := LAST_INSERT_ID();');
      sql.push(guard(`EXISTS (SELECT 1 FROM content_item ci WHERE ci.id = @jlasKanaItemId AND ci.section_id = @jlasKanaSectionId AND ci.content_type = 'READING' AND ci.order_index = ${item.orderIndex} AND BINARY ci.title = BINARY ${hexText(item.romaji)} AND BINARY ci.content = BINARY ${hexText(item.written)} AND BINARY ci.translated_text = BINARY ${hexText(item.meaningVi)})`));
      const note = `J-LAS review draft; focus=${item.focus}`;
      sql.push(`INSERT INTO content_source (entity_type,entity_id,source_code,stable_code,review_status,note) VALUES ('CONTENT_ITEM',@jlasKanaItemId,'AI_GENERATED',${asciiCode(item.code)},'CHUA_DUYET',${hexText(note)}) ON DUPLICATE KEY UPDATE id=id;`);
      sql.push(guard(`EXISTS (SELECT 1 FROM content_source cs WHERE cs.source_code = 'AI_GENERATED' AND cs.stable_code = ${asciiCode(item.code)} AND cs.entity_type = 'CONTENT_ITEM' AND cs.entity_id = @jlasKanaItemId AND cs.review_status = 'CHUA_DUYET' AND BINARY cs.note = BINARY ${hexText(note)})`));
    }
  }
  sql.push('COMMIT;', '');
  return sql.join('\n');
}

/** Owner 2026-10-21 (OD-33): the Kana rule lessons join FOUNDATION for new paths. Same rows as the draft seed; the
 * book and its lessons are then published, the examples stay CHUA_DUYET ("Nội dung bổ sung J-LAS · cần rà soát"),
 * and every guard accepts an already-published row so the consolidated seed stays re-runnable. */
export const PUBLISHED_OUTPUT = 'outputs/2026-10-21-kana-rules-published-v4/seed.sql';

export function renderPublishedKanaRuleSeed(bundle) {
  const draft = renderDraftKanaRuleSeed(bundle)
    .replace('-- [JLAS-W0108] Task-owned Kana sound rules. Scratch/import review only; never publish this draft.',
      '-- [JLAS-W0108] Task-owned Kana sound rules, published into FOUNDATION (owner 2026-10-21, OD-33); examples stay CHUA_DUYET.')
    .replaceAll("t.status = 'DRAFT'", "t.status IN ('DRAFT','PUBLISHED')")
    .replaceAll("l.status = 'DRAFT'", "l.status IN ('DRAFT','PUBLISHED')");
  const lessonCodes = bundle.lessons.map((lesson) => asciiCode(lesson.code)).join(',');
  const publish = [
    "UPDATE textbook SET status='PUBLISHED' WHERE id=@jlasKanaBookId AND status='DRAFT';",
    `UPDATE lesson SET status='PUBLISHED' WHERE unit_id=@jlasKanaUnitId AND status='DRAFT' AND source_code IN (${lessonCodes});`,
    guard(`EXISTS (SELECT 1 FROM textbook t WHERE t.id = @jlasKanaBookId AND t.status = 'PUBLISHED')`),
    guard(`(SELECT COUNT(*) FROM lesson l WHERE l.unit_id = @jlasKanaUnitId AND l.status = 'PUBLISHED' AND l.source_code IN (${lessonCodes})) = ${bundle.lessons.length}`),
  ];
  const end = draft.lastIndexOf('COMMIT;');
  return `${draft.slice(0, end)}${publish.join('\n')}\n${draft.slice(end)}`;
}

/** Replaces the Kana rule block of the consolidated seed (the draft v3 block or an earlier published one). */
export function embedKanaRuleBlock(seed, bundle) {
  const newline = seed.includes('\r\n') ? '\r\n' : '\n';
  const body = renderPublishedKanaRuleSeed(bundle).trim().replace(/\r\n/g, '\n');
  const sha = createHash('sha256').update(body).digest('hex');
  const block = [`-- BEGIN ${PUBLISHED_OUTPUT} SHA256 ${sha}`, body, `-- END ${PUBLISHED_OUTPUT}`].join('\n')
    .replace(/\n/g, newline);
  for (const name of [PUBLISHED_OUTPUT, 'outputs/2026-10-10-kana-rules-draft-v3/seed-draft.sql']) {
    const start = seed.indexOf(`-- BEGIN ${name} SHA256 `);
    if (start < 0) continue;
    const endMarker = seed.indexOf(`-- END ${name}`, start);
    if (endMarker < 0) throw new Error(`Unclosed SQL section: ${name}`);
    const afterEnd = endMarker + `-- END ${name}`.length;
    return `${seed.slice(0, start)}${block}${seed.slice(afterEnd)}`;
  }
  throw new Error('No Kana rule block found in the consolidated seed');
}

async function writeIfAbsentOrEqual(outputPath, content) {
  try {
    const prior = await fs.readFile(outputPath, 'utf8');
    if (prior !== content) throw new Error(`Refusing to overwrite different artifact: ${outputPath}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, content, 'utf8');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  if (process.argv[2] === '--embed') {
    // node build_kana_rules_draft_seed.mjs --embed <mapping-manifest.json> [--check]
    const bundle = JSON.parse(await fs.readFile(process.argv[3], 'utf8'));
    const seedPath = path.resolve('sql/learning/parts/learning_data_common.sql');
    const seed = await fs.readFile(seedPath, 'utf8');
    const updated = embedKanaRuleBlock(seed, bundle);
    if (process.argv.includes('--check')) {
      if (updated !== seed) throw new Error('Published Kana rule block is stale');
    } else {
      await fs.writeFile(seedPath, updated, 'utf8');
      await writeIfAbsentOrEqual(path.resolve(PUBLISHED_OUTPUT), renderPublishedKanaRuleSeed(bundle));
    }
    process.stdout.write(`Published Kana rule block: ${bundle.lessons.length} lessons, ${bundle.itemCount} examples\n`);
    process.exit(0);
  }
  const [inputPath, outputDir] = process.argv.slice(2);
  if (!inputPath || !outputDir) throw new Error('Usage: node build_kana_rules_draft_seed.mjs <review.json> <output-dir>');
  const bundle = buildKanaRuleBundle(JSON.parse(await fs.readFile(inputPath, 'utf8')));
  await writeIfAbsentOrEqual(path.join(outputDir, 'seed-draft.sql'), renderDraftKanaRuleSeed(bundle));
  await writeIfAbsentOrEqual(path.join(outputDir, 'mapping-manifest.json'), JSON.stringify(bundle, null, 2) + '\n');
  process.stdout.write(`Wrote DRAFT mapping: ${bundle.lessons.length} lessons, ${bundle.itemCount} examples\n`);
}
