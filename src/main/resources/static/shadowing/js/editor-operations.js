(function () {
  'use strict';
  window.ShadowingEditorOperations = {
    playback: function (media, changed, failed) {
      let segment = null;
      let revision = 0;
      function stop() {
        revision++;
        segment = null;
        media.pause();
        changed();
      }
      function playing(id) { return segment?.id === id && !media.paused && !media.ended; }
      async function toggle(id, start, end) {
        if (segment?.id === id) { stop(); return; }
        if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start) {
          failed('Thời gian kết thúc phải lớn hơn thời gian bắt đầu.');
          return;
        }
        const request = ++revision;
        segment = { id, start, end };
        media.currentTime = start;
        try { await media.play(); }
        catch (error) {
          if (request !== revision) return;
          segment = null;
          failed('Không phát được đoạn này. Hãy kiểm tra video rồi thử lại.');
        }
        changed();
      }
      media.addEventListener('timeupdate', function () {
        if (segment && (media.currentTime >= segment.end || media.currentTime < segment.start - 0.15)) stop();
      });
      media.addEventListener('seeking', function () {
        if (segment && (media.currentTime >= segment.end || media.currentTime < segment.start - 0.15)) stop();
      });
      ['play', 'pause', 'ended'].forEach(event => media.addEventListener(event, function () {
        // A queued pause event from the previous segment may arrive after a new play.
        if (event === 'ended' || (event === 'pause' && media.paused)) { revision++; segment = null; }
        changed();
      }));
      return { toggle, playing, stop, full: function () { revision++; segment = null; changed(); } };
    },
    // Keep edits from different cards, and serialize saves before an AI request.
    saves: function (write) {
      const pending = new Map();
      let flight = null;
      return {
        get dirty() { return pending.size > 0 || flight !== null; },
        queue: function (id, body) { pending.set(id, body); },
        flush: function () {
          if (flight) return flight;
          flight = (async function () {
            while (pending.size) {
              const [id, body] = pending.entries().next().value;
              await write(id, body);
              // Edits made during this request must still be saved.
              if (pending.get(id) === body) pending.delete(id);
            }
          }()).finally(function () { flight = null; });
          return flight;
        }
      };
    },
    response: async function (res) {
      const body = await res.json().catch(() => null);
      if (!res.ok || body?.success === false) throw new Error(body?.message || body?.error || 'Không xử lý được yêu cầu. Hãy thử lại.');
      if (!body) throw new Error('Máy chủ trả về dữ liệu không hợp lệ.');
      return body;
    }
  };
}());
