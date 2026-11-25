/* [JLAS-W0108] jpath-tour.js — first-visit tour of the JLPT path page (/learning).
 *
 * The page starts it when path.tourDone is false or the url has tour=1 (menu "⋯ → Xem lại hướng dẫn"); the page
 * saves tourDone through PUT /api/learning/jlpt/settings when it ends (done or skipped) and drops tour=1.
 *
 * window.JpathTour:
 *   STEPS                               4 frozen steps {target, text}: level, settings, today, skills
 *   shouldStart(path, search)           !path.tourDone || /(^|[?&])tour=1(&|$)/.test(search)
 *   stepHtml(step, i, n)                role="dialog" box: "Bước i/n", text, "Bỏ qua", "Tiếp" / "Xong" on the last step
 *   create({doc, steps, onFinish, reducedMotion}) → {start, next, skip, index}
 *                                       steps whose target is missing are left out; the target is lit by a
 *                                       fixed scrim with a cut-out (.jp-tour-scrim) and scrolled to the centre (no smooth
 *                                       scrolling with prefers-reduced-motion); focus stays in the box (Tab wraps),
 *                                       Esc = Bỏ qua; refresh() re-applies the highlight after a repaint; onFinish('DONE'|'SKIP') fires once and focus goes back
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
    Object.freeze({ target: '[data-tour="settings"]',
      text: 'Số từ mới mỗi ngày, ngày nghỉ và bài kiểm tra bỏ qua tuần đã biết nằm trong Cài đặt lộ trình.' }),
    Object.freeze({ target: '[data-tour="today"]',
      text: 'Bấm Học hôm nay để học liền một lượt, hoặc học riêng từng tab nếu muốn chia nhỏ.' }),
    Object.freeze({ target: '[data-tour="skills"]',
      text: 'Hai ngày mỗi tuần là ngày kỹ năng Đọc · Nghe: không học mới, chỉ ôn và luyện.' })
  ]);
  var TARGET_CLASS = 'jp-tour-target';
  var PAD = 6;

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
    var sizeWatch = null;
    var lit = null;
    var back = null;
    var scrim = null;
    var hole = null;
    var win = o.win || (doc && doc.defaultView) || root;

    function targetOf(step) { return doc && step ? doc.querySelector(step.target) : null; }

    function unlight() {
      if (lit && lit.classList) lit.classList.remove(TARGET_CLASS);
      lit = null;
    }

    // one fixed full-screen scrim (blocks pointer input behind the modal box) with a cut-out over the target,
    // measured from getBoundingClientRect so overflow/sticky parents of the target cannot clip it
    function place() {
      if (!hole) return;
      var r = lit && typeof lit.getBoundingClientRect === 'function' ? lit.getBoundingClientRect() : null;
      if (!r) { hole.style.display = 'none'; return; }
      hole.style.display = '';
      hole.style.top = (r.top - PAD) + 'px';
      hole.style.left = (r.left - PAD) + 'px';
      hole.style.width = (r.width + 2 * PAD) + 'px';
      hole.style.height = (r.height + 2 * PAD) + 'px';
      anchorBox(r);
    }

    // AI 1 #10: on a wide screen the step box sits right next to the lit target (below it, or above when there
    // is no room), so a new learner sees what the dimmed page is waiting for; phones keep the bottom sheet
    function anchorBox(r) {
      var box = layer && layer.querySelector ? layer.querySelector('.jp-tour__box') : null;
      var vw = win.innerWidth || 0, vh = win.innerHeight || 0;
      if (!box || !box.style || !win) return;
      if (!r || vw <= 720) {
        if (layer.classList) layer.classList.remove('jp-tour--anchored');
        box.style.top = ''; box.style.left = '';
        return;
      }
      if (layer.classList) layer.classList.add('jp-tour--anchored');
      var bw = box.offsetWidth || 380, bh = box.offsetHeight || 160, gap = PAD + 12;
      var top = r.bottom + gap;
      if (top + bh > vh - 8) top = r.top - gap - bh;
      if (top < 8) top = Math.max(8, Math.min(vh - bh - 8, r.top + 8));
      var left = Math.max(8, Math.min(vw - bw - 8, r.left));
      box.style.top = top + 'px';
      box.style.left = left + 'px';
    }

    /** Re-finds the current target (the page may have repainted it) and re-measures the cut-out. */
    function refresh() {
      if (finished || at < 0) return;
      var el = targetOf(live[at]);
      if (el !== lit) {
        unlight();
        if (el && el.classList) { el.classList.add(TARGET_CLASS); lit = el; }
      }
      place();
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
      if (btn.getAttribute('data-tour-action') === 'skip') skip();
      else next();
    }

    function show(i) {
      unlight();
      at = i;
      var step = live[i];
      var el = targetOf(step);
      // owner 17/11: a step in the hidden mode (Học hôm nay / Tự chọn bài) opens that mode first
      var part = el && el.closest ? el.closest('[data-mode-part]') : null;
      var modeCtl = root && root.JpathMode && root.JpathMode.current;
      if (part && modeCtl && modeCtl.get() !== part.getAttribute('data-mode-part')) modeCtl.set(part.getAttribute('data-mode-part'), false);
      if (el && el.classList) { el.classList.add(TARGET_CLASS); lit = el; }
      if (el && typeof el.scrollIntoView === 'function') {
        el.scrollIntoView({ block: 'center', behavior: reduced() ? 'auto' : 'smooth' });
      }
      place();
      layer.innerHTML = stepHtml(step, i + 1, live.length);
      var primary = layer.querySelector('[data-tour-action="next"]');
      if (primary && typeof primary.focus === 'function') primary.focus();
    }

    function finish(kind) {
      if (finished) return;
      finished = true;
      unlight();
      if (sizeWatch) { try { sizeWatch.disconnect(); } catch (e) { /* gone */ } sizeWatch = null; }
      if (doc) doc.removeEventListener('keydown', onKey, true);
      if (win && typeof win.removeEventListener === 'function') {
        win.removeEventListener('resize', place);
        win.removeEventListener('scroll', place, true);
      }
      if (layer && layer.parentNode) layer.parentNode.removeChild(layer);
      if (scrim && scrim.parentNode) scrim.parentNode.removeChild(scrim);
      layer = null;
      scrim = null;
      hole = null;
      if (back && typeof back.focus === 'function') back.focus();
      onFinish(kind);
    }

    function start() {
      if (finished || at >= 0 || !doc || !doc.body) return;
      live = all.filter(function (s) { return !!targetOf(s); });
      if (!live.length) { finish('DONE'); return; }
      back = doc.activeElement;
      scrim = doc.createElement('div');
      scrim.className = 'jp-tour-scrim';
      scrim.setAttribute('aria-hidden', 'true');
      hole = doc.createElement('div');
      hole.className = 'jp-tour-scrim__hole';
      hole.style = hole.style || {};
      scrim.appendChild(hole);
      doc.body.appendChild(scrim);
      layer = doc.createElement('div');
      layer.className = 'jp-tour';
      layer.addEventListener('click', onClick);
      doc.body.appendChild(layer);
      if (win && typeof win.addEventListener === 'function') {
        win.addEventListener('resize', place);
        win.addEventListener('scroll', place, true);
      }
      // owner 17/11: content loading after the guide opened (week-skip box, skeletons) moved the target while the
      // highlight stayed put — follow any size change of the page
      if (win && typeof win.ResizeObserver === 'function' && doc.body) {
        try { sizeWatch = new win.ResizeObserver(function () { place(); }); sizeWatch.observe(doc.body); } catch (e) { sizeWatch = null; }
      }
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

    return { start: start, next: next, skip: skip, refresh: refresh, index: function () { return at; } };
  }

  return { STEPS: STEPS, shouldStart: shouldStart, stepHtml: stepHtml, create: create };
});
