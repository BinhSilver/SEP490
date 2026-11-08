// Authoring packets: the content still missing after the merge is written by the AI pass into JSON packets under
// data/learning-path/authoring/<level>/, then applied back. Packets only fill empty fields; they never overwrite
// J-LAS, book, dictionary or reviewed content. Files are never rewritten once emitted, so answers stay stable.
// Review packets (REVIEW_*, a second pass by a different author) cover every item and may correct any value that
// does not come from a reviewed source; they apply last. Spelling/reading fixes and "same word" verdicts also feed
// the merge (reviewCorrections) so the corrected word groups with its twins.
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { LEVELS, ROOT, argValue, json, normalizeJapanese, readJson } from './util.mjs';
import { formatMeaningVi, grammarFamilyKey, normalizeGrammarPattern } from './normalize.mjs';
import { parentCandidates, toRomaji } from './merge.mjs';

const BANK = { check_bank: 8, order_items: 2, fill_items: 2, extra_practice: 3 };
const GRAMMAR_TEXT = ['meaning_vi', 'formation', 'usage_vi', 'nuance_vi', 'common_mistakes_vi'];

const INSTRUCTIONS = {
  VOCAB_MEANING: 'meaning_vi: chữ thường, tối đa 3 nghĩa ngăn bởi "; ", nghĩa chính đầu, không dấu chấm, không ngoặc tiếng Anh.',
  VOCAB_TYPE: 'word_type_label: một nhãn trong {danh từ, động từ nhóm I/II/III, tính từ い, tính từ な, phó từ, trợ từ, liên từ, thán từ, cụm từ, số từ, đại từ}.',
  VOCAB_EXAMPLE: 'examples: 1–3 câu {ja, ja_furigana "漢字(かんじ)", vi}; chỉ dùng từ và ngữ pháp ≤ cấp; N5 dài 8–25 ký tự, N4 8–32, N3 ≤ 40, N2 ≤ 50, N1 ≤ 60; không tên riêng lạ; tự viết, không chép nguồn ngoài.',
  VOCAB_MNEMONIC: 'mnemonic_vi: 1–2 câu liên tưởng Hán Việt hoặc âm gần tiếng Việt, không tục; confusable_ids (tùy chọn): id các từ dễ nhầm cùng hoặc thấp hơn cấp.',
  TOPIC_ASSIGN: 'topic_key/topic_vi: chọn chủ đề gần nghĩa nhất trong header.topics; không hợp thì "khac"/"Từ thông dụng khác".',
  KANJI_CORE: 'han_viet (âm chính), han_viet_alt (âm phụ), meanings_vi theo khuôn nghĩa.',
  KANJI_MNEMONIC: 'mnemonic_vi: tách bộ thủ/thành phần thành câu chuyện hình ảnh 1–2 câu; confusable_ids (tùy chọn): Kanji dễ nhầm.',
  RADICAL_NAME: 'name_vi: tên bộ thủ tiếng Việt theo bảng 214 bộ (vd 亻 "nhân đứng").',
  GRAMMAR_CORE: 'Các trường còn thiếu: meaning_vi, formation (ký hiệu 〜, V-て, N, Aい, Aな), usage_vi, nuance_vi, common_mistakes_vi, examples (đủ 3 câu {ja, ja_furigana, vi}).',
  GRAMMAR_UNIT: 'unit_no: số bài (theo header.units) phù hợp nhất; unit_title_vi tùy chọn.',
  GRAMMAR_PARENT: 'parent_id: id một mẫu CHÍNH (MAIN) cùng chức năng; chọn trong context.candidates, không hợp thì chọn trong header.mains. Mẫu phụ được học ở cuối bài của mẫu chính đó.',
  GRAMMAR_BANK: 'check_bank ≥ 8 câu, mỗi dạng FILL/ORDER_STAR/MEANING/ERROR ≥ 2: {type, prompt_ja, options[4], answer_index, explanation_vi}; order_items 2 {pieces[], answer_order[]}; fill_items 2 {sentence_ja với ＿＿, answer}; extra_practice 3 (cùng dạng check_bank). Đáp án nhiễu không được cũng đúng.'
};
const REVIEW_INSTRUCTION = 'verdict: {"ok": true} nếu mục đúng; sai thì {"fix": {<trường>: <giá trị mới>}, "note": "lý do ngắn"} (chỉ ghi trường cần sửa, theo đúng khuôn của trường đó); mục và một "twins" là cùng một từ thì thêm "same_word_as": "<id twin>"; (ngữ pháp: sửa mẫu bằng "fix": {"pattern": "〜…"}, mẫu trùng một mẫu khác thì thêm "same_pattern_as": "<id>"); mục bị ghép nhầm với một từ khác thì thêm "not_same_word_as": "<id từ kia>" hoặc "drop_variant": "<cách viết sai>" (bỏ một cách viết thuộc từ khác khỏi "variants"); câu ví dụ sai (kể cả câu đã duyệt) thì thêm "drop_example": "<câu ja>"; câu đã duyệt thiếu ja_furigana thì chép lại nguyên câu trong "fix.examples" kèm ja_furigana.';
for (const k of ['REVIEW_VOCAB', 'REVIEW_KANJI', 'REVIEW_GRAMMAR']) INSTRUCTIONS[k] = REVIEW_INSTRUCTION;
// A SECONDARY grammar pattern (split level, N3) has light content only: meaning, formation, 1–2 examples.
const SECONDARY_TEXT = ['meaning_vi', 'formation'];
const SECONDARY_EXAMPLES = 2;
const SECONDARY_CORE = 'Mẫu PHỤ (học ở cuối bài của mẫu chính): chỉ cần meaning_vi, formation (ký hiệu 〜, V-て, N, Aい, Aな), examples 1–2 câu {ja, ja_furigana, vi}. Không viết usage/nuance/lỗi thường gặp, không câu hỏi.';
const isSecondary = g => g.grammar_role === 'SECONDARY';

