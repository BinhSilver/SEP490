// Merge every source into one deduplicated item list per JLPT level (spec 4.11).
// Members come from the Mirai lists and the J-LAS path source of every level; the dictionary and VNJPClub only
// supply content and may lower an item's level. Each item lands in exactly one level: the lowest one any source gives.
import { LEVELS, LEVEL_RANK, normalizeJapanese, sha256 } from './util.mjs';
import { formatMeaningVi, grammarFamilyKey, normalizeGrammarPattern, wordTypeLabel } from './normalize.mjs';
import { KANJI_READING_OVERRIDES, dictionaryCompounds, kanjiForms, wordReadings } from './kanji-readings.mjs';

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
const sameWords = (x, y) => { const a = new Set(x.split(' ')), b = new Set(y.split(' ')); return a.size === b.size && [...a].every(w => b.has(w)); };
const senseMatch = (x, y) => x === y ? 3 : (x.length > 1 && y.length > 1 && (x.includes(y) || y.includes(x))) || sameWords(x, y) ? 2 : 0;
const entrySenses = e => senses([e.meaning_vi, ...(e.glosses_vi ?? [])].filter(Boolean).join('; '));
const senseScore = (own, entry) => { const es = entrySenses(entry); return own.reduce((s, x) => s + Math.max(0, ...es.map(y => senseMatch(x, y))), 0); };

function vocabularyCandidates({ frames, jlasLevels, dictionary, vnjpclub, miraiMeanings, identity, identityByForm }) {
  const vnjpByKey = new Map();
  for (const v of vnjpclub.vocabulary ?? []) {
    const k = `${normalizeJapanese(v.normalized_expression ?? v.tu)}|${normalizeJapanese(v.normalized_reading ?? v.cach_doc)}`;
    if (!vnjpByKey.has(k)) vnjpByKey.set(k, []);
    vnjpByKey.get(k).push(v);
  }
  const byPreference = (a, b) => (b.meaning_vi ? 1 : 0) - (a.meaning_vi ? 1 : 0) || (LEVEL_RANK[a.jlpt_level] ?? 9) - (LEVEL_RANK[b.jlpt_level] ?? 9) || a.id - b.id;
  const dictFor = (ne, nr) => (dictionary.vocabularyByKey.get(`${ne}|${nr}`) ?? []).slice().sort(byPreference);
  const byReading = nr => (dictionary.vocabularyByReading.get(nr) ?? []).slice().sort(byPreference);
  // The dictionary entry a candidate means. A kanji spelling names its entry; a kana spelling (most Mirai N5 words)
  // only gives the reading, so its own meaning — the J-LAS one, or Mirai's reference meaning, which is used for
  // matching only and never copied — picks the entry among every word read that way. A kana word whose meaning
  // matches no entry gets none (the dictionary has no 一 いち, so Mirai's いち "một" must not become いち "chợ").
  const listedAt = new Map();
  for (const level of LEVELS) for (const v of [...(frames[level]?.vocabulary ?? []), ...(jlasLevels[level]?.vocabulary ?? [])]) {
    const k = normalizeJapanese(v.expression);
    if (!listedAt.has(k)) listedAt.set(k, LEVEL_RANK[level]);
  }
  const listed = e => listedAt.get(normalizeJapanese(e?.expression)) ?? 9;
  const resolve = (c, exact, own) => {
    const kanji = HAS_KANJI.test(c.ne);
    const pool = kanji ? exact : [...new Set([...exact, ...byReading(c.nr)])];
    let best = null, bestScore = 0;
    // Equal scores: the entry whose spelling a list has at the lowest level (かえる "đổi": 変える N4 before 替える N3).
    for (const e of pool) { const s = senseScore(own, e);
      if (s > bestScore || (s === bestScore && s > 0 && listed(e) < listed(best))) { best = e; bestScore = s; } }
    return best ?? (kanji || !own.length ? exact[0] ?? null : null);
  };
  const out = [];
  const add = (c) => {
    c.ne = normalizeJapanese(c.expression); c.nr = normalizeJapanese(c.reading); c.form = spellingForm(c.ne); c.rkey = c.nr;
    const exact = dictFor(c.ne, c.nr);
    const own = senses(c.src === 'JLAS' ? formatMeaningVi(c.meanings ?? []) : formatMeaningVi([miraiMeanings.get(c.mirai_ref)]));
    c.entry = resolve(c, exact, own);
    c.dict = c.entry ? [c.entry, ...(HAS_KANJI.test(c.ne) ? exact.filter(e => e !== c.entry) : [])] : HAS_KANJI.test(c.ne) ? exact : [];
    // VNJPClub is keyed by spelling too, so a kana row may be a homonym (ふる "bỏ" for ふる "rơi"): keep matching rows.
    const rows = vnjpByKey.get(`${c.ne}|${c.nr}`) ?? [];
    const known = [...own, ...(c.entry ? entrySenses(c.entry) : [])];
    c.vnjp = HAS_KANJI.test(c.ne) || !known.length ? rows : rows.filter(v => overlap(senses(formatMeaningVi([v.nghia_vi])), known));
    const meaning = c.src === 'JLAS' ? formatMeaningVi(c.meanings ?? [])
      : (c.dict[0]?.meaning_vi ?? c.dict[0]?.glosses_vi?.[0] ?? c.vnjp[0]?.nghia_vi ?? '');
    c.senses = [...new Set([...own, ...(meaning ? senses(meaning) : [])])];
    out.push(c);
  };
  for (const level of LEVELS) {
    for (const f of frames[level]?.vocabulary ?? []) add({ src: 'MIRAI', level, expression: f.expression, reading: f.reading,
      ...identityByForm.get(`${f.expression}|${f.reading}`), listed: [f.expression, f.reading], order: f.order, topic_key: f.topic_key, topic_vi: f.topic_vi, mirai_ref: f.mirai_ref });
    // A reviewed spelling/reading fix (悪い read "悪い" → わるい) applies before grouping so the word meets its twins.
    for (const v of jlasLevels[level]?.vocabulary ?? []) add({ src: 'JLAS', level, id: v.id, expression: v.expression,
      reading: v.reading, ...identity.get(v.id), meanings: v.meanings_vi ?? [], example_ja: v.example_ja, example_vi: v.example_vi,
      jlas_example: v.jlas_example, tier: v.labels?.tier ?? null, position: v.position ?? 1,
      word_types: [v.reviewed_word_type, v.word_type] });
  }
  return out;
}

