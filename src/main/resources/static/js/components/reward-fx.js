/*
 * RewardFX: reward animations shared by every page.
 *
 * The server decides what was earned; this file only plays it. Rewards come from two places:
 *  - responses of learner actions (claim a mission, use a freeze) -> RewardFX.claim / RewardFX.freezeUsed
 *  - rewards granted in the background (daily goal, streak, milestones) -> GET /api/mission/celebrations,
 *    played once and acknowledged so they never replay.
 * Animations run one after another from a queue and sounds can be muted.
 * Motion has two levels: 'full' (default) and 'gentle' (fades, lid opening and counting numbers, but
 * no shaking, flying coins, confetti or snow). The learner switches with the feather/sparkle button on
 * each card; the choice is remembered. Rewards are the point of these screens, so full motion is the
 * default even when the OS reports reduced motion (Windows "Animation effects" is often off by default).
 */
(function () {
  'use strict';

  if (window.RewardFX) return;

  var CONFETTI_SRC = 'https://cdn.jsdelivr.net/npm/canvas-confetti@1.9.3/dist/confetti.browser.min.js';
  var MUTE_KEY = 'jlas.rewardfx.muted';
  var MOTION_KEY = 'jlas.rewardfx.motion';
  var SOUNDS = {
    coin: '/audio/reward/coin.mp3',
    claim: '/audio/reward/claim.mp3',
    freeze: '/audio/reward/freeze.mp3',
    levelUp: '/audio/reward/level-up.mp3',
    fanfare: '/audio/reward/fanfare.mp3',
    missionReady: '/audio/reward/mission-ready.mp3'
  };
  var COLORS = ['#ff684d', '#ffc857', '#5ec9a6', '#7c83fd', '#f28bb3'];

  var queue = Promise.resolve();
  var confettiPromise = null;
  var audioCache = {};
  /*
   * Header chip. {@code credits}/{@code streak} are the real values from the server; {@code held} is CP
   * that is already in the wallet but whose celebration has not handed it over yet, and
   * {@code streakHeld} is the streak to show until the streak card lights the new day. The chip shows
   * credits - held, and every coin that lands releases its share, so the number climbs with the coins.
   */
  var chipState = { credits: null, streak: null, shown: null, held: 0, streakHeld: null };
  var pendingCheck = null;

  // ---------- small helpers ----------
  function osReducesMotion() {
    // Coi như mọi máy đều bật hoạt ảnh (quyết định sản phẩm); chỉ tắt khi người dùng tự chọn trong app.
    return false;
  }

  function motionMode() {
    var stored = null;
    try { stored = localStorage.getItem(MOTION_KEY); } catch (e) { /* storage unavailable */ }
    if (stored === 'full' || stored === 'gentle') return stored;
    return 'full';
  }

  function setMotionMode(mode) {
    try { localStorage.setItem(MOTION_KEY, mode); } catch (e) { /* storage unavailable */ }
    applyMotionClass();
  }

  /** Full motion is opted into with a class, so the gentle CSS only applies when it is not chosen. */
  function applyMotionClass() {
    document.documentElement.classList.toggle('rfx-full-motion', motionMode() === 'full');
  }

  /** True in gentle mode: skip big movement (shake, flying coins, confetti, snow), keep the rest. */
  function reducedMotion() {
    return motionMode() === 'gentle';
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function icon(name) {
    var node = el('i', 'fa-solid ' + name);
    node.setAttribute('aria-hidden', 'true');
    return node;
  }

  function wait(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function formatNumber(value) {
    return Number(value || 0).toLocaleString('vi-VN');
  }

  function enqueue(task) {
    queue = queue.then(task).catch(function (error) {
      if (window.console) console.warn('RewardFX:', error);
    });
    return queue;
  }

  function authHeaders() {
    return window.JlasAuth && typeof window.JlasAuth.authHeaders === 'function' ? window.JlasAuth.authHeaders() : {};
  }

  function loggedIn() {
    return window.JlasAuth && typeof window.JlasAuth.isLoggedIn === 'function' ? window.JlasAuth.isLoggedIn() : false;
  }

  async function api(path, options) {
    var config = options || {};
    config.headers = Object.assign({}, authHeaders(), config.headers || {});
    if (config.body && !config.headers['Content-Type']) config.headers['Content-Type'] = 'application/json';
    var response = await fetch(path, config);
    var payload = await response.json().catch(function () { return {}; });
    if (!response.ok || payload.success === false) throw new Error(payload.message || 'Request failed');
    return payload.data;
  }

  // ---------- sound ----------
  function muted() {
    try { return localStorage.getItem(MUTE_KEY) === '1'; } catch (e) { return false; }
  }

  function setMuted(value) {
    try { localStorage.setItem(MUTE_KEY, value ? '1' : '0'); } catch (e) { /* storage unavailable */ }
  }

  function play(name, volume) {
    if (muted() || !SOUNDS[name]) return;
    try {
      var audio = audioCache[name];
      if (!audio) {
        audio = new Audio(SOUNDS[name]);
        audio.preload = 'auto';
        audioCache[name] = audio;
      }
      var instance = audio.paused ? audio : audio.cloneNode();
      instance.volume = volume === undefined ? 0.7 : volume;
      instance.currentTime = 0;
      var playing = instance.play();
      if (playing && playing.catch) playing.catch(function () { /* autoplay blocked until a user gesture */ });
    } catch (e) { /* audio unsupported */ }
  }

  // ---------- confetti ----------
  function loadConfetti() {
    if (window.confetti) return Promise.resolve(window.confetti);
    if (confettiPromise) return confettiPromise;
    confettiPromise = new Promise(function (resolve) {
      var script = document.createElement('script');
      script.src = CONFETTI_SRC;
      script.async = true;
      script.onload = function () { resolve(window.confetti || null); };
      script.onerror = function () { resolve(null); };
      document.head.appendChild(script);
    });
    return confettiPromise;
  }

  function sparks(container, count) {
    for (var i = 0; i < count; i++) {
      var spark = el('span', 'rfx-spark');
      var angle = (Math.PI * 2 * i) / count;
      var distance = 90 + (i * 37) % 80;
      spark.style.setProperty('--x', Math.round(Math.cos(angle) * distance) + 'px');
      spark.style.setProperty('--y', Math.round(Math.sin(angle) * distance) + 'px');
      spark.style.setProperty('--d', ((i % 6) * 0.04) + 's');
      spark.style.setProperty('--c', COLORS[i % COLORS.length]);
      container.appendChild(spark);
    }
  }

  function burst(container, strength) {
    if (reducedMotion()) return;
    loadConfetti().then(function (confetti) {
      if (!confetti) { sparks(container, 18); return; }
      var big = strength === 'big';
      confetti({ particleCount: big ? 140 : 70, spread: big ? 100 : 70, startVelocity: big ? 48 : 36,
        origin: { y: 0.45 }, colors: COLORS, zIndex: 1700, disableForReducedMotion: false });
      if (big) {
        setTimeout(function () {
          confetti({ particleCount: 60, angle: 60, spread: 60, origin: { x: 0, y: 0.6 }, colors: COLORS, zIndex: 1700 });
          confetti({ particleCount: 60, angle: 120, spread: 60, origin: { x: 1, y: 0.6 }, colors: COLORS, zIndex: 1700 });
        }, 350);
      }
    });
  }

  // ---------- header chip ----------
  function chipPart(kind) {
    return document.querySelector('[data-reward-chip="' + kind + '"]');
  }

  function bump(kind) {
    var part = chipPart(kind);
    if (!part) return;
    part.classList.remove('is-bumping');
    void part.offsetWidth;
    part.classList.add('is-bumping');
  }

  function countTo(node, from, to, duration, prefix) {
    if (!node) return;
    var head = prefix || '';
    if (from === null || from === to) { node.textContent = head + formatNumber(to); return; }
    // a newer count on the same node cancels the running one
    var token = (node.rfxCount || 0) + 1;
    node.rfxCount = token;
    var start = performance.now();
    function frame(now) {
      if (node.rfxCount !== token) return;
      var progress = Math.min(1, (now - start) / duration);
      var eased = 1 - Math.pow(1 - progress, 3);
      node.textContent = head + formatNumber(Math.round(from + (to - from) * eased));
      if (progress < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  /** Paints credits - held on the chip, counting from what is shown now when {@code duration} is set. */
  function renderCredits(duration) {
    if (chipState.credits === null) return false;
    var node = document.querySelector('[data-reward-chip-value="credits"]');
    var target = Math.max(0, chipState.credits - chipState.held);
    var from = chipState.shown;
    chipState.shown = target;
    if (!node) return false;
    if (!duration || from === null || from === target) {
      node.rfxCount = (node.rfxCount || 0) + 1;
      node.textContent = formatNumber(target);
      return false;
    }
    countTo(node, from, target, duration);
    return true;
  }

  function holdCredits(amount) {
    if (amount > 0) chipState.held += amount;
  }

  /** Hands {@code amount} of the held CP to the chip, which counts up to include it. */
  function releaseCredits(amount, duration) {
    var share = Math.min(chipState.held, Math.max(0, amount || 0));
    if (share <= 0) return;
    chipState.held -= share;
    if (renderCredits(duration || 320)) bump('credits');
  }

  function renderStreak() {
    var streakNode = document.querySelector('[data-reward-chip-value="streak"]');
    var value = chipState.streakHeld !== null ? chipState.streakHeld : chipState.streak;
    if (streakNode) streakNode.textContent = value;
    var streakPart = chipPart('streak');
    if (streakPart) streakPart.classList.toggle('is-cold', value === 0);
  }

  function setChip(values, animate) {
    if (!values) return;
    if (values.credits !== undefined && values.credits !== null) {
      chipState.credits = Number(values.credits);
      if (renderCredits(animate ? 900 : 0)) bump('credits');
    }
    if (values.streak !== undefined && values.streak !== null) {
      var before = chipState.streakHeld !== null ? chipState.streakHeld : chipState.streak;
      chipState.streak = Number(values.streak);
      renderStreak();
      var after = chipState.streakHeld !== null ? chipState.streakHeld : chipState.streak;
      if (animate && before !== null && before !== after) bump('streak');
    }
    var chip = document.querySelector('[data-reward-chip-root]');
    if (chip) chip.hidden = false;
  }

  async function refreshChip(animate) {
    if (!loggedIn()) return null;
    try {
      var summary = await api('/api/mission/summary');
      setChip({ credits: summary.creditBalance, streak: summary.currentStreak }, animate);
      return summary;
    } catch (e) {
      return null;
    }
  }

  // ---------- coins ----------
  function centerOf(node) {
    if (!node || !node.getBoundingClientRect) return null;
    var rect = node.getBoundingClientRect();
    if (!rect.width && !rect.height) return null;
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  }

  /**
   * Coins fly from {@code source} to the header chip; each landing coin hands over its share of
   * {@code amount} (see releaseCredits). Resolves when the last one lands.
   */
  function flyCoins(source, amount) {
    var target = centerOf(chipPart('credits'));
    var start = centerOf(source) || { x: window.innerWidth / 2, y: window.innerHeight / 2 };
    if (!target || reducedMotion() || !Element.prototype.animate) {
      releaseCredits(amount, 900);
      return Promise.resolve();
    }
    var count = Math.max(6, Math.min(16, Math.round((amount || 10) / 3)));
    var total = Math.max(0, amount || 0);
    var shares = [];
    for (var s = 0; s < count; s++) {
      shares.push(Math.round(total * (s + 1) / count) - Math.round(total * s / count));
    }
    var landings = [];
    for (var i = 0; i < count; i++) {
      landings.push(new Promise(function (resolve) {
        var share = shares[i];
        var coin = el('span', 'rfx-coin');
        coin.style.left = start.x + 'px';
        coin.style.top = start.y + 'px';
        document.body.appendChild(coin);
        var spreadX = (Math.random() - 0.5) * 140;
        var spreadY = -40 - Math.random() * 90;
        var dx = target.x - start.x;
        var dy = target.y - start.y;
        var animation = coin.animate([
          { transform: 'translate(0, 0) scale(.4)', opacity: 0 },
          { transform: 'translate(' + spreadX + 'px,' + spreadY + 'px) scale(1.1)', opacity: 1, offset: 0.35 },
          { transform: 'translate(' + dx + 'px,' + dy + 'px) scale(.55)', opacity: 0.9 }
        ], { duration: 900 + i * 40, delay: i * 70, easing: 'cubic-bezier(.45,.05,.35,1)', fill: 'forwards' });
        animation.onfinish = function () {
          coin.remove();
          releaseCredits(share, 260);
          bump('credits');
          play('coin', 0.35);
          resolve();
        };
      }));
    }
    return Promise.all(landings);
  }

  // ---------- toast ----------
  function toastStack() {
    var stack = document.getElementById('rfxToastStack');
    if (!stack) {
      stack = el('div', 'rfx-toast-stack');
      stack.id = 'rfxToastStack';
      stack.setAttribute('role', 'status');
      stack.setAttribute('aria-live', 'polite');
      document.body.appendChild(stack);
    }
    return stack;
  }

  function toast(title, detail, options) {
    var settings = options || {};
    var node = el('div', 'rfx-toast');
    var iconBox = el('span', 'rfx-toast__icon');
    iconBox.appendChild(icon(settings.icon || 'fa-star'));
    var body = el('div', 'rfx-toast__body');
    body.appendChild(el('strong', null, title));
    if (detail) body.appendChild(el('span', null, detail));
    node.append(iconBox, body);
    if (settings.href) {
      var link = el('a', null, settings.linkText || 'Xem');
      link.href = settings.href;
      node.appendChild(link);
    }
    toastStack().appendChild(node);
    setTimeout(function () {
      node.classList.add('is-leaving');
      setTimeout(function () { node.remove(); }, 320);
    }, settings.duration || 4200);
  }

  // ---------- overlay ----------
  function trapFocus(overlay) {
    overlay.addEventListener('keydown', function (event) {
      if (event.key !== 'Tab') return;
      var focusable = overlay.querySelectorAll('button, [href]');
      if (!focusable.length) return;
      var first = focusable[0];
      var last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    });
  }

  function motionButton() {
    var button = el('button', 'rfx-motion');
    button.type = 'button';
    function render() {
      var full = motionMode() === 'full';
      button.replaceChildren(icon(full ? 'fa-wand-magic-sparkles' : 'fa-feather'));
      button.classList.toggle('is-full', full);
      button.setAttribute('aria-label', full ? 'Đang bật hiệu ứng đầy đủ. Bấm để giảm chuyển động'
        : 'Đang giảm chuyển động. Bấm để bật hiệu ứng đầy đủ');
      button.title = full ? 'Hiệu ứng: đầy đủ' : 'Hiệu ứng: nhẹ (bấm để bật đầy đủ)';
    }
    button.addEventListener('click', function () {
      setMotionMode(motionMode() === 'full' ? 'gentle' : 'full');
      render();
    });
    render();
    return button;
  }

  function muteButton() {
    var button = el('button', 'rfx-mute');
    button.type = 'button';
    function render() {
      button.replaceChildren(icon(muted() ? 'fa-volume-xmark' : 'fa-volume-high'));
      button.setAttribute('aria-label', muted() ? 'Bật âm thanh phần thưởng' : 'Tắt âm thanh phần thưởng');
    }
    button.addEventListener('click', function () { setMuted(!muted()); render(); });
    render();
    return button;
  }

  /**
   * Shows a card over the page. Resolves with the value of the pressed action ('close' by default).
   * {@code autoClose} (ms) turns it into a short flash that also closes on click.
   */
  function overlay(options) {
    return new Promise(function (resolve) {
      var previousFocus = document.activeElement;
      var root = el('div', 'rfx-overlay' + (options.variant ? ' rfx-overlay--' + options.variant : ''));
      root.setAttribute('role', 'dialog');
      root.setAttribute('aria-modal', 'true');
      var card = el('section', 'rfx-card' + (options.className ? ' ' + options.className : ''));
      var reveal = reducedMotion() ? Math.min(options.revealDelay || 0, 900) : (options.revealDelay || 0);
      card.style.setProperty('--rfx-reveal', reveal + 'ms');
      var titleId = 'rfxTitle' + Date.now();
      root.setAttribute('aria-labelledby', titleId);
      if (options.layer) options.layer(root);
      if (!options.autoClose) card.append(motionButton(), muteButton());
      if (options.hero) card.appendChild(options.hero);
      if (options.sparkles && !reducedMotion()) addSparkles(card, reveal);
      var step = 0;
      function staged(node) { node.style.setProperty('--k', step++); card.appendChild(node); return node; }
      if (options.eyebrow) staged(el('p', 'rfx-card__eyebrow', options.eyebrow));
      if (options.points) {
        // The number counts up from zero once the hero animation has revealed it.
        var points = el('p', 'rfx-card__points');
        var number = el('span', 'rfx-card__num', '+0');
        points.append(number, document.createTextNode(' '), el('small', null, 'CP'));
        card.appendChild(points);
        setTimeout(function () { countTo(number, 0, options.points, options.pointsDuration || 900, '+'); }, reveal + 120);
      }
      var title = staged(el('h2', 'rfx-card__title', options.title));
      title.id = titleId;
      if (options.message) staged(el('p', 'rfx-card__message', options.message));
      if (options.extra) staged(options.extra);

      var closed = false;
      function close(value) {
        if (closed) return;
        closed = true;
        if (options.onClose) options.onClose(root);
        root.classList.add('is-leaving');
        setTimeout(function () {
          root.remove();
          if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
          resolve(value || 'close');
        }, 240);
      }

      var actions = options.actions || (options.autoClose ? [] : [{ label: 'Tiếp tục học', value: 'close' }]);
      if (actions.length) {
        var actionBox = el('div', 'rfx-card__actions');
        actionBox.style.setProperty('--k', step++);
        actions.forEach(function (action) {
          var button = el('button', 'rfx-btn' + (action.variant ? ' rfx-btn--' + action.variant : ''), action.label);
          button.type = 'button';
          button.disabled = !!action.disabled;
          button.addEventListener('click', function () { close(action.value); });
          actionBox.appendChild(button);
        });
        card.appendChild(actionBox);
      }

      root.appendChild(card);
      root.addEventListener('click', function (event) {
        if (options.autoClose || event.target === root) close(options.autoClose ? 'close' : 'dismiss');
      });
      root.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') close('dismiss');
      });
      trapFocus(root);
      document.body.appendChild(root);
      var firstButton = root.querySelector('.rfx-btn:not([disabled])');
      (firstButton || card).focus && (firstButton || card).focus();
      if (options.onShow) options.onShow(root, card);
      if (options.autoClose) setTimeout(function () { close('close'); }, options.autoClose);
    });
  }

  function addSparkles(card, reveal) {
    var spots = [[8, 18], [86, 12], [14, 48], [90, 42], [4, 78], [94, 74], [50, 4]];
    spots.forEach(function (spot, index) {
      var star = el('span', 'rfx-sparkle');
      star.style.left = spot[0] + '%';
      star.style.top = spot[1] + '%';
      star.style.setProperty('--s', (10 + (index * 5) % 12) + 'px');
      star.style.setProperty('--c', COLORS[index % COLORS.length]);
      star.style.setProperty('--d', (reveal / 1000 + index * 0.18) + 's');
      card.appendChild(star);
    });
  }

  function snowLayer(root) {
    if (reducedMotion()) return;
    for (var i = 0; i < 26; i++) {
      var flake = el('span', 'rfx-snow');
      flake.style.setProperty('--x', ((i * 37) % 100) + 'vw');
      flake.style.setProperty('--s', (4 + (i * 7) % 7) + 'px');
      flake.style.setProperty('--t', (2.6 + (i % 5) * 0.35) + 's');
      flake.style.setProperty('--d', ((i % 9) * 0.22) + 's');
      flake.style.setProperty('--drift', (((i % 7) - 3) * 12) + 'px');
      root.appendChild(flake);
    }
  }

  // ---------- hero builders ----------
  function isoDate(date) {
    return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-'
      + String(date.getDate()).padStart(2, '0');
  }

  /**
   * The last seven days with real weekday labels; the last cell is always today, so the streak's
   * run-up is visible even on a Monday.
   */
  function recentDays(week) {
    if (!week || !week.length) return null;
    var labels = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
    var days = week.slice(-7).map(function (day, index, list) {
      var date = new Date(day.date + 'T00:00:00');
      return { iso: day.date, label: labels[date.getDay()], today: index === list.length - 1,
        state: day.state === 'TODAY' ? 'MISSED' : day.state };
    });
    var yesterday = days.length > 1 ? days[days.length - 2] : null;
    return { days: days, yesterdayState: yesterday ? yesterday.state : 'MISSED' };
  }

  var STREAK_TIERS = [
    { days: 30, name: 'Lửa huyền thoại' },
    { days: 15, name: 'Lửa bão' },
    { days: 7, name: 'Lửa rực' },
    { days: 3, name: 'Lửa bùng' },
    { days: 0, name: 'Đốm lửa' }
  ];

  /** 0 (1-2 days) ... 4 (30+ days): each milestone makes the flame bigger and hotter. */
  function streakTier(streak) {
    for (var i = 0; i < STREAK_TIERS.length; i++) {
      if (streak >= STREAK_TIERS[i].days) return { level: STREAK_TIERS.length - 1 - i, name: STREAK_TIERS[i].name };
    }
    return { level: 0, name: STREAK_TIERS[STREAK_TIERS.length - 1].name };
  }

  /** Flame artwork height per tier (px); the number sits in the flame's core. */
  var FLAME_HEIGHTS = [118, 140, 165, 190, 215];

  /**
   * The flame artwork (images/streak/flame-N.webp) brought to life on a canvas. The image is drawn in
   * thin horizontal slices; each slice is shifted by waves that travel from the base to the tip, so the
   * base stays put while the tongues lick and sway more the higher they are. The flame also breathes in
   * height, glows behind, and throws embers (and sparks at 7+ days). It starts as a small grey flame and
   * ignite() bursts it to full size. The loop stops as soon as the canvas leaves the page.
   */
  function fireEngine(canvas, level) {
    var SPARKS = [0, 0, 3, 6, 11];
    var EMBERS = [4, 6, 9, 12, 16];
    var WIDTH = 260, HEIGHT = 260;
    var dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = WIDTH * dpr;
    canvas.height = HEIGHT * dpr;
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    var image = new Image();
    image.src = '/images/streak/flame-' + Math.max(1, level) + '.webp';
    var glow = null;
    var grey = null;
    image.onload = function () {
      // pre-render a blurred copy for the glow and a grey copy for the unlit state
      // padded so the blur fades out instead of being clipped into a visible box
      glow = document.createElement('canvas');
      glow.pad = 48;
      glow.width = image.width + glow.pad * 2; glow.height = image.height + glow.pad * 2;
      var g = glow.getContext('2d');
      g.filter = 'blur(16px) saturate(1.4)';
      g.drawImage(image, glow.pad, glow.pad);
      grey = document.createElement('canvas');
      grey.width = image.width; grey.height = image.height;
      var gr = grey.getContext('2d');
      gr.filter = 'grayscale(1) brightness(1.15)';
      gr.drawImage(image, 0, 0);
    };

    var emberSprite = (function () {
      var c = document.createElement('canvas');
      c.width = c.height = 32;
      var x = c.getContext('2d');
      var grd = x.createRadialGradient(16, 16, 0, 16, 16, 16);
      grd.addColorStop(0, 'rgba(255,230,140,1)');
      grd.addColorStop(0.35, 'rgba(255,150,50,.7)');
      grd.addColorStop(1, 'rgba(255,90,20,0)');
      x.fillStyle = grd;
      x.fillRect(0, 0, 32, 32);
      return c;
    })();

    var cx = WIDTH / 2;
    var baseY = HEIGHT - 12;
    var targetHeight = FLAME_HEIGHTS[level];
    var size = 0.4, sizeTarget = 0.4, cold = true;
    var embers = [], sparks = [];
    var emberCarry = 0, sparkCarry = 0;
    var last = performance.now(), clock = 0;

    function gauss() { return (Math.random() + Math.random() + Math.random() - 1.5) / 1.5; }

    function frame(now) {
      if (!canvas.isConnected) return;
      var dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      clock += dt;
      // spring toward the target size (overshoots a little when it bursts into flame)
      size += (sizeTarget - size) * Math.min(1, dt * 4.5);

      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 1;
      ctx.clearRect(0, 0, WIDTH, HEIGHT);

      if (image.complete && image.naturalWidth) {
        var breathe = 1 + 0.035 * Math.sin(clock * 5.3) + 0.02 * Math.sin(clock * 11.7);
        var h = targetHeight * size * (cold ? 1 : breathe);
        var w = h * image.width / image.height;
        var left = cx - w / 2;
        var top = baseY - h;
        var sourceH = image.height;
        var step = 2;
        var rows = Math.ceil(h / step);
        ctx.imageSmoothingQuality = 'high';

        // glow that breathes behind the flame
        if (!cold && glow) {
          ctx.globalCompositeOperation = 'lighter';
          ctx.globalAlpha = 0.28 + 0.12 * Math.sin(clock * 4.1);
          var k = h / image.height;
          var gw = glow.width * k * 1.08, gh = glow.height * k * 1.04;
          ctx.drawImage(glow, cx - gw / 2, baseY - gh + glow.pad * k, gw, gh);
          ctx.globalCompositeOperation = 'source-over';
        }

        var source = cold && grey ? grey : image;
        ctx.globalAlpha = cold ? 0.55 : 0.94 + 0.06 * Math.sin(clock * 17);
        var sway = cold ? 0.35 : 1;
        for (var r = 0; r < rows; r++) {
          var y = top + r * step;
          var rel = 1 - (r * step) / h;                       // 1 at the tip, 0 at the base
          var amp = (1.5 + 12 * Math.pow(rel, 1.7)) * sway;
          // smooth waves travelling up the flame; neighbouring slices move almost together
          var dx = amp * (Math.sin(clock * 7.2 - rel * 7) * 0.8 + Math.sin(clock * 11.3 - rel * 10 + 1.3) * 0.2);
          var squeeze = 1 + 0.05 * Math.sin(clock * 6.1 - rel * 5) * rel * sway;
          var rowW = w * squeeze;
          var sy = (r * step / h) * sourceH;
          // slices meet exactly: overlapping translucent slices would show as horizontal stripes
          ctx.drawImage(source, 0, sy, image.width, (step / h) * sourceH, cx - rowW / 2 + dx, y, rowW, step);
        }
      }

      if (!cold) {
        emberCarry += EMBERS[level] * dt;
        while (emberCarry >= 1) {
          embers.push({ x: cx + gauss() * targetHeight * 0.22, y: baseY - targetHeight * (0.35 + 0.35 * Math.random()),
            vx: gauss() * 20, vy: -(45 + Math.random() * 70), life: 0, max: 1 + Math.random() * 1,
            size: 2 + Math.random() * 2.5, seed: Math.random() * 1000 });
          emberCarry -= 1;
        }
        sparkCarry += SPARKS[level] * dt;
        while (sparkCarry >= 1) {
          var dir = Math.random() < 0.5 ? -1 : 1;
          var angle = -Math.PI / 2 + dir * (0.3 + Math.random() * 0.6);
          var velocity = 170 + Math.random() * 140;
          sparks.push({ x: cx + gauss() * targetHeight * 0.12, y: baseY - targetHeight * 0.25,
            vx: Math.cos(angle) * velocity, vy: Math.sin(angle) * velocity, life: 0, max: 0.45 + Math.random() * 0.35 });
          sparkCarry -= 1;
        }
      }
      ctx.globalCompositeOperation = 'source-over';
      sparks = sparks.filter(function (s) {
        s.life += dt;
        var t = s.life / s.max;
        if (t >= 1) return false;
        s.vy += 140 * dt;
        s.x += s.vx * dt;
        s.y += s.vy * dt;
        ctx.globalAlpha = 1 - t;
        ctx.strokeStyle = 'rgb(255,' + Math.round(170 - 80 * t) + ',40)';
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';
        ctx.beginPath();
        ctx.moveTo(s.x, s.y);
        ctx.lineTo(s.x - s.vx * 0.05, s.y - s.vy * 0.05);
        ctx.stroke();
        return true;
      });
      embers = embers.filter(function (e) {
        e.life += dt;
        var t = e.life / e.max;
        if (t >= 1) return false;
        e.vx += Math.sin(clock * 5 + e.seed) * 50 * dt;
        e.x += e.vx * dt;
        e.y += e.vy * dt;
        ctx.globalAlpha = (1 - t) * (0.6 + 0.4 * Math.sin(clock * 22 + e.seed));
        var s = e.size * (1 - t * 0.4);
        ctx.drawImage(emberSprite, e.x - s * 1.6, e.y - s * 1.6, s * 3.2, s * 3.2);
        return true;
      });
      ctx.globalAlpha = 1;
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);

    return {
      ignite: function () {
        cold = false;
        size = 0.55;
        sizeTarget = 1;
        // burst: overshoot, then settle
        var start = performance.now();
        (function overshoot() {
          var t = (performance.now() - start) / 1000;
          if (t < 0.35) { sizeTarget = 1.14; requestAnimationFrame(overshoot); } else { sizeTarget = 1; }
        })();
        for (var e = 0; e < EMBERS[level] * 2; e++) {
          embers.push({ x: cx + gauss() * 30, y: baseY - targetHeight * 0.5, vx: gauss() * 60, vy: -(80 + Math.random() * 90),
            life: 0, max: 0.8 + Math.random() * 0.8, size: 2 + Math.random() * 3, seed: Math.random() * 1000 });
        }
      }
    };
  }

  /** Animated flame artwork (see fireEngine) with the streak number in its core. */
  function blazeHero(streak, tier) {
    var blaze = el('div', 'rfx-blaze tier-' + tier.level);
    var canvas = document.createElement('canvas');
    canvas.className = 'rfx-blaze__fire';
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', tier.name);
    blaze.appendChild(canvas);
    var count = el('span', 'rfx-blaze__count');
    var roll = el('span', 'rfx-blaze__roll');
    roll.append(el('span', null, Math.max(0, streak - 1)), el('span', null, streak));
    count.appendChild(roll);
    // the core of the artwork sits at about 36% of the flame height above its base
    count.style.bottom = Math.round(12 + FLAME_HEIGHTS[tier.level] * 0.36 - 20) + 'px';
    blaze.appendChild(count);
    blaze.fire = fireEngine(canvas, tier.level);
    return blaze;
  }

  function igniteSparks(blaze, tier) {
    if (reducedMotion()) return;
    var total = 10 + tier.level * 6;
    for (var i = 0; i < total; i++) {
      var spark = el('span', 'rfx-spark');
      var angle = (Math.PI * 2 * i) / total;
      var distance = 70 + tier.level * 12 + (i * 13) % 30;
      spark.style.setProperty('--x', Math.round(Math.cos(angle) * distance) + 'px');
      spark.style.setProperty('--y', Math.round(Math.sin(angle) * distance) + 'px');
      spark.style.setProperty('--d', ((i % 5) * 0.03) + 's');
      spark.style.setProperty('--c', ['#ffc857', '#ff8a4c', '#ff684d', '#fff1a8'][i % 4]);
      blaze.appendChild(spark);
    }
  }

  /**
   * Streak scene: a cold flame above the last seven days. Finished days light up one by one, a spark
   * hops from yesterday to today, today ignites, then the flame bursts into its tier's blaze and the
   * counter rolls to the new streak. {@code igniteAt} tells the card when to reveal its text.
   */
  function streakScene(streak, week) {
    var tier = streakTier(streak);
    var root = el('div', 'rfx-streak');
    var flame = blazeHero(streak, tier);
    root.appendChild(flame);

    var info = recentDays(week);
    var todayCell = null;
    var yesterdayCell = null;
    var chain = [];
    var bar = null;
    if (info) {
      bar = el('div', 'rfx-weekbar');
      info.days.forEach(function (day, index) {
        var cell = el('span', 'rfx-weekbar__day', day.label);
        cell.style.setProperty('--i', index);
        cell.title = day.iso;
        if (day.today) {
          cell.classList.add('is-today');
          todayCell = cell;
          if (index > 0) yesterdayCell = bar.lastChild;
        } else if (day.state === 'COMPLETED' || day.state === 'FROZEN') {
          cell.classList.add('is-pending');
          chain.push({ cell: cell, frozen: day.state === 'FROZEN' });
        } else if (day.state === 'FUTURE') {
          cell.classList.add('is-future');
        }
        bar.appendChild(cell);
      });
      if (!(info.yesterdayState === 'COMPLETED' || info.yesterdayState === 'FROZEN')) yesterdayCell = null;
      root.appendChild(bar);
    }

    var gentle = reducedMotion();
    var chainStart = gentle ? 300 : 650;
    var chainStep = gentle ? 60 : 140;
    var hopStart = chainStart + chain.length * chainStep + (gentle ? 100 : 200);
    var hopTime = yesterdayCell && !gentle ? 700 : 0;
    var igniteAt = hopStart + hopTime + (gentle ? 150 : 380);

    function lightChain() {
      chain.forEach(function (entry, index) {
        setTimeout(function () {
          entry.cell.classList.remove('is-pending');
          entry.cell.classList.add(entry.frozen ? 'is-frozen' : 'is-done', 'is-chain');
          if (!entry.frozen) play('coin', 0.18);
        }, chainStart + index * chainStep);
      });
    }

    function lightToday() {
      if (!todayCell) return;
      todayCell.classList.remove('is-today');
      todayCell.classList.add('is-lit-now');
    }

    function ignite() {
      flame.classList.add('is-ignited');
      if (flame.fire) flame.fire.ignite();
      igniteSparks(flame, tier);
      play('levelUp', 0.55);
    }

    function hop(done) {
      if (!bar || !yesterdayCell || !todayCell || gentle || !Element.prototype.animate) { done(); return; }
      var spark = el('span', 'rfx-hop');
      bar.appendChild(spark);
      var base = bar.getBoundingClientRect();
      var from = yesterdayCell.getBoundingClientRect();
      var to = todayCell.getBoundingClientRect();
      var x1 = from.left - base.left + from.width / 2, y1 = from.top - base.top + from.height / 2;
      var x2 = to.left - base.left + to.width / 2, y2 = to.top - base.top + to.height / 2;
      var animation = spark.animate([
        { transform: 'translate(' + x1 + 'px,' + y1 + 'px) scale(.6)', opacity: 0 },
        { transform: 'translate(' + x1 + 'px,' + y1 + 'px) scale(1)', opacity: 1, offset: 0.15 },
        { transform: 'translate(' + ((x1 + x2) / 2) + 'px,' + (y1 - 36) + 'px) scale(1.3)', opacity: 1, offset: 0.55 },
        { transform: 'translate(' + x2 + 'px,' + y2 + 'px) scale(.8)', opacity: 1 }
      ], { duration: hopTime || 1, easing: 'cubic-bezier(.4, 0, .3, 1)', fill: 'forwards' });
      animation.onfinish = function () { spark.remove(); done(); };
    }

    return {
      element: root,
      flame: flame,
      tier: tier,
      play: function () {
        lightChain();
        setTimeout(function () {
          hop(function () {
            lightToday();
            setTimeout(ignite, gentle ? 150 : 380);
          });
        }, hopStart);
      },
      igniteAt: igniteAt
    };
  }
  function weekStrip(week) {
    if (!week || !week.length) return null;
    var labels = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
    var strip = el('div', 'rfx-week');
    week.forEach(function (day, index) {
      var date = new Date(day.date + 'T00:00:00');
      var node = el('span', day.state === 'COMPLETED' ? 'is-done' : day.state === 'FROZEN' ? 'is-frozen' : '',
        labels[date.getDay()]);
      node.style.setProperty('--i', index);
      if (index === week.length - 1 && day.state === 'COMPLETED') node.classList.add('is-new');
      node.title = day.date;
      strip.appendChild(node);
    });
    return strip;
  }

  function shieldHero() {
    var hero = el('div', 'rfx-hero');
    var shield = el('div', 'rfx-shield');
    shield.appendChild(icon('fa-snowflake'));
    hero.appendChild(shield);
    if (!reducedMotion()) {
      for (var i = 0; i < 14; i++) {
        var frost = el('span', 'rfx-frost');
        var angle = (Math.PI * 2 * i) / 14;
        frost.style.setProperty('--x', Math.round(Math.cos(angle) * 110) + 'px');
        frost.style.setProperty('--y', Math.round(Math.sin(angle) * 110) + 'px');
        frost.style.setProperty('--d', (0.5 + (i % 4) * 0.05) + 's');
        hero.appendChild(frost);
      }
    }
    return hero;
  }

  /** Present that shakes, pops its lid and throws coins; shared by every mission reward. */
  function giftHero() {
    var hero = el('div', 'rfx-gift');
    hero.append(el('span', 'rfx-gift__rays'), el('span', 'rfx-gift__glow'));
    for (var i = 0; i < 14; i++) {
      var coin = el('span', 'rfx-gift__coin');
      var wave = i < 8 ? 0 : 1;
      var slot = wave ? i - 8 : i;
      var angle = -Math.PI / 2 + (slot - (wave ? 2.5 : 3.5)) * (wave ? 0.45 : 0.32);
      var reach = wave ? 70 : 105;
      coin.style.setProperty('--x', Math.round(Math.cos(angle) * reach) + 'px');
      coin.style.setProperty('--y', Math.round(Math.sin(angle) * reach) + 'px');
      coin.style.setProperty('--i', slot + wave * 6);
      hero.appendChild(coin);
    }
    var lid = el('div', 'rfx-gift__lid');
    lid.appendChild(el('span', 'rfx-gift__bow'));
    hero.append(el('div', 'rfx-gift__box'), lid);
    return hero;
  }

  /** Context hero (why the points were earned) followed by the present that delivers them. */
  function giftScene(contextHero, startSeconds) {
    var scene = el('div', 'rfx-scene');
    scene.style.setProperty('--gift-start', startSeconds + 's');
    if (contextHero) {
      var context = el('div', 'rfx-scene__context');
      context.appendChild(contextHero);
      scene.appendChild(context);
    }
    scene.appendChild(giftHero());
    return scene;
  }

  /** Timings of the present when it starts at {@code start} seconds (see the gift CSS). */
  function giftTimes(start) {
    var ms = Math.round(start * 1000);
    if (reducedMotion()) return { open: ms + 450, reveal: ms + 700 };
    return { open: ms + 1720, reveal: ms + 1850 };
  }

  /**
   * One card that ends with the present opening: every place that gives points uses it.
   * {@code context} is an optional hero played before the present (rings for the daily goal); the
   * present then counts the points and "Nhận quà" flies the coins home.
   */
  function giftCelebration(options) {
    var start = options.context ? (reducedMotion() ? 1.4 : options.contextSeconds || 2.4) : 0;
    var times = giftTimes(start);
    var center = null;
    return overlay({
      className: 'rfx-card--gift',
      hero: options.context ? giftScene(options.context, start) : giftScene(null, 0),
      eyebrow: options.eyebrow,
      points: options.points,
      pointsDuration: options.pointsDuration,
      title: options.title,
      message: options.message,
      actions: [{ label: options.actionLabel || 'Nhận quà', value: 'close' }],
      revealDelay: times.reveal,
      sparkles: true,
      onShow: function (root) {
        if (options.onContext) options.onContext(root);
        setTimeout(function () { play('claim'); burst(root, options.big ? 'big' : 'normal'); }, times.open);
        if (options.big) setTimeout(function () { burst(root, 'normal'); }, times.open + 800);
      },
      onClose: function (root) { center = centerOf(root.querySelector('.rfx-gift')); }
    }).then(function () {
      var from = center ? { getBoundingClientRect: function () {
        return { left: center.x, top: center.y, width: 1, height: 1 };
      } } : null;
      // The header chip counts up while the coins are still flying towards it.
      if (options.balance !== undefined && options.balance !== null) {
        setTimeout(function () { setChip({ credits: options.balance }, true); }, 600);
      }
      return flyCoins(from, options.points);
    });
  }

  function claim(result) {
    if (!result || result.alreadyClaimed) return Promise.resolve();
    // the new balance already includes the points: hold them until the coins arrive
    var claimedPoints = result.rewardPoints || result.points || 0;
    var newBalance = result.creditBalance !== undefined && result.creditBalance !== null ? result.creditBalance : result.balance;
    if (newBalance !== undefined && newBalance !== null) {
      holdCredits(claimedPoints);
      setChip({ credits: newBalance }, false);
    }
    return enqueue(async function () {
      await giftCelebration({
        eyebrow: 'Quà nhiệm vụ',
        points: result.rewardPoints || result.points || 0,
        balance: result.creditBalance || result.balance,
        title: result.title || result.missionTitle || 'Nhiệm vụ hoàn thành!',
        message: 'Phần quà của bạn đã mở!'
      });
    });
  }

  /** A freeze the learner just used or bought. */
  function freezeUsed(result, source) {
    return enqueue(async function () {
      play('freeze');
      var streak = result && result.streak ? result.streak.currentStreak : null;
      if (result && result.creditsSpent) setChip({ credits: result.creditBalance }, true);
      if (streak !== null) setChip({ streak: streak }, true);
      await overlay({
        variant: 'ice',
        layer: snowLayer,
        revealDelay: 750,
        hero: shieldHero(),
        eyebrow: 'Thẻ bảo vệ',
        title: 'Chuỗi ' + (streak || 0) + ' ngày đã được bảo vệ',
        message: result && result.creditsSpent
          ? 'Đã dùng ' + result.creditsSpent + ' CP để mua thẻ bảo vệ.'
          : 'Đã dùng 1 thẻ bảo vệ bạn đang có.',
        extra: result && result.streak ? weekStrip(result.streak.week) : null,
        actions: [{ label: 'Học tiếp để giữ lửa', value: 'close', variant: 'ice' }]
      });
    });
  }

  /**
   * Streak card for a new streak day; a milestone adds its points, confetti and the coin flight.
   * The week comes from the goals page when it is open, otherwise from the summary endpoint.
   */
  async function streakCelebration(item, context, milestone) {
    var streak = item.streak || 1;
    var week = context && context.week;
    if (!week) {
      try { week = (await api('/api/mission/summary')).week; } catch (e) { week = null; }
    }
    var scene = streakScene(streak, week);
    chipState.streakHeld = null;
    setChip({ streak: streak }, true);
    var center = null;
    await overlay({
      className: 'rfx-card--streak',
      hero: scene.element,
      eyebrow: milestone ? 'Cột mốc chuỗi' : 'Chuỗi ngày',
      points: milestone ? item.amount : 0,
      pointsDuration: 1100,
      title: milestone ? (item.title || ('Chuỗi ' + streak + ' ngày')) : (streak === 1 ? 'Bắt đầu chuỗi mới!' : 'Chuỗi ' + streak + ' ngày!'),
      actions: [{ label: milestone ? 'Tuyệt vời!' : 'Tiếp tục', value: 'close' }],
      revealDelay: scene.igniteAt + 200,
      sparkles: milestone,
      onShow: function (root) {
        scene.play();
        if (milestone) {
          // Higher milestones get more confetti waves.
          setTimeout(function () { play('fanfare'); burst(root, 'big'); }, scene.igniteAt + 150);
          for (var wave = 1; wave <= scene.tier.level; wave++) {
            (function (delay) { setTimeout(function () { burst(root, 'normal'); }, delay); })(scene.igniteAt + 150 + wave * 700);
          }
        }
      },
      onClose: function () { center = centerOf(scene.flame); }
    });
    if (milestone && item.amount) {
      await flyCoins(center ? { getBoundingClientRect: function () {
        return { left: center.x, top: center.y, width: 1, height: 1 };
      } } : null, item.amount);
      await refreshChip(true);
    }
  }

  function playCelebration(item, context) {
    switch (item.kind) {
      case 'DAILY_GOAL':
        return (async function () {
          await giftCelebration({
            eyebrow: 'Mục tiêu hôm nay',
            points: item.amount,
            title: item.title || 'Hoàn thành mục tiêu hôm nay',
            message: 'Tuyệt vời! Kitsu-chan tự hào về bạn.'
          });
          await refreshChip(true);
        })();
      case 'STREAK_EXTENDED':
        return streakCelebration(item, context, false);
      case 'STREAK_MILESTONE':
        return streakCelebration(item, context, true);
      case 'CREDIT_EARNED':
        return (async function () {
          await giftCelebration({
            eyebrow: 'Bạn có quà',
            points: item.amount,
            title: item.title || 'Bạn nhận được điểm thưởng',
            message: 'Điểm đã được cộng vào ví của bạn.'
          });
          await refreshChip(true);
        })();
      default:
        // Finished missions are announced in the notification bell, not here.
        return Promise.resolve();
    }
  }

  /**
   * Plays a list of celebrations in order: daily goals, milestones, then the streak and missions.
   * Rewards that piled up are condensed: only the newest streak day is animated, and finished
   * missions become one toast, so returning after a busy day is not a string of pop-ups.
   */
  function celebrate(items, context) {
    if (!items || !items.length) return Promise.resolve();
    var order = { DAILY_GOAL: 0, STREAK_MILESTONE: 1, CREDIT_EARNED: 2, STREAK_EXTENDED: 3, MISSION_READY: 4 };
    var streaks = items.filter(function (item) { return item.kind === 'STREAK_EXTENDED'; });
    var latestStreak = streaks.reduce(function (best, item) {
      return !best || (item.streak || 0) > (best.streak || 0) ? item : best;
    }, null);
    var sum = function (list) { return list.reduce(function (total, item) { return total + (item.amount || 0); }, 0); };
    var rest = items.filter(function (item) {
      return ['STREAK_EXTENDED', 'MISSION_READY', 'DAILY_GOAL', 'STREAK_MILESTONE'].indexOf(item.kind) === -1;
    });
    // Several finished days become one present with the total.
    var goals = items.filter(function (item) { return item.kind === 'DAILY_GOAL'; });
    if (goals.length === 1) rest.push(goals[0]);
    if (goals.length > 1) {
      rest.push({ kind: 'DAILY_GOAL', amount: sum(goals), title: 'Hoàn thành mục tiêu ' + goals.length + ' ngày' });
    }
    // One streak card: the highest milestone (with every milestone's points), else the newest streak day.
    var milestones = items.filter(function (item) { return item.kind === 'STREAK_MILESTONE'; });
    if (milestones.length) {
      var top = milestones.reduce(function (best, item) { return (item.streak || 0) >= (best.streak || 0) ? item : best; });
      rest.push({ kind: 'STREAK_MILESTONE', title: top.title, amount: sum(milestones),
        streak: Math.max(top.streak || 0, latestStreak ? latestStreak.streak || 0 : 0) });
    } else if (latestStreak) {
      rest.push(latestStreak);
    }
    rest.sort(function (a, b) {
      return (order[a.kind] === undefined ? 9 : order[a.kind]) - (order[b.kind] === undefined ? 9 : order[b.kind]);
    });
    rest.forEach(function (item) {
      enqueue(function () { return playCelebration(item, context); });
    });
    return queue;
  }

  /**
   * Fetches rewards granted in the background, acknowledges them, then plays them once.
   * A second caller while a check is running gets the same promise, so it can wait for the
   * celebrations to finish instead of showing its own dialog on top of them.
   */
  function checkPending(context) {
    if (!loggedIn()) return Promise.resolve();
    if (pendingCheck) return pendingCheck;
    pendingCheck = (async function () {
      try {
        // Keep asking until nothing is left, so a large backlog is not cut off at one page.
        for (var round = 0; round < 5; round++) {
          var items = (await api('/api/mission/celebrations')) || [];
          holdForCelebrations(items);
          if (round === 0 || items.length) await refreshChip(false);
          if (!items.length) return;
          await api('/api/mission/celebrations/ack', {
            method: 'POST',
            body: JSON.stringify({ ids: items.map(function (item) { return item.id; }) })
          }).catch(function () { round = 5; /* replay next time rather than lose it */ });
          await celebrate(items, context);
        }
      } catch (e) {
        /* endpoint unavailable: nothing to celebrate */
      } finally {
        pendingCheck = null;
        // whatever a celebration did not hand over (skipped, failed) is shown now
        await queue;
        releaseCredits(chipState.held, 700);
        if (chipState.streakHeld !== null) { chipState.streakHeld = null; renderStreak(); }
      }
    })();
    return pendingCheck;
  }

  /** Holds back the CP and streak day that these celebrations will hand over while they play. */
  function holdForCelebrations(items) {
    items.forEach(function (item) {
      if (['DAILY_GOAL', 'STREAK_MILESTONE', 'CREDIT_EARNED'].indexOf(item.kind) !== -1) holdCredits(item.amount || 0);
    });
    var days = items.filter(function (item) {
      return (item.kind === 'STREAK_EXTENDED' || item.kind === 'STREAK_MILESTONE') && item.streak;
    }).map(function (item) { return item.streak; });
    if (days.length) {
      var previous = Math.max(0, Math.min.apply(null, days) - 1);
      chipState.streakHeld = chipState.streakHeld === null ? previous : Math.min(chipState.streakHeld, previous);
    }
  }

  /** A dialog that waits its turn behind running animations; resolves with the chosen action. */
  function prompt(options) {
    var choice;
    return enqueue(function () {
      return overlay(options).then(function (value) { choice = value; });
    }).then(function () { return choice; });
  }

  function boot() {
    if (!loggedIn()) return;
    // checkPending paints the chip after holding back what the celebrations will hand over,
    // so the chip never shows the final number before the animation.
    checkPending();
  }

  window.RewardFX = {
    boot: boot,
    refreshChip: refreshChip,
    setChip: setChip,
    claim: claim,
    freezeUsed: freezeUsed,
    celebrate: celebrate,
    checkPending: checkPending,
    toast: toast,
    overlay: overlay,
    prompt: prompt,
    muted: muted,
    setMuted: setMuted,
    motionMode: motionMode,
    setMotionMode: setMotionMode,
    fireEngine: fireEngine
  };

  applyMotionClass();

  // Older pages call RetentionReward.show(...) after a reward; route them through the same animation.
  window.RetentionReward = {
    show: function (data) {
      return claim({
        rewardPoints: data && (data.points || data.rewardPoints),
        creditBalance: data && (data.balance || data.creditBalance),
        title: data && data.missionTitle
      }, document.activeElement);
    }
  };
}());
