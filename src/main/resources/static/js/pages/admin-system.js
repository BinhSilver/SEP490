/* System Admin sections (JLAS2-51): user accounts and the System Admin audit log. */
(function () {
  'use strict';

  var A = window.Admin;
  var API = '/api/admin/system';
  var ROLE_NAMES = { LEARNER: 'Học viên', CONTENT_CREATOR: 'Content Admin', CONTENT_ADMIN: 'Content Admin (vai trò cũ)' };
  // Roles a System Admin may assign (server enforces the same list).
  var ASSIGNABLE = ['LEARNER', 'CONTENT_CREATOR', 'MODERATOR', 'ADMIN', 'SUPER_ADMIN'];
  var STATUS = { ACTIVE: 'Đang hoạt động', LOCKED: 'Đã khoá' };
  var ACTIONS = {
    USER_LOCK: 'Khoá tài khoản', USER_UNLOCK: 'Mở khoá tài khoản',
    USER_ROLE_CHANGE: 'Đổi vai trò', PREMIUM_GRANT: 'Cấp Premium thủ công'
  };
  var MAX_GRANT_DAYS = 365;

  function roleLabel(role) { return ROLE_NAMES[role] || A.ROLE_LABELS[role] || role || '—'; }

  function me() {
    var u = window.JlasAuth && typeof window.JlasAuth.getUser === 'function' ? window.JlasAuth.getUser() : null;
    return u && u.id != null ? Number(u.id) : null;
  }

  function pager(page, totalPages) {
    return totalPages > 1 ? '<div class="adm-pager">' +
      '<button type="button" class="btn btn--secondary btn--sm" data-page="-1"' + (page <= 0 ? ' disabled' : '') + '>Trang trước</button>' +
      '<span class="adm-muted">Trang ' + (page + 1) + '/' + totalPages + '</span>' +
      '<button type="button" class="btn btn--secondary btn--sm" data-page="1"' + (page + 1 >= totalPages ? ' disabled' : '') + '>Trang sau</button>' +
    '</div>' : '';
  }

  // Server requires a trimmed reason of 5–500 characters; check it before sending.
  function reasonLength(modal) {
    return modal.querySelector('#admReason').value.trim().length < 5 ? { error: 'Lý do cần ít nhất 5 ký tự.' } : null;
  }

  var plansCache = null;
  async function grantablePlans() {
    if (!plansCache) {
      plansCache = (await A.api.get(API + '/premium-plans')).filter(function (p) { return String(p.name).toUpperCase() !== 'FREE'; });
    }
    return plansCache;
  }

  // ── Users ──────────────────────────────────────────────────────────────
  A.section('system-users', {
    subtitle: 'Tìm, xem và khoá hoặc mở khoá tài khoản. Mỗi thao tác cần lý do và được ghi vào nhật ký quản trị.',
    render: async function (root) {
      var page = 0;
      var rows = {};
      var detailId = null;
      root.innerHTML =
        '<form class="adm-toolbar adm-panel" data-filters novalidate>' +
          '<input class="input" type="search" name="q" maxlength="100" placeholder="Email, tên đăng nhập hoặc họ tên" aria-label="Tìm người dùng">' +
          '<select class="select" name="role" aria-label="Vai trò"><option value="">Mọi vai trò</option>' +
            ASSIGNABLE.concat(['CONTENT_ADMIN']).map(function (r) {
              return '<option value="' + r + '">' + A.esc(roleLabel(r)) + '</option>';
            }).join('') + '</select>' +
          '<select class="select" name="status" aria-label="Trạng thái"><option value="">Mọi trạng thái</option>' +
            '<option value="ACTIVE">' + STATUS.ACTIVE + '</option><option value="LOCKED">' + STATUS.LOCKED + '</option></select>' +
          '<select class="select" name="premium" aria-label="Premium"><option value="">Free và Premium</option>' +
            '<option value="true">Premium còn hạn</option><option value="false">Không có Premium</option></select>' +
          '<button type="submit" class="btn btn--primary btn--sm"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i> Lọc</button>' +
        '</form><div data-detail></div><div data-list></div>';
      var form = root.querySelector('[data-filters]');

      function statusBadge(u) {
        return u.locked ? '<span class="adm-badge adm-badge--danger">' + STATUS.LOCKED + '</span>'
          : '<span class="adm-badge adm-badge--ok">' + STATUS.ACTIVE + '</span>';
      }

      function premiumCell(u) {
        return u.premiumActive ? '<span class="adm-badge adm-badge--warn">Đến ' + A.date(u.premiumExpiredAt) + '</span>'
          : '<span class="adm-muted">Free</span>';
      }

      function lockButton(u) {
        if (u.id === me()) return '';
        return u.locked
          ? '<button type="button" class="btn btn--secondary btn--sm adm-nowrap" data-unlock="' + u.id + '"><i class="fa-solid fa-lock-open" aria-hidden="true"></i> Mở khoá</button>'
          : '<button type="button" class="btn btn--sm adm-btn--danger adm-nowrap" data-lock="' + u.id + '"><i class="fa-solid fa-lock" aria-hidden="true"></i> Khoá</button>';
      }

      function manageButtons(u) {
        if (u.id === me()) return '';
        return '<button type="button" class="btn btn--secondary btn--sm adm-nowrap" data-role-change="' + u.id + '">' +
            '<i class="fa-solid fa-user-gear" aria-hidden="true"></i> Đổi vai trò</button>' +
          '<button type="button" class="btn btn--primary btn--sm adm-nowrap" data-grant="' + u.id + '">' +
            '<i class="fa-solid fa-crown" aria-hidden="true"></i> Cấp Premium</button>';
      }

      async function changeRole(id) {
        var u = rows[id];
        var decision = await A.decide({
          title: 'Đổi vai trò của ' + (u.fullName || u.email) + '?',
          message: 'Vai trò mới có hiệu lực ngay ở lần thao tác tiếp theo của người dùng.',
          confirmText: 'Đổi vai trò', maxLength: 500,
          placeholder: 'Lý do (ít nhất 5 ký tự), lưu vào nhật ký quản trị',
          extraHtml: '<div class="field"><label class="field__label" for="admRoleSelect">Vai trò mới</label>' +
            '<select class="select" id="admRoleSelect" data-autofocus>' + ASSIGNABLE.map(function (r) {
              return '<option value="' + r + '"' + (r === u.role ? ' disabled' : '') + '>' + A.esc(roleLabel(r)) +
                (r === u.role ? ' (hiện tại)' : '') + '</option>';
            }).join('') + '</select></div>',
          collect: function (modal) {
            var role = modal.querySelector('#admRoleSelect').value;
            if (!role || role === u.role) return { error: 'Vui lòng chọn vai trò khác vai trò hiện tại.' };
            return reasonLength(modal) || { role: role };
          }
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/users/' + id + '/role', { role: decision.extra.role, reason: decision.reason });
          A.toast('Đã đổi vai trò', 'success');
        } catch (e) {
          A.toast(e.message, 'error');
        }
        await load();
        await showDetail(id);
      }

      async function grantPremium(id) {
        var u = rows[id];
        var plans;
        try {
          plans = await grantablePlans();
        } catch (e) {
          A.toast(e.message, 'error');
          return;
        }
        if (!plans.length) {
          A.toast('Chưa có gói Premium nào để cấp.', 'warning');
          return;
        }
        var stacking = u.premiumActive
          ? 'Người dùng đang có Premium đến ' + A.esc(A.date(u.premiumExpiredAt)) + ': số ngày cấp sẽ được <strong>cộng dồn</strong> vào hạn này.'
          : 'Người dùng chưa có Premium còn hạn: thời hạn tính từ bây giờ.';
        var decision = await A.decide({
          title: 'Cấp Premium cho ' + (u.fullName || u.email) + '?',
          message: stacking + ' Không tạo giao dịch thanh toán; người dùng nhận thông báo về gói và hạn mới.',
          confirmText: 'Cấp Premium', maxLength: 500,
          placeholder: 'Lý do (ít nhất 5 ký tự), lưu vào nhật ký quản trị',
          extraHtml:
            '<div class="field"><label class="field__label" for="admPlanSelect">Gói Premium</label>' +
              '<select class="select" id="admPlanSelect" data-autofocus>' + plans.map(function (p) {
                return '<option value="' + p.id + '" data-days="' + p.durationDays + '">' + A.esc(p.name) + ' · ' +
                  p.durationDays + ' ngày' + (p.active ? '' : ' (ngừng bán)') + '</option>';
              }).join('') + '</select></div>' +
            '<div class="field"><label class="field__label" for="admDays">Số ngày (1–' + MAX_GRANT_DAYS + ')</label>' +
              '<input class="input" id="admDays" inputmode="numeric" maxlength="3" value="' +
                Math.min(plans[0].durationDays, MAX_GRANT_DAYS) + '"></div>',
          collect: function (modal) {
            var days = Number(modal.querySelector('#admDays').value);
            if (!Number.isInteger(days) || days < 1 || days > MAX_GRANT_DAYS) {
              return { error: 'Số ngày phải từ 1 đến ' + MAX_GRANT_DAYS + '.' };
            }
            return reasonLength(modal) || { planId: Number(modal.querySelector('#admPlanSelect').value), durationDays: days };
          }
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/users/' + id + '/premium-grants', {
            planId: decision.extra.planId, durationDays: decision.extra.durationDays, reason: decision.reason
          });
          A.toast('Đã cấp Premium', 'success');
        } catch (e) {
          A.toast(e.message, 'error');
        }
        await load();
        await showDetail(id);
      }

      // Plan choice pre-fills its duration; the days field accepts digits only.
      document.addEventListener('change', function (event) {
        if (event.target.id !== 'admPlanSelect') return;
        var days = event.target.selectedOptions[0].dataset.days;
        var input = document.getElementById('admDays');
        if (input && days) input.value = Math.min(Number(days), MAX_GRANT_DAYS);
      });
      document.addEventListener('focusin', function (event) {
        if (event.target.id === 'admDays' && !event.target.dataset.digits) {
          event.target.dataset.digits = '1';
          A.digitsOnly(event.target);
        }
      });

      async function load() {
        var list = root.querySelector('[data-list]');
        list.innerHTML = A.loading();
        var params = new URLSearchParams({ page: page, size: 20 });
        ['q', 'role', 'status', 'premium'].forEach(function (k) {
          var value = form.elements[k].value.trim();
          if (value) params.set(k, value);
        });
        try {
          var data = await A.api.get(API + '/users?' + params.toString());
          rows = {};
          data.items.forEach(function (u) { rows[u.id] = u; });
          list.innerHTML = (data.items.length ?
            '<p class="adm-muted">' + data.totalElements + ' tài khoản</p>' +
            '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
              '<th scope="col">Người dùng</th><th scope="col">Vai trò</th><th scope="col">Trạng thái</th>' +
              '<th scope="col">Premium</th><th scope="col">CP</th><th scope="col">Đăng nhập gần nhất</th>' +
              '<th scope="col"><span class="sr-only">Thao tác</span></th></tr></thead><tbody>' +
              data.items.map(function (u) {
                return '<tr><td><span class="adm-user"><strong>' + A.esc(u.fullName || u.username || u.email) + '</strong>' +
                    '<span class="adm-muted">' + A.esc(u.email) + ' · #' + u.id + '</span></span></td>' +
                  '<td>' + A.esc(roleLabel(u.role)) + '</td>' +
                  '<td>' + statusBadge(u) + '</td>' +
                  '<td>' + premiumCell(u) + '</td>' +
                  '<td>' + (u.creditBalance == null ? '—' : u.creditBalance) + '</td>' +
                  '<td class="adm-muted">' + A.date(u.lastLoginAt) + '</td>' +
                  '<td class="adm-nowrap"><button type="button" class="btn btn--secondary btn--sm" data-detail-id="' + u.id + '">Chi tiết</button> ' +
                    lockButton(u) + '</td></tr>';
              }).join('') + '</tbody></table></div>'
            : A.empty('fa-user-slash', 'Không có tài khoản phù hợp', 'Thử bỏ bớt điều kiện lọc.')) +
            pager(page, data.totalPages);
        } catch (e) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được danh sách', e.message);
        }
      }

      async function showDetail(id) {
        var box = root.querySelector('[data-detail]');
        detailId = id;
        box.innerHTML = '<section class="adm-panel">' + A.loading() + '</section>';
        try {
          var u = await A.api.get(API + '/users/' + id);
          var fields = [
            ['Email', u.email], ['Tên đăng nhập', u.username], ['Họ tên', u.fullName], ['Vai trò', roleLabel(u.role)],
            ['Đăng nhập bằng', u.loginType === 'GOOGLE' ? 'Google' : 'Email/mật khẩu'],
            ['Xác thực email', u.verified ? 'Đã xác thực' : 'Chưa xác thực'],
            ['Trạng thái', u.locked ? STATUS.LOCKED + ' lúc ' + A.date(u.lockedAt) : STATUS.ACTIVE],
            ['Lý do khoá', u.lockedReason],
            ['Premium', u.premiumActive ? (u.premiumPlanName || 'Premium') + ' đến ' + A.date(u.premiumExpiredAt) : 'Free'],
            ['Số dư CP', u.creditBalance], ['Điện thoại', u.phoneNumber], ['Trình độ', u.japaneseLevel],
            ['Ngày tạo', A.date(u.createdAt)], ['Đăng nhập gần nhất', A.date(u.lastLoginAt)]
          ];
          box.innerHTML = '<section class="adm-panel" aria-label="Chi tiết tài khoản">' +
            '<h2 class="adm-panel__title">Tài khoản #' + u.id + '</h2>' +
            '<dl class="adm-detail">' + fields.filter(function (f) { return f[1] != null && f[1] !== ''; }).map(function (f) {
              return '<dt>' + f[0] + '</dt><dd>' + A.esc(f[1]) + '</dd>';
            }).join('') + '</dl>' +
            '<p class="adm-toolbar">' + lockButton(u) + manageButtons(u) +
              '<button type="button" class="btn btn--secondary btn--sm" data-close-detail>Đóng</button></p>' +
          '</section>';
          rows[u.id] = u;
          box.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } catch (e) {
          box.innerHTML = '<p class="adm-error">' + A.esc(e.message) + '</p>';
        }
      }

      async function setLocked(id, lock) {
        var u = rows[id] || { email: '#' + id };
        var decision = await A.decide({
          title: (lock ? 'Khoá tài khoản ' : 'Mở khoá tài khoản ') + (u.fullName || u.email) + '?',
          message: lock ? 'Người dùng bị đăng xuất khỏi mọi thiết bị và không thể đăng nhập cho đến khi được mở khoá.'
            : 'Người dùng có thể đăng nhập lại ngay sau khi mở khoá.',
          danger: lock, confirmText: lock ? 'Khoá tài khoản' : 'Mở khoá', maxLength: 500,
          placeholder: 'Lý do (ít nhất 5 ký tự), lưu vào nhật ký quản trị', collect: reasonLength
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/users/' + id + (lock ? '/lock' : '/unlock'), { reason: decision.reason });
          A.toast(lock ? 'Đã khoá tài khoản' : 'Đã mở khoá tài khoản', 'success');
        } catch (e) {
          A.toast(e.message, 'error');
        }
        await load();
        if (detailId === id) await showDetail(id);
      }

      form.addEventListener('submit', function (event) { event.preventDefault(); page = 0; load(); });
      root.addEventListener('click', function (event) {
        var target = event.target;
        var el;
        if ((el = target.closest('[data-page]'))) { page += Number(el.dataset.page); load(); return; }
        if ((el = target.closest('[data-detail-id]'))) { showDetail(Number(el.dataset.detailId)); return; }
        if ((el = target.closest('[data-lock]'))) { setLocked(Number(el.dataset.lock), true); return; }
        if ((el = target.closest('[data-unlock]'))) { setLocked(Number(el.dataset.unlock), false); return; }
        if ((el = target.closest('[data-role-change]'))) { changeRole(Number(el.dataset.roleChange)); return; }
        if ((el = target.closest('[data-grant]'))) { grantPremium(Number(el.dataset.grant)); return; }
        if (target.closest('[data-close-detail]')) { detailId = null; root.querySelector('[data-detail]').innerHTML = ''; }
      });
      await load();
    }
  });

  // ── Audit log ──────────────────────────────────────────────────────────
  function changesCell(json) {
    if (!json) return '<span class="adm-muted">—</span>';
    var c;
    try { c = JSON.parse(json); } catch (e) { return '<code>' + A.esc(json) + '</code>'; }
    var to = ' <span class="adm-muted">thành</span> ';
    if (c.rejection) return A.esc(c.rejection);
    if (c.before && c.after && c.before.status && c.after.status) {
      return A.esc(STATUS[c.before.status] || c.before.status) + to + A.esc(STATUS[c.after.status] || c.after.status);
    }
    if (c.before && c.after && c.before.role && c.after.role) {
      return A.esc(roleLabel(c.before.role)) + to + A.esc(roleLabel(c.after.role));
    }
    if (c.before && c.after && 'expiresAt' in c.after) {
      var was = c.before.active ? (c.before.plan || 'Premium') + ' đến ' + A.date(c.before.expiresAt) : 'Không có Premium';
      return A.esc(was) + to + A.esc(c.after.plan + ' đến ' + A.date(c.after.expiresAt)) +
        '<br><span class="adm-muted">+' + A.esc(c.durationDays) + ' ngày' + (c.stacked ? ', cộng dồn' : '') + '</span>';
    }
    return '<code>' + A.esc(json) + '</code>';
  }

  A.section('system-audit', {
    subtitle: 'Thao tác của Quản trị hệ thống: ai làm, lúc nào, với đối tượng nào, vì sao và kết quả. Dữ liệu nhạy cảm đã được che.',
    render: async function (root) {
      var page = 0;
      root.innerHTML =
        '<form class="adm-toolbar adm-panel" data-filters novalidate>' +
          '<select class="select" name="action" aria-label="Hành động"><option value="">Mọi hành động</option>' +
            Object.keys(ACTIONS).map(function (k) { return '<option value="' + k + '">' + ACTIONS[k] + '</option>'; }).join('') +
          '</select>' +
          '<select class="select" name="result" aria-label="Kết quả"><option value="">Mọi kết quả</option>' +
            '<option value="SUCCESS">Thành công</option><option value="REJECTED">Bị từ chối</option></select>' +
          '<input class="input" name="actorId" inputmode="numeric" maxlength="12" placeholder="Mã người thao tác" aria-label="Mã người thao tác">' +
          '<input class="input" name="targetId" inputmode="numeric" maxlength="12" placeholder="Mã người dùng bị tác động" aria-label="Mã người dùng bị tác động">' +
          '<label class="adm-inline">Từ <input class="input" type="date" name="from"></label>' +
          '<label class="adm-inline">Đến <input class="input" type="date" name="to"></label>' +
          '<button type="submit" class="btn btn--primary btn--sm">Lọc</button>' +
        '</form><div data-list></div>';
      var form = root.querySelector('[data-filters]');
      A.digitsOnly(form.elements.actorId);
      A.digitsOnly(form.elements.targetId);

      async function load() {
        var list = root.querySelector('[data-list]');
        list.innerHTML = A.loading();
        var params = new URLSearchParams({ page: page, size: 30 });
        ['action', 'result', 'actorId'].forEach(function (k) { if (form.elements[k].value) params.set(k, form.elements[k].value); });
        if (form.elements.targetId.value) {
          params.set('targetType', 'USER');
          params.set('targetId', form.elements.targetId.value);
        }
        // The API takes ISO date-times; whole days are inclusive.
        if (form.elements.from.value) params.set('from', form.elements.from.value + 'T00:00:00');
        if (form.elements.to.value) params.set('to', form.elements.to.value + 'T23:59:59.999');
        try {
          var data = await A.api.get(API + '/audit-logs?' + params.toString());
          list.innerHTML = (data.items.length ?
            '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
              '<th scope="col">Thời gian</th><th scope="col">Người thao tác</th><th scope="col">Hành động</th>' +
              '<th scope="col">Đối tượng</th><th scope="col">Lý do</th><th scope="col">Kết quả</th>' +
              '<th scope="col">Thay đổi</th></tr></thead><tbody>' +
              data.items.map(function (a) {
                return '<tr><td class="adm-muted adm-nowrap">' + A.date(a.createdAt) + '</td>' +
                  '<td><span class="adm-user"><strong>' + A.esc(a.actorEmail) + '</strong><span class="adm-muted">#' + a.actorId + '</span></span></td>' +
                  '<td>' + A.esc(ACTIONS[a.action] || a.action) + '</td>' +
                  '<td>' + A.esc(A.typeLabel(a.targetType)) + (a.targetId ? ' #' + A.esc(a.targetId) : '') + '</td>' +
                  '<td>' + A.esc(a.reason || '—') + '</td>' +
                  '<td>' + (a.result === 'SUCCESS' ? '<span class="adm-badge adm-badge--ok">Thành công</span>'
                    : '<span class="adm-badge adm-badge--warn">Bị từ chối</span>') + '</td>' +
                  '<td>' + changesCell(a.changesJson) + '</td></tr>';
              }).join('') + '</tbody></table></div>'
            : A.empty('fa-inbox', 'Chưa có bản ghi nào')) +
            pager(page, data.totalPages);
        } catch (e) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được nhật ký', e.message);
        }
      }

      form.addEventListener('submit', function (event) { event.preventDefault(); page = 0; load(); });
      root.addEventListener('click', function (event) {
        var button = event.target.closest('[data-page]');
        if (!button) return;
        page += Number(button.dataset.page);
        load();
      });
      await load();
    }
  });
})();
