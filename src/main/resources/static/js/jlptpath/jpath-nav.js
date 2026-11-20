/* [JLAS-W0108] jpath-nav.js — shared navigation kit of the JLPT path pages (team UI direction 04/11).
 *
 * window.JpathNav = {
 *   iconHtml(name, label?)            Shared SVG sprite icon; optional accessible label
 *   backHtml(label, href)             "Quay về {label}" link to the parent page (F86: replaces breadcrumbs)
 *   crumbsHtml(items)                 Old trail [{label, href?}] → backHtml of the nearest parent that is not home
 *   stepperHtml(steps, opts)          Stepper: [{label, sub?, state: 'done'|'now'|'todo'|'off', href?}];
 *                                     opts {vertical, label, compact}
 *   progressHtml(pct, opts)           Progress bar with role="progressbar"; opts {label, caption, tone}
 *   flowNavHtml(opts)                 Back / Skip / Next / Finish row; opts {back:{label,href|action}, skip, next, finish}
 *   pagerHtml(at, pages, opts)        Numbered pagination (0-based at): Đầu, Trước, 1 … n, Sau, Cuối; opts {action}
 *   moreHtml(shown, total, opts)      "Xem thêm" button with the count left
 *   accordionHtml(sections)           [{title, body, open?, id?}] as <details> blocks
 *   tocHtml(sections, label)          Table of contents [{id, label}] for scrollspy
 *   carouselHtml(slides, label)       Slides with prev/next and dot indicators
 *   mount(doc)                        Binds [data-jp-more] lists, [data-jp-toc] scrollspy and [data-jp-carousel]
 * }
 * Every text is escaped here; html bodies passed in (accordion body, slides) are trusted markup from the caller.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathNav = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    var go = function () { api.mount(root.document); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', go, { once: true });
    else go();
    root.document.addEventListener('jpath:rendered', go);
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function list(x) { return Array.isArray(x) ? x : []; }
  function safeHref(u) {
    var s = String(u || '');
    return /^\/(?!\/)/.test(s) ? s : null;
  }
  function clampPct(v) {
    var n = Number(v);
    if (!isFinite(n)) return 0;
    return Math.max(0, Math.min(100, Math.round(n)));
  }

  function iconHtml(name, label) {
    var key = String(name || '');
    if (!/^[a-z][a-z0-9-]*$/.test(key)) return '';
    var access = label ? ' role="img" aria-label="' + esc(label) + '"' : ' aria-hidden="true"';
    return '<svg class="jp-i"' + access + '><use href="/images/icons/jpath-icons.svg#i-' + key + '"></use></svg>';
  }

  // ------------------------------------------------------------------------------------------- breadcrumbs

  // [JLAS-W0108] F86 (owner 12/11): no "Trang chủ › … › here" trail (home is on the nav bar); one "Quay về" link instead.
  var BACK_ICON = '<svg class="jp-back__icon" viewBox="0 0 20 20" aria-hidden="true"><path d="M12.5 4.5 7 10l5.5 5.5" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  function backHtml(label, href) {
    var h = safeHref(href);
    if (!h || !label) return '';
    return '<a class="jp-back" href="' + esc(h) + '">' + BACK_ICON + '<span>Quay về ' + esc(label) + '</span></a>';
  }

  /** Older callers still pass the whole trail: the link goes to the nearest parent that is not the home page. */
  function crumbsHtml(items) {
    var xs = list(items).filter(function (x) { return x && x.label; });
    for (var i = xs.length - 2; i >= 0; i--) {
      var h = safeHref(xs[i].href);
      if (h && h !== '/') return backHtml(xs[i].label, h);
    }
    return '';
  }

  // ------------------------------------------------------------------------------------------- stepper

  var STATE_WORD = { done: 'đã xong', now: 'đang ở bước này', todo: 'chưa tới', off: 'chưa mở' };

  function stepperHtml(steps, opts) {
    var o = opts || {};
    var xs = list(steps);
    if (!xs.length) return '';
    var cls = 'jp-stepper' + (o.vertical ? ' jp-stepper--vertical' : '') + (o.compact ? ' jp-stepper--compact' : '');
    return '<ol class="' + cls + '"' + (o.label ? ' aria-label="' + esc(o.label) + '"' : '') + '>' + xs.map(function (s, i) {
      var state = STATE_WORD[s.state] ? s.state : 'todo';
      var mark = state === 'done' ? iconHtml('check') : esc(String(i + 1));
      var href = safeHref(s.href);
      var label = '<span class="jp-step__label">' + esc(s.label) + '</span>'
        + (s.sub ? '<span class="jp-step__sub">' + esc(s.sub) + '</span>' : '');
      var body = href ? '<a class="jp-step__body" href="' + esc(href) + '">' + label + '</a>'
        : '<span class="jp-step__body">' + label + '</span>';
      return '<li class="jp-step jp-step--' + state + '"' + (state === 'now' ? ' aria-current="step"' : '') + '>'
        + '<span class="jp-step__mark" aria-hidden="true">' + mark + '</span>'
        + '<span class="jp-sr">' + esc('Bước ' + (i + 1) + ', ' + STATE_WORD[state] + ': ') + '</span>' + body + '</li>';
    }).join('') + '</ol>';
  }

  // ------------------------------------------------------------------------------------------- progress bar

  function progressHtml(pct, opts) {
    var o = opts || {};
    var v = clampPct(pct);
    var label = o.label || 'Tiến độ';
    var tone = o.tone ? ' jp-progress--' + esc(o.tone) : '';
    return '<div class="jp-progress' + tone + '">'
      + (o.caption !== false ? '<div class="jp-progress__head"><span>' + esc(label) + '</span><strong>'
        + esc(o.caption || (v + '%')) + '</strong></div>' : '')
      + '<div class="jp-progress__track" role="progressbar" aria-label="' + esc(label) + '" aria-valuemin="0"'
      + ' aria-valuemax="100" aria-valuenow="' + v + '"><span class="jp-progress__fill" style="width:' + v
      + '%"></span></div></div>';
  }

  // ------------------------------------------------------------------------------------------- flow buttons

  function flowBtn(b, cls) {
    if (!b || !b.label) return '';
    var href = safeHref(b.href);
    var label = String(b.label);
    var before = /^[←‹]\s*/.test(label) ? iconHtml('arrow-left') : '';
    var after = /\s*[→›]$/.test(label) ? iconHtml('arrow-right') : '';
    label = label.replace(/^[←‹]\s*/, '').replace(/\s*[→›]$/, '');
    var inner = before + esc(label) + after;
    if (href) return '<a class="jp-btn ' + cls + '" href="' + esc(href) + '">' + inner + '</a>';
    return '<button type="button" class="jp-btn ' + cls + '"' + (b.action ? ' data-action="' + esc(b.action) + '"' : '')
      + (b.disabled ? ' disabled' : '') + '>' + inner + '</button>';
  }

  function flowNavHtml(opts) {
    var o = opts || {};
    var lead = flowBtn(o.back, 'jp-btn--ghost jp-flow__back');
    var tail = flowBtn(o.skip, 'jp-btn--quiet jp-flow__skip') + flowBtn(o.next, 'jp-btn--primary jp-flow__next')
      + flowBtn(o.finish, 'jp-btn--primary jp-flow__finish');
    if (!lead && !tail) return '';
    return '<div class="jp-flow" role="group" aria-label="Điều hướng bước"><div class="jp-flow__lead">' + lead
      + '</div><div class="jp-flow__tail">' + tail + '</div></div>';
  }

  // ------------------------------------------------------------------------------------------- pagination

  /** Page numbers to show around {@code at}: first, last, at ± 1, with null for a gap. */
  function pageWindow(at, pages) {
    var out = [];
    for (var i = 0; i < pages; i++) {
      if (i === 0 || i === pages - 1 || Math.abs(i - at) <= 1) out.push(i);
      else if (out[out.length - 1] !== null) out.push(null);
    }
    return out;
  }

  function pagerHtml(at, pages, opts) {
    var o = opts || {};
    var n = Math.max(1, Math.floor(Number(pages) || 1));
    var cur = Math.min(Math.max(0, Math.floor(Number(at) || 0)), n - 1);
    var action = esc(o.action || 'page');
    function btn(page, label, aria, off) {
      return '<button type="button" class="jp-pager__btn" data-action="' + action + '" data-page="' + page + '"'
        + (aria ? ' aria-label="' + esc(aria) + '"' : '') + (off ? ' disabled' : '') + '>' + esc(label) + '</button>';
    }
    var nums = pageWindow(cur, n).map(function (p) {
      if (p === null) return '<span class="jp-pager__gap" aria-hidden="true">…</span>';
      if (p === cur) return '<span class="jp-pager__btn jp-pager__btn--at" aria-current="page">' + (p + 1) + '</span>';
      return btn(p, String(p + 1), 'Trang ' + (p + 1));
    }).join('');
    return '<nav class="jp-pager" aria-label="Phân trang">'
      + btn(0, 'Đầu', 'Trang đầu', cur <= 0) + btn(cur - 1, 'Trước', 'Trang trước', cur <= 0)
      + '<span class="jp-pager__nums">' + nums + '</span>'
      + '<span class="jp-pager__at">Trang ' + (cur + 1) + '/' + n + '</span>'
      + btn(cur + 1, 'Sau', 'Trang sau', cur >= n - 1) + btn(n - 1, 'Cuối', 'Trang cuối', cur >= n - 1) + '</nav>';
  }

  // ------------------------------------------------------------------------------------------- load more

  function moreHtml(shown, total, opts) {
    var o = opts || {};
    var left = Math.max(0, (Number(total) || 0) - (Number(shown) || 0));
    if (!left) return '';
    var step = Math.min(left, Number(o.step) || left);
    return '<div class="jp-more"><button type="button" class="jp-btn jp-more__btn"'
      + (o.action ? ' data-action="' + esc(o.action) + '"' : ' data-jp-more-btn') + '>'
      + esc('Xem thêm ' + step) + '</button><span class="jp-more__left">' + esc('còn ' + left) + '</span></div>';
  }

  // ------------------------------------------------------------------------------------------- accordion, toc, carousel

  function accordionHtml(sections) {
    return '<div class="jp-acc">' + list(sections).map(function (s) {
      return '<details class="jp-acc__item"' + (s.open ? ' open' : '') + (s.id ? ' id="' + esc(s.id) + '"' : '') + '>'
        + '<summary class="jp-acc__head">' + esc(s.title) + '<span class="jp-acc__icon">' + iconHtml('plus')
        + '</span></summary><div class="jp-acc__body">' + (s.body || '')
        + '</div></details>';
    }).join('') + '</div>';
  }

  function tocHtml(sections, label) {
    var xs = list(sections).filter(function (s) { return s && s.id && s.label; });
    if (!xs.length) return '';
    return '<nav class="jp-toc" data-jp-toc aria-label="' + esc(label || 'Mục lục') + '"><ol class="jp-toc__list">'
      + xs.map(function (s) {
        return '<li><a class="jp-toc__link" href="#' + esc(s.id) + '" data-jp-toc-for="' + esc(s.id) + '">'
          + esc(s.label) + '</a></li>';
      }).join('') + '</ol></nav>';
  }

  function carouselHtml(slides, label) {
    var xs = list(slides);
    if (!xs.length) return '';
    return '<section class="jp-carousel" data-jp-carousel aria-roledescription="băng chuyền" aria-label="'
      + esc(label || 'Danh sách') + '"><div class="jp-carousel__track" tabindex="0">'
      + xs.map(function (s, i) {
        return '<div class="jp-carousel__slide" role="group" aria-roledescription="trang" aria-label="'
          + (i + 1) + '/' + xs.length + '">' + s + '</div>';
      }).join('') + '</div><div class="jp-carousel__nav">'
      + '<button type="button" class="jp-carousel__arrow" data-jp-carousel-go="-1" aria-label="Trang trước">' + iconHtml('chevron-left') + '</button>'
      + '<span class="jp-carousel__dots">' + xs.map(function (s, i) {
        return '<button type="button" class="jp-carousel__dot" data-jp-carousel-to="' + i + '" aria-label="Tới trang '
          + (i + 1) + '"' + (i === 0 ? ' aria-current="true"' : '') + '></button>';
      }).join('') + '</span>'
      + '<button type="button" class="jp-carousel__arrow" data-jp-carousel-go="1" aria-label="Trang sau">' + iconHtml('chevron-right') + '</button>'
      + '</div></section>';
  }

  // ------------------------------------------------------------------------------------------- behaviour

  /** [data-jp-more="<n>"] on a list: shows n children, then a "Xem thêm" button reveals n more each time. */
  function bindMore(el) {
    if (el.getAttribute('data-jp-more-bound')) return;
    var step = Math.max(1, Number(el.getAttribute('data-jp-more')) || 20);
    var kids = Array.prototype.slice.call(el.children);
    if (kids.length <= step) return;
    el.setAttribute('data-jp-more-bound', '1');
    var shown = step;
    var holder = el.ownerDocument.createElement('div');
    function paint() {
      kids.forEach(function (k, i) { k.hidden = i >= shown; });
      holder.innerHTML = moreHtml(shown, kids.length, { step: step });
      var b = holder.querySelector('[data-jp-more-btn]');
      if (b) b.addEventListener('click', function () {
        var first = kids[shown];
        shown += step;
        paint();
        if (first && typeof first.focus === 'function') {
          if (!first.hasAttribute('tabindex')) first.setAttribute('tabindex', '-1');
          first.focus();
        }
      });
    }
    el.parentNode.insertBefore(holder, el.nextSibling);
    paint();
  }

  /** Highlights the toc link of the section in view. */
  function bindToc(nav) {
    if (nav.getAttribute('data-jp-toc-bound') || typeof root.IntersectionObserver !== 'function') return;
    nav.setAttribute('data-jp-toc-bound', '1');
    var doc = nav.ownerDocument;
    var links = Array.prototype.slice.call(nav.querySelectorAll('[data-jp-toc-for]'));
    var targets = links.map(function (a) { return doc.getElementById(a.getAttribute('data-jp-toc-for')); });
    var io = new root.IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        links.forEach(function (a) {
          var on = a.getAttribute('data-jp-toc-for') === e.target.id;
          if (on) a.setAttribute('aria-current', 'true'); else a.removeAttribute('aria-current');
        });
      });
    }, { rootMargin: '-20% 0px -70% 0px' });
    targets.forEach(function (t) { if (t) io.observe(t); });
  }

  function bindCarousel(box) {
    if (box.getAttribute('data-jp-carousel-bound')) return;
    box.setAttribute('data-jp-carousel-bound', '1');
    var track = box.querySelector('.jp-carousel__track');
    var dots = Array.prototype.slice.call(box.querySelectorAll('[data-jp-carousel-to]'));
    if (!track) return;
    function at() { return Math.round(track.scrollLeft / Math.max(1, track.clientWidth)); }
    function go(i) {
      var n = Math.max(0, Math.min(dots.length - 1, i));
      track.scrollTo({ left: n * track.clientWidth, behavior: 'smooth' });
    }
    function mark() {
      var i = at();
      dots.forEach(function (d, j) { if (j === i) d.setAttribute('aria-current', 'true'); else d.removeAttribute('aria-current'); });
    }
    box.addEventListener('click', function (ev) {
      var t = ev.target.closest && ev.target.closest('[data-jp-carousel-go],[data-jp-carousel-to]');
      if (!t) return;
      if (t.hasAttribute('data-jp-carousel-to')) go(Number(t.getAttribute('data-jp-carousel-to')));
      else go(at() + Number(t.getAttribute('data-jp-carousel-go')));
    });
    track.addEventListener('keydown', function (ev) {
      if (ev.key === 'ArrowRight') { ev.preventDefault(); go(at() + 1); }
      if (ev.key === 'ArrowLeft') { ev.preventDefault(); go(at() - 1); }
    });
    track.addEventListener('scroll', function () { mark(); }, { passive: true });
  }

  /**
   * [data-jp-wizard]: its [data-jp-wizard-step="Label"] children become steps shown one at a time, with a stepper on
   * top and Quay lại / Tiếp tục below; the last step keeps the page's own submit button (Hoàn tất).
   */
  function bindWizard(box) {
    if (box.getAttribute('data-jp-wizard-bound')) return;
    var doc = box.ownerDocument;
    var steps = Array.prototype.slice.call(box.children).filter(function (c) { return c.hasAttribute('data-jp-wizard-step'); });
    if (steps.length < 2) return;
    box.setAttribute('data-jp-wizard-bound', '1');
    var at = 0;
    var head = doc.createElement('div');
    head.className = 'jp-wizard__head';
    var foot = doc.createElement('div');
    foot.className = 'jp-wizard__foot';
    box.insertBefore(head, steps[0]);
    box.appendChild(foot);
    function paint(focus) {
      head.innerHTML = '<p class="jp-wizard__count">Bước ' + (at + 1) + '/' + steps.length + '</p>'
        + stepperHtml(steps.map(function (st, i) {
          return { label: st.getAttribute('data-jp-wizard-step'), sub: st.getAttribute('data-jp-wizard-sub'),
            state: i < at ? 'done' : (i === at ? 'now' : 'todo') };
        }), { label: 'Các bước tạo lộ trình' });
      steps.forEach(function (st, i) { st.hidden = i !== at; });
      foot.innerHTML = flowNavHtml({
        back: at > 0 ? { label: '‹ Quay lại', action: 'jp-wizard-back' } : null,
        next: at < steps.length - 1 ? { label: 'Tiếp tục ›', action: 'jp-wizard-next' } : null
      });
      if (focus) {
        var h = steps[at].querySelector('h2');
        if (h) { if (!h.hasAttribute('tabindex')) h.setAttribute('tabindex', '-1'); h.focus(); }
        if (typeof head.scrollIntoView === 'function') head.scrollIntoView({ block: 'start', behavior: 'smooth' });
      }
    }
    foot.addEventListener('click', function (ev) {
      var t = ev.target.closest && ev.target.closest('[data-action]');
      if (!t) return;
      if (t.getAttribute('data-action') === 'jp-wizard-next') { at = Math.min(steps.length - 1, at + 1); paint(true); }
      if (t.getAttribute('data-action') === 'jp-wizard-back') { at = Math.max(0, at - 1); paint(true); }
    });
    paint(false);
  }

  /** [data-jp-toc-auto="<selector>"]: a table of contents of the matching sections (their h2), filled once. */
  function buildToc(holder) {
    if (holder.getAttribute('data-jp-toc-built')) return;
    var doc = holder.ownerDocument;
    var secs = Array.prototype.slice.call(doc.querySelectorAll(holder.getAttribute('data-jp-toc-auto')));
    var items = secs.map(function (sec, i) {
      var h = sec.querySelector('h2, h3');
      if (!h) return null;
      if (!sec.id) sec.id = 'jp-sec-' + (sec.getAttribute('data-block') || i);
      var label = String(h.textContent || '').replace(/^[^0-9A-Za-zÀ-ỹ]+/, '').trim();
      return label ? { id: sec.id, label: label } : null;
    }).filter(Boolean);
    if (!items.length) return;
    holder.setAttribute('data-jp-toc-built', '1');
    holder.innerHTML = tocHtml(items, holder.getAttribute('data-jp-toc-label'));
  }

  function mount(doc) {
    if (!doc || typeof doc.querySelectorAll !== 'function') return;
    Array.prototype.forEach.call(doc.querySelectorAll('[data-jp-toc-auto]'), buildToc);
    Array.prototype.forEach.call(doc.querySelectorAll('[data-jp-wizard]'), bindWizard);
    Array.prototype.forEach.call(doc.querySelectorAll('[data-jp-more]'), bindMore);
    Array.prototype.forEach.call(doc.querySelectorAll('[data-jp-toc]'), bindToc);
    Array.prototype.forEach.call(doc.querySelectorAll('[data-jp-carousel]'), bindCarousel);
  }

  return {
    esc: esc, iconHtml: iconHtml, crumbsHtml: crumbsHtml, backHtml: backHtml, stepperHtml: stepperHtml, progressHtml: progressHtml, flowNavHtml: flowNavHtml,
    pagerHtml: pagerHtml, pageWindow: pageWindow, moreHtml: moreHtml, accordionHtml: accordionHtml, tocHtml: tocHtml,
    carouselHtml: carouselHtml, mount: mount
  };
});

