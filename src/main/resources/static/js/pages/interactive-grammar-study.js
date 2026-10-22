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
  function renderOrdering(pointId, ordering, placedIds, feedback) {
    if (!ordering || !ordering.words.length) return '';
    placedIds = list(placedIds);
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

  function renderFill(pointId, fill, feedback) {
    if (!fill || !fill.promptWithBlank) return '';
    return '<div class="ilp-practice" data-practice="fill">' +
      '<h3>2. Điền phần còn thiếu</h3>' +
      '<div class="ilp-fill__row"><span class="ilp-fill__sentence">' + esc(fill.promptWithBlank) + '</span>' +
      '<input class="ilp-fill__input" type="text" data-fill-input="' + pointId + '" aria-label="Điền phần còn thiếu" />' +
      '<button type="button" class="ilp-practice__submit" data-fill-check="' + pointId + '">Kiểm tra</button></div>' +
      (feedback ? '<p class="ilp-practice__feedback ' + (feedback.correct ? 'is-correct' : 'is-incorrect') + '">' + esc(feedback.explanation) + '</p>' :
        fill.completed ? '<p class="ilp-practice__feedback is-correct">Đã hoàn thành.</p>' : '') +
      '</div>';
  }

  function renderPoint(point, index, total, orderState, feedback) {
    var gate = point.gateSatisfied;
    return '<section class="ilp-point" data-point="' + point.pointId + '">' +
      '<header class="ilp-point__head"><div><span class="ilp-kicker">MẪU ' + (index + 1) + '/' + total + '</span>' +
      '<h2>' + esc(point.pattern) + '</h2>' +
      (point.objective ? '<p class="ilp-point__objective">' + esc(point.objective) + '</p>' : '') + '</div>' +
      '<span class="ilp-point__gate' + (gate ? ' is-done' : '') + '">' + (gate ? 'Đã luyện xong' : 'Cần luyện tập') + '</span>' +
      '</header>' +
      renderFormula(point.components) +
      (point.usageNote ? '<p class="ilp-point__objective">' + esc(point.usageNote) + '</p>' : '') +
      renderExample(point.example) +
      (point.note ? '<p class="ilp-note">' + esc(point.note) + '</p>' : '') +
      renderOrdering(point.pointId, point.ordering, orderState, feedback && (feedback.ordering || feedback.explanation && feedback)) +
      renderFill(point.pointId, point.fill, feedback && feedback.fill) +
      '</section>';
  }

  function renderStudy(v, gate, orderStates, feedbacks, mode) {
    orderStates = orderStates || {};
    feedbacks = feedbacks || {};
    if (!v.points.length) {
      return '<section class="ilp-state"><h1>Chưa có mẫu ngữ pháp</h1><p>Hoạt động này chưa có nội dung.</p></section>';
    }
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkRequired && v.checkEligible && !gateBlocked;
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    var points = v.points.map(function (p, i) {
      return renderPoint(p, i, v.points.length, orderStates[p.pointId], feedbacks[p.pointId]);
    }).join('');
    var modeBanner = mode === 'PRACTICE'
      ? '<p class="ilp-mode-banner" role="note" data-mode-banner="PRACTICE"><strong>Chế độ luyện tập</strong> — không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>'
      : '';
    return modeBanner + '<section class="ilp-grammar">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">NGỮ PHÁP // ' +
      (v.gateSatisfiedCount === v.totalPoints ? 'ĐÃ LUYỆN ĐỦ MẪU' : 'CÓ THỂ KIỂM TRA SỚM') + '</span><h1>Luyện ngữ pháp</h1>' + partLabel(v) +
      '<p>Luyện xếp câu và điền từ trước khi kiểm tra để nắm chắc từng mẫu.</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.gateSatisfiedCount + '/' + v.totalPoints + '</strong><span>mẫu đã xong</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.gateSatisfiedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalPoints + '">' +
      '<span style="width:' + (v.totalPoints ? Math.round(v.gateSatisfiedCount / v.totalPoints * 100) : 0) + '%"></span></div>' +
      points + (v.checkRequired ? gateNote +
      '<button class="ilp-check" type="button" data-check' + (canCheck ? '' : ' disabled') + '>Bắt đầu kiểm tra <span aria-hidden="true">→</span></button>' :
      '<p class="ilp-result__pass">Hoàn thành các bài luyện để hoàn tất kỹ năng này; tình huống giao tiếp không có bài kiểm tra.</p>') +
      '</section>';
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
    return StudyMode
      ? StudyMode.reviewList('Ngữ pháp đã học', items)
      : '<section class="ilp-result"><h1>Xem lại ngữ pháp</h1>' + items.map(function (x) { return x.html; }).join('') + '</section>';
  }

  function renderCheck(check) {
    var questions = list(check.questions).map(function (q, i) {
      var options = list(q.options).map(function (opt, oi) {
        return '<label class="ilp-option"><input type="radio" name="q-' + q.questionId + '" value="' + (oi + 1) + '">' +
          '<span class="ilp-option__mark">' + String.fromCharCode(65 + oi) + '</span><span>' + esc(opt) + '</span></label>';
      }).join('');
      return '<fieldset class="ilp-question"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText) + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    return '<section class="ilp-check no-inline-dictionary">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">MICRO CHECK // ' + (num(check.batchIndex) + 1) + '/' + num(check.batchCount) + '</span>' +
      '<h1>Kiểm tra nhanh Ngữ pháp</h1><p>Chọn đáp án đúng cho từng câu rồi nộp.</p></div></header>' +
      '<form class="ilp-check__form" data-submit data-attempt-id="' + esc(check.attemptId) + '">' + questions +
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
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions('/learning/v2/grammar', id, ['REVIEW', 'PRACTICE']) : '';
    var orderState = {};
    var feedback = {};
    var currentStudy = null;

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

    async function loadStudy() {
      node.setAttribute('aria-busy', 'true');
      try {
        if (mode === 'RESULTS') {
          await StudyMode.loadSavedResults(node, api, '/learning/v2/grammar', id);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'REVIEW') {
          var reviewData = await api('/review');
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
          var rehearsal = viewModel(await api('/review'));
          node.innerHTML = renderReview(rehearsal, true);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var study = await api('/study');
        currentStudy = viewModel(study);
        var gate = null;
        if (currentStudy.checkRequired) {
          try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
        }
        if ((gate && gate.passed) || currentStudy.activityState === 'PASSED') {
          await StudyMode.restoreCompletedResult(node, api, '/learning/v2/grammar', id, renderPassed(PASSED_ACTIONS));
        }
        else { node.innerHTML = renderStudy(currentStudy, gate, orderState, feedback, mode); }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doPractice() {
      try { node.innerHTML = StudyMode.banner('PRACTICE') + renderCheck(await api(issuePath, { method: 'POST' })); }
      catch (e) { node.innerHTML = error(e.message); }
    }

    async function doCheck() {
      try { node.innerHTML = renderCheck(await api(issuePath, { method: 'POST' })); }
      catch (e) { node.innerHTML = error(e.message); }
    }

    node.addEventListener('click', async function (event) {
      if (event.target.closest('[data-retry]')) return loadStudy();
      if (event.target.closest('[data-check-next]')) return doCheck();
      if (event.target.closest('[data-back-to-study]')) return loadStudy();
      if (event.target.closest('[data-practice-review]')) return loadStudy();
      if (event.target.closest('[data-check]')) {
        if (mode === 'LEARN' && currentStudy && currentStudy.gateSatisfiedCount < currentStudy.totalPoints &&
            !(await window.JlasConfirm('Bạn chưa luyện xong tất cả mẫu ngữ pháp. Bạn muốn kiểm tra ngay hay tiếp tục luyện tập?',
              { confirmText: 'Vẫn kiểm tra', cancelText: 'Tiếp tục luyện' }))) return;
        return doCheck();
      }
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var addToken = event.target.closest('[data-order-add]');
      if (addToken) {
        if (mode === 'PRACTICE') return;
        var addId = addToken.getAttribute('data-order-add');
        var addToken2 = Number(addToken.dataset.token);
        orderState[addId] = list(orderState[addId]).concat([addToken2]);
        return loadStudy();
      }
      var removeToken = event.target.closest('[data-order-remove]');
      if (removeToken) {
        if (mode === 'PRACTICE') return;
        var removeId = removeToken.getAttribute('data-order-remove');
        var removeVal = Number(removeToken.dataset.token);
        orderState[removeId] = list(orderState[removeId]).filter(function (t) { return t !== removeVal; });
        return loadStudy();
      }
      var orderCheck = event.target.closest('[data-order-check]');
      if (orderCheck) {
        if (mode === 'PRACTICE') return;
        var pointId = orderCheck.getAttribute('data-order-check');
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
      var answers = Array.prototype.slice.call(form.querySelectorAll('input[type="radio"]:checked')).map(function (input) {
        return { questionId: Number(input.name.slice(2)), selectedOption: Number(input.value) };
      });
      node.setAttribute('aria-busy', 'true');
      try {
        var result = await postJson(submitPath, { attemptId: Number(form.dataset.attemptId), answers: answers });
        node.innerHTML = mode === 'PRACTICE' ? StudyMode.practiceResult(result, 'Ôn bài rồi kiểm tra lại') : renderResult(result, id);
        if (StudyMode && StudyMode.attemptHistory) {
          try {
            var attempts = await api('/attempt-history');
            var resultPanel = node.querySelector('.ilp-result');
            if (resultPanel && attempts && attempts.length)
              resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
          } catch (historyError) { /* Keep the current quiz result usable if history is unavailable. */ }
        }
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction)
          await StudyMode.showNextAction(node, id);
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    });

    loadStudy();
  }

  return {
    viewModel: viewModel, renderFormula: renderFormula, renderExample: renderExample, renderOrdering: renderOrdering,
    renderFill: renderFill, renderPoint: renderPoint, renderStudy: renderStudy, renderCheck: renderCheck,
    renderReview: renderReview, renderResult: renderResult, mount: mount
  };
});
