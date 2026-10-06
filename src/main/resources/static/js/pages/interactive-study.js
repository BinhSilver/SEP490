(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  // [JLAS-W0125] "Phần x / n" only when a pace change split this skill into ordered parts of the package.
  function partLabel(v) {
    return v.partCount > 1 && v.partIndex > 0
      ? '<p class="ilp-part-indicator">Phần ' + v.partIndex + ' / ' + v.partCount + '</p>' : '';
  }
  function checkTypedAnswer(expected, entered) {
    return String(expected || '').normalize('NFKC').trim() === String(entered || '').normalize('NFKC').trim()
      && String(expected || '').trim().length > 0;
  }
  function speakJapanese(value) {
    if (typeof window === 'undefined' || !window.speechSynthesis || !window.SpeechSynthesisUtterance) return false;
    window.speechSynthesis.cancel();
    var utterance = new window.SpeechSynthesisUtterance(String(value || ''));
    utterance.lang = 'ja-JP';
    window.speechSynthesis.speak(utterance);
    return true;
  }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  // Study state -> view model. The current card is the first unremembered one (server-side review order).
  function viewModel(data) {
    data = data || {};
    var cards = (Array.isArray(data.cards) ? data.cards : []).slice()
      .sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
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
    if (!card) return '<div class="ilp-card__empty"><h2>Chưa có thẻ từ vựng</h2></div>';
    if (card.flipped) {
      return '<div class="ilp-card__back">' +
        '<span class="ilp-card__meaning">' + esc(card.meaning) + '</span>' +
        '<div class="ilp-card__chips">' + (card.wordType ? '<span class="ilp-chip">' + esc(card.wordType) + '</span>' : '') +
        (card.jlptLevel ? '<span class="ilp-chip">' + esc(card.jlptLevel) + '</span>' : '') + '</div>' +
        (card.exampleJa ? '<p class="ilp-card__example"><strong>' + esc(card.exampleJa) + '</strong>' +
          (card.exampleVi ? '<span>' + esc(card.exampleVi) + '</span>' : '') + '</p>' : '') +
        '<button class="ilp-study-audio" type="button" data-speak="' + esc(card.front) + '" aria-label="Nghe phát âm ' + esc(card.front) + '">🔊</button>' +
        '</div>';
    }
    return '<div class="ilp-card__front">' +
      '<span class="ilp-card__kicker">' + (card.wordType ? esc(card.wordType) : 'TỪ VỰNG') + '</span>' +
      '<h2 class="ilp-card__word">' + esc(card.front) + '</h2>' +
      '<span class="ilp-card__reading">' + esc(card.reading) + '</span>' +
      '<button class="ilp-study-audio" type="button" data-speak="' + esc(card.front) + '" aria-label="Nghe phát âm ' + esc(card.front) + '">🔊</button>' +
      '<span class="ilp-card__hint">Lật thẻ để xem nghĩa</span>' +
      '</div>';
  }

  function renderVocabularyList(cards) {
    return '<ol class="ilp-study-items">' + cards.map(function (card) {
      return '<li class="ilp-study-items__item"><span class="ilp-study-items__word">' + esc(card.front) + '</span>' +
        '<span class="ilp-study-items__reading">' + esc(card.reading) + '</span>' +
        '<span class="ilp-study-items__meaning">' + esc(card.meaning) + '</span>' +
        '<button class="ilp-study-audio" type="button" data-speak="' + esc(card.front) + '" aria-label="Nghe phát âm ' + esc(card.front) + '">🔊</button></li>';
    }).join('') + '</ol>';
  }

  function displayGroups(cards) {
    var count = Math.ceil(cards.length / 15);
    var result = [];
    for (var i = 0, offset = 0; i < count; i++) {
      var size = Math.floor(cards.length / count) + (i < cards.length % count ? 1 : 0);
      result.push(cards.slice(offset, offset + size));
      offset += size;
    }
    return result;
  }

  // Study view. `gate` is the optional server micro-check state; the check action only opens when the
  // server says every card is remembered AND the batch is attemptable (no cooldown / review pending).
  function renderStudy(v, gate, activeTab, partIndex, ui) {
    activeTab = activeTab === 'flashcards' ? 'flashcards' : 'content';
    ui = ui || {};
    var flashMode = ['browse', 'remember', 'check', 'type'].includes(ui.mode) ? ui.mode : 'remember';
    var groups = displayGroups(v.cards);
    partIndex = Math.min(Math.max(num(partIndex), 0), Math.max(groups.length - 1, 0));
    var browseIndex = Math.min(Math.max(num(ui.browseIndex), 0), Math.max(v.cards.length - 1, 0));
    var card = flashMode === 'remember' ? v.current : v.cards[browseIndex] || null;
    var shownCard = card && flashMode === 'browse' ? Object.assign({}, card, { flipped: !!ui.browseFlipped }) : card;
    var progress = v.totalCards ? Math.round(v.rememberedCount / v.totalCards * 100) : 0;
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkEligible && !gateBlocked;
    var actions = '';
    if (card && flashMode === 'remember') {
      actions = gate && gate.passed ? '' : card.flipped
        ? '<button class="ilp-action ilp-action--forget" type="button" data-remember="' + card.memberId + '" data-value="false">Chưa nhớ</button>' +
          '<button class="ilp-action ilp-action--know" type="button" data-remember="' + card.memberId + '" data-value="true">Đã nhớ</button>'
        : '<span class="ilp-card__hint">Chạm vào thẻ để lật</span>';
    }
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    var modeNav = '<nav class="ilp-card-modes" aria-label="Cách luyện Flashcard">' +
      [['browse', 'Lướt thẻ'], ['remember', 'Ghi nhớ'], ['check', 'Kiểm tra'], ['type', 'Gõ phản xạ']].map(function (mode) {
        return '<button type="button" data-card-mode="' + mode[0] + '" aria-current="' + (flashMode === mode[0]) + '">' + mode[1] + '</button>';
      }).join('') + '</nav>';
    var flashContent = flashMode === 'type'
      ? '<div class="ilp-typing"><p>Nhìn nghĩa và gõ lại từ tiếng Nhật. Đây là lượt luyện riêng, không thay đổi kết quả quiz.</p>' +
        '<strong>' + esc(card && card.meaning) + '</strong>' +
        '<form data-typed-recall><label for="ilp-typed-word">Từ tiếng Nhật</label><input id="ilp-typed-word" autocomplete="off" lang="ja" required>' +
        '<button type="submit">Kiểm tra phản xạ</button></form>' +
        (ui.typedFeedback ? '<p role="status">' + esc(ui.typedFeedback) + '</p>' : '') + '</div>'
      : flashMode === 'check'
        ? '<div class="ilp-card-mode-check"><p>' + (canCheck ? 'Đã sẵn sàng làm bài kiểm tra nhỏ.' : 'Học và ghi nhớ các thẻ trước khi kiểm tra.') + '</p>' +
          '<button class="ilp-check" type="button" data-check' + (canCheck ? '' : ' disabled') + '>Bắt đầu kiểm tra →</button></div>'
        : '<article class="ilp-card' + (shownCard && (shownCard.flipped || gate && gate.passed && flashMode !== 'browse') ? ' is-flipped' : '') + '"' +
          (shownCard && (flashMode === 'browse' || !(gate && gate.passed)) ? ' data-card="' + shownCard.memberId + '" tabindex="0" role="button" aria-label="Thẻ từ vựng"' : '') + '>' +
          cardFace(shownCard && gate && gate.passed && flashMode !== 'browse' ? Object.assign({}, shownCard, { flipped: true }) : shownCard) + '</article>' +
          (flashMode === 'browse' ? '<div class="ilp-browse-nav"><button type="button" data-browse-step="-1"' + (browseIndex === 0 ? ' disabled' : '') + '>← Trước</button>' +
            '<span>' + (v.cards.length ? browseIndex + 1 : 0) + '/' + v.cards.length + '</span>' +
            '<button type="button" data-browse-step="1"' + (browseIndex >= v.cards.length - 1 ? ' disabled' : '') + '>Tiếp →</button></div>' :
            '<div class="ilp-card__actions">' + actions + '</div>' + gateNote +
            (gate && gate.passed ? '' : '<button class="ilp-check" type="button" data-check' + (canCheck ? '' : ' disabled') + '>Bắt đầu kiểm tra <span aria-hidden="true">→</span></button>'));
    return '<section class="ilp-study">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">VOCABULARY // ' +
      (gate && gate.passed ? 'ĐÃ HOÀN THÀNH' : v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG ÔN') + '</span><h1>Từ vựng</h1>' + partLabel(v) +
      '<p>Xem toàn bộ từ trong bài, nghe phát âm rồi học bằng Flashcard.</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.rememberedCount + '/' + v.totalCards + '</strong><span>đã nhớ</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.rememberedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalCards + '">' +
      '<span style="width:' + progress + '%"></span></div>' +
      '<div class="ilp-study-tabs" role="tablist" aria-label="Cách học từ vựng">' +
      '<button type="button" role="tab" aria-selected="' + (activeTab === 'content') + '" data-study-tab="content">Nội dung bài học</button>' +
      '<button type="button" role="tab" aria-selected="' + (activeTab === 'flashcards') + '" data-study-tab="flashcards">Học bằng Flashcard</button></div>' +
      '<section role="tabpanel"' + (activeTab === 'content' ? '' : ' hidden') + '><h2>Các từ trong phần này</h2>' +
      (groups.length > 1 ? '<div class="ilp-study-parts" aria-label="Các phần từ vựng">' + groups.map(function (_, index) {
        return '<button type="button" data-study-part="' + index + '" aria-current="' + (index === partIndex) + '">Phần ' + (index + 1) + '</button>';
      }).join('') + '</div>' : '') +
      renderVocabularyList(groups[partIndex] || []) + '</section>' +
      '<section role="tabpanel" class="ilp-flashcards"' + (activeTab === 'flashcards' ? '' : ' hidden') + '>' +
      modeNav + flashContent +
      '</section>' +
      '</section>';
  }

  function renderCheck(check) {
    var all = check.questions || [];
    var matching = all.filter(function (q) { return q.questionType === 'MATCHING'; });
    var canPair = matching.length >= 2 && matching.every(function (q) {
      return q.options && q.options.length === matching.length && q.options.every(function (option, index) {
        return option === matching[0].options[index];
      });
    });
    var questions = all.filter(function (q) { return !canPair || q.questionType !== 'MATCHING'; }).map(function (q, i) {
      var options = (q.options || []).map(function (opt, oi) {
        return '<label class="ilp-option"><input type="radio" name="q-' + q.questionId + '" value="' + (oi + 1) + '">' +
          '<span class="ilp-option__mark">' + String.fromCharCode(65 + oi) + '</span><span>' + esc(opt) + '</span></label>';
      }).join('');
      return '<fieldset class="ilp-question"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText) + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    if (canPair) {
      questions += '<fieldset class="ilp-matching"><legend>Nối từ ở cột trái với nghĩa ở cột phải</legend>' +
        '<p>Chọn một từ, sau đó chọn nghĩa tương ứng. Có thể chọn lại để sửa.</p>' +
        '<div class="ilp-matching__columns"><div class="ilp-matching__column"><h2>Từ vựng</h2>' +
        matching.map(function (q, i) { return '<button type="button" data-match-word="' + q.questionId + '" aria-pressed="false"><span>' + (i + 1) + '.</span> ' + esc(q.questionText) + '<small data-match-label="' + q.questionId + '">Chưa nối</small></button>'; }).join('') +
        '</div><div class="ilp-matching__column"><h2>Nghĩa</h2>' +
        matching[0].options.map(function (option, i) { return '<button type="button" data-match-meaning="' + (i + 1) + '"><span>' + String.fromCharCode(65 + i) + '.</span> ' + esc(option) + '</button>'; }).join('') +
        '</div></div><div class="ilp-matching__answers">' + matching.map(function (q) {
          return q.options.map(function (_, i) { return '<input type="radio" hidden name="q-' + q.questionId + '" value="' + (i + 1) + '">'; }).join('');
        }).join('') + '</div></fieldset>';
    }
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
    var nextBatch = passed && result.activityState === 'CHECK_READY';
    var wrong = (result.review || []).filter(function (r) { return r.correct === false; });
    var cooldown = result.activityState === 'COOLDOWN';
    return '<section class="ilp-result">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">KẾT QUẢ</span><h1>' + (passed ? 'Đã đạt' : 'Chưa đạt') + '</h1></div>' +
      '<div class="ilp-score' + (passed ? ' is-pass' : ' is-fail') + '"><strong>' + num(result.scorePercent) + '%</strong></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng · ngưỡng ' + num(result.passThresholdPercent) + '%</p>' +
      (passed ? '<p class="ilp-result__pass">' + (nextBatch
        ? 'Đã đạt phần kiểm tra này; còn phần kiểm tra tiếp theo để hoàn tất bài học.'
        : 'Tuyệt vời! Bạn đã hoàn thành phần này.') + '</p>' :
        '<div class="ilp-result__fail"><p>Lần thử ' + num(result.attemptsUsed) + '/3 · còn ' + num(result.attemptsRemaining) + ' lượt.</p>' +
        (result.reviewRequired ? '<p>Hãy ôn lại các từ đã sai trước khi thử lại.</p>' : '') +
        (cooldown ? '<p>Vui lòng quay lại sau <strong>' + esc(formatCooldown(result.cooldownUntil)) + '</strong>.</p>' : '') +
        '</div>') +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions">' +
      (nextBatch ? '<button class="ilp-check" type="button" data-check-next>Làm tiếp phần kiểm tra →</button>' : '') +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/v2/vocabulary?activity=' + encodeURIComponent(activityId() || '') + '&amp;tab=content">Xem lại bài học</a>' +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  // [JLAS-W0108] Task 11 - REVIEW: the server's read-only view, rendered with no flip/remember/check controls.
  function renderReview(v, feedback) {
    return window.InteractiveStudyMode.reviewList('Từ vựng đã học', v.cards.map(function (card) {
      return { html: '<article class="ilp-card is-flipped">' + cardFace(Object.assign({}, card, { flipped: true })) + '</article>' };
    }), feedback === null ? '' : window.InteractiveStudyMode.feedbackList(feedback));
  }

  function renderPassed(extra) {
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>Phần từ vựng đã đạt</h1></div></header>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>' + (extra || '') + '</div></section>';
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở phần từ vựng</h1><p>' + esc(message || 'Vui lòng thử lại sau.') + '</p>' +
      '<button data-retry type="button">Thử lại</button></section>';
  }

  function mount() {
    var node = document.getElementById('interactiveStudyRoot');
    var id = activityId();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/v2/vocabulary' + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động từ vựng.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/vocabulary';
    // [JLAS-W0108] Task 11 - LEARN / REVIEW / PRACTICE. The server enforces each mode; the page only picks endpoints.
    var StudyMode = window.InteractiveStudyMode;
    if (StudyMode && StudyMode.bindNextAction) StudyMode.bindNextAction(node);
    if (StudyMode && StudyMode.bindNextAction) StudyMode.bindNextAction(node);
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var modeApi = StudyMode ? StudyMode.endpoints(mode) : StudyMode;
    var issuePath = (modeApi && modeApi.issue) || '/micro-check';
    var submitPath = (modeApi && modeApi.submit) || '/micro-check/submit';
    var rehearsalState = null;
    var selectedTab = new URLSearchParams(window.location.search).get('tab') === 'flashcards' ? 'flashcards' : 'content';
    var currentStudy = null;
    var currentGate = null;
    var selectedWord = null;
    var selectedPart = 0;
    var flashUi = { mode: 'remember', browseIndex: 0, browseFlipped: false, typedFeedback: '' };
    function renderRehearsal() {
      node.innerHTML = StudyMode.banner('PRACTICE') + renderStudy(viewModel(rehearsalState), null, selectedTab, selectedPart, flashUi) +
        '<button class="ilp-check ilp-check--ghost" type="button" data-attempt-history>Xem lại bài kiểm tra</button>';
    }
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions('/learning/v2/vocabulary', id, ['REVIEW', 'PRACTICE']) : '';
    function showStudy() {
      if (mode === 'PRACTICE') return renderRehearsal();
      if (!currentStudy) return;
      node.innerHTML = renderStudy(viewModel(currentStudy), currentGate, selectedTab, selectedPart, flashUi) +
        (currentGate && currentGate.passed ? '<div class="ilp-result__actions">' + PASSED_ACTIONS + '</div>' : '');
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
          rehearsalState = StudyMode.rehearsal(await api('/review'));
          renderRehearsal();
          node.setAttribute('aria-busy', 'false');
          return;
        }
        currentStudy = await api('/study');
        currentGate = null;
        try { currentGate = await api('/micro-check/state'); } catch (e) { currentGate = null; }
        showStudy();
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
      var tab = event.target.closest('[data-study-tab]');
      if (tab) {
        selectedTab = tab.dataset.studyTab;
        showStudy();
        return;
      }
      var part = event.target.closest('[data-study-part]');
      if (part) {
        selectedPart = Number(part.dataset.studyPart);
        showStudy();
        return;
      }
      var cardMode = event.target.closest('[data-card-mode]');
      if (cardMode) {
        flashUi.mode = cardMode.dataset.cardMode;
        flashUi.typedFeedback = '';
        showStudy();
        return;
      }
      var browseStep = event.target.closest('[data-browse-step]');
      if (browseStep) {
        var cards = viewModel(mode === 'PRACTICE' ? rehearsalState : currentStudy).cards;
        flashUi.browseIndex = Math.min(Math.max(flashUi.browseIndex + Number(browseStep.dataset.browseStep), 0), Math.max(cards.length - 1, 0));
        flashUi.browseFlipped = false;
        showStudy();
        return;
      }
      var audio = event.target.closest('[data-speak]');
      if (audio) { event.stopPropagation(); speakJapanese(audio.dataset.speak); return; }
      var matchWord = event.target.closest('[data-match-word]');
      if (matchWord) {
        selectedWord = matchWord.dataset.matchWord;
        node.querySelectorAll('[data-match-word]').forEach(function (button) {
          button.setAttribute('aria-pressed', String(button === matchWord));
        });
        return;
      }
      var matchMeaning = event.target.closest('[data-match-meaning]');
      if (matchMeaning && selectedWord) {
        var form = matchMeaning.closest('form');
        var value = matchMeaning.dataset.matchMeaning;
        form.querySelectorAll('input[type="radio"]:checked').forEach(function (input) {
          if (input.value === value && input.name !== 'q-' + selectedWord) {
            input.checked = false;
            var oldLabel = form.querySelector('[data-match-label="' + input.name.slice(2) + '"]');
            if (oldLabel) oldLabel.textContent = 'Chưa nối';
          }
        });
        var radio = form.querySelector('input[name="q-' + selectedWord + '"][value="' + value + '"]');
        if (radio) radio.checked = true;
        var label = form.querySelector('[data-match-label="' + selectedWord + '"]');
        if (label) label.textContent = 'Đã nối với ' + matchMeaning.textContent.trim();
        selectedWord = null;
        form.querySelectorAll('[data-match-word]').forEach(function (button) { button.setAttribute('aria-pressed', 'false'); });
        return;
      }
      if (event.target.closest('[data-attempt-history]')) {
        try {
          node.innerHTML = StudyMode.feedbackList(await api('/review/attempts')) +
            '<button class="ilp-check" type="button" data-retry>Trở lại ôn bài</button>';
        } catch (e) { node.innerHTML = error(e.message); }
        return;
      }
      if (event.target.closest('[data-retry]')) return loadStudy();
      if (event.target.closest('[data-practice-again]')) return doPractice();
      if (event.target.closest('[data-check-next]')) return doCheck();
      if (event.target.closest('[data-check]')) return doCheck();
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        return loadStudy();
      }
      var flip = event.target.closest('[data-card]');
      if (flip) {
        if (flashUi.mode === 'browse') {
          flashUi.browseFlipped = !flashUi.browseFlipped;
          showStudy();
          return;
        }
        if (mode === 'PRACTICE') {
          StudyMode.rehearseCard(rehearsalState, flip.dataset.card, 'flip');
          renderRehearsal();
          return;
        }
        try { currentStudy = await api('/cards/' + encodeURIComponent(flip.dataset.card) + '/flip', { method: 'POST' });
          showStudy(); }
        catch (e) { node.innerHTML = error(e.message); }
        return;
      }
      var remember = event.target.closest('[data-remember]');
      if (remember) {
        var remembered = remember.dataset.value === 'true';
        if (mode === 'PRACTICE') {
          StudyMode.rehearseCard(rehearsalState, remember.dataset.remember, 'remember', remembered);
          renderRehearsal();
          return;
        }
        try { currentStudy = await api('/cards/' + encodeURIComponent(remember.dataset.remember) + '/remember?remembered=' + remembered, { method: 'POST' });
          showStudy(); }
        catch (e) { node.innerHTML = error(e.message); }
        return;
      }
    });

    node.addEventListener('keydown', function (event) {
      var card = event.target.closest('[data-card]');
      if (!card || event.target !== card || (event.key !== 'Enter' && event.key !== ' ')) return;
      event.preventDefault();
      card.click();
    });

    node.addEventListener('submit', async function (event) {
      var typed = event.target.closest('[data-typed-recall]');
      if (typed) {
        event.preventDefault();
        var cards = viewModel(mode === 'PRACTICE' ? rehearsalState : currentStudy).cards;
        var answer = cards[flashUi.browseIndex];
        flashUi.typedFeedback = answer && checkTypedAnswer(answer.front, typed.querySelector('input').value)
          ? 'Đúng rồi! Đây chỉ là lượt luyện phản xạ.' : 'Chưa đúng. Hãy xem lại từ rồi thử tiếp.';
        showStudy();
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
        node.innerHTML = mode === 'PRACTICE' ? StudyMode.practiceResult(result) : renderResult(result);
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction)
          await StudyMode.showNextAction(node, id);
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction)
          await StudyMode.showNextAction(node, id);
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    });

    loadStudy();
  }

  return { viewModel: viewModel, checkTypedAnswer: checkTypedAnswer, renderStudy: renderStudy, renderCheck: renderCheck, renderResult: renderResult, mount: mount };
});
