// [JLAS-W0108] Day 2 V1 / Day 3 V1 (24/10): one canonical catalog of N5–N3 vocabulary, Kanji and grammar gathered from
// every book in the repo (plan §4.1: books are provenance, not product structure). Candidate only — the path generator
// still reads its chapter books; nothing here is runtime data until the owner decides.
//   node tools/learning-data-extract/build_canonical_catalog.mjs . [--check] [--n3-snapshot target/claude-n3-backbone.tsv]
// Identity: vocabulary = (normalized expression, normalized reading) with katakana→hiragana(NFKC), ー and ・ kept (spec §4);
// Kanji = code point; grammar = normalized pattern string. Different grammar strings are never merged here: a merge by
// function needs a reviewer, so they stay separate and are counted.
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';

const SECTION = 'canonical_catalog_n5_n3';
const N3_SNAPSHOT = 'jlas_n3_backbone_snapshot';
const LEVELS = ['N5', 'N4', 'N3'];
const VNJPCLUB = { vocabulary: 'data/vnjpclub/tu_vung.json/tu_vung.json', kanji: 'data/vnjpclub/kanji.json/kanji.json',
  grammar: 'data/vnjpclub/ngu_phap.json/ngu_phap.json' };
const rank = (level) => LEVELS.indexOf(level);
const sha = (value) => createHash('sha256').update(value).digest('hex');
const shortId = (value) => sha(value).slice(0, 10);

/** Spec §4: katakana → hiragana over NFKC; ー (U+30FC) and ・ (U+30FB) stay. */
export function normalizeJa(text) {
  return String(text ?? '').normalize('NFKC').replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60));
}
const vocabKey = (expression, reading) => `${normalizeJa(expression).replace(/\s+/g, '')}|${normalizeJa(reading).replace(/\s+/g, '')}`;
/** Grammar key: the pattern without spacing, placeholders' punctuation and the ～ marks. */
export function grammarKey(pattern) {
  return normalizeJa(pattern).replace(/[\s～〜~…・+＋()（）［］[\]「」、。]/g, '');
}
const KANJI = /[㐀-䶿一-鿿]/gu;

/** N3 chapter book (textbook 4, shared seed) copied read-only from the DB export: V rows = vocabulary, G rows = grammar. */
export function parseN3Snapshot(tsv) {
  const vocabulary = []; const grammar = [];
  for (const line of tsv.split(/\r?\n/)) {
    if (!line) continue;
    const f = line.split('\t');
    if (f[0] === 'V') vocabulary.push({ chapter: Number(f[1]), expression: f[3], reading: f[4], meaning_vi: f[7] });
    else if (f[0] === 'G') grammar.push({ chapter: Number(f[1]), pattern: f[3], meaning_vi: f[4] });
  }
  return { kind: 'JLAS_N3_TEXTBOOK_SNAPSHOT', source: 'jlas.textbook id 4 "Tiếng Nhật N3" (sql/seeds/seed_data_jlas.sql), read-only copy',
    chapter_rule: '(unit order - 1) * 5 + lesson order', counts: { vocabulary: vocabulary.length, grammar: grammar.length },
    vocabulary, grammar };
}

function backbone(data) {
  const out = { vocabulary: [], grammar: [] };
  for (const lesson of data.sections.jlpt_sekai_n5_n4.lessons) {
    const book = `Sekai ${lesson.level}`;
    const chapter = Number(lesson.source_lesson_number);
    for (const word of lesson.content.tu ?? []) {
      out.vocabulary.push({ level: lesson.level, chapter, book, expression: word.kanji || word.kana, reading: word.kana,
        meaning_vi: word.vi, topic: word.nhom ?? null });
    }
    for (const point of lesson.content.mauCau ?? []) {
      out.grammar.push({ level: lesson.level, chapter, book, pattern: point.cong_thuc, meaning_vi: point.ten });
    }
  }
  const n3 = data.sections[N3_SNAPSHOT];
  for (const word of n3?.vocabulary ?? []) out.vocabulary.push({ level: 'N3', book: 'Tiếng Nhật N3', topic: null, ...word });
  for (const point of n3?.grammar ?? []) out.grammar.push({ level: 'N3', book: 'Tiếng Nhật N3', ...point });
  return out;
}

const pushUnique = (list, value) => { if (value && !list.includes(value)) list.push(value); };
const lowest = (a, b) => (a == null ? b : b == null ? a : rank(a) <= rank(b) ? a : b);

