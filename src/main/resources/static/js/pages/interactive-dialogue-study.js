(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveDialogueStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0193] Task 7 — Dialogue study page: read-only canonical script + server-timed completion. Unlike
  // Vocabulary/Kanji/Grammar there is no micro-check here — completion is server-computed from periodic
  // visible-heartbeat signals (this file never sends a duration or a "completed" flag, only an idempotency
  // token) plus a dedicated reached-bottom event. The server owns accumulated time, the cap/reset arithmetic
  // and the completion decision throughout; this file only renders what the API returns.

  var HEARTBEAT_INTERVAL_MS = 15000;

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  function list(value) { return Array.isArray(value) ? value : []; }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  function viewModel(data) {
    data = data || {};
    var lines = list(data.lines).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    var required = num(data.requiredSeconds) || 180;
    var accumulated = num(data.accumulatedSeconds);
    var percent = required ? Math.min(100, Math.round(accumulated / required * 100)) : 0;
    return {
      activityId: data.activityId, activityState: data.activityState, sourceRevision: data.sourceRevision,
      requiredSeconds: required, accumulatedSeconds: accumulated,
      remainingSeconds: Math.max(0, num(data.remainingSeconds)), reachedBottom: !!data.reachedBottom,
      completed: !!data.completed, percent: percent, lines: lines
    };
  }

  /** Formats a remaining-seconds count as a short, friendly Vietnamese duration, e.g. "1 phút 42 giây". */
  function formatRemaining(seconds) {
    seconds = Math.max(0, Math.round(num(seconds)));
    var m = Math.floor(seconds / 60);
    var s = seconds % 60;
    if (m <= 0) return s + ' giây';
    return m + ' phút' + (s ? ' ' + s + ' giây' : '');
  }

  function renderLine(line) {
    var side = line.bubbleSide === 'phai' ? ' is-right' : ' is-left';
    var audio = line.audioUrl
      ? '<button type="button" class="ilp-dialogue__audio" data-play-audio="' + esc(line.audioUrl) +
        '" aria-label="Phát âm thanh lời thoại' + (line.roleName ? ' của ' + esc(line.roleName) : '') + '">' +
        '<span aria-hidden="true">🔊</span></button>'
      : '';
    return '<li class="ilp-dialogue__line' + side + '" data-line="' + line.lineId + '">' +
      (line.roleName ? '<span class="ilp-dialogue__role">' + esc(line.roleName) + '</span>' : '') +
      '<div class="ilp-dialogue__bubble"><p class="ilp-dialogue__ja" lang="ja">' + esc(line.japanese) + '</p>' +
      (line.meaningVi ? '<p class="ilp-dialogue__vi">' + esc(line.meaningVi) + '</p>' : '') + audio +
      '</div></li>';
  }

  function renderScript(v) {
    if (!v.lines.length) {
      return '<section class="ilp-state"><h1>Chưa có kịch bản hội thoại</h1><p>Hoạt động này chưa có nội dung.</p></section>';
    }
    var lines = v.lines.map(renderLine).join('');
    return '<section class="ilp-dialogue">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">HỘI THOẠI // ' +
      (v.completed ? 'ĐÃ HOÀN THÀNH' : 'ĐANG LUYỆN TẬP') + '</span><h1>Luyện hội thoại</h1>' +
      '<p>Đọc và nghe toàn bộ đoạn hội thoại, cuộn tới cuối để hoàn thành.</p></div></header>' +
      '<ol class="ilp-dialogue__script">' + lines + '</ol>' +
      '<div id="dialogueBottomSentinel" class="ilp-dialogue__sentinel" aria-hidden="true"></div>' +
      '</section>';
  }

  /** The friendly nudge text is derived purely from server-reported numbers — never invents progress. */
  function renderProgress(v) {
    var note = v.completed
      ? 'Bạn đã hoàn thành phần hội thoại này.'
      : v.remainingSeconds > 0
        ? 'Hãy nán lại thêm ' + formatRemaining(v.remainingSeconds) + ' để luyện hội thoại nhé.'
        : v.reachedBottom
          ? 'Đang xác nhận hoàn thành...'
          : 'Hãy cuộn xuống hết đoạn hội thoại để hoàn thành.';
    return '<div class="ilp-dialogue__progress" role="status" aria-live="polite">' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.accumulatedSeconds +
      '" aria-valuemin="0" aria-valuemax="' + v.requiredSeconds + '">' +
      '<span style="width:' + v.percent + '%"></span></div>' +
      '<p class="ilp-dialogue__note">' + esc(note) + '</p>' +
      '<ul class="ilp-dialogue__evidence">' +
      '<li class="' + (v.reachedBottom ? 'is-done' : '') + '">Đã cuộn đến cuối</li>' +
      '<li class="' + (v.accumulatedSeconds >= v.requiredSeconds ? 'is-done' : '') + '">Đủ ' +
      v.requiredSeconds + ' giây hoạt động</li></ul></div>';
  }

  function renderCompleted() {
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>Phần Hội thoại đã đạt</h1><p>Bạn đã đọc đủ thời gian và cuộn hết đoạn hội thoại.</p></div></header>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  function renderReplay(mode, v) {
    var title = mode === 'REVIEW' ? 'Xem lại hội thoại' : 'Luyện lại hội thoại';
    var lines = v.lines.length ? v.lines.map(renderLine).join('') :
      '<li class="ilp-state">Hoạt động này chưa có nội dung.</li>';
    var StudyMode = typeof window !== 'undefined' ? window.InteractiveStudyMode : null;
    return (StudyMode ? StudyMode.banner(mode) : '') +
      '<section class="ilp-dialogue"><header class="ilp-study__head"><div><span class="ilp-kicker">HỘI THOẠI // ' +
      (mode === 'REVIEW' ? 'XEM LẠI' : 'LUYỆN TẬP') + '</span><h1>' + title + '</h1>' +
      '<p>Đọc lại kịch bản và nghe các dòng có âm thanh.</p></div></header>' +
      '<ol class="ilp-dialogue__script">' + lines + '</ol>' +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở phần Hội thoại</h1><p>' +
      esc(message || 'Vui lòng thử lại sau.') + '</p><button data-retry type="button">Thử lại</button></section>';
  }

  function genToken() {
    return String(Date.now()) + '-' + Math.random().toString(36).slice(2);
  }

  function mount() {
    var node = document.getElementById('interactiveDialogueStudyRoot');
    var id = activityId();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/v2/dialogue' + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động Hội thoại.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/dialogue';
    var StudyMode = window.InteractiveStudyMode;
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var heartbeatTimer = null;
    var observer = null;
    var bottomReported = false;
    var finished = false;

    async function api(path, options) {
      var response = await fetch(base + path, Object.assign({ headers: window.JlasAuth.authHeaders() }, options));
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
      return body.data;
    }

    async function postJson(path, payload) {
      return api(path, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
        body: JSON.stringify(payload || {})
      });
    }

    function stopTracking() {
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
      if (observer) { observer.disconnect(); observer = null; }
    }

    function applyProgress(result) {
      if (finished) return;
      var panel = node.querySelector('.ilp-dialogue__progress');
      var v = viewModel(Object.assign({}, result, { lines: [] }));
      if (panel) panel.outerHTML = renderProgress(v);
      if (result.completed) {
        finished = true;
        stopTracking();
        node.innerHTML = renderCompleted();
      }
    }

    async function sendHeartbeat() {
      if (mode !== 'LEARN' || finished || typeof document === 'undefined' || document.visibilityState !== 'visible') return;
      try { applyProgress(await postJson('/heartbeat', { heartbeatToken: genToken() })); }
      catch (e) { /* transient network errors do not interrupt the reading session */ }
    }

    async function reportBottom() {
      if (mode !== 'LEARN' || finished || bottomReported) return;
      bottomReported = true;
      try { applyProgress(await postJson('/reached-bottom', {})); }
      catch (e) { bottomReported = false; }
    }

    function watchBottom() {
      var sentinel = document.getElementById('dialogueBottomSentinel');
      if (!sentinel || typeof IntersectionObserver === 'undefined') return;
      observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) { if (entry.isIntersecting) reportBottom(); });
      });
      observer.observe(sentinel);
    }

    async function load() {
      node.setAttribute('aria-busy', 'true');
      try {
        if (mode === 'REVIEW' || mode === 'PRACTICE') {
          var replay = await api(mode === 'REVIEW' ? '/review' : '/practice');
          node.innerHTML = renderReplay(mode, viewModel(replay));
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var study = await api('/study');
        var v = viewModel(study);
        if (v.completed) { node.innerHTML = renderCompleted(); finished = true; }
        else {
          node.innerHTML = renderScript(v) + renderProgress(v);
          watchBottom();
          heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);
          sendHeartbeat();
        }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    node.addEventListener('click', function (event) {
      if (event.target.closest('[data-retry]')) return load();
      var playButton = event.target.closest('[data-play-audio]');
      if (playButton && typeof Audio !== 'undefined') {
        try { new Audio(playButton.getAttribute('data-play-audio')).play().catch(function () {}); } catch (e) { /* best-effort */ }
      }
    });

    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') sendHeartbeat();
    });

    load();
  }

  return {
    viewModel: viewModel, formatRemaining: formatRemaining, renderLine: renderLine, renderScript: renderScript,
    renderProgress: renderProgress, renderCompleted: renderCompleted, renderReplay: renderReplay, error: error, mount: mount
  };
});
