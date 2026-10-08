/**
 * /textbooks/lessons/{id}/edit — biên soạn nội dung bên trong một bài học (CONTENT_CREATOR).
 * Dữ liệu: /api/dictionary/manage/** và các endpoint ghi, đều có @PreAuthorize ở server.
 * <p>
 * Tách khỏi textbook-manage.js vì đây là màn hình soạn thảo: mẫu ngữ pháp có bốn trường văn bản dài và
 * kèm danh sách câu ví dụ phải nhìn cùng lúc. Quyền thật chặn ở server; JS chỉ ẩn/hiện và khoá nút.
 */
(function () {
  'use strict';

  var API = '/api/dictionary';
  var SEARCH_PAGE_SIZE = 20;
  var SEARCH_DEBOUNCE_MS = 300;

  var main = document.getElementById('jlabBaseContent');
  var lessonId = main ? main.getAttribute('data-lesson-id') : null;

  var loadingEl = document.getElementById('editorLoading');
  var errorEl = document.getElementById('editorError');
  var lockedEl = document.getElementById('editorLocked');
  var headerEl = document.getElementById('editorHeader');
  var tabsEl = document.getElementById('editorTabs');

  var grammarListEl = document.getElementById('grammarList');
  var lessonVocabListEl = document.getElementById('lessonVocabList');
  var vocabResultsEl = document.getElementById('vocabSearchResults');
  var vocabSearchInput = document.getElementById('vocabSearchInput');
  var vocabSearchMeta = document.getElementById('vocabSearchMeta');
  var vocabPager = document.getElementById('vocabPager');
  var vocabPageLabel = document.getElementById('vocabPageLabel');

  var kanjiForm = document.getElementById('kanjiForm');
  var kanjiInput = document.getElementById('kanjiInput');
  var manualKanjiListEl = document.getElementById('manualKanjiList');
  var derivedKanjiListEl = document.getElementById('derivedKanjiList');
  var roleListEl = document.getElementById('roleList');
  var sceneListEl = document.getElementById('sceneList');
  var importEl = document.getElementById('editorImport');
  var importFileInput = document.getElementById('importFileInput');
  var importResultEl = document.getElementById('importResult');

  var modalEl = document.getElementById('editorModal');
  var modalTitleEl = document.getElementById('editorModalTitle');
  var formEl = document.getElementById('editorForm');
  var formFieldsEl = document.getElementById('editorFormFields');
  var formErrorEl = document.getElementById('editorFormError');
  var submitBtn = document.getElementById('editorSubmitBtn');

  var state = {
    lesson: null,
    locked: '',            // lý do khoá; rỗng = không khoá
    grammar: [],           // [{point, examples}]
    lessonVocab: [],
    kanji: { manual: [], derived: [] },
    roles: [],
    scenes: [],            // [{sectionId, title, sectionTitle, lines}]
    search: { keyword: '', page: 0, data: null },
    submitting: false
  };

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toast(message, kind) {
    if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(message, kind || 'info');
  }

  function clearError() {
    errorEl.textContent = '';
    errorEl.classList.remove('is-visible');
  }

  /**
   * Lỗi cần đọc kỹ rồi mới hành động được (409 "còn N câu ví dụ", 400 "phần thuộc bài khác") hiện ở dải
   * cố định và nằm lại cho tới thao tác sau. KHÔNG dùng toast cho nhóm này: toast tự xoá sau 4,5 giây.
   */
  function showStickyError(message) {
    errorEl.textContent = message;
    errorEl.classList.add('is-visible');
    if (typeof errorEl.scrollIntoView === 'function') {
      errorEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }

  function request(path, options) {
    var opts = options || {};
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    // FormData (upload file): phải XOÁ Content-Type để trình duyệt tự đặt multipart/form-data kèm boundary.
    // JlasAuth.authHeaders() luôn trả sẵn 'application/json', chỉ "không gắn thêm" là chưa đủ.
    if (opts.body instanceof FormData) delete headers['Content-Type'];
    else if (opts.body) headers['Content-Type'] = 'application/json';
    return fetch(API + path, { method: opts.method || 'GET', headers: headers, body: opts.body })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (res.status === 403) throw new Error('Bạn không có quyền biên soạn nội dung.');
          if (!res.ok || !json || json.success === false) {
            throw new Error((json && json.message) || ('Lỗi HTTP ' + res.status));
          }
          return json.data;
        });
      });
  }

  // ── Hiển thị ───────────────────────────────────────────────────────────────

  function actionBtn(action, id, icon, label, extraClass) {
    var locked = state.locked && action !== 'noop';
    var title = locked ? state.locked : label;
    return '<button type="button" class="manage-icon-btn' + (extraClass ? ' ' + extraClass : '') + '"'
      + ' data-action="' + action + '" data-id="' + id + '"'
      + (locked ? ' disabled' : '')
      + ' title="' + escapeHtml(title) + '" aria-label="' + escapeHtml(title) + '">'
      + '<i class="fa-solid ' + icon + '" aria-hidden="true"></i></button>';
  }

  function exampleRow(example) {
    return '<li class="editor-example">'
      + '<div class="editor-example__text">'
      + '<span class="editor-example__ja" lang="ja">' + escapeHtml(example.textJa) + '</span>'
      + (example.textVi ? '<span class="editor-example__vi">' + escapeHtml(example.textVi) + '</span>' : '')
      + '<span class="editor-example__meta">' + escapeHtml(example.title)
      + (example.sectionTitle ? ' · ' + escapeHtml(example.sectionTitle) : '') + '</span>'
      + '</div>'
      + '<span class="editor-row__actions">'
      + actionBtn('edit-example', example.contentItemId, 'fa-pen', 'Sửa câu ví dụ')
      + '<span class="manage-actions-sep" aria-hidden="true"></span>'
      + actionBtn('delete-example', example.contentItemId, 'fa-trash',
        'Xoá vĩnh viễn — không hoàn tác được', 'manage-icon-btn--danger')
      + '</span></li>';
  }

  /** Mẫu và câu ví dụ của nó nằm trong CÙNG một khối, không tách modal — đó là lý do chính tách trang. */
  function grammarCard(entry) {
    var point = entry.point;
    var examples = entry.examples || [];
    return '<article class="editor-card" data-grammar-id="' + point.id + '">'
      + '<header class="editor-card__head">'
      + '<div class="editor-card__title">'
      + '<span class="editor-card__order">' + escapeHtml(point.orderIndex) + '</span>'
      + '<span class="editor-card__pattern" lang="ja">' + escapeHtml(point.pattern) + '</span>'
      + '</div>'
      + '<span class="editor-row__actions">'
      + actionBtn('move-grammar-up', point.id, 'fa-arrow-up', 'Lên trên')
      + actionBtn('move-grammar-down', point.id, 'fa-arrow-down', 'Xuống dưới')
      + actionBtn('add-example', point.id, 'fa-plus', 'Thêm câu ví dụ')
      + actionBtn('edit-grammar', point.id, 'fa-pen', 'Sửa mẫu ngữ pháp')
      + '<span class="manage-actions-sep" aria-hidden="true"></span>'
      + actionBtn('delete-grammar', point.id, 'fa-trash',
        'Xoá vĩnh viễn — chỉ được khi mẫu không còn câu ví dụ', 'manage-icon-btn--danger')
      + '</span></header>'
      + '<p class="editor-card__meaning">' + escapeHtml(point.meaning) + '</p>'
      + (point.example ? '<p class="editor-card__sample" lang="ja">' + escapeHtml(point.example) + '</p>' : '')
      + (point.note ? '<p class="editor-card__note">' + escapeHtml(point.note) + '</p>' : '')
      + '<ul class="editor-examples">'
      + (examples.length ? examples.map(exampleRow).join('')
        : '<li class="editor-empty-hint">Mẫu này chưa có câu ví dụ nào.</li>')
      + '</ul></article>';
  }

  function renderGrammar() {
    document.getElementById('countGrammar').textContent = state.grammar.length;
    grammarListEl.innerHTML = state.grammar.length
      ? state.grammar.map(grammarCard).join('')
      : '<p class="editor-empty-hint">Bài này chưa có mẫu ngữ pháp nào.</p>';
  }

  function vocabRow(word, inLesson) {
    var meta = [word.reading, word.readingRomaji, word.jlptLevel, word.wordType]
      .filter(function (v) { return !!v; }).join(' · ');
    return '<div class="editor-word">'
      + '<div class="editor-word__text">'
      + '<span class="editor-word__expression" lang="ja">' + escapeHtml(word.expression) + '</span>'
      + '<span class="editor-word__meta">' + escapeHtml(meta) + '</span>'
      + (word.meaning ? '<span class="editor-word__meaning">' + escapeHtml(word.meaning) + '</span>' : '')
      + '</div>'
      + (inLesson
        ? actionBtn('detach-vocab', word.id, 'fa-xmark', 'Gỡ từ khỏi bài học', 'manage-icon-btn--danger')
        : (word.attachedToLesson
          ? '<span class="editor-word__tag">đã có trong bài</span>'
          : actionBtn('attach-vocab', word.id, 'fa-plus', 'Thêm từ này vào bài')))
      + '</div>';
  }

  function renderLessonVocab() {
    document.getElementById('countVocab').textContent = state.lessonVocab.length;
    document.getElementById('countPicked').textContent = state.lessonVocab.length;
    lessonVocabListEl.innerHTML = state.lessonVocab.length
      ? state.lessonVocab.map(function (w) { return vocabRow(w, true); }).join('')
      : '<p class="editor-empty-hint">Bài này chưa có từ vựng nào.</p>';
  }

  function renderSearch() {
    var data = state.search.data;
    if (!data) {
      vocabResultsEl.innerHTML = '';
      vocabPager.hidden = true;
      return;
    }
    vocabResultsEl.innerHTML = data.content.length
      ? data.content.map(function (w) { return vocabRow(w, false); }).join('')
      : '<p class="editor-empty-hint">Không tìm thấy từ nào khớp.</p>';
    vocabSearchMeta.textContent = 'Tìm thấy ' + data.totalElements + ' từ.';
    vocabPager.hidden = data.totalPages <= 1;
    vocabPageLabel.textContent = 'Trang ' + (data.page + 1) + '/' + data.totalPages;
    vocabPager.querySelector('[data-action="vocab-prev"]').disabled = data.first;
    vocabPager.querySelector('[data-action="vocab-next"]').disabled = data.last;
  }

  function kanjiCard(kanji, manual) {
    var readings = (kanji.readings || []).map(function (r) { return r.readingText; })
      .filter(function (v) { return !!v; }).slice(0, 4).join('、');
    var meta = [kanji.hanViet, kanji.jlptLevel].filter(function (v) { return !!v; }).join(' · ');
    return '<div class="editor-kanji">'
      + '<span class="editor-kanji__glyph" lang="ja">' + escapeHtml(kanji.character) + '</span>'
      + '<div class="editor-kanji__text">'
      + (meta ? '<span class="editor-kanji__meta">' + escapeHtml(meta) + '</span>' : '')
      + (kanji.meaning ? '<span class="editor-kanji__meaning">' + escapeHtml(kanji.meaning) + '</span>' : '')
      + (readings ? '<span class="editor-kanji__readings" lang="ja">' + escapeHtml(readings) + '</span>' : '')
      + '</div>'
      + (manual ? actionBtn('detach-kanji', kanji.id, 'fa-xmark', 'Gỡ kanji khỏi bài học', 'manage-icon-btn--danger') : '')
      + '</div>';
  }

  function renderKanji() {
    var manual = state.kanji.manual;
    var derived = state.kanji.derived;
    document.getElementById('countKanji').textContent = manual.length + derived.length;
    document.getElementById('countManualKanji').textContent = manual.length;
    document.getElementById('countDerivedKanji').textContent = derived.length;
    manualKanjiListEl.innerHTML = manual.length
      ? manual.map(function (k) { return kanjiCard(k, true); }).join('')
      : '<p class="editor-empty-hint">Chưa gắn tay chữ nào.</p>';
    derivedKanjiListEl.innerHTML = derived.length
      ? derived.map(function (k) { return kanjiCard(k, false); }).join('')
      : '<p class="editor-empty-hint">Từ vựng của bài chưa có chữ Kanji nào.</p>';
  }

  function roleRow(role) {
    var meta = [role.code, role.nameJa].filter(function (v) { return !!v; }).join(' · ');
    return '<div class="editor-word">'
      + '<div class="editor-word__text">'
      + '<span class="editor-word__expression">' + escapeHtml(role.nameVi) + '</span>'
      + '<span class="editor-word__meta" lang="ja">' + escapeHtml(meta) + '</span>'
      + '</div>'
      + '<span class="editor-row__actions">'
      + actionBtn('edit-role', role.id, 'fa-pen', 'Sửa vai')
      + actionBtn('delete-role', role.id, 'fa-trash', 'Xoá vai — chỉ được khi vai chưa có lời thoại',
        'manage-icon-btn--danger')
      + '</span></div>';
  }

  function lineRow(line) {
    var side = line.bubbleSide === 'phai' ? 'phải' : (line.bubbleSide === 'trai' ? 'trái' : '');
    return '<li class="editor-example">'
      + '<div class="editor-example__text">'
      + '<span class="editor-example__meta">' + escapeHtml(line.roleNameVi || 'Không gắn vai')
      + (side ? ' · bên ' + side : '') + '</span>'
      + '<span class="editor-example__ja" lang="ja">' + escapeHtml(line.text) + '</span>'
      + (line.translatedText ? '<span class="editor-example__vi">' + escapeHtml(line.translatedText) + '</span>' : '')
      + '</div>'
      + '<span class="editor-row__actions">'
      + actionBtn('move-line-up', line.contentItemId, 'fa-arrow-up', 'Lên trên')
      + actionBtn('move-line-down', line.contentItemId, 'fa-arrow-down', 'Xuống dưới')
      + actionBtn('edit-line', line.contentItemId, 'fa-pen', 'Sửa lời thoại')
      + '<span class="manage-actions-sep" aria-hidden="true"></span>'
      + actionBtn('delete-line', line.contentItemId, 'fa-trash', 'Xoá vĩnh viễn — không hoàn tác được',
        'manage-icon-btn--danger')
      + '</span></li>';
  }

  function sceneCard(scene) {
    var lines = scene.lines || [];
    return '<article class="editor-card">'
      + '<header class="editor-card__head">'
      + '<div class="editor-card__title"><span class="editor-card__pattern">' + escapeHtml(scene.title) + '</span></div>'
      + '<span class="editor-row__actions">'
      + actionBtn('add-line', scene.sectionId, 'fa-plus', 'Thêm lời thoại')
      + actionBtn('rename-scene', scene.sectionId, 'fa-pen', 'Đổi tên đoạn hội thoại')
      + '<span class="manage-actions-sep" aria-hidden="true"></span>'
      + actionBtn('delete-scene', scene.sectionId, 'fa-trash', 'Xoá đoạn — chỉ được khi đoạn không còn lời thoại',
        'manage-icon-btn--danger')
      + '</span></header>'
      + '<ul class="editor-examples">'
      + (lines.length ? lines.map(lineRow).join('')
        : '<li class="editor-empty-hint">Đoạn này chưa có lời thoại nào.</li>')
      + '</ul></article>';
  }

  function renderDialogue() {
    var lineCount = state.scenes.reduce(function (sum, s) { return sum + (s.lines || []).length; }, 0);
    document.getElementById('countDialogue').textContent = lineCount;
    document.getElementById('countRoles').textContent = state.roles.length;
    roleListEl.innerHTML = state.roles.length
      ? state.roles.map(roleRow).join('')
      : '<p class="editor-empty-hint">Chưa có vai nào. Thêm vai trước khi viết lời thoại.</p>';
    sceneListEl.innerHTML = state.scenes.length
      ? state.scenes.map(sceneCard).join('')
      : '<p class="editor-empty-hint">Bài này chưa có đoạn hội thoại nào.</p>';
  }

  /** Kết quả import nằm lại cho tới lần import sau: danh sách dòng lỗi thường dài, cần đọc kỹ. */
  function renderImportResult(result) {
    importResultEl.classList.toggle('is-error', !result.imported);
    if (!result.imported) {
      var errors = result.errors || [];
      importResultEl.innerHTML = '<strong>File có ' + errors.length + ' lỗi, chưa nhập dữ liệu nào.</strong>'
        + ' Sửa các dòng dưới đây rồi tải lên lại.'
        + '<ul class="editor-import-result__errors">'
        + errors.map(function (e) {
          return '<li><b>' + escapeHtml(e.sheet) + ', dòng ' + escapeHtml(e.row) + ':</b> ' + escapeHtml(e.message) + '</li>';
        }).join('')
        + '</ul>';
    } else {
      var parts = [
        ['từ mới tạo', result.vocabularyCreated], ['từ có sẵn được gắn', result.vocabularyAttached],
        ['mẫu ngữ pháp', result.grammarCreated], ['câu ví dụ', result.examplesCreated],
        ['vai', result.dialogueRolesCreated], ['đoạn hội thoại', result.dialogueScenesCreated],
        ['lời thoại', result.dialogueLinesCreated], ['kanji', result.kanjiAttached]
      ].filter(function (p) { return p[1] > 0; })
        .map(function (p) { return p[1] + ' ' + p[0]; });
      var skipped = [
        ['từ đã có', result.vocabularySkipped], ['mẫu đã có', result.grammarSkipped], ['kanji đã có', result.kanjiSkipped]
      ].filter(function (p) { return p[1] > 0; })
        .map(function (p) { return p[1] + ' ' + p[0]; });
      importResultEl.innerHTML = '<strong>Đã nhập:</strong> ' + escapeHtml(parts.length ? parts.join(', ') : 'không có mục mới')
        + (skipped.length ? '<br><span class="editor-panel__hint">Bỏ qua: ' + escapeHtml(skipped.join(', ')) + '.</span>' : '');
    }
    importResultEl.hidden = false;
  }

  function renderHeader() {
    var lesson = state.lesson;
    document.getElementById('breadcrumbLesson').textContent = lesson.title;
    document.getElementById('editorTitle').textContent = lesson.title;
    document.getElementById('editorContext').textContent =
      [lesson.textbookTitle, lesson.unitTitle].filter(function (v) { return !!v; }).join(' · ');
    document.getElementById('editorMeta').innerHTML =
      '<span class="tb-badge">' + escapeHtml(lesson.status) + '</span>'
      + (lesson.knowledgeTagName ? '<span class="tb-badge">' + escapeHtml(lesson.knowledgeTagName) + '</span>' : '');
    headerEl.hidden = false;
    tabsEl.hidden = false;
    importEl.hidden = false;
    importEl.querySelector('[data-action="import-excel"]').disabled = !!state.locked;
    kanjiForm.querySelector('[data-requires-unlock]').disabled = !!state.locked;
  }

  // ── Tải dữ liệu ────────────────────────────────────────────────────────────

  function loadLesson() {
    return request('/lessons/' + encodeURIComponent(lessonId) + '/content').then(function (data) {
      state.lesson = data;
      // Giáo trình đã lưu trữ: khoá mọi thao tác ghi. Backend cũng trả 409, đây là để báo trước.
      state.locked = data.textbookStatus === 'ARCHIVED'
        ? 'Giáo trình đang lưu trữ — khôi phục giáo trình để sửa nội dung bên trong'
        : '';
      if (state.locked) {
        lockedEl.textContent = state.locked + '.';
        lockedEl.hidden = false;
      }
      renderHeader();
    });
  }

  /** Mẫu ngữ pháp kèm câu ví dụ của từng mẫu: 1 request cho danh sách mẫu, rồi 1 request mỗi mẫu. */
  function loadGrammar() {
    return request('/manage/lessons/' + encodeURIComponent(lessonId) + '/grammar-points')
      .then(function (points) {
        return Promise.all((points || []).map(function (point) {
          return request('/manage/grammar-points/' + point.id + '/examples')
            .then(function (examples) { return { point: point, examples: examples || [] }; });
        }));
      })
      .then(function (entries) {
        state.grammar = entries;
        renderGrammar();
      });
  }

  function loadLessonVocab() {
    return request('/manage/lessons/' + encodeURIComponent(lessonId) + '/vocabulary')
      .then(function (words) {
        state.lessonVocab = words || [];
        renderLessonVocab();
      });
  }

  function loadSearch() {
    var params = '?q=' + encodeURIComponent(state.search.keyword)
      + '&lessonId=' + encodeURIComponent(lessonId)
      + '&page=' + state.search.page
      + '&size=' + SEARCH_PAGE_SIZE;
    return request('/manage/vocabulary/search' + params).then(function (data) {
      state.search.data = data;
      renderSearch();
    });
  }

  /**
   * Kanji gắn tay lấy từ API quản trị; kanji suy ra từ từ vựng = danh sách kanji học viên thấy
   * (/lessons/{id}/content đã gộp hai nguồn) trừ đi các chữ gắn tay.
   */
  function loadKanji() {
    return Promise.all([
      request('/manage/lessons/' + encodeURIComponent(lessonId) + '/kanji'),
      request('/lessons/' + encodeURIComponent(lessonId) + '/content')
    ]).then(function (results) {
      var manual = results[0] || [];
      var manualIds = manual.map(function (k) { return k.id; });
      state.kanji.manual = manual;
      state.kanji.derived = ((results[1] && results[1].kanjis) || []).filter(function (k) {
        return manualIds.indexOf(k.id) === -1;
      });
      renderKanji();
    });
  }

  function loadDialogue() {
    return Promise.all([
      request('/manage/lessons/' + encodeURIComponent(lessonId) + '/dialogue-roles'),
      request('/manage/lessons/' + encodeURIComponent(lessonId) + '/dialogue-scenes')
    ]).then(function (results) {
      state.roles = results[0] || [];
      state.scenes = results[1] || [];
      renderDialogue();
    });
  }

  function reloadAll() {
    return Promise.all([loadGrammar(), loadLessonVocab(), loadKanji(), loadDialogue()])
      .then(function () { return state.search.data ? loadSearch() : null; });
  }

  // ── Form ───────────────────────────────────────────────────────────────────

  function field(label, name, value, attrs) {
    return '<label class="manage-field"><span>' + escapeHtml(label) + '</span>'
      + '<input class="manage-input" name="' + name + '" value="' + escapeHtml(value == null ? '' : value) + '" '
      + (attrs || '') + '></label>';
  }

  function areaField(label, name, value, rows) {
    return '<label class="manage-field"><span>' + escapeHtml(label) + '</span>'
      + '<textarea class="manage-input" name="' + name + '" rows="' + (rows || 3) + '">'
      + escapeHtml(value == null ? '' : value) + '</textarea></label>';
  }

  /** options: [[value, label], ...]; value rỗng = "không chọn". */
  function selectField(label, name, options, value) {
    return '<label class="manage-field"><span>' + escapeHtml(label) + '</span>'
      + '<select class="manage-input" name="' + name + '">'
      + options.map(function (o) {
        return '<option value="' + escapeHtml(o[0]) + '"' + (String(o[0]) === String(value == null ? '' : value) ? ' selected' : '')
          + '>' + escapeHtml(o[1]) + '</option>';
      }).join('')
      + '</select></label>';
  }

  var modalContext = null;

  /** reload: tải lại phần bị ảnh hưởng sau khi lưu; mặc định là ngữ pháp (form ngữ pháp/câu ví dụ). */
  function openModal(title, fieldsHtml, onSubmit, reload) {
    modalContext = { onSubmit: onSubmit, reload: reload || loadGrammar };
    modalTitleEl.textContent = title;
    formFieldsEl.innerHTML = fieldsHtml;
    formErrorEl.hidden = true;
    submitBtn.disabled = false;
    state.submitting = false;
    modalEl.hidden = false;
    var first = formFieldsEl.querySelector('input, textarea, select');
    if (first) first.focus();
  }

  function closeModal() {
    modalEl.hidden = true;
    modalContext = null;
    state.submitting = false;
  }

  function formValues() {
    var data = {};
    Array.prototype.forEach.call(formEl.elements, function (el) {
      if (el.name) data[el.name] = el.value;
    });
    return data;
  }

  function grammarForm(existing) {
    openModal(existing ? 'Sửa mẫu ngữ pháp' : 'Thêm mẫu ngữ pháp',
      field('Mẫu ngữ pháp', 'pattern', existing ? existing.pattern : '', 'required maxlength="500"')
      + areaField('Giải nghĩa', 'meaning', existing ? existing.meaning : '', 4)
      + areaField('Câu ví dụ kèm mẫu', 'example', existing ? existing.example : '', 3)
      + areaField('Ghi chú', 'note', existing ? existing.note : '', 3),
      function (values) {
        var body = JSON.stringify({
          pattern: values.pattern, meaning: values.meaning,
          example: values.example, note: values.note
        });
        return existing
          ? request('/grammar-points/' + existing.id, { method: 'PUT', body: body })
          : request('/lessons/' + lessonId + '/grammar-points', { method: 'POST', body: body });
      });
  }

  function exampleForm(grammarPointId, existing) {
    openModal(existing ? 'Sửa câu ví dụ' : 'Thêm câu ví dụ',
      field('Nhãn', 'title', existing ? existing.title : 'Ví dụ', 'required maxlength="255"')
      + areaField('Câu tiếng Nhật', 'textJa', existing ? existing.textJa : '', 3)
      + areaField('Bản dịch', 'textVi', existing ? existing.textVi : '', 3)
      + '<p class="manage-form__hint">Để trống phần chứa thì hệ thống tự dùng phần “Câu ví dụ” của bài.</p>',
      function (values) {
        var body = JSON.stringify({ title: values.title, textJa: values.textJa, textVi: values.textVi });
        return existing
          ? request('/examples/' + existing.contentItemId, { method: 'PUT', body: body })
          : request('/grammar-points/' + grammarPointId + '/examples', { method: 'POST', body: body });
      });
  }

  var JLPT_OPTIONS = [['', 'Chưa xếp cấp'], ['N5', 'N5'], ['N4', 'N4'], ['N3', 'N3'], ['N2', 'N2'], ['N1', 'N1']];
  var DIALOGUE_SECTION_PREFIX = 'Hội thoại: ';

  /** Gợi ý cách viết bằng chính từ khoá đang tìm, vì thường người biên soạn tìm không thấy rồi mới tạo. */
  function vocabCreateForm() {
    openModal('Tạo từ mới',
      field('Từ (cách viết)', 'expression', state.search.keyword, 'required maxlength="255" lang="ja"')
      + field('Cách đọc (kana)', 'reading', '', 'required maxlength="255" lang="ja"')
      + field('Romaji', 'readingRomaji', '', 'maxlength="255"')
      + areaField('Nghĩa', 'meaning', '', 2)
      + field('Loại từ', 'wordType', '', 'maxlength="50" placeholder="danh từ, động từ nhóm 1…"')
      + selectField('Cấp JLPT', 'jlptLevel', JLPT_OPTIONS, '')
      + areaField('Câu ví dụ (tiếng Nhật)', 'exampleJa', '', 2)
      + areaField('Câu ví dụ (tiếng Việt)', 'exampleVi', '', 2)
      + '<p class="manage-form__hint">Nếu từ điển đã có từ cùng cách viết và cách đọc, hệ thống gắn từ có sẵn'
      + ' thay vì tạo bản trùng. Từ mới ở trạng thái nháp: hiện trong bài học, chưa hiện ở tra cứu từ điển.</p>',
      function (values) {
        return request('/lessons/' + lessonId + '/vocabulary/new', { method: 'POST', body: JSON.stringify(values) });
      },
      function () { return Promise.all([loadLessonVocab(), loadKanji()]).then(function () {
        return state.search.data ? loadSearch() : null;
      }); });
  }

  function roleForm(existing) {
    openModal(existing ? 'Sửa vai' : 'Thêm vai',
      field('Mã vai (viết liền không dấu)', 'code', existing ? existing.code : '',
        'required maxlength="64" placeholder="mira, sato…"')
      + field('Tên vai (tiếng Việt)', 'nameVi', existing ? existing.nameVi : '', 'required maxlength="255"')
      + field('Tên vai (tiếng Nhật)', 'nameJa', existing ? existing.nameJa : '', 'maxlength="255" lang="ja"')
      + areaField('Mô tả', 'description', existing ? existing.description : '', 2),
      function (values) {
        var body = JSON.stringify({
          code: values.code, nameVi: values.nameVi, nameJa: values.nameJa, description: values.description,
          voiceCode: existing ? existing.voiceCode : null
        });
        return existing
          ? request('/dialogue-roles/' + existing.id, { method: 'PUT', body: body })
          : request('/lessons/' + lessonId + '/dialogue-roles', { method: 'POST', body: body });
      },
      loadDialogue);
  }

  /** Đổi tên đi qua PUT /sections: tên phần lưu kèm tiền tố "Hội thoại: " để trang học viên nhận ra đoạn. */
  function sceneForm(existing) {
    openModal(existing ? 'Đổi tên đoạn hội thoại' : 'Thêm đoạn hội thoại',
      field('Tên đoạn', 'title', existing ? existing.title : '', 'required maxlength="200"'),
      function (values) {
        return existing
          ? request('/sections/' + existing.sectionId, { method: 'PUT', body: JSON.stringify({
            title: DIALOGUE_SECTION_PREFIX + values.title.trim(), sectionType: 'EXAMPLE'
          }) })
          : request('/lessons/' + lessonId + '/dialogue-scenes', { method: 'POST', body: JSON.stringify({ title: values.title }) });
      },
      loadDialogue);
  }

  /**
   * Cụm tách nghĩa (chunks) phải ghép lại đúng câu, và server ghi đè cả bộ mỗi lần lưu: giữ nguyên các cụm
   * khi câu tiếng Nhật không đổi, đổi câu thì bỏ cụm cũ (không còn khớp) và báo trước trong form.
   */
  function lineForm(sectionId, existing) {
    var roleOptions = [['', 'Không gắn vai']].concat(state.roles.map(function (r) { return [r.id, r.nameVi + ' (' + r.code + ')']; }));
    var hasChunks = existing && existing.chunks && existing.chunks.length;
    openModal(existing ? 'Sửa lời thoại' : 'Thêm lời thoại',
      selectField('Vai', 'roleId', roleOptions, existing ? existing.roleId : (state.roles[0] ? state.roles[0].id : ''))
      + areaField('Lời thoại tiếng Nhật', 'textJa', existing ? existing.text : '', 3)
      + areaField('Bản dịch', 'textVi', existing ? existing.translatedText : '', 3)
      + selectField('Vị trí bong bóng', 'bubbleSide', [['', 'Mặc định'], ['trai', 'Bên trái'], ['phai', 'Bên phải']],
        existing ? existing.bubbleSide : '')
      + (hasChunks ? '<p class="manage-form__hint">Lời này có ' + existing.chunks.length + ' cụm tách nghĩa.'
        + ' Sửa câu tiếng Nhật sẽ bỏ các cụm đó vì chúng không còn khớp câu mới.</p>' : ''),
      function (values) {
        var textJa = values.textJa.trim();
        var keepChunks = hasChunks && textJa === existing.text;
        var body = JSON.stringify({
          roleId: values.roleId ? Number(values.roleId) : null,
          title: textJa.slice(0, 255),
          textJa: textJa,
          textVi: values.textVi,
          bubbleSide: values.bubbleSide,
          sceneCode: existing ? existing.sceneCode : null,
          chunks: keepChunks ? existing.chunks.map(function (c) { return { surface: c.surface, meaningVi: c.meaningVi }; }) : []
        });
        return existing
          ? request('/dialogue-lines/' + existing.contentItemId, { method: 'PUT', body: body })
          : request('/dialogue-scenes/' + sectionId + '/lines', { method: 'POST', body: body });
      },
      loadDialogue);
  }

  // ── Thao tác ───────────────────────────────────────────────────────────────

  function findGrammar(id) {
    return state.grammar.filter(function (e) { return String(e.point.id) === String(id); })[0] || null;
  }

  function findExample(id) {
    var found = null;
    state.grammar.forEach(function (entry) {
      entry.examples.forEach(function (ex) {
        if (String(ex.contentItemId) === String(id)) found = ex;
      });
    });
    return found;
  }

  function moveGrammar(id, delta) {
    var ids = state.grammar.map(function (e) { return e.point.id; });
    var from = ids.indexOf(Number(id));
    var to = from + delta;
    if (from < 0 || to < 0 || to >= ids.length) return;
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    clearError();
    request('/lessons/' + lessonId + '/grammar-points/order',
      { method: 'PUT', body: JSON.stringify({ orderedIds: ids }) })
      .then(function () {
        toast('Đã cập nhật thứ tự mẫu ngữ pháp.', 'success');
        return loadGrammar();
      })
      // Lỗi thì tải lại từ server để thứ tự hiển thị không lệch với DB.
      .catch(function (err) { showStickyError(err.message); return loadGrammar(); });
  }

  function deleteGrammar(id) {
    var entry = findGrammar(id);
    if (!entry) return;
    if (!window.confirm('Xoá mẫu ngữ pháp “' + entry.point.pattern + '”?\n\n'
      + 'Thao tác KHÔNG hoàn tác được. Chỉ xoá được khi mẫu không còn câu ví dụ nào.')) return;
    clearError();
    request('/grammar-points/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function () { toast('Đã xoá mẫu ngữ pháp.', 'success'); return loadGrammar(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function deleteExample(id) {
    if (!window.confirm('Xoá câu ví dụ này? Thao tác KHÔNG hoàn tác được.')) return;
    clearError();
    request('/examples/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function () { toast('Đã xoá câu ví dụ.', 'success'); return loadGrammar(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function attachVocab(vocabularyId) {
    clearError();
    request('/lessons/' + lessonId + '/vocabulary',
      { method: 'POST', body: JSON.stringify({ vocabularyId: Number(vocabularyId) }) })
      .then(function () { toast('Đã thêm từ vào bài học.', 'success'); return reloadAll(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  /**
   * Xác nhận nói rõ là chỉ gỡ khỏi BÀI NÀY. Người biên soạn không biết cấu trúc bên dưới
   * (content_vocabulary là bảng nối), nếu chỉ hỏi "xoá từ này?" họ sẽ tưởng mất từ khỏi từ điển.
   */
  function detachVocab(vocabularyId) {
    var word = state.lessonVocab.filter(function (w) { return String(w.id) === String(vocabularyId); })[0];
    var name = word ? word.expression : 'này';
    if (!window.confirm('Gỡ từ “' + name + '” khỏi bài học này?\n\n'
      + 'Từ vẫn còn nguyên trong từ điển và trong các bài khác — chỉ bỏ nó khỏi bài đang soạn.')) return;
    clearError();
    request('/lessons/' + lessonId + '/vocabulary/' + encodeURIComponent(vocabularyId), { method: 'DELETE' })
      .then(function () { toast('Đã gỡ từ khỏi bài học.', 'success'); return reloadAll(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function detachKanji(kanjiId) {
    var kanji = state.kanji.manual.filter(function (k) { return String(k.id) === String(kanjiId); })[0];
    if (!window.confirm('Gỡ chữ “' + (kanji ? kanji.character : '') + '” khỏi bài học này?\n\n'
      + 'Chữ vẫn còn trong từ điển. Nếu từ vựng của bài có chứa chữ này thì nó vẫn hiện ở nhóm “Từ từ vựng”.')) return;
    clearError();
    request('/lessons/' + lessonId + '/kanji/' + encodeURIComponent(kanjiId), { method: 'DELETE' })
      .then(function () { toast('Đã gỡ kanji khỏi bài học.', 'success'); return loadKanji(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function findRole(id) {
    return state.roles.filter(function (r) { return String(r.id) === String(id); })[0] || null;
  }

  function findScene(sectionId) {
    return state.scenes.filter(function (s) { return String(s.sectionId) === String(sectionId); })[0] || null;
  }

  function findLine(id) {
    var found = null;
    state.scenes.forEach(function (scene) {
      (scene.lines || []).forEach(function (line) {
        if (String(line.contentItemId) === String(id)) found = { scene: scene, line: line };
      });
    });
    return found;
  }

  function deleteRole(id) {
    var role = findRole(id);
    if (!role || !window.confirm('Xoá vai “' + role.nameVi + '”?\n\nChỉ xoá được khi vai chưa có lời thoại nào.')) return;
    clearError();
    request('/dialogue-roles/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function () { toast('Đã xoá vai.', 'success'); return loadDialogue(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function deleteScene(sectionId) {
    var scene = findScene(sectionId);
    if (!scene || !window.confirm('Xoá đoạn hội thoại “' + scene.title + '”?\n\n'
      + 'Chỉ xoá được khi đoạn không còn lời thoại nào — xoá hết lời trước.')) return;
    clearError();
    request('/sections/' + encodeURIComponent(sectionId), { method: 'DELETE' })
      .then(function () { toast('Đã xoá đoạn hội thoại.', 'success'); return loadDialogue(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function deleteLine(id) {
    if (!window.confirm('Xoá lời thoại này? Thao tác KHÔNG hoàn tác được.')) return;
    clearError();
    request('/dialogue-lines/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function () { toast('Đã xoá lời thoại.', 'success'); return loadDialogue(); })
      .catch(function (err) { showStickyError(err.message); });
  }

  function moveLine(id, delta) {
    var found = findLine(id);
    if (!found) return;
    var ids = found.scene.lines.map(function (l) { return l.contentItemId; });
    var from = ids.indexOf(Number(id));
    var to = from + delta;
    if (from < 0 || to < 0 || to >= ids.length) return;
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    clearError();
    request('/dialogue-scenes/' + found.scene.sectionId + '/lines/order',
      { method: 'PUT', body: JSON.stringify({ orderedIds: ids }) })
      .then(function () { return loadDialogue(); })
      .catch(function (err) { showStickyError(err.message); return loadDialogue(); });
  }

  /** Tải file mẫu qua fetch vì endpoint cần header Authorization (thẻ <a href> không gửi được). */
  function downloadTemplate() {
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    fetch(API + '/manage/lessons/import-template', { headers: headers })
      .then(function (res) {
        if (!res.ok) throw new Error(res.status === 403 ? 'Bạn không có quyền biên soạn nội dung.' : 'Không tải được file mẫu.');
        return res.blob();
      })
      .then(function (blob) {
        var url = URL.createObjectURL(blob);
        var link = document.createElement('a');
        link.href = url;
        link.download = 'mau-noi-dung-bai-hoc.xlsx';
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      })
      .catch(function (err) { showStickyError(err.message); });
  }

  function importExcel(file) {
    if (!file) return;
    var importBtn = importEl.querySelector('[data-action="import-excel"]');
    var form = new FormData();
    form.append('file', file);
    clearError();
    importBtn.disabled = true;
    importResultEl.hidden = true;
    request('/lessons/' + lessonId + '/import', { method: 'POST', body: form })
      .then(function (result) {
        renderImportResult(result);
        if (result.imported) {
          toast('Đã nhập nội dung từ file.', 'success');
          return reloadAll();
        }
        return null;
      })
      .catch(function (err) { showStickyError(err.message); })
      .then(function () {
        importBtn.disabled = !!state.locked;
        importFileInput.value = '';
      });
  }

  // ── Sự kiện ────────────────────────────────────────────────────────────────

  var ACTIONS = {
    'add-grammar': function () { grammarForm(null); },
    'edit-grammar': function (id) { var e = findGrammar(id); if (e) grammarForm(e.point); },
    'delete-grammar': deleteGrammar,
    'move-grammar-up': function (id) { moveGrammar(id, -1); },
    'move-grammar-down': function (id) { moveGrammar(id, 1); },
    'add-example': function (id) { exampleForm(id, null); },
    'edit-example': function (id) { var ex = findExample(id); if (ex) exampleForm(null, ex); },
    'delete-example': deleteExample,
    'attach-vocab': attachVocab,
    'detach-vocab': detachVocab,
    'vocab-prev': function () { state.search.page = Math.max(0, state.search.page - 1); loadSearch(); },
    'vocab-next': function () { state.search.page += 1; loadSearch(); },
    'create-vocab': vocabCreateForm,
    'detach-kanji': detachKanji,
    'add-role': function () { roleForm(null); },
    'edit-role': function (id) { var r = findRole(id); if (r) roleForm(r); },
    'delete-role': deleteRole,
    'add-scene': function () { sceneForm(null); },
    'rename-scene': function (id) { var s = findScene(id); if (s) sceneForm(s); },
    'delete-scene': deleteScene,
    'add-line': function (id) { lineForm(id, null); },
    'edit-line': function (id) { var f = findLine(id); if (f) lineForm(f.scene.sectionId, f.line); },
    'delete-line': deleteLine,
    'move-line-up': function (id) { moveLine(id, -1); },
    'move-line-down': function (id) { moveLine(id, 1); },
    'download-template': downloadTemplate,
    'import-excel': function () { importFileInput.click(); }
  };

  main.addEventListener('click', function (event) {
    var btn = event.target.closest('[data-action]');
    if (!btn || btn.disabled) return;
    var handler = ACTIONS[btn.getAttribute('data-action')];
    if (handler) handler(btn.getAttribute('data-id'));
  });

  formEl.addEventListener('submit', function (event) {
    event.preventDefault();
    if (!modalContext || state.submitting) return;
    state.submitting = true;
    submitBtn.disabled = true;
    formErrorEl.hidden = true;
    var reload = modalContext.reload;
    modalContext.onSubmit(formValues())
      .then(function () {
        closeModal();
        toast('Đã lưu.', 'success');
        return reload();
      })
      .catch(function (err) {
        state.submitting = false;
        submitBtn.disabled = false;
        formErrorEl.textContent = err.message || 'Không lưu được.';
        formErrorEl.hidden = false;
      });
  });

  modalEl.addEventListener('click', function (event) {
    if (event.target.closest('[data-close]')) closeModal();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !modalEl.hidden) closeModal();
  });

  importFileInput.addEventListener('change', function () {
    importExcel(importFileInput.files && importFileInput.files[0]);
  });

  kanjiForm.addEventListener('submit', function (event) {
    event.preventDefault();
    var characters = kanjiInput.value.trim();
    if (!characters || state.locked) return;
    clearError();
    request('/lessons/' + lessonId + '/kanji', { method: 'POST', body: JSON.stringify({ characters: characters }) })
      .then(function () {
        kanjiInput.value = '';
        toast('Đã gắn kanji vào bài học.', 'success');
        return loadKanji();
      })
      .catch(function (err) { showStickyError(err.message); });
  });

  /** Gõ tới đâu tìm tới đó, nhưng gom lại: chỉ bắn request khi người dùng ngừng gõ. */
  var searchTimer = null;
  vocabSearchInput.addEventListener('input', function () {
    if (searchTimer) clearTimeout(searchTimer);
    searchTimer = setTimeout(function () {
      state.search.keyword = vocabSearchInput.value.trim();
      state.search.page = 0;
      loadSearch().catch(function (err) { showStickyError(err.message); });
    }, SEARCH_DEBOUNCE_MS);
  });

  /** Tab dò theo data-tab, không hardcode danh sách: thêm tab hội thoại chỉ cần thêm nút và panel. */
  function setupTabs() {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('.lesson-tab'));
    var panels = Array.prototype.slice.call(document.querySelectorAll('.editor-panel'));
    function activate(target) {
      tabs.forEach(function (t) {
        var active = t.getAttribute('data-tab') === target;
        t.classList.toggle('is-active', active);
        t.setAttribute('aria-selected', active ? 'true' : 'false');
      });
      panels.forEach(function (p) { p.hidden = p.getAttribute('data-panel') !== target; });
      if (target === 'vocab' && !state.search.data) {
        loadSearch().catch(function (err) { showStickyError(err.message); });
      }
    }
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () { activate(tab.getAttribute('data-tab')); });
    });
    activate('grammar');
  }

  if (!lessonId) {
    showStickyError('Thiếu mã bài học.');
    loadingEl.hidden = true;
    return;
  }

  loadLesson()
    .then(function () { return Promise.all([loadGrammar(), loadLessonVocab(), loadKanji(), loadDialogue()]); })
    .then(function () {
      loadingEl.hidden = true;
      setupTabs();
    })
    .catch(function (err) {
      loadingEl.hidden = true;
      showStickyError(err.message || 'Không tải được nội dung bài học.');
    });
})();
