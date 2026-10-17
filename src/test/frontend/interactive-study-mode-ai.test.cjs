const test = require('node:test');
const assert = require('node:assert/strict');
const studyMode = require('../../main/resources/static/js/pages/interactive-study-mode.js');

test('the AI explanation button sends the saved attempt question and displays the response', async () => {
  const oldWindow = global.window;
  const oldFetch = global.fetch;
  const explanation = { textContent: '', hidden: true };
  const button = {
    dataset: { attemptId: '12', questionId: '77' }, disabled: false, textContent: '',
    closest(selector) {
      return selector === 'li' ? { querySelector: () => explanation } : null;
    }
  };
  let clickHandler;
  let sent;
  const node = {
    addEventListener(type, listener) { if (type === 'click') clickHandler = listener; },
    contains(candidate) { return candidate === button; },
    querySelectorAll() { return []; }
  };
  global.window = {
    location: { search: '?activity=50' },
    JlasAuth: { authHeaders: () => ({ Authorization: 'Bearer test' }) }
  };
  global.fetch = async (url, options) => {
    sent = { url, options };
    return { ok: true, json: async () => ({ success: true, data: {
      explanation: '静か là tính từ ナ.', correctAnswerTranslationVi: 'yên tĩnh',
      exampleJa: 'この町は静かです。', exampleVi: 'Thị trấn này yên tĩnh.',
      scriptTranslationVi: 'Người nói bảo thị trấn này yên tĩnh.'
    } }) };
  };
  try {
    studyMode.bindAttemptHistory(node);
    clickHandler({ target: { closest: (selector) => selector === '[data-ai-explain]' ? button : null } });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(sent.url, '/api/learning/v2/activities/50/attempts/12/questions/77/explanation');
    assert.equal(sent.options.method, 'POST');
    assert.match(explanation.textContent, /静か là tính từ ナ/);
    assert.match(explanation.textContent, /Nghĩa đáp án: yên tĩnh/);
    assert.match(explanation.textContent, /Ví dụ: この町は静かです。/);
    assert.match(explanation.textContent, /Dịch: Thị trấn này yên tĩnh./);
    assert.match(explanation.textContent, /Dịch kịch bản \(AI\): Người nói bảo thị trấn này yên tĩnh./);
    assert.equal(explanation.hidden, false);
  } finally {
    global.window = oldWindow;
    global.fetch = oldFetch;
  }
});
