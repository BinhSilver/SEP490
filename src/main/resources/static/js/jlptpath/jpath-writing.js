/* [JLAS-W0108] jpath-writing.js — stroke-order animation and tracing practice ("Luyện viết").
 *
 * window.JpathWriting = { open(view), openList(views), bind(root), parseTrigger(json), createTraceBoard(cellCount),
 *                         animatable(view) }
 *   - A page marks a control with data-jpath-writing='{"itemType":"VOCAB","code":"JV-…"}' (one item) or
 *     '{"today":"VOCAB"}' (today's new items of a tab). bind(root) wires each one once; it runs on DOMContentLoaded and
 *     on every document 'jpath:rendered' {detail:{root}}. A non-button element gets a "Luyện viết" button appended.
 *   - The dialog (class="jp-writing" data-inline-dictionary role="dialog") has two tabs: "Thứ tự nét" plays each
 *     character with an SVG through window.DictStrokeOrder.mount (+ "Xem lại nét"; the tab is hidden when no
 *     character has an SVG) and "Tập viết": one 米-grid cell per character, faint Klee One guide, a canvas
 *     (touch-action: none), Hoàn tác / Xóa ô / Xóa tất cả, not scored. "Viết từng nét" (hidden like "Thứ tự nét")
 *     quizzes one character at a time against its KanjiVG strokes in the browser: "Nét k/n", a miss flashes red for
 *     600 ms with a hint, the 3rd miss reveals the stroke, "Xong chữ …" + "Viết lại". Nothing is sent to the server.
 *     openList adds Trước / Sau. Esc closes.
 *   - Tập viết: the word (or Kanji) written left to right, 1/3/5/8/12 times in one wrapping row, split evenly into dark
 *     guide / faint guide / blank (cellKinds); In phiếu viết opens an A4 sheet of the same cells.
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

  // ---- stroke-by-stroke quiz (pure) -------------------------------------------------------------------------
  // Scored in the browser. FRAME, POINTS and MAX_MEAN_COST are copied from
  // dictionary/handwriting/StrokeMatcher.java (lines 30, 31 and 68) so a stroke passes here exactly when the dictionary
  // matcher would call it close; MAX_TRIES is the path's own rule. Nothing is sent to the server.

  var POINTS = 16;
  var FRAME = 109;
  var MAX_MEAN_COST = 0.15;
  var MAX_TRIES = 3;

  function isPoint(p) {
    return p && isFinite(p[0]) && isFinite(p[1]);
  }

  /** n points ([x, y]) spaced evenly along the length of the polyline (default POINTS). */
  function resample(points, n) {
    var count = n == null ? POINTS : n;
    var line = (points || []).filter(isPoint);
    if (!line.length || count < 1) return [];
    var cumulative = [0];
    for (var i = 1; i < line.length; i++) {
      cumulative.push(cumulative[i - 1] + Math.hypot(line[i][0] - line[i - 1][0], line[i][1] - line[i - 1][1]));
    }
    var length = cumulative[line.length - 1];
    var out = [];
    var segment = 1;
    for (var p = 0; p < count; p++) {
      if (line.length === 1 || length === 0) { out.push([line[0][0], line[0][1]]); continue; }
      var target = count === 1 ? 0 : length * p / (count - 1);
      while (segment < line.length - 1 && cumulative[segment] < target) segment++;
      var span = cumulative[segment] - cumulative[segment - 1];
      var t = span === 0 ? 0 : (target - cumulative[segment - 1]) / span;
      var a = line[segment - 1];
      var b = line[segment];
      out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
    }
    return out;
  }

  /** Canvas pixels (square canvas of sizePx) to the KanjiVG frame. */
  function toFrame(points, sizePx) {
    if (!(sizePx > 0)) return [];
    return (points || []).filter(isPoint).map(function (p) {
      return [p[0] * FRAME / sizePx, p[1] * FRAME / sizePx];
    });
  }

  function meanCost(a, b) {
    var sum = 0;
    for (var i = 0; i < a.length; i++) sum += Math.hypot(a[i][0] - b[i][0], a[i][1] - b[i][1]);
    return sum / a.length / FRAME;
  }

  /** 'OK' when the drawn stroke follows ref, 'REVERSED' when only the reversed direction passes, else 'WRONG'. */
  function strokeVerdict(drawn, ref) {
    var a = resample(drawn);
    var b = resample(ref);
    if (!a.length || !b.length) return 'WRONG';
    if (meanCost(a, b) <= MAX_MEAN_COST) return 'OK';
    if (meanCost(a, b.slice().reverse()) <= MAX_MEAN_COST) return 'REVERSED';
    return 'WRONG';
  }

  /**
   * One character's quiz over its reference strokes (frame coordinates, in writing order). submit(drawn) judges the
   * current stroke and returns {verdict, index (the stroke judged), revealed, done}, or null once done. A correct stroke
   * moves on; the MAX_TRIES-th miss on a stroke reveals it and moves on too.
   */
  function createStrokeQuiz(refStrokes) {
    var refs = (refStrokes || []).slice();
    var at = 0;
    var misses = 0;
    return {
      index: function () { return at; },
      total: function () { return refs.length; },
      done: function () { return at >= refs.length; },
      misses: function () { return misses; },
      submit: function (drawn) {
        if (at >= refs.length) return null;
        var k = at;
        var verdict = strokeVerdict(drawn, refs[k]);
        var revealed = false;
        if (verdict === 'OK') {
          at++;
          misses = 0;
        } else if (++misses >= MAX_TRIES) {
          revealed = true;
          at++;
          misses = 0;
        }
        return { verdict: verdict, index: k, revealed: revealed, done: at >= refs.length };
      },
      reset: function () { at = 0; misses = 0; }
    };
  }

  /** "Nét k/n" for the stroke at index (capped at n once done). */
  function quizProgress(index, total) {
    return 'Nét ' + Math.min(index + 1, total) + '/' + total;
  }

  /** The line shown after a submit (empty for a plain correct stroke). */
  function quizFeedback(result, ch) {
    if (!result) return '';
    var text = '';
    if (result.revealed) text = 'Đã hiện nét đúng';
    else if (result.verdict === 'REVERSED') text = 'Đúng nét nhưng ngược hướng, viết lại từ đầu nét';
    else if (result.verdict === 'WRONG') text = 'Chưa đúng nét ' + (result.index + 1) + ', xem gợi ý';
    if (result.done) text = (text ? text + ' · ' : '') + 'Xong chữ ' + ch;
    return text;
  }

  function strokeNumber(path) {
    var match = /-s(\d+)$/.exec((path && path.id) || '');
    return match ? parseInt(match[1], 10) : -1;
  }

  /** KanjiVG stroke paths of svgEl in writing order. */
  function strokePaths(svgEl) {
    if (!svgEl || typeof svgEl.querySelectorAll !== 'function') return [];
    return Array.prototype.slice.call(svgEl.querySelectorAll('path[id*="-s"]'))
      .filter(function (p) { return strokeNumber(p) >= 0; })
      .sort(function (a, b) { return strokeNumber(a) - strokeNumber(b); });
  }

  /** Reference strokes of a KanjiVG <svg> (browser): POINTS points per stroke through getPointAtLength. */
  function sampleSvgStrokes(svgEl) {
    return strokePaths(svgEl).map(function (path) {
      var length = path.getTotalLength();
      var points = [];
      for (var i = 0; i < POINTS; i++) {
        var pt = path.getPointAtLength(length * i / (POINTS - 1));
        points.push([pt.x, pt.y]);
      }
      return points;
    });
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

  var PER_CELL = [1, 3, 5, 8, 12];
  var DEFAULT_PER = 3;

  /** The characters of the item, in order (a word is practised whole, left to right). */
  function wordChars(view) {
    return (view.characters || []).map(function (c) { return c['char']; });
  }

  /**
   * How the cells of one character split, evenly: about a third with the dark guide, a third faint, the rest blank
   * (3 → 1/1/1, 5 → 2/2/1, 8 → 3/3/2, 12 → 4/4/4). Returns one of 'solid' | 'faint' | 'blank' per cell.
   */
  function cellKinds(per) {
    var n = Math.max(1, per | 0);
    var solid = Math.ceil(n / 3);
    var faint = Math.ceil((n - solid) / 2);
    var out = [];
    for (var k = 0; k < n; k++) out.push(k < solid ? 'solid' : (k < solid + faint ? 'faint' : 'blank'));
    return out;
  }

  /**
   * Practice cells: the whole word (or the one character) written left to right, repeated per times in one row that
   * wraps; each repetition takes its kind from cellKinds (dark guide, faint guide, blank).
   */
  function cellsHtml(chars, per) {
    var at = 0;
    var groups = cellKinds(per).map(function (kind, k, all) {
      return '<div class="jp-writing__word">' + chars.map(function (ch) {
        return '<div class="jp-writing__cell jp-writing__cell--' + kind + '" data-cell="' + (at++) + '">' + GRID
          + (kind === 'blank' ? '' : '<span class="jp-writing__ghost jp-writing__ghost--' + kind + ' jp-kanji" aria-hidden="true">'
            + esc(ch) + '</span>')
          + '<canvas class="jp-writing__canvas" aria-label="Ô tập viết chữ ' + esc(ch) + ' (lần ' + (k + 1) + '/' + all.length + ')"></canvas></div>';
      }).join('') + '</div>';
    }).join('');
    return '<div class="jp-writing__row">' + groups + '</div>';
  }

  function optionsHtml(per) {
    var sel = '<label class="jp-writing__opt">Số lần viết <select data-writing-per>' + PER_CELL.map(function (n) {
      return '<option value="' + n + '"' + (n === per ? ' selected' : '') + '>' + n + ' lần</option>';
    }).join('') + '</select></label>';
    return '<div class="jp-writing__opts">' + sel
      + '<button type="button" class="jp-writing__btn" data-writing-print>In phiếu viết</button></div>';
  }

  /** A4 writing sheet: the word written left to right, repeated like the screen (cellKinds), in rows that wrap. */
  function sheetHtml(view, chars, per) {
    var v = view || {};
    // Owner 06/11: the sheet fills the A4 page with large practice squares (7 per row, 8 rows), whatever the screen's
    // repetition count; the first repetitions are dark, a few faint, the rest blank.
    var perRow = 7;
    var reps = Math.max(1, Math.floor((perRow * 8) / Math.max(1, chars.length)));
    var solid = Math.max(1, Math.min(perRow, Math.ceil(reps / 8)));
    var faint = Math.max(perRow, Math.ceil(reps / 4));
    var kinds = [];
    for (var k = 0; k < reps; k++) kinds.push(k < solid ? 'solid' : (k < solid + faint ? 'faint' : 'blank'));
    var rows = '<div class="r">' + kinds.map(function (cls) {
      return '<span class="w">' + chars.map(function (ch) {
        return '<span class="c c--' + cls + '">' + (cls === 'blank' ? '' : esc(ch)) + '</span>';
      }).join('') + '</span>';
    }).join('') + '</div>';
    var meta = [v.reading, v.hanViet, v.meaning].filter(Boolean).map(esc).join(' · ');
    return '<!doctype html><html lang="vi"><head><meta charset="utf-8"><title>' + esc('Phiếu viết ' + (v.word || ''))
      + '</title><link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Klee+One:wght@400;600&display=swap">'
      + '<style>@page{size:A4;margin:14mm}body{font-family:system-ui,sans-serif;color:#222;margin:0}'
      + 'h1{font:600 22pt "Klee One",serif;margin:0}p{margin:2mm 0 6mm;color:#555;font-size:10.5pt}'
      // the same practice square as on screen: frame, dashed centre cross and dashed diagonals
      + '.r{display:flex;flex-wrap:wrap;gap:2.5mm}.w{display:inline-flex;gap:0;break-inside:avoid}'
      + '.c{position:relative;width:23mm;height:23mm;box-sizing:border-box;border:.4mm solid #7a6fa8;display:grid;'
      + 'place-items:center;font:400 17mm/1 "Klee One",serif;color:#222;background:#fff;'
      + 'background-image:linear-gradient(to right,transparent calc(50% - .1mm),#cfc8e6 calc(50% - .1mm),#cfc8e6 calc(50% + .1mm),transparent calc(50% + .1mm)),'
      + 'linear-gradient(to bottom,transparent calc(50% - .1mm),#cfc8e6 calc(50% - .1mm),#cfc8e6 calc(50% + .1mm),transparent calc(50% + .1mm)),'
      + 'linear-gradient(45deg,transparent calc(50% - .1mm),#e4dff2 calc(50% - .1mm),#e4dff2 calc(50% + .1mm),transparent calc(50% + .1mm)),'
      + 'linear-gradient(-45deg,transparent calc(50% - .1mm),#e4dff2 calc(50% - .1mm),#e4dff2 calc(50% + .1mm),transparent calc(50% + .1mm))}'
      + '.w .c+.c{border-left:0}.c--faint{color:#cfcfcf}.c--blank{color:transparent}'
      + '@media print{.c{-webkit-print-color-adjust:exact;print-color-adjust:exact}}'
      + 'header{display:flex;justify-content:space-between;align-items:flex-end;border-bottom:.4mm solid #4b3a8c;'
      + 'padding-bottom:2mm;margin-bottom:6mm;font-size:9.5pt;color:#4b3a8c}header b{font-size:11pt}'
      + '.who{display:flex;gap:10mm;font-size:9.5pt;color:#555;margin:-3mm 0 6mm}.who span{flex:1;border-bottom:.2mm dotted #999}'
      + '</style></head><body>'
      + '<header><b>J-LAS · Phiếu luyện viết</b><span>' + esc(new Date().toLocaleDateString('vi-VN')) + '</span></header>'
      + '<div class="who"><span>Họ tên:</span><span>Ngày viết:</span></div>'
      + '<h1>' + esc(v.word || '') + '</h1>' + (meta ? '<p>' + meta + '</p>' : '') + rows + '</body></html>';
  }

  /** Owner 05/11: no blank tab; the sheet goes straight to the browser's print preview from a hidden frame. */
  function printSheet(view, chars, per) {
    var html = sheetHtml(view, chars, per);
    var doc = root && root.document;
    if (!doc || !doc.body) return false;
    var frame = doc.createElement('iframe');
    frame.setAttribute('aria-hidden', 'true');
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
    doc.body.appendChild(frame);
    frame.onload = function () {
      var go = function () {
        try { frame.contentWindow.focus(); frame.contentWindow.print(); } catch (e) { /* the browser refused */ }
      };
      var fonts = frame.contentDocument && frame.contentDocument.fonts;
      if (fonts && fonts.ready) fonts.ready.then(go, go); else go();
      root.setTimeout(function () { if (frame.parentNode) frame.parentNode.removeChild(frame); }, 60000);
    };
    frame.srcdoc = html;
    return true;
  }

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
      var count = c.strokeCount ? '<span class="jp-writing__count">' + esc(c.strokeCount) + ' nét</span>' : '';
      return '<figure class="jp-writing__stroke"><div class="jp-writing__svg" data-stroke-index="' + i + '"></div>'
        + '<figcaption class="jp-kanji">' + esc(c['char']) + count + '</figcaption></figure>';
    }).join('');
    var cells = cellsHtml(wordChars(view), DEFAULT_PER);
    var navHtml = nav ? '<div class="jp-writing__nav">'
      + '<button type="button" class="jp-writing__btn" data-writing-prev' + (nav.index === 0 ? ' disabled' : '')
      + '>Trước</button><span class="jp-writing__pos">' + (nav.index + 1) + '/' + nav.total + '</span>'
      + '<button type="button" class="jp-writing__btn" data-writing-next'
      + (nav.index >= nav.total - 1 ? ' disabled' : '') + '>Sau</button></div>' : '';
    var hideAnim = anim.length ? '' : ' hidden';
    var hideTrace = chars.length ? '' : ' hidden';
    return '<div class="jp-writing__panel">' + headerHtml(view)
      + '<div class="jp-writing__tabs" role="tablist"' + hideTrace + '>'
      + tabHtml('order', 'Thứ tự nét', hideAnim) + tabHtml('trace', 'Tập viết', hideTrace)
      + tabHtml('quiz', 'Viết từng nét', hideAnim) + '</div>'
      + paneOpen('order', hideAnim)
      + '<div class="jp-writing__strokes">' + strokes + '</div>'
      + '<button type="button" class="jp-writing__btn" data-writing-replay>Xem lại nét</button></section>'
      + paneOpen('trace', ' hidden') + optionsHtml(DEFAULT_PER)
      + '<div class="jp-writing__cells" data-writing-cells>' + cells + '</div>'
      + '<div class="jp-writing__tools">'
      + '<button type="button" class="jp-writing__btn" data-writing-undo>Hoàn tác</button>'
      + '<button type="button" class="jp-writing__btn" data-writing-clear-cell>Xóa ô</button>'
      + '<button type="button" class="jp-writing__btn" data-writing-clear-all>Xóa tất cả</button></div></section>'
      + (anim.length ? quizPaneHtml(anim) : '')
      + navHtml
      + '<div class="jp-writing__foot"><button type="button" class="jp-writing__btn" data-writing-close>Đóng</button>'
      + '</div></div>';
  }

  function tabHtml(name, label, hidden) {
    return '<button type="button" role="tab" class="jp-writing__tab" id="jp-writing-tab-' + name
      + '" data-writing-tab="' + name + '" aria-controls="jp-writing-pane-' + name + '" aria-selected="false"'
      + hidden + '>' + label + '</button>';
  }

  function paneOpen(name, hidden) {
    return '<section class="jp-writing__pane" id="jp-writing-pane-' + name + '" role="tabpanel" aria-labelledby='
      + '"jp-writing-tab-' + name + '" data-writing-pane="' + name + '"' + hidden + '>';
  }

  function quizPaneHtml(anim) {
    var picks = anim.length > 1 ? '<div class="jp-writing__picks" role="group" aria-label="Chọn chữ">'
      + anim.map(function (c, i) {
        return '<button type="button" class="jp-writing__btn jp-kanji" data-quiz-pick="' + i + '" aria-pressed="'
          + (i === 0) + '">' + esc(c['char']) + '</button>';
      }).join('') + '</div>' : '';
    return paneOpen('quiz', ' hidden') + picks
      + '<p class="jp-writing__progress" data-quiz-progress></p>'
      + '<div class="jp-writing__cell jp-writing__cell--quiz">' + GRID
      + '<div class="jp-writing__ref" data-quiz-ref aria-hidden="true"></div>'
      + '<canvas class="jp-writing__canvas" aria-label="Ô viết từng nét"></canvas></div>'
      + '<p class="jp-writing__msg" data-quiz-msg role="status" aria-live="polite"></p>'
      + '<div class="jp-writing__tools">'
      + '<button type="button" class="jp-writing__btn" data-quiz-restart>Viết lại</button></div></section>';
  }

  var FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
    + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

  /** Where Tab / Shift+Tab goes inside the dialog: the element to focus, or null to let the browser move on. */
  function trapTarget(items, active, shift) {
    if (!items.length) return null;
    var first = items[0];
    var last = items[items.length - 1];
    var at = items.indexOf(active);
    if (at < 0) return shift ? last : first;
    if (shift && active === first) return last;
    if (!shift && active === last) return first;
    return null;
  }

  function visibleFocusables(box) {
    return Array.prototype.slice.call(box.querySelectorAll(FOCUSABLE)).filter(function (el) {
      return !el.closest('[hidden]');
    });
  }

  var svgCache = {};

  function loadSvgText(url) {
    if (!svgCache[url]) {
      svgCache[url] = root.fetch(url).then(function (res) {
        if (!res.ok) throw new Error('stroke svg ' + res.status);
        return res.text();
      });
      svgCache[url].catch(function () { delete svgCache[url]; });
    }
    return svgCache[url];
  }

  /** Wires the "Viết từng nét" pane: one character at a time, judged stroke by stroke in the browser. */
  function setupQuiz(box, view) {
    var pane = box.querySelector('[data-writing-pane="quiz"]');
    if (!pane) return { start: function () {} };
    var doc = box.ownerDocument;
    var chars = animatable(view);
    var cell = pane.querySelector('.jp-writing__cell--quiz');
    var holder = pane.querySelector('[data-quiz-ref]');
    var canvas = pane.querySelector('.jp-writing__canvas');
    var progress = pane.querySelector('[data-quiz-progress]');
    var msg = pane.querySelector('[data-quiz-msg]');
    var state = { pick: -1, quiz: null, paths: [], token: 0, flash: null };

    function say(text, tone) {
      msg.textContent = text;
      if (tone) msg.setAttribute('data-tone', tone);
      else msg.removeAttribute('data-tone');
    }
    function showProgress() {
      progress.textContent = state.quiz ? quizProgress(state.quiz.index(), state.quiz.total()) : '';
    }
    function ctx2d() {
      var ratio = root.devicePixelRatio || 1;
      var w = canvas.clientWidth || 240;
      if (canvas.width !== Math.round(w * ratio)) {
        canvas.width = Math.round(w * ratio);
        canvas.height = Math.round(w * ratio);
      }
      var ctx = canvas.getContext('2d');
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      return ctx;
    }
    function paint(points) {
      var ctx = ctx2d();
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (!points || !points.length) return;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.lineWidth = 6;
      ctx.strokeStyle = root.getComputedStyle ? root.getComputedStyle(canvas).color : '#222';
      ctx.beginPath();
      ctx.moveTo(points[0][0], points[0][1]);
      for (var i = 1; i < points.length; i++) ctx.lineTo(points[i][0], points[i][1]);
      if (points.length === 1) ctx.lineTo(points[0][0] + .1, points[0][1] + .1);
      ctx.stroke();
    }
    function clearFlash() {
      if (state.flash) root.clearTimeout(state.flash);
      state.flash = null;
      cell.classList.remove('jp-writing__cell--miss');
    }
    function mark(k, value) {
      if (state.paths[k]) state.paths[k].setAttribute('data-quiz-stroke', value);
    }
    function restart() {
      clearFlash();
      paint(null);
      say('');
      if (!state.quiz) return;
      state.quiz.reset();
      state.paths.forEach(function (_, k) { mark(k, 'pending'); });
      showProgress();
    }
    function pick(i) {
      if (i === state.pick) return;
      state.pick = i;
      state.quiz = null;
      state.paths = [];
      var token = ++state.token;
      holder.innerHTML = '';
      progress.textContent = '';
      clearFlash();
      paint(null);
      say('Đang tải nét mẫu…');
      Array.prototype.forEach.call(pane.querySelectorAll('[data-quiz-pick]'), function (b) {
        b.setAttribute('aria-pressed', String(Number(b.getAttribute('data-quiz-pick')) === i));
      });
      loadSvgText(chars[i].svgUrl).then(function (text) {
        if (token !== state.token) return;
        var parsed = new root.DOMParser().parseFromString(text, 'image/svg+xml').querySelector('svg');
        if (!parsed) throw new Error('invalid stroke svg');
        var svg = doc.importNode(parsed, true);
        svg.removeAttribute('width');
        svg.removeAttribute('height');
        Array.prototype.forEach.call(svg.querySelectorAll('g[id*="StrokeNumbers"]'), function (g) {
          g.parentNode.removeChild(g);
        });
        holder.appendChild(svg);
        state.paths = strokePaths(svg);
        state.quiz = createStrokeQuiz(sampleSvgStrokes(svg));
        state.paths.forEach(function (_, k) { mark(k, 'pending'); });
        say('');
        showProgress();
      }).catch(function () {
        if (token === state.token) say('Chưa tải được nét mẫu của chữ này.', 'miss');
      });
    }
    function submit(points) {
      var quiz = state.quiz;
      if (!quiz || quiz.done()) { paint(null); return; }
      var result = quiz.submit(toFrame(points, canvas.clientWidth || 240));
      clearFlash();
      if (result.verdict === 'OK') {
        mark(result.index, 'done');
        paint(null);
      } else if (result.revealed) {
        mark(result.index, 'revealed');
        paint(null);
      } else {
        if (result.verdict === 'WRONG') mark(result.index, 'hint');
        cell.classList.add('jp-writing__cell--miss');
        paint(points); // repaint in the miss colour, then clear after the flash
        state.flash = root.setTimeout(function () {
          state.flash = null;
          cell.classList.remove('jp-writing__cell--miss');
          paint(null);
        }, 600);
      }
      say(quizFeedback(result, chars[state.pick]['char']), result.verdict === 'OK' || result.revealed ? '' : 'miss');
      showProgress();
    }

    var points = null;
    function at(ev) {
      var r = canvas.getBoundingClientRect();
      return [ev.clientX - r.left, ev.clientY - r.top];
    }
    canvas.addEventListener('pointerdown', function (ev) {
      ev.preventDefault();
      clearFlash();
      points = [at(ev)];
      if (canvas.setPointerCapture) canvas.setPointerCapture(ev.pointerId);
    });
    canvas.addEventListener('pointermove', function (ev) {
      if (!points) return;
      points.push(at(ev));
      paint(points);
    });
    function end() {
      if (!points) return;
      var drawn = points;
      points = null;
      submit(drawn);
    }
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    pane.querySelector('[data-quiz-restart]').addEventListener('click', restart);
    Array.prototype.forEach.call(pane.querySelectorAll('[data-quiz-pick]'), function (b) {
      b.addEventListener('click', function () { pick(Number(b.getAttribute('data-quiz-pick'))); });
    });
    return {
      start: function () {
        if (state.pick < 0) pick(0);
      }
    };
  }

  function simplify(points) {
    var core = root && root.DictHandwritingCore;
    return core && typeof core.simplify === 'function' ? core.simplify(points, 1.5) : points;
  }

  /** Wires the tracing canvases of box to a fresh board. */
  function setupTrace(box) {
    var pane = box.querySelector('[data-writing-cells]') || box;
    var canvases = Array.prototype.slice.call(pane.querySelectorAll('.jp-writing__canvas'));
    var board = createTraceBoard(canvases.length);
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

    return { board: board, redraw: redraw, active: function () { return active; },
      redrawAll: function () { canvases.forEach(function (_, i) { redraw(i); }); } };
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
    // Trước / Sau re-render from inside the dialog: keep the button that first opened it as the focus target.
    var opener = current ? current.opener : doc.activeElement;
    if (current) current.close(false);
    var box = doc.createElement('div');
    box.className = 'jp-writing';
    box.setAttribute('data-inline-dictionary', '');
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');
    box.setAttribute('aria-labelledby', 'jp-writing-title');
    box.innerHTML = bodyHtml(view, nav);
    var handle = { el: box, close: close, opener: opener };

    function onKey(ev) {
      if (ev.key === 'Escape') { ev.preventDefault(); close(true); }
      else if (ev.key === 'Tab') {
        var target = trapTarget(visibleFocusables(box), doc.activeElement, ev.shiftKey);
        if (target) { ev.preventDefault(); target.focus(); }
      }
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
    var trace = setupTrace(box);
    var practice = { per: DEFAULT_PER };
    function rebuild() {
      var holder = box.querySelector('[data-writing-cells]');
      if (!holder) return;
      holder.innerHTML = cellsHtml(wordChars(view), practice.per);
      trace = setupTrace(box);
      trace.redrawAll();
    }
    var tools = {
      '[data-writing-undo]': function () { var cell = trace.board.undo(); if (cell >= 0) trace.redraw(cell); },
      '[data-writing-clear-cell]': function () { trace.board.clearCell(trace.active()); trace.redraw(trace.active()); },
      '[data-writing-clear-all]': function () { trace.board.clearAll(); trace.redrawAll(); },
      '[data-writing-print]': function () { printSheet(view, wordChars(view), practice.per); }
    };
    Object.keys(tools).forEach(function (sel) {
      var el = box.querySelector(sel);
      if (el) el.addEventListener('click', tools[sel]);
    });
    var perSel = box.querySelector('[data-writing-per]');
    if (perSel) perSel.addEventListener('change', function () { practice.per = Number(perSel.value) || DEFAULT_PER; rebuild(); });
    var quiz = setupQuiz(box, view);
    function show(name) {
      Array.prototype.forEach.call(box.querySelectorAll('[data-writing-pane]'), function (p) {
        p.hidden = p.getAttribute('data-writing-pane') !== name;
      });
      Array.prototype.forEach.call(box.querySelectorAll('[data-writing-tab]'), function (t) {
        t.setAttribute('aria-selected', String(t.getAttribute('data-writing-tab') === name));
      });
      if (name === 'order') order.start();
      else if (name === 'quiz') quiz.start();
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
    if ((view.characters || []).length) show(animatable(view).length ? 'order' : 'trace');
    var first = box.querySelector('[data-writing-tab]:not([hidden])') || box.querySelector('[data-writing-close]');
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
    animatable: animatable,
    POINTS: POINTS,
    FRAME: FRAME,
    MAX_MEAN_COST: MAX_MEAN_COST,
    MAX_TRIES: MAX_TRIES,
    resample: resample,
    toFrame: toFrame,
    strokeVerdict: strokeVerdict,
    createStrokeQuiz: createStrokeQuiz,
    quizProgress: quizProgress,
    quizFeedback: quizFeedback,
    sampleSvgStrokes: sampleSvgStrokes,
    dialogHtml: bodyHtml,
    trapTarget: trapTarget,
    wordChars: wordChars, cellKinds: cellKinds, cellsHtml: cellsHtml, sheetHtml: sheetHtml
  };
});
