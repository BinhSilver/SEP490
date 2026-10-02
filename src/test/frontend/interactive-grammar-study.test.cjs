const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-grammar-study.js');

function point(overrides = {}) {
  return Object.assign({
    pointId: 501, orderIndex: 1, pattern: 'N1 は N2 です', objective: 'N1 là N2', usageNote: 'Câu khẳng định cơ bản.',
    components: [
      { text: 'N1', role: 'SLOT' }, { text: 'は', role: 'PARTICLE' },
      { text: 'N2', role: 'SLOT' }, { text: 'です', role: 'AUXILIARY' }
    ],
    example: { japanese: 'わたしは グエンです。', meaningVi: 'Tôi là Nguyễn.' },
    note: 'は đọc là wa ở vị trí này.',
    ordering: { words: [{ tokenId: 1, text: 'グエンです。' }, { tokenId: 0, text: 'わたしは' }], completed: false },
    fill: { promptWithBlank: 'わたしは _____', completed: false },
    gateSatisfied: false
  }, overrides);
}

function study(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'GRAMMAR', activityState: 'STUDYING', sourceRevision: 'rev-1',
    totalPoints: 1, gateSatisfiedCount: 0, checkEligible: false, points: [point()]
  }, overrides);
}

test('sorts points by order index', () => {
  const model = page.viewModel(study({ points: [point({ pointId: 2, orderIndex: 2 }), point({ pointId: 1, orderIndex: 1 })] }));
  assert.equal(model.points[0].pointId, 1);
  assert.equal(model.points[1].pointId, 2);
});

test('renders the component breakdown with role labels, never fabricating a role', () => {
  const html = page.renderFormula(point().components);
  assert.match(html, /is-particle/);
  assert.match(html, /is-auxiliary/);
  assert.match(html, /Trợ từ/);
  assert.match(html, /Trợ động từ/);
});

test('renders the canonical example without the fill-blank answer inline', () => {
  const html = page.renderExample(point().example);
  assert.match(html, /わたしは グエンです。/);
  assert.match(html, /Tôi là Nguyễn\./);
});

test('ordering pool never shows the correct order as already placed', () => {
  const html = page.renderOrdering(501, point().ordering, []);
  assert.match(html, /data-order-add="501"/);
  assert.doesNotMatch(html, /is-placed/);
  assert.match(html, /disabled/, 'submit is disabled until every token is placed');
});

test('ordering submit enables only once every token has been placed', () => {
  const html = page.renderOrdering(501, point().ordering, [1, 0]);
  assert.match(html, /is-placed/);
  assert.doesNotMatch(html, /data-order-check="501" disabled/);
});

test('fill practice never reveals the missing word in the prompt', () => {
  const html = page.renderFill(501, point().fill);
  assert.match(html, /_____/);
  assert.doesNotMatch(html, /グエンです/);
});

test('a completed practice shows a positive state instead of the input again being required', () => {
  const html = page.renderFill(501, Object.assign({}, point().fill, { completed: true }));
  assert.match(html, /Đã hoàn thành/);
});

test('opens the check only when every point\'s gate is satisfied', () => {
  const notReady = page.renderStudy(page.viewModel(study()), null, {}, {});
  assert.match(notReady, /data-check disabled/);

  const ready = page.renderStudy(page.viewModel(study({ gateSatisfiedCount: 1, checkEligible: true,
    points: [point({ gateSatisfied: true, ordering: { words: [], completed: true }, fill: { promptWithBlank: null, completed: true } })] })), null, {}, {});
  assert.doesNotMatch(ready, /data-check disabled/);
});

test('shows immediate feedback text next to the point it belongs to', () => {
  const html = page.renderStudy(page.viewModel(study()), null, {},
    { 501: { correct: false, explanation: 'Câu đúng là: わたしは グエンです。' } });
  assert.match(html, /is-incorrect/);
  assert.match(html, /Câu đúng là/);
});

test('renders every micro-check question with 1-based options and never exposes an answer key', () => {
  const check = { attemptId: 900, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 2, questions: [
    { questionId: 1, questionType: 'MEANING_MATCH', questionText: 'Mẫu "N1 は N2 です" có nghĩa là gì?', options: ['N1 là N2', 'N2 của N1'] },
    { questionId: 2, questionType: 'USAGE_SELECT', questionText: 'Câu nào dùng đúng mẫu "N1 は N2 です"?', options: ['わたしは グエンです。', 'これは ほんです。'] }
  ] };
  const html = page.renderCheck(check);
  assert.match(html, /data-attempt-id="900"/);
  assert.match(html, /value="1"/);
  assert.match(html, /value="2"/);
  assert.doesNotMatch(html, /correctOption|answerKey|data-answer/);
});

test('renders a pass and a cooldown/review gate', () => {
  const pass = page.renderResult({ totalQuestions: 4, correctAnswers: 3, scorePercent: 75, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] });
  assert.match(pass, /Đã đạt/);
  assert.match(pass, /75%/);

  const fail = page.renderResult({ totalQuestions: 4, correctAnswers: 0, scorePercent: 0, passed: false,
    passThresholdPercent: 70, attemptsUsed: 3, attemptsRemaining: 0, cooldownUntil: '2026-09-29T15:30:00',
    reviewRequired: true, activityState: 'COOLDOWN',
    review: [{ questionId: 1, correctOption: 1, selectedOption: 2, correct: false }] });
  assert.match(fail, /Chưa đạt/);
  assert.match(fail, /Lần thử 3\/3/);
  assert.match(fail, /Hãy ôn lại các mẫu đã sai/);
});

test('empty-safe rendering when there are no points at all', () => {
  const html = page.renderStudy(page.viewModel({ points: [], totalPoints: 0, gateSatisfiedCount: 0 }), null, {}, {});
  assert.match(html, /Chưa có mẫu ngữ pháp/);
});
