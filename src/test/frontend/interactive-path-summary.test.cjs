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
    comprehensionInPath: [
      { skill: 'READING', hasEvidence: true, firstScore: 60, latestScore: 85, bestScore: 85, resolved: true, unresolved: [] },
      { skill: 'LISTENING', hasEvidence: false, firstScore: null, latestScore: null, bestScore: null, resolved: false, unresolved: [] }
    ],
    readiness: { level: 'MEDIUM', explanation: 'explanation text' },
    certificateEligible: false,
    mockTest: { available: false, status: 'BLOCKED_INTEGRATION', url: null, targetLevel: 'N4' }
  }, overrides);
}

test('viewModel defaults missing collections to empty arrays instead of throwing', () => {
  const model = page.viewModel({});
  assert.deepEqual(model.skills, []);
  assert.deepEqual(model.comprehensionInPath, []);
  assert.equal(model.dialogue.totalCount, 0);
});

test('viewModel defaults a missing mockTest field to the honest unavailable state, never fabricating availability', () => {
  const model = page.viewModel({});
  assert.equal(model.mockTest.available, false);
  assert.equal(model.mockTest.status, 'BLOCKED_INTEGRATION');
  assert.equal(model.mockTest.url, null);
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

// ---- RC Task 6: in-path Reading/Listening evidence, distinct from the Smart Test baseline above ----

test('comprehensionInPathCard shows no-evidence state without implying a 0% score', () => {
  const html = page.comprehensionInPathCard({ skill: 'LISTENING', hasEvidence: false, firstScore: null, latestScore: null, bestScore: null, resolved: false, unresolved: [] });
  assert.match(html, /Chưa làm bài đọc\/nghe nào trong lộ trình/);
  assert.doesNotMatch(html, />0%</);
});

test('comprehensionInPathCard renders first/latest/best scores when in-path evidence exists', () => {
  const html = page.comprehensionInPathCard({ skill: 'READING', hasEvidence: true, firstScore: 60, latestScore: 85, bestScore: 85, resolved: true, unresolved: [] });
  assert.match(html, /60%/);
  assert.match(html, /85%/);
});

test('comprehensionInPathCard surfaces an unresolved in-path batch explicitly, never averaged away', () => {
  const html = page.comprehensionInPathCard({
    skill: 'LISTENING', hasEvidence: true, firstScore: 40, latestScore: 40, bestScore: 55, resolved: false,
    unresolved: [{ activityId: 7, batchIndex: 0, latestScore: 40, reviewRequired: true, cooldownUntil: null }]
  });
  assert.match(html, /Còn bài chưa đạt/);
  assert.match(html, /Batch 0/);
});

test('comprehensionInPathCard shows only in-path progress, without Smart Test baseline copy', () => {
  const html = page.comprehensionInPathCard({ skill: 'READING', hasEvidence: true, firstScore: 60, latestScore: 85, bestScore: 85, resolved: true, unresolved: [] });
  assert.match(html, /Đọc hiểu — trong lộ trình/);
  assert.doesNotMatch(html, /đánh giá nền Smart Test/);
});

test('renderActions omits a live certificate link and explains why when not yet eligible', () => {
  const html = page.renderActions({ certificateEligible: false });
  assert.doesNotMatch(html, /href="\/learning\/v2\/certificate"/);
  assert.match(html, /sẽ mở khi/);
});

test('renderActions links to the certificate page once eligible', () => {
  const html = page.renderActions({
    certificateEligible: true,
    mockTest: { status: 'BLOCKED_INTEGRATION', targetLevel: 'N5' },
    latestMockResult: { level: 'N5', passed: true, attemptId: 15 }
  });
  assert.match(html, /href="\/learning\/v2\/certificate"/);
});

test('a completed JLPT path does not expose its certificate before a passing mock attempt', () => {
  const html = page.renderActions({
    certificateEligible: true,
    mockTest: { status: 'BLOCKED_INTEGRATION', targetLevel: 'N5' },
    latestMockResult: null
  });
  assert.doesNotMatch(html, /href="\/learning\/v2\/certificate"/);
});

test('a failed or wrong-level JLPT mock attempt does not expose the path certificate', () => {
  for (const latestMockResult of [
    { level: 'N5', passed: false, attemptId: 15 },
    { level: 'N4', passed: true, attemptId: 16 }
  ]) {
    const html = page.renderActions({
      certificateEligible: true,
      mockTest: { status: 'BLOCKED_INTEGRATION', targetLevel: 'N5' },
      latestMockResult
    });
    assert.doesNotMatch(html, /href="\/learning\/v2\/certificate"/);
  }
});

test('completion message congratulates the learner without calling itself a certificate', () => {
  const html = page.render(summary({ status: 'COMPLETED', certificateEligible: true }));
  assert.match(html, /Chúc mừng/);
  assert.match(html, /hoàn thành xuất sắc lộ trình/);
  assert.match(html, /thi thử JLPT/);
  assert.match(html, /chứng nhận vàng J-LAS/);
  assert.match(html, /data-path-congratulation/);
  const notice = html.match(/<aside[^>]*data-path-congratulation[^>]*>[\s\S]*?<\/aside>/)?.[0] || '';
  assert.doesNotMatch(notice, /href="\/learning\/v2\/certificate"/);
});

test('completion message is absent while path is still active', () => {
  const html = page.render(summary({ status: 'ACTIVE', certificateEligible: false }));
  assert.doesNotMatch(html, /data-path-congratulation/);
});

test('a completed path offers a fresh survey instead of linking back to an active-only path page', () => {
  const html = page.renderActions({ certificateEligible: true, status: 'COMPLETED' });
  assert.match(html, /href="\/survey"[^>]*>Chọn mục tiêu mới<\/a>/);
  assert.doesNotMatch(html, /href="\/learning\/path\/v2"/);
});

// ---- RC Task 7: Mock Test handoff seam — server decides availability/destination, never the browser ----

test('renderActions renders a disabled button using the servers BLOCKED_INTEGRATION status, never a fabricated link', () => {
  const html = page.renderActions({
    certificateEligible: true,
    mockTest: { available: false, status: 'BLOCKED_INTEGRATION', url: null, targetLevel: 'N4' }
  });
  assert.match(html, /disabled/);
  assert.doesNotMatch(html, /<a[^>]*Thi thử/);
});

test('renderActions defaults to the disabled state when mockTest is missing from the response entirely', () => {
  const html = page.renderActions({ certificateEligible: true });
  assert.match(html, /disabled/);
  assert.doesNotMatch(html, /<a[^>]*Thi thử/);
});

test('renderActions omits JLPT Mock Test entirely when the server marks it not applicable', () => {
  const html = page.renderActions({
    certificateEligible: true,
    mockTest: { available: false, status: 'NOT_APPLICABLE', url: null, targetLevel: null }
  });
  assert.doesNotMatch(html, /Thi thử JLPT/);
});

test('renderActions links to the servers exact resolved destination when the provider reports it available', () => {
  const html = page.renderActions({
    certificateEligible: true,
    mockTest: { available: true, status: 'AVAILABLE', url: '/mock-test/start', targetLevel: 'N4' }
  });
  assert.match(html, /<a[^>]*href="\/mock-test\/start"[^>]*>Thi thử JLPT</);
  assert.doesNotMatch(html, /disabled/);
});

test('renderActions never lets the browser compose the mock-test URL itself — it only ever echoes the servers own url field verbatim', () => {
  const html = page.renderActions({
    certificateEligible: true,
    mockTest: { available: true, status: 'AVAILABLE', url: 'https://mock-test.jlas.example.com/start?level=N3', targetLevel: 'N3' }
  });
  assert.match(html, /href="https:\/\/mock-test\.jlas\.example\.com\/start\?level=N3"/);
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

test('path summary omits the replay and review section even when replay links exist', () => {
  const html = page.render(summary({ studyLinks: [
    { activityId: 81, type: 'GRAMMAR', state: 'PASSED' },
    { activityId: 82, type: 'DIALOGUE', state: 'COMPLETED' }
  ] }));
  assert.doesNotMatch(html, /Xem lại và ôn lại/);
  assert.doesNotMatch(html, /data-study-links/);
  assert.doesNotMatch(html, /mode=(review|practice)/);
});
