/* [JLAS-W0108] jpath-week-skip.js — "Tôi biết tuần này" (owner 06/11).
 *
 * Any learner (owner 11/11, F64) can skip the coming week of a tab; was: Smart Test Căn bản+ in the first
 * weeks of the level: GET /api/learning/jlpt/week-skip?tab=VOCAB|KANJI|GRAMMAR → {offer, tab, level, items}. One card
 * on the path page lists the tabs on offer; a button issues POST /api/learning/jlpt/week-skip/{tab}/check and opens
 * the check. Right on 90% or more = the week's items count as known (asked right → Đã biết, not asked → review).
 * F227 (owner 22/11): the card lives in Cài đặt lộ trình ([data-slot="week-skip"]); the path page only counts the
 * offers for its nudge (jpath-tools-nudge.js), whose "Để sau" hides that nudge on this device for the day.
 *
 * window.JpathWeekSkip = { cardHtml(offers), mount(el), count(), hideToday() }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathWeekSkip = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () {
      var card = root.document.querySelector('[data-slot="week-skip"]');
      if (card) { api.mount(card); return; }
      var nudge = root.document.querySelector('[data-slot="tools-nudge"]');
      if (nudge && root.JpathToolsNudge) {
        Promise.resolve(api.count()).then(function (n) { root.JpathToolsNudge.paint(nudge, { skip: n, doc: root.document }); });
      }
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  // owner 18/11: the kana skip test lives in the kana stage at the start of the Từ vựng map (jpath-kana-stage.js)
  var TABS = [['VOCAB', 'Từ vựng'], ['KANJI', 'Kanji'], ['GRAMMAR', 'Ngữ pháp']];
  var BASE = '/api/learning/jlpt/week-skip';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function today() { return new Date().toISOString().slice(0, 10); }
  /** Bug 58: per-user storage key, so a shared computer never hands one learner's state to another. */
  function uidPart() {
    try {
      var auth = root && root.JlasAuth;
      var u = auth && typeof auth.getUser === 'function' ? auth.getUser() : null;
      return u && (u.id || u.userId) ? String(u.id || u.userId) : 'me';
    } catch (e) { return 'me'; }
  }
  function hiddenKey() { return 'jpath-week-skip:hidden:' + uidPart(); }
  function isHidden() {
    try { return root.localStorage.getItem(hiddenKey()) === today(); } catch (e) { return false; }
  }
  function hide() {
    try { root.localStorage.setItem(hiddenKey(), today()); } catch (e) { /* private mode */ }
  }

  function cardHtml(offers) {
    var all = (offers || []).filter(function (o) { return o && o.offer; });
    var list = all.filter(function (o) { return o.tab !== 'KANA'; });
    if (!list.length) return '';
    var level = list[0].level;
    var buttons = list.map(function (o) {
      var name = (TABS.filter(function (t) { return t[0] === o.tab; })[0] || [o.tab, o.tab])[1];
      return '<button type="button" class="jp-btn" data-week-skip-tab="' + esc(o.tab) + '" title="'
        + esc('Tuần tới có ' + o.items + ' mục ' + name + '. Đúng từ 90% thì cả tuần tính là đã biết.') + '">'
        + esc(name) + ' · ' + o.items + ' mục</button>';
    }).join('');
    return '<div class="jp-warmup jp-week-skip" role="region" aria-label="Bỏ qua tuần đã biết">'
      + '<div class="jp-warmup__text"><p class="jp-warmup__title">Đã biết tuần tới của ' + esc(level) + '?</p>'
      + '<p class="jp-warmup__sub">Làm bài kiểm tra ngắn (tối đa 20 câu) '
      + 'cho tuần tới; đúng từ 90% thì tuần đó được bỏ qua trên lộ trình.</p></div>'
      + '<div class="jp-warmup__actions">' + buttons
      + '<button type="button" class="jp-link-btn" data-week-skip-later>Để sau</button></div>'
      + '<p class="jp-warmup__err" role="status" aria-live="polite" data-week-skip-err hidden></p></div>';
  }

  function offers(client) {
    return Promise.all(TABS.map(function (t) {
      return Promise.resolve(client.get(BASE + '?tab=' + t[0])).then(function (r) { return r; }, function () { return null; });
    }));
  }

  /** How many tabs offer the coming week (0 when hidden today or nothing is on offer) — the path page nudge. */
  function count() {
    var client = root && root.JpathApi;
    if (!client || isHidden()) return Promise.resolve(0);
    return offers(client).then(function (list) {
      return list.filter(function (o) { return o && o.offer && o.tab !== 'KANA'; }).length;
    }, function () { return 0; });
  }

  /** The Cài đặt lộ trình card: always shown there, with a line when no week is on offer. */
  function mount(el) {
    var client = root && root.JpathApi;
    if (!el || !client) return null;
    return offers(client).then(function (list) {
      var html = cardHtml(list).replace('<button type="button" class="jp-link-btn" data-week-skip-later>Để sau</button>', '');
      el.hidden = false;
      el.innerHTML = html || '<p class="jp-settings-muted">Chưa có tuần nào để kiểm tra bỏ qua. Gợi ý hiện ở đây khi tuần tới của một phần có mục mới.</p>';
      el.onclick = function (ev) {
        var t = ev.target;
        var b = t.closest && t.closest('[data-week-skip-tab]');
        if (!b || b.disabled) return;
        var label = b.textContent;
        b.disabled = true;
        b.setAttribute('aria-busy', 'true');
        b.textContent = 'Đang mở…';
        var errBox = el.querySelector('[data-week-skip-err]');
        if (errBox) { errBox.textContent = ''; errBox.hidden = true; }
        var url = BASE + '/' + encodeURIComponent(b.getAttribute('data-week-skip-tab')) + '/check';
        Promise.resolve(client.send('POST', url))
          .then(function (res) {
            var token = res && (res.token || res.checkToken);
            if (token) {
              root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token) + '?return=' + encodeURIComponent(root.location.pathname.indexOf('/learning/jlpt/settings') === 0 ? '/learning/jlpt/settings' : '/learning'));
              return;
            }
            throw new Error('Chưa mở được bài kiểm tra. Thử lại sau.');
          })
          .catch(function (err) {
            b.disabled = false;
            b.removeAttribute('aria-busy');
            b.textContent = label;
            var msg = (err && (err.code || err.status) && err.message) || (err && err.message) || 'Chưa mở được bài kiểm tra. Thử lại sau.';
            if (errBox) { errBox.textContent = msg; errBox.hidden = false; }
          });
      };
    }, function () { el.hidden = false; el.innerHTML = '<p class="jp-settings-muted">Chưa tải được gợi ý. Thử lại sau.</p>'; });
  }

  return { cardHtml: cardHtml, mount: mount, count: count, hideToday: hide };
});
