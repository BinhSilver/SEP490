/* [JLAS-W0108] "Báo sai lộ trình": content creators review learner reports of the JLPT path, fix the content in place,
 * close the reports and export the fixes for the seed. Runs inside the admin shell (Admin.*, AdminShell.ready). */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.JpathAdminReports = api;
    if (root.document) {
      if (root.document.readyState === 'loading') {
        root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
      } else {
        api.mount();
      }
    }
  }
})(typeof window !== 'undefined' ? window : globalThis, function (global) {
  'use strict';

  var BASE = '/api/admin/learning-path';
  var ERROR_LABELS = { MEANING: 'Nghĩa', EXAMPLE: 'Ví dụ', AUDIO: 'Audio', KANJI: 'Kanji', OTHER: 'Khác' };
  var ERROR_ORDER = ['MEANING', 'EXAMPLE', 'AUDIO', 'KANJI', 'OTHER'];
  var TYPE_LABELS = { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', KANA: 'Kana', EXAMPLE: 'Câu ví dụ',
    QUESTION: 'Câu hỏi ngữ pháp' };
  var STATUS_LABELS = { OPEN: 'Đang mở', RESOLVED: 'Đã xử lý', REJECTED: 'Từ chối' };
  var FIELD_LABELS = {
    meaning_vi: 'Nghĩa', meanings_vi: 'Nghĩa', word_type_label: 'Từ loại', mnemonic_vi: 'Mẹo nhớ', romaji: 'Romaji',
    han_viet: 'Hán Việt', formation: 'Cấu trúc', usage_vi: 'Cách dùng', nuance_vi: 'Sắc thái',
    common_mistakes_vi: 'Lỗi thường gặp', ja: 'Câu tiếng Nhật', ja_furigana: 'Câu có furigana', vi: 'Nghĩa tiếng Việt',
    prompt_ja: 'Đề bài', prompt_furigana: 'Đề bài có furigana', options_json: 'Lựa chọn (JSON, 4 chuỗi)',
    answer_index: 'Đáp án đúng (0–3)', answer_text: 'Đáp án dạng chữ', accept_json: 'Đáp án đồng nghĩa (JSON)',
    explanation_vi: 'Giải thích', status: 'Trạng thái nội dung'
  };
  var CONTENT_STATUSES = ['REVIEWED', 'AI_CHECKED', 'DRAFT', 'HOLD'];
  var LONG_FIELDS = ['usage_vi', 'nuance_vi', 'common_mistakes_vi', 'mnemonic_vi', 'explanation_vi', 'meaning_vi'];
  var HOLD_KEPT = 'Mục đang ngừng hiển thị — trạng thái giữ nguyên; đổi trạng thái bằng chỉnh sửa.';
  var REGEN_AUDIO_NOTE = 'Audio câu này sẽ được tạo lại khi sinh lại seed.';

  function text(value) {
    return value == null ? '' : String(value).trim();
  }

  /** Fields of `edited` whose trimmed value differs from `original` (null = empty); values are trimmed. */
  function changedFields(original, edited) {
    var out = {};
    var before = original || {};
    Object.keys(edited || {}).forEach(function (key) {
      var next = text(edited[key]);
      if (next !== text(before[key])) {
        out[key] = next;
      }
    });
    return out;
  }

  /** 'Nghĩa 2 · Audio 1': error types with Vietnamese labels, most reported first. */
  function errorTypeSummary(map) {
    if (!map) {
      return '';
    }
    return Object.keys(map)
      .filter(function (key) { return Number(map[key]) > 0; })
      .sort(function (a, b) {
        return Number(map[b]) - Number(map[a]) || ERROR_ORDER.indexOf(a) - ERROR_ORDER.indexOf(b);
      })
      .map(function (key) { return (ERROR_LABELS[key] || key) + ' ' + map[key]; })
      .join(' · ');
  }

  /** File the export is saved as; copy it to data/learning-path/edits/<level>.json. */
  function exportFileName(level) {
    return 'edits-' + String(level || '').trim().toUpperCase() + '.json';
  }

  /** Toast after an export: all rows of the level, how many are new, and that the file is replaced whole. */
  function exportSummary(data, level) {
    var lv = String(level || '').trim().toUpperCase();
    return 'Đã xuất ' + data.count + ' sửa đổi của ' + lv + ' (' + data.newCount + ' mới). Tệp chứa mọi sửa của cấp:'
      + ' thay thế toàn bộ data/learning-path/edits/' + lv + '.json.';
  }

  /** Main resolve button: a HOLD item keeps HOLD, so the label must not promise a release. */
  function resolveLabel(contentStatus) {
    return contentStatus === 'HOLD' ? 'Xong: bỏ ẩn (giữ HOLD)' : 'Xong: bỏ ẩn + đã duyệt';
  }

  // ------------------------------------------------------------------ page

  var state = { status: 'OPEN', level: '', page: 0, total: 0, size: 20, selected: null, item: null };

  function $(id) {
    return global.document.getElementById(id);
  }

  function esc(value) {
    return global.Admin.esc(value);
  }

  function itemUrl(type, code) {
    return BASE + '/items/' + encodeURIComponent(type) + '/' + encodeURIComponent(code);
  }

  function when(value) {
    return value ? global.Admin.date(value) : '';
  }

  async function loadQueue() {
    var status = $('jpaListStatus');
    status.textContent = 'Đang tải…';
    try {
      var query = '?status=' + encodeURIComponent(state.status) + '&page=' + state.page + '&size=' + state.size
        + (state.level ? '&level=' + encodeURIComponent(state.level) : '');
      var page = await global.Admin.api.get(BASE + '/reports' + query);
      state.total = page.total;
      renderGroups(page.items || []);
      var pages = Math.max(1, Math.ceil(page.total / page.size));
      $('jpaPageInfo').textContent = 'Trang ' + (page.page + 1) + '/' + pages;
      $('jpaPrev').disabled = page.page <= 0;
      $('jpaNext').disabled = page.page + 1 >= pages;
      status.textContent = page.total ? page.total + ' mục bị báo' : 'Không có báo lỗi nào ở bộ lọc này.';
    } catch (error) {
      status.textContent = error.message;
    }
  }

  function renderGroups(items) {
    $('jpaGroups').innerHTML = items.map(function (g) {
      var selected = state.selected && state.selected.type === g.targetType && state.selected.code === g.targetCode;
      var badges = (g.hidden ? '<span class="jpa-badge is-warn">Đang ẩn</span>' : '')
        + (g.contentStatus === 'HOLD' ? '<span class="jpa-badge">HOLD</span>' : '');
      return '<li><button type="button" class="jpa-group' + (selected ? ' is-selected' : '') + '" data-type="'
        + esc(g.targetType) + '" data-code="' + esc(g.targetCode) + '"' + (selected ? ' aria-current="true"' : '') + '>'
        + '<span class="jpa-group__head"><span class="jpa-group__type">' + esc(TYPE_LABELS[g.targetType] || g.targetType)
        + (g.level ? ' · ' + esc(g.level) : '') + '</span>' + badges + '</span>'
        + '<span class="jpa-group__preview" lang="ja">' + esc(g.preview || g.targetCode) + '</span>'
        + '<span class="jpa-group__meta">' + esc(g.reportCount) + ' báo cáo · ' + esc(g.reporterCount) + ' người · '
        + esc(errorTypeSummary(g.errorTypes)) + '</span>'
        + (g.notes && g.notes.length ? '<span class="jpa-group__note">“' + esc(g.notes[0]) + '”</span>' : '')
        + '</button></li>';
    }).join('');
  }

  async function openItem(type, code) {
    var selected = { type: type, code: code };
    state.selected = selected;
    var detail = $('jpaDetail');
    detail.innerHTML = '<p class="jpa-muted">Đang tải…</p>';
    try {
      var item = await global.Admin.api.get(itemUrl(type, code));
      // a slower answer for an item clicked earlier never replaces the one now selected
      if (state.selected === selected) { renderItem(item); }
    } catch (error) {
      if (state.selected === selected) { detail.innerHTML = '<p class="jpa-error">' + esc(error.message) + '</p>'; }
    }
    global.document.querySelectorAll('.jpa-group').forEach(function (button) {
      var on = button.dataset.type === type && button.dataset.code === code;
      button.classList.toggle('is-selected', on);
      if (on) { button.setAttribute('aria-current', 'true'); } else { button.removeAttribute('aria-current'); }
    });
  }

  function fieldInput(name, value) {
    var id = 'jpaField-' + name;
    var label = '<label for="' + id + '">' + esc(FIELD_LABELS[name] || name) + ' <code>' + esc(name) + '</code></label>';
    if (name === 'status') {
      return '<div class="jpa-field">' + label + '<select id="' + id + '" data-field="status">'
        + CONTENT_STATUSES.map(function (s) {
          return '<option value="' + s + '"' + (s === value ? ' selected' : '') + '>' + s + '</option>';
        }).join('') + '</select></div>';
    }
    var rows = LONG_FIELDS.indexOf(name) >= 0 ? 4 : 2;
    var lang = /^(ja|ja_furigana|prompt_ja|prompt_furigana|options_json|answer_text|accept_json)$/.test(name)
      ? ' lang="ja"' : '';
    return '<div class="jpa-field">' + label + '<textarea id="' + id + '" data-field="' + esc(name) + '" rows="' + rows
      + '"' + lang + '>' + esc(value == null ? '' : value) + '</textarea></div>';
  }

  function renderItem(item) {
    state.item = item;
    var fields = item.fields || {};
    var names = Object.keys(fields);
    var hasOpen = (item.reports || []).some(function (r) { return r.status === 'OPEN'; });
    var html = '<header class="jpa-detail__head"><div><span class="jpa-eyebrow">'
      + esc(TYPE_LABELS[item.targetType] || item.targetType) + ' · ' + esc(item.targetCode)
      + (item.level ? ' · ' + esc(item.level) : '') + '</span>'
      + '<h2 lang="ja">' + esc(item.context || item.targetCode) + '</h2>'
      + '<p class="jpa-muted">' + (item.status ? 'Trạng thái: ' + esc(item.status) + ' · ' : '')
      + (item.reportHidden ? '<span class="jpa-badge is-warn">Đang ẩn</span> ' : '')
      + (item.authored ? 'Nội dung biên soạn' : 'Nội dung gốc') + '</p></div></header>';
    if (item.audioUrl) {
      html += '<audio class="jpa-audio" controls preload="none" src="' + esc(global.Admin.safeUrl(item.audioUrl) || '')
        + '"></audio>';
    }
    if (names.length) {
      html += '<form id="jpaEditForm" class="jpa-form">' + names.map(function (n) { return fieldInput(n, fields[n]); })
        .join('') + '<p id="jpaAudioNote" class="jpa-note" hidden>' + esc(REGEN_AUDIO_NOTE) + '</p>'
        + '<div class="jpa-actions"><button class="jpa-btn is-primary" type="submit">Lưu sửa</button></div></form>';
    } else {
      html += '<p class="jpa-muted">Mục này không sửa được tại đây.</p>';
    }
    html += '<div class="jpa-actions">'
      + '<button id="jpaResolve" class="jpa-btn is-primary" type="button"' + (hasOpen || item.reportHidden ? '' : ' disabled')
      + '>' + esc(resolveLabel(item.status)) + '</button>'
      + '<button id="jpaReject" class="jpa-btn is-secondary" type="button"' + (hasOpen ? '' : ' disabled')
      + '>Từ chối báo cáo</button></div>'
      + (item.status === 'HOLD' ? '<p class="jpa-note">' + esc(HOLD_KEPT) + '</p>' : '');
    html += '<h3>Báo cáo (' + (item.reports || []).length + ')</h3><ul class="jpa-rows">'
      + (item.reports || []).map(function (r) {
        return '<li><span class="jpa-badge">' + esc(STATUS_LABELS[r.status] || r.status) + '</span> '
          + esc(ERROR_LABELS[r.errorType] || r.errorType) + ' · ' + esc(when(r.createdAt))
          + (r.pageContext ? ' · ' + esc(r.pageContext) : '')
          + (r.note ? '<div class="jpa-row__note">' + esc(r.note) + '</div>' : '') + '</li>';
      }).join('') + '</ul>';
    html += '<h3>Lịch sử sửa</h3>' + ((item.edits || []).length ? '<ul class="jpa-rows">'
      + item.edits.map(function (e) {
        return '<li><code>' + esc(e.field) + '</code> · ' + esc(when(e.createdAt))
          + (e.editorUserId === 0 ? ' · hệ thống' : '') + (e.exportedAt ? ' · đã xuất' : ' · chưa xuất')
          + '<div class="jpa-row__note"><del>' + esc(e.oldValue) + '</del> → <ins>' + esc(e.newValue) + '</ins></div></li>';
      }).join('') + '</ul>' : '<p class="jpa-muted">Chưa có sửa đổi.</p>');
    $('jpaDetail').innerHTML = html;
  }

  function editedValues() {
    var out = {};
    global.document.querySelectorAll('#jpaEditForm [data-field]').forEach(function (input) {
      out[input.dataset.field] = input.value;
    });
    return out;
  }

  async function save(event) {
    event.preventDefault();
    var item = state.item;
    var changes = changedFields(item.fields, editedValues());
    if (!Object.keys(changes).length) {
      global.Admin.toast('Chưa có thay đổi nào.', 'warning');
      return;
    }
    try {
      renderItem(await global.Admin.api.put(itemUrl(item.targetType, item.targetCode), { fields: changes }));
      global.Admin.toast('Đã lưu sửa đổi.', 'success');
      if (item.targetType === 'EXAMPLE' && 'ja' in changes) {
        global.Admin.toast(REGEN_AUDIO_NOTE, 'warning');
      }
      loadQueue();
    } catch (error) {
      global.Admin.toast(error.message, 'error');
    }
  }

  async function resolve(status, unhide, markReviewed) {
    var item = state.item;
    try {
      var view = await global.Admin.api.post(itemUrl(item.targetType, item.targetCode) + '/resolve',
        { status: status, unhide: unhide, markReviewed: markReviewed });
      renderItem(view);
      global.Admin.toast(status === 'RESOLVED' ? 'Đã xử lý báo lỗi.' : 'Đã từ chối báo cáo.', 'success');
      if (markReviewed && view.status === 'HOLD') {
        global.Admin.toast(HOLD_KEPT, 'warning');
      }
      loadQueue();
    } catch (error) {
      global.Admin.toast(error.message, 'error');
    }
  }

  async function exportEdits(event) {
    event.preventDefault();
    var level = $('jpaExportLevel').value;
    try {
      var data = await global.Admin.api.post(BASE + '/edits/export?level=' + encodeURIComponent(level));
      var blob = new global.Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' });
      var url = global.URL.createObjectURL(blob);
      var link = global.document.createElement('a');
      link.href = url;
      link.download = exportFileName(level);
      global.document.body.appendChild(link);
      link.click();
      link.remove();
      global.setTimeout(function () { global.URL.revokeObjectURL(url); }, 1000);
      global.Admin.toast(exportSummary(data, level), 'success');
    } catch (error) {
      global.Admin.toast(error.message, 'error');
    }
  }

  function bind() {
    $('jpaStatus').addEventListener('change', function (e) { state.status = e.target.value; state.page = 0; loadQueue(); });
    $('jpaLevel').addEventListener('change', function (e) { state.level = e.target.value; state.page = 0; loadQueue(); });
    $('jpaPrev').addEventListener('click', function () { if (state.page > 0) { state.page--; loadQueue(); } });
    $('jpaNext').addEventListener('click', function () { state.page++; loadQueue(); });
    $('jpaGroups').addEventListener('click', function (e) {
      var button = e.target.closest('.jpa-group');
      if (button) { openItem(button.dataset.type, button.dataset.code); }
    });
    var detail = $('jpaDetail');
    detail.addEventListener('submit', function (e) { if (e.target.id === 'jpaEditForm') { save(e); } });
    detail.addEventListener('input', function (e) {
      var note = $('jpaAudioNote');
      if (note && state.item && state.item.targetType === 'EXAMPLE' && e.target.dataset.field === 'ja') {
        note.hidden = text(e.target.value) === text(state.item.fields.ja);
      }
    });
    detail.addEventListener('click', function (e) {
      if (e.target.id === 'jpaResolve') { resolve('RESOLVED', true, true); }
      if (e.target.id === 'jpaReject') { resolve('REJECTED', true, false); }
    });
    $('jpaExportForm').addEventListener('submit', exportEdits);
  }

  var mounted = false;

  async function mount() {
    if (mounted || !global.AdminShell || !$('jpaGroups')) {
      return;
    }
    mounted = true;
    if (!(await global.AdminShell.ready)) {
      return;
    }
    bind();
    loadQueue();
  }

  return { changedFields: changedFields, errorTypeSummary: errorTypeSummary, exportFileName: exportFileName,
    exportSummary: exportSummary, resolveLabel: resolveLabel, mount: mount };
});
