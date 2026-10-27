(function () {
  'use strict';
  window.NotebookKanjiCard = {
    create: function () {
      var $ = function (id) { return document.getElementById('notebookKanji' + id); };
      var animations = [], current = null;
      function stop() { animations.forEach(function (animation) { animation.cancel(); }); animations = []; }
      function links(id, characters) {
        var container = $(id); container.replaceChildren();
        (characters || []).forEach(function (character) {
          var link = document.createElement('a'); link.textContent = character;
          link.lang = 'ja'; link.href = '/dictionary/kanji-map?kanji=' + encodeURIComponent(character);
          link.target = '_blank'; link.rel = 'noopener'; container.appendChild(link);
        });
        if (!container.childNodes.length) container.textContent = 'Chưa có liên kết trong dữ liệu lược đồ.';
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
        stop(); current = card && card.type === 'KANJI' ? card : null;
        $('Study').hidden = true; $('Writing').open = false; $('Relations').open = false;
        $('Strokes').replaceChildren();
        if (!current) return;
        var data = current.kanji || {};
        $('Readings').textContent = 'On: ' + ((data.on || []).join(' · ') || 'Chưa có') +
          ' | Kun: ' + ((data.kun || []).join(' · ') || 'Chưa có');
        $('Hint').textContent = data.memoryHint || ''; $('Hint').hidden = !data.memoryHint;
        (data.strokes || []).forEach(function (d) {
          var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
          path.setAttribute('d', d); $('Strokes').appendChild(path);
        });
        $('Strokes').setAttribute('aria-label', 'Thứ tự nét chữ ' + current.expression);
        $('Strokes').hidden = !(data.strokes || []).length;
        $('Replay').hidden = $('Strokes').hidden; $('NoStrokes').hidden = !$('Strokes').hidden;
        links('Components', data.components); links('UsedIn', data.usedIn);
        $('Map').href = '/dictionary/kanji-map?kanji=' + encodeURIComponent(current.expression);
      }
      $('Writing').addEventListener('toggle', function () { if (this.open) replay(); else stop(); });
      $('Replay').addEventListener('click', replay);
      return { prepare: prepare, reveal: function () { $('Study').hidden = !current; }, stop: stop };
    }
  };
})();
