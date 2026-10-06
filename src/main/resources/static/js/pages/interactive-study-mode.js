(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveStudyMode = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0108] Task 11 - shared LEARN / REVIEW / PRACTICE contract for the interactive study pages.
  // REVIEW is read-only (GET /review). PRACTICE is a recorded replay (POST /practice + /practice/submit) that the
  // server never counts toward progress, score or unlocks. The page only chooses the endpoint; the server enforces it.
  var MODES = { review: 'REVIEW', practice: 'PRACTICE', learn: 'LEARN' };
  var LABEL = { REVIEW: 'Xem lại', PRACTICE: 'Luyện tập' };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }

  function resolveMode(search) {
    var value = new URLSearchParams(search || '').get('mode');
    return (value && MODES[String(value).toLowerCase()]) || 'LEARN';
  }

  function endpoints(mode) {
    if (mode === 'REVIEW') return { study: '/review', issue: null, submit: null };
    if (mode === 'PRACTICE') return { study: null, issue: '/practice', submit: '/practice/submit' };
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
  function practiceResult(result) {
    result = result || {};
    var wrong = (Array.isArray(result.review) ? result.review : []).filter(function (r) { return r.correct === false; });
    return banner('PRACTICE') + '<section class="ilp-result"><header class="ilp-study__head"><div>' +
      '<span class="ilp-kicker">KẾT QUẢ LUYỆN TẬP</span><h1>' + num(result.scorePercent) + '%</h1></div></header>' +
      '<p class="ilp-result__meta">' + num(result.correctAnswers) + '/' + num(result.totalQuestions) + ' câu đúng</p>' +
      '<p class="ilp-result__pass">Kết quả luyện tập không ảnh hưởng tiến độ, điểm hay việc mở khóa.</p>' +
      (wrong.length ? '<ol class="ilp-review">' + wrong.map(function (r) { return '<li>Câu ' + esc(r.questionId) + '</li>'; }).join('') + '</ol>' : '') +
      '<div class="ilp-result__actions"><button class="ilp-check" type="button" data-practice-again>Luyện tập lại</button>' +
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
  async function showNextAction(node, currentActivityId) {
    if (!node || !window.JlasAuth) return;
    try {
      var response = await fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() });
      if (!response.ok) return;
      var body = await response.json();
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
    rehearsal: rehearsal, rehearseCard: rehearseCard,
    nextStudyAction: nextStudyAction, nextActionHtml: nextActionHtml,
    showNextAction: showNextAction, bindNextAction: bindNextAction };
});
