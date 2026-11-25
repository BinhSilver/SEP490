/* [JLAS-W0108] jpath-mock-card.js — the Thi thử card (Plan 3 Task 23).
 *
 * window.JpathMockCard = { cardHtml(status, readiness), adviceHref(action), retakeText(iso), mount(el, deps) }
 *   - "Thi thử JLPT {L}" links to destination.url exactly as the server gives it (never built here) while
 *     destination.available; otherwise "Thi thử chưa sẵn sàng" with no link. Readiness (#41) adds "Bạn đã sẵn sàng thi
 *     thử" (a suggestion, never a lock).
 *   - Back on the page (visibilitychange → visible, focus) → POST /mock/sync once, then redraw.
 *   - A failed attempt shows the three parts, the advice links (#43) and "Thi lại sau HH:MM"; a pass sends the learner to
 *     /learning/jlpt/levels/{L}/summary when that pass arrives with a sync (a later visit shows the summary link).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathMockCard = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt/mock';
  var ADVICE = { NOTEBOOK_REVIEW: '/learning/jlpt/notebook', FREE_QUIZ_VOCAB_KANJI: '/learning?tab=vocab',
    FREE_QUIZ_GRAMMAR: '/learning?tab=grammar', SKILL_READING: '/learning?tab=skills', SKILL_LISTENING: '/learning?tab=skills' };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(x) { return Array.isArray(x) ? x : []; }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  function safeUrl(u) {
    var s = String(u || '');
    return /^\/(?!\/)/.test(s) || /^https:\/\//i.test(s) ? s : null;
  }

  function adviceHref(action) { return ADVICE[action] || '/learning?view=jlpt'; }

  /** "HH:MM" (and the date when it is not today) of a server-local ISO time. */
  function retakeText(iso, today) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(iso || ''));
    if (!m) return '';
    var hm = m[4] + ':' + m[5];
    return today && today === m[1] + '-' + m[2] + '-' + m[3] ? hm : hm + ' ' + m[3] + '/' + m[2];
  }

  function cardHtml(st, readiness, today) {
    if (!st) return '';
    var dest = st.destination || {};
    var href = dest.available ? safeUrl(dest.url) : null;
    var head = '<h3 class="jp-skills__sub">Thi thử JLPT ' + esc(st.level) + '</h3>';
    var body = '';
    if (readiness && readiness.ready) body += '<p class="jp-skills__ready">Bạn đã sẵn sàng thi thử.</p>';
    if (st.lastOutcome === 'FAILED' && !st.levelPassed) {
      body += '<p>Lần gần nhất: chưa đạt' + (st.lastScore != null ? ' (' + esc(st.lastScore) + '%)' : '') + '.</p>'
        + '<ul class="jp-mock-sections">' + list(st.sections).map(function (s) {
          return '<li' + (s.weak ? ' class="is-weak"' : '') + '>' + esc(s.title) + ': ' + esc(s.scorePct) + '%'
            + (s.passed ? ' ' + icon('check') : '') + (s.weak ? ' · cần luyện thêm' : '') + '</li>';
        }).join('') + '</ul>'
        + (list(st.advice).length ? '<p>Gợi ý:</p><ul class="jp-mock-advice">' + list(st.advice).map(function (a) {
          return '<li><a href="' + esc(adviceHref(a.action)) + '">' + esc(a.labelVi) + '</a></li>';
        }).join('') + '</ul>' : '')
        + (st.retakeAt ? '<p class="jp-muted">Thi lại sau ' + esc(retakeText(st.retakeAt, today)) + '.</p>' : '');
    }
    if (st.levelPassed) {
      body += '<p>Bạn đã đậu Thi thử ' + esc(st.level) + '. <a href="/learning/jlpt/levels/' + encodeURIComponent(st.level)
        + '/summary">Xem tổng kết cấp</a></p>';
    }
    if (st.notice) body += '<p class="jp-muted">' + esc(st.notice) + '</p>';
    var action = href
      ? '<a class="jp-btn jp-btn--primary" href="' + esc(href) + '">Thi thử JLPT ' + esc(st.level) + '</a>'
      : '<span class="jp-muted">Thi thử chưa sẵn sàng.</span>';
    if (st.resultState === 'PENDING') {
      action += ' <button type="button" class="jp-btn" data-mock-sync>Cập nhật kết quả</button>';
    }
    return '<section class="jp-skill-block jp-mock-card">' + head + body + action + '</section>';
  }

  /** Fills {@code el}; deps = {api, location, document, window, readiness, today}. */
  function mount(el, deps) {
    var d = deps || {};
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var doc = d.document || root.document;
    var win = d.window || root;
    if (!el || !apiClient) return Promise.resolve(null);
    var syncing = false;
    var wasPassed = null; // the level state when the card was first drawn

    function show(st) {
      el.innerHTML = cardHtml(st, d.readiness, d.today);
      return st;
    }

    // only a pass that arrives with this sync (back from the mock test) moves on to the level summary
    function sync() {
      if (syncing) return Promise.resolve(null);
      syncing = true;
      return apiClient.send('POST', BASE + '/sync').then(function (st) {
        syncing = false;
        if (st && st.levelPassed && wasPassed === false && st.level) {
          loc.assign('/learning/jlpt/levels/' + encodeURIComponent(st.level) + '/summary');
          return st;
        }
        return show(st);
      }, function () { syncing = false; return null; });
    }

    if (!el.getAttribute('data-mock-bound')) {
      el.setAttribute('data-mock-bound', '1');
      el.addEventListener('click', function (ev) { if (ev.target.closest('[data-mock-sync]')) sync(); });
      if (doc && typeof doc.addEventListener === 'function') {
        doc.addEventListener('visibilitychange', function () { if (doc.visibilityState === 'visible') sync(); });
      }
      if (win && typeof win.addEventListener === 'function') win.addEventListener('focus', sync);
    }
    return apiClient.get(BASE + '/status').then(function (st) {
      wasPassed = !!(st && st.levelPassed);
      return show(st);
    }, function () { el.innerHTML = ''; return null; });
  }

  return { cardHtml: cardHtml, adviceHref: adviceHref, retakeText: retakeText, mount: mount };
});
