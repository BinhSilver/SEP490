const test = require('node:test');
const assert = require('node:assert/strict');

const api = require('../../main/resources/static/js/pages/learning-path-lesson.js');

test('itemStartUrl builds the start endpoint only when an item id is present', () => {
  assert.equal(api.itemStartUrl('42'), '/api/learning/path-items/42/start');
  assert.equal(api.itemStartUrl(null), null);
  assert.equal(api.itemStartUrl(''), null);
});

test('itemCompleteUrl prefers the item endpoint, falls back to the lesson endpoint', () => {
  assert.equal(api.itemCompleteUrl('42', '7'), '/api/learning/path-items/42/complete');
  assert.equal(api.itemCompleteUrl(null, '7'), '/api/learning/lessons/7/complete');
  assert.equal(api.itemCompleteUrl(null, null), null);
});
