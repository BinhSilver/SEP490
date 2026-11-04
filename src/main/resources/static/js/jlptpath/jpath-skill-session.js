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
        + '><span class="jp-step__mark" aria-hidden="true">' + (done ? '✓' : (i + 1)) + '</span>'
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

  /** The strategy lesson with its example and the comprehension question. */
  function lessonHtml(l) {
    if (!l) return '';
    var ex = l.example || {};
    var exampleBody = (ex.passageJa ? '<div class="jp-skill-passage" lang="ja">' + esc(ex.passageJa) + '</div>' : '')
      + (ex.imageDescriptionVi ? '<p class="jp-skill-situation">Tình huống: ' + esc(ex.imageDescriptionVi) + '</p>' : '')
      + (ex.audioUrl ? '<div data-skill-player data-src="' + esc(ex.audioUrl) + '"></div>' : '')
      + (ex.questionJa ? '<p class="jp-skill-q" lang="ja">' + esc(ex.questionJa) + '</p>'
        + optionsHtml(ex.options, 'data-example-option', null, ex.answerOption) : '')
      + (list(ex.walkthroughVi).length ? '<details><summary>Cách giải</summary>' + bullets(ex.walkthroughVi) + '</details>' : '')
      + (ex.scriptJa ? '<details><summary>Lời thoại</summary><p lang="ja">' + esc(ex.scriptJa) + '</p></details>' : '')
      + (ex.translationVi ? '<details><summary>Bản dịch</summary><p>' + esc(ex.translationVi) + '</p></details>' : '');
    var check = l.check || {};
    return '<section class="jp-skill-block" aria-labelledby="jpLessonTitle"><h2 id="jpLessonTitle">' + esc(l.titleVi) + '</h2>'
      + (l.goalVi ? '<p><strong>Mục tiêu:</strong> ' + esc(l.goalVi) + '</p>' : '')
      + (list(l.steps).length ? '<h3>Các bước</h3>' + bullets(l.steps) : '')
      + (list(l.traps).length ? '<details><summary>Bẫy thường gặp</summary>' + bullets(l.traps) + '</details>' : '')
      + (list(l.tips).length ? '<details><summary>Mẹo</summary>' + bullets(l.tips) + '</details>' : '')
      + '<h3>Ví dụ giải mẫu</h3>' + exampleBody + '</section>'
      + '<section class="jp-skill-block" aria-labelledby="jpLessonCheck"><h2 id="jpLessonCheck">Kiểm tra hiểu bài</h2>'
      + '<p>' + esc(check.questionVi) + '</p>' + optionsHtml(check.options, 'data-lesson-option', null, l.passed ? check.answerOption : null)
      + '<p class="jp-skill-explain" data-lesson-result role="status"></p></section>';
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
      : '<div class="jp-skill-passage' + (set.revealed ? '' : ' no-inline-dictionary') + '" lang="ja">' + esc(set.passageJa) + '</div>';
    var situation = set.imageDescriptionVi ? '<p class="jp-skill-situation">Tình huống: ' + esc(set.imageDescriptionVi) + '</p>' : '';
    var qs = list(set.questionIds).map(function (id) {
      var q = byId[id];
      if (!q) return '';
      var a = answered[id];
      var verdict = a ? '<p class="jp-skill-explain" role="status">' + (a.correct ? '<span class="jp-side-ok">Đúng.</span> '
        : '<span class="jp-side-miss">Sai.</span> ') + esc(a.explanation) + '</p>' : '';
      return '<div class="jp-skill-q' + (a ? '' : ' no-inline-dictionary') + '" data-question="' + esc(id) + '"><p lang="ja">'
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
    return '<section class="jp-skill-block"><h2>Xong buổi ' + esc(s && s.skill === 'LISTENING' ? 'Nghe' : 'Đọc') + ' ✓</h2>'
      + '<p>' + esc((s && s.setCount) || 0) + ' bài · ' + esc((s && s.questionCount) || 0) + ' câu'
      + (s && s.dictationCount ? ' · ' + esc(s.dictationCount) + ' câu chép' : '') + '</p>'
      + '<a class="jp-btn jp-btn--primary" href="/learning">Về lộ trình</a></section>';
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

  return { STEPS: STEPS, stepsHtml: stepsHtml, lessonHtml: lessonHtml, lessonResultHtml: lessonResultHtml, setHtml: setHtml,
    practiceHtml: practiceHtml, doneHtml: doneHtml, audioUrl: audioUrl, mount: mount };
});
