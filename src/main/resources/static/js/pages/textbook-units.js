/**
 * /textbooks/{id} — unit và bài học của một giáo trình.
 * Dữ liệu: GET /api/dictionary/textbooks/{id}/units (Bearer từ JlasAuth → CONTENT_CREATOR thấy cả bài DRAFT).
 */
(function () {
  'use strict';

  var main = document.getElementById('jlabBaseContent');
  var textbookId = main ? main.getAttribute('data-textbook-id') : null;

  var loadingEl = document.getElementById('unitsLoading');
  var errorEl = document.getElementById('unitsError');
  var emptyEl = document.getElementById('unitsEmpty');
  var listEl = document.getElementById('unitsList');
  var headerEl = document.getElementById('textbookHeader');

  var SKILL_LABELS = {
    VOCABULARY: 'Từ vựng', GRAMMAR: 'Ngữ pháp', READING: 'Đọc', LISTENING: 'Nghe',
    SPEAKING: 'Nói', ONYOMI: 'Onyomi', KUNYOMI: 'Kunyomi'
  };

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function showError(message) {
    loadingEl.hidden = true;
    errorEl.textContent = message;
    errorEl.classList.add('is-visible');
    if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(message, 'error');
  }

  /* ---------- Trạng thái duyệt: badge (mọi người) hoặc nút bật/tắt (chỉ CONTENT_CREATOR — ADMIN thuần xem như learner) ----------
   * Quyền thật chặn ở server (@PreAuthorize); JS chỉ ẩn/hiện nút theo role trong localStorage.
   */
  var isContentCreator = (function () {
    var u = window.JlasAuth && typeof JlasAuth.getUser === 'function' ? JlasAuth.getUser() : null;
    return !!(u && u.role === 'CONTENT_CREATOR');
  })();

  function statusBadge(status) {
    if (status === 'PUBLISHED') return '';
    // OD-45 (owner 2026-10-20): learners may open the DRAFT J-LAS N3 textbook; it is never shown as reviewed.
    if (status === 'DRAFT') {
      return '<span class="tb-badge tb-badge--draft" title="Giáo trình chưa được duyệt — nội dung có thể còn sai sót.">Chưa duyệt</span>';
    }
    return '<span class="tb-badge tb-badge--draft">' + escapeHtml(status) + '</span>';
  }

  /** Nút đổi trạng thái cho CONTENT_CREATOR: hiện trạng thái hiện tại, bấm để chuyển PUBLISHED ↔ DRAFT. */
  function statusToggle(kind, id, status) {
    if (!isContentCreator) return statusBadge(status);
    var published = status === 'PUBLISHED';
    return '<button type="button" class="status-toggle' + (published ? ' status-toggle--published' : ' status-toggle--draft') + '"' +
      ' data-kind="' + kind + '" data-id="' + id + '" data-status="' + escapeHtml(status) + '"' +
      ' title="' + (published ? 'Đang công bố — bấm để rút về nháp' : 'Đang nháp — bấm để công bố') + '">' +
      '<i class="fa-solid ' + (published ? 'fa-eye' : 'fa-eye-slash') + '" aria-hidden="true"></i> ' + escapeHtml(status) +
      '</button>';
  }

  function toggleStatus(btn) {
    var kind = btn.getAttribute('data-kind'), id = btn.getAttribute('data-id'), current = btn.getAttribute('data-status');
    var next = current === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED';
    var label = kind === 'textbook' ? 'giáo trình' : 'bài học';
    if (next === 'DRAFT' && !window.confirm('Rút ' + label + ' này về nháp? Học viên sẽ không xem được nữa.')) return;
    btn.disabled = true;
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : { 'Content-Type': 'application/json' };
    headers['Content-Type'] = 'application/json';
    fetch('/api/dictionary/' + (kind === 'textbook' ? 'textbooks' : 'lessons') + '/' + encodeURIComponent(id) + '/status', {
      method: 'PATCH', headers: headers, body: JSON.stringify({ status: next })
    })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (!res.ok || !json || json.success === false) throw new Error((json && json.message) || ('Lỗi HTTP ' + res.status));
          return json.data;
        });
      })
      .then(function (data) {
        // Cập nhật tại chỗ, không reload.
        btn.outerHTML = statusToggle(kind, id, data.status);
        if (window.JlasUI && typeof JlasUI.toast === 'function') {
          JlasUI.toast((data.status === 'PUBLISHED' ? 'Đã công bố ' : 'Đã rút về nháp ') + label + '.', 'success');
        }
      })
      .catch(function (err) {
        btn.disabled = false;
        if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(err.message || 'Không đổi được trạng thái.', 'error');
      });
  }

  function setupStatusToggles() {
    main.addEventListener('click', function (event) {
      var btn = event.target.closest('.status-toggle');
      if (!btn || btn.disabled) return;
      event.preventDefault();
      event.stopPropagation();
      toggleStatus(btn);
    });
  }

  /** hasContent = giáo trình có ít nhất một bài được xem; mô tả chỉ hiện khi KHÔNG có (lời giải thích vì sao trống). */
  function renderHeader(textbook, hasContent) {
    document.getElementById('breadcrumbTextbook').textContent = textbook.title || '';
    document.getElementById('textbookLevel').textContent = textbook.jlptLevel ? 'Cấp độ ' + textbook.jlptLevel : 'Nhập môn';
    document.getElementById('textbookTitle').textContent = textbook.title || '';
    var descEl = document.getElementById('textbookDescription');
    descEl.textContent = !hasContent && textbook.description ? textbook.description : '';
    descEl.hidden = hasContent || !textbook.description;
    document.getElementById('textbookMeta').innerHTML =
      '<span class="tb-badge"><i class="fa-solid fa-layer-group" aria-hidden="true"></i> ' + (textbook.unitCount || 0) + ' unit</span>' +
      statusToggle('textbook', textbook.id, textbook.status);
    document.title = (textbook.title || 'Giáo trình') + ' - KotsuKotsu';
    headerEl.hidden = false;
  }

  function lessonRow(lesson, index) {
    var meta = [];
    meta.push(statusToggle('lesson', lesson.id, lesson.status));
    return '' +
      '<li>' +
        '<a class="lesson-row" href="/textbooks/' + encodeURIComponent(textbookId) + '/lessons/' + encodeURIComponent(lesson.id) + '">' +
          '<span class="lesson-row__order">' + (index + 1) + '</span>' +
          '<span>' +
            '<p class="lesson-row__title">' + escapeHtml(lesson.title) + '</p>' +
            '<span class="lesson-row__meta">' + meta.join('') + '</span>' +
          '</span>' +
          '<i class="fa-solid fa-chevron-right lesson-row__arrow" aria-hidden="true"></i>' +
        '</a>' +
      '</li>';
  }

  function unitCard(unit, index) {
    var lessons = unit.lessons || [];
    var body = lessons.length
      ? '<ol class="lesson-list">' + lessons.map(lessonRow).join('') + '</ol>'
      : '<p class="unit-card__empty">Unit này chưa có bài học được công bố.</p>';
    return '' +
      '<section class="unit-card">' +
        '<div class="unit-card__header">' +
          '<span class="unit-card__index">UNIT ' + (index + 1) + '</span>' +
          '<div>' +
            '<h2 class="unit-card__title">' + escapeHtml(unit.title) + '</h2>' +
            (unit.description ? '<p class="unit-card__desc">' + escapeHtml(unit.description) + '</p>' : '') +
          '</div>' +
        '</div>' +
        body +
      '</section>';
  }

  function render(data) {
    loadingEl.hidden = true;
    var units = data.units || [];
    var totalLessons = units.reduce(function (sum, u) { return sum + (u.lessons ? u.lessons.length : 0); }, 0);
    renderHeader(data.textbook || {}, totalLessons > 0);
    if (units.length === 0 || totalLessons === 0) {
      emptyEl.hidden = false;
      return;
    }
    listEl.innerHTML = units.map(unitCard).join('');
    listEl.hidden = false;
  }

  /** 403 + message PREMIUM_REQUIRED (TextbookAccessPolicy): mở hộp thoại nâng cấp (#tbPremiumDialog). */
  var PREMIUM_REQUIRED = 'PREMIUM_REQUIRED';
  function openPremiumDialog() {
    var dialog = document.getElementById('tbPremiumDialog');
    if (dialog && typeof dialog.showModal === 'function') {
      if (!dialog.open) dialog.showModal();
    } else {
      window.location.href = '/premium';
    }
  }

  function load() {
    if (!textbookId) { showError('Thiếu mã giáo trình.'); return; }
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    fetch('/api/dictionary/textbooks/' + encodeURIComponent(textbookId) + '/units', { headers: headers })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (res.status === 404) throw new Error('Không tìm thấy giáo trình hoặc bạn chưa được xem giáo trình này.');
          if (res.status === 403 && json && json.message === PREMIUM_REQUIRED) {
            openPremiumDialog();
            throw new Error('Giáo trình này cần Premium. Nâng cấp để mở toàn bộ giáo trình.');
          }
          if (!res.ok || !json || json.success === false) {
            throw new Error((json && json.message) || ('Không tải được giáo trình (HTTP ' + res.status + ')'));
          }
          return json.data;
        });
      })
      .then(render)
      .catch(function (err) { showError(err.message || 'Không tải được giáo trình.'); });
  }

  setupStatusToggles();
  load();
})();
