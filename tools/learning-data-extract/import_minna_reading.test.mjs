import test from 'node:test';
import assert from 'node:assert/strict';
import { buildSection } from './import_minna_reading.mjs';

const hash = 'a'.repeat(64);

function fixture() {
  const passages = [];
  const questions = [];
  const options = [];
  let qNumber = 0;
  for (const level of ['N5', 'N4']) {
    const offset = level === 'N4' ? 25 : 0;
    const total = level === 'N5' ? 94 : 96;
    for (let local = 1; local <= 25; local++) {
      const chapter = offset + local;
      const passageId = `P-${level}-${local}`;
      const passage = { ma: passageId, cap: level, phan: 'reading', unit_ma: `BOOK-U${String(local).padStart(3, '0')}`,
        lesson_ma: `LESSON-${chapter}`, tieu_de: `Passage ${chapter}`, noi_dung: `本文 ${chapter}` };
      passages.push(passage);
      const count = Math.floor(total / 25) + (local <= total % 25 ? 1 : 0);
      for (let index = 1; index <= count; index++) {
        const id = `Q-${level}-${local}-${index}`;
        questions.push({ ma: id, ma_cha: passageId, cau_hoi: `Question ${id}`, loai_cau: 'mot_dap_an',
          dap_an: 1, answer_uncertain: false, giai_thich: 'Source explanation' });
        options.push({ cau_hoi_ma: id, order_index: 1, noi_dung: `Correct ${id}`, la_dap_an: true },
          { cau_hoi_ma: id, order_index: 2, noi_dung: `Other ${id}`, la_dap_an: false });
      }
    }
  }
  return { passages, questions, options };
}

test('Minna reading import keeps only source-bound questions and covers all 50 chapters', () => {
  const rows = fixture();
  const result = buildSection(rows.passages, rows.questions, rows.options,
    { 'bai_doc.json': hash, 'cau_hoi.json': hash, 'phuong_an.json': hash });
  assert.equal(result.counts.N5.checks, 94);
  assert.equal(result.counts.N4.checks, 96);
  assert.equal(result.counts.total_checks, 190);
  assert.equal(result.exercises.length, 190);
  assert.deepEqual(result.held_checks, []);
  assert.equal(new Set(result.exercises.map((row) => row.source_code)).size, 190);
  assert.equal(result.exercises.find((row) => row.level === 'N4').chapter, 26);
  assert.equal(result.exercises[0].correct_option, 1);
  assert.equal(result.smart_test_eligible, false);
  assert.equal(result.publication, 'OWNER_AUTHORIZED_TEAM_SEED');
  assert.equal(result.rights, 'OWNER_CONFIRMED_TEAM_DISTRIBUTION');
  assert.equal(result.distribution_authorization, 'JLAS_OWNER_CONFIRMED_TEAM_DISTRIBUTION_2026-10-18');
});

test('Minna reading import fails closed when a source answer does not match its unique key', () => {
  const rows = fixture();
  rows.questions[0].dap_an = 2;
  assert.throws(() => buildSection(rows.passages, rows.questions, rows.options,
    { 'bai_doc.json': hash, 'cau_hoi.json': hash, 'phuong_an.json': hash }),
  /Expected 190 Minna reading checks/);
});

test('OD-09: a passage whose picture is missing is held with its reason and keeps its passage number', () => {
  const rows = fixture();
  const held = rows.passages.find((passage) => passage.ma === 'P-N5-20');
  held.noi_dung = '【sym2-prompt】\n」は 何ですか。';
  // Chapter 20 keeps another readable passage, as lesson 20 does in the source (P030/P031).
  rows.passages.push({ ...held, ma: 'P-N5-20b', noi_dung: '本文 20b' });
  rows.questions.filter((question) => question.ma_cha === 'P-N5-20').slice(2)
    .forEach((question) => { question.ma_cha = 'P-N5-20b'; });
  const result = buildSection(rows.passages, rows.questions, rows.options,
    { 'bai_doc.json': hash, 'cau_hoi.json': hash, 'phuong_an.json': hash });
  assert.equal(result.held_checks.length, 2);
  assert.equal(result.exercises.length, 188);
  assert.equal(result.counts.N5.checks, 92);
  assert.ok(result.held_checks.every((row) => row.reason === 'MISSING_IMAGE' && /-L20-READ-P020-Q00\d$/.test(row.source_code)));
  assert.ok(!result.exercises.some((row) => row.passage_id === 'P-N5-20'));
  // Later passages keep the numbers they had before the hold.
  assert.ok(result.exercises.some((row) => /^LP-OWNED-MINNA-N5-L21-READ-P021-Q001$/.test(row.source_code)));
});

