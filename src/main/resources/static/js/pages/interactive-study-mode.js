(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveStudyMode = api;
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';
  // [JLAS-W0108] Task 11 - shared LEARN / REVIEW / PRACTICE contract for the interactive study pages.
  // REVIEW is read-only (GET /review). PRACTICE is a recorded replay (POST /practice + /practice/submit) that the
  // server never counts toward progress, score or unlocks. The page only chooses the endpoint; the server enforces it.
  var MODES = { review: 'REVIEW', practice: 'PRACTICE', results: 'RESULTS', learn: 'LEARN' };
  var LABEL = { REVIEW: 'Xem lại', PRACTICE: 'Luyện tập', RESULTS: 'Kết quả kiểm tra' };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }

  function resolveMode(search) {
    var value = new URLSearchParams(search || '').get('mode');
    return (value && MODES[String(value).toLowerCase()]) || 'LEARN';
  }

  function endpoints(mode) {
    if (mode === 'REVIEW') return { study: '/review', issue: null, submit: null };
    if (mode === 'PRACTICE') return { study: null, issue: '/practice', submit: '/practice/submit' };
    if (mode === 'RESULTS') return { study: null, issue: null, submit: null };
    return { study: '/study', issue: '/micro-check', submit: '/micro-check/submit' };
  }

  function banner(mode) {
    if (mode === 'REVIEW') {
      return '<p class="ilp-mode-banner" role="note" data-mode-banner="REVIEW"><strong>Chế độ xem lại</strong> — chỉ xem, ' +
        'không ghi nhận gì vào tiến độ.</p>';
    }
    if (mode === 'PRACTICE') {
      return '<p class="ilp-mode-banner" role="note" data-mode-banner="PRACTICE"><strong>Chế độ luyện tập</strong> — ' +
        'không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>';
    }
    return '';
  }

  function passedActions(page, activityId, modes) {
    var links = (modes || []).map(function (mode) {
      return '<a class="ilp-check ilp-check--ghost" href="' + esc(page) + '?activity=' + encodeURIComponent(activityId) +
        '&amp;mode=' + mode.toLowerCase() + '">' + (LABEL[mode] || mode) + '</a>';
    });
    return links.join('');
  }

  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }

  // A fresh, browser-local review queue. Never send these flags to the study/progress API.
  function rehearsal(source) {
    var cards = (source.cards || []).map(function (card) {
      return Object.assign({}, card, { flipped: false, remembered: false });
    });
    return Object.assign({}, source, { cards: cards, totalCards: cards.length,
      rememberedCount: 0, flippedCount: 0, reviewRemaining: cards.length, checkEligible: false });
  }

  function rehearseCard(state, memberId, action, remembered) {
    var card = state.cards.find(function (item) { return String(item.memberId) === String(memberId); });
    if (!card) return state;
    if (action === 'flip') card.flipped = true;
    if (action === 'remember' && card.flipped) {
      card.remembered = remembered === true;
      if (!card.remembered) card.flipped = false;
    }
    state.flippedCount = state.cards.filter(function (item) { return item.flipped; }).length;
    state.rememberedCount = state.cards.filter(function (item) { return item.remembered; }).length;
    state.reviewRemaining = state.cards.length - state.rememberedCount;
    state.checkEligible = state.cards.length > 0 && state.reviewRemaining === 0;
    return state;
  }

  /** Result of a replay: score only. No attempts, cooldown or review gate - the server keeps none for a replay. */
  function practiceResult(result, reviewLabel) {
    result = result || {};
    var wrong = (Array.isArray(result.review) ? result.review : []).filter(function (r) { return r.correct === false; });
    return banner('PRACTICE') + '<section class="ilp-result"><header class="ilp-study__head"><div>' +
      '<span class="ilp-kicker">KẾT QUẢ LUYỆN TẬP</span><h1>' + num(result.scorePercent) + '%</h1></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng</p>' +
      '<p class="ilp-result__pass">Kết quả luyện tập không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>' +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions"><button class="ilp-check" type="button" data-practice-review>' +
      esc(reviewLabel || 'Ôn flashcard rồi kiểm tra lại') + '</button>' +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  /** Read-only REVIEW list; each item is pre-rendered card markup supplied by the page. */
  function reviewList(title, items, feedbackHtml) {
    return banner('REVIEW') + '<section class="ilp-result"><header class="ilp-study__head"><div>' +
      '<span class="ilp-kicker">XEM LẠI</span><h1>' + esc(title) + '</h1></div></header><ol class="ilp-review-list">' +
      (items || []).map(function (item) { return '<li class="ilp-review-list__item">' + item.html + '</li>'; }).join('') +
      '</ol>' + (feedbackHtml || '') +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  /** Stored feedback of past attempts (GET /review/attempts): score, and each question with the correct answer and why. */
  function feedbackList(feedback) {
    if (!Array.isArray(feedback) || !feedback.length) {
      return '<p class="ilp-result__meta" data-feedback-empty>Chưa có lượt làm bài nào để xem lại.</p>';
    }
    return '<section class="ilp-feedback" aria-label="Kết quả các lượt làm bài">' + feedback.map(function (batch) {
      var items = (Array.isArray(batch.review) ? batch.review : []).map(function (r) {
        var correctText = r.correctAnswerText != null ? r.correctAnswerText :
          (r.correctOption != null ? 'Không có nội dung đáp án trong lượt lưu này' : null);
        var selectedText = r.selectedOption == null ? 'bạn bỏ trống' :
          ('bạn chọn: ' + (r.selectedAnswerText != null ? r.selectedAnswerText : 'Không có nội dung lựa chọn trong lượt lưu này'));
        return '<li class="' + (r.correct ? 'is-correct' : 'is-wrong') + '"><span>' + esc(r.questionText || ('Câu ' + r.questionId)) +
          '</span><small>' + (r.correct ? 'Đúng' : 'Sai') + (correctText != null ? ' · đáp án đúng: ' + esc(correctText) : '') +
          ' · ' + esc(selectedText) +
          (r.explanation ? ' · ' + esc(r.explanation) : '') + '</small></li>';
      }).join('');
      return '<article class="ilp-feedback__batch" data-feedback-batch="' + num(batch.batchIndex) + '"><h2>Phần ' +
        (num(batch.batchIndex) + 1) + ' · ' + num(batch.scorePercent) + '% (' + num(batch.correctAnswers) + '/' +
        num(batch.totalQuestions) + ')</h2><ol>' + items + '</ol></article>';
    }).join('') + '</section>';
  }

  /**
   * [JLAS-W0193] §2.23 - while a multi-batch check is mid-flight, the read-only REVIEW screen must still hand the
   * learner the exact next batch with the server's current state, not strand them on a list with no way forward.
   * A review is read-only: link to LEARN instead of issuing a scored check here.
   */
  function reviewGateActions(gate, page, activityId) {
    if (!gate || gate.passed) return '';
    if (gate.checkEligible) {
      var total = num(gate.batchCount);
      var part = total > 1 ? num(gate.currentBatch) + 1 : 1;
      return '<div class="ilp-result__actions"><a class="ilp-check" href="' + esc(page) +
        '?activity=' + encodeURIComponent(activityId) + '&amp;mode=learn">Vào bài học để tiếp tục kiểm tra' +
        (total > 1 ? ' — phần ' + part + '/' + total : '') + '</a></div>';
    }
    if (gate.reviewRequired) {
      return '<p class="ilp-gate-note" role="note">Bạn cần ôn lại nội dung bài học trước khi tiếp tục phần kiểm tra còn lại.</p>';
    }
    if (gate.cooldownUntil) {
      return '<p class="ilp-gate-note" role="note">Phần kiểm tra còn lại đang tạm khóa. Vui lòng quay lại sau.</p>';
    }
    return '<p class="ilp-gate-note" role="note">Phần kiểm tra chưa mở. Quay lại bài học để hoàn thành nội dung rồi kiểm tra.</p>';
  }

  /** Persistent attempt tabs and saved feedback, rendered only alongside quiz results. */
  function attemptSource(attempt) {
    if (!attempt.sourceStatus) return '';
    var source = attempt.source || {};
    if (attempt.sourceStatus !== 'AVAILABLE' || !(source.passage || source.audioUrl)) {
      return '<p class="ilp-gate-note" data-source-unavailable>Nguồn của lượt này không còn được lưu; không thay bằng bài học hiện tại.</p>';
    }
    return '<div class="ilp-attempt-history__source" data-attempt-source><h3>Nguồn của lượt này</h3>' +
      (source.audioUrl
        ? '<audio controls preload="none" src="' + esc(source.audioUrl) + '">Trình duyệt không hỗ trợ audio.</audio>'
        : '<p class="ilp-passage__text">' + esc(source.passage) + '</p>') +
      (source.audioUrl && source.transcriptJa ? '<details class="ilp-attempt-history__script"><summary>Xem kịch bản tiếng Nhật</summary>' +
        '<p class="ilp-passage__text">' + esc(source.transcriptJa) + '</p></details>' : '') + '</div>';
  }

  function attemptHistory(attempts) {
    if (!Array.isArray(attempts) || !attempts.length) return '';
    var active = attempts.length - 1;
    var tabs = attempts.map(function (attempt, index) {
      var kind = attempt.mode === 'PRACTICE' ? 'Ôn tập' : 'Kiểm tra';
      return '<button type="button" data-quiz-history-index="' + index + '" aria-current="' + (index === active) + '">' +
        kind + ' · Phần ' + (num(attempt.batchIndex) + 1) + ' · Lần ' + num(attempt.attemptNumber) +
        ' · ' + num(attempt.scorePercent) + '%</button>';
    }).join('');
    var panels = attempts.map(function (attempt, index) {
      var items = (Array.isArray(attempt.review) ? attempt.review : []).map(function (item) {
        return '<li class="' + (item.correct ? 'is-correct' : 'is-wrong') + '"><strong>' +
          esc(item.questionText || 'Nội dung câu hỏi của lượt này không còn được lưu') + '</strong>' +
          '<span class="ilp-attempt-history__status">' + (item.correct ? 'Đúng' : 'Sai') + '</span>' +
          '<div class="ilp-attempt-history__answers">' +
          '<p>Đáp án: ' + esc(item.correctAnswerText || 'Chưa có trong bản lưu') + '</p>' +
          '<p>Bạn chọn: ' + esc(item.selectedAnswerText || 'Bỏ trống') + '</p></div>' +
          (item.explanation ? '<p class="ilp-attempt-history__explanation"><b>Vì sao:</b> ' + esc(item.explanation) + '</p>' : '') +
          (attempt.mode === 'PRACTICE' ? '' : '<button type="button" class="ilp-explain" data-ai-explain data-attempt-id="' +
            num(attempt.attemptId) + '" data-question-id="' + num(item.questionId) + '">Giải thích bằng AI</button>' +
            '<p class="ilp-ai-explanation" data-ai-explanation hidden></p>') + '</li>';
      }).join('');
      return '<article class="ilp-attempt-history__detail" data-quiz-history-detail="' + index + '"' +
        (index === active ? '' : ' hidden') + '><h2>' + (attempt.mode === 'PRACTICE' ? 'Lượt ôn tập' : 'Lượt kiểm tra') +
        ' · Phần ' + (num(attempt.batchIndex) + 1) + ' · ' + num(attempt.scorePercent) + '% (' +
        num(attempt.correctAnswers) + '/' + num(attempt.totalQuestions) + ')</h2>' +
        attemptSource(attempt) + '<ol>' + items + '</ol></article>';
    }).join('');
    return '<section class="ilp-attempt-history" aria-label="Lịch sử kết quả bài kiểm tra"><h2>Các lần làm bài</h2>' +
      '<nav class="ilp-attempt-history__tabs" aria-label="Chọn lần làm bài">' + tabs + '</nav>' + panels + '</section>';
  }

  /** Reopening results must be a read-only request for saved attempts, not a new quiz issue. */
  async function loadSavedResults(node, request, page, activityId) {
    var attempts = await request('/attempt-history');
    node.innerHTML = '<section class="ilp-result"><header class="ilp-study__head"><div>' +
      '<span class="ilp-kicker">KẾT QUẢ ĐÃ LƯU</span><h1>Lịch sử kiểm tra</h1></div></header>' +
      (attemptHistory(attempts) || '<p class="ilp-result__meta">Chưa có lượt kiểm tra nào được lưu.</p>') +
      '<div class="ilp-result__actions">' +
      passedActions(page, activityId, ['REVIEW', 'PRACTICE']) +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  // A completed lesson can be reopened by browser Back or a full reload. Recover its
  // persisted score and answers instead of replacing the result with navigation alone.
  async function restoreCompletedResult(node, request, page, activityId, fallback) {
    try {
      var attempts = await request('/attempt-history');
      if (!Array.isArray(attempts) || !attempts.some(function (attempt) {
        return attempt.mode !== 'PRACTICE' && attempt.passed;
      })) {
        node.innerHTML = fallback;
        return;
      }
      node.innerHTML = '<section class="ilp-result"><header class="ilp-study__head"><div>' +
        '<span class="ilp-kicker">HOÀN THÀNH</span><h1>Kết quả bài học đã lưu</h1></div></header>' +
        attemptHistory(attempts) + '<div class="ilp-result__actions">' +
        passedActions(page, activityId, ['REVIEW', 'PRACTICE']) +
        '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a></div></section>';
      await showNextAction(node, activityId);
    } catch (ignored) {
      node.innerHTML = fallback;
    }
  }

  function bindAttemptHistory(node) {
    if (!node) return;
    node.addEventListener('click', function (event) {
      var selected = event.target.closest('[data-quiz-history-index]');
      if (selected && node.contains(selected)) {
        var index = selected.dataset.quizHistoryIndex;
        node.querySelectorAll('[data-quiz-history-index]').forEach(function (button) {
          button.setAttribute('aria-current', String(button === selected));
        });
        node.querySelectorAll('[data-quiz-history-detail]').forEach(function (detail) {
          detail.hidden = detail.dataset.quizHistoryDetail !== index;
        });
        return;
      }
      var explain = event.target.closest('[data-ai-explain]');
      if (!explain || !node.contains(explain) || explain.disabled) return;
      var question = explain.closest('li').querySelector('[data-ai-explanation]');
      var browser = root || (typeof window !== 'undefined' ? window : null);
      if (!browser) return;
      var activityId = new URLSearchParams(browser.location.search).get('activity');
      if (!activityId || !browser.JlasAuth) return;
      explain.disabled = true;
      explain.textContent = 'Đang tạo giải thích…';
      fetch('/api/learning/v2/activities/' + encodeURIComponent(activityId) + '/attempts/' +
        encodeURIComponent(explain.dataset.attemptId) + '/questions/' + encodeURIComponent(explain.dataset.questionId) + '/explanation', {
          method: 'POST', headers: browser.JlasAuth.authHeaders()
        }).then(function (response) {
          return response.json().then(function (body) {
            if (!response.ok || !body.success) throw new Error(body.message || 'Chưa lấy được giải thích.');
            return body.data;
          });
        }).then(function (data) {
          var lines = [data.explanation];
          if (data.correctAnswerTranslationVi) lines.push('Nghĩa đáp án: ' + data.correctAnswerTranslationVi);
          if (data.exampleJa) lines.push('Ví dụ: ' + data.exampleJa);
          if (data.exampleVi) lines.push('Dịch: ' + data.exampleVi);
          if (data.scriptTranslationVi) lines.push('Dịch kịch bản (AI): ' + data.scriptTranslationVi);
          question.textContent = lines.join('\n');
          question.hidden = false;
          explain.textContent = 'Tạo lại giải thích';
          explain.disabled = false;
        }).catch(function (error) {
          question.textContent = error.message || 'Chưa lấy được giải thích AI. Bạn có thể thử lại sau.';
          question.hidden = false;
          explain.textContent = 'Thử giải thích lại';
          explain.disabled = false;
        });
    });
  }

  var STUDY_PAGES = { VOCABULARY: '/learning/v2/vocabulary', KANJI: '/learning/v2/kanji',
    GRAMMAR: '/learning/v2/grammar', DIALOGUE: '/learning/v2/dialogue',
    READING: '/learning/v2/reading', LISTENING: '/learning/v2/listening', FOUNDATION: '/learning/v2/foundation' };
  function ordered(items) {
    return (Array.isArray(items) ? items : []).slice().sort(function (a, b) {
      return num(a.orderIndex) - num(b.orderIndex);
    });
  }
  function activeHref(activity) {
    if (!activity || ['STUDYING', 'CHECK_READY', 'COOLDOWN'].indexOf(activity.state) < 0 ||
        !STUDY_PAGES[activity.type] || !Number.isSafeInteger(Number(activity.id)) || Number(activity.id) <= 0) return null;
    return STUDY_PAGES[activity.type] + '?activity=' + encodeURIComponent(activity.id);
  }
  /** Only the refreshed server path can nominate the next lesson; locked activities have no URL. */
  function nextStudyAction(path, currentActivityId) {
    var packages = ordered(path && path.packages);
    var currentPackage = packages.findIndex(function (pkg) {
      return ordered(pkg.activities).some(function (activity) { return String(activity.id) === String(currentActivityId); });
    });
    if (currentPackage < 0) return null;
    for (var i = currentPackage; i < packages.length; i++) {
      var pkg = packages[i];
      if (pkg.status === 'LOCKED') return null;
      var activities = ordered(pkg.activities);
      var after = i === currentPackage ? activities.findIndex(function (activity) {
        return String(activity.id) === String(currentActivityId);
      }) + 1 : 0;
      for (var j = after; j < activities.length; j++) {
        var href = activeHref(activities[j]);
        if (href && pkg.status === 'IN_PROGRESS') return { href: href };
      }
      if (i > currentPackage && pkg.status === 'AVAILABLE' && Number.isSafeInteger(Number(pkg.id)) && Number(pkg.id) > 0) {
        return { startPackageId: pkg.id };
      }
    }
    return null;
  }
  function nextActionHtml(action) {
    if (!action) return '';
    if (action.href) return '<a class="ilp-check" href="' + esc(action.href) + '">Học bài tiếp theo</a>';
    if (action.startPackageId) return '<button class="ilp-check" type="button" data-study-next-package="' +
      esc(action.startPackageId) + '">Học bài tiếp theo</button>';
    return '';
  }
  function showSummaryAction(node) {
    var link = node.querySelector('.ilp-result__actions a[href="/learning/path/v2"]');
    if (!link) return;
    link.href = '/learning/path/v2/summary';
    link.textContent = 'Xem tổng kết lộ trình';
  }
  async function showNextAction(node, currentActivityId) {
    if (!node || !window.JlasAuth) return;
    try {
      var response = await fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() });
      if (!response.ok) {
        // Another result-page read may have completed the last package first.
        if (response.status === 404) {
          var summary = await fetch('/api/learning/v2/paths/current/summary',
            { headers: window.JlasAuth.authHeaders() });
          var summaryBody = summary.ok ? await summary.json() : null;
          if (summaryBody && summaryBody.data && summaryBody.data.status === 'COMPLETED') showSummaryAction(node);
        }
        return;
      }
      var body = await response.json();
      if (body.data && body.data.totalPackages > 0 &&
          body.data.completedPackages === body.data.totalPackages) {
        showSummaryAction(node);
        return;
      }
      var action = nextStudyAction(body.data, currentActivityId);
      var actions = node.querySelector('.ilp-result__actions');
      if (actions && action && !actions.querySelector('[data-study-next-package], a[href="' + action.href + '"]')) {
        actions.insertAdjacentHTML('afterbegin', nextActionHtml(action));
      }
    } catch (ignored) { /* The existing roadmap link remains the safe fallback. */ }
  }
  function bindNextAction(node) {
    if (!node || !window.JlasAuth) return;
    node.addEventListener('click', async function (event) {
      var button = event.target.closest('[data-study-next-package]');
      if (!button || !node.contains(button) || button.disabled) return;
      button.disabled = true;
      try {
        var response = await fetch('/api/learning/v2/packages/' + encodeURIComponent(button.dataset.studyNextPackage) +
          '/start', { method: 'POST', headers: window.JlasAuth.authHeaders() });
        var body = await response.json();
        if (!response.ok) throw new Error(body.message || 'Chưa thể mở bài tiếp theo.');
        var next = ordered(body.data && body.data.activities).map(activeHref).find(Boolean);
        window.location.href = next || '/learning/path/v2';
      } catch (error) {
        button.disabled = false;
        var note = node.querySelector('[data-study-next-error]');
        if (!note) {
          note = document.createElement('p');
          note.setAttribute('data-study-next-error', '');
          note.setAttribute('role', 'alert');
          button.insertAdjacentElement('afterend', note);
        }
        note.textContent = error.message;
      }
    });
  }

  return { resolveMode: resolveMode, endpoints: endpoints, banner: banner, passedActions: passedActions,
    practiceResult: practiceResult, reviewList: reviewList, feedbackList: feedbackList,
    reviewGateActions: reviewGateActions,
    attemptHistory: attemptHistory, bindAttemptHistory: bindAttemptHistory, loadSavedResults: loadSavedResults,
    restoreCompletedResult: restoreCompletedResult,
    rehearsal: rehearsal, rehearseCard: rehearseCard,
    nextStudyAction: nextStudyAction, nextActionHtml: nextActionHtml,
    showNextAction: showNextAction, bindNextAction: bindNextAction };
});
