/* [JLAS-W0108] jpath-report.js — "Báo sai" for JLPT path content.
 *
 * window.JpathReport = { ERROR_TYPES, buildPayload(ctx, errorType, note), messageFor(error), parseAttach(json),
 *                        attach(el, ctx), bindAll(root) }
 *   - A page marks a block with data-jpath-report='{"targetType","targetCode","pageContext"}' (itemType/itemCode
 *     optional hints; the server always derives the owning item). bindAll(root) adds one ⚑ button per block; it runs
 *     on DOMContentLoaded and again on every document 'jpath:rendered' {detail:{root}}.
 *   - The ⚑ button opens a dialog (role="dialog" class="jp-report" data-inline-dictionary, bottom sheet ≤ 640 px):
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
  var TARGETS = ['VOCAB', 'KANJI', 'GRAMMAR', 'KANA', 'EXAMPLE', 'QUESTION', 'SKILL_SET', 'SKILL_QUESTION', 'PAGE'];
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

  function formHtml(ctx) {
    var options = ERROR_TYPES.map(function (t, i) {
      return '<label class="jp-report__option"><input type="radio" name="jp-report-type" value="' + t.code + '"'
        + (i === 0 ? ' checked' : '') + '><span>' + esc(t.label) + '</span></label>';
    }).join('');
    return '<div class="jp-report__panel">'
      + '<h2 class="jp-report__title" id="jp-report-title">' + (ctx && ctx.targetType === 'PAGE' ? 'Báo lỗi trang này' : 'Báo sai nội dung') + '</h2>'
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
    box.className = 'jp-report';
    box.setAttribute('data-inline-dictionary', '');
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-labelledby', 'jp-report-title');
    box.innerHTML = formHtml(ctx);
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
        if (root && root.JlasToast && root.JlasToast.show(MSG_THANKS, 'success')) { close(true); return; }
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
  // F33 (owner 10/11): no ⚑ on every row any more; one floating report button per page. A block with
  // data-jpath-report is only marked pickable; the button lights the blocks and the learner clicks the wrong one.
  var PICKABLE = 'jp-report-pickable';
  var picking = null;

  /** Marks one [data-jpath-report] block as pickable (no button). Returns the block, or null. */
  function attach(el, ctx) {
    if (!el || !ctx || el.getAttribute(BOUND) === '1') return null;
    el.setAttribute(BOUND, '1');
    if (el.classList) el.classList.add(PICKABLE);
    return el;
  }

  /** Marks every [data-jpath-report] block under root (root itself included); makes sure the floating button exists. */
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
    ensureFab();
    return count;
  }

  /** A short key for the page (a-z, 0-9, -; at most 24), e.g. /learning/jlpt/day -> "learning-jlpt-day". */
  function pageKey(path) {
    var key = String(path || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    if (key.length > 24) key = key.slice(key.length - 24).replace(/^-+/, '');
    return key || 'home';
  }

  function pageCtx() {
    var loc = root && root.location;
    return { targetType: 'PAGE', targetCode: pageKey(loc && loc.pathname), pageContext: 'PAGE' };
  }

  function visibleBlocks(doc) {
    return Array.prototype.slice.call(doc.querySelectorAll('[data-jpath-report]')).filter(function (el) {
      return !!parseAttach(el.getAttribute('data-jpath-report'))
        && !(typeof el.closest === 'function' && el.closest('[hidden]'))
        && (typeof el.getClientRects !== 'function' || el.getClientRects().length > 0);
    });
  }

  function stopPicking() {
    if (!picking) return;
    var p = picking; picking = null;
    p.doc.removeEventListener('click', p.onClick, true);
    p.doc.removeEventListener('keydown', p.onKey, true);
    if (p.bar.parentNode) p.bar.parentNode.removeChild(p.bar);
    if (p.doc.body.classList) p.doc.body.classList.remove('jp-report-picking');
    p.fab.setAttribute('aria-expanded', 'false');
  }

  /** Floating button: lights the reportable blocks; a page without any opens the whole-page report. */
  function startPicking(doc, fab) {
    if (picking) { stopPicking(); return; }
    var blocks = visibleBlocks(doc);
    if (!blocks.length) { open(pageCtx(), fab); return; }
    var bar = doc.createElement('div');
    bar.className = 'jp-report-pickbar';
    bar.setAttribute('role', 'status');
    bar.innerHTML = '<span>Bấm vào mục bị sai để báo.</span>'
      + '<button type="button" class="jp-report__action" data-report-page>Báo lỗi cả trang</button>'
      + '<button type="button" class="jp-report__action" data-report-cancel>Hủy</button>';
    function onClick(ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;
      if (t.closest('.jp-report-fab')) return;
      if (t.closest('[data-report-cancel]')) { stopPicking(); return; }
      if (t.closest('[data-report-page]')) { stopPicking(); open(pageCtx(), fab); return; }
      if (t.closest('.jp-report-pickbar')) return;
      ev.preventDefault();
      ev.stopPropagation();
      var block = t.closest('[data-jpath-report]');
      var ctx = block ? parseAttach(block.getAttribute('data-jpath-report')) : null;
      stopPicking();
      if (ctx) open(ctx, fab);
    }
    function onKey(ev) { if (ev.key === 'Escape') { ev.preventDefault(); stopPicking(); fab.focus(); } }
    picking = { doc: doc, bar: bar, fab: fab, onClick: onClick, onKey: onKey };
    doc.body.appendChild(bar);
    if (doc.body.classList) doc.body.classList.add('jp-report-picking');
    fab.setAttribute('aria-expanded', 'true');
    doc.addEventListener('click', onClick, true);
    doc.addEventListener('keydown', onKey, true);
  }

  function ensureFab() {
    var doc = root && root.document;
    if (!doc || !doc.body || typeof doc.createElement !== 'function') return null;
    var fab = doc.querySelector ? doc.querySelector('.jp-report-fab') : null;
    if (fab) return fab;
    fab = doc.createElement('button');
    fab.type = 'button';
    fab.className = 'jp-report-fab';
    fab.setAttribute('aria-label', 'Báo lỗi');
    fab.setAttribute('aria-expanded', 'false');
    fab.setAttribute('data-tip', 'Báo lỗi');
    fab.innerHTML = icon('flag');
    fab.addEventListener('click', function () { startPicking(doc, fab); });
    doc.body.appendChild(fab);
    return fab;
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
    bindAll: bindAll,
    pageKey: pageKey
  };
});
