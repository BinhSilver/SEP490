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

test('vocabulary study opens with a full ordered lesson tab and a separate two-action flashcard tab', () => {
  const html = page.renderStudy(page.viewModel(study()), null);
  assert.match(html, /data-study-tab="content"/);
  assert.match(html, /data-study-tab="flashcards"/);
  assert.ok(html.indexOf('時間') < html.indexOf('学校'));
  assert.match(html, /data-speak="時間"/);
  assert.match(html, /data-speak="学校"/);
  assert.match(html, /data-card="101"/);
  assert.doesNotMatch(html, /data-flip=/);
  assert.match(html, /data-check/);
  assert.match(html, /<section role="tabpanel"><h2>Các từ trong phần này<\/h2>[\s\S]*data-study-tab="flashcards"[\s\S]*data-check/);
  assert.equal((html.match(/data-check(?:>|\s)/g) || []).length, 2);
  assert.doesNotMatch(html, /data-card-mode|Ghi nhớ|Gõ phản xạ/);
});

test('typed recall is local practice and accepts Unicode width normalization', () => {
  assert.equal(page.checkTypedAnswer('カタカナ', 'ｶﾀｶﾅ'), true);
  assert.equal(page.checkTypedAnswer('学校', '学校'), true);
  assert.equal(page.checkTypedAnswer('学校', '時間'), false);
});

test('flashcard browsing persists flips, has no self-rating/type modes, and keeps check available early', () => {
  const model = page.viewModel(study());
  const browse = page.renderStudy(model, null, 'flashcards', 0, { mode: 'browse', browseIndex: 1, browseFlipped: false });
  assert.match(browse, /data-browse-step="-1"/);
  assert.match(browse, /data-browse-step="1"/);
  assert.doesNotMatch(browse, /data-remember=/);
  assert.match(browse, /data-card="101"/);
  assert.match(browse, /data-check/);
  assert.doesNotMatch(browse, /data-remember=|data-card-mode|data-typed-recall/);
});

test('a legacy 54-word activity displays balanced pages without dropping any word', () => {
  const cards = Array.from({ length: 54 }, (_, i) => ({ memberId: i + 1, orderIndex: i + 1,
    front: `語${i + 1}`, reading: `ご${i + 1}`, meaning: `nghĩa ${i + 1}`, flipped: false, remembered: false }));
  const model = page.viewModel(study({ cards, totalCards: 54 }));
  const all = [0, 1, 2, 3].map(part => page.renderStudy(model, null, 'content', part));
  assert.deepEqual(all.map(html => (html.match(/class="ilp-study-items__item"/g) || []).length), [14, 14, 13, 13]);
  for (let i = 1; i <= 54; i++) assert.equal(all.filter(html => html.includes(`>語${i}</span>`)).length, 1);
});

test('splits flashcards and read-only review into the same small parts as the lesson list', () => {
  const cards = Array.from({ length: 54 }, (_, i) => ({ memberId: i + 1, orderIndex: i + 1,
    front: `語${i + 1}`, reading: `ご${i + 1}`, meaning: `nghĩa ${i + 1}`, flipped: true, remembered: true }));
  const model = page.viewModel(study({ cards, totalCards: 54 }));
  const review = page.renderReview(model, null, 'content', 0);
  assert.match(review, /data-study-tab="content"/);
  assert.match(review, /data-study-tab="flashcards"/);
  assert.match(review, /data-study-part="3"/);
  assert.doesNotMatch(review, /data-feedback-batch|Kết quả các lượt làm bài/);
  const flashcards = page.renderReview(model, null, 'flashcards', 0);
  assert.match(flashcards, /data-browse-card/);
  assert.doesNotMatch(flashcards, /data-card-mode|data-check|data-remember=/);
  assert.equal((flashcards.match(/class="ilp-review-list__item"/g) || []).length, 0);
});

