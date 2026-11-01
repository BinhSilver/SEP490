// Which readings of a kanji the words of a level use (spec 4.4 "Cách đọc Kanji", 4.11 "Từ ghép trên thẻ Kanji").
// A word is aligned with its reading: kana written in the word are read as written, every other kanji takes its own
// readings (or, failing that, any kana), and the kanji in question must take one of the forms its reading has there.
import { LEVEL_RANK, normalizeJapanese } from './util.mjs';
import { formatMeaningVi } from './normalize.mjs';

const HAS_KANJI = /[一-龯々]/;
const KANA_RUN = /^[ぁ-ゖー]+/;
const VOICED = { か: 'が', き: 'ぎ', く: 'ぐ', け: 'げ', こ: 'ご', さ: 'ざ', し: 'じ', す: 'ず', せ: 'ぜ', そ: 'ぞ', た: 'だ', ち: 'ぢ',
  つ: 'づ', て: 'で', と: 'ど', は: 'ば', ひ: 'び', ふ: 'ぶ', へ: 'べ', ほ: 'ぼ' };
const SEMI_VOICED = { は: 'ぱ', ひ: 'ぴ', ふ: 'ぷ', へ: 'ぺ', ほ: 'ぽ' };
// Modern spelling writes most voiced ち/つ as じ/ず (鼻血 はなぢ but 世界中 せかいじゅう, 手続き てつづき but 稲妻 いなずま).
const VOICED_ALT = { ち: 'じ', つ: 'ず' };
const escape = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// Forms a kana reading takes in a word: rendaku/handakuten when a kanji comes before it (言葉 ことば, 鉛筆 えんぴつ),
// and gemination of a final つ/ち/く/き when the word goes on (雑誌 ざっし, 喫茶店 きっさてん, 一回 いっかい).
export function readingForms(kana, { initial = true, final = true } = {}) {
  const heads = [kana];
  if (!initial) for (const table of [VOICED, SEMI_VOICED, VOICED_ALT]) if (table[kana[0]]) heads.push(table[kana[0]] + kana.slice(1));
  const forms = [];
  for (const h of heads) {
    forms.push(h);
    if (!final && h.length > 1 && /[つちくき]$/.test(h)) forms.push(h.slice(0, -1) + 'っ');
  }
  return [...new Set(forms)];
}

// Can the kana after a kanji (`after`) be its kun okurigana, conjugated? All but the last kana must be written; the
// kana in place of the last one may be anything after an adjective い or an ichidan る (i/e-row kana before it:
// 起きる, 食べる), else it stays in the godan row or takes the て-form sound (遊んで, 働いて, 待って).
const ROWS = ['あいうえおわ', 'かきくけこ', 'がぎぐげご', 'さしすせそ', 'ざじずぜぞ', 'たちつてと', 'だぢづでど', 'なにぬねの',
  'はひふへほ', 'ばびぶべぼ', 'ぱぴぷぺぽ', 'まみむめも', 'やゆよ', 'らりるれろ'];
const TE_SOUND = { く: 'い', ぐ: 'い', う: 'っ', つ: 'っ', る: 'っ', ぬ: 'ん', ぶ: 'ん', む: 'ん' };
const I_E_ROW = /[いきぎしじちぢにひびぴみりえけげせぜてでねへべぺめれ]/;
function conjugates(stem, okuri, after) {
  const head = okuri.slice(0, -1), last = okuri.at(-1);
  if (!after || !after.startsWith(head)) return false;
  const next = after[head.length];
  if (!next || last === 'い' || (last === 'る' && I_E_ROW.test((stem + head).at(-1)))) return true;
  return ROWS.some(row => row.includes(last) && row.includes(next)) || TE_SOUND[last] === next;
}

// How well `word` reads `ch` as `reading` (on: katakana; kun: "stem.okurigana", "-" marks a prefix/suffix).
// A kun reading with okurigana also matches a conjugated word through its stem — 遊びます/遊んで for あそ.ぶ,
// 知りません for し.る, 好き for す.く — when the kana after the kanji keep all but the last kana of the okurigana
// (so 知りません is not し.らせる). Rendaku and gemination apply as in readingForms.
// Returns null or [kind, okurigana kana written out, no affix mark] (higher is better), where kind is
// 3 = read as listed (the kanji takes the reading, or a kun stem with the okurigana written after it), 2 = the
// okurigana is swallowed by the kanji (受付 うけつけ for -う.け, 入口 for -い.り), 1 = only through rendaku/gemination.
// `neighbours(c)` gives the kana forms another kanji of the word may take (null: any kana).
export function readingFit(word, ch, reading, isOn, neighbours = () => null) {
  const expression = normalizeJapanese(word.expression), target = normalizeJapanese(word.normalized_reading ?? word.reading);
  const raw = normalizeJapanese(reading).replace(/^-|-$/g, '');
  const [stem, okuri = ''] = isOn ? [raw.replace(/[-.]/g, ''), ''] : raw.split('.');
  const affix = /^-|-$/.test(reading) ? 0 : 1;
  const chars = [...expression];
  const alt = forms => `(?:${forms.map(escape).join('|')})`;
  const other = c => {
    if (!HAS_KANJI.test(c)) return escape(c);
    const forms = neighbours(c);
    return forms?.length ? alt(forms) : '.+';
  };
  const fits = (i, forms) => forms.length > 0
    && new RegExp('^' + chars.map((c, j) => j === i ? alt(forms) : other(c)).join('') + '$', 'u').test(target);
  let best = null;
  for (let i = 0; i < chars.length; i++) {
    if (chars[i] !== ch) continue;
    const after = chars.slice(i + 1).join('').match(KANA_RUN)?.[0] ?? '';
    const own = okuri ? (conjugates(stem, okuri, after) ? [stem] : []) : [stem];
    const swallowed = okuri ? [stem + okuri] : [];
    const place = { initial: i === 0 || !HAS_KANJI.test(chars[i - 1]), final: i === chars.length - 1 };
    const changed = [...own, ...swallowed].flatMap(g => readingForms(g, place)).filter(f => f !== stem && f !== stem + okuri);
    const kind = fits(i, own) ? 3 : fits(i, swallowed) ? 2 : fits(i, changed) ? 1 : 0;
    if (!kind) continue;
    let written = 0;
    while (kind === 3 && written < okuri.length && after[written] === okuri[written]) written++;
    const fit = [kind, written, affix];
    if (!best || compareFit(fit, best) > 0) best = fit;
  }
  return best;
}
const compareFit = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

