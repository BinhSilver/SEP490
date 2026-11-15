/* [JLAS-W0108] F129 home-mascot.js — the fox in the corner of the home word cards.
 *
 * Owner 15/11: replaces the small Kitsu on the word-card menu button with a fox that turns its head toward the
 * pointer and reacts when poked. Plain-JS port of "page-mascot" 0.1.0 by Kamran Ahmed (MIT, the package needs
 * React, this project has none); the two 3x3 sprite sheets come from the same repo. Licence and credit:
 * /images/mascots/page-mascot/LICENSE.txt.
 *
 * home-word-deck.js calls HomeMascot.attach(button) on its menu button: the click still opens the menu, the fox
 * only adds the reaction. If this file does not load, the old Kitsu image stays.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.HomeMascot = api;
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var SHEETS = '/images/mascots/page-mascot/';
  var DIRECTIONS_SHEET = SHEETS + 'fox-directions.webp';
  var REACTIONS_SHEET = SHEETS + 'fox-reactions.webp';

  // cell order of the two sheets, left to right, top to bottom
  var DIRECTIONS = ['up-left', 'up', 'up-right', 'left', 'center', 'right', 'down-left', 'down', 'down-right'];
  var REACTIONS = ['blink', 'heart', 'sparkle', 'surprised', 'wink', 'bashful', 'sleepy', 'dizzy', 'delighted'];
  // clockwise from the right, matching atan2 with y pointing down
  var CLOCKWISE = ['right', 'down-right', 'down', 'down-left', 'left', 'up-left', 'up', 'up-right'];
  var SECTOR = (Math.PI * 2) / CLOCKWISE.length;
  var HYSTERESIS = 0.12; // rad past a sector edge before the head turns
  var DEAD_ZONE = 70;    // px around the fox where it just looks ahead
  var PAYOFFS = ['heart', 'sparkle', 'delighted'];
  var BOOP_PAYOFF = 120;
  var BOOP_END = 560;
  var DIZZY_AFTER = 4;
  var DIZZY_WINDOW = 1600;
  var DIZZY_END = 1100;
  var SQUASH_MS = 420;
  var SQUASH = [
    { transform: 'scale(1, 1)', easing: 'ease-in' },
    { transform: 'scale(1.10, 0.86)', offset: 0.18, easing: 'ease-out' },
    { transform: 'scale(0.95, 1.08)', offset: 0.45, easing: 'ease-in-out' },
    { transform: 'scale(1.03, 0.97)', offset: 0.72, easing: 'ease-in-out' },
    { transform: 'scale(1, 1)' }
  ];

  /** background-position of cell {@code index} on a sheet drawn at background-size 300%. */
  function cellPosition(index) {
    return (index % 3) * 50 + '% ' + Math.floor(index / 3) * 50 + '%';
  }

  function wrap(angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }

  /**
   * Head direction for a pointer at (dx, dy) from the fox's centre. {@code sector} is the last result's sector
   * (-1 = none): the head keeps it until the pointer is clearly past its edge, so it does not flicker.
   */
  function aim(dx, dy, sector) {
    if (Math.hypot(dx, dy) < DEAD_ZONE) return { sector: -1, direction: 'center' };
    var angle = Math.atan2(dy, dx);
    if (sector >= 0 && Math.abs(wrap(angle - sector * SECTOR)) < SECTOR / 2 + HYSTERESIS) {
      return { sector: sector, direction: CLOCKWISE[sector] };
    }
    var next = (Math.round(angle / SECTOR) + CLOCKWISE.length) % CLOCKWISE.length;
    return { sector: next, direction: CLOCKWISE[next] };
  }

  /**
   * What a poke at time {@code now} shows: a blink then a rotating payoff, or "dizzy" on the fourth quick poke.
   * Returns the new poke counter and the reactions to show, each {at: ms after the poke, reaction|null}.
   */
  function boop(boops, now) {
    var b = boops || { count: 0, at: 0 };
    var count = now - b.at < DIZZY_WINDOW ? b.count + 1 : 1;
    if (count >= DIZZY_AFTER) {
      return { boops: { count: 0, at: now }, steps: [{ at: 0, reaction: 'dizzy' }, { at: DIZZY_END, reaction: null }] };
    }
    return {
      boops: { count: count, at: now },
      steps: [
        { at: 0, reaction: 'blink' },
        { at: BOOP_PAYOFF, reaction: PAYOFFS[(count - 1) % PAYOFFS.length] },
        { at: BOOP_END, reaction: null }
      ]
    };
  }

  /** The sprite markup that goes inside the button (decorative: the button keeps its own label). */
  function html(directions, reactions) {
    return '<span class="hm-mascot" aria-hidden="true"><span class="hm-mascot__squash">'
      + '<span class="hm-mascot__layer hm-mascot__dir" style="background-image:url(\'' + directions + '\');background-position:'
      + cellPosition(DIRECTIONS.indexOf('center')) + '"></span>'
      + '<span class="hm-mascot__layer hm-mascot__react" style="background-image:url(\'' + reactions + '\');background-position:'
      + cellPosition(0) + '"></span>'
      + '</span></span>';
  }

  function matches(query) {
    return !!(root && root.matchMedia && root.matchMedia(query).matches);
  }

  /** Puts the fox inside {@code button}; the button's own click handler and label are left alone. */
  function attach(button) {
    if (!button || !root || button.classList.contains('hm-mascot-btn')) return;
    button.innerHTML = html(DIRECTIONS_SHEET, REACTIONS_SHEET);
    button.classList.add('hm-mascot-btn');
    var dir = button.querySelector('.hm-mascot__dir');
    var react = button.querySelector('.hm-mascot__react');
    var squash = button.querySelector('.hm-mascot__squash');
    var direction = 'center';
    var sector = -1;
    var pointer = null;
    var frame = 0;
    var timers = [];
    var boops = { count: 0, at: 0 };

    function show(reaction) {
      if (reaction) react.style.backgroundPosition = cellPosition(REACTIONS.indexOf(reaction));
      button.classList.toggle('is-reacting', !!reaction);
    }

    function look() {
      frame = 0;
      if (!pointer) return;
      var box = button.getBoundingClientRect();
      if (!box.width) return; // cards hidden: nothing to turn
      var r = aim(pointer.x - (box.left + box.width / 2), pointer.y - (box.top + box.height / 2), sector);
      sector = r.sector;
      if (r.direction !== direction) {
        direction = r.direction;
        dir.style.backgroundPosition = cellPosition(DIRECTIONS.indexOf(direction));
      }
    }

    function schedule() { if (!frame) frame = root.requestAnimationFrame(look); }

    // head tracking only with a real mouse; touch screens just get the reactions
    if (matches('(hover: hover) and (pointer: fine)')) {
      root.addEventListener('pointermove', function (e) { pointer = { x: e.clientX, y: e.clientY }; schedule(); }, { passive: true });
      root.addEventListener('scroll', schedule, { passive: true });
    }

    button.addEventListener('click', function () {
      timers.forEach(root.clearTimeout);
      timers = [];
      var r = boop(boops, Date.now());
      boops = r.boops;
      r.steps.forEach(function (s) {
        if (s.at === 0) show(s.reaction);
        else timers.push(root.setTimeout(function () { show(s.reaction); }, s.at));
      });
      if (matches('(prefers-reduced-motion: reduce)') || typeof squash.animate !== 'function') return;
      // per-keyframe easing with a linear effect, so the bounce is not front-loaded
      squash.animate(SQUASH, { duration: SQUASH_MS, easing: 'linear' });
    });
  }

  return {
    DIRECTIONS: DIRECTIONS,
    REACTIONS: REACTIONS,
    cellPosition: cellPosition,
    aim: aim,
    boop: boop,
    html: html,
    attach: attach
  };
});
