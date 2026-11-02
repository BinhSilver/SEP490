// Normalizers for the JLPT path source: Vietnamese meaning template, word-type labels, grammar notation.

const ENGLISH_BRACKET = /\(([^()]*)\)/g;
const isEnglishOnly = text => /^[\x20-\x7E]+$/.test(text) && /[A-Za-z]/.test(text);
const isProperNoun = text => {
  const words = text.split(/\s+/).filter(Boolean);
  return words.length >= 2 && words.every(w => /^\p{Lu}/u.test(w));
};

// "Tôi", "tôi (I)", "mình; tớ" → "tôi; mình; tớ": split on ; , /, drop English brackets, lowercase the first
// letter unless every word is capitalised (proper noun), dedupe case-insensitively, keep 3, no final dot.
export function formatMeaningVi(meanings) {
  const parts = [];
  for (const raw of meanings ?? []) {
    if (raw == null) continue;
    for (const piece of String(raw).split(/[;,/]/)) {
      let text = piece.replace(ENGLISH_BRACKET, (m, inner) => isEnglishOnly(inner) ? '' : m)
        .replace(/\s+/g, ' ').trim().replace(/[.。]+$/, '').trim();
      if (!text) continue;
      if (!isProperNoun(text)) text = text.charAt(0).toLocaleLowerCase('vi') + text.slice(1);
      parts.push(text);
    }
  }
  const seen = new Set();
  return parts.filter(p => { const k = p.toLocaleLowerCase('vi'); if (seen.has(k)) return false; seen.add(k); return true; })
    .slice(0, 3).join('; ');
}

const VI_LABELS = new Map([
  ['danh từ', 'danh từ'], ['động từ nhóm i', 'động từ nhóm I'], ['động từ nhóm ii', 'động từ nhóm II'],
  ['động từ nhóm iii', 'động từ nhóm III'], ['tính từ đuôi い', 'tính từ い'], ['tính từ đuôi な', 'tính từ な'],
  ['tính từ い', 'tính từ い'], ['tính từ な', 'tính từ な'], ['phó từ', 'phó từ'], ['trợ từ', 'trợ từ'],
  ['liên từ', 'liên từ'], ['thán từ', 'thán từ'], ['cụm từ', 'cụm từ'], ['số từ', 'số từ'], ['đại từ', 'đại từ']
]);
const CODE_LABELS = new Map([
  ['n', 'danh từ'], ['adv', 'phó từ'], ['prt', 'trợ từ'], ['conj', 'liên từ'], ['int', 'thán từ'], ['exp', 'cụm từ'],
  ['num', 'số từ'], ['pn', 'đại từ'], ['adj-i', 'tính từ い'], ['a-i', 'tính từ い'], ['adi', 'tính từ い'],
  ['adj-na', 'tính từ な'], ['a-na', 'tính từ な'], ['v1', 'động từ nhóm II'], ['vs', 'động từ nhóm III'],
  ['vk', 'động từ nhóm III']
]);

function verbGroupFromReading(reading) {
  const r = String(reading ?? '');
  if (/(する|くる)$/.test(r)) return 'động từ nhóm III';
  if (/[うくぐすつぬぶむ]$/.test(r)) return 'động từ nhóm I';
  return null; // る-verbs are ambiguous (帰る is group I): left for an authoring packet.
}

export function wordTypeLabel(raw, expression, reading) {
  if (raw == null) return null;
  const value = String(raw).trim().replace(/^\((.*)\)$/, '$1');
  const vi = VI_LABELS.get(value.toLocaleLowerCase('vi'));
  if (vi) return vi;
  const code = value.toLowerCase();
  if (CODE_LABELS.has(code)) return CODE_LABELS.get(code);
  if (/^v5/.test(code)) return 'động từ nhóm I';
  if (code === 'v') return verbGroupFromReading(reading);
  return null;
}

export function normalizeGrammarPattern(pattern) {
  return String(pattern ?? '').normalize('NFKC')
    .replace(/[~～]/g, '〜')
    .replace(/V\s*[-ー−]?\s*(て|te)(?=[^ぁ-ゖ]|$)/g, 'V-て')
    .replace(/(イA|い形|i-adj)/g, 'Aい').replace(/(ナA|な形|na-adj)/g, 'Aな')
    .replace(/\s+/g, ' ').trim();
}

