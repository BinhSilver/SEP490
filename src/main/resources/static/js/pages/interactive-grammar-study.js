(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveGrammarStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0193] Task 6 — Grammar study page: 1-3 approved points, each requiring ordering + fill-blank
  // practice before the shared micro-check (issue/submit/state/review — identical contract/rendering to
  // Vocabulary's and Kanji's own pages) opens. Server owns eligibility/score/state throughout; this file only
  // renders what the API returns and forwards learner input back to it.

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  // [JLAS-W0125] "Phần x / n" only when a pace change split this skill into ordered parts of the package.
  function partLabel(v) {
    return v.partCount > 1 && v.partIndex > 0
      ? '<p class="ilp-part-indicator">Phần ' + v.partIndex + ' / ' + v.partCount + '</p>' : '';
  }
  function list(value) { return Array.isArray(value) ? value : []; }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  function viewModel(data) {
    data = data || {};
    var points = list(data.points).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    return {
      activityId: data.activityId, partIndex: num(data.partIndex), partCount: num(data.partCount),
      activityState: data.activityState,
      totalPoints: num(data.totalPoints),
      gateSatisfiedCount: num(data.gateSatisfiedCount),
      checkEligible: !!data.checkEligible,
      checkRequired: data.checkRequired !== false,
      points: points
    };
  }

  function roleClass(role) {
    return role === 'PARTICLE' ? ' is-particle' : role === 'AUXILIARY' ? ' is-auxiliary' : role === 'SLOT' ? ' is-slot' : '';
  }

  function renderFormula(components) {
    return '<div class="ilp-formula">' + list(components).map(function (t) {
      return '<span class="ilp-formula__token' + roleClass(t.role) + '"><span class="ilp-formula__text">' + esc(t.text) +
        '</span><span class="ilp-formula__role">' + esc(roleLabel(t.role)) + '</span></span>';
    }).join('') + '</div>';
  }

  function roleLabel(role) {
    return role === 'PARTICLE' ? 'Trợ từ' : role === 'AUXILIARY' ? 'Trợ động từ' : role === 'SLOT' ? 'Thành phần' : 'Từ/cụm từ';
  }

  function renderExample(example) {
    if (!example) return '';
    return '<div class="ilp-example"><p class="ilp-example__ja">' + esc(example.japanese) + '</p>' +
      (example.meaningVi ? '<p class="ilp-example__vi">' + esc(example.meaningVi) + '</p>' : '') + '</div>';
  }

  /** {@code placedIds} is this page's own ephemeral client state for the ordering pool (never sent until
   * the learner submits) — passed in explicitly so this render stays a pure, testable function. */
  function renderOrdering(pointId, ordering, placedIds, feedback, readOnly) {
    if (!ordering || !ordering.words.length) return '';
    placedIds = list(placedIds);
    // Owner 2026-10-21: a finished point (and the read-only review) shows the sentence as it was solved.
    if (ordering.completed && (readOnly || !placedIds.length) && !(feedback && !feedback.correct)) {
      var solved = list(ordering.words).slice().sort(function (a, b) { return a.tokenId - b.tokenId; });
      return '<div class="ilp-practice is-done" data-practice="ordering"><h3>1. Xếp từ thành câu hoàn chỉnh</h3>' +
        '<div class="ilp-order__slots is-solved">' + solved.map(function (w) {
          return '<span class="ilp-order__token is-placed">' + esc(w.text) + '</span>'; }).join('') + '</div>' +
        '<p class="ilp-practice__feedback is-correct">' + esc(feedback && feedback.explanation || 'Đã hoàn thành.') + '</p></div>';
    }
    if (readOnly) return '';
    var byId = {};
    list(ordering.words).forEach(function (w) { byId[w.tokenId] = w.text; });
    var placedSet = {};
    placedIds.forEach(function (id) { placedSet[id] = true; });
    var slots = placedIds.map(function (id) {
      return '<button type="button" class="ilp-order__token is-placed" data-order-remove="' + pointId + '" data-token="' + id + '">' + esc(byId[id]) + '</button>';
    }).join('') || '<span class="ilp-card__hint">Chạm vào từ bên dưới theo đúng thứ tự</span>';
    var pool = list(ordering.words).filter(function (w) { return !placedSet[w.tokenId]; }).map(function (w) {
      return '<button type="button" class="ilp-order__token" data-order-add="' + pointId + '" data-token="' + w.tokenId + '">' + esc(w.text) + '</button>';
    }).join('');
    var ready = placedIds.length === ordering.words.length;
    return '<div class="ilp-practice" data-practice="ordering">' +
      '<h3>1. Xếp từ thành câu hoàn chỉnh</h3>' +
      '<div class="ilp-order__slots">' + slots + '</div>' +
      '<div class="ilp-order__pool">' + pool + '</div>' +
      '<button type="button" class="ilp-practice__submit" data-order-check="' + pointId + '"' + (ready ? '' : ' disabled') + '>Kiểm tra thứ tự</button>' +
      (feedback ? '<p class="ilp-practice__feedback ' + (feedback.correct ? 'is-correct' : 'is-incorrect') + '">' + esc(feedback.explanation) + '</p>' :
        ordering.completed ? '<p class="ilp-practice__feedback is-correct">Đã hoàn thành.</p>' : '') +
      '</div>';
  }

  function renderFill(pointId, fill, feedback, readOnly) {
    if (!fill || !fill.promptWithBlank) return '';
    if (fill.completed && fill.answer != null && !(feedback && !feedback.correct)) {
      var parts = String(fill.promptWithBlank).split('_____');
      return '<div class="ilp-practice is-done" data-practice="fill"><h3>2. Điền phần còn thiếu</h3>' +
        '<p class="ilp-fill__sentence is-solved">' + esc(parts[0]) + '<mark class="ilp-fill__answer">' + esc(fill.answer) + '</mark>' +
        esc(parts.slice(1).join('_____')) + '</p>' +
        '<p class="ilp-practice__feedback is-correct">' + esc(feedback && feedback.explanation || 'Đã hoàn thành.') + '</p></div>';
    }
    if (readOnly) return '';
    return '<div class="ilp-practice" data-practice="fill">' +
      '<h3>2. Điền phần còn thiếu</h3>' +
      '<div class="ilp-fill__row"><span class="ilp-fill__sentence">' + esc(fill.promptWithBlank) + '</span>' +
      '<input class="ilp-fill__input" type="text" data-fill-input="' + pointId + '" aria-label="Điền phần còn thiếu" />' +
      '<button type="button" class="ilp-practice__submit" data-fill-check="' + pointId + '">Kiểm tra</button></div>' +
      (feedback ? '<p class="ilp-practice__feedback ' + (feedback.correct ? 'is-correct' : 'is-incorrect') + '">' + esc(feedback.explanation) + '</p>' :
        fill.completed ? '<p class="ilp-practice__feedback is-correct">Đã hoàn thành.</p>' : '') +
      '</div>';
  }

  /** The longest literal piece of a pattern ("〜をお願いします" → "をお願いします"), to mark it inside the example. */
  function patternCore(pattern) {
    return String(pattern || '').split(/[〜～~〇○◯…]+|\s+|\bN\d*\b/).map(function (piece) {
      return piece.replace(/[。、！？]+$/, '').trim();
    }).reduce(function (best, piece) { return piece.length > best.length ? piece : best; }, '');
  }
  function exampleWithPattern(japanese, pattern) {
    var text = String(japanese || '');
    var core = patternCore(pattern);
    var at = core.length > 1 ? text.indexOf(core) : -1;
    if (at < 0) return esc(text);
    return esc(text.slice(0, at)) + '<mark class="ilp-point__pattern-in-example">' + esc(core) + '</mark>' + esc(text.slice(at + core.length));
  }
  /** A formula that only repeats the heading (one unit equal to the pattern) adds nothing, so it is not shown. */
  function usefulFormula(point) {
    var components = list(point.components);
    return components.length > 1 || components.length === 1 && components[0].text !== point.pattern;
  }
  function fact(label, body) {
    return '<div class="ilp-point__fact"><dt>' + label + '</dt><dd>' + body + '</dd></div>';
  }

  // Owner 2026-10-21: every block says what it is — meaning, structure (only when it adds something), example with the
  // pattern marked, how to use it, a note — then the practice; nothing repeats the heading.
  function renderPoint(point, index, total, orderState, feedback, readOnly, extra) {
    var gate = point.gateSatisfied;
    var example = point.example && point.example.japanese
      ? fact('Ví dụ', '<p class="ilp-point__example-ja" lang="ja">' + exampleWithPattern(point.example.japanese, point.pattern) + '</p>' +
          (point.example.meaningVi ? '<p class="ilp-point__example-vi">' + esc(point.example.meaningVi) + '</p>' : '')) : '';
    var facts = (usefulFormula(point) ? fact('Cấu trúc', renderFormula(point.components)) : '') + example +
      (point.usageNote ? fact('Cách dùng', esc(point.usageNote)) : '') +
      (point.note ? fact('Lưu ý', esc(point.note)) : '');
    return '<section class="ilp-point" data-point="' + point.pointId + '">' +
      '<header class="ilp-point__head"><div><span class="ilp-point__index">Mẫu ' + (index + 1) + '/' + total + '</span>' +
      '<h2 lang="ja">' + esc(point.pattern) + '</h2>' +
      (point.objective ? '<p class="ilp-point__meaning"><span>Nghĩa</span>' + esc(point.objective) + '</p>' : '') + '</div>' +
      '<span class="ilp-point__gate' + (gate ? ' is-done' : '') + '">' +
        (gate ? 'Đã luyện xong' : point.exampleUnderReview ? 'Chưa có bài luyện' : 'Cần luyện tập') + '</span>' +
      '</header>' +
      (facts ? '<dl class="ilp-point__facts">' + facts + '</dl>' : '') +
      '<h3 class="ilp-point__practice-title">Luyện tập</h3>' +
      (point.exampleUnderReview ? '<p class="ilp-gate-note" role="note" data-example-under-review>Câu ví dụ của mẫu này ' +
        'đang được rà soát nên phần xếp câu và điền từ tạm ẩn. Bạn vẫn học được nghĩa, cách dùng và làm bài kiểm tra.</p>' : '') +
      renderOrdering(point.pointId, point.ordering, orderState, feedback && (feedback.ordering || feedback.explanation && feedback), readOnly) +
      renderFill(point.pointId, point.fill, feedback && feedback.fill, readOnly) +
      (readOnly ? '' : renderExtra(point.pointId, extra)) +
      '</section>';
  }

  /** Stable shuffle of tile indexes for an extra item, never the solved order when there are several tiles. */
  function extraOrder(count, seed) {
    var order = [];
    for (var i = 0; i < count; i++) order.push(i);
    order.sort(function (a, b) { return ((a * 7 + seed * 13) % 11) - ((b * 7 + seed * 13) % 11) || a - b; });
    if (count > 1 && order.every(function (value, index) { return value === index; })) order.reverse();
    return order;
  }
  function sameAnswer(a, b) {
    var clean = function (s) { return String(s || '').replace(/\s+/g, '').replace(/[、。！？]+$/, ''); };
    return clean(a) !== '' && clean(a) === clean(b);
  }

  /** Owner 2026-10-21: optional AI-written sentences for the same pattern — ordering and fill per sentence, checked on the
   * page, never scored and never part of completion. {@code extra}: {status, label, message, items:[{placed, order,
   * orderCorrect, fill, fillCorrect}]}. */
  function renderExtra(pointId, extra) {
    extra = extra || {};
    var head = '<div class="ilp-extra__head"><div><h3>Luyện thêm <span>tùy chọn</span></h3>' +
      '<p>3 câu mới dùng cùng mẫu này, do AI soạn để bạn luyện thêm. Không tính điểm, không bắt buộc.</p></div>' +
      (extra.status === 'ready' ? '' : '<button type="button" class="ilp-extra__generate" data-extra-generate="' + pointId + '"' +
        (extra.status === 'loading' ? ' disabled' : '') + '>' + (extra.status === 'loading' ? 'Đang tạo câu…' :
        extra.status === 'error' ? 'Thử tạo lại' : 'Tạo 3 câu luyện thêm') + '</button>') + '</div>';
    if (extra.status === 'error') return '<div class="ilp-extra" data-extra="' + pointId + '">' + head +
      '<p class="ilp-extra__error" role="alert">' + esc(extra.message || 'Chưa tạo được câu luyện thêm lúc này.') + '</p></div>';
    if (extra.status !== 'ready') return '<div class="ilp-extra" data-extra="' + pointId + '">' + head + '</div>';
    var items = list(extra.items).map(function (item, index) {
      var placed = list(item.placed);
      var placedSet = {};
      placed.forEach(function (i) { placedSet[i] = true; });
      var solved = item.orderCorrect === true;
      var slots = solved ? item.parts.map(function (t) { return '<span class="ilp-order__token is-placed">' + esc(t) + '</span>'; }).join('')
        : placed.map(function (i) {
          return '<button type="button" class="ilp-order__token is-placed" data-extra-remove="' + pointId + ':' + index + ':' + i + '">' +
            esc(item.parts[i]) + '</button>'; }).join('') || '<span class="ilp-card__hint">Chạm vào cụm từ theo đúng thứ tự</span>';
      var pool = solved ? '' : item.order.filter(function (i) { return !placedSet[i]; }).map(function (i) {
        return '<button type="button" class="ilp-order__token" data-extra-add="' + pointId + ':' + index + ':' + i + '">' + esc(item.parts[i]) + '</button>';
      }).join('');
      var orderFeedback = item.orderCorrect == null ? '' : '<p class="ilp-practice__feedback ' + (solved ? 'is-correct">Chính xác!' :
        'is-incorrect">Chưa đúng thứ tự, thử lại nhé.') + '</p>';
      var fillDone = item.fillCorrect === true;
      var promptParts = String(item.prompt || '').split('_____');
      var fill = fillDone
        ? '<p class="ilp-fill__sentence is-solved">' + esc(promptParts[0]) + '<mark class="ilp-fill__answer">' + esc(item.answer) + '</mark>' +
          esc(promptParts.slice(1).join('_____')) + '</p>'
        : '<div class="ilp-fill__row"><span class="ilp-fill__sentence">' + esc(item.prompt) + '</span>' +
          '<input class="ilp-fill__input" type="text" data-extra-fill-input="' + pointId + ':' + index + '" value="' + esc(item.fill || '') +
          '" aria-label="Điền phần còn thiếu, câu ' + (index + 1) + '" />' +
          '<button type="button" class="ilp-practice__submit" data-extra-fill-check="' + pointId + ':' + index + '">Kiểm tra</button></div>' +
          (item.fillCorrect === false ? '<p class="ilp-practice__feedback is-incorrect">Chưa đúng. Đáp án: ' + esc(item.answer) + '</p>' : '');
      return '<li class="ilp-extra__item"><p class="ilp-extra__label">Câu ' + (index + 1) + '</p>' +
        '<div class="ilp-practice" data-practice="ordering"><h4>Xếp cụm từ thành câu</h4><div class="ilp-order__slots' + (solved ? ' is-solved' : '') + '">' +
        slots + '</div>' + (pool ? '<div class="ilp-order__pool">' + pool + '</div>' : '') +
        (solved ? '' : '<button type="button" class="ilp-practice__submit" data-extra-order-check="' + pointId + ':' + index + '"' +
          (placed.length === item.parts.length ? '' : ' disabled') + '>Kiểm tra thứ tự</button>') + orderFeedback + '</div>' +
        '<div class="ilp-practice" data-practice="fill"><h4>Điền phần còn thiếu</h4>' + fill + '</div>' +
        (solved && fillDone && item.meaningVi ? '<p class="ilp-extra__vi">' + esc(item.meaningVi) + '</p>' : '') + '</li>';
    }).join('');
    return '<div class="ilp-extra" data-extra="' + pointId + '">' + head + '<ol class="ilp-extra__items">' + items + '</ol>' +
      '<p class="ilp-extra__source">' + esc(extra.label || 'Câu luyện thêm do AI tạo · chưa rà soát') + '</p></div>';
  }

  function renderStudy(v, gate, orderStates, feedbacks, mode, extras) {
    orderStates = orderStates || {};
    feedbacks = feedbacks || {};
    extras = extras || {};
    if (!v.points.length) {
      return '<section class="ilp-state"><h1>Chưa có mẫu ngữ pháp</h1><p>Hoạt động này chưa có nội dung.</p></section>';
    }
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkRequired && v.checkEligible && !gateBlocked;
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    var readOnly = mode === 'REVIEW';
    var communication = v.checkRequired === false;
    var done = mode !== 'REPLAY' && (v.activityState === 'PASSED' || v.activityState === 'COMPLETED');
    var points = v.points.map(function (p, i) {
      return renderPoint(p, i, v.points.length, orderStates[p.pointId], feedbacks[p.pointId], readOnly, extras[p.pointId]);
    }).join('');
    var StudyModeBanner = typeof window !== 'undefined' && window.InteractiveStudyMode ? window.InteractiveStudyMode.banner : null;
    var modeBanner = mode === 'PRACTICE'
      ? '<p class="ilp-mode-banner" role="note" data-mode-banner="PRACTICE"><strong>Chế độ luyện tập</strong> — không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>'
      : mode === 'REPLAY' && StudyModeBanner ? StudyModeBanner('REPLAY') : '';
    // Communication grammar has no quiz: no technical eyebrow and no check copy (owner 2026-10-21).
    return modeBanner + '<section class="ilp-grammar">' +
      '<header class="ilp-study__head"><div>' + (communication ? '' : '<span class="ilp-kicker">NGỮ PHÁP // ' +
      (v.gateSatisfiedCount === v.totalPoints ? 'ĐÃ LUYỆN ĐỦ MẪU' : 'CÓ THỂ KIỂM TRA SỚM') + '</span>') +
      '<h1>' + (communication ? 'Mẫu câu' : 'Luyện ngữ pháp') + (done ? ' <span class="ilp-done-pill">Đã hoàn thành</span>' : '') + '</h1>' + partLabel(v) +
      '<p>' + (communication ? 'Xếp câu rồi điền phần còn thiếu cho từng mẫu.' : 'Luyện xếp câu và điền từ trước khi kiểm tra để nắm chắc từng mẫu.') + '</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.gateSatisfiedCount + '/' + v.totalPoints + '</strong><span>mẫu đã xong</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.gateSatisfiedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalPoints + '">' +
      '<span style="width:' + (v.totalPoints ? Math.round(v.gateSatisfiedCount / v.totalPoints * 100) : 0) + '%"></span></div>' +
      points + (v.checkRequired && !readOnly && !done ? gateNote +
      '<button class="ilp-check" type="button" data-check' + (canCheck ? '' : ' disabled') + '>Bắt đầu kiểm tra <span aria-hidden="true">→</span></button>' : '') +
      '</section>';
  }

  /** Owner 2026-10-23: a finished lesson replayed from scratch — every exercise open again, checked on the page. */
  function freshLesson(v) {
    var points = v.points.map(function (p) {
      return Object.assign({}, p, { gateSatisfied: false,
        ordering: p.ordering ? Object.assign({}, p.ordering, { completed: false }) : p.ordering,
        fill: p.fill ? Object.assign({}, p.fill, { completed: false }) : p.fill });
    });
    return Object.assign({}, v, { points: points, gateSatisfiedCount: 0, checkEligible: true });
  }
  /** Local check of a replayed exercise: the solved order is the token order; the fill answer comes with the review. */
  function replayCheck(point, kind, value) {
    if (!point) return null;
    var correct = kind === 'ordering'
      ? list(value).length === list(point.ordering && point.ordering.words).length && list(value).every(function (token, index) {
          return token === list(point.ordering.words).map(function (w) { return w.tokenId; }).sort(function (a, b) { return a - b; })[index];
        })
      : sameAnswer(value, point.fill && point.fill.answer);
    return { correct: correct, explanation: correct ? 'Chính xác.' : 'Chưa đúng — hãy thử lại.' };
  }
  function replayProgress(v) {
    v.points.forEach(function (p) {
      p.gateSatisfied = (!p.ordering || !list(p.ordering.words).length || p.ordering.completed) &&
        (!p.fill || !p.fill.promptWithBlank || p.fill.completed);
    });
    v.gateSatisfiedCount = v.points.filter(function (p) { return p.gateSatisfied; }).length;
    return v;
  }

  function renderReview(v, practice) {
    var items = v.points.map(function (p) {
      return { html: '<article class="ilp-point"><h2>' + esc(p.pattern) + '</h2>' +
        (p.objective ? '<p>' + esc(p.objective) + '</p>' : '') + renderFormula(p.components) +
        renderExample(p.example) + (p.usageNote ? '<p>' + esc(p.usageNote) + '</p>' : '') +
        (p.note ? '<p class="ilp-note">' + esc(p.note) + '</p>' : '') + '</article>' };
    });
    var StudyMode = typeof window !== 'undefined' ? window.InteractiveStudyMode : null;
    if (practice === true) {
      return (StudyMode ? StudyMode.banner('PRACTICE') :
        '<p class="ilp-mode-banner" role="note" data-mode-banner="PRACTICE"><strong>Chế độ luyện tập</strong> — không ảnh hưởng tiến độ.</p>') +
        '<section class="ilp-grammar"><header class="ilp-study__head"><div><span class="ilp-kicker">NGỮ PHÁP // ÔN BÀI</span>' +
        '<h1>Ôn lại mẫu ngữ pháp</h1><p>Xem mẫu và ví dụ trước khi làm lại bài kiểm tra. Việc ôn này không thay đổi tiến độ.</p>' +
        '</div></header>' + items.map(function (item) { return item.html; }).join('') +
        '<button class="ilp-check" type="button" data-check>Làm lại bài kiểm tra →</button></section>';
    }
    // Owner 2026-10-21: "Xem lại bài giảng" looks like the lesson itself, every exercise already done.
    return (StudyMode ? StudyMode.banner('REVIEW') : '') + renderStudy(v, null, {}, {}, 'REVIEW') +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div>';
  }

  function renderCheck(check) {
    // [JLAS-W0113] Restore previously autosaved selections (questionId -> selectedOption) so a reopen/reload
    // shows the learner's saved choices instead of a blank quiz.
    var selectedByQuestion = {};
    list(check.draftAnswers).forEach(function (d) {
      if (d && d.selectedOption != null) selectedByQuestion[String(d.questionId)] = Number(d.selectedOption);
    });
    var questions = list(check.questions).map(function (q, i) {
      var options = list(q.options).map(function (opt, oi) {
        return '<label class="ilp-option"><input type="radio" name="q-' + q.questionId + '" value="' + (oi + 1) + '"' +
          (selectedByQuestion[String(q.questionId)] === (oi + 1) ? ' checked' : '') + '>' +
          '<span class="ilp-option__mark">' + String.fromCharCode(65 + oi) + '</span><span>' + esc(opt) + '</span></label>';
      }).join('');
      return '<fieldset class="ilp-question"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText) + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    return '<section class="ilp-check no-inline-dictionary">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">MICRO CHECK // ' + (num(check.batchIndex) + 1) + '/' + num(check.batchCount) + '</span>' +
      '<h1>Kiểm tra nhanh Ngữ pháp</h1><p>Chọn đáp án đúng cho từng câu rồi nộp.</p></div></header>' +
      '<form class="ilp-check__form" data-submit data-attempt-id="' + esc(check.attemptId) + '">' + questions +
      '<span class="ilp-check__draft" data-draft-status role="status" aria-live="polite"></span>' +
      '<button class="ilp-check__submit" type="submit">Nộp bài</button></form>' +
      '</section>';
  }

  function formatCooldown(value) {
    var d = value ? new Date(value) : null;
    if (!d || Number.isNaN(d.getTime())) return '';
    return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
  }

  function renderResult(result, activityId) {
    var passed = !!result.passed;
    // [JLAS-W0193] §2.23 — a passed batch of a multi-batch activity must hand the learner the exact next
    // batch, exactly like Vocabulary/Kanji/Foundation do; Grammar was the only page missing this CTA.
    var nextBatch = passed && result.activityState === 'CHECK_READY';
    var wrong = list(result.review).filter(function (r) { return r.correct === false; });
    var cooldown = result.activityState === 'COOLDOWN';
    return '<section class="ilp-result">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">KẾT QUẢ</span><h1>' + (passed ? 'Đã đạt' : 'Chưa đạt') + '</h1></div>' +
      '<div class="ilp-score' + (passed ? ' is-pass' : ' is-fail') + '"><strong>' + num(result.scorePercent) + '%</strong></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng · ngưỡng ' + num(result.passThresholdPercent) + '%</p>' +
      (passed ? '<p class="ilp-result__pass">' + (nextBatch
        ? 'Đã đạt phần kiểm tra này; còn phần kiểm tra tiếp theo để hoàn tất bài học.'
        : 'Tuyệt vời! Bạn đã hoàn thành phần này.') + '</p>' :
        '<div class="ilp-result__fail"><p>Lần thử ' + num(result.attemptsUsed) + '/3 · còn ' + num(result.attemptsRemaining) + ' lượt.</p>' +
        (result.reviewRequired ? '<p>Hãy ôn lại các mẫu đã sai trước khi thử lại.</p>' : '') +
        (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
        '</div>') +
      (wrong.length ? '<p class="ilp-review-summary">' + wrong.length +
        ' câu cần xem lại. Đáp án, lựa chọn và giải thích của từng câu nằm trong “Các lần làm bài” bên dưới.</p>' : '') +
      '<div class="ilp-result__actions">' +
      (nextBatch ? '<button class="ilp-check" type="button" data-check-next>Làm tiếp phần kiểm tra →</button>' : '') +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      (passed && num(activityId) > 0
        ? '<a class="ilp-check" href="/learning/v2/grammar?activity=' + encodeURIComponent(activityId) +
          '&amp;mode=review">Xem lại bài học</a>'
        : '<button class="ilp-check" type="button" data-back-to-study>Xem lại bài học</button>') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  function renderPassed(extra) {
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>Phần Ngữ pháp đã đạt</h1></div></header>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>' + (extra || '') + '</div></section>';
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở phần Ngữ pháp</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p>' +
      '<button data-retry type="button">Thử lại</button></section>';
  }

  function mount() {
    var node = document.getElementById('interactiveGrammarStudyRoot');
    var id = activityId();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/v2/grammar' + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động Ngữ pháp.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/grammar';
    // [JLAS-W0108] Task 11 - LEARN / REVIEW / PRACTICE. The server enforces each mode; the page only picks endpoints.
    var StudyMode = window.InteractiveStudyMode;
    if (StudyMode && StudyMode.bindNextAction) StudyMode.bindNextAction(node);
    if (StudyMode && StudyMode.bindAttemptHistory) StudyMode.bindAttemptHistory(node);
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var modeApi = StudyMode ? StudyMode.endpoints(mode) : StudyMode;
    var issuePath = (modeApi && modeApi.issue) || '/micro-check';
    var submitPath = (modeApi && modeApi.submit) || '/micro-check/submit';
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions('/learning/v2/grammar', id, ['REVIEW', 'PRACTICE', 'RESULTS']) : '';
    var orderState = {};
    var feedback = {};
    var extraStates = {};
    var lastGate = null;
    /** Repaints from memory (no request) — for the optional extra practice, which is checked on the page. */
    function paint() {
      if (!currentStudy) return;
      var done = (lastGate && lastGate.passed) || currentStudy.activityState === 'PASSED' || currentStudy.activityState === 'COMPLETED';
      node.innerHTML = renderStudy(currentStudy, lastGate, orderState, feedback, mode, extraStates) +
        (done && currentStudy.checkRequired ? '<div class="ilp-result__actions">' + PASSED_ACTIONS + '</div>' : '');
    }
    function extraItem(key) {
      var bits = String(key).split(':');
      var state = extraStates[bits[0]];
      return state && state.items ? { state: state, item: state.items[Number(bits[1])], tile: Number(bits[2]) } : null;
    }
    var currentStudy = null;
    // [JLAS-W0113] Autosave state (same contract as interactive-study.js): drafts only ever autosave in LEARN
    // mode; PRACTICE and REVIEW replays are ephemeral and never write a draft.
    var draftPath = '/micro-check/draft';
    var draftTimer = null;
    var draftRetries = 0;
    var draftDirty = false;
    var draftInFlight = null;
    var draftSeq = 0;

    async function api(path, options) {
      var response = await fetch(base + path, Object.assign({ headers: window.JlasAuth.authHeaders() }, options));
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
      return body.data;
    }

    async function postJson(path, payload) {
      return api(path, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
        body: JSON.stringify(payload)
      });
    }

    // [JLAS-W0113] Autosave a draft of the learner's in-progress selections. The draft is the FULL current
    // checked set (an unselected answer is simply omitted, and the server drops it), so "unselect" is
    // represented naturally. Autosave never grades and never consumes quota/rewards — the server only writes
    // `draft_answers_json` and rejects the write once the attempt is submitted.
    function setDraftStatus(text, isError) {
      var el = node.querySelector('[data-draft-status]');
      if (el) {
        el.textContent = text;
        el.classList.toggle('is-error', !!isError);
      }
    }

    function collectDraft(form) {
      return Array.prototype.slice.call(form.querySelectorAll('input[type="radio"]:checked')).map(function (input) {
        return { questionId: Number(input.name.slice(2)), selectedOption: Number(input.value) };
      });
    }

    async function flushDraft() {
      var form = node.querySelector('.ilp-check__form');
      if (!form) return true; // quiz already submitted or navigated away — nothing left to save
      if (draftInFlight) return draftInFlight.then(function (ok) {
        return ok && draftDirty ? flushDraft() : ok;
      }); // serialise writes, including edits made during the first request
      if (!draftDirty) return true;
      var seq = draftSeq;
      var attemptId = Number(form.dataset.attemptId);
      setDraftStatus('Đang lưu…');
      var pending = api(draftPath, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
        body: JSON.stringify({ attemptId: attemptId, answers: collectDraft(form) })
      }).then(function () {
        if (seq !== draftSeq) return true; // keep dirty and save the newer selection next
        draftRetries = 0;
        draftDirty = false;
        setDraftStatus('Đã lưu');
        return true;
      }, function () {
        if (seq !== draftSeq) return true;
        draftRetries += 1;
        if (draftRetries <= 3 && node.querySelector('.ilp-check__form')) {
          setDraftStatus('Lưu thất bại — thử lại…', true);
          draftTimer = setTimeout(flushDraft, 2500);
        } else {
          setDraftStatus('Chưa lưu được nháp. Kiểm tra kết nối.', true);
        }
        return false;
      }).finally(function () { draftInFlight = null; });
      draftInFlight = pending;
      return pending.then(function (ok) { return ok && draftDirty ? flushDraft() : ok; });
    }

    function scheduleDraftSave(form) {
      if (mode !== 'LEARN' || !form) return;
      draftDirty = true;
      draftSeq += 1;
      if (draftTimer) clearTimeout(draftTimer);
      setDraftStatus('Đang lưu…');
      draftTimer = setTimeout(flushDraft, 800);
    }

    var PRACTICE_RESULT_STATE_KEY = 'jlasGrammarPracticeResult';
    var LEARN_RESULT_STATE_KEY = 'jlasGrammarLearnResult';

    function savedPracticeResult() {
      var state = window.history && window.history.state;
      var saved = state && state[PRACTICE_RESULT_STATE_KEY];
      return saved && String(saved.activityId) === String(id) && saved.result ? saved : null;
    }

    function rememberPracticeResult(result, attempts) {
      if (!window.history || typeof window.history.replaceState !== 'function') return;
      var current = window.history.state;
      var state = current && typeof current === 'object' ? Object.assign({}, current) : {};
      state[PRACTICE_RESULT_STATE_KEY] = {
        activityId: Number(id), result: result, attempts: Array.isArray(attempts) ? attempts : []
      };
      window.history.replaceState(state, '', window.location.href);
    }

    function clearPracticeResult() {
      if (!window.history || typeof window.history.replaceState !== 'function') return;
      var current = window.history.state;
      if (!current || !current[PRACTICE_RESULT_STATE_KEY]) return;
      var state = Object.assign({}, current);
      delete state[PRACTICE_RESULT_STATE_KEY];
      window.history.replaceState(state, '', window.location.href);
    }

    function savedLearnResult() {
      var state = window.history && window.history.state;
      var saved = state && state[LEARN_RESULT_STATE_KEY];
      return saved && String(saved.activityId) === String(id) && saved.result && saved.attemptId ? saved : null;
    }

    function rememberLearnResult(result, attemptId) {
      if (!window.history || typeof window.history.replaceState !== 'function' || !(attemptId > 0)) return;
      var current = window.history.state;
      var state = current && typeof current === 'object' ? Object.assign({}, current) : {};
      state[LEARN_RESULT_STATE_KEY] = { activityId: Number(id), attemptId: attemptId, result: result };
      window.history.replaceState(state, '', window.location.href);
    }

    function clearLearnResult() {
      if (!window.history || typeof window.history.replaceState !== 'function') return;
      var current = window.history.state;
      if (!current || !current[LEARN_RESULT_STATE_KEY]) return;
      var state = Object.assign({}, current);
      delete state[LEARN_RESULT_STATE_KEY];
      window.history.replaceState(state, '', window.location.href);
    }

    function issuedKey() { return 'jlasGrammarIssued:' + id; }
    function rememberIssuedAttempt(attemptId) {
      if (mode !== 'LEARN' || !(Number(attemptId) > 0)) return;
      try { window.sessionStorage.setItem(issuedKey(), String(Number(attemptId))); } catch (ignored) { /* storage unavailable */ }
    }
    function issuedAttempt() {
      try { return Number(window.sessionStorage.getItem(issuedKey())) || 0; } catch (ignored) { return 0; }
    }

    async function restoreLearnResult(saved) {
      var attempts;
      try { attempts = await api('/attempt-history'); } catch (historyError) { return false; }
      var learnAttempts = Array.isArray(attempts) ? attempts.filter(function (attempt) { return attempt.mode !== 'PRACTICE'; }) : [];
      var latest = learnAttempts.length ? learnAttempts[learnAttempts.length - 1] : null;
      if (!latest || String(latest.attemptId) !== String(saved.attemptId) || issuedAttempt() > Number(saved.attemptId)) {
        clearLearnResult();
        return false;
      }
      node.innerHTML = renderResult(saved.result, id);
      var resultPanel = node.querySelector('.ilp-result');
      if (resultPanel && attempts.length && StudyMode && StudyMode.attemptHistory)
        resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
      if (saved.result.passed && saved.result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction)
        await StudyMode.showNextAction(node, id);
      return true;
    }

    function renderSavedPracticeResult(saved) {
      node.innerHTML = StudyMode.practiceResult(saved.result, 'Ôn bài rồi kiểm tra lại');
      if (saved.attempts.length && StudyMode && StudyMode.attemptHistory) {
        var resultPanel = node.querySelector('.ilp-result');
        if (resultPanel) resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(saved.attempts));
      }
    }

    async function loadStudy() {
      node.setAttribute('aria-busy', 'true');
      try {
        if (mode === 'RESULTS') {
          await StudyMode.loadSavedResults(node, api, '/learning/v2/grammar', id);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'REVIEW' || mode === 'REPLAY') {
          var reviewData = await api('/review');
          if (StudyMode.isFinished && StudyMode.isFinished(reviewData)) {
            mode = 'REPLAY';
            issuePath = '/practice';
            submitPath = '/practice/submit';
            orderState = {}; feedback = {};
            currentStudy = freshLesson(viewModel(reviewData));
            lastGate = null;
            var savedReplay = savedPracticeResult();
            if (savedReplay) renderSavedPracticeResult(savedReplay);
            else paint();
            node.setAttribute('aria-busy', 'false');
            return;
          }
          node.innerHTML = renderReview(viewModel(reviewData));
          // [JLAS-W0193] §2.23 — a review reopened mid-check must still offer the remaining batch.
          var gate = null;
          if (reviewData && reviewData.checkRequired !== false) {
            try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
          }
          if (gate && !gate.passed) node.insertAdjacentHTML('beforeend', StudyMode.reviewGateActions(gate, '/learning/v2/grammar', id));
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'PRACTICE') {
          var savedPractice = savedPracticeResult();
          if (savedPractice) {
            renderSavedPracticeResult(savedPractice);
            node.setAttribute('aria-busy', 'false');
            return;
          }
          var rehearsal = viewModel(await api('/review'));
          node.innerHTML = renderReview(rehearsal, true);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var savedLearn = mode === 'LEARN' ? savedLearnResult() : null;
        if (savedLearn && await restoreLearnResult(savedLearn)) {
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var study = await api('/study');
        var wasDone = currentStudy && (currentStudy.activityState === 'PASSED' || currentStudy.activityState === 'COMPLETED');
        currentStudy = viewModel(study);
        var gate = null;
        if (currentStudy.checkRequired) {
          try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
        }
        if (mode === 'LEARN' && gate && !gate.passed && num(gate.inProgressAttemptId) > 0) {
          await doCheck();
          node.setAttribute('aria-busy', 'false');
          return;
        }
        // Owner 2026-10-21: a finished part stays on its page (solved) and the moment of finishing gets a popup.
        var done = (gate && gate.passed) || currentStudy.activityState === 'PASSED' || currentStudy.activityState === 'COMPLETED';
        lastGate = gate;
        node.innerHTML = renderStudy(currentStudy, gate, orderState, feedback, mode, extraStates) +
          (done && currentStudy.checkRequired ? '<div class="ilp-result__actions">' + PASSED_ACTIONS + '</div>' : '');
        if (done && wasDone === false && StudyMode && StudyMode.celebrate) {
          StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã hoàn thành phần ' +
            (currentStudy.checkRequired ? 'Ngữ pháp' : 'Mẫu câu') + '.', activityId: id });
        }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doPractice() {
      clearPracticeResult();
      try { node.innerHTML = StudyMode.banner('PRACTICE') + renderCheck(await api(issuePath, { method: 'POST' })); }
      catch (e) { node.innerHTML = error(e.message); }
    }

    async function doCheck() {
      clearLearnResult();
      try {
        var check = await api(issuePath, { method: 'POST' });
        rememberIssuedAttempt(check && check.attemptId);
        node.innerHTML = renderCheck(check);
      }
      catch (e) { node.innerHTML = error(e.message); }
    }

    node.addEventListener('click', async function (event) {
      if (StudyMode && StudyMode.isSelectingText && StudyMode.isSelectingText(node)) return; // a lookup selection, not a click
      // ---- Optional AI extra practice: generated on request, checked on the page, never sent as progress ----
      var generate = event.target.closest('[data-extra-generate]');
      if (generate) {
        var genId = generate.getAttribute('data-extra-generate');
        extraStates[genId] = { status: 'loading' };
        paint();
        try {
          var made = await postJson('/points/' + encodeURIComponent(genId) + '/extra-practice', {});
          extraStates[genId] = { status: 'ready', label: made.label, items: list(made.items).map(function (item, index) {
            return Object.assign({}, item, { placed: [], order: extraOrder(list(item.parts).length, Number(genId) + index), fill: '' });
          }) };
        } catch (e) { extraStates[genId] = { status: 'error', message: e.message }; }
        return paint();
      }
      var extraAdd = event.target.closest('[data-extra-add]');
      if (extraAdd) {
        var added = extraItem(extraAdd.getAttribute('data-extra-add'));
        if (added && added.item) { added.item.placed = list(added.item.placed).concat([added.tile]); added.item.orderCorrect = null; }
        return paint();
      }
      var extraRemove = event.target.closest('[data-extra-remove]');
      if (extraRemove) {
        var removed = extraItem(extraRemove.getAttribute('data-extra-remove'));
        if (removed && removed.item) {
          removed.item.placed = list(removed.item.placed).filter(function (t) { return t !== removed.tile; });
          removed.item.orderCorrect = null;
        }
        return paint();
      }
      var extraOrderCheck = event.target.closest('[data-extra-order-check]');
      if (extraOrderCheck) {
        var ordered = extraItem(extraOrderCheck.getAttribute('data-extra-order-check'));
        if (ordered && ordered.item) {
          ordered.item.orderCorrect = ordered.item.placed.every(function (t, i) { return t === i; }) &&
            ordered.item.placed.length === ordered.item.parts.length;
          if (!ordered.item.orderCorrect) ordered.item.placed = [];
        }
        return paint();
      }
      var extraFillCheck = event.target.closest('[data-extra-fill-check]');
      if (extraFillCheck) {
        var key = extraFillCheck.getAttribute('data-extra-fill-check');
        var filled = extraItem(key);
        var box = node.querySelector('[data-extra-fill-input="' + key + '"]');
        if (filled && filled.item) {
          filled.item.fill = box ? box.value : '';
          filled.item.fillCorrect = sameAnswer(filled.item.fill, filled.item.answer);
        }
        return paint();
      }
      if (event.target.closest('[data-retry]')) { clearLearnResult(); return loadStudy(); }
      if (event.target.closest('[data-check-next]')) { clearLearnResult(); return doCheck(); }
      if (event.target.closest('[data-back-to-study]')) { clearLearnResult(); return loadStudy(); }
      if (event.target.closest('[data-practice-review]')) { clearPracticeResult(); return loadStudy(); }
      if (event.target.closest('[data-check]')) {
        if (mode === 'LEARN' && currentStudy && currentStudy.gateSatisfiedCount < currentStudy.totalPoints &&
            !(await window.JlasConfirm('Bạn chưa luyện xong tất cả mẫu ngữ pháp. Bạn muốn kiểm tra ngay hay tiếp tục luyện tập?',
              { confirmText: 'Vẫn kiểm tra', cancelText: 'Tiếp tục luyện' }))) return;
        clearLearnResult();
        return doCheck();
      }
      if (event.target.closest('[data-review]')) {
        clearLearnResult();
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var addToken = event.target.closest('[data-order-add]');
      if (addToken) {
        if (mode === 'PRACTICE') return;
        var addId = addToken.getAttribute('data-order-add');
        var addToken2 = Number(addToken.dataset.token);
        orderState[addId] = list(orderState[addId]).concat([addToken2]);
        return mode === 'REPLAY' ? paint() : loadStudy();
      }
      var removeToken = event.target.closest('[data-order-remove]');
      if (removeToken) {
        if (mode === 'PRACTICE') return;
        var removeId = removeToken.getAttribute('data-order-remove');
        var removeVal = Number(removeToken.dataset.token);
        orderState[removeId] = list(orderState[removeId]).filter(function (t) { return t !== removeVal; });
        return mode === 'REPLAY' ? paint() : loadStudy();
      }
      var orderCheck = event.target.closest('[data-order-check]');
      if (orderCheck) {
        if (mode === 'PRACTICE') return;
        var pointId = orderCheck.getAttribute('data-order-check');
        if (mode === 'REPLAY') {
          // A replayed lesson is checked on the page; nothing is sent as progress.
          var orderPoint = currentStudy.points.filter(function (p) { return String(p.pointId) === String(pointId); })[0];
          var orderResult = replayCheck(orderPoint, 'ordering', orderState[pointId]);
          feedback[pointId] = Object.assign({}, feedback[pointId], { ordering: orderResult });
          if (orderResult && orderResult.correct) { orderPoint.ordering.completed = true; orderState[pointId] = []; }
          else orderState[pointId] = [];
          replayProgress(currentStudy);
          return paint();
        }
        try {
          var result = await postJson('/points/' + encodeURIComponent(pointId) + '/practice/ordering',
              { order: list(orderState[pointId]) });
          feedback[pointId] = Object.assign({}, feedback[pointId], { ordering: result });
          if (result.correct) orderState[pointId] = [];
        } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var fillCheck = event.target.closest('[data-fill-check]');
      if (fillCheck) {
        if (mode === 'PRACTICE') return;
        var fillPointId = fillCheck.getAttribute('data-fill-check');
        var input = node.querySelector('[data-fill-input="' + fillPointId + '"]');
        if (mode === 'REPLAY') {
          var fillPoint = currentStudy.points.filter(function (p) { return String(p.pointId) === String(fillPointId); })[0];
          var fillReplay = replayCheck(fillPoint, 'fill', input ? input.value : '');
          feedback[fillPointId] = Object.assign({}, feedback[fillPointId], { fill: fillReplay });
          if (fillReplay && fillReplay.correct) fillPoint.fill.completed = true;
          replayProgress(currentStudy);
          return paint();
        }
        try {
          var fillResult = await postJson('/points/' + encodeURIComponent(fillPointId) + '/practice/fill',
              { answer: input ? input.value : '' });
          feedback[fillPointId] = Object.assign({}, feedback[fillPointId], { fill: fillResult });
        } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
    });

    node.addEventListener('submit', async function (event) {
      var form = event.target.closest('[data-submit]');
      if (!form) return;
      event.preventDefault();
      // A submitted attempt must never be re-autosaved: stop any pending debounce and cancel in-flight retries.
      if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
      draftDirty = false;
      var answers = Array.prototype.slice.call(form.querySelectorAll('input[type="radio"]:checked')).map(function (input) {
        return { questionId: Number(input.name.slice(2)), selectedOption: Number(input.value) };
      });
      node.setAttribute('aria-busy', 'true');
      try {
        var result = await postJson(submitPath, { attemptId: Number(form.dataset.attemptId), answers: answers });
        if (mode === 'PRACTICE' || mode === 'REPLAY') rememberPracticeResult(result, []);
        else rememberLearnResult(result, Number(form.dataset.attemptId));
        node.innerHTML = mode === 'PRACTICE' || mode === 'REPLAY' ? StudyMode.practiceResult(result, 'Ôn bài rồi kiểm tra lại') : renderResult(result, id);
        if (StudyMode && StudyMode.attemptHistory) {
          try {
            var attempts = await api('/attempt-history');
            var resultPanel = node.querySelector('.ilp-result');
            if (resultPanel && attempts && attempts.length) {
              resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
              if (mode === 'PRACTICE' || mode === 'REPLAY') rememberPracticeResult(result, attempts);
            }
          } catch (historyError) { /* Keep the current quiz result usable if history is unavailable. */ }
        }
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction) {
          await StudyMode.showNextAction(node, id);
          if (StudyMode.celebrate) StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã vượt qua phần Ngữ pháp.', activityId: id });
        }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    });

    node.addEventListener('change', function (event) {
      if (event.target.matches && event.target.matches('.ilp-check__form input[type="radio"]')) {
        scheduleDraftSave(event.target.closest('form'));
      }
    });

    // [JLAS-W0126] App-controlled page-leave protection for the in-progress micro-check draft. The browser
    // cannot reliably block unload, so we own the two navigations the app performs while a dirty draft is on
    // screen — in-page anchors (breadcrumb/shell links) and the shared "Quay lại" button — and flush the draft
    // first, warning only when the flush genuinely fails. A pagehide keepalive POST is a best-effort backstop
    // for tab close/reload (not guaranteed: the server may never see it).
    if (typeof document.addEventListener === 'function') document.addEventListener('click', function (event) {
      if (!draftDirty) return;
      var target = event.target && event.target.closest ? event.target : null;
      if (!target) return;
      var back = target.closest('.lp-page-nav__back');
      var anchor = back ? null : target.closest('a[href]');
      var href = anchor ? anchor.getAttribute('href') : null;
      if (!back && (!href || href.charAt(0) === '#')) return; // in-page jump, not a leave
      event.preventDefault();
      event.stopPropagation();
      flushDraft().then(function (ok) {
        var proceed = ok ? Promise.resolve(true)
          : (window.JlasConfirm ? window.JlasConfirm('Chưa lưu được nháp của bạn. Vẫn rời trang?') : Promise.resolve(true));
        proceed.then(function (yes) {
          if (!yes) return;
          if (back) {
            var nav = window.JlasPageNavigation;
            var action = nav && nav.resolveBackAction
              ? nav.resolveBackAction(document.referrer, window.location.origin, '/learning/path/v2')
              : { type: 'fallback', href: '/learning/path/v2' };
            if (action.type === 'history') window.history.back();
            else window.location.assign(action.href);
          } else {
            window.location.assign(href);
          }
        });
      });
    }, true);

    if (typeof window.addEventListener === 'function') window.addEventListener('pagehide', function () {
      if (!draftDirty) return;
      var form = node.querySelector('.ilp-check__form');
      if (!form) return;
      var attemptId = Number(form.dataset.attemptId);
      // sendBeacon cannot carry the JWT header, so use fetch(keepalive) for the authenticated draft write.
      if (typeof fetch === 'function') {
        fetch(base + draftPath, {
          method: 'POST', keepalive: true,
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ attemptId: attemptId, answers: collectDraft(form) })
        });
      }
    });

    loadStudy();
  }

  return {
    viewModel: viewModel, renderFormula: renderFormula, renderExample: renderExample, renderOrdering: renderOrdering,
    renderFill: renderFill, renderPoint: renderPoint, renderStudy: renderStudy, renderCheck: renderCheck,
    renderExtra: renderExtra, extraOrder: extraOrder, sameAnswer: sameAnswer,
    freshLesson: freshLesson, replayCheck: replayCheck, replayProgress: replayProgress,
    renderReview: renderReview, renderResult: renderResult, mount: mount
  };
});
