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
      var index = motion.matches ? 0 : Math.floor((time - entry.start) / config.frameDurationMs) % config.frameCount;
      var frame = config.sourceFrames[index], ctx = canvas.getContext('2d');
      var scale = canvas.height * (entry.ship ? 0.72 : 0.9) / (frame.scaleHeight || frame.height);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (entry.effects) {
        var flameIndex = motion.matches ? 0 : Math.floor((time - entry.start) / 90) % 4;
        var flame = entry.effectFrames[flameIndex], flameScale = canvas.height * 0.27 / flame.height;
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
  function mount(container) {
    container.querySelectorAll('canvas[data-character-skin]').forEach(function (canvas) {
      if (canvas.dataset.characterMounted) return;
      canvas.dataset.characterMounted = 'true';
      var code = canvas.dataset.characterSkin, name = names[code];
      if (!name) return;
      Promise.all([manifest(name, code), image(code), ship(code) ? image(code, true) : Promise.resolve(null)]).then(function (loaded) {
        if (!canvas.isConnected) return;
        entries.set(canvas, { config: loaded[0].actions.idle, image: loaded[1], effects: loaded[2], effectFrames: loaded[0].effects && loaded[0].effects.frames,
          start: performance.now(), ship: ship(code) });
        var fallback = canvas.parentElement.querySelector('[data-character-fallback]');
        if (fallback) fallback.hidden = true;
        canvas.hidden = false;
        refresh();
      }).catch(function () { canvas.dataset.characterMounted = ''; bundles.delete(name); images.delete(code); });
    });
  }
  document.addEventListener('visibilitychange', refresh);
  motion.addEventListener('change', refresh);
  window.JlasCharacterSkins = { supports: function (code) { return Object.prototype.hasOwnProperty.call(names, code); }, mount: mount };
}());
