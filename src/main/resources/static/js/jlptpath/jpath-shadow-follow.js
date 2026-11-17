/* [JLAS-W0108] jpath-shadow-follow.js — "Luyện nói theo" (Plan 3 Task 21, Task 1 branch B: the path's own small screen).
 *
 * window.JpathShadowFollow = { hasMicrophone(env), panelHtml(text, hasMic), scoreHtml(result), open(el, {src, text, env, api}) }
 *   - Plays the sentence, records the learner with MediaRecorder and plays the recording back to compare. "Chấm phát
 *     âm" (owner 04/11) sends the recording and the sentence to POST /api/learning/jlpt/speak (Azure pronunciation
 *     assessment, 30 scored attempts per day) and shows the score, three sub-scores and the words to practise again
 *     (scoreHtml). Without a microphone (or MediaRecorder) the record button is hidden and listening stays.
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
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
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
      + '<button type="button" class="jp-btn" data-follow="listen">' + icon('play') + ' Nghe câu</button>'
      + (hasMic ? ' <button type="button" class="jp-btn" data-follow="record">' + icon('circle') + ' Ghi âm</button>'
        + ' <button type="button" class="jp-btn" data-follow="mine" disabled>' + icon('play') + ' Nghe lại giọng mình</button>'
        + ' <button type="button" class="jp-btn jp-btn--primary" data-follow="score" disabled>' + icon('target') + ' Chấm phát âm</button>' : '')
      + '<p class="jp-muted" data-follow-note>' + (hasMic ? 'Ghi âm xong thì bấm Chấm phát âm để xem điểm và các chữ cần luyện lại.'
        : 'Máy chưa có micro: bạn vẫn nghe và nói theo được.') + '</p>'
      + '<div class="jp-follow__score" data-follow-score aria-live="polite"></div></div>';
  }

  function bar(label, value) {
    var v = typeof value === 'number' ? Math.max(0, Math.min(100, Math.round(value))) : null;
    return '<div class="jp-follow__bar"><span>' + esc(label) + '</span><span class="jp-follow__track"><span style="width:'
      + (v == null ? 0 : v) + '%"></span></span><b>' + (v == null ? '—' : v) + '</b></div>';
  }

  /** The score of one attempt: overall, accuracy / fluency / completeness, the words to practise in red. */
  function scoreHtml(r) {
    if (!r) return '';
    var total = typeof r.pronunciation === 'number' ? Math.round(r.pronunciation) : null;
    var tone = total == null ? '' : (total >= 80 ? ' is-good' : (total >= 60 ? ' is-ok' : ' is-low'));
    var words = (r.words || []).filter(function (w) { return w && w.word; }).map(function (w) {
      var bad = (w.errorType && w.errorType !== 'None') || (typeof w.accuracy === 'number' && w.accuracy < 70);
      return '<span class="jp-follow__w' + (bad ? ' is-bad' : '') + '" lang="ja" title="' + esc((w.accuracy == null ? '' : Math.round(w.accuracy) + ' điểm')
        + (w.errorType && w.errorType !== 'None' ? ' · ' + ({ Mispronunciation: 'phát âm sai', Omission: 'bị bỏ sót', Insertion: 'thừa' }[w.errorType] || w.errorType) : ''))
        + '">' + esc(w.word) + '</span>';
    }).join('');
    return '<div class="jp-follow__result' + tone + '"><div class="jp-follow__total"><b>' + (total == null ? '—' : total)
      + '</b><span>/100</span></div><div class="jp-follow__bars">' + bar('Chính xác', r.accuracy) + bar('Trôi chảy', r.fluency)
      + bar('Đầy đủ', r.completeness) + '</div></div>'
      + (words ? '<p class="jp-follow__words">' + words + '</p>' : '')
      + (r.feedbackVi ? '<p>' + esc(r.feedbackVi) + '</p>' : '')
      + '<p class="jp-muted">' + esc('Còn ' + (r.attemptsLeft == null ? '' : r.attemptsLeft) + ' lượt chấm hôm nay.') + '</p>';
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
    var mineBlob = null;
    el.addEventListener('click', function (ev) {
      var b = ev.target.closest ? ev.target.closest('[data-follow]') : null;
      if (!b) return;
      var what = b.getAttribute('data-follow');
      if (what === 'listen' && sentence) { sentence.currentTime = 0; sentence.play(); return; }
      if (what === 'mine' && mineUrl && env.document) { var a = env.document.createElement('audio'); a.src = mineUrl; a.play(); return; }
      if (what === 'score' && mineBlob) {
        var client = opts.api || env.JpathApi;
        var out = el.querySelector('[data-follow-score]');
        if (!client || typeof client.upload !== 'function' || !env.FormData) return;
        b.disabled = true;
        if (out) out.innerHTML = '<p class="jp-muted">Đang chấm…</p>';
        // F72: send WAV 16 kHz mono so the server scores without ffmpeg
        var wav = env.JpathWav ? env.JpathWav.toWav(mineBlob, env)
          : Promise.resolve({ blob: mineBlob, name: 'speak.' + (/ogg/.test(mineBlob.type) ? 'ogg' : (/mp4|m4a/.test(mineBlob.type) ? 'm4a' : 'webm')) });
        wav.then(function (clip) {
          var form = new env.FormData();
          form.append('text', opts.text || '');
          form.append('audio', clip.blob, clip.name);
          return client.upload('/api/learning/jlpt/speak', form);
        }).then(function (r) {
          if (out) out.innerHTML = scoreHtml(r);
          b.disabled = false;
        }, function (e) {
          if (out) out.innerHTML = '<p class="jp-alert" role="alert">' + esc((e && e.message) || 'Chưa chấm được phát âm.') + '</p>';
          b.disabled = !(e && e.status === 429) ? false : true;
        });
        return;
      }
      if (what === 'record' && mic) {
        if (recorder && recorder.state === 'recording') { recorder.stop(); b.innerHTML = icon('circle') + ' Ghi âm'; return; }
        env.navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
          chunks = [];
          recorder = new env.MediaRecorder(stream);
          recorder.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };
          recorder.onstop = function () {
            stream.getTracks().forEach(function (t) { t.stop(); });
            if (mineUrl && env.URL) env.URL.revokeObjectURL(mineUrl);
            mineBlob = new env.Blob(chunks, { type: (recorder && recorder.mimeType) || 'audio/webm' });
            mineUrl = env.URL ? env.URL.createObjectURL(mineBlob) : null;
            var mine = el.querySelector('[data-follow="mine"]');
            if (mine) mine.disabled = !mineUrl;
            var scoreBtn = el.querySelector('[data-follow="score"]');
            if (scoreBtn) scoreBtn.disabled = !mineBlob || !mineBlob.size;
          };
          recorder.start();
          b.innerHTML = icon('pause') + ' Dừng ghi';
        }, function () {
          b.disabled = true;
          b.textContent = 'Không dùng được micro';
        });
      }
    });
  }

  return { hasMicrophone: hasMicrophone, panelHtml: panelHtml, scoreHtml: scoreHtml, open: open };
});
