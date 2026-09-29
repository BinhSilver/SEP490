const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const pathModule = require('node:path');
const page = require('../../main/resources/static/js/pages/interactive-learning-path.js');

function path(overrides = {}) {
  return Object.assign({
    title: 'Ôn vững nền tảng', currentLevel: 'N5', targetLevel: 'N4', minutesPerDay: 30,
    completedPackages: 0, totalPackages: 1, progressPercent: 0,
    packages: [{ id: 9, phase: 'GOAL_LEARNING', dayNumber: 3, orderIndex: 1,
      title: 'Ghép âm và biến âm', status: 'AVAILABLE', progressPercent: 0,
      activities: [
        { id: 1, type: 'DIALOGUE', state: 'LOCKED', orderIndex: 4 },
        { id: 2, type: 'VOCABULARY', state: 'LOCKED', orderIndex: 1 },
        { id: 3, type: 'GRAMMAR', state: 'LOCKED', orderIndex: 3 },
        { id: 4, type: 'KANJI', state: 'LOCKED', orderIndex: 2 }
      ] }]
  }, overrides);
}

/** [JLAS-W0193] RC Task 5 — a warm-up day with the two new comprehension activities, kept separate from the
 * four-activity `path()` fixture above so its own "orders the four target activities" assertion stays exact. */
function pathWithComprehension() {
  return path({ packages: [{ id: 9, phase: 'WARM_UP', dayNumber: 1, orderIndex: 1, title: 'Khởi động Đọc/Nghe',
    status: 'AVAILABLE', progressPercent: 0,
    activities: [
      { id: 5, type: 'READING', state: 'LOCKED', orderIndex: 1 },
      { id: 6, type: 'LISTENING', state: 'LOCKED', orderIndex: 2 }
    ] }] });
}

test('resolves the Smart Test attempt from the redirected V2 URL before storage', () => {
  const storage = { getItem: () => '41' };
  assert.equal(page.resolveAssessmentAttemptId('?assessmentAttemptId=91', storage), 91);
});

test('builds the existing learning-path creation request for V2 bootstrap', () => {
  assert.deepEqual(page.pathCreationRequest(91), {
    url: '/api/learning/paths',
    body: JSON.stringify({ assessmentAttemptId: 91 })
  });
  assert.deepEqual(page.pathCreationRequest(null), {
    url: '/api/learning/paths/from-zero',
    body: '{}'
  });
});

test('orders the four target activities using the approved rail', () => {
  const model = page.viewModel(path());
  assert.deepEqual(model.packages[0].activityLabels, ['Từ vựng', 'Kanji', 'Ngữ pháp', 'Hội thoại']);
});

test('renders only supported roadmap data and no legacy aggregate quiz', () => {
  const html = page.render(path());
  assert.match(html, /MISSION DIRECTIVE/);
  assert.match(html, /Bắt đầu học/);
  assert.doesNotMatch(html, /Mini Quiz|quiz cuối bài|daily quiz|XP|Tải xuống|Luyện nghe/);
});

test('renders Vietnamese package states instead of backend enum names', () => {
  const html = page.render(path());
  assert.match(html, /Sẵn sàng/);
  assert.doesNotMatch(html, />AVAILABLE</);
});

test('filters packages by the selected learning phase', () => {
  const data = path({ packages: [
    Object.assign({}, path().packages[0], { id: 8, phase: 'WARM_UP', title: 'Khởi động' }),
    Object.assign({}, path().packages[0], { id: 9, phase: 'GOAL_LEARNING', orderIndex: 2, title: 'Mục tiêu' })
  ] });
  assert.match(page.render(data, 'WARM_UP'), /Khởi động/);
  assert.doesNotMatch(page.render(data, 'WARM_UP'), /<h3>Mục tiêu<\/h3>/);
  assert.match(page.render(data, 'GOAL_LEARNING'), /<h3>Mục tiêu<\/h3>/);
  assert.doesNotMatch(page.render(data, 'GOAL_LEARNING'), /<h3>Khởi động<\/h3>/);
});

test('turns coverage diagnostics into a concise learner-facing readiness state', () => {
  const html = page.errorState('BLOCKED_CONTENT',
    'Lộ trình V2 chưa được phát hành vì thiếu học liệu: [CoverageGap[tagCode=LESSON:26, reasonCode=KANJI_NOT_READY]]');
  assert.match(html, /Học liệu đang được hoàn thiện/);
  assert.match(html, /Quay lại Smart Test/);
  assert.match(html, /href="\/smart-test"/);
  assert.doesNotMatch(html, /CoverageGap|KANJI_NOT_READY|LESSON:26/);
});

test('keeps roadmap actions in normal layout flow so they cannot overlap the next day card', () => {
  const css = fs.readFileSync(pathModule.join(__dirname,
    '../../main/resources/static/css/pages/interactive-learning-path.css'), 'utf8');
  assert.match(css, /\.ilp-button\{[^}]*float:none[^}]*margin:[^;}]*auto/);
  assert.match(css, /\.ilp-state button\{[^}]*float:none/);
});

test('omits optional intensity when the API has no value', () => {
  const html = page.render(path({ minutesPerDay: null }));
  assert.doesNotMatch(html, /phút\/ngày/);
});

test('links an unlocked Kanji activity to its own study page (Task 5)', () => {
  const data = path();
  data.packages[0].activities[3].state = 'STUDYING';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/kanji\?activity=4"/);
});

test('does not link a locked Kanji activity', () => {
  const html = page.render(path());
  assert.doesNotMatch(html, /\/learning\/v2\/kanji\?activity=4/);
});

test('links an unlocked Grammar activity to its own study page (Task 6)', () => {
  const data = path();
  data.packages[0].activities[2].state = 'STUDYING';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/grammar\?activity=3"/);
});

