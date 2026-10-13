/* Renders the admin sidebar for the current role and starts the requested section. */
window.AdminShell.ready.then(function (allowed) {
  'use strict';
  if (!allowed) return;

  var A = window.Admin;
  var root = document.getElementById('admRoot');
  var current = root.dataset.section;

  function renderNav(counts) {
    window.AdminShell.renderNav(counts);
  }

  async function refreshCounts() {
    if (!A.can(A.MODERATION)) return; // badge counts are moderation-only
    try {
      renderNav(await A.api.get('/api/admin/moderation/overview'));
    } catch (ignored) { /* counts are optional */ }
  }

  async function boot() {
    renderNav(null);

    var item = null;
    A.MODULES.forEach(function (group) {
      group.items.forEach(function (i) { if (i.key === current) item = i; });
    });
    var section = A.sections[current];
    var content = document.getElementById('admContent');
    if (!item || !section) {
      content.innerHTML = A.empty('fa-circle-question', 'Không tìm thấy mục này');
      return;
    }
    document.getElementById('admTitle').textContent = item.label;
    document.getElementById('admSubtitle').textContent = section.subtitle || '';
    document.title = item.label + ' | Trang quản trị | JLAS';
    if (!A.can(item.roles)) {
      content.innerHTML = A.empty('fa-lock', 'Bạn không có quyền xem mục này',
        'Liên hệ quản trị viên nếu bạn cần được cấp quyền.');
      return;
    }
    content.innerHTML = A.loading();
    try {
      await section.render(content, { refreshCounts: refreshCounts });
    } catch (error) {
      content.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được dữ liệu', error.message);
    }
    refreshCounts();
  }

  return boot();
});
