(function () {
  'use strict';
  window.NotebookKanjiCard = {
    create: function () {
      var $ = function (id) { return document.getElementById('notebookKanji' + id); };
      var animations = [], current = null, expanded = false;
      var namespace = 'http://www.w3.org/2000/svg';
      function stop() { animations.forEach(function (animation) { animation.cancel(); }); animations = []; }
      function svg(tag, attributes, text) {
        var element = document.createElementNS(namespace, tag);
        Object.keys(attributes || {}).forEach(function (key) { element.setAttribute(key, attributes[key]); });
        if (text != null) element.textContent = text;
        return element;
      }
      function diagram() {
        var canvas = $('Diagram'); canvas.replaceChildren();
        var data = current && current.kanji || {};
        var allLeft = Array.from(new Set(data.components || [])).filter(function (ch) { return ch !== current.expression; });
        var allRight = Array.from(new Set(data.usedIn || [])).filter(function (ch) { return ch !== current.expression; });
        var left = expanded ? allLeft : allLeft.slice(0, 4);
        var right = expanded ? allRight : allRight.slice(0, 4);
        var extra = allLeft.length + allRight.length - left.length - right.length;
        $('NoRelations').hidden = allLeft.length + allRight.length > 0;
        $('More').hidden = !extra && !expanded;
        $('More').textContent = expanded ? 'Thu gọn liên kết' : 'Xem thêm ' + extra + ' liên kết';
        $('More').setAttribute('aria-expanded', String(expanded));
        var height = Math.max(220, Math.max(left.length, right.length) * 58 + 56);
        var centerY = 38 + (height - 38) / 2;
        canvas.setAttribute('viewBox', '0 0 360 ' + height);
        canvas.setAttribute('aria-label', 'Thành phần tạo nên ' + current.expression + ' và các chữ có chứa ' + current.expression);
        canvas.append(svg('text', { x: 48, y: 17, class: 'notebook-graph-caption' }, 'Thành phần'),
          svg('text', { x: 180, y: 17, class: 'notebook-graph-caption' }, 'Đang ôn'),
          svg('text', { x: 312, y: 17, class: 'notebook-graph-caption' }, 'Chữ liên quan'));
        var defs = svg('defs'), marker = svg('marker', { id: 'notebookGraphArrow', viewBox: '0 0 6 6', refX: 5, refY: 3, markerWidth: 5, markerHeight: 5, orient: 'auto' });
        marker.appendChild(svg('path', { d: 'M0 0L6 3L0 6Z', class: 'notebook-graph-arrow' }));
        defs.appendChild(marker); canvas.appendChild(defs);
        function y(index, count) { return centerY + (index - (count - 1) / 2) * 58; }
        function link(fromX, fromY, toX, toY) {
          canvas.appendChild(svg('path', { d: 'M' + fromX + ' ' + fromY + ' C' + ((fromX + toX) / 2) + ' ' + fromY + ' ' + ((fromX + toX) / 2) + ' ' + toY + ' ' + toX + ' ' + toY,
            class: 'notebook-graph-edge', 'marker-end': 'url(#notebookGraphArrow)' }));
        }
        left.forEach(function (ch, i) { link(72, y(i, left.length), 146, centerY); });
        right.forEach(function (ch, i) { link(214, centerY, 287, y(i, right.length)); });
        function node(ch, x, nodeY, center) {
          var group = svg(center ? 'g' : 'a', { class: 'notebook-graph-node' + (center ? ' is-current' : '') });
          if (!center) {
            group.setAttribute('href', '/dictionary/kanji-map?kanji=' + encodeURIComponent(ch));
            group.setAttribute('target', '_blank'); group.setAttribute('rel', 'noopener');
            group.setAttribute('aria-label', 'Xem liên kết chữ ' + ch);
          }
          var label = ch.startsWith('CDP-') ? ch.slice(4) : ch;
          group.append(svg('title', {}, ch), svg('circle', { cx: x, cy: nodeY, r: center ? 32 : 24 }),
            svg('text', { x: x, y: nodeY, class: ch.startsWith('CDP-') ? 'is-code' : '', lang: 'ja' }, label));
          canvas.appendChild(group);
        }
        left.forEach(function (ch, i) { node(ch, 48, y(i, left.length), false); });
        right.forEach(function (ch, i) { node(ch, 312, y(i, right.length), false); });
        node(current.expression, 180, centerY, true);
      }
      function replay() {
        stop();
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
        stop(); current = card && card.type === 'KANJI' ? card : null; expanded = false;
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
      $('More').addEventListener('click', function () { if (current) { expanded = !expanded; diagram(); } });
      return { prepare: prepare, reveal: function (show) { $('Study').hidden = !current || !show; if (show) replay(); else stop(); }, stop: stop };
    }
  };
})();
