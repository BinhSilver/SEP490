const test = require('node:test');
const assert = require('node:assert/strict');
const review = require('../../main/resources/static/js/pages/admin-tutor-review.js');

test('list row shows how often a question was asked and its Japanese word', () => {
  assert.equal(review.itemMeta({ askCount: 3, word: '誕生日' }), '3 lượt hỏi · Từ: 誕生日');
  assert.equal(review.itemMeta({ askCount: 1, word: null }), '1 lượt hỏi');
});

test('the answer text is sent only when the reviewer changed it', () => {
  assert.deepEqual(review.answerUpdate({ answer: 'abc' }, 'APPROVED', 'abc '), { status: 'APPROVED' });
  assert.deepEqual(review.answerUpdate({ answer: 'abc' }, 'PENDING', ' xyz'), { status: 'PENDING', answer: 'xyz' });
  assert.deepEqual(review.answerUpdate({ answer: 'abc' }, 'REJECTED', null), { status: 'REJECTED' });
});
