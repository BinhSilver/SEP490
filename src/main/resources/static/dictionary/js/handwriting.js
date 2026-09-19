/**
 * Bảng viết tay Kanji cho trang từ điển.
 *
 * Mỗi nét vẽ xong sẽ được gửi nhận diện (debounce, xem handwriting-core.js) và danh sách ứng viên
 * được cập nhật realtime — kể cả khi chữ chưa vẽ xong hay sai thứ tự nét. Chọn một ứng viên sẽ xoá
 * canvas, đóng bảng, đưa ký tự vào ô tìm kiếm và dùng đúng luồng tìm kiếm hiện có của Dictionary.
 * Không lưu nét vẽ sau khi chọn.
 */
(function (global) {
  'use strict';

  var core = global.DictHandwritingCore;
  var MIN_POINT_DISTANCE = 1.5; // px canvas: bỏ qua điểm quá sát nhau
  var LINE_WIDTH = 11;

  var canvas, ctx, strokeCounter, statusEl, candidatesEl, session;
  var activePointer = null;
  var currentStroke = null;

  var STATUS_TEXT = {
    idle: 'Vẽ một nét để xem gợi ý.',
    loading: 'Đang nhận diện…',
    ready: 'Chọn chữ Kanji đúng, hoặc tiếp tục vẽ để thu hẹp gợi ý.',
    empty: 'Chưa tìm thấy chữ phù hợp — thử vẽ thêm hoặc hoàn tác một nét.'
  };

  function tokenColor(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function paintStroke(points, color) {
    if (!points.length) return;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.lineWidth = LINE_WIDTH;
    ctx.strokeStyle = color;
    if (points.length === 1) {
      ctx.beginPath();
      ctx.arc(points[0][0], points[0][1], LINE_WIDTH / 2, 0, Math.PI * 2);
      ctx.fillStyle = color;
      ctx.fill();
      return;
    }
    ctx.beginPath();
    ctx.moveTo(points[0][0], points[0][1]);
    for (var i = 1; i < points.length; i++) ctx.lineTo(points[i][0], points[i][1]);
    ctx.stroke();
  }

  function redraw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    var color = tokenColor('--color-primary');
    session.strokes().forEach(function (stroke) { paintStroke(stroke, color); });
    if (currentStroke) paintStroke(currentStroke, color);
  }

  function pointFromEvent(event) {
    var rect = canvas.getBoundingClientRect();
    return [
      (event.clientX - rect.left) * (canvas.width / rect.width),
      (event.clientY - rect.top) * (canvas.height / rect.height)
    ];
  }

  function readingHint(kanji) {
    var readings = kanji.readings || [];
    var preferred = readings.filter(function (r) { return r.readingType === 'ON'; })[0]
      || readings.filter(function (r) { return r.readingType === 'KUN'; })[0];
    return preferred ? preferred.readingText : '';
  }

  function candidateButton(kanji) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'dict-handwriting-candidate';
    button.dataset.kanji = kanji.character;
    button.setAttribute('aria-label', 'Chọn ' + kanji.character);

    var glyph = document.createElement('span');
    glyph.className = 'dict-handwriting-candidate__kanji';
    glyph.textContent = kanji.character;
    var strokes = document.createElement('strong');
    strokes.textContent = kanji.strokeCount ? kanji.strokeCount + ' nét' : '';
    var reading = document.createElement('small');
    reading.textContent = readingHint(kanji);

    button.append(glyph, strokes, reading);
    button.addEventListener('click', function () { selectCandidate(kanji.character); });
    return button;
  }

  function render(state) {
    if (strokeCounter) strokeCounter.textContent = state.strokeCount + ' nét';

    statusEl.textContent = state.status === 'error' ? state.error : STATUS_TEXT[state.status];
    statusEl.classList.toggle('is-error', state.status === 'error');
    candidatesEl.classList.toggle('is-loading', state.status === 'loading');
    // Đang chờ kết quả mới: giữ nguyên ứng viên cũ (đỡ nhấp nháy), chỉ làm mờ.
    if (state.status !== 'loading') {
      candidatesEl.replaceChildren.apply(candidatesEl, state.candidates.map(candidateButton));
    }
  }

  function recognize(payload, signal) {
    return global.DictApi.recognizeHandwriting(payload, signal);
  }

  function selectCandidate(character) {
    session.clear();
    redraw();
    var searchInput = document.getElementById('dict-search-input');
    if (!searchInput) return;
    searchInput.value = character;
    if (global.JlasUI) global.JlasUI.closeModal('dict-handwriting-modal');
    searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    if (searchInput.focus) searchInput.focus();
  }

  function onPointerDown(event) {
    if (activePointer !== null || (event.pointerType === 'mouse' && event.button !== 0)) return;
    event.preventDefault();
    if (session.isFull()) {
      statusEl.textContent = 'Đã đạt tối đa ' + core.MAX_STROKES + ' nét — hãy hoàn tác hoặc xoá bớt.';
      return;
    }
    activePointer = event.pointerId;
    if (canvas.setPointerCapture) canvas.setPointerCapture(event.pointerId);
    currentStroke = [pointFromEvent(event)];
    redraw();
  }

  function onPointerMove(event) {
    if (event.pointerId !== activePointer) return;
    event.preventDefault();
    var point = pointFromEvent(event);
    var last = currentStroke[currentStroke.length - 1];
    if (Math.hypot(point[0] - last[0], point[1] - last[1]) < MIN_POINT_DISTANCE) return;
    currentStroke.push(point);
    redraw(); // chỉ vẽ lại canvas cục bộ — không gọi API ở đây
  }

  function onPointerEnd(event) {
    if (event.pointerId !== activePointer) return;
    activePointer = null;
    var stroke = currentStroke;
    currentStroke = null;
    if (event.type === 'pointercancel') { redraw(); return; }
    // Nét vẽ nhanh có thể không kịp có pointermove cuối: lấy luôn điểm nhả bút để không mất đoạn cuối.
    var release = pointFromEvent(event);
    var last = stroke[stroke.length - 1];
    if (Math.hypot(release[0] - last[0], release[1] - last[1]) >= MIN_POINT_DISTANCE) stroke.push(release);
    session.addStroke(stroke); // một nét hoàn tất => một lần nhận diện (đã debounce)
    redraw();
  }

  function undo() {
    session.undo();
    redraw();
  }

  function redo() {
    session.redo();
    redraw();
  }

  function clear() {
    activePointer = null;
    currentStroke = null;
    session.clear();
    redraw();
  }

  function init() {
    canvas = document.getElementById('dict-handwriting-canvas');
    if (!canvas) return;
    ctx = canvas.getContext('2d');
    strokeCounter = document.getElementById('dict-hw-stroke-count');
    statusEl = document.getElementById('dict-hw-status');
    candidatesEl = document.getElementById('dict-hw-candidates');

    session = core.createSession({
      recognize: recognize,
      onChange: render,
      width: canvas.width,
      height: canvas.height
    });

    canvas.addEventListener('pointerdown', onPointerDown);
    canvas.addEventListener('pointermove', onPointerMove);
    canvas.addEventListener('pointerup', onPointerEnd);
    canvas.addEventListener('pointercancel', onPointerEnd);

    document.getElementById('dict-hw-undo').addEventListener('click', undo);
    document.getElementById('dict-hw-redo').addEventListener('click', redo);
    document.getElementById('dict-hw-clear').addEventListener('click', clear);
    clear();
  }

  document.addEventListener('DOMContentLoaded', init);

  global.DictHandwriting = { clear: function () { if (session) clear(); } };
})(window);
