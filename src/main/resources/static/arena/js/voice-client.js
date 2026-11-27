/** Opt-in voice chat for a two-player Arena room. Audio never passes through J-LAS REST/SSE. */
(function (global) {
  'use strict';

  var arena = global.JlasArena;
  var sdk = global.LivekitClient;
  var root = document.querySelector('[data-room-id]')
    || document.querySelector('[data-session-id][data-mode="VERSUS"]');
  if (!arena || !root) return;

  var sessionId = root.dataset.sessionId || null;
  var roomId = root.dataset.roomId || null;
  if (!roomId) return;

  var intentKey = 'jlas.voice.intent.' + roomId;
  var room = null;
  var joining = false;
  var mutedRemote = false;
  var needsAudioUnlock = false;
  var micOn = false;
  var enabled = false;
  var connectingId = 0;
  var media = document.createElement('div');
  media.hidden = true;
  document.body.appendChild(media);

  var box = document.createElement('aside');
  var dock = root.querySelector('[data-arena-voice-dock]');
  var compact = dock && dock.hasAttribute('data-voice-compact');
  box.className = compact ? 'arena-voice arena-voice--compact' : 'arena-voice';
  box.hidden = true;
  box.setAttribute('aria-label', 'Voice chat trận đấu');
  box.innerHTML = '<div class="arena-voice__top">'
    + '<span class="arena-voice__dot" data-voice-dot aria-hidden="true"></span>'
    + '<span class="arena-voice__status" data-voice-status role="status" aria-live="polite">Voice tắt</span>'
    + '</div><div class="arena-voice__actions">'
    + '<button type="button" class="arena-voice__button arena-voice__button--primary" data-voice-toggle aria-pressed="false">Bật mic</button>'
    + '<button type="button" class="arena-voice__button" data-voice-listen aria-pressed="false" disabled>Tắt tiếng đối thủ</button>'
    + '<button type="button" class="arena-voice__button arena-voice__button--leave" data-voice-leave disabled>Rời voice</button>'
    + '</div>';
  (dock || document.body).appendChild(box);

  var dot = box.querySelector('[data-voice-dot]');
  var status = box.querySelector('[data-voice-status]');
  var toggle = box.querySelector('[data-voice-toggle]');
  var listen = box.querySelector('[data-voice-listen]');
  var leave = box.querySelector('[data-voice-leave]');
  if (compact) {
    var menu = document.createElement('details');
    menu.className = 'arena-voice__menu';
    menu.innerHTML = '<summary class="ar-btn ar-btn--sm" aria-label="Tùy chọn voice"><i class="fa-solid fa-ellipsis" aria-hidden="true"></i></summary>';
    var panel = document.createElement('div');
    panel.className = 'arena-voice__panel';
    panel.append(box.querySelector('.arena-voice__top'), box.querySelector('.arena-voice__actions'));
    menu.append(panel);
    toggle.className = 'ar-btn ar-btn--sm';
    box.append(toggle, menu);
    toggle.disabled = true;
    toggle.title = 'Voice chat chưa khả dụng';
    menu.hidden = true;
    // The dock stays hidden until the server says voice is on: a greyed-out mic only raised the question why.
    document.addEventListener('click', function (event) {
      if (!menu.contains(event.target)) menu.open = false;
    });
  }

  function setStatus(message, state) {
    status.textContent = message;
    dot.dataset.state = state || 'idle';
    if (compact) {
      toggle.title = state === 'error' || state === 'warning' ? message : '';
      if (state === 'error' || state === 'warning') menu.open = true;
    }
  }

  function updateButtons() {
    toggle.disabled = joining;
    toggle.textContent = room ? (micOn ? 'Tắt mic' : 'Bật mic')
      : (joining ? 'Đang kết nối…' : 'Bật mic');
    toggle.setAttribute('aria-pressed', micOn ? 'true' : 'false');
    listen.disabled = !room;
    leave.disabled = !room && !joining;
    listen.textContent = needsAudioUnlock ? 'Bật âm thanh'
      : mutedRemote ? 'Bật tiếng đối thủ' : 'Tắt tiếng đối thủ';
    listen.setAttribute('aria-pressed', mutedRemote ? 'true' : 'false');
  }

  function forgetIntent() {
    try { sessionStorage.removeItem(intentKey); } catch (ignored) { }
  }

  function rememberIntent() {
    try { sessionStorage.setItem(intentKey, micOn ? 'mic' : 'listen'); } catch (ignored) { }
  }

  function hasIntent() {
    try { return sessionStorage.getItem(intentKey) || ''; } catch (ignored) { return ''; }
  }

  function stop(forget) {
    connectingId += 1;
    if (forget) forgetIntent();
    var old = room;
    room = null;
    joining = false;
    needsAudioUnlock = false;
    micOn = false;
    if (old) old.disconnect();
    media.replaceChildren();
    setStatus('Voice tắt', 'idle');
    updateButtons();
  }

  function attachAudio(track) {
    var audio = track.attach();
    audio.autoplay = true;
    audio.muted = mutedRemote;
    audio.setAttribute('data-voice-audio', '');
    media.appendChild(audio);
    audio.play().catch(function () {
      needsAudioUnlock = true;
      setStatus('Chạm để bật âm thanh', 'warning');
      updateButtons();
    });
  }

  function connect() {
    if (!enabled || room || joining || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      if (!navigator.mediaDevices) setStatus('Trình duyệt không hỗ trợ micro hoặc trang chưa dùng HTTPS', 'error');
      return;
    }
    joining = true;
    var attempt = ++connectingId;
    var desiredMic = hasIntent() !== 'listen';
    setStatus('Đang kết nối voice…', 'pending');
    updateButtons();
    var url = '/api/games/voice/rooms/' + encodeURIComponent(roomId) + '/token';
    if (sessionId) url += '?sessionId=' + encodeURIComponent(sessionId);
    arena.api.post(url, {}).then(function (credentials) {
      if (attempt !== connectingId) return null;
      var next = new sdk.Room({ adaptiveStream: false, dynacast: false });
      next.on(sdk.RoomEvent.TrackSubscribed, function (track) {
        if (track.kind === sdk.Track.Kind.Audio) attachAudio(track);
      });
      next.on(sdk.RoomEvent.TrackUnsubscribed, function (track) {
        track.detach().forEach(function (element) { element.remove(); });
      });
      next.on(sdk.RoomEvent.Reconnecting, function () {
        setStatus('Mất kết nối, đang thử lại…', 'warning');
      });
      next.on(sdk.RoomEvent.Reconnected, function () {
        setStatus(micOn ? 'Mic đang bật' : 'Đang nghe đối thủ', 'online');
      });
      next.on(sdk.RoomEvent.Disconnected, function (reason) {
        if (room !== next) return;
        room = null;
        media.replaceChildren();
        joining = false;
        micOn = false;
        updateButtons();
        if (reason === sdk.DisconnectReason.DUPLICATE_IDENTITY) {
          forgetIntent();
          setStatus('Voice đã chuyển sang tab khác', 'warning');
        } else if (reason === sdk.DisconnectReason.PARTICIPANT_REMOVED
            || reason === sdk.DisconnectReason.ROOM_DELETED) {
          forgetIntent();
          setStatus('Voice đã kết thúc', 'idle');
        } else {
          setStatus('Voice mất kết nối. Bấm Bật mic để thử lại', 'error');
        }
      });
      room = next;
      return next.connect(credentials.serverUrl, credentials.participantToken).then(function () {
        if (attempt !== connectingId) { next.disconnect(); return; }
        return next.localParticipant.setMicrophoneEnabled(desiredMic).then(function () {
          if (attempt !== connectingId) { next.disconnect(); return; }
          micOn = desiredMic;
          rememberIntent();
          joining = false;
          setStatus(micOn ? 'Mic đang bật' : 'Đang nghe đối thủ', 'online');
          updateButtons();
        });
      });
    }).catch(function (error) {
      if (attempt !== connectingId) return;
      var old = room;
      room = null;
      joining = false;
      micOn = false;
      if (old) old.disconnect();
      forgetIntent();
      setStatus(error && error.message || 'Không kết nối được voice', 'error');
      updateButtons();
    });
  }

  toggle.addEventListener('click', function () {
    if (!room) { connect(); return; }
    var currentRoom = room;
    toggle.disabled = true;
    currentRoom.localParticipant.setMicrophoneEnabled(!micOn).then(function () {
      if (room !== currentRoom) return;
      micOn = !micOn;
      rememberIntent();
      setStatus(micOn ? 'Mic đang bật' : 'Đang nghe đối thủ', 'online');
    }).catch(function () {
      if (room !== currentRoom) return;
      setStatus('Không đổi được trạng thái mic', 'error');
    }).finally(updateButtons);
  });
  leave.addEventListener('click', function () { stop(true); });
  listen.addEventListener('click', function () {
    if (!room) return;
    if (needsAudioUnlock) {
      room.startAudio().then(function () {
        media.querySelectorAll('audio').forEach(function (audio) { audio.play().catch(function () {}); });
        needsAudioUnlock = false;
        setStatus(micOn ? 'Mic đang bật' : 'Đang nghe đối thủ', 'online');
        updateButtons();
      }).catch(function () { setStatus('Không phát được âm thanh', 'error'); });
      return;
    }
    mutedRemote = !mutedRemote;
    media.querySelectorAll('audio').forEach(function (audio) { audio.muted = mutedRemote; });
    updateButtons();
  });

  function loadSdk() {
    if (sdk) return Promise.resolve();
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = '/arena/vendor/livekit-client-2.22.3.umd.js';
      script.onload = function () { sdk = global.LivekitClient; sdk ? resolve() : reject(new Error('SDK voice không khả dụng')); };
      script.onerror = function () { reject(new Error('Không tải được SDK voice')); };
      document.head.appendChild(script);
    });
  }

  arena.api.get('/api/games/voice/status').then(function (config) {
    if (!config || !config.enabled) return;
    return loadSdk().then(function () {
      enabled = true;
      box.hidden = false;
      if (compact) {
        toggle.title = '';
        menu.hidden = false;
      }
      updateButtons();
      if (hasIntent()) connect();
    });
  }).catch(function () { });

  arena.live.on('ROOM_CLOSED', function (payload, envelope) {
    if (envelope.roomId === roomId) { stop(true); box.hidden = true; }
  });
  arena.live.on('ROOM_STATE', function (payload, envelope) {
    if (envelope.roomId !== roomId || !room || !payload || !payload.players) return;
    arena.me().then(function (me) {
      if (!payload.players.some(function (player) { return player.userId === me.userId; })) stop(true);
    });
  });
  arena.live.on('CONNECTED', function (payload) {
    if (!payload || !payload.reconnected || !room) return;
    Promise.all([arena.api.room(roomId), arena.me()]).then(function (results) {
      if (room && !results[0].players.some(function (player) { return player.userId === results[1].userId; })) stop(true);
    }).catch(function (error) {
      if (error && (error.status === 403 || error.status === 404)) stop(true);
    });
  });
  global.addEventListener('pagehide', function () { stop(false); });
})(window);
