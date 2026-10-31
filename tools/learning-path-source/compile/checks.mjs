// Cleanliness checks for a compiled JLPT level (spec 4.11 "Bộ kiểm độ sạch tự động") and the item status rule.
import { LEVEL_RANK, normalizeJapanese } from './util.mjs';
import { formatMeaningVi } from './normalize.mjs';

export const WARNING_CODES = new Set(['FURIGANA_MISSING', 'RADICAL_NAME_MISSING', 'SAME_SPELLING']);
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

function wordsAbove(sentence, levelOfWord, level) {
  const text = normalizeJapanese(sentence);
  const found = [];
  for (let i = 0; i < text.length;) {
    let matched = 0;
    for (let len = Math.min(8, text.length - i); len >= 1; len--) {
      const w = text.slice(i, i + len);
      if (!(HAS_KANJI.test(w) || len >= 3)) continue;
      const lvl = levelOfWord.get(w);
      if (lvl) { if (LEVEL_RANK[lvl] > LEVEL_RANK[level]) found.push(w); matched = len; break; }
    }
    i += matched || 1;
  }
  return found;
}

const stripFurigana = text => String(text).replace(/[(（][^)）]*[)）]/g, '');
const ownMeaning = item => item.provenance?.meaning_source === 'CLAUDE';

function checkExamples(item, examples, minCount, level, ctx, err, warn) {
  if (examples.length < minCount) err('EXAMPLE_MISSING', `${examples.length}/${minCount}`);
  for (const e of examples) {
    if (!e.vi) err('EXAMPLE_TRANSLATION_MISSING', e.ja);
    if (e.ja_furigana && stripFurigana(e.ja_furigana) !== e.ja) err('FURIGANA_MISMATCH', e.ja);
    if (!e.ja_furigana && HAS_KANJI.test(e.ja)) warn('FURIGANA_MISSING', e.ja);
    if (e.source !== 'CLAUDE') continue;
    const above = wordsAbove(e.ja, ctx.levelOfWord, level);
    if (above.length) err('EXAMPLE_ABOVE_LEVEL', `${e.ja} → ${above.join(',')}`);
    const len = e.ja.replace(/\s/g, '').length, [min, max] = EXAMPLE_LENGTH[level];
    if (len < min || len > max) err('EXAMPLE_LENGTH', `${len}: ${e.ja}`);
    const ref = ctx.miraiExamples?.find(r => similarity(r, e.ja) > 0.7);
    if (ref) err('MIRAI_SIMILAR', `example ≈ ${ref}`);
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
  for (const t of compiled.spelling_twins ?? []) errors.push({ code: 'SAME_SPELLING', id: t.id, detail: `${t.twin_id} (${t.twin_level})`, warning: true });

  for (const v of compiled.vocabulary) {
    const { err, warn } = sink(v.id);
    if (v.normalized_expression !== normalizeJapanese(v.expression) || v.normalized_reading !== normalizeJapanese(v.reading))
      err('NORMALIZATION_MISMATCH', v.key);
    if (!v.meaning_vi || v.meaning_vi !== formatMeaningVi([v.meaning_vi])) err('MEANING_FORMAT', v.meaning_vi);
    if (!v.word_type_label) err('WORD_TYPE_MISSING', v.key);
    if (!v.mnemonic_vi) err('MNEMONIC_MISSING', v.key);
    checkExamples(v, v.examples, 1, level, ctx, err, warn);
    const ref = ctx.miraiReference.get(v.key);
    if (ref?.meaning_vi && ownMeaning(v) && similarity(ref.meaning_vi, v.meaning_vi) > 0.7) err('MIRAI_SIMILAR', `meaning ≈ ${ref.meaning_vi}`);
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
    if (['formation', 'usage_vi', 'nuance_vi', 'common_mistakes_vi'].some(f => !g[f])) err('GRAMMAR_CORE_MISSING', g.pattern);
    if (!g.unit_no) err('UNIT_MISSING', g.pattern);
    checkExamples(g, g.examples, 3, level, ctx, err, warn);
    checkBank(g, err);
  }

  const ids = new Set([...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].map(x => x.id));
  for (const m of compiled.merged_ids ?? []) ids.add(m.id);
  for (const id of ctx.expectedIds) if (!ids.has(id) && !ctx.otherLevelIds.has(id)) errors.push({ code: 'ITEM_LOST', id, detail: '' });
  for (const id of ids) if (ctx.otherLevelIds.has(id)) errors.push({ code: 'MULTI_LEVEL', id, detail: '' });

  const byCode = {};
  for (const e of errors) byCode[e.code] = (byCode[e.code] ?? 0) + 1;
  const stats = { vocabulary: compiled.vocabulary.length, kanji: compiled.kanji.length, grammar: compiled.grammar.length,
    from_frame: compiled.vocabulary.filter(v => v.provenance?.frame).length,
    jlas_matched: compiled.vocabulary.filter(v => v.provenance?.sources?.includes('JLAS')).length,
    claude_authored: [...compiled.vocabulary, ...compiled.kanji, ...compiled.grammar].filter(x => x.provenance?.claude_fields?.length).length,
    level_moves: compiled.level_moves?.length ?? 0, conflicts: compiled.conflicts?.length ?? 0, errors_by_code: byCode };
  return { errors, stats };
}

// Items with any blocking error stay DRAFT (hidden); clean items become AI_CHECKED when Claude wrote any field,
// otherwise REVIEWED. HOLD (reported) items keep HOLD.
export function applyStatuses(compiled, result) {
  const blocked = new Set(result.errors.filter(e => !e.warning && !WARNING_CODES.has(e.code)).map(e => e.id));
  const status = x => x.status === 'HOLD' ? 'HOLD' : blocked.has(x.id) ? 'DRAFT'
    : x.provenance?.claude_fields?.length ? 'AI_CHECKED' : 'REVIEWED';
  return { ...compiled, vocabulary: compiled.vocabulary.map(v => ({ ...v, status: status(v) })),
    kanji: compiled.kanji.map(k => ({ ...k, status: status(k) })), grammar: compiled.grammar.map(g => ({ ...g, status: status(g) })) };
}
