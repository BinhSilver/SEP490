const test = require('node:test');
const assert = require('node:assert/strict');
const studyMode = require('../../main/resources/static/js/pages/interactive-study-mode.js');
const foundation = require('../../main/resources/static/js/pages/interactive-foundation-study.js');
const kanji = require('../../main/resources/static/js/pages/interactive-kanji-study.js');
const grammar = require('../../main/resources/static/js/pages/interactive-grammar-study.js');
const comprehension = require('../../main/resources/static/js/pages/interactive-comprehension-study.js');
const vocabulary = require('../../main/resources/static/js/pages/interactive-study.js');

async function mountPractice(page, rootId, content) {
  const previousWindow = global.window;
  const previousDocument = global.document;
  const previousFetch = global.fetch;
  const requests = [];
  const listeners = {};
  const node = {
    innerHTML: '',
    addEventListener(type, listener) { (listeners[type] ||= []).push(listener); },
    setAttribute() {},
    querySelector() { return null; }
  };
  global.window = {
    location: { search: '?activity=17&mode=practice', pathname: '/learning/v2/listening' },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } },
    InteractiveStudyMode: studyMode
  };
  global.document = {
    getElementById(id) { return id === rootId ? node : null; },
    querySelector() { return null; }
  };
  global.fetch = async (url, options = {}) => {
    requests.push({ url, method: options.method || 'GET' });
    if (url.endsWith('/review') || url.endsWith('/review/attempts'))
      return { ok: true, json: async () => ({ data: content }) };
    if (url.endsWith('/practice')) return { ok: true, json: async () => ({ data: { attemptId: 91, questions: [] } }) };
    throw new Error('Unexpected request: ' + url);
  };
  page.mount();
  await new Promise(resolve => setImmediate(resolve));
  return {
    node, requests,
    async click(selector, dataset = {}) {
      const target = { dataset, closest(value) { return value === selector ? target : null; } };
      for (const listener of listeners.click || []) await listener({ target });
    },
    cleanup() {
      global.window = previousWindow;
      global.document = previousDocument;
      global.fetch = previousFetch;
    }
  };
}

test('Foundation practice starts with local flashcards, not an issued quiz', async () => {
  const page = await mountPractice(foundation, 'interactiveFoundationStudyRoot', {
    activityId: 17, activityState: 'PASSED', contentKind: 'CHARACTER', cards: [{ memberId: 4, character: 'あ', romaji: 'a', orderIndex: 1 }]
  });
  try {
    assert.match(page.node.innerHTML, /Chế độ luyện tập/);
    assert.match(page.node.innerHTML, /data-study-tab="flashcard"/);
    assert.match(page.node.innerHTML, /aria-selected="true" data-study-tab="flashcard"/);
    assert.deepEqual(page.requests.map(request => request.method + ' ' + request.url.split('/').pop()), ['GET review']);
  } finally { page.cleanup(); }
});

test('Foundation practice flip and remember stay local, then the result action restarts flashcards', async () => {
  const page = await mountPractice(foundation, 'interactiveFoundationStudyRoot', {
    activityId: 17, activityState: 'PASSED', contentKind: 'CHARACTER', cards: [{ memberId: 4, character: 'あ', romaji: 'a', orderIndex: 1 }]
  });
  try {
    await page.click('[data-card]', { card: '4' });
    await page.click('[data-remember]', { remember: '4', value: 'true' });
    assert.match(page.node.innerHTML, /1\/1/);
    assert.deepEqual(page.requests.map(request => request.method), ['GET']);
    page.node.innerHTML = studyMode.practiceResult({ scorePercent: 50, correctAnswers: 1, totalQuestions: 2 });
    await page.click('[data-practice-review]');
    assert.match(page.node.innerHTML, /Học bằng Flashcard/);
    assert.match(page.node.innerHTML, /0\/1/);
    assert.deepEqual(page.requests.map(request => request.method), ['GET', 'GET']);
  } finally { page.cleanup(); }
});

test('Kanji practice result action returns to flashcards without issuing another quiz', async () => {
  const page = await mountPractice(kanji, 'interactiveKanjiStudyRoot', {
    activityId: 17, activityState: 'PASSED', cards: [{ memberId: 4, character: '学', meaningVi: 'học', orderIndex: 1 }]
  });
  try {
    page.node.innerHTML = studyMode.practiceResult({ scorePercent: 50, correctAnswers: 1, totalQuestions: 2 });
    await page.click('[data-practice-review]');
    assert.match(page.node.innerHTML, /Học bằng Flashcard/);
    assert.match(page.node.innerHTML, /aria-selected="true" data-study-tab="flashcards"/);
    assert.deepEqual(page.requests.map(request => request.method), ['GET', 'GET']);
  } finally { page.cleanup(); }
});

test('Grammar practice reopens a read-only lesson before the next quiz, with no progress writes', async () => {
  const page = await mountPractice(grammar, 'interactiveGrammarStudyRoot', {
    activityId: 17, totalPoints: 1, points: [{ pointId: 5, pattern: '〜ている', objective: 'Trạng thái',
      ordering: { words: [{ tokenId: 1, text: 'います' }] }, fill: { promptWithBlank: '___' } }]
  });
  try {
    assert.match(page.node.innerHTML, /〜ている/);
    assert.match(page.node.innerHTML, /data-check/);
    assert.doesNotMatch(page.node.innerHTML, /data-order-check|data-fill-check/);
    page.node.innerHTML = studyMode.practiceResult({ scorePercent: 50, correctAnswers: 1, totalQuestions: 2 }, 'Ôn bài rồi kiểm tra lại');
    await page.click('[data-practice-review]');
    assert.match(page.node.innerHTML, /〜ている/);
    assert.deepEqual(page.requests.map(request => request.method), ['GET', 'GET']);
  } finally { page.cleanup(); }
});

