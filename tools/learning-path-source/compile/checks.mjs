// Cleanliness checks for a compiled JLPT level (spec 4.11 "Bộ kiểm độ sạch tự động") and the item status rule.
import { LEVEL_RANK, normalizeJapanese } from './util.mjs';
import { formatMeaningVi } from './normalize.mjs';

export const WARNING_CODES = new Set(['FURIGANA_MISSING', 'RADICAL_NAME_MISSING', 'SAME_SPELLING', 'EXAMPLE_KANA_ABOVE_LEVEL',
  'EXAMPLE_ABOVE_LEVEL_SOURCE']);
const EXAMPLE_LENGTH = { N5: [8, 25], N4: [8, 32], N3: [8, 40], N2: [8, 48], N1: [8, 56] };
const BANK_TYPES = ['FILL', 'ORDER_STAR', 'MEANING', 'ERROR'];
const HAS_KANJI = /[一-龯々]/;
const PUNCT = /[\s.,;:!?'"“”‘’()（）「」『』、。！？・…\-–—]/g;

const plain = text => String(text ?? '').toLocaleLowerCase('vi').replace(PUNCT, '');
function bigrams(text) { const m = new Map(); for (let i = 0; i < text.length - 1; i++) { const b = text.slice(i, i + 2); m.set(b, (m.get(b) ?? 0) + 1); } return m; }

// Dice coefficient over character bigrams; strings shorter than 6 characters are never compared.
export function similarity(a, b) {
  const x = plain(a), y = plain(b);
  if (x.length < 6 || y.length < 6) return 0;
  const bx = bigrams(x), by = bigrams(y);
  let shared = 0;
  for (const [g, n] of bx) shared += Math.min(n, by.get(g) ?? 0);
  return (2 * shared) / ((x.length - 1) + (y.length - 1));
}

// Every lookup form of a dictionary-style expression: without 〜, without ＜する＞/（する） or with する joined,
// with the optional [お] kept or dropped.
export function wordForms(expression) {
  const s = String(expression ?? '').normalize('NFKC');
  const strip = t => t.replace(/^[〜~～]+|[〜~～]+$/g, '').replace(/\s+/g, '').trim();
  return [...new Set([
    strip(s.replace(/[＜<（(][^＞>）)]*[＞>）)]/g, '')), strip(s.replace(/[＜<（(]([^＞>）)]*)[＞>）)]/g, '$1')),
    strip(s.replace(/[[［]([^\]］]*)[\]］]/g, '$1')), strip(s.replace(/[[［][^\]］]*[\]］]/g, ''))
  ].map(normalizeJapanese))].filter(Boolean);
}

const INFLECTING_END = /[うくぐすつぬぶむるい]$/;
// Level of every word and kanji: the compiled levels (all spellings and readings, lowest level wins), then the
// J-LAS dictionary for words no level lists. A listed verb or い-adjective also gives its stem (降る → 降, 開ける →
// 開け, 暖かい → 暖か) so its conjugations read at the word's level even when its kanji is listed higher.
// kanaLevel holds only words the path itself spells in kana: not the dictionary's odd kana entries (ました, がある),
// not readings of kanji words (しま of 島 would split しました).
export function buildLevelIndex({ levels, dictionary }) {
  const levelOfWord = new Map(), kanaLevel = new Map(), kanjiLevel = new Map();
  const note = (map, key, lvl) => { if (!key || !(lvl in LEVEL_RANK)) return; const cur = map.get(key);
    if (!cur || LEVEL_RANK[lvl] < LEVEL_RANK[cur]) map.set(key, lvl); };
  for (const { level, vocabulary = [], kanji = [] } of levels) {
    for (const v of vocabulary) {
      const spellings = [v.expression, ...(v.variants ?? [])].flatMap(wordForms);
      for (const f of [...spellings, ...wordForms(v.normalized_reading)]) note(levelOfWord, f, level);
      for (const f of spellings) {
        if (!HAS_KANJI.test(f)) note(kanaLevel, f, level);
        else if (INFLECTING_END.test(f) && HAS_KANJI.test(f.slice(0, -1))) note(levelOfWord, f.slice(0, -1), level);
      }
    }
    for (const k of kanji) note(kanjiLevel, k.character, level);
  }
  for (const e of dictionary?.entryById?.values() ?? []) for (const f of [e.expression, e.reading].flatMap(wordForms)) note(levelOfWord, f, e.jlpt_level);
  for (const [ch, k] of dictionary?.kanjiByChar ?? []) note(kanjiLevel, ch, k.jlpt_level);
  return { levelOfWord, kanaLevel, kanjiLevel };
}

