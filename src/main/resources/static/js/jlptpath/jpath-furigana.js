/* [JLAS-W0108] jpath-furigana.js — furigana helpers of the JLPT path pages.
 *
 * Source text carries readings inline: 父親(ちちおや)は… . Only a kanji run (CJK, 々, ヶ) directly before a
 * parenthesis holding kana only becomes <ruby>; every other parenthesis stays literal. All output is HTML-escaped.
 *
 * window.JpathFurigana:
 *   toRuby(text)                          escaped HTML with <ruby>base<rt>reading</rt></ruby>; null → ''
 *   plain(text)                           text with the readings removed (not escaped)
 *   exampleHtml({ja, jaFurigana, vi})     .jp-ex-ja (jaFurigana, else ja) + .jp-ex-vi; missing parts are left out
 *   kanjiReadingHtml(reading, levelReadings)
 *                                         'ひと.つ' → 'ひと<span class="jp-okuri">つ</span>', leading/trailing '-' → '〜',
 *                                         wrapped in <b> when its hiragana form is one of levelReadings
 *   bindToggle(root, hiddenAll)           click on a ruby flips that one (jp-rt-flip); setAll(bool) sets
 *                                         jp-furigana-off on root. Returns {setAll(bool), isAllHidden()}.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathFurigana = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function () {
  'use strict';

  var READING = /([㐀-鿿々ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g;

  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toRuby(text) {
    if (text === null || text === undefined) return '';
    var s = String(text);
    var out = '';
    var last = 0;
    var m;
    READING.lastIndex = 0;
    while ((m = READING.exec(s)) !== null) {
      out += esc(s.slice(last, m.index)) + '<ruby>' + esc(m[1]) + '<rt>' + esc(m[2]) + '</rt></ruby>';
      last = READING.lastIndex;
    }
    return out + esc(s.slice(last));
  }

  function plain(text) {
    if (text === null || text === undefined) return '';
    return String(text).replace(READING, '$1');
  }

  function exampleHtml(example) {
    var e = example || {};
    var ja = e.jaFurigana != null && e.jaFurigana !== '' ? e.jaFurigana : e.ja;
    var html = '';
    if (ja != null && ja !== '') html += '<div class="jp-ex-ja">' + toRuby(ja) + '</div>';
    if (e.vi != null && e.vi !== '') html += '<div class="jp-ex-vi">' + esc(e.vi) + '</div>';
    return html;
  }

  function toHiragana(s) {
    return s.replace(/[ァ-ヶ]/g, function (c) { return String.fromCharCode(c.charCodeAt(0) - 0x60); });
  }

  function normalizeReading(s) {
    return toHiragana(String(s).normalize ? String(s).normalize('NFKC') : String(s)).replace(/[.\-〜]/g, '');
  }

  function kanjiReadingHtml(reading, levelReadings) {
    if (reading === null || reading === undefined || reading === '') return '';
    var r = String(reading);
    var lead = r.charAt(0) === '-';
    var trail = r.length > 1 && r.charAt(r.length - 1) === '-';
    var core = r.slice(lead ? 1 : 0, trail ? r.length - 1 : r.length);
    var dot = core.indexOf('.');
    var html = dot < 0 ? esc(core)
      : esc(core.slice(0, dot)) + '<span class="jp-okuri">' + esc(core.slice(dot + 1)) + '</span>';
    html = (lead ? '〜' : '') + html + (trail ? '〜' : '');
    var key = normalizeReading(r);
    var bold = Array.isArray(levelReadings) && levelReadings.some(function (x) {
      return x != null && normalizeReading(x) === key;
    });
    return bold ? '<b>' + html + '</b>' : html;
  }

  function bindToggle(rootEl, hiddenAll) {
    var hidden = !!hiddenAll;
    function setAll(value) {
      hidden = !!value;
      if (hidden) rootEl.classList.add('jp-furigana-off');
      else rootEl.classList.remove('jp-furigana-off');
    }
    rootEl.addEventListener('click', function (event) {
      var target = event && event.target;
      var ruby = target && typeof target.closest === 'function' ? target.closest('ruby') : null;
      if (ruby) ruby.classList.toggle('jp-rt-flip');
    });
    setAll(hidden);
    return { setAll: setAll, isAllHidden: function () { return hidden; } };
  }

  return { toRuby: toRuby, plain: plain, exampleHtml: exampleHtml, kanjiReadingHtml: kanjiReadingHtml,
    bindToggle: bindToggle, esc: esc };
});
