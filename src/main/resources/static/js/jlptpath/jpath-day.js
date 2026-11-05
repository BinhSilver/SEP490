/* [JLAS-W0108] jpath-day.js — the detail page of one study day (/learning/jlpt/day?d=YYYY-MM-DD), owner 05/11.
 *
 * Reads GET /api/learning/jlpt/history/{day} (JpathDayDetailView: kind, done, cpAwarded, items, checks, wrongItems)
 * and shows, per tab, the new items and the reviewed cards of that day (wrong ones in red), the best score of each
 * tab's checks, and actions: practise writing an item (data-jpath-writing), the notebook for the wrong items,
 * Hôm trước / Hôm sau.
 *
 * window.JpathDay = { dayHtml(detail, iso, todayIso), shift(iso, n), mount() }
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
  var WRITABLE = { VOCAB: 1, KANJI: 1, KANA: 1 };

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
  function title(day) {
    var d = parse(day);
    if (!d) return esc(day);
    return WEEKDAY[d.getUTCDay()] + ' ' + String(d.getUTCDate()).padStart(2, '0') + '/'
      + String(d.getUTCMonth() + 1).padStart(2, '0');
  }

  var wrongCodes = {};
  function isWrong(it) {
    return !!wrongCodes[it.itemType + ':' + it.code] || it.correct === false || it.lastRating === 'FORGOT';
  }

  function itemHtml(it) {
    var cls = 'jp-dayd__item' + (isWrong(it) ? ' is-wrong' : '');
    var body = '<span lang="ja" class="jp-dayd__ja">' + esc(it.label || it.code) + '</span>'
      + (it.reading && it.reading !== it.label ? '<span lang="ja" class="jp-dayd__rd">' + esc(it.reading) + '</span>' : '');
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
    wrongCodes = {};
    list(x.wrongItems).forEach(function (w) { if (w) wrongCodes[w.itemType + ':' + w.code] = true; });
    var nav = '<nav class="jp-dayd__nav" aria-label="Ngày khác"><a class="jp-btn" href="?d=' + esc(shift(day, -1)) + '">‹ Hôm trước</a>'
      + (day < today ? '<a class="jp-btn" href="?d=' + esc(shift(day, 1)) + '">Hôm sau ›</a>' : '') + '</nav>';
    var html = '<header class="jp-dayd__head"><h1 class="jp-page-head__title">' + title(day) + ' · '
      + esc(KIND[x.kind] || 'Ngoài lộ trình') + '</h1>' + nav + '</header>';
    var reviewed = items.filter(function (i) { return i.lastAction === 'REVIEW_RATE'; });
    var fresh = items.filter(function (i) { return i.lastAction !== 'REVIEW_RATE'; });
    var best = bestScores(x.checks);
    var scores = TABS.filter(function (t) { return t[0] in best; }).map(function (t) { return t[1] + ' ' + best[t[0]] + '%'; });
    html += '<p class="jp-dayd__sum">' + (x.done ? 'Đã xong ngày' : (items.length ? 'Chưa xong ngày' : 'Ngày này chưa học'))
      + (Number(x.cpAwarded) > 0 ? ' · +' + esc(x.cpAwarded) + ' CP' : '')
      + (scores.length ? ' · Kiểm tra: ' + esc(scores.join(', ')) : '') + '</p>';
    if (!items.length) {
      return html + '<p class="jp-side__note">Không có mục nào được học hay ôn trong ngày này.</p>'
        + '<a class="jp-btn jp-btn--primary" href="/learning">Về lộ trình</a>';
    }
    TABS.forEach(function (t) {
      var mine = fresh.filter(function (i) { return i.itemType === t[0]; });
      if (!mine.length) return;
      var wrong = mine.filter(isWrong).length;
      html += '<section class="jp-dayd__sec"><h2 class="jp-dayd__h">' + esc(t[1]) + ' · ' + mine.length + ' ' + t[2] + ' mới'
        + (wrong ? ' <span class="jp-dayd__bad">· sai ' + wrong + '</span>' : '') + '</h2><ul class="jp-dayd__list">'
        + mine.map(itemHtml).join('') + '</ul></section>';
    });
    if (reviewed.length) {
      var forgot = reviewed.filter(isWrong).length;
      html += '<section class="jp-dayd__sec"><h2 class="jp-dayd__h">Ôn thẻ · ' + reviewed.length + ' thẻ · '
        + (reviewed.length - forgot) + ' nhớ, ' + forgot + ' quên</h2><ul class="jp-dayd__list">'
        + reviewed.map(itemHtml).join('') + '</ul></section>';
    }
    var wrongAll = list(x.wrongItems);
    html += '<div class="jp-dayd__actions">'
      + (wrongAll.length ? '<a class="jp-btn jp-btn--primary" href="/learning/jlpt/notebook">Ôn lại ' + wrongAll.length + ' mục sai</a>' : '')
      + (day === today ? '<button type="button" class="jp-btn" data-jpath-writing="' + esc(JSON.stringify({ today: 'KANJI' }))
        + '">Luyện viết kanji hôm nay</button>' : '')
      + '<a class="jp-btn" href="/learning">Về lộ trình</a></div>'
      + '<p class="jp-side__note">Bấm vào một từ hay chữ để luyện viết. Mục màu đỏ là mục sai hoặc chọn "Chưa nhớ".</p>';
    return html;
  }

  function mount() {
    var doc = root && root.document;
    var el = doc && doc.getElementById && doc.getElementById('jpathDayRoot');
    if (!el || !root.JpathApi) return;
    var today = iso(new Date(Date.now() - new Date().getTimezoneOffset() * 60000));
    var m = /[?&]d=(\d{4}-\d{2}-\d{2})/.exec(String(root.location && root.location.search || ''));
    var day = m ? m[1] : today;
    if (day > today) day = today;
    root.JpathApi.get(BASE + '/history/' + day).then(function (d) {
      el.innerHTML = dayHtml(d, day, today);
    }, function () {
      el.innerHTML = '<p class="jp-alert" role="alert">Chưa tải được ngày này. Thử lại sau.</p>';
    });
  }

  return { dayHtml: dayHtml, shift: shift, mount: mount };
});
