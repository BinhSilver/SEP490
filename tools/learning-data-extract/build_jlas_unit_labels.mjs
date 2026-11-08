// [JLAS-W0108] F1-1 (owner 2026-10-27): labels for every vocabulary word, Kanji and grammar point of the compiled books
// N5–N3 so a later composer can fit a path to a learner's pace, time and needs. Candidate only (CANDIDATE_NOT_APPLIED):
// no SQL block, no change to the compiled books, chapters or revisions; the owner reviews the counts before any use.
//   node tools/learning-data-extract/build_jlas_unit_labels.mjs . [--check|--dry-run] [--report file.md]
//
// tier       CORE / EXTENDED. Vocabulary: in the level's main course book, or in ≥ 2 source books. Kanji: all CORE (every
//            Kanji of a compiled book carries its own KANJIDIC2 JLPT level). Grammar: all CORE (owner 2026-10-27, until the
//            function-based merge of patterns, B6, makes "the same point in ≥ 2 books" measurable).
// difficulty 1–5 from the item's place in its level (the books' easy → hard order): 1 + floor(position × 5).
// topic      the source topic when the book has one (Sekai), else null.
// requires   grammar: words (≥ 2 characters, this or an earlier chapter) and Kanji of the same level seen in its example;
//            vocabulary: the level's Kanji written in it. Auto-matched, for ordering only.
// minutes    the planner's existing estimates: vocabulary 0.5, Kanji 0.75, grammar 1.0 (30 / 45 / 60 seconds).
import path from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';

export const SECTION = 'jlas_unit_labels';
export const LEVELS = ['N5', 'N4', 'N3'];
export const MAIN_BOOK = { N5: 'Sekai N5', N4: 'Sekai N4', N3: 'Tiếng Nhật N3' };
export const MINUTES = { vocabulary: 0.5, kanji: 0.75, grammar: 1 };
const KANJI = /[一-鿿々]/gu;

export const difficultyOf = (position) => Math.min(5, 1 + Math.floor(Math.max(0, Number(position) || 0) * 5));

export function vocabularyTier(item, level) {
  const books = item.books ?? [];
  return books.includes(MAIN_BOOK[level]) || books.length >= 2 ? 'CORE' : 'EXTENDED';
}

/** Owner 2026-10-27: every grammar point is CORE for now (see the header). */
export function grammarTier() {
  return 'CORE';
}

/** Pure: labels for one compiled book. */
export function labelBook(book) {
  const level = book.level;
  const chapterOf = new Map();
  for (const c of book.chapters) {
    for (const kind of ['vocabulary', 'kanji', 'grammar']) for (const id of c[kind] ?? []) if (!chapterOf.has(id)) chapterOf.set(id, c.number);
  }
  const kanjiByChar = new Map(book.kanji.map((k) => [k.character, k.id]));
  // Words in chapter order, longest first, so "日本語" wins over "日本" when both match.
  const words = book.vocabulary.filter((v) => [...String(v.expression ?? '')].length >= 2 && chapterOf.has(v.id))
    .map((v) => ({ id: v.id, text: String(v.expression).replace(/[（(].*?[）)]/gu, '').replace(/[～~]/gu, ''), chapter: chapterOf.get(v.id) }))
    .filter((w) => [...w.text].length >= 2)
    .sort((a, b) => [...b.text].length - [...a.text].length || a.id.localeCompare(b.id));
  const kanjiIn = (text) => [...new Set(String(text ?? '').match(KANJI) ?? [])].map((ch) => kanjiByChar.get(ch)).filter(Boolean).sort();

  const vocabulary = {}, kanji = {}, grammar = {};
  for (const v of book.vocabulary) {
    vocabulary[v.id] = { chapter: chapterOf.get(v.id) ?? null, tier: vocabularyTier(v, level), difficulty: difficultyOf(v.position),
      topic: v.topic ?? null, requires: kanjiIn(v.expression), minutes: MINUTES.vocabulary };
  }
  for (const k of book.kanji) {
    kanji[k.id] = { chapter: chapterOf.get(k.id) ?? null, tier: 'CORE', difficulty: difficultyOf(k.position), topic: null,
      requires: k.first_word ? [k.first_word] : [], minutes: MINUTES.kanji };
  }
  for (const g of book.grammar) {
    const chapter = chapterOf.get(g.id) ?? null;
    const example = String(g.example ?? '').split('\n')[0];
    let rest = example;
    const used = [];
    for (const w of words) {
      if (chapter != null && w.chapter > chapter) continue;
      if (rest.includes(w.text)) { used.push(w.id); rest = rest.split(w.text).join(' '); }
    }
    grammar[g.id] = { chapter, tier: grammarTier(g, level), difficulty: difficultyOf(g.position), topic: null,
      requires: [...used.sort(), ...kanjiIn(example)], minutes: MINUTES.grammar };
  }
  return { vocabulary, kanji, grammar };
}

