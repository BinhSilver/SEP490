/* [JLAS-W0108] jpath-dialog.js — one modal dialog at a time for the JLPT pages.
 *
 * window.JpathDialog.open(doc, html, opener) → { el, close }
 *   - role="dialog" aria-modal="true" aria-labelledby="jp-dialog-title"; html is already escaped by the caller
 *   - a second open() closes the first (no stacked dialogs, no duplicate ids)
 *   - Tab / Shift+Tab wrap inside the dialog, Esc / "Đóng" / backdrop click close it
 *   - focus returns to the opener, or to the element focused before opening
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathDialog = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function () {
  'use strict';

  var FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
    + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
  var current = null;

  function focusables(panel) {
    return Array.prototype.slice.call(panel.querySelectorAll(FOCUSABLE));
  }

  function trap(ev, panel, doc) {
    var items = focusables(panel);
    if (!items.length) { ev.preventDefault(); panel.focus(); return; }
    var first = items[0];
    var last = items[items.length - 1];
    var active = doc.activeElement;
    if (ev.shiftKey && (active === first || active === panel)) { ev.preventDefault(); last.focus(); }
    else if (!ev.shiftKey && active === last) { ev.preventDefault(); first.focus(); }
    else if (items.indexOf(active) < 0 && active !== panel) { ev.preventDefault(); first.focus(); }
  }

  function open(doc, html, opener) {
    if (!doc || !doc.body) return null;
    if (current) current.close(false);
    var back = opener || doc.activeElement;
    var box = doc.createElement('div');
    box.className = 'jp-dialog-backdrop';
    box.innerHTML = '<div class="jp-dialog jp-card" role="dialog" aria-modal="true" aria-labelledby="jp-dialog-title"'
      + ' tabindex="-1">' + html + '<button type="button" class="jp-btn" data-dialog-close>Đóng</button></div>';
    var panel = box.firstChild;
    var handle = { el: panel, close: close };

    function onKey(ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); close(true); }
      else if (ev.key === 'Tab') trap(ev, panel, doc);
    }
    function close(restore) {
      doc.removeEventListener('keydown', onKey, true);
      if (box.parentNode) box.parentNode.removeChild(box);
      if (current === handle) current = null;
      if (restore !== false && back && typeof back.focus === 'function') back.focus();
    }

    box.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t === box || (t && typeof t.closest === 'function' && t.closest('[data-dialog-close]'))) close(true);
    });
    doc.addEventListener('keydown', onKey, true);
    doc.body.appendChild(box);
    current = handle;
    panel.focus();
    return handle;
  }

  return { open: open, isOpen: function () { return !!current; } };
});
