// Merge every source into one deduplicated item list per JLPT level (spec 4.11).
// Members come from the Mirai lists and the J-LAS path source of every level; the dictionary and VNJPClub only
// supply content and may lower an item's level. Each item lands in exactly one level: the lowest one any source gives.
import { LEVELS, LEVEL_RANK, normalizeJapanese, sha256 } from './util.mjs';
import { formatMeaningVi, grammarFamilyKey, normalizeGrammarPattern, wordTypeLabel } from './normalize.mjs';

const HAS_KANJI = /[一-龯々]/;
const JAPANESE = /[ぁ-ゖァ-ヺ一-龯々]/;
const lowest = levels => levels.filter(Boolean).sort((a, b) => LEVEL_RANK[a] - LEVEL_RANK[b])[0] ?? null;
const senses = text => formatMeaningVi([text]).split('; ').map(s => s.toLocaleLowerCase('vi')).filter(Boolean);

// ---------------------------------------------------------------- romaji
const KANA = {
  あ: 'a', い: 'i', う: 'u', え: 'e', お: 'o', か: 'ka', き: 'ki', く: 'ku', け: 'ke', こ: 'ko', が: 'ga', ぎ: 'gi',
  ぐ: 'gu', げ: 'ge', ご: 'go', さ: 'sa', し: 'shi', す: 'su', せ: 'se', そ: 'so', ざ: 'za', じ: 'ji', ず: 'zu', ぜ: 'ze',
  ぞ: 'zo', た: 'ta', ち: 'chi', つ: 'tsu', て: 'te', と: 'to', だ: 'da', ぢ: 'ji', づ: 'zu', で: 'de', ど: 'do', な: 'na',
  に: 'ni', ぬ: 'nu', ね: 'ne', の: 'no', は: 'ha', ひ: 'hi', ふ: 'fu', へ: 'he', ほ: 'ho', ば: 'ba', び: 'bi', ぶ: 'bu',
  べ: 'be', ぼ: 'bo', ぱ: 'pa', ぴ: 'pi', ぷ: 'pu', ぺ: 'pe', ぽ: 'po', ま: 'ma', み: 'mi', む: 'mu', め: 'me', も: 'mo',
  や: 'ya', ゆ: 'yu', よ: 'yo', ら: 'ra', り: 'ri', る: 'ru', れ: 're', ろ: 'ro', わ: 'wa', を: 'o', ん: 'n', ゔ: 'vu',
  ぁ: 'a', ぃ: 'i', ぅ: 'u', ぇ: 'e', ぉ: 'o'
};
const YOON = { ゃ: 'a', ゅ: 'u', ょ: 'o' };
export function toRomaji(kana) {
  const text = normalizeJapanese(kana);
  let out = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i], next = text[i + 1];
    if (c === 'っ') { const n = KANA[next] ?? ''; out += n.startsWith('ch') ? 't' : (n[0] ?? ''); continue; }
    if (c === 'ー') { out += out.slice(-1); continue; }
    if (next && YOON[next] && KANA[c]) {
      const base = KANA[c];
      out += (/^(shi|chi|ji)$/.test(base) ? base.slice(0, -1) : base.slice(0, -1) + 'y') + YOON[next];
      i++; continue;
    }
    out += KANA[c] ?? c;
  }
  return out;
}

// ---------------------------------------------------------------- vocabulary
function vocabularyCandidates({ frames, jlasLevels, dictionary, vnjpclub }) {
  const vnjpByKey = new Map();
  for (const v of vnjpclub.vocabulary ?? []) {
    const k = `${normalizeJapanese(v.normalized_expression ?? v.tu)}|${normalizeJapanese(v.normalized_reading ?? v.cach_doc)}`;
    if (!vnjpByKey.has(k)) vnjpByKey.set(k, []);
    vnjpByKey.get(k).push(v);
  }
  const dictFor = (ne, nr) => (dictionary.vocabularyByKey.get(`${ne}|${nr}`) ?? [])
    .slice().sort((a, b) => (b.meaning_vi ? 1 : 0) - (a.meaning_vi ? 1 : 0) || (LEVEL_RANK[a.jlpt_level] ?? 9) - (LEVEL_RANK[b.jlpt_level] ?? 9) || a.id - b.id);
  const out = [];
  const add = (c) => {
    c.ne = normalizeJapanese(c.expression); c.nr = normalizeJapanese(c.reading);
    c.dict = dictFor(c.ne, c.nr); c.vnjp = vnjpByKey.get(`${c.ne}|${c.nr}`) ?? [];
    const meaning = c.src === 'JLAS' ? formatMeaningVi(c.meanings ?? [])
      : (c.dict[0]?.meaning_vi ?? c.dict[0]?.glosses_vi?.[0] ?? c.vnjp[0]?.nghia_vi ?? '');
    c.senses = meaning ? senses(meaning) : [];
    out.push(c);
  };
  for (const level of LEVELS) {
    for (const f of frames[level]?.vocabulary ?? []) add({ src: 'MIRAI', level, expression: f.expression, reading: f.reading,
      order: f.order, topic_key: f.topic_key, topic_vi: f.topic_vi, mirai_ref: f.mirai_ref });
    for (const v of jlasLevels[level]?.vocabulary ?? []) add({ src: 'JLAS', level, id: v.id, expression: v.expression,
      reading: v.reading, meanings: v.meanings_vi ?? [], example_ja: v.example_ja, example_vi: v.example_vi,
      jlas_example: v.jlas_example, tier: v.labels?.tier ?? null, position: v.position ?? 1,
      word_types: [v.reviewed_word_type, v.word_type] });
  }
  return out;
}