function summary(labels, book) {
  const chapters = book.chapters.map((c) => {
    const row = { chapter: c.number };
    let fullMinutes = 0, coreMinutes = 0;
    for (const kind of ['vocabulary', 'kanji', 'grammar']) {
      const items = (c[kind] ?? []).map((id) => labels[kind][id]).filter(Boolean);
      const core = items.filter((x) => x.tier === 'CORE');
      row[kind] = { total: items.length, core: core.length };
      fullMinutes += items.reduce((n, x) => n + x.minutes, 0);
      coreMinutes += core.reduce((n, x) => n + x.minutes, 0);
    }
    row.minutes = { full: Math.round(fullMinutes), core: Math.round(coreMinutes) };
    return row;
  });
  const totals = {};
  for (const kind of ['vocabulary', 'kanji', 'grammar']) {
    const all = Object.values(labels[kind]);
    totals[kind] = { total: all.length, core: all.filter((x) => x.tier === 'CORE').length };
  }
  return { totals, chapters };
}

const plainWord = (expression) => String(expression ?? '').replace(/[（(].*?[）)]/gu, '').replace(/[～~]/gu, '').replace(/する$/u, '');
const HAS_KANJI = /[一-鿿]/u;

/**
 * F1-2 (owner 2026-10-27): what the chapter order guarantees and what it does not. A path studies a chapter's words, then
 * its Kanji, then its grammar, and never cuts a grammar point or passage, so `kanji_before_first_word` must stay 0. A
 * grammar example may still use a word (written with Kanji, not met at a lower level) that a later chapter teaches —
 * listed for content review; ordering cannot fix it.
 */
export function auditBook(book, labels, knownWords) {
  const kanjiBeforeFirstWord = book.kanji.filter((k) => {
    const word = k.first_word && labels.vocabulary[k.first_word];
    return word && word.chapter != null && labels.kanji[k.id].chapter != null && word.chapter > labels.kanji[k.id].chapter;
  }).map((k) => k.id);
  const firstChapter = new Map();
  for (const v of book.vocabulary) {
    const text = plainWord(v.expression), chapter = labels.vocabulary[v.id].chapter;
    if (!HAS_KANJI.test(text) || [...text].length < 2 || knownWords.has(text) || chapter == null) continue;
    if (!firstChapter.has(text) || firstChapter.get(text) > chapter) firstChapter.set(text, chapter);
  }
  const laterWords = [];
  for (const g of book.grammar) {
    const chapter = labels.grammar[g.id].chapter;
    const example = String(g.example ?? '').split('\n')[0];
    const words = [...firstChapter].filter(([text, ch]) => chapter != null && ch > chapter && example.includes(text))
      .map(([word, ch]) => ({ word, chapter: ch })).sort((a, b) => a.chapter - b.chapter || a.word.localeCompare(b.word));
    if (words.length) laterWords.push({ grammar_id: g.id, chapter, example, words });
  }
  return { kanji_before_first_word: kanjiBeforeFirstWord, grammar_examples_using_later_words: laterWords };
}

/** Pure: the whole section from the compiled books in {@code data}. */
export function buildUnitLabels(data) {
  const levels = {};
  const knownWords = new Set();
  for (const level of LEVELS) {
    const book = data.sections[`jlas_compiled_book_${level.toLowerCase()}`];
    if (!book?.chapters || !book.vocabulary || !book.kanji || !book.grammar) throw new Error(`Compiled book ${level} is missing.`);
    const labels = labelBook(book);
    const audit = auditBook(book, labels, knownWords);
    if (audit.kanji_before_first_word.length) {
      throw new Error(`${level}: Kanji taught before their first word: ${audit.kanji_before_first_word.join(', ')}`);
    }
    levels[level] = { summary: summary(labels, book), audit, ...labels };
    book.vocabulary.forEach((v) => knownWords.add(plainWord(v.expression)));
  }
  return {
    kind: 'LEARNING_UNIT_LABELS', status: 'CANDIDATE_NOT_APPLIED',
    owner_request: 'F1 nguồn biên soạn linh hoạt (owner 2026-10-27); tier rule approved by the owner 2026-10-27',
    rules: {
      tier: 'vocabulary: main course book or ≥2 source books; kanji: all CORE (KANJIDIC2 JLPT level); grammar: all CORE until B6 (owner 2026-10-27)',
      main_book: MAIN_BOOK,
      difficulty: '1 + floor(position × 5), position = place in the level (easy → hard)',
      requires: 'grammar: words (≥2 chars, ≤ its chapter) + Kanji in its example; vocabulary: Kanji in it; kanji: its first word',
      minutes: MINUTES,
    },
    levels,
  };
}

