(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveLearningPathPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  var ACTIVITY_LABEL = { VOCABULARY: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', DIALOGUE: 'Hội thoại',
    READING: 'Đọc hiểu', LISTENING: 'Nghe hiểu', FOUNDATION: 'Bảng chữ cái' };
  // REVIEW/PRACTICE links are exposed only for activity types with owner-scoped replay routes.
  var REVIEW_TYPES = { VOCABULARY: true, KANJI: true, FOUNDATION: true, GRAMMAR: true, DIALOGUE: true, READING: true, LISTENING: true };
  var PRACTICE_TYPES = { VOCABULARY: true, KANJI: true, FOUNDATION: true, GRAMMAR: true, DIALOGUE: true, READING: true, LISTENING: true };
  var RESULTS_TYPES = { VOCABULARY: true, KANJI: true, FOUNDATION: true, GRAMMAR: true, READING: true, LISTENING: true };
  // Approved roadmap names: REVIEW is the read-only lesson, PRACTICE replays the quiz without touching progress.
  // Dialogue has no quiz, so its replay stays "Ôn lại".
  var MODE_LINK_LABEL = { REVIEW: 'Xem lại bài giảng', PRACTICE: 'Kiểm tra lại', RESULTS: 'Kết quả kiểm tra' };
  function modeLinkLabel(type, mode) {
    return type === 'DIALOGUE' && mode === 'PRACTICE' ? 'Ôn lại' : MODE_LINK_LABEL[mode] || mode;
  }
  /** "Từ vựng · Phần 2/3" when a pace change split one skill into ordered parts of the same package. */
  function activityLabel(a) {
    var label = ACTIVITY_LABEL[a.type] || a.type;
    var count = positiveInteger(a.partCount);
    var part = positiveInteger(a.partIndex);
    return count > 1 && part ? label + ' · Phần ' + part + '/' + count : label;
  }
  var STAGE_LEVEL_LABEL = { FOUNDATION: 'Bảng chữ cái' };
  var BLOCK_REASON = {
    BLOCKED_CONTENT: 'Học liệu chưa sẵn sàng cho chặng này nên chưa thể mở bài thi thử.',
    PRIOR_STAGE_INCOMPLETE: 'Hoàn thành 100% các chặng trước để mở bài thi thử.',
    TARGET_STAGE_BELOW_THRESHOLD: 'Hoàn thành ít nhất {percent}% chặng mục tiêu để mở bài thi thử.'
  };
  var PACE_LABEL = { LIGHTER: 'Nhẹ hơn', FASTER: 'Nhanh hơn' };
  var SKILL_DESCRIPTION = {
    VOCABULARY: 'Học nghĩa, cách đọc và ghi nhớ từ mới qua flashcard; sau đó làm bài kiểm tra.',
    KANJI: 'Nhận biết chữ Kanji, cách đọc và nghĩa trong các từ thường gặp.',
    GRAMMAR: 'Học mẫu câu, luyện sắp xếp câu và điền từ; có thể làm bài kiểm tra sau.',
    READING: 'Đọc đoạn văn ngắn, tìm thông tin và trả lời câu hỏi.',
    LISTENING: 'Nghe hội thoại hoặc thông báo, luyện bắt ý và trả lời câu hỏi.',
    DIALOGUE: 'Luyện nghe và phản hồi trong tình huống giao tiếp; hoàn thành các lượt thoại.',
    FOUNDATION: 'Luyện bảng chữ cái và các kiến thức nền tảng của tiếng Nhật.'
  };
  var COMMUNICATION_SKILL_DESCRIPTION = {
    VOCABULARY: 'Học từ và cụm từ dùng ngay trong tình huống này.',
    GRAMMAR: 'Luyện mẫu câu qua xếp câu và điền từ trong ngữ cảnh.',
    DIALOGUE: 'Luyện nghe và phản hồi theo các lượt hội thoại thực tế.'
  };
  var ACTIVITY_STATE_LABEL = { LOCKED: 'Chưa mở', STUDYING: 'Đang học', CHECK_READY: 'Sẵn sàng kiểm tra', COOLDOWN: 'Đang chờ', PASSED: 'Đã hoàn thành', COMPLETED: 'Đã hoàn thành' };

  var ACTIVITY_PATH = { VOCABULARY: '/learning/v2/vocabulary', KANJI: '/learning/v2/kanji', GRAMMAR: '/learning/v2/grammar', DIALOGUE: '/learning/v2/dialogue',
    READING: '/learning/v2/reading', LISTENING: '/learning/v2/listening', FOUNDATION: '/learning/v2/foundation' };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  function positiveInteger(value) {
    value = Number(value);
    return Number.isInteger(value) && value > 0 ? value : null;
  }
  function resolveAssessmentAttemptId(search, storage) {
    var params = new URLSearchParams(search || '');
    var fromUrl = positiveInteger(params.get('assessmentAttemptId'));
    if (fromUrl) return fromUrl;
    if (!storage || typeof storage.getItem !== 'function') return null;
    return positiveInteger(storage.getItem('assessmentAttemptId'));
  }
  function resolveReviewStartLevel(search) {
    var level = new URLSearchParams(search || '').get('reviewStartLevel');
    return /^(N5|N4|N3)$/.test(String(level || '')) ? level : null;
  }
  // The learner's answer to a Smart Test result below the self-reported level (owner 2026-10-19).
  function resolveDiscrepancyChoice(search) {
    var choice = new URLSearchParams(search || '').get('discrepancyChoice');
    return /^(ACCEPT|DECLINE)_RECOMMENDATION$/.test(String(choice || '')) ? choice : null;
  }
  function pathCreationRequest(attemptId, reviewStartLevel, discrepancyChoice) {
    if (!attemptId) return { url: '/api/learning/paths/from-zero', body: '{}' };
    var body = { assessmentAttemptId: attemptId,
      reviewStartLevel: /^(N5|N4|N3)$/.test(String(reviewStartLevel || '')) ? reviewStartLevel : null };
    if (/^(ACCEPT|DECLINE)_RECOMMENDATION$/.test(String(discrepancyChoice || ''))) body.discrepancyChoice = discrepancyChoice;
    return { url: '/api/learning/paths', body: JSON.stringify(body) };
  }
  function viewModel(data) {
    data = data || {};
    var packages = (Array.isArray(data.packages) ? data.packages : []).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    packages.forEach(function (pkg) {
      pkg.activities = (Array.isArray(pkg.activities) ? pkg.activities : []).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
      pkg.activityLabels = pkg.activities.map(activityLabel);
      pkg.canStart = pkg.status === 'AVAILABLE';
    });
    return {
      title: String(data.title || 'Lộ trình cá nhân').replace(/^\[DEMO\]\s*/i, ''), goal: data.goal || 'JLPT', currentLevel: data.currentLevel || '—',
      targetLevel: data.targetLevel || '—', minutesPerDay: num(data.minutesPerDay), progressPercent: num(data.progressPercent),
      completedPackages: num(data.completedPackages), totalPackages: num(data.totalPackages), packages: packages,
      smartTestReviewOnlyTargets: Array.isArray(data.smartTestReviewOnlyTargets)
        ? data.smartTestReviewOnlyTargets.filter(function (target) {
          return target && (target.skill === 'VOCABULARY' || target.skill === 'KANJI') &&
            target.target;
        }) : [],
      communicationGuide: data.communicationGuide && Array.isArray(data.communicationGuide.topics)
        ? data.communicationGuide : null
    };
  }
  function renderMockResult(result) {
    if (!result) return '';
    var status = result.passed ? 'ĐẠT' : 'CHƯA ĐẠT';
    var level = esc(result.level || 'JLPT');
    var href = '/jlpt/result?level=' + encodeURIComponent(result.level || 'N5');
    return '<section class="ilp-mock-result" aria-live="polite"><div><strong>Đã thi thử ' + level + ': ' + status +
      '</strong><p>' + esc(result.correctCount) + '/' + esc(result.questionCount) + ' câu đúng · ' + esc(result.scorePercent) + '%</p></div>' +
      '<a class="ilp-button" href="' + href + '">Xem kết quả JLPT</a></section>';
  }
  function modesFor(type) {
    var modes = [];
    if (REVIEW_TYPES[type]) modes.push('REVIEW');
    if (PRACTICE_TYPES[type]) modes.push('PRACTICE');
    if (RESULTS_TYPES[type]) modes.push('RESULTS');
    return modes;
  }
  function modeHref(type, id, mode) {
    return ACTIVITY_PATH[type] + '?activity=' + encodeURIComponent(id) + '&amp;mode=' + mode.toLowerCase();
  }
  function activityDone(a) {
    return a.type === 'DIALOGUE' ? a.state === 'COMPLETED' : a.state === 'PASSED' || a.state === 'COMPLETED';
  }
  function startedPackageStudyHref(pkg) {
    var activities = Array.isArray(pkg && pkg.activities) ? pkg.activities.slice() : [];
    activities.sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    var next = activities.find(function (activity) {
      return activity && ['STUDYING', 'CHECK_READY', 'COOLDOWN'].indexOf(activity.state) >= 0 &&
        ACTIVITY_PATH[activity.type] && positiveInteger(activity.id);
    });
    return next ? ACTIVITY_PATH[next.type] + '?activity=' + encodeURIComponent(next.id) : null;
  }
  function chapterSkillCards(parts, communicationGoal) {
    var skills = {};
    parts.forEach(function (pkg) {
      var sessions = Array.isArray(pkg.sessions) ? pkg.sessions : [];
      if (!sessions.length && Array.isArray(pkg.activities)) sessions = [{ activities: pkg.activities, legacy: true }];
      sessions.forEach(function (session) {
        (Array.isArray(session.activities) ? session.activities : []).forEach(function (activity) {
          // Keep the alphabet/foundation experience in its legacy list; the new skill picker is
          // for chapter skills that learners can open independently.
          if (activity.type === 'FOUNDATION') return;
          var skill = skills[activity.type] || (skills[activity.type] = { entries: [], count: 0, words: [], wordPackages: {} });
          skill.entries.push({ pkg: pkg, session: session, activity: activity });
          var countKey = { VOCABULARY: 'vocabulary', KANJI: 'kanji', GRAMMAR: 'grammar', DIALOGUE: 'dialogue', READING: 'reading', LISTENING: 'listening', FOUNDATION: 'foundation' }[activity.type];
          if (countKey && session.contentCounts && !skill.countedPackages) skill.countedPackages = {};
          if (countKey && session.contentCounts && !skill.countedPackages[pkg.id]) {
            skill.count += num(session.contentCounts[countKey]);
            skill.countedPackages[pkg.id] = true;
          }
          if (session.referenceVocabulary && !skill.wordPackages[pkg.id]) {
            skill.words = skill.words.concat(session.referenceVocabulary);
            skill.wordPackages[pkg.id] = true;
          }
        });
      });
    });
    var skillOrder = ['VOCABULARY', 'KANJI', 'GRAMMAR', 'READING', 'LISTENING', 'DIALOGUE'];
    var skillCards = skillOrder.filter(function (type) { return skills[type]; }).map(function (type) {
      var skill = skills[type];
      var completed = skill.entries.filter(function (entry) { return activityDone(entry.activity); }).length;
      var state = completed === skill.entries.length ? 'COMPLETED' :
        skill.entries.some(function (entry) { return entry.pkg.status === 'IN_PROGRESS'; }) ? 'IN_PROGRESS' :
        skill.entries.some(function (entry) { return entry.pkg.status === 'AVAILABLE'; }) ? 'AVAILABLE' : 'LOCKED';
      var rows = skill.entries.map(function (entry, index) {
        var pkg = entry.pkg, session = entry.session, activity = entry.activity;
        var done = activityDone(activity);
        var actions = session.legacy && done ? modesFor(type) :
          (Array.isArray(session.allowedActions) ? session.allowedActions : []);
        var childActions = done && (actions.indexOf('START') >= 0 || actions.indexOf('CONTINUE') >= 0) ? modesFor(type) : actions;
        // [JLAS-W0193] §2.24 — communication-goal paths have no quiz: a completed vocabulary/grammar activity
        // offers only its read-only review, never RESULTS or scored Practice-style modes.
        if (communicationGoal && done && (type === 'VOCABULARY' || type === 'GRAMMAR')) {
          childActions = childActions.filter(function (action) { return action === 'REVIEW'; });
        }
        if (done && !communicationGoal && RESULTS_TYPES[type] && childActions.indexOf('REVIEW') >= 0 && childActions.indexOf('RESULTS') < 0) childActions = childActions.concat('RESULTS');
        var links = childActions.filter(function (action) {
          return done && (action === 'REVIEW' || action === 'PRACTICE' || action === 'RESULTS') && ACTIVITY_PATH[type];
        }).map(function (action) {
          return '<a class="ilp-activity__mode" href="' + modeHref(type, activity.id, action) + '">' + modeLinkLabel(type, action) + '</a>';
        }).join('');
        var active = ['STUDYING', 'CHECK_READY', 'COOLDOWN'].indexOf(activity.state) >= 0;
        // The activity state is the server-owned access decision. Keep the link usable even when an
        // older response still reports the package as AVAILABLE while its selected child is STUDYING.
        var continueLink = active && ACTIVITY_PATH[type]
          ? '<a class="ilp-skill-part__continue" href="' + ACTIVITY_PATH[type] + '?activity=' + encodeURIComponent(activity.id) + '">Tiếp tục học</a>' : '';
        var start = pkg.status === 'AVAILABLE' && !done && !active ? '<button class="ilp-skill-part__start" type="button" data-start-package="' + esc(String(pkg.id)) +
          '" data-preferred-activity="' + esc(String(activity.id)) + '">Bắt đầu phần học</button>' : '';
        var locked = pkg.status === 'LOCKED' ? '<small class="ilp-skill-part__lock">' + esc(pkg.lockReason || 'Hoàn thành phần trước để mở.') + '</small>' : '';
        var stateLabel = pkg.status === 'LOCKED' ? 'Đang khóa' : pkg.status === 'AVAILABLE' ? 'Sẵn sàng' :
          ACTIVITY_STATE_LABEL[activity.state] || activity.state || '';
        var part = positiveInteger(pkg.segmentCount) > 1 ? '<small class="ilp-skill-part__number">' + esc(partLabel(pkg)) + '</small>' : '';
        return '<li class="ilp-skill-part' + (done ? ' is-done' : '') + '" data-package-card="' + esc(String(pkg.id)) + '">' +
          '<div class="ilp-skill-part__title"><span class="ilp-activity__mark">' + (done ? '✓' : String(index + 1).padStart(2, '0')) + '</span><div>' + part +
          '<strong>' + esc(activityLabel(activity)) + '</strong><small>' + esc(stateLabel) + '</small></div></div>' +
          (start || locked || continueLink || links ? '<div class="ilp-skill-part__actions">' + start + locked + continueLink + links + '</div>' : '') + '</li>';
      }).join('');
      var unit = { VOCABULARY: 'từ mới', KANJI: 'chữ Kanji', GRAMMAR: 'mẫu ngữ pháp', DIALOGUE: 'lượt thoại', READING: 'bài đọc', LISTENING: 'bài nghe' }[type];
      var words = skill.words.map(function (word) { return '<li><strong lang="ja">' + esc(word.expression) + '</strong>' +
        (word.reading ? ' <span lang="ja">' + esc(word.reading) + '</span>' : '') + (word.meaning ? ' — ' + esc(word.meaning) : '') + '</li>'; }).join('');
      return '<article class="ilp-skill-card ilp-skill-card--' + esc(state.toLowerCase()) + '"><button class="ilp-skill-card__toggle" type="button" data-skill-toggle aria-expanded="false">' +
        '<span class="ilp-skill-card__icon" aria-hidden="true">' + (state === 'COMPLETED' ? '✓' : '✦') + '</span><span class="ilp-skill-card__summary"><strong>' + esc(ACTIVITY_LABEL[type] || type) +
        '</strong><span>' + esc((communicationGoal ? COMMUNICATION_SKILL_DESCRIPTION[type] : null) || SKILL_DESCRIPTION[type] || 'Học và hoàn thành nội dung kỹ năng này.') + '</span></span><span class="ilp-skill-card__status">' +
        completed + '/' + skill.entries.length + ' hoạt động</span><span class="ilp-skill-card__chevron" aria-hidden="true">⌄</span></button>' +
        '<div class="ilp-skill-card__details" hidden>' + (skill.count ? '<p class="ilp-skill-card__meta">' + skill.count + ' ' + unit + '</p>' : '') +
        '<ol class="ilp-skill-card__parts">' + rows + '</ol>' + (words ? '<details class="ilp-reference"><summary>Từ tham khảo ngắn (' + skill.words.length + ')</summary><ul>' + words + '</ul></details>' : '') +
        '</div></article>';
    });
    if (!skillCards.length) return '';
    return '<section class="ilp-skill-choices" aria-label="Kỹ năng trong ' + (communicationGoal ? 'tình huống' : 'chương') + '"><header class="ilp-skill-choices__head"><div><span class="ilp-kicker">' +
      (communicationGoal ? 'NỘI DUNG TÌNH HUỐNG' : 'NỘI DUNG CHƯƠNG') + '</span><h4>Chọn kỹ năng muốn học</h4></div>' +
      '<p>' + (communicationGoal
        ? 'Bạn có thể học Từ vựng, Ngữ pháp hoặc Hội thoại theo thứ tự phù hợp. Hoàn thành cả ba kỹ năng để hoàn tất tình huống.'
        : 'Chọn kỹ năng bất kỳ trong chương. Mỗi kỹ năng gồm các phần nhỏ; hoàn thành mọi mục bắt buộc để mở chương tiếp theo.') + '</p></header>' +
      '<div class="ilp-skill-grid">' + skillCards.join('') + '</div></section>';
  }
  function foundationRows(parts) {
    var entries = [];
    parts.forEach(function (pkg) {
      (Array.isArray(pkg.activities) ? pkg.activities : []).forEach(function (activity) {
        if (activity && activity.type === 'FOUNDATION') entries.push({ pkg: pkg, activity: activity });
      });
    });
    if (!entries.length) return '';
    var rows = entries.map(function (entry, index) {
      var pkg = entry.pkg, activity = entry.activity, done = activityDone(activity);
      var active = ['STUDYING', 'CHECK_READY', 'COOLDOWN'].indexOf(activity.state) >= 0;
      var path = ACTIVITY_PATH.FOUNDATION;
      var state = pkg.status === 'LOCKED' ? 'Chưa mở' : done ? 'Đã hoàn thành' : active ? 'Đang học' : 'Sẵn sàng';
      var action = '';
      if (done) action = modesFor('FOUNDATION').map(function (mode) {
        return '<a class="ilp-activity__mode" href="' + modeHref('FOUNDATION', activity.id, mode) + '">' + modeLinkLabel('FOUNDATION', mode) + '</a>';
      }).join('');
      else if (active && pkg.status === 'IN_PROGRESS') action = '<a class="ilp-activity__cta" href="' + path + '?activity=' + encodeURIComponent(activity.id) + '">Tiếp tục học</a>';
      else if (active) action = '<a class="ilp-button" href="' + path + '?activity=' + encodeURIComponent(activity.id) + '">Tiếp tục học</a>';
      else if (pkg.status === 'AVAILABLE') action = '<button class="ilp-button" type="button" data-start-package="' + esc(String(pkg.id)) + '" data-preferred-activity="' + esc(String(activity.id)) + '">Bắt đầu học</button>';
      else if (pkg.status === 'LOCKED') action = '<small class="ilp-lock">' + esc(pkg.lockReason || 'Hoàn thành chương trước để mở.') + '</small>';
      return '<li class="ilp-activity' + (done ? ' is-done' : active ? ' is-active' : '') + '" data-package-card="' + esc(String(pkg.id)) + '">' +
        '<span class="ilp-activity__mark">' + (done ? '✓' : String(index + 1).padStart(2, '0')) + '</span><span><strong>' + esc(activityLabel(activity)) + '</strong><small>' + esc(state) + '</small></span>' +
        (action ? '<span class="ilp-activity__modes">' + action + '</span>' : '') + '</li>';
    }).join('');
    return '<section class="ilp-foundation-legacy" aria-label="Bảng chữ cái"><h4>Bảng chữ cái</h4><ul class="ilp-activities">' + rows + '</ul></section>';
  }
  // [JLAS-W0193] Task 8 — package completion popup: three actions (Trở về lộ trình / Học vượt / Nghỉ ngơi),
  // last-package variant routes to the (Task 10) summary placeholder instead. Only rendered for the package
  // the server just completed THIS call (`justCompleted`), not every historically completed package, so the
  // popup does not re-nag on every roadmap visit.
  function popupModel(pkg) {
    if (!pkg || !pkg.justCompleted) return null;
    var score = (pkg.scorePercent === null || pkg.scorePercent === undefined) ? null : num(pkg.scorePercent);
    return {
      packageId: pkg.id, title: pkg.title, scorePercent: score, isLast: !!pkg.lastPackage,
      nextPackageId: pkg.nextAvailablePackageId || null
    };
  }
  function firstDone(pkg, mode) {
    var list = (pkg && Array.isArray(pkg.activities)) ? pkg.activities : [];
    for (var i = 0; i < list.length; i++) {
      var activity = list[i];
      var done = activity.state === 'PASSED' || activity.state === 'COMPLETED';
      if (done && modesFor(activity.type).indexOf(mode) >= 0) return activity;
    }
    return null;
  }
  function renderPopup(pkg) {
    var model = popupModel(pkg);
    if (!model) return '';
    var scoreHtml = model.scorePercent == null ? '' :
      '<p class="ilp-popup__score">Điểm phần này: <strong>' + model.scorePercent + '%</strong></p>';
    var advanceHtml = model.isLast
      ? '<button type="button" class="ilp-popup__primary" data-popup-summary>Xem tổng kết chặng</button>'
      : '<button type="button" class="ilp-popup__primary"' +
        (model.nextPackageId ? ' data-popup-advance="' + esc(String(model.nextPackageId)) + '"' : ' disabled') +
        '>Tiếp tục</button>';
    var review = firstDone(pkg, 'REVIEW');
    var practice = firstDone(pkg, 'PRACTICE');
    var reviewHtml = review ? '<a class="ilp-popup__secondary" href="' + modeHref(review.type, review.id, 'REVIEW') + '">Xem lại</a>' : '';
    var practiceHtml = practice ? '<a class="ilp-popup__secondary" href="' + modeHref(practice.type, practice.id, 'PRACTICE') + '">Ôn lại</a>' : '';
    var practiceNote = practice ? '<p class="ilp-popup__note">Ôn lại là luyện tập: không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>' : '';
    return '<div class="ilp-popup-backdrop" data-popup-backdrop>' +
      '<section class="ilp-popup" role="dialog" aria-modal="true" aria-labelledby="ilpPopupTitle" tabindex="-1" data-popup="' +
      esc(String(model.packageId)) + '"><h2 id="ilpPopupTitle">Bạn đã hoàn thành phần bài học hôm nay</h2>' +
      '<p>Bạn muốn tiếp tục như thế nào?</p>' + scoreHtml +
      '<div class="ilp-popup__actions">' + advanceHtml + reviewHtml + practiceHtml +
      '<button type="button" class="ilp-popup__quiet" data-popup-return>Trở về lộ trình</button>' +
      '</div>' + practiceNote + '</section></div>';
  }
  // [JLAS-W0108] Task 11 - stage progress and the Mock Test gate. Every figure comes from the server's
  // GET /paths/current/final-eligibility; with no data nothing is rendered rather than a guessed state.
  function stageName(level) { return STAGE_LEVEL_LABEL[level] || level; }
  function renderStageProgress(elig) {
    if (!elig || !Array.isArray(elig.stages) || !elig.stages.length) return '';
    var items = elig.stages.map(function (st) {
      var percent = num(st.percent);
      var status = st.blocked ? 'Học liệu chưa sẵn sàng'
        : (st.status === 'COMPLETED' ? 'Đã hoàn thành' : percent > 0 ? 'Đang học' : 'Chưa bắt đầu');
      return '<li class="ilp-stage' + (st.blocked ? ' is-blocked' : st.status === 'COMPLETED' ? ' is-done' : '') +
        '" data-stage-level="' + esc(st.level) + '"><div class="ilp-stage__head"><strong>' + esc(stageName(st.level)) +
        '</strong><span>' + esc(status) + '</span></div><progress max="100" value="' + percent + '" aria-label="' +
        esc(stageName(st.level)) + '">' + percent + '%</progress><small>' + num(st.completedPackages) + '/' +
        num(st.totalPackages) + ' ngày · ' + percent + '%</small></li>';
    }).join('');
    return '<section class="ilp-stage-progress" aria-label="Tiến độ theo chặng"><span class="ilp-kicker">TIẾN ĐỘ THEO CHẶNG</span>' +
      '<ol class="ilp-stages">' + items + '</ol></section>';
  }
  function renderMockGate(elig) {
    if (!elig) return '';
    if (elig.eligible) {
      return '<div class="ilp-mock-gate is-open"><p>Bạn đã đủ điều kiện thi thử JLPT ' + esc(elig.targetLevel) + '.</p>' +
        '<a class="ilp-button" href="/learning/path/v2/summary">Xem tổng kết &amp; thi thử</a></div>';
    }
    var template = BLOCK_REASON[elig.reasonCode] || 'Chưa đủ điều kiện để mở bài thi thử.';
    var reason = template.replace('{percent}', esc(String(num(elig.requiredTargetPercent))));
    return '<div class="ilp-mock-gate is-locked"><p>' + reason + '</p>' +
      '<button class="ilp-button" type="button" disabled aria-disabled="true">Thi thử JLPT (chưa mở)</button></div>';
  }
  function renderPaceControls(minutesPerDay) {
    if (!minutesPerDay) return '';
    return '<div class="ilp-pace" role="group" aria-label="Nhịp học"><span class="ilp-kicker">NHỊP HỌC</span>' +
      '<p>' + num(minutesPerDay) + ' phút/ngày</p><div class="ilp-pace__actions">' +
      '<button type="button" data-pace-preview="LIGHTER">' + PACE_LABEL.LIGHTER + '</button>' +
      '<button type="button" data-pace-preview="FASTER">' + PACE_LABEL.FASTER + '</button></div></div>';
  }
  function renderPaceDialog(preview) {
    if (!preview) return '';
    var confirm = preview.changed
      ? '<button type="button" class="ilp-popup__primary" data-pace-confirm data-pace-direction="' + esc(preview.direction) +
        '" data-pace-minutes="' + num(preview.newMinutesPerDay) + '" data-pace-token="' + esc(preview.previewToken) + '">Xác nhận</button>'
      : '';
    var body = preview.changed
      ? '<p>Nhịp học đổi từ <strong>' + num(preview.currentMinutesPerDay) + ' phút</strong> sang <strong>' +
        num(preview.newMinutesPerDay) + ' phút</strong> mỗi ngày.</p><p>Số ngày còn lại: ' + num(preview.currentRemainingDays) +
        ' ngày → <strong>' + num(preview.newRemainingDays) + ' ngày</strong>.</p>' +
        '<p class="ilp-popup__note">Nhịp mới áp dụng cho các bài chưa bắt đầu. Bài đang học giữ nguyên lịch; nội dung, tiến độ và mục tiêu không đổi.</p>'
      : '<p>Nhịp học đã ở mức ' + num(preview.currentMinutesPerDay) + ' phút/ngày — không thể đổi thêm theo hướng này.</p>';
    return '<div class="ilp-popup-backdrop" data-pace-backdrop><section class="ilp-popup" role="dialog" aria-modal="true" ' +
      'aria-labelledby="ilpPaceTitle" tabindex="-1" data-pace-dialog><h2 id="ilpPaceTitle">Đổi nhịp học</h2>' + body +
      '<div class="ilp-popup__actions">' + confirm + '<button type="button" class="ilp-popup__quiet" data-pace-cancel>Hủy</button></div>' +
      '</section></div>';
  }
  function paceRequestBody(preview) {
    return JSON.stringify({ direction: preview.direction, newMinutesPerDay: preview.newMinutesPerDay, previewToken: preview.previewToken });
  }
  function renderToast(message) {
    if (!message) return '';
    return '<div class="ilp-toast" role="status" aria-live="polite" data-toast>' + esc(message) + '</div>';
  }
  /** Next focus index when Tab is pressed inside a dialog with {@code count} focusable controls. */
  function nextFocusIndex(index, count, backwards) {
    if (!count) return -1;
    if (backwards) return index <= 0 ? count - 1 : index - 1;
    return index >= count - 1 ? 0 : index + 1;
  }
  // Owner decision 2026-10-06: the roadmap unit is the chapter (source lesson) with ordered parts, not the
  // calendar day — a learner may finish several parts in one sitting, so no day labels are shown.
  function chapterNumber(pkg) { return positiveInteger(pkg.chapterNumber) || num(pkg.orderIndex); }
  function phaseLabel(pkg) { return pkg.phase === 'WARM_UP' ? 'KHỞI ĐỘNG' : 'MỤC TIÊU'; }
  function chapterKicker(pkg, communicationGoal) {
    return communicationGoal ? 'TÌNH HUỐNG ' + chapterNumber(pkg) : 'CHƯƠNG ' + chapterNumber(pkg) + ' · ' + esc(phaseLabel(pkg));
  }
  function partLabel(pkg) {
    return 'Phần ' + (positiveInteger(pkg.segmentIndex) || 1) + '/' + (positiveInteger(pkg.segmentCount) || 1);
  }
  /** The server already drops the textbook's "Bài 17:" prefix; kept here so an older payload never leaks it. */
  function chapterTitle(pkg) {
    var raw = String(pkg.chapterTitle || pkg.title || '');
    var stripped = raw.replace(/^\s*Bài\s*\d+\s*[:：.\-–—]\s*/, '');
    return stripped || raw;
  }
  function chapterKey(pkg) {
    return positiveInteger(pkg.chapterLessonId) ? pkg.phase + ':' + pkg.chapterLessonId : 'PACKAGE:' + pkg.id;
  }
  /** Consecutive packages from one source lesson render as a single chapter with skills spanning all parts.
   * {@code kickerFor}/{@code noteFor} let the communication topic view relabel a card without changing it. */
  function chapterCards(packages, communicationGoal, kickerFor, noteFor) {
    var groups = [];
    packages.forEach(function (pkg) {
      var last = groups[groups.length - 1];
      if (last && last.key === chapterKey(pkg)) last.parts.push(pkg);
      else groups.push({ key: chapterKey(pkg), parts: [pkg] });
    });
    return groups.map(function (group) {
      var first = group.parts[0];
      var activities = group.parts.reduce(function (all, pkg) {
        return all.concat(Array.isArray(pkg.activities) ? pkg.activities : []);
      }, []);
      var done = activities.filter(activityDone).length;
      var total = activities.length;
      var progress = total ? Math.round(done * 100 / total) : 0;
      return '<section class="ilp-chapter" data-chapter="' + esc(group.key) + '"><header class="ilp-chapter__head"><div>' +
        '<span class="ilp-kicker">' + (kickerFor ? kickerFor(first) : chapterKicker(first, communicationGoal)) + '</span><h3>' + esc(chapterTitle(first)) + '</h3></div>' +
        '<span class="ilp-status">' + (total ? done + '/' + total + ' hoạt động đã hoàn thành' : 'Chưa có hoạt động học') + '</span></header>' +
        (noteFor ? noteFor(first) : '') + sourceReviewNote(first) +
        '<div class="ilp-chapter__progress" role="progressbar" aria-label="Tiến độ ' + (communicationGoal ? 'tình huống ' : 'chương ') + chapterNumber(first) + '" aria-valuenow="' + progress + '" aria-valuemin="0" aria-valuemax="100"><span style="width:' + progress + '%"></span></div>' +
        foundationRows(group.parts) + chapterSkillCards(group.parts, communicationGoal) + '</section>';
    }).join('');
  }
  /** §2.28 / OD-44: the server notes a chapter whose textbook is still DRAFT; it is studied, never called reviewed. */
  function sourceReviewNote(pkg) {
    return pkg && pkg.sourceReviewNote
      ? '<p class="ilp-chapter__hint ilp-chapter__unreviewed">' + esc(pkg.sourceReviewNote) + '</p>' : '';
  }
  /** §2.24 (owner 2026-10-19, Q5): optional J-LAS textbooks for BrSE learners; a book learners cannot open yet has no link.
   * OD-45 (owner 2026-10-20): a book open while still DRAFT links normally and carries a "Chưa duyệt" label. */
  function bookSuggestions(guide) {
    var books = guide && Array.isArray(guide.bookSuggestions) ? guide.bookSuggestions : [];
    if (!books.length) return '';
    return '<aside class="ilp-books" aria-label="Sách tham khảo"><h2>Sách tham khảo (tự nguyện)</h2>' +
      '<p>Không bắt buộc — bạn vẫn học mọi tình huống bình thường. Đọc thêm giáo trình giúp nắm chắc nền ngữ pháp và từ vựng.</p><ul>' +
      books.map(function (book) {
        var label = 'Giáo trình ' + esc(book.level) + ' — ' + esc(book.title);
        return '<li>' + (book.available && Number(book.textbookId) > 0
          ? '<a class="ilp-books__link" href="/textbooks/' + encodeURIComponent(book.textbookId) + '">' + label + '</a>' +
            (book.unreviewed ? ' <span class="ilp-books__unreviewed" title="Giáo trình chưa được duyệt — nội dung có thể còn sai sót.">Chưa duyệt</span>' : '')
          : '<span class="ilp-books__pending">' + label + ' · đang được biên soạn, sẽ mở khi phát hành</span>') + '</li>';
      }).join('') + '</ul></aside>';
  }

  // Owner 2026-10-19: a communication roadmap groups units by topic — topic vocabulary first, then its dialogue
  // situations easy → hard, survey focus topics first. Display only: every unit stays freely selectable.
  var JLPT_RANK = { FROM_ZERO: 0, N5: 1, N4: 2, N3: 3, N2: 4, N1: 5 };
  function communicationTopics(packages, guide) {
    var byLesson = {};
    packages.forEach(function (pkg) {
      var key = String(pkg.chapterLessonId || '');
      (byLesson[key] = byLesson[key] || []).push(pkg);
    });
    var selfKey = String(guide.learnerSelfLevel || '').toUpperCase();
    var selfRank = Object.prototype.hasOwnProperty.call(JLPT_RANK, selfKey) ? JLPT_RANK[selfKey] : null;
    var used = {};
    var sections = guide.topics.map(function (topic) {
      var units = {};
      var parts = [];
      (Array.isArray(topic.units) ? topic.units : []).forEach(function (unit) {
        var key = String(unit.lessonId);
        if (!byLesson[key] || used[key]) return;
        used[key] = true;
        units[key] = unit;
        parts = parts.concat(byLesson[key]);
      });
      if (!parts.length) return '';
      var activities = parts.reduce(function (all, pkg) { return all.concat(Array.isArray(pkg.activities) ? pkg.activities : []); }, []);
      var done = activities.filter(activityDone).length;
      var levelOf = function (pkg) {
        var unit = units[String(pkg.chapterLessonId)] || {};
        return unit.vocabularyTopic ? '' : String(unit.jlptLevel || '').toUpperCase();
      };
      var kicker = function (pkg) {
        var unit = units[String(pkg.chapterLessonId)] || {};
        if (unit.vocabularyTopic) return 'TỪ VỰNG CHỦ ĐỀ';
        var level = levelOf(pkg);
        return 'TÌNH HUỐNG' + (level ? ' · ' + esc(level) : '');
      };
      var note = function (pkg) {
        var level = levelOf(pkg);
        if (selfRank === null || !JLPT_RANK[level] || JLPT_RANK[level] <= selfRank) return '';
        return '<p class="ilp-chapter__hint">Khó hơn trình độ hiện tại của bạn (' + esc(selfKey === 'FROM_ZERO' ? 'mới bắt đầu' : selfKey) +
          '). Bạn vẫn học được — nên xem Từ vựng chủ đề trước, bật bản dịch và dùng “Nghe chậm”.</p>';
      };
      return '<section class="ilp-topic" data-topic="' + esc(topic.code) + '"><header class="ilp-topic__head"><div>' +
        '<span class="ilp-kicker">CHỦ ĐỀ</span><h2>' + esc(topic.titleVi) + '</h2></div>' +
        (topic.priority ? '<span class="ilp-topic__priority">Ưu tiên của bạn</span>' : '') +
        '<span class="ilp-status">' + done + '/' + activities.length + ' hoạt động</span></header>' +
        chapterCards(parts, true, kicker, note) + '</section>';
    }).join('');
    var rest = packages.filter(function (pkg) { return !used[String(pkg.chapterLessonId || '')]; });
    return sections + (rest.length ? chapterCards(rest, true) : '');
  }
  function phaseTabs(activePhase) {
    var tabs = [{ value: 'ALL', label: 'Tất cả' }, { value: 'WARM_UP', label: 'Khởi động' },
      { value: 'GOAL_LEARNING', label: 'Mục tiêu' }];
    return '<div class="ilp-phase-tabs" role="tablist" aria-label="Giai đoạn">' + tabs.map(function (tab) {
      var selected = tab.value === activePhase;
      return '<button type="button" role="tab" aria-selected="' + selected + '" data-phase-filter="' + tab.value + '"' +
        (selected ? ' class="is-active"' : '') + '>' + tab.label + '</button>';
    }).join('') + '</div>';
  }
  function renderSmartTestReviewOnly(targets) {
    if (!targets || !targets.length) return '';
    var groups = { VOCABULARY: [], KANJI: [] };
    var labels = { VOCABULARY: 'Từ vựng', KANJI: 'Kanji' };
    targets.forEach(function (target) {
      if (groups[target.skill]) groups[target.skill].push(target);
    });
    var content = Object.keys(groups).filter(function (skill) { return groups[skill].length; }).map(function (skill) {
      return '<section><h3>' + labels[skill] + '</h3><ul>' + groups[skill].map(function (target) {
        return '<li lang="ja">' + esc(target.target) + '</li>';
      }).join('') + '</ul></section>';
    }).join('');
    return '<aside class="ilp-review-only" aria-labelledby="ilpReviewOnlyTitle"><div><span class="ilp-kicker">GỢI Ý ÔN TẬP</span>' +
      '<h2 id="ilpReviewOnlyTitle">Nội dung nên xem lại</h2><p>Các mục dưới đây có thể xem nhanh trong lộ trình học.</p></div>' +
      '<div class="ilp-review-only__groups">' + content + '</div></aside>';
  }
  function render(data, phaseFilter, extras) {
    extras = extras || {};
    var v = viewModel(data);
    var communicationGoal = ['BA', 'TRAVEL', 'DAILY_LIFE'].indexOf(v.goal) >= 0;
    phaseFilter = phaseFilter || 'ALL';
    var visiblePackages = communicationGoal || phaseFilter === 'ALL' ? v.packages : v.packages.filter(function (pkg) { return pkg.phase === phaseFilter; });
    return '<section class="ilp-hero"><div><span class="ilp-label">Lộ trình của bạn</span><h1>' + esc(v.title) +
      '</h1><p>Mỗi phần học được chia vừa sức. Bạn có thể tiếp tục từ nơi đã dừng.</p><div class="ilp-metrics"><span>' +
      esc(v.currentLevel) + ' → ' + esc(v.targetLevel) + '</span>' + (v.minutesPerDay ? '<span>' + v.minutesPerDay + ' phút/ngày</span>' : '') +
      '<span>' + v.totalPackages + ' bài học</span></div></div><div class="ilp-total"><div><span>TIẾN ĐỘ TỔNG</span><strong>' +
      v.progressPercent + '%</strong></div><progress max="100" value="' + v.progressPercent + '">' + v.progressPercent + '%</progress><small>' +
      v.completedPackages + '/' + v.totalPackages + ' bài hoàn thành</small></div></section>' +
      (communicationGoal ? '' : renderMockResult(extras.mockResult) + renderStageProgress(extras.eligibility) + renderMockGate(extras.eligibility) + renderSmartTestReviewOnly(v.smartTestReviewOnlyTargets)) +
      '<div class="ilp-layout"><section>' + (communicationGoal ? '' : phaseTabs(phaseFilter)) + '<div class="ilp-days">' +
      (visiblePackages.length ? (communicationGoal && v.communicationGuide
        ? bookSuggestions(v.communicationGuide) + communicationTopics(visiblePackages, v.communicationGuide)
        : chapterCards(visiblePackages, communicationGoal)) :'<div class="ilp-empty"><h2>Chưa có bài học trong giai đoạn này</h2><p>Nội dung đang được hoàn thiện để phát hành.</p></div>') +
      '</div></section><aside class="ilp-aside"><span class="ilp-kicker">MỤC TIÊU HIỆN TẠI</span><dl><div><dt>Cấp độ</dt><dd>' + esc(v.currentLevel) + ' → ' + esc(v.targetLevel) +
      '</dd></div>' + (v.minutesPerDay ? '<div><dt>Cường độ</dt><dd>' + v.minutesPerDay + ' phút/ngày</dd></div>' : '') +
      '</dl>' + renderPaceControls(v.minutesPerDay) + '</aside></div>' +
      renderPopup(v.packages.filter(function (p) { return p.justCompleted; })[0]) + renderToast(extras.toast);
  }
  function errorState(code, message) {
    if (code === 'BLOCKED_CONTENT' || /CoverageGap|thiếu học liệu|chưa được phát hành/i.test(message || '')) {
      return '<section class="ilp-state ilp-state--readiness"><span class="ilp-state__icon" aria-hidden="true">文</span>' +
        '<h1>Học liệu đang được hoàn thiện</h1><p>Lộ trình của bạn đã được phân tích, nhưng một số nội dung chưa đủ điều kiện phát hành. Tiến độ Smart Test vẫn được giữ nguyên.</p>' +
        // OD-48 + spec §2.1: the readiness gate's learner message names the level and the missing skills.
        (/^Chưa thể tạo lộ trình đầy đủ:/.test(message || '') ? '<p class="ilp-state__detail">' + esc(message) + '</p>' : '') +
        '<div class="ilp-state__actions"><button data-retry type="button">Kiểm tra lại</button>' +
        '<a href="/smart-test">Quay lại Smart Test</a></div></section>';
    }
    return '<section class="ilp-state"><h1>Chưa thể mở lộ trình</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p><button data-retry type="button">Thử lại</button></section>';
  }
  async function completedPathDestination(fetcher, headers) {
    var response = await fetcher('/api/learning/v2/paths/current/summary', { headers: headers });
    var body = await response.json();
    if (response.ok && body.success !== false && body.data && body.data.status === 'COMPLETED') {
      return '/learning/path/v2/summary';
    }
    if (!response.ok && response.status !== 404 && body.code !== 'NOT_FOUND') {
      throw new Error(body.message || 'Không thể kiểm tra lộ trình đã hoàn thành.');
    }
    return null;
  }
  function mount() {
    var node = document.getElementById('interactivePathRoot');
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/path/v2')) return;
    var attemptedBootstrap = false;
    var currentPathData = null;
    var phaseFilter = 'ALL';
    var eligibilityData = null;
    var latestMockResult = null;
    var toastMessage = '';
    var toastTimer = null;
    var assessmentAttemptId = resolveAssessmentAttemptId(window.location.search, window.localStorage);
    var reviewStartLevel = resolveReviewStartLevel(window.location.search);
    var discrepancyChoice = resolveDiscrepancyChoice(window.location.search);
    async function bootstrapPath() {
      var request = pathCreationRequest(assessmentAttemptId, reviewStartLevel, discrepancyChoice);
      var response = await fetch(request.url, {
        method: 'POST', headers: window.JlasAuth.authHeaders(), body: request.body
      });
      var body = await response.json();
      if (!response.ok || body.success === false) {
        var failure = new Error(body.message || 'Không thể tạo lộ trình tương tác.');
        failure.code = body.code;
        throw failure;
      }
    }
    // Best effort: a failed eligibility read hides the stage panel; it never blocks the roadmap itself.
    async function loadEligibility() {
      try {
        var response = await fetch('/api/learning/v2/paths/current/final-eligibility', { headers: window.JlasAuth.authHeaders() });
        var body = await response.json();
        return response.ok && body.data ? body.data : null;
      } catch (e) { return null; }
    }
    function viewExtras() { return { eligibility: eligibilityData, mockResult: latestMockResult, toast: toastMessage }; }
    async function loadLatestMockResult(level) {
      if (!/^N[1-5]$/.test(String(level || ''))) return null;
      try {
        var response = await fetch('/api/jlpt/mock-attempts/latest?level=' + encodeURIComponent(level), { headers: window.JlasAuth.authHeaders() });
        var body = await response.json();
        return response.ok && body.success !== false && body.data ? body.data : null;
      } catch (e) { return null; }
    }
    function clearToastSoon() {
      if (!toastMessage) return;
      clearTimeout(toastTimer);
      toastTimer = setTimeout(function () { toastMessage = ''; var toast = node.querySelector('[data-toast]'); if (toast) toast.remove(); }, 4000);
    }
    function rerender() { node.innerHTML = render(currentPathData, phaseFilter, viewExtras()); afterRender(node); clearToastSoon(); }
    function closePaceDialog() {
      var backdrop = node.querySelector('[data-pace-backdrop]');
      var opener = node.__paceOpener;
      if (backdrop) backdrop.remove();
      if (opener && document.body.contains(opener) && typeof opener.focus === 'function') opener.focus();
    }
    async function load() {
      node.setAttribute('aria-busy', 'true');
      try { var response = await fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() }); var body = await response.json();
        if ((response.status === 404 || body.code === 'NOT_FOUND') && !attemptedBootstrap) {
          attemptedBootstrap = true;
          // A completed path is absent from /current. Ordinary navigation must not create a new one.
          // Only an explicit assessment URL requests generation for a new goal; ignore stale storage here.
          if (!positiveInteger(new URLSearchParams(window.location.search || '').get('assessmentAttemptId'))) {
            var completedDestination = await completedPathDestination(fetch, window.JlasAuth.authHeaders());
            if (completedDestination) {
              var finishedResponse = await fetch('/api/learning/v2/paths/completed/roadmap', { headers: window.JlasAuth.authHeaders() });
              var finishedBody = await finishedResponse.json();
              if (!finishedResponse.ok || finishedBody.success === false || !finishedBody.data) {
                throw new Error(finishedBody.message || 'Không thể mở lại lộ trình đã hoàn thành.');
              }
              currentPathData = finishedBody.data;
              latestMockResult = await loadLatestMockResult(currentPathData.targetLevel);
              node.innerHTML = render(currentPathData, phaseFilter, viewExtras());
              var pace = node.querySelector('[data-pace-preview]');
              if (pace && pace.parentElement) pace.parentElement.innerHTML = latestMockResult
                ? '<a class="ilp-button" href="/jlpt/result?level=' + encodeURIComponent(latestMockResult.level) + '">Xem kết quả JLPT</a>'
                : '<a class="ilp-button" href="/learning/path/v2/summary">Xem tổng kết lộ trình</a>';
              node.setAttribute('aria-busy', 'false');
              afterRender(node);
              return;
            }
          }
          try { await bootstrapPath(); }
          // A refused creation (e.g. the OD-48 readiness gate) is re-attempted by "Kiểm tra lại", not skipped.
          catch (failure) { attemptedBootstrap = false; throw failure; }
          return load();
        }
        if (response.ok && body.data) {
          currentPathData = body.data;
          eligibilityData = await loadEligibility();
          latestMockResult = await loadLatestMockResult(currentPathData.targetLevel);
          node.innerHTML = render(body.data, phaseFilter, viewExtras());
          clearToastSoon();
        }
        else node.innerHTML = errorState(body.code, body.message);
        afterRender(node); }
      catch (e) { node.innerHTML = errorState(e && e.code, e && e.message ? e.message : 'Không thể kết nối tới máy chủ.'); }
      node.setAttribute('aria-busy', 'false');
    }
    function closePopup(trigger) {
      var popup = node.querySelector('[data-popup]');
      var packageId = popup ? popup.getAttribute('data-popup') : null;
      var backdrop = node.querySelector('[data-popup-backdrop]');
      if (backdrop) backdrop.remove();
      if (trigger && typeof trigger.focus === 'function') trigger.focus();
      return packageId;
    }
    function highlightPackage(packageId) {
      if (!packageId) return;
      var card = node.querySelector('[data-package-card="' + CSS.escape(packageId) + '"]');
      if (!card) return;
      card.scrollIntoView({ block: 'center', behavior: 'auto' });
      card.classList.add('is-highlighted');
      var clear = function () { card.classList.remove('is-highlighted'); };
      card.addEventListener('animationend', clear, { once: true });
      setTimeout(clear, 1800); // fallback for prefers-reduced-motion (no animationend fires there)
    }
    function trapDialogFocus(event, dialog) {
      if (!dialog || event.key !== 'Tab') return;
      var focusable = Array.prototype.slice.call(dialog.querySelectorAll('button:not([disabled]), a[href]'));
      if (!focusable.length) return;
      var index = focusable.indexOf(document.activeElement);
      var wraps = event.shiftKey ? index <= 0 : index === focusable.length - 1;
      if (!wraps) return;
      event.preventDefault();
      focusable[nextFocusIndex(index, focusable.length, event.shiftKey)].focus();
    }
    function trapFocus(event) { trapDialogFocus(event, node.querySelector('[data-popup]')); }
    node.addEventListener('click', async function (event) {
      if (event.target.closest('[data-retry]')) return load();
      var skillToggle = event.target.closest('[data-skill-toggle]');
      if (skillToggle) {
        var expanded = skillToggle.getAttribute('aria-expanded') === 'true';
        skillToggle.setAttribute('aria-expanded', String(!expanded));
        skillToggle.closest('.ilp-skill-card').querySelector('.ilp-skill-card__details').hidden = expanded;
        return;
      }
      var phaseButton = event.target.closest('[data-phase-filter]');
      if (phaseButton && currentPathData) {
        phaseFilter = phaseButton.dataset.phaseFilter;
        rerender();
        return;
      }

      var paceButton = event.target.closest('[data-pace-preview]');
      if (paceButton && currentPathData) {
        node.__paceOpener = paceButton;
        try {
          var previewResponse = await fetch('/api/learning/v2/paths/current/pace/preview?direction=' +
            encodeURIComponent(paceButton.dataset.pacePreview), { headers: window.JlasAuth.authHeaders() });
          var previewBody = await previewResponse.json();
          if (!previewResponse.ok || !previewBody.data) { toastMessage = previewBody.message || 'Không thể xem trước nhịp học.'; return rerender(); }
          node.insertAdjacentHTML('beforeend', renderPaceDialog(previewBody.data));
          var dialog = node.querySelector('[data-pace-dialog]'); if (dialog) dialog.focus();
        } catch (e) { toastMessage = 'Không thể kết nối tới máy chủ.'; rerender(); }
        return;
      }
      if (event.target.closest('[data-pace-cancel]') || event.target === node.querySelector('[data-pace-backdrop]')) {
        return closePaceDialog();
      }
      var paceConfirm = event.target.closest('[data-pace-confirm]');
      if (paceConfirm) {
        if (paceConfirm.disabled) return;
        paceConfirm.disabled = true;
        try {
          var applyResponse = await fetch('/api/learning/v2/paths/current/pace', {
            method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
            body: paceRequestBody({ direction: paceConfirm.dataset.paceDirection,
              newMinutesPerDay: Number(paceConfirm.dataset.paceMinutes), previewToken: paceConfirm.dataset.paceToken })
          });
          var applyBody = await applyResponse.json();
          toastMessage = applyResponse.ok ? 'Đã cập nhật nhịp học.' : (applyBody.message || 'Không thể đổi nhịp học.');
        } catch (e) { toastMessage = 'Không thể kết nối tới máy chủ.'; }
        await load();
        return;
      }

      if (event.target.closest('[data-popup-return]') || event.target === node.querySelector('[data-popup-backdrop]')) {
        return highlightPackage(closePopup());
      }
      if (event.target.closest('[data-popup-summary]')) { window.location.href = '/learning/path/v2/summary'; return; }
      var advanceButton = event.target.closest('[data-popup-advance]');
      if (advanceButton) {
        if (advanceButton.disabled) return;
        advanceButton.disabled = true;
        try {
          var startResponse = await fetch('/api/learning/v2/packages/' + encodeURIComponent(advanceButton.dataset.popupAdvance) +
            '/start', { method: 'POST', headers: window.JlasAuth.authHeaders() });
          var startBody = await startResponse.json().catch(function () { return {}; });
          if (!startResponse.ok) { advanceButton.disabled = false; return; }
          var nextHref = startedPackageStudyHref(startBody.data);
          if (nextHref) { window.location.href = nextHref; return; }
          closePopup();
          await load();
        } catch (e) { advanceButton.disabled = false; }
        return;
      }

      var button = event.target.closest('[data-start-package]'); if (!button) return;
      button.disabled = true;
      try { var response = await fetch('/api/learning/v2/packages/' + encodeURIComponent(button.dataset.startPackage) + '/start', { method: 'POST', headers: window.JlasAuth.authHeaders() });
        var body = await response.json().catch(function () { return {}; });
        if (!response.ok) { node.innerHTML = errorState(body.code, body.message); return; }
        var preferredId = positiveInteger(button.dataset.preferredActivity);
        var unlocked = Array.isArray(body.data && body.data.activities) ? body.data.activities.find(function (activity) {
          return preferredId === positiveInteger(activity.id) &&
            ['STUDYING', 'CHECK_READY', 'COOLDOWN'].indexOf(activity.state) >= 0 && ACTIVITY_PATH[activity.type];
        }) : null;
        var studyHref = unlocked ? ACTIVITY_PATH[unlocked.type] + '?activity=' + encodeURIComponent(unlocked.id)
          : startedPackageStudyHref(body.data);
        if (studyHref) { window.location.href = studyHref; return; }
        await load(); }
      catch (e) { node.innerHTML = errorState(null, 'Không thể bắt đầu ngày học.'); }
    });
    node.addEventListener('keydown', function (event) {
      if (node.querySelector('[data-pace-dialog]')) {
        if (event.key === 'Escape') { closePaceDialog(); return; }
        trapDialogFocus(event, node.querySelector('[data-pace-dialog]'));
        return;
      }
      var popup = node.querySelector('[data-popup]'); if (!popup) return;
      if (event.key === 'Escape') { closePopup(); return; }
      trapFocus(event);
    });
    load();
  }
  function afterRender(node) {
    var popup = node.querySelector('[data-popup]');
    if (popup) popup.focus();
  }
  return { viewModel: viewModel, render: render, mount: mount, modesFor: modesFor, renderStageProgress: renderStageProgress,
    renderMockGate: renderMockGate, renderMockResult: renderMockResult, renderPaceControls: renderPaceControls, renderPaceDialog: renderPaceDialog,
    paceRequestBody: paceRequestBody, renderToast: renderToast, nextFocusIndex: nextFocusIndex, popupModel: popupModel, renderPopup: renderPopup, errorState: errorState,
    afterRender: afterRender, resolveAssessmentAttemptId: resolveAssessmentAttemptId,
    resolveReviewStartLevel: resolveReviewStartLevel, resolveDiscrepancyChoice: resolveDiscrepancyChoice,
    pathCreationRequest: pathCreationRequest,
    startedPackageStudyHref: startedPackageStudyHref, completedPathDestination: completedPathDestination };
});
