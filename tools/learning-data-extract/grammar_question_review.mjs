// [JLAS-W0108] OD-D2-01 (owner 2026-10-23): the AI-drafted grammar application questions are reviewed by the owner in
// small batches; only questions marked DUYET reach learners.
//   export: node tools/learning-data-extract/grammar_question_review.mjs export . [out.csv]
//           writes the review sheet (UTF-8 CSV with BOM, opens in Excel); fill "quyet_dinh" with DUYET / SUA / BO.
//   apply:  node tools/learning-data-extract/grammar_question_review.mjs apply . sheet.csv
//           records the decisions in the Learning-owned candidates and rebuilds the runtime file.
//   add:    node tools/learning-data-extract/grammar_question_review.mjs add . draft.json [...]
//           validates new AI drafts against their Sekai lesson and adds them as candidates (NEEDS_HUMAN_REVIEW).
//   build:  node tools/learning-data-extract/grammar_question_review.mjs build . [--check]
//           writes src/main/resources/learning/grammar-application-reviewed.json (REVIEWED multiple-choice items only).
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { readLearningContent, readLearningSection, writeLearningSection } from './learning_content_store.mjs';
import { collectJapaneseText, validateQuestions } from './validate_owned_grammar_questions.mjs';

const SECTION = 'grammar_question_candidates';
const RUNTIME = 'src/main/resources/learning/grammar-application-reviewed.json';
const COLUMNS = ['id', 'cap', 'bai', 'mau_cau', 'loai', 'ngu_canh_vi', 'de_ja', 'phuong_an', 'dap_an', 'cau_dung_ja',
  'nghia_vi', 'giai_thich_vi', 'trang_thai', 'quyet_dinh', 'ghi_chu'];
const DECISION = { DUYET: 'REVIEWED', BO: 'REJECTED', SUA: 'NEEDS_HUMAN_REVIEW' };

export function candidateItems(section) {
  return Object.entries(section.files).flatMap(([file, record]) => JSON.parse(record.text).map((item) => ({ file, item })));
}

/** Sekai pattern (`cong_thuc`) for a candidate's lesson + grammar code — the same text the DB grammar point carries. */
export function patternIndex(sekaiSection) {
  const index = new Map();
  for (const lesson of sekaiSection.lessons) {
    const match = /^LP-OWNED-SEKAI-(N[45])-L(\d+)$/.exec(lesson.owned_lesson_code ?? '');
    if (!match) continue;
    for (const point of lesson.content?.mauCau ?? []) {
      index.set(`SEKAI-${match[1]}-${String(Number(match[2])).padStart(2, '0')}|${point.ma}`, point.cong_thuc);
    }
  }
  return index;
}

const csvCell = (value) => {
  const text = value == null ? '' : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
};

