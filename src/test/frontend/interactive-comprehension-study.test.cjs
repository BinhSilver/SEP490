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
  const html = page.renderStudy(v, false);
  assert.match(html, /Người nói đang ở đâu/);
  assert.doesNotMatch(html, /Nhà|Trường/);
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
  assert.match(html, /data-audio-error/);
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

test('the Listening answer CTA is disabled until the server reports eligibility', () => {
  const v = page.viewModel(studyListening({ microCheck: Object.assign({}, studyListening().microCheck, { checkEligible: false }) }));
  const html = page.renderStudy(v, false);
  assert.match(html, /data-check disabled/);
});

test('the Listening answer CTA enables only when the server reports eligibility, and exactly one primary action exists', () => {
  const html = page.renderStudy(page.viewModel(studyListening()), false);
  assert.doesNotMatch(html, /data-check disabled/);
  const buttons = html.match(/class="ilp-check"/g) || [];
  assert.equal(buttons.length, 1);
});

test('reading answers are presented inline and never described as a separate quiz', () => {
  const view = page.viewModel(studyReading());
  assert.equal(page.shouldOpenInlineQuestions(view), true);
  const check = { attemptId: 900, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 1,
    questions: [{ questionId: 10, questionText: 'Ý chính?', options: ['A', 'B'] }] };
  const html = page.renderCheckScreen(view, check);
  assert.match(html, /Câu hỏi trong bài đọc/);
  assert.match(html, /Hoàn tất bài đọc/);
  assert.doesNotMatch(html, /KIỂM TRA|Kiểm tra đọc hiểu|Bắt đầu kiểm tra/);
});

test('only eligible Reading auto-opens inline questions; Listening keeps its explicit answer action', () => {
  assert.equal(page.shouldOpenInlineQuestions(page.viewModel(studyReading())), true);
  assert.equal(page.shouldOpenInlineQuestions(page.viewModel(studyListening())), false);
  const blocked = Object.assign({}, studyReading().microCheck, { checkEligible: false });
  assert.equal(page.shouldOpenInlineQuestions(page.viewModel(studyReading({ microCheck: blocked }))), false);
});

test('shows a review-required gate note distinct for Reading vs Listening', () => {
  const readingGate = Object.assign({}, studyReading().microCheck, { reviewRequired: true, checkEligible: false });
  const readingHtml = page.renderStudy(page.viewModel(studyReading({ microCheck: readingGate })), false);
  assert.match(readingHtml, /đọc lại đoạn văn/);

  const listeningGate = Object.assign({}, studyListening().microCheck, { reviewRequired: true, checkEligible: false });
  const listeningHtml = page.renderStudy(page.viewModel(studyListening({ microCheck: listeningGate })), false);
  assert.match(listeningHtml, /nghe lại audio/);
});

