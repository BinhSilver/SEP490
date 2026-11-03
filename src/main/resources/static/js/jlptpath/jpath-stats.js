/* [JLAS-W0108] jpath-stats.js — stats page of the JLPT path (plan Task 67).
 *
 * window.JpathStats = { heatmapColumns(cells, from), barHeights(values, maxPx), heatClass(level), kindMark(kind), mount() }
 *   - GET /api/learning/jlpt/stats?range=7|30: accuracy tiles ("—" when null), learned / known / notebook, streaks and
 *     one CSS bar per day; GET /stats/heatmap?months=6: weeks as columns (Monday first, 💤 rest, 📖 skill), a cell
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
    return kind === 'REST' ? '💤' : kind === 'SKILL' ? '📖' : '';
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
    return { acc: acc, counts: counts, bars: bars };
  }

  function heatmapMarkup(h) {
    var cols = heatmapColumns(h && h.cells, h && h.from);
    return '<div class="jp-side-heat__grid">' + cols.map(function (col) {
      return '<div class="jp-side-heat__col">' + col.map(function (c) {
        if (!c) return '<span class="jp-side-heat__cell is-empty" aria-hidden="true"></span>';
        var label = shortDay(c.day) + ': ' + (Number(c.count) || 0) + ' lượt';
        return '<a class="jp-side-heat__cell ' + heatClass(c.level) + '" href="/learning/jlpt/history?day='
          + encodeURIComponent(c.day) + '" title="' + esc(label) + '" aria-label="' + esc(label) + '">'
          + esc(kindMark(c.kind)) + '</a>';
      }).join('') + '</div>';
    }).join('') + '</div>';
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

    function load(range) {
      main.querySelectorAll('[data-range]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-range') === String(range)));
      });
      status.textContent = 'Đang tải…';
      api.get(BASE + '/stats?range=' + range).then(function (s) {
        var m = statsMarkup(s || {});
        part('accuracy').innerHTML = m.acc;
        part('counts').innerHTML = m.counts;
        part('bars').innerHTML = m.bars;
        status.textContent = '';
      }, fail);
    }

    main.addEventListener('click', function (e) {
      var range = e.target.closest('[data-range]');
      if (range) load(Number(range.getAttribute('data-range')));
    });
    load(7);
    api.get(BASE + '/stats/heatmap?months=6').then(function (h) { part('heatmap').innerHTML = heatmapMarkup(h); }, fail);
    api.get(BASE + '/badges').then(function (b) { part('badges').innerHTML = badgesMarkup(b); }, fail);
  }

  return { heatmapColumns: heatmapColumns, barHeights: barHeights, heatClass: heatClass, kindMark: kindMark,
    statsMarkup: statsMarkup, heatmapMarkup: heatmapMarkup, badgesMarkup: badgesMarkup, mount: mount };
});
