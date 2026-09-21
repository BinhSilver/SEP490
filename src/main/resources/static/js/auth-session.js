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

  function isTokenExpired(token) {
    if (!token) return true;
    var parts = token.split('.');
    if (parts.length !== 3) return false;
    try {
      var payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      while (payload.length % 4) payload += '=';
      var claims = JSON.parse(atob(payload));
      return Number.isFinite(Number(claims.exp)) && Number(claims.exp) * 1000 <= Date.now();
    } catch (e) {
      return false;
    }
  }

  var nativeFetch = window.fetch.bind(window);
  var unauthorizedRedirecting = false;

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

  window.fetch = function () {
    return nativeFetch.apply(window, arguments).then(function (response) {
      redirectToLoginAfterUnauthorized(response);
      return response;
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
      return !!token && !isTokenExpired(token);
    },

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

    logout: async function () {
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
      window.location.href = '/';
    }
  };

  document.addEventListener('DOMContentLoaded', function () {
    var token = JlasAuth.getAccessToken();
    if (token && isTokenExpired(token)) {
      JlasAuth.clearSession();
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
      JlasAuth.refreshUser();
    }
  });

  document.addEventListener('click', function (e) {
    var logoutBtn = e.target.closest('[data-auth-logout]');
    if (logoutBtn) {
      e.preventDefault();
      JlasAuth.logout();
    }
  });
})();
