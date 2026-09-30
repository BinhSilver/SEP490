(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveComprehensionStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0193] RC Task 5 — Reading/Listening comprehension player. One page serves both skills: every
  // render decision below branches on `activityType` as returned by GET .../comprehension/study (server-owned,
  // from the activity row itself), never on the URL path the page was opened from. Unlike Vocabulary/Kanji/
  // Grammar, the source (passage/audio) IS the basis for the micro-check questions, so it stays visible during
  // the check itself (read-only, no evidence controls) instead of being replaced by a bare question form.
  // Passage text arrives already tag-stripped by JpaComprehensionExerciseCatalog#sanitizePassage; this file
  // still HTML-escapes it before insertion (esc()) and never uses innerHTML with unescaped server text, so no
  // path here can execute markup even if that server-side stripping were ever weakened.

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  function list(value) { return Array.isArray(value) ? value : []; }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }
  function pathKind() {
    if (typeof window === 'undefined' || !window.location) return 'reading';
    return window.location.pathname.indexOf('/listening') !== -1 ? 'listening' : 'reading';
  }

  function viewModel(data) {
    data = data || {};
    var source = data.source || {};
    var check = data.microCheck || {};
    return {
      activityId: data.activityId,
      activityType: data.activityType === 'LISTENING' ? 'LISTENING' : 'READING',
      exerciseCount: num(data.exerciseCount),
      currentExercise: num(data.currentExercise),
      passage: source.passage || null,
      audioUrl: source.audioUrl || null,
      questionCount: list(data.questions).length,
      checkEligible: !!check.checkEligible,
      reviewRequired: !!check.reviewRequired,
      cooldownUntil: check.cooldownUntil || null,
      attemptsUsed: num(check.attemptsUsed),
      attemptsRemaining: num(check.attemptsRemaining),
      passed: !!check.passed
    };
  }

  function formatCooldown(value) {
    var d = value ? new Date(value) : null;
    if (!d || Number.isNaN(d.getTime())) return '';
    return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
  }

  /** {@code interactive=false} suppresses the sentinel/manual-evidence button (used while a micro-check
   * attempt is in progress) - reading/listening the source again mid-check should not itself count as
   * post-failure review evidence. {@code evidenceDone} only affects the manual button's own visual state. */
  function renderSourcePanel(v, evidenceDone, interactive) {
    var showControls = interactive !== false;
    if (v.activityType === 'LISTENING') {
      if (!v.audioUrl) {
        return '<div class="ilp-audio"><p class="ilp-audio__missing">Chưa có file nghe hợp lệ cho bài này.</p></div>';
      }
      return '<div class="ilp-audio"><span class="ilp-audio__icon" aria-hidden="true">🎧</span>' +
        '<audio class="ilp-audio__player" controls preload="none" data-comprehension-audio src="' + esc(v.audioUrl) + '">' +
        'Trình duyệt của bạn không hỗ trợ phát âm thanh.</audio>' +
        '<p class="ilp-audio__hint">Nghe kỹ audio rồi trả lời câu hỏi bên dưới.</p></div>';
    }
    var button = showControls
      ? '<button type="button" class="ilp-passage__reached-btn' + (evidenceDone ? ' is-done' : '') +
        '" data-reached-passage aria-describedby="comprehensionPassageSentinel">' +
        (evidenceDone ? 'Đã ghi nhận đọc xong' : 'Đã đọc xong đoạn văn') + '</button>'
      : '';
    var sentinel = showControls ? '<div id="comprehensionPassageSentinel" class="ilp-passage__sentinel" aria-hidden="true"></div>' : '';
    return '<div class="ilp-passage"><p class="ilp-passage__text">' + esc(v.passage || '') + '</p>' + sentinel + button + '</div>';
  }

  function renderEvidenceList(v, evidenceDone) {
    var label = v.activityType === 'LISTENING' ? 'Đã nghe audio' : 'Đã đọc hết đoạn văn';
    return '<ul class="ilp-comprehension__evidence"><li class="' + (evidenceDone ? 'is-done' : '') + '">' + esc(label) + '</li></ul>';
  }

  function renderStudy(v, evidenceDone) {
    var isListening = v.activityType === 'LISTENING';
    var kicker = isListening ? 'NGHE HIỂU' : 'ĐỌC HIỂU';
    var title = isListening ? 'Luyện nghe hiểu' : 'Luyện đọc hiểu';
    var instructions = isListening ? 'Nghe kỹ audio rồi mở bài kiểm tra nhỏ.' : 'Đọc kỹ đoạn văn rồi mở bài kiểm tra nhỏ.';
    var gateNote = v.reviewRequired
      ? '<p class="ilp-gate-note">' + (isListening ? 'Bạn cần nghe lại audio trước khi kiểm tra lại.' : 'Bạn cần đọc lại đoạn văn trước khi kiểm tra lại.') + '</p>'
      : (!v.checkEligible && v.cooldownUntil
        ? '<p class="ilp-comprehension__cooldown">Vui lòng quay lại sau <strong>' + esc(formatCooldown(v.cooldownUntil)) + '</strong>.</p>'
        : '');
    return '<section class="ilp-comprehension">' +
      '<header class="ilp-comprehension__head"><div><span class="ilp-kicker">' + kicker + ' // ' +
      (v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG LUYỆN TẬP') + '</span><h1>' + title + '</h1><p>' + instructions + '</p></div>' +
      '<span class="ilp-comprehension__progress-badge">Bài ' + (v.currentExercise + 1) + '/' + v.exerciseCount +
      ' · ' + v.questionCount + ' câu hỏi</span></header>' +
      renderSourcePanel(v, evidenceDone, true) +
      renderEvidenceList(v, evidenceDone) +
      gateNote +
      '<button class="ilp-check" type="button" data-check' + (v.checkEligible ? '' : ' disabled') + '>Bắt đầu kiểm tra <span aria-hidden="true">→</span></button>' +
      '</section>';
  }

  function renderCheckForm(check) {
    var questions = list(check.questions).map(function (q, i) {
      var options = list(q.options).map(function (opt, oi) {
        return '<label class="ilp-option"><input type="radio" name="q-' + q.questionId + '" value="' + (oi + 1) + '">' +
          '<span class="ilp-option__mark">' + String.fromCharCode(65 + oi) + '</span><span>' + esc(opt) + '</span></label>';
      }).join('');
      return '<fieldset class="ilp-question"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText) + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    return '<form class="ilp-check__form" data-submit data-attempt-id="' + esc(check.attemptId) + '">' + questions +
      '<button class="ilp-check__submit" type="submit">Nộp bài</button></form>';
  }

  /** The source (passage/audio) stays visible and re-playable/re-readable during the check itself - unlike
   * Vocabulary/Kanji/Grammar, the source IS the basis for these questions, so hiding it would make the
   * activity untestable rather than just less convenient. `interactive=false` suppresses the reached-end
   * evidence controls here: reading/listening again mid-attempt is not the post-failure review interaction. */
  function renderCheckScreen(v, check) {
    var isListening = v.activityType === 'LISTENING';
    return '<section class="ilp-comprehension">' +
      '<header class="ilp-comprehension__head"><div><span class="ilp-kicker">' + (isListening ? 'NGHE HIỂU' : 'ĐỌC HIỂU') +
      ' // KIỂM TRA</span><h1>' + (isListening ? 'Kiểm tra nghe hiểu' : 'Kiểm tra đọc hiểu') + '</h1>' +
      '<p>Xem/nghe lại nội dung bên dưới nếu cần, rồi chọn đáp án đúng cho từng câu.</p></div>' +
      '<span class="ilp-comprehension__progress-badge">Bài ' + (v.currentExercise + 1) + '/' + v.exerciseCount +
      ' · ' + num(check.totalQuestions) + ' câu hỏi</span></header>' +
      renderSourcePanel(v, false, false) +
      renderCheckForm(check) +
      '</section>';
  }

  function renderResult(result, activityType) {
    var passed = !!result.passed;
    var fullyPassed = passed && result.activityState === 'PASSED';
    var isListening = activityType === 'LISTENING';
    var wrong = list(result.review).filter(function (r) { return r.correct === false; });
    var cooldown = result.activityState === 'COOLDOWN';
    var actionHtml = fullyPassed
      ? '<a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>'
      : passed
        ? '<button class="ilp-check" type="button" data-next-exercise>Bài tiếp theo <span aria-hidden="true">→</span></button>'
        : '<button class="ilp-check" type="button" data-back-to-study>Quay lại bài</button>';
    return '<section class="ilp-result">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">KẾT QUẢ</span><h1>' + (passed ? 'Đã đạt' : 'Chưa đạt') + '</h1></div>' +
      '<div class="ilp-score' + (passed ? ' is-pass' : ' is-fail') + '"><strong>' + num(result.scorePercent) + '%</strong></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng · ngưỡng ' + num(result.passThresholdPercent) + '%</p>' +
      (passed
        ? (fullyPassed ? '<p class="ilp-result__pass">Tuyệt vời! Bạn đã hoàn thành phần này.</p>'
          : '<p class="ilp-result__pass">Đã qua bài này. Hoạt động còn bài tiếp theo.</p>')
        : '<div class="ilp-result__fail"><p>Lần thử ' + num(result.attemptsUsed) + '/3 · còn ' + num(result.attemptsRemaining) + ' lượt.</p>' +
          (result.reviewRequired ? '<p>' + (isListening ? 'Hãy nghe lại audio trước khi thử lại.' : 'Hãy đọc lại đoạn văn trước khi thử lại.') + '</p>' : '') +
          (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
          '</div>') +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions">' + actionHtml + '</div></section>';
  }

  // [JLAS-W0108] PRACTICE: the frozen source of the replayed batch stays above every question.
  function sourceView(type, source) {
    source = source || {};
    return { activityType: type === 'LISTENING' ? 'LISTENING' : 'READING', passage: source.passage || null, audioUrl: source.audioUrl || null };
  }
  function renderPractice(replay, activityType) {
    var banner = (typeof window !== 'undefined' && window.InteractiveStudyMode) ? window.InteractiveStudyMode.banner('PRACTICE') :
      '<p class="ilp-mode-banner" role="note"><strong>Chế độ luyện tập</strong> — không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>';
    return banner + '<section class="ilp-comprehension"><header class="ilp-comprehension__head"><div><span class="ilp-kicker">' +
      (activityType === 'LISTENING' ? 'NGHE HIỂU' : 'ĐỌC HIỂU') + ' // LUYỆN TẬP</span><h1>Luyện tập</h1></div>' +
      '<span class="ilp-comprehension__progress-badge">Bài ' + (num(replay.batchIndex) + 1) + '/' + num(replay.batchCount) +
      '</span></header>' + renderSourcePanel(sourceView(activityType, replay.source), false, false) + renderCheckForm(replay) + '</section>';
  }
  // [JLAS-W0108] REVIEW: each batch shows the source it was actually asked against, or says it is unavailable.
  function renderReview(feedback, activityType) {
    var banner = '<p class="ilp-mode-banner" role="note" data-mode-banner="REVIEW"><strong>Chế độ xem lại</strong> — chỉ xem, không ghi nhận gì vào tiến độ.</p>';
    if (!Array.isArray(feedback) || !feedback.length) {
      return banner + '<p class="ilp-result__meta" data-feedback-empty>Chưa có lượt làm bài nào để xem lại.</p>' +
        '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div>';
    }
    return banner + feedback.map(function (batch) {
      var source = batch.sourceStatus === 'AVAILABLE' && batch.source
        ? renderSourcePanel(sourceView(activityType, batch.source), false, false)
        : '<p class="ilp-gate-note" data-source-unavailable>Nội dung bài đọc/nghe của lượt này không còn được lưu, nên không thể hiển thị lại. Kết quả và đáp án vẫn được giữ bên dưới.</p>';
      var items = list(batch.review).map(function (r) {
        return '<li class="' + (r.correct ? 'is-correct' : 'is-wrong') + '"><span>' + esc(r.questionText || ('Câu ' + r.questionId)) +
          '</span><small>' + (r.correct ? 'Đúng' : 'Sai') + (r.correctOption != null ? ' · đáp án đúng: ' + esc(r.correctOption) : '') +
          (r.selectedOption != null ? ' · bạn chọn: ' + esc(r.selectedOption) : ' · bạn bỏ trống') +
          (r.explanation ? ' · ' + esc(r.explanation) : '') + '</small></li>';
      }).join('');
      return '<article class="ilp-feedback__batch" data-feedback-batch="' + num(batch.batchIndex) + '"><h2>Bài ' + (num(batch.batchIndex) + 1) +
        ' · ' + num(batch.scorePercent) + '% (' + num(batch.correctAnswers) + '/' + num(batch.totalQuestions) + ')</h2>' + source +
        '<ol>' + items + '</ol></article>';
    }).join('') + '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div>';
  }

  function renderPassed(activityType, extra) {
    var title = activityType === 'LISTENING' ? 'Phần Nghe hiểu đã đạt' : 'Phần Đọc hiểu đã đạt';
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>' + esc(title) + '</h1></div></header>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>' + (extra || '') + '</div></section>';
  }

  function error(message, code) {
    if (code === 'BLOCKED_CONTENT') {
      return '<section class="ilp-state"><h1>Chưa có đủ học liệu</h1><p>' +
        esc(message || 'Bài tập này chưa có nội dung sẵn sàng. Vui lòng quay lại sau.') + '</p>' +
        '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a></section>';
    }
    return '<section class="ilp-state"><h1>Chưa thể mở phần Đọc/Nghe hiểu</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p>' +
      '<button data-retry type="button">Thử lại</button></section>';
  }

  function mount() {
    var node = document.getElementById('interactiveComprehensionStudyRoot');
    var id = activityId();
    var fallbackPath = '/learning/v2/' + pathKind();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin(fallbackPath + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/comprehension';
    var currentType = pathKind() === 'listening' ? 'LISTENING' : 'READING';
    var currentReviewRequired = false;
    var evidenceSent = false;
    var evidenceObserver = null;
    var StudyMode = window.InteractiveStudyMode;
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions(fallbackPath, id, ['REVIEW', 'PRACTICE']) : '';

    async function api(path, options) {
      var response = await fetch(base + path, Object.assign({ headers: window.JlasAuth.authHeaders() }, options));
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) { var e = new Error(body.message || 'Không thể kết nối tới máy chủ.'); e.code = body.code; throw e; }
      return body.data;
    }
    async function postJson(path) {
      return api(path, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()) });
    }

    function stopWatching() { if (evidenceObserver) { evidenceObserver.disconnect(); evidenceObserver = null; } }

    async function sendReviewEvidence() {
      if (evidenceSent) return;
      evidenceSent = true;
      var wasReviewRequired = currentReviewRequired;
      try {
        await postJson('/review-evidence');
        if (wasReviewRequired) { try { await postJson('/micro-check/review'); } catch (e) { /* cooldown not yet elapsed - state refresh below still reflects it */ } }
      } catch (e) { evidenceSent = false; return; }
      loadStudy();
    }

    function watchPassageEnd() {
      var sentinel = document.getElementById('comprehensionPassageSentinel');
      if (!sentinel || typeof IntersectionObserver === 'undefined') return;
      evidenceObserver = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) { if (entry.isIntersecting) sendReviewEvidence(); });
      });
      evidenceObserver.observe(sentinel);
    }

    function watchAudioPlay() {
      var audio = node.querySelector('[data-comprehension-audio]');
      if (audio) audio.addEventListener('play', function () { sendReviewEvidence(); }, { once: true });
    }

    async function loadStudy() {
      node.setAttribute('aria-busy', 'true');
      stopWatching();
      try {
        if (mode === 'REVIEW') {
          node.innerHTML = renderReview(await api('/review/attempts'), currentType);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'PRACTICE') { await doPractice(); node.setAttribute('aria-busy', 'false'); return; }
        var study = await api('/study');
        var v = viewModel(study);
        currentType = v.activityType;
        currentReviewRequired = v.reviewRequired;
        evidenceSent = false;
        if (v.passed) { node.innerHTML = renderPassed(v.activityType, PASSED_ACTIONS); }
        else {
          node.innerHTML = renderStudy(v, false);
          if (v.activityType === 'LISTENING') watchAudioPlay(); else watchPassageEnd();
        }
      } catch (e) { node.innerHTML = error(e.message, e.code); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doPractice() {
      try { node.innerHTML = renderPractice(await postJson('/practice'), currentType); }
      catch (e) { node.innerHTML = error(e.message, e.code); }
    }

    async function doCheck() {
      try {
        var study = await api('/study');
        var v = viewModel(study);
        currentType = v.activityType;
        var check = await postJson('/micro-check');
        node.innerHTML = renderCheckScreen(v, check);
      } catch (e) { node.innerHTML = error(e.message, e.code); }
    }

    node.addEventListener('click', async function (event) {
      if (event.target.closest('[data-retry]')) return loadStudy();
      if (event.target.closest('[data-check]')) return doCheck();
      if (event.target.closest('[data-practice-again]')) return doPractice();
      if (event.target.closest('[data-reached-passage]')) return sendReviewEvidence();
      if (event.target.closest('[data-next-exercise]') || event.target.closest('[data-back-to-study]')) return loadStudy();
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
        var result = await api(mode === 'PRACTICE' ? '/practice/submit' : '/micro-check/submit', {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ attemptId: Number(form.dataset.attemptId), answers: answers })
        });
        node.innerHTML = mode === 'PRACTICE' ? StudyMode.practiceResult(result) : renderResult(result, currentType);
      } catch (e) { node.innerHTML = error(e.message, e.code); }
      node.setAttribute('aria-busy', 'false');
    });

    loadStudy();
  }

  return {
    viewModel: viewModel, formatCooldown: formatCooldown, renderSourcePanel: renderSourcePanel,
    renderEvidenceList: renderEvidenceList, renderStudy: renderStudy, renderCheckForm: renderCheckForm,
    renderCheckScreen: renderCheckScreen, renderResult: renderResult, renderPassed: renderPassed,
    renderPractice: renderPractice, renderReview: renderReview,
    error: error, mount: mount
  };
});