export function reviewSheet(section, patterns) {
  const rows = candidateItems(section).map(({ item }) => {
    const options = item.options ?? [];
    const answer = item.type === 'ORDER'
      ? (item.correct_order ?? []).join(' → ') : options[(item.correct_index ?? 0) - 1] ?? '';
    return [item.id, item.level, item.lesson_source_id, patterns.get(`${item.lesson_source_id}|${item.grammar_code}`) ?? item.grammar_code,
      item.type, item.context_vi, item.prompt_ja, options.join(' | '), answer, item.answer_ja, item.answer_vi,
      item.explanation_vi, item.review_status, '', item.review_note ?? ''];
  });
  return '﻿' + [COLUMNS, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

export function parseCsv(text) {
  const rows = [];
  let row = [], cell = '', quoted = false;
  const input = text.replace(/^﻿/, '');
  for (let i = 0; i < input.length; i++) {
    const c = input[i];
    if (quoted) {
      if (c === '"' && input[i + 1] === '"') { cell += '"'; i++; }
      else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && input[i + 1] === '\n') i++;
      row.push(cell); rows.push(row); row = []; cell = '';
    } else cell += c;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  const [header, ...body] = rows.filter((r) => r.some((v) => v !== ''));
  return body.map((r) => Object.fromEntries(header.map((name, i) => [name.trim(), (r[i] ?? '').trim()])));
}

/** Pure: decisions DUYET/SUA/BO become review statuses; blank decisions leave an item as it was. */
export function applyDecisions(section, rows) {
  const byId = new Map(rows.map((row) => [row.id, row]));
  for (const row of rows) {
    const decision = row.quyet_dinh?.toUpperCase();
    if (decision && !DECISION[decision]) throw new Error(`Unknown decision "${row.quyet_dinh}" for ${row.id} (use DUYET, SUA or BO)`);
  }
  const files = Object.fromEntries(Object.entries(section.files).map(([file, record]) => {
    const items = JSON.parse(record.text).map((item) => {
      const row = byId.get(item.id);
      const decision = row?.quyet_dinh?.toUpperCase();
      if (!decision) return item;
      return { ...item, review_status: DECISION[decision], review_note: row.ghi_chu || undefined,
        reviewed_by: 'OWNER', reviewed_on: new Date().toISOString().slice(0, 10) };
    });
    const text = JSON.stringify(items, null, 2) + '\n';
    // The file checksum follows its text (the draft seed generator verifies it).
    return [file, { ...record, text, ...(record.sha256 ? { sha256: createHash('sha256').update(text).digest('hex') } : {}) }];
  }));
  const reviewed = candidateItems({ files }).filter(({ item }) => item.review_status === 'REVIEWED').length;
  return { ...section, files, reviewed_count: reviewed };
}

/** Sekai lesson file a candidate's lesson_source_id names (SEKAI-N5-03 → data/seed_jlas/sekai/sekai-n5/json/bai-03.json). */
export const lessonSourcePath = (lessonSourceId) => {
  const match = /^SEKAI-(N[1-5])-(\d{2})$/.exec(lessonSourceId ?? '');
  return match ? `data/seed_jlas/sekai/sekai-${match[1].toLowerCase()}/json/bai-${match[2]}.json` : null;
};

/**
 * Pure: adds new AI-draft files to the candidates. Every item goes through the same mechanical validator as the first
 * slice against its own lesson (examples, lesson and grammar codes); a file name or question ID already present is refused.
 */
export function addCandidates(section, batches) {
  const ids = new Set(candidateItems(section).map(({ item }) => item.id));
  const files = { ...section.files };
  for (const { file, items, source } of batches) {
    if (files[file]) throw new Error(`Candidate file ${file} already exists`);
    const lessonIds = new Set(items.map((item) => item.lesson_source_id));
    if (lessonIds.size !== 1) throw new Error(`${file}: one lesson per file`);
    const errors = validateQuestions(items, { sourceExamples: collectJapaneseText(source),
      sourceLessonId: [...lessonIds][0], sourceGrammarCodes: (source.mauCau ?? []).map((point) => point.ma) });
    for (const item of items) if (ids.has(item.id)) errors.push(`${item.id}: already a candidate`); else ids.add(item.id);
    if (errors.length) throw new Error(`${file}: ${errors.join('; ')}`);
    const text = JSON.stringify(items, null, 2) + '\n';
    files[file] = { sha256: createHash('sha256').update(text).digest('hex'), text };
  }
  return { ...section, files, question_count: candidateItems({ files }).length };
}

/** Runtime copy: REVIEWED multiple-choice/fill items, keyed by level + the DB grammar pattern. */
export function runtimeItems(section, patterns) {
  return candidateItems(section).map(({ item }) => item)
    .filter((item) => item.review_status === 'REVIEWED' && (item.type === 'MCQ' || item.type === 'FILL'))
    .map((item) => {
      const pattern = patterns.get(`${item.lesson_source_id}|${item.grammar_code}`);
      const options = item.options ?? [];
      if (!pattern || options.length < 2 || options.length > 4 || !(item.correct_index >= 1 && item.correct_index <= options.length)
          || new Set(options.map((o) => o.trim())).size !== options.length) {
        throw new Error(`Reviewed grammar question ${item.id} is not usable (pattern/options/key).`);
      }
      return { id: item.id, level: item.level, pattern, type: item.type, context_vi: item.context_vi, prompt_ja: item.prompt_ja,
        options, correct_index: item.correct_index, answer_ja: item.answer_ja, answer_vi: item.answer_vi,
        explanation_vi: item.explanation_vi };
    });
}

async function build(root, check) {
  const section = await readLearningSection(root, SECTION);
  const patterns = patternIndex(await readLearningSection(root, 'jlpt_sekai_n5_n4'));
  const items = runtimeItems(section, patterns);
  const text = JSON.stringify({ source: 'data/learning-owned/learning-content.json#sections.grammar_question_candidates',
    owner_decision: 'OD-D2-01 2026-10-23', note: 'Only owner-reviewed (DUYET) multiple-choice/fill items.', items }, null, 1) + '\n';
  const target = path.join(root, RUNTIME);
  if (check) {
    const current = await readFile(target, 'utf8').catch(() => '');
    // A Windows checkout may turn LF into CRLF; only the content counts.
    if (current.replace(/\r\n/g, '\n') !== text) throw new Error('Reviewed grammar runtime file is stale.');
  } else {
    await writeFile(target, text, 'utf8');
  }
  return items.length;
}

async function main() {
  const [command, rootArg, fileArg] = process.argv.slice(2);
  const root = path.resolve(rootArg ?? '.');
  if (command === 'export') {
    await readLearningContent(root);
    const section = await readLearningSection(root, SECTION);
    const patterns = patternIndex(await readLearningSection(root, 'jlpt_sekai_n5_n4'));
    const out = path.resolve(fileArg ?? path.join(root, 'docs/learning/duyet-cau-ngu-phap.csv'));
    await writeFile(out, reviewSheet(section, patterns), 'utf8');
    console.log(`review sheet: ${out} (${candidateItems(section).length} questions)`);
  } else if (command === 'apply') {
    if (!fileArg) throw new Error('apply needs the filled review sheet');
    const section = await readLearningSection(root, SECTION);
    const updated = applyDecisions(section, parseCsv(await readFile(path.resolve(fileArg), 'utf8')));
    await writeLearningSection(root, SECTION, updated);
    console.log(`reviewed=${updated.reviewed_count}; runtime items=${await build(root, false)}`);
  } else if (command === 'add') {
    const drafts = process.argv.slice(4).filter((arg) => !arg.startsWith('--'));
    if (!drafts.length) throw new Error('add needs one or more draft JSON files (one lesson per file)');
    const batches = [];
    for (const draft of drafts) {
      const items = JSON.parse(await readFile(path.resolve(draft), 'utf8'));
      const sourcePath = lessonSourcePath(items[0]?.lesson_source_id);
      if (!sourcePath) throw new Error(`${draft}: unknown lesson_source_id`);
      batches.push({ file: path.basename(draft), items, source: JSON.parse(await readFile(path.join(root, sourcePath), 'utf8')) });
    }
    const updated = addCandidates(await readLearningSection(root, SECTION), batches);
    await writeLearningSection(root, SECTION, updated);
    console.log(`candidates=${updated.question_count} (+${batches.reduce((n, b) => n + b.items.length, 0)})`);
  } else if (command === 'build') {
    console.log(`runtime items=${await build(root, process.argv.includes('--check'))}`);
  } else {
    throw new Error('Usage: grammar_question_review.mjs export|apply|add|build <root> [file...]');
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
