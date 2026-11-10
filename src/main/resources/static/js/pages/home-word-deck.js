/* [JLAS-W0108] F25 home-word-deck.js — word cards beside the home hero.
 *
 * A framed copy of the home background (day/night image of .home-scene) with a stack of word cards in front:
 * the front shows one word, a tap flips it to the meaning, the arrow moves to the next word and "Học từ này"
 * opens the learning path. Words come from the learner's JLPT path (state LEARNING at the open level, else the
 * open level's list, rotated by day); a guest or a failed call gets a few fixed N5 words.
 * A small button hides or shows the whole block; the choice is kept per browser (localStorage, best effort).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var HIDE_KEY = 'home.wordDeck.hidden';
  var BASE = '/api/learning/jlpt';
  var FALLBACK = [
    { expression: '私', reading: 'わたし', meaning: 'tôi' },
    { expression: '学校', reading: 'がっこう', meaning: 'trường học' },
    { expression: '友達', reading: 'ともだち', meaning: 'bạn bè' },
    { expression: '毎日', reading: 'まいにち', meaning: 'mỗi ngày' }
  ];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** Day number since epoch, used to rotate the list so each day starts on a different word. */
  function dayIndex(now) {
    return Math.floor((now || Date.now()) / 86400000);
  }

  /** Up to {@code max} words starting at the day's offset; empty or bad input gives the fixed words. */
  function pickWords(items, now, max) {
    var list = (Array.isArray(items) ? items : []).filter(function (x) { return x && x.expression; });
    if (!list.length) return FALLBACK.slice();
    var n = Math.min(max || 5, list.length);
    var start = dayIndex(now) % list.length;
    var out = [];
    for (var i = 0; i < n; i++) out.push(list[(start + i) % list.length]);
    return out;
  }

  function label(learning) {
    return learning ? 'Từ đang ôn hôm nay' : 'Một từ, một khởi đầu';
  }

  function cardHtml(word, title) {
    var w = word || {};
    return '<p class="hwd-card__eyebrow">' + esc(title) + '</p>'
      + '<span class="hwd-card__spark" aria-hidden="true">✦</span>'
      + '<div class="hwd-card__face">'
      + '<p class="hwd-card__word" lang="ja">' + esc(w.expression) + '</p>'
      + (w.reading && w.reading !== w.expression ? '<p class="hwd-card__reading" lang="ja">' + esc(w.reading) + '</p>' : '')
      + '<p class="hwd-card__meaning" hidden>' + esc(w.meaning) + '</p>'
      + '</div>'
      + '<p class="hwd-card__hint">↻ Chạm để xem nghĩa</p>'
      + '<div class="hwd-card__foot"><a class="hwd-card__learn" href="/learning">Học từ này ↗</a>'
      + '<button type="button" class="hwd-card__next" data-hwd="next" aria-label="Từ tiếp theo">→</button></div>';
  }

  function deckHtml() {
    return '<button type="button" class="hwd-toggle" data-hwd="toggle" aria-expanded="true">Ẩn thẻ từ</button>'
      + '<div class="hwd-stage">'
      + '<figure class="hwd-photo" aria-hidden="true"><span class="hwd-photo__img"></span>'
      + '<span class="hwd-photo__poem" lang="ja">毎日、こつこつ。</span>'
      + '<span class="hwd-photo__badge" lang="ja">日<br>本</span>'
      + '<figcaption class="hwd-photo__cap">KotsuKotsu · mỗi ngày một chút</figcaption></figure>'
      + '<div class="hwd-deck"><span class="hwd-deck__back hwd-deck__back--2"></span><span class="hwd-deck__back hwd-deck__back--1"></span>'
      + '<div class="hwd-card" role="button" tabindex="0" aria-live="polite" data-hwd="flip"></div></div>'
      + '</div>';
  }

  function readHidden() {
    try { return root.localStorage.getItem(HIDE_KEY) === '1'; } catch (e) { return false; }
  }

  function writeHidden(v) {
    try { root.localStorage.setItem(HIDE_KEY, v ? '1' : '0'); } catch (e) { /* private mode: not kept */ }
  }

  function getJson(url) {
    var auth = root.JlasAuth;
    if (!auth || typeof auth.isLoggedIn !== 'function' || !auth.isLoggedIn()) return Promise.reject(new Error('guest'));
    return root.fetch(url, { headers: auth.authHeaders() }).then(function (r) {
      if (!r.ok) throw new Error('HTTP_' + r.status);
      return r.json();
    }).then(function (env) { return env && env.data; });
  }

  /** Resolves to {words, learning}: words being learned at the open level, else that level's list. */
  function loadWords() {
    return getJson(BASE + '/path').then(function (path) {
      var level = (path && path.openLevel) || 'N5';
      var url = BASE + '/lists/vocabulary?level=' + encodeURIComponent(level);
      return getJson(url + '&state=LEARNING').then(function (page) {
        var items = page && page.items;
        if (items && items.length) return { words: pickWords(items, null, 5), learning: true };
        return getJson(url).then(function (all) { return { words: pickWords(all && all.items, null, 5), learning: false }; });
      });
    }).catch(function () { return { words: FALLBACK.slice(), learning: false }; });
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var hero = doc && doc.querySelector('.home-hero');
    if (mounted || !hero) return;
    mounted = true;
    var box = doc.createElement('aside');
    box.className = 'hwd';
    box.setAttribute('aria-label', 'Thẻ từ hôm nay');
    box.innerHTML = deckHtml();
    hero.appendChild(box);
    var card = box.querySelector('.hwd-card');
    var toggle = box.querySelector('[data-hwd="toggle"]');
    var state = { words: FALLBACK.slice(), learning: false, i: 0 };

    function draw() {
      card.innerHTML = cardHtml(state.words[state.i % state.words.length], label(state.learning));
      card.classList.remove('is-flipped');
    }

    function setHidden(v) {
      box.classList.toggle('is-hidden', v);
      toggle.textContent = v ? 'Hiện thẻ từ' : 'Ẩn thẻ từ';
      toggle.setAttribute('aria-expanded', String(!v));
    }

    function flip() {
      var flipped = card.classList.toggle('is-flipped');
      card.querySelector('.hwd-card__meaning').hidden = !flipped;
      card.querySelector('.hwd-card__hint').textContent = flipped ? '↻ Chạm để xem lại từ' : '↻ Chạm để xem nghĩa';
    }

    box.addEventListener('click', function (e) {
      if (e.target.closest('.hwd-card__learn')) return;
      if (e.target.closest('[data-hwd="toggle"]')) { var v = !box.classList.contains('is-hidden'); setHidden(v); writeHidden(v); return; }
      if (e.target.closest('[data-hwd="next"]')) {
        state.i = (state.i + 1) % state.words.length;
        card.classList.add('is-leaving');
        root.setTimeout(function () { card.classList.remove('is-leaving'); draw(); }, 220);
        return;
      }
      if (e.target.closest('[data-hwd="flip"]')) flip();
    });
    card.addEventListener('keydown', function (e) {
      if ((e.key === 'Enter' || e.key === ' ') && e.target === card) { e.preventDefault(); flip(); }
    });

    setHidden(readHidden());
    draw();
    loadWords().then(function (r) { state.words = r.words; state.learning = r.learning; state.i = 0; draw(); });
  }

  return { pickWords: pickWords, cardHtml: cardHtml, label: label, FALLBACK: FALLBACK, mount: mount };
});
