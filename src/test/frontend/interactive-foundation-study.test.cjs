const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
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

test('unfinished alphabet activity cannot offer a practice quiz', async () => {
  const savedWindow = global.window, savedDocument = global.document, savedFetch = global.fetch;
  const root = { innerHTML: '', addEventListener() {}, setAttribute() {} };
  global.window = { location: { search: '?activity=1&mode=practice' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: require('../../main/resources/static/js/pages/interactive-study-mode.js') };
  global.document = { getElementById(id) { return id === 'interactiveFoundationStudyRoot' ? root : null; } };
  global.fetch = async () => ({ ok: true, json: async () => ({ data: study() }) });
  try {
    page.mount();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(root.innerHTML, /chưa hoàn thành/i);
    assert.doesNotMatch(root.innerHTML, /data-check/);
  } finally { global.window = savedWindow; global.document = savedDocument; global.fetch = savedFetch; }
});

test('sorts cards by order and selects the first unviewed card as current', () => {
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

test('foundation review returns to the two-tab lesson view without quiz attempt history', () => {
  assert.equal(typeof page.renderReview, 'function');
  if (typeof page.renderReview !== 'function') return;
  const html = page.renderReview(page.viewModel(study()), 'content', { browseIndex: 0 });
  assert.match(html, /data-study-tab="content"/);
  assert.match(html, /data-study-tab="flashcard"/);
  assert.match(html, /data-kana-list/);
  assert.doesNotMatch(html, /Kết quả các lượt làm bài|data-feedback-batch|data-remember=|data-check/);
  assert.match(html, /data-browse-card/);
});

test('flashcard is tap-to-flip, has pronunciation control, and does not reveal romaji on the front', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard');
  assert.match(html, /か/);
  assert.match(html, /data-card="301"/);
  assert.match(html, /data-pronounce="か"/);
  assert.doesNotMatch(html, /Lật thẻ/);
  assert.doesNotMatch(html, /Đã nhớ/);
  const card = html.match(/<article class="ilp-card[\s\S]*?<\/article>/)[0];
  assert.match(card, /class="ilp-card__back" aria-hidden="true" inert>[\s\S]*?>ka</);
});

test('back reveals the romaji and marks the card viewed', () => {
  const s = study();
  s.cards[0].flipped = true;
  const html = page.renderStudy(page.viewModel(s), null, 'flashcard');
  assert.match(html, />ka</);
  assert.match(html, /đã xem/);
  assert.doesNotMatch(html, /Đã nhớ|Chưa nhớ/);
});

test('labels the script from the character itself (Hiragana vs Katakana)', () => {
  assert.equal(page.scriptLabel('あ'), 'HIRAGANA');
  assert.equal(page.scriptLabel('ア'), 'KATAKANA');
  assert.equal(page.scriptLabel('ん'), 'HIRAGANA');
  assert.equal(page.scriptLabel('ン'), 'KATAKANA');
  assert.equal(page.scriptLabel(''), 'BẢNG CHỮ CÁI');
  assert.match(page.cardFace({ memberId: 1, character: 'ア', romaji: 'a', flipped: false }), /KATAKANA/);
});

test('keeps quiz available before all cards are viewed and preserves the cooldown review gate', () => {
  const s = study();
  const early = page.renderStudy(page.viewModel(s), null, 'flashcard');
  assert.match(early, /data-check/);
  assert.doesNotMatch(early, /data-check disabled/);

  const gated = page.renderStudy(page.viewModel(s), { reviewRequired: true, checkEligible: false, passed: false });
  assert.match(gated, /data-check/);
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

test('renders saved quiz attempt history inside the quiz result, not the lesson review page', () => {
  const history = '<section class="ilp-attempt-history" aria-label="Lịch sử kết quả bài kiểm tra"><h2>Các lần làm bài</h2><button>Lần 2 · 80%</button></section>';
  const html = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 2, attemptsRemaining: 1, reviewRequired: false,
    activityState: 'PASSED', review: [] }, { activityId: 42, attemptHistoryHtml: history });
  assert.ok(html.indexOf('KẾT QUẢ') < html.indexOf('Lịch sử kết quả bài kiểm tra'));
  assert.match(html, /Lần 2 · 80%/);
});

test('quiz pass result links back into the original lesson content and roadmap', () => {
  const html = page.renderResult({ totalQuestions: 10, correctAnswers: 8, scorePercent: 80, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] }, { activityId: 42 });
  assert.match(html, /href="\/learning\/v2\/foundation\?activity=42&amp;mode=review&amp;tab=content"/);
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

test('flashcard tab only offers card browsing and quiz check', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard');
  assert.match(html, /data-card="301"/);
  assert.match(html, /data-check/);
  assert.doesNotMatch(html, /Ghi nhớ|Gõ phản xạ|data-card-mode/);
});

test('browse mode is a large tap-to-flip server-tracked card', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'browse', browseIndex: 0 });
  assert.match(html, /data-card="300"/);
  assert.doesNotMatch(html, /data-browse-card/);
  assert.match(html, /1\/2/);
  assert.match(html, /data-browse-step="-1" disabled/);
  const flippedStudy = study({ cards: study().cards.map(card => card.memberId === 301 ? { ...card, flipped: true } : card) });
  const flipped = page.renderStudy(page.viewModel(flippedStudy), null, 'flashcard', { mode: 'browse', browseIndex: 1 });
  assert.match(flipped, /class="ilp-card" data-card="301"/);
  assert.match(flipped, /1\/2<\/strong>/);
  assert.match(flipped, /data-browse-step="1" disabled/);
});

