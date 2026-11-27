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
  // "Không hỏi lại hôm nay" (js/components/cp-auto-pay.js).
  var AUTO_PAY = 'quickLookup';
  var skipConfirmMemory = {};

  function skipConfirmKey() {
    var auth = typeof window !== 'undefined' ? window.JlasAuth : null;
    var user = auth && typeof auth.getUser === 'function' ? auth.getUser() : null;
    var uid = user ? (user.id || user.userId || 'me') : 'me';
    return 'jlas_buy_confirm_skip_' + uid + '_quickLookup';
  }

  function isSkipConfirm() {
    var k = skipConfirmKey();
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        return window.localStorage.getItem(k) === '1';
      }
    } catch (ignored) { /* memory fallback */ }
    return skipConfirmMemory[k] === '1';
  }

  function setSkipConfirm(skip) {
    var k = skipConfirmKey();
    var val = skip ? '1' : '0';
    skipConfirmMemory[k] = val;
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        if (skip) window.localStorage.setItem(k, '1');
        else window.localStorage.removeItem(k);
      }
    } catch (ignored) { /* memory fallback */ }
  }

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
    if (!response.ok || !payload.success) {
      var error = new Error(payload.message || 'Không thể tra từ lúc này.');
      error.code = payload.code;
      throw error;
    }
    return payload.data;
  }

  /**
   * Hết lượt tra từ nhanh: báo trước số CP sẽ trừ và số dư sau khi mua, hoặc số CP còn thiếu.
   * {@code allowance} is GET /api/dictionary/inline/allowance.
   */
  function paywallMarkup(allowance) {
    var price = allowance.packPrice;
    var balance = allowance.creditBalance;
    var enough = balance >= price;
    var skip = isSkipConfirm();
    var detail = enough
      ? 'Mua ' + allowance.packLookups + ' lượt tra: <strong>' + price + ' CP</strong>. Số dư: ' + balance + ' CP → ' + (balance - price) + ' CP.'
      : 'Mua ' + allowance.packLookups + ' lượt tra cần ' + price + ' CP. Bạn có ' + balance + ' CP, còn thiếu <strong>' + (price - balance) + ' CP</strong>.';
    var buy = enough
      ? '<button type="button" data-inline-buy data-price="' + escapeHtml(price) + '" data-balance="' + escapeHtml(balance)
        + '" data-lookups="' + escapeHtml(allowance.packLookups) + '">Mua ' + allowance.packLookups + ' lượt (' + price + ' CP)</button>'
      : '<a href="/credits">Nạp CP</a>';
    var auto = typeof window !== 'undefined' ? window.JlasCpAutoPay : null;
    var capNote = auto && auto.capReached(AUTO_PAY)
      ? '<p class="inline-dict__hint">Đã tự dùng đủ ' + auto.DAILY_CAP + ' CP hôm nay nên cần xác nhận lại.</p>' : '';
    var skipHint = enough && skip
      ? '<p class="inline-dict__hint" data-inline-skip-note>Mua 1 chạm đang bật · <button type="button" class="inline-dict__link-btn" data-inline-reset-confirm>Hỏi lại khi mua</button></p>'
      : '';
    var confirmBox = enough
      ? '<div class="inline-dict__confirm" data-inline-confirm-box hidden>'
        + '<p>Xác nhận dùng <strong>' + price + ' CP</strong> mua ' + allowance.packLookups + ' lượt tra từ? (Còn lại: ' + (balance - price) + ' CP)</p>'
        + '<label class="inline-dict__auto"><input type="checkbox" data-inline-skip-confirm> Không hỏi lại lần sau (chỉ cần bấm Mua là xong)</label>'
        + '<div class="inline-dict__actions">'
        + '<button type="button" data-inline-confirm-yes>Xác nhận mua</button>'
        + '<button type="button" class="inline-dict__notebook-btn" data-inline-confirm-no>Hủy</button>'
        + '</div></div>'
      : '';
    var autoOption = auto && enough
      ? '<label class="inline-dict__auto"><input type="checkbox" data-inline-auto> Không hỏi lại hôm nay (tự mua khi hết lượt, tối đa '
        + auto.DAILY_CAP + ' CP/ngày)</label>' : '';
    return '<div class="inline-dict__paywall"><strong>Bạn đã hết lượt tra từ nhanh</strong><p>' + detail + '</p>' + capNote +
      '<p class="inline-dict__hint">Tài khoản thường có 1 lượt dùng thử đầu tiên; tài khoản Cao cấp tra từ không giới hạn. Tra lại cùng từ trong ngày không tốn lượt.</p>' +
      skipHint + '</div>' +
      confirmBox +
      autoOption +
      '<div class="inline-dict__actions" data-inline-main-actions>' + buy + '<a href="/premium">Nâng cấp gói Cao cấp</a></div>' +
      '<p class="inline-dict__status" role="status" aria-live="polite"></p>';
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
      var resetConfirmBtn = event.target.closest('[data-inline-reset-confirm]');
      if (resetConfirmBtn) {
        setSkipConfirm(false);
        var note = popup.querySelector('[data-inline-skip-note]');
        if (note) note.remove();
        var st = popup.querySelector('.inline-dict__status');
        if (st) st.textContent = 'Đã bật lại bước xác nhận trước khi mua.';
        return;
      }
      var confirmYes = event.target.closest('[data-inline-confirm-yes]');
      if (confirmYes) {
        var skipBox = popup.querySelector('[data-inline-skip-confirm]');
        if (skipBox && skipBox.checked) setSkipConfirm(true);
        executeBuyPack(confirmYes);
        return;
      }
      var confirmNo = event.target.closest('[data-inline-confirm-no]');
      if (confirmNo) {
        var cBox = popup.querySelector('[data-inline-confirm-box]');
        var mAct = popup.querySelector('[data-inline-main-actions]');
        if (cBox) cBox.hidden = true;
        if (mAct) mAct.hidden = false;
        return;
      }
      var buyButton = event.target.closest('[data-inline-buy]');
      if (buyButton) { buyPack(buyButton); return; }
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
      chooser.innerHTML = '<strong>Chọn sổ tay</strong><div class="inline-dict__notebook-list">' + choices + '</div>' +
        '<button type="button" data-inline-notebook-new>+ Tạo sổ tay mới</button>' +
        '<form class="inline-dict__create" hidden>' +
        '<label>Tên sổ tay<input name="name" type="text" maxlength="120" required autocomplete="off" placeholder="Ví dụ: Từ vựng N3"></label>' +
        '<div class="inline-dict__create-actions"><button type="submit">Tạo và lưu</button>' +
        '<button type="button" data-inline-notebook-cancel>Hủy</button></div></form>';
      popup.appendChild(chooser);
      button.setAttribute('aria-expanded', 'true');
      var newButton = chooser.querySelector('[data-inline-notebook-new]');
      var form = chooser.querySelector('form');
      var input = form.querySelector('input');
      var submit = form.querySelector('[type="submit"]');
      var cancel = form.querySelector('[data-inline-notebook-cancel]');
      var creating = false;

      function finishSaving(name) {
        chooser.remove();
        button.setAttribute('aria-expanded', 'false');
        status.textContent = 'Đã thêm vào ' + name + '.';
      }

      function showNotebook(book) {
        var choice = document.createElement('button');
        choice.type = 'button';
        choice.dataset.inlineNotebook = String(book.id);
        choice.textContent = book.name;
        var count = document.createElement('small');
        count.textContent = '0 từ';
        choice.appendChild(count);
        chooser.querySelector('.inline-dict__notebook-list').appendChild(choice);
      }

      chooser.addEventListener('click', async function (event) {
        if (event.target.closest('[data-inline-notebook-new]')) {
          form.hidden = false;
          newButton.hidden = true;
          status.textContent = '';
          input.focus();
          return;
        }
        if (event.target.closest('[data-inline-notebook-cancel]')) {
          if (creating) return;
          form.hidden = true;
          newButton.hidden = false;
          status.textContent = '';
          newButton.focus();
          return;
        }
        var choice = event.target.closest('[data-inline-notebook]');
        if (!choice || creating || choice.disabled) return;
        choice.disabled = true;
        try {
          await request('/api/dictionary/notebooks/' + encodeURIComponent(choice.dataset.inlineNotebook) + '/entries/' +
            encodeURIComponent(button.dataset.inlineNotebookOpen), { method: 'POST' });
          finishSaving(choice.firstChild.textContent);
        } catch (error) {
          status.textContent = error.message;
          choice.disabled = false;
        }
      });
      form.addEventListener('submit', async function (event) {
        event.preventDefault();
        if (creating) return;
        var name = input.value.trim();
        if (!name || name.length > 120) {
          status.textContent = 'Tên sổ tay phải từ 1 đến 120 ký tự.';
          input.focus();
          return;
        }
        creating = true;
        submit.disabled = true;
        cancel.disabled = true;
        input.disabled = true;
        status.textContent = 'Đang tạo sổ tay và lưu từ...';
        var created = null;
        try {
          created = await request('/api/dictionary/notebooks', { method: 'POST',
            headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name }) });
          // Keep the new notebook selectable if adding the word fails, so retry never creates a duplicate.
          showNotebook(created);
          form.hidden = true;
          newButton.hidden = false;
          input.value = '';
          await request('/api/dictionary/notebooks/' + encodeURIComponent(created.id) + '/entries/' +
            encodeURIComponent(button.dataset.inlineNotebookOpen), { method: 'POST' });
          finishSaving(created.name);
        } catch (error) {
          status.textContent = created
            ? 'Đã tạo sổ tay. Chưa lưu được từ: ' + error.message + ' Chọn sổ vừa tạo để thử lại.'
            : error.message;
        } finally {
          creating = false;
          submit.disabled = false;
          cancel.disabled = false;
          input.disabled = false;
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
    // Below the selection when it fits, otherwise above it (e.g. Shadowing subtitles near the bottom);
    // if neither side fits, keep the whole panel on screen.
    var height = panel.offsetHeight, margin = 12, gap = 10;
    var top = rect.bottom + gap;
    if (top + height > window.innerHeight - margin) {
      top = rect.top - gap - height >= margin ? rect.top - gap - height
        : Math.max(margin, window.innerHeight - margin - height);
    }
    panel.style.left = left + 'px'; panel.style.top = top + 'px';
  }

  function close() { if (popup) popup.hidden = true; }

  // The text and place of the lookup that hit the paywall, retried after buying a pack.
  var pendingLookup = null;

  function showPanel(markup, rect) {
    var panel = ensurePopup();
    // In native fullscreen (Shadowing focus mode) only the fullscreen element renders.
    var host = document.fullscreenElement || document.body;
    if (panel.parentNode !== host) host.appendChild(panel);
    panel.innerHTML = markup; panel.hidden = false; position(rect);
    panel.focus({ preventScroll: true });
    return panel;
  }

  async function lookupText(text, rect, chargeNote) {
    var current = ++requestNumber;
    try {
      var word = await request('/api/dictionary/inline?q=' + encodeURIComponent(text));
      if (current !== requestNumber) return;
      var panel = showPanel(popupMarkup(word), rect);
      var saveButton = panel.querySelector('[data-inline-save]');
      saveButton.serverSaved = word.saved === true;
      if (chargeNote) panel.querySelector('.inline-dict__status').textContent = chargeNote;
    } catch (error) {
      if (current !== requestNumber || error.code !== 'QUICK_LOOKUP_EXHAUSTED') { close(); return; }
      var auto = window.JlasCpAutoPay;
      if (!chargeNote && auto && auto.covers(AUTO_PAY)) {
        try {
          var pack = await request('/api/dictionary/inline/pack', { method: 'POST' });
          auto.add(AUTO_PAY, pack.packPrice);
          var paid = auto.state(AUTO_PAY);
          await lookupText(text, rect, '−' + pack.packPrice + ' CP: đã tự mua ' + pack.packLookups
            + ' lượt tra (tự dùng hôm nay: ' + paid.spent + '/' + paid.cap + ' CP)');
          return;
        } catch (buyError) {
          // Không đủ CP: tắt tự mua và hiện bảng mua để báo số còn thiếu.
          auto.disable(AUTO_PAY);
        }
      }
      try {
        var allowance = await request('/api/dictionary/inline/allowance');
        if (current !== requestNumber) return;
        pendingLookup = { text: text, rect: rect };
        showPanel(paywallMarkup(allowance), rect);
      } catch (ignored) { close(); }
    }
  }

  function buyPack(button) {
    if (!isSkipConfirm()) {
      var confirmBox = popup && popup.querySelector('[data-inline-confirm-box]');
      var mainActions = popup && popup.querySelector('[data-inline-main-actions]');
      if (confirmBox) {
        confirmBox.hidden = false;
        if (mainActions) mainActions.hidden = true;
        return;
      }
    }
    executeBuyPack(button);
  }

  async function executeBuyPack(button) {
    var status = popup.querySelector('.inline-dict__status');
    var autoBox = popup.querySelector('[data-inline-auto]');
    var auto = window.JlasCpAutoPay;
    button.disabled = true;
    try {
      var pack = await request('/api/dictionary/inline/pack', { method: 'POST' });
      if (auto) {
        if (autoBox && autoBox.checked) {
          auto.enable(AUTO_PAY, pack.packPrice);
          auto.add(AUTO_PAY, pack.packPrice);
        } else {
          auto.disable(AUTO_PAY);
        }
      }
      if (pendingLookup) await lookupText(pendingLookup.text, pendingLookup.rect);
      pendingLookup = null;
    } catch (error) {
      button.disabled = false;
      if (status) status.textContent = error.message;
    }
  }

  async function lookupSelection() {
    var selection = window.getSelection && window.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return;
    var text = normalizeSelection(rangeText(selection.getRangeAt(0)));
    var node = selection.anchorNode && (selection.anchorNode.nodeType === 3 ? selection.anchorNode.parentElement : selection.anchorNode);
    if (!isEligibleNode(node, text)) return;
    await lookupText(text, selection.getRangeAt(0).getBoundingClientRect());
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
    popupMarkup: popupMarkup, paywallMarkup: paywallMarkup, saveButtonMarkup: saveButtonMarkup, syncSaved: syncSaved,
    setSaved: setSaved, isSkipConfirm: isSkipConfirm, setSkipConfirm: setSkipConfirm, mount: mount };
});
