const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/auth-session.js'), 'utf8');
const start = source.indexOf('  function startPage() {');
const end = source.indexOf("  document.addEventListener('click'", start);
const pageStartup = source.slice(start, end);

async function visit(pathname, { completed = false, role = 'LEARNER', loggedIn = true } = {}) {
  const requests = [], redirects = [];
  vm.runInNewContext(pageStartup + '\nstartPage();', {
    document: { cookie: 'accessToken=test; access_token=test; auth_status=1' },
    localStorage: { getItem() { return null; } },
    console: { info() {} },
    isTokenExpired: () => false,
    cookieValue: () => null,
    window: { location: { pathname, replace(url) { redirects.push(url); } } },
    JlasAuth: {
      getAccessToken: () => 'test-token', isLoggedIn: () => loggedIn,
      highlightActiveNav() {}, applyNav() {}, authHeaders: () => ({}),
      refreshUser: async () => ({ role })
    },
    fetch: async url => {
      requests.push(url);
      return { ok: true, json: async () => ({ success: true, data: { completed } }) };
    }
  });
  await new Promise(resolve => setImmediate(resolve));
  return { requests, redirects };
}

test('a new learner stays on the notebook without a survey check', async () => {
  assert.deepEqual(await visit('/learning/notebook'), { requests: [], redirects: [] });
});
test('a notebook URL with a trailing slash is also excluded', async () => {
  assert.deepEqual(await visit('/learning/notebook/'), { requests: [], redirects: [] });
});
test('learning entry still redirects an unsurveyed learner', async () => {
  assert.deepEqual(await visit('/learning'), { requests: ['/api/assessment/survey'], redirects: ['/survey'] });
});
test('learning-path child pages still check the survey', async () => {
  assert.deepEqual(await visit('/learning/jlpt'), { requests: ['/api/assessment/survey'], redirects: ['/survey'] });
});
test('learners who completed the survey remain on their learning path', async () => {
  assert.deepEqual(await visit('/learning/jlpt', { completed: true }), { requests: ['/api/assessment/survey'], redirects: [] });
});
test('staff accounts continue to skip placement onboarding', async () => {
  assert.deepEqual(await visit('/learning', { role: 'CONTENT_CREATOR' }), { requests: [], redirects: [] });
});
test('guest handling is unchanged', async () => {
  assert.deepEqual(await visit('/learning/notebook', { loggedIn: false }), { requests: [], redirects: [] });
});
test('pages outside learning do not request a survey', async () => {
  assert.deepEqual(await visit('/jlpt'), { requests: [], redirects: [] });
});