const KANA_RUN = { hira: /[ぁ-ゖ]/, kata: /[ァ-ヺー]/ };
// Family names N5 textbooks use for their characters (the brief allows them); their kanji (田, 鈴...) are not N5 kanji.
const TEXTBOOK_NAMES = new Set(['山田', '田中', '鈴木', '佐藤', '木村', '中村', '山本', '高橋', '小林', '吉田']);
const PARTICLES = ['から', 'まで', 'より', 'だけ', 'など', 'しか', 'ので', 'のに', 'けど', 'でも', 'って', 'ても',
  'は', 'が', 'を', 'に', 'へ', 'で', 'と', 'も', 'の', 'や', 'か', 'ね', 'よ', 'な'];

// Whether a hiragana run splits into particles and the path's kana words at or below the level (うちに = うち + に).
function splitsWithin(run, kanaLevel, exempt, limit) {
  const ok = Array(run.length + 1).fill(false);
  ok[0] = true;
  for (let p = 0; p < run.length; p++) {
    if (!ok[p]) continue;
    for (const pt of PARTICLES) if (run.startsWith(pt, p)) ok[p + pt.length] = true;
    for (let len = 2; p + len <= run.length; len++) {
      const w = run.slice(p, p + len), lvl = kanaLevel.get(w);
      if (exempt.has(w) || (lvl && LEVEL_RANK[lvl] <= limit)) ok[p + len] = true;
    }
  }
  return ok[run.length];
}

// Words of a sentence above the level. Kanji are firm evidence: the sentence is read the way that keeps it lowest —
// a word with kanji, or a lone kanji at its kanji level (N1 when no list has it) — and what stays above blocks. Kana
// are weak evidence (Mirai spells most words of every level in kana, conjugated forms included), so they only warn:
// a whole katakana word above the level, or a standalone hiragana run that is itself a word above the level and does
// not split into lower words and particles (ほうがいい never yields うがい). Okurigana after a kanji is free; the
// item's own word or pattern (`exempt`) is never above level.
function wordsAbove(sentence, ctx, level, exempt) {
  const text = String(sentence).normalize('NFKC'), n = text.length, limit = LEVEL_RANK[level];
  const kanjiLevel = ctx.kanjiLevel ?? new Map(), kanaMap = ctx.kanaLevel ?? ctx.levelOfWord;
  const levelOf = (w, map = ctx.levelOfWord) => { const k = normalizeJapanese(w); return exempt.has(k) ? null : map.get(k) ?? null; };
  const best = Array(n + 1).fill(null);
  best[0] = { cost: -1, kanji: [], kana: [] };
  const step = (i, j, lvl, w, kana = false) => {
    const r = lvl ? LEVEL_RANK[lvl] : -1, above = r > limit;
    const cost = kana ? best[i].cost : Math.max(best[i].cost, r);
    const next = { cost, kanji: above && !kana ? [...best[i].kanji, w] : best[i].kanji, kana: above && kana ? [...best[i].kana, w] : best[i].kana };
    if (!best[j] || cost < best[j].cost || (cost === best[j].cost && next.kanji.length < best[j].kanji.length)) best[j] = next;
  };
  const runEnd = (i, re) => { let j = i; while (j < n && re.test(text[j])) j++; return j; };
  for (let i = 0; i < n; i++) {
    if (!best[i]) continue;
    const c = text[i];
    for (let len = 1; len <= Math.min(10, n - i); len++) {
      const w = text.slice(i, i + len), k = normalizeJapanese(w);
      if (exempt.has(k) || TEXTBOOK_NAMES.has(w)) step(i, i + len, null, w);
      else if (HAS_KANJI.test(w) && ctx.levelOfWord.has(k)) step(i, i + len, levelOf(w), w);
    }
    const kind = KANA_RUN.kata.test(c) ? 'kata' : KANA_RUN.hira.test(c) ? 'hira' : null;
    if (kind && !(i > 0 && KANA_RUN[kind].test(text[i - 1]))) {
      const j = runEnd(i, KANA_RUN[kind]), w = text.slice(i, j), k = normalizeJapanese(w);
      let lvl = kind === 'kata' ? levelOf(w) : null;
      if (kind === 'hira' && j - i >= 2 && !(i > 0 && HAS_KANJI.test(text[i - 1]))) {
        const exact = levelOf(w, kanaMap);
        if (exact && LEVEL_RANK[exact] > limit && !splitsWithin(k, kanaMap, exempt, limit)) lvl = exact;
      }
      step(i, j, lvl, w, true);
      continue;
    }
    step(i, i + 1, HAS_KANJI.test(c) ? kanjiLevel.get(c) ?? 'N1' : null, c);
  }
  return best[n] ?? { kanji: [], kana: [] };
}

