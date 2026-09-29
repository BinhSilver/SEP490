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
    READING: 'Đọc hiểu', LISTENING: 'Nghe hiểu' };
  var PACKAGE_STATUS_LABEL = { AVAILABLE: 'Sẵn sàng', IN_PROGRESS: 'Đang học', COMPLETED: 'Đã hoàn thành', LOCKED: 'Chưa mở' };

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
    var ACTIVITY_PATH = { VOCABULARY: '/learning/v2/vocabulary', KANJI: '/learning/v2/kanji', GRAMMAR: '/learning/v2/grammar', DIALOGUE: '/learning/v2/dialogue',
      READING: '/learning/v2/reading', LISTENING: '/learning/v2/listening' };
    var path = ACTIVITY_PATH[a.type];
    if (path && !done && a.state !== 'LOCKED') {
      return '<li class="' + css + '"><a class="ilp-activity__link" href="' + path + '?activity=' +
        encodeURIComponent(a.id) + '">' + inner + '</a></li>';
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
  function renderPopup(pkg) {
    var model = popupModel(pkg);
    if (!model) return '';
    var scoreHtml = model.scorePercent == null ? '' :
      '<p class="ilp-popup__score">Điểm phần này: <strong>' + model.scorePercent + '%</strong></p>';
    var advanceHtml = model.isLast
      ? '<button type="button" class="ilp-popup__primary" data-popup-summary>Xem tổng kết chặng</button>'
      : '<button type="button" class="ilp-popup__primary"' +
        (model.nextPackageId ? ' data-popup-advance="' + esc(String(model.nextPackageId)) + '"' : ' disabled') +
        '>Học vượt</button>';
    return '<div class="ilp-popup-backdrop" data-popup-backdrop>' +
      '<section class="ilp-popup" role="dialog" aria-modal="true" aria-labelledby="ilpPopupTitle" tabindex="-1" data-popup="' +
      esc(String(model.packageId)) + '"><h2 id="ilpPopupTitle">Bạn đã hoàn thành phần bài học hôm nay</h2>' +
      '<p>Bạn muốn tiếp tục như thế nào?</p>' + scoreHtml +
      '<div class="ilp-popup__actions">' + advanceHtml +
      '<button type="button" class="ilp-popup__secondary" data-popup-return>Trở về lộ trình</button>' +
      '<button type="button" class="ilp-popup__quiet" data-popup-rest>Nghỉ ngơi</button>' +
      '</div></section></div>';
  }
  function packageCard(pkg) {
    var action = pkg.canStart ? '<button class="ilp-button" type="button" data-start-package="' + pkg.id + '">Bắt đầu học <span aria-hidden="true">→</span></button>' : '';
    return '<article class="ilp-day ilp-day--' + esc(pkg.status.toLowerCase()) + '" data-package-card="' + esc(String(pkg.id)) + '"><header><span class="ilp-day__number">' +
      String(pkg.dayNumber).padStart(2, '0') + '</span><div><span class="ilp-kicker">DAY ' + String(pkg.dayNumber).padStart(2, '0') +
      ' · ' + esc(pkg.phase === 'WARM_UP' ? 'KHỞI ĐỘNG' : 'MỤC TIÊU') + '</span><h3>' + esc(pkg.title) +
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
  function render(data, phaseFilter) {
    var v = viewModel(data);
    phaseFilter = phaseFilter || 'ALL';
    var visiblePackages = phaseFilter === 'ALL' ? v.packages : v.packages.filter(function (pkg) { return pkg.phase === phaseFilter; });
    return '<section class="ilp-hero"><div><span class="ilp-label">MISSION DIRECTIVE // LỘ TRÌNH TỐI ƯU</span><h1>' + esc(v.title) +
      '</h1><p>Học từng phần tương tác, lưu tiến độ thật và tiếp tục đúng nơi bạn dừng lại.</p><div class="ilp-metrics"><span>' +
      esc(v.currentLevel) + ' → ' + esc(v.targetLevel) + '</span>' + (v.minutesPerDay ? '<span>' + v.minutesPerDay + ' phút/ngày</span>' : '') +
      '<span>' + v.totalPackages + ' ngày học</span></div></div><div class="ilp-total"><div><span>TIẾN ĐỘ TỔNG</span><strong>' +
      v.progressPercent + '%</strong></div><progress max="100" value="' + v.progressPercent + '">' + v.progressPercent + '%</progress><small>' +
      v.completedPackages + '/' + v.totalPackages + ' ngày hoàn thành</small></div></section><div class="ilp-layout"><section>' + phaseTabs(phaseFilter) + '<div class="ilp-days">' +
      (visiblePackages.length ? visiblePackages.map(packageCard).join('') : '<div class="ilp-empty"><h2>Chưa có ngày học trong giai đoạn này</h2><p>Chọn giai đoạn khác để xem nội dung đã được phát hành.</p></div>') +
      '</div></section><aside class="ilp-aside"><span class="ilp-kicker">MỤC TIÊU HIỆN TẠI</span><dl><div><dt>Cấp độ</dt><dd>' + esc(v.currentLevel) + ' → ' + esc(v.targetLevel) +
      '</dd></div>' + (v.minutesPerDay ? '<div><dt>Cường độ</dt><dd>' + v.minutesPerDay + ' phút/ngày</dd></div>' : '') +
      '</dl><p class="ilp-note">J-LAS chỉ mở nội dung đã đủ dữ liệu và lưu trạng thái trên máy chủ.</p></aside></div>' +
      renderPopup(v.packages.filter(function (p) { return p.justCompleted; })[0]);
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
    async function load() {
      node.setAttribute('aria-busy', 'true');
      try { var response = await fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() }); var body = await response.json();
        if ((response.status === 404 || body.code === 'NOT_FOUND') && !attemptedBootstrap) {
          attemptedBootstrap = true;
          await bootstrapPath();
          return load();
        }
        if (response.ok && body.data) { currentPathData = body.data; node.innerHTML = render(body.data, phaseFilter); }
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
    function trapFocus(event) {
      var popup = node.querySelector('[data-popup]'); if (!popup || event.key !== 'Tab') return;
      var focusable = popup.querySelectorAll('button:not([disabled])');
      if (!focusable.length) return;
      var first = focusable[0], last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
    node.addEventListener('click', async function (event) {
      if (event.target.closest('[data-retry]')) return load();
      var phaseButton = event.target.closest('[data-phase-filter]');
      if (phaseButton && currentPathData) {
        phaseFilter = phaseButton.dataset.phaseFilter;
        node.innerHTML = render(currentPathData, phaseFilter);
        afterRender(node);
        return;
      }

      if (event.target.closest('[data-popup-return]') || event.target === node.querySelector('[data-popup-backdrop]')) {
        return highlightPackage(closePopup());
      }
      if (event.target.closest('[data-popup-rest]')) return closePopup();
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
  return { viewModel: viewModel, render: render, mount: mount, popupModel: popupModel, renderPopup: renderPopup, errorState: errorState,
    afterRender: afterRender, resolveAssessmentAttemptId: resolveAssessmentAttemptId, pathCreationRequest: pathCreationRequest };
});
