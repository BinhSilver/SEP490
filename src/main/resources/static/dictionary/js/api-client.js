/* Dictionary API client — theo đúng pattern FlashApi (flashcard/js/api-client.js) */
(function (global) {
  'use strict';

  function authHeaders() {
    if (global.JlasAuth && typeof global.JlasAuth.authHeaders === 'function') {
      return global.JlasAuth.authHeaders();
    }
    var h = {};
    var t = localStorage.getItem('accessToken');
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  async function parse(res) {
    var text = await res.text();
    var body = null;
    try { body = text ? JSON.parse(text) : null; } catch (e) { body = { message: text }; }
    if (!res.ok) {
      var msg = (body && body.message) || res.statusText || 'Request failed';
      var err = new Error(msg);
      err.status = res.status;
      err.body = body;
      throw err;
    }
    if (body && typeof body === 'object' && 'success' in body) {
      return body.data !== undefined ? body.data : body;
    }
    return body;
  }

  var api = {
    search: function (query, page, size) {
      var params = new URLSearchParams({ q: query, page: String(page || 0), size: String(size || 20) });
      return fetch('/api/dictionary/search?' + params.toString(), { headers: authHeaders() }).then(parse);
    },
    vocabularyDetail: function (id) {
      return fetch('/api/dictionary/vocabulary/' + encodeURIComponent(id), { headers: authHeaders() }).then(parse);
    },
    kanjiDetail: function (character) {
      return fetch('/api/dictionary/kanji/' + encodeURIComponent(character), { headers: authHeaders() }).then(parse);
    },
    /** Nhận diện Kanji vẽ tay: payload = { width, height, strokes: [[[x, y], ...], ...] }. */
    recognizeHandwriting: function (payload, signal) {
      var headers = authHeaders();
      headers['Content-Type'] = 'application/json';
      return fetch('/api/dictionary/handwriting/recognize', {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(payload),
        signal: signal
      }).then(parse);
    },
    history: function () {
      return fetch('/api/dictionary/history', { headers: authHeaders() }).then(parse);
    }
  };

  global.DictApi = api;
})(window);
