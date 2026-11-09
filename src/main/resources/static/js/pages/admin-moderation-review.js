/* Review sections: overview, reported content, AI queue, dictionary contributions, appeals (JLAS-W0250). */
(function () {
  'use strict';

  var A = window.Admin;
  var API = '/api/admin/moderation';

  function link(url, text) {
    var safe = A.safeUrl(url);
    return safe ? '<a class="adm-link" href="' + A.esc(safe) + '" target="_blank" rel="noopener noreferrer">' +
      '<i class="fa-solid fa-up-right-from-square" aria-hidden="true"></i> ' + A.esc(text) + '</a>' : '';
  }

  async function privateFile(path) {
    var headers = window.JlasAuth.authHeaders();
    delete headers['Content-Type'];
    var response = await fetch(path, { headers: headers });
    if (!response.ok) {
      var error = await response.json().catch(function () { return {}; });
      throw new Error(error.message || 'Không tải được file');
    }
    return response.blob();
  }

  async function downloadEvidence(button) {
    button.disabled = true;
    try {
      var blob = await privateFile('/api/appeals/' + button.dataset.evidence + '/file');
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url; a.download = button.dataset.name || 'bang-chung';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    } catch (error) { A.toast(error.message, 'error'); }
    finally { button.disabled = false; }
  }

  // ── Overview ───────────────────────────────────────────────────────────
  A.section('overview', {
    subtitle: 'Các việc đang chờ xử lý trong khu kiểm duyệt.',
    render: async function (root) {
      var o = await A.api.get(API + '/overview');
      var cards = [
        ['reports', 'Báo cáo chờ xử lý', o.pendingReports, 'fa-flag', o.reportsAvailable ? '' : 'Chờ tích hợp báo cáo nội dung'],
        ['queue', 'Hàng đợi AI', o.pendingQueue, 'fa-robot', ''],
        ['appeals', 'Kháng nghị chờ', o.pendingAppeals, 'fa-scale-balanced', ''],
        ['dictionary', 'Đóng góp từ điển chờ', o.pendingContributions, 'fa-book', ''],
        ['denylist', 'Từ khoá cấm đang bật', o.activeDenylistTerms, 'fa-ban', ''],
        ['sanctions', 'Chế tài đang hiệu lực', o.activeSanctions, 'fa-user-lock', '']
      ];
      var items = {};
      A.MODULES.forEach(function (g) { g.items.forEach(function (i) { items[i.key] = i; }); });
      root.innerHTML = '<div class="adm-stats">' + cards.filter(function (c) { return A.can(items[c[0]].roles); })
        .map(function (c) {
          return '<a class="adm-stat" href="' + items[c[0]].href + '">' +
            '<i class="fa-solid ' + c[3] + '" aria-hidden="true"></i>' +
            '<span class="adm-stat__label">' + A.esc(c[1]) + '</span>' +
            '<strong class="adm-stat__value">' + A.esc(c[2]) + '</strong>' +
            (c[4] ? '<span class="adm-muted">' + A.esc(c[4]) + '</span>' : '') + '</a>';
        }).join('') + '</div>';
    }
  });

  // ── Reported content ───────────────────────────────────────────────────
  A.section('reports', {
    subtitle: 'Nội dung đang bị ẩn tạm thời do bị báo cáo. Báo cáo đúng thì gỡ hẳn, báo cáo sai thì khôi phục.',
    render: async function (root, ctx) {
      var data = await A.api.get(API + '/reports');
      if (!data.available) {
        root.innerHTML = A.empty('fa-plug', 'Chức năng báo cáo chưa được kết nối',
          'Màn này sẽ tự hoạt động khi tính năng báo cáo nội dung được tích hợp.');
        return;
      }
      if (!data.items.length) {
        root.innerHTML = A.empty('fa-circle-check', 'Không có báo cáo nào cần xử lý');
        return;
      }
      root.innerHTML = '<div class="adm-cards">' + data.items.map(function (view) {
        var t = view.target;
        return '<article class="adm-card" data-type="' + A.esc(t.targetType) + '" data-id="' + t.targetId + '"' +
            ' data-owner="' + A.esc(t.ownerName || '') + '">' +
          '<div class="adm-card__head">' +
            '<span class="adm-badge">' + A.esc(A.typeLabel(t.targetType)) + '</span>' +
            '<span class="adm-badge adm-badge--warn"><i class="fa-solid fa-eye-slash" aria-hidden="true"></i> Đang ẩn tạm thời</span>' +
            '<span class="adm-badge adm-badge--danger">' + t.reportCount + ' báo cáo</span>' +
          '</div>' +
          '<p class="adm-card__preview jp">' + (t.preview ? A.highlighted(t.preview, view.highlights) : '(Không có nội dung xem trước)') + '</p>' +
          A.flaggedTerms(view.highlights) +
          '<div class="adm-chips">' + (t.reasons || []).map(function (r) {
            return '<span class="adm-chip">' + A.esc(r) + '</span>';
          }).join('') + '</div>' +
          '<p class="adm-muted">Người đăng: ' + A.esc(t.ownerName || ('#' + (t.ownerUserId || '—'))) +
            ' · Bị ẩn lúc ' + A.date(t.hiddenAt) + ' ' + link(t.targetUrl, 'Xem nội dung') + '</p>' +
          '<div class="adm-card__actions">' +
            '<button type="button" class="btn btn--sm adm-btn--danger" data-valid="true"><i class="fa-solid fa-trash" aria-hidden="true"></i> Báo cáo đúng, gỡ nội dung</button>' +
            '<button type="button" class="btn btn--secondary btn--sm" data-valid="false"><i class="fa-solid fa-rotate-left" aria-hidden="true"></i> Báo cáo sai, khôi phục</button>' +
          '</div>' +
        '</article>';
      }).join('') + '</div>';

      root.onclick = async function (event) {
        var button = event.target.closest('[data-valid]');
        if (!button) return;
        var card = button.closest('.adm-card');
        var valid = button.dataset.valid === 'true';
        var canSanction = A.can(A.ADMINS);
        var decision = await A.decide({
          title: valid ? 'Gỡ nội dung vi phạm?' : 'Khôi phục nội dung?',
          message: valid
            ? 'Nội dung sẽ chuyển sang <strong>Đã gỡ</strong> và mọi báo cáo của nó được đóng.' +
              (card.dataset.type === 'VIDEO'
                ? '<br>Link YouTube gốc cũng bị <strong>chặn</strong>: không ai (kể cả người đăng) tạo lại được, và các bản copy của người khác từ cùng link bị gỡ theo.'
                : '')
            : 'Nội dung sẽ <strong>hiển thị lại</strong> và mọi báo cáo của nó được đóng.',
          danger: valid,
          userVisible: valid,
          confirmText: valid ? 'Gỡ nội dung' : 'Khôi phục',
          extraHtml: valid && canSanction
            ? '<div class="field"><label class="field__label" for="admSanction">Chế tài cho người đăng</label>' +
              '<select class="select" id="admSanction">' + A.sanctionOptions(true) + '</select></div>' : '',
          collect: function (modal) {
            var select = modal.querySelector('#admSanction');
            return { sanction: select ? A.parseSanction(select.value) : null };
          }
        });
        if (!decision) return;
        var sanction = decision.extra && decision.extra.sanction;
        try {
          await A.api.post(API + '/reports/resolve', {
            targetType: card.dataset.type, targetId: Number(card.dataset.id), valid: valid,
            reason: decision.reason,
            sanctionType: sanction ? sanction.type : null, durationDays: sanction ? sanction.durationDays : null
          });
          A.toast(valid ? 'Đã gỡ nội dung' : 'Đã khôi phục nội dung', 'success');
          card.remove();
          if (!root.querySelector('.adm-card')) root.innerHTML = A.empty('fa-circle-check', 'Không có báo cáo nào cần xử lý');
          ctx.refreshCounts();
        } catch (error) {
          A.toast(error.message, 'error');
        }
      };
    }
  });

  // ── Shared renderer for review_case queues (AI queue and dictionary) ────
  /** collapsible: only the header shows; a click on it reveals the rest (see bindExpand). */
  function queueCard(item, extra, collapsible) {
    var confidence = Number.isFinite(Number(item.confidence)) ? Math.round(Number(item.confidence) * 100) + '%' : null;
    var summary =
      '<div class="adm-card__head">' +
        '<span class="adm-badge">' + A.esc(A.typeLabel(item.targetType)) + '</span>' +
        (confidence ? '<span class="adm-badge adm-badge--warn">Độ tin cậy AI ' + confidence + '</span>' : '') +
        '<span class="adm-muted">#' + item.id + ' · ' + A.date(item.createdAt) + '</span>' +
      '</div>' +
      (item.titleSnapshot ? '<h3 class="adm-card__title">' + A.esc(item.titleSnapshot) + '</h3>' : '');
    return '<article class="adm-card' + (collapsible ? ' adm-card--collapsible' : '') + '" data-id="' + item.id + '" data-target="' + item.targetId + '">' +
      (collapsible
        ? '<button type="button" class="adm-card__summary" data-expand aria-expanded="false">' + summary +
            '<i class="fa-solid fa-chevron-down adm-card__chevron" aria-hidden="true"></i></button><div class="adm-card__body" hidden>'
        : summary) +
      (item.contentPreview ? '<p class="adm-card__preview jp">' + A.highlighted(item.contentPreview, item.highlights) + '</p>' : '') +
      (item.summary ? '<p class="adm-muted">Lý do gắn cờ: ' + A.esc(item.summary) + '</p>' : '') +
      A.flaggedTerms(item.highlights) +
      (extra || '') +
      '<p class="adm-muted">' + (item.ownerUserId ? 'Người đăng #' + item.ownerUserId + ' ' : '') + (item.targetType === 'USER_AVATAR'
        ? '<button type="button" class="btn btn--secondary btn--sm" data-avatar="' + item.targetId + '">Xem ảnh đại diện</button>'
        : link(item.targetUrl, 'Xem nội dung')) + '</p>' +
      (item.targetType === 'USER_AVATAR' ? '<img class="adm-avatar-preview" alt="Ảnh đại diện đang chờ kiểm duyệt" hidden>' : '') +
      '<div class="adm-card__actions" data-actions></div>' +
      (collapsible ? '</div>' : '') +
    '</article>';
  }

  /** Toggles collapsible cards; onFirstOpen(card) runs once per card (e.g. to load its details). */
  function bindExpand(root, onFirstOpen) {
    root.addEventListener('click', function (event) {
      var toggle = event.target.closest('[data-expand]');
      if (!toggle) return;
      var card = toggle.closest('.adm-card');
      var open = toggle.getAttribute('aria-expanded') !== 'true';
      toggle.setAttribute('aria-expanded', String(open));
      card.querySelector('.adm-card__body').hidden = !open;
      if (open && !card.dataset.loaded) { card.dataset.loaded = '1'; onFirstOpen(card); }
    });
  }

  function bindQueue(root, ctx, labels, emptyText) {
    root.onclick = async function (event) {
      var preview = event.target.closest('[data-avatar]');
      if (preview) {
        preview.disabled = true;
        try {
          var blob = await privateFile(API + '/avatars/' + preview.dataset.avatar + '/file');
          var url = URL.createObjectURL(blob);
          var image = preview.closest('.adm-card').querySelector('.adm-avatar-preview');
          image.onload = image.onerror = function () { URL.revokeObjectURL(url); };
          image.src = url; image.hidden = false;
        } catch (error) { A.toast(error.message, 'error'); }
        finally { preview.disabled = false; }
        return;
      }
      var button = event.target.closest('[data-approve]');
      if (!button) return;
      var card = button.closest('.adm-card');
      var approve = button.dataset.approve === 'true';
      var decision = await A.decide({
        title: approve ? labels.approveTitle : labels.rejectTitle,
        message: approve ? labels.approveText : labels.rejectText,
        danger: !approve,
        userVisible: !approve,
        confirmText: approve ? labels.approve : labels.reject
      });
      if (!decision) return;
      try {
        await A.api.post(API + '/queue/' + card.dataset.id + '/resolve', { approve: approve, reason: decision.reason });
        A.toast(approve ? labels.approveDone : labels.rejectDone, 'success');
        card.remove();
        if (!root.querySelector('.adm-card')) root.innerHTML = A.empty('fa-circle-check', emptyText);
        ctx.refreshCounts();
      } catch (error) {
        A.toast(error.message, 'error');
      }
    };
  }

  function actionButtons(labels) {
    return '<button type="button" class="btn btn--primary btn--sm" data-approve="true"><i class="fa-solid fa-check" aria-hidden="true"></i> ' + A.esc(labels.approve) + '</button>' +
      '<button type="button" class="btn btn--sm adm-btn--danger" data-approve="false"><i class="fa-solid fa-xmark" aria-hidden="true"></i> ' + A.esc(labels.reject) + '</button>';
  }

  // ── AI queue ───────────────────────────────────────────────────────────
  var QUEUE_LABELS = {
    approve: 'Cho phép', reject: 'Ẩn nội dung',
    approveTitle: 'Cho phép nội dung?', rejectTitle: 'Ẩn nội dung?',
    approveText: 'Nội dung được giữ nguyên và hiển thị công khai.',
    rejectText: 'Nội dung sẽ bị ẩn khỏi người học.',
    approveDone: 'Đã cho phép nội dung', rejectDone: 'Đã ẩn nội dung'
  };

  A.section('queue', {
    subtitle: 'Ảnh đại diện và nội dung được AI hoặc từ khoá gắn cờ. Ảnh đại diện cần duyệt được giữ riêng tư; bình luận được xem lại sau khi đăng.',
    render: async function (root, ctx) {
      var items = await A.api.get(API + '/queue');
      if (!items.length) {
        root.innerHTML = A.empty('fa-circle-check', 'Hàng đợi trống');
        return;
      }
      root.innerHTML = '<div class="adm-cards">' + items.map(function (i) { return queueCard(i, '', true); }).join('') + '</div>';
      root.querySelectorAll('[data-actions]').forEach(function (el) { el.innerHTML = actionButtons(QUEUE_LABELS); });
      bindExpand(root, function () {});
      bindQueue(root, ctx, QUEUE_LABELS, 'Hàng đợi trống');
    }
  });

  // ── Dictionary contributions ───────────────────────────────────────────
  var DICT_LABELS = {
    approve: 'Duyệt và công khai', reject: 'Từ chối',
    approveTitle: 'Duyệt đóng góp?', rejectTitle: 'Từ chối đóng góp?',
    approveText: 'Từ vựng sẽ được công khai trong từ điển và người đóng góp được cộng 15 credit.',
    rejectText: 'Từ vựng sẽ tiếp tục bị ẩn. Người đóng góp không được cộng credit.',
    approveDone: 'Đã duyệt, người đóng góp được cộng credit', rejectDone: 'Đã từ chối đóng góp'
  };

  function detailHtml(d) {
    var rows = [['Từ', d.expression, true], ['Cách đọc', d.reading, true], ['Nghĩa', d.meaning],
      ['Từ loại', d.wordType], ['JLPT', d.jlptLevel], ['Ví dụ', d.exampleJa, true], ['Dịch ví dụ', d.exampleVi]];
    return '<dl class="adm-detail">' + rows.filter(function (r) { return r[1]; }).map(function (r) {
      return '<dt>' + r[0] + '</dt><dd' + (r[2] ? ' class="jp"' : '') + '>' + A.esc(r[1]) + '</dd>';
    }).join('') + '</dl>' +
      (d.duplicates.length
        ? '<div class="adm-note adm-note--warn"><strong>Đã có ' + d.duplicates.length + ' từ cùng cách viết trong từ điển:</strong><ul>' +
          d.duplicates.map(function (x) {
            return '<li class="jp">' + A.esc(x.expression) + ' 【' + A.esc(x.reading) + '】 ' + A.esc(x.meaning || '') + '</li>';
          }).join('') + '</ul></div>'
        : '<p class="adm-muted"><i class="fa-solid fa-circle-check" aria-hidden="true"></i> Chưa có từ trùng trong từ điển.</p>');
  }

  A.section('dictionary', {
    subtitle: 'Từ vựng do người học đóng góp. Duyệt thì công khai trong từ điển, từ chối thì giữ ẩn.',
    render: async function (root, ctx) {
      var items = await A.api.get(API + '/dictionary');
      if (!items.length) {
        root.innerHTML = A.empty('fa-circle-check', 'Không có đóng góp nào chờ duyệt');
        return;
      }
      root.innerHTML = '<div class="adm-cards">' + items.map(function (i) {
        return queueCard(i, '<div data-detail>' + A.loading() + '</div>', true);
      }).join('') + '</div>';
      // Details are fetched when a contribution is opened, not for the whole list up front.
      bindExpand(root, async function (card) {
        var box = card.querySelector('[data-detail]');
        try {
          box.innerHTML = detailHtml(await A.api.get(API + '/dictionary/entries/' + card.dataset.target));
        } catch (error) {
          box.innerHTML = '<p class="adm-error">' + A.esc(error.message) + '</p>';
        }
        card.querySelector('[data-actions]').innerHTML = actionButtons(DICT_LABELS);
      });
      bindQueue(root, ctx, DICT_LABELS, 'Không có đóng góp nào chờ duyệt');
    }
  });

  // ── Appeals ────────────────────────────────────────────────────────────
  var APPEAL_STATUS = { OPEN: 'Chờ xử lý', APPROVED: 'Đã chấp nhận', REJECTED: 'Đã từ chối' };

  A.section('appeals', {
    subtitle: 'Đơn kháng nghị chế tài của người dùng. Chấp nhận thì chế tài được gỡ ngay.',
    render: async function (root, ctx) {
      var status = 'OPEN';
      root.innerHTML =
        '<div class="adm-toolbar" data-filter-bar>' +
          '<select class="select" data-status-filter aria-label="Trạng thái">' +
          ['OPEN', 'APPROVED', 'REJECTED', ''].map(function (s) {
            return '<option value="' + s + '"' + (s === status ? ' selected' : '') + '>' + (s ? APPEAL_STATUS[s] : 'Tất cả') + '</option>';
          }).join('') +
          '</select></div><div data-list></div>';
      var list = root.querySelector('[data-list]');
      A.filterPopover(root.querySelector('[data-filter-bar]'));
      bindExpand(root, function () {});
      root.querySelector('[data-status-filter]').addEventListener('change', function (event) {
        status = event.target.value;
        load();
      });

      async function load() {
        list.innerHTML = A.loading();
        try {
          var items = await A.api.get(API + '/appeals' + (status ? '?status=' + status : ''));
          list.innerHTML = items.length ? '<div class="adm-cards">' + items.map(function (a) {
            var open = a.status === 'OPEN';
            return '<article class="adm-card adm-card--collapsible" data-id="' + a.id + '">' +
              '<button type="button" class="adm-card__summary" data-expand aria-expanded="false"><div class="adm-card__head">' +
                '<span class="adm-badge' + (open ? ' adm-badge--warn' : a.status === 'APPROVED' ? ' adm-badge--ok' : '') + '">' +
                  A.esc(APPEAL_STATUS[a.status] || a.status) + '</span>' +
                '<span class="adm-badge">' + A.esc(a.sanctionType === 'BAN' ? 'Khoá tài khoản' : 'Cấm bình luận') + '</span>' +
                '<span class="adm-muted">Đơn #' + a.id + ' · Người dùng #' + (a.requesterUserId || '—') + ' · ' + A.date(a.createdAt) + '</span>' +
              '</div><i class="fa-solid fa-chevron-down adm-card__chevron" aria-hidden="true"></i></button>' +
              '<div class="adm-card__body" hidden>' +
              '<p class="adm-card__preview">' + A.esc(a.reason) + '</p>' +
              (a.evidenceUrl ? '<p>' + link(a.evidenceUrl, 'Bằng chứng đính kèm') + '</p>' : '') +
              (a.evidenceFile ? '<p><button type="button" class="btn btn--secondary btn--sm" data-evidence="' + a.id +
                '" data-name="' + A.esc(a.evidenceFile.name) + '"><i class="fa-solid fa-download" aria-hidden="true"></i> ' +
                A.esc(a.evidenceFile.name) + ' (' + Math.ceil(a.evidenceFile.size / 1024) + ' KB)</button></p>' : '') +
              (a.resolutionNote ? '<p class="adm-muted">Kết quả: ' + A.esc(a.resolutionNote) + ' · ' + A.date(a.resolvedAt) + '</p>' : '') +
              (open ? '<div class="adm-card__actions">' +
                '<button type="button" class="btn btn--primary btn--sm" data-approve="true"><i class="fa-solid fa-check" aria-hidden="true"></i> Chấp nhận, gỡ chế tài</button>' +
                '<button type="button" class="btn btn--secondary btn--sm" data-approve="false"><i class="fa-solid fa-xmark" aria-hidden="true"></i> Từ chối</button>' +
              '</div>' : '') +
              '</div>' +
            '</article>';
          }).join('') + '</div>' : A.empty('fa-inbox', 'Không có đơn nào');
        } catch (error) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được danh sách', error.message);
        }
      }

      root.onclick = async function (event) {
        var evidence = event.target.closest('[data-evidence]');
        if (evidence) { await downloadEvidence(evidence); return; }
        var button = event.target.closest('[data-approve]');
        if (!button) return;
        var id = button.closest('.adm-card').dataset.id;
        var approve = button.dataset.approve === 'true';
        var decision = await A.decide({
          title: approve ? 'Chấp nhận kháng nghị?' : 'Từ chối kháng nghị?',
          message: approve ? 'Chế tài của người dùng sẽ được gỡ ngay.' : 'Chế tài được giữ nguyên.',
          confirmText: approve ? 'Chấp nhận' : 'Từ chối',
          userVisible: true,
          maxLength: 500
        });
        if (!decision) return;
        try {
          await A.api.post(API + '/appeals/' + id + '/resolve', { approve: approve, note: decision.reason });
          A.toast(approve ? 'Đã chấp nhận, chế tài đã được gỡ' : 'Đã từ chối kháng nghị', 'success');
          load();
          ctx.refreshCounts();
        } catch (error) {
          A.toast(error.message, 'error');
        }
      };
      await load();
    }
  });
})();
