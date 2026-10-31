// [JLAS-W0108] Owner 2026-10-27 (D3-06 rule): J-LAS-written N3 grammar application questions and N3 word examples.
// Drafts (AI_DRAFT) are checked here; an item failing a check is excluded with its reasons (never silently fixed), the rest
// is enabled with the label "J-LAS soạn bổ sung". Writes two learning-content.json sections and the runtime file of
// grammar questions (FILL only — the grammar check has no ordering question yet; ORDER items are kept in the section).
//   node tools/learning-data-extract/build_n3_supplement_content.mjs . <drafts-dir> [--check|--dry-run]
// <drafts-dir> holds grammar-n3-chapters-*.json, vocab-examples-n3-chapters-*.json and, when returned, the fix files
// n3-fix-grammar-done.json / n3-fix-vocab-done.json (same ids; an item marked "unfixable" stays excluded).
import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';
import { fixedWordId, SOURCE_WORD_FIXES } from './build_jlas_compiled_books.mjs';

export const GRAMMAR_SECTION = 'jlas_n3_grammar_application';
export const EXAMPLE_SECTION = 'jlas_n3_vocab_examples';
export const LABEL = 'J-LAS soạn bổ sung';
export const RUNTIME_FILE = 'src/main/resources/learning/grammar-application-supplement.json';
const norm = (s) => String(s ?? '').normalize('NFKC').replace(/\s+/gu, '');
const plain = (e) => String(e ?? '').replace(/[（(].*?[）)]/gu, '').replace(/[～~]/gu, '').replace(/する$/u, '').split(/[・＝、。,]/u)[0].trim();
const HAS_KANJI = /[一-鿿]/u;

/** Structural problems of one grammar draft (empty when usable). */
export function grammarProblems(it, source) {
  const p = [];
  if (!source) return ['UNKNOWN_GRAMMAR'];
  if (!/^LP-GRAMC-N3-C\d{2}-[0-9a-f]{10}-0[12]$/.test(it.id ?? '') || it.grammar_id !== source.grammar_id) p.push('ID');
  for (const f of ['context_vi', 'prompt_ja', 'answer_ja', 'answer_vi', 'explanation_vi']) if (!String(it[f] ?? '').trim()) p.push(`MISSING_${f}`);
  const o = it.options ?? [];
  if (it.type === 'FILL' || it.type === 'MCQ') {
    if (o.length !== 4 || new Set(o.map(norm)).size !== 4 || !(it.correct_index >= 1 && it.correct_index <= 4)) p.push('OPTIONS');
    else if (it.type === 'FILL' && ((String(it.prompt_ja).match(/\[\s*\]/g) || []).length !== 1
        || norm(String(it.prompt_ja).replace(/\[\s*\]/, o[it.correct_index - 1])) !== norm(it.answer_ja))) p.push('FILL_ANSWER');
    else if (it.type === 'MCQ' && norm(o[it.correct_index - 1]) !== norm(it.answer_ja)) p.push('MCQ_ANSWER');
  } else if (it.type === 'ORDER') {
    const ord = it.correct_order ?? [];
    if (o.length < 3 || o.length > 5 || ord.length !== o.length || new Set(ord).size !== o.length
        || norm(ord.map((i) => o[i - 1]).join('')) !== norm(it.answer_ja)) p.push('ORDER_ANSWER');
  } else p.push('TYPE');
  if (source.source_example && norm(it.answer_ja) === norm(String(source.source_example).split('\n')[0])) p.push('COPIES_SOURCE');
  return p;
}

