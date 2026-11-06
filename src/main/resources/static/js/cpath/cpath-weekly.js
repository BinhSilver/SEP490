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

  var BASE = '/api/learning/comm/weekly';

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

  function resultHtml(r) {
    var ok = r.passed === true;
    var rows = (r.review || []).map(function (x) {
      return '<li class="jp-review__row' + (x.correct ? '' : ' jp-review__row--picked') + '"><span lang="ja">' + esc(x.ja)
        + '</span> · ' + esc(x.answer) + (x.correct ? '' : ' <strong>(sai)</strong>') + '</li>';
    }).join('');
    return '<section class="jp-result" aria-labelledby="cpWeeklyResult">'
      + '<div class="jp-result__score' + (ok ? ' jp-ok' : '') + '">' + esc(r.score) + '%</div>'
      + '<h2 id="cpWeeklyResult" class="jp-result__title" tabindex="-1">'
      + esc(ok ? ('Đạt kiểm tra tuần: đúng ' + r.correct + '/' + r.total + ' câu.' + (r.cp ? (' +' + r.cp + ' CP.') : ''))
        : ('Đúng ' + r.correct + '/' + r.total + ' câu, cần từ 80% để đạt. Ôn lại các câu sai rồi làm lại nhé.')) + '</h2>'
      + '<ul class="jp-review">' + rows + '</ul>'
      + '<div class="jp-result__actions">'
      + (ok ? '' : '<button type="button" class="jp-btn" data-weekly-retry>Làm lại</button>')
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
    var state = { data: null, i: 0, answers: {} };

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
        el.innerHTML = resultHtml(r || {});
        var h = el.querySelector('#cpWeeklyResult');
        if (h && h.focus) h.focus();
      }, function () {
        el.innerHTML = infoHtml('Chưa chấm được bài. Thử lại sau.');
      });
    }
    function load() {
      return Promise.resolve(client.get(BASE)).then(function (d) {
        state.data = d || {};
        state.i = 0;
        state.answers = {};
        if (!state.data.open) {
          el.innerHTML = infoHtml('Kiểm tra tuần mở vào ' + ddmm(state.data.testDay) + '.');
          return;
        }
        if (!(state.data.questions || []).length) {
          el.innerHTML = infoHtml('Tuần này bạn chưa học tình huống nào nên chưa có bài kiểm tra.');
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

  return { questionHtml: questionHtml, resultHtml: resultHtml, mount: mount };
});
