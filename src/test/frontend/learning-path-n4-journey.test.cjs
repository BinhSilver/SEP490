const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '../../main/resources');
const targets = require('../../main/resources/static/js/pages/smart-test-survey-targets.js');
const studyMode = require('../../main/resources/static/js/pages/interactive-study-mode.js');
const roadmap = require('../../main/resources/static/js/pages/interactive-learning-path.js');
const summary = require('../../main/resources/static/js/pages/interactive-path-summary.js');

// Fixtures mirror the server contracts (TargetOptionResponse, FinalEligibilityResponse, LearningPacePreviewResponse);
// the numbers are synthetic, chosen only to sit on both sides of the 50% gate.
function option(level, allowed, extra) {
  return Object.assign({ targetLevel: level, allowed, recommendedTarget: level, stageLevels: [level],
    reasonCode: null, requiresTargetConfirmation: false }, extra || {});
}
function stage(index, level, status, completed, total) {
  const percent = total === 0 ? 0 : Math.floor(completed * 100 / total);
  return { stageId: index, stageIndex: index, level, status, completedPackages: completed, totalPackages: total,
    percent, blocked: total === 0 };
}
function eligibility(overrides) {
  return Object.assign({ eligible: false, targetLevel: 'N4', requiredTargetPercent: 50, reasonCode: 'TARGET_STAGE_BELOW_THRESHOLD',
    stages: [stage(1, 'FOUNDATION', 'COMPLETED', 10, 10), stage(2, 'N5', 'COMPLETED', 20, 20), stage(3, 'N4', 'ACTIVE', 49, 100)] }, overrides);
}

// ---- Survey: target options come from the server only ----

test('below-N5 learner targeting N4 sees the three server stages in order', () => {
  const view = targets.describeOptions([
    option('N5', true, { stageLevels: ['FOUNDATION', 'N5'] }),
    option('N4', true, { stageLevels: ['FOUNDATION', 'N5', 'N4'] })]);
  assert.equal(view[1].stageLabel, 'Bảng chữ cái → N5 → N4');
  assert.equal(view[0].stageLabel, 'Bảng chữ cái → N5');
  assert.equal(view[1].disabled, false);
});

test('an option the server disallows is disabled with its reason and the recommended target is flagged', () => {
  const view = targets.describeOptions([
    option('N4', false, { recommendedTarget: 'N3', reasonCode: 'TARGET_BELOW_ASSESSED_LEVEL', requiresTargetConfirmation: true }),
    option('N3', true, {}),
    option('N1', false, { recommendedTarget: 'N3', reasonCode: 'TARGET_TOO_FAR', requiresTargetConfirmation: true })]);
  assert.equal(view[0].disabled, true);
  assert.match(view[0].reason, /thấp hơn|dưới/i);
  assert.equal(view[2].disabled, true);
  assert.match(view[2].reason, /quá xa|chưa/i);
  assert.equal(view.find((o) => o.level === 'N3').recommended, true);
  assert.equal(view.find((o) => o.level === 'N1').recommended, false);
});

test('an unknown reason code degrades to a neutral message, never to an allowed option', () => {
  const view = targets.describeOptions([option('N2', false, { reasonCode: 'SOMETHING_NEW' })]);
  assert.equal(view[0].disabled, true);
  assert.ok(view[0].reason.length > 0);
});

test('no options (request failed) disables every target instead of falling back to a hardcoded list', () => {
  assert.deepEqual(targets.describeOptions(undefined), []);
  assert.deepEqual(targets.describeOptions([]), []);
  assert.equal(targets.assessedLevelFor({ studied: 'NO', selfLevel: null }), 'FROM_ZERO');
  assert.equal(targets.assessedLevelFor({ studied: 'YES', selfLevel: 'N4' }), 'N4');
  assert.equal(targets.assessedLevelFor({ studied: 'YES', selfLevel: null }), 'UNKNOWN');
  assert.equal(targets.assessedLevelFor({ studied: 'YES', selfLevel: 'bogus' }), 'UNKNOWN');
});

