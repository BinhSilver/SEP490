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
  var ACTIVITY_LABEL = { VOCABULARY: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', DIALOGUE: 'Hội thoại' };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
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
    var ACTIVITY_PATH = { VOCABULARY: '/learning/v2/vocabulary', KANJI: '/learning/v2/kanji', GRAMMAR: '/learning/v2/grammar', DIALOGUE: '/learning/v2/dialogue' };
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
      '</h3></div><span class="ilp-status">' + esc(pkg.status) + '</span></header><div class="ilp-day__progress"><span style="width:' +
      num(pkg.progressPercent) + '%"></span></div><ol class="ilp-activities">' + activityRows(pkg) + '</ol>' +
      (pkg.lockReason ? '<p class="ilp-lock">🔒 ' + esc(pkg.lockReason) + '</p>' : '') + action + '</article>';
  }
  function render(data) {
    var v = viewModel(data);
    return '<section class="ilp-hero"><div><span class="ilp-label">MISSION DIRECTIVE // LỘ TRÌNH TỐI ƯU</span><h1>' + esc(v.title) +
      '</h1><p>Học từng phần tương tác, lưu tiến độ thật và tiếp tục đúng nơi bạn dừng lại.</p><div class="ilp-metrics"><span>' +
      esc(v.currentLevel) + ' → ' + esc(v.targetLevel) + '</span>' + (v.minutesPerDay ? '<span>' + v.minutesPerDay + ' phút/ngày</span>' : '') +
      '<span>' + v.totalPackages + ' ngày học</span></div></div><div class="ilp-total"><div><span>TIẾN ĐỘ TỔNG</span><strong>' +
      v.progressPercent + '%</strong></div><progress max="100" value="' + v.progressPercent + '">' + v.progressPercent + '%</progress><small>' +
      v.completedPackages + '/' + v.totalPackages + ' ngày hoàn thành</small></div></section><div class="ilp-layout"><section><div class="ilp-phase-tabs" aria-label="Giai đoạn"><span class="is-active">Tất cả</span><span>Khởi động</span><span>Mục tiêu</span></div><div class="ilp-days">' +
      (v.packages.length ? v.packages.map(packageCard).join('') : '<div class="ilp-empty"><h2>Chưa có package sẵn sàng</h2><p>Hệ thống chỉ phát hành ngày học khi đủ dữ liệu đã duyệt.</p></div>') +
      '</div></section><aside class="ilp-aside"><span class="ilp-kicker">MỤC TIÊU HIỆN TẠI</span><dl><div><dt>Cấp độ</dt><dd>' + esc(v.currentLevel) + ' → ' + esc(v.targetLevel) +
      '</dd></div>' + (v.minutesPerDay ? '<div><dt>Cường độ</dt><dd>' + v.minutesPerDay + ' phút/ngày</dd></div>' : '') +
      '</dl><p class="ilp-note">J-LAS chỉ mở nội dung đã đủ dữ liệu và lưu trạng thái trên máy chủ.</p></aside></div>' +
      renderPopup(v.packages.filter(function (p) { return p.justCompleted; })[0]);
  }
  function error(message) { return '<section class="ilp-state"><h1>Chưa thể mở lộ trình</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p><button data-retry type="button">Thử lại</button></section>'; }
  function mount() {
    var node = document.getElementById('interactivePathRoot');
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/path/v2')) return;
    async function load() {
      node.setAttribute('aria-busy', 'true');
      try { var response = await fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() }); var body = await response.json();
        node.innerHTML = response.ok && body.data ? render(body.data) : error(body.message); afterRender(node); }
      catch (e) { node.innerHTML = error('Không thể kết nối tới máy chủ.'); }
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
        if (!response.ok) { var body = await response.json(); node.innerHTML = error(body.message); return; } await load(); }
      catch (e) { node.innerHTML = error('Không thể bắt đầu ngày học.'); }
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
  return { viewModel: viewModel, render: render, mount: mount, popupModel: popupModel, renderPopup: renderPopup, afterRender: afterRender };
});