const overlap = (a, b) => a.some(x => b.some(y => x === y || (x.length > 1 && y.length > 1 && (x.includes(y) || y.includes(x)))));
const KATAKANA_WORD = /^[ァ-ヺー・]+$/;
const wordsOf = s => s.toLocaleLowerCase('vi').split(/[^\p{L}\p{N}]+/u).filter(Boolean);
const wordSet = list => new Set(list.flatMap(wordsOf));
const unaccented = s => s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd');
// A full sense of one side is a sense of the other: the same words, the same multi-word sense written with other
// accents ("hàng ngày"/"hằng ngày"), or found whole inside it ("thư" in "lá thư").
const within = (x, y) => { const a = wordsOf(x), b = wordsOf(y);
  return a.length > 0 && a.length < b.length && b.some((_, i) => a.every((w, k) => b[i + k] === w)); };
const senseMeets = (x, y) => x === y || sameWords(x, y) || within(x, y) || within(y, x)
  || (wordsOf(x).length >= 2 && unaccented(wordsOf(x).join(' ')) === unaccented(wordsOf(y).join(' ')));
const sharedSense = (a, b) => a.some(x => b.some(y => senseMeets(x, y)));
const sharedWords = (a, b) => { const B = wordSet(b); return [...wordSet(a)].filter(w => B.has(w)).length; };
// Two spellings are one word only on firm wording: a shared full sense, or two shared words (accents count, so "ba"
// never meets "bà"). One shared syllable is not enough — "danh" joined 名刺 "danh thiếp" to 名詞 "danh từ".
export function wordingFits(a, b) { return sharedSense(a, b) || sharedWords(a, b) >= 2; }
// Loose fit (one shared word; accents ignored for 3+ letters, "alo"/"alô"): enough for one spelling read one way
// (今日は/こんにちは listed twice by the dictionary), and for a kana word when only one word of its reading shares any.
const looseFits = (a, b) => {
  const A = [...wordSet(a)].filter(w => w.length >= 2), B = wordSet(b), plain = new Set([...B].filter(w => w.length >= 3).map(unaccented));
  return overlap(a, b) || A.some(w => B.has(w) || (w.length >= 3 && plain.has(unaccented(w))));
};
const sameSpellingFits = looseFits;
const fitScore = (a, b) => (sharedSense(a, b) ? 10 : 0) + sharedWords(a, b);

