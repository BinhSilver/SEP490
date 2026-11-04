/* [JLAS-W0108] jpath-done.js — the "Xong hôm nay" screen of the JLPT path (/learning/jlpt/done).
 *
 * Data: GET /api/learning/jlpt/today/summary (JpathDaySummaryView: kind, allDone, level, newLearned, reviewed,
 * accuracyPercent, cpToday, streak, levelPercentBefore/After, wrongItems).
 *
 * window.JpathDone:
 *   summaryHtml(summary)   allDone → "Xong phần hôm nay!" (SKILL: "Xong ngày kỹ năng!") with counts, accuracy, CP,
 *                          streak, level delta and today's wrong items (.jp-celebrate, ≤ 1.2 s, off under reduced
 *                          motion); "Học thêm 5 mục" per tab (data-extra-tab), "Luyện viết hôm nay"
 *                          (data-jpath-writing {today:'VOCAB'}), "In bảng" (data-jpath-print {scope:'TODAY',
 *                          tab:'VOCAB'}), <div data-jpath-extra-links>. Not done → "Phần hôm nay chưa xong" + /learning.
 *   extraRequest(tab)      → {tab, mode:'EXTRA', count:5} for POST /sessions
 *   mount(el, env)         loads the summary into el (#jpathDoneRoot on the page)
 * Needs jpath-api.js and jpath-display.js.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathDone = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var EXTRA_COUNT = 5;
  var EXTRA_TABS = [['VOCAB', 'Từ vựng'], ['KANJI', 'Kanji'], ['GRAMMAR', 'Ngữ pháp']];
  var MSG_FAILED = 'Chưa tải được kết quả hôm nay. Thử lại sau.';
  var MSG_EXTRA_FAILED = 'Chưa mở được lượt học thêm. Thử lại sau.';

  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function num(v) { var n = Number(v); return isFinite(n) ? n : 0; }
  function dataJson(obj) { return esc(JSON.stringify(obj)); }

  function extraRequest(tab) { return { tab: tab, mode: 'EXTRA', count: EXTRA_COUNT }; }

  function summaryHtml(summary) {
    var s = summary || {};
    if (!s.allDone) {
      return '<section class="jp-done jp-card" aria-labelledby="jp-done-title"><h1 class="jp-done__title" id="jp-done-title">'
        + 'Phần hôm nay chưa xong</h1><p>Học nốt các phần còn lại trong lộ trình để hoàn thành ngày hôm nay.</p>'
        + '<a class="jp-btn jp-btn--primary" href="/learning">Về lộ trình học</a></section>';
    }
    var before = num(s.levelPercentBefore);
    var after = num(s.levelPercentAfter);
    var delta = after - before;
    var html = '<section class="jp-done jp-card" aria-labelledby="jp-done-title">'
      + '<h1 class="jp-done__title" id="jp-done-title">' + (s.kind === 'SKILL' ? 'Xong ngày kỹ năng' : 'Xong phần hôm nay') + '</h1>'
      + '<p class="jp-done__counts">' + esc(num(s.newLearned)) + ' mục đã học · ' + esc(num(s.reviewed)) + ' thẻ đã ôn</p>'
      + '<a class="jp-btn jp-btn--primary" href="/learning">Về lộ trình học</a>'
      + '<details class="jp-done__more"><summary>Xem thêm kết quả và lựa chọn</summary><ul class="jp-done__stats">';
    if (s.accuracyPercent !== null && s.accuracyPercent !== undefined) {
      html += '<li class="jp-done__stat">Đúng ' + esc(num(s.accuracyPercent)) + '%</li>';
    }
    html += '<li class="jp-done__stat jp-done__cp">+' + esc(num(s.cpToday)) + ' CP</li>'
      + '<li class="jp-done__stat">Chuỗi ' + esc(num(s.streak)) + ' ngày</li></ul>'
      + '<p class="jp-done__level">Tiến độ ' + esc(s.level || '') + ': ' + before + '% → ' + after + '%'
      + (delta > 0 ? ' (+' + delta + '%)' : '') + '</p>';
    var wrong = (s.wrongItems || []).map(function (w) { return w && (w.expression || w.code); }).filter(Boolean);
    if (wrong.length) html += '<p class="jp-done__wrong">Mục sai hôm nay: <span lang="ja">' + esc(wrong.join(', ')) + '</span></p>';
    // A skill day has no new learning, so it offers no extra rounds.
    if (s.kind !== 'SKILL') {
      html += '<div class="jp-done__extra" role="group" aria-label="Học thêm 5 mục">'
        + EXTRA_TABS.map(function (t) {
          return '<button type="button" class="jp-chip jp-done__chip" data-extra-tab="' + t[0] + '">Học thêm 5 mục · ' + esc(t[1]) + '</button>';
        }).join('') + '</div><p class="jp-done__hint">Học vượt nhiều sẽ làm tăng số thẻ ôn ngày mai.</p>';
    }
    html += '<div class="jp-done__actions">'
      + '<button type="button" class="jp-btn" data-jpath-writing="' + dataJson({ today: 'VOCAB' }) + '">Luyện viết hôm nay</button>'
      + '<button type="button" class="jp-btn" data-jpath-print="' + dataJson({ scope: 'TODAY', tab: 'VOCAB' }) + '">In bảng</button>'
      + '</div>'
      + '<div data-jpath-extra-links></div>'
      + '<p class="jp-status" role="status" data-part="status"></p>';
    return html + '</details></section>';
  }

  function closestAttr(target, attr, el) {
    var n = target;
    while (n && n !== el) {
      if (n.getAttribute && n.getAttribute(attr) !== null && n.getAttribute(attr) !== undefined) return n;
      n = n.parentNode;
    }
    return null;
  }

  function mount(el, options) {
    var o = options || {};
    var w = root || {};
    var api = o.api || w.JpathApi;
    var doc = o.document || w.document;
    var navigate = o.navigate || function (url) { w.location.href = url; };
    var D = w.JpathDisplay;
    var busy = false;

    function setChips(disabled, first) {
      var list = el.querySelectorAll ? el.querySelectorAll('[data-extra-tab]') : null;
      var chips = list ? Array.prototype.slice.call(list) : [];
      if (first && chips.indexOf(first) < 0) chips.push(first);
      chips.forEach(function (c) { c.disabled = disabled; });
    }

    function status(msg) {
      var p = el.querySelector && el.querySelector('[data-part="status"]');
      if (p) p.textContent = msg;
    }

    function load() {
      el.setAttribute('aria-busy', 'true');
      el.innerHTML = '<p class="jp-status" role="status">Đang tải…</p>';
      return api.get(BASE + '/today/summary').then(function (summary) {
        el.innerHTML = summaryHtml(summary);
        el.setAttribute('aria-busy', 'false');
        if (D && doc) D.rendered(el, doc);
      }, function () {
        el.setAttribute('aria-busy', 'false');
        el.innerHTML = '<div class="jp-done jp-card" role="alert"><p>' + esc(MSG_FAILED) + '</p>'
          + '<button type="button" class="jp-btn" data-action="reload">Thử lại</button></div>';
      });
    }

    el.addEventListener('click', function (ev) {
      var n = closestAttr(ev.target, 'data-extra-tab', el);
      if (n) {
        if (busy) return;
        busy = true;
        setChips(true, n);
        api.send('POST', BASE + '/sessions', extraRequest(n.getAttribute('data-extra-tab'))).then(function (view) {
          var id = view && view.sessionId;
          if (id === null || id === undefined) throw new Error('no session');
          navigate('/learning/jlpt/study?session=' + encodeURIComponent(id));
        }).catch(function (err) {
          busy = false;
          setChips(false, n);
          status((err && err.message) || MSG_EXTRA_FAILED);
        });
        return;
      }
      var a = closestAttr(ev.target, 'data-action', el);
      if (a && a.getAttribute('data-action') === 'reload') load();
    });
    return load();
  }

  function mountPage() {
    var el = root && root.document && root.document.getElementById('jpathDoneRoot');
    return el ? mount(el, {}) : null;
  }

  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', mountPage);
    else mountPage();
  }

  return { summaryHtml: summaryHtml, extraRequest: extraRequest, mount: mount, mountPage: mountPage };
});
