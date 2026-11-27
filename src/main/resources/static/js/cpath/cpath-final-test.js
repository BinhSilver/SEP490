/*
 * Comm path final test page (owner 26/11): one focused flow for every comm goal —
 * steps (situations done → AI call → result → certificate), the brief of the test, ONE start button that opens
 * the call directly, then the result with the scored criteria. The AI Call result page of the test sends the
 * learner back here.
 */
(function (root) {
  'use strict';

  var STEPS = ['Học xong tình huống', 'Hội thoại kiểm tra', 'Kết quả', 'Chứng nhận'];
  var MASCOT = {
    TRAVEL: '/images/mascots/certificates/kitsu-travel.png',
    DAILY_LIFE: '/images/mascots/certificates/kitsu-daily-life.png',
    WORK: '/images/mascots/certificates/kitsu-work.png',
    BA: '/images/mascots/certificates/kitsu-brse.png'
  };

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** current: 0-based index of the step the learner is on; steps before it are done. */
  function steps(current) {
    return '<ol class="cp-final-steps" aria-label="Các bước bài kiểm tra cuối">' + STEPS.map(function (label, i) {
      var state = i < current ? 'done' : (i === current ? 'current' : 'todo');
      return '<li class="cp-final-steps__item is-' + state + '"' + (i === current ? ' aria-current="step"' : '') + '>'
        + '<span class="cp-final-steps__dot" aria-hidden="true">' + (i < current ? '' : (i + 1)) + '</span>'
        + '<span class="cp-final-steps__label">' + esc(label) + '</span></li>';
    }).join('') + '</ol>';
  }

  function button(href, label, kind) {
    return '<a class="jp-btn cp-ft__btn' + (kind === 'primary' ? ' jp-btn--primary' : '') + '" href="' + esc(href) + '">' + esc(label) + '</a>';
  }

  function fact(label, value) {
    return '<div class="cp-ft__fact"><dt>' + esc(label) + '</dt><dd>' + esc(value) + '</dd></div>';
  }

  function ready(test) {
    var b = test.brief || {};
    var setting = b.background || b.description;
    var facts = '';
    if (b.learnerRole && b.learnerRole !== 'Người học') facts += fact('Bạn đóng vai', b.learnerRole);
    if (b.aiRole && b.aiRole !== 'Người đối thoại') facts += fact('Người đối thoại', b.aiRole);
    if (b.minutes) facts += fact('Thời gian', 'Khoảng ' + b.minutes + ' phút');
    facts += fact('Điểm đạt', 'Từ ' + (test.passScore || 70) + '/100');
    if (Array.isArray(test.topics) && test.topics.length) facts += fact('Chủ đề', test.topics.length + ' chủ đề đã học');
    var topics = Array.isArray(test.topics) && test.topics.length
      ? '<h3 class="cp-ft__h3">Nội dung được kiểm tra</h3><ul class="cp-ft__chips">'
        + test.topics.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul>'
      : '';
    var how = ['Trả lời người đối thoại bằng tiếng Nhật, bằng giọng nói hoặc gõ chữ.',
      'Hoàn thành các việc trong hội thoại như khi gặp tình huống thật.',
      'Hội thoại xong, hệ thống chấm điểm và đưa bạn về đây xem kết quả.'];
    return '<div class="cp-ft__grid">'
      + '<article class="cp-ft__card">'
      + '<p class="cp-ft__label">Đề bài</p>'
      + '<h2 class="cp-ft__title">' + esc(b.title || 'Hội thoại tổng hợp') + '</h2>'
      + (setting ? '<p class="cp-ft__setting">' + esc(setting) + '</p>' : '')
      + (b.location ? '<p class="cp-ft__place">' + esc(b.location) + '</p>' : '')
      + topics
      + '<h3 class="cp-ft__h3">Cách làm</h3><ol class="cp-ft__how">'
      + how.map(function (t, i) { return '<li><span class="cp-ft__how-n">' + (i + 1) + '</span><span>' + esc(t) + '</span></li>'; }).join('')
      + '</ol></article>'
      + '<aside class="cp-ft__panel" aria-label="Bắt đầu bài kiểm tra">'
      + '<p class="cp-ft__label">Sẵn sàng làm bài</p>'
      + '<dl class="cp-ft__facts">' + facts + '</dl>'
      + previous(test)
      + button(test.startUrl || test.scenarioUrl, 'Bắt đầu bài kiểm tra', 'primary')
      + '<p class="cp-ft__note">Chưa đạt thì làm lại được sau 24 giờ. Tiến độ học không bị mất.</p>'
      + '</aside></div>';
  }

  function previous(test) {
    if (test.lastScore == null) return '';
    var weak = Array.isArray(test.weakTopics) && test.weakTopics.length ? ' Nên ôn: ' + test.weakTopics.join(', ') + '.' : '';
    return '<p class="cp-ft__prev">Lần trước: <strong>' + esc(Math.round(Number(test.lastScore))) + '/100</strong>.' + esc(weak) + '</p>';
  }

  function ring(score, pass) {
    var n = Math.max(0, Math.min(100, Math.round(Number(score) || 0)));
    return '<div class="cp-ft__ring ' + (pass ? 'is-pass' : 'is-fail') + '" style="--p:' + n + '" role="img" aria-label="' + n + ' trên 100 điểm">'
      + '<span class="cp-ft__ring-num">' + n + '</span><span class="cp-ft__ring-max">/100</span></div>';
  }

  function pathHref(opts) {
    var g = opts && opts.goal;
    return '/learning/comm/path' + (g ? '?goal=' + encodeURIComponent(g) : '');
  }

  function result(test, pass, now, opts) {
    var d = test.lastDetails || {};
    var criteria = Array.isArray(d.criteria) ? d.criteria : [];
    var bars = criteria.length ? '<ul class="cp-ft__bars">' + criteria.map(function (c) {
      var v = Math.max(0, Math.min(100, Number(c.score) || 0));
      return '<li><span class="cp-ft__bar-name">' + esc(c.name) + '</span><span class="cp-ft__bar"><span style="width:' + v + '%"></span></span>'
        + '<span class="cp-ft__bar-val">' + esc(v) + '</span></li>';
    }).join('') + '</ul>' : '';
    var meta = [];
    if (d.seconds) meta.push('Thời gian ' + Math.max(1, Math.round(Number(d.seconds) / 60)) + ' phút');
    if (d.mistakes != null) meta.push(d.mistakes + ' lỗi đã sửa');
    var weak = !pass && Array.isArray(test.weakTopics) && test.weakTopics.length
      ? '<h3 class="cp-ft__h3">Nên ôn trước khi làm lại</h3><ul class="cp-ft__chips">'
        + test.weakTopics.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul>' : '';
    var retryAt = test.retryAt ? new Date(test.retryAt) : null;
    var retry = !pass && retryAt && retryAt.getTime() > now
      ? '<p class="cp-ft__note">Làm lại được từ ' + esc(retryAt.toLocaleString('vi-VN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' })) + '.</p>' : '';
    var backPath = pathHref(opts);
    var actions = pass
      ? button(test.certificateUrl || '/learning/comm/certificate', 'Xem chứng nhận', 'primary') + button(backPath, 'Về lộ trình')
      : (retry ? button(backPath, 'Ôn lại lộ trình', 'primary')
        : button(test.startUrl || test.scenarioUrl || backPath, 'Làm lại bài kiểm tra', 'primary') + button(backPath, 'Ôn lại lộ trình'));
    return '<article class="cp-ft__card cp-ft__result ' + (pass ? 'is-pass' : 'is-fail') + '">'
      + ring(test.lastScore, pass)
      + '<div class="cp-ft__result-body">'
      + '<p class="cp-ft__label">' + (pass ? 'Đã đạt' : 'Chưa đạt') + '</p>'
      + '<h2 class="cp-ft__title">' + (pass ? 'Chúc mừng, bạn đã hoàn thành lộ trình!' : 'Cần từ ' + esc(test.passScore || 70) + ' điểm để hoàn thành') + '</h2>'
      + (meta.length ? '<p class="cp-ft__meta">' + esc(meta.join(' · ')) + '</p>' : '')
      + bars + weak + retry
      + '<div class="cp-ft__actions">' + actions + '</div>'
      + (d.reviewUrl ? '<a class="cp-ft__link" href="' + esc(d.reviewUrl) + '">Xem nhận xét chi tiết từng câu</a>' : '')
      + '</div></article>';
  }

  /** Returns the page HTML for the test state; pure so the Node tests can check every case. */
  function view(test, path, opts) {
    if (!path || !test) throw new Error('Không tải được thông tin lộ trình.');
    var now = (opts && opts.now) || Date.now();
    var requestedGoal = opts && opts.goal;
    var progress = path.progress || {};
    var total = Number(progress.totalSituations) || 0;
    var done = Number(progress.doneSituations) || 0;
    if (test.passed && (!requestedGoal || requestedGoal === path.goal)) return steps(3) + result(test, true, now, opts);
    if (path.status === 'PAUSED') {
      return '<article class="cp-ft__card cp-ft__state"><h2 class="cp-ft__title">Lộ trình này đang tạm dừng</h2>'
        + '<p>Mở Lộ trình của tôi để học tiếp lộ trình này trước khi làm bài kiểm tra cuối. Tiến độ đã học được giữ nguyên.</p>'
        + '<div class="cp-ft__actions">' + button('/learning/my', 'Mở Lộ trình của tôi', 'primary')
        + button(pathHref(opts), 'Về bản đồ lộ trình') + '</div></article>';
    }
    if ((requestedGoal && requestedGoal !== path.goal) || path.role === 'SECONDARY') {
      return '<article class="cp-ft__card cp-ft__state"><h2 class="cp-ft__title">Đây đang là lộ trình phụ</h2>'
        + '<p>Đặt lộ trình này làm lộ trình chính để làm bài kiểm tra cuối. Tiến độ đã học được giữ nguyên.</p>'
        + '<div class="cp-ft__actions">' + button('/learning/comm/path?goal=' + encodeURIComponent(requestedGoal || path.goal), 'Mở lộ trình', 'primary') + '</div></article>';
    }
    if (total <= 0 || done < total) {
      var pct = total > 0 ? Math.round(done * 100 / total) : 0;
      return steps(0) + '<article class="cp-ft__card cp-ft__state"><p class="cp-ft__label">Chưa mở</p>'
        + '<h2 class="cp-ft__title">Học xong tất cả tình huống để mở bài kiểm tra</h2>'
        + '<div class="cp-ft__progress"><span style="width:' + pct + '%"></span></div>'
        + '<p class="cp-ft__meta">Đã xong ' + esc(done) + '/' + esc(total) + ' tình huống</p>'
        + '<div class="cp-ft__actions">' + button(pathHref(opts), 'Tiếp tục học', 'primary') + '</div></article>';
    }
    var retryAt = test.retryAt ? new Date(test.retryAt).getTime() : 0;
    if (test.lastScore != null && (retryAt > now || (opts && opts.justChecked))) return steps(2) + result(test, false, now, opts);
    if (!test.aiAvailable) {
      return steps(1) + '<article class="cp-ft__card cp-ft__state"><h2 class="cp-ft__title">Làm bài nhập vai thay thế</h2>'
        + '<p>Hội thoại với AI đang tạm ngưng. Bài nhập vai thay thế được chấm theo cùng mức điểm.</p>' + previous(test)
        + '<div class="cp-ft__actions">' + button(test.fallbackUrl || '/learning/comm/weekly?mode=fallback', 'Làm bài kiểm tra thay thế', 'primary') + '</div></article>';
    }
    return steps(1) + ready(test);
  }

  var api = { view: view, steps: steps, mascot: function (goal) { return MASCOT[goal] || null; } };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (!root || !root.document) return;

  var el = root.document.getElementById('cpathFinalRoot');
  if (!el) return;
  function loadError() {
    el.innerHTML = '<article class="cp-ft__card cp-ft__state"><h2 class="cp-ft__title">Chưa tải được bài kiểm tra</h2>'
      + '<p>Kết nối đang gặp sự cố. Bạn có thể tải lại trang để thử lại.</p>'
      + '<div class="cp-ft__actions">' + button(root.location.pathname + root.location.search, 'Thử tải lại', 'primary')
      + button('/learning/comm/path', 'Về lộ trình giao tiếp') + '</div></article>';
  }
  if (!root.JpathApi) { loadError(); return; }
  var loadTimer = root.setTimeout(loadError, 25000);
  var query = new URLSearchParams(root.location.search);
  var goal = query.get('goal');
  var backEl = root.document.querySelector('.jp-back[href^="/learning/comm/path"]');
  if (backEl && goal) backEl.setAttribute('href', pathHref({ goal: goal }));
  var suffix = goal ? '?goal=' + encodeURIComponent(goal) : '';
  var justChecked = query.has('check');
  var checked = justChecked && root.JpathApi.send
    ? Promise.resolve(root.JpathApi.send('POST', '/api/learning/comm/goal-test/check' + suffix, {})).catch(function () { return null; })
    : Promise.resolve();
  checked.then(function () {
    return Promise.all([root.JpathApi.get('/api/learning/comm/goal-test' + suffix), root.JpathApi.get('/api/learning/comm/path' + suffix)]);
  }).then(function (data) {
    root.clearTimeout(loadTimer);
    var path = data[1] || {};
    var goalEl = root.document.getElementById('cpFtGoal');
    if (goalEl && path.nameVi) { goalEl.textContent = path.nameVi; goalEl.hidden = false; }
    var pic = root.document.getElementById('cpFtMascot');
    if (pic && MASCOT[path.goal]) pic.src = MASCOT[path.goal];
    el.innerHTML = view(data[0], path, { goal: goal, justChecked: justChecked });
  }).catch(function () {
    root.clearTimeout(loadTimer);
    loadError();
  });
}(typeof window !== 'undefined' ? window : null));
