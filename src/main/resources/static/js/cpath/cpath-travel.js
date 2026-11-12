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
            + (p.note ? '<span class="cp-pb__note">' + esc(p.note) + '</span>' : '') + '</span></button></li>';
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
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Chưa tải được sổ câu.') + '</p>';
    });
  }

  // ------------------------------------------------------------------------------------------------ game

  function gameStartHtml(data) {
    return '<header class="jp-page-head"><h1 class="jp-page-head__title">Trò chơi nghe nhanh</h1>'
      + '<p class="jp-page-head__lede">' + esc(data.seconds || 60) + ' giây: nghe từ hoặc câu du lịch rồi chọn nghĩa. '
      + 'Đúng liên tiếp được cộng thêm điểm chuỗi; sai thì chuỗi về 0.</p></header>'
      + (root.CpathAudio ? root.CpathAudio.sliderHtml({ compact: true }) : '')
      + '<section class="jp-card cp-game__start">'
      + '<p>Kỷ lục của bạn: <strong>' + esc(data.best || 0) + '</strong> điểm</p>'
      + '<button type="button" class="cp-btn-primary" data-game-start' + ((data.questions || []).length ? '' : ' disabled') + '>Bắt đầu</button>'
      + ((data.questions || []).length ? '' : '<p class="jp-side__note">Chưa có âm thanh cho trò chơi.</p>')
      + '</section>';
  }

  function gameQuestionHtml(st) {
    var q = st.questions[st.i];
    return '<section class="jp-card cp-game" aria-live="polite">'
      + '<div class="cp-game__bar"><span class="cp-game__time" data-game-time>' + st.left + ' giây</span>'
      + '<span>Điểm <strong>' + st.score + '</strong></span><span>Chuỗi <strong>' + st.streak + '</strong></span></div>'
      + '<button type="button" class="cp-btn-primary cp-meaning-play" data-game-replay>' + speaker() + ' Nghe lại</button>'
      + (st.feedback ? '<p class="cp-game__fb' + (st.feedback.ok ? ' is-ok' : ' is-wrong') + '" role="status">'
        + (st.feedback.ok ? '+' + st.feedback.points + ' điểm' : 'Đáp án: ' + esc(st.feedback.answer))
        + ' · <span lang="ja">' + esc(st.feedback.ja) + '</span></p>' : '')
      + '<div class="cp-core-quiz__opts" role="group" aria-label="Chọn nghĩa">'
      + q.options.map(function (o, i) {
        return '<button type="button" class="cp-core-opt" data-game-pick="' + i + '">' + esc(o) + '</button>';
      }).join('') + '</div></section>';
  }

  function gameEndHtml(st, res) {
    var best = res && res.best != null ? res.best : st.best;
    return '<section class="jp-card cp-core-result" role="status"><h2>Hết giờ!</h2>'
      + '<p>Bạn được <strong>' + st.score + '</strong> điểm · đúng ' + st.right + '/' + st.answered + ' · chuỗi dài nhất ' + st.bestStreak + '.</p>'
      + (res && res.record ? '<p class="cp-game__record">Kỷ lục mới!</p>' : '<p>Kỷ lục: ' + esc(best) + ' điểm.</p>')
      + '<div class="cp-settings__actions"><button type="button" class="cp-btn-primary" data-game-again>Chơi lại</button>'
      + '<a class="cp-btn-ghost" href="/learning/comm/phrasebook">Mở sổ câu</a>'
      + '<a class="cp-btn-ghost" href="/learning/comm/path">Về lộ trình</a></div></section>';
  }

  function mountGame(el, deps) {
    if (!el || !root.JpathApi) return;
    var d = deps || {};
    var setTimer = d.setInterval || root.setInterval, clearTimer = d.clearInterval || root.clearInterval;
    var data = null, st = null, timer = null;
    function render() {
      if (!st) { el.innerHTML = gameStartHtml(data); return; }
      if (st.over) return;
      el.innerHTML = gameQuestionHtml(st);
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
      st = { questions: qs, i: 0, left: Number(data.seconds) || 60, score: 0, streak: 0, bestStreak: 0, right: 0, answered: 0,
        best: data.best || 0, feedback: null, over: false };
      render();
      say();
      timer = setTimer(function () {
        st.left -= 1;
        var tEl = el.querySelector('[data-game-time]');
        if (tEl) tEl.textContent = st.left + ' giây';
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
        st.feedback = { ok: true, points: p, ja: q.ja };
      } else {
        st.streak = 0;
        st.feedback = { ok: false, answer: q.options[q.answer], ja: q.ja };
      }
      st.i = (st.i + 1) % st.questions.length;
      render();
      say();
    }
    if (root.CpathAudio) root.CpathAudio.bind(el, root.CpathAudio.saveToServer);
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t.closest && t.closest('[data-game-start]')) { start(); return; }
      if (t.closest && t.closest('[data-game-again]')) { st = null; load(); return; }
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
        el.innerHTML = '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Chưa tải được trò chơi.') + '</p>';
      });
    }
    load();
  }

  return { points: points, phrasebookHtml: phrasebookHtml, gameQuestionHtml: gameQuestionHtml, gameEndHtml: gameEndHtml,
    mountPhrasebook: mountPhrasebook, mountGame: mountGame };
});
