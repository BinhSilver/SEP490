(function () {
  'use strict';
  window.NotebookKanjiCard = {
    create: function () {
      var $ = function (id) { return document.getElementById('notebookKanji' + id); };
      var animations = [], current = null, diagramMotion = null, currentDiagram = null;
      var namespace = 'http://www.w3.org/2000/svg';
      function stopStrokes() { animations.forEach(function (animation) { animation.cancel(); }); animations = []; }
      function stopDiagram() { if (diagramMotion) diagramMotion.destroy(); diagramMotion = null; }
      function stop() { stopStrokes(); stopDiagram(); }
      function svg(tag, attributes, text) {
        var element = document.createElementNS(namespace, tag);
        Object.keys(attributes || {}).forEach(function (key) { element.setAttribute(key, attributes[key]); });
        if (text != null) element.textContent = text;
        return element;
      }
      function diagram() {
        var canvas = $('Diagram'); canvas.replaceChildren(); currentDiagram = null;
        var data = current.kanji || {};
        var parts = Array.from(new Set(data.components || [])).filter(function (ch) { return ch !== current.expression; });
        canvas.hidden = !parts.length; $('Motion').hidden = !parts.length; $('NoRelations').hidden = !!parts.length;
        if (!parts.length) return;
        var width = 280, height = Math.max(160, (parts.length - 1) * 78 + 80);
        var nodes = parts.map(function (ch, i) {
          return { node: { character: ch }, x: 48, y: parts.length === 1 ? height / 2 : 40 + i * 78, selected: false };
        });
        var target = { node: { character: current.expression }, x: 232, y: height / 2, selected: true };
        var edges = nodes.map(function (item) {
          var dx = target.x - item.x, dy = target.y - item.y, distance = Math.hypot(dx, dy);
          return { from: item.node.character, to: current.expression, points: [
            { x: item.x + dx / distance * 26, y: item.y + dy / distance * 26 },
            { x: target.x - dx / distance * 28, y: target.y - dy / distance * 28 }
          ] };
        });
        nodes.push(target); currentDiagram = { nodes: nodes, edges: edges, width: width, height: height };
        canvas.style.aspectRatio = width + '/' + height;
        var drawing = svg('svg', { viewBox: '0 0 ' + width + ' ' + height, 'aria-hidden': 'true' });
        var defs = svg('defs'), marker = svg('marker', { id: 'notebookGraphArrow', viewBox: '0 0 10 10', refX: 10, refY: 5, markerWidth: 6, markerHeight: 6, orient: 'auto' });
        marker.appendChild(svg('polygon', { points: '0,0 10,5 0,10' })); defs.appendChild(marker); drawing.appendChild(defs);
        edges.forEach(function (edge) {
          drawing.appendChild(svg('path', { 'data-from': edge.from, 'data-to': edge.to,
            'marker-end': 'url(#notebookGraphArrow)', d: 'M' + edge.points[0].x + ',' + edge.points[0].y + ' L' + edge.points[1].x + ',' + edge.points[1].y }));
        });
        canvas.appendChild(drawing);
        nodes.forEach(function (item) {
          var ch = item.node.character, button = document.createElement('button');
          button.type = 'button'; button.lang = 'ja'; button.dataset.part = ch;
          button.className = 'km-detail-graph-node' + (item.selected ? ' is-current' : '') + (ch.startsWith('CDP-') ? ' is-cdp' : '');
          button.textContent = ch; button.title = ch;
          button.setAttribute('aria-label', item.selected ? 'Chữ đang học: ' + ch : 'Xem thành phần ' + ch);
          if (item.selected) button.setAttribute('aria-current', 'true');
          button.style.left = item.x / width * 100 + '%'; button.style.top = item.y / height * 100 + '%'; canvas.appendChild(button);
        });
      }
      function animateDiagram() {
        if (currentDiagram && !diagramMotion && window.KanjiMapDiagram) diagramMotion = window.KanjiMapDiagram.mount($('Diagram'), currentDiagram);
      }
      function replay() {
        stopStrokes();
        if (!current || $('Study').hidden || !$('Writing').open) return;
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        Array.from($('Strokes').querySelectorAll('path')).forEach(function (path, index) {
          var length = path.getTotalLength();
          animations.push(path.animate([{ strokeDasharray: length, strokeDashoffset: length },
            { strokeDasharray: length, strokeDashoffset: 0 }],
          { duration: 450, delay: index * 520, fill: 'both', easing: 'ease-in-out' }));
        });
      }
      function prepare(card) {
        stop(); current = card && card.type === 'KANJI' ? card : null; currentDiagram = null;
        $('Study').hidden = true; $('Writing').open = false;
        $('Strokes').replaceChildren(); $('Diagram').replaceChildren();
        if (!current) return;
        var data = current.kanji || {};
        $('Writing').open = true;
        $('On').textContent = (data.on || []).join(' · ') || 'Chưa có';
        $('Kun').textContent = (data.kun || []).join(' · ') || 'Chưa có';
        $('Hint').textContent = data.memoryHint || ''; $('Hint').hidden = !data.memoryHint;
        (data.strokes || []).forEach(function (d) {
          var path = svg('path', { d: d }); $('Strokes').appendChild(path);
        });
        $('Strokes').setAttribute('aria-label', 'Thứ tự nét chữ ' + current.expression);
        $('Strokes').hidden = !(data.strokes || []).length;
        $('Replay').hidden = $('Strokes').hidden; $('NoStrokes').hidden = !$('Strokes').hidden;
        diagram();
      }
      $('Writing').addEventListener('toggle', function () { if (this.open) replay(); else stop(); });
      $('Replay').addEventListener('click', replay);
      $('Diagram').addEventListener('click', function (event) {
        var button = event.target.closest('[data-part]');
        if (button && !button.classList.contains('is-current')) window.open('/dictionary/kanji-map?kanji=' + encodeURIComponent(button.dataset.part), '_blank', 'noopener');
      });
      return { prepare: prepare, reveal: function (show) { $('Study').hidden = !current || !show; if (current && show) { replay(); animateDiagram(); } else stop(); }, stop: stop };
    }
  };
})();
