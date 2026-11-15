/* [JLAS-W0108] jpath-api.js — the JLPT path pages' only HTTP client.
 *
 * window.JpathApi.get(url) / .send(method, url, body?) call the ApiResponse envelope endpoints and resolve to
 * envelope.data (null when absent). Failures reject with {code, message, status}:
 *   401                    -> code 'UNAUTHORIZED'
 *   envelope success:false -> envelope.code, or 'HTTP_<status>' when the envelope carries no code
 *   body is not JSON       -> 'HTTP_<status>'
 *   fetch itself fails     -> 'NETWORK', status 0
 * root.fetch and root.JlasAuth are read on every call, so the auth-session.js fetch wrapper (token refresh)
 * and late-loaded auth helpers are always the ones used.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathApi = api;
  // F34 (owner 10/11): one toast for short Learning messages, drawn by the team's JlasUI.toast (components.js);
  // returns false when no toast is available so the caller can keep its inline text
  if (root && !root.JlasToast) {
    root.JlasToast = {
      show: function (message, type) {
        var ui = root.JlasUI;
        if (!message || !ui || typeof ui.toast !== 'function') return false;
        ui.toast(message, type || 'success');
        return true;
      }
    };
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var MSG_BUSY = 'Máy chủ đang bận. Thử lại sau.';
  var MSG_NETWORK = 'Không kết nối được máy chủ. Thử lại sau.';
  var MSG_UNAUTHORIZED = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';

  // [JLAS-W0108] A5: one shared notice when the server cannot be reached or fails (5xx), so no page is left blank.
  var NOTICE_ID = 'jp-net-notice';
  function showNotice(text) {
    var doc = root && root.document;
    if (!doc || !doc.body) return;
    var el = doc.getElementById(NOTICE_ID);
    if (!el) {
      el = doc.createElement('div');
      el.id = NOTICE_ID;
      el.className = 'jp-net-notice';
      el.setAttribute('role', 'alert');
      el.innerHTML = '<span class="jp-net-notice__text"></span>'
        + '<button type="button" class="jp-net-notice__retry">Thử lại</button>'
        + '<button type="button" class="jp-net-notice__close" aria-label="Đóng">×</button>';
      el.querySelector('.jp-net-notice__retry').addEventListener('click', function () { root.location.reload(); });
      el.querySelector('.jp-net-notice__close').addEventListener('click', function () { el.hidden = true; });
      doc.body.appendChild(el);
    }
    el.querySelector('.jp-net-notice__text').textContent = text;
    el.hidden = false;
  }
  function hideNotice() {
    var el = root && root.document && root.document.getElementById(NOTICE_ID);
    if (el) el.hidden = true;
  }
  if (root && root.addEventListener) {
    root.addEventListener('offline', function () { showNotice('Mất kết nối mạng. Tiến độ sẽ lưu khi có mạng lại.'); });
    root.addEventListener('online', hideNotice);
  }

  // [JLAS-W0108] F82: an outside service that is down (AI key, base set not installed) is explained where it is used.
  var QUIET_CODES = ['EXTERNAL_SERVICE_ERROR', 'SERVICE_UNAVAILABLE', 'CPATH_CORE_UNAVAILABLE'];

  function failure(code, message, status) {
    if (code === 'NETWORK') showNotice(MSG_NETWORK);
    else if (status >= 500 && QUIET_CODES.indexOf(code) < 0) showNotice(MSG_BUSY);
    var error = new Error(message);
    error.code = code;
    error.status = status;
    return error;
  }

  function headers() {
    var auth = root && root.JlasAuth;
    if (auth && typeof auth.authHeaders === 'function') return auth.authHeaders();
    return { 'Content-Type': 'application/json' };
  }

  function parse(text) {
    if (!text) return undefined;
    try {
      return JSON.parse(text);
    } catch (e) {
      return undefined;
    }
  }

  function send(method, url, body) {
    var options = { method: method, headers: headers() };
    if (body !== undefined) options.body = JSON.stringify(body);

    var request;
    try {
      request = Promise.resolve(root.fetch(url, options));
    } catch (e) {
      request = Promise.reject(e);
    }

    return request.then(function (response) {
      return Promise.resolve(response.text()).then(parse, function () { return undefined; })
        .then(function (envelope) { return unwrap(response.status, envelope); });
    }, function () {
      throw failure('NETWORK', MSG_NETWORK, 0);
    });
  }

  function unwrap(status, envelope) {
    var isEnvelope = envelope !== null && typeof envelope === 'object';
    var message = isEnvelope && envelope.message ? envelope.message : null;
    if (status === 401) throw failure('UNAUTHORIZED', message || MSG_UNAUTHORIZED, status);
    if (!isEnvelope) throw failure('HTTP_' + status, MSG_BUSY, status);
    if (envelope.success === false || status < 200 || status >= 300) {
      throw failure(envelope.code || 'HTTP_' + status, message || MSG_BUSY, status);
    }
    hideNotice();
    return envelope.data === undefined || envelope.data === null ? null : envelope.data;
  }

  function get(url) {
    return send('GET', url);
  }

  /** POST of a FormData (a recording): the auth headers without the JSON Content-Type, so the browser sets the boundary. */
  function upload(url, form) {
    var h = Object.assign({}, headers());
    Object.keys(h).forEach(function (k) { if (k.toLowerCase() === 'content-type') delete h[k]; });
    var request;
    try {
      request = Promise.resolve(root.fetch(url, { method: 'POST', headers: h, body: form }));
    } catch (e) {
      request = Promise.reject(e);
    }
    return request.then(function (response) {
      return Promise.resolve(response.text()).then(parse, function () { return undefined; })
        .then(function (envelope) { return unwrap(response.status, envelope); });
    }, function () {
      throw failure('NETWORK', MSG_NETWORK, 0);
    });
  }

  return { get: get, send: send, upload: upload };
});