// Fields a review may correct; a value from a reviewed source (…_REVIEWED provenance) is never replaced.
const FIXABLE = {
  REVIEW_VOCAB: ['expression', 'reading', 'meaning_vi', 'word_type_label', 'topic_key', 'topic_vi', 'examples', 'mnemonic_vi', 'confusable_ids', 'variants'],
  REVIEW_KANJI: ['han_viet', 'han_viet_alt', 'meanings_vi', 'mnemonic_vi', 'confusable_ids'],
  REVIEW_GRAMMAR: ['pattern', ...GRAMMAR_TEXT, 'examples', 'unit_no', 'unit_title_vi', ...Object.keys(BANK)]
};
const REVIEW_KINDS = new Set(Object.keys(FIXABLE));
const SOURCE_OF = { meaning_vi: 'meaning_source', meanings_vi: 'meaning_source', word_type_label: 'word_type_source', examples: 'example_source' };
const ARRAY_FIELDS = new Set(['han_viet_alt', 'confusable_ids', 'variants']);

const vocabContext = v => ({ expression: v.expression, reading: v.reading, meaning_vi: v.meaning_vi, word_type_label: v.word_type_label,
  topic_vi: v.topic_vi, examples: v.examples.map(e => ({ ja: e.ja, vi: e.vi })) });
const kanjiContext = k => ({ character: k.character, han_viet: k.han_viet, meanings_vi: k.meanings_vi, readings_on: k.readings_on,
  readings_kun: k.readings_kun, level_readings: k.level_readings, radical: k.radical });
const grammarContext = g => ({ pattern: g.pattern, pattern_variants: g.pattern_variants, meaning_vi: g.meaning_vi, unit_no: g.unit_no,
  examples: g.examples.map(e => ({ ja: e.ja, vi: e.vi })), source_questions: g.source_questions ?? [] });
const secondaryContext = g => ({ pattern: g.pattern, pattern_variants: g.pattern_variants, meaning_vi: g.meaning_vi,
  examples: g.examples.map(e => ({ ja: e.ja, vi: e.vi })) });
const mainList = level => level.grammar.filter(g => g.grammar_role === 'MAIN');

const inQueue = v => v.queue !== 'EXTRA';

