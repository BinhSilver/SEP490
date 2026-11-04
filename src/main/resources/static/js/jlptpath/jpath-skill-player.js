/* [JLAS-W0108] jpath-skill-player.js — audio player of the Listening sets (Plan 3 Task 20).
 *
 * window.JpathSkillPlayer = { create(el, opts), controlsHtml(state) }
 *   - create(el, {src, slowRate = 0.75, allowReplay = true, sound = true, audio}) draws ▶ Phát / ⏸ Dừng, "Nghe lại"
 *     and "Nghe chậm" (only when replay is allowed) and returns {play(), pause(), replay(), setSlow(on), on(name, fn),
 *     state()}. 'ended' fires once per listen; with allowReplay false the play button is off after the first listen
 *     (timed section practice, #40). sound false (Cài đặt) never starts the audio by itself; the learner presses ▶.
 *   - opts.audio lets tests pass a fake element ({play, pause, currentTime, playbackRate, addEventListener}).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathSkillPlayer = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  /** Buttons for a state {playing, listened, slow, allowReplay}. */
  function controlsHtml(st) {
    var s = st || {};
    var locked = !s.allowReplay && s.listened && !s.playing;
    var main = '<button type="button" class="jp-btn jp-btn--primary" data-player="toggle"' + (locked ? ' disabled' : '')
      + ' aria-pressed="' + (s.playing ? 'true' : 'false') + '">' + (s.playing ? icon('pause') + ' Dừng' : (locked ? 'Đã nghe' : icon('play') + ' Phát'))
      + '</button>';
    if (!s.allowReplay) return '<div class="jp-player">' + main + '</div>';
    return '<div class="jp-player">' + main
      + ' <button type="button" class="jp-btn" data-player="replay">Nghe lại</button>'
      + ' <button type="button" class="jp-btn" data-player="slow" aria-pressed="' + (s.slow ? 'true' : 'false') + '">'
      + 'Nghe chậm</button></div>';
  }

  function create(el, opts) {
    var o = opts || {};
    var slowRate = o.slowRate || 0.75;
    var audio = o.audio || (root && root.document ? root.document.createElement('audio') : null);
    var st = { playing: false, listened: false, slow: false, allowReplay: o.allowReplay !== false };
    var handlers = { ended: [] };
    if (audio && o.src) { audio.src = o.src; audio.preload = 'none'; }

    function draw() { if (el) el.innerHTML = controlsHtml(st); }

    function play() {
      if (!audio || st.playing || (!st.allowReplay && st.listened)) return;
      audio.playbackRate = st.slow ? slowRate : 1;
      st.playing = true;
      draw();
      var p = audio.play();
      if (p && typeof p.catch === 'function') p.catch(function () { st.playing = false; draw(); });
    }

    function pause() {
      if (!audio || !st.playing) return;
      // a section listen cannot be paused and resumed (one listen only)
      if (!st.allowReplay) return;
      audio.pause();
      st.playing = false;
      draw();
    }

    function replay() {
      if (!audio || !st.allowReplay) return;
      audio.currentTime = 0;
      st.playing = false;
      play();
    }

    function setSlow(on) {
      if (!st.allowReplay) return;
      st.slow = !!on;
      if (audio) audio.playbackRate = st.slow ? slowRate : 1;
      draw();
    }

    if (audio && typeof audio.addEventListener === 'function') {
      audio.addEventListener('ended', function () {
        st.playing = false;
        st.listened = true;
        draw();
        handlers.ended.forEach(function (fn) { fn(); });
      });
    }
    if (el && typeof el.addEventListener === 'function') {
      el.addEventListener('click', function (ev) {
        var b = ev.target && ev.target.closest ? ev.target.closest('[data-player]') : null;
        if (!b || b.disabled) return;
        var what = b.getAttribute('data-player');
        if (what === 'toggle') { if (st.playing) pause(); else play(); }
        else if (what === 'replay') replay();
        else if (what === 'slow') setSlow(!st.slow);
      });
    }
    draw();
    if (o.autoplay && o.sound !== false) play();

    return { play: play, pause: pause, replay: replay, setSlow: setSlow,
      on: function (name, fn) { (handlers[name] = handlers[name] || []).push(fn); },
      state: function () { return Object.assign({}, st); } };
  }

  return { create: create, controlsHtml: controlsHtml };
});
