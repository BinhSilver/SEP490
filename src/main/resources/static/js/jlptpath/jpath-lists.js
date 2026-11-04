/* [JLAS-W0108] jpath-lists.js — the list part of the JLPT path page tabs ([data-slot="list"]).
 *
 * Reads GET /api/learning/jlpt/lists/* (pages are 0-based); writes POST /topics/{level}/{topicKey}/known-check,
 * POST /sessions (kana row) and PUT /settings {vocabListView}. The item drawer and free quiz dialog live in
 * jpath-item-drawer.js, the grammar lesson in jpath-grammar-lesson.js; both are re-exported here unchanged.
 * Every text goes through JpathDisplay.esc.
 *
 * The slot holds a filter bar ([data-part="bar"]) and a results region ([data-part="results"]). A filter change redraws
 * only the results, so the search input keeps focus, caret and IME composition; the bar is redrawn only when its own
 * shape changes (tab, view, script, pane mode). Search waits 300 ms, never while composing (fires on compositionend).
 *
 * window.JpathLists:
 *   STATE_LABEL, listUrl(kind, filter)  /lists/<kind>?… in the fixed order level, topic, unit, state, q, radical, sort,
 *                                       view, script, page; empty values left out
 *   viewToggleHtml(view), topicGridHtml(topics, level), vocabTableHtml(page, filter), printMenuHtml(tab, filter)
 *   kanjiFiltersHtml(filter, facets), kanjiListHtml(page, view, filter), radicalChar(n)
 *   kanaColumns(row), kanaHtml(rows, script)
 *                                       "Học hàng này" → POST /sessions {tab:'KANA', mode:'BROWSE', startItemCode: row code}
 *   grammarAccordionHtml(units, filter, openCode, totals), grammarOpenMode() (always PAGE: the lesson page)
 *   mountTab(slot, tabKey, ctx)         ctx = {level, display, skillDay, api, location, history, document, navigate,
 *                                       confirm, rendered, width, force}; the same tab + level + view + skill day is not
 *                                       fetched again unless force; a new level drops the per-tab filters
 *   (re-exported) itemDrawerHtml, freeQuizDialogHtml, freeQuizRequest, safeReturn, checkUrl, messageOf,
 *                 grammarLessonHtml, structureChips, mountGrammarLesson
 * Needs jpath-display.js, jpath-furigana.js, jpath-dialog.js, jpath-item-drawer.js and jpath-grammar-lesson.js first.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathLists = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var SETTINGS_URL = BASE + '/settings';
  var STATES = ['NEW', 'LEARNING', 'NOTEBOOK', 'KNOWN'];
  var URL_ORDER = ['level', 'topic', 'unit', 'state', 'q', 'radical', 'sort', 'view', 'script', 'page'];
  var LIST_KIND = { topics: 'topics', vocab: 'vocabulary', vocabulary: 'vocabulary', kanji: 'kanji',
    grammar: 'grammar', kana: 'kana' };
  var TAB_CODE = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var LIST_URL_KEYS = ['topic', 'state', 'q', 'radical', 'sort', 'view', 'script', 'unit', 'open'];
  var SEARCH_DELAY_MS = 300;
  var MSG_LOAD = 'Không tải được danh sách. Thử lại sau.';
  var MSG_EMPTY_VOCAB = 'Không có từ nào khớp bộ lọc.';
  var MSG_PICK = 'Chọn một mẫu để xem bài học.';

  function D() {
    return (root && root.JpathDisplay) || (typeof require === 'function' ? require('./jpath-display.js') : null);
  }
  function H() {
    return (root && root.JpathItemDrawer) || (typeof require === 'function' ? require('./jpath-item-drawer.js') : null);
  }
  function GL() {
    return (root && root.JpathGrammarLesson) || (typeof require === 'function' ? require('./jpath-grammar-lesson.js') : null);
  }
  var STATE_LABEL = H().STATE_LABEL;
  function esc(v) { return D().esc(v); }
  function icon(name) {
    var nav = root.JpathNav || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav ? nav.iconHtml(name) : '';
  }
  function dataJson(o) { return D().dataJson(o); }
  function list(v) { return Array.isArray(v) ? v : []; }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function num(v) { return Number(v) || 0; }
  function tabCode(tab) {
    var s = String(tab || '');
    return TAB_CODE[s.toLowerCase()] || s.toUpperCase();
  }
  function stateOf(s) { return H().stateOf(s); }
  function stateChip(s) { return H().stateChip(s); }
  function optionHtml(v, l, s) { return H().optionHtml(v, l, s); }
  function readingsHtml(r, lv) { return H().readingsHtml(r, lv); }
  function messageOf(e, fallback, scope) { return H().messageOf(e, fallback, scope); }

  function listUrl(kind, filter) {
    var f = filter || {};
    var parts = [];
    URL_ORDER.forEach(function (k) {
      if (has(f[k])) parts.push(k + '=' + encodeURIComponent(String(f[k])));
    });
    return BASE + '/lists/' + (LIST_KIND[kind] || kind) + (parts.length ? '?' + parts.join('&') : '');
  }

  // ------------------------------------------------------------------------------------------------ shared bits

  function printFilter(tab, filter) {
    var out = { scope: 'LIST', tab: tabCode(tab) };
    Object.keys(filter || {}).forEach(function (k) {
      if (k !== 'page' && k !== 'scope' && k !== 'tab' && has(filter[k])) out[k] = filter[k];
    });
    return out;
  }

  function printMenuHtml(tab, filter) {
    var t = tabCode(tab);
    return '<details class="jp-print-menu"><summary class="jp-btn">In</summary><div class="jp-print-menu__items">'
      + '<button type="button" class="jp-link-btn" data-jpath-print="' + dataJson({ scope: 'TODAY', tab: t })
      + '">In phần học hôm nay</button>'
      + '<button type="button" class="jp-link-btn" data-jpath-print="' + dataJson(printFilter(t, filter))
      + '">In danh sách đang lọc</button></div></details>';
  }

  function quizButtonHtml(tab) {
    return '<button type="button" class="jp-btn" data-action="free-quiz" data-tab="' + esc(tabCode(tab))
      + '">Luyện quiz</button>';
  }

  function pagerHtml(page, localPage) {
    var p = page || {};
    var size = num(p.size) || 50;
    var perBackendPage = Math.ceil(size / 20);
    var fullBackendPages = Math.floor(num(p.total) / size);
    var lastItems = num(p.total) % size;
    var pages = Math.max(1, fullBackendPages * perBackendPage + Math.ceil(lastItems / 20));
    var at = Math.min(Math.max(0, num(p.page) * perBackendPage + num(localPage)), pages - 1);
    // shared numbered pager (Đầu, Trước, 1 … n, Sau, Cuối) when the nav kit is on the page
    var N = root.JpathNav || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    if (N && typeof N.pagerHtml === 'function') return N.pagerHtml(at, pages);
    var html = '<nav class="jp-pager" aria-label="Trang">';
    html += '<button type="button" class="jp-btn" data-action="page" data-page="' + (at - 1) + '"'
      + (at <= 0 ? ' disabled' : '') + '>Trước</button>';
    html += '<span class="jp-pager__at">' + esc('Trang ' + (at + 1) + '/' + pages) + '</span>';
    html += '<button type="button" class="jp-btn" data-action="page" data-page="' + (at + 1) + '"'
      + (at >= pages - 1 ? ' disabled' : '') + '>Sau</button>';
    return html + '</nav>';
  }

  function localPagerHtml(at, total, action) {
    var nav = root.JpathNav || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && total > 20 ? nav.pagerHtml(at, Math.ceil(total / 20), { action: action }) : '';
  }

  function facetLabel(facet) {
    var f = facet || {};
    return (has(f.label) ? f.label : f.value) + (f.count != null ? ' (' + num(f.count) + ')' : '');
  }

  // ------------------------------------------------------------------------------------------------ vocab

  function viewToggleHtml(view) {
    var flat = view === 'FLAT';
    return '<div class="jp-seg" role="group" aria-label="Kiểu danh sách">'
      + '<button type="button" class="jp-seg__btn" data-action="list-view" data-view="TOPICS" aria-pressed="' + !flat
      + '">Theo chủ đề</button>'
      + '<button type="button" class="jp-seg__btn" data-action="list-view" data-view="FLAT" aria-pressed="' + flat
      + '">Danh sách</button></div>';
  }

  function pct(n, total) { return total > 0 ? Math.round(num(n) * 1000 / total) / 10 : 0; }

  function topicCardHtml(t) {
    var c = t || {};
    var total = num(c.total);
    var parts = [['known', c.known], ['learning', c.learning], ['notebook', c.notebook], ['new', c.notLearned]];
    var bar = parts.filter(function (p) { return num(p[1]) > 0; }).map(function (p) {
      return '<span class="jp-stack__seg jp-stack__seg--' + p[0] + '" style="width:' + pct(p[1], total) + '%"></span>';
    }).join('');
    var summary = 'Đã biết ' + num(c.known) + ' · Đang học ' + num(c.learning) + ' · Sổ tay ' + num(c.notebook)
      + ' · Chưa học ' + num(c.notLearned);
    var done = total > 0 && num(c.known) >= total;
    return '<li class="jp-topic-card">'
      + '<button type="button" class="jp-topic-card__open" data-action="open-topic" data-topic="' + esc(c.topicKey) + '">'
      + '<span class="jp-topic-card__title">' + esc(c.title || c.topicKey) + '</span> '
      + '<span class="jp-topic-card__count">' + esc(total + ' từ') + '</span></button>'
      + '<div class="jp-stack" role="img" aria-label="' + esc(summary) + '">' + bar + '</div>'
      + '<p class="jp-topic-card__sub">' + esc(summary) + '</p>'
      + (done ? '<p class="jp-topic-card__done">' + icon('check') + ' Đã biết cả chủ đề</p>'
        : '<button type="button" class="jp-link-btn" data-action="topic-known" data-topic="' + esc(c.topicKey)
          + '">Tôi biết chủ đề này</button>')
      + '</li>';
  }

  function topicGridHtml(topics, level, page) {
    var items = list(topics);
    if (!items.length) return '<p class="jp-muted">Chưa có chủ đề nào ở ' + esc(level || '') + '.</p>';
    return '<p class="jp-list-summary">' + esc(items.length + ' chủ đề') + '</p>'
      + '<ul class="jp-topic-grid" aria-label="' + esc('Chủ đề từ vựng ' + (level || '')) + '">'
      + items.slice(num(page) * 20, (num(page) + 1) * 20).map(topicCardHtml).join('') + '</ul>'
      + localPagerHtml(num(page), items.length, 'topic-page');
  }

  function vocabFiltersHtml(filter, facets) {
    var f = filter || {};
    var fc = facets || {};
    var topics = list(fc.topics);
    var states = list(fc.states);
    var html = '<div class="jp-list-filters" role="search">';
    html += '<label class="jp-field"><span class="jp-field__label">Chủ đề</span><select class="jp-input" data-filter="topic">'
      + optionHtml('', 'Tất cả chủ đề', !has(f.topic))
      + topics.map(function (t) { return optionHtml(t.value, facetLabel(t), t.value === f.topic); }).join('')
      + '</select></label>';
    var stateOpts = states.length ? states : STATES.map(function (s) { return { value: s, count: null }; });
    html += '<label class="jp-field"><span class="jp-field__label">Trạng thái</span><select class="jp-input" data-filter="state">'
      + optionHtml('', 'Tất cả', !has(f.state))
      + stateOpts.map(function (s) {
        return optionHtml(s.value, facetLabel({ label: STATE_LABEL[s.value] || s.value, count: s.count }),
          String(s.value) === String(f.state || '').toUpperCase());
      }).join('') + '</select></label>';
    html += '<label class="jp-field jp-field--grow"><span class="jp-field__label">Tìm</span>'
      + '<input class="jp-input" type="search" data-filter="q" placeholder="Tìm từ, cách đọc, nghĩa…" value="'
      + esc(f.q || '') + '" autocomplete="off"></label>';
    return html + '</div>';
  }

  function vocabRowHtml(r) {
    var row = r || {};
    return '<tr><td><button type="button" class="jp-row-open" data-action="open-item" data-type="VOCAB" data-code="'
      + esc(row.code) + '"><span class="jp-ja" lang="ja">' + esc(row.expression) + '</span></button></td>'
      + '<td><span class="jp-ja" lang="ja">' + esc(row.reading) + '</span>'
      + (has(row.romaji) ? '<span class="jp-romaji">' + esc(row.romaji) + '</span>' : '') + '</td>'
      + '<td>' + esc(row.meaning) + '</td><td class="jp-col-type">' + esc(row.wordType) + '</td>'
      + '<td>' + stateChip(row.state) + (row.hard ? ' <span class="jp-badge">Khó nhớ</span>' : '') + '</td></tr>';
  }

  function vocabResultsHtml(page, filter, localPage) {
    var p = page || {};
    var f = filter || {};
    var items = list(p.items);
    var html = '<div class="jp-list-bar">' + (has(f.topic)
      ? '<button type="button" class="jp-link-btn" data-action="clear-topic">' + icon('arrow-left') + ' Tất cả chủ đề</button>' : '')
      + printMenuHtml('VOCAB', f) + '</div>';
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">' + esc(MSG_EMPTY_VOCAB) + '</p>';
    return html + '<p class="jp-list-summary">' + esc(num(p.total) + ' từ') + '</p>'
      + '<div class="jp-table-wrap"><table class="jp-table"><thead><tr><th scope="col">Từ</th>'
      + '<th scope="col">Cách đọc</th><th scope="col">Nghĩa</th><th scope="col" class="jp-col-type">Loại từ</th>'
      + '<th scope="col">Trạng thái</th></tr></thead><tbody>'
      + items.slice(num(localPage) * 20, (num(localPage) + 1) * 20).map(vocabRowHtml).join('')
      + '</tbody></table></div>' + pagerHtml(p, localPage);
  }

  function vocabTableHtml(page, filter, localPage) {
    var p = page || {};
    return vocabFiltersHtml(filter, p.facets) + vocabResultsHtml(p, filter, localPage);
  }

  // ------------------------------------------------------------------------------------------------ kanji

  var KANJI_SORTS = [['JLPT', 'Thứ tự cấp'], ['STROKES', 'Số nét'], ['FREQUENCY', 'Tần suất'], ['HAN_VIET', 'Âm Hán Việt']];

  /** The Kangxi radical n (1–214) as its CJK character; '' when out of range. */
  function radicalChar(n) {
    var v = Number(n);
    if (!(v >= 1 && v <= 214) || Math.floor(v) !== v) return '';
    var c = String.fromCodePoint(0x2F00 + v - 1);
    return typeof c.normalize === 'function' ? c.normalize('NFKC') : c;
  }

  function radicalLabel(facet) {
    var f = facet || {};
    var name = [radicalChar(f.value), has(f.label) ? f.label : null].filter(has).join(' ') || ('Bộ ' + f.value);
    return name + (f.count != null ? ' (' + num(f.count) + ')' : '');
  }

  function kanjiFiltersHtml(filter, facets) {
    var f = filter || {};
    var radicals = list((facets || {}).radicals);
    var grid = f.view === 'GRID';
    return '<div class="jp-list-filters" role="search">'
      + '<label class="jp-field jp-field--grow"><span class="jp-field__label">Tìm</span>'
      + '<input class="jp-input" type="search" data-filter="q" placeholder="Tìm chữ, âm Hán, nghĩa…" value="'
      + esc(f.q || '') + '" autocomplete="off"></label>'
      + '<label class="jp-field"><span class="jp-field__label">Bộ thủ</span><select class="jp-input" data-filter="radical">'
      + optionHtml('', 'Tất cả bộ', !has(f.radical))
      + radicals.map(function (r) { return optionHtml(r.value, radicalLabel(r), String(r.value) === String(f.radical)); }).join('')
      + '</select></label>'
      + '<label class="jp-field"><span class="jp-field__label">Sắp xếp</span><select class="jp-input" data-filter="sort">'
      + KANJI_SORTS.map(function (s) { return optionHtml(s[0], s[1], s[0] === (f.sort || 'JLPT')); }).join('')
      + '</select></label>'
      + '<div class="jp-seg" role="group" aria-label="Kiểu hiển thị">'
      + '<button type="button" class="jp-seg__btn" data-action="kanji-view" data-view="TABLE" aria-pressed="' + !grid
      + '">Bảng</button><button type="button" class="jp-seg__btn" data-action="kanji-view" data-view="GRID" aria-pressed="'
      + grid + '">Lưới</button></div></div>';
  }

  function kanjiRowHtml(r) {
    var k = r || {};
    return '<tr><td><button type="button" class="jp-row-open jp-kanji jp-kanji-char" data-action="open-item" data-type="KANJI"'
      + ' data-code="' + esc(k.code) + '" lang="ja">' + esc(k.character) + '</button></td>'
      + '<td class="jp-hanviet">' + esc(has(k.hanViet) ? String(k.hanViet).toUpperCase() : '') + '</td>'
      + '<td>' + esc(k.meaning) + '</td>'
      + '<td class="jp-ja">' + readingsHtml(k.readingsOn, k.levelReadings) + '</td>'
      + '<td class="jp-ja">' + readingsHtml(k.readingsKun, k.levelReadings) + '</td>'
      + '<td>' + esc(has(k.strokeCount) ? k.strokeCount : '') + '</td></tr>';
  }

  function kanjiCellHtml(r) {
    var k = r || {};
    return '<li><button type="button" class="jp-kanji-tile jp-kanji-tile--' + stateOf(k.state).toLowerCase()
      + '" data-action="open-item" data-type="KANJI" data-code="' + esc(k.code) + '">'
      + '<span class="jp-kanji jp-kanji-tile__char" lang="ja">' + esc(k.character) + '</span>'
      + '<span class="jp-kanji-tile__hv">' + esc(has(k.hanViet) ? String(k.hanViet).toUpperCase() : '') + '</span>'
      + '</button></li>';
  }

  function kanjiListHtml(page, view, filter, localPage) {
    var p = page || {};
    var items = list(p.items);
    var html = '<div class="jp-list-bar">' + printMenuHtml('KANJI', filter) + '</div>';
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">Không có chữ Kanji nào khớp bộ lọc.</p>';
    if (view === 'GRID') {
      html += '<ul class="jp-kanji-grid">' + items.slice(num(localPage) * 20, (num(localPage) + 1) * 20).map(kanjiCellHtml).join('') + '</ul>';
    } else {
      html += '<div class="jp-table-wrap"><table class="jp-table jp-table--kanji"><thead><tr><th scope="col">Chữ</th>'
        + '<th scope="col">Hán Việt</th><th scope="col">Nghĩa</th><th scope="col">On</th><th scope="col">Kun</th>'
        + '<th scope="col">Nét</th></tr></thead><tbody>'
        + items.slice(num(localPage) * 20, (num(localPage) + 1) * 20).map(kanjiRowHtml).join('') + '</tbody></table></div>';
    }
    return html + pagerHtml(p, localPage);
  }

  // ------------------------------------------------------------------------------------------------ kana

  var VOWELS = ['a', 'i', 'u', 'e', 'o'];
  var YOON_VOWELS = ['a', 'u', 'o'];

  function lastVowel(romaji) {
    var m = /([aiueo])$/.exec(String(romaji || '').toLowerCase());
    return m ? m[1] : null;
  }

  /** Grid slots of one kana row: 5 per line a i u e o (ん in its own slot after them), YOON 3 per line a u o. */
  function kanaColumns(row) {
    var r = row || {};
    var cells = list(r.kanas).filter(Boolean);
    if (r.kind === 'YOON') {
      var groups = [];
      var byKey = {};
      cells.forEach(function (c) {
        var key = String(c.romaji || '').replace(/[aiueo]$/, '');
        if (!byKey[key]) { byKey[key] = [null, null, null]; groups.push(byKey[key]); }
        var i = YOON_VOWELS.indexOf(lastVowel(c.romaji));
        if (i >= 0) byKey[key][i] = c;
      });
      return [].concat.apply([], groups);
    }
    var slots = [null, null, null, null, null];
    var extra = [];
    cells.forEach(function (c) {
      var i = VOWELS.indexOf(lastVowel(c.romaji));
      if (i >= 0 && !slots[i]) slots[i] = c; else extra.push(c);
    });
    return slots.concat(extra);
  }

  function kanaCellHtml(c) {
    if (!c) return '<span class="jp-kana-cell jp-kana-cell--empty" aria-hidden="true"></span>';
    return '<button type="button" class="jp-kana-cell jp-kana-cell--' + stateOf(c.state).toLowerCase()
      + '" data-jpath-writing="' + dataJson({ itemType: 'KANA', code: c.code }) + '" aria-label="'
      + esc(c.kana + ' ' + (c.romaji || '') + ', luyện viết') + '"><span class="jp-kanji jp-kana-cell__kana" lang="ja">'
      + esc(c.kana) + '</span><span class="jp-romaji">' + esc(c.romaji) + '</span></button>';
  }

  function kanaBarHtml(script) {
    var s = script === 'KATAKANA' ? 'KATAKANA' : 'HIRAGANA';
    return '<div class="jp-list-bar"><div class="jp-seg" role="group" aria-label="Bảng chữ">'
      + '<button type="button" class="jp-seg__btn" data-action="kana-script" data-script="HIRAGANA" aria-pressed="'
      + (s === 'HIRAGANA') + '">Hiragana</button><button type="button" class="jp-seg__btn" data-action="kana-script"'
      + ' data-script="KATAKANA" aria-pressed="' + (s === 'KATAKANA') + '">Katakana</button></div>'
      + '<button type="button" class="jp-btn" data-jpath-print="' + dataJson({ scope: 'KANA', script: s }) + '">In bảng chữ</button></div>';
  }

  function kanaRowsHtml(rows) {
    var html = '';
    var items = list(rows).filter(Boolean);
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">Chưa có hàng chữ nào.</p>';
    html += '<ol class="jp-kana-rows">' + items.map(function (r) {
      var first = list(r.kanas).filter(Boolean)[0];
      // BROWSE takes the row code (HR-…/KR-…): Task 34 starts that row's new kana plus a KANA_ROW check
      return '<li class="jp-kana-row"><div class="jp-kana-row__head"><h3 class="jp-kana-row__title">' + esc(r.title)
        + '</h3>' + (r.passed ? '<span class="jp-ok jp-kana-row__passed">' + icon('check') + ' Đã đạt</span>' : '')
        + (first ? '<button type="button" class="jp-link-btn" data-action="kana-row" data-row="' + esc(r.code)
          + '">Học hàng này</button>' : '') + '</div>'
        + '<div class="jp-kana-grid' + (r.kind === 'YOON' ? ' jp-kana-grid--yoon' : '') + '">'
        + kanaColumns(r).map(kanaCellHtml).join('') + '</div></li>';
    }).join('') + '</ol>';
    return html;
  }

  function kanaHtml(rows, script) { return kanaBarHtml(script) + kanaRowsHtml(rows); }

  // ------------------------------------------------------------------------------------------------ grammar

  var PANE_MIN_WIDTH = 900;

  // owner 04/11: a grammar lesson always opens on its own page (the side pane beside the list is gone)
  function grammarOpenMode() { return 'PAGE'; }

  function unitTitle(u) {
    var x = u || {};
    return 'Bài ' + x.unitNo + (has(x.title) ? ' · ' + x.title : '');
  }

  function dotClass(state) {
    var s = stateOf(state);
    if (s === 'KNOWN') return 'jp-dot-known';
    if (s === 'LEARNING' || s === 'NOTEBOOK') return 'jp-dot-learning';
    return 'jp-dot-new';
  }

  function grammarRowHtml(r, openCode, pane) {
    var g = r || {};
    var open = has(openCode) && g.code === openCode;
    return '<li><button type="button" class="jp-grow' + (open ? ' jp-grow--open' : '') + '" data-action="open-grammar"'
      + ' data-code="' + esc(g.code) + '" aria-expanded="' + open + '"'
      + (pane ? ' aria-controls="jp-grammar-pane"' : '') + '>'
      + '<span class="jp-dot ' + dotClass(g.state) + '" aria-hidden="true"></span>'
      + '<span class="jp-sr-only">' + esc(STATE_LABEL[stateOf(g.state)]) + ': </span>'
      + '<span class="jp-grow__text"><b lang="ja">' + esc(g.pattern) + '</b>' + (has(g.meaning) ? ' · ' + esc(g.meaning) : '')
      + '</span><span class="jp-grow__mark" aria-hidden="true">' + icon(open ? 'chevron-down' : 'chevron-right') + '</span></button></li>';
  }

  function grammarFiltersHtml(filter, options) {
    var f = filter || {};
    return '<div class="jp-list-filters" role="search">'
      + '<label class="jp-field"><span class="jp-field__label">Bài</span><select class="jp-input" data-filter="unit">'
      + optionHtml('', 'Tất cả bài', !has(f.unit))
      + options.map(function (u) { return optionHtml(u.unitNo, unitTitle(u), String(u.unitNo) === String(f.unit)); }).join('')
      + '</select></label>'
      + '<label class="jp-field jp-field--grow"><span class="jp-field__label">Tìm</span>'
      + '<input class="jp-input" type="search" data-filter="q" placeholder="Tìm mẫu, nghĩa…" value="' + esc(f.q || '')
      + '" autocomplete="off"></label>' + quizButtonHtml('GRAMMAR') + '</div>';
  }

  /** The progress line and the units; pane = true adds aria-controls to the rows (wide-screen pane). */
  function grammarUnitsHtml(units, openCode, totals, pane, showSummary) {
    var items = list(units).filter(Boolean);
    var t = totals || {};
    var total = has(t.total) ? num(t.total) : items.reduce(function (s, u) { return s + num(u.total); }, 0);
    var done = has(t.done) ? num(t.done) : items.reduce(function (s, u) { return s + num(u.done); }, 0);
    var html = showSummary === false ? '' : '<p class="jp-grammar-sum">' + esc(done + '/' + total + ' mẫu đã xong') + ' · <span class="jp-legend">'
      + '<span class="jp-dot jp-dot-known" aria-hidden="true"></span> đã xong '
      + '<span class="jp-dot jp-dot-learning" aria-hidden="true"></span> đang học '
      + '<span class="jp-dot jp-dot-new" aria-hidden="true"></span> chưa học</span></p>';
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">Không có mẫu nào khớp bộ lọc.</p>';
    return html + items.map(function (u) {
      return '<section class="jp-gunit"><h3 class="jp-gunit__title">' + esc(unitTitle(u))
        + ' <span class="jp-gunit__count">' + esc(num(u.done) + '/' + num(u.total) + ' mẫu') + '</span></h3>'
        + '<ul class="jp-gunit__rows">' + list(u.patterns).map(function (r) { return grammarRowHtml(r, openCode, pane); }).join('')
        + '</ul></section>';
    }).join('');
  }

  function compactGrammarHtml(units, openCode, totals, pane, page) {
    var count = 0;
    var shown = [];
    var start = num(page) * 20, end = start + 20;
    list(units).filter(Boolean).forEach(function (u) {
      var rows = list(u.patterns);
      var part = rows.slice(Math.max(0, start - count), Math.max(0, end - count));
      if (part.length) shown.push(Object.assign({}, u, { patterns: part }));
      count += rows.length;
    });
    return grammarUnitsHtml(shown, openCode, totals, pane) + localPagerHtml(num(page), count, 'grammar-page');
  }

  function grammarAccordionHtml(units, filter, openCode, totals) {
    var t = totals || {};
    var options = list(t.units).length ? list(t.units) : list(units).filter(Boolean);
    return grammarFiltersHtml(filter, options) + grammarUnitsHtml(units, openCode, totals, false);
  }

  // ------------------------------------------------------------------------------------------------ url state

  function readParams(loc) {
    var out = {};
    var search = String((loc && loc.search) || '').replace(/^\?/, '');
    search.split('&').forEach(function (pair) {
      if (!pair) return;
      var i = pair.indexOf('=');
      var k = i < 0 ? pair : pair.slice(0, i);
      var v = i < 0 ? '' : pair.slice(i + 1);
      try { out[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' ')); } catch (e) { /* skip */ }
    });
    return out;
  }

  function writeParams(env, values) {
    var loc = env.location;
    if (!env.history || !loc) return;
    var params = readParams(loc);
    Object.keys(values).forEach(function (k) {
      if (has(values[k])) params[k] = String(values[k]); else delete params[k];
    });
    var q = Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
    }).join('&');
    try { env.history.replaceState(null, '', ((loc.pathname) || '/learning') + (q ? '?' + q : '')); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------------------------------------ tabs
  // each load(st) resolves to {barKey, bar, results}: the bar is redrawn only when barKey changes

  var TABS = {};

  TABS.vocab = {
    urlKeys: ['topic', 'state', 'q'],
    init: function (st, params) {
      st.filter = { topic: params.topic || '', state: params.state || '', q: params.q || '', page: 0, localPage: 0,
        topicPage: 0,
        backendSize: 50 };
      st.view = (st.ctx.display && st.ctx.display.vocabListView) === 'FLAT' ? 'FLAT' : 'TOPICS';
    },
    load: function (st) {
      var f = st.filter;
      var level = st.ctx.level;
      var view = has(f.topic) ? 'FLAT' : st.view;
      var top = '<div class="jp-list-bar">' + viewToggleHtml(view) + quizButtonHtml('VOCAB') + '</div>';
      if (view === 'TOPICS') {
        return (st.topics ? Promise.resolve(st.topics) : st.api.get(listUrl('topics', { level: level }))).then(function (topics) {
          st.topics = list(topics);
          var matched = st.topics.filter(function (t) {
            return !f.q || String((t && (t.title || t.topicKey)) || '').toLocaleLowerCase().includes(String(f.q).toLocaleLowerCase());
          });
          return { barKey: 'vocab:TOPICS', bar: top + '<div class="jp-list-filters" role="search"><label class="jp-field jp-field--grow">'
            + '<span class="jp-field__label">Tìm chủ đề</span><input class="jp-input" type="search" data-filter="q"'
            + ' placeholder="Tìm chủ đề…" value="' + esc(f.q) + '" autocomplete="off"></label></div>',
            results: '<div class="jp-list-bar">' + printMenuHtml('VOCAB', { level: level }) + '</div>'
              + topicGridHtml(matched, level, f.topicPage) };
        });
      }
      return (st.pageData && num(st.pageData.page) === f.page ? Promise.resolve(st.pageData)
        : st.api.get(listUrl('vocabulary', { level: level, topic: f.topic, state: f.state, q: f.q, page: f.page })))
        .then(function (page) {
          st.pageData = page;
          f.backendSize = num(page && page.size) || 50;
          var current = { level: level, topic: f.topic, state: f.state, q: f.q };
          return { barKey: 'vocab:FLAT', bar: top + vocabFiltersHtml(current, page && page.facets),
            results: vocabResultsHtml(page, current, f.localPage) };
        });
    }
  };

  TABS.kanji = {
    urlKeys: ['q', 'radical', 'sort', 'view'],
    init: function (st, params) {
      var sort = String(params.sort || '').toUpperCase();
      st.filter = { q: params.q || '', radical: /^\d+$/.test(params.radical || '') ? params.radical : '',
        sort: KANJI_SORTS.some(function (s) { return s[0] === sort; }) ? sort : '',
        view: String(params.view || '').toUpperCase() === 'GRID' ? 'GRID' : '', page: 0, localPage: 0,
        backendSize: 50 };
    },
    load: function (st) {
      var f = st.filter;
      var level = st.ctx.level;
      var view = f.view === 'GRID' ? 'GRID' : 'TABLE';
      return (st.pageData && num(st.pageData.page) === f.page ? Promise.resolve(st.pageData)
        : st.api.get(listUrl('kanji', { level: level, q: f.q, radical: f.radical, sort: f.sort, view: f.view,
          page: f.page }))).then(function (page) {
        st.pageData = page;
        f.backendSize = num(page && page.size) || 50;
        var current = { level: level, q: f.q, radical: f.radical, sort: f.sort, view: f.view };
        return { barKey: 'kanji:' + view,
          bar: '<div class="jp-list-bar">' + quizButtonHtml('KANJI') + '</div>' + kanjiFiltersHtml(current, page && page.facets),
          results: kanjiListHtml(page, view, current, f.localPage) };
      });
    }
  };

  TABS.kana = {
    urlKeys: ['script'],
    init: function (st, params) {
      st.filter = { script: String(params.script || '').toUpperCase() === 'KATAKANA' ? 'KATAKANA' : 'HIRAGANA' };
    },
    load: function (st) {
      var script = st.filter.script;
      return st.api.get(listUrl('kana', { script: script })).then(function (rows) {
        return { barKey: 'kana:' + script, bar: kanaBarHtml(script), results: kanaRowsHtml(rows) };
      });
    }
  };

  TABS.grammar = {
    urlKeys: ['unit', 'q', 'open'],
    init: function (st, params) {
      st.filter = { unit: /^\d+$/.test(params.unit || '') ? params.unit : '', q: params.q || '',
        open: /^JG-[0-9a-f]{10}$/.test(params.open || '') ? params.open : '', grammarPage: 0 };
      st.cache = {};
    },
    load: function (st) {
      var f = st.filter;
      var level = st.ctx.level;
      var mode = grammarOpenMode(widthOf(st.ctx));
      function units(filter) {
        var url = listUrl('grammar', filter);
        if (st.cache[url]) return Promise.resolve(st.cache[url]);
        return st.api.get(url).then(function (v) { st.cache[url] = list(v); return st.cache[url]; });
      }
      var all = units({ level: level });
      var shown = has(f.unit) || has(f.q) ? units({ level: level, unit: f.unit, q: f.q }) : all;
      var pane = mode === 'PANE' && has(f.open)
        ? st.api.get(GL().lessonUrl(f.open)).then(function (v) { return { lesson: v || {} }; },
          function (e) { return { error: messageOf(e, 'Không tải được bài ngữ pháp.') }; })
        : Promise.resolve({});
      return Promise.all([all, shown, pane]).then(function (res) {
        st.units = res[0];
        st.lesson = res[2].lesson || null;
        var totals = { units: res[0], total: res[0].reduce(function (s, u) { return s + num(u.total); }, 0),
          done: res[0].reduce(function (s, u) { return s + num(u.done); }, 0) };
        if (f.open && !f.grammarPage) {
          var codes = [].concat.apply([], res[1].map(function (u) { return list(u.patterns); }))
            .map(function (r) { return r.code; });
          var openIndex = codes.indexOf(f.open);
          if (openIndex >= 20) f.grammarPage = Math.floor(openIndex / 20);
        }
        var out = { barKey: 'grammar:' + mode, bar: grammarFiltersHtml(f, res[0]) };
        if (mode !== 'PANE') { out.results = compactGrammarHtml(res[1], null, totals, false, f.grammarPage); return out; }
        var paneHtml = st.lesson ? GL().grammarLessonHtml(st.lesson)
          : (res[2].error ? '<p class="jp-alert" role="alert">' + esc(res[2].error) + '</p>'
            : '<p class="jp-muted jp-grammar-pane__hint">' + esc(MSG_PICK) + '</p>');
        out.results = '<div class="jp-grammar-split"><div class="jp-grammar-list">'
          + compactGrammarHtml(res[1], f.open, totals, true, f.grammarPage) + '</div>'
          + '<div class="jp-grammar-pane" id="jp-grammar-pane" data-part="pane" aria-live="polite">' + paneHtml
          + '</div></div>';
        return out;
      });
    }
  };

  function widthOf(ctx) {
    if (ctx && typeof ctx.width === 'function') return ctx.width();
    return (root && root.innerWidth) || 0;
  }

  // ------------------------------------------------------------------------------------------------ mount

  function qs(el, sel) { return el && typeof el.querySelector === 'function' ? el.querySelector(sel) : null; }

  function signature(key, c) {
    var d = c.display || {};
    return [key, c.level, d.vocabListView, !!c.skillDay, key === 'grammar' ? grammarOpenMode(widthOf(c)) : ''].join('|');
  }

  function mountTab(slot, tabKey, ctx) {
    if (!slot) return Promise.resolve();
    var key = String(tabKey || '').toLowerCase();
    var st = slot.__jpathLists;
    if (!st) {
      st = { seq: 0, filters: {}, timer: null, barKey: null, sig: null, level: undefined, ignoreUrl: false };
      slot.__jpathLists = st;
      bind(slot, st);
    }
    var c = ctx || {};
    st.ctx = c;
    st.api = c.api || root.JpathApi;
    st.env = { location: c.location || (root && root.location), history: c.history || (root && root.history) };
    st.doc = c.document || (root && root.document);
    st.navigate = c.navigate || function (u) { root.location.assign(u); };
    st.rendered = c.rendered || function (el) { D().rendered(el); };
    st.setT = c.setTimeout || function (fn, ms) { return setTimeout(fn, ms); };
    st.clearT = c.clearTimeout || function (id) { clearTimeout(id); };
    if (st.level !== undefined && st.level !== c.level) {
      // a new level: the topic, unit, open lesson… of the old level mean nothing there
      st.filters = {};
      st.ignoreUrl = true;
      var cleared = {};
      LIST_URL_KEYS.forEach(function (k) { cleared[k] = null; });
      writeParams(st.env, cleared);
    }
    st.level = c.level;
    var sig = signature(key, c);
    if (!c.force && st.sig === sig && st.key === key) return Promise.resolve();
    st.sig = sig;
    st.key = key;
    var spec = TABS[key];
    if (!spec) { slot.innerHTML = ''; st.barKey = null; return Promise.resolve(); }
    if (!st.filters[key]) {
      var tabSt = { ctx: c };
      spec.init(tabSt, st.ignoreUrl ? {} : readParams(st.env.location));
      st.filters[key] = tabSt;
    }
    if (c.force) {
      st.filters[key].pageData = null;
      st.filters[key].topics = null;
    }
    return reload(slot, st);
  }

  function tabState(st) {
    var t = st.filters[st.key];
    t.ctx = st.ctx;
    t.api = st.api;
    return t;
  }

  function liveQuery(slot) {
    var input = qs(slot, '[data-filter="q"]');
    return input && typeof input.value === 'string' ? input.value : null;
  }

  /** Refreshes the facet options of the mounted bar without touching the search input. */
  function syncSelects(st, bar, html) {
    if (!st.doc || typeof st.doc.createElement !== 'function' || typeof bar.querySelectorAll !== 'function') return;
    var tmp = st.doc.createElement('div');
    tmp.innerHTML = html;
    Array.prototype.forEach.call(tmp.querySelectorAll('select[data-filter]'), function (fresh) {
      var live = bar.querySelector('select[data-filter="' + fresh.getAttribute('data-filter') + '"]');
      if (live) live.innerHTML = fresh.innerHTML;
    });
  }

  function tabOfKey(barKey) { return barKey ? String(barKey).split(':')[0] : null; }

  function paint(slot, st, out, focusFilter) {
    var bar = qs(slot, '[data-part="bar"]');
    var results = qs(slot, '[data-part="results"]');
    if (bar && results && st.barKey === out.barKey) {
      results.innerHTML = out.results;
      syncSelects(st, bar, out.bar);
      return;
    }
    var live = tabOfKey(st.barKey) === tabOfKey(out.barKey) ? liveQuery(slot) : null;
    slot.innerHTML = '<div class="jp-list-head" data-part="bar">' + out.bar + '</div><div class="jp-list-results"'
      + ' data-part="results">' + out.results + '</div><p class="jp-alert" role="alert" data-part="list-error" hidden></p>';
    st.barKey = out.barKey;
    var input = qs(slot, '[data-filter="q"]');
    if (input && live !== null && live !== input.value) input.value = live; // the learner typed on while we loaded
    restoreFocus(slot, focusFilter);
  }

  function reload(slot, st, focusFilter) {
    var spec = TABS[st.key];
    if (!spec) return Promise.resolve();
    var seq = ++st.seq;
    var key = st.key;
    return Promise.resolve().then(function () { return spec.load(tabState(st), slot); }).then(function (out) {
      if (seq !== st.seq || key !== st.key) return;
      paint(slot, st, out, focusFilter);
      st.rendered(slot);
    }, function (e) {
      if (seq !== st.seq) return;
      var results = qs(slot, '[data-part="results"]');
      var html = '<p class="jp-alert" role="alert">' + esc(messageOf(e, MSG_LOAD, 'list')) + '</p>';
      if (results && tabOfKey(st.barKey) === key) results.innerHTML = html;
      else { slot.innerHTML = html; st.barKey = null; }
    });
  }

  function restoreFocus(slot, name) {
    if (!name) return;
    var el = qs(slot, '[data-filter="' + name + '"]');
    if (!el || typeof el.focus !== 'function') return;
    el.focus();
    if (typeof el.setSelectionRange === 'function' && typeof el.value === 'string') {
      try { el.setSelectionRange(el.value.length, el.value.length); } catch (e) { /* not a text input */ }
    }
  }

  function showError(slot, message) { H().showError(slot, 'list-error', message); }

  function currentReturn(st) {
    var loc = st.env.location || {};
    return (loc.pathname || '/learning') + (loc.search || '');
  }

  function applyFilter(slot, st, name, value) {
    var t = tabState(st);
    if (name !== 'page' && String(has(t.filter[name]) ? t.filter[name] : '') === String(has(value) ? value : '')) {
      return Promise.resolve();
    }
    if (name === 'page' && (st.key === 'vocab' || st.key === 'kanji')) {
      var perBackendPage = Math.ceil((num(t.filter.backendSize) || 50) / 20);
      t.filter.page = Math.floor(num(value) / perBackendPage);
      t.filter.localPage = num(value) % perBackendPage;
    } else {
      t.filter[name] = value;
      if (name !== 'page') {
        t.filter.page = 0;
        t.filter.localPage = 0;
        t.filter.topicPage = 0;
        t.filter.grammarPage = 0;
        t.pageData = null;
      }
    }
    var urlValues = {};
    (TABS[st.key].urlKeys || []).forEach(function (k) { urlValues[k] = t.filter[k]; });
    writeParams(st.env, urlValues);
    return reload(slot, st, name === 'page' || name === 'q' ? null : name);
  }

  function openGrammar(slot, st, el, ev) {
    var code = el.getAttribute('data-code');
    if (!/^JG-[0-9a-f]{10}$/.test(code || '')) return null;
    var link = el.getAttribute('href');
    if (grammarOpenMode(widthOf(st.ctx)) === 'PAGE') {
      if (!link) st.navigate('/learning/jlpt/grammar/' + encodeURIComponent(code));
      return null;
    }
    if (ev && typeof ev.preventDefault === 'function') ev.preventDefault();
    tabState(st).filter.open = code;
    writeParams(st.env, { open: code });
    return reload(slot, st);
  }

  function startKanaRow(slot, st, el) {
    // Task 34 BROWSE on KANA takes the row code (HR-…/KR-…): the row's new kana plus a KANA_ROW check
    var req = { tab: 'KANA', mode: 'BROWSE', startItemCode: el.getAttribute('data-row') };
    el.disabled = true;
    return st.api.send('POST', BASE + '/sessions', req).then(function (res) {
      var id = res && (res.sessionId != null ? res.sessionId : res.id);
      st.navigate('/learning/jlpt/study?session=' + encodeURIComponent(id));
    }, function (e) {
      el.disabled = false;
      showError(slot, messageOf(e));
    });
  }

  function topicTitle(st, key) {
    var t = list(tabState(st).topics).filter(function (x) { return x && x.topicKey === key; })[0];
    return (t && t.title) || key;
  }

  function topicKnown(slot, st, el) {
    var key = el.getAttribute('data-topic');
    var level = st.ctx.level;
    var ask = st.ctx.confirm || (root && root.JlasConfirm) || function (m) { return Promise.resolve(root.confirm(m)); };
    var msg = 'Làm 10 câu của chủ đề “' + topicTitle(st, key) + '”? Đúng từ 9 câu thì các từ trả lời đúng vào Đã biết.';
    return Promise.resolve(ask(msg, { confirmText: 'Làm 10 câu', cancelText: 'Để sau' })).then(function (ok) {
      if (!ok) return null;
      el.disabled = true;
      return st.api.send('POST', BASE + '/topics/' + encodeURIComponent(level) + '/' + encodeURIComponent(key)
        + '/known-check').then(function (res) {
        var token = res && (res.token || res.checkToken);
        if (token) st.navigate(H().checkUrl(token, currentReturn(st)));
      });
    }).catch(function (e) {
      el.disabled = false;
      showError(slot, messageOf(e));
    });
  }

  function saveListView(st, view) {
    if (st.ctx.display) st.ctx.display.vocabListView = view;
    st.sig = signature(st.key, st.ctx);
    var p = st.api.send('PUT', SETTINGS_URL, { vocabListView: view });
    if (p && typeof p.catch === 'function') p.catch(function () { /* the view still changes on this page */ });
  }

  function bind(slot, st) {
    var actions = {
      'topic-page': function (el) {
        tabState(st).filter.topicPage = Math.max(0, num(el.getAttribute('data-page')));
        return reload(slot, st);
      },
      'grammar-page': function (el) {
        tabState(st).filter.grammarPage = Math.max(0, num(el.getAttribute('data-page')));
        return reload(slot, st);
      },
      'list-view': function (el) {
        var view = el.getAttribute('data-view') === 'FLAT' ? 'FLAT' : 'TOPICS';
        var t = tabState(st);
        t.view = view;
        if (has(t.filter.topic)) { t.filter.topic = ''; writeParams(st.env, { topic: null }); }
        saveListView(st, view);
        return reload(slot, st);
      },
      'open-topic': function (el) { return applyFilter(slot, st, 'topic', el.getAttribute('data-topic')); },
      'clear-topic': function () { return applyFilter(slot, st, 'topic', ''); },
      'page': function (el) { return applyFilter(slot, st, 'page', Math.max(0, num(el.getAttribute('data-page')))); },
      'open-item': function (el) {
        return H().openItem({ api: st.api, document: st.doc, type: el.getAttribute('data-type'),
          code: el.getAttribute('data-code'), opener: el, display: st.ctx.display, navigate: st.navigate,
          returnTo: currentReturn(st), rendered: st.rendered,
          onChange: function () { reload(slot, st); }, onError: function (m) { showError(slot, m); } });
      },
      'topic-known': function (el) { return topicKnown(slot, st, el); },
      'free-quiz': function (el) {
        var t = tabState(st);
        return H().openFreeQuiz({ api: st.api, document: st.doc, tab: el.getAttribute('data-tab') || TAB_CODE[st.key],
          opener: el, display: st.ctx.display, skillDay: !!st.ctx.skillDay, units: t.units, unit: t.filter && t.filter.unit,
          level: st.ctx.level, topicKey: t.filter && t.filter.topic, navigate: st.navigate, returnTo: currentReturn(st) });
      },
      'kanji-view': function (el) {
        return applyFilter(slot, st, 'view', el.getAttribute('data-view') === 'GRID' ? 'GRID' : '');
      },
      'kana-script': function (el) { return applyFilter(slot, st, 'script', el.getAttribute('data-script')); },
      'kana-row': function (el) { return startKanaRow(slot, st, el); },
      'open-grammar': function (el, ev) { return openGrammar(slot, st, el, ev); }
    };
    function paneAction(el) {
      var t = tabState(st);
      if (st.key !== 'grammar' || !t.lesson) return false;
      var pane = qs(slot, '[data-part="pane"]');
      return !!pane && GL().lessonAction(el, t.lesson, pane, { api: st.api, navigate: st.navigate,
        returnTo: currentReturn(st) });
    }
    function isSearch(t) { return !!t && typeof t.getAttribute === 'function' && t.getAttribute('data-filter') === 'q'; }
    function scheduleSearch(input) {
      if (st.timer !== null) st.clearT(st.timer);
      var key = st.key;
      st.timer = st.setT(function () {
        st.timer = null;
        if (st.key !== key) return;
        applyFilter(slot, st, 'q', String(input.value || '').trim());
      }, SEARCH_DELAY_MS);
    }
    slot.addEventListener('click', function (ev) {
      var t = ev.target;
      var el = t && typeof t.closest === 'function' ? t.closest('[data-action]') : null;
      if (!el || el.disabled) return;
      var handler = actions[el.getAttribute('data-action')];
      if (handler) handler(el, ev); else paneAction(el);
    });
    slot.addEventListener('change', function (ev) {
      var t = ev.target;
      var name = t && typeof t.getAttribute === 'function' ? t.getAttribute('data-filter') : null;
      if (!name || name === 'q') return;
      applyFilter(slot, st, name, t.value);
    });
    slot.addEventListener('input', function (ev) {
      if (!isSearch(ev.target) || ev.isComposing) return; // IME: wait for compositionend
      scheduleSearch(ev.target);
    });
    slot.addEventListener('compositionend', function (ev) {
      if (isSearch(ev.target)) scheduleSearch(ev.target);
    });
  }

  function lessonExport(name) { return function () { var m = GL(); return m[name].apply(m, arguments); }; }
  function drawerExport(name) { return function () { var m = H(); return m[name].apply(m, arguments); }; }

  return {
    STATE_LABEL: STATE_LABEL, SEARCH_DELAY_MS: SEARCH_DELAY_MS, listUrl: listUrl, viewToggleHtml: viewToggleHtml,
    topicGridHtml: topicGridHtml, vocabTableHtml: vocabTableHtml, printMenuHtml: printMenuHtml, mountTab: mountTab,
    kanjiFiltersHtml: kanjiFiltersHtml, kanjiListHtml: kanjiListHtml, radicalChar: radicalChar,
    kanaColumns: kanaColumns, kanaHtml: kanaHtml, grammarAccordionHtml: grammarAccordionHtml,
    grammarOpenMode: grammarOpenMode, messageOf: messageOf,
    itemDrawerHtml: drawerExport('itemDrawerHtml'), freeQuizDialogHtml: drawerExport('freeQuizDialogHtml'),
    freeQuizRequest: drawerExport('freeQuizRequest'), safeReturn: drawerExport('safeReturn'),
    checkUrl: drawerExport('checkUrl'), grammarLessonHtml: lessonExport('grammarLessonHtml'),
    structureChips: lessonExport('structureChips'), mountGrammarLesson: lessonExport('mountGrammarLesson')
  };
});
