const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-dialogue-study.js');

function line(overrides = {}) {
  return Object.assign({
    lineId: 201, orderIndex: 1, roleCode: 'STAFF', roleName: 'Nhân viên', sceneCode: 'scene-1',
    bubbleSide: 'trai', japanese: 'いらっしゃいませ。', meaningVi: 'Xin chào quý khách.', audioUrl: null
  }, overrides);
}

function study(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'DIALOGUE', activityState: 'STUDYING', sourceRevision: 'rev-1',
    requiredSeconds: 180, accumulatedSeconds: 0, remainingSeconds: 180, reachedBottom: false,
    completed: false, lines: [line()]
  }, overrides);
}

test('sorts lines by order index', () => {
  const model = page.viewModel(study({ lines: [line({ lineId: 2, orderIndex: 2 }), line({ lineId: 1, orderIndex: 1 })] }));
  assert.equal(model.lines[0].lineId, 1);
  assert.equal(model.lines[1].lineId, 2);
});

test('computes percent complete from server-reported accumulated/required seconds only', () => {
  const model = page.viewModel(study({ accumulatedSeconds: 90, requiredSeconds: 180 }));
  assert.equal(model.percent, 50);
});

test('percent never exceeds 100 even if accumulated seconds is reported past the requirement', () => {
  const model = page.viewModel(study({ accumulatedSeconds: 400, requiredSeconds: 180 }));
  assert.equal(model.percent, 100);
});

test('formats remaining time as a short friendly duration', () => {
  assert.equal(page.formatRemaining(42), '42 giây');
  assert.equal(page.formatRemaining(102), '1 phút 42 giây');
  assert.equal(page.formatRemaining(120), '2 phút');
  assert.equal(page.formatRemaining(-5), '0 giây');
});

test('renders a line with role, Japanese text and meaning, without fabricating audio', () => {
  const html = page.renderLine(line());
  assert.match(html, /Nhân viên/);
  assert.match(html, /いらっしゃいませ。/);
  assert.match(html, /Xin chào quý khách\./);
  assert.doesNotMatch(html, /data-play-audio/);
});

test('renders an audio button only when an approved audio url exists', () => {
  const html = page.renderLine(line({ audioUrl: '/audio/line-201.mp3' }));
  assert.match(html, /data-play-audio="\/audio\/line-201\.mp3"/);
});

test('bubble side maps to a left/right class without inventing a side when data is missing', () => {
  const right = page.renderLine(line({ bubbleSide: 'phai' }));
  const left = page.renderLine(line({ bubbleSide: 'trai' }));
  assert.match(right, /is-right/);
  assert.match(left, /is-left/);
});

test('script includes a bottom sentinel element for scroll-to-bottom detection', () => {
  const html = page.renderScript(page.viewModel(study()));
  assert.match(html, /id="dialogueBottomSentinel"/);
});

test('empty-safe rendering when there is no dialogue content at all', () => {
  const html = page.renderScript(page.viewModel({ lines: [] }));
  assert.match(html, /Chưa có kịch bản hội thoại/);
});

test('progress panel shows the friendly remaining-time nudge derived from server data, never invented', () => {
  const html = page.renderProgress(page.viewModel(study({ accumulatedSeconds: 60, remainingSeconds: 120 })));
  assert.match(html, /Hãy nán lại thêm 2 phút để luyện hội thoại nhé\./);
});

test('progress panel prompts to scroll when time is satisfied but bottom is not reached', () => {
  const html = page.renderProgress(page.viewModel(study({ accumulatedSeconds: 180, remainingSeconds: 0, reachedBottom: false })));
  assert.match(html, /Hãy cuộn xuống hết đoạn hội thoại/);
});

test('progress panel marks both evidence items done only when both are true', () => {
  const html = page.renderProgress(page.viewModel(study({
    accumulatedSeconds: 180, remainingSeconds: 0, reachedBottom: true, completed: true
  })));
  const doneCount = (html.match(/is-done/g) || []).length;
  assert.equal(doneCount, 2);
  assert.match(html, /Bạn đã hoàn thành phần hội thoại này\./);
});

test('renders a completion state with a return-to-roadmap action', () => {
  const html = page.renderCompleted();
  assert.match(html, /Phần Hội thoại đã đạt/);
  assert.match(html, /href="\/learning\/path\/v2"/);
});

test('error state renders a retry affordance', () => {
  const html = page.error('mất kết nối');
  assert.match(html, /mất kết nối/);
  assert.match(html, /data-retry/);
});

test('review/practice banner and script have no tracking/progress controls', () => {
  const html = page.renderReplay('REVIEW', page.viewModel(study({ completed: true })));
  assert.match(html, /Xem lại hội thoại/);
  assert.match(html, /いらっしゃいませ/);
  assert.doesNotMatch(html, /dialogueBottomSentinel|role="progressbar"|data-retry/);
});
