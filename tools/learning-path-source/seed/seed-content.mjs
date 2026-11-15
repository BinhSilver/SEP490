// [JLAS-W0108] JLPT path seed generator, part 2: compiled level source → table rows, edits written back by the admin,
// column-length check, release verdict and the level content SQL. Pure functions; the CLI that writes the level data
// file lives with the caller. Rows hold SQL-ready values: JSON columns are already serialised strings (or null).
// Compiled grammar has no `priority` yet: it falls back to the study order.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { LEVELS, LEVEL_RANK, ROOT } from '../compile/util.mjs';
import { codePointLength, rowsToUpsert, sqlString } from './seed-sql.mjs';
import { LIMITS } from './limits.mjs';

/** Table key → SQL table, primary key and the columns a seed run writes (report_hidden and updated_at never). */
export const TABLES = {
  topic: { sql: 'jpath_vocab_topic', pk: ['level', 'topic_key'], cols: ['level', 'topic_key', 'title_vi', 'sort_order'] },
  grammar_unit: { sql: 'jpath_grammar_unit', pk: ['level', 'unit_no'], cols: ['level', 'unit_no', 'title_vi'] },
  vocabulary: {
    sql: 'jpath_vocabulary', pk: ['code'], cc: true,
    cols: ['code', 'level', 'topic_key', 'sort_order', 'priority', 'tier', 'expression', 'reading', 'normalized_expression',
      'normalized_reading', 'romaji', 'kana_only', 'variants_json', 'meaning_vi', 'word_type_label', 'mnemonic_vi',
      'dictionary_entry_id', 'audio_path', 'audio_sha256', 'status', 'authored', 'seed_version'],
  },
  kanji: {
    sql: 'jpath_kanji', pk: ['code'], cc: true,
    cols: ['code', 'level', 'character', 'sort_order', 'han_viet', 'han_viet_alt_json', 'meanings_vi', 'readings_on_json',
      'readings_kun_json', 'level_readings_json', 'level_reading_source', 'stroke_count', 'radical_number', 'radical_name_vi', 'frequency',
      'mnemonic_vi', 'dictionary_kanji_id', 'status', 'authored', 'seed_version'],
  },
  kanji_compound: { sql: 'jpath_kanji_compound', pk: ['kanji_code', 'vocab_code'], cols: ['kanji_code', 'vocab_code', 'sort_order'] },
  grammar: {
    sql: 'jpath_grammar', pk: ['code'], cc: true,
    cols: ['code', 'level', 'unit_no', 'sort_order', 'priority', 'pattern', 'pattern_variants_json', 'family_key', 'meaning_vi',
      'formation', 'usage_vi', 'nuance_vi', 'common_mistakes_vi', 'related_codes_json', 'parent_code', 'status', 'authored',
      'seed_version'],
  },
  example: {
    sql: 'jpath_example', pk: ['code'], cc: true,
    cols: ['code', 'owner_type', 'owner_code', 'level', 'sort_order', 'role', 'ja', 'ja_furigana', 'vi', 'audio_path',
      'audio_sha256', 'status', 'authored', 'seed_version'],
  },
  grammar_question: {
    sql: 'jpath_grammar_question', pk: ['code'], cc: true,
    cols: ['code', 'grammar_code', 'level', 'bank', 'question_type', 'sort_order', 'prompt_ja', 'prompt_furigana',
      'options_json', 'answer_index', 'pieces_json', 'answer_order_json', 'answer_text', 'accept_json', 'explanation_vi',
      'status', 'authored', 'seed_version'],
  },
  confusable_pair: { sql: 'jpath_confusable_pair', pk: ['item_type', 'code_a', 'code_b'],
    cols: ['item_type', 'code_a', 'code_b', 'level', 'diff_vi', 'examples_json', 'question_json'] },
};

/** Report/edit target type → table key (KANA rows belong to the base seed and are never edited here). */
const EDIT_TARGETS = { VOCAB: 'vocabulary', KANJI: 'kanji', GRAMMAR: 'grammar', EXAMPLE: 'example', QUESTION: 'grammar_question' };

