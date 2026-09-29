(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.StageCompletePage = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function findStage(path, stageId) {
    var stages = (path && path.stages) || [];
    var match = stages.find(function (s) { return String(s.id) === String(stageId); });
    return match || null;
  }

  // [JLAS-W0108] Chứng chỉ CHỈ hiện khi chặng vừa hoàn thành là chặng CUỐI (stageIndex lớn nhất)
  // và lộ trình đã COMPLETED — hoàn thành một chặng giữa không bao giờ mời nhận chứng chỉ.
  function isPathCompletionFor(path, stageId) {
    if (!path || path.status !== 'COMPLETED') return false;
    var stage = findStage(path, stageId);
    if (!stage) return false;
    var stages = path.stages || [];
    var maxIndex = stages.reduce(function (max, s) { return Math.max(max, s.stageIndex); }, -1);
    return stage.stageIndex === maxIndex;
  }

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  function render(root, path, stageId) {
    var stage = findStage(path, stageId);
    var level = stage ? escapeHtml(stage.level) : '';
    var isPathDone = isPathCompletionFor(path, stageId);
    var cert = isPathDone
      ? '<div class="sc-cert-offer">' +
          '<p>Bạn có muốn nhận chứng chỉ thành tựu không?</p>' +
          '<a class="sc-button sc-button--primary" href="/learning/certificate/' + path.id + '">' +
            icon('workspace_premium') + '<span>Tạo chứng chỉ</span></a>' +
        '</div>'
      : '<a class="sc-button sc-button--primary" href="/learning/path">' +
          icon('arrow_forward') + '<span>Tiếp tục lộ trình</span></a>';
    root.innerHTML = '<section class="sc-card">' +
      '<span class="sc-eyebrow">' + icon('emoji_events') + ' Hoàn thành chặng</span>' +
      '<h1>Chúc mừng bạn đã hoàn thành cấp ' + level + '!</h1>' +
      (isPathDone ? '<p>Bạn đã hoàn thành toàn bộ lộ trình học.</p>' : '') +
      cert + '</section>';
  }

  function renderError(root, message) {
    root.innerHTML = '<section class="sc-card sc-card--error">' +
      '<p>' + escapeHtml(message) + '</p>' +
      '<a class="sc-button" href="/learning/path">Về lộ trình của tôi</a></section>';
  }

  async function mount() {
    var root = document.getElementById('stageCompleteRoot');
    if (!root) return;
    if (!window.JlasAuth || !window.JlasAuth.requireLogin(window.location.pathname)) return;
    var stageId = root.getAttribute('data-stage-id');
    try {
      var response = await fetch('/api/learning/paths/current', { headers: window.JlasAuth.authHeaders() });
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok || body.success === false || !body.data) {
        renderError(root, 'Không tải được thông tin lộ trình.');
        return;
      }
      render(root, body.data, stageId);
    } catch (error) {
      renderError(root, 'Không thể kết nối tới máy chủ. Vui lòng thử lại.');
    }
  }

  return { mount: mount, findStage: findStage, isPathCompletionFor: isPathCompletionFor };
});
