import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { collectJapaneseText, validateQuestions } from './validate_owned_grammar_questions.mjs';

const hex = value => `CONVERT(0x${Buffer.from(String(value), 'utf8').toString('hex')} USING utf8mb4)`;
const sha256 = value => createHash('sha256').update(value).digest('hex');

/** Stage AI candidates in an unscored table; this never writes scored quiz tables. */
export function buildDraftSeed(items, { lessonSources = {} } = {}) {
  // The staged row is the AI draft as written: an owner decision (grammar_question_review.mjs apply) lives in the
  // reviewed runtime file, so it never changes the draft's payload or checksum (the guard below refuses changed drafts).
  // Only a decision the review tool recorded (reviewed_by OWNER) is set aside; a draft claiming approval itself is refused.
  const OWNER_DECISIONS = new Set(['REVIEWED', 'REJECTED', 'NEEDS_HUMAN_REVIEW']);
  const asDrafted = items.map((item) => {
    if (!item || typeof item !== 'object' || item.reviewed_by !== 'OWNER' || !OWNER_DECISIONS.has(item.review_status)) return item;
    const { review_note, reviewed_by, reviewed_on, ...draft } = item;
    return { ...draft, review_status: 'NEEDS_HUMAN_REVIEW' };
  });
  const ordered = [...asDrafted].sort((a, b) => String(a?.id).localeCompare(String(b?.id), 'en'));
  const errors = validateQuestions(ordered);
  for (const item of ordered) {
    const match = /^LP-GRAM-(N[1-5])-(\d{2})-/.exec(item.id);
    if (match && item.lesson_source_id !== `SEKAI-${match[1]}-${match[2]}`)
      errors.push(`${item.id}: question ID does not match lesson source`);
    const source = lessonSources[item.lesson_source_id];
    if (source) errors.push(...validateQuestions([item], {
      sourceExamples: collectJapaneseText(source),
      sourceLessonId: item.lesson_source_id,
      sourceGrammarCodes: source.mauCau?.map(point => point.ma) ?? []
    }));
  }
  if (errors.length) throw new Error(errors.join('\n'));
  if (!ordered.length) throw new Error('No AI draft questions supplied');
  let sql = '-- Generated task-owned AI draft questions. UNSCORED PRACTICE ONLY.\n';
  sql += '-- Apply sql/migrations/022_learning_owned_consolidated.sql first. Do not use --force.\n';
  sql += 'SET NAMES utf8mb4;\n';
  sql += 'CREATE TEMPORARY TABLE owned_practice_draft_guard (ok INT NOT NULL CHECK (ok=1));\n';
  for (const item of ordered) {
    const hash = sha256(JSON.stringify(item));
    sql += `INSERT INTO owned_practice_draft_guard SELECT IF(NOT EXISTS(` +
      `SELECT 1 FROM learning_practice_draft_question WHERE BINARY draft_code=BINARY ${hex(item.id)} ` +
      `AND BINARY source_sha256 <> BINARY ${hex(hash)}),1,0);\n`;
  }
  sql += 'START TRANSACTION;\n';
  for (const item of ordered) {
    const payload = JSON.stringify(item);
    const hash = sha256(payload);
    sql += 'INSERT INTO learning_practice_draft_question ' +
      '(draft_code,lesson_source_code,goal_code,jlpt_level,grammar_code,question_type,' +
      'source_status,review_status,payload_json,source_sha256) VALUES (' +
      [item.id, item.lesson_source_id, item.goal, item.level, item.grammar_code, item.type]
        .map(hex).concat(["'AI_DRAFT'", "'NEEDS_HUMAN_REVIEW'", hex(payload), hex(hash)]).join(',') +
      ') ON DUPLICATE KEY UPDATE draft_code=draft_code;\n';
  }
  return sql + 'COMMIT;\n';
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const [inputDirectory, outputFile] = process.argv.slice(2);
  if (!inputDirectory || !outputFile) {
    console.error('Usage: node build_owned_practice_draft_seed.mjs <candidate-dir> <output.sql>');
    process.exitCode = 2;
  } else {
    let files, items;
    if (path.extname(inputDirectory) === '.json') {
      const document = JSON.parse(await fs.readFile(inputDirectory, 'utf8'));
      if (document.schema !== 'JLAS_LEARNING_OWNED_CONTENT_V1') throw new Error('Unknown Learning data schema');
      const records = document.sections?.grammar_question_candidates?.files;
      if (!records) throw new Error('Missing grammar question candidates');
      files = Object.keys(records).sort();
      items = files.flatMap(name => {
        const { text, sha256: expected } = records[name];
        if (sha256(text) !== expected) throw new Error(`Grammar candidate checksum mismatch: ${name}`);
        return JSON.parse(text);
      });
    } else {
      files = (await fs.readdir(inputDirectory)).filter(name => name.endsWith('.json')).sort();
      items = (await Promise.all(files.map(async name =>
        JSON.parse(await fs.readFile(path.join(inputDirectory, name), 'utf8'))))).flat();
    }
    const lessonSources = {};
    for (const lessonId of new Set(items.map(item => item.lesson_source_id))) {
      const match = /^SEKAI-(N[1-5])-(\d{2})$/.exec(lessonId);
      if (!match) throw new Error(`Unsupported source lesson ${lessonId}`);
      const sourcePath = path.join('data', 'seed_jlas', 'sekai', `sekai-${match[1].toLowerCase()}`,
        'json', `bai-${match[2]}.json`);
      lessonSources[lessonId] = JSON.parse(await fs.readFile(sourcePath, 'utf8'));
    }
    const seed = buildDraftSeed(items, { lessonSources });
    await fs.writeFile(outputFile, seed, 'utf8');
    console.log(JSON.stringify({ files: files.length, questions: items.length, sha256: sha256(seed), outputFile }));
  }
}
