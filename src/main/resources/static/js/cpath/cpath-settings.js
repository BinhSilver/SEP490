/* Cài đặt lộ trình Giao tiếp (F61), mỗi nhóm lưu riêng:
 *   Nhịp học     PUT  /api/learning/comm/path/pace     xem trước ngày xong, cảnh báo không kịp, hoàn tác lần đổi gần nhất
 *   Hiển thị     PUT  /api/learning/comm/path/display  {furigana, translation} lưu ngay khi bấm; {reset:true} về theo trình độ
 *   Độ sâu       PUT  /api/learning/comm/path/depth    {depth: BASIC|ELEMENTARY|ADVANCED} (F70; lùi mức hỏi xác nhận)
 *   Tốc độ nghe  PUT  /api/learning/comm/path/display  {speed} — thanh kéo CpathAudio, mỗi mức có chú thích (F70/F79)
 *   Mục tiêu     link khảo sát đổi mục tiêu chính / thêm mục tiêu phụ
 *   Tạm dừng     POST /api/learning/comm/path/pause    giữ tiến độ, chọn lại mục tiêu này trong khảo sát để học tiếp
 *   Đặt lại      POST /api/learning/comm/path/restart  {confirmText:'BẮT ĐẦU LẠI'} xoá tiến độ, giữ Sổ tay và nhịp học
 */
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
  var RESTART_TEXT = 'BẮT ĐẦU LẠI';
  var LEVEL_NAMES = { BEGINNER: 'Mới bắt đầu', ELEMENTARY: 'Sơ cấp', INTERMEDIATE: 'Trung cấp' };
  var DISPLAY_FIELDS = [
    ['furigana', 'Furigana', 'Hiện cách đọc hiragana nhỏ phía trên chữ Hán trong hội thoại, thẻ từ và câu luyện nói.'],
    ['romaji', 'Romaji', 'Hiện phiên âm chữ Latinh gần đúng dưới từ vựng và câu thoại đã có cách đọc.'],
    ['translation', 'Bản dịch tiếng Việt', 'Hiện nghĩa tiếng Việt dưới mỗi câu. Ở bước nhập vai, bản dịch đáp án vẫn ẩn tới khi bạn chọn.']
  ];
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  }); }
  function restartTextOk(text) {
    return String(text == null ? '' : text).trim().normalize('NFC').toLocaleUpperCase('vi') === RESTART_TEXT;
  }
  function payload(path) {
    return { dailySituations: Math.min(4, Math.max(1, Number(path.dailySituations) || 1)),
      restDays: Array.isArray(path.restDays) ? path.restDays.slice() : [],
      finishDate: path.finishDate || '' };
  }
  function displayOf(path) {
    var d = (path && path.display) || {};
    return { furigana: d.furigana !== false, translation: d.translation !== false, custom: d.custom === true,
      romaji: d.romaji === true, fullRomaji: d.fullRomaji === true, speed: d.speed == null ? 1 : Number(d.speed) };
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
  function note(text, isError) {
    if (!text) return '';
    return isError ? '<p class="jp-alert" role="alert">' + esc(text) + '</p>'
      : '<p class="cp-settings__notice" role="status">' + esc(text) + '</p>';
  }

  function paceSection(path, draft, notices, canUndo) {
    var calc = estimate(path, draft);
    return '<section class="jp-card cp-settings" id="cpSetPace" aria-labelledby="cpPaceTitle">'
      + '<h2 id="cpPaceTitle">Nhịp học</h2>'
      + '<p class="cp-settings__desc">Chỉnh số tình huống, ngày nghỉ và ngày muốn hoàn thành. Bài đã học được giữ nguyên; '
      + 'lịch hôm nay chỉ đổi phần bạn chưa bắt đầu.</p>'
      + '<fieldset><legend>Tình huống mỗi ngày</legend><div class="cp-settings__options">'
      + [1, 2, 3, 4].map(function (n) { return '<label><input type="radio" name="cp-daily" value="' + n + '"'
        + (draft.dailySituations === n ? ' checked' : '') + '> ' + n + ' tình huống (khoảng ' + (n * 15) + ' phút)</label>'; }).join('')
      + '</div></fieldset>'
      + '<fieldset><legend>Ngày nghỉ</legend><div class="cp-settings__options">'
      + DAYS.map(function (d) { return '<label><input type="checkbox" data-cp-rest="' + d[0] + '"'
        + (draft.restDays.indexOf(d[0]) >= 0 ? ' checked' : '') + '> ' + d[1] + '</label>'; }).join('')
      + '</div></fieldset>'
      + '<label class="cp-settings__date">Muốn hoàn thành trước ngày <input type="date" data-cp-finish value="' + esc(draft.finishDate) + '"></label>'
      + (calc.remaining > 0
        ? '<p class="cp-settings__preview" role="status">Còn ' + calc.remaining + ' tình huống. Với nhịp này, dự kiến học xong các tình huống '
          + shortDate(calc.date) + ' (' + draft.dailySituations + ' tình huống/ngày học).</p>'
        : '<p class="cp-settings__preview" role="status">Bạn đã học hết các tình huống của lộ trình này. Nhịp học chỉ còn dùng cho phần ôn tập.</p>')
      + (calc.late ? '<p class="jp-alert" role="alert">Nhịp hiện chọn có thể không kịp ngày hoàn thành. Hãy tăng số tình huống hoặc giảm ngày nghỉ.</p>' : '')
      + note(notices.pace, notices.paceError)
      + '<div class="cp-settings__actions"><button type="button" class="cp-btn-primary" data-cp-save-pace>Lưu nhịp học</button>'
      + (canUndo ? '<button type="button" class="cp-btn-ghost" data-cp-undo-pace>Hoàn tác lần đổi vừa rồi</button>' : '')
      + '<a class="cp-btn-ghost" href="/learning/comm/path">Về bản đồ</a></div></section>';
  }

  function displaySection(path, notices) {
    var d = displayOf(path);
    var level = LEVEL_NAMES[path.selfLevel] || 'trình độ đã chọn';
    return '<section class="jp-card cp-settings" id="cpSetDisplay" aria-labelledby="cpDisplayTitle">'
      + '<h2 id="cpDisplayTitle">Hiển thị chữ</h2>'
      + '<p class="cp-settings__desc">Lưu ngay khi bạn bấm và áp dụng cho mọi tình huống. Trong bài học vẫn có nút bật/tắt nhanh cho riêng lần học đó.</p>'
      + '<div class="cp-settings__switches">'
      + DISPLAY_FIELDS.map(function (f) {
        var key = f[0];
        var full = key === 'romaji' && d.fullRomaji;
        var label = full ? 'Romaji đầy đủ' : f[1];
        var desc = full ? 'Đang bật vì bạn chưa học chữ cái. Tắt khi đã đọc được Hiragana và Katakana.' : f[2];
        return '<label class="cp-settings__switch"><input type="checkbox" data-cp-display="' + key + '"'
          + (d[key] ? ' checked' : '') + '><span><strong>' + esc(label) + '</strong><small>' + esc(desc) + '</small></span></label>';
      }).join('')
      + '</div>'
      + '<p class="cp-settings__desc">' + (d.custom ? 'Bạn đang dùng cách hiển thị tự chọn.'
        : 'Đang theo mặc định của trình độ ' + esc(level) + '.') + '</p>'
      + note(notices.display, notices.displayError)
      + (d.custom ? '<div class="cp-settings__actions"><button type="button" class="cp-btn-ghost" data-cp-display-reset>Về mặc định theo trình độ</button></div>' : '')
      + '</section>';
  }

  var DEPTH_DESC = {
    BASIC: 'Phần thiết yếu nhất, khoảng 1/3 bộ nền và 1/3 tình huống.',
    ELEMENTARY: 'Khoảng 2/3 bộ nền và tình huống.',
    ADVANCED: 'Toàn bộ, thêm kính ngữ đầy đủ và các mẫu câu sắc thái.'
  };

  /** F70: depth of the base set and situations, with size and days left at the current pace. */
  var DEPTH_FITS = { BASIC: 'Mới bắt đầu', ELEMENTARY: 'Sơ cấp', ADVANCED: 'Trung cấp' };

  function depthSection(core, notices) {
    if (!core || !core.enabled || !Array.isArray(core.depths)) return '';
    return '<section class="jp-card cp-settings" id="cpSetDepth" aria-labelledby="cpDepthTitle">'
      + '<h2 id="cpDepthTitle">Độ sâu</h2>'
      + '<p class="cp-settings__desc">Quyết định học bao nhiêu từ, mẫu câu và tình huống. Lên mức: phần mới được thêm vào các ngày sau, '
      + 'tiến độ giữ nguyên. Xuống mức: phần chưa học được ẩn, phần đã học vẫn được ôn.</p>'
      + '<fieldset><legend>Chọn độ sâu</legend><div class="cp-settings__depths">'
      + core.depths.map(function (x) {
        var on = x.code === core.depth;
        return '<label class="cp-settings__depth' + (on ? ' is-on' : '') + '"><input type="radio" name="cp-depth" value="' + esc(x.code) + '"'
          + (on ? ' checked' : '') + '><span><strong>' + esc(x.labelVi) + '</strong>'
          // F181 (AI test L8): the depth names differ from the survey levels; say which level each one fits
          + (DEPTH_FITS[x.code] ? '<small class="cp-settings__fits">Hợp với trình độ: ' + esc(DEPTH_FITS[x.code]) + '</small>' : '')
          + '<small>' + esc(DEPTH_DESC[x.code] || '') + '</small>'
          + '<small>' + Number(x.words || 0) + ' từ · ' + Number(x.grammar || 0) + ' mẫu ngữ pháp'
          + (Number(x.keigo || 0) ? ' · ' + Number(x.keigo) + ' mục kính ngữ' : '')
          + ' · bộ từ và mẫu câu còn khoảng ' + Number(x.daysLeft || 0) + ' ngày học</small></span></label>';
      }).join('')
      + '</div></fieldset>'
      + (core.depthSaved ? '' : '<p class="cp-settings__desc">Đang theo trình độ bạn đã khai khi tạo lộ trình.</p>')
      + note(notices.depth, notices.depthError)
      + '</section>';
  }

  /** F70/F79: the listening speed slider, each stop labelled with what it is like. */
  function listenSection(path, notices) {
    var A = root && root.CpathAudio;
    if (!A) return '';
    A.fromDisplay(path.display);
    return '<section class="jp-card cp-settings" id="cpSetListen" aria-labelledby="cpListenTitle">'
      + '<h2 id="cpListenTitle">Nghe</h2>'
      + '<p class="cp-settings__desc">Áp dụng cho mọi âm thanh của Giao tiếp: câu thoại, từ, mẫu câu, kiểm tra tuần và bài nền. '
      + 'Tăng dần khi đã nghe quen để bắt kịp tốc độ người Nhật nói ngoài đời.</p>'
      + A.sliderHtml()
      + note(notices.listen, notices.listenError)
      + '</section>';
  }

  /** Owner 17/11: the shared "Mục tiêu học của bạn" panel (jpath-goals.js), the same on the JLPT settings. */
  function goalSection() {
    return '<div id="cpSetGoal" data-jpath-goals></div>';
  }

  function manageSection(path, notices) {
    var name = esc(path.nameVi || 'Giao tiếp');
    return '<section class="jp-card cp-settings" id="cpSetManage" aria-labelledby="cpManageTitle">'
      + '<h2 id="cpManageTitle">Tạm dừng hoặc đặt lại lộ trình</h2>'
      + '<div class="cp-settings__option">'
      + '<h3>Tạm dừng</h3>'
      + '<p class="cp-settings__desc">Ngừng lộ trình ' + name + ', tiến độ được giữ nguyên. Muốn học tiếp, vào khảo sát Giao tiếp và chọn lại mục tiêu này.</p>'
      + '<div class="cp-settings__actions"><button type="button" class="cp-btn-ghost" data-cp-pause>Tạm dừng lộ trình</button></div>'
      + '</div>'
      + '<div class="cp-settings__option cp-settings__option--danger">'
      + '<h3>Đặt lại từ đầu</h3>'
      + '<p class="cp-settings__desc">Xoá tiến độ tình huống, chủ đề, kiểm tra và câu nói yếu của ' + name
      + '. Sổ tay và nhịp học được giữ; CP đã nhận không cộng lại lần nữa. Không hoàn tác được.</p>'
      + '<label class="cp-settings__date">Gõ “' + RESTART_TEXT + '” để mở nút'
      + '<input type="text" autocomplete="off" spellcheck="false" data-cp-restart-text></label>'
      + '<div class="cp-settings__actions"><button type="button" class="cp-btn-danger" data-cp-restart disabled>Đặt lại từ đầu</button></div>'
      + '</div>'
      + note(notices.manage, true)
      + '</section>';
  }

  function view(path, draft, notice, canUndo, notices, core) {
    var n = notices || {};
    if (notice && !n.pace) n = Object.assign({}, n, { pace: notice });
    return '<header class="jp-page-head cp-settings__head">'
      + '<h1 class="jp-page-head__title">Cài đặt lộ trình Giao tiếp</h1>'
      + '<p class="jp-page-head__lede">Mỗi nhóm lưu riêng. Thay đổi lớn có hộp xác nhận.</p>'
      + '<nav class="cp-settings__toc" aria-label="Mục lục cài đặt">'
      + '<a href="#cpSetPace">Nhịp học</a>' + (core && core.enabled ? '<a href="#cpSetDepth">Độ sâu</a>' : '')
      + '<a href="#cpSetDisplay">Hiển thị chữ</a><a href="#cpSetListen">Nghe</a>'
      + '<a href="#cpSetGoal">Mục tiêu</a><a href="#cpSetManage">Tạm dừng / đặt lại</a></nav></header>'
      + paceSection(path, draft, n, canUndo)
      + depthSection(core, n)
      + displaySection(path, n)
      + listenSection(path, n)
      + goalSection(path)
      + manageSection(path, n);
  }

  function mount(el, deps) {
    if (!el || !root.JpathApi) return;
    var d = deps || {};
    var ask = d.confirm || root.JlasConfirm || function (msg) { return Promise.resolve(root.confirm(msg)); };
    var go = d.go || function (url) { root.location.assign(url); };
    var path = null, draft = null, undo = null, notices = {}, busy = false, core = null;
    function render() {
      var focusId = root.document && root.document.activeElement && root.document.activeElement.getAttribute
        ? root.document.activeElement.getAttribute('data-cp-display') : null;
      el.innerHTML = view(path, draft, '', !!undo, notices, core);
      var goalsEl = el.querySelector && el.querySelector('[data-jpath-goals]');
      if (goalsEl && typeof window !== 'undefined' && window.JpathGoals) window.JpathGoals.mount(goalsEl);
      if (focusId) {
        var again = el.querySelector('[data-cp-display="' + focusId + '"]');
        if (again) again.focus();
      }
    }
    function fail(e, fallback) { return e && e.message || fallback; }
    function savePace(next, undoValue) {
      busy = true;
      el.querySelector('[data-cp-save-pace]').disabled = true;
      Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/pace', next)).then(function (p) {
        busy = false; path = p; draft = payload(p); undo = undoValue;
        notices = { pace: undoValue ? 'Đã lưu nhịp học.' : 'Đã hoàn tác về nhịp học trước.' }; render();
      }).catch(function (e) { busy = false; notices = { pace: fail(e, 'Chưa lưu được nhịp học.'), paceError: true }; render(); });
    }
    function saveDisplay(body) {
      busy = true;
      Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/display', body)).then(function (p) {
        busy = false; path = p; notices = { display: body.reset ? 'Đã về mặc định theo trình độ.' : 'Đã lưu cách hiển thị.' }; render();
      }).catch(function (e) { busy = false; notices = { display: fail(e, 'Chưa lưu được cách hiển thị.'), displayError: true }; render(); });
    }
    function danger(message, opts, request, fallbackUrl) {
      if (busy) return;
      Promise.resolve(ask(message, opts)).then(function (yes) {
        if (!yes) return;
        busy = true;
        Promise.resolve(request()).then(function (data) {
          go(data && data.redirectUrl ? data.redirectUrl : fallbackUrl);
        }).catch(function (e) { busy = false; notices = { manage: fail(e, 'Chưa thực hiện được. Thử lại sau.') }; render(); });
      });
    }
    function saveDepth(depth) {
      busy = true;
      Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/depth', { depth: depth })).then(function (c) {
        busy = false; core = c; notices = { depth: 'Đã đổi độ sâu sang ' + (c && c.depthLabel || '') + '.' };
        return Promise.resolve(root.JpathApi.get('/api/learning/comm/path')).then(function (p) { path = p; render(); });
      }).catch(function (e) { busy = false; notices = { depth: fail(e, 'Chưa đổi được độ sâu.'), depthError: true }; render(); });
    }
    if (root.CpathAudio) {
      root.CpathAudio.bind(el, function (value) {
        return Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/display', { speed: value })).then(function (p) {
          if (p) path = p;
        }).catch(function () {
          var sec = el.querySelector('#cpSetListen');
          if (sec && !sec.querySelector('.jp-alert')) sec.insertAdjacentHTML('beforeend', note('Chưa lưu được tốc độ; trang này vẫn dùng tốc độ vừa chọn.', true));
        });
      });
    }
    el.addEventListener('change', function (ev) {
      var t = ev.target;
      if (t.name === 'cp-depth') {
        if (busy || !core) { render(); return; }
        var to = t.value;
        var order = ['BASIC', 'ELEMENTARY', 'ADVANCED'];
        if (order.indexOf(to) < order.indexOf(core.depth)) {
          Promise.resolve(ask('Xuống độ sâu thấp hơn? Phần chưa học sẽ được ẩn; phần đã học vẫn được ôn.',
            { confirmText: 'Đổi độ sâu', cancelText: 'Hủy', label: 'Đổi độ sâu' })).then(function (yes) {
            if (yes) saveDepth(to); else render();
          });
        } else saveDepth(to);
        return;
      }
      if (t.hasAttribute('data-cp-speed')) return;
      if (t.hasAttribute('data-cp-display')) {
        if (busy) { t.checked = !t.checked; return; }
        var body = {};
        body[t.getAttribute('data-cp-display')] = t.checked;
        saveDisplay(body);
        return;
      }
      if (t.name === 'cp-daily') draft.dailySituations = Number(t.value);
      else if (t.hasAttribute('data-cp-rest')) {
        var code = t.getAttribute('data-cp-rest');
        draft.restDays = draft.restDays.filter(function (x) { return x !== code; });
        if (t.checked) draft.restDays.push(code);
      } else if (t.hasAttribute('data-cp-finish')) draft.finishDate = t.value;
      else return;
      notices = {}; render();
    });
    el.addEventListener('input', function (ev) {
      if (!ev.target.hasAttribute('data-cp-restart-text')) return;
      var btn = el.querySelector('[data-cp-restart]');
      if (btn) btn.disabled = !restartTextOk(ev.target.value);
    });
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (t.closest('[data-cp-save-pace]')) {
        if (busy) return;
        if (draft.restDays.length >= 7) { notices = { pace: 'Cần giữ ít nhất một ngày học trong tuần.', paceError: true }; render(); return; }
        savePace(draft, payload(path));
      } else if (t.closest('[data-cp-undo-pace]') && undo && !busy) {
        savePace(undo, null);
      } else if (t.closest('[data-cp-display-reset]') && !busy) {
        saveDisplay({ reset: true });
      } else if (t.closest('[data-cp-pause]')) {
        danger('Tạm dừng lộ trình ' + (path.nameVi || 'Giao tiếp') + '? Tiến độ được giữ; chọn lại mục tiêu này trong khảo sát để học tiếp.',
          { confirmText: 'Tạm dừng', cancelText: 'Hủy', label: 'Tạm dừng lộ trình' },
          function () { return root.JpathApi.send('POST', '/api/learning/comm/path/pause'); }, '/learning');
      } else if (t.closest('[data-cp-restart]')) {
        var input = el.querySelector('[data-cp-restart-text]');
        var text = input ? input.value : '';
        if (!restartTextOk(text)) return;
        danger('Xoá toàn bộ tiến độ lộ trình ' + (path.nameVi || 'Giao tiếp') + '? Không hoàn tác được.',
          { confirmText: 'Xoá và đặt lại', cancelText: 'Hủy', label: 'Đặt lại từ đầu' },
          function () { return root.JpathApi.send('POST', '/api/learning/comm/path/restart', { confirmText: text }); },
          '/learning/comm/path');
      }
    });
    Promise.resolve(root.JpathApi.get('/api/learning/comm/path')).then(function (p) {
      path = p; draft = payload(p); render();
      return Promise.resolve(root.JpathApi.get('/api/learning/comm/core')).then(function (c) { core = c; render(); }, function () { /* no base set */ });
    }).catch(function (e) { el.innerHTML = '<p class="jp-alert" role="alert">' + esc(fail(e, 'Chưa tải được cài đặt.')) + '</p>'; });
  }
  return { RESTART_TEXT: RESTART_TEXT, restartTextOk: restartTextOk, payload: payload, displayOf: displayOf,
    estimate: estimate, view: view, mount: mount };
});
