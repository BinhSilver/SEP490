/**
 * Cửa hàng Trò chơi (/games/shop): trang phục mèo (Bắt cá) và máy bay (Bắn máy bay), mua bằng CP chung.
 * ?game=AIR_DEFENSE mở thẳng mục máy bay.
 */
(function () {
  'use strict';

  var arena = window.JlasArena;
  var esc = arena.esc;
  var SLUGS = { NEKO_CATCH: 'neko', AIR_DEFENSE: 'air-defense', MEMORY_MATCH: 'memory' };
  /** Dải ảnh hoạt hình (make_shop_anim): mèo đứng yên 6 khung, máy bay nghiêng 8 khung + lửa đuôi. */
  var ANIM = '/arena/images/shop/anim/';

  var el = {
    tabs: document.querySelector('[data-shop-tabs]'),
    status: document.querySelector('[data-shop-status]'),
    grid: document.querySelector('[data-shop-grid]'),
    confirm: document.querySelector('[data-confirm]'),
    confirmArt: document.querySelector('[data-confirm-art]'),
    confirmText: document.querySelector('[data-confirm-text]'),
    confirmOk: document.querySelector('[data-confirm-ok]')
  };

  var state = { shop: null, game: null, busy: false, pending: null };

  function toast(message, type) {
    if (window.JlasUI) window.JlasUI.toast(message, type || 'info');
  }

  function section() {
    var sections = state.shop ? state.shop.sections : [];
    return sections.find(function (item) { return item.gameType === state.game; }) || sections[0] || null;
  }

  function findItem(code) {
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
    }).join('');

    var current = section();
    el.grid.hidden = !current;
    if (!current) return;
    el.grid.className = 'ar-shop-grid ar-shop-grid--' + (SLUGS[current.gameType] || 'game');
    el.grid.innerHTML = current.items.map(function (item) { return card(item, shop.balance, current.gameType); }).join('');
    if (window.JlasCharacterSkins) window.JlasCharacterSkins.mount(el.grid);
  }

  /** Ảnh xem trước chuyển động; không có dải ảnh cho game khác thì dùng ảnh tĩnh. */
  function art(item, gameType) {
    var renderer = item.renderCode || item.code;
    if (renderer === 'CUSTOM') return '<img src="' + esc(item.image) + '" alt="' + esc(item.name) + '" loading="lazy" style="width:100%;height:100%;object-fit:contain">';
    if (window.JlasCharacterSkins && window.JlasCharacterSkins.supports(renderer)) {
      return '<span class="ar-character-art' + (gameType === 'AIR_DEFENSE' ? ' ar-spaceship-art' : '') + '"><img src="' + esc(item.image) + '" alt="" loading="lazy" data-character-fallback>'
        + '<canvas width="256" height="256" hidden data-character-skin="' + esc(renderer)
        + '" role="img" aria-label="' + esc(item.name) + '"></canvas></span>';
    }
    if (gameType === 'AIR_DEFENSE') {
      return '<span class="ar-plane-fly" role="img" aria-label="' + esc(item.name) + '">'
        + '<span class="ar-sprite ar-sprite--plane" style="background-image:url(' + ANIM + esc(renderer) + '.png)"></span>'
        + '<span class="ar-sprite ar-sprite--exhaust" style="background-image:url(' + ANIM + 'exhaust.png)"></span></span>';
    }
    if (gameType === 'NEKO_CATCH') {
      return '<span class="ar-sprite ar-sprite--cat" role="img" aria-label="' + esc(item.name) + '"'
        + ' style="background-image:url(' + ANIM + esc(renderer) + '.webp)"></span>';
    }
    return '<img src="' + esc(item.image) + '" alt="' + esc(item.name) + '" loading="lazy">';
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
      price = '<span class="ar-shop-item__price"><span class="ar-coin" aria-hidden="true"></span>' + item.price + ' CP</span>';
      action = '<button type="button" class="ar-btn ar-btn--primary ar-btn--block" data-buy="' + esc(item.code) + '">'
        + 'Mua · ' + item.price + ' CP</button>';
    } else {
      price = '<span class="ar-shop-item__price"><span class="ar-coin" aria-hidden="true"></span>' + item.price + ' CP</span>';
      action = '<button type="button" class="ar-btn ar-btn--block" disabled>Cần thêm ' + (item.price - balance) + ' CP</button>';
    }
    return '<article class="ar-shop-item' + (item.equipped ? ' is-equipped' : '') + '">'
      + '<figure class="ar-shop-item__art">' + art(item, gameType) + '</figure>'
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

  function apply(shop) {
    state.shop = shop;
    if (!shop.sections.some(function (item) { return item.gameType === state.game; })) {
      state.game = shop.sections.length ? shop.sections[0].gameType : null;
    }
    el.status.hidden = true;
    render();
  }

  /** Ô CP trên thanh điều hướng cập nhật theo số dư mới sau khi mua. */
  function refreshChip() {
    if (window.RewardFX && typeof window.RewardFX.refreshChip === 'function') window.RewardFX.refreshChip(true);
  }

  function load() {
    el.status.hidden = false;
    el.status.innerHTML = '<p class="ar-muted">Đang mở cửa hàng…</p>';
    arena.api.shop().then(apply).catch(function (error) { showError(error.message); });
  }

  function setBusy(busy) {
    state.busy = busy;
    el.grid.querySelectorAll('button[data-buy], button[data-equip]').forEach(function (button) { button.disabled = busy; });
    el.confirmOk.disabled = busy;
  }

  function askBuy(item) {
    state.pending = item;
    el.confirmArt.className = 'ar-shop-item__art ar-shop-item__art--' + (SLUGS[state.game] || 'game');
    el.confirmArt.innerHTML = art(item, state.game);
    if (window.JlasCharacterSkins) window.JlasCharacterSkins.mount(el.confirmArt);
    el.confirmText.innerHTML = 'Mua <strong>' + esc(item.name) + '</strong> với <strong>' + item.price + ' CP</strong>?'
      + ' Bạn còn lại ' + (state.shop.balance - item.price) + ' CP. Mua xong sẽ dùng luôn.';
    el.confirmOk.textContent = 'Mua · ' + item.price + ' CP';
    if (typeof el.confirm.showModal === 'function') el.confirm.showModal();
    else if (window.confirm(el.confirmText.textContent)) buy();
  }

  function closeConfirm() {
    if (el.confirm.open) el.confirm.close();
    state.pending = null;
  }

  function buy() {
    var item = state.pending;
    if (!item || state.busy) return;
    setBusy(true);
    arena.api.buyItem(item.code).then(function (shop) {
      closeConfirm();
      apply(shop);
      refreshChip();
      toast('Đã mua ' + item.name + ' và đang dùng.', 'success');
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
    arena.api.equipItem(code).then(function (shop) {
      apply(shop);
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