/** Pure: both sections and the runtime grammar file from the drafts, the inputs and the books in {@code data}. */
export function buildSupplement(data, drafts) {
  const n3 = data.sections.jlas_compiled_book_n3;
  const chapterOf = new Map(n3.chapters.flatMap((c) => [...c.grammar, ...c.vocabulary].map((id) => [id, c.number])));
  const grammarById = new Map(n3.grammar.map((g) => [g.id, g]));
  // Every compiled level: a fixed misspelling has a new id (fixedWordId) and may now live at a lower level (砂糖 → N4).
  const wordById = new Map(['n5', 'n4', 'n3', 'n2', 'n1'].flatMap((l) => data.sections[`jlas_compiled_book_${l}`]?.vocabulary ?? [])
    .map((v) => [v.id, v]));
  // A word written with Kanji, not met at N5/N4, taught in a later N3 chapter than the sentence's (owner: never ahead).
  const known = new Set(['n5', 'n4'].flatMap((l) => data.sections[`jlas_compiled_book_${l}`].vocabulary.map((v) => plain(v.expression))));
  const firstChapter = new Map();
  for (const v of n3.vocabulary) {
    const t = plain(v.expression), ch = chapterOf.get(v.id);
    if (!HAS_KANJI.test(t) || [...t].length < 2 || known.has(t) || ch == null) continue;
    if (!firstChapter.has(t) || firstChapter.get(t) > ch) firstChapter.set(t, ch);
  }
  const later = (text, ch) => [...firstChapter].filter(([t, c]) => c > ch && String(text).includes(t)).map(([t, c]) => `${t}@${c}`).sort();

  const fixedGrammar = new Map((drafts.grammarFixes ?? []).map((x) => [x.id, x]));
  const grammarItems = drafts.grammar.map((x) => fixedGrammar.get(x.id) ?? x);
  const items = [], excluded = [], answers = new Map();
  for (const it of grammarItems) {
    const source = grammarById.get(it.grammar_id) && { ...grammarById.get(it.grammar_id), grammar_id: it.grammar_id };
    const reasons = it.unfixable ? ['UNFIXABLE'] : grammarProblems(it, source ? { ...source, source_example: source.example } : null);
    const ch = chapterOf.get(it.grammar_id);
    const ahead = later(it.answer_ja, ch); if (ahead.length) reasons.push(`LATER_WORD:${ahead.join(',')}`);
    const key = norm(it.answer_ja);
    if (answers.has(key) && answers.get(key) !== it.grammar_id) reasons.push(`DUPLICATE_ANSWER:${key}`);
    if (reasons.length) { excluded.push({ id: it.id, reasons }); continue; }
    answers.set(key, it.grammar_id);
    const { source_status, review_status, unfixable, note, fix_reasons, ...kept } = it;
    items.push({ ...kept, pattern: grammarById.get(it.grammar_id).pattern, chapter: ch, origin: 'JLAS_SUPPLEMENT', label: LABEL });
  }
  items.sort((a, b) => a.id.localeCompare(b.id)); excluded.sort((a, b) => a.id.localeCompare(b.id));

  const fixedVocab = new Map((drafts.vocabFixes ?? []).map((x) => [x.vocab_id, x]));
  const examples = {}, vExcluded = [], seen = new Map();
  for (const raw of drafts.vocab) {
    const it = fixedVocab.get(raw.vocab_id) ?? raw;
    const id = fixedWordId(it.vocab_id);
    const word = wordById.get(it.vocab_id) ?? wordById.get(id);
    const reasons = it.unfixable ? ['UNFIXABLE'] : [];
    if (!word) reasons.push('UNKNOWN_VOCAB');
    if (!String(it.example_ja ?? '').trim() || !String(it.example_vi ?? '').trim()) reasons.push('MISSING');
    const shown = SOURCE_WORD_FIXES[it.vocab_id]?.[0] ?? word?.expression;
    // The word may appear as written, in kana (幾つ → いくつ), or conjugated as `form` says ("て形 (かんで)" → かんで, 済まして).
    // Also each "・" alternative and the parenthesised part kept (鼻（水）が出る → 鼻水が出る, 立つ・立てる → 立てる, 為る → する).
    const spellings = [shown, word?.reading].flatMap((s) => String(s ?? '').split('・'))
      .flatMap((s) => [s.replace(/[（(].*?[）)]/gu, ''), s.replace(/[（(）)]/gu, '')]).map((s) => s.replace(/[～~―]/gu, ''));
    const forms = [plain(shown), plain(word?.reading), ...spellings, String(it.form ?? ''),
      /[（(]([^）)]+)[）)]/.exec(String(it.form ?? ''))?.[1] ?? ''].map(norm).filter((f) => f.length >= 1);
    // A fixed misspelling or a whole phrase may legitimately not appear verbatim; only a short plain word must.
    if (word && !SOURCE_WORD_FIXES[it.vocab_id] && [...plain(shown)].length <= 6 && !forms.some((f) => norm(it.example_ja).includes(f))) reasons.push('WORD_NOT_IN_EXAMPLE');
    const key = norm(it.example_ja);
    if (seen.has(key)) reasons.push(`DUPLICATE_EXAMPLE:${seen.get(key)}`);
    if (reasons.length) { vExcluded.push({ id, reasons }); continue; }
    seen.set(key, id);
    examples[id] = { example_ja: it.example_ja, example_vi: it.example_vi, meaning_index: it.meaning_index ?? 0, ...(it.form ? { form: it.form } : {}) };
  }
  vExcluded.sort((a, b) => a.id.localeCompare(b.id));
  const sortedExamples = Object.fromEntries(Object.entries(examples).sort(([a], [b]) => a.localeCompare(b)));
  const common = { origin: 'JLAS_SUPPLEMENT', label: LABEL, owner_decision: 'D3-06 (2026-10-24) + 2026-10-27: enable checked drafts with a label' };
  return {
    grammar: { kind: 'LEARNING_GRAMMAR_APPLICATION', level: 'N3', ...common,
      counts: { items: items.length, fill: items.filter((x) => x.type === 'FILL').length, excluded: excluded.length }, items, excluded },
    examples: { kind: 'LEARNING_VOCABULARY_EXAMPLES', level: 'N3', ...common,
      counts: { items: Object.keys(sortedExamples).length, excluded: vExcluded.length }, items: sortedExamples, excluded: vExcluded },
  };
}

