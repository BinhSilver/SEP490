import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// [JLAS-W0108] Owner 2026-10-24 (D3-09): Claude reviews the word types Learning derives (IPADIC/kuromoji, OD-06) and the owner
// takes the review as approved, so cards drop the "tự gán" label. The review found two kinds of error, both fixed in
// Learning's own copy (the shared `vocabulary_entry.word_type` belongs to Dictionary and is not touched):
//  1. kana-only single words the tokenizer splits into pieces ("れいぞうこ" → phrase), or reads as the wrong class
//     ("くもり" → verb); corrected below by expression;
//  2. raw source codes in `vocabulary_entry.word_type` ("v", "n", "a-na"…) printed as they are; mapped to Vietnamese labels.
// Review scope and method: every compiled-book word (9,070, N5–N3) labelled by `GeneratedWordType`; all 279 kana-only
// "Cụm từ" read one by one, kana-only words of the other classes sampled (1 in 7). Kanji words follow the IPADIC class.
//   node tools/learning-data-extract/build_word_type_reviewed.mjs . [--check]
const OUTPUT = 'src/main/resources/learning/word-type-reviewed.json';

const NOUN = 'Danh từ';
const groups = {
  [NOUN]: 'イヤホン ひるごはん ばんごはん あさごはん いざかや とりにく ぶたにく たまご さしみ みそしる れいぞうこ うしろ '
    + 'ちゅうしゃじょう さいふ おにいさん ひらがな しんごう かど からだ よやく りょかん こうはい おきゃくさん ぶちょう '
    + 'てんきよほう やくそく しりょう けいたい ひげ どろぼう うがい おかわり そうざい おやつ おたま じゃんけん はきけ '
    + 'そでなし よだれ ふたつめ じゅっぷん なっとう くもり できあがり よっぱらい あちらこちら',
  'Đại từ': 'わたしたち',
  'Động từ nhóm II': 'つかれました',
  'Động từ nhóm III': 'べんきょうします うんてんする あんないします オーペンする イライラする がっかりする わくわくする '
    + 'ほっとする さっぱりする のんびりする ぶらぶらする じっとする ちゃんとする',
  'Tính từ đuôi な': 'わがまま じゃま',
  'Phó từ': 'もしかしたら もしかすると まあまあ またっく どうやって そのうち いっしょに ほんとうに',
  'Thán từ': 'ええと おまちどおさま おだいじに おかげさまで おめでとうございます',
  'Cụm từ': 'ざんねんですが おいでください そうだね',
};

/** Raw codes some dictionary rows carry in `word_type`, and the Vietnamese label a card shows for each. */
export const SOURCE_CODES = {
  v: 'Động từ', '(v)': 'Động từ', n: NOUN, suf: 'Hậu tố', 'a-na': 'Tính từ đuôi な', 'a-i': 'Tính từ đuôi い',
  adj: 'Tính từ', PHRASE: 'Cụm từ', 'Động từ nhóm 1': 'Động từ nhóm I', 'Động từ nhóm 2': 'Động từ nhóm II',
  'Động từ nhóm 3': 'Động từ nhóm III',
};

export function buildWordTypeReviewed() {
  const expressions = {};
  for (const [label, words] of Object.entries(groups)) {
    for (const word of words.split(/\s+/).filter(Boolean)) {
      if (word in expressions) throw new Error(`${word} is listed twice`);
      expressions[word] = label;
    }
  }
  return { reviewedBy: 'Claude, owner-approved 2026-10-24 (D3-09)', expressions: sortKeys(expressions), sourceCodes: SOURCE_CODES };
}

function sortKeys(object) {
  return Object.fromEntries(Object.entries(object).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const text = JSON.stringify(buildWordTypeReviewed()) + '\n';
  const target = path.join(root, OUTPUT);
  if (process.argv.includes('--check')) {
    if ((await fs.readFile(target, 'utf8')) !== text) throw new Error(`${OUTPUT} is stale`);
  } else {
    await fs.writeFile(target, text, 'utf8');
  }
  const out = JSON.parse(text);
  console.log(JSON.stringify({ expressions: Object.keys(out.expressions).length, sourceCodes: Object.keys(out.sourceCodes).length }));
}
