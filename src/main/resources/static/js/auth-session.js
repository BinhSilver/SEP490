/**
 * Auth session helpers — JWT in localStorage, header guest/user toggle.
 */
(function () {
  'use strict';

  var TOKEN_KEY = 'accessToken';
  var REFRESH_KEY = 'refreshToken';
  var USER_KEY = 'user';

  function cookieValue(name) {
    var prefix = name + '=';
    var item = document.cookie.split('; ').find(function (value) {
      return value.indexOf(prefix) === 0;
    });
    return item ? decodeURIComponent(item.substring(prefix.length)) : null;
  }

  /** Làm mới khi access token còn dưới 10 phút, kiểm tra mỗi phút: dùng thường xuyên thì không bao giờ phải đăng nhập lại. */
  var REFRESH_AHEAD_MS = 10 * 60 * 1000;
  var CHECK_EVERY_MS = 60 * 1000;

  /** Thời điểm hết hạn (ms) trong JWT; null nếu không đọc được. */
  function tokenExpiry(token) {
    if (!token) return null;
    var parts = token.split('.');
    if (parts.length !== 3) return null;
    try {
      var payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      while (payload.length % 4) payload += '=';
      var exp = Number(JSON.parse(atob(payload)).exp);
      return Number.isFinite(exp) ? exp * 1000 : null;
    } catch (e) {
      return null;
    }
  }

  function isTokenExpired(token) {
    if (!token) return true;
    var expiry = tokenExpiry(token);
    return expiry !== null && expiry <= Date.now();
  }

  function expiresSoon(token) {
    var expiry = tokenExpiry(token);
    return expiry !== null && expiry - Date.now() < REFRESH_AHEAD_MS;
  }

  function storedRefreshToken() {
    try {
      return localStorage.getItem(REFRESH_KEY) || null;
    } catch (e) {
      return null;
    }
  }

  var nativeFetch = window.fetch.bind(window);
  var unauthorizedRedirecting = false;
  var refreshing = null;

  /**
   * Đổi refresh token lấy cặp token mới. Kết quả: true = đã có phiên còn hạn, false = server từ chối
   * (refresh token hết hạn/bị thu hồi → phải đăng nhập lại), null = lỗi mạng (giữ phiên, thử lại sau).
   * Mỗi lần làm mới server cấp refresh token mới và vô hiệu cái cũ, nên các yêu cầu đồng thời dùng chung
   * một lần gọi và các tab xếp hàng qua navigator.locks: tab đến sau dùng luôn token tab trước vừa lấy.
   */
  function refreshSession() {
    if (refreshing) return refreshing;
    function run() {
      var before = localStorage.getItem(TOKEN_KEY);
      if (before && !expiresSoon(before)) return true;
      var refresh = storedRefreshToken();
      if (!refresh) return false;
      return nativeFetch('/api/auth/refresh', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refreshToken: refresh })
      }).then(function (response) {
        if (response.status >= 500) return null;
        return response.ok ? response.json() : false;
      }).then(function (payload) {
        if (payload === null) return null;
        var data = payload && payload.data;
        if (data && data.accessToken) {
          window.JlasAuth.setSession(data.accessToken, data.refreshToken, data.user);
          return true;
        }
        // Một tab khác có thể vừa dùng refresh token này và lưu cặp token mới.
        var latest = localStorage.getItem(TOKEN_KEY);
        return !!latest && latest !== before && !isTokenExpired(latest);
      }).catch(function () {
        return null;
      });
    }
    var locked = navigator.locks && typeof navigator.locks.request === 'function'
      ? navigator.locks.request('jlas-auth-refresh', run)
      : Promise.resolve().then(run);
    refreshing = locked.then(function (result) {
      refreshing = null;
      return result;
    }, function () {
      refreshing = null;
      return null;
    });
    return refreshing;
  }

  function refreshIfExpiringSoon() {
    var token = localStorage.getItem(TOKEN_KEY);
    if (!storedRefreshToken() || (token && !expiresSoon(token))) return;
    refreshSession().then(function (result) {
      if (result === false) window.JlasAuth.clearSession();
    });
  }

  /** 401 vì access token hết hạn thì làm mới rồi gửi lại; trừ các API đăng nhập/OTP (401 là sai thông tin). */
  function canRefreshAfter(response) {
    if (!response || response.status !== 401 || !storedRefreshToken()) return false;
    try {
      var path = new URL(response.url || '', window.location.origin).pathname;
      return path.indexOf('/api/auth/') !== 0 || path === '/api/auth/me' || path === '/api/auth/profile';
    } catch (e) {
      return false;
    }
  }

  /** Request object có thể đã đọc body nên không gửi lại; URL + options thì thay Authorization bằng token mới. */
  function retryArguments(input, init) {
    if (typeof Request !== 'undefined' && input instanceof Request) return null;
    var options = Object.assign({}, init || {});
    var headers = new Headers(options.headers || {});
    if (headers.has('Authorization')) headers.set('Authorization', 'Bearer ' + localStorage.getItem(TOKEN_KEY));
    options.headers = headers;
    return [input, options];
  }

  function redirectToLoginAfterUnauthorized(response) {
    if (unauthorizedRedirecting || !response || response.status !== 401) return;

    var apiPath;
    try {
      apiPath = new URL(response.url || '', window.location.origin).pathname;
    } catch (e) {
      return;
    }

    // Authentication endpoints deliberately return 401 for invalid credentials/OTP.
    // Those forms show their own Vietnamese validation message instead of navigating away.
    if (apiPath.indexOf('/api/auth/') === 0 || window.location.pathname === '/login') return;

    unauthorizedRedirecting = true;
    if (window.JlasAuth && typeof window.JlasAuth.clearSession === 'function') {
      window.JlasAuth.clearSession();
    }

    var returnUrl = window.location.pathname + window.location.search;
    window.location.replace('/login?redirect=' + encodeURIComponent(returnUrl));
  }

  function redirectToAppealWhenBanned(response) {
    if (!response || response.status !== 403 || window.location.pathname === '/appeal.html') return;
    response.clone().json().then(function (body) {
      if (body && body.code === 'ACCOUNT_BANNED') window.location.replace('/appeal.html');
    }).catch(function () {});
  }

  function afterResponse(response) {
    redirectToLoginAfterUnauthorized(response);
    redirectToAppealWhenBanned(response);
    return response;
  }

  window.fetch = function (input, init) {
    return nativeFetch(input, init).then(function (response) {
      if (!canRefreshAfter(response)) return afterResponse(response);
      return refreshSession().then(function (result) {
        var retry = result === true ? retryArguments(input, init) : null;
        if (!retry) {
          if (result === false) return afterResponse(response);
          return response;
        }
        return nativeFetch(retry[0], retry[1]).then(afterResponse);
      });
    });
  };

  window.JlasAuth = {
    getAccessToken: function () {
      return localStorage.getItem(TOKEN_KEY) || cookieValue(TOKEN_KEY);
    },

    getUser: function () {
      try {
        var raw = localStorage.getItem(USER_KEY);
        return raw ? JSON.parse(raw) : null;
      } catch (e) {
        return null;
      }
    },

    setSession: function (accessToken, refreshToken, user) {
      if (accessToken) {
        localStorage.setItem(TOKEN_KEY, accessToken);
        document.cookie = 'accessToken=' + accessToken + '; path=/; max-age=604800; SameSite=Lax';
        document.cookie = 'access_token=' + encodeURIComponent(accessToken) + '; path=/; max-age=604800; SameSite=Lax';
      }
      if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken);
      if (user) localStorage.setItem(USER_KEY, JSON.stringify(user));
      this.applyNav();
    },

    clearSession: function () {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(REFRESH_KEY);
      localStorage.removeItem(USER_KEY);
      document.cookie = 'accessToken=; path=/; max-age=0';
      document.cookie = 'access_token=; path=/; max-age=0';
      document.cookie = 'auth_status=; path=/; max-age=0';
      this.applyNav();
    },

    isLoggedIn: function () {
      var token = this.getAccessToken();
      // Đang làm mới phiên thì vẫn coi là đăng nhập, để trang không chuyển về /login trước khi có token mới.
      return (!!token && !isTokenExpired(token)) || !!refreshing;
    },

    refreshSession: refreshSession,

    authHeaders: function () {
      var t = this.getAccessToken();
      console.debug('[AUTH_TRACE] authHeaders', {
        hasToken: !!t,
        tokenLength: t ? t.length : 0,
        authStatusCookie: cookieValue('auth_status')
      });
      return t
        ? { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t }
        : { 'Content-Type': 'application/json' };
    },

    /** Fetch /api/auth/me and refresh cached user */
    refreshUser: async function () {
      var token = this.getAccessToken();
      if (!token) return null;
      try {
        var res = await fetch('/api/auth/me', {
          headers: { Authorization: 'Bearer ' + token }
        });
        if (res.status === 401) {
          this.clearSession();
          return null;
        }
        var json = await res.json();
        if (json.success && json.data) {
          localStorage.setItem(USER_KEY, JSON.stringify(json.data));
          this.applyNav();
          return json.data;
        }
      } catch (e) { /* ignore */ }
      return this.getUser();
    },

    initials: function (user) {
      if (!user) return '?';
      var name = user.fullName || user.email || '?';
      var parts = name.trim().split(/\s+/);
      if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
      return name.substring(0, 2).toUpperCase();
    },

    avatarUrl: function (user) {
      if (!user) return null;
      var url = user.avatar || user.avatarUrl || user.picture || user.photoUrl || user.imageUrl;
      if (typeof url === 'string' && url.trim()) return url.trim();
      return null;
    },

    displayName: function (user) {
      if (!user) return 'Người dùng';
      return user.fullName || user.email || 'Người dùng';
    },

    roleLabel: function (user) {
      if (!user || !user.role) return 'Học viên';
      if (user.role === 'PREMIUM') return 'Thành viên Pro';
      if (user.role === 'ADMIN') return 'Quản trị viên';
      if (user.role === 'MODERATOR') return 'Kiểm duyệt viên';
      if (user.role === 'CONTENT_CREATOR') return 'Người biên soạn';
      return 'Học viên';
    },

    applyNav: function () {
      var loggedIn = this.isLoggedIn();
      var user = this.getUser();

      document.querySelectorAll('[data-auth-guest]').forEach(function (el) {
        el.hidden = loggedIn;
      });
      document.querySelectorAll('[data-auth-user]').forEach(function (el) {
        el.hidden = !loggedIn;
      });

      if (!loggedIn || !user) {
        if (window.JLabBaseShell && typeof window.JLabBaseShell.syncAuth === 'function') {
          window.JLabBaseShell.syncAuth();
        }
        return;
      }

      document.querySelectorAll('[data-user-name]').forEach(function (el) {
        el.textContent = JlasAuth.displayName(user);
      });
      document.querySelectorAll('[data-user-email]').forEach(function (el) {
        el.textContent = user.email || '';
      });
      document.querySelectorAll('[data-user-role]').forEach(function (el) {
        el.textContent = JlasAuth.roleLabel(user);
      });
      document.querySelectorAll('[data-user-level]').forEach(function (el) {
        el.textContent = user.japaneseLevel && user.japaneseLevel !== 'UNKNOWN'
          ? user.japaneseLevel
          : '—';
      });

      var avatar = JlasAuth.avatarUrl(user);
      var initials = JlasAuth.initials(user);
      document.querySelectorAll('[data-user-avatar]').forEach(function (el) {
        function showInitials() {
          var fallback = document.createElement('span');
          fallback.className = 'user-avatar__initials';
          fallback.textContent = initials;
          el.replaceChildren(fallback);
        }
        if (avatar) {
          var image = document.createElement('img');
          image.src = avatar;
          image.alt = '';
          image.className = 'user-avatar__img';
          image.addEventListener('error', showInitials, { once: true });
          el.replaceChildren(image);
        } else {
          showInitials();
        }
      });

      // Khung avatar ([data-avatar-frame]) + màu tên ([data-user-name]) mua ở Cửa hàng.
      if (window.JlasAvatarFrame) window.JlasAvatarFrame.applyUser(user);

      if (window.JLabBaseShell && typeof window.JLabBaseShell.syncAuth === 'function') {
        window.JLabBaseShell.syncAuth();
      }
    },

    highlightActiveNav: function () {
      if (window.JLabBaseShell && typeof window.JLabBaseShell.syncActiveLink === 'function') {
        window.JLabBaseShell.syncActiveLink();
        return;
      }

      var path = window.location.pathname.toLowerCase().split('?')[0].replace(/\/$/, '');
      if (!path) path = '/';

      var navLinks = document.querySelectorAll('.site-header__nav a, .jlas-platform-nav__links a');
      if (!navLinks || !navLinks.length) return;

      navLinks.forEach(function (a) {
        var href = a.getAttribute('href');
        if (!href || href.startsWith('#')) return;
        var cleanHref = href.toLowerCase().split('?')[0].replace(/\/$/, '');
        if (!cleanHref) cleanHref = '/';

        var isActive = false;
        if (cleanHref === '/') {
          isActive = (path === '/' || path === '/index' || path === '/index.html');
        } else {
          var baseHref = cleanHref.replace(/\.html$/, '');
          var basePath = path.replace(/\.html$/, '');
          isActive = (basePath === baseHref || basePath.startsWith(baseHref + '/'));
        }

        if (isActive) {
          a.classList.add('is-active');
        } else {
          a.classList.remove('is-active');
        }
      });
    },

    requireLogin: function (redirectTo) {
      if (!this.isLoggedIn()) {
        var currentPage = window.location.pathname + window.location.search;
        window.location.href = '/login?redirect=' + encodeURIComponent(redirectTo || currentPage);
        return false;
      }
      return true;
    },

    logout: async function (redirectTo) {
      var refresh = localStorage.getItem(REFRESH_KEY);
      try {
        if (refresh) {
          await fetch('/api/auth/logout', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: refresh })
          });
        }
      } catch (e) { /* ignore */ }
      this.clearSession();
      window.location.href = redirectTo === '/login' ? '/login' : '/';
    }
  };

  // Bắt đầu ngay khi script chạy (trước DOMContentLoaded) để các script trang sau gặp token đã làm mới.
  var startupToken = localStorage.getItem(TOKEN_KEY);
  var needsRefresh = storedRefreshToken() && (!startupToken || expiresSoon(startupToken));
  window.JlasAuth.ready = needsRefresh
    ? refreshSession().then(function (result) {
      if (result === false) window.JlasAuth.clearSession();
    })
    : Promise.resolve();
  window.setInterval(refreshIfExpiringSoon, CHECK_EVERY_MS);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') refreshIfExpiringSoon();
  });

  document.addEventListener('DOMContentLoaded', function () {
    window.JlasAuth.ready.then(startPage);
  });

  function startPage() {
    var token = JlasAuth.getAccessToken();
    if (token && isTokenExpired(token)) {
      // Còn refresh token (lần làm mới vừa lỗi mạng) thì giữ phiên; lần kiểm tra sau sẽ thử lại.
      if (!storedRefreshToken()) JlasAuth.clearSession();
      token = null;
    }
    if (token && !document.cookie.includes('accessToken=')) {
      document.cookie = 'accessToken=' + token + '; path=/; max-age=604800; SameSite=Lax';
    }
    if (token && !document.cookie.includes('access_token=')) {
      document.cookie = 'access_token=' + encodeURIComponent(token) + '; path=/; max-age=604800; SameSite=Lax';
    }
    if (token && !document.cookie.includes('auth_status=')) {
      document.cookie = 'auth_status=1; path=/; max-age=900; SameSite=Lax';
    }
    console.info('[AUTH_TRACE] pageAuthState', {
      path: window.location.pathname,
      localToken: !!localStorage.getItem('accessToken'),
      accessTokenCookie: !!cookieValue('accessToken'),
      authStatusCookie: cookieValue('auth_status'),
      loggedIn: JlasAuth.isLoggedIn(),
      tokenLength: token ? token.length : 0
    });
    JlasAuth.highlightActiveNav();
    JlasAuth.applyNav();
    if (JlasAuth.isLoggedIn()) {
      JlasAuth.refreshUser().then(async function (user) {
        var path = window.location.pathname.replace(/\/$/, '') || '/';
        var authPages = ['/login', '/register', '/verify-email', '/forgot-password', '/reset-password', '/survey'];
        // The placement survey is for learners only; staff accounts never get sent to it.
        var staffRoles = ['ADMIN', 'MODERATOR', 'CONTENT_CREATOR'];
        if (!user || authPages.indexOf(path) >= 0 || path.indexOf('/admin') === 0 || path.indexOf('/premium') === 0 || path.indexOf('/credits') === 0
            || staffRoles.indexOf(user.role) >= 0) return;
        // [JLAS-W0108] A8: only the learning path needs the survey; other pages stay open.
        // Owner 19/11: only the two path pages; /learning/onyomi and other study modules never send to the survey
        if (path !== '/learning' && path.indexOf('/learning/jlpt') !== 0 && path.indexOf('/learning/comm') !== 0) return;
        try {
          var response = await fetch('/api/assessment/survey', { headers: JlasAuth.authHeaders() });
          if (!response.ok) return;
          var payload = await response.json();
          if (payload.success && payload.data && payload.data.completed === false) {
            window.location.replace('/survey');
          }
        } catch (error) { /* Keep the current page available when the server cannot be reached. */ }
      });
    }
  }

  document.addEventListener('click', function (e) {
    var logoutBtn = e.target.closest('[data-auth-logout]');
    if (logoutBtn) {
      e.preventDefault();
      JlasAuth.logout();
    }
  });
})();