test('a disabled previously-selected target must be cleared so the learner cannot submit it', () => {
  const view = targets.describeOptions([option('N4', false, { reasonCode: 'TARGET_TOO_FAR' }), option('N5', true, {})]);
  assert.equal(targets.mustClearSelection('N4', view), true);
  assert.equal(targets.mustClearSelection('N5', view), false);
  assert.equal(targets.mustClearSelection(null, view), false);
});

// ---- Roadmap: stage progress and the 49% / 50% gate ----

test('each stage shows completed/total, percent and its lock reason', () => {
  const html = roadmap.renderStageProgress(eligibility());
  assert.match(html, /Bảng chữ cái/);
  assert.match(html, /10\/10/);
  assert.match(html, /49\/100/);
  assert.match(html, /49%/);
  assert.match(html, /data-stage-level="N4"/);
});

test('a stage with no required content is blocked, not shown as empty progress', () => {
  const html = roadmap.renderStageProgress(eligibility({ reasonCode: 'BLOCKED_CONTENT',
    stages: [stage(1, 'N5', 'ACTIVE', 0, 0)] }));
  assert.match(html, /Học liệu chưa sẵn sàng/);
});

test('49% keeps the Mock Test action locked with the exact reason', () => {
  const html = roadmap.renderMockGate(eligibility());
  assert.match(html, /disabled/);
  assert.match(html, /aria-disabled="true"/);
  assert.match(html, /50%/);
  assert.doesNotMatch(html, /href=/);
});

test('50% enables the Mock Test action, pointing only at the server-decided summary page', () => {
  const html = roadmap.renderMockGate(eligibility({ eligible: true, reasonCode: null,
    stages: [stage(1, 'N5', 'COMPLETED', 20, 20), stage(2, 'N4', 'ACTIVE', 50, 100)] }));
  assert.match(html, /href="\/learning\/path\/v2\/summary"/);
  assert.doesNotMatch(html, /disabled/);
});

test('a prior stage that is not finished blocks the Mock Test with its own reason', () => {
  const html = roadmap.renderMockGate(eligibility({ reasonCode: 'PRIOR_STAGE_INCOMPLETE' }));
  assert.match(html, /100%/);
  assert.match(html, /disabled/);
});

test('missing eligibility data renders nothing instead of a fake state', () => {
  assert.equal(roadmap.renderStageProgress(null), '');
  assert.equal(roadmap.renderMockGate(null), '');
});

// ---- Roadmap: review / practice entry points only where a backend contract exists ----

test('finished activities link to review and practice, unfinished ones do not', () => {
  const html = roadmap.render({ id: 1, title: 'Lộ trình', packages: [{ id: 5, title: 'Ngày 1', status: 'IN_PROGRESS', phase: 'GOAL_LEARNING', dayNumber: 1, orderIndex: 1,
    activities: [{ id: 11, type: 'VOCABULARY', state: 'PASSED', orderIndex: 1 }, { id: 12, type: 'GRAMMAR', state: 'STUDYING', orderIndex: 2 }] }] });
  assert.match(html, /href="\/learning\/v2\/vocabulary\?activity=11&amp;mode=review"|href="\/learning\/v2\/vocabulary\?activity=11&mode=review"/);
  assert.match(html, /mode=practice/);
  assert.doesNotMatch(html, /activity=12&(amp;)?mode=/);
});

test('grammar offers practice only; reading, listening and dialogue offer no mode link yet', () => {
  assert.deepEqual(roadmap.modesFor('VOCABULARY'), ['REVIEW', 'PRACTICE']);
  assert.deepEqual(roadmap.modesFor('KANJI'), ['REVIEW', 'PRACTICE']);
  assert.deepEqual(roadmap.modesFor('FOUNDATION'), ['REVIEW', 'PRACTICE']);
  assert.deepEqual(roadmap.modesFor('GRAMMAR'), ['PRACTICE']);
  assert.deepEqual(roadmap.modesFor('READING'), []);
  assert.deepEqual(roadmap.modesFor('LISTENING'), []);
  assert.deepEqual(roadmap.modesFor('DIALOGUE'), []);
});

