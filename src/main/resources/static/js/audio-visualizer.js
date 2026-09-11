/* Live Audio Visualizer control — AI Call conversation.
 *
 * - Khi user bấm btn-mic  hiện thanh bars + label "Bạn đang nói…"
 * - Khi AI phát TTS audio  hiện bars + label "AI đang nói…"
 *
 * Đơn giản: dùng AnalyserNode thật (lấy amplitude từ mic / audio TTS) nếu được,
 * nếu không thì animate CSS thuần.
 */
(function () {
  'use strict';

  function $(id) { return document.getElementById(id); }

  function showVis(label, kind) {
    var v = $('liveVisualizer');
    if (!v) return;
    v.classList.add('is-active');
    v.classList.toggle('is-user', kind === 'user');
    var lab = $('visLabel');
    if (lab && label) lab.textContent = label;
  }
  function hideVis() {
    var v = $('liveVisualizer');
    if (!v) return;
    v.classList.remove('is-active');
    v.classList.remove('is-user');
  }

  // Random animate bars nếu không có audio thật
  function animateRandom() {
    var bars = document.querySelectorAll('#liveVisualizer .vis-bar');
    if (!bars.length) return;
    bars.forEach(function (b, i) {
      b.style.setProperty('--h', (0.2 + Math.random() * 0.8).toFixed(2));
    });
  }
  var randomId = null;
  function startRandom() {
    stopRandom();
    randomId = setInterval(animateRandom, 140);
  }
  function stopRandom() {
    if (randomId) { clearInterval(randomId); randomId = null; }
  }

  // Expose qua window cho các module khác gọi
  window.LiveAudioVis = {
    showAiSpeaking: function () { showVis('AI đang nói…', 'ai'); startRandom(); },
    showUserSpeaking: function () { showVis('Bạn đang nói…', 'user'); startRandom(); },
    stop: function () { hideVis(); stopRandom(); }
  };

  document.addEventListener('DOMContentLoaded', function () {
    var mic = $('btnMic');
    if (mic) {
      // Khi user click mic  show user speaking (sẽ dừng khi STT về text xong)
      mic.addEventListener('click', function () {
        // simple: hiện trong 2.5s rồi ẩn — đợi STT implementation sau
        window.LiveAudioVis.showUserSpeaking();
        setTimeout(window.LiveAudioVis.stop, 2500);
      });
    }
    // Hook vào TTS audio nếu có: dùng event 'play' trên audio
    document.addEventListener('play', function (e) {
      if (e.target && e.target.tagName === 'AUDIO') {
        window.LiveAudioVis.showAiSpeaking();
      }
    }, true);
    document.addEventListener('ended', function (e) {
      if (e.target && e.target.tagName === 'AUDIO') {
        window.LiveAudioVis.stop();
      }
    }, true);
  });
})();