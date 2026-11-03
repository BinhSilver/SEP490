/* [JLAS-W0108] jpath-confusables.js — "Phân biệt chữ dễ nhầm" page of the JLPT path (plan Task 68).
 *
 * window.JpathConfusables = { pairMarkup(pair), mount() }
 *   - Tabs Từ vựng / Kanji: GET /api/learning/jlpt/confusables?tab= lists the pairs the learner has learned on both
 *     sides, most mistakes first. "Luyện 10 câu" → POST /checks {kind:'CONFUSABLE', tab, count:10} →
 *     /learning/jlpt/check/{token}; without a pair the button is off with "Bạn cần học cả hai mục của một cặp trước".
 *     The practice does not count towards progress.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathConfusables = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var NO_PAIR = 'Bạn cần học cả hai mục của một cặp trước';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function side(s, kanji) {
    if (!s) return '';
    return '<div class="jp-pair__side"><strong lang="ja"' + (kanji ? ' class="jp-kanji"' : '') + '>' + esc(s.expression)
      + '</strong>' + (s.reading ? '<span lang="ja">' + esc(s.reading) + '</span>' : '')
      + '<span>' + esc(s.meaning) + '</span></div>';
  }

  /** Two items side by side, with the number of wrong answers when there are some. */
  function pairMarkup(p) {
    if (!p) return '';
    var kanji = p.itemType === 'KANJI';
    return '<li class="jp-pair">' + side(p.a, kanji) + '<span class="jp-pair__vs" aria-hidden="true">≠</span>'
      + side(p.b, kanji) + (Number(p.wrongCount) > 0 ? '<span class="jp-pair__wrong">' + esc(p.wrongCount)
        + ' lần nhầm</span>' : '') + '</li>';
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-confusables]');
    if (mounted || !main || !root.JpathApi) return;
    mounted = true;
    var api = root.JpathApi;
    var status = main.querySelector('[data-role="status"]');
    var list = main.querySelector('[data-role="pairs"]');
    var start = main.querySelector('[data-role="start"]');
    var tab = 'VOCAB';

    function load() {
      main.querySelectorAll('[data-tab]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-tab') === tab));
      });
      status.textContent = 'Đang tải…';
      start.disabled = true;
      api.get(BASE + '/confusables?tab=' + tab).then(function (pairs) {
        var items = Array.isArray(pairs) ? pairs : [];
        list.innerHTML = items.map(pairMarkup).join('');
        start.disabled = !items.length;
        status.textContent = items.length ? '' : NO_PAIR;
      }, function (err) { status.textContent = (err && err.message) || 'Không tải được các cặp dễ nhầm.'; });
    }

    main.addEventListener('click', function (e) {
      var t = e.target.closest('[data-tab]');
      if (t) { tab = t.getAttribute('data-tab'); load(); return; }
      if (e.target.closest('[data-role="start"]') && !start.disabled) {
        start.disabled = true;
        api.send('POST', BASE + '/checks', { kind: 'CONFUSABLE', tab: tab, count: 10 }).then(function (check) {
          if (check && check.token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(check.token));
          else start.disabled = false;
        }, function (err) {
          start.disabled = false;
          status.textContent = (err && err.message) || 'Chưa mở được bài luyện.';
        });
      }
    });
    load();
  }

  return { pairMarkup: pairMarkup, NO_PAIR: NO_PAIR, mount: mount };
});
