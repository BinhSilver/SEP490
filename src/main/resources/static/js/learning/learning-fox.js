/* [JLAS-W0108] learning-fox.js (owner 23/11): the round fox of the learning flow behaves like the home page fox.
 *
 * Every <img> from /images/mascots/fox/ (also ones the page scripts add later) becomes the live sprite of
 * home-mascot.js: the head turns toward the mouse, a click shows a reaction (blink, then heart / sparkle / delighted,
 * dizzy after four quick clicks) with a small squash. The mood in the file name (celebrate, cheer, …) is shown first
 * for a moment, then the fox looks around. Needs home-mascot.js loaded before; without it the still images stay.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.LearningFox = api;
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var SHEETS = '/images/mascots/page-mascot/';
  var MOOD_MS = 1600;
  // still image name → the reaction cell shown first (null = straight to looking around)
  var MOODS = {
    'celebrate-30': 'wink', 'cheer-30': 'sparkle', 'confused-30': 'surprised', 'crying-30': 'dizzy',
    'resting-30': 'sleepy', 'announcing': 'delighted', 'waving': 'delighted', 'encouraging': 'blink',
    'love': 'heart', 'blush': 'bashful', 'thinking-30': null, 'preparing': null
  };

  /** Mood reaction for a still image path, or null. */
  function moodOf(src) {
    var m = /kitsu-([a-z0-9-]+?)(?:-128)?\.webp/.exec(String(src || ''));
    return m && Object.prototype.hasOwnProperty.call(MOODS, m[1]) ? MOODS[m[1]] : null;
  }

  var foxes = [];
  var pointer = null;
  var frame = 0;
  var listening = false;

  function matches(q) { return !!(root && root.matchMedia && root.matchMedia(q).matches); }

  function lookAll() {
    frame = 0;
    if (!pointer) return;
    var M = root.HomeMascot;
    foxes.forEach(function (f) {
      if (!f.el.isConnected) return;
      var box = f.el.getBoundingClientRect();
      if (!box.width) return;
      var r = M.aim(pointer.x - (box.left + box.width / 2), pointer.y - (box.top + box.height / 2), f.sector);
      f.sector = r.sector;
      if (r.direction !== f.direction) {
        f.direction = r.direction;
        f.dir.style.backgroundPosition = M.cellPosition(M.DIRECTIONS.indexOf(f.direction));
      }
    });
    foxes = foxes.filter(function (f) { return f.el.isConnected; });
  }

  function schedule() { if (!frame) frame = root.requestAnimationFrame(lookAll); }

  function listen() {
    if (listening || !matches('(hover: hover) and (pointer: fine)')) return;
    listening = true;
    root.addEventListener('pointermove', function (e) { pointer = { x: e.clientX, y: e.clientY }; schedule(); }, { passive: true });
    root.addEventListener('scroll', schedule, { passive: true });
  }

  /** Swaps one still fox image for the live sprite, same classes and size. */
  function upgrade(img) {
    var M = root.HomeMascot;
    if (!M || !img || img.dataset.lfDone) return;
    img.dataset.lfDone = '1';
    var box = img.getBoundingClientRect();
    var w = box.width || Number(img.getAttribute('width')) || 64;
    var h = box.height || Number(img.getAttribute('height')) || w;
    var el = root.document.createElement('span');
    el.className = (img.className ? img.className + ' ' : '') + 'lf-fox';
    el.setAttribute('aria-hidden', 'true');
    el.style.width = w + 'px';
    el.style.height = h + 'px';
    el.innerHTML = M.html(SHEETS + 'fox-directions.webp', SHEETS + 'fox-reactions.webp');
    img.replaceWith(el);
    var f = {
      el: el, dir: el.querySelector('.hm-mascot__dir'), react: el.querySelector('.hm-mascot__react'),
      squash: el.querySelector('.hm-mascot__squash'), direction: 'center', sector: -1, timers: [], boops: { count: 0, at: 0 }
    };
    foxes.push(f);

    function show(reaction) {
      if (reaction) f.react.style.backgroundPosition = M.cellPosition(M.REACTIONS.indexOf(reaction));
      el.classList.toggle('is-reacting', !!reaction);
    }
    var mood = moodOf(img.getAttribute('src'));
    if (mood) {
      show(mood);
      f.timers.push(root.setTimeout(function () { show(null); }, MOOD_MS));
    }
    el.addEventListener('click', function () {
      f.timers.forEach(root.clearTimeout);
      f.timers = [];
      var r = M.boop(f.boops, Date.now());
      f.boops = r.boops;
      r.steps.forEach(function (s) {
        if (s.at === 0) show(s.reaction);
        else f.timers.push(root.setTimeout(function () { show(s.reaction); }, s.at));
      });
      if (matches('(prefers-reduced-motion: reduce)') || typeof f.squash.animate !== 'function') return;
      f.squash.animate([
        { transform: 'scale(1, 1)', easing: 'ease-in' },
        { transform: 'scale(1.10, 0.86)', offset: 0.18, easing: 'ease-out' },
        { transform: 'scale(0.95, 1.08)', offset: 0.45, easing: 'ease-in-out' },
        { transform: 'scale(1.03, 0.97)', offset: 0.72, easing: 'ease-in-out' },
        { transform: 'scale(1, 1)' }
      ], { duration: 420, easing: 'linear' });
    });
    listen();
  }

  function scan(node) {
    if (!node || !node.querySelectorAll) return;
    if (node.matches && node.matches('img[src*="/mascots/fox/"]')) upgrade(node);
    node.querySelectorAll('img[src*="/mascots/fox/"]').forEach(upgrade);
  }

  function start() {
    if (!root || !root.document || !root.HomeMascot) return;
    scan(root.document.body);
    if (typeof root.MutationObserver === 'function') {
      new root.MutationObserver(function (list) {
        list.forEach(function (m) { m.addedNodes.forEach(scan); });
      }).observe(root.document.body, { childList: true, subtree: true });
    }
  }

  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }

  return { moodOf: moodOf, upgrade: upgrade, scan: scan };
});
