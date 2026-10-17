const test = require('node:test');
const assert = require('node:assert/strict');
const mode = require('../../main/resources/static/js/pages/interactive-study-mode.js');

test('rehearsal resets only local cards and keeps server completion untouched', () => {
  const source = { activityState: 'PASSED', cards: [{ memberId: 4, flipped: true, remembered: true }] };
  const local = mode.rehearsal(source);
  assert.equal(local.cards[0].flipped, false);
  assert.equal(local.cards[0].remembered, false);
  assert.equal(local.checkEligible, false);
  assert.equal(source.cards[0].remembered, true);
  assert.equal(source.activityState, 'PASSED');
  mode.rehearseCard(local, 4, 'remember', true);
  assert.equal(local.rememberedCount, 0);
  mode.rehearseCard(local, 4, 'flip');
  mode.rehearseCard(local, 4, 'remember', true);
  assert.equal(local.rememberedCount, 1);
  assert.equal(local.checkEligible, true);
});

test('forgotten cards remain available for another local flip without losing content', () => {
  const local = mode.rehearsal({ cards: [{memberId: 7, front: '学校'}] });
  mode.rehearseCard(local, 7, 'flip');
  mode.rehearseCard(local, 7, 'remember', false);
  assert.equal(local.cards[0].front, '学校');
  assert.equal(local.cards[0].flipped, false);
  assert.equal(local.reviewRemaining, 1);
});

test('practice quiz result offers flashcard review before another quiz', () => {
  const html = mode.practiceResult({ scorePercent: 60, correctAnswers: 3, totalQuestions: 5, review: [] });
  assert.match(html, /data-practice-review/);
  assert.match(html, /Ôn flashcard rồi kiểm tra lại/);
  assert.doesNotMatch(html, /data-practice-again/);
});

test('non-flashcard practice result labels the return action as lesson review', () => {
  const html = mode.practiceResult({ scorePercent: 60, correctAnswers: 3, totalQuestions: 5 }, 'Ôn bài rồi kiểm tra lại');
  assert.match(html, /Ôn bài rồi kiểm tra lại/);
  assert.doesNotMatch(html, /Ôn flashcard rồi kiểm tra lại/);
});

test('quiz history bar keeps each saved attempt selectable with its score', () => {
  const html = mode.attemptHistory([
    { attemptId: 11, attemptNumber: 1, batchIndex: 0, mode: 'LEARN', scorePercent: 40, correctAnswers: 2, totalQuestions: 5, review: [] },
    { attemptId: 12, attemptNumber: 2, batchIndex: 0, mode: 'LEARN', scorePercent: 80, correctAnswers: 4, totalQuestions: 5, review: [] }
  ]);
  assert.equal((html.match(/data-quiz-history-index=/g) || []).length, 2);
  assert.match(html, /Lần 1/);
  assert.match(html, /Lần 2/);
  assert.match(html, /40%/);
  assert.match(html, /80%/);
});

test('saved quiz feedback explains the actual answer before optional AI help', () => {
  const html = mode.attemptHistory([{ attemptId: 12, attemptNumber: 1, batchIndex: 0,
    mode: 'LEARN', scorePercent: 0, correctAnswers: 0, totalQuestions: 1,
    review: [{ questionId: 77, questionText: '静か nghĩa là gì?', correct: false,
      correctAnswerText: 'yên tĩnh', selectedAnswerText: 'ồn ào',
      explanation: '静か là tính từ ナ, dùng để chỉ sự yên tĩnh.' }] }]);
  assert.match(html, /静か nghĩa là gì\?/);
  assert.match(html, /Đáp án: yên tĩnh/);
  assert.match(html, /Bạn chọn: ồn ào/);
  assert.match(html, /ilp-attempt-history__answers/);
  assert.match(html, /静か là tính từ ナ/);
  assert.match(html, /Giải thích bằng AI/);
});

test('selecting a saved attempt shows only its matching feedback panel', () => {
  const buttons = [0, 1].map(index => ({ dataset: { quizHistoryIndex: String(index) },
    setAttribute(name, value) { this[name] = value; } }));
  const details = [0, 1].map(index => ({ dataset: { quizHistoryDetail: String(index) }, hidden: index !== 1 }));
  const node = { addEventListener(type, listener) { this.click = listener; },
    contains(button) { return buttons.includes(button); },
    querySelectorAll(selector) { return selector.includes('detail') ? details : buttons; } };
  mode.bindAttemptHistory(node);
  node.click({ target: { closest() { return buttons[0]; } } });
  assert.equal(buttons[0]['aria-current'], 'true');
  assert.equal(buttons[1]['aria-current'], 'false');
  assert.equal(details[0].hidden, false);
  assert.equal(details[1].hidden, true);
});

test('comprehension history shows the frozen source belonging to each attempt, never a current lesson source', () => {
  const html = mode.attemptHistory([
    { attemptId: 1, attemptNumber: 1, batchIndex: 0, mode: 'LEARN', scorePercent: 40,
      review: [], sourceStatus: 'AVAILABLE', source: { audioUrl: '/audio/old.mp3', transcriptJa: '女：こんにちは。' } },
    { attemptId: 2, attemptNumber: 2, batchIndex: 0, mode: 'LEARN', scorePercent: 80,
      review: [], sourceStatus: 'AVAILABLE', source: { audioUrl: '/audio/new.mp3' } },
    { attemptId: 3, attemptNumber: 3, batchIndex: 0, mode: 'LEARN', scorePercent: 100,
      review: [], sourceStatus: 'UNAVAILABLE', source: null }
  ]);
  assert.match(html, /\/audio\/old\.mp3/);
  assert.match(html, /女：こんにちは。/);
  assert.match(html, /\/audio\/new\.mp3/);
  assert.match(html, /Nguồn của lượt này không còn/);
  assert.doesNotMatch(html, /data-reached-passage/);
});

test('reopened quiz results show saved attempts without requesting a new quiz or mixing into lesson review', async () => {
  assert.equal(mode.resolveMode('?activity=17&mode=results'), 'RESULTS');
  assert.deepEqual(mode.endpoints('RESULTS'), { study: null, issue: null, submit: null });
  const calls = [];
  const node = { innerHTML: '' };
  await mode.loadSavedResults(node, async path => {
    calls.push(path);
    return [{ attemptId: 11, attemptNumber: 1, batchIndex: 0, mode: 'LEARN', scorePercent: 40,
      correctAnswers: 2, totalQuestions: 5, review: [{ questionText: '旧い質問', correct: false,
        correctAnswerText: '正解' }], sourceStatus: 'AVAILABLE', source: { passage: '元の文章' } },
    { attemptId: 12, attemptNumber: 2, batchIndex: 0, mode: 'PRACTICE', scorePercent: 80,
      correctAnswers: 4, totalQuestions: 5, review: [] }];
  }, '/learning/v2/reading', 17);
  assert.deepEqual(calls, ['/attempt-history']);
  assert.match(node.innerHTML, /Lần 1/);
  assert.match(node.innerHTML, /Lần 2/);
  assert.match(node.innerHTML, /元の文章/);
  assert.match(node.innerHTML, /mode=review/);
  assert.match(node.innerHTML, /mode=practice/);
  assert.doesNotMatch(node.innerHTML, /data-check|data-submit|data-reached-passage/);
});
