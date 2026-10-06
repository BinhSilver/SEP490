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

test('foundation activity opens with content and flashcard tabs, showing full ordered kana list', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /Nội dung bài học/);
  assert.match(html, /Học bằng Flashcard/);
  assert.match(html, /data-kana-list/);
  assert.match(html, /data-pronounce="あ"/);
  assert.match(html, /data-pronounce="か"/);
  assert.match(html, /aria-selected="true" data-study-tab="content"/);
});

test('flashcard is tap-to-flip, has pronunciation control, and does not reveal romaji on the front', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard');
  assert.match(html, /か/);
  assert.match(html, /data-card="301"/);
  assert.match(html, /data-pronounce="か"/);
  assert.doesNotMatch(html, /Lật thẻ/);
  assert.doesNotMatch(html, /Đã nhớ/);
  const card = html.match(/<article class="ilp-card[\s\S]*?<\/article>/)[0];
  assert.doesNotMatch(card, />ka</);
});

test('back reveals the romaji and the remember actions once flipped', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null, 'flashcard');
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

test('a passed activity remains on the two-tab lesson page instead of trapping the learner on a result-only screen', () => {
  const html = page.renderStudy(page.viewModel(study()), { passed: true, checkEligible: false }, 'content');
  assert.match(html, /Nội dung bài học/);
  assert.match(html, /Học bằng Flashcard/);
  assert.match(html, /data-kana-list/);
  assert.match(html, /đã hoàn thành/i);
  assert.doesNotMatch(html, /data-check/);
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

  const nextBatch = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, reviewRequired: false,
    activityState: 'CHECK_READY', review: [] }, { activityId: 42 });
  assert.match(nextBatch, /data-check-next/);
  assert.match(nextBatch, /còn phần kiểm tra tiếp theo/i);
  assert.doesNotMatch(nextBatch, /đã hoàn thành phần này/i);

  const fail = page.renderResult({ totalQuestions: 10, correctAnswers: 0, scorePercent: 0, passed: false,
    passThresholdPercent: 70, attemptsUsed: 3, attemptsRemaining: 0, cooldownUntil: '2026-09-30T15:30:00',
    reviewRequired: true, activityState: 'COOLDOWN',
    review: [{ questionId: 1, correctOption: 1, selectedOption: 2, correct: false }] });
  assert.match(fail, /Chưa đạt/);
  assert.match(fail, /Lần thử 3\/3/);
  assert.match(fail, /Hãy ôn lại các chữ đã sai/);
});

test('quiz pass result links back into the original lesson content and roadmap', () => {
  const html = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] }, { activityId: 42 });
  assert.match(html, /href="\/learning\/v2\/foundation\?activity=42&amp;tab=content"/);
  assert.match(html, /href="\/learning\/path\/v2"/);
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

test('[JLAS-W0125] a part created by a pace change shows its position; an unsplit activity does not', () => {
  const render = (o) => page.renderStudy(page.viewModel(study(o)), null);
  assert.match(render({ partIndex: 2, partCount: 3 }), /Phần 2 \/ 3/);
  assert.doesNotMatch(render({ partIndex: 1, partCount: 1 }), /Phần 1 \/ 1/);
  assert.doesNotMatch(render({}), /Phần \d+ \/ \d+/);
});

// ---- Foundation flashcard mode parity with Vocabulary/Kanji (spec 2026-10-05 §2, §3) ----

test('flashcard tab offers the four approved modes and keeps "Ghi nhớ" as the default', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard');
  for (const label of ['Lướt thẻ', 'Ghi nhớ', 'Kiểm tra', 'Gõ phản xạ']) assert.match(html, new RegExp('>' + label + '<'));
  assert.match(html, /data-card-mode="remember" aria-current="true"/);
  assert.match(html, /data-card="301"/);
});

test('browse mode flips locally and never posts a server flip', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'browse', browseIndex: 0 });
  assert.match(html, /data-browse-card/);
  assert.doesNotMatch(html, /data-card="/);
  assert.match(html, /1\/2/);
  assert.match(html, /data-browse-step="-1" disabled/);
  const flipped = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'browse', browseIndex: 1, browseFlipped: true });
  assert.match(flipped, /ilp-card is-flipped/);
  assert.match(flipped, /data-browse-step="1" disabled/);
});

test('typing mode shows the kana and asks for romaji as practice only', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'type', browseIndex: 0 });
  assert.match(html, /data-typed-recall/);
  assert.match(html, /あ/);
  assert.match(html, /không thay đổi kết quả/);
  const typing = html.match(/<div class="ilp-typing">[\s\S]*?<\/form>/)[0];
  assert.doesNotMatch(typing, />a</, 'the typing panel must not reveal the romaji answer');
});

test('check mode opens the official quiz only when the server says the part is eligible', () => {
  const blocked = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'check' });
  assert.match(blocked, /data-check disabled/);
  const ready = page.renderStudy(page.viewModel(study({ checkEligible: true })), null, 'flashcard', { mode: 'check' });
  assert.match(ready, /data-check>/);
});

test('typed romaji is compared case- and width-insensitively, never accepting an empty key', () => {
  assert.equal(page.checkTypedRomaji('ka', ' KA '), true);
  assert.equal(page.checkTypedRomaji('shi', 'ｓｈｉ'), true);
  assert.equal(page.checkTypedRomaji('ka', 'ki'), false);
  assert.equal(page.checkTypedRomaji('', ''), false);
});
