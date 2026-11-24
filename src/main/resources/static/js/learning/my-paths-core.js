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

  function bar(percent) {
    var p = Math.max(0, Math.min(100, Math.round(num(percent))));
    return '<div class="mp-prog"><span class="mp-prog__bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="' + p
      + '" aria-label="Tiến độ ' + p + '%"><i style="width:' + p + '%"></i></span><span class="mp-prog__pct">' + p + '%</span></div>';
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

  /** The levels of a path as stops: passed, current, ahead. Only when the entry/exit data names them. */
  function stepsHtml(io) {
    if (!io || !Array.isArray(io.steps) || io.steps.length < 2) return '';
    var passed = Array.isArray(io.passed) ? io.passed : [];
    return '<ol class="mp-steps" aria-label="Các chặng của lộ trình">' + io.steps.map(function (st) {
      var cls = passed.indexOf(st) >= 0 ? ' is-passed' : (st === io.current ? ' is-current' : '');
      return '<li class="mp-steps__stop' + cls + '"' + (st === io.current ? ' aria-current="step"' : '') + '><span class="mp-steps__dot"></span>'
        + '<span class="mp-steps__name">' + esc(st) + '</span></li>';
    }).join('') + '</ol>';
  }

  var WEEKDAY = ['Chủ nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];
  function todayLabel(now) {
    var d = now instanceof Date ? now : new Date();
    return WEEKDAY[d.getDay()] + ', ' + ('0' + d.getDate()).slice(-2) + '/' + ('0' + (d.getMonth() + 1)).slice(-2);
  }
  var FOX = '/images/mascots/fox/';

  function kindLabel(p) { return p.kind === 'JLPT' ? 'JLPT' : 'Giao tiếp'; }

  /** Big card of a path being studied. */
  function cardHtml(p, single) {
    var key = esc(p.key);
    var meta = [];
    if (num(p.minutesPerDay) > 0) meta.push(num(p.minutesPerDay) + ' phút/ngày');
    if (p.finishDate && p.status !== 'FINISHED') meta.push('dự kiến xong ' + esc(dateVi(p.finishDate)));
    if (p.focusTopic) meta.push('tuần này: ' + esc(p.focusTopic));
    var actions = [];
    if (p.status === 'ACTIVE') {
      if (!p.todayDone && !single) actions.push('<a class="jp-btn jp-btn--primary" href="' + esc(safeUrl(p.todayUrl, safeUrl(p.url))) + '" data-mp-today="' + key + '">Học hôm nay</a>');
      actions.push('<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Vào lộ trình</a>');
      actions.push('<button type="button" class="mp-link mp-card__pause" data-mp-pause="' + key + '">Tạm dừng</button>');
    } else if (p.status === 'PAUSED') {
      actions.push('<button type="button" class="jp-btn jp-btn--primary" data-mp-resume="' + key + '">Học tiếp</button>');
      actions.push('<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Xem lộ trình</a>');
    } else {
      actions.push('<a class="jp-btn" href="' + esc(safeUrl(p.url)) + '">Xem lại</a>');
    }
    return '<article class="mp-card mp-card--' + String(p.status || '').toLowerCase() + ' mp-kind--' + (p.kind === 'JLPT' ? 'jlpt' : 'comm')
      + '" data-mp-card="' + key + '">'
      + '<header class="mp-card__head"><span class="mp-chip">' + kindLabel(p) + '</span>'
      + '<h3>' + esc(p.title) + '</h3></header>'
      + stepsHtml(p.io)
      + bar(p.percent)
      + '<p class="mp-card__today' + (p.todayDone ? ' is-done' : '') + '">' + todayLine(p) + '</p>'
      + (meta.length ? '<p class="mp-card__meta">' + meta.join(' · ') + '</p>' : '')
      + (p.io && ioCore() ? ioCore().lineHtml(p.io) : '')
      + '<div class="mp-card__actions">' + actions.join('') + '</div>'
      + '</article>';
  }

  /** One compact row of a paused or finished path. */
  function rowHtml(p) {
    var key = esc(p.key);
    var paused = p.status === 'PAUSED';
    var note = paused ? (p.pausedSince ? 'tạm dừng từ ' + esc(dateVi(p.pausedSince)) : 'tạm dừng') : 'đã xong';
    var act = paused
      ? '<button type="button" class="jp-btn" data-mp-resume="' + key + '">Học tiếp</button>'
        + '<a class="mp-link" href="' + esc(safeUrl(p.url)) + '">Xem</a>'
      : '<a class="mp-link" href="' + esc(safeUrl(p.url)) + '">Xem lại</a>';
    var pct = Math.max(0, Math.min(100, Math.round(num(p.percent))));
    return '<li class="mp-row mp-kind--' + (p.kind === 'JLPT' ? 'jlpt' : 'comm') + '" data-mp-card="' + key + '"><span class="mp-row__name">'
      + '<span class="mp-row__dot" aria-hidden="true"></span>' + esc(p.title)
      + '<span class="mp-row__bar" aria-hidden="true"><i style="width:' + pct + '%"></i></span>' + '</span><span class="mp-row__note">' + Math.round(num(p.percent)) + '% · ' + note + '</span>'
      + '<span class="mp-row__act">' + act + '</span></li>';
  }

  function todayHtml(view, now) {
    var list = active(view);
    var total = num(view && view.totalMinutes);
    var free = num(view && view.freeMinutes);
    var over = !!(view && view.overFreeTime) || (free > 0 && total > free);
    var date = '<span class="mp-today__date">' + todayLabel(now) + '</span>';
    if (!list.length) {
      return '<section class="mp-today"><img class="mp-today__fox" src="' + FOX + 'kitsu-resting-30-128.webp" alt="" width="96" height="96">'
        + '<div class="mp-today__text">' + date + '<h2>Chưa có lộ trình nào đang học</h2>'
        + '<p>Chọn Học tiếp ở một lộ trình bên dưới, hoặc thêm lộ trình mới.</p></div></section>';
    }
    var pr = todayProgress(view);
    var next = nextToday(view);
    var minutesLeft = list.reduce(function (sum, p) { return sum + (p.todayDone ? 0 : num(p.todayMinutesLeft)); }, 0);
    var sub = [];
    if (total) sub.push('Mỗi ngày khoảng ' + total + ' phút' + (free ? ', bạn rảnh ' + free + ' phút' : ''));
    if (pr.total > 1) sub.push('đã xong ' + pr.done + '/' + pr.total + ' lộ trình');
    var left = list.filter(function (p) { return !p.todayDone; }).map(function (p) { return esc(p.title); });
    var remind = left.length && pr.total > 1 && pr.done > 0 ? '<p class="mp-today__remind" role="status">Hôm nay còn ' + left.join(', ') + ' chưa học.</p>' : '';
    var headline = next
      ? (minutesLeft > 0 ? 'Còn khoảng <strong>' + minutesLeft + ' phút</strong> cho hôm nay' : 'Phần hôm nay đang chờ bạn')
      : 'Hôm nay đã xong. Hẹn gặp lại ngày mai.';
    var cta = !next ? '' :
      '<a class="jp-btn jp-btn--primary" href="' + esc(safeUrl(next.todayUrl, safeUrl(next.url))) + '" data-mp-all>'
      + (pr.total > 1 ? 'Học hết hôm nay' : 'Học hôm nay') + '</a>'
      + (pr.total > 1 ? '<span class="mp-today__next">Tiếp theo: ' + esc(next.title) + '</span>' : '');
    var fox = next ? 'kitsu-encouraging-128.webp' : 'kitsu-celebrate-30-128.webp';
    return '<section class="mp-today' + (next ? '' : ' is-done') + (over ? ' mp-today--over' : '') + '">'
      + '<img class="mp-today__fox" src="' + FOX + fox + '" alt="" width="96" height="96">'
      + '<div class="mp-today__text">' + date + '<h2>' + headline + '</h2>'
      + (sub.length ? '<p>' + sub.join(' · ') + '</p>' : '') + remind
      + (over ? '<p class="mp-today__warn" role="alert">Tổng thời gian đang vượt thời gian rảnh. Giảm nhịp ở Cài đặt của một lộ trình, hoặc tạm dừng bớt.</p>' : '')
      + '</div>' + (cta ? '<div class="mp-today__cta">' + cta + '</div>' : '') + '</section>';
  }

  /** The whole dashboard body: today, the paths being studied (max 2), then paused and finished ones. */
  function dashboardHtml(view) {
    var list = paths(view);
    var max = num(view && view.maxActive) || 2;
    var studying = active(view);
    var rest = list.filter(function (p) { return p && p.status === 'PAUSED'; })
      .concat(list.filter(function (p) { return p && p.status === 'FINISHED'; }));
    var add = studying.length < max
      ? '<a class="mp-add" href="/survey?edit=goal" data-mp-add title="Thêm lộ trình JLPT hoặc giao tiếp (Du lịch, Đời sống, Công việc)">'
        + '<span class="mp-add__plus" aria-hidden="true">+</span>Thêm lộ trình</a>'
      : '';
    return todayHtml(view)
      + '<section class="mp-list" aria-label="Đang học"><div class="mp-list__head"><h2>Đang học <span class="mp-count">'
      + studying.length + '/' + max + '</span></h2>'
      + '<span class="mp-list__hint">Học cùng lúc tối đa ' + max + ' lộ trình.</span>' + add + '</div>'
      + '<div class="mp-grid">' + studying.map(function (p) { return cardHtml(p, studying.length === 1); }).join('') + '</div></section>'
      + (rest.length ? '<section class="mp-list" aria-label="Tạm dừng và đã xong"><div class="mp-list__head"><h2>Tạm dừng và đã xong</h2>'
        + '<span class="mp-list__hint">Tiến độ được giữ nguyên.</span></div>'
        + '<ul class="mp-rows">' + rest.map(rowHtml).join('') + '</ul></section>' : '')
      + (add ? '' : '<p class="mp-full">Đang học đủ ' + max + ' lộ trình. Muốn thêm lộ trình mới, hãy tạm dừng một lộ trình trước.</p>');
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
