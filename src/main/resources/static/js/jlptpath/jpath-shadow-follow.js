/* [JLAS-W0108] jpath-shadow-follow.js — "Luyện nói theo" (Plan 3 Task 21, Task 1 branch B: the path's own small screen).
 *
 * window.JpathShadowFollow = { hasMicrophone(env), panelHtml(text, hasMic), open(el, {src, text, env}) }
 *   - Plays the sentence, records the learner with MediaRecorder and plays the recording back to compare. The
 *     recording stays in the browser: nothing is sent and nothing is scored. Without a microphone (or MediaRecorder)
 *     the record button is hidden and listening stays.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathShadowFollow = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** True when the browser can record: getUserMedia and MediaRecorder. */
  function hasMicrophone(env) {
    var e = env || root || {};
    var nav = e.navigator;
    return !!(nav && nav.mediaDevices && typeof nav.mediaDevices.getUserMedia === 'function'
      && typeof e.MediaRecorder === 'function');
  }

  function panelHtml(text, hasMic) {
    return '<div class="jp-follow" role="group" aria-label="Luyện nói theo">'
      + '<p lang="ja">' + esc(text) + '</p>'
      + '<button type="button" class="jp-btn" data-follow="listen">▶ Nghe câu</button>'
      + (hasMic ? ' <button type="button" class="jp-btn" data-follow="record">● Ghi âm</button>'
        + ' <button type="button" class="jp-btn" data-follow="mine" disabled>▶ Nghe lại giọng mình</button>' : '')
      + '<p class="jp-muted">' + (hasMic ? 'Bản ghi chỉ ở trên máy này, không gửi đi và không chấm điểm.'
        : 'Máy chưa có micro: bạn vẫn nghe và nói theo được.') + '</p></div>';
  }

  /** Draws the panel into {@code el} and wires it. */
  function open(el, o) {
    var opts = o || {};
    var env = opts.env || root;
    var mic = hasMicrophone(env);
    el.innerHTML = panelHtml(opts.text, mic);
    var sentence = env.document ? env.document.createElement('audio') : null;
    if (sentence) sentence.src = opts.src || '';
    var recorder = null;
    var chunks = [];
    var mineUrl = null;
    el.addEventListener('click', function (ev) {
      var b = ev.target.closest ? ev.target.closest('[data-follow]') : null;
      if (!b) return;
      var what = b.getAttribute('data-follow');
      if (what === 'listen' && sentence) { sentence.currentTime = 0; sentence.play(); return; }
      if (what === 'mine' && mineUrl && env.document) { var a = env.document.createElement('audio'); a.src = mineUrl; a.play(); return; }
      if (what === 'record' && mic) {
        if (recorder && recorder.state === 'recording') { recorder.stop(); b.textContent = '● Ghi âm'; return; }
        env.navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
          chunks = [];
          recorder = new env.MediaRecorder(stream);
          recorder.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };
          recorder.onstop = function () {
            stream.getTracks().forEach(function (t) { t.stop(); });
            if (mineUrl && env.URL) env.URL.revokeObjectURL(mineUrl);
            mineUrl = env.URL ? env.URL.createObjectURL(new env.Blob(chunks)) : null;
            var mine = el.querySelector('[data-follow="mine"]');
            if (mine) mine.disabled = !mineUrl;
          };
          recorder.start();
          b.textContent = '■ Dừng ghi';
        }, function () {
          b.disabled = true;
          b.textContent = 'Không dùng được micro';
        });
      }
    });
  }

  return { hasMicrophone: hasMicrophone, panelHtml: panelHtml, open: open };
});
