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
    vocabularyContributions: function (id, page, size) {
      var params = new URLSearchParams({ page: String(page || 0), size: String(size || 5) });
      return fetch('/api/dictionary/vocabulary/' + encodeURIComponent(id) + '/contributions?' + params,
        { headers: authHeaders() }).then(parse);
    },
    addVocabularyContribution: function (id, content) {
      var headers = authHeaders();
      headers['Content-Type'] = 'application/json';
      return fetch('/api/dictionary/vocabulary/' + encodeURIComponent(id) + '/contributions',
        { method: 'POST', headers: headers, body: JSON.stringify({ content: content }) }).then(parse);
    },
    kanjiContributions: function (character, page, size) {
      var params = new URLSearchParams({ page: String(page || 0), size: String(size || 5) });
      return fetch('/api/dictionary/kanji/' + encodeURIComponent(character) + '/contributions?' + params,
        { headers: authHeaders() }).then(parse);
    },
    addKanjiContribution: function (character, content) {
      var headers = authHeaders();
      headers['Content-Type'] = 'application/json';
      return fetch('/api/dictionary/kanji/' + encodeURIComponent(character) + '/contributions',
        { method: 'POST', headers: headers, body: JSON.stringify({ content: content }) }).then(parse);
    },
    voteContribution: function (id, vote) {
      var headers = authHeaders();
      headers['Content-Type'] = 'application/json';
      return fetch('/api/dictionary/contributions/' + encodeURIComponent(id) + '/vote',
        { method: 'PUT', headers: headers, body: JSON.stringify({ vote: vote }) }).then(parse);
    },
    reportContribution: function (id) {
      return fetch('/api/dictionary/contributions/' + encodeURIComponent(id) + '/report',
        { method: 'POST', headers: authHeaders() }).then(parse);
    },
    deleteContribution: function (id) {
      return fetch('/api/dictionary/contributions/' + encodeURIComponent(id),
        { method: 'DELETE', headers: authHeaders() }).then(parse);
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
    /** Bảng bộ phận (bộ thủ) để tra Kanji, đã sắp theo số nét. */
    kanjiComponents: function () {
      return fetch('/api/dictionary/components', { headers: authHeaders() }).then(parse);
    },
    /** Kanji chứa tất cả bộ phận có id trong `ids`. */
    searchByComponents: function (ids, signal) {
      var params = new URLSearchParams({ ids: ids.join(',') });
      return fetch('/api/dictionary/components/search?' + params.toString(),
        { headers: authHeaders(), signal: signal }).then(parse);
    },
    history: function () {
      return fetch('/api/dictionary/history', { headers: authHeaders() }).then(parse);
    }
  };

  global.DictApi = api;
})(window);
