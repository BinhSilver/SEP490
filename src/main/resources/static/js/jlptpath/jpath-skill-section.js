/* [JLAS-W0108] jpath-skill-section.js — timed section practice (Plan 3 Task 22, policy #40).
 *
 * window.JpathSkillSection = { FLOOR_PCT, remainingMs(view, loadedAt, now), timerText(ms), setHtml(set, ctx),
 *                              resultHtml(result, tries), mount() }
 *   - Reading: one clock for the whole part, counted from the server's deadlineAt and serverNow (the device clock is
 *     never trusted); at zero the part is handed in by itself.
 *   - Listening: each set plays once (no replay, no slow), then perSetAnswerSeconds × its questions to answer; what
 *     is left unanswered stays wrong.
 *   - Result: the score against the 32% line, passed or not, the last two tries of the skill and a link to review the
 *     wrong questions on the check page.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathSkillSection = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var FLOOR_PCT = 32;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(x) { return Array.isArray(x) ? x : []; }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  function parse(iso) {
    // server-local ISO without zone: both times share it, so the difference is exact
    var t = Date.parse(String(iso || '') + (/[zZ]|[+-]\d\d:?\d\d$/.test(String(iso || '')) ? '' : 'Z'));
    return isNaN(t) ? null : t;
  }

  /** Milliseconds left: (deadlineAt − serverNow) at load, minus the time spent on the page since. Null = no clock. */
  function remainingMs(view, loadedAt, now) {
    var sk = view && view.skill;
    var deadline = parse(sk && sk.deadlineAt);
    var serverNow = parse(sk && sk.serverNow);
    if (deadline == null || serverNow == null) return null;
    return Math.max(0, deadline - serverNow - Math.max(0, now - loadedAt));
  }

  function timerText(ms) {
    var s = Math.ceil(Math.max(0, ms) / 1000);
    return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
  }

  function optionButtons(q, a, closed) {
    return '<div class="jp-skill-q__options">' + list(q.options).map(function (o) {
      var picked = a && a.value === o.id;
      return '<button type="button"' + (picked ? ' class="is-picked" aria-pressed="true"' : '') + ' data-section-option="'
        + esc(o.id) + '"' + (a || closed ? ' disabled' : '') + ' lang="ja">' + esc(o.label) + '</button>';
    }).join('') + '</div>';
  }

  /** One set: passage or the single-listen player, then its questions (answers locked one by one, no verdict yet). */
  function setHtml(set, ctx) {
    var c = ctx || {};
    var listening = set.skill === 'LISTENING';
    var head = listening
      ? (set.audioSlot != null ? '<div data-section-player data-set="' + esc(set.index) + '" data-src="' + esc(BASE + '/checks/'
        + encodeURIComponent(c.token) + '/audio/' + encodeURIComponent(set.audioSlot)) + '"></div>' : '')
      : '<div class="jp-skill-passage" lang="ja">' + esc(set.passageJa) + '</div>';
    var closed = listening && !(c.open || {})[set.index];
    return '<section class="jp-skill-block" data-section-set="' + esc(set.index) + '"><h2>Bài ' + esc(set.index) + '</h2>' + head
      + (set.imageDescriptionVi ? '<p class="jp-skill-situation">Tình huống: ' + esc(set.imageDescriptionVi) + '</p>' : '')
      + list(set.questionIds).map(function (id) {
        var q = (c.questions || {})[id];
        if (!q) return '';
        return '<div class="jp-skill-q" data-question="' + esc(id) + '"><p lang="ja">' + esc(q.prompt) + '</p>'
          + optionButtons(q, (c.answered || {})[id], closed) + '</div>';
      }).join('') + (listening && closed ? '<p class="jp-muted">Bấm Phát để nghe một lần rồi trả lời.</p>' : '')
      + '</section>';
  }

  /** The score against the 32% floor line, passed or not, the last tries. */
  function resultHtml(r, tries) {
    var pct = Math.max(0, Math.min(100, Number(r && r.scorePercent) || 0));
    var passed = !!(r && r.passed);
    var last = list(tries).map(function (t) {
      return (t.passed ? 'Đạt ' : 'Chưa đạt ') + (t.scorePct != null ? Math.round(Number(t.scorePct)) + '%' : '—');
    }).join(', ');
    return '<section class="jp-skill-block"><h2>' + (passed ? 'Đạt ' + icon('check') : 'Chưa đạt') + ' — ' + esc(pct) + '%</h2>'
      + '<div class="jp-section-floor" role="img" aria-label="' + esc(pct + '% so với điểm sàn ' + FLOOR_PCT + '%') + '">'
      + '<div class="jp-section-floor__bar" style="width:' + pct + '%"></div><div class="jp-section-floor__line"></div></div>'
      + '<p class="jp-muted">Điểm sàn ' + FLOOR_PCT + '% · ' + esc((r && r.correct) || 0) + '/' + esc((r && r.total) || 0) + ' câu đúng'
      + (last ? ' · 2 lần gần nhất: ' + esc(last) : '') + '</p>'
      + (r && r.token ? '<a class="jp-link-btn" href="/learning/jlpt/check/' + encodeURIComponent(r.token) + '">Xem lại câu sai</a> ' : '')
      + '<a class="jp-btn jp-btn--primary" href="/learning?tab=skills">Về Đọc · Nghe</a></section>';
  }

  function uuid() {
    var c = root && root.crypto;
    return c && typeof c.randomUUID === 'function' ? c.randomUUID() : 's' + Date.now().toString(36);
  }

  var mounted = false;

  function mount(deps) {
    var d = deps && deps.document ? deps : {};
    var doc = d.document || (root && root.document);
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-skill-section]');
    var api = d.api || root.JpathApi;
    var now = d.now || Date.now;
    var schedule = d.setInterval || root.setInterval;
    if (mounted || !main || !api) return null;
    mounted = true;
    var token = main.getAttribute('data-token');
    var status = main.querySelector('[data-role="status"]');
    var body = main.querySelector('[data-role="body"]');
    var timer = main.querySelector('[data-role="timer"]');
    var state = { view: null, loadedAt: 0, open: {}, submitted: false, shownAt: now(), tick: null };

    function draw() {
      var v = state.view;
      var questions = {};
      list(v.questions).forEach(function (q) { questions[q.id] = q; });
      var answered = {};
      list(v.answered).forEach(function (a) { answered[a.questionId] = a; });
      var ctx = { questions: questions, answered: answered, token: v.token, open: state.open };
      body.innerHTML = list(v.skill && v.skill.sets).map(function (s) { return setHtml(s, ctx); }).join('')
        + '<button type="button" class="jp-btn jp-btn--primary" data-section-submit>Nộp bài</button>';
      body.querySelectorAll('[data-section-player]').forEach(function (el) {
        if (!root.JpathSkillPlayer) return;
        var idx = el.getAttribute('data-set');
        var p = root.JpathSkillPlayer.create(el, { src: el.getAttribute('data-src'), allowReplay: false });
        p.on('ended', function () {
          var n = body.querySelectorAll('[data-section-set="' + idx + '"] [data-question]').length;
          state.open[idx] = true;
          draw();
          var secs = Number(v.skill.perSetAnswerSeconds) || 12;
          (d.setTimeout || root.setTimeout)(function () { state.open[idx] = false; draw(); }, secs * n * 1000);
        });
      });
    }

    function submit() {
      if (state.submitted) return Promise.resolve(null);
      state.submitted = true;
      if (state.tick && root.clearInterval) root.clearInterval(state.tick);
      return api.send('POST', BASE + '/checks/' + encodeURIComponent(token) + '/submit', { submitKey: uuid() }).then(function (r) {
        timer.hidden = true;
        var skill = state.view && list(state.view.skill && state.view.skill.sets)[0];
        return api.get(BASE + '/skills/readiness').then(function (rd) {
          var tries = skill && skill.skill === 'LISTENING' ? rd && rd.lastListening : rd && rd.lastReading;
          body.innerHTML = resultHtml(r, tries);
        }, function () { body.innerHTML = resultHtml(r, []); });
      }, function (e) { state.submitted = false; status.textContent = (e && e.message) || 'Chưa nộp được bài.'; });
    }

    function startClock() {
      var left = remainingMs(state.view, state.loadedAt, now());
      if (left == null) return;
      timer.hidden = false;
      var paint = function () {
        var ms = remainingMs(state.view, state.loadedAt, now());
        timer.textContent = 'Còn ' + timerText(ms);
        if (ms <= 0) submit();
      };
      paint();
      if (schedule) state.tick = schedule(paint, 1000);
    }

    body.addEventListener('click', function (ev) {
      var t = ev.target;
      var opt = t.closest('[data-section-option]');
      if (opt && !opt.disabled) {
        var q = opt.closest('[data-question]');
        var buttons = [];
        q.querySelectorAll('button').forEach(function (b) { buttons.push([b, b.disabled]); b.disabled = true; });
        opt.classList.add('is-picked');
        status.textContent = '';
        api.send('POST', BASE + '/checks/' + encodeURIComponent(token) + '/answers', { questionId: q.getAttribute('data-question'),
          value: opt.getAttribute('data-section-option'), elapsedMs: now() - state.shownAt }).then(function () {
          state.shownAt = now();
          state.view.answered = list(state.view.answered).concat([{ questionId: q.getAttribute('data-question'),
            value: opt.getAttribute('data-section-option') }]);
        }, function (e) {
          // F140: the answer did not reach the server — give the question back (buttons as they were) to pick again
          buttons.forEach(function (x) { x[0].disabled = x[1]; });
          opt.classList.remove('is-picked');
          var m = e && e.code !== 'NETWORK' && e.message ? String(e.message).trim() : '';
          status.textContent = m || 'Chưa gửi được câu trả lời. Hãy chọn lại.';
        });
        return;
      }
      if (t.closest('[data-section-submit]')) submit();
    });

    return api.get(BASE + '/checks/' + encodeURIComponent(token)).then(function (v) {
      state.view = v;
      state.loadedAt = now();
      status.textContent = '';
      if (v.status !== 'ISSUED') { state.submitted = true; return submitDone(v); }
      draw();
      startClock();
      return v;
    }, function (e) { status.textContent = (e && e.message) || 'Không tải được bài.'; });

    function submitDone(v) {
      body.innerHTML = '<p>Bài này đã nộp.</p><a class="jp-link-btn" href="/learning/jlpt/check/' + encodeURIComponent(v.token)
        + '">Xem kết quả</a>';
      return v;
    }
  }

  return { FLOOR_PCT: FLOOR_PCT, remainingMs: remainingMs, timerText: timerText, setHtml: setHtml, resultHtml: resultHtml,
    mount: mount };
});
