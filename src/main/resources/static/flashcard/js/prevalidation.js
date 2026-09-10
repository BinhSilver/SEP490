/* BR-11 — Pre-validation Highlight realtime.
 * Highlight viền đỏ ngay khi user gõ input không hợp lệ (không đợi server).
 * Dùng cho: editor (set title, card front/back), set page (set title/desc),
 * shadowing editor (video title).
 */
(function (root) {
  'use strict';

  /**
   * Validate 1 input theo rule. Trả về { ok: boolean, message?: string }.
   * Rule có thể là:
   *   - string: tên rule có sẵn ('required', 'min:N', 'max:N')
   *   - function(value)  {ok, message}
   *   - array các rule trên (kết hợp AND)
   */
  function validate(value, rule) {
    const v = (value || '').toString();
    if (typeof rule === 'function') {
      return rule(v) || { ok: true };
    }
    if (Array.isArray(rule)) {
      for (let i = 0; i < rule.length; i++) {
        const r = validate(v, rule[i]);
        if (!r.ok) return r;
      }
      return { ok: true };
    }
    if (typeof rule === 'string') {
      if (rule === 'required') {
        return v.trim() ? { ok: true } : { ok: false, message: 'Không được để trống' };
      }
      const m = rule.match(/^(min|max):(\d+)$/);
      if (m) {
        const n = parseInt(m[2], 10);
        if (m[1] === 'min') {
          return v.trim().length >= n
            ? { ok: true }
            : { ok: false, message: `Tối thiểu ${n} ký tự` };
        } else {
          return v.length <= n
            ? { ok: true }
            : { ok: false, message: `Tối đa ${n} ký tự` };
        }
      }
    }
    return { ok: true };
  }

  /**
   * Bind realtime validation cho 1 input/textarea.
   * @param {HTMLElement} el input/textarea
   * @param {string|Function|Array} rule
   * @param {object} [opts]
   * @param {HTMLElement} [opts.errorEl] element hiển thị message
   */
  function bind(el, rule, opts) {
    if (!el) return;
    opts = opts || {};
    const errorEl = opts.errorEl;
    const wrap = el.closest('.input-wrap') || el.parentElement;

    function setVisual(ok, message) {
      el.classList.toggle('is-invalid', !ok);
      wrap.classList.toggle('is-invalid', !ok);
      if (errorEl) {
        errorEl.hidden = !!ok;
        errorEl.textContent = ok ? '' : (message || '');
      } else if (message) {
        el.setAttribute('title', message);
      }
    }

    function run() {
      const r = validate(el.value, rule);
      setVisual(r.ok, r.message);
      return r.ok;
    }

    el.addEventListener('input', run);
    el.addEventListener('blur', run);
    // Run ngay để áp dụng cho field đã có sẵn (sau khi render lại)
    run();
    return { revalidate: run };
  }

  /**
   * Validate toàn bộ form. Trả về true nếu tất cả ok, ngược lại scroll đến
   * input đầu tiên invalid và highlight.
   * @param {HTMLElement} formEl
   */
  function validateForm(formEl) {
    const inputs = formEl.querySelectorAll('[data-validate]');
    let firstBad = null;
    let ok = true;
    inputs.forEach(el => {
      const rule = el.getAttribute('data-validate');
      const r = validate(el.value, rule);
      el.classList.toggle('is-invalid', !r.ok);
      const wrap = el.closest('.input-wrap') || el.parentElement;
      wrap.classList.toggle('is-invalid', !r.ok);
      if (!r.ok) {
        ok = false;
        if (!firstBad) firstBad = el;
      }
    });
    if (firstBad) {
      firstBad.scrollIntoView({ behavior: 'smooth', block: 'center' });
      try { firstBad.focus(); } catch (_) { /* noop */ }
    }
    return ok;
  }

  root.PreValidation = { bind, validateForm, validate };
})(window);