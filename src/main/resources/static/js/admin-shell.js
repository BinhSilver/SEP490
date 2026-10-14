(function (global) {
  'use strict';

  var admin = global.Admin;
  var main = document.getElementById('jlabBaseContent');
  var shell = { user: null };
  var current;
  global.AdminShell = shell;

  document.getElementById('adminShellMount').innerHTML =
    '<a class="admin-skip" href="#jlabBaseContent">Đến nội dung</a>' +
    '<header class="admin-topbar"><button id="adminShellToggle" class="admin-menu-button" type="button" ' +
    'aria-label="Mở menu quản trị" aria-controls="adminShellSidebar" aria-expanded="false">' +
    '<i class="fa-solid fa-bars" aria-hidden="true"></i></button>' +
    '<span id="adminShellTitle">J-LAS Admin</span><div class="admin-account">' +
    '<span id="adminShellName"></span><span id="adminShellRole" class="admin-role"></span>' +
    '<button id="adminShellLogout" class="admin-logout" type="button">Đăng xuất</button></div></header>' +
    '<aside id="adminShellSidebar" class="admin-sidebar" aria-label="Quản trị J-LAS">' +
    '<div class="admin-brand"><img src="/images/jlab.png" alt="" width="36" height="36">' +
    '<strong>J-LAS <span>Admin Portal</span></strong></div>' +
    '<nav id="adminShellNav" aria-label="Chức năng quản trị"></nav></aside>' +
    '<div id="adminShellStatus" class="admin-status" role="status">Đang xác thực tài khoản…</div>';

  var toggle = document.getElementById('adminShellToggle');
  var sidebar = document.getElementById('adminShellSidebar');
  function setMenu(open) {
    sidebar.setAttribute('data-open', String(open));
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Đóng menu quản trị' : 'Mở menu quản trị');
  }
  toggle.addEventListener('click', function () {
    setMenu(toggle.getAttribute('aria-expanded') !== 'true');
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') {
      setMenu(false);
      toggle.focus();
    }
  });
  document.getElementById('adminShellLogout').addEventListener('click', async function () {
    await global.JlasAuth.logout('/login');
  });

  shell.renderNav = function (counts) {
    document.getElementById('adminShellNav').innerHTML = admin.MODULES.map(function (group) {
      var items = group.items.filter(function (item) { return admin.can(item.roles); });
      if (!items.length) return '';
      return (group.title ? '<p class="admin-nav-group">' + admin.esc(group.title) + '</p>' : '') +
        '<ul class="admin-nav-list">' + items.map(function (item) {
          var count = item.badge && counts && Number(counts[item.badge]);
          return '<li><a href="' + admin.esc(item.href) + '"' + (item === current ? ' aria-current="page"' : '') + '>' +
            '<i class="fa-solid ' + admin.esc(item.icon) + '" aria-hidden="true"></i><span>' + admin.esc(item.label) + '</span>' +
            (Number.isFinite(count) && count > 0 ? '<span class="admin-nav-count">' + count + '</span>' : '') + '</a></li>';
        }).join('') + '</ul>';
    }).join('');
  };

  async function boot() {
    var status = document.getElementById('adminShellStatus');
    try {
      var auth = global.JlasAuth;
      if (!auth) throw new Error('Không thể khởi tạo phiên đăng nhập.');
      var response = await fetch('/api/auth/me', { headers: auth.authHeaders() });
      if (response.status === 401) {
        auth.clearSession();
        global.location.replace('/login?redirect=' + encodeURIComponent(global.location.pathname + global.location.search));
        return false;
      }
      if (!response.ok) throw new Error(response.status === 403
        ? 'Bạn không có quyền truy cập trang quản trị.' : 'Không thể xác thực tài khoản. Vui lòng tải lại trang.');
      var payload = await response.json();
      if (!payload.success || !payload.data) throw new Error('Không thể xác thực tài khoản. Vui lòng tải lại trang.');
      shell.user = payload.data;
      auth.setSession(null, null, shell.user);
      var pathname = global.location.pathname.replace(/\/$/, '') || '/';
      var route = /^\/textbooks\/lessons\/\d+\/edit$/.test(pathname) ? '/textbooks/manage' : pathname;
      admin.MODULES.forEach(function (group) {
        group.items.forEach(function (item) { if (item.href === route) current = item; });
      });
      shell.renderNav(null);
      document.getElementById('adminShellName').textContent = shell.user.fullName || shell.user.email || '';
      document.getElementById('adminShellRole').textContent = admin.ROLE_LABELS[admin.role()] || '';
      if (!current || !admin.can(current.roles)) {
        status.innerHTML = admin.empty('fa-lock', 'Bạn không có quyền xem mục này', 'Liên hệ quản trị viên nếu cần được cấp quyền.');
        return false;
      }
      document.getElementById('adminShellTitle').textContent = current.label;
      status.hidden = true;
      main.hidden = false;
      return true;
    } catch (error) {
      shell.user = null;
      status.innerHTML = admin.empty('fa-circle-exclamation', 'Không thể mở trang quản trị', error.message);
      return false;
    }
  }

  shell.ready = boot();
})(window);