// Spellings that differ only in okurigana or kana between the same kanji (分る/分かる, 引越し/引っ越し, 曇/曇り) are
// one spelling of the word: the dictionary lists them as separate entries, but same kanji + same reading is one word.
const spellingForm = ne => HAS_KANJI.test(ne) ? [...ne].filter(ch => /[一-龯々]/.test(ch)).join('') : ne;
const fullSenses = c => c.entry ? [...c.senses, ...entrySenses(c.entry)] : c.senses;
const anchoredBy = c => HAS_KANJI.test(c.form);

// A kana spelling whose own meaning picked a kanji dictionary entry names that word (Mirai かく "gãi" → 掻く).
const names = c => !HAS_KANJI.test(c.form) && !!c.entry && HAS_KANJI.test(c.entry.expression ?? '');

// Same reading, then:
// 1. One word for sure: one dictionary entry (かぞく/家族), or one spelling — a katakana spelling is one loanword; one
//    kanji spelling is one word; kana spellings naming different kanji words are two (ふく 服/拭く); a kana spelling
//    with two other entries, or with none, is two words unless their meanings fit (今日は/こんにちは, もしもし
//    "alo"/"alô" do; くれる "cho, tặng"/"lặn" do not); with an entry on one side only the other side must fit it
//    (いち "một" ≠ いち "chợ").
// 2. A group with no kanji spelling joins the ONE group it fits best — never two, so a kana word cannot chain 機会 and
//    機械 into one item. A firm fit (wordingFits) wins, the word of the lowest level first (an N5 kana word is the N5
//    word); a loose one (one shared word) counts only when no other word of that reading shares any. A group that
//    names a kanji word joins another kanji spelling only on a firm fit (こども 子供 → 子ども "trẻ con"; never
//    めいし 名刺 "danh thiếp" → 名詞 "danh từ" through "danh").
// A group never holds two kanji spellings unless they share a dictionary entry, nor a pair a review said differ
// (`barred`); a review's same-word verdict may still join them.
function groupLexemes(candidates, barred = () => false) {
  const parent = candidates.map((_, i) => i);
  const find = i => parent[i] === i ? i : (parent[i] = find(parent[i]));
  const membersOf = candidates.map((_, i) => [i]);
  const clash = (x, y) => barred(x, y) || (anchoredBy(x) && anchoredBy(y) && x.form !== y.form && !(x.entry && x.entry.id === y.entry?.id));
  const union = (a, b) => {
    let ra = find(a), rb = find(b);
    if (ra === rb) return true;
    for (const i of membersOf[ra]) for (const j of membersOf[rb]) if (clash(candidates[i], candidates[j])) return false;
    if (ra < rb) [ra, rb] = [rb, ra];
    parent[ra] = rb; membersOf[rb].push(...membersOf[ra]); membersOf[ra] = [];
    return true;
  };
  const sure = (a, b) => {
    if (a.entry && b.entry && a.entry.id === b.entry.id) return true;
    if (a.form !== b.form) return false;
    if (KATAKANA_WORD.test(a.expression) && a.expression === b.expression) return true;
    if (names(a) || names(b)) return HAS_KANJI.test(a.form);
    if (a.entry && b.entry) return sameSpellingFits(fullSenses(a), fullSenses(b));
    if (HAS_KANJI.test(a.form)) return true;
    if (!a.entry && !b.entry) return !a.senses.length || !b.senses.length || sameSpellingFits(a.senses, b.senses);
    const [has, other] = a.entry ? [a, b] : [b, a];
    return !other.senses.length || sameSpellingFits(other.senses, fullSenses(has));
  };
  const byReading = new Map();
  candidates.forEach((c, i) => { if (!byReading.has(c.rkey)) byReading.set(c.rkey, []); byReading.get(c.rkey).push(i); });
  for (const idxs of byReading.values()) {
    for (let x = 0; x < idxs.length; x++) for (let y = x + 1; y < idxs.length; y++)
      if (sure(candidates[idxs[x]], candidates[idxs[y]])) union(idxs[x], idxs[y]);
    const roots = () => [...new Set(idxs.map(find))].sort((p, q) => p - q);
    const of = r => membersOf[r].map(i => candidates[i]);
    const kanjiGroup = r => of(r).some(anchoredBy);
    const named = r => of(r).some(names);
    const levelOf = r => Math.min(...of(r).map(c => LEVEL_RANK[c.level]));
    for (const pass of ['kanji', 'named', 'kana']) for (const r0 of roots()) {
      const r = find(r0);
      if (kanjiGroup(r) || (named(r) && pass !== 'kanji')) continue;
      const own = [...new Set(of(r).flatMap(c => c.senses))];
      if (!own.length) continue;
      const kind = t => kanjiGroup(t) ? 'kanji' : named(t) ? 'named' : 'kana';
      const targets = roots().filter(t => t !== r && kind(t) === pass).map(t => ({ t, theirs: [...new Set(of(t).flatMap(fullSenses))] }));
      const firm = targets.filter(x => wordingFits(own, x.theirs)).map(x => ({ ...x, level: levelOf(x.t), score: fitScore(own, x.theirs) }))
        .sort((p, q) => p.level - q.level || q.score - p.score || p.t - q.t);
      const loose = named(r) ? [] : targets.filter(x => looseFits(own, x.theirs));
      const best = firm[0]?.t ?? (loose.length === 1 ? loose[0].t : null);
      if (best != null) union(r, best);
    }
  }
  const groups = new Map();
  candidates.forEach((c, i) => { const r = find(i); if (!groups.has(r)) groups.set(r, []); groups.get(r).push(c); });
  return [...groups.values()];
}

