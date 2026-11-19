/* Tool sections: denylist, user sanctions, audit trail (JLAS-W0250). */
(function () {
  'use strict';

  var A = window.Admin;
  var API = '/api/admin/moderation';

  var ACTION_LABELS = { BLOCK: 'Chặn', REVIEW: 'Cần xem lại' };

  // ── Denylist ───────────────────────────────────────────────────────────
  A.section('denylist', {
    subtitle: 'Từ khoá dùng để kiểm tra nội dung người dùng gửi lên. Thay đổi có hiệu lực ngay.',
    render: async function (root) {
      var state = { terms: [], query: '', action: '' };
      root.innerHTML =
        '<div class="adm-grid-2">' +
          '<section class="adm-panel">' +
            '<h2 class="adm-panel__title">Thêm từ khoá</h2>' +
            '<form class="adm-form" data-add novalidate>' +
              '<div class="field"><label class="field__label" for="admTerm">Từ khoá</label>' +
                '<input class="input jp" id="admTerm" maxlength="255" placeholder="Ví dụ: spam link"></div>' +
              '<div class="field"><label class="field__label" for="admAction">Hành động</label>' +
                '<select class="select" id="admAction" data-jl-select><option value="BLOCK">Chặn, không cho đăng</option>' +
                '<option value="REVIEW">Cần xem lại, đưa vào hàng đợi</option></select></div>' +
              '<label class="adm-check"><input type="checkbox" id="admAccent"> Phân biệt dấu tiếng Việt (dùng cho từ ngắn dễ trùng)</label>' +
              '<div class="field"><label class="field__label" for="admAddReason">Lý do</label>' +
                '<input class="input" id="admAddReason" maxlength="1000" placeholder="Vì sao cần thêm từ khoá này"></div>' +
              '<p class="adm-error" data-add-error aria-live="polite"></p>' +
              '<button type="submit" class="btn btn--primary btn--sm"><i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm từ khoá</button>' +
            '</form>' +
          '</section>' +
          '<section class="adm-panel">' +
            '<h2 class="adm-panel__title">Thử văn bản</h2>' +
            '<form class="adm-form" data-test novalidate>' +
              '<textarea class="textarea jp" id="admTestText" rows="4" maxlength="5000" placeholder="Dán đoạn văn để xem có từ khoá nào bị bắt"></textarea>' +
              '<button type="submit" class="btn btn--secondary btn--sm"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i> Kiểm tra</button>' +
              '<div data-test-result></div>' +
            '</form>' +
          '</section>' +
        '</div>' +
        '<section class="adm-panel">' +
          '<div class="adm-toolbar" data-filter-bar>' +
            '<input type="search" class="input" data-search placeholder="Tìm từ khoá" maxlength="100" aria-label="Tìm từ khoá">' +
            '<select class="select" data-filter aria-label="Hành động"><option value="">Mọi hành động</option>' +
              '<option value="BLOCK">Chặn</option><option value="REVIEW">Cần xem lại</option><option value="OFF">Đang tắt</option></select>' +
            '<span class="adm-muted" data-count></span>' +
          '</div>' +
          '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
            '<th scope="col">Từ khoá</th><th scope="col">Hành động</th><th scope="col">Phân biệt dấu</th>' +
            '<th scope="col">Trạng thái</th><th scope="col">Cập nhật</th><th scope="col"><span class="sr-only">Sửa</span></th>' +
          '</tr></thead><tbody data-rows></tbody></table></div>' +
        '</section>';

      var rows = root.querySelector('[data-rows]');
      A.filterPopover(root.querySelector('[data-filter-bar]'));

      function render() {
        var q = state.query.toLowerCase();
        var list = state.terms.filter(function (t) {
          if (q && t.term.toLowerCase().indexOf(q) < 0) return false;
          if (state.action === 'OFF') return !t.active;
          return !state.action || (t.active && t.action === state.action);
        });
        root.querySelector('[data-count]').textContent = list.length + ' / ' + state.terms.length + ' từ khoá';
        rows.innerHTML = list.length ? list.map(function (t) {
          return '<tr data-id="' + t.id + '"' + (t.active ? '' : ' class="is-off"') + '>' +
            '<td class="jp"><strong>' + A.esc(t.term) + '</strong></td>' +
            '<td><span class="adm-badge ' + (t.action === 'BLOCK' ? 'adm-badge--danger' : 'adm-badge--warn') + '">' + ACTION_LABELS[t.action] + '</span></td>' +
            '<td>' + (t.accentSensitive ? 'Có' : '—') + '</td>' +
            '<td>' + (t.active ? '<span class="adm-badge adm-badge--ok">Đang bật</span>' : '<span class="adm-badge">Đang tắt</span>') + '</td>' +
            '<td class="adm-muted">' + A.date(t.updatedAt) + '</td>' +
            '<td><button type="button" class="adm-icon-btn" data-edit aria-label="Sửa từ khoá ' + A.esc(t.term) + '"><i class="fa-solid fa-pen" aria-hidden="true"></i></button></td>' +
          '</tr>';
        }).join('') : '<tr><td colspan="6">' + A.empty('fa-magnifying-glass', 'Không có từ khoá phù hợp') + '</td></tr>';
      }

      async function load() {
        state.terms = await A.api.get(API + '/denylist');
        render();
      }

      root.querySelector('[data-search]').addEventListener('input', function (e) { state.query = e.target.value.trim(); render(); });
      root.querySelector('[data-filter]').addEventListener('change', function (e) { state.action = e.target.value; render(); });

      root.querySelector('[data-add]').addEventListener('submit', async function (event) {
        event.preventDefault();
        var error = root.querySelector('[data-add-error]');
        var term = root.querySelector('#admTerm').value.trim();
        var reason = root.querySelector('#admAddReason').value.trim();
        if (!term) { error.textContent = 'Vui lòng nhập từ khoá.'; return; }
        if (!reason) { error.textContent = 'Vui lòng nhập lý do.'; return; }
        error.textContent = '';
        try {
          await A.api.post(API + '/denylist', {
            term: term, action: root.querySelector('#admAction').value,
            accentSensitive: root.querySelector('#admAccent').checked, reason: reason
          });
          A.toast('Đã thêm từ khoá "' + term + '"', 'success');
          event.target.reset();
          await load();
        } catch (e) {
          error.textContent = e.message;
        }
      });

      root.querySelector('[data-test]').addEventListener('submit', async function (event) {
        event.preventDefault();
        var text = root.querySelector('#admTestText').value;
        var out = root.querySelector('[data-test-result]');
        if (!text.trim()) { out.innerHTML = '<p class="adm-error">Vui lòng nhập văn bản cần thử.</p>'; return; }
        try {
          var result = await A.api.post(API + '/denylist/test', { text: text });
          var hits = result.hits;
          out.innerHTML = hits.length
            ? '<div class="adm-note adm-note--warn"><strong>Bị bắt bởi ' + hits.length + ' từ khoá:</strong><div class="adm-chips">' +
              hits.map(function (h) {
                return '<span class="adm-chip adm-chip--' + (h.action === 'BLOCK' ? 'block' : 'review') + ' jp">' +
                  A.esc(h.term) + ' · ' + ACTION_LABELS[h.action] + '</span>';
              }).join('') + '</div></div>' +
              '<p class="adm-card__preview jp">' + A.highlighted(text, result.highlights) + '</p>'
            : '<p class="adm-note adm-note--ok"><i class="fa-solid fa-circle-check" aria-hidden="true"></i> Không có từ khoá nào bị bắt.</p>';
        } catch (e) {
          out.innerHTML = '<p class="adm-error">' + A.esc(e.message) + '</p>';
        }
      });

      rows.addEventListener('click', async function (event) {
        if (!event.target.closest('[data-edit]')) return;
        var id = Number(event.target.closest('tr').dataset.id);
        var term = state.terms.filter(function (t) { return t.id === id; })[0];
        var decision = await A.decide({
          title: 'Sửa từ khoá "' + term.term + '"',
          confirmText: 'Lưu thay đổi',
          extraHtml:
            '<div class="field"><label class="field__label" for="admEditAction">Hành động</label>' +
              '<select class="select" id="admEditAction" data-autofocus data-jl-select>' +
                '<option value="BLOCK"' + (term.action === 'BLOCK' ? ' selected' : '') + '>Chặn</option>' +
                '<option value="REVIEW"' + (term.action === 'REVIEW' ? ' selected' : '') + '>Cần xem lại</option></select></div>' +
            '<label class="adm-check"><input type="checkbox" id="admEditAccent"' + (term.accentSensitive ? ' checked' : '') + '> Phân biệt dấu tiếng Việt</label>' +
            '<label class="adm-check"><input type="checkbox" id="admEditActive"' + (term.active ? ' checked' : '') + '> Đang bật</label>',
          collect: function (modal) {
            return {
              action: modal.querySelector('#admEditAction').value,
              accentSensitive: modal.querySelector('#admEditAccent').checked,
              active: modal.querySelector('#admEditActive').checked
            };
          }
        });
        if (!decision) return;
        try {
          await A.api.put(API + '/denylist/' + id, Object.assign({ reason: decision.reason }, decision.extra));
          A.toast('Đã cập nhật từ khoá', 'success');
          await load();
        } catch (e) {
          A.toast(e.message, 'error');
        }
      });

      await load();
    }
  });

  // ── Sanctions ──────────────────────────────────────────────────────────
  function sanctionRow(s, withUser) {
    var active = !s.revokedAt && (!s.endsAt || new Date(s.endsAt) > new Date());
    return '<tr data-id="' + s.id + '">' +
      (withUser ? '<td>#' + s.userId + '</td>' : '') +
      '<td>' + A.esc(A.sanctionLabel(s.type, s.endsAt)) + '</td>' +
      '<td>' + A.esc(s.reason || '—') + '</td>' +
      '<td class="adm-muted">' + A.date(s.startsAt) + '</td>' +
      '<td>' + (active ? '<span class="adm-badge adm-badge--danger">Đang hiệu lực</span>'
        : s.revokedAt ? '<span class="adm-badge">Đã gỡ ' + A.date(s.revokedAt) + '</span>' : '<span class="adm-badge">Hết hạn</span>') + '</td>' +
      '<td>' + (active ? '<button type="button" class="btn btn--secondary btn--sm" data-revoke>Gỡ chế tài</button>' : '') + '</td>' +
    '</tr>';
  }

  function sanctionTable(list, withUser) {
    return '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
      (withUser ? '<th scope="col">Người dùng</th>' : '') +
      '<th scope="col">Chế tài</th><th scope="col">Lý do</th><th scope="col">Bắt đầu</th><th scope="col">Trạng thái</th>' +
      '<th scope="col"><span class="sr-only">Thao tác</span></th></tr></thead><tbody>' +
      (list.length ? list.map(function (s) { return sanctionRow(s, withUser); }).join('')
        : '<tr><td colspan="' + (withUser ? 6 : 5) + '">' + A.empty('fa-inbox', 'Chưa có chế tài nào') + '</td></tr>') +
      '</tbody></table></div>';
  }

  A.section('sanctions', {
    subtitle: 'Cấm bình luận hoặc khoá tài khoản người dùng vi phạm. Mọi thao tác đều được ghi nhật ký.',
    render: async function (root, ctx) {
      var found = null;
      root.innerHTML =
        '<section class="adm-section">' +
          '<h2 class="adm-panel__title">Tìm người dùng</h2>' +
          '<form class="adm-toolbar" data-lookup novalidate>' +
            '<input type="email" class="input" id="admEmail" placeholder="Email người dùng" maxlength="255" aria-label="Email người dùng">' +
            '<button type="submit" class="btn btn--primary btn--sm"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i> Tìm</button>' +
          '</form>' +
          '<div data-user></div>' +
        '</section>' +
        '<section class="adm-section"><h2 class="adm-panel__title">Chế tài đang hiệu lực</h2><div data-active></div></section>';

      async function loadActive() {
        root.querySelector('[data-active]').innerHTML = sanctionTable(await A.api.get(API + '/sanctions/active'), true);
      }

      async function lookup(email) {
        var box = root.querySelector('[data-user]');
        box.innerHTML = A.loading();
        try {
          found = await A.api.get(API + '/sanctions/lookup?email=' + encodeURIComponent(email));
          var u = found.user;
          box.innerHTML =
            '<div class="adm-user"><strong>' + A.esc(u.fullName || u.email) + '</strong>' +
              '<span class="adm-muted">' + A.esc(u.email) + ' · #' + u.id + ' · ' + A.esc(A.ROLE_LABELS[u.role] || 'Học viên') + '</span></div>' +
            '<button type="button" class="btn btn--sm adm-btn--danger" data-apply><i class="fa-solid fa-user-lock" aria-hidden="true"></i> Áp chế tài</button>' +
            '<h3 class="adm-panel__subtitle">Lịch sử chế tài</h3>' + sanctionTable(found.history, false);
        } catch (e) {
          found = null;
          box.innerHTML = '<p class="adm-error">' + A.esc(e.message) + '</p>';
        }
      }

      root.querySelector('[data-lookup]').addEventListener('submit', function (event) {
        event.preventDefault();
        var email = root.querySelector('#admEmail').value.trim();
        if (!email) {
          root.querySelector('[data-user]').innerHTML = '<p class="adm-error">Vui lòng nhập email.</p>';
          return;
        }
        lookup(email);
      });

      root.addEventListener('click', async function (event) {
        if (event.target.closest('[data-apply]') && found) {
          var decision = await A.decide({
            title: 'Áp chế tài cho ' + (found.user.fullName || found.user.email) + '?',
            danger: true, confirmText: 'Áp chế tài', maxLength: 500, userVisible: true,
            extraHtml: '<div class="field"><label class="field__label" for="admApplyType">Loại chế tài</label>' +
              '<select class="select" id="admApplyType" data-autofocus data-jl-select>' + A.sanctionOptions(false) + '</select></div>',
            collect: function (modal) { return A.parseSanction(modal.querySelector('#admApplyType').value); }
          });
          if (!decision) return;
          try {
            await A.api.post(API + '/sanctions', {
              userId: found.user.id, type: decision.extra.type, durationDays: decision.extra.durationDays, reason: decision.reason
            });
            A.toast('Đã áp chế tài', 'success');
            await Promise.all([lookup(found.user.email), loadActive()]);
            ctx.refreshCounts();
          } catch (e) {
            A.toast(e.message, 'error');
          }
          return;
        }
        var revoke = event.target.closest('[data-revoke]');
        if (!revoke) return;
        var id = revoke.closest('tr').dataset.id;
        var decision2 = await A.decide({ title: 'Gỡ chế tài #' + id + '?', confirmText: 'Gỡ chế tài' });
        if (!decision2) return;
        try {
          await A.api.post(API + '/sanctions/' + id + '/revoke', { reason: decision2.reason });
          A.toast('Đã gỡ chế tài', 'success');
          await Promise.all([found ? lookup(found.user.email) : null, loadActive()]);
          ctx.refreshCounts();
        } catch (e) {
          A.toast(e.message, 'error');
        }
      });

      await loadActive();
    }
  });

  // ── Audit trail ────────────────────────────────────────────────────────
  var AUDIT_ACTIONS = {
    REPORT_VALID: 'Báo cáo đúng, gỡ nội dung', REPORT_INVALID: 'Báo cáo sai, khôi phục',
    COMMUNITY_REMOVE: 'Gỡ trực tiếp (Cộng đồng)',
    QUEUE_APPROVE: 'Hàng đợi: cho phép', QUEUE_REJECT: 'Hàng đợi: ẩn nội dung',
    DICTIONARY_APPROVE: 'Duyệt đóng góp từ điển', DICTIONARY_REJECT: 'Từ chối đóng góp từ điển',
    APPEAL_APPROVE: 'Chấp nhận kháng nghị', APPEAL_REJECT: 'Từ chối kháng nghị',
    SANCTION_APPLY: 'Áp chế tài', SANCTION_REVOKE: 'Gỡ chế tài', SANCTION_REPLACE: 'Thay chế tài cũ',
    DENYLIST_CREATE: 'Thêm từ khoá cấm', DENYLIST_UPDATE: 'Sửa từ khoá cấm', DENYLIST_DISABLE: 'Tắt từ khoá cấm',
    QUEUE_REVISE: 'Hàng đợi: đổi quyết định', DICTIONARY_REVISE: 'Từ điển: đổi quyết định',
    APPEAL_REVISE: 'Kháng nghị: đổi quyết định', SANCTION_REINSTATE: 'Áp lại chế tài'
  };

  var STATE_LABELS = {
    OPEN: 'Chờ xử lý', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối', ACTIVE: 'Đang hiệu lực', REVOKED: 'Đã gỡ',
    PUBLISHED: 'Hiển thị', TEMPORARILY_HIDDEN: 'Ẩn tạm thời', REMOVED: 'Đã gỡ'
  };

  function stateLabel(value) {
    if (!value) return '—';
    if (STATE_LABELS[value]) return STATE_LABELS[value];
    var sanction = /^(BAN|MUTE):(?:(\d+)D|PERMANENT)$/.exec(value);
    if (sanction) return (sanction[1] === 'BAN' ? 'Khoá tài khoản ' : 'Cấm bình luận ') + (sanction[2] ? sanction[2] + ' ngày' : 'vĩnh viễn');
    return value;
  }

  // What each outcome means per decision family, and what flipping to it does.
  var REVISE_TEXT = {
    QUEUE: {
      APPROVED: ['Cho phép nội dung', 'Nội dung sẽ hiển thị lại cho mọi người.'],
      REJECTED: ['Ẩn nội dung', 'Nội dung sẽ bị ẩn khỏi người học.']
    },
    DICTIONARY: {
      APPROVED: ['Duyệt và công khai', 'Từ được công khai lại trong từ điển; người đóng góp nhận lại credit thưởng nếu đã bị thu hồi.'],
      REJECTED: ['Từ chối', 'Từ bị gỡ khỏi từ điển và thu hồi 15 credit thưởng (không trừ quá số dư hiện có).']
    },
    APPEAL: {
      APPROVED: ['Chấp nhận kháng nghị', 'Chế tài của người dùng sẽ được gỡ ngay.'],
      REJECTED: ['Từ chối kháng nghị', 'Chế tài cũ được áp lại nếu chưa hết hạn.']
    }
  };

  function affectedCell(person) {
    if (!person) return '<span class="adm-muted">—</span>';
    return '<span class="adm-person"><strong>' + A.esc(person.name || ('Người dùng #' + person.id)) + '</strong>' +
      '<span class="adm-muted">' + A.esc(person.email || '') + ' · #' + person.id + '</span></span>';
  }

  function reviseDialog(row) {
    var family = REVISE_TEXT[row.action.split('_')[0]];
    var current = family[row.toState] || [stateLabel(row.toState), ''];
    var next = family[row.toState === 'APPROVED' ? 'REJECTED' : 'APPROVED'];
    return A.decide({
      title: 'Đổi quyết định?',
      message: '<span class="adm-revise">' +
          '<span><span class="adm-muted">Hiện tại</span><strong>' + A.esc(current[0]) + '</strong></span>' +
          '<span><span class="adm-muted">Đổi thành</span><strong>' + A.esc(next[0]) + '</strong></span>' +
        '</span>' + A.esc(next[1]),
      confirmText: 'Đổi quyết định',
      userVisible: true,
      danger: row.toState === 'APPROVED',
      placeholder: 'Vì sao cần đổi quyết định này'
    });
  }

  A.section('audit', {
    subtitle: 'Mọi thay đổi trạng thái trong khu kiểm duyệt: ai làm, lúc nào, quyết định gì, vì sao. ' +
      'Quyết định mới nhất của mỗi mục có thể đổi lại; mỗi lần đổi được ghi thành một dòng mới.',
    render: async function (root, ctx) {
      var page = 0;
      var rowsById = {};
      root.innerHTML =
        '<form class="adm-toolbar" data-filters novalidate>' +
          '<select class="select" name="action" aria-label="Hành động"><option value="">Mọi hành động</option>' +
            Object.keys(AUDIT_ACTIONS).map(function (k) { return '<option value="' + k + '">' + AUDIT_ACTIONS[k] + '</option>'; }).join('') +
          '</select>' +
          '<input class="input" name="actorId" inputmode="numeric" maxlength="12" placeholder="Mã người thao tác" aria-label="Mã người thao tác" data-in-filter="Người thao tác">' +
          '<div class="adm-filter-range" data-in-filter="Khoảng thời gian">' +
            '<label class="adm-inline">Từ <input class="input" type="date" name="from"></label>' +
            '<label class="adm-inline">Đến <input class="input" type="date" name="to"></label></div>' +
        '</form><div data-list></div>';
      var form = root.querySelector('[data-filters]');
      A.filterPopover(form);
      A.digitsOnly(form.elements.actorId);

      async function load() {
        var list = root.querySelector('[data-list]');
        list.innerHTML = A.loading();
        var params = new URLSearchParams({ page: page, size: 30 });
        ['action', 'actorId', 'from', 'to'].forEach(function (k) { if (form.elements[k].value) params.set(k, form.elements[k].value); });
        try {
          var data = await A.api.get(API + '/audit?' + params.toString());
          rowsById = {};
          data.items.forEach(function (a) { rowsById[a.id] = a; });
          list.innerHTML = (data.items.length ?
            '<div class="adm-table-wrap"><table class="adm-table"><thead><tr>' +
              '<th scope="col">Thời gian</th><th scope="col">Người thao tác</th><th scope="col">Hành động</th>' +
              '<th scope="col">Đối tượng</th><th scope="col">Người bị ảnh hưởng</th>' +
              '<th scope="col">Trạng thái</th><th scope="col">Lý do</th>' +
              '<th scope="col"><span class="sr-only">Thao tác</span></th></tr></thead><tbody>' +
              data.items.map(function (a) {
                return '<tr><td class="adm-muted">' + A.date(a.createdAt) + '</td>' +
                  '<td>#' + (a.actorUserId || '—') + '<br><span class="adm-muted">' + A.esc(a.actorRole) + '</span></td>' +
                  '<td>' + A.esc(AUDIT_ACTIONS[a.action] || a.action) + '</td>' +
                  '<td>' + A.esc(A.typeLabel(a.targetType)) + (a.targetId ? ' #' + a.targetId : '') + '</td>' +
                  '<td>' + affectedCell(a.affected) + '</td>' +
                  '<td>' + A.esc(stateLabel(a.fromState)) + ' <span class="adm-muted">thành</span> ' + A.esc(stateLabel(a.toState)) + '</td>' +
                  '<td>' + A.esc(a.reason) + '</td>' +
                  '<td>' + (a.revisable ? '<button type="button" class="btn btn--secondary btn--sm adm-nowrap" data-revise="' + a.id + '">' +
                    '<i class="fa-solid fa-rotate" aria-hidden="true"></i> Đổi quyết định</button>' : '') + '</td></tr>';
              }).join('') + '</tbody></table></div>'
            : A.empty('fa-inbox', 'Chưa có bản ghi nào')) +
            (data.totalPages > 1 ? '<div class="adm-pager">' +
              '<button type="button" class="btn btn--secondary btn--sm" data-page="-1"' + (page <= 0 ? ' disabled' : '') + '>Trang trước</button>' +
              '<span class="adm-muted">Trang ' + (page + 1) + '/' + data.totalPages + '</span>' +
              '<button type="button" class="btn btn--secondary btn--sm" data-page="1"' + (page + 1 >= data.totalPages ? ' disabled' : '') + '>Trang sau</button>' +
            '</div>' : '');
        } catch (e) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được nhật ký', e.message);
        }
      }

      form.addEventListener('submit', function (event) { event.preventDefault(); page = 0; load(); });
      root.addEventListener('click', async function (event) {
        var revise = event.target.closest('[data-revise]');
        if (revise) {
          var row = rowsById[revise.dataset.revise];
          var decision = row && await reviseDialog(row);
          if (!decision) return;
          try {
            await A.api.post(API + '/audit/' + row.id + '/revise', { reason: decision.reason });
            A.toast('Đã đổi quyết định, người dùng đã được thông báo', 'success');
            await load();
            ctx.refreshCounts();
          } catch (e) {
            A.toast(e.message, 'error');
            if (e.status === 409) load();
          }
          return;
        }
        var button = event.target.closest('[data-page]');
        if (!button) return;
        page += Number(button.dataset.page);
        load();
      });
      await load();
    }
  });
})();
