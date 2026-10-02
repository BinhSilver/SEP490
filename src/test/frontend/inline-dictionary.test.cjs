const test = require('node:test');
const assert = require('node:assert/strict');
const inline = require('../../main/resources/static/js/inline-dictionary.js');

function node({ optIn = true, blocked = false } = {}) {
  return { closest(selector) {
    if (selector.includes('input') && blocked) return {};
    if (selector === '[data-inline-dictionary]') return optIn ? {} : null;
    return null;
  } };
}

test('lookup is restricted to opted-in Japanese content and private/editor regions are excluded', () => {
  assert.equal(inline.isEligibleNode(node(), '日本語を勉強します'), true);
  assert.equal(inline.isEligibleNode(node({ optIn: false }), '日本語'), false);
  assert.equal(inline.isEligibleNode(node({ blocked: true }), '日本語'), false);
  assert.equal(inline.isEligibleNode(node(), 'plain English'), false);
});

test('selection is trimmed and bounded before lookup', () => {
  assert.equal(inline.normalizeSelection('  日本語  '), '日本語');
  assert.equal(inline.normalizeSelection('日'.repeat(101)), '');
});

test('popup escapes server text and exposes keyboard-friendly actions', () => {
  const html = inline.popupMarkup({ vocabularyId: 5, expression: '<旅行>', reading: 'りょこう',
    meaning: 'du lịch', wordType: 'noun', detailUrl: '/dictionary/index.html?q=x' });
  assert.match(html, /&lt;旅行&gt;/);
  assert.match(html, /data-inline-save="5"/);
  assert.match(html, /Mở từ điển/);
  assert.doesNotMatch(html, /<旅行>/);
});
