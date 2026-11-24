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
    if (OVERVIEW.indexOf(loc.pathname) < 0) return;
    var html = core.switcherHtml(view, core.currentKey(view, loc.pathname, loc.search));
    var old = doc.querySelector('.mp-switch');
    if (old) { if (!html && old.parentNode) old.parentNode.removeChild(old); return; } // a cached bar goes once a path pauses
    var main = doc.getElementById('jlabBaseContent');
    if (!html || !main) return;
    var box = doc.createElement('div');
    box.innerHTML = html;
    var bar = box.firstChild;
    main.insertBefore(bar, main.firstChild);
    slider(bar);
  }

  // owner 25/11: the white pill slides between the paths: on click it slides to the picked tab, then the next page
  // starts it where it was and slides it home, so switching reads as one bar moving instead of a page reload
  var PILL_KEY = 'mp.switch.from';
  function store(k, v) { try { if (v == null) root.sessionStorage.removeItem(k); else root.sessionStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  function read(k) { try { return root.sessionStorage.getItem(k); } catch (e) { return null; } }
  function place(pill, tab) {
    pill.style.width = tab.offsetWidth + 'px';
    pill.style.transform = 'translateX(' + tab.offsetLeft + 'px)';
  }
  function slider(bar) {
    var tabs = bar.querySelector('.mp-switch__tabs');
    var cur = bar.querySelector('.mp-switch__tab.is-current');
    if (!tabs || !cur || !root.requestAnimationFrame) return;
    var pill = root.document.createElement('span');
    pill.className = 'mp-switch__pill';
    pill.setAttribute('aria-hidden', 'true');
    tabs.insertBefore(pill, tabs.firstChild);
    tabs.classList.add('has-pill');
    var from = read(PILL_KEY);
    store(PILL_KEY, null);
    var start = from ? JSON.parse(from) : null;
    if (start) { pill.style.width = start.w + 'px'; pill.style.transform = 'translateX(' + start.x + 'px)'; }
    else place(pill, cur);
    root.requestAnimationFrame(function () { root.requestAnimationFrame(function () {
      pill.classList.add('is-moving'); place(pill, cur);
    }); });
    tabs.addEventListener('click', function (e) {
      var a = e.target.closest && e.target.closest('.mp-switch__tab');
      if (!a || a === cur || e.metaKey || e.ctrlKey || e.shiftKey || e.button) return;
      e.preventDefault();
      store(PILL_KEY, JSON.stringify({ x: cur.offsetLeft, w: cur.offsetWidth }));
      cur.classList.remove('is-current'); a.classList.add('is-current');
      place(pill, a);
      root.setTimeout(function () { root.location.href = a.href; }, 220);
    });
  }

  function apply(doc, view, loc) {
    var list = view && Array.isArray(view.paths) ? view.paths : [];
    if (list.length < 2) { addSwitcher(doc, view, loc); return; }
    addSideLinks(doc, loc.pathname);
    addSwitcher(doc, view, loc);
  }

  function mount(doc, loc) {
    if (!doc || !loc || !root.JpathApi || !root.MyPathsCore || loc.pathname === '/learning/my') return;
    var cached = read('mp.view');
    if (cached) { try { apply(doc, JSON.parse(cached), loc); } catch (e) { /* stale cache */ } }
    root.JpathApi.get('/api/learning/my-paths').then(function (view) {
      try { store('mp.view', JSON.stringify(view)); } catch (e) { /* too big */ }
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
