// [JLAS-W0108] Plan 3 Task 4: blanks of a listening dictation line (policy #35). A blank is a word whose reading is known
// (the set's new_words, or jpath_vocabulary of the set's level and lower levels from compiled/<L>.json) found in the
// line: longest first, no overlap, at most 2. The learner types the word in kana or kanji; JpathDictationGrader checks it.
// CLI (stats only, writes nothing): node tools/learning-path-source/skills/skill-dictation.mjs --level N5
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LEVEL_RANK, ROOT, normalizeJapanese, readJson } from '../compile/util.mjs';

export const MAX_BLANKS = 2;
const KANJI = /[㐀-鿿豈-﫿々]/u;
const KANA_ONLY = /^[ぁ-ゖァ-ヺー・]+$/u;
const HIRAGANA_ONLY = /^[ぁ-ゖー]+$/u;
// After a one-kanji word only these keep its reading (上に, 夜は); 上がる, 生まれる would read differently.
const AFTER_SINGLE_KANJI = new Set([...'はがをにでとのもへやかよねな、。！？「」　 ']);
// Short kana words (です, いる, に) sit inside other words and are no listening point.
const MIN_KANA_LENGTH = 3;

// べんきょう＜する＞ → べんきょう; entries with 〜 or brackets (〜月, （電話を）かける) are patterns, not words.
const clean = s => String(s ?? '').replace(/[＜<][^＞>]*[＞>]/g, '').trim();
const isWord = s => s.length > 0 && !/[〜～~()（）［］\[\]！!？?、。\s]/.test(s);

/** Index of level vocabulary entries: expression → {readings: Set<normalized>, accept: [...]}. */
export function vocabIndex(entries) {
  const index = new Map();
  for (const e of entries ?? []) {
    const expression = clean(e.expression);
    const reading = clean(e.reading);
    if (!isWord(expression) || !reading) continue;
    const row = index.get(expression) ?? { readings: new Set(), accept: [expression] };
    row.readings.add(normalizeJapanese(reading));
    for (const v of (e.variants ?? []).map(clean)) if (isWord(v) && !row.accept.includes(v)) row.accept.push(v);
    index.set(expression, row);
  }
  return index;
}

function candidates({ newWords, vocab }) {
  const out = new Map();
  for (const w of newWords ?? []) {
    const expression = clean(w.expression);
    const reading = clean(w.reading);
    if (!isWord(expression) || !reading) continue;
    const known = vocab?.get(expression);
    out.set(expression, { expression, readings: new Set([normalizeJapanese(reading)]),
      accept: known ? known.accept : [expression], fromSet: true });
  }
  for (const [expression, row] of vocab ?? []) if (!out.has(expression)) out.set(expression, { expression, ...row, fromSet: false });
  return [...out.values()].filter(c => c.readings.size === 1 // two readings (日 ひ/にち) and no set word: ambiguous
    && (!KANA_ONLY.test(normalizeJapanese(c.expression)) || c.expression.length >= MIN_KANA_LENGTH));
}

function fits(text, start, end, expression) {
  const before = text[start - 1] ?? '';
  const after = text[end] ?? '';
  if (KANJI.test(expression[0]) && KANJI.test(before)) return false; // part of a longer compound
  if (KANJI.test(expression[expression.length - 1]) && KANJI.test(after)) return false;
  if (expression.length === 1 && KANJI.test(expression) && after && !AFTER_SINGLE_KANJI.has(after)) return false;
  return true;
}

/** Blanks of one line, in text order: [{start, end, answer, accept}] (start/end are UTF-16 offsets into text). */
export function blanksFor(text, { newWords = [], vocab = new Map() } = {}) {
  const line = String(text ?? '');
  if (!line) return [];
  const found = [];
  for (const c of candidates({ newWords, vocab })) {
    for (let at = line.indexOf(c.expression); at >= 0; at = line.indexOf(c.expression, at + 1)) {
      if (fits(line, at, at + c.expression.length, c.expression)) found.push({ c, start: at, end: at + c.expression.length });
    }
  }
  // Words with kanji or katakana before hiragana-only ones (います, ください are no listening point while a real word is
  // there); then longest first, the set's own new words first, earliest first.
  const tier = f => (HIRAGANA_ONLY.test(f.c.expression) ? 1 : 0);
  found.sort((a, b) => tier(a) - tier(b) || (b.end - b.start) - (a.end - a.start) || Number(b.c.fromSet) - Number(a.c.fromSet)
    || a.start - b.start);
  const picked = [];
  for (const f of found) {
    if (picked.length === MAX_BLANKS) break;
    if (picked.some(p => (f.start < p.end && p.start < f.end) || p.c.expression === f.c.expression)) continue;
    picked.push(f);
  }
  return picked.sort((a, b) => a.start - b.start).map(({ c, start, end }) => {
    const kana = KANA_ONLY.test(normalizeJapanese(c.expression));
    const accept = [c.expression, ...c.accept.filter(a => a !== c.expression)];
    return { start, end, answer: kana ? c.expression : [...c.readings][0], accept };
  });
}

/** Vocabulary of the level and every lower level (compiled/<L>.json), as one index. */
export function levelVocab(level) {
  const entries = [];
  for (const [l, rank] of Object.entries(LEVEL_RANK)) {
    if (rank > LEVEL_RANK[level]) continue;
    const file = path.join(ROOT, 'data/learning-path/compiled', `${l}.json`);
    if (existsSync(file)) entries.push(...readJson(file).vocabulary);
  }
  return vocabIndex(entries);
}

/** Dictation rows of accepted listening sets (code <setId>-D<n>); lines without any blank are left out and listed. */
export function dictationBlanks(sets, vocab) {
  const rows = [];
  const empty = [];
  for (const set of sets) {
    (set.dictation ?? []).forEach((text, i) => {
      const code = `${set.id}-D${i + 1}`;
      const blanks = blanksFor(text, { newWords: set.new_words, vocab });
      if (blanks.length) rows.push({ code, set: set.id, sort_order: i + 1, text, blanks });
      else empty.push({ code, set: set.id, text });
    });
  }
  return { rows, empty };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { checkLevel, loadLevelInputs } = await import('./skill-check.mjs');
  const args = process.argv.slice(2);
  const levels = args.flatMap((a, i) => a === '--level' ? [args[i + 1]] : []);
  for (const level of levels) {
    const { accepted } = checkLevel(level, loadLevelInputs(level));
    const { rows, empty } = dictationBlanks(accepted.filter(s => Array.isArray(s.dictation)), levelVocab(level));
    const blanks = rows.reduce((n, r) => n + r.blanks.length, 0);
    console.log(`${level}: ${rows.length} dictation lines with blanks (${blanks} blanks), ${empty.length} without a blank`);
    for (const e of empty) console.log(`  no blank ${e.code} ${e.text}`);
  }
}
