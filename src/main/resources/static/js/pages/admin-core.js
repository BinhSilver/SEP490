/* Shared admin workspace helpers (JLAS-W0250). Sections register themselves with Admin.section(). */
(function (global) {
  'use strict';

  // Roles: MODERATOR moderates; ADMIN (System Admin) runs the system and also moderates,
  // including user sanctions. CONTENT_CREATOR works only in the content tools.
  var MODERATION = ['MODERATOR', 'ADMIN'];
  var ADMINS = ['ADMIN']; // may sanction users
  var SYSTEM = ['ADMIN'];

  /**
   * Sidebar registry. A new management area = a new group or item here plus a section script.
   * `roles` only hides links; every API checks permissions again on the server.
   */
  var MODULES = [
    { title: null, items: [
      { key: 'overview', label: 'Tổng quan', icon: 'fa-gauge-high', href: '/admin', roles: ['MODERATOR'] },
      { key: 'system-dashboard', label: 'Tổng quan', icon: 'fa-gauge-high', href: '/admin/system/dashboard', roles: SYSTEM }
    ] },
    { title: 'Quản lý nội dung', items: [
      { key: 'content-textbooks', label: 'Giáo trình và bài học', icon: 'fa-book-open', href: '/textbooks/manage', roles: ['CONTENT_CREATOR'] },
      { key: 'content-jlpt', label: 'Đề thi JLPT', icon: 'fa-file-pen', href: '/admin/jlpt', roles: ['CONTENT_CREATOR'] },
      { key: 'content-kanji-map', label: 'Lược đồ Kanji', icon: 'fa-diagram-project', href: '/admin/kanji-map', roles: ['CONTENT_CREATOR'] }
    ] },
    { title: 'Quản trị hệ thống', items: [
      { key: 'system-users', label: 'Người dùng', icon: 'fa-users', href: '/admin/system/users', roles: SYSTEM },
      { key: 'system-plans', label: 'Gói Premium', icon: 'fa-crown', href: '/admin/system/plans', roles: SYSTEM },
      { key: 'system-coupons', label: 'Mã giảm giá', icon: 'fa-tags', href: '/admin/system/coupons', roles: SYSTEM },
      { key: 'system-credit-packages', label: 'Gói CP', icon: 'fa-coins', href: '/admin/system/credit-packages', roles: SYSTEM },
      { key: 'system-payments', label: 'Thanh toán', icon: 'fa-receipt', href: '/admin/system/payments', roles: SYSTEM },
      { key: 'system-audit', label: 'Nhật ký quản trị', icon: 'fa-clipboard-list', href: '/admin/system/audit', roles: SYSTEM }
    ] },
    { title: 'Trò chơi', items: [
      { key: 'game-skins', label: 'Trang phục game', icon: 'fa-shirt', href: '/admin/game-skins', roles: ADMINS },
      { key: 'avatar-frames', label: 'Khung avatar', icon: 'fa-crown', href: '/admin/avatar-frames', roles: ADMINS },
      { key: 'game-effects', label: 'Xem hiệu ứng game', icon: 'fa-gamepad', href: '/admin/game-effects', roles: MODERATION }
    ] },
    { title: 'Kiểm duyệt', items: [
      { key: 'reports', label: 'Báo cáo nội dung', icon: 'fa-flag', href: '/admin/moderation/reports', roles: MODERATION, badge: 'pendingReports' },
      { key: 'queue', label: 'Hàng đợi AI', icon: 'fa-robot', href: '/admin/moderation/queue', roles: MODERATION, badge: 'pendingQueue' },
      { key: 'appeals', label: 'Kháng nghị', icon: 'fa-scale-balanced', href: '/admin/moderation/appeals', roles: MODERATION, badge: 'pendingAppeals' },
      { key: 'dictionary', label: 'Đóng góp từ điển', icon: 'fa-book', href: '/admin/moderation/dictionary', roles: MODERATION, badge: 'pendingContributions' },
      { key: 'denylist', label: 'Từ khoá cấm', icon: 'fa-ban', href: '/admin/moderation/denylist', roles: MODERATION },
      { key: 'sanctions', label: 'Chế tài người dùng', icon: 'fa-user-lock', href: '/admin/moderation/sanctions', roles: ADMINS },
      { key: 'audit', label: 'Nhật ký kiểm duyệt', icon: 'fa-clock-rotate-left', href: '/admin/moderation/audit', roles: MODERATION }
    ] }
  ];

  var ROLE_LABELS = { MODERATOR: 'Kiểm duyệt viên', ADMIN: 'Quản trị viên', CONTENT_CREATOR: 'Người biên soạn' };

  var TYPE_LABELS = {
    USER_AVATAR: 'Ảnh đại diện', FLASHCARD_SET: 'Bộ thẻ', FLASHCARD_COMMENT: 'Bình luận bộ thẻ', VIDEO: 'Video Shadowing',
    VIDEO_COMMENT: 'Bình luận video', WORD_CONTRIBUTION: 'Đóng góp từ vựng', LESSON: 'Bài học',
    SCENARIO: 'Kịch bản', TEXTBOOK: 'Giáo trình', USER: 'Người dùng', USER_SANCTION: 'Chế tài',
    APPEAL: 'Kháng nghị', DENYLIST_TERM: 'Từ khoá cấm', PREMIUM_PLAN: 'Gói Premium',
    COUPON: 'Mã giảm giá', CREDIT_PACKAGE: 'Gói CP'
  };

  // ── Auth & API ─────────────────────────────────────────────────────────
  function user() {
    return global.JlasAuth && typeof global.JlasAuth.getUser === 'function' ? global.JlasAuth.getUser() : null;
  }

  function role() {
    var u = global.AdminShell ? global.AdminShell.user : user();
    return u && u.role ? String(u.role).toUpperCase() : '';
  }

  function can(roles) { return roles.indexOf(role()) >= 0; }

  function headers() {
    var h = global.JlasAuth && typeof global.JlasAuth.authHeaders === 'function' ? global.JlasAuth.authHeaders() : {};
    h['Content-Type'] = 'application/json';
    return h;
  }

  async function request(method, url, body) {
    var response = await fetch(url, {
      method: method, headers: headers(), body: body === undefined ? undefined : JSON.stringify(body)
    });
    var text = await response.text();
    var data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = { message: text }; }
    if (response.status === 401) {
      global.location.replace('/login?redirect=' + encodeURIComponent(global.location.pathname + global.location.search));
    }
    if (!response.ok) {
      var errors = data && data.errors;
      var first = errors && Object.keys(errors).length ? errors[Object.keys(errors)[0]] : null;
      var error = new Error(response.status === 403 ? 'Bạn không có quyền thực hiện thao tác này.'
        : first || (data && data.message) || 'Có lỗi xảy ra, vui lòng thử lại.');
      error.status = response.status;
      throw error;
    }
    return data && typeof data === 'object' && 'success' in data ? data.data : data;
  }

  // ── Formatting ─────────────────────────────────────────────────────────
  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function date(value) {
    if (!value) return '—';
    var d = new Date(value);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('vi-VN') + ' ' + d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
  }

  function typeLabel(type) { return TYPE_LABELS[type] || type || '—'; }

  /**
   * Text with flagged terms wrapped in <mark>. Every slice is escaped separately, so user
   * content can never inject HTML. Spans come from the server and never overlap.
   */
  function highlighted(text, spans) {
    var value = String(text == null ? '' : text);
    var list = (spans || []).filter(function (s) { return s.start >= 0 && s.end <= value.length && s.start < s.end; });
    var html = '';
    var cursor = 0;
    list.forEach(function (s) {
      if (s.start < cursor) return;
      var label = (s.action === 'BLOCK' ? 'Từ bị chặn: ' : 'Từ cần xem lại: ') + s.term;
      html += esc(value.slice(cursor, s.start)) +
        '<mark class="adm-mark adm-mark--' + (s.action === 'BLOCK' ? 'block' : 'review') + '" title="' + esc(label) + '">' +
        esc(value.slice(s.start, s.end)) + '</mark>';
      cursor = s.end;
    });
    return html + esc(value.slice(cursor));
  }

  /** Distinct flagged terms as chips, shown next to the flag reason. */
  function flaggedTerms(spans) {
    var seen = {};
    var chips = (spans || []).filter(function (s) {
      var key = s.action + '|' + s.term;
      if (seen[key]) return false;
      seen[key] = true;
      return true;
    }).map(function (s) {
      return '<span class="adm-chip adm-chip--' + (s.action === 'BLOCK' ? 'block' : 'review') + ' jp">' + esc(s.term) + '</span>';
    });
    return chips.length ? '<div class="adm-flagged"><span class="adm-muted">Từ bị bắt:</span>' + chips.join('') + '</div>' : '';
  }

  /** Only http(s) links are rendered, so a stored javascript: URL can never become clickable. */
  function safeUrl(url) {
    return /^https?:\/\//i.test(String(url || '')) || /^\/(?!\/)/.test(String(url || '')) ? url : null;
  }

  // ── Toast ──────────────────────────────────────────────────────────────
  function toast(message, type) {
    var host = document.getElementById('admToastHost');
    if (!host) {
      host = document.createElement('div');
      host.id = 'admToastHost';
      host.className = 'adm-toast-host';
      host.setAttribute('aria-live', 'polite');
      document.body.appendChild(host);
    }
    var icons = { success: 'fa-circle-check', error: 'fa-circle-exclamation', warning: 'fa-triangle-exclamation' };
    var item = document.createElement('div');
    item.className = 'adm-toast adm-toast--' + (type || 'info');
    item.setAttribute('role', type === 'error' ? 'alert' : 'status');
    item.innerHTML = '<i class="fa-solid ' + (icons[type] || 'fa-circle-info') + '" aria-hidden="true"></i><span>' + esc(message) + '</span>';
    host.appendChild(item);
    while (host.children.length > 3) host.firstElementChild.remove();
    setTimeout(function () { item.remove(); }, 4500);
  }

  // ── Decision dialog: every moderator decision needs a reason ───────────
  /**
   * Opens a modal with a required reason. `extraHtml` may add fields; `collect(dialog)` reads them.
   * Resolves to { reason, extra } or null when cancelled.
   */
  function decide(options) {
    return new Promise(function (resolve) {
      var last = document.activeElement;
      var modal = document.createElement('div');
      modal.className = 'adm-modal';
      modal.setAttribute('role', 'dialog');
      modal.setAttribute('aria-modal', 'true');
      modal.setAttribute('aria-labelledby', 'admDecideTitle');
      var max = options.maxLength || 1000;
      modal.innerHTML =
        '<form class="adm-modal__dialog" novalidate>' +
          '<h2 class="adm-modal__title" id="admDecideTitle">' + esc(options.title) + '</h2>' +
          (options.message ? '<p class="adm-modal__text">' + options.message + '</p>' : '') +
          (options.extraHtml || '') +
          (options.noReason ? '' :
          '<div class="field">' +
            '<label class="field__label" for="admReason">Lý do <span aria-hidden="true">*</span></label>' +
            '<textarea class="textarea" id="admReason" rows="3" maxlength="' + max + '" placeholder="' +
              esc(options.placeholder || 'Ghi rõ lý do để lưu vào nhật ký') + '"></textarea>' +
            '<div class="adm-field-foot">' +
              (options.userVisible
                ? '<span class="adm-hint"><i class="fa-solid fa-bell" aria-hidden="true"></i> Người dùng sẽ nhận thông báo kèm lý do này</span>'
                : '<span class="adm-hint">Chỉ lưu vào nhật ký, người dùng không thấy</span>') +
              '<span class="adm-muted" data-count>0/' + max + '</span></div>' +
          '</div>') +
          '<p class="adm-error" data-error aria-live="polite"></p>' +
          '<div class="adm-modal__actions">' +
            '<button type="button" class="btn btn--secondary" data-cancel>Huỷ</button>' +
            '<button type="submit" class="btn ' + (options.danger ? 'adm-btn--danger' : 'btn--primary') + '">' +
              esc(options.confirmText || 'Xác nhận') + '</button>' +
          '</div>' +
        '</form>';
      document.body.appendChild(modal);
      document.body.classList.add('adm-modal-open');
      var form = modal.querySelector('form');
      var reason = modal.querySelector('#admReason');
      var errorEl = modal.querySelector('[data-error]');
      var count = modal.querySelector('[data-count]');

      function close(value) {
        modal.remove();
        document.body.classList.remove('adm-modal-open');
        document.removeEventListener('keydown', onKey);
        if (last && last.focus) last.focus();
        resolve(value);
      }
      function onKey(event) { if (event.key === 'Escape') close(null); }

      if (reason) {
        reason.addEventListener('input', function () {
          count.textContent = reason.value.length + '/' + max;
          errorEl.textContent = '';
        });
      }
      modal.querySelector('[data-cancel]').addEventListener('click', function () { close(null); });
      modal.addEventListener('click', function (event) { if (event.target === modal) close(null); });
      document.addEventListener('keydown', onKey);
      form.addEventListener('submit', function (event) {
        event.preventDefault();
        var value = reason ? reason.value.trim() : '';
        if (reason && !value) {
          errorEl.textContent = 'Vui lòng nhập lý do.';
          reason.focus();
          return;
        }
        var extra = options.collect ? options.collect(modal) : null;
        if (extra && extra.error) {
          errorEl.textContent = extra.error;
          return;
        }
        close({ reason: value, extra: extra });
      });
      var focus = modal.querySelector('[data-autofocus]') || reason || modal.querySelector('button[type="submit"]');
      setTimeout(function () { focus.focus(); }, 20);
    });
  }

  /** Blocks non-digit characters while typing (project rule: validate at input time). */
  function digitsOnly(input) {
    input.addEventListener('beforeinput', function (event) {
      if (event.data && /\D/.test(event.data)) event.preventDefault();
    });
    input.addEventListener('input', function () {
      var clean = input.value.replace(/\D/g, '');
      if (clean !== input.value) input.value = clean;
    });
  }

  function empty(icon, title, text) {
    return '<div class="adm-empty"><i class="fa-solid ' + icon + '" aria-hidden="true"></i><strong>' + esc(title) +
      '</strong>' + (text ? '<span>' + esc(text) + '</span>' : '') + '</div>';
  }

  function loading() {
    return '<div class="adm-empty"><span class="adm-spinner" aria-hidden="true"></span><span>Đang tải…</span></div>';
  }

  // Durations allowed by UserSanctionService: 3, 7, 30 days; BAN may also be permanent.
  var SANCTION_CHOICES = [
    ['MUTE:3', 'Cấm bình luận 3 ngày'], ['MUTE:7', 'Cấm bình luận 7 ngày'], ['MUTE:30', 'Cấm bình luận 30 ngày'],
    ['BAN:3', 'Khoá tài khoản 3 ngày'], ['BAN:7', 'Khoá tài khoản 7 ngày'], ['BAN:30', 'Khoá tài khoản 30 ngày'],
    ['BAN:', 'Khoá tài khoản vĩnh viễn']
  ];

  function sanctionOptions(includeNone) {
    return (includeNone ? '<option value="">Không áp chế tài</option>' : '') +
      SANCTION_CHOICES.map(function (c) { return '<option value="' + c[0] + '">' + c[1] + '</option>'; }).join('');
  }

  function parseSanction(value) {
    if (!value) return null;
    var parts = value.split(':');
    return { type: parts[0], durationDays: parts[1] ? Number(parts[1]) : null };
  }

  function sanctionLabel(type, endsAt) {
    var name = type === 'BAN' ? 'Khoá tài khoản' : 'Cấm bình luận';
    return name + (endsAt ? ' đến ' + date(endsAt) : ' vĩnh viễn');
  }

  var sections = {};

  global.Admin = {
    MODULES: MODULES,
    MODERATION: MODERATION,
    ADMINS: ADMINS,
    SYSTEM: SYSTEM,
    ROLE_LABELS: ROLE_LABELS,
    role: role,
    can: can,
    api: {
      get: function (url) { return request('GET', url); },
      post: function (url, body) { return request('POST', url, body === undefined ? {} : body); },
      put: function (url, body) { return request('PUT', url, body); },
      delete: function (url, body) { return request('DELETE', url, body); }
    },
    esc: esc,
    date: date,
    typeLabel: typeLabel,
    highlighted: highlighted,
    flaggedTerms: flaggedTerms,
    safeUrl: safeUrl,
    toast: toast,
    decide: decide,
    digitsOnly: digitsOnly,
    empty: empty,
    loading: loading,
    sanctionOptions: sanctionOptions,
    parseSanction: parseSanction,
    sanctionLabel: sanctionLabel,
    section: function (key, definition) { sections[key] = definition; },
    sections: sections
  };
})(window);
