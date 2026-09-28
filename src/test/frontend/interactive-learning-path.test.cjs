const test = require('node:test');
const assert = require('node:assert/strict');
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
