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
  assert.deepEqual(model.comprehension, []);
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

test('comprehensionCard always discloses the Smart-Test-only, not-re-tested-in-path note', () => {
  const html = page.comprehensionCard({ skill: 'READING', hasEvidence: true, correctPercent: 40, weak: true, note: 'chưa được đánh giá lại trong lộ trình' });
  assert.match(html, /còn yếu/);
  assert.match(html, /chưa được đánh giá lại trong lộ trình/);
});

test('comprehensionCard reports insufficient evidence plainly rather than a 0% score', () => {
  const html = page.comprehensionCard({ skill: 'LISTENING', hasEvidence: false, correctPercent: null, weak: false, note: 'note' });
  assert.match(html, /Chưa có dữ liệu Smart Test/);
});

test('comprehensionCard labels its data explicitly as the Smart Test baseline', () => {
  const html = page.comprehensionCard({ skill: 'READING', hasEvidence: true, correctPercent: 80, weak: false, note: 'note' });
  assert.match(html, /Đánh giá nền \(Smart Test\)/);
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

test('comprehensionInPathCard explicitly distinguishes itself from the Smart Test baseline, never silently replacing it', () => {
  const html = page.comprehensionInPathCard({ skill: 'READING', hasEvidence: true, firstScore: 60, latestScore: 85, bestScore: 85, resolved: true, unresolved: [] });
  assert.match(html, /khác với đánh giá nền Smart Test/);
  assert.doesNotMatch(html, /Đánh giá nền \(Smart Test\)/); // that exact phrase is the baseline card's own label
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

test('summary exposes both replay modes for finished Grammar and Dialogue', () => {
  const html = page.render(summary({ studyLinks: [
    { activityId: 81, type: 'GRAMMAR', state: 'PASSED' },
    { activityId: 82, type: 'DIALOGUE', state: 'COMPLETED' }
  ] }));
  for (const [type, id] of [['grammar', 81], ['dialogue', 82]]) {
    for (const mode of ['review', 'practice']) {
      assert.ok(html.includes(`/learning/v2/${type}?activity=${id}&amp;mode=${mode}`));
    }
  }
});