const CJK = /[々㐀-䶿一-鿿豈-﫿\u{20000}-\u{2FFFF}]/u;
const AUDIO_PREFIX = 'data/content-audio/';
const VISIBLE = new Set(['REVIEWED', 'AI_CHECKED']);
const AI_SOURCES = new Set(['AI', 'CLAUDE']); // 'CLAUDE' / claude_fields: names used by compiled files before the rename

const jsonOrNull = (v) => (v === null || v === undefined ? null : JSON.stringify(v));
const blank = (v) => v === null || v === undefined || String(v).trim() === '';

// ---------------------------------------------------------------- audio
/**
 * Audio manifest → Map keyed by file name without `.mp3` (`<code>` for a word, `<code>-ex<n>` for the n-th compiled example).
 * `ok` only when the file exists under `audioRoot` with the manifest's byte count and SHA-256.
 */
export function loadAudio(manifest, audioRoot = 'data/content-audio') {
  const root = path.resolve(ROOT, audioRoot);
  const out = new Map();
  for (const item of manifest?.items ?? []) {
    const file = String(item.file);
    const key = path.posix.basename(file).replace(/\.mp3$/i, '');
    const audioPath = file.startsWith(AUDIO_PREFIX) ? file.slice(AUDIO_PREFIX.length) : file;
    const local = path.join(root, audioPath);
    let ok = false;
    if (existsSync(local) && statSync(local).size === item.bytes) {
      ok = createHash('sha256').update(readFileSync(local)).digest('hex') === item.sha256;
    }
    out.set(key, { audio_path: audioPath, audio_sha256: item.sha256, ok });
  }
  return out;
}

// ---------------------------------------------------------------- compiled → rows
const aiFields = (prov) => [...(prov?.ai_fields ?? []), ...(prov?.claude_fields ?? [])];
/** An item is authored when the AI wrote any field of it, or any of its `*_source` says AI. */
function itemAuthored(prov) {
  if (aiFields(prov).length > 0) return 1;
  return Object.entries(prov ?? {}).some(([k, v]) => k.endsWith('_source') && AI_SOURCES.has(v)) ? 1 : 0;
}
const exampleAuthored = (example, prov) => (AI_SOURCES.has(example.source) || aiFields(prov).includes('examples') ? 1 : 0);

function compareBy(pk) {
  return (a, b) => {
    for (const col of pk) {
      if (a[col] < b[col]) return -1;
      if (a[col] > b[col]) return 1;
    }
    return 0;
  };
}

/** Rank (1-based) of each key by the smallest `order` of its items. */
function rankByFirstOrder(items, keyOf) {
  const first = new Map();
  for (const item of items) {
    const key = keyOf(item);
    const seen = first.get(key);
    if (!seen || item.order < seen.order) first.set(key, item);
  }
  return [...first.entries()].sort((a, b) => a[1].order - b[1].order).map(([key, item], i) => ({ key, item, rank: i + 1 }));
}

function audioOf(audio, key) {
  const a = audio?.get(key);
  return a?.ok ? { audio_path: a.audio_path, audio_sha256: a.audio_sha256 } : { audio_path: null, audio_sha256: null };
}

function exampleRows(ownerType, item, audio, seedVersion) {
  // MAIN first, otherwise compiled order; the audio file is numbered by the compiled position (tts.mjs).
  const indexed = (item.examples ?? []).map((e, at) => ({ e, at }));
  const sorted = [...indexed.filter((x) => x.e.role === 'MAIN'), ...indexed.filter((x) => x.e.role !== 'MAIN')];
  return sorted.map(({ e, at }, i) => ({
    code: `${item.id}-E${i + 1}`, owner_type: ownerType, owner_code: item.id, level: item.level, sort_order: i + 1,
    role: e.role === 'MAIN' ? 'MAIN' : 'EXTRA', ja: e.ja, ja_furigana: e.ja_furigana ?? null, vi: e.vi ?? null,
    ...audioOf(audio, `${item.id}-ex${at + 1}`), status: item.status, authored: exampleAuthored(e, item.provenance),
    seed_version: seedVersion,
  }));
}

