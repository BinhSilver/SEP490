/*
 * Popup báo trước số CP sẽ trừ (kế hoạch Pro/CP, mục 2 "Báo giá trước"): tốn bao nhiêu CP, số dư trước và
 * sau khi trừ; thiếu CP thì hiện số còn thiếu kèm nút nạp CP. Một popup dùng chung cho mọi chỗ trừ CP.
 *
 * JlasCpConfirm.open({ title, message, cost, balance, confirmLabel, option }) → Promise<{ confirmed, option }>
 * option (tuỳ chọn): một ô đánh dấu, ví dụ "Tự dùng CP cho các lượt sau trong cuộc gọi này".
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasCpConfirm = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var TOP_UP_URL = '/profile';
  var UPGRADE_URL = '/profile?tab=premium';

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  /** The numbers the popup shows; {@code shortBy} is how many CP are missing (0 when the balance covers it). */
  function summary(cost, balance) {
    var price = Math.max(0, Number(cost) || 0);
    var have = Math.max(0, Number(balance) || 0);
    return { cost: price, balance: have, after: have - price, shortBy: Math.max(0, price - have) };
  }

  function markup(options) {
    var numbers = summary(options.cost, options.balance);
    var enough = numbers.shortBy === 0;
    var rows = '<div><dt>Tốn</dt><dd>' + numbers.cost + ' CP</dd></div>' +
      '<div><dt>Số dư</dt><dd>' + numbers.balance + ' CP' +
      (enough ? ' → <strong>' + numbers.after + ' CP</strong>' : '') + '</dd></div>';
    var option = options.option && enough
      ? '<label class="cp-confirm__option"><input type="checkbox" data-cp-option> ' + escapeHtml(options.option) + '</label>' : '';
    var primary = enough
      ? '<button type="button" class="cp-confirm__primary" data-cp-ok>' + escapeHtml(options.confirmLabel || ('Dùng ' + numbers.cost + ' CP')) + '</button>'
      : '<a class="cp-confirm__primary" href="' + TOP_UP_URL + '">Nạp CP</a>';
    return '<div class="cp-confirm__dialog" role="dialog" aria-modal="true" aria-labelledby="cpConfirmTitle">' +
      '<h2 id="cpConfirmTitle">' + escapeHtml(options.title || 'Xác nhận dùng CP') + '</h2>' +
      (options.message ? '<p>' + escapeHtml(options.message) + '</p>' : '') +
      '<dl class="cp-confirm__summary">' + rows + '</dl>' +
      (enough ? '' : '<p class="cp-confirm__short">Bạn còn thiếu <strong>' + numbers.shortBy + ' CP</strong>.</p>') +
      option +
      '<div class="cp-confirm__actions"><button type="button" data-cp-cancel>Huỷ</button>' +
      '<a href="' + UPGRADE_URL + '">Nâng cấp Pro</a>' + primary + '</div></div>';
  }

  function open(options) {
    return new Promise(function (resolve) {
      var overlay = document.createElement('div');
      overlay.className = 'cp-confirm';
      overlay.innerHTML = markup(options || {});
      (document.fullscreenElement || document.body).appendChild(overlay);
      var previous = document.activeElement;
      function finish(confirmed) {
        var box = overlay.querySelector('[data-cp-option]');
        document.removeEventListener('keydown', onKey);
        overlay.remove();
        if (previous && typeof previous.focus === 'function') previous.focus({ preventScroll: true });
        resolve({ confirmed: confirmed, option: !!(box && box.checked) });
      }
      function onKey(event) { if (event.key === 'Escape') finish(false); }
      overlay.addEventListener('click', function (event) {
        if (event.target === overlay || event.target.closest('[data-cp-cancel]')) finish(false);
        else if (event.target.closest('[data-cp-ok]')) finish(true);
      });
      document.addEventListener('keydown', onKey);
      var focus = overlay.querySelector('[data-cp-ok]') || overlay.querySelector('[data-cp-cancel]');
      if (focus) focus.focus();
    });
  }

  return { summary: summary, markup: markup, open: open };
});