const overlap = (a, b) => a.some(x => b.some(y => x === y || (x.length > 1 && y.length > 1 && (x.includes(y) || y.includes(x)))));

function groupLexemes(candidates) {
  const parent = candidates.map((_, i) => i);
  const find = i => parent[i] === i ? i : (parent[i] = find(parent[i]));
  const union = (a, b) => { parent[find(a)] = find(b); };
  const byReading = new Map();
  candidates.forEach((c, i) => { if (!byReading.has(c.nr)) byReading.set(c.nr, []); byReading.get(c.nr).push(i); });
  for (const idxs of byReading.values()) {
    for (let x = 0; x < idxs.length; x++) for (let y = x + 1; y < idxs.length; y++) {
      const a = candidates[idxs[x]], b = candidates[idxs[y]];
      const sameSpelling = a.ne === b.ne;
      const kanaPair = !HAS_KANJI.test(a.ne) || !HAS_KANJI.test(b.ne);
      // Different kanji spellings of one reading (橋/箸) are different words; a kana spelling joins the
      // kanji word whose meaning it shares (はし "đũa" → 箸).
      if (sameSpelling || (kanaPair && a.senses.length && b.senses.length && overlap(a.senses, b.senses))) union(idxs[x], idxs[y]);
    }
  }
  const groups = new Map();
  candidates.forEach((c, i) => { const r = find(i); if (!groups.has(r)) groups.set(r, []); groups.get(r).push(c); });
  return [...groups.values()];
}

function parseExampleLines(text) {
  const lines = String(text ?? '').split('\n').map(l => l.trim()).filter(Boolean);
  const pairs = [];
  for (let i = 0; i < lines.length; i++) {
    if (!JAPANESE.test(lines[i])) continue;
    const vi = lines[i + 1] && !JAPANESE.test(lines[i + 1]) ? lines[++i] : null;
    pairs.push({ ja: lines[i - (vi ? 1 : 0)], vi });
  }
  return pairs;
}

const exampleIndexes = new WeakMap();
function vocabularyExampleIndex(vnjpclub) {
  if (!exampleIndexes.has(vnjpclub)) {
    const index = new Map();
    for (const e of vnjpclub.examples ?? []) if (e.loai_cha === 'VOC') {
      if (!index.has(e.ma_cha)) index.set(e.ma_cha, []);
      index.get(e.ma_cha).push(e);
    }
    exampleIndexes.set(vnjpclub, index);
  }
  return exampleIndexes.get(vnjpclub);
}

function vocabularyExamples(members, vnjpclub) {
  const list = [];
  for (const m of members.filter(m => m.src === 'JLAS')) {
    const je = m.jlas_example;
    if (je && (je.ja || je.example_ja)) list.push({ ja: je.ja ?? je.example_ja, ja_furigana: je.furigana ?? null,
      vi: je.vi ?? je.example_vi ?? null, source: 'JLAS_REVIEWED' });
    if (m.example_ja) list.push({ ja: m.example_ja, ja_furigana: null, vi: m.example_vi ?? null, source: 'BOOK' });
  }
  for (const code of new Set(members.flatMap(m => m.vnjp.map(v => v.ma))))
    for (const e of vocabularyExampleIndex(vnjpclub).get(code) ?? [])
      list.push({ ja: e.cau_ja, ja_furigana: e.furigana ?? null, vi: e.dich_vi ?? null, source: 'VNJPCLUB' });
  const seen = new Set();
  return list.filter(e => e.ja && !seen.has(e.ja) && seen.add(e.ja)).slice(0, 3)
    .map((e, i) => ({ ...e, role: i === 0 ? 'MAIN' : 'EXTRA' }));
}

