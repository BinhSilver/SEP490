(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractivePathSummaryPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0193] Task 10 — real content for GET /learning/path/v2/summary (Task 8 shipped only a
  // placeholder route). Pure view-model/render functions are exported so node:test can exercise them
  // directly, same pattern every other V2 page in this module already uses.
  var SKILL_LABEL = { VOCABULARY: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' };
  var COMPREHENSION_LABEL = { READING: 'Đọc hiểu', LISTENING: 'Nghe hiểu' };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function scoreText(value) { return (value === null || value === undefined) ? '—' : String(value) + '%'; }

  function viewModel(data) {
    data = data || {};
    var skills = Array.isArray(data.skills) ? data.skills : [];
    // Reading and Listening here summarize only activities completed inside this path.
    var comprehensionInPath = Array.isArray(data.comprehensionInPath) ? data.comprehensionInPath : [];
    // [JLAS-W0193] RC Task 7 — server-resolved Mock Test hand-off destination. Missing/malformed data
    // degrades to the honest BLOCKED_INTEGRATION state, never to a fabricated "available" default.
    var mockTest = (data.mockTest && typeof data.mockTest === 'object')
      ? data.mockTest : { available: false, status: 'BLOCKED_INTEGRATION', url: null, targetLevel: null };
    return {
      pathId: data.pathId, status: data.status || null, completedAt: data.completedAt || null,
      skills: skills, dialogue: data.dialogue || { totalCount: 0, completedCount: 0, allCompleted: false },
      comprehensionInPath: comprehensionInPath,
      certificateEligible: !!data.certificateEligible,
      mockTest: mockTest,
      latestMockResult: data.latestMockResult || null,
      studyLinks: Array.isArray(data.studyLinks) ? data.studyLinks : []
    };
  }

  function unresolvedItem(item) {
    var reviewNote = item.reviewRequired ? ' — cần ôn lại trước khi thử tiếp' : '';
    return '<li>Batch ' + esc(item.batchIndex) + ' (điểm gần nhất: ' + scoreText(item.latestScore) + ')' +
      esc(reviewNote) + '</li>';
  }

  function skillCard(skill) {
    var label = SKILL_LABEL[skill.skill] || skill.skill;
    if (!skill.hasEvidence) {
      return '<article class="ilp-summary-card is-pending"><h3>' + esc(label) + '</h3>' +
        '<p>Chưa có bằng chứng trong lộ trình này.</p></article>';
    }
    var status = skill.resolved
      ? '<p class="ilp-summary-status is-ok">Đã đạt micro-check.</p>'
      : '<p class="ilp-summary-status is-weak">Còn nội dung chưa đạt — xem chi tiết bên dưới.</p>';
    var unresolved = (skill.unresolved || []).length
      ? '<ul class="ilp-summary-unresolved">' + skill.unresolved.map(unresolvedItem).join('') + '</ul>' : '';
    return '<article class="ilp-summary-card">' +
      '<h3>' + esc(label) + '</h3>' +
      '<dl class="ilp-summary-scores">' +
      '<div><dt>Lần đầu</dt><dd>' + scoreText(skill.firstScore) + '</dd></div>' +
      '<div><dt>Gần nhất</dt><dd>' + scoreText(skill.latestScore) + '</dd></div>' +
      '<div><dt>Tốt nhất</dt><dd>' + scoreText(skill.bestScore) + '</dd></div>' +
      '</dl>' + status + unresolved + '</article>';
  }

  function renderDialogue(dialogue) {
    var text = dialogue.totalCount === 0
      ? 'Chưa có hoạt động Hội thoại trong lộ trình này.'
      : 'Đã hoàn thành ' + esc(dialogue.completedCount) + '/' + esc(dialogue.totalCount) + ' hội thoại.';
    return '<article class="ilp-summary-card"><h3>Hội thoại</h3><p>' + text + '</p></article>';
  }

  // [JLAS-W0193] In-path Reading/Listening evidence card.
  function comprehensionInPathCard(item) {
    var label = COMPREHENSION_LABEL[item.skill] || item.skill;
    if (!item.hasEvidence) {
      return '<article class="ilp-summary-card is-pending"><h3>' + esc(label) + ' — trong lộ trình</h3>' +
        '<p>Chưa làm bài đọc/nghe nào trong lộ trình này.</p></article>';
    }
    var status = item.resolved
      ? '<p class="ilp-summary-status is-ok">Đã đạt micro-check trong lộ trình.</p>'
      : '<p class="ilp-summary-status is-weak">Còn bài chưa đạt — xem chi tiết bên dưới.</p>';
    var unresolved = (item.unresolved || []).length
      ? '<ul class="ilp-summary-unresolved">' + item.unresolved.map(unresolvedItem).join('') + '</ul>' : '';
    return '<article class="ilp-summary-card"><h3>' + esc(label) + ' — trong lộ trình</h3>' +
      '<dl class="ilp-summary-scores">' +
      '<div><dt>Lần đầu</dt><dd>' + scoreText(item.firstScore) + '</dd></div>' +
      '<div><dt>Gần nhất</dt><dd>' + scoreText(item.latestScore) + '</dd></div>' +
      '<div><dt>Tốt nhất</dt><dd>' + scoreText(item.bestScore) + '</dd></div>' +
      '</dl>' + status + unresolved +
      '</article>';
  }

  function renderMockTestAction(mockTest, latestResult) {
    // [JLAS-W0193] RC Task 7 — the server (MockTestDestinationProvider) alone decides availability and the
    // destination URL; this only ever echoes `mockTest.url` verbatim, never composes or guesses one, and
    // never trusts a query parameter. No real Mock Test owner contract exists in this codebase today, so
    // the honest default is BLOCKED_INTEGRATION — rendered as a disabled button, never a fabricated link.
    if (mockTest && mockTest.status === 'NOT_APPLICABLE') return '';
    if (mockTest && mockTest.available && mockTest.url) {
      var destination = latestResult && /^N[1-5]$/.test(latestResult.level || '')
        ? '/jlpt/result?level=' + encodeURIComponent(latestResult.level) : mockTest.url;
      return '<a class="ilp-summary__quiet" href="' + esc(destination) + '">' +
        (latestResult ? 'Xem kết quả thi thử' : 'Thi thử JLPT') + '</a>';
    }
    return '<button class="ilp-summary__quiet" type="button" disabled aria-disabled="true" ' +
      'title="Chức năng thi thử JLPT sắp ra mắt">Thi thử JLPT (sắp có)</button>';
  }

  function renderMockResult(result) {
    if (!result) return '';
    var status = result.passed ? 'ĐẠT' : 'CHƯA ĐẠT';
    return '<article class="ilp-summary-card ilp-summary-mock-result"><h2>Kết quả thi thử JLPT</h2>' +
      '<p class="ilp-summary-status ' + (result.passed ? 'is-ok' : 'is-weak') + '">' + esc(result.level) + ' · ' + status + '</p>' +
      '<p><strong>' + esc(result.correctCount) + '/' + esc(result.questionCount) + ' câu đúng</strong> · ' + esc(result.scorePercent) + '%</p>' +
      '<p>Mã lượt thi #' + esc(result.attemptId) + '</p></article>';
  }

  function renderActions(v) {
    var targetLevel = v.mockTest && v.mockTest.targetLevel;
    var isJlptPath = /^N[1-5]$/.test(String(targetLevel || ''));
    var latestMockPassed = v.latestMockResult && v.latestMockResult.passed === true &&
      v.latestMockResult.level === targetLevel;
    var certificate = v.certificateEligible && (!isJlptPath || latestMockPassed)
      ? '<a class="ilp-summary__primary" href="/learning/v2/certificate">Xem chứng nhận hoàn thành</a>'
      : (v.certificateEligible && isJlptPath
        ? '<p class="ilp-summary-note">Giấy chứng nhận sẽ mở sau khi bạn thi thử JLPT đúng cấp mục tiêu và đạt.</p>'
        : '<p class="ilp-summary-note">Chứng nhận sẽ mở khi bạn hoàn thành mọi ngày học bắt buộc.</p>');
    var mockTest = renderMockTestAction(v.mockTest, v.latestMockResult);
    var nextPath = v.status === 'COMPLETED'
      ? '<a class="ilp-summary__secondary" href="/survey">Chọn mục tiêu mới</a>'
      : '<a class="ilp-summary__secondary" href="/learning/path/v2">Về lộ trình</a>';
    return '<div class="ilp-summary-actions">' + certificate + mockTest +
      nextPath + '</div>';
  }

  function renderCompletionMessage(data) {
    if (!data || data.status !== 'COMPLETED' || !data.certificateEligible) return '';
    var targetLevel = data.mockTest && data.mockTest.targetLevel;
    var mockPrompt = /^N[1-5]$/.test(String(targetLevel || ''))
      ? '<p>Hãy thi thử JLPT ' + esc(targetLevel) + ' ngay để xem bạn đã chinh phục target chưa. Nếu thi đạt, bạn sẽ nhận chứng nhận vàng J-LAS.</p>'
      : '<p>Bạn đã đi hết các nội dung học bắt buộc. Hãy tự hào về chặng đường này và giữ vững nhịp học cho mục tiêu tiếp theo.</p>';
    return '<aside class="ilp-completion-note" data-path-congratulation aria-labelledby="ilpCompletionTitle">' +
      '<span class="ilp-completion-note__mark" aria-hidden="true">✓</span>' +
      '<div><h2 id="ilpCompletionTitle">Chúc mừng! Bạn đã hoàn thành xuất sắc lộ trình</h2>' +
      mockPrompt + '</div>' +
      '</aside>';
  }

  function render(data) {
    var v = viewModel(data);
    return '<section class="ilp-summary">' +
      '<h1>Tổng kết lộ trình</h1>' +
      '<div class="ilp-summary-grid">' + v.skills.map(skillCard).join('') + renderDialogue(v.dialogue) +
      v.comprehensionInPath.map(comprehensionInPathCard).join('') + renderMockResult(v.latestMockResult) + '</div>' +
      renderCompletionMessage(data) +
      renderActions(v) + '</section>';
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở tổng kết</h1><p>' + esc(message || 'Vui lòng thử lại sau.') +
      '</p><button data-retry type="button">Thử lại</button></section>';
  }

  function mount() {
    var node = document.getElementById('interactivePathSummaryRoot');
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/path/v2/summary')) return;
    async function load() {
      node.setAttribute('aria-busy', 'true');
      try {
        var response = await fetch('/api/learning/v2/paths/current/summary', { headers: window.JlasAuth.authHeaders() });
        var body = await response.json();
        if (response.ok && body.data) {
          var data = body.data;
          var level = data.mockTest && data.mockTest.targetLevel;
          if (/^N[1-5]$/.test(String(level || ''))) {
            try {
              var resultResponse = await fetch('/api/jlpt/mock-attempts/latest?level=' + encodeURIComponent(level),
                { headers: window.JlasAuth.authHeaders() });
              var resultBody = await resultResponse.json();
              data.latestMockResult = resultResponse.ok && resultBody.success !== false ? resultBody.data : null;
            } catch (ignored) { data.latestMockResult = null; }
          }
          node.innerHTML = render(data);
        } else node.innerHTML = error(body.message);
      } catch (e) {
        node.innerHTML = error('Không thể kết nối tới máy chủ.');
      }
      node.setAttribute('aria-busy', 'false');
    }
    node.addEventListener('click', function (event) {
      if (event.target.closest('[data-retry]')) load();
    });
    load();
  }

  return {
    viewModel: viewModel, skillCard: skillCard, renderDialogue: renderDialogue,
    comprehensionInPathCard: comprehensionInPathCard, renderActions: renderActions, renderMockResult: renderMockResult,
    renderCompletionMessage: renderCompletionMessage,
    render: render, error: error, mount: mount
  };
});
