const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-foundation-study.js');

function study(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'FOUNDATION', activityState: 'STUDYING', sourceRevision: 'rev-1',
    totalCards: 2, flippedCount: 1, rememberedCount: 1, reviewRemaining: 1, checkEligible: false,
    cards: [
      { memberId: 301, orderIndex: 2, character: 'か', romaji: 'ka', flipped: false, remembered: false },
      { memberId: 300, orderIndex: 1, character: 'あ', romaji: 'a', flipped: true, remembered: true }
    ]
  }, overrides);
}

test('sorts cards by order and selects the first unremembered card as current', () => {
  const model = page.viewModel(study());
  assert.equal(model.totalCards, 2);
  assert.equal(model.cards[0].memberId, 300);
  assert.equal(model.current.memberId, 301);
});

test('front shows the kana character and a flip action, never the romaji', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /か/);
  assert.match(html, /Lật thẻ/);
  assert.doesNotMatch(html, /Đã nhớ/);
  assert.doesNotMatch(html, />ka</);
});

test('back reveals the romaji and the remember actions once flipped', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null);
  assert.match(html, />ka</);
  assert.match(html, /Đã nhớ/);
  assert.match(html, /Chưa nhớ/);
});

test('labels the script from the character itself (Hiragana vs Katakana)', () => {
  assert.equal(page.scriptLabel('あ'), 'HIRAGANA');
  assert.equal(page.scriptLabel('ア'), 'KATAKANA');
  assert.equal(page.scriptLabel('ん'), 'HIRAGANA');
  assert.equal(page.scriptLabel('ン'), 'KATAKANA');
  assert.equal(page.scriptLabel(''), 'BẢNG CHỮ CÁI');
  assert.match(page.cardFace({ memberId: 1, character: 'ア', romaji: 'a', flipped: false }), /KATAKANA/);
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

test('names the check "Kiểm tra nhanh", renders 1-based options and never exposes an answer key', () => {
  const check = { attemptId: 700, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 2, questions: [
    { questionId: 1, questionType: 'CHARACTER_TO_ROMAJI', questionText: 'Chữ “あ” đọc là gì?', options: ['a', 'ka'] },
    { questionId: 2, questionType: 'ROMAJI_TO_CHARACTER', questionText: 'Chữ nào đọc là “ka”?', options: ['か', 'あ'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /Kiểm tra nhanh Bảng chữ cái/);
  assert.doesNotMatch(html, /Quiz cuối bài/);
  assert.match(html, /data-attempt-id="700"/);
  assert.match(html, /value="1"/);
  assert.match(html, /value="2"/);
  assert.doesNotMatch(html, /correctOption|answerKey|data-answer/);
});

test('renders a pass and a cooldown/review gate', () => {
  const pass = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] });
  assert.match(pass, /Đã đạt/);
  assert.match(pass, /80%/);

  const fail = page.renderResult({ totalQuestions: 10, correctAnswers: 0, scorePercent: 0, passed: false,
    passThresholdPercent: 70, attemptsUsed: 3, attemptsRemaining: 0, cooldownUntil: '2026-09-30T15:30:00',
    reviewRequired: true, activityState: 'COOLDOWN',
    review: [{ questionId: 1, correctOption: 1, selectedOption: 2, correct: false }] });
  assert.match(fail, /Chưa đạt/);
  assert.match(fail, /Lần thử 3\/3/);
  assert.match(fail, /Hãy ôn lại các chữ đã sai/);
});

test('escapes server content instead of injecting it as HTML', () => {
  const s = study();
  s.cards[0].character = '<img src=x onerror=alert(1)>';
  const html = page.renderStudy(page.viewModel(s), null);
  assert.doesNotMatch(html, /<img src=x/);
  assert.match(html, /&lt;img/);
});

test('empty-safe rendering when there are no cards at all', () => {
  const html = page.renderStudy(page.viewModel({ cards: [], totalCards: 0, rememberedCount: 0 }), null);
  assert.match(html, /Chưa có thẻ chữ cái/);
});
