(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasInlineDictionary = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  var popup;
  var requestNumber = 0;

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  function normalizeSelection(value) {
    var text = String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
    return text.length > 0 && text.length <= 100 ? text : '';
  }

  function isEligibleNode(node, text) {
    if (!node || !normalizeSelection(text) || !/[\u3040-\u30ff\u3400-\u9fff]/u.test(text)) return false;
    if (node.closest('input,textarea,[contenteditable="true"],[data-inline-private],.no-inline-dictionary')) return false;
    return !!node.closest('[data-inline-dictionary]');
  }

  function popupMarkup(word) {
    return '<button class="inline-dict__close" type="button" aria-label="Đóng chú thích">×</button>' +
      '<div class="inline-dict__word"><strong lang="ja">' + escapeHtml(word.expression) + '</strong>' +
      '<span lang="ja">' + escapeHtml(word.reading || 'Chưa có cách đọc') + '</span></div>' +
      '<p class="inline-dict__meaning">' + escapeHtml(word.meaning || 'Chưa có nghĩa tóm tắt') + '</p>' +
      '<p class="inline-dict__type">' + escapeHtml(word.wordType || 'Chưa phân loại') + '</p>' +
      '<div class="inline-dict__actions"><button type="button" data-inline-save="' +
      escapeHtml(word.vocabularyId) + '">Lưu từ</button><a href="' + escapeHtml(word.detailUrl) +
      '">Mở từ điển</a></div><p class="inline-dict__status" role="status" aria-live="polite"></p>';
  }

  async function request(url, options) {
    var response = window.JlasAuth && window.JlasAuth.authFetch
      ? await window.JlasAuth.authFetch(url, options || {}) : await fetch(url, options || {});
    var payload = await response.json();
    if (!response.ok || !payload.success) throw new Error(payload.message || 'Không thể tra từ lúc này.');
    return payload.data;
  }

  function ensurePopup() {
    if (popup) return popup;
    popup = document.createElement('aside');
    popup.className = 'inline-dict';
    popup.setAttribute('role', 'dialog');
    popup.setAttribute('aria-label', 'Chú thích từ vựng');
    popup.hidden = true;
    document.body.appendChild(popup);
    popup.addEventListener('click', async function (event) {
      if (event.target.closest('.inline-dict__close')) { close(); return; }
      var save = event.target.closest('[data-inline-save]');
      if (!save) return;
      var status = popup.querySelector('.inline-dict__status');
      save.disabled = true;
      try {
        await request('/api/dictionary/favorites/' + encodeURIComponent(save.dataset.inlineSave), { method: 'POST' });
        save.textContent = 'Đã lưu';
        status.textContent = 'Từ này đã có trong danh sách yêu thích.';
      } catch (error) { status.textContent = error.message; save.disabled = false; }
    });
    return popup;
  }

  function position(rect) {
    var panel = ensurePopup();
    if (matchMedia('(max-width: 640px)').matches) { panel.style.left = ''; panel.style.top = ''; return; }
    var left = Math.min(window.innerWidth - 340, Math.max(12, rect.left + rect.width / 2 - 160));
    var top = rect.bottom + 10;
    panel.style.left = left + 'px'; panel.style.top = top + 'px';
  }

  function close() { if (popup) popup.hidden = true; }

  async function lookupSelection() {
    var selection = window.getSelection && window.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return;
    var text = normalizeSelection(selection.toString());
    var node = selection.anchorNode && (selection.anchorNode.nodeType === 3 ? selection.anchorNode.parentElement : selection.anchorNode);
    if (!isEligibleNode(node, text)) return;
    var rect = selection.getRangeAt(0).getBoundingClientRect();
    var current = ++requestNumber;
    try {
      var word = await request('/api/dictionary/inline?q=' + encodeURIComponent(text));
      if (current !== requestNumber) return;
      var panel = ensurePopup();
      panel.innerHTML = popupMarkup(word); panel.hidden = false; position(rect);
      panel.querySelector('.inline-dict__close').focus({ preventScroll: true });
    } catch (error) { close(); }
  }

  function mount() {
    document.addEventListener('pointerup', function () { setTimeout(lookupSelection, 0); });
    document.addEventListener('keyup', function (event) {
      if (event.key === 'Escape') close();
      else if (event.key === 'Shift' || event.key.startsWith('Arrow')) setTimeout(lookupSelection, 0);
    });
    document.addEventListener('pointerdown', function (event) {
      if (popup && !popup.hidden && !popup.contains(event.target)) close();
    });
  }

  return { escapeHtml: escapeHtml, normalizeSelection: normalizeSelection, isEligibleNode: isEligibleNode,
    popupMarkup: popupMarkup, mount: mount };
});
