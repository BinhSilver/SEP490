(function (root, factory) {
  'use strict';
  if (typeof module !== 'undefined' && module.exports) module.exports = factory;
  if (root && root.document) root.JlasHomeScene = factory(root);
})(typeof window !== 'undefined' ? window : null, function (window) {
  'use strict';
  var document = window.document;
  var scene, sunAnchor, moonAnchor, horizon, sun, moon, geometry, media, lighting;
  var visual = 0, target = 0, progress = 0, from = 0, to = 1;
  var frame = null, generation = 0, active = false, fullMotion = false;
  var DURATION = 2100;

  function clamp(value) { return Math.max(0, Math.min(1, value)); }
  function smooth(value) { value = clamp(value); return value * value * (3 - 2 * value); }
  function now() { return window.performance ? window.performance.now() : Date.now(); }
  function center(element) {
    var rect = element.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, size: rect.width || 1 };
  }
  function measure() {
    var sunOrigin = center(sunAnchor), moonOrigin = center(moonAnchor);
    var bounds = scene.getBoundingClientRect();
    var size = Math.max(sunOrigin.size, moonOrigin.size);
    return {
      sun: sunOrigin, moon: moonOrigin, horizon: center(horizon),
      setting: {
        x: bounds.left + bounds.width + size / 2,
        y: Math.max(bounds.top + bounds.height * 0.7, sunOrigin.y + size * 2, moonOrigin.y + size * 2)
      }
    };
  }
  function setBody(element, origin, travel, opacity) {
    // The descending side of the sky's elliptical orbit: always right and down.
    var angle = travel * Math.PI / 2;
    var x = (geometry.setting.x - origin.x) * Math.sin(angle);
    var y = (geometry.setting.y - origin.y) * (1 - Math.cos(angle));
    element.style.transform = 'translate3d(' + x.toFixed(2) + 'px,' + y.toFixed(2) + 'px,0) scale(1.0000)';
    element.style.opacity = String(opacity);
  }
  function body(element, origin, destination, travel, opacity, arc) {
    var dx = destination.x - origin.x, dy = destination.y - origin.y;
    var scale = 1 + (Math.min(1, destination.size / origin.size) - 1) * travel;
    element.style.transform = 'translate3d(' + (dx * travel).toFixed(2) + 'px,' +
      (dy * travel + Math.sin(Math.PI * travel) * arc).toFixed(2) + 'px,0) scale(' + scale.toFixed(4) + ')';
    element.style.opacity = String(opacity);
  }
  function render() {
    var night = active ? (from === 0 ? smooth(progress) : 1 - smooth(progress)) : visual;
    var nextLighting = night >= 0.45 ? 'night' : 'day';
    if (nextLighting !== lighting) {
      scene.setAttribute('data-home-lighting', nextLighting);
      lighting = nextLighting;
    }
    scene.style.setProperty('--home-night', String(night));
    scene.style.setProperty('--home-dusk', String(active ? Math.sin(Math.PI * progress) * 0.75 : 0));
    if (active) {
      // Both directions share the orbit: set on the right, then rise from the left.
      setBody(from === 0 ? sun : moon, from === 0 ? geometry.sun : geometry.moon,
        smooth(progress / 0.48), 1 - smooth((progress - 0.40) / 0.08));
      body(from === 0 ? moon : sun, from === 0 ? geometry.moon : geometry.sun,
        geometry.horizon, 1 - smooth((progress - 0.52) / 0.48), smooth((progress - 0.52) / 0.10), -30);
    } else {
      body(sun, geometry.sun, geometry.horizon, visual === 0 ? 0 : 1, visual === 0 ? 1 : 0, -30);
      body(moon, geometry.moon, geometry.horizon, visual === 1 ? 0 : 1, visual === 1 ? 1 : 0, -30);
    }
  }
  function cancel() {
    generation += 1;
    if (frame !== null && window.cancelAnimationFrame) window.cancelAnimationFrame(frame);
    frame = null;
    active = false;
  }
  function settle() {
    if (!scene) return;
    cancel();
    visual = target;
    geometry = measure();
    render();
    scene.classList.remove('is-transitioning');
  }
  function settleIfActive() { if (active) settle(); }
  function mount() {
    if (scene) return true;
    var candidate = document.querySelector('[data-home-scene]');
    if (!candidate) return false;
    sunAnchor = candidate.querySelector('[data-home-sun-anchor]');
    moonAnchor = candidate.querySelector('[data-home-moon-anchor]');
    horizon = candidate.querySelector('[data-home-horizon]');
    sun = candidate.querySelector('[data-home-sun]');
    moon = candidate.querySelector('[data-home-moon]');
    if (!sunAnchor || !moonAnchor || !horizon || !sun || !moon) return false;
    scene = candidate;
    fullMotion = candidate.getAttribute('data-home-motion') === 'full';
    visual = target = document.documentElement.getAttribute('data-theme') === 'dark' ? 1 : 0;
    // Luôn chạy hoạt ảnh ngày/đêm, không theo prefers-reduced-motion của hệ điều hành (quyết định sản phẩm).
    media = null;
    if (media && media.addEventListener) media.addEventListener('change', function (event) {
      if (event.matches && !fullMotion) settleIfActive();
    });
    window.addEventListener('resize', settleIfActive);
    window.addEventListener('scroll', settleIfActive, { passive: true, capture: true });
    window.addEventListener('pagehide', settleIfActive);
    document.addEventListener('visibilitychange', function () { if (document.hidden) settleIfActive(); });
    return true;
  }
  function beginCycle() {
    cancel();
    geometry = measure();
    from = visual;
    to = 1 - visual;
    progress = 0;
    active = true;
    scene.classList.add('is-transitioning');
    var token = generation, started = now();
    render();
    function tick(time) {
      if (token !== generation) return;
      var elapsed = clamp((time - started) / DURATION);
      progress = smooth(elapsed);
      render();
      if (elapsed === 1) {
        visual = to;
        if (target !== visual) beginCycle();
        else settle();
        return;
      }
      frame = window.requestAnimationFrame(tick);
    }
    frame = window.requestAnimationFrame(tick);
  }
  function sync(theme, animate) {
    if (!mount()) return theme;
    target = theme === 'dark' ? 1 : 0;
    if (!animate || (media && media.matches && !fullMotion) || document.hidden || !window.requestAnimationFrame) {
      settle();
    } else if (!active) {
      if (target === visual) settle();
      else beginCycle();
    }
    // An active half-cycle finishes before the latest request starts the next one.
    return theme;
  }
  return { sync: sync };
});
