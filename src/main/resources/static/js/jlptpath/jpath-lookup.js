/* [JLAS-W0108] jpath-lookup.js — Look up of grammar patterns on JLPT path pages (plan Task 62).
 *
 * window.JpathLookup = { shouldLookup(text), cardMarkup(view), mount() }
 *   - Listens to pointerup / keyup like inline-dictionary.js; the selection text drops <rt>/<rp> and is looked up only
 *     inside [data-inline-dictionary] and outside .no-inline-dictionary (JlasInlineDictionary.isEligibleNode).
 *   - Waits 150 ms, numbers each request and drops a late answer; GET /api/learning/jlpt/lookup/grammar?q=.
 *   - Draws <aside class="jp-lookup-grammar" role="dialog"> under the open dictionary popup, else under the selection;
 *     at ≤ 640 px it is pinned to the bottom. Esc or a click outside closes it.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathLookup = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var URL = '/api/learning/jlpt/lookup/grammar?q=';
  var DELAY_MS = 150;
  var MAX_LEN = 100;
  var JA = /[぀-ヿ㐀-鿿]/;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function clean(text) { return String(text == null ? '' : text).normalize('NFKC').replace(/\s+/g, ''); }

  /** A selection worth asking about: at least two characters, some Japanese, at most 100 characters. */
  function shouldLookup(text) {
    var t = clean(text);
    return t.length >= 2 && t.length <= MAX_LEN && JA.test(t);
  }

  function ruby(text) {
    var F = root && root.JpathFurigana;
    return F && typeof F.toRuby === 'function' ? F.toRuby(text) : esc(text);
  }

  /** One pattern card: pattern, meaning, formation, the MAIN example (furigana when known) and the lesson link. */
  function cardMarkup(v) {
    if (!v) return '';
    var html = '<article class="jp-lookup-grammar__card"><header><strong lang="ja">' + esc(v.pattern) + '</strong>'
      + (v.level ? ' <span class="jp-lookup-grammar__level">' + esc(v.level) + '</span>' : '') + '</header>';
    if (v.meaning) html += '<p class="jp-lookup-grammar__meaning">' + esc(v.meaning) + '</p>';
    if (v.formation) html += '<p class="jp-lookup-grammar__formation" lang="ja">' + esc(v.formation) + '</p>';
    if (v.exampleJa) {
      html += '<div class="jp-lookup-grammar__example"><p lang="ja">' + (v.exampleFurigana ? ruby(v.exampleFurigana)
        : esc(v.exampleJa)) + '</p>' + (v.exampleVi ? '<p>' + esc(v.exampleVi) + '</p>' : '') + '</div>';
    }
    var href = /^\/learning\/jlpt\/grammar\/[A-Za-z0-9-]+$/.test(String(v.url || '')) ? v.url : null;
    if (href) html += '<a class="jp-link-btn" href="' + esc(href) + '">Xem bài mẫu</a>';
    return html + '</article>';
  }

  var panel = null;
  var seq = 0;
  var timer = null;

  function close() {
    seq++;
    if (panel) panel.hidden = true;
  }

  function ensurePanel(doc) {
    if (panel && panel.isConnected) return panel;
    panel = doc.createElement('aside');
    panel.className = 'jp-lookup-grammar';
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', 'Mẫu ngữ pháp');
    panel.hidden = true;
    doc.body.appendChild(panel);
    return panel;
  }

  function place(p, doc, rect) {
    if (root.matchMedia && root.matchMedia('(max-width: 640px)').matches) {
      p.classList.add('is-pinned');
      p.style.top = '';
      p.style.left = '';
      return;
    }
    p.classList.remove('is-pinned');
    var dict = doc.querySelector('.inline-dict:not([hidden])');
    var box = dict ? dict.getBoundingClientRect() : rect;
    var top = (box ? box.bottom : 0) + 8 + (root.scrollY || 0);
    var left = Math.max(8, (box ? box.left : 8) + (root.scrollX || 0));
    p.style.top = top + 'px';
    p.style.left = left + 'px';
  }

  function selectionInfo(doc) {
    var sel = root.getSelection && root.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
    var D = root.JlasInlineDictionary;
    var range = sel.getRangeAt(0);
    var text = D && typeof D.rangeText === 'function' ? D.rangeText(range) : String(sel);
    var node = sel.anchorNode && (sel.anchorNode.nodeType === 3 ? sel.anchorNode.parentElement : sel.anchorNode);
    var eligible = D && typeof D.isEligibleNode === 'function' ? D.isEligibleNode(node, text)
      : !!(node && node.closest && node.closest('[data-inline-dictionary]')
        && !node.closest('input,textarea,[contenteditable="true"],.no-inline-dictionary'));
    if (!eligible || !shouldLookup(text)) return null;
    return { text: clean(text), rect: range.getBoundingClientRect() };
  }

  function lookup(doc) {
    var info = selectionInfo(doc);
    if (!info) return;
    var current = ++seq;
    var get = root.JpathApi && root.JpathApi.get;
    if (typeof get !== 'function') return;
    get(URL + encodeURIComponent(info.text)).then(function (views) {
      if (current !== seq) return;
      var list = Array.isArray(views) ? views : [];
      if (!list.length) { close(); return; }
      var p = ensurePanel(doc);
      p.innerHTML = list.map(cardMarkup).join('');
      p.hidden = false;
      place(p, doc, info.rect);
    }, function () { if (current === seq) close(); });
  }

  function schedule(doc) {
    if (timer) root.clearTimeout(timer);
    // after inline-dictionary.js has opened its popup, so the card goes under it
    timer = root.setTimeout(function () { timer = null; lookup(doc); }, DELAY_MS);
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    if (mounted || !doc || typeof doc.addEventListener !== 'function') return;
    mounted = true;
    doc.addEventListener('pointerup', function (e) {
      if (panel && panel.contains(e.target)) return;
      schedule(doc);
    });
    doc.addEventListener('keyup', function (e) {
      if (e.key === 'Escape') { close(); return; }
      if (panel && panel.contains(e.target)) return;
      if (e.key === 'Shift' || String(e.key).indexOf('Arrow') === 0) schedule(doc);
    });
    doc.addEventListener('pointerdown', function (e) {
      if (panel && !panel.hidden && !panel.contains(e.target)) close();
    });
  }

  return { shouldLookup: shouldLookup, cardMarkup: cardMarkup, mount: mount };
});