const two = (n) => String(n).padStart(2, '0');
function questionRows(g, seedVersion) {
  const base = { grammar_code: g.id, level: g.level, prompt_furigana: null, options_json: null, answer_index: null,
    pieces_json: null, answer_order_json: null, answer_text: null, accept_json: null, explanation_vi: null,
    status: g.status, authored: 1, seed_version: seedVersion };
  const choice = (bank, letter) => (q, i) => ({ ...base, code: `${g.id}-${letter}${two(i + 1)}`, bank, question_type: q.type,
    sort_order: i + 1, prompt_ja: q.prompt_ja, options_json: jsonOrNull(q.options), answer_index: q.answer_index ?? null,
    explanation_vi: q.explanation_vi ?? null });
  const order = (q, i) => ({ ...base, code: `${g.id}-O${two(i + 1)}`, bank: 'ORDER', question_type: 'ORDER', sort_order: i + 1,
    prompt_ja: q.pieces.join(' / '), pieces_json: jsonOrNull(q.pieces), answer_order_json: jsonOrNull(q.answer_order),
    explanation_vi: q.translation_vi ?? null });
  const fill = (q, i) => {
    const accept = [...new Set((q.accept ?? []).map((a) => String(a).trim()).filter((a) => a && a !== q.answer))];
    return { ...base, code: `${g.id}-F${two(i + 1)}`, bank: 'FILL', question_type: 'FILL_TEXT', sort_order: i + 1,
      prompt_ja: q.sentence_ja, answer_text: q.answer, accept_json: accept.length ? JSON.stringify(accept) : null,
      explanation_vi: q.translation_vi ?? null };
  };
  return [
    ...(g.check_bank ?? []).map(choice('CHECK', 'C')),
    ...(g.order_items ?? []).map(order),
    ...(g.fill_items ?? []).map(fill),
    ...(g.extra_practice ?? []).map(choice('EXTRA', 'X')),
    // owner 05/11: JLPT-style situation questions (data/learning-path/situation/<L>.json); the context rides in
    // the prompt as "tình huống ‖ câu" because the table has no context column
    ...(g.situation_bank ?? []).map((q, i) => ({ ...base, code: `${g.id}-S${two(i + 1)}`, bank: 'SITU', question_type: q.type,
      sort_order: i + 1, prompt_ja: q.context_vi ? `${q.context_vi} ‖ ${q.prompt_ja}` : q.prompt_ja,
      prompt_furigana: q.prompt_furigana ? (q.context_vi ? `${q.context_vi} ‖ ${q.prompt_furigana}` : q.prompt_furigana) : null,
      options_json: jsonOrNull(q.options), answer_index: q.answer_index ?? null, explanation_vi: q.explanation_vi ?? null })),
  ];
}

/**
 * Pairs of this level's items, plus pairs that lower-level items point at this level's items: a pair is stored at the
 * higher level, so this level's run (which deletes its pairs first) must write it back even when only the lower item lists it.
 */
/**
 * [JLAS-W0108] Owner 07/11: authored confusable pairs (vocabulary, kanji, grammar) and grammar "related" links of one
 * level, from data/learning-path/confusables/<LEVEL>.json when it exists. A pair is kept only when both codes are known.
 */
export function readConfusables(level, file = path.join(ROOT, 'data/learning-path/confusables', `${level}.json`)) {
  if (!existsSync(file)) return { pairs: [], related: [] };
  const data = JSON.parse(readFileSync(file, 'utf8'));
  return { pairs: Array.isArray(data.pairs) ? data.pairs : [], related: Array.isArray(data.related) ? data.related : [] };
}

function authoredPairRows(pairs, levelOf, level) {
  const out = [];
  for (const p of pairs) {
    const type = String(p.type || '').toUpperCase();
    if (!['VOCAB', 'KANJI', 'GRAMMAR'].includes(type) || !p.a || !p.b || p.a === p.b) continue;
    if (!levelOf(p.a) || !levelOf(p.b)) continue;
    const [a, b] = p.a < p.b ? [p.a, p.b] : [p.b, p.a];
    const hi = [levelOf(a), levelOf(b), level].reduce((x, y) => (LEVEL_RANK[y] > LEVEL_RANK[x] ? y : x));
    out.push({ item_type: type, code_a: a, code_b: b, level: hi, diff_vi: p.diffVi ?? null,
      examples_json: jsonOrNull(Array.isArray(p.examples) && p.examples.length ? p.examples : null),
      question_json: jsonOrNull(p.question ?? null) });
  }
  return out;
}

