window.AdminShell.ready.then(function (allowed) {
  'use strict';
  if (!allowed) return;
  var $ = function (id) { return document.getElementById(id); };
  var state = { verbs: [], contexts: [], forms: [], dirty: false, tab: 'verbs', verbPage: 0, contextPage: 0 };
  var pageSizes = { verbs: 10, contexts: 10 };

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function headers(json) {
    var result = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    if (json) result['Content-Type'] = 'application/json';
    return result;
  }
  async function api(path, options) {
    var response = await fetch(path, Object.assign({ headers: headers(false) }, options || {}));
    var body = await response.json().catch(function () { return {}; });
    if (!response.ok || body.success === false) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
    return body.data;
  }
  function toast(message, error) {
    var el = $('adminToast'); el.textContent = message; el.classList.toggle('is-error', !!error); el.hidden = false;
    clearTimeout(toast.timer); toast.timer = setTimeout(function () { el.hidden = true; }, 3600);
  }
  function markDirty() {
    state.dirty = true; $('saveButton').disabled = false;
  }
  function groupName(group) { return ({ 1: 'Nhóm 1 · Godan', 2: 'Nhóm 2 · Ichidan', 3: 'Nhóm 3 · Bất quy tắc' })[group] || 'Nhóm ' + group; }
  function formLabel(id) { var found = state.forms.find(function (item) { return item.id === id; }); return found ? found.label : id; }
  function verbName(id) {
    var v = state.verbs.find(function (item) { return item.id === id; });
    return v ? v.word + ' · ' + v.reading : id;
  }
  function filteredVerbs() {
    var query = $('verbSearch').value.trim().toLocaleLowerCase('vi');
    var level = $('verbLevelFilter').value, group = $('verbGroupFilter').value;
    return state.verbs.filter(function (v) {
      var text = [v.id, v.word, v.reading, v.meaning].join(' ').toLocaleLowerCase('vi');
      return (!query || text.includes(query)) && (!level || v.level === level) && (!group || String(v.group) === group);
    }).sort(function (a, b) { return a.level.localeCompare(b.level) || a.word.localeCompare(b.word, 'ja'); });
  }
  function renderVerbs() {
    var rows = filteredVerbs(), pages = Math.max(1, Math.ceil(rows.length / pageSizes.verbs));
    state.verbPage = Math.min(state.verbPage, pages - 1);
    var pageRows = rows.slice(state.verbPage * pageSizes.verbs, (state.verbPage + 1) * pageSizes.verbs);
    $('verbRows').innerHTML = pageRows.map(function (v) {
      return '<tr><td><div class="verb-admin__word"><strong lang="ja">' + escapeHtml(v.word) + '</strong><span><small lang="ja">' + escapeHtml(v.reading) + '</small></span></div></td>' +
        '<td class="verb-admin__meaning">' + escapeHtml(v.meaning) + '</td><td><span class="verb-admin__badge">' + escapeHtml(groupName(v.group)) + '</span></td><td><span class="verb-admin__badge">' + escapeHtml(v.level) + '</span></td>' +
        '<td><div class="verb-admin__row-actions"><button type="button" data-edit-verb="' + escapeHtml(v.id) + '" aria-label="Sửa ' + escapeHtml(v.word) + '">✎</button><button type="button" data-delete-verb="' + escapeHtml(v.id) + '" aria-label="Xóa ' + escapeHtml(v.word) + '">×</button></div></td></tr>';
    }).join('');
    $('verbEmpty').hidden = rows.length > 0;
    $('verbVisibleCount').textContent = rows.length ? 'Đang hiển thị ' + (state.verbPage * pageSizes.verbs + 1) + '–' + Math.min((state.verbPage + 1) * pageSizes.verbs, rows.length) + ' / ' + rows.length : '0 động từ';
    $('verbPage').textContent = (state.verbPage + 1) + ' / ' + pages;
    $('verbPrev').disabled = state.verbPage === 0; $('verbNext').disabled = state.verbPage >= pages - 1;
  }
  function filteredContexts() {
    var query = $('contextSearch').value.trim().toLocaleLowerCase('vi'), level = $('contextLevelFilter').value, form = $('contextFormFilter').value;
    return state.contexts.filter(function (c) {
      var v = state.verbs.find(function (item) { return item.id === c.verbId; });
      var haystack = [c.id, c.sentence, c.translation, v && v.word, v && v.reading, v && v.meaning].join(' ').toLocaleLowerCase('vi');
      return (!query || haystack.includes(query)) && (!level || (v && v.level === level)) && (!form || c.form === form);
    }).sort(function (a, b) { return a.id.localeCompare(b.id); });
  }
  function renderContexts() {
    var rows = filteredContexts(), pages = Math.max(1, Math.ceil(rows.length / pageSizes.contexts));
    state.contextPage = Math.min(state.contextPage, pages - 1);
    var pageRows = rows.slice(state.contextPage * pageSizes.contexts, (state.contextPage + 1) * pageSizes.contexts);
    $('contextRows').innerHTML = pageRows.map(function (c) {
      var v = state.verbs.find(function (item) { return item.id === c.verbId; });
      return '<article class="verb-context-card"><div><div class="verb-context-card__meta"><span class="verb-admin__badge">' + escapeHtml(formLabel(c.form)) + '</span><span class="verb-admin__badge">' + escapeHtml(v ? v.level : '—') + '</span><span class="verb-context-card__id">' + escapeHtml(c.id) + ' · ' + escapeHtml(verbName(c.verbId)) + '</span></div><p class="verb-context-card__sentence" lang="ja">' + escapeHtml(c.sentence) + '</p><p class="verb-context-card__translation">' + escapeHtml(c.translation) + '</p></div><div class="verb-admin__row-actions"><button type="button" data-edit-context="' + escapeHtml(c.id) + '" aria-label="Sửa câu">✎</button><button type="button" data-delete-context="' + escapeHtml(c.id) + '" aria-label="Xóa câu">×</button></div></article>';
    }).join('');
    $('contextEmpty').hidden = rows.length > 0;
    $('contextVisibleCount').textContent = rows.length ? 'Đang hiển thị ' + (state.contextPage * pageSizes.contexts + 1) + '–' + Math.min((state.contextPage + 1) * pageSizes.contexts, rows.length) + ' / ' + rows.length : '0 câu';
    $('contextPage').textContent = (state.contextPage + 1) + ' / ' + pages;
    $('contextPrev').disabled = state.contextPage === 0; $('contextNext').disabled = state.contextPage >= pages - 1;
  }
  function render() {
    $('verbTabCount').textContent = state.verbs.length;
    $('contextTabCount').textContent = state.contexts.length;
    renderVerbs(); renderContexts();
  }
  async function load() {
    $('saveButton').disabled = true; $('reloadButton').disabled = true;
    try {
      var results = await Promise.all([api('/api/admin/verb-practice/catalog'), api('/api/admin/verb-practice/forms')]);
      state.verbs = results[0].content.verbs || []; state.contexts = results[0].content.contexts || []; state.forms = results[1] || [];
      state.dirty = false;
      var formOptions = '<option value="">Tất cả thể</option>' + state.forms.map(function (f) { return '<option value="' + escapeHtml(f.id) + '">' + escapeHtml(f.label) + '</option>'; }).join('');
      $('contextFormFilter').innerHTML = formOptions;
      var formSelectOptions = state.forms.map(function (f) { return '<option value="' + escapeHtml(f.id) + '">' + escapeHtml(f.label) + '</option>'; }).join('');
      $('contextForm').elements.form.innerHTML = formSelectOptions;
      populateVerbChoices(); render();
    } catch (error) { toast(error.message, true); }
    finally { $('reloadButton').disabled = false; }
  }
  function populateVerbChoices(selected) {
    var sorted = state.verbs.slice().sort(function (a, b) { return a.word.localeCompare(b.word, 'ja'); });
    $('contextForm').elements.verbId.innerHTML = sorted.map(function (v) { return '<option value="' + escapeHtml(v.id) + '">' + escapeHtml(v.word + ' · ' + v.reading + ' · ' + v.level) + '</option>'; }).join('');
    if (selected) $('contextForm').elements.verbId.value = selected;
  }
  async function save() {
    $('saveButton').disabled = true;
    try {
      var saved = await api('/api/admin/verb-practice/catalog', { method: 'PUT', headers: headers(true), body: JSON.stringify({ verbs: state.verbs, contexts: state.contexts }) });
      state.verbs = saved.content.verbs; state.contexts = saved.content.contexts; state.dirty = false;
      render(); toast('Đã lưu kho nội dung. Nội dung mới được áp dụng ngay.');
    } catch (error) { $('saveButton').disabled = false; toast(error.message, true); }
  }
  function openVerb(verb) {
    var form = $('verbForm'), fields = form.elements;
    form.reset(); $('verbDialogTitle').textContent = verb ? 'Sửa động từ' : 'Thêm động từ';
    fields.id.disabled = !!verb;
    if (verb) Object.keys(verb).forEach(function (key) { if (fields[key]) fields[key].value = verb[key]; });
    $('verbDialog').showModal();
  }
  function openContext(context) {
    var form = $('contextForm'), fields = form.elements;
    form.reset(); $('contextDialogTitle').textContent = context ? 'Sửa câu ngữ cảnh' : 'Thêm câu ngữ cảnh';
    fields.id.disabled = !!context; $('deleteContext').hidden = !context;
    populateVerbChoices(context && context.verbId);
    if (context) Object.keys(context).forEach(function (key) { if (fields[key]) fields[key].value = context[key]; });
    $('contextDialog').dataset.editId = context ? context.id : '';
    $('contextDialog').showModal();
  }
  function askDelete(title, text) {
    $('confirmTitle').textContent = title; $('confirmText').textContent = text;
    var dialog = $('confirmDialog'); dialog.showModal();
    return new Promise(function (resolve) {
      dialog.addEventListener('close', function done() { dialog.removeEventListener('close', done); resolve(dialog.returnValue === 'confirm'); });
    });
  }
  $('saveButton').addEventListener('click', save);
  $('reloadButton').addEventListener('click', async function () { if (!state.dirty || await askDelete('Tải lại nội dung?', 'Các thay đổi chưa lưu sẽ bị bỏ.')) load(); });
  $('addVerb').addEventListener('click', function () { openVerb(null); });
  $('addContext').addEventListener('click', function () { openContext(null); });
  $('verbForm').addEventListener('submit', function (event) {
    event.preventDefault(); var f = event.currentTarget.elements;
    var item = { id: f.id.value.trim(), word: f.word.value.trim(), reading: f.reading.value.trim(), meaning: f.meaning.value.trim(), group: Number(f.group.value), level: f.level.value };
    if (!f.id.disabled && state.verbs.some(function (v) { return v.id === item.id; })) { toast('Mã động từ này đã tồn tại.', true); return; }
    if (f.id.disabled) state.verbs = state.verbs.map(function (v) { return v.id === item.id ? item : v; }); else state.verbs.push(item);
    $('verbDialog').close(); populateVerbChoices(); markDirty(); render();
  });
  $('contextForm').addEventListener('submit', function (event) {
    event.preventDefault(); var f = event.currentTarget.elements;
    var item = { id: f.id.value.trim(), verbId: f.verbId.value, form: f.form.value, sentence: f.sentence.value.trim(), translation: f.translation.value.trim() };
    var editing = !!f.id.disabled;
    if (!editing && state.contexts.some(function (c) { return c.id === item.id; })) { toast('Mã câu này đã tồn tại.', true); return; }
    if ((item.sentence.match(/___/g) || []).length !== 1) { toast('Câu tiếng Nhật cần có đúng một chỗ trống ___.', true); return; }
    if (editing) state.contexts = state.contexts.map(function (c) { return c.id === item.id ? item : c; }); else state.contexts.push(item);
    $('contextDialog').close(); markDirty(); render();
  });
  document.addEventListener('click', async function (event) {
    var target = event.target.closest('button'); if (!target) return;
    if (target.dataset.close !== undefined) target.closest('dialog').close();
    if (target.dataset.editVerb) openVerb(state.verbs.find(function (v) { return v.id === target.dataset.editVerb; }));
    if (target.dataset.deleteVerb) {
      var verb = state.verbs.find(function (v) { return v.id === target.dataset.deleteVerb; }); if (!verb) return;
      var linked = state.contexts.filter(function (c) { return c.verbId === verb.id; }).length;
      if (await askDelete('Xóa ' + verb.word + '?', 'Động từ này đang có ' + linked + ' câu ngữ cảnh. Các câu đó cũng sẽ bị xóa khỏi kho nội dung. Tiến độ cũ của người học vẫn được giữ trong lịch sử.')) {
        state.verbs = state.verbs.filter(function (v) { return v.id !== verb.id; }); state.contexts = state.contexts.filter(function (c) { return c.verbId !== verb.id; });
        markDirty(); render(); populateVerbChoices();
      }
    }
    if (target.dataset.editContext) openContext(state.contexts.find(function (c) { return c.id === target.dataset.editContext; }));
    if (target.dataset.deleteContext) {
      var ctx = state.contexts.find(function (c) { return c.id === target.dataset.deleteContext; });
      if (ctx && await askDelete('Xóa câu ngữ cảnh?', 'Câu này sẽ không còn xuất hiện trong bài luyện theo ngữ cảnh.')) {
        state.contexts = state.contexts.filter(function (c) { return c.id !== ctx.id; }); markDirty(); render();
      }
    }
    if (target.id === 'deleteContext') {
      var ctxToDelete = state.contexts.find(function (c) { return c.id === $('contextDialog').dataset.editId; });
      if (ctxToDelete && await askDelete('Xóa câu ngữ cảnh?', 'Câu này sẽ không còn xuất hiện trong bài luyện theo ngữ cảnh.')) {
        state.contexts = state.contexts.filter(function (c) { return c.id !== ctxToDelete.id; }); $('contextDialog').close(); markDirty(); render();
      }
    }
    if (target.dataset.tab) {
      state.tab = target.dataset.tab; document.querySelectorAll('.verb-admin__tabs button').forEach(function (button) { button.classList.toggle('is-active', button === target); });
      $('verbsPanel').hidden = state.tab !== 'verbs'; $('contextsPanel').hidden = state.tab !== 'contexts';
    }
    if (target.id === 'verbPrev' && state.verbPage > 0) { state.verbPage--; renderVerbs(); }
    if (target.id === 'verbNext') { state.verbPage++; renderVerbs(); }
    if (target.id === 'contextPrev' && state.contextPage > 0) { state.contextPage--; renderContexts(); }
    if (target.id === 'contextNext') { state.contextPage++; renderContexts(); }
  });
  ['verbSearch', 'verbLevelFilter', 'verbGroupFilter'].forEach(function (id) { $(id).addEventListener('input', function () { state.verbPage = 0; renderVerbs(); }); });
  ['contextSearch', 'contextLevelFilter', 'contextFormFilter'].forEach(function (id) { $(id).addEventListener('input', function () { state.contextPage = 0; renderContexts(); }); });
  window.addEventListener('beforeunload', function (event) { if (state.dirty) { event.preventDefault(); event.returnValue = ''; } });
  load();
});
