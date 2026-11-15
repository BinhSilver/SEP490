/* [JLAS-W0108] jpath-dictation.js — dictation step of a Listening session (Plan 3 Task 21).
 *
 * window.JpathDictation = { MASK, maskHtml(mask, questionId), lineHtml(line, token), resultHtml(line), mount(el, opts) }
 *   - Each line: ▶ Nghe (the line's audio through the check token), the sentence with an input per ＿＿ blank (lang ja,
 *     autocomplete off, IME friendly), "Kiểm tra" or Enter → POST /checks/{token}/answers {value: JSON array}.
 *   - A locked line shows every blank: EXACT green, MINOR yellow with the expected text, WRONG red with the expected
 *     text, then the whole sentence and "Luyện nói theo" (window.JpathShadowFollow).
 *   - Every line locked → "Nộp phần chép" (POST /checks/{token}/submit) → opts.onSubmitted().
 *   - F139: a failed submit/advance or line check says so in [data-dict-status] and gives the button back.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathDictation = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt/checks/';
  var MASK = '＿＿';
  var MSG_SUBMIT_FAILED = 'Chưa nộp được phần chép. Kiểm tra kết nối rồi bấm lại.';
  var MSG_CHECK_FAILED = 'Chưa kiểm được dòng này. Kiểm tra kết nối rồi thử lại.';
  var VERDICT = { EXACT: ['is-exact', 'Đúng'], MINOR: ['is-minor', 'Gần đúng'], WRONG: ['is-wrong', 'Sai'] };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(x) { return Array.isArray(x) ? x : []; }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  /** The masked sentence with one input per blank; the blanks' text is never in it. */
  function maskHtml(mask, qid) {
    var parts = String(mask || '').split(MASK);
    var out = '';
    parts.forEach(function (p, i) {
      out += '<span lang="ja">' + esc(p) + '</span>';
      if (i < parts.length - 1) {
        out += '<input type="text" lang="ja" autocomplete="off" autocapitalize="off" spellcheck="false" size="6" maxlength="40"'
          + ' data-blank="' + i + '" aria-label="Ô trống ' + (i + 1) + '" data-line="' + esc(qid) + '">';
      }
    });
    return out;
  }

  /** A locked line: verdict per blank (the expected text when not exact), the sentence, the follow button. */
  function resultHtml(line) {
    var parts = String(line.mask || '').split(MASK);
    var out = '';
    parts.forEach(function (p, i) {
      out += '<span lang="ja">' + esc(p) + '</span>';
      if (i < parts.length - 1) {
        var v = VERDICT[list(line.verdicts)[i]] || VERDICT.WRONG;
        var typed = list(line.typed)[i];
        var expected = list(line.expected)[i];
        var shown = typed && String(typed).length > 40 ? String(typed).slice(0, 40) + '…' : typed;
        out += '<strong class="' + v[0] + '" lang="ja" title="' + esc(v[1]) + '">' + esc(shown || '—') + '</strong>'
          + (list(line.verdicts)[i] === 'EXACT' ? '' : ' <span class="' + v[0] + '">(' + esc(v[1]) + ': ' + esc(expected) + ')</span>');
      }
    });
    return out + '<p class="jp-muted" lang="ja">' + esc(line.textJa) + '</p>'
      + '<button type="button" class="jp-btn" data-follow-line="' + esc(line.questionId) + '">' + icon('mic-2') + ' Luyện nói theo</button>'
      + '<div data-follow-panel="' + esc(line.questionId) + '"></div>';
  }

  function lineHtml(line, token) {
    var audio = line.audioSlot != null
      ? '<button type="button" class="jp-btn" data-line-audio="' + esc(BASE + encodeURIComponent(token) + '/audio/'
        + encodeURIComponent(line.audioSlot)) + '">' + icon('play') + ' Nghe</button> ' : '';
    return '<div class="jp-dict-line" data-dict-line="' + esc(line.questionId) + '">' + audio
      + (line.locked ? resultHtml(line)
        : maskHtml(line.mask, line.questionId) + ' <button type="button" class="jp-btn jp-btn--primary" data-dict-check="'
          + esc(line.questionId) + '">Kiểm tra</button>') + '</div>';
  }

  function viewHtml(view) {
    var lines = list(view && view.skill && view.skill.dictation);
    var all = lines.length > 0 && lines.every(function (l) { return l.locked; });
    return '<section class="jp-skill-block"><h2>Chép chính tả</h2><p class="jp-muted">Nghe rồi điền chữ còn thiếu. Gõ kana '
      + 'hoặc chữ Hán đều được; sai nhẹ (ん, っ, trường âm) vẫn tính đúng.</p>'
      + lines.map(function (l) { return lineHtml(l, view.token); }).join('')
      + (view.status === 'ISSUED' ? '<button type="button" class="jp-btn jp-btn--primary" data-dict-submit'
        + (all ? '' : ' disabled') + '>Nộp phần chép</button>' : '')
      + '<p class="jp-status" role="alert" data-dict-status></p></section>';
  }

  function uuid() {
    var c = root && root.crypto;
    return c && typeof c.randomUUID === 'function' ? c.randomUUID() : 'd' + Date.now().toString(36);
  }

  /** Draws the dictation of {@code opts.token} into {@code el}; opts = {token, api, onSubmitted}. */
  function mount(el, opts) {
    var o = opts || {};
    var api = o.api || root.JpathApi;
    var view = null;
    var shownAt = Date.now();

    function draw() {
      return api.get(BASE + encodeURIComponent(o.token)).then(function (v) {
        view = v;
        el.innerHTML = viewHtml(v);
        var first = el.querySelector('input[data-blank]');
        if (first && typeof first.focus === 'function') first.focus();
      });
    }

    function say(msg) {
      var p = el.querySelector('[data-dict-status]');
      if (p) p.textContent = msg;
    }

    /** The server's own message when it sent one (e.g. INCOMPLETE), else the fallback. */
    function errText(err, fallback) {
      var m = err && err.code !== 'NETWORK' && err.message ? String(err.message).trim() : '';
      return m || fallback;
    }

    function check(qid) {
      var inputs = el.querySelectorAll('input[data-line="' + qid + '"]');
      var values = [];
      inputs.forEach(function (i) { values.push(String(i.value).slice(0, 40)); });
      if (!values.some(function (x) { return String(x).trim(); })) return;
      api.send('POST', BASE + encodeURIComponent(o.token) + '/answers', { questionId: qid, value: JSON.stringify(values),
        elapsedMs: Date.now() - shownAt }).then(function () { shownAt = Date.now(); return draw(); })
        .catch(function (err) { say(errText(err, MSG_CHECK_FAILED)); });
    }

    if (!el.getAttribute('data-dict-bound')) {
      el.setAttribute('data-dict-bound', '1');
      el.addEventListener('click', function (ev) {
        var t = ev.target;
        var c = t.closest('[data-dict-check]');
        if (c) { check(c.getAttribute('data-dict-check')); return; }
        var a = t.closest('[data-line-audio]');
        if (a && root.document) { var au = root.document.createElement('audio'); au.src = a.getAttribute('data-line-audio'); au.play(); return; }
        var f = t.closest('[data-follow-line]');
        if (f && root.JpathShadowFollow && view) {
          var qid = f.getAttribute('data-follow-line');
          var line = list(view.skill && view.skill.dictation).filter(function (l) { return l.questionId === qid; })[0];
          var panel = el.querySelector('[data-follow-panel="' + qid + '"]');
          if (line && panel) {
            root.JpathShadowFollow.open(panel, { text: line.textJa, src: line.audioSlot != null
              ? BASE + encodeURIComponent(o.token) + '/audio/' + encodeURIComponent(line.audioSlot) : null });
          }
          return;
        }
        var btn = t.closest('[data-dict-submit]');
        if (btn) {
          if (btn.disabled) return;
          btn.disabled = true;
          say('');
          api.send('POST', BASE + encodeURIComponent(o.token) + '/submit', { submitKey: uuid() }).then(function () {
            return o.onSubmitted ? o.onSubmitted() : draw();
          }).catch(function (err) {
            btn.disabled = false;
            say(errText(err, MSG_SUBMIT_FAILED));
          });
        }
      });
      el.addEventListener('keydown', function (ev) {
        if (ev.key !== 'Enter' || ev.isComposing) return; // Enter that ends an IME composition does not submit
        var input = ev.target.closest ? ev.target.closest('input[data-line]') : null;
        if (!input) return;
        ev.preventDefault();
        check(input.getAttribute('data-line'));
      });
    }
    return draw();
  }

  return { MASK: MASK, maskHtml: maskHtml, lineHtml: lineHtml, resultHtml: resultHtml, viewHtml: viewHtml, mount: mount };
});
