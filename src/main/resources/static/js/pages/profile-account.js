(function () {
  'use strict';

  if (typeof JlasAuth === 'undefined' || !document.getElementById('panel-overview')) return;

  function showAlert(element, message, type) {
    if (!element) return;
    element.hidden = !message;
    if (!message) return;
    if (window.JlasUI && typeof JlasUI.showAlert === 'function') {
      JlasUI.showAlert(element, message, type || 'error');
    } else {
      element.textContent = message;
    }
  }

  async function api(path, options) {
    var config = options || {};
    config.headers = Object.assign({}, JlasAuth.authHeaders(), config.headers || {});
    var response = await fetch(path, config);
    var payload = await response.json().catch(function () { return {}; });
    if (!response.ok || payload.success === false) {
      throw new Error(payload.message || 'Không thể xử lý yêu cầu.');
    }
    return payload.data;
  }

  function setText(id, value) {
    var element = document.getElementById(id);
    if (element) element.textContent = value;
  }

  function formatDate(value) {
    if (!value) return '—';
    try { return new Date(value).toLocaleString('vi-VN'); } catch (error) { return value; }
  }

  function formatMoney(amount, currency) {
    try {
      return new Intl.NumberFormat('vi-VN', {
        style: 'currency', currency: currency || 'VND', maximumFractionDigits: 0
      }).format(Number(amount || 0));
    } catch (error) {
      return Number(amount || 0).toLocaleString('vi-VN') + ' ' + (currency || 'VND');
    }
  }

  function empty(message) {
    var element = document.createElement('p');
    element.className = 'profile-empty';
    element.textContent = message;
    return element;
  }

  function renderCreditHistory(transactions) {
    var list = document.getElementById('creditHistoryList');
    if (!list) return;
    list.replaceChildren();
    if (!transactions || !transactions.length) {
      list.appendChild(empty('Chưa có biến động Credit.'));
      return;
    }
    transactions.slice(0, 5).forEach(function (transaction) {
      var row = document.createElement('div');
      row.className = 'profile-ledger__row';
      var body = document.createElement('div');
      var title = document.createElement('strong');
      title.textContent = transaction.note || creditReason(transaction.reason);
      var date = document.createElement('span');
      date.textContent = formatDate(transaction.createdAt);
      body.append(title, date);
      var amount = document.createElement('b');
      amount.className = Number(transaction.amount) >= 0 ? 'is-positive' : 'is-negative';
      amount.textContent = (Number(transaction.amount) >= 0 ? '+' : '') + transaction.amount;
      row.append(body, amount);
      list.appendChild(row);
    });
  }

  function creditReason(reason) {
    var labels = {
      ADMIN_GRANT: 'Credit được tặng', CREATOR_REWARD: 'Thưởng nội dung',
      DAILY_CHECKIN: 'Điểm danh hằng ngày', STUDY_REWARD: 'Thưởng học tập',
      QUIZ_REWARD: 'Thưởng hoàn thành Quiz', SHADOWING_REWARD: 'Thưởng luyện Shadowing',
      STREAK_MILESTONE_REWARD: 'Thưởng chuỗi ngày', STREAK_FREEZE: 'Dùng bảo vệ chuỗi',
      VIDEO_UNLOCK: 'Mở khóa video', WORD_CONTRIBUTION_REWARD: 'Thưởng đóng góp từ',
      REFUND: 'Hoàn Credit'
    };
    return labels[reason] || 'Biến động Credit';
  }

  function renderPayments(payments) {
    var list = document.getElementById('paymentHistoryList');
    if (!list) return;
    list.replaceChildren();
    if (!payments || !payments.length) {
      list.appendChild(empty('Bạn chưa có giao dịch thanh toán.'));
      return;
    }
    payments.forEach(function (payment) {
      var row = document.createElement('div');
      row.className = 'profile-ledger__row profile-ledger__row--payment';
      var body = document.createElement('div');
      var title = document.createElement('strong');
      title.textContent = payment.planName;
      var meta = document.createElement('span');
      meta.textContent = payment.provider + ' · ' + formatDate(payment.paidAt || payment.createdAt);
      body.append(title, meta);
      var side = document.createElement('div');
      side.className = 'profile-ledger__side';
      var amount = document.createElement('b');
      amount.textContent = formatMoney(payment.amount, payment.currency);
      var status = document.createElement('span');
      status.className = 'profile-payment-status profile-payment-status--' + String(payment.status).toLowerCase();
      status.textContent = paymentStatus(payment.status);
      side.append(amount, status);
      row.append(body, side);
      list.appendChild(row);
    });
  }

  function paymentStatus(status) {
    return ({ PAID: 'Thành công', PENDING: 'Đang xử lý', FAILED: 'Thất bại',
      CANCELLED: 'Đã hủy', REFUNDED: 'Đã hoàn tiền' })[status] || status;
  }

  function renderProgress(id, current, target) {
    var progress = document.getElementById(id + 'Progress');
    setText(id + 'Value', current + ' / ' + target);
    if (progress) {
      progress.max = Math.max(1, Number(target));
      progress.value = Math.min(Number(current), progress.max);
    }
  }

  var loginHistoryEntries = [];
  var visibleLoginHistory = 6;

  async function loadDashboard() {
    var loading = document.getElementById('dashboardLoading');
    var content = document.getElementById('dashboardContent');
    var errorBox = document.getElementById('dashboardError');
    try {
      var results = await Promise.all([
        api('/api/profile/dashboard'),
        api('/api/credits/me')
      ]);
      var dashboard = results[0];
      var credit = results[1];
      var learning = dashboard.learning;
      setText('dashCurrentStreak', learning.currentStreak + ' ngày');
      setText('dashLongestStreak', learning.longestStreak + ' ngày');
      setText('dashCreditBalance', Number(credit.balance || 0).toLocaleString('vi-VN'));
      renderProgress('dashVocab', learning.vocabularyLearned, learning.dailyVocabTarget);
      renderProgress('dashQuiz', learning.quizzesCompleted, learning.dailyQuizTarget);
      renderProgress('dashMinutes', learning.studyMinutes, learning.dailyStudyMinutes);
      renderCreditHistory(credit.transactions);
      renderPayments(dashboard.payments);
      setText('premiumPlanName', dashboard.plan.name);
      setText('premiumStatus', dashboard.plan.status === 'ACTIVE' ? 'Đang sử dụng'
        : dashboard.plan.status === 'EXPIRED' ? 'Đã hết hạn' : 'Miễn phí');
      setText('premiumExpires', formatDate(dashboard.plan.expiresAt));
      if (content) content.hidden = false;
    } catch (error) {
      showAlert(errorBox, error.message, 'error');
    } finally {
      if (loading) loading.hidden = true;
    }
  }

  function loginDeviceIcon(device) {
    var value = String(device || '').toLowerCase();
    if (value.includes('android') || value.includes('iphone') || value.includes('ipad')) return 'fa-mobile-screen-button';
    return 'fa-desktop';
  }

  function renderLoginHistory(entries) {
    var list = document.getElementById('loginHistoryList');
    var more = document.getElementById('loginHistoryMore');
    if (!list) return;
    list.replaceChildren();
    if (!entries || !entries.length) {
      list.appendChild(empty('Chưa có dữ liệu lịch sử đăng nhập để hiển thị.'));
      if (more) more.hidden = true;
      return;
    }
    entries.slice(0, visibleLoginHistory).forEach(function (entry, index) {
      var row = document.createElement('article');
      row.className = 'login-history-item' + (entry.active ? ' is-active' : '');
      var icon = document.createElement('span');
      icon.className = 'login-history-item__icon';
      icon.setAttribute('aria-hidden', 'true');
      icon.innerHTML = '<i class="fa-solid ' + loginDeviceIcon(entry.device) + '"></i>';

      var body = document.createElement('div');
      body.className = 'login-history-item__body';
      var title = document.createElement('strong');
      title.textContent = entry.device || 'Thiết bị chưa xác định';
      var meta = document.createElement('span');
      meta.textContent = [entry.ipAddress, formatDate(entry.signedInAt)].filter(Boolean).join(' · ');
      body.append(title, meta);

      var status = document.createElement('span');
      status.className = 'status-pill ' + (entry.active ? 'status-pill--ok' : 'status-pill--muted');
      status.textContent = entry.active ? 'Phiên còn hiệu lực' : 'Đã kết thúc';
      if (index === 0) status.title = 'Lần đăng nhập gần nhất';
      row.append(icon, body, status);
      list.appendChild(row);
    });
    if (more) {
      var remaining = entries.length - visibleLoginHistory;
      more.hidden = remaining <= 0;
      more.textContent = remaining > 0 ? 'Xem thêm ' + remaining + ' lần đăng nhập' : '';
    }
  }

  async function loadLoginHistory() {
    var loading = document.getElementById('loginHistoryLoading');
    var alertBox = document.getElementById('loginHistoryAlert');
    if (!loading && !alertBox) return;
    try {
      loginHistoryEntries = await api('/api/auth/login-history');
      visibleLoginHistory = 6;
      renderLoginHistory(loginHistoryEntries);
    } catch (error) {
      showAlert(alertBox, error.message, 'error');
    } finally {
      if (loading) loading.hidden = true;
    }
  }

  var loginHistoryMore = document.getElementById('loginHistoryMore');
  if (loginHistoryMore) loginHistoryMore.addEventListener('click', function () {
    visibleLoginHistory += 6;
    renderLoginHistory(loginHistoryEntries);
  });

  function updateGoogleState(user) {
    var status = document.getElementById('googleLinkStatus');
    var unlink = document.getElementById('unlinkGoogleBtn');
    var container = document.getElementById('googleLinkButton');
    if (!status || !unlink || !container) return;
    status.textContent = user.googleLinked ? 'Đã liên kết với Google' : 'Chưa liên kết';
    unlink.hidden = !user.googleLinked;
    unlink.disabled = !user.hasPassword;
    container.hidden = !!user.googleLinked;
    var passwordField = document.getElementById('emailChangePasswordField');
    var passwordInput = document.getElementById('emailChangePassword');
    if (passwordField) passwordField.hidden = !user.hasPassword;
    if (passwordInput) passwordInput.required = !!user.hasPassword;
  }

  function initGoogleLink(attempt) {
    var container = document.getElementById('googleLinkButton');
    if (!container || container.hidden) return;
    var clientId = (document.body.dataset.googleClientId || '').trim();
    if (!clientId) {
      container.replaceChildren(empty('Google Sign-In chưa được cấu hình.'));
      return;
    }
    if (!window.google || !google.accounts || !google.accounts.id) {
      if (attempt < 30) window.setTimeout(function () { initGoogleLink(attempt + 1); }, 200);
      return;
    }
    google.accounts.id.initialize({
      client_id: clientId,
      callback: async function (response) {
        if (!response || !response.credential) return;
        var alertBox = document.getElementById('googleLinkAlert');
        try {
          var user = await api('/api/auth/google/link', {
            method: 'POST', body: JSON.stringify({ idToken: response.credential })
          });
          localStorage.setItem('user', JSON.stringify(user));
          updateGoogleState(user);
          showAlert(alertBox, 'Đã liên kết tài khoản Google.', 'success');
        } catch (error) {
          showAlert(alertBox, error.message, 'error');
        }
      },
      auto_select: false
    });
    container.replaceChildren();
    google.accounts.id.renderButton(container, {
      type: 'standard', theme: 'outline', size: 'large', text: 'continue_with', shape: 'pill', width: 300
    });
  }

  var emailRequestForm = document.getElementById('emailChangeRequestForm');
  var emailConfirmForm = document.getElementById('emailChangeConfirmForm');
  if (emailRequestForm) emailRequestForm.addEventListener('submit', async function (event) {
    event.preventDefault();
    if (!emailRequestForm.checkValidity()) return emailRequestForm.reportValidity();
    var button = document.getElementById('requestEmailChangeBtn');
    var alertBox = document.getElementById('emailChangeAlert');
    button.disabled = true;
    try {
      await api('/api/auth/email-change/request', {
        method: 'POST',
        body: JSON.stringify({
          newEmail: document.getElementById('newEmail').value.trim(),
          currentPassword: document.getElementById('emailChangePassword').value
        })
      });
      emailRequestForm.hidden = true;
      emailConfirmForm.hidden = false;
      document.getElementById('oldEmailOtp').focus();
      showAlert(alertBox, 'Đã gửi hai mã OTP. Hãy kiểm tra cả hai hộp thư.', 'success');
    } catch (error) {
      showAlert(alertBox, error.message, 'error');
    } finally {
      button.disabled = false;
    }
  });

  if (emailConfirmForm) emailConfirmForm.addEventListener('submit', async function (event) {
    event.preventDefault();
    if (!emailConfirmForm.checkValidity()) return emailConfirmForm.reportValidity();
    var button = document.getElementById('confirmEmailChangeBtn');
    var alertBox = document.getElementById('emailChangeAlert');
    button.disabled = true;
    try {
      await api('/api/auth/email-change/confirm', {
        method: 'POST',
        body: JSON.stringify({
          oldEmailOtp: document.getElementById('oldEmailOtp').value,
          newEmailOtp: document.getElementById('newEmailOtp').value
        })
      });
      showAlert(alertBox, 'Đổi email thành công. Đang chuyển tới trang đăng nhập...', 'success');
      window.setTimeout(function () {
        JlasAuth.clearSession();
        window.location.replace('/login?emailChanged=1');
      }, 1200);
    } catch (error) {
      showAlert(alertBox, error.message, 'error');
      button.disabled = false;
    }
  });

  var cancelEmailChange = document.getElementById('cancelEmailChangeBtn');
  if (cancelEmailChange) cancelEmailChange.addEventListener('click', function () {
    emailConfirmForm.hidden = true;
    emailConfirmForm.reset();
    emailRequestForm.hidden = false;
  });

  var unlinkGoogle = document.getElementById('unlinkGoogleBtn');
  if (unlinkGoogle) unlinkGoogle.addEventListener('click', async function () {
    if (!window.confirm('Hủy liên kết Google khỏi tài khoản này?')) return;
    var alertBox = document.getElementById('googleLinkAlert');
    unlinkGoogle.disabled = true;
    try {
      var user = await api('/api/auth/google/link', { method: 'DELETE' });
      localStorage.setItem('user', JSON.stringify(user));
      updateGoogleState(user);
      initGoogleLink(0);
      showAlert(alertBox, 'Đã hủy liên kết Google.', 'success');
    } catch (error) {
      showAlert(alertBox, error.message, 'error');
      unlinkGoogle.disabled = false;
    }
  });

  JlasAuth.refreshUser().then(function (user) {
    if (!user) return;
    updateGoogleState(user);
    if (!user.googleLinked) initGoogleLink(0);
  });
  loadDashboard();
  loadLoginHistory();
})();
