/* [JLAS-W0108] my-paths-core.js (owner 24/11, "Lộ trình của tôi"): pure HTML builders and choices for the
 * dashboard (/learning/my) and the switcher on every path page. No DOM, no fetch: the page scripts call these with
 * the GET /api/learning/my-paths view (spec target/phuong-an-cac-lo-trinh-cua-toi-2026-11-24.md §2 and §4).
 *
 * Rules drawn here:
 *   - paths are equal (no main/secondary); at most `maxActive` study at the same time;
 *   - "Học hết hôm nay" opens the first ACTIVE path whose today is not done, in list order; when every ACTIVE path
 *     is done it says so and opens nothing;
 *   - each card keeps its own "Học hôm nay" (that path alone), "Vào lộ trình" and Tạm dừng / Học tiếp;
 *   - the switcher shows only with 2+ ACTIVE paths.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.MyPathsCore = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var STATUS_LABEL = { ACTIVE: 'Đang học', PAUSED: 'Tạm dừng', FINISHED: 'Đã xong' };

  function esc(v) {
    return String(v === null || v === undefined ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** Same-origin learning links only (no open redirect from the API). */
  function safeUrl(v, fallback) {
    return typeof v === 'string' && /^\/learning(\/|\?|$)/.test(v) && v.indexOf('//') < 0 ? v : (fallback || '/learning');
  }

  function num(v) { var n = Number(v); return isFinite(n) ? n : 0; }

  /** dd/mm/yyyy from yyyy-mm-dd, or '' */
  function dateVi(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
  }

  function paths(view) { return view && Array.isArray(view.paths) ? view.paths : []; }
  function active(view) { return paths(view).filter(function (p) { return p && p.status === 'ACTIVE'; }); }

  /** The path "Học hết hôm nay" opens next, or null when every ACTIVE path is done today. */
  function nextToday(view) {
    var list = active(view);
    for (var i = 0; i < list.length; i++) if (!list[i].todayDone) return list[i];
    return null;
  }

  /** {done, total} of ACTIVE paths finished today. */
  function todayProgress(view) {
    var list = active(view);
    return { done: list.filter(function (p) { return !!p.todayDone; }).length, total: list.length };
  }

  /** Learner-facing reason why a resume must ask first, or '' when there is a free place. */
  function needsChoice(view) {
    var max = num(view && view.maxActive) || 2;
    return active(view).length >= max;
  }

  function ring(percent) {
    var p = Math.max(0, Math.min(100, Math.round(num(percent))));
    return '<span class="mp-ring" style="--mp-p:' + p + '" role="img" aria-label="Tiến độ ' + p + '%">'
      + '<span class="mp-ring__text">' + p + '%</span></span>';
  }

  function todayLine(p) {
    if (p.status !== 'ACTIVE') return p.status === 'PAUSED' && p.pausedSince
      ? 'Tạm dừng từ ' + esc(dateVi(p.pausedSince)) + '. Tiến độ được giữ.' : (p.status === 'FINISHED' ? 'Đã học xong lộ trình này.' : 'Tiến độ được giữ.');
    if (p.todayDone) return 'Hôm nay: đã xong';
    var left = num(p.todayMinutesLeft);
    return left > 0 ? 'Hôm nay: còn khoảng ' + left + ' phút' : 'Hôm nay: chưa học';
  }

  function ioCore() {
    if (typeof PathIoCore !== 'undefined') return PathIoCore; // browser global
    try { return typeof require === 'function' ? require('./path-io-core.js') : null; } catch (e) { return null; }
  }

  function cardHtml(p) {
    var key = esc(p.key);
    var statusCls = 'mp-card--' + String(p.status || '').toLowerCase();
    var meta = [];
    if (num(p.minutesPerDay) > 0) meta.push('<span>' + num(p.minutesPerDay) + ' phút mỗi ngày</span>');
    if (p.finishDate && p.status !== 'FINISHED') meta.push('<span>Dự kiến xong ' + esc(dateVi(p.finishDate)) + '</span>');
    if (p.focusTopic) meta.push('<span>Tuần này: ' + esc(p.focusTopic) + '</span>');
    var actions = [];
    if (p.status === 'ACTIVE') {
      actions.push(p.todayDone
        ? '<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Vào lộ trình</a>'
        : '<a class="jp-btn jp-btn--primary" href="' + esc(safeUrl(p.todayUrl, safeUrl(p.url))) + '" data-mp-today="' + key + '">Học hôm nay</a>'
          + '<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Vào lộ trình</a>');
      actions.push('<button type="button" class="jp-btn mp-card__pause" data-mp-pause="' + key + '">Tạm dừng</button>');
    } else if (p.status === 'PAUSED') {
      actions.push('<button type="button" class="jp-btn jp-btn--primary" data-mp-resume="' + key + '">Học tiếp</button>');
      actions.push('<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Xem lộ trình</a>');
    } else {
      actions.push('<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Xem lại</a>');
    }
    return '<article class="mp-card ' + statusCls + '" data-mp-card="' + key + '">'
      + '<div class="mp-card__head">' + ring(p.percent)
      + '<div class="mp-card__title"><span class="mp-card__kind">' + (p.kind === 'JLPT' ? 'JLPT' : 'Giao tiếp') + '</span>'
      + '<h3>' + esc(p.title) + '</h3>'
      + '<span class="mp-badge mp-badge--' + String(p.status || '').toLowerCase() + '">' + esc(STATUS_LABEL[p.status] || p.status) + '</span></div></div>'
      + '<p class="mp-card__today' + (p.todayDone ? ' is-done' : '') + '">' + todayLine(p) + '</p>'
      + (meta.length ? '<p class="mp-card__meta">' + meta.join('') + '</p>' : '')
      // F240: entry and exit of the path (from /api/learning/path-io, merged in by the page)
      + (p.io && ioCore() ? ioCore().lineHtml(p.io) : '')
      + '<div class="mp-card__actions">' + actions.join('') + '</div>'
      + '</article>';
  }

  function heroHtml(view) {
    var list = active(view);
    if (!list.length) {
      return '<section class="mp-hero"><div><h2>Chưa có lộ trình nào đang học</h2>'
        + '<p>Chọn Học tiếp ở một lộ trình đang tạm dừng, hoặc thêm lộ trình mới.</p></div></section>';
    }
    var pr = todayProgress(view);
    var next = nextToday(view);
    var btn = next
      ? '<a class="jp-btn jp-btn--primary mp-hero__go" href="' + esc(safeUrl(next.todayUrl, safeUrl(next.url))) + '" data-mp-all>'
        + (pr.total > 1 ? 'Học hết hôm nay' : 'Học hôm nay') + '</a>'
        + (pr.total > 1 ? '<span class="mp-hero__next">Tiếp theo: ' + esc(next.title) + '</span>' : '')
      : '<span class="mp-hero__done">Hôm nay đã xong. Hẹn gặp lại ngày mai.</span>';
    var bar = pr.total > 1
      ? '<div class="mp-hero__bar" role="progressbar" aria-valuemin="0" aria-valuemax="' + pr.total + '" aria-valuenow="' + pr.done
        + '" aria-label="Đã xong ' + pr.done + ' trên ' + pr.total + ' lộ trình hôm nay"><span style="width:' + Math.round(pr.done * 100 / pr.total) + '%"></span></div>'
        + '<p class="mp-hero__count">Đã xong ' + pr.done + '/' + pr.total + ' lộ trình hôm nay</p>'
      : '';
    var left = list.filter(function (p) { return !p.todayDone; }).map(function (p) { return esc(p.title); });
    var remind = left.length && pr.total > 1 && pr.done > 0 ? '<p class="mp-hero__remind" role="status">Hôm nay còn ' + left.join(', ') + ' chưa học.</p>' : '';
    return '<section class="mp-hero"><div class="mp-hero__text"><h2>Hôm nay</h2>'
      + '<p>' + (pr.total > 1 ? 'Thẻ ôn của các lộ trình học trước, rồi lần lượt từng lộ trình.' : 'Phần hôm nay của lộ trình đang học.') + '</p>'
      + bar + remind + '</div><div class="mp-hero__cta">' + btn + '</div></section>';
  }

  function timeHtml(view) {
    var total = num(view && view.totalMinutes);
    if (!total) return '';
    var free = num(view && view.freeMinutes);
    var over = !!(view && view.overFreeTime) || (free > 0 && total > free);
    return '<section class="mp-time' + (over ? ' mp-time--over' : '') + '" role="' + (over ? 'alert' : 'note') + '">'
      + '<p><strong>Mỗi ngày khoảng ' + total + ' phút</strong>' + (free ? ' · thời gian rảnh bạn chọn: ' + free + ' phút' : '') + '</p>'
      + (over ? '<p>Tổng thời gian đang vượt thời gian rảnh. Giảm nhịp ở Cài đặt của một lộ trình, hoặc tạm dừng bớt.</p>' : '')
      + '</section>';
  }

  /** The whole dashboard body. */
  function dashboardHtml(view) {
    var list = paths(view);
    var max = num(view && view.maxActive) || 2;
    var order = { ACTIVE: 0, PAUSED: 1, FINISHED: 2 };
    var sorted = list.slice().sort(function (a, b) { return rank(a) - rank(b); });
    function rank(p) { return order.hasOwnProperty(p.status) ? order[p.status] : 3; }
    return heroHtml(view) + timeHtml(view)
      + '<section class="mp-list" aria-label="Các lộ trình"><div class="mp-list__head"><h2>Các lộ trình</h2>'
      + '<span class="mp-list__hint">Học cùng lúc tối đa ' + max + ' lộ trình. Tạm dừng lộ trình nào chỉ dừng lộ trình đó.</span></div>'
      + '<div class="mp-grid">' + sorted.map(cardHtml).join('')
      + '<a class="mp-card mp-card--add" href="/survey?edit=goal" data-mp-add><span class="mp-card__plus" aria-hidden="true">+</span>'
      + '<span>Thêm lộ trình</span><small>JLPT hoặc giao tiếp (Du lịch, Đời sống, Công việc)</small></a>'
      + '</div></section>';
  }

  /** Switcher bar for a path page; '' with fewer than 2 ACTIVE paths. */
  function switcherHtml(view, currentKey) {
    var list = active(view);
    if (list.length < 2) return '';
    return '<nav class="mp-switch" aria-label="Chuyển lộ trình">'
      + '<a class="mp-switch__home" href="/learning/my">Lộ trình của tôi</a>'
      + '<span class="mp-switch__tabs">' + list.map(function (p) {
        var cur = p.key === currentKey;
        return '<a class="mp-switch__tab' + (cur ? ' is-current' : '') + '" href="' + esc(safeUrl(p.url)) + '"'
          + (cur ? ' aria-current="page"' : '') + '>' + esc(p.title)
          + (p.todayDone ? '<span class="mp-switch__done">Xong hôm nay</span>' : '') + '</a>';
      }).join('') + '</span></nav>';
  }

  /** Key of the path a page belongs to: JLPT pages, or COMM:<goal> from ?goal= or the first ACTIVE comm path. */
  function currentKey(view, pathname, search) {
    var p = String(pathname || '');
    if (p.indexOf('/learning/comm') === 0) {
      var m = /[?&]goal=([A-Z_]+)/.exec(String(search || ''));
      if (m) return 'COMM:' + m[1];
      var c = active(view).filter(function (x) { return x.kind === 'COMM'; })[0];
      return c ? c.key : null;
    }
    if (p === '/learning' || p.indexOf('/learning/jlpt') === 0) return 'JLPT';
    return null;
  }

  /** Text and choices of the "already 2 studying" dialog for resuming `key`. */
  function fullChoice(view, key) {
    var target = paths(view).filter(function (p) { return p.key === key; })[0];
    var running = active(view);
    return {
      message: 'Bạn đang học ' + running.map(function (p) { return p.title; }).join(' và ')
        + '. Để học tiếp ' + (target ? target.title : 'lộ trình này') + ', chọn một lộ trình để tạm dừng (tiến độ được giữ).',
      choices: running.map(function (p) { return { key: p.key, label: 'Tạm dừng ' + p.title }; })
    };
  }

  return {
    esc: esc, safeUrl: safeUrl, dateVi: dateVi, nextToday: nextToday, todayProgress: todayProgress,
    needsChoice: needsChoice, cardHtml: cardHtml, dashboardHtml: dashboardHtml, switcherHtml: switcherHtml,
    currentKey: currentKey, fullChoice: fullChoice
  };
});
