/**
 * Logic thuần của bảng viết tay (không đụng DOM nên test được bằng Node): làm gọn nét, dựng payload
 * và phiên nhận diện realtime.
 *
 * Phiên nhận diện chỉ gọi API khi một NÉT kết thúc (không gọi theo mousemove/pointermove), gộp
 * các nét đến sát nhau bằng debounce, huỷ request cũ khi có nét mới và bỏ qua phản hồi cũ đến muộn.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.DictHandwritingCore = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // Phải khớp giới hạn ở HandwritingRequest (backend).
  var MAX_STROKES = 30;
  var MAX_POINTS = 500;
  var SIMPLIFY_EPSILON = 1.5; // px canvas
  var MIN_STROKE_LENGTH = 5; // px canvas; ignore taps and accidental tiny drags
  var DEFAULT_DELAY_MS = 150;

  function perpendicularDistance(p, a, b) {
    var dx = b[0] - a[0], dy = b[1] - a[1];
    var lengthSquared = dx * dx + dy * dy;
    if (lengthSquared === 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
    var t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSquared));
    return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
  }

  /** Ramer–Douglas–Peucker (lặp, không đệ quy để tránh tràn stack với nét dài). */
  function simplify(points, epsilon) {
    if (points.length <= 2) return points.slice();
    var keep = new Array(points.length).fill(false);
    keep[0] = keep[points.length - 1] = true;
    var stack = [[0, points.length - 1]];
    while (stack.length) {
      var range = stack.pop(), first = range[0], last = range[1];
      var maxDistance = 0, index = -1;
      for (var i = first + 1; i < last; i++) {
        var d = perpendicularDistance(points[i], points[first], points[last]);
        if (d > maxDistance) { maxDistance = d; index = i; }
      }
      if (index !== -1 && maxDistance > epsilon) {
        keep[index] = true;
        stack.push([first, index], [index, last]);
      }
    }
    return points.filter(function (_, i) { return keep[i]; });
  }

  function round1(value) { return Math.round(value * 10) / 10; }

  function clamp(value, max) { return Math.min(Math.max(value, 0), max); }

  function isRealStroke(points) {
    if (!points || points.length < 2) return false;
    var length = 0;
    for (var i = 1; i < points.length; i++) {
      length += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
    }
    return length >= MIN_STROKE_LENGTH;
  }

  /** Dựng body cho POST /api/dictionary/handwriting/recognize từ các nét thô. */
  function buildPayload(strokes, width, height) {
    return {
      width: width,
      height: height,
      strokes: strokes.filter(isRealStroke).map(function (stroke) {
        var simplified = simplify(stroke, SIMPLIFY_EPSILON);
        if (simplified.length > MAX_POINTS) {
          var step = (simplified.length - 1) / (MAX_POINTS - 1);
          simplified = Array.from({ length: MAX_POINTS }, function (_, i) { return simplified[Math.round(i * step)]; });
        }
        return simplified.map(function (p) { return [round1(clamp(p[0], width)), round1(clamp(p[1], height))]; });
      })
    };
  }

  /**
   * @param {object} options
   *   recognize(payload, signal) => Promise<candidate[]>
   *   onChange(state)  state = { status, strokeCount, candidates, error }
   *   width, height    kích thước canvas (pixel)
   *   delay            debounce (ms), mặc định 150
   *   timers           { setTimeout, clearTimeout } — để test
   */
  function createSession(options) {
    var delay = options.delay == null ? DEFAULT_DELAY_MS : options.delay;
    var timers = options.timers || { setTimeout: setTimeout.bind(null), clearTimeout: clearTimeout.bind(null) };
    var strokes = [];
    var candidates = [];
    var status = 'idle';
    var error = null;
    var timer = null;
    var inFlight = null;
    var sequence = 0;

    function emit() {
      options.onChange({ status: status, strokeCount: strokes.length, candidates: candidates.slice(), error: error });
    }

    function cancelPending() {
      if (timer !== null) { timers.clearTimeout(timer); timer = null; }
      if (inFlight) { inFlight.abort(); inFlight = null; }
      sequence++; // mọi phản hồi của request đã phát đi trở thành "cũ"
    }

    function run() {
      timer = null;
      var mine = ++sequence;
      var controller = new AbortController();
      inFlight = controller;
      var payload = buildPayload(strokes, options.width, options.height);
      Promise.resolve()
        .then(function () { return options.recognize(payload, controller.signal); })
        .then(function (result) {
          if (mine !== sequence) return;
          inFlight = null;
          candidates = result || [];
          status = candidates.length ? 'ready' : 'empty';
          error = null;
          emit();
        })
        .catch(function (err) {
          if (mine !== sequence) return;
          inFlight = null;
          candidates = [];
          status = 'error';
          error = (err && err.message) || 'Không nhận diện được';
          emit();
        });
    }

    /** Có nét mới / mất nét: chạy lại nhận diện sau khoảng debounce. */
    function schedule() {
      cancelPending();
      if (!strokes.length) {
        candidates = [];
        status = 'idle';
        error = null;
      } else {
        status = 'loading'; // giữ ứng viên cũ trên màn hình cho tới khi có kết quả mới
        timer = timers.setTimeout(run, delay);
      }
      emit();
    }

    return {
      /** @returns {boolean} false nếu nét bị từ chối (rỗng hoặc đã đủ số nét tối đa). */
      addStroke: function (points) {
        if (!isRealStroke(points) || strokes.length >= MAX_STROKES) return false;
        strokes.push(points.slice(0, 5000)); // chặn cứng bộ nhớ nếu một nét quá dài
        schedule();
        return true;
      },
      undo: function () {
        if (!strokes.length) return;
        strokes.pop();
        schedule();
      },
      clear: function () {
        strokes = [];
        schedule();
      },
      strokes: function () { return strokes; },
      isFull: function () { return strokes.length >= MAX_STROKES; }
    };
  }

  return {
    MAX_STROKES: MAX_STROKES,
    MAX_POINTS: MAX_POINTS,
    simplify: simplify,
    isRealStroke: isRealStroke,
    buildPayload: buildPayload,
    createSession: createSession
  };
});