function buildLexeme(members, dictionary) {
  const byLevel = (a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level];
  const jlas = members.filter(m => m.src === 'JLAS').sort((a, b) => byLevel(a, b) || a.id.localeCompare(b.id));
  const mirai = members.filter(m => m.src === 'MIRAI').sort((a, b) => byLevel(a, b) || a.order - b.order);
  const display = mirai[0] ?? jlas.find(m => HAS_KANJI.test(m.ne)) ?? jlas[0];
  const allDict = members.flatMap(m => m.dict);
  const level = lowest([...members.map(m => m.level), ...allDict.map(d => d.jlpt_level)]);
  const key = `${display.ne}|${display.nr}`;
  const id = jlas[0]?.id ?? `JV-${sha256(`LP|${key}`).slice(0, 10)}`;
  const dictEntry = display.dict[0] ?? allDict[0] ?? null;
  const jlasMeaning = formatMeaningVi(jlas.flatMap(m => m.meanings));
  const vnjpMeaning = formatMeaningVi(members.flatMap(m => m.vnjp.map(v => v.nghia_vi)));
  const dictMeaning = formatMeaningVi(dictEntry ? [dictEntry.meaning_vi ?? dictEntry.glosses_vi[0]] : []);
  const [meaning, meaningSource] = jlasMeaning ? [jlasMeaning, 'JLAS'] : vnjpMeaning ? [vnjpMeaning, 'VNJPCLUB']
    : dictMeaning ? [dictMeaning, 'DICTIONARY'] : ['', null];
  const conflicts = [];
  if (jlasMeaning && dictMeaning && !overlap(senses(jlasMeaning), senses(dictMeaning))) conflicts.push({ kind: 'MEANING', id,
    values: [{ source: 'JLAS', value: jlasMeaning }, { source: 'DICTIONARY', value: dictMeaning }], chosen: 'JLAS' });
  const rawTypes = [...jlas.flatMap(m => m.word_types), ...members.flatMap(m => m.vnjp.map(v => v.tu_loai)), dictEntry?.word_type];
  const wordType = rawTypes.map(t => wordTypeLabel(t || null, display.expression, display.reading)).find(Boolean) ?? null;
  const sources = ['MIRAI', 'JLAS', 'VNJPCLUB', 'DICTIONARY'].filter(s =>
    s === 'MIRAI' ? mirai.length : s === 'JLAS' ? jlas.length : s === 'VNJPCLUB' ? members.some(m => m.vnjp.length) : !!dictEntry);
  return { id, merged_ids: jlas.slice(1).map(m => m.id).filter(x => x !== id), key, level, members, mirai, jlas,
    item: { id, level, expression: display.expression, reading: display.reading, normalized_expression: display.ne,
      normalized_reading: display.nr, romaji: toRomaji(display.reading),
      variants: [...new Set(members.map(m => m.expression))].filter(e => e !== display.expression).sort(),
      dictionary_entry_id: dictEntry?.id ?? null, key, topic_key: mirai[0]?.topic_key ?? 'khac',
      topic_vi: mirai[0]?.topic_vi ?? 'Từ thông dụng khác', order: 0, meaning_vi: meaning, word_type_label: wordType,
      examples: [], mnemonic_vi: null, confusable_ids: [], priority: 0, tier: jlas[0]?.tier ?? 'CORE', status: 'DRAFT',
      provenance: { frame: mirai.length ? 'MIRAI' : null, sources, meaning_source: meaningSource, example_source: null } },
    conflicts };
}

// ---------------------------------------------------------------- kanji
const hexOf = ch => ch.codePointAt(0).toString(16).toUpperCase();

