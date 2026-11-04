/* [JLAS-W0108] jpath-report.js — "Báo sai" for JLPT path content.
 *
 * window.JpathReport = { ERROR_TYPES, buildPayload(ctx, errorType, note), messageFor(error), parseAttach(json),
 *                        attach(el, ctx), bindAll(root) }
 *   - A page marks a block with data-jpath-report='{"targetType","targetCode","pageContext"}' (itemType/itemCode
 *     optional hints; the server always derives the owning item). bindAll(root) adds one ⚑ button per block; it runs
 *     on DOMContentLoaded and again on every document 'jpath:rendered' {detail:{root}}.
 *   - The ⚑ button opens a dialog (role="dialog" class="jp-report no-inline-dictionary", bottom sheet ≤ 640 px):
 *     error type, note (≤ 500), Gửi. POST /api/learning/jlpt/reports through window.JpathApi. Esc closes it.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathReport = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var REPORT_URL = '/api/learning/jlpt/reports';
  var NOTE_MAX = 500;
  var TARGETS = ['VOCAB', 'KANJI', 'GRAMMAR', 'KANA', 'EXAMPLE', 'QUESTION'];
  var CONTEXT_RE = /^[A-Z_]{1,40}$/;
  var ERROR_TYPES = Object.freeze([
    Object.freeze({ code: 'MEANING', label: 'Nghĩa sai' }),
    Object.freeze({ code: 'EXAMPLE', label: 'Ví dụ sai' }),
    Object.freeze({ code: 'AUDIO', label: 'Audio lỗi' }),
    Object.freeze({ code: 'KANJI', label: 'Kanji sai' }),
    Object.freeze({ code: 'OTHER', label: 'Khác' })
  ]);
  var MSG_THANKS = 'Cảm ơn bạn! Người biên soạn sẽ xem lại.';
  var MSG_DUPLICATE = 'Bạn đã báo sai mục này, người biên soạn đang xem.';
  var MSG_LIMIT = 'Bạn đã gửi 20 báo lỗi hôm nay. Hãy thử lại vào ngày mai.';
  var MSG_NOT_FOUND = 'Không tìm thấy nội dung này nữa.';
  var MSG_FAILED = 'Chưa gửi được báo lỗi. Thử lại sau.';
  var BOUND = 'data-jpath-report-bound';
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  function isErrorType(code) {
    for (var i = 0; i < ERROR_TYPES.length; i++) if (ERROR_TYPES[i].code === code) return true;
    return false;
  }

  /** Request body of POST /reports. Throws when the target or the error type is missing. */
  function buildPayload(ctx, errorType, note) {
    if (!ctx || !ctx.targetType || !ctx.targetCode) throw new Error('report target is required');
    if (!isErrorType(errorType)) throw new Error('error type is required');
    var text = typeof note === 'string' ? note.trim() : '';
    if (text.length > NOTE_MAX) text = text.slice(0, NOTE_MAX);
    return {
      targetType: ctx.targetType,
      targetCode: ctx.targetCode,
      itemType: ctx.itemType || null,
      itemCode: ctx.itemCode || null,
      errorType: errorType,
      note: text ? text : null,
      pageContext: ctx.pageContext || null
    };
  }

  /** Learner-facing text for a JpathApi failure ({code, message}). */
  function messageFor(error) {
    var code = error && error.code;
    if (code === 'JPATH_REPORT_DUPLICATE') return MSG_DUPLICATE;
    if (code === 'TOO_MANY_REQUESTS') return MSG_LIMIT;
    if (code === 'JPATH_ITEM_NOT_FOUND') return MSG_NOT_FOUND;
    if (code === 'UNAUTHORIZED' && error.message) return error.message;
    return MSG_FAILED;
  }

  /** The data-jpath-report value as a context, or null when it is not a usable target. */
  function parseAttach(json) {
    var raw;
    try {
      raw = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (e) {
      return null;
    }
    if (!raw || typeof raw !== 'object') return null;
    var type = typeof raw.targetType === 'string' ? raw.targetType.trim().toUpperCase() : '';
    var code = typeof raw.targetCode === 'string' ? raw.targetCode.trim() : '';
    if (TARGETS.indexOf(type) < 0 || !code || code.length > 24) return null;
    var out = { targetType: type, targetCode: code };
    if (typeof raw.pageContext === 'string' && CONTEXT_RE.test(raw.pageContext)) out.pageContext = raw.pageContext;
    if (typeof raw.itemType === 'string' && raw.itemType) out.itemType = raw.itemType;
    if (typeof raw.itemCode === 'string' && raw.itemCode) out.itemCode = raw.itemCode;
    return out;
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function formHtml() {
    var options = ERROR_TYPES.map(function (t, i) {
      return '<label class="jp-report__option"><input type="radio" name="jp-report-type" value="' + t.code + '"'
        + (i === 0 ? ' checked' : '') + '><span>' + esc(t.label) + '</span></label>';
    }).join('');
    return '<div class="jp-report__panel">'
      + '<h2 class="jp-report__title" id="jp-report-title">Báo sai nội dung</h2>'
      + '<form class="jp-report__form" novalidate>'
      + '<fieldset class="jp-report__types"><legend>Lỗi gì?</legend>' + options + '</fieldset>'
      + '<label class="jp-report__note-label" for="jp-report-note">Ghi chú (không bắt buộc)</label>'
      + '<textarea id="jp-report-note" class="jp-report__note" maxlength="' + NOTE_MAX + '" rows="3"></textarea>'
      + '<p class="jp-report__status" role="status" aria-live="polite"></p>'
      + '<div class="jp-report__actions">'
      + '<button type="button" class="jp-report__action" data-report-close>Đóng</button>'
      + '<button type="submit" class="jp-report__action jp-report__action--primary" data-report-send>Gửi</button>'
      + '</div></form></div>';
  }

  var current = null;

  function apiClient() {
    return (root && root.JpathApi) || (typeof require === 'function' ? require('./jpath-api.js') : null);
  }

  /** Opens the report dialog for ctx; a second open closes the first. Returns { el, close }. */
  function open(ctx, opener) {
    var doc = root && root.document;
    if (!doc || !doc.body) return null;
    if (current) current.close(false);
    var box = doc.createElement('div');
    box.className = 'jp-report no-inline-dictionary';
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-labelledby', 'jp-report-title');
    box.innerHTML = formHtml();
    var handle = { el: box, close: close };
    var form = box.querySelector('form');
    var status = box.querySelector('.jp-report__status');
    var send = box.querySelector('[data-report-send]');

    function onKey(ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); close(true); }
    }
    function close(restore) {
      doc.removeEventListener('keydown', onKey, true);
      if (box.parentNode) box.parentNode.removeChild(box);
      if (current === handle) current = null;
      if (restore !== false && opener && typeof opener.focus === 'function') opener.focus();
    }

    box.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t === box || (t && typeof t.closest === 'function' && t.closest('[data-report-close]'))) close(true);
    });
    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var picked = form.querySelector('input[name="jp-report-type"]:checked');
      var body = buildPayload(ctx, picked ? picked.value : 'OTHER', form.querySelector('textarea').value);
      send.disabled = true;
      status.textContent = 'Đang gửi…';
      apiClient().send('POST', REPORT_URL, body).then(function () {
        status.textContent = MSG_THANKS;
        form.querySelector('.jp-report__types').disabled = true;
        send.hidden = true;
      }, function (error) {
        status.textContent = messageFor(error);
        send.disabled = error && error.code === 'JPATH_REPORT_DUPLICATE';
      });
    });
    doc.addEventListener('keydown', onKey, true);
    doc.body.appendChild(box);
    current = handle;
    var first = box.querySelector('input');
    if (first) first.focus();
    return handle;
  }

  /** Adds one ⚑ button to el for ctx; a second call on the same element does nothing. Returns the button. */
  function attach(el, ctx) {
    if (!el || !ctx || el.getAttribute(BOUND) === '1') return null;
    var doc = el.ownerDocument || (root && root.document);
    var button = doc.createElement('button');
    button.type = 'button';
    button.className = 'jp-report-btn';
    button.setAttribute('aria-label', 'Báo sai');
    button.setAttribute('title', 'Báo sai');
    button.innerHTML = icon('flag');
    button.addEventListener('click', function (ev) {
      if (ev && typeof ev.stopPropagation === 'function') ev.stopPropagation();
      open(ctx, button);
    });
    el.appendChild(button);
    el.setAttribute(BOUND, '1');
    return button;
  }

  /** Attaches every [data-jpath-report] block under root (root itself included). Returns the number attached. */
  function bindAll(scope) {
    if (!scope) return 0;
    var nodes = [];
    if (typeof scope.getAttribute === 'function' && scope.getAttribute('data-jpath-report') !== null) nodes.push(scope);
    if (typeof scope.querySelectorAll === 'function') {
      Array.prototype.push.apply(nodes, Array.prototype.slice.call(scope.querySelectorAll('[data-jpath-report]')));
    }
    var count = 0;
    nodes.forEach(function (el) {
      if (el.getAttribute(BOUND) === '1') return;
      var ctx = parseAttach(el.getAttribute('data-jpath-report'));
      if (ctx && attach(el, ctx)) count++;
    });
    return count;
  }

  var doc = root && root.document;
  if (doc && typeof doc.addEventListener === 'function') {
    doc.addEventListener('jpath:rendered', function (ev) { bindAll(ev && ev.detail && ev.detail.root); });
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', function () { bindAll(doc.body); });
    else if (doc.body) bindAll(doc.body);
  }

  return {
    ERROR_TYPES: ERROR_TYPES,
    buildPayload: buildPayload,
    messageFor: messageFor,
    parseAttach: parseAttach,
    attach: attach,
    bindAll: bindAll
  };
});
