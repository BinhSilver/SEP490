// [JLAS-W0108] F1-2 follow-up (owner 2026-10-27/28): grammar examples that used a word a later chapter teaches were
// rewritten by an AI drafter (target/ai-drafts/grammar-examples-rewrite-done.json, outside the repository). This tool
// keeps a rewrite only when the same audit that found the problem (build_jlas_unit_labels.auditBook) passes on it:
//   1. a rewrite identical to the answer of that grammar point's own application question is dropped (it shows the answer);
//   2. a word the audit still flags is written in kana (its reading in the compiled book), then the audit runs again;
//   3. anything still flagged, empty or unknown is dropped with a reason.
// The kept examples go to section `jlas_grammar_example_rewrites`; build_jlas_compiled_books.mjs puts them in place of the
// source example with the J-LAS label. Usage:
//   node tools/learning-data-extract/build_grammar_example_rewrites.mjs . [drafts-file] [--check|--dry-run]
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';
import { buildUnitLabels } from './build_jlas_unit_labels.mjs';

export const SECTION = 'jlas_grammar_example_rewrites';
export const LABEL = 'J-LAS soạn bổ sung';
const DEFAULT_DRAFTS = 'target/ai-drafts/grammar-examples-rewrite-done.json';
const plainWord = (x) => String(x ?? '').replace(/[（(].*?[）)]/gu, '').replace(/[～~]/gu, '').replace(/する$/u, '');
const compact = (x) => String(x ?? '').replace(/[\s。、「」！？!?.,]/gu, '');

const bookOf = (data, level) => data.sections[`jlas_compiled_book_${String(level).toLowerCase()}`];

/** Words the audit flags in each rewritten example, with every rewrite put in place in a copy of the books. */
function flagged(data, examples) {
  const copy = { ...data, sections: { ...data.sections } };
  for (const level of ['n5', 'n4', 'n3']) {
    const book = data.sections[`jlas_compiled_book_${level}`];
    copy.sections[`jlas_compiled_book_${level}`] = { ...book, grammar: book.grammar.map((g) =>
      examples.has(g.id) ? { ...g, example: `${examples.get(g.id).ja}\n${examples.get(g.id).vi}` } : g) };
  }
  const result = new Map();
  for (const level of Object.values(buildUnitLabels(copy).levels)) {
    for (const x of level.audit.grammar_examples_using_later_words) {
      if (examples.has(x.grammar_id)) result.set(x.grammar_id, x.words.map((w) => w.word));
    }
  }
  return result;
}

/** Pure: the section from the drafts and the canonical content. */
export function buildRewrites(data, drafts) {
  const answers = new Map();
  for (const q of Object.values(data.sections.jlas_n3_grammar_application?.items ?? {})) {
    if (q?.grammar_id && q.answer_ja) (answers.get(q.grammar_id) ?? answers.set(q.grammar_id, new Set()).get(q.grammar_id)).add(compact(q.answer_ja));
  }
  const readings = new Map();
  for (const level of ['n5', 'n4', 'n3']) {
    for (const v of bookOf(data, level).vocabulary) {
      const text = plainWord(v.expression), reading = plainWord(v.reading);
      if (text && reading && !readings.has(text)) readings.set(text, reading);
    }
  }
  const excluded = [];
  const examples = new Map();
  for (const d of [...drafts].sort((a, b) => String(a.id).localeCompare(String(b.id)))) {
    const grammar = bookOf(data, d.level)?.grammar.find((g) => g.id === d.id);
    if (!grammar) { excluded.push({ id: d.id, reason: 'NOT_IN_BOOK' }); continue; }
    if (!d.example_ja || !d.example_vi || d.status !== 'DONE') { excluded.push({ id: d.id, reason: 'EMPTY' }); continue; }
    if (answers.get(d.id)?.has(compact(d.example_ja))) { excluded.push({ id: d.id, reason: 'SAME_AS_APPLICATION_ANSWER' }); continue; }
    examples.set(d.id, { level: d.level, ja: String(d.example_ja).trim(), vi: String(d.example_vi).trim(), kana: [] });
  }
  for (const [id, words] of flagged(data, examples)) {
    const item = examples.get(id);
    for (const word of words) {
      const reading = readings.get(word);
      if (!reading) continue;
      item.ja = item.ja.split(word).join(reading);
      item.kana.push(`${word}→${reading}`);
    }
  }
  for (const [id, words] of flagged(data, examples)) {
    excluded.push({ id, reason: `LATER_WORDS:${words.join(',')}` });
    examples.delete(id);
  }
  const items = {};
  for (const [id, x] of [...examples].sort(([a], [b]) => a.localeCompare(b))) {
    items[id] = { level: x.level, example_ja: x.ja, example_vi: x.vi, ...(x.kana.length ? { kana_fixed: x.kana } : {}) };
  }
  const byLevel = {};
  for (const x of Object.values(items)) byLevel[x.level] = (byLevel[x.level] ?? 0) + 1;
  return {
    kind: 'JLAS_GRAMMAR_EXAMPLE_REWRITES', label: LABEL, review_status: 'DRAFT', origin: 'JLAS_SUPPLEMENT',
    note: 'Replaces a source grammar example that used a word taught in a later chapter. Kept only when the unit-label audit passes.',
    counts: { drafts: drafts.length, kept: Object.keys(items).length, kana_fixed: Object.values(items).filter((x) => x.kana_fixed).length,
      excluded: excluded.length, by_level: byLevel },
    items, excluded: excluded.sort((a, b) => a.id.localeCompare(b.id)),
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const draftsFile = path.resolve(root, process.argv.slice(3).find((a) => !a.startsWith('--')) ?? DEFAULT_DRAFTS);
  const data = await readLearningContent(root);
  if (!existsSync(draftsFile)) {
    // The drafts live outside the repository: without them the stored section is taken as is.
    console.log(`${SECTION}: drafts not present, stored section kept (${data.sections[SECTION]?.counts?.kept ?? 0} examples).`);
  } else {
    const built = buildRewrites(data, JSON.parse(await readFile(draftsFile, 'utf8')));
    if (process.argv.includes('--check')) {
      if (JSON.stringify(built) !== JSON.stringify(data.sections[SECTION])) throw new Error(`${SECTION} is stale.`);
      console.log(`${SECTION} is current.`);
    } else {
      if (!process.argv.includes('--dry-run')) await writeLearningSection(root, SECTION, built);
      console.log(JSON.stringify(built.counts));
    }
  }
}