function confusableRows(itemType, items, lowerItems, levelOf) {
  const pairs = new Map();
  const own = new Set(items.map((item) => item.id));
  const sources = [...items.map((item) => [item, null]), ...lowerItems.map((item) => [item, own])];
  for (const [item, onlyTo] of sources) {
    for (const other of item.confusable_ids ?? []) {
      if (other === item.id || (onlyTo && !onlyTo.has(other))) continue;
      const otherLevel = levelOf(other);
      if (!otherLevel) continue;
      const [a, b] = item.id < other ? [item.id, other] : [other, item.id];
      const level = LEVEL_RANK[otherLevel] > LEVEL_RANK[item.level] ? otherLevel : item.level;
      pairs.set(`${a}|${b}`, { item_type: itemType, code_a: a, code_b: b, level, diff_vi: null, examples_json: null,
        question_json: null });
    }
  }
  return [...pairs.values()];
}

/**
 * Compiled level JSON → the 9 content tables, each sorted by primary key. `audio` comes from loadAudio; `knownCodes`
 * (Map code → level, or a Set of codes of this level) limits confusable pairs to items that exist. `lowerLevels` = the
 * compiled sources of lower levels: their confusable links to this level's items are written by this level's run too.
 */
export function buildLevelRows(compiled, { seedVersion, audio = new Map(), knownCodes = new Map(), lowerLevels = [],
  confusables = readConfusables(compiled.level) } = {}) {
  const level = compiled.level;
  const levelOf = (code) => (knownCodes instanceof Map ? knownCodes.get(code) : knownCodes.has(code) ? level : undefined);
  const vocab = compiled.vocabulary ?? [];
  const kanji = compiled.kanji ?? [];
  const grammar = compiled.grammar ?? [];

  const topic = rankByFirstOrder(vocab, (v) => v.topic_key)
    .map(({ key, item, rank }) => ({ level, topic_key: key, title_vi: item.topic_vi, sort_order: rank }));
  const grammar_unit = rankByFirstOrder(grammar, (g) => g.unit_no)
    .map(({ key, item }) => ({ level, unit_no: key, title_vi: item.unit_title_vi ?? null }));

  const vocabulary = vocab.map((v) => ({
    code: v.id, level: v.level, topic_key: v.topic_key, sort_order: v.order, priority: v.priority ?? v.order, tier: v.tier ?? 'CORE',
    expression: v.expression, reading: v.reading, normalized_expression: v.normalized_expression,
    normalized_reading: v.normalized_reading, romaji: v.romaji ?? null, kana_only: CJK.test(v.expression) ? 0 : 1,
    variants_json: jsonOrNull(v.variants), meaning_vi: v.meaning_vi ?? null, word_type_label: v.word_type_label ?? null,
    mnemonic_vi: v.mnemonic_vi ?? null, dictionary_entry_id: v.dictionary_entry_id ?? null, ...audioOf(audio, v.id),
    status: v.status, authored: itemAuthored(v.provenance), seed_version: seedVersion,
  }));

  const kanjiRows = kanji.map((k) => ({
    code: k.id, level: k.level, character: k.character, sort_order: k.order, han_viet: k.han_viet ?? null,
    han_viet_alt_json: jsonOrNull(k.han_viet_alt), meanings_vi: k.meanings_vi ?? null,
    readings_on_json: jsonOrNull(k.readings_on), readings_kun_json: jsonOrNull(k.readings_kun),
    level_readings_json: jsonOrNull(k.level_readings), level_reading_source: k.provenance?.level_readings_source ?? null,
    stroke_count: k.stroke_count ?? null,
    radical_number: k.radical?.number ?? null, radical_name_vi: k.radical?.name_vi ?? null, frequency: k.frequency ?? null,
    mnemonic_vi: k.mnemonic_vi ?? null, dictionary_kanji_id: null, status: k.status, authored: itemAuthored(k.provenance),
    seed_version: seedVersion,
  }));
  // A compound links a word that exists at this or a lower level and is not HOLD; the first 5 such words are kept.
  const lowerOf = (key) => lowerLevels.filter((c) => LEVEL_RANK[c.level] < LEVEL_RANK[level]).flatMap((c) => c[key] ?? []);
  const linkable = new Set([...vocab, ...lowerOf('vocabulary')].filter((v) => v.status !== 'HOLD').map((v) => v.id));
  const kanji_compound = kanji.flatMap((k) => (k.compound_ids ?? []).filter((code) => linkable.has(code)).slice(0, 5)
    .map((code, i) => ({ kanji_code: k.id, vocab_code: code, sort_order: i + 1 })));

  // authored "related" links fill grammar points that have none (codes must exist)
  const relatedOf = new Map();
  for (const r of confusables.related ?? []) {
    const own = grammar.find((g) => g.id === r.grammar);
    const codes = (r.relatedCodes ?? []).filter((c) => c !== r.grammar && levelOf(c));
    if (own && !(own.related_ids ?? []).length && codes.length) relatedOf.set(r.grammar, codes);
  }
  const grammarRows = grammar.map((g) => ({
    code: g.id, level: g.level, unit_no: g.unit_no, sort_order: g.order, priority: g.priority ?? g.order, pattern: g.pattern,
    pattern_variants_json: jsonOrNull(g.pattern_variants), family_key: g.family_key, meaning_vi: g.meaning_vi ?? null,
    formation: g.formation ?? null, usage_vi: g.usage_vi ?? null, nuance_vi: g.nuance_vi ?? null,
    common_mistakes_vi: g.common_mistakes_vi ?? null,
    related_codes_json: jsonOrNull(relatedOf.get(g.id) ?? g.related_ids),
    // a secondary pattern is attached to its main one (shown in the main lesson, never learned on its own)
    parent_code: g.grammar_role === 'SECONDARY' && g.parent_id ? g.parent_id : null, status: g.status,
    authored: itemAuthored(g.provenance), seed_version: seedVersion,
  }));

  const example = [
    ...vocab.flatMap((v) => exampleRows('VOCAB', v, audio, seedVersion)),
    ...kanji.flatMap((k) => exampleRows('KANJI', k, audio, seedVersion)),
    ...grammar.flatMap((g) => exampleRows('GRAMMAR', g, audio, seedVersion)),
  ];
  const grammar_question = grammar.flatMap((g) => questionRows(g, seedVersion));
  const byKey = new Map();
  for (const row of [...confusableRows('VOCAB', vocab, lowerOf('vocabulary'), levelOf),
    ...confusableRows('KANJI', kanji, lowerOf('kanji'), levelOf),
    ...authoredPairRows(confusables.pairs ?? [], levelOf, level)]) {
    const key = `${row.item_type}|${row.code_a}|${row.code_b}`;
    const old = byKey.get(key);
    // an authored pair adds its explanation to a pair the compiled source already lists
    byKey.set(key, old ? { ...old, ...Object.fromEntries(Object.entries(row).filter(([, v]) => v !== null)) } : row);
  }
  const confusable_pair = [...byKey.values()];

  const tables = { topic, grammar_unit, vocabulary, kanji: kanjiRows, kanji_compound, grammar: grammarRows, example,
    grammar_question, confusable_pair };
  for (const [key, def] of Object.entries(TABLES)) tables[key].sort(compareBy(def.pk));
  return tables;
}

