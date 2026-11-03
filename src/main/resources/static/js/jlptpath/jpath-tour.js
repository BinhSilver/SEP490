/* [JLAS-W0108] jpath-tour.js — first-visit tour of the JLPT path page (/learning).
 *
 * The page starts it when path.tourDone is false or the url has tour=1 (menu "⋯ → Xem lại hướng dẫn"); the page
 * saves tourDone through PUT /api/learning/jlpt/settings when it ends (done or skipped) and drops tour=1.
 *
 * window.JpathTour:
 *   STEPS                               4 frozen steps {target, text}: level, slider, today, skills
 *   shouldStart(path, search)           !path.tourDone || /(^|[?&])tour=1(&|$)/.test(search)
 *   stepHtml(step, i, n)                role="dialog" box: "Bước i/n", text, "Bỏ qua", "Tiếp" / "Xong" on the last step
 *   create({doc, steps, onFinish, reducedMotion}) → {start, next, skip, index}
 *                                       steps whose target is missing are left out; the target is lit by a
 *                                       box-shadow scrim (.jp-tour-target) and scrolled to the centre (no smooth
 *                                       scrolling with prefers-reduced-motion); focus stays in the box (Tab wraps),
 *                                       Esc = Bỏ qua; onFinish('DONE'|'SKIP') fires once and focus goes back
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathTour = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var STEPS = Object.freeze([
    Object.freeze({ target: '[data-tour="level"]',
      text: 'Đây là cấp của bạn: học cấp đang mở, cấp dưới để ôn, cấp trên mở bằng Thi thử JLPT.' }),
    Object.freeze({ target: '[data-tour="slider"]',
      text: 'Kéo để chọn số mục mới mỗi ngày cho từng tab; bạn được cập nhật 2 lần mỗi cấp.' }),
    Object.freeze({ target: '[data-tour="today"]',
      text: 'Bấm Học hôm nay để học liền một lượt, hoặc học riêng từng tab nếu muốn chia nhỏ.' }),
    Object.freeze({ target: '[data-tour="skills"]',
      text: 'Hai ngày mỗi tuần là ngày kỹ năng Đọc · Nghe: không học mới, chỉ ôn và luyện.' })
  ]);
  var TARGET_CLASS = 'jp-tour-target';

  function esc(v) {
    var d = root && root.JpathDisplay;
    if (d && typeof d.esc === 'function') return d.esc(v);
    return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function shouldStart(path, search) {
    return !(path && path.tourDone) || /(^|[?&])tour=1(&|$)/.test(String(search || ''));
  }

  function stepHtml(step, i, n) {
    var last = i >= n;
    return '<div class="jp-tour__box" role="dialog" aria-modal="true" aria-labelledby="jp-tour-step"'
      + ' aria-describedby="jp-tour-text" tabindex="-1">'
      + '<p class="jp-tour__step" id="jp-tour-step">' + esc('Bước ' + i + '/' + n) + '</p>'
      + '<p class="jp-tour__text" id="jp-tour-text">' + esc(step && step.text) + '</p>'
      + '<div class="jp-tour__actions">'
      + '<button type="button" class="jp-btn" data-tour-action="skip">Bỏ qua</button>'
      + '<button type="button" class="jp-btn jp-btn--primary" data-tour-action="next">' + (last ? 'Xong' : 'Tiếp')
      + '</button></div></div>';
  }

  function prefersReducedMotion() {
    try {
      return !!(root && typeof root.matchMedia === 'function' && root.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
  }

  function create(options) {
    var o = options || {};
    var doc = o.doc || (root && root.document);
    var all = Array.isArray(o.steps) ? o.steps : STEPS;
    var reduced = o.reducedMotion || prefersReducedMotion;
    var onFinish = typeof o.onFinish === 'function' ? o.onFinish : function () { /* nothing */ };
    var live = [];
    var at = -1;
    var finished = false;
    var layer = null;
    var lit = null;
    var back = null;

    function targetOf(step) { return doc && step ? doc.querySelector(step.target) : null; }

    function unlight() {
      if (lit && lit.classList) lit.classList.remove(TARGET_CLASS);
      lit = null;
    }

    function buttons() {
      return layer ? Array.prototype.slice.call(layer.querySelectorAll('[data-tour-action]')) : [];
    }

    function onKey(ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); skip(); return; }
      if (ev.key !== 'Tab') return;
      var items = buttons();
      if (!items.length) return;
      var first = items[0];
      var last = items[items.length - 1];
      var active = doc.activeElement;
      if (ev.shiftKey && (active === first || items.indexOf(active) < 0)) { ev.preventDefault(); last.focus(); }
      else if (!ev.shiftKey && (active === last || items.indexOf(active) < 0)) { ev.preventDefault(); first.focus(); }
    }

    function onClick(ev) {
      var t = ev.target;
      var btn = t && typeof t.closest === 'function' ? t.closest('[data-tour-action]') : null;
      if (!btn) return;
      if (btn.action === 'skip' || (btn.getAttribute && btn.getAttribute('data-tour-action') === 'skip')) skip();
      else next();
    }

    function show(i) {
      unlight();
      at = i;
      var step = live[i];
      var el = targetOf(step);
      if (el && el.classList) { el.classList.add(TARGET_CLASS); lit = el; }
      if (el && typeof el.scrollIntoView === 'function') {
        el.scrollIntoView({ block: 'center', behavior: reduced() ? 'auto' : 'smooth' });
      }
      layer.innerHTML = stepHtml(step, i + 1, live.length);
      var primary = layer.querySelector('[data-tour-action="next"]');
      if (primary && typeof primary.focus === 'function') primary.focus();
    }

    function finish(kind) {
      if (finished) return;
      finished = true;
      unlight();
      if (doc) doc.removeEventListener('keydown', onKey, true);
      if (layer && layer.parentNode) layer.parentNode.removeChild(layer);
      layer = null;
      if (back && typeof back.focus === 'function') back.focus();
      onFinish(kind);
    }

    function start() {
      if (finished || at >= 0 || !doc || !doc.body) return;
      live = all.filter(function (s) { return !!targetOf(s); });
      if (!live.length) { finish('DONE'); return; }
      back = doc.activeElement;
      layer = doc.createElement('div');
      layer.className = 'jp-tour';
      layer.addEventListener('click', onClick);
      doc.body.appendChild(layer);
      doc.addEventListener('keydown', onKey, true);
      show(0);
    }

    function next() {
      if (finished || at < 0) return;
      if (at >= live.length - 1) finish('DONE');
      else show(at + 1);
    }

    function skip() {
      if (finished || at < 0) return;
      finish('SKIP');
    }

    return { start: start, next: next, skip: skip, index: function () { return at; } };
  }

  return { STEPS: STEPS, shouldStart: shouldStart, stepHtml: stepHtml, create: create };
});
