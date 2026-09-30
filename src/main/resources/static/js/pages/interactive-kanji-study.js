(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveKanjiStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0193] Task 5 — Kanji study page. Same view-model/render/mount shape as interactive-study.js
  // (Vocabulary, Task 4): the server-owned study + micro-check contract is identical, only the card content
  // and the API base path differ, mirroring the InteractiveStudyEngine split on the backend.

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  function list(value) { return Array.isArray(value) ? value : []; }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  function viewModel(data) {
    data = data || {};
    var cards = list(data.cards).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    var current = null;
    for (var i = 0; i < cards.length; i++) { if (!cards[i].remembered) { current = cards[i]; break; } }
    if (!current && cards.length) current = cards[0];
    return {
      activityId: data.activityId,
      activityState: data.activityState,
      totalCards: num(data.totalCards),
      flippedCount: num(data.flippedCount),
      rememberedCount: num(data.rememberedCount),
      reviewRemaining: num(data.reviewRemaining),
      checkEligible: !!data.checkEligible,
      current: current,
      cards: cards
    };
  }

  function readingChip(label, readings) {
    var values = list(readings);
    if (!values.length) return '';
    return '<span class="ilp-chip">' + esc(label) + ': ' + esc(values.join('、')) + '</span>';
  }

  function cardFace(card) {
    if (!card) return '<div class="ilp-card__empty"><h2>Chưa có thẻ Kanji</h2></div>';
    if (card.flipped) {
      return '<div class="ilp-card__back">' +
        '<span class="ilp-card__meaning">' + esc(card.meaningVi) + '</span>' +
        '<div class="ilp-card__chips">' +
        (card.hanViet ? '<span class="ilp-chip">' + esc(card.hanViet) + '</span>' : '') +
        (card.jlptLevel ? '<span class="ilp-chip">' + esc(card.jlptLevel) + '</span>' : '') +
        (card.strokeCount != null ? '<span class="ilp-chip">' + num(card.strokeCount) + ' nét</span>' : '') +
        readingChip('On', card.onReadings) + readingChip('Kun', card.kunReadings) +
        '</div></div>';
    }
    return '<div class="ilp-card__front">' +
      '<span class="ilp-card__kicker">KANJI</span>' +
      '<h2 class="ilp-card__word ilp-card__word--kanji">' + esc(card.character) + '</h2>' +
      '<span class="ilp-card__hint">Lật thẻ để xem nghĩa</span>' +
      '</div>';
  }

  function renderStudy(v, gate) {
    var card = v.current;
    var progress = v.totalCards ? Math.round(v.rememberedCount / v.totalCards * 100) : 0;
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkEligible && !gateBlocked;
    var actions = '';
    if (card) {
      actions = card.flipped
        ? '<button class="ilp-action ilp-action--forget" type="button" data-remember="' + card.memberId + '" data-value="false">Chưa nhớ</button>' +
          '<button class="ilp-action ilp-action--know" type="button" data-remember="' + card.memberId + '" data-value="true">Đã nhớ</button>'
        : '<button class="ilp-action ilp-action--flip" type="button" data-flip="' + card.memberId + '">Lật thẻ <span aria-hidden="true">↻</span></button>';
    }
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    return '<section class="ilp-study">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">KANJI // ' +
      (v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG ÔN') + '</span><h1>Chữ Hán</h1>' +
      '<p>Lật từng thẻ, tự đánh giá rồi mở khóa bài kiểm tra nhỏ.</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.rememberedCount + '/' + v.totalCards + '</strong><span>đã nhớ</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.rememberedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalCards + '">' +
      '<span style="width:' + progress + '%"></span></div>' +
      '<article class="ilp-card' + (card && card.flipped ? ' is-flipped' : '') + '"' + (card ? ' data-card="' + card.memberId + '" tabindex="0" role="button" aria-label="Thẻ Kanji"' : '') + '>' + cardFace(card) + '</article>' +
      '<div class="ilp-card__actions">' + actions + '</div>' + gateNote +
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
      '<h1>Bài kiểm tra nhỏ</h1><p>Chọn đáp án đúng cho từng câu rồi nộp.</p></div></header>' +
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
        (result.reviewRequired ? '<p>Hãy ôn lại các chữ đã sai trước khi thử lại.</p>' : '') +
        (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
        '</div>') +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions">' +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  // [JLAS-W0108] Task 11 - REVIEW: the server's read-only view, rendered with no flip/remember/check controls.
  function renderReview(v, feedback) {
    return window.InteractiveStudyMode.reviewList('Kanji đã học', v.cards.map(function (card) {
      return { html: '<article class="ilp-card is-flipped">' + cardFace(Object.assign({}, card, { flipped: true })) + '</article>' };
    }), feedback === null ? '' : window.InteractiveStudyMode.feedbackList(feedback));
  }

  function renderPassed(extra) {
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>Phần Kanji đã đạt</h1></div></header>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>' + (extra || '') + '</div></section>';
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở phần Kanji</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p>' +
      '<button data-retry type="button">Thử lại</button></section>';
  }

  function mount() {
    var node = document.getElementById('interactiveKanjiStudyRoot');
    var id = activityId();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/v2/kanji' + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động Kanji.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/kanji';
    // [JLAS-W0108] Task 11 - LEARN / REVIEW / PRACTICE. The server enforces each mode; the page only picks endpoints.
    var StudyMode = window.InteractiveStudyMode;
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var modeApi = StudyMode ? StudyMode.endpoints(mode) : StudyMode;
    var issuePath = (modeApi && modeApi.issue) || '/micro-check';
    var submitPath = (modeApi && modeApi.submit) || '/micro-check/submit';
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions('/learning/v2/kanji', id, ['REVIEW', 'PRACTICE']) : '';

    async function api(path, options) {
      var response = await fetch(base + path, Object.assign({ headers: window.JlasAuth.authHeaders() }, options));
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
      return body.data;
    }

    async function loadStudy() {
      node.setAttribute('aria-busy', 'true');
      try {
        if (mode === 'REVIEW') {
          var feedback = null;
          try { feedback = await api('/review/attempts'); } catch (e) { feedback = null; }
          node.innerHTML = renderReview(viewModel(await api(modeApi.study)), feedback);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'PRACTICE') {
          await doPractice();
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var study = await api('/study');
        var gate = null;
        try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
        if (gate && gate.passed) { node.innerHTML = renderPassed(PASSED_ACTIONS); }
        else { node.innerHTML = renderStudy(viewModel(study), gate); }
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
      if (event.target.closest('[data-practice-again]')) return doPractice();
      if (event.target.closest('[data-check]')) return doCheck();
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var flip = event.target.closest('[data-flip]');
      if (flip) {
        try { node.innerHTML = renderStudy(viewModel(await api('/cards/' + encodeURIComponent(flip.dataset.flip) + '/flip', { method: 'POST' })), null); }
        catch (e) { node.innerHTML = error(e.message); }
        return;
      }
      var remember = event.target.closest('[data-remember]');
      if (remember) {
        var remembered = remember.dataset.value === 'true';
        try { node.innerHTML = renderStudy(viewModel(await api('/cards/' + encodeURIComponent(remember.dataset.remember) + '/remember?remembered=' + remembered, { method: 'POST' })), null); }
        catch (e) { node.innerHTML = error(e.message); }
        return;
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
        var result = await api(submitPath, {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ attemptId: Number(form.dataset.attemptId), answers: answers })
        });
        node.innerHTML = mode === 'PRACTICE' ? StudyMode.practiceResult(result) : renderResult(result);
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    });

    loadStudy();
  }

  return { viewModel: viewModel, renderStudy: renderStudy, renderCheck: renderCheck, renderResult: renderResult, mount: mount };
});
