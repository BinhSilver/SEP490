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
    esc: esc,
    requireLogin: requireLogin,
    openFlashcardQuiz: openFlashcardQuiz,
    setSidebarActive: setSidebarActive,
    applyTheme: applyTheme,
    toggleTheme: toggleTheme
  };
})();
