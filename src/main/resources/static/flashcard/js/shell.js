/* Shared theme + bottom nav helpers */
(function () {
  'use strict';

  var STORAGE_THEME = 'jm-theme';

  function getTheme() {
    return localStorage.getItem(STORAGE_THEME) || 'light';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(STORAGE_THEME, theme);
    var icon = document.querySelector('#btn-theme i');
    if (icon) icon.className = theme === 'dark' ? 'fa-solid fa-sun' : 'fa-solid fa-moon';
  }

  function toggleTheme() {
    applyTheme(getTheme() === 'dark' ? 'light' : 'dark');
  }

  function setSidebarActive(tab) {
    document.querySelectorAll('[data-sidebar-tab]').forEach(function (item) {
      item.classList.toggle('is-active', item.getAttribute('data-sidebar-tab') === tab);
    });
  }

  function inferSidebarTab() {
    var page = document.body.classList;
    if (page.contains('flashcard-page--library')) {
      return new URLSearchParams(window.location.search).get('tab') === 'my' ? 'my' : 'public';
    }
    if (page.contains('flashcard-page--editor')) return 'my';
    return null;
  }

  function showToast(msg, type) {
    var toast = document.getElementById('toast');
    if (!toast) return;
    type = type || 'info';
    var icon = type === 'ok' ? 'fa-circle-check' : type === 'err' ? 'fa-circle-xmark' : 'fa-circle-info';
    var cls = type === 'ok' ? 'is-ok' : type === 'err' ? 'is-err' : '';
    toast.innerHTML = '<i class="fa-solid ' + icon + '"></i> ' + String(msg || '')
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    toast.className = 'toast is-show ' + cls;
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { toast.classList.remove('is-show'); }, 2800);
  }

  function esc(s) {
    return String(s ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** Dedicated folder form shared by the library and card editor. */
  function requestFolderName(options) {
    options = options || {};
    if (document.getElementById('fc-folder-dialog')) return Promise.resolve(null);
    var previousFocus = document.activeElement;
    var dialog = document.createElement('dialog');
    dialog.id = 'fc-folder-dialog';
    dialog.className = 'fc-folder-dialog';
    dialog.setAttribute('aria-labelledby', 'fc-folder-title');
    dialog.setAttribute('aria-describedby', 'fc-folder-hint');
    dialog.innerHTML =
      '<form novalidate>' +
        '<header class="fc-folder-dialog__header"><h2 id="fc-folder-title"></h2>' +
          '<button type="button" class="fc-folder-dialog__close" data-folder-cancel aria-label="Đóng"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button></header>' +
        '<div class="fc-folder-dialog__body"><label for="fc-folder-name">Tên thư mục</label>' +
          '<input id="fc-folder-name" name="name" type="text" maxlength="100" autocomplete="off" aria-describedby="fc-folder-hint fc-folder-error">' +
          '<p id="fc-folder-hint">Tối đa 100 ký tự.</p>' +
          '<p id="fc-folder-error" class="fc-folder-dialog__error" role="alert" hidden></p></div>' +
        '<footer class="fc-folder-dialog__footer"><button type="button" class="fc-folder-dialog__button" data-folder-cancel>Hủy</button>' +
          '<button type="submit" class="fc-folder-dialog__button fc-folder-dialog__button--primary"></button></footer>' +
      '</form>';
    var input = dialog.querySelector('input');
    var error = dialog.querySelector('#fc-folder-error');
    dialog.querySelector('h2').textContent = options.rename ? 'Đổi tên thư mục' : 'Tạo thư mục';
    dialog.querySelector('[type="submit"]').textContent = options.rename ? 'Lưu tên' : 'Tạo thư mục';
    input.value = options.name || '';
    return new Promise(function (resolve) {
      var result = null;
      dialog.querySelector('form').addEventListener('submit', function (event) {
        event.preventDefault();
        var name = input.value.trim();
        if (!name || name.length > 100) {
          error.textContent = !name ? 'Vui lòng nhập tên thư mục.' : 'Tên thư mục không được quá 100 ký tự.';
          error.hidden = false;
          input.setAttribute('aria-invalid', 'true');
          input.focus();
          return;
        }
        result = name;
        dialog.close();
      });
      input.addEventListener('input', function () {
        error.hidden = true;
        input.removeAttribute('aria-invalid');
      });
      dialog.querySelectorAll('[data-folder-cancel]').forEach(function (button) {
        button.addEventListener('click', function () { dialog.close(); });
      });
      dialog.addEventListener('click', function (event) {
        if (event.target !== dialog) return;
        var rect = dialog.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
      });
      dialog.addEventListener('close', function () {
        dialog.remove();
        if (previousFocus && previousFocus.isConnected) previousFocus.focus({ preventScroll: true });
        resolve(result);
      }, { once: true });
      document.body.append(dialog);
      dialog.showModal();
      input.focus();
      input.select();
    });
  }

  function requireLogin(msg) {
    var loggedIn = window.JlasAuth && JlasAuth.isLoggedIn();
    console.info('[AUTH_TRACE] requireLogin', {
      path: window.location.pathname,
      loggedIn: !!loggedIn,
      localToken: !!localStorage.getItem('accessToken'),
      authStatusCookie: document.cookie.indexOf('auth_status=1') >= 0
    });
    if (loggedIn) return true;
    showToast(msg || 'Vui lòng đăng nhập', 'err');
    var redirectTarget = window.location.pathname + window.location.search;
    setTimeout(function () {
      location.href = '/login?redirect=' + encodeURIComponent(redirectTarget);
    }, 600);
    return false;
  }

  async function openFlashcardQuiz(setId, options) {
    options = options || {};
    if (!requireLogin('Đăng nhập để kiểm tra kiến thức bằng quiz')) return null;
    if (!setId || !window.FlashApi) {
      showToast('Không xác định được bộ thẻ cần kiểm tra', 'err');
      return null;
    }

    var button = options.button || null;
    var originalHtml = button ? button.innerHTML : '';
    if (button) {
      button.disabled = true;
      button.setAttribute('aria-busy', 'true');
      button.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang chuẩn bị...';
    }

    try {
      var result = await FlashApi.post('/api/quiz/from-flashcard/' + Number(setId), {
        onlyLearning: !!options.onlyLearning
      });
      showToast('Đã chuẩn bị ' + (result.cardCount || 0) + ' thẻ để kiểm tra', 'ok');
      var target = '/flashcard/quiz.html?setId=' + encodeURIComponent(result.vocabSetId) +
        '&sourceSetId=' + encodeURIComponent(setId);
      location.href = target;
      return result;
    } catch (e) {
      var message = e.message || 'Không tạo được bài kiểm tra từ bộ thẻ';
      if (/filter|quá hẹp|empty|4 thẻ|4 card/i.test(message) || e.status === 400) {
        message = options.onlyLearning
          ? 'Cần ít nhất 4 thẻ chưa thuộc để làm bài kiểm tra.'
          : 'Bộ Flashcard cần ít nhất 4 thẻ để làm bài kiểm tra.';
      }
      showToast(message, 'err');
      return null;
    } finally {
      if (button && document.body.contains(button)) {
        button.disabled = false;
        button.removeAttribute('aria-busy');
        button.innerHTML = originalHtml;
      }
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    applyTheme(getTheme());
    setSidebarActive(inferSidebarTab());
    var btn = document.getElementById('btn-theme');
    if (btn) btn.addEventListener('click', toggleTheme);
    if (window.JlasAuth && typeof JlasAuth.applyNav === 'function') {
      try { JlasAuth.applyNav(); } catch (e) { /* ignore */ }
    }
  });

  window.FlashShell = {
    showToast: showToast,
    requestFolderName: requestFolderName,
    esc: esc,
    requireLogin: requireLogin,
    openFlashcardQuiz: openFlashcardQuiz,
    setSidebarActive: setSidebarActive,
    applyTheme: applyTheme,
    toggleTheme: toggleTheme
  };
})();
