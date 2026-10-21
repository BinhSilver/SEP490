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

  // Furigana lives in <rt>/<rp>; selecting 漢字 over ruby must look up 漢字, not 漢字かんじ.
  function rangeText(range) {
    var fragment = range.cloneContents();
    fragment.querySelectorAll('rt,rp').forEach(function (node) { node.remove(); });
    return fragment.textContent;
  }

  function isEligibleNode(node, text) {
    if (!node || !normalizeSelection(text) || !/[\u3040-\u30ff\u3400-\u9fff]/u.test(text)) return false;
    if (node.closest('input,textarea,[contenteditable="true"],[data-inline-private],.no-inline-dictionary')) return false;
    return !!node.closest('[data-inline-dictionary]');
  }

  // Nút lưu là nút bật/tắt: đã lưu thì bấm lần nữa để bỏ lưu.
  function saveButtonMarkup(saved) {
    return '<i class="' + (saved ? 'fa-solid' : 'fa-regular') + ' fa-bookmark" aria-hidden="true"></i> ' +
      (saved ? 'Đã lưu' : 'Lưu từ');
  }

  // Câu ví dụ chỉ có ở một phần từ vựng; không có thì không chừa khung trống.
  function exampleMarkup(word) {
    if (!word.exampleJa && !word.exampleVi) return '';
    return '<div class="inline-dict__example">' +
      (word.exampleJa ? '<p lang="ja">' + escapeHtml(word.exampleJa) + '</p>' : '') +
      (word.exampleVi ? '<p class="inline-dict__example-vi">' + escapeHtml(word.exampleVi) + '</p>' : '') + '</div>';
  }

  function popupMarkup(word) {
    var saved = word.saved === true;
    return '<div class="inline-dict__word"><strong lang="ja">' + escapeHtml(word.expression) + '</strong>' +
      '<span lang="ja">' + escapeHtml(word.reading || 'Chưa có cách đọc') + '</span>' +
      (word.jlptLevel ? '<span class="inline-dict__jlpt" title="Cấp độ JLPT">' + escapeHtml(word.jlptLevel) + '</span>' : '') +
      '</div>' +
      '<p class="inline-dict__meaning">' + escapeHtml(word.meaning || 'Chưa có nghĩa tóm tắt') + '</p>' +
      '<p class="inline-dict__type">' + escapeHtml(word.wordType || 'Chưa phân loại') + '</p>' +
      exampleMarkup(word) +
      '<div class="inline-dict__actions"><button type="button" data-inline-save="' +
      escapeHtml(word.vocabularyId) + '" aria-pressed="' + saved + '" title="' +
      (saved ? 'Bấm để bỏ lưu' : 'Lưu vào danh sách yêu thích') + '"' + (saved ? ' class="is-saved"' : '') + '>' +
      saveButtonMarkup(saved) + '</button>' +
      // Sổ tay (Sổ tay của tôi, Sổ tay điểm yếu, sổ tự tạo) là tính năng riêng với danh sách yêu thích.
      '<button type="button" class="inline-dict__notebook-btn" data-inline-notebook-open="' + escapeHtml(word.vocabularyId) +
      '" aria-expanded="false"><i class="fa-regular fa-folder-open" aria-hidden="true"></i> Sổ tay</button>' +
      '<a href="' + escapeHtml(word.detailUrl) +
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
    // Focus goes to the panel itself (no ring) so the save button is not outlined after a mouse selection;
    // keyboard users still Tab into the buttons and press Escape to close.
    popup.tabIndex = -1;
    document.body.appendChild(popup);
    popup.addEventListener('click', function (event) {
      var save = event.target.closest('[data-inline-save]');
      if (!save) return;
      var notebookButton = event.target.closest('[data-inline-notebook-open]');
      if (notebookButton) { openNotebooks(notebookButton); return; }
      var save = event.target.closest('[data-inline-save]');
      if (!save) return;
      // Đổi nút ngay khi bấm (không chờ máy chủ); syncSaved gửi request phía sau và hoàn tác nếu lỗi.
      popup.querySelector('.inline-dict__status').textContent = '';
      setSaved(save, save.getAttribute('aria-pressed') !== 'true');
      syncSaved(save, popup.querySelector('.inline-dict__status'));
    });
    return popup;
  }

  // Đưa máy chủ về đúng trạng thái nút đang hiện. Bấm liên tục khi request trước chưa xong thì chỉ gửi thêm
  // khi trạng thái cuối khác máy chủ, nên không có request thừa và không bị lệch.
  /** Mở (hoặc đóng) danh sách sổ tay ngay trong popup, chọn một sổ để thêm từ này vào. */
  async function openNotebooks(button) {
    var status = popup.querySelector('.inline-dict__status');
    var open = popup.querySelector('.inline-dict__notebooks');
    if (open) { open.remove(); button.setAttribute('aria-expanded', 'false'); return; }
    if (button.disabled) return;
    button.disabled = true;
    status.textContent = '';
    try {
      var notebooks = await request('/api/dictionary/notebooks');
      // Popup có thể đã chuyển sang từ khác trong lúc chờ.
      if (!popup.contains(button)) return;
      var choices = notebooks.map(function (n) {
        return '<button type="button" data-inline-notebook="' + escapeHtml(n.id) + '">' + escapeHtml(n.name) +
          '<small>' + escapeHtml(n.wordCount) + ' từ</small></button>';
      }).join('');
      var chooser = document.createElement('div');
      chooser.className = 'inline-dict__notebooks';
      chooser.innerHTML = '<strong>Chọn sổ tay</strong>' + choices;
      popup.appendChild(chooser);
      button.setAttribute('aria-expanded', 'true');
      chooser.addEventListener('click', async function (event) {
        var choice = event.target.closest('[data-inline-notebook]');
        if (!choice) return;
        choice.disabled = true;
        try {
          await request('/api/dictionary/notebooks/' + encodeURIComponent(choice.dataset.inlineNotebook) + '/entries/' +
            encodeURIComponent(button.dataset.inlineNotebookOpen), { method: 'POST' });
          chooser.remove();
          button.setAttribute('aria-expanded', 'false');
          status.textContent = 'Đã thêm vào ' + choice.firstChild.textContent + '.';
        } catch (error) {
          status.textContent = error.message;
          choice.disabled = false;
        }
      });
    } catch (error) {
      status.textContent = error.message;
    } finally {
      button.disabled = false;
    }
  }

  async function syncSaved(button, status) {
    if (button.syncing) return;
    button.syncing = true;
    var url = '/api/dictionary/favorites/' + encodeURIComponent(button.dataset.inlineSave);
    try {
      while (button.serverSaved !== (button.getAttribute('aria-pressed') === 'true')) {
        var want = button.getAttribute('aria-pressed') === 'true';
        await request(url, { method: want ? 'POST' : 'DELETE' });
        button.serverSaved = want;
      }
    } catch (error) {
      setSaved(button, button.serverSaved);
      status.textContent = error.message;
    }
    button.syncing = false;
  }

  function setSaved(button, saved) {
    button.setAttribute('aria-pressed', String(saved));
    button.classList.toggle('is-saved', saved);
    button.title = saved ? 'Bấm để bỏ lưu' : 'Lưu vào danh sách yêu thích';
    button.innerHTML = saveButtonMarkup(saved);
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
    var text = normalizeSelection(rangeText(selection.getRangeAt(0)));
    var node = selection.anchorNode && (selection.anchorNode.nodeType === 3 ? selection.anchorNode.parentElement : selection.anchorNode);
    if (!isEligibleNode(node, text)) return;
    var rect = selection.getRangeAt(0).getBoundingClientRect();
    var current = ++requestNumber;
    try {
      var word = await request('/api/dictionary/inline?q=' + encodeURIComponent(text));
      if (current !== requestNumber) return;
      var panel = ensurePopup();
      // In native fullscreen (Shadowing focus mode) only the fullscreen element renders.
      var host = document.fullscreenElement || document.body;
      if (panel.parentNode !== host) host.appendChild(panel);
      panel.innerHTML = popupMarkup(word); panel.hidden = false; position(rect);
      var saveButton = panel.querySelector('[data-inline-save]');
      saveButton.serverSaved = word.saved === true;
      panel.focus({ preventScroll: true });
    } catch (error) { close(); }
  }

  // Clicks and keys inside the popup (e.g. "Lưu từ") must not look the still-selected text up again:
  // that re-rendered the popup from the server before the save finished, flipping the button back.
  function insidePopup(target) {
    return !!(popup && target && popup.contains(target));
  }

  function mount() {
    document.addEventListener('pointerup', function (event) {
      if (!insidePopup(event.target)) setTimeout(lookupSelection, 0);
    });
    document.addEventListener('keyup', function (event) {
      if (event.key === 'Escape') close();
      else if (insidePopup(event.target)) return;
      else if (event.key === 'Shift' || event.key.startsWith('Arrow')) setTimeout(lookupSelection, 0);
    });
    document.addEventListener('pointerdown', function (event) {
      if (popup && !popup.hidden && !popup.contains(event.target)) close();
    });
  }

  return { escapeHtml: escapeHtml, normalizeSelection: normalizeSelection, rangeText: rangeText, isEligibleNode: isEligibleNode,
    popupMarkup: popupMarkup, saveButtonMarkup: saveButtonMarkup, syncSaved: syncSaved, setSaved: setSaved, mount: mount };
});
