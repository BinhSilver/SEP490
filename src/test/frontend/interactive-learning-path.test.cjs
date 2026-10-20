const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const pathModule = require('node:path');
const page = require('../../main/resources/static/js/pages/interactive-learning-path.js');

// Owner decision 2026-10-06: the roadmap unit is the chapter (source lesson) with ordered parts, not the
// calendar day; a learner may finish several parts in one sitting, so no "DAY n" / "Ngày n" labels.
test('parts of one lesson render inside one chapter card with numbered parts and no day labels', () => {
  const part = (id, index, status) => ({ id, phase: 'GOAL_LEARNING', dayNumber: 12 + index, orderIndex: index,
    title: 'Bài 1: Giới thiệu bản thân · Phần ' + index + '/2', status, progressPercent: 0,
    chapterLessonId: 501, chapterTitle: 'Bài 1: Giới thiệu bản thân', chapterNumber: 3, segmentIndex: index, segmentCount: 2,
    lockReason: status === 'LOCKED' ? 'Hoàn thành phần trước để mở khóa.' : null,
    activities: [{ id: id * 10, type: 'VOCABULARY', state: status === 'LOCKED' ? 'LOCKED' : 'STUDYING', orderIndex: 1, dayNumber: 12 + index }] });
  const html = page.render(path({ packages: [part(1, 1, 'IN_PROGRESS'), part(2, 2, 'LOCKED')] }));
  assert.equal((html.match(/class="ilp-chapter"/g) || []).length, 1);
  assert.match(html, /CHƯƠNG 3 · MỤC TIÊU/);
  // UAT 2026-10-06: the source textbook's lesson number ("Bài 17") leaks catalog order — the first target chapter
  // read "Bài 17". Only the lesson title is shown; the chapter kicker carries the learner's own numbering.
  assert.match(html, /<h3>Giới thiệu bản thân<\/h3>/);
  assert.doesNotMatch(html, /Bài 1:/);
  assert.match(html, /Phần 1\/2/);
  assert.match(html, /Phần 2\/2/);
  assert.match(html, /Hoàn thành phần trước để mở khóa/);
  assert.doesNotMatch(html, /DAY \d/);
  assert.doesNotMatch(html, /Ngày \d/);
});

test('chapter progress counts finished learning activities, not hidden daily package segments', () => {
  const packages = [
    { id: 101, phase: 'GOAL_LEARNING', orderIndex: 1, status: 'COMPLETED', progressPercent: 100,
      chapterLessonId: 811, chapterTitle: 'Giới thiệu bản thân', chapterNumber: 11,
      activities: [
        { id: 1, type: 'VOCABULARY', state: 'PASSED' },
        { id: 2, type: 'KANJI', state: 'PASSED' }
      ] },
    { id: 102, phase: 'GOAL_LEARNING', orderIndex: 2, status: 'COMPLETED', progressPercent: 100,
      chapterLessonId: 811, chapterTitle: 'Giới thiệu bản thân', chapterNumber: 11,
      activities: [
        { id: 3, type: 'VOCABULARY', state: 'PASSED' },
        { id: 4, type: 'KANJI', state: 'PASSED' }
      ] },
    { id: 103, phase: 'GOAL_LEARNING', orderIndex: 3, status: 'COMPLETED', progressPercent: 100,
      chapterLessonId: 811, chapterTitle: 'Giới thiệu bản thân', chapterNumber: 11,
      activities: [
        { id: 5, type: 'VOCABULARY', state: 'PASSED' },
        { id: 6, type: 'KANJI', state: 'PASSED' }
      ] },
    { id: 104, phase: 'GOAL_LEARNING', orderIndex: 4, status: 'COMPLETED', progressPercent: 100,
      chapterLessonId: 811, chapterTitle: 'Giới thiệu bản thân', chapterNumber: 11,
      activities: [
        { id: 7, type: 'VOCABULARY', state: 'PASSED' },
        { id: 8, type: 'KANJI', state: 'PASSED' }
      ] },
    { id: 105, phase: 'GOAL_LEARNING', orderIndex: 5, status: 'COMPLETED', progressPercent: 100,
      chapterLessonId: 811, chapterTitle: 'Giới thiệu bản thân', chapterNumber: 11,
      activities: [
        { id: 9, type: 'KANJI', state: 'PASSED' },
        { id: 10, type: 'GRAMMAR', state: 'STUDYING' }
      ] },
    { id: 106, phase: 'GOAL_LEARNING', orderIndex: 6, status: 'IN_PROGRESS', progressPercent: 0,
      chapterLessonId: 811, chapterTitle: 'Giới thiệu bản thân', chapterNumber: 11,
      activities: [{ id: 11, type: 'DIALOGUE', state: 'LOCKED' }] }
  ];
  const html = page.render(path({ packages }));
  assert.match(html, /9\/11 hoạt động đã hoàn thành/);
  assert.match(html, /aria-valuenow="82"/);
  assert.doesNotMatch(html, /5\/6 phần đã xong/);
});

