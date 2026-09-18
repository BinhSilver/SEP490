/**
 * Bảng viết tay Kanji cho trang từ điển.
 * Phần nhận diện hiện là dữ liệu demo từ prototype; chọn một ứng viên sẽ đưa
 * ký tự đó về ô tìm kiếm và dùng luồng tìm kiếm hiện có của Dictionary Home.
 */
(function (global) {
  'use strict';

  var canvas, ctx, strokeCounter;
  var strokes = [];
  var currentStroke = null;
  var drawing = false;
  var selectedKanji = '作';

  var demoStrokes = [
    { points: [[130, 80], [105, 130], [80, 190]], token: '--color-primary', width: 13 },
    { points: [[105, 175], [105, 230], [105, 305]], token: '--color-primary', width: 12 },
    { points: [[175, 95], [235, 90], [295, 86]], token: '--color-primary', width: 11 },
    { points: [[205, 98], [202, 165], [180, 205]], token: '--color-primary', width: 11 },
    { points: [[175, 185], [230, 180], [290, 175]], token: '--color-primary', width: 11 },
    { points: [[245, 94], [250, 260], [245, 300], [215, 280]], token: '--color-primary', width: 12 },
    { points: [[165, 308], [230, 304], [305, 300]], token: '--color-tertiary', width: 11 }
  ];

  function tokenColor(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function updateStrokeCounter(count) {
    if (strokeCounter) strokeCounter.textContent = count + ' nét';
  }

  function paintStroke(points, color, width) {
    if (!points.length) return;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.lineWidth = width;
    ctx.strokeStyle = color;

    if (points.length === 1) {
      ctx.beginPath();
      ctx.arc(points[0].x, points[0].y, width / 2, 0, Math.PI * 2);
      ctx.fillStyle = color;
      ctx.fill();
      return;
    }

    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    for (var i = 1; i < points.length; i++) ctx.lineTo(points[i].x, points[i].y);
    ctx.stroke();
  }

  function drawDemo() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    demoStrokes.forEach(function (stroke, index) {
      var points = stroke.points.map(function (point) { return { x: point[0], y: point[1] }; });
      var color = tokenColor(stroke.token);
      paintStroke(points, color, stroke.width);

      var start = points[0];
      ctx.fillStyle = tokenColor('--color-surface-lowest');
      ctx.beginPath();
      ctx.arc(start.x - 6, start.y - 6, 8, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = color;
      ctx.font = '700 9px "JetBrains Mono", monospace';
      ctx.fillText(String(index + 1), start.x - 9, start.y - 3);
    });
    updateStrokeCounter(demoStrokes.length);
  }

  function redrawUserStrokes() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    var color = tokenColor('--color-primary');
    strokes.forEach(function (stroke) { paintStroke(stroke, color, 11); });
    updateStrokeCounter(strokes.length);
  }

  function pointFromEvent(event) {
    var rect = canvas.getBoundingClientRect();
    var source = (event.touches && event.touches[0]) || event;
    return {
      x: (source.clientX - rect.left) * (canvas.width / rect.width),
      y: (source.clientY - rect.top) * (canvas.height / rect.height)
    };
  }

  function start(event) {
    event.preventDefault();
    drawing = true;
    currentStroke = [pointFromEvent(event)];
    strokes.push(currentStroke);
    redrawUserStrokes();
  }

  function move(event) {
    if (!drawing) return;
    event.preventDefault();
    currentStroke.push(pointFromEvent(event));
    redrawUserStrokes();
  }

  function end() {
    drawing = false;
    currentStroke = null;
  }

  function undo() {
    strokes.pop();
    if (strokes.length) redrawUserStrokes(); else drawDemo();
  }

  function clearCanvas() {
    strokes = [];
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    updateStrokeCounter(0);
  }

  function reset() {
    strokes = [];
    drawDemo();
  }

  function selectCandidate(button, candidates, submitLabel) {
    selectedKanji = button.dataset.kanji || '作';
    Array.prototype.forEach.call(candidates, function (candidate) {
      var isSelected = candidate === button;
      candidate.classList.toggle('is-selected', isSelected);
      candidate.setAttribute('aria-pressed', String(isSelected));
    });
    if (submitLabel) submitLabel.textContent = 'Tra từ điển với “' + selectedKanji + '”';
  }

  function submitSearch() {
    var searchInput = document.getElementById('dict-search-input');
    if (!searchInput) return;
    searchInput.value = selectedKanji;
    if (global.JlasUI) global.JlasUI.closeModal('dict-handwriting-modal');
    searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    if (searchInput.focus) searchInput.focus();
  }

  function init() {
    canvas = document.getElementById('dict-handwriting-canvas');
    if (!canvas) return;
    ctx = canvas.getContext('2d');
    strokeCounter = document.getElementById('dict-hw-stroke-count');

    canvas.addEventListener('mousedown', start);
    canvas.addEventListener('mousemove', move);
    global.addEventListener('mouseup', end);
    canvas.addEventListener('touchstart', start, { passive: false });
    canvas.addEventListener('touchmove', move, { passive: false });
    global.addEventListener('touchend', end);

    var undoButton = document.getElementById('dict-hw-undo');
    var clearButton = document.getElementById('dict-hw-clear');
    var resetButton = document.getElementById('dict-hw-reset');
    var submitButton = document.getElementById('dict-hw-submit');
    var submitLabel = document.getElementById('dict-hw-submit-label');
    var candidates = document.querySelectorAll('.dict-handwriting-candidate');

    if (undoButton) undoButton.addEventListener('click', undo);
    if (clearButton) clearButton.addEventListener('click', clearCanvas);
    if (resetButton) resetButton.addEventListener('click', reset);
    if (submitButton) submitButton.addEventListener('click', submitSearch);
    Array.prototype.forEach.call(candidates, function (candidate) {
      candidate.addEventListener('click', function () {
        selectCandidate(candidate, candidates, submitLabel);
      });
    });

    if (candidates.length) selectCandidate(candidates[0], candidates, submitLabel);
    reset();
  }

  document.addEventListener('DOMContentLoaded', init);

  global.DictHandwriting = { clear: reset, reset: reset };
})(window);