test('Listening practice opens frozen lesson audio before issuing a quiz', async () => {
  const feedback = [{ batchIndex: 0, sourceStatus: 'AVAILABLE', source: { audioUrl: '/audio/lesson-1.mp3' } }];
  const page = await mountPractice(comprehension, 'interactiveComprehensionStudyRoot', feedback);
  try {
    assert.match(page.node.innerHTML, /lesson-1\.mp3/);
    assert.match(page.node.innerHTML, /data-check/);
    assert.doesNotMatch(page.node.innerHTML, /data-submit|data-reached-passage/);
    assert.deepEqual(page.requests.map(request => request.method), ['GET']);
    page.node.innerHTML = studyMode.practiceResult({ scorePercent: 50, correctAnswers: 1, totalQuestions: 2 }, 'Ôn bài rồi kiểm tra lại');
    await page.click('[data-practice-review]');
    assert.match(page.node.innerHTML, /lesson-1\.mp3/);
    assert.deepEqual(page.requests.map(request => request.method), ['GET', 'GET']);
    await page.click('[data-check]');
    assert.deepEqual(page.requests.map(request => request.method + ' ' + request.url.split('/').pop()),
      ['GET attempts', 'GET attempts', 'POST practice']);
  } finally { page.cleanup(); }
});

test('Reading practice presents its frozen passage without recording reading evidence', () => {
  const html = comprehension.renderPracticeSources([
    { batchIndex: 0, sourceStatus: 'AVAILABLE', source: { passage: '駅へ行きます。' } }
  ], 'READING');
  assert.match(html, /駅へ行きます。/);
  assert.match(html, /data-check/);
  assert.doesNotMatch(html, /data-reached-passage|data-submit|data-quiz-history/);
});

test('each quiz skill reopens saved results after reload with one read-only history request', async () => {
  const pages = [
    [foundation, 'interactiveFoundationStudyRoot', 'foundation'],
    [vocabulary, 'interactiveStudyRoot', 'vocabulary'],
    [kanji, 'interactiveKanjiStudyRoot', 'kanji'],
    [grammar, 'interactiveGrammarStudyRoot', 'grammar'],
    [comprehension, 'interactiveComprehensionStudyRoot', 'reading'],
    [comprehension, 'interactiveComprehensionStudyRoot', 'listening']
  ];
  for (const [page, rootId, kind] of pages) {
    const oldWindow = global.window, oldDocument = global.document, oldFetch = global.fetch;
    const requests = [];
    const node = { innerHTML: '', addEventListener() {}, setAttribute() {}, querySelector() { return null; } };
    global.window = { location: { search: '?activity=17&mode=results', pathname: '/learning/v2/' + kind },
      JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } }, InteractiveStudyMode: studyMode };
    global.document = { getElementById(id) { return id === rootId ? node : null; }, querySelector() { return null; } };
    global.fetch = async (url, options = {}) => {
      requests.push({ url, method: options.method || 'GET' });
      if (!url.endsWith('/attempt-history')) throw new Error('Unexpected request: ' + url);
      return { ok: true, json: async () => ({ data: [{ attemptId: 1, attemptNumber: 1, batchIndex: 0,
        mode: 'LEARN', scorePercent: 60, correctAnswers: 3, totalQuestions: 5, review: [] }] }) };
    };
    try {
      page.mount();
      await new Promise(resolve => setImmediate(resolve));
      assert.deepEqual(requests.map(r => r.method + ' ' + r.url.split('/').pop()), ['GET attempt-history'], kind);
      assert.match(node.innerHTML, /Lịch sử kết quả bài kiểm tra/, kind);
      assert.match(node.innerHTML, /Lần 1/, kind);
      assert.doesNotMatch(node.innerHTML, /data-check|data-submit/, kind);
    } finally {
      global.window = oldWindow; global.document = oldDocument; global.fetch = oldFetch;
    }
  }
});

test('legacy quiz history identifies a missing saved question instead of implying its text survived', () => {
  const html = studyMode.attemptHistory([{ attemptId: 5, attemptNumber: 1, batchIndex: 0,
    mode: 'LEARN', scorePercent: 0, correctAnswers: 0, totalQuestions: 1,
    review: [{ questionId: 99, questionText: null, correct: false,
      correctAnswerText: 'đúng', selectedAnswerText: 'sai' }] }]);
  assert.match(html, /Nội dung câu hỏi của lượt này không còn được lưu/);
  assert.match(html, /Đáp án: đúng/);
  assert.doesNotMatch(html, /<strong>Câu 99<\/strong>/);
});

test('quiz result lesson links use read-only review for flashcard skills', () => {
  const oldWindow = global.window;
  global.window = { location: { search: '?activity=17' } };
  const result = { totalQuestions: 5, correctAnswers: 5, scorePercent: 100, passed: true,
    passThresholdPercent: 70, activityState: 'PASSED', review: [] };
  try {
    for (const [kind, html] of [
      ['foundation', foundation.renderResult(result, { activityId: 17 })],
      ['vocabulary', vocabulary.renderResult(result)],
      ['kanji', kanji.renderResult(result)]
    ]) {
      assert.match(html, new RegExp('/learning/v2/' + kind + '\\?activity=17&amp;mode=review&amp;tab=content'), kind);
    }
  } finally { global.window = oldWindow; }
});
