const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-grammar-study.js');
const studyMode = require('../../main/resources/static/js/pages/interactive-study-mode.js');

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

test('offers an early check while retaining practice progress guidance', () => {
  const notReady = page.renderStudy(page.viewModel(study({ checkEligible: true })), null, {}, {});
  assert.doesNotMatch(notReady, /data-check disabled/);
  assert.match(notReady, /0\/1/);

  const ready = page.renderStudy(page.viewModel(study({ gateSatisfiedCount: 1, checkEligible: true,
    points: [point({ gateSatisfied: true, ordering: { words: [], completed: true }, fill: { promptWithBlank: null, completed: true } })] })), null, {}, {});
  assert.doesNotMatch(ready, /data-check disabled/);
});

test('communication grammar completes through practice without showing a quiz action', () => {
  const html = page.renderStudy(page.viewModel(study({ checkRequired: false })), null, {}, {});
  assert.match(html, /tình huống giao tiếp không có bài kiểm tra/);
  assert.doesNotMatch(html, /data-check|Bắt đầu kiểm tra/);
});

test('grammar lesson does not display quiz history controls', () => {
  const html = page.renderStudy(page.viewModel(study()), null, {}, {});
  assert.doesNotMatch(html, /data-attempt-history|Xem lại bài kiểm tra/);
});

test('shows immediate feedback text next to the point it belongs to', () => {
  const html = page.renderStudy(page.viewModel(study()), null, {},
    { 501: { correct: false, explanation: 'Câu đúng là: わたしは グエンです。' } });
  assert.match(html, /is-incorrect/);
  assert.match(html, /Câu đúng là/);
});

