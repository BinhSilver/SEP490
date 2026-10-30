/* Trang admin "Khung avatar" (/admin/avatar-frames): danh sách có xem trước khung động trên avatar mẫu, form
   thêm/sửa, tải ảnh PNG. Quy tắc: ẩn = ngừng bán nhưng người đã mua vẫn đeo; chỉ xoá được khung chưa ai mua. */
(function () {
  'use strict';
  var A = window.Admin, API = '/api/admin/avatar-frames';
  var PREVIEW = 'frame-admin-preview';

  /** Avatar mẫu có khung động, dùng markup thật của avatar-frame.js để xem đúng như người dùng thấy. */
  function preview(item, size) {
    var frames = window.JlasAvatarFrame;
    var fx = '';
    if (frames && item.image) {
      frames.register([{ code: item.code, image: item.image, theme: item.theme, gems: item.gems }]);
      fx = frames.markup(item.code);
    }
    return '<span class="af-host frame-admin__avatar' + (fx ? ' has-frame' : '') + '" style="width:' + size + 'px;height:' + size + 'px">'
      + '<span class="frame-admin__face" aria-hidden="true"><i class="fa-solid fa-user"></i></span>' + fx + '</span>';
  }

  function state(item) {
    return item.deleted ? 'deleted' : item.active ? 'active' : 'hidden';
  }

  A.section('avatar-frames', { render: async function (host) {
    var loaded = await Promise.all([A.api.get(API), A.api.get(API + '/themes')]);
    var items = loaded[0], themes = loaded[1], editing = false;
    var status = '';
    host.innerHTML = '<div class="skin-admin frame-admin">'
      + '<div class="ga-toolbar">'
      + '<div class="ga-search"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i><input type="search" class="input" aria-label="Tìm khung" placeholder="Tìm tên khung…"></div>'
      + '<select class="input" aria-label="Trạng thái" data-status-filter><option value="">Đang quản lý</option><option value="active">Đang bán</option>'
      + '<option value="hidden">Đã ẩn</option><option value="deleted">Đã xoá</option></select>'
      + '<button type="button" class="btn btn--primary" data-create><i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm khung</button></div>'
      + '<div data-editor></div><p class="adm-muted" data-count aria-live="polite"></p><div data-results></div></div>';
    var root = host.querySelector('.frame-admin'), search = root.querySelector('input[type=search]');
    var results = root.querySelector('[data-results]');

    function card(item) {
      var canDelete = !item.deleted && item.owners === 0;
      var label = { active: 'Đang bán', hidden: 'Đã ẩn', deleted: 'Đã xoá' }[state(item)];
      var price = item.price > 0 ? '<span class="ga-chip ga-chip--price"><i class="fa-solid fa-coins" aria-hidden="true"></i> ' + item.price.toLocaleString('vi-VN') + ' CP</span>'
        : '<span class="ga-chip ga-chip--free">Miễn phí</span>';
      var actions = item.deleted ? '' : '<button type="button" class="ga-icon-btn ga-icon-btn--edit" data-edit="' + A.esc(item.code) + '" title="Sửa" aria-label="Sửa ' + A.esc(item.name) + '"><i class="fa-solid fa-pen" aria-hidden="true"></i></button>'
        + '<button type="button" class="ga-icon-btn ga-icon-btn--danger" data-delete="' + A.esc(item.code) + '"' + (canDelete ? ' title="Xoá"' : ' disabled title="Đã có người mua: chỉ ẩn được, không xoá"')
        + ' aria-label="Xoá ' + A.esc(item.name) + '"><i class="fa-solid fa-trash" aria-hidden="true"></i></button>';
      return '<article class="ga-card" data-state="' + state(item) + '">'
        + '<div class="ga-stage ga-stage--frame"><div class="ga-badges"></div>'
        + '<span class="ga-status" data-state="' + state(item) + '">' + label + '</span>' + preview(item, 76) + '</div>'
        + '<div class="ga-body"><strong class="ga-name" title="' + A.esc(item.name) + '">' + A.esc(item.name) + '</strong>'
        + '<div class="ga-meta">' + price + '<span class="ga-chip"><span data-name-style="' + A.esc(item.nameStyle) + '">Màu tên: ' + A.esc(item.nameStyleLabel) + '</span></span>'
        + '<span class="ga-chip"><i class="fa-solid fa-user" aria-hidden="true"></i> ' + item.owners.toLocaleString('vi-VN') + ' người mua</span></div></div>'
        + '<div class="ga-actions">' + actions + '</div></article>';
    }
    function paint() {
      var q = search.value.trim().toLocaleLowerCase('vi');
      var shown = items.filter(function (item) {
        return (item.name + ' ' + item.code).toLocaleLowerCase('vi').includes(q)
          && (status === 'deleted' ? item.deleted : !item.deleted && (!status || status === state(item)));
      });
      root.querySelector('[data-count]').textContent = shown.length + ' khung';
      if (!shown.length) { results.innerHTML = A.empty('fa-crown', 'Không có khung phù hợp'); return; }
      results.innerHTML = '<div class="ga-grid">' + shown.map(card).join('') + '</div>';
    }
    A.filterPopover(root.querySelector('.ga-toolbar'));
    root.querySelector('[data-status-filter]').addEventListener('change', function (event) {
      status = event.target.value; paint();
    });

    async function reload() { items = await A.api.get(API); paint(); }
    search.addEventListener('input', paint);

    function editor(item) {
      if (editing) return;
      var current = item || { code: 'frame-', name: '', description: '', price: 300, image: '', theme: 'cosmic', sortOrder: items.length + 1, active: true, owners: 0, gems: null };
      var mount = root.querySelector('[data-editor]');
      mount.innerHTML = '<div class="ga-backdrop" data-cancel></div><aside class="ga-drawer" role="dialog" aria-modal="true" aria-labelledby="frame-editor-title">'
        + '<form class="skin-editor"><header><h2 id="frame-editor-title">' + (item ? 'Sửa khung avatar' : 'Thêm khung avatar') + '</h2>'
        + '<button type="button" class="ga-icon-btn" data-cancel aria-label="Đóng"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button></header>'
        + '<div class="ga-drawer-body"><div class="ga-stage ga-stage--frame"><span data-preview-slot></span></div>'
        + '<div class="skin-editor__grid">'
        + '<label>Mã khung<input class="input" name="code" required maxlength="40" pattern="frame-[a-z0-9][a-z0-9-]{1,33}" placeholder="frame-ten-khung" value="' + A.esc(current.code) + '" ' + (item ? 'readonly' : '') + '></label>'
        + '<label>Tên khung<input class="input" name="name" required maxlength="100" value="' + A.esc(current.name) + '"></label>'
        + '<label>Giá CP<input class="input" name="price" type="number" min="0" max="1000000" step="1" required value="' + current.price + '"></label>'
        + '<label>Thứ tự hiển thị<input class="input" name="sortOrder" type="number" min="0" max="9999" step="1" required value="' + current.sortOrder + '"></label>'
        + '<label>Chủ đề hiệu ứng<select class="input" name="theme">' + Object.keys(themes).map(function (code) {
            return '<option value="' + A.esc(code) + '">' + A.esc(themes[code]) + ' (' + A.esc(code) + ')</option>'; }).join('') + '</select>'
        + '</label>'
        + '<label>Ảnh khung<input class="input" name="image" required maxlength="1024" value="' + A.esc(current.image) + '" placeholder="/uploads/avatar-frames/…"></label>'
        + '<label class="skin-editor__description">Mô tả<textarea class="textarea" name="description" maxlength="500" rows="2">' + A.esc(current.description) + '</textarea></label></div>'
        + '<section class="skin-editor__pack"><div class="skin-editor__art">'
        + '<label class="btn btn--primary">Tải gói khung (.zip)<input type="file" accept=".zip,application/zip" data-pack-upload></label>'
        + '<label class="btn btn--secondary">Chỉ tải ảnh<input type="file" accept="image/png,image/webp" data-upload></label></div>'
        + '<div class="skin-editor__art"><select class="input" data-template aria-label="Chọn khung làm gói mẫu">'
        + items.filter(function (frame) { return !frame.deleted; }).map(function (frame) {
            return '<option value="' + A.esc(frame.code) + '">Mẫu: ' + A.esc(frame.name) + '</option>'; }).join('') + '</select>'
        + '<button type="button" class="btn btn--secondary" data-template-download><i class="fa-solid fa-download" aria-hidden="true"></i> Tải gói mẫu</button></div>'
        + '<p class="adm-muted" data-pack-status></p></section>'
        + '<label class="skin-editor__active"><input type="checkbox" name="active" ' + (current.active ? 'checked' : '') + '> Đang bán trong cửa hàng'
        + (current.owners ? ' <span class="frame-admin__muted">(bỏ chọn để ẩn; ' + current.owners + ' người đã mua vẫn đeo được)</span>' : '') + '</label>'
        + '<p class="adm-error" data-error role="alert"></p></div><footer><button type="button" class="btn btn--secondary" data-cancel>Huỷ</button><button class="btn btn--primary" type="submit">Lưu khung</button></footer></form></aside>';
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
      var slot = form.querySelector('[data-preview-slot]'), status = form.querySelector('[data-pack-status]');
      fields.namedItem('theme').value = current.theme;
      // Vị trí hiệu ứng riêng (đá chớp sáng, quả cầu, giọt treo) đo trên đúng một ảnh: đổi ảnh khác thì không dùng nữa.
      var gems = current.gems || null, gemsImage = current.image;
      function activeGems() { return gems && image.value.trim() === gemsImage ? gems : null; }

      function refresh() {
        slot.innerHTML = image.value.trim()
          ? preview({ code: PREVIEW, image: image.value.trim(), theme: fields.namedItem('theme').value, gems: activeGems() }, 120)
          : '<p class="ga-stage-empty">Tải gói khung (.zip) hoặc ảnh khung lên để xem trước trên avatar.</p>';
      }
      async function upload(url, file) {
        var data = new FormData(); data.append('file', file);
        var headers = window.JlasAuth.authHeaders(); delete headers['Content-Type'];
        var response = await fetch(url, { method: 'POST', headers: headers, body: data });
        var result = await response.json().catch(function () { return {}; });
        if (!response.ok) throw new Error(result.message || 'Không tải được tệp');
        return result.data;
      }
      async function busy(event, run) {
        var file = event.target.files[0]; if (!file) return;
        var error = form.querySelector('[data-error]'), save = form.querySelector('[type=submit]');
        editing = true; save.disabled = true; error.textContent = 'Đang tải lên…';
        try { await run(file); error.textContent = ''; }
        catch (errorValue) { error.textContent = errorValue.message; }
        finally { editing = false; save.disabled = false; event.target.value = ''; }
      }
      refresh(); A.digitsOnly(fields.namedItem('price')); A.digitsOnly(fields.namedItem('sortOrder'));
      mount.querySelectorAll('[data-cancel]').forEach(function (button) { button.addEventListener('click', closeEditor); });
      image.addEventListener('input', refresh);
      fields.namedItem('theme').addEventListener('change', refresh);
      form.querySelector('[data-pack-upload]').addEventListener('change', function (event) {
        busy(event, async function (file) {
          var pack = await upload(API + '/pack', file);
          image.value = pack.image; gems = pack.gems || null; gemsImage = pack.image;
          status.textContent = 'Gói hợp lệ: ' + pack.summary + '.';
          refresh();
        });
      });
      form.querySelector('[data-upload]').addEventListener('change', function (event) {
        busy(event, async function (file) {
          image.value = (await upload(API + '/image', file)).url; gems = null; gemsImage = image.value;
          status.textContent = '';
          refresh();
        });
      });
      form.querySelector('[data-template-download]').addEventListener('click', async function () {
        var code = form.querySelector('[data-template]').value, error = form.querySelector('[data-error]');
        if (!code) return;
        try {
          error.textContent = '';
          var response = await fetch(API + '/pack-template/' + encodeURIComponent(code), { headers: window.JlasAuth.authHeaders() });
          if (!response.ok) { var body = await response.json().catch(function () { return {}; }); throw new Error(body.message || 'Không tải được gói mẫu'); }
          var link = document.createElement('a');
          link.href = URL.createObjectURL(await response.blob()); link.download = 'goi-khung-' + code + '.zip';
          document.body.appendChild(link); link.click(); link.remove();
          setTimeout(function () { URL.revokeObjectURL(link.href); }, 1000);
        } catch (errorValue) { error.textContent = errorValue.message; }
      });
      form.addEventListener('submit', async function (event) {
        event.preventDefault(); if (editing || !form.reportValidity()) return;
        editing = true; var save = form.querySelector('[type=submit]'); save.disabled = true; save.textContent = 'Đang lưu…';
        var body = { code: fields.namedItem('code').value.trim(), name: fields.namedItem('name').value.trim(),
          description: fields.namedItem('description').value.trim(), price: Number(fields.namedItem('price').value),
          image: image.value.trim(), theme: fields.namedItem('theme').value, sortOrder: Number(fields.namedItem('sortOrder').value),
          active: fields.namedItem('active').checked, gems: activeGems() ? JSON.stringify(activeGems()) : '' };
        try {
          await (item ? A.api.put(API + '/' + encodeURIComponent(item.code), body) : A.api.post(API, body));
          editing = false; closeEditor(); await reload(); A.toast('Đã lưu khung avatar.', 'success');
        } catch (error) { form.querySelector('[data-error]').textContent = error.message; }
        finally { editing = false; save.disabled = false; save.textContent = 'Lưu khung'; }
      });
      form.querySelector(item ? '[name=name]' : '[name=code]').focus();
    }

    root.querySelector('[data-create]').addEventListener('click', function () { editor(null); });
    results.addEventListener('click', async function (event) {
      var edit = event.target.closest('[data-edit]');
      if (edit) { editor(items.find(function (item) { return item.code === edit.dataset.edit; })); return; }
      var remove = event.target.closest('[data-delete]'); if (!remove || remove.disabled || editing) return;
      var item = items.find(function (i) { return i.code === remove.dataset.delete; });
      var confirmed = await A.decide({ title: 'Xoá ' + item.name + '?', noReason: true, danger: true, confirmText: 'Xoá khung',
        message: 'Khung chưa có ai mua nên sẽ bị xoá hẳn khỏi cửa hàng. Mã khung không dùng lại được.' });
      if (!confirmed) return;
      remove.disabled = true;
      try { await A.api.delete(API + '/' + encodeURIComponent(item.code)); await reload(); A.toast('Đã xoá khung.', 'success'); }
      catch (error) { A.toast(error.message, 'error'); remove.disabled = false; }
    });
    paint();
  } });
})();
