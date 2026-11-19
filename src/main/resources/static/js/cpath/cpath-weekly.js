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
  /** F72: recordings go up as WAV 16 kHz mono (JpathWav); without it the original blob is sent. */
  function wavAll(blobs) {
    return Promise.all((blobs || []).map(function (b) {
      return root && root.JpathWav ? root.JpathWav.toWav(b) : Promise.resolve({ blob: b, name: 'speech.webm' });
    }));
  }
  // F193: one compact round speaker button instead of a wide "Nghe" text button
  var SPEAKER = '<svg viewBox="0 0 20 20" width="18" height="18" fill="none" aria-hidden="true"><path d="M9.5 4.5L5.8 7.5H3.2v5h2.6l3.7 3v-11z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M12.8 7.2a4 4 0 0 1 0 5.6M15 5.2a7 7 0 0 1 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
  var BASE = REVIEW ? '/api/learning/comm/review' : '/api/learning/comm/weekly';
  if (REVIEW && root && root.document) root.document.title = 'Ôn tập hôm nay - KotsuKotsu'; // F99

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function ddmm(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] : '';
  }

  // [JLAS-W0108] F154: refresh header CP chip after earning CP on weekly/roleplay submit
  function refreshRewardHeader(cp) {
    if (cp && root && root.RewardFX && typeof root.RewardFX.refreshChip === 'function') {
      root.RewardFX.refreshChip(true);
    }
  }

  // F187 (probe P2-1): every attempt shows the questions and their options in a new order, so a retry cannot be
  // passed by copying the previous result page; data-weekly-pick keeps the server's option index
  function shuffled(n, rnd) {
    var a = [];
    for (var i = 0; i < n; i++) a.push(i);
    for (var j = n - 1; j > 0; j--) { var k = Math.floor((rnd || Math.random)() * (j + 1)); var t = a[j]; a[j] = a[k]; a[k] = t; }
    return a;
  }

  function questionHtml(q, i, n, picked) {
    var order = q._order || shuffled((q.options || []).length);
    q._order = order;
    var opts = order.map(function (k, pos) {
      return '<button type="button" class="jp-check-opt' + (picked === k ? ' is-picked' : '') + '" data-weekly-pick="' + k + '"'
        + (picked === k ? ' aria-pressed="true"' : '') + '><span class="jp-check-opt__key">'
        + (pos + 1) + '</span> ' + esc(q.options[k]) + '</button>';
    }).join('');
    return '<section class="jp-check" aria-labelledby="cpWeeklyQ">'
      + '<header class="jp-check__head"><span class="jp-check__count">Câu ' + (i + 1) + '/' + n + '</span>'
      + '<div class="jp-check__bar" aria-hidden="true"><span style="display:block;height:100%;width:' + Math.round(100 * i / n)
      + '%;background:var(--jp-primary)"></span></div></header>'
      + '<p class="jp-check__dir">Chọn nghĩa đúng</p>'
      + '<div class="jp-check__prompt"><p id="cpWeeklyQ" class="jp-check__text--mid" lang="ja">' + esc(q.ja) + '</p>'
      + (q.reading && q.reading !== q.ja ? '<p class="jp-check__furi" lang="ja">' + esc(q.reading) + '</p>' : '')
      + (SHOW_RO ? ro(q.reading || q.ja) : '')
      + (q.audioUrl ? '<button type="button" class="jp-speak-btn" data-weekly-play aria-label="Nghe phát âm" title="Nghe phát âm">' + SPEAKER + '</button>' : '')
      + '</div>'
      + '<div class="jp-check__opts">' + opts + '</div>'
      + (i > 0 ? '<p><button type="button" class="jp-btn" data-weekly-prev>‹ Câu trước</button></p>' : '')
      + '</section>';
  }

  // [JLAS-W0108] C3: one next step under the weekly score (weak items → notebook)
  function weekTipHtml(r) {
    if (REVIEW) return '';
    var wrong = (r.review || []).filter(function (x) { return !x.correct; }).length;
    var tip = !wrong ? 'Tuần này bạn nắm chắc hết các mẫu câu. Giữ nhịp này nhé!'
      : 'Mẫu còn yếu: ' + wrong + ' câu sai bên dưới. Ôn lại trong Sổ tay trước khi sang tuần mới.';
    var score = Number(r.score);
    var support = score < 70
      ? 'Bật Furigana và Bản dịch ở đầu trang tình huống nếu câu Nhật còn khó đọc.'
      : (score >= 90 ? 'Bạn có thể thử tắt Furigana và Bản dịch ở đầu trang tình huống để tự luyện đọc.' : '');
    return '<p class="jp-result__note" data-part="week-tip">' + esc(tip)
      + (wrong ? ' <a href="/learning/comm/notebook">Mở Sổ tay</a>' : '') + '</p>'
      + (support ? '<p class="jp-result__note" data-part="week-support-tip">' + esc(support) + '</p>' : '');
  }

  function resultHtml(r, questions, answers) {
    var ok = r.passed === true;
    var qMap = {};
    (questions || []).forEach(function (q) { if (q && q.id != null) qMap[q.id] = q; });
    var ansMap = answers || {};
    // F183 (AI test L13): wrong answers first, each with what the learner picked next to the right meaning
    var review = (r.review || []).slice().sort(function (a, b) { return (a.correct ? 1 : 0) - (b.correct ? 1 : 0); });
    var rows = review.map(function (x) {
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
      var picked = !x.correct && opts && pickedIdx != null && opts[pickedIdx] != null
        ? '<div class="jp-review__picked">Bạn chọn: <span class="jp-bad">' + esc(opts[pickedIdx]) + '</span></div>' : '';
      return '<li class="jp-review__row' + (x.correct ? '' : ' jp-review__row--picked') + '"><div><span lang="ja">' + esc(x.ja)
        + '</span> · ' + esc(x.answer) + (x.correct ? '' : ' <strong>(sai)</strong>') + '</div>' + ro(x.reading || x.ja) + picked + aiSlot + '</li>';
    }).join('');
    return '<section class="jp-result" aria-labelledby="cpWeeklyResult">'
      + '<div class="jp-result__score' + (ok ? ' jp-ok' : '') + '">' + esc(r.score) + '%</div>'
      + '<h2 id="cpWeeklyResult" class="jp-result__title" tabindex="-1">'
      + esc(REVIEW ? ('Ôn xong: đúng ' + r.correct + '/' + r.total + ' câu.' + (Number(r.correct) < Number(r.total) ? ' Mục sai đã vào Sổ tay.' : ' Không sai câu nào.')) : ok ? ('Đạt kiểm tra tuần: đúng ' + r.correct + '/' + r.total + ' câu.' + (r.cp ? (' +' + r.cp + ' CP.') : ''))
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
    var state = { data: null, i: 0, answers: {}, result: null, speechOnly: false, speechIndex: 0, speechClips: [], recorder: null, stream: null, timer: null, chunks: [] };

    function play() {
      var q = state.data.questions[state.i];
      if (q && q.audioUrl && root.Audio) {
        try {
          if (root.CpathAudio) root.CpathAudio.play(q.audioUrl, q.ja || q.reading || '');
          else new root.Audio(q.audioUrl).play();
        } catch (e) { /* no audio */ }
      }
    }
    // F187 (probe P2-3): F5 mid-test lost every answer; keep a draft for this week in sessionStorage and warn on leave
    function draftKey() { return 'cpWeeklyDraft:' + (REVIEW ? 'review:' : '') + String(state.data && (state.data.weekStart || state.data.testDay) || ''); }
    function saveDraft() {
      try { root.sessionStorage.setItem(draftKey(), JSON.stringify({ i: state.i, answers: state.answers, ids: state.data.questions.map(function (q) { return q.id; }) })); } catch (e) { /* ignore */ }
    }
    function clearDraft() { try { root.sessionStorage.removeItem(draftKey()); } catch (e) { /* ignore */ } }
    function readDraft() {
      try {
        var d = JSON.parse(root.sessionStorage.getItem(draftKey()) || 'null');
        var ids = state.data.questions.map(function (q) { return q.id; });
        if (d && d.answers && Array.isArray(d.ids) && d.ids.slice().sort().join() === ids.slice().sort().join()) return d;
      } catch (e) { /* ignore */ }
      return null;
    }
    state.dirty = false;
    if (root.addEventListener) {
      root.addEventListener('beforeunload', function (ev) {
        if (!state.dirty) return;
        ev.preventDefault();
        ev.returnValue = '';
      });
    }
    function show() {
      var qs = state.data.questions;
      var q = qs[state.i];
      el.innerHTML = questionHtml(q, state.i, qs.length, state.answers[q.id]);
    }
    function submit() {
      el.innerHTML = '<p class="jp-status" role="status">Đang chấm…</p>';
      Promise.resolve(client.send('POST', BASE, { answers: state.answers })).then(function (r) {
        state.dirty = false;
        clearDraft();
        state.result = r || {};
        refreshRewardHeader(state.result.cp);
        el.innerHTML = resultHtml(state.result, state.data && state.data.questions, state.answers)
          + ((state.data.speakingQuestions || []).length
            ? '<button type="button" class="jp-btn" data-weekly-speech-start>Tiếp tục phần Nói</button>' : '');
        var h = el.querySelector('#cpWeeklyResult');
        if (h && h.focus) h.focus();
      }, function (e) {
        // F110: keep the answers; show the server's reason and let the learner send the same answers again
        el.innerHTML = '<p class="jp-alert" role="alert">' + esc(e && (e.code || e.status) && e.message || 'Chưa chấm được bài. Thử lại sau.') + '</p>'
          + '<p><button type="button" class="jp-btn" data-weekly-resubmit>Gửi lại bài</button> '
          + '<a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình giao tiếp</a></p>';
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
      el.innerHTML = '<p class="jp-status" role="status">Đang chấm phần Nói…</p>';
      wavAll(state.speechClips).then(function (clips) {
        var form = new root.FormData();
        clips.forEach(function (c) { form.append('audio', c.blob, c.name); });
        return client.upload(BASE + '/speak', form);
      }).then(function (r) {
        el.innerHTML = (state.speechOnly ? '<h2>Ôn câu nói</h2>' : resultHtml(state.result, state.data && state.data.questions, state.answers))
          + '<p>Phần Nói đã chấm ' + esc(r.updated) + ' câu. Câu dưới 70 vào Sổ tay; câu đạt từ 70 được bỏ khỏi Sổ tay.</p>';
      }).catch(function (e) {
        el.innerHTML = (state.speechOnly ? '<h2>Ôn câu nói</h2>' : resultHtml(state.result, state.data && state.data.questions, state.answers))
          + '<p class="jp-alert" role="alert">' + esc(e && (e.code || e.status) && e.message || 'Không chấm được phần Nói.') + '</p>';
      });
    }
    function load() {
      return Promise.resolve(client.get(BASE)).then(function (d) {
        state.data = d || {};
        if (root.CpathAudio) root.CpathAudio.fromDisplay(state.data.display); // F70: listening speed
        SHOW_RO = !!(state.data.display && (state.data.display.romaji || state.data.display.fullRomaji));
        state.i = 0;
        state.answers = {};
        state.speechOnly = false;
        if (!state.data.open) {
          el.innerHTML = infoHtml('Kiểm tra tuần mở vào ' + ddmm(state.data.testDay) + ', hoặc sớm hơn khi bạn học xong các tình huống của tuần này.');
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
        var qs = state.data.questions;
        var draft = readDraft();
        if (draft) {
          var byId = {};
          qs.forEach(function (q) { byId[q.id] = q; });
          state.data.questions = draft.ids.map(function (id) { return byId[id]; });
          state.answers = draft.answers;
          state.i = Math.min(Math.max(0, Number(draft.i) || 0), qs.length - 1);
          state.dirty = Object.keys(state.answers).length > 0;
        } else {
          state.data.questions = shuffled(qs.length).map(function (k) { return qs[k]; });
        }
        show();
      }, function (e) {
        el.innerHTML = infoHtml(e && (e.code || e.status) && e.message || 'Chưa tải được bài kiểm tra tuần. Tải lại trang hoặc thử lại sau.');
      });
    }

    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest) return;
      if (t.closest('[data-weekly-play]')) { play(); return; }
      if (t.closest('[data-weekly-retry]')) { load(); return; }
      if (t.closest('[data-weekly-resubmit]')) { submit(); return; }
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
      if (t.closest('[data-weekly-prev]')) { if (state.i > 0) { state.i--; saveDraft(); show(); } return; }
      var b = t.closest('[data-weekly-pick]');
      if (!b || !state.data) return;
      var q = state.data.questions[state.i];
      var choice = Number(b.getAttribute('data-weekly-pick'));
      function accept() {
        state.answers[q.id] = choice;
        state.dirty = true;
        state.i++;
        if (state.i >= state.data.questions.length) submit();
        else { saveDraft(); show(); }
      }
      if (!REVIEW) { accept(); return; }
      if (state.picking) return;
      state.picking = true;
      client.send('POST', BASE + '/pick?questionId=' + encodeURIComponent(q.id) + '&optionIndex=' + choice)
        .then(function (r) {
          state.picking = false;
          if (r && r.correct) accept();
          else el.insertAdjacentHTML('beforeend', '<p class="jp-status" role="status">Chưa đúng. Chọn lại để tiếp tục.</p>');
        }, function (e) {
          state.picking = false;
          el.insertAdjacentHTML('beforeend', '<p class="jp-alert" role="alert">' + esc(e && (e.code || e.status) && e.message || 'Chưa kiểm tra được. Thử lại.') + '</p>');
        });
    });
    return load();
  }


  // owner 17/11: romaji wherever Japanese is shown (Hepburn from the reading, same converter as the situation page)
  function ro(text) {
    var S = root && root.CpathSituation;
    var r = S && typeof S.toRomaji === 'function' ? S.toRomaji(text || '') : '';
    return r ? '<span class="cp-romaji">' + esc(r) + '</span>' : '';
  }
  var SHOW_RO = false;

  // F195 (L11): furigana text "{漢字|かんじ}" or "漢字(かんじ)" → ruby when the learner shows furigana, plain text otherwise
  function ruby(text, show) {
    var src = String(text == null ? '' : text);
    var rx = /\{([^|{}]+)\|([^{}]+)\}|([㐀-鿿々ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g;
    var out = '', last = 0, m;
    while ((m = rx.exec(src)) !== null) {
      var base = m[1] != null ? m[1] : m[3];
      var rt = m[1] != null ? m[2] : m[4];
      out += esc(src.slice(last, m.index)) + (show && base !== rt ? '<ruby>' + esc(base) + '<rt>' + esc(rt) + '</rt></ruby>' : esc(base));
      last = rx.lastIndex;
    }
    return out + esc(src.slice(last));
  }

  // F195 (L11): the quick-practice result teaches — wrong lines first, each with what the learner picked and the right
  // reply; same row markup as the weekly check result (jp-review__row) so both result pages look alike
  function roleplayReviewHtml(review, questions, answers) {
    var byId = {};
    (questions || []).forEach(function (q) { if (q && q.id != null) byId[q.id] = q; });
    var rows = (review || []).slice().sort(function (a, b) { return (a.correct ? 1 : 0) - (b.correct ? 1 : 0); });
    if (!rows.length) return '';
    return '<ul class="jp-review" aria-label="Xem lại từng câu">' + rows.map(function (x) {
      var q = byId[x.id] || {};
      var idx = answers ? answers[x.id] : null;
      var picked = idx != null && q.options ? q.options[idx] : '';
      var aiSlot = x.ja && x.answer
        ? '<div class="jp-ai-slot" data-jpath-ai-explain="' + esc(JSON.stringify({
          source: 'CPATH_ROLEPLAY',
          contextText: q.promptVi || '',
          prompt: q.promptJa || x.ja,
          options: q.options || [],
          userAnswer: picked || '',
          correctAnswer: x.answer
        })) + '"></div>' : '';
      return '<li class="jp-review__row' + (x.correct ? '' : ' jp-review__row--picked') + '">'
        + '<div><span lang="ja">' + esc(x.ja) + '</span>' + (x.correct ? '' : ' <strong>(sai)</strong>') + '</div>' + ro(x.ja)
        + (x.correct ? '<div class="jp-review__picked">Đáp: <span lang="ja">' + esc(x.answer) + '</span></div>'
          : (picked ? '<div class="jp-review__picked">Bạn chọn: <span class="jp-bad" lang="ja">' + esc(picked) + '</span></div>' : '')
            + '<div class="jp-review__picked">Đáp án: <strong lang="ja">' + esc(x.answer) + '</strong></div>')
        + aiSlot
        + '</li>';
    }).join('') + '</ul>';
  }

  function mountRoleplay(el, client) {
    var query = new URLSearchParams(String(root.location.search || ''));
    var mode = query.get('mode');
    var code = query.get('code') || '';
    var scope = mode === 'fallback' ? 'goal' : mode === 'topic' ? 'topic' : 'situation';
    var base = mode === 'fallback' ? '/api/learning/comm/goal-test/fallback'
      : '/api/learning/comm/roleplay?scope=' + encodeURIComponent(scope) + '&code=' + encodeURIComponent(code);
    // F99: the page title says which test this is (the template's title is the weekly test's)
    if (root.document) {
      root.document.title = (scope === 'goal' ? 'Bài test cuối mục tiêu' : scope === 'topic' ? 'Nhập vai cuối chủ đề' : 'Luyện nhanh tình huống')
        + ' - KotsuKotsu';
    }
    var state = { questions: [], goal: '', index: 0, answers: {}, clips: [], recorder: null, stream: null, timer: null, chunks: [], busy: false, picking: false };
    function error(message) {
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc(message) + '</p><button type="button" class="jp-btn" data-rp-retry>Thử lại</button>';
    }
    function render() {
      var q = state.questions[state.index];
      if (!q) { submit(); return; }
      el.innerHTML = '<section class="jp-check" aria-labelledby="cpRoleplayQ">'
        + '<header class="jp-check__head"><span class="jp-check__count">Câu ' + (state.index + 1) + '/' + state.questions.length + '</span></header>'
        + '<p class="jp-check__dir">Người đối diện nói:</p><div class="jp-check__prompt">'
        + '<p id="cpRoleplayQ" class="jp-check__text--mid" lang="ja">' + ruby(q.promptFurigana || q.promptJa, state.furigana) + '</p>'
        + '<p>' + esc(q.promptVi) + '</p>'
        + (q.promptAudioUrl ? '<button type="button" class="jp-speak-btn" data-rp-play aria-label="Nghe câu này" title="Nghe câu này">' + SPEAKER + '</button>' : '') + '</div>'
        + (q.speaking
          ? '<p class="jp-check__dir">Đọc to câu đáp:</p><p lang="ja">' + esc(q.replyJa) + '</p>'
            + '<button type="button" class="jp-btn" data-rp-record>Ghi âm</button> '
            + '<button type="button" class="jp-btn" data-rp-stop hidden>Dừng ghi âm</button> '
            + '<button type="button" class="jp-btn" data-rp-next disabled>Câu tiếp theo</button> '
            + '<button type="button" class="jp-btn" data-rp-skip>Bỏ qua phần nói</button>'
          : '<p class="jp-check__dir">Chọn câu đáp phù hợp</p><div class="jp-check__opts">'
            + (q.options || []).map(function (o, i) { return '<button type="button" class="jp-check-opt" lang="ja" data-rp-pick="' + i + '">'
              + '<span class="jp-check-opt__key">' + (i + 1) + '</span> <span class="cp-rp-opt">' + ruby((q.optionsFurigana && q.optionsFurigana[i]) || o, state.furigana) + '</span></button>'; }).join('') + '</div>')
        + '</section>';
    }
    function submit() {
      if (state.busy) return;
      state.busy = true;
      el.innerHTML = '<p class="jp-status" role="status">Đang chấm bài…</p>';
      wavAll(state.clips).then(function (clips) {
        var form = new root.FormData();
        form.append('answers', JSON.stringify(state.answers));
        clips.forEach(function (c) { form.append('audio', c.blob, c.name); });
        return client.upload(base, form);
      }).then(function (result) {
        state.busy = false;
        refreshRewardHeader(result && result.cp);
        var ok = scope === 'situation' ? Number(result.score) >= 70 : result.passed === true;
        var wrong = (result.review || []).filter(function (x) { return !x.correct; }).length;
        el.innerHTML = '<section class="jp-result" aria-labelledby="cpRoleplayResult">'
          + '<div class="jp-result__score' + (ok ? ' jp-ok' : '') + '">' + esc(result.score) + '%</div>'
          + '<h2 id="cpRoleplayResult" class="jp-result__title" tabindex="-1">' + (scope === 'situation' ? 'Luyện nhanh hoàn tất'
            : result.passed ? 'Đã đạt bài nhập vai' : result.missingSpeak
              ? 'Đã đạt bài nhập vai; chủ đề còn thiếu điểm nói' : 'Chưa đạt bài nhập vai') + '</h2>'
          + '<p class="jp-result__note">Chọn câu: ' + esc(result.choiceScore) + '%.'
          + (scope === 'situation' ? '' : result.speakingSkipped ? ' Chưa chấm phần nói; điểm chỉ tính phần chọn câu.'
            : ' Nói: ' + esc(result.speakingScore) + '%.')
          + ' ' + (result.cp ? '+' + esc(result.cp) + ' CP.' : 'Bài này không cộng thêm CP.') + '</p>'
          + '<p class="jp-result__note">' + (wrong ? 'Sai ' + wrong + ' câu — xem lại bên dưới.' : 'Không sai câu nào.') + '</p>'
          + roleplayReviewHtml(result.review, state.questions, state.answers)
          + (scope === 'goal' && result.passed ? '<nav class="jp-result__note" aria-label="Bước học tiếp"><h3>Gợi ý bước tiếp</h3>'
            + '<p><a href="/learning/comm/survey">Chọn mục tiêu giao tiếp khác</a></p>'
            + (state.goal === 'WORK' || state.goal === 'BA' ? '<p><a href="/learning/comm/survey?goal=WORK">Chọn lĩnh vực Công việc khác</a></p>' : '')
            + '<p><a href="/learning/jlpt/start">Bắt đầu lộ trình JLPT</a></p></nav>' : '')
          + '<div class="jp-result__actions">'
          + (scope === 'situation' || !result.passed ? '<button type="button" class="jp-btn" data-rp-retry>Làm lại</button>' : '')
          + '<a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình giao tiếp</a></div></section>';
        var head = el.querySelector('#cpRoleplayResult');
        if (head && head.focus) head.focus();
      }).catch(function (e) { state.busy = false; error(e && (e.code || e.status) && e.message || 'Chưa chấm được bài.'); });
    }
    function load() {
      state.index = 0; state.answers = {}; state.clips = [];
      el.innerHTML = '<p class="jp-skel jp-skel--list" role="status" aria-busy="true"><span class="jp-skel__sr">Đang tải bài nhập vai…</span><i class="jp-skel__b jp-skel__b--title"></i><i class="jp-skel__b jp-skel__b--line"></i><i class="jp-skel__b jp-skel__b--line"></i><i class="jp-skel__b jp-skel__b--short"></i></p>';
      client.get(base).then(function (data) {
        if (!data || !data.open) { error('Bài kiểm tra chưa đến giờ làm lại hoặc đã đạt.'); return; }
        state.questions = data.questions || [];
        state.goal = data.goal || '';
        state.furigana = !data.display || data.display.furigana !== false;
        SHOW_RO = !!(data.display && (data.display.romaji || data.display.fullRomaji));
        if (!state.questions.length) { error('Chưa có câu thoại để luyện nhập vai.'); return; }
        render();
      }).catch(function (e) { error(e && (e.code || e.status) && e.message || 'Chưa tải được bài nhập vai.'); });
    }
    el.addEventListener('click', function (ev) {
      var target = ev.target;
      if (!target || !target.closest) return;
      if (target.closest('[data-rp-retry]')) { load(); return; }
      var q = state.questions[state.index];
      if (!q) return;
      if (target.closest('[data-rp-play]')) {
        if (q.promptAudioUrl && root.CpathAudio) root.CpathAudio.play(q.promptAudioUrl, q.ja || '');
        else if (q.promptAudioUrl && root.Audio) new root.Audio(q.promptAudioUrl).play();
        return;
      }
      var pick = target.closest('[data-rp-pick]');
      if (pick) {
        if (state.picking) return;
        var optionIndex = Number(pick.getAttribute('data-rp-pick'));
        if (scope !== 'situation') {
          state.answers[q.id] = optionIndex; state.index++; render(); return;
        }
        state.picking = true;
        el.querySelectorAll('[data-rp-pick]').forEach(function (button) { button.disabled = true; });
        client.send('POST', '/api/learning/comm/roleplay/pick?code=' + encodeURIComponent(code)
          + '&questionId=' + encodeURIComponent(q.id) + '&optionIndex=' + optionIndex).then(function (verdict) {
          state.picking = false;
          if (verdict && verdict.correct) {
            state.answers[q.id] = optionIndex; state.index++; render();
          } else {
            render();
            el.insertAdjacentHTML('beforeend', '<p class="jp-status" role="status">Chưa đúng. Chọn lại để tiếp tục.</p>');
          }
        }, function (err) {
          state.picking = false;
          render();
          el.insertAdjacentHTML('beforeend', '<p class="jp-alert" role="alert">' + esc(err && (err.code || err.status) && err.message || 'Chưa kiểm tra được. Thử lại.') + '</p>');
        });
        return;
      }
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
