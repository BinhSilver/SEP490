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
    // Timing for a subtitle added by hand, checked with the same rules that block publishing.
    newRange: (function () {
      const MIN_LENGTH = 0.3;
      const DEFAULT_LENGTH = 3;
      // The publish check only reports overlaps larger than this.
      const OVERLAP_TOLERANCE = 0.15;
      const round = value => Math.round(value * 100) / 100;
      const clock = seconds => {
        const m = Math.floor(seconds / 60);
        const s = seconds - m * 60;
        return m + ':' + (s < 10 ? '0' : '') + s.toFixed(1);
      };
      const sorted = subs => subs.map((sub, index) => ({ sub, index }))
        .filter(item => Number.isFinite(item.sub.startTime) && Number.isFinite(item.sub.endTime))
        .sort((a, b) => a.sub.startTime - b.sub.startTime);

      /** Free gaps of the timeline, in time order. */
      function gaps(subs, duration) {
        const result = [];
        let cursor = 0;
        sorted(subs).forEach(({ sub }) => {
          if (sub.startTime - cursor >= MIN_LENGTH) result.push({ start: cursor, end: sub.startTime });
          cursor = Math.max(cursor, sub.endTime);
        });
        if (duration - cursor >= MIN_LENGTH) result.push({ start: cursor, end: duration });
        return result;
      }

      /** The free slot at {@code at}, else the nearest one: starts there and lasts up to 3 seconds. */
      function suggest(subs, duration, at) {
        const free = gaps(subs, duration);
        if (!free.length) return null;
        const time = Math.min(Math.max(0, at || 0), duration);
        const slot = free.find(gap => time >= gap.start && time < gap.end - MIN_LENGTH)
          || free.slice().sort((a, b) => distance(a, time) - distance(b, time))[0];
        const start = time >= slot.start && time < slot.end - MIN_LENGTH ? time : slot.start;
        return { start: round(start), end: round(Math.min(slot.end, start + DEFAULT_LENGTH)) };
      }
      function distance(gap, time) { return time < gap.start ? gap.start - time : Math.max(0, time - gap.end); }

      /** Problems that would block publishing, plus a free slot to fix an overlap with one click. */
      function check(subs, duration, start, end) {
        const errors = [];
        if (!Number.isFinite(start) || !Number.isFinite(end)) {
          return { ok: false, errors: ['Hãy nhập thời gian bắt đầu và kết thúc.'], overlaps: [], fit: null };
        }
        if (start < 0) errors.push('Thời gian bắt đầu không được âm.');
        if (end <= start) errors.push('Thời gian kết thúc phải lớn hơn thời gian bắt đầu.');
        else if (end - start < MIN_LENGTH) errors.push('Câu cần dài ít nhất 0,3 giây.');
        if (Number.isFinite(duration) && duration > 0 && end > duration + 0.05) {
          errors.push('Kết thúc vượt quá độ dài video (' + clock(duration) + ').');
        }
        const overlaps = sorted(subs).filter(({ sub }) =>
          start < sub.endTime - OVERLAP_TOLERANCE && end > sub.startTime + OVERLAP_TOLERANCE);
        overlaps.forEach(({ sub, index }) => errors.push('Chồng thời gian với câu #' + (index + 1)
          + ' (' + clock(sub.startTime) + ' – ' + clock(sub.endTime) + ').'));
        let fit = null;
        if (overlaps.length) {
          const slot = gaps(subs, duration).find(gap => start < gap.end && end > gap.start)
            || suggest(subs, duration, start);
          if (slot) fit = { start: round(Math.max(slot.start, Math.min(start, slot.end - MIN_LENGTH))),
            end: round(Math.min(slot.end, Math.max(end, slot.start + MIN_LENGTH))) };
        }
        return { ok: errors.length === 0, errors, overlaps: overlaps.map(item => item.sub.id), fit };
      }

      /** List position that keeps subtitles in time order. */
      function insertIndex(subs, start) {
        const index = subs.findIndex(sub => sub.startTime > start);
        return index < 0 ? subs.length : index;
      }

      return { gaps, suggest, check, insertIndex, clock, MIN_LENGTH };
    }()),
    response: async function (res) {
      const body = await res.json().catch(() => null);
      if (!res.ok || body?.success === false) throw new Error(body?.message || body?.error || 'Không xử lý được yêu cầu. Hãy thử lại.');
      if (!body) throw new Error('Máy chủ trả về dữ liệu không hợp lệ.');
      return body;
    }
  };
}());
