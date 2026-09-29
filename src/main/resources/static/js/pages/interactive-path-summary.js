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
  var READINESS_LABEL = { LOW: 'Còn thiếu bằng chứng', MEDIUM: 'Có bằng chứng, chưa xác nhận toàn diện' };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function scoreText(value) { return (value === null || value === undefined) ? '—' : String(value) + '%'; }

  function viewModel(data) {
    data = data || {};
    var skills = Array.isArray(data.skills) ? data.skills : [];
    var comprehension = Array.isArray(data.comprehension) ? data.comprehension : [];
    return {
      pathId: data.pathId, status: data.status || null, completedAt: data.completedAt || null,
      skills: skills, dialogue: data.dialogue || { totalCount: 0, completedCount: 0, allCompleted: false },
      comprehension: comprehension,
      readiness: data.readiness || { level: 'LOW', explanation: '' },
      certificateEligible: !!data.certificateEligible
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

  function comprehensionCard(item) {
    var label = COMPREHENSION_LABEL[item.skill] || item.skill;
    var body = item.hasEvidence
      ? '<p>Smart Test: ' + esc(item.correctPercent) + '% đúng' + (item.weak ? ' — còn yếu' : '') + '.</p>'
      : '<p>Chưa có dữ liệu Smart Test cho kỹ năng này.</p>';
    return '<article class="ilp-summary-card is-secondary"><h3>' + esc(label) + '</h3>' + body +
      '<p class="ilp-summary-note">' + esc(item.note) + '</p></article>';
  }

  function renderReadiness(readiness) {
    var label = READINESS_LABEL[readiness.level] || readiness.level;
    return '<section class="ilp-summary-readiness"><h2>Độ sẵn sàng ước tính: ' + esc(label) + '</h2>' +
      '<p>' + esc(readiness.explanation) + '</p>' +
      '<p class="ilp-summary-note">Đây là ước tính nội bộ, không phải xác nhận đỗ JLPT.</p></section>';
  }

  function renderActions(v) {
    var certificate = v.certificateEligible
      ? '<a class="ilp-summary__primary" href="/learning/v2/certificate">Xem chứng nhận hoàn thành</a>'
      : '<p class="ilp-summary-note">Chứng nhận sẽ mở khi bạn hoàn thành mọi ngày học bắt buộc.</p>';
    // No real Mock Test owner contract exists in this codebase yet (searched — none found); per the
    // release-hardening rule against linking to something fake, this stays a disabled placeholder rather
    // than a live link, so a future session can wire it up without lying to the learner today.
    var mockTest = '<button class="ilp-summary__quiet" type="button" disabled aria-disabled="true" ' +
      'title="Chức năng thi thử JLPT sắp ra mắt">Thi thử JLPT (sắp có)</button>';
    return '<div class="ilp-summary-actions">' + certificate + mockTest +
      '<a class="ilp-summary__secondary" href="/learning/path/v2">Về lộ trình</a></div>';
  }

  function render(data) {
    var v = viewModel(data);
    return '<section class="ilp-summary">' +
      '<h1>Tổng kết lộ trình</h1>' +
      '<div class="ilp-summary-grid">' + v.skills.map(skillCard).join('') + renderDialogue(v.dialogue) +
      v.comprehension.map(comprehensionCard).join('') + '</div>' +
      renderReadiness(v.readiness) + renderActions(v) + '</section>';
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
        node.innerHTML = response.ok && body.data ? render(body.data) : error(body.message);
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
    comprehensionCard: comprehensionCard, renderReadiness: renderReadiness, renderActions: renderActions,
    render: render, error: error, mount: mount
  };
});
