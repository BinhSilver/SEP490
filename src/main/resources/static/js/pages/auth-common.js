(function () {
  'use strict';

  var DEFAULT_REDIRECT = '/';
  // Admin-area roles land in their workspace instead of the learner home (/admin routes each role).
  var ROLE_HOME = { ADMIN: '/admin', MODERATOR: '/admin', CONTENT_CREATOR: '/textbooks/manage' };

  function getField(fieldId) {
    return document.getElementById(fieldId);
  }

  function setFieldError(fieldId, message) {
    var field = getField(fieldId);
    var error = getField(fieldId + 'Error');
    if (!field || !error) return;

    var text = message || '';
    error.textContent = text;
    field.setAttribute('aria-invalid', text ? 'true' : 'false');
    field.closest('.field, .otp-fieldset')?.classList.toggle('has-error', Boolean(text));
  }

  function clearFieldErrors(fieldIds) {
    fieldIds.forEach(function (fieldId) {
      setFieldError(fieldId, '');
    });
  }

  function focusFirstInvalid(fieldIds) {
    var firstInvalid = fieldIds.map(getField).find(function (field) {
      return field && field.getAttribute('aria-invalid') === 'true';
    });
    if (firstInvalid) firstInvalid.focus();
  }

  function setButtonLoading(button, loading, loadingLabel, idleLabel) {
    if (!button) return;
    var label = button.querySelector('.btn__label');
    button.disabled = loading;
    button.classList.toggle('is-loading', loading);
    button.setAttribute('aria-busy', String(loading));
    if (label) label.textContent = loading ? loadingLabel : idleLabel;
  }

  function setupPasswordToggles() {
    document.querySelectorAll('[data-password-toggle]').forEach(function (button) {
      button.addEventListener('click', function () {
        var input = getField(button.dataset.passwordToggle);
        var icon = button.querySelector('i');
        if (!input) return;

        var willShow = input.type === 'password';
        input.type = willShow ? 'text' : 'password';
        button.setAttribute('aria-pressed', String(willShow));
        button.setAttribute('aria-label', willShow ? 'Ẩn mật khẩu' : 'Hiện mật khẩu');
        if (icon) {
          icon.classList.toggle('fa-eye', !willShow);
          icon.classList.toggle('fa-eye-slash', willShow);
        }
      });
    });
  }

  function saveSession(data) {
    console.info('[AUTH_TRACE] saveSession', {
      hasData: !!data,
      hasAccessToken: !!(data && data.accessToken),
      expiresIn: data && data.expiresIn,
      userEmail: data && data.user && data.user.email
    });
    if (!data || !data.accessToken) return;
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken || '');
    localStorage.setItem('user', JSON.stringify(data.user || {}));
    document.cookie = 'accessToken=' + encodeURIComponent(data.accessToken)
      + '; path=/; max-age=604800; SameSite=Lax';
    // Standalone pages (Arena) authenticate from cookies instead of localStorage.
    document.cookie = 'access_token=' + encodeURIComponent(data.accessToken)
      + '; path=/; max-age=604800; SameSite=Lax';
    document.cookie = 'auth_status=1; path=/; max-age=' + (data.expiresIn || 900) + '; SameSite=Lax';
    console.info('[AUTH_TRACE] sessionStored', {
      localToken: !!localStorage.getItem('accessToken'),
      authStatusCookie: document.cookie.indexOf('auth_status=1') >= 0,
      visibleCookies: document.cookie.split('; ').map(function (item) { return item.split('=')[0]; })
    });
  }

  function getSafeRedirect() {
    var redirect = new URLSearchParams(window.location.search).get('redirect');
    if (!redirect || !redirect.startsWith('/') || redirect.startsWith('//')) {
      return ROLE_HOME[savedRole()] || DEFAULT_REDIRECT;
    }
    return redirect;
  }

  function savedRole() {
    try {
      return (JSON.parse(localStorage.getItem('user') || '{}') || {}).role;
    } catch (error) {
      return null;
    }
  }

  async function readJson(response) {
    try {
      return await response.json();
    } catch (error) {
      return { success: false, message: 'Máy chủ trả về dữ liệu không hợp lệ.' };
    }
  }

  window.JLabAuth = {
    clearFieldErrors: clearFieldErrors,
    focusFirstInvalid: focusFirstInvalid,
    getSafeRedirect: getSafeRedirect,
    readJson: readJson,
    saveSession: saveSession,
    setButtonLoading: setButtonLoading,
    setFieldError: setFieldError,
    setupPasswordToggles: setupPasswordToggles
  };
})();
