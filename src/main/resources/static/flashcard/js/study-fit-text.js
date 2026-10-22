/* Shrinks a flashcard face's text until it fits the face, so long backs never need a scrollbar. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashStudyFitText = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  var MIN_SIZE = 16;

  /* Short faces grow a little past the stylesheet size, long ones start a little below it. */
  function scaleFor(text) {
    var length = Array.from(String(text || '').replace(/\s+/g, ' ').trim()).length;
    if (length <= 3) return 1.35;
    if (length <= 6) return 1.2;
    if (length <= 12) return 1.1;
    if (length <= 24) return 1;
    if (length <= 40) return 0.85;
    return 0.75;
  }

  /* Largest whole pixel size in [min, max] for which fits(size) holds; min when nothing fits. */
  function largestFit(min, max, fits) {
    if (fits(max)) return max;
    var low = min, high = max - 1, best = min;
    while (low <= high) {
      var mid = Math.floor((low + high) / 2);
      if (fits(mid)) { best = mid; low = mid + 1; } else { high = mid - 1; }
    }
    return best;
  }

  function overflows(el) {
    return el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1;
  }

  /* Starts from the stylesheet size (clamp) scaled by text length, then shrinks only if it overflows. */
  function fit(el, view) {
    if (!el) return;
    el.style.removeProperty('font-size');
    if (!el.clientWidth || !el.textContent) return;
    var base = parseFloat(view.getComputedStyle(el).fontSize) || MIN_SIZE;
    var max = Math.max(MIN_SIZE, Math.round(base * scaleFor(el.textContent)));
    var size = largestFit(Math.min(MIN_SIZE, max), max, function (px) {
      el.style.fontSize = px + 'px';
      return !overflows(el);
    });
    el.style.fontSize = size + 'px';
  }

  /* Refits the given faces now and whenever the card box changes size (resize, focus mode). */
  function attach(card, faces, view) {
    var frame = 0;
    function refit() {
      faces.forEach(function (el) { fit(el, view); });
    }
    function schedule() {
      if (frame) view.cancelAnimationFrame(frame);
      frame = view.requestAnimationFrame(function () { frame = 0; refit(); });
    }
    if (typeof view.ResizeObserver === 'function') new view.ResizeObserver(schedule).observe(card);
    else view.addEventListener('resize', schedule);
    if (view.document && view.document.fonts && view.document.fonts.ready) view.document.fonts.ready.then(schedule);
    return { refit: refit, schedule: schedule };
  }

  return { largestFit: largestFit, scaleFor: scaleFor, fit: fit, attach: attach, MIN_SIZE: MIN_SIZE };
});
