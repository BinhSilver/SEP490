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

test('kanji lesson has ordered full-content and flashcard tabs with audio', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /data-study-tab="content"/);
  assert.match(html, /data-study-tab="flashcards"/);
  assert.ok(html.indexOf('間') < html.indexOf('校'));
  assert.match(html, /data-speak="間"/);
  assert.match(html, /data-speak="校"/);
  assert.match(html, /On: カン/);
  assert.doesNotMatch(html, /data-flip=/);
  assert.match(html, /data-card-mode="browse"/);
  assert.match(html, /data-card-mode="remember"/);
  assert.match(html, /data-card-mode="check"/);
  assert.match(html, /data-card-mode="type"/);
});

test('Kanji typed recall stays local and does not treat another character as correct', () => {
  assert.equal(page.checkTypedAnswer('間', '間'), true);
  assert.equal(page.checkTypedAnswer('間', '校'), false);
});

test('Kanji browsing and typing stay separate from server remembered-card actions', () => {
  const model = page.viewModel(study());
  const browse = page.renderStudy(model, null, 'flashcards', 0, { mode: 'browse', browseIndex: 1 });
  assert.match(browse, /data-browse-step="1"/);
  assert.doesNotMatch(browse, /data-remember=/);
  const typed = page.renderStudy(model, null, 'flashcards', 0, { mode: 'type', browseIndex: 1 });
  assert.match(typed, /data-typed-recall/);
  assert.doesNotMatch(typed, /data-remember=/);
});

test('a legacy 61-Kanji activity shows five balanced pages and retains every glyph', () => {
  const cards = Array.from({ length: 61 }, (_, i) => ({ memberId: i + 1, orderIndex: i + 1,
    character: `字${i + 1}`, meaningVi: `nghĩa ${i + 1}`, onReadings: [], kunReadings: [],
    flipped: false, remembered: false }));
  const model = page.viewModel(study({ cards, totalCards: 61 }));
  const all = [0, 1, 2, 3, 4].map(part => page.renderStudy(model, null, 'content', part));
  assert.deepEqual(all.map(html => (html.match(/class="ilp-study-items__item"/g) || []).length), [13, 12, 12, 12, 12]);
  for (let i = 1; i <= 61; i++) assert.equal(all.filter(html => html.includes(`>字${i}</span>`)).length, 1);
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
  const activeCard = html.match(/<article class="ilp-card[\s\S]*?<\/article>/)?.[0] || '';
  assert.doesNotMatch(activeCard, /On:/);
  assert.doesNotMatch(activeCard, /Kun:/);
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

test('shared Kanji matching meanings render as two columns, not multiple choice cards', () => {
  const html = page.renderCheck({ attemptId: 6, batchIndex: 0, batchCount: 1, questions: [
    { questionId: 1, questionType: 'MATCHING', questionText: '校', options: ['khoảng, giữa', 'trường học'] },
    { questionId: 2, questionType: 'MATCHING', questionText: '間', options: ['khoảng, giữa', 'trường học'] }
  ] });
  assert.match(html, /class="ilp-matching"/);
  assert.match(html, /data-match-word="1"/);
  assert.equal((html.match(/data-match-meaning=/g) || []).length, 2);
  assert.doesNotMatch(html, /correctOption|answerKey/);
});

test('renders a pass and a cooldown/review gate', () => {
  const pass = page.renderResult({ totalQuestions: 10, correctAnswers: 7, scorePercent: 70, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] });
  assert.match(pass, /Đã đạt/);
  assert.match(pass, /70%/);

  const nextBatch = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, reviewRequired: false,
    activityState: 'CHECK_READY', review: [] });
  assert.match(nextBatch, /data-check-next/);
  assert.match(nextBatch, /còn phần kiểm tra tiếp theo/i);
  assert.doesNotMatch(nextBatch, /đã hoàn thành phần này/i);

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

test('[JLAS-W0125] a part created by a pace change shows its position; an unsplit activity does not', () => {
  const render = (o) => page.renderStudy(page.viewModel(study(o)), null);
  assert.match(render({ partIndex: 2, partCount: 3 }), /Phần 2 \/ 3/);
  assert.doesNotMatch(render({ partIndex: 1, partCount: 1 }), /Phần 1 \/ 1/);
  assert.doesNotMatch(render({}), /Phần \d+ \/ \d+/);
});
