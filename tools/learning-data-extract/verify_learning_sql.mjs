import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const migrationFile = path.join(root, 'sql/migrations/022_learning_owned_consolidated.sql');
const seedFile = path.join(root, 'sql/seeds/learning_owned.sql');
export function verify(migration, seed, content = null) {
  if (!migration.startsWith('-- [JLAS-W0108] Consolidated task-owned Learning migration.'))
    throw new Error('Unexpected Learning migration header');
  if (!seed?.startsWith('-- [JLAS-W0108] Consolidated task-owned Learning seed.'))
    throw new Error('Unexpected Learning seed header');
  for (const marker of [
    'learning_practice_draft_question', 'learning_owned_source_draft',
    'draft_answers_json', 'jlpt_mock_attempt',
  ]) if (!migration.includes(marker)) throw new Error(`Missing ${marker}`);
  for (const marker of [
    'learning_owned_sekai_n4_core.sql', 'learning_owned_n4_kanji_smart_test.sql', 'learning_owned_vocab_link_repair.sql',
    'learning-content.json#sections.sekai_n5_comprehension',
    'learning-content.json#sections.jlpt_minna_n5_n4_listening',
    'learning-content.json#sections.jlpt_minna_n5_n4_reading',
    'learning-content.json#sections.jlpt_speed_n3_comprehension',
    'learning-content.json#sections.jlpt_speed_n5_n4_reading',
  ]) if (!seed.split('\n').some((line) => line.startsWith('-- BEGIN ') && line.includes(`/${marker} SHA256 `)))
    throw new Error(`Missing ${marker}`);
  if (!seed.includes('-- BEGIN generated communication-goal lessons (learning-content.json)'))
    throw new Error('Missing generated communication-goal lessons');
  if (content) {
    // V05/V06: every JSON-rendered block names the SHA-256 of the section(s) it came from; a stale block fails here.
    const hashes = content.section_sha256 ?? {};
    for (const match of seed.matchAll(/^-- BEGIN data\/learning-owned\/learning-content\.json#sections\.([a-z0-9_]+) SHA256 ([0-9a-f]{64})\r?$/gm)) {
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
  if (/^\s*(?:DROP\s+TABLE|TRUNCATE\b|DELETE\s+FROM|CREATE\s+DATABASE|USE\s+\S+|SOURCE\s+\S+)/im.test(`${migrationSql}\n${seedSql}`))
    throw new Error('Destructive or DB-switching SQL');
  if (/^\s*(?:CREATE\s+(?!TEMPORARY\s+TABLE)TABLE|ALTER\s+TABLE|DROP\s+TABLE)\b/im.test(seedSql))
    throw new Error('Schema DDL must stay in the migration');
  // Fail closed (2026-10-19 review): every DELETE/DROP line must be one of the known, scoped forms.
  const ALLOWED_DELETE = /^DELETE cv FROM content_vocabulary cv WHERE cv\.content_item_id=@item_[a-z0-9_]+ AND cv\.vocabulary_id<>@vocab_[a-z0-9_]+;$/;
  const ALLOWED_DROP = /^DROP (?:PROCEDURE (?:IF EXISTS )?jlas_[a-z0-9_]+(?:;|\/\/|\$\$)|TEMPORARY TABLE IF EXISTS jlas_[a-z0-9_]+;)$/;
  for (const line of `${migrationSql}\n${seedSql}`.split('\n')) {
    const statement = line.trim();
    if (/^DELETE\b/i.test(statement) && !ALLOWED_DELETE.test(statement)) throw new Error(`Destructive DELETE outside the allowlist: ${statement.slice(0, 120)}`);
    if (/^DROP\b/i.test(statement) && !ALLOWED_DROP.test(statement)) throw new Error(`Destructive DROP outside the allowlist: ${statement.slice(0, 120)}`);
  }
  if ((migration.match(/-- BEGIN /g) || []).length !== 4 || (seed.match(/-- BEGIN /g) || []).length !== 18)
    throw new Error('Unexpected consolidated SQL section count');
  const embeddedOutputs = [...seed.matchAll(/^-- BEGIN (outputs\/[^\r\n]+\.sql) SHA256 ([0-9a-f]{64})\r?\n/gm)];
  if (embeddedOutputs.length !== 6) throw new Error('Unexpected embedded Learning output count');
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
  console.log(`Verified consolidated Learning SQL SHA256 ${verify(fs.readFileSync(migrationFile, 'utf8'), fs.readFileSync(seedFile, 'utf8'), content)}`);
}
