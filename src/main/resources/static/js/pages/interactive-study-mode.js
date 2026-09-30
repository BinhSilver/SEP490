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
  function reviewList(title, items) {
    return banner('REVIEW') + '<section class="ilp-result"><header class="ilp-study__head"><div>' +
      '<span class="ilp-kicker">XEM LẠI</span><h1>' + esc(title) + '</h1></div></header><ol class="ilp-review-list">' +
      (items || []).map(function (item) { return '<li class="ilp-review-list__item">' + item.html + '</li>'; }).join('') +
      '</ol><div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  return { resolveMode: resolveMode, endpoints: endpoints, banner: banner, passedActions: passedActions,
    practiceResult: practiceResult, reviewList: reviewList };
});
