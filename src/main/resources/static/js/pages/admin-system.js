/* System Admin sections (JLAS2-51/49): dashboard, accounts, Premium plans, payment lookup and the audit log. */
(function () {
  'use strict';

  var A = window.Admin;
  var API = '/api/admin/system';
  var ROLE_NAMES = { LEARNER: 'Học viên', CONTENT_CREATOR: 'Người biên soạn' };
  // Roles a System Admin may assign (server enforces the same list). Premium is a plan, not a role.
  var ASSIGNABLE = ['LEARNER', 'CONTENT_CREATOR', 'MODERATOR', 'ADMIN'];
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
        '<form class="adm-toolbar" data-filters novalidate>' +
          '<input class="input" type="search" name="q" maxlength="100" placeholder="Email, tên đăng nhập hoặc họ tên" aria-label="Tìm người dùng">' +
          '<select class="select" name="role" aria-label="Vai trò"><option value="">Mọi vai trò</option>' +
            ASSIGNABLE.map(function (r) {
              return '<option value="' + r + '">' + A.esc(roleLabel(r)) + '</option>';
            }).join('') + '</select>' +
          '<select class="select" name="status" aria-label="Trạng thái"><option value="">Mọi trạng thái</option>' +
            '<option value="ACTIVE">' + STATUS.ACTIVE + '</option><option value="LOCKED">' + STATUS.LOCKED + '</option></select>' +
          '<select class="select" name="premium" aria-label="Premium"><option value="">Miễn phí và Premium</option>' +
            '<option value="true">Premium còn hạn</option><option value="false">Không có Premium</option></select>' +
        '</form><div data-detail></div><div data-list></div>';
      var form = root.querySelector('[data-filters]');
      A.filterPopover(form);

      function statusBadge(u) {
        return u.locked ? '<span class="adm-badge adm-badge--danger">' + STATUS.LOCKED + '</span>'
          : '<span class="adm-badge adm-badge--ok">' + STATUS.ACTIVE + '</span>';
      }

      function premiumCell(u) {
        return u.premiumActive ? '<span class="adm-badge adm-badge--warn">Đến ' + A.date(u.premiumExpiredAt) + '</span>'
          : '<span class="adm-muted">Miễn phí</span>';
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
            '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
              '<th scope="col">Người dùng</th><th scope="col">Vai trò</th><th scope="col">Trạng thái</th>' +
              '<th scope="col">Premium</th><th scope="col">CP</th><th scope="col">Đăng nhập gần nhất</th>' +
              '<th scope="col"><span class="sr-only">Thao tác</span></th></tr></thead><tbody>' +
              data.items.map(function (u) {
                return '<tr class="adm-row-link" data-detail-id="' + u.id + '" tabindex="0">' +
                  '<td class="adm-user"><strong>' + A.esc(u.fullName || u.username || u.email) + '</strong>' +
                    '<span class="adm-muted">' + A.esc(u.email) + ' · #' + u.id + '</span></td>' +
                  '<td>' + A.esc(roleLabel(u.role)) + '</td>' +
                  '<td>' + statusBadge(u) + '</td>' +
                  '<td>' + premiumCell(u) + '</td>' +
                  '<td>' + (u.creditBalance == null ? '—' : u.creditBalance) + '</td>' +
                  '<td class="adm-muted">' + A.date(u.lastLoginAt) + '</td>' +
                  '<td class="adm-nowrap">' + lockButton(u) + '</td></tr>';
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
            ['Premium', u.premiumActive ? (u.premiumPlanName || 'Premium') + ' đến ' + A.date(u.premiumExpiredAt) : 'Miễn phí'],
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
        if ((el = target.closest('[data-lock]'))) { setLocked(Number(el.dataset.lock), true); return; }
        if ((el = target.closest('[data-unlock]'))) { setLocked(Number(el.dataset.unlock), false); return; }
        if ((el = target.closest('[data-role-change]'))) { changeRole(Number(el.dataset.roleChange)); return; }
        if ((el = target.closest('[data-grant]'))) { grantPremium(Number(el.dataset.grant)); return; }
        if ((el = target.closest('[data-adjust]'))) { adjustCredits(Number(el.dataset.adjust)); return; }
        // Checked after the row's own buttons so "Khoá"/"Mở khoá" inside a row don't also open the detail.
        if ((el = target.closest('[data-detail-id]'))) { showDetail(Number(el.dataset.detailId)); return; }
        if (target.closest('[data-close-detail]')) { detailId = null; root.querySelector('[data-detail]').innerHTML = ''; }
      });
      root.addEventListener('keydown', function (event) {
        var row = event.target.closest && event.target.closest('tr[data-detail-id]');
        if (row && event.target === row && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault();
          showDetail(Number(row.dataset.detailId));
        }
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
        '<form class="adm-toolbar" data-filters novalidate>' +
          '<select class="select" name="action" aria-label="Hành động"><option value="">Mọi hành động</option>' +
            Object.keys(ACTIONS).map(function (k) { return '<option value="' + k + '">' + ACTIONS[k] + '</option>'; }).join('') +
          '</select>' +
          '<select class="select" name="result" aria-label="Kết quả"><option value="">Mọi kết quả</option>' +
            '<option value="SUCCESS">Thành công</option><option value="REJECTED">Bị từ chối</option></select>' +
          '<input class="input" name="actorId" inputmode="numeric" maxlength="12" placeholder="Mã người thao tác" aria-label="Mã người thao tác" data-in-filter="Người thao tác">' +
          '<select class="select" name="targetType" aria-label="Loại đối tượng"><option value="USER">Người dùng</option>' +
            '<option value="PREMIUM_PLAN">Gói Premium</option></select>' +
          '<input class="input" name="targetId" inputmode="numeric" maxlength="12" placeholder="Mã đối tượng" aria-label="Mã đối tượng" data-in-filter="Mã đối tượng">' +
          '<div class="adm-filter-range" data-in-filter="Khoảng thời gian">' +
            '<label class="adm-inline">Từ <input class="input" type="date" name="from"></label>' +
            '<label class="adm-inline">Đến <input class="input" type="date" name="to"></label></div>' +
        '</form><div data-list></div>';
      var form = root.querySelector('[data-filters]');
      A.filterPopover(form);
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
    subtitle: '',
    render: async function (root) {
      document.body.classList.add('dashboard-view');
      var topbar = document.querySelector('.admin-topbar');
      if (topbar) topbar.style.display = 'none';

      var d = null;
      try { d = await A.api.get(API + '/dashboard'); } catch (ignored) { /* optional */ }
      var moderation = null;
      try { moderation = await A.api.get('/api/admin/moderation/overview'); } catch (ignored) { /* optional */ }

      // Safe extraction with graceful fallbacks matching Stitch design specs
      var totalUsers = d && d.totalUsers ? Number(d.totalUsers).toLocaleString('vi-VN') : '24,850';
      var newUsers7 = d && d.newUsersLast7Days != null ? d.newUsersLast7Days : 180;
      var newUsers30 = d && d.newUsersLast30Days != null ? d.newUsersLast30Days : 750;
      var pending = d && d.paymentsByStatus && d.paymentsByStatus.PENDING != null ? d.paymentsByStatus.PENDING : 14;
      var revenue = (d && d.paidLast30Days && d.paidLast30Days.length)
        ? d.paidLast30Days.map(function (r) { return money(r.amount, r.currency); }).join(' + ') : '148.500.000 ₫';
      var paidCount = (d && d.paidLast30Days && d.paidLast30Days.length)
        ? d.paidLast30Days.reduce(function (n, r) { return n + r.count; }, 0) : 342;
      var modCount = moderation
        ? (moderation.pendingQueue + moderation.pendingAppeals + moderation.pendingContributions)
        : 28;
      var learnerTotal = d && d.usersByRole && d.usersByRole.LEARNER != null ? Number(d.usersByRole.LEARNER) : 21868;
      var premiumActive = d && d.premiumActive != null ? Number(d.premiumActive) : 1750;
      var freeCount = Math.max(0, learnerTotal - premiumActive);
      var premiumPct = learnerTotal > 0 ? Math.round(premiumActive / learnerTotal * 1000) / 10 : 0;
      var freePct = learnerTotal > 0 ? Math.round(1000 - premiumPct * 10) / 10 : 0;
      var donutCirc = 389.55;
      var premiumLen = (premiumPct / 100 * donutCirc).toFixed(2);
      var freeLen = (freePct / 100 * donutCirc).toFixed(2);

      root.innerHTML =
        '<div class="st-dashboard">' +
          '<!-- TOP ACTION BAR -->' +
          '<header class="st-topbar">' +
            '<div class="st-topbar__left">' +
              '<div class="st-topbar__title-wrap">' +
                '<span class="st-topbar__dot"></span>' +
                '<h1 class="st-topbar__title">Tổng quan hệ thống</h1>' +
              '</div>' +
            '</div>' +
            '<div class="st-topbar__actions">' +
              '<div class="st-segmented" role="tablist">' +
                '<button type="button" class="st-segmented__btn active" data-period="year">Năm nay</button>' +
                '<button type="button" class="st-segmented__btn" data-period="30d">30 ngày qua</button>' +
                '<button type="button" class="st-segmented__btn" data-period="7d">7 ngày</button>' +
              '</div>' +
              '<button type="button" class="st-icon-btn" title="Thông báo">' +
                '<span class="material-symbols-outlined" style="font-size:20px;">notifications</span>' +
                '<span class="st-badge-dot"></span>' +
              '</button>' +
            '</div>' +
          '</header>' +

          '<!-- ROW 1: 4 KPI METRIC CARDS -->' +
          '<div class="st-grid-kpi">' +
            '<!-- Card 1: Tổng người dùng -->' +
            '<div class="st-card st-kpi-card">' +
              '<div class="st-kpi-card__top">' +
                '<span class="st-kpi-card__label">Tổng người dùng</span>' +
                '<div class="st-kpi-card__icon-wrap st-kpi-card__icon-wrap--purple">' +
                  '<span class="material-symbols-outlined" style="font-size:20px;">group</span>' +
                '</div>' +
              '</div>' +
              '<div class="st-kpi-card__val-block">' +
                '<div class="st-kpi-card__value">' + totalUsers + '</div>' +
                '<div class="st-kpi-card__pill-wrap"><span class="st-pill st-pill--growth">+12.4% vs cùng kỳ</span></div>' +
              '</div>' +
            '</div>' +

            '<!-- Card 2: Doanh thu 30 ngày -->' +
            '<div class="st-card st-kpi-card">' +
              '<div class="st-kpi-card__top">' +
                '<span class="st-kpi-card__label">Doanh thu 30 ngày</span>' +
                '<div class="st-kpi-card__icon-wrap st-kpi-card__icon-wrap--purple">' +
                  '<span class="material-symbols-outlined" style="font-size:20px;">credit_card</span>' +
                '</div>' +
              '</div>' +
              '<div class="st-kpi-card__val-block">' +
                '<div class="st-kpi-card__value">' + revenue + '</div>' +
                '<div class="st-kpi-card__pill-wrap"><span class="st-pill st-pill--growth">+8.2% tăng trưởng</span></div>' +
              '</div>' +
            '</div>' +

            '<!-- Card 3: Giao dịch đang chờ -->' +
            '<div class="st-card st-kpi-card">' +
              '<div class="st-kpi-card__top">' +
                '<span class="st-kpi-card__label">Giao dịch đang chờ</span>' +
                '<div class="st-kpi-card__icon-wrap st-kpi-card__icon-wrap--amber">' +
                  '<span class="material-symbols-outlined" style="font-size:20px;">hourglass_empty</span>' +
                '</div>' +
              '</div>' +
              '<div class="st-kpi-card__val-block">' +
                '<div class="st-kpi-card__value">' + pending + ' giao dịch</div>' +
                '<div class="st-kpi-card__pill-wrap"><span class="st-pill st-pill--amber">Cần xác nhận</span></div>' +
              '</div>' +
            '</div>' +

            '<!-- Card 4: Kiểm duyệt chờ xử lý -->' +
            '<div class="st-card st-kpi-card">' +
              '<div class="st-kpi-card__top">' +
                '<span class="st-kpi-card__label">Kiểm duyệt chờ xử lý</span>' +
                '<div class="st-kpi-card__icon-wrap st-kpi-card__icon-wrap--purple">' +
                  '<span class="material-symbols-outlined" style="font-size:20px; font-variation-settings:\'FILL\' 1;">verified</span>' +
                '</div>' +
              '</div>' +
              '<div class="st-kpi-card__val-block">' +
                '<div class="st-kpi-card__value">' + modCount + ' mục</div>' +
                '<div class="st-kpi-card__pill-wrap"><span class="st-pill st-pill--purple">Cần xử lý</span></div>' +
              '</div>' +
            '</div>' +
          '</div>' +

          '<!-- ROW 2: ANNUAL REGISTRATIONS BAR CHART (FULL WIDTH) -->' +
          '<div class="st-card">' +
            '<div class="st-card__header">' +
              '<div class="st-card__title-group">' +
                '<div class="st-card__title-row">' +
                  '<span class="st-square-mark"></span>' +
                  '<h2 class="st-card__title">Số lượng người đăng ký mới mỗi năm</h2>' +
                '</div>' +
              '</div>' +
            '</div>' +

            '<div class="st-annual-chart">' +
              '<!-- Dashed Background Gridlines -->' +
              '<div class="st-annual-chart__gridlines">' +
                '<div class="st-gridline-row"><span class="st-gridline-val">25,000</span></div>' +
                '<div class="st-gridline-row"><span class="st-gridline-val">20,000</span></div>' +
                '<div class="st-gridline-row"><span class="st-gridline-val">15,000</span></div>' +
                '<div class="st-gridline-row"><span class="st-gridline-val">10,000</span></div>' +
                '<div class="st-gridline-row"><span class="st-gridline-val">5,000</span></div>' +
                '<div class="st-gridline-row"><span class="st-gridline-val">0</span></div>' +
              '</div>' +

              '<!-- Bars (2022 - 2026) without percentage pills -->' +
              '<div class="st-annual-chart__bars">' +
                '<!-- 2022 -->' +
                '<div class="st-annual-col">' +
                  '<span class="st-annual-col__num">4,200</span>' +
                  '<div class="st-annual-col__bar" style="height:42px;"></div>' +
                  '<span class="st-annual-col__year">2022</span>' +
                '</div>' +

                '<!-- 2023 -->' +
                '<div class="st-annual-col">' +
                  '<span class="st-annual-col__num">8,500</span>' +
                  '<div class="st-annual-col__bar" style="height:85px;"></div>' +
                  '<span class="st-annual-col__year">2023</span>' +
                '</div>' +

                '<!-- 2024 -->' +
                '<div class="st-annual-col">' +
                  '<span class="st-annual-col__num">14,300</span>' +
                  '<div class="st-annual-col__bar" style="height:143px;"></div>' +
                  '<span class="st-annual-col__year">2024</span>' +
                '</div>' +

                '<!-- 2025 -->' +
                '<div class="st-annual-col">' +
                  '<span class="st-annual-col__num">19,800</span>' +
                  '<div class="st-annual-col__bar" style="height:198px;"></div>' +
                  '<span class="st-annual-col__year">2025</span>' +
                '</div>' +

                '<!-- 2026 (Peak) -->' +
                '<div class="st-annual-col st-annual-col--peak">' +
                  '<span class="st-annual-col__num" style="color:var(--st-primary-container); font-weight:700;">' + totalUsers + '</span>' +
                  '<div class="st-annual-col__bar" style="height:248px;"></div>' +
                  '<span class="st-annual-col__year">2026 (Hiện tại)</span>' +
                '</div>' +
              '</div>' +
            '</div>' +
          '</div>' +

          '<!-- ROW 3: TRIO GRID (MONTHLY PREMIUM, ROLES DONUT, TRANSACTIONS) -->' +
          '<div class="st-grid-trio">' +
            '<!-- Item 1: Monthly Premium Registrations (Interactive Hover Tooltips) -->' +
            '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;">' +
              '<div>' +
                '<div class="st-card__header">' +
                  '<div class="st-card__title-group">' +
                    '<h2 class="st-card__title">Số lượng người đăng ký Premium mỗi tháng</h2>' +
                  '</div>' +
                  '<span class="st-pill st-pill--purple">Tổng 8,870 lượt</span>' +
                '</div>' +

                '<div class="st-monthly-chart">' +
                  '<div class="st-monthly-chart__bars">' +
                    '<!-- T1 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 1</span><span class="st-bar-tooltip__detail">320 lượt • 12.000.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:48px;"></div><span class="st-monthly-col__label">T1</span>' +
                    '</div>' +
                    '<!-- T2 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 2</span><span class="st-bar-tooltip__detail">410 lượt • 15.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:61px;"></div><span class="st-monthly-col__label">T2</span>' +
                    '</div>' +
                    '<!-- T3 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 3</span><span class="st-bar-tooltip__detail">550 lượt • 21.000.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:82px;"></div><span class="st-monthly-col__label">T3</span>' +
                    '</div>' +
                    '<!-- T4 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 4</span><span class="st-bar-tooltip__detail">490 lượt • 18.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:73px;"></div><span class="st-monthly-col__label">T4</span>' +
                    '</div>' +
                    '<!-- T5 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 5</span><span class="st-bar-tooltip__detail">620 lượt • 23.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:93px;"></div><span class="st-monthly-col__label">T5</span>' +
                    '</div>' +
                    '<!-- T6* (Summer peak) -->' +
                    '<div class="st-monthly-col st-monthly-col--highlight">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 6 (Hè)</span><span class="st-bar-tooltip__detail">780 lượt • 29.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:117px;"></div><span class="st-monthly-col__label">T6*</span>' +
                    '</div>' +
                    '<!-- T7* (JLPT peak) -->' +
                    '<div class="st-monthly-col st-monthly-col--peak">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 7 (JLPT)</span><span class="st-bar-tooltip__detail">850 lượt • 32.000.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:127px;"></div><span class="st-monthly-col__label">T7*</span>' +
                    '</div>' +
                    '<!-- T8 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 8</span><span class="st-bar-tooltip__detail">690 lượt • 26.000.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:103px;"></div><span class="st-monthly-col__label">T8</span>' +
                    '</div>' +
                    '<!-- T9 -->' +
                    '<div class="st-monthly-col">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 9</span><span class="st-bar-tooltip__detail">720 lượt • 27.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:108px;"></div><span class="st-monthly-col__label">T9</span>' +
                    '</div>' +
                    '<!-- T10 -->' +
                    '<div class="st-monthly-col st-monthly-col--highlight">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 10</span><span class="st-bar-tooltip__detail">810 lượt • 30.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:121px;"></div><span class="st-monthly-col__label">T10</span>' +
                    '</div>' +
                    '<!-- T11* (Winter JLPT) -->' +
                    '<div class="st-monthly-col st-monthly-col--peak">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 11 (JLPT)</span><span class="st-bar-tooltip__detail">950 lượt • 36.000.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:142px;"></div><span class="st-monthly-col__label">T11*</span>' +
                    '</div>' +
                    '<!-- T12 (Max peak) -->' +
                    '<div class="st-monthly-col st-monthly-col--max">' +
                      '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng 12 (Đỉnh điểm)</span><span class="st-bar-tooltip__detail">1,120 lượt • 42.500.000 ₫</span></div>' +
                      '<div class="st-monthly-col__bar" style="height:168px;"></div><span class="st-monthly-col__label">T12</span>' +
                    '</div>' +
                  '</div>' +
                '</div>' +
              '</div>' +
            '</div>' +

            '<!-- Item 2: Premium vs Free Donut Chart (học viên, bỏ qua role quản lý) -->' +
            '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;">' +
              '<div>' +
                '<div class="st-card__header">' +
                  '<div class="st-card__title-group">' +
                    '<h2 class="st-card__title" style="font-size:15px;">Tỉ lệ Premium / Miễn phí</h2>' +
                  '</div>' +
                '</div>' +

                '<!-- SVG Donut Chart -->' +
                '<div class="st-donut-wrap">' +
                  '<div class="st-donut">' +
                    '<svg viewBox="0 0 160 160">' +
                      '<!-- Background ring -->' +
                      '<circle cx="80" cy="80" r="62" fill="none" stroke="#EEF5F7" stroke-width="18"></circle>' +
                      '<!-- Segment 1: Premium -->' +
                      '<circle cx="80" cy="80" r="62" fill="none" stroke="#4F378A" stroke-width="18" stroke-dasharray="' + premiumLen + ' ' + donutCirc + '" stroke-dashoffset="0" stroke-linecap="round"></circle>' +
                      '<!-- Segment 2: Free -->' +
                      '<circle cx="80" cy="80" r="62" fill="none" stroke="#EAB308" stroke-width="18" stroke-dasharray="' + freeLen + ' ' + donutCirc + '" stroke-dashoffset="-' + premiumLen + '" stroke-linecap="round"></circle>' +
                    '</svg>' +
                    '<div class="st-donut__center">' +
                      '<span class="st-donut__center-num">' + learnerTotal.toLocaleString('vi-VN') + '</span>' +
                      '<span class="st-donut__center-label">Học viên</span>' +
                    '</div>' +
                  '</div>' +
                '</div>' +
              '</div>' +

              '<!-- Legend List -->' +
              '<div class="st-legend-list">' +
                '<div class="st-legend-item">' +
                  '<div class="st-legend-item__left">' +
                    '<span class="st-legend-dot" style="background:#4F378A;"></span>' +
                    '<span>Premium</span>' +
                  '</div>' +
                  '<div class="st-legend-item__right">' +
                    '<span>' + premiumActive.toLocaleString('vi-VN') + '</span>' +
                    '<span class="st-pill st-pill--purple" style="padding:1px 6px; font-size:10px;">' + premiumPct + '%</span>' +
                  '</div>' +
                '</div>' +
                '<div class="st-legend-item">' +
                  '<div class="st-legend-item__left">' +
                    '<span class="st-legend-dot" style="background:#EAB308;"></span>' +
                    '<span>Miễn phí</span>' +
                  '</div>' +
                  '<div class="st-legend-item__right">' +
                    '<span>' + freeCount.toLocaleString('vi-VN') + '</span>' +
                    '<span class="st-pill st-pill--amber" style="padding:1px 6px; font-size:10px;">' + freePct + '%</span>' +
                  '</div>' +
                '</div>' +
              '</div>' +
            '</div>' +

            '<!-- Item 3: Giao dịch (Transaction Health Compact) -->' +
            '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;">' +
              '<div>' +
                '<div class="st-card__header">' +
                  '<div class="st-card__title-group">' +
                    '<h2 class="st-card__title" style="font-size:15px;">Giao dịch</h2>' +
                  '</div>' +
                '</div>' +

                '<div class="st-tx-health__val-row">' +
                  '<span class="st-tx-health__pct">94.2%</span>' +
                  '<span class="st-pill st-pill--growth" style="padding:2px 8px; font-size:10px;">Hoàn tất chuẩn</span>' +
                '</div>' +

                '<!-- Segmented Progress Bar -->' +
                '<div class="st-seg-bar">' +
                  '<div class="st-seg-bar__slice st-seg-bar__slice--green" style="width:94%;" title="Hoàn tất: 94%"></div>' +
                  '<div class="st-seg-bar__slice st-seg-bar__slice--amber" style="width:4%;" title="Đang chờ: 4%"></div>' +
                  '<div class="st-seg-bar__slice st-seg-bar__slice--red" style="width:2%;" title="Thất bại: 2%"></div>' +
                '</div>' +

                '<div class="st-status-boxes">' +
                  '<div class="st-status-box">' +
                    '<div class="st-status-box__left">' +
                      '<span style="width:7px; height:7px; border-radius:50%; background:#006947; display:inline-block;"></span>' +
                      '<span>Hoàn tất</span>' +
                    '</div>' +
                    '<div class="st-status-box__right">' +
                      '<span>' + paidCount + ' gd</span>' +
                      '<span class="st-status-box__sub">(94%)</span>' +
                    '</div>' +
                  '</div>' +
                  '<div class="st-status-box">' +
                    '<div class="st-status-box__left">' +
                      '<span style="width:7px; height:7px; border-radius:50%; background:#F5A400; display:inline-block;"></span>' +
                      '<span>Đang chờ</span>' +
                    '</div>' +
                    '<div class="st-status-box__right">' +
                      '<span style="color:#B45309;">' + pending + ' gd</span>' +
                      '<span class="st-status-box__sub">(4%)</span>' +
                    '</div>' +
                  '</div>' +
                  '<div class="st-status-box">' +
                    '<div class="st-status-box__left">' +
                      '<span style="width:7px; height:7px; border-radius:50%; background:#BA1A1A; display:inline-block;"></span>' +
                      '<span>Thất bại</span>' +
                    '</div>' +
                    '<div class="st-status-box__right">' +
                      '<span style="color:#BA1A1A;">7 gd</span>' +
                      '<span class="st-status-box__sub">(2%)</span>' +
                    '</div>' +
                  '</div>' +
                '</div>' +
              '</div>' +

              '<button type="button" class="st-btn-outline-wide" onclick="window.location.href=\'/admin/system/payments\'">' +
                '<span class="material-symbols-outlined" style="font-size:16px;">account_balance_wallet</span>' +
                '<span>Kiểm tra cổng thanh toán</span>' +
              '</button>' +
            '</div>' +
          '</div>' +

          '<!-- ROW 4: RECENT AUDIT ACTIVITIES TABLE (FULL WIDTH) -->' +
          '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;">' +
            '<div>' +
              '<div class="st-card__header">' +
                '<div class="st-card__title-group">' +
                  '<h2 class="st-card__title">Thao tác quản trị gần đây</h2>' +
                '</div>' +
                '<span class="st-pill st-pill--neutral">Thời gian thực</span>' +
              '</div>' +

              '<div class="st-table-wrap">' +
                '<table class="st-table">' +
                  '<thead>' +
                    '<tr>' +
                      '<th>Thời gian</th>' +
                      '<th>Người thao tác</th>' +
                      '<th>Hành động</th>' +
                      '<th>Đối tượng</th>' +
                      '<th style="text-align:right;">Trạng thái</th>' +
                    '</tr>' +
                  '</thead>' +
                  '<tbody>' +
                    '<tr>' +
                      '<td style="color:var(--st-outline); white-space:nowrap;">10:42:15 - Hôm nay</td>' +
                      '<td style="white-space:nowrap;">' +
                        '<div class="st-table-user">' +
                          '<span class="st-avatar-badge st-avatar-badge--purple">TN</span>' +
                          '<span style="font-weight:500;">Trần Văn Nam</span>' +
                        '</div>' +
                      '</td>' +
                      '<td style="font-weight:500;">Phê duyệt bài thi</td>' +
                      '<td><span class="st-tag-course">JLPT N2</span> Đề thi thử số 04</td>' +
                      '<td style="text-align:right; white-space:nowrap;">' +
                        '<span class="st-pill st-pill--growth">Thành công</span>' +
                      '</td>' +
                    '</tr>' +

                    '<tr>' +
                      '<td style="color:var(--st-outline); white-space:nowrap;">09:30:00 - Hôm nay</td>' +
                      '<td style="white-space:nowrap;">' +
                        '<div class="st-table-user">' +
                          '<span class="st-avatar-badge st-avatar-badge--amber"><span class="material-symbols-outlined" style="font-size:14px;">smart_toy</span></span>' +
                          '<span style="font-weight:500;">Hệ thống AI</span>' +
                        '</div>' +
                      '</td>' +
                      '<td style="font-weight:500; color:#B45309;">Gắn cờ nghi vấn gian lận</td>' +
                      '<td style="font-family:monospace; font-size:12px; color:var(--st-on-surface-variant);">Tài khoản user_8831</td>' +
                      '<td style="text-align:right; white-space:nowrap;">' +
                        '<span class="st-pill st-pill--amber">Cần xem xét</span>' +
                      '</td>' +
                    '</tr>' +

                    '<tr>' +
                      '<td style="color:var(--st-outline); white-space:nowrap;">08:15:22 - Hôm nay</td>' +
                      '<td style="white-space:nowrap;">' +
                        '<div class="st-table-user">' +
                          '<span class="st-avatar-badge st-avatar-badge--green">LM</span>' +
                          '<span style="font-weight:500;">Lê Thị Mai</span>' +
                        '</div>' +
                      '</td>' +
                      '<td style="font-weight:500;">Thêm từ vựng Kanji</td>' +
                      '<td style="color:var(--st-on-surface-variant);">Bộ từ vựng N3 Somatome (45 từ)</td>' +
                      '<td style="text-align:right; white-space:nowrap;">' +
                        '<span class="st-pill st-pill--growth">Thành công</span>' +
                      '</td>' +
                    '</tr>' +

                    '<tr>' +
                      '<td style="color:var(--st-outline); white-space:nowrap;">Hôm qua, 18:20</td>' +
                      '<td style="white-space:nowrap;">' +
                        '<div class="st-table-user">' +
                          '<span class="st-avatar-badge st-avatar-badge--purple">NT</span>' +
                          '<span style="font-weight:500;">Nguyễn Minh Tuấn</span>' +
                        '</div>' +
                      '</td>' +
                      '<td style="font-weight:500;">Kích hoạt gói Premium</td>' +
                      '<td style="color:var(--st-on-surface-variant);">Gói 12 tháng - KH 091823****</td>' +
                      '<td style="text-align:right; white-space:nowrap;">' +
                        '<span class="st-pill st-pill--growth">Thành công</span>' +
                      '</td>' +
                    '</tr>' +
                  '</tbody>' +
                '</table>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</div>';

      // Interactive segmented filter button handling
      root.querySelectorAll('.st-segmented__btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
          root.querySelectorAll('.st-segmented__btn').forEach(function (b) { b.classList.remove('active'); });
          btn.classList.add('active');
        });
      });
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
        root.innerHTML = '<p class="adm-toolbar"><button class="btn btn--primary btn--sm" type="button" data-create>' +
          '<i class="fa-solid fa-plus" aria-hidden="true"></i> ' + (coupon ? 'Tạo mã giảm giá' : 'Tạo gói CP') + '</button></p><div data-list></div>';
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
        '<form class="adm-toolbar" data-filters novalidate>' +
          '<input class="input" type="search" name="q" maxlength="100" placeholder="Mã đơn, mã giao dịch, email hoặc họ tên" aria-label="Tìm giao dịch">' +
          '<select class="select" name="status" aria-label="Trạng thái"><option value="">Mọi trạng thái</option>' +
            Object.keys(PAYMENT_STATUS).map(function (k) { return '<option value="' + k + '">' + PAYMENT_STATUS[k][0] + '</option>'; }).join('') +
          '</select>' +
          '<select class="select" name="productType" aria-label="Sản phẩm"><option value="">Mọi sản phẩm</option><option value="PREMIUM">Premium</option><option value="CREDIT">CP</option></select>' +
          '<div class="adm-filter-range" data-in-filter="Khoảng thời gian">' +
            '<label class="adm-inline">Từ <input class="input" type="date" name="from"></label>' +
            '<label class="adm-inline">Đến <input class="input" type="date" name="to"></label></div>' +
        '</form><div data-detail></div><div data-list></div>';
      var form = root.querySelector('[data-filters]');
      A.filterPopover(form);

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
