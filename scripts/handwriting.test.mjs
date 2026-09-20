import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const JS_DIR = new URL('../src/main/resources/static/dictionary/js/', import.meta.url);
const core = require(new URL('handwriting-core.js', JS_DIR).pathname.replace(/^\/([A-Za-z]:)/, '$1'));

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const tick = () => sleep(0);

/** Timer giả: chỉ chạy khi test ra lệnh, để kiểm tra debounce chính xác. */
function fakeTimers() {
  let next = 1;
  const pending = new Map();
  return {
    setTimeout: (fn) => { pending.set(next, fn); return next++; },
    clearTimeout: (id) => { pending.delete(id); },
    fire: () => { const fns = [...pending.values()]; pending.clear(); fns.forEach((fn) => fn()); },
    count: () => pending.size
  };
}

const stroke = (...pairs) => pairs;
const line = stroke([10, 10], [50, 10], [100, 10]);

function newSession(recognize, extra = {}) {
  const timers = fakeTimers();
  const states = [];
  const session = core.createSession({
    recognize, onChange: (s) => states.push(s), width: 360, height: 360, timers, ...extra
  });
  return { session, timers, states, last: () => states[states.length - 1] };
}

// ---------------------------------------------------------------- simplify / payload

test('simplify keeps endpoints and corners but drops collinear points', () => {
  const points = [[0, 0], [1, 0.1], [2, 0], [3, 0.1], [4, 0], [4, 5], [4, 10]];
  const out = core.simplify(points, 1.5);
  assert.deepEqual(out[0], [0, 0]);
  assert.deepEqual(out[out.length - 1], [4, 10]);
  assert.ok(out.some((p) => p[0] === 4 && p[1] === 0), 'the corner must survive');
  assert.ok(out.length < points.length);
});

test('simplify handles dots, two-point strokes and long strokes without recursion overflow', () => {
  assert.deepEqual(core.simplify([[5, 5]], 1.5), [[5, 5]]);
  assert.deepEqual(core.simplify([[0, 0], [9, 9]], 1.5), [[0, 0], [9, 9]]);
  const long = Array.from({ length: 100000 }, (_, i) => [i, Math.sin(i / 50) * 40]);
  assert.ok(core.simplify(long, 1.5).length > 2);
});

test('payload is clamped to the canvas, rounded and capped at the backend point limit', () => {
  const wild = Array.from({ length: 5000 }, (_, i) => [(i % 2) * 999 - 50, (i % 7) * 60.123456]);
  const payload = core.buildPayload([wild, [[10.04, 20.06]]], 360, 360);

  assert.equal(payload.width, 360);
  assert.ok(payload.strokes[0].length <= core.MAX_POINTS);
  for (const [x, y] of payload.strokes[0]) {
    assert.ok(x >= 0 && x <= 360 && y >= 0 && y <= 360);
  }
  assert.deepEqual(payload.strokes[1], [[10, 20.1]]);
});

// ---------------------------------------------------------------- session

test('no request until a stroke finishes, and rapid strokes are debounced into ONE request', async () => {
  const calls = [];
  const { session, timers } = newSession(async (payload) => { calls.push(payload); return [{ character: '日' }]; });

  assert.equal(calls.length, 0);
  session.addStroke(line);
  session.addStroke(line);
  session.addStroke(line);
  assert.equal(timers.count(), 1, 'a single pending timer');
  assert.equal(calls.length, 0, 'nothing sent before the debounce fires');

  timers.fire();
  await tick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].strokes.length, 3);
});

test('candidates update as strokes are added (realtime, partial drawing)', async () => {
  const answers = [[{ character: '一' }, { character: '二' }], [{ character: '二' }, { character: '三' }]];
  const { session, timers, last } = newSession(async () => answers.shift());

  session.addStroke(line);
  assert.equal(last().status, 'loading');
  timers.fire(); await tick();
  assert.equal(last().status, 'ready');
  assert.deepEqual(last().candidates.map((c) => c.character), ['一', '二']);

  session.addStroke(line);
  assert.equal(last().status, 'loading');
  assert.deepEqual(last().candidates.map((c) => c.character), ['一', '二'], 'old candidates stay while loading');
  timers.fire(); await tick();
  assert.deepEqual(last().candidates.map((c) => c.character), ['二', '三']);
});

