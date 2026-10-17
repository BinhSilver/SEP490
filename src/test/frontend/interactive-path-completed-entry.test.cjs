const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-learning-path.js');

test('completed path entry resolves summary before any new path creation', async () => {
  const requests = [];
  const url = await page.completedPathDestination(async (url, opts) => {
    requests.push({url, opts});
    return {ok: true, json: async () => ({success: true, data: {status: 'COMPLETED', pathId: 9}})};
  }, {Authorization: 'test-only'});
  assert.equal(url, '/learning/path/v2/summary');
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/learning/v2/paths/current/summary');
  assert.equal(requests[0].opts.method, undefined);
});

test('missing or active summary does not masquerade as completed', async () => {
  for (const value of [
    {ok: false, body: {code: 'NOT_FOUND'}},
    {ok: true, body: {success: true, data: {status: 'ACTIVE'}}},
    {ok: true, body: {success: false, data: {status: 'COMPLETED'}}}
  ]) {
    assert.equal(await page.completedPathDestination(async () => ({ok: value.ok, json: async () => value.body}), {}), null);
  }
});