test('review flashcard backs give long Vietnamese meanings readable line spacing', () => {
  const css = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  assert.match(css, /\.ilp-card__meaning\s*\{[^}]*line-height:\s*1\.5/s);
  assert.match(css, /\.ilp-card__meaning\s*\{[^}]*min-width:\s*0[^}]*max-width:\s*100%/s);
  assert.match(css, /\.ilp-card__back\s*\{[^}]*min-width:\s*0[^}]*max-width:\s*100%/s);
  assert.match(css, /\.ilp-review-list\s*\{[^}]*grid-template-columns:\s*repeat\(auto-fill,\s*minmax\(min\(100%,\s*300px\),\s*1fr\)\)/s);
});

test('flipping a review or practice card stays local and never records learning progress', () => {
  assert.equal(page.shouldPersistFlip('REVIEW', 'remember'), false);
  assert.equal(page.shouldPersistFlip('PRACTICE', 'remember'), false);
  assert.equal(page.shouldPersistFlip('LEARN', 'browse'), true);
  assert.equal(page.shouldPersistFlip('LEARN', 'remember'), true);
});

test('an unfinished vocabulary activity does not offer a practice quiz that the server will reject', async () => {
  const previousWindow = global.window;
  const previousDocument = global.document;
  const previousFetch = global.fetch;
  const root = { innerHTML: '', addEventListener() {}, setAttribute() {} };
  const requests = [];
  global.window = { location: { search: '?activity=230&mode=practice' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: require('../../main/resources/static/js/pages/interactive-study-mode.js') };
  global.document = { getElementById(id) { return id === 'interactiveStudyRoot' ? root : null; } };
  global.fetch = async url => {
    requests.push(url);
    return { ok: true, json: async () => ({ data: study({ activityId: 230, activityState: 'STUDYING' }) }) };
  };
  try {
    page.mount();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(root.innerHTML, /chưa hoàn thành/i);
    assert.match(root.innerHTML, /href="\/learning\/v2\/vocabulary\?activity=230"/);
    assert.doesNotMatch(root.innerHTML, /data-check/);
    assert.equal(requests.length, 1);
  } finally {
    global.window = previousWindow;
    global.document = previousDocument;
    global.fetch = previousFetch;
  }
});

test('learning flashcard reveals the same card back and advances only on the next action', async () => {
  const previousWindow = global.window;
  const previousDocument = global.document;
  const previousFetch = global.fetch;
  const listeners = {};
  const root = {
    innerHTML: '',
    addEventListener(type, listener) { (listeners[type] ||= []).push(listener); },
    setAttribute() {},
    querySelector() { return null; }
  };
  const cards = [
    { memberId: 1, orderIndex: 1, front: '私', reading: 'わたし', meaning: 'tôi', flipped: false, remembered: false },
    { memberId: 2, orderIndex: 2, front: 'あなた', reading: 'あなた', meaning: 'bạn', flipped: false, remembered: false }
  ];
  const initial = { activityId: 230, activityType: 'VOCABULARY', totalCards: 2, flippedCount: 0,
    rememberedCount: 0, reviewRemaining: 2, checkEligible: false, cards };
  const flipped = Object.assign({}, initial, { flippedCount: 1, cards: [
    Object.assign({}, cards[0], { flipped: true }), cards[1]
  ] });
  global.window = { location: { search: '?activity=230&tab=flashcards' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: require('../../main/resources/static/js/pages/interactive-study-mode.js') };
  global.document = { getElementById(id) { return id === 'interactiveStudyRoot' ? root : null; } };
  let flipWrites = 0;
  global.fetch = async (url) => {
    if (url.endsWith('/cards/1/flip')) flipWrites++;
    return { ok: true, json: async () => ({ data:
    url.endsWith('/micro-check/state') ? { checkEligible: false, passed: false } :
      url.endsWith('/cards/1/flip') ? flipped : initial }) };
  };
  try {
    page.mount();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(root.innerHTML, /class="ilp-card[^"]*"[^>]*data-card="1"/);
    const card = { dataset: { card: '1' }, closest(selector) { return selector === '[data-card]' ? card : null; } };
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-flipped is-turning"/);
    assert.match(root.innerHTML, /<span class="ilp-card__meaning">tôi<\/span>/);
    assert.match(root.innerHTML, /1\/2 trong phần này/);
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-turning"/);
    assert.equal(flipWrites, 1, 'turning back must not record a second viewed event');
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-flipped is-turning"/);
    assert.equal(flipWrites, 1, 'already viewed cards turn locally in either direction');

    const next = { dataset: { browseStep: '1' }, closest(selector) { return selector === '[data-browse-step]' ? next : null; } };
    await Promise.all((listeners.click || []).map(listener => listener({ target: next })));
    assert.match(root.innerHTML, /<h2 class="ilp-card__word">あなた<\/h2>/);
    assert.match(root.innerHTML, /2\/2 trong phần này/);
  } finally {
    global.window = previousWindow;
    global.document = previousDocument;
    global.fetch = previousFetch;
  }
});

test('flashcard has a two-sided turn with reduced-motion fallback', () => {
  const css = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcards', 0, { browseIndex: 1, browseFlipped: false });
  assert.match(html, /ilp-card__inner/);
  assert.match(html, /ilp-card__front/);
  assert.match(html, /ilp-card__back/);
  assert.match(css, /@keyframes ilp-card-turn-forward/);
  assert.match(css, /@keyframes ilp-card-turn-backward/);
  assert.match(css, /prefers-reduced-motion:\s*reduce/);
});

test('shows viewed-card progress instead of remember actions', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null);
  assert.match(html, /đã xem/);
  assert.doesNotMatch(html, /Đã nhớ|Chưa nhớ/);
});