function needs(level) {
  const out = [];
  const add = (kind, item, fields, context, variant = null) => out.push({ kind, id: item.id, fields_needed: fields, context, variant });
  // Words out of the study queue (queue EXTRA) are not authored.
  for (const v of level.vocabulary.filter(inQueue)) {
    if (!v.meaning_vi) add('VOCAB_MEANING', v, ['meaning_vi'], vocabContext(v));
    if (!v.word_type_label) add('VOCAB_TYPE', v, ['word_type_label'], vocabContext(v));
    if (!v.examples.length || !v.examples[0].vi) add('VOCAB_EXAMPLE', v, ['examples'], vocabContext(v));
    if (!v.mnemonic_vi) add('VOCAB_MNEMONIC', v, ['mnemonic_vi'], vocabContext(v));
    if (v.topic_key === 'khac') add('TOPIC_ASSIGN', v, ['topic_key', 'topic_vi'], vocabContext(v));
  }
  const radicals = new Map();
  // Kanji out of the study queue (capped level, queue EXTRA) are not authored either.
  for (const k of level.kanji.filter(inQueue)) {
    const missing = ['han_viet', 'meanings_vi'].filter(f => !k[f]);
    if (missing.length) add('KANJI_CORE', k, missing, kanjiContext(k));
    if (!k.mnemonic_vi) add('KANJI_MNEMONIC', k, ['mnemonic_vi'], kanjiContext(k));
    if (k.radical && !k.radical.name_vi && !radicals.has(k.radical.number)) radicals.set(k.radical.number, k.character);
  }
  for (const [n, sample] of [...radicals].sort((a, b) => a[0] - b[0]))
    out.push({ kind: 'RADICAL_NAME', id: `R-${n}`, fields_needed: ['name_vi'], context: { number: n, sample_kanji: sample } });
  for (const g of level.grammar.filter(x => !isSecondary(x))) {
    const text = GRAMMAR_TEXT.filter(f => !g[f]);
    if (text.length || g.examples.length < 3) add('GRAMMAR_CORE', g, [...text, ...(g.examples.length < 3 ? ['examples'] : [])], grammarContext(g));
    if (!g.unit_no) add('GRAMMAR_UNIT', g, ['unit_no', 'unit_title_vi'], grammarContext(g));
    const bank = Object.entries(BANK).filter(([f, n]) => (g[f]?.length ?? 0) < n).map(([f]) => f);
    if (bank.length) add('GRAMMAR_BANK', g, bank, grammarContext(g));
  }
  const mains = mainList(level);
  for (const g of level.grammar.filter(isSecondary)) {
    const text = SECONDARY_TEXT.filter(f => !g[f]);
    if (text.length || !g.examples.length) add('GRAMMAR_CORE', g, [...text, ...(g.examples.length ? [] : ['examples'])], secondaryContext(g), 'SECONDARY');
    if (!g.parent_id) add('GRAMMAR_PARENT', g, ['parent_id'], { ...secondaryContext(g), candidates: parentCandidates(g, mains) });
  }
  return out;
}

function header(kind, level, variant = null) {
  const h = { instructions: variant === 'SECONDARY' && kind === 'GRAMMAR_CORE' ? SECONDARY_CORE : INSTRUCTIONS[kind] };
  if (variant) h.grammar_role = variant;
  if (kind === 'GRAMMAR_PARENT') h.mains = mainList(level).map(g => ({ id: g.id, pattern: g.pattern, meaning_vi: g.meaning_vi }));
  if (kind === 'TOPIC_ASSIGN') h.topics = [...new Map(level.vocabulary.filter(v => v.topic_key !== 'khac')
    .map(v => [v.topic_key, v.topic_vi])).entries()].map(([topic_key, topic_vi]) => ({ topic_key, topic_vi }));
  if (kind === 'GRAMMAR_UNIT') h.units = [...new Set(level.grammar.filter(g => !isSecondary(g)).map(g => g.unit_no).filter(Boolean))].sort((a, b) => a - b);
  return h;
}

const chunk = (list, size) => Array.from({ length: Math.ceil(list.length / size) }, (_, i) => list.slice(i * size, (i + 1) * size));
const packetId = (kind, n) => `${kind}-${String(n).padStart(3, '0')}`;
const toPacket = (kind, n, level, items, variant = null) => ({ packet_id: packetId(kind, n), kind, level: level.level, header: header(kind, level, variant),
  items: items.map(({ id, fields_needed, context }) => ({ id, fields_needed, context, answer: null })) });

