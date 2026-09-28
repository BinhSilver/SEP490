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
