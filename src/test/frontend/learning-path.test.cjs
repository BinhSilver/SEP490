const test = require('node:test');
const assert = require('node:assert/strict');

const LearningPathPage = require('../../main/resources/static/js/pages/learning-path.js');

function pathFixture() {
  return {
    id: 1,
    goal: 'JLPT',
    currentLevel: 'N5',
    targetLevel: 'N4',
    minutesPerDay: 30,
    totalLessons: 4,
    totalMinutes: 55,
    totalDays: 3,
    completedLessons: 1,
    coverageGaps: [{ tagCode: 'KANJI', reasonCode: 'MISSING_WARM_UP_RESOURCE' }],
    phases: [
      {
        phase: 'GOAL_LEARNING',
        lessonCount: 2,
        totalMinutes: 30,
        dayCount: 2,
        items: [
          { lessonId: 103, title: 'Đọc hiểu N4', reasonText: 'Học theo mục tiêu', dayNumber: 3, orderIndex: 3, estimatedMinutes: 20, completed: false }
        ]
      },
      {
        phase: 'WARM_UP',
        lessonCount: 2,
        totalMinutes: 25,
        dayCount: 1,
        items: [
          { lessonId: 102, title: 'Thể て', reasonText: 'Ôn lại điểm yếu', dayNumber: 1, orderIndex: 2, estimatedMinutes: 15, completed: false },
          { lessonId: 101, title: 'Trợ từ は/が', reasonText: 'Ôn lại điểm yếu', dayNumber: 1, orderIndex: 1, estimatedMinutes: 10, completed: true }
        ]
      }
    ]
  };
}

test('continue learning opens the same warm-up flashcard activity as the roadmap', () => {
  const fixture = pathFixture();
  fixture.phases[1].items = [{ lessonId: 101, vocabularyEntryId: 42,
    title: 'Ôn tập Kanji & Từ vựng', reasonCode: 'WARM_UP_FLASHCARDS',
    dayNumber: 1, orderIndex: 1, estimatedMinutes: 10, completed: false }];
  const html = LearningPathPage.renderPath(LearningPathPage.createViewModel(fixture));
  assert.match(html, /href="\/learning\/warm-up\/flashcard">Tiếp tục học/);
});

test('orders phases and lessons according to the persisted schedule', () => {
  const view = LearningPathPage.createViewModel(pathFixture());

  assert.deepEqual(view.phases.map((phase) => phase.code), ['WARM_UP', 'GOAL_LEARNING']);
  assert.deepEqual(view.days.map((day) => day.dayNumber), [1, 3]);
  assert.deepEqual(view.days[0].items.map((item) => item.lessonId), [101, 102]);
});

test('derives completed, active, and locked days without inventing progress', () => {
  const fixture = pathFixture();
  fixture.phases[1].items[0].completed = true;
  fixture.phases[0].items.push({
    lessonId: 104,
    title: 'Nghe hội thoại',
    reasonText: 'Học theo mục tiêu',
    dayNumber: 2,
    orderIndex: 2,
    estimatedMinutes: 10,
    completed: false
  });
  fixture.completedLessons = 2;

  const view = LearningPathPage.createViewModel(fixture);

  assert.deepEqual(view.days.map((day) => day.status), ['COMPLETED', 'ACTIVE', 'LOCKED']);
  assert.equal(view.progressPercent, 50);
  assert.equal(view.remainingLessons, 2);
});

test('keeps progress safe when the API returns an empty path', () => {
  const fixture = pathFixture();
  fixture.totalLessons = 0;
  fixture.completedLessons = 0;
  fixture.phases = [];

  const view = LearningPathPage.createViewModel(fixture);

  assert.equal(view.progressPercent, 0);
  assert.deepEqual(view.days, []);
});

test('reads assessment attempt only from an explicit integration source', () => {
  const storage = { getItem: (key) => key === 'assessmentAttemptId' ? '92' : null };

  assert.equal(LearningPathPage.resolveAssessmentAttemptId('?assessmentAttemptId=91', storage), 91);
  assert.equal(LearningPathPage.resolveAssessmentAttemptId('', storage), 92);
  assert.equal(LearningPathPage.resolveAssessmentAttemptId('?assessmentAttemptId=invalid', { getItem: () => null }), null);
});

test('renders the Gemini aiPlan narrative without blocking the page when absent', () => {
  const withoutPlan = LearningPathPage.renderPath(LearningPathPage.createViewModel(pathFixture()));
  assert.doesNotMatch(withoutPlan, /lp-ai-plan/);

  const fixture = pathFixture();
  fixture.aiPlan = {
    overview: 'Bạn yếu trợ từ, cần ôm gọn nền tảng N5 trước khi lên N4.',
    phases: [
      { phase: 'WARM_UP', strategy: 'Ôn lại toàn bộ trợ từ N5.', focus: 'Trợ từ は/が/を.', tips: 'Làm lại bài đã sai.' },
      { phase: 'GOAL_LEARNING', strategy: 'Luyện đọc hiểu N4.', focus: 'Đọc theo chủ đề.', tips: 'Nghe kèm để nhớ lâu.' }
    ]
  };
  const html = LearningPathPage.renderPath(LearningPathPage.createViewModel(fixture));

  assert.match(html, /lp-ai-plan/);
  assert.match(html, /Bạn yếu trợ từ/);
  assert.match(html, /Giai đoạn khởi động/);
  assert.match(html, /Giai đoạn chinh phục mục tiêu/);
  assert.match(html, /Ôn lại toàn bộ trợ từ N5\./);
  assert.match(html, /Trọng tâm/);
  assert.match(html, /Mẹo/);
});

test('maps API errors to actionable Vietnamese messages', () => {
  assert.match(LearningPathPage.errorMessage('CONFLICT'), /Smart Test/i);
  assert.match(LearningPathPage.errorMessage('SERVICE_UNAVAILABLE'), /tạm thời/i);
  assert.match(LearningPathPage.errorMessage('FORBIDDEN'), /quyền/i);
});