test('a late response from an older request never overwrites newer candidates', async () => {
  const resolvers = [];
  const { session, timers, last } = newSession(() => new Promise((resolve) => resolvers.push(resolve)));

  session.addStroke(line); timers.fire(); await tick();
  session.addStroke(line); timers.fire(); await tick();
  assert.equal(resolvers.length, 2);

  resolvers[1]([{ character: 'NEW' }]); await tick();
  resolvers[0]([{ character: 'OLD' }]); await tick();

  assert.deepEqual(last().candidates.map((c) => c.character), ['NEW']);
});

test('a new stroke aborts the in-flight request', async () => {
  const signals = [];
  const { session, timers } = newSession((_, signal) => { signals.push(signal); return new Promise(() => {}); });

  session.addStroke(line); timers.fire(); await tick();
  assert.equal(signals[0].aborted, false);
  session.addStroke(line);
  assert.equal(signals[0].aborted, true);
});

test('undo re-recognises the remaining strokes; undoing the last stroke goes idle with no request', async () => {
  const calls = [];
  const { session, timers, last } = newSession(async (p) => { calls.push(p.strokes.length); return [{ character: '日' }]; });

  session.addStroke(line); session.addStroke(line);
  timers.fire(); await tick();
  session.undo(); timers.fire(); await tick();
  assert.deepEqual(calls, [2, 1]);

  session.undo();
  assert.equal(last().status, 'idle');
  assert.equal(timers.count(), 0);
  assert.deepEqual(last().candidates, []);
  timers.fire(); await tick();
  assert.equal(calls.length, 2, 'no request for an empty canvas');
});

test('clear cancels the pending timer and the in-flight request, and resets state', async () => {
  const signals = [];
  const { session, timers, last } = newSession((_, s) => { signals.push(s); return new Promise(() => {}); });

  session.addStroke(line); timers.fire(); await tick();
  session.addStroke(line);
  session.clear();

  assert.equal(signals[0].aborted, true);
  assert.equal(timers.count(), 0);
  assert.equal(last().status, 'idle');
  assert.equal(last().strokeCount, 0);
});

test('no match => empty state; backend failure => error state; both recover on the next stroke', async () => {
  const outcomes = [async () => [], async () => { throw new Error('Máy chủ bận'); }, async () => [{ character: '日' }]];
  const { session, timers, last } = newSession((...a) => outcomes.shift()(...a));

  session.addStroke(line); timers.fire(); await tick();
  assert.equal(last().status, 'empty');

  session.addStroke(line); timers.fire(); await tick();
  assert.equal(last().status, 'error');
  assert.equal(last().error, 'Máy chủ bận');
  assert.deepEqual(last().candidates, []);

  session.addStroke(line); timers.fire(); await tick();
  assert.equal(last().status, 'ready');
  assert.equal(last().error, null);
});

test('empty strokes are rejected and the stroke limit is enforced', () => {
  const { session } = newSession(async () => []);
  assert.equal(session.addStroke([]), false);
  assert.equal(session.addStroke(null), false);
  for (let i = 0; i < core.MAX_STROKES; i++) assert.equal(session.addStroke(line), true);
  assert.equal(session.isFull(), true);
  assert.equal(session.addStroke(line), false);
  assert.equal(session.strokes().length, core.MAX_STROKES);
});

// ---------------------------------------------------------------- DOM layer (fake DOM)

class FakeElement {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.handlers = {}; this.dataset = {}; this.attrs = {};
    this.classes = new Set(); this.textContent = ''; this.value = ''; this.className = '';
    this.classList = {
      toggle: (name, on) => { on ? this.classes.add(name) : this.classes.delete(name); },
      add: (name) => this.classes.add(name)
    };
  }
  addEventListener(type, fn) { (this.handlers[type] ||= []).push(fn); }
  dispatchEvent(event) { (this.handlers[event.type] || []).forEach((fn) => fn(event)); }
  setAttribute(k, v) { this.attrs[k] = v; }
  append(...kids) { this.children.push(...kids); }
  replaceChildren(...kids) { this.children = kids; }
  focus() { this.focused = true; }
  setPointerCapture() {}
  getBoundingClientRect() { return { left: 0, top: 0, width: 360, height: 360 }; }
  getContext() {
    const noop = () => {};
    return { clearRect: noop, beginPath: noop, moveTo: noop, lineTo: noop, stroke: noop, arc: noop, fill: noop };
  }
}