test('a package without a source lesson is its own chapter', () => {
  const html = page.render(path({ packages: [{ id: 7, phase: 'WARM_UP', dayNumber: 1, orderIndex: 1, title: 'Khởi động Từ vựng',
    status: 'AVAILABLE', chapterLessonId: null, chapterTitle: 'Khởi động Từ vựng', chapterNumber: 1, segmentIndex: 1, segmentCount: 1,
    activities: [{ id: 70, type: 'VOCABULARY', state: 'LOCKED', orderIndex: 1 }] }] }));
  assert.match(html, /CHƯƠNG 1 · KHỞI ĐỘNG/);
  assert.match(html, /class="ilp-chapter"/);
  assert.doesNotMatch(html, /DAY \d/);
});

test('a single-part chapter shows the lesson title without the textbook lesson number', () => {
  const html = page.render(path({ packages: [{ id: 8, phase: 'GOAL_LEARNING', dayNumber: 6, orderIndex: 6,
    title: 'Bài 17: Đừng làm, phải làm · Phần 1/1', status: 'AVAILABLE', chapterLessonId: 17,
    chapterTitle: 'Bài 17: Đừng làm, phải làm', chapterNumber: 6, segmentIndex: 1, segmentCount: 1,
    activities: [{ id: 80, type: 'VOCABULARY', state: 'LOCKED', orderIndex: 1 }] }] }));
  assert.match(html, /<h3>Đừng làm, phải làm<\/h3>/);
  assert.doesNotMatch(html, /Bài 17/);
});

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

test('communication path renders independent situations without JLPT phases or quiz copy', () => {
  const html = page.render(path({
    goal: 'TRAVEL',
    title: 'Tiếng Nhật du lịch',
    packages: [{
      id: 31, phase: 'GOAL_LEARNING', orderIndex: 1, status: 'AVAILABLE',
      chapterLessonId: 901, chapterNumber: 1, chapterTitle: 'Tại sân bay',
      activities: [
        { id: 311, type: 'VOCABULARY', state: 'LOCKED', orderIndex: 1 },
        { id: 312, type: 'GRAMMAR', state: 'LOCKED', orderIndex: 2 },
        { id: 313, type: 'DIALOGUE', state: 'LOCKED', orderIndex: 3 }
      ]
    }]
  }), 'WARM_UP', { mockResult: { passed: true, level: 'N5' } });

  assert.match(html, /TÌNH HUỐNG 1/);
  assert.match(html, /Bạn có thể học Từ vựng, Ngữ pháp hoặc Hội thoại theo thứ tự phù hợp/);
  assert.doesNotMatch(html, /CHƯƠNG 1|Khởi động|Mục tiêu|làm bài kiểm tra|thi thử N5/);
  assert.match(html, /từ và cụm từ dùng ngay trong tình huống này/i);
});

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

test('active warm-up activity exposes an explicit continue action', () => {
  const data = pathWithComprehension();
  data.packages[0].status = 'IN_PROGRESS';
  data.packages[0].activities[0].state = 'STUDYING';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/reading\?activity=5"[^>]*>[\s\S]*?Tiếp tục học/);
});

test('goal sidebar does not expose server implementation details', () => {
  const html = page.render(path());
  assert.doesNotMatch(html, /lưu trạng thái trên máy chủ/);
});

test('resolves the Smart Test attempt from the redirected V2 URL before storage', () => {
  const storage = { getItem: () => '41' };
  assert.equal(page.resolveAssessmentAttemptId('?assessmentAttemptId=91', storage), 91);
});

test('builds the existing learning-path creation request for V2 bootstrap', () => {
  assert.deepEqual(page.pathCreationRequest(91), {
    url: '/api/learning/paths',
    body: JSON.stringify({ assessmentAttemptId: 91, reviewStartLevel: null })
  });
  assert.deepEqual(page.pathCreationRequest(null), {
    url: '/api/learning/paths/from-zero',
    body: '{}'
  });
});

test('sends a validated explicitly selected review start level with the assessment attempt', () => {
  assert.equal(page.resolveReviewStartLevel('?reviewStartLevel=N5'), 'N5');
  assert.equal(page.resolveReviewStartLevel('?reviewStartLevel=N1'), null);
  assert.deepEqual(page.pathCreationRequest(3, 'N5'), {
    url: '/api/learning/paths',
    body: JSON.stringify({ assessmentAttemptId: 3, reviewStartLevel: 'N5' })
  });
});

