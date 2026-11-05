/**
 * Age-rated Shadowing videos (video.min_age). On a lesson page, asks the server whether this account may open the
 * video before anything plays: without a birth date the dialog asks for one (saved once to the profile), under the
 * age it explains why and links back to the library. Guests get a 401, which auth-session.js turns into the login
 * page. The server enforces the same rule on every content endpoint; this only gives the learner a way forward.
 */
(function (root, factory) {
  'use strict';
  const api = factory(root);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ShadowingAgeGate = api;
}(typeof window !== 'undefined' ? window : globalThis, function (root) {
  'use strict';
  const GATE_CODES = new Set(['BIRTH_DATE_REQUIRED', 'AGE_RESTRICTED']);
  const EARLIEST = '1900-01-01';

  function isGateCode(code) {
    return GATE_CODES.has(code);
  }

  function todayIso(now) {
    const date = now || new Date();
    return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-'
      + String(date.getDate()).padStart(2, '0');
  }

  /** yyyy-mm-dd strings compare like dates; the server checks the same range again. */
  function birthDateError(value, today) {
    if (!value) return 'Vui lòng nhập ngày sinh.';
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value < EARLIEST || value > (today || todayIso())) {
      return 'Ngày sinh chưa hợp lệ.';
    }
    return '';
  }

  const document = root.document;
  const dialog = document?.getElementById('age-gate-dialog');
  const videoId = dialog?.dataset.videoId;
  const description = dialog?.querySelector('#age-gate-description');
  const form = dialog?.querySelector('#age-gate-form');
  const input = dialog?.querySelector('#age-gate-birth');
  const error = dialog?.querySelector('#age-gate-error');
  const submit = dialog?.querySelector('.age-gate-submit');
  const back = dialog?.querySelector('.age-gate-back');
  let wheel = null;

  function headers(json) {
    return typeof root.authHeaders === 'function' ? root.authHeaders(json) : {};
  }

  async function accessError() {
    const response = await fetch('/api/videos/' + encodeURIComponent(videoId) + '/access', { headers: headers(false) });
    if (response.ok) return null;
    return response.json().catch(() => ({}));
  }

  function open() {
    if (dialog.open) return;
    dialog.showModal();
    // Focus the dialog itself: focusing its first control would ring it before the learner does anything.
    dialog.focus();
    wheel?.sync();
  }

  function showBirthDateForm() {
    description.hidden = true;
    back.textContent = 'Hủy';
    form.hidden = false;
    submit.hidden = false;
    input.max = todayIso();
    open();
  }

  function showRestricted(message) {
    description.textContent = message || 'Bạn chưa đủ tuổi để xem video này.';
    description.hidden = false;
    back.textContent = 'Về thư viện';
    form.hidden = true;
    submit.hidden = true;
    open();
  }

  function show(body) {
    if (body?.code === 'BIRTH_DATE_REQUIRED') showBirthDateForm();
    else if (body?.code === 'AGE_RESTRICTED') showRestricted(body.message);
  }

  function setError(text) {
    error.textContent = text;
    input.setAttribute('aria-invalid', text ? 'true' : 'false');
  }

  async function saveBirthDate(event) {
    event.preventDefault();
    const value = input.value;
    const invalid = input.validity.badInput ? 'Ngày sinh chưa hợp lệ.' : birthDateError(value);
    setError(invalid);
    if (invalid) {
      input.focus();
      return;
    }
    submit.disabled = true;
    try {
      const response = await fetch('/api/auth/profile', {
        method: 'PUT',
        headers: headers(),
        body: JSON.stringify({ dateOfBirth: value })
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || body.success === false) {
        setError(body.message || 'Chưa lưu được ngày sinh. Vui lòng thử lại.');
        return;
      }
      if (body.data) {
        try { localStorage.setItem('user', JSON.stringify(body.data)); } catch (e) { /* cache only */ }
      }
      const stillBlocked = await accessError();
      if (stillBlocked) show(stillBlocked);
      else location.reload();
    } catch (e) {
      setError('Không kết nối được máy chủ. Vui lòng thử lại.');
    } finally {
      submit.disabled = false;
    }
  }

  if (dialog && videoId) {
    // The page behind has no content to use, so Escape must not leave the learner on an empty lesson.
    // Chrome ignores the cancel veto until the page has had a user gesture, hence the reopen on close.
    dialog.addEventListener('cancel', event => event.preventDefault());
    dialog.addEventListener('close', open);
    wheel = root.DateWheel?.attach(input);
    form.addEventListener('submit', saveBirthDate);
    input.addEventListener('change', () => { if (input.getAttribute('aria-invalid') === 'true') setError(birthDateError(input.value)); });
    accessError().then(body => { if (body) show(body); }).catch(() => { /* the page shows its own load error */ });
  }

  return { isGateCode, birthDateError, todayIso };
}));
