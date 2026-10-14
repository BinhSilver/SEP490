/* Ôn hôm nay (SRS) — hàm thuần dùng chung cho trang và cho test Node. */
(function (root) {
  'use strict';

  /** Bốn nút chấm kiểu Anki, ánh xạ sang thang SM-2 0–5 của backend. */
  var GRADES = [
    { key: 'again', quality: 1, label: 'Quên', hotkey: '1' },
    { key: 'hard', quality: 3, label: 'Khó', hotkey: '2' },
    { key: 'good', quality: 4, label: 'Được', hotkey: '3' },
    { key: 'easy', quality: 5, label: 'Dễ', hotkey: '4' }
  ];
  var PASS_QUALITY = 3;
  /** Thẻ vừa quên được xem lại sau ngần này thẻ khác trong cùng phiên (không chấm lại lên server). */
  var RELEARN_GAP = 3;
  var WEEKDAYS = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
  /** Ngưỡng số lượt ôn/ngày cho các mức màu heatmap 1–4. */
  var HEAT_LEVELS = [1, 10, 25, 50];

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function decimal(value) {
    var rounded = Math.round(value * 10) / 10;
    return String(rounded).replace('.', ',');
  }

  function formatInterval(days) {
    var d = Math.max(0, Math.round(Number(days) || 0));
    if (d === 0) return 'Hôm nay';
    if (d < 30) return d + ' ngày';
    if (d < 365) return decimal(d / 30) + ' tháng';
    return decimal(d / 365) + ' năm';
  }

  function gradeByHotkey(key) {
    for (var i = 0; i < GRADES.length; i++) if (GRADES[i].hotkey === key) return GRADES[i];
    return null;
  }

  /* Ngày dạng 'YYYY-MM-DD' xử lý theo UTC để không lệch múi giờ. */
  function parseDay(text) {
    var parts = String(text).split('-').map(Number);
    return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
  }
  function formatDay(date) {
    return date.toISOString().slice(0, 10);
  }
  function addDays(date, n) {
    return new Date(date.getTime() + n * 86400000);
  }

  function heatLevel(count) {
    var level = 0;
    for (var i = 0; i < HEAT_LEVELS.length; i++) if (count >= HEAT_LEVELS[i]) level = i + 1;
    return level;
  }

  /**
   * Lưới heatmap gồm `weeks` cột, mỗi cột Thứ Hai → Chủ Nhật, cột cuối chứa hôm nay.
   * Ô sau hôm nay có `future: true` để vẽ trống.
   */
  function heatmapCells(heatmap, today, weeks) {
    var counts = {};
    (heatmap || []).forEach(function (row) { counts[row.date] = Number(row.count) || 0; });
    var end = parseDay(today);
    var mondayOffset = (end.getUTCDay() + 6) % 7;
    var start = addDays(end, -mondayOffset - (weeks - 1) * 7);
    var cells = [];
    for (var i = 0; i < weeks * 7; i++) {
      var day = addDays(start, i);
      var key = formatDay(day);
      var count = counts[key] || 0;
      cells.push({ date: key, count: count, level: heatLevel(count), future: day.getTime() > end.getTime() });
    }
    return cells;
  }

  function forecastBars(forecast) {
    var rows = forecast || [];
    var max = rows.reduce(function (m, row) { return Math.max(m, Number(row.count) || 0); }, 0);
    return rows.map(function (row, index) {
      var count = Number(row.count) || 0;
      return {
        date: row.date,
        label: index === 0 ? 'Hôm nay' : WEEKDAYS[parseDay(row.date).getUTCDay()],
        count: count,
        pct: max ? Math.round(count / max * 100) : 0
      };
    });
  }

  /** Chèn thẻ vừa quên trở lại hàng đợi để xem lại trong phiên; trả về hàng đợi mới. */
  function requeue(queue, item, gap) {
    var next = queue.slice();
    var at = Math.min(gap == null ? RELEARN_GAP : gap, next.length);
    next.splice(at, 0, Object.assign({}, item, { relearn: true }));
    return next;
  }

  function isLapse(quality) {
    return Number(quality) < PASS_QUALITY;
  }

  var api = {
    GRADES: GRADES, RELEARN_GAP: RELEARN_GAP, HEAT_LEVELS: HEAT_LEVELS,
    esc: esc, formatInterval: formatInterval, gradeByHotkey: gradeByHotkey, heatLevel: heatLevel,
    heatmapCells: heatmapCells, forecastBars: forecastBars, requeue: requeue, isLapse: isLapse
  };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashcardSrs = api;
})(typeof window !== 'undefined' ? window : globalThis);