// The reading each word uses: the best fit (九つ is ここの.つ, not ここの; 受けます is う.ける, not -う.け), then the
// reading listed first (結婚 けっこん is ケツ, not ケチ). The other kanji of the word first keep their own readings
// (誕生日 is たん·じょう·び, so 生 is ショウ, not -う); a word that only fits when they take any kana is aligned loosely,
// and one that fits no reading at all (昨日 きのう) is left out. Returns [word, reading index] pairs.
export function wordReadings(words, ch, readings, neighbours = () => null) {
  const out = [];
  for (const w of words) {
    let at = -1;
    for (const near of [neighbours, () => null]) {
      let best = null;
      readings.forEach(([, raw, isOn], i) => {
        const fit = readingFit(w, ch, raw, isOn, near);
        if (fit && (!best || compareFit(fit, best) > 0)) { best = fit; at = i; }
      });
      if (at >= 0) break;
    }
    if (at >= 0) out.push([w, at]);
  }
  return out;
}

// Kana forms a kanji with these readings may take inside a word: every reading with its sound changes, and a kun
// verb's stem forms (受付 うけ, 入口 いり, 話し はなし).
const I_ROW = { う: 'い', く: 'き', ぐ: 'ぎ', す: 'し', つ: 'ち', ぬ: 'に', ぶ: 'び', む: 'み', る: 'り' };
export function kanjiForms(on = [], kun = []) {
  const bases = on.map(r => normalizeJapanese(r).replace(/[-.]/g, ''));
  for (const r of kun) {
    const [stem, okuri = ''] = normalizeJapanese(r).replace(/^-|-$/g, '').split('.');
    bases.push(stem);
    if (okuri) bases.push(stem + okuri, stem + okuri.slice(0, -1), stem + okuri.slice(0, -1) + (I_ROW[okuri.at(-1)] ?? ''));
  }
  return [...new Set(bases.filter(Boolean).flatMap(b => readingForms(b, { initial: false, final: false })))];
}

// Core kanji whose readings neither the J-LAS dictionary export nor data/seed_jlas/kanji_data.json (KANJIDIC2) carry.
// Values are the KANJIDIC2 on/kun readings; used only when both lists are empty, so a fixed dictionary takes over.
export const KANJI_READING_OVERRIDES = {
  千: { on: ['セン'], kun: ['ち'] }
};

// Dictionary words for a kanji no level word is written with (九, 七 at N5): published, kana reading, a real word
// (no sentence or list punctuation such as 工場・機械, at most 6 characters), with a Vietnamese meaning; lowest JLPT
// level first (none last), then shorter, then entry id. When some use a reading of the kanji (`teaches`), only those
// are kept, so 七日 なぬか does not stand beside 七 しち. Spec 4.11: "thiếu thì lấy từ thông dụng trong từ điển".
const DICTIONARY_COMPOUND_LIMIT = 3;
const SENTENCE = /[。、，,・！？!?\s]/;
const compoundIndexes = new WeakMap();
function compoundIndex(dictionary) {
  if (!compoundIndexes.has(dictionary)) {
    const index = new Map();
    for (const e of dictionary.entryById?.values() ?? []) {
      const meaning = e.meaning_vi || e.glosses_vi?.[0];
      if (e.status !== 'PUBLISHED' || !meaning || !e.reading || HAS_KANJI.test(e.reading)) continue;
      if (SENTENCE.test(e.expression) || [...e.expression].length > 6) continue;
      for (const c of new Set([...e.expression].filter(c => HAS_KANJI.test(c)))) {
        if (!index.has(c)) index.set(c, []);
        index.get(c).push(e);
      }
    }
    const rank = e => LEVEL_RANK[e.jlpt_level] ?? 9;
    for (const list of index.values())
      list.sort((a, b) => rank(a) - rank(b) || [...a.expression].length - [...b.expression].length || a.id - b.id);
    compoundIndexes.set(dictionary, index);
  }
  return compoundIndexes.get(dictionary);
}

export function dictionaryCompounds(dictionary, ch, teaches = () => true) {
  const seen = new Set(), out = [];
  const list = compoundIndex(dictionary).get(ch) ?? [];
  const fit = new Set(list.filter(e => teaches(e)));
  for (const e of fit.size ? [...fit] : list) {
    const key = `${normalizeJapanese(e.normalized_expression ?? e.expression)}|${normalizeJapanese(e.normalized_reading ?? e.reading)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ dictionary_entry_id: e.id, expression: e.expression, reading: e.reading,
      meaning_vi: formatMeaningVi([e.meaning_vi || e.glosses_vi[0]]), jlpt_level: e.jlpt_level ?? null });
    if (out.length === DICTIONARY_COMPOUND_LIMIT) break;
  }
  return out;
}
