/* Sanction appeal page: active sanctions, appeal form, own appeal history (JLAS-W0250). */
(function () {
  'use strict';

  var REASON_MIN = 20;
  var STATUS = {
    OPEN: ['Chờ xử lý', 'is-open', 'fa-hourglass-half'],
    APPROVED: ['Đã chấp nhận', 'is-ok', 'fa-circle-check'],
    REJECTED: ['Đã từ chối', 'is-no', 'fa-circle-xmark']
  };

  var $ = function (id) { return document.getElementById(id); };
  var form = $('aplForm');
  var select = $('aplSanction');
  var reason = $('aplReason');
  var evidence = $('aplEvidence');
  var submit = $('aplSubmit');
  var touched = { reason: false, evidence: false };

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function date(value) {
    if (!value) return '';
    var d = new Date(value);
    return isNaN(d.getTime()) ? '' : d.toLocaleString('vi-VN', {
      hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', year: 'numeric'
    });
  }

  function typeName(type) { return type === 'BAN' ? 'Khoá tài khoản' : 'Cấm bình luận'; }
  function until(endsAt) { return endsAt ? 'đến ' + date(endsAt) : 'vĩnh viễn'; }
  function isHttpUrl(value) { return /^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(value); }

  // ── Validation while typing ───────────────────────────────────────────
  function validate() {
    var text = reason.value.trim();
    var link = evidence.value.trim();
    var reasonError = !text ? 'Vui lòng nhập lý do.'
      : text.length < REASON_MIN ? 'Lý do cần ít nhất ' + REASON_MIN + ' ký tự (còn thiếu ' + (REASON_MIN - text.length) + ').' : '';
    var evidenceError = link && !isHttpUrl(link) ? 'Liên kết phải bắt đầu bằng http:// hoặc https://' : '';
    $('aplReasonCount').textContent = reason.value.length + '/1000';
    $('aplReasonError').textContent = touched.reason ? reasonError : '';
    $('aplEvidenceError').textContent = touched.evidence ? evidenceError : '';
    reason.setAttribute('aria-invalid', String(touched.reason && !!reasonError));
    evidence.setAttribute('aria-invalid', String(touched.evidence && !!evidenceError));
    submit.disabled = !!(reasonError || evidenceError) || !select.value;
    return !reasonError && !evidenceError;
  }

  reason.addEventListener('input', function () { touched.reason = true; validate(); });
  evidence.addEventListener('input', function () { touched.evidence = true; validate(); });
  evidence.addEventListener('blur', function () { touched.evidence = true; validate(); });

  // ── Rendering ─────────────────────────────────────────────────────────
  function renderSanctions(list) {
    var box = $('aplSanctions');
    if (!list.length) {
      box.innerHTML = '<div class="apl-empty"><i class="fa-solid fa-shield-heart" aria-hidden="true"></i>' +
        '<strong>Tài khoản của bạn không có chế tài nào</strong>' +
        '<span>Bạn đang dùng JLAS bình thường.</span><a class="btn btn--secondary btn--sm" href="/">Về trang chủ</a></div>';
      return;
    }
    box.innerHTML = list.map(function (s) {
      var ban = s.type === 'BAN';
      return '<article class="apl-sanction ' + (ban ? 'is-ban' : 'is-mute') + '">' +
        '<div class="apl-sanction__icon"><i class="fa-solid ' + (ban ? 'fa-user-lock' : 'fa-comment-slash') + '" aria-hidden="true"></i></div>' +
        '<div class="apl-sanction__body">' +
          '<strong>' + typeName(s.type) + ' ' + esc(until(s.endsAt)) + '</strong>' +
          '<p>' + (ban
            ? 'Bạn chỉ có thể xem thông báo và gửi kháng nghị cho tới khi hết hạn hoặc được gỡ.'
            : 'Bạn không thể bình luận hay đăng nội dung công khai. Việc học và bộ thẻ riêng tư vẫn dùng bình thường.') + '</p>' +
          (s.reason ? '<p><span class="apl-muted">Lý do:</span> ' + esc(s.reason) + '</p>' : '') +
          '<span class="apl-muted">Bắt đầu ' + esc(date(s.startsAt)) + '</span>' +
        '</div></article>';
    }).join('');
  }

  function renderForm(sanctions, appeals) {
    var card = $('aplFormCard');
    card.hidden = !sanctions.length;
    if (!sanctions.length) return;
    var pending = appeals.filter(function (a) { return a.status === 'OPEN'; })[0];
    $('aplPendingNote').hidden = !pending;
    form.hidden = !!pending;
    if (pending) {
      $('aplPendingNote').innerHTML = '<i class="fa-solid fa-hourglass-half" aria-hidden="true"></i> ' +
        'Đơn #' + pending.id + ' của bạn đang chờ xử lý. Mỗi lần chỉ gửi được một đơn; kết quả sẽ được gửi qua thông báo.';
      return;
    }
    select.innerHTML = sanctions.map(function (s) {
      return '<option value="' + s.id + '">' + esc(typeName(s.type) + ' ' + until(s.endsAt)) + '</option>';
    }).join('');
    select.disabled = sanctions.length === 1;
    validate();
  }

  function renderHistory(list) {
    var box = $('aplHistory');
    if (!list.length) {
      box.innerHTML = '<p class="apl-muted apl-center">Bạn chưa gửi đơn kháng nghị nào.</p>';
      return;
    }
    box.innerHTML = '<ul class="apl-list">' + list.map(function (a) {
      var st = STATUS[a.status] || [a.status, '', 'fa-circle'];
      var link = a.evidenceUrl && isHttpUrl(a.evidenceUrl)
        ? '<a class="apl-link" href="' + esc(a.evidenceUrl) + '" target="_blank" rel="noopener noreferrer">' +
          '<i class="fa-solid fa-paperclip" aria-hidden="true"></i> Bằng chứng đính kèm</a>' : '';
      return '<li class="apl-appeal">' +
        '<div class="apl-appeal__head">' +
          '<span class="apl-pill ' + st[1] + '"><i class="fa-solid ' + st[2] + '" aria-hidden="true"></i> ' + st[0] + '</span>' +
          '<strong>Đơn #' + a.id + '</strong>' +
          '<span class="apl-muted">' + esc(typeName(a.sanctionType)) + ' · gửi lúc ' + esc(date(a.createdAt)) + '</span>' +
        '</div>' +
        '<p>' + esc(a.reason) + '</p>' + link +
        (a.resolutionNote ? '<div class="apl-reply"><span class="apl-muted">Phản hồi của kiểm duyệt viên' +
          (a.resolvedAt ? ' · ' + esc(date(a.resolvedAt)) : '') + '</span><p>' + esc(a.resolutionNote) + '</p></div>' : '') +
      '</li>';
    }).join('') + '</ul>';
  }

  async function load() {
    try {
      var results = await Promise.all([FlashApi.get('/api/appeals/sanctions'), FlashApi.get('/api/appeals/me')]);
      var sanctions = Array.isArray(results[0]) ? results[0] : [];
      var appeals = Array.isArray(results[1]) ? results[1] : [];
      renderSanctions(sanctions);
      renderForm(sanctions, appeals);
      renderHistory(appeals);
    } catch (error) {
      $('aplSanctions').innerHTML = '<p class="apl-error">Không tải được dữ liệu. Vui lòng tải lại trang.</p>';
      $('aplHistory').innerHTML = '';
    }
  }

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    touched.reason = touched.evidence = true;
    if (!validate()) return;
    submit.disabled = true;
    $('aplSubmitError').textContent = '';
    try {
      await FlashApi.post('/api/appeals', {
        sanctionId: Number(select.value),
        reason: reason.value.trim(),
        evidenceUrl: evidence.value.trim() || null
      });
      form.reset();
      touched.reason = touched.evidence = false;
      await load();
      // The form is replaced by the "pending" note; move focus there so it is announced.
      $('aplPendingNote').setAttribute('tabindex', '-1');
      $('aplPendingNote').focus();
    } catch (error) {
      $('aplSubmitError').textContent = (error && error.message) || 'Không gửi được đơn, vui lòng thử lại.';
      validate();
    }
  });

  if (window.JlasAuth && !JlasAuth.requireLogin()) return;
  load();
})();
