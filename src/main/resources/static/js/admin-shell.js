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
    '<span id="adminShellTitle">Quản trị KotsuKotsu</span>' +
    '<div id="adminShellThemeMount" class="admin-theme-control"></div></header>' +
    '<aside id="adminShellSidebar" class="admin-sidebar" aria-label="Quản trị KotsuKotsu">' +
    '<div class="admin-brand"><img src="/images/jlab.png" alt="" width="36" height="36">' +
    '<strong>KotsuKotsu <span>Cổng quản trị</span></strong></div>' +
    '<nav id="adminShellNav" aria-label="Chức năng quản trị"></nav>' +
    '<div class="admin-sidebar__footer">' +
      '<div class="admin-sidebar__account"><span id="adminShellInitials" class="admin-sidebar__avatar"></span>' +
        '<div class="admin-sidebar__info"><span id="adminShellName"></span><span id="adminShellRole" class="admin-role"></span></div></div>' +
      '<button id="adminShellLogout" class="admin-logout" type="button">' +
        '<i class="fa-solid fa-right-from-bracket" aria-hidden="true"></i><span>Đăng xuất</span></button>' +
    '</div></aside>' +
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

  var NAV_STATE_KEY = 'jlas-admin-nav-collapsed';
  function readCollapsed() {
    try { return JSON.parse(global.localStorage.getItem(NAV_STATE_KEY) || '{}'); } catch (ignored) { return {}; }
  }
  function writeCollapsed(state) {
    try { global.localStorage.setItem(NAV_STATE_KEY, JSON.stringify(state)); } catch (ignored) { /* ignore */ }
  }

  // Every menu item is a full page load: carry the sidebar's scroll offset over so it doesn't jump back to the top.
  var SCROLL_KEY = 'jlas-admin-sidebar-scroll';
  sidebar.addEventListener('click', function (event) {
    if (!event.target.closest || !event.target.closest('a[href]')) return;
    try { global.sessionStorage.setItem(SCROLL_KEY, String(sidebar.scrollTop || 0)); } catch (ignored) { /* ignore */ }
  });
  function restoreScroll() {
    var saved = null;
    try { saved = global.sessionStorage.getItem(SCROLL_KEY); } catch (ignored) { /* ignore */ }
    if (saved !== null) sidebar.scrollTop = Number(saved) || 0;
    var active = document.getElementById('adminShellNav').querySelectorAll('[aria-current="page"]')[0];
    if (active && active.scrollIntoView) active.scrollIntoView({ block: 'nearest' });
  }

  shell.renderNav = function (counts) {
    var collapsed = readCollapsed();
    var nav = document.getElementById('adminShellNav');
    nav.innerHTML = admin.MODULES.map(function (group, index) {
      var items = group.items.filter(function (item) { return admin.can(item.roles); });
      if (!items.length) return '';
      if (!group.title) {
        return '<ul class="admin-nav-list">' + items.map(renderItem).join('') + '</ul>';
      }
      var groupId = 'adminNavGroup' + index;
      var hasCurrent = items.indexOf(current) !== -1;
      var isOpen = hasCurrent || collapsed[group.title] !== true;
      return '<button type="button" class="admin-nav-group" data-group="' + admin.esc(group.title) + '" ' +
          'aria-expanded="' + isOpen + '" aria-controls="' + groupId + '">' +
          '<span>' + admin.esc(group.title) + '</span><i class="fa-solid fa-chevron-down" aria-hidden="true"></i>' +
        '</button>' +
        '<ul class="admin-nav-list" id="' + groupId + '"' + (isOpen ? '' : ' hidden') + '>' +
          items.map(renderItem).join('') + '</ul>';

      function renderItem(item) {
        var count = item.badge && counts && Number(counts[item.badge]);
        return '<li><a href="' + admin.esc(item.href) + '"' + (item === current ? ' aria-current="page"' : '') + '>' +
          '<i class="fa-solid ' + admin.esc(item.icon) + '" aria-hidden="true"></i><span>' + admin.esc(item.label) + '</span>' +
          (Number.isFinite(count) && count > 0 ? '<span class="admin-nav-count">' + count + '</span>' : '') + '</a></li>';
      }
    }).join('');

    nav.querySelectorAll('.admin-nav-group').forEach(function (button) {
      button.addEventListener('click', function () {
        var open = button.getAttribute('aria-expanded') !== 'true';
        button.setAttribute('aria-expanded', String(open));
        document.getElementById(button.getAttribute('aria-controls')).hidden = !open;
        var state = readCollapsed();
        state[button.dataset.group] = !open;
        writeCollapsed(state);
      });
    });
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
      // Trang con của "Giáo trình và bài học" (soạn bài, tạo giáo trình, thêm bài) tô sáng mục /textbooks/manage.
      var route = /^\/textbooks\/(lessons\/\d+\/edit|manage\/new|manage\/\d+\/lessons\/new)$/.test(pathname)
        ? '/textbooks/manage' : pathname;
      admin.MODULES.forEach(function (group) {
        group.items.forEach(function (item) { if (item.href === route) current = item; });
      });
      shell.renderNav(null);
      restoreScroll();
      var name =shell.user.fullName || shell.user.email || '';
      document.getElementById('adminShellName').textContent = name;
      document.getElementById('adminShellRole').textContent = admin.ROLE_LABELS[admin.role()] || '';
      document.getElementById('adminShellInitials').textContent =
        name.split(' ').filter(Boolean).map(function (w) { return w[0]; }).join('').slice(-2).toUpperCase();
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
