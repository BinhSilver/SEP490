(function () {
  'use strict';

  var ui = window.DictUi;
  var esc = ui.escapeHtml;
  var tabs = ['word', 'kanji', 'example', 'grammar'];
  var state = { tab: 'word', query: '', result: null, selectedId: null, word: null, detailCode: null,
    selectedGrammarCode: null, grammarDetailToken: 0, kanjiDetailToken: 0,
    page: 0, size: 20, token: 0, otherPageToken: 0, suggestionResult: null,
    kanjiRelated: [], kanjiCharacter: '' };
  var els = {};
  var suggestionTimer;

  function byId(id) { return document.getElementById(id); }
  function firstGloss(word) {
    if (word.meaning) return word.meaning;
    var first = (word.senses || [])[0];
    return first ? (first.glosses || []).map(function (g) { return g.text; }).join('; ') : '';
  }
  function routePath(tab, query) {
    return '/search/' + tab + (query ? '/' + encodeURIComponent(query) : '');
  }
  function parseRoute() {
    var path = window.location.pathname;
    if (path === '/dictionary/index.html' || path === '/dictionary/' || path === '/dictionary') {
      var legacy = new URLSearchParams(window.location.search).get('q') || '';
      history.replaceState(null, '', routePath('word', legacy));
      return { tab: 'word', query: legacy };
    }
    var parts = path.split('/');
    var tab = tabs.indexOf(parts[2]) >= 0 ? parts[2] : 'word';
    var query = '';
    if (parts[2] === 'grammar_details') {
      try { return { tab: 'grammar', query: '', detailCode: decodeURIComponent(parts[3] || '') }; }
      catch (_err) { return { tab: 'grammar', query: '' }; }
    }
    try { query = decodeURIComponent(parts.slice(3).join('/')); } catch (_err) { query = ''; }
    return { tab: tab, query: query };
  }
  function navigate(tab, query, id) {
    history.pushState(null, '', routePath(tab, query));
    state.selectedId = id == null ? null : String(id);
    renderRoute();
  }
  function navigateGrammar(code) {
    history.pushState(null, '', '/search/grammar_details/' + encodeURIComponent(code));
    renderRoute();
  }
  function setShell(home) {
    els.hero.hidden = !home;
    els.homePanels.hidden = !home;
    els.layout.hidden = home;
    els.layout.classList.toggle('is-grammar', state.tab === 'grammar');
    els.layout.classList.toggle('is-grammar-direct', state.tab === 'grammar' && !!state.detailCode);
    els.other.hidden = true;
    els.clear.hidden = !els.input.value;
    Array.prototype.forEach.call(els.tabs.querySelectorAll('[data-tab]'), function (button) {
      var active = button.dataset.tab === state.tab;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-selected', String(active));
    });
  }
  function showEmpty() { els.detail.innerHTML = ui.emptyStateHtml(); }
  function showLoading(value) { els.status.hidden = !value; }

  function vocabRowHtml(word) {
    var id = word.id == null ? '' : String(word.id);
    return '<button type="button" class="dict-vocab-row" data-word="' + esc(word.expression) +
      '" data-vocab-id="' + esc(id) + '"><span class="dict-vocab-row__head"><strong class="dict-vocab-row__expression">' +
      esc(word.expression) + '</strong><span class="dict-vocab-row__reading">' + esc(word.reading || '') +
      '</span>' + ui.jlptBadgeHtml(word.jlptLevel) + '</span><span class="dict-vocab-row__gloss">' +
      esc(firstGloss(word)) + '</span></button>';
  }
  function renderOtherResults() {
    var list = state.result && state.result.vocabularyResults && state.result.vocabularyResults.content || [];
    var others = list.filter(function (word) {
      var current = state.word;
      return current && current.id != null ? String(word.id) !== String(current.id)
        : word.expression !== (current && current.expression);
    });
    var page = state.result && state.result.vocabularyResults;
    var hasMore = !!page && page.last === false;
    var hasOthers = others.length > 0 || hasMore || state.page > 0;
    var sideKanji = byId('dict-side-kanji');
    var hasKanji = state.tab === 'word' && !sideKanji.hidden && sideKanji.innerHTML !== '';
    els.other.hidden = state.tab !== 'word' || (!hasOthers && !hasKanji);
    byId('dict-other-title').hidden = !hasOthers;
    els.vocabList.hidden = !hasOthers;
    byId('dict-other-title').textContent = 'Kết quả khác';
    els.vocabList.innerHTML = others.map(vocabRowHtml).join('');
    els.otherPagination.hidden = false;
    els.otherPagination.innerHTML = page ? pagerHtml('data-other-page', state.page, 0, hasMore) : '';
  }

  /**
   * Numbered pager shared by the word and grammar lists. Word search only knows whether a next page exists
   * (totalPages = 0), so it shows the pages reached so far plus the next one.
   */
  function pagerHtml(attr, page, totalPages, hasMore) {
    var last = totalPages ? totalPages - 1 : (hasMore ? page + 1 : page);
    if (last <= 0) return '';
    function button(target, label, options) {
      options = options || {};
      return '<button type="button"' + (options.icon ? ' class="dict-pagination__icon"' : '') + ' ' + attr + '="' + target + '"' +
        (options.disabled || options.current ? ' disabled' : '') + (options.current ? ' aria-current="page"' : '') +
        ' aria-label="' + esc(options.label || 'Trang ' + (target + 1)) + '">' + label + '</button>';
    }
    var start = Math.max(0, page - 2);
    var end = Math.min(last, page + 2);
    var html = button(page - 1, '<i class="fa-solid fa-chevron-left" aria-hidden="true"></i>',
      { icon: true, disabled: page === 0, label: 'Trang trước' });
    if (start > 0) html += button(0, '1') + (start > 1 ? '<span class="dict-pagination__gap">…</span>' : '');
    for (var target = start; target <= end; target++) html += button(target, String(target + 1), { current: target === page });
    if (totalPages && end < last) {
      html += (end < last - 1 ? '<span class="dict-pagination__gap">…</span>' : '') + button(last, String(last + 1));
    }
    return html + button(page + 1, '<i class="fa-solid fa-chevron-right" aria-hidden="true"></i>',
      { icon: true, disabled: page >= last, label: 'Trang sau' });
  }

  function sensesHtml(senses) {
    var groups = [];
    (senses || []).forEach(function (sense) {
      var pos = (sense.partsOfSpeech || []).join(', ');
      var last = groups[groups.length - 1];
      if (!last || last.pos !== pos) groups.push(last = { pos: pos, senses: [] });
      last.senses.push(sense);
    });
    return groups.map(function (group) {
      return '<section class="dict-word-section dict-meaning"><h2><i class="fa-solid fa-book-open"></i> ' +
        esc(group.pos || 'Ý nghĩa') + '</h2>' + group.senses.map(function (sense) {
          return '<div class="dict-meaning__sense">' + (sense.domains || []).map(function (domain) {
            return '<span class="dict-domain">' + esc(domain) + '</span>';
          }).join('') + (sense.glosses || []).map(function (gloss) {
            return '<p class="dict-meaning__gloss">' + esc(gloss.text) + '</p>';
          }).join('') + (sense.usageNote ? '<small>' + esc(sense.usageNote) + '</small>' : '') + '</div>';
        }).join('') + '</section>';
    }).join('');
  }
  function actionButton(label, icon, extraClass) {
    return '<button type="button" class="dict-action ' + (extraClass || '') + '" data-coming-soon>' +
      (icon ? '<i class="fa-solid fa-' + icon + '"></i> ' : '') + esc(label) + '</button>';
  }
  function relatedRowHtml(word) {
    return '<button type="button" class="dict-related-row" data-word="' + esc(word.expression) + '"' +
      (word.id == null ? '' : ' data-vocab-id="' + esc(String(word.id)) + '"') + '>' +
      '<span class="dict-related-row__head"><strong lang="ja">' + esc(word.expression) + '</strong>' +
      '<span class="dict-related-row__reading" lang="ja">' + esc(word.reading || '') + '</span>' +
      ui.jlptBadgeHtml(word.jlptLevel) + '</span>' +
      '<span class="dict-related-row__meaning">' + esc(word.meaning || firstGloss(word)) + '</span></button>';
  }
  function relatedWordsUrl(kanji, exclude) {
    var params = new URLSearchParams({ kanji: kanji.join('') });
    if (exclude) params.set('exclude', exclude);
    return '/dictionary/words.html?' + params.toString();
  }
  function kanjiMiniHtml(kanji) {
    var readings = kanji.readings || [];
    var on = readings.filter(function (r) { return r.readingType === 'ON'; }).map(function (r) { return r.readingText; }).join('、');
    var kun = readings.filter(function (r) { return r.readingType === 'KUN'; }).map(function (r) { return r.readingText; }).join('、');
    return '<article class="dict-kanji-mini"><div class="dict-kanji-mini__glyph" data-stroke-for="' + esc(kanji.character) +
      '">' + esc(kanji.character) + '</div><div><h3>' + esc(kanji.character) + ' <small>' + esc(kanji.hanViet || '') +
      '</small></h3><p>On: ' + esc(on || '—') + '</p><p>Kun: ' + esc(kun || '—') + '</p>' +
      '<div class="dict-kanji-mini__actions"><button type="button" class="dict-text-button" data-kanji="' + esc(kanji.character) + '">Xem chi tiết</button>' +
      '<a class="dict-text-button" href="/dictionary/kanji-map?kanji=' + encodeURIComponent(kanji.character) +
      '" aria-label="Xem lược đồ chữ ' + esc(kanji.character) + '">Xem lược đồ</a></div></div></article>';
  }
  async function fillWordKanji(word, token) {
    var composition = word.kanjiComposition || [];
    var details = await Promise.all(composition.map(function (part) {
      return window.DictApi.kanjiDetail(part.character).catch(function () {
        return word.id == null ? (window.DictMock.kanji[part.character] || null) : null;
      });
    }));
    if (token !== state.token) return;
    // The word's kanji live at the top of the right column, above the other search results.
    var host = byId('dict-side-kanji');
    var available = details.filter(Boolean);
    if (host) {
      host.hidden = !available.length;
      host.innerHTML = available.length ? '<h2>Hán tự trong từ</h2><div class="dict-kanji-mini-grid">' +
        available.map(kanjiMiniHtml).join('') + '</div>' : '';
      available.forEach(function (kanji) {
        if (!kanji.strokeSvgUri) return;
        var box = Array.prototype.find.call(host.querySelectorAll('[data-stroke-for]'), function (node) {
          return node.dataset.strokeFor === kanji.character;
        });
        if (!box) return;
        window.DictStrokeOrder.mount(box, kanji.strokeSvgUri).then(function (replay) {
          if (token !== state.token || !box.isConnected) return;
          if (!replay) box.innerHTML = '<img class="dict-stroke-order__fallback" src="' + esc(kanji.strokeSvgUri) + '" alt="Thứ tự nét ' + esc(kanji.character) + '">';
        });
      });
    }
    renderOtherResults();
  }

  function renderWord(word, token) {
    var data = word;
    state.word = data;
    var pos = [];
    (data.senses || []).forEach(function (sense) { pos.push.apply(pos, sense.partsOfSpeech || []); });
    var conjugations = window.DictConjugation.conjugate(data.expression, pos);
    var html = '<article class="dict-word-card"><header class="dict-word-head"><div><h1>' + esc(data.expression) +
      (data.verified || data.usageScope === 'TEXTBOOK' || data.usageScope === 'BOTH'
        ? ' <i class="fa-solid fa-circle-check dict-verified" title="Đã xác minh"></i>' : '') +
      '</h1><p>「' + esc(data.reading || '') + '」' +
      (data.hanViet ? '「' + esc(data.hanViet.toUpperCase()) + '」' : '') +
      '</p></div><div class="dict-word-head__actions">' +
      '<button type="button" class="dict-action" data-save-word aria-haspopup="dialog" aria-controls="dict-notebook-popover" aria-expanded="false"><i class="fa-solid fa-plus"></i> Lưu</button>' +
      ui.speakerButtonHtml(data.reading || data.expression) + '</div></header>' +
      '<div class="dict-pronunciation">' + ui.speakerButtonHtml(data.reading || data.expression) +
      (data.pitchAccent == null ? esc(data.reading || '') : ui.pitchAccentHtml(data.reading, data.pitchAccent)) + '</div>' +
      (data.pitchAccent == null ? '' : '<div class="dict-pitch-source"' +
        (data.pitchAccentSource === 'KANJIUM' ? ' title="The pitch accent notation was provided by Uros O. through his free database."' : '') + '>' +
        (data.pitchAccentSource === 'UNIDIC_CWJ' ? 'Trọng âm: UniDic (NINJAL)' :
          data.pitchAccentSource === 'KANJIUM' ? 'Trọng âm: Kanjium – Uros O. (CC BY-SA 4.0)' : '') + '</div>') +
      '<div class="dict-actions">' + '<button type="button" class="dict-action dict-action--ai" data-tutor-ask="' +
      esc('Giải thích từ 「' + data.expression + '」: nghĩa, cách đọc và ví dụ.') + '"><i class="fa-solid fa-sparkles"></i> Giải nghĩa AI</button>' +
      actionButton('Luyện phát âm', 'microphone') + '</div>' + ui.jlptBadgeHtml(data.jlptLevel) +
      sensesHtml(data.senses);
    if (data.exampleJa) html += '<div class="dict-word-section dict-example">' +
      '<p lang="ja">' + esc(data.exampleJa) + ui.speakerButtonHtml(data.exampleJa) + '</p>' +
      '<p>' + esc(data.exampleVi || '') + '</p></div>';
    html += '<section class="dict-word-section"><h2>Tra cứu chuyên sâu với AI</h2><div class="dict-chip-row">' +
      // Mỗi nút gửi một câu hỏi soạn sẵn cho gia sư AI (tutor-widget.js); gia sư chỉ trả lời theo dữ liệu J-LAS.
      [['Cụm từ cố định', 'Từ 「{w}」 hay đi kèm với những từ nào? Nêu các cụm từ thường gặp.'],
        ['Lỗi phổ biến', 'Người học hay mắc lỗi gì khi dùng từ 「{w}」?'],
        ['Cách dùng', 'Từ 「{w}」 dùng như thế nào? Cho ví dụ.'],
        ['Từ trái nghĩa', 'Từ trái nghĩa của 「{w}」 là gì?'],
        ['Đoạn văn minh họa', 'Cho các câu ví dụ có từ 「{w}」.']]
        .map(function (chip) {
          return '<button type="button" class="dict-ai-chip" data-tutor-ask="' + esc(chip[1].replace('{w}', data.expression)) + '">' +
            esc(chip[0]) + '</button>';
        }).join('') +
      '</div></section>' + (data.id == null ? '' : '<div data-contributions></div>');
    if (conjugations) html += '<section class="dict-word-section"><h2>Chia động từ</h2><div class="dict-table-wrap"><table class="dict-conjugation">' +
      '<thead><tr><th>Tên thể (形)</th><th>Từ</th></tr></thead><tbody>' + conjugations.map(function (row) {
        return '<tr><td>' + esc(row.label) + '</td><td lang="ja">' + esc(row.form) + '</td></tr>';
      }).join('') + '</tbody></table></div></section>';
    if ((data.antonyms || []).length) html += '<section class="dict-word-section"><h2>Từ trái nghĩa</h2>' +
      data.antonyms.map(relatedRowHtml).join('') + '</section>';
    html += '</article>';
    els.detail.innerHTML = html;
    var contribHost = els.detail.querySelector('[data-contributions]');
    if (contribHost) {
      contribHost.dataset.vocabularyId = data.id;
      loadContributions(contribHost, 0, token);
    }
    renderOtherResults();
    fillWordKanji(data, token);
  }
  function renderKanjiResults() {
    var results = state.result && state.result.kanjiResults || [];
    var host = byId('dict-side-kanji');
    els.other.hidden = !results.length;
    host.hidden = !results.length;
    host.innerHTML = results.length ? '<h2>Kết quả tra cứu kanji</h2><div class="dict-vocab-list">' + results.map(function (kanji) {
      return '<button type="button" class="dict-vocab-row dict-kanji-result-row"' +
        ' data-kanji-pick="' + esc(kanji.character) + '" aria-pressed="false">' +
        '<strong class="dict-kanji-result-row__glyph" lang="ja">' + esc(kanji.character) + '</strong>' +
        '<small class="dict-kanji-result-row__han-viet">' + esc((kanji.hanViet || '').toUpperCase()) + '</small></button>';
    }).join('') + '</div>' : '';
    selectKanjiResult();
    byId('dict-other-title').hidden = true;
    els.vocabList.hidden = true;
    els.otherPagination.hidden = true;
    els.otherPagination.innerHTML = '';
  }
  function selectKanjiResult() {
    Array.prototype.forEach.call(byId('dict-side-kanji').querySelectorAll('[data-kanji-pick]'), function (row) {
      var selected = row.dataset.kanjiPick === state.kanjiCharacter;
      row.classList.toggle('is-selected', selected);
      row.setAttribute('aria-pressed', String(selected));
    });
  }
  function renderKanjiRelated() {
    els.other.hidden = false;
    byId('dict-other-title').hidden = false;
    byId('dict-other-title').textContent = 'Từ vựng chứa Kanji';
    els.vocabList.hidden = false;
    els.vocabList.innerHTML = (state.kanjiRelated.length
      ? state.kanjiRelated.slice(0, 8).map(relatedRowHtml).join('')
      : '<p class="dict-detail__empty">Chưa có từ vựng liên quan.</p>') +
      (state.kanjiCharacter ? '<a class="dict-text-button" href="' +
        esc(relatedWordsUrl([state.kanjiCharacter])) + '">Xem thêm</a>' : '');
    els.otherPagination.hidden = true;
    els.otherPagination.innerHTML = '';
  }

  async function loadContributions(host, page, token) {
    try {
      var result = await window.DictApi.vocabularyContributions(host.dataset.vocabularyId, page, 5);
      if (token === state.token && host.isConnected) {
        host.dataset.page = result.page;
        host.innerHTML = ui.contributionsHtml(result);
      }
      return result;
    } catch (_err) {
      if (token === state.token && host.isConnected) ui.toast('Không thể tải ý kiến đóng góp');
    }
  }

  function isLoggedIn() {
    return !!(window.JlasAuth && window.JlasAuth.isLoggedIn && window.JlasAuth.isLoggedIn());
  }

  // Save-to-notebook popover anchored to the "Lưu" button (Pinterest-style board picker).
  var notebookToken = 0;
  var notebookAnchor = null;
  var notebookCloseTimer = null;
  var notebookSaved = new Set();
  var notebookBooks = [];
  var notebookWordId = null;
  var notebookBusy = false;
  var RECENT_NOTEBOOKS_LIMIT = 3;

  function notebookKey(bookId, wordId) { return String(wordId == null ? notebookWordId : wordId) + ':' + String(bookId); }
  function notebookPopover() { return byId('dict-notebook-popover'); }
  function notebookOpen() { return !notebookPopover().hidden; }
  function foldText(value) {
    return String(value || '').normalize('NFD').replace(/[̀-ͯ]/g, '')
      .replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().trim();
  }

  function recentNotebookKey() {
    var user = window.JlasAuth && typeof window.JlasAuth.getUser === 'function' ? window.JlasAuth.getUser() : null;
    return 'jlas.dict.recentNotebooks.' + (user && (user.id || user.userId) || 'me');
  }
  function recentNotebookIds() {
    try {
      var ids = JSON.parse(localStorage.getItem(recentNotebookKey()) || '[]');
      return Array.isArray(ids) ? ids.map(String) : [];
    } catch (_err) { return []; }
  }
  function rememberNotebook(bookId) {
    try {
      var ids = recentNotebookIds().filter(function (id) { return id !== String(bookId); });
      ids.unshift(String(bookId));
      localStorage.setItem(recentNotebookKey(), JSON.stringify(ids.slice(0, 10)));
    } catch (_err) { /* Per-viewer convenience only. */ }
  }

  function positionNotebookPopover() {
    var popover = notebookPopover();
    if (popover.hidden) return;
    if (!notebookAnchor || !notebookAnchor.isConnected) { closeNotebookPopover(false); return; }
    if (window.matchMedia('(max-width: 560px)').matches) {
      popover.style.top = ''; popover.style.left = ''; return;
    }
    var gap = 8, margin = 16;
    var rect = notebookAnchor.getBoundingClientRect();
    var width = popover.offsetWidth, height = popover.offsetHeight;
    var left = Math.min(Math.max(margin, rect.right - width), window.innerWidth - width - margin);
    var below = rect.bottom + gap;
    var top = below + height <= window.innerHeight - margin || rect.top - gap - height < margin
      ? below : rect.top - gap - height;
    popover.style.left = Math.round(left) + 'px';
    popover.style.top = Math.round(Math.max(margin, top)) + 'px';
  }

  function closeNotebookPopover(restoreFocus) {
    var popover = notebookPopover();
    if (popover.hidden) return;
    notebookToken++;
    window.clearTimeout(notebookCloseTimer);
    notebookCloseTimer = null;
    popover.hidden = true;
    if (notebookAnchor && notebookAnchor.isConnected) {
      notebookAnchor.setAttribute('aria-expanded', 'false');
      if (restoreFocus !== false) notebookAnchor.focus();
    }
    notebookAnchor = null;
  }

  function notebookStatus(message, kind) {
    var status = byId('dict-notebook-status');
    status.textContent = message || '';
    status.dataset.state = kind || '';
  }

  function showNotebookView(view) {
    var popover = notebookPopover();
    popover.querySelector('[data-notebook-view="list"]').hidden = view !== 'list';
    byId('dict-notebook-create').hidden = view !== 'create';
    notebookStatus('');
    positionNotebookPopover();
  }

  function notebookRowHtml(book) {
    var saved = notebookSaved.has(notebookKey(book.id));
    var name = String(book.name || '');
    return '<button type="button" class="dict-save-row" data-notebook-id="' + esc(book.id) + '"' +
      (saved ? ' disabled aria-label="' + esc(name) + ', đã lưu"' : '') + '>' +
      '<span class="dict-save-tile" aria-hidden="true">' + esc(name.trim().charAt(0).toUpperCase() || '#') + '</span>' +
      '<span class="dict-save-row__text"><span class="dict-save-row__name">' + esc(name) + '</span>' +
      '<span class="dict-save-row__count">' + esc(book.wordCount || 0) + ' từ</span></span>' +
      (saved ? '<span class="dict-save-row__done"><i class="fa-solid fa-check" aria-hidden="true"></i> Đã lưu</span>'
        : '<span class="dict-save-row__action" aria-hidden="true">Lưu</span>') +
      '</button>';
  }

  function renderNotebookList() {
    var list = byId('dict-notebook-list');
    var query = byId('dict-notebook-search').value.trim();
    var folded = foldText(query);
    if (!notebookBooks.length && !query) {
      list.innerHTML = '<p class="dict-notebook-message">Chưa có sổ tay. Hãy tạo sổ tay đầu tiên.</p>';
      positionNotebookPopover();
      return;
    }
    var html = '';
    if (folded) {
      var matches = notebookBooks.filter(function (book) { return foldText(book.name).indexOf(folded) >= 0; });
      html = matches.length
        ? '<p class="dict-save-section">Kết quả</p>' + matches.map(notebookRowHtml).join('')
        : '<p class="dict-notebook-message">Không tìm thấy sổ tay</p>';
      var exact = notebookBooks.some(function (book) { return foldText(book.name) === folded; });
      if (!exact && query.length <= 120) {
        html += '<button type="button" class="dict-save-row" data-notebook-create-query>' +
          '<span class="dict-save-tile dict-save-tile--plus" aria-hidden="true"><i class="fa-solid fa-plus"></i></span>' +
          '<span class="dict-save-row__text"><span class="dict-save-row__name">Tạo sổ tay «' + esc(query) + '»</span></span></button>';
      }
    } else {
      var bookById = {};
      notebookBooks.forEach(function (book) { bookById[String(book.id)] = book; });
      var recent = recentNotebookIds().map(function (id) { return bookById[id]; }).filter(Boolean)
        .slice(0, RECENT_NOTEBOOKS_LIMIT);
      if (recent.length) html += '<p class="dict-save-section">Gần đây</p>' + recent.map(notebookRowHtml).join('');
      html += '<p class="dict-save-section">Tất cả sổ tay</p>' + notebookBooks.map(notebookRowHtml).join('');
    }
    list.innerHTML = html;
    positionNotebookPopover();
  }

  async function loadNotebooks(token) {
    var loading = byId('dict-notebook-loading');
    var errorBox = byId('dict-notebook-error');
    loading.hidden = false;
    errorBox.hidden = true;
    byId('dict-notebook-list').innerHTML = '';
    notebookStatus('');
    positionNotebookPopover();
    try {
      var books = await window.DictApi.notebooks();
      if (token !== notebookToken) return;
      notebookBooks = books || [];
      loading.hidden = true;
      renderNotebookList();
    } catch (error) {
      if (token !== notebookToken) return;
      loading.hidden = true;
      errorBox.hidden = false;
      errorBox.querySelector('p').textContent = error.message || 'Không thể tải sổ tay lúc này.';
      positionNotebookPopover();
    }
  }

  function openNotebookChooser(button) {
    if (!isLoggedIn()) { ui.toast('Vui lòng đăng nhập để lưu từ vào sổ tay.'); return; }
    if (!state.word || state.word.id == null) { ui.toast('Không thể lưu từ này lúc này.'); return; }
    if (notebookOpen()) { closeNotebookPopover(); return; }
    var token = ++notebookToken;
    notebookAnchor = button;
    notebookWordId = state.word.id;
    notebookBusy = false;
    notebookBooks = [];
    var form = byId('dict-notebook-create');
    form.reset();
    Array.prototype.forEach.call(form.querySelectorAll('button, input'), function (control) { control.disabled = false; });
    byId('dict-notebook-search').value = '';
    byId('dict-notebook-word').textContent = state.word.expression +
      (state.word.reading ? ' · ' + state.word.reading : '');
    notebookPopover().hidden = false;
    showNotebookView('list');
    button.setAttribute('aria-expanded', 'true');
    byId('dict-notebook-search').focus();
    loadNotebooks(token);
  }

  function finishNotebookSave(book, token, wordId) {
    notebookSaved.add(notebookKey(book.id, wordId));
    rememberNotebook(book.id);
    book.wordCount = (Number(book.wordCount) || 0) + 1;
    ui.toast('Đã thêm vào sổ tay «' + book.name + '»');
    if (token !== notebookToken) return;
    renderNotebookList();
    notebookStatus('Đã thêm vào sổ tay «' + book.name + '».', 'success');
    notebookCloseTimer = window.setTimeout(function () { closeNotebookPopover(); }, 600);
  }

  async function saveToNotebook(book, rowButton) {
    if (notebookBusy || notebookCloseTimer) return;
    notebookBusy = true;
    if (rowButton) rowButton.disabled = true;
    var token = notebookToken;
    var wordId = notebookWordId;
    try {
      await window.DictApi.addToNotebook(book.id, wordId);
      finishNotebookSave(book, token, wordId);
    } catch (error) {
      if (token === notebookToken) notebookStatus(error.message || 'Không thể lưu từ lúc này.', 'error');
    } finally {
      if (token === notebookToken) {
        notebookBusy = false;
        if (rowButton && rowButton.isConnected && !notebookSaved.has(notebookKey(book.id))) rowButton.disabled = false;
      }
    }
  }

  async function createNotebookAndSave(name) {
    if (notebookBusy || notebookCloseTimer) return;
    name = String(name || '').trim();
    if (!name || name.length > 120) { notebookStatus('Tên sổ tay phải từ 1 đến 120 ký tự.', 'error'); return; }
    var form = byId('dict-notebook-create');
    notebookBusy = true;
    Array.prototype.forEach.call(form.querySelectorAll('button, input'), function (control) { control.disabled = true; });
    var token = notebookToken;
    var wordId = notebookWordId;
    var created = null;
    try {
      created = await window.DictApi.createNotebook(name);
      if (token === notebookToken) {
        // Keep the new notebook selectable even if adding the word fails, so a retry never creates a duplicate.
        notebookBooks.push(created);
        byId('dict-notebook-search').value = '';
        showNotebookView('list');
        renderNotebookList();
      }
      await window.DictApi.addToNotebook(created.id, wordId);
      finishNotebookSave(created, token, wordId);
    } catch (error) {
      if (token === notebookToken) {
        notebookStatus(created
          ? 'Đã tạo sổ tay. Chưa lưu được từ: ' + (error.message || 'Vui lòng thử lại.') + ' Chọn sổ vừa tạo để thử lại.'
          : error.message || 'Không thể tạo sổ tay lúc này.', 'error');
        if (!created) byId('dict-notebook-name').focus();
      }
    } finally {
      if (token === notebookToken) {
        notebookBusy = false;
        Array.prototype.forEach.call(form.querySelectorAll('button, input'), function (control) { control.disabled = false; });
      }
    }
  }

  function initNotebookPopover() {
    var popover = notebookPopover();
    var form = byId('dict-notebook-create');
    var input = byId('dict-notebook-name');
    var search = byId('dict-notebook-search');
    search.addEventListener('input', renderNotebookList);
    popover.addEventListener('click', function (event) {
      if (event.target.closest('[data-notebook-close]')) { closeNotebookPopover(); return; }
      if (notebookCloseTimer) return;
      if (event.target.closest('[data-notebook-retry]') && !notebookBusy) { loadNotebooks(notebookToken); return; }
      if (event.target.closest('[data-notebook-new]')) {
        if (notebookBusy) return;
        input.value = search.value.trim().slice(0, 120);
        showNotebookView('create'); input.focus(); return;
      }
      if (event.target.closest('[data-notebook-back]')) {
        if (notebookBusy) return;
        showNotebookView('list'); search.focus(); return;
      }
      if (event.target.closest('[data-notebook-create-query]')) { createNotebookAndSave(search.value); return; }
      var choice = event.target.closest('[data-notebook-id]');
      if (!choice || choice.disabled) return;
      var book = notebookBooks.find(function (item) { return String(item.id) === choice.dataset.notebookId; });
      if (book) saveToNotebook(book, choice);
    });
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      createNotebookAndSave(input.value);
    });
    popover.addEventListener('keydown', function (event) {
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      var rows = Array.prototype.filter.call(popover.querySelectorAll('.dict-save-row:not(:disabled)'),
        function (row) { return !row.closest('[hidden]'); });
      if (!rows.length) return;
      event.preventDefault();
      var index = rows.indexOf(document.activeElement);
      var next = event.key === 'ArrowDown' ? index + 1 : index - 1;
      if (index < 0) next = event.key === 'ArrowDown' ? 0 : rows.length - 1;
      rows[(next + rows.length) % rows.length].focus();
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && notebookOpen()) { event.preventDefault(); closeNotebookPopover(); }
    });
    document.addEventListener('mousedown', function (event) {
      if (!notebookOpen()) return;
      if (popover.contains(event.target) || (notebookAnchor && notebookAnchor.contains(event.target))) return;
      closeNotebookPopover(false);
    });
    window.addEventListener('resize', positionNotebookPopover);
    window.addEventListener('scroll', positionNotebookPopover, true);
  }

  async function handleContributionClick(event) {
    var button = event.target.closest('[data-contribution-page], [data-contribution-vote], [data-contribution-report], [data-contribution-send]');
    if (!button) return false;
    var host = button.closest('[data-contributions]');
    if (!host) return false;
    if (button.hasAttribute('data-contribution-page')) {
      loadContributions(host, Number(button.dataset.contributionPage), state.token);
      return true;
    }
    if (button.hasAttribute('data-contribution-report')) {
      if (!isLoggedIn()) { ui.toast('Vui lòng đăng nhập để báo cáo'); return true; }
      if (!window.confirm('Báo cáo ý kiến này? Ý kiến sẽ bị ẩn cho đến khi được kiểm duyệt.')) return true;
      button.disabled = true;
      try {
        await window.DictApi.reportContribution(button.dataset.contributionId);
        ui.toast('Đã báo cáo. Ý kiến đã được ẩn để chờ kiểm duyệt.');
        var page = Number(host.dataset.page || 0);
        var result = await loadContributions(host, page, state.token);
        if (result && !result.content.length && page > 0 && host.isConnected) {
          await loadContributions(host, page - 1, state.token);
        }
      } catch (error) { ui.toast(error.message || 'Không thể báo cáo ý kiến'); }
      finally { button.disabled = false; }
      return true;
    }
    if (!isLoggedIn()) { ui.toast('Vui lòng đăng nhập để đóng góp ý kiến'); return true; }
    button.disabled = true;
    try {
      if (button.hasAttribute('data-contribution-vote')) {
        var vote = Number(button.dataset.contributionVote);
        await window.DictApi.voteContribution(button.dataset.contributionId,
          button.getAttribute('aria-pressed') === 'true' ? 0 : vote);
        await loadContributions(host, Number(host.dataset.page || 0), state.token);
      } else {
        var textarea = host.querySelector('textarea');
        var content = textarea && textarea.value.trim();
        if (!content) { ui.toast('Vui lòng nhập ý kiến đóng góp'); return true; }
        await window.DictApi.addVocabularyContribution(host.dataset.vocabularyId, content);
        await loadContributions(host, 0, state.token);
      }
    } catch (error) { ui.toast(error.message || 'Không thể gửi ý kiến đóng góp'); }
    finally { button.disabled = false; }
    return true;
  }

  var escapeHtml = esc;
  function jlptBadgeClass(level) {
    return 'dict-badge dict-badge--jlpt dict-badge--' + String(level || '').toLowerCase();
  }

  function kanjiReadingCardsHtml(readings) {
    var labels = {
      ON: { title: 'Onyomi', subtitle: 'Âm On' },
      KUN: { title: 'Kunyomi', subtitle: 'Âm Kun' },
      OTHER: { title: 'Cách đọc khác', subtitle: 'Khác' }
    };
    var groups = {};
    (readings || []).forEach(function (reading) {
      if (String(reading.readingType).toLowerCase() === 'nanori') return;
      var type = labels[reading.readingType] ? reading.readingType : 'OTHER';
      (groups[type] = groups[type] || []).push(reading);
    });
    var types = Object.keys(labels).filter(function (type) { return groups[type]; });
    if (!types.length) return '<p class="dict-detail__empty">Chưa có cách đọc được ghi nhận.</p>';
    return '<div class="dict-kanji-readings">' + types.map(function (type) {
      return '<article class="dict-kanji-reading-card">' +
        '<div class="dict-kanji-reading-card__head">' +
          '<div><h3>' + labels[type].title + '</h3><span>' + labels[type].subtitle + '</span></div>' +
        '</div>' +
        '<div class="dict-kanji-reading-card__values">' + groups[type].map(function (reading) {
          return '<span class="dict-kanji-reading-card__value">' + escapeHtml(reading.readingText) +
            (reading.common ? '<small>Phổ biến</small>' : '') + '</span>';
        }).join('') + '</div>' +
      '</article>';
    }).join('') + '</div>';
  }

  function detailActionsHtml() {
    return '' +
      '<div class="dict-detail__actions">' +
        '<span class="dict-icon-btn" title="Phát âm · Sắp ra mắt"><i class="fa-solid fa-volume-high"></i></span>' +
        '<span class="dict-icon-btn" title="Lưu từ · Sắp ra mắt"><i class="fa-regular fa-bookmark"></i></span>' +
      '</div>';
  }

  function renderKanjiDetail(kanji) {
    var relatedVocabulary = kanji.relatedVocabulary || [];
    state.kanjiRelated = relatedVocabulary;
    state.kanjiCharacter = kanji.character;
    var radical = kanji.radical;
    var radicalHtml = radical
      ? '<div class="dict-kanji-radical">' +
          '<span class="dict-kanji-radical__glyph">' + escapeHtml(radical.character) + '</span>' +
          '<div class="dict-kanji-radical__body">' +
            '<span class="dict-kanji-radical__label">Bộ thủ</span>' +
            '<strong>' + escapeHtml(radical.nameVi || radical.nameJa || ('Bộ #' + radical.radicalNumber)) + '</strong>' +
            (radical.nameJa && radical.nameVi ? '<span>' + escapeHtml(radical.nameJa) + '</span>' : '') +
            (radical.meaningVi ? '<p>' + escapeHtml(radical.meaningVi) + '</p>' : '') +
          '</div>' +
          '<div class="dict-kanji-radical__meta">' +
            (radical.radicalNumber ? '<span>#' + radical.radicalNumber + '</span>' : '') +
            (radical.strokeCount ? '<span>' + radical.strokeCount + ' nét</span>' : '') +
          '</div>' +
        '</div>'
      : '<p class="dict-detail__empty">Chưa có thông tin bộ thủ.</p>';

    els.detail.innerHTML = '' +
      '<article class="dict-kanji-detail">' +
        '<header class="dict-kanji-detail__hero">' +
          '<div class="dict-kanji-glyph-panel">' +
            '<div class="dict-kanji-glyph-panel__top">' +
              '<span>Hán tự</span>' +
              (kanji.jlptLevel ? '<span class="' + jlptBadgeClass(kanji.jlptLevel) + '">' + escapeHtml(kanji.jlptLevel) + '</span>' : '') +
            '</div>' +
            '<div class="dict-kanji-glyph" aria-label="Hán tự ' + escapeHtml(kanji.character) + '">' + escapeHtml(kanji.character) + '</div>' +
            '<dl class="dict-kanji-facts">' +
              (kanji.hanViet ? '<div><dt>Hán Việt</dt><dd>' + escapeHtml(kanji.hanViet) + '</dd></div>' : '') +
              (kanji.strokeCount ? '<div><dt>Số nét</dt><dd>' + kanji.strokeCount + '</dd></div>' : '') +
            '</dl>' +
          '</div>' +
          '<div class="dict-kanji-semantic">' +
            '<div class="dict-kanji-semantic__head">' +
              '<div><span class="dict-kanji-kicker">Ý nghĩa</span>' +
                '<h1>' + escapeHtml(kanji.meaningVi || 'Chưa có nghĩa được ghi nhận') + '</h1></div>' +
              detailActionsHtml() +
            '</div>' +
            '<section class="dict-kanji-semantic__section" aria-labelledby="dict-kanji-readings-title">' +
              '<div class="dict-kanji-section-heading"><h2 id="dict-kanji-readings-title">Cách đọc</h2>' +
                '<span>Onyomi và Kunyomi</span></div>' +
              kanjiReadingCardsHtml(kanji.readings) +
            '</section>' +
            '<section class="dict-kanji-semantic__section" aria-labelledby="dict-kanji-radical-title">' +
              '<div class="dict-kanji-section-heading"><h2 id="dict-kanji-radical-title">Bộ thủ</h2></div>' +
              radicalHtml +
            '</section>' +
          '</div>' +
        '</header>' +

        '<section class="dict-kanji-section dict-kanji-stroke" aria-labelledby="dict-kanji-stroke-title">' +
          '<div class="dict-kanji-section-heading dict-kanji-section-heading--action">' +
            '<div><h2 id="dict-kanji-stroke-title">Thứ tự nét</h2>' +
              '<p>' + (kanji.strokeCount ? kanji.strokeCount + ' nét · ' : '') + 'Hoạt ảnh từ dữ liệu KanjiVG</p></div>' +
            (kanji.strokeSvgUri ? '<button type="button" id="dict-stroke-replay" class="btn btn--ghost btn--sm">' +
              '<i class="fa-solid fa-rotate-right"></i> Phát lại</button>' : '') +
          '</div>' +
          '<div class="dict-kanji-stroke__stage">' +
            (kanji.strokeSvgUri
              ? '<div id="dict-stroke-order" class="dict-stroke-order dict-kanji-stroke__canvas"></div>'
              : '<div class="dict-kanji-stroke__empty"><i class="fa-solid fa-pen-nib"></i>' +
                  '<p>Chưa có dữ liệu thứ tự nét cho Hán tự này.</p></div>') +
          '</div>' +
        '</section>' +

        '<footer class="dict-kanji-cta">' +
          '<div><span class="dict-kanji-cta__icon"><i class="fa-solid fa-graduation-cap"></i></span>' +
            '<div><strong>Luyện ghi nhớ ' + escapeHtml(kanji.character) + '</strong>' +
              '<p>Bài luyện viết đang được phát triển và sẽ sớm ra mắt.</p></div></div>' +
          '<div class="dict-kanji-cta__actions">' +
            '<button type="button" class="btn btn--primary" disabled title="Sắp ra mắt">Luyện viết · Sắp ra mắt</button>' +
          '</div>' +
        '</footer>' +
      '</article>';

    renderKanjiRelated();
    selectKanjiResult();
    if (kanji.strokeSvgUri) {
      var container = byId('dict-stroke-order');
      window.DictStrokeOrder.mount(container, kanji.strokeSvgUri).then(function (replay) {
        var replayBtn = byId('dict-stroke-replay');
        if (!replay) {
          // Animation thất bại (ví dụ mạng lỗi) -> fallback về ảnh tĩnh thay vì để trống.
          if (!container.isConnected) return;
          container.innerHTML = '<img class="dict-stroke-order__fallback" src="' + escapeHtml(kanji.strokeSvgUri) +
            '" alt="Thứ tự nét ' + escapeHtml(kanji.character) + '">';
          // .btn (buttons.css) forces display:inline-flex, which would ignore [hidden];
          // remove the element outright instead of trying to hide it.
          if (replayBtn) replayBtn.remove();
          return;
        }
        if (replayBtn) replayBtn.addEventListener('click', replay);
      });
    }
  }

  async function loadWord(result, token) {
    var list = result && result.vocabularyResults && result.vocabularyResults.content || [];
    var mock = window.DictMock.words[state.query];
    var chosen = state.selectedId ? list.find(function (v) { return String(v.id) === state.selectedId; }) : null;
    if (state.selectedId && !chosen) chosen = { id: state.selectedId };
    if (!chosen) chosen = list.find(function (v) { return v.expression === state.query; }) || list[0];
    if (!chosen) { if (mock) renderWord(mock, token); else showEmpty(); return; }
    try {
      var detail = await window.DictApi.vocabularyDetail(chosen.id);
      if (token !== state.token) return;
      renderWord(detail, token);
    } catch (_err) {
      if (token !== state.token) return;
      if (mock) renderWord(mock, token); else showEmpty();
    }
  }
  async function loadKanji(result, token, pick) {
    var first = Array.from(state.query).find(function (ch) { return /[\u3400-\u9fff]/.test(ch); });
    var character = pick || first || ((result && result.kanjiResults || [])[0] || {}).character;
    if (!character) { showEmpty(); return; }
    var request = ++state.kanjiDetailToken;
    try {
      var kanji = await window.DictApi.kanjiDetail(character);
      if (token === state.token && request === state.kanjiDetailToken) renderKanjiDetail(kanji);
    } catch (_err) {
      if (token !== state.token || request !== state.kanjiDetailToken) return;
      var mock = window.DictMock.kanji[character];
      if (mock) renderKanjiDetail(mock);
      else { state.kanjiCharacter = ''; selectKanjiResult(); showEmpty(); }
    }
  }

  function grammarBadge(item) {
    return item.unreviewed ? '<span class="dict-grammar__review">chưa duyệt</span>' : '';
  }
  function grammarRow(item, related) {
    var selected = !related && state.selectedGrammarCode === item.code;
    return '<button type="button" class="dict-grammar-row' + (selected ? ' is-selected' : '') +
      '" data-grammar-code="' + esc(item.code) + '"' + (related ? ' data-grammar-related="true"' : '') +
      ' aria-pressed="' + String(selected) + '">' +
      '<span class="dict-grammar-row__top"><strong lang="ja">' + esc(item.pattern) + '</strong>' +
      '<span class="dict-grammar__level">' + esc(item.level || '') + '</span>' + grammarBadge(item) + '</span>' +
      '<small>' + esc(item.meaningVi || '') + '</small></button>';
  }
  function grammarSection(title, value) {
    return value ? '<section class="dict-grammar__section"><h2>' + esc(title) + '</h2><p>' + esc(value) + '</p></section>' : '';
  }
  function grammarTokens(tokens, fallback) {
    if (!tokens || !tokens.length) return esc(fallback || '');
    return tokens.map(function (token) {
      return token.reading ? '<ruby>' + esc(token.text) + '<rt>' + esc(token.reading) + '</rt></ruby>' : esc(token.text);
    }).join('');
  }
  function renderGrammarDetail(grammar) {
    var variants = (grammar.variants || []).filter(function (value) { return value !== grammar.pattern; });
    var exampleHtml = (grammar.examples || []).map(function (example) {
      return '<li><p lang="ja">' + grammarTokens(example.tokens, example.ja) +
        (example.audioUrl ? ' <button type="button" class="dict-grammar__audio" data-grammar-audio="' +
          esc(example.audioUrl) + '" aria-label="Phát âm ví dụ">▶</button>' : '') + '</p>' +
        (example.vi ? '<small>' + esc(example.vi) + '</small>' : '') + '</li>';
    }).join('');
    var relatedHtml = (grammar.related || []).map(function (item) { return grammarRow(item, true); }).join('');
    var occurrenceHtml = (grammar.textbookOccurrences || []).map(function (item) {
      return '<li><a href="' + esc(item.url) + '">' + esc(item.textbookTitle) +
        ' <span class="dict-grammar__level">' + esc(item.level || '') + '</span> · ' +
        esc(item.lessonTitle) + '</a><p lang="ja">' + esc(item.pattern) + '</p>' +
        (item.meaning ? '<p>' + esc(item.meaning) + '</p>' : '') +
        (item.explanation ? '<p>' + esc(item.explanation) + '</p>' : '') +
        (item.exampleJa ? '<p lang="ja">' + esc(item.exampleJa) + '</p>' : '') +
        (item.exampleVi ? '<small>' + esc(item.exampleVi) + '</small>' : '') + '</li>';
    }).join('');
    els.detail.innerHTML = '<article class="dict-grammar"><header class="dict-grammar__hero"><div>' +
      '<p>Ngữ pháp · <span class="dict-grammar__level">' + esc(grammar.level || '') + '</span> ' + grammarBadge(grammar) + '</p>' +
      '<h1 lang="ja">' + esc(grammar.pattern) + '</h1>' +
      (grammar.meaningVi ? '<p>' + esc(grammar.meaningVi) + '</p>' : '') + '</div></header>' +
      (variants.length ? grammarSection('Biến thể', variants.join(' · ')) : '') +
      grammarSection('Cấu trúc', grammar.formation) + grammarSection('Cách dùng', grammar.usageVi) +
      grammarSection('Sắc thái', grammar.nuanceVi) + grammarSection('Lỗi thường gặp', grammar.commonMistakesVi) +
      (exampleHtml ? '<section class="dict-grammar__section"><h2>Ví dụ</h2><ul class="dict-grammar__examples">' + exampleHtml + '</ul></section>' : '') +
      (relatedHtml ? '<section class="dict-grammar__section"><h2>Ngữ pháp liên quan</h2><div class="dict-grammar__related">' + relatedHtml + '</div></section>' : '') +
      (occurrenceHtml ? '<section class="dict-grammar__section"><h2>Xuất hiện trong giáo trình</h2><ul class="dict-grammar__occurrences">' + occurrenceHtml + '</ul></section>' : '') +
      '</article>';
  }
  function renderGrammarResults(page) {
    var items = page.content || [];
    els.other.hidden = false;
    byId('dict-side-kanji').hidden = true;
    byId('dict-other-title').hidden = false;
    els.vocabList.hidden = false;
    byId('dict-other-title').textContent = 'Kết quả ngữ pháp';
    els.vocabList.innerHTML = items.length ? items.map(function (item) { return grammarRow(item, false); }).join('') : '<p>Không tìm thấy ngữ pháp phù hợp.</p>';
    els.otherPagination.hidden = false;
    els.otherPagination.innerHTML = pagerHtml('data-grammar-page', page.page, page.totalPages, !page.last);
  }
  async function selectGrammarResult(code, token) {
    state.selectedGrammarCode = code;
    var request = ++state.grammarDetailToken;
    Array.prototype.forEach.call(els.vocabList.querySelectorAll('[data-grammar-code]'), function (row) {
      var selected = row.dataset.grammarCode === code;
      row.classList.toggle('is-selected', selected);
      row.setAttribute('aria-pressed', String(selected));
    });
    try {
      var detail = await window.DictApi.grammarDetail(code);
      if (token === state.token && request === state.grammarDetailToken) renderGrammarDetail(detail);
    } catch (_err) {
      if (token === state.token && request === state.grammarDetailToken) {
        els.detail.innerHTML = '<p class="dict-detail__empty">Không thể tải ngữ pháp lúc này.</p>';
      }
    } finally { if (token === state.token && request === state.grammarDetailToken) showLoading(false); }
  }
  async function loadGrammar(token, code) {
    try {
      if (code) {
        var direct = await window.DictApi.grammarDetail(code);
        if (token === state.token) renderGrammarDetail(direct);
        return;
      }
      var page = await window.DictApi.grammarSearch(state.query, 0, state.size);
      if (token !== state.token) return;
      renderGrammarResults(page);
      if (!page.content.length) { showEmpty(); return; }
      await selectGrammarResult(page.content[0].code, token);
    } catch (_err) {
      if (token === state.token) els.detail.innerHTML = '<p class="dict-detail__empty">Không thể tải ngữ pháp lúc này.</p>';
    } finally { if (token === state.token) showLoading(false); }
  }
  async function loadGrammarPage(page) {
    if (page < 0) return;
    var token = state.token;
    try {
      var result = await window.DictApi.grammarSearch(state.query, page, state.size);
      if (token === state.token) {
        state.page = page;
        state.selectedGrammarCode = null;
        renderGrammarResults(result);
        if (result.content.length) await selectGrammarResult(result.content[0].code, token);
        else showEmpty();
      }
    } catch (_err) { if (token === state.token) ui.toast('Không thể tải kết quả'); }
  }
  async function renderRoute() {
    var route = parseRoute();
    state.tab = route.tab;
    state.query = route.query;
    state.detailCode = route.detailCode || null;
    state.selectedGrammarCode = null;
    state.kanjiCharacter = '';
    state.grammarDetailToken++;
    state.page = 0;
    state.otherPageToken++;
    var token = ++state.token;
    els.input.value = state.query;
    setShell(!state.query && !state.detailCode);
    var sideKanji = byId('dict-side-kanji');
    sideKanji.hidden = true;
    sideKanji.innerHTML = '';
    if (state.tab !== 'grammar') {
      byId('dict-other-title').textContent = 'Kết quả khác';
      els.vocabList.innerHTML = '';
      els.otherPagination.innerHTML = '';
    }
    hideSuggestions();
    if (!state.query && !state.detailCode) { showLoading(false); loadDailyWords(); return; }
    els.detail.innerHTML = '';
    state.result = null;
    if (state.tab === 'grammar') {
      showLoading(true);
      await loadGrammar(token, state.detailCode);
      return;
    }
    if (state.tab === 'example') {
      showEmpty(); return;
    }
    showLoading(true);
    var result = null;
    try { result = await window.DictApi.search(state.query, 0, state.size); } catch (_err) { /* Dữ liệu mẫu khi API không sẵn sàng. */ }
    if (token !== state.token) return;
    state.result = result;
    if (state.tab === 'kanji') renderKanjiResults();
    try {
      if (state.tab === 'word') await loadWord(result, token);
      else await loadKanji(result, token);
    } finally {
      if (token === state.token) showLoading(false);
    }
  }

  function hideSuggestions() { els.suggest.hidden = true; els.suggest.innerHTML = ''; }
  function renderSuggestions(result) {
    if (document.activeElement !== els.input) return;
    var words = result && result.vocabularyResults && result.vocabularyResults.content || [];
    var kanji = result && result.kanjiResults || [];
    var items = words.slice(0, 5).map(function (word) {
      return '<li><button type="button" class="dict-suggest__item" data-word="' + esc(word.expression) +
        '" data-vocab-id="' + esc(word.id) + '"><strong>' + esc(word.expression) + '</strong> ' +
        esc(word.reading || '') + '<small>' + esc(firstGloss(word)) + '</small></button></li>';
    });
    if (kanji.length) items.push('<li><button type="button" class="dict-suggest__item" data-kanji="' +
      esc(kanji[0].character) + '">' + esc(kanji[0].character) + ' · Hán tự</button></li>');
    els.suggest.innerHTML = items.join('');
    els.suggest.hidden = !items.length;
  }
  async function searchSuggestions() {
    var query = els.input.value.trim();
    if (!query) { hideSuggestions(); return; }
    try {
      var result = await window.DictApi.search(query, 0, 6);
      if (els.input.value.trim() !== query) return;
      state.suggestionResult = result;
      renderSuggestions(result);
    } catch (_err) { hideSuggestions(); }
  }
  async function loadOtherPage(page) {
    if (page < 0 || !state.result || !state.result.vocabularyResults) return;
    var token = state.token;
    var request = ++state.otherPageToken;
    Array.prototype.forEach.call(els.otherPagination.querySelectorAll('button'), function (button) { button.disabled = true; });
    try {
      var result = await window.DictApi.search(state.query, page, state.size);
      if (token !== state.token || request !== state.otherPageToken) return;
      state.page = page;
      state.result = result;
      renderOtherResults();
    } catch (_err) {
      if (token === state.token && request === state.otherPageToken) {
        renderOtherResults();
        ui.toast('Không thể tải kết quả');
      }
    }
  }
  function commitSearch() {
    var query = els.input.value.trim();
    if (/^[a-zA-Z][a-zA-Z'-]*$/.test(query) && window.DictRomaji) {
      query = window.DictRomaji.toHiragana(query) || query;
    }
    els.input.value = query;
    navigate(state.tab, query);
  }
  function compactRecent(entries) {
    var seen = new Set();
    var candidates = [];
    (entries || []).forEach(function (entry) {
      var query = entry.queryText || '';
      if (entry.resultCount === 0 || (/^[a-zA-Z ]+$/.test(query) && query.trim().length < 3)) return;
      if (seen.has(query)) return;
      seen.add(query);
      candidates.push(entry);
    });
    var filtered = candidates.filter(function (entry) {
      var query = (entry.queryText || '').toLowerCase();
      if (candidates.some(function (other) {
        var otherQuery = (other.queryText || '').toLowerCase();
        return otherQuery.length > query.length && otherQuery.indexOf(query) === 0;
      })) return false;
      if (!/^[a-zA-Z]+$/.test(query) || !window.DictRomaji) return true;
      var hiragana = window.DictRomaji.toHiragana(query).replace(/[a-zA-Z]+$/, '');
      return !hiragana || !candidates.some(function (other) {
        return /[\u3040-\u30ff\u3400-\u9fff]/.test(other.queryText || '') &&
          (other.queryText || '').indexOf(hiragana) === 0;
      });
    });
    var kept = [];
    filtered.forEach(function (entry) {
      var query = entry.queryText || '';
      var prefix = query.slice(0, 2).toLowerCase();
      var searchedAt = Date.parse(entry.searchedAt);
      if (prefix.length === 2 && Number.isFinite(searchedAt) && kept.some(function (newer) {
        var newerAt = Date.parse(newer.searchedAt);
        return (newer.queryText || '').slice(0, 2).toLowerCase() === prefix &&
          Number.isFinite(newerAt) && newerAt >= searchedAt && newerAt - searchedAt <= 3 * 60 * 1000;
      })) return;
      kept.push(entry);
    });
    return kept;
  }
  async function loadRecent() {
    if (!window.JlasAuth || !window.JlasAuth.isLoggedIn || !window.JlasAuth.isLoggedIn()) {
      els.recentEmpty.hidden = false; return;
    }
    try {
      var entries = compactRecent(await window.DictApi.history()).slice(0, 8);
      els.recentEmpty.hidden = !!entries.length;
      if (!entries.length) els.recentEmpty.textContent = 'Chưa có lịch sử tra cứu nào.';
      var cards = await Promise.all(entries.map(async function (entry) {
        var detail = null;
        if (entry.selectedVocabularyId) {
          try { detail = await window.DictApi.vocabularyDetail(entry.selectedVocabularyId); }
          catch (_err) { /* Hiển thị truy vấn khi chi tiết không còn sẵn. */ }
        }
        if (detail) return '<button type="button" class="dict-recent-card" data-word="' +
          esc(detail.expression) + '" data-vocab-id="' + esc(detail.id || entry.selectedVocabularyId) + '">' +
          '<span class="dict-recent-card__head"><span class="dict-recent-card__expression"' +
          (/[\u3040-\u30ff\u3400-\u9fff\u3005\u3006]/.test(detail.expression) ? ' lang="ja"' : '') + '>' +
          esc(detail.expression) + '</span>' + ui.jlptBadgeHtml(detail.jlptLevel) + '</span>' +
          '<span class="dict-recent-card__gloss">' + esc(firstGloss(detail).split(/[;；\r\n]/, 1)[0].trim()) + '</span></button>';
        return '<button type="button" class="dict-recent-card" data-word="' + esc(entry.queryText) +
          '"><span class="dict-recent-card__head"><span class="dict-recent-card__expression"' +
          (/[\u3040-\u30ff\u3400-\u9fff\u3005\u3006]/.test(entry.queryText) ? ' lang="ja"' : '') + '>' +
          esc(entry.queryText) + '</span></span>' +
          '<span class="dict-recent-card__gloss">' + esc(entry.resultCount || 0) +
          ' kết quả</span></button>';
      }));
      els.recent.innerHTML = cards.join('');
    } catch (_err) {
      els.recentEmpty.hidden = false;
      els.recentEmpty.textContent = 'Không thể tải lịch sử tra cứu lúc này.';
    }
  }
  async function loadDailyWords() {
    if (!isLoggedIn()) {
      els.dailyBadge.hidden = true;
      els.dailySubtitle.textContent = '';
      els.dailyEmpty.textContent = 'Đăng nhập để nhận gợi ý từ vựng mỗi ngày.';
      els.dailyEmpty.hidden = false;
      return;
    }
    try {
      var result = await window.DictApi.dailyWords();
      var words = result.words || [];
      els.dailyBadge.hidden = !words.length;
      els.dailyBadge.textContent = result.source === 'TARGET_LEVEL' ? (result.level || '') :
        result.source === 'HISTORY' ? 'Từ bạn đã tra' : '';
      els.dailySubtitle.textContent = result.source === 'TARGET_LEVEL' ?
        'Theo mốc ' + (result.level || '') + ' · đổi mới mỗi ngày' :
        result.source === 'HISTORY' ? 'Từ những từ bạn đã tra · đổi mới mỗi ngày' : '';
      els.dailyList.innerHTML = words.map(function (word) {
        return '<button type="button" class="dict-daily-row" data-word="' + esc(word.expression) +
          '" data-vocab-id="' + esc(word.id) + '"><span class="dict-daily-row__head">' +
          '<span class="dict-daily-row__term"><span class="dict-daily-row__expression" lang="ja">' +
          esc(word.expression) + '</span><span class="dict-daily-row__reading" lang="ja">' +
          esc(word.reading || '') + '</span></span>' + ui.jlptBadgeHtml(word.jlptLevel) +
          '</span><span class="dict-daily-row__meaning" title="' + esc(word.meaning || '') + '">' +
          esc(word.meaning || '') + '</span></button>';
      }).join('');
      els.dailyEmpty.hidden = !!words.length;
      if (!words.length) els.dailyEmpty.textContent =
        'Chọn mốc trình độ hướng tới hoặc tra vài từ tiếng Nhật để nhận gợi ý.';
    } catch (_err) {
      els.dailyBadge.hidden = true;
      els.dailySubtitle.textContent = '';
      els.dailyEmpty.textContent = 'Không thể tải từ vựng mỗi ngày lúc này.';
      els.dailyEmpty.hidden = false;
    }
  }
  function init() {
    els.hero = byId('dict-hero'); els.homePanels = byId('dict-home-panels');
    els.input = byId('dict-search-input'); els.clear = byId('dict-search-clear');
    els.suggest = byId('dict-suggest'); els.tabs = byId('dict-tabs');
    els.layout = byId('dict-results-layout'); els.detail = byId('dict-detail-pane');
    els.other = byId('dict-other-results'); els.vocabList = byId('dict-vocab-list');
    els.otherPagination = byId('dict-other-pagination'); els.status = byId('dict-status');
    els.recent = byId('dict-recent-list'); els.recentEmpty = byId('dict-recent-empty');
    els.dailyList = byId('dict-daily-list'); els.dailyBadge = byId('dict-daily-badge');
    els.dailyEmpty = byId('dict-daily-empty');
    els.dailySubtitle = byId('dict-daily-subtitle');
    initNotebookPopover();
    els.input.addEventListener('input', function () {
      els.clear.hidden = !els.input.value;
      window.clearTimeout(suggestionTimer);
      suggestionTimer = window.setTimeout(searchSuggestions, 250);
    });
    els.input.addEventListener('focus', function () { if (state.suggestionResult) renderSuggestions(state.suggestionResult); });
    els.input.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') hideSuggestions();
      if (event.key === 'Enter') { event.preventDefault(); commitSearch(); }
    });
    els.clear.addEventListener('click', function () {
      els.input.value = ''; els.clear.hidden = true; hideSuggestions(); els.input.focus();
    });
    els.tabs.addEventListener('click', function (event) {
      var button = event.target.closest('[data-tab]');
      if (button) navigate(button.dataset.tab, state.query);
    });
    document.addEventListener('click', function (event) {
      var saveWord = event.target.closest('[data-save-word]');
      if (saveWord) { openNotebookChooser(saveWord); return; }
      if (event.target.closest('[data-contribution-page], [data-contribution-vote], [data-contribution-report], [data-contribution-send]')) {
        handleContributionClick(event);
        return;
      }
      if (!event.target.closest('.dict-search-bar')) hideSuggestions();
      var tutorAsk = event.target.closest('[data-tutor-ask]');
      if (tutorAsk) {
        var tutor = window.JlasTutorWidget;
        if (!tutor || !tutor.open(tutorAsk.getAttribute('data-tutor-ask'))) {
          ui.toast(window.JlasAuth && window.JlasAuth.isLoggedIn() ? 'Gia sư AI đang tạm nghỉ, bạn thử lại sau nhé' : 'Đăng nhập để hỏi gia sư AI');
        }
        return;
      }
      var coming = event.target.closest('[data-coming-soon]');
      if (coming) ui.toast('Sắp ra mắt');
      var otherPage = event.target.closest('[data-other-page]');
      if (otherPage && !otherPage.disabled) { loadOtherPage(Number(otherPage.dataset.otherPage)); return; }
      var grammarPage = event.target.closest('[data-grammar-page]');
      if (grammarPage && !grammarPage.disabled) { loadGrammarPage(Number(grammarPage.dataset.grammarPage)); return; }
      var grammarAudio = event.target.closest('[data-grammar-audio]');
      if (grammarAudio) { new Audio(grammarAudio.dataset.grammarAudio).play().catch(function () { ui.toast('Không thể phát âm thanh'); }); return; }
      var grammar = event.target.closest('[data-grammar-code]');
      if (grammar) {
        if (grammar.dataset.grammarRelated) navigateGrammar(grammar.dataset.grammarCode);
        else { showLoading(true); selectGrammarResult(grammar.dataset.grammarCode, state.token); }
        return;
      }
      var kanjiPick = event.target.closest('[data-kanji-pick]');
      if (kanjiPick) { loadKanji(state.result, state.token, kanjiPick.dataset.kanjiPick); return; }
      var kanji = event.target.closest('[data-kanji]');
      if (kanji) { navigate('kanji', kanji.dataset.kanji); return; }
      var word = event.target.closest('[data-word]');
      if (word) navigate('word', word.dataset.word, word.dataset.vocabId || null);
    });
    byId('dict-open-handwriting').addEventListener('click', function () {
      if (window.DictHandwriting) window.DictHandwriting.clear();
      if (window.JlasUI) window.JlasUI.openModal('dict-handwriting-modal');
    });
    byId('dict-mode-radical').addEventListener('click', function () {
      if (window.DictRadical) window.DictRadical.open();
    });
    window.addEventListener('popstate', function () { state.selectedId = null; renderRoute(); });
    renderRoute();
    loadRecent();
  }
  document.addEventListener('DOMContentLoaded', init);
})();