function kanjiItems({ frames, jlasLevels, dictionary }, level, vocabPool) {
  const byChar = new Map();
  const take = ch => { if (!byChar.has(ch)) byChar.set(ch, { mirai: [], jlas: [] }); return byChar.get(ch); };
  for (const l of LEVELS) {
    for (const f of frames[l]?.kanji ?? []) take(f.character).mirai.push({ level: l, order: f.order });
    for (const k of jlasLevels[l]?.kanji ?? []) take(k.character).jlas.push({ level: l, ...k });
  }
  const out = [];
  for (const [ch, g] of byChar) {
    const dict = dictionary.kanjiByChar.get(ch) ?? null;
    const lvl = lowest([...g.mirai.map(m => m.level), ...g.jlas.map(j => j.level), dict?.jlpt_level]);
    if (lvl !== level) continue;
    const j = g.jlas.sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level])[0] ?? null;
    const hv = String(j?.han_viet ?? dict?.han_viet ?? '').split(/[,、/;]/).map(s => s.trim()).filter(Boolean);
    const on = (j?.readings_on?.length ? j.readings_on : dict?.readings_on) ?? [];
    const kun = (j?.readings_kun?.length ? j.readings_kun : dict?.readings_kun) ?? [];
    const words = vocabPool.filter(v => v.expression.includes(ch));
    const matchesReading = (r, isOn) => {
      if (isOn) { const kana = normalizeJapanese(r).replace(/[-.]/g, ''); return w => w.normalized_reading.includes(kana); }
      const [stem, okuri = ''] = r.replace(/^-|-$/g, '').split('.');
      return w => w.normalized_reading.includes(stem + okuri) && (!okuri || w.expression.includes(ch + okuri));
    };
    const readings = [...on.map(r => [normalizeJapanese(r), r, true]), ...kun.map(r => [r, r, false])];
    const levelReadings = [], compounds = [];
    for (const [label, raw, isOn] of readings) {
      const w = words.find(matchesReading(raw, isOn));
      if (!w) continue;
      if (!levelReadings.includes(label)) levelReadings.push(label);
      if (!compounds.includes(w.id)) compounds.push(w.id);
    }
    for (const w of words) if (compounds.length < 5 && !compounds.includes(w.id)) compounds.push(w.id);
    const meanings = j?.reviewed_meaning_vi ? [j.reviewed_meaning_vi] : j?.meanings_vi?.length ? j.meanings_vi : [dict?.meaning_vi];
    out.push({ mirai: g.mirai.find(m => m.level === level) ?? null, position: j?.position ?? 1, item: {
      id: `JK-${hexOf(ch)}`, level, character: ch, han_viet: hv[0] ?? null, han_viet_alt: hv.slice(1),
      meanings_vi: formatMeaningVi(meanings), readings_on: on, readings_kun: kun, level_readings: levelReadings,
      stroke_count: j?.stroke_count ?? dict?.stroke_count ?? null, radical: dict?.radical ?? null, frequency: null,
      compound_ids: compounds.slice(0, 5), mnemonic_vi: null, order: 0, status: 'DRAFT',
      provenance: { frame: g.mirai.length ? 'MIRAI' : null,
        sources: ['MIRAI', 'JLAS', 'DICTIONARY'].filter(s => s === 'MIRAI' ? g.mirai.length : s === 'JLAS' ? g.jlas.length : !!dict),
        meaning_source: j?.reviewed_meaning_vi ? 'JLAS_REVIEWED' : j?.meanings_vi?.length ? 'JLAS' : dict?.meaning_vi ? 'DICTIONARY' : null } } });
  }
  return out.sort((a, b) => (a.mirai ? 0 : 1) - (b.mirai ? 0 : 1) || (a.mirai?.order ?? 0) - (b.mirai?.order ?? 0)
    || a.position - b.position || a.item.character.localeCompare(b.item.character))
    .map((x, i) => ({ ...x.item, order: i + 1 }));
}

// ---------------------------------------------------------------- grammar
const wordChars = s => [...s].filter(c => JAPANESE.test(c)).join('');
function commonPrefixLength(a, b) { let n = 0; while (n < a.length && n < b.length && a[n] === b[n]) n++; return n; }

