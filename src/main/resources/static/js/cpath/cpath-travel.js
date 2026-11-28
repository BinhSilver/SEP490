/*
 * [JLAS-W0108] F70 (owner 12/11): Du lịch "học vui vui" — two pages built from the goal's own phrases and words.
 *   #cpathPhrasebookRoot  Sổ câu cấp tốc   GET /api/learning/comm/travel/phrasebook  phrases by place; tap = listen
 *   #cpathGameRoot        Trò chơi nghe nhanh GET/POST /api/learning/comm/travel/game  60 s: hear → pick the meaning;
 *                         right = 10 points + 2 per answer in the streak, wrong = streak back to 0; best score kept.
 * Both use the learner's listening speed (CpathAudio) and the romaji toggle of the travel path.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathTravel = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () {
      api.mountPhrasebook(root.document.getElementById('cpathPhrasebookRoot'));
      api.mountGame(root.document.getElementById('cpathGameRoot'));
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var POINTS = 10, STREAK_BONUS = 2;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function romajiOf(text) {
    var S = root && root.CpathSituation;
    return S && typeof S.toRomaji === 'function' ? S.toRomaji(text) : '';
  }

  /** Points of one right answer when the streak (after it) is n. */
  function points(streak) { return POINTS + STREAK_BONUS * Math.max(0, streak - 1); }

  function speaker() {
    return '<svg viewBox="0 0 20 20" width="18" height="18" fill="none" aria-hidden="true">'
      + '<path d="M9.5 4.5L5.8 7.5H3.2v5h2.6l3.7 3v-11z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>'
      + '<path d="M12.8 7.2a4 4 0 0 1 0 5.6M15 5.2a7 7 0 0 1 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
  }

  function play(url, text) {
    if (root.CpathAudio) root.CpathAudio.play(url, text);
  }

  // ------------------------------------------------------------------------------------------- phrasebook

  /** owner 17/11: a phrase alone reads like a grammar pattern; show one ready sentence that uses it. */
  function exampleOf(p, lines) {
    var sit = root && root.CpathSituation;
    if (sit && typeof sit.exampleLineFor === 'function') return sit.exampleLineFor({ ja: p.ja, example: p.example }, lines || []);
    return p.example && p.example.ja ? p.example : null;
  }

  function exampleHtml(p, lines, romaji) {
    var ex = exampleOf(p, lines);
    if (!ex || !ex.ja || String(ex.ja) === String(p.ja)) return '';
    return '<button type="button" class="cp-pb__example" data-pb-play="' + esc(ex.audioUrl || '') + '" data-pb-text="' + esc(ex.ja) + '">'
      + '<span class="cp-pb__ex-label">Ví dụ</span>'
      + '<span class="cp-pb__ex-ja" lang="ja">' + esc(ex.ja) + '</span>'
      + (romaji && romajiOf(ex.furigana || ex.reading || ex.ja) ? '<span class="cp-line__romaji">' + esc(romajiOf(ex.furigana || ex.reading || ex.ja)) + '</span>' : '')
      + (ex.vi ? '<span class="cp-pb__ex-vi">' + esc(ex.vi) + '</span>' : '') + '</button>';
  }

  function phrasebookHtml(data, romaji, filter) {
    var topics = (data && data.topics) || [];
    var chips = '<div class="cp-pb__chips" role="group" aria-label="Chọn nơi">'
      + '<button type="button" class="cp-rest-chip" data-pb-filter="" aria-pressed="' + (!filter ? 'true' : 'false') + '"><span>Tất cả</span></button>'
      + topics.map(function (t) {
        return '<button type="button" class="cp-rest-chip" data-pb-filter="' + esc(t.code) + '" aria-pressed="' + (filter === t.code ? 'true' : 'false') + '"><span>' + esc(t.nameVi) + '</span></button>';
      }).join('') + '</div>';
    var total = 0;
    var body = topics.filter(function (t) { return !filter || t.code === filter; }).map(function (t) {
      total += (t.phrases || []).length;
      return '<section class="jp-card cp-pb__place" aria-labelledby="pb-' + esc(t.code) + '">'
        + '<h2 id="pb-' + esc(t.code) + '">' + esc(t.nameVi) + (t.nameJa ? ' <small lang="ja">' + esc(t.nameJa) + '</small>' : '') + '</h2>'
        + '<ul class="cp-pb__list">' + (t.phrases || []).map(function (p) {
          return '<li><button type="button" class="cp-pb__phrase" data-pb-play="' + esc(p.audioUrl || '') + '" data-pb-text="' + esc(p.ja) + '">'
            + '<span class="cp-pb__icon">' + speaker() + '</span>'
            + '<span class="cp-pb__body"><span class="cp-pb__ja" lang="ja">' + esc(p.ja) + '</span>'
            + (romaji ? '<span class="cp-line__romaji">' + esc(romajiOf(p.reading || p.ja)) + '</span>' : '')
            + '<span class="cp-pb__vi">' + esc(p.vi) + '</span>'
            + (p.note ? '<span class="cp-pb__note">' + esc(p.note) + '</span>' : '') + '</span></button>'
            + exampleHtml(p, t.lines, romaji) + '</li>';
        }).join('') + '</ul></section>';
    }).join('');
    return '<header class="jp-page-head"><h1 class="jp-page-head__title">Sổ câu cấp tốc</h1>'
      + '<p class="jp-page-head__lede">Câu cứu cánh theo từng nơi trong chuyến đi. Bấm vào câu để nghe; mở ngay khi cần nói.</p></header>'
      + (root.CpathAudio ? root.CpathAudio.sliderHtml({ compact: true }) : '')
      + '<div class="cp-pb__tools">' + chips
      + '<button type="button" class="cp-btn-ghost" data-pb-romaji aria-pressed="' + (romaji ? 'true' : 'false') + '">Romaji</button></div>'
      + (total ? body : '<p class="jp-side__note">Chưa có câu nào.</p>')
      + '<div class="cp-settings__actions"><a class="cp-btn-primary" href="/learning/comm/game">Chơi nghe nhanh 60 giây</a>'
      + '<a class="cp-btn-ghost" href="/learning/comm/path">Về lộ trình</a></div>';
  }

  function mountPhrasebook(el) {
    if (!el || !root.JpathApi) return;
    var data = null, romaji = false, filter = '';
    function render() { el.innerHTML = phrasebookHtml(data, romaji, filter); }
    if (root.CpathAudio) root.CpathAudio.bind(el, root.CpathAudio.saveToServer);
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      var b = t.closest && t.closest('[data-pb-play]');
      if (b) { play(b.getAttribute('data-pb-play'), b.getAttribute('data-pb-text')); return; }
      var f = t.closest && t.closest('[data-pb-filter]');
      if (f) { filter = f.getAttribute('data-pb-filter') || ''; render(); return; }
      if (t.closest && t.closest('[data-pb-romaji]')) { romaji = !romaji; render(); }
    });
    Promise.resolve(root.JpathApi.get('/api/learning/comm/travel/phrasebook')).then(function (d) {
      data = d || {};
      romaji = !!(data.display && data.display.romaji);
      if (root.CpathAudio) root.CpathAudio.fromDisplay(data.display);
      render();
    }).catch(function (e) {
      el.innerHTML = '<p class="jp-alert jp-alert--page" role="alert">' + esc(e && (e.code || e.status) && e.message || 'Chưa tải được sổ câu.') + '</p>';
    });
  }

  // ------------------------------------------------------------------------------------------------ game

  // owner 17/11: wider game screens, the Japanese and romaji can be shown, a review list at the end
  var GAME_VIEW_KEY = 'cpath-game-view';
  function readView() {
    try { var v = JSON.parse(root.localStorage.getItem(GAME_VIEW_KEY) || '{}'); return { ja: !!v.ja, romaji: !!v.romaji }; }
    catch (e) { return { ja: false, romaji: false }; }
  }
  function saveView(v) { try { root.localStorage.setItem(GAME_VIEW_KEY, JSON.stringify(v)); } catch (e) { /* private window */ } }

  function viewToggles(view) {
    var v = view || {};
    return '<div class="cp-game__toggles" role="group" aria-label="Hiện chữ">'
      + '<button type="button" class="cp-rest-chip" data-game-view="ja" aria-pressed="' + (v.ja ? 'true' : 'false') + '"><span>Hiện chữ Nhật</span></button>'
      + '<button type="button" class="cp-rest-chip" data-game-view="romaji" aria-pressed="' + (v.romaji ? 'true' : 'false') + '"><span>Romaji</span></button>'
      + '</div>';
  }

  function jaBlock(q, view, force) {
    var v = view || {};
    var showJa = force || v.ja;
    var showRo = force ? v.romaji || !v.ja : v.romaji;
    if (!showJa && !showRo) return '';
    return '<div class="cp-game__text">'
      + (showJa ? '<span class="cp-game__ja" lang="ja">' + esc(q.ja) + '</span>' : '')
      + (showRo ? '<span class="cp-game__romaji">' + esc(romajiOf(q.reading || q.ja)) + '</span>' : '')
      + '</div>';
  }

  function gameStartHtml(data, view) {
    var n = (data.questions || []).length;
    return '<header class="jp-page-head cp-game__head"><h1 class="jp-page-head__title">Trò chơi nghe nhanh</h1>'
      + '<p class="jp-page-head__lede">Nghe từ hoặc câu du lịch rồi chọn nghĩa đúng. Đúng liên tiếp được cộng điểm chuỗi; sai thì chuỗi về 0.</p></header>'
      + '<section class="jp-card cp-game cp-game__start">'
      + '<ul class="cp-game__tiles">'
      + '<li><strong>' + esc(data.seconds || 60) + ' giây</strong><span>mỗi lượt chơi</span></li>'
      + '<li><strong>+10 → +18</strong><span>điểm, tăng theo chuỗi đúng</span></li>'
      + '<li><strong>' + esc(data.best || 0) + '</strong><span>kỷ lục của bạn</span></li></ul>'
      + '<div class="cp-game__setup"><div class="cp-game__setup-row"><span class="cp-game__setup-label">Tốc độ nghe</span>'
      + (root.CpathAudio ? root.CpathAudio.sliderHtml({ compact: true }) : '') + '</div>'
      + '<div class="cp-game__setup-row"><span class="cp-game__setup-label">Khi nghe</span>' + viewToggles(view)
      + '<span class="cp-game__setup-hint">Mới học thì bật chữ hoặc romaji; quen rồi tắt đi để luyện tai.</span></div></div>'
      + '<button type="button" class="cp-btn-primary cp-game__go" data-game-start' + (n ? '' : ' disabled') + '>Bắt đầu</button>'
      + (n ? '' : '<p class="jp-side__note">Chưa có âm thanh cho trò chơi.</p>')
      + '</section>';
  }

  function gameQuestionHtml(st, view) {
    var q = st.questions[st.i];
    var total = Number(st.seconds) || 60;
    var pct = Math.max(0, Math.min(100, Math.round(st.left * 100 / total)));
    var fb = st.feedback;
    return '<section class="jp-card cp-game cp-game--play" aria-live="polite">'
      + '<div class="cp-game__bar">'
      + '<span class="cp-game__stat cp-game__time"><span data-game-time>' + st.left + '</span> giây</span>'
      + '<span class="cp-game__stat">Điểm <strong>' + st.score + '</strong></span>'
      + '<span class="cp-game__stat">Chuỗi <strong>' + st.streak + '</strong></span>'
      + '<span class="cp-game__stat">Đúng <strong>' + st.right + '/' + st.answered + '</strong></span></div>'
      + '<div class="cp-game__timebar" aria-hidden="true"><span data-game-timebar style="width:' + pct + '%"></span></div>'
      + '<div class="cp-game__prompt">'
      + '<button type="button" class="cp-game__play" data-game-replay aria-label="Nghe lại">' + speaker() + '</button>'
      + '<div class="cp-game__prompt-body"><span class="cp-game__ask">Câu ' + (st.answered + 1) + ' · Nghe rồi chọn nghĩa</span>'
      + jaBlock(q, view, false) + '</div>' + viewToggles(view) + '</div>'
      + (fb ? '<div class="cp-game__fb' + (fb.ok ? ' is-ok' : ' is-wrong') + '" role="status">'
        + '<strong>' + (fb.ok ? 'Đúng! +' + fb.points + ' điểm' : 'Chưa đúng') + '</strong>'
        + '<span class="cp-game__fb-line"><span lang="ja">' + esc(fb.q.ja) + '</span>'
        + '<span class="cp-game__fb-ro">' + esc(romajiOf(fb.q.reading || fb.q.ja)) + '</span>'
        + '<span>= ' + esc(fb.q.options[fb.q.answer]) + '</span></span></div>' : '')
      + '<div class="cp-game__opts" role="group" aria-label="Chọn nghĩa">'
      + q.options.map(function (o, i) {
        return '<button type="button" class="cp-game__opt" data-game-pick="' + i + '"><span class="cp-game__opt-key">'
          + 'ABCD'.charAt(i) + '</span><span>' + esc(o) + '</span></button>';
      }).join('') + '</div></section>';
  }

  function gameEndHtml(st, res) {
    var best = res && res.best != null ? res.best : st.best;
    var acc = st.answered ? Math.round(st.right * 100 / st.answered) : 0;
    var wrong = (st.log || []).filter(function (x) { return !x.ok; });
    var seen = {};
    wrong = wrong.filter(function (x) { if (seen[x.q.ja]) return false; seen[x.q.ja] = 1; return true; });
    return '<section class="jp-card cp-game cp-game__end" role="status">'
      + '<div class="cp-game__end-head"><h2>Hết giờ!</h2>'
      + (res && res.record ? '<span class="cp-game__record">Kỷ lục mới!</span>' : '<span class="cp-game__best">Kỷ lục: ' + esc(best) + ' điểm</span>') + '</div>'
      + '<ul class="cp-game__tiles">'
      + '<li><strong>' + st.score + '</strong><span>điểm</span></li>'
      + '<li><strong>' + st.right + '/' + st.answered + '</strong><span>câu đúng · ' + acc + '%</span></li>'
      + '<li><strong>' + st.bestStreak + '</strong><span>chuỗi dài nhất</span></li></ul>'
      + (wrong.length ? '<div class="cp-game__review"><p class="cp-game__review-title">Nghe lại các câu chưa đúng (' + wrong.length + ')</p><ul>'
        + wrong.map(function (x) {
          return '<li><button type="button" class="cp-game__review-item" data-pb-play="' + esc(x.q.audioUrl || '') + '" data-pb-text="' + esc(x.q.ja) + '">'
            + '<span class="cp-pb__icon">' + speaker() + '</span><span class="cp-game__review-body"><span lang="ja">' + esc(x.q.ja) + '</span>'
            + '<span class="cp-game__fb-ro">' + esc(romajiOf(x.q.reading || x.q.ja)) + '</span>'
            + '<span class="cp-game__review-vi">' + esc(x.q.options[x.q.answer]) + '</span></span></button></li>';
        }).join('') + '</ul></div>'
        : (st.answered ? '<p class="cp-game__perfect">Không sai câu nào. Giỏi lắm!</p>' : ''))
      + '<div class="cp-game__end-acts"><button type="button" class="cp-btn-primary" data-game-again>Chơi lại</button>'
      + '<a class="cp-btn-ghost" href="/learning/comm/phrasebook">Mở sổ câu</a>'
      + '<a class="cp-btn-ghost" href="/learning/comm/path">Về lộ trình</a></div></section>';
  }

  function mountGame(el, deps) {
    if (!el || !root.JpathApi) return;
    var d = deps || {};
    var setTimer = d.setInterval || root.setInterval, clearTimer = d.clearInterval || root.clearInterval;
    var data = null, st = null, timer = null, view = readView();
    function render() {
      if (!st) { el.innerHTML = gameStartHtml(data, view); return; }
      if (st.over) return;
      el.innerHTML = gameQuestionHtml(st, view);
    }
    function current() { return st.questions[st.i]; }
    function say() { var q = current(); if (q) play(q.audioUrl, q.ja); }
    function finish() {
      if (timer) { clearTimer(timer); timer = null; }
      st.over = true;
      el.innerHTML = gameEndHtml(st, null);
      Promise.resolve(root.JpathApi.send('POST', '/api/learning/comm/travel/game', { score: st.score })).then(function (res) {
        if (res) { data.best = res.best; el.innerHTML = gameEndHtml(st, res); }
      }).catch(function () { /* the score stays on screen */ });
    }
    function start() {
      var qs = (data.questions || []).slice();
      st = { questions: qs, i: 0, left: Number(data.seconds) || 60, seconds: Number(data.seconds) || 60, log: [], score: 0, streak: 0, bestStreak: 0, right: 0, answered: 0,
        best: data.best || 0, feedback: null, over: false };
      render();
      say();
      timer = setTimer(function () {
        st.left -= 1;
        var tEl = el.querySelector('[data-game-time]');
        if (tEl) tEl.textContent = String(st.left);
        var bEl = el.querySelector('[data-game-timebar]');
        if (bEl) bEl.style.width = Math.max(0, Math.round(st.left * 100 / st.seconds)) + '%';
        if (st.left <= 0) finish();
      }, 1000);
    }
    function pick(i) {
      var q = current();
      st.answered++;
      var ok = i === q.answer;
      if (ok) {
        st.streak++; st.right++;
        st.bestStreak = Math.max(st.bestStreak, st.streak);
        var p = points(st.streak);
        st.score += p;
        st.feedback = { ok: true, points: p, q: q };
      } else {
        st.streak = 0;
        st.feedback = { ok: false, q: q };
      }
      st.log.push({ ok: ok, q: q });
      st.i = (st.i + 1) % st.questions.length;
      render();
      say();
    }
    if (root.CpathAudio) root.CpathAudio.bind(el, root.CpathAudio.saveToServer);
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t.closest && t.closest('[data-game-start]')) { start(); return; }
      if (t.closest && t.closest('[data-game-again]')) { st = null; load(); return; }
      var vb = t.closest && t.closest('[data-game-view]');
      if (vb) { var k = vb.getAttribute('data-game-view'); view[k] = !view[k]; saveView(view); if (!st || !st.over) render(); return; }
      var rv = t.closest && t.closest('[data-pb-play]');
      if (rv) { play(rv.getAttribute('data-pb-play'), rv.getAttribute('data-pb-text')); return; }
      if (!st || st.over) return;
      if (t.closest && t.closest('[data-game-replay]')) { say(); return; }
      var b = t.closest && t.closest('[data-game-pick]');
      if (b) pick(Number(b.getAttribute('data-game-pick')));
    });
    function load() {
      Promise.resolve(root.JpathApi.get('/api/learning/comm/travel/game')).then(function (x) {
        data = x || {};
        if (root.CpathAudio) root.CpathAudio.fromDisplay(data.display);
        render();
      }).catch(function (e) {
        // QA 27/11 G1: a Free account got 403 and the skeleton stayed; say it is a Premium part
        if (e && (e.status === 403 || e.code === 'CPATH_PREMIUM_REQUIRED')) { el.innerHTML = '<section class="jp-premium-box" role="note"><img src="/images/mascots/kitsu/kitsu-announcing.webp" alt="" width="56" height="56">'
          + '<div><p class="jp-premium-box__title">Phần này dành cho tài khoản Premium</p>'
          + '<p class="jp-premium-box__text">Nâng cấp Premium để luyện phần này, hoặc quay về lộ trình để học tiếp.</p></div>'
          + '<a class="jp-btn jp-btn--primary" href="/premium">Nâng cấp Premium</a> <a class="jp-btn" href="/learning?view=comm">Về lộ trình</a></section>'; return; }
        el.innerHTML = '<p class="jp-alert jp-alert--page" role="alert">' + esc(e && (e.code || e.status) && e.message || 'Chưa tải được trò chơi.') + '</p>';
      });
    }
    load();
  }

  return { exampleOf: exampleOf, points: points, phrasebookHtml: phrasebookHtml, gameQuestionHtml: gameQuestionHtml, gameEndHtml: gameEndHtml,
    mountPhrasebook: mountPhrasebook, mountGame: mountGame };
});
