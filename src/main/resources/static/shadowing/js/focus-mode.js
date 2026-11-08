(function () {
  'use strict';

  let active = null;
  const buttons = Array.from(document.querySelectorAll('[data-shadowing-focus]'));

  function restore(entry) {
    if (active !== entry) return;
    active = null;
    entry.target.classList.remove('is-shadowing-focus');
    document.documentElement.classList.remove('shadowing-focus-open');
    entry.inertElements.forEach(function (element) { element.inert = false; });
    entry.button.setAttribute('aria-pressed', 'false');
    entry.button.setAttribute('aria-label', 'Bật chế độ toàn màn hình');
    entry.button.title = 'Bật chế độ toàn màn hình';
    entry.button.firstElementChild.className = 'fa-solid fa-expand';
    if (entry.previousFocus?.isConnected) entry.previousFocus.focus({ preventScroll: true });
    window.scrollTo(entry.scrollX, entry.scrollY);
  }

  async function exit() {
    const entry = active;
    if (!entry) return;
    restore(entry);
    if (document.fullscreenElement === entry.target) {
      try { await document.exitFullscreen(); } catch (_) { /* Escape remains available in the browser. */ }
    }
  }

  async function enter(button, target) {
    if (active) await exit();
    const entry = {
      button: button, target: target, native: false, inertElements: [],
      previousFocus: document.activeElement, scrollX: window.scrollX, scrollY: window.scrollY
    };
    active = entry;
    // Keep the media in place: reparenting a playing video can interrupt playback on phones.
    for (let branch = target; branch && branch !== document.body; branch = branch.parentElement) {
      Array.from(branch.parentElement?.children || []).forEach(function (sibling) {
        if (sibling !== branch && !sibling.inert) {
          sibling.inert = true;
          entry.inertElements.push(sibling);
        }
      });
    }
    target.classList.add('is-shadowing-focus');
    document.documentElement.classList.add('shadowing-focus-open');
    button.setAttribute('aria-pressed', 'true');
    button.setAttribute('aria-label', 'Thoát chế độ toàn màn hình');
    button.title = 'Thoát chế độ toàn màn hình';
    button.firstElementChild.className = 'fa-solid fa-compress';
    button.focus({ preventScroll: true });

    // iPhone and browsers that refuse element fullscreen keep the same viewport layout.
    if (target.requestFullscreen && document.fullscreenEnabled) {
      try {
        await target.requestFullscreen();
        if (active === entry) entry.native = document.fullscreenElement === target;
        else if (document.fullscreenElement === target) await document.exitFullscreen();
      } catch (_) { /* The fixed viewport fallback is already active. */ }
    }
  }

  buttons.forEach(function (button) {
    const target = document.getElementById(button.dataset.shadowingFocus);
    if (!target) return;
    button.setAttribute('aria-controls', target.id);
    button.addEventListener('click', function () {
      if (active?.target === target) exit();
      else enter(button, target);
    });
  });

  document.addEventListener('fullscreenchange', function () {
    if (!active) return;
    if (document.fullscreenElement === active.target) active.native = true;
    else if (active.native) restore(active);
  });

  document.addEventListener('keydown', function (event) {
    if (!active) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      exit();
    } else if (event.key === 'Tab') {
      const controls = Array.from(active.target.querySelectorAll(
        'button, input, textarea, select, a[href], [tabindex]'
      )).filter(function (element) {
        return !element.disabled && element.tabIndex >= 0 && element.getClientRects().length;
      });
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (first && event.shiftKey && document.activeElement === first) {
        event.preventDefault(); last.focus();
      } else if (last && !event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
      }
    }
  });

  window.ShadowingFocus = { exit: exit };
}());
