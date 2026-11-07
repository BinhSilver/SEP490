(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.KanjiMapDiagram = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  function createSimulation(diagram) {
    var nodes = diagram.nodes.map(function (item, i) {
      return { character: item.node.character, x: item.x, y: item.y, homeX: item.x, homeY: item.y,
        vx: 0, vy: 0, phase: i * 2.4, pinned: false };
    });
    var byCharacter = new Map(nodes.map(function (n) { return [n.character, n]; }));
    var links = diagram.edges.map(function (edge) {
      var a = byCharacter.get(edge.from), b = byCharacter.get(edge.to);
      return { from: a, to: b, length: Math.hypot(b.x - a.x, b.y - a.y) };
    });
    function bound(n) {
      n.x = Math.max(30, Math.min(diagram.width - 30, n.x));
      n.y = Math.max(30, Math.min(diagram.height - 30, n.y));
    }
    function pin(character, x, y) {
      var n = byCharacter.get(character); if (!n) return;
      n.pinned = true; n.x = x; n.y = y; n.vx = n.vy = 0; bound(n);
    }
    function release(character) { var n = byCharacter.get(character); if (n) n.pinned = false; }
    function step(milliseconds, elapsed) {
      var dt = Math.max(0, Math.min(2, milliseconds / (1000 / 60)));
      if (!dt) return;
      nodes.forEach(function (n) {
        if (n.pinned) return;
        var t = elapsed / 1000;
        n.vx += (n.homeX + Math.sin(t * .65 + n.phase) * 7 - n.x) * .014 * dt;
        n.vy += (n.homeY + Math.cos(t * .55 + n.phase) * 6 - n.y) * .014 * dt;
      });
      for (var i = 0; i < nodes.length; i++) for (var j = i + 1; j < nodes.length; j++) {
        var a = nodes[i], b = nodes[j], dx = b.x - a.x, dy = b.y - a.y;
        var distance = Math.hypot(dx, dy);
        if (distance >= 64) continue;
        if (distance < .01) { dx = 1; dy = 0; distance = 1; }
        var push = (64 - distance) * .08 * dt;
        if (!a.pinned) { a.vx -= dx / distance * push; a.vy -= dy / distance * push; }
        if (!b.pinned) { b.vx += dx / distance * push; b.vy += dy / distance * push; }
      }
      links.forEach(function (link) {
        var a = link.from, b = link.to, dx = b.x - a.x, dy = b.y - a.y;
        var length = Math.max(1, Math.hypot(dx, dy)), pull = (length - link.length) * .0015 * dt;
        if (!a.pinned) { a.vx += dx / length * pull; a.vy += dy / length * pull; }
        if (!b.pinned) { b.vx -= dx / length * pull; b.vy -= dy / length * pull; }
      });
      nodes.forEach(function (n) {
        if (n.pinned) return;
        var damping = Math.pow(.83, dt);
        n.vx *= damping; n.vy *= damping;
        n.x += n.vx * dt; n.y += n.vy * dt; bound(n);
      });
    }
    function segments() {
      return links.map(function (link) {
        var a = link.from, b = link.to, dx = b.x - a.x, dy = b.y - a.y;
        var distance = Math.max(.01, Math.hypot(dx, dy));
        // Keep very short, temporarily overlapping dragged links finite and forward-facing.
        var trim = Math.min(1, distance / 58);
        return { from: a.character, to: b.character,
          x1: a.x + dx / distance * 26 * trim, y1: a.y + dy / distance * 26 * trim,
          x2: b.x - dx / distance * 28 * trim, y2: b.y - dy / distance * 28 * trim };
      });
    }
    return { nodes: nodes, links: links, pin: pin, release: release, step: step, segments: segments };
  }

  function mount(element, diagram) {
    var simulation = createSimulation(diagram), doc = element.ownerDocument, win = doc.defaultView;
    var abort = new win.AbortController(), media = win.matchMedia('(prefers-reduced-motion: reduce)');
    var toggle = element.closest('section').querySelector('[data-diagram-motion]');
    var buttons = new Map(Array.from(element.querySelectorAll('.km-detail-graph-node')).map(function (b) { return [b.dataset.part, b]; }));
    var paths = Array.from(element.querySelectorAll('path[data-from]'));
    var svg = element.querySelector('svg');
    var particles = paths.map(function () {
      var circle = doc.createElementNS('http://www.w3.org/2000/svg', 'circle');
      circle.setAttribute('r', '2.3'); circle.setAttribute('class', 'km-detail-flow'); svg.appendChild(circle); return circle;
    });
    var paused = media.matches, onScreen = false, destroyed = false, frame = null, last = null, elapsed = 0;
    var drag = null, suppressClick = false;
    function listen(target, type, handler, options) { target.addEventListener(type, handler, Object.assign({}, options, { signal: abort.signal })); }
    function playing() { return !destroyed && !paused && onScreen && doc.visibilityState !== 'hidden'; }
    function draw() {
      simulation.nodes.forEach(function (n) {
        var b = buttons.get(n.character);
        b.style.left = n.x / diagram.width * 100 + '%'; b.style.top = n.y / diagram.height * 100 + '%';
      });
      simulation.segments().forEach(function (s, i) {
        paths[i].setAttribute('d', 'M' + s.x1 + ',' + s.y1 + ' L' + s.x2 + ',' + s.y2);
        var phase = (elapsed / 1700 + i * .29) % 1;
        particles[i].setAttribute('cx', s.x1 + (s.x2 - s.x1) * phase);
        particles[i].setAttribute('cy', s.y1 + (s.y2 - s.y1) * phase);
        particles[i].style.display = playing() ? '' : 'none';
      });
    }
    function tick(now) {
      frame = null;
      if (!playing() || !element.isConnected) return;
      var dt = last == null ? 1000 / 60 : Math.min(34, now - last); last = now; elapsed += dt;
      simulation.step(dt, elapsed); draw(); frame = win.requestAnimationFrame(tick);
    }
    function sync() {
      if (!playing() && frame != null) { win.cancelAnimationFrame(frame); frame = null; }
      if (playing() && frame == null) { last = null; frame = win.requestAnimationFrame(tick); }
      toggle.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">' +
        (paused ? '<path d="m8 5 10 7-10 7Z"/>' : '<path d="M8 5v14M16 5v14"/>') + '</svg>';
      toggle.setAttribute('aria-label', paused ? 'Bật chuyển động sơ đồ' : 'Tạm dừng chuyển động sơ đồ');
      toggle.title = paused ? 'Bật chuyển động' : 'Tạm dừng';
      toggle.setAttribute('aria-pressed', String(paused)); draw();
    }
    listen(toggle, 'click', function () { paused = !paused; sync(); });
    listen(media, 'change', function () { paused = media.matches; sync(); });
    listen(doc, 'visibilitychange', sync);
    var observer = new win.IntersectionObserver(function (entries) { onScreen = entries[0].isIntersecting; sync(); });
    observer.observe(element);
    listen(element, 'pointerdown', function (event) {
      var button = event.target.closest('.km-detail-graph-node');
      if (!button || event.button !== 0 || drag) return;
      suppressClick = false;
      drag = { id: event.pointerId, character: button.dataset.part, button: button,
        x: event.clientX, y: event.clientY, moved: false };
      button.setPointerCapture(event.pointerId);
    });
    listen(element, 'pointermove', function (event) {
      if (!drag || event.pointerId !== drag.id) return;
      if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) > 4) drag.moved = true;
      if (!drag.moved) return;
      event.preventDefault(); element.classList.add('is-dragging');
      var rect = element.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      simulation.pin(drag.character, (event.clientX - rect.left) / rect.width * diagram.width,
        (event.clientY - rect.top) / rect.height * diagram.height); draw();
    });
    function release(event) {
      if (!drag || event.pointerId !== drag.id) return;
      var previous = drag; drag = null; suppressClick = previous.moved;
      simulation.release(previous.character); element.classList.remove('is-dragging');
      if (previous.button.hasPointerCapture(previous.id)) previous.button.releasePointerCapture(previous.id);
    }
    listen(element, 'pointerup', release); listen(element, 'pointercancel', release); listen(element, 'lostpointercapture', release);
    listen(element, 'click', function (event) {
      if (suppressClick && event.detail !== 0 && event.target.closest('.km-detail-graph-node')) {
        event.preventDefault(); event.stopPropagation(); suppressClick = false;
      }
    }, { capture: true });
    sync();
    return { destroy: function () {
      destroyed = true;
      if (frame != null) win.cancelAnimationFrame(frame);
      frame = null; observer.disconnect(); abort.abort();
      if (drag) { simulation.release(drag.character); if (drag.button.hasPointerCapture(drag.id)) drag.button.releasePointerCapture(drag.id); drag = null; }
      particles.forEach(function (p) { p.remove(); }); element.classList.remove('is-dragging');
    } };
  }
  return { createSimulation: createSimulation, mount: mount };
});
