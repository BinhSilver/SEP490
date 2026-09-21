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

  var grid, empty, pager, pageLabel, searchInput, sortSelect, folderPanel, folderList;
  var pendingDeleteId = null;

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
    if (new URLSearchParams(window.location.search).get('tab') === 'my') {
      state.tab = 'my';
    }
    document.getElementById('cancel-delete-set').addEventListener('click', closeDeleteModal);
    document.getElementById('confirm-delete-set').addEventListener('click', confirmDelete);
    document.getElementById('delete-set-modal').addEventListener('click', function (event) {
      if (event.target === this) closeDeleteModal();
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

    if (state.tab === 'my') {
      folderPanel.hidden = false;
      document.getElementById('library-heading').innerHTML = '<span class="library-results-heading__icon"><i class="fa-regular fa-folder-open"></i></span> Bộ thẻ của tôi';
      document.getElementById('library-subheading').textContent = 'Bản nháp và các bộ thẻ được sắp xếp theo thư mục của bạn.';
      loadFolders().then(load).catch(function (e) {
        FlashShell.showToast(e.message || 'Không tải được thư mục', 'err');
        load();
      });
    } else {
      load();
    }
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

  function ratingLine(s) {
    var avg = s.avgRating != null ? Number(s.avgRating).toFixed(1) : '—';
    var rc = s.ratingCount || 0;
    var cc = s.commentCount || 0;
    return (
      '<div class="lib-card__social">' +
        '<span title="Đánh giá"><i class="fa-solid fa-star" style="color:#f5a623"></i> ' + avg +
        ' <span class="muted">(' + rc + ')</span></span>' +
        '<span title="Bình luận"><i class="fa-solid fa-comment"></i> ' + cc + '</span>' +
      '</div>'
    );
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
      var quizDisabled = s.draft || totalCards < 4;
      var setTitle = s.title || 'Bản nháp chưa đặt tên';
      var setHref = s.draft ? '/flashcard/editor.html?id=' + s.id : '/flashcard/set.html?id=' + s.id;
      var thumb = s.thumbnail
        ? '<img src="' + FlashShell.esc(s.thumbnail) + '" alt="" class="lib-card__thumb"/>'
        : '<div class="lib-card__thumb lib-card__thumb--ph"><i class="fa-solid fa-layer-group"></i></div>';
      var vis = s.visibility === 'PUBLIC'
        ? '<span class="tag tag-public">Công khai</span>'
        : '<span class="tag tag-private">Riêng tư</span>';
      var draftBadge = s.draft ? '<span class="tag tag-draft">Bản nháp</span>' : '';
      var folderBadge = s.folderName ? '<span class="tag tag-folder"><i class="fa-solid fa-folder"></i> ' + FlashShell.esc(s.folderName) + '</span>' : '';
      var actions = '';
      if (s.ownedByMe) {
        actions =
          '<div class="lib-card__owner-actions" aria-label="Quản lý bộ thẻ">' +
            '<a class="btn btn--ghost btn-ghost btn-sm lib-icon-action" href="/flashcard/editor.html?id=' + s.id + '" title="Sửa bộ thẻ" aria-label="Sửa bộ thẻ"><i class="fa-solid fa-pen"></i></a>' +
            (s.draft ? '' : '<button type="button" class="btn btn--ghost btn-ghost btn-sm lib-icon-action" data-vis="' + s.id + '" data-cur="' + s.visibility + '" title="Đổi chế độ hiển thị" aria-label="Đổi chế độ hiển thị"><i class="fa-solid fa-eye"></i></button>') +
            '<button type="button" class="btn btn--ghost btn-ghost btn-sm lib-icon-action lib-icon-action--danger" data-del="' + s.id + '" title="Xóa bộ thẻ" aria-label="Xóa bộ thẻ"><i class="fa-solid fa-trash"></i></button>' +
          '</div>';
      } else {
        actions =
          '<button type="button" class="btn btn--ghost btn-ghost btn-sm" data-clone="' + s.id + '"><i class="fa-solid fa-clone"></i> Lưu bản sao</button>';
      }
      return (
        '<article class="lib-card">' +
          '<a class="lib-card__link" href="' + setHref + '" aria-label="Xem bộ ' + FlashShell.esc(setTitle) + '">' +
            thumb +
            '<div class="lib-card__meta">' + vis + draftBadge + folderBadge + moderationBadge(s) + '</div>' +
            '<span class="lib-card__count"><i class="fa-solid fa-layer-group"></i> ' + totalCards + ' thẻ</span>' +
          '</a>' +
          '<div class="lib-card__body">' +
            '<h3 class="lib-card__title"><a href="' + setHref + '">' +
              FlashShell.esc(setTitle) + '</a></h3>' +
            '<p class="lib-card__desc">' + FlashShell.esc(s.description || 'Chưa có mô tả cho bộ thẻ này.') + '</p>' +
            '<div class="lib-card__foot">' +
              '<span class="lib-card__owner"><span class="lib-card__avatar"><i class="fa-solid fa-user"></i></span>' + FlashShell.esc(s.ownerName || 'Người học JLAS') + '</span>' +
              '<span class="lib-card__usage" title="Số lượt hoàn thành"><i class="fa-solid fa-graduation-cap"></i> ' + (s.studyCount || 0) + ' lượt học</span>' +
            '</div>' +
            ratingLine(s) +
            (s.ownedByMe && state.tab === 'my' ? '<label class="lib-folder-select"><span>Thư mục</span><select data-move-set="' + s.id + '">' + folderOptions(s.folderId) + '</select></label>' : '') +
            '<div class="lib-card__actions">' +
              (s.draft
                ? '<a class="btn btn--primary btn-primary btn-sm" href="/flashcard/editor.html?id=' + s.id + '"><i class="fa-solid fa-pen"></i> Tiếp tục soạn</a>'
                : '<a class="btn btn--primary btn-primary btn-sm" href="/flashcard/study.html?setId=' + s.id + '"><i class="fa-solid fa-play"></i> Học ngay</a>') +
              '<button type="button" class="btn btn--secondary btn-ghost btn-sm" data-quiz="' + s.id + '" ' +
                (quizDisabled ? 'disabled title="' + (s.draft ? 'Hoàn tất bản nháp trước khi kiểm tra' : 'Cần ít nhất 4 thẻ để kiểm tra') + '"' : 'title="Kiểm tra kiến thức từ bộ thẻ này"') + '>' +
                '<i class="fa-solid fa-bolt"></i> Kiểm tra</button>' +
              actions +
            '</div>' +
          '</div>' +
        '</article>'
      );
    }).join('');

    grid.querySelectorAll('[data-clone]').forEach(function (btn) {
      btn.addEventListener('click', function () { doClone(Number(btn.getAttribute('data-clone'))); });
    });
    grid.querySelectorAll('[data-quiz]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        FlashShell.openFlashcardQuiz(Number(btn.getAttribute('data-quiz')), { button: btn });
      });
    });
    grid.querySelectorAll('[data-del]').forEach(function (btn) {
      btn.addEventListener('click', function () { doDelete(Number(btn.getAttribute('data-del'))); });
    });
    grid.querySelectorAll('[data-vis]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        toggleVis(Number(btn.getAttribute('data-vis')), btn.getAttribute('data-cur'));
      });
    });
    grid.querySelectorAll('[data-move-set]').forEach(function (select) {
      select.addEventListener('change', function () {
        moveSetToFolder(Number(select.getAttribute('data-move-set')), select.value);
      });
    });
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
