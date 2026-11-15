/* [JLAS-W0108] jpath-extra-links.js — "Luyện thêm" strip of the JLPT path (plan Task 69).
 *
 * window.JpathExtraLinks = { linksMarkup(view), mount() }
 *   - Draws GET /api/learning/jlpt/extra-links into every [data-jpath-extra-links] not drawn yet (the path page and the
 *     done screen); runs on DOMContentLoaded, on every document 'jpath:rendered' and when such a host is added to the
 *     page. The view is fetched once.
 *   - An open link is <a href>; one not open is <span aria-disabled="true"> with its note.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathExtraLinks = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var URL = '/api/learning/jlpt/extra-links';
  var DRAWN = 'data-jpath-extra-links-drawn';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function safeUrl(u) {
    var s = String(u || '');
    return /^\/(?!\/)/.test(s) || /^https?:\/\//i.test(s) ? s : null;
  }

  function item(l) {
    var note = l.note ? ' <small>' + esc(l.note) + '</small>' : '';
    var href = l.available ? safeUrl(l.url) : null;
    if (href) {
      return '<li><a class="jp-extra__link" href="' + esc(href) + '">' + esc(l.label) + '</a>' + note + '</li>';
    }
    // a closed link says why inside its own row (the side column hid the trailing note: looked like a broken item)
    return '<li><span class="jp-extra__link is-off" aria-disabled="true"' + (l.note ? ' title="' + esc(l.note) + '"' : '') + '>'
      + esc(l.label) + (l.note ? ' <span class="jp-extra__lock">' + esc(l.note) + '</span>' : '') + '</span></li>';
  }

  /** The strip: practice tools, then the path's own pages. */
  function linksMarkup(view) {
    var v = view || {};
    var links = Array.isArray(v.links) ? v.links : [];
    var pages = Array.isArray(v.pages) ? v.pages : [];
    if (!links.length && !pages.length) return '';
    // folded (owner 04/11: the side column fits one screen); the summary is the heading
    return '<nav class="jp-extra" aria-label="Luyện thêm"><details class="jp-extra__fold"><summary class="jp-extra__title">Luyện thêm</summary>'
      + '<ul class="jp-extra__list">' + links.map(item).join('') + '</ul>'
      + (pages.length ? '<ul class="jp-extra__list jp-extra__list--pages">' + pages.map(item).join('') + '</ul>' : '')
      + '</details></nav>';
  }

  var view = null;
  var loading = null;

  function draw(doc) {
    var hosts = doc.querySelectorAll('[data-jpath-extra-links]:not([' + DRAWN + '])');
    if (!hosts.length) return;
    if (!view) {
      if (!loading && root.JpathApi && typeof root.JpathApi.get === 'function') {
        loading = root.JpathApi.get(URL).then(function (v) { view = v || {}; draw(doc); },
          function () { loading = null; });
      }
      return;
    }
    var html = linksMarkup(view);
    hosts.forEach(function (h) { h.innerHTML = html; h.setAttribute(DRAWN, ''); });
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    if (mounted || !doc || typeof doc.querySelectorAll !== 'function') return;
    mounted = true;
    draw(doc);
    doc.addEventListener('jpath:rendered', function () { draw(doc); });
    // the done screen adds its host after load without a 'jpath:rendered'
    if (typeof root.MutationObserver === 'function' && doc.body) {
      new root.MutationObserver(function () { draw(doc); }).observe(doc.body, { childList: true, subtree: true });
    }
  }

  return { linksMarkup: linksMarkup, mount: mount };
});
