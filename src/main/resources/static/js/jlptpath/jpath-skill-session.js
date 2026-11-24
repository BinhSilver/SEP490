/* [JLAS-W0108] jpath-skill-session.js — the skill session screen (Plan 3 Task 20).
 *
 * window.JpathSkillSession = { STEPS, stepsHtml(session), lessonHtml(lesson), lessonResultHtml(r),
 *                              practiceHtml(set, view, index, state), doneHtml(session), audioUrl(token, slot), mount() }
 *   - Steps Lý thuyết → Luyện → Chép chính tả (Reading has no dictation step).
 *   - THEORY: GET /skills/lessons/{type}?sessionId= — goal, steps, "Bẫy thường gặp" and "Mẹo" folded, the worked
 *     example (Listening: a player), the comprehension question: a wrong pick shows the explanation and lets the learner
 *     pick again (#36); right → POST /skills/sessions/{id}/advance.
 *   - PRACTICE (05/11 redesign): one bài (set) at a time, headed "Thứ bảy 07/11 · Đọc · Bài 2/3". The passage (or the
 *     listening dialogue once revealed) is split into sentences; hover/tap a sentence shows its Vietnamese label below.
 *     Exam-style questions (問1…) with four freely-selectable choices and a 4-button bar — Kết quả (grade the picked
 *     answers), Làm lại (clear), Đáp án (lock + show the right answer and explanation), Dịch nghĩa (toggle the whole
 *     passage translation). Kết quả/Đáp án lock the set's answers with POST /checks/{token}/answers; the last bài's
 *     "Hoàn thành buổi" submits POST /checks/{token}/submit → the session moves on. The score is display-only.
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

  function stepLockTitle(code) {
    if (code === 'PRACTICE') return 'Hoàn thành bước Lý thuyết trước để mở phần Luyện.';
    if (code === 'DICTATION') return 'Hoàn thành phần Luyện trước để mở Chép chính tả.';
    return 'Hoàn thành bước hiện tại trước để mở bước này.';
  }

  /** The step bar; a Reading session has no dictation step. */
  function stepsHtml(s) {
    var steps = s && s.skill === 'READING' ? STEPS.slice(0, 2) : STEPS;
    var at = ORDER[(s && s.step) || 'THEORY'];
    // stepper (jpath-nav.css): a circle per step joined by a line; the list itself is <ol class="jp-stepper">
    return steps.map(function (st, i) {
      var done = i < at;
      var state = done ? 'done' : (i === at ? 'now' : 'todo');
      var lockMsg = state === 'todo' ? stepLockTitle(st[0]) : '';
      return '<li class="jp-step jp-step--' + state + (done ? ' is-done' : '') + '"' + (i === at ? ' aria-current="step"' : '')
        + (lockMsg ? ' data-step-locked="' + esc(lockMsg) + '" title="' + esc(lockMsg) + '"' : '')
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

  // ---- Practice (Đọc · Nghe) ---------------------------------------------------------------------------

  var SEN_JA = /[。！？]/g;
  var SEN_VI = /[。．！？!?]|\.(?=\s|$)/g;
  var WEEKDAY = ['Chủ nhật', 'Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy'];
  var SPEAKER = { N: 'Người dẫn', M: 'Nam', F: 'Nữ' };

  /** Split Japanese (or Vietnamese) text into sentences, keeping each terminator with its sentence. */
  function splitSentences(text, vi) {
    var s = String(text == null ? '' : text);
    var re = vi ? SEN_VI : SEN_JA;
    re.lastIndex = 0;
    var out = [];
    var last = 0;
    var m;
    while ((m = re.exec(s))) {
      out.push(s.slice(last, m.index + 1));
      last = m.index + 1;
    }
    if (last < s.length) out.push(s.slice(last));
    return out.map(function (x) { return x.trim(); }).filter(function (x) { return x.length > 0; });
  }

  /** Reading: Japanese sentences zipped by index with the Vietnamese translation; missing → no label (never invented). */
  function zipJaVi(ja, vi) {
    var jas = splitSentences(ja, false);
    var vis = vi == null || vi === '' ? [] : splitSentences(vi, true);
    return jas.map(function (x, i) { return { ja: x, vi: vis[i] || '' }; });
  }

  /** Listening: each script line with the matching translation line (scriptVi is one line per speaker). */
  function zipScript(script, scriptVi) {
    var vis = String(scriptVi == null ? '' : scriptVi).split(/\r?\n/).map(function (l) {
      return l.replace(/^[^:：]*[:：]\s*/, '').trim();
    }).filter(function (x) { return x.length > 0; });
    return list(script).map(function (l, i) { return { speaker: l.speaker, ja: l.ja, vi: vis[i] || '' }; });
  }

  /** "Thứ bảy 07/11" for the header: the session's study day (GET /skills/sessions/{id} studyDay), else today. */
  var sessionDay = null;
  function dayLabel() {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(sessionDay || ''));
    if (m) return WEEKDAY[new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay()] + ' ' + m[3] + '/' + m[2];
    var d = new Date();
    return WEEKDAY[d.getDay()] + ' ' + String(d.getDate()).padStart(2, '0') + '/'
      + String(d.getMonth() + 1).padStart(2, '0');
  }

  /** A sentence list: one sentence per line; hover/tap shows its translation right below (light floating label). */
  function senListHtml(lines, withWho, viOn) {
    if (!list(lines).length) return '';
    var spans = list(lines).map(function (l, i) {
      var vi = l.vi ? '<span class="jp-sen__vi">' + esc(l.vi) + '</span>' : '';
      var who = withWho ? '<span class="jp-sen__who">' + esc(SPEAKER[l.speaker] || l.speaker) + '</span>' : '';
      return '<span class="jp-sen' + (l.vi ? ' has-vi' : '') + '" tabindex="0" data-sen="' + i + '">'
        + who + '<span class="jp-sen__ja" lang="ja">' + esc(l.ja) + '</span>' + vi + '</span>';
    });
    var inner = withWho ? spans.join('') : paragraphs(spans);
    return '<div class="jp-skill-passage jp-sen-list' + (withWho ? '' : ' jp-sen-list--prose') + (viOn ? ' is-vi' : '')
      + '">' + inner + '</div>';
  }

  /** F242: a reading passage is shown as paragraphs of PARA_SENTENCES sentences (display only, data unchanged). */
  var PARA_SENTENCES = 3;
  function paragraphs(spans) {
    var out = '';
    for (var k = 0; k < spans.length; k += PARA_SENTENCES) {
      out += '<span class="jp-sen-par">' + spans.slice(k, k + PARA_SENTENCES).join('') + '</span>';
    }
    return out;
  }

  /**
   * Owner 06/11: per-set translations from GET /skills/sets/{code}/translation ({questions_vi, lines_vi | sentences}),
   * cached per set; null while not loaded or when the set has none (the page then falls back to the old split).
   */
  var TR = {};
  /** F106: the check shuffles the options, so the cache (and the request) is per set and check token. */
  function trKey(set, token) { return set.code + '|' + (token || ''); }
  function trUrl(set, token) {
    var url = BASE + '/skills/sets/' + encodeURIComponent(set.code) + '/translation';
    var ids = list(set.questionIds).map(String);
    return token && ids.length
      ? url + '?token=' + encodeURIComponent(String(token)) + '&q=' + ids.map(encodeURIComponent).join(',')
      : url;
  }
  function trOf(set, token) { var k = set && set.code ? trKey(set, token) : null; return k && TR[k] ? TR[k] : null; }

  /** The source to study: the reading passage, or the Listening player + the dialogue once it is revealed. */
  function setSource(set, graded, viOn, token) {
    var tr = trOf(set, token);
    if (set.skill === 'LISTENING') {
      var player = set.audioSlot != null
        ? '<div data-skill-player data-src="' + esc(audioUrl(token, set.audioSlot)) + '"></div>' : '';
      var lines = tr && Array.isArray(tr.lines_vi)
        ? list(set.script).map(function (l, i) { return { speaker: l.speaker, ja: l.ja, vi: tr.lines_vi[i] || '' }; })
        : zipScript(set.script, set.scriptVi);
      return player + (graded && set.revealed ? senListHtml(lines, true, viOn) : '');
    }
    if (tr && Array.isArray(tr.sentences)) {
      return senListHtml(tr.sentences.map(function (x) { return { ja: x.ja, vi: graded && set.revealed ? x.vi : '' }; }), false, viOn);
    }
    return senListHtml(zipJaVi(set.passageJa, graded && set.revealed ? set.passageVi : null), false, viOn);
  }

  function hasPicks(picks) {
    return Object.keys(picks || {}).some(function (k) { return !!picks[k]; });
  }

  /** True when every question of a set is locked (the set is then revealed). */
  function setDone(set, answered) {
    return list(set.questionIds).length > 0 && list(set.questionIds).every(function (id) { return !!answered[id]; });
  }

  /** The 4 buttons under the questions: Kết quả / Làm lại / Đáp án / Dịch nghĩa. */
  function toolbarHtml(set, ctx) {
    var allPicked = list(set.questionIds).length > 0
      && list(set.questionIds).every(function (id) { return !!ctx.answered[id] || !!ctx.picks[id]; });
    var btn = function (data, cls, label, disabled, pressed) {
      return '<button type="button" class="jp-btn' + (cls ? ' ' + cls : '') + '" data-skill-tool="' + data + '"'
        + (disabled ? ' disabled' : '') + (pressed == null ? '' : ' aria-pressed="' + pressed + '"') + '>'
        + label + '</button>';
    };
    return '<div class="jp-skill-toolbar" role="toolbar" aria-label="Thao tác với bài">'
      + btn('result', 'jp-btn--primary', 'Kết quả', ctx.graded || !allPicked)
      + btn('redo', '', 'Làm lại', ctx.graded || !hasPicks(ctx.picks))
      + btn('answer', '', 'Đáp án', !ctx.graded)
      + (ctx.split ? '' : btn('translate', '', 'Dịch nghĩa', !ctx.graded, ctx.viOn))
      + '</div>' + (!ctx.graded ? '<p class="jp-muted">Chọn đáp án trước</p>' : '');
  }

  /** One exam-style question (問1 …) with its four choices. */
  function questionHtml(q, no, ctx) {
    var a = ctx.answered[q.id];
    var locked = ctx.graded || (ctx.practice && !!a);
    var picked = !ctx.graded ? ctx.picks[q.id] : null;
    var missed = !locked && ctx.feedback && ctx.feedback[q.id];
    var opts = list(q.options).map(function (o) {
      var cls = '';
      if (locked && a) {
        if (o.id === a.correctValue) cls = ' class="is-right"';
        else if (o.id === a.value) cls = ' class="is-wrong"';
      } else if (missed && missed.value === o.id) {
        cls = ' class="is-wrong"';
      } else if (picked === o.id) {
        cls = ' class="is-picked"';
      }
      return '<button type="button"' + cls + (locked ? ' disabled' : '') + ' data-answer-option="'
        + esc(o.id) + '"><span lang="ja">' + esc(o.label) + '</span><!--opt' + list(q.options).indexOf(o) + '--></button>';
    }).join('');
    var verdict = '';
    if (locked && a) {
      var pickedOpt = list(q.options).filter(function (o) { return o && o.id === a.value; })[0];
      var rightOpt = list(q.options).filter(function (o) { return o && o.id === a.correctValue; })[0];
      var pickedLbl = pickedOpt ? pickedOpt.label : 'Chưa chọn';
      var rightLbl = rightOpt ? rightOpt.label : '';
      verdict = '<div class="jp-skill-explain" role="status">' + (a.correct
        ? '<span class="jp-side-ok">' + icon('circle-check') + ' Đúng.</span>'
        : '<div class="jp-review__rows">'
          + '<div class="jp-review__row jp-review__row--picked jp-bad"><span class="jp-review__tag">Bạn chọn: </span><span class="jp-review__val" lang="ja">' + esc(pickedLbl) + '</span>' + icon('circle-x') + '</div>'
          + '<div class="jp-review__row jp-review__row--answer jp-ok"><span class="jp-review__tag">Đáp án: </span><span class="jp-review__val" lang="ja">' + esc(rightLbl) + '</span>' + icon('circle-check') + '</div>'
          + '</div>')
        + (ctx.showExplain && a.explanation ? '<p class="jp-skill-explain__note">' + esc(a.explanation) + '</p>' : '')
        + (ctx.token && q.id ? '<div class="jp-ai-slot" data-jpath-ai-explain="'
          + attrJson({ source: 'JPATH_SKILL', checkToken: String(ctx.token), questionId: String(q.id) }) + '"></div>' : '')
        + '</div>';
    }
    var qv = ctx.viOn && ctx.qvi ? ctx.qvi[q.id] : null;
    if (qv && Array.isArray(qv.options_vi)) {
      opts = list(q.options).map(function (o, k) {
        return '<span class="jp-skill-opt-vi">' + esc(qv.options_vi[k] || '') + '</span>';
      }).reduce(function (html, vi, k) {
        return html.replace('<!--opt' + k + '-->', vi);
      }, opts);
    }
    return '<div class="jp-skill-q" data-question="' + esc(q.id) + '">'
      + '<p class="jp-skill-q__no">問' + esc(no) + '</p>'
      + '<p lang="ja">' + esc(q.prompt) + '</p>'
      + (qv && qv.question_vi ? '<p class="jp-skill-q__vi">' + esc(qv.question_vi) + '</p>' : '')
      + '<div class="jp-skill-q__options">' + opts + '</div>'
      + (missed ? '<p class="jp-skill-feedback" role="status">Chưa đúng, hãy chọn lại.</p>' : '')
      + verdict + '</div>';
  }

  /** New words of a revealed set (Lưu vào sổ tay / Tra từ), kept from the previous design. */
  function wordsHtml(set) {
    var words = list(set.newWords).map(function (w) {
      var action = w.vocabCode
        ? '<button type="button" class="jp-btn" data-save-word="' + esc(w.vocabCode) + '">Lưu vào sổ tay</button>'
        : '<a class="jp-link-btn" href="/search/word/' + encodeURIComponent(w.expression || '') + '">Tra từ</a>';
      return '<li><strong lang="ja">' + esc(w.expression) + '</strong> <span lang="ja">' + esc(w.reading)
        + '</span> — ' + esc(w.meaningVi) + ' ' + action + '</li>';
    }).join('');
    return words ? '<div class="jp-skill-reveal"><h3>Từ mới</h3><ul class="jp-skill-words">' + words + '</ul></div>' : '';
  }

  /** The score of a graded set — shown for information only, never counted toward progress. */
  function scoreHtml(set, ctx) {
    if (!ctx.graded) return '';
    var ids = list(set.questionIds);
    var correct = 0;
    ids.forEach(function (id) { if (ctx.answered[id] && ctx.answered[id].correct) correct++; });
    return '<p class="jp-skill-score" role="status">Đúng ' + correct + '/' + ids.length + ' câu · điểm chỉ để xem.</p>';
  }

  /** Once a bài is done: Bài tiếp, or Hoàn thành buổi after the last one. */
  function nextHtml(graded, index, total) {
    if (!graded) return '';
    return '<div class="jp-skill-next"><button type="button" class="jp-btn jp-btn--primary" data-skill-next="1">'
      + (index >= total - 1 ? 'Hoàn thành buổi' : 'Bài tiếp') + '</button></div>';
  }

  /** The whole practice: the context line, then the current bài (source, questions, the 4 buttons). */
  function practiceHtml(set, view, index, st) {
    var total = list(view.skill && view.skill.sets).length;
    var graded = setDone(set, st.answered);
    var report = attrJson({ targetType: 'SKILL_SET', targetCode: set.code, pageContext: 'SKILL_SESSION' });
    var ctx = { token: view.token, questions: st.questions, answered: st.answered, picks: st.picks || {},
      feedback: st.feedback || {},
      graded: graded, practice: view.kind === 'SKILL_PRACTICE', showExplain: !!st.showExplain, viOn: !!st.viOn, qvi: {} };
    var trq = trOf(set, view.token);
    if (trq && Array.isArray(trq.questions_vi)) {
      list(set.questionIds).forEach(function (qid, k) { if (trq.questions_vi[k]) ctx.qvi[qid] = trq.questions_vi[k]; });
    }
    var skillName = set.skill === 'LISTENING' ? 'Nghe' : 'Đọc';
    var head = '<p class="jp-skill-context" role="status">' + esc(dayLabel()) + ' · ' + esc(skillName)
      + ' · Bài ' + (index + 1) + '/' + total + '</p>';
    var qs = list(set.questionIds).map(function (qid, n) {
      var q = ctx.questions[qid];
      return q ? questionHtml(q, n + 1, ctx) : '';
    }).join('');
    var source = setSource(set, graded, ctx.viOn, ctx.token);
    var situation = set.imageDescriptionVi ? '<p class="jp-skill-situation">Tình huống: ' + esc(set.imageDescriptionVi) + '</p>' : '';
    // F242: a reading passage sits on the left (sticky on wide screens) with Furigana + Dịch nghĩa right above it
    ctx.split = set.skill !== 'LISTENING' && source !== '';
    if (!ctx.split) {
      return head + '<section class="jp-skill-block jp-skill-bai" data-jpath-report="' + report + '">'
        + source + situation
        + qs + toolbarHtml(set, ctx) + scoreHtml(set, ctx) + (set.revealed ? wordsHtml(set) : '')
        + nextHtml(graded, index, total) + '</section>';
    }
    var passageBar = '<div class="jp-skill-passage-bar" role="toolbar" aria-label="Công cụ đoạn văn">'
      + '<span class="jp-skill-passage-bar__furi" data-skill-furi-slot></span>'
      + '<button type="button" class="jp-btn" data-skill-tool="translate"' + (graded ? '' : ' disabled')
      + ' aria-pressed="' + (ctx.viOn ? 'true' : 'false') + '"' + (graded ? '' : ' title="Dịch nghĩa mở sau khi xem kết quả"')
      + '>Dịch nghĩa</button></div>';
    return head + '<section class="jp-skill-block jp-skill-bai jp-skill-bai--split" data-jpath-report="' + report + '">'
      + '<div class="jp-skill-bai__text">' + passageBar + source + '</div>'
      + '<div class="jp-skill-bai__qs">' + situation
      + qs + toolbarHtml(set, ctx) + scoreHtml(set, ctx) + (set.revealed ? wordsHtml(set) : '')
      + nextHtml(graded, index, total) + '</div></section>';
  }

  function doneHtml(s) {
    var qCount = (s && s.questionCount) || 0;
    var prac = s && s.practiceCorrect != null
      ? ('đúng ' + esc(s.practiceCorrect) + '/' + esc(qCount) + ' câu')
      : (esc(qCount) + ' câu');
    var dict = '';
    if (s && s.dictationCount) {
      dict = s.dictationCorrect != null
        ? (' · chép đúng ' + esc(s.dictationCorrect) + '/' + esc(s.dictationCount) + ' câu')
        : (' · ' + esc(s.dictationCount) + ' câu chép');
    }
    return '<section class="jp-skill-block"><h2>Xong buổi ' + esc(s && s.skill === 'LISTENING' ? 'Nghe' : 'Đọc') + ' ' + icon('check') + '</h2>'
      + '<p>' + esc((s && s.setCount) || 0) + ' bài · ' + prac + dict + '</p>'
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
    var state = { session: null, lesson: null, view: null, shownAt: Date.now(),
      current: 0, picks: {}, feedback: {}, viOn: false, showExplain: false };

    function fail(e) { status.textContent = (e && (e.code || e.status) && e.message) || 'Có lỗi, hãy thử lại.'; }

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
        if (s && s.studyDay) sessionDay = s.studyDay;
        state.session = s;
        steps.innerHTML = stepsHtml(s);
        main.querySelector('[data-role="title"]').textContent = 'Buổi ' + (s.skill === 'LISTENING' ? 'Nghe' : 'Đọc');
        status.textContent = '';
        if (s.step === 'THEORY') {
          return api.get(BASE + '/skills/lessons/' + encodeURIComponent(s.typeCode) + '?sessionId=' + encodeURIComponent(id))
            .then(function (l) { state.lesson = l; parkFuriToggle(); body.innerHTML = lessonHtml(l); players(); });
        }
        if (s.step === 'PRACTICE' && s.practiceToken) return loadPractice(s.practiceToken);
        if (s.step === 'DICTATION' && s.dictationToken && root.JpathDictation) {
          return root.JpathDictation.mount(body, { token: s.dictationToken, api: api, onSubmitted: function () {
            return api.send('POST', BASE + '/skills/sessions/' + encodeURIComponent(id) + '/advance').then(load);
          } });
        }
        if (s.step === 'DONE' || s.done) { parkFuriToggle(); body.innerHTML = doneHtml(s); return null; }
        // a step whose check is not ready yet: let the server move it on
        return api.send('POST', BASE + '/skills/sessions/' + encodeURIComponent(id) + '/advance').then(function (n) {
          if (n && n.step !== s.step) return load();
          parkFuriToggle();
          body.innerHTML = '<p class="jp-muted">Bài luyện của buổi này đang được chuẩn bị.</p>';
          return null;
        });
      }, fail);
    }

    function practiceState(v) {
      var questions = {};
      list(v.questions).forEach(function (q) { questions[q.id] = q; });
      var answered = answeredMap(v);
      var sets = list(v.skill && v.skill.sets);
      var current = -1;
      sets.forEach(function (s, i) { if (current < 0 && !setDone(s, answered)) current = i; });
      return { sets: sets, questions: questions, answered: answered, token: v.token,
        current: current < 0 ? 0 : current, allDone: current < 0 };
    }

    function renderPractice() {
      var ps = practiceState(state.view);
      var set = ps.sets[state.current];
      if (!set) { return submitPractice(); }
      var trk = set.code ? trKey(set, ps.token) : null;
      if (trk && !(trk in TR)) {
        TR[trk] = null;
        Promise.resolve(api.get(trUrl(set, ps.token))).then(function (t) {
          TR[trk] = t || null;
          if (t) renderPractice();
        }, function () { /* keep the old split */ });
      }
      parkFuriToggle();
      body.innerHTML = practiceHtml(set, state.view, state.current, {
        token: ps.token, questions: ps.questions, answered: ps.answered,
        picks: state.picks, feedback: state.feedback, showExplain: state.showExplain, viOn: state.viOn });
      placeFuriToggle();
      players();
    }

    /**
     * F242: the page's one Furigana toggle (jpath-auto-furigana.js) moves into the bar right above a reading passage,
     * and back to the page head before the body is drawn again (so a redraw never throws it away).
     */
    function furiHolder() {
      var page = body.closest ? body.closest('[data-jpath-auto-furigana]') : null;
      return page && page.querySelector ? page.querySelector('.jp-furi-toggle-wrap') : null;
    }
    function parkFuriToggle() {
      var h = furiHolder();
      if (!h || !body.contains || !body.contains(h)) return;
      var page = body.closest('[data-jpath-auto-furigana]');
      var head = page.querySelector('.jp-page-head') || page;
      head.appendChild(h);
    }
    function placeFuriToggle() {
      var slot = body.querySelector ? body.querySelector('[data-skill-furi-slot]') : null;
      var h = slot ? furiHolder() : null;
      if (h) slot.appendChild(h);
    }

    function refreshToolbar() {
      var ps = practiceState(state.view);
      var set = ps.sets[state.current];
      if (!set) return;
      var allPicked = list(set.questionIds).length > 0
        && list(set.questionIds).every(function (qid) { return !!ps.answered[qid] || !!state.picks[qid]; });
      var resultBtn = body.querySelector('[data-skill-tool="result"]');
      if (resultBtn) resultBtn.disabled = setDone(set, ps.answered) || !allPicked;
      var redoBtn = body.querySelector('[data-skill-tool="redo"]');
      if (redoBtn) redoBtn.disabled = !hasPicks(state.picks);
    }

    function lockSet(values, showExplain) {
      // probe L1: one lock run at a time — repeated "Kết quả" taps while it is sending are ignored
      if (state.locking) return state.locking;
      var ps = practiceState(state.view);
      var set = ps.sets[state.current];
      var lockBtn = body && body.querySelector && body.querySelector('[data-skill-tool="result"]');
      if (lockBtn) lockBtn.disabled = true;
      var seq = Promise.resolve();
      var feedback = {};
      list(set.questionIds).forEach(function (qid) {
        if (ps.answered[qid]) return;
        seq = seq.then(function () {
          return api.send('POST', BASE + '/checks/' + encodeURIComponent(ps.token) + '/answers',
            { questionId: qid, value: values[qid] || '', elapsedMs: Date.now() - state.shownAt })
            .then(function (result) {
              if (result && result.correct === false) feedback[qid] = { value: values[qid] };
            });
        });
      });
      state.locking = seq.then(function () {
        state.showExplain = showExplain;
        state.shownAt = Date.now();
        return api.get(BASE + '/checks/' + encodeURIComponent(ps.token)).then(function (v) {
          state.view = v;
          state.feedback = feedback;
          if (v.kind === 'SKILL_PRACTICE') state.picks = {};
          renderPractice();
          // probe L2: a question locked from another window keeps that answer — say so instead of a silent "wrong"
          var got = answeredMap(v);
          var elsewhere = list(set.questionIds).some(function (qid) {
            return got[qid] && values[qid] && got[qid].value != null && String(got[qid].value) !== String(values[qid]);
          });
          if (elsewhere && status) status.textContent = 'Có câu đã được trả lời ở cửa sổ khác — kết quả theo đáp án đã khóa.';
          return v;
        });
      }, fail).then(function (v) { state.locking = null; refreshToolbar(); return v; },
        function (e) { state.locking = null; refreshToolbar(); fail(e); });
      return state.locking;
    }

    function submitPractice(btn) {
      var nextBtn = btn || (body && body.querySelector && body.querySelector('[data-skill-next]'));
      var prevLabel = nextBtn ? nextBtn.textContent : '';
      if (nextBtn) {
        nextBtn.disabled = true;
        nextBtn.textContent = 'Đang tải…';
      }
      if (status) status.textContent = 'Đang tải bước tiếp theo…';
      return api.send('POST', BASE + '/checks/' + encodeURIComponent(state.view.token) + '/submit', { submitKey: uuid() })
        .then(load, function (e) {
          if (nextBtn) {
            nextBtn.disabled = false;
            if (prevLabel) nextBtn.textContent = prevLabel;
          }
          fail(e);
        });
    }

    function nextSet(btn) {
      var ps = practiceState(state.view);
      if (state.current < ps.sets.length - 1) {
        state.current += 1;
        state.picks = {};
        state.feedback = {};
        state.viOn = false;
        state.showExplain = false;
        renderPractice();
      } else {
        submitPractice(btn);
      }
    }

    function handleTool(which) {
      var ps = practiceState(state.view);
      var set = ps.sets[state.current];
      if (!set) return;
      if (which === 'result') {
        var vals = {};
        list(set.questionIds).forEach(function (qid) { vals[qid] = state.picks[qid] || ''; });
        return lockSet(vals, false);
      }
      if (which === 'redo') {
        state.picks = {};
        state.feedback = {};
        body.querySelectorAll('[data-answer-option]').forEach(function (b) { b.classList.remove('is-picked'); });
        refreshToolbar();
        return;
      }
      if (which === 'answer') {
        if (!setDone(set, ps.answered)) return;
        state.showExplain = true;
        renderPractice();
        return;
      }
      if (which === 'translate') {
        state.viOn = !state.viOn;
        var btn = body.querySelector('[data-skill-tool="translate"]');
        if (btn) btn.setAttribute('aria-pressed', state.viOn ? 'true' : 'false');
        // owner 06/11: the questions and options get their translation too, so the set is drawn again
        renderPractice();
      }
    }

    function loadPractice(token) {
      return api.get(BASE + '/checks/' + encodeURIComponent(token)).then(function (v) {
        state.view = v;
        state.shownAt = Date.now();
        if (v.status !== 'ISSUED') { return load(); }
        var ps = practiceState(v);
        if (ps.allDone) { return submitPractice(); }
        state.current = ps.current;
        state.picks = {};
        state.viOn = false;
        state.showExplain = false;
        renderPractice();
        return v;
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
      var tool = t.closest('[data-skill-tool]');
      if (tool && !tool.disabled && state.view) { handleTool(tool.getAttribute('data-skill-tool')); return; }
      var next = t.closest('[data-skill-next]');
      if (next && !next.disabled && state.view) { nextSet(next); return; }
      var answer = t.closest('[data-answer-option]');
      if (answer && !answer.disabled && state.view) {
        var q = answer.closest('[data-question]');
        var qid = q.getAttribute('data-question');
        var oid = answer.getAttribute('data-answer-option');
        state.picks[qid] = oid;
        delete state.feedback[qid];
        q.querySelectorAll('[data-answer-option]').forEach(function (b) {
          b.classList.remove('is-wrong');
          b.classList.toggle('is-picked', state.picks[qid] === b.getAttribute('data-answer-option'));
        });
        var note = q.querySelector('.jp-skill-feedback');
        if (note) note.remove();
        refreshToolbar();
        return;
      }
      var sen = t.closest('.jp-sen');
      if (sen && !t.closest('ruby') && !t.closest('button') && sen.querySelector('.jp-sen__vi')) {
        sen.classList.toggle('is-open');
        return;
      }
      var save = t.closest('[data-save-word]');
      if (save) {
        save.disabled = true;
        api.send('POST', BASE + '/items/VOCAB/' + encodeURIComponent(save.getAttribute('data-save-word')) + '/notebook')
          .then(function () { save.textContent = 'Đã lưu'; }, function (e) { save.disabled = false; fail(e); });
      }
    });
    if (steps && typeof steps.addEventListener === 'function') {
      steps.addEventListener('click', function (ev) {
        var t = ev.target && typeof ev.target.closest === 'function' ? ev.target.closest('[data-step-locked]') : null;
        if (!t) return;
        var msg = t.getAttribute('data-step-locked');
        if (!msg) return;
        if (status) status.textContent = msg;
        if (root && root.JlasToast && typeof root.JlasToast.show === 'function') root.JlasToast.show(msg, 'info');
      });
    }
    load();
  }

  return { STEPS: STEPS, stepsHtml: stepsHtml, lessonHtml: lessonHtml, tryExample: tryExample,
    lessonResultHtml: lessonResultHtml, practiceHtml: practiceHtml, doneHtml: doneHtml, audioUrl: audioUrl, mount: mount };
});
