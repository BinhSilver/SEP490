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
  assert.match(html, /data-check/);
  assert.doesNotMatch(html, /data-card-mode|Ghi nhớ|Gõ phản xạ/);
});

test('Kanji review returns to the two-tab lesson view without quiz attempt history or write controls', () => {
  assert.equal(typeof page.renderReview, 'function');
  if (typeof page.renderReview !== 'function') return;
  const html = page.renderReview(page.viewModel(study()), 'content', 0, { browseIndex: 0 });
  assert.match(html, /data-study-tab="content"/);
  assert.match(html, /data-study-tab="flashcards"/);
  assert.match(html, /ilp-kanji-list/);
  assert.match(html, /data-browse-card|data-card=/);
  assert.doesNotMatch(html, /Kết quả các lượt làm bài|data-feedback-batch|data-remember=|data-check/);
});

test('unfinished Kanji activity cannot offer a practice quiz', async () => {
  const savedWindow = global.window, savedDocument = global.document, savedFetch = global.fetch;
  const root = { innerHTML: '', addEventListener() {}, setAttribute() {} };
  global.window = { location: { search: '?activity=1&mode=practice' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: require('../../main/resources/static/js/pages/interactive-study-mode.js') };
  global.document = { getElementById(id) { return id === 'interactiveKanjiStudyRoot' ? root : null; } };
  global.fetch = async () => ({ ok: true, json: async () => ({ data: study() }) });
  try {
    page.mount();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(root.innerHTML, /chưa hoàn thành/i);
    assert.doesNotMatch(root.innerHTML, /data-check/);
  } finally { global.window = savedWindow; global.document = savedDocument; global.fetch = savedFetch; }
});

test('Kanji typed recall stays local and does not treat another character as correct', () => {
  assert.equal(page.checkTypedAnswer('間', '間'), true);
  assert.equal(page.checkTypedAnswer('間', '校'), false);
});

test('Kanji browsing persists viewed cards and omits separate remember/type modes', () => {
  const model = page.viewModel(study());
  const browse = page.renderStudy(model, null, 'flashcards', 0, { mode: 'browse', browseIndex: 1 });
  assert.match(browse, /data-browse-step="1"/);
  assert.doesNotMatch(browse, /data-remember=/);
  assert.match(browse, /data-card="201"/);
  assert.match(browse, /data-check/);
  assert.doesNotMatch(browse, /data-card-mode|data-typed-recall|data-remember=/);
});

test('a viewed Kanji card opens on its front and can reveal its back locally', () => {
  const model = page.viewModel(study());
  const front = page.renderStudy(model, null, 'flashcards', 0, { browseIndex: 0, browseFlipped: false });
  const back = page.renderStudy(model, null, 'flashcards', 0, { browseIndex: 0, browseFlipped: true, turning: true });
  assert.doesNotMatch(front, /class="ilp-card is-flipped/);
  assert.match(back, /class="ilp-card is-flipped is-turning/);
});

test('Kanji first reveal stays on the same card; subsequent turns do not write again', async () => {
  const savedWindow = global.window, savedDocument = global.document, savedFetch = global.fetch;
  const listeners = {};
  const root = { innerHTML: '', addEventListener(type, listener) { (listeners[type] ||= []).push(listener); }, setAttribute() {} };
  const initial = study({ flippedCount: 0, cards: study().cards.map(card => ({ ...card, flipped: false })) });
  const viewed = study({ flippedCount: 1, cards: study().cards.map(card => ({ ...card, flipped: card.memberId === 200 })) });
  let writes = 0;
  global.window = { location: { search: '?activity=1&tab=flashcards' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: require('../../main/resources/static/js/pages/interactive-study-mode.js') };
  global.document = { getElementById(id) { return id === 'interactiveKanjiStudyRoot' ? root : null; } };
  global.fetch = async url => {
    if (url.endsWith('/cards/200/flip')) writes++;
    return { ok: true, json: async () => ({ data: url.endsWith('/micro-check/state') ? {} :
      url.endsWith('/cards/200/flip') ? viewed : initial }) };
  };
  try {
    page.mount();
    await new Promise(resolve => setImmediate(resolve));
    const card = { dataset: { card: '200' }, closest(selector) { return selector === '[data-card]' ? card : null; } };
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-flipped is-turning"/);
    assert.match(root.innerHTML, /1\/2 trong phần này/);
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-turning"/);
    assert.equal(writes, 1);
  } finally { global.window = savedWindow; global.document = savedDocument; global.fetch = savedFetch; }
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

test('shows viewed progress and real-data-only reading chips after flipping', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null);
  assert.match(html, /đã xem/);
  assert.doesNotMatch(html, /Đã nhớ|Chưa nhớ/);
  assert.match(html, /On: カン/);
});

test('never fabricates a reading chip when kanji_reading has no rows', () => {
  const s = study();
  s.cards = s.cards.map(card => ({ ...card, flipped: true, onReadings: [], kunReadings: [] }));
  const html = page.renderStudy(page.viewModel(s), null);
  const activeCard = html.match(/<article class="ilp-card[\s\S]*?<\/article>/)?.[0] || '';
  assert.doesNotMatch(activeCard, /On:|Kun:/);
});

test('opens the check only when every card is remembered and the batch is attemptable', () => {
  const s = study();
  s.cards[0].flipped = true; s.cards[0].remembered = true;
  s.rememberedCount = 2; s.reviewRemaining = 0; s.checkEligible = true;
  assert.doesNotMatch(page.renderStudy(page.viewModel(s), null), /data-check disabled/);

  const gated = page.renderStudy(page.viewModel(s), { reviewRequired: true, checkEligible: false, passed: false });
  assert.match(gated, /data-check/);
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
  assert.match(html, /Hoàn thành phần trắc nghiệm và ghép các cặp chữ Hán – nghĩa/);
  assert.match(html, /data-check-progress>Đã trả lời 0\/0 câu trắc nghiệm · 0\/2 cặp nối/);
  assert.doesNotMatch(html, /Chưa nối|data-match-label/);
  assert.doesNotMatch(html, /correctOption|answerKey/);
});

test('failed Kanji quiz result returns to the lesson tabs; passed result uses read-only review', () => {
  const failed = page.renderResult({ totalQuestions: 2, correctAnswers: 1, scorePercent: 50, passed: false,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, activityState: 'CHECK_READY', review: [] });
  const passed = page.renderResult({ totalQuestions: 2, correctAnswers: 2, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, activityState: 'PASSED', review: [] });
  assert.match(failed, /href="\/learning\/v2\/kanji\?activity=[^\"]*&amp;tab=content">Quay lại bài học/);
  assert.doesNotMatch(failed, /mode=review/);
  assert.match(passed, /mode=review&amp;tab=content">Xem lại bài giảng/);
});

test('submitting an incomplete Kanji quiz warns that blank questions are scored incorrect', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const js = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pages/interactive-kanji-study.js'), 'utf8');
  assert.match(js, /Bạn còn .* câu chưa trả lời/);
  assert.match(js, /Câu bỏ trống sẽ bị tính sai/);
});

test('Kanji matching selection and completed pairs use visible highlighted states without status text', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const js = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pages/interactive-kanji-study.js'), 'utf8');
  const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  assert.match(js, /classList\.toggle\('is-matched'/);
  assert.match(js, /classList\.toggle\('is-selected'/);
  assert.match(css, /\.ilp-matching__column button\.is-selected[^{}]*\{/);
  assert.match(css, /\.ilp-matching__column button\.is-matched[^{}]*\{/);
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

test('renders saved quiz attempt history inside the quiz result, not the lesson review page', () => {
  const history = '<section class="ilp-attempt-history" aria-label="Lịch sử kết quả bài kiểm tra"><h2>Các lần làm bài</h2><button>Lần 2 · 80%</button></section>';
  const html = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 2, attemptsRemaining: 1, reviewRequired: false,
    activityState: 'PASSED', review: [] }, { attemptHistoryHtml: history });
  assert.ok(html.indexOf('KẾT QUẢ') < html.indexOf('Lịch sử kết quả bài kiểm tra'));
  assert.match(html, /Lần 2 · 80%/);
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