export const DEFAULT_SIZE_BY_KIND = { GRAMMAR_BANK: 10 };

// Needs grouped by kind, then variant (a SECONDARY GRAMMAR_CORE never shares a packet with a MAIN one); packet
// numbers run on per kind across its variants.
function groupNeeds(list) {
  const byKind = new Map();
  for (const n of list) {
    if (!byKind.has(n.kind)) byKind.set(n.kind, new Map());
    const variants = byKind.get(n.kind);
    if (!variants.has(n.variant)) variants.set(n.variant, []);
    variants.get(n.variant).push(n);
  }
  return byKind;
}

export function buildPackets(level, { size = 40, sizeByKind = {} } = {}) {
  return [...groupNeeds(needs(level))].flatMap(([kind, variants]) => {
    let n = 0;
    return [...variants].flatMap(([variant, items]) => chunk(items, sizeByKind[kind] ?? size).map(c => toPacket(kind, ++n, level, c, variant)));
  });
}

const reviewContext = {
  REVIEW_VOCAB: v => ({ expression: v.expression, reading: v.reading, romaji: v.romaji, variants: v.variants, meaning_vi: v.meaning_vi,
    word_type_label: v.word_type_label, topic_vi: v.topic_vi, mnemonic_vi: v.mnemonic_vi, confusable_ids: v.confusable_ids,
    examples: v.examples.map(e => ({ ja: e.ja, ja_furigana: e.ja_furigana, vi: e.vi, source: e.source })) }),
  REVIEW_KANJI: k => ({ ...kanjiContext(k), han_viet_alt: k.han_viet_alt, stroke_count: k.stroke_count, compound_ids: k.compound_ids,
    mnemonic_vi: k.mnemonic_vi, confusable_ids: k.confusable_ids }),
  REVIEW_GRAMMAR: g => ({ pattern: g.pattern, pattern_variants: g.pattern_variants, unit_no: g.unit_no, unit_title_vi: g.unit_title_vi,
    ...Object.fromEntries(GRAMMAR_TEXT.map(f => [f, g[f]])), examples: g.examples.map(e => ({ ja: e.ja, ja_furigana: e.ja_furigana, vi: e.vi, source: e.source })),
    ...Object.fromEntries(Object.keys(BANK).map(f => [f, g[f] ?? []])) })
};

const lockedFields = x => [...new Set(Object.entries(SOURCE_OF).filter(([f, src]) => f !== 'examples' && f in x && /REVIEWED/.test(x.provenance?.[src] ?? '')).map(([f]) => f))];

// extra(kind, item) adds what the item alone does not carry (dictionary meaning, check findings, spelling twins).
export function buildReviewPackets(level, { size = 40, sizeByKind = { REVIEW_GRAMMAR: 10 }, extra = () => ({}) } = {}) {
  const lists = [['REVIEW_VOCAB', level.vocabulary.filter(inQueue)], ['REVIEW_KANJI', level.kanji.filter(inQueue)], ['REVIEW_GRAMMAR', level.grammar]];
  return lists.filter(([, items]) => items.length).flatMap(([kind, items]) => chunk(items.map(x => ({ id: x.id, fields_needed: ['verdict'],
    context: { ...reviewContext[kind](x), locked_fields: lockedFields(x), ...extra(kind, x) } })), sizeByKind[kind] ?? size).map((c, i) => toPacket(kind, i + 1, level, c)));
}

// Review packets for items no review packet covers yet; existing files are never rewritten. `ids` instead picks
// exactly those items, covered ones included (a later pass on items whose word changed); the newer packet applies last.
export function emitReviewPackets(level, dir, { ids = null, ...opts } = {}) {
  const existing = readPackets(dir).filter(p => REVIEW_KINDS.has(p.kind));
  const present = new Set(existing.flatMap(p => p.items.map(i => `${p.kind}|${i.id}`)));
  const wanted = ids ? new Set(ids) : null;
  const pick = kind => x => wanted ? wanted.has(x.id) : !present.has(`${kind}|${x.id}`);
  const fresh = { ...level, vocabulary: level.vocabulary.filter(pick('REVIEW_VOCAB')),
    kanji: level.kanji.filter(pick('REVIEW_KANJI')), grammar: level.grammar.filter(pick('REVIEW_GRAMMAR')) };
  const lastNumber = kind => Math.max(0, ...existing.filter(p => p.kind === kind).map(p => Number(p.packet_id.slice(-3))));
  const offset = Object.fromEntries([...REVIEW_KINDS].map(k => [k, lastNumber(k)]));
  mkdirSync(dir, { recursive: true });
  return buildReviewPackets(fresh, opts).map(p => {
    const n = offset[p.kind] + Number(p.packet_id.slice(-3));
    const packet = { ...p, packet_id: packetId(p.kind, n) };
    writeFileSync(path.join(dir, `${packet.packet_id}.json`), json(packet));
    return packet.packet_id;
  });
}

