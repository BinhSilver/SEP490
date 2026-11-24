/* [JLAS-W0108] jpath-day.js — the detail page of one study day (/learning/jlpt/day?d=YYYY-MM-DD), owner 05/11.
 *
 * Reads GET /api/learning/jlpt/history/{day} (JpathDayDetailView: kind, done, cpAwarded, items, checks, wrongItems)
 * and shows, per tab, the new items and the reviewed cards of that day (wrong ones in red), the best score of each
 * tab's checks, and actions: practise writing an item (data-jpath-writing), the notebook for the wrong items,
 * Hôm trước / Hôm sau.
 *
 * window.JpathDay = { dayHtml(detail, iso, todayIso), shift(iso, n), weekDays(iso), mount() }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathDay = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount);
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var KIND = { STUDY: 'Ngày học', SKILL: 'Ngày kỹ năng', REST: 'Ngày nghỉ', REVIEW_ONLY: 'Ngày ôn' };
  var TABS = [['KANA', 'Chữ cái', 'chữ'], ['VOCAB', 'Từ vựng', 'từ'], ['KANJI', 'Kanji', 'chữ'], ['GRAMMAR', 'Ngữ pháp', 'mẫu']];
  var WEEKDAY = ['Chủ nhật', 'Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy'];
  var WEEKDAY_SHORT = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
  var WRITABLE = { VOCAB: 1, KANJI: 1, KANA: 1 };
  var KITSU_BASE = '/images/mascots/fox/';
  var SVG_PRINT = '<svg class="jp-ic" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9V3h12v6"/><rect x="6" y="14" width="12" height="7" rx="1"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/></svg>';
  var SVG_PEN = '<svg class="jp-ic" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19l7-7 3 3-7 7-3-3z"/><path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/><path d="M2 2l7.5 7.5"/></svg>';
  var SVG_REFRESH = '<svg class="jp-ic" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 0 0-15-6.7L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 15 6.7L21 16"/><path d="M16 16h5v5"/></svg>';
  var SVG_CHECK = '<svg class="jp-dayd__node-mark" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8.2L6.6 11.3L12.5 5.2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  var SVG_LOCK = '<svg class="jp-dayd__node-lock" viewBox="0 0 16 16" aria-hidden="true"><rect x="3.5" y="7.5" width="9" height="6.5" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M5.5 7.5V5.6a2.5 2.5 0 0 1 5 0v1.9" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
  var SVG_SCENERY = '<svg class="jp-dayd__scenery" viewBox="0 0 800 140" preserveAspectRatio="none" aria-hidden="true">'
    + '<defs>'
    + '<linearGradient id="jpDaySky" x1="0" y1="0" x2="1" y2="1">'
    + '<stop offset="0%" stop-color="#e0f2fe" stop-opacity="0.78"/>'
    + '<stop offset="52%" stop-color="#fef3c7" stop-opacity="0.65"/>'
    + '<stop offset="100%" stop-color="#fce7f3" stop-opacity="0.72"/>'
    + '</linearGradient>'
    + '<linearGradient id="jpDayHillBack" x1="0" y1="0" x2="0" y2="1">'
    + '<stop offset="0%" stop-color="#c4b5fd" stop-opacity="0.35"/>'
    + '<stop offset="100%" stop-color="#ddd6fe" stop-opacity="0.12"/>'
    + '</linearGradient>'
    + '<linearGradient id="jpDayHillFront" x1="0" y1="0" x2="0" y2="1">'
    + '<stop offset="0%" stop-color="#86efac" stop-opacity="0.38"/>'
    + '<stop offset="100%" stop-color="#bbf7d0" stop-opacity="0.15"/>'
    + '</linearGradient>'
    + '</defs>'
    + '<rect width="800" height="140" fill="url(#jpDaySky)"/>'
    + '<circle cx="690" cy="36" r="24" fill="#fde68a" opacity="0.65"/>'
    + '<g fill="#ffffff" opacity="0.75">'
    + '<path d="M85 38c0-9 7-16 16-16 6 0 12 3 14 9 3-2 6-3 10-3 9 0 16 7 16 16H85z"/>'
    + '<path d="M460 28c0-8 6-14 14-14 5 0 10 3 12 7 2-1 5-2 8-2 8 0 14 6 14 14h-48z"/>'
    + '</g>'
    + '<path d="M0 108 Q180 68 380 98 T800 86 L800 140 L0 140 Z" fill="url(#jpDayHillBack)"/>'
    + '<path d="M0 118 Q240 84 490 112 T800 102 L800 140 L0 140 Z" fill="url(#jpDayHillFront)"/>'
    + '<g fill="#f472b6" opacity="0.55">'
    + '<path d="M180 24c4-3 9 0 7 5-2 4-7 5-10 2-2-3 0-5 3-7z"/>'
    + '<path d="M310 42c3-2 7 0 6 4-1 3-6 4-8 1-2-2 0-4 2-5z"/>'
    + '<path d="M610 26c4-3 8 0 7 4-2 4-6 5-9 2-2-2 0-4 2-6z"/>'
    + '<path d="M745 52c3-2 7 0 6 4-1 3-5 4-8 1-1-2 0-4 2-5z"/>'
    + '</g>'
    + '</svg>';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function list(v) { return Array.isArray(v) ? v : []; }

  function parse(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    return m ? new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])) : null;
  }
  function iso(d) { return d.toISOString().slice(0, 10); }
  function shift(day, n) {
    var d = parse(day);
    if (!d) return day;
    d.setUTCDate(d.getUTCDate() + n);
    return iso(d);
  }
  function shortDate(day) {
    var d = parse(day);
    if (!d) return esc(day);
    return String(d.getUTCDate()).padStart(2, '0') + '/' + String(d.getUTCMonth() + 1).padStart(2, '0');
  }
  function title(day) {
    var d = parse(day);
    if (!d) return esc(day);
    return WEEKDAY[d.getUTCDay()] + ' ' + shortDate(day);
  }

  function weekDays(day) {
    var d = parse(day);
    if (!d) return [];
    var wd = d.getUTCDay();
    var offset = (wd + 6) % 7; // Monday = 0 .. Sunday = 6
    var mon = shift(day, -offset);
    var out = [];
    for (var i = 0; i < 7; i++) out.push(shift(mon, i));
    return out;
  }

  function weekStripHtml(day, today, isDone, weekStatus) {
    var days = weekDays(day);
    if (!days.length) return '';
    var cells = days.map(function (di, idx) {
      var dt = parse(di);
      var wdShort = dt ? WEEKDAY_SHORT[dt.getUTCDay()] : '';
      var dm = shortDate(di);
      var isCurrent = di === day;
      var isToday = di === today;
      var isFuture = today && di > today;
      // AI 1 #15: a past day is ticked only when the history says something was studied that day
      var known = weekStatus && Object.prototype.hasOwnProperty.call(weekStatus, di) ? !!weekStatus[di] : null;
      var isPastDone = isCurrent ? !!isDone : (known !== null ? known : false);
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
      var stepCls = 'jp-dayd__step' + (isFuture ? ' is-future' : ' is-past') + (isCurrent ? ' is-current' : '') + (isPastDone ? ' is-done' : '');
      var delayAttr = ' style="--jp-step-idx:' + idx + '"';
      if (isFuture) {
        return '<li class="' + stepCls + '"' + delayAttr + '><span class="' + cls + '" aria-disabled="true" title="'
          + esc(title(di) + ' (Chưa tới)') + '">' + inner + '</span></li>';
      }
      return '<li class="' + stepCls + '"' + delayAttr + '><a class="' + cls + '" href="?d='
        + esc(di) + '"' + (isCurrent ? ' aria-current="date"' : '') + ' title="' + esc(title(di)) + '">'
        + inner + '</a></li>';
    });
    return '<div class="jp-dayd__banner">'
      + SVG_SCENERY
      + '<nav class="jp-dayd__week" aria-label="Các ngày trong tuần"><ol class="jp-dayd__week-list">'
      + cells.join('') + '</ol></nav>'
      + '</div>';
  }

  function mascotHtml(done, hasItems, wrongCount) {
    var img = !hasItems ? 'kitsu-resting-30.webp' : (done ? 'kitsu-celebrate-30.webp' : 'kitsu-encouraging.webp');
    var speech = !hasItems
      ? 'Ngày này chưa có bài học nào.'
      : (done ? 'Ngày này đã hoàn thành, giỏi lắm!' : 'Cùng ôn lại các mục trong ngày nhé!');
    var sub = wrongCount > 0
      ? '<span class="jp-dayd__mascot-sub">Còn ' + esc(wrongCount) + ' mục cần ôn lại, cố lên nhé!</span>'
      : '';
    return '<aside class="jp-dayd__mascot" aria-label="Bạn đồng hành">'
      + '<img class="jp-dayd__mascot-img" src="' + KITSU_BASE + img + '" width="68" height="68" alt="" loading="lazy" />'
      + '<div class="jp-dayd__speech">'
      + '<p class="jp-dayd__speech-main">' + esc(speech) + '</p>'
      + sub
      + '</div>'
      + '</aside>';
  }

  var wrongCodes = {};
  function isWrong(it) {
    return !!wrongCodes[it.itemType + ':' + it.code] || it.correct === false || it.lastRating === 'FORGOT';
  }

  function itemHtml(it) {
    var wrong = isWrong(it);
    var cls = 'jp-dayd__item' + (wrong ? ' is-wrong' : '');
    var body = '<span lang="ja" class="jp-dayd__ja">' + esc(it.label || it.code) + '</span>'
      + (it.reading && it.reading !== it.label ? '<span lang="ja" class="jp-dayd__rd">' + esc(it.reading) + '</span>' : '')
      + (wrong ? '<span class="jp-dayd__wrong-dot" aria-label="Cần ôn lại" title="Mục sai / chưa nhớ"></span>' : '');
    if (WRITABLE[it.itemType]) {
      return '<li><button type="button" class="' + cls + '" title="Luyện viết" data-jpath-writing="'
        + esc(JSON.stringify({ itemType: it.itemType, code: it.code })) + '">' + body + '</button></li>';
    }
    return '<li><span class="' + cls + '">' + body + '</span></li>';
  }

  function bestScores(checks) {
    var best = {};
    list(checks).forEach(function (c) {
      if (!c || c.scorePercent === null || c.scorePercent === undefined || !c.tab) return;
      var s = Math.round(Number(c.scorePercent));
      if (!(c.tab in best) || s > best[c.tab]) best[c.tab] = s;
    });
    return best;
  }

  function dayHtml(d, day, today) {
    var x = d || {};
    var items = list(x.items);
    var wrongAll = list(x.wrongItems);
    wrongCodes = {};
    wrongAll.forEach(function (w) { if (w) wrongCodes[w.itemType + ':' + w.code] = true; });
    var strip = weekStripHtml(day, today, !!x.done, x.weekStatus);
    var nav = '<nav class="jp-dayd__nav" aria-label="Ngày khác"><a class="jp-btn" href="?d=' + esc(shift(day, -1)) + '">‹ Hôm trước</a>'
      + (day < today ? '<a class="jp-btn" href="?d=' + esc(shift(day, 1)) + '">Hôm sau ›</a>' : '') + '</nav>';
    var html = strip + '<header class="jp-dayd__head"><h1 class="jp-page-head__title">' + title(day) + ' · '
      + esc(KIND[x.kind] || 'Ngoài lộ trình') + '</h1>' + nav + '</header>';
    var reviewed = items.filter(function (i) { return i.lastAction === 'REVIEW_RATE'; });
    // F171: the badge counts what the sections show — one row per item, only the study tabs (it said 12 where the
    // history and the sections listed 10, because an item studied twice or a non-tab row counted again)
    var shownTypes = {};
    TABS.forEach(function (t) { shownTypes[t[0]] = true; });
    var seenFresh = {};
    var fresh = items.filter(function (i) {
      if (i.lastAction === 'REVIEW_RATE' || !shownTypes[i.itemType]) return false;
      var k = i.itemType + ':' + i.code;
      if (seenFresh[k]) return false;
      seenFresh[k] = true;
      return true;
    });
    var hasType = function (type) { return items.some(function (i) { return i.itemType === type; }); };
    // Owner 05/11: same look as the study parts; quiz results behind their own button
    var best = bestScores(x.checks);
    var scores = TABS.filter(function (t) { return t[0] in best; }).map(function (t) {
      return '<li><span>' + esc(t[1]) + ':</span> <b>' + best[t[0]] + '%</b></li>';
    });
    var statusText = x.done ? 'Đã xong ngày' : (items.length ? 'Chưa xong ngày' : 'Ngày này chưa học');
    var statusCls = x.done ? 'jp-dayd__badge jp-dayd__badge--done' : (items.length ? 'jp-dayd__badge jp-dayd__badge--pending' : 'jp-dayd__badge');
    var cpBadge = Number(x.cpAwarded) > 0
      ? ' <span class="jp-dayd__points-sep">·</span> <span class="jp-dayd__badge jp-dayd__badge--cp">+' + esc(x.cpAwarded) + ' CP</span>'
      : '';
    html += '<div class="jp-dayd__hero-row">'
      + '<div class="jp-dayd__hero-info">'
      + '<p class="jp-dayd__sum"><span class="' + statusCls + '">' + (x.done ? SVG_CHECK + ' ' : '') + statusText + '</span>' + cpBadge + '</p>'
      + (scores.length ? '<details class="jp-dayd__quiz"><summary class="jp-btn">Xem kết quả kiểm tra</summary><ul>'
        + scores.join('') + '</ul><a class="jp-link-btn" href="/learning/jlpt/history?day=' + esc(day)
        + '">Làm lại một bài kiểm tra</a></details>' : '')
      + '</div>'
      + mascotHtml(!!x.done, items.length > 0, wrongAll.length)
      + '</div>';
    if (!items.length) {
      return html + '<p class="jp-side__note">Không có mục nào được học hay ôn trong ngày này.</p>'
        + '<a class="jp-btn jp-btn--primary" href="/learning">Về lộ trình</a>';
    }
    TABS.forEach(function (t) {
      var mine = fresh.filter(function (i) { return i.itemType === t[0]; });
      if (!mine.length) return;
      var wrong = mine.filter(isWrong).length;
      html += '<section class="jp-dayd__sec"><h2 class="jp-dayd__h"><span>' + esc(t[1]) + '</span><span class="jp-dayd__meta"> · '
        + mine.length + ' ' + t[2] + ' mới</span>'
        + (wrong ? ' <span class="jp-dayd__bad">· sai ' + wrong + '</span>' : '') + '</h2><ul class="jp-dayd__list jp-parts__items">'
        + mine.map(itemHtml).join('') + '</ul></section>';
    });
    if (reviewed.length) {
      var forgot = reviewed.filter(isWrong).length;
      html += '<section class="jp-dayd__sec"><h2 class="jp-dayd__h"><span>Ôn thẻ</span><span class="jp-dayd__meta"> · ' + reviewed.length + ' thẻ · '
        + (reviewed.length - forgot) + ' nhớ, ' + forgot + ' quên</span></h2><ul class="jp-dayd__list jp-parts__items">'
        + reviewed.map(itemHtml).join('') + '</ul></section>';
    }
    html += '<div class="jp-dayd__actions">'
      + (wrongAll.length ? '<a class="jp-btn jp-btn--primary" href="/learning/jlpt/notebook">' + SVG_REFRESH + '<span>Ôn mục sai trong Sổ tay</span></a>' : '')
      // F171: a kana-only day has no kanji to write or print
      + (day === today && hasType('KANJI') ? '<button type="button" class="jp-btn" data-jpath-writing="' + esc(JSON.stringify({ today: 'KANJI' }))
        + '">' + SVG_PEN + '<span>Luyện viết kanji hôm nay</span></button>' : '')
      + (hasType('KANJI') ? '<button type="button" class="jp-btn" data-jpath-print="' + esc(JSON.stringify({ scope: 'DAY', tab: 'KANJI', day: day }))
      + '">' + SVG_PRINT + '<span>In bảng kanji ngày này</span></button>' : '')
      + (hasType('VOCAB') ? '<button type="button" class="jp-btn" data-jpath-print="' + esc(JSON.stringify({ scope: 'DAY', tab: 'VOCAB', day: day }))
      + '">' + SVG_PRINT + '<span>In bảng từ vựng ngày này</span></button>' : '')
      + '<a class="jp-link-btn jp-dayd__back" href="/learning">Về lộ trình</a></div>'
      + '<p class="jp-side__note">Bấm vào một từ hay chữ để luyện viết. Mục màu đỏ là mục sai hoặc chọn "Chưa nhớ".</p>';
    return html;
  }

  function mount() {
    var doc = root && root.document;
    var el = doc && doc.getElementById && doc.getElementById('jpathDayRoot');
    if (!el || !root.JpathApi) return;
    // E2E-002: "today" is the learner's study day by the app clock, sent with every day detail; the browser date is
    // only the first guess (a learner moved to a later day would otherwise be pushed back to the browser's today).
    var today = iso(new Date(Date.now() - new Date().getTimezoneOffset() * 60000));
    var m = /[?&]d=(\d{4}-\d{2}-\d{2})/.exec(String(root.location && root.location.search || ''));
    var day = m ? m[1] : today;
    // F185: a wrong (2026-02-31) or future ?d= errored forever or jumped silently; open today, say so, fix the URL
    var notice = '';
    if (m) {
      var probe = new Date(day + 'T00:00:00Z');
      if (isNaN(probe.getTime()) || probe.toISOString().slice(0, 10) !== day) { day = today; notice = 'Ngày trên đường dẫn không hợp lệ nên đang mở hôm nay.'; }
    } else if (/[?&]d=/.test(String(root.location && root.location.search || ''))) {
      notice = 'Ngày trên đường dẫn không hợp lệ nên đang mở hôm nay.';
    }
    function load() {
      if (el.setAttribute) el.setAttribute('aria-busy', 'true');
      var wDays = weekDays(day);
      var histQuery = wDays.length ? '?from=' + wDays[0] + '&to=' + wDays[wDays.length - 1] : '';
      var histPromise = root.JpathApi.get(BASE + '/history' + histQuery).then(function (h) {
        var map = {};
        if (h && Array.isArray(h.days)) {
          h.days.forEach(function (hd) {
            // Q8 (owner 24/11): a day is ticked only when its check was passed (done), not for a part studied halfway
            if (hd && hd.day) map[hd.day] = !!hd.done;
          });
        }
        return map;
      }, function () { return null; });
      Promise.all([root.JpathApi.get(BASE + '/history/' + day), histPromise]).then(function (res) {
        var d = res[0];
        var weekMap = res[1];
        if (d && d.today) today = String(d.today);
        if (day > today) { day = today; notice = 'Ngày đó chưa tới nên đang mở hôm nay.'; load(); return; }
        if (d && weekMap) d.weekStatus = weekMap;
        if (el.setAttribute) el.setAttribute('aria-busy', 'false');
        el.innerHTML = (notice ? '<p class="jp-day-notice" role="status">' + esc(notice) + '</p>' : '') + dayHtml(d, day, today);
        if (notice && root.history && root.history.replaceState) {
          try { root.history.replaceState(null, '', '?d=' + encodeURIComponent(day)); } catch (e) { /* ignore */ }
        }
        if (root.JpathPrint && root.JpathPrint.bind) root.JpathPrint.bind(el);
        if (root.JpathWriting && root.JpathWriting.bind) root.JpathWriting.bind(el);
      }, function () {
        if (el.setAttribute) el.setAttribute('aria-busy', 'false');
        el.innerHTML = '<p class="jp-alert" role="alert">Chưa tải được ngày này. <button type="button" class="jp-btn" data-day-retry>Thử lại</button></p>';
        var retry = el.querySelector && el.querySelector('[data-day-retry]');
        if (retry) retry.addEventListener('click', load);
      });
    }
    load();
  }

  return { dayHtml: dayHtml, shift: shift, weekDays: weekDays, mount: mount };
});