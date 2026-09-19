const { test } = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../main/resources/static/js/pages/personalized-path.js');
const data = require('../../main/resources/static/js/pages/personalized-path-data.js');

test('shows both phases and totals derived from the path', () => {
  const html = view.render(data.example());
  assert.match(html, /Khởi động/);
  assert.match(html, /Chinh phục N4/);
  assert.match(html, /210/);
  assert.match(html, /8 bài học/);
});
test('filters by phase without changing overall totals', () => {
  const html = view.render(data.example(), { phase: 'WARM_UP', status: 'ALL' });
  assert.match(html, /Phân biệt は và が/);
  assert.doesNotMatch(html, /Nói về dự định với/);
  assert.match(html, /210/);
});
test('filters completed lessons and shows an explicit no-match state', () => {
  const html = view.render(data.example(), { phase: 'GOAL_LEARNING', status: 'COMPLETED' });
  assert.match(html, /Không có bài học phù hợp/);
  assert.match(html, /Xóa bộ lọc/);
});
test('escapes lesson titles and reasons from the data source', () => {
  const path = data.example();
  path.items[0].title = '<img src=x onerror=alert(1)>';
  path.items[0].reason = '<script>alert(1)</script>';
  const html = view.render(path);
  assert.doesNotMatch(html, /<img src=x|<script>/);
  assert.match(html, /&lt;img/);
});
test('empty and error states have usable actions without invented assessment routes', () => {
  assert.match(view.renderEmpty(), /chưa có lộ trình/i);
  assert.doesNotMatch(view.renderEmpty(), /href="\/assessment/);
  assert.match(view.renderError(), /data-retry/);
});
test('demo sources return isolated data and can model empty or error states', async () => {
  const first = await data.demo('active').load();
  first.items[0].title = 'Changed';
  assert.notEqual((await data.demo('active').load()).items[0].title, 'Changed');
  assert.equal(await data.demo('empty').load(), null);
  await assert.rejects(data.demo('error').load());
});
test('API adapter unwraps data, handles 404, and rejects errors', async () => {
  assert.deepEqual(await data.api('/api/path', async () => ({ ok: true, status: 200,
    json: async () => ({ data: data.example() }) })).load(), data.example());
  assert.equal(await data.api('/api/path', async () => ({ status: 404 })).load(), null);
  await assert.rejects(data.api('/api/path', async () => ({ ok: false, status: 500 })).load());
});
test('malformed success payload is an error, not a fabricated empty path', async () => {
  await assert.rejects(data.api('/api/path', async () => ({ ok: true, status: 200,
    json: async () => ({ data: {} }) })).load());
});
