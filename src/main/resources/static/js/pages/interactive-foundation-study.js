(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveFoundationStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0108] Hiragana/Katakana foundation study page for from-zero learners. Same view-model/render/mount
  // shape as interactive-kanji-study.js: the server-owned study + micro-check contract is identical, only the
  // card content (a kana character + its romaji) and the API base path differ.

  var StudyModeLib = typeof window !== 'undefined' && window.InteractiveStudyMode ? window.InteractiveStudyMode
    : (typeof require === 'function' ? require('./interactive-study-mode.js') : null);
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  // [JLAS-W0125] "Phần x / n" only when a pace change split this skill into ordered parts of the package.
  // Typed recall is local practice only: it never touches mastery, flip/remember evidence or the quiz score.
  function checkTypedRomaji(expected, entered) {
    var key = String(expected || '').normalize('NFKC').trim().toLowerCase();
    return key.length > 0 && key === String(entered || '').normalize('NFKC').trim().toLowerCase();
  }
  function partLabel(v) {
    return v.partCount > 1 && v.partIndex > 0
      ? '<p class="ilp-part-indicator">Phần ' + v.partIndex + ' / ' + v.partCount + '</p>' : '';
  }
  // [JLAS-W0108] Quiz split into multiple parts: name the exact next part (mirrors interactive-kanji-study.js).
  function checkButtonLabel(gate) {
    if (!gate) return 'Bắt đầu kiểm tra →';
    var total = num(gate.batchCount);
    if (total > 1) {
      var part = num(gate.currentBatch) + 1;
      return (num(gate.currentBatch) > 0 ? 'Tiếp tục kiểm tra' : 'Bắt đầu kiểm tra') + ' — phần ' + part + '/' + total;
    }
    return 'Bắt đầu kiểm tra →';
  }
  function list(value) { return Array.isArray(value) ? value : []; }
  function speakJapanese(text) {
    if (typeof window === 'undefined' || !window.speechSynthesis || !window.SpeechSynthesisUtterance) return false;
    window.speechSynthesis.cancel();
    var utterance = new window.SpeechSynthesisUtterance(String(text || ''));
    utterance.lang = 'ja-JP';
    window.speechSynthesis.speak(utterance);
    return true;
  }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  /** Hiragana U+3040–U+309F, Katakana U+30A0–U+30FF; anything else (should not happen) gets a neutral label. */
  function scriptLabel(character) {
    var code = String(character || '').charCodeAt(0);
    if (code >= 0x3040 && code <= 0x309F) return 'HIRAGANA';
    if (code >= 0x30A0 && code <= 0x30FF) return 'KATAKANA';
    return 'BẢNG CHỮ CÁI';
  }

  function viewModel(data) {
    data = data || {};
    var kind = data.contentKind === 'EXAMPLE_WORD' ? 'EXAMPLE_WORD' : 'CHARACTER';
    var cards = list(data.cards).map(function (card) { return Object.assign({}, card, { contentKind: kind }); })
      .sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    var current = null;
    for (var i = 0; i < cards.length; i++) { if (!cards[i].flipped) { current = cards[i]; break; } }
    if (!current && cards.length) current = cards[0];
    return {
      activityId: data.activityId, partIndex: num(data.partIndex), partCount: num(data.partCount),
      contentKind: kind, lessonTitle: data.lessonTitle, lessonDescription: data.lessonDescription,
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

  function cardFace(card) {
    if (!card) return '<div class="ilp-card__empty"><h2>Chưa có thẻ chữ cái</h2></div>';
    var isWord = card.contentKind === 'EXAMPLE_WORD';
    return '<div class="ilp-card__inner"><div class="ilp-card__front"' + (card.flipped ? ' aria-hidden="true" inert' : '') + '>' +
      '<span class="ilp-card__kicker">' + (isWord ? 'TỪ VÍ DỤ KANA' : esc(scriptLabel(card.character))) + '</span>' +
      '<h2 class="ilp-card__word' + (isWord ? '' : ' ilp-card__word--kanji') + '">' + esc(card.character) + '</h2>' +
      '<button class="ilp-study-audio" type="button" data-pronounce="' + esc(card.character) + '" aria-label="Nghe phát âm ' + esc(card.character) + '">🔊</button>' +
      '<span class="ilp-card__hint">Chạm vào thẻ để xem cách đọc</span></div>' +
      '<div class="ilp-card__back"' + (!card.flipped ? ' aria-hidden="true" inert' : '') + '>' +
        '<span class="ilp-card__kicker">CÁCH ĐỌC</span>' +
        '<span class="ilp-card__meaning">' + esc(card.romaji) + '</span>' +
      (isWord && card.meaningVi ? '<span class="ilp-card__translation">' + esc(card.meaningVi) + '</span>' : '') +
      '<span class="ilp-card__reading">' + esc(card.character) + '</span>' +
      '<button class="ilp-study-audio" type="button" data-pronounce="' + esc(card.character) + '" aria-label="Nghe phát âm ' + esc(card.character) + '">🔊</button>' +
        '</div></div>';
  }

  function renderKanaList(cards, kind) {
    if (!cards.length) return '<p class="ilp-state">Chưa có nội dung trong phần học này.</p>';
    return '<ul class="ilp-kana-list' + (kind === 'EXAMPLE_WORD' ? ' ilp-kana-list--words' : '') + '" data-kana-list>' + cards.map(function (card) {
      return '<li class="ilp-kana-list__item"><span class="ilp-kana-list__glyph">' + esc(card.character) + '</span>' +
        '<span class="ilp-kana-list__reading">' + esc(card.romaji) + '</span>' +
        (kind === 'EXAMPLE_WORD' ? '<span class="ilp-kana-list__meaning">' + esc(card.meaningVi) + '</span>' : '') +
        '<button class="ilp-study-audio" type="button" data-pronounce="' + esc(card.character) + '" aria-label="Nghe phát âm ' + esc(card.character) + '">🔊</button></li>';
    }).join('') + '</ul>';
  }

  function renderStudy(v, gate, activeTab, ui) {
    activeTab = activeTab === 'flashcard' ? 'flashcard' : 'content';
    ui = ui || {};
    var readOnly = !!ui.readOnly;
    var isWord = v.contentKind === 'EXAMPLE_WORD';
    var heading = isWord ? (v.lessonTitle || 'Từ ví dụ Kana') : 'Bảng chữ cái';
    var flashMode = readOnly ? 'browse' : (ui.mode === 'check' ? 'check' : 'browse');
    var firstUnseen = v.cards.findIndex(function (item) { return !item.flipped; });
    var browseIndex = ui.browseIndex == null ? Math.max(0, firstUnseen) : num(ui.browseIndex);
    browseIndex = Math.min(Math.max(browseIndex, 0), Math.max(v.cards.length - 1, 0));
    var card = v.current;
    var position = StudyModeLib.positionHeader(activeTab === 'flashcard' && flashMode !== 'check', browseIndex + 1, v.cards.length);
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = !readOnly && v.checkEligible && !gateBlocked && !(gate && gate.passed);
    var actions = '';
    if (card && !readOnly) {
      actions = card.flipped
        ? '<button class="ilp-action ilp-action--forget" type="button" data-remember="' + card.memberId + '" data-value="false">Chưa nhớ</button>' +
          '<button class="ilp-action ilp-action--know" type="button" data-remember="' + card.memberId + '" data-value="true">Đã nhớ</button>'
        : '<span class="ilp-card__hint">Chạm vào thẻ để xem cách đọc</span>';
    }
    var gateNote = !readOnly && gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    return '<section class="ilp-study">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">' + (isWord ? 'TỪ VÍ DỤ KANA' : 'BẢNG CHỮ CÁI') + ' // ' +
      (gate && gate.passed ? 'ĐÃ HOÀN THÀNH' : (v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG ÔN')) + '</span><h1>' + esc(heading) + '</h1>' + partLabel(v) +
      '<p>' + (isWord ? esc(v.lessonDescription || 'Đọc cả từ, nghe phát âm rồi luyện bằng Flashcard.') : 'Xem bảng chữ theo thứ tự, nghe cách đọc rồi lật thẻ để kiểm tra cách đọc.') + '</p>' +
      // Owner 2026-10-21 (OD-33): the Kana rule examples are J-LAS-written (AI) and still await review.
      (isWord ? '<p class="ilp-source-note">Nội dung bổ sung J-LAS · cần rà soát</p>' : '') +
      (readOnly ? '' : StudyModeLib.completionGuide('CHECK', isWord ? 'từ' : 'chữ', gate,
        v.activityState === 'PASSED' || v.activityState === 'COMPLETED')) + '</div>' +
      position.count + '</header>' + position.bar +
      '<div class="ilp-study-tabs" role="tablist" aria-label="' + (isWord ? 'Cách học từ ví dụ Kana' : 'Cách học bảng chữ cái') + '">' +
      '<button type="button" role="tab" id="kana-content-tab" aria-controls="kana-content-panel" aria-selected="' + (activeTab === 'content') + '" data-study-tab="content">Nội dung bài học</button>' +
      '<button type="button" role="tab" id="kana-flashcard-tab" aria-controls="kana-flashcard-panel" aria-selected="' + (activeTab === 'flashcard') + '" data-study-tab="flashcard">Học bằng Flashcard</button></div>' +
      '<section id="kana-content-panel" role="tabpanel" aria-labelledby="kana-content-tab"' + (activeTab === 'content' ? '' : ' hidden') + ' class="ilp-kana-content">' +
      '<h2>' + (isWord ? 'Các từ trong phần này' : 'Các chữ trong phần này') + '</h2><p>' +
      (isWord ? 'Nghe cả từ để nhận ra âm và kiểm tra cách đọc.' : 'Nghe từng chữ để làm quen với âm đọc.') + '</p>' +
      (gate && gate.passed ? '<p class="ilp-result__pass">Bạn đã hoàn thành phần này. Có thể xem lại nội dung bất cứ lúc nào.</p>' : '') +
      renderKanaList(v.cards, v.contentKind) +
      // [JLAS-W0193] §2.6 — the quiz entry must be visible on the content tab too, not only inside the
      // flashcard panel; hidden entirely once the activity is passed or the view is read-only (review).
      (readOnly || gate && gate.passed ? '' : '<div class="ilp-study__next">' +
        '<button class="ilp-check" type="button" data-study-tab="flashcard">Học bằng Flashcard</button>' +
        '<button class="ilp-check" type="button" data-check>' + checkButtonLabel(gate) + '</button>' +
        '<p>Học thẻ rồi kiểm tra để hoàn thành phần này. Bạn cũng có thể kiểm tra ngay.</p></div>') + '</section>' +
      '<section id="kana-flashcard-panel" role="tabpanel" aria-labelledby="kana-flashcard-tab"' + (activeTab === 'flashcard' ? '' : ' hidden') + ' class="ilp-kana-flashcards">' +
      renderFlashModes(v, card, flashMode, browseIndex, ui, actions, gateNote, gate, canCheck) + '</section>' +
      '</section>';
  }

  // Flashcards have only two learner actions: browse/flip and open the official quiz.
  function renderFlashModes(v, card, flashMode, browseIndex, ui, actions, gateNote, gate, canCheck) {
    var checkButton = ui.readOnly || gate && gate.passed ? '' : '<button class="ilp-check" type="button" data-check>Bắt đầu kiểm tra →</button>';
    if (flashMode === 'check') {
      return '<div class="ilp-card-mode-check"><p>' + (gate && gate.passed ? 'Bạn đã đạt bài kiểm tra của phần này.'
        : canCheck ? 'Đã lướt hết thẻ. Bạn có thể bắt đầu kiểm tra.' : 'Bạn có thể kiểm tra ngay hoặc lướt hết thẻ trước.') + '</p>' + gateNote + checkButton + '</div>';
    }
    var shown = v.cards[browseIndex] || null;
    if (flashMode === 'browse') {
      var face = shown ? Object.assign({}, shown, { flipped: !!ui.browseFlipped }) : null;
      return '<article class="ilp-card' + (face && face.flipped ? ' is-flipped' : '') + (ui.turning ? ' is-turning' : '') + '"' +
        (face ? (ui.readOnly ? ' data-browse-card tabindex="0" role="button" aria-label="Thẻ; chạm hoặc nhấn Enter để lật"' :
          !(gate && gate.passed) ? ' data-card="' + face.memberId + '" tabindex="0" role="button" aria-label="Thẻ; chạm hoặc nhấn Enter để lật"' : '') : '') + '>' +
        cardFace(face) + '</article>' + browseNav(v, browseIndex) + gateNote + checkButton;
    }
    return '';
  }

  function browseNav(v, browseIndex) {
    return '<div class="ilp-browse-nav"><button type="button" data-browse-step="-1"' + (browseIndex === 0 ? ' disabled' : '') + '>← Trước</button>' +
      '<span>' + (v.cards.length ? browseIndex + 1 : 0) + '/' + v.cards.length + '</span>' +
      '<button type="button" data-browse-step="1"' + (browseIndex >= v.cards.length - 1 ? ' disabled' : '') + '>Tiếp →</button></div>';
  }

  function renderCheck(check) {
    // [JLAS-W0113] Restore previously autosaved selections (questionId -> selectedOption) so a reopen/reload
    // shows the learner's saved choices instead of a blank quiz.
    var selectedByQuestion = {};
    list(check.draftAnswers).forEach(function (d) {
      if (d && d.selectedOption != null) selectedByQuestion[String(d.questionId)] = Number(d.selectedOption);
    });
    var isWord = list(check.questions).some(function (q) { return q.questionType === 'WORD_TO_ROMAJI' || q.questionType === 'ROMAJI_TO_WORD'; });
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
      '<h1>Kiểm tra nhanh ' + (isWord ? 'từ ví dụ Kana' : 'Bảng chữ cái') + '</h1><p>Chọn đáp án đúng cho từng câu rồi nộp.</p></div></header>' +
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

  function renderResult(result, context) {
    context = context || {};
    var passed = !!result.passed;
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
        (result.reviewRequired ? '<p>Hãy ôn lại các ' + (context.contentKind === 'EXAMPLE_WORD' ? 'từ' : 'chữ') + ' đã sai trước khi thử lại.</p>' : '') +
        (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
        '</div>') +
      (wrong.length ? '<p class="ilp-review-summary">Có ' + wrong.length + ' câu cần xem lại. Chi tiết câu hỏi và đáp án nằm trong lịch sử lượt làm bên dưới.</p>' : '') +
      (context.attemptHistoryHtml || '') +
      '<div class="ilp-result__actions">' +
      (nextBatch ? '<button class="ilp-check" type="button" data-check-next>Làm tiếp phần kiểm tra →</button>' : '') +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      (num(context.activityId) > 0 ? '<a class="ilp-check ilp-check--ghost" href="/learning/v2/foundation?activity=' +
        encodeURIComponent(context.activityId) + '&amp;mode=review&amp;tab=content">Xem lại bài học</a>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  // [JLAS-W0108] Task 11 - REVIEW: the server's read-only view, rendered with no flip/remember/check controls.
  function renderReview(v, activeTab, ui) {
    return '<p class="ilp-mode-banner" role="note" data-mode-banner="REVIEW"><strong>Chế độ xem lại</strong> — chỉ xem, không ghi nhận tiến độ.</p>' +
      renderStudy(v, null, activeTab || 'content', Object.assign({}, ui || {}, { mode: 'browse', readOnly: true }));
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở phần Bảng chữ cái</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p>' +
      '<button data-retry type="button">Thử lại</button></section>';
  }

  function mount() {
    var node = document.getElementById('interactiveFoundationStudyRoot');
    var id = activityId();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/v2/foundation' + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động Bảng chữ cái.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/foundation';
    // [JLAS-W0108] Task 11 - LEARN / REVIEW / PRACTICE. The server enforces each mode; the page only picks endpoints.
    var StudyMode = window.InteractiveStudyMode;
    if (StudyMode && StudyMode.bindNextAction) StudyMode.bindNextAction(node);
    if (StudyMode && StudyMode.bindAttemptHistory) StudyMode.bindAttemptHistory(node);
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var modeApi = StudyMode ? StudyMode.endpoints(mode) : StudyMode;
    var issuePath = (modeApi && modeApi.issue) || '/micro-check';
    var submitPath = (modeApi && modeApi.submit) || '/micro-check/submit';
    var selectedTab = new URLSearchParams(window.location.search).get('tab') === 'flashcard' ? 'flashcard' : 'content';
    var currentStudy = null;
    var currentGate = null;
    var flashUi = { mode: 'browse', browseIndex: null, browseFlipped: false, turning: false, typedFeedback: '' };
    var flipPending = false;
    // [JLAS-W0113] Autosave state (same contract as interactive-study.js): drafts only ever autosave in LEARN
    // mode; PRACTICE and REVIEW replays are ephemeral and never write a draft.
    var draftPath = '/micro-check/draft';
    var draftTimer = null;
    var draftRetries = 0;
    var draftDirty = false;
    var draftInFlight = null;
    var draftSeq = 0;
    function updateLessonHeading() {
      if (!currentStudy) return;
      var title = currentStudy.contentKind === 'EXAMPLE_WORD'
        ? (currentStudy.lessonTitle || 'Từ ví dụ Kana') : 'Bảng chữ cái';
      var breadcrumb = document.querySelector('.ilp-breadcrumb strong[aria-current="page"]');
      if (breadcrumb) breadcrumb.textContent = title;
      document.title = title + ' - JLAS';
    }
    function showStudy() {
      if (!currentStudy) return;
      updateLessonHeading();
      node.innerHTML = mode === 'REVIEW'
        ? renderReview(viewModel(currentStudy), selectedTab, flashUi)
        : (mode === 'PRACTICE' ? StudyMode.banner('PRACTICE') : '') +
          renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
    }

    async function api(path, options) {
      var response = await fetch(base + path, Object.assign({ headers: window.JlasAuth.authHeaders() }, options));
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
      return body.data;
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

    async function loadStudy() {
      node.setAttribute('aria-busy', 'true');
      try {
        if (mode === 'RESULTS') {
          await StudyMode.loadSavedResults(node, api, '/learning/v2/foundation', id);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'REVIEW') {
          currentStudy = await api(modeApi.study);
          showStudy();
          // [JLAS-W0193] §2.23 — a review reopened mid-check must still offer the remaining batch.
          var gate = null;
          if (currentStudy && currentStudy.checkRequired !== false) {
            try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
          }
          if (gate && !gate.passed) node.insertAdjacentHTML('beforeend', StudyMode.reviewGateActions(gate, '/learning/v2/foundation', id));
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'PRACTICE') {
          var practiceSource = await api('/review');
          if (practiceSource.activityState !== 'PASSED' && practiceSource.activityState !== 'COMPLETED') {
            node.innerHTML = '<section class="ilp-state"><h1>Bài học chưa hoàn thành</h1>' +
              '<p>Hoàn thành bài học và kiểm tra trước khi ôn lại.</p>' +
              '<a class="ilp-check" href="/learning/v2/foundation?activity=' + encodeURIComponent(id) +
              '">Tiếp tục học</a></section>';
            node.setAttribute('aria-busy', 'false');
            return;
          }
          currentStudy = StudyMode.rehearsal(practiceSource);
          currentGate = null;
          selectedTab = 'flashcard';
          flashUi.mode = 'browse';
          showStudy();
          node.setAttribute('aria-busy', 'false');
          return;
        }
        currentStudy = await api('/study');
        currentGate = null;
        try { currentGate = await api('/micro-check/state'); } catch (e) { currentGate = null; }
        // Owner 2026-10-21: a finished part reopens on its own page, never on an empty result screen.
        showStudy();
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doPractice() {
      try { node.innerHTML = StudyMode.banner('PRACTICE') + renderCheck(await api(issuePath, { method: 'POST' })); }
      catch (e) { node.innerHTML = error(e.message); }
    }

    async function revealCard(cardId) {
      if (flipPending || !currentStudy) return;
      var cards = viewModel(currentStudy).cards;
      var index = cards.findIndex(function (item) { return String(item.memberId) === String(cardId); });
      if (index < 0) return;
      flashUi.browseIndex = index;
      if (!flashUi.browseFlipped && !cards[index].flipped) {
        flipPending = true;
        try {
          currentStudy = mode === 'PRACTICE'
            ? StudyMode.rehearseCard(currentStudy, cardId, 'flip')
            : await api('/cards/' + encodeURIComponent(cardId) + '/flip', { method: 'POST' });
        } catch (e) { node.innerHTML = error(e.message); return; }
        finally { flipPending = false; }
      }
      flashUi.browseFlipped = !flashUi.browseFlipped;
      flashUi.turning = true;
      showStudy();
    }

    async function doCheck() {
      try { node.innerHTML = renderCheck(await api(issuePath, { method: 'POST' })); }
      catch (e) { node.innerHTML = error(e.message); }
    }

    node.addEventListener('click', async function (event) {
      if (StudyMode && StudyMode.isSelectingText && StudyMode.isSelectingText(node)) return; // a lookup selection, not a click
      if (event.target.closest('[data-retry]')) return loadStudy();
      var tab = event.target.closest('[data-study-tab]');
      if (tab && currentStudy) {
        selectedTab = tab.dataset.studyTab;
        showStudy();
        return;
      }
      var audio = event.target.closest('[data-pronounce]');
      if (audio) { event.stopPropagation(); speakJapanese(audio.dataset.pronounce); return; }
      if (event.target.closest('[data-browse-card]')) { flashUi.browseFlipped = !flashUi.browseFlipped; flashUi.turning = true; return showStudy(); }
      var cardMode = event.target.closest('[data-card-mode]');
      if (cardMode) {
        flashUi.mode = cardMode.dataset.cardMode;
        flashUi.typedFeedback = '';
        flashUi.browseFlipped = false;
        flashUi.turning = false;
        return showStudy();
      }
      var browseStep = event.target.closest('[data-browse-step]');
      if (browseStep && currentStudy) {
        var cards = viewModel(currentStudy).cards;
        var firstUnseen = cards.findIndex(function (item) { return !item.flipped; });
        var from = flashUi.browseIndex == null ? Math.max(0, firstUnseen) : flashUi.browseIndex;
        flashUi.browseIndex = Math.min(Math.max(from + Number(browseStep.dataset.browseStep), 0), Math.max(cards.length - 1, 0));
        flashUi.browseFlipped = false;
        flashUi.turning = false;
        flashUi.typedFeedback = '';
        return showStudy();
      }
      if (event.target.closest('[data-practice-review]')) {
        // A new practice round starts on the first card, front side up.
        flashUi.browseIndex = null; flashUi.browseFlipped = false; flashUi.turning = false;
        return loadStudy();
      }
      if (event.target.closest('[data-check-next]')) return doCheck();
      if (event.target.closest('[data-check]')) {
        if (mode === 'LEARN' && currentStudy && currentStudy.flippedCount < currentStudy.totalCards &&
            !(await window.JlasConfirm('Bạn chưa lướt hết các thẻ. Chọn OK để vẫn kiểm tra; chọn Hủy để lướt ôn tập hết thẻ.'))) return;
        return doCheck();
      }
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var flipCard = event.target.closest('[data-card]');
      if (flipCard) {
        return revealCard(flipCard.dataset.card);
      }
      var remember = event.target.closest('[data-remember]');
      if (remember) {
        var remembered = remember.dataset.value === 'true';
        try {
          currentStudy = mode === 'PRACTICE'
            ? StudyMode.rehearseCard(currentStudy, remember.dataset.remember, 'remember', remembered)
            : await api('/cards/' + encodeURIComponent(remember.dataset.remember) + '/remember?remembered=' + remembered, { method: 'POST' });
          showStudy();
        }
        catch (e) { node.innerHTML = error(e.message); }
        return;
      }
    });

    node.addEventListener('keydown', async function (event) {
      var browseCard = event.target.closest('[data-browse-card]');
      if (browseCard && event.target === browseCard && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault();
        flashUi.browseFlipped = !flashUi.browseFlipped;
        flashUi.turning = true;
        showStudy();
        var again = node.querySelector('[data-browse-card]');
        if (again) again.focus();
        return;
      }
      var card = event.target.closest('[data-card]');
      if (!card || event.target !== card || (event.key !== 'Enter' && event.key !== ' ')) return;
      event.preventDefault();
      await revealCard(card.dataset.card);
      var focusedCard = node.querySelector('[data-card]');
      if (focusedCard) focusedCard.focus();
    });

    node.addEventListener('submit', async function (event) {
      var typed = event.target.closest('[data-typed-recall]');
      if (typed) {
        event.preventDefault();
        var answer = currentStudy ? viewModel(currentStudy).cards[flashUi.browseIndex] : null;
        flashUi.typedFeedback = answer && checkTypedRomaji(answer.romaji, typed.querySelector('input').value)
          ? 'Đúng rồi! Đây chỉ là lượt luyện phản xạ.' : 'Chưa đúng. Hãy nghe lại ' +
          (currentStudy && currentStudy.contentKind === 'EXAMPLE_WORD' ? 'từ' : 'chữ') + ' này rồi thử tiếp.';
        showStudy();
        var input = node.querySelector('#ilp-typed-romaji');
        if (input) input.focus();
        return;
      }
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
        var result = await api(submitPath, {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ attemptId: Number(form.dataset.attemptId), answers: answers })
        });
        node.innerHTML = mode === 'PRACTICE' ? StudyMode.practiceResult(result) : renderResult(result,
          { activityId: id, contentKind: currentStudy && currentStudy.contentKind });
        if (StudyMode && StudyMode.attemptHistory) {
          try {
            var attempts = await api('/attempt-history');
            var resultPanel = node.querySelector('.ilp-result');
            if (resultPanel && attempts && attempts.length)
              resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
          } catch (historyError) { /* Keep the scored result usable if optional history is unavailable. */ }
        }
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction) {
          await StudyMode.showNextAction(node, id);
          if (StudyMode.celebrate) StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã vượt qua phần ' + 'Bảng chữ cái' + '.', activityId: id });
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

  return { viewModel: viewModel, cardFace: cardFace, scriptLabel: scriptLabel, renderKanaList: renderKanaList,
    speakJapanese: speakJapanese, renderStudy: renderStudy, renderReview: renderReview, checkTypedRomaji: checkTypedRomaji,
    renderCheck: renderCheck, renderResult: renderResult, mount: mount };
});