test('allows early check after the UI confirmation and keeps post-failure review guidance', () => {
  const s = study();
  s.cards[0].flipped = true; s.cards[0].remembered = true;
  s.rememberedCount = 2; s.reviewRemaining = 0; s.checkEligible = true;
  assert.doesNotMatch(page.renderStudy(page.viewModel(s), null), /data-check disabled/);

  const gated = page.renderStudy(page.viewModel(s), { reviewRequired: true, checkEligible: false, passed: false });
  assert.match(gated, /data-check/);
  assert.match(gated, /cần ôn lại/);
});

test('communication vocabulary uses flashcards only and exposes no quiz action', () => {
  const html = page.renderStudy(page.viewModel(study({ checkRequired: false })), null);
  assert.match(html, /Xem hết các thẻ để hoàn thành phần Từ vựng của tình huống/);
  assert.doesNotMatch(html, /data-check|Bắt đầu kiểm tra|kiểm tra ngay/);
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

test('scored micro-check is locked against the inline dictionary, study view stays open (JLAS-W0126)', () => {
  const check = { attemptId: 9, batchIndex: 0, batchCount: 1, totalQuestions: 1, questions: [
    { questionId: 1, questionType: 'WORD_TO_MEANING', questionText: 'Từ "時間" có nghĩa là gì?', options: ['thời gian', 'trường học'] }
  ] };
  assert.match(page.renderCheck(check), /class="ilp-check no-inline-dictionary"/);
  assert.doesNotMatch(page.renderStudy(page.viewModel(study()), null), /no-inline-dictionary/);
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
  assert.match(html, /Hoàn thành phần trắc nghiệm và ghép các cặp từ – nghĩa/);
  assert.match(html, /data-check-progress>Đã trả lời 0\/0 câu trắc nghiệm · 0\/2 cặp nối/);
  assert.doesNotMatch(html, /Chưa nối|data-match-label/);
  assert.match(html, /aria-pressed="false"/);
  assert.doesNotMatch(html, /correctOption|answerKey/);
});

test('quiz progress distinguishes numbered questions from matching pairs', () => {
  const check = { attemptId: 8, batchIndex: 0, batchCount: 1, questions: [
    { questionId: 1, questionType: 'MULTIPLE_CHOICE', questionText: '学生 nghĩa là gì?', options: ['học sinh', 'giáo viên'] },
    { questionId: 2, questionType: 'MULTIPLE_CHOICE', questionText: '静か nghĩa là gì?', options: ['nhiều', 'yên tĩnh'] },
    { questionId: 3, questionType: 'MATCHING', questionText: '会社員', options: ['giỏi', 'nhân viên công ty'] },
    { questionId: 4, questionType: 'MATCHING', questionText: '静か', options: ['giỏi', 'nhân viên công ty'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /Đã trả lời 0\/2 câu trắc nghiệm · 0\/2 cặp nối/);
  assert.doesNotMatch(html, /Đã trả lời 0\/4 câu/);
});

test('vocabulary quiz counter is inside the submitted form so answer changes can update it', () => {
  const html = page.renderCheck({ attemptId: 8, batchIndex: 0, batchCount: 1, questions: [
    { questionId: 1, questionType: 'MULTIPLE_CHOICE', questionText: '私 nghĩa là gì?', options: ['tôi', 'bạn'] }
  ] });
  const form = html.slice(html.indexOf('<form '), html.indexOf('</form>'));
  assert.match(form, /data-check-progress/);
  assert.match(form, /name="q-1"/);
});

test('changing a matching pair never clears an already selected multiple-choice answer', () => {
  const mcq = { name: 'q-1', value: '2', checked: true };
  const oldPair = { name: 'q-11', value: '2', checked: true };
  const newPair = { name: 'q-12', value: '2', checked: false };
  const form = {
    querySelectorAll(selector) {
      const radios = selector.startsWith('.ilp-matching__answers') ? [oldPair, newPair] : [mcq, oldPair, newPair];
      return radios.filter(radio => radio.checked);
    },
    querySelector(selector) { return selector.includes('q-12') && selector.includes('value="2"') ? newPair : null; }
  };
  page.selectMatchingAnswer(form, '12', '2');
  assert.equal(mcq.checked, true);
  assert.equal(oldPair.checked, false);
  assert.equal(newPair.checked, true);
});

test('a selected matched word gets a distinct edit highlight above its completed-pair state', () => {
  const css = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  const selectedRule = css.lastIndexOf('.ilp-matching__column button.is-matched.is-selected');
  const matchedRule = css.indexOf('.ilp-matching__column button.is-matched{');
  assert.ok(selectedRule >= 0 && matchedRule >= 0 && selectedRule > matchedRule,
    'selected styling must be declared after matched styling so a completed pair can be edited visibly');
});

test('[JLAS-W0108] matching columns stay in a stable order within one attempt and render every pair once', () => {
  const check = { attemptId: 42, batchIndex: 0, batchCount: 1, questions: [
    { questionId: 11, questionType: 'MATCHING', questionText: '学校', options: ['trường học', 'thời gian', 'giáo viên', 'học sinh'] },
    { questionId: 12, questionType: 'MATCHING', questionText: '時間', options: ['trường học', 'thời gian', 'giáo viên', 'học sinh'] },
    { questionId: 13, questionType: 'MATCHING', questionText: '先生', options: ['trường học', 'thời gian', 'giáo viên', 'học sinh'] },
    { questionId: 14, questionType: 'MATCHING', questionText: '学生', options: ['trường học', 'thời gian', 'giáo viên', 'học sinh'] }
  ] };
  const first = page.renderCheck(check);
  const second = page.renderCheck(check);
  // Re-render/back/reload/resume of the same attempt must keep the exact same column order.
  assert.equal(first, second);
  // Both columns are complete and unique: every word id and every meaning letter (A–D) appears once.
  assert.equal((first.match(/data-match-word="1[1-4]"/g) || []).length, 4);
  const meaningValues = (first.match(/data-match-meaning="(\d)"/g) || []).map(m => m.slice(-2, -1)).sort();
  assert.deepEqual(meaningValues, ['1', '2', '3', '4']);
  // A fresh attempt is re-shuffled independently (documented as best-effort rotation; not asserting a specific order).
  assert.match(first, /aria-pressed="false"/);
  assert.doesNotMatch(first, /correctOption|answerKey|data-answer/);
});

test('[JLAS-W0108] a multi-part quiz names the exact next part on the study view', () => {
  const model = page.viewModel(study({ checkEligible: true }));
  const render = (gate) => page.renderStudy(model, gate, 'flashcards', 0, { mode: 'check' });

  assert.match(render({ batchCount: 3, currentBatch: 1, checkEligible: true, passed: false, reviewRequired: false }),
    /Tiếp tục kiểm tra — phần 2\/3/);
  assert.match(render({ batchCount: 3, currentBatch: 0, checkEligible: true, passed: false, reviewRequired: false }),
    /Bắt đầu kiểm tra — phần 1\/3/);
  assert.match(render({ batchCount: 1, currentBatch: 0, checkEligible: true, passed: false, reviewRequired: false }),
    /Bắt đầu kiểm tra →/);
  assert.doesNotMatch(render({ batchCount: 3, currentBatch: 1, checkEligible: true, passed: false, reviewRequired: false }),
    /Bắt đầu kiểm tra →/);
});

test('failed quiz result returns to the lesson tabs; passed result uses read-only lesson review', () => {
  const failed = page.renderResult({ totalQuestions: 2, correctAnswers: 1, scorePercent: 50, passed: false,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, activityState: 'CHECK_READY', review: [] });
  const passed = page.renderResult({ totalQuestions: 2, correctAnswers: 2, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, activityState: 'PASSED', review: [] });
  assert.match(failed, /href="\/learning\/v2\/vocabulary\?activity=[^\"]*&amp;tab=content">Quay lại bài học/);
  assert.doesNotMatch(failed, /mode=review/);
  assert.match(passed, /mode=review&amp;tab=content">Xem lại bài giảng/);
});

test('submitting an incomplete quiz warns that blank questions are scored incorrect', () => {
  const js = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/js/pages/interactive-study.js'), 'utf8');
  assert.match(js, /Bạn còn .* câu chưa trả lời/);
  assert.match(js, /Câu bỏ trống sẽ bị tính sai/);
});

test('matching selection and completed pairs use visible highlighted states without status text', () => {
  const js = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/js/pages/interactive-study.js'), 'utf8');
  const css = fs.readFileSync(pathModule.join(__dirname, '../../main/resources/static/css/pages/interactive-study.css'), 'utf8');
  assert.match(js, /classList\.toggle\('is-matched'/);
  assert.match(js, /classList\.toggle\('is-selected'/);
  assert.match(js, /setTimeout\(function \(\) \{[\s\S]*updateCheckProgress\(form\)/);
  assert.equal(page.checkProgressText(2, 10, 5, 5), 'Đã trả lời 2/10 câu trắc nghiệm · 5/5 cặp nối');
  assert.match(css, /\.ilp-matching__column button\.is-selected[^{}]*\{/);
  assert.match(css, /\.ilp-matching__column button\.is-matched[^{}]*\{/);
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

test('[JLAS-W0113] autosaved drafts re-mark saved choices and matching pairs, leaving others blank', () => {
  const check = { attemptId: 42, batchIndex: 0, batchCount: 1, draftAnswers: [
    { questionId: 1, selectedOption: 2 },
    { questionId: 11, selectedOption: 1 }
  ], questions: [
    { questionId: 1, questionType: 'MULTIPLE_CHOICE', questionText: '学校 nghĩa là gì?', options: ['thời gian', 'trường học'] },
    { questionId: 11, questionType: 'MATCHING', questionText: '学校', options: ['trường học', 'thời gian'] },
    { questionId: 12, questionType: 'MATCHING', questionText: '時間', options: ['trường học', 'thời gian'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /name="q-1" value="2" checked>/);
  assert.match(html, /name="q-11" value="1" checked>/);
  assert.doesNotMatch(html, /name="q-12" value="1" checked>/);
  assert.match(html, /data-draft-status/);
});

test('[JLAS-W0113] a draft that only unselects is stored as the empty selection set', () => {
  const html = page.renderCheck({ attemptId: 42, batchIndex: 0, batchCount: 1, draftAnswers: [
    { questionId: 1, selectedOption: null }
  ], questions: [
    { questionId: 1, questionType: 'MULTIPLE_CHOICE', questionText: '学校 nghĩa là gì?', options: ['thời gian', 'trường học'] }
  ] });
  assert.doesNotMatch(html, /name="q-1" value="\d+" checked>/);
  assert.match(html, /data-draft-status/);
});