const JAPANESE_RUN = /[ぁ-ゖァ-ヺー一-龯々]+/g;
// The item's own word (vocabulary: every spelling and the reading) or its pattern's words (grammar).
function ownWords(item) {
  if (item.pattern) return new Set([item.pattern, ...(item.pattern_variants ?? []), item.family_key]
    .flatMap(p => String(p ?? '').match(JAPANESE_RUN) ?? []).map(normalizeJapanese));
  return new Set([item.expression, ...(item.variants ?? []), item.reading].flatMap(wordForms));
}

const stripFurigana = text => String(text).replace(/[(（][^)）]*[)）]/g, '');
// Values written by the AI pass ("CLAUDE" in data built before the rename).
export const isAiSource = source => source === 'AI' || source === 'CLAUDE';
const aiFields = x => x.provenance?.ai_fields ?? x.provenance?.claude_fields ?? [];
const ownMeaning = item => isAiSource(item.provenance?.meaning_source);

// First Mirai example (any level) more than 70% alike; lengths too far apart cannot pass the Dice test.
function similarToAny(ctx, text) {
  const x = plain(text);
  if (x.length < 6) return null;
  return (ctx.miraiExamples ?? []).find(r => { const y = plain(r);
    return y.length >= 6 && 2 * Math.min(x.length, y.length) / (x.length + y.length) > 0.7 && similarity(r, text) > 0.7; }) ?? null;
}

function checkExamples(item, examples, minCount, level, ctx, err, warn) {
  if (examples.length < minCount) err('EXAMPLE_MISSING', `${examples.length}/${minCount}`);
  for (const [i, e] of examples.entries()) {
    if (!e.vi) err('EXAMPLE_TRANSLATION_MISSING', e.ja);
    if (e.ja_furigana && stripFurigana(e.ja_furigana) !== e.ja) err('FURIGANA_MISMATCH', e.ja);
    if (!e.ja_furigana && HAS_KANJI.test(e.ja)) warn('FURIGANA_MISSING', e.ja);
    const above = wordsAbove(e.ja, ctx, level, ownWords(item));
    // Book, VNJPClub and reviewed sentences are not rewritten here: above-level words in them only warn.
    if (!isAiSource(e.source)) {
      if (above.kanji.length) warn('EXAMPLE_ABOVE_LEVEL_SOURCE', `${e.ja} → ${above.kanji.join(',')}`);
      continue;
    }
    if (above.kanji.length) err('EXAMPLE_ABOVE_LEVEL', `${e.ja} → ${above.kanji.join(',')}`);
    if (above.kana.length) warn('EXAMPLE_KANA_ABOVE_LEVEL', `${e.ja} → ${above.kana.join(',')}`);
    const len = e.ja.replace(/\s/g, '').length, [min, max] = EXAMPLE_LENGTH[level];
    if (len < min || len > max) err('EXAMPLE_LENGTH', `${len}: ${e.ja}`);
    const ref = similarToAny(ctx, e.ja);
    // The detail names the field only: Mirai text must never reach a report or a review packet.
    if (ref) err('MIRAI_SIMILAR', `example #${i + 1}`);
  }
}

function checkBank(g, err) {
  const bank = g.check_bank ?? [];
  const perType = BANK_TYPES.map(t => bank.filter(q => q.type === t).length);
  if (bank.length < 8 || perType.some(n => n < 2) || (g.order_items?.length ?? 0) < 2 || (g.fill_items?.length ?? 0) < 2
    || (g.extra_practice?.length ?? 0) < 3) err('GRAMMAR_BANK_SHORT', `bank ${bank.length} [${perType.join('/')}]`);
  for (const q of [...bank, ...(g.extra_practice ?? [])]) {
    const options = q.options ?? [];
    const correct = options[q.answer_index];
    if (correct == null) continue;
    if (options.some((o, i) => i !== q.answer_index && plain(o) === plain(correct))) err('DISTRACTOR_ALSO_CORRECT', q.prompt_ja);
  }
}

