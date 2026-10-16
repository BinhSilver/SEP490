(function () {
  'use strict';

  var els = {};
  var state = {
    query: '', page: 0, size: 20, loadingMore: false, lastResult: null,
    activeTab: 'all', selectedType: null, selectedId: null,
    suggestionQuery: '',
    filters: { jlpt: 'ALL', pos: new Set() }
  };
  var DEBOUNCE_MS = 250;
  var debounceTimer = null;
  var SUGGEST_LIMIT = 6;
  var RECENT_LIMIT = 6;
  var RELATED_VOCAB_LIMIT = 8;

  function byId(id) { return document.getElementById(id); }

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function firstGloss(senses) {
    if (!senses || !senses.length) return '';
    var glosses = senses[0].glosses || [];
    return glosses.map(function (g) { return g.text; }).join('; ');
  }

  function jlptBadgeClass(level) {
    var key = String(level || '').toUpperCase();
    return 'dict-badge dict-badge--jlpt ' + (
      { N5: 'dict-badge--n5', N4: 'dict-badge--n4', N3: 'dict-badge--n3',
        N2: 'dict-badge--n2', N1: 'dict-badge--n1' }[key] || '');
  }

  /** Gộp toàn bộ nhãn từ loại (tiếng Việt) của mọi sense, dùng cho filter từ loại phía sidebar. */
  function vocabPosKey(vocab) {
    var labels = [];
    (vocab.senses || []).forEach(function (sense) {
      (sense.partsOfSpeech || []).forEach(function (p) { labels.push(p); });
    });
    return labels.join(' | ').toLowerCase();
  }

  // -------------------------------------------------------------------
  // Result row rendering (sidebar + suggestion dropdown share the markup)
  // -------------------------------------------------------------------

  function kanjiCardHtml(kanji) {
    var selected = state.selectedType === 'kanji' && state.selectedId === kanji.character;
    return '' +
      '<button type="button" class="dict-kanji-card' + (selected ? ' is-selected' : '') +
        '" data-kanji="' + escapeHtml(kanji.character) + '" data-jlpt="' + escapeHtml(kanji.jlptLevel || '') + '">' +
        '<span class="dict-kanji-card__char">' + escapeHtml(kanji.character) + '</span>' +
        (kanji.hanViet ? '<span class="dict-kanji-card__hanviet">' + escapeHtml(kanji.hanViet) + '</span>' : '') +
        (kanji.jlptLevel ? '<span class="' + jlptBadgeClass(kanji.jlptLevel) + '">' + escapeHtml(kanji.jlptLevel) + '</span>' : '') +
      '</button>';
  }

  function vocabRowHtml(vocab) {
    var gloss = vocab.meaning || firstGloss(vocab.senses) || '';
    var selected = state.selectedType === 'vocab' && state.selectedId === String(vocab.id);
    return '' +
      '<button type="button" class="dict-vocab-row' + (selected ? ' is-selected' : '') +
        '" data-vocab-id="' + vocab.id + '" data-jlpt="' + escapeHtml(vocab.jlptLevel || '') +
        '" data-pos="' + escapeHtml(vocabPosKey(vocab)) + '">' +
        '<div class="dict-vocab-row__head">' +
          '<span class="dict-vocab-row__expression">' + escapeHtml(vocab.expression) + '</span>' +
          '<span class="dict-vocab-row__reading">' + escapeHtml(vocab.reading) + '</span>' +
          (vocab.hanViet ? '<span class="dict-vocab-row__hanviet">' + escapeHtml(vocab.hanViet) + '</span>' : '') +
          (vocab.jlptLevel ? '<span class="' + jlptBadgeClass(vocab.jlptLevel) + '">' + escapeHtml(vocab.jlptLevel) + '</span>' : '') +
        '</div>' +
        (gloss ? '<div class="dict-vocab-row__gloss">' + escapeHtml(gloss) + '</div>' : '') +
      '</button>';
  }

  function usedInCardHtml(vocab) {
    var gloss = vocab.meaning || firstGloss(vocab.senses) || '';
    return '' +
      '<button type="button" class="dict-used-in-card" data-vocab-id="' + vocab.id + '">' +
        '<div class="dict-used-in-card__head">' +
          '<span class="dict-used-in-card__expression">' + escapeHtml(vocab.expression) + '</span>' +
          '<span class="dict-used-in-card__reading">' + escapeHtml(vocab.reading) + '</span>' +
          (vocab.jlptLevel ? '<span class="' + jlptBadgeClass(vocab.jlptLevel) + '">' + escapeHtml(vocab.jlptLevel) + '</span>' : '') +
        '</div>' +
        (gloss ? '<div class="dict-used-in-card__gloss">' + escapeHtml(gloss) + '</div>' : '') +
        (vocab.exampleJa ? '<div class="dict-used-in-card__example">' + escapeHtml(vocab.exampleJa) + '</div>' : '') +
      '</button>';
  }

  function suggestItemHtml(item) {
    var gloss = item.type === 'kanji'
      ? (item.data.hanViet || item.data.meaningVi || '')
      : (item.data.meaning || firstGloss(item.data.senses) || '');
    var expression = item.type === 'kanji' ? item.data.character : item.data.expression;
    var reading = item.type === 'kanji' ? '' : item.data.reading;
    return '' +
      '<li role="option">' +
        '<button type="button" class="dict-suggest__item" data-type="' + item.type +
          '" data-key="' + escapeHtml(item.key) + '">' +
          '<i class="fa-solid fa-magnifying-glass dict-suggest__icon" aria-hidden="true"></i>' +
          '<span class="dict-suggest__content">' +
            '<span class="dict-suggest__head">' +
              '<span class="dict-suggest__expression">' + escapeHtml(expression) + '</span>' +
              (reading ? '<span class="dict-suggest__reading">' + escapeHtml(reading) + '</span>' : '') +
            '</span>' +
            (gloss ? '<span class="dict-suggest__gloss">' + escapeHtml(gloss) + '</span>' : '') +
          '</span>' +
        '</button>' +
      '</li>';
  }

  // -------------------------------------------------------------------
  // Home / results view toggling
  // -------------------------------------------------------------------

  function setHomeMode(isHome) {
    els.hero.hidden = !isHome;
    els.homePanels.hidden = !isHome;
    els.searchModes.hidden = !isHome;
    els.searchZone.hidden = !isHome;
    if (isHome) {
      els.resultsHeader.hidden = true;
      els.tabs.hidden = true;
      els.resultsLayout.hidden = true;
      els.resultsLayout.classList.remove('is-detail-view');
      els.empty.hidden = true;
    }
  }

  function setDetailMode(title) {
    els.hero.hidden = true;
    els.homePanels.hidden = true;
    els.searchModes.hidden = true;
    els.searchZone.hidden = true;
    els.tabs.hidden = true;
    els.empty.hidden = true;
    els.resultsHeader.hidden = false;
    els.resultsTitle.textContent = title || 'Chi tiết từ vựng';
    els.resultsLayout.hidden = false;
    els.resultsLayout.classList.add('is-detail-view');
  }

  function resetToHome() {
    window.clearTimeout(debounceTimer);
    els.searchInput.value = '';
    state.query = '';
    state.suggestionQuery = '';
    state.lastResult = null;
    history.replaceState(null, '', window.location.pathname);
    hideSuggestions();
    setHomeMode(true);
  }

  // -------------------------------------------------------------------
  // Search + tabs + sidebar
  // -------------------------------------------------------------------

  function setActiveTab(tab) {
    state.activeTab = tab;
    els.tabAll.classList.toggle('is-active', tab === 'all');
    els.tabAll.setAttribute('aria-selected', String(tab === 'all'));
    els.tabVocab.classList.toggle('is-active', tab === 'vocab');
    els.tabVocab.setAttribute('aria-selected', String(tab === 'vocab'));
    els.tabKanji.classList.toggle('is-active', tab === 'kanji');
    els.tabKanji.setAttribute('aria-selected', String(tab === 'kanji'));
    els.vocabSection.hidden = tab === 'kanji';
    els.kanjiSection.hidden = tab === 'vocab';
  }

  function renderResults(result, append) {
    var kanjiList = result.kanjiResults || [];
    var vocabPage = result.vocabularyResults || { content: [] };
    var vocabList = vocabPage.content || [];

    if (!append) {
      els.kanjiGrid.innerHTML = kanjiList.map(kanjiCardHtml).join('');
      els.vocabList.innerHTML = '';
    }
    vocabList.forEach(function (vocab) {
      els.vocabList.insertAdjacentHTML('beforeend', vocabRowHtml(vocab));
    });

    var vocabCount = els.vocabList.children.length;
    var hasAny = kanjiList.length > 0 || vocabCount > 0;

    els.resultsHeader.hidden = false;
    els.resultsTitle.textContent = 'Kết quả cho "' + result.query + '"';
    els.empty.hidden = hasAny;
    els.emptyQuery.textContent = result.query || '';
    els.loadMore.hidden = vocabPage.last !== false || vocabList.length === 0;
    els.tabs.hidden = !hasAny;
    els.resultsLayout.hidden = !hasAny;
    els.filterPanel.hidden = !hasAny;
    els.radicalPanel.hidden = kanjiList.length === 0;
    els.tabAllCount.textContent = hasAny ? '(' + (vocabCount + kanjiList.length) + ')' : '';
    els.tabVocabCount.textContent = vocabCount ? '(' + vocabCount + ')' : '';
    els.tabKanjiCount.textContent = kanjiList.length ? '(' + kanjiList.length + ')' : '';

    if (!append) {
      resetFilters();
      renderRadicalPanel(kanjiList);
    }

    if (!append && hasAny) {
      var defaultTab = vocabCount > 0 && kanjiList.length > 0 ? 'all' : (vocabCount > 0 ? 'vocab' : 'kanji');
      setActiveTab(defaultTab);
      // Master-detail: tự mở kết quả xếp hạng cao nhất (exact match được ranking ưu tiên
      // đứng đầu — xem DictionaryServiceImpl), giống Mazii luôn hiện sẵn 1 chi tiết.
      openTopResult(result);
    }
    state.lastResult = result;
  }

  /** Mở kết quả khớp nhất (đầu danh sách, đã được backend rank exact-match lên trước). */
  function openTopResult(result) {
    var vocabList = (result.vocabularyResults && result.vocabularyResults.content) || [];
    var kanjiList = result.kanjiResults || [];
    if (vocabList.length) return openVocabulary(vocabList[0].id);
    if (kanjiList.length) return openKanji(kanjiList[0].character);
    return Promise.resolve(false);
  }

  function showLoading(isLoading) {
    els.status.hidden = !isLoading;
  }

  async function runSearch(query, page, append) {
    if (!query) {
      setHomeMode(true);
      els.vocabList.innerHTML = '';
      els.kanjiGrid.innerHTML = '';
      els.loadMore.hidden = true;
      hideSuggestions();
      return;
    }
    setHomeMode(false);
    showLoading(true);
    try {
      var result = await window.DictApi.search(query, page, state.size);
      renderResults(result, append);
      if (!append) renderSuggestions(result);
    } catch (err) {
      els.resultsHeader.hidden = false;
      els.resultsTitle.textContent = 'Kết quả cho "' + query + '"';
      els.tabs.hidden = true;
      els.resultsLayout.hidden = true;
      els.filterPanel.hidden = true;
      els.radicalPanel.hidden = true;
      els.empty.hidden = false;
      els.emptyQuery.textContent = query;
    } finally {
      showLoading(false);
    }
  }

  async function commitQuery(query) {
    window.clearTimeout(debounceTimer);
    state.query = String(query || '').trim();
    state.page = 0;
    history.replaceState(null, '', state.query
      ? '?q=' + encodeURIComponent(state.query)
      : window.location.pathname);
    hideSuggestions();
    if (!state.query) {
      setHomeMode(true);
      return;
    }

    setHomeMode(false);
    showLoading(true);
    try {
      var result = await window.DictApi.search(state.query, 0, state.size);
      state.lastResult = result;
      var opened = await openTopResult(result);
      if (!opened) renderResults(result, false);
    } catch (err) {
      els.resultsHeader.hidden = false;
      els.resultsTitle.textContent = 'Không thể tải kết quả';
      els.tabs.hidden = true;
      els.resultsLayout.hidden = true;
      els.empty.hidden = false;
      els.emptyQuery.textContent = state.query;
      if (window.JlasUI && window.JlasUI.toast) {
        window.JlasUI.toast('Không thể tải chi tiết từ vựng. Hãy thử lại.', 'error');
      }
    } finally {
      showLoading(false);
    }
  }

  async function runSuggestionSearch() {
    var query = els.searchInput.value.trim();
    if (!query) {
      state.suggestionQuery = '';
      state.lastResult = null;
      hideSuggestions();
      return;
    }
    try {
      var result = await window.DictApi.search(query, 0, state.size);
      if (els.searchInput.value.trim() !== query) return;
      state.suggestionQuery = query;
      state.lastResult = result;
      renderSuggestions(result);
    } catch (err) {
      hideSuggestions();
    }
  }

  function scheduleSearch() {
    window.clearTimeout(debounceTimer);
    debounceTimer = window.setTimeout(runSuggestionSearch, DEBOUNCE_MS);
  }

  var ROMAJI_PATTERN = /^[a-zA-Z][a-zA-Z'-]*$/;

  /** Enter: chốt kết quả khớp nhất ngay, không chờ debounce, và đóng dropdown gợi ý. */
  function commitSearch() {
    hideSuggestions();
    var value = els.searchInput.value.trim();
    if (ROMAJI_PATTERN.test(value) && window.DictRomaji) {
      // Người dùng gõ Romaji (kiểu IME) -> hiển thị lại đúng Hiragana rồi tra theo đó.
      var hiragana = window.DictRomaji.toHiragana(value);
      if (hiragana && hiragana !== value) {
        els.searchInput.value = hiragana;
        value = hiragana;
      }
    }
    if (value === state.suggestionQuery && state.lastResult) {
      state.query = value;
      history.replaceState(null, '', '?q=' + encodeURIComponent(value));
      openTopResult(state.lastResult);
      return;
    }
    commitQuery(value);
  }

  function searchFor(queryText) {
    els.searchInput.value = queryText;
    return commitQuery(queryText);
  }

  // -------------------------------------------------------------------
  // Suggestion dropdown (live preview directly under the search box)
  // -------------------------------------------------------------------

  function renderSuggestions(result) {
    var vocabList = (result.vocabularyResults && result.vocabularyResults.content || [])
      .slice(0, SUGGEST_LIMIT).map(function (v) {
        return { type: 'vocab', key: String(v.id), data: v };
      });
    var kanjiList = (result.kanjiResults || []).slice(0, SUGGEST_LIMIT).map(function (k) {
      return { type: 'kanji', key: k.character, data: k };
    });
    var exactKanjiIndex = kanjiList.findIndex(function (item) {
      return item.key === result.query;
    });
    var items;
    if (exactKanjiIndex >= 0) {
      // Một Kanji khớp chính xác phải luôn chọn được, kể cả khi danh sách từ vựng đã
      // chiếm đủ giới hạn gợi ý. Enter vẫn ưu tiên từ vựng theo hành vi đã thống nhất.
      var exactKanji = kanjiList[exactKanjiIndex];
      items = [exactKanji].concat(vocabList, kanjiList.filter(function (_item, index) {
        return index !== exactKanjiIndex;
      })).slice(0, SUGGEST_LIMIT);
    } else if (kanjiList.length) {
      items = vocabList.slice(0, SUGGEST_LIMIT - 1).concat(kanjiList[0]);
    } else {
      items = vocabList.slice(0, SUGGEST_LIMIT);
    }
    if (!items.length || document.activeElement !== els.searchInput) {
      hideSuggestions();
      return;
    }
    els.suggest.innerHTML = items.map(suggestItemHtml).join('');
    els.suggest.hidden = false;
  }

  function hideSuggestions() {
    els.suggest.hidden = true;
    els.suggest.innerHTML = '';
  }

  // -------------------------------------------------------------------
  // Filter panel (JLPT tier + từ loại) — lọc client-side trên kết quả đã tải,
  // vì API search hiện chưa hỗ trợ tham số lọc facet.
  // -------------------------------------------------------------------

  function resetFilters() {
    state.filters = { jlpt: 'ALL', pos: new Set() };
    Array.prototype.forEach.call(els.filterJlpt.querySelectorAll('[data-jlpt]'), function (btn) {
      btn.classList.toggle('is-active', btn.getAttribute('data-jlpt') === 'ALL');
    });
    Array.prototype.forEach.call(els.filterPos.querySelectorAll('[data-pos]'), function (btn) {
      btn.classList.remove('is-active');
    });
    applyFilters();
  }

  function applyFilters() {
    var jlpt = state.filters.jlpt;
    var posGroups = Array.from(state.filters.pos);

    function matchPos(el) {
      if (!posGroups.length) return true;
      var itemPos = el.getAttribute('data-pos') || '';
      return posGroups.some(function (group) {
        return group.split('|').some(function (keyword) { return itemPos.indexOf(keyword) !== -1; });
      });
    }

    Array.prototype.forEach.call(els.vocabList.querySelectorAll('[data-vocab-id]'), function (row) {
      var jlptOk = jlpt === 'ALL' || row.getAttribute('data-jlpt') === jlpt;
      row.hidden = !(jlptOk && matchPos(row));
    });
    Array.prototype.forEach.call(els.kanjiGrid.querySelectorAll('[data-kanji]'), function (card) {
      card.hidden = !(jlpt === 'ALL' || card.getAttribute('data-jlpt') === jlpt);
    });
  }

  function initFilterPanel() {
    els.filterJlpt.addEventListener('click', function (event) {
      var btn = event.target.closest('[data-jlpt]');
      if (!btn) return;
      state.filters.jlpt = btn.getAttribute('data-jlpt');
      Array.prototype.forEach.call(els.filterJlpt.querySelectorAll('[data-jlpt]'), function (b) {
        b.classList.toggle('is-active', b === btn);
      });
      applyFilters();
    });
    els.filterPos.addEventListener('click', function (event) {
      var btn = event.target.closest('[data-pos]');
      if (!btn) return;
      var key = btn.getAttribute('data-pos');
      if (state.filters.pos.has(key)) { state.filters.pos.delete(key); btn.classList.remove('is-active'); }
      else { state.filters.pos.add(key); btn.classList.add('is-active'); }
      applyFilters();
    });
    els.filterReset.addEventListener('click', resetFilters);
  }

  // -------------------------------------------------------------------
  // Radical panel (bộ thủ của các Kanji có trong kết quả tra cứu)
  // -------------------------------------------------------------------

  function renderRadicalPanel(kanjiList) {
    var seen = {};
    var radicals = [];
    kanjiList.forEach(function (k) {
      if (k.radical && !seen[k.radical.character]) {
        seen[k.radical.character] = true;
        radicals.push(k.radical);
      }
    });
    els.radicalList.innerHTML = radicals.map(function (r) {
      return '<div class="dict-radical-card">' +
        '<div class="dict-radical-card__char">' + escapeHtml(r.character) + '</div>' +
        '<div class="dict-radical-card__name">' + escapeHtml(r.nameVi || r.nameJa || ('Bộ #' + r.radicalNumber)) + '</div>' +
      '</div>';
    }).join('');
  }

  // -------------------------------------------------------------------
  // Detail view (Kanji / Vocabulary)
  // -------------------------------------------------------------------

  function senseListHtml(senses) {
    if (!senses || !senses.length) return '<p class="dict-detail__empty">Chưa có nghĩa được ghi nhận.</p>';
    return senses.map(function (sense) {
      var glosses = (sense.glosses || []).map(function (g) { return escapeHtml(g.text); }).join('; ');
      var tags = [].concat(sense.partsOfSpeech || [], sense.domains || []).map(function (t) {
        return '<span class="dict-badge dict-badge--outline">' + escapeHtml(t) + '</span>';
      }).join(' ');
      return '<li>' + (tags ? '<div class="dict-detail__sense-tags">' + tags + '</div>' : '') +
        '<span>' + glosses + '</span>' +
        (sense.usageNote ? ' <em>(' + escapeHtml(sense.usageNote) + ')</em>' : '') + '</li>';
    }).join('');
  }

  function kanjiReadingCardsHtml(readings) {
    var labels = {
      ON: { title: 'Onyomi', subtitle: 'Âm On' },
      KUN: { title: 'Kunyomi', subtitle: 'Âm Kun' },
      NANORI: { title: 'Nanori', subtitle: 'Tên riêng' },
      OTHER: { title: 'Cách đọc khác', subtitle: 'Khác' }
    };
    var groups = {};
    (readings || []).forEach(function (reading) {
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

  function vocabularySenseCardsHtml(senses) {
    if (!senses || !senses.length) {
      return '<p class="dict-detail__empty">Chưa có nghĩa được ghi nhận.</p>';
    }
    return senses.map(function (sense, index) {
      var tags = [].concat(sense.partsOfSpeech || [], sense.domains || []).map(function (tag) {
        return '<span class="dict-badge dict-badge--outline">' + escapeHtml(tag) + '</span>';
      }).join('');
      var glosses = (sense.glosses || []).map(function (gloss) {
        return '<p class="dict-vocab-sense__gloss">' + escapeHtml(gloss.text) + '</p>';
      }).join('');
      var order = String(sense.senseOrder || (index + 1)).padStart(2, '0');
      return '<article class="dict-vocab-sense">' +
        '<div class="dict-vocab-sense__head">' +
          '<span class="dict-vocab-sense__number">' + order + '</span>' +
          '<div class="dict-vocab-sense__tags">' + tags + '</div>' +
        '</div>' +
        (glosses || '<p class="dict-detail__empty">Chưa có diễn giải cho nghĩa này.</p>') +
        (sense.usageNote ? '<p class="dict-vocab-sense__note">' + escapeHtml(sense.usageNote) + '</p>' : '') +
      '</article>';
    }).join('');
  }

  function vocabularyDetailActionsHtml() {
    return '<div class="dict-vocab-detail__actions">' +
      '<button type="button" class="btn btn--secondary" disabled title="Sắp ra mắt">' +
        '<i class="fa-solid fa-volume-high"></i> Nghe phát âm</button>' +
      '<button type="button" class="btn btn--ghost" disabled title="Sắp ra mắt">' +
        '<i class="fa-regular fa-bookmark"></i> Lưu từ</button>' +
      '<button type="button" class="btn btn--primary" disabled title="Sắp ra mắt">' +
        '<i class="fa-solid fa-plus"></i> Thêm vào bộ thẻ</button>' +
    '</div>';
  }

  async function renderVocabularyDetail(vocab) {
    state.selectedType = 'vocab';
    state.selectedId = String(vocab.id);
    setDetailMode('Chi tiết từ vựng');
    var example = vocab.exampleJa
      ? '<div class="dict-vocab-example">' +
          '<span>Ví dụ</span><p lang="ja">' + escapeHtml(vocab.exampleJa) + '</p>' +
          (vocab.exampleVi ? '<p>' + escapeHtml(vocab.exampleVi) + '</p>' : '') +
        '</div>'
      : '';

    els.detailPane.innerHTML = '<article class="dict-vocab-detail">' +
      '<header class="dict-vocab-detail__hero">' +
        '<div class="dict-vocab-detail__identity">' +
          '<p class="dict-vocab-detail__reading" lang="ja">' + escapeHtml(vocab.reading) +
            (vocab.readingRomaji ? '<span>' + escapeHtml(vocab.readingRomaji) + '</span>' : '') + '</p>' +
          '<h1 lang="ja">' + escapeHtml(vocab.expression) + '</h1>' +
          (vocab.hanViet ? '<p class="dict-vocab-detail__hanviet">' + escapeHtml(vocab.hanViet) + '</p>' : '') +
          '<div class="dict-detail__chips">' +
            (vocab.wordType ? '<span class="dict-badge dict-badge--outline">' + escapeHtml(vocab.wordType) + '</span>' : '') +
            (vocab.jlptLevel ? '<span class="' + jlptBadgeClass(vocab.jlptLevel) + '">' + escapeHtml(vocab.jlptLevel) + '</span>' : '') +
          '</div>' +
          (vocab.meaning ? '<p class="dict-vocab-detail__summary">' + escapeHtml(vocab.meaning) + '</p>' : '') +
        '</div>' +
        vocabularyDetailActionsHtml() +
      '</header>' +

      '<div class="dict-vocab-detail__layout">' +
        '<div class="dict-vocab-detail__main">' +
          '<section class="dict-vocab-section" aria-labelledby="dict-meaning-title">' +
            '<div class="dict-vocab-section__heading">' +
              '<h2 id="dict-meaning-title">Nghĩa và định nghĩa</h2>' +
              '<span>Dữ liệu từ điển</span>' +
            '</div>' +
            '<div class="dict-vocab-senses">' + vocabularySenseCardsHtml(vocab.senses) + '</div>' +
            example +
          '</section>' +
          (vocab.kanjiComposition && vocab.kanjiComposition.length
            ? '<section class="dict-vocab-section" aria-labelledby="dict-kanji-breakdown-title">' +
                '<div class="dict-vocab-section__heading"><h2 id="dict-kanji-breakdown-title">Cấu tạo Hán tự</h2>' +
                  '<span>Chọn Kanji để xem chi tiết</span></div>' +
                '<div id="dict-decomp-grid" class="dict-decomp-grid"><p class="dict-detail__empty">Đang tải...</p></div>' +
              '</section>'
            : '') +
        '</div>' +

        '<aside class="dict-vocab-detail__sidebar">' +
          '<section class="dict-vocab-learning">' +
            '<div class="dict-vocab-section__heading"><h2>Trạng thái học tập</h2>' +
              '<span>Sắp ra mắt</span></div>' +
            '<div class="dict-vocab-learning__empty"><i class="fa-solid fa-chart-line"></i>' +
              '<p>SRS và tiến độ ghi nhớ sẽ xuất hiện tại đây khi tính năng học tập được kết nối.</p></div>' +
          '</section>' +
          '<section id="dict-related-panel" class="dict-related-panel" hidden>' +
            '<div class="dict-vocab-section__heading"><h2>Từ vựng liên quan</h2></div>' +
            '<div id="dict-related-list" class="dict-vocab-list dict-vocab-list--nested"></div>' +
          '</section>' +
          '<section id="dict-detail-radical-panel" class="dict-related-panel" hidden>' +
            '<div class="dict-vocab-section__heading"><h2>Bộ thủ</h2></div>' +
            '<div id="dict-detail-radicals" class="dict-radical-list"></div>' +
          '</section>' +
        '</aside>' +
      '</div>' +
    '</article>';
    refreshSelectionHighlight();

    if (vocab.kanjiComposition && vocab.kanjiComposition.length) {
      await loadDecompositionAndRelated(vocab);
    }
  }

  /**
   * Kanji composition trong VocabularyResponse chỉ có character/jlptLevel — không có
   * nghĩa/âm đọc. Ta gọi thêm kanjiDetail() cho từng Hán tự cấu thành để: (1) hiển thị
   * mini-card cấu tạo Hán tự đầy đủ, (2) gộp relatedVocabulary của các Hán tự đó lại
   * thành danh sách "Từ vựng liên quan" (loại trừ chính từ đang xem) — không bịa dữ liệu,
   * toàn bộ lấy từ API kanji detail sẵn có.
   */
  async function loadDecompositionAndRelated(vocab) {
    var characters = vocab.kanjiComposition.map(function (k) { return k.character; });
    var details = await Promise.all(characters.map(function (ch) {
      return window.DictApi.kanjiDetail(ch).catch(function () { return null; });
    }));
    // Người dùng có thể đã chuyển sang mục khác trong lúc chờ fetch — bỏ qua nếu detail pane
    // không còn hiển thị đúng từ vựng này nữa (tránh ghi đè nhầm sang mục đang xem hiện tại).
    if (state.selectedType !== 'vocab' || state.selectedId !== String(vocab.id)) return;
    var grid = byId('dict-decomp-grid');
    if (!grid) return;

    grid.innerHTML = details.filter(Boolean).map(function (kanji) {
      var onyomi = (kanji.readings || []).filter(function (r) { return r.readingType === 'ON'; })
        .map(function (r) { return r.readingText; }).join('、');
      var kunyomi = (kanji.readings || []).filter(function (r) { return r.readingType === 'KUN'; })
        .map(function (r) { return r.readingText; }).join('、');
      return '<div class="dict-decomp-card">' +
        '<div class="dict-decomp-card__char">' + escapeHtml(kanji.character) + '</div>' +
        (kanji.meaningVi ? '<div class="dict-decomp-card__meaning">' + escapeHtml(kanji.meaningVi) + '</div>' : '') +
        (onyomi || kunyomi ? '<div class="dict-decomp-card__reading">' +
          (onyomi ? 'On: ' + escapeHtml(onyomi) + ' ' : '') + (kunyomi ? 'Kun: ' + escapeHtml(kunyomi) : '') +
          '</div>' : '') +
        '<button type="button" class="btn btn--ghost btn--sm" data-kanji="' + escapeHtml(kanji.character) + '">Xem chi tiết</button>' +
      '</div>';
    }).join('');

    var relatedMap = new Map();
    details.filter(Boolean).forEach(function (kanji) {
      (kanji.relatedVocabulary || []).forEach(function (v) {
        if (v.id !== vocab.id && !relatedMap.has(v.id)) relatedMap.set(v.id, v);
      });
    });
    var relatedList = Array.from(relatedMap.values()).slice(0, RELATED_VOCAB_LIMIT);
    var relatedPanel = byId('dict-related-panel');
    var relatedContainer = byId('dict-related-list');
    if (relatedPanel && relatedContainer && relatedList.length) {
      relatedContainer.innerHTML = relatedList.map(vocabRowHtml).join('');
      relatedPanel.hidden = false;
    }

    var radicalPanel = byId('dict-detail-radical-panel');
    var radicalContainer = byId('dict-detail-radicals');
    var radicalSeen = {};
    var radicals = details.filter(Boolean).map(function (kanji) { return kanji.radical; })
      .filter(function (radical) {
        if (!radical || radicalSeen[radical.character]) return false;
        radicalSeen[radical.character] = true;
        return true;
      });
    if (radicalPanel && radicalContainer && radicals.length) {
      radicalContainer.innerHTML = radicals.map(function (radical) {
        return '<div class="dict-radical-card">' +
          '<div class="dict-radical-card__char">' + escapeHtml(radical.character) + '</div>' +
          '<div class="dict-radical-card__name">' +
            escapeHtml(radical.nameVi || radical.nameJa || ('Bộ #' + radical.radicalNumber)) + '</div>' +
        '</div>';
      }).join('');
      radicalPanel.hidden = false;
    }
  }

  function renderKanjiDetail(kanji) {
    state.selectedType = 'kanji';
    state.selectedId = kanji.character;
    setDetailMode('Chi tiết Hán tự');
    var relatedVocabulary = kanji.relatedVocabulary || [];
    var related = relatedVocabulary.slice(0, RELATED_VOCAB_LIMIT).map(usedInCardHtml).join('');
    var radical = kanji.radical;
    var unicode = kanji.unicodeCode
      ? (String(kanji.unicodeCode).toUpperCase().indexOf('U+') === 0
        ? String(kanji.unicodeCode).toUpperCase()
        : 'U+' + String(kanji.unicodeCode).toUpperCase())
      : '';
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

    els.detailPane.innerHTML = '' +
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
              (unicode ? '<div><dt>Unicode</dt><dd>' + escapeHtml(unicode) + '</dd></div>' : '') +
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

        '<section class="dict-kanji-section" aria-labelledby="dict-kanji-vocab-title">' +
          '<div class="dict-kanji-section-heading dict-kanji-section-heading--action">' +
            '<div><h2 id="dict-kanji-vocab-title">Từ vựng chứa Kanji</h2>' +
              '<p>Chọn một từ để mở trang chi tiết từ vựng.</p></div>' +
            '<span>' + relatedVocabulary.length + ' kết quả</span>' +
          '</div>' +
          '<div class="dict-used-in-grid">' +
            (related || '<p class="dict-detail__empty">Chưa có từ vựng liên quan.</p>') +
          '</div>' +
        '</section>' +

        '<footer class="dict-kanji-cta">' +
          '<div><span class="dict-kanji-cta__icon"><i class="fa-solid fa-graduation-cap"></i></span>' +
            '<div><strong>Luyện ghi nhớ ' + escapeHtml(kanji.character) + '</strong>' +
              '<p>Bộ thẻ và bài luyện viết sẽ được bổ sung trong phiên bản tiếp theo.</p></div></div>' +
          '<div class="dict-kanji-cta__actions">' +
            '<button type="button" class="btn btn--secondary" disabled title="Sắp ra mắt">Thêm vào bộ thẻ</button>' +
            '<button type="button" class="btn btn--primary" disabled title="Sắp ra mắt">Luyện viết · Sắp ra mắt</button>' +
          '</div>' +
        '</footer>' +
      '</article>';
    refreshSelectionHighlight();

    if (kanji.strokeSvgUri) {
      var container = byId('dict-stroke-order');
      window.DictStrokeOrder.mount(container, kanji.strokeSvgUri).then(function (replay) {
        var replayBtn = byId('dict-stroke-replay');
        if (!replay) {
          // Animation thất bại (ví dụ mạng lỗi) -> fallback về ảnh tĩnh thay vì để trống.
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

  function refreshSelectionHighlight() {
    Array.prototype.forEach.call(els.vocabList.querySelectorAll('[data-vocab-id]'), function (row) {
      row.classList.toggle('is-selected',
        state.selectedType === 'vocab' && row.getAttribute('data-vocab-id') === state.selectedId);
    });
    Array.prototype.forEach.call(els.kanjiGrid.querySelectorAll('[data-kanji]'), function (card) {
      card.classList.toggle('is-selected',
        state.selectedType === 'kanji' && card.getAttribute('data-kanji') === state.selectedId);
    });
  }

  async function openVocabulary(id) {
    try {
      var vocab = await window.DictApi.vocabularyDetail(id);
      await renderVocabularyDetail(vocab);
      return true;
    } catch (err) {
      if (window.JlasUI && window.JlasUI.toast) {
        window.JlasUI.toast('Không thể tải chi tiết từ vựng. Hãy thử lại.', 'error');
      }
      return false;
    }
  }

  async function openKanji(character) {
    try {
      var kanji = await window.DictApi.kanjiDetail(character);
      renderKanjiDetail(kanji);
      return true;
    } catch (err) {
      if (window.JlasUI && window.JlasUI.toast) {
        window.JlasUI.toast('Không thể tải chi tiết Hán tự. Hãy thử lại.', 'error');
      }
      return false;
    }
  }

  function delegateClicks(container) {
    container.addEventListener('click', function (event) {
      var kanjiBtn = event.target.closest('[data-kanji]');
      if (kanjiBtn) { openKanji(kanjiBtn.getAttribute('data-kanji')); return; }
      var vocabBtn = event.target.closest('[data-vocab-id]');
      if (vocabBtn) { openVocabulary(vocabBtn.getAttribute('data-vocab-id')); }
    });
  }

  // -------------------------------------------------------------------
  // Trang chủ: lịch sử tra cứu gần đây (dùng /api/dictionary/history có sẵn)
  // -------------------------------------------------------------------

  function recentPlainCardHtml(history) {
    return '<button type="button" class="dict-recent-card" data-history-query="' + escapeHtml(history.queryText) + '">' +
      '<div class="dict-recent-card__head">' +
        '<span class="dict-recent-card__expression">' + escapeHtml(history.queryText) + '</span>' +
      '</div>' +
      '<div class="dict-recent-card__gloss">' + history.resultCount + ' kết quả</div>' +
    '</button>';
  }

  function recentRichCardHtml(history, vocab) {
    var gloss = vocab.meaning || firstGloss(vocab.senses) || '';
    return '<button type="button" class="dict-recent-card" data-history-query="' + escapeHtml(history.queryText) +
        '" data-history-vocab-id="' + vocab.id + '">' +
      '<div class="dict-recent-card__head">' +
        '<span class="dict-recent-card__expression">' + escapeHtml(vocab.expression) + '</span>' +
        (vocab.jlptLevel ? '<span class="' + jlptBadgeClass(vocab.jlptLevel) + '">' + escapeHtml(vocab.jlptLevel) + '</span>' : '') +
      '</div>' +
      '<div class="dict-recent-card__reading">' + escapeHtml(vocab.reading) + '</div>' +
      (gloss ? '<div class="dict-recent-card__gloss">' + escapeHtml(gloss) + '</div>' : '') +
    '</button>';
  }

  async function loadRecentLookups() {
    if (!window.JlasAuth || !window.JlasAuth.isLoggedIn || !window.JlasAuth.isLoggedIn()) {
      els.recentEmpty.hidden = false;
      return;
    }
    try {
      var history = await window.DictApi.history();
      var recent = (history || []).slice(0, RECENT_LIMIT);
      if (!recent.length) {
        els.recentEmpty.hidden = false;
        els.recentEmpty.textContent = 'Chưa có lịch sử tra cứu nào.';
        return;
      }
      var cardsHtml = await Promise.all(recent.map(async function (item) {
        if (item.selectedVocabularyId) {
          try {
            var vocab = await window.DictApi.vocabularyDetail(item.selectedVocabularyId);
            return recentRichCardHtml(item, vocab);
          } catch (err) { /* fall through to plain card */ }
        }
        return recentPlainCardHtml(item);
      }));
      els.recentList.innerHTML = cardsHtml.join('');
    } catch (err) {
      els.recentEmpty.hidden = false;
      els.recentEmpty.textContent = 'Không thể tải lịch sử tra cứu lúc này.';
    }
  }

  function initRecentLookups() {
    els.recentList.addEventListener('click', async function (event) {
      var card = event.target.closest('[data-history-query]');
      if (!card) return;
      var queryText = card.getAttribute('data-history-query');
      var vocabId = card.getAttribute('data-history-vocab-id');
      await searchFor(queryText);
      if (vocabId) openVocabulary(vocabId);
    });
  }

  // -------------------------------------------------------------------
  // Modal viết tay
  // -------------------------------------------------------------------

  function openHandwritingModal() {
    if (window.DictHandwriting) window.DictHandwriting.clear();
    window.JlasUI.openModal('dict-handwriting-modal');
  }

  function init() {
    els.hero = byId('dict-hero');
    els.homePanels = byId('dict-home-panels');
    els.searchZone = byId('dict-search-zone');
    els.searchModes = document.querySelector('.dict-search-modes');
    els.searchInput = byId('dict-search-input');
    els.suggest = byId('dict-suggest');
    els.resultsHeader = byId('dict-results-header');
    els.resultsTitle = byId('dict-results-title');
    els.backHome = byId('dict-back-home');
    els.tabs = byId('dict-tabs');
    els.tabAll = document.querySelector('.dict-tab[data-tab="all"]');
    els.tabVocab = document.querySelector('.dict-tab[data-tab="vocab"]');
    els.tabKanji = document.querySelector('.dict-tab[data-tab="kanji"]');
    els.tabAllCount = byId('dict-tab-all-count');
    els.tabVocabCount = byId('dict-tab-vocab-count');
    els.tabKanjiCount = byId('dict-tab-kanji-count');
    els.resultsLayout = byId('dict-results-layout');
    els.detailPane = byId('dict-detail-pane');
    els.kanjiSection = byId('dict-kanji-section');
    els.kanjiGrid = byId('dict-kanji-grid');
    els.vocabSection = byId('dict-vocab-section');
    els.vocabList = byId('dict-vocab-list');
    els.loadMore = byId('dict-load-more');
    els.empty = byId('dict-empty');
    els.emptyQuery = byId('dict-empty-query');
    els.status = byId('dict-status');
    els.filterPanel = byId('dict-filter-panel');
    els.filterJlpt = byId('dict-filter-jlpt');
    els.filterPos = byId('dict-filter-pos');
    els.filterReset = byId('dict-filter-reset');
    els.radicalPanel = byId('dict-radical-panel');
    els.radicalList = byId('dict-radical-list');
    els.recentList = byId('dict-recent-list');
    els.recentEmpty = byId('dict-recent-empty');

    els.searchInput.addEventListener('input', scheduleSearch);
    els.searchInput.addEventListener('focus', function () {
      if (state.lastResult) renderSuggestions(state.lastResult);
    });
    els.searchInput.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { hideSuggestions(); return; }
      if (event.key === 'Enter') { event.preventDefault(); commitSearch(); }
    });
    document.addEventListener('click', function (event) {
      if (!els.suggest.hidden && !event.target.closest('.dict-search-bar')) hideSuggestions();
    });
    els.suggest.addEventListener('click', function (event) {
      var item = event.target.closest('[data-type]');
      if (!item) return;
      hideSuggestions();
      var key = item.getAttribute('data-key');
      state.query = els.searchInput.value.trim();
      history.replaceState(null, '', state.query ? '?q=' + encodeURIComponent(state.query) : window.location.pathname);
      if (item.getAttribute('data-type') === 'kanji') openKanji(key); else openVocabulary(key);
    });

    els.tabAll.addEventListener('click', function () { setActiveTab('all'); });
    els.tabVocab.addEventListener('click', function () { setActiveTab('vocab'); });
    els.tabKanji.addEventListener('click', function () { setActiveTab('kanji'); });
    els.loadMore.addEventListener('click', function () {
      if (state.loadingMore) return;
      state.loadingMore = true;
      state.page += 1;
      runSearch(state.query, state.page, true).finally(function () { state.loadingMore = false; });
    });
    els.backHome.addEventListener('click', resetToHome);

    initFilterPanel();
    initRecentLookups();

    delegateClicks(els.kanjiGrid);
    delegateClicks(els.vocabList);
    delegateClicks(els.detailPane);

    [byId('dict-open-handwriting'), byId('dict-mode-handwriting'), byId('dict-empty-handwriting')]
      .forEach(function (btn) { if (btn) btn.addEventListener('click', openHandwritingModal); });
    var radicalButton = byId('dict-mode-radical');
    if (radicalButton) radicalButton.addEventListener('click', function () {
      if (window.DictRadical) window.DictRadical.open();
    });

    var emptyClearBtn = byId('dict-empty-clear');
    if (emptyClearBtn) emptyClearBtn.addEventListener('click', resetToHome);

    setHomeMode(true);
    loadRecentLookups();

    var initialQuery = new URLSearchParams(window.location.search).get('q');
    if (initialQuery) {
      els.searchInput.value = initialQuery;
      commitQuery(initialQuery);
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();