// ---------------------------------------------------------------- edits
/**
 * Admin edits (`data/learning-path/edits/<L>.json` `edits[]`, last value per field) applied to row columns in file order.
 * Returns new tables; the input is not changed. A column the table does not have is a hard error.
 */
export function applyEdits(tables, edits = []) {
  const out = structuredClone(tables);
  const index = new Map();
  for (const key of Object.values(EDIT_TARGETS)) index.set(key, new Map((out[key] ?? []).map((row) => [row.code, row])));
  let applied = 0;
  const skipped = [];
  for (const edit of edits) {
    if (edit.targetType === 'KANA') { skipped.push({ ...edit, reason: 'KANA' }); continue; }
    const key = EDIT_TARGETS[edit.targetType];
    if (!key) throw new Error(`Unknown edit target type ${edit.targetType}`);
    if (edit.field === 'report_hidden') { skipped.push({ ...edit, reason: 'REPORT_HIDDEN' }); continue; }
    if (!TABLES[key].cols.includes(edit.field) || TABLES[key].pk.includes(edit.field)) {
      throw new Error(`${TABLES[key].sql} has no editable column ${edit.field}`);
    }
    const row = index.get(key).get(edit.targetCode);
    if (!row) { skipped.push({ ...edit, reason: 'UNKNOWN_CODE' }); continue; }
    const v = edit.value;
    row[edit.field] = edit.field.endsWith('_json') && v !== null && v !== undefined && typeof v !== 'string' ? JSON.stringify(v) : v ?? null;
    applied++;
  }
  return { tables: out, applied, skipped };
}

