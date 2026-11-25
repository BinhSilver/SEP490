(function () {
  'use strict';

  var app = document.getElementById('notebookApp');
  if (!app) return;
  var $ = function (id) { return document.getElementById(id); };
  var state = { books: [], active: null, entries: [], kanji: [], sort: 'newest', requestId: 0, selectedEntries: new Set(), selectionMode: false, exportBusy: false };

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (char) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char];
    });
  }

  function notice(id, message) {
    var element = $(id);
    element.textContent = message || '';
    element.hidden = !message;
  }

  async function request(path, options) {
    var headers = window.JlasAuth ? window.JlasAuth.authHeaders() : {};
    if (options && options.body) headers['Content-Type'] = 'application/json';
    var response = await fetch('/api/dictionary' + path, Object.assign({ headers: headers }, options || {}));
    var payload;
    try { payload = await response.json(); }
    catch (error) { throw new Error('Không đọc được phản hồi từ máy chủ.'); }
    if (!response.ok || payload.success === false) {
      throw new Error(response.status === 401 ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.' : payload.message || 'Không thể tải sổ tay.');
    }
    return payload.data;
  }

  function renderBooks() {
    $('notebookBooks').innerHTML = state.books.map(function (book) {
      return '<button class="notebook-book" type="button" data-book="' + book.id + '">' +
        '<span class="notebook-book-icon" aria-hidden="true">▤</span>' +
        '<span class="notebook-book-info"><strong>' + escapeHtml(book.name) + '</strong><small>' + ((Number(book.wordCount) || 0) + (Number(book.kanjiCount) || 0)) + ' từ' + '</small></span>' +
        '<span class="notebook-book-arrow" aria-hidden="true">›</span></button>';
    }).join('');
  }

  function updateBookTotal() {
    $('notebookTotal').textContent = notebookItems().length + ' từ đã lưu';
    $('notebookQuery').parentElement.hidden = !state.entries.length && !state.kanji.length;
  }
  function notebookItems() {
    return window.NotebookFlashcards.notebookItems(state.entries, state.kanji);
  }

  function visibleNotebookItems() {
    var query = $('notebookQuery').value.trim().toLocaleLowerCase();
    var entries = notebookItems().filter(function (item) {
      var searchable = [item.term, item.reading, item.meaning, item.hanViet].concat(item.source.on || [], item.source.kun || []);
      return !query || searchable.join(' ').toLocaleLowerCase().includes(query);
    });
    entries.sort(function (a, b) {
      var timeA = Date.parse(a.savedAt) || 0, timeB = Date.parse(b.savedAt) || 0;
      return (timeA - timeB) * (state.sort === 'oldest' ? 1 : -1) || a.key.localeCompare(b.key);
    });
    return entries;
  }

  function renderEntries() {
    var query = $('notebookQuery').value.trim();
    var all = notebookItems(), entries = visibleNotebookItems();
    $('notebookEntries').innerHTML = entries.length ? entries.map(function (item) {
      var entry = item.source, kanji = item.type === 'kanji', body = '';
      if (item.hanViet) {
        body += '<p>Hán Việt: ' + escapeHtml(item.hanViet) + '</p>';
      }
      if (item.exampleJa || item.exampleVi) {
        body += '<div class="notebook-entry-example">' + (item.exampleJa ? '<span lang="ja">' + escapeHtml(item.exampleJa) + '</span>' : '') +
          (item.exampleVi ? '<span>→ ' + escapeHtml(item.exampleVi) + '</span>' : '') + '</div>';
      }
      var actions = '<button type="button" data-speak-entry="' + escapeHtml(item.key) + '"><span class="material-symbols-outlined" aria-hidden="true">volume_up</span> Phát âm</button>' +
        '<a href="/dictionary/index.html?q=' + encodeURIComponent(item.term) + '">Mở từ điển ↗</a>' +
        (kanji ? '<a href="/dictionary/kanji-map?kanji=' + encodeURIComponent(item.term) + '">Sơ đồ liên kết ↗</a>' : '') +
        '<button type="button" ' + (kanji ? 'data-remove-kanji="' + escapeHtml(item.term) : 'data-remove="' + entry.vocabularyId) + '"><span class="material-symbols-outlined" aria-hidden="true">delete</span> Xóa</button>';
      var selection = !state.selectionMode ? '' : '<label class="notebook-word-select"><input type="checkbox" data-select-entry="' + escapeHtml(item.key) + '" aria-label="Chọn ' + escapeHtml(item.term) + ' để tạo flashcard" ' +
        (state.selectedEntries.has(item.key) ? 'checked ' : '') + (state.exportBusy ? 'disabled' : '') + '></label>';
      return '<div class="notebook-entry-row' + (state.selectionMode ? ' is-selectable' : '') + '">' + selection + '<details class="notebook-entry" data-entry-key="' + escapeHtml(item.key) + '"' + '><summary class="notebook-entry-summary">' +
        '<span class="notebook-entry-main"><span><strong class="notebook-entry-term" lang="ja">' + escapeHtml(item.term) + '</strong>' +
        (item.reading ? '<small class="notebook-entry-reading" lang="ja">' + escapeHtml(item.reading) + '</small>' : '') + '</span>' +
        '<span class="notebook-entry-meaning">' + escapeHtml(item.meaning || 'Chưa có nghĩa trong từ điển.') + '</span></span>' +
        '<span class="notebook-entry-chevron" aria-hidden="true"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="m7 10 5 5 5-5"/></svg></span></summary><div class="notebook-entry-body">' + body +
        '<div class="notebook-entry-actions">' + actions + '</div></div></details></div>';
    }).join('') : '<div class="notebook-empty"><h2>' + (!all.length ? 'Sổ này chưa có từ' : 'Không tìm thấy từ phù hợp') +
      '</h2><p>' + (!all.length ? 'Lưu từ vào sổ để bắt đầu.' : 'Thử tìm bằng từ, cách đọc hoặc nghĩa khác.') + '</p></div>';
    updateSelectionToolbar();
  }

  function updateSelectionToolbar() {
    var items = notebookItems(), existing = new Set(items.map(function (item) { return item.key; }));
    state.selectedEntries.forEach(function (id) { if (!existing.has(id)) state.selectedEntries.delete(id); });
    var count = state.selectedEntries.size, visible = visibleNotebookItems();
    $('notebookFlashcardToolbar').hidden = !state.selectionMode;
    $('notebookChooseFlashcards').hidden = state.selectionMode || !items.length;
    $('notebookChooseFlashcards').setAttribute('aria-expanded', String(state.selectionMode));
    $('notebookCancelSelection').disabled = state.exportBusy;
    $('notebookSelectedCount').textContent = count ? count + ' từ được chọn' : 'Chưa chọn từ';
    $('notebookSelectWords').disabled = state.exportBusy || !visible.length;
    $('notebookSelectWords').textContent = visible.length && visible.every(function (item) { return state.selectedEntries.has(item.key); }) ? 'Bỏ chọn các từ đang hiện' : 'Chọn tất cả từ';
    $('notebookClearSelection').hidden = !count;
    $('notebookClearSelection').disabled = state.exportBusy || !count;
    $('notebookCreateFlashcards').disabled = state.exportBusy || !count;
    $('notebookCreateFlashcards').textContent = state.exportBusy ? 'Đang tạo bộ thẻ…' : 'Tạo bộ thẻ';
  }

  async function createSelectedFlashcards() {
    if (!state.active || !state.selectionMode || state.exportBusy) return;
    var body;
    try { body = window.NotebookFlashcards.buildDraft(state.active, notebookItems(), state.selectedEntries); }
    catch (error) { notice('notebookDetailNotice', error.message); return; }
    state.exportBusy = true; renderEntries(); notice('notebookDetailNotice', '');
    try {
      var headers = window.JlasAuth ? window.JlasAuth.authHeaders() : {};
      headers['Content-Type'] = 'application/json';
      var response = await fetch('/api/flashcards/drafts', { method: 'POST', headers: headers, body: JSON.stringify(body) });
      var payload = await response.json();
      if (!response.ok || payload.success === false) throw new Error(payload.message || 'Không tạo được bộ thẻ. Hãy thử lại.');
      window.location.href = window.NotebookFlashcards.editorUrl(payload.data);
    } catch (error) { notice('notebookDetailNotice', error.message); }
    finally { state.exportBusy = false; renderEntries(); }
  }


  async function refreshBooks() {
    state.books = await request('/notebooks');
    renderBooks();
    $('notebookBooks').setAttribute('aria-busy', 'false');
  }

  async function openBook(book) {
    var requestId = ++state.requestId;
    notice('notebookNotice', '');
    try {
      var results = await Promise.all([request('/notebooks/' + book.id + '/entries'),request('/notebooks/' + book.id + '/kanji')]);
      var entries = results[0];
      if (requestId !== state.requestId) return;
      state.active = book; $('notebookDetail').dataset.book = String(book.id); // [JLAS-W0108] Q6: lets jpath-grammar-mistakes.js find the 'Lộ trình học' book
      state.entries = entries; state.kanji = results[1]; state.selectedEntries.clear(); state.selectionMode=false;
      $('notebookQuery').value = '';
      state.sort = 'newest';
      $('notebookSortLabel').textContent = 'Mới nhất';
      $('notebookSortMenu').hidden = true;
      $('notebookSortToggle').setAttribute('aria-expanded', 'false');
      $('notebookSortMenu').querySelectorAll('[data-sort]').forEach(function (item) {
        item.setAttribute('aria-pressed', String(item.dataset.sort === state.sort));
      });
      $('notebookTitle').textContent = book.name;
      updateBookTotal();
      $('notebookManage').hidden = false;
      $('notebookDelete').setAttribute('aria-label', book.type === 'CUSTOM' ? 'Xóa sổ tay' : 'Xóa toàn bộ nội dung trong sổ');
      $('notebookDelete').title = book.type === 'CUSTOM' ? 'Xóa sổ tay' : 'Xóa toàn bộ nội dung trong sổ';
      notice('notebookDetailNotice', '');
      updateSelectionToolbar();
      renderEntries();
      $('notebookOverview').hidden = true;
      $('notebookDetail').hidden = false;
    } catch (error) {
      if (requestId === state.requestId) notice('notebookNotice', error.message);
    }
  }

  $('notebookBooks').addEventListener('click', function (event) {
    var button = event.target.closest('[data-book]');
    if (!button) return;
    var book = state.books.find(function (item) { return String(item.id) === button.dataset.book; });
    if (book) openBook(book);
  });

  $('notebookCreate').addEventListener('click', function () {
    $('notebookName').value = '';
    notice('notebookCreateNotice', '');
    $('notebookCreateDialog').showModal();
    $('notebookName').focus();
  });
  $('notebookCancel').addEventListener('click', function () { $('notebookCreateDialog').close(); });
  $('notebookCreateForm').addEventListener('submit', async function (event) {
    event.preventDefault();
    var name = $('notebookName').value.trim();
    if (!name) { notice('notebookCreateNotice', 'Vui lòng nhập tên sổ tay.'); return; }
    var submit = $('notebookSubmit');
    submit.disabled = true;
    try {
      var book = await request('/notebooks', { method: 'POST', body: JSON.stringify({ name: name }) });
      await refreshBooks();
      $('notebookCreateDialog').close();
      await openBook(book);
    } catch (error) { notice('notebookCreateNotice', error.message); }
    finally { submit.disabled = false; }
  });

  $('notebookRename').addEventListener('click', function () {
    if (!state.active) return;
    $('notebookManageName').value = state.active.name;
    notice('notebookManageNotice', '');
    $('notebookManageDialog').showModal();
    $('notebookManageName').focus();
  });
  $('notebookManageCancel').addEventListener('click', function () { $('notebookManageDialog').close(); });
  $('notebookManageForm').addEventListener('submit', async function (event) {
    event.preventDefault();
    if (!state.active) return;
    var button = $('notebookManageSubmit');
    button.disabled = true;
    try {
      state.active = await request('/notebooks/' + state.active.id,
        { method: 'PUT', body: JSON.stringify({ name: $('notebookManageName').value.trim() }) });
      $('notebookTitle').textContent = state.active.name;
      $('notebookManageDialog').close();
      await refreshBooks();
      notice('notebookDetailNotice', 'Đã đổi tên sổ tay.');
    } catch (error) { notice('notebookManageNotice', error.message); }
    finally { button.disabled = false; }
  });
  $('notebookDelete').addEventListener('click', function () {
    if (!state.active) return;
    var custom = state.active.type === 'CUSTOM';
    $('notebookDeleteTitle').textContent = custom ? 'Xóa sổ tay?' : 'Xóa toàn bộ nội dung trong sổ?';
    $('notebookDeleteMessage').textContent = custom
      ? 'Xóa “' + state.active.name + '” và toàn bộ nội dung đã lưu trong sổ này?'
      : 'Xóa toàn bộ nội dung đã lưu trong “' + state.active.name + '”? Sổ mặc định vẫn được giữ lại.';
    $('notebookDeleteConfirm').textContent = custom ? 'Xóa sổ tay' : 'Xóa toàn bộ nội dung';
    notice('notebookDeleteNotice', '');
    $('notebookDeleteDialog').showModal();
  });
  $('notebookDeleteCancel').addEventListener('click', function () { $('notebookDeleteDialog').close(); });
  $('notebookDeleteConfirm').addEventListener('click', async function () {
    if (!state.active) return;
    var button = this;
    button.disabled = true;
    try {
      if (state.active.type === 'CUSTOM') {
        await request('/notebooks/' + state.active.id, { method: 'DELETE' });
        state.active = null;
        $('notebookDeleteDialog').close();
        $('notebookDetail').hidden = true;
        $('notebookOverview').hidden = false;
        await refreshBooks();
        notice('notebookNotice', 'Đã xóa sổ tay.');
      } else {
        await request('/notebooks/' + state.active.id + '/entries', { method: 'DELETE' });
        $('notebookDeleteDialog').close();
        await openBook(state.active);
        notice('notebookDetailNotice', 'Đã xóa toàn bộ từ trong sổ.');
      }
    } catch (error) { notice('notebookDeleteNotice', error.message); }
    finally { button.disabled = false; }
  });

  $('notebookChooseFlashcards').addEventListener('click',function(){
    if(state.exportBusy||!notebookItems().length)return;
    state.selectionMode=true;renderEntries();$('notebookSelectWords').focus();
  });
  $('notebookCancelSelection').addEventListener('click',function(){
    if(state.exportBusy)return;
    state.selectionMode=false;state.selectedEntries.clear();renderEntries();$('notebookChooseFlashcards').focus();
  });
  $('notebookEntries').addEventListener('change',function(event){
    var input=event.target;
    if(!state.selectionMode||!input.matches('[data-select-entry]')||state.exportBusy)return;
    var id=input.dataset.selectEntry;
    if(input.checked)state.selectedEntries.add(id);else state.selectedEntries.delete(id);
    updateSelectionToolbar();
  });
  $('notebookSelectWords').addEventListener('click',function(){
    if(state.exportBusy)return;
    var words=visibleNotebookItems(),all=words.length&&words.every(function(entry){return state.selectedEntries.has(entry.key);});
    words.forEach(function(entry){var id=entry.key;if(all)state.selectedEntries.delete(id);else state.selectedEntries.add(id);});renderEntries();
  });
  $('notebookClearSelection').addEventListener('click',function(){if(state.exportBusy)return;state.selectedEntries.clear();renderEntries();});
  $('notebookCreateFlashcards').addEventListener('click',createSelectedFlashcards);

  $('notebookQuery').addEventListener('input', renderEntries);
  $('notebookSortToggle').addEventListener('click', function () {
    var menu = $('notebookSortMenu');
    menu.hidden = !menu.hidden;
    this.setAttribute('aria-expanded', String(!menu.hidden));
  });
  $('notebookSortMenu').addEventListener('click', function (event) {
    var button = event.target.closest('[data-sort]');
    if (!button) return;
    state.sort = button.dataset.sort;
    $('notebookSortLabel').textContent = state.sort === 'oldest' ? 'Cũ nhất' : 'Mới nhất';
    this.querySelectorAll('[data-sort]').forEach(function (item) {
      item.setAttribute('aria-pressed', String(item.dataset.sort === state.sort));
    });
    this.hidden = true;
    $('notebookSortToggle').setAttribute('aria-expanded', 'false');
    $('notebookSortToggle').focus();
    renderEntries();
  });
  document.addEventListener('click', function (event) {
    if (!event.target.closest('.notebook-sort')) {
      $('notebookSortMenu').hidden = true;
      $('notebookSortToggle').setAttribute('aria-expanded', 'false');
    }
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !$('notebookSortMenu').hidden) {
      $('notebookSortMenu').hidden = true;
      $('notebookSortToggle').setAttribute('aria-expanded', 'false');
      $('notebookSortToggle').focus();
    }
  });

  $('notebookEntries').addEventListener('click', async function (event) {
    var speak = event.target.closest('[data-speak-entry]');
    if (speak) {
      var word = notebookItems().find(function (item) { return item.key === speak.dataset.speakEntry; });
      if (!word) return;
      if (!('speechSynthesis' in window)) { notice('notebookDetailNotice', 'Trình duyệt này chưa hỗ trợ phát âm.'); return; }
      window.speechSynthesis.cancel();
      var utterance = new SpeechSynthesisUtterance(word.term || word.reading);
      utterance.lang = 'ja-JP';
      utterance.rate = 0.85;
      window.speechSynthesis.speak(utterance);
      return;
    }
    var button = event.target.closest('[data-remove], [data-remove-kanji]');
    if (!button || !state.active || state.exportBusy) return;
    var kanji = button.hasAttribute('data-remove-kanji'), bookId = state.active.id, revision = state.requestId;
    var endpoint = kanji ? '/kanji/' + encodeURIComponent(button.dataset.removeKanji) : '/entries/' + button.dataset.remove;
    button.disabled = true;
    try {
      await request('/notebooks/' + bookId + endpoint, { method: 'DELETE' });
      var entries = await request('/notebooks/' + bookId + (kanji ? '/kanji' : '/entries'));
      if (revision !== state.requestId) return;
      if (kanji) state.kanji = entries; else state.entries = entries;
      updateBookTotal(); updateSelectionToolbar(); renderEntries();
      notice('notebookDetailNotice', 'Đã bỏ từ khỏi sổ tay.');
    } catch (error) { if (revision === state.requestId) notice('notebookDetailNotice', error.message); button.disabled = false; }
  });
  refreshBooks().then(function () {
    var id = new URLSearchParams(location.search).get('book');
    var book = state.books.find(function (b) { return String(b.id) === id; });
    if (book) return openBook(book);
  }).catch(function (error) {
    $('notebookBooks').setAttribute('aria-busy', 'false');
    notice('notebookNotice', error.message);
  });
})();
