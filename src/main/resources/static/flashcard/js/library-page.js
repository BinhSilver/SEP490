/* Library — Public / My Flashcards + ratings */
(function () {
  'use strict';

  var state = {
    tab: 'public',
    title: '',
    sort: 'NEWEST',
    page: 0,
    size: 12,
    totalPages: 0,
    folderId: null,
    folders: []
  };

  var grid, empty, pager, pageLabel, searchInput, sortSelect, folderPanel, folderList, contextMenu, exportModal;
  var pendingDeleteId = null;
  var contextSetId = null;
  var contextSetVisibility = null;
  var exportSet = null;

  document.addEventListener('DOMContentLoaded', function () {
    var redirectTarget = window.location.pathname + window.location.search;
    if (window.JlasAuth && typeof JlasAuth.requireLogin === 'function') {
      if (!JlasAuth.requireLogin(redirectTarget)) return;
    } else if (!localStorage.getItem('accessToken')) {
      window.location.href = '/login?redirect=' + encodeURIComponent(redirectTarget);
      return;
    }

    grid = document.getElementById('lib-grid');
    empty = document.getElementById('lib-empty');
    pager = document.getElementById('lib-pager');
    pageLabel = document.getElementById('lib-page-label');
    searchInput = document.getElementById('lib-search');
    sortSelect = document.getElementById('lib-sort');
    folderPanel = document.getElementById('folder-panel');
    folderList = document.getElementById('folder-list');
    contextMenu = document.getElementById('lib-context-menu');
    exportModal = document.getElementById('export-modal');
    if (new URLSearchParams(window.location.search).get('tab') === 'my') {
      state.tab = 'my';
    }
    document.getElementById('cancel-delete-set').addEventListener('click', closeDeleteModal);
    document.getElementById('confirm-delete-set').addEventListener('click', confirmDelete);
    document.getElementById('delete-set-modal').addEventListener('click', function (event) {
      if (event.target === this) closeDeleteModal();
    });
    contextMenu.addEventListener('click', function (event) {
      var action = event.target.closest('[data-context-action]');
      if (!action) return;
      handleContextAction(action.getAttribute('data-context-action'));
    });
    bindExportModal();
    document.addEventListener('click', function (event) {
      if (!event.target.closest('#lib-context-menu')) closeContextMenu();
    });
    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') return;
      closeContextMenu();
      closeExportModal();
    });
    window.addEventListener('resize', closeContextMenu);
    window.addEventListener('scroll', closeContextMenu, true);
    grid.addEventListener('contextmenu', function (event) {
      var card = event.target.closest('.lib-card[data-set-id]');
      if (!card || card.getAttribute('data-set-owned') !== 'true') return;
      event.preventDefault();
      openContextMenu(
        Number(card.getAttribute('data-set-id')),
        card.getAttribute('data-set-visibility'),
        card.getAttribute('data-set-draft') === 'true',
        event.clientX,
        event.clientY
      );
    });

    var searchTimer;
    searchInput.addEventListener('input', function () {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(function () {
        state.title = searchInput.value.trim();
        state.page = 0;
        load();
      }, 320);
    });

    sortSelect.addEventListener('change', function () {
      state.sort = sortSelect.value;
      state.page = 0;
      load();
    });

    document.getElementById('lib-prev').addEventListener('click', function () {
      if (state.page > 0) { state.page--; load(); }
    });
    document.getElementById('lib-next').addEventListener('click', function () {
      if (state.page + 1 < state.totalPages) { state.page++; load(); }
    });

    document.getElementById('add-folder').addEventListener('click', createFolder);
    document.querySelectorAll('[data-folder-filter]').forEach(function (button) {
      button.addEventListener('click', function () { selectFolder(button.getAttribute('data-folder-filter')); });
    });

    folderPanel.hidden = false;
    if (state.tab === 'my') {
      document.getElementById('library-heading').innerHTML = '<span class="library-results-heading__icon"><i class="fa-regular fa-folder-open"></i></span> Bộ thẻ của tôi';
      document.getElementById('library-subheading').textContent = 'Bản nháp và các bộ thẻ được sắp xếp theo thư mục của bạn.';
    } else {
      document.getElementById('library-heading').innerHTML = '<span class="library-results-heading__icon"><i class="fa-regular fa-rectangle-list"></i></span> Bộ thẻ công khai';
      document.getElementById('library-subheading').textContent = 'Khám phá các bộ thẻ được chia sẻ bởi cộng đồng.';
    }
    loadFolders().then(load).catch(function (e) {
      FlashShell.showToast(e.message || 'Không tải được thư mục', 'err');
      load();
    });
  });

  async function load() {
    grid.innerHTML = '<div class="lib-loading"><i class="fa-solid fa-spinner fa-spin"></i> Đang tải...</div>';
    empty.hidden = true;
    try {
      var path;
      var q = new URLSearchParams({
        sort: state.sort,
        page: String(state.page),
        size: String(state.size)
      });
      if (state.title) q.set('title', state.title);
      if (state.tab === 'my' && state.folderId !== null) q.set('folderId', String(state.folderId));

      if (state.tab === 'my') {
        path = '/api/flashcards/my?' + q.toString();
      } else {
        path = '/api/flashcards/public?' + q.toString();
      }

      var page = await FlashApi.get(path);
      render(page);
    } catch (e) {
      grid.innerHTML = '';
      FlashShell.showToast(e.message || 'Không tải được thư viện', 'err');
    }
  }

  async function loadFolders() {
    state.folders = await FlashApi.get('/api/flashcards/folders') || [];
    renderFolders();
  }

  function renderFolders() {
    folderList.innerHTML = state.folders.map(function (folder) {
      return '<div class="folder-row">' +
        '<button type="button" class="folder-filter" data-folder-filter="' + folder.id + '">' +
          '<i class="fa-solid fa-folder"></i><span>' + FlashShell.esc(folder.name) + '</span><small>' + (folder.setCount || 0) + '</small>' +
        '</button>' +
        '<button type="button" class="folder-icon-btn" data-folder-rename="' + folder.id + '" title="Đổi tên"><i class="fa-solid fa-pen"></i></button>' +
        '<button type="button" class="folder-icon-btn folder-icon-btn--danger" data-folder-delete="' + folder.id + '" title="Xóa thư mục"><i class="fa-solid fa-trash"></i></button>' +
      '</div>';
    }).join('');
    folderList.querySelectorAll('[data-folder-filter]').forEach(function (button) {
      button.addEventListener('click', function () { selectFolder(button.getAttribute('data-folder-filter')); });
    });
    folderList.querySelectorAll('[data-folder-rename]').forEach(function (button) {
      button.addEventListener('click', function () { renameFolder(Number(button.getAttribute('data-folder-rename'))); });
    });
    folderList.querySelectorAll('[data-folder-delete]').forEach(function (button) {
      button.addEventListener('click', function () { deleteFolder(Number(button.getAttribute('data-folder-delete'))); });
    });
    paintActiveFolder();
  }

  function selectFolder(value) {
    state.folderId = value === 'all' ? null : Number(value);
    state.page = 0;
    paintActiveFolder();
    load();
  }

  function paintActiveFolder() {
    document.querySelectorAll('[data-folder-filter]').forEach(function (button) {
      var value = button.getAttribute('data-folder-filter');
      button.classList.toggle('is-active', state.folderId === null ? value === 'all' : Number(value) === state.folderId);
    });
  }

  async function createFolder() {
    var name = prompt('Tên thư mục mới:');
    if (!name || !name.trim()) return;
    try {
      await FlashApi.post('/api/flashcards/folders', { name: name.trim() });
      await loadFolders();
      FlashShell.showToast('Đã tạo thư mục', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Không tạo được thư mục', 'err');
    }
  }

  async function renameFolder(id) {
    var current = state.folders.find(function (folder) { return folder.id === id; });
    var name = prompt('Tên mới của thư mục:', current ? current.name : '');
    if (!name || !name.trim()) return;
    try {
      await FlashApi.put('/api/flashcards/folders/' + id, { name: name.trim() });
      await loadFolders();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không đổi được tên thư mục', 'err');
    }
  }

  async function deleteFolder(id) {
    if (!confirm('Xóa thư mục? Các bộ thẻ bên trong sẽ chuyển về Chưa phân loại.')) return;
    try {
      await FlashApi.del('/api/flashcards/folders/' + id);
      if (state.folderId === id) state.folderId = null;
      await loadFolders();
      load();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không xóa được thư mục', 'err');
    }
  }

  async function moveSetToFolder(setId, value) {
    try {
      await FlashApi.put('/api/flashcards/' + setId + '/folder', { folderId: value === '0' ? null : Number(value) });
      await loadFolders();
      load();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không chuyển được bộ thẻ', 'err');
    }
  }

  function folderOptions(selectedId) {
    var options = '<option value="0">Chưa phân loại</option>';
    state.folders.forEach(function (folder) {
      options += '<option value="' + folder.id + '" ' + (folder.id === selectedId ? 'selected' : '') + '>' + FlashShell.esc(folder.name) + '</option>';
    });
    return options;
  }

  function ratingBadge(s) {
    var avg = s.avgRating != null ? Number(s.avgRating).toFixed(1) : '—';
    return '<span class="lib-card__rating" title="Đánh giá ' + avg + ' trên 5"><i class="fa-solid fa-star" aria-hidden="true"></i> ' + avg + '</span>';
  }

  function moderationBadge(s) {
    if (s.moderationStatus === 'REVIEW') {
      return '<span class="tag tag-review">Chờ duyệt</span>';
    }
    if (s.moderationStatus === 'BLOCKED') {
      return '<span class="tag tag-blocked">Bị chặn public</span>';
    }
    return '';
  }

  function render(page) {
    var items = (page && page.content) || [];
    state.totalPages = page ? page.totalPages : 0;
    pageLabel.textContent = (state.page + 1) + ' / ' + Math.max(1, state.totalPages);
    pager.hidden = state.totalPages <= 1;

    if (!items.length) {
      grid.innerHTML = '';
      empty.hidden = false;
      return;
    }
    empty.hidden = true;

    grid.innerHTML = items.map(function (s) {
      var totalCards = s.totalCards || 0;
      var setTitle = s.title || 'Bản nháp chưa đặt tên';
      var setHref = s.draft ? '/flashcard/editor.html?id=' + s.id : '/flashcard/study.html?setId=' + s.id;
      var thumb = s.thumbnail
        ? '<img src="' + FlashShell.esc(s.thumbnail) + '" alt="" class="lib-card__thumb"/>'
        : '';
      var vis = s.visibility === 'PUBLIC'
        ? '<span class="tag tag-public">Công khai</span>'
        : '<span class="tag tag-private">Riêng tư</span>';
      var draftBadge = s.draft ? '<span class="tag tag-draft">Bản nháp</span>' : '';
      var folderBadge = s.folderName ? '<span class="tag tag-folder"><i class="fa-solid fa-folder"></i> ' + FlashShell.esc(s.folderName) + '</span>' : '';
      var actions = '';
      if (s.ownedByMe && !s.draft) {
        // Drafts already open the editor from the main button.
        actions =
            '<a class="btn btn--ghost btn-ghost btn-sm lib-icon-action" href="/flashcard/editor.html?id=' + s.id + '" title="Sửa bộ thẻ" aria-label="Sửa bộ thẻ"><i class="fa-solid fa-pen" aria-hidden="true"></i></a>';
      }
      if (!s.ownedByMe) {
        actions =
            '<button type="button" class="btn btn--ghost btn-ghost btn-sm lib-icon-action" data-clone="' + s.id + '" title="Lưu bản sao" aria-label="Lưu bản sao"><i class="fa-solid fa-clone" aria-hidden="true"></i></button>';
      }
      actions +=
          '<button type="button" class="btn btn--ghost btn-ghost btn-sm lib-icon-action" data-export="' + s.id + '" title="Xuất bộ thẻ" aria-label="Xuất bộ thẻ" aria-haspopup="dialog"><i class="fa-solid fa-download"></i></button>';
      if (!s.draft) {
        // Only public sets can be opened by other people, so only they get a share link.
        var shareable = s.visibility === 'PUBLIC';
        actions +=
            '<button type="button" class="btn btn--ghost btn-ghost btn-sm lib-icon-action" data-share="' + s.id + '"' +
            (shareable ? ' title="Chia sẻ link bộ thẻ"' : ' disabled title="Chỉ chia sẻ được bộ thẻ công khai"') +
            ' aria-label="Chia sẻ bộ thẻ"><i class="fa-solid fa-share-nodes"></i></button>';
      }
      var cardVisual = s.thumbnail
        ? '<a class="lib-card__link" href="' + setHref + '" aria-label="Xem bộ ' + FlashShell.esc(setTitle) + '">' +
            thumb +
            '<div class="lib-card__meta">' + ratingBadge(s) + '</div>' +
          '</a>'
        : '<div class="lib-card__meta lib-card__meta--no-thumb">' + ratingBadge(s) + '</div>';
      return (
        '<article class="lib-card' + (s.thumbnail ? '' : ' lib-card--no-thumb') + '" data-set-id="' + s.id + '" data-set-owned="' + Boolean(s.ownedByMe) + '" data-set-visibility="' + FlashShell.esc(s.visibility || 'PRIVATE') + '" data-set-draft="' + Boolean(s.draft) + '">' +
          cardVisual +
          '<div class="lib-card__body">' +
            '<h3 class="lib-card__title"><a href="' + setHref + '">' +
              FlashShell.esc(setTitle) + '</a></h3>' +
            '<div class="lib-card__count">' + totalCards + ' từ</div>' +
            (s.ownedByMe && state.tab === 'my' ? '<label class="lib-folder-select"><span>Thư mục</span><select data-move-set="' + s.id + '">' + folderOptions(s.folderId) + '</select></label>' : '') +
            '<div class="lib-card__actions">' +
              (s.draft
                ? '<a class="btn btn--primary btn-primary btn-sm lib-icon-action" href="/flashcard/editor.html?id=' + s.id + '" title="Tiếp tục soạn" aria-label="Tiếp tục soạn"><i class="fa-solid fa-pen" aria-hidden="true"></i></a>'
                : '<a class="btn btn--primary btn-primary btn-sm lib-icon-action" href="/flashcard/study.html?setId=' + s.id + '" title="Học ngay" aria-label="Học ngay"><i class="fa-solid fa-play" aria-hidden="true"></i></a>') +
              actions +
            '</div>' +
            '<div class="lib-card__foot">' +
              '<span class="lib-card__owner"><span class="lib-card__avatar"><i class="fa-solid fa-user"></i></span>' + FlashShell.esc(s.ownerName || 'Người học JLAS') + '</span>' +
              '<span class="lib-card__usage" title="Số lượt hoàn thành"><i class="fa-solid fa-graduation-cap"></i> ' + (s.studyCount || 0) + ' lượt học</span>' +
            '</div>' +
          '</div>' +
        '</article>'
      );
    }).join('');

    grid.querySelectorAll('[data-clone]').forEach(function (btn) {
      btn.addEventListener('click', function () { doClone(Number(btn.getAttribute('data-clone'))); });
    });
    grid.querySelectorAll('[data-export]').forEach(function (btn) {
      btn.addEventListener('click', function () { openExportModal(Number(btn.getAttribute('data-export'))); });
    });
    grid.querySelectorAll('[data-share]').forEach(function (btn) {
      btn.addEventListener('click', function () { doShare(Number(btn.getAttribute('data-share'))); });
    });
    grid.querySelectorAll('[data-move-set]').forEach(function (select) {
      select.addEventListener('change', function () {
        moveSetToFolder(Number(select.getAttribute('data-move-set')), select.value);
      });
    });
  }

  function openContextMenu(id, visibility, draft, x, y) {
    contextSetId = id;
    contextSetVisibility = visibility;
    contextMenu.querySelector('[data-context-action="visibility"]').hidden = draft;
    contextMenu.hidden = false;
    var left = Math.min(x, window.innerWidth - contextMenu.offsetWidth - 10);
    var top = Math.min(y, window.innerHeight - contextMenu.offsetHeight - 10);
    contextMenu.style.left = Math.max(10, left) + 'px';
    contextMenu.style.top = Math.max(10, top) + 'px';
  }

  function closeContextMenu() {
    if (!contextMenu) return;
    contextMenu.hidden = true;
    contextSetId = null;
    contextSetVisibility = null;
  }

  function handleContextAction(action) {
    var id = contextSetId;
    var visibility = contextSetVisibility;
    closeContextMenu();
    if (!id) return;
    if (action === 'edit') window.location.href = '/flashcard/editor.html?id=' + id;
    if (action === 'visibility') toggleVis(id, visibility);
    if (action === 'delete') doDelete(id);
  }

  /* ── Xuất bộ thẻ: xem trước kiểu Quizlet cho Văn bản / Excel / CSV ── */
  var TERM_SEPARATORS = { tab: '\t', comma: ',' };
  var ROW_SEPARATORS = { newline: '\n', semicolon: ';' };
  var EXPORT_ICONS = { text: 'fa-file-lines', xlsx: 'fa-file-excel', csv: 'fa-file-csv' };
  var exportTab = 'text';
  var exportCsv = null; // { hanViet, name, blob, text } — the exact file shown in the CSV preview
  var exportCsvRequest = 0;

  function bindExportModal() {
    document.getElementById('export-close').addEventListener('click', closeExportModal);
    document.getElementById('export-cancel').addEventListener('click', closeExportModal);
    exportModal.addEventListener('click', function (event) {
      if (event.target === exportModal) closeExportModal();
    });
    exportModal.querySelectorAll('[data-export-tab]').forEach(function (tab) {
      tab.addEventListener('click', function () { selectExportTab(tab.getAttribute('data-export-tab')); });
      tab.addEventListener('keydown', onExportTabKey);
    });
    exportModal.querySelectorAll('input[type="radio"]').forEach(function (input) {
      input.addEventListener('change', renderExportPreview);
    });
    document.getElementById('export-hanviet').addEventListener('change', renderExportPreview);
    ['export-term-custom', 'export-row-custom'].forEach(function (id) {
      document.getElementById(id).addEventListener('input', renderExportPreview);
    });
    document.getElementById('export-copy').addEventListener('click', copyExportText);
    document.getElementById('export-txt').addEventListener('click', downloadExportText);
    document.getElementById('export-xlsx').addEventListener('click', function () {
      if (exportSet) doExport(exportSet.id, 'xlsx');
    });
    document.getElementById('export-csv-download').addEventListener('click', downloadExportCsv);
  }

  async function openExportModal(id) {
    closeContextMenu();
    exportSet = null;
    exportCsv = null;
    document.getElementById('export-title').textContent = 'Xuất bộ thẻ';
    document.getElementById('export-preview').value = '';
    document.getElementById('export-csv').value = '';
    document.getElementById('export-table').innerHTML = '';
    document.getElementById('export-meta').textContent = 'Đang tải bộ thẻ...';
    setExportBusy(true);
    selectExportTab('text', true);
    exportModal.hidden = false;
    try {
      var set = await FlashApi.get('/api/flashcards/' + id);
      if (exportModal.hidden) return;
      exportSet = set;
      document.getElementById('export-title').textContent = 'Xuất bộ thẻ: ' + (set.title || 'Chưa đặt tên');
      setExportBusy(false);
      renderExportPreview();
      document.getElementById('export-tab-text').focus();
    } catch (e) {
      document.getElementById('export-meta').textContent = e.message || 'Không tải được bộ thẻ';
    }
  }

  function closeExportModal() {
    if (!exportModal || exportModal.hidden) return;
    exportModal.hidden = true;
    exportSet = null;
    exportCsv = null;
  }

  function setExportBusy(busy) {
    exportModal.querySelectorAll('[data-export-for]').forEach(function (btn) { btn.disabled = busy; });
  }

  function selectExportTab(tab, silent) {
    exportTab = tab;
    // The header badge follows the selected format (colour comes from CSS via data-format).
    document.getElementById('export-dialog').setAttribute('data-format', tab);
    document.getElementById('export-badge-icon').className = 'fa-solid ' + EXPORT_ICONS[tab];
    exportModal.querySelectorAll('[data-export-tab]').forEach(function (button) {
      var active = button.getAttribute('data-export-tab') === tab;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-selected', String(active));
      button.tabIndex = active ? 0 : -1;
    });
    exportModal.querySelectorAll('[data-export-panel]').forEach(function (panel) {
      panel.hidden = panel.getAttribute('data-export-panel') !== tab;
    });
    exportModal.querySelectorAll('[data-export-for]').forEach(function (button) {
      button.hidden = button.getAttribute('data-export-for').split(' ').indexOf(tab) < 0;
    });
    if (!silent) renderExportPreview();
  }

  function onExportTabKey(event) {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
    var tabs = Array.prototype.slice.call(exportModal.querySelectorAll('[data-export-tab]'));
    var index = tabs.indexOf(event.currentTarget) + (event.key === 'ArrowRight' ? 1 : -1);
    var next = tabs[(index + tabs.length) % tabs.length];
    selectExportTab(next.getAttribute('data-export-tab'));
    next.focus();
    event.preventDefault();
  }

  function withHanViet() {
    return document.getElementById('export-hanviet').checked;
  }

  function selectedSeparator(name, presets, customId) {
    var choice = exportModal.querySelector('input[name="' + name + '"]:checked').value;
    var custom = document.getElementById(customId);
    custom.disabled = choice !== 'custom';
    // An empty custom separator falls back to the first preset so cards never run together.
    if (choice === 'custom') return custom.value || presets[Object.keys(presets)[0]];
    return presets[choice];
  }

  function flattenCell(value) {
    return String(value || '').replace(/\s*[\r\n]+\s*/g, ' ').trim();
  }

  /* Same columns and fallback as the server export: front, back (or reading — meaning), Hán Việt. */
  function exportRows() {
    return ((exportSet && exportSet.cards) || []).map(function (card) {
      var back = card.back || [card.reading, card.meaning].filter(Boolean).join(' — ');
      var cells = [card.front, back];
      if (withHanViet()) cells.push(card.hanViet);
      return cells.map(flattenCell);
    });
  }

  function buildExportText() {
    if (!exportSet) return '';
    var termSep = selectedSeparator('export-term-sep', TERM_SEPARATORS, 'export-term-custom');
    var rowSep = selectedSeparator('export-row-sep', ROW_SEPARATORS, 'export-row-custom');
    return exportRows().map(function (cells) {
      // Drop an empty trailing Hán Việt cell so the line does not end with a separator.
      while (cells.length > 2 && !cells[cells.length - 1]) cells.pop();
      return cells.join(termSep);
    }).join(rowSep);
  }

  function buildExportTable() {
    var header = ['#', 'Mặt trước', 'Mặt sau'];
    if (withHanViet()) header.push('Hán Việt');
    var body = exportRows().map(function (cells, index) {
      return '<tr><th scope="row">' + (index + 1) + '</th>' +
        cells.map(function (cell) { return '<td>' + FlashShell.esc(cell) + '</td>'; }).join('') + '</tr>';
    }).join('');
    return '<thead><tr>' + header.map(function (name) { return '<th scope="col">' + name + '</th>'; }).join('') + '</tr></thead>' +
      '<tbody>' + body + '</tbody>';
  }

  function renderExportPreview() {
    if (!exportSet) return;
    var count = (exportSet.cards || []).length;
    document.getElementById('export-meta').textContent = count + ' thẻ';
    if (exportTab === 'text') document.getElementById('export-preview').value = buildExportText();
    if (exportTab === 'xlsx') document.getElementById('export-table').innerHTML = buildExportTable();
    if (exportTab === 'csv') loadExportCsv();
  }

  /* The CSV preview is the server file itself, so what the user sees is exactly what gets saved. */
  async function loadExportCsv() {
    var hanViet = withHanViet();
    if (exportCsv && exportCsv.hanViet === hanViet) {
      document.getElementById('export-csv').value = exportCsv.text;
      return;
    }
    var request = ++exportCsvRequest;
    var setId = exportSet.id;
    var preview = document.getElementById('export-csv');
    preview.value = '';
    document.getElementById('export-meta').textContent = 'Đang tạo file CSV...';
    setExportBusy(true);
    try {
      var file = await FlashApi.fetchFile('/api/flashcards/' + setId + '/export?format=csv&hanViet=' + hanViet, 'flashcards.csv');
      var text = (await file.blob.text()).replace(/^\uFEFF/, '');
      if (request !== exportCsvRequest || !exportSet || exportSet.id !== setId) return;
      exportCsv = { hanViet: hanViet, name: file.name, blob: file.blob, text: text };
      preview.value = text;
      document.getElementById('export-meta').textContent = (exportSet.cards || []).length + ' thẻ';
    } catch (e) {
      if (request === exportCsvRequest) document.getElementById('export-meta').textContent = e.message || 'Không tạo được file CSV';
    } finally {
      if (request === exportCsvRequest) setExportBusy(false);
    }
  }

  async function copyExportText() {
    var preview = document.getElementById(exportTab === 'csv' ? 'export-csv' : 'export-preview');
    if (await copyText(preview.value)) {
      FlashShell.showToast('Đã sao chép nội dung', 'ok');
    } else {
      FlashShell.showToast('Không sao chép được, hãy bôi đen và nhấn Ctrl+C', 'err');
    }
  }

  function downloadExportText() {
    if (!exportSet) return;
    var name = (exportSet.title || 'flashcards').replace(/[\\/:*?"<>|\r\n\t]+/g, '_').trim() + '.txt';
    FlashApi.saveFile(new Blob([buildExportText()], { type: 'text/plain;charset=utf-8' }), name);
    FlashShell.showToast('Đã xuất ' + name, 'ok');
  }

  function downloadExportCsv() {
    if (!exportCsv) return;
    FlashApi.saveFile(exportCsv.blob, exportCsv.name);
    FlashShell.showToast('Đã xuất ' + exportCsv.name, 'ok');
  }

  async function doExport(id, format) {
    try {
      var path = '/api/flashcards/' + id + '/export?format=' + encodeURIComponent(format) + '&hanViet=' + withHanViet();
      var name = await FlashApi.download(path, 'flashcards.' + format);
      FlashShell.showToast('Đã xuất ' + (name || 'file'), 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Xuất file thất bại', 'err');
    }
  }

  async function doShare(id) {
    var url = window.location.origin + '/flashcard/study.html?setId=' + id;
    if (await copyText(url)) {
      FlashShell.showToast('Đã sao chép link chia sẻ', 'ok');
    } else {
      window.prompt('Sao chép link chia sẻ:', url);
    }
  }

  /* Clipboard API needs HTTPS or localhost; fall back to a hidden textarea on plain HTTP. */
  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      var copied = false;
      try { copied = document.execCommand('copy'); } catch (ignored) { copied = false; }
      area.remove();
      return copied;
    }
  }

  async function doClone(id) {
    if (!FlashShell.requireLogin('Đăng nhập để nhân bản')) return;
    try {
      var set = await FlashApi.post('/api/flashcards/' + id + '/clone', {});
      FlashShell.showToast('Đã nhân bản: ' + set.title, 'ok');
      state.tab = 'my';
      state.page = 0;
      load();
    } catch (e) {
      FlashShell.showToast(e.message || 'Nhân bản thất bại', 'err');
    }
  }

  async function doDelete(id) {
    pendingDeleteId = id;
    document.getElementById('delete-set-modal').hidden = false;
    document.getElementById('cancel-delete-set').focus();
  }

  function closeDeleteModal() {
    pendingDeleteId = null;
    document.getElementById('delete-set-modal').hidden = true;
  }

  async function confirmDelete() {
    if (!pendingDeleteId) return;
    var id = pendingDeleteId;
    try {
      await FlashApi.del('/api/flashcards/' + id);
      FlashShell.showToast('Đã xóa', 'ok');
      closeDeleteModal();
      load();
    } catch (e) {
      FlashShell.showToast(e.message || 'Xóa thất bại', 'err');
    }
  }

  async function toggleVis(id, cur) {
    try {
      var detail = await FlashApi.get('/api/flashcards/' + id);
      var next = cur === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC';
      var updated = await FlashApi.put('/api/flashcards/' + id, {
        title: detail.title,
        description: detail.description,
        thumbnail: detail.thumbnail,
        visibility: next
      });
      if (updated && updated.pendingReview) {
        alert(updated.moderationMessage ||
          'Nội dung đang chờ kiểm duyệt. Bộ thẻ được lưu ở chế độ Chỉ mình tôi.');
      } else {
        FlashShell.showToast(
          'Đã đổi sang ' + (updated.visibility === 'PUBLIC' ? 'Công khai' : 'Riêng tư'),
          'ok'
        );
      }
      load();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không đổi được chế độ hiển thị', 'err');
    }
  }
})();
