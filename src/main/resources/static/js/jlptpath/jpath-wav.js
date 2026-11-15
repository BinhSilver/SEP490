/* [JLAS-W0108] F72 — đổi bản ghi âm của trình duyệt (webm/ogg/mp4) sang WAV PCM 16 kHz mono ngay trên máy người học.
 *
 * Azure chấm phát âm chỉ nhận WAV PCM (hoặc OGG Opus); trước đây máy chủ đổi bằng ffmpeg, máy chủ thiếu ffmpeg là mọi
 * lượt chấm hỏng. Gửi WAV chuẩn thì máy chủ chuyển thẳng cho Azure.
 *
 * window.JpathWav:
 *   toWav(blob, env?) → Promise<{blob, name}>  WAV 16 kHz mono; trình duyệt không đổi được thì trả lại blob gốc
 *                                              (name theo đuôi gốc) để máy chủ thử ffmpeg dự phòng
 *   encodeWav(Float32Array, sampleRate) → ArrayBuffer  PCM 16-bit little-endian, có header RIFF
 *   downmix(channels[]) → Float32Array          trung bình các kênh
 *   resample(Float32Array, fromRate, toRate) → Float32Array  nội suy tuyến tính (đủ cho giọng nói)
 *   extFor(type) → 'wav' | 'ogg' | 'm4a' | 'webm'
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathWav = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';
  var TARGET_RATE = 16000;

  function extFor(type) {
    var t = String(type || '').toLowerCase();
    if (/wav/.test(t)) return 'wav';
    if (/ogg/.test(t)) return 'ogg';
    if (/mp4|m4a|aac/.test(t)) return 'm4a';
    return 'webm';
  }

  function downmix(channels) {
    if (!channels || !channels.length) return new Float32Array(0);
    if (channels.length === 1) return channels[0];
    var n = channels[0].length;
    var out = new Float32Array(n);
    for (var c = 0; c < channels.length; c++) {
      var ch = channels[c];
      for (var i = 0; i < n; i++) out[i] += ch[i] / channels.length;
    }
    return out;
  }

  function resample(samples, fromRate, toRate) {
    if (!samples || fromRate === toRate || !fromRate) return samples;
    var ratio = fromRate / toRate;
    var n = Math.max(1, Math.floor(samples.length / ratio));
    var out = new Float32Array(n);
    for (var i = 0; i < n; i++) {
      var pos = i * ratio;
      var j = Math.floor(pos);
      var frac = pos - j;
      var a = samples[j] || 0;
      var b = j + 1 < samples.length ? samples[j + 1] : a;
      out[i] = a + (b - a) * frac;
    }
    return out;
  }

  function encodeWav(samples, sampleRate) {
    var n = samples.length;
    var buffer = new ArrayBuffer(44 + n * 2);
    var v = new DataView(buffer);
    function str(off, s) { for (var i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i)); }
    str(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); str(8, 'WAVE');
    str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, sampleRate, true); v.setUint32(28, sampleRate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    str(36, 'data'); v.setUint32(40, n * 2, true);
    for (var i = 0; i < n; i++) {
      var s = Math.max(-1, Math.min(1, samples[i] || 0));
      v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
    }
    return buffer;
  }

  function original(blob) {
    return { blob: blob, name: 'speech.' + extFor(blob && blob.type) };
  }

  function toWav(blob, env) {
    var e = env || root || {};
    var Ctx = e.AudioContext || e.webkitAudioContext;
    if (!blob || !Ctx || typeof blob.arrayBuffer !== 'function' || typeof e.Blob !== 'function') {
      return Promise.resolve(original(blob));
    }
    if (/wav/i.test(blob.type || '')) return Promise.resolve(original(blob));
    var ctx;
    return blob.arrayBuffer().then(function (bytes) {
      ctx = new Ctx();
      return new Promise(function (resolve, reject) {
        // old Safari only has the callback form of decodeAudioData
        var p = ctx.decodeAudioData(bytes, resolve, reject);
        if (p && typeof p.then === 'function') p.then(resolve, reject);
      });
    }).then(function (audio) {
      var channels = [];
      for (var c = 0; c < audio.numberOfChannels; c++) channels.push(audio.getChannelData(c));
      var mono = resample(downmix(channels), audio.sampleRate, TARGET_RATE);
      if (!mono.length) return original(blob);
      return { blob: new e.Blob([encodeWav(mono, TARGET_RATE)], { type: 'audio/wav' }), name: 'speech.wav' };
    }).catch(function () {
      return original(blob);
    }).then(function (result) {
      if (ctx && typeof ctx.close === 'function') { try { ctx.close(); } catch (err) { /* already closed */ } }
      return result;
    });
  }

  return { TARGET_RATE: TARGET_RATE, toWav: toWav, encodeWav: encodeWav, downmix: downmix, resample: resample, extFor: extFor };
});
