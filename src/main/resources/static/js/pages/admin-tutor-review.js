/* "Duyệt câu trả lời AI": content creators review what the J-LAS tutor wrote on its own when the J-LAS data had nothing
 * (tier-2 answers) and the antonyms it suggested. Same layout and helpers as "Báo sai lộ trình" (jpath-admin-reports.js):
 * runs inside the admin shell (Admin.*, AdminShell.ready). */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.AdminTutorReview = api;
    if (root.document) {
      if (root.document.readyState === 'loading') {
        root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
      } else {
        api.mount();
      }
    }
  }
})(typeof window !== 'undefined' ? window : globalThis, function (global) {
  'use strict';

  var BASE = '/api/admin/tutor-review';
  var KIND_LABELS = { ANSWER: 'Câu trả lời AI', ANTONYM: 'Từ trái nghĩa' };
  var STATUS_LABELS = { PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối' };
  var DICTIONARY_NOTE = 'Khi hiển thị, hệ thống tự thêm nhãn AI và cách đọc, nghĩa của các từ tiếng Nhật lấy từ từ điển J-LAS;'
    + ' không cần viết những phần đó vào đây.';
  var ANTONYM_NOTE = 'Từ trái nghĩa gõ vào phải có trong từ điển J-LAS; cách đọc và nghĩa hiển thị lấy từ từ điển.';

  function text(value) {
    return value == null ? '' : String(value).trim();
  }

  /** '3 lượt hỏi · Từ: 誕生日': what the list row says under the question. */
  function itemMeta(item) {
    var parts = [item.askCount + ' lượt hỏi'];
    if (item.word) { parts.push('Từ: ' + item.word); }
    return parts.join(' · ');
  }

  /** Body of PUT /answers/{id}: the text is sent only when it changed, so a status-only action keeps the stored answer. */
  function answerUpdate(item, status, editedAnswer) {
    var body = { status: status };
    if (editedAnswer != null && text(editedAnswer) !== text(item.answer)) { body.answer = text(editedAnswer); }
    return body;
  }

  // ------------------------------------------------------------------ page

  var state = { kind: 'ANSWER', status: 'PENDING', q: '', page: 0, size: 20, items: [], selectedId: null };
  var searchTimer = null;

  function $(id) {
    return global.document.getElementById(id);
  }

  function esc(value) {
    return global.Admin.esc(value);
  }

  function when(value) {
    return value ? global.Admin.date(value) : '';
  }

  async function loadQueue() {
    var status = $('trvListStatus');
    status.textContent = 'Đang tải…';
    try {
      var query = '?kind=' + encodeURIComponent(state.kind) + '&status=' + encodeURIComponent(state.status)
        + '&page=' + state.page + '&size=' + state.size + (state.q ? '&q=' + encodeURIComponent(state.q) : '');
      var page = await global.Admin.api.get(BASE + '/items' + query);
      state.items = page.items || [];
      renderItems();
      var pages = Math.max(1, Math.ceil(page.total / page.size));
      $('trvPageInfo').textContent = 'Trang ' + (page.page + 1) + '/' + pages;
      $('trvPrev').disabled = page.page <= 0;
      $('trvNext').disabled = page.page + 1 >= pages;
      status.textContent = page.total ? page.total + ' mục' : 'Không có mục nào ở bộ lọc này.';
      if (state.selectedId != null && !state.items.some(function (i) { return i.id === state.selectedId; })) {
        state.selectedId = null;
        $('trvDetail').innerHTML = '<p class="jpa-muted">Chọn một mục ở danh sách để xem và duyệt.</p>';
      }
    } catch (error) {
      status.textContent = error.message;
    }
  }

  function renderItems() {
    $('trvItems').innerHTML = state.items.map(function (item) {
      var selected = item.id === state.selectedId;
      var badge = item.status === 'PENDING' ? '<span class="jpa-badge is-warn">' + esc(STATUS_LABELS[item.status]) + '</span>'
        : '<span class="jpa-badge">' + esc(STATUS_LABELS[item.status] || item.status) + '</span>';
      var preview = item.kind === 'ANTONYM'
        ? item.word + (item.antonym ? ' ↔ ' + item.antonym : ' ↔ (chưa có)') : item.question;
      return '<li><button type="button" class="jpa-group' + (selected ? ' is-selected' : '') + '" data-id="' + esc(item.id) + '"'
        + (selected ? ' aria-current="true"' : '') + '>'
        + '<span class="jpa-group__head"><span class="jpa-group__type">' + esc(KIND_LABELS[item.kind] || item.kind) + '</span>'
        + badge + '</span>'
        + '<span class="jpa-group__preview" lang="ja">' + esc(preview) + '</span>'
        + '<span class="jpa-group__meta">' + esc(itemMeta(item)) + ' · ' + esc(when(item.updatedAt)) + '</span>'
        + '</button></li>';
    }).join('');
  }

  function selected() {
    return state.items.find(function (i) { return i.id === state.selectedId; }) || null;
  }

  function open(id) {
    state.selectedId = id;
    renderItems();
    var item = selected();
    if (!item) { return; }
    var head = '<header class="jpa-detail__head"><div><span class="jpa-eyebrow">' + esc(KIND_LABELS[item.kind])
      + ' · ' + esc(STATUS_LABELS[item.status] || item.status) + ' · ' + esc(itemMeta(item)) + '</span>'
      + '<h2 lang="ja">' + esc(item.kind === 'ANTONYM' ? item.word + (item.wordReading ? ' (' + item.wordReading + ')' : '')
        : item.question) + '</h2>'
      + '<p class="jpa-muted">' + (item.model ? 'Model: ' + esc(item.model) + ' · ' : '') + 'Cập nhật ' + esc(when(item.updatedAt))
      + '</p></div></header>';
    var back = item.status === 'PENDING' ? ''
      : '<button id="trvPending" class="jpa-btn is-secondary" type="button">Đưa về chờ duyệt</button>';
    var html;
    if (item.kind === 'ANSWER') {
      html = head + '<form id="trvForm" class="jpa-form">'
        + '<div class="jpa-field"><label for="trvAnswer">Câu trả lời người học thấy</label>'
        + '<textarea id="trvAnswer" rows="12" lang="ja">' + esc(item.answer) + '</textarea></div>'
        + '<p class="jpa-note">' + esc(DICTIONARY_NOTE) + '</p>'
        + '<div class="jpa-actions">'
        + '<button class="jpa-btn is-primary" type="submit" data-status="APPROVED">Lưu và duyệt</button>'
        + '<button class="jpa-btn is-secondary" type="submit" data-status="' + esc(item.status) + '">Chỉ lưu sửa</button>'
        + '<button id="trvReject" class="jpa-btn is-secondary" type="button"' + (item.status === 'REJECTED' ? ' disabled' : '')
        + '>Từ chối</button>' + back + '</div></form>';
    } else {
      var current = item.antonym
        ? '<span lang="ja">' + esc(item.antonym) + (item.antonymReading ? ' (' + esc(item.antonymReading) + ')' : '') + '</span>'
          + (item.antonymMeaning ? ': ' + esc(item.antonymMeaning) : '')
        : 'AI chưa tìm được từ nào có trong từ điển.';
      html = head + '<h3>Gợi ý hiện tại</h3><p>' + current + '</p>'
        + '<form id="trvForm" class="jpa-form">'
        + '<div class="jpa-field"><label for="trvAntonym">Đổi sang từ khác (để trống nếu giữ nguyên)</label>'
        + '<input id="trvAntonym" type="text" lang="ja" autocomplete="off" placeholder="ví dụ: つまらない"/></div>'
        + '<p class="jpa-note">' + esc(ANTONYM_NOTE) + '</p>'
        + '<div class="jpa-actions">'
        + '<button class="jpa-btn is-primary" type="submit" data-status="APPROVED">Duyệt</button>'
        + '<button id="trvReject" class="jpa-btn is-secondary" type="button"' + (item.status === 'REJECTED' ? ' disabled' : '')
        + '>Từ chối</button>' + back + '</div></form>';
    }
    $('trvDetail').innerHTML = html;
  }

  async function update(status) {
    var item = selected();
    if (!item) { return; }
    try {
      if (item.kind === 'ANSWER') {
        var area = $('trvAnswer');
        await global.Admin.api.put(BASE + '/answers/' + item.id, answerUpdate(item, status, area ? area.value : null));
      } else {
        var input = $('trvAntonym');
        await global.Admin.api.put(BASE + '/antonyms/' + item.id,
          { status: status, antonym: input && text(input.value) ? text(input.value) : null });
      }
      global.Admin.toast(status === 'APPROVED' ? 'Đã duyệt.' : status === 'REJECTED' ? 'Đã từ chối.' : 'Đã lưu.', 'success');
      await loadQueue();
      if (selected()) { open(state.selectedId); }
    } catch (error) {
      global.Admin.toast(error.message, 'error');
    }
  }

  function bind() {
    $('trvKind').addEventListener('change', function (e) { state.kind = e.target.value; state.page = 0; state.selectedId = null; loadQueue(); });
    $('trvStatus').addEventListener('change', function (e) { state.status = e.target.value; state.page = 0; loadQueue(); });
    $('trvSearch').addEventListener('input', function (e) {
      global.clearTimeout(searchTimer);
      searchTimer = global.setTimeout(function () { state.q = text(e.target.value); state.page = 0; loadQueue(); }, 300);
    });
    $('trvFilters').addEventListener('submit', function (e) { e.preventDefault(); });
    $('trvPrev').addEventListener('click', function () { if (state.page > 0) { state.page--; loadQueue(); } });
    $('trvNext').addEventListener('click', function () { state.page++; loadQueue(); });
    $('trvItems').addEventListener('click', function (e) {
      var button = e.target.closest('.jpa-group');
      if (button) { open(Number(button.dataset.id)); }
    });
    var detail = $('trvDetail');
    detail.addEventListener('submit', function (e) {
      if (e.target.id !== 'trvForm') { return; }
      e.preventDefault();
      var submitter = e.submitter || detail.querySelector('button[data-status="APPROVED"]');
      update(submitter.dataset.status);
    });
    detail.addEventListener('click', function (e) {
      if (e.target.id === 'trvReject') { update('REJECTED'); }
      if (e.target.id === 'trvPending') { update('PENDING'); }
    });
  }

  var mounted = false;

  async function mount() {
    if (mounted || !global.AdminShell || !$('trvItems')) {
      return;
    }
    mounted = true;
    if (!(await global.AdminShell.ready)) {
      return;
    }
    bind();
    loadQueue();
  }

  return { itemMeta: itemMeta, answerUpdate: answerUpdate, mount: mount };
});
