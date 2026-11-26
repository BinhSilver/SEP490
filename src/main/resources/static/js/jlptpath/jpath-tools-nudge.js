/* [JLAS-W0108] jpath-tools-nudge.js — F227 (owner 22/11).
 *
 * "Nhịp học và ngày nghỉ" and "Đã biết trước? Kiểm tra để bỏ qua" live in Cài đặt lộ trình now. The path page only
 * shows a short nudge when there is a reason: a pace warning, a coming week that can be checked and skipped, or —
 * once per device — the hint that these tools moved. While a nudge shows, the Cài đặt menu item carries a dot.
 * Opening Cài đặt lộ trình marks the hint as seen.
 *
 * window.JpathToolsNudge = { nudgeHtml(state), paint(el, patch), markSeen(), seen(), SETTINGS_URL }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathToolsNudge = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { if (root.document.querySelector('[data-jpath-settings]')) api.markSeen(); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var SETTINGS_URL = '/learning/jlpt/settings';
  var SEEN_KEY = 'jpath-tools-moved:seen';
  var state = { warnings: [], learning: true, skip: 0, el: null, doc: null };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function seen() {
    try { return root.localStorage.getItem(SEEN_KEY) === '1'; } catch (e) { return true; }
  }
  function markSeen() {
    try { root.localStorage.setItem(SEEN_KEY, '1'); } catch (e) { /* private mode */ }
  }

  function row(text, href, label, extra) {
    return '<li class="jp-nudge__row"><span class="jp-nudge__text">' + esc(text) + '</span>'
      + '<span class="jp-nudge__actions"><a class="jp-btn" href="' + esc(href) + '">' + esc(label) + '</a>' + (extra || '')
      + '</span></li>';
  }

  /** Rows only for what has a reason today; '' when nothing does. */
  function nudgeHtml(s) {
    var st = s || {};
    var rows = [];
    (Array.isArray(st.warnings) ? st.warnings : []).forEach(function (w) {
      if (!w || !w.messageVi) return;
      if (w.code === 'EXAM_DATE_PASSED') rows.push(row(w.messageVi, SETTINGS_URL + '#jp-settings-target-title', 'Đặt ngày thi mới'));
      else if (st.learning !== false) rows.push(row(w.messageVi, SETTINGS_URL + '#jp-settings-schedule-title', 'Chỉnh nhịp học'));
    });
    if (Number(st.skip) > 0) {
      rows.push(row('Đã biết trước tuần tới? Có ' + Number(st.skip) + ' phần bạn có thể kiểm tra để bỏ qua.',
        SETTINGS_URL + '#jp-settings-skip-title', 'Kiểm tra để bỏ qua',
        ' <button type="button" class="jp-link-btn" data-nudge-later="skip">Để sau</button>'));
    }
    if (st.hint) {
      rows.push(row('Nhịp học, ngày nghỉ và bài kiểm tra bỏ qua tuần đã biết giờ nằm trong Cài đặt lộ trình.',
        SETTINGS_URL, 'Mở Cài đặt', ' <button type="button" class="jp-link-btn" data-nudge-later="hint">Đã hiểu</button>'));
    }
    return rows.length ? '<ul class="jp-nudge" aria-label="Gợi ý từ Cài đặt lộ trình">' + rows.join('') + '</ul>' : '';
  }

  function dot(doc, on) {
    var links = doc && doc.querySelectorAll ? doc.querySelectorAll('.jp-sidenav__link[href="' + SETTINGS_URL + '"]') : [];
    for (var i = 0; i < links.length; i++) {
      if (links[i].classList) links[i].classList.toggle('has-dot', !!on);
      if (on) links[i].setAttribute('title', 'Có gợi ý trong Cài đặt lộ trình');
      else links[i].removeAttribute('title');
    }
  }

  function draw() {
    var el = state.el;
    if (!el) return;
    var html = nudgeHtml({ warnings: state.warnings, learning: state.learning, skip: state.skip, hint: !seen() });
    el.innerHTML = html;
    el.hidden = !html;
    dot(state.doc || root.document, !!html);
  }

  /** Merge what changed (warnings, learning, skip count) and redraw the nudge in el. */
  function paint(el, patch) {
    if (el) state.el = el;
    var p = patch || {};
    if (p.doc) state.doc = p.doc;
    if ('warnings' in p) state.warnings = Array.isArray(p.warnings) ? p.warnings : [];
    if ('learning' in p) state.learning = p.learning !== false;
    if ('skip' in p) state.skip = Number(p.skip) || 0;
    if (state.el && !state.el.__jpNudge && state.el.addEventListener) {
      state.el.__jpNudge = true;
      state.el.addEventListener('click', function (ev) {
        var b = ev.target && ev.target.closest && ev.target.closest('[data-nudge-later]');
        if (!b) return;
        if (b.getAttribute('data-nudge-later') === 'hint') markSeen();
        else {
          state.skip = 0;
          if (root.JpathWeekSkip && typeof root.JpathWeekSkip.hideToday === 'function') root.JpathWeekSkip.hideToday();
        }
        draw();
      });
    }
    draw();
  }

  return { nudgeHtml: nudgeHtml, paint: paint, markSeen: markSeen, seen: seen, SETTINGS_URL: SETTINGS_URL };
});
