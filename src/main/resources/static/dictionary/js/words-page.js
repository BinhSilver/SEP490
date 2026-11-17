(function () {
  'use strict';

  var esc = window.DictUi.escapeHtml;
  var state = { kanji: [], selected: '', exclude: '', page: 0, request: 0 };
  var chips = document.getElementById('dict-words-chips');
  var list = document.getElementById('dict-words-list');
  var status = document.getElementById('dict-words-status');
  var pagination = document.getElementById('dict-words-pagination');
  var back = document.getElementById('dict-words-back');

  function firstGloss(word) {
    if (word.meaning) return word.meaning;
    var first = (word.senses || [])[0];
    return first ? (first.glosses || []).map(function (gloss) { return gloss.text; }).join('; ') : '';
  }
  function readUrl() {
    var params = new URLSearchParams(window.location.search);
    state.kanji = Array.from(new Set(Array.from(params.get('kanji') || '').filter(function (character) {
      return /[\u3400-\u9fff]/.test(character);
    })));
    state.selected = state.kanji.indexOf(params.get('k')) >= 0 ? params.get('k') : state.kanji[0] || '';
    state.exclude = params.get('exclude') || '';
    var page = Number(params.get('page'));
    state.page = Number.isSafeInteger(page) && page >= 0 ? page : 0;
  }
  function updateUrl(replace) {
    var params = new URLSearchParams({ kanji: state.kanji.join(''), k: state.selected, page: String(state.page) });
    if (state.exclude) params.set('exclude', state.exclude);
    history[replace ? 'replaceState' : 'pushState'](null, '', '/dictionary/words.html?' + params);
  }
  function renderChips() {
    chips.innerHTML = state.kanji.map(function (character) {
      return '<button type="button" class="dict-ai-chip" data-kanji="' + esc(character) + '" aria-pressed="' +
        (character === state.selected) + '">' + esc(character) + '</button>';
    }).join('');
    back.href = state.exclude ? '/dictionary/index.html?q=' + encodeURIComponent(state.exclude)
      : state.selected ? '/search/kanji/' + encodeURIComponent(state.selected) : '/dictionary/index.html';
  }
  function rowHtml(word) {
    return '<a class="dict-related-row" href="/dictionary/index.html?q=' +
      esc(encodeURIComponent(word.expression)) + '"><span class="dict-related-row__head"><strong lang="ja">' +
      esc(word.expression) + '</strong><span class="dict-related-row__reading" lang="ja">' +
      esc(word.reading || '') + '</span>' + window.DictUi.jlptBadgeHtml(word.jlptLevel) +
      '</span><span class="dict-related-row__meaning">' + esc(firstGloss(word)) + '</span></a>';
  }
  function pageButton(label, page, disabled, current) {
    return '<button type="button" data-page="' + page + '"' + (disabled ? ' disabled' : '') +
      (current ? ' aria-current="page"' : '') + '>' + label + '</button>';
  }
  function renderPagination(totalPages) {
    if (totalPages <= 1) { pagination.innerHTML = ''; return; }
    var start = Math.max(0, state.page - 2);
    var end = Math.min(totalPages - 1, state.page + 2);
    var html = pageButton('Trước', state.page - 1, state.page === 0, false);
    if (start > 0) html += pageButton('1', 0, false, false) + (start > 1 ? '<span>…</span>' : '');
    for (var page = start; page <= end; page++) html += pageButton(String(page + 1), page, false, page === state.page);
    if (end < totalPages - 1) html += (end < totalPages - 2 ? '<span>…</span>' : '') +
      pageButton(String(totalPages), totalPages - 1, false, false);
    pagination.innerHTML = html + pageButton('Tiếp', state.page + 1, state.page >= totalPages - 1, false);
  }
  async function load() {
    var request = ++state.request;
    renderChips();
    list.innerHTML = '';
    pagination.innerHTML = '';
    status.hidden = false;
    status.textContent = 'Đang tải từ vựng...';
    if (!state.selected) { status.textContent = 'Chưa có Kanji để tra cứu.'; return; }
    try {
      var result = await window.DictApi.kanjiVocabulary(state.selected, state.page, 20);
      if (request !== state.request) return;
      if (state.page >= Math.max(1, result.totalPages || 0)) {
        state.page = Math.max(0, (result.totalPages || 0) - 1);
        updateUrl(true);
        load();
        return;
      }
      var words = (result.content || []).filter(function (word) { return word.expression !== state.exclude; });
      status.hidden = !!words.length;
      if (!words.length) status.textContent = 'Chưa có từ vựng';
      list.innerHTML = words.map(rowHtml).join('');
      renderPagination(result.totalPages || 0);
    } catch (_err) {
      if (request === state.request) status.textContent = 'Không thể tải từ vựng. Vui lòng thử lại.';
    }
  }
  chips.addEventListener('click', function (event) {
    var chip = event.target.closest('[data-kanji]');
    if (!chip || chip.dataset.kanji === state.selected) return;
    state.selected = chip.dataset.kanji;
    state.page = 0;
    updateUrl(false);
    load();
  });
  pagination.addEventListener('click', function (event) {
    var button = event.target.closest('[data-page]');
    if (!button || button.disabled || Number(button.dataset.page) === state.page) return;
    state.page = Number(button.dataset.page);
    updateUrl(false);
    load();
  });
  window.addEventListener('popstate', function () { readUrl(); load(); });
  readUrl();
  updateUrl(true);
  load();
})();
