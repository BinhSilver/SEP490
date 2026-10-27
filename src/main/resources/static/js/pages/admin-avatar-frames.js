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
    host.innerHTML = '<div class="skin-admin frame-admin"><div class="skin-admin__toolbar">'
      + '<input type="search" class="input" aria-label="Tìm khung" placeholder="Tìm tên hoặc mã khung…">'
      + '<select class="input" aria-label="Lọc trạng thái"><option value="">Đang quản lý</option><option value="active">Đang bán</option><option value="hidden">Đã ẩn</option><option value="deleted">Đã xoá</option></select>'
      + '<button type="button" class="btn btn--primary" data-create><i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm khung</button></div>'
      + '<div data-editor></div><p class="adm-muted" data-count aria-live="polite"></p><div class="skin-admin__results" data-results></div></div>';
    var root = host.querySelector('.frame-admin'), search = root.querySelector('input[type=search]');
    var filter = root.querySelector('.skin-admin__toolbar select'), results = root.querySelector('[data-results]');

    function paint() {
      var q = search.value.trim().toLocaleLowerCase('vi'), status = filter.value;
      var shown = items.filter(function (item) {
        return (item.name + ' ' + item.code).toLocaleLowerCase('vi').includes(q)
          && (status === 'deleted' ? item.deleted : !item.deleted && (!status || status === state(item)));
      });
      root.querySelector('[data-count]').textContent = shown.length + ' khung';
      if (!shown.length) { results.innerHTML = A.empty('fa-crown', 'Không có khung phù hợp'); return; }
      results.innerHTML = '<table class="skin-admin__table"><thead><tr><th>Khung</th><th>Chủ đề · màu tên</th><th>Giá</th><th>Đã mua</th><th>Trạng thái</th><th><span class="sr-only">Thao tác</span></th></tr></thead><tbody>'
        + shown.map(function (item) {
          var canDelete = !item.deleted && item.owners === 0;
          return '<tr><td><div class="skin-admin__identity">' + preview(item, 44) + '<div><strong>' + A.esc(item.name) + '</strong><small>' + A.esc(item.code) + '</small></div></div></td>'
            + '<td>' + A.esc(item.nameStyleLabel) + '<small class="frame-admin__muted">' + A.esc(item.theme) + (item.gems ? '' : ' · không có đá chớp sáng') + '</small></td>'
            + '<td>' + item.price.toLocaleString('vi-VN') + ' CP</td>'
            + '<td>' + item.owners.toLocaleString('vi-VN') + '</td>'
            + '<td><span class="skin-admin__status" data-state="' + state(item) + '">' + { active: 'Đang bán', hidden: 'Đã ẩn', deleted: 'Đã xoá' }[state(item)] + '</span></td>'
            + '<td><div class="skin-admin__actions">' + (item.deleted ? '' : '<button type="button" class="btn btn--secondary" data-edit="' + A.esc(item.code) + '" aria-label="Sửa ' + A.esc(item.name) + '">Sửa</button>'
              + '<button type="button" class="btn btn--secondary" data-delete="' + A.esc(item.code) + '"' + (canDelete ? '' : ' disabled title="Đã có người mua: chỉ ẩn được, không xoá"')
              + ' aria-label="Xoá ' + A.esc(item.name) + '">Xoá</button>') + '</div></td></tr>';
        }).join('') + '</tbody></table>';
    }

    async function reload() { items = await A.api.get(API); paint(); }
    search.addEventListener('input', paint);
    filter.addEventListener('change', paint);

    function editor(item) {
      if (editing) return;
      var current = item || { code: 'frame-', name: '', description: '', price: 300, image: '', theme: 'cosmic', sortOrder: items.length + 1, active: true, owners: 0, gems: null };
      var mount = root.querySelector('[data-editor]');
      mount.innerHTML = '<form class="skin-editor" aria-labelledby="frame-editor-title"><header><h2 id="frame-editor-title">' + (item ? 'Sửa khung avatar' : 'Thêm khung avatar') + '</h2>'
        + '<button type="button" class="btn btn--secondary" data-cancel>Đóng</button></header>'
        + '<div class="skin-editor__grid">'
        + '<label>Mã khung<input class="input" name="code" required maxlength="40" pattern="frame-[a-z0-9][a-z0-9-]{1,33}" placeholder="frame-ten-khung" value="' + A.esc(current.code) + '" ' + (item ? 'readonly' : '') + '></label>'
        + '<label>Tên khung<input class="input" name="name" required maxlength="100" value="' + A.esc(current.name) + '"></label>'
        + '<label>Giá CP<input class="input" name="price" type="number" min="0" max="1000000" step="1" required value="' + current.price + '"></label>'
        + '<label>Thứ tự hiển thị<input class="input" name="sortOrder" type="number" min="0" max="9999" step="1" required value="' + current.sortOrder + '"></label>'
        + '<label>Chủ đề hiệu ứng<select class="input" name="theme">' + Object.keys(themes).map(function (code) {
            return '<option value="' + A.esc(code) + '">' + A.esc(themes[code]) + ' (' + A.esc(code) + ')</option>'; }).join('') + '</select>'
        + '<small class="frame-admin__muted">Quyết định màu ánh sáng, hào quang, kim tuyến và màu tên đi kèm.</small></label>'
        + '<label>Ảnh khung<input class="input" name="image" required maxlength="1024" value="' + A.esc(current.image) + '" placeholder="/uploads/avatar-frames/…"></label>'
        + '<label class="skin-editor__description">Mô tả<textarea class="textarea" name="description" maxlength="500" rows="2">' + A.esc(current.description) + '</textarea></label></div>'
        + '<div class="skin-editor__art"><span data-preview-slot></span><label class="btn btn--secondary">Tải ảnh PNG<input type="file" accept="image/png" data-upload></label>'
        + '<span class="adm-muted">PNG nền trong suốt, hình vuông, lỗ tròn ở giữa · tối đa 2048 px, 5 MB</span></div>'
        + '<p class="adm-muted" data-gems-note></p>'
        + '<label class="skin-editor__active"><input type="checkbox" name="active" ' + (current.active ? 'checked' : '') + '> Đang bán trong cửa hàng'
        + (current.owners ? ' <span class="frame-admin__muted">(bỏ chọn để ẩn; ' + current.owners + ' người đã mua vẫn đeo được)</span>' : '') + '</label>'
        + '<p class="adm-error" data-error role="alert"></p><footer><button type="button" class="btn btn--secondary" data-cancel>Huỷ</button><button class="btn btn--primary" type="submit">Lưu khung</button></footer></form>';
      var form = mount.querySelector('form'), fields = form.elements, image = fields.namedItem('image');
      var slot = form.querySelector('[data-preview-slot]'), note = form.querySelector('[data-gems-note]');
      fields.namedItem('theme').value = current.theme;

      function refresh() {
        var sameImage = item && image.value.trim() === item.image;
        var gems = sameImage ? item.gems : null;
        slot.innerHTML = preview({ code: PREVIEW, image: image.value.trim(), theme: fields.namedItem('theme').value, gems: gems }, 96);
        note.textContent = gems ? 'Khung gốc có đá chớp sáng đo trên ảnh này.'
          : 'Khung này không có hiệu ứng đá chớp sáng (chỉ có ở ảnh gốc); các hiệu ứng khác vẫn chạy đủ.';
      }
      refresh(); A.digitsOnly(fields.namedItem('price')); A.digitsOnly(fields.namedItem('sortOrder'));
      form.querySelectorAll('[data-cancel]').forEach(function (button) { button.addEventListener('click', function () { if (!editing) mount.innerHTML = ''; }); });
      image.addEventListener('input', refresh);
      fields.namedItem('theme').addEventListener('change', refresh);
      form.querySelector('[data-upload]').addEventListener('change', async function (event) {
        var file = event.target.files[0]; if (!file) return;
        var error = form.querySelector('[data-error]'), save = form.querySelector('[type=submit]');
        editing = true; save.disabled = true; error.textContent = 'Đang tải ảnh…';
        try {
          var data = new FormData(); data.append('file', file);
          var headers = window.JlasAuth.authHeaders(); delete headers['Content-Type'];
          var response = await fetch(API + '/image', { method: 'POST', headers: headers, body: data });
          var result = await response.json(); if (!response.ok) throw new Error(result.message || 'Không tải được ảnh');
          image.value = result.data.url; refresh(); error.textContent = '';
        } catch (errorValue) { error.textContent = errorValue.message; }
        finally { editing = false; save.disabled = false; event.target.value = ''; }
      });
      form.addEventListener('submit', async function (event) {
        event.preventDefault(); if (editing || !form.reportValidity()) return;
        editing = true; var save = form.querySelector('[type=submit]'); save.disabled = true; save.textContent = 'Đang lưu…';
        var body = { code: fields.namedItem('code').value.trim(), name: fields.namedItem('name').value.trim(),
          description: fields.namedItem('description').value.trim(), price: Number(fields.namedItem('price').value),
          image: image.value.trim(), theme: fields.namedItem('theme').value, sortOrder: Number(fields.namedItem('sortOrder').value),
          active: fields.namedItem('active').checked };
        try {
          await (item ? A.api.put(API + '/' + encodeURIComponent(item.code), body) : A.api.post(API, body));
          await reload(); mount.innerHTML = ''; A.toast('Đã lưu khung avatar.', 'success');
        } catch (error) { form.querySelector('[data-error]').textContent = error.message; }
        finally { editing = false; save.disabled = false; save.textContent = 'Lưu khung'; }
      });
      form.querySelector(item ? '[name=name]' : '[name=code]').focus();
      mount.scrollIntoView({ block: 'nearest' });
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
