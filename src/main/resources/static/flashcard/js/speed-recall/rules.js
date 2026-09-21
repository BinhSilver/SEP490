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
export function compareCharacters(input, expected) {
  const actual = Array.from(normalizeAnswer(input)), target = Array.from(normalizeAnswer(expected));
  return Array.from({ length: Math.max(actual.length, target.length) }, (_, i) => ({
    char: actual[i] ?? '', expected: target[i] ?? '',
    status: i < actual.length ? (actual[i] === target[i] ? 'correct' : 'wrong') : (i === actual.length ? 'active' : 'empty')
  }));
}
