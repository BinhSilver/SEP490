/* [JLAS-W0108] jpath-level-summary.js — the level summary page (Plan 3 Task 23).
 *
 * window.JpathLevelSummary = { summaryHtml(s), mount() }
 *   - GET /api/learning/jlpt/levels/{L}/summary: known / learned, micro-check accuracy, study days, the 10 weakest
 *     items, the mock score and the day it was passed; "Xem chứng nhận" once passed; "Học tiếp {next}" or "Cấp sau sắp
 *     có"; "Bạn đã đạt mục tiêu" (with a pointer to Cài đặt) when the target level is passed. The "Luyện thêm" strip
 *     below is drawn by jpath-extra-links.js.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathLevelSummary = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt/levels/';
  var TYPE = { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', KANA: 'Chữ cái' };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(x) { return Array.isArray(x) ? x : []; }

  function day(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
  }

  function tile(value, label) {
    return '<div class="jp-side-tile"><strong>' + esc(value) + '</strong><span>' + esc(label) + '</span></div>';
  }

  // owner 18/11: before the level test, one round of every verb form the level has taught (optional)
  function verbReview(level, passed) {
    var V = root && root.JpathVerbs;
    var forms = V && V.LEVEL_REVIEW[String(level || '').toUpperCase()];
    if (passed || !forms || !forms.length) return '';
    var back = root.location ? root.location.pathname + root.location.search : '/learning';
    return '<section class="jp-card jp-verbs" aria-label="Ôn chia động từ"><div class="jp-verbs__text">'
      + '<h2 class="jp-verbs__title">Ôn chia động từ trước bài kiểm tra cấp</h2>'
      + '<p class="jp-verbs__sub">Một vòng trộn mọi thể đã học ở ' + esc(level) + '. Không bắt buộc.</p></div>'
      + '<a class="jp-btn jp-btn--primary" href="' + esc(V.practiceUrl(level, forms, back, 'Tổng kết ' + level)) + '">Ôn các thể</a></section>';
  }

  function summaryHtml(s) {
    if (!s) return '';
    var lvl = esc(s.level);
    var passed = !!s.passedOn;
    var acc = s.accuracyPct == null ? '—' : Math.round(Number(s.accuracyPct)) + '%';
    var head = passed
      ? '<p class="jp-skills__ready">Bạn đã đậu ' + lvl + ' ngày ' + esc(day(s.passedOn))
        + (s.mockScore != null ? ' · Thi thử ' + esc(s.mockScore) + '%' : '') + '.</p>' : '';
    var next = '';
    if (s.targetReached) {
      next = '<p><strong>Bạn đã đạt mục tiêu.</strong> Muốn học tiếp, hãy đổi cấp mục tiêu trong '
        + '<a href="/learning/jlpt/settings">Cài đặt</a>.</p>';
    } else if (s.nextLevel) {
      next = '<a class="jp-btn jp-btn--primary" href="/learning?view=jlpt">Học tiếp ' + esc(s.nextLevel) + '</a>';
    } else if (s.nextLevelComing) {
      next = '<p class="jp-muted">Cấp sau sắp có.</p>';
    }
    var weak = list(s.weak).length ? '<details class="jp-side-card"><summary>10 mục hay quên</summary><ul class="jp-side-checks">'
      + list(s.weak).map(function (w) {
        return '<li>' + esc(TYPE[w.type] || w.type) + ' · <span lang="ja">' + esc(w.label || w.code) + '</span> · quên '
          + esc(w.forgetCount) + ' lần</li>';
      }).join('') + '</ul></details>' : '';
    return head
      + '<section class="jp-side-card"><h2>Kết quả ' + lvl + '</h2><div class="jp-side-tiles">'
      + tile(s.learned || 0, 'Đã học') + tile(s.known || 0, 'Đã biết') + tile(acc, 'Độ chính xác micro-check')
      + tile((s.studyDays || 0) + ' ngày', 'Ngày học xong') + '</div></section>'
      + weak
      + verbReview(s.level, passed)
      + '<div class="jp-side__range">' + (passed ? '<a class="jp-btn" href="/learning/jlpt/levels/' + encodeURIComponent(s.level)
        + '/certificate">Xem chứng nhận</a>' : '') + next + '</div>';
  }

  var mounted = false;

  function mount() {
    var doc = root && root.document;
    var main = doc && doc.querySelector && doc.querySelector('[data-jpath-level-summary]');
    if (mounted || !main || !root.JpathApi) return;
    mounted = true;
    var level = main.getAttribute('data-level');
    var status = main.querySelector('[data-role="status"]');
    main.querySelector('[data-role="title"]').textContent = 'Tổng kết cấp ' + level;
    root.JpathApi.get(BASE + encodeURIComponent(level) + '/summary').then(function (s) {
      status.textContent = '';
      main.querySelector('[data-role="body"]').innerHTML = summaryHtml(s);
    }, function (e) { status.textContent = (e && (e.code || e.status) && e.message) || 'Không tải được tổng kết.'; });
  }

  return { summaryHtml: summaryHtml, mount: mount };
});
