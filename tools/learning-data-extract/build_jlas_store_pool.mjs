// [JLAS-W0108] §2.32 T6 (owner 2026-10-27): the Learning store pool section — passages placed in no chapter, offered
// only as per-learner extra practice ("Luyện thêm"), never a required part. Codes LP-OWNED-JLAS-{L}-POOL-READ-P{k}-Q{nnn},
// labels measured against the level's chapter passages (poolLabels), review DRAFT, origin JLAS_SUPPLEMENT.
//   node tools/learning-data-extract/build_jlas_store_pool.mjs . [--check|--dry-run]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLearningContent, writeLearningSection } from './learning_content_store.mjs';
import { CHAPTERS, poolLabels, readyComprehension } from './build_jlas_compiled_books.mjs';
import { N3_SOURCE_PASSAGES, N4_JLAS_PASSAGES } from './jlas_store_pool_content.mjs';

export const SECTION = 'jlas_store_pool';
export const LABEL = 'Nội dung bổ sung J-LAS · cần rà soát';
const norm = (value) => String(value ?? '').normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase();
const pad3 = (n) => String(n).padStart(3, '0');

function validatePassage(p, level) {
  const where = `${level} pool passage "${p.title}"`;
  if (!Number.isInteger(p.chapter_hint) || p.chapter_hint < 1 || p.chapter_hint > CHAPTERS[level]) throw new Error(`${where}: chapter hint`);
  if (!p.text_lines?.length || p.text_lines.some((line) => !String(line).trim())) throw new Error(`${where}: text`);
  if (!p.questions?.length) throw new Error(`${where}: no question`);
  for (const q of p.questions) {
    const options = q.options ?? [];
    if (!String(q.prompt ?? '').trim() || options.length < 3 || options.length > 4 || options.some((o) => !String(o ?? '').trim())
        || new Set(options.map(norm)).size !== options.length || !Number.isInteger(q.correct_option)
        || q.correct_option < 1 || q.correct_option > options.length || !String(q.explanation ?? '').trim()) {
      throw new Error(`${where}: invalid question "${q.prompt}"`);
    }
  }
  for (const line of p.passage_translation ?? []) {
    if (!String(line.ja ?? '').trim() || !String(line.vi ?? '').trim()) throw new Error(`${where}: translation line`);
  }
}

/** Pure: the pool section from the authored passages, labelled against {@code data}'s compiled books and chapter passages. */
export function buildStorePool(data) {
  const peers = readyComprehension(data).reading;
  const passages = [];
  const levels = { N3: N3_SOURCE_PASSAGES, N4: N4_JLAS_PASSAGES };
  for (const [level, authored] of Object.entries(levels)) {
    const book = data.sections[`jlas_compiled_book_${level.toLowerCase()}`];
    if (!book?.vocabulary || !book?.grammar) throw new Error(`Compiled book ${level} is missing.`);
    const levelPeers = peers.filter((x) => x.level === level);
    authored.forEach((p, index) => {
      validatePassage(p, level);
      const group = `LP-OWNED-JLAS-${level}-POOL-READ-P${index + 1}`;
      const item = { ...p, origin: 'JLAS_SUPPLEMENT', review: 'DRAFT' };
      passages.push({
        group, level, skill: 'READING', chapter_hint: p.chapter_hint, title: p.title, format: p.format,
        source: p.source ?? null, answers_by: 'JLAS', text_lines: p.text_lines, passage_translation: p.passage_translation ?? [],
        questions: p.questions.map((q, i) => ({ source_code: `${group}-Q${pad3(i + 1)}`, prompt: q.prompt, options: q.options,
          correct_option: q.correct_option, answer_text: q.options[q.correct_option - 1], explanation: q.explanation })),
        labels: poolLabels(item, levelPeers, level, 'READING', book.vocabulary, book.grammar),
      });
    });
  }
  const count = (level) => ({ passages: passages.filter((p) => p.level === level).length,
    questions: passages.filter((p) => p.level === level).reduce((n, p) => n + p.questions.length, 0) });
  return {
    kind: 'LEARNING_STORE_POOL', owner_approval: '§2.32 store design v2 (owner 2026-10-26); T6 load (owner 2026-10-27)',
    review_status: 'CHUA_DUYET', label: LABEL, study_eligible: true, quiz_eligible: false, smart_test_eligible: false,
    required_part_eligible: false,
    rights: 'OWNER_CONFIRMED_TEAM_DISTRIBUTION', distribution_authorization: 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-24',
    note: 'N3: passages copied from the digitization team\'s books (read-only source), questions and keys by J-LAS; '
      + 'N4: written by J-LAS for the 12 slots missing against the Speed Master blueprint. All DRAFT until the owner reviews.',
    counts: { N3: count('N3'), N4: count('N4') },
    passages,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const data = await readLearningContent(root);
  const section = buildStorePool(data);
  if (process.argv.includes('--check')) {
    if (JSON.stringify(data.sections[SECTION]) !== JSON.stringify(section)) throw new Error(`${SECTION} is stale.`);
  } else if (!process.argv.includes('--dry-run')) {
    await writeLearningSection(root, SECTION, section);
  }
  console.log(SECTION, JSON.stringify(section.counts));
}
