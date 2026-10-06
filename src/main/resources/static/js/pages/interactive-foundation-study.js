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
    var cards = list(data.cards).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    var current = null;
    for (var i = 0; i < cards.length; i++) { if (!cards[i].remembered) { current = cards[i]; break; } }
    if (!current && cards.length) current = cards[0];
    return {
      activityId: data.activityId, partIndex: num(data.partIndex), partCount: num(data.partCount),
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
    if (card.flipped) {
      return '<div class="ilp-card__back">' +
        '<span class="ilp-card__kicker">CÁCH ĐỌC</span>' +
        '<span class="ilp-card__meaning">' + esc(card.romaji) + '</span>' +
      '<span class="ilp-card__reading">' + esc(card.character) + '</span>' +
      '<button class="ilp-study-audio" type="button" data-pronounce="' + esc(card.character) + '" aria-label="Nghe phát âm ' + esc(card.character) + '">🔊</button>' +
        '</div>';
    }
    return '<div class="ilp-card__front">' +
      '<span class="ilp-card__kicker">' + esc(scriptLabel(card.character)) + '</span>' +
      '<h2 class="ilp-card__word ilp-card__word--kanji">' + esc(card.character) + '</h2>' +
      '<button class="ilp-study-audio" type="button" data-pronounce="' + esc(card.character) + '" aria-label="Nghe phát âm ' + esc(card.character) + '">🔊</button>' +
      '<span class="ilp-card__hint">Chạm vào thẻ để xem cách đọc</span>' +
      '</div>';
  }

  function renderKanaList(cards) {
    if (!cards.length) return '<p class="ilp-state">Chưa có chữ cái trong phần học này.</p>';
    return '<ul class="ilp-kana-list" data-kana-list>' + cards.map(function (card) {
      return '<li class="ilp-kana-list__item"><span class="ilp-kana-list__glyph">' + esc(card.character) + '</span>' +
        '<span class="ilp-kana-list__reading">' + esc(card.romaji) + '</span>' +
        '<button class="ilp-study-audio" type="button" data-pronounce="' + esc(card.character) + '" aria-label="Nghe phát âm ' + esc(card.character) + '">🔊</button></li>';
    }).join('') + '</ul>';
  }

  var FLASH_MODES = [['browse', 'Lướt thẻ'], ['remember', 'Ghi nhớ'], ['check', 'Kiểm tra'], ['type', 'Gõ phản xạ']];

  function renderStudy(v, gate, activeTab, ui) {
    activeTab = activeTab === 'flashcard' ? 'flashcard' : 'content';
    ui = ui || {};
    var flashMode = FLASH_MODES.some(function (m) { return m[0] === ui.mode; }) ? ui.mode : 'remember';
    var browseIndex = Math.min(Math.max(num(ui.browseIndex), 0), Math.max(v.cards.length - 1, 0));
    var card = v.current;
    var progress = v.totalCards ? Math.round(v.rememberedCount / v.totalCards * 100) : 0;
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkEligible && !gateBlocked && !(gate && gate.passed);
    var actions = '';
    if (card) {
      actions = card.flipped
        ? '<button class="ilp-action ilp-action--forget" type="button" data-remember="' + card.memberId + '" data-value="false">Chưa nhớ</button>' +
          '<button class="ilp-action ilp-action--know" type="button" data-remember="' + card.memberId + '" data-value="true">Đã nhớ</button>'
        : '<span class="ilp-card__hint">Chạm vào thẻ để xem cách đọc</span>';
    }
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    return '<section class="ilp-study">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">BẢNG CHỮ CÁI // ' +
      (gate && gate.passed ? 'ĐÃ HOÀN THÀNH' : (v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG ÔN')) + '</span><h1>Bảng chữ cái</h1>' + partLabel(v) +
      '<p>Xem bảng chữ theo thứ tự, nghe cách đọc rồi chuyển sang Flashcard để ghi nhớ.</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.rememberedCount + '/' + v.totalCards + '</strong><span>đã nhớ</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.rememberedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalCards + '">' +
      '<span style="width:' + progress + '%"></span></div>' +
      '<div class="ilp-study-tabs" role="tablist" aria-label="Cách học bảng chữ cái">' +
      '<button type="button" role="tab" id="kana-content-tab" aria-controls="kana-content-panel" aria-selected="' + (activeTab === 'content') + '" data-study-tab="content">Nội dung bài học</button>' +
      '<button type="button" role="tab" id="kana-flashcard-tab" aria-controls="kana-flashcard-panel" aria-selected="' + (activeTab === 'flashcard') + '" data-study-tab="flashcard">Học bằng Flashcard</button></div>' +
      '<section id="kana-content-panel" role="tabpanel" aria-labelledby="kana-content-tab"' + (activeTab === 'content' ? '' : ' hidden') + ' class="ilp-kana-content">' +
      '<h2>Các chữ trong phần này</h2><p>Nghe từng chữ để làm quen với âm đọc.</p>' +
      (gate && gate.passed ? '<p class="ilp-result__pass">Bạn đã hoàn thành phần này. Có thể xem lại nội dung bất cứ lúc nào.</p>' : '') +
      renderKanaList(v.cards) + '</section>' +
      '<section id="kana-flashcard-panel" role="tabpanel" aria-labelledby="kana-flashcard-tab"' + (activeTab === 'flashcard' ? '' : ' hidden') + ' class="ilp-kana-flashcards">' +
      renderFlashModes(v, card, flashMode, browseIndex, ui, actions, gateNote, gate, canCheck) + '</section>' +
      '</section>';
  }

  // Same four modes as Vocabulary/Kanji. Only "Ghi nhớ" writes flip/remember evidence and only "Kiểm tra"
  // opens the official quiz; "Lướt thẻ" flips locally and "Gõ phản xạ" is practice that is never scored.
  function renderFlashModes(v, card, flashMode, browseIndex, ui, actions, gateNote, gate, canCheck) {
    var nav = '<nav class="ilp-card-modes" aria-label="Cách luyện Flashcard">' + FLASH_MODES.map(function (m) {
      return '<button type="button" data-card-mode="' + m[0] + '" aria-current="' + (flashMode === m[0]) + '">' + m[1] + '</button>';
    }).join('') + '</nav>';
    var checkButton = gate && gate.passed ? '' : '<button class="ilp-check" type="button" data-check' + (canCheck ? '' : ' disabled') +
      '>Bắt đầu kiểm tra <span aria-hidden="true">→</span></button>';
    if (flashMode === 'check') {
      return nav + '<div class="ilp-card-mode-check"><p>' + (gate && gate.passed ? 'Bạn đã đạt bài kiểm tra của phần này.'
        : canCheck ? 'Đã sẵn sàng làm bài kiểm tra nhỏ.' : 'Học và ghi nhớ các thẻ trước khi kiểm tra.') + '</p>' + gateNote + checkButton + '</div>';
    }
    var shown = v.cards[browseIndex] || null;
    if (flashMode === 'type') {
      return nav + '<div class="ilp-typing"><p>Nhìn chữ và gõ cách đọc bằng chữ La-tinh (romaji). Đây là lượt luyện riêng, không thay đổi kết quả quiz.</p>' +
        '<strong class="ilp-typing__glyph" lang="ja">' + esc(shown && shown.character) + '</strong>' +
        '<form data-typed-recall><label for="ilp-typed-romaji">Cách đọc</label><input id="ilp-typed-romaji" autocomplete="off" autocapitalize="off" spellcheck="false" required>' +
        '<button type="submit">Kiểm tra phản xạ</button></form>' +
        (ui.typedFeedback ? '<p role="status">' + esc(ui.typedFeedback) + '</p>' : '') + browseNav(v, browseIndex) + '</div>';
    }
    if (flashMode === 'browse') {
      var face = shown ? Object.assign({}, shown, { flipped: !!ui.browseFlipped }) : null;
      return nav + '<article class="ilp-card' + (face && face.flipped ? ' is-flipped' : '') + '"' +
        (face ? ' data-browse-card tabindex="0" role="button" aria-label="Thẻ chữ cái. Chạm hoặc nhấn Enter để lật"' : '') + '>' +
        cardFace(face) + '</article>' + browseNav(v, browseIndex);
    }
    return nav + '<article class="ilp-card' + (card && card.flipped ? ' is-flipped' : '') + '"' + (card ? ' data-card="' + card.memberId + '" tabindex="0" role="group" aria-label="Thẻ chữ cái. Chạm hoặc nhấn Enter để lật"' : '') + '>' + cardFace(card) + '</article>' +
      '<div class="ilp-card__actions">' + actions + '</div>' + gateNote + checkButton;
  }

  function browseNav(v, browseIndex) {
    return '<div class="ilp-browse-nav"><button type="button" data-browse-step="-1"' + (browseIndex === 0 ? ' disabled' : '') + '>← Trước</button>' +
      '<span>' + (v.cards.length ? browseIndex + 1 : 0) + '/' + v.cards.length + '</span>' +
      '<button type="button" data-browse-step="1"' + (browseIndex >= v.cards.length - 1 ? ' disabled' : '') + '>Tiếp →</button></div>';
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
      '<h1>Kiểm tra nhanh Bảng chữ cái</h1><p>Chọn đáp án đúng cho từng câu rồi nộp.</p></div></header>' +
      '<form class="ilp-check__form" data-submit data-attempt-id="' + esc(check.attemptId) + '">' + questions +
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
        (result.reviewRequired ? '<p>Hãy ôn lại các chữ đã sai trước khi thử lại.</p>' : '') +
        (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
        '</div>') +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions">' +
      (nextBatch ? '<button class="ilp-check" type="button" data-check-next>Làm tiếp phần kiểm tra →</button>' : '') +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      (num(context.activityId) > 0 ? '<a class="ilp-check ilp-check--ghost" href="/learning/v2/foundation?activity=' +
        encodeURIComponent(context.activityId) + '&amp;tab=content">Xem lại bài học</a>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  // [JLAS-W0108] Task 11 - REVIEW: the server's read-only view, rendered with no flip/remember/check controls.
  function renderReview(v, feedback) {
    return window.InteractiveStudyMode.reviewList('Bảng chữ cái đã học', v.cards.map(function (card) {
      return { html: '<article class="ilp-card is-flipped">' + cardFace(Object.assign({}, card, { flipped: true })) + '</article>' };
    }), feedback === null ? '' : window.InteractiveStudyMode.feedbackList(feedback));
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
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var modeApi = StudyMode ? StudyMode.endpoints(mode) : StudyMode;
    var issuePath = (modeApi && modeApi.issue) || '/micro-check';
    var submitPath = (modeApi && modeApi.submit) || '/micro-check/submit';
    var selectedTab = new URLSearchParams(window.location.search).get('tab') === 'flashcard' ? 'flashcard' : 'content';
    var currentStudy = null;
    var currentGate = null;
    var flashUi = { mode: 'remember', browseIndex: 0, browseFlipped: false, typedFeedback: '' };
    function showStudy() {
      if (currentStudy) node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
    }

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
        currentStudy = await api('/study');
        currentGate = null;
        try { currentGate = await api('/micro-check/state'); } catch (e) { currentGate = null; }
        node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
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
      var tab = event.target.closest('[data-study-tab]');
      if (tab && currentStudy) {
        selectedTab = tab.dataset.studyTab;
        node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
        return;
      }
      var audio = event.target.closest('[data-pronounce]');
      if (audio) { event.stopPropagation(); speakJapanese(audio.dataset.pronounce); return; }
      var cardMode = event.target.closest('[data-card-mode]');
      if (cardMode) {
        flashUi.mode = cardMode.dataset.cardMode;
        flashUi.typedFeedback = '';
        flashUi.browseFlipped = false;
        return showStudy();
      }
      var browseStep = event.target.closest('[data-browse-step]');
      if (browseStep && currentStudy) {
        var total = viewModel(currentStudy).cards.length;
        flashUi.browseIndex = Math.min(Math.max(flashUi.browseIndex + Number(browseStep.dataset.browseStep), 0), Math.max(total - 1, 0));
        flashUi.browseFlipped = false;
        flashUi.typedFeedback = '';
        return showStudy();
      }
      if (event.target.closest('[data-browse-card]')) {
        flashUi.browseFlipped = !flashUi.browseFlipped;
        return showStudy();
      }
      if (event.target.closest('[data-practice-again]')) return doPractice();
      if (event.target.closest('[data-check-next]')) return doCheck();
      if (event.target.closest('[data-check]')) return doCheck();
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var flipCard = event.target.closest('[data-card]');
      if (flipCard) {
        try {
          currentStudy = await api('/cards/' + encodeURIComponent(flipCard.dataset.card) + '/flip', { method: 'POST' });
          node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
        }
        catch (e) { node.innerHTML = error(e.message); }
        return;
      }
      var remember = event.target.closest('[data-remember]');
      if (remember) {
        var remembered = remember.dataset.value === 'true';
        try {
          currentStudy = await api('/cards/' + encodeURIComponent(remember.dataset.remember) + '/remember?remembered=' + remembered, { method: 'POST' });
          node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
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
        showStudy();
        var again = node.querySelector('[data-browse-card]');
        if (again) again.focus();
        return;
      }
      var card = event.target.closest('[data-card]');
      if (!card || event.target !== card || (event.key !== 'Enter' && event.key !== ' ')) return;
      event.preventDefault();
      try {
        currentStudy = await api('/cards/' + encodeURIComponent(card.dataset.card) + '/flip', { method: 'POST' });
        node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, flashUi);
      } catch (e) { node.innerHTML = error(e.message); }
    });

    node.addEventListener('submit', async function (event) {
      var typed = event.target.closest('[data-typed-recall]');
      if (typed) {
        event.preventDefault();
        var answer = currentStudy ? viewModel(currentStudy).cards[flashUi.browseIndex] : null;
        flashUi.typedFeedback = answer && checkTypedRomaji(answer.romaji, typed.querySelector('input').value)
          ? 'Đúng rồi! Đây chỉ là lượt luyện phản xạ.' : 'Chưa đúng. Hãy nghe lại chữ này rồi thử tiếp.';
        showStudy();
        var input = node.querySelector('#ilp-typed-romaji');
        if (input) input.focus();
        return;
      }
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
        node.innerHTML = mode === 'PRACTICE' ? StudyMode.practiceResult(result) : renderResult(result, { activityId: id });
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction)
          await StudyMode.showNextAction(node, id);
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    });

    loadStudy();
  }

  return { viewModel: viewModel, cardFace: cardFace, scriptLabel: scriptLabel, renderKanaList: renderKanaList,
    speakJapanese: speakJapanese, renderStudy: renderStudy, checkTypedRomaji: checkTypedRomaji,
    renderCheck: renderCheck, renderResult: renderResult, mount: mount };
});
