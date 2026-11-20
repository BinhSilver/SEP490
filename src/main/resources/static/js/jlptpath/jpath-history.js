/* [JLAS-W0108] jpath-history.js — history page of the JLPT path (plan Task 67).
 *
 * window.JpathHistory = { rowMarkup(day), redoBody(token, tab), mount() }
 *   - GET /api/learning/jlpt/history?from=&to= (14 or 30 days back from today); a row opens the day below the range
 *     buttons; ?day=YYYY-MM-DD opens GET /history/{day} on load (links from the stats heatmap).
 *   - "Làm lại" on a redoable check: POST /checks redoBody(token, tab) → /learning/jlpt/check/{new token}.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathHistory = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var DAY_KIND = { STUDY: 'Ngày học', SKILL: 'Ngày kỹ năng', REST: 'Ngày nghỉ', REVIEW_ONLY: 'Ngày ôn' };
  var KIND_MARK = { REST: 'moon', SKILL: 'book-open' };
  var TAB = { KANA: 'Chữ cái', VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', SKILL: 'Đọc · Nghe' };
  var CHECK = {
    MICRO_CHECK: 'Micro-check', GROUP_EXERCISE: 'Bài tập nhóm', SKIP_ONE: 'Kiểm 1 câu', TOPIC_KNOWN: 'Kiểm chủ đề',
    FREE_QUIZ: 'Quiz tự do', CONFUSABLE: 'Phân biệt dễ nhầm', KANA_ROW: 'Kiểm hàng chữ', GRAMMAR_PRACTICE: 'Luyện ngay',
    GRAMMAR_EXTRA: 'Luyện thêm', GRAMMAR_REVIEW: 'Ôn ngữ pháp', SKILL_LESSON: 'Bài chiến thuật',
    SKILL_PRACTICE: 'Luyện kỹ năng', SKILL_DICTATION: 'Chép chính tả', SKILL_SECTION: 'Luyện phần thi',
    WEEK_KNOWN: 'Kiểm bỏ qua tuần'
  };
  var WEEKDAY = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function icon(name) { return root && root.JpathNav && root.JpathNav.iconHtml ? root.JpathNav.iconHtml(name)
    : '<svg class="jp-i" aria-hidden="true"><use href="/images/icons/jpath-icons.svg#i-' + name + '"></use></svg>'; }

  /** "T3 · 03/11" for an ISO day; the raw text when it is not one. */
  function dayLabel(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    if (!m) return String(iso || '');
    var wd = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay();
    return WEEKDAY[wd] + ' · ' + m[3] + '/' + m[2];
  }

  function percent(v) {
    return v === null || v === undefined || isNaN(Number(v)) ? '—' : Math.round(Number(v)) + '%';
  }

  function checkMarkup(c) {
    var name = (CHECK[c.kind] || c.kind || '') + (c.tab && TAB[c.tab] ? ' · ' + TAB[c.tab] : '');
    var redo = c.redoable && c.token
      ? ' <button type="button" class="jp-side-btn jp-side-btn--small" data-redo="' + esc(c.token) + '" data-tab="'
        + esc(c.tab || '') + '">Làm lại</button>' : '';
    return '<li class="jp-side-check"><span>' + esc(name) + '</span> <strong>' + esc(percent(c.scorePercent))
      + '</strong>' + (c.passed === true ? ' <span class="jp-side-ok">Đạt</span>'
        : c.passed === false ? ' <span class="jp-side-miss">Chưa đạt</span>' : '') + redo + '</li>';
  }

  /** One day row: kind, new / reviewed counts, done flag, checks with their scores. */
  function rowMarkup(d) {
    if (!d) return '';
    var checks = Array.isArray(d.checks) ? d.checks : [];
    var mark = KIND_MARK[d.kind] ? icon(KIND_MARK[d.kind]) + ' ' : '';
    return '<li class="jp-side-day"><button type="button" class="jp-side-day__btn" data-day="' + esc(d.day) + '">'
      + '<span class="jp-side-day__date">' + esc(dayLabel(d.day)) + '</span>'
      + '<span class="jp-side-day__kind">' + mark + esc(DAY_KIND[d.kind] || 'Ngoài lộ trình') + (d.done ? ' ' + icon('circle-check') : '')
      + '</span><span class="jp-side-day__nums">' + esc(Number(d.newLearned) || 0) + ' mới · '
      + esc(Number(d.reviewed) || 0) + ' ôn' + (checks.length ? ' · ' + checks.length + ' bài kiểm' : '') + '</span>'
      + '</button></li>';
  }

  function detailMarkup(d) {
    var checks = Array.isArray(d.checks) ? d.checks : [];
    return '<h2>' + esc(dayLabel(d.day)) + ' — ' + esc(DAY_KIND[d.kind] || 'Ngoài lộ trình') + '</h2>'
      + '<p>' + esc(Number(d.newLearned) || 0) + ' mục mới · ' + esc(Number(d.reviewed) || 0) + ' lượt ôn'
      + (d.done ? ' · đã xong ngày' : '') + (Number(d.cpAwarded) > 0 ? ' · +' + esc(d.cpAwarded) + ' CP' : '') + '</p>'
      + '<p><a class="jp-btn jp-btn--primary" href="/learning/jlpt/day?d=' + esc(d.day) + '">Xem chi tiết ngày</a></p>'
      + (checks.length ? '<ul class="jp-side-checks">' + checks.map(checkMarkup).join('') + '</ul>'
        : '<p class="jp-side__note">Ngày này chưa có bài kiểm.</p>');
  }

  /** Body of POST /checks that redoes a submitted check as a new free quiz. */
  function redoBody(token, tab) {
    return { kind: 'FREE_QUIZ', tab: tab || null, redoOf: token };
  }

  function isoDay(date) {
    return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-'
      + String(date.getDate()).padStart(2, '0');
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-history]');
    if (mounted || !main || !root.JpathApi) return;
    mounted = true;
    var api = root.JpathApi;
    var status = main.querySelector('[data-role="status"]');
    var list = main.querySelector('[data-role="days"]');
    var detail = main.querySelector('[data-role="detail"]');
    var search = main.querySelector('[data-role="search"]');
    var pager = main.querySelector('[data-role="pager"]');
    var days = [];
    var page = 0;

    // I12: same fold as the server's normalized_* columns (NFKC, katakana to hiragana), so a full-width or katakana query still matches
    function foldSearch(v) {
      var s = String(v == null ? '' : v);
      if (typeof s.normalize === 'function') s = s.normalize('NFKC');
      return s.trim().toLocaleLowerCase().replace(/[ァ-ヶ]/g, function (c) { return String.fromCharCode(c.charCodeAt(0) - 0x60); });
    }

    function draw() {
      var term = foldSearch(search.value);
      var filtered = days.filter(function (d) {
        return !term || [d.day, dayLabel(d.day), DAY_KIND[d.kind]].some(function (v) {
          return foldSearch(v).indexOf(term) >= 0;
        });
      });
      var pages = Math.max(1, Math.ceil(filtered.length / 20));
      var summary = main.querySelector('[data-role="summary"]');
      if (summary) summary.textContent = filtered.length + ' ngày trong danh sách';
      page = Math.min(page, pages - 1);
      list.innerHTML = filtered.slice(page * 20, page * 20 + 20).map(rowMarkup).join('');
      pager.hidden = pages <= 1;
      pager.querySelector('[data-page="prev"]').disabled = page === 0;
      pager.querySelector('[data-page="next"]').disabled = page + 1 >= pages;
      pager.querySelector('[data-role="page"]').textContent = 'Trang ' + (page + 1) + '/' + pages;
      status.textContent = filtered.length ? '' : (days.length ? 'Không tìm thấy ngày phù hợp.' : 'Chưa có ngày học nào trong khoảng này.');
    }

    function show(d) {
      detail.innerHTML = detailMarkup(d);
      detail.hidden = false;
      if (typeof detail.scrollIntoView === 'function') detail.scrollIntoView({ block: 'nearest' });
    }

    var serverToday = null;

    function load(range) {
      main.querySelectorAll('[data-range]').forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-range') === String(range)));
      });
      status.textContent = 'Đang tải…';
      // E2E-002: the range ends at the learner's study day by the app clock. Until the first answer tells it, ask
      // without dates (the server ends at its today) and ask again when that default is not this range.
      var query = '';
      if (serverToday) {
        var p = serverToday.split('-');
        var to = new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]));
        var from = new Date(to.getFullYear(), to.getMonth(), to.getDate() - (range - 1));
        query = '?from=' + isoDay(from) + '&to=' + isoDay(to);
      }
      return api.get(BASE + '/history' + query).then(function (h) {
        if (!query && h && h.today) {
          serverToday = String(h.today);
          return load(range);
        }
        days = (h && Array.isArray(h.days)) ? h.days : [];
        page = 0;
        draw();
      }, function (err) { status.textContent = (err && (err.code || err.status) && err.message) || 'Không tải được lịch sử.'; });
    }

    main.addEventListener('click', function (e) {
      var t = e.target;
      var range = t.closest('[data-range]');
      if (range) { load(Number(range.getAttribute('data-range'))); return; }
      var pg = t.closest('[data-page]');
      if (pg) { page += pg.getAttribute('data-page') === 'next' ? 1 : -1; draw(); return; }
      var redo = t.closest('[data-redo]');
      if (redo) {
        redo.disabled = true;
        api.send('POST', BASE + '/checks', redoBody(redo.getAttribute('data-redo'), redo.getAttribute('data-tab') || null))
          .then(function (check) {
            if (check && check.token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(check.token));
            else redo.disabled = false;
          }, function (err) {
            redo.disabled = false;
            status.textContent = (err && (err.code || err.status) && err.message) || 'Chưa làm lại được bài này.';
          });
        return;
      }
      var row = t.closest('[data-day]');
      if (row) {
        var day = row.getAttribute('data-day');
        var found = days.filter(function (d) { return d.day === day; })[0];
        if (found) show(found);
      }
    });
    search.addEventListener('input', function () { page = 0; draw(); });

    var wanted = /[?&]day=(\d{4}-\d{2}-\d{2})/.exec(String(root.location && root.location.search || ''));
    load(14).then(function () {
      if (!wanted) return;
      api.get(BASE + '/history/' + wanted[1]).then(show, function (err) {
        status.textContent = (err && (err.code || err.status) && err.message) || 'Không tải được ngày này.';
      });
    });
  }

  return { rowMarkup: rowMarkup, detailMarkup: detailMarkup, redoBody: redoBody, dayLabel: dayLabel, mount: mount };
});
