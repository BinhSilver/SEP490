/* F24 landing: hiện dần các khối khi cuộn tới; tắt khi người dùng giảm chuyển động. */
(function () {
  'use strict';
  // the guest blocks show only for a browser with no signed-in token
  var auth = window.JlasAuth;
  var signedIn = !!(auth && typeof auth.isLoggedIn === 'function' && auth.isLoggedIn());
  document.querySelectorAll('[data-lp-guest]').forEach(function (el) { el.hidden = signedIn; });
  if (signedIn) return;
  wireDemos();
  loadPlans();
  var items = document.querySelectorAll('.lp-reveal');
  // tiles slide in from alternate sides, everything else rises
  document.querySelectorAll('.lp-space > .lp-reveal').forEach(function (el, i) {
    el.setAttribute('data-from', i % 2 ? 'right' : 'left');
    el.style.setProperty('--rd', (i % 2 ? 120 : 0) + 'ms');
  });
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduce || !('IntersectionObserver' in window)) {
    items.forEach(function (el) { el.classList.add('is-visible'); });
    return;
  }
  document.documentElement.classList.add('lp-motion');
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      // like the reference: a block hides again once it leaves the screen and replays each time it comes back,
      // scrolling down or up
      if (entry.isIntersecting) entry.target.classList.add('is-visible');
      else if (entry.intersectionRatio === 0) entry.target.classList.remove('is-visible');
    });
  }, { rootMargin: '0px 0px -10% 0px' });
  items.forEach(function (el) { io.observe(el); });

  // restart a CSS animation class on an element
  function replay(el, cls) {
    el.classList.remove(cls);
    void el.offsetWidth;
    el.classList.add(cls);
  }

  // plans block: live prices from the public plan and CP package APIs; the block keeps its text if they fail
  function loadPlans() {
    var root = document.querySelector('[data-lp-plans]');
    if (!root || !window.fetch) return;
    function money(v) { return Number(v).toLocaleString('vi-VN') + 'đ'; }
    function get(url) {
      return fetch(url, { headers: { Accept: 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return j && j.data; })
        .catch(function () { return null; });
    }
    get('/api/premium/plans').then(function (d) {
      if (!d || !d.plans) return;
      var monthly = null;
      var yearly = null;
      d.plans.forEach(function (plan) {
        if (plan.name === 'MONTHLY') monthly = plan;
        if (plan.name === 'YEARLY') yearly = plan;
        var card = root.querySelector('[data-lp-plan="' + plan.name + '"]');
        if (!card) return;
        card.querySelector('[data-lp-price]').textContent = money(plan.price);
        card.querySelector('[data-lp-per]').textContent = '/ ' + plan.durationDays + ' ngày';
      });
      var save = root.querySelector('[data-lp-save]');
      if (monthly && yearly && save) {
        var full = monthly.price * yearly.durationDays / monthly.durationDays;
        var pct = Math.round((1 - yearly.price / full) * 100);
        if (pct > 0) { save.textContent = 'Tiết kiệm ' + pct + '%'; save.hidden = false; }
      }
      if (d.aiCall) {
        var f = root.querySelector('[data-lp-ai-free]');
        var p = root.querySelector('[data-lp-ai-pro]');
        if (f) f.textContent = 'Gọi AI ' + d.aiCall.freeDailyMinutes + ' phút/ngày, ' + d.aiCall.freeMonthlyMinutes + ' phút/tháng';
        if (p) p.textContent = 'Gọi AI ' + d.aiCall.premiumDailyMinutes + ' phút/ngày, ' + d.aiCall.premiumMonthlyMinutes + ' phút/tháng';
      }
    });
    get('/api/credits/packages').then(function (d) {
      var box = root.querySelector('[data-lp-cp]');
      var list = root.querySelector('[data-lp-cp-list]');
      if (!d || !d.packages || !d.packages.length || !box || !list) return;
      d.packages.forEach(function (pk) {
        var li = document.createElement('li');
        var b = document.createElement('b');
        b.textContent = pk.creditAmount + ' CP';
        var span = document.createElement('span');
        span.textContent = money(pk.price);
        li.appendChild(b);
        li.appendChild(span);
        list.appendChild(li);
      });
      box.hidden = false;
    });
  }

  // small try-it screens: flip card, next subtitle, sample reply, toolbox tabs, sample question
  function wireDemos() {
    document.querySelectorAll('[data-lp-flip]').forEach(function (card) {
      card.addEventListener('click', function () {
        var on = card.classList.toggle('is-flipped');
        card.setAttribute('aria-pressed', on ? 'true' : 'false');
      });
    });

    var lines = [
      ['今日は、日本語を勉強します。', 'Hôm nay tôi sẽ học tiếng Nhật.'],
      ['毎日、少しずつ練習しましょう。', 'Mỗi ngày hãy luyện tập một chút nhé.'],
      ['一緒に話してみませんか。', 'Bạn có muốn thử nói cùng nhau không?']
    ];
    document.querySelectorAll('[data-lp-sub]').forEach(function (box) {
      var i = 0;
      var ja = box.querySelector('[data-lp-sub-ja]');
      var vi = box.querySelector('[data-lp-sub-vi]');
      var btn = box.querySelector('[data-lp-sub-next]');
      if (!ja || !vi || !btn) return;
      btn.addEventListener('click', function () {
        i = (i + 1) % lines.length;
        ja.textContent = lines[i][0];
        vi.textContent = lines[i][1];
        replay(ja, 'is-new');
        replay(vi, 'is-new');
      });
    });

    document.querySelectorAll('[data-lp-reply-btn]').forEach(function (btn) {
      var reply = btn.parentNode.querySelector('[data-lp-reply]');
      var typing = btn.parentNode.querySelector('[data-lp-typing]');
      if (!reply) return;
      btn.addEventListener('click', function () {
        if (!reply.hidden) {
          reply.hidden = true;
          btn.textContent = 'Thử câu trả lời mẫu';
          return;
        }
        btn.disabled = true;
        if (typing) typing.hidden = false;
        setTimeout(function () {
          if (typing) typing.hidden = true;
          reply.hidden = false;
          btn.disabled = false;
          btn.textContent = 'Ẩn câu trả lời';
        }, 900);
      });
    });

    document.querySelectorAll('[data-lp-tabs]').forEach(function (root) {
      var tabs = Array.prototype.slice.call(root.querySelectorAll('[role="tab"]'));
      function select(tab, focus) {
        tabs.forEach(function (t) {
          var on = t === tab;
          t.setAttribute('aria-selected', on ? 'true' : 'false');
          t.tabIndex = on ? 0 : -1;
          var pane = document.getElementById(t.getAttribute('aria-controls'));
          if (pane) pane.hidden = !on;
        });
        if (focus) tab.focus();
      }
      tabs.forEach(function (tab, idx) {
        tab.addEventListener('click', function () { select(tab, false); });
        tab.addEventListener('keydown', function (e) {
          var step = (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? 1 : (e.key === 'ArrowUp' || e.key === 'ArrowLeft') ? -1 : 0;
          if (!step) return;
          e.preventDefault();
          select(tabs[(idx + step + tabs.length) % tabs.length], true);
        });
      });
    });

    // flashcard: next word flies in
    var words = [
      ['旅行', 'りょこう', 'du lịch', 'Danh từ · N5'],
      ['勉強', 'べんきょう', 'học tập', 'Danh từ · N5'],
      ['友達', 'ともだち', 'bạn bè', 'Danh từ · N5']
    ];
    // the card shows the word, turns to its meaning, then the next word flies in; on its own, no button.
    // It waits while off screen, while hovered, or when the visitor asks for less motion.
    document.querySelectorAll('.lp-flash').forEach(function (box) {
      var card = box.querySelector('[data-lp-flip]');
      var count = box.querySelector('[data-lp-flash-count]');
      var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      var k = 0;
      var phase = 0; // 0 = word showing, 1 = meaning showing
      var onScreen = false;
      var hovered = false;
      if (!card || reduce) return;
      function setFlipped(on) {
        card.classList.toggle('is-flipped', on);
        card.setAttribute('aria-pressed', on ? 'true' : 'false');
      }
      function showWord(i) {
        box.querySelector('[data-lp-w]').textContent = words[i][0];
        box.querySelector('[data-lp-r]').textContent = words[i][1];
        box.querySelector('[data-lp-m]').textContent = words[i][2];
        box.querySelector('[data-lp-t]').textContent = words[i][3];
        if (count) count.textContent = (i + 1) + ' / ' + words.length;
      }
      function tick() {
        if (!onScreen || hovered || document.hidden) return;
        if (phase === 0) {
          setFlipped(true);
          phase = 1;
          return;
        }
        k = (k + 1) % words.length;
        setFlipped(false);
        showWord(k);
        replay(card, 'is-arriving');
        phase = 0;
      }
      card.addEventListener('click', function () { phase = card.classList.contains('is-flipped') ? 1 : 0; });
      if ('IntersectionObserver' in window) {
        new IntersectionObserver(function (entries) { onScreen = entries[0].isIntersecting; }, { threshold: 0.4 }).observe(box);
      } else {
        onScreen = true;
      }
      box.addEventListener('mouseenter', function () { hovered = true; });
      box.addEventListener('mouseleave', function () { hovered = false; });
      setInterval(tick, 2600);
    });

    // open book: turns its own pages like a real book (one leaf at a time), then closes back and starts over;
    // it waits while off screen, while hovered, or when the visitor asks for less motion
    document.querySelectorAll('[data-lp-book]').forEach(function (book) {
      var open = book.querySelector('.lp-book__open');
      var leaves = Array.prototype.slice.call(book.querySelectorAll('.lp-book__leaf'));
      var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      var TURN_MS = 1500;
      var turned = 0;
      var busy = false;
      var onScreen = false;
      var hovered = false;

      function turn(leaf, forward, done) {
        leaf.classList.remove('is-turning-fwd', 'is-turning-back');
        void leaf.offsetWidth;
        leaf.classList.toggle('is-turned', forward);
        leaf.classList.add(forward ? 'is-turning-fwd' : 'is-turning-back');
        open.classList.add('is-turning');
        setTimeout(function () {
          leaf.classList.remove('is-turning-fwd', 'is-turning-back');
          open.classList.remove('is-turning');
          if (done) done();
        }, TURN_MS);
      }

      function step() {
        if (busy || !onScreen || hovered || document.hidden) return;
        busy = true;
        if (turned < leaves.length) {
          turn(leaves[turned], true, function () { turned++; busy = false; });
          return;
        }
        // last spread shown: close the book back, leaf after leaf, then start again
        var i = leaves.length - 1;
        (function back() {
          if (i < 0) { turned = 0; busy = false; return; }
          turn(leaves[i], false);
          i--;
          setTimeout(back, 380);
        })();
      }

      if (reduce) return;
      if ('IntersectionObserver' in window) {
        new IntersectionObserver(function (entries) { onScreen = entries[0].isIntersecting; }, { threshold: 0.4 }).observe(book);
      } else {
        onScreen = true;
      }
      book.addEventListener('mouseenter', function () { hovered = true; });
      book.addEventListener('mouseleave', function () { hovered = false; });
      setInterval(step, 3600);
    });

    // kanji stroke steps: show strokes up to the chosen one
    document.querySelectorAll('.lp-kanji').forEach(function (kanji) {
      var paths = kanji.querySelectorAll('[data-lp-draw] path');
      var steps = kanji.querySelectorAll('[data-lp-stroke]');
      steps.forEach(function (b) {
        b.addEventListener('click', function () {
          var n = +b.getAttribute('data-lp-stroke');
          kanji.querySelector('[data-lp-draw]').classList.remove('is-drawing');
          paths.forEach(function (p, i) {
            p.classList.toggle('is-hidden', i >= n);
            p.classList.toggle('is-current', i === n - 1);
          });
          steps.forEach(function (x) { x.setAttribute('aria-pressed', x === b ? 'true' : 'false'); });
        });
      });
    });

    // kanji stroke order: draw when first seen, and again on each tap
    document.querySelectorAll('[data-lp-draw]').forEach(function (box) {
      box.addEventListener('click', function () {
        box.querySelectorAll('path').forEach(function (p) { p.classList.remove('is-hidden', 'is-current'); });
        replay(box, 'is-drawing');
      });
      if ('IntersectionObserver' in window) {
        var seen = new IntersectionObserver(function (entries) {
          if (entries[0].isIntersecting) { replay(box, 'is-drawing'); seen.disconnect(); }
        }, { threshold: 0.5 });
        seen.observe(box);
      }
    });

    document.querySelectorAll('[data-lp-quiz]').forEach(function (quiz) {
      var hint = quiz.querySelector('[data-lp-quiz-hint]');
      var opts = quiz.querySelectorAll('.lp-quiz__opts button');
      opts.forEach(function (btn) {
        btn.addEventListener('click', function () {
          opts.forEach(function (b) { b.classList.remove('is-right', 'is-wrong'); });
          var ok = btn.hasAttribute('data-ok');
          btn.classList.add(ok ? 'is-right' : 'is-wrong');
          if (!ok) quiz.querySelector('[data-ok]').classList.add('is-right');
          if (hint) hint.textContent = ok
            ? 'Chính xác! 学校 đọc là がっこう: 学 (がく) + 校 (こう), âm く biến thành っ.'
            : 'Chưa đúng. Đáp án là がっこう: 学 (がく) + 校 (こう), âm く biến thành っ.';
          if (hint) replay(hint, 'is-new');
        });
      });
    });
  }
})();
