/* [JLAS-W0108] jpath-print.js — A4 writing sheets ("In").
 *
 * window.JpathPrint = { url(params), parse(json), bind(root) }
 *   - A list marks a control with data-jpath-print='{"scope":"LIST","tab":"KANJI","level":"N5","q":…}' (scope TODAY |
 *     LIST | KANA; KANA carries "script"). bind(root) wires each control once and opens url(params) in a new tab; it
 *     runs on DOMContentLoaded and on every document 'jpath:rendered' {detail:{root}}.
 *   - On the print page itself (body.jp-print) "In bảng này" calls window.print() and "Đóng" closes the tab.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathPrint = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var PAGE = '/learning/jlpt/print';
  // Query keys the print page reads (JpathPrintRequest), in a fixed order; anything else (page, view) is dropped.
  var KEYS = ['scope', 'tab', 'level', 'day', 'part', 'topic', 'state', 'q', 'script', 'radical', 'sort'];
  var BOUND = 'data-jpath-print-bound';

  /** The print page URL of params; null, undefined and blank values are left out. */
  function url(params) {
    var p = params || {};
    var parts = [];
    KEYS.forEach(function (k) {
      var v = p[k];
      if (v === null || v === undefined) return;
      var s = String(v).trim();
      if (!s) return;
      parts.push(k + '=' + encodeURIComponent(s));
    });
    return parts.length ? PAGE + '?' + parts.join('&') : PAGE;
  }

  /** The data-jpath-print value as an object; null when unusable. */
  function parse(json) {
    var raw;
    try {
      raw = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (e) {
      return null;
    }
    return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : null;
  }

  function openSheet(params) {
    var target = url(params);
    var opener = root && typeof root.open === 'function' ? root.open : null;
    if (opener) opener(target, '_blank', 'noopener');
  }

  /** Wires every [data-jpath-print] control under scope (scope included) once. Returns the number wired. */
  function bind(scope) {
    if (!scope) return 0;
    var nodes = [];
    if (typeof scope.getAttribute === 'function' && scope.getAttribute('data-jpath-print') !== null) nodes.push(scope);
    if (typeof scope.querySelectorAll === 'function') {
      Array.prototype.push.apply(nodes, Array.prototype.slice.call(scope.querySelectorAll('[data-jpath-print]')));
    }
    var count = 0;
    nodes.forEach(function (el) {
      if (el.getAttribute(BOUND) === '1') return;
      var params = parse(el.getAttribute('data-jpath-print'));
      if (!params) return;
      el.addEventListener('click', function (ev) {
        if (ev && typeof ev.preventDefault === 'function') ev.preventDefault();
        openSheet(parse(el.getAttribute('data-jpath-print')) || params);
      });
      el.setAttribute(BOUND, '1');
      count++;
    });
    return count;
  }

  function bindPrintPage(doc) {
    var printNow = doc.querySelector('[data-jpath-print-now]');
    if (printNow) printNow.addEventListener('click', function () { root.print(); });
    var close = doc.querySelector('[data-jpath-print-close]');
    if (close) {
      close.addEventListener('click', function () {
        root.close();
        // A tab the script did not open stays open: go back to the path instead.
        root.setTimeout(function () { if (!root.closed) root.location.href = '/learning'; }, 150);
      });
    }
  }

  function start(doc) {
    if (doc.body && doc.body.classList && doc.body.classList.contains('jp-print')) bindPrintPage(doc);
    else bind(doc.body);
  }

  var rootDoc = root && root.document;
  if (rootDoc && typeof rootDoc.addEventListener === 'function') {
    rootDoc.addEventListener('jpath:rendered', function (ev) { bind(ev && ev.detail && ev.detail.root); });
    if (rootDoc.readyState === 'loading') rootDoc.addEventListener('DOMContentLoaded', function () { start(rootDoc); });
    else if (rootDoc.body) start(rootDoc);
  }

  return { url: url, parse: parse, bind: bind };
});