test('sends the learner answer to a lower Smart Test result only when it is a known choice', () => {
  assert.equal(page.resolveDiscrepancyChoice('?discrepancyChoice=ACCEPT_RECOMMENDATION'), 'ACCEPT_RECOMMENDATION');
  assert.equal(page.resolveDiscrepancyChoice('?discrepancyChoice=DECLINE_RECOMMENDATION'), 'DECLINE_RECOMMENDATION');
  assert.equal(page.resolveDiscrepancyChoice('?discrepancyChoice=SKIP_ALL'), null);
  assert.deepEqual(page.pathCreationRequest(3, null, 'ACCEPT_RECOMMENDATION'), {
    url: '/api/learning/paths',
    body: JSON.stringify({ assessmentAttemptId: 3, reviewStartLevel: null, discrepancyChoice: 'ACCEPT_RECOMMENDATION' })
  });
});

test('orders the four target activities using the approved rail', () => {
  const model = page.viewModel(path());
  assert.deepEqual(model.packages[0].activityLabels, ['Từ vựng', 'Kanji', 'Ngữ pháp', 'Hội thoại']);
});

test('renders only supported roadmap data and no legacy aggregate quiz', () => {
  const html = page.render(path());
  assert.match(html, /Lộ trình của bạn/);
  assert.doesNotMatch(html, /MISSION DIRECTIVE/);
  assert.match(html, /Bắt đầu phần học/);
  assert.doesNotMatch(html, /Mini Quiz|quiz cuối bài|daily quiz|XP|Tải xuống/);
});

test('shows small Smart Test remediation sets as a read-only list with no quiz or progress actions', () => {
  const html = page.render(path({ smartTestReviewOnlyTargets: [
    { skill: 'VOCABULARY', target: '学生' }, { skill: 'KANJI', target: '食' }
  ] }));
  assert.match(html, /Nội dung nên xem lại/);
  assert.match(html, /学生/);
  assert.match(html, /食/);
  assert.doesNotMatch(html, /Bắt đầu kiểm tra|Ôn lại bằng Flashcard|smart-test-review-only/);
});

// Owner clarification 2026-10-11: detailed Smart Test questions and answers belong to test results,
// not the roadmap's compact list of content to revisit.
test('keeps Smart Test grammar prompts, answers and explanations out of the roadmap', () => {
  const html = page.render(path({ smartTestReviewOnlyTargets: [
    { skill: 'GRAMMAR', target: 'を', sentence: '駅で切符＿＿＿＿買います。', answer: 'を', explanation: 'Trợ từ chỉ đối tượng.' }
  ] }));
  assert.doesNotMatch(html, /class="ilp-review-only"/);
  assert.doesNotMatch(html, /駅で切符|Đáp án:|Trợ từ chỉ đối tượng/);
});

