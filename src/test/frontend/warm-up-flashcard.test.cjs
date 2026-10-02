const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname,
  '../../main/resources/static/js/pages/warm-up-flashcard.js'), 'utf8');

function setup(cards, rejectSave = false) {
  const nodes = new Map();
  function el(id) {
    if (!nodes.has(id)) nodes.set(id, { hidden: true, disabled: false, textContent: '',
      style: {}, classList: { toggle() {} }, listeners: {},
      addEventListener(event, fn) { this.listeners[event] = fn; } });
    return nodes.get(id);
  }
  const writes = [];
  const document = { getElementById: el, body: { getAttribute: () => '50' },
    addEventListener() {} };
  const window = { FlashApi: {
    get: async () => ({ cards }),
    post: async (url, body) => {
      writes.push({ url, body });
      if (rejectSave) throw new Error('Mất mạng');
      return { memoryStatus: body.memoryStatus };
    }
  }, location: { replace() {} } };
  vm.runInNewContext(source, { document, window });
  return { el, writes, boot: window.WarmUpFlashcard.boot,
    click: async id => { await el(id).listeners.click({ stopPropagation() {} }); await new Promise(setImmediate); } };
}

test('must flip before remembering and saves every answer before completing', async () => {
  const app = setup([{ id: 1, front: '一' }]);
  await app.boot(); await new Promise(setImmediate);
  await app.click('wu-remembered');
  assert.equal(app.writes.length, 0);
  await app.click('wu-flip');
  await app.click('wu-remembered');
  assert.equal(app.writes.length, 1);
  assert.equal(app.writes[0].body.memoryStatus, 'REMEMBERED');
  assert.equal(app.el('wu-complete').hidden, false);
});

test('not remembered cards remain available for another round', async () => {
  const app = setup([{ id: 1, front: '一' }]);
  await app.boot(); await new Promise(setImmediate);
  await app.click('wu-flip'); await app.click('wu-not-remembered');
  assert.equal(app.el('wu-complete').hidden, true);
  assert.equal(app.el('wu-stage').hidden, false);
});

test('failed save does not advance or claim completion', async () => {
  const app = setup([{ id: 1, front: '一' }], true);
  await app.boot(); await new Promise(setImmediate);
  await app.click('wu-flip'); await app.click('wu-remembered');
  assert.equal(app.el('wu-complete').hidden, true);
  assert.match(app.el('wu-save-status').textContent, /Mất mạng/);
});

test('reload resumes at the first card not remembered', async () => {
  const app = setup([{ id: 1, front: '一', memoryStatus: 'REMEMBERED' },
    { id: 2, front: '二', memoryStatus: 'NEW' }]);
  await app.boot();
  assert.equal(app.el('wu-face-front').textContent, '二');
  assert.match(app.el('wu-progress-label').textContent, /1 \/ 2/);
});

test('a persisted completed set stays complete on reload without extra writes', async () => {
  const app = setup([{ id: 1, front: '一', memoryStatus: 'REMEMBERED' }]);
  await app.boot();
  assert.equal(app.el('wu-complete').hidden, false);
  assert.equal(app.writes.length, 0);
});

test('completed set can be reviewed again via the review button', async () => {
  const app = setup([{ id: 1, front: '一', memoryStatus: 'REMEMBERED' },
    { id: 2, front: '二', memoryStatus: 'REMEMBERED' }]);
  await app.boot();
  assert.equal(app.el('wu-complete').hidden, false);
  await app.click('wu-review-again');
  assert.equal(app.el('wu-complete').hidden, true);
  assert.equal(app.el('wu-stage').hidden, false);
  assert.equal(app.el('wu-face-front').textContent, '一');
  assert.match(app.el('wu-progress-label').textContent, /1 \/ 2/);
  // Ôn lại lướt qua cả thẻ đã nhớ (không bỏ qua), tiến độ theo vị trí.
  await app.click('wu-flip'); await app.click('wu-remembered');
  assert.equal(app.el('wu-face-front').textContent, '二');
  assert.match(app.el('wu-progress-label').textContent, /2 \/ 2/);
  await app.click('wu-flip'); await app.click('wu-remembered');
  assert.equal(app.el('wu-complete').hidden, false);
});
