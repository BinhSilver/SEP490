(function () {
  'use strict';
  var names = { 'cat-godzilla': 'godzilla', 'cat-ronova': 'ronova', 'cat-zenitsu': 'zenitsu', 'cat-tanjiro': 'tanjiro' };
  ['halo-fighter', 'aegis-frame', 'split-core', 'void-blade'].forEach(function (name) { names['plane-' + name] = name; });
  function ship(code) { return code.indexOf('plane-') === 0; }
  var entries = new Map(), bundles = new Map(), images = new Map(), raf = 0;
  // Luôn chạy hoạt ảnh, không theo prefers-reduced-motion của hệ điều hành (quyết định sản phẩm).
  var motion = { matches: false, addEventListener: function () {} };
  function manifest(name, code) {
    if (!bundles.has(name)) {
      var path = ship(code) ? '/arena/app/air-defense/assets/space/skins/' : '/arena/app/neko/sprites/actions/';
      bundles.set(name, fetch(path + name + '/manifest.json?v=20261005-aligned-1')
        .then(function (response) { if (!response.ok) throw new Error('Character atlas unavailable'); return response.json(); }));
    }
    return bundles.get(name);
  }
  function image(code, effects) {
    var key = code + (effects ? ':effects' : '');
    if (!images.has(key)) images.set(key, new Promise(function (resolve, reject) {
      var img = new Image(); img.onload = function () { resolve(img); }; img.onerror = reject;
      img.src = ship(code) ? '/arena/app/air-defense/assets/space/skins/' + names[code] + (effects ? '/effects.png' : '/body.png') : '/arena/images/shop/anim/' + code + '.png';
    }));
    return images.get(key);
  }
  function draw(time) {
    raf = 0;
    if (document.hidden) return;
    entries.forEach(function (entry, canvas) {
      if (!canvas.isConnected) { entries.delete(canvas); return; }
      var config = entry.config;
      // Thời điểm của requestAnimationFrame là lúc khung hình bắt đầu, có thể sớm hơn lúc canvas được gắn:
      // không để thời gian trôi qua âm, kẻo chỉ số khung hình âm và không có khung nào để vẽ.
      var elapsed = Math.max(0, time - entry.start);
      var index = motion.matches ? 0 : Math.floor(elapsed / config.frameDurationMs) % config.frameCount;
      var frame = config.sourceFrames[index], ctx = canvas.getContext('2d');
      if (!frame) return;
      var scale = canvas.height * (entry.ship ? 0.72 : 0.9) / (frame.scaleHeight || frame.height);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (entry.effects) {
        var flameIndex = motion.matches ? 0 : Math.floor(elapsed / 90) % 4;
        var flame = entry.effectFrames[flameIndex] || entry.effectFrames[0], flameScale = canvas.height * 0.27 / flame.height;
        ctx.drawImage(entry.effects, flame.x, flame.y, flame.width, flame.height,
          canvas.width / 2 - flame.centerX * flameScale, canvas.height * 0.62 - flame.centerY * flameScale,
          flame.width * flameScale, flame.height * flameScale);
      }
      ctx.drawImage(entry.image, frame.x, frame.y, frame.width, frame.height,
        canvas.width / 2 - (frame.centerX == null ? frame.width / 2 : frame.centerX) * scale,
        entry.ship ? canvas.height / 2 - frame.centerY * scale : canvas.height * 0.97 - frame.anchorY * scale,
        frame.width * scale, frame.height * scale);
    });
    if (entries.size && !motion.matches) raf = requestAnimationFrame(draw);
  }
  function refresh() { cancelAnimationFrame(raf); raf = 0; draw(performance.now()); }
  function loadUrl(url) {
    if (!images.has(url)) images.set(url, new Promise(function (resolve, reject) {
      var img = new Image(); img.onload = function () { resolve(img); }; img.onerror = function () { images.delete(url); reject(); };
      img.src = url;
    }));
    return images.get(url);
  }
  /** Khung lưới khi manifest mèo không ghi sourceFrames: ô cellSize, neo ở giữa đáy (baseline). */
  function gridFrames(bundle, config) {
    var frames = [];
    for (var i = 0; i < config.frameCount; i++) frames.push({ x: (i % config.columns) * bundle.cellSize,
      y: Math.floor(i / config.columns) * bundle.cellSize, width: bundle.cellSize, height: bundle.cellSize,
      anchorX: bundle.cellSize / 2, anchorY: bundle.baseline });
    return frames;
  }
  /**
   * Gói skin admin tải lên (data-skin-pack = thư mục gói, data-skin-game = ship | cat): đọc manifest.json của gói,
   * ảnh nằm theo đường dẫn tương đối trong gói.
   */
  function packSource(pack, game) {
    var key = 'pack:' + pack;
    if (!bundles.has(key)) bundles.set(key, fetch(pack + 'manifest.json')
      .then(function (response) { if (!response.ok) throw new Error('Skin pack unavailable'); return response.json(); }));
    return bundles.get(key).then(function (bundle) {
      var isShip = game === 'ship', idle = bundle.actions.idle;
      var config = isShip || (idle.sourceFrames && idle.sourceFrames.length) ? idle
        : Object.assign({}, idle, { sourceFrames: gridFrames(bundle, idle) });
      return Promise.all([loadUrl(pack + (isShip ? bundle.body.path : idle.path)),
        isShip ? loadUrl(pack + bundle.effects.path) : Promise.resolve(null)]).then(function (loaded) {
        return { config: config, image: loaded[0], effects: loaded[1], effectFrames: isShip ? bundle.effects.frames : null, ship: isShip };
      });
    });
  }
  function builtinSource(code) {
    var name = names[code];
    if (!name) return null;
    return Promise.all([manifest(name, code), image(code), ship(code) ? image(code, true) : Promise.resolve(null)]).then(function (loaded) {
      return { config: loaded[0].actions.idle, image: loaded[1], effects: loaded[2],
        effectFrames: loaded[0].effects && loaded[0].effects.frames, ship: ship(code) };
    });
  }
  function mount(container) {
    container.querySelectorAll('canvas[data-character-skin], canvas[data-skin-pack]').forEach(function (canvas) {
      if (canvas.dataset.characterMounted) return;
      var pack = canvas.dataset.skinPack, code = canvas.dataset.characterSkin;
      var pending = pack ? packSource(pack, canvas.dataset.skinGame) : builtinSource(code);
      if (!pending) return;
      canvas.dataset.characterMounted = 'true';
      pending.then(function (source) {
        if (!canvas.isConnected) return;
        entries.set(canvas, { config: source.config, image: source.image, effects: source.effects,
          effectFrames: source.effectFrames, start: performance.now(), ship: source.ship });
        var fallback = canvas.parentElement && canvas.parentElement.querySelector('[data-character-fallback]');
        if (fallback) fallback.hidden = true;
        canvas.hidden = false;
        refresh();
      }).catch(function () {
        canvas.dataset.characterMounted = '';
        if (pack) bundles.delete('pack:' + pack); else { bundles.delete(names[code]); images.delete(code); }
      });
    });
  }
  document.addEventListener('visibilitychange', refresh);
  motion.addEventListener('change', refresh);
  function supports(code) { return Object.prototype.hasOwnProperty.call(names, code); }
  var ANIM = '/arena/images/shop/anim/';
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }
  /**
   * Markup for a skin's animated preview, shared by the shop, the room skin picker and the VS screen.
   * item: { code, name, image, renderCode, packPath }. Call mount() on its container once it is in the page.
   */
  function art(item, gameType) {
    var renderer = item.renderCode || item.code;
    // Gói skin tải lên: ảnh đại diện (đã là một khung, nên không dùng cách cắt atlas 4×4 của .ar-spaceship-art)
    // trong lúc tải, rồi chuyển động idle vẽ từ chính gói.
    if (renderer === 'PACK' && item.packPath) {
      return '<span class="ar-character-art"><img src="' + esc(item.image) + '" alt="" loading="lazy" data-character-fallback>'
        + '<canvas width="256" height="256" hidden data-skin-pack="' + esc(item.packPath) + '" data-skin-game="' + (gameType === 'AIR_DEFENSE' ? 'ship' : 'cat')
        + '" role="img" aria-label="' + esc(item.name) + '"></canvas></span>';
    }
    if (renderer === 'CUSTOM') return '<img src="' + esc(item.image) + '" alt="' + esc(item.name) + '" loading="lazy" style="width:100%;height:100%;object-fit:contain">';
    if (supports(renderer)) {
      return '<span class="ar-character-art' + (gameType === 'AIR_DEFENSE' ? ' ar-spaceship-art' : '') + '"><img src="' + esc(item.image) + '" alt="" loading="lazy" data-character-fallback>'
        + '<canvas width="256" height="256" hidden data-character-skin="' + esc(renderer)
        + '" role="img" aria-label="' + esc(item.name) + '"></canvas></span>';
    }
    if (gameType === 'AIR_DEFENSE') {
      return '<span class="ar-plane-fly" role="img" aria-label="' + esc(item.name) + '">'
        + '<span class="ar-sprite ar-sprite--plane" style="background-image:url(' + ANIM + esc(renderer) + '.png)"></span>'
        + '<span class="ar-sprite ar-sprite--exhaust" style="background-image:url(' + ANIM + 'exhaust.png)"></span></span>';
    }
    if (gameType === 'NEKO_CATCH') {
      return '<span class="ar-sprite ar-sprite--cat" role="img" aria-label="' + esc(item.name) + '"'
        + ' style="background-image:url(' + ANIM + esc(renderer) + '.webp)"></span>';
    }
    return '<img src="' + esc(item.image) + '" alt="' + esc(item.name) + '" loading="lazy">';
  }
  window.JlasCharacterSkins = { supports: supports, mount: mount, art: art };
}());
