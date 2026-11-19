const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/pages/jlpt-practice.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const catalog = level => [{ code: 'READ_' + level, section: 'vocabulary', nameVi: 'Dạng ' + level, nameJa: '漢字', questionCount: 20 }];

// Run the real page handlers with delayed and out-of-order API responses.
function page() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {
      innerHTML: '', textContent: '', disabled: false, hidden: false, inert: false,
      listeners: {}, attributes: {}, classList: { toggle() {} },
      setAttribute(name, value) { this.attributes[name] = value; },
      addEventListener(name, handler) { this.listeners[name] = handler; }
    });
    return nodes.get(id);
  }
  const requests = [];
  vm.runInNewContext(source, {
    document: { getElementById: node, querySelector(selector) {
      assert.equal(selector, '#practiceLimit input:checked'); return { value: '20' };
    } },
    window: { scrollTo() {} },
    fetch(url) { return new Promise((resolve, reject) => requests.push({ url, resolve, reject, settled: false })); }
  });
  function metadataRequests(level) {
    return requests.filter(r => r.url.startsWith('/api/jlpt/practice/types?') && new URL(r.url, 'http://localhost').searchParams.get('level') === level);
  }
  function pending(level) {
    const request = metadataRequests(level).find(r => !r.settled);
    assert.ok(request, 'expected an outstanding request for ' + level);
    request.settled = true;
    return request;
  }
  return {
    node, requests, metadataRequests,
    choose(level) { node('practiceLevels').listeners.click({ target: { closest() { return { dataset: { level } }; } } }); },
    start() { node('practiceStart').listeners.click(); },
    async reply(level, types = catalog(level)) {
      pending(level).resolve({ ok: true, json: async () => ({ success: true, data: types }) }); await flush();
    },
    async fail(level) { pending(level).reject(new Error('Không kết nối được')); await flush(); }
  };
}

test('prefetches each level once and shares the initial request', () => {
  const p = page();
  assert.equal(p.requests.length, 5);
  for (const level of ['N5', 'N4', 'N3', 'N2', 'N1']) assert.equal(p.metadataRequests(level).length, 1);
  assert.doesNotMatch(p.node('practiceTypes').innerHTML, /Đang tải/);
  assert.equal(p.node('practiceStart').disabled, true);
});

test('cached level switches render immediately without another request', async () => {
  const p = page(); await p.reply('N3'); await p.reply('N2');
  p.choose('N2');
  assert.match(p.node('practiceTypes').innerHTML, /Dạng N2/);
  assert.equal(p.node('practiceStart').disabled, false);
  p.choose('N3');
  assert.match(p.node('practiceTypes').innerHTML, /Dạng N3/);
  assert.equal(p.requests.length, 5);
});

test('an uncached switch preserves visible choices but blocks starting with old data', async () => {
  const p = page(); await p.reply('N3');
  const visible = p.node('practiceTypes').innerHTML;
  p.choose('N2');
  assert.equal(p.node('practiceTypes').innerHTML, visible);
  assert.equal(p.node('practiceTypes').inert, true);
  assert.equal(p.node('practiceStart').disabled, true);
  p.start();
  assert.equal(p.requests.length, 5);
  assert.equal(p.metadataRequests('N2').length, 1);
  await p.reply('N2');
  assert.match(p.node('practiceTypes').innerHTML, /Dạng N2/);
  assert.equal(p.node('practiceTypes').inert, false);
  assert.equal(p.node('practiceStart').disabled, false);
});

test('a late response cannot overwrite the latest level or its question request', async () => {
  const p = page(); await p.reply('N3');
  p.choose('N2'); p.choose('N4');
  await p.reply('N4'); await p.reply('N2');
  assert.match(p.node('practiceTypes').innerHTML, /Dạng N4/);
  assert.doesNotMatch(p.node('practiceTypes').innerHTML, /Dạng N2/);
  p.start();
  const request = p.requests.find(r => r.url.startsWith('/api/jlpt/practice/questions?'));
  const params = new URL(request.url, 'http://localhost').searchParams;
  assert.equal(params.get('level'), 'N4');
  assert.equal(params.get('type'), 'READ_N4');
  assert.equal(params.get('limit'), '20');
});

test('empty catalogs are cached and keep starting disabled', async () => {
  const p = page(); await p.reply('N3'); await p.reply('N1', []);
  p.choose('N1');
  assert.match(p.node('practiceTypes').innerHTML, /Chưa có dạng câu hỏi/);
  assert.equal(p.node('practiceStart').disabled, true);
  p.choose('N3'); p.choose('N1');
  assert.equal(p.metadataRequests('N1').length, 1);
});

test('a selected-level failure restores the visible level and permits retry', async () => {
  const p = page(); await p.reply('N3'); p.choose('N2'); await p.fail('N2');
  assert.equal(p.node('practiceSummaryLevel').textContent, 'N3');
  assert.match(p.node('practiceTypes').innerHTML, /Dạng N3/);
  assert.equal(p.node('practiceStart').disabled, false);
  assert.equal(p.node('practiceSetupNotice').hidden, false);
  p.choose('N2');
  assert.equal(p.metadataRequests('N2').length, 2);
  await p.reply('N2');
  assert.match(p.node('practiceTypes').innerHTML, /Dạng N2/);
  assert.equal(p.node('practiceSetupNotice').hidden, true);
});

test('a late failure cannot revert a newer cached choice or display an error', async () => {
  const p = page(); await p.reply('N3'); p.choose('N2'); p.choose('N3'); await p.fail('N2');
  assert.equal(p.node('practiceSummaryLevel').textContent, 'N3');
  assert.equal(p.node('practiceStart').disabled, false);
  assert.equal(p.node('practiceSetupNotice').hidden, true);
});

test('an initial failure allows a fresh retry of the same level', async () => {
  const p = page(); await p.fail('N3');
  assert.equal(p.node('practiceStart').disabled, true);
  assert.equal(p.node('practiceSetupNotice').hidden, false);
  p.choose('N3');
  assert.equal(p.metadataRequests('N3').length, 2);
  await p.reply('N3');
  assert.equal(p.node('practiceStart').disabled, false);
  assert.equal(p.node('practiceSetupNotice').hidden, true);
});
