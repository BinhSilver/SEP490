// [JLAS-W0108] JLPT path seed generator, part 1: pure SQL helpers and the base seed (levels, kana rows, kana).
// Owner 2026-11-01: the base seed is a generated block of sql/learning/learning_data_common.sql (it belongs to no level).
// Run: node tools/learning-path-source/seed/seed-sql.mjs [--check]
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LEVELS, ROOT, sha256 } from '../compile/util.mjs';
import { learningDataFile, replaceDataBlock } from '../../learning-data-extract/learning_data_files.mjs';

/** One SQL literal. Strings use MySQL backslash escapes; objects and arrays must be serialised by the caller. */
export function sqlString(v) {
  if (v === null || v === undefined) return 'NULL';
  if (typeof v === 'boolean') return v ? '1' : '0';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error(`Not a finite number: ${v}`);
    return String(v);
  }
  if (typeof v === 'bigint') return String(v);
  if (typeof v === 'string') {
    return `'${v.replace(/[\\'\0\n\r\x1A]/g, (c) => ({
      '\\': '\\\\', "'": "\\'", '\0': '\\0', '\n': '\\n', '\r': '\\r', '\x1A': '\\Z',
    })[c])}'`;
  }
  throw new Error(`Cannot write ${Array.isArray(v) ? 'an array' : typeof v} as an SQL literal`);
}

/** Length as MySQL VARCHAR counts it (code points, so a supplementary kanji counts once). */
export const codePointLength = (s) => [...s].length;

const ident = (name) => `\`${name}\``;

/**
 * Upsert statements, `chunk` rows each: `INSERT INTO t (...) VALUES (...),... AS n ON DUPLICATE KEY UPDATE c = n.c, ...;`.
 * `report_hidden` belongs to the moderators and is never overwritten by a seed run.
 */
export function rowsToUpsert(table, cols, rows, updateCols, { chunk = 500 } = {}) {
  if (updateCols.includes('report_hidden')) throw new Error(`${table}: a seed never updates report_hidden`);
  if (!updateCols.length) throw new Error(`${table}: no column to update`);
  for (const col of updateCols) if (!cols.includes(col)) throw new Error(`${table}: update column ${col} is not inserted`);
  if (!rows.length) return '';
  const values = rows.map((row) => `(${cols.map((col) => {
    if (!(col in row)) throw new Error(`${table}: row has no ${col}`);
    return sqlString(row[col]);
  }).join(', ')})`);
  const head = `INSERT INTO ${ident(table)} (${cols.map(ident).join(', ')}) VALUES`;
  const tail = `AS n ON DUPLICATE KEY UPDATE ${updateCols.map((c) => `${ident(c)} = n.${ident(c)}`).join(', ')};`;
  const out = [];
  for (let i = 0; i < values.length; i += chunk) out.push(`${head}\n${values.slice(i, i + chunk).join(',\n')}\n${tail}\n`);
  return out.join('');
}

// Kana rows in learning order; `chars` are Hiragana, the Katakana rows shift them by 0x60.
export const KANA_ROWS = [
  ['a', 'BASIC', 'あいうえお'], ['ka', 'BASIC', 'かきくけこ'], ['sa', 'BASIC', 'さしすせそ'], ['ta', 'BASIC', 'たちつてと'],
  ['na', 'BASIC', 'なにぬねの'], ['ha', 'BASIC', 'はひふへほ'], ['ma', 'BASIC', 'まみむめも'], ['ya', 'BASIC', 'やゆよ'],
  ['ra', 'BASIC', 'らりるれろ'], ['wa', 'BASIC', 'わをん'],
  ['ga', 'DAKUTEN', 'がぎぐげご'], ['za', 'DAKUTEN', 'ざじずぜぞ'], ['da', 'DAKUTEN', 'だぢづでど'], ['ba', 'DAKUTEN', 'ばびぶべぼ'],
  ['pa', 'HANDAKUTEN', 'ぱぴぷぺぽ'],
  ['yoon1', 'YOON', ['きゃ', 'きゅ', 'きょ', 'しゃ', 'しゅ', 'しょ', 'ちゃ', 'ちゅ', 'ちょ', 'にゃ', 'にゅ', 'にょ']],
  ['yoon2', 'YOON', ['ひゃ', 'ひゅ', 'ひょ', 'みゃ', 'みゅ', 'みょ', 'りゃ', 'りゅ', 'りょ']],
  ['yoon3', 'YOON', ['ぎゃ', 'ぎゅ', 'ぎょ', 'じゃ', 'じゅ', 'じょ', 'びゃ', 'びゅ', 'びょ', 'ぴゃ', 'ぴゅ', 'ぴょ']],
].map(([key, kind, chars]) => ({ key, kind, chars: [...chars] }));

const toKatakana = (s) => s.replace(/[ぁ-ゖ]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 0x60));
const SCRIPTS = [
  { name: 'hiragana', script: 'HIRAGANA', row: 'HR', kana: 'H', offset: 0, convert: (s) => s },
  { name: 'katakana', script: 'KATAKANA', row: 'KR', kana: 'K', offset: KANA_ROWS.length, convert: toKatakana },
];
// ぢ/づ share their romaji with じ/ず; their codes use the spelling romaji instead.
const CODE_ROMAJI = { ぢ: 'di', づ: 'du', ヂ: 'di', ヅ: 'du' };

