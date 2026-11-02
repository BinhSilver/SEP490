/**
 * Community Hub: tiện ích dùng chung cho trang feed và trang chi tiết.
 * Gọi API kèm JWT (JlasAuth), đổi lỗi 400/403/404/409 thành thông báo tiếng Việt, toast nhỏ, escape HTML.
 */
(function (global) {
  'use strict';

  var FALLBACK = {
    400: 'Dữ liệu gửi lên chưa hợp lệ.',
    403: 'Bạn không có quyền thực hiện thao tác này.',
    404: 'Không tìm thấy nội dung hoặc nội dung không còn công khai.',
    409: 'Thao tác không thể thực hiện ở trạng thái hiện tại.',
    429: 'Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút.'
  };

  function headers() {
    if (global.JlasAuth && typeof global.JlasAuth.authHeaders === 'function') return global.JlasAuth.authHeaders();
    return { 'Content-Type': 'application/json' };
  }

  function friendlyMessage(status, body) {
    if (status === 404) return FALLBACK[404];
    if (body && body.errors) {
      var first = Object.keys(body.errors).map(function (k) { return body.errors[k]; })[0];
      if (first) return first;
    }
    if (body && body.message && /[À-ỹ]/.test(body.message)) return body.message;
    return FALLBACK[status] || 'Có lỗi xảy ra, vui lòng thử lại.';
  }

  async function api(path, options) {
    options = options || {};
    if (global.JlasAuth && global.JlasAuth.ready) {
      try { await global.JlasAuth.ready; } catch (ignored) { /* vẫn gọi API, server tự trả 401 */ }
    }
    var response;
    try {
      response = await fetch(path, {
        method: options.method || 'GET',
        headers: headers(),
        body: options.body === undefined ? undefined : JSON.stringify(options.body)
      });
    } catch (networkError) {
      var offline = new Error('Không kết nối được máy chủ, vui lòng kiểm tra mạng.');
      offline.status = 0;
      throw offline;
    }
    var body = null;
    try { body = await response.json(); } catch (ignored) { body = null; }
    if (!response.ok || (body && body.success === false)) {
      var error = new Error(friendlyMessage(response.status, body));
      error.status = response.status;
      throw error;
    }
    return body ? body.data : null;
  }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** Chỉ cho phép link nội bộ (bắt đầu bằng "/") hoặc http(s). */
  function safeUrl(url) {
    var value = String(url || '');
    return /^(\/(?!\/)|https?:\/\/)/i.test(value) ? value : '#';
  }

  function toast(message, type) {
    var host = document.getElementById('cmToastHost');
    if (!host) {
      host = document.createElement('div');
      host.id = 'cmToastHost';
      host.className = 'cm-toast-host';
      host.setAttribute('aria-live', 'polite');
      document.body.appendChild(host);
    }
    var el = document.createElement('div');
    el.className = 'cm-toast cm-toast--' + (type || 'info');
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');
    el.textContent = message;
    host.appendChild(el);
    while (host.children.length > 3) host.removeChild(host.firstChild);
    setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 4000);
  }

  function stars(value) {
    var html = '';
    var rounded = Math.round((Number(value) || 0) * 2) / 2;
    for (var i = 1; i <= 5; i++) {
      var cls = rounded >= i ? 'fa-solid fa-star' : rounded >= i - 0.5 ? 'fa-solid fa-star-half-stroke' : 'fa-regular fa-star';
      html += '<i class="' + cls + '" aria-hidden="true"></i>';
    }
    return '<span class="cm-stars" aria-label="' + (Number(value) || 0).toFixed(1) + ' trên 5 sao">' + html + '</span>';
  }

  function formatDate(value) {
    if (!value) return '';
    var date = new Date(value);
    if (isNaN(date.getTime())) return '';
    return date.toLocaleString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  function avatar(author) {
    var name = author && author.displayName ? author.displayName : '?';
    if (author && author.avatarUrl) {
      return '<img class="cm-avatar" src="' + esc(safeUrl(author.avatarUrl)) + '" alt="" loading="lazy">';
    }
    return '<span class="cm-avatar cm-avatar--initial" aria-hidden="true">' + esc(name.trim().charAt(0).toUpperCase()) + '</span>';
  }

  function detailUrl(contentType, contentId) {
    return '/community/' + encodeURIComponent(contentType) + '/' + encodeURIComponent(contentId);
  }

  global.CommunityUI = {
    api: api,
    esc: esc,
    safeUrl: safeUrl,
    toast: toast,
    stars: stars,
    formatDate: formatDate,
    avatar: avatar,
    detailUrl: detailUrl
  };
})(window);
