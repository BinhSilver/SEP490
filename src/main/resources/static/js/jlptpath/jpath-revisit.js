/* [JLAS-W0108] jpath-revisit.js — "Xem lại bài đã học" (owner 17/11).
 *
 * After a day is done the learner can, from the done page and the day page, without hunting through the parts map:
 *   - Học lại như mới: the same cards again from the first one (a BROWSE round; the day stays done, CP kept),
 *   - Làm lại bài kiểm tra: a new round of that tab's check (the old score stays in the history),
 *   - Lịch sử học: every day and every score.
 * Mount point: <div data-jpath-revisit="YYYY-MM-DD"></div> (added by the page after it renders; watched here).
 * Data: GET /api/learning/jlpt/history/{day} (items, checks).
 *
 * window.JpathRevisit = { groups(detail), cardHtml(detail, day), sessionBody(group), redoBody(check), mount(el) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathRevisit = api;
  if (root && root.document && typeof module === 'undefined') {
    var scan = function () {
      var els = root.document.querySelectorAll('[data-jpath-revisit]:not([data-revisit-on])');
      for (var i = 0; i < els.length; i++) api.mount(els[i]);
    };
    var start = function () {
      scan();
      if (typeof root.MutationObserver === 'function') new root.MutationObserver(scan).observe(root.document.body, { childList: true, subtree: true });
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var TABS = [['VOCAB', 'Từ vựng', 'từ'], ['KANJI', 'Kanji', 'chữ'], ['GRAMMAR', 'Ngữ pháp', 'mẫu']];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function list(v) { return Array.isArray(v) ? v : []; }

  /** Per study tab: the items of that day (first touched first) and the latest check of the tab. */
  function groups(detail) {
    var d = detail || {};
    return TABS.map(function (t) {
      var items = list(d.items).filter(function (it) { return it && it.itemType === t[0] && it.code; });
      var checks = list(d.checks).filter(function (c) { return c && c.tab === t[0]; });
      var last = checks.slice().sort(function (a, b) { return String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')); })[0] || null;
      return { tab: t[0], label: t[1], unit: t[2], items: items, check: last };
    }).filter(function (g) { return g.items.length || g.check; });
  }

  function sessionBody(g) {
    return { tab: g.tab, mode: 'BROWSE', startItemCode: g.items[0].code, count: Math.min(200, g.items.length) };
  }
  function redoBody(c) { return { kind: 'FREE_QUIZ', tab: c.tab || null, redoOf: c.token }; }

  function scoreHtml(c) {
    if (!c || c.scorePercent == null) return '<span class="jp-revisit__score is-none">Chưa làm kiểm tra</span>';
    var ok = c.passed === true;
    return '<span class="jp-revisit__score ' + (ok ? 'is-ok' : (c.passed === false ? 'is-miss' : '')) + '">Lần trước '
      + Math.round(Number(c.scorePercent)) + '%' + (ok ? ' · Đạt' : (c.passed === false ? ' · Chưa đạt' : '')) + '</span>';
  }

  function cardHtml(detail, day) {
    var gs = groups(detail);
    if (!gs.length) return '';
    var rows = gs.map(function (g, i) {
      var relearn = g.items.length
        ? '<button type="button" class="jp-btn jp-btn--sm' + (i === 0 ? ' jp-btn--primary' : '') + '" data-revisit-learn="' + i + '">Học lại như mới</button>' : '';
      var redo = g.check && g.check.redoable && g.check.token
        ? '<button type="button" class="jp-btn jp-btn--sm" data-revisit-redo="' + i + '">Làm lại kiểm tra</button>' : '';
      return '<li class="jp-revisit__row" data-tab="' + esc(g.tab) + '">'
        + '<span class="jp-revisit__name"><strong>' + esc(g.label) + '</strong>'
        + '<span class="jp-revisit__meta">' + (g.items.length ? g.items.length + ' ' + g.unit + ' · ' : '') + scoreHtml(g.check) + '</span></span>'
        + '<span class="jp-revisit__acts">' + relearn + redo + '</span></li>';
    }).join('');
    return '<section class="jp-card jp-revisit" aria-labelledby="jpRevisitTitle">'
      + '<div class="jp-revisit__head"><h2 class="jp-revisit__title" id="jpRevisitTitle">Xem lại bài đã học</h2>'
      + '<a class="jp-revisit__history" href="/learning/jlpt/history' + (day ? '?day=' + esc(day) : '') + '">Lịch sử học ›</a></div>'
      + '<p class="jp-revisit__note">Ngày này vẫn tính là đã xong. Học lại hay làm lại chỉ là luyện thêm, điểm cũ vẫn được lưu.</p>'
      + '<ul class="jp-revisit__rows">' + rows + '</ul>'
      + '<p class="jp-revisit__msg" role="status" hidden></p></section>';
  }

  function mount(el) {
    var client = root && root.JpathApi;
    if (!el || !client) return null;
    el.setAttribute('data-revisit-on', '1');
    var day = el.getAttribute('data-jpath-revisit');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day || '')) return null;
    return Promise.resolve(client.get(BASE + '/history/' + day)).then(function (detail) {
      var gs = groups(detail);
      el.innerHTML = cardHtml(detail, day);
      el.onclick = function (ev) {
        var b = ev.target && ev.target.closest ? ev.target.closest('[data-revisit-learn],[data-revisit-redo]') : null;
        if (!b) return;
        var learn = b.hasAttribute('data-revisit-learn');
        var g = gs[Number(b.getAttribute(learn ? 'data-revisit-learn' : 'data-revisit-redo'))];
        if (!g) return;
        b.disabled = true;
        var req = learn ? client.send('POST', BASE + '/sessions', sessionBody(g)) : client.send('POST', BASE + '/checks', redoBody(g.check));
        Promise.resolve(req).then(function (v) {
          if (learn && v && v.sessionId != null) root.location.assign('/learning/jlpt/study?session=' + encodeURIComponent(v.sessionId));
          else if (!learn && v && v.token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(v.token));
          else b.disabled = false;
        }, function (err) {
          b.disabled = false;
          var msg = el.querySelector('.jp-revisit__msg');
          if (msg) { msg.hidden = false; msg.textContent = (err && err.message) || 'Chưa mở được. Thử lại sau.'; }
        });
      };
    }, function () { el.hidden = true; });
  }

  return { groups: groups, cardHtml: cardHtml, sessionBody: sessionBody, redoBody: redoBody, mount: mount };
});