function grammarItems({ frames, jlasLevels }, level) {
  const byFamily = new Map();
  const take = fam => { if (!byFamily.has(fam)) byFamily.set(fam, { mirai: [], jlas: [] }); return byFamily.get(fam); };
  for (const l of LEVELS) {
    for (const f of frames[l]?.grammar ?? []) take(grammarFamilyKey(f.pattern)).mirai.push({ level: l, ...f });
    for (const g of jlasLevels[l]?.grammar ?? []) take(grammarFamilyKey(g.pattern)).jlas.push({ level: l, ...g });
  }
  const families = [];
  for (const [family, g] of byFamily) {
    const lvl = lowest([...g.mirai.map(m => m.level), ...g.jlas.map(j => j.level)]);
    families.push({ family, lvl, ...g });
  }
  const atLevel = families.filter(f => f.lvl === level);
  const out = atLevel.map(f => {
    const j = f.jlas.sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level] || a.id.localeCompare(b.id))[0] ?? null;
    const m = f.mirai.sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level] || a.order - b.order)[0] ?? null;
    const id = j?.id ?? `JG-${sha256(`LP|${f.family}`).slice(0, 10)}`;
    const patterns = [...new Set([...f.jlas, ...f.mirai].map(x => normalizeGrammarPattern(x.pattern)))];
    const pattern = normalizeGrammarPattern(j?.pattern ?? m.pattern);
    const merged = f.jlas.map(x => x.id).filter(x => x !== id).map(x => ({ id: x, into: id }));
    return { family: f.family, merged, mirai: m?.level === level ? m : null, position: j?.position ?? 1, item: {
      id, level, pattern, pattern_variants: patterns.filter(p => p !== pattern), family_key: f.family,
      unit_no: m?.unit_no ?? 0, unit_title_vi: null, order: 0, meaning_vi: j?.meaning?.trim() || null, formation: null,
      usage_vi: j?.note ?? null, nuance_vi: null, common_mistakes_vi: null, related_ids: [],
      examples: parseExampleLines(j?.example).slice(0, 3).map((e, i) => ({ ...e, ja_furigana: null, source: 'BOOK',
        role: i === 0 ? 'MAIN' : 'EXTRA' })),
      check_bank: [], order_items: [], fill_items: [], extra_practice: [],
      source_questions: [...(j?.reviewed_application_questions ?? []), ...(j?.application_questions ?? [])],
      status: 'DRAFT', provenance: { frame: f.mirai.length ? 'MIRAI' : null,
        sources: ['MIRAI', 'JLAS'].filter(s => s === 'MIRAI' ? f.mirai.length : f.jlas.length),
        meaning_source: j?.meaning ? 'JLAS' : null } } };
  });
  for (const a of out) {
    const aw = wordChars(a.family);
    a.item.related_ids = out.filter(b => b !== a && commonPrefixLength(aw, wordChars(b.family)) >= 2)
      .map(b => b.item.id).slice(0, 3);
  }
  const sorted = out.sort((a, b) => (a.mirai ? 0 : 1) - (b.mirai ? 0 : 1) || (a.mirai?.order ?? 0) - (b.mirai?.order ?? 0)
    || a.position - b.position || a.item.pattern.localeCompare(b.item.pattern));
  return { items: sorted.map((x, i) => ({ ...x.item, order: i + 1 })), merged: sorted.flatMap(x => x.merged) };
}

// ---------------------------------------------------------------- entry point
export function compileLevel({ level, frames, jlasLevels, dictionary, vnjpclub }) {
  const candidates = vocabularyCandidates({ frames, jlasLevels, dictionary, vnjpclub });
  const lexemes = groupLexemes(candidates).map(members => buildLexeme(members, dictionary));
  const pool = lexemes.filter(l => LEVEL_RANK[l.level] <= LEVEL_RANK[level]).map(l => l.item);
  const here = lexemes.filter(l => l.level === level);
  const conflicts = here.flatMap(l => l.conflicts);
  const levelMoves = [];
  for (const l of here) for (const from of [...new Set(l.members.map(m => m.level))].filter(x => x !== level))
    levelMoves.push({ id: l.id, from, to: level, reason: 'LOWEST_LEVEL' });
  const unmatched = here.filter(l => !l.item.dictionary_entry_id && !l.jlas.length)
    .map(l => ({ source: 'MIRAI', key: l.key, reason: 'NO_DICTIONARY_MATCH' }));
  const vocabulary = here.map(l => ({ ...l.item, examples: vocabularyExamples(l.members, vnjpclub) }))
    .map(v => ({ ...v, provenance: { ...v.provenance, example_source: v.examples[0]?.source ?? null } }));
  const framedOrder = new Map(here.map(l => [l.id, l.mirai.find(m => m.level === level)?.order ?? null]));
  const jlasPos = new Map(here.map(l => [l.id, l.jlas[0]?.position ?? 1]));
  vocabulary.sort((a, b) => {
    const oa = framedOrder.get(a.id), ob = framedOrder.get(b.id);
    if ((oa == null) !== (ob == null)) return oa == null ? 1 : -1;
    if (oa != null) return oa - ob;
    return (a.tier === 'CORE' ? 0 : 1) - (b.tier === 'CORE' ? 0 : 1) || jlasPos.get(a.id) - jlasPos.get(b.id) || a.key.localeCompare(b.key);
  });
  vocabulary.forEach((v, i) => { v.order = i + 1; v.priority = i + 1; });
  const grammar = grammarItems({ frames, jlasLevels }, level);
  return { level, vocabulary, kanji: kanjiItems({ frames, jlasLevels, dictionary }, level, pool),
    grammar: grammar.items, conflicts, level_moves: levelMoves, unmatched,
    merged_ids: [...here.flatMap(l => l.merged_ids.map(m => ({ id: m, into: l.id }))), ...grammar.merged] };
}
