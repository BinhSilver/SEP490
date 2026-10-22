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
  // [JLAS-W0108] Quiz split into multiple parts: name the exact next part so "continue" clearly resumes
  // part n of total rather than a fresh quiz. `currentBatch` is the server's 0-based first unpassed batch.
  function checkButtonLabel(gate) {
    if (!gate) return 'Bắt đầu kiểm tra →';
    var total = num(gate.batchCount);
    if (total > 1) {
      var part = num(gate.currentBatch) + 1;
      return (num(gate.currentBatch) > 0 ? 'Tiếp tục kiểm tra' : 'Bắt đầu kiểm tra') + ' — phần ' + part + '/' + total;
    }
    return 'Bắt đầu kiểm tra →';
  }
  function checkTypedAnswer(expected, entered) {
    return String(expected || '').normalize('NFKC').trim() === String(entered || '').normalize('NFKC').trim()
      && String(expected || '').trim().length > 0;
  }
  function checkProgressText(answeredQuestions, totalQuestions, answeredPairs, totalPairs) {
    return 'Đã trả lời ' + answeredQuestions + '/' + totalQuestions + ' câu trắc nghiệm · ' +
      answeredPairs + '/' + totalPairs + ' cặp nối';
  }
  function shouldPersistFlip(mode, flashMode) {
    return mode !== 'REVIEW' && mode !== 'PRACTICE';
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
      checkRequired: data.checkRequired !== false,
      current: current,
      cards: cards
    };
  }

  function cardFace(card) {
    if (!card) return '<div class="ilp-card__empty"><h2>Chưa có thẻ từ vựng</h2></div>';
    return '<div class="ilp-card__inner">' +
      '<div class="ilp-card__front"' + (card.flipped ? ' aria-hidden="true" inert' : '') + '>' +
      '<span class="ilp-card__kicker">' + (card.wordType ? esc(card.wordType) : 'TỪ VỰNG') + '</span>' +
      '<h2 class="ilp-card__word">' + esc(card.front) + '</h2>' +
      '<span class="ilp-card__reading">' + esc(card.reading) + '</span>' +
      '<button class="ilp-study-audio" type="button" data-speak="' + esc(card.front) + '" aria-label="Nghe phát âm ' + esc(card.front) + '">🔊</button>' +
      '<span class="ilp-card__hint">Lật thẻ để xem nghĩa</span></div>' +
      '<div class="ilp-card__back"' + (!card.flipped ? ' aria-hidden="true" inert' : '') + '>' +
        '<span class="ilp-card__meaning">' + esc(card.meaning) + '</span>' +
        '<div class="ilp-card__chips">' + (card.wordType ? '<span class="ilp-chip">' + esc(card.wordType) + '</span>' : '') +
        (card.jlptLevel ? '<span class="ilp-chip">' + esc(card.jlptLevel) + '</span>' : '') + '</div>' +
        (card.exampleJa ? '<p class="ilp-card__example"><strong>' + esc(card.exampleJa) + '</strong>' +
          (card.exampleVi ? '<span>' + esc(card.exampleVi) + '</span>' : '') + '</p>' : '') +
        '<button class="ilp-study-audio" type="button" data-speak="' + esc(card.front) + '" aria-label="Nghe phát âm ' + esc(card.front) + '">🔊</button>' +
      '</div></div>';
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

  function partNavigation(groups, selected) {
    if (groups.length < 2) return '';
    return '<nav class="ilp-study-parts" aria-label="Chia phần nội dung">' + groups.map(function (_, index) {
      return '<button type="button" data-study-part="' + index + '" aria-current="' + (index === selected) + '">Phần ' +
        (index + 1) + ' / ' + groups.length + '</button>';
    }).join('') + '</nav>';
  }

  // Study view. `gate` is the optional server micro-check state; the check action only opens when the
  // server says every card is remembered AND the batch is attemptable (no cooldown / review pending).
  function studiedDone(v) { return v.activityState === 'PASSED' || v.activityState === 'COMPLETED'; }
  /** Owner 2026-10-21: communication vocabulary has no quiz; the learner may finish it here without flipping every card. */
  function markStudiedBlock(v) {
    return studiedDone(v)
      ? '<p class="ilp-study__done" data-studied-done>Bạn đã hoàn thành phần Từ vựng này. <a href="/learning/path/v2">Về lộ trình</a></p>'
      : '<button class="ilp-check" type="button" data-mark-studied>Đánh dấu hoàn thành</button>';
  }

  function renderStudy(v, gate, activeTab, partIndex, ui) {
    activeTab = activeTab === 'flashcards' ? 'flashcards' : 'content';
    ui = ui || {};
    var flashMode = ui.mode === 'check' ? 'check' : 'browse';
    var groups = displayGroups(v.cards);
    partIndex = Math.min(Math.max(num(partIndex), 0), Math.max(groups.length - 1, 0));
    var partCards = groups[partIndex] || [];
    var readOnly = !!ui.readOnly;
    if (readOnly) flashMode = 'browse';
    var firstUnseen = partCards.findIndex(function (item) { return !item.flipped; });
    var browseIndex = ui.browseIndex == null ? Math.max(0, firstUnseen) : num(ui.browseIndex);
    browseIndex = Math.min(Math.max(browseIndex, 0), Math.max(partCards.length - 1, 0));
    var card = flashMode === 'remember' ? (partCards.find(function (item) { return !item.remembered; }) || partCards[0] || null)
      : partCards[browseIndex] || null;
    var shownCard = card && flashMode === 'browse' ? Object.assign({}, card,
      { flipped: !!ui.browseFlipped }) : card;
    var progress = v.totalCards ? Math.round(v.flippedCount / v.totalCards * 100) : 0;
    var gateBlocked = gate && (gate.reviewRequired || !gate.checkEligible) && !gate.passed;
    var canCheck = v.checkRequired && v.checkEligible && !gateBlocked;
    var checkLabel = checkButtonLabel(gate);
    var actions = '';
    if (card && flashMode === 'remember' && !readOnly) {
      actions = gate && gate.passed ? '' : card.flipped
        ? '<button class="ilp-action ilp-action--forget" type="button" data-remember="' + card.memberId + '" data-value="false">Chưa nhớ</button>' +
          '<button class="ilp-action ilp-action--know" type="button" data-remember="' + card.memberId + '" data-value="true">Đã nhớ</button>'
        : '<span class="ilp-card__hint">Chạm vào thẻ để lật</span>';
    }
    var gateNote = gateBlocked
      ? '<p class="ilp-gate-note">' + (gate.reviewRequired ? 'Bạn cần ôn lại phần đã sai trước khi kiểm tra lại.' : 'Bạn đang trong thời gian chờ.') + '</p>'
      : '';
    var flashContent = v.checkRequired && flashMode === 'check'
      ? '<div class="ilp-card-mode-check"><p>' + (gate && gate.passed ? 'Bạn đã hoàn thành bài kiểm tra của phần này.' :
          canCheck ? 'Đã lướt hết thẻ. Bạn có thể bắt đầu kiểm tra.' : 'Bạn có thể kiểm tra ngay hoặc lướt hết thẻ trước.') + '</p>' + gateNote +
          (readOnly || gate && gate.passed ? '' : '<button class="ilp-check" type="button" data-check>' + checkLabel + '</button>') + '</div>'
      : '<article class="ilp-card' + (shownCard && shownCard.flipped ? ' is-flipped' : '') + (ui.turning ? ' is-turning' : '') + '"' +
          (shownCard ? (readOnly ? ' data-browse-card tabindex="0" role="button" aria-label="Thẻ từ vựng; chạm để lật"' :
            !(gate && gate.passed) ? ' data-card="' + shownCard.memberId + '" tabindex="0" role="button" aria-label="Thẻ từ vựng; chạm để lật"' : '') : '') + '>' +
          cardFace(shownCard) + '</article>' +
          '<div class="ilp-browse-nav"><button type="button" data-browse-step="-1"' + (browseIndex === 0 ? ' disabled' : '') + '>← Trước</button>' +
          '<span>' + (partCards.length ? browseIndex + 1 : 0) + '/' + partCards.length + ' trong phần này</span>' +
          '<button type="button" data-browse-step="1"' + (browseIndex >= partCards.length - 1 ? ' disabled' : '') + '>Tiếp →</button></div>' + gateNote +
          (readOnly || gate && gate.passed || !v.checkRequired ? '' : '<button class="ilp-check" type="button" data-check>' + checkLabel + '</button>') +
          (!readOnly && !v.checkRequired ? markStudiedBlock(v) : '');
    return '<section class="ilp-study">' +
      '<header class="ilp-study__head"><div>' + (v.checkRequired === false
        ? '' : '<span class="ilp-kicker">VOCABULARY // ' +
      (gate && gate.passed || v.activityState === 'PASSED' ? 'ĐÃ HOÀN THÀNH' : v.checkEligible ? 'SẴN SÀNG KIỂM TRA' : 'ĐANG ÔN') + '</span>') +
      '<h1>Từ vựng' + (v.checkRequired === false && studiedDone(v) ? ' <span class="ilp-done-pill">Đã hoàn thành</span>' : '') + '</h1>' + partLabel(v) +
      '<p>Xem toàn bộ từ trong bài, nghe phát âm rồi học bằng Flashcard.</p></div>' +
      '<div class="ilp-study__count"><strong>' + v.flippedCount + '/' + v.totalCards + '</strong><span>đã xem</span></div></header>' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.flippedCount + '" aria-valuemin="0" aria-valuemax="' + v.totalCards + '">' +
      '<span style="width:' + progress + '%"></span></div>' +
      '<div class="ilp-study-tabs" role="tablist" aria-label="Cách học từ vựng">' +
      '<button type="button" role="tab" aria-selected="' + (activeTab === 'content') + '" data-study-tab="content">Nội dung bài học</button>' +
      '<button type="button" role="tab" aria-selected="' + (activeTab === 'flashcards') + '" data-study-tab="flashcards">Học bằng Flashcard</button></div>' +
      '<section role="tabpanel"' + (activeTab === 'content' ? '' : ' hidden') + '><h2>Các từ trong phần này</h2>' +
      partNavigation(groups, partIndex) +
      renderVocabularyList(groups[partIndex] || []) +
      (readOnly || gate && gate.passed ? '' : '<div class="ilp-study__next"><button class="ilp-check" type="button" data-study-tab="flashcards">Học bằng Flashcard</button>' +
        (v.checkRequired ? '<button class="ilp-check" type="button" data-check>' + checkLabel + '</button><p>Học thẻ rồi kiểm tra để hoàn thành phần này. Bạn cũng có thể kiểm tra ngay.</p>' :
          '<p>Xem hết các thẻ để hoàn thành phần Từ vựng của tình huống.</p>') + '</div>') + '</section>' +
      '<section role="tabpanel" class="ilp-flashcards"' + (activeTab === 'flashcards' ? '' : ' hidden') + '>' +
      partNavigation(groups, partIndex) + flashContent +
      '</section>' +
      '</section>';
  }

  // [JLAS-W0108] Deterministic per-attempt column shuffle for MATCHING. Seeded by attemptId so a
  // re-render / reload / resume of the same attempt keeps the same order, while a new attempt reshuffles.
  // The answer key never reaches the client, so "avoid all-correct-aligned rows" is enforced only by the
  // server-side meaning rotation plus a client-side Fisher-Yates shuffle that rejects a no-op permutation.
  function hashSeed(value) {
    var h = 2166136261;
    for (var i = 0; i < value.length; i++) { h ^= value.charCodeAt(i); h = Math.imul(h, 16777619); }
    return h >>> 0;
  }
  function mulberry32(seed) {
    var a = seed >>> 0;
    return function () {
      a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function shuffled(array, rng) {
    var out = array.slice();
    for (var i = out.length - 1; i > 0; i--) {
      var j = Math.floor(rng() * (i + 1));
      var swap = out[i]; out[i] = out[j]; out[j] = swap;
    }
    return out;
  }
  function matchingColumns(questions, attemptId) {
    var matching = (questions || []).filter(function (q) { return q.questionType === 'MATCHING'; });
    if (matching.length < 2) return { wordOrder: matching.map(function (q) { return q.questionId; }), meaningOrder: [] };
    var wordIds = matching.map(function (q) { return q.questionId; });
    var meaningCount = (matching[0].options || []).length;
    var meaningIndices = [];
    for (var i = 1; i <= meaningCount; i++) meaningIndices.push(i);
    var rng = mulberry32(hashSeed(String(attemptId == null ? '' : attemptId) + ':' + matching.length + ':' + meaningCount));
    var wordOrder = shuffled(wordIds, rng);
    var meaningOrder = shuffled(meaningIndices, rng);
    if (wordOrder.length > 1 && wordOrder.every(function (id, i) { return id === wordIds[i]; })) wordOrder = shuffled(wordIds, rng);
    if (meaningOrder.length > 1 && meaningOrder.every(function (idx, i) { return idx === meaningIndices[i]; })) meaningOrder = shuffled(meaningIndices, rng);
    return { wordOrder: wordOrder, meaningOrder: meaningOrder };
  }
  function renderCheck(check) {
    var all = check.questions || [];
    // [JLAS-W0113] Restore previously autosaved selections (questionId -> selectedOption) so a reopen/reload
    // shows the learner's saved MCQ choices and matching pairs instead of a blank quiz.
    var selectedByQuestion = {};
    (check.draftAnswers || []).forEach(function (d) {
      if (d && d.selectedOption != null) selectedByQuestion[String(d.questionId)] = Number(d.selectedOption);
    });
    var matching = all.filter(function (q) { return q.questionType === 'MATCHING'; });
    var canPair = matching.length >= 2 && matching.every(function (q) {
      return q.options && q.options.length === matching.length && q.options.every(function (option, index) {
        return option === matching[0].options[index];
      });
    });
    var questions = all.filter(function (q) { return !canPair || q.questionType !== 'MATCHING'; }).map(function (q, i) {
      var options = (q.options || []).map(function (opt, oi) {
        return '<label class="ilp-option"><input type="radio" name="q-' + q.questionId + '" value="' + (oi + 1) + '"' +
          (selectedByQuestion[String(q.questionId)] === (oi + 1) ? ' checked' : '') + '>' +
          '<span class="ilp-option__mark">' + String.fromCharCode(65 + oi) + '</span><span>' + esc(opt) + '</span></label>';
      }).join('');
      return '<fieldset class="ilp-question"><legend><span class="ilp-question__num">' + (i + 1) + '</span>' +
        '<span>' + esc(q.questionText) + '</span></legend><div class="ilp-options">' + options + '</div></fieldset>';
    }).join('');
    if (canPair) {
      var matchOrder = matchingColumns(all, check.attemptId);
      var matchById = {};
      matching.forEach(function (q) { matchById[q.questionId] = q; });
      var matchMeaningByIndex = {};
      matching[0].options.forEach(function (option, i) { matchMeaningByIndex[i + 1] = option; });
      var matchNumber = {};
      matchOrder.wordOrder.forEach(function (id, i) { matchNumber[id] = i + 1; });
      questions += '<fieldset class="ilp-matching"><legend>Nối từ ở cột trái với nghĩa ở cột phải</legend>' +
        '<p>Chọn một từ rồi chọn nghĩa tương ứng (hoặc ngược lại). Chọn mục khác để đổi; bấm vào cặp đã nối để tháo.</p>' +
        '<div class="ilp-matching__columns"><div class="ilp-matching__column"><h2>Từ vựng</h2>' +
        matchOrder.wordOrder.map(function (id) { return '<button type="button" data-match-word="' + id + '" aria-pressed="false"><span>' + matchNumber[id] + '.</span> ' + esc(matchById[id].questionText) + '</button>'; }).join('') +
        '</div><div class="ilp-matching__column"><h2>Nghĩa</h2>' +
        matchOrder.meaningOrder.map(function (idx, i) { return '<button type="button" data-match-meaning="' + idx + '" aria-pressed="false"><span>' + String.fromCharCode(65 + i) + '.</span> ' + esc(matchMeaningByIndex[idx]) + '</button>'; }).join('') +
        '</div></div><div class="ilp-matching__answers">' + matching.map(function (q) {
          return q.options.map(function (_, i) { return '<input type="radio" hidden name="q-' + q.questionId + '" value="' + (i + 1) + '"' +
            (selectedByQuestion[String(q.questionId)] === (i + 1) ? ' checked' : '') + '>'; }).join('');
        }).join('') + '</div></fieldset>';
    }
    // [JLAS-W0126] no-inline-dictionary locks the scored check against the inline dictionary popover (and its
    // "Lưu từ"/"Mở từ điển"), which would reveal the answer. The study/result views stay unlocked.
    return '<section class="ilp-check no-inline-dictionary"><form class="ilp-check__form" data-submit data-attempt-id="' + esc(check.attemptId) + '">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">MICRO CHECK // ' + (num(check.batchIndex) + 1) + '/' + num(check.batchCount) + '</span>' +
      '<h1>Bài kiểm tra nhỏ</h1><p>Hoàn thành phần trắc nghiệm và ghép các cặp từ – nghĩa.</p><span class="ilp-check__answered" role="status" data-check-progress>' +
        checkProgressText(0, canPair ? all.length - matching.length : all.length, 0, canPair ? matching.length : 0) + '</span></div></header>' +
      questions +
      '<span class="ilp-check__draft" data-draft-status role="status" aria-live="polite"></span>' +
      '<button class="ilp-check__submit" type="submit">Nộp bài</button></form>' +
      '</section>';
  }

  function syncMatchingHighlights(form, selectedWord, selectedMeaning) {
    form.querySelectorAll('[data-match-word]').forEach(function (button) {
      var questionId = button.dataset.matchWord;
      var answer = form.querySelector('input[name="q-' + questionId + '"]:checked');
      var matched = !!answer;
      button.classList.toggle('is-matched', matched);
      button.classList.toggle('is-selected', !matched && selectedWord === questionId);
      button.setAttribute('aria-pressed', String(matched || selectedWord === questionId));
      var badge = button.querySelector('[data-match-badge]');
      if (matched) {
        if (!badge) {
          badge = document.createElement('span');
          badge.setAttribute('data-match-badge', '');
          badge.className = 'ilp-matching__badge';
          button.appendChild(badge);
        }
        var meaningButton = form.querySelector('[data-match-meaning="' + answer.value + '"]');
        badge.textContent = meaningButton && meaningButton.querySelector('span')
          ? String(meaningButton.querySelector('span').textContent || '').replace(/\.\s*$/, '') : answer.value;
      } else if (badge) {
        badge.textContent = '';
      }
    });
    form.querySelectorAll('[data-match-meaning]').forEach(function (button) {
      var idx = button.dataset.matchMeaning;
      var matched = !!form.querySelector('.ilp-matching__answers input[value="' + idx + '"]:checked');
      button.classList.toggle('is-matched', matched);
      button.classList.toggle('is-selected', !matched && selectedMeaning === idx);
      button.setAttribute('aria-pressed', String(matched || selectedMeaning === idx));
    });
  }

  function updateCheckProgress(form) {
    var questionNames = new Set(Array.prototype.map.call(form.querySelectorAll('.ilp-question input[type="radio"]'), function (input) { return input.name; }));
    var pairNames = new Set(Array.prototype.map.call(form.querySelectorAll('.ilp-matching__answers input[type="radio"]'), function (input) { return input.name; }));
    var answeredQuestions = 0;
    var answeredPairs = 0;
    form.querySelectorAll('input[type="radio"]:checked').forEach(function (input) {
      if (questionNames.has(input.name)) answeredQuestions++;
      else if (pairNames.has(input.name)) answeredPairs++;
    });
    var output = form.querySelector('[data-check-progress]');
    if (output) output.textContent = checkProgressText(answeredQuestions, questionNames.size, answeredPairs, pairNames.size);
  }

  function selectMatchingAnswer(form, wordId, value) {
    form.querySelectorAll('.ilp-matching__answers input[type="radio"]:checked').forEach(function (input) {
      if (input.value === value && input.name !== 'q-' + wordId) input.checked = false;
    });
    var radio = form.querySelector('input[name="q-' + wordId + '"][value="' + value + '"]');
    if (radio) radio.checked = true;
  }

  // [JLAS-W0113] Re-run the derived UI after the form HTML (with restored `checked` radios) is in the DOM:
  // refresh matching highlights and the answered progress counter so a reopened/reloaded attempt shows its
  // saved state, not a blank quiz.
  function restoreCheckForm(form) {
    if (!form) return;
    syncMatchingHighlights(form, null, null);
    updateCheckProgress(form);
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
      (wrong.length ? '<p class="ilp-review-summary">Có ' + wrong.length + ' câu cần xem lại. Chi tiết câu hỏi và đáp án nằm trong lịch sử lượt làm bên dưới.</p>' : '') +
      '<div class="ilp-result__actions">' +
      (nextBatch ? '<button class="ilp-check" type="button" data-check-next>Làm tiếp phần kiểm tra →</button>' : '') +
      (result.reviewRequired ? '<button class="ilp-check" type="button" data-review>Ôn lại phần đã sai</button>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/v2/vocabulary?activity=' + encodeURIComponent(activityId() || '') +
        (passed ? '&amp;mode=review&amp;tab=content">Xem lại bài giảng' : '&amp;tab=content">Quay lại bài học') + '</a>' +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a>' +
      '</div></section>';
  }

  // [JLAS-W0108] Task 11 - REVIEW: the server's read-only view, rendered with no flip/remember/check controls.
  function renderReview(v, feedback, activeTab, partIndex, ui) {
    return '<p class="ilp-mode-banner" role="note" data-mode-banner="REVIEW"><strong>Chế độ xem lại</strong> — chỉ xem, không ghi nhận tiến độ.</p>' +
      renderStudy(v, null, activeTab || 'content', partIndex || 0, Object.assign({}, ui || {}, { mode: 'browse', readOnly: true }));
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
    if (StudyMode && StudyMode.bindAttemptHistory) StudyMode.bindAttemptHistory(node);
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var modeApi = StudyMode ? StudyMode.endpoints(mode) : StudyMode;
    var issuePath = (modeApi && modeApi.issue) || '/micro-check';
    var submitPath = (modeApi && modeApi.submit) || '/micro-check/submit';
    var rehearsalState = null;
    var selectedTab = mode === 'PRACTICE' || new URLSearchParams(window.location.search).get('tab') === 'flashcards'
      ? 'flashcards' : 'content';
    var currentStudy = null;
    var currentGate = null;
    var reviewStudy = null;
    var selectedWord = null;
    var selectedMeaning = null;
    var selectedPart = 0;
    var flashUi = { mode: 'browse', browseIndex: null, browseFlipped: false, typedFeedback: '' };
    var flipPending = false;
    // [JLAS-W0113] Autosave state: debounce timer, retry count, and the draft endpoint (same path for every
    // content adapter). Drafts only ever autosave in LEARN mode (the credit-earning micro-check); PRACTICE and
    // REVIEW replays are ephemeral and never write a draft.
    var draftPath = '/micro-check/draft';
    var draftTimer = null;
    var draftRetries = 0;
    // [JLAS-W0126] Draft dirtiness + in-flight guard for app-controlled page-leave protection. `draftDirty`
    // is set whenever an edit schedules a save and cleared once the latest save is acknowledged; `draftSeq`
    // ignores a stale in-flight response so an out-of-order write can never regress a newer selection.
    var draftDirty = false;
    var draftInFlight = null;
    var draftSeq = 0;
    function renderRehearsal() {
      node.innerHTML = StudyMode.banner('PRACTICE') + renderStudy(viewModel(rehearsalState), null, selectedTab, selectedPart, flashUi);
    }
    var PASSED_ACTIONS = StudyMode ? StudyMode.passedActions('/learning/v2/vocabulary', id, ['REVIEW', 'PRACTICE', 'RESULTS']) : '';
    function celebrateIfFinished(wasDone) {
      if (wasDone || !currentStudy || !studiedDone(currentStudy) || !StudyMode || !StudyMode.celebrate) return;
      StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã hoàn thành phần Từ vựng.', activityId: id });
    }
    function showStudy() {
      if (mode === 'PRACTICE') return renderRehearsal();
      if (mode === 'REVIEW' && reviewStudy) {
        node.innerHTML = renderReview(viewModel(reviewStudy), null, selectedTab, selectedPart, flashUi);
        return;
      }
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

    // [JLAS-W0113] Autosave a draft of the learner's in-progress selections. The draft is the FULL current
    // checked set (an unselected/unpaired answer is simply omitted, and the server drops it), so "unselect" is
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
          await StudyMode.loadSavedResults(node, api, '/learning/v2/vocabulary', id);
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'REVIEW') {
          reviewStudy = await api(modeApi.study);
          showStudy();
          // [JLAS-W0193] §2.23 — a review reopened mid-check must still offer the remaining batch.
          var gate = null;
          if (reviewStudy && reviewStudy.checkRequired !== false) {
            try { gate = await api('/micro-check/state'); } catch (e) { gate = null; }
          }
          if (gate && !gate.passed) node.insertAdjacentHTML('beforeend', StudyMode.reviewGateActions(gate, '/learning/v2/vocabulary', id));
          node.setAttribute('aria-busy', 'false');
          return;
        }
        if (mode === 'PRACTICE') {
          var practiceSource = await api('/review');
          if (practiceSource.activityState !== 'PASSED' && practiceSource.activityState !== 'COMPLETED') {
            node.innerHTML = '<section class="ilp-state"><h1>Bài học chưa hoàn thành</h1>' +
              '<p>Hoàn thành bài học và kiểm tra trước khi ôn lại.</p>' +
              '<a class="ilp-check" href="/learning/v2/vocabulary?activity=' + encodeURIComponent(id) +
              '">Tiếp tục học</a></section>';
            node.setAttribute('aria-busy', 'false');
            return;
          }
          rehearsalState = StudyMode.rehearsal(practiceSource);
          renderRehearsal();
          node.setAttribute('aria-busy', 'false');
          return;
        }
        currentStudy = await api('/study');
        currentGate = null;
        if (currentStudy.checkRequired !== false) {
          try { currentGate = await api('/micro-check/state'); } catch (e) { currentGate = null; }
        }
        // Owner 2026-10-21: a finished part reopens on its own page (done state), never on an empty result screen.
        showStudy();
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    async function doPractice() {
      try {
        selectedWord = null; selectedMeaning = null;
        var practiceCheck = await api(issuePath, { method: 'POST' });
        node.innerHTML = StudyMode.banner('PRACTICE') + renderCheck(practiceCheck);
        restoreCheckForm(node.querySelector('.ilp-check__form'));
      } catch (e) { node.innerHTML = error(e.message); }
    }

    async function doCheck() {
      try {
        selectedWord = null; selectedMeaning = null;
        var check = await api(issuePath, { method: 'POST' });
        node.innerHTML = renderCheck(check);
        restoreCheckForm(node.querySelector('.ilp-check__form'));
      } catch (e) { node.innerHTML = error(e.message); }
    }

    node.addEventListener('click', async function (event) {
      if (StudyMode && StudyMode.isSelectingText && StudyMode.isSelectingText(node)) return; // a lookup selection, not a click
      var markStudied = event.target.closest('[data-mark-studied]');
      if (markStudied) {
        if (markStudied.disabled) return;
        markStudied.disabled = true;
        var doneBefore = studiedDone(currentStudy);
        try { currentStudy = await api('/complete', { method: 'POST' }); }
        catch (e) { markStudied.disabled = false; node.innerHTML = error(e.message); return; }
        showStudy();
        celebrateIfFinished(doneBefore);
        return;
      }
      var tab = event.target.closest('[data-study-tab]');
      if (tab) {
        selectedTab = tab.dataset.studyTab;
        showStudy();
        return;
      }
      var part = event.target.closest('[data-study-part]');
      if (part) {
        selectedPart = Number(part.dataset.studyPart);
        flashUi.browseIndex = null;
        flashUi.browseFlipped = false;
        flashUi.turning = false;
        showStudy();
        return;
      }
      var historyTab = event.target.closest('[data-quiz-history-index]');
      if (historyTab) {
        var selectedHistory = historyTab.dataset.quizHistoryIndex;
        node.querySelectorAll('[data-quiz-history-index]').forEach(function (button) {
          button.setAttribute('aria-current', String(button === historyTab));
        });
        node.querySelectorAll('[data-quiz-history-detail]').forEach(function (detail) {
          detail.hidden = detail.dataset.quizHistoryDetail !== selectedHistory;
        });
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
        var sourceState = mode === 'PRACTICE' ? rehearsalState : mode === 'REVIEW' ? reviewStudy : currentStudy;
        var cards = displayGroups(viewModel(sourceState).cards)[selectedPart] || [];
        var firstUnseen = cards.findIndex(function (card) { return !card.flipped; });
        var from = flashUi.browseIndex == null ? Math.max(0, firstUnseen) : flashUi.browseIndex;
        flashUi.browseIndex = Math.min(Math.max(from + Number(browseStep.dataset.browseStep), 0), Math.max(cards.length - 1, 0));
        flashUi.browseFlipped = false;
        flashUi.turning = false;
        showStudy();
        return;
      }
      var audio = event.target.closest('[data-speak]');
      if (audio) { event.stopPropagation(); speakJapanese(audio.dataset.speak); return; }
      if (event.target.closest('[data-browse-card]')) { flashUi.browseFlipped = !flashUi.browseFlipped; flashUi.turning = true; showStudy(); return; }
      var matchWord = event.target.closest('[data-match-word]');
      if (matchWord) {
        var matchForm = matchWord.closest('form');
        var wordId = matchWord.dataset.matchWord;
        var wordAnswer = matchForm.querySelector('input[name="q-' + wordId + '"]:checked');
        if (wordAnswer) { wordAnswer.checked = false; selectedWord = null; selectedMeaning = null; }
        else if (selectedWord === wordId) { selectedWord = null; }
        else {
          selectedWord = wordId;
          if (selectedMeaning != null) {
            selectMatchingAnswer(matchForm, wordId, selectedMeaning);
            selectedWord = null; selectedMeaning = null;
          }
        }
        syncMatchingHighlights(matchForm, selectedWord, selectedMeaning);
        updateCheckProgress(matchForm);
        scheduleDraftSave(matchForm);
        return;
      }
      var matchMeaning = event.target.closest('[data-match-meaning]');
      if (matchMeaning) {
        var form = matchMeaning.closest('form');
        var value = matchMeaning.dataset.matchMeaning;
        var meaningAnswer = form.querySelector('.ilp-matching__answers input[value="' + value + '"]:checked');
        if (meaningAnswer) { meaningAnswer.checked = false; selectedWord = null; selectedMeaning = null; }
        else if (selectedMeaning === value) { selectedMeaning = null; }
        else {
          selectedMeaning = value;
          if (selectedWord != null) {
            selectMatchingAnswer(form, selectedWord, value);
            selectedWord = null; selectedMeaning = null;
          }
        }
        syncMatchingHighlights(form, selectedWord, selectedMeaning);
        updateCheckProgress(form);
        scheduleDraftSave(form);
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
      if (event.target.closest('[data-practice-review]')) {
        rehearsalState = StudyMode.rehearsal(rehearsalState);
        selectedTab = 'flashcards'; selectedPart = 0;
        flashUi = { mode: 'browse', browseIndex: 0, browseFlipped: false, typedFeedback: '' };
        renderRehearsal();
        return;
      }
      if (event.target.closest('[data-check-next]')) return doCheck();
      if (event.target.closest('[data-check]')) {
        if (mode === 'LEARN' && currentStudy && currentStudy.flippedCount < currentStudy.totalCards &&
            !(await window.JlasConfirm('Bạn chưa lướt hết các thẻ. Bạn muốn kiểm tra ngay hay tiếp tục ôn thẻ?',
              { confirmText: 'Vẫn kiểm tra', cancelText: 'Ôn hết thẻ' }))) return;
        return doCheck();
      }
      if (event.target.closest('[data-review]')) {
        try { await api('/micro-check/review', { method: 'POST' }); } catch (e) { node.innerHTML = error(e.message); return; }
        selectedTab = 'flashcards'; flashUi.mode = 'browse';
        return loadStudy();
      }
      var flip = event.target.closest('[data-card]');
      if (flip) {
        if (flipPending) return;
        var sourceState = mode === 'PRACTICE' ? rehearsalState : currentStudy;
        var visibleCards = displayGroups(viewModel(sourceState).cards)[selectedPart] || [];
        var visibleIndex = visibleCards.findIndex(function (card) { return String(card.memberId) === String(flip.dataset.card); });
        if (visibleIndex >= 0) flashUi.browseIndex = visibleIndex;
        var firstReveal = visibleIndex >= 0 && !visibleCards[visibleIndex].flipped && !flashUi.browseFlipped;
        if (firstReveal) {
          if (!shouldPersistFlip(mode, flashUi.mode)) StudyMode.rehearseCard(rehearsalState, flip.dataset.card, 'flip');
          else {
            flipPending = true;
            var doneBeforeFlip = studiedDone(currentStudy);
            try { currentStudy = await api('/cards/' + encodeURIComponent(flip.dataset.card) + '/flip', { method: 'POST' }); }
            catch (e) { node.innerHTML = error(e.message); return; }
            finally { flipPending = false; }
            flashUi.browseFlipped = !flashUi.browseFlipped;
            flashUi.turning = true;
            showStudy();
            celebrateIfFinished(doneBeforeFlip);
            return;
          }
        }
        flashUi.browseFlipped = !flashUi.browseFlipped;
        flashUi.turning = true;
        showStudy();
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

    // Clicking a label may target its text/mark rather than the radio itself. Read the browser's
    // post-default checked state on the next task so the live counter never stays stale.
    node.addEventListener('click', function (event) {
      var option = event.target.closest && event.target.closest('.ilp-check__form .ilp-option');
      if (!option) return;
      window.setTimeout(function () {
        var form = option.closest('form');
        if (form) updateCheckProgress(form);
      }, 0);
    });

    node.addEventListener('change', function (event) {
      if (event.target.matches && event.target.matches('.ilp-check__form input[type="radio"]')) {
        var changeForm = event.target.closest('form');
        updateCheckProgress(changeForm);
        scheduleDraftSave(changeForm);
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
      // A submitted attempt must never be re-autosaved: stop any pending debounce and cancel in-flight retries.
      if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
      draftDirty = false;
      var unanswered = new Set(Array.prototype.map.call(form.querySelectorAll('input[type="radio"]'), function (input) {
        return input.name;
      })).size - form.querySelectorAll('input[type="radio"]:checked').length;
      if (unanswered > 0 && !(await window.JlasConfirm('Bạn còn ' + unanswered + ' câu chưa trả lời. Câu bỏ trống sẽ bị tính sai. Vẫn nộp bài?'))) return;
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
        try {
          var attempts = await api('/attempt-history');
          var resultPanel = node.querySelector('.ilp-result');
          if (resultPanel && attempts && attempts.length) resultPanel.insertAdjacentHTML('beforeend', StudyMode.attemptHistory(attempts));
        } catch (historyError) { /* Keep the current quiz result usable if the optional history request fails. */ }
        if (mode === 'LEARN' && result.passed && result.activityState === 'PASSED' && StudyMode && StudyMode.showNextAction) {
          await StudyMode.showNextAction(node, id);
          if (StudyMode.celebrate) StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã vượt qua phần Từ vựng.', activityId: id });
        }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
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

  return { viewModel: viewModel, checkTypedAnswer: checkTypedAnswer, checkProgressText: checkProgressText, shouldPersistFlip: shouldPersistFlip, selectMatchingAnswer: selectMatchingAnswer, renderStudy: renderStudy, renderReview: renderReview, renderCheck: renderCheck, renderResult: renderResult, mount: mount };
});
