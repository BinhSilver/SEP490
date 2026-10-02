const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-kanji-study.js');

function study(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'KANJI', activityState: 'STUDYING', sourceRevision: 'rev-1',
    totalCards: 2, flippedCount: 1, rememberedCount: 1, reviewRemaining: 1, checkEligible: false,
    cards: [
      { memberId: 201, orderIndex: 2, character: '校', hanViet: 'HIỆU', meaningVi: 'trường học',
        jlptLevel: 'N5', strokeCount: 10, onReadings: ['コウ'], kunReadings: [], flipped: false, remembered: false },
      { memberId: 200, orderIndex: 1, character: '間', hanViet: 'GIAN', meaningVi: 'khoảng, giữa',
        jlptLevel: 'N5', strokeCount: 12, onReadings: ['カン'], kunReadings: ['あいだ'], flipped: true, remembered: true }
    ]
  }, overrides);
}

test('sorts cards by order and selects the first unremembered card as current', () => {
  const model = page.viewModel(study());
  assert.equal(model.totalCards, 2);
  assert.equal(model.rememberedCount, 1);
  assert.equal(model.cards[0].memberId, 200);
  assert.equal(model.current.memberId, 201);
});

test('shows a flip action before the card is flipped', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /Lật thẻ/);
  assert.doesNotMatch(html, /Đã nhớ/);
  assert.match(html, /校/);
});

test('shows remember actions and real-data-only reading chips once flipped', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null);
  assert.match(html, /Đã nhớ/);
  assert.match(html, /Chưa nhớ/);
  assert.match(html, /On: コウ/);
});

test('never fabricates a reading chip when kanji_reading has no rows', () => {
  const s = study();
  s.cards[0].flipped = true;
  s.cards[0].onReadings = [];
  s.cards[0].kunReadings = [];
  const html = page.renderStudy(page.viewModel(s), null);
  assert.doesNotMatch(html, /On:/);
  assert.doesNotMatch(html, /Kun:/);
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
    { questionId: 1, questionType: 'KANJI_TO_READING', questionText: 'Chữ "間" đọc là gì?', options: ['カン', 'コウ'] },
    { questionId: 2, questionType: 'MATCHING', questionText: 'Ghép chữ "校" với nghĩa đúng.', options: ['trường học', 'khoảng, giữa'] }
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
  assert.match(fail, /Hãy ôn lại các chữ đã sai/);
});

test('empty-safe rendering when there are no cards at all', () => {
  const html = page.renderStudy(page.viewModel({ cards: [], totalCards: 0, rememberedCount: 0 }), null);
  assert.match(html, /Chưa có thẻ Kanji/);
});