export function buildCatalog(data, vnjp, inputs) {
  const core = backbone(data);
  // Vocabulary
  const vocab = new Map();
  const addVocab = (entry, chapterBook) => {
    const key = vocabKey(entry.expression, entry.reading);
    if (!key.replace('|', '')) return;
    let item = vocab.get(key);
    if (!item) {
      item = { id: `VOC-${shortId(key)}`, key, expression: entry.expression, reading: entry.reading, level: null, chapter: null,
        topic: null, meanings_vi: [], sources: [] };
      vocab.set(key, item);
    }
    if (chapterBook && (item.chapter == null || rank(entry.level) < rank(item.level)
        || (entry.level === item.level && entry.chapter < item.chapter.number))) {
      item.level = entry.level; item.chapter = { book: entry.book, number: entry.chapter };
    }
    if (!item.chapter) item.level = lowest(item.level, entry.level);
    item.topic ??= entry.topic ?? null;
    pushUnique(item.meanings_vi, String(entry.meaning_vi ?? '').trim());
    const source = entry.book;
    if (!item.sources.includes(source)) item.sources.push(source);
  };
  for (const word of core.vocabulary) addVocab(word, true);
  for (const row of vnjp.vocabulary) {
    if (!LEVELS.includes(row.cap)) continue;
    addVocab({ level: row.cap, book: row.sach, expression: row.tu, reading: row.cach_doc || row.tu, meaning_vi: row.nghia_vi }, false);
  }
  // Kanji: characters of the catalog's words, with KANJIDIC2 data and VNJPClub Vietnamese meanings.
  const kanjidic = new Map(data.sections.kanji_n5_n1.content.kanji.map((k) => [k.character, k]));
  const vnjpKanji = new Map();
  for (const row of vnjp.kanji) {
    if (!row.kanji) continue;
    // A meaning from any book counts; only N5–N3 books set a level.
    const entry = vnjpKanji.get(row.kanji) ?? { level: null, meanings_vi: [], books: [] };
    if (LEVELS.includes(row.cap)) entry.level = lowest(entry.level, row.cap);
    pushUnique(entry.meanings_vi, String(row.nghia_vi ?? '').trim());
    pushUnique(entry.books, row.sach);
    vnjpKanji.set(row.kanji, entry);
  }
  const kanji = new Map();
  const order = [...vocab.values()].sort((a, b) => (a.chapter ? 0 : 1) - (b.chapter ? 0 : 1)
    || rank(a.level) - rank(b.level) || (a.chapter?.number ?? 0) - (b.chapter?.number ?? 0));
  for (const word of order) {
    for (const character of String(word.expression).match(KANJI) ?? []) {
      if (kanji.has(character)) continue;
      const dic = kanjidic.get(character);
      const vn = vnjpKanji.get(character);
      const level = dic?.jlpt_level && LEVELS.includes(dic.jlpt_level) ? dic.jlpt_level : vn?.level ?? null;
      if (!level) continue; // N2/N1 Kanji inside an N5–N3 word are counted with their own level later, not here
      kanji.set(character, { id: `KAN-${character.codePointAt(0).toString(16).toUpperCase()}`, character, level,
        first_word: word.id, chapter: word.chapter, han_viet: dic?.han_viet ?? null,
        readings_on: (dic?.readings_on ?? []).map((r) => r.text), readings_kun: (dic?.readings_kun ?? []).map((r) => r.text),
        meanings_en: dic?.meanings_en ?? [], meanings_vi: vn?.meanings_vi ?? [], sources: vn?.books ?? [] });
    }
  }
  // Grammar
  const grammar = new Map();
  const addGrammar = (entry, chapterBook) => {
    const key = grammarKey(entry.pattern);
    if (!key) return;
    const id = `GRA-${shortId(key)}`;
    let item = grammar.get(key);
    if (!item) {
      item = { id, key, pattern: entry.pattern, level: null, chapter: null, meanings_vi: [], sources: [] };
      grammar.set(key, item);
    }
    if (chapterBook && (item.chapter == null || rank(entry.level) < rank(item.level))) {
      item.level = entry.level; item.chapter = { book: entry.book, number: entry.chapter };
    }
    if (!item.chapter) item.level = lowest(item.level, entry.level);
    pushUnique(item.meanings_vi, String(entry.meaning_vi ?? '').trim());
    if (!item.sources.includes(entry.book)) item.sources.push(entry.book);
  };
  for (const point of core.grammar) addGrammar(point, true);
  for (const row of vnjp.grammar) {
    if (!LEVELS.includes(row.cap)) continue;
    addGrammar({ level: row.cap, book: row.sach, pattern: row.mau || row.cau_truc, meaning_vi: row.nghia_vi }, false);
  }

  const vocabulary = [...vocab.values()].sort((a, b) => rank(a.level) - rank(b.level) || (a.chapter ? 0 : 1) - (b.chapter ? 0 : 1)
    || (a.chapter?.number ?? 0) - (b.chapter?.number ?? 0) || a.key.localeCompare(b.key));
  const kanjiList = [...kanji.values()].sort((a, b) => rank(a.level) - rank(b.level) || a.id.localeCompare(b.id));
  const grammarList = [...grammar.values()].sort((a, b) => rank(a.level) - rank(b.level) || (a.chapter ? 0 : 1) - (b.chapter ? 0 : 1)
    || (a.chapter?.number ?? 0) - (b.chapter?.number ?? 0) || a.key.localeCompare(b.key));
  const rawSum = (rows, level) => rows.filter((r) => r.level === level).length;
  const summary = Object.fromEntries(LEVELS.map((level) => {
    const v = vocabulary.filter((w) => w.level === level);
    const g = grammarList.filter((p) => p.level === level);
    return [level, {
      vocabulary: { distinct: v.length, in_chapter_book: v.filter((w) => w.chapter).length,
        other_books_only: v.filter((w) => !w.chapter).length, several_books: v.filter((w) => w.sources.length > 1).length,
        raw_rows: rawSum(core.vocabulary, level) + vnjp.vocabulary.filter((r) => r.cap === level).length },
      kanji: { distinct: kanjiList.filter((k) => k.level === level).length,
        in_chapter_book: kanjiList.filter((k) => k.level === level && k.chapter).length,
        with_vietnamese_meaning: kanjiList.filter((k) => k.level === level && k.meanings_vi.length).length },
      grammar: { distinct_patterns: g.length, in_chapter_book: g.filter((p) => p.chapter).length,
        same_pattern_in_several_books: g.filter((p) => p.sources.length > 1).length,
        raw_rows: rawSum(core.grammar, level) + vnjp.grammar.filter((r) => r.cap === level).length,
        note: 'Different strings for one function (e.g. V-たばかり / ～たばかり) are not merged: function merges need review.' },
    }];
  }));
  return {
    kind: 'LEARNING_CANONICAL_CATALOG_N5_N3', status: 'CANDIDATE_NOT_APPLIED',
    note: 'Plan §4.1 / Day 2 V1 / Day 3 V1. The path generator still reads Sekai N5/N4 and "Tiếng Nhật N3" chapters; '
      + 'this catalog is the crosswalk of every book onto canonical IDs, for review and coverage. No item is reviewed.',
    rules: { vocabulary: 'katakana→hiragana(NFKC(expression|reading)), spaces removed, ー ・ kept',
      kanji: 'code point; level = KANJIDIC2 JLPT level, else lowest VNJPClub level', grammar: 'normalized pattern string',
      level: 'chapter book level when the item is in a chapter book, else the lowest source level',
      chapter: 'first chapter of the chapter book (Sekai N5 1–25, Sekai N4 26–50 — the Minna numbering — and Tiếng Nhật N3 1–27)' },
    inputs, summary, vocabulary, kanji: kanjiList, grammar: grammarList,
  };
}

