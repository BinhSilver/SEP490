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

  /** A round axis top at or above max: 1, 2, 5 or 10 × a power of ten (5 when every day is zero). */
  function niceMax(max) {
    var m = Math.max(0, Number(max) || 0);
    if (!m) return 5;
    var p = Math.pow(10, Math.floor(Math.log10(m)));
    var steps = [1, 2, 5, 10];
    for (var i = 0; i < steps.length; i++) if (steps[i] * p >= m) return steps[i] * p;
    return 10 * p;
  }

  /**
   * Owner 23/11: the "Mỗi ngày" chart, always shown — one stacked column per day (Học mới at the base, Ôn lại on top,
   * a 2px gap between them), a recessive 3-line grid, a value only on the busiest day and today, a hover / focus
   * tooltip with both numbers, a legend, and a table for screen readers. With 30 days only every 5th date is written.
   */
  function dayChartHtml(days) {
    var list = Array.isArray(days) ? days : [];
    if (!list.length) return '<p class="jp-daychart__empty">Chưa có ngày học nào trong khoảng này.</p>';
    var rows = list.map(function (d) {
      var n = Math.max(0, Number(d.newLearned) || 0), r = Math.max(0, Number(d.reviewed) || 0);
      return { day: shortDay(d.day), n: n, r: r, t: n + r };
    });
    var max = Math.max.apply(null, rows.map(function (x) { return x.t; }));
    var top = niceMax(max);
    var peak = max > 0 ? rows.map(function (x) { return x.t; }).indexOf(max) : -1;
    var last = rows.length - 1;
    var every = rows.length > 10 ? 5 : 1;
    var pct = function (v) { return (v / top * 100).toFixed(2) + '%'; };
    var grid = [top, Math.round(top / 2), 0].map(function (v) {
      return '<span class="jp-daychart__grid" style="bottom:' + pct(v) + '"><span class="jp-daychart__tick">' + v + '</span></span>';
    }).join('');
    var cols = rows.map(function (x, i) {
      var label = (i === peak || i === last) && x.t > 0 ? '<span class="jp-daychart__val">' + x.t + '</span>' : '';
      var tip = x.day + ': ' + x.n + ' học mới, ' + x.r + ' ôn lại';
      return '<div class="jp-daychart__col' + (i === last ? ' is-today' : '') + '" tabindex="0" aria-label="' + esc(tip) + '">'
        + '<div class="jp-daychart__stack" style="height:' + pct(x.t) + '">' + label
        + (x.r ? '<span class="jp-daychart__seg jp-daychart__seg--rev is-top" style="flex-grow:' + x.r + '"></span>' : '')
        + (x.n ? '<span class="jp-daychart__seg jp-daychart__seg--new' + (x.r ? '' : ' is-top') + '" style="flex-grow:' + x.n + '"></span>' : '')
        + '</div>'
        + '<span class="jp-daychart__tip" role="tooltip"><strong>' + esc(x.day) + '</strong>'
        + '<span><i class="jp-daychart__sw jp-daychart__sw--new"></i>Học mới ' + x.n + '</span>'
        + '<span><i class="jp-daychart__sw jp-daychart__sw--rev"></i>Ôn lại ' + x.r + '</span></span>'
        + '<span class="jp-daychart__day">' + (i % every === 0 || i === last ? esc(i === last ? 'Hôm nay' : x.day) : '') + '</span>'
        + '</div>';
    }).join('');
    var table = '<table class="jp-sr-only"><caption>Số mục mỗi ngày</caption><thead><tr><th>Ngày</th><th>Học mới</th><th>Ôn lại</th></tr></thead><tbody>'
      + rows.map(function (x) { return '<tr><td>' + esc(x.day) + '</td><td>' + x.n + '</td><td>' + x.r + '</td></tr>'; }).join('')
      + '</tbody></table>';
    return '<div class="jp-daychart' + (rows.length > 10 ? ' is-dense' : '') + '">'
      + '<div class="jp-daychart__legend" aria-hidden="true"><span><i class="jp-daychart__sw jp-daychart__sw--new"></i>Học mới</span>'
      + '<span><i class="jp-daychart__sw jp-daychart__sw--rev"></i>Ôn lại</span></div>'
      + '<div class="jp-daychart__plot">' + grid + '<div class="jp-daychart__cols">' + cols + '</div></div>' + table + '</div>';
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

  function card(value, label, sub, tone, ico) {
    return '<div class="jp-stat-card jp-stat--' + tone + '"><span class="jp-stat-card__icon">' + icon(ico) + '</span>'
      + '<span class="jp-stat-card__body"><span class="jp-stat-card__label">' + esc(label) + '</span><strong class="jp-stat-card__value">'
      + esc(value) + '</strong>' + (sub ? '<span class="jp-stat-card__sub">' + esc(sub) + '</span>' : '') + '</span></div>';
  }

  var SKILL_TONE = { VOCAB: 'indigo', KANJI: 'mauve', GRAMMAR: 'teal', KANA: 'purple' };

  /** Mean of the accuracies that have data, or null. */
  function meanAccuracy(acc) {
    var v = ACC_TABS.map(function (t) { return acc ? acc[t[0]] : null; })
      .filter(function (x) { return x !== null && x !== undefined && !isNaN(Number(x)); }).map(Number);
    return v.length ? v.reduce(function (a, x) { return a + x; }, 0) / v.length : null;
  }

  /**
   * Owner 22/11 (F224): stat cards (streak, learned, known before, accuracy), one progress row per part
   * (learned + known, accuracy bar), and the per-day bars. {@code acc}/{@code counts} stay for the details block.
   */
  function statsMarkup(s) {
    var acc = ACC_TABS.map(function (t) { return tile(percent(s.accuracy && s.accuracy[t[0]]), t[1]); }).join('');
    var counts = tile(sum(s.learned), 'Đã học') + tile(sum(s.known), 'Đã biết trước')
      + tile((Number(s.currentStreak) || 0) + ' ngày', 'Chuỗi hiện tại')
      + tile((Number(s.longestStreak) || 0) + ' ngày', 'Chuỗi dài nhất');
    var days = Array.isArray(s.days) ? s.days : [];
    var bars = dayChartHtml(days);
    var mean = meanAccuracy(s.accuracy);
    var summary = card((Number(s.currentStreak) || 0) + ' ngày', 'Chuỗi hiện tại', 'Dài nhất ' + (Number(s.longestStreak) || 0) + ' ngày', 'purple', 'zap')
      + card(sum(s.learned), 'Mục đã học', 'Từ, Kanji, ngữ pháp, chữ cái', 'indigo', 'book-open')
      + card(sum(s.known), 'Đã biết trước', 'Không cần học lại', 'teal', 'circle-check')
      + card(percent(mean), 'Trả lời đúng', 'Trung bình các phần', 'sky', 'target');
    var skills = ACC_TABS.map(function (t) {
      var learned = Number(s.learned && s.learned[t[0]]) || 0;
      var known = Number(s.known && s.known[t[0]]) || 0;
      var a = s.accuracy ? s.accuracy[t[0]] : null;
      var has = a !== null && a !== undefined && !isNaN(Number(a));
      var width = has ? Math.max(0, Math.min(100, Math.round(Number(a)))) : 0;
      return '<li class="jp-stat-skill jp-stat--' + SKILL_TONE[t[0]] + '"><div class="jp-stat-skill__head"><strong>' + esc(t[1]) + '</strong><span>'
        + esc(learned + ' đã học' + (known ? ' · ' + known + ' đã biết trước' : '')) + '</span></div>'
        + '<div class="jp-stat-skill__bar" role="img" aria-label="' + esc(t[1] + ': đúng ' + (has ? width + '%' : 'chưa có dữ liệu')) + '">'
        + '<span style="width:' + width + '%"></span></div>'
        + '<span class="jp-stat-skill__acc">' + (has ? 'Đúng ' + width + '%' : 'Chưa có bài') + '</span></li>';
    }).join('');
    return { summary: summary, skills: skills, acc: acc, counts: counts, bars: bars };
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

  /** Earned badges first, then the closest to earn; each not yet earned shows a progress bar. */
  /**
   * Owner 22/11: a badge must read at a glance — a medal in the colour of its kind with its own mark
   * (words, streak, kana, skill stage, mock test level); an earned medal is filled, one not earned is pale.
   */
  function badgeKind(code) {
    var c = String(code || '');
    var mock = /^MOCK_PASS_(N[1-5])$/.exec(c);
    if (mock) return { tone: 'sky', mark: mock[1] };
    if (/^STREAK_/.test(c)) return { tone: 'purple', icon: 'zap' };
    if (/^KANA_/.test(c)) return { tone: 'mauve', mark: 'あ' };
    if (/^KNOWN_|^WORDS_|^VOCAB_/.test(c)) return { tone: 'indigo', icon: 'book-open' };
    return { tone: 'teal', icon: 'target' };
  }

  function medalHtml(b) {
    var k = badgeKind(b.code);
    var inner = k.mark ? '<span class="jp-medal__mark"' + (k.mark === 'あ' ? ' lang="ja"' : '') + '>' + esc(k.mark) + '</span>'
      : icon(k.icon);
    return '<span class="jp-medal jp-stat--' + k.tone + (b.earned ? ' is-earned' : '') + '" aria-hidden="true">' + inner
      + (b.earned ? '<span class="jp-medal__check">' + icon('check') + '</span>' : '') + '</span>';
  }

  /** Earned badges first, then the closest to earn; each not yet earned shows a progress bar. */
  function badgesMarkup(list) {
    var all = (Array.isArray(list) ? list : []).slice();
    var ratio = function (b) { return b.earned ? 2 : (Number(b.goal) ? (Number(b.progress) || 0) / Number(b.goal) : 0); };
    all.sort(function (x, y) { return ratio(y) - ratio(x); });
    return all.map(function (b) {
      var pct = b.earned ? 100 : Math.max(0, Math.min(100, Math.round(ratio(b) * 100)));
      var tone = badgeKind(b.code).tone;
      return '<li class="jp-side-badge jp-stat--' + tone + (b.earned ? ' is-earned' : '') + '">' + medalHtml(b)
        + '<span class="jp-side-badge__body"><strong>' + esc(b.title) + '</strong>'
        + '<span class="jp-side-badge__desc">' + esc(b.description) + '</span>'
        + (b.earned ? '' : '<span class="jp-side-badge__bar" aria-hidden="true"><span style="width:' + pct + '%"></span></span>')
        + '<span class="jp-side-badge__state">'
        + (b.earned ? 'Đã đạt' : esc((Number(b.progress) || 0) + '/' + (Number(b.goal) || 0))) + '</span></span></li>';
    }).join('');
  }

  /** '3/12 huy hiệu' line over the strip. */
  function badgesLine(list) {
    var all = Array.isArray(list) ? list : [];
    return all.filter(function (b) { return b.earned; }).length + '/' + all.length + ' huy hiệu đã đạt';
  }

  /** Owner 23/11: "3/12" with a small ring of the earned share, shown beside the chevron button. */
  function badgesTallyHtml(list) {
    var all = Array.isArray(list) ? list : [];
    var earned = all.filter(function (b) { return b.earned; }).length;
    var total = all.length;
    var r = 8, c = 2 * Math.PI * r;
    var off = (c - (total ? earned / total : 0) * c).toFixed(1);
    return '<span class="jp-stat-badges__frac" aria-label="' + esc(badgesLine(all)) + '"><strong>' + earned + '</strong>/' + total + '</span>'
      + '<svg class="jp-stat-badges__ring" viewBox="0 0 22 22" width="22" height="22" aria-hidden="true">'
      + '<circle cx="11" cy="11" r="' + r + '" fill="none" stroke-width="2.5" class="jp-stat-badges__ring-bg"/>'
      + '<circle cx="11" cy="11" r="' + r + '" fill="none" stroke-width="2.5" class="jp-stat-badges__ring-fg"'
      + ' stroke-dasharray="' + c.toFixed(1) + '" stroke-dashoffset="' + off + '" stroke-linecap="round" transform="rotate(-90 11 11)"/></svg>';
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
    var fail = function (err) { status.textContent = (err && (err.code || err.status) && err.message) || 'Không tải được thống kê.'; };
    var heatmap = null;
    var monthIndex = 0;
    // owner 23/11: thin stroke chevrons in one compact pill (the sprite chevrons read as solid triangles at this size)
    var chev = function (pts) {
      return '<svg class="jp-month-pill__chev" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor"'
        + ' stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"><polyline points="' + pts + '"/></svg>';
    };
    main.querySelector('[data-month-step="-1"]').innerHTML = chev('15 6 9 12 15 18');
    main.querySelector('[data-month-step="1"]').innerHTML = chev('9 6 15 12 9 18');

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
        if (part('skills')) part('skills').innerHTML = m.skills;
        if (part('accuracy')) part('accuracy').innerHTML = m.acc;
        if (part('counts')) part('counts').innerHTML = m.counts;
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
    api.get(BASE + '/badges').then(function (b) {
      part('badges').innerHTML = badgesMarkup(b);
      if (part('badges-line')) part('badges-line').textContent = badgesLine(b);
      if (part('badges-tally')) part('badges-tally').innerHTML = badgesTallyHtml(b);
      var more = part('badges-more');
      var total = Array.isArray(b) ? b.length : 0;
      if (more) {
        more.hidden = total <= 6;
        // owner 23/11: a small square chevron button (down = show all, up = fold), not a text pill
        more.innerHTML = '<svg class="jp-stat-badges__chev" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor"'
          + ' stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
        more.setAttribute('aria-label', 'Xem tất cả ' + total + ' huy hiệu');
        more.title = 'Xem tất cả ' + total + ' huy hiệu';
        more.onclick = function () {
          var all = part('badges').classList.toggle('is-all');
          var label = all ? 'Thu gọn huy hiệu' : 'Xem tất cả ' + total + ' huy hiệu';
          more.setAttribute('aria-label', label);
          more.title = label;
          more.setAttribute('aria-expanded', String(all));
        };
      }
    }, fail);
  }

  return { heatmapColumns: heatmapColumns, barHeights: barHeights, niceMax: niceMax, dayChartHtml: dayChartHtml, heatClass: heatClass, kindMark: kindMark,
    availableMonths: availableMonths,
    statsMarkup: statsMarkup, heatmapMarkup: heatmapMarkup, badgesMarkup: badgesMarkup, badgesLine: badgesLine, badgesTallyHtml: badgesTallyHtml, badgeKind: badgeKind, mount: mount };
});
