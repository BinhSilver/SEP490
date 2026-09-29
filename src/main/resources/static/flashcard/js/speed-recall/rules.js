export const RULES_VERSION = 'reading-v1';
export function normalizeAnswer(value) { return String(value ?? '').normalize('NFKC').trim(); }
export function isKanaAnswer(value) { return /^[\p{Script=Hiragana}\p{Script=Katakana}ー]+$/u.test(normalizeAnswer(value)); }
export function getQuestionTime(streak) { return streak >= 20 ? 3000 : streak >= 10 ? 4000 : streak >= 5 ? 5000 : 6000; }
export function calculateScore({ responseTime, maxTime, streak }) {
  return 100 + Math.floor(50 * Math.max(0, Math.min(1, 1 - responseTime / maxTime))) + Math.min(streak + 1, 20) * 5;
}
export function getQuestionAndAnswer(item, mode = 'KANJI_TO_READING') {
  const pairs = {
    KANJI_TO_READING: [item.front, item.reading],
    KANJI_TO_MEANING: [item.front, item.meaning],
    MEANING_TO_JAPANESE: [item.meaning, item.front],
    KANA_TO_ROMAJI: [item.reading, item.romaji]
  };
  if (!pairs[mode]) throw new Error('Chế độ chưa được hỗ trợ');
  return { question: pairs[mode][0], expectedAnswer: pairs[mode][1], meaning: item.meaning };
}
/** Hán Việt is graded without case, accents or extra spaces: "Đại", "ĐẠI" and "dai" are equal. */
export function foldHanViet(value) {
  return String(value ?? '').normalize('NFKC').normalize('NFD').replace(/\p{M}+/gu, '')
    .replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().replace(/\s+/g, ' ').trim();
}
/** Typed Vietnamese as shown to the learner: accents kept, spaces collapsed. */
export function tidyHanViet(value) { return String(value ?? '').normalize('NFC').replace(/\s+/g, ' ').trim(); }

/** "Cao, đắt" accepts "cao" or "đắt" (same separators as the server). */
const ALTERNATIVES = /[,;/、，；]/;
function looseMatch(input, expected) {
  const value = foldHanViet(input);
  if (!value) return false;
  return value === foldHanViet(expected) || String(expected ?? '').split(ALTERNATIVES).some(part => foldHanViet(part) === value);
}
function exactMatch(input, expected) {
  const value = normalizeAnswer(input);
  return value !== '' && value === normalizeAnswer(expected);
}

/**
 * How one answer type is typed, shown and graded. Server grading uses the same rules.
 * `free` answers are typed as a whole and checked on match / Enter; kana is judged slot by slot.
 */
export const ANSWER_TYPES = {
  HIRAGANA: { free: false, lang: 'ja', display: normalizeAnswer, same: (a, b) => a === b, matches: exactMatch },
  KANJI: { free: true, lang: 'ja', display: normalizeAnswer, matches: exactMatch },
  MEANING: { free: true, lang: 'vi', display: tidyHanViet, matches: looseMatch },
  HAN_VIET: { free: true, lang: 'vi', display: tidyHanViet, matches: looseMatch }
};

export function compareCharacters(input, expected, type = ANSWER_TYPES.HIRAGANA) {
  const actual = Array.from(type.display(input)), target = Array.from(type.display(expected));
  return Array.from({ length: Math.max(actual.length, target.length) }, (_, i) => ({
    char: actual[i] ?? '', expected: target[i] ?? '',
    status: i < actual.length ? (type.same(actual[i], target[i] ?? '') ? 'correct' : 'wrong') : (i === actual.length ? 'active' : 'empty')
  }));
}
