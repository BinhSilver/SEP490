const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-path-summary.js');

function skill(overrides = {}) {
  return Object.assign({
    skill: 'VOCABULARY', hasEvidence: true, firstScore: 60, latestScore: 90, bestScore: 95,
    resolved: true, unresolved: []
  }, overrides);
}

function summary(overrides = {}) {
  return Object.assign({
    pathId: 1, status: 'ACTIVE', completedAt: null,
    skills: [skill()],
    dialogue: { totalCount: 1, completedCount: 1, allCompleted: true },
    comprehension: [
      { skill: 'READING', hasEvidence: true, assessedCount: 10, wrongCount: 2, correctPercent: 80, weak: false, note: 'note-r' },
      { skill: 'LISTENING', hasEvidence: false, assessedCount: null, wrongCount: null, correctPercent: null, weak: false, note: 'note-l' }
    ],
    readiness: { level: 'MEDIUM', explanation: 'explanation text' },
    certificateEligible: false
  }, overrides);
}

test('viewModel defaults missing collections to empty arrays instead of throwing', () => {
  const model = page.viewModel({});
  assert.deepEqual(model.skills, []);
  assert.deepEqual(model.comprehension, []);
  assert.equal(model.dialogue.totalCount, 0);
});

test('skillCard shows dash placeholders and a pending state when the skill has no evidence yet, never 0%', () => {
  const html = page.skillCard(skill({ hasEvidence: false, firstScore: null, latestScore: null, bestScore: null }));
  assert.match(html, /Chưa có bằng chứng/);
  assert.doesNotMatch(html, />0%</);
});

test('skillCard renders first/latest/best scores when evidence exists', () => {
  const html = page.skillCard(skill());
  assert.match(html, /60%/);
  assert.match(html, /90%/);
  assert.match(html, /95%/);
});

test('skillCard surfaces an unresolved batch explicitly rather than hiding it in an average', () => {
  const html = page.skillCard(skill({
    resolved: false,
    unresolved: [{ activityId: 3, batchIndex: 0, latestScore: 40, reviewRequired: true, cooldownUntil: null }]
  }));
  assert.match(html, /Còn nội dung chưa đạt/);
  assert.match(html, /Batch 0/);
  assert.match(html, /cần ôn lại/);
});

test('renderDialogue reports a plain completed/total count, never a percentage score', () => {
  const html = page.renderDialogue({ totalCount: 2, completedCount: 1, allCompleted: false });
  assert.match(html, /1\/2/);
  assert.doesNotMatch(html, /%/);
});

test('comprehensionCard always discloses the Smart-Test-only, not-re-tested-in-path note', () => {
  const html = page.comprehensionCard({ skill: 'READING', hasEvidence: true, correctPercent: 40, weak: true, note: 'chưa được đánh giá lại trong lộ trình' });
  assert.match(html, /còn yếu/);
  assert.match(html, /chưa được đánh giá lại trong lộ trình/);
});

test('comprehensionCard reports insufficient evidence plainly rather than a 0% score', () => {
  const html = page.comprehensionCard({ skill: 'LISTENING', hasEvidence: false, correctPercent: null, weak: false, note: 'note' });
  assert.match(html, /Chưa có dữ liệu Smart Test/);
});

test('renderReadiness always states this is an internal estimate, not a JLPT pass confirmation', () => {
  const html = page.renderReadiness({ level: 'MEDIUM', explanation: 'vì abc' });
  assert.match(html, /không phải xác nhận đỗ JLPT/);
  assert.match(html, /vì abc/);
});

test('renderActions omits a live certificate link and explains why when not yet eligible', () => {
  const html = page.renderActions({ certificateEligible: false });
  assert.doesNotMatch(html, /href="\/learning\/v2\/certificate"/);
  assert.match(html, /sẽ mở khi/);
});

test('renderActions links to the certificate page once eligible', () => {
  const html = page.renderActions({ certificateEligible: true });
  assert.match(html, /href="\/learning\/v2\/certificate"/);
});

test('renderActions never links the mock-test button to a fake destination; it stays disabled', () => {
  const html = page.renderActions({ certificateEligible: true });
  assert.match(html, /disabled/);
  assert.doesNotMatch(html, /<a[^>]*Thi thử/);
});

test('render assembles the full page without throwing on a realistic response', () => {
  const html = page.render(summary());
  assert.match(html, /Tổng kết lộ trình/);
  assert.match(html, /Từ vựng/);
  assert.match(html, /Hội thoại/);
  assert.match(html, /Đọc hiểu/);
});

test('error renders a retry affordance', () => {
  const html = page.error('boom');
  assert.match(html, /boom/);
  assert.match(html, /data-retry/);
});
