(function (global) {
  'use strict';
  var arena = global.JlasArena, esc = arena.esc;
  var money = function (value) { return Number(value).toLocaleString('vi-VN'); };
  function mount(root, gameType, onBusy) {
    var shop = null, busy = false, locked = false, pending = null, error = '';
    function items() { return shop ? (shop.sections.find(function (s) { return s.gameType === gameType; }) || {}).items || [] : []; }
    function message(text, kind) { if (global.JlasUI) global.JlasUI.toast(text, kind); }
    function syncDisabled() {
      root.querySelectorAll('[data-skin]').forEach(function (button) {
        var item = items().find(function (entry) { return entry.code === button.dataset.skin; });
        button.disabled = busy || locked || !item || item.equipped || (!item.owned && shop.balance < item.price);
      });
      var confirm = root.querySelector('[data-skin-confirm]');
      if (confirm) confirm.disabled = busy || locked;
      root.setAttribute('aria-busy', String(busy));
    }
    function render() {
      var scroll = root.querySelector('.ar-room-skins__list');
      var left = scroll ? scroll.scrollLeft : 0;
      root.innerHTML = '<header><h2>' + (gameType === 'AIR_DEFENSE' ? 'Máy bay của bạn' : 'Trang phục')
        + '</h2><output aria-label="Số dư">' + (shop ? money(shop.balance) : '…') + ' CP</output></header>'
        + (shop ? '<div class="ar-room-skins__list" role="group" aria-label="Chọn hoặc mua skin">'
          + items().map(function (item) {
            var label = item.equipped ? 'Đang dùng' : item.owned ? 'Dùng' : shop.balance < item.price
              ? 'Thiếu ' + money(item.price - shop.balance) + ' CP' : 'Mua · ' + money(item.price) + ' CP';
            // Same animated preview as the shop; a plane's image is a 4×4 atlas, never shown whole.
            var picture = global.JlasCharacterSkins ? global.JlasCharacterSkins.art(item, gameType)
              : '<img src="' + esc(item.image) + '" alt="" loading="lazy">';
            return '<button type="button" class="ar-room-skins__item' + (item.equipped ? ' is-selected' : '')
              + '" data-skin="' + esc(item.code) + '" aria-pressed="' + item.equipped + '">'
              + '<span class="ar-room-skins__art" aria-hidden="true">' + picture + '</span><strong>' + esc(item.name)
              + '</strong><span>' + esc(label) + '</span></button>';
          }).join('') + '</div>' : '<p class="ar-muted">Đang tải skin…</p>')
        + (error ? '<p class="ar-error-text" role="alert">' + esc(error) + '</p><button type="button" class="ar-btn ar-btn--sm" data-skin-retry>Thử lại</button>' : '')
        + '<dialog class="ar-dialog ar-skin-confirm" aria-labelledby="ar-skin-confirm-title"><div class="ar-dialog__head">'
        + '<h2 id="ar-skin-confirm-title">Xác nhận mua skin</h2></div><div class="ar-dialog__body" data-skin-confirm-body></div>'
        + '<div class="ar-dialog__foot"><button type="button" class="ar-btn" data-skin-cancel>Hủy</button>'
        + '<button type="button" class="ar-btn ar-btn--primary" data-skin-confirm>Mua và dùng</button></div></dialog>';
      if (root.querySelector('.ar-room-skins__list')) root.querySelector('.ar-room-skins__list').scrollLeft = left;
      if (global.JlasCharacterSkins) global.JlasCharacterSkins.mount(root);
      syncDisabled();
    }
    function load() {
      return arena.api.shop().then(function (view) { shop = view; error = ''; render(); })
        .catch(function (failure) { error = failure.message; render(); });
    }
    function act(item) {
      if (busy || locked || !item || item.equipped || (!item.owned && (!shop || shop.balance < item.price))) return;
      busy = true; error = ''; onBusy(true); syncDisabled();
      var dialog = root.querySelector('dialog');
      (item.owned ? arena.api.equipItem(item.code) : arena.api.buyItem(item.code)).then(function (view) {
        if (dialog.open) dialog.close();
        shop = view; pending = null;
        render();
        if (global.RewardFX && global.RewardFX.refreshChip) global.RewardFX.refreshChip(true);
        message('Đang dùng ' + item.name + '.', 'success');
      }).catch(function (failure) {
        if (dialog.open) dialog.close();
        pending = null; error = failure.message; message(error, 'error');
        return load().then(function () { error = failure.message; render(); });
      }).finally(function () { busy = false; onBusy(false); syncDisabled(); });
    }
    root.addEventListener('click', function (event) {
      var button = event.target.closest('button');
      if (!button || button.disabled) return;
      var dialog = root.querySelector('dialog');
      if (button.hasAttribute('data-skin-retry')) { void load(); return; }
      if (button.hasAttribute('data-skin-cancel')) { dialog.close(); pending = null; return; }
      if (button.hasAttribute('data-skin-confirm')) { act(pending); return; }
      if (!button.dataset.skin) return;
      var item = items().find(function (entry) { return entry.code === button.dataset.skin; });
      if (!item) return;
      if (item.owned) { act(item); return; }
      pending = item;
      root.querySelector('[data-skin-confirm-body]').innerHTML = '<p>Mua <strong>' + esc(item.name)
        + '</strong> với <strong>' + money(item.price) + ' CP</strong>?</p><p>Số dư sau khi mua: '
        + money(shop.balance - item.price) + ' CP</p>';
      dialog.showModal();
    });
    render(); void load();
    return { setDisabled: function (disabled) {
      locked = disabled;
      var dialog = root.querySelector('dialog');
      if (locked && dialog && dialog.open && !busy) { dialog.close(); pending = null; }
      syncDisabled();
    } };
  }
  global.JlasRoomSkins = { mount: mount };
})(window);