function loadHandwritingUi(recognizeImpl) {
  const ids = ['dict-handwriting-canvas', 'dict-hw-stroke-count', 'dict-hw-status', 'dict-hw-candidates',
    'dict-hw-undo', 'dict-hw-clear', 'dict-search-input'];
  const elements = Object.fromEntries(ids.map((id) => [id, new FakeElement(id.includes('canvas') ? 'canvas' : 'div')]));
  elements['dict-handwriting-canvas'].width = 360;
  elements['dict-handwriting-canvas'].height = 360;

  const domReady = [];
  const closed = [];
  const searches = [];
  const requests = [];
  elements['dict-search-input'].addEventListener('keydown', (e) => searches.push({ key: e.key, value: elements['dict-search-input'].value }));

  const sandbox = {
    Math, Promise, AbortController, setTimeout, clearTimeout, console,
    KeyboardEvent: class { constructor(type, init) { this.type = type; Object.assign(this, init); } },
    getComputedStyle: () => ({ getPropertyValue: () => '#000' }),
    document: {
      documentElement: {},
      getElementById: (id) => elements[id] || null,
      createElement: (tag) => new FakeElement(tag),
      addEventListener: (type, fn) => { if (type === 'DOMContentLoaded') domReady.push(fn); }
    },
    JlasUI: { closeModal: (id) => closed.push(id) },
    DictApi: { recognizeHandwriting: (payload, signal) => { requests.push(payload); return recognizeImpl(payload, signal); } }
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  for (const file of ['handwriting-core.js', 'handwriting.js']) {
    vm.runInContext(readFileSync(new URL(file, JS_DIR), 'utf8'), sandbox, { filename: file });
  }
  domReady.forEach((fn) => fn());

  const canvas = elements['dict-handwriting-canvas'];
  const pointer = (type, x, y, id = 1) => canvas.dispatchEvent({
    type, clientX: x, clientY: y, pointerId: id, pointerType: 'mouse', button: 0, preventDefault() {}
  });
  const drawStroke = (points, id = 1) => {
    pointer('pointerdown', ...points[0], id);
    points.slice(1).forEach(([x, y]) => pointer('pointermove', x, y, id));
    pointer('pointerup', ...points[points.length - 1], id);
  };
  return { elements, closed, searches, requests, drawStroke, pointer, sandbox };
}

const chips = (ui) => ui.elements['dict-hw-candidates'].children;
const sakuCandidate = { character: '作', strokeCount: 7, readings: [{ readingType: 'KUN', readingText: 'つく.る' }, { readingType: 'ON', readingText: 'サク' }] };

test('UI: dragging the pointer does not call the API; finishing the stroke does, once, after the debounce', async () => {
  const ui = loadHandwritingUi(async () => [sakuCandidate]);

  ui.pointer('pointerdown', 10, 10);
  for (let i = 0; i < 200; i++) ui.pointer('pointermove', 10 + i, 10 + (i % 5) * 3);
  await sleep(250);
  assert.equal(ui.requests.length, 0, 'no request while the stroke is still being drawn');

  ui.pointer('pointerup', 210, 10);
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '1 nét');
  await sleep(300);
  assert.equal(ui.requests.length, 1);
  assert.ok(ui.requests[0].strokes[0].length < 100, 'the 200 raw points were simplified');
});

test('UI: candidates render (ON reading preferred), selecting one clears, closes and searches', async () => {
  const ui = loadHandwritingUi(async () => [sakuCandidate, { character: '昨', strokeCount: 9, readings: [] }]);

  ui.drawStroke([[10, 10], [80, 10], [150, 12]]);
  await sleep(300);

  assert.equal(chips(ui).length, 2);
  const first = chips(ui)[0];
  assert.equal(first.dataset.kanji, '作');
  assert.deepEqual(first.children.map((c) => c.textContent), ['作', '7 nét', 'サク']);
  assert.equal(ui.elements['dict-hw-status'].textContent, 'Chọn chữ Kanji đúng, hoặc tiếp tục vẽ để thu hẹp gợi ý.');

  first.dispatchEvent({ type: 'click' });

  assert.equal(ui.elements['dict-search-input'].value, '作');
  assert.deepEqual(ui.closed, ['dict-handwriting-modal']);
  assert.deepEqual(ui.searches, [{ key: 'Enter', value: '作' }], 'reuses the existing Enter-to-search flow');
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '0 nét', 'canvas cleared');
  assert.equal(chips(ui).length, 0);
});

