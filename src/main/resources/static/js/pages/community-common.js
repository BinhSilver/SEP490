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
    // options.form (FormData, vd. bài viết kèm ảnh/video): bỏ Content-Type để trình duyệt tự đặt multipart boundary.
    var requestHeaders = Object.assign({}, headers());
    if (options.form) {
      Object.keys(requestHeaders).forEach(function (k) { if (k.toLowerCase() === 'content-type') delete requestHeaders[k]; });
    }
    try {
      response = await fetch(path, {
        method: options.method || 'GET',
        headers: requestHeaders,
        body: options.form || (options.body === undefined ? undefined : JSON.stringify(options.body))
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
    if (contentType === 'POST') return '/community/posts/' + encodeURIComponent(contentId);
    return '/community/' + encodeURIComponent(contentType) + '/' + encodeURIComponent(contentId);
  }

  /** "vừa xong", "5 phút trước", "3 giờ trước", "2 ngày trước"; quá 7 ngày thì hiện ngày. */
  function relativeTime(value) {
    if (!value) return '';
    var date = new Date(value);
    if (isNaN(date.getTime())) return '';
    var seconds = Math.max(0, Math.round((Date.now() - date.getTime()) / 1000));
    if (seconds < 60) return 'vừa xong';
    var minutes = Math.floor(seconds / 60);
    if (minutes < 60) return minutes + ' phút trước';
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + ' giờ trước';
    var days = Math.floor(hours / 24);
    if (days <= 7) return days + ' ngày trước';
    return date.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  /** <time> hiện thời gian tương đối, rê chuột (title) thấy ngày giờ đầy đủ. */
  function timeTag(value) {
    if (!value) return '';
    return '<time datetime="' + esc(value) + '" title="' + esc(formatDate(value)) + '">' + esc(relativeTime(value)) + '</time>';
  }

  async function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return;
    }
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    var ok = document.execCommand('copy');
    document.body.removeChild(area);
    if (!ok) throw new Error('copy failed');
  }

  // ── Hộp thoại dùng chung: một ngăn xếp cho mọi hộp của Community ─────────────
  // Escape chỉ đóng hộp TRÊN CÙNG, Tab / Shift+Tab không ra khỏi hộp đang mở (aria-modal), đóng hộp thì trả focus về
  // nơi đã mở nó (hoặc fallback() khi nơi đó không còn trên trang, vd thẻ vừa được vẽ lại sau khi sửa).
  var modalStack = [];
  var FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), ' +
    'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

  function focusables(modal) {
    return Array.prototype.filter.call(modal.querySelectorAll(FOCUSABLE), function (node) {
      return node.getClientRects().length > 0 || node === document.activeElement;
    });
  }

  /** options: focus (phần tử nhận focus đầu tiên), onEscape (cách đóng khi bấm Escape), fallback() (focus khi đóng). */
  function openModal(modal, options) {
    options = options || {};
    modalStack = modalStack.filter(function (entry) { return entry.modal !== modal; });
    modalStack.push({ modal: modal, opener: document.activeElement, onEscape: options.onEscape || null,
      fallback: options.fallback || null });
    modal.hidden = false;
    var first = options.focus || focusables(modal)[0];
    if (first && typeof first.focus === 'function') first.focus();
  }

  function closeModal(modal) {
    modal.hidden = true;
    var index = -1;
    modalStack.forEach(function (entry, i) { if (entry.modal === modal) index = i; });
    if (index < 0) return;
    var entry = modalStack.splice(index, 1)[0];
    // Nơi mở còn trên trang và còn hiển thị (phần tử bị ẩn / đã thay thì không có client rect) mới trả focus về đó.
    var visible = entry.opener && entry.opener.isConnected && entry.opener.getClientRects().length > 0;
    var back = visible ? entry.opener : (entry.fallback ? entry.fallback() : null);
    if (back && typeof back.focus === 'function') back.focus();
  }

  function hasOpenModal() {
    return modalStack.some(function (entry) { return !entry.modal.hidden; });
  }

  document.addEventListener('keydown', function (event) {
    modalStack = modalStack.filter(function (entry) { return !entry.modal.hidden && entry.modal.isConnected; });
    var top = modalStack[modalStack.length - 1];
    if (!top) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      if (top.onEscape) top.onEscape(); else closeModal(top.modal);
    } else if (event.key === 'Tab') {
      var items = focusables(top.modal);
      if (!items.length) { event.preventDefault(); return; }
      var first = items[0];
      var last = items[items.length - 1];
      if (!top.modal.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });

  /**
   * Hộp thoại xác nhận của trang (thay confirm()). Cần markup #cmConfirm trong template.
   * Trả Promise<boolean>; Esc / bấm nền / Huỷ = false. Mở hộp mới khi hộp cũ còn chờ thì hộp cũ trả false (một lần bấm
   * "Đồng ý" không xác nhận hai thao tác).
   */
  var pendingConfirm = null;

  function confirmDialog(options) {
    var modal = document.getElementById('cmConfirm');
    if (!modal) return Promise.resolve(false);
    if (pendingConfirm) pendingConfirm(false);
    document.getElementById('cmConfirmTitle').textContent = options.title || 'Xác nhận';
    document.getElementById('cmConfirmMessage').textContent = options.message || '';
    var ok = document.getElementById('cmConfirmOk');
    ok.textContent = options.confirmLabel || 'Đồng ý';
    return new Promise(function (resolve) {
      function close(result) {
        if (pendingConfirm !== close) return;
        pendingConfirm = null;
        modal.removeEventListener('click', onClick);
        closeModal(modal);
        resolve(result);
      }
      function onClick(event) {
        if (event.target.closest('#cmConfirmOk')) close(true);
        else if (event.target.closest('[data-cm-close]')) close(false);
      }
      pendingConfirm = close;
      modal.addEventListener('click', onClick);
      openModal(modal, { focus: ok, onEscape: function () { close(false); }, fallback: options.fallback });
    });
  }

  /** Người đang đăng nhập (lưu ở JlasAuth) dưới dạng author để vẽ avatar ô soạn bài / ô bình luận. */
  function currentAuthor() {
    var user = global.JlasAuth && typeof global.JlasAuth.getUser === 'function' ? global.JlasAuth.getUser() : null;
    if (!user) return { displayName: '?', avatarUrl: null };
    return { displayName: user.fullName || user.username || 'Bạn', avatarUrl: user.avatarUrl || user.avatar || null };
  }

  global.CommunityUI = {
    api: api,
    esc: esc,
    safeUrl: safeUrl,
    toast: toast,
    stars: stars,
    formatDate: formatDate,
    avatar: avatar,
    detailUrl: detailUrl,
    relativeTime: relativeTime,
    timeTag: timeTag,
    copyText: copyText,
    confirmDialog: confirmDialog,
    openModal: openModal,
    closeModal: closeModal,
    hasOpenModal: hasOpenModal,
    currentAuthor: currentAuthor
  };
})(window);