// Spelling/reading fixes and same-word / not-same-word / dropped-variant verdicts, which must reach the merge before
// words are grouped.
// identity is keyed by item id (reaches J-LAS words), identityByForm by the reviewed "expression|reading" (reaches
// Mirai words, which carry no id before the merge).
export function reviewCorrections(packets) {
  const identity = new Map(), identityByForm = new Map(), sameWord = [], notSameWord = [], dropVariant = new Map();
  const grammarPattern = new Map(), samePattern = [];
  for (const p of packets.filter(p => p.kind === 'REVIEW_GRAMMAR')) for (const { id, answer } of p.items) {
    if (!empty(answer?.fix?.pattern)) grammarPattern.set(id, answer.fix.pattern);
    if (answer?.same_pattern_as) samePattern.push([id, answer.same_pattern_as]);
  }
  for (const p of packets.filter(p => p.kind === 'REVIEW_VOCAB')) for (const { id, context, answer } of p.items) {
    const fix = Object.fromEntries(['expression', 'reading'].filter(f => !empty(answer?.fix?.[f])).map(f => [f, answer.fix[f]]));
    if (Object.keys(fix).length) {
      identity.set(id, fix);
      if (context?.expression && context?.reading) identityByForm.set(`${context.expression}|${context.reading}`, fix);
    }
    if (answer?.same_word_as) sameWord.push([id, answer.same_word_as]);
    for (const other of [].concat(answer?.not_same_word_as ?? [])) notSameWord.push([id, other]);
    const dropped = [].concat(answer?.drop_variant ?? []).filter(Boolean);
    if (dropped.length) dropVariant.set(id, new Set([...(dropVariant.get(id) ?? []), ...dropped]));
  }
  return { identity, identityByForm, sameWord, notSameWord, dropVariant, grammarPattern, samePattern };
}

// Corrections are global: a review at one level (N5 どなた same_word_as a J-LAS N4 word) also moves or fixes the word
// when another level is compiled, so every level reads the review packets of every level (in level order).
export const correctionDirs = () => LEVELS.map(authoringDir);
export const globalCorrections = (dirs = correctionDirs()) => reviewCorrections(dirs.flatMap(readPackets));

export function readPackets(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).filter(f => f.endsWith('.json')).sort().map(f => readJson(path.join(dir, f)));
}

// `kinds` limits the emit to some packet kinds (null: every kind).
export function emitPackets(level, dir, { size = 40, sizeByKind = {}, kinds = null } = {}) {
  const existing = readPackets(dir);
  const present = new Set(existing.flatMap(p => p.items.map(i => `${p.kind}|${i.id}`)));
  const lastNumber = kind => Math.max(0, ...existing.filter(p => p.kind === kind).map(p => Number(p.packet_id.slice(-3))));
  const fresh = needs(level).filter(n => !present.has(`${n.kind}|${n.id}`) && (!kinds || kinds.includes(n.kind)));
  mkdirSync(dir, { recursive: true });
  const written = [];
  for (const [kind, variants] of groupNeeds(fresh)) {
    let n = lastNumber(kind);
    for (const [variant, items] of variants) for (const c of chunk(items, sizeByKind[kind] ?? size)) {
      const p = toPacket(kind, ++n, level, c, variant);
      writeFileSync(path.join(dir, `${p.packet_id}.json`), json(p));
      written.push(p.packet_id);
    }
  }
  return written;
}