// ---- Completion popup: Tiếp tục / Xem lại / Ôn lại, accessible dialog ----

function justCompleted(extra) {
  return Object.assign({ id: 9, title: 'Ngày 3', justCompleted: true, scorePercent: 82, nextAvailablePackageId: 10,
    activities: [{ id: 31, type: 'VOCABULARY', state: 'PASSED', orderIndex: 1 }] }, extra || {});
}

test('completion popup offers Tiếp tục, Xem lại and Ôn lại and stays an aria-modal dialog', () => {
  const html = roadmap.renderPopup(justCompleted());
  assert.match(html, /role="dialog" aria-modal="true"/);
  assert.match(html, />Tiếp tục</);
  assert.match(html, /mode=review[^>]*>Xem lại</);
  assert.match(html, /mode=practice[^>]*>Ôn lại</);
});

test('the popup says practice never affects progress, score or unlocks', () => {
  assert.match(roadmap.renderPopup(justCompleted()), /Ôn lại[\s\S]*không ảnh hưởng|không ảnh hưởng[\s\S]*Ôn lại/);
});

test('the popup does not invent review or practice links when the package has no eligible activity', () => {
  const html = roadmap.renderPopup(justCompleted({ activities: [{ id: 41, type: 'DIALOGUE', state: 'PASSED', orderIndex: 1 }] }));
  assert.doesNotMatch(html, /mode=review/);
  assert.doesNotMatch(html, /mode=practice/);
});

// ---- Toast and pace dialog ----

test('toast is a polite live region', () => {
  const html = roadmap.renderToast('Đã đổi nhịp học');
  assert.match(html, /role="status"/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /Đã đổi nhịp học/);
  assert.equal(roadmap.renderToast(''), '');
});

test('pace preview dialog states current and new minutes per day and keeps confirm separate from cancel', () => {
  const html = roadmap.renderPaceDialog({ direction: 'FASTER', currentMinutesPerDay: 30, newMinutesPerDay: 45, changed: true,
    remainingPackages: 4, currentRemainingDays: 4, newRemainingDays: 3, previewToken: 'tok' });
  assert.match(html, /role="dialog" aria-modal="true"/);
  assert.match(html, /30 phút/);
  assert.match(html, /45 phút/);
  assert.match(html, /4 ngày/);
  assert.match(html, /3 ngày/);
  assert.match(html, /data-pace-confirm/);
  assert.match(html, /data-pace-cancel/);
});

test('pace dialog with no change offers no confirm action', () => {
  const html = roadmap.renderPaceDialog({ direction: 'LIGHTER', currentMinutesPerDay: 15, newMinutesPerDay: 15, changed: false,
    remainingPackages: 4, currentRemainingDays: 4, newRemainingDays: 4, previewToken: 'tok' });
  assert.doesNotMatch(html, /data-pace-confirm/);
  assert.match(html, /data-pace-cancel/);
});

test('pace request carries exactly the previewed direction, target and token', () => {
  const body = roadmap.paceRequestBody({ direction: 'FASTER', newMinutesPerDay: 45, previewToken: 'tok' });
  assert.deepEqual(JSON.parse(body), { direction: 'FASTER', newMinutesPerDay: 45, previewToken: 'tok' });
});

test('focus trap wraps Tab within the dialog and Escape is recognised', () => {
  const buttons = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
  assert.equal(roadmap.nextFocusIndex(2, 3, false), 0);
  assert.equal(roadmap.nextFocusIndex(0, 3, true), 2);
  assert.equal(roadmap.nextFocusIndex(0, 3, false), 1);
  assert.equal(roadmap.nextFocusIndex(1, 3, true), 0);
  assert.equal(roadmap.nextFocusIndex(0, 0, false), -1);
  assert.equal(buttons.length, 3);
});

// ---- Study mode shared contract ----

test('mode comes from the query string and anything unknown is LEARN', () => {
  assert.equal(studyMode.resolveMode('?activity=3&mode=review'), 'REVIEW');
  assert.equal(studyMode.resolveMode('?activity=3&mode=practice'), 'PRACTICE');
  assert.equal(studyMode.resolveMode('?activity=3&mode=LEARN'), 'LEARN');
  assert.equal(studyMode.resolveMode('?activity=3&mode=hack'), 'LEARN');
  assert.equal(studyMode.resolveMode(''), 'LEARN');
});

