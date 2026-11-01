import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
// Owner 2026-11-01: the Learning flow keeps ONE schema file and its data split into a common file plus one file per JLPT
// level, applied in this order (a level file may read rows of a lower level, never of a higher one). Each file stays under
// GitHub's 50 MB warning limit; the checks below read the data files as one text in run order.
const migrationFile = path.join(root, 'sql/learning/learning_schema.sql');
export const DATA_LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
export const SEED_FILES = ['sql/learning/learning_data_common.sql', ...DATA_LEVELS.map((level) => `sql/learning/learning_data_${level}.sql`)];
export const MAX_SEED_PART_BYTES = 50 * 1024 * 1024;
export function readLearningSeed(base = root) {
  return SEED_FILES.map((file) => fs.readFileSync(path.join(base, file), 'utf8')).join('\n');
}
/** Per-file rules: header, and an (empty or generated) jpath region in every level file. */
export function verifyDataFiles(base = root) {
  for (const [index, file] of SEED_FILES.entries()) {
    const text = fs.readFileSync(path.join(base, file), 'utf8');
    const size = Buffer.byteLength(text);
    if (size > MAX_SEED_PART_BYTES) throw new Error(`${file} is ${(size / 1048576).toFixed(1)} MiB, above the 50 MiB cap`);
    const level = index === 0 ? null : DATA_LEVELS[index - 1];
    const head = level ? `-- [JLAS-W0108] Learning flow data - JLPT ${level} ` : '-- [JLAS-W0108] Learning flow data - common part';
    if (!text.startsWith(head)) throw new Error(`Unexpected header in ${file}`);
    const hasJpath = /^-- BEGIN GENERATED jpath\r?$/m.test(text) && /^-- END GENERATED jpath\r?$/m.test(text);
    if (level && !hasJpath) throw new Error(`${file} lacks its GENERATED jpath region`);
    if (!level && hasJpath) throw new Error(`${file} must not carry a jpath region`);
    for (const match of text.matchAll(/^-- BEGIN (?:data\/learning-owned\/learning-content\.json#sections\.[a-z0-9_]+@(N[1-5])|\S+#(N[1-5]|COMMON)) SHA256 /gm)) {
      const part = match[1] ?? match[2];
      if ((part === 'COMMON' ? null : part) !== level) throw new Error(`${file} holds a block of ${part}: ${match[0]}`);
    }
  }
}
// Fail closed (2026-10-19 review): every DELETE/DROP line must be one of the known, scoped forms.
// The second DELETE form (D3-04 compiled books) is set-based, scoped to the words of that block's own temporary table.
export const ALLOWED_DELETE = new RegExp('^DELETE cv FROM content_vocabulary cv (?:'
  + String.raw`WHERE cv\.content_item_id=@item_[a-z0-9_]+ AND cv\.vocabulary_id<>@vocab_[a-z0-9_]+`
  + String.raw`|JOIN jlas_compiled_word_n[1-5] w ON w\.item_id=cv\.content_item_id WHERE cv\.vocabulary_id<>w\.vocab_id);$`);
// §2.32 T4: widening a CHECK constraint drops and re-adds it inside one ALTER TABLE (no rows are touched).
export const ALLOWED_DROP = /^DROP (?:PROCEDURE (?:IF EXISTS )?jlas_[a-z0-9_]+(?:;|\/\/|\$\$)|TEMPORARY TABLE IF EXISTS jlas_[a-z0-9_]+;|CHECK chk_[a-z0-9_]+,)$/;
export function verify(migration, seed, content = null) {
  if (!migration.startsWith('-- [JLAS-W0108] Learning flow schema'))
    throw new Error('Unexpected Learning schema header');
  if (!seed?.startsWith('-- [JLAS-W0108] Learning flow data - common part'))
    throw new Error('Unexpected Learning seed header');
  for (const marker of [
    'learning_practice_draft_question', 'learning_owned_source_draft',
    'draft_answers_json', 'jlpt_mock_attempt',
  ]) if (!migration.includes(marker)) throw new Error(`Missing ${marker}`);
  for (const marker of [
    'learning_owned_sekai_n4_core.sql', 'learning_owned_n4_kanji_smart_test.sql',
    'learning_owned_vocab_link_repair.sql#N5', 'learning_owned_vocab_link_repair.sql#N4',
    'learning-content.json#sections.sekai_n5_comprehension',
    'learning-content.json#sections.jlpt_minna_n5_n4_listening@N5', 'learning-content.json#sections.jlpt_minna_n5_n4_listening@N4',
    'learning-content.json#sections.jlpt_minna_n5_n4_reading@N5', 'learning-content.json#sections.jlpt_minna_n5_n4_reading@N4',
    'learning-content.json#sections.jlpt_speed_n3_comprehension',
    'learning-content.json#sections.jlpt_speed_n5_n4_reading@N5', 'learning-content.json#sections.jlpt_speed_n5_n4_reading@N4',
    'learning-content.json#sections.jlas_compiled_book_n5',
    'learning-content.json#sections.jlas_compiled_book_n4',
    'learning-content.json#sections.jlas_compiled_book_n3',
    'learning-content.json#sections.jlas_store_pool@N4', 'learning-content.json#sections.jlas_store_pool@N3',
  ]) if (!seed.split('\n').some((line) => line.startsWith('-- BEGIN ') && line.includes(`/${marker} SHA256 `)))
    throw new Error(`Missing ${marker}`);
  // Owner 2026-11-01: the LP-OWNED content_source provenance rows were deleted on purpose and are never re-created.
  if (/^INSERT INTO content_source\b[^\n]*'VNJPCLUB'/m.test(seed)) throw new Error('LP-OWNED content_source provenance must not be re-created');
  if (!seed.includes('-- BEGIN generated communication-goal lessons (learning-content.json)'))
    throw new Error('Missing generated communication-goal lessons');
  if (content) {
    // V05/V06: every JSON-rendered block names the SHA-256 of the section(s) it came from; a stale block fails here.
    const hashes = content.section_sha256 ?? {};
    for (const match of seed.matchAll(/^-- BEGIN data\/learning-owned\/learning-content\.json#sections\.([a-z0-9_]+)(?:@N[1-5])? SHA256 ([0-9a-f]{64})\r?$/gm)) {
      if (hashes[match[1]] !== match[2]) throw new Error(`Seed block is stale for section ${match[1]}`);
    }
    const comm = /^-- SOURCE SHA256 communication_path_release=([0-9a-f]{64}) goal_source_snapshots=([0-9a-f]{64})\r?$/m.exec(seed);
    if (!comm) throw new Error('Communication block has no source SHA-256 line');
    if (hashes.communication_path_release !== comm[1] || hashes.goal_source_snapshots !== comm[2])
      throw new Error('Communication block is stale for its source sections');
  }
  if (!seed.includes('-- [JLAS-W0108] Approved Smart Test N3 corrections'))
    throw new Error('Missing approved N3 Smart Test corrections');
  for (const key of ["'KANJI-DIVERSE-N3-020'", "'KANJI-DIVERSE-N3-001'"])
    if (!seed.includes(key)) throw new Error(`Missing ${key}`);
  const migrationSql = migration.split('\n').filter((line) => !/^\s*--/.test(line)).join('\n');
  const seedSql = seed.split('\n').filter((line) => !/^\s*--/.test(line)).join('\n');
  // SOURCE is a one-argument client command; a column definition such as "source VARCHAR(255) NULL," is not.
  if (/^\s*(?:DROP\s+TABLE|TRUNCATE\b|DELETE\s+FROM|CREATE\s+DATABASE|USE\s+\S+|SOURCE\s+\S+\s*;?\s*$)/im.test(`${migrationSql}\n${seedSql}`))
    throw new Error('Destructive or DB-switching SQL');
  if (/^\s*(?:CREATE\s+(?!TEMPORARY\s+TABLE)TABLE|ALTER\s+TABLE|DROP\s+TABLE)\b/im.test(seedSql))
    throw new Error('Schema DDL must stay in the migration');
  for (const line of `${migrationSql}\n${seedSql}`.split('\n')) {
    const statement = line.trim();
    // V07 (24/10): REPLACE INTO deletes and re-inserts rows (new ids, cascades).
    if (/^REPLACE\s+INTO\b/i.test(statement)) throw new Error(`REPLACE INTO is not allowed: ${statement.slice(0, 120)}`);
    if (/^DELETE\b/i.test(statement) && !ALLOWED_DELETE.test(statement)) throw new Error(`Destructive DELETE outside the allowlist: ${statement.slice(0, 120)}`);
    if (/^DROP\b/i.test(statement) && !ALLOWED_DROP.test(statement)) throw new Error(`Destructive DROP outside the allowlist: ${statement.slice(0, 120)}`);
  }
  // V07 (24/10): an UPDATE statement (it may span lines) without WHERE before its terminating semicolon touches every row.
  for (const match of `${migrationSql}\n${seedSql}`.matchAll(/^\s*UPDATE\b[^;]*;/gim)) {
    if (!/\bWHERE\b/i.test(match[0])) throw new Error(`UPDATE without WHERE: ${match[0].trim().slice(0, 120)}`);
  }
  for (const part of ['-- PART A.', '-- PART B.', '-- PART C.'])
    if (!migration.includes(part)) throw new Error(`Learning schema lacks ${part}`);
  if ((seed.match(/^-- BEGIN GENERATED jpath\r?$/gm) || []).length !== DATA_LEVELS.length)
    throw new Error('Every level data file needs its GENERATED jpath region');
  if ((seed.match(/-- BEGIN /g) || []).length !== 44)
    throw new Error('Unexpected consolidated SQL section count');
  // Embedded outputs and the level parts of split seed blocks carry the SHA-256 of their own body.
  const embeddedOutputs = [...seed.matchAll(/^-- BEGIN ((?:outputs\/[^\r\n #]+\.sql|sql\/seeds\/[^\r\n #]+\.sql#(?:N[1-5]|COMMON))(?:#N[1-5])?) SHA256 ([0-9a-f]{64})\r?\n/gm)];
  if (embeddedOutputs.length !== 22) throw new Error('Unexpected embedded Learning output count');
  for (const match of embeddedOutputs) {
    const end = seed.indexOf(`\n-- END ${match[1]}`, match.index + match[0].length);
    if (end < 0) throw new Error(`Missing embedded Learning output end: ${match[1]}`);
    const body = seed.slice(match.index + match[0].length, end).trim().replace(/\r\n/g, '\n');
    if (createHash('sha256').update(body).digest('hex') !== match[2])
      throw new Error(`Embedded Learning output checksum mismatch: ${match[1]}`);
  }
  return createHash('sha256').update(migration).update('\0').update(seed).digest('hex');
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const content = JSON.parse(fs.readFileSync(path.join(root, 'data/learning-owned/learning-content.json'), 'utf8'));
  verifyDataFiles();
  console.log(`Verified consolidated Learning SQL SHA256 ${verify(fs.readFileSync(migrationFile, 'utf8'), readLearningSeed(), content)}`);
}