const empty = value => value == null || value === '' || (Array.isArray(value) && value.length === 0);
function setIfEmpty(item, field, value, transform = x => x) {
  if (empty(value) || !empty(item[field])) return false;
  item[field] = transform(value);
  (item.provenance.ai_fields ??= []).push(field);
  return true;
}
function addExamples(item, examples, max) {
  if (!Array.isArray(examples) || !examples.length) return;
  const have = new Set(item.examples.map(e => e.ja));
  const added = examples.filter(e => e?.ja && !have.has(e.ja)).slice(0, Math.max(0, max - item.examples.length))
    .map(e => ({ ja: e.ja, ja_furigana: e.ja_furigana ?? null, vi: e.vi ?? null, source: 'AI' }));
  if (!added.length) return;
  item.examples = [...item.examples, ...added].map((e, i) => ({ ...e, role: i === 0 ? 'MAIN' : 'EXTRA' }));
  (item.provenance.ai_fields ??= []).push('examples');
  item.provenance.example_source = item.examples[0].source;
}

const reviewed = source => /REVIEWED/.test(source ?? '');

// Where the value being replaced came from: its provenance source, else the AI pass when it wrote the field, else
// the merged sources.
function sourceOf(item, field) {
  const src = item.provenance?.[SOURCE_OF[field]];
  if (src) return src;
  return (item.provenance?.ai_fields ?? item.provenance?.claude_fields ?? []).includes(field) ? 'AI' : 'SOURCE';
}

// A review fix replaces the field; every override is logged (conflicts, kind REVIEW_FIX) and every sentence it drops
// is listed (dropped_examples), so the report shows what the review changed. An echoed sentence keeps its source.
function applyFix(item, field, value, log) {
  if (field !== 'examples' && reviewed(item.provenance?.[SOURCE_OF[field]])) return;
  const before = structuredClone(item[field] ?? null), beforeSource = sourceOf(item, field);
  if (field === 'meaning_vi' || field === 'meanings_vi') { item[field] = formatMeaningVi([].concat(value)); item.provenance.meaning_source = 'AI'; }
  else if (field === 'examples') {
    if (!Array.isArray(value) || !value.length) return;
    // Reviewed sentences stay (first); the review's own sentences replace the rest.
    // Reviewed sentences stay as written; an echo of one may only add the furigana it lacks (spelling the same text).
    const kept = item.examples.filter(e => reviewed(e.source)).map(e => {
      const echo = value.find(v => v?.ja === e.ja && v.ja_furigana && stripFurigana(v.ja_furigana) === e.ja);
      return !e.ja_furigana && echo ? { ...e, ja_furigana: echo.ja_furigana } : e;
    });
    const fresh = value.filter(e => e?.ja && !kept.some(k => k.ja === e.ja)).map(e => ({ ja: e.ja, ja_furigana: e.ja_furigana ?? null,
      vi: e.vi ?? null, source: item.examples.find(o => o.ja === e.ja)?.source ?? 'AI' }));
    item.examples = [...kept, ...fresh].slice(0, 3).map((e, i) => ({ ...e, role: i === 0 ? 'MAIN' : 'EXTRA' }));
    // The same sentences as before keep their order (a furigana-only echo moves no sentence, so no audio moves).
    const order = (before ?? []).map(e => e.ja);
    if (item.examples.length === order.length && item.examples.every(e => order.includes(e.ja)))
      item.examples = order.map(ja => item.examples.find(e => e.ja === ja)).map((e, i) => ({ ...e, role: i === 0 ? 'MAIN' : 'EXTRA' }));
    item.provenance.example_source = item.examples[0]?.source ?? null;
    for (const e of before ?? []) if (!item.examples.some(n => n.ja === e.ja)) log.dropped.push({ id: item.id, ja: e.ja, vi: e.vi ?? null, source: e.source });
  } else if (field === 'pattern') {
    item.pattern = normalizeGrammarPattern(value);
    item.family_key = grammarFamilyKey(value);
  } else if (field === 'expression' || field === 'reading') {
    item[field] = value;
    item[`normalized_${field}`] = normalizeJapanese(value);
    if (field === 'reading' || field === 'expression') item.romaji = toRomaji(item.reading, item.expression);
  } else {
    item[field] = field === 'unit_no' ? Number(value) : value;
    if (field === 'word_type_label') item.provenance.word_type_source = 'AI';
  }
  item.provenance.fixed_fields = [...new Set([...(item.provenance.fixed_fields ?? []), field])].sort();
  (item.provenance.ai_fields ??= []).push(field);
  if (field === 'confusable_ids') for (const gone of (before ?? []).filter(x => !value.includes(x))) log.unlinked.add(`${item.id}|${gone}`);
  if (JSON.stringify(before) !== JSON.stringify(item[field] ?? null))
    log.conflicts.push({ kind: 'REVIEW_FIX', id: item.id, field, chosen: 'AI',
      values: [{ source: beforeSource, value: before }, { source: 'AI', value: structuredClone(item[field]) }] });
}

