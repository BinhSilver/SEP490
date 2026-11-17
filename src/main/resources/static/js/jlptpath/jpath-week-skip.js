/* [JLAS-W0108] jpath-week-skip.js — "Tôi biết tuần này" (owner 06/11).
 *
 * Any learner (owner 11/11, F64) can skip the coming week of a tab; was: Smart Test Căn bản+ in the first
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
    var start = function () { api.mount(root.document.querySelector('[data-slot="week-skip"]')); api.tools(root.document); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var TABS = [['VOCAB', 'Từ vựng'], ['KANJI', 'Kanji'], ['GRAMMAR', 'Ngữ pháp'], ['KANA', 'Chữ cái']];
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

  /** F65c: check the next kana rows or all remaining rows (matching boards, ≥ 90% = known). */
  function kanaHtml(o) {
    return '<div class="jp-warmup jp-week-skip" role="region" aria-label="Kiểm tra nhanh chữ cái">'
      + '<div class="jp-warmup__text"><p class="jp-warmup__title">Đã thuộc chữ cái?</p>'
      + '<p class="jp-warmup__sub">Nối chữ với cách đọc cho vài hàng tiếp theo hoặc cả bảng chữ còn lại; '
      + 'đúng từ 90% thì phần đã kiểm tra tính là đã thuộc.</p></div>'
      + '<div class="jp-warmup__actions"><button type="button" class="jp-btn" data-week-skip-tab="KANA">Kiểm tra nhanh · '
      + esc(o.items) + ' chữ</button>'
      + '<button type="button" class="jp-link-btn" data-week-skip-tab="KANA" data-week-skip-rows="0">Tôi biết cả bảng chữ</button>'
      + '<button type="button" class="jp-link-btn" data-week-skip-later>Để sau</button></div></div>';
  }

  function cardHtml(offers) {
    var all = (offers || []).filter(function (o) { return o && o.offer; });
    var kana = all.filter(function (o) { return o.tab === 'KANA'; })[0];
    var list = all.filter(function (o) { return o.tab !== 'KANA'; });
    if (!list.length) return kana ? kanaHtml(kana) : '';
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
      + '<button type="button" class="jp-link-btn" data-week-skip-later>Để sau</button></div></div>'
      + (kana ? kanaHtml(kana) : '');
  }

  /** Owner 17/11: the path's tool row — one panel open at a time, the open one remembered on this device. */
  function tools(doc) {
    var box = doc && doc.querySelector ? doc.querySelector('[data-jp-tools]') : null;
    if (!box) return;
    function set(name) {
      var chips = box.querySelectorAll('[data-tool]');
      for (var i = 0; i < chips.length; i++) {
        var on = chips[i].getAttribute('data-tool') === name;
        chips[i].setAttribute('aria-expanded', on ? 'true' : 'false');
        var panel = box.querySelector('[data-tool-panel="' + chips[i].getAttribute('data-tool') + '"]');
        if (panel) panel.hidden = !on;
      }
      try { root.localStorage.setItem('jpath-tools:open', name || ''); } catch (e) { /* private mode */ }
    }
    box.addEventListener('click', function (ev) {
      var chip = ev.target.closest && ev.target.closest('[data-tool]');
      if (!chip) return;
      set(chip.getAttribute('aria-expanded') === 'true' ? '' : chip.getAttribute('data-tool'));
    });
    var saved = '';
    try { saved = root.localStorage.getItem('jpath-tools:open') || ''; } catch (e) { saved = ''; }
    if (saved === 'pace') set('pace');
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
      var n = (html.match(/class="jp-warmup jp-week-skip"/g) || []).length;
      var chip = root.document && root.document.querySelector ? root.document.querySelector('[data-tool="skip"]') : null;
      el.innerHTML = html;
      if (chip) {
        // owner 17/11: inside the path's tool row the offers open from their own button (jpath tools below)
        chip.hidden = false;
        var count = chip.querySelector('[data-tool-count]');
        if (count) count.textContent = n + ' gợi ý';
      } else {
        el.hidden = false;
      }
      el.onclick = function (ev) {
        var t = ev.target;
        if (t.closest && t.closest('[data-week-skip-later]')) {
          hide();
          el.hidden = true;
          var c = root.document && root.document.querySelector ? root.document.querySelector('[data-tool="skip"]') : null;
          if (c) { c.hidden = true; c.setAttribute('aria-expanded', 'false'); }
          return;
        }
        var b = t.closest && t.closest('[data-week-skip-tab]');
        if (!b) return;
        b.disabled = true;
        var rows = b.getAttribute('data-week-skip-rows');
        var url = BASE + '/' + encodeURIComponent(b.getAttribute('data-week-skip-tab')) + '/check'
          + (rows === '0' ? '?rows=0' : '');
        Promise.resolve(client.send('POST', url))
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

  return { cardHtml: cardHtml, mount: mount, tools: tools };
});