/* [JLAS-W0108] Owner 17/11: an information notice (.jp-band or role="note") shows once and has a close button, on
 * every JLPT and communication path page. Seen/closed notices are remembered per browser (a convenience only, so a
 * blocked storage just shows them again). Alerts (role="alert") and errors are never touched. */
(function (root) {
  'use strict';
  if (!root || !root.document) return;
  var doc = root.document;
  /** Bug 58: per-user storage key, so a shared computer never hands one learner's state to another. */
  function uidPart() {
    try {
      var auth = root && root.JlasAuth;
      var u = auth && typeof auth.getUser === 'function' ? auth.getUser() : null;
      return u && (u.id || u.userId) ? String(u.id || u.userId) : 'me';
    } catch (e) { return 'me'; }
  }
  var KEY = 'jpathNoticesSeen';
  function load() { try { return JSON.parse(root.localStorage.getItem(KEY + ':' + uidPart()) || '{}') || {}; } catch (e) { return {}; } }
  function save(map) { try { root.localStorage.setItem(KEY + ':' + uidPart(), JSON.stringify(map)); } catch (e) { /* storage off */ } }
  function idOf(el) {
    if (el.getAttribute('data-notice-id')) return el.getAttribute('data-notice-id');
    var s = (el.textContent || '').replace(/\s+/g, ' ').trim();
    var h = 0;
    for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
    return 'n' + (h >>> 0).toString(36);
  }
  function enhance(scope) {
    var seen = load();
    var changed = false;
    var base = scope || doc;
    if (!base || typeof base.querySelectorAll !== 'function') return;
    var list = base.querySelectorAll('.jp-band, [role="note"]');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.hasAttribute('data-notice-ready') || el.closest('[role="alert"], .jp-alert, [data-notice-keep]')) continue;
      if (!(el.textContent || '').trim()) continue;
      el.setAttribute('data-notice-ready', '');
      var id = idOf(el);
      if (seen[id]) { el.hidden = true; continue; }
      seen[id] = 1; changed = true; // shown once: next visit it stays hidden
      el.classList.add('jp-notice--closable');
      var btn = doc.createElement('button');
      btn.type = 'button';
      btn.className = 'jp-notice__close';
      btn.setAttribute('aria-label', 'Tắt thông báo');
      btn.textContent = '×';
      btn.addEventListener('click', function (ev) { var box = ev.currentTarget.parentNode; if (box) box.hidden = true; });
      el.appendChild(btn);
    }
    if (changed) save(seen);
  }
  function start() {
    enhance(doc);
    if (root.MutationObserver && doc.body) {
      var pending = false;
      new root.MutationObserver(function () {
        if (pending) return;
        pending = true;
        root.requestAnimationFrame(function () { pending = false; enhance(doc); });
      }).observe(doc.body || doc, { childList: true, subtree: true });
    }
  }
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start); else start();
  root.JpathNotices = { enhance: enhance };
})(typeof window !== 'undefined' ? window : null);
