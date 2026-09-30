/**
 * Trang thiết lập trận (/games/lobby?game=…&mode=QUICK|VERSUS|SOLO&setId=…):
 * - QUICK: chọn game + bộ rồi tìm đối thủ cùng bộ; cài đặt do hai bên tung đồng xu trong phòng.
 * - VERSUS: chọn đủ cài đặt rồi tạo phòng riêng, gửi mã cho bạn bè.
 * - SOLO: chỉ chọn game + bộ; hỏi – đáp, thẻ và tuỳ chọn chọn ở màn chuẩn bị trong game.
 */
(function () {
  'use strict';

  var arena = window.JlasArena;
  var setup = window.JlasArenaSetup;
  var esc = arena.esc;
  var params = new URLSearchParams(window.location.search);

  var state = {
    catalog: null,
    gameType: params.get('game') || 'NEKO_CATCH',
    mode: ['VERSUS', 'SOLO'].indexOf(params.get('mode')) >= 0 ? params.get('mode') : 'QUICK',
    deck: null,
    detail: null,
    question: 'KANJI',
    answer: 'MEANING',
    options: {},
    submitting: false,
    searching: null,
    searchTimer: null,
    searchPoll: null
  };

  var picker = null;

  var el = {
    title: document.querySelector('[data-setup-title]'),
    modeSeg: document.querySelector('[data-mode-seg]'),
    gamePicker: document.querySelector('[data-game-picker]'),
    deckPicker: document.querySelector('[data-deck-picker]'),
    fieldPicker: document.querySelector('[data-field-picker]'),
    options: document.querySelector('[data-options]'),
    problem: document.querySelector('[data-summary-problem]'),
    submits: document.querySelectorAll('[data-submit]'),
    stepFields: document.querySelector('[data-step-fields]'),
    stepOptions: document.querySelector('[data-step-options]'),
    overlay: document.querySelector('[data-search-overlay]'),
    searchGame: document.querySelector('[data-search-game]'),
    searchDeck: document.querySelector('[data-search-deck]'),
    searchTime: document.querySelector('[data-search-time]'),
    searchHint: document.querySelector('[data-search-hint]'),
    searchCancel: document.querySelector('[data-search-cancel]')
  };

  var TITLES = { QUICK: 'Ghép trận nhanh', VERSUS: 'Phòng với bạn bè', SOLO: 'Chơi luyện tập' };
  var SUBMIT_LABELS = { QUICK: 'Tìm đối thủ', VERSUS: 'Tạo phòng', SOLO: 'Bắt đầu luyện tập' };

  function item() {
    return state.catalog.games.find(function (game) { return game.type === state.gameType; }) || state.catalog.games[0];
  }

  function syncUrl() {
    var next = new URLSearchParams();
    next.set('game', state.gameType);
    next.set('mode', state.mode);
    if (state.deck) next.set('setId', state.deck.id);
    window.history.replaceState(null, '', window.location.pathname + '?' + next.toString());
  }

  function renderMode() {
    var quick = state.mode === 'QUICK';
    var solo = state.mode === 'SOLO';
    el.title.textContent = TITLES[state.mode];
    el.modeSeg.querySelectorAll('[data-value]').forEach(function (button) {
      var checked = button.dataset.value === state.mode;
      button.setAttribute('aria-checked', String(checked));
      button.tabIndex = checked ? 0 : -1;
    });
    el.submits.forEach(function (button) {
      button.textContent = SUBMIT_LABELS[state.mode];
    });
    // Ghép trận nhanh: cặp hỏi–đáp và tuỳ chọn do đồng xu quyết định trong phòng.
    // Luyện tập: game nào cũng có màn chuẩn bị riêng nên ở đây chỉ còn chọn game và bộ thẻ.
    el.stepFields.hidden = quick || solo;
    el.stepOptions.hidden = quick || solo;
  }

  /** Dựng thẻ game một lần (cảnh xem trước chạy liên tục); các lần sau chỉ đổi trạng thái chọn. */
  function renderGames() {
    if (!el.gamePicker.childElementCount) {
      el.gamePicker.innerHTML = state.catalog.games.map(function (game) {
        return '<button type="button" class="ar-game-option ar-game-option--' + esc(game.slug) + '" role="radio"'
          + ' aria-checked="false" tabindex="-1" data-game="' + esc(game.type) + '">'
          + '<span class="ar-game-option__art"><span class="ar-preview" data-game-preview="' + esc(game.slug) + '">'
          + '<img src="' + esc(game.image) + '" alt="" width="960" height="600"></span></span>'
          + '<span><strong>' + esc(game.name) + '</strong><span>' + esc(game.tagline) + '</span></span></button>';
      }).join('');
      if (window.JlasGamePreviews) window.JlasGamePreviews.mountAll(el.gamePicker);
    }
    el.gamePicker.querySelectorAll('[data-game]').forEach(function (button) {
      var checked = button.dataset.game === state.gameType;
      button.setAttribute('aria-checked', String(checked));
      button.tabIndex = checked ? 0 : -1;
    });
  }

  function renderFields() {
    if (!state.detail || state.mode !== 'VERSUS') return;
    setup.renderFieldPicker(el.fieldPicker, {
      detail: state.detail,
      gameType: state.gameType,
      options: state.options,
      question: state.question,
      answer: state.answer,
      onChange: function (question, answer) {
        state.question = question;
        state.answer = answer;
        normalize();
        render();
      }
    });
  }

  function renderOptions() {
    if (state.mode !== 'VERSUS') return;
    setup.renderOptions(el.options, {
      catalogItem: item(),
      mode: state.mode,
      options: state.options,
      detail: state.detail,
      question: state.question,
      answer: state.answer,
      onChange: function (options) {
        state.options = options;
        normalize();
        render();
      }
    });
  }

  /** Ghép nhanh chỉ cần bộ có ít nhất một cặp hỏi–đáp đủ thẻ; cặp cụ thể sẽ tung đồng xu chọn. */
  function quickNeed() {
    return state.gameType === 'MEMORY_MATCH' ? 6 : setup.minWords(state.gameType, {});
  }

  function problem() {
    if (!state.deck) return 'Chọn một bộ flashcard ở bước 2.';
    if (state.deck.draft) return 'Bộ này đang là bản nháp; xuất bản bộ ở trang Flashcard rồi mới chơi được.';
    if (state.mode === 'QUICK') {
      var need = quickNeed();
      var playable = (state.detail && state.detail.pairs || []).some(function (pair) { return pair.usableCards >= need; });
      return playable ? null : 'Bộ "' + state.deck.title + '" chưa có cặp hỏi – đáp nào đủ ' + need + ' thẻ cho ' + item().name + '.';
    }
    var need = setup.minWords(state.gameType, state.options);
    var count = setup.usable(state.detail, state.question, state.answer);
    if (count < need && state.mode === 'SOLO') {
      return 'Bộ "' + state.deck.title + '" chưa có đủ ' + need + ' thẻ có cùng phần hỏi và phần đáp để chơi '
        + item().name + '.';
    }
    if (count < need) {
      return 'Bộ "' + state.deck.title + '" chỉ có ' + count + ' thẻ có đủ ' + arena.fieldLabel(state.question)
        + ' và ' + arena.fieldLabel(state.answer) + '. ' + item().name + ' cần ít nhất ' + need + ' thẻ.';
    }
    return null;
  }

  /** Không còn khung Tóm tắt: chỉ báo vì sao chưa bấm được và bật/tắt nút. */
  function renderSummary() {
    var message = problem();
    el.problem.hidden = !message;
    el.problem.textContent = message || '';
    el.submits.forEach(function (button) {
      button.disabled = !!message || state.submitting;
      button.dataset.loading = String(state.submitting);
    });
  }

  /** Vẽ lại làm mất focus của nút vừa bấm; ghi nhớ nút đó để trả focus cho người dùng bàn phím. */
  function focusKey() {
    var active = document.activeElement;
    if (!active || !active.dataset) return null;
    var seg = active.closest('[data-seg]');
    if (seg && active.dataset.value) return '[data-seg="' + seg.dataset.seg + '"] [data-value="' + active.dataset.value + '"]';
    if (active.dataset.game) return '[data-game="' + active.dataset.game + '"]';
    if (active.closest('[data-mode-seg]') && active.dataset.value) return '[data-mode-seg] [data-value="' + active.dataset.value + '"]';
    if (active.id) return '#' + active.id;
    return null;
  }

  function render() {
    var key = focusKey();
    renderMode();
    renderGames();
    renderFields();
    renderOptions();
    renderSummary();
    syncUrl();
    if (key) {
      var target = document.querySelector(key);
      if (target) target.focus();
    }
  }

  function normalize() {
    state.options = setup.normalizeOptions(item(), state.mode, state.options, state.detail, state.question, state.answer);
    // Luyện tập không có bước hỏi – đáp: tự lấy cặp chơi được (người chơi đổi lại ở màn chuẩn bị).
    if (state.mode === 'SOLO' && state.detail
        && setup.usable(state.detail, state.question, state.answer) < setup.minWords(state.gameType, state.options)) {
      var pair = setup.bestPair(state.detail, state.gameType, state.options);
      state.question = pair.question;
      state.answer = pair.answer;
      state.options = setup.normalizeOptions(item(), state.mode, state.options, state.detail, state.question, state.answer);
    }
  }

  function onDeckSelected(deck, detail) {
    var firstDeck = !state.deck;
    state.deck = deck;
    state.detail = detail;
    var need = setup.minWords(state.gameType, state.options);
    if (firstDeck || setup.usable(detail, state.question, state.answer) < need) {
      var pair = setup.bestPair(detail, state.gameType, state.options);
      state.question = pair.question;
      state.answer = pair.answer;
    }
    normalize();
    render();
  }

  function submit() {
    if (problem() || state.submitting) return;
    state.submitting = true;
    renderSummary();
    var settings = {
      setId: state.deck.id,
      questionField: state.question,
      answerField: state.answer,
      options: state.options
    };
    var request;
    if (state.mode === 'QUICK') {
      request = arena.api.quickMatch(state.gameType, state.deck.id).then(function (ticket) {
        state.submitting = false;
        renderSummary();
        if (ticket.status === 'MATCHED' && ticket.roomId) goToRoom(ticket.roomId);
        else startSearching(ticket);
      });
    } else if (state.mode === 'VERSUS') {
      request = arena.api.createRoom({ gameType: state.gameType, visibility: 'PRIVATE', settings: settings })
        .then(function (room) { goToRoom(room.roomId); });
    } else {
      request = arena.api.solo({ gameType: state.gameType, settings: settings })
        .then(function (launch) { window.location.href = launch.playUrl; });
    }
    request.catch(function (error) {
      state.submitting = false;
      renderSummary();
      el.problem.hidden = false;
      el.problem.textContent = error.message;
      if (window.JlasUI) window.JlasUI.toast(error.message, 'error');
    });
  }

  // ------------------------------------------------------------- quick match

  function goToRoom(roomId) {
    stopSearching();
    window.location.href = '/games/room/' + encodeURIComponent(roomId);
  }

  function formatWait(ms) {
    var seconds = Math.max(0, Math.floor(ms / 1000));
    return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
  }

  function renderSearching() {
    var ticket = state.searching;
    if (!ticket) return;
    el.searchGame.textContent = 'Ghép trận nhanh · ' + ticket.gameName;
    el.searchDeck.textContent = ticket.setTitle;
    el.searchTime.textContent = formatWait(Date.now() - ticket.startedLocal);
    el.searchHint.textContent = ticket.searchingSameGame > 0
      ? ticket.searchingSameGame + ' người khác đang tìm ' + ticket.gameName + ' (có thể với bộ khác). Bạn sẽ được ghép khi có người chọn cùng bộ.'
      : 'Chờ người chọn cùng game và cùng bộ thẻ. Gửi tên bộ cho bạn bè để ghép nhanh hơn.';
  }

  function startSearching(ticket) {
    state.searching = Object.assign({}, ticket, { startedLocal: Date.now() - (ticket.waitedMs || 0) });
    el.overlay.hidden = false;
    renderSearching();
    el.searchCancel.focus();
    window.clearInterval(state.searchTimer);
    state.searchTimer = window.setInterval(renderSearching, 500);
    window.clearInterval(state.searchPoll);
    // SSE báo ngay khi ghép được; hỏi lại định kỳ để cập nhật số người đang tìm và phòng trường hợp lỡ sự kiện.
    state.searchPoll = window.setInterval(function () {
      arena.api.quickMatchStatus().then(function (status) {
        if (status.status === 'MATCHED' && status.roomId) {
          goToRoom(status.roomId);
        } else if (status.status === 'SEARCHING') {
          state.searching.searchingSameGame = status.searchingSameGame;
        } else {
          stopSearching();
          if (window.JlasUI) window.JlasUI.toast('Lượt tìm trận đã hết hạn, bấm "Tìm đối thủ" để tìm lại.', 'info');
        }
      }).catch(function () {});
    }, 4000);
  }

  function stopSearching() {
    state.searching = null;
    window.clearInterval(state.searchTimer);
    window.clearInterval(state.searchPoll);
    el.overlay.hidden = true;
  }

  el.searchCancel.addEventListener('click', function () {
    el.searchCancel.disabled = true;
    arena.api.cancelQuickMatch().catch(function () {}).then(function () {
      el.searchCancel.disabled = false;
      stopSearching();
    });
  });

  arena.live.connect();
  arena.live.on('QUICK_MATCH_FOUND', function (payload) {
    if (payload && payload.roomId) goToRoom(payload.roomId);
  });

  el.modeSeg.addEventListener('click', function (event) {
    var button = event.target.closest('[data-value]');
    if (!button) return;
    state.mode = button.dataset.value;
    normalize();
    render();
  });

  el.gamePicker.addEventListener('click', function (event) {
    var button = event.target.closest('[data-game]');
    if (!button || button.dataset.game === state.gameType) return;
    state.gameType = button.dataset.game;
    state.options = {};
    if (picker) picker.setGame(state.gameType);
    normalize();
    render();
  });

  el.gamePicker.addEventListener('keydown', function (event) {
    if (['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].indexOf(event.key) < 0) return;
    event.preventDefault();
    var games = state.catalog.games;
    var index = games.findIndex(function (game) { return game.type === state.gameType; });
    var step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1;
    state.gameType = games[(index + step + games.length) % games.length].type;
    state.options = {};
    if (picker) picker.setGame(state.gameType);
    normalize();
    render();
    var focused = el.gamePicker.querySelector('[aria-checked="true"]');
    if (focused) focused.focus();
  });

  el.submits.forEach(function (button) { button.addEventListener('click', submit); });

  arena.api.catalog().then(function (catalog) {
    state.catalog = catalog;
    if (!catalog.games.some(function (game) { return game.type === state.gameType; })) {
      state.gameType = catalog.games[0].type;
    }
    normalize();
    render();
    picker = new setup.DeckPicker(el.deckPicker, { onSelect: onDeckSelected, gameType: state.gameType });
    picker.load(true);
    var setId = params.get('setId');
    if (setId) picker.select(setId).catch(function () {});
    // Quay lại trang khi vẫn đang trong hàng chờ: hiện lại màn tìm trận.
    arena.api.quickMatchStatus().then(function (status) {
      if (status.status === 'SEARCHING') {
        state.mode = 'QUICK';
        state.gameType = status.gameType;
        picker.setGame(state.gameType);
        render();
        startSearching(status);
      }
    }).catch(function () {});
  }).catch(function (error) {
    el.gamePicker.innerHTML = '<p class="ar-error-text">' + esc(error.message) + '</p>';
  });
})();