export function runChecks(compiled, ctx) {
  const errors = [];
  const level = compiled.level;
  const sink = id => ({ err: (code, detail) => errors.push({ code, id, detail }),
    warn: (code, detail) => errors.push({ code, id, detail, warning: true }) });
  const dup = (list, keyOf) => {
    const seen = new Map();
    for (const x of list) { const k = keyOf(x); if (seen.has(k)) errors.push({ code: 'DUPLICATE_KEY', id: x.id, detail: `${k} = ${seen.get(k)}` }); else seen.set(k, x.id); }
  };
  dup(compiled.vocabulary, v => v.key);
  dup(compiled.kanji, k => k.character);
  dup(compiled.grammar, g => g.family_key);
  dup([...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar], x => x.id);
  for (const m of compiled.mixed_entries ?? []) errors.push({ code: 'MIXED_ENTRIES', id: m.id,
    detail: m.spellings.map(s => `${s.expression}#${s.entry_id}`).join(', ') });
  for (const t of compiled.spelling_twins ?? []) errors.push({ code: 'SAME_SPELLING', id: t.id, detail: `${t.twin_id} (${t.twin_level})`, warning: true });

  for (const v of compiled.vocabulary) {
    const { err, warn } = sink(v.id);
    if (v.normalized_expression !== normalizeJapanese(v.expression) || v.normalized_reading !== normalizeJapanese(v.reading))
      err('NORMALIZATION_MISMATCH', v.key);
    if (!v.meaning_vi || v.meaning_vi !== formatMeaningVi([v.meaning_vi])) err('MEANING_FORMAT', v.meaning_vi);
    if (!v.word_type_label) err('WORD_TYPE_MISSING', v.key);
    if (!v.mnemonic_vi) err('MNEMONIC_MISSING', v.key);
    checkExamples(v, v.examples, 1, level, ctx, err, warn);
    // Every spelling of the word against Mirai's word read the same way.
    const refs = [v.expression, ...(v.variants ?? [])].map(e => ctx.miraiReference.get(`${normalizeJapanese(e)}|${v.normalized_reading}`)).filter(Boolean);
    if (ownMeaning(v) && refs.some(r => r.meaning_vi && similarity(r.meaning_vi, v.meaning_vi) > 0.7)) err('MIRAI_SIMILAR', 'meaning');
  }
  for (const k of compiled.kanji) {
    const { err, warn } = sink(k.id);
    if (!k.han_viet || !k.meanings_vi) err('KANJI_CORE_MISSING', k.character);
    else if (k.meanings_vi !== formatMeaningVi([k.meanings_vi])) err('MEANING_FORMAT', k.meanings_vi);
    if (!k.mnemonic_vi) err('MNEMONIC_MISSING', k.character);
    if (k.radical && !k.radical.name_vi) warn('RADICAL_NAME_MISSING', String(k.radical.number));
  }
  for (const g of compiled.grammar) {
    const { err, warn } = sink(g.id);
    if (!g.meaning_vi) err('MEANING_FORMAT', g.pattern);
    const gref = ctx.miraiGrammar?.get(g.family_key);
    if (gref && ownMeaning(g) && similarity(gref, g.meaning_vi) > 0.7) err('MIRAI_SIMILAR', 'meaning');
    if (['formation', 'usage_vi', 'nuance_vi', 'common_mistakes_vi'].some(f => !g[f])) err('GRAMMAR_CORE_MISSING', g.pattern);
    if (!g.unit_no) err('UNIT_MISSING', g.pattern);
    checkExamples(g, g.examples, 3, level, ctx, err, warn);
    checkBank(g, err);
  }

  const ids = new Set([...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].map(x => x.id));
  for (const m of compiled.merged_ids ?? []) ids.add(m.id);
  for (const id of ctx.expectedIds) if (!ids.has(id) && !ctx.otherLevelIds.has(id)) errors.push({ code: 'ITEM_LOST', id, detail: ctx.lostDetail?.(id) ?? '' });
  for (const id of ids) if (ctx.otherLevelIds.has(id)) errors.push({ code: 'MULTI_LEVEL', id, detail: '' });

  const byCode = {};
  for (const e of errors) byCode[e.code] = (byCode[e.code] ?? 0) + 1;
  const stats = { vocabulary: compiled.vocabulary.length, kanji: compiled.kanji.length, grammar: compiled.grammar.length,
    from_frame: compiled.vocabulary.filter(v => v.provenance?.frame).length,
    jlas_matched: compiled.vocabulary.filter(v => v.provenance?.sources?.includes('JLAS')).length,
    ai_authored: [...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].filter(x => aiFields(x).length).length,
    level_moves: compiled.level_moves?.length ?? 0, conflicts: compiled.conflicts?.length ?? 0, errors_by_code: byCode };
  return { errors, stats };
}

// Items with any blocking error stay DRAFT (hidden); clean items become AI_CHECKED when the AI pass wrote any field,
// otherwise REVIEWED. HOLD (reported) items keep HOLD.
export function applyStatuses(compiled, result) {
  const blocked = new Set(result.errors.filter(e => !e.warning && !WARNING_CODES.has(e.code)).map(e => e.id));
  const status = x => x.status === 'HOLD' ? 'HOLD' : blocked.has(x.id) ? 'DRAFT'
    : aiFields(x).length ? 'AI_CHECKED' : 'REVIEWED';
  return { ...compiled, vocabulary: compiled.vocabulary.map(v => ({ ...v, status: status(v) })),
    kanji: compiled.kanji.map(k => ({ ...k, status: status(k) })), grammar: compiled.grammar.map(g => ({ ...g, status: status(g) })) };
}
