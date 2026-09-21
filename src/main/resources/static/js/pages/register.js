(function () {
  'use strict';

  var alertEl = document.getElementById('alert');
  var registerForm = document.getElementById('registerForm');
  var submitBtn = document.getElementById('submitBtn');
  var stepRegister = document.getElementById('stepRegister');
  var stepOtp = document.getElementById('stepOtp');
  var registeredEmailText = document.getElementById('registeredEmailText');
  var otpForm = document.getElementById('otpForm');
  var verifyOtpBtn = document.getElementById('verifyOtpBtn');
  var resendOtpBtn = document.getElementById('resendOtpBtn');
  var backToRegisterBtn = document.getElementById('backToRegisterBtn');
  var fullNameInput = document.getElementById('fullName');
  var emailInput = document.getElementById('email');
  var passwordInput = document.getElementById('password');
  var confirmPasswordInput = document.getElementById('confirmPassword');
  var otpDigits = Array.from(document.querySelectorAll('.otp-digit'));
  var REGISTER_FIELDS = ['fullName', 'email', 'password', 'confirmPassword'];
  var FULL_NAME_PATTERN = /^\p{Script=Latin}[\p{Script=Latin}\p{M}]*(?: \p{Script=Latin}[\p{Script=Latin}\p{M}]*)*$/u;
  var PASSWORD_RULES = {
    length: function (value) { return value.length >= 8 && value.length <= 100; },
    case: function (value) { return /[a-z]/.test(value) && /[A-Z]/.test(value); },
    number: function (value) { return /\d/.test(value); },
    special: function (value) { return /[@$!%*?&#^()_+={}\[\]:;"'<>,./\\|~`\-]/.test(value); }
  };
  var SERVER_FIELD_MESSAGES = {
    fullName: 'Họ và tên chưa hợp lệ.',
    email: 'Email chưa hợp lệ.',
    password: 'Mật khẩu chưa đáp ứng đủ yêu cầu.',
    confirmPassword: 'Mật khẩu xác nhận chưa hợp lệ.',
    registerRequest: 'Mật khẩu xác nhận không khớp.'
  };
  var RESEND_COOLDOWN_SECONDS = 60;

  var targetEmail = '';
  var cooldownSeconds = 0;
  var timerInterval = null;
  var otpSubmitTimer = null;

  JLabAuth.setupPasswordToggles();

  function updatePasswordRules() {
    var value = passwordInput.value;
    Object.keys(PASSWORD_RULES).forEach(function (ruleName) {
      var item = document.querySelector('[data-password-rule="' + ruleName + '"]');
      var passes = PASSWORD_RULES[ruleName](value);
      if (!item) return;
      item.classList.toggle('is-valid', passes);
      var icon = item.querySelector('i');
      if (icon) {
        icon.classList.toggle('fa-circle', !passes);
        icon.classList.toggle('fa-circle-check', passes);
      }
    });
  }

  function isPasswordStrong(value) {
    return Object.keys(PASSWORD_RULES).every(function (ruleName) {
      return PASSWORD_RULES[ruleName](value);
    });
  }

  function isFullNameValid(value) {
    return FULL_NAME_PATTERN.test(value);
  }

  function validateRegistration() {
    JLabAuth.clearFieldErrors(REGISTER_FIELDS);
    var fullName = fullNameInput.value.trim();
    var email = emailInput.value.trim();

    if (!fullName) {
      JLabAuth.setFieldError('fullName', 'Vui lòng nhập họ và tên.');
    } else if (fullName.length < 2) {
      JLabAuth.setFieldError('fullName', 'Họ và tên cần có ít nhất 2 ký tự.');
    } else if (!isFullNameValid(fullName)) {
      JLabAuth.setFieldError('fullName', 'Họ và tên chỉ được gồm chữ cái tiếng Việt và khoảng trắng giữa các từ.');
    }
    if (!email) {
      JLabAuth.setFieldError('email', 'Vui lòng nhập email.');
    } else if (!emailInput.validity.valid) {
      JLabAuth.setFieldError('email', 'Email chưa đúng định dạng.');
    }
    if (!passwordInput.value) {
      JLabAuth.setFieldError('password', 'Vui lòng tạo mật khẩu.');
    } else if (!isPasswordStrong(passwordInput.value)) {
      JLabAuth.setFieldError('password', 'Mật khẩu chưa đáp ứng đủ các yêu cầu bên dưới.');
    }
    if (!confirmPasswordInput.value) {
      JLabAuth.setFieldError('confirmPassword', 'Vui lòng nhập lại mật khẩu.');
    } else if (passwordInput.value !== confirmPasswordInput.value) {
      JLabAuth.setFieldError('confirmPassword', 'Mật khẩu xác nhận không khớp.');
    }

    var valid = REGISTER_FIELDS.every(function (fieldId) {
      return document.getElementById(fieldId).getAttribute('aria-invalid') !== 'true';
    });
    if (!valid) JLabAuth.focusFirstInvalid(REGISTER_FIELDS);
    return valid;
  }

  function showServerFieldErrors(errors) {
    if (!errors) return false;
    var hasFieldError = false;
    Object.keys(errors).forEach(function (fieldName) {
      var targetField = fieldName === 'registerRequest' ? 'confirmPassword' : fieldName;
      if (!REGISTER_FIELDS.includes(targetField)) return;
      JLabAuth.setFieldError(targetField, SERVER_FIELD_MESSAGES[fieldName] || errors[fieldName]);
      hasFieldError = true;
    });
    if (hasFieldError) JLabAuth.focusFirstInvalid(REGISTER_FIELDS);
    return hasFieldError;
  }

  function localizeApiMessage(message, fallback) {
    var normalized = String(message || '').toLowerCase();
    if (normalized.includes('already registered')) return 'Email này đã được đăng ký.';
    if (normalized.includes('already verified')) return 'Email này đã được xác minh.';
    if (normalized.includes('too many') || normalized.includes('quá nhiều') || normalized.includes('sau 3 phút')) {
      return 'Bạn chỉ có thể yêu cầu mã OTP mới sau 3 phút.';
    }
    if (normalized.includes('expired')) return 'Mã xác minh đã hết hạn. Vui lòng gửi lại mã mới.';
    if (normalized.includes('invalid') || normalized.includes('incorrect')) {
      return 'Mã xác minh không đúng. Vui lòng kiểm tra lại.';
    }
    return fallback;
  }

  function getOtpCode() {
    return otpDigits.map(function (input) { return input.value; }).join('');
  }

  function clearOtp() {
    otpDigits.forEach(function (input) {
      input.value = '';
      input.setAttribute('aria-invalid', 'false');
    });
    JLabAuth.setFieldError('otp', '');
  }

  function scheduleOtpSubmit() {
    window.clearTimeout(otpSubmitTimer);
    if (getOtpCode().length !== otpDigits.length || verifyOtpBtn.disabled) return;
    otpSubmitTimer = window.setTimeout(function () {
      otpForm.requestSubmit();
    }, 250);
  }

  function updateCooldownButton() {
    if (cooldownSeconds > 0) {
      resendOtpBtn.disabled = true;
      resendOtpBtn.textContent = 'Gửi lại mã sau ' + cooldownSeconds + ' giây';
    } else {
      resendOtpBtn.disabled = false;
      resendOtpBtn.textContent = 'Gửi lại mã';
    }
  }

  function startCooldown(seconds) {
    cooldownSeconds = seconds;
    window.clearInterval(timerInterval);
    updateCooldownButton();
    timerInterval = window.setInterval(function () {
      cooldownSeconds -= 1;
      updateCooldownButton();
      if (cooldownSeconds <= 0) window.clearInterval(timerInterval);
    }, 1000);
  }

  function showOtpStep(withCooldown) {
    stepRegister.hidden = true;
    stepOtp.hidden = false;
    registeredEmailText.textContent = targetEmail;
    clearOtp();
    if (withCooldown !== false) startCooldown(RESEND_COOLDOWN_SECONDS);
    else {
      cooldownSeconds = 0;
      updateCooldownButton();
    }
    otpDigits[0].focus();
  }

  function markOtpInvalid(message) {
    JLabAuth.setFieldError('otp', message);
    otpDigits.forEach(function (input) { input.setAttribute('aria-invalid', 'true'); });
    otpDigits[0].focus();
  }

  passwordInput.addEventListener('input', function () {
    updatePasswordRules();
    if (passwordInput.getAttribute('aria-invalid') !== 'true') return;
    if (!passwordInput.value) JLabAuth.setFieldError('password', 'Vui lòng tạo mật khẩu.');
    else if (!isPasswordStrong(passwordInput.value)) {
      JLabAuth.setFieldError('password', 'Mật khẩu chưa đáp ứng đủ các yêu cầu bên dưới.');
    } else JLabAuth.setFieldError('password', '');
  });
  confirmPasswordInput.addEventListener('input', function () {
    if (confirmPasswordInput.value || confirmPasswordInput.getAttribute('aria-invalid') === 'true') {
      JLabAuth.setFieldError(
        'confirmPassword',
        confirmPasswordInput.value === passwordInput.value ? '' : 'Mật khẩu xác nhận không khớp.'
      );
    }
  });
  fullNameInput.addEventListener('input', function () {
    if (fullNameInput.getAttribute('aria-invalid') !== 'true') return;
    var name = fullNameInput.value.trim();
    if (!name) JLabAuth.setFieldError('fullName', 'Vui lòng nhập họ và tên.');
    else if (name.length < 2) JLabAuth.setFieldError('fullName', 'Họ và tên cần có ít nhất 2 ký tự.');
    else if (!isFullNameValid(name)) JLabAuth.setFieldError('fullName', 'Họ và tên chỉ được gồm chữ cái tiếng Việt và khoảng trắng giữa các từ.');
    else JLabAuth.setFieldError('fullName', '');
  });
  fullNameInput.addEventListener('blur', function () {
    var name = fullNameInput.value.trim();
    if (!name) return;
    if (name.length < 2) JLabAuth.setFieldError('fullName', 'Họ và tên cần có ít nhất 2 ký tự.');
    else if (!isFullNameValid(name)) JLabAuth.setFieldError('fullName', 'Họ và tên chỉ được gồm chữ cái tiếng Việt và khoảng trắng giữa các từ.');
    else JLabAuth.setFieldError('fullName', '');
  });
  emailInput.addEventListener('input', function () {
    if (emailInput.getAttribute('aria-invalid') !== 'true') return;
    if (!emailInput.value.trim()) JLabAuth.setFieldError('email', 'Vui lòng nhập email.');
    else if (!emailInput.validity.valid) JLabAuth.setFieldError('email', 'Email chưa đúng định dạng.');
    else JLabAuth.setFieldError('email', '');
  });
  emailInput.addEventListener('blur', function () {
    var email = emailInput.value.trim();
    if (!email) return;
    if (!emailInput.validity.valid) JLabAuth.setFieldError('email', 'Email chưa đúng định dạng.');
    else JLabAuth.setFieldError('email', '');
  });

  otpDigits.forEach(function (input, index) {
    input.addEventListener('input', function () {
      input.value = input.value.replace(/\D/g, '').slice(-1);
      JLabAuth.setFieldError('otp', '');
      otpDigits.forEach(function (digit) { digit.setAttribute('aria-invalid', 'false'); });
      if (input.value && index < otpDigits.length - 1) otpDigits[index + 1].focus();
      scheduleOtpSubmit();
    });
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Backspace' && !input.value && index > 0) otpDigits[index - 1].focus();
      if (event.key === 'ArrowLeft' && index > 0) otpDigits[index - 1].focus();
      if (event.key === 'ArrowRight' && index < otpDigits.length - 1) otpDigits[index + 1].focus();
    });
  });

  document.querySelector('[data-otp-inputs]').addEventListener('paste', function (event) {
    var pasted = (event.clipboardData || window.clipboardData).getData('text').replace(/\D/g, '');
    if (pasted.length !== otpDigits.length) return;
    event.preventDefault();
    pasted.split('').forEach(function (character, index) { otpDigits[index].value = character; });
    otpDigits[otpDigits.length - 1].focus();
    scheduleOtpSubmit();
  });

  registerForm.addEventListener('submit', async function (event) {
    event.preventDefault();
    JlasUI.hideAlert(alertEl);
    if (!validateRegistration()) return;

    targetEmail = emailInput.value.trim();
    JLabAuth.setButtonLoading(submitBtn, true, 'Đang tạo tài khoản…', 'Tạo tài khoản');
    try {
      var response = await fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fullName: fullNameInput.value.trim(),
          email: targetEmail,
          password: passwordInput.value,
          confirmPassword: confirmPasswordInput.value,
          japaneseLevel: document.getElementById('level').value
        })
      });
      var json = await JLabAuth.readJson(response);
      if (!response.ok || !json.success) {
        if (json.code === 'PENDING_VERIFICATION') {
          var wantsNewOtp = window.confirm(
            'Email này chưa được xác thực. Bạn có muốn gửi mã OTP mới không?'
          );
          showOtpStep(false);
          if (wantsNewOtp) resendOtpBtn.click();
          else JlasUI.showAlert(
            alertEl,
            'Bạn có thể nhập mã đã nhận trước đó hoặc bấm “Gửi lại mã” khi cần.',
            'info'
          );
          return;
        }
        if (!showServerFieldErrors(json.errors)) {
          JlasUI.showAlert(alertEl, localizeApiMessage(json.message, 'Không thể tạo tài khoản.'), 'error');
        }
        return;
      }

      showOtpStep();
      JlasUI.showAlert(alertEl, 'Mã xác minh đã được gửi đến ' + targetEmail + '.', 'success');
    } catch (error) {
      JlasUI.showAlert(alertEl, 'Không thể kết nối đến máy chủ. Vui lòng thử lại.', 'error');
    } finally {
      JLabAuth.setButtonLoading(submitBtn, false, 'Đang tạo tài khoản…', 'Tạo tài khoản');
    }
  });

  otpForm.addEventListener('submit', async function (event) {
    event.preventDefault();
    window.clearTimeout(otpSubmitTimer);
    JlasUI.hideAlert(alertEl);
    var otp = getOtpCode();
    if (otp.length !== otpDigits.length) {
      markOtpInvalid('Vui lòng nhập đủ 6 chữ số.');
      return;
    }

    JLabAuth.setButtonLoading(verifyOtpBtn, true, 'Đang xác minh…', 'Xác minh và hoàn tất');
    var verificationCompleted = false;
    try {
      var response = await fetch('/api/auth/verify-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: targetEmail, otp: otp })
      });
      var json = await JLabAuth.readJson(response);
      if (!response.ok || !json.success) {
        markOtpInvalid(localizeApiMessage(json.message, 'Mã xác minh không đúng hoặc đã hết hạn.'));
        return;
      }

      verificationCompleted = true;
      JLabAuth.saveSession(json.data);
      JlasUI.showAlert(alertEl, 'Xác minh thành công. Đang chuyển hướng…', 'success');
      window.setTimeout(function () { window.location.href = '/'; }, 600);
    } catch (error) {
      JlasUI.showAlert(alertEl, 'Không thể kết nối đến máy chủ. Vui lòng thử lại.', 'error');
    } finally {
      if (!verificationCompleted) {
        JLabAuth.setButtonLoading(verifyOtpBtn, false, 'Đang xác minh…', 'Xác minh và hoàn tất');
      }
    }
  });

  resendOtpBtn.addEventListener('click', async function () {
    if (!targetEmail || cooldownSeconds > 0) return;
    JlasUI.hideAlert(alertEl);
    resendOtpBtn.disabled = true;
    resendOtpBtn.textContent = 'Đang gửi…';
    try {
      var response = await fetch('/api/auth/resend-verification', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: targetEmail })
      });
      var json = await JLabAuth.readJson(response);
      if (!response.ok || !json.success) {
        JlasUI.showAlert(
          alertEl,
          localizeApiMessage(json.message, 'Không thể gửi lại mã xác minh.'),
          'error'
        );
        updateCooldownButton();
        return;
      }
      clearOtp();
      startCooldown(RESEND_COOLDOWN_SECONDS);
      otpDigits[0].focus();
      JlasUI.showAlert(alertEl, 'Đã gửi mã xác minh mới đến ' + targetEmail + '.', 'success');
    } catch (error) {
      JlasUI.showAlert(alertEl, 'Không thể kết nối đến máy chủ. Vui lòng thử lại.', 'error');
      updateCooldownButton();
    }
  });

  backToRegisterBtn.addEventListener('click', function () {
    JlasUI.hideAlert(alertEl);
    window.clearTimeout(otpSubmitTimer);
    stepOtp.hidden = true;
    stepRegister.hidden = false;
    emailInput.focus();
  });

  updatePasswordRules();
})();
