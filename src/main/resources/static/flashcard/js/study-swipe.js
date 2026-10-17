/* Touch gestures share the same review action as the X / check buttons. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashStudySwipe = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  function attach(card, options) {
    var gesture = null;
    var busy = false;
    var suppressClickUntil = 0;
    function enabled() { return !busy && options.enabled(); }
    function threshold() { return Math.max(56, Math.min(110, card.getBoundingClientRect().width * 0.22)); }

    function reset() {
      var previous = gesture;
      gesture = null;
      card.classList.remove('is-swiping');
      card.removeAttribute('data-swipe-direction');
      card.style.removeProperty('--swipe-x');
      card.style.removeProperty('--swipe-angle');
      card.style.removeProperty('--swipe-strength');
      if (previous && card.hasPointerCapture(previous.id)) card.releasePointerCapture(previous.id);
    }

    card.addEventListener('pointerdown', function (event) {
      if (gesture) { reset(); return; }
      if (!enabled() || event.isPrimary === false || event.pointerType === 'mouse') return;
      if (event.target.closest('button, a, input, select, textarea')) return;
      gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, axis: null, dx: 0, dy: 0 };
      // Capture before the first move. Touch browsers initially capture the
      // inner face/text, whose lostpointercapture event bubbles to this card.
      card.setPointerCapture(event.pointerId);
    });

    card.addEventListener('pointermove', function (event) {
      if (!gesture || event.pointerId !== gesture.id) return;
      if (!enabled()) { reset(); return; }
      gesture.dx = event.clientX - gesture.x;
      gesture.dy = event.clientY - gesture.y;
      var x = Math.abs(gesture.dx);
      var y = Math.abs(gesture.dy);
      if (!gesture.axis) {
        if (y > 12 && y >= x) gesture.axis = 'vertical';
        else if (x > 12 && x > y * 1.25) {
          gesture.axis = 'horizontal';
        }
      }
      if (gesture.axis !== 'horizontal') return;
      event.preventDefault();
      var distance = Math.max(-96, Math.min(96, gesture.dx));
      card.classList.add('is-swiping');
      card.setAttribute('data-swipe-direction', gesture.dx < 0 ? 'left' : 'right');
      card.style.setProperty('--swipe-x', distance + 'px');
      card.style.setProperty('--swipe-angle', (distance / 20) + 'deg');
      card.style.setProperty('--swipe-strength', Math.min(1, x / threshold()));
    });

    card.addEventListener('pointerup', async function (event) {
      if (!gesture || event.pointerId !== gesture.id) return;
      var finished = gesture;
      // Include release coordinates even if move events were coalesced.
      finished.dx = event.clientX - finished.x;
      finished.dy = event.clientY - finished.y;
      var dragged = finished.axis === 'horizontal'
        || (!finished.axis && Math.abs(finished.dx) > 12 && Math.abs(finished.dx) > Math.abs(finished.dy) * 1.25);
      var accepted = dragged && enabled() && Math.abs(finished.dx) >= threshold()
        && Math.abs(finished.dx) > Math.abs(finished.dy) * 1.25;
      if (dragged) suppressClickUntil = Date.now() + 500;
      reset();
      if (!accepted) return;
      busy = true;
      try { await options.onSwipe(finished.dx > 0 ? 'right' : 'left'); }
      finally { busy = false; }
    });

    card.addEventListener('pointercancel', reset);
    card.addEventListener('lostpointercapture', function (event) {
      // Losing a descendant's implicit capture does not cancel the card's gesture.
      if (gesture && event.target === card && event.pointerId === gesture.id) reset();
    });
    card.addEventListener('click', function (event) {
      // Touch release emits click too; it must not flip the next card.
      if (event.detail !== 0 && Date.now() < suppressClickUntil) {
        suppressClickUntil = 0;
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    }, true);
    return { cancel: reset };
  }
  return { attach: attach };
});