test('UI: candidates change as more strokes are drawn; undo and clear update the panel', async () => {
  const answers = [[{ character: '一', strokeCount: 1 }], [{ character: '二', strokeCount: 2 }]];
  const ui = loadHandwritingUi(async () => answers.shift() || []);

  ui.drawStroke([[10, 10], [150, 10]]); await sleep(300);
  assert.deepEqual(chips(ui).map((c) => c.dataset.kanji), ['一']);
  ui.drawStroke([[10, 60], [150, 60]]); await sleep(300);
  assert.deepEqual(chips(ui).map((c) => c.dataset.kanji), ['二']);
  assert.equal(ui.requests[1].strokes.length, 2);

  ui.elements['dict-hw-undo'].dispatchEvent({ type: 'click' });
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '1 nét');

  ui.elements['dict-hw-clear'].dispatchEvent({ type: 'click' });
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '0 nét');
  assert.equal(chips(ui).length, 0);
  assert.equal(ui.elements['dict-hw-status'].textContent, 'Vẽ một nét để xem gợi ý.');
  await sleep(300);
  assert.equal(ui.requests.length, 2, 'clear cancelled the re-recognition that undo had scheduled, and sent none itself');
});

test('UI: no candidates and API errors show their own state', async () => {
  const outcomes = [async () => [], async () => { throw new Error('Lỗi mạng'); }];
  const ui = loadHandwritingUi((...a) => outcomes.shift()(...a));

  ui.drawStroke([[10, 10], [150, 10]]); await sleep(300);
  assert.match(ui.elements['dict-hw-status'].textContent, /Chưa tìm thấy/);
  assert.equal(chips(ui).length, 0);

  ui.drawStroke([[10, 60], [150, 60]]); await sleep(300);
  assert.equal(ui.elements['dict-hw-status'].textContent, 'Lỗi mạng');
  assert.ok(ui.elements['dict-hw-status'].classes.has('is-error'));
});

test('UI: a fast flick (pointerdown then pointerup, no pointermove) keeps its endpoint instead of becoming a dot', async () => {
  const ui = loadHandwritingUi(async () => []);

  ui.pointer('pointerdown', 20, 20);
  ui.pointer('pointerup', 200, 20);
  await sleep(300);

  assert.deepEqual(JSON.parse(JSON.stringify(ui.requests[0].strokes[0])), [[20, 20], [200, 20]]);
});

test('UI: a tap (release on the press point) is sent as a single-point dot stroke', async () => {
  const ui = loadHandwritingUi(async () => []);

  ui.pointer('pointerdown', 180, 60);
  ui.pointer('pointerup', 180, 60);
  await sleep(300);

  assert.deepEqual(JSON.parse(JSON.stringify(ui.requests[0].strokes[0])), [[180, 60]]);
});

test('UI: a second finger / another pointer cannot corrupt the stroke in progress', async () => {
  const ui = loadHandwritingUi(async () => []);

  ui.pointer('pointerdown', 10, 10, 1);
  ui.pointer('pointerdown', 200, 200, 2);
  ui.pointer('pointermove', 300, 300, 2);
  ui.pointer('pointerup', 300, 300, 2);
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '0 nét', 'pointer 2 did not end pointer 1\'s stroke');
  ui.pointer('pointermove', 90, 10, 1);
  ui.pointer('pointerup', 90, 10, 1);
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '1 nét');
});

test('UI: the modal opener API clears the board', async () => {
  const ui = loadHandwritingUi(async () => [sakuCandidate]);
  ui.drawStroke([[10, 10], [150, 10]]); await sleep(300);
  assert.equal(chips(ui).length, 1);

  ui.sandbox.DictHandwriting.clear();
  assert.equal(chips(ui).length, 0);
  assert.equal(ui.elements['dict-hw-stroke-count'].textContent, '0 nét');
});
