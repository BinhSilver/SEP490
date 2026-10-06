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
