/* System Admin sections (JLAS2-51/49): dashboard, accounts, Premium plans, payment lookup and the audit log. */
(function () {
  'use strict';

  var A = window.Admin;
  var API = '/api/admin/system';
  var ROLE_NAMES = { LEARNER: 'Học viên', CONTENT_CREATOR: 'Content Creator' };
  // Roles a System Admin may assign (server enforces the same list). Premium is a plan, not a role.
  var ASSIGNABLE = ['LEARNER', 'CONTENT_CREATOR', 'MODERATOR', 'SUPER_ADMIN'];
  var STATUS = { ACTIVE: 'Đang hoạt động', LOCKED: 'Đã khoá' };
  var ACTIONS = {
    USER_LOCK: 'Khoá tài khoản', USER_UNLOCK: 'Mở khoá tài khoản',
    USER_ROLE_CHANGE: 'Đổi vai trò', PREMIUM_GRANT: 'Cấp Premium thủ công', USER_CREDIT_ADJUST: 'Điều chỉnh CP',
    PREMIUM_PLAN_CREATE: 'Tạo gói Premium', PREMIUM_PLAN_UPDATE: 'Sửa gói Premium',
    PREMIUM_PLAN_DEACTIVATE: 'Ngừng bán gói', PREMIUM_PLAN_ACTIVATE: 'Mở bán gói'
  };
  var PAYMENT_STATUS = {
    PENDING: ['Đang chờ', 'warn'], PAID: ['Đã thanh toán', 'ok'], FAILED: ['Thất bại', 'danger'],
    CANCELLED: ['Đã huỷ', ''], EXPIRED: ['Hết hạn', ''], REFUNDED: ['Đã hoàn tiền', '']
  };

  function money(amount, currency) {
    if (amount == null) return '—';
    return Number(amount).toLocaleString('vi-VN', { maximumFractionDigits: 2 }) + (currency ? ' ' + currency : '');
  }

  function paymentBadge(status) {
    var s = PAYMENT_STATUS[status] || [status, ''];
    return '<span class="adm-badge' + (s[1] ? ' adm-badge--' + s[1] : '') + '">' + A.esc(s[0]) + '</span>';
  }
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
      plansCache = (await A.api.get(API + '/premium-plans')).filter(function (p) { return p.active && !p.free; });
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
            ASSIGNABLE.map(function (r) {
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
          A.toast('Chưa có gói Premium nào đang bán để cấp.', 'warning');
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
                  p.durationDays + ' ngày</option>';
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
              '<button type="button" class="btn btn--secondary btn--sm" data-adjust="' + u.id + '">Điều chỉnh CP</button>' +
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

      async function adjustCredits(id) {
        var u = rows[id];
        var decision = await A.decide({
          title: 'Điều chỉnh CP cho ' + (u.fullName || u.email),
          message: 'Số dư hiện tại: ' + u.creditBalance + ' CP. Nhập số dương để cộng, số âm để trừ.',
          confirmText: 'Lưu điều chỉnh', maxLength: 500,
          extraHtml: '<div class="field"><label class="field__label" for="admCreditAmount">Số CP (−100.000 đến 100.000)</label>' +
            '<input class="input" id="admCreditAmount" type="number" min="-100000" max="100000" step="1" data-autofocus></div>',
          collect: function (modal) {
            var amount = Number(modal.querySelector('#admCreditAmount').value);
            if (!Number.isInteger(amount) || amount === 0 || Math.abs(amount) > 100000)
              return { error: 'Số CP phải khác 0 và tối đa 100.000.' };
            return reasonLength(modal) || { amount: amount };
          }
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/users/' + id + '/credits', { amount: decision.extra.amount, reason: decision.reason });
          A.toast('Đã điều chỉnh CP', 'success');
          await load(); await showDetail(id);
        } catch (e) { A.toast(e.message, 'error'); }
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
        if ((el = target.closest('[data-adjust]'))) { adjustCredits(Number(el.dataset.adjust)); return; }
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
    if (c.before && c.after && 'active' in c.after) {
      return (c.before.active ? 'Đang bán' : 'Ngừng bán') + to + (c.after.active ? 'Đang bán' : 'Ngừng bán');
    }
    if (c.after && 'durationDays' in c.after) {
      var fields = { name: 'Tên', description: 'Mô tả', price: 'Giá', durationDays: 'Số ngày' };
      if (!c.before) {
        return 'Gói mới: ' + A.esc(c.after.name) + ' · ' + A.esc(money(c.after.price)) + ' · ' + A.esc(c.after.durationDays) + ' ngày';
      }
      return Object.keys(fields).filter(function (k) { return String(c.before[k]) !== String(c.after[k]); })
        .map(function (k) {
          return fields[k] + ': ' + A.esc(c.before[k] == null ? '—' : c.before[k]) + to + A.esc(c.after[k] == null ? '—' : c.after[k]);
        }).join('<br>');
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
          '<select class="select" name="targetType" aria-label="Loại đối tượng"><option value="USER">Người dùng</option>' +
            '<option value="PREMIUM_PLAN">Gói Premium</option></select>' +
          '<input class="input" name="targetId" inputmode="numeric" maxlength="12" placeholder="Mã đối tượng" aria-label="Mã đối tượng">' +
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
          params.set('targetType', form.elements.targetType.value);
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

  // ── Dashboard ──────────────────────────────────────────────────────────
  function stat(href, icon, label, value, note) {
    return '<a class="adm-stat" href="' + href + '"><i class="fa-solid ' + icon + '" aria-hidden="true"></i>' +
      '<span class="adm-stat__label">' + A.esc(label) + '</span>' +
      '<strong class="adm-stat__value">' + A.esc(value) + '</strong>' +
      (note ? '<span class="adm-muted">' + A.esc(note) + '</span>' : '') + '</a>';
  }

  function countTable(caption, rows) {
    return '<section class="adm-panel"><h2 class="adm-panel__title">' + A.esc(caption) + '</h2>' +
      (rows.length ? '<table class="adm-table"><tbody>' + rows.map(function (r) {
        return '<tr><td>' + r[0] + '</td><td class="adm-nowrap"><strong>' + A.esc(r[1]) + '</strong></td></tr>';
      }).join('') + '</tbody></table>' : A.empty('fa-inbox', 'Chưa có dữ liệu')) + '</section>';
  }

  A.section('system-dashboard', {
    subtitle: 'Toàn cảnh tài khoản, Premium, gói bán, thanh toán và việc kiểm duyệt đang chờ.',
    render: async function (root) {
      var d = await A.api.get(API + '/dashboard');
      var moderation = null;
      try { moderation = await A.api.get('/api/admin/moderation/overview'); } catch (ignored) { /* optional */ }
      var pending = d.paymentsByStatus.PENDING || 0;
      var revenue = d.paidLast30Days.length
        ? d.paidLast30Days.map(function (r) { return money(r.amount, r.currency); }).join(' + ') : '0';
      var paidCount = d.paidLast30Days.reduce(function (n, r) { return n + r.count; }, 0);
      root.innerHTML =
        '<div class="adm-stats">' +
          stat('/admin/system/users', 'fa-users', 'Tài khoản', d.totalUsers,
            '+' + d.newUsersLast7Days + ' trong 7 ngày, +' + d.newUsersLast30Days + ' trong 30 ngày') +
          stat('/admin/system/users', 'fa-crown', 'Premium còn hạn', d.premiumActive, '') +
          stat('/admin/system/users', 'fa-lock', 'Tài khoản bị khoá', d.lockedUsers,
            d.unverifiedUsers + ' tài khoản chưa xác thực email') +
          stat('/admin/system/plans', 'fa-tags', 'Gói đang bán', d.plansOnSale,
            d.plansStopped ? d.plansStopped + ' gói ngừng bán' : '') +
          stat('/admin/system/payments', 'fa-receipt', 'Doanh thu 30 ngày', revenue, paidCount + ' giao dịch thành công') +
          stat('/admin/system/payments?productType=PREMIUM', 'fa-crown', 'Premium 30 ngày',
            (d.paidByProductLast30Days || []).filter(function (r) { return r.productType === 'PREMIUM'; })
              .map(function (r) { return money(r.amount, r.currency); }).join(' + ') || '0', '') +
          stat('/admin/system/payments?productType=CREDIT', 'fa-coins', 'CP 30 ngày',
            (d.paidByProductLast30Days || []).filter(function (r) { return r.productType === 'CREDIT'; })
              .map(function (r) { return money(r.amount, r.currency); }).join(' + ') || '0', '') +
          stat('/admin/system/payments', 'fa-tag', 'Đã giảm giá 30 ngày', money(d.discountLast30Days, 'VND'), '') +
          stat('/admin/system/payments', 'fa-hourglass-half', 'Giao dịch đang chờ', pending, '') +
        '</div>' +
        (moderation ? '<h2 class="adm-panel__title adm-section-title">Kiểm duyệt đang chờ</h2><div class="adm-stats">' +
          stat('/admin/moderation/queue', 'fa-robot', 'Hàng đợi AI', moderation.pendingQueue, '') +
          stat('/admin/moderation/appeals', 'fa-scale-balanced', 'Kháng nghị chờ', moderation.pendingAppeals, '') +
          stat('/admin/moderation/dictionary', 'fa-book', 'Đóng góp từ điển chờ', moderation.pendingContributions, '') +
          stat('/admin/moderation/sanctions', 'fa-user-lock', 'Chế tài đang hiệu lực', moderation.activeSanctions, '') +
        '</div>' : '') +
        '<div class="adm-grid-2 adm-section-gap">' +
          countTable('Tài khoản theo vai trò', Object.keys(d.usersByRole).map(function (r) {
            return [A.esc(roleLabel(r)), d.usersByRole[r]];
          })) +
          countTable('Giao dịch theo trạng thái', Object.keys(d.paymentsByStatus).map(function (s) {
            return [paymentBadge(s), d.paymentsByStatus[s]];
          })) +
        '</div>' +
        '<section class="adm-panel adm-section-gap"><h2 class="adm-panel__title">Thao tác quản trị gần đây</h2>' +
          (d.recentActions.length ? '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
            '<th scope="col">Thời gian</th><th scope="col">Người thao tác</th><th scope="col">Hành động</th>' +
            '<th scope="col">Đối tượng</th><th scope="col">Kết quả</th></tr></thead><tbody>' +
            d.recentActions.map(function (a) {
              return '<tr><td class="adm-muted adm-nowrap">' + A.date(a.createdAt) + '</td><td>' + A.esc(a.actorEmail) + '</td>' +
                '<td>' + A.esc(ACTIONS[a.action] || a.action) + '</td>' +
                '<td>' + A.esc(A.typeLabel(a.targetType)) + (a.targetId ? ' #' + A.esc(a.targetId) : '') + '</td>' +
                '<td>' + (a.result === 'SUCCESS' ? '<span class="adm-badge adm-badge--ok">Thành công</span>'
                  : '<span class="adm-badge adm-badge--warn">Bị từ chối</span>') + '</td></tr>';
            }).join('') + '</tbody></table></div>' +
            '<p><a class="adm-link" href="/admin/system/audit">Xem toàn bộ nhật ký quản trị</a></p>'
            : A.empty('fa-inbox', 'Chưa có thao tác nào')) +
        '</section>';
    }
  });

  // ── Premium plans ──────────────────────────────────────────────────────
  var PRICE_PATTERN = /^\d{1,10}(\.\d{1,2})?$/;

  // The days field of the plan dialog accepts digits only.
  document.addEventListener('focusin', function (event) {
    if (event.target.id === 'admPlanDays' && !event.target.dataset.digits) {
      event.target.dataset.digits = '1';
      A.digitsOnly(event.target);
    }
  });

  A.section('system-plans', {
    subtitle: 'Gói Premium đang bán. Sửa gói chỉ áp dụng cho lần mua sau: giao dịch cũ giữ thông tin lúc mua, ' +
      'người đang dùng giữ nguyên hạn. Gói không bị xoá, chỉ ngừng bán.',
    render: async function (root) {
      var plans = {};
      root.innerHTML = '<p class="adm-toolbar"><button type="button" class="btn btn--primary btn--sm" data-create>' +
        '<i class="fa-solid fa-plus" aria-hidden="true"></i> Tạo gói mới</button></p><div data-list></div>';

      function statusCell(p) {
        if (p.free) return '<span class="adm-badge">Gói mặc định</span>';
        return p.active ? '<span class="adm-badge adm-badge--ok">Đang bán</span>'
          : '<span class="adm-badge adm-badge--danger">Ngừng bán</span>';
      }

      function actions(p) {
        if (p.free) return '<span class="adm-muted">Không sửa được</span>';
        return '<button type="button" class="btn btn--secondary btn--sm" data-edit="' + p.id + '">' +
            '<i class="fa-solid fa-pen" aria-hidden="true"></i> Sửa</button> ' +
          (p.active
            ? '<button type="button" class="btn btn--sm adm-btn--danger adm-nowrap" data-toggle="' + p.id + '">Ngừng bán</button>'
            : '<button type="button" class="btn btn--secondary btn--sm adm-nowrap" data-toggle="' + p.id + '">Mở bán</button>');
      }

      async function load() {
        var list = root.querySelector('[data-list]');
        list.innerHTML = A.loading();
        try {
          var data = await A.api.get(API + '/premium-plans');
          plans = {};
          data.forEach(function (p) { plans[p.id] = p; });
          list.innerHTML = '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
            '<th scope="col">Gói</th><th scope="col">Giá</th><th scope="col">Số ngày</th><th scope="col">Trạng thái</th>' +
            '<th scope="col">Đang dùng</th><th scope="col">Cập nhật</th><th scope="col"><span class="sr-only">Thao tác</span></th>' +
            '</tr></thead><tbody>' + data.map(function (p) {
              return '<tr><td><span class="adm-user"><strong>' + A.esc(p.name) + '</strong>' +
                  (p.description ? '<span class="adm-muted">' + A.esc(p.description) + '</span>' : '') + '</span></td>' +
                '<td class="adm-nowrap">' + A.esc(money(p.price)) + '</td>' +
                '<td>' + A.esc(p.durationDays) + '</td>' +
                '<td>' + statusCell(p) + '</td>' +
                '<td>' + (p.free ? '<span class="adm-muted">—</span>' : A.esc(p.activeSubscribers) + ' tài khoản') + '</td>' +
                '<td class="adm-muted adm-nowrap">' + A.date(p.updatedAt) + '</td>' +
                '<td class="adm-nowrap">' + actions(p) + '</td></tr>';
            }).join('') + '</tbody></table></div>';
        } catch (e) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được danh sách gói', e.message);
        }
      }

      async function edit(p) {
        var decision = await A.decide({
          title: p ? 'Sửa gói ' + p.name : 'Tạo gói Premium mới',
          message: p ? 'Thay đổi áp dụng cho lần mua sau; người đang dùng gói giữ nguyên hạn.' : 'Gói mới được mở bán ngay sau khi tạo.',
          confirmText: p ? 'Lưu thay đổi' : 'Tạo gói', maxLength: 500,
          placeholder: 'Lý do (ít nhất 5 ký tự), lưu vào nhật ký quản trị',
          extraHtml:
            '<div class="field"><label class="field__label" for="admPlanName">Tên gói</label>' +
              '<input class="input" id="admPlanName" maxlength="100" data-autofocus value="' + A.esc(p ? p.name : '') + '"></div>' +
            '<div class="field"><label class="field__label" for="admPlanDesc">Mô tả (không bắt buộc)</label>' +
              '<textarea class="textarea" id="admPlanDesc" rows="2" maxlength="500">' + A.esc(p && p.description || '') + '</textarea></div>' +
            '<div class="field"><label class="field__label" for="admPlanPrice">Giá</label>' +
              '<input class="input" id="admPlanPrice" inputmode="decimal" maxlength="13" value="' + A.esc(p ? p.price : '') + '"></div>' +
            '<div class="field"><label class="field__label" for="admPlanDays">Số ngày hiệu lực (1–3650)</label>' +
              '<input class="input" id="admPlanDays" inputmode="numeric" maxlength="4" value="' + A.esc(p ? p.durationDays : '') + '"></div>',
          collect: function (modal) {
            var name = modal.querySelector('#admPlanName').value.trim();
            var price = modal.querySelector('#admPlanPrice').value.trim().replace(',', '.');
            var days = Number(modal.querySelector('#admPlanDays').value);
            if (name.length < 2) return { error: 'Tên gói cần ít nhất 2 ký tự.' };
            if (!PRICE_PATTERN.test(price)) return { error: 'Giá phải là số không âm, tối đa 2 chữ số thập phân.' };
            if (!Number.isInteger(days) || days < 1 || days > 3650) return { error: 'Số ngày phải từ 1 đến 3650.' };
            return reasonLength(modal) || {
              name: name, description: modal.querySelector('#admPlanDesc').value.trim(), price: Number(price), durationDays: days
            };
          }
        });
        if (!decision) return;
        var body = Object.assign({ reason: decision.reason }, decision.extra);
        try {
          if (p) await A.api.put(API + '/premium-plans/' + p.id, body);
          else await A.api.post(API + '/premium-plans', body);
          plansCache = null;
          A.toast(p ? 'Đã cập nhật gói' : 'Đã tạo gói', 'success');
        } catch (e) {
          A.toast(e.message, 'error');
        }
        await load();
      }

      async function toggle(p) {
        var stop = p.active;
        var decision = await A.decide({
          title: (stop ? 'Ngừng bán gói ' : 'Mở bán lại gói ') + p.name + '?',
          message: stop ? 'Người dùng không mua được gói này nữa. ' + p.activeSubscribers +
              ' tài khoản đang dùng gói vẫn giữ Premium đến hết hạn.' : 'Người dùng mua được gói này trở lại.',
          danger: stop, confirmText: stop ? 'Ngừng bán' : 'Mở bán', maxLength: 500,
          placeholder: 'Lý do (ít nhất 5 ký tự), lưu vào nhật ký quản trị', collect: reasonLength
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/premium-plans/' + p.id + (stop ? '/deactivate' : '/activate'), { reason: decision.reason });
          plansCache = null;
          A.toast(stop ? 'Đã ngừng bán gói' : 'Đã mở bán gói', 'success');
        } catch (e) {
          A.toast(e.message, 'error');
        }
        await load();
      }

      root.addEventListener('click', function (event) {
        var el;
        if (event.target.closest('[data-create]')) { edit(null); return; }
        if ((el = event.target.closest('[data-edit]'))) { edit(plans[el.dataset.edit]); return; }
        if ((el = event.target.closest('[data-toggle]'))) { toggle(plans[el.dataset.toggle]); }
      });
      await load();
    }
  });

  // ── Payments (read only) ───────────────────────────────────────────────
  function catalogSection(kind) {
    var coupon = kind === 'coupons', endpoint = API + '/' + kind;
    return {
      subtitle: coupon ? 'Quản lý mã giảm giá.' : 'Quản lý gói CP.',
      render: async function (root) {
        var rows = {};
        root.innerHTML = '<button class="btn btn--primary" type="button" data-create>Tạo mới</button><div data-list></div>';
        async function load() {
          var items = await A.api.get(endpoint);
          rows = {}; items.forEach(function (p) { rows[p.id] = p; });
          root.querySelector('[data-list]').innerHTML = '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
            (coupon ? '<th>Mã</th><th>Giảm</th><th>Phạm vi</th><th>Đã dùng</th><th>Đang giữ</th>'
              : '<th>Gói</th><th>CP</th><th>Giá</th><th>Đã bán</th>') + '<th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>' +
            items.map(function (p) {
              return '<tr>' + (coupon ? '<td>' + A.esc(p.code) + '</td><td>' + A.esc(p.discountValue) + (p.discountType === 'PERCENT' ? '%' : ' VND') +
                '</td><td>' + A.esc(p.appliesTo) + '</td><td>' + p.usedCount + '</td><td>' + p.reservedCount + '</td>'
                : '<td>' + A.esc(p.name) + '</td><td>' + p.creditAmount + '</td><td>' + money(p.price, 'VND') + '</td><td>' + p.soldCount + '</td>') +
                '<td>' + (p.active ? 'Hoạt động' : 'Đã ngừng') + '</td><td>' +
                '<button class="btn btn--secondary btn--sm" data-edit="' + p.id + '">Sửa</button> ' +
                '<button class="btn btn--secondary btn--sm" data-toggle="' + p.id + '">' + (p.active ? 'Ngừng' : 'Kích hoạt') + '</button> ' +
                '<button class="btn btn--sm adm-btn--danger" data-delete="' + p.id + '">Xóa</button></td></tr>';
            }).join('') + '</tbody></table></div>';
        }
        async function edit(p) {
          function field(id, label, value, type) {
            return '<label>' + label + '<input class="input" id="cat' + id + '" type="' + (type || 'text') + '" value="' + A.esc(value == null ? '' : value) + '"></label>';
          }
          var html = coupon ? field('Code', 'Mã', p && p.code) + field('Description', 'Mô tả', p && p.description) +
            '<label>Loại<select class="select" id="catType"><option value="PERCENT"' + (!p || p.discountType === 'PERCENT' ? ' selected' : '') + '>Phần trăm</option><option value="FIXED"' + (p && p.discountType === 'FIXED' ? ' selected' : '') + '>Số tiền</option></select></label>' +
            field('Value', 'Giá trị', p && p.discountValue, 'number') + field('Max', 'Giảm tối đa', p && p.maxDiscount, 'number') +
            field('Min', 'Đơn tối thiểu', p && p.minOrderAmount, 'number') +
            '<label>Phạm vi<select class="select" id="catApplies"><option value="ALL"' + (!p || p.appliesTo === 'ALL' ? ' selected' : '') + '>Tất cả</option><option value="PREMIUM"' + (p && p.appliesTo === 'PREMIUM' ? ' selected' : '') + '>Premium</option><option value="CREDIT"' + (p && p.appliesTo === 'CREDIT' ? ' selected' : '') + '>CP</option></select></label>' +
            field('Start', 'Bắt đầu', p && p.startsAt, 'datetime-local') + field('End', 'Kết thúc', p && p.endsAt, 'datetime-local') +
            field('Limit', 'Tổng lượt', p && p.usageLimit, 'number') + field('UserLimit', 'Lượt mỗi người', p && p.perUserLimit, 'number')
            : field('Name', 'Tên gói', p && p.name) + field('Description', 'Mô tả', p && p.description) +
              field('Credits', 'Số CP', p && p.creditAmount, 'number') + field('Price', 'Giá VND', p && p.price, 'number');
          var decision = await A.decide({ title: p ? 'Sửa' : 'Tạo mới', confirmText: 'Lưu', maxLength: 500,
            extraHtml: '<div class="adm-form-grid">' + html + '</div>',
            collect: function (modal) {
              var value = function (id) { return modal.querySelector('#cat' + id).value.trim(); };
              return reasonLength(modal) || (coupon ? {
                code: value('Code'), description: value('Description') || null, discountType: value('Type'),
                discountValue: Number(value('Value')), maxDiscount: value('Max') ? Number(value('Max')) : null,
                minOrderAmount: value('Min') ? Number(value('Min')) : null, appliesTo: value('Applies'),
                startsAt: value('Start') || null, endsAt: value('End') || null,
                usageLimit: value('Limit') ? Number(value('Limit')) : null,
                perUserLimit: value('UserLimit') ? Number(value('UserLimit')) : null
              } : { name: value('Name'), description: value('Description') || null,
                creditAmount: Number(value('Credits')), price: Number(value('Price')) });
            }
          });
          if (!decision) return;
          try {
            var body = Object.assign({ reason: decision.reason }, decision.extra);
            if (p) await A.api.put(endpoint + '/' + p.id, body); else await A.api.post(endpoint, body);
            A.toast('Đã lưu', 'success'); await load();
          } catch (e) { A.toast(e.message, 'error'); }
        }
        async function action(p, del) {
          var decision = await A.decide({ title: del ? 'Xóa?' : (p.active ? 'Ngừng?' : 'Kích hoạt?'),
            confirmText: 'Xác nhận', maxLength: 500, collect: reasonLength });
          if (!decision) return;
          try {
            if (del) await A.api.delete(endpoint + '/' + p.id, { reason: decision.reason });
            else await A.api.post(endpoint + '/' + p.id + (p.active ? '/deactivate' : '/activate'), { reason: decision.reason });
            A.toast('Đã cập nhật', 'success'); await load();
          } catch (e) { A.toast(e.message, 'error'); }
        }
        root.addEventListener('click', function (event) {
          var el;
          if (event.target.closest('[data-create]')) { edit(null); return; }
          if ((el = event.target.closest('[data-edit]'))) { edit(rows[el.dataset.edit]); return; }
          if ((el = event.target.closest('[data-toggle]'))) { action(rows[el.dataset.toggle], false); return; }
          if ((el = event.target.closest('[data-delete]'))) action(rows[el.dataset.delete], true);
        });
        await load();
      }
    };
  }
  A.section('system-coupons', catalogSection('coupons'));
  A.section('system-credit-packages', catalogSection('credit-packages'));

  A.section('system-payments', {
    subtitle: 'Tra cứu giao dịch mua Premium và CP. Chỉ xem: không sửa, không hoàn tiền tại đây.',
    render: async function (root) {
      var page = 0;
      root.innerHTML =
        '<form class="adm-toolbar adm-panel" data-filters novalidate>' +
          '<input class="input" type="search" name="q" maxlength="100" placeholder="Mã đơn, mã giao dịch, email hoặc họ tên" aria-label="Tìm giao dịch">' +
          '<select class="select" name="status" aria-label="Trạng thái"><option value="">Mọi trạng thái</option>' +
            Object.keys(PAYMENT_STATUS).map(function (k) { return '<option value="' + k + '">' + PAYMENT_STATUS[k][0] + '</option>'; }).join('') +
          '</select>' +
          '<select class="select" name="productType" aria-label="Sản phẩm"><option value="">Mọi sản phẩm</option><option value="PREMIUM">Premium</option><option value="CREDIT">CP</option></select>' +
          '<label class="adm-inline">Từ <input class="input" type="date" name="from"></label>' +
          '<label class="adm-inline">Đến <input class="input" type="date" name="to"></label>' +
          '<button type="submit" class="btn btn--primary btn--sm"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i> Lọc</button>' +
        '</form><div data-detail></div><div data-list></div>';
      var form = root.querySelector('[data-filters]');

      async function load() {
        var list = root.querySelector('[data-list]');
        list.innerHTML = A.loading();
        var params = new URLSearchParams({ page: page, size: 20 });
        ['q', 'status', 'productType'].forEach(function (k) { var v = form.elements[k].value.trim(); if (v) params.set(k, v); });
        if (form.elements.from.value) params.set('from', form.elements.from.value + 'T00:00:00');
        if (form.elements.to.value) params.set('to', form.elements.to.value + 'T23:59:59.999');
        try {
          var data = await A.api.get(API + '/payments?' + params.toString());
          list.innerHTML = (data.items.length ?
            '<p class="adm-muted">' + data.totalElements + ' giao dịch</p>' +
            '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
              '<th scope="col">Tạo lúc</th><th scope="col">Mã đơn</th><th scope="col">Người mua</th><th scope="col">Gói</th>' +
              '<th scope="col">Số tiền</th><th scope="col">Trạng thái</th><th scope="col">Thanh toán lúc</th>' +
              '<th scope="col"><span class="sr-only">Thao tác</span></th></tr></thead><tbody>' +
              data.items.map(function (p) {
                return '<tr><td class="adm-muted adm-nowrap">' + A.date(p.createdAt) + '</td>' +
                  '<td><code>' + A.esc(p.orderCode) + '</code><br><span class="adm-muted">' + A.esc(p.provider) + '</span></td>' +
                  '<td><span class="adm-user"><strong>' + A.esc(p.userName || p.userEmail) + '</strong>' +
                    '<span class="adm-muted">' + A.esc(p.userEmail) + ' · #' + p.userId + '</span></span></td>' +
                  '<td>' + A.esc(p.planName) + '<br><span class="adm-muted">' + (p.productType === 'CREDIT' ? p.creditAmount + ' CP' : p.durationDays + ' ngày') + '</span></td>' +
                  '<td class="adm-nowrap">' + A.esc(money(p.amountPaid, p.currency)) +
                    (Number(p.discountAmount) ? '<br><small>Giảm ' + money(p.discountAmount) + ' (' + A.esc(p.couponCode) + ')</small>' : '') + '</td>' +
                  '<td>' + paymentBadge(p.status) + '</td>' +
                  '<td class="adm-muted adm-nowrap">' + A.date(p.paidAt) + '</td>' +
                  '<td><button type="button" class="btn btn--secondary btn--sm" data-payment="' + p.id + '">Chi tiết</button></td></tr>';
              }).join('') + '</tbody></table></div>'
            : A.empty('fa-receipt', 'Chưa có giao dịch phù hợp',
                'Giao dịch xuất hiện ở đây khi người dùng mua Premium qua cổng thanh toán.')) +
            pager(page, data.totalPages);
        } catch (e) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được giao dịch', e.message);
        }
      }

      async function showDetail(id) {
        var box = root.querySelector('[data-detail]');
        box.innerHTML = '<section class="adm-panel">' + A.loading() + '</section>';
        try {
          var p = await A.api.get(API + '/payments/' + id);
          var fields = [
            ['Mã đơn', p.orderCode], ['Cổng thanh toán', p.provider], ['Mã giao dịch cổng', p.providerTransactionId],
            ['Mã kết quả', p.responseCode], ['Trạng thái', (PAYMENT_STATUS[p.status] || [p.status])[0]],
            ['Sản phẩm', p.productType === 'CREDIT' ? 'CP' : 'Premium'],
            ['Giá gốc', money(p.originalAmount, p.currency)], ['Giảm giá', money(p.discountAmount, p.currency)],
            ['Mã giảm giá', p.couponCode], ['Số tiền', money(p.amountPaid, p.currency)],
            ['Gói lúc mua', p.planName + ' · ' + (p.productType === 'CREDIT' ? p.creditAmount + ' CP' : p.durationDays + ' ngày')],
            ['Người mua', (p.userName ? p.userName + ' · ' : '') + p.userEmail + ' (#' + p.userId + ')'],
            ['Tạo lúc', A.date(p.createdAt)], ['Thanh toán lúc', A.date(p.paidAt)], ['Cập nhật', A.date(p.updatedAt)]
          ];
          box.innerHTML = '<section class="adm-panel" aria-label="Chi tiết giao dịch">' +
            '<h2 class="adm-panel__title">Giao dịch #' + p.id + '</h2>' +
            '<dl class="adm-detail">' + fields.filter(function (f) { return f[1] != null && f[1] !== ''; }).map(function (f) {
              return '<dt>' + f[0] + '</dt><dd>' + A.esc(f[1]) + '</dd>';
            }).join('') + '</dl>' +
            '<p class="adm-toolbar"><button type="button" class="btn btn--secondary btn--sm" data-close-detail>Đóng</button></p></section>';
          box.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } catch (e) {
          box.innerHTML = '<p class="adm-error">' + A.esc(e.message) + '</p>';
        }
      }

      form.addEventListener('submit', function (event) { event.preventDefault(); page = 0; load(); });
      root.addEventListener('click', function (event) {
        var el;
        if ((el = event.target.closest('[data-page]'))) { page += Number(el.dataset.page); load(); return; }
        if ((el = event.target.closest('[data-payment]'))) { showDetail(Number(el.dataset.payment)); return; }
        if (event.target.closest('[data-close-detail]')) root.querySelector('[data-detail]').innerHTML = '';
      });
      await load();
    }
  });
})();
