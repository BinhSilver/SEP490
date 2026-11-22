/* [JLAS-W0108] jpath-display.js — display toolkit of the JLPT path study pages.
 *
 * display = the `display` block of GET /api/learning/jlpt/settings (keys may be omitted, Jackson non_null):
 *   {furiganaHiddenAll, romajiMode AUTO|ON|OFF, romajiOn, fontSize S|M|L, soundOn, autoplayAudio}
 *   romajiOn wins; without it ON → on, OFF → off, AUTO → on only at N5 (display.level; no level counts as N5).
 *
 * window.JpathDisplay:
 *   applyDisplay(root, display)     jp-font-S|M|L (default M), jp-furigana-off, jp-romaji-off on root
 *   controlsHtml(display, {furigana}) furigana (left out when furigana is false) / romaji / font S M L buttons (data-jp-control, aria-pressed)
 *   createSaver({send, setTimeout, clearTimeout, delayMs = 400}) → {save(patch)}
 *                                   merges patches and sends one PUT /api/learning/jlpt/settings per quiet
 *                                   period; romajiOn true/false is written as romajiMode ON/OFF
 *   tone(kind, display, audioContextFactory)
 *                                   'correct' 880 Hz 90 ms, otherwise 220 Hz 140 ms, gain 0.04; silent (false)
 *                                   unless display.soundOn
 *   playAudio(url, audioFactory)    Promise<boolean>; false when there is no url or playback is blocked
 *   rendered(root, doc = document)  dispatches bubbling 'jpath:rendered' {detail:{root}} on root
 *   esc(v), dataJson(obj), fmtNumber(2110)='2.110', fmtDate('2026-12-12')='12/12/2026',
 *   fmtMonth('2027-07-04')='07/2027', dayShort('WED')='T4'
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathDisplay = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var SETTINGS_URL = '/api/learning/jlpt/settings';
  var FONTS = ['S', 'M', 'L'];
  var FONT_LABELS = { S: 'Chữ nhỏ', M: 'Chữ vừa', L: 'Chữ lớn' };
  var DAY_SHORT = { MON: 'T2', TUE: 'T3', WED: 'T4', THU: 'T5', FRI: 'T6', SAT: 'T7', SUN: 'CN' };
  var GAIN = 0.04;
  var TONES = { correct: { hz: 880, sec: 0.09 }, wrong: { hz: 220, sec: 0.14 } };

  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function dataJson(obj) { return esc(JSON.stringify(obj)); }

  function fontOf(display) {
    var f = display && display.fontSize;
    return FONTS.indexOf(f) >= 0 ? f : 'M';
  }

  function romajiOn(display) {
    var d = display || {};
    if (typeof d.romajiOn === 'boolean') return d.romajiOn;
    if (d.romajiMode === 'ON') return true;
    if (d.romajiMode === 'OFF') return false;
    return d.level == null || d.level === 'N5';
  }

  function applyDisplay(rootEl, display) {
    var d = display || {};
    var cl = rootEl.classList;
    FONTS.forEach(function (f) { cl.remove('jp-font-' + f); });
    cl.add('jp-font-' + fontOf(d));
    if (d.furiganaHiddenAll) cl.add('jp-furigana-off'); else cl.remove('jp-furigana-off');
    if (romajiOn(d)) cl.remove('jp-romaji-off'); else cl.add('jp-romaji-off');
  }

  function button(attrs, pressed, label) {
    return '<button type="button" class="jp-display-btn" ' + attrs + ' aria-pressed="' + (pressed ? 'true' : 'false')
      + '">' + esc(label) + '</button>';
  }

  function controlsHtml(display, opts) {
    var d = display || {};
    var o = opts || {};
    var font = fontOf(d);
    var html = '<div class="jp-display-controls" role="group" aria-label="Tùy chọn hiển thị">';
    // owner 22/11: no Furigana button when the card has no Kanji to read
    if (o.furigana !== false) html += button('data-jp-control="furigana"', !d.furiganaHiddenAll, 'Furigana');
    html += button('data-jp-control="romaji"', romajiOn(d), 'Romaji');
    FONTS.forEach(function (f) {
      html += button('data-jp-control="font" data-jp-font="' + f + '" aria-label="' + esc(FONT_LABELS[f]) + '"',
        f === font, f === 'S' ? 'A-' : (f === 'L' ? 'A+' : 'A'));
    });
    return html + '</div>';
  }

  function createSaver(options) {
    var o = options || {};
    var send = o.send || function (m, u, b) { return root.JpathApi.send(m, u, b); };
    var setT = o.setTimeout || function (fn, ms) { return setTimeout(fn, ms); };
    var clearT = o.clearTimeout || function (id) { clearTimeout(id); };
    var delay = o.delayMs == null ? 400 : o.delayMs;
    var pending = null;
    var timer = null;

    function flush() {
      timer = null;
      var body = pending;
      pending = null;
      if (!body) return;
      var result = send('PUT', SETTINGS_URL, body);
      if (result && typeof result.catch === 'function') result.catch(function () { /* best effort */ });
    }

    function save(patch) {
      pending = pending || {};
      Object.keys(patch || {}).forEach(function (k) {
        if (k === 'romajiOn') pending.romajiMode = patch[k] ? 'ON' : 'OFF';
        else pending[k] = patch[k];
      });
      if (timer !== null) clearT(timer);
      timer = setT(flush, delay);
    }

    return { save: save };
  }

  var sharedContext = null;
  function defaultContext() {
    if (!sharedContext) {
      var Ctx = root && (root.AudioContext || root.webkitAudioContext);
      if (!Ctx) throw new Error('no AudioContext');
      sharedContext = new Ctx();
    }
    return sharedContext;
  }

  function tone(kind, display, audioContextFactory) {
    if (!display || !display.soundOn) return false;
    var spec = kind === 'correct' ? TONES.correct : TONES.wrong;
    try {
      var ctx = (audioContextFactory || defaultContext)();
      var osc = ctx.createOscillator();
      var gain = ctx.createGain();
      osc.frequency.value = spec.hz;
      gain.gain.value = GAIN;
      osc.connect(gain);
      gain.connect(ctx.destination);
      var t = ctx.currentTime || 0;
      osc.start(t);
      osc.stop(t + spec.sec);
      return true;
    } catch (e) {
      return false;
    }
  }

  function playAudio(url, audioFactory) {
    if (!url) return Promise.resolve(false);
    try {
      var audio = audioFactory ? audioFactory(url) : new root.Audio(url);
      var p = audio.play();
      if (p && typeof p.then === 'function') {
        return p.then(function () { return true; }, function () { return false; });
      }
      return Promise.resolve(true);
    } catch (e) {
      return Promise.resolve(false);
    }
  }

  function rendered(rootEl, doc) {
    var d = doc || root.document;
    var Ev = d.CustomEvent || (d.defaultView && d.defaultView.CustomEvent) || root.CustomEvent;
    rootEl.dispatchEvent(new Ev('jpath:rendered', { bubbles: true, detail: { root: rootEl } }));
  }

  function fmtNumber(n) {
    if (n === null || n === undefined || n === '' || isNaN(Number(n))) return '';
    var v = Math.round(Number(n));
    var digits = String(Math.abs(v)).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
    return (v < 0 ? '-' : '') + digits;
  }

  function isoParts(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso == null ? '' : String(iso));
    return m ? { y: m[1], m: m[2], d: m[3] } : null;
  }

  function fmtDate(iso) { var p = isoParts(iso); return p ? p.d + '/' + p.m + '/' + p.y : ''; }
  function fmtMonth(iso) { var p = isoParts(iso); return p ? p.m + '/' + p.y : ''; }
  function dayShort(code) { return DAY_SHORT[code] || ''; }

  return { applyDisplay: applyDisplay, controlsHtml: controlsHtml, createSaver: createSaver, tone: tone,
    playAudio: playAudio, rendered: rendered, esc: esc, dataJson: dataJson, fmtNumber: fmtNumber,
    fmtDate: fmtDate, fmtMonth: fmtMonth, dayShort: dayShort, romajiOn: romajiOn, GAIN: GAIN,
    SETTINGS_URL: SETTINGS_URL };
});
