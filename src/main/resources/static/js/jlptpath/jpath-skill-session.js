/* [JLAS-W0108] jpath-skill-session.js — the skill session screen (Plan 3 Task 20).
 *
 * window.JpathSkillSession = { STEPS, stepsHtml(session), lessonHtml(lesson), lessonResultHtml(r), setHtml(set, ctx),
 *                              practiceHtml(view), doneHtml(session), audioUrl(token, slot), mount() }
 *   - Steps Lý thuyết → Luyện → Chép chính tả (Reading has no dictation step).
 *   - THEORY: GET /skills/lessons/{type}?sessionId= — goal, steps, "Bẫy thường gặp" and "Mẹo" folded, the worked
 *     example (Listening: a player), the comprehension question: a wrong pick shows the explanation and lets the learner
 *     pick again (#36); right → POST /skills/sessions/{id}/advance.
 *   - PRACTICE: GET /checks/{practiceToken} — one block per set: Reading passage (no Look up while it is being
 *     answered), Listening player (Nghe chậm 0,75×, Nghe lại), "Tình huống" card for picture types; each answer is
 *     locked with POST /checks/{token}/answers and shows right/wrong and the explanation; a locked set shows the
 *     translation or the script + translation (Look up on), its new words (Lưu vào sổ tay / Tra từ) and ⚑ Báo sai.
 *     All answered → "Nộp bài" (POST /checks/{token}/submit) → the session moves on.
 *   - DICTATION: window.JpathDictation (Task 21). DONE: the session summary.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathSkillSession = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var STEPS = [['THEORY', 'Lý thuyết'], ['PRACTICE', 'Luyện'], ['DICTATION', 'Chép chính tả']];
  var ORDER = { THEORY: 0, PRACTICE: 1, DICTATION: 2, DONE: 3 };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function attrJson(o) { return esc(JSON.stringify(o)); }

  function list(x) { return Array.isArray(x) ? x : []; }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  function audioUrl(token, slot) {
    return BASE + '/checks/' + encodeURIComponent(token) + '/audio/' + encodeURIComponent(slot);
  }

  /** The step bar; a Reading session has no dictation step. */
  function stepsHtml(s) {
    var steps = s && s.skill === 'READING' ? STEPS.slice(0, 2) : STEPS;
    var at = ORDER[(s && s.step) || 'THEORY'];
    // stepper (jpath-nav.css): a circle per step joined by a line; the list itself is <ol class="jp-stepper">
    return steps.map(function (st, i) {
      var done = i < at;
      var state = done ? 'done' : (i === at ? 'now' : 'todo');
      return '<li class="jp-step jp-step--' + state + (done ? ' is-done' : '') + '"' + (i === at ? ' aria-current="step"' : '')
        + '><span class="jp-step__mark" aria-hidden="true">' + (done ? icon('check') : (i + 1)) + '</span>'
        + '<span class="jp-sr">' + (i + 1) + '. </span><span class="jp-step__body"><span class="jp-step__label">'
        + esc(st[1]) + '</span>' + (done ? '<span class="jp-step__sub">Đã xong</span>' : '') + '</span></li>';
    }).join('');
  }

  function bullets(items) {
    var l = list(items);
    return l.length ? '<ul>' + l.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '';
  }

  function optionsHtml(options, attr, chosen, right) {
    return '<div class="jp-skill-q__options">' + list(options).map(function (o) {
      var cls = right && o.id === right ? ' class="is-right"' : (chosen && o.id === chosen ? ' class="is-wrong"' : '');
      return '<button type="button"' + cls + ' ' + attr + '="' + esc(o.id) + '"' + (right ? ' disabled' : '') + ' lang="ja">'
        + esc(o.label) + '</button>';
    }).join('') + '</div>';
  }

  function calloutList(kind, iconName, title, items) {
    var l = list(items);
    if (!l.length) return '';
    return '<div class="jp-callout jp-callout--' + kind + '"><span class="jp-callout__icon">' + icon(iconName) + '</span><div>'
      + '<h3 class="jp-callout__title">' + esc(title) + '</h3>' + bullets(l) + '</div></div>';
  }

  /** The strategy lesson: goal, numbered steps, traps and tips, a worked example to try first, the comprehension check. */
  function lessonHtml(l) {
    if (!l) return '';
    var ex = l.example || {};
    var source = (ex.passageJa ? '<div class="jp-skill-passage" lang="ja">' + esc(ex.passageJa) + '</div>' : '')
      + (ex.imageDescriptionVi ? '<p class="jp-skill-situation">Tình huống: ' + esc(ex.imageDescriptionVi) + '</p>' : '')
      + (ex.audioUrl ? '<div data-skill-player data-src="' + esc(ex.audioUrl) + '"></div>' : '');
    // the learner answers the sample question first; the walkthrough opens on the answer
    var tryIt = (ex.questionJa ? '<p class="jp-skill-try__label">Thử trả lời trước khi xem cách giải</p>'
      + '<p class="jp-skill-q" lang="ja">' + esc(ex.questionJa) + '</p>'
      + '<div data-example-answer="' + esc(ex.answerOption) + '">' + optionsHtml(ex.options, 'data-example-option', null, null)
      + '</div><p class="jp-skill-explain" data-example-result role="status"></p>' : '')
      + (list(ex.walkthroughVi).length ? '<details class="jp-skill-walk" data-example-walk><summary>Cách giải</summary>'
        + '<ol class="jp-howto jp-howto--small">' + list(ex.walkthroughVi).map(function (x) {
          return '<li>' + esc(x) + '</li>'; }).join('') + '</ol></details>' : '')
      + (ex.scriptJa ? '<details><summary>Lời thoại</summary><p lang="ja">' + esc(ex.scriptJa) + '</p></details>' : '')
      + (ex.translationVi ? '<details><summary>Bản dịch</summary><p>' + esc(ex.translationVi) + '</p></details>' : '');
    var check = l.check || {};
    var steps = list(l.steps).length ? '<h3 class="jp-skill-sub">Các bước</h3><ol class="jp-howto">'
      + list(l.steps).map(function (x) { return '<li>' + esc(String(x).replace(/^Bước\s*\d+\s*[:.]\s*/, '')) + '</li>'; }).join('')
      + '</ol>' : '';
    var aside = calloutList('warn', 'circle-alert', 'Bẫy thường gặp', l.traps) + calloutList('tip', 'lightbulb', 'Mẹo', l.tips);
    return '<section class="jp-skill-block jp-skill-lesson" aria-labelledby="jpLessonTitle">'
      + '<header class="jp-skill-lesson__hero"><h2 id="jpLessonTitle">' + esc(l.titleVi) + '</h2>'
      + (l.goalVi ? '<p class="jp-skill-lesson__goal">' + icon('target') + '<span><strong>Mục tiêu:</strong> ' + esc(l.goalVi)
        + '</span></p>' : '') + '</header>'
      + '<div class="jp-skill-lesson__how">' + '<div>' + steps + '</div>'
      + (aside ? '<div class="jp-skill-lesson__aside">' + aside + '</div>' : '') + '</div>'
      + '<h3 class="jp-skill-sub">Ví dụ giải mẫu</h3><div class="jp-skill-example">'
      + (source ? '<div class="jp-skill-example__source">' + source + '</div>' : '')
      + '<div class="jp-skill-example__try">' + tryIt + '</div></div></section>'
      + '<section class="jp-skill-block jp-skill-check" aria-labelledby="jpLessonCheck"><h2 id="jpLessonCheck">Kiểm tra hiểu bài</h2>'
      + '<p>' + esc(check.questionVi) + '</p>' + optionsHtml(check.options, 'data-lesson-option', null, l.passed ? check.answerOption : null)
      + '<p class="jp-skill-explain" data-lesson-result role="status"></p></section>';
  }

  /** Marks the learner's try on the worked example and opens its walkthrough; true when it handled the click. */
  function tryExample(btn) {
    var box = btn.closest('[data-example-answer]');
    if (!box) return false;
    var right = box.getAttribute('data-example-answer');
    var chosen = btn.getAttribute('data-example-option');
    Array.prototype.forEach.call(box.querySelectorAll('[data-example-option]'), function (b) {
      b.disabled = true;
      var id = b.getAttribute('data-example-option');
      if (right && id === right) b.classList.add('is-right');
      else if (id === chosen) b.classList.add(right ? 'is-wrong' : 'is-picked');
    });
    var block = box.closest('.jp-skill-example__try') || box.parentNode;
    var out = block && block.querySelector('[data-example-result]');
    if (out) {
      // the server sends the example's answer only once the lesson is passed: before that the walkthrough is the key
      out.innerHTML = !right ? 'Bạn đã chọn một đáp án. Đối chiếu với cách giải bên dưới xem có khớp không.'
        : (chosen === right ? '<span class="jp-side-ok">' + icon('circle-check') + ' Chính xác!</span> Xem cách giải để chắc vì sao.'
          : '<span class="jp-side-miss">' + icon('circle-x') + ' Chưa đúng.</span> Đáp án đúng đã được tô xanh, xem cách giải bên dưới.');
    }
    var walk = block && block.querySelector('[data-example-walk]');
    if (walk) walk.open = true;
    return true;
  }

  /** The verdict of a comprehension answer: wrong → explanation and pick again (#36). */
  function lessonResultHtml(r) {
    if (!r) return '';
    return r.correct ? '<span class="jp-side-ok">Đúng rồi.</span> ' + esc(r.explanationVi)
      : '<span class="jp-side-miss">Chưa đúng.</span> ' + esc(r.explanationVi) + ' Hãy chọn lại.';
  }

  function answeredMap(view) {
    var m = {};
    list(view && view.answered).forEach(function (a) { if (a && a.questionId) m[a.questionId] = a; });
    return m;
  }

  /** One set block: what is needed to answer, the questions, and once locked the translation or script. */
  function setHtml(set, ctx) {
    var c = ctx || {};
    var byId = c.questions || {};
    var answered = c.answered || {};
    var listening = set.skill === 'LISTENING';
    var report = attrJson({ targetType: 'SKILL_SET', targetCode: set.code, pageContext: 'SKILL_SESSION' });
    var head = listening
      ? (set.audioSlot != null ? '<div data-skill-player data-src="' + esc(audioUrl(c.token, set.audioSlot)) + '"></div>' : '')
      : '<div class="jp-skill-passage" lang="ja">' + esc(set.passageJa) + '</div>';
    var situation = set.imageDescriptionVi ? '<p class="jp-skill-situation">Tình huống: ' + esc(set.imageDescriptionVi) + '</p>' : '';
    var qs = list(set.questionIds).map(function (id) {
      var q = byId[id];
      if (!q) return '';
      var a = answered[id];
      var verdict = a ? '<p class="jp-skill-explain" role="status">' + (a.correct ? '<span class="jp-side-ok">Đúng.</span> '
        : '<span class="jp-side-miss">Sai.</span> ') + esc(a.explanation) + '</p>' : '';
      return '<div class="jp-skill-q" data-question="' + esc(id) + '"><p lang="ja">'
        + esc(q.prompt) + '</p>' + optionsHtml(q.options, 'data-answer-option', a ? a.value : null, a ? a.correctValue : null)
        + verdict + '</div>';
    }).join('');
    var reveal = '';
    if (set.revealed) {
      var words = list(set.newWords).map(function (w) {
        var action = w.vocabCode
          ? '<button type="button" class="jp-btn" data-save-word="' + esc(w.vocabCode) + '">Lưu vào sổ tay</button>'
          : '<a class="jp-link-btn" href="/search/word/' + encodeURIComponent(w.expression || '') + '">Tra từ</a>';
        return '<li><strong lang="ja">' + esc(w.expression) + '</strong> <span lang="ja">' + esc(w.reading) + '</span> — '
          + esc(w.meaningVi) + ' ' + action + '</li>';
      }).join('');
      reveal = '<div class="jp-skill-reveal">'
        + (listening ? '<h3>Lời thoại</h3>' + list(set.script).map(function (l) {
          return '<p lang="ja"><strong>' + esc(l.speaker) + ':</strong> ' + esc(l.ja) + '</p>';
        }).join('') + (set.scriptVi ? '<details open><summary>Bản dịch</summary><p>' + esc(set.scriptVi) + '</p></details>' : '')
          : (set.passageVi ? '<h3>Bản dịch</h3><p>' + esc(set.passageVi) + '</p>' : ''))
        + (words ? '<h3>Từ mới</h3><ul class="jp-skill-words">' + words + '</ul>' : '') + '</div>';
    }
    return '<section class="jp-skill-block" data-jpath-report="' + report + '"><h2>Bài ' + esc(set.index) + '</h2>'
      + head + situation + qs + reveal + '</section>';
  }

  /** The practice check: every set, then "Nộp bài" once every question is answered. */
  function practiceHtml(view) {
    var v = view || {};
    var questions = {};
    list(v.questions).forEach(function (q) { questions[q.id] = q; });
    var answered = answeredMap(v);
    var sets = list(v.skill && v.skill.sets);
    var all = list(v.questions).length > 0 && list(v.questions).every(function (q) { return !!answered[q.id]; });
    var ctx = { questions: questions, answered: answered, token: v.token };
    return sets.map(function (s) { return setHtml(s, ctx); }).join('')
      + (v.status === 'ISSUED' ? '<button type="button" class="jp-btn jp-btn--primary" data-submit-practice'
        + (all ? '' : ' disabled') + '>Nộp bài</button>' + (all ? '' : ' <span class="jp-muted">Trả lời hết các câu để nộp.</span>') : '');
  }

  function doneHtml(s) {
    return '<section class="jp-skill-block"><h2>Xong buổi ' + esc(s && s.skill === 'LISTENING' ? 'Nghe' : 'Đọc') + ' ' + icon('check') + '</h2>'
      + '<p>' + esc((s && s.setCount) || 0) + ' bài · ' + esc((s && s.questionCount) || 0) + ' câu'
      + (s && s.dictationCount ? ' · ' + esc(s.dictationCount) + ' câu chép' : '') + '</p>'
      + doneActions(s) + '</section>';
  }

  /** After a session: a skill day goes on in the Đọc · Nghe tab (the other skill, then today's summary). */
  function doneActions(s) {
    if (s && s.kind === 'DAY') {
      return '<div class="jp-flow"><div class="jp-flow__lead"><a class="jp-btn jp-btn--ghost" href="/learning?saved=1">'
        + 'Xem tổng kết hôm nay</a></div><div class="jp-flow__tail"><a class="jp-btn jp-btn--primary" href="/learning?tab=skills">'
        + 'Làm tiếp ngày kỹ năng</a></div></div>';
    }
    return '<div class="jp-flow"><div class="jp-flow__tail"><a class="jp-btn jp-btn--primary" href="/learning?tab=skills">'
      + 'Về Đọc · Nghe</a></div></div>';
  }

  function uuid() {
    var c = root && root.crypto;
    if (c && typeof c.randomUUID === 'function') return c.randomUUID();
    return 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-skill-session]');
    if (mounted || !main || !root.JpathApi) return;
    mounted = true;
    var api = root.JpathApi;
    var id = main.getAttribute('data-session-id');
    var status = main.querySelector('[data-role="status"]');
    var body = main.querySelector('[data-role="body"]');
    var steps = main.querySelector('[data-role="steps"]');
    var state = { session: null, lesson: null, view: null, shownAt: Date.now() };

    function fail(e) { status.textContent = (e && e.message) || 'Có lỗi, hãy thử lại.'; }

    function players() {
      body.querySelectorAll('[data-skill-player]').forEach(function (el) {
        if (el.getAttribute('data-bound') || !root.JpathSkillPlayer) return;
        el.setAttribute('data-bound', '1');
        root.JpathSkillPlayer.create(el, { src: el.getAttribute('data-src'), slowRate: 0.75, allowReplay: true });
      });
      if (typeof doc.dispatchEvent === 'function' && typeof root.CustomEvent === 'function') {
        doc.dispatchEvent(new root.CustomEvent('jpath:rendered', { detail: { root: body } }));
      }
    }

    function load() {
      return api.get(BASE + '/skills/sessions/' + encodeURIComponent(id)).then(function (s) {
        state.session = s;
        steps.innerHTML = stepsHtml(s);
        main.querySelector('[data-role="title"]').textContent = 'Buổi ' + (s.skill === 'LISTENING' ? 'Nghe' : 'Đọc');
        status.textContent = '';
        if (s.step === 'THEORY') {
          return api.get(BASE + '/skills/lessons/' + encodeURIComponent(s.typeCode) + '?sessionId=' + encodeURIComponent(id))
            .then(function (l) { state.lesson = l; body.innerHTML = lessonHtml(l); players(); });
        }
        if (s.step === 'PRACTICE' && s.practiceToken) return loadPractice(s.practiceToken);
        if (s.step === 'DICTATION' && s.dictationToken && root.JpathDictation) {
          return root.JpathDictation.mount(body, { token: s.dictationToken, api: api, onSubmitted: function () {
            return api.send('POST', BASE + '/skills/sessions/' + encodeURIComponent(id) + '/advance').then(load);
          } });
        }
        if (s.step === 'DONE' || s.done) { body.innerHTML = doneHtml(s); return null; }
        // a step whose check is not ready yet: let the server move it on
        return api.send('POST', BASE + '/skills/sessions/' + encodeURIComponent(id) + '/advance').then(function (n) {
          if (n && n.step !== s.step) return load();
          body.innerHTML = '<p class="jp-muted">Bài luyện của buổi này đang được chuẩn bị.</p>';
          return null;
        });
      }, fail);
    }

    function loadPractice(token) {
      return api.get(BASE + '/checks/' + encodeURIComponent(token)).then(function (v) {
        state.view = v;
        body.innerHTML = practiceHtml(v);
        state.shownAt = Date.now();
        players();
      }, fail);
    }

    body.addEventListener('click', function (ev) {
      var t = ev.target;
      var exampleOpt = t.closest('[data-example-option]');
      if (exampleOpt && !exampleOpt.disabled && tryExample(exampleOpt)) return;
      var lessonOpt = t.closest('[data-lesson-option]');
      if (lessonOpt && !lessonOpt.disabled && state.lesson) {
        var opt = lessonOpt.getAttribute('data-lesson-option');
        api.send('POST', BASE + '/skills/lessons/' + encodeURIComponent(state.lesson.typeCode) + '/check',
          { option: opt, sessionId: Number(id) }).then(function (r) {
          var out = body.querySelector('[data-lesson-result]');
          if (out) out.innerHTML = lessonResultHtml(r);
          if (r && r.correct) {
            return api.send('POST', BASE + '/skills/sessions/' + encodeURIComponent(id) + '/advance').then(load);
          }
          lessonOpt.classList.add('is-wrong');
          return null;
        }, fail);
        return;
      }
      var answer = t.closest('[data-answer-option]');
      if (answer && !answer.disabled && state.view) {
        var q = answer.closest('[data-question]');
        body.querySelectorAll('[data-question="' + q.getAttribute('data-question') + '"] button').forEach(function (b) {
          b.disabled = true;
        });
        api.send('POST', BASE + '/checks/' + encodeURIComponent(state.view.token) + '/answers', {
          questionId: q.getAttribute('data-question'), value: answer.getAttribute('data-answer-option'),
          elapsedMs: Date.now() - state.shownAt }).then(function () {
          state.shownAt = Date.now();
          return loadPractice(state.view.token);
        }, fail);
        return;
      }
      if (t.closest('[data-submit-practice]') && state.view) {
        t.disabled = true;
        api.send('POST', BASE + '/checks/' + encodeURIComponent(state.view.token) + '/submit', { submitKey: uuid() })
          .then(load, fail);
        return;
      }
      var save = t.closest('[data-save-word]');
      if (save) {
        save.disabled = true;
        api.send('POST', BASE + '/items/VOCAB/' + encodeURIComponent(save.getAttribute('data-save-word')) + '/notebook')
          .then(function () { save.textContent = 'Đã lưu'; }, function (e) { save.disabled = false; fail(e); });
      }
    });
    load();
  }

  return { STEPS: STEPS, stepsHtml: stepsHtml, lessonHtml: lessonHtml, tryExample: tryExample, lessonResultHtml: lessonResultHtml, setHtml: setHtml,
    practiceHtml: practiceHtml, doneHtml: doneHtml, audioUrl: audioUrl, mount: mount };
});
