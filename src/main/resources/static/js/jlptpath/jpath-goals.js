/* [JLAS-W0108] jpath-goals.js — "Mục tiêu học của bạn" (owner 17/11, after the mentor review).
 *
 * One panel, the same on the JLPT and the communication settings pages: the main goal, the secondary goal, the
 * paused paths, and in plain words when each change is allowed and what it does. Every change asks first and says
 * what happens to the progress. Data: GET /api/learning/comm/goals/overview
 *   { surveyGoal, main:{kind,goal,nameVi,url}|null, secondary:{…}|null, paused:[{…}], canAddSecondary }
 *
 * window.JpathGoals = { panelHtml(view), changeText(action, view), mount(el) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathGoals = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () {
      var els = root.document.querySelectorAll('[data-jpath-goals]');
      for (var i = 0; i < els.length; i++) api.mount(els[i]);
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var URL_OVERVIEW = '/api/learning/comm/goals/overview';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function name(g) { return g ? (g.kind === 'JLPT' ? 'Lộ trình ' + g.nameVi : 'Giao tiếp · ' + g.nameVi) : ''; }

  /** What a change does, said before it happens (the confirm text). */
  function changeText(action, view) {
    var v = view || {};
    if (action === 'main') {
      return 'Đổi mục tiêu chính?\n\n'
        + (v.main ? '• ' + name(v.main) + ' sẽ TẠM DỪNG. Tiến độ, sổ tay và CP giữ nguyên; chọn lại mục tiêu này là học tiếp.\n' : '')
        + '• Bạn chọn mục tiêu mới ở bước khảo sát. Nếu chọn đúng mục tiêu phụ hiện tại, mục tiêu phụ được nâng lên làm chính và giữ tiến độ.\n'
        + '• Chọn JLPT làm mục tiêu chính: lộ trình Giao tiếp (chính và phụ) tạm dừng, tiến độ giữ nguyên.\n'
        + '• Chọn một mục tiêu Giao tiếp: mục tiêu phụ khác vẫn giữ nguyên.';
    }
    if (action === 'secondary') {
      return (v.secondary ? 'Đổi mục tiêu phụ?\n\n• ' + name(v.secondary) + ' sẽ TẠM DỪNG (tiến độ giữ nguyên).\n' : 'Thêm mục tiêu phụ?\n\n')
        + '• Mục tiêu phụ phải khác mục tiêu chính.\n'
        + '• Mục tiêu phụ học tự do: không giao bài theo ngày, không làm bài tổng kết khi còn là mục tiêu phụ.\n'
        + '• Mỗi lúc chỉ có 1 mục tiêu phụ.';
    }
    if (action === 'pause-open') {
      return 'Tạm dừng ' + (v.leftOpen || []).map(name).join(', ') + '?\n\n'
        + '• Tiến độ, sổ tay và CP giữ nguyên.\n• Muốn học lại: "Đổi mục tiêu chính" và chọn mục tiêu đó.';
    }
    if (action === 'promote') {
      return 'Nâng ' + name(v.secondary) + ' lên làm mục tiêu chính?\n\n'
        + '• Mục tiêu này giữ toàn bộ tiến độ và bắt đầu được giao bài mỗi ngày.\n'
        + (v.main ? '• ' + name(v.main) + ' sẽ TẠM DỪNG (tiến độ giữ nguyên).' : '');
    }
    return '';
  }

  function row(label, body, note, extra) {
    return '<div class="jp-goals__row"><p class="jp-goals__label">' + label + '</p>'
      + '<div class="jp-goals__val">' + body + (note ? '<p class="jp-goals__note">' + note + '</p>' : '') + (extra || '') + '</div></div>';
  }

  function panelHtml(view) {
    var v = view || {};
    var mainBody = v.main
      ? '<strong>' + esc(name(v.main)) + '</strong>'
      : '<strong>Chưa có lộ trình chính đang học</strong>';
    var mainNote = v.main
      ? 'Được giao bài mỗi ngày trong "Hôm nay", có kiểm tra tuần và bài tổng kết.'
      : 'Làm khảo sát để chọn mục tiêu và tạo lộ trình.';
    var secBody = v.secondary ? '<strong>' + esc(name(v.secondary)) + '</strong>' : '<span class="jp-goals__none">Chưa có</span>';
    var secNote = v.secondary
      ? 'Học tự do khi muốn, không giao bài theo ngày.'
      : (v.canAddSecondary ? 'Có thể thêm 1 mục tiêu giao tiếp khác để học tự do.'
        : 'Mục tiêu phụ chỉ có khi mục tiêu chính là Giao tiếp (lộ trình JLPT đã giao bài đủ mỗi ngày).');
    var paused = (v.paused || []);
    var pausedBody = paused.length
      ? '<ul class="jp-goals__paused">' + paused.map(function (g) { return '<li>' + esc(name(g)) + '</li>'; }).join('') + '</ul>'
      : '<span class="jp-goals__none">Không có</span>';

    var open = (v.leftOpen || []);
    var openRow = open.length ? '<div class="jp-goals__warn" role="note" data-notice-keep>'
      + '<p><strong>Còn lộ trình đang mở từ trước khi đổi mục tiêu:</strong> '
      + open.map(function (g) { return esc(name(g)); }).join(', ') + '. '
      + 'Mỗi lúc chỉ nên có 1 mục tiêu chính được giao bài; tạm dừng để lộ trình gọn, tiến độ vẫn giữ nguyên.</p>'
      + '<button type="button" class="jp-btn jp-btn--sm" data-goal-action="pause-open">Tạm dừng các lộ trình này</button></div>' : '';
    var actions = '<div class="jp-goals__actions">'
      + '<a class="jp-btn" href="/survey?edit=goal" data-goal-action="main">Đổi mục tiêu chính</a>'
      + (v.canAddSecondary ? '<a class="jp-btn" href="/learning/comm/survey?secondary=1" data-goal-action="secondary">'
        + (v.secondary ? 'Đổi mục tiêu phụ' : 'Thêm mục tiêu phụ') + '</a>' : '')
      + (v.secondary ? '<button type="button" class="jp-btn" data-goal-action="promote">Nâng mục tiêu phụ lên chính</button>' : '')
      + '</div>';

    var rules = '<details class="jp-goals__rules"><summary>Khi nào được đổi? Đổi rồi tiến độ ra sao?</summary><ul>'
      + '<li><b>Đổi mục tiêu chính</b> — lúc nào cũng được. Lộ trình đang học <b>tạm dừng</b>, không mất tiến độ; chọn lại là học tiếp. Chọn JLPT thì lộ trình Giao tiếp (cả chính và phụ) tạm dừng.</li>'
      + '<li><b>Thêm / đổi mục tiêu phụ</b> — chỉ khi mục tiêu chính là Giao tiếp; phải khác mục tiêu chính; mục tiêu phụ mới thay mục tiêu phụ cũ (cũ tạm dừng).</li>'
      + '<li><b>Nâng mục tiêu phụ lên chính</b> — giữ tiến độ của mục tiêu phụ; mục tiêu chính cũ tạm dừng.</li>'
      + '<li><b>Đổi cấp JLPT</b> (xuống cấp, quay lại cấp đã đạt, lên cấp sau bài tổng kết) — ở mục "Đổi cấp hoặc mục tiêu" của Cài đặt lộ trình JLPT; không đổi mục tiêu chính.</li>'
      + '<li><b>Làm lại Smart Test</b> — chỉ xem lại trình độ; muốn áp dụng thì bấm "Cập nhật lộ trình theo kết quả này" trên trang kết quả.</li>'
      + '</ul></details>';

    return '<section class="jp-card jp-goals" aria-labelledby="jpGoalsTitle">'
      + '<h2 id="jpGoalsTitle" class="jp-goals__title">Mục tiêu học của bạn</h2>'
      + row('Mục tiêu chính', mainBody, mainNote)
      + row('Mục tiêu phụ', secBody, secNote)
      + openRow
      + row('Đang tạm dừng', pausedBody, paused.length ? 'Tiến độ giữ nguyên. Chọn lại mục tiêu này ở "Đổi mục tiêu chính" là học tiếp.' : '')
      + actions + rules
      + '<p class="jp-goals__msg" role="status" hidden></p>'
      + '</section>';
  }

  /** Owner 24/11 "Lộ trình của tôi": paths are equal, so the settings box lists them and links to the dashboard. */
  function myPathsPanelHtml(view) {
    var label = { ACTIVE: 'Đang học', PAUSED: 'Tạm dừng', FINISHED: 'Đã xong' };
    var list = view && Array.isArray(view.paths) ? view.paths : [];
    var rows = list.length
      ? '<ul class="jp-goals__paused">' + list.map(function (p) {
        return '<li><strong>' + esc(p.title) + '</strong> · ' + esc(label[p.status] || p.status) + '</li>';
      }).join('') + '</ul>'
      : '<span class="jp-goals__none">Chưa có lộ trình nào</span>';
    return '<section class="jp-card jp-goals" aria-labelledby="jpGoalsTitle">'
      + '<h2 id="jpGoalsTitle" class="jp-goals__title">Lộ trình của tôi</h2>'
      + row('Các lộ trình', rows, 'Học cùng lúc tối đa ' + (Number(view && view.maxActive) || 2)
        + ' lộ trình, mỗi lộ trình giữ nhịp riêng. Tạm dừng lộ trình nào chỉ dừng lộ trình đó; Học tiếp mở lại đúng chỗ đã dừng.')
      + '<div class="jp-goals__actions"><a class="jp-btn jp-btn--primary" href="/learning/my">Mở Lộ trình của tôi</a></div>'
      + '</section>';
  }

  function mount(el) {
    var client = root && root.JpathApi;
    if (!el || !client) return null;
    // the "Lộ trình của tôi" API replaces the main/secondary overview; older servers keep the old box
    return Promise.resolve(client.get('/api/learning/my-paths')).then(function (mine) {
      el.innerHTML = myPathsPanelHtml(mine);
      el.onclick = null;
    }, function () { return mountOverview(el, client); });
  }

  function mountOverview(el, client) {
    return Promise.resolve(client.get(URL_OVERVIEW)).then(function (view) {
      el.innerHTML = panelHtml(view);
      var ask = root.JlasConfirm || function (msg) { return Promise.resolve(root.confirm(msg)); };
      el.onclick = function (ev) {
        var t = ev.target && ev.target.closest ? ev.target.closest('[data-goal-action]') : null;
        if (!t) return;
        ev.preventDefault();
        var action = t.getAttribute('data-goal-action');
        Promise.resolve(ask(changeText(action, view))).then(function (yes) {
          if (!yes) return;
          if (action !== 'promote' && action !== 'pause-open') { root.location.assign(t.getAttribute('href')); return; }
          var msg = el.querySelector('.jp-goals__msg');
          var jlptOpen = (view.leftOpen || []).some(function (g) { return g.kind === 'JLPT'; });
          var req = action === 'promote' ? client.send('POST', '/api/learning/comm/path/promote-secondary')
            : (jlptOpen ? client.send('POST', '/api/learning/jlpt/path/pause') : client.send('POST', '/api/learning/comm/goals/pause-for-jlpt'));
          Promise.resolve(req).then(function () {
            if (action === 'promote') { root.location.assign('/learning/comm/path?goalChanged=promote'); return; }
            mountOverview(el, client).then(function () {
              var m2 = el.querySelector('.jp-goals__msg');
              if (m2) { m2.hidden = false; m2.textContent = 'Đã tạm dừng. Tiến độ được giữ nguyên.'; }
            });
          }, function (err) {
            if (msg) { msg.hidden = false; msg.textContent = (err && (err.code || err.status) && err.message) || 'Chưa nâng được mục tiêu phụ. Thử lại sau.'; }
          });
        });
      };
    }, function () { el.hidden = true; });
  }

  return { panelHtml: panelHtml, myPathsPanelHtml: myPathsPanelHtml, changeText: changeText, mount: mount };
});
