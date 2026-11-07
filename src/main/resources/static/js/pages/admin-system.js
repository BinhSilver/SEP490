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
    USER_BIRTH_DATE_CHANGE: 'Sửa ngày sinh',
    PREMIUM_PLAN_CREATE: 'Tạo gói Premium', PREMIUM_PLAN_UPDATE: 'Sửa gói Premium',
    PREMIUM_PLAN_DEACTIVATE: 'Ngừng bán gói', PREMIUM_PLAN_ACTIVATE: 'Mở bán gói',
    COUPON_CREATE: 'Tạo mã giảm giá', COUPON_UPDATE: 'Sửa mã giảm giá', COUPON_ACTIVE: 'Bật/tắt mã giảm giá',
    COUPON_DELETE: 'Xoá mã giảm giá', CREDIT_PACKAGE_CREATE: 'Tạo gói CP', CREDIT_PACKAGE_UPDATE: 'Sửa gói CP',
    CREDIT_PACKAGE_ACTIVE: 'Bật/tắt gói CP', CREDIT_PACKAGE_DELETE: 'Xoá gói CP'
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
            ['Ngày sinh', birthDateText(u.dateOfBirth)],
            ['Ngày tạo', A.date(u.createdAt)], ['Đăng nhập gần nhất', A.date(u.lastLoginAt)]
          ];
          box.innerHTML = '<section class="adm-panel" aria-label="Chi tiết tài khoản">' +
            '<h2 class="adm-panel__title">Tài khoản #' + u.id + '</h2>' +
            '<dl class="adm-detail">' + fields.filter(function (f) { return f[1] != null && f[1] !== ''; }).map(function (f) {
              return '<dt>' + f[0] + '</dt><dd>' + A.esc(f[1]) + '</dd>';
            }).join('') + '</dl>' +
            '<p class="adm-toolbar">' + lockButton(u) + manageButtons(u) +
              '<button type="button" class="btn btn--secondary btn--sm" data-adjust="' + u.id + '">Điều chỉnh CP</button>' +
              '<button type="button" class="btn btn--secondary btn--sm" data-birth-date="' + u.id + '">Sửa ngày sinh</button>' +
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

      function birthDateText(iso) {
        return iso ? iso.split('-').reverse().join('/') : 'Chưa có';
      }

      // Learners set their birth date once; a wrong one is fixed here, or cleared so they are asked again.
      async function changeBirthDate(id) {
        var u = rows[id];
        var current = u.dateOfBirth || '';
        var now = new Date();
        var today = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-' +
          String(now.getDate()).padStart(2, '0');
        var decision = await A.decide({
          title: 'Sửa ngày sinh của ' + (u.fullName || u.email) + '?',
          message: 'Hiện tại: ' + A.esc(birthDateText(u.dateOfBirth)) + '. Ngày sinh quyết định video Shadowing có ' +
            'nhãn độ tuổi nào người dùng được xem. Để trống để xoá: người dùng sẽ được hỏi lại khi mở video có nhãn.',
          confirmText: 'Lưu ngày sinh', maxLength: 500,
          placeholder: 'Lý do (ít nhất 5 ký tự), lưu vào nhật ký quản trị',
          extraHtml: '<div class="field"><label class="field__label" for="admBirthDate">Ngày sinh mới</label>' +
            '<input class="input" id="admBirthDate" type="date" min="1900-01-01" max="' + today + '" value="' +
              A.esc(current) + '" data-autofocus></div>',
          collect: function (modal) {
            var input = modal.querySelector('#admBirthDate');
            var value = input.value;
            if (input.validity.badInput || (value && (value < '1900-01-01' || value > today))) {
              return { error: 'Ngày sinh chưa hợp lệ.' };
            }
            if (value === current) return { error: 'Ngày sinh mới trùng với ngày sinh hiện tại.' };
            return reasonLength(modal) || { dateOfBirth: value || null };
          }
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/users/' + id + '/birth-date', {
            dateOfBirth: decision.extra.dateOfBirth, reason: decision.reason
          });
          A.toast('Đã cập nhật ngày sinh', 'success');
        } catch (e) {
          A.toast(e.message, 'error');
        }
        await load();
        await showDetail(id);
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
        if ((el = target.closest('[data-birth-date]'))) { changeBirthDate(Number(el.dataset.birthDate)); return; }
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
    // CP adjustment: {amount, before, after} are plain balances.
    if (typeof c.amount === 'number' && typeof c.before === 'number') {
      return A.esc(c.before.toLocaleString('vi-VN')) + ' CP' + to + A.esc(c.after.toLocaleString('vi-VN')) + ' CP' +
        '<br><span class="adm-muted">' + (c.amount > 0 ? '+' : '') + A.esc(c.amount.toLocaleString('vi-VN')) + ' CP</span>';
    }
    // Coupon / CP package created or deleted ({code} / {name}), switched on or off ({active}).
    if (!c.before && !c.after) {
      if ('code' in c) return 'Mã: ' + A.esc(c.code);
      if ('name' in c) return 'Gói: ' + A.esc(c.name);
      if ('active' in c) return c.active ? 'Bật' : 'Tắt';
    }
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
    if (c.before && c.after && 'active' in c.after && Object.keys(c.after).length === 1) {
      return (c.before.active ? 'Đang bán' : 'Ngừng bán') + to + (c.after.active ? 'Đang bán' : 'Ngừng bán');
    }
    // Coupon / CP package edits: list only the fields that changed.
    if (c.before && c.after && typeof c.after === 'object' && !('durationDays' in c.after)) {
      var labels = { code: 'Mã', name: 'Tên', description: 'Mô tả', price: 'Giá', creditAmount: 'Số CP',
        discountType: 'Kiểu giảm', discountValue: 'Mức giảm', maxDiscount: 'Giảm tối đa', minOrderAmount: 'Đơn tối thiểu',
        appliesTo: 'Áp dụng cho', startsAt: 'Bắt đầu', endsAt: 'Kết thúc', usageLimit: 'Lượt dùng tối đa',
        perUserLimit: 'Lượt mỗi người', active: 'Trạng thái', dateOfBirth: 'Ngày sinh' };
      var show = function (k, v) { return v == null ? '—' : k === 'active' ? (v ? 'Bật' : 'Tắt') : v; };
      var changed = Object.keys(c.after).filter(function (k) { return String(c.before[k]) !== String(c.after[k]); });
      if (!changed.length) return '<span class="adm-muted">Không đổi</span>';
      return changed.map(function (k) {
        return A.esc(labels[k] || k) + ': ' + A.esc(show(k, c.before[k])) + to + A.esc(show(k, c.after[k]));
      }).join('<br>');
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

  // ── Dashboard: every figure comes from GET /api/admin/system/dashboard?period=… ─────────────────
  var PERIODS = [
    { key: 'year', label: 'Năm nay', revenue: 'Doanh thu năm nay', compare: 'so với cùng kỳ năm trước' },
    { key: '30d', label: '30 ngày qua', revenue: 'Doanh thu 30 ngày', compare: 'so với 30 ngày trước đó' },
    { key: '7d', label: '7 ngày', revenue: 'Doanh thu 7 ngày', compare: 'so với 7 ngày trước đó' }
  ];
  var TARGET_TYPES = { USER: 'Người dùng', PREMIUM_PLAN: 'Gói Premium', COUPON: 'Mã giảm giá', CREDIT_PACKAGE: 'Gói CP' };

  function numberVi(n) { return Number(n || 0).toLocaleString('vi-VN'); }

  /** Sum of one currency (the first one listed, VND in practice) so two periods can be compared. */
  function revenueOf(list, currency) {
    return (list || []).filter(function (r) { return r.currency === currency; })
      .reduce(function (sum, r) { return sum + Number(r.amount || 0); }, 0);
  }

  /**
   * Growth pill, e.g. "+51 người mới · +89,3%" or "+12,4% tăng trưởng"; the comparison period is in the tooltip.
   * No percentage when the previous period had nothing to compare with.
   */
  function growthPill(current, previous, compare, prefix) {
    var pct = previous ? Math.round((current - previous) / previous * 1000) / 10 : null;
    var pctText = pct == null ? '' : (pct >= 0 ? '+' : '') + pct.toLocaleString('vi-VN') + '%';
    var text = prefix ? prefix + (pctText ? ' · ' + pctText : '') : (pctText ? pctText + ' tăng trưởng' : '');
    if (!text || (!current && pct == null)) return '<span class="st-pill st-pill--neutral">Chưa có dữ liệu kỳ trước</span>';
    return '<span class="st-pill ' + (pct != null && pct < 0 ? 'st-pill--amber' : 'st-pill--growth') + '" title="' +
      A.esc(pctText ? pctText + ' ' + compare : compare) + '">' + A.esc(text) + '</span>';
  }

  /** Smallest 1/2/5 × 10^n step that splits {@code max} into at most five gridlines. */
  function niceScale(max) {
    if (max <= 0) return 5;
    var raw = max / 5, magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
    var step = [1, 2, 5, 10].map(function (m) { return m * magnitude; }).filter(function (s) { return s >= raw; })[0];
    return step * 5;
  }

  function actionTime(value) {
    if (!value) return '—';
    var at = new Date(value), now = new Date();
    var time = at.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    var yesterday = new Date(now); yesterday.setDate(now.getDate() - 1);
    if (at.toDateString() === now.toDateString()) return time + ' - Hôm nay';
    if (at.toDateString() === yesterday.toDateString()) return 'Hôm qua, ' + time.slice(0, 5);
    return at.toLocaleDateString('vi-VN') + ' ' + time.slice(0, 5);
  }

  function initials(email) {
    var name = String(email || '?').split('@')[0].replace(/[^a-zA-Z0-9]/g, ' ').trim().split(/\s+/);
    return ((name[0] || '?')[0] + (name[1] ? name[1][0] : (name[0] || '?').slice(1, 2))).toUpperCase();
  }

  function dashboardHtml(d, moderation, period) {
    var p = PERIODS.filter(function (x) { return x.key === period; })[0];
    var stats = d.periodStats || {};
    var currency = ((stats.paid || [])[0] || (stats.paidPrevious || [])[0] || { currency: 'VND' }).currency;
    var revenueNow = revenueOf(stats.paid, currency), revenueBefore = revenueOf(stats.paidPrevious, currency);
    var revenueText = (stats.paid || []).length
      ? stats.paid.map(function (r) { return money(r.amount, r.currency); }).join(' + ') : money(0, currency);
    var byStatus = d.paymentsByStatus || {};
    var txOf = function (key) { return Number(byStatus[key] || 0); };
    var pending = txOf('PENDING');
    var modCount = moderation ? moderation.pendingQueue + moderation.pendingAppeals + moderation.pendingContributions : null;

    // Premium / Free among learners only (staff roles are left out).
    var learnerTotal = Number((d.usersByRole || {}).LEARNER || 0);
    var premiumActive = Math.min(learnerTotal, Number(d.premiumActive || 0));
    var freeCount = Math.max(0, learnerTotal - premiumActive);
    var premiumPct = learnerTotal > 0 ? Math.round(premiumActive / learnerTotal * 1000) / 10 : 0;
    var freePct = learnerTotal > 0 ? Math.round(1000 - premiumPct * 10) / 10 : 0;
    var donutCirc = 389.55;
    var premiumLen = (premiumPct / 100 * donutCirc).toFixed(2);
    var freeLen = (freePct / 100 * donutCirc).toFixed(2);

    // Transaction health (cancelled and expired count as not completed; refunds are left out).
    var txDone = txOf('PAID'), txPending = pending, txFailed = txOf('FAILED') + txOf('CANCELLED') + txOf('EXPIRED');
    var txTotal = txDone + txPending + txFailed;
    var txPct = function (n) { return txTotal > 0 ? Math.round(n / txTotal * 1000) / 10 : 0; };
    var txDonePct = txPct(txDone), txPendingPct = txPct(txPending), txFailedPct = txPct(txFailed);

    // Registrations per year.
    var years = d.registrationsByYear || [];
    var yearMax = niceScale(Math.max.apply(null, years.map(function (y) { return y.count; }).concat([0])));
    var yearBarMax = 236;

    // Premium purchases per month of the current year.
    var months = [];
    for (var m = 1; m <= 12; m++) months.push({ month: m, count: 0, amounts: {} });
    (d.premiumByMonth || []).forEach(function (r) {
      var slot = months[r.month - 1];
      slot.count += r.count;
      slot.amounts[r.currency] = (slot.amounts[r.currency] || 0) + Number(r.amount || 0);
    });
    var monthMax = Math.max.apply(null, months.map(function (x) { return x.count; }).concat([0]));
    var premiumTotal = months.reduce(function (n, x) { return n + x.count; }, 0);
    var currentMonth = new Date().getMonth() + 1;
    var monthBarMax = 150;

    var recent = d.recentActions || [];

    return '<div class="st-dashboard">' +
      '<header class="st-topbar">' +
        '<div class="st-topbar__left"><div class="st-topbar__title-wrap"><span class="st-topbar__dot"></span>' +
          '<h1 class="st-topbar__title">Tổng quan hệ thống</h1></div></div>' +
        '<div class="st-topbar__actions">' +
          '<div class="st-segmented" role="tablist" aria-label="Khoảng thời gian">' +
            PERIODS.map(function (x) {
              return '<button type="button" role="tab" class="st-segmented__btn' + (x.key === period ? ' active' : '') +
                '" aria-selected="' + (x.key === period) + '" data-period="' + x.key + '">' + x.label + '</button>';
            }).join('') +
          '</div>' +
          '<a class="st-icon-btn" href="/admin/moderation/queue" title="' +
            (modCount ? modCount + ' mục đang chờ kiểm duyệt' : 'Không có mục chờ kiểm duyệt') + '">' +
            '<span class="material-symbols-outlined" style="font-size:20px;">notifications</span>' +
            (modCount ? '<span class="st-badge-dot"></span>' : '') +
          '</a>' +
        '</div>' +
      '</header>' +

      '<div class="st-grid-kpi">' +
        kpiCard('Tổng người dùng', 'group', 'purple', numberVi(d.totalUsers),
          growthPill(stats.newUsers, stats.newUsersPrevious, p.compare, '+' + numberVi(stats.newUsers) + ' người mới')) +
        kpiCard(p.revenue, 'credit_card', 'purple', revenueText,
          growthPill(revenueNow, revenueBefore, p.compare, '')) +
        kpiCard('Giao dịch đang chờ', 'hourglass_empty', 'amber', numberVi(pending) + ' giao dịch',
          pending ? '<span class="st-pill st-pill--amber">Cần xác nhận</span>' : '<span class="st-pill st-pill--neutral">Không có</span>') +
        kpiCard('Kiểm duyệt chờ xử lý', 'verified', 'purple', modCount == null ? '—' : numberVi(modCount) + ' mục',
          modCount ? '<span class="st-pill st-pill--purple">Cần xử lý</span>' : '<span class="st-pill st-pill--neutral">Đã xử lý hết</span>') +
      '</div>' +

      '<div class="st-card">' +
        '<div class="st-card__header"><div class="st-card__title-group"><div class="st-card__title-row">' +
          '<span class="st-square-mark"></span><h2 class="st-card__title">Số lượng người đăng ký mới mỗi năm</h2>' +
        '</div></div></div>' +
        '<div class="st-annual-chart">' +
          '<div class="st-annual-chart__gridlines">' +
            [5, 4, 3, 2, 1, 0].map(function (i) {
              return '<div class="st-gridline-row"><span class="st-gridline-val">' + numberVi(yearMax / 5 * i) + '</span></div>';
            }).join('') +
          '</div>' +
          '<div class="st-annual-chart__bars">' +
            years.map(function (y, i) {
              var last = i === years.length - 1;
              return '<div class="st-annual-col' + (last ? ' st-annual-col--peak' : '') + '" title="' + y.year + ': ' + numberVi(y.count) + ' người đăng ký">' +
                '<span class="st-annual-col__num"' + (last ? ' style="color:var(--st-primary-container); font-weight:700;"' : '') + '>' + numberVi(y.count) + '</span>' +
                '<div class="st-annual-col__bar" style="height:' + Math.round(y.count / yearMax * yearBarMax) + 'px;"></div>' +
                '<span class="st-annual-col__year">' + y.year + (last ? ' (Hiện tại)' : '') + '</span>' +
              '</div>';
            }).join('') +
          '</div>' +
        '</div>' +
      '</div>' +

      '<div class="st-grid-trio">' +
        '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;"><div>' +
          '<div class="st-card__header"><div class="st-card__title-group">' +
            '<h2 class="st-card__title">Số lượng người đăng ký Premium mỗi tháng (' + A.esc(d.premiumYear) + ')</h2></div>' +
            '<span class="st-pill st-pill--purple">Tổng ' + numberVi(premiumTotal) + ' lượt</span>' +
          '</div>' +
          '<div class="st-monthly-chart"><div class="st-monthly-chart__bars">' +
            months.map(function (x) {
              var amounts = Object.keys(x.amounts).map(function (c) { return money(x.amounts[c], c); }).join(' + ') || money(0, currency);
              var cls = x.count > 0 && x.count === monthMax ? ' st-monthly-col--max' : (x.month === currentMonth ? ' st-monthly-col--highlight' : '');
              return '<div class="st-monthly-col' + cls + '">' +
                '<div class="st-bar-tooltip"><span class="st-bar-tooltip__title">Tháng ' + x.month + '</span>' +
                  '<span class="st-bar-tooltip__detail">' + numberVi(x.count) + ' lượt • ' + amounts + '</span></div>' +
                '<div class="st-monthly-col__bar" style="height:' + (monthMax ? Math.round(x.count / monthMax * monthBarMax) : 0) + 'px;"></div>' +
                '<span class="st-monthly-col__label">T' + x.month + '</span>' +
              '</div>';
            }).join('') +
          '</div></div>' +
        '</div></div>' +

        '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;"><div>' +
          '<div class="st-card__header"><div class="st-card__title-group">' +
            '<h2 class="st-card__title" style="font-size:15px;">Tỉ lệ Premium / Miễn phí</h2></div></div>' +
          '<div class="st-donut-wrap"><div class="st-donut"><svg viewBox="0 0 160 160">' +
            '<circle cx="80" cy="80" r="62" fill="none" stroke="#EEF5F7" stroke-width="18"></circle>' +
            '<circle cx="80" cy="80" r="62" fill="none" stroke="#4F378A" stroke-width="18" stroke-dasharray="' + premiumLen + ' ' + donutCirc + '" stroke-dashoffset="0" stroke-linecap="round"></circle>' +
            '<circle cx="80" cy="80" r="62" fill="none" stroke="#EAB308" stroke-width="18" stroke-dasharray="' + freeLen + ' ' + donutCirc + '" stroke-dashoffset="-' + premiumLen + '" stroke-linecap="round"></circle>' +
          '</svg><div class="st-donut__center"><span class="st-donut__center-num">' + numberVi(learnerTotal) + '</span>' +
            '<span class="st-donut__center-label">Học viên</span></div></div></div>' +
        '</div>' +
        '<div class="st-legend-list">' +
          legendItem('#4F378A', 'Premium', premiumActive, premiumPct, 'purple') +
          legendItem('#EAB308', 'Miễn phí', freeCount, freePct, 'amber') +
        '</div></div>' +

        '<div class="st-card" style="display:flex; flex-direction:column; justify-content:space-between;"><div>' +
          '<div class="st-card__header"><div class="st-card__title-group">' +
            '<h2 class="st-card__title" style="font-size:15px;">Giao dịch</h2></div></div>' +
          '<div class="st-tx-health__val-row"><span class="st-tx-health__pct">' + txDonePct + '%</span>' +
            (txTotal === 0 ? '' : txDonePct >= 90
              ? '<span class="st-pill st-pill--growth" style="padding:2px 8px; font-size:10px;">Hoàn tất chuẩn</span>'
              : '<span class="st-pill st-pill--amber" style="padding:2px 8px; font-size:10px;">Cần kiểm tra</span>') +
          '</div>' +
          '<div class="st-seg-bar">' +
            [['green', 'Hoàn tất', txDonePct], ['amber', 'Đang chờ', txPendingPct], ['red', 'Thất bại', txFailedPct]]
              .filter(function (s) { return s[2] > 0; })
              .map(function (s) {
                return '<div class="st-seg-bar__slice st-seg-bar__slice--' + s[0] + '" style="flex:' + s[2] + ' 1 0;" title="' + s[1] + ': ' + s[2] + '%"></div>';
              }).join('') +
          '</div>' +
          '<div class="st-status-boxes">' +
            statusBox('#006947', 'Hoàn tất', '', txDone, txDonePct) +
            statusBox('#F5A400', 'Đang chờ', '#B45309', txPending, txPendingPct) +
            statusBox('#BA1A1A', 'Thất bại', '#BA1A1A', txFailed, txFailedPct) +
          '</div></div>' +
          '<a class="st-btn-outline-wide" href="/admin/system/payments">' +
            '<span class="material-symbols-outlined" style="font-size:16px;">account_balance_wallet</span>' +
            '<span>Kiểm tra cổng thanh toán</span></a>' +
        '</div>' +
      '</div>' +

      '<div class="st-card"><div>' +
        '<div class="st-card__header"><div class="st-card__title-group"><h2 class="st-card__title">Thao tác quản trị gần đây</h2></div>' +
          '<a class="st-pill st-pill--neutral" href="/admin/system/audit">Xem nhật ký</a></div>' +
        (recent.length ? '<div class="st-table-wrap"><table class="st-table"><thead><tr>' +
            '<th>Thời gian</th><th>Người thao tác</th><th>Hành động</th><th>Đối tượng</th><th style="text-align:right;">Trạng thái</th>' +
          '</tr></thead><tbody>' +
          recent.map(function (r, i) {
            var badge = ['purple', 'green', 'amber'][i % 3];
            var ok = r.result === 'SUCCESS';
            return '<tr>' +
              '<td style="color:var(--st-outline); white-space:nowrap;">' + A.esc(actionTime(r.createdAt)) + '</td>' +
              '<td style="white-space:nowrap;"><div class="st-table-user"><span class="st-avatar-badge st-avatar-badge--' + badge + '">' +
                A.esc(initials(r.actorEmail)) + '</span><span style="font-weight:500;">' + A.esc(r.actorEmail || ('#' + r.actorId)) + '</span></div></td>' +
              '<td style="font-weight:500;">' + A.esc(ACTIONS[r.action] || r.action) + '</td>' +
              '<td style="color:var(--st-on-surface-variant);">' + A.esc(TARGET_TYPES[r.targetType] || r.targetType) +
                (r.targetId ? ' #' + A.esc(r.targetId) : '') + (r.reason ? ' · ' + A.esc(r.reason) : '') + '</td>' +
              '<td style="text-align:right; white-space:nowrap;"><span class="st-pill ' + (ok ? 'st-pill--growth">Thành công' : 'st-pill--amber">Bị từ chối') + '</span></td>' +
            '</tr>';
          }).join('') +
          '</tbody></table></div>'
          : A.empty('fa-clock-rotate-left', 'Chưa có thao tác quản trị', 'Các thao tác như khoá tài khoản, sửa gói sẽ hiện ở đây.')) +
      '</div></div>' +
    '</div>';
  }

  function kpiCard(label, icon, tone, value, pill) {
    return '<div class="st-card st-kpi-card"><div class="st-kpi-card__top"><span class="st-kpi-card__label">' + A.esc(label) + '</span>' +
      '<div class="st-kpi-card__icon-wrap st-kpi-card__icon-wrap--' + tone + '"><span class="material-symbols-outlined" style="font-size:20px;">' + icon + '</span></div></div>' +
      '<div class="st-kpi-card__val-block"><div class="st-kpi-card__value">' + value + '</div>' +
      '<div class="st-kpi-card__pill-wrap">' + pill + '</div></div></div>';
  }

  function legendItem(color, label, count, pct, tone) {
    return '<div class="st-legend-item"><div class="st-legend-item__left"><span class="st-legend-dot" style="background:' + color + ';"></span>' +
      '<span>' + label + '</span></div><div class="st-legend-item__right"><span>' + numberVi(count) + '</span>' +
      '<span class="st-pill st-pill--' + tone + '" style="padding:1px 6px; font-size:10px;">' + pct + '%</span></div></div>';
  }

  function statusBox(dot, label, color, count, pct) {
    return '<div class="st-status-box"><div class="st-status-box__left">' +
      '<span style="width:7px; height:7px; border-radius:50%; background:' + dot + '; display:inline-block;"></span><span>' + label + '</span></div>' +
      '<div class="st-status-box__right"><span' + (color ? ' style="color:' + color + ';"' : '') + '>' + numberVi(count) + ' gd</span>' +
      '<span class="st-status-box__sub">(' + pct + '%)</span></div></div>';
  }

  A.section('system-dashboard', {
    subtitle: '',
    render: async function (root) {
      document.body.classList.add('dashboard-view');
      var period = 'year';
      try { period = sessionStorage.getItem('jlas-admin-dashboard-period') || 'year'; } catch (ignored) { /* private mode */ }

      async function draw() {
        root.setAttribute('aria-busy', 'true');
        var results = await Promise.allSettled([
          A.api.get(API + '/dashboard?period=' + encodeURIComponent(period)),
          A.api.get('/api/admin/moderation/overview')
        ]);
        root.removeAttribute('aria-busy');
        if (results[0].status !== 'fulfilled') {
          root.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được tổng quan hệ thống',
            (results[0].reason && results[0].reason.message) || 'Vui lòng tải lại trang.');
          return;
        }
        root.innerHTML = dashboardHtml(results[0].value, results[1].status === 'fulfilled' ? results[1].value : null, period);
      }

      root.addEventListener('click', function (event) {
        var button = event.target.closest('[data-period]');
        if (!button || button.dataset.period === period) return;
        period = button.dataset.period;
        try { sessionStorage.setItem('jlas-admin-dashboard-period', period); } catch (ignored) { /* private mode */ }
        draw();
      });
      root.innerHTML = A.loading();
      await draw();
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
