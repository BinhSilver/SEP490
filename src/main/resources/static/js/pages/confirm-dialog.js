/* confirm-dialog.js — minimal, accessible Promise-based replacement for native window.confirm.
 *
 * Native confirm() is jarring, visually inconsistent with the app and can be blocked by the browser.
 * This renders a small in-page modal and resolves true/false, so call sites keep their "did the user
 * agree?" shape but go through `await window.JlasConfirm(message)` instead of a synchronous confirm.
 *
 * Accessibility (beyond the native dialog it replaces):
 *   - role=dialog + aria-modal + an accessible name (aria-labelledby -> the message, with an
 *     aria-label fallback), so a screen reader announces the actual question.
 *   - focus is moved to the primary action on open, trapped between the two buttons with Tab/Shift+Tab,
 *     and returned to the previously focused element when the dialog closes.
 *   - Escape and clicking the backdrop cancel (resolve false).
 *
 * The B5 theme layer owns the visual treatment: restyle the `.jlas-confirm*` hooks (or inject a styled
 * theme variant) without touching the call sites. Safe default: resolve false if a second dialog is
 * opened while one is already up (replaces the browser's one-dialog-at-a-time behaviour).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root ? root.document : null);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasConfirm = api.confirm;
})(typeof window !== 'undefined' ? window : null, function (doc) {
  'use strict';

  var active = null;
  var MESSAGE_ID = 'jlas-confirm-message';
  var FOCUSABLE = '.jlas-confirm__ok, .jlas-confirm__cancel';

  function remove() {
    if (active && active.el) {
      active.el.remove();
      doc.removeEventListener('keydown', onKey, true);
    }
  }

  function resolveAndClose(result) {
    var resolve = active && active.resolve;
    var previouslyFocused = active && active.previouslyFocused;
    remove();
    active = null;
    // Return focus to where it was before the dialog opened, so keyboard users are not stranded.
    if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
      previouslyFocused.focus();
    }
    if (resolve) resolve(result);
  }

  function onKey(e) {
    if (e.key === 'Escape') { e.preventDefault(); resolveAndClose(false); return; }
    if (e.key === 'Tab' && active) { trapFocus(e); }
  }

  function trapFocus(e) {
    var focusables = active.el.querySelectorAll(FOCUSABLE);
    if (!focusables.length) return;
    var first = focusables[0];
    var last = focusables[focusables.length - 1];
    var current = doc.activeElement;
    var inside = active.el.contains && active.el.contains(current);
    if (e.shiftKey) {
      if (!inside || current === first) { e.preventDefault(); last.focus(); }
    } else if (!inside || current === last) {
      e.preventDefault(); first.focus();
    }
  }

  function build(message, opts) {
    var rootEl = doc.createElement('div');
    rootEl.className = 'jlas-confirm';
    rootEl.setAttribute('role', 'dialog');
    rootEl.setAttribute('aria-modal', 'true');
    rootEl.setAttribute('aria-label', opts.label || 'Xác nhận');
    rootEl.setAttribute('aria-labelledby', MESSAGE_ID);
    rootEl.innerHTML =
      '<div class="jlas-confirm__backdrop"></div>' +
      '<div class="jlas-confirm__panel" role="document">' +
        '<p class="jlas-confirm__message" id="' + MESSAGE_ID + '"></p>' +
        '<div class="jlas-confirm__actions">' +
          '<button type="button" class="jlas-confirm__cancel"></button>' +
          '<button type="button" class="jlas-confirm__ok"></button>' +
        '</div>' +
      '</div>';
    rootEl.querySelector('.jlas-confirm__message').textContent = message;
    rootEl.querySelector('.jlas-confirm__ok').textContent = opts.confirmText || 'OK';
    rootEl.querySelector('.jlas-confirm__cancel').textContent = opts.cancelText || 'Hủy';
    return rootEl;
  }

  function ensureStyles() {
    if (doc.getElementById('jlas-confirm-styles')) return;
    var style = doc.createElement('style');
    style.id = 'jlas-confirm-styles';
    // Minimal neutral styling; overridable by the B5 theme without touching the script.
    // max-width + side margin keep the dialog usable at phone width (no horizontal scroll).
    style.textContent =
      '.jlas-confirm{position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;}' +
      '.jlas-confirm__backdrop{position:absolute;inset:0;background:rgba(15,23,42,.45);}' +
      '.jlas-confirm__panel{position:relative;max-width:min(24rem,calc(100vw - 2rem));margin:1rem;background:#fff;color:#0f172a;' +
        'border-radius:12px;padding:1.25rem;box-shadow:0 20px 50px rgba(0,0,0,.25);font-family:inherit;}' +
      '.jlas-confirm__message{margin:0 0 1rem;line-height:1.5;white-space:pre-line;}' +
      '.jlas-confirm__actions{display:flex;justify-content:flex-end;gap:.5rem;}' +
      '.jlas-confirm__actions button{font:inherit;font-weight:600;padding:.5rem .9rem;border-radius:8px;' +
        'border:1px solid transparent;cursor:pointer;}' +
      '.jlas-confirm__cancel{background:#f1f5f9;color:#334155;}' +
      '.jlas-confirm__ok{background:#2563eb;color:#fff;}' +
      '.jlas-confirm__actions button:focus-visible{outline:2px solid #2563eb;outline-offset:2px;}';
    doc.head.appendChild(style);
  }

  function confirm(message, opts) {
    opts = opts || {};
    if (active) resolveAndClose(false); // one dialog at a time; close the stale one as "cancelled".
    ensureStyles();
    var previouslyFocused = doc.activeElement;
    var rootEl = build(String(message), opts);
    doc.body.appendChild(rootEl);
    var ok = rootEl.querySelector('.jlas-confirm__ok');
    var cancel = rootEl.querySelector('.jlas-confirm__cancel');
    var promise = new Promise(function (resolve) {
      active = { el: rootEl, resolve: resolve, previouslyFocused: previouslyFocused };
    });
    ok.addEventListener('click', function () { resolveAndClose(true); });
    cancel.addEventListener('click', function () { resolveAndClose(false); });
    rootEl.querySelector('.jlas-confirm__backdrop').addEventListener('click', function () { resolveAndClose(false); });
    doc.addEventListener('keydown', onKey, true);
    ok.focus();
    return promise;
  }

  return { confirm: confirm };
});
