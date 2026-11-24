/* [JLAS-W0108] my-paths-nav.js (owner 24/11): links every path page to "Lộ trình của tôi".
 *   - With 2+ paths (studying or paused) each side menu (.jp-sidenav) gets a "Lộ trình của tôi" link at the top.
 *   - On the two path overview pages (/learning, /learning/comm/path) with 2+ paths studying, a switcher bar sits
 *     at the top of the page to jump between them (same bar on JLPT and communication pages).
 * Silent when the API is missing or fails: the page works as before.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && root.document) {
    var run = function () { api.mount(root.document, root.location); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', run);
    else run();
  }
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var OVERVIEW = ['/learning', '/learning/comm/path'];
  var ICON = '<svg class="jp-i" aria-hidden="true"><use href="/images/icons/jpath-icons.svg#i-route"/></svg>';

  function addSideLinks(doc, pathname) {
    Array.prototype.forEach.call(doc.querySelectorAll('.jp-sidenav'), function (nav) {
      if (nav.querySelector('[data-mp-link]')) return;
      var a = doc.createElement('a');
      a.className = 'jp-sidenav__link mp-sidelink';
      a.setAttribute('data-mp-link', '');
      a.href = '/learning/my';
      if (pathname === '/learning/my') a.setAttribute('aria-current', 'page');
      a.innerHTML = ICON + ' Lộ trình của tôi';
      var title = nav.querySelector('.jp-sidenav__title');
      var host = nav.querySelector('.jp-sidenav__fold') || nav;
      var anchor = host === nav ? (title ? title.nextSibling : nav.firstChild) : host.querySelector('.jp-sidenav__link');
      host.insertBefore(a, anchor || null);
    });
  }

  function addSwitcher(doc, view, loc) {
    var core = root.MyPathsCore;
    if (OVERVIEW.indexOf(loc.pathname) < 0 || doc.querySelector('.mp-switch')) return;
    var html = core.switcherHtml(view, core.currentKey(view, loc.pathname, loc.search));
    var main = doc.getElementById('jlabBaseContent');
    if (!html || !main) return;
    var box = doc.createElement('div');
    box.innerHTML = html;
    main.insertBefore(box.firstChild, main.firstChild);
  }

  function apply(doc, view, loc) {
    var list = view && Array.isArray(view.paths) ? view.paths : [];
    if (list.length < 2) return;
    addSideLinks(doc, loc.pathname);
    addSwitcher(doc, view, loc);
  }

  function mount(doc, loc) {
    if (!doc || !loc || !root.JpathApi || !root.MyPathsCore || loc.pathname === '/learning/my') return;
    root.JpathApi.get('/api/learning/my-paths').then(function (view) {
      apply(doc, view, loc);
      // side menus drawn later by page scripts (e.g. the folded phone menu) get the link too
      if (typeof root.MutationObserver === 'function') {
        var timer = 0;
        new root.MutationObserver(function () {
          if (timer) return;
          timer = root.setTimeout(function () { timer = 0; apply(doc, view, loc); }, 120);
        }).observe(doc.body, { childList: true, subtree: true });
      }
    }, function () { /* no API yet or not signed in: leave the page as it is */ });
  }

  return { mount: mount, apply: apply };
});
