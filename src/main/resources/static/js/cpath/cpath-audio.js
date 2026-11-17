/*
 * [JLAS-W0108] F70 + F79 (owner 12/11): the listening speed of the communication path ("Nghe tốc độ thật").
 * One slider — 0,75× · 1× · 1,25× · 1,5× — for every comm audio (lines, words, phrases, notes, weekly test, base lesson,
 * Du lịch phrasebook and game); the browser voice used when a clip is missing follows it too. Each stop says what it is
 * like, so the learner knows which speed matches which situation. The choice is saved with the display choices
 * (PUT /api/learning/comm/path/display {speed}), so it follows the learner to another device.
 */
(function (root, factory) {
  var api = factory(root);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.CpathAudio = api;
}(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this), function (root) {
  'use strict';

  var SPEEDS = [
    { value: 0.75, label: '0,75×', name: 'Chậm', hint: 'Mới học, nghe rõ từng âm.' },
    { value: 1, label: '1×', name: 'Bài học', hint: 'Tốc độ đọc mẫu, rõ ràng.' },
    { value: 1.25, label: '1,25×', name: 'Tự nhiên', hint: 'Người Nhật nói chậm rãi, lịch sự với người nước ngoài.' },
    { value: 1.5, label: '1,5×', name: 'Đời thật', hint: 'Nhân viên cửa hàng, ga tàu, đồng nghiệp nói bình thường.' }
  ];
  var TTS_BASE = 0.95;
  var current = 1;
  var playing = null;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** The nearest allowed stop (anything else → 1×). */
  function normalize(v) {
    var n = Number(v);
    for (var i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i].value - n) < 0.001) return SPEEDS[i].value;
    return 1;
  }

  function stopOf(v) {
    var n = normalize(v);
    for (var i = 0; i < SPEEDS.length; i++) if (SPEEDS[i].value === n) return SPEEDS[i];
    return SPEEDS[1];
  }

  function setSpeed(v) { current = normalize(v); return current; }
  function speed() { return current; }

  /** Reads the saved speed from a display object of the API ({speed}). */
  function fromDisplay(display) { return setSpeed(display && display.speed != null ? display.speed : 1); }

  function fallbackTts(text, onEnd) {
    try {
      if (root && root.speechSynthesis && root.SpeechSynthesisUtterance && text) {
        root.speechSynthesis.cancel();
        var u = new root.SpeechSynthesisUtterance(String(text));
        u.lang = 'ja-JP';
        u.rate = TTS_BASE * current;
        if (typeof onEnd === 'function') {
          u.onend = function () { onEnd(); };
          u.onerror = function () { onEnd(); };
        }
        root.speechSynthesis.speak(u);
        return;
      }
    } catch (e) { /* no browser voice */ }
    if (typeof onEnd === 'function') setTimeout(onEnd, 750);
  }

  /**
   * Plays a clip at the chosen speed (pitch kept), else the browser voice; onEnd runs when it ends (or fails).
   * Starting a new clip stops the one playing, so tapping fast never overlaps two voices.
   */
  function play(url, text, onEnd) {
    var done = false;
    function end() { if (!done) { done = true; if (typeof onEnd === 'function') onEnd(); } }
    if (!url || !root || !root.Audio) { fallbackTts(text, onEnd); return null; }
    try {
      if (playing) { try { playing.pause(); } catch (e) { /* ignore */ } }
      var a = new root.Audio(url);
      a.playbackRate = current;
      if ('preservesPitch' in a) a.preservesPitch = true;
      a.onended = end;
      a.onerror = function () { if (!done) { done = true; fallbackTts(text, onEnd); } };
      playing = a;
      var p = a.play();
      if (p && typeof p.then === 'function') {
        p.then(null, function () { if (!done) { done = true; fallbackTts(text, onEnd); } });
      }
      return a;
    } catch (e) {
      fallbackTts(text, onEnd);
      return null;
    }
  }

  /** The slider with the label of the chosen stop under it (data-cp-speed is the range input). */
  function sliderHtml(opts) {
    var o = opts || {};
    var s = stopOf(current);
    var idx = SPEEDS.indexOf(s);
    var ticks = SPEEDS.map(function (x, i) {
      return '<span class="cp-speed__tick' + (i === idx ? ' is-on' : '') + '">' + esc(x.label) + '</span>';
    }).join('');
    return '<div class="cp-speed' + (o.compact ? ' cp-speed--compact' : '') + '" data-cp-speed-box>'
      + '<label class="cp-speed__head" for="cp-speed-range">'
      + '<span class="cp-speed__title">Tốc độ nghe</span>'
      + '<span class="cp-speed__value" data-cp-speed-value>' + esc(s.label) + ' · ' + esc(s.name) + '</span>'
      + '</label>'
      + '<input id="cp-speed-range" class="cp-speed__range" type="range" min="0" max="' + (SPEEDS.length - 1) + '" step="1" value="' + idx + '"'
      + ' data-cp-speed aria-valuetext="' + esc(s.label + ' ' + s.name) + '">'
      + '<div class="cp-speed__ticks" aria-hidden="true">' + ticks + '</div>'
      + '<p class="cp-speed__hint" data-cp-speed-hint>' + esc(s.hint) + '</p>'
      + '</div>';
  }

  function refreshBox(box) {
    var s = stopOf(current);
    var idx = SPEEDS.indexOf(s);
    var v = box.querySelector('[data-cp-speed-value]');
    var h = box.querySelector('[data-cp-speed-hint]');
    var r = box.querySelector('[data-cp-speed]');
    if (v) v.textContent = s.label + ' · ' + s.name;
    if (h) h.textContent = s.hint;
    if (r) { r.value = String(idx); r.setAttribute('aria-valuetext', s.label + ' ' + s.name); }
    var ticks = box.querySelectorAll('.cp-speed__tick');
    for (var i = 0; i < ticks.length; i++) ticks[i].classList.toggle('is-on', i === idx);
  }

  /**
   * Wires every slider under rootEl: moving it changes the speed at once (the label follows) and, when the learner lets
   * go, saves it. save(value) returns a promise; a failed save keeps the speed for this page only.
   */
  function bind(rootEl, save) {
    if (!rootEl || rootEl.__cpSpeedBound) return;
    rootEl.__cpSpeedBound = true;
    rootEl.addEventListener('input', function (ev) {
      var t = ev.target;
      if (!t || !t.matches || !t.matches('[data-cp-speed]')) return;
      var s = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, Number(t.value) || 0))];
      setSpeed(s.value);
      var box = t.closest('[data-cp-speed-box]');
      if (box) refreshBox(box);
    });
    rootEl.addEventListener('change', function (ev) {
      var t = ev.target;
      if (!t || !t.matches || !t.matches('[data-cp-speed]')) return;
      if (typeof save === 'function') {
        try { save(current); } catch (e) { /* kept locally */ }
      }
    });
  }

  /** The default saver: the display endpoint of the main path (JpathApi carries the session). */
  function saveToServer(value) {
    if (!root || !root.JpathApi || typeof root.JpathApi.send !== 'function') return null;
    return Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/display', { speed: value })).catch(function () { return null; });
  }

  return {
    SPEEDS: SPEEDS,
    normalize: normalize,
    stopOf: stopOf,
    setSpeed: setSpeed,
    speed: speed,
    fromDisplay: fromDisplay,
    play: play,
    fallbackTts: fallbackTts,
    sliderHtml: sliderHtml,
    bind: bind,
    saveToServer: saveToServer
  };
}));
