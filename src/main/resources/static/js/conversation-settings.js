(function () {
  'use strict';

  // Folding side cards (free chat "Công cụ"): closed on arrival, the heading button opens and closes it.
  document.querySelectorAll('[data-fold]').forEach(function (card) {
    const button = card.querySelector('.side-card__fold');
    const body = card.querySelector('.side-card__fold-body');
    if (!button || !body) return;
    button.addEventListener('click', function () {
      const open = button.getAttribute('aria-expanded') !== 'true';
      button.setAttribute('aria-expanded', String(open));
      body.hidden = !open;
      card.classList.toggle('is-open', open);
    });
  });

  const trigger = document.getElementById('btnConversationSettings');
  const dialog = document.getElementById('conversationSettingsDialog');
  const content = document.getElementById('conversationSettingsContent');
  const closeButton = document.getElementById('btnCloseConversationSettings');
  if (!trigger || !dialog || !content || !closeButton || typeof dialog.showModal !== 'function') return;

  const mobile = window.matchMedia('(max-width: 900px)');
  function sizeConversation() {
    const shell = document.getElementById('jlabBaseContent');
    if (!shell) return;
    if (mobile.matches && window.visualViewport) {
      shell.style.setProperty('--conversation-viewport-height', window.visualViewport.height + 'px');
    } else shell.style.removeProperty('--conversation-viewport-height');
  }
  if (window.visualViewport) window.visualViewport.addEventListener('resize', sizeConversation);
  const cards = Array.from(document.querySelectorAll('[data-conversation-settings], .cv-sidebar .side-card')).map(function (card) {
    const anchor = document.createComment('conversation settings position');
    card.before(anchor);
    return { card: card, anchor: anchor };
  });

  function adapt() {
    sizeConversation();
    if (dialog.open) dialog.close();
    cards.forEach(function (entry) {
      if (mobile.matches) content.append(entry.card);
      else entry.anchor.after(entry.card);
    });
    trigger.hidden = !mobile.matches || cards.length === 0;
  }

  trigger.addEventListener('click', function () {
    if (!mobile.matches || dialog.open) return;
    dialog.showModal();
    trigger.setAttribute('aria-expanded', 'true');
    closeButton.focus();
  });
  closeButton.addEventListener('click', function () { dialog.close(); });
  dialog.addEventListener('close', function () { trigger.setAttribute('aria-expanded', 'false'); });
  dialog.addEventListener('click', function (event) {
    if (event.target !== dialog) return;
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
  });
  mobile.addEventListener('change', adapt);
  adapt();
})();
