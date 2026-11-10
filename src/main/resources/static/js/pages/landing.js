/* F24 landing: hiện dần các khối khi cuộn tới; tắt khi người dùng giảm chuyển động. */
(function () {
  'use strict';
  // the guest blocks show only for a browser with no signed-in token
  var auth = window.JlasAuth;
  var signedIn = !!(auth && typeof auth.isLoggedIn === 'function' && auth.isLoggedIn());
  document.querySelectorAll('[data-lp-guest]').forEach(function (el) { el.hidden = signedIn; });
  if (signedIn) return;
  wireDemos();
  var items = document.querySelectorAll('.lp-reveal');
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduce || !('IntersectionObserver' in window)) {
    items.forEach(function (el) { el.classList.add('is-visible'); });
    return;
  }
  document.documentElement.classList.add('lp-motion');
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        io.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -10% 0px' });
  items.forEach(function (el) { io.observe(el); });

  // restart a CSS animation class on an element
  function replay(el, cls) {
    el.classList.remove(cls);
    void el.offsetWidth;
    el.classList.add(cls);
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

    // kanji stroke order: draw when first seen, and again on each tap
    document.querySelectorAll('[data-lp-draw]').forEach(function (box) {
      box.addEventListener('click', function () { replay(box, 'is-drawing'); });
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
