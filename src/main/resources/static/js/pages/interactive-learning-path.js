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
  // Approved roadmap names: REVIEW is the read-only lesson, PRACTICE replays the quiz without touching progress.
  // Dialogue has no quiz, so its replay stays "Ôn lại".
  var MODE_LINK_LABEL = { REVIEW: 'Xem lại bài giảng', PRACTICE: 'Kiểm tra lại' };
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
  var PACKAGE_STATUS_LABEL = { AVAILABLE: 'Sẵn sàng', IN_PROGRESS: 'Đang học', COMPLETED: 'Đã hoàn thành', LOCKED: 'Chưa mở' };
  var SESSION_LABEL = { VOCABULARY: 'Từ vựng', KANJI_GRAMMAR: 'Ngữ pháp / Kanji', DIALOGUE: 'Hội thoại' };
  var SESSION_STATE_LABEL = { LOCKED: 'Đang khóa', AVAILABLE: 'Sẵn sàng', IN_PROGRESS: 'Đang học', COMPLETED: 'Đã hoàn thành' };
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
  function pathCreationRequest(attemptId) {
    return attemptId
      ? { url: '/api/learning/paths', body: JSON.stringify({ assessmentAttemptId: attemptId }) }
      : { url: '/api/learning/paths/from-zero', body: '{}' };
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
      title: data.title || 'Lộ trình cá nhân', goal: data.goal || 'JLPT', currentLevel: data.currentLevel || '—',
      targetLevel: data.targetLevel || '—', minutesPerDay: num(data.minutesPerDay), progressPercent: num(data.progressPercent),
      completedPackages: num(data.completedPackages), totalPackages: num(data.totalPackages), packages: packages,
      smartTestReviewOnlyTargets: Array.isArray(data.smartTestReviewOnlyTargets)
        ? data.smartTestReviewOnlyTargets.filter(function (target) {
          return target && (target.skill === 'VOCABULARY' || target.skill === 'KANJI' || target.skill === 'GRAMMAR') &&
            target.target;
        }) : []
    };
  }
  function modesFor(type) {
    var modes = [];
    if (REVIEW_TYPES[type]) modes.push('REVIEW');
    if (PRACTICE_TYPES[type]) modes.push('PRACTICE');
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
  function activityRow(a, index) {
    var done = activityDone(a);
    var active = a.state === 'STUDYING' || a.state === 'CHECK_READY' || a.state === 'COOLDOWN';
    var label = activityLabel(a);
    var state = done ? 'Đã hoàn thành' : active ? 'Đang học' : 'Chưa mở';
    var inner = '<span class="ilp-activity__mark">' + (done ? '✓' : String(index + 1).padStart(2, '0')) + '</span>' +
      '<span><strong>' + esc(label) + '</strong><small>' + esc(state) + activityDayLabel(a) + '</small></span>';
    var css = 'ilp-activity' + (done ? ' is-done' : active ? ' is-active' : '');
    // [JLAS-W0193] RC Task 5 — Reading/Listening routes keyed by the server-reported activity type, same as
    // every other entry here: the roadmap never guesses which player to open, it only looks up `a.type`.
    var path = ACTIVITY_PATH[a.type];
    if (path && !done && active) {
      return '<li class="' + css + '"><a class="ilp-activity__link" href="' + path + '?activity=' +
        encodeURIComponent(a.id) + '">' + inner + '<span class="ilp-activity__cta">Tiếp tục học</span></a></li>';
    }
    if (done && path) {
      var links = modesFor(a.type).map(function (mode) {
        return '<a class="ilp-activity__mode" href="' + modeHref(a.type, a.id, mode) + '">' + modeLinkLabel(a.type, mode) + '</a>';
      }).join('');
      if (links) return '<li class="' + css + '">' + inner + '<span class="ilp-activity__modes">' + links + '</span></li>';
    }
    return '<li class="' + css + '">' + inner + '</li>';
  }
  function activityRows(pkg) {
    return pkg.activities.map(activityRow).join('');
  }
  function sessionRows(pkg) {
    if (!Array.isArray(pkg.sessions) || !pkg.sessions.length) return '';
    return '<div class="ilp-session-list">' + pkg.sessions.map(function (session) {
      var actions = Array.isArray(session.allowedActions) ? session.allowedActions : [];
      var rows = (Array.isArray(session.activities) ? session.activities : []).map(function (a, index) {
        var label = activityLabel(a);
        var done = activityDone(a);
        var learnAllowed = actions.indexOf('START') >= 0 || actions.indexOf('CONTINUE') >= 0;
        if (learnAllowed && !done) return activityRow(a, index);
        // A passed Kanji child stays reviewable while Grammar in the same session is still being learned.
        var childActions = done && learnAllowed ? modesFor(a.type) : actions;
        var links = childActions.filter(function (action) {
          return done && (action === 'REVIEW' || action === 'PRACTICE') && ACTIVITY_PATH[a.type];
        }).map(function (action) {
          return '<a class="ilp-activity__mode" href="' + modeHref(a.type, a.id, action) + '">' +
            modeLinkLabel(a.type, action) + '</a>';
        }).join('');
        return '<li class="ilp-activity' + ((a.state === 'PASSED' || a.state === 'COMPLETED') ? ' is-done' : '') + '">' +
          '<span class="ilp-activity__mark">' + (done ? '✓' : String(index + 1).padStart(2, '0')) +
          '</span><span><strong>' + esc(label) + '</strong><small>' + esc(ACTIVITY_STATE_LABEL[a.state] || a.state || '') + activityDayLabel(a) + '</small></span>' +
          (links ? '<span class="ilp-activity__modes">' + links + '</span>' : '') + '</li>';
      }).join('');
      var counts = Object.keys(session.contentCounts || {}).filter(function (key) { return num(session.contentCounts[key]) > 0; })
        .map(function (key) { return num(session.contentCounts[key]) + ' ' + (key === 'vocabulary' ? 'từ' : key === 'grammar' ? 'mẫu ngữ pháp' : key === 'kanji' ? 'chữ Kanji' : key === 'dialogue' ? 'lượt thoại' : esc(key)); }).join(' · ');
      var words = (Array.isArray(session.referenceVocabulary) ? session.referenceVocabulary : []).map(function (word) {
        return '<li><strong lang="ja">' + esc(word.expression) + '</strong>' + (word.reading ? ' <span lang="ja">' + esc(word.reading) + '</span>' : '') +
          (word.meaning ? ' — ' + esc(word.meaning) : '') + '</li>';
      }).join('');
      return '<section class="ilp-session ilp-session--' + esc(String(session.state || 'LOCKED').toLowerCase()) + '">' +
        '<header><h4>' + esc(SESSION_LABEL[session.sessionType] || session.sessionType || 'Phiên học') + '</h4>' +
        '<span>' + esc(SESSION_STATE_LABEL[session.state] || session.state || '') + '</span></header>' +
        (counts || session.estimatedMinutes ? '<p class="ilp-session__meta">' + esc(counts) +
          (session.estimatedMinutes ? ' · khoảng ' + num(session.estimatedMinutes) + ' phút' : '') + '</p>' : '') +
        (session.blockedReason ? '<p class="ilp-session__blocked">' + esc(session.blockedReason) + '</p>' : '') +
        (rows ? '<ol class="ilp-activities">' + rows + '</ol>' : '') +
        (words ? '<details class="ilp-reference"><summary>Từ tham khảo ngắn (' + (session.referenceVocabulary || []).length + ')</summary><ul>' + words + '</ul></details>' : '') +
        '</section>';
    }).join('') + '</div>';
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
  function activityDayLabel() { return ''; }
  function chapterNumber(pkg) { return positiveInteger(pkg.chapterNumber) || num(pkg.orderIndex); }
  function phaseLabel(pkg) { return pkg.phase === 'WARM_UP' ? 'KHỞI ĐỘNG' : 'MỤC TIÊU'; }
  function chapterKicker(pkg) { return 'CHƯƠNG ' + chapterNumber(pkg) + ' · ' + esc(phaseLabel(pkg)); }
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
  // [JLAS-W0193] FE V2 restyle (spec §10.3) — a completed day collapses to one row instead of
  // repeating the full four-activity card; a locked day shows its reason at reduced contrast
  // instead of an empty activities list users can't act on.
  function packageCard(pkg, inChapter) {
    var status = pkg.status.toLowerCase();
    var kicker = inChapter ? esc(phaseLabel(pkg)) : chapterKicker(pkg);
    var title = inChapter ? partLabel(pkg) : chapterTitle(pkg);
    var badge = String(inChapter ? (positiveInteger(pkg.segmentIndex) || 1) : chapterNumber(pkg)).padStart(2, '0');
    if (pkg.status === 'COMPLETED') {
      return '<article class="ilp-day ilp-day--completed ilp-day--collapsed" data-package-card="' + esc(String(pkg.id)) + '">' +
        '<span class="ilp-day__number">' + badge + '</span>' +
        '<div class="ilp-day__collapsed-title"><span class="ilp-kicker">' + kicker + '</span><h3>' + esc(title) + '</h3></div>' +
        '<span class="ilp-status">' + esc(PACKAGE_STATUS_LABEL[pkg.status] || pkg.status) + '</span>' +
        (sessionRows(pkg) || '<ol class="ilp-activities">' + activityRows(pkg) + '</ol>') + '</article>';
    }
    if (pkg.status === 'LOCKED') {
      return '<article class="ilp-day ilp-day--locked" data-package-card="' + esc(String(pkg.id)) + '">' +
        '<span class="ilp-day__number" aria-hidden="true">🔒</span>' +
        '<div class="ilp-day__collapsed-title"><span class="ilp-kicker">' + kicker + '</span><h3>' + esc(title) + '</h3>' +
        (pkg.lockReason ? '<p class="ilp-lock">' + esc(pkg.lockReason) + '</p>' : '') + '</div>' +
        '<span class="ilp-status">' + esc(PACKAGE_STATUS_LABEL[pkg.status] || pkg.status) + '</span></article>';
    }
    var action = pkg.canStart ? '<button class="ilp-button" type="button" data-start-package="' + pkg.id + '">Bắt đầu học <span aria-hidden="true">→</span></button>' : '';
    return '<article class="ilp-day ilp-day--' + esc(status) + '" data-package-card="' + esc(String(pkg.id)) + '"><header><span class="ilp-day__number">' +
      badge + '</span><div><span class="ilp-kicker">' + kicker + '</span><h3>' + esc(title) +
      '</h3></div><span class="ilp-status">' + esc(PACKAGE_STATUS_LABEL[pkg.status] || pkg.status) + '</span></header><div class="ilp-day__progress"><span style="width:' +
      num(pkg.progressPercent) + '%"></span></div>' + (sessionRows(pkg) || '<ol class="ilp-activities">' + activityRows(pkg) + '</ol>') +
      (pkg.lockReason ? '<p class="ilp-lock">🔒 ' + esc(pkg.lockReason) + '</p>' : '') + action + '</article>';
  }
  /** Consecutive parts of one lesson become one chapter card; a single-part chapter keeps its own card. */
  function chapterCards(packages) {
    var groups = [];
    packages.forEach(function (pkg) {
      var last = groups[groups.length - 1];
      if (last && last.key === chapterKey(pkg)) last.parts.push(pkg);
      else groups.push({ key: chapterKey(pkg), parts: [pkg] });
    });
    return groups.map(function (group) {
      if (group.parts.length === 1) return packageCard(group.parts[0], false);
      var first = group.parts[0];
      var done = group.parts.filter(function (pkg) { return pkg.status === 'COMPLETED'; }).length;
      return '<section class="ilp-chapter" data-chapter="' + esc(group.key) + '"><header class="ilp-chapter__head"><div>' +
        '<span class="ilp-kicker">' + chapterKicker(first) + '</span><h3>' + esc(chapterTitle(first)) + '</h3></div>' +
        '<span class="ilp-status">' + done + '/' + group.parts.length + ' phần đã xong</span></header>' +
        '<div class="ilp-chapter__parts">' + group.parts.map(function (pkg) { return packageCard(pkg, true); }).join('') +
        '</div></section>';
    }).join('');
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
    var groups = { VOCABULARY: [], KANJI: [], GRAMMAR: [] };
    var labels = { VOCABULARY: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' };
    targets.forEach(function (target) {
      if (groups[target.skill]) groups[target.skill].push(target);
    });
    var content = Object.keys(groups).filter(function (skill) { return groups[skill].length; }).map(function (skill) {
      if (skill === 'GRAMMAR') {
        // A wrong Smart Test grammar question with no grammar lesson yet: reviewed from the question itself.
        return '<section class="ilp-review-only__grammar"><h3>' + labels[skill] + '</h3><ul>' + groups[skill].map(function (target) {
          return '<li><strong lang="ja">' + esc(target.target) + '</strong>' +
            (target.sentence ? '<span lang="ja">' + esc(target.sentence) + '</span>' : '') +
            (target.answer ? '<span>Đáp án: <span lang="ja">' + esc(target.answer) + '</span></span>' : '') +
            (target.explanation ? '<small>' + esc(target.explanation) + '</small>' : '') + '</li>';
        }).join('') + '</ul></section>';
      }
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
    phaseFilter = phaseFilter || 'ALL';
    var visiblePackages = phaseFilter === 'ALL' ? v.packages : v.packages.filter(function (pkg) { return pkg.phase === phaseFilter; });
    return '<section class="ilp-hero"><div><span class="ilp-label">MISSION DIRECTIVE // LỘ TRÌNH TỐI ƯU</span><h1>' + esc(v.title) +
      '</h1><p>Học từng phần tương tác, lưu tiến độ thật và tiếp tục đúng nơi bạn dừng lại.</p><div class="ilp-metrics"><span>' +
      esc(v.currentLevel) + ' → ' + esc(v.targetLevel) + '</span>' + (v.minutesPerDay ? '<span>' + v.minutesPerDay + ' phút/ngày</span>' : '') +
      '<span>' + v.totalPackages + ' bài học</span></div></div><div class="ilp-total"><div><span>TIẾN ĐỘ TỔNG</span><strong>' +
      v.progressPercent + '%</strong></div><progress max="100" value="' + v.progressPercent + '">' + v.progressPercent + '%</progress><small>' +
      v.completedPackages + '/' + v.totalPackages + ' bài hoàn thành</small></div></section>' + renderStageProgress(extras.eligibility) + renderMockGate(extras.eligibility) +
      renderSmartTestReviewOnly(v.smartTestReviewOnlyTargets) + '<div class="ilp-layout"><section>' + phaseTabs(phaseFilter) + '<div class="ilp-days">' +
      (visiblePackages.length ? chapterCards(visiblePackages) : '<div class="ilp-empty"><h2>Chưa có bài học trong giai đoạn này</h2><p>Chọn giai đoạn khác để xem nội dung đã được phát hành.</p></div>') +
      '</div></section><aside class="ilp-aside"><span class="ilp-kicker">MỤC TIÊU HIỆN TẠI</span><dl><div><dt>Cấp độ</dt><dd>' + esc(v.currentLevel) + ' → ' + esc(v.targetLevel) +
      '</dd></div>' + (v.minutesPerDay ? '<div><dt>Cường độ</dt><dd>' + v.minutesPerDay + ' phút/ngày</dd></div>' : '') +
      '</dl>' + renderPaceControls(v.minutesPerDay) + '</aside></div>' +
      renderPopup(v.packages.filter(function (p) { return p.justCompleted; })[0]) + renderToast(extras.toast);
  }
  function errorState(code, message) {
    if (code === 'BLOCKED_CONTENT' || /CoverageGap|thiếu học liệu|chưa được phát hành/i.test(message || '')) {
      return '<section class="ilp-state ilp-state--readiness"><span class="ilp-state__icon" aria-hidden="true">文</span>' +
        '<h1>Học liệu đang được hoàn thiện</h1><p>Lộ trình của bạn đã được phân tích, nhưng một số nội dung chưa đủ điều kiện phát hành. Tiến độ Smart Test vẫn được giữ nguyên.</p>' +
        '<div class="ilp-state__actions"><button data-retry type="button">Kiểm tra lại</button>' +
        '<a href="/smart-test">Quay lại Smart Test</a></div></section>';
    }
    return '<section class="ilp-state"><h1>Chưa thể mở lộ trình</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p><button data-retry type="button">Thử lại</button></section>';
  }
  function mount() {
    var node = document.getElementById('interactivePathRoot');
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/path/v2')) return;
    var attemptedBootstrap = false;
    var currentPathData = null;
    var phaseFilter = 'ALL';
    var eligibilityData = null;
    var toastMessage = '';
    var toastTimer = null;
    var assessmentAttemptId = resolveAssessmentAttemptId(window.location.search, window.localStorage);
    async function bootstrapPath() {
      var request = pathCreationRequest(assessmentAttemptId);
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
    function viewExtras() { return { eligibility: eligibilityData, toast: toastMessage }; }
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
          await bootstrapPath();
          return load();
        }
        if (response.ok && body.data) {
          currentPathData = body.data;
          eligibilityData = await loadEligibility();
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
        var studyHref = startedPackageStudyHref(body.data);
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
    renderMockGate: renderMockGate, renderPaceControls: renderPaceControls, renderPaceDialog: renderPaceDialog,
    paceRequestBody: paceRequestBody, renderToast: renderToast, nextFocusIndex: nextFocusIndex, popupModel: popupModel, renderPopup: renderPopup, errorState: errorState,
    afterRender: afterRender, resolveAssessmentAttemptId: resolveAssessmentAttemptId, pathCreationRequest: pathCreationRequest,
    startedPackageStudyHref: startedPackageStudyHref };
});
