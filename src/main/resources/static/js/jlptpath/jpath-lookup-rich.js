/* [JLAS-W0108] jpath-lookup-rich.js — a fuller Look up card on the JLPT path pages (owner 04/11).
 *
 * The shared quick lookup (inline-dictionary.js, aside.inline-dict) shows one meaning. On the path pages this module
 * waits for that card to open and adds, under the word, what the dictionary already has:
 *   Cách đọc · Hán Việt · Từ loại, every sense numbered with its parts of speech, the example with a play button
 *   (GET /api/dictionary/tts), "Các kết quả gần đúng" and "Từ cùng cách đọc" (GET /api/dictionary/search).
 * Reads GET /api/dictionary/vocabulary/{id} (the id is the card's save button). The shared buttons (Lưu từ, Sổ tay,
 * Mở từ điển) stay as they are. Nothing is written.
 *
 * window.JpathLookupRich = { richHtml(detail, near, sameReading), sensesHtml(senses), mount(doc, deps) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathLookupRich = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    var start = function () { api.mount(root.document, {}); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start, { once: true });
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var MAX_NEAR = 4;
  var MAX_SAME = 5;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function list(v) { return Array.isArray(v) ? v : []; }
  function has(v) { return v !== null && v !== undefined && String(v).trim() !== ''; }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }
  function wordUrl(expression) { return '/search/word/' + encodeURIComponent(expression || ''); }

  /** Senses in order, numbered; a sense = its Vietnamese glosses (else any), with its parts of speech as tags. */
  function sensesHtml(senses) {
    var rows = list(senses).slice().sort(function (a, b) { return (a.senseOrder || 0) - (b.senseOrder || 0); })
      .map(function (s) {
        // usage notes that only carry the import's topic group ("Nhóm chủ đề: khac") are not for learners
        var glosses = list(s.glosses).filter(function (g) { return g && has(g.text); });
        var vi = glosses.filter(function (g) { return g.languageCode === 'vi' || g.languageCode === 'vie'; });
        var text = (vi.length ? vi : glosses).map(function (g) { return g.text; }).join('; ');
        if (!text) return '';
        var tags = list(s.partsOfSpeech).concat(list(s.domains)).filter(has)
          .map(function (t) { return '<span class="jp-lu__tag">' + esc(t) + '</span>'; }).join('');
        return '<li>' + tags + '<span>' + esc(text) + '</span>'
          + (has(s.usageNote) && !/^Nhóm chủ đề/i.test(s.usageNote) ? '<span class="jp-lu__note">' + esc(s.usageNote) + '</span>' : '') + '</li>';
      }).filter(Boolean);
    if (!rows.length) return '';
    return '<section class="jp-lu__part"><h4 class="jp-lu__label">Nghĩa (' + rows.length + ')</h4><ol class="jp-lu__senses">'
      + rows.join('') + '</ol></section>';
  }

  function wordRows(words, title) {
    var items = list(words).filter(function (w) { return w && has(w.expression); });
    if (!items.length) return '';
    return '<section class="jp-lu__part"><h4 class="jp-lu__label">' + title + '</h4><ul class="jp-lu__words">'
      + items.map(function (w) {
        return '<li><a href="' + esc(wordUrl(w.expression)) + '"><strong lang="ja">' + esc(w.expression) + '</strong>'
          + (has(w.reading) && w.reading !== w.expression ? ' <span lang="ja">【' + esc(w.reading) + '】</span>' : '')
          + (has(w.meaning) ? ' <span class="jp-lu__mean">' + esc(w.meaning) + '</span>' : '') + '</a></li>';
      }).join('') + '</ul></section>';
  }

  /** The block added under the word of the shared card. */
  function richHtml(detail, near, sameReading) {
    var d = detail || {};
    var facts = [['Cách đọc', d.reading, 'ja'], ['Hán Việt', has(d.hanViet) ? String(d.hanViet).toUpperCase() : '', 'hv'],
      ['Từ loại', d.wordType, '']].filter(function (f) { return has(f[1]); }).map(function (f) {
      return '<div class="jp-lu__fact"><dt>' + f[0] + '</dt><dd' + (f[2] === 'ja' ? ' lang="ja"' : '')
        + (f[2] === 'hv' ? ' class="jp-lu__hv"' : '') + '>' + esc(f[1]) + '</dd></div>';
    }).join('');
    var example = has(d.exampleJa) ? '<section class="jp-lu__part"><h4 class="jp-lu__label">'
      + '<button type="button" class="jp-lu__play" data-lu-play="' + esc(d.exampleJa) + '" aria-label="Nghe câu ví dụ">'
      + icon('volume-2') + '</button> Ví dụ</h4><p class="jp-lu__ex" lang="ja">' + esc(d.exampleJa) + '</p>'
      + (has(d.exampleVi) ? '<p class="jp-lu__exvi">→ ' + esc(d.exampleVi) + '</p>' : '') + '</section>' : '';
    var sameTitle = 'Từ cùng cách đọc' + (has(d.reading) ? ' <span lang="ja">【' + esc(d.reading) + '】</span>' : '');
    return '<div class="jp-lu" data-lu-for="' + esc(d.id) + '">' + (facts ? '<dl class="jp-lu__facts">' + facts + '</dl>' : '')
      + sensesHtml(d.senses) + example + wordRows(near, 'Các kết quả gần đúng') + wordRows(sameReading, sameTitle) + '</div>';
  }

  function others(page, d, sameReading) {
    var items = list(page && page.vocabularyResults && page.vocabularyResults.content)
      .concat(list(page && page.vocabularyResults && page.vocabularyResults.items));
    var seen = {};
    return items.filter(function (w) {
      if (!w || w.id === d.id || seen[w.expression + '|' + w.reading]) return false;
      seen[w.expression + '|' + w.reading] = true;
      return sameReading ? w.reading === d.reading && w.expression !== d.expression
        : w.reading !== d.reading || w.expression !== d.expression;
    });
  }

  function mount(doc, deps) {
    var d = deps || {};
    if (!doc || !doc.body || !doc.body.classList || !doc.body.classList.contains('jp-body')) return null;
    var fetchJson = d.fetchJson || function (url) {
      var f = root.JlasAuth && root.JlasAuth.authFetch ? root.JlasAuth.authFetch(url, {}) : root.fetch(url, { credentials: 'same-origin' });
      return Promise.resolve(f).then(function (r) { return r.json(); }).then(function (p) {
        if (!p || !p.success) throw new Error('lookup');
        return p.data;
      });
    };
    var cache = {};
    var serial = 0;

    /**
     * Owner 05/11: some dictionary rows are whole sentences (田中 → 田中さんは隔週で…). Such a head word is replaced by
     * the real words found for the selected text, so the card never shows a sentence as the word.
     */
    function sentenceLike(text) {
      var t = String(text || '').trim();
      return /[。、！？!?]/.test(t) || t.length > 10;
    }

    function replaceSentence(card, head) {
      var picked = '';
      try { picked = String(root.getSelection ? root.getSelection().toString() : '').trim(); } catch (e) { picked = ''; }
      if (!has(picked)) return true;
      card.classList.add('jp-lu-card');
      Array.prototype.forEach.call(card.querySelectorAll('.jp-lu'), function (n) { n.remove(); });
      head.innerHTML = '<strong lang="ja">' + esc(picked) + '</strong>';
      fetchJson('/api/dictionary/search?q=' + encodeURIComponent(picked) + '&size=12').then(function (page) {
        var words = others(page, {}, false).filter(function (w) { return !sentenceLike(w.expression); });
        words.sort(function (a, b) {
          var ea = a.expression === picked ? 0 : (String(a.expression).indexOf(picked) === 0 ? 1 : 2);
          var eb = b.expression === picked ? 0 : (String(b.expression).indexOf(picked) === 0 ? 1 : 2);
          return ea - eb;
        });
        var body = wordRows(words.slice(0, MAX_NEAR + 2), 'Từ trong từ điển')
          || '<p class="jp-lu__note">Chưa có từ này trong từ điển.</p>';
        head.insertAdjacentHTML('afterend', '<div class="jp-lu" data-lu-for="sentence">' + body + '</div>');
      }, function () { /* keep the plain card */ });
      return true;
    }

    function enrich(card) {
      var save = card.querySelector('[data-inline-save]');
      var id = save && save.getAttribute('data-inline-save');
      var head0 = card.querySelector('.inline-dict__word');
      var headWord = head0 && head0.querySelector('strong');
      if (head0 && head0.hasAttribute('data-lu-head')) return;
      if (headWord && sentenceLike(headWord.textContent) && replaceSentence(card, head0)) {
        head0.setAttribute('data-lu-head', '1');
        Array.prototype.forEach.call(card.querySelectorAll('.inline-dict__meaning, .inline-dict__type, .inline-dict__example, .inline-dict__actions'),
          function (n) { n.hidden = true; });
        return;
      }
      if (!id || id === 'null' || card.querySelector('.jp-lu[data-lu-for="' + id + '"]')) return;
      var mine = ++serial;
      var load = cache[id] || (cache[id] = fetchJson('/api/dictionary/vocabulary/' + encodeURIComponent(id)).then(function (det) {
        var q = function (text) {
          return has(text) ? fetchJson('/api/dictionary/search?q=' + encodeURIComponent(text) + '&size=12').catch(function () { return null; })
            : Promise.resolve(null);
        };
        return Promise.all([q(det.expression), det.reading && det.reading !== det.expression ? q(det.reading) : Promise.resolve(null)])
          .then(function (res) {
            return { det: det, near: others(res[0], det, false).slice(0, MAX_NEAR),
              same: others(res[1] || res[0], det, true).slice(0, MAX_SAME) };
          });
      }));
      load.then(function (x) {
        if (mine !== serial || card.hidden) return;
        var head = card.querySelector('.inline-dict__word');
        if (!head || card.querySelector('.jp-lu[data-lu-for="' + id + '"]')) return;
        card.classList.add('jp-lu-card');
        head.insertAdjacentHTML('afterend', richHtml(x.det, x.near, x.same));
        // the card grew: keep it inside the window
        var r = card.getBoundingClientRect();
        var vw = root.innerWidth || 0;
        var vh = root.innerHeight || 0;
        if (vw && r.right > vw - 12) card.style.left = Math.max(12, vw - r.width - 12) + 'px';
        if (vh && r.bottom > vh - 12) card.style.top = Math.max(12, vh - r.height - 12) + 'px';
      }, function () { delete cache[id]; });
    }

    var watch = function () {
      var card = doc.querySelector('aside.inline-dict');
      if (card && !card.hidden) enrich(card);
    };
    if (typeof root.MutationObserver === 'function') {
      new root.MutationObserver(watch).observe(doc.body, { childList: true, subtree: true, attributes: true,
        attributeFilter: ['hidden'] });
    }
    doc.addEventListener('click', function (ev) {
      var b = ev.target && ev.target.closest ? ev.target.closest('[data-lu-play]') : null;
      if (!b) return;
      ev.preventDefault();
      try { new root.Audio('/api/dictionary/tts?text=' + encodeURIComponent(b.getAttribute('data-lu-play'))).play(); } catch (e) { /* no audio */ }
    });
    return { enrich: enrich };
  }

  return { richHtml: richHtml, sensesHtml: sensesHtml, mount: mount };
});
