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
  // Games with a character or plane skin; chosen once the settings are settled, before pressing Ready.
  var SKIN_GAMES = { NEKO_CATCH: true, AIR_DEFENSE: true };
  // Luôn tung đồng xu có hoạt ảnh, không theo prefers-reduced-motion của hệ điều hành (quyết định sản phẩm).
  var reduceMotion = false;

  var root = null;
  var ctx = null;
  var room = null;
  var tickTimer = null;
  var lastToss = null;
  var tossLandsAt = 0;
  // Cặp hỏi – đáp đang chọn dở; giữ lại qua các lần render lại (ROOM_STATE có thể tới khi đang chọn).
  var pairPick = null;
  // Ai đã sẵn sàng ở lần render trước: chỉ đóng dấu (có hoạt ảnh) đúng lúc vừa bấm.
  var readySeen = {};

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
      + '<header class="vs__top">'
      + '  <div><h1 class="vs__title" data-vs-game></h1><p class="vs__deck">Ghép trận nhanh với bộ <strong data-vs-deck></strong></p></div>'
      + '  <span class="vs__conn"><span class="ar-dot" data-vs-dot aria-hidden="true"></span><span data-vs-conn>Đang kết nối</span></span>'
      + '</header>'
      + '<ol class="vs__steps" data-vs-steps aria-label="Các cài đặt của trận"></ol>'
      + '<div class="vs__stage">'
      + '  <div class="vs__side vs__side--left">'
      + '    <figure class="vs__hero vs__hero--left" data-vs-hero="0"><img alt=""></figure>'
      + '    <div class="vs__plate vs__plate--left" data-vs-plate="0"></div>'
      + '  </div>'
      + '  <div class="vs__core">'
      + '    <div class="vs__vortex" aria-hidden="true"><i></i><i></i><i></i><i></i></div>'
      + '    <div class="vs__coins" data-vs-coins hidden aria-hidden="true">' + coinHtml(0) + coinHtml(1) + '</div>'
      + '    <strong class="vs__mark" data-vs-mark aria-hidden="true">VS</strong>'
      + '  </div>'
      + '  <div class="vs__side vs__side--right">'
      + '    <figure class="vs__hero vs__hero--right" data-vs-hero="1"><img alt=""></figure>'
      + '    <div class="vs__plate vs__plate--right" data-vs-plate="1"></div>'
      + '  </div>'
      + '</div>'
      + '<div class="vs__banner" data-vs-banner role="status" aria-live="polite"></div>'
      + '<div class="vs__panel" data-vs-panel></div>'
      // Outside the panel, which is rebuilt on every ROOM_STATE: room-page.js mounts the skin picker here once.
      + '<dialog class="ar-dialog vs__skins-dialog" data-vs-skins-dialog aria-label="Chọn skin">'
      + '<button type="button" class="ar-icon-btn vs__skins-close" data-vs-skins-close aria-label="Đóng"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button>'
      + '<section class="ar-room-skins vs__skins" data-vs-skins></section></dialog>';
    root.addEventListener('click', onClick);
    root.addEventListener('change', onChange);
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
    root.dataset.game = room.gameType;
    renderHeroes(HEROES[room.gameType] || HEROES.NEKO_CATCH);
    q('[data-vs-game]').textContent = room.gameName;
    q('[data-vs-deck]').textContent = room.settings && room.settings.setTitle || '—';
    renderPlates();
    renderSteps();
    renderCoin();
    renderStage();
    window.clearInterval(tickTimer);
    tickTimer = window.setInterval(tick, 200);
    tick();
  }

  /**
   * Each side shows the skin that player has equipped, animated like the shop preview. Rebuilt only when the
   * skin changes, so the animation does not restart on every ROOM_STATE. Without skin data: the painted hero.
   */
  function renderHeroes(heroes) {
    var skins = window.JlasCharacterSkins;
    [0, 1].forEach(function (slot) {
      var figure = q('[data-vs-hero="' + slot + '"]');
      var p = playerBySlot(slot);
      var skin = skins && p && p.skin;
      var look = skin ? room.gameType + ':' + skin.code : 'hero:' + heroes[slot];
      if (figure.dataset.look === look) return;
      figure.dataset.look = look;
      figure.classList.toggle('has-skin', !!skin);
      figure.innerHTML = skin ? '<span class="vs__skin">' + skins.art(skin, room.gameType) + '</span>'
        : '<img alt="" src="' + esc(heroes[slot]) + '">';
      if (skin) skins.mount(figure);
    });
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
      if (p.userId === ctx.meId) tags.push('<span class="vs__tag vs__tag--me">Bạn</span>');
      var readyPhase = room.players.length === 2 && (room.status === 'WAITING' || room.status === 'COUNTDOWN');
      var ready = readyPhase && p.ready;
      if (readyPhase) {
        tags.push(ready ? '<span class="vs__tag vs__tag--ok">Sẵn sàng</span>' : '<span class="vs__tag">Chưa sẵn sàng</span>');
      }
      if (!p.online) tags.push('<span class="vs__tag vs__tag--bad">Mất kết nối</span>');
      // Con dấu 済 chỉ có hoạt ảnh đóng dấu ở lần render ngay sau khi người đó bấm Sẵn sàng.
      var seal = ready
        ? '<span class="vs__seal' + (readySeen[p.userId] ? '' : ' is-stamping') + '" title="Sẵn sàng" aria-hidden="true">済</span>'
        : '<span class="vs__seal-slot" aria-hidden="true"></span>';
      readySeen[p.userId] = ready;
      // Google profile photos refuse requests that carry a referrer; a photo that still fails shows the initials.
      var avatar = p.avatarUrl ? '<img src="' + esc(p.avatarUrl) + '" alt="" referrerpolicy="no-referrer">' : esc(initials(p.displayName));
      // Khung avatar + màu tên người chơi mua ở Cửa hàng (components/avatar-frame.js).
      var frames = window.JlasAvatarFrame;
      var face = '<div class="vs__avatar">' + avatar + '</div>';
      plate.innerHTML = (frames ? frames.host(face, p.avatarFrame, 'vs__avatar-host') : face)
        + '<div class="vs__who"><strong' + (frames ? frames.nameAttr(p.nameStyle) : '') + '>' + esc(p.displayName)
        + '</strong><span>' + tags.join('') + '</span></div>'
        + seal;
      if (frames) frames.mountAll(plate);
      plate.querySelectorAll('.vs__avatar img').forEach(function (img) {
        var fallback = initials(p.displayName);
        var swap = function () { img.replaceWith(document.createTextNode(fallback)); };
        if (img.complete && !img.naturalWidth) swap(); else img.addEventListener('error', swap, { once: true });
      });
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
        ? esc(chosenText(step)) + ' <small>(' + (step.timedOut ? 'hết giờ, tự chọn' : esc(nameOf(step.chosenByUserId)) + ' chọn') + ')</small>'
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
    // Skins can only change before Ready, so the picker closes once the room moves on.
    var skinsDialog = q('[data-vs-skins-dialog]');
    if (skinsDialog.open && (room.status !== 'WAITING' || room.players.length < 2)) skinsDialog.close();

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
        ? '<span>Tung đồng xu cho <b>' + esc(step.title.toLowerCase()) + '</b>: ai ra mặt cáo sẽ ' + reward + '</span>'
        : '<span><b>' + esc(nameOf(step.tossWinnerUserId)) + '</b> ra mặt cáo và ' + reward + '</span>';
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
    var passButton = step.passed ? '' : '<button type="button" class="vs__btn vs__btn--ghost" data-vs-action="pass">'
      + 'Nhường quyền chọn cho đối thủ</button>';
    if (step.key === 'fields') {
      panel.innerHTML = passedNote + pairPickerHtml(step)
        + '<div class="vs__actions"><button type="button" class="vs__btn vs__btn--primary" data-vs-pair-confirm>Chọn cặp này</button>'
        + passButton + '</div>'
        + '<div class="vs__timer"><i data-vs-bar></i></div>';
      syncPairPicker(step);
      return;
    }
    var options = step.options.map(function (option) {
      var recommended = option.value === step.recommended;
      return '<button type="button" class="vs__option" data-vs-choose="' + esc(option.value) + '">'
        + '<b>' + esc(option.label) + '</b><small>' + esc(option.hint || '') + '</small>'
        + (recommended ? '<em>Gợi ý</em>' : '') + '</button>';
    }).join('');
    panel.innerHTML = passedNote
      + '<div class="vs__options" role="group" aria-label="' + esc(step.title) + '">' + options + '</div>'
      + (passButton ? '<div class="vs__actions">' + passButton + '</div>' : '')
      + '<div class="vs__timer"><i data-vs-bar></i></div>';
  }

  // ------------------------------------------------------------------ cặp hỏi – đáp

  /** Giá trị lựa chọn có dạng "KANJI>MEANING"; tách thành câu hỏi và câu trả lời. */
  function pairsOf(step) {
    return step.options.map(function (option) {
      var parts = option.value.split('>');
      return { value: option.value, question: parts[0], answer: parts[1], hint: option.hint || '' };
    });
  }

  function fieldName(field) {
    return arena.fieldLabel(field);
  }

  /** Lấy lựa chọn đang dở nếu vẫn hợp lệ, không thì lấy cặp gợi ý (hoặc cặp đầu tiên). */
  function currentPair(step) {
    var pairs = pairsOf(step);
    var stepId = step.key + ':' + room.draft.index;
    if (pairPick && pairPick.stepId === stepId) {
      var kept = pairs.find(function (pair) { return pair.value === pairPick.value; });
      if (kept) return kept;
    }
    var fallback = pairs.find(function (pair) { return pair.value === step.recommended; }) || pairs[0];
    pairPick = { stepId: stepId, value: fallback ? fallback.value : null };
    return fallback;
  }

  function pairPickerHtml(step) {
    var seen = {};
    var questions = pairsOf(step).filter(function (pair) {
      if (seen[pair.question]) return false;
      seen[pair.question] = true;
      return true;
    }).map(function (pair) {
      return '<option value="' + esc(pair.question) + '">' + esc(fieldName(pair.question)) + '</option>';
    }).join('');
    return '<div class="vs__pair" role="group" aria-label="' + esc(step.title) + '">'
      + '<label class="vs__field"><span>Hỏi bằng</span><select data-vs-pair="question">' + questions + '</select></label>'
      + '<span class="vs__pair-arrow" aria-hidden="true"><i class="fa-solid fa-arrow-right"></i></span>'
      + '<label class="vs__field"><span>Trả lời bằng</span><select data-vs-pair="answer"></select></label>'
      + '</div>'
      + '<p class="vs__pair-meta" data-vs-pair-meta aria-live="polite"></p>';
  }

  /** Đổ danh sách câu trả lời theo câu hỏi đang chọn và cập nhật số thẻ, không vẽ lại cả bảng. */
  function syncPairPicker(step) {
    var pair = currentPair(step);
    var questionSelect = q('[data-vs-pair="question"]');
    var answerSelect = q('[data-vs-pair="answer"]');
    var meta = q('[data-vs-pair-meta]');
    if (!pair || !questionSelect || !answerSelect) return;
    questionSelect.value = pair.question;
    answerSelect.innerHTML = pairsOf(step).filter(function (candidate) {
      return candidate.question === pair.question;
    }).map(function (candidate) {
      return '<option value="' + esc(candidate.value) + '">' + esc(fieldName(candidate.answer)) + '</option>';
    }).join('');
    answerSelect.value = pair.value;
    meta.innerHTML = 'Cặp này có <b>' + esc(pair.hint) + '</b> dùng được'
      + (pair.value === step.recommended ? '<em>Cặp gợi ý</em>' : '');
  }

  function onChange(event) {
    var select = event.target.closest('[data-vs-pair]');
    var step = currentStep();
    if (!select || !step || step.key !== 'fields') return;
    var pairs = pairsOf(step);
    var picked;
    if (select.dataset.vsPair === 'question') {
      // Đổi câu hỏi: giữ câu trả lời cũ nếu cặp mới vẫn có, không thì lấy câu trả lời đầu tiên.
      var previous = pairs.find(function (pair) { return pair.value === pairPick.value; });
      picked = pairs.find(function (pair) {
        return pair.question === select.value && previous && pair.answer === previous.answer;
      }) || pairs.find(function (pair) { return pair.question === select.value; });
    } else {
      picked = pairs.find(function (pair) { return pair.value === select.value; });
    }
    if (!picked) return;
    pairPick.value = picked.value;
    syncPairPicker(step);
  }

  function renderReadyPanel(banner, panel) {
    var me = player(ctx.meId);
    var settings = room.settings || {};
    // Các cài đặt đã chốt đã hiện ở dải các bước phía trên, nên ở đây chỉ còn nút. Chỉ khi phòng không có bước chọn
    // (không có dải phía trên) mới ghi lại cặp hỏi – đáp.
    var hasSteps = !!(room.draft && room.draft.steps && room.draft.steps.length);
    banner.innerHTML = '';
    panel.innerHTML = (hasSteps ? '' : '<ul class="vs__summary" aria-label="Cài đặt đã chọn"><li><span>Hỏi → Đáp</span><b>'
        + esc(arena.fieldLabel(settings.questionField)) + ' → ' + esc(arena.fieldLabel(settings.answerField)) + '</b></li></ul>')
      + '<div class="vs__actions">'
      + '<button type="button" class="vs__btn ' + (me && me.ready ? '' : 'vs__btn--primary') + '" data-vs-action="ready"' + (ctx.busy ? ' disabled' : '') + '>'
      + (me && me.ready ? 'Huỷ sẵn sàng' : '<i class="fa-solid fa-check" aria-hidden="true"></i> Sẵn sàng') + '</button>'
      + skinButton(me)
      + '<button type="button" class="vs__btn vs__btn--ghost" data-vs-action="leave">Rời phòng</button></div>';
  }

  /** Opens the skin picker; locked once the player is Ready, like the picker itself. */
  function skinButton(me) {
    if (!SKIN_GAMES[room.gameType]) return '';
    var plane = room.gameType === 'AIR_DEFENSE';
    var locked = !!(me && me.ready);
    return '<button type="button" class="vs__btn" data-vs-action="skins"' + (locked ? ' disabled title="Huỷ sẵn sàng để đổi"' : '') + '>'
      + '<i class="fa-solid ' + (plane ? 'fa-jet-fighter' : 'fa-shirt') + '" aria-hidden="true"></i> '
      + (plane ? 'Đổi máy bay' : 'Đổi trang phục') + '</button>';
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
    var confirmPair = event.target.closest('[data-vs-pair-confirm]');
    if (confirmPair && step && step.key === 'fields' && pairPick && pairPick.value) {
      root.querySelectorAll('[data-vs-pair-confirm], [data-vs-pair], [data-vs-action="pass"]').forEach(function (control) {
        control.disabled = true;
      });
      ctx.onChoose(step.key, pairPick.value);
      return;
    }
    if (choose && step) {
      root.querySelectorAll('[data-vs-choose]').forEach(function (button) { button.disabled = true; });
      ctx.onChoose(step.key, choose.dataset.vsChoose);
      return;
    }
    var skinsDialog = q('[data-vs-skins-dialog]');
    // A click on the dialog itself (not its content) is a click on the backdrop.
    if (event.target === skinsDialog || event.target.closest('[data-vs-skins-close]')) {
      skinsDialog.close();
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
    } else if (action.dataset.vsAction === 'skins') {
      skinsDialog.showModal();
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