// Kanji spellings of different dictionary entries inside one automatically built group (a guard: grouping refuses them).
function mixedEntries(group) {
  const spellings = [...new Map(group.filter(c => anchoredBy(c) && c.entry).map(c => [c.form, { expression: c.expression, entry_id: c.entry.id }])).values()];
  return new Set(spellings.map(s => s.entry_id)).size > 1 ? spellings.sort((a, b) => a.expression.localeCompare(b.expression)) : null;
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

// Every id a group answers to before same-word verdicts join groups: its own id, its J-LAS ids, and the id each Mirai
// word would have alone (as listed and as corrected). Verdicts name groups by these ids; the ids a joined group no longer uses become aliases.
function joinGroups(groups, sameWord) {
  const keys = lexemeKeys(groups);
  const idsOf = groups.map((g, i) => { const { jlas, mirai } = sortedMembers(g);
    return [jlas[0]?.id ?? `JV-${sha256(`LP|${keys[i]}`).slice(0, 10)}`, ...jlas.map(m => m.id),
      ...mirai.flatMap(m => [[m.ne, m.nr], m.listed.map(normalizeJapanese)]).map(([e, r]) => `JV-${sha256(`LP|${e}|${r}`).slice(0, 10)}`)]; });
  const owner = new Map();
  idsOf.forEach((ids, i) => ids.forEach(id => { if (!owner.has(id)) owner.set(id, i); }));
  const parent = groups.map((_, i) => i);
  const find = i => parent[i] === i ? i : (parent[i] = find(parent[i]));
  for (const [a, b] of sameWord) if (owner.has(a) && owner.has(b)) { const [x, y] = [find(owner.get(a)), find(owner.get(b))].sort((p, q) => p - q); parent[y] = x; }
  const joined = new Map();
  groups.forEach((g, i) => { const r = find(i); if (!joined.has(r)) joined.set(r, { members: [], formerIds: [] });
    joined.get(r).members.push(...g); joined.get(r).formerIds.push(...idsOf[i]); });
  return [...joined.values()];
}

function sortedMembers(members) {
  const byLevel = (a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level];
  return { jlas: members.filter(m => m.src === 'JLAS').sort((a, b) => byLevel(a, b) || a.id.localeCompare(b.id)),
    mirai: members.filter(m => m.src === 'MIRAI').sort((a, b) => byLevel(a, b) || a.order - b.order) };
}

// Shown spelling: a kanji spelling from Mirai or J-LAS (the dictionary's usual form, かぞく → 家族), else Mirai's kana.
function displayOf(members) {
  const { jlas, mirai } = sortedMembers(members);
  return mirai.find(m => HAS_KANJI.test(m.ne)) ?? jlas.find(m => HAS_KANJI.test(m.ne)) ?? mirai[0] ?? jlas[0];
}

// Homonyms spelled alike (ふく 服/拭く, かみ 紙/髪) share expression|reading; the first — a J-LAS word, else the earliest
// Mirai one — keeps the plain key (and so the id it always had), the others get "#<dictionary entry id>".
function lexemeKeys(groups) {
  const keys = groups.map(g => { const d = displayOf(g); return `${d.ne}|${d.nr}`; });
  const rank = g => { const { jlas, mirai } = sortedMembers(g);
    return [jlas.length ? 0 : 1, mirai[0] ? LEVEL_RANK[mirai[0].level] : 9, mirai[0]?.order ?? Infinity, g.find(m => m.entry)?.entry.id ?? Infinity]; };
  const compare = (a, b) => { for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1; return 0; };
  const byKey = new Map();
  keys.forEach((k, i) => { if (!byKey.has(k)) byKey.set(k, []); byKey.get(k).push(i); });
  for (const [key, idxs] of byKey) if (idxs.length > 1) {
    idxs.sort((a, b) => compare(rank(groups[a]), rank(groups[b])) || a - b);
    for (const i of idxs.slice(1)) {
      const entry = groups[i].find(m => m.entry)?.entry, m = sortedMembers(groups[i]).mirai[0];
      keys[i] = `${key}#${entry ? entry.id : m ? `${m.level}-${m.order}` : groups[i][0].id}`;
    }
  }
  return keys;
}

function buildLexeme(members, key) {
  const { jlas, mirai } = sortedMembers(members);
  const display = displayOf(members);
  const allDict = members.flatMap(m => m.dict);
  const level = lowest([...members.map(m => m.level), ...allDict.map(d => d.jlpt_level)]);
  const id = jlas[0]?.id ?? `JV-${sha256(`LP|${key}`).slice(0, 10)}`;
  const dictEntry = display.entry ?? members.find(m => m.entry)?.entry ?? display.dict[0] ?? allDict[0] ?? null;
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
      variants: [...new Set([...members.map(m => m.expression), HAS_KANJI.test(dictEntry?.expression ?? '') ? dictEntry.expression : null])]
        .filter(e => e && e !== display.expression).sort(),
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
  // Forms the other kanji of a word may take, from the dictionary readings (or the override table).
  const formsByChar = new Map();
  const neighbours = c => {
    if (!formsByChar.has(c)) {
      const d = dictionary.kanjiByChar.get(c);
      const r = d?.readings_on?.length || d?.readings_kun?.length ? { on: d.readings_on, kun: d.readings_kun } : KANJI_READING_OVERRIDES[c];
      formsByChar.set(c, r ? kanjiForms(r.on, r.kun) : null);
    }
    return formsByChar.get(c);
  };
  const out = [];
  for (const [ch, g] of byChar) {
    const dict = dictionary.kanjiByChar.get(ch) ?? null;
    const lvl = lowest([...g.mirai.map(m => m.level), ...g.jlas.map(j => j.level), dict?.jlpt_level]);
    if (lvl !== level) continue;
    const j = g.jlas.sort((a, b) => LEVEL_RANK[a.level] - LEVEL_RANK[b.level])[0] ?? null;
    const hv = String(j?.han_viet ?? dict?.han_viet ?? '').split(/[,、/;]/).map(s => s.trim()).filter(Boolean);
    let on = (j?.readings_on?.length ? j.readings_on : dict?.readings_on) ?? [];
    let kun = (j?.readings_kun?.length ? j.readings_kun : dict?.readings_kun) ?? [];
    if (!on.length && !kun.length && KANJI_READING_OVERRIDES[ch]) ({ on, kun } = KANJI_READING_OVERRIDES[ch]);
    const words = vocabPool.filter(v => v.expression.includes(ch));
    const readings = [...on.map(r => [normalizeJapanese(r), r, true]), ...kun.map(r => [r, r, false])];
    // No level word is written with the kanji (九, 七 at N5): common dictionary words stand in for compounds and readings.
    const dictWords = words.length ? [] : dictionaryCompounds(dictionary, ch, e => wordReadings([e], ch, readings, neighbours).length > 0);
    const readingWords = words.length ? words : dictWords;
    const levelReadings = [], compounds = [];
    const used = wordReadings(readingWords, ch, readings, neighbours);
    for (const [index, [label]] of readings.entries()) {
      const w = used.find(([, at]) => at === index)?.[0];
      if (!w) continue;
      if (!levelReadings.includes(label)) levelReadings.push(label);
      if (words.length && !compounds.includes(w.id)) compounds.push(w.id);
    }
    for (const w of words) if (compounds.length < 5 && !compounds.includes(w.id)) compounds.push(w.id);
    // Words read only as a whole (昨日 きのう, 果物 くだもの) use no reading of the kanji: the card shows its first reading.
    let readingsSource = levelReadings.length ? (words.length ? 'LEVEL_WORDS' : 'DICTIONARY_WORDS') : null;
    if (!levelReadings.length && readingWords.length && readings.length) { levelReadings.push(readings[0][0]); readingsSource = 'FIRST_READING'; }
    const meanings = j?.reviewed_meaning_vi ? [j.reviewed_meaning_vi] : j?.meanings_vi?.length ? j.meanings_vi : [dict?.meaning_vi];
    out.push({ mirai: g.mirai.find(m => m.level === level) ?? null, position: j?.position ?? 1, item: {
      id: `JK-${hexOf(ch)}`, level, character: ch, han_viet: hv[0] ?? null, han_viet_alt: hv.slice(1),
      meanings_vi: formatMeaningVi(meanings), readings_on: on, readings_kun: kun, level_readings: levelReadings,
      stroke_count: j?.stroke_count ?? dict?.stroke_count ?? null, radical: dict?.radical ?? null, frequency: null,
      compound_ids: compounds.slice(0, 5), dictionary_compounds: dictWords, mnemonic_vi: null, order: 0, status: 'DRAFT',
      provenance: { frame: g.mirai.length ? 'MIRAI' : null, level_readings_source: readingsSource,
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
export function compileLevel({ level, frames, jlasLevels, dictionary, vnjpclub, miraiMeanings = new Map(),
  corrections = {} }) {
  const { identity = new Map(), identityByForm = new Map(), sameWord = [] } = corrections;
  const candidates = vocabularyCandidates({ frames, jlasLevels, dictionary, vnjpclub, miraiMeanings, identity, identityByForm });
  const automatic = groupLexemes(candidates);
  const joined = joinGroups(automatic, sameWord);
  const groups = joined.map(j => j.members);
  const keys = lexemeKeys(groups);
  const lexemes = groups.map((members, i) => buildLexeme(members, keys[i]));
  // An id a word answered to before (a Mirai word that stood alone, a word joined by a same-word verdict) becomes an
  // alias of the word it is now part of, so content written for that id still lands (packets resolve merged ids).
  const lexemeOf = new Map(groups.flatMap((g, i) => g.map(c => [c, lexemes[i]])));
  const mixed = automatic.map(g => [g, mixedEntries(g)]).filter(([, m]) => m)
    .map(([g, spellings]) => ({ id: lexemeOf.get(g[0]).id, level: lexemeOf.get(g[0]).level, spellings }));
  const live = new Set(lexemes.map(l => l.id));
  const aliases = new Map();
  lexemes.forEach((l, i) => { for (const old of joined[i].formerIds)
    if (!live.has(old) && !l.merged_ids.includes(old) && !aliases.has(old)) aliases.set(old, l.id); });
  const pool = lexemes.filter(l => LEVEL_RANK[l.level] <= LEVEL_RANK[level]).map(l => l.item);
  const here = lexemes.filter(l => l.level === level);
  const hereIds = new Set(here.map(l => l.id));
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
  // Same spelling and reading kept as two words (any level): homonyms, or synonyms the fit test missed — for review.
  const base = l => l.key.split('#')[0];
  const bySpelling = new Map();
  for (const l of lexemes) { if (!bySpelling.has(base(l))) bySpelling.set(base(l), []); bySpelling.get(base(l)).push(l); }
  const spellingTwins = here.flatMap(l => bySpelling.get(base(l)).filter(o => o !== l)
    .map(o => ({ id: l.id, twin_id: o.id, twin_level: o.level })))
    .sort((a, b) => a.id.localeCompare(b.id) || a.twin_id.localeCompare(b.twin_id));
  return { level, vocabulary, kanji: kanjiItems({ frames, jlasLevels, dictionary }, level, pool),
    grammar: grammar.items, conflicts, level_moves: levelMoves, unmatched, spelling_twins: spellingTwins,
    mixed_entries: mixed.filter(m => m.level === level).map(({ id, spellings }) => ({ id, spellings })).sort((a, b) => a.id.localeCompare(b.id)),
    merged_ids: [...here.flatMap(l => l.merged_ids.map(m => ({ id: m, into: l.id }))),
      ...[...aliases].filter(([, into]) => hereIds.has(into)).map(([id, into]) => ({ id, into })), ...grammar.merged] };
}
