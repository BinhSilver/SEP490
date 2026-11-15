/* cpath-day.js — Trang Chi tiết Ngày học Lộ trình Giao tiếp (day.html).
 * Dạng UMD: window.CpathDay, gọi GET /api/learning/comm/day?d=YYYY-MM-DD -> {day, situations:[...], scores:[...], cp}.
 * Dùng chung phong cách với trang chi tiết ngày của lộ trình JLPT (jpath-day.js + jpath-side.css).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathDay = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var WEEKDAY = ['Chủ nhật', 'Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy'];
  var WEEKDAY_SHORT = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
  var SVG_CHECK = '<svg class="jp-dayd__node-mark" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8.2L6.6 11.3L12.5 5.2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  var SVG_LOCK = '<svg class="jp-dayd__node-lock" viewBox="0 0 16 16" aria-hidden="true"><rect x="3.5" y="7.5" width="9" height="6.5" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M5.5 7.5V5.6a2.5 2.5 0 0 1 5 0v1.9" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
  var SVG_SCENERY = '<svg class="jp-dayd__scenery" viewBox="0 0 800 140" preserveAspectRatio="none" aria-hidden="true">'
    + '<rect width="800" height="140" fill="var(--jp-c-purple-100)" opacity="0.65"/>'
    + '<circle cx="690" cy="36" r="22" fill="var(--jp-c-peach-100)"/>'
    + '<path d="M0 108 Q180 68 380 98 T800 86 L800 140 L0 140 Z" fill="var(--jp-c-indigo-100)"/>'
    + '<path d="M0 118 Q240 84 490 112 T800 102 L800 140 L0 140 Z" fill="var(--jp-c-purple-100)"/>'
    + '</svg>';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(v) { return Array.isArray(v) ? v : []; }

  function kitsuSrc(file) {
    return '/images/mascots/kitsu/' + file;
  }

  function pageUrl(page, params) {
    var base = '/learning/comm/' + page;
    var q = [];
    if (params) {
      Object.keys(params).forEach(function (k) {
        if (params[k] != null && params[k] !== '') q.push(encodeURIComponent(k) + '=' + encodeURIComponent(params[k]));
      });
    }
    return q.length ? (base + '?' + q.join('&')) : base;
  }

  function parseIso(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    return m ? new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])) : null;
  }

  function toIso(d) { return d.toISOString().slice(0, 10); }

  function shift(day, n) {
    var d = parseIso(day);
    if (!d) return day;
    d.setUTCDate(d.getUTCDate() + n);
    return toIso(d);
  }

  function shortDate(day) {
    var d = parseIso(day);
    if (!d) return esc(day);
    return String(d.getUTCDate()).padStart(2, '0') + '/' + String(d.getUTCMonth() + 1).padStart(2, '0');
  }

  function title(day) {
    var d = parseIso(day);
    if (!d) return esc(day);
    return WEEKDAY[d.getUTCDay()] + ' ' + shortDate(day);
  }

  function weekDays(day) {
    var d = parseIso(day);
    if (!d) return [];
    var wd = d.getUTCDay();
    var offset = (wd + 6) % 7;
    var mon = shift(day, -offset);
    var out = [];
    for (var i = 0; i < 7; i++) out.push(shift(mon, i));
    return out;
  }

  function weekStripHtml(day, today, isDone, weekStatus) {
    var days = weekDays(day);
    if (!days.length) return '';
    var cells = days.map(function (di, idx) {
      var dt = parseIso(di);
      var wdShort = dt ? WEEKDAY_SHORT[dt.getUTCDay()] : '';
      var dm = shortDate(di);
      var isCurrent = di === day;
      var isToday = di === today;
      var isFuture = today && di > today;
      // F176: a past day is ticked only when the server says all its situations were finished (empty days too before)
      var st = weekStatus && weekStatus[di];
      var isPastDone = st ? (!isFuture && st === 'DONE') : ((!isFuture && !isCurrent) || (isCurrent && isDone));
      var cls = 'jp-dayd__node'
        + (isCurrent ? ' is-current' : '')
        + (isToday ? ' is-today' : '')
        + (isFuture ? ' is-future' : ' is-past')
        + (isPastDone ? ' is-done' : '');
      var badge = isPastDone
        ? '<span class="jp-dayd__node-tick" title="Đã học">' + SVG_CHECK + '</span>'
        : (isFuture ? '<span class="jp-dayd__node-lockwrap" title="Chưa mở">' + SVG_LOCK + '</span>' : '');
      var inner = '<span class="jp-dayd__circle">'
        + '<span class="jp-dayd__wd">' + esc(wdShort) + '</span>'
        + '<span class="jp-dayd__dm">' + esc(dm) + '</span>'
        + badge
        + '</span>'
        + (isToday ? '<span class="jp-dayd__today-tag">Hôm nay</span>' : '');
      var stepCls = 'jp-dayd__step' + (isFuture ? ' is-future' : ' is-past') + (isCurrent ? ' is-current' : '');
      if (isFuture) {
        return '<li class="' + stepCls + '" style="--jp-step-idx:' + idx + '"><span class="' + cls + '" aria-disabled="true">' + inner + '</span></li>';
      }
      return '<li class="' + stepCls + '" style="--jp-step-idx:' + idx + '"><a class="' + cls + '" href="'
        + esc(pageUrl('day', { d: di })) + '"' + (isCurrent ? ' aria-current="date"' : '') + '>' + inner + '</a></li>';
    });
    return '<div class="jp-dayd__banner">'
      + SVG_SCENERY
      + '<nav class="jp-dayd__week" aria-label="Các ngày trong tuần"><ol class="jp-dayd__week-list">'
      + cells.join('') + '</ol></nav>'
      + '</div>';
  }

  function dayHtml(data, day, today) {
    var x = data || {};
    var situations = list(x.situations);
    var scores = list(x.scores);
    var allDone = situations.length > 0 && situations.every(function (s) { return s.status === 'DONE' || s.status === 'TODAY_DONE'; });

    var strip = weekStripHtml(day, today, allDone, x.weekStatus);
    var nav = '<nav class="jp-dayd__nav" aria-label="Ngày khác">'
      + '<a class="jp-btn" href="' + esc(pageUrl('day', { d: shift(day, -1) })) + '">‹ Hôm trước</a>'
      + (day < today ? '<a class="jp-btn" href="' + esc(pageUrl('day', { d: shift(day, 1) })) + '">Hôm sau ›</a>' : '')
      + '</nav>';

    var restEmpty = !situations.length && x.restDay === true; // F116
    var statusText = allDone ? 'Đã xong ngày' : (situations.length ? 'Đang học trong ngày' : (restEmpty ? 'Ngày nghỉ' : 'Ngày này chưa có bài'));
    var statusCls = allDone ? 'jp-dayd__badge jp-dayd__badge--done' : 'jp-dayd__badge jp-dayd__badge--pending';
    var cpBadge = Number(x.cp) > 0
      ? ' <span class="jp-dayd__points-sep">·</span> <span class="jp-dayd__badge jp-dayd__badge--cp">+' + esc(x.cp) + ' CP</span>'
      : '';

    var kitsuImg = allDone ? 'kitsu-celebrate-30.webp' : (situations.length ? 'kitsu-cheer-30.webp' : 'kitsu-resting-30.webp');
    var kitsuSpeech = allDone
      ? 'Bạn đã hoàn thành đầy đủ các tình huống giao tiếp của ngày này!'
      : (situations.length ? 'Cùng hoàn thành nốt các bước hội thoại trong ngày nhé!'
        : (restEmpty ? (day === today ? 'Hôm nay là ngày nghỉ của bạn. Muốn học thêm thì về lộ trình bấm "Học thêm 1 tình huống".' : 'Đây là ngày nghỉ của bạn.') : 'Ngày này chưa có lịch học.'));

    var sitItemsHtml = situations.map(function (s) {
      var st = s.steps || {};
      var isSitDone = s.status === 'DONE' || s.status === 'TODAY_DONE';
      var sitHref = pageUrl('situation', { id: s.id });
      return '<li class="jp-today__pill jp-todo__item' + (isSitDone ? ' is-done' : '') + '">'
        + '<div class="jp-today__pill-body">'
        + '<div class="jp-today__pill-head">'
        + '<strong class="jp-today__pill-title">' + esc(s.titleVi || s.id) + '</strong>'
        + '<span class="jp-today__pill-count">' + esc(s.topicNameVi || s.topicCode || '') + (s.score != null ? (' · ' + s.score + ' điểm') : '') + '</span>'
        + '</div>'
        + '<div class="cp-survey__sub">'
        + (data && data.goal === 'TRAVEL' ? 'Nghe & chọn nghĩa' : 'Từ & mẫu câu') + ': <strong>' + (st.words ? 'Xong' : 'Chưa') + '</strong> · '
        + 'Nghe: <strong>' + (st.listen ? 'Xong' : 'Chưa') + '</strong> · '
        + 'Nhập vai: <strong>' + (st.roleplay ? 'Xong' : 'Chưa') + '</strong> · '
        + 'Nói: <strong>' + (st.speak ? 'Xong' : 'Chưa') + '</strong>'
        + '</div>'
        + '</div>'
        + '<a class="cp-btn-primary" href="' + esc(sitHref) + '">' + (isSitDone ? 'Ôn lại' : 'Tiếp tục học') + '</a>'
        + '</li>';
    }).join('');

    var scoreRowsHtml = scores.map(function (sc) {
      var val = sc.score != null ? sc.score : sc.value;
      var label = sc.labelVi || sc.type || 'Điểm kỹ năng';
      var note = sc.noteVi || '';
      return '<li class="cp-pill is-selected" style="cursor:default;">'
        + '<div class="cp-pill__body">'
        + '<span class="cp-pill__label">' + esc(label) + ': <strong>' + esc(val) + ' điểm</strong></span>'
        + (note ? '<span class="cp-pill__desc">' + esc(note) + '</span>' : '')
        + '</div>'
        + '</li>';
    }).join('');

    return strip
      + '<header class="jp-dayd__head"><h1 class="jp-page-head__title">' + title(day) + ' · Lộ trình Giao tiếp</h1>' + nav + '</header>'
      + '<div class="jp-dayd__hero-row">'
      + '<div class="jp-dayd__hero-info">'
      + '<p class="jp-dayd__sum"><span class="' + statusCls + '">' + (allDone ? SVG_CHECK + ' ' : '') + statusText + '</span>' + cpBadge + '</p>'
      + '</div>'
      + '<aside class="jp-dayd__mascot" aria-label="Bạn đồng hành">'
      + '<img class="jp-dayd__mascot-img" src="' + esc(kitsuSrc(kitsuImg)) + '" width="64" height="64" alt="" />'
      + '<div class="jp-dayd__speech"><p class="jp-dayd__speech-main">' + esc(kitsuSpeech) + '</p></div>'
      + '</aside>'
      + '</div>'
      + '<section class="jp-dayd__sec">'
      + '<h2 class="jp-dayd__h"><span>Tình huống trong ngày</span><span class="jp-dayd__meta"> · ' + situations.length + ' tình huống</span></h2>'
      + (situations.length ? '<ul class="jp-todo">' + sitItemsHtml + '</ul>' : '<p class="jp-side__note">Chưa có tình huống nào trong ngày này.</p>')
      + '</section>'
      + (scores.length
        ? '<section class="jp-dayd__sec">'
          + '<h2 class="jp-dayd__h"><span>Kết quả chấm điểm</span></h2>'
          + '<ul class="cp-survey__grid" style="list-style:none;padding:0;margin:0;">' + scoreRowsHtml + '</ul>'
          + '</section>'
        : '')
      + '<div class="jp-dayd__actions" style="margin-top:12px;">'
      + '<a class="cp-btn-primary" href="' + esc(pageUrl('path')) + '">Về lộ trình giao tiếp</a>'
      + '</div>';
  }

  function mount(targetEl) {
    var doc = root && root.document;
    var el = targetEl || (doc && doc.getElementById && doc.getElementById('cpathDayRoot'));
    if (!el || !root.JpathApi) return;

    // the learner day comes from the server (app clock), never from the browser's calendar (E2E-002)
    var search = (root && root.location && root.location.search) ? String(root.location.search) : '';
    var m = /[?&]d=(\d{4}-\d{2}-\d{2})/.exec(search);
    var day = m ? m[1] : null;
    // F94: ?d=2026-02-31 / 2026-13-01 match the pattern but are no real day; the server answers 400
    // and "Thử lại" would ask the same bad day forever, so open today instead
    if (day) {
      var probe = new Date(day + 'T00:00:00Z');
      if (isNaN(probe.getTime()) || probe.toISOString().slice(0, 10) !== day) day = null;
    }

    function load() {
      if (el.setAttribute) el.setAttribute('aria-busy', 'true');
      root.JpathApi.get('/api/learning/comm/day' + (day ? '?d=' + encodeURIComponent(day) : '')).then(function (data) {
        if (!data) throw new Error('Chưa tải được dữ liệu ngày học.');
        var today = String(data.today || data.day || '').slice(0, 10);
        if (!day || day > today) {
          // no day asked, or a day after today: show today
          if (day && day > today) { day = today; load(); return; }
          day = String(data.day || today).slice(0, 10);
        }
        if (el.setAttribute) el.setAttribute('aria-busy', 'false');
        el.innerHTML = dayHtml(data, day, today);
      }).catch(function (error) {
        if (el.setAttribute) el.setAttribute('aria-busy', 'false');
        el.innerHTML = '<p class="jp-alert" role="alert">' + esc(error && error.message || 'Chưa tải được chi tiết ngày.')
          + ' <button type="button" class="jp-btn" data-cp-retry>Thử lại</button></p>';
        var retry = el.querySelector('[data-cp-retry]');
        if (retry) retry.addEventListener('click', load);
      });
    }
    load();
  }

  return {
    dayHtml: dayHtml,
    shift: shift,
    weekDays: weekDays,
    mount: mount
  };
});
