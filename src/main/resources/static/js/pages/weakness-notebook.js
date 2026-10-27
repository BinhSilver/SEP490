(function () {
  'use strict';

  var app = document.getElementById('notebookApp');
  if (!app) return;
  var $ = function (id) { return document.getElementById(id); };
  var state = { books: [], active: null, entries: [], kanji: [], filter: 'all', sort: 'newest', requestId: 0, studyCard: null, studyBusy: false, studyAnswered: 0 };
  var kanjiCard = window.NotebookKanjiCard.create();
  var studyRevision = 0;

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
        '<span class="notebook-book-info"><strong>' + escapeHtml(book.name) + '</strong><small>' + book.wordCount + ' từ' + (book.kanjiCount ? ' · ' + book.kanjiCount + ' Kanji' : '') + '</small></span>' +
        '<span class="notebook-book-arrow" aria-hidden="true">›</span></button>';
    }).join('');
  }

  function updateBookTotal() {
    $('notebookTotal').textContent = 'Sổ tay · ' + state.entries.length + ' từ vựng' + (state.kanji.length ? ' · ' + state.kanji.length + ' Kanji' : '');
    $('notebookQuery').parentElement.hidden = !state.entries.length && !state.kanji.length;
  }
  function notebookItems() {
    return state.entries.map(function (entry) {
      return { type: 'vocabulary', key: 'word:' + entry.vocabularyId, term: entry.expression,
        reading: entry.reading, meaning: entry.meaning, savedAt: entry.createdAt, source: entry };
    }).concat(state.kanji.map(function (entry) {
      return { type: 'kanji', key: 'kanji:' + entry.character, term: entry.character,
        reading: entry.hanViet, meaning: entry.meaningVi, savedAt: entry.savedAt, source: entry };
    }));
  }

  function renderEntries() {
    var query = $('notebookQuery').value.trim().toLocaleLowerCase();
    var all = notebookItems();
    var entries = all.filter(function (item) {
      if (state.filter !== 'all' && item.type !== state.filter) return false;
      var searchable = [item.term, item.reading, item.meaning].concat(item.source.on || [], item.source.kun || []);
      return !query || searchable.join(' ').toLocaleLowerCase().includes(query);
    });
    entries.sort(function (a, b) {
      var timeA = Date.parse(a.savedAt) || 0, timeB = Date.parse(b.savedAt) || 0;
      return (timeA - timeB) * (state.sort === 'oldest' ? 1 : -1) || a.key.localeCompare(b.key);
    });
    $('notebookEntryCount').textContent = entries.length;
    $('notebookEntryFilters').querySelectorAll('[data-entry-filter]').forEach(function (button) {
      var type = button.dataset.entryFilter;
      button.setAttribute('aria-pressed', String(type === state.filter));
      button.querySelector('span').textContent = type === 'all' ? all.length : type === 'kanji' ? state.kanji.length : state.entries.length;
    });
    $('notebookEntries').innerHTML = entries.length ? entries.map(function (item, index) {
      var entry = item.source, kanji = item.type === 'kanji';
      var status = entry.repetitions == null ? 'new' : entry.repetitions >= 3 ? 'deep' : entry.repetitions >= 2 ? 'good' : 'learning';
      var body = '';
      if (kanji) {
        body = '<p class="notebook-kanji-readings">' + ((entry.on || []).length ? 'On: <span lang="ja">' + entry.on.map(escapeHtml).join(' · ') + '</span><br>' : '') +
          ((entry.kun || []).length ? 'Kun: <span lang="ja">' + entry.kun.map(escapeHtml).join(' · ') + '</span>' : '') + '</p>';
      } else if (entry.exampleJa || entry.exampleVi) {
        body = '<div class="notebook-entry-example">' + (entry.exampleJa ? '<span lang="ja">' + escapeHtml(entry.exampleJa) + '</span>' : '') +
          (entry.exampleVi ? '<span>→ ' + escapeHtml(entry.exampleVi) + '</span>' : '') + '</div>';
      }
      var actions = (kanji ? '' : '<button type="button" data-speak="' + entry.vocabularyId + '"><span class="material-symbols-outlined" aria-hidden="true">volume_up</span> Phát âm</button>') +
        '<a href="/dictionary/index.html?q=' + encodeURIComponent(item.term || '') + '">Mở từ điển ↗</a>' +
        (kanji ? '<a href="/dictionary/kanji-map?kanji=' + encodeURIComponent(item.term) + '">Sơ đồ liên kết ↗</a>' : '') +
        '<button type="button" ' + (kanji ? 'data-remove-kanji="' + escapeHtml(item.term) : 'data-remove="' + entry.vocabularyId) + '"><span class="material-symbols-outlined" aria-hidden="true">delete</span> Xóa</button>';
      return '<details class="notebook-entry" data-entry-kind="' + item.type + '"' + (index === 0 ? ' open' : '') + '><summary class="notebook-entry-summary"><span class="notebook-entry-dot notebook-entry-dot--' + status + '"></span>' +
        '<span class="notebook-entry-main"><span><strong class="notebook-entry-term" lang="ja">' + escapeHtml(item.term) + '</strong>' +
        (item.reading ? '<small class="notebook-entry-reading"' + (kanji ? '' : ' lang="ja"') + '>' + escapeHtml(item.reading) + '</small>' : '') + '</span>' +
        '<span class="notebook-entry-meaning">' + escapeHtml(item.meaning || 'Chưa có nghĩa trong từ điển.') + '</span></span>' +
        '<span class="notebook-entry-kind">' + (kanji ? 'Kanji' : 'Từ vựng') + '</span><span class="notebook-entry-chevron" aria-hidden="true">⌄</span></summary><div class="notebook-entry-body">' + body +
        '<div class="notebook-entry-actions">' + actions + '</div></div></details>';
    }).join('') : '<div class="notebook-empty"><h2>' + (!all.length ? 'Sổ này chưa có nội dung' : query ? 'Không tìm thấy nội dung phù hợp' : 'Chưa có ' + (state.filter === 'kanji' ? 'Kanji' : 'từ vựng') + ' trong sổ') +
      '</h2><p>' + (!all.length ? 'Lưu từ vựng hoặc Kanji vào sổ để bắt đầu.' : 'Thử thay đổi bộ lọc hoặc từ khóa tìm kiếm.') + '</p></div>';
  }

  function renderProgress() {
    var counts = { deep: 0, good: 0, learning: 0, new: 0 };
    var all = state.entries.concat(state.kanji);
    all.forEach(function (entry) {
      var status = entry.repetitions == null ? 'new' : entry.repetitions >= 3 ? 'deep' : entry.repetitions >= 2 ? 'good' : 'learning';
      counts[status]++;
    });
    $('notebookDeepCount').textContent = counts.deep;
    $('notebookGoodCount').textContent = counts.good;
    $('notebookLearningCount').textContent = counts.learning;
    $('notebookNewCount').textContent = counts.new;
    $('notebookProgressTotal').textContent = all.length + ' thẻ · từ vựng và Kanji';
    var percent = all.length ? Math.round((all.length - counts.new) / all.length * 100) : 0;
    $('notebookProgressFill').style.width = percent + '%';
    $('notebookProgressTrack').setAttribute('aria-valuenow', String(percent));
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
      state.active = book;
      state.entries = entries; state.kanji = results[1];
      $('notebookQuery').value = '';
      state.filter = 'all';
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
      $('notebookLearningProgress').hidden = !entries.length && !state.kanji.length;
      notice('notebookDetailNotice', '');
      renderProgress();
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

  $('notebookBack').addEventListener('click', async function () {
    ++state.requestId;
    $('notebookDetail').hidden = true;
    $('notebookOverview').hidden = false;
    try { await refreshBooks(); } catch (error) { notice('notebookNotice', error.message); }
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

  $('notebookQuery').addEventListener('input', renderEntries);
  $('notebookEntryFilters').addEventListener('click', function (event) {
    var button = event.target.closest('[data-entry-filter]');
    if (!button) return;
    state.filter = button.dataset.entryFilter;
    renderEntries();
  });
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

  async function loadStudyCard(manualCheck) {
    if (!state.active) return;
    var revision = ++studyRevision;
    state.studyBusy = true;
    state.studyCard = null;
    kanjiCard.prepare(null);
    $('notebookCard').hidden = true;
    $('notebookRatings').hidden = true;
    $('notebookStudyDone').hidden = true;
    var refreshButton = $('notebookStudyRefresh');
    if (manualCheck) {
      refreshButton.disabled = true;
      refreshButton.textContent = 'Đang kiểm tra...';
    } else {
      refreshButton.hidden = true;
    }
    notice('notebookStudyNotice', '');
    try {
      var session = await request('/notebooks/' + state.active.id + '/study');
      if ($('notebookStudy').hidden || revision !== studyRevision) return;
      state.studyCard = session.card;
      $('notebookStudyLimit').value = String(session.dailyNewLimit);
      $('notebookStudyCount').textContent = session.dueCount + ' thẻ đến hạn · ' +
        Math.min(session.newCount, session.newRemaining) + ' thẻ mới có thể học hôm nay';
      var remaining = session.dueCount + Math.min(session.newCount, session.newRemaining);
      var total = state.studyAnswered + remaining;
      var progress = total ? Math.round(state.studyAnswered / total * 100) : 100;
      $('notebookStudyPosition').textContent = session.card
        ? (state.studyAnswered + 1) + ' / ' + total
        : state.studyAnswered + ' / ' + state.studyAnswered;
      $('notebookStudyProgress').setAttribute('aria-valuenow', String(progress));
      $('notebookStudyProgressFill').style.width = progress + '%';
      if (!session.card) {
        var quotaReached = session.newCount > 0 && session.newRemaining === 0;
        $('notebookStudyDone').textContent = quotaReached
          ? 'Bạn đã học đủ ' + session.dailyNewLimit + ' thẻ mới hôm nay. Hiện chưa có thẻ đến hạn; thẻ sẽ xuất hiện lại theo lịch ôn đã hiển thị.'
          : 'Hiện chưa có thẻ đến hạn. Thẻ vừa quên sẽ xuất hiện lại theo lịch ôn đã hiển thị.';
        if (manualCheck) $('notebookStudyDone').textContent += ' Vừa kiểm tra xong, chưa có thẻ mới đến hạn.';
        $('notebookStudyDone').hidden = false;
        refreshButton.hidden = false;
        return;
      }
      refreshButton.hidden = true;
      kanjiCard.prepare(session.card);
      $('notebookCardType').textContent = session.card.type === 'KANJI' ? 'KANJI' : 'TỪ VỰNG';
      $('notebookCardWord').textContent = session.card.expression;
      $('notebookCardAnswer').textContent = session.card.expression;
      $('notebookCardReading').textContent = session.card.type === 'KANJI'
        ? 'Hán Việt: ' + (session.card.kanji.hanViet || 'Chưa có') : session.card.reading || '';
      $('notebookCardMeaning').textContent = session.card.meaning || 'Chưa có nghĩa trong từ điển.';
      $('notebookCardExampleJa').textContent = session.card.exampleJa || '';
      $('notebookCardExampleVi').textContent = session.card.exampleVi || '';
      $('notebookCardExample').hidden = !session.card.exampleJa && !session.card.exampleVi;
      $('notebookAgainTime').textContent = session.intervals.AGAIN;
      $('notebookHardTime').textContent = session.intervals.HARD;
      $('notebookGoodTime').textContent = session.intervals.GOOD;
      $('notebookEasyTime').textContent = session.intervals.EASY;
      $('notebookCardFront').hidden = false;
      $('notebookCardBack').hidden = true;
      $('notebookCard').classList.remove('is-revealed');
      $('notebookCard').classList.toggle('is-kanji', session.card.type === 'KANJI');
      $('notebookCardFlip').setAttribute('aria-label', 'Lật sang mặt đáp án');
      $('notebookCardFlip').setAttribute('aria-pressed', 'false');
      $('notebookCard').hidden = false;
      $('notebookCard').focus();
    } catch (error) { if (revision === studyRevision) notice('notebookStudyNotice', error.message); }
    finally {
      if (revision !== studyRevision) return;
      state.studyBusy = false;
      if (manualCheck) {
        refreshButton.disabled = false;
        refreshButton.textContent = 'Kiểm tra thẻ đến hạn';
      }
    }
  }

  $('notebookStudyStart').addEventListener('click', function () {
    if (!state.active) return;
    state.studyAnswered = 0;
    app.classList.add('notebook-page--studying');
    $('notebookOverview').hidden = true;
    $('notebookDetail').hidden = true;
    $('notebookStudy').hidden = false;
    $('notebookStudyTitle').textContent = state.active.name;
    loadStudyCard();
  });
  $('notebookStudyBack').addEventListener('click', function () {
    ++studyRevision; kanjiCard.prepare(null);
    state.studyCard = null;
    app.classList.remove('notebook-page--studying');
    $('notebookStudy').hidden = true;
    $('notebookDetail').hidden = false;
    if (state.active) openBook(state.active);
  });
  $('notebookStudyRefresh').addEventListener('click', function () {
    if (!state.studyBusy) loadStudyCard(true);
  });
  $('notebookStudyLimit').addEventListener('change', async function () {
    if (!state.active) return;
    var select = this;
    select.disabled = true;
    try {
      await request('/notebooks/' + state.active.id + '/study/settings',
        { method: 'PUT', body: JSON.stringify({ dailyNewLimit: Number(select.value) }) });
      await loadStudyCard();
    } catch (error) { notice('notebookStudyNotice', error.message); }
    finally { select.disabled = false; }
  });
  function flipStudyCard() {
    if (!state.studyCard || state.studyBusy) return;
    var showBack = $('notebookCardBack').hidden;
    $('notebookCardFront').hidden = showBack;
    $('notebookCardBack').hidden = !showBack;
    $('notebookCard').classList.toggle('is-revealed', showBack);
    $('notebookCardFlip').setAttribute('aria-label', showBack ? 'Lật về mặt trước' : 'Lật sang mặt đáp án');
    $('notebookCardFlip').setAttribute('aria-pressed', String(showBack));
    $('notebookRatings').hidden = !showBack;
    kanjiCard.reveal(showBack);
  }
  $('notebookCardFlip').addEventListener('click', flipStudyCard);
  $('notebookCard').addEventListener('click', function (event) {
    if (event.target.closest('button, a, details, .notebook-kanji-structure')) return;
    if (window.getSelection().toString()) return;
    flipStudyCard();
  });
  $('notebookRatings').addEventListener('click', async function (event) {
    var button = event.target.closest('[data-rating]');
    if (!button || !state.studyCard || state.studyBusy || $('notebookRatings').hidden) return;
    state.studyBusy = true;
    var card = state.studyCard, bookId = state.active.id, revision = studyRevision;
    kanjiCard.stop();
    var buttons = $('notebookRatings').querySelectorAll('button');
    buttons.forEach(function (item) { item.disabled = true; });
    try {
      var target = card.type === 'KANJI' ? 'kanji/' + encodeURIComponent(card.expression) : card.vocabularyId;
      await request('/notebooks/' + bookId + '/study/' + target,
        { method: 'POST', body: JSON.stringify({ rating: button.dataset.rating }) });
      if (revision !== studyRevision || $('notebookStudy').hidden) return;
      state.studyAnswered++;
      await loadStudyCard();
    } catch (error) { notice('notebookStudyNotice', error.message); }
    finally { buttons.forEach(function (item) { item.disabled = false; }); state.studyBusy = false; }
  });
  document.addEventListener('keydown', function (event) {
    if ($('notebookStudy').hidden || event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === 'Escape') { $('notebookStudyBack').click(); return; }
    if (event.target.closest('input, textarea, select, a, summary, [contenteditable="true"]') ||
        event.target.closest('button') && event.target.id !== 'notebookCard') return;
    if (event.key === ' ' || event.key === 'Enter' && event.target === $('notebookCard')) { event.preventDefault(); flipStudyCard(); return; }
    var rating = { '1': 'AGAIN', '2': 'HARD', '3': 'GOOD', '4': 'EASY' }[event.key];
    if (rating && !$('notebookRatings').hidden) {
      event.preventDefault();
      $('notebookRatings').querySelector('[data-rating="' + rating + '"]').click();
    }
  });

  $('notebookEntries').addEventListener('click', async function (event) {
    var speak = event.target.closest('[data-speak]');
    if (speak) {
      var word = state.entries.find(function (item) { return String(item.vocabularyId) === speak.dataset.speak; });
      if (!word) return;
      if (!('speechSynthesis' in window)) { notice('notebookDetailNotice', 'Trình duyệt này chưa hỗ trợ phát âm.'); return; }
      window.speechSynthesis.cancel();
      var utterance = new SpeechSynthesisUtterance(word.expression || word.reading);
      utterance.lang = 'ja-JP';
      utterance.rate = 0.85;
      window.speechSynthesis.speak(utterance);
      return;
    }
    var button = event.target.closest('[data-remove], [data-remove-kanji]');
    if (!button || !state.active) return;
    var kanji = button.hasAttribute('data-remove-kanji'), bookId = state.active.id, revision = state.requestId;
    var endpoint = kanji ? '/kanji/' + encodeURIComponent(button.dataset.removeKanji) : '/entries/' + button.dataset.remove;
    button.disabled = true;
    try {
      await request('/notebooks/' + bookId + endpoint, { method: 'DELETE' });
      var entries = await request('/notebooks/' + bookId + (kanji ? '/kanji' : '/entries'));
      if (revision !== state.requestId) return;
      if (kanji) state.kanji = entries; else state.entries = entries;
      updateBookTotal(); renderProgress(); renderEntries();
      $('notebookLearningProgress').hidden = !state.entries.length && !state.kanji.length;
      notice('notebookDetailNotice', kanji ? 'Đã bỏ Kanji khỏi sổ tay.' : 'Đã bỏ từ khỏi sổ tay.');
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
