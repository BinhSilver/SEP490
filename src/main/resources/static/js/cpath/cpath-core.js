/*
 * [JLAS-W0108] F70 (owner 12/11): "Bài nền" of the communication path — today's ~10 words and patterns taken from the
 * JLPT store, learnt right before today's situation, which uses them.
 *   GET  /api/learning/comm/core/lesson   items {code, kind VOCAB|GRAMMAR|KEIGO, ja, reading, romaji, meaningVi, note,
 *                                         audioUrl, example, options[4], done}
 *   POST /api/learning/comm/core/lesson   {answers:{code: meaning}} → each right answer = learnt (also on the JLPT path)
 *   GET  /api/learning/comm/core/check    ?check=1 — "Đã biết – ôn nhanh": items below the Smart Test level
 *   POST /api/learning/comm/core/check    ≥ 80 % → all of them count as known; otherwise they come in the lessons
 * Two phases per lesson: Học (cards) → Kiểm tra nhanh (pick the meaning); wrong items stay for "Học lại phần sai".
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathCore = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document.getElementById('cpathCoreRoot')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var KIND_LABEL = { VOCAB: 'Từ', GRAMMAR: 'Mẫu câu', KEIGO: 'Kính ngữ' };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** "私(わたし)は" → ruby when furigana is on, plain text otherwise. */
  function ruby(text, on) {
    var s = String(text == null ? '' : text);
    var rx = /([㐀-鿿々〆ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g;
    if (!on) return esc(s.replace(rx, '$1'));
    var out = '', last = 0, m;
    while ((m = rx.exec(s)) !== null) {
      out += esc(s.slice(last, m.index)) + '<ruby>' + esc(m[1]) + '<rt>' + esc(m[2]) + '</rt></ruby>';
      last = rx.lastIndex;
    }
    return out + esc(s.slice(last));
  }

  function romajiOf(text) {
    var S = root && root.CpathSituation;
    return S && typeof S.toRomaji === 'function' ? S.toRomaji(text) : '';
  }

  function speaker() {
    return '<svg viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path d="M9.5 4.5L5.8 7.5H3.2v5h2.6l3.7 3v-11z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>'
      + '<path d="M12.8 7.2a4 4 0 0 1 0 5.6M15 5.2a7 7 0 0 1 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
  }

  function playBtn(url, text, label) {
    return '<button type="button" class="cp-audio-btn" data-core-play="' + esc(url || '') + '" data-core-text="' + esc(text || '')
      + '" aria-label="' + esc(label || 'Nghe') + '">' + speaker() + '</button>';
  }

  /** Plain Japanese of an item for the voice (pattern notes such as "(Past)" dropped). */
  function spoken(item) {
    return String(item.ja || '').replace(/\([^)]*[A-Za-z][^)]*\)/g, '').replace(/[〜~]/g, '').trim();
  }

  function cardHtml(item, display) {
    var d = display || {};
    var reading = item.reading && item.reading !== item.ja ? item.reading : '';
    var rom = d.romaji ? (item.romaji || romajiOf(item.reading || item.ja)) : '';
    var ex = item.example;
    return '<li class="cp-core-card' + (item.done ? ' is-done' : '') + '"><div class="cp-core-card__word">'
      + '<div class="cp-core-card__head">'
      + '<span class="cp-core-card__kind cp-core-card__kind--' + esc(String(item.kind).toLowerCase()) + '">' + esc(KIND_LABEL[item.kind] || 'Mục') + '</span>'
      + (item.done ? '<span class="cp-core-card__done">Đã thuộc</span>' : '')
      + '</div>'
      + '<div class="cp-core-card__main">'
      + '<div class="cp-core-card__ja" lang="ja">' + ruby(item.ja, d.furigana !== false) + '</div>'
      + playBtn(item.audioUrl, spoken(item), 'Nghe ' + (item.ja || ''))
      + '</div>'
      + (d.furigana !== false && reading ? '<div class="cp-core-card__rd" lang="ja">' + esc(reading) + '</div>' : '')
      + (rom ? '<div class="cp-line__romaji">' + esc(rom) + '</div>' : '')
      + '<div class="cp-core-card__vi">' + esc(item.meaningVi) + '</div>'
      + (item.note ? '<div class="cp-core-card__note">' + esc(item.note) + '</div>' : '')
      + '</div>'
      + (ex && ex.ja ? '<div class="cp-core-card__ex">'
        + '<div class="cp-core-card__ex-ja" lang="ja"><span class="cp-core-card__ex-text">' + ruby(ex.furigana || ex.ja, d.furigana !== false) + '</span>'
        + playBtn(ex.audioUrl, ex.ja, 'Nghe câu ví dụ') + '</div>'
        + (d.romaji ? '<div class="cp-line__romaji">' + esc(romajiOf(ex.furigana || ex.ja)) + '</div>' : '')
        + (d.translation !== false && ex.vi ? '<div class="cp-core-card__ex-vi">' + esc(ex.vi) + '</div>' : '')
        + '</div>' : '')
      + '</li>';
  }

  function quizHtml(q, index, total, picked, display) {
    var d = display || {};
    return '<section class="jp-card cp-core-quiz" aria-labelledby="cpCoreQ">'
      + '<p class="cp-core-quiz__count">Câu ' + (index + 1) + '/' + total + ' · chọn nghĩa đúng</p>'
      + '<div class="cp-core-card__main"><div id="cpCoreQ" class="cp-core-card__ja" lang="ja">' + ruby(q.ja, d.furigana !== false) + '</div>'
      + playBtn(q.audioUrl, spoken(q), 'Nghe') + '</div>'
      + (d.romaji ? '<div class="cp-line__romaji">' + esc(q.romaji || romajiOf(q.reading || q.ja)) + '</div>' : '')
      + '<div class="cp-core-quiz__opts" role="group" aria-label="Các nghĩa">'
      + (q.options || []).map(function (o, i) {
        return '<button type="button" class="cp-core-opt' + (picked === o ? ' is-picked' : '') + '" data-core-pick="' + i + '">' + esc(o) + '</button>';
      }).join('')
      + '</div></section>';
  }

  function resultHtml(res, items, isCheck) {
    var byCode = {};
    (items || []).forEach(function (x) { byCode[x.code] = x; });
    var wrong = (res.results || []).filter(function (r) { return !r.correct; });
    var head;
    if (isCheck) {
      head = res.passed
        ? '<h2>Đạt ' + res.score + '% — phần đã biết được tính là đã thuộc</h2><p>Bạn không cần học lại các mục này trong bài nền.</p>'
        : '<h2>' + res.score + '% — chưa đạt ' + res.pass + '%</h2><p>Các mục này sẽ được xếp vào bài nền hằng ngày để ôn lại.</p>';
    } else {
      head = res.lessonDone
        ? '<h2>Xong bài nền hôm nay</h2><p>Đúng ' + res.correct + '/' + res.total + '. Các mục này cũng được tính bên lộ trình JLPT.</p>'
        : '<h2>Đúng ' + res.correct + '/' + res.total + '</h2><p>Còn ' + wrong.length + ' mục chưa thuộc. Xem lại rồi kiểm tra lại nhé.</p>';
    }
    return '<section class="jp-card cp-core-result" role="status">' + head
      + (wrong.length ? '<ul class="cp-core-wrong">' + wrong.map(function (r) {
        var it = byCode[r.code] || {};
        return '<li><span lang="ja">' + esc(it.ja || r.code) + '</span> — ' + esc(r.answer) + '</li>';
      }).join('') + '</ul>' : '')
      + '<div class="cp-settings__actions">'
      + (!isCheck && !res.lessonDone ? '<button type="button" class="cp-btn-primary" data-core-again>Học lại phần sai</button>' : '')
      + '<a class="' + (isCheck || res.lessonDone ? 'cp-btn-primary' : 'cp-btn-ghost') + '" href="/learning/comm/path">Về lộ trình — học tình huống hôm nay</a>'
      + '</div></section>';
  }

  /** Items still to check: not done yet (all of them in the known check). */
  function pending(items) { return (items || []).filter(function (x) { return !x.done; }); }

  function mount(el, deps) {
    if (!el || !root.JpathApi) return;
    var d = deps || {};
    var search = d.search != null ? d.search : String(root.location && root.location.search || '');
    var isCheck = /[?&]check=1/.test(search);
    var data = null, phase = 'learn', qi = 0, answers = {}, result = null, busy = false;
    var A = root.CpathAudio;

    function header() {
      var title = isCheck ? 'Đã biết – ôn nhanh' : 'Bài nền hôm nay';
      var lede = isCheck
        ? 'Các mục dưới trình độ ' + esc(data.level || '') + ' của bạn. Đạt ' + esc(data.pass) + '% thì không phải học lại.'
        : 'Học ' + (data.items || []).length + ' mục trước, rồi vào tình huống hôm nay dùng đúng các mục này.'
          + (data.depthLabel ? ' Độ sâu: ' + esc(data.depthLabel) + '.' : '');
      return '<header class="jp-page-head"><h1 class="jp-page-head__title">' + title + '</h1>'
        + '<p class="jp-page-head__lede">' + lede + '</p></header>'
        + (A ? A.sliderHtml({ compact: true }) : '');
    }

    function render() {
      if (!data) return;
      var display = data.display || {};
      var items = isCheck ? (data.questions || []) : (data.items || []);
      if (!items.length) {
        el.innerHTML = header() + '<section class="jp-card"><p>' + (isCheck ? 'Không còn mục nào cần kiểm tra.'
          : (data.restDay ? 'Hôm nay là ngày nghỉ, không có bài nền.' : 'Bạn đã học hết bài nền ở độ sâu này. Đổi độ sâu trong Cài đặt nếu muốn học thêm.'))
          + '</p><a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình</a></section>';
        return;
      }
      if (phase === 'result' && result) {
        el.innerHTML = header() + resultHtml(result, items, isCheck);
        return;
      }
      var todo = isCheck ? items : pending(items);
      if (!isCheck && data.done) {
        el.innerHTML = header() + '<section class="jp-card cp-core-result" role="status"><h2>Bài nền hôm nay đã xong</h2>'
          + '<p>Bạn có thể xem lại thẻ bên dưới hoặc vào tình huống hôm nay.</p>'
          + '<a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình</a></section>'
          + '<ul class="cp-core-cards">' + items.map(function (x) { return cardHtml(x, display); }).join('') + '</ul>';
        return;
      }
      if (phase === 'learn' && !isCheck) {
        el.innerHTML = header() + '<ul class="cp-core-cards">' + todo.map(function (x) { return cardHtml(x, display); }).join('') + '</ul>'
          + '<div class="cp-settings__actions"><button type="button" class="cp-btn-primary" data-core-quiz>Kiểm tra nhanh (' + todo.length + ' câu)</button></div>';
        return;
      }
      var q = todo[qi];
      el.innerHTML = header() + quizHtml(q, qi, todo.length, answers[q.code], display)
        + '<div class="cp-settings__actions">'
        + (qi > 0 ? '<button type="button" class="cp-btn-ghost" data-core-prev>Câu trước</button>' : '')
        + (!isCheck ? '<button type="button" class="cp-btn-ghost" data-core-back>Xem lại thẻ</button>' : '')
        + '<button type="button" class="cp-btn-primary" data-core-next' + (answers[q.code] == null || busy ? ' disabled' : '') + '>'
        + (qi + 1 < todo.length ? 'Câu tiếp' : 'Nộp bài') + '</button></div>';
    }

    function submit() {
      busy = true; render();
      var url = isCheck ? '/api/learning/comm/core/check' : '/api/learning/comm/core/lesson';
      Promise.resolve(root.JpathApi.send('POST', url, { answers: answers })).then(function (r) {
        busy = false; result = r || {}; phase = 'result'; render();
      }).catch(function (e) {
        busy = false; render();
        el.insertAdjacentHTML('beforeend', '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Chưa nộp được bài.') + '</p>');
      });
    }

    function load() {
      var url = isCheck ? '/api/learning/comm/core/check' : '/api/learning/comm/core/lesson';
      Promise.resolve(root.JpathApi.get(url)).then(function (x) {
        data = x || {}; phase = isCheck ? 'quiz' : 'learn'; qi = 0; answers = {}; result = null;
        if (A) A.fromDisplay(data.display);
        render();
      }).catch(function (e) {
        el.innerHTML = '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Chưa tải được bài nền.')
          + ' <a class="cp-btn-ghost" href="/learning/comm/path">Về lộ trình</a></p>';
      });
    }

    if (A) A.bind(el, A.saveToServer);
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      var play = t.closest && t.closest('[data-core-play]');
      if (play) {
        if (A) A.play(play.getAttribute('data-core-play'), play.getAttribute('data-core-text'));
        return;
      }
      var items = isCheck ? (data && data.questions || []) : pending(data && data.items);
      if (t.closest('[data-core-quiz]')) { phase = 'quiz'; qi = 0; answers = {}; render(); return; }
      if (t.closest('[data-core-back]')) { phase = 'learn'; render(); return; }
      if (t.closest('[data-core-prev]')) { qi = Math.max(0, qi - 1); render(); return; }
      var pick = t.closest('[data-core-pick]');
      if (pick) {
        var q = items[qi];
        answers[q.code] = q.options[Number(pick.getAttribute('data-core-pick'))];
        render();
        return;
      }
      if (t.closest('[data-core-next]')) {
        if (qi + 1 < items.length) { qi++; render(); } else submit();
        return;
      }
      if (t.closest('[data-core-again]')) load();
    });
    load();
  }

  return { ruby: ruby, cardHtml: cardHtml, quizHtml: quizHtml, resultHtml: resultHtml, pending: pending, spoken: spoken, mount: mount };
});
