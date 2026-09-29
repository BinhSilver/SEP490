(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasTheme = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.init, { once: true });
    } else {
      api.init();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var STORAGE_KEY = 'jlas-theme';
  var DARK = 'dark';
  var LIGHT = 'light';
  var mountedCord = null;

  function systemPrefersDark() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    } catch (error) { return false; }
  }

  function prefersReducedMotion() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (error) { return false; }
  }

  function storedTheme() {
    try {
      var value = window.localStorage.getItem(STORAGE_KEY);
      return value === DARK || value === LIGHT ? value : null;
    } catch (error) { return null; }
  }

  function resolveTheme() {
    return storedTheme() || (systemPrefersDark() ? DARK : LIGHT);
  }

  function applyTheme(theme, persist) {
    if (theme !== DARK && theme !== LIGHT) theme = resolveTheme();
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.classList.remove(LIGHT, DARK);
    document.documentElement.classList.add(theme);
    if (persist !== false) {
      try { window.localStorage.setItem(STORAGE_KEY, theme); } catch (error) { /* ignore */ }
    }
    if (mountedCord) mountedCord.setPulled(theme === LIGHT);
    return theme;
  }

  function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') === DARK ? DARK : LIGHT;
    return applyTheme(current === DARK ? LIGHT : DARK);
  }

  /* ------------------------------------------------------------------
   * PullCord — a Verlet rope hanging from the top of the viewport.
   * Pull it past a detent to toggle the theme, or click / Enter / Space.
   * ------------------------------------------------------------------ */

  var CORD = {
    segments: 16,
    segLen: 13,
    anchorX: 24,
    anchorY: 0,
    gravity: 0.5,
    damping: 0.985,
    iterations: 3,
    maxStretch: 1.6,
    fireRatio: 1.2,
    grabRadius: 48
  };

  function mountPullcord(onToggle) {
    if (!document.body) return null;

    var container = document.createElement('div');
    container.className = 'pullcord';
    container.id = 'jlasThemePullcord';
    container.setAttribute('title', 'Kéo dây hoặc bấm để đổi giao diện');
    container.setAttribute('role', 'button');
    container.setAttribute('tabindex', '0');
    container.setAttribute('aria-label', 'Bật tắt chế độ sáng tối');
    container.setAttribute('aria-pressed', 'false');

    var NS = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('class', 'pullcord__svg');
    svg.setAttribute('viewBox', '0 0 48 280');
    svg.setAttribute('aria-hidden', 'true');

    var rope = document.createElementNS(NS, 'polyline');
    rope.setAttribute('class', 'pullcord__rope');
    svg.appendChild(rope);

    var handle = document.createElementNS(NS, 'circle');
    handle.setAttribute('class', 'pullcord__handle');
    handle.setAttribute('r', '12');
    svg.appendChild(handle);

    container.appendChild(svg);
    document.body.appendChild(container);

    var points = [];
    var i;
    for (i = 0; i <= CORD.segments; i++) {
      points.push({
        x: CORD.anchorX,
        y: CORD.anchorY + i * CORD.segLen,
        px: CORD.anchorX,
        py: CORD.anchorY + i * CORD.segLen
      });
    }
    var lastIndex = points.length - 1;
    var restLength = CORD.segments * CORD.segLen;
    var motionOk = !prefersReducedMotion();

    var grabbed = false;
    var activePointer = null;
    var moved = false;
    var fired = false;
    var startPoint = null;
    var rafId = null;
    var stillFrames = 0;

    function setPulled(pulled) {
      container.setAttribute('aria-pressed', String(!!pulled));
      container.classList.toggle('is-pulled', !!pulled);
    }

    function toggle() {
      var theme = onToggle();
      setPulled(theme === LIGHT);
    }

    function render() {
      var pts = [];
      var j;
      for (j = 0; j < points.length; j++) pts.push(points[j].x + ',' + points[j].y);
      rope.setAttribute('points', pts.join(' '));
      var end = points[lastIndex];
      handle.setAttribute('cx', end.x);
      handle.setAttribute('cy', end.y);
    }

    function localPoint(event) {
      var rect = svg.getBoundingClientRect();
      return { x: (event.clientX - rect.left) * 48 / rect.width, y: (event.clientY - rect.top) * 280 / rect.height };
    }

    function distanceFromAnchor() {
      var p = points[lastIndex];
      var dx = p.x - CORD.anchorX;
      var dy = p.y - CORD.anchorY;
      return Math.sqrt(dx * dx + dy * dy);
    }

    function moveHandleTo(target) {
      var dx = target.x - CORD.anchorX;
      var dy = target.y - CORD.anchorY;
      var dist = Math.sqrt(dx * dx + dy * dy) || 0.0001;
      var maxDist = restLength * CORD.maxStretch;
      if (dist > maxDist) {
        target = { x: CORD.anchorX + (dx / dist) * maxDist, y: CORD.anchorY + (dy / dist) * maxDist };
      }
      var p = points[lastIndex];
      p.x = target.x;
      p.y = target.y;
      p.px = target.x;
      p.py = target.y;
    }

    function step() {
      var j, p;
      for (j = 1; j <= lastIndex; j++) {
        p = points[j];
        if (grabbed && j === lastIndex) continue;
        var vx = (p.x - p.px) * CORD.damping;
        var vy = (p.y - p.py) * CORD.damping;
        p.px = p.x;
        p.py = p.y;
        p.x += vx;
        p.y += vy + CORD.gravity;
      }
      var iter;
      for (iter = 0; iter < CORD.iterations; iter++) {
        for (j = 1; j <= lastIndex; j++) {
          var a = points[j - 1];
          var b = points[j];
          var dx = b.x - a.x;
          var dy = b.y - a.y;
          var dist = Math.sqrt(dx * dx + dy * dy) || 0.0001;
          var diff = (dist - CORD.segLen) / dist;
          if (grabbed && j === lastIndex) {
            a.x += dx * diff;
            a.y += dy * diff;
          } else if (j === 1) {
            b.x -= dx * diff;
            b.y -= dy * diff;
          } else {
            b.x -= dx * 0.5 * diff;
            b.y -= dy * 0.5 * diff;
            a.x += dx * 0.5 * diff;
            a.y += dy * 0.5 * diff;
          }
        }
        points[0].x = CORD.anchorX;
        points[0].y = CORD.anchorY;
      }
      render();
    }

    function loop() {
      step();
      var maxV = 0;
      var j, p;
      for (j = 1; j <= lastIndex; j++) {
        p = points[j];
        var vx = Math.abs(p.x - p.px);
        var vy = Math.abs(p.y - p.py);
        if (vx > maxV) maxV = vx;
        if (vy > maxV) maxV = vy;
      }
      if (grabbed || maxV > 0.02) {
        stillFrames = 0;
        rafId = requestAnimationFrame(loop);
      } else {
        stillFrames += 1;
        if (stillFrames < 8) {
          rafId = requestAnimationFrame(loop);
        } else {
          rafId = null;
        }
      }
    }

    function startLoop() {
      if (rafId == null) rafId = requestAnimationFrame(loop);
    }

    function onPointerDown(event) {
      if (!event.isPrimary || event.button !== 0 || grabbed) return;
      var pt = localPoint(event);
      var p = points[lastIndex];
      var dx = pt.x - p.x;
      var dy = pt.y - p.y;
      if (dx * dx + dy * dy > CORD.grabRadius * CORD.grabRadius) return;
      grabbed = true;
      activePointer = event.pointerId;
      moved = false;
      fired = false;
      startPoint = pt;
      moveHandleTo(pt);
      window.addEventListener('pointermove', onPointerMove);
      window.addEventListener('pointerup', onPointerUp);
      window.addEventListener('pointercancel', onPointerUp);
      if (motionOk) startLoop();
    }

    function onPointerMove(event) {
      if (!grabbed || event.pointerId !== activePointer || !motionOk) return;
      var pt = localPoint(event);
      var dx = pt.x - startPoint.x;
      var dy = pt.y - startPoint.y;
      if (dx * dx + dy * dy > 16) moved = true;
      moveHandleTo(pt);
      if (!fired && distanceFromAnchor() / restLength > CORD.fireRatio) {
        fired = true;
        toggle();
      }
      startLoop();
    }

    function onPointerUp(event) {
      if (!grabbed || event.pointerId !== activePointer) return;
      grabbed = false;
      activePointer = null;
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
      window.removeEventListener('pointercancel', onPointerUp);
      if (event.type === 'pointerup' && !moved && !fired) toggle();
      if (motionOk) startLoop();
    }

    container.addEventListener('keydown', function (event) {
      if (!event.repeat && (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar')) {
        event.preventDefault();
        toggle();
      }
    });
    container.addEventListener('click', function (event) {
      // Assistive-technology activation has no preceding pointer gesture.
      if (event.detail === 0) toggle();
    });

    svg.addEventListener('pointerdown', onPointerDown);

    render();
    if (motionOk) startLoop();

    return { setPulled: setPulled, toggle: toggle };
  }

  function init() {
    if (mountedCord || !document.body) return;
    var theme = applyTheme(resolveTheme(), false);
    mountedCord = mountPullcord(toggleTheme);
    if (mountedCord) mountedCord.setPulled(theme === LIGHT);
    window.addEventListener('storage', function (event) {
      if (event.key === STORAGE_KEY) applyTheme(resolveTheme(), false);
    });
    if (window.matchMedia) {
      var system = window.matchMedia('(prefers-color-scheme: dark)');
      if (system.addEventListener) system.addEventListener('change', function () {
        if (!storedTheme()) applyTheme(resolveTheme(), false);
      });
    }
  }

  return {
    resolveTheme: resolveTheme,
    applyTheme: applyTheme,
    toggleTheme: toggleTheme,
    init: init
  };
});
