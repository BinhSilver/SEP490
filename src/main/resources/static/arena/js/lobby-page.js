/**
 * Trang thiết lập trận (/games/lobby?game=…&mode=QUICK|VERSUS|SOLO&setId=…):
 * - QUICK: chọn game + bộ rồi tìm đối thủ cùng bộ; cài đặt do hai bên tung đồng xu trong phòng.
 * - VERSUS: chọn game + bộ rồi tạo phòng, gửi mã cho bạn bè; đủ hai người thì tung đồng xu chọn cài đặt.
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
  var mobile = window.matchMedia('(max-width: 900px)');
  var savedHistory = window.history.state || {};
  // Nút trên thẻ game đã chọn cả game lẫn chế độ: mở thẳng bộ thẻ trên mobile.
  var entryStep = params.get('game') && ['QUICK', 'VERSUS', 'SOLO'].indexOf(params.get('mode')) >= 0 ? 3 : 1;
  var mobileStep = [1, 2, 3].indexOf(savedHistory.jlasArenaSetupStep) >= 0 ? savedHistory.jlasArenaSetupStep : entryStep;
  var historyId = savedHistory.jlasArenaSetupId || Date.now().toString(36) + Math.random().toString(36).slice(2);

  var el = {
    title: document.querySelector('[data-setup-title]'),
    modeSeg: document.querySelector('[data-mode-seg]'),
    gamePicker: document.querySelector('[data-game-picker]'),
    deckPicker: document.querySelector('[data-deck-picker]'),
    problem: document.querySelector('[data-summary-problem]'),
    submits: document.querySelectorAll('[data-submit]'),
    stepMode: document.querySelector('[data-step-mode]'),
    stepGame: document.querySelector('[data-step-game]'),
    stepDeck: document.querySelector('[data-step-deck]'),
    mobileMode: document.querySelector('[data-mobile-mode]'),
    mobileBar: document.querySelector('.ar-mobile-bar'),
    mobileSubmit: document.querySelector('.ar-mobile-bar [data-submit]'),
    error: document.querySelector('[data-setup-error]'),
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
    var games = availableGames();
    return games.find(function (game) { return game.type === state.gameType; }) || games[0];
  }

  function availableGames() {
    return state.catalog.games.filter(function (game) { return arena.canPlayGame(game.type); });
  }

  arena.phoneMedia.addEventListener('change', function () {
    if (!state.catalog) return;
    if (!arena.canPlayGame(state.gameType)) {
      state.gameType = availableGames()[0].type;
      state.options = {};
      if (picker) picker.setGame(state.gameType);
      mobileStep = 2;
      if (mobile.matches) window.history.replaceState(stepHistory(mobileStep), '');
      normalize();
    }
    render();
  });

  function syncUrl() {
    var next = new URLSearchParams();
    next.set('game', state.gameType);
    next.set('mode', state.mode);
    if (state.deck) next.set('setId', state.deck.id);
    window.history.replaceState(window.history.state, '', window.location.pathname + '?' + next.toString());
  }

  function stepHistory(step) {
    return Object.assign({}, window.history.state, { jlasArenaSetupStep: step, jlasArenaSetupId: historyId });
  }

  function renderMode() {
    el.title.textContent = TITLES[state.mode];
    el.modeSeg.querySelectorAll('[data-value]').forEach(function (button) {
      var checked = button.dataset.value === state.mode;
      button.setAttribute('aria-checked', String(checked));
      button.tabIndex = checked ? 0 : -1;
    });
    el.submits.forEach(function (button) {
      button.textContent = SUBMIT_LABELS[state.mode];
    });
  }

  /** Mobile dùng lại các bộ chọn hiện có để giữ nguyên lựa chọn khi quay lại hoặc đổi kích thước. */
  function renderWizard() {
    var isMobile = mobile.matches;
    if (isMobile && (!window.history.state || window.history.state.jlasArenaSetupId !== historyId)) {
      window.history.replaceState(stepHistory(mobileStep), '');
    }
    var modeParent = isMobile ? el.mobileMode : el.stepGame;
    if (el.modeSeg.parentElement !== modeParent) {
      modeParent.insertBefore(el.modeSeg, isMobile ? null : el.gamePicker);
    }
    el.stepMode.hidden = !isMobile || mobileStep !== 1;
    el.stepGame.hidden = isMobile && mobileStep !== 2;
    el.stepDeck.hidden = isMobile && mobileStep !== 3;
    el.stepGame.querySelector('.ar-step__no').textContent = isMobile ? '2' : '1';
    el.stepDeck.querySelector('.ar-step__no').textContent = isMobile ? '3' : '2';
    el.mobileBar.hidden = !isMobile || mobileStep !== 3;
    el.mobileSubmit.hidden = !isMobile || mobileStep !== 3;
  }

  function focusStep() {
    var heading = [el.stepMode, el.stepGame, el.stepDeck][mobileStep - 1].querySelector('h2');
    heading.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: 'instant' });
  }

  function changeStep(step) {
    if (!mobile.matches || state.submitting) return;
    var nextStep = Math.max(1, Math.min(3, step));
    if (nextStep === mobileStep) return;
    mobileStep = nextStep;
    window.history.pushState(stepHistory(mobileStep), '', window.location.href);
    renderWizard();
    focusStep();
  }

  // Nút Back/Forward của máy chuyển giữa các bước, không xoá những lựa chọn đã chọn.
  window.addEventListener('popstate', function (event) {
    var entry = event.state;
    if (!entry || entry.jlasArenaSetupId !== historyId) return;
    mobileStep = entry.jlasArenaSetupStep;
    renderWizard();
    syncUrl();
    if (mobile.matches) focusStep();
  });
  mobile.addEventListener('change', function () {
    var active = document.activeElement;
    renderWizard();
    if (mobile.matches && active && active.closest('[hidden]')) focusStep();
  });

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
      button.hidden = !arena.canPlayGame(button.dataset.game);
      var checked = button.dataset.game === state.gameType;
      button.setAttribute('aria-checked', String(checked));
      button.tabIndex = checked ? 0 : -1;
    });
  }

  /** Ghép nhanh chỉ cần bộ có ít nhất một cặp hỏi–đáp đủ thẻ; cặp cụ thể sẽ tung đồng xu chọn. */
  function quickNeed() {
    return state.gameType === 'MEMORY_MATCH' ? 6 : setup.minWords(state.gameType, {});
  }

  function problem() {
    if (!arena.canPlayGame(state.gameType)) return 'Bắn máy bay cần bàn phím. Hãy mở game trên máy tính.';
    if (!state.deck) return 'Chọn một bộ flashcard để tiếp tục.';
    if (state.deck.draft) return 'Bộ này đang là bản nháp; xuất bản bộ ở trang Flashcard rồi mới chơi được.';
    if (state.mode !== 'SOLO') {
      var need = quickNeed();
      var playable = (state.detail && state.detail.pairs || []).some(function (pair) { return pair.usableCards >= need; });
      return playable ? null : 'Bộ "' + state.deck.title + '" chưa có cặp hỏi – đáp nào đủ ' + need + ' thẻ cho ' + item().name + '.';
    }
    var need = setup.minWords(state.gameType, state.options);
    var count = setup.usable(state.detail, state.question, state.answer);
    if (count < need) {
      return 'Bộ "' + state.deck.title + '" chưa có đủ ' + need + ' thẻ có cùng phần hỏi và phần đáp để chơi '
        + item().name + '.';
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
    renderWizard();
    renderGames();
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
    if (problem() || state.submitting || (mobile.matches && mobileStep !== 3)) return;
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

  function selectMode(value) {
    state.mode = value;
    if (state.catalog) {
      normalize();
      render();
    } else renderMode();
  }

  el.modeSeg.addEventListener('click', function (event) {
    var button = event.target.closest('[data-value]');
    if (!button) return;
    selectMode(button.dataset.value);
    changeStep(2);
  });

  el.modeSeg.addEventListener('keydown', function (event) {
    if (['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].indexOf(event.key) < 0) return;
    event.preventDefault();
    var buttons = Array.prototype.slice.call(el.modeSeg.querySelectorAll('[data-value]'));
    var index = buttons.indexOf(event.target.closest('[data-value]'));
    var direction = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1;
    var next = buttons[(index + direction + buttons.length) % buttons.length];
    selectMode(next.dataset.value);
    next.focus();
  });

  el.gamePicker.addEventListener('click', function (event) {
    var button = event.target.closest('[data-game]');
    if (!button || !arena.canPlayGame(button.dataset.game)) return;
    if (button.dataset.game !== state.gameType) {
      state.gameType = button.dataset.game;
      state.options = {};
      if (picker) picker.setGame(state.gameType);
      normalize();
      render();
    }
    changeStep(3);
  });

  el.gamePicker.addEventListener('keydown', function (event) {
    if (['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].indexOf(event.key) < 0) return;
    event.preventDefault();
    var games = availableGames();
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

  renderMode();
  renderWizard();

  arena.api.catalog().then(function (catalog) {
    state.catalog = catalog;
    if (!availableGames().some(function (game) { return game.type === state.gameType; })) {
      state.gameType = availableGames()[0].type;
      if (mobileStep === 3) {
        mobileStep = 2;
        if (mobile.matches) window.history.replaceState(stepHistory(mobileStep), '');
      }
    }
    normalize();
    render();
    picker = new setup.DeckPicker(el.deckPicker, { onSelect: onDeckSelected, gameType: state.gameType });
    picker.load(true);
    var setId = params.get('setId');
    if (setId) picker.select(setId).catch(function () {});
    // Quay lại trang khi vẫn đang trong hàng chờ: hiện lại màn tìm trận.
    arena.api.quickMatchStatus().then(function (status) {
      if (status.status === 'SEARCHING' && arena.canPlayGame(status.gameType)) {
        state.mode = 'QUICK';
        state.gameType = status.gameType;
        picker.setGame(state.gameType);
        render();
        startSearching(status);
      }
    }).catch(function () {});
  }).catch(function (error) {
    el.error.hidden = false;
    el.error.textContent = error.message;
  });
})();