// ---------------------------------------------------------------- limits
/** Every string value longer than its DDL length (code points), as `{table, code, column, length, limit}`. */
export function checkLimits(tables) {
  const out = [];
  for (const [key, def] of Object.entries(TABLES)) {
    for (const row of tables[key] ?? []) {
      for (const [column, value] of Object.entries(row)) {
        const limit = LIMITS[`${def.sql}.${column}`];
        if (limit === undefined || typeof value !== 'string') continue;
        const length = codePointLength(value);
        if (length > limit) out.push({ table: def.sql, code: row.code ?? def.pk.map((c) => row[c]).join('|'), column, length, limit });
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------- release verdict
const NOTE_LIMIT = LIMITS['jpath_level.release_note'];

/**
 * Release check of one level after edits (spec 4.11). Only visible items (REVIEWED|AI_CHECKED) are checked; each reason
 * counts items. AUDIO_MISSING (preflight C4): a word without ok audio or without ok MAIN-example audio, a grammar pattern
 * without ok MAIN-example audio. FURIGANA_MISSING (C5) only for sentences that contain a kanji. KANJI_COMPOUND_MISSING:
 * a kanji without a compound whose word is visible; `visibleVocab` adds visible words the tables do not hold (lower levels).
 */
export function releaseVerdict(level, tables, { visibleVocab = new Set(), kanjiWithoutWords = new Set() } = {}) {
  const reasons = {};
  const add = (code, n = 1) => { if (n > 0) reasons[code] = (reasons[code] ?? 0) + n; };
  const ofLevel = (rows) => (rows ?? []).filter((r) => r.level === level);
  const vocab = ofLevel(tables.vocabulary);
  const kanji = ofLevel(tables.kanji);
  const grammar = ofLevel(tables.grammar);

  // Owner 07/11: DRAFT items (blocking errors) are left out of the path, they no longer hold the level back.
  // They stay hidden (not VISIBLE) and are not checked below.

  const examplesOf = new Map();
  for (const e of ofLevel(tables.example)) {
    if (!VISIBLE.has(e.status)) continue;
    if (!examplesOf.has(e.owner_code)) examplesOf.set(e.owner_code, []);
    examplesOf.get(e.owner_code).push(e);
  }
  const exampleChecks = (item) => {
    const exs = examplesOf.get(item.code) ?? [];
    const main = exs.find((e) => e.role === 'MAIN');
    if (blank(item.meaning_vi)) add('MEANING_MISSING');
    if (!main) add('MAIN_EXAMPLE_MISSING');
    if (exs.some((e) => blank(e.vi))) add('EXAMPLE_VI_MISSING');
    if (exs.some((e) => CJK.test(e.ja) && blank(e.ja_furigana))) add('FURIGANA_MISSING');
    return main;
  };

  const meanings = new Set();
  for (const v of vocab.filter((r) => VISIBLE.has(r.status))) {
    const main = exampleChecks(v);
    if (blank(v.audio_path) || blank(main?.audio_path)) add('AUDIO_MISSING');
    const key = `${v.normalized_expression}|${v.normalized_reading}|${v.meaning_vi ?? ''}`;
    if (meanings.has(key)) add('DUPLICATE_MEANING');
    meanings.add(key);
  }

  const visibleWords = new Set([...visibleVocab,
    ...(tables.vocabulary ?? []).filter((r) => VISIBLE.has(r.status)).map((r) => r.code)]);
  const compounds = new Set((tables.kanji_compound ?? []).filter((c) => visibleWords.has(c.vocab_code)).map((c) => c.kanji_code));
  // Owner 05/11 level caps: a kanji whose compounds all went to Mở rộng (HOLD) still has words, just not in the path
  const anyCompound = new Set((tables.kanji_compound ?? []).map((c) => c.kanji_code));
  for (const k of kanji.filter((r) => VISIBLE.has(r.status))) {
    if (blank(k.meanings_vi)) add('MEANING_MISSING');
    if (blank(k.han_viet)) add('HAN_VIET_MISSING');
    // a kanji that no word of this or a lower level uses (君 at N3) cannot have a compound
    if (!compounds.has(k.code) && !kanjiWithoutWords.has(k.code) && !anyCompound.has(k.code)) add('KANJI_COMPOUND_MISSING');
  }

  const questionsOf = new Map();
  for (const q of ofLevel(tables.grammar_question)) {
    if (!VISIBLE.has(q.status)) continue;
    if (!questionsOf.has(q.grammar_code)) questionsOf.set(q.grammar_code, []);
    questionsOf.get(q.grammar_code).push(q);
  }
  for (const g of grammar.filter((r) => VISIBLE.has(r.status))) {
    const main = exampleChecks(g);
    if (blank(main?.audio_path)) add('AUDIO_MISSING');
    // an attached (secondary) pattern has no question bank of its own: it is practised through its main pattern
    if (g.parent_code) continue;
    const qs = questionsOf.get(g.code) ?? [];
    const check = qs.filter((q) => q.bank === 'CHECK');
    const types = new Set(check.map((q) => q.question_type));
    const short = check.length < 8 || ['FILL', 'ORDER_STAR', 'MEANING', 'ERROR'].some((t) => !types.has(t))
      || qs.filter((q) => q.bank === 'ORDER').length < 2 || qs.filter((q) => q.bank === 'FILL').length < 2;
    if (short) add('GRAMMAR_BANK_SHORT');
  }

  const sorted = Object.fromEntries(Object.entries(reasons).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
  const released = Object.keys(sorted).length === 0;
  let note = null;
  if (!released) {
    note = Object.entries(sorted).map(([k, n]) => `${k}=${n}`).join('; ');
    if (codePointLength(note) > NOTE_LIMIT) note = `${[...note].slice(0, NOTE_LIMIT - 1).join('')}…`;
  }
  return { released, reasons: sorted, note };
}

// ---------------------------------------------------------------- SQL
function upsert(key, rows) {
  const def = TABLES[key];
  return rowsToUpsert(def.sql, def.cols, rows, def.cols.filter((c) => !def.pk.includes(c))).trimEnd();
}

/**
 * F35: dictionary_entry_id baked at compile time only fits the DB it was compiled against (vocabulary_entry ids
 * differ between machines), so each level's SQL re-resolves the link on the DB it is loaded into:
 * exact (normalized_expression, normalized_reading) first — unique in vocabulary_entry —, then, for dead or
 * plainly wrong links, by normalized_expression (preferring the same JLPT level, then entries the textbooks use),
 * else NULL so the FE can say the word has no dictionary entry instead of saving the wrong word.
 */
function relinkDictionarySql(L) {
  const wrong = (j) => `(cur.id IS NULL
       OR (CONVERT(cur.normalized_expression USING utf8mb4) COLLATE utf8mb4_bin
               <> CONVERT(${j}.normalized_expression USING utf8mb4) COLLATE utf8mb4_bin
           AND CONVERT(cur.normalized_reading USING utf8mb4) COLLATE utf8mb4_bin
               <> CONVERT(${j}.normalized_reading USING utf8mb4) COLLATE utf8mb4_bin))`;
  return [
    `UPDATE jpath_vocabulary j
JOIN vocabulary_entry v
  ON v.normalized_expression = CONVERT(j.normalized_expression USING utf8mb4) COLLATE utf8mb4_0900_as_cs
 AND v.normalized_reading   = CONVERT(j.normalized_reading   USING utf8mb4) COLLATE utf8mb4_0900_as_cs
SET j.dictionary_entry_id = v.id
WHERE j.level = ${L} AND (j.dictionary_entry_id IS NULL OR j.dictionary_entry_id <> v.id);`,
    `UPDATE jpath_vocabulary j
JOIN (
    SELECT code, pick FROM (
        SELECT j2.code,
               FIRST_VALUE(v.id) OVER (PARTITION BY j2.code
                   ORDER BY (v.jlpt_level = j2.level) DESC, (cv.vid IS NOT NULL) DESC, v.id) AS pick,
               ROW_NUMBER() OVER (PARTITION BY j2.code ORDER BY v.id) AS rn
        FROM jpath_vocabulary j2
        LEFT JOIN vocabulary_entry cur ON cur.id = j2.dictionary_entry_id
        JOIN vocabulary_entry v
          ON v.normalized_expression = CONVERT(j2.normalized_expression USING utf8mb4) COLLATE utf8mb4_0900_as_cs
        LEFT JOIN (SELECT DISTINCT vocabulary_id AS vid FROM content_vocabulary) cv ON cv.vid = v.id
        WHERE j2.level = ${L} AND j2.dictionary_entry_id IS NOT NULL
          AND ${wrong('j2')}
    ) t WHERE t.rn = 1
) p ON p.code = j.code
SET j.dictionary_entry_id = p.pick;`,
    `UPDATE jpath_vocabulary j
LEFT JOIN vocabulary_entry cur ON cur.id = j.dictionary_entry_id
SET j.dictionary_entry_id = NULL
WHERE j.level = ${L} AND j.dictionary_entry_id IS NOT NULL
  AND ${wrong('j')};`,
  ].join('\n');
}

/**
 * One level's content SQL in one transaction: upserts in FK order, link rows (compounds, confusable pairs) deleted and
 * re-inserted for the level, rows this seed version did not write are moved to HOLD (never deleted), then the release flag.
 */
export function levelContentSql(level, tables, { seedVersion, verdict, header } = {}) {
  if (!LEVELS.includes(level)) throw new Error(`Unknown level ${level}`);
  if (!seedVersion) throw new Error('seedVersion is required');
  if (!verdict) throw new Error('verdict is required');
  const L = sqlString(level);
  const v = sqlString(seedVersion);
  const headerLines = header === undefined || header === null ? [] : (Array.isArray(header) ? header : [header]);
  const parts = [
    ...headerLines,
    `-- release: ${level} released=${verdict.released ? 1 : 0}`,
    'SET NAMES utf8mb4;',
    'START TRANSACTION;',
    rowsToUpsert('jpath_level', ['level', 'sort_order'], [{ level, sort_order: LEVELS.indexOf(level) + 1 }], ['sort_order']).trimEnd(),
    upsert('topic', tables.topic),
    upsert('grammar_unit', tables.grammar_unit),
    upsert('vocabulary', tables.vocabulary),
    `-- F35: tra lại dictionary_entry_id trên DB đang nạp (id biên dịch sẵn chỉ đúng với DB đã dùng để build)`,
    relinkDictionarySql(L),
    upsert('kanji', tables.kanji),
    `DELETE c FROM jpath_kanji_compound c JOIN jpath_kanji k ON k.code = c.kanji_code WHERE k.level = ${L};`,
    upsert('kanji_compound', tables.kanji_compound),
    upsert('grammar', tables.grammar),
    upsert('example', tables.example),
    upsert('grammar_question', tables.grammar_question),
    `DELETE p FROM jpath_confusable_pair p WHERE p.level = ${L};`,
    upsert('confusable_pair', tables.confusable_pair),
    ...Object.values(TABLES).filter((d) => d.cc).map((d) =>
      `UPDATE ${d.sql} SET status = 'HOLD' WHERE level = ${L} AND (seed_version IS NULL OR seed_version <> ${v});`),
    verdict.released
      ? `UPDATE jpath_level SET released = 1, released_at = COALESCE(released_at, NOW(6)), seed_version = ${v}, release_note = NULL WHERE level = ${L};`
      : `UPDATE jpath_level SET seed_version = ${v}, release_note = ${sqlString(verdict.note)} WHERE level = ${L};`,
    'COMMIT;',
    '',
  ];
  return parts.filter((p) => p !== '').join('\n') + '\n';
}
