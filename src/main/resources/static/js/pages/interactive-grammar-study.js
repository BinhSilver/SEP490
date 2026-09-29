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
  function list(value) { return Array.isArray(value) ? value : []; }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  function viewModel(data) {
    data = data || {};
    var points = list(data.points).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    return {
      activityId: data.activityId,
      activityState: data.activityState,
      totalPoints: num(data.totalPoints),
      gateSatisfiedCount: num(data.gateSatisfiedCount),
      checkEligible: !!data.checkEligible,
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
  function renderOrdering(pointId, ordering, placedIds) {
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
      (ordering.completed ? '<p class="ilp-practice__feedback is-correct">Đã hoàn thành.</p>' : '') +
      '</div>';
  }

  function renderFill(pointId, fill) {
    if (!fill || !fill.promptWithBlank) return '';
    return '<div class="ilp-practice" data-practice="fill">' +
      '<h3>2. Điền phần còn thiếu</h3>' +
      '<div class="ilp-fill__row"><span class="ilp-fill__sentence">' + esc(fill.promptWithBlank) + '</span>' +
      '<input class="ilp-fill__input" type="text" data-fill-input="' + pointId + '" aria-label="Điền phần còn thiếu" />' +
      '<button type="button" class="ilp-practice__submit" data-fill-check="' + pointId + '">Kiểm tra</button></div>' +
      (fill.completed ? '<p class="ilp-practice__feedback is-correct">Đã hoàn thành.</p>' : '') +
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
      renderOrdering(point.pointId, point.ordering, orderState) +
      renderFill(point.pointId, point.fill) +
      (feedback ? '<p class="ilp-practice__feedback ' + (feedback.correct ? 'is-correct' : 'is-incorrect') + '">' + esc(feedback.explanation) + '</p>' : '') +
      '</section>';
  }

  function renderStudy(v, gate, orderStates, feedbacks) {
    orderStates = orderStates || {};
    feedbacks = feedbacks || {};
    if (!v.points.length) {
      return '<section class="ilp-state"><h1>Chưa có mẫu ngữ pháp</h1><p>Hoạt động này chưa có nội dung.</p></section>';
    }
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkEligible && !gateBlocked;
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    var points = v.points.map(function (p, i) {
      return renderPoint(p, i, v.points.length, orderStates[p.pointId], feedbacks[p.pointId]);
    }).join('');
    return '<section class="ilp-grammar">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">NGỮ PHÁP // ' +
      (v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG LUYỆN TẬP') + '</span><h1>Luyện ngữ pháp</h1>' +
      '<p>Hoàn thành xếp câu và điền từ cho từng mẫu để mở bài kiểm tra nhỏ.</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.gateSatisfiedCount + '/' + v.totalPoints + '</strong><span>mẫu đã xong</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.gateSatisfiedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalPoints + '">' +
      '<span style="width:' + (v.totalPoints ? Math.round(v.gateSatisfiedCount / v.totalPoints * 100) : 0) + '%"></span></div>' +
      points + gateNote +
      '<button class="ilp-check" type="button" data-check' + (canCheck ? '' : ' disabled') + '>Bắt đầu kiểm tra <span aria-hidden="true">→</span></button>' +
      '</section>';
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
    return '<section class="ilp-check">' +
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

  function renderResult(result) {
    var passed = !!result.passed;
    var wrong = list(result.review).filter(function (r) { return r.correct === false; });
    var cooldown = result.activityState === 'COOLDOWN';
    return '<section class="ilp-result">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">KẾT QUẢ</span><h1>' + (passed ? 'Đã đạt' : 'Chưa đạt') + '</h1></div>' +
      '<div class="ilp-score' + (passed ? ' is-pass' : ' is-fail') + '"><strong>' + num(result.scorePercent) + '%</strong></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng · ngưỡng ' + num(result.passThresholdPercent) + '%</p>' +
      (passed ? '<p class="ilp-result__pass">Tuyệt vời! Bạn đã hoàn thành phần này.</p>' :
        '<div class="ilp-result__fail"><p>Lần thử ' + num(result.attemptsUsed) + '/3 · còn ' + num(result.attemptsRemaining) + ' lượt.</p>' +
        (result.reviewRequired ? '<p>Hãy ôn lại các mẫu đã sai trước khi thử lại.</p>' : '') +
        (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
        '</div>') +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions">' +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  function renderPassed() {
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>Phần Ngữ pháp đã đạt</h1></div></header>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
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
    var orderState = {};
    var feedback = {};

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
        var study = await api('/study');
        var gate = null;
        try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
        if (gate && gate.passed) { node.innerHTML = renderPassed(); }
        else { node.innerHTML = renderStudy(viewModel(study), gate, orderState, feedback); }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doCheck() {
      try { node.innerHTML = renderCheck(await api('/micro-check', { method: 'POST' })); }
      catch (e) { node.innerHTML = error(e.message); }
    }

    node.addEventListener('click', async function (event) {
      if (event.target.closest('[data-retry]')) return loadStudy();
      if (event.target.closest('[data-check]')) return doCheck();
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var addToken = event.target.closest('[data-order-add]');
      if (addToken) {
        var addId = addToken.getAttribute('data-order-add');
        var addToken2 = Number(addToken.dataset.token);
        orderState[addId] = list(orderState[addId]).concat([addToken2]);
        return loadStudy();
      }
      var removeToken = event.target.closest('[data-order-remove]');
      if (removeToken) {
        var removeId = removeToken.getAttribute('data-order-remove');
        var removeVal = Number(removeToken.dataset.token);
        orderState[removeId] = list(orderState[removeId]).filter(function (t) { return t !== removeVal; });
        return loadStudy();
      }
      var orderCheck = event.target.closest('[data-order-check]');
      if (orderCheck) {
        var pointId = orderCheck.getAttribute('data-order-check');
        try {
          var result = await postJson('/points/' + encodeURIComponent(pointId) + '/practice/ordering',
              { order: list(orderState[pointId]) });
          feedback[pointId] = result;
          if (result.correct) orderState[pointId] = [];
        } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var fillCheck = event.target.closest('[data-fill-check]');
      if (fillCheck) {
        var fillPointId = fillCheck.getAttribute('data-fill-check');
        var input = node.querySelector('[data-fill-input="' + fillPointId + '"]');
        try {
          var fillResult = await postJson('/points/' + encodeURIComponent(fillPointId) + '/practice/fill',
              { answer: input ? input.value : '' });
          feedback[fillPointId] = fillResult;
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
        var result = await postJson('/micro-check/submit', { attemptId: Number(form.dataset.attemptId), answers: answers });
        node.innerHTML = renderResult(result);
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    });

    loadStudy();
  }

  return {
    viewModel: viewModel, renderFormula: renderFormula, renderExample: renderExample, renderOrdering: renderOrdering,
    renderFill: renderFill, renderPoint: renderPoint, renderStudy: renderStudy, renderCheck: renderCheck,
    renderResult: renderResult, mount: mount
  };
});
