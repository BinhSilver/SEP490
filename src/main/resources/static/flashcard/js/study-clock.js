(function (root, factory) {
  'use strict';
  var clock = factory();
  if (typeof module === 'object' && module.exports) module.exports = clock;
  else root.FlashStudyClock = clock;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';

  function validElapsed(value) {
    var number = Number(value);
    return Number.isFinite(number) && number >= 0 ? number : 0;
  }

  function create(savedElapsedMs, readNow) {
    var now = readNow || function () { return performance.now(); };
    var accumulated = validElapsed(savedElapsedMs);
    var segmentStart = null;

    function elapsedMs() {
      return accumulated + (segmentStart === null ? 0 : Math.max(0, now() - segmentStart));
    }

    return {
      start: function () {
        if (segmentStart === null) segmentStart = now();
      },
      pause: function () {
        accumulated = elapsedMs();
        segmentStart = null;
      },
      reset: function (elapsed) {
        accumulated = validElapsed(elapsed);
        segmentStart = null;
      },
      elapsedMs: elapsedMs
    };
  }

  return { create: create };
});
