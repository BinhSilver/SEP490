/* [JLAS-W0108] jpath-wordbank.js — "Kho đã biết" and "Sổ tay" pages of the JLPT path (plan Task 68).
 *
 * window.JpathWordBank = { rowMarkup(row, page), pagerState(page), mount() }
 *   - <main data-jpath-wordbank="KNOWN|NOTEBOOK">: tabs Từ vựng / Kanji; GET /api/learning/jlpt/known|notebook?tab=&page=
 *     (0-based pages of the server's size). Each row has Báo sai (data-jpath-report, pageContext KNOWN_LIST /
 *     NOTEBOOK_LIST) and Luyện viết (data-jpath-writing); the notebook adds "Bỏ khỏi sổ tay" → DELETE
 *     /items/{type}/{code}/notebook. 'jpath:rendered' is sent after each draw so the report and writing modules bind.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathWordBank = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var CONTEXT = { KNOWN: 'KNOWN_LIST', NOTEBOOK: 'NOTEBOOK_LIST' };
  var EMPTY = { KNOWN: 'Chưa có mục nào bạn đã biết ở tab này.', NOTEBOOK: 'Sổ tay đang trống ở tab này.' };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function attrJson(o) { return esc(JSON.stringify(o)); }

  /** Pager of a JpathPage: page count, previous / next available. */
  function pagerState(p) {
    var size = Math.max(1, Number(p && p.size) || 20);
    var total = Math.max(0, Number(p && p.total) || 0);
    var page = Math.max(0, Number(p && p.page) || 0);
    var pages = Math.max(1, Math.ceil(total / size));
    return { pages: pages, page: page, hasPrev: page > 0, hasNext: page + 1 < pages };
  }

  /** One row; {@code page} is KNOWN or NOTEBOOK (the notebook gets the remove button). */
  function rowMarkup(r, page) {
    if (!r) return '';
    // AI 1 #16: the Chữ cái tab returns kana cells {code, kana, romaji}; show them like a word row
    if (r.kana && !r.expression) r = { itemType: 'KANA', code: r.code, expression: r.kana, reading: '', meaning: r.romaji };
    var type = r.itemType || 'VOCAB';
    var report = { targetType: type, targetCode: r.code, pageContext: CONTEXT[page] || 'KNOWN_LIST' };
    var writable = type === 'VOCAB' || type === 'KANJI';
    return '<li class="jp-bank-row" data-jpath-report="' + attrJson(report) + '">'
      + '<span class="jp-bank-row__word"><strong lang="ja"' + (type === 'KANJI' ? ' class="jp-kanji"' : '') + '>'
      + esc(r.expression) + '</strong>' + (r.reading ? ' <span lang="ja">' + esc(r.reading) + '</span>' : '') + '</span>'
      + '<span class="jp-bank-row__meaning">' + esc(r.meaning) + '</span>'
      + '<span class="jp-bank-row__actions">'
      + (writable ? '<button type="button" class="jp-side-btn jp-side-btn--small" data-jpath-writing="'
        + attrJson({ itemType: type, code: r.code }) + '">Luyện viết</button>' : '')
      + (page === 'NOTEBOOK' ? '<button type="button" class="jp-side-btn jp-side-btn--small" data-remove="'
        + esc(r.code) + '" data-type="' + esc(type) + '">Bỏ khỏi sổ tay</button>' : '')
      + '</span></li>';
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-wordbank]');
    if (mounted || !main || !root.JpathApi) return;
    mounted = true;
    var api = root.JpathApi;
    var kind = main.getAttribute('data-jpath-wordbank') === 'NOTEBOOK' ? 'NOTEBOOK' : 'KNOWN';
    var status = main.querySelector('[data-role="status"]');
    var list = main.querySelector('[data-role="rows"]');
    var pager = main.querySelector('[data-role="pager"]');
    var search = main.querySelector('[data-role="search"]');
    var state = { tab: 'VOCAB', page: 0 };
    var items = [];

    function draw() {
      var term = (search.value || '').trim().toLocaleLowerCase();
      var visible = items.filter(function (r) {
        return !term || [r.expression, r.reading, r.meaning].some(function (v) {
          return String(v || '').toLocaleLowerCase().indexOf(term) >= 0;
        });
      });
      list.innerHTML = visible.map(function (r) { return rowMarkup(r, kind); }).join('');
      if (term) status.textContent = visible.length ? visible.length + ' mục trong trang này' : 'Không tìm thấy mục trong trang này.';
      if (typeof doc.dispatchEvent === 'function' && typeof root.CustomEvent === 'function') {
        doc.dispatchEvent(new root.CustomEvent('jpath:rendered', { detail: { root: list } }));
      }
    }

    function load() {
      main.querySelectorAll('[data-tab]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-tab') === state.tab));
      });
      status.textContent = 'Đang tải…';
      var url = BASE + (kind === 'NOTEBOOK' ? '/notebook' : '/known') + '?tab=' + state.tab + '&page=' + state.page;
      return api.get(url).then(function (p) {
        items = (p && Array.isArray(p.items)) ? p.items : [];
        var ps = pagerState(p);
        if (!items.length && state.page > 0) { state.page = ps.pages - 1; return load(); }
        status.textContent = items.length ? (Number(p.total) || items.length) + ' mục' : EMPTY[kind];
        var summary = main.querySelector('[data-role="summary"]');
        if (summary) summary.textContent = (Number(p.total) || items.length) + (kind === 'NOTEBOOK' ? ' mục trong sổ tay' : ' mục đã biết');
        pager.hidden = ps.pages <= 1;
        pager.querySelector('[data-page="prev"]').disabled = !ps.hasPrev;
        pager.querySelector('[data-page="next"]').disabled = !ps.hasNext;
        pager.querySelector('[data-role="page"]').textContent = 'Trang ' + (ps.page + 1) + '/' + ps.pages;
        draw();
      }, function (err) { status.textContent = (err && err.message) || 'Không tải được danh sách.'; });
    }

    main.addEventListener('click', function (e) {
      var t = e.target;
      var tab = t.closest('[data-tab]');
      if (tab) { state.tab = tab.getAttribute('data-tab'); state.page = 0; search.value = ''; load(); return; }
      var pg = t.closest('[data-page]');
      if (pg) { state.page += pg.getAttribute('data-page') === 'next' ? 1 : -1; state.page = Math.max(0, state.page); load(); return; }
      var rm = t.closest('[data-remove]');
      if (rm) {
        rm.disabled = true;
        api.send('DELETE', BASE + '/items/' + encodeURIComponent(rm.getAttribute('data-type')) + '/'
          + encodeURIComponent(rm.getAttribute('data-remove')) + '/notebook').then(load, function (err) {
          rm.disabled = false;
          status.textContent = (err && err.message) || 'Chưa bỏ được mục này khỏi sổ tay.';
        });
      }
    });
    search.addEventListener('input', draw);
    load();
  }

  return { rowMarkup: rowMarkup, pagerState: pagerState, mount: mount };
});
