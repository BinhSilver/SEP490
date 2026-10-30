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
    // Owner 2026-10-23: "Xem lại bài giảng" replays the lesson as it was the first time, quiz included.
    if (mode === 'REPLAY') {
      return '<p class="ilp-mode-banner" role="note" data-mode-banner="REPLAY"><strong>Xem lại bài giảng</strong> — ' +
        'học lại như lần đầu. Mỗi lần làm bài kiểm tra được lưu vào Kết quả kiểm tra, không ảnh hưởng tiến độ.</p>';
    }
    return '';
  }

  /** OD-D2-05: the chapter's words/Kanji already learnt in an earlier chapter — shown, never carded or checked. */
  function reviewItemsBlock(items) {
    items = Array.isArray(items) ? items : [];
    if (!items.length) return '';
    return '<section class="ilp-review-items" aria-label="Mục ôn"><h3>Ôn lại</h3>' +
      '<p>Đã học ở bài trước, chỉ để ôn: không tính vào bài kiểm tra của chương này.</p><ul>' +
      items.map(function (item) {
        return '<li><strong lang="ja">' + esc(item.front) + '</strong>' + (item.reading ? ' <span lang="ja">' + esc(item.reading) + '</span>' : '') +
          (item.meaning ? ' — ' + esc(item.meaning) : '') + '<small class="ilp-review-items__from">Ôn — đã học ở bài “' +
          esc(item.learnedIn) + '”</small></li>';
      }).join('') + '</ul></section>';
  }

  /** A part the learner already finished: only then can its lesson be replayed with a practice quiz. */
  function isFinished(state) {
    return !!state && (state.activityState === 'PASSED' || state.activityState === 'COMPLETED');
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
  /** Owner 2026-10-22: in a communication path "next" stays inside the learner's topic — its other vocabulary parts and
   * its unfinished situations — before following path order (paths saved before 20/10 list every topic first). */
  function nextInTopic(packages, currentPackage, currentActivityId) {
    var topic = packages[currentPackage].topicKey;
    if (topic == null) return null;
    var order = [];
    for (var i = currentPackage; i < packages.length; i++) order.push(i);
    for (var k = 0; k < currentPackage; k++) order.push(k);
    for (var n = 0; n < order.length; n++) {
      var index = order[n];
      var pkg = packages[index];
      if (String(pkg.topicKey) !== String(topic) || pkg.status === 'LOCKED' || pkg.status === 'COMPLETED') continue;
      var activities = ordered(pkg.activities);
      var after = index === currentPackage ? activities.findIndex(function (activity) {
        return String(activity.id) === String(currentActivityId);
      }) + 1 : 0;
      for (var j = after; j < activities.length; j++) {
        var href = activeHref(activities[j]);
        if (href && pkg.status === 'IN_PROGRESS') return { href: href };
      }
      if (index !== currentPackage && pkg.status === 'AVAILABLE' && Number.isSafeInteger(Number(pkg.id)) && Number(pkg.id) > 0) {
        return { startPackageId: pkg.id };
      }
    }
    return null;
  }
  /** Only the refreshed server path can nominate the next lesson; locked activities have no URL. */
  function nextStudyAction(path, currentActivityId) {
    var packages = ordered(path && path.packages);
    var currentPackage = packages.findIndex(function (pkg) {
      return ordered(pkg.activities).some(function (activity) { return String(activity.id) === String(currentActivityId); });
    });
    if (currentPackage < 0) return null;
    var inTopic = nextInTopic(packages, currentPackage, currentActivityId);
    if (inTopic) return inTopic;
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

  // Owner 2026-10-21: finishing a part keeps the study page as it is and only adds a congratulation popup with confetti —
  // never an empty "HOÀN THÀNH" screen. One popup per completion; reopening a finished part shows the page, no popup.
  var CELEBRATE_STYLE = '.ilp-celebrate{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:16px;' +
    'background:color-mix(in srgb,var(--color-on-surface,#161d1f) 38%,transparent)}' +
    '.ilp-celebrate__canvas{position:fixed;inset:0;width:100%;height:100%;pointer-events:none}' +
    '.ilp-celebrate__card{position:relative;width:min(420px,100%);padding:26px 24px 22px;border-radius:18px;text-align:center;' +
    'background:var(--color-surface-lowest,#fff);color:var(--color-on-surface,#161d1f);box-shadow:0 18px 50px rgba(30,20,60,.25)}' +
    '.ilp-celebrate__icon{display:grid;place-items:center;width:56px;height:56px;margin:0 auto 10px;border-radius:50%;font-size:28px;' +
    'background:var(--color-primary-fixed,#e9ddff);color:var(--color-primary,#4f378a)}' +
    '.ilp-celebrate__card h2{margin:0 0 6px;color:var(--color-primary,#4f378a);font-size:22px}' +
    '.ilp-celebrate__card p{margin:0 0 18px;color:var(--color-on-surface-variant,#494551)}' +
    '.ilp-celebrate .ilp-result__actions{display:flex;flex-wrap:wrap;justify-content:center;gap:8px;margin:0}' +
    '.ilp-celebrate__stay{min-height:40px;padding:8px 16px;border:1px solid var(--color-outline-variant,#cbc4d2);border-radius:10px;' +
    'background:var(--color-surface-lowest,#fff);color:var(--color-primary,#4f378a);font-weight:600;cursor:pointer}' +
    '.ilp-celebrate :focus-visible{outline:3px solid var(--color-focus,#4f378a);outline-offset:2px}';
  var CONFETTI_COLORS = ['#4f378a', '#6750a4', '#b69df8', '#e9ddff', '#7ad5a0', '#f48fb1'];

  function celebrationHtml(title, message) {
    return '<div class="ilp-celebrate" role="dialog" aria-modal="true" aria-labelledby="ilpCelebrateTitle" data-celebrate>' +
      '<canvas class="ilp-celebrate__canvas" aria-hidden="true"></canvas><section class="ilp-celebrate__card" tabindex="-1">' +
      '<span class="ilp-celebrate__icon" aria-hidden="true">🎉</span><h2 id="ilpCelebrateTitle">' + esc(title || 'Chúc mừng!') + '</h2>' +
      '<p>' + esc(message || 'Bạn đã hoàn thành phần học này.') + '</p><div class="ilp-result__actions">' +
      '<a class="ilp-check" href="/learning/path/v2">Về lộ trình</a>' +
      '<button class="ilp-celebrate__stay" type="button" data-celebrate-close>Ở lại trang này</button></div></section></div>';
  }

  function confetti(canvas, browser) {
    if (!canvas || !canvas.getContext || !browser || !browser.requestAnimationFrame) return;
    if (browser.matchMedia && browser.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    var ctx = canvas.getContext('2d');
    var width = canvas.width = browser.innerWidth, height = canvas.height = browser.innerHeight;
    var pieces = [];
    for (var i = 0; i < 140; i++) {
      pieces.push({ x: width / 2 + (Math.random() - 0.5) * width * 0.3, y: height * 0.35, size: 5 + Math.random() * 6,
        vx: (Math.random() - 0.5) * 14, vy: -6 - Math.random() * 9, spin: Math.random() * Math.PI,
        color: CONFETTI_COLORS[i % CONFETTI_COLORS.length] });
    }
    var started = null;
    function frame(time) {
      if (started === null) started = time;
      var elapsed = time - started;
      ctx.clearRect(0, 0, width, height);
      pieces.forEach(function (p) {
        p.vy += 0.32; p.vx *= 0.99; p.x += p.vx; p.y += p.vy; p.spin += 0.18;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.spin); ctx.fillStyle = p.color;
        ctx.globalAlpha = Math.max(0, 1 - elapsed / 2600);
        ctx.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2); ctx.restore();
      });
      if (elapsed < 2600 && canvas.isConnected) browser.requestAnimationFrame(frame);
      else ctx.clearRect(0, 0, width, height);
    }
    browser.requestAnimationFrame(frame);
  }

  /** Shows the congratulation popup over the page (which stays as it is) and offers the next lesson when the path has one. */
  function celebrate(node, options) {
    options = options || {};
    var browser = root || (typeof window !== 'undefined' ? window : null);
    if (!node || !browser || !browser.document) return null;
    var doc = browser.document;
    if (!doc.getElementById('ilpCelebrateStyle')) {
      var style = doc.createElement('style');
      style.id = 'ilpCelebrateStyle';
      style.textContent = CELEBRATE_STYLE;
      doc.head.appendChild(style);
    }
    var old = doc.querySelector('[data-celebrate]');
    if (old) old.remove();
    var opener = doc.activeElement;
    doc.body.insertAdjacentHTML('beforeend', celebrationHtml(options.title, options.message));
    var popup = doc.querySelector('[data-celebrate]');
    var close = function () {
      popup.remove();
      doc.removeEventListener('keydown', onKey);
      if (opener && typeof opener.focus === 'function' && doc.body.contains(opener)) opener.focus();
    };
    var onKey = function (event) {
      if (event.key === 'Escape') close();
      if (event.key !== 'Tab') return;
      var focusable = Array.prototype.slice.call(popup.querySelectorAll('a[href], button:not([disabled])'));
      if (!focusable.length) return;
      var index = focusable.indexOf(doc.activeElement);
      if (event.shiftKey ? index <= 0 : index === focusable.length - 1) {
        event.preventDefault();
        focusable[event.shiftKey ? focusable.length - 1 : 0].focus();
      }
    };
    popup.addEventListener('click', function (event) {
      if (event.target === popup || event.target.closest('[data-celebrate-close]')) close();
    });
    doc.addEventListener('keydown', onKey);
    var first = popup.querySelector('.ilp-result__actions a, .ilp-result__actions button');
    if (first) first.focus();
    confetti(popup.querySelector('canvas'), browser);
    if (options.activityId) {
      bindNextAction(popup);
      showNextAction(popup, options.activityId);
    }
    return popup;
  }

  /** Owner 2026-10-21: lookup works on Japanese inside cards, tiles and options too. A click that ends a text
   * selection there is the learner looking a word up, not flipping the card or choosing the option. */
  function isSelectingText(node) {
    var browser = root || (typeof window !== 'undefined' ? window : null);
    var selection = browser && browser.getSelection ? browser.getSelection() : null;
    if (!selection || selection.isCollapsed || !String(selection).trim()) return false;
    var anchor = selection.anchorNode;
    return !!(anchor && node && node.contains(anchor.nodeType === 3 ? anchor.parentNode : anchor));
  }

  /** Owner 2026-10-22: on the Flashcard tab the counter and bar say which card of this part is showing (e.g. 6/8), not how
   * many were seen — a finished part still read "3/8 đã xem". The content tab shows neither. */
  function positionHeader(onFlashcards, position, total) {
    position = Number(position); total = Number(total);
    if (!onFlashcards || !(total > 0) || !(position > 0)) return { count: '', bar: '' };
    return {
      count: '<div class="ilp-study__count" data-card-position><strong>' + position + '/' + total + '</strong><span>thẻ đang xem</span></div>',
      bar: '<div class="ilp-progress" role="progressbar" aria-label="Vị trí thẻ trong phần này" aria-valuenow="' + position +
        '" aria-valuemin="1" aria-valuemax="' + total + '"><span style="width:' + Math.round(position / total * 100) + '%"></span></div>'
    };
  }

  /**
   * Owner 2026-10-22 (option A): every study page says what completes it, with the current status, without changing
   * the rule. JLPT parts (kind CHECK) finish by passing the check at 70% — cards are optional (§2.6, §2.8); communication
   * vocabulary (kind MARK) finishes with "Đánh dấu hoàn thành" (owner 2026-10-21). {@code itemNoun}: "từ", "chữ".
   */
  function completionGuide(kind, itemNoun, gate, done, now) {
    var noun = itemNoun || 'từ';
    var steps = kind === 'MARK'
      ? ['Xem các ' + noun + ' trong phần này (tab Nội dung hoặc Flashcard).', 'Bấm “Đánh dấu hoàn thành”.']
      : ['Xem các ' + noun + ' ở tab Nội dung hoặc Flashcard — không bắt buộc.', 'Làm bài kiểm tra và đạt từ 70%.'];
    var status = completionStatus(kind, gate, done, now);
    return '<div class="ilp-completion-guide" data-completion-guide><p class="ilp-completion-guide__title">Để hoàn thành phần này</p>' +
      '<ol>' + steps.map(function (step) { return '<li>' + esc(step) + '</li>'; }).join('') + '</ol>' +
      (kind === 'MARK' ? '<p class="ilp-completion-guide__note">Flashcard giúp ôn lại, không bắt buộc.</p>' : '') +
      (status ? '<span class="ilp-status-chip ilp-status-chip--' + status.tone + '" data-completion-status="' + status.code + '">' +
        esc(status.text) + '</span>' : '') + '</div>';
  }

  function completionStatus(kind, gate, done, now) {
    if (done || gate && gate.passed) return { code: 'DONE', tone: 'done', text: kind === 'MARK' ? 'Đã hoàn thành' : 'Đã đạt bài kiểm tra' };
    if (kind === 'MARK') return { code: 'TODO', tone: 'todo', text: 'Chưa đánh dấu hoàn thành' };
    if (!gate) return null;
    if (gate.reviewRequired) return { code: 'REVIEW', tone: 'warn', text: 'Cần ôn lại phần sai trước khi làm lại' };
    var until = gate.cooldownUntil ? new Date(gate.cooldownUntil) : null;
    if (until && !isNaN(until) && until > (now || new Date())) {
      return { code: 'COOLDOWN', tone: 'warn', text: 'Làm lại sau ' + String(until.getHours()).padStart(2, '0') + ':' +
        String(until.getMinutes()).padStart(2, '0') };
    }
    var total = Number(gate.batchCount) || 1;
    var part = total > 1 ? ' · phần ' + ((Number(gate.currentBatch) || 0) + 1) + '/' + total : '';
    if (Number(gate.attemptsUsed) > 0 && gate.latestScore != null) {
      return { code: 'RETRY', tone: 'warn', text: 'Lần gần nhất ' + Number(gate.latestScore) + '% · đã làm ' + Number(gate.attemptsUsed) +
        '/' + (Number(gate.attemptsUsed) + Number(gate.attemptsRemaining || 0)) + ' lần' + part };
    }
    return { code: 'TODO', tone: 'todo', text: 'Chưa làm bài kiểm tra' + part };
  }

  return { resolveMode: resolveMode, endpoints: endpoints, banner: banner, isFinished: isFinished, reviewItemsBlock: reviewItemsBlock,
    passedActions: passedActions,
    positionHeader: positionHeader, completionGuide: completionGuide,
    celebrate: celebrate, celebrationHtml: celebrationHtml, isSelectingText: isSelectingText,
    practiceResult: practiceResult, reviewList: reviewList, feedbackList: feedbackList,
    reviewGateActions: reviewGateActions,
    attemptHistory: attemptHistory, bindAttemptHistory: bindAttemptHistory, loadSavedResults: loadSavedResults,
    restoreCompletedResult: restoreCompletedResult,
    rehearsal: rehearsal, rehearseCard: rehearseCard,
    nextStudyAction: nextStudyAction, nextActionHtml: nextActionHtml,
    showNextAction: showNextAction, bindNextAction: bindNextAction };
});
