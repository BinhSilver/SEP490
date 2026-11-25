/** Add the navbar's gold coin to displayed CP currency, including live popup content. */
(function () {
  'use strict';
  if (window.JlasCpUnit) return;

  var token = /(?<![\p{L}_])CP(?![\p{L}\p{N}_])/gu;
  var ignored = 'script,style,title,textarea,input,select,option,pre,code,kbd,samp,svg,math,[contenteditable]:not([contenteditable="false"]),.jlas-cp-unit,.jlas-cp-icon,[data-cp-plain]';
  var coinSelector = '.jlas-cp-icon,.reward-chip__coin,.fa-coins';

  function matches(text) { return Array.from(text.matchAll(token)); }

  function decorate(root) {
    if (!root || (root.nodeType !== 1 && root.nodeType !== 3)) return;
    var parent = root.nodeType === 3 ? root.parentElement : root;
    if (!parent || parent.closest(ignored)) return;
    var nodes = [];
    if (root.nodeType === 3) nodes.push(root);
    else {
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) nodes.push(walker.currentNode);
    }
    nodes.forEach(function (node) {
      var element = node.parentElement;
      if (!element || element.closest(ignored)) return;
      var found = matches(node.data);
      if (!found.length) return;
      // Reuse a leading coin on a compact control instead of adding a second one.
      var label = element.closest('button,a,label,.ga-chip,.reward-chip__part') || element;
      var existing = label.querySelector(coinSelector);
      if (existing && matches(label.textContent).length === 1) {
        existing.classList.add('jlas-cp-icon');
        existing.setAttribute('aria-hidden', 'true');
        return;
      }
      var fragment = document.createDocumentFragment(), offset = 0;
      found.forEach(function (match) {
        // Keep the amount and its unit on the same line when a sentence wraps.
        var amount = node.data.slice(offset, match.index).match(/\d+(?:[.,]\d+)*[ \u00a0]*$/u);
        var start = match.index - (amount ? amount[0].length : 0);
        fragment.appendChild(document.createTextNode(node.data.slice(offset, start)));
        var unit = document.createElement('span');
        unit.className = 'jlas-cp-unit';
        var coin = document.createElement('span');
        coin.className = 'jlas-cp-icon';
        coin.setAttribute('aria-hidden', 'true');
        if (amount) unit.appendChild(document.createTextNode(amount[0]));
        unit.append(coin, document.createTextNode('CP'));
        fragment.appendChild(unit);
        offset = match.index + match[0].length;
      });
      fragment.appendChild(document.createTextNode(node.data.slice(offset)));
      node.replaceWith(fragment);
    });
  }

  window.JlasCpUnit = { decorate: decorate };
  if (!document.querySelector('link[href*="/css/components/cp-unit.css"]')) {
    var stylesheet = document.createElement('link');
    stylesheet.rel = 'stylesheet';
    stylesheet.href = '/css/components/cp-unit.css?v=20261123-gold';
    document.head.appendChild(stylesheet);
  }
  function start() {
    decorate(document.body);
    new MutationObserver(function (records) {
      var roots = new Set();
      records.forEach(function (record) {
        if (record.type === 'characterData') roots.add(record.target);
        else record.addedNodes.forEach(function (node) { roots.add(node); });
      });
      roots.forEach(decorate);
    }).observe(document.body, { childList: true, characterData: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
