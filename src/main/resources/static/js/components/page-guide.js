/*
 * Step-by-step page guide shared by the Shadowing and Flashcard pages (same look as the Shadowing session setup guide).
 *
 *   JlasPageGuide.setup({
 *     key: 'jlas.something.guide.v1',     // remembered in this browser once the guide is finished or skipped
 *     button: element,                    // "Hướng dẫn" button; enabled once the page is ready
 *     label: 'Hướng dẫn ...',             // accessible name of the guide card
 *     steps: () => [[selector, title, text], ...],  // text: a short line, or an array shown as bullets;
 *                                                   // steps whose target is not visible are left out
 *     isReady: () => boolean              // optional; the guide waits for it before opening by itself
 *   });
 *
 * The part being explained is lit through a hole in a dark layer; the rest of the page cannot be clicked meanwhile.
 * While open, <body> carries .page-guide-active so a page can keep hover-only controls showing.
 */
(function () {
  'use strict';

  function visibleMatch(selector) {
    const all = document.querySelectorAll(selector);
    for (let i = 0; i < all.length; i++) {
      const rect = all[i].getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0 && getComputedStyle(all[i]).visibility !== 'hidden') return all[i];
    }
    return null;
  }

  function setup(options) {
    const button = options.button;
    const isReady = options.isReady || function () { return true; };
    let panel = null, spot = null, blocker = null, target = null, step = 0, steps = [];

    function place() {
      if (!panel || !target) return;
      const rect = target.getBoundingClientRect();
      const pad = 6;
      const x = rect.left - pad, y = rect.top - pad, w = rect.width + pad * 2, h = rect.height + pad * 2;
      Object.assign(spot.style, { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' });
      blocker.style.clipPath = 'path(evenodd, "M0 0H' + innerWidth + 'V' + innerHeight + 'H0Z'
        + 'M' + x + ' ' + y + 'h' + w + 'v' + h + 'h' + (-w) + 'Z")';
      const height = panel.offsetHeight;
      const below = rect.bottom + 18;
      const above = rect.top - height - 18;
      const top = below + height <= innerHeight - 16 ? below : above >= 16 ? above : innerHeight - height - 16;
      panel.style.top = Math.max(16, top) + 'px';
      panel.style.left = Math.max(16, Math.min(rect.left, innerWidth - panel.offsetWidth - 16)) + 'px';
    }

    function keyboard(event) {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); finish(); return; }
      if (event.key !== 'Tab' || !panel) return;
      const selector = 'button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea, [tabindex="0"]';
      const controls = [...(target.matches(selector) ? [target] : target.querySelectorAll(selector)),
        ...panel.querySelectorAll('button:not([hidden])')];
      const index = controls.indexOf(document.activeElement);
      const nextIndex = index < 0 ? 0 : (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
      event.preventDefault();
      controls[nextIndex].focus({ preventScroll: true });
    }

    /** One line, or a list when a step covers several things; "Label: text" gets a bold label for quick reading. */
    function renderBody(body, text) {
      body.replaceChildren();
      if (!Array.isArray(text)) {
        const p = document.createElement('p');
        p.textContent = text;
        body.appendChild(p);
        return;
      }
      const list = document.createElement('ul');
      text.forEach(function (line) {
        const item = document.createElement('li');
        const cut = line.indexOf(': ');
        if (cut > 0) {
          const label = document.createElement('strong');
          label.textContent = line.slice(0, cut + 1);
          item.append(label, ' ' + line.slice(cut + 2));
        } else {
          item.textContent = line;
        }
        list.appendChild(item);
      });
      body.appendChild(list);
    }

    function show() {
      const entry = steps[step];
      target = visibleMatch(entry[0]);
      if (!target) { finish(); return; }
      panel.querySelector('small').textContent = 'Bước ' + (step + 1) + ' / ' + steps.length;
      panel.querySelector('h2').textContent = entry[1];
      renderBody(panel.querySelector('.page-guide__body'), entry[2]);
      panel.querySelector('[data-guide-back]').hidden = step === 0;
      panel.querySelector('[data-guide-next]').textContent = step === steps.length - 1 ? 'Đã hiểu' : 'Tiếp';
      target.scrollIntoView({ block: 'center', behavior: 'instant' });
      place();
      panel.querySelector('[data-guide-next]').focus({ preventScroll: true });
    }

    function next() { if (step < steps.length - 1) { step++; show(); } else finish(); }
    function back() { if (step > 0) { step--; show(); } }

    function finish() {
      if (!panel) return;
      panel.remove(); spot.remove(); blocker.remove();
      panel = spot = blocker = target = null;
      document.body.classList.remove('page-guide-active');
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
      document.removeEventListener('keydown', keyboard, true);
      try { localStorage.setItem(options.key, 'done'); } catch (_) { /* Storage may be blocked. */ }
      button?.focus({ preventScroll: true });
    }

    function open() {
      if (panel || !isReady()) return;
      steps = options.steps().filter(function (entry) { return visibleMatch(entry[0]); });
      if (!steps.length) return;
      step = 0;
      document.body.classList.add('page-guide-active');
      blocker = document.createElement('div');
      blocker.className = 'page-guide-blocker';
      blocker.setAttribute('aria-hidden', 'true');
      spot = document.createElement('div');
      spot.className = 'page-guide-spot';
      spot.setAttribute('aria-hidden', 'true');
      panel = document.createElement('section');
      panel.className = 'page-guide';
      panel.setAttribute('role', 'dialog');
      panel.setAttribute('aria-label', options.label || 'Hướng dẫn');
      panel.innerHTML = '<div aria-live="polite" aria-atomic="true"><small></small><h2></h2><div class="page-guide__body"></div></div>'
        + '<nav><button type="button" data-guide-skip>Bỏ qua</button>'
        + '<button type="button" data-guide-back>Quay lại</button>'
        + '<button type="button" data-guide-next>Tiếp</button></nav>';
      document.body.append(blocker, spot, panel);
      panel.querySelector('[data-guide-skip]').addEventListener('click', finish);
      panel.querySelector('[data-guide-back]').addEventListener('click', back);
      panel.querySelector('[data-guide-next]').addEventListener('click', next);
      window.addEventListener('resize', place);
      window.addEventListener('scroll', place, true);
      document.addEventListener('keydown', keyboard, true);
      show();
    }

    button?.addEventListener('click', open);

    // Opens by itself on the first visit, once the page has drawn what the steps point at.
    function whenReady() {
      if (!isReady()) return false;
      if (button) button.disabled = false;
      let seen = false;
      try { seen = localStorage.getItem(options.key) === 'done'; } catch (_) { /* Still show the guide. */ }
      if (!seen) setTimeout(open, 400);
      return true;
    }

    if (!whenReady()) {
      const watcher = new MutationObserver(function () { if (whenReady()) watcher.disconnect(); });
      watcher.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });
    }

    return { open: open, finish: finish };
  }

  window.JlasPageGuide = { setup: setup };
}());