const stripFurigana = text => String(text).replace(/[(（][^)）]*[)）]/g, '');

// drop_example: sentences a review rules wrong (any source, reviewed ones included) leave the item; logged like a fix.
function dropExamples(item, sentences, log) {
  const gone = new Set([].concat(sentences ?? []).filter(Boolean));
  if (!gone.size || !item.examples?.some(e => gone.has(e.ja))) return;
  const before = structuredClone(item.examples), beforeSource = sourceOf(item, 'examples');
  item.examples = item.examples.filter(e => !gone.has(e.ja)).map((e, i) => ({ ...e, role: i === 0 ? 'MAIN' : 'EXTRA' }));
  item.provenance.example_source = item.examples[0]?.source ?? null;
  for (const e of before) if (gone.has(e.ja)) log.dropped.push({ id: item.id, ja: e.ja, vi: e.vi ?? null, source: e.source });
  item.provenance.fixed_fields = [...new Set([...(item.provenance.fixed_fields ?? []), 'examples'])].sort();
  log.conflicts.push({ kind: 'REVIEW_FIX', id: item.id, field: 'examples', chosen: 'AI',
    values: [{ source: beforeSource, value: before }, { source: 'AI', value: structuredClone(item.examples) }] });
}

export function applyPackets(level, packets) {
  const out = structuredClone(level);
  const log = { conflicts: [], dropped: [], unlinked: new Set() };
  const byId = new Map([...out.vocabulary, ...out.kanji, ...out.grammar].map(x => [x.id, x]));
  for (const m of out.merged_ids ?? []) if (!byId.has(m.id) && byId.has(m.into)) byId.set(m.id, byId.get(m.into));
  const ordered = [...packets.filter(p => !REVIEW_KINDS.has(p.kind)), ...packets.filter(p => REVIEW_KINDS.has(p.kind))];
  for (const p of ordered) for (const { id, answer } of p.items) {
    if (!answer) continue;
    if (REVIEW_KINDS.has(p.kind)) {
      const item = byId.get(id);
      if (item && answer.drop_example) dropExamples(item, answer.drop_example, log);
      // An explicit [] clears an array field (junk alternative readings, wrong confusables); other empties are ignored.
      if (item && answer.fix) for (const f of FIXABLE[p.kind]) if (!empty(answer.fix[f]) || (ARRAY_FIELDS.has(f) && Array.isArray(answer.fix[f])))
        applyFix(item, f, answer.fix[f], log);
      continue;
    }
    if (p.kind === 'RADICAL_NAME') {
      const n = Number(id.slice(2));
      for (const k of out.kanji) if (k.radical?.number === n && !k.radical.name_vi) k.radical.name_vi = answer.name_vi ?? null;
      continue;
    }
    const item = byId.get(id);
    if (!item) continue;
    switch (p.kind) {
      case 'VOCAB_MEANING':
        if (setIfEmpty(item, 'meaning_vi', answer.meaning_vi, v => formatMeaningVi([v]))) item.provenance.meaning_source = 'AI';
        break;
      case 'VOCAB_TYPE': if (setIfEmpty(item, 'word_type_label', answer.word_type_label)) item.provenance.word_type_source = 'AI'; break;
      case 'VOCAB_EXAMPLE': addExamples(item, answer.examples, 3); break;
      case 'VOCAB_MNEMONIC': case 'KANJI_MNEMONIC':
        setIfEmpty(item, 'mnemonic_vi', answer.mnemonic_vi);
        if (Array.isArray(answer.confusable_ids)) item.confusable_ids = [...new Set([...(item.confusable_ids ?? []), ...answer.confusable_ids])];
        break;
      case 'TOPIC_ASSIGN':
        if (item.topic_key === 'khac' && answer.topic_key) { item.topic_key = answer.topic_key; item.topic_vi = answer.topic_vi ?? item.topic_vi; }
        break;
      case 'KANJI_CORE':
        setIfEmpty(item, 'han_viet', answer.han_viet);
        setIfEmpty(item, 'han_viet_alt', answer.han_viet_alt);
        if (setIfEmpty(item, 'meanings_vi', answer.meanings_vi, v => formatMeaningVi([].concat(v)))) item.provenance.meaning_source = 'AI';
        break;
      case 'GRAMMAR_CORE':
        for (const f of isSecondary(item) ? SECONDARY_TEXT : GRAMMAR_TEXT) setIfEmpty(item, f, answer[f]);
        addExamples(item, answer.examples, isSecondary(item) ? SECONDARY_EXAMPLES : 3);
        break;
      case 'GRAMMAR_PARENT': {
        // The answer may name a merged id; it resolves to the item it went into, which must be a MAIN pattern.
        const parent = byId.get(answer.parent_id);
        if (isSecondary(item) && !item.parent_id && parent?.grammar_role === 'MAIN') {
          item.parent_id = parent.id;
          item.provenance.parent_source = 'AI';
        }
        break;
      }
      case 'GRAMMAR_UNIT':
        if (!item.unit_no && answer.unit_no) { item.unit_no = Number(answer.unit_no); item.unit_title_vi ??= answer.unit_title_vi ?? null; }
        break;
      case 'GRAMMAR_BANK':
        for (const [f, n] of Object.entries(BANK)) if ((item[f]?.length ?? 0) < n && Array.isArray(answer[f])) {
          item[f] = answer[f];
          (item.provenance.ai_fields ??= []).push(f);
        }
        break;
    }
  }
  // Confusables are symmetric; a pair a review removed is removed on both sides.
  const unlinked = (a, b) => log.unlinked.has(`${a}|${b}`) || log.unlinked.has(`${b}|${a}`);
  for (const list of [out.vocabulary, out.kanji]) for (const x of list) x.confusable_ids = (x.confusable_ids ?? []).filter(o => !unlinked(x.id, o));
  for (const list of [out.vocabulary, out.kanji]) for (const x of list) for (const other of x.confusable_ids ?? []) {
    const o = byId.get(other);
    if (o && !unlinked(x.id, o.id) && !(o.confusable_ids ?? []).includes(x.id)) o.confusable_ids = [...(o.confusable_ids ?? []), x.id];
  }
  if (log.conflicts.length) out.conflicts = [...(out.conflicts ?? []), ...log.conflicts];
  if (log.dropped.length) out.dropped_examples = [...(out.dropped_examples ?? []), ...log.dropped];
  return out;
}

