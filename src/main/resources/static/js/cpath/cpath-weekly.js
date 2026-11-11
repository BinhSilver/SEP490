/* [JLAS-W0108] cpath-weekly.js — weekly test of the communication path (owner 06/11).
 *
 * GET  /api/learning/comm/weekly → {weekStart, testDay, open, passScore, passed, questions:[{id, ja, reading, audioUrl, options[]}]}
 * POST /api/learning/comm/weekly {answers:{id: optionIndex}} → {score, correct, total, passed, cp, review:[{id, ja, correct, answer}]}
 * One question at a time (Japanese → choose the Vietnamese meaning), then the result with the right answers.
 *
 * window.CpathWeekly = { questionHtml(q, i, n), resultHtml(r), mount(el) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathWeekly = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document.getElementById('cpathWeeklyRoot')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  // ?mode=review: today's spaced review (same page, no CP); otherwise the weekly test
  var REVIEW = !!(root && root.location && /[?&]mode=review\b/.test(String(root.location.search || '')));
  var ROLEPLAY = !!(root && root.location && /[?&]mode=(fallback|topic|quick)(?:&|$)/.test(String(root.location.search || '')));
  var BASE = REVIEW ? '/api/learning/comm/review' : '/api/learning/comm/weekly';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function ddmm(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] : '';
  }

  function questionHtml(q, i, n) {
    var opts = (q.options || []).map(function (o, k) {
      return '<button type="button" class="jp-check-opt" data-weekly-pick="' + k + '"><span class="jp-check-opt__key">'
        + (k + 1) + '</span> ' + esc(o) + '</button>';
    }).join('');
    return '<section class="jp-check" aria-labelledby="cpWeeklyQ">'
      + '<header class="jp-check__head"><span class="jp-check__count">Câu ' + (i + 1) + '/' + n + '</span>'
      + '<div class="jp-check__bar" aria-hidden="true"><span style="display:block;height:100%;width:' + Math.round(100 * i / n)
      + '%;background:var(--jp-primary)"></span></div></header>'
      + '<p class="jp-check__dir">Chọn nghĩa đúng</p>'
      + '<div class="jp-check__prompt"><p id="cpWeeklyQ" class="jp-check__text--mid" lang="ja">' + esc(q.ja) + '</p>'
      + (q.reading && q.reading !== q.ja ? '<p class="jp-check__furi" lang="ja">' + esc(q.reading) + '</p>' : '')
      + (q.audioUrl ? '<button type="button" class="jp-btn" data-weekly-play>Nghe</button>' : '')
      + '</div>'
      + '<div class="jp-check__opts">' + opts + '</div>'
      + '</section>';
  }

  // [JLAS-W0108] C3: one next step under the weekly score (weak items → notebook)
  function weekTipHtml(r) {
    if (REVIEW) return '';
    var wrong = (r.review || []).filter(function (x) { return !x.correct; }).length;
    var tip = !wrong ? 'Tuần này bạn nắm chắc hết các mẫu câu. Giữ nhịp này nhé!'
      : 'Mẫu còn yếu: ' + wrong + ' câu sai bên dưới. Ôn lại trong Sổ tay trước khi sang tuần mới.';
    return '<p class="jp-result__note" data-part="week-tip">' + esc(tip)
      + (wrong ? ' <a href="/learning/comm/notebook">Mở Sổ tay</a>' : '') + '</p>';
  }

  function resultHtml(r, questions, answers) {
    var ok = r.passed === true;
    var qMap = {};
    (questions || []).forEach(function (q) { if (q && q.id != null) qMap[q.id] = q; });
    var ansMap = answers || {};
    var rows = (r.review || []).map(function (x) {
      var q = x.id != null ? qMap[x.id] : null;
      var opts = q && Array.isArray(q.options) ? q.options : null;
      var pickedIdx = x.id != null ? ansMap[x.id] : null;
      var userAns = (opts && pickedIdx != null && opts[pickedIdx] != null)
        ? opts[pickedIdx]
        : (x.correct ? x.answer : '(Chưa đúng)');
      var aiSlot = (x.ja && x.answer)
        ? '<div class="jp-ai-slot" data-jpath-ai-explain="' + esc(JSON.stringify({
          source: 'CPATH_WEEKLY',
          prompt: 'Chọn nghĩa tiếng Việt đúng cho câu: ' + x.ja + (q && q.reading && q.reading !== x.ja ? ' (' + q.reading + ')' : ''),
          options: opts,
          userAnswer: userAns,
          correctAnswer: x.answer
        })) + '"></div>'
        : '';
      return '<li class="jp-review__row' + (x.correct ? '' : ' jp-review__row--picked') + '"><div><span lang="ja">' + esc(x.ja)
        + '</span> · ' + esc(x.answer) + (x.correct ? '' : ' <strong>(sai)</strong>') + '</div>' + aiSlot + '</li>';
    }).join('');
    return '<section class="jp-result" aria-labelledby="cpWeeklyResult">'
      + '<div class="jp-result__score' + (ok ? ' jp-ok' : '') + '">' + esc(r.score) + '%</div>'
      + '<h2 id="cpWeeklyResult" class="jp-result__title" tabindex="-1">'
      + esc(REVIEW ? ('Ôn xong: đúng ' + r.correct + '/' + r.total + ' câu. Mục sai đã vào Sổ tay.') : ok ? ('Đạt kiểm tra tuần: đúng ' + r.correct + '/' + r.total + ' câu.' + (r.cp ? (' +' + r.cp + ' CP.') : ''))
        : ('Đúng ' + r.correct + '/' + r.total + ' câu, cần từ 80% để đạt. Ôn lại các câu sai rồi làm lại nhé.')) + '</h2>'
      + weekTipHtml(r)
      + '<ul class="jp-review">' + rows + '</ul>'
      + '<div class="jp-result__actions">'
      + (ok || REVIEW ? '' : '<button type="button" class="jp-btn" data-weekly-retry>Làm lại</button>')
      + '<a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình giao tiếp</a></div>'
      + '</section>';
  }

  function infoHtml(text) {
    return '<p class="jp-alert" role="status">' + esc(text) + '</p>'
      + '<p><a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình giao tiếp</a></p>';
  }

  function mount(el) {
    var client = root && root.JpathApi;
    if (!el || !client) return null;
    if (ROLEPLAY) return mountRoleplay(el, client);
    var crumb = root.document && root.document.querySelector('.jp-crumbs__here');
    if (crumb && REVIEW) crumb.textContent = 'Ôn tập hôm nay';
    var state = { data: null, i: 0, answers: {}, result: null, speechOnly: false, speechIndex: 0, speechClips: [], recorder: null, stream: null, timer: null, chunks: [] };

    function play() {
      var q = state.data.questions[state.i];
      if (q && q.audioUrl && root.Audio) {
        try { new root.Audio(q.audioUrl).play(); } catch (e) { /* no audio */ }
      }
    }
    function show() {
      var qs = state.data.questions;
      el.innerHTML = questionHtml(qs[state.i], state.i, qs.length);
    }
    function submit() {
      el.innerHTML = '<p class="jp-status" role="status">Đang chấm…</p>';
      Promise.resolve(client.send('POST', BASE, { answers: state.answers })).then(function (r) {
        state.result = r || {};
        el.innerHTML = resultHtml(state.result, state.data && state.data.questions, state.answers)
          + ((state.data.speakingQuestions || []).length
            ? '<button type="button" class="jp-btn" data-weekly-speech-start>Tiếp tục phần Nói</button>' : '');
        var h = el.querySelector('#cpWeeklyResult');
        if (h && h.focus) h.focus();
      }, function () {
        el.innerHTML = infoHtml('Chưa chấm được bài. Thử lại sau.');
      });
    }
    function showSpeech() {
      var questions = state.data.speakingQuestions || [];
      var q = questions[state.speechIndex];
      if (!q) { submitSpeech(); return; }
      el.innerHTML = '<section class="jp-check"><p class="jp-check__dir">Phần Nói · câu ' + (state.speechIndex + 1) + '/' + questions.length
        + '. Phần này không tính vào điểm đạt tuần.</p><p lang="ja" class="jp-check__text--mid">' + esc(q.ja) + '</p>'
        + '<p>' + esc(q.vi) + '</p><button type="button" class="jp-btn" data-weekly-record>Ghi âm</button> '
        + '<button type="button" class="jp-btn" data-weekly-stop hidden>Dừng ghi âm</button> '
        + '<button type="button" class="jp-btn" data-weekly-speech-next disabled>Câu tiếp theo</button> '
        + '<button type="button" class="jp-btn" data-weekly-speech-skip>Bỏ qua phần Nói</button></section>';
    }
    function submitSpeech() {
      var form = new root.FormData();
      state.speechClips.forEach(function (blob) { form.append('audio', blob, 'weekly.webm'); });
      el.innerHTML = '<p class="jp-status" role="status">Đang chấm phần Nói…</p>';
      client.upload(BASE + '/speak', form).then(function (r) {
        el.innerHTML = (state.speechOnly ? '<h2>Ôn câu nói</h2>' : resultHtml(state.result, state.data && state.data.questions, state.answers))
          + '<p>Phần Nói đã chấm ' + esc(r.updated) + ' câu. Câu dưới 70 vào Sổ tay; câu đạt từ 70 được bỏ khỏi Sổ tay.</p>';
      }).catch(function (e) {
        el.innerHTML = (state.speechOnly ? '<h2>Ôn câu nói</h2>' : resultHtml(state.result, state.data && state.data.questions, state.answers))
          + '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Không chấm được phần Nói.') + '</p>';
      });
    }
    function load() {
      return Promise.resolve(client.get(BASE)).then(function (d) {
        state.data = d || {};
        state.i = 0;
        state.answers = {};
        state.speechOnly = false;
        if (!state.data.open) {
          el.innerHTML = infoHtml('Kiểm tra tuần mở vào ' + ddmm(state.data.testDay) + '.');
          return;
        }
        if (!(state.data.questions || []).length) {
          if ((state.data.speakingQuestions || []).length) {
            state.speechOnly = true;
            el.innerHTML = '<p>Hôm nay có câu nói cần ôn lại.</p><button type="button" class="jp-btn" data-weekly-speech-start>Bắt đầu phần Nói</button>';
            return;
          }
          el.innerHTML = infoHtml(REVIEW ? 'Hôm nay chưa có mục cần ôn. Quay lại sau khi học xong tình huống.' : 'Tuần này bạn chưa học tình huống nào nên chưa có bài kiểm tra.');
          return;
        }
        show();
      }, function () {
        el.innerHTML = infoHtml('Chưa tải được bài kiểm tra tuần. Tải lại trang hoặc thử lại sau.');
      });
    }

    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest) return;
      if (t.closest('[data-weekly-play]')) { play(); return; }
      if (t.closest('[data-weekly-retry]')) { load(); return; }
      if (t.closest('[data-weekly-speech-start]')) { state.speechIndex = 0; state.speechClips = []; showSpeech(); return; }
      if (t.closest('[data-weekly-speech-skip]')) {
        if (state.timer) root.clearTimeout(state.timer);
        if (state.recorder && state.recorder.state !== 'inactive') { state.recorder.onstop = null; state.recorder.stop(); }
        if (state.stream) state.stream.getTracks().forEach(function (track) { track.stop(); });
        el.innerHTML = (state.speechOnly ? '<h2>Ôn câu nói</h2>' : resultHtml(state.result))
          + '<p>Đã bỏ qua phần Nói. Điểm trắc nghiệm giữ nguyên.</p>'; return;
      }
      if (t.closest('[data-weekly-speech-next]')) { state.speechIndex++; showSpeech(); return; }
      if (t.closest('[data-weekly-record]')) {
        if (!root.navigator || !root.navigator.mediaDevices || !root.MediaRecorder) {
          el.innerHTML += '<p class="jp-alert">Thiết bị không hỗ trợ micro. Bạn có thể bỏ qua phần Nói.</p>'; return;
        }
        root.navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
          state.stream = stream;
          state.chunks = [];
          state.recorder = new root.MediaRecorder(stream);
          state.recorder.ondataavailable = function (event) { if (event.data && event.data.size) state.chunks.push(event.data); };
          state.recorder.onstop = function () {
            if (state.timer) root.clearTimeout(state.timer);
            state.speechClips.push(new root.Blob(state.chunks, { type: state.recorder.mimeType || 'audio/webm' }));
            stream.getTracks().forEach(function (track) { track.stop(); });
            var next = el.querySelector('[data-weekly-speech-next]'); if (next) next.disabled = false;
          };
          state.recorder.start();
          state.timer = root.setTimeout(function () { if (state.recorder.state !== 'inactive') state.recorder.stop(); }, 10000);
          el.querySelector('[data-weekly-record]').hidden = true;
          el.querySelector('[data-weekly-stop]').hidden = false;
        }).catch(function () { el.innerHTML += '<p class="jp-alert">Không mở được micro. Bạn có thể bỏ qua phần Nói.</p>'; });
        return;
      }
      if (t.closest('[data-weekly-stop]') && state.recorder) {
        state.recorder.stop(); t.closest('[data-weekly-stop]').hidden = true; return;
      }
      var b = t.closest('[data-weekly-pick]');
      if (!b || !state.data) return;
      var q = state.data.questions[state.i];
      state.answers[q.id] = Number(b.getAttribute('data-weekly-pick'));
      state.i++;
      if (state.i >= state.data.questions.length) submit();
      else show();
    });
    return load();
  }

  function mountRoleplay(el, client) {
    var query = new URLSearchParams(String(root.location.search || ''));
    var mode = query.get('mode');
    var crumb = root.document && root.document.querySelector('.jp-crumbs__here');
    if (crumb) crumb.textContent = mode === 'fallback' ? 'Bài nhập vai mục tiêu'
      : mode === 'topic' ? 'Bài nhập vai chủ đề' : 'Luyện nhanh';
    var code = query.get('code') || '';
    var scope = mode === 'fallback' ? 'goal' : mode === 'topic' ? 'topic' : 'situation';
    var base = mode === 'fallback' ? '/api/learning/comm/goal-test/fallback'
      : '/api/learning/comm/roleplay?scope=' + encodeURIComponent(scope) + '&code=' + encodeURIComponent(code);
    var state = { questions: [], goal: '', index: 0, answers: {}, clips: [], recorder: null, stream: null, timer: null, chunks: [], busy: false };
    function error(message) {
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc(message) + '</p><button type="button" class="jp-btn" data-rp-retry>Thử lại</button>';
    }
    function render() {
      var q = state.questions[state.index];
      if (!q) { submit(); return; }
      el.innerHTML = '<section class="jp-check" aria-labelledby="cpRoleplayQ">'
        + '<header class="jp-check__head"><span class="jp-check__count">Câu ' + (state.index + 1) + '/' + state.questions.length + '</span></header>'
        + '<p class="jp-check__dir">Người đối diện nói:</p><div class="jp-check__prompt">'
        + '<p id="cpRoleplayQ" class="jp-check__text--mid" lang="ja">' + esc(q.promptJa) + '</p>'
        + '<p>' + esc(q.promptVi) + '</p>'
        + (q.promptAudioUrl ? '<button type="button" class="jp-btn" data-rp-play>Nghe</button>' : '') + '</div>'
        + (q.speaking
          ? '<p class="jp-check__dir">Đọc to câu đáp:</p><p lang="ja">' + esc(q.replyJa) + '</p>'
            + '<button type="button" class="jp-btn" data-rp-record>Ghi âm</button> '
            + '<button type="button" class="jp-btn" data-rp-stop hidden>Dừng ghi âm</button> '
            + '<button type="button" class="jp-btn" data-rp-next disabled>Câu tiếp theo</button> '
            + '<button type="button" class="jp-btn" data-rp-skip>Bỏ qua phần nói</button>'
          : '<p class="jp-check__dir">Chọn câu đáp phù hợp</p><div class="jp-check__opts">'
            + (q.options || []).map(function (o, i) { return '<button type="button" class="jp-check-opt" data-rp-pick="' + i + '">'
              + '<span class="jp-check-opt__key">' + (i + 1) + '</span> ' + esc(o) + '</button>'; }).join('') + '</div>')
        + '</section>';
    }
    function submit() {
      if (state.busy) return;
      state.busy = true;
      el.innerHTML = '<p class="jp-status" role="status">Đang chấm bài…</p>';
      var form = new root.FormData();
      form.append('answers', JSON.stringify(state.answers));
      state.clips.forEach(function (blob) { form.append('audio', blob, 'roleplay.webm'); });
      client.upload(base, form).then(function (result) {
        state.busy = false;
        el.innerHTML = '<section class="jp-result"><div class="jp-result__score">' + esc(result.score) + '%</div>'
          + '<h2 class="jp-result__title">' + (scope === 'situation' ? 'Luyện nhanh hoàn tất'
            : result.passed ? 'Đã đạt bài nhập vai' : result.missingSpeak
              ? 'Đã đạt bài nhập vai; chủ đề còn thiếu điểm nói' : 'Chưa đạt bài nhập vai') + '</h2>'
          + '<p>Chọn câu: ' + esc(result.choiceScore) + '%.'
          + (scope === 'situation' ? '' : result.speakingSkipped ? ' Chưa chấm phần nói; điểm chỉ tính phần chọn câu.'
            : ' Nói: ' + esc(result.speakingScore) + '%.') + '</p>'
          + '<p>' + (result.cp ? '+' + esc(result.cp) + ' CP.' : 'Bài này không cộng thêm CP.') + '</p>'
          + (scope === 'goal' && result.passed ? '<nav aria-label="Bước học tiếp"><h3>Gợi ý bước tiếp</h3>'
            + '<p><a href="/learning/comm/survey">Chọn mục tiêu giao tiếp khác</a></p>'
            + (state.goal === 'WORK' || state.goal === 'BA' ? '<p><a href="/learning/comm/survey?goal=WORK">Chọn lĩnh vực Công việc khác</a></p>' : '')
            + '<p><a href="/learning/jlpt/start">Bắt đầu lộ trình JLPT</a></p></nav>' : '')
          + '<a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình giao tiếp</a></section>';
      }).catch(function (e) { state.busy = false; error(e && e.message || 'Chưa chấm được bài.'); });
    }
    function load() {
      state.index = 0; state.answers = {}; state.clips = [];
      el.innerHTML = '<p class="jp-status" role="status">Đang tải bài nhập vai…</p>';
      client.get(base).then(function (data) {
        if (!data || !data.open) { error('Bài kiểm tra chưa đến giờ làm lại hoặc đã đạt.'); return; }
        state.questions = data.questions || [];
        state.goal = data.goal || '';
        if (!state.questions.length) { error('Chưa có câu thoại để luyện nhập vai.'); return; }
        render();
      }).catch(function (e) { error(e && e.message || 'Chưa tải được bài nhập vai.'); });
    }
    el.addEventListener('click', function (ev) {
      var target = ev.target;
      if (!target || !target.closest) return;
      if (target.closest('[data-rp-retry]')) { load(); return; }
      var q = state.questions[state.index];
      if (!q) return;
      if (target.closest('[data-rp-play]')) {
        if (q.promptAudioUrl && root.Audio) new root.Audio(q.promptAudioUrl).play();
        return;
      }
      var pick = target.closest('[data-rp-pick]');
      if (pick) { state.answers[q.id] = Number(pick.getAttribute('data-rp-pick')); state.index++; render(); return; }
      if (target.closest('[data-rp-skip]')) {
        if (state.timer) root.clearTimeout(state.timer);
        if (state.recorder && state.recorder.state !== 'inactive') { state.recorder.onstop = null; state.recorder.stop(); }
        if (state.stream) state.stream.getTracks().forEach(function (track) { track.stop(); });
        state.clips = []; state.index = state.questions.length; submit(); return;
      }
      if (target.closest('[data-rp-next]')) { state.index++; render(); return; }
      if (target.closest('[data-rp-record]')) {
        if (!root.navigator || !root.navigator.mediaDevices || !root.MediaRecorder) {
          target.closest('[data-rp-record]').disabled = true; return;
        }
        root.navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
          state.stream = stream;
          state.chunks = [];
          state.recorder = new root.MediaRecorder(stream);
          state.recorder.ondataavailable = function (event) { if (event.data && event.data.size) state.chunks.push(event.data); };
          state.recorder.onstop = function () {
            if (state.timer) root.clearTimeout(state.timer);
            state.clips.push(new root.Blob(state.chunks, { type: state.recorder.mimeType || 'audio/webm' }));
            stream.getTracks().forEach(function (track) { track.stop(); });
            var next = el.querySelector('[data-rp-next]'); if (next) next.disabled = false;
          };
          state.recorder.start();
          state.timer = root.setTimeout(function () { if (state.recorder.state !== 'inactive') state.recorder.stop(); }, 10000);
          el.querySelector('[data-rp-record]').hidden = true;
          el.querySelector('[data-rp-stop]').hidden = false;
        }).catch(function () { var skip = el.querySelector('[data-rp-skip]'); if (skip) skip.focus(); });
        return;
      }
      if (target.closest('[data-rp-stop]') && state.recorder) {
        state.recorder.stop();
        target.closest('[data-rp-stop]').hidden = true;
      }
    });
    return load();
  }

  return { questionHtml: questionHtml, resultHtml: resultHtml, mount: mount };
});
