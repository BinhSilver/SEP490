/* [JLAS-W0108] jpath-lists.js — the list part of the JLPT path page tabs ([data-slot="list"]) and the item drawer.
 *
 * Reads GET /api/learning/jlpt/lists/* (pages are 0-based), GET /items/{type}/{code}; writes through
 * POST /items/{type}/{code}/known, POST|DELETE /items/{type}/{code}/notebook, POST /topics/{level}/{topicKey}/known-check,
 * POST /checks and PUT /settings {vocabListView}. Every text goes through JpathDisplay.esc.
 *
 * window.JpathLists:
 *   STATE_LABEL                         NEW 'Chưa học', LEARNING 'Đang học', NOTEBOOK 'Sổ tay', KNOWN 'Đã biết'
 *   listUrl(kind, filter)               /lists/<kind>?… in the fixed order level, topic, unit, state, q, radical, sort,
 *                                       view, script, page; empty values left out
 *   viewToggleHtml(view)                Theo chủ đề / Danh sách (TOPICS | FLAT)
 *   topicGridHtml(topics, level)        topic cards: counts, stacked bar, "Tôi biết chủ đề này"
 *   vocabTableHtml(page, filter)        filters + flat table + "Trang p/n" + print menu
 *   printMenuHtml(tab, filter)          data-jpath-print TODAY and LIST (the filter without page)
 *   itemDrawerHtml(detail)              item detail; buttons follow the learner state
 *   freeQuizDialogHtml(tab, ctx), freeQuizRequest(values)
 *   kanjiFiltersHtml(filter, facets)    search, radical (character + name + count), sort, Bảng / Lưới
 *   kanjiListHtml(page, view, filter)   table Chữ · Hán Việt · Nghĩa · On · Kun · Nét, or a .jp-kanji grid; print menu
 *   grammarAccordionHtml(units, filter, openCode, totals)
 *                                       units with "{done}/{total} mẫu", state dots, aria-expanded on the open pattern
 *   grammarLessonHtml(lesson), structureChips(formation), grammarOpenMode(width) (≥ 900 PANE, else PAGE)
 *   mountGrammarLesson(rootEl?, deps?)  the /learning/jlpt/grammar/{code} page (#jpathGrammarRoot); auto-mounted
 *                                       Luyện ngay / Luyện thêm → POST /checks → JpathCheck.mountInline (when loaded,
 *                                       else the check page); "Học mẫu này…" → POST /grammar/{code}/queue-today
 *   kanaColumns(row), kanaHtml(rows, script)
 *                                       kana grid (a i u e o; YOON a u o; ん in its own slot), writing on every cell,
 *                                       "Học hàng này" → POST /sessions {tab:'KANA', mode:'BROWSE', startItemCode}
 *   safeReturn(v), checkUrl(token, returnTo)
 *   mountTab(slot, tabKey, ctx)         draws the list of one tab into slot and wires it; ctx = {level, display,
 *                                       skillDay, api, location, history, document, navigate, confirm, rendered}
 * Needs jpath-display.js, jpath-furigana.js and jpath-dialog.js loaded first.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathLists = api;
  if (root && root.document && typeof root.document.getElementById === 'function') {
    var start = function () {
      if (root.document.getElementById('jpathGrammarRoot')) api.mountGrammarLesson();
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var SETTINGS_URL = BASE + '/settings';
  var STATE_LABEL = { NEW: 'Chưa học', LEARNING: 'Đang học', NOTEBOOK: 'Sổ tay', KNOWN: 'Đã biết' };
  var STATES = ['NEW', 'LEARNING', 'NOTEBOOK', 'KNOWN'];
  var URL_ORDER = ['level', 'topic', 'unit', 'state', 'q', 'radical', 'sort', 'view', 'script', 'page'];
  var LIST_KIND = { topics: 'topics', vocab: 'vocabulary', vocabulary: 'vocabulary', kanji: 'kanji',
    grammar: 'grammar', kana: 'kana' };
  var TAB_CODE = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var SEARCH_DELAY_MS = 300;
  var MSG_LOAD = 'Không tải được danh sách. Thử lại sau.';
  var MSG_BAD_FILTER = 'Bộ lọc không hợp lệ. Hãy đổi bộ lọc rồi thử lại.';
  var MSG_ACTION = 'Chưa làm được thao tác này. Thử lại sau.';
  var MSG_EMPTY_VOCAB = 'Không có từ nào khớp bộ lọc.';

  function D() {
    return (root && root.JpathDisplay) || (typeof require === 'function' ? require('./jpath-display.js') : null);
  }
  function F() {
    return (root && root.JpathFurigana) || (typeof require === 'function' ? require('./jpath-furigana.js') : null);
  }
  function DLG() {
    return (root && root.JpathDialog) || (typeof require === 'function' ? require('./jpath-dialog.js') : null);
  }
  function esc(v) { return D().esc(v); }
  function dataJson(o) { return D().dataJson(o); }
  function list(v) { return Array.isArray(v) ? v : []; }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function num(v) { return Number(v) || 0; }
  function tabCode(tab) {
    var s = String(tab || '');
    return TAB_CODE[s.toLowerCase()] || s.toUpperCase();
  }
  function stateOf(s) { return STATE_LABEL[s] ? s : 'NEW'; }

  /** A user-facing message: 400s get our own text (the server says only "Bad request"). */
  function messageOf(e, fallback) {
    if (e && (e.status === 400 || e.code === 'BAD_REQUEST')) return MSG_BAD_FILTER;
    return (e && e.message) || fallback || MSG_ACTION;
  }

  // ------------------------------------------------------------------------------------------------ urls

  function listUrl(kind, filter) {
    var f = filter || {};
    var parts = [];
    URL_ORDER.forEach(function (k) {
      if (has(f[k])) parts.push(k + '=' + encodeURIComponent(String(f[k])));
    });
    return BASE + '/lists/' + (LIST_KIND[kind] || kind) + (parts.length ? '?' + parts.join('&') : '');
  }

  function safeReturn(v) {
    if (typeof v !== 'string') return '/learning';
    if (!/^\/learning(?:[/?#]|$)/.test(v) || v.indexOf('//') >= 0 || v.indexOf('\\') >= 0) return '/learning';
    return v;
  }

  function checkUrl(token, returnTo) {
    return '/learning/jlpt/check/' + encodeURIComponent(String(token || '')) + '?return='
      + encodeURIComponent(safeReturn(returnTo));
  }

  function itemUrl(type, code) {
    return BASE + '/items/' + encodeURIComponent(String(type || '').toLowerCase()) + '/' + encodeURIComponent(code);
  }

  // ------------------------------------------------------------------------------------------------ shared bits

  function stateChip(state) {
    var s = stateOf(state);
    return '<span class="jp-state jp-state--' + s.toLowerCase() + '">' + esc(STATE_LABEL[s]) + '</span>';
  }

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

  function pagerHtml(page) {
    var p = page || {};
    var size = num(p.size) || 50;
    var pages = Math.max(1, Math.ceil(num(p.total) / size));
    var at = Math.min(Math.max(0, num(p.page)), pages - 1);
    var html = '<nav class="jp-pager" aria-label="Trang">';
    html += '<button type="button" class="jp-btn" data-action="page" data-page="' + (at - 1) + '"'
      + (at <= 0 ? ' disabled' : '') + '>‹ Trước</button>';
    html += '<span class="jp-pager__at">' + esc('Trang ' + (at + 1) + '/' + pages) + '</span>';
    html += '<button type="button" class="jp-btn" data-action="page" data-page="' + (at + 1) + '"'
      + (at >= pages - 1 ? ' disabled' : '') + '>Sau ›</button>';
    return html + '</nav>';
  }

  function optionHtml(value, label, selected) {
    return '<option value="' + esc(value) + '"' + (selected ? ' selected' : '') + '>' + esc(label) + '</option>';
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
      + (done ? '<p class="jp-topic-card__done">Đã biết cả chủ đề ✓</p>'
        : '<button type="button" class="jp-link-btn" data-action="topic-known" data-topic="' + esc(c.topicKey)
          + '">Tôi biết chủ đề này</button>')
      + '</li>';
  }

  function topicGridHtml(topics, level) {
    var items = list(topics);
    if (!items.length) return '<p class="jp-muted">Chưa có chủ đề nào ở ' + esc(level || '') + '.</p>';
    return '<ul class="jp-topic-grid" aria-label="' + esc('Chủ đề từ vựng ' + (level || '')) + '">'
      + items.map(topicCardHtml).join('') + '</ul>';
  }

  function vocabFiltersHtml(filter, facets) {
    var f = filter || {};
    var fc = facets || {};
    var topics = list(fc.topics);
    var states = list(fc.states);
    var html = '<div class="jp-list-filters" role="search">';
    if (has(f.topic)) {
      html += '<button type="button" class="jp-link-btn" data-action="clear-topic">← Tất cả chủ đề</button>';
    }
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

  function vocabResultsHtml(page) {
    var p = page || {};
    var items = list(p.items);
    if (!items.length) return '<p class="jp-muted jp-list-empty">' + esc(MSG_EMPTY_VOCAB) + '</p>';
    return '<div class="jp-table-wrap"><table class="jp-table"><thead><tr><th scope="col">Từ</th>'
      + '<th scope="col">Cách đọc</th><th scope="col">Nghĩa</th><th scope="col" class="jp-col-type">Loại từ</th>'
      + '<th scope="col">Trạng thái</th></tr></thead><tbody>' + items.map(vocabRowHtml).join('')
      + '</tbody></table></div>' + pagerHtml(p);
  }

  function vocabTableHtml(page, filter) {
    var p = page || {};
    return '<div class="jp-list-bar">' + printMenuHtml('VOCAB', filter) + '</div>'
      + vocabFiltersHtml(filter, p.facets) + vocabResultsHtml(p);
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

  function kanjiListHtml(page, view, filter) {
    var p = page || {};
    var items = list(p.items);
    var html = '<div class="jp-list-bar">' + printMenuHtml('KANJI', filter) + '</div>';
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">Không có chữ Kanji nào khớp bộ lọc.</p>';
    if (view === 'GRID') {
      html += '<ul class="jp-kanji-grid">' + items.map(kanjiCellHtml).join('') + '</ul>';
    } else {
      html += '<div class="jp-table-wrap"><table class="jp-table jp-table--kanji"><thead><tr><th scope="col">Chữ</th>'
        + '<th scope="col">Hán Việt</th><th scope="col">Nghĩa</th><th scope="col">On</th><th scope="col">Kun</th>'
        + '<th scope="col">Nét</th></tr></thead><tbody>' + items.map(kanjiRowHtml).join('') + '</tbody></table></div>';
    }
    return html + pagerHtml(p);
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

  function kanaHtml(rows, script) {
    var s = script === 'KATAKANA' ? 'KATAKANA' : 'HIRAGANA';
    var html = '<div class="jp-list-bar"><div class="jp-seg" role="group" aria-label="Bảng chữ">'
      + '<button type="button" class="jp-seg__btn" data-action="kana-script" data-script="HIRAGANA" aria-pressed="'
      + (s === 'HIRAGANA') + '">Hiragana</button><button type="button" class="jp-seg__btn" data-action="kana-script"'
      + ' data-script="KATAKANA" aria-pressed="' + (s === 'KATAKANA') + '">Katakana</button></div>'
      + '<button type="button" class="jp-btn" data-jpath-print="' + dataJson({ scope: 'KANA', script: s }) + '">In bảng chữ</button></div>';
    var items = list(rows).filter(Boolean);
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">Chưa có hàng chữ nào.</p>';
    html += '<ol class="jp-kana-rows">' + items.map(function (r) {
      var first = list(r.kanas).filter(Boolean)[0];
      return '<li class="jp-kana-row"><div class="jp-kana-row__head"><h3 class="jp-kana-row__title">' + esc(r.title)
        + '</h3>' + (r.passed ? '<span class="jp-ok jp-kana-row__passed">✓ Đã đạt</span>' : '')
        + (first ? '<button type="button" class="jp-link-btn" data-action="kana-row" data-row="' + esc(r.code)
          + '" data-start="' + esc(first.code) + '">Học hàng này</button>' : '') + '</div>'
        + '<div class="jp-kana-grid' + (r.kind === 'YOON' ? ' jp-kana-grid--yoon' : '') + '">'
        + kanaColumns(r).map(kanaCellHtml).join('') + '</div></li>';
    }).join('') + '</ol>';
    return html;
  }

  // ------------------------------------------------------------------------------------------------ grammar

  var PANE_MIN_WIDTH = 900;
  var MSG_QUEUED = 'Đã thêm vào lượt hôm nay.';

  function grammarOpenMode(width) { return num(width) >= PANE_MIN_WIDTH ? 'PANE' : 'PAGE'; }

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

  function grammarRowHtml(r, openCode) {
    var g = r || {};
    var open = has(openCode) && g.code === openCode;
    return '<li><button type="button" class="jp-grow' + (open ? ' jp-grow--open' : '') + '" data-action="open-grammar"'
      + ' data-code="' + esc(g.code) + '" aria-expanded="' + open + '">'
      + '<span class="jp-dot ' + dotClass(g.state) + '" aria-hidden="true"></span>'
      + '<span class="jp-sr-only">' + esc(STATE_LABEL[stateOf(g.state)]) + ': </span>'
      + '<span class="jp-grow__text"><b lang="ja">' + esc(g.pattern) + '</b>' + (has(g.meaning) ? ' · ' + esc(g.meaning) : '')
      + '</span><span class="jp-grow__mark" aria-hidden="true">' + (open ? '▾' : '▸') + '</span></button></li>';
  }

  function grammarAccordionHtml(units, filter, openCode, totals) {
    var f = filter || {};
    var items = list(units).filter(Boolean);
    var t = totals || {};
    var total = has(t.total) ? num(t.total) : items.reduce(function (s, u) { return s + num(u.total); }, 0);
    var done = has(t.done) ? num(t.done) : items.reduce(function (s, u) { return s + num(u.done); }, 0);
    var options = list(t.units).length ? list(t.units) : items;
    var html = '<div class="jp-list-filters" role="search">'
      + '<label class="jp-field"><span class="jp-field__label">Bài</span><select class="jp-input" data-filter="unit">'
      + optionHtml('', 'Tất cả bài', !has(f.unit))
      + options.map(function (u) { return optionHtml(u.unitNo, unitTitle(u), String(u.unitNo) === String(f.unit)); }).join('')
      + '</select></label>'
      + '<label class="jp-field jp-field--grow"><span class="jp-field__label">Tìm</span>'
      + '<input class="jp-input" type="search" data-filter="q" placeholder="Tìm mẫu, nghĩa…" value="' + esc(f.q || '')
      + '" autocomplete="off"></label>' + quizButtonHtml('GRAMMAR') + '</div>';
    html += '<p class="jp-grammar-sum">' + esc(done + '/' + total + ' mẫu đã xong') + ' · <span class="jp-legend">'
      + '<span class="jp-dot jp-dot-known" aria-hidden="true"></span> đã xong '
      + '<span class="jp-dot jp-dot-learning" aria-hidden="true"></span> đang học '
      + '<span class="jp-dot jp-dot-new" aria-hidden="true"></span> chưa học</span></p>';
    if (!items.length) return html + '<p class="jp-muted jp-list-empty">Không có mẫu nào khớp bộ lọc.</p>';
    return html + items.map(function (u) {
      return '<section class="jp-gunit"><h3 class="jp-gunit__title">' + esc(unitTitle(u))
        + ' <span class="jp-gunit__count">' + esc(num(u.done) + '/' + num(u.total) + ' mẫu') + '</span></h3>'
        + '<ul class="jp-gunit__rows">' + list(u.patterns).map(function (r) { return grammarRowHtml(r, openCode); }).join('')
        + '</ul></section>';
    }).join('');
  }

  function structureChips(formation) {
    if (!has(formation)) return '';
    var parts = String(formation).split(/\s*[+＋]\s*/).filter(function (p) { return p !== ''; });
    return '<span class="jp-struct" lang="ja">' + parts.map(function (p) {
      return '<span class="jp-struct__chip">' + esc(p) + '</span>';
    }).join('<span class="jp-struct__plus" aria-hidden="true">+</span>') + '</span>';
  }

  function lessonPart(label, body) {
    return body ? '<div class="jp-lesson__part"><span class="jp-lesson__label">' + esc(label) + '</span>' + body + '</div>' : '';
  }

  function textPart(label, value) { return has(value) ? lessonPart(label, '<p>' + esc(value) + '</p>') : ''; }

  function grammarLessonHtml(lesson) {
    var g = lesson || {};
    var state = stateOf(g.state);
    var html = '<article class="jp-lesson" data-jpath-report="'
      + dataJson({ targetType: 'GRAMMAR', targetCode: g.code, pageContext: 'GRAMMAR_LESSON' }) + '">';
    var eyebrow = ['NGỮ PHÁP ' + (g.level || ''), has(g.unitNo) ? 'BÀI ' + g.unitNo : null,
      num(g.unitSize) > 0 ? 'MẪU ' + num(g.indexInUnit) + '/' + num(g.unitSize) : null].filter(has).join(' · ');
    html += '<p class="jp-lesson__eyebrow">' + esc(eyebrow) + '</p>';
    html += '<h2 class="jp-lesson__pattern" id="jp-lesson-title" lang="ja">' + esc(g.pattern) + '</h2>';
    var variants = list(g.variants).filter(has);
    if (variants.length) html += '<p class="jp-muted" lang="ja">' + esc(variants.join(' · ')) + '</p>';
    html += textPart('Nghĩa', g.meaning);
    html += lessonPart('Cấu trúc', structureChips(g.formation));
    html += textPart('Cách dùng', g.usage) + textPart('Sắc thái', g.nuance) + textPart('Lỗi thường gặp', g.commonMistakes);
    var compare = list(g.related).filter(function (r) { return r && has(r.pattern); }).map(function (r) {
      return '<p class="jp-lesson__compare">' + esc('So sánh: ' + r.pattern + (has(r.meaning) ? ' (' + r.meaning + ')' : ''))
        + '</p>';
    }).join('');
    html += compare;
    var examples = list(g.examples).map(function (e) {
      var ex = F().exampleHtml(e);
      if (!ex) return '';
      return '<li class="jp-example">' + (e && has(e.audioUrl) ? '<button type="button" class="jp-icon-btn" data-action="play" data-audio="'
        + esc(e.audioUrl) + '" aria-label="Nghe câu ví dụ">🔊</button>' : '') + '<div>' + ex + '</div></li>';
    }).filter(Boolean).join('');
    html += lessonPart('Ví dụ', examples ? '<ul class="jp-examples">' + examples + '</ul>' : '');
    html += '</article>';
    if (g.practiceAvailable || g.extraAvailable) {
      html += '<section class="jp-lesson-practice no-inline-dictionary" data-practice="GRAMMAR_PRACTICE"'
        + ' aria-labelledby="jp-practice-title"><h3 class="jp-lesson-practice__title" id="jp-practice-title">Luyện ngay</h3>';
      if (g.practiceAvailable) {
        html += '<p class="jp-muted">1 câu xếp từ và 1 câu điền.</p><button type="button" class="jp-btn jp-btn--primary"'
          + ' data-action="grammar-practice" data-kind="GRAMMAR_PRACTICE">Luyện ngay</button>';
      }
      if (g.extraAvailable) {
        html += '<button type="button" class="jp-link-btn" data-action="grammar-practice" data-kind="GRAMMAR_EXTRA">'
          + '+ Luyện thêm 3 câu</button>';
      }
      html += '<div class="jp-lesson-practice__mount" data-part="practice-mount"></div>'
        + '<p class="jp-alert" role="alert" data-part="lesson-error" hidden></p></section>';
    } else {
      html += '<p class="jp-alert" role="alert" data-part="lesson-error" hidden></p>';
    }
    var foot = '';
    if (state === 'NEW' && g.learnable !== false) {
      foot += '<button type="button" class="jp-btn" data-action="queue-today">Học mẫu này trong lượt hôm nay</button>';
    }
    var nav = '';
    if (has(g.prevCode)) {
      nav += '<a class="jp-link-btn" data-action="open-grammar" data-code="' + esc(g.prevCode) + '" href="/learning/jlpt/grammar/'
        + encodeURIComponent(g.prevCode) + '">← Mẫu trước</a>';
    }
    if (has(g.nextCode)) {
      nav += '<a class="jp-link-btn" data-action="open-grammar" data-code="' + esc(g.nextCode) + '" href="/learning/jlpt/grammar/'
        + encodeURIComponent(g.nextCode) + '">Mẫu sau →</a>';
    }
    if (nav) foot += '<nav class="jp-lesson__nav" aria-label="Mẫu khác">' + nav + '</nav>';
    if (foot) html += '<div class="jp-lesson__foot">' + foot + '<p class="jp-status" role="status" data-part="lesson-status"></p></div>';
    return html;
  }

  function lessonUrl(code) { return BASE + '/grammar/' + encodeURIComponent(code); }

  /** Lesson buttons shared by the list pane and the lesson page. env = {api, navigate, returnTo, toast}. */
  function lessonAction(el, lesson, container, env) {
    var action = el.getAttribute('data-action');
    var g = lesson || {};
    if (action === 'play') { D().playAudio(el.getAttribute('data-audio')); return true; }
    if (action === 'grammar-practice') {
      el.disabled = true;
      env.api.send('POST', BASE + '/checks', { kind: el.getAttribute('data-kind') || 'GRAMMAR_PRACTICE', tab: 'GRAMMAR',
        level: g.level, itemCode: g.code }).then(function (res) {
        var token = res && (res.token || res.checkToken);
        var mountEl = container.querySelector('[data-part="practice-mount"]');
        var check = root && root.JpathCheck;
        if (token && check && typeof check.mountInline === 'function' && mountEl) {
          el.disabled = false;
          return check.mountInline(mountEl, token, {});
        }
        if (token) env.navigate(checkUrl(token, env.returnTo));
        return null;
      }).catch(function (e) {
        el.disabled = false;
        showError(container, 'lesson-error', messageOf(e));
      });
      return true;
    }
    if (action === 'queue-today') {
      el.disabled = true;
      env.api.send('POST', BASE + '/grammar/' + encodeURIComponent(g.code) + '/queue-today').then(function () {
        var ui = root && root.JlasUI;
        if (ui && typeof ui.toast === 'function') ui.toast(MSG_QUEUED, 'success');
        var status = container.querySelector('[data-part="lesson-status"]');
        if (status) status.textContent = MSG_QUEUED;
      }, function (e) {
        el.disabled = false;
        showError(container, 'lesson-error', messageOf(e));
      });
      return true;
    }
    return false;
  }

  /** Draws the grammar lesson page (#jpathGrammarRoot[data-grammar-code]). */
  function mountGrammarLesson(rootEl, deps) {
    var d = deps || {};
    var doc = d.document || (root && root.document);
    var el = rootEl || (doc && doc.getElementById('jpathGrammarRoot'));
    if (!el) return Promise.resolve();
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var rendered = d.rendered || function (x) { D().rendered(x); };
    var code = el.getAttribute('data-grammar-code');
    var lesson = null;
    var env = { api: apiClient, navigate: d.navigate || function (u) { root.location.assign(u); },
      returnTo: ((loc && loc.pathname) || '/learning') + ((loc && loc.search) || '') };
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      var btn = t && typeof t.closest === 'function' ? t.closest('[data-action]') : null;
      if (!btn || btn.disabled || !lesson) return;
      lessonAction(btn, lesson, el, env);
    });
    return Promise.resolve().then(function () { return apiClient.get(lessonUrl(code)); }).then(function (view) {
      lesson = view || {};
      el.innerHTML = grammarLessonHtml(lesson);
      el.setAttribute('aria-busy', 'false');
      if (doc && has(lesson.pattern)) doc.title = lesson.pattern + ' - JLAS';
      rendered(el);
    }, function (e) {
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc(messageOf(e, 'Không tải được bài ngữ pháp.')) + '</p>';
      el.setAttribute('aria-busy', 'false');
    });
  }

  // ------------------------------------------------------------------------------------------------ drawer

  function section(label, body) {
    return body ? '<section class="jp-drawer__part"><h3 class="jp-drawer__label">' + esc(label) + '</h3>' + body
      + '</section>' : '';
  }

  function readingsHtml(readings, levelReadings) {
    return list(readings).filter(has).map(function (r) {
      return '<span lang="ja">' + F().kanjiReadingHtml(r, levelReadings) + '</span>';
    }).join('、');
  }

  function wordListHtml(words) {
    var rows = list(words).map(function (w) {
      var x = w || {};
      return '<li><span class="jp-ja" lang="ja">' + esc(x.expression) + '</span>'
        + (has(x.reading) ? ' <span class="jp-muted" lang="ja">' + esc(x.reading) + '</span>' : '')
        + (has(x.meaning) ? ' · ' + esc(x.meaning) : '') + (x.learned ? ' <span class="jp-ok">✓</span>' : '') + '</li>';
    }).join('');
    return rows ? '<ul class="jp-word-list">' + rows + '</ul>' : '';
  }

  function factRow(label, value) {
    return value ? '<div class="jp-fact"><dt>' + esc(label) + '</dt><dd>' + value + '</dd></div>' : '';
  }

  function drawerButtons(type, state) {
    var noNotebook = type === 'GRAMMAR' || type === 'KANA';
    if (state === 'NEW') {
      return '<button type="button" class="jp-btn jp-btn--primary" data-action="item-known">Đã biết (kiểm 1 câu)</button>';
    }
    if (state === 'LEARNING') {
      return '<button type="button" class="jp-btn jp-btn--primary" data-action="item-known">Đánh dấu Đã biết</button>'
        + (noNotebook ? '' : '<button type="button" class="jp-btn" data-action="notebook-add">Thêm vào sổ tay</button>');
    }
    if (state === 'NOTEBOOK') {
      return '<button type="button" class="jp-btn" data-action="notebook-remove">Bỏ khỏi sổ tay</button>';
    }
    return '';
  }

  function itemDrawerHtml(detail) {
    var d = detail || {};
    var type = String(d.itemType || 'VOCAB').toUpperCase();
    var state = stateOf(d.state);
    var k = d.kanji || {};
    var g = d.grammar || {};
    var kana = d.kana || {};
    var title = type === 'GRAMMAR' ? (g.pattern || d.expression) : (type === 'KANA' ? (kana.kana || d.expression)
      : d.expression);
    var kanjiInWord = list(d.kanjiInWord);
    var html = '<div class="jp-drawer" data-part="drawer" data-jpath-report="'
      + dataJson({ targetType: type, targetCode: d.code, pageContext: 'LIST' }) + '">';
    html += '<div class="jp-drawer__head">' + stateChip(state) + (d.hard ? ' <span class="jp-badge">Khó nhớ</span>' : '')
      + '<button type="button" class="jp-drawer__x" data-dialog-close aria-label="Đóng">×</button></div>';
    html += '<h2 class="jp-drawer__title jp-kanji" id="jp-dialog-title" lang="ja">' + esc(title) + '</h2>';
    if (has(d.reading) && d.reading !== title) html += '<p class="jp-drawer__reading jp-ja" lang="ja">' + esc(d.reading) + '</p>';
    var romaji = d.romaji || (type === 'KANA' ? kana.romaji : null);
    if (has(romaji)) html += '<p class="jp-romaji">' + esc(romaji) + '</p>';
    if (has(d.meaning)) html += '<p class="jp-drawer__meaning">' + esc(d.meaning) + '</p>';
    var meta = [d.wordType, d.topic].filter(has).join(' · ');
    if (meta) html += '<p class="jp-muted">' + esc(meta) + '</p>';
    if (has(d.audioUrl)) {
      html += '<button type="button" class="jp-btn" data-action="play" data-audio="' + esc(d.audioUrl)
        + '" aria-label="Nghe phát âm">🔊 Nghe</button>';
    }
    if (type === 'KANJI') {
      var facts = factRow('Hán Việt', esc([k.hanViet].concat(list(k.hanVietAlt)).filter(has).join(', ').toUpperCase()))
        + factRow('Âm On', readingsHtml(k.readingsOn, k.levelReadings))
        + factRow('Âm Kun', readingsHtml(k.readingsKun, k.levelReadings))
        + factRow('Số nét', has(k.strokeCount) ? esc(k.strokeCount) : '')
        + factRow('Bộ', has(k.radicalNumber) || has(k.radicalName)
          ? esc([k.radicalName, has(k.radicalNumber) ? '(' + k.radicalNumber + ')' : null].filter(has).join(' ')) : '');
      if (facts) html += '<dl class="jp-facts">' + facts + '</dl>';
    }
    if (type === 'GRAMMAR') {
      html += section('Cấu trúc', has(g.formation) ? '<p lang="ja">' + esc(g.formation) + '</p>' : '')
        + section('Cách dùng', has(g.usage) ? '<p>' + esc(g.usage) + '</p>' : '')
        + section('Sắc thái', has(g.nuance) ? '<p>' + esc(g.nuance) + '</p>' : '');
    }
    html += section('Kanji trong từ', kanjiInWord.length ? '<ul class="jp-word-list">' + kanjiInWord.map(function (c) {
      var x = c || {};
      return '<li><span class="jp-kanji jp-drawer__char" lang="ja">' + esc(x.character) + '</span>'
        + (has(x.hanViet) ? ' ' + esc(String(x.hanViet).toUpperCase()) : '') + (has(x.meaning) ? ' · ' + esc(x.meaning) : '')
        + '</li>';
    }).join('') + '</ul>' : '');
    html += section('Từ có chữ này', wordListHtml(d.compounds));
    var examples = list(d.examples).map(function (e) {
      var ex = F().exampleHtml(e);
      if (!ex) return '';
      return '<li class="jp-example">' + (e && has(e.audioUrl) ? '<button type="button" class="jp-icon-btn" data-action="play" data-audio="'
        + esc(e.audioUrl) + '" aria-label="Nghe câu ví dụ">🔊</button>' : '') + '<div>' + ex + '</div></li>';
    }).filter(Boolean).join('');
    html += section('Ví dụ', examples ? '<ul class="jp-examples">' + examples + '</ul>' : '');
    html += section('Mẹo nhớ', has(d.mnemonic) ? '<p>' + esc(d.mnemonic) + '</p>' : '');
    html += section('Dễ nhầm với', wordListHtml(d.confusables));
    var writable = type === 'KANJI' || type === 'KANA' || (type === 'VOCAB' && kanjiInWord.length > 0);
    if (writable && has(d.code)) {
      html += '<div class="jp-drawer__writing" data-jpath-writing="' + dataJson({ itemType: type, code: d.code }) + '"></div>';
    }
    var buttons = drawerButtons(type, state);
    if (buttons) html += '<div class="jp-drawer__actions">' + buttons + '</div>';
    html += '<p class="jp-alert" role="alert" data-part="drawer-error" hidden></p>';
    return html + '</div>';
  }

  // ------------------------------------------------------------------------------------------------ free quiz

  function radio(name, value, label, checked, disabled) {
    return '<label class="jp-choice"><input type="radio" name="' + name + '" value="' + esc(value) + '"'
      + (checked ? ' checked' : '') + (disabled ? ' disabled' : '') + '><span>' + esc(label) + '</span></label>';
  }

  function freeQuizDialogHtml(tab, ctx) {
    var c = ctx || {};
    var t = tabCode(tab);
    var grammar = t === 'GRAMMAR';
    var display = c.display || {};
    var timerOn = grammar ? display.grammarTimerOn !== false : !!display.quizTimerOn;
    var html = '<h2 class="jp-dialog__title" id="jp-dialog-title">Luyện quiz</h2>'
      + '<form class="jp-quiz-form no-inline-dictionary" data-part="quiz-form" data-tab="' + esc(t) + '">';
    html += '<fieldset class="jp-fieldset"><legend>Số câu</legend><div class="jp-choices">'
      + [5, 10, 20].map(function (n) { return radio('count', n, n + ' câu', n === 10, n === 5 && !!c.skillDay); }).join('')
      + '</div>' + (c.skillDay ? '<p class="jp-muted">Ngày kỹ năng cần quiz từ 10 câu.</p>' : '') + '</fieldset>';
    if (grammar) {
      html += '<label class="jp-field"><span class="jp-field__label">Bài</span><select class="jp-input" name="unit">'
        + optionHtml('', 'Tất cả bài', !has(c.unit))
        + list(c.units).map(function (u) {
          return optionHtml(u.unitNo, 'Bài ' + u.unitNo, String(u.unitNo) === String(c.unit));
        }).join('') + '</select></label>';
    }
    var sources = grammar ? [['LEARNED', 'Đã học'], ['ALL', 'Tất cả']]
      : [['LEARNED', 'Đã học'], ['NOTEBOOK', 'Sổ tay'], ['ALL', 'Tất cả']];
    html += '<fieldset class="jp-fieldset"><legend>Nguồn câu hỏi</legend><div class="jp-choices">'
      + sources.map(function (s, i) { return radio('source', s[0], s[1], i === 0); }).join('') + '</div></fieldset>';
    if (!grammar) {
      html += '<fieldset class="jp-fieldset"><legend>Chiều hỏi</legend><div class="jp-choices">'
        + radio('direction', 'JA_VI', 'Nhật → Việt', false) + radio('direction', 'VI_JA', 'Việt → Nhật', false)
        + radio('direction', 'MIXED', 'Xen kẽ', true) + '</div></fieldset>';
    }
    html += '<label class="jp-choice"><input type="checkbox" name="timer"' + (timerOn ? ' checked' : '')
      + '><span>Bật đồng hồ</span></label>';
    html += '<p class="jp-alert" role="alert" data-part="quiz-error" hidden></p>'
      + '<button type="submit" class="jp-btn jp-btn--primary" data-action="quiz-start">Bắt đầu</button></form>';
    return html;
  }

  function freeQuizRequest(values) {
    var v = values || {};
    var tab = tabCode(v.tab);
    var grammar = tab === 'GRAMMAR';
    var count = Number(v.count) || 10;
    if (v.skillDay && count < 10) count = 10;
    var req = { kind: 'FREE_QUIZ', tab: tab, level: v.level || null, topicKey: grammar ? null : (v.topicKey || null),
      source: v.source || 'LEARNED', count: count, direction: grammar ? null : (v.direction || 'MIXED'),
      timerOn: !!v.timerOn };
    if (grammar && has(v.unitNo)) req.unitNo = Number(v.unitNo);
    return req;
  }

  function readQuizForm(form) {
    function checked(name) {
      var el = form.querySelector('input[name="' + name + '"]:checked');
      return el ? el.value : null;
    }
    var unit = form.querySelector('select[name="unit"]');
    var timer = form.querySelector('input[name="timer"]');
    return { count: checked('count'), source: checked('source'), direction: checked('direction'),
      unitNo: unit ? unit.value : null, timerOn: !!(timer && timer.checked) };
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

  // ------------------------------------------------------------------------------------------------ mount

  var TABS = {};

  TABS.vocab = {
    urlKeys: ['topic', 'state', 'q'],
    init: function (st, params) {
      st.filter = { topic: params.topic || '', state: params.state || '', q: params.q || '', page: 0 };
      st.view = (st.ctx.display && st.ctx.display.vocabListView) === 'FLAT' ? 'FLAT' : 'TOPICS';
    },
    load: function (st) {
      var f = st.filter;
      var level = st.ctx.level;
      var bar = '<div class="jp-list-bar">' + viewToggleHtml(has(f.topic) ? 'FLAT' : st.view) + quizButtonHtml('VOCAB')
        + '</div>';
      if (st.view === 'TOPICS' && !has(f.topic)) {
        return st.api.get(listUrl('topics', { level: level })).then(function (topics) {
          st.topics = list(topics);
          return bar + printMenuHtml('VOCAB', { level: level }) + topicGridHtml(st.topics, level);
        });
      }
      return st.api.get(listUrl('vocabulary', { level: level, topic: f.topic, state: f.state, q: f.q, page: f.page }))
        .then(function (page) {
          st.facets = (page && page.facets) || {};
          return bar + vocabTableHtml(page, { level: level, topic: f.topic, state: f.state, q: f.q });
        });
    }
  };

  TABS.kanji = {
    urlKeys: ['q', 'radical', 'sort', 'view'],
    init: function (st, params) {
      var sort = String(params.sort || '').toUpperCase();
      st.filter = { q: params.q || '', radical: /^\d+$/.test(params.radical || '') ? params.radical : '',
        sort: KANJI_SORTS.some(function (s) { return s[0] === sort; }) ? sort : '',
        view: String(params.view || '').toUpperCase() === 'GRID' ? 'GRID' : '', page: 0 };
    },
    load: function (st) {
      var f = st.filter;
      var level = st.ctx.level;
      return st.api.get(listUrl('kanji', { level: level, q: f.q, radical: f.radical, sort: f.sort, view: f.view,
        page: f.page })).then(function (page) {
        var current = { level: level, q: f.q, radical: f.radical, sort: f.sort, view: f.view };
        return '<div class="jp-list-bar">' + quizButtonHtml('KANJI') + '</div>'
          + kanjiFiltersHtml(current, page && page.facets) + kanjiListHtml(page, f.view === 'GRID' ? 'GRID' : 'TABLE', current);
      });
    }
  };

  TABS.kana = {
    urlKeys: ['script'],
    init: function (st, params) {
      st.filter = { script: String(params.script || '').toUpperCase() === 'KATAKANA' ? 'KATAKANA' : 'HIRAGANA' };
    },
    load: function (st) {
      return st.api.get(listUrl('kana', { script: st.filter.script })).then(function (rows) {
        return kanaHtml(rows, st.filter.script);
      });
    }
  };

  TABS.grammar = {
    urlKeys: ['unit', 'q', 'open'],
    init: function (st, params) {
      st.filter = { unit: /^\d+$/.test(params.unit || '') ? params.unit : '', q: params.q || '',
        open: /^JG-[0-9a-f]{10}$/.test(params.open || '') ? params.open : '' };
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
        ? st.api.get(lessonUrl(f.open)).then(function (v) { return v; }, function () { return null; }) : Promise.resolve(null);
      return Promise.all([all, shown, pane]).then(function (res) {
        st.units = res[0];
        st.lesson = res[2];
        var totals = { units: res[0], total: res[0].reduce(function (s, u) { return s + num(u.total); }, 0),
          done: res[0].reduce(function (s, u) { return s + num(u.done); }, 0) };
        var accordion = grammarAccordionHtml(res[1], f, mode === 'PANE' ? f.open : null, totals);
        if (mode !== 'PANE') return accordion;
        var paneHtml = st.lesson ? grammarLessonHtml(st.lesson)
          : '<p class="jp-muted jp-grammar-pane__hint">Chọn một mẫu để xem bài học.</p>';
        return '<div class="jp-grammar-split"><div class="jp-grammar-list">' + accordion + '</div>'
          + '<div class="jp-grammar-pane" data-part="pane" aria-live="polite">' + paneHtml + '</div></div>';
      });
    }
  };

  function widthOf(ctx) {
    if (ctx && typeof ctx.width === 'function') return ctx.width();
    return (root && root.innerWidth) || 0;
  }

  function openGrammar(slot, st, el, ev) {
    var code = el.getAttribute('data-code');
    if (!/^JG-[0-9a-f]{10}$/.test(code || '')) return null;
    var link = typeof el.getAttribute === 'function' && el.getAttribute('href');
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
    var req = { tab: 'KANA', mode: 'BROWSE', startItemCode: el.getAttribute('data-start') };
    el.disabled = true;
    return st.api.send('POST', BASE + '/sessions', req).then(function (res) {
      var id = res && (res.sessionId != null ? res.sessionId : res.id);
      st.navigate('/learning/jlpt/study?session=' + encodeURIComponent(id));
    }, function (e) {
      el.disabled = false;
      showError(slot, 'list-error', messageOf(e));
    });
  }

  function mountTab(slot, tabKey, ctx) {
    if (!slot) return Promise.resolve();
    var key = String(tabKey || '').toLowerCase();
    var st = slot.__jpathLists;
    if (!st) {
      st = { seq: 0, filters: {}, timer: null };
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
    st.key = key;
    var spec = TABS[key];
    if (!spec) { slot.innerHTML = ''; return Promise.resolve(); }
    if (!st.filters[key]) {
      var tabState = { ctx: c };
      spec.init(tabState, readParams(st.env.location));
      st.filters[key] = tabState;
    }
    return reload(slot, st);
  }

  function tabState(st) {
    var t = st.filters[st.key];
    t.ctx = st.ctx;
    t.api = st.api;
    return t;
  }

  function reload(slot, st, focusFilter) {
    var spec = TABS[st.key];
    if (!spec) return Promise.resolve();
    var seq = ++st.seq;
    var key = st.key;
    return Promise.resolve().then(function () { return spec.load(tabState(st), slot); }).then(function (html) {
      if (seq !== st.seq || key !== st.key) return;
      slot.innerHTML = html + '<p class="jp-alert" role="alert" data-part="list-error" hidden></p>';
      restoreFocus(slot, focusFilter);
      st.rendered(slot);
    }, function (e) {
      if (seq !== st.seq) return;
      slot.innerHTML = '<p class="jp-alert" role="alert">' + esc(messageOf(e, MSG_LOAD)) + '</p>';
    });
  }

  function restoreFocus(slot, name) {
    if (!name || typeof slot.querySelector !== 'function') return;
    var el = slot.querySelector('[data-filter="' + name + '"]');
    if (!el || typeof el.focus !== 'function') return;
    el.focus();
    if (typeof el.setSelectionRange === 'function' && typeof el.value === 'string') {
      try { el.setSelectionRange(el.value.length, el.value.length); } catch (e) { /* not a text input */ }
    }
  }

  function showError(container, part, message) {
    var el = container && typeof container.querySelector === 'function'
      ? container.querySelector('[data-part="' + part + '"]') : null;
    if (!el) return;
    el.textContent = message;
    el.hidden = false;
  }

  function currentReturn(st) {
    var loc = st.env.location || {};
    return (loc.pathname || '/learning') + (loc.search || '');
  }

  function applyFilter(slot, st, name, value) {
    var t = tabState(st);
    t.filter[name] = value;
    if (name !== 'page') t.filter.page = 0;
    var urlValues = {};
    (TABS[st.key].urlKeys || []).forEach(function (k) { urlValues[k] = t.filter[k]; });
    writeParams(st.env, urlValues);
    return reload(slot, st, name === 'page' ? null : name);
  }

  function openDialog(st, html, opener) {
    var dlg = DLG();
    return dlg ? dlg.open(st.doc, html, opener) : null;
  }

  function openItem(slot, st, type, code, opener) {
    return st.api.get(itemUrl(type, code)).then(function (detail) {
      var handle = openDialog(st, itemDrawerHtml(detail), opener);
      if (!handle) return null;
      handle.el.classList.add('jp-dialog--drawer');
      var current = detail || {};
      function redraw() {
        var box = handle.el.querySelector('[data-part="drawer"]');
        if (box) box.outerHTML = itemDrawerHtml(current);
        st.rendered(handle.el);
      }
      handle.el.addEventListener('click', function (ev) {
        var t = ev.target;
        var el = t && typeof t.closest === 'function' ? t.closest('[data-action]') : null;
        if (!el) return;
        var action = el.getAttribute('data-action');
        var url = itemUrl(current.itemType, current.code);
        var call = null;
        if (action === 'play') { D().playAudio(el.getAttribute('data-audio')); return; }
        if (action === 'item-known') {
          call = st.api.send('POST', url + '/known').then(function (res) {
            if (res && res.checkToken) { st.navigate(checkUrl(res.checkToken, currentReturn(st))); return; }
            if (res && res.state) current.state = res.state;
            redraw();
          });
        } else if (action === 'notebook-add' || action === 'notebook-remove') {
          call = st.api.send(action === 'notebook-add' ? 'POST' : 'DELETE', url + '/notebook').then(function (res) {
            if (res && res.state) current.state = res.state;
            redraw();
          });
        }
        if (!call) return;
        el.disabled = true;
        call.then(function () { reload(slot, st); }, function (e) {
          el.disabled = false;
          showError(handle.el, 'drawer-error', messageOf(e));
        });
      });
      st.rendered(handle.el);
      return handle;
    }, function (e) { showError(slot, 'list-error', messageOf(e, 'Không tải được mục này.')); });
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
    return Promise.resolve(ask(msg)).then(function (ok) {
      if (!ok) return null;
      el.disabled = true;
      return st.api.send('POST', BASE + '/topics/' + encodeURIComponent(level) + '/' + encodeURIComponent(key)
        + '/known-check').then(function (res) {
        var token = res && (res.token || res.checkToken);
        if (token) st.navigate(checkUrl(token, currentReturn(st)));
      });
    }).catch(function (e) {
      el.disabled = false;
      showError(slot, 'list-error', messageOf(e));
    });
  }

  function freeQuiz(slot, st, el) {
    var tab = el.getAttribute('data-tab') || TAB_CODE[st.key];
    var t = tabState(st);
    var qctx = { display: st.ctx.display, skillDay: !!st.ctx.skillDay, units: t.units, unit: t.filter && t.filter.unit };
    var handle = openDialog(st, freeQuizDialogHtml(tab, qctx), el);
    if (!handle) return null;
    var form = handle.el.querySelector('[data-part="quiz-form"]');
    if (!form) return handle;
    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var values = readQuizForm(form);
      values.tab = tab;
      values.level = st.ctx.level;
      values.topicKey = t.filter && t.filter.topic;
      values.skillDay = !!st.ctx.skillDay;
      var btn = form.querySelector('[data-action="quiz-start"]');
      if (btn) btn.disabled = true;
      st.api.send('POST', BASE + '/checks', freeQuizRequest(values)).then(function (res) {
        var token = res && (res.token || res.checkToken);
        if (token) st.navigate(checkUrl(token, currentReturn(st)));
      }, function (e) {
        if (btn) btn.disabled = false;
        showError(handle.el, 'quiz-error', messageOf(e));
      });
    });
    return handle;
  }

  function saveListView(st, view) {
    if (st.ctx.display) st.ctx.display.vocabListView = view;
    var p = st.api.send('PUT', SETTINGS_URL, { vocabListView: view });
    if (p && typeof p.catch === 'function') p.catch(function () { /* the view still changes on this page */ });
  }

  function bind(slot, st) {
    var actions = {
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
      'open-item': function (el) { return openItem(slot, st, el.getAttribute('data-type'), el.getAttribute('data-code'), el); },
      'topic-known': function (el) { return topicKnown(slot, st, el); },
      'free-quiz': function (el) { return freeQuiz(slot, st, el); },
      'kanji-view': function (el) {
        return applyFilter(slot, st, 'view', el.getAttribute('data-view') === 'GRID' ? 'GRID' : '');
      },
      'kana-script': function (el) { return applyFilter(slot, st, 'script', el.getAttribute('data-script')); },
      'kana-row': function (el) { return startKanaRow(slot, st, el); },
      'open-grammar': function (el, ev) { return openGrammar(slot, st, el, ev); }
    };
    function paneAction(el) {
      var t = tabState(st);
      if (st.key !== 'grammar' || !t.lesson || typeof slot.querySelector !== 'function') return false;
      var pane = slot.querySelector('[data-part="pane"]');
      return !!pane && lessonAction(el, t.lesson, pane, { api: st.api, navigate: st.navigate, returnTo: currentReturn(st) });
    }
    st.actions = actions;
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
      var t = ev.target;
      if (!t || typeof t.getAttribute !== 'function' || t.getAttribute('data-filter') !== 'q') return;
      if (st.timer !== null) st.clearT(st.timer);
      var value = t.value;
      st.timer = st.setT(function () { st.timer = null; applyFilter(slot, st, 'q', String(value).trim()); },
        SEARCH_DELAY_MS);
    });
  }

  return {
    STATE_LABEL: STATE_LABEL, SEARCH_DELAY_MS: SEARCH_DELAY_MS, listUrl: listUrl, viewToggleHtml: viewToggleHtml,
    topicGridHtml: topicGridHtml, vocabTableHtml: vocabTableHtml, printMenuHtml: printMenuHtml,
    itemDrawerHtml: itemDrawerHtml, freeQuizDialogHtml: freeQuizDialogHtml, freeQuizRequest: freeQuizRequest,
    safeReturn: safeReturn, checkUrl: checkUrl, mountTab: mountTab, messageOf: messageOf,
    kanjiFiltersHtml: kanjiFiltersHtml, kanjiListHtml: kanjiListHtml, radicalChar: radicalChar,
    kanaColumns: kanaColumns, kanaHtml: kanaHtml,
    grammarAccordionHtml: grammarAccordionHtml, grammarLessonHtml: grammarLessonHtml, structureChips: structureChips,
    grammarOpenMode: grammarOpenMode, mountGrammarLesson: mountGrammarLesson
  };
});
