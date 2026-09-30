/**
 * MemoryStore: cầu nối giữa bảng Memory (React) và server. Snapshot nhận qua SSE của JlasArena,
 * nước đi gửi bằng REST; bảng React chỉ vẽ lại theo snapshot mới nhất (luật chơi nằm ở server).
 */
(function (global) {
  'use strict';

  function MemoryStore(sessionId) {
    this.sessionId = sessionId;
    this.state = null;
    this.currentUserId = null;
    this.connection = 'connecting';
    this.listeners = { change: [], event: [], connection: [] };
    this.offs = [];
    this.destroyed = false;
  }

  MemoryStore.prototype.on = function (type, handler) {
    var list = this.listeners[type];
    if (!list || typeof handler !== 'function') return function () {};
    list.push(handler);
    return function off() {
      var index = list.indexOf(handler);
      if (index >= 0) list.splice(index, 1);
    };
  };

  MemoryStore.prototype.emit = function (type, payload) {
    var list = (this.listeners[type] || []).slice();
    for (var i = 0; i < list.length; i++) {
      try {
        list[i](payload, this);
      } catch (err) {
        if (global.console) global.console.error('[memory] listener lỗi', err);
      }
    }
  };

  MemoryStore.prototype.setConnection = function (status) {
    if (this.connection === status) return;
    this.connection = status;
    this.emit('connection', status);
  };

  /** Bỏ snapshot cũ hơn: sự kiện tới trễ không làm bàn lùi trạng thái. */
  MemoryStore.prototype.applyState = function (next) {
    if (!next || next.sessionId !== this.sessionId) return;
    if (this.state && typeof next.stateVersion === 'number' && next.stateVersion < this.state.stateVersion) return;
    this.state = next;
    this.emit('change', next);
  };

  MemoryStore.prototype.serverNow = function () {
    return global.JlasArena.live.serverNow();
  };

  MemoryStore.prototype.refresh = function () {
    var self = this;
    return global.JlasArena.api.get('/api/games/memory/' + encodeURIComponent(self.sessionId))
      .then(function (state) {
        self.applyState(state);
        self.emit('event', { type: 'MEMORY_SESSION_STATE', payload: state });
        return state;
      });
  };

  MemoryStore.prototype.connect = function () {
    if (this.destroyed || this.offs.length) return;
    var self = this;
    var live = global.JlasArena.live;
    live.connect();

    self.offs.push(live.onStatus(function (status) {
      self.setConnection(status === 'online' ? 'online' : status === 'connecting' || status === 'idle' ? 'connecting' : 'offline');
    }));
    self.offs.push(live.on('MEMORY_STATE', function (envelope, raw) {
      if (!raw || raw.sessionId !== self.sessionId || !envelope) return;
      self.applyState(envelope.payload);
      self.emit('event', envelope);
    }));
    self.offs.push(live.on('CONNECTED', function (payload) {
      // Nối lại sau khi rớt mạng: tải lại snapshot vì có thể đã lỡ vài sự kiện.
      if (payload && payload.reconnected) self.refresh().catch(function () {});
    }));
  };

  /** Gửi ý định lật thẻ; server trả snapshot mới (SSE cũng gửi cho người còn lại). */
  MemoryStore.prototype.flip = function (cardInstanceId) {
    if (this.destroyed || this.connection === 'offline') return false;
    var self = this;
    global.JlasArena.api.post('/api/games/memory/' + encodeURIComponent(self.sessionId) + '/flip',
      { cardInstanceId: cardInstanceId })
      .then(function (state) { self.applyState(state); })
      .catch(function (error) {
        self.emit('event', { type: 'MEMORY_ERROR', payload: { message: error && error.message } });
        self.refresh().catch(function () {});
      });
    return true;
  };

  MemoryStore.prototype.destroy = function () {
    this.destroyed = true;
    for (var i = 0; i < this.offs.length; i++) this.offs[i]();
    this.offs = [];
    this.listeners = { change: [], event: [], connection: [] };
  };

  global.MemoryStore = MemoryStore;
})(window);