test('a viewed Kana card can show its front again without losing viewed progress', () => {
  const model = page.viewModel(study());
  const front = page.renderStudy(model, null, 'flashcard', { browseIndex: 0, browseFlipped: false });
  const back = page.renderStudy(model, null, 'flashcard', { browseIndex: 0, browseFlipped: true, turning: true });
  assert.doesNotMatch(front, /class="ilp-card is-flipped/);
  assert.match(back, /class="ilp-card is-flipped is-turning/);
  assert.match(back, /1\/2/);
});

test('Kana first reveal stays on the same card; turning back does not write again', async () => {
  const savedWindow = global.window, savedDocument = global.document, savedFetch = global.fetch;
  const listeners = {};
  const root = { innerHTML: '', addEventListener(type, listener) { (listeners[type] ||= []).push(listener); }, setAttribute() {} };
  const initial = study({ flippedCount: 0, cards: study().cards.map(card => ({ ...card, flipped: false })) });
  const viewed = study({ flippedCount: 1, cards: study().cards.map(card => ({ ...card, flipped: card.memberId === 300 })) });
  let writes = 0;
  global.window = { location: { search: '?activity=1&tab=flashcard' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: require('../../main/resources/static/js/pages/interactive-study-mode.js') };
  global.document = { getElementById(id) { return id === 'interactiveFoundationStudyRoot' ? root : null; },
    querySelector() { return null; } };
  global.fetch = async url => {
    if (url.endsWith('/cards/300/flip')) writes++;
    return { ok: true, json: async () => ({ data: url.endsWith('/micro-check/state') ? {} :
      url.endsWith('/cards/300/flip') ? viewed : initial }) };
  };
  try {
    page.mount();
    await new Promise(resolve => setImmediate(resolve));
    const card = { dataset: { card: '300' }, closest(selector) { return selector === '[data-card]' ? card : null; } };
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-flipped is-turning"/);
    assert.match(root.innerHTML, /1\/2<\/strong>/);
    await Promise.all((listeners.click || []).map(listener => listener({ target: card })));
    assert.match(root.innerHTML, /class="ilp-card is-turning"/);
    assert.equal(writes, 1);
  } finally { global.window = savedWindow; global.document = savedDocument; global.fetch = savedFetch; }
});

test('typing mode is no longer offered in the lesson flashcard flow', () => {
  const html = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'type', browseIndex: 0 });
  assert.doesNotMatch(html, /data-typed-recall|Gõ phản xạ/);
});

test('check mode is offered early; confirmation is enforced by the mounted page', () => {
  const blocked = page.renderStudy(page.viewModel(study()), null, 'flashcard', { mode: 'check' });
  assert.match(blocked, /data-check>/);
  const ready = page.renderStudy(page.viewModel(study({ checkEligible: true })), null, 'flashcard', { mode: 'check' });
  assert.match(ready, /data-check>/);
});

test('typed romaji is compared case- and width-insensitively, never accepting an empty key', () => {
  assert.equal(page.checkTypedRomaji('ka', ' KA '), true);
  assert.equal(page.checkTypedRomaji('shi', 'ｓｈｉ'), true);
  assert.equal(page.checkTypedRomaji('ka', 'ki'), false);
  assert.equal(page.checkTypedRomaji('', ''), false);
});

test('word-level Kana examples show their real lesson, full word, reading and Vietnamese meaning', () => {
  const word = study({ contentKind: 'EXAMPLE_WORD', lessonTitle: 'Âm ngắt với っ / ッ',
    lessonDescription: 'Đọc cả từ, chú ý âm ngắt.', totalCards: 1, cards: [
      { memberId: 901, orderIndex: 1, character: 'きっぷ', romaji: 'kippu', meaningVi: 'vé', flipped: false, remembered: false }
    ] });
  const model = page.viewModel(word);
  const lesson = page.renderStudy(model, null);
  assert.match(lesson, /Âm ngắt với っ \/ ッ/);
  assert.match(lesson, /Đọc cả từ, chú ý âm ngắt/);
  assert.match(lesson, /きっぷ/);
  assert.match(lesson, /kippu/);
  assert.match(lesson, /vé/);
  assert.match(lesson, /data-pronounce="きっぷ"/);
  assert.doesNotMatch(lesson, /Các chữ trong phần này|Nghe từng chữ/);
  const front = page.renderStudy(model, null, 'flashcard');
  assert.match(front, /data-pronounce="きっぷ"/);
  assert.doesNotMatch(front, /ilp-card__word--kanji/);
  word.cards[0].flipped = true;
  assert.match(page.renderStudy(page.viewModel(word), null, 'flashcard'), /vé/);
  assert.match(page.renderReview(model), /Âm ngắt với っ \/ ッ/);
});

test('word-level Kana quiz names the lesson rather than the character chart', () => {
  const check = { attemptId: 902, activityId: 9, batchIndex: 0, batchCount: 1, questions: [
    { questionId: 1, questionType: 'WORD_TO_ROMAJI', questionText: 'Từ “きっぷ” đọc là gì?', options: ['kippu', 'kitte'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /Kiểm tra nhanh từ ví dụ Kana/);
  assert.match(html, /Từ “きっぷ” đọc là gì/);
  assert.doesNotMatch(html, /Kiểm tra nhanh Bảng chữ cái/);
});

test('all 26 draft word examples retain source order and reach the lesson UI with full-word audio targets', (t) => {
  const dataPath = path.join(__dirname, '../../../data/learning-owned/learning-content.json');
  if (!fs.existsSync(dataPath)) { t.skip('draft data file not present'); return; }
  const draft = JSON.parse(fs.readFileSync(dataPath, 'utf8')).sections.kana_special_sounds;
  let seen = 0;
  for (const lesson of draft.lessons) {
    const cards = lesson.examples.map((item, i) => ({ memberId: i + 1, orderIndex: i + 1,
      character: item.written, romaji: item.romaji, meaningVi: item.meaning_vi,
      flipped: false, remembered: false }));
    const html = page.renderStudy(page.viewModel(study({ contentKind: 'EXAMPLE_WORD', lessonTitle: lesson.title,
      lessonDescription: lesson.rule_vi, totalCards: cards.length, cards })), null);
    const list = html.match(/<ul class="ilp-kana-list[^>]*>[\s\S]*?<\/ul>/)[0];
    for (const item of lesson.examples) {
      assert.ok(list.includes(item.written), item.id + ' missing written form');
      assert.ok(list.includes(item.romaji), item.id + ' missing romaji');
      assert.ok(list.includes(item.meaning_vi), item.id + ' missing meaning');
      assert.ok(list.includes('data-pronounce="' + item.written + '"'), item.id + ' missing whole-word TTS target');
      seen++;
    }
    assert.ok(cards.length <= 15, lesson.id + ' must fit the approved per-part limit');
  }
  assert.equal(seen, 26);
});

test('Kana word pronunciation sends the whole Japanese word to browser speech, not separate characters', () => {
  const originalWindow = global.window;
  let spoken;
  global.window = { SpeechSynthesisUtterance: function (text) { this.text = text; },
    speechSynthesis: { cancel() {}, speak(utterance) { spoken = utterance; } } };
  try {
    assert.equal(page.speakJapanese('きっぷ'), true);
    assert.equal(spoken.text, 'きっぷ');
    assert.equal(spoken.lang, 'ja-JP');
  } finally {
    if (originalWindow === undefined) delete global.window;
    else global.window = originalWindow;
  }
});
