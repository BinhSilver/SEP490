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
  // [JLAS-W0108] Task 11 - REVIEW exists only where the server exposes a read-only review (vocabulary, kanji,
  // foundation); PRACTICE replay exists for card, grammar and Reading/Listening activities (their replays carry the frozen source of the replayed batch). Dialogue has no micro-check engine, so it has neither.
  var REVIEW_TYPES = { VOCABULARY: true, KANJI: true, FOUNDATION: true, READING: true, LISTENING: true };
  var PRACTICE_TYPES = { VOCABULARY: true, KANJI: true, FOUNDATION: true, GRAMMAR: true, READING: true, LISTENING: true };
  var MODE_LINK_LABEL = { REVIEW: 'Xem lại', PRACTICE: 'Ôn lại' };
  var STAGE_LEVEL_LABEL = { FOUNDATION: 'Bảng chữ cái' };
  var BLOCK_REASON = {
    BLOCKED_CONTENT: 'Học liệu chưa sẵn sàng cho chặng này nên chưa thể mở bài thi thử.',
    PRIOR_STAGE_INCOMPLETE: 'Hoàn thành 100% các chặng trước để mở bài thi thử.',
    TARGET_STAGE_BELOW_THRESHOLD: 'Hoàn thành ít nhất {percent}% chặng mục tiêu để mở bài thi thử.'
  };
  var PACE_LABEL = { LIGHTER: 'Nhẹ hơn', FASTER: 'Nhanh hơn' };
  var PACKAGE_STATUS_LABEL = { AVAILABLE: 'Sẵn sàng', IN_PROGRESS: 'Đang học', COMPLETED: 'Đã hoàn thành', LOCKED: 'Chưa mở' };

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
      pkg.activityLabels = pkg.activities.map(function (a) { return ACTIVITY_LABEL[a.type] || a.type; });
      pkg.canStart = pkg.status === 'AVAILABLE';
    });
    return {
      title: data.title || 'Lộ trình cá nhân', goal: data.goal || 'JLPT', currentLevel: data.currentLevel || '—',
      targetLevel: data.targetLevel || '—', minutesPerDay: num(data.minutesPerDay), progressPercent: num(data.progressPercent),
      completedPackages: num(data.completedPackages), totalPackages: num(data.totalPackages), packages: packages
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
  function activityRow(a, index) {
    var done = a.state === 'PASSED' || a.state === 'COMPLETED';
    var active = a.state === 'STUDYING' || a.state === 'CHECK_READY' || a.state === 'COOLDOWN';
    var label = ACTIVITY_LABEL[a.type] || a.type;
    var state = done ? 'Đã hoàn thành' : active ? 'Đang học' : 'Chưa mở';
    var inner = '<span class="ilp-activity__mark">' + (done ? '✓' : String(index + 1).padStart(2, '0')) + '</span>' +
      '<span><strong>' + esc(label) + '</strong><small>' + esc(state) + '</small></span>';
    var css = 'ilp-activity' + (done ? ' is-done' : active ? ' is-active' : '');
    // [JLAS-W0193] RC Task 5 — Reading/Listening routes keyed by the server-reported activity type, same as
    // every other entry here: the roadmap never guesses which player to open, it only looks up `a.type`.
    var path = ACTIVITY_PATH[a.type];
    if (path && !done && a.state !== 'LOCKED') {
      return '<li class="' + css + '"><a class="ilp-activity__link" href="' + path + '?activity=' +
        encodeURIComponent(a.id) + '">' + inner + '</a></li>';
    }
    if (done && path) {
      var links = modesFor(a.type).map(function (mode) {
        return '<a class="ilp-activity__mode" href="' + modeHref(a.type, a.id, mode) + '">' + MODE_LINK_LABEL[mode] + '</a>';
      }).join('');
      if (links) return '<li class="' + css + '">' + inner + '<span class="ilp-activity__modes">' + links + '</span></li>';
    }
    return '<li class="' + css + '">' + inner + '</li>';
  }
  function activityRows(pkg) {
    return pkg.activities.map(activityRow).join('');
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
        '<p class="ilp-popup__note">Chỉ lịch các ngày chưa học thay đổi. Nội dung, tiến độ và mục tiêu được giữ nguyên.</p>'
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
  function dayKicker(pkg) {
    return 'DAY ' + String(pkg.dayNumber).padStart(2, '0') + ' · ' + esc(pkg.phase === 'WARM_UP' ? 'KHỞI ĐỘNG' : 'MỤC TIÊU');
  }
  // [JLAS-W0193] FE V2 restyle (spec §10.3) — a completed day collapses to one row instead of
  // repeating the full four-activity card; a locked day shows its reason at reduced contrast
  // instead of an empty activities list users can't act on.
  function packageCard(pkg) {
    var status = pkg.status.toLowerCase();
    if (pkg.status === 'COMPLETED') {
      return '<article class="ilp-day ilp-day--completed ilp-day--collapsed" data-package-card="' + esc(String(pkg.id)) + '">' +
        '<span class="ilp-day__number">' + String(pkg.dayNumber).padStart(2, '0') + '</span>' +
        '<div class="ilp-day__collapsed-title"><span class="ilp-kicker">' + dayKicker(pkg) + '</span><h3>' + esc(pkg.title) + '</h3></div>' +
        '<span class="ilp-status">' + esc(PACKAGE_STATUS_LABEL[pkg.status] || pkg.status) + '</span></article>';
    }
    if (pkg.status === 'LOCKED') {
      return '<article class="ilp-day ilp-day--locked" data-package-card="' + esc(String(pkg.id)) + '">' +
        '<span class="ilp-day__number" aria-hidden="true">🔒</span>' +
        '<div class="ilp-day__collapsed-title"><span class="ilp-kicker">' + dayKicker(pkg) + '</span><h3>' + esc(pkg.title) + '</h3>' +
        (pkg.lockReason ? '<p class="ilp-lock">' + esc(pkg.lockReason) + '</p>' : '') + '</div>' +
        '<span class="ilp-status">' + esc(PACKAGE_STATUS_LABEL[pkg.status] || pkg.status) + '</span></article>';
    }
    var action = pkg.canStart ? '<button class="ilp-button" type="button" data-start-package="' + pkg.id + '">Bắt đầu học <span aria-hidden="true">→</span></button>' : '';
    return '<article class="ilp-day ilp-day--' + esc(status) + '" data-package-card="' + esc(String(pkg.id)) + '"><header><span class="ilp-day__number">' +
      String(pkg.dayNumber).padStart(2, '0') + '</span><div><span class="ilp-kicker">' + dayKicker(pkg) + '</span><h3>' + esc(pkg.title) +
      '</h3></div><span class="ilp-status">' + esc(PACKAGE_STATUS_LABEL[pkg.status] || pkg.status) + '</span></header><div class="ilp-day__progress"><span style="width:' +
      num(pkg.progressPercent) + '%"></span></div><ol class="ilp-activities">' + activityRows(pkg) + '</ol>' +
      (pkg.lockReason ? '<p class="ilp-lock">🔒 ' + esc(pkg.lockReason) + '</p>' : '') + action + '</article>';
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
  function render(data, phaseFilter, extras) {
    extras = extras || {};
    var v = viewModel(data);
    phaseFilter = phaseFilter || 'ALL';
    var visiblePackages = phaseFilter === 'ALL' ? v.packages : v.packages.filter(function (pkg) { return pkg.phase === phaseFilter; });
    return '<section class="ilp-hero"><div><span class="ilp-label">MISSION DIRECTIVE // LỘ TRÌNH TỐI ƯU</span><h1>' + esc(v.title) +
      '</h1><p>Học từng phần tương tác, lưu tiến độ thật và tiếp tục đúng nơi bạn dừng lại.</p><div class="ilp-metrics"><span>' +
      esc(v.currentLevel) + ' → ' + esc(v.targetLevel) + '</span>' + (v.minutesPerDay ? '<span>' + v.minutesPerDay + ' phút/ngày</span>' : '') +
      '<span>' + v.totalPackages + ' ngày học</span></div></div><div class="ilp-total"><div><span>TIẾN ĐỘ TỔNG</span><strong>' +
      v.progressPercent + '%</strong></div><progress max="100" value="' + v.progressPercent + '">' + v.progressPercent + '%</progress><small>' +
      v.completedPackages + '/' + v.totalPackages + ' ngày hoàn thành</small></div></section>' + renderStageProgress(extras.eligibility) + renderMockGate(extras.eligibility) +
      '<div class="ilp-layout"><section>' + phaseTabs(phaseFilter) + '<div class="ilp-days">' +
      (visiblePackages.length ? visiblePackages.map(packageCard).join('') : '<div class="ilp-empty"><h2>Chưa có ngày học trong giai đoạn này</h2><p>Chọn giai đoạn khác để xem nội dung đã được phát hành.</p></div>') +
      '</div></section><aside class="ilp-aside"><span class="ilp-kicker">MỤC TIÊU HIỆN TẠI</span><dl><div><dt>Cấp độ</dt><dd>' + esc(v.currentLevel) + ' → ' + esc(v.targetLevel) +
      '</dd></div>' + (v.minutesPerDay ? '<div><dt>Cường độ</dt><dd>' + v.minutesPerDay + ' phút/ngày</dd></div>' : '') +
      '</dl>' + renderPaceControls(v.minutesPerDay) + '<p class="ilp-note">J-LAS chỉ mở nội dung đã đủ dữ liệu và lưu trạng thái trên máy chủ.</p></aside></div>' +
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
          if (!startResponse.ok) { advanceButton.disabled = false; return; }
          closePopup();
          await load();
        } catch (e) { advanceButton.disabled = false; }
        return;
      }

      var button = event.target.closest('[data-start-package]'); if (!button) return;
      button.disabled = true;
      try { var response = await fetch('/api/learning/v2/packages/' + encodeURIComponent(button.dataset.startPackage) + '/start', { method: 'POST', headers: window.JlasAuth.authHeaders() });
        if (!response.ok) { var body = await response.json(); node.innerHTML = errorState(body.code, body.message); return; } await load(); }
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
    afterRender: afterRender, resolveAssessmentAttemptId: resolveAssessmentAttemptId, pathCreationRequest: pathCreationRequest };
});
