/* [JLAS-W0108] jpath-offline-queue.js — keeps study events of one JLPT path session until the server has them.
 *
 * window.JpathOfflineQueue.create({sessionId, storage, send, setTimeout, onChange}) -> Queue
 *   enqueue(event) -> boolean   false once MAX_PENDING events are waiting
 *   flush() -> Promise<{sent, kept, dropped, result?}>   in order, BATCH per send; one run at a time
 *     send rejects {network:true} or a 5xx -> keep everything left, retry after BACKOFF_MS (last value repeats)
 *     send rejects 408 or 429              -> same as network: keep and retry with backoff
 *     send rejects 401 or 403              -> keep, no timer; the next flush() or resume() (after a new login) retries
 *     send rejects any other 4xx           -> drop that batch, onChange({dropped}), go on with the next one
 *   pending(), attach(win) ('online' -> flush), resume(), clear()
 * Events are saved under KEY_PREFIX + sessionId; when storage is missing or throws the queue lives in memory.
 * Resending is safe: the server applies events idempotently by eventId.
 * rejectedIds(result) (option): ids of the events the server refused in a sent batch; the run then stops there, the
 *   outcome carries those events as {rejected:[...]} and the events after them stay queued, in order, so the page can
 *   send them again on a fresh session (never a silent loss of later events).
 * F144: two tabs of one session share the key — every save and flush first takes in the events the other tab stored
 * (by clientEventId), leaving out those this tab already sent or dropped, so neither tab wipes the other's events.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathOfflineQueue = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var KEY_PREFIX = 'jpath.pending.';
  var BATCH = 50;
  var MAX_PENDING = 500;
  var BACKOFF_MS = [2000, 5000, 15000, 30000];

  function isAuthError(err) {
    return !!err && !err.network && (err.status === 401 || err.status === 403);
  }

  function isRejected(err) {
    return !!err && !err.network && typeof err.status === 'number' && err.status >= 400 && err.status < 500
      && err.status !== 408 && err.status !== 429 && !isAuthError(err);
  }

  function create(options) {
    var opts = options || {};
    var key = KEY_PREFIX + opts.sessionId;
    var storage = opts.storage || null;
    var send = opts.send;
    var schedule = opts.setTimeout || (root && root.setTimeout ? root.setTimeout.bind(root) : setTimeout);
    var onChange = typeof opts.onChange === 'function' ? opts.onChange : function () {};
    var rejectedIds = typeof opts.rejectedIds === 'function' ? opts.rejectedIds : function () { return []; };
    var gone = {};
    var events = load();
    var running = null;
    var failures = 0;
    var timerPending = false;

    function load() {
      if (!storage) return [];
      try {
        var raw = storage.getItem(key);
        var parsed = raw ? JSON.parse(raw) : [];
        return Array.isArray(parsed) ? parsed : [];
      } catch (e) {
        storage = null;
        return [];
      }
    }

    function idOf(e) {
      var id = e && (e.clientEventId || e.eventId);
      return id ? 'id:' + id : 'json:' + JSON.stringify(e);
    }

    function forget(list) { list.forEach(function (e) { gone[idOf(e)] = true; }); }

    /** Takes in the events another tab stored under the same key (not the ones this tab already sent or dropped). */
    function adopt() {
      if (!storage) return;
      var have = {};
      events.forEach(function (e) { have[idOf(e)] = true; });
      load().forEach(function (e) {
        var id = idOf(e);
        if (have[id] || gone[id]) return;
        have[id] = true;
        events.push(e);
      });
    }

    function save() {
      adopt();
      if (!storage) return;
      try { storage.setItem(key, JSON.stringify(events)); } catch (e) { storage = null; }
    }

    function enqueue(event) {
      if (events.length >= MAX_PENDING) return false;
      events.push(event);
      save();
      onChange({ pending: events.length });
      return true;
    }

    function scheduleRetry() {
      if (timerPending) return;
      var delay = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
      failures++;
      timerPending = true;
      schedule(function () { timerPending = false; return flush(); }, delay);
    }

    function run() {
      var outcome = { sent: 0, kept: 0, dropped: 0 };
      function next() {
        adopt();
        if (!events.length) return Promise.resolve(outcome);
        var batch = events.slice(0, BATCH);
        return Promise.resolve().then(function () { return send(batch); }).then(function (result) {
          events.splice(0, batch.length);
          forget(batch);
          save();
          failures = 0;
          outcome.sent += batch.length;
          outcome.result = result;
          var refused = {};
          (rejectedIds(result) || []).forEach(function (id) { refused['id:' + id] = true; });
          var rejected = batch.filter(function (e) { return refused[idOf(e)]; });
          if (rejected.length) {
            outcome.rejected = rejected;
            outcome.kept = events.length;
            return outcome;
          }
          return next();
        }, function (err) {
          if (isRejected(err)) {
            events.splice(0, batch.length);
            forget(batch);
            save();
            outcome.dropped += batch.length;
            onChange({ dropped: batch.length, error: err, pending: events.length });
            return next();
          }
          outcome.kept = events.length;
          if (isAuthError(err)) onChange({ authRequired: true, error: err, pending: events.length });
          else scheduleRetry();
          return outcome;
        });
      }
      return next().then(function (result) {
        onChange({ pending: events.length });
        return result;
      });
    }

    function flush() {
      if (running) return running;
      running = run().then(function (result) { running = null; return result; }, function (err) { running = null; throw err; });
      return running;
    }

    function pending() { return events.slice(); }

    function attach(win) {
      if (win && typeof win.addEventListener === 'function') {
        win.addEventListener('online', function () { return flush(); });
      }
    }

    function clear() {
      adopt();
      forget(events);
      events = [];
      if (!storage) return;
      try { storage.removeItem(key); } catch (e) { storage = null; }
    }

    return { enqueue: enqueue, flush: flush, resume: flush, pending: pending, attach: attach, clear: clear };
  }

  function uuid(cryptoObj) {
    if (cryptoObj && typeof cryptoObj.randomUUID === 'function') return cryptoObj.randomUUID();
    var bytes = new Array(16);
    if (cryptoObj && typeof cryptoObj.getRandomValues === 'function') {
      var arr = cryptoObj.getRandomValues(new Uint8Array(16));
      for (var i = 0; i < 16; i++) bytes[i] = arr[i];
    } else {
      for (var j = 0; j < 16; j++) bytes[j] = Math.floor(Math.random() * 256);
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = bytes.map(function (b) { return (b + 0x100).toString(16).slice(1); }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  return { KEY_PREFIX: KEY_PREFIX, BATCH: BATCH, MAX_PENDING: MAX_PENDING, BACKOFF_MS: BACKOFF_MS, create: create, uuid: uuid };
});