test('listening keeps question answering inside the lesson instead of presenting a separate quiz', () => {
  const view = page.viewModel(studyListening());
  const studyHtml = page.renderStudy(view, false);
  assert.match(studyHtml, /Trả lời câu hỏi nghe hiểu/);
  assert.doesNotMatch(studyHtml, /Bắt đầu kiểm tra|SẴN SÀNG KIỂM TRA/);
  const check = { attemptId: 901, totalQuestions: 1,
    questions: [{ questionId: 20, questionText: 'Ai đang nói?', options: ['A', 'B'] }] };
  const answerHtml = page.renderCheckScreen(view, check);
  assert.match(answerHtml, /Trả lời câu hỏi của bài nghe/);
  assert.match(answerHtml, /Xem kết quả bài nghe/);
  assert.doesNotMatch(answerHtml, /KIỂM TRA|Kiểm tra nghe hiểu/);
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

test('renders a comprehension check as one visible question page with navigation and a final-page submit', () => {
  const check = { attemptId: 902, totalQuestions: 3, questions: [
    { questionId: 20, questionText: 'Câu một?', options: ['A', 'B'] },
    { questionId: 21, questionText: 'Câu hai?', options: ['C', 'D'] },
    { questionId: 22, questionText: 'Câu ba?', options: ['E', 'F'] }
  ] };
  const html = page.renderCheckForm(check);
  assert.match(html, /data-question-page="0"/);
  assert.match(html, /data-question-page="1" hidden/);
  assert.match(html, /data-question-page="2" hidden/);
  assert.match(html, /data-question-prev[^>]*disabled/);
  assert.match(html, /data-question-next/);
  assert.match(html, /data-question-counter[^>]*>Câu 1 \/ 3</);
  assert.match(html, /data-question-submit[^>]*hidden/);
});

test('question pager moves both directions and retains selections while revealing submit only on the last page', () => {
  const pages = [
    { hidden: false, selection: 2, focus() {} },
    { hidden: true, selection: 1, focus() {} },
    { hidden: true, selection: 2, focus() {} }
  ];
  const controls = {
    '[data-question-prev]': { disabled: true },
    '[data-question-next]': { hidden: false },
    '[data-question-counter]': { textContent: '' },
    '[data-question-submit]': { hidden: true }
  };
  const form = {
    querySelectorAll: () => pages,
    querySelector: (selector) => controls[selector]
  };

  page.showQuestionPage(form, 1);
  assert.deepEqual(pages.map((item) => item.hidden), [true, false, true]);
  assert.equal(controls['[data-question-prev]'].disabled, false);
  assert.equal(controls['[data-question-counter]'].textContent, 'Câu 2 / 3');
  assert.equal(controls['[data-question-submit]'].hidden, true);
  assert.deepEqual(pages.map((item) => item.selection), [2, 1, 2]);

  page.showQuestionPage(form, 2);
  assert.deepEqual(pages.map((item) => item.hidden), [true, true, false]);
  assert.equal(controls['[data-question-next]'].hidden, true);
  assert.equal(controls['[data-question-submit]'].hidden, false);

  page.showQuestionPage(form, 1);
  assert.deepEqual(pages.map((item) => item.hidden), [true, false, true]);
  assert.deepEqual(pages.map((item) => item.selection), [2, 1, 2]);
});

// ---- [JLAS-W0113] draft autosave/restore: reopened answers restore the learner's saved selections ----

test('renderCheckForm restores previously autosaved selections as checked options', () => {
  const check = { attemptId: 903, activityId: 1, batchIndex: 0, batchCount: 2, totalQuestions: 2,
    draftAnswers: [{ questionId: 10, selectedOption: 2 }, { questionId: 11, selectedOption: 1 }],
    questions: [
      { questionId: 10, questionType: 'READING_MICRO_CHECK', questionText: 'Ý chính là gì?', options: ['A', 'B'] },
      { questionId: 11, questionType: 'READING_MICRO_CHECK', questionText: 'Câu 2?', options: ['C', 'D'] }
    ] };
  const html = page.renderCheckForm(check);
  assert.match(html, /name="q-10" value="2" checked>/);
  assert.match(html, /name="q-11" value="1" checked>/);
  assert.doesNotMatch(html, /name="q-10" value="1" checked/);
  assert.match(html, /data-draft-status/);
});

test('renderCheckForm tolerates a missing draftAnswers feed (practice carries none) and renders unchecked', () => {
  const check = { attemptId: 904, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 1,
    questions: [{ questionId: 12, questionText: 'Ý chính?', options: ['A', 'B'] }] };
  const html = page.renderCheckForm(check);
  assert.match(html, /name="q-12" value="1">/);
  assert.doesNotMatch(html, /checked/);
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

test('renders a full pass with lesson replay and roadmap actions', () => {
  const html = page.renderResult({ totalQuestions: 2, correctAnswers: 2, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] }, 'READING', 23);
  assert.match(html, /href="\/learning\/path\/v2"/);
  assert.match(html, /\/learning\/v2\/reading\?activity=23&amp;mode=review/);
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
  assert.match(html, /1 câu cần xem lại/);
  assert.doesNotMatch(html, />Câu 20</);
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

function mountComprehension({ historyState = null, attempts = [], study = studyReading(), issued = null, historyFails = false } = {}) {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const handlers = {}, calls = [];
  const node = { innerHTML: '', setAttribute() {}, getAttribute() { return null; },
    addEventListener: (name, handler) => { (handlers[name] = handlers[name] || []).push(handler); },
    querySelector: selector => (selector === '.ilp-result' && node.innerHTML.includes('ilp-result')
      ? { insertAdjacentHTML: (where, html) => { node.innerHTML += html; } } : null),
    querySelectorAll: () => [] };
  const document = { readyState: 'loading', visibilityState: 'visible', getElementById: () => node,
    addEventListener: (name, handler) => { handlers['document:' + name] = handler; } };
  const history = { state: historyState, replaceState(state) { history.state = state; } };
  const studyMode = { resolveMode: () => 'LEARN', passedActions: () => '', bindNextAction() {}, bindAttemptHistory() {},
    attemptHistory: list => '<section class="ilp-attempt-history">' + list.length + '</section>',
    showNextAction: async () => {}, restoreCompletedResult: async () => {} };
  const store = new Map(issued == null ? [] : [['jlasComprehensionIssued:5328', String(issued)]]);
  const sessionStorage = { getItem: key => (store.has(key) ? store.get(key) : null), setItem: (key, value) => store.set(key, String(value)) };
  const window = { document, history, sessionStorage, location: { search: '?activity=5328', pathname: '/learning/v2/reading', href: 'x' },
    JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) }, InteractiveStudyMode: studyMode };
  const respond = data => ({ ok: true, json: async () => ({ data }) });
  const fetch = async url => {
    const path = url.replace('/api/learning/v2/activities/5328/comprehension', '');
    calls.push(path);
    if (path === '/attempt-history') { if (historyFails) throw new Error('offline'); return respond(attempts); }
    if (path === '/study') return respond(study);
    if (path === '/micro-check') return respond({ attemptId: 99, questions: [], draftAnswers: [] });
    return respond({});
  };
  const context = vm.createContext({ window, document, URLSearchParams, fetch, history,
    setTimeout, clearTimeout, IntersectionObserver: function () { this.observe = () => {}; this.disconnect = () => {}; } });
  vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-comprehension-study.js'), 'utf8'), context);
  handlers['document:DOMContentLoaded']();
  return { node, calls, history, store, settle: () => new Promise(resolve => setTimeout(resolve, 20)) };
}

const savedLearnResult = {
  jlasComprehensionLearnResult: { activityId: 5328, attemptId: 6, activityType: 'READING',
    result: { passed: false, activityState: 'STUDYING', scorePercent: 0, correctAnswers: 0, totalQuestions: 3,
      passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, reviewRequired: true, review: [] } }
};

test('browser Back to a submitted LEARN attempt restores its saved result instead of issuing a new check', async () => {
  const page = mountComprehension({ historyState: savedLearnResult,
    attempts: [{ attemptId: 6, mode: 'LEARN', passed: false, scorePercent: 0, review: [] }] });
  await page.settle();
  assert.ok(!page.calls.includes('/micro-check'), 'Back must not issue a new attempt: ' + page.calls.join(','));
  assert.match(page.node.innerHTML, /ilp-result/);
  assert.match(page.node.innerHTML, /Chưa đạt/);
});

test('a remembered result the server does not know is ignored and the normal study flow runs', async () => {
  const page = mountComprehension({ historyState: savedLearnResult, attempts: [] });
  await page.settle();
  assert.ok(page.calls.includes('/study'), page.calls.join(','));
  assert.equal(page.history.state.jlasComprehensionLearnResult, undefined);
});

test('Back does not restore an older result once a newer attempt was submitted', async () => {
  const page = mountComprehension({ historyState: savedLearnResult,
    attempts: [{ attemptId: 6, mode: 'LEARN', passed: false, review: [] }, { attemptId: 8, mode: 'LEARN', passed: false, review: [] }] });
  await page.settle();
  assert.ok(page.calls.includes('/study'), page.calls.join(','));
  assert.equal(page.history.state.jlasComprehensionLearnResult, undefined);
});

test('Back does not restore an older result while a newer attempt is open in this tab', async () => {
  const page = mountComprehension({ historyState: savedLearnResult, issued: 7,
    attempts: [{ attemptId: 6, mode: 'LEARN', passed: false, review: [] }] });
  await page.settle();
  assert.ok(page.calls.includes('/study'), page.calls.join(','));
});

test('an unavailable attempt history falls back to the normal study flow instead of an error screen', async () => {
  const page = mountComprehension({ historyState: savedLearnResult, historyFails: true });
  await page.settle();
  assert.ok(page.calls.includes('/study'), page.calls.join(','));
});

test('opening a LEARN check records its attempt so an older remembered result is not restored over it', async () => {
  const page = mountComprehension({ study: studyReading() });
  await page.settle();
  assert.ok(page.calls.includes('/micro-check'), page.calls.join(','));
  assert.equal(page.store.get('jlasComprehensionIssued:5328'), '99');
});

// OD-44 follow-up (owner 2026-10-20): Speed Master N3 material names its source and is never presented as reviewed.
test('a study state with a source note shows it once, escaped, and a state without one shows nothing', () => {
  const noted = page.renderStudy(page.viewModel(studyReading({ sourceNote: 'Nguồn VNJPClub · Speed Master N3 · <chưa duyệt>' })), false);
  assert.equal((noted.match(/ilp-comprehension__source-note/g) || []).length, 1);
  assert.match(noted, /Nguồn VNJPClub · Speed Master N3 · &lt;chưa duyệt&gt;/);
  assert.doesNotMatch(page.renderStudy(page.viewModel(studyListening()), false), /ilp-comprehension__source-note/);
});

// Review 2026-10-20: a new reading opens straight on the inline check screen, and listening answers there too — the
// source note must be on that screen as well, not only on the study screen.
test('the check screen shows the source note for both reading and listening', () => {
  const check = { totalQuestions: 1, questions: [{ questionId: 10, prompt: 'Câu?', options: ['A', 'B', 'C'] }] };
  for (const study of [studyReading({ sourceNote: 'Nguồn VNJPClub · Speed Master N3 · chưa duyệt' }),
    studyListening({ sourceNote: 'Nguồn VNJPClub · Speed Master N3 · chưa duyệt' })]) {
    const html = page.renderCheckScreen(page.viewModel(study), check);
    assert.equal((html.match(/ilp-comprehension__source-note/g) || []).length, 1, study.activityType);
  }
  assert.doesNotMatch(page.renderCheckScreen(page.viewModel(studyReading()), check), /ilp-comprehension__source-note/);
});
