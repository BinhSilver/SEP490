/**
 * Cửa hàng Trò chơi (/games/shop): trang phục mèo (Bắt cá), máy bay (Bắn máy bay) và khung avatar + màu tên
 * cho hồ sơ, mua bằng CP chung. ?game=AIR_DEFENSE mở thẳng mục máy bay, ?game=FRAMES mở mục khung avatar.
 */
(function () {
  'use strict';

  var arena = window.JlasArena;
  var esc = arena.esc;
  /** Mục khung avatar không phải game: dữ liệu lấy từ /api/profile/cosmetics thay vì /api/games/shop. */
  var FRAMES = 'FRAMES';
  var SLUGS = { NEKO_CATCH: 'neko', AIR_DEFENSE: 'air-defense', MEMORY_MATCH: 'memory', FRAMES: 'frames' };
  /** Dải ảnh hoạt hình (make_shop_anim): mèo đứng yên 6 khung, máy bay nghiêng 8 khung + lửa đuôi. */

  var el = {
    tabs: document.querySelector('[data-shop-tabs]'),
    status: document.querySelector('[data-shop-status]'),
    grid: document.querySelector('[data-shop-grid]'),
    confirm: document.querySelector('[data-confirm]'),
    confirmArt: document.querySelector('[data-confirm-art]'),
    confirmText: document.querySelector('[data-confirm-text]'),
    confirmOk: document.querySelector('[data-confirm-ok]')
  };

  var state = { shop: null, frames: null, game: null, busy: false, pending: null };

  function toast(message, type) {
    if (window.JlasUI) window.JlasUI.toast(message, type || 'info');
  }

  function section() {
    var sections = state.shop ? state.shop.sections : [];
    return sections.find(function (item) { return item.gameType === state.game; }) || sections[0] || null;
  }

  function findItem(code) {
    if (state.game === FRAMES) {
      return state.frames ? state.frames.frames.find(function (frame) { return frame.code === code; }) : null;
    }
    var current = section();
    return current ? current.items.find(function (item) { return item.code === code; }) : null;
  }

  function render() {
    var shop = state.shop;
    el.tabs.hidden = false;
    el.tabs.innerHTML = shop.sections.map(function (item) {
      var selected = item.gameType === state.game;
      return '<button type="button" role="tab" aria-selected="' + selected + '" data-game="' + esc(item.gameType) + '">'
        + esc(item.gameName) + ' · ' + esc(item.title) + '</button>';
    }).join('') + (state.frames
      ? '<button type="button" role="tab" aria-selected="' + (state.game === FRAMES) + '" data-game="' + FRAMES + '">'
        + 'Hồ sơ · Khung avatar</button>'
      : '');

    if (state.game === FRAMES && state.frames) {
      el.grid.hidden = false;
      el.grid.className = 'ar-shop-grid ar-shop-grid--frames';
      el.grid.innerHTML = state.frames.frames.map(function (frame) { return frameCard(frame, shop.balance); }).join('');
      mountFrames(el.grid);
      return;
    }
    var current = section();
    el.grid.hidden = !current;
    if (!current) return;
    el.grid.className = 'ar-shop-grid ar-shop-grid--' + (SLUGS[current.gameType] || 'game');
    el.grid.innerHTML = current.items.map(function (item) { return card(item, shop.balance, current.gameType); }).join('');
    if (window.JlasCharacterSkins) window.JlasCharacterSkins.mount(el.grid);
  }

  /** Avatar của chính người xem đặt trong khung, để thấy ngay khung trông thế nào trên mình. */
  function frameArt(frame) {
    var auth = window.JlasAuth;
    var user = auth ? auth.getUser() : null;
    var url = auth && user ? auth.avatarUrl(user) : null;
    var face = url
      ? '<img class="user-avatar__img" src="' + esc(url) + '" alt="" referrerpolicy="no-referrer">'
      : '<span class="user-avatar__initials">' + esc(auth ? auth.initials(user) : '?') + '</span>';
    return '<span class="ar-frame-art" data-frame-host="' + esc(frame.code) + '" role="img" aria-label="' + esc(frame.name) + '">'
      + '<span class="user-avatar ar-frame-art__avatar">' + face + '</span></span>';
  }

  function mountFrames(root) {
    if (!window.JlasAvatarFrame) return;
    root.querySelectorAll('[data-frame-host]').forEach(function (host) {
      window.JlasAvatarFrame.mount(host, host.getAttribute('data-frame-host'));
    });
  }

  function frameCard(frame, balance) {
    var price;
    var action;
    if (frame.equipped) {
      price = '<span class="ar-shop-item__owned">Đang dùng</span>';
      action = '<a class="ar-btn ar-btn--block" href="/profile">'
        + '<i class="fa-solid fa-check" aria-hidden="true"></i> Đổi ở Hồ sơ</a>';
    } else if (frame.owned) {
      price = '<span class="ar-shop-item__owned">Đã có</span>';
      action = '<button type="button" class="ar-btn ar-btn--block" data-equip="' + esc(frame.code) + '">Dùng</button>';
    } else if (balance >= frame.price) {
      price = '<span class="ar-shop-item__price"><span class="jlas-cp-icon" aria-hidden="true"></span>' + frame.price + ' CP</span>';
      action = '<button type="button" class="ar-btn ar-btn--primary ar-btn--block" data-buy="' + esc(frame.code) + '">'
        + 'Mua · ' + frame.price + ' CP</button>';
    } else {
      price = '<span class="ar-shop-item__price"><span class="jlas-cp-icon" aria-hidden="true"></span>' + frame.price + ' CP</span>';
      action = '<button type="button" class="ar-btn ar-btn--block" disabled>Cần thêm ' + (frame.price - balance) + ' CP</button>';
    }
    return '<article class="ar-shop-item' + (frame.equipped ? ' is-equipped' : '') + '">'
      + '<figure class="ar-shop-item__art">' + frameArt(frame) + '</figure>'
      + '<div class="ar-shop-item__body">'
      + '<h2 title="' + esc(frame.name) + '">' + esc(frame.name) + '</h2>'
      + '<p class="ar-shop-item__desc" title="' + esc(frame.description) + '">' + esc(frame.description) + '</p>'
      + '<p class="ar-shop-item__name-style" title="Kèm màu tên ' + esc(frame.nameStyleLabel) + '">Kèm màu tên <strong data-name-style="' + esc(frame.nameStyle) + '">'
      + esc(frame.nameStyleLabel) + '</strong></p>'
      + '<div class="ar-shop-item__foot">' + price + action + '</div>'
      + '</div></article>';
  }

  /** Ảnh xem trước chuyển động (character-skin-preview.js, dùng chung với phòng đấu). */
  function art(item, gameType) {
    return window.JlasCharacterSkins.art(item, gameType);
  }

  /** Nhãn bậc trên ảnh: Thường / Hiếm / Huyền thoại; trang phục mặc định ghi "Mặc định". */
  function tierBadge(item) {
    if (item.starter) return '<span class="ar-shop-tier ar-shop-tier--starter">Mặc định</span>';
    if (!item.tier) return '';
    return '<span class="ar-shop-tier ar-shop-tier--' + esc(item.tier.toLowerCase()) + '">' + esc(item.tierLabel || item.tier) + '</span>';
  }

  function card(item, balance, gameType) {
    var price;
    var action;
    if (item.equipped) {
      price = '<span class="ar-shop-item__owned">Đang dùng</span>';
      action = '<button type="button" class="ar-btn ar-btn--block" disabled>'
        + '<i class="fa-solid fa-check" aria-hidden="true"></i> Đang dùng</button>';
    } else if (item.owned) {
      price = '<span class="ar-shop-item__owned">' + (item.starter ? 'Có sẵn' : 'Đã có') + '</span>';
      action = '<button type="button" class="ar-btn ar-btn--block" data-equip="' + esc(item.code) + '">Dùng</button>';
    } else if (balance >= item.price) {
      price = '<span class="ar-shop-item__price"><span class="jlas-cp-icon" aria-hidden="true"></span>' + item.price + ' CP</span>';
      action = '<button type="button" class="ar-btn ar-btn--primary ar-btn--block" data-buy="' + esc(item.code) + '">'
        + 'Mua · ' + item.price + ' CP</button>';
    } else {
      price = '<span class="ar-shop-item__price"><span class="jlas-cp-icon" aria-hidden="true"></span>' + item.price + ' CP</span>';
      action = '<button type="button" class="ar-btn ar-btn--block" disabled>Cần thêm ' + (item.price - balance) + ' CP</button>';
    }
    return '<article class="ar-shop-item' + (item.equipped ? ' is-equipped' : '') + (item.tier ? ' ar-shop-item--' + esc(item.tier.toLowerCase()) : '') + '">'
      + '<figure class="ar-shop-item__art">' + tierBadge(item) + art(item, gameType) + '</figure>'
      + '<div class="ar-shop-item__body">'
      + '<h2>' + esc(item.name) + '</h2>'
      + '<p class="ar-shop-item__desc">' + esc(item.description) + '</p>'
      + '<div class="ar-shop-item__foot">' + price + action + '</div>'
      + '</div></article>';
  }

  function showError(message) {
    el.status.hidden = false;
    el.status.innerHTML = '<p><strong>Không mở được cửa hàng.</strong> ' + esc(message) + '</p>'
      + '<button type="button" class="ar-btn ar-btn--sm" data-retry>Thử lại</button>';
  }

  function apply(shop, frames) {
    state.shop = shop;
    if (frames !== undefined) state.frames = frames;
    var known = shop.sections.some(function (item) { return item.gameType === state.game; })
      || (state.game === FRAMES && state.frames);
    if (!known) {
      state.game = shop.sections.length ? shop.sections[0].gameType : (state.frames ? FRAMES : null);
    }
    el.status.hidden = true;
    render();
  }

  /** Sau khi mua/đổi khung: số CP mới cho mục game, và header/hồ sơ dùng khung + màu tên mới. */
  function applyFrames(view) {
    state.frames = view;
    state.shop.balance = view.balance;
    render();
    if (window.JlasAuth) window.JlasAuth.refreshUser();
  }

  /** Ô CP trên thanh điều hướng cập nhật theo số dư mới sau khi mua. */
  function refreshChip() {
    if (window.RewardFX && typeof window.RewardFX.refreshChip === 'function') window.RewardFX.refreshChip(true);
  }

  function load() {
    el.status.hidden = false;
    el.status.innerHTML = '<p class="ar-muted">Đang mở cửa hàng…</p>';
    // Mục khung avatar lỗi thì cửa hàng game vẫn mở được (chỉ ẩn tab khung).
    var frames = arena.api.cosmetics().catch(function () { return null; });
    Promise.all([arena.api.shop(), frames]).then(function (result) {
      apply(result[0], result[1]);
    }).catch(function (error) { showError(error.message); });
  }

  function setBusy(busy) {
    state.busy = busy;
    el.grid.querySelectorAll('button[data-buy], button[data-equip]').forEach(function (button) { button.disabled = busy; });
    el.confirmOk.disabled = busy;
  }

  function askBuy(item) {
    var isFrame = state.game === FRAMES;
    state.pending = { item: item, frame: isFrame };
    el.confirmArt.className = 'ar-shop-item__art ar-shop-item__art--' + (SLUGS[state.game] || 'game');
    if (isFrame) {
      el.confirmArt.innerHTML = frameArt(item);
      mountFrames(el.confirmArt);
    } else {
      el.confirmArt.innerHTML = art(item, state.game);
      if (window.JlasCharacterSkins) window.JlasCharacterSkins.mount(el.confirmArt);
    }
    el.confirmText.innerHTML = 'Mua <strong>' + esc(item.name) + '</strong> với <strong>' + item.price + ' CP</strong>?'
      + ' Bạn còn lại ' + (state.shop.balance - item.price) + ' CP. Mua xong sẽ dùng luôn'
      + (isFrame ? ' cả khung và màu tên <strong data-name-style="' + esc(item.nameStyle) + '">' + esc(item.nameStyleLabel) + '</strong>.' : '.');
    el.confirmOk.textContent = 'Mua · ' + item.price + ' CP';
    if (typeof el.confirm.showModal === 'function') el.confirm.showModal();
    else if (window.confirm(el.confirmText.textContent)) buy();
  }

  function closeConfirm() {
    if (el.confirm.open) el.confirm.close();
    state.pending = null;
  }

  function buy() {
    var pending = state.pending;
    if (!pending || state.busy) return;
    var item = pending.item;
    setBusy(true);
    var request = pending.frame
      ? arena.api.buyFrame(item.code).then(applyFrames)
      : arena.api.buyItem(item.code).then(function (shop) { apply(shop); });
    request.then(function () {
      closeConfirm();
      refreshChip();
      toast(pending.frame ? 'Đã mua ' + item.name + ': khung và màu tên đang dùng.' : 'Đã mua ' + item.name + ' và đang dùng.', 'success');
    }).catch(function (error) {
      closeConfirm();
      toast(error.message, 'error');
      load();
    }).then(function () { setBusy(false); });
  }

  function equip(code) {
    var item = findItem(code);
    if (!item || state.busy) return;
    setBusy(true);
    // Dùng một khung từ cửa hàng là dùng luôn màu tên đi kèm; muốn phối khác thì chọn ở Hồ sơ.
    var request = state.game === FRAMES
      ? arena.api.equipCosmetics(item.code, item.nameStyle).then(applyFrames)
      : arena.api.equipItem(code).then(function (shop) { apply(shop); });
    request.then(function () {
      toast('Đang dùng ' + item.name + '.', 'success');
    }).catch(function (error) {
      toast(error.message, 'error');
    }).then(function () { setBusy(false); });
  }

  el.tabs.addEventListener('click', function (event) {
    var tab = event.target.closest('[data-game]');
    if (!tab || tab.dataset.game === state.game) return;
    state.game = tab.dataset.game;
    render();
    var url = new URL(window.location.href);
    url.searchParams.set('game', state.game);
    window.history.replaceState(null, '', url);
  });

  el.grid.addEventListener('click', function (event) {
    var buyButton = event.target.closest('[data-buy]');
    if (buyButton) {
      var item = findItem(buyButton.dataset.buy);
      if (item) askBuy(item);
      return;
    }
    var equipButton = event.target.closest('[data-equip]');
    if (equipButton) equip(equipButton.dataset.equip);
  });

  el.status.addEventListener('click', function (event) {
    if (event.target.closest('[data-retry]')) load();
  });

  el.confirmOk.addEventListener('click', buy);
  el.confirm.querySelectorAll('[data-confirm-cancel]').forEach(function (button) {
    button.addEventListener('click', closeConfirm);
  });
  el.confirm.addEventListener('close', function () { state.pending = null; });

  state.game = new URLSearchParams(window.location.search).get('game');
  load();
})();
