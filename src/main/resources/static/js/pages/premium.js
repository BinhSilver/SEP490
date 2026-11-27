/* Premium pricing, PayOS checkout and billing history (JLAS2-49). The server decides every payment result. */
(function () {
  'use strict';

  var root = document.getElementById('premiumApp');
  var page = root.dataset.page;
  var API = '/api/premium';
  var POLL_MS = 3000;
  var STATUS = {
    PENDING: ['Đang chờ thanh toán', 'warn'], PAID: ['Đã thanh toán', 'ok'], FAILED: ['Thất bại', 'bad'],
    CANCELLED: ['Đã huỷ', ''], EXPIRED: ['Hết hạn', ''], REFUNDED: ['Đã hoàn tiền', '']
  };

  function esc(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function vnd(amount) { return Number(amount || 0).toLocaleString('vi-VN') + 'đ'; }
  function date(value, withTime) {
    if (!value) return '—';
    var d = new Date(value);
    return withTime ? d.toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', year: 'numeric' })
      : d.toLocaleDateString('vi-VN');
  }
  function minutes(value) { return Number(value).toLocaleString('vi-VN', { maximumFractionDigits: 1 }); }
  function badge(status) {
    var s = STATUS[status] || [status, ''];
    return '<span class="pm-badge jl-badge' + (s[1] ? ' pm-badge--' + s[1] : '') + '">' + esc(s[0]) + '</span>';
  }
  function loggedIn() { return !!(window.JlasAuth && JlasAuth.isLoggedIn()); }

  async function api(method, url, body) {
    var headers = loggedIn() ? JlasAuth.authHeaders() : {};
    headers['Content-Type'] = 'application/json';
    var response = await fetch(url, { method: method, headers: headers, body: body ? JSON.stringify(body) : undefined });
    var data = null;
    try { data = await response.json(); } catch (ignored) { /* empty body */ }
    if (response.status === 401) {
      location.assign('/login?redirect=' + encodeURIComponent(location.pathname));
      throw new Error('Vui lòng đăng nhập.');
    }
    if (!response.ok) {
      var errors = data && data.errors;
      var first = errors && Object.keys(errors).length ? errors[Object.keys(errors)[0]] : null;
      throw new Error(first || (data && data.message) || 'Có lỗi xảy ra, vui lòng thử lại.');
    }
    return data ? data.data : null;
  }

  function fail(message) {
    root.innerHTML = '<div class="pm-card pm-result pm-result--bad"><i class="fa-solid fa-circle-exclamation pm-result__icon" aria-hidden="true"></i>' +
      '<h1>Không tải được trang</h1><p class="pm-muted">' + esc(message) + '</p>' +
      '<div class="pm-actions"><a class="btn btn--secondary" href="/premium">Về bảng giá</a></div></div>';
  }

  // ── Pricing ────────────────────────────────────────────────────────────
  async function pricing() {
    var data = await api('GET', API + '/plans');
    var monthly = data.plans.filter(function (p) { return p.durationDays < 365; })[0];
    var yearly = data.plans.filter(function (p) { return p.durationDays >= 365; })[0];
    var period = yearly && !monthly ? 'year' : 'month';
    var current = data.current;
    var q = data.aiCall;

    function premiumPlan() { return period === 'year' ? yearly : monthly; }

    function yearlyOriginal() {
      return monthly && yearly ? Number(monthly.price) * Math.round(yearly.durationDays / monthly.durationDays) : 0;
    }

    function savings() {
      if (!monthly || !yearly) return '';
      var saved = yearlyOriginal() - Number(yearly.price);
      return saved > 0 ? '<span class="pm-save">Tiết kiệm ' + vnd(saved) + '</span>' : '';
    }

    function cta(plan) {
      if (!data.paymentsEnabled) return '<button class="btn btn--primary" type="button" disabled>Thanh toán đang bảo trì</button>';
      if (!loggedIn()) return '<a class="btn btn--primary" href="/login?redirect=%2Fpremium">Đăng nhập để nâng cấp</a>';
      return '<button class="btn btn--primary" type="button" data-buy="' + plan.id + '">' +
        (current && current.premiumActive ? 'Gia hạn thêm' : 'Nâng cấp gói Cao cấp') + '</button>';
    }

    function render() {
      var plan = premiumPlan();
      var freeDaily = minutes(q.freeDailyMinutes) + ' phút/ngày';
      var premiumMonthly = Number(q.premiumMonthlyMinutes) > 0 ? 'tối đa ' + minutes(q.premiumMonthlyMinutes) + ' phút/tháng'
        : 'không giới hạn số phút trong tháng';
      root.innerHTML =
        '<header class="pm-hero"><h1>Nâng cấp gói Cao cấp</h1>' +
          '<p>Luyện hội thoại tiếng Nhật với AI nhiều hơn mỗi ngày.</p>' +
          (monthly && yearly ? '<div class="pm-toggle" role="group" aria-label="Chu kỳ thanh toán">' +
            '<button type="button" data-period="month" aria-pressed="' + (period === 'month') + '">Theo tháng</button>' +
            '<button type="button" data-period="year" aria-pressed="' + (period === 'year') + '">Theo năm' + savings() + '</button></div>' : '') +
        '</header>' +
        '<div class="pm-plans">' +
          '<article class="pm-plan">' +
            '<div><h2>Miễn phí</h2></div>' +
            '<div class="pm-price"><strong>0đ</strong></div>' +
            '<ul class="pm-features">' +
              '<li><i class="fa-solid fa-check" aria-hidden="true"></i><span>Gọi AI luyện hội thoại ' + freeDaily +
                ', tối đa ' + minutes(q.freeMonthlyMinutes) + ' phút/tháng</span></li>' +
              '<li><i class="fa-solid fa-check" aria-hidden="true"></i><span>Toàn bộ các tính năng học hiện có</span></li>' +
            '</ul>' +
            (current && !current.premiumActive ? '<button class="btn btn--secondary" type="button" disabled>Gói hiện tại của bạn</button>' : '') +
          '</article>' +
          (plan ? '<article class="pm-plan pm-plan--featured"><span class="pm-plan__tag">Khuyên dùng</span>' +
            '<div><h2>Gói Cao cấp</h2>' + (plan.description ? '<p class="pm-plan__desc">' + esc(plan.description) + '</p>' : '') + '</div>' +
            '<div class="pm-price">' + (period === 'year' && yearlyOriginal() > Number(plan.price)
              ? '<s class="pm-price__original">' + vnd(yearlyOriginal()) + '</s>' : '') +
              '<strong class="jl-num">' + vnd(plan.price) + '</strong><span>/ ' + (period === 'year' ? 'năm' : plan.durationDays + ' ngày') + '</span>' +
              (period === 'year' ? '<span>(≈ ' + vnd(Math.round(Number(plan.price) / 12)) + '/tháng)</span>' : '') + '</div>' +
            '<ul class="pm-features">' +
              '<li><i class="fa-solid fa-check" aria-hidden="true"></i><span>Gọi AI luyện hội thoại ' + minutes(q.premiumDailyMinutes) +
                ' phút/ngày, ' + premiumMonthly + '</span></li>' +
              '<li><i class="fa-solid fa-check" aria-hidden="true"></i><span>Mọi tính năng của gói Miễn phí</span></li>' +
              '<li><i class="fa-solid fa-check" aria-hidden="true"></i><span>Thanh toán một lần cho ' + plan.durationDays + ' ngày, không tự gia hạn</span></li>' +
            '</ul>' +
            (current && current.premiumActive ? '<p class="pm-note">Gói Cao cấp của bạn có hiệu lực đến ' + date(current.expiresAt) +
              '. Mua thêm sẽ cộng ' + plan.durationDays + ' ngày vào hạn này.</p>' : '') +
            '<p class="pm-error" data-error aria-live="assertive"></p>' + cta(plan) +
          '</article>' : '<article class="pm-plan"><h2>Gói Cao cấp</h2><p class="pm-muted">Hiện chưa có gói Cao cấp nào đang bán.</p></article>') +
        '</div>' +
        '<section class="pm-faq" aria-label="Câu hỏi thường gặp"><h2>Câu hỏi thường gặp</h2>' +
          '<details><summary>Thanh toán bằng cách nào?</summary><p>Quét mã QR bằng ứng dụng ngân hàng bất kỳ (qua cổng PayOS). ' +
            'Gói Cao cấp được kích hoạt ngay khi hệ thống xác nhận giao dịch với cổng thanh toán.</p></details>' +
          '<details><summary>Gói có tự động gia hạn không?</summary><p>Không. Mỗi lần mua là một khoản thanh toán duy nhất; ' +
            'hết hạn bạn có thể mua tiếp.</p></details>' +
          '<details><summary>Đang dùng gói Cao cấp thì mua thêm thế nào?</summary><p>Số ngày của gói mới được cộng dồn vào hạn gói Cao cấp hiện tại.</p></details>' +
        '</section>' +
        (loggedIn() ? '<p class="pm-note" style="text-align:center;margin-top:20px"><a href="/premium/billing">Xem gói của bạn và lịch sử thanh toán</a></p>' : '');
    }

    root.addEventListener('click', async function (event) {
      var toggle = event.target.closest('[data-period]');
      if (toggle) { period = toggle.dataset.period; render(); return; }
      var buy = event.target.closest('[data-buy]');
      if (!buy) return;
      buy.disabled = true;
      buy.textContent = 'Đang tạo đơn…';
      try {
        var order = await api('POST', API + '/checkout', { planId: Number(buy.dataset.buy), couponCode: null });
        location.assign('/premium/checkout/' + order.orderCode);
      } catch (e) {
        root.querySelector('[data-error]').textContent = e.message;
        buy.disabled = false;
        buy.textContent = 'Thử lại';
      }
    });
    render();
  }

  // ── Checkout ───────────────────────────────────────────────────────────
  async function checkout() {
    var code = root.dataset.order;
    var timer = null;
    var countdown = null;
    var qrRendered = false;
    var busy = false;

    // PayOS sends the buyer back with ?cancel=1 after cancelling there: confirm it with the server.
    var order = new URLSearchParams(location.search).get('cancel') === '1'
      ? await api('POST', API + '/orders/' + code + '/cancel') : await api('GET', API + '/orders/' + code);

    function summary(o) {
      return '<section class="pm-card"><h2>Đơn hàng</h2><dl class="pm-summary">' +
        '<div><dt>Gói</dt><dd>' + (o.productType === 'CREDIT' ? 'CP' : 'Cao cấp') + ' · ' + esc(o.planName) + '</dd></div>' +
        (o.productType === 'CREDIT' ? '<div><dt>Số CP</dt><dd>' + o.creditAmount + ' CP</dd></div>'
          : '<div><dt>Thời hạn</dt><dd>' + o.durationDays + ' ngày</dd></div>') +
        '<div><dt>Mã đơn</dt><dd>' + esc(o.orderCode) + '</dd></div>' +
        '<div><dt>Trạng thái</dt><dd>' + badge(o.status) + '</dd></div>' +
        (Number(o.discountAmount) ? '<div><dt>Giá gốc</dt><dd>' + vnd(o.originalAmount) + '</dd></div>' +
          '<div><dt>Giảm giá ' + esc(o.couponCode) + '</dt><dd>−' + vnd(o.discountAmount) + '</dd></div>' : '') +
        '<div class="pm-total"><dt>Tổng thanh toán</dt><dd>' + vnd(o.amount) + '</dd></div>' +
      '</dl></section>';
    }

    function remaining(o) {
      var ms = new Date(o.expiresAt).getTime() - Date.now();
      if (ms <= 0) return 'Link đã hết hạn';
      var m = Math.floor(ms / 60000), s = Math.floor(ms % 60000 / 1000);
      return 'Link hết hạn sau ' + m + ':' + (s < 10 ? '0' : '') + s;
    }

    function render(o, resultError) {
      if (o.status === 'PAID') {
        stop();
        root.innerHTML = '<div class="pm-card pm-result pm-result--ok"><i class="fa-solid fa-circle-check pm-result__icon" aria-hidden="true"></i>' +
          '<h1>Thanh toán thành công</h1><p>' + (o.productType === 'CREDIT'
            ? 'Đã cộng <strong>' + o.creditAmount + ' CP</strong> vào tài khoản.'
            : 'Gói Cao cấp của bạn có hiệu lực đến <strong>' + date(o.premiumUntil, true) + '</strong>.') + '</p>' +
          '<div class="pm-actions"><a class="btn btn--primary" href="/ai-call">Bắt đầu luyện hội thoại AI</a>' +
          '<a class="btn btn--secondary" href="/premium/billing">Xem lịch sử thanh toán</a></div></div>';
        if (window.JlasAuth && JlasAuth.refreshUser) JlasAuth.refreshUser();
        return;
      }
      if (o.status !== 'PENDING') {
        stop();
        var text = { CANCELLED: 'Đơn đã được huỷ, bạn chưa bị trừ tiền.', EXPIRED: 'Link thanh toán đã hết hạn, bạn chưa bị trừ tiền.',
          FAILED: 'Giao dịch không thành công. Nếu tài khoản đã bị trừ tiền, hãy liên hệ hỗ trợ kèm mã đơn ' + o.orderCode + '.' };
        root.innerHTML = '<div class="pm-checkout">' + summary(o) +
          '<section class="pm-card pm-result pm-result--bad"><i class="fa-solid fa-circle-xmark pm-result__icon" aria-hidden="true"></i>' +
          '<h1>' + esc((STATUS[o.status] || [o.status])[0]) + '</h1><p class="pm-muted">' + esc(text[o.status] || '') + '</p>' +
          (resultError ? '<p class="pm-error">' + esc(resultError) + '</p>' : '') +
          '<div class="pm-actions"><a class="btn btn--primary" href="' + (o.productType === 'CREDIT' ? '/credits' : '/premium') + '">Chọn lại gói</a></div></section></div>';
        return;
      }
      if (!qrRendered) {
        root.innerHTML = '<div class="pm-checkout">' + '<div data-summary>' + summary(o) + '</div>' +
          '<section class="pm-card pm-pay"><h2>Quét mã để thanh toán</h2>' +
            '<div class="pm-qr" data-qr aria-label="Mã QR thanh toán"></div>' +
            '<p class="pm-muted">Mở ứng dụng ngân hàng, quét mã và giữ nguyên số tiền, nội dung chuyển khoản.</p>' +
            '<p><span class="pm-status"><i class="fa-solid fa-circle-notch fa-spin" aria-hidden="true"></i> Đang chờ xác nhận thanh toán</span></p>' +
            '<p class="pm-muted" data-countdown></p>' +
            (!Number(o.discountAmount) ? '<div class="pm-coupon"><label for="checkoutCoupon">Mã giảm giá</label>' +
              '<div class="pm-coupon__controls"><input id="checkoutCoupon" class="input" data-coupon maxlength="40" autocomplete="off">' +
              '<button class="btn btn--secondary" type="button" data-apply-coupon>Áp dụng</button></div></div>' : '') +
            '<div class="pm-actions">' +
              '<button class="btn btn--secondary" type="button" data-cancel>Huỷ đơn</button></div>' +
            '<p class="pm-error" data-error aria-live="assertive"></p>' +
          '</section></div>';
        var box = root.querySelector('[data-qr]');
        try {
          if (!window.QRCode || !o.qrCode) throw new Error('QR unavailable');
          new window.QRCode(box, { text: o.qrCode, width: 232, height: 232, correctLevel: window.QRCode.CorrectLevel.M });
        } catch (ignored) {
          box.outerHTML = '<p class="pm-muted">Không hiển thị được mã QR.' +
            (o.checkoutUrl ? ' <a href="' + esc(o.checkoutUrl) + '" target="_blank" rel="noopener">Mở trang thanh toán PayOS</a>.' : '') + '</p>';
        }
        qrRendered = true;
      } else {
        root.querySelector('[data-summary]').innerHTML = summary(o);
      }
      var cd = root.querySelector('[data-countdown]');
      clearInterval(countdown);
      countdown = setInterval(function () { cd.textContent = remaining(o); }, 1000);
      cd.textContent = remaining(o);
    }

    function stop() { clearTimeout(timer); clearInterval(countdown); }

    async function poll() {
      try {
        order = await api('GET', API + '/orders/' + code);
        if (busy) return;
        render(order);
      } catch (e) { /* transient: keep polling */ }
      if (order.status === 'PENDING') timer = setTimeout(poll, POLL_MS);
    }

    root.addEventListener('click', async function (event) {
      var apply = event.target.closest('[data-apply-coupon]');
      if (apply) {
        if (busy) return;
        var coupon = root.querySelector('[data-coupon]');
        var error = root.querySelector('[data-error]');
        var couponCode = coupon.value.trim();
        error.textContent = '';
        if (!couponCode) { error.textContent = 'Vui lòng nhập mã giảm giá.'; coupon.focus(); return; }
        busy = true;
        apply.disabled = true;
        coupon.disabled = true;
        apply.textContent = 'Đang áp dụng…';
        try {
          stop();
          order = await api('POST', API + '/orders/' + code + '/coupon', { code: couponCode });
          if (String(order.orderCode) !== String(code)) {
            location.replace('/premium/checkout/' + order.orderCode);
            return;
          }
          qrRendered = false;
          render(order);
          if (order.status === 'PENDING') timer = setTimeout(poll, POLL_MS);
        } catch (e) {
          try {
            order = await api('GET', API + '/orders/' + code);
          } catch (ignored) { /* Keep the existing checkout visible if status cannot be fetched. */ }
          if (order.status !== 'PENDING') {
            render(order, e.message);
          } else {
            error.textContent = e.message;
            coupon.disabled = false;
            apply.disabled = false;
            apply.textContent = 'Áp dụng';
            timer = setTimeout(poll, POLL_MS);
          }
        } finally { busy = false; }
        return;
      }
      var cancel = event.target.closest('[data-cancel]');
      if (!cancel) return;
      if (busy) return;
      if (!confirm('Huỷ đơn thanh toán này?')) return;
      cancel.disabled = true;
      try {
        stop();
        order = await api('POST', API + '/orders/' + code + '/cancel');
        render(order);
        if (order.status === 'PENDING') timer = setTimeout(poll, POLL_MS);
      } catch (e) {
        root.querySelector('[data-error]').textContent = e.message;
        cancel.disabled = false;
        timer = setTimeout(poll, POLL_MS);
      }
    });
    render(order);
    if (order.status === 'PENDING') timer = setTimeout(poll, POLL_MS);
  }

  // ── Billing ────────────────────────────────────────────────────────────
  async function billing() {
    var data = await Promise.all([api('GET', API + '/plans'), api('GET', API + '/orders')]);
    var current = data[0].current;
    var orders = data[1];
    root.innerHTML = '<div class="pm-stack">' +
      '<header class="pm-hero" style="text-align:left;margin:0"><h1>Gói &amp; thanh toán</h1></header>' +
      '<section class="pm-card pm-current">' +
        (current && current.premiumActive
          ? '<div><h2 style="margin:0 0 6px">Cao cấp · ' + esc(current.planName) + '</h2><p class="pm-muted" style="margin:0">Có hiệu lực đến ' +
            date(current.expiresAt, true) + '. Không tự gia hạn.</p></div><a class="btn btn--primary" href="/premium">Gia hạn thêm</a>'
          : '<div><h2 style="margin:0 0 6px">Miễn phí</h2><p class="pm-muted" style="margin:0">Nâng cấp để luyện hội thoại AI nhiều hơn mỗi ngày.</p></div>' +
            '<a class="btn btn--primary" href="/premium">Nâng cấp gói Cao cấp</a>') +
      '</section>' +
      '<p><a class="btn btn--secondary" href="/credits">Mua thêm CP</a></p>' +
      '<section class="pm-card"><h2>Lịch sử thanh toán</h2>' +
        (orders.length ? '<div class="pm-table-wrap"><table class="pm-table"><thead><tr><th scope="col">Ngày</th><th scope="col">Gói</th>' +
          '<th scope="col">Số tiền</th><th scope="col">Trạng thái</th><th scope="col">Hạn Cao cấp</th><th scope="col">Mã đơn</th></tr></thead><tbody>' +
          orders.map(function (o) {
            return '<tr><td>' + date(o.createdAt, true) + '</td><td>' + esc(o.planName) + ' · ' +
              (o.productType === 'CREDIT' ? o.creditAmount + ' CP' : o.durationDays + ' ngày') + '</td>' +
              '<td>' + vnd(o.amount) + (Number(o.discountAmount) ? '<br><small>Giảm ' + vnd(o.discountAmount) + ' (' + esc(o.couponCode) + ')</small>' : '') + '</td><td>' + badge(o.status) +
              (o.status === 'PENDING' ? ' <a href="/premium/checkout/' + o.orderCode + '">Tiếp tục thanh toán</a>' : '') + '</td>' +
              '<td>' + (o.premiumUntil ? date(o.premiumUntil, true) : '—') + '</td><td>' + esc(o.orderCode) + '</td></tr>';
          }).join('') + '</tbody></table></div>'
          : '<p class="pm-muted">Bạn chưa có giao dịch nào.</p>') +
      '</section></div>';
  }

  async function credits() {
    var data = await api('GET', '/api/credits/packages');
    root.innerHTML = '<header class="pm-credits-head"><h1>Mua CP</h1>' +
      '<span class="pm-credits-balance"><span class="pm-credits-coin" aria-hidden="true"></span>' +
      (data.balance == null ? 'Đăng nhập để xem số dư' : '<strong>' + Number(data.balance).toLocaleString('vi-VN') + '</strong> CP') + '</span></header>' +
      '<div class="pm-credits-grid">' + data.packages.map(function (p) {
        var description = (p.description || '').trim();
        var repeated = [p.creditAmount + ' cp', 'gói ' + p.creditAmount + ' cp'].includes(description.replace(/\s+/g, ' ').replace(/[.!。]+$/, '').toLowerCase());
        return '<article class="pm-credits-card"><div class="pm-credits-card__art">' +
          '<span class="pm-credits-coin pm-credits-coin--large" aria-hidden="true"></span><span>+' + p.creditAmount + ' CP</span></div>' +
          '<div class="pm-credits-card__body"><h2>' + esc(p.name) + '</h2>' +
          (description && !repeated ? '<p class="pm-credits-card__desc">' + esc(description) + '</p>' : '') +
          '<div class="pm-credits-card__foot"><span class="pm-credits-price">' + vnd(p.price) + '</span>' +
          (data.paymentsEnabled ? '<button type="button" class="btn btn--primary" data-buy-credit="' + p.id + '">Mua gói</button>' : '<button type="button" class="btn btn--primary" disabled>Thanh toán đang bảo trì</button>') +
          '<p class="pm-error" data-error-for="' + p.id + '" aria-live="assertive"></p></div></div></article>';
      }).join('') + '</div><p class="pm-credits-history"><a href="/premium/billing">Lịch sử mua CP và thanh toán</a></p>';
    root.addEventListener('click', async function (event) {
      var button = event.target.closest('[data-buy-credit]');
      if (!button) return;
      var id = Number(button.dataset.buyCredit);
      var error = root.querySelector('[data-error-for="' + id + '"]');
      error.textContent = '';
      try {
        button.disabled = true;
        var order = await api('POST', '/api/credits/checkout', { packageId: id, couponCode: null });
        location.assign('/premium/checkout/' + order.orderCode);
      } catch (e) { error.textContent = e.message; button.disabled = false; }
    });
  }

  var run = { pricing: pricing, credits: credits, checkout: checkout, billing: billing }[page];
  if (run) run().catch(function (e) { fail(e.message); });
})();
