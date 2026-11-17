// Read-only export of the J-LAS dictionary (another team's module) for path compilation.
// Rows are copied to target/dictionary-export/rows.json (local only); nothing is written back to the dictionary.
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { ROOT, argValue, readJson, writeOrCheck } from './util.mjs';

const MYSQL = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe';
export const EXPORT_FILE = path.join(ROOT, 'target/dictionary-export/rows.json');

const QUERIES = {
  entries: `SELECT id, expression, reading, normalized_expression, normalized_reading, meaning, word_type, jlpt_level,
    dictionary_status, usage_scope FROM vocabulary_entry ORDER BY id`,
  variants: 'SELECT vocabulary_id, variant_expression, normalized_expression FROM vocabulary_variant ORDER BY id',
  readings: 'SELECT vocabulary_id, reading, normalized_reading FROM vocabulary_reading ORDER BY id',
  glosses: `SELECT s.vocabulary_id, g.gloss_text FROM vocabulary_sense s JOIN vocabulary_gloss g ON g.sense_id = s.id
    WHERE g.language_code = 'vi' ORDER BY s.vocabulary_id, s.sense_order, g.id`,
  kanji: `SELECT id, unicode_code, CHAR(CONV(unicode_code, 16, 10) USING utf32) AS \`character\`, han_viet, meaning_vi,
    jlpt_level, stroke_count, radical_id FROM kanji ORDER BY id`,
  kanjiReadings: 'SELECT kanji_id, reading_type, reading_text, is_common FROM kanji_reading ORDER BY kanji_id, id',
  radicals: 'SELECT id, radical_number, name_vi FROM radical ORDER BY id'
};

const UNESCAPE = { t: '\t', n: '\n', r: '\r', '0': '\0', '\\': '\\' };
const unescapeField = value => value === 'NULL' ? null : value.replace(/\\(.)/g, (m, c) => UNESCAPE[c] ?? c);

export function parseMysqlTsv(text) {
  const lines = String(text).replace(/\r\n/g, '\n').split('\n').filter(line => line.length > 0);
  if (!lines.length) return [];
  const columns = lines[0].split('\t');
  return lines.slice(1).map(line => {
    const fields = line.split('\t');
    return Object.fromEntries(columns.map((c, i) => [c, unescapeField(fields[i] ?? 'NULL')]));
  });
}

const num = value => value == null ? null : Number(value);
const push = (map, key, value) => { if (!map.has(key)) map.set(key, []); map.get(key).push(value); };

export function buildDictionaryIndex(rows) {
  const entryById = new Map();
  for (const e of rows.entries) {
    entryById.set(Number(e.id), { id: Number(e.id), expression: e.expression, reading: e.reading,
      normalized_expression: e.normalized_expression, normalized_reading: e.normalized_reading, variants: [], readings: [],
      meaning_vi: e.meaning, glosses_vi: [], word_type: e.word_type, jlpt_level: e.jlpt_level,
      status: e.dictionary_status ?? null, usage_scope: e.usage_scope ?? null });
  }
  for (const v of rows.variants) entryById.get(Number(v.vocabulary_id))?.variants
    .push({ expression: v.variant_expression, normalized_expression: v.normalized_expression });
  for (const r of rows.readings) entryById.get(Number(r.vocabulary_id))?.readings.push(r.normalized_reading);
  for (const g of rows.glosses) entryById.get(Number(g.vocabulary_id))?.glosses_vi.push(g.gloss_text);

  const vocabularyByKey = new Map();
  const vocabularyByReading = new Map();
  for (const e of entryById.values()) {
    const readings = [e.normalized_reading, ...e.readings];
    const spellings = [e.normalized_expression, ...e.variants.map(v => v.normalized_expression)];
    for (const s of new Set(spellings)) for (const r of new Set(readings)) push(vocabularyByKey, `${s}|${r}`, e);
    for (const r of new Set(readings)) push(vocabularyByReading, r, e);
  }

  const radicalById = new Map(rows.radicals.map(r => [Number(r.id), { number: num(r.radical_number), name_vi: r.name_vi }]));
  const readingsByKanji = new Map();
  for (const r of rows.kanjiReadings) push(readingsByKanji, Number(r.kanji_id), r);
  const kanjiByChar = new Map();
  for (const k of rows.kanji) {
    const readings = readingsByKanji.get(Number(k.id)) ?? [];
    kanjiByChar.set(k.character, { id: Number(k.id), character: k.character, han_viet: k.han_viet, meaning_vi: k.meaning_vi,
      jlpt_level: k.jlpt_level, stroke_count: num(k.stroke_count), radical: radicalById.get(num(k.radical_id)) ?? null,
      readings_on: readings.filter(r => r.reading_type === 'ON').map(r => r.reading_text),
      readings_kun: readings.filter(r => r.reading_type === 'KUN').map(r => r.reading_text),
      common_readings: readings.filter(r => r.is_common === '1').map(r => r.reading_text) });
  }
  return { entryById, vocabularyByKey, vocabularyByReading, kanjiByChar };
}

export const loadDictionaryIndex = (file = EXPORT_FILE) => buildDictionaryIndex(readJson(file));

function query(db, sql) {
  const result = spawnSync(MYSQL, ['-uroot', '--batch', '--default-character-set=utf8mb4', db, '-e', sql],
    { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024, env: process.env });
  if (result.status !== 0) throw new Error(`mysql failed: ${result.stderr.split('\n')[0]}`);
  return parseMysqlTsv(result.stdout);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  // F35: default to the system DB; ids are re-resolved at load time anyway, but the build DB should be jlas
  const db = argValue(process.argv.slice(2), '--db', 'jlas');
  if (!process.env.MYSQL_PWD) throw new Error('MYSQL_PWD is not set');
  const rows = Object.fromEntries(Object.entries(QUERIES).map(([name, sql]) => [name, query(db, sql)]));
  writeOrCheck(EXPORT_FILE, rows, false);
  console.log(Object.entries(rows).map(([k, v]) => `${k}=${v.length}`).join(' '));
}