test('ordering and fill feedback stay directly beneath their own exercise', () => {
  const html = page.renderStudy(page.viewModel(study()), null, {}, {
    501: {
      ordering: { correct: true, explanation: 'Thứ tự đúng: わたしは グエンです。' },
      fill: { correct: false, explanation: 'Phần điền cần là グエンです。' }
    }
  });
  const orderHeading = html.indexOf('1. Xếp từ thành câu hoàn chỉnh');
  const orderFeedback = html.indexOf('Thứ tự đúng:');
  const fillHeading = html.indexOf('2. Điền phần còn thiếu');
  const fillFeedback = html.indexOf('Phần điền cần là');
  assert.ok(orderHeading < orderFeedback && orderFeedback < fillHeading,
    'phản hồi xếp câu phải nằm trước bài điền từ');
  assert.ok(fillHeading < fillFeedback, 'phản hồi điền từ phải nằm sau chính bài điền từ');
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

test('result points to saved answer details instead of exposing opaque question IDs', () => {
  const html = page.renderResult({ totalQuestions: 2, correctAnswers: 1, scorePercent: 50,
    passed: false, passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2,
    review: [{ questionId: 9876, correct: false }] });
  assert.match(html, /câu cần xem lại/i);
  assert.doesNotMatch(html, /Câu 9876/);
});

test('empty-safe rendering when there are no points at all', () => {
  const html = page.renderStudy(page.viewModel({ points: [], totalPoints: 0, gateSatisfiedCount: 0 }), null, {}, {});
  assert.match(html, /Chưa có mẫu ngữ pháp/);
});

test('review rendering exposes canonical grammar only and has no practice or quiz controls', () => {
  const previousWindow = global.window;
  global.window = { InteractiveStudyMode: studyMode };
  const html = page.renderReview(study(), [{ batchIndex: 0, scorePercent: 50,
    correctAnswers: 1, totalQuestions: 2, review: [{ questionText: 'Câu quiz cũ' }] }]);
  global.window = previousWindow;
  assert.match(html, /Ngữ pháp đã học/);
  assert.match(html, /N1 は N2 です/);
  assert.doesNotMatch(html, /data-order-add|data-fill-check|data-check|data-submit|Câu quiz cũ|Kết quả các lượt làm bài/);
});

test('[JLAS-W0125] a part created by a pace change shows its position; an unsplit activity does not', () => {
  const render = (o) => page.renderStudy(page.viewModel(study(o)), null, {}, {});
  assert.match(render({ partIndex: 2, partCount: 3 }), /Phần 2 \/ 3/);
  assert.doesNotMatch(render({ partIndex: 1, partCount: 1 }), /Phần 1 \/ 1/);
  assert.doesNotMatch(render({}), /Phần \d+ \/ \d+/);
});

// ---- [JLAS-W0113] draft autosave/restore: reopened quiz shows the learner's saved selections ----

test('renderCheck restores previously autosaved selections as checked options', () => {
  const check = { attemptId: 901, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 2,
    draftAnswers: [{ questionId: 1, selectedOption: 2 }, { questionId: 2, selectedOption: 1 }],
    questions: [
      { questionId: 1, questionType: 'MEANING_MATCH', questionText: 'Mẫu "N1 は N2 です" có nghĩa là gì?', options: ['N1 là N2', 'N2 của N1'] },
      { questionId: 2, questionType: 'USAGE_SELECT', questionText: 'Câu nào dùng đúng mẫu "N1 は N2 です"?', options: ['わたしは グエンです。', 'これは ほんです。'] }
    ] };
  const html = page.renderCheck(check);
  assert.match(html, /name="q-1" value="2" checked>/);
  assert.match(html, /name="q-2" value="1" checked>/);
  assert.doesNotMatch(html, /name="q-1" value="1" checked/);
  assert.match(html, /data-draft-status/);
});

test('renderCheck tolerates a missing draftAnswers feed and renders a fresh unchecked quiz', () => {
  const check = { attemptId: 902, activityId: 1, batchIndex: 0, batchCount: 1, totalQuestions: 1,
    questions: [{ questionId: 1, questionType: 'USAGE_SELECT', questionText: 'Q?', options: ['A', 'B'] }] };
  const html = page.renderCheck(check);
  assert.match(html, /name="q-1" value="1">/);
  assert.doesNotMatch(html, /checked/);
});

// ---- §2.23: a passed batch of a multi-batch activity hands the learner the exact next batch ----

test('a passed batch of a multi-batch grammar activity offers the next-batch CTA', () => {
  const html = page.renderResult({ totalQuestions: 4, correctAnswers: 3, scorePercent: 75, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'CHECK_READY', review: [] });
  assert.match(html, /data-check-next>Làm tiếp phần kiểm tra →</);
  assert.match(html, /còn phần kiểm tra tiếp theo để hoàn tất bài học/);
});

test('a fully passed grammar activity shows the completion message and no next-batch CTA', () => {
  const html = page.renderResult({ totalQuestions: 4, correctAnswers: 4, scorePercent: 100, passed: true,
    passThresholdPercent: 70, attemptsUsed: 1, attemptsRemaining: 2, cooldownUntil: null, reviewRequired: false,
    activityState: 'PASSED', review: [] });
  assert.doesNotMatch(html, /data-check-next/);
  assert.match(html, /Tuyệt vời! Bạn đã hoàn thành phần này/);
});

// Owner 2026-10-21: each block of a pattern says what it is; a structure box that only repeats the heading is gone.
test('a pattern shows labelled meaning, example with the pattern marked and note, without repeating its heading', () => {
  const v = page.viewModel(study({ checkRequired: false }));
  Object.assign(v.points[0], { pattern: '〜をお願いします', objective: 'Cho tôi xin ~',
    components: [{ text: '〜をお願いします', role: 'AUXILIARY' }],
    example: { japanese: 'おはようございます。パスポートと予約番号をお願いします。', meaningVi: null },
    usageNote: null, note: 'Câu đề nghị cơ bản, dùng khắp nơi trong dịch vụ.' });
  const html = page.renderStudy(v, null, {}, {});
  assert.match(html, /ilp-point__meaning"><span>Nghĩa<\/span>Cho tôi xin ~/);
  assert.match(html, /<dt>Ví dụ<\/dt><dd><p class="ilp-point__example-ja" lang="ja">おはようございます。パスポートと予約番号<mark class="ilp-point__pattern-in-example">をお願いします<\/mark>。/);
  assert.match(html, /<dt>Lưu ý<\/dt><dd>Câu đề nghị cơ bản/);
  assert.doesNotMatch(html, /<dt>Cấu trúc<\/dt>|Trợ động từ/, 'a one-unit structure equal to the heading is not repeated');
  assert.match(html, /class="ilp-point__practice-title">Luyện tập</);
});

// Owner 2026-10-21: three optional AI sentences per pattern; they are generated on request and checked on the page.
test('the extra practice starts as an optional button and, once generated, offers ordering and fill per sentence', () => {
  const empty = page.renderExtra(501, {});
  assert.match(empty, /Luyện thêm <span>tùy chọn<\/span>/);
  assert.match(empty, /data-extra-generate="501">Tạo 3 câu luyện thêm</);
  assert.match(empty, /Không tính điểm, không bắt buộc/);
  const ready = page.renderExtra(501, { status: 'ready', label: 'Câu luyện thêm do AI tạo · chưa rà soát', items: [
    { japanese: 'お水を一杯お願いします。', meaningVi: 'Cho tôi xin một cốc nước.', parts: ['お水を', '一杯', 'お願いします。'],
      prompt: 'お水を一杯_____。', answer: 'お願いします', placed: [], order: page.extraOrder(3, 1), fill: '' }] });
  assert.equal((ready.match(/data-extra-add="501:0:/g) || []).length, 3);
  assert.match(ready, /お水を一杯_____。/);
  assert.match(ready, /data-extra-fill-check="501:0"/);
  assert.match(ready, /Câu luyện thêm do AI tạo · chưa rà soát/);
  assert.doesNotMatch(ready, /data-extra-generate/);
  assert.notDeepEqual(page.extraOrder(3, 1), [0, 1, 2], 'tiles never start solved');
  assert.ok(page.sameAnswer('お願いします。', 'お願いします'));
  assert.ok(!page.sameAnswer('', 'お願いします'));
});

test('extra practice is not offered in the read-only review', () => {
  const v = page.viewModel(study({ checkRequired: false, activityState: 'PASSED' }));
  assert.doesNotMatch(page.renderStudy(v, null, {}, {}, 'REVIEW'), /data-extra-generate/);
  assert.match(page.renderStudy(v, null, {}, {}, 'LEARN'), /data-extra-generate/);
});
