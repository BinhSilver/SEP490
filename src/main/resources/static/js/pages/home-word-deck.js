/* [JLAS-W0108] F25 home-word-deck.js — word cards beside the home hero.
 *
 * A tilted glass frame over the home background (the scene shows through it, so it always matches) with a stack of word cards in front:
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
  var SOURCE_KEY = 'home.wordDeck.source';
  // owner 10/11: the learner picks where the cards come from; AUTO = review words, else new words of the level
  var SOURCES = [
    { key: 'AUTO', label: 'Tự động (gợi ý)' },
    { key: 'REVIEW', label: 'Từ đang ôn' },
    { key: 'NOTEBOOK', label: 'Sổ tay của tôi' },
    { key: 'NEW', label: 'Từ mới cấp hiện tại' }
  ];
  var TITLES = { REVIEW: 'Từ đang ôn hôm nay', NOTEBOOK: 'Từ trong sổ tay', NEW: 'Từ mới hôm nay', START: 'Một từ, một khởi đầu',
    EMPTY_REVIEW: 'Chưa có từ đang ôn · gợi ý từ của cấp', EMPTY_NOTEBOOK: 'Sổ tay trống · gợi ý từ của cấp',
    EMPTY_NEW: 'Đã học hết từ mới · gợi ý từ của cấp' };
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

  /** Card eyebrow for the kind of list actually shown (REVIEW / NOTEBOOK / NEW / START). */
  function label(kind) {
    return TITLES[kind] || TITLES.START;
  }

  function cardHtml(word, title) {
    var w = word || {};
    return '<div class="hwd-card__eyebrow">' + esc(title) + '</div>'
      + '<span class="hwd-card__spark" aria-hidden="true">✦</span>'
      + '<div class="hwd-card__face">'
      + '<div class="hwd-card__word" lang="ja">' + esc(w.expression) + '</div>'
      + (w.reading && w.reading !== w.expression ? '<div class="hwd-card__reading" lang="ja">' + esc(w.reading) + '</div>' : '')
      + '<div class="hwd-card__meaning" hidden>' + esc(w.meaning) + '</div>'
      + '</div>'
      + '<div class="hwd-card__hint">↻ Chạm để xem nghĩa</div>'
      + '<div class="hwd-card__foot"><a class="hwd-card__learn" href="/learning">Học từ này ↗</a>'
      + '<button type="button" class="hwd-card__next" data-hwd="next" aria-label="Từ tiếp theo">→</button></div>';
  }

  function deckHtml() {
    return '<div class="hwd-bar">'
      + '<button type="button" class="hwd-more" data-hwd="menu" aria-haspopup="menu" aria-expanded="false" aria-label="Tùy chọn thẻ từ">'
      + '<img src="/images/mascots/kitsu/kitsu-announcing-128.webp" alt="" width="56" height="56" loading="lazy"></button>'
      + '<div class="hwd-menu" role="menu" hidden>'
      + '<div class="hwd-menu__title">Nguồn từ</div>'
      + SOURCES.map(function (o) {
        return '<button type="button" class="hwd-menu__item" role="menuitemradio" aria-checked="false" data-hwd-source="'
          + o.key + '"><span class="hwd-menu__check" aria-hidden="true">✓</span>' + esc(o.label) + '</button>';
      }).join('')
      + '<div class="hwd-menu__sep" role="separator"></div>'
      + '<button type="button" class="hwd-menu__item" role="menuitem" data-hwd="toggle"><span class="hwd-menu__check" aria-hidden="true"></span>'
      + '<span class="hwd-toggle__text">Ẩn thẻ từ</span></button>'
      + '</div></div>'
      // shown only while the cards are hidden: Kitsu resting, hover or focus says what a click does
      + '<button type="button" class="hwd-kitsu" data-hwd="show" aria-label="Mở thẻ từ">'
      + '<img src="/images/mascots/kitsu/kitsu-resting-30-128.webp" alt="" width="72" height="72" loading="lazy">'
      + '<span class="hwd-kitsu__bubble" aria-hidden="true">Mở thẻ từ</span></button>'
      + '<div class="hwd-stage">'
      + '<figure class="hwd-photo" aria-hidden="true"><span class="hwd-photo__glass"></span>'
      + '<span class="hwd-photo__poem" lang="ja">毎日、こつこつ。</span>'
      + '<span class="hwd-photo__badge" lang="ja">日<br>本</span>'
      + '<figcaption><span class="hwd-photo__corner"></span><span class="hwd-photo__tag">Mỗi ngày một chút</span><span class="hwd-photo__cap">KotsuKotsu</span></figcaption></figure>'
      + '<div class="hwd-deck"><span class="hwd-deck__back hwd-deck__back--2"></span><span class="hwd-deck__back hwd-deck__back--1"></span>'
      + '<div class="hwd-card" role="button" tabindex="0" aria-live="polite" data-hwd="flip"></div></div>'
      + '</div>';
  }

  function readSource() {
    try {
      var v = root.localStorage.getItem(SOURCE_KEY);
      return SOURCES.some(function (o) { return o.key === v; }) ? v : 'AUTO';
    } catch (e) { return 'AUTO'; }
  }

  function writeSource(v) {
    try { root.localStorage.setItem(SOURCE_KEY, v); } catch (e) { /* private mode: not kept */ }
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

  function itemsOf(page) { return (page && page.items) || []; }

  /**
   * Resolves to {words, kind} for {@code source}: REVIEW = words being learned at the open level, NOTEBOOK = the
   * learner's starred words, NEW = not-yet-learned words of the open level, AUTO = REVIEW else NEW. An empty choice
   * falls back to the level's list; a guest or a failed call gets the fixed starter words (kind START).
   */
  function loadWords(source) {
    return getJson(BASE + '/path').then(function (path) {
      var level = (path && path.openLevel) || 'N5';
      var url = BASE + '/lists/vocabulary?level=' + encodeURIComponent(level);
      function pick(kind, req) {
        return req.then(function (page) {
          var items = itemsOf(page);
          return items.length ? { words: pickWords(items, null, 5), kind: kind } : null;
        });
      }
      function review() { return pick('REVIEW', getJson(url + '&state=LEARNING')); }
      function fresh() { return pick('NEW', getJson(url + '&state=NEW')); }
      var chain;
      if (source === 'REVIEW') chain = review();
      else if (source === 'NOTEBOOK') chain = pick('NOTEBOOK', getJson(BASE + '/notebook?tab=VOCAB'));
      else if (source === 'NEW') chain = fresh();
      else chain = review().then(function (r) { return r || fresh(); });
      return chain.then(function (r) {
        return r || getJson(url).then(function (all) { return { words: pickWords(itemsOf(all), null, 5), kind: source && source !== 'AUTO' ? 'EMPTY_' + source : 'START' }; });
      });
    }).catch(function () { return { words: FALLBACK.slice(), kind: 'START' }; });
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
    var menu = box.querySelector('.hwd-menu');
    var more = box.querySelector('[data-hwd="menu"]');
    var state = { words: FALLBACK.slice(), kind: 'START', i: 0, source: readSource() };
    var auth = root.JlasAuth;
    if (!(auth && auth.isLoggedIn && auth.isLoggedIn())) { // a guest has no path: only hide/show is offered
      box.querySelectorAll('[data-hwd-source], .hwd-menu__title, .hwd-menu__sep').forEach(function (el) { el.hidden = true; });
    }

    function markSource() {
      box.querySelectorAll('[data-hwd-source]').forEach(function (el) {
        el.setAttribute('aria-checked', String(el.getAttribute('data-hwd-source') === state.source));
      });
    }

    function openMenu(v) {
      menu.hidden = !v;
      more.setAttribute('aria-expanded', String(v));
    }

    function reload() {
      loadWords(state.source).then(function (r) { state.words = r.words; state.kind = r.kind; state.i = 0; draw(); });
    }

    function draw() {
      card.innerHTML = cardHtml(state.words[state.i % state.words.length], label(state.kind));
      card.classList.remove('is-flipped');
    }

    function setHidden(v) {
      box.classList.toggle('is-hidden', v);
      toggle.querySelector('.hwd-toggle__text').textContent = v ? 'Hiện thẻ từ' : 'Ẩn thẻ từ';
    }

    function flip() {
      var flipped = card.classList.toggle('is-flipped');
      card.querySelector('.hwd-card__meaning').hidden = !flipped;
      card.querySelector('.hwd-card__hint').textContent = flipped ? '↻ Chạm để xem lại từ' : '↻ Chạm để xem nghĩa';
    }

    box.addEventListener('click', function (e) {
      if (e.target.closest('.hwd-card__learn')) return;
      if (e.target.closest('[data-hwd="show"]')) {
        setHidden(false);
        writeHidden(false);
        more.classList.remove('is-waking');
        void more.offsetWidth; // restart the wake-up animation
        more.classList.add('is-waking');
        return;
      }
      if (e.target.closest('[data-hwd="menu"]')) { openMenu(menu.hidden); return; }
      var src = e.target.closest('[data-hwd-source]');
      if (src) {
        state.source = src.getAttribute('data-hwd-source');
        writeSource(state.source);
        markSource();
        openMenu(false);
        reload();
        return;
      }
      if (e.target.closest('[data-hwd="toggle"]')) {
        var v = !box.classList.contains('is-hidden');
        setHidden(v);
        writeHidden(v);
        openMenu(false);
        return;
      }
      if (e.target.closest('[data-hwd="next"]')) {
        state.i = (state.i + 1) % state.words.length;
        card.classList.add('is-leaving');
        root.setTimeout(function () { card.classList.remove('is-leaving'); draw(); }, 220);
        return;
      }
      if (e.target.closest('[data-hwd="flip"]')) flip();
    });
    doc.addEventListener('click', function (e) { if (!box.contains(e.target)) openMenu(false); });
    doc.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !menu.hidden) { openMenu(false); more.focus(); } });
    markSource();
    card.addEventListener('keydown', function (e) {
      if ((e.key === 'Enter' || e.key === ' ') && e.target === card) { e.preventDefault(); flip(); }
    });

    setHidden(readHidden());
    draw();
    reload();
  }

  return { pickWords: pickWords, cardHtml: cardHtml, label: label, loadWords: loadWords, SOURCES: SOURCES, FALLBACK: FALLBACK, mount: mount };
});
