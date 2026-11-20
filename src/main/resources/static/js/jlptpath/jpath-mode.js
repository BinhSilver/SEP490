/* [JLAS-W0108] jpath-mode.js — owner 17/11: "Học hôm nay" and "Tự chọn bài" are two modes, not one long page.
 *
 * Both path pages (JLPT and Giao tiếp) put a two-tab bar at the top of .jp-path__main. Regions carry
 * data-mode-part="today" or "free"; the main gets data-mode and CSS hides the other part. The choice is kept per
 * viewer (localStorage) and can be forced with ?mode=free|today; a link to an anchor inside the hidden part
 * switches to that part first.
 *
 * window.JpathMode = { MODES, pick(saved, query, hashInFree), barHtml(mode), mount(doc) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathMode = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  /** Bug 58: per-user storage key, so a shared computer never hands one learner's state to another. */
  function uidPart() {
    try {
      var auth = root && root.JlasAuth;
      var u = auth && typeof auth.getUser === 'function' ? auth.getUser() : null;
      return u && (u.id || u.userId) ? String(u.id || u.userId) : 'me';
    } catch (e) { return 'me'; }
  }
  var KEY = 'jpath-mode';
  var MODES = [
    { code: 'today', label: 'Học hôm nay', sub: 'Bài được giao theo ngày' },
    { code: 'free', label: 'Tự chọn bài', sub: 'Chọn phần bất kỳ để học hoặc ôn' }
  ];

  function valid(m) { return m === 'today' || m === 'free'; }

  /** Order: link to an anchor in the free part, then ?mode=, then the saved choice, then "today". */
  function pick(saved, query, hashInFree) {
    if (hashInFree) return 'free';
    if (valid(query)) return query;
    if (valid(saved)) return saved;
    return 'today';
  }

  function barHtml(mode) {
    return '<div class="jp-modebar" role="tablist" aria-label="Chế độ học">' + MODES.map(function (m) {
      var on = m.code === mode;
      return '<button type="button" role="tab" class="jp-modebar__tab' + (on ? ' is-on' : '') + '" data-mode="' + m.code
        + '" aria-selected="' + on + '"><span class="jp-modebar__label">' + m.label + '</span>'
        + '<span class="jp-modebar__sub">' + m.sub + '</span></button>';
    }).join('') + '</div>';
  }

  function read() { try { return root.localStorage.getItem(KEY + ':' + uidPart()); } catch (e) { return null; } }
  function save(m) { try { root.localStorage.setItem(KEY + ':' + uidPart(), m); } catch (e) { /* private window */ } }

  function inFree(doc, hash) {
    if (!hash || hash.length < 2 || hash.charAt(0) !== '#') return false;
    var el = null;
    try { el = doc.getElementById(decodeURIComponent(hash.slice(1))); } catch (e) { el = null; }
    return !!(el && el.closest && el.closest('[data-mode-part="free"]'));
  }

  function mount(doc) {
    var main = doc && doc.querySelector ? doc.querySelector('.jp-path__main') : null;
    if (!main || main.querySelector('.jp-modebar')) return null;
    var query = null;
    try { query = new root.URLSearchParams(root.location.search).get('mode'); } catch (e) { query = null; }
    var mode = pick(read(), query, inFree(doc, root.location && root.location.hash));
    // JLPT: under the page title (hero), communication: at the top of the main column
    var hero = main.querySelector(':scope > .jp-path__hero');
    if (hero) hero.insertAdjacentHTML('afterend', barHtml(mode));
    else main.insertAdjacentHTML('afterbegin', barHtml(mode));
    var set = function (m, keep) {
      mode = m;
      main.setAttribute('data-mode', m);
      Array.prototype.forEach.call(main.querySelectorAll('.jp-modebar__tab'), function (b) {
        var on = b.getAttribute('data-mode') === m;
        b.classList.toggle('is-on', on);
        b.setAttribute('aria-selected', String(on));
      });
      if (keep) save(m);
    };
    set(mode, false);
    main.querySelector('.jp-modebar').addEventListener('click', function (ev) {
      var b = ev.target.closest ? ev.target.closest('[data-mode]') : null;
      if (b) set(b.getAttribute('data-mode'), true);
    });
    // a link to something in the free part (e.g. "Học bù" → a part) opens that part
    doc.addEventListener('click', function (ev) {
      var a = ev.target && ev.target.closest ? ev.target.closest('a[href^="#"]') : null;
      if (a && mode !== 'free' && inFree(doc, a.getAttribute('href'))) set('free', false);
    }, true);
    if (root.addEventListener) root.addEventListener('hashchange', function () {
      if (inFree(doc, root.location.hash)) set('free', false);
    });
    var ctl = { set: set, get: function () { return mode; } };
    if (root && root.JpathMode) root.JpathMode.current = ctl;
    return ctl;
  }

  return { MODES: MODES, pick: pick, barHtml: barHtml, mount: mount };
});
