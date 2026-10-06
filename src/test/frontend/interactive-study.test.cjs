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

test('vocabulary study opens with a full ordered lesson tab and a separate flashcard tab', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /data-study-tab="content"/);
  assert.match(html, /data-study-tab="flashcards"/);
  assert.ok(html.indexOf('時間') < html.indexOf('学校'));
  assert.match(html, /data-speak="時間"/);
  assert.match(html, /data-speak="学校"/);
  assert.match(html, /data-card="101"/);
  assert.doesNotMatch(html, /data-flip=/);
  assert.match(html, /data-card-mode="browse"/);
  assert.match(html, /data-card-mode="remember"/);
  assert.match(html, /data-card-mode="check"/);
  assert.match(html, /data-card-mode="type"/);
});

test('typed recall is local practice and accepts Unicode width normalization', () => {
  assert.equal(page.checkTypedAnswer('カタカナ', 'ｶﾀｶﾅ'), true);
  assert.equal(page.checkTypedAnswer('学校', '学校'), true);
  assert.equal(page.checkTypedAnswer('学校', '時間'), false);
});

test('browse and typed-recall modes do not expose server remember actions', () => {
  const model = page.viewModel(study());
  const browse = page.renderStudy(model, null, 'flashcards', 0, { mode: 'browse', browseIndex: 1, browseFlipped: false });
  assert.match(browse, /data-browse-step="-1"/);
  assert.match(browse, /data-browse-step="1"/);
  assert.doesNotMatch(browse, /data-remember=/);
  const typed = page.renderStudy(model, null, 'flashcards', 0, { mode: 'type', browseIndex: 1 });
  assert.match(typed, /data-typed-recall/);
  assert.doesNotMatch(typed, /data-remember=/);
});

test('a legacy 54-word activity displays balanced pages without dropping any word', () => {
  const cards = Array.from({ length: 54 }, (_, i) => ({ memberId: i + 1, orderIndex: i + 1,
    front: `語${i + 1}`, reading: `ご${i + 1}`, meaning: `nghĩa ${i + 1}`, flipped: false, remembered: false }));
  const model = page.viewModel(study({ cards, totalCards: 54 }));
  const all = [0, 1, 2, 3].map(part => page.renderStudy(model, null, 'content', part));
  assert.deepEqual(all.map(html => (html.match(/class="ilp-study-items__item"/g) || []).length), [14, 14, 13, 13]);
  for (let i = 1; i <= 54; i++) assert.equal(all.filter(html => html.includes(`>語${i}</span>`)).length, 1);
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
  assert.match(fail, /Ôn lại phần đã sai/);
});

test('a shared matching snapshot renders two columns, not ten independent multiple-choice questions', () => {
  const check = { attemptId: 8, batchIndex: 0, batchCount: 1, questions: [
    { questionId: 1, questionType: 'MATCHING', questionText: '学校', options: ['thời gian', 'trường học'] },
    { questionId: 2, questionType: 'MATCHING', questionText: '時間', options: ['thời gian', 'trường học'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /class="ilp-matching"/);
  assert.match(html, /data-match-word="1"/);
  assert.match(html, /data-match-meaning="2"/);
  assert.equal((html.match(/data-match-meaning=/g) || []).length, 2);
  assert.doesNotMatch(html, /correctOption|answerKey/);
});

test('study and quiz layouts use the wider responsive shell and wrap long content', () => {
  const css = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  assert.match(css, /\.ilp-study-page\s*\{[^}]*max-width:\s*1320px/s);
  assert.match(css, /\.ilp-page\s*\{[^}]*width:\s*100%/s);
  assert.match(css, /\.ilp-card\s*\{[^}]*min-width:\s*0/s);
  assert.match(css, /\.ilp-option\s*\{[^}]*min-width:\s*0/s);
  assert.match(css, /overflow-wrap:\s*anywhere/);
  assert.match(css, /@media\s*\(max-width:\s*520px\)[\s\S]*\.ilp-options\s*\{[^}]*grid-template-columns:\s*1fr/s);
});

test('[JLAS-W0125] a part created by a pace change shows its position; an unsplit activity does not', () => {
  const render = (o) => page.renderStudy(page.viewModel(study(o)), null);
  assert.match(render({ partIndex: 2, partCount: 3 }), /Phần 2 \/ 3/);
  assert.doesNotMatch(render({ partIndex: 1, partCount: 1 }), /Phần 1 \/ 1/);
  assert.doesNotMatch(render({}), /Phần \d+ \/ \d+/);
});
