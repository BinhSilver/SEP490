(function () {
  'use strict';

  var redirectingAuthenticatedUser = false;
  var LOCKED_MESSAGE = 'Tài khoản đã bị khóa. Vui lòng liên hệ quản trị viên.';

  function loginRedirectTarget() {
    return window.JLabAuth && typeof JLabAuth.getSafeRedirect === 'function'
      ? JLabAuth.getSafeRedirect()
      : '/';
  }

  function redirectIfAlreadyAuthenticated() {
    if (redirectingAuthenticatedUser || !window.JlasAuth || !JlasAuth.isLoggedIn()) return;

    var token = JlasAuth.getAccessToken();
    if (!token) {
      redirectingAuthenticatedUser = true;
      window.location.replace(loginRedirectTarget());
      return;
    }

    JlasAuth.refreshUser().then(function () {
      if (JlasAuth.isLoggedIn()) {
        redirectingAuthenticatedUser = true;
        // replace() removes /login from history, so Back will not return here.
        window.location.replace(loginRedirectTarget());
      }
    });
  }

  // A locked account is sent here as /login?locked=1: drop its stale session so the form stays usable.
  var lockedRedirect = new URLSearchParams(window.location.search).get('locked') === '1';
  if (lockedRedirect && window.JlasAuth) JlasAuth.clearSession();

  redirectIfAlreadyAuthenticated();
  window.addEventListener('pageshow', redirectIfAlreadyAuthenticated);
  if (lockedRedirect && window.JlasUI) JlasUI.toast(LOCKED_MESSAGE, 'error');

  var form = document.getElementById('loginForm');
  var submitBtn = document.getElementById('submitBtn');
  var emailInput = document.getElementById('email');
  var passwordInput = document.getElementById('password');
  var capsLockWarning = document.getElementById('capsLockWarning');
  var googleSection = document.getElementById('googleSection');
  var FIELD_IDS = ['email', 'password'];
  var GOOGLE_LOAD_RETRIES = 20;
  var loginLockTimer = null;
  var loginLockedUntil = 0;
  var touched = {
    email: false,
    password: false
  };
  var PASSWORD_RULES = {
    length: function (value) { return value.length >= 8 && value.length <= 100; },
    case: function (value) { return /[a-z]/.test(value) && /[A-Z]/.test(value); },
    number: function (value) { return /\d/.test(value); },
    special: function (value) { return /[@$!%*?&#^()_+={}\[\]:;"'<>,./\\|~`\-]/.test(value); }
  };

  JLabAuth.setupPasswordToggles();

  function hasLoginEmail() {
    return Boolean(emailInput.value.trim()) && emailInput.validity.valid;
  }

  function hasLoginPassword() {
    return Boolean(passwordInput.value);
  }

  function updatePasswordRules() {
    var value = passwordInput.value;
    Object.keys(PASSWORD_RULES).forEach(function (ruleName) {
      var item = document.querySelector('[data-password-rule="' + ruleName + '"]');
      if (!item) return;
      var valid = PASSWORD_RULES[ruleName](value);
      item.classList.toggle('is-valid', valid);
      var icon = item.querySelector('i');
      if (icon) {
        icon.classList.toggle('fa-circle-xmark', !valid);
        icon.classList.toggle('fa-circle-check', valid);
      }
    });
  }

  updatePasswordRules();

  function validateField(fieldId) {
    var message = '';
    if (fieldId === 'email') {
      if (!emailInput.value.trim()) message = 'Vui lòng nhập email.';
      else if (!hasLoginEmail()) message = 'Email không hợp lệ. Ví dụ: ban@example.com';
    } else if (!hasLoginPassword()) {
      message = 'Vui lòng nhập mật khẩu.';
    }
    JLabAuth.setFieldError(fieldId, message);
    return !message;
  }

  function validateForm() {
    var valid = true;
    FIELD_IDS.forEach(function (fieldId) {
      touched[fieldId] = true;
      if (!validateField(fieldId)) valid = false;
    });
    if (!valid) JLabAuth.focusFirstInvalid(FIELD_IDS);
    return valid;
  }

  function showUnverifiedAlert(email) {
    window.location.assign('/verify-email?email=' + encodeURIComponent(email));
  }

  function isLoginLocked() {
    return loginLockedUntil > Date.now();
  }

  function lockLoginButton(seconds) {
    JLabAuth.setButtonLoading(submitBtn, false, 'Đang đăng nhập…', 'Đăng nhập');
    loginLockedUntil = Date.now() + seconds * 1000;
    window.clearInterval(loginLockTimer);

    function updateLockState() {
      var remaining = Math.max(0, Math.ceil((loginLockedUntil - Date.now()) / 1000));
      var label = submitBtn.querySelector('.btn__label');
      if (remaining === 0) {
        window.clearInterval(loginLockTimer);
        loginLockTimer = null;
        loginLockedUntil = 0;
        submitBtn.disabled = false;
        submitBtn.classList.remove('is-rate-limited');
        submitBtn.removeAttribute('aria-disabled');
        if (label) label.textContent = 'Đăng nhập';
        return;
      }

      submitBtn.disabled = true;
      submitBtn.classList.add('is-rate-limited');
      submitBtn.setAttribute('aria-disabled', 'true');
      if (label) label.textContent = 'Thử lại sau ' + remaining + ' giây';
    }

    updateLockState();
    loginLockTimer = window.setInterval(updateLockState, 250);
  }

  function waitSecondsFromMessage(message) {
    var match = String(message || '').match(/sau\s+(\d+)\s+giây/i);
    return match ? Number(match[1]) : 30;
  }

  function finishLogin(data) {
    JLabAuth.saveSession(data);
    if (window.matchMedia('(max-width: 900px)').matches) {
      window.location.replace(loginRedirectTarget());
      return;
    }

    var seconds = 3;
    var getMessage = function (sec) {
      return 'Đăng nhập thành công! Tự động chuyển hướng sau ' + sec + 's...';
    };

    var formatMessage = function (sec) {
      return getMessage(sec).replace('! ', '!\n');
    };

    var toastEl = JlasUI.toast(formatMessage(seconds), 'success');
    if (toastEl) toastEl.classList.add('toast--login-progress');
    var textSpan = toastEl ? toastEl.querySelector('span:nth-child(2)') : null;

    var timer = setInterval(function () {
      seconds--;
      if (seconds > 0) {
        if (textSpan) {
          textSpan.textContent = formatMessage(seconds);
        }
      } else {
        clearInterval(timer);
        window.location.href = JLabAuth.getSafeRedirect();
      }
    }, 1000);
  }

  async function loginWithGoogleIdToken(idToken) {
    try {
      var response = await fetch('/api/auth/google', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken: idToken })
      });
      var json = await JLabAuth.readJson(response);
      console.info('[AUTH_TRACE] googleResponse', {
        status: response.status,
        ok: response.ok,
        success: !!(json && json.success),
        hasData: !!(json && json.data),
        hasAccessToken: !!(json && json.data && json.data.accessToken)
      });
      if (!response.ok || !json.success) {
        JlasUI.toast(json.message || 'Không thể đăng nhập bằng Google.', 'error');
        return;
      }
      finishLogin(json.data);
    } catch (error) {
      JlasUI.toast('Không thể kết nối đến máy chủ. Vui lòng thử lại.', 'error');
    }
  }

  function initGoogleSignIn(attempt) {
    if (!googleSection) return;
    var clientId = (document.body.dataset.googleClientId || '').trim();
    if (!clientId) {
      googleSection.hidden = true;
      return;
    }
    if (typeof google === 'undefined' || !google.accounts || !google.accounts.id) {
      if (attempt < GOOGLE_LOAD_RETRIES) {
        window.setTimeout(function () { initGoogleSignIn(attempt + 1); }, 150);
      } else {
        googleSection.hidden = true;
      }
      return;
    }

    google.accounts.id.initialize({
      client_id: clientId,
      callback: function (response) {
        if (response && response.credential) {
          loginWithGoogleIdToken(response.credential);
        } else {
          JlasUI.toast('Google không trả về thông tin đăng nhập.', 'error');
        }
      },
      auto_select: false
    });
    var container = document.getElementById('googleBtnContainer');
    var lastRender = '';
    function renderGoogleButton() {
      var containerWidth = Math.floor(container.getBoundingClientRect().width);
      if (containerWidth <= 0) return;
      var width = Math.min(containerWidth, 400);
      var text = containerWidth < 280 ? 'signin' : 'signin_with';
      var theme = 'outline';
      var renderKey = width + ':' + text + ':' + theme;
      if (renderKey === lastRender) return;
      lastRender = renderKey;
      google.accounts.id.renderButton(container, {
        type: 'standard',
        theme: theme,
        size: 'large',
        text: text,
        shape: 'pill',
        width: width
      });
    }
    renderGoogleButton();
    if (window.ResizeObserver) {
      new window.ResizeObserver(renderGoogleButton).observe(container);
    }
  }

  emailInput.addEventListener('input', function () {
    if (touched.email) validateField('email');
  });
  passwordInput.addEventListener('input', function () {
    if (touched.password) validateField('password');
    updatePasswordRules();
  });
  ['keydown', 'keyup'].forEach(function (eventName) {
    passwordInput.addEventListener(eventName, function (event) {
      if (capsLockWarning) capsLockWarning.hidden = !event.getModifierState('CapsLock');
    });
  });
  emailInput.addEventListener('blur', function () {
    touched.email = true;
    validateField('email');
  });
  passwordInput.addEventListener('blur', function () {
    touched.password = true;
    validateField('password');
    updatePasswordRules();
    if (capsLockWarning) capsLockWarning.hidden = true;
  });

  form.addEventListener('submit', async function (event) {
    event.preventDefault();
    if (isLoginLocked() || submitBtn.disabled) return;
    if (!validateForm()) return;

    JLabAuth.setButtonLoading(submitBtn, true, 'Đang đăng nhập…', 'Đăng nhập');
    var loginCompleted = false;
    var loginRateLimited = false;
    try {
      var response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: emailInput.value.trim(),
          password: passwordInput.value
        })
      });
      var json = await JLabAuth.readJson(response);
      console.info('[AUTH_TRACE] localResponse', {
        status: response.status,
        ok: response.ok,
        success: !!(json && json.success),
        hasData: !!(json && json.data),
        hasAccessToken: !!(json && json.data && json.data.accessToken)
      });
      if (!response.ok || !json.success) {
        var message = json.message || 'Đăng nhập không thành công.';
        var isUnverified = json.code === 'EMAIL_UNVERIFIED'
          || json.code === 'ACCOUNT_UNVERIFIED'
          || message.toLowerCase().includes('not verified')
          || message.toLowerCase().includes('chưa xác thực');
        if (isUnverified) showUnverifiedAlert(emailInput.value.trim());
        else if (json.code === 'ACCOUNT_LOCKED') JlasUI.toast(LOCKED_MESSAGE, 'error');
        else if (json.code === 'TOO_MANY_REQUESTS') {
          loginRateLimited = true;
          JlasUI.toast(message, 'error');
          lockLoginButton(waitSecondsFromMessage(message));
        }
        else if (message.toLowerCase().includes('google login')) {
          JlasUI.toast('Tài khoản này sử dụng Google. Vui lòng đăng nhập bằng Google.', 'error');
        } else JlasUI.toast('Email hoặc mật khẩu không chính xác.', 'error');
        return;
      }
      loginCompleted = true;
      finishLogin(json.data);
    } catch (error) {
      JlasUI.toast('Không thể kết nối đến máy chủ. Vui lòng thử lại.', 'error');
    } finally {
      if (!loginCompleted && !loginRateLimited) {
        JLabAuth.setButtonLoading(submitBtn, false, 'Đang đăng nhập…', 'Đăng nhập');
      }
    }
  });

  window.addEventListener('load', function () { initGoogleSignIn(0); });
})();
