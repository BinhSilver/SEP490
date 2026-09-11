/* Flashcard API client — JWT via JlasAuth when available */
(function (global) {
  'use strict';

  function authHeaders(json) {
    if (global.JlasAuth && typeof global.JlasAuth.authHeaders === 'function') {
      return global.JlasAuth.authHeaders();
    }
    var h = {};
    if (json !== false) h['Content-Type'] = 'application/json';
    var t = localStorage.getItem('accessToken');
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  async function parse(res) {
    if (res.status === 204) return null;
    var text = await res.text();
    var body = null;
    try { body = text ? JSON.parse(text) : null; } catch (e) { body = { message: text }; }
    if (res.status === 401) {
      if (global.JlasAuth && typeof global.JlasAuth.clearSession === 'function') {
        global.JlasAuth.clearSession();
      }
      var redirectTarget = global.location.pathname + global.location.search;
      if (!/^\/login(?:\.html)?\/?$/i.test(global.location.pathname)) {
        global.location.replace('/login?redirect=' + encodeURIComponent(redirectTarget));
      }
    }
    if (!res.ok) {
      var validationMessage = body && body.errors && Object.keys(body.errors).length
        ? body.errors[Object.keys(body.errors)[0]]
        : null;
      var msg = validationMessage || (body && (body.message || body.error)) || res.statusText || 'Request failed';
      var err = new Error(msg);
      err.status = res.status;
      err.body = body;
      throw err;
    }
    // ApiResponse wrapper
    if (body && typeof body === 'object' && 'success' in body) {
      return body.data !== undefined ? body.data : body;
    }
    return body;
  }

  var api = {
    get: async function (path) {
      var r = await fetch(path, { headers: authHeaders() });
      return parse(r);
    },
    post: async function (path, body) {
      var r = await fetch(path, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify(body ?? {})
      });
      return parse(r);
    },
    put: async function (path, body) {
      var r = await fetch(path, {
        method: 'PUT',
        headers: authHeaders(),
        body: JSON.stringify(body ?? {})
      });
      return parse(r);
    },
    del: async function (path) {
      var r = await fetch(path, { method: 'DELETE', headers: authHeaders() });
      return parse(r);
    },
    upload: async function (path, file) {
      var fd = new FormData();
      fd.append('file', file);
      var h = authHeaders(false);
      delete h['Content-Type'];
      var r = await fetch(path, { method: 'POST', headers: h, body: fd });
      return parse(r);
    }
  };

  global.FlashApi = api;
})(window);