test('does not link a locked Grammar activity', () => {
  const html = page.render(path());
  assert.doesNotMatch(html, /\/learning\/v2\/grammar\?activity=3/);
});

test('links an unlocked Dialogue activity to its own study page (Task 7)', () => {
  const data = path();
  data.packages[0].activities[0].state = 'STUDYING';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/dialogue\?activity=1"/);
});

test('does not link a locked Dialogue activity', () => {
  const html = page.render(path());
  assert.doesNotMatch(html, /\/learning\/v2\/dialogue\?activity=1/);
});

test('links an unlocked Reading activity to its own study page (RC Task 5)', () => {
  const data = pathWithComprehension();
  data.packages[0].activities[0].state = 'STUDYING';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/reading\?activity=5"/);
});

test('does not link a locked Reading activity', () => {
  const html = page.render(pathWithComprehension());
  assert.doesNotMatch(html, /\/learning\/v2\/reading\?activity=5/);
});

test('links an unlocked Listening activity to its own study page (RC Task 5)', () => {
  const data = pathWithComprehension();
  data.packages[0].activities[1].state = 'CHECK_READY';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/listening\?activity=6"/);
});

test('does not link a locked Listening activity', () => {
  const html = page.render(pathWithComprehension());
  assert.doesNotMatch(html, /\/learning\/v2\/listening\?activity=6/);
});

test('marks a passed Reading/Listening activity as done without a link', () => {
  const data = pathWithComprehension();
  data.packages[0].activities[0].state = 'PASSED';
  data.packages[0].activities[1].state = 'PASSED';
  const html = page.render(data);
  assert.doesNotMatch(html, /\/learning\/v2\/reading\?activity=5/);
  assert.doesNotMatch(html, /\/learning\/v2\/listening\?activity=6/);
  assert.match(html, /Đọc hiểu/);
  assert.match(html, /Nghe hiểu/);
});

test('marks a completed Dialogue activity as done without a link (Dialogue has no micro-check state)', () => {
  const data = path();
  data.packages[0].activities[0].state = 'COMPLETED';
  const html = page.render(data);
  assert.doesNotMatch(html, /\/learning\/v2\/dialogue\?activity=1/);
  assert.match(html, /is-done/);
});

// ---- Task 8: package completion popup ----

test('popupModel is null for a package that is not just-completed', () => {
  assert.equal(page.popupModel(undefined), null);
  assert.equal(page.popupModel({ status: 'COMPLETED', justCompleted: false }), null);
  assert.equal(page.popupModel({ status: 'AVAILABLE', justCompleted: false }), null);
});

test('popupModel carries score, next-package and last-package flags for a just-completed package', () => {
  const model = page.popupModel({ id: 9, title: 'Ngày 3', justCompleted: true, scorePercent: 82,
    lastPackage: false, nextAvailablePackageId: 10 });
  assert.deepEqual(model, { packageId: 9, title: 'Ngày 3', scorePercent: 82, isLast: false, nextPackageId: 10 });
});

test('renderPopup renders nothing when the package is not just-completed', () => {
  assert.equal(page.renderPopup(path().packages[0]), '');
});

test('renderPopup shows the exact completion copy and three ranked actions', () => {
  const html = page.renderPopup({ id: 9, title: 'Ngày 3', justCompleted: true, scorePercent: 90,
    lastPackage: false, nextAvailablePackageId: 10 });
  assert.match(html, /Bạn đã hoàn thành phần bài học hôm nay/);
  assert.match(html, /Bạn muốn tiếp tục như thế nào\?/);
  assert.match(html, /data-popup-advance="10"[^>]*>Học vượt</);
  assert.match(html, /data-popup-return>Trở về lộ trình</);
  assert.match(html, /data-popup-rest>Nghỉ ngơi</);
  assert.match(html, /Điểm phần này: <strong>90%<\/strong>/);
});

test('renderPopup omits the score line when the package has no valid score', () => {
  const html = page.renderPopup({ id: 9, justCompleted: true, scorePercent: null, lastPackage: false, nextAvailablePackageId: 10 });
  assert.doesNotMatch(html, /Điểm phần này/);
});

test('renderPopup disables Học vượt when no next package is unlocked yet', () => {
  const html = page.renderPopup({ id: 9, justCompleted: true, lastPackage: false, nextAvailablePackageId: null });
  assert.match(html, /class="ilp-popup__primary" disabled>Học vượt</);
});

test('renderPopup swaps the primary action for the last package in the path', () => {
  const html = page.renderPopup({ id: 9, justCompleted: true, lastPackage: true, nextAvailablePackageId: null });
  assert.match(html, /data-popup-summary>Xem tổng kết chặng</);
  assert.doesNotMatch(html, />Học vượt</);
});

test('render shows the popup only for the package the server just completed, not older completed ones', () => {
  const data = path({ packages: [
    { id: 8, phase: 'GOAL_LEARNING', dayNumber: 2, orderIndex: 1, title: 'Ngày 2', status: 'COMPLETED',
      progressPercent: 100, justCompleted: false, activities: [] },
    { id: 9, phase: 'GOAL_LEARNING', dayNumber: 3, orderIndex: 2, title: 'Ngày 3', status: 'COMPLETED',
      progressPercent: 100, justCompleted: true, lastPackage: true, activities: [] }
  ] });
  const html = page.render(data);
  const matches = html.match(/ilp-popup-backdrop/g) || [];
  assert.equal(matches.length, 1);
  assert.match(html, /data-popup="9"/);
});

test('render shows no popup when nothing just completed', () => {
  const html = page.render(path());
  assert.doesNotMatch(html, /ilp-popup-backdrop/);
});
