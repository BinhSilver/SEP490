const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-comprehension-study.js');

function studyReading(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'READING', activityState: 'STUDYING', sourceRevision: 'rev-1',
    exerciseCount: 2, currentExercise: 0,
    source: { passage: 'Đây là một đoạn văn mẫu để luyện đọc hiểu.', audioUrl: null },
    questions: [{ id: 10, prompt: 'Ý chính của đoạn văn là gì?', options: ['A', 'B', 'C'] }],
    microCheck: { activityId: 1, activityState: 'STUDYING', batchCount: 2, currentBatch: 0, attemptsUsed: 0,
      attemptsRemaining: 3, cooldownUntil: null, reviewRequired: false, checkEligible: true, passed: false,
      firstScore: null, latestScore: null, bestScore: null }
  }, overrides);
}

function studyListening(overrides = {}) {
  return Object.assign(studyReading({
    activityType: 'LISTENING',
    source: { passage: null, audioUrl: '/audio/comprehension/exercise-1.mp3' },
    questions: [{ id: 20, prompt: 'Người nói đang ở đâu?', options: ['Nhà', 'Trường'] }]
  }), overrides);
}

test('viewModel normalizes a Reading study state, exposing only the passage', () => {
  const v = page.viewModel(studyReading());
  assert.equal(v.activityType, 'READING');
  assert.equal(v.passage, 'Đây là một đoạn văn mẫu để luyện đọc hiểu.');
  assert.equal(v.audioUrl, null);
  assert.equal(v.questionCount, 1);
  assert.equal(v.checkEligible, true);
});

test('viewModel normalizes a Listening study state, exposing only the audio url', () => {
  const v = page.viewModel(studyListening());
  assert.equal(v.activityType, 'LISTENING');
  assert.equal(v.audioUrl, '/audio/comprehension/exercise-1.mp3');
  assert.equal(v.passage, null);
});

test('viewModel never invents eligibility - defaults to ineligible/not-passed when the server omits microCheck', () => {
  const v = page.viewModel({ activityType: 'READING', source: { passage: 'x' }, questions: [] });
  assert.equal(v.checkEligible, false);
  assert.equal(v.passed, false);
  assert.equal(v.reviewRequired, false);
});

test('renders the sanitized passage as escaped text, never executing embedded markup', () => {
  const v = page.viewModel(studyReading({ source: { passage: '<script>alert(1)</script> nội dung an toàn', audioUrl: null } }));
  const html = page.renderSourcePanel(v, false, true);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&lt;script&gt;/);
});

test('passage panel includes a bottom sentinel and a manual reached-end affordance for keyboard users', () => {
  const html = page.renderSourcePanel(page.viewModel(studyReading()), false, true);
  assert.match(html, /id="comprehensionPassageSentinel"/);
  assert.match(html, /data-reached-passage/);
  assert.match(html, /Đã đọc xong đoạn văn/);
});

test('reached-end button reflects done state without a manual complete flag existing on the model', () => {
  const html = page.renderSourcePanel(page.viewModel(studyReading()), true, true);
  assert.match(html, /is-done/);
  assert.match(html, /Đã ghi nhận đọc xong/);
});

test('suppresses the evidence sentinel/button when read-only (mid-check) so it is not confused with review evidence', () => {
  const html = page.renderSourcePanel(page.viewModel(studyReading()), false, false);
  assert.doesNotMatch(html, /data-reached-passage/);
  assert.doesNotMatch(html, /comprehensionPassageSentinel/);
});

test('renders an audio element only for an approved server-issued url, never fabricating one', () => {
  const html = page.renderSourcePanel(page.viewModel(studyListening()), false, true);
  assert.match(html, /data-comprehension-audio/);
  assert.match(html, /src="\/audio\/comprehension\/exercise-1\.mp3"/);
});

test('shows an explicit missing-audio state instead of a broken or fabricated player', () => {
  const v = page.viewModel(studyListening({ source: { passage: null, audioUrl: null } }));
  const html = page.renderSourcePanel(v, false, true);
  assert.doesNotMatch(html, /<audio/);
  assert.match(html, /Chưa có file nghe hợp lệ/);
});

test('never renders a transcript or the correct answer on the study/source panel', () => {
  const html = page.renderSourcePanel(page.viewModel(studyListening()), false, true);
  assert.doesNotMatch(html, /transcript|correctOption|answerKey/i);
});

test('study view shows exercise progress and question count from server data only', () => {
  const html = page.renderStudy(page.viewModel(studyReading({ exerciseCount: 3, currentExercise: 1 })), false);
  assert.match(html, /Bài 2\/3/);
  assert.match(html, /1 câu hỏi/);
});

test('the check CTA is disabled until the server reports eligibility', () => {
  const v = page.viewModel(studyReading({ microCheck: Object.assign({}, studyReading().microCheck, { checkEligible: false }) }));
  const html = page.renderStudy(v, false);
  assert.match(html, /data-check disabled/);
});

test('the check CTA enables only when the server reports eligibility, and exactly one primary action exists', () => {
  const html = page.renderStudy(page.viewModel(studyReading()), false);
  assert.doesNotMatch(html, /data-check disabled/);
  const buttons = html.match(/class="ilp-check"/g) || [];
  assert.equal(buttons.length, 1);
});

