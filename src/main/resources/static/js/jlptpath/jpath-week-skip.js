/* [JLAS-W0108] jpath-week-skip.js — "Tôi biết tuần này" (owner 06/11).
 *
 * A learner whose path came from a Smart Test at Căn bản or better can skip the coming week of a tab in the first
 * weeks of the level: GET /api/learning/jlpt/week-skip?tab=VOCAB|KANJI|GRAMMAR → {offer, tab, level, items}. One card
 * on the path page lists the tabs on offer; a button issues POST /api/learning/jlpt/week-skip/{tab}/check and opens
 * the check. Right on 90% or more = the week's items count as known (asked right → Đã biết, not asked → review).
 * "Để sau" hides the card on this device for the day.
 *
 * window.JpathWeekSkip = { cardHtml(offers), mount(el) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathWeekSkip = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document.querySelector('[data-slot="week-skip"]')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var TABS = [['VOCAB', 'Từ vựng'], ['KANJI', 'Kanji'], ['GRAMMAR', 'Ngữ pháp']];
  var BASE = '/api/learning/jlpt/week-skip';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function today() { return new Date().toISOString().slice(0, 10); }
  function hiddenKey() { return 'jpath-week-skip:hidden'; }
  function isHidden() {
    try { return root.localStorage.getItem(hiddenKey()) === today(); } catch (e) { return false; }
  }
  function hide() {
    try { root.localStorage.setItem(hiddenKey(), today()); } catch (e) { /* private mode */ }
  }

  function cardHtml(offers) {
    var list = (offers || []).filter(function (o) { return o && o.offer; });
    if (!list.length) return '';
    var level = list[0].level;
    var buttons = list.map(function (o) {
      var name = (TABS.filter(function (t) { return t[0] === o.tab; })[0] || [o.tab, o.tab])[1];
      return '<button type="button" class="jp-btn" data-week-skip-tab="' + esc(o.tab) + '" title="'
        + esc('Tuần tới có ' + o.items + ' mục ' + name + '. Đúng từ 90% thì cả tuần tính là đã biết.') + '">'
        + esc(name) + ' · ' + o.items + ' mục</button>';
    }).join('');
    return '<div class="jp-warmup jp-week-skip" role="region" aria-label="Bỏ qua tuần đã biết">'
      + '<div class="jp-warmup__text"><p class="jp-warmup__title">Đã biết phần đầu ' + esc(level) + '?</p>'
      + '<p class="jp-warmup__sub">Kết quả Smart Test của bạn từ Căn bản trở lên. Làm bài kiểm tra ngắn (tối đa 20 câu) '
      + 'cho tuần tới; đúng từ 90% thì tuần đó được bỏ qua trên lộ trình.</p></div>'
      + '<div class="jp-warmup__actions">' + buttons
      + '<button type="button" class="jp-link-btn" data-week-skip-later>Để sau</button></div></div>';
  }

  function mount(el) {
    var client = root && root.JpathApi;
    if (!el || !client) return null;
    if (isHidden()) { el.hidden = true; return null; }
    var asks = TABS.map(function (t) {
      return Promise.resolve(client.get(BASE + '?tab=' + t[0])).then(function (r) { return r; }, function () { return null; });
    });
    return Promise.all(asks).then(function (offers) {
      var html = cardHtml(offers);
      if (!html) { el.hidden = true; el.innerHTML = ''; return; }
      el.hidden = false;
      el.innerHTML = html;
      el.onclick = function (ev) {
        var t = ev.target;
        if (t.closest && t.closest('[data-week-skip-later]')) {
          hide();
          el.hidden = true;
          return;
        }
        var b = t.closest && t.closest('[data-week-skip-tab]');
        if (!b) return;
        b.disabled = true;
        Promise.resolve(client.send('POST', BASE + '/' + encodeURIComponent(b.getAttribute('data-week-skip-tab')) + '/check'))
          .then(function (res) {
            var token = res && (res.token || res.checkToken);
            if (token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token) + '?return=%2Flearning');
          })
          .catch(function () {
            b.disabled = false;
            b.insertAdjacentHTML('afterend', '<span class="jp-warmup__err">Chưa mở được bài kiểm tra. Thử lại sau.</span>');
          });
      };
    }, function () { el.hidden = true; });
  }

  return { cardHtml: cardHtml, mount: mount };
});
