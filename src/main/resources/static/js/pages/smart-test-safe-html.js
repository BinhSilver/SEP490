/**
 * Smart Test question/passage markup from the question bank (e.g. 姉は東京の<u>大□</u>で勉強しています。) is shown as
 * formatting, never as raw markup: every "<" / ">" is escaped first, then only bare formatting tags from a fixed
 * list are re-enabled, without attributes. Scripts, handlers, links and images therefore always stay inert text.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SmartTestSafeHtml = api;
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var ALLOWED = 'u|b|strong|em|i|br|p|ruby|rt|rp|span|ul|ol|li|table|thead|tbody|tr|th|td';
  var TAG = new RegExp('&lt;(\\/?)(' + ALLOWED + ')\\b(?:[^&]|&(?!gt;))*?\\/?&gt;', 'gi');

  function html(value) {
    return String(value == null ? '' : value)
      .replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(TAG, function (_, close, tag) {
        tag = tag.toLowerCase();
        return tag === 'br' ? '<br>' : '<' + close + tag + '>';
      });
  }

  /** True when the text carries a formatting tag worth rendering (callers keep plain text otherwise). */
  function hasMarkup(value) {
    return /<\/?(?:u|b|strong|em|i|br|p|ruby|rt|rp)\b/i.test(String(value == null ? '' : value));
  }

  return { html: html, hasMarkup: hasMarkup };
}));