export const authoringDir = level => path.join(ROOT, 'data/learning-path/authoring', level);

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  const argv = process.argv.slice(2);
  const level = argValue(argv, '--level', 'N5');
  const { loadCompileInputs, compileFromInputs } = await import('./inputs.mjs');
  const dir = authoringDir(level);
  const existing = readPackets(dir);
  // Gaps are what is still empty after every packet already written (an alias may have filled an item).
  const compiled = applyPackets(compileFromInputs({ ...loadCompileInputs(), corrections: globalCorrections() }, level), existing);
  if (argv.includes('--emit')) {
    const kinds = argValue(argv, '--kinds')?.split(',').filter(Boolean) ?? null;
    const written = emitPackets(compiled, dir, { size: 40, sizeByKind: DEFAULT_SIZE_BY_KIND, kinds });
    const counts = {};
    for (const id of written) { const k = id.slice(0, -4); counts[k] = (counts[k] ?? 0) + 1; }
    console.log(`${level}: ${written.length} new packets`, JSON.stringify(counts));
  }
  if (argv.includes('--apply')) {
    const packets = readPackets(dir);
    const answered = packets.flatMap(p => p.items.filter(i => i.answer).map(() => p.kind));
    const counts = {};
    for (const k of answered) counts[k] = (counts[k] ?? 0) + 1;
    console.log(`${level}: ${answered.length} answered items`, JSON.stringify(counts));
  }
}
