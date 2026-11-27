/* Add keyboard containment to the existing dialogs without changing their actions or request payloads. */
(function () {
  'use strict';
  if (!document.body.classList.contains('aicall-standard')) return;
  const entries = [
    ['quotaModal', el => !el.hidden, '#btnCloseQuota'],
    ['muteModal', el => !el.hidden, '#btnCloseMute'],
    ['wordModal', el => el.classList.contains('show'), '#btnCloseWordModal']
  ];
  let current = null;
  entries.forEach(([id, isOpen, closeSelector]) => {
    const el = document.getElementById(id);
    if (!el) return;
    let open = false, returnTo = null;
    const focusable = () => [...el.querySelectorAll('button:not([disabled]),a[href],input:not([disabled]),textarea:not([disabled]),select:not([disabled]),[tabindex="0"]')].filter(node => node.getClientRects().length);
    function sync() {
      const next = isOpen(el);
      if (next === open) return;
      open = next;
      if (open) {
        returnTo = document.activeElement;
        current = { el, closeSelector, focusable };
        el.setAttribute('aria-modal', 'true');
        (el.querySelector(closeSelector) || focusable()[0])?.focus();
      } else {
        if (current?.el === el) current = null;
        if (returnTo?.isConnected) returnTo.focus();
      }
    }
    new MutationObserver(sync).observe(el, { attributes: true, attributeFilter: ['hidden', 'class'] });
    sync();
  });
  document.addEventListener('keydown', event => {
    if (!current) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      current.el.querySelector(current.closeSelector)?.click();
    } else if (event.key === 'Tab') {
      const nodes = current.focusable();
      if (!nodes.length) return;
      const index = nodes.indexOf(document.activeElement);
      if (event.shiftKey && index <= 0) { event.preventDefault(); nodes.at(-1).focus(); }
      else if (!event.shiftKey && (index === nodes.length - 1 || index < 0)) { event.preventDefault(); nodes[0].focus(); }
    }
  }, true);
})();
