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
      questionPrompts: list(data.questions).map(function (q) { return q.prompt || ''; }).filter(Boolean),
      checkEligible: !!check.checkEligible,
      reviewRequired: !!check.reviewRequired,
      cooldownUntil: check.cooldownUntil || null,
      attemptsUsed: num(check.attemptsUsed),
      attemptsRemaining: num(check.attemptsRemaining),
      passed: !!check.passed,
      inProgressAttemptId: check.inProgressAttemptId ? num(check.inProgressAttemptId) : null,
      sourceNote: data.sourceNote || null,
      passageTranslations: list(data.passageTranslations).map(list)
    };
  }

  /** Owner 2026-10-22: after the part is passed, the source's own line-by-line translation sits under each passage. */
  function translationPanel(lines) {
    if (!lines || !lines.length) return '';
    return '<details class="ilp-passage-translation" data-passage-translation><summary>Xem bản dịch đoạn văn</summary><ol>' +
      lines.map(function (line) {
        return '<li><span lang="ja">' + esc(line.ja) + '</span><span>' + esc(line.vi) + '</span></li>';
      }).join('') + '</ol><p class="ilp-source-note">Bản dịch theo nguồn VNJPClub.</p></details>';
  }

  function formatCooldown(value) {
    var d = value ? new Date(value) : null;
    if (!d || Number.isNaN(d.getTime())) return '';
    return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
  }

  function shouldOpenInlineQuestions(v) {
    if (!v || v.passed === true) return false;
    if (v.inProgressAttemptId > 0) return true;
    return v.activityType === 'READING' && v.checkEligible === true;
  }

  /** {@code interactive=false} suppresses the sentinel/manual-evidence button (used while a micro-check
   * attempt is in progress) - reading/listening the source again mid-check should not itself count as
   * post-failure review evidence. {@code evidenceDone} only affects the manual button's own visual state. */
  function renderSourcePanel(v, evidenceDone, interactive, showQuizHint) {
    var showControls = interactive !== false;
    if (v.activityType === 'LISTENING') {
      if (!v.audioUrl) {
        return '<div class="ilp-audio"><p class="ilp-audio__missing">Chưa có file nghe hợp lệ cho bài này.</p></div>';
      }
      return '<div class="ilp-audio"><span class="ilp-audio__icon" aria-hidden="true">🎧</span>' +
        '<audio class="ilp-audio__player" controls preload="none" data-comprehension-audio src="' + esc(v.audioUrl) + '">' +
        'Trình duyệt của bạn không hỗ trợ phát âm thanh.</audio>' +
        (showQuizHint === false ? '' : '<p class="ilp-audio__hint">Nghe hết audio, ghi lại ý chính rồi trả lời câu hỏi.</p>') +
        '<p class="ilp-audio__missing" role="alert" data-audio-error hidden>Không tải được file nghe. Hãy thử tải lại trang; nếu vẫn lỗi, bài này chưa thể học tiếp.</p></div>';
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
    var instructions = isListening ? 'Bước 1: xem dạng câu hỏi và dự đoán thông tin cần nghe. Bước 2: nghe hết audio, ghi ý chính. Bước 3: trả lời câu hỏi nghe hiểu.' : 'Đọc kỹ nội dung. Khi bạn đọc đến cuối bài, các câu hỏi sẽ hiện ngay bên dưới để trả lời tại đây.';
    var gateNote = v.reviewRequired
      ? '<p class="ilp-gate-note">' + (isListening ? 'Bạn cần nghe lại audio trước khi kiểm tra lại.' : 'Bạn cần đọc lại đoạn văn trước khi kiểm tra lại.') + '</p>'
      : (!v.checkEligible && v.cooldownUntil
        ? '<p class="ilp-comprehension__cooldown">Vui lòng quay lại sau <strong>' + esc(formatCooldown(v.cooldownUntil)) + '</strong>.</p>'
        : '');
    return '<section class="ilp-comprehension">' +
      '<header class="ilp-comprehension__head"><div><span class="ilp-kicker">' + kicker + ' // ' +
      (v.checkEligible ? (isListening ? 'SẴN SÀNG TRẢ LỜI' : 'SẴN SÀNG KIỂM TRA') : 'ĐANG LUYỆN TẬP') + '</span><h1>' + title + '</h1><p>' + instructions + '</p></div>' +
      '<span class="ilp-comprehension__progress-badge">Bài ' + (v.currentExercise + 1) + '/' + v.exerciseCount +
      ' · ' + v.questionCount + ' câu hỏi</span></header>' +
      sourceNote(v) +
      (isListening && v.questionPrompts.length ? '<aside class="ilp-listening-preview"><h2>Trước khi nghe</h2>' +
        '<p>Đọc câu hỏi để biết mình cần chú ý thông tin nào; đáp án sẽ xuất hiện sau khi nghe.</p><ol>' +
        v.questionPrompts.map(function (prompt) { return '<li>' + esc(prompt) + '</li>'; }).join('') +
        '</ol></aside>' : '') +
      renderSourcePanel(v, evidenceDone, true) +
      renderEvidenceList(v, evidenceDone) +
      gateNote +
      (isListening ? '<button class="ilp-check" type="button" data-check' + (v.checkEligible ? '' : ' disabled') + '>' +
        'Trả lời câu hỏi nghe hiểu <span aria-hidden="true">→</span></button>' : '') +
      '</section>';
  }

  function renderCheckForm(check, isListening) {
    // [JLAS-W0113] Restore previously autosaved selections (questionId -> selectedOption) so a reopen/reload
    // shows the learner's saved choices instead of a blank quiz. A PRACTICE replay carries no draftAnswers;
    // the empty restore set keeps those forms untouched.
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
      return '<fieldset class="ilp-question" data-question-page="' + i + '"' + (i === 0 ? '' : ' hidden') +
        ' tabindex="-1"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText) + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    var questionCount = list(check.questions).length;
    var lastIndex = Math.max(0, questionCount - 1);
    return '<form class="ilp-check__form" data-submit data-question-pager data-attempt-id="' + esc(check.attemptId) + '">' + questions +
      '<nav class="ilp-question-pager" aria-label="Điều hướng câu hỏi">' +
      '<button class="ilp-check ilp-check--ghost" type="button" data-question-prev disabled>Câu trước</button>' +
      '<span data-question-counter role="status" aria-live="polite">Câu 1 / ' + questionCount + '</span>' +
      '<button class="ilp-check ilp-check--ghost" type="button" data-question-next' + (lastIndex === 0 ? ' hidden' : '') + '>Câu tiếp</button>' +
      '</nav>' +
      '<span class="ilp-check__draft" data-draft-status role="status" aria-live="polite"></span>' +
      '<button class="ilp-check__submit" type="submit" data-question-submit' + (lastIndex > 0 ? ' hidden' : '') + '>' +
      (isListening ? 'Xem kết quả bài nghe' : 'Hoàn tất bài đọc') + '</button></form>';
  }

  function showQuestionPage(form, nextIndex) {
    var pages = Array.prototype.slice.call(form.querySelectorAll('fieldset[data-question-page]'));
    if (!pages.length) return;
    var index = Math.max(0, Math.min(pages.length - 1, Number(nextIndex) || 0));
    pages.forEach(function (page, pageIndex) { page.hidden = pageIndex !== index; });
    var previous = form.querySelector('[data-question-prev]');
    var next = form.querySelector('[data-question-next]');
    var counter = form.querySelector('[data-question-counter]');
    var submit = form.querySelector('[data-question-submit]');
    if (previous) previous.disabled = index === 0;
    if (next) next.hidden = index === pages.length - 1;
    if (counter) counter.textContent = 'Câu ' + (index + 1) + ' / ' + pages.length;
    if (submit) submit.hidden = index !== pages.length - 1;
    if (pages[index] && typeof pages[index].focus === 'function') pages[index].focus();
  }

  /** The source (passage/audio) stays visible and re-playable/re-readable during the check itself - unlike
   * Vocabulary/Kanji/Grammar, the source IS the basis for these questions, so hiding it would make the
   * activity untestable rather than just less convenient. `interactive=false` suppresses the reached-end
   * evidence controls here: reading/listening again mid-attempt is not the post-failure review interaction. */
  /** OD-44 follow-up: unreviewed material names its source on every screen it is studied or answered on. */
  function sourceNote(v) {
    return v && v.sourceNote ? '<p class="ilp-comprehension__source-note">' + esc(v.sourceNote) + '</p>' : '';
  }

  function renderCheckScreen(v, check) {
    var isListening = v.activityType === 'LISTENING';
    // The passage/audio stays visible during the check (it is the question basis), but lookups are locked
    // so the inline dictionary cannot reveal the answer while scoring.
    return '<section class="ilp-comprehension no-inline-dictionary">' +
      '<header class="ilp-comprehension__head"><div><span class="ilp-kicker">' + (isListening ? 'NGHE HIỂU' : 'ĐỌC HIỂU') +
      (isListening ? ' // TRẢ LỜI CÂU HỎI' : ' // HỌC TRỰC TIẾP') + '</span><h1>' +
      (isListening ? 'Trả lời câu hỏi của bài nghe' : 'Câu hỏi trong bài đọc') + '</h1>' +
      '<p>' + (isListening ? 'Nghe lại nội dung nếu cần, rồi chọn đáp án đúng cho từng câu.' :
        'Đọc nội dung và trả lời ngay bên dưới. Sau khi hoàn tất, bạn có thể xem đáp án và yêu cầu AI giải thích từng câu.') + '</p></div>' +
      '<span class="ilp-comprehension__progress-badge">Bài ' + (v.currentExercise + 1) + '/' + v.exerciseCount +
      ' · ' + num(check.totalQuestions) + ' câu hỏi</span></header>' +
      sourceNote(v) +
      renderSourcePanel(v, false, false) +
      renderCheckForm(check, isListening) +
      '</section>';
  }

  function renderResult(result, activityType, activityId) {
    var passed = !!result.passed;
    var fullyPassed = passed && result.activityState === 'PASSED';
    var isListening = activityType === 'LISTENING';
    var wrong = list(result.review).filter(function (r) { return r.correct === false; });
    var cooldown = result.activityState === 'COOLDOWN';
    var actionHtml = fullyPassed
      ? (num(activityId) > 0 ? '<a class="ilp-check" href="/learning/v2/' + (isListening ? 'listening' : 'reading') +
        '?activity=' + encodeURIComponent(activityId) + '&amp;mode=review">Xem lại bài học</a>' : '') +
        '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>'
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
      (wrong.length ? '<p class="ilp-review">' + wrong.length + ' câu cần xem lại. Đáp án, lời giải và nội dung bài ' +
        (isListening ? 'nghe' : 'đọc') + ' nằm trong lịch sử làm bài bên dưới.</p>' : '') +
      '<div class="ilp-result__actions">' + actionHtml + '</div></section>';
  }

  // [JLAS-W0108] PRACTICE: the frozen source of the replayed batch stays above every question.
  function sourceView(type, source) {
    source = source || {};
    return { activityType: type === 'LISTENING' ? 'LISTENING' : 'READING', passage: source.passage || null, audioUrl: source.audioUrl || null };
  }
  function renderPractice(replay, activityType, bannerMode) {
    var lecture = bannerMode === 'REPLAY';
    var banner = (typeof window !== 'undefined' && window.InteractiveStudyMode) ? window.InteractiveStudyMode.banner(lecture ? 'REPLAY' : 'PRACTICE') :
      '<p class="ilp-mode-banner" role="note"><strong>Chế độ luyện tập</strong> — không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>';
    return banner + '<section class="ilp-comprehension"><header class="ilp-comprehension__head"><div><span class="ilp-kicker">' +
      (activityType === 'LISTENING' ? 'NGHE HIỂU' : 'ĐỌC HIỂU') + (lecture ? '' : ' // LUYỆN TẬP') + '</span><h1>' +
      (lecture ? (activityType === 'LISTENING' ? 'Nghe hiểu' : 'Đọc hiểu') : 'Luyện tập') + '</h1></div>' +
      '<span class="ilp-comprehension__progress-badge">Bài ' + (num(replay.batchIndex) + 1) + '/' + num(replay.batchCount) +
      '</span></header>' + renderSourcePanel(sourceView(activityType, replay.source), false, false) + renderCheckForm(replay) + '</section>';
  }
  /** A finished Reading/Listening part: its sources as studied, then the saved attempts with the learner's answers. */
  function renderDoneLesson(sources, attempts, activityType, actions, translations) {
    var StudyMode = typeof window !== 'undefined' ? window.InteractiveStudyMode : null;
    var title = activityType === 'LISTENING' ? 'Nghe hiểu' : 'Đọc hiểu';
    return '<section class="ilp-comprehension"><header class="ilp-comprehension__head"><div><h1>' + title +
      ' <span class="ilp-done-pill">Đã hoàn thành</span></h1><p>Bài và câu trả lời của bạn được giữ nguyên để xem lại.</p></div></header>' +
      (Array.isArray(sources) && sources.length ? renderStoredSources(sources, activityType, translations) : '') +
      (StudyMode && Array.isArray(attempts) && attempts.length ? StudyMode.attemptHistory(attempts) : '') +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>' + (actions || '') + '</div></section>';
  }
  // [JLAS-W0108] REVIEW is the lesson replay, not the quiz-history screen. Each batch reuses the frozen
  // source it was actually asked against, but deliberately omits scores, answers and explanations.
  function renderReview(feedback, activityType) {
    var banner = '<p class="ilp-mode-banner" role="note" data-mode-banner="REVIEW"><strong>Chế độ xem lại</strong> — chỉ xem, không ghi nhận gì vào tiến độ.</p>';
    if (!Array.isArray(feedback) || !feedback.length) {
      return banner + '<p class="ilp-result__meta" data-feedback-empty>Chưa có nội dung bài học để xem lại.</p>' +
        '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div>';
    }
    var title = activityType === 'LISTENING' ? 'Bài nghe đã học' : 'Bài đọc đã học';
    return banner + '<section class="ilp-comprehension"><header class="ilp-comprehension__head"><div>' +
      '<span class="ilp-kicker">XEM LẠI</span><h1>' + title + '</h1>' +
      '<p>Xem/nghe lại đúng nội dung của từng phần đã học.</p></div></header>' + renderStoredSources(feedback, activityType) +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  function renderStoredSources(feedback, activityType, translations) {
    return feedback.map(function (batch) {
      var source = batch.sourceStatus === 'AVAILABLE' && batch.source
        ? renderSourcePanel(sourceView(activityType, batch.source), false, false, false) +
          translationPanel(list(translations)[num(batch.batchIndex)])
        : '<p class="ilp-gate-note" data-source-unavailable>Nội dung bài đọc/nghe của phần này không còn được lưu nên chưa thể hiển thị lại.</p>';
      return '<article class="ilp-review-source" data-review-source="' + num(batch.batchIndex) + '"><h2>Phần ' +
        (num(batch.batchIndex) + 1) + '</h2>' + source + '</article>';
    }).join('');
  }

  function renderPracticeSources(feedback, activityType) {
    var available = list(feedback).some(function (batch) { return batch.sourceStatus === 'AVAILABLE' && batch.source; });
    return '<p class="ilp-mode-banner" role="note" data-mode-banner="PRACTICE"><strong>Chế độ luyện tập</strong> — không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>' +
      '<section class="ilp-comprehension"><header class="ilp-comprehension__head"><div><span class="ilp-kicker">' +
      (activityType === 'LISTENING' ? 'NGHE HIỂU' : 'ĐỌC HIỂU') + ' // ÔN BÀI</span><h1>' +
      (activityType === 'LISTENING' ? 'Nghe lại bài học' : 'Đọc lại bài học') + '</h1>' +
      '<p>Xem/nghe lại nguồn đã học trước khi làm bài kiểm tra luyện tập.</p></div></header>' +
      renderStoredSources(list(feedback), activityType) +
      (available ? '<button class="ilp-check" type="button" data-check>Làm lại bài kiểm tra →</button>'
        : '<p class="ilp-gate-note" data-source-unavailable>Chưa có nguồn bài học để luyện tập lại.</p>') +
      '</section>';
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

  // =========================================================================
  // §2.32 (owner 2026-10-26) — "Luyện thêm (không tính điểm)"
  // =========================================================================
  var REASON_MAP = {
    WEAKNESS: 'Trúng chỗ bạn sai',
    DIFFICULTY: 'Độ khó hợp với bạn',
    SAME_CHAPTER: 'Cùng chương'
  };
  function formatReason(code) {
    return REASON_MAP[code] || String(code || '');
  }

  function renderExtraPracticeSection(data, loading, errorMsg) {
    var title = '<header class="ilp-extra-practice__head">' +
      '<div><span class="ilp-kicker">MỞ RỘNG // BỔ TRỢ</span>' +
      '<h2 id="ilpExtraPracticeTitle" class="ilp-extra-practice__title">Luyện thêm <span class="ilp-extra-practice__pill">Không tính điểm</span></h2>' +
      '<p class="ilp-extra-practice__desc">Các bài đọc/nghe thêm phù hợp với bạn. Làm thêm không ảnh hưởng tiến độ học tập.</p>' +
      // F1-5 (owner 2026-10-28): the learner chose this skill as a focus — more passages, offered up front.
      (data && data.focus ? '<p class="ilp-extra-practice__focus" role="note">Kỹ năng ưu tiên của bạn: J-LAS gợi ý thêm bài luyện cho kỹ năng này.</p>' : '') +
      '</div></header>';

    if (loading) {
      return '<section class="ilp-extra-practice" aria-labelledby="ilpExtraPracticeTitle" aria-busy="true">' +
        title +
        '<div class="ilp-extra-practice__loading" role="status"><span class="ilp-loader" aria-hidden="true"></span> Đang tải gợi ý luyện thêm…</div>' +
        '</section>';
    }

    if (errorMsg) {
      return '<section class="ilp-extra-practice" aria-labelledby="ilpExtraPracticeTitle">' +
        title +
        '<div class="ilp-extra-practice__empty is-error" data-extra-error role="status">' +
        '<p>' + esc(errorMsg) + '</p>' +
        '<button type="button" class="ilp-check ilp-check--ghost ilp-check--sm" data-extra-retry>Thử lại</button>' +
        '</div></section>';
    }

    var items = list(data && data.items);
    // No chapter means extra practice does not apply here (communication goal, non-compiled path): show nothing.
    if (!items.length && data && data.chapter == null) return '';
    if (!items.length) {
      var msg = (data && data.message) || 'Chưa có bài luyện thêm cho phần này.';
      return '<section class="ilp-extra-practice" aria-labelledby="ilpExtraPracticeTitle">' +
        title +
        '<div class="ilp-extra-practice__empty" data-extra-empty role="status">' +
        '<p>' + esc(msg) + '</p>' +
        '</div></section>';
    }

    var currentChapter = data && data.chapter != null ? num(data.chapter) : null;
    var cards = items.map(function (item, idx) {
      var reasons = list(item.reasons).map(function (r) {
        return '<span class="ilp-extra-badge ilp-extra-badge--reason ilp-extra-badge--' + esc(String(r).toLowerCase()) + '">' +
          esc(formatReason(r)) + '</span>';
      }).join('');
      var meta = [];
      if (item.questionCount) meta.push('<span class="ilp-extra-meta">' + num(item.questionCount) + ' câu hỏi</span>');
      if (item.minutes) meta.push('<span class="ilp-extra-meta">' + num(item.minutes) + ' phút</span>');
      if (item.difficulty) meta.push('<span class="ilp-extra-meta">Độ khó ' + num(item.difficulty) + '/5</span>');

      var subtitle = '';
      if (item.chapterHint != null) {
        subtitle = (currentChapter != null && num(item.chapterHint) === currentChapter)
          ? 'Cùng chương'
          : 'Mức gần chương ' + num(item.chapterHint);
      }

      return '<article class="ilp-extra-card" data-extra-passage="' + esc(item.passage) + '">' +
        '<div class="ilp-extra-card__head">' +
          '<div class="ilp-extra-card__tags">' +
            reasons +
            (item.label ? '<span class="ilp-extra-badge ilp-extra-badge--label" role="note">' + esc(item.label) + '</span>' : '') +
          '</div>' +
          '<div class="ilp-extra-card__meta-group">' + meta.join(' · ') + '</div>' +
        '</div>' +
        '<div class="ilp-extra-card__body">' +
          '<div class="ilp-extra-card__info">' +
            '<h3 class="ilp-extra-card__title">Bài luyện thêm ' + (idx + 1) + '</h3>' +
            (subtitle ? '<p class="ilp-extra-card__subtitle">' + esc(subtitle) + '</p>' : '') +
          '</div>' +
          '<button type="button" class="ilp-check ilp-check--sm ilp-extra-card__start-btn" data-extra-start="' + esc(item.passage) + '" ' +
            'aria-label="Luyện bài ' + (idx + 1) + ': ' + num(item.questionCount) + ' câu hỏi">' +
            'Luyện bài này <span aria-hidden="true">→</span></button>' +
        '</div>' +
      '</article>';
    }).join('');

    return '<section class="ilp-extra-practice" aria-labelledby="ilpExtraPracticeTitle">' +
      title +
      '<div class="ilp-extra-practice__grid">' + cards + '</div>' +
      '</section>';
  }

  function renderExtraCheckForm(issue, isListening) {
    var questions = list(issue.questions).map(function (q, i) {
      var options = list(q.options).map(function (opt, oi) {
        return '<label class="ilp-option"><input type="radio" name="q-' + q.questionId + '" value="' + (oi + 1) + '">' +
          '<span class="ilp-option__mark">' + String.fromCharCode(65 + oi) + '</span><span>' + esc(opt) + '</span></label>';
      }).join('');
      return '<fieldset class="ilp-question" data-question-page="' + i + '"' + (i === 0 ? '' : ' hidden') +
        ' tabindex="-1"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText || q.prompt || '') + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    var questionCount = list(issue.questions).length;
    var lastIndex = Math.max(0, questionCount - 1);
    return '<form class="ilp-check__form" data-extra-submit data-question-pager data-attempt-id="' + esc(issue.attemptId) + '">' + questions +
      '<nav class="ilp-question-pager" aria-label="Điều hướng câu hỏi">' +
      '<button class="ilp-check ilp-check--ghost" type="button" data-question-prev disabled>Câu trước</button>' +
      '<span data-question-counter role="status" aria-live="polite">Câu 1 / ' + questionCount + '</span>' +
      '<button class="ilp-check ilp-check--ghost" type="button" data-question-next' + (lastIndex === 0 ? ' hidden' : '') + '>Câu tiếp</button>' +
      '</nav>' +
      '<button class="ilp-check__submit" type="submit" data-question-submit' + (lastIndex > 0 ? ' hidden' : '') + '>' +
      'Nộp bài luyện thêm</button></form>';
  }

  function renderExtraPracticeScreen(issue, currentType) {
    var isListening = currentType === 'LISTENING';
    var banner = '<div class="ilp-mode-banner" role="note" data-mode-banner="EXTRA_PRACTICE">' +
      '<strong>Luyện thêm (không tính điểm)</strong> — Bài tập bổ trợ kỹ năng, không ảnh hưởng tiến độ, điểm số hay việc mở khóa bài tiếp theo.</div>';
    var labelNote = issue.label ? '<p class="ilp-comprehension__source-note">' + esc(issue.label) + '</p>' : '';
    var kicker = (isListening ? 'NGHE HIỂU' : 'ĐỌC HIỂU') + ' // LUYỆN THÊM';
    var title = isListening ? 'Luyện nghe hiểu thêm' : 'Luyện đọc hiểu thêm';
    var questionCount = list(issue.questions).length;

    return banner + '<section class="ilp-comprehension no-inline-dictionary ilp-extra-practice-active">' +
      '<header class="ilp-comprehension__head"><div><span class="ilp-kicker">' + kicker + '</span>' +
      '<h1>' + title + '</h1>' +
      '<p>' + (isListening ? 'Nghe nội dung và chọn đáp án đúng cho từng câu hỏi.' : 'Đọc kỹ đoạn văn và chọn đáp án đúng cho từng câu hỏi.') + '</p></div>' +
      '<div class="ilp-extra-head-actions">' +
        '<span class="ilp-comprehension__progress-badge">Luyện thêm · ' + questionCount + ' câu hỏi</span>' +
        '<button type="button" class="ilp-check ilp-check--ghost ilp-check--sm" data-extra-cancel>Về bài chính</button>' +
      '</div></header>' +
      labelNote +
      renderSourcePanel({ activityType: currentType, passage: issue.source && issue.source.passage, audioUrl: issue.source && issue.source.audioUrl }, false, false) +
      renderExtraCheckForm(issue, isListening) +
      '</section>';
  }

  function renderExtraPracticeResult(result, issue, currentType) {
    var isListening = currentType === 'LISTENING';
    var passed = !!result.passed;
    var reviews = list(result.review);
    var questionMap = {};
    if (issue && Array.isArray(issue.questions)) {
      issue.questions.forEach(function (q) { questionMap[String(q.questionId)] = q; });
    }

    var reviewsHtml = reviews.map(function (rev, idx) {
      var isCorrect = !!rev.correct;
      var q = questionMap[String(rev.questionId)] || {};
      var qText = q.questionText || q.prompt || '';
      var options = list(q.options);
      var selectedText = rev.selectedOption != null && options[rev.selectedOption - 1] ? options[rev.selectedOption - 1] : '';
      var correctText = rev.correctOption != null && options[rev.correctOption - 1] ? options[rev.correctOption - 1] : '';
      var explanation = rev.explanation || '';

      return '<article class="ilp-extra-review-item ' + (isCorrect ? 'is-correct' : 'is-wrong') + '" tabindex="0">' +
        '<div class="ilp-extra-review-item__head">' +
          '<span class="ilp-extra-review-item__badge">' + (isCorrect ? '✓ Câu ' + (idx + 1) + ' đúng' : '✗ Câu ' + (idx + 1) + ' chưa đúng') + '</span>' +
        '</div>' +
        (qText ? '<p class="ilp-extra-review-item__q"><strong>' + esc(qText) + '</strong></p>' : '') +
        '<div class="ilp-extra-review-item__answers">' +
          (selectedText ? '<p class="ilp-extra-review-item__user-ans">Lựa chọn của bạn: <span>' + esc(selectedText) + '</span></p>' : '') +
          (!isCorrect && correctText ? '<p class="ilp-extra-review-item__correct-ans">Đáp án đúng: <span>' + esc(correctText) + '</span></p>' : '') +
        '</div>' +
        (explanation ? '<div class="ilp-extra-review-item__explanation"><span class="ilp-extra-review-item__exp-label">Giải thích:</span> <p>' + esc(explanation) + '</p></div>' : '') +
      '</article>';
    }).join('');

    return '<section class="ilp-result ilp-extra-result">' +
      '<div class="ilp-mode-banner" role="note">' +
        '<strong>Luyện thêm hoàn tất</strong> — Kết quả này không ảnh hưởng đến tiến độ học tập, điểm số hay việc mở khóa bài tiếp theo.' +
      '</div>' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">KẾT QUẢ // LUYỆN THÊM</span>' +
      '<h1>' + (passed ? 'Đã hoàn thành bài luyện thêm' : 'Đã nộp bài luyện thêm') + '</h1></div>' +
      '<div class="ilp-score ' + (passed ? 'is-pass' : 'is-fail') + '"><strong>' + num(result.scorePercent) + '%</strong></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng</p>' +
      '<p class="ilp-result__pass">Kết quả luyện thêm không tính vào tiến độ hoàn thành của lộ trình.</p>' +
      (reviewsHtml ? '<div class="ilp-extra-result__reviews">' + reviewsHtml + '</div>' : '') +
      '<div class="ilp-result__actions">' +
        '<button class="ilp-check" type="button" data-extra-back-to-study>Quay lại bài chính</button>' +
        '<button class="ilp-check ilp-check--ghost" type="button" data-extra-more>Luyện bài khác</button>' +
      '</div></section>';
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
    var PRACTICE_RESULT_STATE_KEY = 'jlasComprehensionPracticeResult';
    var LEARN_RESULT_STATE_KEY = 'jlasComprehensionLearnResult';
    if (StudyMode && StudyMode.bindNextAction) StudyMode.bindNextAction(node);
    if (StudyMode && StudyMode.bindAttemptHistory) StudyMode.bindAttemptHistory(node);
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var replayLecture = false;
    var currentExtraIssue = null;
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions(fallbackPath, id, ['REVIEW', 'PRACTICE']) : '';
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
      if (!response.ok) { var e = new Error(body.message || 'Không thể kết nối tới máy chủ.'); e.code = body.code; throw e; }
      return body.data;
    }
    async function postJson(path) {
      return api(path, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()) });
    }

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

    // A submitted LEARN attempt is remembered on its own history entry, so browser Back/Forward shows that
    // result again instead of issuing a new check. The server's attempt history stays the source of truth.
    function savedLearnResult() {
      var state = window.history && window.history.state;
      var saved = state && state[LEARN_RESULT_STATE_KEY];
      return saved && String(saved.activityId) === String(id) && saved.result && saved.attemptId ? saved : null;
    }

    function rememberLearnResult(result, attemptId) {
      if (!window.history || typeof window.history.replaceState !== 'function' || !(attemptId > 0)) return;
      var current = window.history.state;
      var state = current && typeof current === 'object' ? Object.assign({}, current) : {};
      state[LEARN_RESULT_STATE_KEY] = { activityId: Number(id), attemptId: attemptId, activityType: currentType, result: result };
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

    // The newest attempt this tab opened. A remembered result is only shown again while it is still the
    // learner's latest attempt; a check opened later (even unsubmitted) wins.
    function issuedKey() { return 'jlasComprehensionIssued:' + id; }
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
      currentType = saved.activityType === 'LISTENING' ? 'LISTENING' : 'READING';
      node.innerHTML = renderResult(saved.result, currentType, id) + '<div data-extra-practice-mount></div>';
      attachExtraPractice();
      var resultPanel = node.querySelector('.ilp-result');
      if (resultPanel && attempts.length && StudyMode && StudyMode.attemptHistory)
        resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
      if (saved.result.passed && saved.result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction)
        await StudyMode.showNextAction(node, id);
      return true;
    }

    function renderSavedPracticeResult(saved) {
      node.innerHTML = StudyMode.practiceResult(saved.result, 'Ôn bài rồi kiểm tra lại') + '<div data-extra-practice-mount></div>';
      attachExtraPractice();
      if (saved.attempts.length && StudyMode.attemptHistory) {
        var resultPanel = node.querySelector('.ilp-result');
        if (resultPanel) resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(saved.attempts));
      }
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
      if (!audio) return;
      audio.addEventListener('ended', function () { sendReviewEvidence(); }, { once: true });
      audio.addEventListener('error', function () {
        var message = node.querySelector('[data-audio-error]');
        if (message) message.hidden = false;
        var check = node.querySelector('[data-check]');
        if (check) check.disabled = true;
      });
    }

    function watchExtraAudioError() {
      var audio = node.querySelector('[data-comprehension-audio]');
      if (!audio) return;
      audio.addEventListener('error', function () {
        var message = node.querySelector('[data-audio-error]');
        if (message) message.hidden = false;
      });
    }

    async function attachExtraPractice() {
      var mountNode = node.querySelector('[data-extra-practice-mount]');
      if (!mountNode) return;
      mountNode.innerHTML = renderExtraPracticeSection(null, true, null);
      try {
        var suggestions = await api('/extra-practice');
        if (mountNode && mountNode.parentNode) {
          mountNode.innerHTML = renderExtraPracticeSection(suggestions, false, null);
          // F1-5: a focus skill opens its extra practice for the learner instead of leaving it below the fold.
          var section = suggestions && suggestions.focus && list(suggestions.items).length ? mountNode.querySelector('.ilp-extra-practice') : null;
          if (section && typeof section.scrollIntoView === 'function') section.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      } catch (err) {
        if (mountNode && mountNode.parentNode) {
          mountNode.innerHTML = renderExtraPracticeSection(null, false, err.message || 'Không thể tải danh sách luyện thêm.');
        }
      }
    }

    async function doExtraPractice(passage) {
      if (!passage) return;
      node.setAttribute('aria-busy', 'true');
      stopWatching();
      try {
        var issue = await api('/extra-practice', {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ passage: passage })
        });
        currentExtraIssue = issue;
        node.innerHTML = renderExtraPracticeScreen(issue, currentType);
        // Not the main audio watcher: its "ended" hook sends review evidence for the required activity.
        watchExtraAudioError();
        var firstQ = node.querySelector('fieldset[data-question-page]');
        if (firstQ && typeof firstQ.focus === 'function') firstQ.focus();
        if (typeof window !== 'undefined' && typeof window.scrollTo === 'function') window.scrollTo({ top: 0, behavior: 'smooth' });
      } catch (e) {
        var mountNode = node.querySelector('[data-extra-practice-mount]');
        if (mountNode) {
          mountNode.innerHTML = renderExtraPracticeSection(null, false, e.message || 'Không thể bắt đầu bài luyện thêm.');
        } else {
          alert(e.message || 'Không thể bắt đầu bài luyện thêm.');
        }
      } finally {
        node.setAttribute('aria-busy', 'false');
      }
    }

    async function loadStudy() {
      node.setAttribute('aria-busy', 'true');
      stopWatching();
      try {
        if (mode === 'RESULTS') {
          await StudyMode.loadSavedResults(node, api, fallbackPath, id);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'REVIEW') {
          // Owner 2026-10-23: a finished part replays as the first time (passage + open questions); the quiz
          // goes through the practice endpoints, so each replay is saved in the attempt history.
          var replayView = viewModel(await api('/study'));
          if (replayView.passed) {
            mode = 'PRACTICE';
            replayLecture = true;
            currentType = replayView.activityType;
            clearPracticeResult();
            node.innerHTML = renderPractice(await postJson('/practice'), currentType, 'REPLAY');
            node.setAttribute('aria-busy', 'false');
            return;
          }
          node.innerHTML = renderReview(await api('/review/attempts'), currentType) + '<div data-extra-practice-mount></div>';
          attachExtraPractice();
          // [JLAS-W0193] §2.23 — a review reopened mid-check must still offer the remaining batch.
          // The comprehension study payload carries the gate inside `microCheck`, not on the attempts feed.
          var gate = null;
          try { var reviewStudy = await api('/study'); gate = reviewStudy && reviewStudy.microCheck; } catch (e) { gate = null; }
          if (gate && !gate.passed) node.insertAdjacentHTML('beforeend', StudyMode.reviewGateActions(gate, fallbackPath, id));
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'PRACTICE') {
          var saved = savedPracticeResult();
          if (saved) {
            renderSavedPracticeResult(saved);
            node.setAttribute('aria-busy', 'false');
            return;
          }
          node.innerHTML = renderPracticeSources(await api('/review/attempts'), currentType) + '<div data-extra-practice-mount></div>';
          attachExtraPractice();
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var savedLearn = mode === 'LEARN' ? savedLearnResult() : null;
        if (savedLearn && await restoreLearnResult(savedLearn)) {
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var study = await api('/study');
        var v = viewModel(study);
        currentType = v.activityType;
        currentReviewRequired = v.reviewRequired;
        evidenceSent = false;
        if (v.passed) {
          // Owner 2026-10-21: a finished part reopens on its passages and the learner's saved answers, not an empty screen.
          var doneSources = [], doneAttempts = [];
          try { doneSources = await api('/review/attempts'); } catch (e) { doneSources = []; }
          try { doneAttempts = await api('/attempt-history'); } catch (e) { doneAttempts = []; }
          node.innerHTML = renderDoneLesson(doneSources, doneAttempts, v.activityType, PASSED_ACTIONS, v.passageTranslations) +
            '<div data-extra-practice-mount></div>';
          attachExtraPractice();
        }
        else if (shouldOpenInlineQuestions(v)) {
          var inlineCheck = await postJson('/micro-check');
          rememberIssuedAttempt(inlineCheck && inlineCheck.attemptId);
          // Extra practice stays off the scored check screen: starting one would replace the unsent answers.
          node.innerHTML = renderCheckScreen(v, inlineCheck);
        }
        else {
          node.innerHTML = renderStudy(v, false) +
            '<div data-extra-practice-mount></div>';
          if (v.activityType === 'LISTENING') watchAudioPlay(); else watchPassageEnd();
          attachExtraPractice();
        }
      } catch (e) { node.innerHTML = error(e.message, e.code); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doPractice() {
      clearPracticeResult();
      try { node.innerHTML = renderPractice(await postJson('/practice'), currentType, replayLecture ? 'REPLAY' : 'PRACTICE'); }
      catch (e) { node.innerHTML = error(e.message, e.code); }
    }

    async function doCheck() {
      try {
        var study = await api('/study');
        var v = viewModel(study);
        currentType = v.activityType;
        var check = await postJson('/micro-check');
        rememberIssuedAttempt(check && check.attemptId);
        node.innerHTML = renderCheckScreen(v, check);
      } catch (e) { node.innerHTML = error(e.message, e.code); }
    }

    node.addEventListener('click', async function (event) {
      if (event.target.closest('[data-retry]')) return loadStudy();
      if (event.target.closest('[data-check]')) { clearLearnResult(); return mode === 'PRACTICE' ? doPractice() : doCheck(); }
      if (event.target.closest('[data-practice-review]')) { clearPracticeResult(); return loadStudy(); }
      if (event.target.closest('[data-reached-passage]')) return sendReviewEvidence();
      if (event.target.closest('[data-next-exercise]') || event.target.closest('[data-back-to-study]')) { clearLearnResult(); return loadStudy(); }
      if (event.target.closest('[data-extra-start]')) {
        var extraBtn = event.target.closest('[data-extra-start]');
        return doExtraPractice(extraBtn.getAttribute('data-extra-start'));
      }
      if (event.target.closest('[data-extra-cancel]') || event.target.closest('[data-extra-back-to-study]')) {
        currentExtraIssue = null;
        return loadStudy();
      }
      if (event.target.closest('[data-extra-more]')) {
        currentExtraIssue = null;
        await loadStudy();
        var extraSec = node.querySelector('.ilp-extra-practice');
        if (extraSec && typeof extraSec.scrollIntoView === 'function') {
          extraSec.scrollIntoView({ behavior: 'smooth' });
        }
        return;
      }
      if (event.target.closest('[data-extra-retry]')) {
        return attachExtraPractice();
      }
    });

    node.addEventListener('submit', async function (event) {
      var extraForm = event.target.closest('[data-extra-submit]');
      var form = extraForm || event.target.closest('[data-submit]');
      if (!form) return;
      event.preventDefault();
      if (extraForm) {
        var extraAnswers = Array.prototype.slice.call(form.querySelectorAll('input[type="radio"]:checked')).map(function (input) {
          return { questionId: Number(input.name.slice(2)), selectedOption: Number(input.value) };
        });
        node.setAttribute('aria-busy', 'true');
        try {
          var extraResult = await api('/extra-practice/submit', {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
            body: JSON.stringify({ attemptId: Number(form.dataset.attemptId), answers: extraAnswers })
          });
          node.innerHTML = renderExtraPracticeResult(extraResult, currentExtraIssue, currentType);
          if (typeof window !== 'undefined' && typeof window.scrollTo === 'function') window.scrollTo({ top: 0, behavior: 'smooth' });
        } catch (e) {
          node.innerHTML = error(e.message, e.code);
        } finally {
          node.setAttribute('aria-busy', 'false');
        }
        return;
      }
      // A submitted attempt must never be re-autosaved: stop any pending debounce and cancel in-flight retries.
      if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
      draftDirty = false;
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
        if (mode === 'PRACTICE') rememberPracticeResult(result, []);
        else rememberLearnResult(result, Number(form.dataset.attemptId));
        node.innerHTML = (mode === 'PRACTICE' ? StudyMode.practiceResult(result, 'Ôn bài rồi kiểm tra lại') : renderResult(result, currentType, id)) +
          '<div data-extra-practice-mount></div>';
        attachExtraPractice();
        if (StudyMode && StudyMode.attemptHistory) {
          try {
            var attempts = await api('/attempt-history');
            var resultPanel = node.querySelector('.ilp-result');
            if (resultPanel && attempts && attempts.length) {
              resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
              if (mode === 'PRACTICE') rememberPracticeResult(result, attempts);
            }
          } catch (historyError) { /* Keep the current quiz result usable if history is unavailable. */ }
        }
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction) {
          await StudyMode.showNextAction(node, id);
          if (StudyMode.celebrate) StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã vượt qua phần ' + (currentType === 'LISTENING' ? 'Nghe hiểu' : 'Đọc hiểu') + '.', activityId: id });
        }
      } catch (e) { node.innerHTML = error(e.message, e.code); }
      node.setAttribute('aria-busy', 'false');
    });

    node.addEventListener('click', function (event) {
      var target = event.target && event.target.closest ? event.target : null;
      if (!target) return;
      var button = target.closest('[data-question-prev], [data-question-next]');
      if (!button) return;
      var form = button.closest('[data-question-pager]');
      if (!form) return;
      event.preventDefault();
      var pages = Array.prototype.slice.call(form.querySelectorAll('fieldset[data-question-page]'));
      var current = pages.findIndex(function (page) { return !page.hidden; });
      showQuestionPage(form, current + (button.matches('[data-question-next]') ? 1 : -1));
    });

    node.addEventListener('change', function (event) {
      if (event.target.matches && event.target.matches('.ilp-check__form input[type="radio"]')) {
        var form = event.target.closest('form');
        if (form && !form.hasAttribute('data-extra-submit')) {
          scheduleDraftSave(form);
        }
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
      var form = node.querySelector('.ilp-check__form:not([data-extra-submit])');
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
    viewModel: viewModel, formatCooldown: formatCooldown, shouldOpenInlineQuestions: shouldOpenInlineQuestions,
    renderSourcePanel: renderSourcePanel,
    renderEvidenceList: renderEvidenceList, renderStudy: renderStudy, renderCheckForm: renderCheckForm,
    showQuestionPage: showQuestionPage,
    renderCheckScreen: renderCheckScreen, renderResult: renderResult, renderPassed: renderPassed, renderDoneLesson: renderDoneLesson,
    renderPractice: renderPractice, renderReview: renderReview, renderPracticeSources: renderPracticeSources,
    formatReason: formatReason, renderExtraPracticeSection: renderExtraPracticeSection,
    renderExtraPracticeScreen: renderExtraPracticeScreen, renderExtraPracticeResult: renderExtraPracticeResult,
    error: error, mount: mount
  };
});
