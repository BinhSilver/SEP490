/* [JLAS-W0108] owner 18/11: the team's flashcard study page opened from a learning path gets a "Về lộ trình" link.
 * Only back links inside /learning are kept (no open redirect). The link is remembered per set for this tab, so it
 * stays when the study page reloads itself with another mode. */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && root.document) {
    var run = function () { api.mount(root.document, root.location, root.sessionStorage); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', run);
    else run();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function safeBack(v) {
    return typeof v === 'string' && /^\/learning(\/|\?|$)/.test(v) && v.indexOf('//') < 0 ? v : null;
  }

  function backFor(search, store) {
    var q = new URLSearchParams(search || '');
    var setId = q.get('setId') || '';
    var key = 'jlas.path.fcBack.' + setId;
    var back = safeBack(q.get('back'));
    try {
      if (back) store && store.setItem(key, back);
      else back = safeBack(store && store.getItem(key));
    } catch (e) { /* blocked storage: only the URL counts */ }
    return back;
  }

  function mount(doc, loc, store) {
    var back = backFor(loc && loc.search, store);
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

  return { safeBack: safeBack, backFor: backFor, mount: mount };
});