/** The runtime grammar file: the enabled FILL / MCQ items, in ReviewedGrammarApplications' item shape plus the label. */
export function renderRuntime(grammar) {
  const items = grammar.items.filter((x) => x.type === 'FILL' || x.type === 'MCQ').map((x) => ({
    id: x.id, level: x.level, pattern: x.pattern, type: x.type, context_vi: x.context_vi, prompt_ja: x.prompt_ja,
    options: x.options, correct_index: x.correct_index, answer_ja: x.answer_ja, answer_vi: x.answer_vi,
    explanation_vi: x.explanation_vi, label: grammar.label }));
  return `${JSON.stringify({ source: `learning-content.json#sections.${GRAMMAR_SECTION}`, label: grammar.label,
    note: 'J-LAS-written application questions enabled by the owner (D3-06 rule); a check failure excludes an item', items }, null, 1)}\n`;
}

async function readDrafts(dir) {
  const files = await readdir(dir);
  const load = async (f) => JSON.parse(await readFile(path.join(dir, f), 'utf8'));
  const many = async (re) => (await Promise.all(files.filter((f) => re.test(f)).sort().map(load))).flat();
  const optional = async (f) => (files.includes(f) ? load(f) : []);
  return { grammar: await many(/^grammar-n3-chapters-\d\d-\d\d\.json$/), vocab: await many(/^vocab-examples-n3-chapters-\d\d-\d\d\.json$/),
    grammarFixes: await optional('n3-fix-grammar-done.json'), vocabFixes: await optional('n3-fix-vocab-done.json') };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const runtimePath = path.join(root, RUNTIME_FILE);
  if (process.argv.includes('--check')) {
    // The drafts live outside the repository: check the runtime file against the stored section only.
    const current = (await readFile(runtimePath, 'utf8')).replace(/\r\n/g, '\n');
    if (current !== renderRuntime(data.sections[GRAMMAR_SECTION])) throw new Error('grammar-application-supplement.json is stale.');
    console.log('runtime file matches', GRAMMAR_SECTION);
  } else {
    const built = buildSupplement(data, await readDrafts(path.resolve(process.argv[3] ?? 'target/ai-drafts')));
    if (!process.argv.includes('--dry-run')) {
      await writeLearningSection(root, GRAMMAR_SECTION, built.grammar);
      await writeLearningSection(root, EXAMPLE_SECTION, built.examples);
      await writeFile(runtimePath, renderRuntime(built.grammar), 'utf8');
    }
    console.log('grammar', JSON.stringify(built.grammar.counts), '| examples', JSON.stringify(built.examples.counts));
  }
}
