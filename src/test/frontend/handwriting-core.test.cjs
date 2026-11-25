const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../../main/resources/static/dictionary/js/handwriting-core.js');

test('taps and tiny drags do not count or reach the API', () => {
  const states = [];
  const session = core.createSession({
    width: 360, height: 360,
    recognize: async () => [],
    onChange: state => states.push(state),
    timers: { setTimeout: () => 1, clearTimeout: () => {} }
  });
  assert.equal(core.isRealStroke([[10, 10]]), false);
  assert.equal(session.addStroke([[10, 10]]), false);
  assert.equal(session.addStroke([[10, 10], [12, 11]]), false);
  assert.equal(session.strokes().length, 0);
  assert.equal(session.addStroke([[10, 10], [20, 10]]), true);
  assert.equal(states.at(-1).strokeCount, 1);
  assert.equal(core.buildPayload([[[1, 1]], [[1, 1], [2, 2]], [[10, 10], [20, 10]]], 360, 360).strokes.length, 1);
});
