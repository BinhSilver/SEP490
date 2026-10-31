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
