import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeLearningSection } from './learning_content_store.mjs';

const SECTION = 'jlpt_minna_n5_n4_reading';
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const clean = (value) => String(value ?? '').trim();
const norm = (value) => clean(value).normalize('NFKC').replace(/\s+/g, ' ').toLocaleLowerCase('ja');
const escapeHtml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');

// Owner 2026-10-21 (OD-09): a passage that still shows a picture placeholder cannot be answered from its text.
const MISSING_IMAGE = /【sym\d+-prompt】/;

function parseIndex(value) {
  const index = Number(value);
  return Number.isInteger(index) && index > 0 ? index : null;
}

/** Build passage-bound Learning checks; source-answer records remain non-scored and Smart-Test-ineligible. */
export function buildSection(passages, questions, options, sourceHashes) {
  if (!Array.isArray(passages) || !Array.isArray(questions) || !Array.isArray(options)
      || !sourceHashes || Object.values(sourceHashes).some((hash) => !/^[a-f0-9]{64}$/.test(hash))) {
    throw new Error('Minna reading inputs and SHA-256 provenance are required.');
  }
  const optionsByQuestion = new Map();
  for (const option of options) {
    if (!option.cau_hoi_ma || !Number.isInteger(Number(option.order_index))) continue;
    const group = optionsByQuestion.get(option.cau_hoi_ma) ?? [];
    group.push(option);
    optionsByQuestion.set(option.cau_hoi_ma, group);
  }
  const questionsByPassage = new Map();
  for (const question of questions) {
    if (!question.ma_cha) continue;
    const group = questionsByPassage.get(question.ma_cha) ?? [];
    group.push(question);
    questionsByPassage.set(question.ma_cha, group);
  }

  const chapterCounts = { N5: new Set(), N4: new Set() };
  const exercises = [];
  const held = { missing_passage: 0, invalid_answer_or_options: 0, non_reading_or_ambiguous: 0 };
  const heldChecks = [];
  const passageOrder = new Map();
  for (const passage of passages) {
    if (passage.phan !== 'reading' || !['N5', 'N4'].includes(passage.cap)) continue;
    const unitMatch = String(passage.unit_ma ?? '').match(/-U(\d{3})$/);
    const localChapter = unitMatch ? Number(unitMatch[1]) : NaN;
    if (!Number.isInteger(localChapter) || localChapter < 1 || localChapter > 25) continue;
    const chapter = localChapter + (passage.cap === 'N4' ? 25 : 0);
    const content = clean(passage.noi_dung);
    const passageQuestions = questionsByPassage.get(passage.ma) ?? [];
    if (!content) held.missing_passage += passageQuestions.length;
    const orderedQuestions = [...passageQuestions].sort((a, b) => Number(a.order_index) - Number(b.order_index));
    for (const question of orderedQuestions) {
      const label = `${passage.cap} chapter ${chapter} ${question.ma}`;
      const prompt = clean(question.cau_hoi);
      const questionOptions = (optionsByQuestion.get(question.ma) ?? [])
        .sort((a, b) => Number(a.order_index) - Number(b.order_index));
      const correctOptions = questionOptions.filter((option) => option.la_dap_an === true || option.la_dap_an === 'True');
      const declaredAnswer = parseIndex(question.dap_an);
      const answerOption = declaredAnswer == null ? null : questionOptions[declaredAnswer - 1];
      const valid = content && prompt && question.loai_cau === 'mot_dap_an'
        && !/nghe|vừa nghe|người nghe/i.test(prompt)
        && question.answer_uncertain !== true && question.answer_uncertain !== 'True'
        && questionOptions.length >= 2 && questionOptions.length <= 4
        && questionOptions.every((option) => clean(option.noi_dung))
        && new Set(questionOptions.map((option) => norm(option.noi_dung))).size === questionOptions.length
        && correctOptions.length === 1 && answerOption === correctOptions[0];
      if (!valid) {
        if (!content) continue;
        if (question.loai_cau !== 'mot_dap_an' || /nghe|vừa nghe/i.test(prompt)) held.non_reading_or_ambiguous++;
        else held.invalid_answer_or_options++;
        continue;
      }
      const order = passageOrder.get(passage.ma) ?? passageOrder.size + 1;
      passageOrder.set(passage.ma, order);
      const withinPassage = exercises.filter((row) => row.passage_id === passage.ma).length
        + heldChecks.filter((row) => row.passage_id === passage.ma).length + 1;
      const sourceCode = `LP-OWNED-MINNA-${passage.cap}-L${String(chapter).padStart(2, '0')}-READ-P${String(order).padStart(3, '0')}-Q${String(withinPassage).padStart(3, '0')}`;
      // Held checks keep their passage number and code, so no other check is renumbered.
      if (MISSING_IMAGE.test(content)) {
        heldChecks.push({ source_code: sourceCode, passage_id: passage.ma, question_id: question.ma, reason: 'MISSING_IMAGE' });
        continue;
      }
      const answerIndex = questionOptions.findIndex((option) => option === correctOptions[0]) + 1;
      exercises.push({
        source_code: sourceCode,
        chapter,
        level: passage.cap,
        passage_id: passage.ma,
        passage_title: clean(passage.tieu_de) || `Minna bài ${chapter} · Đọc hiểu`,
        passage_html: content.split(/\r?\n/).filter((line) => clean(line))
          .map((line) => `<p>${escapeHtml(line)}</p>`).join(''),
        question_id: question.ma,
        question_prompt: prompt,
        options: questionOptions.map((option) => clean(option.noi_dung)),
        answer_text: clean(correctOptions[0].noi_dung),
        correct_option: answerIndex,
        explanation: clean(question.giai_thich),
        source_lesson_code: passage.lesson_ma,
        source_unit_code: passage.unit_ma,
        source_status: 'DRAFT',
      });
      chapterCounts[passage.cap].add(chapter);
    }
  }
  for (const level of ['N5', 'N4']) {
    const expected = new Set(Array.from({ length: 25 }, (_, index) => index + (level === 'N4' ? 26 : 1)));
    if (chapterCounts[level].size !== 25 || [...expected].some((chapter) => !chapterCounts[level].has(chapter))) {
      throw new Error(`${level} reading coverage is incomplete: ${chapterCounts[level].size}/25 chapters have usable checks.`);
    }
  }
  if (exercises.length + heldChecks.length !== 190) {
    throw new Error(`Expected 190 Minna reading checks (usable + held); got ${exercises.length + heldChecks.length}.`);
  }
  const checks = (level) => exercises.filter((row) => row.level === level).length;
  return {
    kind: 'JLPT_MINNA_N5_N4_PASSAGE_BOUND_READING',
    source_owner: 'vnjpclub',
    source_hashes: sourceHashes,
    publication: 'OWNER_AUTHORIZED_TEAM_SEED',
    study_eligible: true,
    quiz_eligible: false,
    smart_test_eligible: false,
    rights: 'OWNER_CONFIRMED_TEAM_DISTRIBUTION',
    distribution_authorization: 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18',
    counts: { N5: { chapters: 25, checks: checks('N5') }, N4: { chapters: 25, checks: checks('N4') },
      total_checks: exercises.length },
    held,
    held_checks: heldChecks,
    exercises,
  };
}

async function readJson(filename) {
  const bytes = await readFile(filename);
  return { rows: JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')), sha256: sha256(bytes) };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const input = path.resolve(process.argv[3] ?? '');
  if (!process.argv[3]) throw new Error('Pass the user-supplied Minna source directory explicitly.');
  const files = Object.fromEntries(await Promise.all(['bai_doc.json', 'cau_hoi.json', 'phuong_an.json']
    .map(async (name) => [name, await readJson(path.join(input, name))])));
  const sourceHashes = Object.fromEntries(Object.entries(files).map(([name, value]) => [name, value.sha256]));
  const section = buildSection(files['bai_doc.json'].rows, files['cau_hoi.json'].rows,
    files['phuong_an.json'].rows, sourceHashes);
  await writeLearningSection(root, SECTION, section);
  console.log(JSON.stringify({ counts: section.counts, held: section.held, source_hashes: sourceHashes }));
}
