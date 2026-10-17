const test = require('node:test');
const assert = require('node:assert/strict');
const grammar = require('../../main/resources/static/js/pages/interactive-grammar-study.js');

function state() {
  return { activityId: 9, activityType: 'GRAMMAR', activityState: 'PASSED', totalPoints: 1,
    gateSatisfiedCount: 1, checkEligible: true, points: [{ pointId: 42, orderIndex: 1,
      pattern: 'V てはいけません', objective: 'Cấm làm', usageNote: null,
      components: [], example: { japanese: 'ここで写真を撮ってはいけません。', meaningVi: 'Không được chụp ảnh.' },
      note: null, ordering: { words: [{ tokenId: 0, text: 'ここで' }], completed: true },
      fill: { promptWithBlank: 'ここで写真を_____。', completed: true }, gateSatisfied: true }] };
}

test('grammar rehearsal shows the original lesson without progress-writing exercises', () => {
  const html = grammar.renderReview(grammar.viewModel(state()), true);
  assert.match(html, /Chế độ luyện tập/);
  assert.match(html, /V てはいけません/);
  assert.match(html, /Làm lại bài kiểm tra/);
  assert.doesNotMatch(html, /data-order-check|data-fill-check/);
  assert.doesNotMatch(html, /data-attempt-history|Xem lại bài kiểm tra/);
});

test('grammar quiz result returns to its lesson content instead of leaving only the roadmap', () => {
  const result = { totalQuestions: 1, correctAnswers: 1, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, activityState: 'PASSED', review: [] };
  assert.match(grammar.renderResult(result, 9), /\/learning\/v2\/grammar\?activity=9&amp;mode=review/);
  assert.match(grammar.renderResult({ ...result, passed: false, activityState: 'CHECK_READY' }, 9), /data-back-to-study/);
});
