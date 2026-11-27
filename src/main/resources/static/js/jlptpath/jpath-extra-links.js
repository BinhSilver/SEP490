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

  /**
   * Owner 23/11: inside the side menu ("Theo dõi việc học") the tools sit under one "Luyện thêm" row with a triangle
   * that unfolds the full list; pages the menu already links ({@code skipUrls}) are left out.
   */
  function navMarkup(view, skipUrls) {
    var v = view || {};
    var skip = skipUrls || [];
    var all = (Array.isArray(v.links) ? v.links : []).concat(Array.isArray(v.pages) ? v.pages : []);
    var seen = {};
    var rows = all.filter(function (l) {
      var key = String(l && l.url || '') + '|' + String(l && l.label || '');
      // QA 27/11 G4: the comm menu links its own "Sổ tay" (another url); a label the menu already has is skipped too
      if (!l || seen[key] || (l.url && skip.indexOf(l.url) >= 0) || (l.label && skip.indexOf('label:' + l.label) >= 0)) return false;
      seen[key] = true;
      return true;
    });
    if (!rows.length) return '';
    var row = function (l) {
      var href = l.available ? safeUrl(l.url) : null;
      if (href) return '<a class="jp-sidenav__link jp-sidenav__link--sub" href="' + esc(href) + '">' + esc(l.label) + '</a>';
      return '<span class="jp-sidenav__link jp-sidenav__link--sub is-off" aria-disabled="true"' + (l.note ? ' title="' + esc(l.note) + '"' : '')
        + '>' + esc(l.label) + (l.note ? ' <span class="jp-extra__lock">' + esc(l.note) + '</span>' : '') + '</span>';
    };
    return '<details class="jp-sidenav__more"><summary class="jp-sidenav__link jp-sidenav__more-toggle">'
      + '<span class="jp-sidenav__tri" aria-hidden="true"></span>Luyện thêm</summary>'
      + '<div class="jp-sidenav__more-list">' + rows.map(row).join('') + '</div></details>';
  }

  var OPEN_KEY = 'jpath.sidenav.more';

  function rememberFold(host) {
    var d = host.querySelector('.jp-sidenav__more');
    if (!d) return;
    try { if (root.localStorage.getItem(OPEN_KEY) === '1') d.open = true; } catch (ignore) { /* storage off */ }
    d.addEventListener('toggle', function () {
      try { root.localStorage.setItem(OPEN_KEY, d.open ? '1' : '0'); } catch (ignore) { /* storage off */ }
    });
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
    hosts.forEach(function (h) {
      if (h.getAttribute('data-jpath-extra-links') === 'nav') {
        var nav = h.closest ? h.closest('nav') : null;
        var skip = nav ? Array.prototype.map.call(nav.querySelectorAll('a.jp-sidenav__link[href]'), function (a) {
          return a.getAttribute('href');
        }).concat(Array.prototype.map.call(nav.querySelectorAll('.jp-sidenav__link'), function (a) {
          return 'label:' + String(a.textContent || '').trim();
        })) : [];
        h.innerHTML = navMarkup(view, skip);
        rememberFold(h);
      } else {
        h.innerHTML = html;
      }
      h.setAttribute(DRAWN, '');
    });
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

  return { linksMarkup: linksMarkup, navMarkup: navMarkup, mount: mount };
});
