/**
 * /textbooks/manage — quản lý cây nội dung (giáo trình → phần → bài) cho CONTENT_CREATOR.
 * Dữ liệu: /api/dictionary/manage/** và các endpoint ghi, đều có @PreAuthorize ở server.
 * <p>
 * Trang này là nơi DUY NHẤT nhìn thấy mục đã lưu trữ (ARCHIVED). Mọi màn hình khác đều giấu chúng.
 * Quyền thật chặn ở server; JS chỉ ẩn/hiện nút.
 */
window.AdminShell.ready.then(function (allowed) {
  'use strict';
  if (!allowed) return;

  var API = '/api/dictionary';
  var KNOWLEDGE_TAG_TYPE = 'KNOWLEDGE';

  var loadingEl = document.getElementById('manageLoading');
  var errorEl = document.getElementById('manageError');
  var emptyEl = document.getElementById('manageEmpty');
  var treeEl = document.getElementById('manageTree');
  var showArchivedEl = document.getElementById('showArchived');

  var modalEl = document.getElementById('manageModal');
  var modalTitleEl = document.getElementById('manageModalTitle');
  var formEl = document.getElementById('manageForm');
  var formFieldsEl = document.getElementById('manageFormFields');
  var formErrorEl = document.getElementById('manageFormError');
  var submitBtn = document.getElementById('manageSubmitBtn');

  var SKILL_LABELS = {
    VOCABULARY: 'Từ vựng', GRAMMAR: 'Ngữ pháp', READING: 'Đọc', LISTENING: 'Nghe',
    SPEAKING: 'Nói', ONYOMI: 'Onyomi', KUNYOMI: 'Kunyomi'
  };
  var LESSON_TYPE_LABELS = { CURRICULUM: 'Giáo trình', AMON: 'Âm On', REVIEW: 'Ôn tập' };
  var STATUS_LABELS = { DRAFT: 'Nháp', PUBLISHED: 'Đang công bố', ARCHIVED: 'Đã lưu trữ' };

  /** textbooks: danh sách phẳng; trees[id]: cây đã tải; expanded: id đang mở. */
  var state = { textbooks: [], trees: {}, expanded: {}, knowledgeTags: null, submitting: false };

  /*
   * [JLAS2-17] Công bố / ẩn hàng loạt. selected.textbook / selected.lesson: id → true. Mục đã lưu trữ không chọn được
   * (endpoint hàng loạt từ chối, phải khôi phục từng mục). filter: ALL / PUBLISHED / DRAFT.
   */
  var bulk = { selected: { textbook: {}, lesson: {} }, filter: 'ALL', busy: false, result: null };
  var BULK_CHUNK = 500;
  var statusFilterEl = document.getElementById('statusFilter');
  var selectAllEl = document.getElementById('selectAllVisible');
  var selectAllLabelEl = document.getElementById('selectAllLabel');
  var bulkBarEl = document.getElementById('bulkBar');

  /*
   * [JLAS2-17] Tìm kiếm + bộ lọc. q: chuỗi đã chuẩn hoá (thường, bỏ dấu); levels: [] = tất cả, 'NONE' = không theo cấp độ;
   * topic: mã tag SITUATION ('' = tất cả, '__none' = không gắn chủ đề); source: ALL / TEAM / VNJP / MINNA.
   * Nguồn suy theo dải id seed (vnjpclub 1.000.001–1.999.999, Minna 3.000.001–3.999.999, còn lại = nhóm biên soạn).
   * Chủ đề của giáo trình = các tag SITUATION gắn vào bài của nó (topicCodes trong GET /manage/textbooks), cùng nguồn
   * với tab "Theo chủ đề" của học viên. Tìm theo tên phần / bài cần cây giáo trình → tải ngầm các cây chưa có
   * (API sẵn có, 4 yêu cầu song song) khi bắt đầu tìm.
   */
  var LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
  var LEVEL_CHIPS = [{ value: 'ALL', label: 'Tất cả' }].concat(LEVELS.map(function (l) { return { value: l, label: l }; }))
    .concat([{ value: 'NONE', label: 'Không theo cấp độ' }]);
  var SOURCE_CHIPS = [{ value: 'ALL', label: 'Tất cả' }, { value: 'TEAM', label: 'Nhóm biên soạn' },
    { value: 'VNJP', label: 'vnjpclub' }, { value: 'MINNA', label: 'Minna' }];
  var filters = { qRaw: '', q: '', levels: [], topic: '', source: 'ALL', sort: 'level' };
  var trees = { loading: false, done: 0, total: 0, all: false };
  var topicNames = {};
  var searchEl = document.getElementById('manageSearch');
  var searchClearEl = document.getElementById('manageSearchClear');
  var levelFilterEl = document.getElementById('levelFilter');
  var sourceFilterEl = document.getElementById('sourceFilter');
  var topicFilterEl = document.getElementById('topicFilter');
  var sortSelectEl = document.getElementById('sortSelect');
  var summaryEl = document.getElementById('filterSummary');
  var clearFiltersEl = document.getElementById('clearFilters');
  var filtersPanelEl = document.getElementById('manageFilters');
  var filtersToggleEl = document.getElementById('manageFiltersToggle');
  var filtersBackdropEl = document.getElementById('manageFiltersBackdrop');
  var filtersCountEl = document.getElementById('manageFiltersCount');

  /** Thường + bỏ dấu tiếng Việt (đ → d) để "giao trinh" khớp "Giáo trình". */
  function norm(value) {
    return String(value == null ? '' : value).normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().trim();
  }

  function sourceOf(textbookId) {
    var n = Number(textbookId);
    if (n >= 1000001 && n <= 1999999) return 'VNJP';
    if (n >= 3000001 && n <= 3999999) return 'MINNA';
    return 'TEAM';
  }

  /** Lọc theo thuộc tính của chính giáo trình (cấp độ, chủ đề, nguồn) — chưa xét tìm kiếm / trạng thái. */
  function passesAttrFilters(t) {
    if (filters.levels.length) {
      var lv = t.jlptLevel ? String(t.jlptLevel).toUpperCase() : 'NONE';
      if (filters.levels.indexOf(lv) < 0) return false;
    }
    if (filters.topic) {
      var codes = t.topicCodes || [];
      if (filters.topic === '__none' ? codes.length > 0 : codes.indexOf(filters.topic) < 0) return false;
    }
    return filters.source === 'ALL' || sourceOf(t.id) === filters.source;
  }

  /**
   * Kết quả tìm của một giáo trình: null = không tìm / khớp tên giáo trình (hiện cả cây); object = chỉ khớp ở phần / bài
   * ({ units, lessonUnits, lessons, unitCount, lessonCount }); undefined = không khớp (hoặc cây chưa tải xong).
   */
  function searchHit(t) {
    if (!filters.q || norm(t.title).indexOf(filters.q) >= 0) return null;
    var tree = state.trees[t.id];
    if (!tree) return undefined;
    var hit = { units: {}, lessonUnits: {}, lessons: {}, unitCount: 0, lessonCount: 0 };
    (tree.units || []).forEach(function (u) {
      if (norm(u.title).indexOf(filters.q) >= 0) { hit.units[u.id] = true; hit.unitCount++; }
      (u.lessons || []).forEach(function (l) {
        if (norm(l.title).indexOf(filters.q) >= 0) { hit.lessons[l.id] = true; hit.lessonUnits[u.id] = true; hit.lessonCount++; }
      });
    });
    return hit.unitCount || hit.lessonCount ? hit : undefined;
  }

  function hitOfUnit(unitId) {
    var found = null;
    state.textbooks.forEach(function (t) {
      ((state.trees[t.id] && state.trees[t.id].units) || []).forEach(function (u) { if (String(u.id) === String(unitId)) found = t; });
    });
    return found ? (searchHit(found) || null) : null;
  }

  function unitInSearch(hit, unit) { return !hit || !!hit.units[unit.id] || !!hit.lessonUnits[unit.id]; }
  function lessonInSearch(hit, unit, lesson) { return !hit || !!hit.units[unit.id] || !!hit.lessons[lesson.id]; }

  var LEVEL_ORDER = { N5: 0, N4: 1, N3: 2, N2: 3, N1: 4 };
  function compareTextbooks(a, b) {
    if (filters.sort === 'name') return String(a.title).localeCompare(String(b.title), 'vi', { sensitivity: 'base' });
    if (filters.sort === 'updated') {
      var ta = a.updatedAt ? Date.parse(a.updatedAt) : 0;
      var tb = b.updatedAt ? Date.parse(b.updatedAt) : 0;
      return tb - ta || b.id - a.id;
    }
    var la = a.jlptLevel && LEVEL_ORDER[a.jlptLevel] != null ? LEVEL_ORDER[a.jlptLevel] : 5;
    var lb = b.jlptLevel && LEVEL_ORDER[b.jlptLevel] != null ? LEVEL_ORDER[b.jlptLevel] : 5;
    return la - lb || a.id - b.id;
  }

  function activeFilterCount() {
    return (filters.q ? 1 : 0) + (filters.levels.length ? 1 : 0) + (filters.topic ? 1 : 0) + (filters.source !== 'ALL' ? 1 : 0)
      + (bulk.filter !== 'ALL' ? 1 : 0) + (showArchived() ? 1 : 0);
  }

  function chipsHtml(chips, isOn) {
    return chips.map(function (c) {
      var on = isOn(c.value);
      return '<button type="button" class="manage-chip' + (on ? ' is-on' : '') + '" data-value="' + c.value + '" aria-pressed="' + on + '">'
        + escapeHtml(c.label) + '</button>';
    }).join('');
  }

  function renderFilterControls() {
    if (levelFilterEl) levelFilterEl.innerHTML = chipsHtml(LEVEL_CHIPS, function (v) {
      return v === 'ALL' ? !filters.levels.length : filters.levels.indexOf(v) >= 0;
    });
    if (sourceFilterEl) sourceFilterEl.innerHTML = chipsHtml(SOURCE_CHIPS, function (v) { return filters.source === v; });
    if (searchClearEl) searchClearEl.hidden = !filters.qRaw;
    var n = activeFilterCount();
    if (filtersCountEl) { filtersCountEl.hidden = !n; filtersCountEl.textContent = n; }
  }

  function renderTopicOptions() {
    if (!topicFilterEl) return;
    var used = {};
    state.textbooks.forEach(function (t) { (t.topicCodes || []).forEach(function (c) { used[c] = true; }); });
    var codes = Object.keys(used).sort(function (a, b) { return String(topicNames[a] || a).localeCompare(String(topicNames[b] || b), 'vi'); });
    if (filters.topic && filters.topic !== '__none' && !used[filters.topic]) codes.push(filters.topic);
    topicFilterEl.innerHTML = '<option value="">Tất cả</option>'
      + codes.map(function (c) { return '<option value="' + escapeHtml(c) + '">' + escapeHtml(topicNames[c] || c) + '</option>'; }).join('')
      + '<option value="__none">Không gắn chủ đề</option>';
    topicFilterEl.value = filters.topic;
  }

  function writeQuery() {
    var params = new URLSearchParams();
    if (filters.qRaw) params.set('q', filters.qRaw);
    if (filters.levels.length) params.set('level', filters.levels.join(','));
    if (filters.topic) params.set('topic', filters.topic);
    if (filters.source !== 'ALL') params.set('source', filters.source);
    if (bulk.filter !== 'ALL') params.set('status', bulk.filter);
    if (showArchived()) params.set('archived', '1');
    if (filters.sort !== 'level') params.set('sort', filters.sort);
    var qs = params.toString();
    try { history.replaceState(null, '', location.pathname + (qs ? '?' + qs : '')); } catch (e) { /* bỏ qua */ }
  }

  function readQuery() {
    var params = new URLSearchParams(location.search);
    filters.qRaw = params.get('q') || '';
    filters.q = norm(filters.qRaw);
    filters.levels = (params.get('level') || '').split(',').filter(function (v) { return v === 'NONE' || LEVELS.indexOf(v) >= 0; });
    filters.topic = params.get('topic') || '';
    var src = params.get('source');
    filters.source = SOURCE_CHIPS.some(function (c) { return c.value === src; }) ? src : 'ALL';
    var st = params.get('status');
    bulk.filter = st === 'PUBLISHED' || st === 'DRAFT' ? st : 'ALL';
    var sort = params.get('sort');
    filters.sort = sort === 'name' || sort === 'updated' ? sort : 'level';
    if (searchEl) searchEl.value = filters.qRaw;
    if (statusFilterEl) statusFilterEl.value = bulk.filter;
    if (sortSelectEl) sortSelectEl.value = filters.sort;
    if (showArchivedEl) showArchivedEl.checked = params.get('archived') === '1';
  }

  /** Bộ lọc đổi: vẽ lại + ghi query string. */
  function applyFilters() {
    renderFilterControls();
    writeQuery();
    if (filters.q) ensureAllTrees();
    render();
  }

  /** Tải ngầm cây của mọi giáo trình chưa có (để tìm theo tên phần / bài); vẽ lại theo từng đợt, không theo từng cây. */
  function ensureAllTrees() {
    if (trees.loading || trees.all) return;
    var missing = state.textbooks.filter(function (t) { return !state.trees[t.id]; }).map(function (t) { return t.id; });
    if (!missing.length) { trees.all = true; return; }
    trees.loading = true;
    trees.done = 0;
    trees.total = missing.length;
    var queue = missing.slice();
    var lastPaint = 0;
    function worker() {
      var id = queue.shift();
      if (id == null) return Promise.resolve();
      return request('/manage/textbooks/' + encodeURIComponent(id))
        .then(function (data) { if (!state.trees[id]) state.trees[id] = data; })
        .catch(function () { /* giáo trình lỗi: chỉ tìm được theo tên giáo trình */ })
        .then(function () {
          trees.done++;
          if (filters.q && Date.now() - lastPaint > 400) { lastPaint = Date.now(); render(); }
          return worker();
        });
    }
    Promise.all([worker(), worker(), worker(), worker()]).then(function () {
      trees.loading = false;
      trees.all = true;
      render();
    });
  }

  function renderSummary(visibleCount) {
    if (!summaryEl) return;
    var total = state.textbooks.filter(function (t) { return showArchived() || !isArchived(t); }).length;
    var text = 'Hiển thị ' + visibleCount + ' / ' + total + ' giáo trình';
    if (filters.q && trees.loading) text += ' · đang tải tên phần / bài để tìm (' + trees.done + '/' + trees.total + ')…';
    summaryEl.textContent = text;
    if (clearFiltersEl) clearFiltersEl.hidden = activeFilterCount() === 0;
  }

  function openFilters(open) {
    if (!filtersPanelEl) return;
    filtersPanelEl.classList.toggle('is-open', open);
    if (filtersBackdropEl) filtersBackdropEl.hidden = !open;
    if (filtersToggleEl) filtersToggleEl.setAttribute('aria-expanded', open ? 'true' : 'false');
    document.body.classList.toggle('manage-filters-open', open);
    if (open) {
      var first = filtersPanelEl.querySelector('button, select, input');
      if (first) first.focus();
    } else if (filtersToggleEl && filtersToggleEl.offsetParent) {
      filtersToggleEl.focus();
    }
  }

  function setupFilters() {
    readQuery();
    renderFilterControls();
    var timer = null;
    if (searchEl) searchEl.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () {
        filters.qRaw = searchEl.value;
        filters.q = norm(searchEl.value);
        applyFilters();
      }, 200);
    });
    if (searchClearEl) searchClearEl.addEventListener('click', function () {
      searchEl.value = '';
      filters.qRaw = '';
      filters.q = '';
      applyFilters();
      searchEl.focus();
    });
    if (levelFilterEl) levelFilterEl.addEventListener('click', function (event) {
      var chip = event.target.closest('.manage-chip');
      if (!chip) return;
      var v = chip.getAttribute('data-value');
      if (v === 'ALL') filters.levels = [];
      else if (filters.levels.indexOf(v) >= 0) filters.levels = filters.levels.filter(function (x) { return x !== v; });
      else filters.levels = filters.levels.concat([v]);
      applyFilters();
    });
    if (sourceFilterEl) sourceFilterEl.addEventListener('click', function (event) {
      var chip = event.target.closest('.manage-chip');
      if (!chip) return;
      filters.source = chip.getAttribute('data-value');
      applyFilters();
    });
    if (topicFilterEl) topicFilterEl.addEventListener('change', function () { filters.topic = topicFilterEl.value; applyFilters(); });
    if (sortSelectEl) sortSelectEl.addEventListener('change', function () { filters.sort = sortSelectEl.value; applyFilters(); });
    if (clearFiltersEl) clearFiltersEl.addEventListener('click', function () {
      filters = { qRaw: '', q: '', levels: [], topic: '', source: 'ALL', sort: filters.sort };
      bulk.filter = 'ALL';
      if (searchEl) searchEl.value = '';
      if (statusFilterEl) statusFilterEl.value = 'ALL';
      if (showArchivedEl) showArchivedEl.checked = false;
      if (topicFilterEl) topicFilterEl.value = '';
      applyFilters();
    });
    if (filtersToggleEl) filtersToggleEl.addEventListener('click', function () { openFilters(!filtersPanelEl.classList.contains('is-open')); });
    ['manageFiltersClose', 'manageFiltersDone'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.addEventListener('click', function () { openFilters(false); });
    });
    if (filtersBackdropEl) filtersBackdropEl.addEventListener('click', function () { openFilters(false); });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && filtersPanelEl && filtersPanelEl.classList.contains('is-open')) openFilters(false);
    });
    // Tên chủ đề: API công khai sẵn có (tag SITUATION); lỗi thì hiện mã.
    fetch(API + '/topics').then(function (res) { return res.ok ? res.json() : null; }).then(function (json) {
      ((json && json.data) || []).forEach(function (t) { topicNames[t.code] = t.name; });
      renderTopicOptions();
    }).catch(function () { renderTopicOptions(); });
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toast(message, kind) {
    if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(message, kind || 'info');
  }

  function showError(message) {
    loadingEl.hidden = true;
    errorEl.textContent = message;
    errorEl.classList.add('is-visible');
    toast(message, 'error');
  }

  function clearError() {
    errorEl.textContent = '';
    errorEl.classList.remove('is-visible');
  }

  /**
   * Lỗi cần đọc kỹ rồi mới hành động được (ví dụ 409 "phần còn N bài học"): hiện ở dải cố định đầu
   * danh sách và nằm lại cho tới khi người dùng thao tác tiếp, KHÔNG dùng toast vì toast tự xoá sau
   * 4,5 giây. Cố ý không gọi toast song song để không báo hai lần cùng một chuyện.
   */
  function showStickyError(message) {
    errorEl.textContent = message;
    errorEl.classList.add('is-visible');
    if (typeof errorEl.scrollIntoView === 'function') {
      errorEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }

  // ── Gọi API ────────────────────────────────────────────────────────────────

  /**
   * Mọi lỗi đều ném Error mang đúng `message` do server trả về — nhất là 409 khi xoá phần còn bài:
   * người dùng cần đọc được lý do để biết phải xử lý bài trước, không nuốt thành lỗi chung chung.
   */
  function request(path, options) {
    var opts = options || {};
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function'
      ? JlasAuth.authHeaders() : {};
    // authHeaders() luôn kèm Content-Type JSON; với FormData phải bỏ để trình duyệt tự đặt multipart + boundary.
    if (typeof FormData !== 'undefined' && opts.body instanceof FormData) delete headers['Content-Type'];
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

  function showArchived() {
    return !!(showArchivedEl && showArchivedEl.checked);
  }

  function isArchived(item) {
    return item && item.status === 'ARCHIVED';
  }

  function statusBadge(status) {
    var cls = status === 'PUBLISHED' ? 'is-published' : (status === 'ARCHIVED' ? 'is-archived' : 'is-draft');
    return '<span class="manage-status ' + cls + '">' + escapeHtml(STATUS_LABELS[status] || status) + '</span>';
  }

  /**
   * @param disabledReason nếu có: nút bị tắt và tooltip đổi thành lý do. Dùng cho nội dung nằm trong
   *        giáo trình đã lưu trữ — backend đã chặn bằng 409, tắt ở đây để người dùng biết trước khi bấm.
   */
  function iconBtn(action, kind, id, icon, label, extraClass, disabledReason) {
    var title = disabledReason || label;
    return '<button type="button" class="manage-icon-btn' + (extraClass ? ' ' + extraClass : '') + '"'
      + ' data-action="' + action + '" data-kind="' + kind + '" data-id="' + id + '"'
      + (disabledReason ? ' disabled' : '')
      + ' title="' + escapeHtml(title) + '" aria-label="' + escapeHtml(title) + '">'
      + '<i class="fa-solid ' + icon + '" aria-hidden="true"></i></button>';
  }

  var ARCHIVED_PARENT_REASON = 'Giáo trình đang lưu trữ — khôi phục giáo trình để sửa nội dung bên trong';

  /**
   * [JLAS2-17] Nút "Xem trước" = link sang trang học viên (tab mới). Tài khoản biên soạn xem được cả mục Nháp
   * (DictionaryServiceImpl.canViewDraft), nên chỉ cần ghi chú trong tooltip. Giáo trình đã lưu trữ không hiện ở
   * trang học viên (404 kể cả với người biên soạn) → nút bị khoá kèm lý do.
   */
  function previewLink(href, hiddenNote, disabledReason) {
    var title = disabledReason || ('Xem như học viên' + (hiddenNote ? ' — ' + hiddenNote : '') + ' (mở tab mới)');
    if (disabledReason) {
      return '<span class="manage-icon-btn manage-preview-btn is-disabled" role="link" aria-disabled="true"'
        + ' title="' + escapeHtml(title) + '" aria-label="' + escapeHtml(title) + '">'
        + '<i class="fa-solid fa-arrow-up-right-from-square" aria-hidden="true"></i></span>';
    }
    return '<a class="manage-icon-btn manage-preview-btn' + (hiddenNote ? ' is-hidden-from-learner' : '') + '"'
      + ' href="' + escapeHtml(href) + '" target="_blank" rel="noopener" draggable="false"'
      + ' title="' + escapeHtml(title) + '" aria-label="' + escapeHtml(title) + '">'
      + '<i class="fa-solid fa-arrow-up-right-from-square" aria-hidden="true"></i></a>';
  }

  /** Ghi chú "học viên chưa thấy" cho bài: chính bài hoặc giáo trình cha chưa công bố. */
  function lessonHiddenNote(lesson, textbook) {
    if (lesson.status === 'ARCHIVED') return 'Học viên không thấy bài này (Đã lưu trữ)';
    if (lesson.status !== 'PUBLISHED') return 'Học viên chưa thấy bài này (Nháp)';
    if (textbook && textbook.status !== 'PUBLISHED') return 'Học viên chưa thấy bài này (giáo trình đang Nháp)';
    return '';
  }

  /**
   * Nút trạng thái. Mục đã lưu trữ chỉ có một lối ra là Khôi phục (về nháp).
   * KHÔNG có nút xoá cứng cho giáo trình và bài học ở bất kỳ đâu: FK đều ON DELETE CASCADE và
   * lesson_progress cascade theo lesson, nên xoá cứng sẽ cuốn cả tiến độ học viên.
   */
  function statusButtons(kind, item, lockReason) {
    if (isArchived(item)) {
      return iconBtn('restore', kind, item.id, 'fa-rotate-left', 'Khôi phục về nháp',
        'manage-icon-btn--restore', lockReason);
    }
    var published = item.status === 'PUBLISHED';
    // fa-inbox (khay) chứ KHÔNG phải fa-box-archive: fa-box-archive là hộp có nắp, ở cỡ 30px trông gần
    // như hệt fa-trash của nút xoá phần, mà hai hành động này một cái khôi phục được, một cái vĩnh viễn.
    return iconBtn(published ? 'unpublish' : 'publish', kind, item.id,
        published ? 'fa-eye' : 'fa-eye-slash',
        published ? 'Đang công bố — bấm để rút về nháp' : 'Đang nháp — bấm để công bố', '', lockReason)
      + iconBtn('archive', kind, item.id, 'fa-inbox',
        'Lưu trữ — ẩn khỏi học viên, khôi phục được', '', lockReason);
  }

  /** Lọc theo trạng thái (Tất cả / Đã công bố / Nháp); mục đã lưu trữ vẫn do ô "Hiện mục đã lưu trữ" quyết. */
  function matchesFilter(item) {
    return bulk.filter === 'ALL' || item.status === bulk.filter;
  }

  function lessonVisible(lesson, hit, unit) {
    return (showArchived() || !isArchived(lesson)) && matchesFilter(lesson) && (!hit || lessonInSearch(hit, unit, lesson));
  }

  function selectBox(kind, item, label) {
    var archived = isArchived(item);
    return '<input type="checkbox" class="manage-check" data-select="' + kind + '" data-id="' + item.id + '"'
      + (bulk.selected[kind][item.id] ? ' checked' : '') + (archived || bulk.busy ? ' disabled' : '')
      + ' title="' + escapeHtml(archived ? 'Mục đã lưu trữ — khôi phục trước khi công bố / ẩn hàng loạt' : 'Chọn ' + label) + '"'
      + ' aria-label="' + escapeHtml('Chọn ' + label) + '">';
  }

  /** Nút chữ nhỏ "Công bố tất cả bài" / "Ẩn tất cả bài" cho một phần hoặc cả giáo trình. */
  function bulkScopeButtons(scope, id, disabledReason) {
    var dis = disabledReason || bulk.busy ? ' disabled' : '';
    var title = disabledReason ? ' title="' + escapeHtml(disabledReason) + '"' : '';
    return '<span class="manage-bulk-scope">'
      + '<button type="button" class="manage-chip-btn" data-bulk-scope="' + scope + '" data-bulk-action="PUBLISH" data-id="' + id + '"' + dis + title + '>'
      + '<i class="fa-solid fa-eye" aria-hidden="true"></i> Công bố tất cả bài</button>'
      + '<button type="button" class="manage-chip-btn" data-bulk-scope="' + scope + '" data-bulk-action="UNPUBLISH" data-id="' + id + '"' + dis + title + '>'
      + '<i class="fa-solid fa-eye-slash" aria-hidden="true"></i> Ẩn tất cả bài</button></span>';
  }

  function lessonRow(lesson, lockReason, textbook) {
    var meta = [LESSON_TYPE_LABELS[lesson.lessonType] || lesson.lessonType,
      SKILL_LABELS[lesson.skill] || lesson.skill];
    if (lesson.estimatedMinutes) meta.push(lesson.estimatedMinutes + ' phút');
    return '<li class="manage-row manage-row--lesson' + (isArchived(lesson) ? ' is-archived-row' : '') + '"'
      + ' draggable="' + (lockReason ? 'false' : 'true') + '" data-sort-kind="lesson" data-id="' + lesson.id + '">'
      + selectBox('lesson', lesson, 'bài ' + lesson.title)
      + '<span class="manage-grip" aria-hidden="true"><i class="fa-solid fa-grip-vertical"></i></span>'
      + '<span class="manage-row__order">' + escapeHtml(lesson.orderIndex) + '</span>'
      + '<span class="manage-row__main">'
      + '<span class="manage-row__title">' + escapeHtml(lesson.title) + '</span>'
      + '<span class="manage-row__meta">' + escapeHtml(meta.join(' · ')) + '</span>'
      + '</span>'
      + statusBadge(lesson.status)
      + '<span class="manage-row__actions">'
      + iconBtn('move-up', 'lesson', lesson.id, 'fa-arrow-up', 'Lên trên', '', lockReason)
      + iconBtn('move-down', 'lesson', lesson.id, 'fa-arrow-down', 'Xuống dưới', '', lockReason)
      + iconBtn('edit', 'lesson', lesson.id, 'fa-pen', 'Sửa bài học', '', lockReason)
      // Biên soạn nội dung bên trong bài (ngữ pháp, từ vựng) nằm ở trang riêng: màn này lo cấu trúc và
      // trạng thái, còn soạn thảo cần trường văn bản dài nên không nhét vừa modal ở đây.
      + iconBtn('open-editor', 'lesson', lesson.id, 'fa-list-check', 'Biên soạn nội dung bài học')
      + (textbook ? previewLink('/textbooks/' + textbook.id + '/lessons/' + lesson.id, lessonHiddenNote(lesson, textbook)) : '')
      + statusButtons('lesson', lesson, lockReason)
      // Xoá cứng chỉ dành cho bài nháp tạo nhầm; server chặn bằng 409 nếu bài đã có nội dung, có người
      // học hoặc gắn quiz. Cùng kiểu trình bày với nút xoá phần: vạch ngăn + màu cảnh báo thường trực.
      + '<span class="manage-actions-sep" aria-hidden="true"></span>'
      + iconBtn('delete', 'lesson', lesson.id, 'fa-trash',
        'Xoá vĩnh viễn — chỉ được với bài nháp chưa có nội dung và chưa ai học', 'manage-icon-btn--danger')
      + '</span></li>';
  }

  function unitBlock(unit, lockReason, hit, textbook) {
    var shown = (unit.lessons || []).filter(function (l) { return showArchived() || !isArchived(l); });
    var hiddenCount = (unit.lessons || []).length - shown.length;
    var lessons = shown.filter(function (l) { return matchesFilter(l) && lessonInSearch(hit, unit, l); });
    var selectable = lessons.filter(function (l) { return !isArchived(l); });
    var picked = selectable.filter(function (l) { return bulk.selected.lesson[l.id]; }).length;
    return '<li class="manage-unit" data-sort-kind="unit" data-id="' + unit.id + '"'
      + ' draggable="' + (lockReason ? 'false' : 'true') + '">'
      + '<div class="manage-row manage-row--unit">'
      // Ô chọn của phần = chọn / bỏ chọn mọi bài đang hiện của phần (trạng thái lửng khi chọn một phần).
      + '<input type="checkbox" class="manage-check" data-select="unit" data-id="' + unit.id + '"'
      + (selectable.length && picked === selectable.length ? ' checked' : '')
      + (picked > 0 && picked < selectable.length ? ' data-indeterminate="1"' : '')
      + (!selectable.length || bulk.busy ? ' disabled' : '')
      + ' aria-label="' + escapeHtml('Chọn mọi bài đang hiện của ' + unit.title) + '">'
      + '<span class="manage-grip" aria-hidden="true"><i class="fa-solid fa-grip-vertical"></i></span>'
      + '<span class="manage-row__order">' + escapeHtml(unit.orderIndex) + '</span>'
      + '<span class="manage-row__main">'
      + '<span class="manage-row__title">' + escapeHtml(unit.title) + '</span>'
      + '<span class="manage-row__meta">' + shown.length + ' bài'
      + (hiddenCount > 0 ? ' · ' + hiddenCount + ' đã lưu trữ đang ẩn' : '')
      + (bulk.filter !== 'ALL' || hit ? ' · ' + lessons.length + ' khớp bộ lọc' : '') + '</span>'
      + '</span>'
      + bulkScopeButtons('unit', unit.id, lockReason)
      + '<span class="manage-row__actions">'
      + iconBtn('move-up', 'unit', unit.id, 'fa-arrow-up', 'Lên trên', '', lockReason)
      + iconBtn('move-down', 'unit', unit.id, 'fa-arrow-down', 'Xuống dưới', '', lockReason)
      + iconBtn('add-lesson', 'unit', unit.id, 'fa-plus', 'Thêm bài học', '', lockReason)
      + iconBtn('edit', 'unit', unit.id, 'fa-pen', 'Sửa phần', '', lockReason)
      // Vạch ngăn + màu cảnh báo thường trực: đây là hành động vĩnh viễn duy nhất của cả màn hình.
      // Vẫn bật khi giáo trình đã lưu trữ (không truyền lockReason): dọn phần rỗng là việc lành.
      + '<span class="manage-actions-sep" aria-hidden="true"></span>'
      + iconBtn('delete', 'unit', unit.id, 'fa-trash',
        'Xoá vĩnh viễn — không hoàn tác được', 'manage-icon-btn--danger')
      + '</span></div>'
      + '<ul class="manage-lessons" data-sort-group="lesson" data-unit-id="' + unit.id + '">'
      + (lessons.length
        ? lessons.map(function (l) { return lessonRow(l, lockReason, textbook); }).join('')
        : '<li class="manage-empty-hint">' + (shown.length ? 'Không có bài nào khớp bộ lọc.' : 'Phần này chưa có bài học nào.') + '</li>')
      + '</ul></li>';
  }

  function textbookBlock(textbook) {
    var open = !!state.expanded[textbook.id];
    var hit = searchHit(textbook) || null;
    var tree = state.trees[textbook.id];
    var level = textbook.jlptLevel ? escapeHtml(textbook.jlptLevel) : 'Không theo cấp độ';
    // Giáo trình đã lưu trữ: khoá mọi thao tác thêm/sửa/đổi thứ tự bên trong (backend cũng chặn bằng 409).
    var lockReason = isArchived(textbook) ? ARCHIVED_PARENT_REASON : '';
    var body = '';
    if (open) {
      if (!tree) {
        body = '<div class="manage-tree__loading"><i class="fa-solid fa-circle-notch fa-spin" aria-hidden="true"></i> Đang tải…</div>';
      } else {
        var units = tree.units || [];
        body = (units.length
          ? '<div class="manage-book-bulk"><span class="manage-book-bulk__label">Cả giáo trình:</span>'
            + bulkScopeButtons('textbook', textbook.id, lockReason) + '</div>'
          : '')
          + '<ul class="manage-units" data-sort-group="unit" data-textbook-id="' + textbook.id + '">'
          + (units.length
            ? units.filter(function (u) { return unitInSearch(hit, u); }).map(function (u) { return unitBlock(u, lockReason, hit, textbook); }).join('')
            : '<li class="manage-empty-hint">Giáo trình này chưa có phần nào.</li>')
          + '</ul>';
      }
    }
    return '<section class="manage-book' + (isArchived(textbook) ? ' is-archived-row' : '') + '" data-id="' + textbook.id + '">'
      + '<div class="manage-row manage-row--book">'
      + selectBox('textbook', textbook, 'giáo trình ' + textbook.title)
      + '<button type="button" class="manage-toggle" data-action="toggle" data-kind="textbook" data-id="' + textbook.id + '"'
      + ' aria-expanded="' + (open ? 'true' : 'false') + '">'
      + '<i class="fa-solid ' + (open ? 'fa-chevron-down' : 'fa-chevron-right') + '" aria-hidden="true"></i></button>'
      + '<span class="manage-row__main">'
      + '<span class="manage-row__title">' + escapeHtml(textbook.title) + '</span>'
      + '<span class="manage-row__meta">' + level + ' · ' + (textbook.unitCount || 0) + ' phần'
      + (hit ? ' · <mark class="manage-hit">khớp ' + [hit.unitCount ? hit.unitCount + ' phần' : '', hit.lessonCount ? hit.lessonCount + ' bài' : '']
        .filter(Boolean).join(', ') + '</mark>' : '') + '</span>'
      + '</span>'
      + statusBadge(textbook.status)
      + '<span class="manage-row__actions">'
      + iconBtn('add-unit', 'textbook', textbook.id, 'fa-plus', 'Thêm phần', '', lockReason)
      + iconBtn('edit', 'textbook', textbook.id, 'fa-pen', 'Sửa giáo trình', '', lockReason)
      + previewLink('/textbooks/' + textbook.id,
        textbook.status === 'PUBLISHED' ? '' : 'Học viên chưa thấy giáo trình này (Nháp)',
        isArchived(textbook) ? 'Giáo trình đã lưu trữ — không hiện ở trang học viên, khôi phục để xem trước' : '')
      // Nút trạng thái của CHÍNH giáo trình không khoá: đó là lối khôi phục duy nhất.
      + statusButtons('textbook', textbook)
      + '</span></div>'
      + body + '</section>';
  }

  /** Giáo trình hiện khi khớp bộ lọc, hoặc đang mở (để vẫn thấy các bài khớp bộ lọc bên trong). */
  function visibleTextbooks() {
    return state.textbooks.filter(function (t) {
      return (showArchived() || !isArchived(t)) && (matchesFilter(t) || state.expanded[t.id])
        && passesAttrFilters(t) && searchHit(t) !== undefined;
    }).sort(compareTextbooks);
  }

  function render() {
    loadingEl.hidden = true;
    var visible = visibleTextbooks();
    renderSummary(visible.length);
    if (!visible.length && state.textbooks.length) {   // có dữ liệu nhưng bộ lọc / ô lưu trữ ẩn hết
      emptyEl.hidden = true;
      treeEl.hidden = false;
      treeEl.innerHTML = '<p class="manage-empty-hint">Không có giáo trình nào khớp bộ lọc.</p>';
      renderBulkBar();
      return;
    }
    emptyEl.hidden = visible.length > 0;
    treeEl.hidden = visible.length === 0;
    treeEl.innerHTML = visible.map(textbookBlock).join('');
    Array.prototype.forEach.call(treeEl.querySelectorAll('[data-indeterminate]'), function (el) { el.indeterminate = true; });
    renderBulkBar();
  }

  // ── Tải dữ liệu ────────────────────────────────────────────────────────────

  function loadTextbooks() {
    return request('/manage/textbooks').then(function (data) {
      state.textbooks = data || [];
      trees.all = false;
      clearError();
      renderTopicOptions();
      if (filters.q) ensureAllTrees();
      render();
    });
  }

  function loadTree(textbookId) {
    return request('/manage/textbooks/' + encodeURIComponent(textbookId)).then(function (data) {
      state.trees[textbookId] = data;
      render();
    });
  }

  /** Tải lại cả danh sách và những cây đang mở, để số phần/bài và thứ tự luôn khớp DB. */
  function reloadAll() {
    var openIds = Object.keys(state.expanded).filter(function (id) { return state.expanded[id]; });
    return loadTextbooks().then(function () {
      return Promise.all(openIds.map(function (id) {
        return request('/manage/textbooks/' + encodeURIComponent(id))
          .then(function (data) { state.trees[id] = data; })
          .catch(function () { delete state.trees[id]; });
      }));
    }).then(render);
  }

  // ── Trạng thái duyệt ───────────────────────────────────────────────────────

  var STATUS_ACTIONS = {
    publish: { status: 'PUBLISHED', done: 'Đã công bố' },
    unpublish: { status: 'DRAFT', done: 'Đã rút về nháp' },
    archive: { status: 'ARCHIVED', done: 'Đã lưu trữ' },
    restore: { status: 'DRAFT', done: 'Đã khôi phục về nháp' }
  };

  function changeStatus(action, kind, id) {
    var rule = STATUS_ACTIONS[action];
    var label = kind === 'textbook' ? 'giáo trình' : 'bài học';
    if (action === 'archive'
      && !window.confirm('Lưu trữ ' + label + ' này? Học viên sẽ không thấy nữa, nhưng dữ liệu và tiến độ đã học vẫn được giữ.')) {
      return;
    }
    if (action === 'unpublish'
      && !window.confirm('Rút ' + label + ' này về nháp? Học viên sẽ không xem được nữa.')) {
      return;
    }
    request('/' + (kind === 'textbook' ? 'textbooks' : 'lessons') + '/' + encodeURIComponent(id) + '/status',
      { method: 'PATCH', body: JSON.stringify({ status: rule.status }) })
      .then(function () {
        toast(rule.done + ' ' + label + '.', 'success');
        return reloadAll();
      })
      .catch(function (err) { toast(err.message || 'Không đổi được trạng thái.', 'error'); });
  }

  // ── Công bố / ẩn hàng loạt ─────────────────────────────────────────────────

  var KIND_LABELS = { textbook: 'giáo trình', lesson: 'bài học' };
  var BULK_ACTIONS = {
    PUBLISH: { status: 'PUBLISHED', verb: 'Công bố', effect: 'Học viên sẽ thấy các nội dung này.' },
    UNPUBLISH: { status: 'DRAFT', verb: 'Ẩn (về Nháp)', effect: 'Học viên sẽ không còn thấy các nội dung này.' }
  };

  /** Giáo trình lấy từ nguồn ngoài theo dải id seed: vnjpclub 1.000.001–1.999.999, Minna 3.000.001–3.999.999. */
  function isExternalTextbook(id) {
    var n = Number(id);
    return (n >= 1000001 && n <= 1999999) || (n >= 3000001 && n <= 3999999);
  }

  function textbookIdOfLesson(lessonId) {
    var found = null;
    Object.keys(state.trees).forEach(function (bookId) {
      ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
        (u.lessons || []).forEach(function (l) { if (String(l.id) === String(lessonId)) found = bookId; });
      });
    });
    return found;
  }

  function selectedIds(kind) {
    return Object.keys(bulk.selected[kind]).filter(function (id) { return bulk.selected[kind][id]; }).map(Number);
  }

  /** Mọi mục đang hiện, khớp bộ lọc và chọn được (không lưu trữ): giáo trình + bài của các giáo trình đang mở. */
  function selectableVisible() {
    var out = { textbook: [], lesson: [] };
    visibleTextbooks().forEach(function (t) {
      if (matchesFilter(t) && !isArchived(t)) out.textbook.push(t.id);
      var tree = state.expanded[t.id] && state.trees[t.id];
      var hit = searchHit(t) || null;
      ((tree && tree.units) || []).forEach(function (u) {
        if (!unitInSearch(hit, u)) return;
        (u.lessons || []).forEach(function (l) { if (lessonVisible(l, hit, u) && !isArchived(l)) out.lesson.push(l.id); });
      });
    });
    return out;
  }

  function renderBulkBar() {
    var nBooks = selectedIds('textbook').length;
    var nLessons = selectedIds('lesson').length;
    var total = nBooks + nLessons;
    var pool = selectableVisible();
    var poolSize = pool.textbook.length + pool.lesson.length;
    var pickedInPool = pool.textbook.filter(function (id) { return bulk.selected.textbook[id]; }).length
      + pool.lesson.filter(function (id) { return bulk.selected.lesson[id]; }).length;
    if (selectAllEl) {
      selectAllEl.checked = poolSize > 0 && pickedInPool === poolSize;
      selectAllEl.indeterminate = pickedInPool > 0 && pickedInPool < poolSize;
      selectAllEl.disabled = poolSize === 0 || bulk.busy;
      selectAllLabelEl.textContent = 'Chọn tất cả ' + poolSize + ' mục đang hiện' + (activeFilterCount() ? ' (theo bộ lọc)' : '');
    }
    if (!bulkBarEl) return;
    bulkBarEl.hidden = total === 0 && !bulk.result && !bulk.busy;
    var parts = [];
    if (nBooks) parts.push(nBooks + ' giáo trình');
    if (nLessons) parts.push(nLessons + ' bài');
    bulkBarEl.querySelector('[data-bulk-count]').textContent = total
      ? 'Đã chọn ' + total + ' (' + parts.join(', ') + ')' : 'Chưa chọn mục nào';
    Array.prototype.forEach.call(bulkBarEl.querySelectorAll('button[data-bulk-run], button[data-bulk-clear]'), function (b) {
      b.disabled = bulk.busy || total === 0;
    });
    var progressEl = bulkBarEl.querySelector('[data-bulk-progress]');
    var resultEl = bulkBarEl.querySelector('[data-bulk-result]');
    progressEl.hidden = !bulk.busy;
    if (bulk.result) {
      resultEl.hidden = false;
      resultEl.className = 'manage-bulkbar__result ' + (bulk.result.failed ? 'is-error' : 'is-ok');
      resultEl.innerHTML = escapeHtml(bulk.result.text) +
        (bulk.result.reasons.length ? '<ul>' + bulk.result.reasons.map(function (r) { return '<li>' + escapeHtml(r) + '</li>'; }).join('') + '</ul>' : '') +
        ' <button type="button" class="manage-bulkbar__dismiss" data-bulk-dismiss aria-label="Ẩn kết quả">×</button>';
    } else {
      resultEl.hidden = true;
      resultEl.innerHTML = '';
    }
  }

  function setSelected(kind, ids, on) {
    ids.forEach(function (id) { if (on) bulk.selected[kind][id] = true; else delete bulk.selected[kind][id]; });
  }

  /** Cập nhật trạng thái trong state (không tải lại) cho các id đã xử lý thành công. */
  function applyLocalStatus(kind, ids, status) {
    var set = {};
    ids.forEach(function (id) { set[id] = true; });
    if (kind === 'textbook') {
      state.textbooks.forEach(function (t) { if (set[t.id]) t.status = status; });
      Object.keys(state.trees).forEach(function (bookId) {
        var tree = state.trees[bookId];
        if (tree && tree.textbook && set[tree.textbook.id]) tree.textbook.status = status;
      });
    } else {
      Object.keys(state.trees).forEach(function (bookId) {
        ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
          (u.lessons || []).forEach(function (l) { if (set[l.id]) l.status = status; });
        });
      });
    }
  }

  function bulkConfirmText(action, groups) {
    var rule = BULK_ACTIONS[action];
    var counts = groups.filter(function (g) { return g.ids.length; })
      .map(function (g) { return g.ids.length + ' ' + KIND_LABELS[g.kind]; });
    var external = groups.some(function (g) {
      return g.ids.some(function (id) { return isExternalTextbook(g.kind === 'textbook' ? id : textbookIdOfLesson(id)); });
    });
    return rule.verb + ' ' + counts.join(' và ') + '?\n' + rule.effect +
      (external ? '\nNội dung lấy từ nguồn bên ngoài — chỉ công bố khi nhóm đã thống nhất.' : '');
  }

  /**
   * Chạy hàng loạt: chia lô ≤ 500 id gọi POST /manage/status/bulk (mỗi lô một transaction ở server). Công bố thì
   * giáo trình trước rồi mới tới bài (bài cần giáo trình đã công bố). Lô lỗi giữ nguyên lựa chọn để thử lại.
   */
  function runBulk(action, groups) {
    if (bulk.busy) return;
    var rule = BULK_ACTIONS[action];
    var ordered = groups.filter(function (g) { return g.ids.length; });
    if (action === 'UNPUBLISH') ordered.reverse();
    var chunks = [];
    ordered.forEach(function (g) {
      for (var i = 0; i < g.ids.length; i += BULK_CHUNK) chunks.push({ kind: g.kind, ids: g.ids.slice(i, i + BULK_CHUNK) });
    });
    if (!chunks.length) return;
    bulk.busy = true;
    bulk.result = null;
    var done = 0, failed = 0, reasons = [];
    var progressEl = bulkBarEl.querySelector('[data-bulk-progress]');
    render();
    var chain = Promise.resolve();
    chunks.forEach(function (chunk, index) {
      chain = chain.then(function () {
        progressEl.textContent = 'Đang xử lý lô ' + (index + 1) + '/' + chunks.length + '…';
        return request('/manage/status/bulk', {
          method: 'POST',
          body: JSON.stringify({ kind: chunk.kind.toUpperCase(), action: action, ids: chunk.ids })
        }).then(function (data) {
          var ids = (data && data.ids) || chunk.ids;
          applyLocalStatus(chunk.kind, ids, rule.status);
          setSelected(chunk.kind, ids, false);
          done += ids.length;
        }).catch(function (err) {
          failed += chunk.ids.length;
          reasons.push(err.message || 'Lỗi không xác định.');
        });
      });
    });
    return chain.then(function () {
      bulk.busy = false;
      bulk.result = {
        failed: failed,
        text: rule.verb + ': thành công ' + done + ', lỗi ' + failed + (failed ? ' (các mục lỗi vẫn được chọn để thử lại).' : '.'),
        reasons: reasons
      };
      toast(bulk.result.text, failed ? 'error' : 'success');
      // Số liệu đếm của danh sách giáo trình (unitCount…) không đổi theo trạng thái, nên chỉ vẽ lại từ state.
      render();
    });
  }

  function runSelected(action) {
    var groups = [{ kind: 'textbook', ids: selectedIds('textbook') }, { kind: 'lesson', ids: selectedIds('lesson') }];
    if (!groups[0].ids.length && !groups[1].ids.length) return;
    if (!window.confirm(bulkConfirmText(action, groups))) return;
    runBulk(action, groups);
  }

  /** "Công bố / Ẩn tất cả bài" của một phần hoặc cả giáo trình: mọi bài chưa lưu trữ trong phạm vi (không theo bộ lọc). */
  function runScope(scope, id, action) {
    var lessons = [];
    Object.keys(state.trees).forEach(function (bookId) {
      if (scope === 'textbook' && String(bookId) !== String(id)) return;
      ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
        if (scope === 'unit' && String(u.id) !== String(id)) return;
        (u.lessons || []).forEach(function (l) { if (!isArchived(l)) lessons.push(l.id); });
      });
    });
    if (!lessons.length) { toast('Không có bài nào để ' + (action === 'PUBLISH' ? 'công bố.' : 'ẩn.'), 'info'); return; }
    var groups = [{ kind: 'lesson', ids: lessons }];
    if (!window.confirm(bulkConfirmText(action, groups))) return;
    runBulk(action, groups);
  }

  function onSelectChange(event) {
    var box = event.target.closest('.manage-check');
    if (!box) return;
    var kind = box.getAttribute('data-select');
    var id = box.getAttribute('data-id');
    if (kind === 'unit') {
      var unit = findUnit(id);
      var unitHit = hitOfUnit(id);
      var ids = ((unit && unit.lessons) || []).filter(function (l) { return lessonVisible(l, unitHit, unit) && !isArchived(l); })
        .map(function (l) { return l.id; });
      setSelected('lesson', ids, box.checked);
    } else {
      setSelected(kind, [id], box.checked);
    }
    render();
  }

  /** Dùng chung cho xoá phần và xoá bài: cùng cách xác nhận, cùng cách báo lỗi. */
  function deleteEntity(kind, id) {
    var isUnit = kind === 'unit';
    var question = isUnit
      ? 'Xoá phần này? Thao tác KHÔNG hoàn tác được. Chỉ xoá được phần đã rỗng bài.'
      : 'Xoá bài học này? Thao tác KHÔNG hoàn tác được. Chỉ xoá được bài còn nháp, chưa có nội dung và chưa ai học.';
    if (!window.confirm(question)) return;
    clearError();
    request('/' + (isUnit ? 'units' : 'lessons') + '/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function () {
        toast(isUnit ? 'Đã xoá phần.' : 'Đã xoá bài học.', 'success');
        return reloadAll();
      })
      .catch(function (err) {
        // 409 ở đây là thứ phải đọc kỹ rồi mới biết làm gì tiếp, nên hiện ở dải cố định đầu danh sách
        // để nó NẰM LẠI trên trang — toast tự biến mất sau 4,5 giây là quá ngắn.
        // Chỉ báo một lần, không kèm toast, tránh nói hai lần cùng một chuyện.
        showStickyError(err.message || (isUnit ? 'Không xoá được phần.' : 'Không xoá được bài học.'));
      });
  }

  // ── Sắp xếp lại thứ tự ─────────────────────────────────────────────────────

  /**
   * Luôn gửi TOÀN BỘ danh sách id theo thứ tự mới (API đánh số lại cả danh sách trong một transaction),
   * không gửi từng cặp hoán đổi — order_index có UNIQUE nên hoán đổi từng cặp sẽ vi phạm ràng buộc.
   * Lỗi thì tải lại từ server để giao diện không lệch với DB.
   */
  function submitOrder(kind, parentId, orderedIds) {
    var path = kind === 'unit'
      ? '/textbooks/' + encodeURIComponent(parentId) + '/units/order'
      : '/units/' + encodeURIComponent(parentId) + '/lessons/order';
    return request(path, { method: 'PUT', body: JSON.stringify({ orderedIds: orderedIds }) })
      .then(function () {
        toast(kind === 'unit' ? 'Đã cập nhật thứ tự phần.' : 'Đã cập nhật thứ tự bài học.', 'success');
        return reloadAll();
      })
      .catch(function (err) {
        toast(err.message || 'Không đổi được thứ tự.', 'error');
        return reloadAll();
      });
  }

  /** Id hiện có của một nhóm, theo đúng thứ tự đang hiển thị. */
  function groupIds(groupEl) {
    return Array.prototype.slice.call(groupEl.children)
      .filter(function (child) { return child.hasAttribute('data-id'); })
      .map(function (child) { return Number(child.getAttribute('data-id')); });
  }

  /**
   * Danh sách hiển thị có thể đang ẩn mục lưu trữ, nhưng API đòi ĐỦ id của nhóm. Nên lấy thứ tự mới
   * từ DOM rồi chèn lại những id bị ẩn vào đúng vị trí tương đối cũ, dựa trên dữ liệu đã tải.
   */
  function fullOrder(kind, parentId, visibleOrder) {
    var all = [];
    if (kind === 'unit') {
      var tree = state.trees[parentId];
      all = ((tree && tree.units) || []).map(function (u) { return u.id; });
    } else {
      Object.keys(state.trees).forEach(function (bookId) {
        ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
          if (String(u.id) === String(parentId)) all = (u.lessons || []).map(function (l) { return l.id; });
        });
      });
    }
    var visibleSet = {};
    visibleOrder.forEach(function (id) { visibleSet[id] = true; });
    var hidden = all.filter(function (id) { return !visibleSet[id]; });
    // Mục đang ẩn giữ nguyên vị trí tương đối: chèn lại theo thứ tự cũ, sau các mục hiện trước nó.
    if (!hidden.length) return visibleOrder;
    var result = [];
    var visibleQueue = visibleOrder.slice();
    all.forEach(function (id) {
      if (visibleSet[id]) { if (visibleQueue.length) result.push(visibleQueue.shift()); }
      else result.push(id);
    });
    return result.concat(visibleQueue);
  }

  function moveBy(kind, id, delta) {
    var row = treeEl.querySelector('[data-sort-kind="' + kind + '"][data-id="' + id + '"]');
    if (!row) return;
    var group = row.parentElement;
    var ids = groupIds(group);
    var from = ids.indexOf(Number(id));
    var to = from + delta;
    if (from < 0 || to < 0 || to >= ids.length) return;
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    var parentId = kind === 'unit' ? group.getAttribute('data-textbook-id') : group.getAttribute('data-unit-id');
    submitOrder(kind, parentId, fullOrder(kind, parentId, ids));
  }

  /** Kéo thả HTML5; nút mũi tên lên/xuống là lối đi tương đương cho bàn phím. */
  function setupDragAndDrop() {
    var dragged = null;
    treeEl.addEventListener('dragstart', function (event) {
      var row = event.target.closest('[data-sort-kind]');
      if (!row) return;
      dragged = row;
      row.classList.add('is-dragging');
      event.dataTransfer.effectAllowed = 'move';
      try { event.dataTransfer.setData('text/plain', row.getAttribute('data-id')); } catch (e) { /* Safari */ }
    });
    treeEl.addEventListener('dragend', function () {
      if (dragged) dragged.classList.remove('is-dragging');
      dragged = null;
    });
    treeEl.addEventListener('dragover', function (event) {
      if (!dragged) return;
      var over = event.target.closest('[data-sort-kind]');
      // Chỉ cho thả trong cùng một nhóm: không kéo bài sang phần khác, không kéo phần sang giáo trình khác.
      if (!over || over === dragged || over.parentElement !== dragged.parentElement) return;
      event.preventDefault();
      var rect = over.getBoundingClientRect();
      var after = (event.clientY - rect.top) > rect.height / 2;
      over.parentElement.insertBefore(dragged, after ? over.nextSibling : over);
    });
    treeEl.addEventListener('drop', function (event) {
      if (!dragged) return;
      event.preventDefault();
      var group = dragged.parentElement;
      var kind = dragged.getAttribute('data-sort-kind');
      var parentId = kind === 'unit' ? group.getAttribute('data-textbook-id') : group.getAttribute('data-unit-id');
      var ids = groupIds(group);
      dragged.classList.remove('is-dragging');
      dragged = null;
      submitOrder(kind, parentId, fullOrder(kind, parentId, ids));
    });
  }

  // ── Form ───────────────────────────────────────────────────────────────────

  function field(label, name, value, attrs) {
    return '<label class="manage-field"><span>' + escapeHtml(label) + '</span>'
      + '<input class="manage-input" name="' + name + '" value="' + escapeHtml(value == null ? '' : value) + '" '
      + (attrs || '') + '></label>';
  }

  function textareaField(label, name, value) {
    return '<label class="manage-field"><span>' + escapeHtml(label) + '</span>'
      + '<textarea class="manage-input" name="' + name + '" rows="2">' + escapeHtml(value == null ? '' : value) + '</textarea></label>';
  }

  function selectField(label, name, value, options, extra) {
    return '<label class="manage-field"><span>' + escapeHtml(label) + '</span>'
      + '<select class="manage-input" name="' + name + '" ' + (extra || '') + '>'
      + options.map(function (opt) {
        return '<option value="' + escapeHtml(opt.value) + '"'
          + (String(opt.value) === String(value == null ? '' : value) ? ' selected' : '') + '>'
          + escapeHtml(opt.label) + '</option>';
      }).join('')
      + '</select></label>';
  }

  function optionsOf(map, withEmpty) {
    var list = withEmpty ? [{ value: '', label: '— không —' }] : [];
    return list.concat(Object.keys(map).map(function (k) { return { value: k, label: map[k] }; }));
  }

  var modalContext = null;

  function openModal(title, fieldsHtml, onSubmit) {
    modalContext = { onSubmit: onSubmit };
    modalTitleEl.textContent = title;
    formFieldsEl.innerHTML = fieldsHtml;
    formErrorEl.hidden = true;
    formErrorEl.textContent = '';
    submitBtn.disabled = false;
    modalEl.hidden = false;
    var first = formFieldsEl.querySelector('input, select, textarea');
    if (first) first.focus();
  }

  function closeModal() {
    // Giải phóng URL xem trước của ảnh chưa tải lên (nếu modal đang có ThumbnailUploader).
    if (modalContext && modalContext.thumbnail) modalContext.thumbnail.release();
    modalEl.hidden = true;
    modalContext = null;
    state.submitting = false;
  }

  var THUMB_PENDING_MESSAGE = 'Ảnh bạn chọn chưa được tải lên. Nếu tiếp tục, ảnh này sẽ bị bỏ.';

  /**
   * Chặn rời form khi ThumbnailUploader còn việc dở: đang upload thì không cho đi tiếp; còn ảnh đã chọn nhưng
   * chưa tải lên thì hỏi lại. Trả true nếu được phép tiếp tục (lưu/đóng). actionLabel: "lưu" | "đóng".
   */
  function confirmLeaveThumbnail(actionLabel) {
    var thumbnail = modalContext && modalContext.thumbnail;
    if (!thumbnail) return true;
    if (thumbnail.isBusy()) {
      toast('Ảnh đang được tải lên, vui lòng đợi xong rồi ' + actionLabel + '.', 'warning');
      return false;
    }
    if (!thumbnail.hasPending()) return true;
    return window.confirm(THUMB_PENDING_MESSAGE + '\n\nOK: tiếp tục ' + actionLabel
      + '.\nHuỷ: quay lại để tải ảnh lên.');
  }

  /** Mọi lối đóng modal do người dùng (nút Huỷ, bấm overlay, phím Esc) đi qua đây. */
  function requestCloseModal() {
    if (confirmLeaveThumbnail('đóng')) closeModal();
  }

  function formValues() {
    var data = {};
    Array.prototype.forEach.call(formEl.elements, function (el) {
      if (!el.name) return;
      data[el.name] = el.type === 'checkbox' ? el.checked : el.value;
    });
    return data;
  }

  function textbookForm(existing) {
    var levels = [{ value: '', label: '— không theo cấp độ —' }]
      .concat(['N5', 'N4', 'N3', 'N2', 'N1'].map(function (l) { return { value: l, label: l }; }));
    openModal(existing ? 'Sửa giáo trình' : 'Thêm giáo trình',
      field('Tên giáo trình', 'title', existing ? existing.title : '', 'required maxlength="255"')
      + textareaField('Mô tả', 'description', existing ? existing.description : '')
      + selectField('Cấp độ JLPT', 'jlptLevel', existing ? existing.jlptLevel : '', levels)
      + field('Chữ Hán trên thumbnail tự sinh', 'thumbnailKanji', existing ? existing.thumbnailKanji : '',
        'maxlength="4" lang="ja" placeholder="' + THUMB_DEFAULT_KANJI + '"')
      + themeColorField(existing ? existing.themeColor : '')
      + (existing && canManageThumbnail()
        ? thumbnailUploaderHtml(existing)
        : (existing ? '' : '<p class="manage-form__hint">Lưu giáo trình trước, sau đó bấm Sửa để thêm ảnh thumbnail.</p>'))
      + '<p class="manage-form__hint">Giáo trình mới luôn ở trạng thái nháp. Công bố bằng nút con mắt ở danh sách.</p>',
      function (values) {
        var body = JSON.stringify({
          title: values.title, description: values.description, jlptLevel: values.jlptLevel,
          thumbnailKanji: values.thumbnailKanji, themeColor: values.themeColor
        });
        return existing
          ? request('/textbooks/' + existing.id, { method: 'PUT', body: body })
          : request('/textbooks', { method: 'POST', body: body });
      });
    setupThemeColorField();
    if (existing && canManageThumbnail()) setupThumbnailUploader(existing);
  }

  // ── Thumbnail giáo trình ───────────────────────────────────────────────────

  var THUMB_DEFAULT_KANJI = '日';
  var THUMB_MAX_BYTES = 2 * 1024 * 1024;
  var THUMB_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
  var THUMB_EXTENSION = /\.(jpe?g|png|webp)$/i;
  /** Câu gợi ý của uploader: dùng khi render lần đầu và khi refreshButtons đặt lại (không có ảnh chờ). */
  var THUMB_HINT = 'JPG, PNG hoặc WEBP, tối đa 2MB. Kích thước 1200×375 (tỉ lệ 16:5), tránh đặt chữ ở góc phải trên.';

  /** Chỉ CONTENT_CREATOR mới thấy uploader; server cũng chặn bằng @PreAuthorize nên đây chỉ là ẩn/hiện. */
  function canManageThumbnail() {
    var user = window.JlasAuth && typeof JlasAuth.getUser === 'function' ? JlasAuth.getUser() : null;
    return !!(user && user.role === 'CONTENT_CREATOR');
  }

  /** Ô màu: text #RRGGBB (có name → gửi lên) + bảng chọn màu (không name) đồng bộ vào ô text. Để trống = tự chọn. */
  function themeColorField(value) {
    var color = value && /^#[0-9a-f]{6}$/i.test(value) ? value : '';
    return '<label class="manage-field"><span>Màu nền thumbnail tự sinh</span>'
      + '<span class="theme-color-field">'
      + '<input type="color" class="theme-color-field__picker" data-role="theme-picker" value="' + escapeHtml(color || '#ece6fb') + '"'
      + ' aria-label="Chọn màu nền">'
      + '<input class="manage-input" name="themeColor" value="' + escapeHtml(color) + '" maxlength="7"'
      + ' placeholder="#RRGGBB — để trống để tự chọn theo tên">'
      + '</span></label>';
  }

  function setupThemeColorField() {
    var picker = formFieldsEl.querySelector('[data-role="theme-picker"]');
    var text = formFieldsEl.querySelector('input[name="themeColor"]');
    if (!picker || !text) return;
    picker.addEventListener('input', function () { text.value = picker.value; });
    text.addEventListener('input', function () {
      if (/^#[0-9a-f]{6}$/i.test(text.value.trim())) picker.value = text.value.trim();
    });
  }

  function thumbnailUploaderHtml(textbook) {
    var has = !!textbook.thumbnailUrl;
    return '<div class="thumb-uploader">'
      + '<span class="thumb-uploader__label">Ảnh thumbnail</span>'
      + '<div class="thumb-uploader__drop" data-role="thumb-drop" tabindex="0" role="button"'
      + ' aria-label="Bấm hoặc kéo thả ảnh thumbnail vào đây">'
      + '<img class="thumb-uploader__preview" data-role="thumb-preview" alt="Xem trước ảnh thumbnail"'
      + (has ? ' src="' + escapeHtml(textbook.thumbnailUrl) + '"' : ' hidden') + '>'
      + '<span class="thumb-uploader__placeholder" data-role="thumb-placeholder"' + (has ? ' hidden' : '') + '>'
      + '<i class="fa-regular fa-image" aria-hidden="true"></i> Bấm hoặc kéo thả ảnh vào đây</span>'
      + '</div>'
      + '<input type="file" accept="image/jpeg,image/png,image/webp" data-role="thumb-file" hidden>'
      + '<p class="manage-form__hint" data-role="thumb-hint">' + escapeHtml(THUMB_HINT) + '</p>'
      + '<p class="manage-form__error" data-role="thumb-error" hidden></p>'
      + '<div class="thumb-uploader__actions">'
      + '<button type="button" class="manage-btn manage-btn--primary" data-role="thumb-upload">'
      + (has ? 'Đổi ảnh' : 'Tải ảnh lên') + '</button>'
      + '<button type="button" class="manage-btn" data-role="thumb-remove"' + (has ? '' : ' hidden') + '>Gỡ ảnh</button>'
      + '</div></div>';
  }

  /** Lỗi tiếng Việt cho file sai định dạng/quá lớn; null nếu hợp lệ. Server kiểm lại cả magic bytes. */
  function thumbnailFileError(file) {
    if (!file) return 'Vui lòng chọn một ảnh.';
    if (THUMB_TYPES.indexOf(file.type) < 0 || !THUMB_EXTENSION.test(file.name || '')) {
      return 'Ảnh không đúng định dạng. Chỉ nhận JPG, PNG hoặc WEBP.';
    }
    if (file.size > THUMB_MAX_BYTES) return 'Ảnh vượt quá 2MB. Vui lòng chọn ảnh nhỏ hơn.';
    return null;
  }

  /**
   * ThumbnailUploader: chọn (bấm/kéo thả) → xem trước ngay → "Tải ảnh lên"/"Đổi ảnh" mới gửi lên server.
   * Upload/gỡ ảnh gọi API riêng, không phụ thuộc nút Lưu của form; xong thì tải lại danh sách.
   */
  function setupThumbnailUploader(textbook) {
    var drop = formFieldsEl.querySelector('[data-role="thumb-drop"]');
    if (!drop) return;
    var fileInput = formFieldsEl.querySelector('[data-role="thumb-file"]');
    var preview = formFieldsEl.querySelector('[data-role="thumb-preview"]');
    var placeholder = formFieldsEl.querySelector('[data-role="thumb-placeholder"]');
    var errorBox = formFieldsEl.querySelector('[data-role="thumb-error"]');
    var hint = formFieldsEl.querySelector('[data-role="thumb-hint"]');
    var uploadBtn = formFieldsEl.querySelector('[data-role="thumb-upload"]');
    var removeBtn = formFieldsEl.querySelector('[data-role="thumb-remove"]');
    var currentUrl = textbook.thumbnailUrl || null;
    var pendingFile = null;
    var objectUrl = null;
    var busy = false;

    // Cho closeModal/onFormSubmit biết uploader còn việc dở (confirmLeaveThumbnail) và giải phóng preview khi đóng.
    if (modalContext) {
      modalContext.thumbnail = {
        isBusy: function () { return busy; },
        hasPending: function () { return !!pendingFile; },
        release: function () { pendingFile = null; releaseObjectUrl(); }
      };
    }

    function setError(message) {
      errorBox.textContent = message || '';
      errorBox.hidden = !message;
    }

    function showPreview(src) {
      if (src) { preview.src = src; preview.hidden = false; placeholder.hidden = true; }
      else { preview.removeAttribute('src'); preview.hidden = true; placeholder.hidden = false; }
    }

    function releaseObjectUrl() {
      if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; }
    }

    function refreshButtons() {
      uploadBtn.textContent = currentUrl ? 'Đổi ảnh' : 'Tải ảnh lên';
      uploadBtn.disabled = busy;
      removeBtn.hidden = !currentUrl || !!pendingFile;
      removeBtn.disabled = busy;
      // Đang upload thì khoá luôn nút Lưu của form; xong trả về theo trạng thái lưu của form.
      submitBtn.disabled = busy || state.submitting;
      hint.textContent = pendingFile
        ? 'Ảnh mới chưa được lưu. Bấm "' + uploadBtn.textContent + '" để lưu.'
        : THUMB_HINT;
    }

    function pick(file) {
      var error = thumbnailFileError(file);
      if (error) { setError(error); return; }
      setError('');
      releaseObjectUrl();
      pendingFile = file;
      objectUrl = URL.createObjectURL(file);
      showPreview(objectUrl);
      refreshButtons();
    }

    function applyResult(data, message) {
      currentUrl = (data && data.thumbnailUrl) || null;
      textbook.thumbnailUrl = currentUrl;
      pendingFile = null;
      releaseObjectUrl();
      showPreview(currentUrl);
      toast(message, 'success');
      reloadAll().catch(function () { /* danh sách sẽ đúng ở lần tải sau */ });
    }

    function run(promise, message) {
      busy = true;
      refreshButtons();
      return promise
        .then(function (data) { applyResult(data, message); })
        .catch(function (err) { setError(err.message || 'Không cập nhật được ảnh thumbnail.'); })
        .then(function () { busy = false; refreshButtons(); });
    }

    drop.addEventListener('click', function () { if (!busy) fileInput.click(); });
    drop.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); if (!busy) fileInput.click(); }
    });
    fileInput.addEventListener('change', function () {
      if (fileInput.files && fileInput.files[0]) pick(fileInput.files[0]);
      fileInput.value = '';
    });
    drop.addEventListener('dragover', function (event) {
      event.preventDefault();
      drop.classList.add('is-dragover');
    });
    drop.addEventListener('dragleave', function () { drop.classList.remove('is-dragover'); });
    drop.addEventListener('drop', function (event) {
      event.preventDefault();
      drop.classList.remove('is-dragover');
      if (!busy && event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0]) {
        pick(event.dataTransfer.files[0]);
      }
    });
    // Ảnh hiện tại tải lỗi (file bị xoá trên server…): hiện ô chọn ảnh thay vì ảnh vỡ.
    preview.addEventListener('error', function () { if (!pendingFile) showPreview(null); });

    uploadBtn.addEventListener('click', function () {
      if (busy || state.submitting) return;
      if (!pendingFile) { fileInput.click(); return; }
      var formData = new FormData();
      formData.append('file', pendingFile);
      run(request('/textbooks/' + encodeURIComponent(textbook.id) + '/thumbnail', { method: 'POST', body: formData }),
        'Đã cập nhật ảnh thumbnail.');
    });
    removeBtn.addEventListener('click', function () {
      if (busy || state.submitting || !currentUrl) return;
      if (!window.confirm('Gỡ ảnh thumbnail? Giáo trình sẽ quay về thumbnail tự sinh.')) return;
      run(request('/textbooks/' + encodeURIComponent(textbook.id) + '/thumbnail', { method: 'DELETE' }),
        'Đã gỡ ảnh thumbnail.');
    });
    refreshButtons();
  }

  function unitForm(textbookId, existing) {
    openModal(existing ? 'Sửa phần' : 'Thêm phần',
      field('Tên phần', 'title', existing ? existing.title : '', 'required maxlength="255"')
      + textareaField('Mô tả', 'description', existing ? existing.description : ''),
      function (values) {
        var body = JSON.stringify({ title: values.title, description: values.description });
        return existing
          ? request('/units/' + existing.id, { method: 'PUT', body: body })
          : request('/textbooks/' + textbookId + '/units', { method: 'POST', body: body });
      });
  }

  /**
   * lesson.knowledge_tag_id là NOT NULL nên form phải chọn được tag; kèm lối tạo tag mới ngay tại chỗ
   * vì thực tế mỗi bài có một điểm kiến thức riêng, không dùng lại tag của bài khác.
   */
  function lessonForm(unitId, existing) {
    loadKnowledgeTags().then(function (tags) {
      var tagOptions = [{ value: '', label: '— chọn điểm kiến thức —' }]
        .concat(tags.map(function (t) { return { value: t.id, label: t.name + ' (' + t.code + ')' }; }));
      openModal(existing ? 'Sửa bài học' : 'Thêm bài học',
        field('Tên bài học', 'title', existing ? existing.title : '', 'required maxlength="255"')
        + textareaField('Mô tả', 'description', existing ? existing.description : '')
        + selectField('Loại bài', 'lessonType', existing ? existing.lessonType : 'CURRICULUM', optionsOf(LESSON_TYPE_LABELS))
        + selectField('Kỹ năng', 'skill', existing ? existing.skill : 'VOCABULARY', optionsOf(SKILL_LABELS))
        + selectField('Tình huống', 'situationTag', existing ? existing.situationTag : '',
          optionsOf({ TRAVEL: 'Du lịch', BA: 'Công việc', FOUNDATION: 'Nền tảng' }, true))
        + field('Thời lượng (phút)', 'estimatedMinutes', existing ? existing.estimatedMinutes : '', 'type="number" min="1"')
        + selectField('Điểm kiến thức', 'knowledgeTagId', existing ? existing.knowledgeTagId : '', tagOptions,
          'data-role="tag-select"')
        + '<button type="button" class="manage-link-btn" id="newTagToggle">+ Tạo điểm kiến thức mới</button>'
        + '<div class="manage-newtag" id="newTagBox" hidden>'
        + field('Mã tag mới', 'newTagCode', '', 'maxlength="100" placeholder="VD: N3-EMOTION"')
        + field('Tên tag mới', 'newTagName', '', 'maxlength="255" placeholder="VD: N3 bài 1: Cảm xúc"')
        + '</div>',
        function (values) {
          return resolveKnowledgeTagId(values).then(function (tagId) {
            var body = JSON.stringify({
              knowledgeTagId: tagId,
              title: values.title,
              description: values.description,
              lessonType: values.lessonType,
              skill: values.skill,
              situationTag: values.situationTag || null,
              estimatedMinutes: values.estimatedMinutes ? Number(values.estimatedMinutes) : null
            });
            return existing
              ? request('/lessons/' + existing.id, { method: 'PUT', body: body })
              : request('/units/' + unitId + '/lessons', { method: 'POST', body: body });
          });
        });
      var toggle = document.getElementById('newTagToggle');
      var box = document.getElementById('newTagBox');
      if (toggle && box) {
        toggle.addEventListener('click', function () {
          box.hidden = !box.hidden;
          toggle.textContent = box.hidden ? '+ Tạo điểm kiến thức mới' : '− Dùng tag có sẵn';
        });
      }
    }).catch(function (err) { toast(err.message || 'Không tải được danh sách điểm kiến thức.', 'error'); });
  }

  /** Nếu người dùng điền tag mới thì tạo tag trước rồi mới lưu bài, để không có bài thiếu điểm kiến thức. */
  function resolveKnowledgeTagId(values) {
    var box = document.getElementById('newTagBox');
    var wantsNew = box && !box.hidden && values.newTagCode && values.newTagName;
    if (!wantsNew) {
      if (!values.knowledgeTagId) return Promise.reject(new Error('Phải chọn hoặc tạo một điểm kiến thức.'));
      return Promise.resolve(Number(values.knowledgeTagId));
    }
    return request('/manage/tags', {
      method: 'POST',
      body: JSON.stringify({ code: values.newTagCode, name: values.newTagName, tagType: KNOWLEDGE_TAG_TYPE })
    }).then(function (tag) {
      state.knowledgeTags = null;
      return tag.id;
    });
  }

  function loadKnowledgeTags() {
    if (state.knowledgeTags) return Promise.resolve(state.knowledgeTags);
    return request('/manage/tags?type=' + KNOWLEDGE_TAG_TYPE).then(function (tags) {
      state.knowledgeTags = tags || [];
      return state.knowledgeTags;
    });
  }

  function findTextbook(id) {
    return state.textbooks.filter(function (t) { return String(t.id) === String(id); })[0] || null;
  }

  function findUnit(id) {
    var found = null;
    Object.keys(state.trees).forEach(function (bookId) {
      ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
        if (String(u.id) === String(id)) found = u;
      });
    });
    return found;
  }

  function findLesson(id) {
    var found = null;
    Object.keys(state.trees).forEach(function (bookId) {
      ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
        (u.lessons || []).forEach(function (l) { if (String(l.id) === String(id)) found = l; });
      });
    });
    return found;
  }

  function unitIdOfLesson(lessonId) {
    var unitId = null;
    Object.keys(state.trees).forEach(function (bookId) {
      ((state.trees[bookId] && state.trees[bookId].units) || []).forEach(function (u) {
        (u.lessons || []).forEach(function (l) { if (String(l.id) === String(lessonId)) unitId = u.id; });
      });
    });
    return unitId;
  }

  // ── Sự kiện ────────────────────────────────────────────────────────────────

  function onTreeClick(event) {
    var btn = event.target.closest('[data-action]');
    if (!btn) return;
    var action = btn.getAttribute('data-action');
    var kind = btn.getAttribute('data-kind');
    var id = btn.getAttribute('data-id');

    if (action === 'toggle') {
      var open = !state.expanded[id];
      state.expanded[id] = open;
      render();
      if (open && !state.trees[id]) loadTree(id).catch(function (err) { showError(err.message); });
      return;
    }
    if (action === 'edit') {
      if (kind === 'textbook') textbookForm(findTextbook(id));
      else if (kind === 'unit') unitForm(null, findUnit(id));
      else lessonForm(null, findLesson(id));
      return;
    }
    if (action === 'open-editor') {
      window.location.href = '/textbooks/lessons/' + encodeURIComponent(id) + '/edit';
      return;
    }
    if (action === 'add-unit') { unitForm(id, null); return; }
    if (action === 'add-lesson') {
      // [JLAS2-17] Thêm bài học ở trang riêng 3 bước (thay modal); modal Sửa bài học giữ nguyên.
      var bookId = Object.keys(state.trees).filter(function (tid) {
        return ((state.trees[tid] && state.trees[tid].units) || []).some(function (u) { return String(u.id) === String(id); });
      })[0];
      window.location.href = '/textbooks/manage/' + encodeURIComponent(bookId) + '/lessons/new?unitId=' + encodeURIComponent(id);
      return;
    }
    if (action === 'delete') { deleteEntity(kind, id); return; }
    if (action === 'move-up') { moveBy(kind, id, -1); return; }
    if (action === 'move-down') { moveBy(kind, id, 1); return; }
    if (STATUS_ACTIONS[action]) changeStatus(action, kind, id);
  }

  function onFormSubmit(event) {
    event.preventDefault();
    if (!modalContext || state.submitting) return;
    var values = formValues();
    if (!values.title || !String(values.title).trim()) {
      formErrorEl.textContent = 'Tên không được để trống.';
      formErrorEl.hidden = false;
      return;
    }
    if (!confirmLeaveThumbnail('lưu')) return;
    state.submitting = true;
    submitBtn.disabled = true;
    formErrorEl.hidden = true;
    modalContext.onSubmit(values)
      .then(function () {
        closeModal();
        toast('Đã lưu.', 'success');
        return reloadAll();
      })
      .catch(function (err) {
        state.submitting = false;
        submitBtn.disabled = false;
        formErrorEl.textContent = err.message || 'Không lưu được.';
        formErrorEl.hidden = false;
      });
  }

  treeEl.addEventListener('click', onTreeClick);
  formEl.addEventListener('submit', onFormSubmit);
  modalEl.addEventListener('click', function (event) {
    if (event.target.closest('[data-close]')) requestCloseModal();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !modalEl.hidden) requestCloseModal();
  });
  showArchivedEl.addEventListener('change', applyFilters);
  // [JLAS2-17] Công bố / ẩn hàng loạt
  treeEl.addEventListener('change', onSelectChange);
  treeEl.addEventListener('click', function (event) {
    var scopeBtn = event.target.closest('[data-bulk-scope]');
    if (scopeBtn && !scopeBtn.disabled) {
      runScope(scopeBtn.getAttribute('data-bulk-scope'), scopeBtn.getAttribute('data-id'), scopeBtn.getAttribute('data-bulk-action'));
    }
  });
  if (statusFilterEl) {
    statusFilterEl.addEventListener('change', function () { bulk.filter = statusFilterEl.value; applyFilters(); });
  }
  if (selectAllEl) {
    selectAllEl.addEventListener('change', function () {
      var pool = selectableVisible();
      setSelected('textbook', pool.textbook, selectAllEl.checked);
      setSelected('lesson', pool.lesson, selectAllEl.checked);
      render();
    });
  }
  if (bulkBarEl) {
    bulkBarEl.addEventListener('click', function (event) {
      var run = event.target.closest('[data-bulk-run]');
      if (run && !run.disabled) { runSelected(run.getAttribute('data-bulk-run')); return; }
      if (event.target.closest('[data-bulk-clear]')) {
        bulk.selected = { textbook: {}, lesson: {} };
        bulk.result = null;
        render();
        return;
      }
      if (event.target.closest('[data-bulk-dismiss]')) { bulk.result = null; render(); }
    });
  }
  setupDragAndDrop();
  setupFilters();

  loadTextbooks().catch(function (err) {
    showError(err.message || 'Không tải được danh sách giáo trình.');
  });
});
