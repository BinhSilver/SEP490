/* [JLAS-W0108] jpath-confusables.js — "Phân biệt chữ dễ nhầm" page of the JLPT path (plan Task 68).
 *
 * window.JpathConfusables = { pairMarkup(pair), mount() }
 *   - Tabs Từ vựng / Kanji: GET /api/learning/jlpt/confusables?tab= (&preview=true: every pair, "Xem trước tất cả") lists the pairs the learner has learned on both
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
  // an empty tab says why: a pair shows once both of its sides are learned
  var EMPTY = {
    VOCAB: 'Chưa có cặp nào: một cặp từ dễ nhầm hiện ở đây khi bạn đã học cả hai từ.',
    KANJI: 'Chưa có cặp nào: một cặp Kanji dễ nhầm hiện ở đây khi bạn đã học cả hai chữ.',
    GRAMMAR: 'Chưa có cặp nào: một cặp mẫu ngữ pháp dễ nhầm (kèm giải thích, ví dụ và câu luyện) hiện ở đây khi bạn đã học cả hai mẫu.'
  };

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

  /** Owner 07/11: how the two differ, contrasting examples and one choose-the-pattern question (grammar pairs). */
  function details(p, i) {
    var out = p.diffVi ? '<p class="jp-pair__diff">' + esc(p.diffVi) + '</p>' : '';
    var ex = Array.isArray(p.examples) ? p.examples : [];
    if (ex.length) {
      out += '<ul class="jp-pair__examples">' + ex.map(function (x) {
        return '<li><span lang="ja">' + esc(x.ja) + '</span><span>' + esc(x.vi) + '</span></li>';
      }).join('') + '</ul>';
    }
    var q = p.question;
    if (q && q.ja && Array.isArray(q.options)) {
      out += '<div class="jp-pair__quiz" data-quiz="' + i + '"><p lang="ja">' + esc(q.ja) + '</p><div class="jp-pair__opts">'
        + q.options.map(function (o, k) {
          return '<button type="button" class="jp-btn" data-quiz-pick="' + k + '" lang="ja">' + esc(o) + '</button>';
        }).join('') + '</div><p class="jp-pair__answer" role="status" hidden></p></div>';
    }
    return out;
  }

  /** Two items side by side, with the number of wrong answers when there are some. */
  function pairMarkup(p, i) {
    if (!p) return '';
    var kanji = p.itemType === 'KANJI';
    return '<li class="jp-pair">' + side(p.a, kanji) + '<span class="jp-pair__vs" aria-hidden="true">≠</span>'
      + side(p.b, kanji) + (Number(p.wrongCount) > 0 ? '<span class="jp-pair__wrong">' + esc(p.wrongCount)
        + ' lần nhầm</span>' : '') + details(p, i) + '</li>';
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
    var search = main.querySelector('[data-role="search"]');
    var pager = main.querySelector('[data-role="pager"]');
    var tab = 'VOCAB';
    var mode = 'mine';
    var pairs = [];
    var shown = [];
    var page = 0;

    // I12: same fold as the server's normalized_* columns (NFKC, katakana to hiragana), so a full-width or katakana query still matches
    function foldSearch(v) {
      var s = String(v == null ? '' : v);
      if (typeof s.normalize === 'function') s = s.normalize('NFKC');
      return s.trim().toLocaleLowerCase().replace(/[ァ-ヶ]/g, function (c) { return String.fromCharCode(c.charCodeAt(0) - 0x60); });
    }

    function draw() {
      var term = foldSearch(search.value);
      var filtered = pairs.filter(function (p) {
        return !term || [p.a, p.b].some(function (s) {
          return s && [s.expression, s.reading, s.meaning].some(function (v) {
            return foldSearch(v).indexOf(term) >= 0;
          });
        });
      });
      var pages = Math.max(1, Math.ceil(filtered.length / 20));
      page = Math.min(page, pages - 1);
      shown = filtered.slice(page * 20, page * 20 + 20);
      list.innerHTML = shown.map(pairMarkup).join('');
      pager.hidden = pages <= 1;
      pager.querySelector('[data-page="prev"]').disabled = page === 0;
      pager.querySelector('[data-page="next"]').disabled = page + 1 >= pages;
      pager.querySelector('[data-role="page"]').textContent = 'Trang ' + (page + 1) + '/' + pages;
      status.textContent = filtered.length ? (mode === 'preview' ? 'Xem trước · ' : '') + filtered.length + ' cặp'
        : (pairs.length ? 'Không tìm thấy cặp phù hợp.' : (EMPTY[tab] || NO_PAIR) + (mode === 'mine' ? ' Bấm "Xem trước tất cả" để xem các cặp sẽ gặp.' : ''));
    }

    function load() {
      main.querySelectorAll('[data-tab]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-tab') === tab));
      });
      main.querySelectorAll('[data-mode]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-mode') === mode));
      });
      status.textContent = 'Đang tải…';
      start.disabled = true;
      api.get(BASE + '/confusables?tab=' + tab + (mode === 'preview' ? '&preview=true' : '')).then(function (result) {
        pairs = Array.isArray(result) ? result : [];
        page = 0;
        // grammar pairs are practised inline (each pair has its own question); the 10-question check is for words
        start.hidden = tab === 'GRAMMAR' || mode === 'preview';
        start.disabled = !pairs.length;
        draw();
      }, function (err) { status.textContent = (err && err.message) || 'Không tải được các cặp dễ nhầm.'; });
    }

    main.addEventListener('click', function (e) {
      var t = e.target.closest('[data-tab]');
      if (t) { tab = t.getAttribute('data-tab'); search.value = ''; load(); return; }
      var m = e.target.closest('[data-mode]');
      if (m) { mode = m.getAttribute('data-mode'); search.value = ''; load(); return; }
      var pick = e.target.closest('[data-quiz-pick]');
      if (pick) {
        var box = pick.closest('[data-quiz]');
        var p = shown[Number(box.getAttribute('data-quiz'))];
        var k = Number(pick.getAttribute('data-quiz-pick'));
        var ok = p && p.question && k === Number(p.question.answer);
        box.querySelectorAll('[data-quiz-pick]').forEach(function (b) { b.disabled = true; });
        var ans = box.querySelector('.jp-pair__answer');
        ans.hidden = false;
        ans.textContent = (ok ? 'Đúng. ' : 'Chưa đúng. Đáp án: ' + (p.question.options[p.question.answer] || '') + '. ')
          + (p.question.explainVi || '');
        return;
      }
      var pg = e.target.closest('[data-page]');
      if (pg) { page += pg.getAttribute('data-page') === 'next' ? 1 : -1; draw(); return; }
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
    search.addEventListener('input', function () { page = 0; draw(); });
    load();
  }

  return { pairMarkup: pairMarkup, NO_PAIR: NO_PAIR, mount: mount };
});
