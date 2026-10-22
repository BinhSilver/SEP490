const test = require('node:test');
const assert = require('node:assert/strict');
const studyMode = require('../../main/resources/static/js/pages/interactive-study-mode.js');

function resultNode() {
  const link = { href: '/learning/path/v2', textContent: 'Về lộ trình' };
  const actions = {
    querySelector(selector) { return selector === 'a[href="/learning/path/v2"]' ? link : null; },
    insertAdjacentHTML() {}
  };
  return { link, node: { querySelector(selector) {
    if (selector === '.ilp-result__actions a[href="/learning/path/v2"]') return link;
    return selector === '.ilp-result__actions' ? actions : null;
  } } };
}

test('last completed activity leads to the path summary', async () => {
  const oldWindow = global.window;
  const oldFetch = global.fetch;
  const { link, node } = resultNode();
  global.window = { JlasAuth: { authHeaders: () => ({ Authorization: 'Bearer test' }) } };
  global.fetch = async () => ({ ok: true, json: async () => ({ data: {
    completedPackages: 2, totalPackages: 2, packages: []
  } }) });
  try {
    await studyMode.showNextAction(node, 99);
    assert.equal(link.href, '/learning/path/v2/summary');
    assert.equal(link.textContent, 'Xem tổng kết lộ trình');
  } finally {
    global.window = oldWindow;
    global.fetch = oldFetch;
  }
});

test('completed path summary remains reachable after current path returns 404', async () => {
  const oldWindow = global.window;
  const oldFetch = global.fetch;
  const { link, node } = resultNode();
  const calls = [];
  global.window = { JlasAuth: { authHeaders: () => ({ Authorization: 'Bearer test' }) } };
  global.fetch = async (url) => {
    calls.push(url);
    if (url.endsWith('/summary')) return { ok: true, json: async () => ({ data: { status: 'COMPLETED' } }) };
    return { ok: false, status: 404 };
  };
  try {
    await studyMode.showNextAction(node, 99);
    assert.deepEqual(calls, ['/api/learning/v2/paths/current', '/api/learning/v2/paths/current/summary']);
    assert.equal(link.href, '/learning/path/v2/summary');
  } finally {
    global.window = oldWindow;
    global.fetch = oldFetch;
  }
});

test('reopening a completed lesson restores the saved score and answer review', async () => {
  const oldWindow = global.window;
  const oldFetch = global.fetch;
  const node = { innerHTML: '', querySelector: () => null };
  const requests = [];
  global.window = { JlasAuth: { authHeaders: () => ({ Authorization: 'Bearer test' }) } };
  global.fetch = async () => ({ ok: false, status: 500 });
  try {
    await studyMode.restoreCompletedResult(node, async (path) => {
      requests.push(path);
      return [{ mode: 'LEARN', passed: true, scorePercent: 80, correctAnswers: 4,
        totalQuestions: 5, attemptNumber: 1, batchIndex: 0,
        review: [{ questionText: 'Câu đã làm', correct: true }] }];
    }, '/learning/v2/reading', 42, 'fallback');
    assert.deepEqual(requests, ['/attempt-history']);
    assert.match(node.innerHTML, /80% \(4\/5\)/);
    assert.match(node.innerHTML, /Câu đã làm/);
    assert.match(node.innerHTML, /Về lộ trình/);
    assert.match(node.innerHTML, /mode=review/);
  } finally {
    global.window = oldWindow;
    global.fetch = oldFetch;
  }
});

test('completed lesson without a saved passing attempt keeps its fallback', async () => {
  const node = { innerHTML: '' };
  await studyMode.restoreCompletedResult(node, async () => [], '/learning/v2/reading', 42, 'fallback');
  assert.equal(node.innerHTML, 'fallback');
});

// Owner 2026-10-21: finishing a part keeps the page and adds a congratulation popup instead of an empty screen.
test('the congratulation popup is a labelled dialog with a roadmap link and a way to stay on the page', () => {
  const html = studyMode.celebrationHtml('Chúc mừng!', 'Bạn đã hoàn thành phần <Từ vựng>.');
  assert.match(html, /role="dialog" aria-modal="true" aria-labelledby="ilpCelebrateTitle"/);
  assert.match(html, /<h2 id="ilpCelebrateTitle">Chúc mừng!<\/h2>/);
  assert.match(html, /Bạn đã hoàn thành phần &lt;Từ vựng&gt;\./);
  assert.match(html, /class="ilp-result__actions"><a class="ilp-check" href="\/learning\/path\/v2">Về lộ trình<\/a>/);
  assert.match(html, /data-celebrate-close>Ở lại trang này</);
  assert.match(html, /<canvas class="ilp-celebrate__canvas" aria-hidden="true">/);
});
