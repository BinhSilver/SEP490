window.AdminShell.ready.then(function (allowed) {
  'use strict';
  if (!allowed) return;
  var $ = function (id) { return document.getElementById('kma-' + id); };
  var model = window.KanjiMapModel;
  var state = { graph: null, nodes: [], page: 0, entry: null, baseline: '', dirty: false, busy: false, loading: false };
  var form = $('form'), fields = form.elements, size = 15;
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return { '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]; }); }
  function message(id, text, error) { var el = $(id); el.textContent = text || ''; el.hidden = !text; el.classList.toggle('is-error', !!error); }
  async function api(path, options) {
    var headers = window.JlasAuth ? window.JlasAuth.authHeaders() : {};
    if (options && options.body) headers['Content-Type'] = 'application/json';
    var response = await fetch('/api/admin/kanji-map' + path, Object.assign({ headers: headers }, options || {}));
    var payload = await response.json().catch(function () { return {}; });
    if (!response.ok || payload.success === false) throw new Error(response.status === 403 ? 'Bạn cần quyền Content Creator để biên tập lược đồ.' : payload.message || 'Không thể kết nối máy chủ. Vui lòng thử lại.');
    return payload.data;
  }
  function kind(n) { return n.isRadicalVariant ? 'Dạng bộ thủ' : n.isRadical ? (n.isKanji ? 'Kanji · Bộ thủ' : 'Bộ thủ') : n.isKanji ? 'Kanji' : 'Thành phần'; }
  function filtered() {
    var query = model.normal($('search').value), level = $('level').value, type = $('type').value, status = $('status').value;
    return state.nodes.filter(function (n) {
      var haystack = model.normal([n.character, n.hanViet, n.meaningVi, n.radicalNameVi].concat(n.radicalAliases || [], n.on || [], n.kun || []).join(' '));
      return (!query || haystack.includes(query)) && (!level || (level === 'UNCLASSIFIED' ? !n.level : n.level === level)) &&
        (!type || (type === 'kanji' ? n.isKanji : type === 'radical' ? n.isRadical || n.isRadicalVariant : !n.isKanji && !n.isRadical && !n.isRadicalVariant)) &&
        (!status || (status === 'edited' ? n.contentVersion > 0 : !n.contentVersion));
    });
  }
  function render() {
    var rows = filtered(), pages = Math.max(1, Math.ceil(rows.length / size)); state.page = Math.min(state.page, pages - 1);
    $('rows').innerHTML = rows.slice(state.page * size, (state.page + 1) * size).map(function (n) {
      return '<tr><td><span class="kma-table-glyph' + (n.character.startsWith('CDP-') ? ' is-code' : '') + '" lang="ja">' + esc(n.character) + '</span></td><td><span class="kma-table-name">' + esc(model.displayName(n)) + '</span><small>' + esc(kind(n)) + '</small></td><td class="kma-table-meaning">' + esc(n.meaningVi || '—') + '</td><td><span class="kma-level">' + esc(n.level || 'Chưa xếp') + '</span></td><td>' + (n.contentVersion ? '<span class="kma-edited">Đã biên tập</span><br><small>Phiên bản ' + n.contentVersion + '</small>' : '<small>Theo nguồn</small>') + '</td><td><button class="kma-button" type="button" data-edit="' + esc(n.character) + '" aria-label="Biên tập ' + esc(n.character) + '">Biên tập</button></td></tr>';
    }).join('');
    $('empty').hidden = !!rows.length; $('match-count').textContent = rows.length.toLocaleString('vi-VN') + ' kết quả';
    $('range').textContent = rows.length ? 'Hiển thị ' + (state.page * size + 1) + '–' + Math.min((state.page + 1) * size, rows.length) + ' / ' + rows.length : '0 kết quả';
    $('page').textContent = (state.page + 1) + ' / ' + pages;
    $('prev').disabled = !state.page; $('next').disabled = state.page + 1 >= pages;
    $('total').textContent = state.nodes.length.toLocaleString('vi-VN');
    $('kanji').textContent = state.graph.counts.kanji.toLocaleString('vi-VN');
    $('edited').textContent = state.nodes.filter(function (n) { return n.contentVersion > 0; }).length.toLocaleString('vi-VN');
  }
  async function load() {
    if (state.loading) return;
    state.loading = true; $('reload').disabled = true; $('loading').hidden = false; message('notice', '');
    try {
      state.graph = await api('/catalog');
      var levels = {N5:0,N4:1,N3:2,N2:3,N1:4};
      state.nodes = state.graph.nodes.slice().sort(function (a, b) {
        return Number(b.isKanji) - Number(a.isKanji) || (levels[a.level] == null ? 5 : levels[a.level]) - (levels[b.level] == null ? 5 : levels[b.level]) || (a.strokeCount || 0) - (b.strokeCount || 0) || a.character.localeCompare(b.character, 'ja');
      });
      render();
    }
    catch (error) { message('notice', error.message, true); }
    finally { state.loading = false; $('reload').disabled = false; $('loading').hidden = true; }
  }
  function lines(value) { return String(value || '').split('\n').map(function (v) { return v.trim(); }).filter(Boolean); }
  function values() {
    return { version: state.entry.version, hanViet: fields.hanViet.value.trim(), meaningVi: fields.meaningVi.value.trim(), level: fields.level.value || null,
      on: lines(fields.on.value), kun: lines(fields.kun.value), memoryHint: fields.memoryHint.value.trim(),
      radicalNameVi: fields.radicalNameVi.value.trim(), radicalAliases: lines(fields.radicalAliases.value) };
  }
  function applyRestrictions() {
    fields.level.disabled = !state.entry.node.isKanji || state.busy;
    fields.meaningVi.required = !!state.entry.node.isKanji;
    var radical = state.entry.node.isRadical || state.entry.node.isRadicalVariant;
    $('radical-fields').hidden = !radical;
    fields.radicalNameVi.required = !!radical;
    $('save').disabled = state.busy || !state.dirty;
    $('reset').disabled = state.busy || (!state.entry.version && !state.dirty);
  }
  function changed() {
    state.dirty = JSON.stringify(values()) !== state.baseline;
    $('dirty').textContent = state.dirty ? 'Có thay đổi chưa lưu' : 'Chưa có thay đổi';
    applyRestrictions(); preview();
  }
  function preview() {
    var value = values(), n = state.entry.node;
    $('preview-glyph').textContent = n.character; $('preview-glyph').classList.toggle('is-code', n.character.startsWith('CDP-'));
    $('preview-name').textContent = value.radicalNameVi || value.hanViet || kind(n);
    $('preview-meaning').textContent = value.meaningVi || 'Chưa có nghĩa';
    $('preview-level').textContent = value.level || 'Chưa xếp cấp';
    $('preview-readings').textContent = (value.on.length ? 'On: ' + value.on.join(' · ') + '\n' : '') + (value.kun.length ? 'Kun: ' + value.kun.join(' · ') : '');
    $('preview-hint').textContent = value.memoryHint;
  }
  function fill(entry) {
    state.entry = entry;
    var n = entry.node;
    ['hanViet', 'meaningVi', 'level', 'memoryHint', 'radicalNameVi'].forEach(function (key) { fields[key].value = n[key] || ''; });
    ['on', 'kun', 'radicalAliases'].forEach(function (key) { fields[key].value = (n[key] || []).join('\n'); });
    $('editor-title').textContent = n.character + ' · ' + kind(n);
    $('version').textContent = entry.version ? 'Phiên bản ' + entry.version + ' · Cập nhật ' + new Date(entry.updatedAt).toLocaleString('vi-VN') + ' · Người biên tập #' + entry.updatedBy : 'Nội dung gốc · Chưa có bản biên tập';
    $('components').innerHTML = n.components.length ? n.components.map(function (p) { return '<span lang="ja">' + esc(p.character) + (p.partial ? '<small>một phần</small>' : '') + '</span>'; }).join('') : '<p>Chữ chưa được tách thành phần nhỏ hơn.</p>';
    $('preview-link').href = '/dictionary/kanji-map?kanji=' + encodeURIComponent(n.character);
    var source = entry.source;
    $('source-content').innerHTML = '<p><b>Hán Việt:</b> ' + esc(source.hanViet || '—') + '<br><b>Nghĩa Việt:</b> ' + esc(source.meaningVi || '—') + '<br><b>Cấp độ:</b> ' + esc(source.level || 'Chưa xếp cấp') + '<br><b>Âm On:</b> ' + esc((source.on || []).join(' · ') || '—') + '<br><b>Âm Kun:</b> ' + esc((source.kun || []).join(' · ') || '—') + '<br><b>Tên bộ thủ:</b> ' + esc(source.radicalNameVi || '—') + '<br><b>Tên khác:</b> ' + esc((source.radicalAliases || []).join(' · ') || '—') + '<br><b>Gợi ý:</b> ' + esc(source.memoryHint || '—') + '</p><a href="/assets/kanji-map/SOURCES.md" target="_blank" rel="noopener">Xem nguồn dữ liệu và giấy phép ↗</a>';
    state.baseline = JSON.stringify(values()); message('editor-error', ''); changed();
  }
  async function open(character) {
    if (state.busy) return;
    state.busy = true;
    try { fill(await api('/entries/' + encodeURIComponent(character))); $('editor').showModal(); }
    catch (error) { message('notice', error.message, true); }
    finally { state.busy = false; if (state.entry) applyRestrictions(); }
  }
  function confirmAction(title, text) {
    $('confirm-title').textContent = title; $('confirm-message').textContent = text; $('confirm').returnValue = 'cancel';
    return new Promise(function (resolve) { $('confirm').addEventListener('close', function () { resolve($('confirm').returnValue === 'ok'); }, { once: true }); $('confirm').showModal(); });
  }
  async function close() {
    if (state.busy) return;
    if (!state.dirty || await confirmAction('Bỏ thay đổi chưa lưu?', 'Nội dung bạn vừa nhập chưa được lưu. Đóng cửa sổ sẽ bỏ các thay đổi này.')) $('editor').close();
  }
  function busy(value) {
    state.busy = value;
    form.querySelectorAll('input,select,textarea,button').forEach(function (el) { el.disabled = value; });
    applyRestrictions();
    $('save').textContent = value ? 'Đang lưu…' : 'Lưu & áp dụng';
  }
  function accept(entry) {
    var index = state.nodes.findIndex(function (n) { return n.character === entry.node.character; });
    state.nodes[index] = Object.assign({}, entry.node, {contentVersion:entry.version, contentUpdatedAt:entry.updatedAt});
    fill(entry); render();
  }
  form.addEventListener('input', changed); form.addEventListener('change', changed);
  form.addEventListener('submit', async function (event) {
    event.preventDefault(); if (state.busy || !state.dirty || !form.reportValidity()) return;
    var candidate = values(); busy(true); message('editor-error', '');
    try {
      accept(await api('/entries/' + encodeURIComponent(state.entry.node.character), {method:'PUT', body:JSON.stringify(candidate)}));
      message('notice', 'Đã lưu chữ ' + state.entry.node.character + '. Nội dung mới được áp dụng khi người học tải lược đồ.');
    } catch (error) { message('editor-error', error.message, true); }
    finally { busy(false); }
  });
  $('reset').addEventListener('click', async function () {
    if (state.busy || !await confirmAction('Khôi phục nội dung nguồn?', 'Chữ ' + state.entry.node.character + ' sẽ dùng lại nội dung gốc. Thay đổi được lưu và áp dụng ngay.')) return;
    busy(true); message('editor-error', '');
    try {
      accept(await api('/entries/' + encodeURIComponent(state.entry.node.character) + '/reset', {method:'PUT', body:JSON.stringify({version:state.entry.version})}));
      message('notice', 'Đã khôi phục nội dung nguồn của chữ ' + state.entry.node.character + '.');
    } catch (error) { message('editor-error', error.message, true); }
    finally { busy(false); }
  });
  $('editor').addEventListener('cancel', function (event) { event.preventDefault(); close(); });
  $('close').addEventListener('click', close);
  $('rows').addEventListener('click', function (event) { var button = event.target.closest('[data-edit]'); if (button) open(button.dataset.edit); });
  ['search','level','type','status'].forEach(function (key) { $(key).addEventListener(key === 'search' ? 'input' : 'change', function () { state.page = 0; if (state.graph) render(); }); });
  $('prev').addEventListener('click', function () { if (state.page) { state.page--; render(); } });
  $('next').addEventListener('click', function () { state.page++; render(); });
  $('reload').addEventListener('click', load);
  window.addEventListener('beforeunload', function (event) { if ($('editor').open && state.dirty) { event.preventDefault(); event.returnValue = ''; } });
  load();
});
