/* [JLAS-W0108] jpath-stats.js — stats page of the JLPT path (plan Task 67).
 *
 * window.JpathStats = { heatmapColumns(cells, from), barHeights(values, maxPx), heatClass(level), kindMark(kind), mount() }
 *   - GET /api/learning/jlpt/stats?range=7|30: accuracy tiles ("—" when null), learned / known / notebook, streaks and
 *     one CSS bar per day; GET /stats/heatmap?months=6: one month at a time, a cell
 *     opens /learning/jlpt/history?day=…; GET /badges: earned badges and "progress/goal" for the others.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathStats = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var BAR_PX = 80;
  var ACC_TABS = [['VOCAB', 'Từ vựng'], ['KANJI', 'Kanji'], ['GRAMMAR', 'Ngữ pháp'], ['KANA', 'Chữ cái']];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** Monday = 0 … Sunday = 6 for an ISO day. */
  function weekdayIndex(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    if (!m) return 0;
    return (new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay() + 6) % 7;
  }

  /** Cells (oldest first, from {@code from}) as week columns starting on Monday; days before {@code from} are null. */
  function heatmapColumns(cells, from) {
    var list = Array.isArray(cells) ? cells : [];
    var cols = [];
    var col = [];
    for (var i = 0; i < weekdayIndex(from); i++) col.push(null);
    list.forEach(function (c) {
      col.push(c);
      if (col.length === 7) { cols.push(col); col = []; }
    });
    if (col.length) cols.push(col);
    return cols;
  }

  /** Bar heights in px, the largest value reaching {@code maxPx}; all zero when every value is zero. */
  function barHeights(values, maxPx) {
    var v = (Array.isArray(values) ? values : []).map(function (x) { return Math.max(0, Number(x) || 0); });
    var max = Math.max.apply(null, [0].concat(v));
    return v.map(function (x) { return max ? Math.round(x / max * maxPx) : 0; });
  }

  function heatClass(level) {
    var n = Math.max(0, Math.min(4, Math.round(Number(level) || 0)));
    return 'jp-heat-' + n;
  }

  function kindMark(kind) {
    return kind === 'REST' ? 'moon' : kind === 'SKILL' ? 'book-open' : '';
  }

  function icon(name, label) {
    if (root && root.JpathNav && root.JpathNav.iconHtml) return root.JpathNav.iconHtml(name, label);
    return '<svg class="jp-i" ' + (label ? 'role="img" aria-label="' + esc(label) + '"' : 'aria-hidden="true"')
      + '><use href="/images/icons/jpath-icons.svg#i-' + esc(name) + '"></use></svg>';
  }

  function percent(v) {
    return v === null || v === undefined || isNaN(Number(v)) ? '—' : Math.round(Number(v)) + '%';
  }

  function sum(map) {
    return Object.keys(map || {}).reduce(function (a, k) { return a + (Number(map[k]) || 0); }, 0);
  }

  function tile(value, label) {
    return '<div class="jp-side-tile"><strong>' + esc(value) + '</strong><span>' + esc(label) + '</span></div>';
  }

  function shortDay(iso) {
    var m = /^\d{4}-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    return m ? m[2] + '/' + m[1] : String(iso || '');
  }

  function statsMarkup(s) {
    var acc = ACC_TABS.map(function (t) { return tile(percent(s.accuracy && s.accuracy[t[0]]), t[1]); }).join('');
    var counts = tile(sum(s.learned), 'Đã học') + tile(sum(s.known), 'Đã biết') + tile(sum(s.notebook), 'Sổ tay')
      + tile((Number(s.currentStreak) || 0) + ' ngày', 'Chuỗi hiện tại')
      + tile((Number(s.longestStreak) || 0) + ' ngày', 'Chuỗi dài nhất');
    var days = Array.isArray(s.days) ? s.days : [];
    var heights = barHeights(days.map(function (d) { return (Number(d.newLearned) || 0) + (Number(d.reviewed) || 0); }),
      BAR_PX);
    var bars = days.map(function (d, i) {
      var total = (Number(d.newLearned) || 0) + (Number(d.reviewed) || 0);
      return '<div class="jp-side-bar" title="' + esc(shortDay(d.day) + ': ' + (Number(d.newLearned) || 0) + ' mới, '
        + (Number(d.reviewed) || 0) + ' ôn') + '"><span class="jp-side-bar__fill" style="height:' + heights[i]
        + 'px"></span><span class="jp-side-bar__n">' + esc(total) + '</span><span class="jp-side-bar__day">'
        + esc(shortDay(d.day)) + '</span></div>';
    }).join('');
    var summary = tile(sum(s.learned), 'Đã học') + tile(sum(s.known), 'Đã biết')
      + tile(sum(s.notebook), 'Sổ tay') + tile((Number(s.currentStreak) || 0) + ' ngày', 'Chuỗi hiện tại');
    return { summary: summary, acc: acc, counts: counts, bars: bars };
  }

  function availableMonths(h) {
    return Array.from(new Set((h && Array.isArray(h.cells) ? h.cells : []).map(function (c) {
      return String(c.day || '').slice(0, 7);
    }).filter(function (m) { return /^\d{4}-\d{2}$/.test(m); })));
  }

  function heatmapMarkup(h, month) {
    var cells = h && Array.isArray(h.cells) ? h.cells : [];
    var months = availableMonths(h);
    var selected = month || months[months.length - 1];
    if (!selected) return '';
    var year = Number(selected.slice(0, 4));
    var monthIndex = Number(selected.slice(5, 7)) - 1;
    var first = new Date(Date.UTC(year, monthIndex, 1));
    var count = new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
    var byDay = {};
    cells.forEach(function (c) { if (String(c.day).slice(0, 7) === selected) byDay[c.day] = c; });
    var html = '<div class="jp-side-heat__grid">';
    ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'].forEach(function (day) {
      html += '<span class="jp-side-heat__weekday">' + day + '</span>';
    });
    for (var blank = 0; blank < (first.getUTCDay() + 6) % 7; blank++) {
      html += '<span class="jp-side-heat__cell is-empty" aria-hidden="true"></span>';
    }
    for (var day = 1; day <= count; day++) {
      var iso = selected + '-' + String(day).padStart(2, '0');
      var c = byDay[iso];
      if (!c) { html += '<span class="jp-side-heat__cell is-empty">' + day + '</span>'; continue; }
      var kind = c.kind === 'REST' ? 'ngày nghỉ' : c.kind === 'SKILL' ? 'ngày kỹ năng' : '';
      var label = shortDay(c.day) + ': ' + (Number(c.count) || 0) + ' lượt' + (kind ? ', ' + kind : '');
      html += '<a class="jp-side-heat__cell ' + heatClass(c.level) + '" href="/learning/jlpt/history?day='
        + encodeURIComponent(c.day) + '" title="' + esc(label) + '" aria-label="' + esc(label) + '">'
        + '<span>' + day + '</span>' + (kindMark(c.kind) ? icon(kindMark(c.kind)) : '') + '</a>';
    }
    return html + '</div>';
  }

  function badgesMarkup(list) {
    return (Array.isArray(list) ? list : []).map(function (b) {
      return '<li class="jp-side-badge' + (b.earned ? ' is-earned' : '') + '"><strong>' + esc(b.title) + '</strong>'
        + '<span>' + esc(b.description) + '</span><span class="jp-side-badge__state">'
        + (b.earned ? 'Đã đạt' : esc((Number(b.progress) || 0) + '/' + (Number(b.goal) || 0))) + '</span></li>';
    }).join('');
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-stats]');
    if (mounted || !main || !root.JpathApi) return;
    mounted = true;
    var api = root.JpathApi;
    var status = main.querySelector('[data-role="status"]');
    var part = function (name) { return main.querySelector('[data-role="' + name + '"]'); };
    var fail = function (err) { status.textContent = (err && err.message) || 'Không tải được thống kê.'; };
    var heatmap = null;
    var monthIndex = 0;
    main.querySelector('[data-month-step="-1"]').innerHTML = icon('chevron-left');
    main.querySelector('[data-month-step="1"]').innerHTML = icon('chevron-right');

    function drawMonth() {
      var months = availableMonths(heatmap);
      if (!months.length) { part('heatmap').textContent = 'Chưa có dữ liệu lịch học.'; return; }
      monthIndex = Math.max(0, Math.min(monthIndex, months.length - 1));
      var month = months[monthIndex];
      part('month-label').textContent = 'Tháng ' + Number(month.slice(5, 7)) + '/' + month.slice(0, 4);
      part('heatmap').innerHTML = heatmapMarkup(heatmap, month);
      main.querySelector('[data-month-step="-1"]').disabled = monthIndex === 0;
      main.querySelector('[data-month-step="1"]').disabled = monthIndex === months.length - 1;
    }

    function load(range) {
      main.querySelectorAll('[data-range]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-range') === String(range)));
      });
      status.textContent = 'Đang tải…';
      api.get(BASE + '/stats?range=' + range).then(function (s) {
        var m = statsMarkup(s || {});
        part('summary').innerHTML = m.summary;
        part('accuracy').innerHTML = m.acc;
        part('counts').innerHTML = m.counts;
        part('bars').innerHTML = m.bars;
        status.textContent = '';
      }, fail);
    }

    main.addEventListener('click', function (e) {
      var range = e.target.closest('[data-range]');
      if (range) load(Number(range.getAttribute('data-range')));
      var step = e.target.closest('[data-month-step]');
      if (step) { monthIndex += Number(step.getAttribute('data-month-step')); drawMonth(); }
    });
    load(7);
    api.get(BASE + '/stats/heatmap?months=6').then(function (h) {
      heatmap = h;
      monthIndex = availableMonths(h).length - 1;
      drawMonth();
    }, fail);
    api.get(BASE + '/badges').then(function (b) { part('badges').innerHTML = badgesMarkup(b); }, fail);
  }

  return { heatmapColumns: heatmapColumns, barHeights: barHeights, heatClass: heatClass, kindMark: kindMark,
    availableMonths: availableMonths,
    statsMarkup: statsMarkup, heatmapMarkup: heatmapMarkup, badgesMarkup: badgesMarkup, mount: mount };
});
