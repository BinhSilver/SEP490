/* [JLAS-W0108] jpath-writing.js — stroke-order animation and tracing practice ("Luyện viết").
 *
 * window.JpathWriting = { open(view), openList(views), bind(root), parseTrigger(json), createTraceBoard(cellCount),
 *                         animatable(view) }
 *   - A page marks a control with data-jpath-writing='{"itemType":"VOCAB","code":"JV-…"}' (one item) or
 *     '{"today":"VOCAB"}' (today's new items of a tab). bind(root) wires each one once; it runs on DOMContentLoaded and
 *     on every document 'jpath:rendered' {detail:{root}}. A non-button element gets a "Luyện viết" button appended.
 *   - The dialog (class="jp-writing no-inline-dictionary" role="dialog") has two tabs: "Thứ tự nét" plays each
 *     character with an SVG through window.DictStrokeOrder.mount (+ "Xem lại nét"; the tab is hidden when no
 *     character has an SVG) and "Tập viết": one 米-grid cell per character, faint Klee One guide, a canvas
 *     (touch-action: none), Hoàn tác / Xóa ô / Xóa tất cả. Nothing is scored. openList adds Trước / Sau. Esc closes.
 * Needs jpath-api.js; uses /dictionary/js/stroke-order.js and handwriting-core.js (point simplification) when loaded.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathWriting = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt/writing/';
  var ITEM_TYPES = ['VOCAB', 'KANJI', 'KANA'];
  var TABS = ['VOCAB', 'KANJI', 'GRAMMAR', 'KANA', 'SKILL'];
  var BOUND = 'data-jpath-writing-bound';
  var MSG_EMPTY = 'Hôm nay chưa có mục mới để luyện viết.';
  var MSG_FAILED = 'Chưa tải được bài luyện viết. Thử lại sau.';

  // ---- pure helpers ----------------------------------------------------------------------------------------

  /** The data-jpath-writing value: {itemType, code} or {today}; null when unusable. */
  function parseTrigger(json) {
    var raw;
    try {
      raw = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (e) {
      return null;
    }
    if (!raw || typeof raw !== 'object') return null;
    if (typeof raw.today === 'string') {
      var tab = raw.today.trim().toUpperCase();
      return TABS.indexOf(tab) >= 0 ? { today: tab } : null;
    }
    var type = typeof raw.itemType === 'string' ? raw.itemType.trim().toUpperCase() : '';
    var code = typeof raw.code === 'string' ? raw.code.trim() : '';
    if (ITEM_TYPES.indexOf(type) < 0 || !code || code.length > 24) return null;
    return { itemType: type, code: code };
  }

  /** Characters of a view that can be animated (they have a stroke-order SVG). */
  function animatable(view) {
    var chars = (view && view.characters) || [];
    return chars.filter(function (c) { return c && c.hasSvg && c.svgUrl; });
  }

  /**
   * Stroke model of the tracing cells: addStroke(cell, points), undo() removes the last stroke drawn anywhere,
   * clearCell(cell), clearAll(), strokes(cell), total(). Returns the cell an undo touched (or -1).
   */
  function createTraceBoard(cellCount) {
    var n = Math.max(0, cellCount | 0);
    var cells = [];
    for (var i = 0; i < n; i++) cells.push([]);
    var history = [];

    function valid(cell) { return cell >= 0 && cell < n; }

    return {
      cellCount: n,
      addStroke: function (cell, points) {
        if (!valid(cell) || !points || !points.length) return false;
        cells[cell].push(points.slice());
        history.push(cell);
        return true;
      },
      undo: function () {
        while (history.length) {
          var cell = history.pop();
          if (cells[cell].length) { cells[cell].pop(); return cell; }
        }
        return -1;
      },
      clearCell: function (cell) {
        if (!valid(cell)) return;
        cells[cell] = [];
        history = history.filter(function (c) { return c !== cell; });
      },
      clearAll: function () {
        for (var j = 0; j < n; j++) cells[j] = [];
        history = [];
      },
      strokes: function (cell) { return valid(cell) ? cells[cell].slice() : []; },
      total: function () { return cells.reduce(function (s, c) { return s + c.length; }, 0); }
    };
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ---- dialog ----------------------------------------------------------------------------------------------

  var GRID = '<svg class="jp-writing__grid" viewBox="0 0 100 100" aria-hidden="true" focusable="false">'
    + '<rect x="1" y="1" width="98" height="98" fill="none" stroke="currentColor" stroke-width="1.5"/>'
    + '<path d="M50 0V100M0 50H100M0 0L100 100M100 0L0 100" fill="none" stroke="currentColor" stroke-width=".8"'
    + ' stroke-dasharray="4 3"/></svg>';

  var current = null;

  function apiClient() {
    return (root && root.JpathApi) || (typeof require === 'function' ? require('./jpath-api.js') : null);
  }

  function headerHtml(view) {
    var meta = [view.reading, view.hanViet, view.meaning].filter(function (s) { return s; }).map(esc).join(' · ');
    return '<h2 class="jp-writing__title" id="jp-writing-title"><span class="jp-kanji">' + esc(view.word)
      + '</span></h2>' + (meta ? '<p class="jp-writing__meta">' + meta + '</p>' : '');
  }

  function bodyHtml(view, nav) {
    var anim = animatable(view);
    var chars = view.characters || [];
    var strokes = anim.map(function (c, i) {
      var count = c.strokeCount ? '<span class="jp-writing__count">' + c.strokeCount + ' nét</span>' : '';
      return '<figure class="jp-writing__stroke"><div class="jp-writing__svg" data-stroke-index="' + i + '"></div>'
        + '<figcaption class="jp-kanji">' + esc(c['char']) + count + '</figcaption></figure>';
    }).join('');
    var cells = chars.map(function (c, i) {
      return '<div class="jp-writing__cell" data-cell="' + i + '">' + GRID
        + '<span class="jp-writing__ghost jp-kanji" aria-hidden="true">' + esc(c['char']) + '</span>'
        + '<canvas class="jp-writing__canvas" aria-label="Ô tập viết chữ ' + esc(c['char']) + '"></canvas></div>';
    }).join('');
    var navHtml = nav ? '<div class="jp-writing__nav">'
      + '<button type="button" class="jp-writing__btn" data-writing-prev' + (nav.index === 0 ? ' disabled' : '')
      + '>Trước</button><span class="jp-writing__pos">' + (nav.index + 1) + '/' + nav.total + '</span>'
      + '<button type="button" class="jp-writing__btn" data-writing-next'
      + (nav.index >= nav.total - 1 ? ' disabled' : '') + '>Sau</button></div>' : '';
    return '<div class="jp-writing__panel">' + headerHtml(view)
      + '<div class="jp-writing__tabs" role="tablist">'
      + '<button type="button" role="tab" class="jp-writing__tab" data-writing-tab="order"'
      + (anim.length ? '' : ' hidden') + '>Thứ tự nét</button>'
      + '<button type="button" role="tab" class="jp-writing__tab" data-writing-tab="trace">Tập viết</button></div>'
      + '<section class="jp-writing__pane" data-writing-pane="order"' + (anim.length ? '' : ' hidden') + '>'
      + '<div class="jp-writing__strokes">' + strokes + '</div>'
      + '<button type="button" class="jp-writing__btn" data-writing-replay>Xem lại nét</button></section>'
      + '<section class="jp-writing__pane" data-writing-pane="trace" hidden>'
      + '<div class="jp-writing__cells">' + cells + '</div>'
      + '<div class="jp-writing__tools">'
      + '<button type="button" class="jp-writing__btn" data-writing-undo>Hoàn tác</button>'
      + '<button type="button" class="jp-writing__btn" data-writing-clear-cell>Xóa ô</button>'
      + '<button type="button" class="jp-writing__btn" data-writing-clear-all>Xóa tất cả</button></div></section>'
      + navHtml
      + '<div class="jp-writing__foot"><button type="button" class="jp-writing__btn" data-writing-close>Đóng</button>'
      + '</div></div>';
  }

  function simplify(points) {
    var core = root && root.DictHandwritingCore;
    return core && typeof core.simplify === 'function' ? core.simplify(points, 1.5) : points;
  }

  /** Wires the tracing canvases of box to a fresh board. */
  function setupTrace(box, view) {
    var board = createTraceBoard((view.characters || []).length);
    var canvases = Array.prototype.slice.call(box.querySelectorAll('.jp-writing__canvas'));
    var active = 0;

    function size(canvas) {
      var ratio = root.devicePixelRatio || 1;
      var w = canvas.clientWidth || 120;
      var h = canvas.clientHeight || 120;
      if (canvas.width !== Math.round(w * ratio)) { canvas.width = Math.round(w * ratio); canvas.height = Math.round(h * ratio); }
      return ratio;
    }
    function redraw(cell, live) {
      var canvas = canvases[cell];
      if (!canvas) return;
      var ratio = size(canvas);
      var ctx = canvas.getContext('2d');
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.lineWidth = 5;
      ctx.strokeStyle = root.getComputedStyle ? root.getComputedStyle(canvas).color : '#222';
      board.strokes(cell).concat(live ? [live] : []).forEach(function (pts) {
        ctx.beginPath();
        ctx.moveTo(pts[0][0], pts[0][1]);
        for (var i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
        if (pts.length === 1) ctx.lineTo(pts[0][0] + .1, pts[0][1] + .1);
        ctx.stroke();
      });
    }

    canvases.forEach(function (canvas, cell) {
      var points = null;
      function at(ev) {
        var r = canvas.getBoundingClientRect();
        return [ev.clientX - r.left, ev.clientY - r.top];
      }
      canvas.addEventListener('pointerdown', function (ev) {
        ev.preventDefault();
        active = cell;
        points = [at(ev)];
        if (canvas.setPointerCapture) canvas.setPointerCapture(ev.pointerId);
      });
      canvas.addEventListener('pointermove', function (ev) {
        if (!points) return;
        points.push(at(ev));
        redraw(cell, points);
      });
      function end() {
        if (!points) return;
        board.addStroke(cell, simplify(points));
        points = null;
        redraw(cell);
      }
      canvas.addEventListener('pointerup', end);
      canvas.addEventListener('pointercancel', end);
    });

    box.querySelector('[data-writing-undo]').addEventListener('click', function () {
      var cell = board.undo();
      if (cell >= 0) redraw(cell);
    });
    box.querySelector('[data-writing-clear-cell]').addEventListener('click', function () {
      board.clearCell(active);
      redraw(active);
    });
    box.querySelector('[data-writing-clear-all]').addEventListener('click', function () {
      board.clearAll();
      canvases.forEach(function (_, i) { redraw(i); });
    });
    return { board: board, redrawAll: function () { canvases.forEach(function (_, i) { redraw(i); }); } };
  }

  function setupOrder(box, view) {
    var replays = [];
    var mount = root && root.DictStrokeOrder && root.DictStrokeOrder.mount;
    var holders = Array.prototype.slice.call(box.querySelectorAll('[data-stroke-index]'));
    var chars = animatable(view);
    var started = false;
    function start() {
      if (started || typeof mount !== 'function') return;
      started = true;
      holders.forEach(function (el, i) {
        Promise.resolve(mount(el, chars[i].svgUrl)).then(function (replay) {
          if (typeof replay === 'function') replays.push(replay);
          else el.closest('.jp-writing__stroke').hidden = true;
        });
      });
    }
    box.querySelector('[data-writing-replay]').addEventListener('click', function () {
      replays.forEach(function (r) { r(); });
    });
    return { start: start };
  }

  function render(view, nav) {
    var doc = root && root.document;
    if (!doc || !doc.body) return null;
    if (current) current.close(false);
    var opener = doc.activeElement;
    var box = doc.createElement('div');
    box.className = 'jp-writing no-inline-dictionary';
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-labelledby', 'jp-writing-title');
    box.innerHTML = bodyHtml(view, nav);
    var handle = { el: box, close: close };

    function onKey(ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); close(true); }
    }
    function close(restore) {
      doc.removeEventListener('keydown', onKey, true);
      if (box.parentNode) box.parentNode.removeChild(box);
      if (current === handle) current = null;
      if (restore !== false && opener && typeof opener.focus === 'function') opener.focus();
    }

    doc.addEventListener('keydown', onKey, true);
    doc.body.appendChild(box);
    current = handle;

    var order = setupOrder(box, view);
    var trace = setupTrace(box, view);
    function show(name) {
      Array.prototype.forEach.call(box.querySelectorAll('[data-writing-pane]'), function (p) {
        p.hidden = p.getAttribute('data-writing-pane') !== name;
      });
      Array.prototype.forEach.call(box.querySelectorAll('[data-writing-tab]'), function (t) {
        t.setAttribute('aria-selected', String(t.getAttribute('data-writing-tab') === name));
      });
      if (name === 'order') order.start();
      else trace.redrawAll();
    }
    Array.prototype.forEach.call(box.querySelectorAll('[data-writing-tab]'), function (t) {
      t.addEventListener('click', function () { show(t.getAttribute('data-writing-tab')); });
    });
    box.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t === box || (t && t.closest && t.closest('[data-writing-close]'))) close(true);
    });
    if (nav) {
      var prev = box.querySelector('[data-writing-prev]');
      var next = box.querySelector('[data-writing-next]');
      prev.addEventListener('click', function () { nav.go(nav.index - 1); });
      next.addEventListener('click', function () { nav.go(nav.index + 1); });
    }
    show(animatable(view).length ? 'order' : 'trace');
    var first = box.querySelector('[data-writing-tab]:not([hidden])');
    if (first) first.focus();
    return handle;
  }

  /** Opens the practice of one view. */
  function open(view) {
    if (!view) return null;
    return render(view, null);
  }

  /** Opens a list of views with Trước / Sau; an empty list shows a short note instead. */
  function openList(views) {
    var list = (views || []).filter(Boolean);
    if (!list.length) {
      return render({ word: 'Luyện viết hôm nay', meaning: MSG_EMPTY, characters: [] }, null);
    }
    function go(i) {
      var index = Math.max(0, Math.min(list.length - 1, i));
      return render(list[index], { index: index, total: list.length, go: go });
    }
    return go(0);
  }

  function run(trigger) {
    var client = apiClient();
    var request = trigger.today
      ? client.get(BASE + 'today?tab=' + encodeURIComponent(trigger.today)).then(openList)
      : client.get(BASE + encodeURIComponent(trigger.itemType) + '/' + encodeURIComponent(trigger.code)).then(open);
    return request.catch(function (error) {
      var message = error && error.code === 'UNAUTHORIZED' && error.message ? error.message : MSG_FAILED;
      if (root && root.JlasToast && typeof root.JlasToast.show === 'function') root.JlasToast.show(message);
      else if (root && root.console) root.console.warn(message);
    });
  }

  /** Wires every [data-jpath-writing] control under scope (scope included) once. Returns the number wired. */
  function bind(scope) {
    if (!scope) return 0;
    var nodes = [];
    if (typeof scope.getAttribute === 'function' && scope.getAttribute('data-jpath-writing') !== null) nodes.push(scope);
    if (typeof scope.querySelectorAll === 'function') {
      Array.prototype.push.apply(nodes, Array.prototype.slice.call(scope.querySelectorAll('[data-jpath-writing]')));
    }
    var count = 0;
    nodes.forEach(function (el) {
      if (el.getAttribute(BOUND) === '1') return;
      var trigger = parseTrigger(el.getAttribute('data-jpath-writing'));
      if (!trigger) return;
      var control = el;
      if (el.tagName !== 'BUTTON' && el.tagName !== 'A') {
        var doc = el.ownerDocument || root.document;
        control = doc.createElement('button');
        control.type = 'button';
        control.className = 'jp-writing-btn';
        control.textContent = trigger.today ? 'Luyện viết hôm nay' : 'Luyện viết';
        el.appendChild(control);
      }
      control.addEventListener('click', function (ev) {
        if (ev && typeof ev.preventDefault === 'function') ev.preventDefault();
        run(trigger);
      });
      el.setAttribute(BOUND, '1');
      count++;
    });
    return count;
  }

  var rootDoc = root && root.document;
  if (rootDoc && typeof rootDoc.addEventListener === 'function') {
    rootDoc.addEventListener('jpath:rendered', function (ev) { bind(ev && ev.detail && ev.detail.root); });
    if (rootDoc.readyState === 'loading') rootDoc.addEventListener('DOMContentLoaded', function () { bind(rootDoc.body); });
    else if (rootDoc.body) bind(rootDoc.body);
  }

  return {
    open: open,
    openList: openList,
    bind: bind,
    parseTrigger: parseTrigger,
    createTraceBoard: createTraceBoard,
    animatable: animatable
  };
});
