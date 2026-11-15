/* [JLAS-W0108] jpath-warmup.js — the short warm-up on the level below a newly opened level (owner 06/11).
 *
 * GET /api/learning/jlpt/warmup → {offer, level, lowerLevel, openedOn}. While offered (first days of the level, not N5)
 * and not dismissed on this device, the path page shows one card: about 10 minutes, 20 items of the level below
 * (Từ vựng 10, Kanji 5, Ngữ pháp 5), notebook items first, then any. Each part is a free quiz (POST /checks, kind
 * FREE_QUIZ) and is not counted in progress. "Bỏ qua" hides it.
 *
 * window.JpathWarmup = { cardHtml(w, done), mount(el) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathWarmup = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document.querySelector('[data-slot="warmup"]')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var PARTS = [['VOCAB', 'Từ vựng', 10], ['KANJI', 'Kanji', 5], ['GRAMMAR', 'Ngữ pháp', 5]];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function key(w) { return 'jpath-warmup:' + w.level + ':' + w.openedOn; }
  function read(w) {
    try { return JSON.parse(root.localStorage.getItem(key(w)) || '{}') || {}; } catch (e) { return {}; }
  }
  function write(w, state) {
    try { root.localStorage.setItem(key(w), JSON.stringify(state)); } catch (e) { /* private mode */ }
  }

  function cardHtml(w, state) {
    var s = state || {};
    var buttons = PARTS.map(function (p) {
      var done = !!s[p[0]];
      return '<button type="button" class="jp-btn' + (done ? ' is-done' : '') + '" data-warmup-tab="' + p[0] + '" data-warmup-count="'
        + p[2] + '">' + esc(p[1]) + ' · ' + p[2] + (done ? ' ✓' : '') + '</button>';
    }).join('');
    return '<div class="jp-warmup" role="region" aria-label="Khởi động">'
      + '<div class="jp-warmup__text"><p class="jp-warmup__title">Khởi động trước khi vào ' + esc(w.level) + '</p>'
      + '<p class="jp-warmup__sub">Ôn nhanh 20 mục của ' + esc(w.lowerLevel) + ', khoảng 10 phút. Mục trong Sổ tay được hỏi trước. '
      + 'Không tính vào tiến độ.</p></div>'
      + '<div class="jp-warmup__actions">' + buttons
      + '<button type="button" class="jp-link-btn" data-warmup-skip>Bỏ qua</button></div></div>';
  }

  function start(w, tab, count) {
    var client = root.JpathApi;
    var body = function (source) {
      return { kind: 'FREE_QUIZ', tab: tab, level: w.lowerLevel, source: source, count: count,
        direction: tab === 'GRAMMAR' ? null : 'MIXED', timerOn: false };
    };
    var tokenOf = function (res) {
      var token = res && (res.token || res.checkToken);
      if (!token) throw new Error('no token');
      return token;
    };
    // notebook first (words and kanji only); a notebook too small falls back to every item of the level
    var first = tab === 'GRAMMAR' ? 'ALL' : 'NOTEBOOK';
    return Promise.resolve(client.send('POST', '/api/learning/jlpt/checks', body(first))).then(tokenOf).then(null, function () {
      return Promise.resolve(client.send('POST', '/api/learning/jlpt/checks', body('ALL'))).then(tokenOf);
    });
  }

  function mount(el) {
    if (!el || !root.JpathApi) return null;
    return Promise.resolve(root.JpathApi.get('/api/learning/jlpt/warmup')).then(function (w) {
      var state = w && w.offer ? read(w) : null;
      if (!w || !w.offer || state.skipped || PARTS.every(function (p) { return state[p[0]]; })) {
        el.hidden = true;
        el.innerHTML = '';
        return;
      }
      el.hidden = false;
      el.innerHTML = cardHtml(w, state);
      el.onclick = function (ev) {
        var t = ev.target;
        if (t.closest && t.closest('[data-warmup-skip]')) {
          state.skipped = true;
          write(w, state);
          el.hidden = true;
          return;
        }
        var b = t.closest && t.closest('[data-warmup-tab]');
        if (!b) return;
        if (b.disabled) return;
        var tab = b.getAttribute('data-warmup-tab');
        b.disabled = true;
        var err = el.querySelector && el.querySelector('[data-warmup-err]');
        if (err) err.hidden = true;
        // F144: ✓ only once the quiz exists; a failure gives the button back and shows one error line
        start(w, tab, Number(b.getAttribute('data-warmup-count'))).then(function (token) {
          state[tab] = true;
          write(w, state);
          root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token) + '?return=%2Flearning');
        }).catch(function () {
          b.disabled = false;
          var e = el.querySelector && el.querySelector('[data-warmup-err]');
          if (e) { e.hidden = false; return; }
          b.insertAdjacentHTML('afterend', '<span class="jp-warmup__err" role="alert" data-warmup-err>Chưa mở được bài ôn. Thử lại sau.</span>');
        });
      };
    }, function () { el.hidden = true; });
  }

  return { cardHtml: cardHtml, mount: mount };
});
