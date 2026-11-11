/* Nhịp học Giao tiếp: xem trước lịch, lưu và hoàn tác lần đổi gần nhất. */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathSettings = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document.getElementById('cpathSettingsRoot')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';
  var DAYS = [['MON', 'T2'], ['TUE', 'T3'], ['WED', 'T4'], ['THU', 'T5'], ['FRI', 'T6'], ['SAT', 'T7'], ['SUN', 'CN']];
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  }); }
  function payload(path) {
    return { dailySituations: Number(path.dailySituations) === 2 ? 2 : 1,
      restDays: Array.isArray(path.restDays) ? path.restDays.slice() : [],
      finishDate: path.finishDate || '' };
  }
  function estimate(path, draft) {
    var remaining = Math.max(0, Number(path.progress && path.progress.totalSituations || 0)
      - Number(path.progress && path.progress.doneSituations || 0));
    var iso = String(path.today && path.today.date || '');
    var start = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(iso + 'T00:00:00Z') : null;
    if (!start || !Number.isFinite(start.getTime())) return { remaining: remaining, date: '', late: false };
    var dayCodes = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
    var date = new Date(start.getTime());
    var left = remaining;
    for (var n = 0; left > 0 && n < 3650; n++) {
      if (draft.restDays.indexOf(dayCodes[date.getUTCDay()]) < 0) left -= draft.dailySituations;
      if (left > 0) date.setUTCDate(date.getUTCDate() + 1);
    }
    var done = date.toISOString().slice(0, 10);
    return { remaining: remaining, date: done,
      late: !!draft.finishDate && done > draft.finishDate };
  }
  function shortDate(iso) { var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] + '/' + m[1] : ''; }
  function view(path, draft, notice, canUndo) {
    var calc = estimate(path, draft);
    return '<section class="jp-card cp-settings" aria-labelledby="cpPaceTitle">'
      + '<h1 id="cpPaceTitle">Nhịp học Giao tiếp</h1>'
      + '<p>Chỉnh số tình huống, ngày nghỉ và ngày muốn hoàn thành. Bài đã học được giữ nguyên.</p>'
      + '<fieldset><legend>Tình huống mỗi ngày</legend><div class="cp-settings__options">'
      + [1, 2].map(function (n) { return '<label><input type="radio" name="cp-daily" value="' + n + '"'
        + (draft.dailySituations === n ? ' checked' : '') + '> ' + n + ' tình huống (khoảng ' + (n * 15) + ' phút)</label>'; }).join('')
      + '</div></fieldset>'
      + '<fieldset><legend>Ngày nghỉ</legend><div class="cp-settings__options">'
      + DAYS.map(function (d) { return '<label><input type="checkbox" data-cp-rest="' + d[0] + '"'
        + (draft.restDays.indexOf(d[0]) >= 0 ? ' checked' : '') + '> ' + d[1] + '</label>'; }).join('')
      + '</div></fieldset>'
      + '<label class="cp-settings__date">Muốn hoàn thành trước ngày <input type="date" data-cp-finish value="' + esc(draft.finishDate) + '"></label>'
      + '<p class="cp-settings__preview" role="status">Còn ' + calc.remaining + ' tình huống. Với nhịp này, dự kiến xong '
      + shortDate(calc.date) + ' (' + draft.dailySituations + ' tình huống/ngày học).</p>'
      + (calc.late ? '<p class="jp-alert" role="alert">Nhịp hiện chọn có thể không kịp ngày hoàn thành. Hãy tăng số tình huống hoặc giảm ngày nghỉ.</p>' : '')
      + (notice ? '<p class="cp-settings__notice" role="status">' + esc(notice) + '</p>' : '')
      + '<div class="cp-settings__actions"><button type="button" class="cp-btn-primary" data-cp-save-pace>Lưu nhịp học</button>'
      + (canUndo ? '<button type="button" class="cp-btn-ghost" data-cp-undo-pace>Hoàn tác lần đổi vừa rồi</button>' : '')
      + '<a class="cp-btn-ghost" href="/learning/comm/path">Về bản đồ</a></div></section>';
  }
  function mount(el) {
    if (!el || !root.JpathApi) return;
    var path = null, draft = null, undo = null, notice = '';
    function render() { el.innerHTML = view(path, draft, notice, !!undo); }
    function save(next, undoValue) {
      el.querySelector('[data-cp-save-pace]').disabled = true;
      Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/pace', next)).then(function (p) {
        path = p; draft = payload(p); undo = undoValue; notice = 'Đã lưu nhịp học.'; render();
      }).catch(function (e) { notice = e && e.message || 'Chưa lưu được nhịp học.'; render(); });
    }
    el.addEventListener('change', function (ev) {
      var t = ev.target;
      if (t.name === 'cp-daily') draft.dailySituations = Number(t.value);
      else if (t.hasAttribute('data-cp-rest')) {
        var code = t.getAttribute('data-cp-rest');
        draft.restDays = draft.restDays.filter(function (d) { return d !== code; });
        if (t.checked) draft.restDays.push(code);
      } else if (t.hasAttribute('data-cp-finish')) draft.finishDate = t.value;
      notice = ''; render();
    });
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t.closest('[data-cp-save-pace]')) {
        if (draft.restDays.length >= 7) { notice = 'Cần giữ ít nhất một ngày học trong tuần.'; render(); return; }
        save(draft, payload(path));
      } else if (t.closest('[data-cp-undo-pace]') && undo) {
        save(undo, null);
      }
    });
    Promise.resolve(root.JpathApi.get('/api/learning/comm/path')).then(function (p) {
      path = p; draft = payload(p); render();
    }).catch(function (e) { el.innerHTML = '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Chưa tải được cài đặt.') + '</p>'; });
  }
  return { payload: payload, estimate: estimate, view: view, mount: mount };
});