/**
 * F1-3 (owner 2026-10-27): the runtime list of EXTENDED words — the {@code content_item} ids the compiled-book seed gives
 * them ({@code BASE[L] + OFFSET.vocabularyItem + slot}, see build_jlas_compiled_books_sql.mjs) — so a path squeezed by its
 * exam date can leave them out of the required part. Sorted for a stable file.
 */
export function renderVocabularyTiers(data, section, { base, offset }) {
  const extended = {};
  for (const level of LEVELS) {
    const book = data.sections[`jlas_compiled_book_${level.toLowerCase()}`];
    const labels = section.levels[level].vocabulary;
    extended[level] = book.vocabulary.filter((v) => labels[v.id]?.tier === 'EXTENDED')
      .map((v) => base[level] + offset + v.slot).sort((a, b) => a - b);
  }
  return `${JSON.stringify({
    kind: 'COMPILED_VOCABULARY_TIERS', source: `learning-content.json#sections.${SECTION}`,
    rule: section.rules.tier, note: 'content_item ids of EXTENDED words; every other word of a compiled chapter is CORE',
    extended,
  }, null, 1)}\n`;
}

export function renderReport(section) {
  const lines = ['# Nhãn CORE / EXTENDED — báo cáo số lượng (F1-1, 27/10/2026)', '',
    `Trạng thái: \`${section.status}\` — chưa dùng cho lộ trình. Luật: ${section.rules.tier}.`, ''];
  for (const [level, data] of Object.entries(section.levels)) {
    const t = data.summary.totals;
    const pct = (x) => `${x.core}/${x.total} (${Math.round((100 * x.core) / Math.max(1, x.total))}%)`;
    lines.push(`## ${level}`, '', `Từ vựng CORE ${pct(t.vocabulary)} · Kanji CORE ${pct(t.kanji)} · Ngữ pháp CORE ${pct(t.grammar)}`, '',
      '| Chương | Từ vựng CORE/tổng | Kanji | Ngữ pháp CORE/tổng | Phút đủ | Phút chỉ CORE |', '|---|---|---|---|---|---|');
    for (const c of data.summary.chapters) {
      lines.push(`| ${c.chapter} | ${c.vocabulary.core}/${c.vocabulary.total} | ${c.kanji.core}/${c.kanji.total} | ${c.grammar.core}/${c.grammar.total} | ${c.minutes.full} | ${c.minutes.core} |`);
    }
    lines.push('');
  }
  lines.push('## Ví dụ ngữ pháp dùng từ chưa học (F1-2)', '',
    'Từ viết bằng Kanji, chưa có ở cấp dưới, mà chương sau mới dạy. Thứ tự chương không sửa được; cần sửa/thay ví dụ hoặc chú nghĩa.', '');
  for (const [level, data] of Object.entries(section.levels)) {
    const list = data.audit.grammar_examples_using_later_words;
    lines.push(`### ${level} — ${list.length}/${data.summary.totals.grammar.total} ví dụ`, '', '| Chương | Ví dụ | Từ chưa học (chương dạy) |', '|---|---|---|');
    for (const x of list) lines.push(`| ${x.chapter} | ${x.example.replaceAll('|', '／')} | ${x.words.map((w) => `${w.word} (${w.chapter})`).join(', ')} |`);
    lines.push('');
  }
  return lines.join('\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const section = buildUnitLabels(data);
  if (process.argv.includes('--check')) {
    if (JSON.stringify(data.sections[SECTION]) !== JSON.stringify(section)) throw new Error(`${SECTION} is stale.`);
  } else if (!process.argv.includes('--dry-run')) {
    await writeLearningSection(root, SECTION, section);
  }
  if (process.argv.includes('--report')) await writeFile(process.argv[process.argv.indexOf('--report') + 1], renderReport(section), 'utf8');
  // F1-3: the runtime EXTENDED-word list ships with the labels; --check also refuses a stale copy.
  const { BASE, OFFSET } = await import('./build_jlas_compiled_books_sql.mjs');
  const tiersPath = path.join(root, 'src/main/resources/learning/compiled-vocabulary-tiers.json');
  const tiers = renderVocabularyTiers(data, section, { base: BASE, offset: OFFSET.vocabularyItem });
  if (process.argv.includes('--check')) {
    const current = await readFile(tiersPath, 'utf8').catch(() => '');
    if (current.replace(/\r\n/g, '\n') !== tiers) throw new Error('compiled-vocabulary-tiers.json is stale.');
  } else if (!process.argv.includes('--dry-run')) {
    await writeFile(tiersPath, tiers, 'utf8');
  }
  for (const [level, d] of Object.entries(section.levels)) console.log(level, JSON.stringify(d.summary.totals));
}
