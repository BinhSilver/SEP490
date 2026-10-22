const test = require('node:test');
const assert = require('node:assert/strict');
const route = require('../../main/resources/static/js/pages/smart-test-result-route.js');

function result(overrides) {
  return Object.assign({ estimatedOverallLevel: 'N5', abilityBand: 'N5 Căn bản', targetLevel: 'N3',
    recommendedTarget: 'N4', requiresTargetConfirmation: true, selfReportedLevel: 'UNKNOWN',
    targetReasonCode: 'TARGET_TOO_FAR' }, overrides);
}

test('OD-35: an UNKNOWN learner two levels below the target is asked to study up from the assessed level', () => {
  const notice = route.notice(result());
  assert.equal(notice.kind, 'UNKNOWN_FAR');
  assert.equal(notice.askConsent, true);
  assert.deepEqual(notice.stages, ['N5', 'N4', 'N3']);
  assert.match(notice.text, /N5 → N4 → N3/);
});

test('OD-35: one level below is the ordinary route, no question', () => {
  const notice = route.notice(result({ estimatedOverallLevel: 'N4', abilityBand: 'N4 Căn bản',
    requiresTargetConfirmation: false, targetReasonCode: null }));
  assert.notEqual(notice.kind, 'UNKNOWN_FAR');
  assert.equal(notice.askConsent, false);
});

test('OD-34: an unreleased target keeps the result but offers no path yet', () => {
  const notice = route.notice(result({ estimatedOverallLevel: 'N4', abilityBand: 'N4 Căn bản', targetLevel: 'N2',
    recommendedTarget: 'N3', targetReasonCode: 'PATH_NOT_SUPPORTED' }));
  assert.equal(notice.kind, 'PENDING_RELEASE');
  assert.equal(notice.blockCreate, true);
  assert.match(notice.text, /N2 chưa được phát hành/);
});

test('OD-37: a Nhập môn result on the N5 → N4 survey explains the N5 stage without asking', () => {
  const notice = route.notice(result({ selfReportedLevel: 'N5', targetLevel: 'N4', abilityBand: 'N5 Nhập môn',
    requiresTargetConfirmation: false, targetReasonCode: null }));
  assert.equal(notice.kind, 'INTRO_STAGE');
  assert.equal(notice.askConsent, false);
  assert.match(notice.text, /chặng N5/);
});

test('a plain result has no extra notice', () => {
  const notice = route.notice(result({ selfReportedLevel: 'N4', estimatedOverallLevel: 'N4', targetLevel: 'N4',
    abilityBand: 'N4 Căn bản', requiresTargetConfirmation: false, targetReasonCode: null }));
  assert.equal(notice.kind, null);
});

test('OD-34: the survey keeps N2/N1 selectable for an UNKNOWN learner and says the path waits for the release', () => {
  const targets = require('../../main/resources/static/js/pages/smart-test-survey-targets.js');
  const [n2] = targets.describeOptions([{ targetLevel: 'N2', allowed: true, recommendedTarget: 'N2', stageLevels: [],
    reasonCode: 'PENDING_RELEASE' }]);
  assert.equal(n2.disabled, false);
  assert.match(n2.reason, /chưa phát hành/);
});
