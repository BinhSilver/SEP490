/* Renders the admin sidebar for the current role and starts the requested section. */
(function () {
  'use strict';

  var A = window.Admin;
  var root = document.getElementById('admRoot');
  var current = root.dataset.section;

  function allowedItems() {
    return A.MODULES.map(function (group) {
      return { title: group.title, items: group.items.filter(function (item) { return A.can(item.roles); }) };
    }).filter(function (group) { return group.items.length; });
  }

  function renderNav(counts) {
    document.getElementById('admNav').innerHTML = allowedItems().map(function (group) {
      return (group.title ? '<p class="adm-nav__group">' + A.esc(group.title) + '</p>' : '') +
        '<ul class="adm-nav__list">' + group.items.map(function (item) {
          var badge = item.badge && counts && counts[item.badge] > 0
            ? '<span class="adm-badge adm-badge--count" aria-label="' + counts[item.badge] + ' mục chờ">' + counts[item.badge] + '</span>' : '';
          return '<li><a class="adm-nav__link" href="' + item.href + '"' +
            (item.key === current ? ' aria-current="page"' : '') + '>' +
            '<i class="fa-solid ' + item.icon + '" aria-hidden="true"></i><span>' + A.esc(item.label) + '</span>' + badge + '</a></li>';
        }).join('') + '</ul>';
    }).join('');
  }

  async function refreshCounts() {
    try {
      renderNav(await A.api.get('/api/admin/moderation/overview'));
    } catch (ignored) { /* counts are optional */ }
  }

  async function boot() {
    if (window.JlasAuth && typeof window.JlasAuth.refreshUser === 'function') {
      await window.JlasAuth.refreshUser();
    }
    document.getElementById('admRole').textContent = A.ROLE_LABELS[A.role()] || '';
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

  boot();
})();
