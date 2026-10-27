/* Full notification history with paging (the bell panel shows only the latest 50). */
(function () {
  'use strict';

  var PAGE_SIZE = 20;
  var ICONS = {
    MODERATION_CONTENT: 'fa-shield-halved', MODERATION_SANCTION: 'fa-user-lock', MODERATION_APPEAL: 'fa-scale-balanced',
    SECURITY: 'fa-shield-halved', DICTIONARY_CONTRIBUTION: 'fa-book', FLASHCARD_COMMENT: 'fa-comment',
    SHADOWING_COMMENT: 'fa-comment', SHADOWING_VIDEO_READY: 'fa-closed-captioning',
    SHADOWING_VIDEO_FAILED: 'fa-triangle-exclamation', WELCOME: 'fa-hand'
  };
  var state = { page: 0, unreadOnly: false, totalPages: 0 };

  function $(id) { return document.getElementById(id); }

  function headers() {
    var h = window.JlasAuth && typeof window.JlasAuth.authHeaders === 'function' ? window.JlasAuth.authHeaders() : {};
    h['Content-Type'] = 'application/json';
    return h;
  }

  function time(value) {
    var shell = window.JLabBaseShell;
    return shell && typeof shell.formatNotificationTime === 'function' ? shell.formatNotificationTime(value) : '';
  }

  /** Same-site paths and http(s) only, matching the bell panel. */
  function safeUrl(url) {
    var value = String(url || '');
    return /^\/(?!\/)/.test(value) || /^https?:\/\//i.test(value) ? value : null;
  }

  function row(item) {
    var url = safeUrl(item.targetUrl);
    var el = document.createElement(url ? 'a' : 'div');
    el.className = 'ntf-item' + (item.read ? '' : ' is-unread');
    if (url) el.href = url;

    var iconBox = document.createElement('span');
    iconBox.className = 'ntf-item__icon';
    iconBox.innerHTML = '<i class="fa-solid ' + (ICONS[item.type] || 'fa-bell') + '" aria-hidden="true"></i>';

    var body = document.createElement('div');
    body.className = 'ntf-item__body';
    var title = document.createElement('strong');
    title.textContent = item.title || 'Thông báo';
    var message = document.createElement('p');
    message.textContent = item.message || '';
    var when = document.createElement('time');
    if (item.createdAt) {
      when.dateTime = item.createdAt;
      when.textContent = time(item.createdAt);
      when.title = new Date(item.createdAt).toLocaleString('vi-VN');
    }
    body.append(title, message, when);
    el.append(iconBox, body);

    if (!item.read) {
      var mark = document.createElement('button');
      mark.type = 'button';
      mark.className = 'ntf-item__mark';
      mark.textContent = 'Đã đọc';
      mark.setAttribute('aria-label', 'Đánh dấu đã đọc: ' + (item.title || ''));
      mark.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        markRead(item.id).then(load);
      });
      el.appendChild(mark);
      el.addEventListener('click', function () { markRead(item.id); });
    }
    return el;
  }

  function markRead(id) {
    return fetch('/api/notifications/read', {
      method: 'POST', headers: headers(), body: JSON.stringify(id ? { id: id } : {}), keepalive: true
    }).then(function () {
      if (window.JLabBaseShell && typeof window.JLabBaseShell.refreshNotifications === 'function') {
        window.JLabBaseShell.refreshNotifications();
      }
    }).catch(function () {});
  }

  async function load() {
    var list = $('ntfList');
    list.setAttribute('aria-busy', 'true');
    try {
      var response = await fetch('/api/notifications/history?page=' + state.page + '&size=' + PAGE_SIZE +
        '&unreadOnly=' + state.unreadOnly, { headers: headers() });
      if (response.status === 401) {
        window.location.replace('/login?redirect=' + encodeURIComponent('/notifications'));
        return;
      }
      var body = await response.json();
      var data = body.data || body;
      state.totalPages = data.totalPages;
      if (state.page > 0 && state.page >= data.totalPages) {
        state.page = Math.max(0, data.totalPages - 1);
        return load();
      }
      list.replaceChildren();
      if (!data.items.length) {
        list.innerHTML = '<div class="ntf-empty"><i class="fa-regular fa-bell-slash" aria-hidden="true"></i>' +
          '<strong>' + (state.unreadOnly ? 'Không có thông báo chưa đọc' : 'Bạn chưa có thông báo') + '</strong></div>';
      } else {
        data.items.forEach(function (item) { list.appendChild(row(item)); });
      }
      $('ntfSummary').textContent = data.totalElements + ' thông báo · ' + data.unreadCount + ' chưa đọc';
      $('ntfMarkAll').hidden = data.unreadCount === 0;
      $('ntfPager').hidden = data.totalPages <= 1;
      $('ntfPageInfo').textContent = 'Trang ' + (data.page + 1) + '/' + Math.max(1, data.totalPages);
      $('ntfPager').querySelector('[data-step="-1"]').disabled = data.page <= 0;
      $('ntfPager').querySelector('[data-step="1"]').disabled = data.page + 1 >= data.totalPages;
    } catch (error) {
      list.innerHTML = '<div class="ntf-empty"><i class="fa-solid fa-circle-exclamation" aria-hidden="true"></i>' +
        '<strong>Không tải được thông báo</strong><span>Vui lòng thử lại sau.</span></div>';
    } finally {
      list.removeAttribute('aria-busy');
    }
  }

  document.querySelectorAll('.ntf-tab').forEach(function (tab) {
    tab.addEventListener('click', function () {
      document.querySelectorAll('.ntf-tab').forEach(function (t) { t.setAttribute('aria-selected', String(t === tab)); });
      state.unreadOnly = tab.dataset.unread === 'true';
      state.page = 0;
      load();
    });
  });
  $('ntfPager').addEventListener('click', function (event) {
    var button = event.target.closest('[data-step]');
    if (!button || button.disabled) return;
    state.page += Number(button.dataset.step);
    load();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });
  $('ntfMarkAll').addEventListener('click', function () { markRead(null).then(load); });

  if (window.JlasAuth && typeof window.JlasAuth.isLoggedIn === 'function' && !window.JlasAuth.isLoggedIn()) {
    window.location.replace('/login?redirect=' + encodeURIComponent('/notifications'));
    return;
  }
  load();
})();
