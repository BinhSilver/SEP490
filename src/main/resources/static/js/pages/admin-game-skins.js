(function () {
  'use strict';
  var A = window.Admin, API = '/api/admin/game-skins';
  var names = { NEKO_CATCH: 'Bắt cá', AIR_DEFENSE: 'Bắn máy bay' };
  A.section('game-skins', { render: async function (host) {
    var loaded = await Promise.all([A.api.get(API), A.api.get(API + '/renderers')]);
    var items = loaded[0], renderers = loaded[1], editing = false;
    host.innerHTML = '<div class="skin-admin"><div class="skin-admin__toolbar">'
      + '<input type="search" class="input" aria-label="Tìm trang phục" placeholder="Tìm tên hoặc mã skin…">'
      + '<select class="input" aria-label="Lọc game"><option value="">Tất cả game</option><option value="NEKO_CATCH">Bắt cá</option><option value="AIR_DEFENSE">Bắn máy bay</option></select>'
      + '<select class="input" aria-label="Lọc trạng thái"><option value="">Đang quản lý</option><option value="active">Đang bán</option><option value="hidden">Đã ẩn</option><option value="deleted">Đã xóa</option></select>'
      + '<button type="button" class="btn btn--primary" data-create><i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm skin</button></div>'
      + '<div data-editor></div><p class="adm-muted" data-count aria-live="polite"></p><div class="skin-admin__results" data-results></div></div>';
    var root = host.querySelector('.skin-admin'), search = root.querySelector('input[type=search]');
    var selects = root.querySelectorAll('.skin-admin__toolbar select'), results = root.querySelector('[data-results]');
    function paint() {
      var q = search.value.trim().toLocaleLowerCase('vi'), game = selects[0].value, status = selects[1].value;
      var shown = items.filter(function (item) {
        return (!game || item.gameType === game) && (item.name + ' ' + item.code).toLocaleLowerCase('vi').includes(q)
          && (status === 'deleted' ? item.deleted : !item.deleted && (!status || (status === 'active' ? item.active : !item.active)));
      });
      root.querySelector('[data-count]').textContent = shown.length + ' trang phục';
      if (!shown.length) { results.innerHTML = A.empty('fa-shirt', 'Không có trang phục phù hợp'); return; }
      results.innerHTML = '<table class="skin-admin__table"><thead><tr><th>Trang phục</th><th>Game</th><th>Giá</th><th>Trạng thái</th><th><span class="sr-only">Thao tác</span></th></tr></thead><tbody>' + shown.map(function (item) {
        return '<tr><td><div class="skin-admin__identity"><img src="' + A.esc(item.image) + '" alt="" width="56" height="56"><div><strong>' + A.esc(item.name) + '</strong><small>' + A.esc(item.code) + '</small></div></div></td>'
          + '<td>' + names[item.gameType] + '</td><td>' + item.price.toLocaleString('vi-VN') + ' CP</td>'
          + '<td><span class="skin-admin__status" data-state="' + (item.deleted ? 'deleted' : item.active ? 'active' : 'hidden') + '">' + (item.deleted ? 'Đã xóa' : item.starter ? 'Mặc định' : item.active ? 'Đang bán' : 'Đã ẩn') + '</span></td>'
          + '<td><div class="skin-admin__actions">' + (item.deleted ? '' : '<button type="button" class="btn btn--secondary" data-edit="' + A.esc(item.code) + '" aria-label="Sửa ' + A.esc(item.name) + '">Sửa</button>'
            + (item.starter ? '' : '<button type="button" class="btn btn--secondary" data-delete="' + A.esc(item.code) + '" aria-label="Xóa ' + A.esc(item.name) + '">Xóa</button>')) + '</div></td></tr>';
      }).join('') + '</tbody></table>';
    }
    async function reload() { items = await A.api.get(API); paint(); }
    search.addEventListener('input', paint);
    selects.forEach(function (select) { select.addEventListener('change', paint); });
    function editor(item) {
      if (editing) return;
      var current = item || { code: '', gameType: 'NEKO_CATCH', name: '', description: '', price: 200, image: '', renderCode: 'CUSTOM', active: true };
      var mount = root.querySelector('[data-editor]');
      mount.innerHTML = '<form class="skin-editor" aria-labelledby="skin-editor-title"><header><h2 id="skin-editor-title">' + (item ? 'Sửa trang phục' : 'Thêm trang phục') + '</h2>'
        + '<button type="button" class="btn btn--secondary" data-cancel>Đóng</button></header>'
        + '<div class="skin-editor__grid"><label>Mã skin<input class="input" name="code" required maxlength="40" pattern="[a-z0-9][a-z0-9-]{1,39}" placeholder="cat-ten-skin" value="' + A.esc(current.code) + '" ' + (item ? 'readonly' : '') + '></label>'
        + '<label>Game<select class="input" name="gameType" ' + (item ? 'disabled' : '') + '><option value="NEKO_CATCH">Bắt cá</option><option value="AIR_DEFENSE">Bắn máy bay</option></select></label>'
        + '<label>Tên trang phục<input class="input" name="name" required maxlength="100" value="' + A.esc(current.name) + '"></label>'
        + '<label>Giá CP<input class="input" name="price" type="number" min="0" max="1000000" step="1" required value="' + current.price + '" ' + (current.starter ? 'readonly' : '') + '></label>'
        + '<label>Hình trong game<select class="input" name="renderCode"></select></label>'
        + '<label>Ảnh trang phục<input class="input" name="image" required maxlength="1024" value="' + A.esc(current.image) + '" placeholder="/uploads/game-skins/…"></label>'
        + '<label class="skin-editor__description">Mô tả<textarea class="textarea" name="description" maxlength="500" rows="2">' + A.esc(current.description) + '</textarea></label></div>'
        + '<div class="skin-editor__art"><img data-preview alt="Xem trước trang phục" hidden><label class="btn btn--secondary">Tải ảnh PNG/JPG<input type="file" accept="image/png,image/jpeg" data-upload></label><span class="adm-muted">Tối đa 5 MB · 2048 × 2048</span></div>'
        + '<label class="skin-editor__active"><input type="checkbox" name="active" ' + (current.active ? 'checked' : '') + ' ' + (current.starter ? 'disabled' : '') + '> Hiển thị trong cửa hàng</label>'
        + '<p class="adm-error" data-error role="alert"></p><footer><button type="button" class="btn btn--secondary" data-cancel>Hủy</button><button class="btn btn--primary" type="submit">Lưu trang phục</button></footer></form>';
      var form = mount.querySelector('form'), fields = form.elements, image = fields.namedItem('image'), preview = form.querySelector('[data-preview]');
      fields.namedItem('gameType').value = current.gameType;
      function previewImage() { preview.hidden = !image.value; if (image.value.startsWith('/arena/') || image.value.startsWith('/uploads/game-skins/')) preview.src = image.value; }
      function rendererOptions(value) {
        var game = fields.namedItem('gameType').value;
        fields.namedItem('renderCode').innerHTML = '<option value="CUSTOM">Dùng ảnh đã tải lên</option>' + renderers.filter(function (r) { return r.gameType === game; }).map(function (r) { return '<option value="' + A.esc(r.code) + '">' + A.esc(r.name) + ' · chuyển động có sẵn</option>'; }).join('');
        fields.namedItem('renderCode').value = value || 'CUSTOM';
      }
      rendererOptions(current.renderCode); previewImage(); A.digitsOnly(fields.namedItem('price'));
      form.querySelectorAll('[data-cancel]').forEach(function (button) { button.addEventListener('click', function () { if (!editing) mount.innerHTML = ''; }); });
      image.addEventListener('input', previewImage);
      fields.namedItem('gameType').addEventListener('change', function () { rendererOptions(); });
      fields.namedItem('renderCode').addEventListener('change', function () {
        var renderer = renderers.find(function (r) { return r.code === fields.namedItem('renderCode').value; });
        if (renderer) { image.value = renderer.image; previewImage(); }
      });
      form.querySelector('[data-upload]').addEventListener('change', async function (event) {
        var file = event.target.files[0]; if (!file) return;
        var error = form.querySelector('[data-error]'), save = form.querySelector('[type=submit]');
        editing = true; save.disabled = true; error.textContent = 'Đang tải ảnh…';
        try {
          var data = new FormData(); data.append('file', file);
          var headers = window.JlasAuth.authHeaders(); delete headers['Content-Type'];
          var response = await fetch(API + '/image', { method: 'POST', headers: headers, body: data });
          var result = await response.json(); if (!response.ok) throw new Error(result.message || 'Không tải được ảnh');
          image.value = result.data.url; fields.namedItem('renderCode').value = 'CUSTOM'; previewImage(); error.textContent = '';
        } catch (errorValue) { error.textContent = errorValue.message; }
        finally { editing = false; save.disabled = false; event.target.value = ''; }
      });
      form.addEventListener('submit', async function (event) {
        event.preventDefault(); if (editing || !form.reportValidity()) return;
        editing = true; var save = form.querySelector('[type=submit]'); save.disabled = true; save.textContent = 'Đang lưu…';
        var body = { code: fields.namedItem('code').value.trim(), gameType: fields.namedItem('gameType').value, name: fields.namedItem('name').value.trim(), price: Number(fields.namedItem('price').value), image: image.value.trim(), description: fields.namedItem('description').value.trim(), renderCode: fields.namedItem('renderCode').value, active: fields.namedItem('active').checked };
        try {
          await (item ? A.api.put(API + '/' + encodeURIComponent(item.code), body) : A.api.post(API, body));
          await reload(); mount.innerHTML = ''; A.toast('Đã lưu trang phục.', 'success');
        } catch (error) { form.querySelector('[data-error]').textContent = error.message; }
        finally { editing = false; save.disabled = false; save.textContent = 'Lưu trang phục'; }
      });
      form.querySelector(item ? '[name=name]' : '[name=code]').focus();
      mount.scrollIntoView({ block: 'nearest' });
    }
    root.querySelector('[data-create]').addEventListener('click', function () { editor(null); });
    results.addEventListener('click', async function (event) {
      var edit = event.target.closest('[data-edit]');
      if (edit) { editor(items.find(function (item) { return item.code === edit.dataset.edit; })); return; }
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