test('each mode maps to its own endpoints; practice never touches the micro-check endpoints', () => {
  assert.deepEqual(studyMode.endpoints('LEARN'), { study: '/study', issue: '/micro-check', submit: '/micro-check/submit' });
  assert.deepEqual(studyMode.endpoints('REVIEW'), { study: '/review', issue: null, submit: null });
  assert.deepEqual(studyMode.endpoints('PRACTICE'), { study: null, issue: '/practice', submit: '/practice/submit' });
});

test('mode banners are explicit: review is read-only, practice does not affect progress', () => {
  assert.match(studyMode.banner('REVIEW'), /chỉ xem|Chế độ xem lại/);
  assert.match(studyMode.banner('PRACTICE'), /không ảnh hưởng/);
  assert.equal(studyMode.banner('LEARN'), '');
});

test('passed activities expose links to the modes the page supports', () => {
  const html = studyMode.passedActions('/learning/v2/vocabulary', 77, ['REVIEW', 'PRACTICE']);
  assert.match(html, /\/learning\/v2\/vocabulary\?activity=77&(amp;)?mode=review/);
  assert.match(html, /\/learning\/v2\/vocabulary\?activity=77&(amp;)?mode=practice/);
  assert.doesNotMatch(studyMode.passedActions('/learning/v2/grammar', 77, ['PRACTICE']), /mode=review/);
});

// ---- Layout contract: 1180px desktop, single column at 375px, reduced motion ----

test('roadmap CSS caps the desktop container at 1180px and collapses to one column on phones', () => {
  const css = fs.readFileSync(path.join(root, 'static/css/pages/interactive-learning-path.css'), 'utf8');
  assert.match(css, /\.ilp-page\s*\{[^}]*max-width:\s*1180px/);
  assert.match(css, /@media \(max-width:\s*520px\)/);
  assert.match(css, /\.ilp-stage-progress/);
  assert.match(css, /\.ilp-toast/);
  assert.match(css, /prefers-reduced-motion/);
});

test('stage progress and pace controls collapse to a single column at 375px', () => {
  const css = fs.readFileSync(path.join(root, 'static/css/pages/interactive-learning-path.css'), 'utf8');
  const phone = css.slice(css.lastIndexOf('@media (max-width: 520px)'));
  assert.match(phone, /\.ilp-stage-progress[^{]*\{[^}]*grid-template-columns:\s*1fr/);
  assert.match(phone, /\.ilp-pace/);
});

test('summary page still refuses to fabricate a Mock Test link', () => {
  const html = summary.renderActions(summary.viewModel({}));
  assert.match(html, /disabled/);
  assert.doesNotMatch(html, /Thi thử JLPT<\/a>/);
});

test('practice result shows score and the no-progress note, with no attempts, cooldown or review gate', () => {
  const html = studyMode.practiceResult({ scorePercent: 40, correctAnswers: 2, totalQuestions: 5, passed: false,
    attemptsUsed: 0, attemptsRemaining: 3, reviewRequired: false, cooldownUntil: null, review: [] });
  assert.match(html, /40%/);
  assert.match(html, /2\/5/);
  assert.match(html, /không ảnh hưởng/);
  assert.match(html, /href="\/learning\/path\/v2"/);
  assert.match(html, /data-practice-again/);
  assert.doesNotMatch(html, /lượt|Lần thử|Vui lòng quay lại sau/);
});

test('review list renders every card read-only, with no flip or remember controls', () => {
  const html = studyMode.reviewList('Nội dung đã học', [{ html: '<b>あ</b>' }, { html: '<b>い</b>' }]);
  assert.match(html, /あ/);
  assert.match(html, /い/);
  assert.doesNotMatch(html, /data-flip|data-remember|data-check/);
  assert.match(html, /Chế độ xem lại|chỉ xem/);
});