test('roadmap review list remains limited to compact vocabulary and kanji targets', () => {
  const html = page.render(path({ smartTestReviewOnlyTargets: [
    { skill: 'VOCABULARY', target: '学生' }, { skill: 'KANJI', target: '食' },
    { skill: 'GRAMMAR', target: 'を', sentence: 'sentence', answer: 'answer', explanation: 'detail' }
  ] }));
  const panel = (html.match(/<aside class="ilp-review-only"[\s\S]*?<\/aside>/) || [''])[0];
  assert.match(panel, /Nội dung nên xem lại/);
  assert.match(panel, /学生/);
  assert.match(panel, />食</);
  assert.doesNotMatch(panel, /Ngữ pháp|sentence|answer|detail/);
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

test('roadmap uses flat completed chapters and reserves a warm accent for the active lesson', () => {
  const css = fs.readFileSync(pathModule.join(__dirname,
    '../../main/resources/static/css/pages/interactive-learning-path.css'), 'utf8');
  assert.match(css, /\.ilp-page \.ilp-hero\s*\{[^}]*border:\s*0/);
  assert.match(css, /\.ilp-page \.ilp-chapter\s*\{[^}]*border:\s*0/);
  assert.match(css, /\.ilp-page \.ilp-day--completed,\s*\.ilp-page \.ilp-day--locked\s*\{[^}]*border-bottom/);
  assert.match(css, /\.ilp-page \.ilp-day--in_progress,\s*\.ilp-page \.ilp-day--available\s*\{[^}]*border-left:[^;}]*var\(--color-tertiary-container\)/);
  assert.match(css, /\.ilp-page \.ilp-total strong\s*\{[^}]*color:\s*var\(--color-on-tertiary-container\)/);
});

test('roadmap controls visibly respond to hover and press without styling disabled controls as active', () => {
  const css = fs.readFileSync(pathModule.join(__dirname,
    '../../main/resources/static/css/pages/interactive-learning-path.css'), 'utf8');
  assert.match(css, /\.ilp-page :is\([^}]*\):hover:not\(:disabled\)\s*\{/);
  assert.match(css, /\.ilp-page :is\([^}]*\):active:not\(:disabled\)\s*\{/);
  assert.match(css, /\.ilp-page :is\([^}]*\):active:not\(:disabled\)\s*\{[^}]*background-color:[^;}]*color-mix/);
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

test('routes to the first server-unlocked activity after a package starts', () => {
  assert.equal(page.startedPackageStudyHref({ activities: [
    { id: 4, type: 'VOCABULARY', state: 'LOCKED', orderIndex: 1 },
    { id: 8, type: 'KANJI', state: 'STUDYING', orderIndex: 2 }
  ] }), '/learning/v2/kanji?activity=8');
  assert.equal(page.startedPackageStudyHref({ activities: [
    { id: 4, type: 'VOCABULARY', state: 'LOCKED', orderIndex: 1 }
  ] }), null);
  assert.equal(page.startedPackageStudyHref({ activities: [
    { id: 4, type: 'UNKNOWN', state: 'STUDYING', orderIndex: 1 }
  ] }), null);
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
  assert.doesNotMatch(html, /\shref="\/learning\/v2\/reading\?activity=5"/);
});

test('links an unlocked Listening activity to its own study page (RC Task 5)', () => {
  const data = pathWithComprehension();
  data.packages[0].activities[1].state = 'CHECK_READY';
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/listening\?activity=6"/);
});

test('does not link a locked Listening activity', () => {
  const html = page.render(pathWithComprehension());
  assert.doesNotMatch(html, /\shref="\/learning\/v2\/listening\?activity=6"/);
});

test('marks a passed Reading/Listening activity as done without a link', () => {
  const data = pathWithComprehension();
  data.packages[0].activities[0].state = 'PASSED';
  data.packages[0].activities[1].state = 'PASSED';
  const html = page.render(data);
  assert.doesNotMatch(html, /href="\/learning\/v2\/reading\?activity=5"/);
  assert.doesNotMatch(html, /href="\/learning\/v2\/listening\?activity=6"/);
  assert.doesNotMatch(html, /data-start-href="\/learning\/v2\/(reading|listening)\?activity=[56]"/);
  assert.match(html, /Đọc hiểu/);
  assert.match(html, /Nghe hiểu/);
});

test('completed Grammar and Dialogue activities expose only their supported replay actions', () => {
  const data = path();
  data.packages[0].activities[2].state = 'PASSED';
  data.packages[0].activities[0].state = 'COMPLETED';
  const html = page.render(data);
  assert.match(html, /\/learning\/v2\/grammar\?activity=3&amp;mode=review/);
  assert.match(html, /\/learning\/v2\/grammar\?activity=3&amp;mode=practice/);
  assert.match(html, /\/learning\/v2\/dialogue\?activity=1&amp;mode=review/);
  assert.match(html, /\/learning\/v2\/dialogue\?activity=1&amp;mode=practice/);
  assert.doesNotMatch(html, /\/learning\/v2\/dialogue\?activity=1"/);
});

test('renders server-owned completed sessions and only server-allowed replay actions', () => {
  const data = path({ packages: [{ id: 9, phase: 'GOAL_LEARNING', dayNumber: 3, orderIndex: 1,
    title: 'Bài 3', status: 'COMPLETED', progressPercent: 100, activities: [], sessions: [
      { sessionType: 'VOCABULARY', state: 'COMPLETED', allowedActions: ['REVIEW', 'PRACTICE'],
        activities: [{ id: 44, type: 'VOCABULARY', state: 'PASSED' }], contentCounts: { vocabulary: 8 },
        contentCount: 8, estimatedMinutes: 10, referenceVocabulary: [] },
      { sessionType: 'KANJI_GRAMMAR', state: 'COMPLETED', allowedActions: [],
        activities: [{ id: 45, type: 'GRAMMAR', state: 'PASSED' }], contentCounts: { grammar: 2 },
        contentCount: 2, estimatedMinutes: 10, referenceVocabulary: [
          { canonicalId: 900, expression: '<学校>', reading: 'がっこう', meaning: 'trường học', wordType: 'NOUN' }
        ] },
      { sessionType: 'DIALOGUE', state: 'COMPLETED', allowedActions: ['REVIEW', 'PRACTICE'],
        activities: [{ id: 46, type: 'DIALOGUE', state: 'COMPLETED' }], contentCounts: { dialogue: 4 },
        contentCount: 4, estimatedMinutes: 10, referenceVocabulary: [] }
    ] }] });
  const html = page.render(data);
  assert.match(html, /Từ vựng/);
  assert.match(html, /8 từ/);
  assert.match(html, /\/learning\/v2\/vocabulary\?activity=44&amp;mode=review/);
  assert.match(html, /&lt;学校&gt;/);
  assert.match(html, /Hội thoại/);
  assert.match(html, /\/learning\/v2\/dialogue\?activity=46&amp;mode=review/);
  assert.doesNotMatch(html, /\/learning\/v2\/grammar\?activity=45/);
});

test('session continuation links only unlocked children and keeps completed child replay available', () => {
  const data = path({ packages: [{ id: 9, phase: 'GOAL_LEARNING', dayNumber: 1, orderIndex: 1,
    title: 'Bài 1', status: 'IN_PROGRESS', activities: [], sessions: [
      { sessionType: 'KANJI_GRAMMAR', state: 'IN_PROGRESS', allowedActions: ['CONTINUE'], activities: [
        { id: 41, type: 'KANJI', state: 'PASSED' }, { id: 42, type: 'GRAMMAR', state: 'STUDYING' }] },
      { sessionType: 'DIALOGUE', state: 'LOCKED', allowedActions: [], activities: [
        { id: 43, type: 'DIALOGUE', state: 'LOCKED' }] }
    ] }] });
  const html = page.render(data);
  assert.match(html, /href="\/learning\/v2\/grammar\?activity=42"/);
  assert.match(html, /href="\/learning\/v2\/kanji\?activity=41&amp;mode=review"/);
  assert.doesNotMatch(html, /href="\/learning\/v2\/dialogue\?activity=43/);
});

test('completed legacy and warm-up packages retain replay actions without session projection', () => {
  const html = page.render(path({ packages: [{ id: 9, phase: 'WARM_UP', dayNumber: 1, orderIndex: 1,
    title: 'Ôn đọc', status: 'COMPLETED', activities: [{ id: 7, type: 'READING', state: 'PASSED' }] }] }));
  assert.match(html, /href="\/learning\/v2\/reading\?activity=7&amp;mode=review"/);
  assert.match(html, /href="\/learning\/v2\/reading\?activity=7&amp;mode=practice"/);
});

for (const count of [0, 1, 4]) {
  test(`reference-only vocabulary renders ${count} words without a flashcard activity`, () => {
    const words = Array.from({ length: count }, (_, i) => ({ expression: '語' + i, meaning: 'từ ' + i }));
    const html = page.render(path({ packages: [{ id: 1, phase: 'GOAL_LEARNING', dayNumber: 1, orderIndex: 1,
      title: 'Ngữ pháp', status: 'IN_PROGRESS', activities: [], sessions: [{ sessionType: 'KANJI_GRAMMAR',
        state: 'IN_PROGRESS', allowedActions: ['CONTINUE'], activities: [{ id: 2, type: 'GRAMMAR', state: 'STUDYING' }],
        referenceVocabulary: words }] }] }));
    assert.equal(html.includes('Từ tham khảo ngắn'), count > 0);
    for (const word of words) assert.ok(html.includes(word.expression));
    assert.doesNotMatch(html, /\/learning\/v2\/vocabulary/);
  });
}

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
  assert.match(html, /data-popup-advance="10"[^>]*>Tiếp tục</);
  assert.match(html, /data-popup-return>Trở về lộ trình</);
  assert.match(html, /Điểm phần này: <strong>90%<\/strong>/);
});

test('renderPopup omits the score line when the package has no valid score', () => {
  const html = page.renderPopup({ id: 9, justCompleted: true, scorePercent: null, lastPackage: false, nextAvailablePackageId: 10 });
  assert.doesNotMatch(html, /Điểm phần này/);
});

test('renderPopup disables Tiếp tục when no next package is unlocked yet', () => {
  const html = page.renderPopup({ id: 9, justCompleted: true, lastPackage: false, nextAvailablePackageId: null });
  assert.match(html, /class="ilp-popup__primary" disabled>Tiếp tục</);
});

test('renderPopup swaps the primary action for the last package in the path', () => {
  const html = page.renderPopup({ id: 9, justCompleted: true, lastPackage: true, nextAvailablePackageId: null });
  assert.match(html, /data-popup-summary>Xem tổng kết chặng</);
  assert.doesNotMatch(html, />Tiếp tục</);
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

/** [JLAS-W0108] A from-zero learner's alphabet day: one WARM_UP package with a single FOUNDATION activity. */
function pathWithFoundation(state) {
  return path({ packages: [{ id: 9, phase: 'WARM_UP', dayNumber: 1, orderIndex: 1, title: 'Bài 1: Hàng あ, か, さ',
    status: 'AVAILABLE', progressPercent: 0,
    activities: [{ id: 7, type: 'FOUNDATION', state: state, orderIndex: 1 }] }] });
}

test('labels a FOUNDATION activity in Vietnamese instead of the raw enum name', () => {
  const html = page.render(pathWithFoundation('LOCKED'));
  assert.match(html, /Bảng chữ cái/);
  assert.doesNotMatch(html, />FOUNDATION</);
});

test('links an unlocked FOUNDATION activity to the alphabet study page', () => {
  const html = page.render(pathWithFoundation('STUDYING'));
  assert.match(html, /href="\/learning\/v2\/foundation\?activity=7"/);
});

test('does not link a locked FOUNDATION activity', () => {
  const html = page.render(pathWithFoundation('LOCKED'));
  assert.doesNotMatch(html, /\/learning\/v2\/foundation\?activity=7/);
});

// ---- [JLAS-W0125] split parts and approved roadmap action names ----

test('activities split by a pace change show their part position, single parts stay unlabelled', () => {
  const data = path();
  data.packages[0].status = 'IN_PROGRESS';
  data.packages[0].activities = [
    { id: 2, type: 'VOCABULARY', state: 'PASSED', orderIndex: 1, partIndex: 1, partCount: 2 },
    { id: 7, type: 'VOCABULARY', state: 'STUDYING', orderIndex: 2, partIndex: 2, partCount: 2 },
    { id: 4, type: 'KANJI', state: 'LOCKED', orderIndex: 3, partIndex: 1, partCount: 1 }
  ];
  const html = page.render(data);
  assert.match(html, /Từ vựng · Phần 1\/2/);
  assert.match(html, /Từ vựng · Phần 2\/2[\s\S]*?href="\/learning\/v2\/vocabulary\?activity=7"/);
  assert.doesNotMatch(html, /Kanji · Phần/);
});

test('completed quiz activities offer "Xem lại bài giảng" and "Kiểm tra lại"; Dialogue has no quiz to retake', () => {
  const data = path();
  data.packages[0].activities[1].state = 'PASSED';
  data.packages[0].activities[0].state = 'COMPLETED';
  const html = page.render(data);
  assert.match(html, /vocabulary\?activity=2&amp;mode=review">Xem lại bài giảng</);
  assert.match(html, /vocabulary\?activity=2&amp;mode=practice">Kiểm tra lại</);
  assert.match(html, /dialogue\?activity=1&amp;mode=review">Xem lại bài giảng</);
  assert.match(html, /dialogue\?activity=1&amp;mode=practice">Ôn lại</);
  assert.match(html, /vocabulary\?activity=2&amp;mode=results">Kết quả kiểm tra</);
  assert.doesNotMatch(html, /dialogue\?activity=1&amp;mode=results/);
});

// ---- §2.24: communication-goal roadmap — done content links are REVIEW-only, never scored retakes ----

test('done Vocabulary/Grammar in a communication goal offer only the read-only review, never practice or results', () => {
  const data = path({ goal: 'TRAVEL' });
  data.packages[0].activities = [
    { id: 2, type: 'VOCABULARY', state: 'PASSED', orderIndex: 1 },
    { id: 3, type: 'GRAMMAR', state: 'PASSED', orderIndex: 2 },
    { id: 1, type: 'DIALOGUE', state: 'COMPLETED', orderIndex: 3 }
  ];
  const html = page.render(data);
  assert.match(html, /vocabulary\?activity=2&amp;mode=review">Xem lại bài giảng</);
  assert.doesNotMatch(html, /vocabulary\?activity=2&amp;mode=(practice|results)/);
  assert.match(html, /grammar\?activity=3&amp;mode=review">Xem lại bài giảng</);
  assert.doesNotMatch(html, /grammar\?activity=3&amp;mode=(practice|results)/);
  // §2.24 covers only the no-quiz content types; Dialogue rehearsal keeps its own actions.
  assert.match(html, /dialogue\?activity=1&amp;mode=review/);
});

test('a non-communication path keeps scored retake links for the same done activities', () => {
  const data = path();
  data.packages[0].activities = [{ id: 2, type: 'VOCABULARY', state: 'PASSED', orderIndex: 1 }];
  const html = page.render(data);
  assert.match(html, /vocabulary\?activity=2&amp;mode=practice">Kiểm tra lại</);
  assert.match(html, /vocabulary\?activity=2&amp;mode=results">Kết quả kiểm tra</);
});

// ---- Owner 2026-10-19: communication roadmaps group units by topic (display only, never locked) ----

function commPackage(id, lessonId, title, types) {
  return { id, phase: 'GOAL_LEARNING', orderIndex: id, status: 'AVAILABLE', progressPercent: 0,
    chapterLessonId: lessonId, chapterTitle: title, chapterNumber: id, segmentIndex: 1, segmentCount: 1,
    activities: types.map((type, index) => ({ id: id * 10 + index, type, state: 'LOCKED', orderIndex: index + 1 })) };
}

test('communication roadmap groups units by topic, survey focus first, with level chips and a soft warning', () => {
  const data = path({ goal: 'TRAVEL', packages: [
    commPackage(1, 11, 'J-LAS · Từ vựng · Sân bay & xuất nhập cảnh', ['VOCABULARY']),
    commPackage(2, 12, 'J-LAS · Làm thủ tục ở sân bay', ['GRAMMAR', 'DIALOGUE']),
    commPackage(3, 21, 'J-LAS · Từ vựng · Hỏi đường & sự cố', ['VOCABULARY']),
    commPackage(4, 22, 'J-LAS · Để quên đồ', ['GRAMMAR', 'DIALOGUE'])],
    communicationGuide: { learnerSelfLevel: 'N5', topics: [
      { code: 'TROUBLE', titleVi: 'Hỏi đường & sự cố', order: 11, priority: true, units: [
        { lessonId: 21, vocabularyTopic: true, jlptLevel: null }, { lessonId: 22, vocabularyTopic: false, jlptLevel: 'N3' }] },
      { code: 'AIR', titleVi: 'Sân bay & xuất nhập cảnh', order: 1, priority: false, units: [
        { lessonId: 11, vocabularyTopic: true, jlptLevel: null }, { lessonId: 12, vocabularyTopic: false, jlptLevel: 'N5' }] }] } });
  const html = page.render(data);
  assert.equal((html.match(/class="ilp-topic"/g) || []).length, 2);
  assert.ok(html.indexOf('Hỏi đường &amp; sự cố') < html.indexOf('Sân bay &amp; xuất nhập cảnh'), 'focus topic first');
  assert.match(html, /Ưu tiên của bạn/);
  assert.match(html, /TỪ VỰNG CHỦ ĐỀ/);
  assert.match(html, /TÌNH HUỐNG · N3/);
  assert.match(html, /Khó hơn trình độ hiện tại/, 'N3 situation above a self-reported N5 learner is flagged');
  assert.equal((html.match(/Khó hơn trình độ hiện tại/g) || []).length, 1, 'the N5 situation is not flagged');
  assert.doesNotMatch(html, /ilp-skill-part__lock/, 'grouping never locks a unit');
});

test('a BrSE roadmap offers optional J-LAS book links, shows a book that is not open yet without a link, and never gates units', () => {
  const data = path({ goal: 'BA', packages: [commPackage(1, 11, 'J-LAS · Từ vựng · Giao tiếp', ['VOCABULARY'])],
    communicationGuide: { learnerSelfLevel: 'N5', topics: [{ code: 'COM', titleVi: 'Giao tiếp công việc', order: 1, priority: false,
      units: [{ lessonId: 11, vocabularyTopic: true, jlptLevel: null }] }],
      bookSuggestions: [{ level: 'N4', title: 'Learning N4 — Sekai N4', textbookId: 4000002, available: true },
        { level: 'N3', title: 'Tiếng Nhật <N3>', textbookId: 4, available: false }] } });
  const html = page.render(data);
  assert.match(html, /class="ilp-books"/);
  assert.match(html, /Sách tham khảo \(tự nguyện\)/);
  assert.match(html, /href="\/textbooks\/4000002"[^>]*>[^<]*Learning N4 — Sekai N4/);
  assert.doesNotMatch(html, /href="\/textbooks\/4"/, 'a book learners cannot open has no link');
  assert.match(html, /Tiếng Nhật &lt;N3&gt;[\s\S]*đang được biên soạn/);
  assert.match(html, /không bắt buộc/i);
  assert.doesNotMatch(html, /ilp-skill-part__lock/);
  const travel = page.render(path({ goal: 'TRAVEL', packages: [commPackage(1, 11, 'J-LAS · Từ vựng · Sân bay', ['VOCABULARY'])],
    communicationGuide: { learnerSelfLevel: 'N5', topics: [], bookSuggestions: [] } }));
  assert.doesNotMatch(travel, /ilp-books/);
});

// OD-45 (owner 2026-10-20): the BrSE N3 link opens the J-LAS N3 textbook while it is still DRAFT, labelled unreviewed.
test('a BrSE book that is open but unreviewed links to the textbook and says it is not reviewed yet', () => {
  const data = path({ goal: 'BA', packages: [commPackage(1, 11, 'J-LAS · Từ vựng · Giao tiếp', ['VOCABULARY'])],
    communicationGuide: { learnerSelfLevel: 'N4', topics: [{ code: 'COM', titleVi: 'Giao tiếp công việc', order: 1, priority: false,
      units: [{ lessonId: 11, vocabularyTopic: true, jlptLevel: null }] }],
      bookSuggestions: [{ level: 'N3', title: 'Tiếng Nhật N3', textbookId: 4, available: true, unreviewed: true }] } });
  const html = page.render(data);
  assert.match(html, /href="\/textbooks\/4"[^>]*>[^<]*Tiếng Nhật N3<\/a>/);
  assert.match(html, /ilp-books__unreviewed[^>]*>Chưa duyệt</);
  assert.doesNotMatch(html, /đang được biên soạn/);
});

// §2.28 / OD-44: chapters from the DRAFT J-LAS N3 textbook carry the server's unreviewed note; others carry none.
test('a chapter from an unreviewed textbook shows the server note once and a reviewed chapter shows nothing', () => {
  const chapter = (id, lessonId, note) => ({ id, phase: 'GOAL_LEARNING', dayNumber: id, orderIndex: id, title: 'Chủ đề ' + id,
    status: 'IN_PROGRESS', progressPercent: 0, chapterLessonId: lessonId, chapterTitle: 'Chủ đề ' + id, chapterNumber: id,
    segmentIndex: 1, segmentCount: 1, sourceReviewNote: note,
    activities: [{ id: id * 10, type: 'GRAMMAR', state: 'STUDYING', orderIndex: 1, dayNumber: id }] });
  const html = page.render(path({ packages: [chapter(1, 61, 'Giáo trình chưa duyệt — <nội dung> đang được rà soát.'),
    chapter(2, 902, null)] }));
  assert.equal((html.match(/ilp-chapter__unreviewed/g) || []).length, 1);
  assert.match(html, /Giáo trình chưa duyệt — &lt;nội dung&gt; đang được rà soát\./);
});

// OD-48 (owner 2026-10-20) + spec §2.1: when the readiness gate refuses a path, say which level misses which skill.
test('a path refused by the readiness gate names the level and the missing skills, escaped', () => {
  const html = page.errorState('BLOCKED_CONTENT', 'Chưa thể tạo lộ trình đầy đủ: cấp N3 còn thiếu Đọc, Nghe <b>. '
    + 'J-LAS không tạo lộ trình thiếu kỹ năng — nội dung sẽ được bổ sung trước khi mở cấp này.');
  assert.match(html, /Học liệu đang được hoàn thiện/);
  assert.match(html, /ilp-state__detail[^>]*>Chưa thể tạo lộ trình đầy đủ: cấp N3 còn thiếu Đọc, Nghe &lt;b&gt;\./);
  assert.match(html, /href="\/smart-test"/);
});

// Review 2026-10-20 (OD-48): after the readiness gate refuses a new path, "Kiểm tra lại" must really check again —
// retry the creation and keep naming the missing skills — instead of falling to a misleading "no path" state.
test('retrying after a readiness-gate refusal asks the server to create the path again and keeps the skill detail', async () => {
  const previousWindow = global.window, previousDocument = global.document, previousFetch = global.fetch;
  const listeners = {};
  const node = { innerHTML: '', addEventListener(type, listener) { (listeners[type] ||= []).push(listener); },
    setAttribute() {}, querySelector() { return null; }, querySelectorAll() { return []; } };
  const posts = [];
  global.window = {
    location: { search: '?assessmentAttemptId=55', pathname: '/learning/path/v2' },
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    JlasAuth: { requireLogin() { return true; }, authHeaders() { return {}; } }
  };
  global.document = { getElementById(id) { return id === 'interactivePathRoot' ? node : null; },
    querySelector() { return null; }, addEventListener() {}, body: { contains() { return false; } } };
  const refusal = 'Chưa thể tạo lộ trình đầy đủ: cấp N3 còn thiếu Đọc, Nghe. J-LAS không tạo lộ trình thiếu kỹ năng — nội dung sẽ được bổ sung trước khi mở cấp này.';
  global.fetch = async (url, options = {}) => {
    if (url === '/api/learning/v2/paths/current') return { ok: false, status: 404, json: async () => ({ success: false, code: 'NOT_FOUND', message: 'Bạn chưa có lộ trình tương tác đang hoạt động.' }) };
    if (url === '/api/learning/paths' && options.method === 'POST') {
      posts.push(options.body);
      return { ok: false, status: 422, json: async () => ({ success: false, code: 'BLOCKED_CONTENT', message: refusal }) };
    }
    throw new Error('Unexpected request: ' + url);
  };
  try {
    page.mount();
    for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve));
    assert.equal(posts.length, 1);
    assert.match(node.innerHTML, /cấp N3 còn thiếu Đọc, Nghe/);
    const target = { closest(selector) { return selector === '[data-retry]' ? target : null; } };
    for (const listener of listeners.click || []) await listener({ target });
    for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve));
    assert.equal(posts.length, 2, 'Kiểm tra lại retries the creation');
    assert.match(node.innerHTML, /cấp N3 còn thiếu Đọc, Nghe/);
    assert.doesNotMatch(node.innerHTML, /Chưa thể mở lộ trình/);
  } finally {
    global.window = previousWindow; global.document = previousDocument; global.fetch = previousFetch;
  }
});