const POLITE_TAIL = /\s*(です|ます|だ|である)$/;
const HAS_WORD = /[ぁ-ゖァ-ヺ一-龯]/;

// 〜てもいい and 〜てもいいです share a key; the tail is only stripped when real words remain.
export function grammarFamilyKey(pattern) {
  const normalized = normalizeGrammarPattern(pattern);
  const core = normalized.replace(/^〜+|〜+$/g, '').trim();
  const stripped = core.replace(POLITE_TAIL, '').replace(/^〜+|〜+$/g, '').trim();
  return HAS_WORD.test(stripped) ? stripped : core;
}

// ---------------------------------------------------------------- grammar core keys (N3 MAIN/SECONDARY split)
const JA_CHAR = /[ぁ-ゖァ-ヺー一-龯々]/;
const LATIN = /[A-Za-zÀ-ỹ]/;
// Kana in brackets after kanji is a reading (内(うち)に), except these one-kana particles, which are optional okurigana
// (一方(で), 最中(に)).
const OPTIONAL_KANA = new Set(['に', 'で', 'て', 'は', 'を', 'が', 'の']);
const ALTERNATIVE = /\s*[/／]\s*|・/;
const toHiragana = s => s.replace(/[ァ-ヶ]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x60));
const japaneseOnly = s => toHiragana([...s].filter(c => JA_CHAR.test(c)).join(''));

// The grammar word a pattern teaches, however a book writes it: 内(うち)に, 〜うちに... (A) and
// "V-ている / V-ない / A / N の + うちに" all give うちに. Readings and kanji spellings both count (内に), optional kana
// are kept (に対し(て) → に対して), letter/number sense labels ((A), B, 1) are dropped, a kanji sense label ((原因))
// stays part of the key, the polite tail だ/です is dropped when 3+ characters remain. A formation segment (with Latin
// letters) adds only the verb-form ending all its alternatives share (V-て / A-くて → て). Only the first alternative
// counts, plus — unless `first` — later alternatives that start with its first three characters (おかげだ・おかげで).
export function grammarCoreKeys(pattern, { first = false } = {}) {
  const s = String(pattern ?? '').normalize('NFKC').replace(/[~～]/g, '〜')
    .replace(/・{2,}|。{2,}|\.{2,}|…+/g, '…')
    .replace(/\s*[(（]\s*[A-CＡ-Ｃ]\s*[)）]/g, '').replace(/\s+[A-C1-3](?=$|[・/])/g, '');
  let alternatives;
  if (s.includes('+')) {
    const parts = s.split('+');
    const ending = segment => {
      const ends = segment.split(/\s*\/\s*/).map(f => (f.trim().match(/(?:V|A)-く?(て|たり|た|ば)$/) ?? [])[1] ?? null);
      return ends.every(e => e && e === ends[0]) ? ends[0] : '';
    };
    alternatives = [parts.slice(0, -1).map(p => LATIN.test(p) ? ending(p) : japaneseOnly(p)).join('')
      + parts[parts.length - 1].split(ALTERNATIVE)[0]];
  } else {
    const all = s.split(ALTERNATIVE).filter(x => x.trim());
    const head = japaneseOnly(all[0] ?? '').slice(0, 3);
    alternatives = all.filter((a, i) => i === 0 || (!first && head.length === 3 && japaneseOnly(a).startsWith(head)));
  }
  const keys = new Set();
  const furigana = /([一-龯々]+)[(（]([ぁ-ゖ]+)[)）]/g;
  for (const a of alternatives) {
    const spell = keepKanji => a.replace(furigana, (m, k, r) => OPTIONAL_KANA.has(r) ? k + r : keepKanji ? k : r)
      .replace(/[(（]([ぁ-ゖ]+)[)）]/g, '$1');
    for (const form of [spell(false), spell(true)]) {
      const whole = japaneseOnly(form), bare = whole.replace(/(です|だ)$/, ''), key = bare.length >= 3 ? bare : whole;
      if (key.length >= 2) keys.add(key);
      // の before a kanji word is the formation's (の間(あいだ)に ~ N の + 間に).
      if (/^の[一-龯々]/.test(key) && key.length >= 3) keys.add(key.slice(1));
    }
  }
  return [...keys];
}
