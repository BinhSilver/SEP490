/* Trang admin "Trang phục game" (/admin/game-skins). Trang phục mới là gói skin (.zip: manifest.json + ảnh PNG, cùng
   định dạng skin gốc) hoặc dùng lại chuyển động của một skin gốc. Kiểu "ảnh tĩnh" cũ chỉ còn cho trang phục đã tạo
   trước đây. Định dạng gói: docs/game-skin-packs.md; "Tải gói mẫu" lấy zip của một skin gốc làm khuôn. */
(function () {
  'use strict';
  var A = window.Admin, API = '/api/admin/game-skins';
  var names = { NEKO_CATCH: 'Bắt cá', AIR_DEFENSE: 'Bắn máy bay' };
  var WEAPONS = { 'halo-fighter': 'Thiên Luân Phán Xét', 'aegis-frame': 'Vệ Thần Thiên Giới',
    'split-core': 'Vệ Binh Tinh Tú', 'void-blade': 'Tử Thần' };

  /** Ảnh máy bay gốc là atlas nhiều khung hình; vẽ một khung qua canvas như trang cửa hàng thay vì hiện cả tấm. */
  function atlasRenderer(renderCode, image) {
    var skins = window.JlasCharacterSkins;
    return renderCode && renderCode !== 'CUSTOM' && renderCode !== 'PACK' && /\/assets\/space\/skins\//.test(image || '')
      && skins && skins.supports(renderCode) ? renderCode : null;
  }
  /** Ảnh / chuyển động của một trang phục: gói skin, skin gốc dạng atlas, hoặc ảnh. */
  function art(item, size, attrs) {
    var box = 'width="' + size * 2 + '" height="' + size * 2 + '" style="width:' + size + 'px;height:' + size + 'px" ' + (attrs || '');
    if (item.renderCode === 'PACK' && item.packPath) {
      return '<canvas class="skin-admin__sprite" ' + box + ' data-skin-pack="' + A.esc(item.packPath)
        + '" data-skin-game="' + (item.gameType === 'AIR_DEFENSE' ? 'ship' : 'cat') + '"></canvas>';
    }
    var renderer = atlasRenderer(item.renderCode, item.image);
    if (renderer) return '<canvas class="skin-admin__sprite" ' + box + ' data-character-skin="' + A.esc(renderer) + '"></canvas>';
    return '<img src="' + A.esc(item.image) + '" alt="" width="' + size + '" height="' + size + '" ' + (attrs || '') + '>';
  }
  function mountArt(container) { if (window.JlasCharacterSkins) window.JlasCharacterSkins.mount(container); }
  function kind(item) {
    if (item.renderCode === 'PACK') return 'Gói trang phục' + (item.weaponTheme ? ' · vũ khí ' + (WEAPONS[item.weaponTheme] || item.weaponTheme) : '');
    if (item.renderCode === 'CUSTOM') return 'Ảnh tĩnh (kiểu cũ)';
    return item.renderCode === item.code ? 'Trang phục gốc' : 'Dùng lại ' + item.renderCode;
  }
  /** Tải tệp từ API admin (cần header đăng nhập, nên không dùng link thường). */
  async function download(url, filename) {
    var response = await fetch(url, { headers: window.JlasAuth.authHeaders() });
    if (!response.ok) { var body = await response.json().catch(function () { return {}; }); throw new Error(body.message || 'Không tải được gói mẫu'); }
    var link = document.createElement('a');
    link.href = URL.createObjectURL(await response.blob()); link.download = filename;
    document.body.appendChild(link); link.click(); link.remove();
    setTimeout(function () { URL.revokeObjectURL(link.href); }, 1000);
  }
  async function upload(url, file) {
    var data = new FormData(); data.append('file', file);
    var headers = window.JlasAuth.authHeaders(); delete headers['Content-Type'];
    var response = await fetch(url, { method: 'POST', headers: headers, body: data });
    var result = await response.json().catch(function () { return {}; });
    if (!response.ok) throw new Error(result.message || 'Không tải được tệp');
    return result.data;
  }

  A.section('game-skins', { render: async function (host) {
    // tiers: bậc trang phục (Thường / Hiếm / Huyền thoại) và giá đi kèm, lấy từ máy chủ.
    var loaded = await Promise.all([A.api.get(API), A.api.get(API + '/renderers'), A.api.get(API + '/pack-options'),
      A.api.get(API + '/tiers').catch(function () { return []; })]);
    var items = loaded[0], renderers = loaded[1], options = loaded[2], tiers = loaded[3], editing = false;
    var game = '';
    host.innerHTML = '<div class="skin-admin">'
      + '<div class="ga-toolbar">'
      + '<div class="ga-search"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i><input type="search" class="input" aria-label="Tìm trang phục" placeholder="Tìm tên trang phục…"></div>'
      + '<select class="input" aria-label="Game" data-game-filter><option value="">Tất cả game</option>'
      + '<option value="NEKO_CATCH">' + A.esc(names.NEKO_CATCH) + '</option><option value="AIR_DEFENSE">' + A.esc(names.AIR_DEFENSE) + '</option></select>'
      + '<select class="input" aria-label="Trạng thái" data-status-filter><option value="">Đang quản lý</option><option value="active">Đang bán</option><option value="hidden">Đã ẩn</option><option value="deleted">Đã xóa</option></select>'
      + '<button type="button" class="btn btn--primary" data-create><i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm trang phục</button></div>'
      + '<div data-editor></div><div data-effects-popup></div><p class="adm-muted" data-count aria-live="polite"></p><div data-results></div></div>';
    var root = host.querySelector('.skin-admin'), search = root.querySelector('input[type=search]');
    var statusFilter = root.querySelector('[data-status-filter]'), results = root.querySelector('[data-results]');
    var gameFilter = root.querySelector('[data-game-filter]');
    A.filterPopover(root.querySelector('.ga-toolbar'));
    function state(item) { return item.deleted ? 'deleted' : item.active ? 'active' : 'hidden'; }
    function card(item) {
      var badges = (item.starter ? '<span class="ga-badge ga-badge--default"><i class="fa-solid fa-star" aria-hidden="true"></i> Mặc định</span>' : '')
        + (item.renderCode === 'PACK' ? '<span class="ga-badge ga-badge--pack"><i class="fa-solid fa-box-open" aria-hidden="true"></i> Gói trang phục</span>' : '');
      var label = { active: 'Đang bán', hidden: 'Đã ẩn', deleted: 'Đã xóa' }[state(item)];
      var price = item.price > 0 ? '<span class="ga-chip ga-chip--price"><i class="fa-solid fa-coins" aria-hidden="true"></i> ' + item.price.toLocaleString('vi-VN') + ' CP</span>'
        : '<span class="ga-chip ga-chip--free">Miễn phí</span>';
      var tier = tiers.find(function (t) { return t.code === item.tier; });
      if (tier && !item.starter) price = '<span class="ga-chip ga-chip--tier-' + A.esc(tier.code.toLowerCase()) + '">' + A.esc(tier.label) + '</span>' + price;
      var actions = item.deleted ? '' : '<button type="button" class="ga-icon-btn ga-icon-btn--edit" data-edit="' + A.esc(item.code) + '" title="Sửa" aria-label="Sửa ' + A.esc(item.name) + '"><i class="fa-solid fa-pen" aria-hidden="true"></i></button>'
        + '<button type="button" class="ga-icon-btn" data-effects="' + A.esc(item.code) + '" title="Xem hiệu ứng" aria-label="Xem hiệu ứng của ' + A.esc(item.name) + '"><i class="fa-solid fa-play" aria-hidden="true"></i></button>'
        + (item.starter ? '' : '<button type="button" class="ga-icon-btn ga-icon-btn--star" data-starter="' + A.esc(item.code) + '" title="Đặt làm trang phục mặc định" aria-label="Đặt ' + A.esc(item.name) + ' làm trang phục mặc định"><i class="fa-regular fa-star" aria-hidden="true"></i></button>'
          + '<button type="button" class="ga-icon-btn ga-icon-btn--danger" data-delete="' + A.esc(item.code) + '" title="Xóa" aria-label="Xóa ' + A.esc(item.name) + '"><i class="fa-solid fa-trash" aria-hidden="true"></i></button>');
      return '<article class="ga-card" data-state="' + state(item) + '">'
        + '<div class="ga-stage ga-stage--' + item.gameType + ' ga-stage--action" role="button" tabindex="0" data-effects="' + A.esc(item.code) + '" aria-label="Xem hiệu ứng của ' + A.esc(item.name) + '">'
        + '<span class="ga-play" aria-hidden="true"><i class="fa-solid fa-play"></i></span><div class="ga-badges">' + badges + '</div>'
        + '<span class="ga-status" data-state="' + state(item) + '">' + label + '</span>' + art(item, 140) + '</div>'
        + '<div class="ga-body"><strong class="ga-name" title="' + A.esc(item.name) + '">' + A.esc(item.name) + '</strong>'
        + '<div class="ga-meta">' + price + '<span class="ga-chip">' + A.esc(names[item.gameType]) + '</span><span class="ga-chip">' + A.esc(kind(item)) + '</span></div></div>'
        + '<div class="ga-actions">' + actions + '</div></article>';
    }
    function paint() {
      var q = search.value.trim().toLocaleLowerCase('vi'), status = statusFilter.value;
      var shown = items.filter(function (item) {
        return (!game || item.gameType === game) && (item.name + ' ' + item.code).toLocaleLowerCase('vi').includes(q)
          && (status === 'deleted' ? item.deleted : !item.deleted && (!status || (status === 'active' ? item.active : !item.active)));
      });
      root.querySelector('[data-count]').textContent = shown.length + ' trang phục';
      if (!shown.length) { results.innerHTML = A.empty('fa-shirt', 'Không có trang phục phù hợp'); return; }
      // Skin mặc định đứng đầu mỗi game, rồi đến skin đang bán.
      shown.sort(function (a, b) { return (b.starter - a.starter) || (b.active - a.active); });
      results.innerHTML = '<div class="ga-grid">' + shown.map(card).join('') + '</div>';
      mountArt(results);
    }
    gameFilter.addEventListener('change', function () { game = gameFilter.value; paint(); });
    async function reload() { items = await A.api.get(API); paint(); }
    search.addEventListener('input', paint);
    statusFilter.addEventListener('change', paint);

    function editor(item) {
      if (editing) return;
      var current = item || { code: '', gameType: 'NEKO_CATCH', name: '', description: '', price: 500, tier: 'COMMON', image: '', renderCode: 'PACK', active: true, packPath: null, weaponTheme: null };
      var tierOptions = tiers.map(function (t) {
        return '<option value="' + A.esc(t.code) + '"' + (t.code === (current.tier || 'COMMON') ? ' selected' : '') + '>'
          + A.esc(t.label) + ' · ' + t.price.toLocaleString('vi-VN') + ' CP</option>';
      }).join('');
      var mount = root.querySelector('[data-editor]');
      mount.innerHTML = '<div class="ga-backdrop" data-cancel></div><aside class="ga-drawer" role="dialog" aria-modal="true" aria-labelledby="skin-editor-title">'
        + '<form class="skin-editor"><header><h2 id="skin-editor-title">' + (item ? 'Sửa trang phục' : 'Thêm trang phục') + '</h2>'
        + '<button type="button" class="ga-icon-btn" data-cancel aria-label="Đóng"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button></header>'
        + '<div class="ga-drawer-body"><div class="ga-stage" data-preview-stage><span data-preview-slot></span></div>'
        + '<div class="skin-editor__grid"><label>Mã trang phục<input class="input" name="code" required maxlength="40" pattern="[a-z0-9][a-z0-9-]{1,39}" placeholder="cat-ten-skin" value="' + A.esc(current.code) + '" ' + (item ? 'readonly' : '') + '></label>'
        + '<label>Game<select class="input" name="gameType" ' + (item ? 'disabled' : '') + '><option value="NEKO_CATCH">Bắt cá</option><option value="AIR_DEFENSE">Bắn máy bay</option></select></label>'
        + '<label>Tên trang phục<input class="input" name="name" required maxlength="100" value="' + A.esc(current.name) + '"></label>'
        + (current.starter
          ? '<label>Bậc<input class="input" value="Mặc định · miễn phí" readonly></label>'
          : '<label>Bậc (quyết định giá)<select class="input" name="tier">' + tierOptions + '</select></label>')
        + '<label>Hình trong game<select class="input" name="renderCode"></select></label>'
        + '<label data-weapon-field>Bộ vũ khí<select class="input" name="weaponTheme"><option value="">Vũ khí mặc định</option>'
        + Object.keys(WEAPONS).map(function (code) { return '<option value="' + code + '">Mượn của ' + A.esc(WEAPONS[code]) + '</option>'; }).join('') + '</select></label>'
        + '<label class="skin-editor__description">Mô tả<textarea class="textarea" name="description" maxlength="500" rows="2">' + A.esc(current.description) + '</textarea></label></div>'
        + '<section class="skin-editor__pack" data-pack-field>'
        + '<p class="adm-muted">Gói trang phục là tệp .zip gồm <code>manifest.json</code> và các ảnh PNG, cùng định dạng trang phục gốc của game. Tải một gói mẫu về, thay ảnh, sửa manifest rồi nén lại.</p>'
        + '<div class="skin-editor__art"><label class="btn btn--primary">Tải gói trang phục (.zip)<input type="file" accept=".zip,application/zip" data-pack-upload></label>'
        + '<select class="input" data-template aria-label="Chọn trang phục gốc làm gói mẫu"></select>'
        + '<button type="button" class="btn btn--secondary" data-template-download><i class="fa-solid fa-download" aria-hidden="true"></i> Tải gói mẫu</button></div>'
        + '<p class="adm-muted" data-pack-status></p></section>'
        + '<div class="skin-editor__art"><label class="btn btn--secondary">Đổi ảnh đại diện (PNG/JPG)<input type="file" accept="image/png,image/jpeg" data-upload></label>'
        + '<span class="adm-muted">Ảnh hiện trong cửa hàng khi chưa tải xong chuyển động.</span></div>'
        + '<input type="hidden" name="image" value="' + A.esc(current.image) + '">'
        + '<label class="skin-editor__active"><input type="checkbox" name="active" ' + (current.active ? 'checked' : '') + ' ' + (current.starter ? 'disabled' : '') + '> Hiển thị trong cửa hàng</label>'
        + '<p class="adm-error" data-error role="alert"></p></div><footer><button type="button" class="btn btn--secondary" data-cancel>Hủy</button><button class="btn btn--primary" type="submit">Lưu trang phục</button></footer></form></aside>';
      document.body.classList.add('ga-drawer-open');
      function closeEditor() {
        if (editing) return;
        mount.innerHTML = '';
        document.body.classList.remove('ga-drawer-open');
        document.removeEventListener('keydown', onKey);
      }
      function onKey(event) { if (event.key === 'Escape') closeEditor(); }
      document.addEventListener('keydown', onKey);
      var form = mount.querySelector('form'), fields = form.elements, image = fields.namedItem('image');
      var previewSlot = form.querySelector('[data-preview-slot]'), packStatus = form.querySelector('[data-pack-status]');
      var errorBox = form.querySelector('[data-error]'), packPath = current.packPath;
      fields.namedItem('gameType').value = current.gameType;
      fields.namedItem('weaponTheme').value = current.weaponTheme || '';

      function game() { return fields.namedItem('gameType').value; }
      function render() { return fields.namedItem('renderCode').value; }
      function sync() {
        var pack = render() === 'PACK';
        form.querySelector('[data-pack-field]').hidden = !pack;
        form.querySelector('[data-weapon-field]').hidden = !(pack && game() === 'AIR_DEFENSE');
        var item = { renderCode: render(), image: image.value, packPath: pack ? packPath : null, gameType: game() };
        form.querySelector('[data-preview-stage]').className = 'ga-stage ga-stage--' + game();
        previewSlot.innerHTML = item.image || item.packPath ? art(item, 200, 'aria-label="Xem trước trang phục" role="img"')
          : '<p class="ga-stage-empty">Tải gói trang phục (.zip) lên để xem trước chuyển động.</p>';
        mountArt(previewSlot);
        if (pack && !packPath) packStatus.textContent = 'Chưa có gói trang phục. Tải tệp .zip lên để xem trước chuyển động.';
      }
      function rendererOptions(value) {
        var legacy = item && item.renderCode === 'CUSTOM' ? '<option value="CUSTOM">Ảnh tĩnh (kiểu cũ)</option>' : '';
        fields.namedItem('renderCode').innerHTML = '<option value="PACK">Gói trang phục tải lên</option>' + legacy
          + renderers.filter(function (r) { return r.gameType === game(); }).map(function (r) {
            return '<option value="' + A.esc(r.code) + '">' + A.esc(r.name) + ' · dùng lại chuyển động có sẵn</option>'; }).join('');
        fields.namedItem('renderCode').value = value || 'PACK';
        form.querySelector('[data-template]').innerHTML = (options.templates[game()] || []).map(function (code) {
          var source = renderers.find(function (r) { return r.code === code; });
          return '<option value="' + A.esc(code) + '">Mẫu: ' + A.esc(source ? source.name : code) + '</option>'; }).join('');
      }
      rendererOptions(current.renderCode); sync();
      if (packPath) packStatus.textContent = 'Đang dùng gói ' + packPath;
      mount.querySelectorAll('[data-cancel]').forEach(function (button) { button.addEventListener('click', closeEditor); });
      fields.namedItem('gameType').addEventListener('change', function () { packPath = null; image.value = ''; rendererOptions(); sync(); });
      fields.namedItem('renderCode').addEventListener('change', function () {
        var renderer = renderers.find(function (r) { return r.code === render(); });
        if (renderer) image.value = renderer.image;
        sync();
      });
      form.querySelector('[data-template-download]').addEventListener('click', async function () {
        var code = form.querySelector('[data-template]').value;
        if (!code) return;
        try { errorBox.textContent = ''; await download(API + '/pack-template/' + encodeURIComponent(code), 'goi-mau-' + code + '.zip'); }
        catch (error) { errorBox.textContent = error.message; }
      });
      async function busyUpload(event, run) {
        var file = event.target.files[0]; if (!file) return;
        var save = form.querySelector('[type=submit]');
        editing = true; save.disabled = true; errorBox.textContent = 'Đang tải lên…';
        try { await run(file); errorBox.textContent = ''; }
        catch (errorValue) { errorBox.textContent = errorValue.message; }
        finally { editing = false; save.disabled = false; event.target.value = ''; }
      }
      form.querySelector('[data-pack-upload]').addEventListener('change', function (event) {
        busyUpload(event, async function (file) {
          var info = await upload(API + '/pack?gameType=' + encodeURIComponent(game()), file);
          packPath = info.packPath; image.value = info.preview;
          packStatus.textContent = 'Gói hợp lệ: ' + info.summary + '.';
          sync();
        });
      });
      form.querySelector('[data-upload]').addEventListener('change', function (event) {
        busyUpload(event, async function (file) {
          image.value = (await upload(API + '/image', file)).url;
          sync();
        });
      });
      form.addEventListener('submit', async function (event) {
        event.preventDefault(); if (editing || !form.reportValidity()) return;
        var pack = render() === 'PACK';
        if (pack && !packPath) { errorBox.textContent = 'Tải gói trang phục (.zip) lên trước khi lưu.'; return; }
        if (!image.value) { errorBox.textContent = 'Trang phục cần ảnh đại diện.'; return; }
        editing = true; var save = form.querySelector('[type=submit]'); save.disabled = true; save.textContent = 'Đang lưu…';
        var body = { code: fields.namedItem('code').value.trim(), gameType: game(), name: fields.namedItem('name').value.trim(),
          price: current.price, tier: fields.namedItem('tier') ? fields.namedItem('tier').value : (current.tier || 'COMMON'),
          image: image.value.trim(), description: fields.namedItem('description').value.trim(),
          renderCode: render(), active: fields.namedItem('active').checked, packPath: pack ? packPath : null,
          weaponTheme: pack && game() === 'AIR_DEFENSE' ? (fields.namedItem('weaponTheme').value || null) : null };
        try {
          await (item ? A.api.put(API + '/' + encodeURIComponent(item.code), body) : A.api.post(API, body));
          editing = false; closeEditor(); await reload(); A.toast('Đã lưu trang phục.', 'success');
        } catch (error) { errorBox.textContent = error.message; }
        finally { editing = false; save.disabled = false; save.textContent = 'Lưu trang phục'; }
      });
      form.querySelector(item ? '[name=name]' : '[name=code]').focus();
    }
    /**
     * Popup xem hiệu ứng của một skin: trang /admin/game-effects/preview trong iframe, khoá vào đúng game và skin đó
     * (kể cả gói skin và bộ vũ khí mượn).
     */
    function openEffects(item) {
      var url = '/admin/game-effects/preview';
      if (item) {
        var query = new URLSearchParams({ game: item.gameType === 'AIR_DEFENSE' ? 'air' : 'cat', skin: item.code, id: String(item.id),
          render: item.renderCode, image: item.image || '', name: item.name });
        if (item.packPath) query.set('pack', item.packPath);
        if (item.weaponTheme) query.set('weapon', item.weaponTheme);
        url += '?' + query;
      }
      var popup = root.querySelector('[data-effects-popup]');
      var title = item ? item.name : 'Hiệu ứng trò chơi';
      popup.innerHTML = '<div class="ga-backdrop" data-close></div><div class="ga-modal" role="dialog" aria-modal="true" aria-labelledby="ga-effects-title">'
        + '<header><div><span class="ga-modal__kicker">' + (item ? 'Hiệu ứng trong trận · ' + A.esc(names[item.gameType]) : 'Bắn máy bay · Bắt cá · Lật thẻ') + '</span>'
        + '<h2 id="ga-effects-title">' + A.esc(title) + '</h2></div>'
        + '<button type="button" class="ga-icon-btn" data-close aria-label="Đóng"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button></header>'
        + '<div class="ga-modal__body"><iframe src="' + A.esc(url) + '" title="Xem hiệu ứng ' + A.esc(title) + '" allow="autoplay"></iframe></div></div>';
      document.body.classList.add('ga-drawer-open');
      // Gỡ iframe khi đóng để dừng nhạc và hiệu ứng đang chạy.
      function close() {
        popup.innerHTML = '';
        document.body.classList.remove('ga-drawer-open');
        document.removeEventListener('keydown', onKey);
      }
      function onKey(event) { if (event.key === 'Escape') close(); }
      document.addEventListener('keydown', onKey);
      popup.querySelectorAll('[data-close]').forEach(function (element) { element.addEventListener('click', close); });
      popup.querySelector('[data-close].ga-icon-btn').focus();
    }
    results.addEventListener('keydown', function (event) {
      var stage = event.target.closest('.ga-stage[data-effects]');
      if (stage && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); stage.click(); }
    });
    root.querySelector('[data-create]').addEventListener('click', function () { editor(null); });
    results.addEventListener('click', async function (event) {
      var effects = event.target.closest('[data-effects]');
      if (effects) { openEffects(items.find(function (item) { return item.code === effects.dataset.effects; })); return; }
      var edit = event.target.closest('[data-edit]');
      if (edit) { editor(items.find(function (item) { return item.code === edit.dataset.edit; })); return; }
      var starter = event.target.closest('[data-starter]');
      if (starter && !editing) {
        var chosen = items.find(function (i) { return i.code === starter.dataset.starter; });
        var previous = items.find(function (i) { return i.gameType === chosen.gameType && i.starter && !i.deleted; });
        var ok = await A.decide({ title: 'Đặt ' + chosen.name + ' làm trang phục mặc định?', noReason: true, confirmText: 'Đặt mặc định',
          message: 'Trang phục mặc định của game ' + names[chosen.gameType] + ' sẽ <strong>miễn phí cho mọi người</strong> và tự dùng cho người chưa chọn trang phục.'
            + (chosen.price > 0 ? ' Giá ' + chosen.price.toLocaleString('vi-VN') + ' CP sẽ về 0.' : '')
            + (previous ? ' Người đang mặc ' + A.esc(previous.name) + ' vẫn giữ được trang phục đó.' : '') });
        if (!ok) return;
        starter.disabled = true;
        try { await A.api.put(API + '/' + encodeURIComponent(chosen.code) + '/starter'); await reload(); A.toast('Đã đặt ' + chosen.name + ' làm trang phục mặc định.', 'success'); }
        catch (error) { A.toast(error.message, 'error'); starter.disabled = false; }
        return;
      }
      var remove = event.target.closest('[data-delete]'); if (!remove || editing) return;
      var item = items.find(function (i) { return i.code === remove.dataset.delete; });
      var confirmed = await A.decide({ title: 'Xóa ' + item.name + '?', noReason: true, danger: true, confirmText: 'Xóa trang phục', message: 'Trang phục sẽ ngừng bán. Người đã mua vẫn giữ quyền sử dụng.' });
      if (!confirmed) return;
      remove.disabled = true;
      try { await A.api.delete(API + '/' + encodeURIComponent(item.code)); await reload(); A.toast('Đã xóa trang phục.', 'success'); }
      catch (error) { A.toast(error.message, 'error'); remove.disabled = false; }
    });
    paint();
  } });
})();