test('shows a review-required gate note distinct for Reading vs Listening', () => {
  const readingGate = Object.assign({}, studyReading().microCheck, { reviewRequired: true, checkEligible: false });
  const readingHtml = page.renderStudy(page.viewModel(studyReading({ microCheck: readingGate })), false);
  assert.match(readingHtml, /đọc lại đoạn văn/);

  const listeningGate = Object.assign({}, studyListening().microCheck, { reviewRequired: true, checkEligible: false });
  const listeningHtml = page.renderStudy(page.viewModel(studyListening({ microCheck: listeningGate })), false);
  assert.match(listeningHtml, /nghe lại audio/);
});

test('shows a cooldown message with the server-issued time once review evidence is satisfied but cooldown remains', () => {
  const gate = Object.assign({}, studyReading().microCheck, {
    reviewRequired: false, checkEligible: false, cooldownUntil: '2026-09-29T15:30:00'
  });
  const html = page.renderStudy(page.viewModel(studyReading({ microCheck: gate })), false);
  assert.match(html, /Vui lòng quay lại sau/);
});

test('renders every micro-check question with 1-based options and never exposes an answer key', () => {
  const check = { attemptId: 900, activityId: 1, batchIndex: 0, batchCount: 2, totalQuestions: 2, questions: [
    { questionId: 10, questionType: 'READING_MICRO_CHECK', questionText: 'Ý chính là gì?', options: ['A', 'B'] },
    { questionId: 11, questionType: 'READING_MICRO_CHECK', questionText: 'Câu 2?', options: ['C', 'D'] }
  ] };
  const html = page.renderCheckForm(check);
  assert.match(html, /data-attempt-id="900"/);
  assert.match(html, /value="1"/);
  assert.match(html, /value="2"/);
  assert.doesNotMatch(html, /correctOption|answerKey|data-answer/);
});

test('check screen keeps the source visible (read-only) alongside the question form', () => {
  const check = { attemptId: 900, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 1,
    questions: [{ questionId: 10, questionText: 'Ý chính?', options: ['A', 'B'] }] };
  const html = page.renderCheckScreen(page.viewModel(studyReading()), check);
  assert.match(html, /Đây là một đoạn văn mẫu/);
  assert.doesNotMatch(html, /data-reached-passage/);
});

test('renders a pass that unlocks the next exercise (not fully passed yet) with a single continue CTA', () => {
  const html = page.renderResult({ totalQuestions: 2, correctAnswers: 2, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'CHECK_READY', review: [] }, 'READING');
  assert.match(html, /Đã đạt/);
  assert.match(html, /data-next-exercise/);
  assert.doesNotMatch(html, /href="\/learning\/path\/v2"/);
  const actions = html.match(/<div class="ilp-result__actions">([\s\S]*?)<\/div>/)[1];
  assert.equal((actions.match(/ilp-check/g) || []).length, 1);
});

test('renders a full pass (all exercises done) with a single return-to-roadmap CTA', () => {
  const html = page.renderResult({ totalQuestions: 2, correctAnswers: 2, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] }, 'READING');
  assert.match(html, /href="\/learning\/path\/v2"/);
  assert.doesNotMatch(html, /data-next-exercise/);
});

test('renders a cooldown/review fail state with a single back-to-study CTA and Listening-specific wording', () => {
  const html = page.renderResult({ totalQuestions: 2, correctAnswers: 0, scorePercent: 0, passed: false,
    passThresholdPercent: 70, attemptsUsed: 3, attemptsRemaining: 0, cooldownUntil: '2026-09-29T15:30:00',
    reviewRequired: true, activityState: 'COOLDOWN',
    review: [{ questionId: 20, correctOption: 1, selectedOption: 2, correct: false }] }, 'LISTENING');
  assert.match(html, /Chưa đạt/);
  assert.match(html, /Lần thử 3\/3/);
  assert.match(html, /nghe lại audio/);
  assert.match(html, /data-back-to-study/);
  const actions = html.match(/<div class="ilp-result__actions">([\s\S]*?)<\/div>/)[1];
  assert.equal((actions.match(/ilp-check/g) || []).length, 1);
});

test('never leaks the correct option or a wrong answer\'s selected text in the result review list', () => {
  const html = page.renderResult({ totalQuestions: 1, correctAnswers: 0, scorePercent: 0, passed: false,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'CHECK_READY', review: [{ questionId: 10, correctOption: 2, selectedOption: 1, correct: false }] }, 'READING');
  assert.doesNotMatch(html, /correctOption|selectedOption|>2</);
});

test('renders a distinct passed state per activity type', () => {
  assert.match(page.renderPassed('READING'), /Phần Đọc hiểu đã đạt/);
  assert.match(page.renderPassed('LISTENING'), /Phần Nghe hiểu đã đạt/);
});

test('error state distinguishes an explicit content gap from a generic connection failure', () => {
  const gap = page.error('Bài tập này chưa có nội dung sẵn sàng.', 'BLOCKED_CONTENT');
  assert.match(gap, /Chưa có đủ học liệu/);
  assert.doesNotMatch(gap, /data-retry/);

  const generic = page.error('mất kết nối', undefined);
  assert.match(generic, /mất kết nối/);
  assert.match(generic, /data-retry/);
});
