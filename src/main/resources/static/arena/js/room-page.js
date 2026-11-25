/**
 * Phòng đối kháng (/games/room/{roomId}): 2 chỗ ngồi, sẵn sàng, chủ phòng đổi cài đặt và bắt đầu,
 * đếm ngược rồi cả hai cùng sang trang chơi. Trạng thái phòng đến qua SSE (ROOM_STATE).
 */
(function () {
  'use strict';

  var arena = window.JlasArena;
  var setup = window.JlasArenaSetup;
  var esc = arena.esc;
  var main = document.querySelector('[data-room-id]');
  var roomId = main.dataset.roomId;

  var state = { room: null, meId: null, catalog: null, closed: false, navigating: false, busy: false };
  var draft = null;
  var dialogPicker = null;
  var countdownTimer = null;
  var skinPicker = null;
  var vsSkinPicker = null;
  var returnedFromMatch = new URLSearchParams(window.location.search).get('fromMatch') === '1';
  arena.phoneMedia.addEventListener('change', render);

  var el = {
    status: document.querySelector('[data-room-status]'),
    room: document.querySelector('[data-room]'),
    game: document.querySelector('[data-room-game]'),
    code: document.querySelector('[data-room-code]'),
    visibility: document.querySelector('[data-room-visibility]'),
    connDot: document.querySelector('[data-conn-dot]'),
    connText: document.querySelector('[data-conn-text]'),
    seats: [document.querySelector('[data-seat="0"]'), document.querySelector('[data-seat="1"]')],
    settings: document.querySelector('[data-settings-list]'),
    editSettings: document.querySelector('[data-edit-settings]'),
    actions: document.querySelector('[data-room-actions]'),
    lastResult: document.querySelector('[data-last-result]'),
    lastResultBody: document.querySelector('[data-last-result-body]'),
    dialog: document.querySelector('[data-settings-dialog]'),
    dialogDeck: document.querySelector('[data-dialog-deck]'),
    dialogFields: document.querySelector('[data-dialog-fields]'),
    dialogOptions: document.querySelector('[data-dialog-options]'),
    dialogProblem: document.querySelector('[data-dialog-problem]'),
    saveSettings: document.querySelector('[data-save-settings]'),
    countdown: document.querySelector('[data-countdown]'),
    countdownNumber: document.querySelector('[data-countdown-number]'),
    countdownText: document.querySelector('[data-countdown-text]'),
    vs: document.querySelector('[data-vs]'),
    shell: document.querySelector('[data-room-shell]')
  };

  var vsContext = {
    meId: null,
    busy: false,
    onChoose: function (stepKey, value) {
      arena.api.draftChoose(roomId, stepKey, value).then(applyRoom).catch(function (error) {
        toast(error.message, 'error');
        refresh().catch(function () {});
      });
    },
    onPass: function (stepKey) {
      arena.api.draftPass(roomId, stepKey).then(applyRoom).catch(function (error) {
        toast(error.message, 'error');
        refresh().catch(function () {});
      });
    },
    onReady: function (ready) {
      act(arena.api.ready(roomId, ready));
    },
    onLeave: function () {
      state.closed = true;
      arena.api.leave(roomId).catch(function () {}).then(function () { window.location.href = '/games'; });
    }
  };
  window.JlasVsRoom.mount(el.vs, vsContext);

  function toast(message, type) {
    if (window.JlasUI) window.JlasUI.toast(message, type || 'info');
  }

  function showStatus(html) {
    el.status.hidden = false;
    el.status.innerHTML = html;
    el.room.hidden = true;
  }

  function isHost() {
    return state.room && state.room.hostUserId === state.meId;
  }

  function me() {
    return state.room && state.room.players.find(function (player) { return player.userId === state.meId; });
  }

  /** Hai chữ đầu của 2 từ cuối tên, bỏ phần ghi chú trong ngoặc: "Lê Minh Châu (test)" → "MC". */
  function initials(name) {
    var clean = String(name || '?').replace(/\([^)]*\)/g, ' ').trim() || '?';
    return clean.split(/\s+/).slice(-2).map(function (part) { return part.charAt(0); }).join('').toUpperCase();
  }

  // ------------------------------------------------------------------ render

  function render() {
    var room = state.room;
    if (!room) return;
    if (!arena.canPlayGame(room.gameType)) {
      el.vs.hidden = true;
      el.shell.hidden = false;
      el.countdown.hidden = true;
      window.clearInterval(countdownTimer);
      document.body.classList.remove('is-vs-room');
      showStatus('<h1>Chơi Bắn máy bay trên máy tính</h1>'
        + '<p>Game cần bàn phím để gõ đáp án. Bạn hãy mở bằng máy tính để chơi.</p>'
        + '<a class="ar-btn ar-btn--primary" href="/games">Về trang Trò chơi</a>');
      return;
    }
    if (room.quickMatch) {
      // Ghép trận nhanh: màn VS toàn khung, tung đồng xu chọn cài đặt.
      el.status.hidden = true;
      el.shell.hidden = true;
      el.vs.hidden = false;
      document.body.classList.add('is-vs-room');
      document.title = room.gameName + ' · Đấu tay đôi - KotsuKotsu';
      window.JlasVsRoom.render(room);
      var vsSkinRoot = el.vs.querySelector('[data-vs-skins]');
      var skinGame = room.gameType === 'NEKO_CATCH' || room.gameType === 'AIR_DEFENSE';
      if (vsSkinRoot && skinGame && !vsSkinPicker) vsSkinPicker = window.JlasRoomSkins.mount(vsSkinRoot, room.gameType, function (busy) {
        state.skinBusy = busy;
        window.JlasVsRoom.setBusy(busy); // keeps Ready disabled while a purchase is in flight
      });
      if (vsSkinPicker) vsSkinPicker.setDisabled(room.status !== 'WAITING' || !!(me() && me().ready));
      renderCountdown(room);
      return;
    }
    el.status.hidden = true;
    el.vs.hidden = true;
    el.shell.hidden = false;
    document.body.classList.remove('is-vs-room');
    el.room.hidden = false;
    document.title = 'Phòng ' + room.code + ' · ' + room.gameName + ' - KotsuKotsu';
    el.game.textContent = room.gameName + (room.gameType === 'DRAW_GUESS' ? ' · Chơi cùng bạn' : ' · Đấu tay đôi');
    el.code.textContent = room.code;
    el.visibility.textContent = 'Chỉ người có mã hoặc link mời mới vào được';
    var skinRoot = document.querySelector('[data-room-skins]');
    skinRoot.hidden = room.gameType !== 'NEKO_CATCH' && room.gameType !== 'AIR_DEFENSE';
    if (!skinRoot.hidden && !skinPicker) skinPicker = window.JlasRoomSkins.mount(skinRoot, room.gameType, function (busy) {
      state.skinBusy = busy;
      renderActions(state.room);
    });
    if (skinPicker) skinPicker.setDisabled(room.status !== 'WAITING' || !!(!isHost() && me() && me().ready));
    renderSeats(room);
    renderSettings(room);
    renderActions(room);
    renderLastResult(room);
    renderCountdown(room);
  }

  function renderSeats(room) {
    for (var slot = 0; slot < 2; slot++) {
      var seat = el.seats[slot];
      var player = room.players.find(function (p) { return p.slot === slot; });
      seat.classList.toggle('is-empty', !player);
      seat.classList.toggle('is-ready', !!(player && player.ready));
      if (!player) {
        seat.innerHTML = '<div class="ar-avatar" aria-hidden="true">?</div><h2>Đang chờ đối thủ</h2>'
          + '<p class="ar-seat__wait">Gửi mã <strong class="ar-code">' + esc(room.code) + '</strong> hoặc link mời cho bạn bè.</p>';
        continue;
      }
      var tags = [];
      if (player.host) tags.push('<span class="ar-chip ar-chip--accent"><i class="fa-solid fa-crown" aria-hidden="true"></i> Chủ phòng</span>');
      if (player.userId === state.meId) tags.push('<span class="ar-chip">Bạn</span>');
      tags.push(player.ready
        ? '<span class="ar-chip ar-chip--ok"><i class="fa-solid fa-check" aria-hidden="true"></i> Sẵn sàng</span>'
        : '<span class="ar-chip ar-chip--gold">Chưa sẵn sàng</span>');
      if (!player.online) tags.push('<span class="ar-chip ar-chip--bad">Mất kết nối</span>');
      var avatar = player.avatarUrl
        ? '<img src="' + esc(player.avatarUrl) + '" alt="">'
        : esc(initials(player.displayName));
      // Khung avatar + màu tên người chơi mua ở Cửa hàng (components/avatar-frame.js).
      var frames = window.JlasAvatarFrame;
      var face = '<div class="ar-avatar" aria-hidden="true">' + avatar + '</div>';
      seat.innerHTML = (frames ? frames.host(face, player.avatarFrame, 'ar-avatar-host') : face)
        + '<h2' + (frames ? frames.nameAttr(player.nameStyle) : '') + '>' + esc(player.displayName) + '</h2>'
        + '<div class="ar-seat__tags">' + tags.join('') + '</div>';
      if (frames) frames.mountAll(seat);
    }
  }

  function renderSettings(room) {
    var settings = room.settings || {};
    var rows = settings.jlpt ? [['Cấp JLPT', settings.jlpt]] : [
      ['Bộ flashcard', settings.setTitle || '—'],
      ['Cài đặt trận', 'Tung đồng xu khi đủ 2 người']
    ];
    el.settings.innerHTML = rows.map(function (row) {
      return '<dt>' + esc(row[0]) + '</dt><dd>' + esc(row[1]) + '</dd>';
    }).join('');
    el.editSettings.hidden = !settings.jlpt || !isHost() || room.status !== 'WAITING';
  }

  function renderActions(room) {
    var mine = me();
    var html = '';
    if (room.status === 'PLAYING' && room.playUrl) {
      html += '<p class="ar-room-actions__hint">Trận đang diễn ra.</p>'
        + '<a class="ar-btn ar-btn--primary ar-btn--lg ar-btn--block" href="' + esc(room.playUrl) + '">Vào trận</a>';
    } else if (room.status === 'COUNTDOWN') {
      html += '<p class="ar-room-actions__hint">Trận sắp bắt đầu…</p>';
    } else if (isHost()) {
      var guest = room.players.find(function (p) { return p.userId !== state.meId; });
      var hint = !guest ? (room.gameType === 'DRAW_GUESS' ? 'Đang chờ bạn vào phòng.' : 'Đang chờ đối thủ vào phòng.')
        : !guest.ready ? 'Chờ ' + guest.displayName + ' bấm "Sẵn sàng".'
          : guest.displayName + ' đã sẵn sàng. Bấm bắt đầu khi bạn muốn.';
      html += '<p class="ar-room-actions__hint">' + esc(hint) + '</p>'
        + '<button type="button" class="ar-btn ar-btn--primary ar-btn--lg ar-btn--block" data-action="start"'
        + (guest && guest.ready && !state.busy && !state.skinBusy ? '' : ' disabled') + '>'
        + '<i class="fa-solid fa-play" aria-hidden="true"></i> ' + (room.lastResult ? 'Chơi lại' : 'Bắt đầu') + '</button>';
    } else if (mine) {
      html += '<p class="ar-room-actions__hint">' + (mine.ready
        ? 'Bạn đã sẵn sàng. Chờ chủ phòng bắt đầu.'
        : 'Xem cài đặt trận rồi bấm "Sẵn sàng".') + '</p>'
        + '<button type="button" class="ar-btn ar-btn--lg ar-btn--block ' + (mine.ready ? '' : 'ar-btn--ok') + '" data-action="ready"'
        + (state.busy || state.skinBusy ? ' disabled' : '') + '>'
        + (mine.ready ? 'Huỷ sẵn sàng' : '<i class="fa-solid fa-check" aria-hidden="true"></i> Sẵn sàng') + '</button>';
    }
    html += '<button type="button" class="ar-btn ar-btn--danger ar-btn--block" data-action="leave">'
      + '<i class="fa-solid fa-door-open" aria-hidden="true"></i> Rời phòng</button>';
    el.actions.innerHTML = html;
  }

  function renderLastResult(room) {
    var result = room.lastResult;
    el.lastResult.hidden = !result;
    if (!result) return;
    if (result.gameType === 'DRAW_GUESS') {
      el.lastResultBody.textContent = 'Đã kết thúc phiên Vẽ & Đoán.';
      return;
    }
    var headline = result.draw ? 'Hoà!'
      : result.winnerUserId === state.meId ? 'Bạn thắng!'
        : result.winnerUserId ? 'Đối thủ thắng' : 'Không có người thắng';
    el.lastResultBody.innerHTML = '<p class="ar-kicker">' + esc(arena.gameName(result.gameType)) + ' · ' + esc(headline) + '</p>'
      + '<ol class="ar-result-lines">' + result.lines.map(function (line) {
        return '<li class="' + (line.winner ? 'is-winner' : '') + '"><span class="ar-rank">' + line.rank + '</span>'
          + '<span><strong>' + esc(line.displayName) + (line.userId === state.meId ? ' (bạn)' : '') + '</strong>'
          + '<small>' + esc(line.detail) + '</small></span><b>' + line.score + '</b></li>';
      }).join('') + '</ol>';
  }

  function renderCountdown(room) {
    window.clearInterval(countdownTimer);
    if (room.status !== 'COUNTDOWN' || !room.countdownEndsAt) {
      el.countdown.hidden = true;
      return;
    }
    el.countdown.hidden = false;
    function tick() {
      var left = Math.ceil((room.countdownEndsAt - arena.live.serverNow()) / 1000);
      if (left > 0) {
        el.countdownNumber.textContent = String(left);
        el.countdownText.textContent = room.gameName + ' · ' + (room.settings && room.settings.setTitle || '');
      } else {
        el.countdownNumber.textContent = 'GO';
        el.countdownText.textContent = 'Đang vào trận…';
        window.clearInterval(countdownTimer);
      }
    }
    tick();
    countdownTimer = window.setInterval(tick, 200);
  }

  // ------------------------------------------------------------------ state

  function applyRoom(room) {
    if (!room || room.roomId !== roomId) return;
    if (state.room && room.version < state.room.version) return;
    var wasPlaying = state.room && state.room.status === 'PLAYING';
    state.room = room;
    render();
    if (wasPlaying && room.status === 'WAITING' && room.lastResult) {
      toast('Trận đã kết thúc. Bấm "Sẵn sàng" để đấu lại.', 'info');
    }
  }

  function goToPlay(url) {
    if (state.navigating || !url || (state.room && !arena.canPlayGame(state.room.gameType))) return;
    state.navigating = true;
    window.location.href = url;
  }

  function refresh() {
    return arena.api.room(roomId).then(applyRoom);
  }

  function act(promise) {
    state.busy = true;
    window.JlasVsRoom.setBusy(true);
    render();
    return promise.then(function (room) {
      state.busy = false;
      window.JlasVsRoom.setBusy(false);
      if (room) applyRoom(room);
      else render();
    }).catch(function (error) {
      state.busy = false;
      window.JlasVsRoom.setBusy(false);
      render();
      toast(error.message, 'error');
    });
  }

  el.actions.addEventListener('click', function (event) {
    var button = event.target.closest('[data-action]');
    if (!button || button.disabled) return;
    var action = button.dataset.action;
    if (action === 'start') {
      act(arena.api.start(roomId));
    } else if (action === 'ready') {
      act(arena.api.ready(roomId, !(me() && me().ready)));
    } else if (action === 'leave') {
      if (state.room && state.room.status === 'PLAYING'
        && !window.confirm('Trận đang diễn ra. Rời phòng bây giờ sẽ bị tính là thua. Bạn chắc chứ?')) return;
      state.closed = true;
      arena.api.leave(roomId).catch(function () {}).then(function () {
        window.location.href = '/games';
      });
    }
  });

  function copy(text, done) {
    var write = navigator.clipboard && navigator.clipboard.writeText
      ? navigator.clipboard.writeText(text)
      : Promise.reject(new Error('clipboard'));
    write.then(function () { toast(done, 'success'); }).catch(function () {
      window.prompt('Hãy tự sao chép:', text);
    });
  }

  document.querySelector('[data-copy-code]').addEventListener('click', function () {
    if (state.room) copy(state.room.code, 'Đã sao chép mã phòng ' + state.room.code);
  });
  document.querySelector('[data-copy-link]').addEventListener('click', function () {
    copy(window.location.origin + '/games/room/' + encodeURIComponent(roomId), 'Đã sao chép link mời');
  });

  // ------------------------------------------------------------ settings dialog

  function catalogItem() {
    return state.catalog.games.find(function (game) { return game.type === state.room.gameType; });
  }

  function renderDialog() {
    var item = catalogItem();
    var advanced = ['NEKO_CATCH', 'AIR_DEFENSE', 'MEMORY_MATCH'].indexOf(state.room.gameType) >= 0;
    var gameSetup = document.querySelector('[data-dialog-game-setup]');
    el.dialogDeck.parentElement.classList.toggle('ar-room-deck-summary', advanced);
    el.dialogDeck.parentElement.querySelector('h2').hidden = advanced;
    document.querySelector('[data-dialog-deck-summary]').hidden = !advanced;
    document.querySelector('[data-dialog-deck-name]').textContent = draft.deck ? draft.deck.title : state.room.settings.setTitle;
    document.querySelector('[data-dialog-toggle-decks]').setAttribute('aria-expanded', String(!!draft.changeDeck));
    el.dialogDeck.hidden = advanced && !draft.changeDeck;
    el.dialogFields.parentElement.hidden = advanced;
    el.dialogOptions.parentElement.hidden = advanced;
    gameSetup.hidden = !advanced;
    if (advanced) {
      var chosen = window.JlasRoomSettings.render(gameSetup, {
        cards: draft.cards, question: draft.question, answer: draft.answer, options: draft.options,
        gameType: state.room.gameType, manualDuration: draft.manualDuration,
        onChange: function (next) {
          draft.question = next.question; draft.answer = next.answer;
          draft.options = next.options; draft.manualDuration = next.manualDuration;
          renderDialog();
        }
      });
      if (chosen.options) draft.options = chosen.options;
      el.dialogProblem.hidden = !chosen.problem;
      el.dialogProblem.textContent = draft.cardError || chosen.problem || '';
      el.saveSettings.disabled = !!chosen.problem || !!draft.cardError;
      if (draft.cardError) el.dialogProblem.hidden = false;
      return;
    }
    if (draft.detail) {
      setup.renderFieldPicker(el.dialogFields, {
        detail: draft.detail,
        gameType: state.room.gameType,
        options: draft.options,
        question: draft.question,
        answer: draft.answer,
        onChange: function (question, answer) {
          draft.question = question;
          draft.answer = answer;
          draft.options = setup.normalizeOptions(item, 'VERSUS', draft.options, draft.detail, question, answer);
          renderDialog();
        }
      });
    }
    setup.renderOptions(el.dialogOptions, {
      catalogItem: item,
      mode: 'VERSUS',
      options: draft.options,
      detail: draft.detail,
      question: draft.question,
      answer: draft.answer,
      onChange: function (options) {
        draft.options = setup.normalizeOptions(item, 'VERSUS', options, draft.detail, draft.question, draft.answer);
        renderDialog();
      }
    });
    var need = setup.minWords(state.room.gameType, draft.options);
    var count = setup.usable(draft.detail, draft.question, draft.answer);
    var problem = !draft.deck ? 'Chọn một bộ flashcard.'
      : count < need ? 'Cặp hỏi/đáp này chỉ có ' + count + ' thẻ dùng được; cần ít nhất ' + need + '.' : null;
    el.dialogProblem.hidden = !problem;
    el.dialogProblem.textContent = problem || '';
    el.saveSettings.disabled = !!problem;
  }

  el.editSettings.addEventListener('click', function () {
    var settings = state.room.settings;
    if (settings.jlpt) {
      var level = document.getElementById('ar-room-jlpt');
      if (!level.options.length) {
        catalogItem().choices.jlpt.forEach(function (value) { level.add(new Option(value, value)); });
      }
      level.value = settings.jlpt;
      document.querySelector('[data-dialog-level]').hidden = false;
      [el.dialogDeck, el.dialogFields, el.dialogOptions].forEach(function (part) { part.parentElement.hidden = true; });
      el.dialogProblem.hidden = true;
      el.saveSettings.disabled = false;
      el.dialog.showModal();
      return;
    }
    draft = {
      deck: null,
      cards: null,
      cardError: null,
      changeDeck: false,
      manualDuration: Array.isArray(settings.options && settings.options.cardIds),
      detail: null,
      question: settings.questionField,
      answer: settings.answerField,
      options: Object.assign({}, settings.options)
    };
    if (!dialogPicker) {
      dialogPicker = new setup.DeckPicker(el.dialogDeck, {
        gameType: state.room.gameType,
        onSelect: function (deck, detail) {
          var changedDeck = draft.deck && draft.deck.id !== deck.id;
          if (draft.deck) draft.changeDeck = false;
          draft.deck = deck;
          draft.detail = detail;
          if (['NEKO_CATCH', 'AIR_DEFENSE', 'MEMORY_MATCH'].indexOf(state.room.gameType) >= 0) {
            if (changedDeck) { draft.options.cardIds = null; draft.manualDuration = false; }
            draft.cards = null; draft.cardError = null;
            var targetDraft = draft;
            arena.api.get('/api/games/decks/' + encodeURIComponent(deck.id) + '/cards').then(function (cards) {
              if (draft !== targetDraft || draft.deck.id !== deck.id) return;
              draft.cards = cards; renderDialog();
            }).catch(function (error) {
              if (draft !== targetDraft) return;
              draft.cardError = error.message; renderDialog();
            });
            renderDialog();
            return;
          }
          var need = setup.minWords(state.room.gameType, draft.options);
          if (changedDeck && setup.usable(detail, draft.question, draft.answer) < need) {
            var pair = setup.bestPair(detail, state.room.gameType, draft.options);
            draft.question = pair.question;
            draft.answer = pair.answer;
          }
          draft.options = setup.normalizeOptions(catalogItem(), 'VERSUS', draft.options, detail, draft.question, draft.answer);
          renderDialog();
        }
      });
      dialogPicker.load(true);
    }
    dialogPicker.select(settings.setId).catch(function () {});
    renderDialog();
    if (typeof el.dialog.showModal === 'function') el.dialog.showModal();
    else el.dialog.setAttribute('open', '');
  });

  document.querySelectorAll('[data-close-dialog]').forEach(function (button) {
    button.addEventListener('click', function () { el.dialog.close(); });
  });
  document.querySelector('[data-dialog-toggle-decks]').addEventListener('click', function () {
    draft.changeDeck = !draft.changeDeck;
    renderDialog();
  });

  el.saveSettings.addEventListener('click', function () {
    var puzzle = !!state.room.settings.jlpt;
    if (!puzzle && (!draft || !draft.deck)) return;
    el.saveSettings.dataset.loading = 'true';
    el.saveSettings.disabled = true;
    arena.api.updateSettings(roomId, puzzle ? { jlpt: document.getElementById('ar-room-jlpt').value } : {
      setId: draft.deck.id,
      questionField: draft.question,
      answerField: draft.answer,
      options: draft.options
    }).then(function (room) {
      applyRoom(room);
      el.dialog.close();
      toast('Đã lưu cài đặt. Đối thủ cần bấm "Sẵn sàng" lại.', 'success');
    }).catch(function (error) {
      el.dialogProblem.hidden = false;
      el.dialogProblem.textContent = error.message;
    }).then(function () {
      el.saveSettings.dataset.loading = 'false';
      el.saveSettings.disabled = false;
    });
  });

  // ------------------------------------------------------------------- live

  arena.live.on('ROOM_STATE', function (room, envelope) {
    if (envelope.roomId === roomId) applyRoom(room);
  });
  arena.live.on('ROOM_STARTED', function (payload, envelope) {
    if (envelope.roomId === roomId) goToPlay(payload.playUrl);
  });
  arena.live.on('ROOM_NOTICE', function (payload, envelope) {
    if (envelope.roomId === roomId && payload && payload.message) toast(payload.message, 'info');
  });
  arena.live.on('ROOM_CLOSED', function (payload, envelope) {
    if (envelope.roomId !== roomId) return;
    state.closed = true;
    showStatus('<p><strong>Phòng đã đóng.</strong> ' + esc(payload && payload.message || '') + '</p>'
      + '<a class="ar-btn ar-btn--primary" href="/games">Về trang Trò chơi</a>');
  });
  arena.live.on('CONNECTED', function (payload) {
    if (payload && payload.reconnected && !state.closed) refresh().catch(function () {});
  });
  arena.live.onStatus(function (status) {
    el.connDot.className = 'ar-dot is-' + (status === 'idle' ? 'connecting' : status);
    el.connText.textContent = status === 'online' ? 'Đã kết nối' : status === 'offline' ? 'Mất kết nối, đang thử lại' : 'Đang kết nối';
    window.JlasVsRoom.setConnection(status);
  });

  // ------------------------------------------------------------------- boot

  function enter() {
    return arena.api.room(roomId).catch(function (error) {
      // Mở link mời khi chưa ở trong phòng: tự vào phòng nếu còn chỗ.
      if (error.status === 403) return arena.api.joinRoom(roomId);
      throw error;
    });
  }

  Promise.all([arena.me(), arena.api.catalog(), enter()]).then(function (results) {
    state.meId = results[0].userId;
    vsContext.meId = state.meId;
    state.catalog = results[1];
    arena.live.connect();
    applyRoom(results[2]);
    if (state.room.status === 'PLAYING' && state.room.playUrl && !returnedFromMatch) {
      // Quay lại phòng khi trận của mình vẫn đang chạy: đưa thẳng về trận.
      goToPlay(state.room.playUrl);
    }
  }).catch(function (error) {
    showStatus('<p><strong>Không vào được phòng.</strong> ' + esc(error.message) + '</p>'
      + '<a class="ar-btn ar-btn--primary" href="/games">Về trang Trò chơi</a>');
  });
})();