async function loadVnjp(root) {
  const files = {};
  const inputs = {};
  for (const [name, file] of Object.entries(VNJPCLUB)) {
    const text = await readFile(path.join(root, file), 'utf8');
    files[name] = JSON.parse(text);
    inputs[file] = sha(text.replace(/\r\n/g, '\n'));
  }
  return { files, inputs };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const at = process.argv.indexOf('--n3-snapshot');
  if (at > 0) {
    const snapshot = parseN3Snapshot(await readFile(path.resolve(process.argv[at + 1]), 'utf8'));
    await writeLearningSection(root, N3_SNAPSHOT, snapshot);
    data.sections[N3_SNAPSHOT] = snapshot;
    console.log(`N3 snapshot: ${snapshot.counts.vocabulary} words, ${snapshot.counts.grammar} grammar points`);
  }
  const fresh = await readLearningContent(root);
  const { files, inputs } = await loadVnjp(root);
  for (const name of ['jlpt_sekai_n5_n4', N3_SNAPSHOT, 'kanji_n5_n1']) inputs[`learning-content.json#sections.${name}`] = fresh.section_sha256[name];
  const catalog = buildCatalog(fresh, files, inputs);
  if (process.argv.includes('--check')) {
    if (JSON.stringify(fresh.sections[SECTION]) !== JSON.stringify(catalog)) throw new Error(`${SECTION} is stale.`);
  } else {
    await writeLearningSection(root, SECTION, catalog);
  }
  console.log(JSON.stringify(catalog.summary, null, 1));
}
