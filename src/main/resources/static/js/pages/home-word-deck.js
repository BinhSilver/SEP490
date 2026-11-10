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
  var SOURCE_TITLES = { AUTO: 'Gợi ý cho bạn', REVIEW: 'Từ đang ôn', NOTEBOOK: 'Sổ tay của tôi', NEW: 'Từ mới cấp hiện tại' };
  var TITLES = { QUICK: 'Một từ mới mỗi ngày', REVIEW: 'Từ đang ôn hôm nay', NOTEBOOK: 'Từ trong sổ tay', NEW: 'Từ mới hôm nay', START: 'Một từ, một khởi đầu',
    EMPTY_REVIEW: 'Chưa có từ đang ôn · gợi ý từ của cấp', EMPTY_NOTEBOOK: 'Sổ tay trống · gợi ý từ của cấp',
    EMPTY_NEW: 'Đã học hết từ mới · gợi ý từ của cấp' };
  var BASE = '/api/learning/jlpt';
  // owner 11/11: at least 30 fixed N5 words, shown to a learner without a path (and if a call fails)
  var FALLBACK = [
    { expression: '私', reading: 'わたし', meaning: 'tôi' },
    { expression: '学校', reading: 'がっこう', meaning: 'trường học' },
    { expression: '友達', reading: 'ともだち', meaning: 'bạn bè' },
    { expression: '毎日', reading: 'まいにち', meaning: 'mỗi ngày' },
    { expression: '先生', reading: 'せんせい', meaning: 'giáo viên' },
    { expression: '学生', reading: 'がくせい', meaning: 'học sinh, sinh viên' },
    { expression: '日本語', reading: 'にほんご', meaning: 'tiếng Nhật' },
    { expression: '水', reading: 'みず', meaning: 'nước' },
    { expression: '山', reading: 'やま', meaning: 'núi' },
    { expression: '川', reading: 'かわ', meaning: 'sông' },
    { expression: '花', reading: 'はな', meaning: 'hoa' },
    { expression: '雨', reading: 'あめ', meaning: 'mưa' },
    { expression: '天気', reading: 'てんき', meaning: 'thời tiết' },
    { expression: '今日', reading: 'きょう', meaning: 'hôm nay' },
    { expression: '明日', reading: 'あした', meaning: 'ngày mai' },
    { expression: '時間', reading: 'じかん', meaning: 'thời gian' },
    { expression: '電車', reading: 'でんしゃ', meaning: 'tàu điện' },
    { expression: '駅', reading: 'えき', meaning: 'nhà ga' },
    { expression: '家', reading: 'いえ', meaning: 'nhà' },
    { expression: '家族', reading: 'かぞく', meaning: 'gia đình' },
    { expression: '母', reading: 'はは', meaning: 'mẹ (của mình)' },
    { expression: '父', reading: 'ちち', meaning: 'bố (của mình)' },
    { expression: '本', reading: 'ほん', meaning: 'sách' },
    { expression: '名前', reading: 'なまえ', meaning: 'tên' },
    { expression: '食べる', reading: 'たべる', meaning: 'ăn' },
    { expression: '飲む', reading: 'のむ', meaning: 'uống' },
    { expression: '行く', reading: 'いく', meaning: 'đi' },
    { expression: '見る', reading: 'みる', meaning: 'xem, nhìn' },
    { expression: '大きい', reading: 'おおきい', meaning: 'to, lớn' },
    { expression: '新しい', reading: 'あたらしい', meaning: 'mới' }
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
  function label(kind, source, level) {
    if (kind === 'QUICK' || /^EMPTY_/.test(kind || '') || !source) return TITLES[kind] || TITLES.START;
    var t = SOURCE_TITLES[source] || SOURCE_TITLES.AUTO;
    return source === 'NEW' && level ? 'Từ mới cấp ' + level : t;
  }

  function cardHtml(word, title, kind) {
    var w = word || {};
    var link = kind === 'QUICK'
      ? '<a class="hwd-card__learn" href="/search/word/' + encodeURIComponent(w.expression || '') + '">Tra từ này ↗</a>'
      : '<a class="hwd-card__learn" href="/learning">Học từ này ↗</a>';
    return '<div class="hwd-card__eyebrow">' + esc(title) + '</div>'
      + '<span class="hwd-card__spark" aria-hidden="true">✦</span>'
      + '<div class="hwd-card__face">'
      + '<div class="hwd-card__word" lang="ja">' + esc(w.expression) + '</div>'
      + (w.reading && w.reading !== w.expression ? '<div class="hwd-card__reading" lang="ja">' + esc(w.reading) + '</div>' : '')
      + '<div class="hwd-card__meaning" hidden>' + esc(w.meaning) + '</div>'
      + '</div>'
      + '<div class="hwd-card__hint">↻ Chạm để xem nghĩa</div>'
      + '<div class="hwd-card__foot">' + link
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
  var state0 = { level: null };

  function loadWords(source) {
    return getJson(BASE + '/path').then(function (path) {
      var level = (path && path.openLevel) || 'N5';
      state0.level = level;
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
    }).catch(function () { return { words: pickWords(FALLBACK, null, FALLBACK.length), kind: 'QUICK' }; });
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var hero = doc && doc.querySelector('.home-hero');
    if (mounted || !hero) return;
    // F24: the word deck and glass frame are for signed-in learners only; guests get the plain hero
    var auth = root.JlasAuth;
    if (!(auth && typeof auth.isLoggedIn === 'function' && auth.isLoggedIn())) return;
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
      card.innerHTML = cardHtml(state.words[state.i % state.words.length], label(state.kind, state.source, state0.level), state.kind);
      // without a path there is nothing being reviewed or saved yet: only "Tự động" and "Từ mới" stay in the menu
      box.querySelectorAll('[data-hwd-source="REVIEW"], [data-hwd-source="NOTEBOOK"]').forEach(function (el) {
        el.hidden = state.kind === 'QUICK';
      });
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
