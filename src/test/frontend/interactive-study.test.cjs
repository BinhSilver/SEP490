const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const pathModule = require('node:path');
const page = require('../../main/resources/static/js/pages/interactive-study.js');

function study(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'VOCABULARY', activityState: 'STUDYING', sourceRevision: 'rev-1',
    totalCards: 2, flippedCount: 1, rememberedCount: 1, reviewRemaining: 1, checkEligible: false,
    cards: [
      { memberId: 101, orderIndex: 2, front: '学校', reading: 'がっこう', meaning: 'trường học',
        wordType: 'danh từ', jlptLevel: 'N5', exampleJa: null, exampleVi: null, flipped: false, remembered: false },
      { memberId: 100, orderIndex: 1, front: '時間', reading: 'じかん', meaning: 'thời gian',
        wordType: 'danh từ', jlptLevel: 'N5', exampleJa: '時間がありますか。', exampleVi: 'Bạn có thời gian không?',
        flipped: true, remembered: true }
    ]
  }, overrides);
}

test('sorts cards by order and selects the first unremembered card as current', () => {
  const model = page.viewModel(study());
  assert.equal(model.totalCards, 2);
  assert.equal(model.rememberedCount, 1);
  assert.equal(model.reviewRemaining, 1);
  assert.equal(model.checkEligible, false);
  assert.equal(model.cards[0].memberId, 100);
  assert.equal(model.current.memberId, 101);
});

test('shows a flip action before the card is flipped', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /Lật thẻ/);
  assert.doesNotMatch(html, /Đã nhớ/);
});

test('shows remember actions once the card is flipped', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null);
  assert.match(html, /Đã nhớ/);
  assert.match(html, /Chưa nhớ/);
});

test('opens the check only when every card is remembered and the batch is attemptable', () => {
  const s = study();
  s.cards[0].flipped = true; s.cards[0].remembered = true;
  s.rememberedCount = 2; s.reviewRemaining = 0; s.checkEligible = true;
  assert.doesNotMatch(page.renderStudy(page.viewModel(s), null), /data-check disabled/);

  const gated = page.renderStudy(page.viewModel(s), { reviewRequired: true, checkEligible: false, passed: false });
  assert.match(gated, /data-check disabled/);
  assert.match(gated, /cần ôn lại/);
});

test('renders every question with 1-based options and never exposes an answer key', () => {
  const check = { attemptId: 500, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 2, questions: [
    { questionId: 1, questionType: 'WORD_TO_MEANING', questionText: 'Từ "時間" có nghĩa là gì?', options: ['thời gian', 'trường học'] },
    { questionId: 2, questionType: 'MATCHING', questionText: 'Ghép từ "学校" với nghĩa đúng.', options: ['trường học', 'thời gian'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /data-attempt-id="500"/);
  assert.match(html, /value="1"/);
  assert.match(html, /value="2"/);
  assert.doesNotMatch(html, /correctOption|answerKey|data-answer/);
});

test('renders a pass and a cooldown/review gate', () => {
  const pass = page.renderResult({ totalQuestions: 10, correctAnswers: 7, scorePercent: 70, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] });
  assert.match(pass, /Đã đạt/);
  assert.match(pass, /70%/);

  const fail = page.renderResult({ totalQuestions: 10, correctAnswers: 0, scorePercent: 0, passed: false,
    passThresholdPercent: 70, attemptsUsed: 3, attemptsRemaining: 0, cooldownUntil: '2026-09-29T15:30:00',
    reviewRequired: true, activityState: 'COOLDOWN',
    review: [{ questionId: 1, correctOption: 1, selectedOption: 2, correct: false }] });
  assert.match(fail, /Chưa đạt/);
  assert.match(fail, /Lần thử 3\/3/);
  assert.match(fail, /Ôn lại phần đã sai/);
});

test('study and quiz layouts use the wider responsive shell and wrap long content', () => {
  const css = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  assert.match(css, /\.ilp-study-page\s*\{[^}]*max-width:\s*1180px/s);
  assert.match(css, /\.ilp-card\s*\{[^}]*min-width:\s*0/s);
  assert.match(css, /\.ilp-option\s*\{[^}]*min-width:\s*0/s);
  assert.match(css, /overflow-wrap:\s*anywhere/);
  assert.match(css, /@media\s*\(max-width:\s*520px\)[\s\S]*\.ilp-options\s*\{[^}]*grid-template-columns:\s*1fr/s);
});
