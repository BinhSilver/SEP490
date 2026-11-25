/**
 * JLAS Game Arena client: REST /api/games/** + kênh realtime SSE /api/games/stream.
 * Dùng chung cho sảnh, trang thiết lập, phòng chờ và cả 3 game React (đọc window.JlasArena).
 *
 * - Token đăng nhập nằm ở cookie; access token sống 15 phút nên client tự làm mới trước khi hết hạn,
 *   tránh bị đá về trang đăng nhập giữa trận.
 * - SSE chỉ đẩy server → trình duyệt; mọi thao tác gửi lên bằng REST.
 */
(function (global) {
  'use strict';

  var REFRESH_EVERY_MS = 10 * 60 * 1000;
  var REFRESH_BEFORE_EXPIRY_MS = 2 * 60 * 1000;
  var refreshing = null;
  var keepAliveTimer = null;
  // Keep portrait and landscape phones under the same availability rule.
  var phoneMedia = global.matchMedia('(max-width: 767px), (max-height: 500px) and (pointer: coarse)');
  function canPlayGame(type) {
    return type !== 'AIR_DEFENSE' || !phoneMedia.matches;
  }

  function ArenaError(message, status, code) {
    var error = new Error(message || 'Có lỗi xảy ra, bạn thử lại nhé');
    error.status = status || 0;
    error.code = code || null;
    return error;
  }

  function storedToken() {
    try {
      return global.localStorage.getItem('accessToken');
    } catch (e) {
      return null;
    }
  }

  function tokenExpiresSoon() {
    var token = storedToken();
    if (!token) return false;
    var parts = token.split('.');
    if (parts.length !== 3) return false;
    try {
      var payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      while (payload.length % 4) payload += '=';
      var claims = JSON.parse(global.atob(payload));
      return Number(claims.exp) * 1000 - Date.now() < REFRESH_BEFORE_EXPIRY_MS;
    } catch (e) {
      return false;
    }
  }

  /** Làm mới access token bằng refresh token; nhiều lời gọi cùng lúc dùng chung một request. */
  function refreshToken() {
    // Dùng chung bộ làm mới của trang (một lần gọi, xếp hàng giữa các tab) vì refresh token chỉ dùng được một lần.
    if (global.JlasAuth && typeof global.JlasAuth.refreshSession === 'function') {
      return global.JlasAuth.refreshSession().then(function (result) { return result === true; });
    }
    if (refreshing) return refreshing;
    var refresh = null;
    try {
      refresh = global.localStorage.getItem('refreshToken');
    } catch (e) {
      refresh = null;
    }
    if (!refresh) return Promise.resolve(false);
    refreshing = global.fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken: refresh })
    }).then(function (response) {
      return response.ok ? response.json() : null;
    }).then(function (payload) {
      var data = payload && payload.data;
      if (data && data.accessToken && global.JlasAuth && typeof global.JlasAuth.setSession === 'function') {
        global.JlasAuth.setSession(data.accessToken, data.refreshToken, data.user);
        return true;
      }
      return false;
    }).catch(function () {
      return false;
    }).then(function (ok) {
      refreshing = null;
      return ok;
    });
    return refreshing;
  }

  function startKeepAlive() {
    if (keepAliveTimer) return;
    keepAliveTimer = global.setInterval(refreshToken, REFRESH_EVERY_MS);
  }

  /** Longest a "leave match" waits for the server before the page moves on anyway. */
  var LEAVE_WAIT_MS = 400;

  function request(method, url, body) {
    var ready = tokenExpiresSoon() ? refreshToken() : Promise.resolve(true);
    return ready.then(function () {
      var options = {
        method: method,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' }
      };
      if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body === null ? {} : body);
      }
      return global.fetch(url, options).then(function (response) {
        if (response.status === 204) return null;
        return response.json().catch(function () {
          return { success: false, message: 'Có lỗi xảy ra, bạn thử lại sau ít phút nhé' };
        }).then(function (payload) {
          if (!response.ok || !payload || payload.success === false) {
            throw ArenaError(payload && payload.message, response.status, payload && payload.code);
          }
          return payload.data;
        });
      }, function () {
        throw ArenaError('Không kết nối được. Kiểm tra mạng rồi thử lại.', 0, 'NETWORK');
      });
    });
  }

  function qs(params) {
    var parts = [];
    Object.keys(params).forEach(function (key) {
      var value = params[key];
      if (value === undefined || value === null || value === '') return;
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
    });
    return parts.length ? '?' + parts.join('&') : '';
  }

  var api = {
    get: function (url) { return request('GET', url); },
    post: function (url, body) { return request('POST', url, body === undefined ? null : body); },
    put: function (url, body) { return request('PUT', url, body); },

    catalog: function () { return request('GET', '/api/games/catalog'); },
    lobby: function () { return request('GET', '/api/games/lobby'); },
    decks: function (tab, query, page, gameType) {
      return request('GET', '/api/games/decks' + qs({ tab: tab, q: query, page: page || 0, game: gameType }));
    },
    deck: function (setId) { return request('GET', '/api/games/decks/' + encodeURIComponent(setId)); },

    createRoom: function (payload) { return request('POST', '/api/games/rooms', payload); },
    joinByCode: function (code) { return request('POST', '/api/games/rooms/join', { code: code }); },
    room: function (roomId) { return request('GET', '/api/games/rooms/' + encodeURIComponent(roomId)); },
    joinRoom: function (roomId) { return request('POST', '/api/games/rooms/' + encodeURIComponent(roomId) + '/join', null); },
    updateSettings: function (roomId, settings) {
      return request('PUT', '/api/games/rooms/' + encodeURIComponent(roomId) + '/settings', settings);
    },
    ready: function (roomId, ready) {
      return request('POST', '/api/games/rooms/' + encodeURIComponent(roomId) + '/ready', { ready: !!ready });
    },
    start: function (roomId) { return request('POST', '/api/games/rooms/' + encodeURIComponent(roomId) + '/start', null); },
    leave: function (roomId) { return request('POST', '/api/games/rooms/' + encodeURIComponent(roomId) + '/leave', null); },

    quickMatch: function (gameType, setId) {
      return request('POST', '/api/games/quick-match', { gameType: gameType, setId: setId });
    },
    quickMatchStatus: function () { return request('GET', '/api/games/quick-match'); },
    cancelQuickMatch: function () { return request('POST', '/api/games/quick-match/cancel', null); },
    draftChoose: function (roomId, stepKey, value) {
      return request('POST', '/api/games/rooms/' + encodeURIComponent(roomId) + '/draft/choose',
        { stepKey: stepKey, value: value });
    },
    draftPass: function (roomId, stepKey) {
      return request('POST', '/api/games/rooms/' + encodeURIComponent(roomId) + '/draft/pass', { stepKey: stepKey });
    },

    solo: function (payload) { return request('POST', '/api/games/solo', payload); },
    session: function (sessionId) { return request('GET', '/api/games/sessions/' + encodeURIComponent(sessionId)); },
    /**
     * Leaving a match only takes the player back to the lobby, so it never waits on the server: the forfeit is sent
     * as a keepalive request (it still goes out after the page changes) and this resolves after a short moment at
     * most, even if the request is slow, queued behind open live connections, or fails.
     */
    leaveSession: function (sessionId) {
      var url = '/api/games/sessions/' + encodeURIComponent(sessionId) + '/leave';
      var ready = tokenExpiresSoon() ? refreshToken().catch(function () { return false; }) : Promise.resolve(true);
      var sent = ready.then(function () {
        return global.fetch(url, {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          keepalive: true,
          headers: { Accept: 'application/json' }
        });
      }).then(function () { return null; }, function () { return null; });
      var moment = new Promise(function (resolve) { global.setTimeout(function () { resolve(null); }, LEAVE_WAIT_MS); });
      return Promise.race([sent, moment]);
    },

    shop: function () { return request('GET', '/api/games/shop'); },
    buyItem: function (code) {
      return request('POST', '/api/games/shop/items/' + encodeURIComponent(code) + '/buy', null);
    },
    equipItem: function (code) {
      return request('POST', '/api/games/shop/items/' + encodeURIComponent(code) + '/equip', null);
    },

    /** Khung avatar + màu tên (cùng CP với cửa hàng game). */
    cosmetics: function () { return request('GET', '/api/profile/cosmetics'); },
    buyFrame: function (code) {
      return request('POST', '/api/profile/cosmetics/frames/' + encodeURIComponent(code) + '/buy', null);
    },
    equipCosmetics: function (frameCode, nameStyle) {
      return request('PUT', '/api/profile/cosmetics', { frameCode: frameCode, nameStyle: nameStyle });
    }
  };

  /** Một EventSource cho cả trang; handler đăng ký theo `type` của sự kiện (hoặc '*'). */
  var live = {
    source: null,
    status: 'idle',
    skewMs: 0,
    retry: 0,
    timer: null,
    opened: false,
    handlers: {},
    statusHandlers: [],

    connect: function () {
      var self = this;
      startKeepAlive();
      if (self.source || typeof global.EventSource === 'undefined') return self;
      self.setStatus('connecting');
      var source = new global.EventSource('/api/games/stream');
      self.source = source;
      source.onopen = function () {
        var reconnected = self.opened;
        self.opened = true;
        self.retry = 0;
        self.setStatus('online');
        self.emit('CONNECTED', { type: 'CONNECTED', payload: { reconnected: reconnected } });
      };
      source.onmessage = function (event) {
        var envelope;
        try {
          envelope = JSON.parse(event.data);
        } catch (e) {
          return;
        }
        if (!envelope || !envelope.type) return;
        if (envelope.serverTime) self.skewMs = envelope.serverTime - Date.now();
        self.emit(envelope.type, envelope);
        self.emit('*', envelope);
      };
      source.onerror = function () {
        if (source.readyState === global.EventSource.CLOSED) {
          // Máy chủ từ chối (thường do token hết hạn): làm mới token rồi nối lại có giãn cách.
          self.source = null;
          self.setStatus('offline');
          self.scheduleReconnect();
        } else {
          self.setStatus('connecting');
        }
      };
      return self;
    },

    scheduleReconnect: function () {
      var self = this;
      if (self.timer) return;
      var delay = Math.min(10000, 1000 * Math.pow(2, self.retry));
      self.retry += 1;
      self.timer = global.setTimeout(function () {
        self.timer = null;
        refreshToken().then(function () { self.connect(); });
      }, delay);
    },

    on: function (type, handler) {
      var list = this.handlers[type] || (this.handlers[type] = []);
      list.push(handler);
      return function off() {
        var index = list.indexOf(handler);
        if (index >= 0) list.splice(index, 1);
      };
    },

    onStatus: function (handler) {
      var list = this.statusHandlers;
      list.push(handler);
      handler(this.status);
      return function off() {
        var index = list.indexOf(handler);
        if (index >= 0) list.splice(index, 1);
      };
    },

    emit: function (type, envelope) {
      var list = (this.handlers[type] || []).slice();
      for (var i = 0; i < list.length; i++) {
        try {
          list[i](envelope.payload, envelope);
        } catch (err) {
          if (global.console) global.console.error('[arena] handler lỗi', err);
        }
      }
    },

    setStatus: function (status) {
      if (this.status === status) return;
      this.status = status;
      var list = this.statusHandlers.slice();
      for (var i = 0; i < list.length; i++) list[i](status);
    },

    serverNow: function () {
      return Date.now() + this.skewMs;
    },

    close: function () {
      if (this.source) this.source.close();
      this.source = null;
      if (this.timer) global.clearTimeout(this.timer);
      this.timer = null;
      this.setStatus('idle');
    }
  };

  var mePromise = null;

  /** Người đang đăng nhập: { userId, name, avatarUrl }. */
  function me() {
    if (mePromise) return mePromise;
    mePromise = request('GET', '/api/auth/me').then(function (user) {
      user = user || {};
      return {
        userId: user.userId || user.id,
        name: user.fullName || user.username || (user.email ? String(user.email).split('@')[0] : 'Bạn'),
        avatarUrl: user.avatarUrl || user.avatar || null
      };
    }).catch(function (error) {
      mePromise = null;
      throw error;
    });
    return mePromise;
  }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  var FIELD_LABELS = { KANJI: 'Kanji', HIRAGANA: 'Cách đọc', MEANING: 'Nghĩa tiếng Việt', HAN_VIET: 'Hán Việt' };
  var GAME_NAMES = { NEKO_CATCH: 'Bắt cá', MEMORY_MATCH: 'Lật thẻ', AIR_DEFENSE: 'Bắn máy bay',
    SPHINX_RIDDLE: 'Giải đố Nhân Sư', PICTURE_REBUS: 'Ghép hình đoán từ', DRAW_GUESS: 'Vẽ & Đoán' };

  global.addEventListener('beforeunload', function () { live.close(); });

  global.JlasArena = {
    api: api,
    live: live,
    me: me,
    refreshToken: refreshToken,
    esc: esc,
    phoneMedia: phoneMedia,
    canPlayGame: canPlayGame,
    fieldLabel: function (field) { return FIELD_LABELS[field] || field || ''; },
    gameName: function (type) { return GAME_NAMES[type] || type || ''; },
    FIELD_LABELS: FIELD_LABELS,
    GAME_NAMES: GAME_NAMES
  };
})(window);
