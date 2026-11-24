/* "Báo cáo cộng đồng" (/admin/community-reports): content admin xem mọi báo cáo bài viết / nội dung chia sẻ / bình luận
 * trong Cộng đồng (gom theo đối tượng, chia tab theo loại), gỡ nội dung vi phạm hoặc giữ lại khi báo cáo sai. Bố cục và CSS dùng chung với
 * "Báo lỗi học tập" (lcr-*). API: /api/admin/community-reports. */
(function () {
  'use strict';
  var API = '/api/admin/community-reports';
  var STATES = { PUBLISHED: 'Đang hiển thị', TEMPORARILY_HIDDEN: 'Đang tạm ẩn (đủ báo cáo)', REMOVED: 'Đã gỡ', DELETED: 'Tác giả đã xoá' };
  var OUTCOMES = { RESOLVED: 'Đã gỡ nội dung', REJECTED: 'Báo cáo sai, giữ nội dung', AUTHOR_DELETED: 'Tự đóng: tác giả đã xoá' };
  var state = { page: 0, pages: 0, items: [], kinds: [], kind: '', selected: null, revision: 0, saving: false };

  function $(id) { return document.getElementById(id); }
  function esc(v) { return window.Admin.esc(v); }
  function when(v) { return v ? new Date(v).toLocaleString('vi-VN') : '—'; }
  function key(item) { return item.targetType + ':' + item.targetId; }
  function notice(text) { $('lcrNotice').textContent = text || ''; $('lcrNotice').hidden = !text; }
  function closed() { return $('crStatus').value === 'CLOSED'; }

  function badge(item) {
    return '<span class="cr-badge cr-badge--' + esc(item.kindKey || '') + '">' + esc(item.kind) + '</span>';
  }

  /** Hàng tab: "Tất cả" + từng loại có báo cáo ở trạng thái đang xem, kèm số lượng. */
  function renderKinds() {
    var total = state.kinds.reduce(function (sum, k) { return sum + k.count; }, 0);
    var tabs = [{ key: '', label: 'Tất cả', count: total }].concat(state.kinds);
    if (state.kind && !state.kinds.some(function (k) { return k.key === state.kind; })) {
      tabs.push({ key: state.kind, label: kindLabel(state.kind), count: 0 });
    }
    var hadFocus = $('crKinds').contains(document.activeElement);
    $('crKinds').innerHTML = tabs.map(function (k) {
      var on = k.key === state.kind;
      return '<button type="button" role="tab" data-kind="' + esc(k.key) + '" aria-selected="' + on + '" tabindex="' + (on ? 0 : -1) + '">' +
        esc(k.label) + '<span class="cr-count">' + k.count + '</span></button>';
    }).join('');
    if (hadFocus) $('crKinds').querySelector('[aria-selected="true"]').focus();
  }

  function kindLabel(key) {
    for (var i = 0; i < state.kinds.length; i++) if (state.kinds[i].key === key) return state.kinds[i].label;
    return { POST: 'Bài viết', COMMENT: 'Bình luận', FLASHCARD_SET: 'Bộ flashcard', SHADOWING_VIDEO: 'Video Shadowing' }[key] || key;
  }

  async function load() {
    var revision = ++state.revision;
    notice('');
    $('lcrList').setAttribute('aria-busy', 'true');
    try {
      var status = encodeURIComponent($('crStatus').value);
      var results = await Promise.all([
        window.Admin.api.get(API + '/kinds?status=' + status),
        window.Admin.api.get(API + '?status=' + status + '&page=' + state.page + (state.kind ? '&kind=' + encodeURIComponent(state.kind) : ''))
      ]);
      if (revision !== state.revision) return;
      state.kinds = results[0] || [];
      renderKinds();
      var data = results[1];
      state.items = data.items || [];
      state.pages = data.totalPages;
      $('lcrCount').textContent = data.totalElements + (closed() ? ' lượt xử lý' : ' nội dung bị báo cáo');
      $('lcrList').innerHTML = state.items.length ? state.items.map(function (item) {
        return '<button type="button" class="lcr-item" data-key="' + esc(key(item)) + '" aria-pressed="' + (state.selected === key(item)) + '">' +
          '<small>' + badge(item) + item.reportCount + ' báo cáo · ' +
            esc(closed() ? (OUTCOMES[item.outcome] || item.outcome) : (STATES[item.state] || item.state)) + '</small>' +
          '<strong>' + esc(clip(item.preview, 140)) + '</strong>' +
          '<small>' + esc(item.ownerName || 'Không rõ tác giả') + ' · ' + esc(when(closed() ? item.resolvedAt : item.lastReportedAt)) + '</small>' +
          '</button>';
      }).join('') : '<p class="lcr-muted">' + (closed() ? 'Chưa có báo cáo nào được xử lý' : 'Không có báo cáo nào chờ xử lý') +
        (state.kind ? ' trong mục ' + esc(kindLabel(state.kind)) : '') + '.</p>';
      $('lcrPrevious').disabled = state.page === 0;
      $('lcrNext').disabled = state.page + 1 >= state.pages;
      $('lcrPage').textContent = 'Trang ' + (state.page + 1) + ' / ' + Math.max(1, state.pages);
      var current = find(state.selected);
      if (current) detail(current); else { state.selected = null; $('lcrDetail').innerHTML = '<p>Chọn một báo cáo để xem nội dung.</p>'; }
    } catch (error) {
      if (revision === state.revision) notice(error.message);
    } finally {
      if (revision === state.revision) $('lcrList').setAttribute('aria-busy', 'false');
    }
  }

  function clip(text, max) {
    var value = String(text || '').replace(/\s+/g, ' ').trim();
    return value.length > max ? value.slice(0, max - 1) + '…' : value;
  }

  function find(k) {
    for (var i = 0; i < state.items.length; i++) if (key(state.items[i]) === k) return state.items[i];
    return null;
  }

  function detail(item) {
    state.selected = key(item);
    var link = window.Admin.safeUrl(item.targetUrl);
    var open = !closed() && item.state !== 'REMOVED' && item.state !== 'DELETED';
    var lines = (item.reports || []).map(function (r) {
      return '<li><strong>' + esc(r.reason) + '</strong>' + (r.note ? ': ' + esc(r.note) : '') +
        ' <span class="lcr-muted">· ' + esc(when(r.createdAt)) + '</span></li>';
    }).join('');
    $('lcrDetail').innerHTML =
      '<span class="lcr-muted">' + badge(item) + esc(STATES[item.state] || item.state) + '</span>' +
      '<h2>' + esc(item.ownerName || 'Không rõ tác giả') + '</h2>' +
      '<p class="lcr-note">' + esc(item.preview) + '</p>' +
      (link ? '<a href="' + esc(link) + '" target="_blank" rel="noopener">Mở trong Cộng đồng ↗</a>' : '') +
      '<h3>' + item.reportCount + ' lượt báo cáo</h3><ul class="lcr-note">' + lines + '</ul>' +
      (closed()
        ? '<h3>' + esc(OUTCOMES[item.outcome] || item.outcome) + '</h3><p class="lcr-note">' + esc(item.resolutionNote || '') + '</p>' +
          '<p class="lcr-muted">' + esc(item.resolvedByName || 'Hệ thống') + ' · ' + esc(when(item.resolvedAt)) + '</p>'
        : open
          ? '<label for="crReason">Lý do (gửi kèm thông báo cho tác giả) *</label><textarea id="crReason" maxlength="500"></textarea>' +
            '<div class="lcr-actions"><button type="button" class="lcr-resolve" data-act="remove">Gỡ nội dung</button>' +
            '<button type="button" data-act="dismiss">Giữ lại (báo cáo sai)</button></div>'
          : '<p class="lcr-muted">Nội dung không còn hiển thị, không cần xử lý thêm.</p>');
    $('lcrList').querySelectorAll('[data-key]').forEach(function (button) {
      button.setAttribute('aria-pressed', String(button.dataset.key === state.selected));
    });
  }

  async function act(action) {
    var item = find(state.selected);
    if (state.saving || !item) return;
    var reason = $('crReason').value.trim();
    if (!reason) { notice('Vui lòng nhập lý do.'); $('crReason').focus(); return; }
    state.saving = true;
    notice('');
    $('lcrDetail').querySelectorAll('button,textarea').forEach(function (el) { el.disabled = true; });
    try {
      await window.Admin.api.post(API + '/' + action, { targetType: item.targetType, targetId: item.targetId, reason: reason });
      window.Admin.toast(action === 'remove' ? 'Đã gỡ nội dung và báo cho tác giả.' : 'Đã giữ nội dung, báo cáo được đóng.', 'success');
      state.selected = null;
      await load();
    } catch (error) {
      notice(error.message);
      $('lcrDetail').querySelectorAll('button,textarea').forEach(function (el) { el.disabled = false; });
    } finally {
      state.saving = false;
    }
  }

  $('crFilters').addEventListener('submit', function (event) { event.preventDefault(); state.page = 0; state.selected = null; load(); });
  $('crStatus').addEventListener('change', function () { state.page = 0; state.selected = null; load(); });
  $('crKinds').addEventListener('click', function (event) {
    var tab = event.target.closest('[data-kind]');
    if (!tab || tab.dataset.kind === state.kind) return;
    state.kind = tab.dataset.kind;
    state.page = 0;
    state.selected = null;
    load();
  });
  // Mũi tên trái/phải chuyển tab (role=tablist).
  $('crKinds').addEventListener('keydown', function (event) {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    var tabs = Array.prototype.slice.call($('crKinds').querySelectorAll('[data-kind]'));
    var index = tabs.indexOf(document.activeElement);
    if (index < 0) return;
    var next = tabs[(index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
    next.focus();
    next.click();
    event.preventDefault();
  });
  $('lcrPrevious').addEventListener('click', function () { if (state.page > 0) { state.page--; load(); } });
  $('lcrNext').addEventListener('click', function () { if (state.page + 1 < state.pages) { state.page++; load(); } });
  $('lcrList').addEventListener('click', function (event) {
    var button = event.target.closest('[data-key]');
    var item = button && find(button.dataset.key);
    if (item) detail(item);
  });
  $('lcrDetail').addEventListener('click', function (event) {
    var button = event.target.closest('[data-act]');
    if (button) act(button.dataset.act);
  });
  Promise.resolve(window.AdminShell.ready).then(function (ready) {
    if (ready) { $('jlabBaseContent').hidden = false; load(); }
  }).catch(function (error) { notice(error.message); });
})();
