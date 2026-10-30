/**
 * Màn VS của phòng ghép trận nhanh (kiểu Master Duel): hai người chơi hai bên, vòng xoáy + chữ VS ở giữa,
 * mỗi cài đặt tung một đồng xu; ai ra mặt ngửa được chọn hoặc nhường quyền chọn; chọn xong thì bấm Sẵn sàng.
 * room-page.js gọi JlasVsRoom.render(room) mỗi khi có ROOM_STATE mới.
 */
(function (global) {
  'use strict';

  var arena = global.JlasArena;
  var esc = arena.esc;
  var FLIP_MS = 1900;
  var HEROES = {
    NEKO_CATCH: ['/arena/images/vs/neko-left.webp', '/arena/images/vs/neko-right.webp'],
    MEMORY_MATCH: ['/arena/images/vs/memory-left.webp', '/arena/images/vs/memory-right.webp'],
    AIR_DEFENSE: ['/arena/images/vs/air-left.png', '/arena/images/vs/air-right.png']
  };
  // Luôn tung đồng xu có hoạt ảnh, không theo prefers-reduced-motion của hệ điều hành (quyết định sản phẩm).
  var reduceMotion = false;

  var root = null;
  var ctx = null;
  var room = null;
  var tickTimer = null;
  var lastToss = null;
  var tossLandsAt = 0;

  function initials(name) {
    var clean = String(name || '?').replace(/\([^)]*\)/g, ' ').trim() || '?';
    return clean.split(/\s+/).slice(-2).map(function (part) { return part.charAt(0); }).join('').toUpperCase();
  }

  function playerBySlot(slot) {
    return room.players.find(function (player) { return player.slot === slot; }) || null;
  }

  function player(userId) {
    return room.players.find(function (p) { return p.userId === userId; }) || null;
  }

  function nameOf(userId) {
    if (userId === ctx.meId) return 'Bạn';
    var p = player(userId);
    return p ? p.displayName : 'Đối thủ';
  }

  function sideOf(userId) {
    var p = player(userId);
    return p && p.slot === 1 ? 'right' : 'left';
  }

  function otherId(userId) {
    var p = room.players.find(function (candidate) { return candidate.userId !== userId; });
    return p ? p.userId : null;
  }

  /** "Đi trước/Đi sau" chỉ đúng với người đã chọn, nên đổi thành tên người lật trước. */
  function chosenText(step) {
    if (step.key !== 'first' || !step.chosenValue) return step.chosenLabel || '—';
    var first = step.chosenValue === 'FIRST' ? step.chosenByUserId : otherId(step.chosenByUserId);
    return nameOf(first) + ' đi trước';
  }

  function build() {
    root.innerHTML = ''
      + '<div class="vs__bg" aria-hidden="true"><i class="vs__glow vs__glow--left"></i><i class="vs__glow vs__glow--right"></i><i class="vs__scan"></i></div>'
      + '<header class="vs__top">'
      + '  <div><p class="vs__kicker" data-vs-game></p><p class="vs__deck">Bộ <strong data-vs-deck></strong></p></div>'
      + '  <span class="vs__conn"><span class="ar-dot" data-vs-dot aria-hidden="true"></span><span data-vs-conn>Đang kết nối</span></span>'
      + '</header>'
      + '<ol class="vs__steps" data-vs-steps aria-label="Các cài đặt của trận"></ol>'
      + '<div class="vs__stage">'
      + '  <figure class="vs__hero vs__hero--left" data-vs-hero="0"><img alt=""></figure>'
      + '  <div class="vs__core">'
      + '    <div class="vs__vortex" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div>'
      + '    <div class="vs__coins" data-vs-coins hidden aria-hidden="true">' + coinHtml(0) + coinHtml(1) + '</div>'
      + '    <strong class="vs__mark" data-vs-mark aria-hidden="true">VS</strong>'
      + '  </div>'
      + '  <figure class="vs__hero vs__hero--right" data-vs-hero="1"><img alt=""></figure>'
      + '</div>'
      + '<div class="vs__banner" data-vs-banner role="status" aria-live="polite"></div>'
      + '<div class="vs__panel" data-vs-panel></div>'
      + '<div class="vs__plates">'
      + '  <div class="vs__plate vs__plate--left" data-vs-plate="0"></div>'
      + '  <div class="vs__plate vs__plate--right" data-vs-plate="1"></div>'
      + '</div>';
    root.addEventListener('click', onClick);
  }

  /** Đồng xu của một người chơi: mặt trước khắc cáo (thắng), mặt sau chữ JLAS. */
  function coinHtml(slot) {
    return '<div class="vs__coin vs__coin--' + (slot ? 'right' : 'left') + '" data-vs-coin="' + slot + '">'
      + '<div class="vs__coin-inner" data-vs-coin-inner>'
      + '<span class="vs__coin-face vs__coin-face--fox"><span class="vs__coin-emboss"><i class="vs__coin-fox"></i></span></span>'
      + '<span class="vs__coin-face vs__coin-face--jlas"><b>JLAS</b><small>コツコツ</small></span>'
      + '</div><small class="vs__coin-name" data-vs-coin-name></small></div>';
  }

  function q(selector) {
    return root.querySelector(selector);
  }

  // ------------------------------------------------------------------ render

  function render(nextRoom) {
    room = nextRoom;
    if (!root.firstChild) build();
    var heroes = HEROES[room.gameType] || HEROES.NEKO_CATCH;
    root.dataset.game = room.gameType;
    q('[data-vs-hero="0"] img').src = heroes[0];
    q('[data-vs-hero="1"] img').src = heroes[1];
    q('[data-vs-game]').textContent = room.gameName + ' · Ghép trận nhanh';
    q('[data-vs-deck]').textContent = room.settings && room.settings.setTitle || '—';
    renderPlates();
    renderSteps();
    renderCoin();
    renderStage();
    window.clearInterval(tickTimer);
    tickTimer = window.setInterval(tick, 200);
    tick();
  }

  function renderPlates() {
    for (var slot = 0; slot < 2; slot++) {
      var p = playerBySlot(slot);
      var plate = q('[data-vs-plate="' + slot + '"]');
      if (!p) {
        plate.innerHTML = '<div class="vs__avatar">?</div><div class="vs__who"><strong>Đã rời phòng</strong></div>';
        continue;
      }
      var tags = [];
      if (p.userId === ctx.meId) tags.push('<span class="vs__tag">Bạn</span>');
      if (room.players.length === 2 && (room.status === 'WAITING' || room.status === 'COUNTDOWN')) {
        tags.push(p.ready ? '<span class="vs__tag vs__tag--ok">Sẵn sàng</span>' : '<span class="vs__tag vs__tag--wait">Chưa sẵn sàng</span>');
      }
      if (!p.online) tags.push('<span class="vs__tag vs__tag--bad">Mất kết nối</span>');
      var avatar = p.avatarUrl ? '<img src="' + esc(p.avatarUrl) + '" alt="">' : esc(initials(p.displayName));
      // Khung avatar + màu tên người chơi mua ở Cửa hàng (components/avatar-frame.js).
      var frames = window.JlasAvatarFrame;
      var face = '<div class="vs__avatar">' + avatar + '</div>';
      plate.innerHTML = (frames ? frames.host(face, p.avatarFrame, 'vs__avatar-host') : face)
        + '<div class="vs__who"><strong' + (frames ? frames.nameAttr(p.nameStyle) : '') + '>' + esc(p.displayName)
        + '</strong><span>' + tags.join('') + '</span></div>'
        + '<div class="vs__case" title="Bộ thẻ"><i class="fa-solid fa-layer-group" aria-hidden="true"></i>'
        + '<span>' + esc(room.settings && room.settings.setTitle || '') + '</span></div>';
      if (frames) frames.mountAll(plate);
    }
  }

  function renderSteps() {
    var draft = room.draft;
    var list = q('[data-vs-steps]');
    if (!draft) {
      list.innerHTML = '';
      return;
    }
    list.innerHTML = draft.steps.map(function (step, index) {
      var current = step.phase === 'TOSS' || step.phase === 'CHOOSE';
      var cls = step.phase === 'DONE' ? 'is-done' : current ? 'is-current' : '';
      var value = step.phase === 'DONE'
        ? esc(chosenText(step)) + ' <small>· ' + (step.timedOut ? 'hết giờ, tự chọn' : esc(nameOf(step.chosenByUserId)) + ' chọn') + '</small>'
        : current ? (step.phase === 'TOSS' ? 'Đang tung…' : esc(nameOf(step.chooserUserId)) + ' đang chọn') : 'Chờ';
      return '<li class="vs__step ' + cls + '"><span class="vs__step-no">' + (index + 1) + '</span>'
        + '<span><b>' + esc(step.title) + '</b><em>' + value + '</em></span></li>';
    }).join('');
  }

  function currentStep() {
    var draft = room.draft;
    if (!draft || room.status !== 'DRAFTING') return null;
    return draft.steps.find(function (step) { return step.phase === 'TOSS' || step.phase === 'CHOOSE'; }) || null;
  }

  /**
   * Hai đồng xu, mỗi người một đồng, tung cùng lúc. Backend đã bốc sẵn người thắng (tossWinnerUserId):
   * đồng của người đó đáp mặt cáo, đồng còn lại đáp mặt JLAS. Mỗi cài đặt mới tung lại từ đầu; tải lại trang
   * giữa chừng thì hiện luôn kết quả.
   */
  function renderCoin() {
    var step = currentStep();
    var coins = q('[data-vs-coins]');
    if (!step) {
      coins.hidden = true;
      lastToss = null;
      return;
    }
    coins.hidden = false;
    var winnerSide = sideOf(step.tossWinnerUserId);
    var key = step.key + ':' + step.tossWinnerUserId + ':' + room.draft.index;
    var restart = key !== lastToss;
    var animate = restart && step.phase === 'TOSS' && !reduceMotion;
    lastToss = key;
    [0, 1].forEach(function (slot) {
      var coin = q('[data-vs-coin="' + slot + '"]');
      var inner = coin.querySelector('[data-vs-coin-inner]');
      var p = playerBySlot(slot);
      var fox = (slot === 1 ? 'right' : 'left') === winnerSide;
      coin.querySelector('[data-vs-coin-name]').textContent = p ? (p.userId === ctx.meId ? 'Bạn' : p.displayName) : '';
      // Số vòng chẵn → mặt cáo; lệch nửa vòng → mặt JLAS. Đồng bên phải quay ngược chiều cho đỡ đơn điệu.
      inner.style.setProperty('--flip-end', (slot === 1 ? -1 : 1) * (fox ? 1800 : 1980) + 'deg');
      if (!restart) return;
      coin.classList.remove('is-winner', 'is-loser');
      inner.classList.remove('is-flipping', 'is-landed');
      if (animate) {
        void inner.offsetWidth;
        inner.classList.add('is-flipping');
      } else {
        inner.classList.add('is-landed');
        coin.classList.add(fox ? 'is-winner' : 'is-loser');
      }
    });
    tossLandsAt = animate ? Date.now() + FLIP_MS : (restart ? 0 : tossLandsAt);
  }

  /** Xu vừa đáp: làm nổi đồng ra mặt cáo, làm mờ đồng còn lại. */
  function markLanded() {
    var step = currentStep();
    if (!step) return;
    var winnerSide = sideOf(step.tossWinnerUserId);
    [0, 1].forEach(function (slot) {
      var fox = (slot === 1 ? 'right' : 'left') === winnerSide;
      var coin = q('[data-vs-coin="' + slot + '"]');
      coin.classList.toggle('is-winner', fox);
      coin.classList.toggle('is-loser', !fox);
    });
  }

  function renderStage() {
    var step = currentStep();
    var draft = room.draft;
    var mark = q('[data-vs-mark]');
    var banner = q('[data-vs-banner]');
    var panel = q('[data-vs-panel]');
    var intro = !!draft && draft.phase === 'INTRO' && room.status === 'DRAFTING';
    root.classList.toggle('is-intro', intro);
    // Hết màn chào thì thu nhỏ sân khấu để bảng chọn và bảng tên nằm gọn trong một màn hình.
    root.classList.toggle('is-compact', !intro);
    mark.classList.toggle('is-dim', !!step);

    if (room.players.length < 2) {
      banner.innerHTML = '<span>Đối thủ đã rời phòng.</span>';
      panel.innerHTML = '<div class="vs__actions">'
        + '<a class="vs__btn vs__btn--primary" href="/games/lobby?mode=QUICK&game=' + encodeURIComponent(room.gameType)
        + (room.settings && room.settings.setId ? '&setId=' + encodeURIComponent(room.settings.setId) : '') + '">Tìm trận mới</a>'
        + '<button type="button" class="vs__btn" data-vs-action="leave">Về trang Trò chơi</button></div>';
      return;
    }
    if (room.status === 'DRAFTING' && draft && draft.phase === 'INTRO') {
      banner.innerHTML = '<span>Đã tìm thấy đối thủ! Chuẩn bị tung đồng xu chọn cài đặt…</span>';
      panel.innerHTML = '';
      return;
    }
    if (room.status === 'DRAFTING' && step) {
      renderDraftPanel(step, banner, panel);
      return;
    }
    if (room.status === 'COUNTDOWN') {
      banner.innerHTML = '<span>Cả hai đã sẵn sàng. Vào trận…</span>';
      panel.innerHTML = '';
      return;
    }
    if (room.status === 'PLAYING' && room.playUrl) {
      banner.innerHTML = '<span>Trận đang diễn ra.</span>';
      panel.innerHTML = '<div class="vs__actions"><a class="vs__btn vs__btn--primary" href="' + esc(room.playUrl) + '">Vào trận</a></div>';
      return;
    }
    renderReadyPanel(banner, panel);
  }

  function renderDraftPanel(step, banner, panel) {
    var flipping = tossLandsAt > Date.now();
    var mine = step.chooserUserId === ctx.meId;
    if (step.phase === 'TOSS' || flipping) {
      // Bước "Lượt lật đầu tiên" không có lượt chọn: ai ra mặt cáo đi trước luôn.
      var reward = step.key === 'first' ? 'đi trước' : 'được quyền chọn ' + esc(step.title.toLowerCase());
      banner.innerHTML = flipping
        ? '<span>Tung đồng xu · <b>' + esc(step.title) + '</b> · ai ra mặt cáo ' + reward + '</span>'
        : '<span><b>' + esc(nameOf(step.tossWinnerUserId)) + '</b> ra mặt cáo · ' + reward + '</span>';
      panel.innerHTML = '';
      return;
    }
    var passedNote = step.passed
      ? '<p class="vs__note">' + esc(nameOf(step.tossWinnerUserId)) + ' đã nhường quyền chọn cho ' + esc(mine ? 'bạn' : nameOf(step.chooserUserId)) + '.</p>'
      : '';
    if (!mine) {
      banner.innerHTML = '<span>Đối thủ đang chọn <b>' + esc(step.title.toLowerCase()) + '</b>… <em data-vs-left></em></span>';
      panel.innerHTML = passedNote + '<div class="vs__timer"><i data-vs-bar></i></div>';
      return;
    }
    banner.innerHTML = '<span>Bạn được quyền chọn <b>' + esc(step.title.toLowerCase()) + '</b> <em data-vs-left></em></span>';
    var options = step.options.map(function (option) {
      var recommended = option.value === step.recommended;
      return '<button type="button" class="vs__option" data-vs-choose="' + esc(option.value) + '">'
        + '<b>' + esc(option.label) + '</b><small>' + esc(option.hint || '') + '</small>'
        + (recommended ? '<em>Gợi ý</em>' : '') + '</button>';
    }).join('');
    panel.innerHTML = passedNote
      + '<div class="vs__options' + (step.options.length > 6 ? ' vs__options--dense' : '') + '" role="group" aria-label="'
      + esc(step.title) + '">' + options + '</div>'
      + '<div class="vs__timer"><i data-vs-bar></i></div>'
      + (step.passed ? '' : '<div class="vs__actions"><button type="button" class="vs__btn" data-vs-action="pass">'
        + '<i class="fa-solid fa-hand-holding-hand" aria-hidden="true"></i> Nhường quyền chọn cho đối thủ</button></div>');
  }

  function renderReadyPanel(banner, panel) {
    var me = player(ctx.meId);
    var settings = room.settings || {};
    var chosen = (room.draft && room.draft.steps || []).map(function (step) {
      return '<li><span>' + esc(step.title) + '</span><b>' + esc(chosenText(step)) + '</b></li>';
    }).join('');
    var result = room.lastResult;
    var resultHtml = '';
    if (result) {
      var headline = result.draw ? 'Hoà!' : result.winnerUserId === ctx.meId ? 'Bạn thắng!' : 'Đối thủ thắng';
      resultHtml = '<div class="vs__result"><p>Trận vừa xong · <b>' + esc(headline) + '</b></p>'
        + result.lines.map(function (line) {
          return '<span class="' + (line.winner ? 'is-winner' : '') + '">' + esc(line.userId === ctx.meId ? 'Bạn' : line.displayName)
            + ' <b>' + line.score + '</b></span>';
        }).join('') + '</div>';
    }
    banner.innerHTML = result
      ? '<span>Bấm <b>Sẵn sàng</b> để đấu lại với cùng cài đặt.</span>'
      : '<span>Cài đặt xong. Hai bên bấm <b>Sẵn sàng</b> để vào trận.</span>';
    panel.innerHTML = resultHtml
      + '<ul class="vs__summary" aria-label="Cài đặt đã chọn">' + (chosen
        || '<li><span>Hỏi → Đáp</span><b>' + esc(arena.fieldLabel(settings.questionField)) + ' → '
        + esc(arena.fieldLabel(settings.answerField)) + '</b></li>') + '</ul>'
      + '<div class="vs__actions">'
      + '<button type="button" class="vs__btn ' + (me && me.ready ? '' : 'vs__btn--primary') + '" data-vs-action="ready"' + (ctx.busy ? ' disabled' : '') + '>'
      + (me && me.ready ? 'Huỷ sẵn sàng' : '<i class="fa-solid fa-check" aria-hidden="true"></i> Sẵn sàng') + '</button>'
      + '<button type="button" class="vs__btn vs__btn--ghost" data-vs-action="leave">Rời phòng</button></div>';
  }

  /** Cập nhật đồng hồ chọn và đổi banner khi đồng xu vừa đáp xuống. */
  function tick() {
    if (!room) return;
    var draft = room.draft;
    if (tossLandsAt && Date.now() >= tossLandsAt) {
      tossLandsAt = 0;
      markLanded();
      renderStage();
    }
    var left = root.querySelector('[data-vs-left]');
    var bar = root.querySelector('[data-vs-bar]');
    if (!draft || !draft.phaseEndsAt || room.status !== 'DRAFTING') return;
    var remaining = Math.max(0, draft.phaseEndsAt - arena.live.serverNow());
    if (left) left.textContent = Math.ceil(remaining / 1000) + 's';
    if (bar) bar.style.width = Math.min(100, remaining / 150) + '%';
  }

  function onClick(event) {
    var choose = event.target.closest('[data-vs-choose]');
    var step = currentStep();
    if (choose && step) {
      root.querySelectorAll('[data-vs-choose]').forEach(function (button) { button.disabled = true; });
      ctx.onChoose(step.key, choose.dataset.vsChoose);
      return;
    }
    var action = event.target.closest('[data-vs-action]');
    if (!action) return;
    if (action.dataset.vsAction === 'pass' && step) {
      action.disabled = true;
      ctx.onPass(step.key);
    } else if (action.dataset.vsAction === 'ready') {
      var me = player(ctx.meId);
      ctx.onReady(!(me && me.ready));
    } else if (action.dataset.vsAction === 'leave') {
      ctx.onLeave();
    }
  }

  function setConnection(status) {
    if (!root || !root.firstChild) return;
    q('[data-vs-dot]').className = 'ar-dot is-' + (status === 'idle' ? 'connecting' : status);
    q('[data-vs-conn]').textContent = status === 'online' ? 'Đã kết nối' : status === 'offline' ? 'Mất kết nối, đang thử lại' : 'Đang kết nối';
  }

  global.JlasVsRoom = {
    mount: function (element, context) {
      root = element;
      ctx = context;
    },
    render: render,
    setConnection: setConnection,
    setBusy: function (busy) {
      if (ctx) ctx.busy = busy;
      if (room) renderStage();
    }
  };
})(window);
