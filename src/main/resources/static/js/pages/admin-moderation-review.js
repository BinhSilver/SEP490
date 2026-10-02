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

  // ── Overview ───────────────────────────────────────────────────────────
  A.section('overview', {
    subtitle: 'Các việc đang chờ xử lý trong khu kiểm duyệt.',
    render: async function (root) {
      var o = await A.api.get(API + '/overview');
      var cards = [
        ['reports', 'Báo cáo chờ xử lý', o.pendingReports, 'fa-flag', o.reportsAvailable ? '' : 'Chờ tích hợp JLAS2-37'],
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
          'Màn này sẽ tự hoạt động khi tính năng báo cáo nội dung (JLAS2-37) được tích hợp.');
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
            ? 'Nội dung sẽ chuyển sang <strong>Đã gỡ</strong> và mọi báo cáo của nó được đóng.'
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
  function queueCard(item, extra) {
    var confidence = Number.isFinite(Number(item.confidence)) ? Math.round(Number(item.confidence) * 100) + '%' : null;
    return '<article class="adm-card" data-id="' + item.id + '" data-target="' + item.targetId + '">' +
      '<div class="adm-card__head">' +
        '<span class="adm-badge">' + A.esc(A.typeLabel(item.targetType)) + '</span>' +
        (confidence ? '<span class="adm-badge adm-badge--warn">Độ tin cậy AI ' + confidence + '</span>' : '') +
        '<span class="adm-muted">#' + item.id + ' · ' + A.date(item.createdAt) + '</span>' +
      '</div>' +
      (item.titleSnapshot ? '<h3 class="adm-card__title">' + A.esc(item.titleSnapshot) + '</h3>' : '') +
      (item.contentPreview ? '<p class="adm-card__preview jp">' + A.highlighted(item.contentPreview, item.highlights) + '</p>' : '') +
      (item.summary ? '<p class="adm-muted">Lý do gắn cờ: ' + A.esc(item.summary) + '</p>' : '') +
      A.flaggedTerms(item.highlights) +
      (extra || '') +
      '<p class="adm-muted">' + (item.ownerUserId ? 'Người đăng #' + item.ownerUserId + ' ' : '') + link(item.targetUrl, 'Xem nội dung') + '</p>' +
      '<div class="adm-card__actions" data-actions></div>' +
    '</article>';
  }

  function bindQueue(root, ctx, labels, emptyText) {
    root.onclick = async function (event) {
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
    subtitle: 'Nội dung được AI hoặc từ khoá gắn cờ. Bình luận vẫn hiển thị ngay, đây là bước xem lại sau.',
    render: async function (root, ctx) {
      var items = await A.api.get(API + '/queue');
      if (!items.length) {
        root.innerHTML = A.empty('fa-circle-check', 'Hàng đợi trống');
        return;
      }
      root.innerHTML = '<div class="adm-cards">' + items.map(function (i) { return queueCard(i); }).join('') + '</div>';
      root.querySelectorAll('[data-actions]').forEach(function (el) { el.innerHTML = actionButtons(QUEUE_LABELS); });
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
        return queueCard(i, '<div data-detail>' + A.loading() + '</div>');
      }).join('') + '</div>';
      root.querySelectorAll('.adm-card').forEach(async function (card) {
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
        '<div class="adm-tabs" role="tablist" aria-label="Lọc trạng thái">' +
          ['OPEN', 'APPROVED', 'REJECTED', ''].map(function (s) {
            return '<button type="button" class="adm-tab" role="tab" data-status="' + s + '" aria-selected="' + (s === status) + '">' +
              (s ? APPEAL_STATUS[s] : 'Tất cả') + '</button>';
          }).join('') +
        '</div><div data-list></div>';
      var list = root.querySelector('[data-list]');

      async function load() {
        list.innerHTML = A.loading();
        try {
          var items = await A.api.get(API + '/appeals' + (status ? '?status=' + status : ''));
          list.innerHTML = items.length ? '<div class="adm-cards">' + items.map(function (a) {
            var open = a.status === 'OPEN';
            return '<article class="adm-card" data-id="' + a.id + '">' +
              '<div class="adm-card__head">' +
                '<span class="adm-badge' + (open ? ' adm-badge--warn' : a.status === 'APPROVED' ? ' adm-badge--ok' : '') + '">' +
                  A.esc(APPEAL_STATUS[a.status] || a.status) + '</span>' +
                '<span class="adm-badge">' + A.esc(a.sanctionType === 'BAN' ? 'Khoá tài khoản' : 'Cấm bình luận') + '</span>' +
                '<span class="adm-muted">Đơn #' + a.id + ' · Người dùng #' + (a.requesterUserId || '—') + ' · ' + A.date(a.createdAt) + '</span>' +
              '</div>' +
              '<p class="adm-card__preview">' + A.esc(a.reason) + '</p>' +
              (a.evidenceUrl ? '<p>' + link(a.evidenceUrl, 'Bằng chứng đính kèm') + '</p>' : '') +
              (a.resolutionNote ? '<p class="adm-muted">Kết quả: ' + A.esc(a.resolutionNote) + ' · ' + A.date(a.resolvedAt) + '</p>' : '') +
              (open ? '<div class="adm-card__actions">' +
                '<button type="button" class="btn btn--primary btn--sm" data-approve="true"><i class="fa-solid fa-check" aria-hidden="true"></i> Chấp nhận, gỡ chế tài</button>' +
                '<button type="button" class="btn btn--secondary btn--sm" data-approve="false"><i class="fa-solid fa-xmark" aria-hidden="true"></i> Từ chối</button>' +
              '</div>' : '') +
            '</article>';
          }).join('') + '</div>' : A.empty('fa-inbox', 'Không có đơn nào');
        } catch (error) {
          list.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được danh sách', error.message);
        }
      }

      root.onclick = async function (event) {
        var tab = event.target.closest('.adm-tab');
        if (tab) {
          status = tab.dataset.status;
          root.querySelectorAll('.adm-tab').forEach(function (t) { t.setAttribute('aria-selected', String(t === tab)); });
          load();
          return;
        }
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