function rowTitle(key, chars) {
  // A yoon row is three-sound groups; its title names the first sound of the first and the last group.
  if (key.startsWith('yoon')) return `Âm ghép ${chars[0]}–${chars[chars.length - 3]}`;
  return `Hàng ${chars[0]}`;
}

/** jpath_kana_row and jpath_kana rows from data/seed_jlas/kana_data.json (104 characters per script). */
export function buildKana(kanaData) {
  const rows = [];
  const kana = [];
  for (const s of SCRIPTS) {
    const chars = (kanaData?.[s.name] ?? []).flatMap((group) => group.chars);
    if (chars.length !== 104) throw new Error(`${s.name} has ${chars.length} characters, expected 104`);
    const rowOf = new Map();
    KANA_ROWS.forEach((r, i) => {
      const own = r.chars.map(s.convert);
      const code = `${s.row}-${r.key}`;
      rows.push({ code, script: s.script, sort_order: s.offset + i + 1, title_vi: rowTitle(r.key, own), row_kind: r.kind });
      for (const c of own) rowOf.set(c, code);
    });
    chars.forEach(({ char, romaji }, i) => {
      const rowCode = rowOf.get(char);
      if (!rowCode) throw new Error(`${s.name}: ${char} belongs to no kana row`);
      rowOf.delete(char);
      kana.push({ code: `${s.kana}-${CODE_ROMAJI[char] ?? romaji}`, row_code: rowCode, script: s.script, sort_order: i + 1, kana: char, romaji });
    });
    if (rowOf.size) throw new Error(`${s.name}: ${[...rowOf.keys()].join(' ')} missing from the kana file`);
  }
  if (new Set(kana.map((k) => k.code)).size !== kana.length) throw new Error('Duplicate kana code');
  return { rows, kana };
}

/** Planned [vocab, kanji, grammar] of a level whose compiled source does not exist yet. */
export const PLANNED_FALLBACK = { N4: [1500, 250, 130], N3: [2000, 370, 150], N2: [1546, 367, 150], N1: [1852, 1000, 99] };

/** Non-HOLD [vocab, kanji, grammar] counts of every data/learning-path/compiled/<L>.json that exists. */
export function readCompiledCounts(root = ROOT) {
  const out = {};
  for (const level of LEVELS) {
    const file = path.join(root, 'data/learning-path/compiled', `${level}.json`);
    if (!existsSync(file)) continue;
    const compiled = JSON.parse(readFileSync(file, 'utf8'));
    out[level] = ['vocabulary', 'kanji', 'grammar'].map((k) => (compiled[k] ?? []).filter((item) => item.status !== 'HOLD').length);
  }
  return out;
}

/** The base seed: 5 jpath_level rows (never `released`), 36 kana rows, 208 kana. Deterministic, no run time. */
export function buildBaseSql({ kanaData, compiledCounts }) {
  const levels = LEVELS.map((level, i) => {
    const planned = compiledCounts?.[level] ?? PLANNED_FALLBACK[level];
    if (!planned) throw new Error(`No compiled source and no planned fallback for ${level}`);
    const [vocab, kanji, grammar] = planned;
    return { level, sort_order: i + 1, planned_vocab: vocab, planned_kanji: kanji, planned_grammar: grammar };
  });
  const { rows, kana } = buildKana(kanaData);
  const levelCols = ['level', 'sort_order', 'planned_vocab', 'planned_kanji', 'planned_grammar'];
  const rowCols = ['code', 'script', 'sort_order', 'title_vi', 'row_kind'];
  const kanaCols = ['code', 'row_code', 'script', 'sort_order', 'kana', 'romaji'];
  return [
    '-- SINH TỰ ĐỘNG, KHÔNG SỬA TAY.',
    '-- JLPT path base data: levels (planned counts only; release is set by the admin), kana rows and kana.',
    'SET NAMES utf8mb4;',
    'START TRANSACTION;',
    rowsToUpsert('jpath_level', levelCols, levels, levelCols.slice(1)).trimEnd(),
    rowsToUpsert('jpath_kana_row', rowCols, rows, rowCols.slice(1)).trimEnd(),
    rowsToUpsert('jpath_kana', kanaCols, kana, kanaCols.slice(1)).trimEnd(),
    'COMMIT;',
    '',
  ].join('\n');
}

export const BASE_MARKER = 'tools/learning-path-source/seed/seed-sql.mjs#COMMON';

/** The base seed as a data block: `-- BEGIN <marker> SHA256 <hash of body>` … `-- END <marker>`. */
export function renderBaseBlock(root = ROOT) {
  const kanaData = JSON.parse(readFileSync(path.join(root, 'data/seed_jlas/kana_data.json'), 'utf8'));
  const body = buildBaseSql({ kanaData, compiledCounts: readCompiledCounts(root) }).trim();
  return `-- BEGIN ${BASE_MARKER} SHA256 ${sha256(body)}\n${body}\n-- END ${BASE_MARKER}`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const file = learningDataFile(ROOT);
  const current = readFileSync(file, 'utf8');
  const next = replaceDataBlock(current, BASE_MARKER, `${renderBaseBlock(ROOT)}\n`);
  if (process.argv.includes('--check')) {
    if (next !== current) { console.error(`${path.relative(ROOT, file)}: jpath base block is stale`); process.exit(1); }
    console.log('jpath base block is current');
  } else {
    if (next !== current) writeFileSync(file, next);
    console.log(`${next === current ? 'Unchanged' : 'Wrote'} jpath base block in ${path.relative(ROOT, file)}`);
  }
}
