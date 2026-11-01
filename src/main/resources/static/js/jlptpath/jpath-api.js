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
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var MSG_BUSY = 'Máy chủ đang bận. Thử lại sau.';
  var MSG_NETWORK = 'Không kết nối được máy chủ. Thử lại sau.';
  var MSG_UNAUTHORIZED = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';

  function failure(code, message, status) {
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
    return envelope.data === undefined || envelope.data === null ? null : envelope.data;
  }

  function get(url) {
    return send('GET', url);
  }

  return { get: get, send: send };
});
