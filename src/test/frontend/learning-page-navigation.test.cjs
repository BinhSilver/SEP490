const test = require('node:test');
const assert = require('node:assert/strict');
const navigation = require('../../main/resources/static/js/pages/learning-page-navigation.js');
const fs = require('node:fs');
const path = require('node:path');

test('back action uses browser history only for a same-origin referrer', () => {
  assert.deepEqual(
    navigation.resolveBackAction('http://localhost:8080/learning/path/v2', 'http://localhost:8080', '/learning'),
    { type: 'history' }
  );
  assert.deepEqual(
    navigation.resolveBackAction('https://outside.example/', 'http://localhost:8080', '/learning'),
    { type: 'fallback', href: '/learning' }
  );
  assert.deepEqual(
    navigation.resolveBackAction('', 'http://localhost:8080', '/learning/path/v2'),
    { type: 'fallback', href: '/learning/path/v2' }
  );
  assert.deepEqual(
    navigation.resolveBackAction('not a valid url', 'http://localhost:8080', '/learning'),
    { type: 'fallback', href: '/learning' }
  );
});

test('scroll controls appear only when there is useful room to scroll in that direction', () => {
  assert.deepEqual(navigation.getScrollVisibility(0, 1800, 800), { up: false, down: true });
  assert.deepEqual(navigation.getScrollVisibility(500, 1800, 800), { up: true, down: true });
  assert.deepEqual(navigation.getScrollVisibility(1200, 1800, 800), { up: true, down: false });
  assert.deepEqual(navigation.getScrollVisibility(0, 800, 800), { up: false, down: false });
});

test('mobile page reserves room below the last learning action for the floating navigation', () => {
  const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/css/components/learning-page-navigation.css'), 'utf8');
  assert.match(css, /@media\(max-width:640px\)[\s\S]*?\.ilp-page,\.survey-page\{padding-bottom:84px\}/);
});
