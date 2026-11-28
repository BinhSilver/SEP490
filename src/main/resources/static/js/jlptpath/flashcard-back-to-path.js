/* [JLAS-W0108] owner 18/11: the team's flashcard study page opened from a learning path gets a "Về lộ trình" link.
 * Only back links inside /learning are kept (no open redirect). The link is remembered per set for this tab, so it
 * stays when the study page reloads itself with another mode.
 * Owner 24/11: a path's card set is personal, so it has no star rating. Opened from any path (from=jlpt or
 * from=comm, every comm goal) social rating is hidden. The learning-path entry starts in required Recall; other modes
 * remain available for extra practice.
 * The "from" flag is remembered per set like the back link. */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (root) root.FlashcardPathBack = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && root.document) {
    var run = function () { api.mount(root.document, root.location, root.sessionStorage); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', run);
    else run();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var PATHS = { jlpt: true, comm: true };

  function safeBack(v) {
    return typeof v === 'string' && /^\/learning(\/|\?|$)/.test(v) && v.indexOf('//') < 0 ? v : null;
  }

  function remembered(search, store, name, read) {
    var q = new URLSearchParams(search || '');
    var key = 'jlas.path.' + name + '.' + (q.get('setId') || '');
    var v = read(q);
    try {
      if (v) store && store.setItem(key, v);
      else v = read(new URLSearchParams(name + '=' + encodeURIComponent((store && store.getItem(key)) || '')));
    } catch (e) { /* blocked storage: only the URL counts */ }
    return v || null;
  }

  function backFor(search, store) {
    return remembered(search, store, 'fcBack', function (q) { return safeBack(q.get('back') || q.get('fcBack')); });
  }

  /** "jlpt" / "comm" when the page was opened from a learning path, else null. */
  function pathFor(search, store) {
    return remembered(search, store, 'fcFrom', function (q) {
      var v = q.get('from') || q.get('fcFrom');
      return PATHS[v] ? v : null;
    });
  }

  function hideRating(doc) {
    Array.prototype.forEach.call(doc.querySelectorAll('#btn-open-rating'), function (el) {
      el.hidden = true;
      el.style.display = 'none';
    });
  }

  function mount(doc, loc, store) {
    var search = loc && loc.search;
    if (pathFor(search, store)) hideRating(doc);
    var back = backFor(search, store);
    if (!back || doc.querySelector('[data-path-back]')) return;
    var main = doc.getElementById('jlabBaseContent');
    if (!main) return;
    var a = doc.createElement('a');
    a.className = 'btn btn--secondary btn--sm';
    a.setAttribute('data-path-back', '');
    a.href = back;
    a.textContent = 'Về lộ trình';
    a.style.margin = '0 0 12px';
    a.style.alignSelf = 'flex-start';
    main.insertBefore(a, main.firstChild);
    var done = doc.querySelector('.complete-back');
    if (done) { done.href = back; done.textContent = 'Về lộ trình'; }
  }

  return { safeBack: safeBack, backFor: backFor, pathFor: pathFor, mount: mount };
});
