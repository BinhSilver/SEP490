/**
 * Cảnh xem trước tự chạy trên thẻ game ở trang Trò chơi và trang Thiết lập trận (thay ảnh tĩnh):
 *  - Bắt cá: cá rơi xuống, mèo chạy tới hứng.
 *  - Lật thẻ: lật 2 thẻ ra một cặp Kanji – nghĩa rồi úp lại.
 *  - Bắn máy bay: máy bay địch mang chữ lao xuống, máy bay mình bắn hạ.
 * Cảnh luôn tự chạy (kể cả khi máy bật giảm chuyển động), nhưng chỉ khi thẻ đang hiện trên màn hình và tab đang mở.
 */
(function () {
  'use strict';

  var IMG = '/arena/images/previews/';

  function el(tag, className, parent) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (parent) parent.appendChild(node);
    return node;
  }

  function preload(names) {
    names.forEach(function (name) { new Image().src = IMG + name; });
  }

  /** Bật lại một animation CSS chạy một lần (bỏ class, ép reflow, thêm lại). */
  function replay(node, className) {
    node.classList.remove(className);
    void node.offsetWidth;
    node.classList.add(className);
  }

  /** Vòng lặp có thể dừng: mỗi bước là [hàm, số ms chờ sau đó]. */
  function Loop(steps) {
    this.steps = steps;
    this.timer = null;
    this.index = 0;
  }

  Loop.prototype.start = function () {
    if (this.timer) return;
    var self = this;
    (function tick() {
      var step = self.steps[self.index % self.steps.length];
      self.index += 1;
      var wait = step();
      self.timer = window.setTimeout(tick, wait);
    })();
  };

  Loop.prototype.stop = function () {
    window.clearTimeout(this.timer);
    this.timer = null;
  };

  // ------------------------------------------------------------------ Bắt cá
  function neko(root) {
    preload(['cat-run.webp', 'cat-catch.webp', 'cat-idle.webp', 'fish-mint.webp']);
    el('div', 'pv-bg', root);
    var fish = el('div', 'pv-fish', root);
    var cat = el('div', 'pv-cat is-idle', root);
    var pop = el('div', 'pv-pop', root);
    pop.textContent = '+10';
    var words = ['ăn', 'nước', 'đi', 'nghe', 'núi', 'đọc'];
    var spots = [72, 30, 58, 22, 80, 44];
    var round = 0;
    var catX = 50;
    cat.style.left = catX + '%';
    fish.style.opacity = '0';

    function setCat(state) {
      cat.classList.remove('is-idle', 'is-run', 'is-catch');
      void cat.offsetWidth;
      cat.classList.add(state);
    }

    var FALL = 1500;
    var steps = [
      function drop() {
        var x = spots[round % spots.length];
        fish.className = 'pv-fish' + (round % 2 ? ' pv-fish--mint' : '');
        fish.textContent = words[round % words.length];
        fish.style.transition = 'none';
        fish.style.left = x + '%';
        fish.style.top = '-14%';
        fish.style.opacity = '1';
        void fish.offsetWidth;
        fish.style.transition = 'top ' + FALL + 'ms cubic-bezier(0.45, 0, 0.9, 0.6)';
        fish.style.top = '58%';

        var run = Math.min(FALL - 200, Math.max(500, Math.abs(x - catX) * 24));
        cat.classList.toggle('is-left', x < catX);
        setCat('is-run');
        cat.style.transition = 'left ' + run + 'ms linear';
        cat.style.left = x + '%';
        catX = x;
        window.setTimeout(function () { if (cat.classList.contains('is-run')) setCat('is-idle'); }, run);
        round += 1;
        return FALL;
      },
      function catchFish() {
        setCat('is-catch');
        fish.classList.add('is-caught');
        pop.style.left = catX + '%';
        pop.style.top = '44%';
        replay(pop, 'is-on');
        return 600;
      },
      function rest() {
        setCat('is-idle');
        fish.style.opacity = '0';
        return 450;
      }
    ];

    return { loop: new Loop(steps) };
  }

  // ----------------------------------------------------------------- Lật thẻ
  function memory(root) {
    var PAIRS = [['水', 'nước'], ['山', 'núi'], ['食べる', 'ăn'], ['見る', 'nhìn']];
    // Vị trí 8 thẻ trên lưới 4x2: [cặp, mặt] (0 = Kanji, 1 = nghĩa).
    var LAYOUT = [[0, 0], [2, 1], [1, 0], [3, 1], [3, 0], [0, 1], [2, 0], [1, 1]];
    var grid = el('div', 'pv-grid', root);
    var cards = LAYOUT.map(function (slot) {
      var card = el('div', 'pv-card', grid);
      var inner = el('div', 'pv-card__inner', card);
      el('div', 'pv-card__back', inner).textContent = '❄';
      var front = el('div', 'pv-card__front', inner);
      el('small', '', front).textContent = slot[1] ? 'NGHĨA' : 'KANJI';
      el('b', slot[1] ? '' : 'jp', front).textContent = PAIRS[slot[0]][slot[1]];
      return { node: card, pair: slot[0] };
    });

    function pairCards(pair) {
      return cards.filter(function (card) { return card.pair === pair; });
    }

    var order = [0, 2, 1, 3];
    var turn = 0;
    var current = [];
    var steps = [
      function flipFirst() {
        current = pairCards(order[turn % order.length]);
        current[0].node.classList.add('is-flipped');
        return 750;
      },
      function flipSecond() {
        current[1].node.classList.add('is-flipped');
        return 650;
      },
      function match() {
        current.forEach(function (card) { card.node.classList.add('is-matched'); });
        return 1300;
      },
      function flipBack() {
        current.forEach(function (card) { card.node.classList.remove('is-flipped', 'is-matched'); });
        turn += 1;
        return 800;
      }
    ];

    return { loop: new Loop(steps) };
  }

  // ------------------------------------------------------------ Bắn máy bay
  function air(root) {
    preload(['enemy-red.png', 'explosion.png']);
    var enemy = el('div', 'pv-enemy', root);
    var label = el('span', 'pv-enemy__label', enemy);
    el('i', 'pv-enemy__ship', enemy);
    var laser = el('div', 'pv-laser', root);
    var boom = el('div', 'pv-boom', root);
    var pop = el('div', 'pv-pop', root);
    pop.textContent = '+100';
    var player = el('div', 'pv-player', root);
    var plane = el('span', 'ar-sprite ar-sprite--plane', player);
    plane.style.backgroundImage = 'url(/arena/images/shop/anim/plane-blue.png)';
    var flame = el('span', 'ar-sprite ar-sprite--exhaust', player);
    flame.style.backgroundImage = 'url(/arena/images/shop/anim/exhaust.png)';

    var WORDS = ['水', '山', '読む', '飲む', '見る'];
    var SPOTS = [34, 64, 44, 70, 28];
    var DESCEND = 2000;
    var round = 0;
    var x = 50;
    var steps = [
      function spawn() {
        x = SPOTS[round % SPOTS.length];
        enemy.className = 'pv-enemy' + (round % 2 ? ' pv-enemy--red' : '');
        label.textContent = WORDS[round % WORDS.length];
        enemy.style.transition = 'none';
        enemy.style.left = x + '%';
        enemy.style.top = '-12%';
        void enemy.offsetWidth;
        enemy.style.transition = 'top ' + DESCEND + 'ms linear';
        enemy.style.top = '44%';
        round += 1;
        return DESCEND - 300;
      },
      function fire() {
        // Tia laser từ mũi máy bay mình tới máy bay địch (tính theo vị trí thật trên màn hình).
        var box = root.getBoundingClientRect();
        var from = player.getBoundingClientRect();
        var to = enemy.querySelector('.pv-enemy__ship').getBoundingClientRect();
        // Khung cha có thể bị zoom (sảnh thu nhỏ 90%): đổi px màn hình về px của khung.
        var scale = root.offsetWidth ? box.width / root.offsetWidth : 1;
        var x1 = (from.left + from.width / 2 - box.left) / scale;
        var y1 = (from.top + from.height * 0.15 - box.top) / scale;
        var x2 = (to.left + to.width / 2 - box.left) / scale;
        var y2 = (to.top + to.height / 2 - box.top) / scale;
        laser.style.left = x1 + 'px';
        laser.style.top = y1 + 'px';
        laser.style.width = Math.hypot(x2 - x1, y2 - y1) + 'px';
        laser.style.transform = 'rotate(' + Math.atan2(y2 - y1, x2 - x1) + 'rad)';
        replay(laser, 'is-on');
        return 160;
      },
      function explode() {
        var box = root.getBoundingClientRect();
        var ship = enemy.querySelector('.pv-enemy__ship').getBoundingClientRect();
        var left = ((ship.left + ship.width / 2 - box.left) / box.width) * 100;
        var top = ((ship.top + ship.height / 2 - box.top) / box.height) * 100;
        enemy.classList.add('is-dead');
        boom.style.left = left + '%';
        boom.style.top = top + '%';
        replay(boom, 'is-on');
        pop.style.left = left + '%';
        pop.style.top = (top - 12) + '%';
        replay(pop, 'is-on');
        return 900;
      }
    ];

    return { loop: new Loop(steps) };
  }

  // ------------------------------------------------------------ Kana Connect
  /**
   * The card's board plays like the real game: a 3×3 board in its beige frame where every kana belongs to one word.
   * A finger drags a purple "snake" with reading-direction chevrons under the letters; a right word turns the snake
   * lime and fills that word's row of slots beside the board; when all four are found the board resets.
   */
  function kanaConnect(root) {
    var KANA = ['か', 'な', 'ほ', 'ね', 'ぐ', 'し', 'こ', 'な', 'つ'];
    var SIZE = 3;
    var WORDS = [
      { cells: [0, 1], kana: 'かな', meaning: 'chữ kana' },
      { cells: [2, 5], kana: 'ほし', meaning: 'ngôi sao' },
      { cells: [8, 7, 4], kana: 'つなぐ', meaning: 'nối' },
      { cells: [3, 6], kana: 'ねこ', meaning: 'con mèo' }
    ];
    var svgNs = 'http://www.w3.org/2000/svg';

    var frame = el('div', 'pv-kc-frame', root);
    var board = el('div', 'pv-kc-board', frame);
    var tiles = KANA.map(function (kana) {
      var tile = el('span', 'pv-kc-tile', board);
      el('b', '', tile).textContent = kana;
      return tile;
    });
    var svg = document.createElementNS(svgNs, 'svg');
    svg.setAttribute('class', 'pv-kc-snakes');
    svg.setAttribute('viewBox', '0 0 ' + SIZE * 100 + ' ' + SIZE * 100);
    board.appendChild(svg);
    var finger = el('span', 'pv-kc-finger', board);

    var side = el('div', 'pv-kc-side', root);
    var status = el('div', 'pv-kc-status', side);
    var list = el('div', 'pv-kc-words', side);
    var rows = WORDS.map(function (word) {
      var row = el('div', 'pv-kc-word', list);
      return Array.prototype.map.call(word.kana, function () { return el('span', 'pv-kc-slot', row); });
    });
    var gloss = el('div', 'pv-kc-gloss', side);

    /** Cell centre in board units: each cell is 100×100, so the snake scales with the board. */
    function point(cell) {
      return [(cell % SIZE) * 100 + 50, Math.floor(cell / SIZE) * 100 + 50];
    }

    function polyline(className, cells) {
      var line = document.createElementNS(svgNs, 'polyline');
      line.setAttribute('class', className);
      line.setAttribute('points', cells.map(function (c) { return point(c).join(','); }).join(' '));
      return line;
    }

    /** A snake as in the game: edge, fill, and a chevron between each pair of cells in reading order. */
    function snake(cells, state) {
      var group = document.createElementNS(svgNs, 'g');
      group.setAttribute('class', 'pv-kc-snake pv-kc-snake--' + state);
      group.appendChild(polyline('pv-kc-snake__edge', cells));
      group.appendChild(polyline('pv-kc-snake__fill', cells));
      for (var i = 1; i < cells.length; i++) {
        var a = point(cells[i - 1]), b = point(cells[i]);
        var dx = Math.sign(b[0] - a[0]), dy = Math.sign(b[1] - a[1]);
        var mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
        var arrow = document.createElementNS(svgNs, 'polyline');
        arrow.setAttribute('class', 'pv-kc-snake__arrow');
        arrow.setAttribute('points', [
          [mx - dx * 6 - dy * 9, my - dy * 6 - dx * 9],
          [mx + dx * 6, my + dy * 6],
          [mx - dx * 6 + dy * 9, my - dy * 6 + dx * 9]
        ].map(function (p) { return p.join(','); }).join(' '));
        group.appendChild(arrow);
      }
      return group;
    }

    var foundSnakes = [];
    var active = null;
    var found = 0;

    function setActive(cells) {
      if (active) active.remove();
      active = cells.length ? snake(cells, 'active') : null;
      if (active) svg.appendChild(active);
    }

    function moveFinger(cell) {
      var p = point(cell);
      finger.style.left = (p[0] / (SIZE * 100)) * 100 + '%';
      finger.style.top = (p[1] / (SIZE * 100)) * 100 + '%';
    }

    function showStatus() {
      status.className = 'pv-kc-status';
      status.textContent = found + '/' + WORDS.length + ' từ';
    }

    var steps = [];
    WORDS.forEach(function (word, w) {
      word.cells.forEach(function (cell, n) {
        steps.push(function drag() {
          var picked = word.cells.slice(0, n + 1);
          finger.classList.add('is-on');
          moveFinger(cell);
          setActive(n === 0 ? [] : picked);
          tiles[cell].classList.add('is-picked');
          // The kana picked so far, in purple, as in the game's selection line.
          status.className = 'pv-kc-status is-selecting';
          status.textContent = picked.map(function (c) { return KANA[c]; }).join('');
          return n === 0 ? 450 : 380;
        });
      });
      steps.push(function release() {
        setActive([]);
        var done = snake(word.cells, 'found');
        svg.appendChild(done);
        foundSnakes.push(done);
        word.cells.forEach(function (cell) {
          tiles[cell].classList.remove('is-picked');
          tiles[cell].classList.add('is-found');
        });
        rows[w].forEach(function (slot, i) {
          slot.textContent = word.kana.charAt(i);
          slot.classList.add('is-found');
        });
        finger.classList.remove('is-on');
        found = w + 1;
        showStatus();
        gloss.innerHTML = '';
        el('b', '', gloss).textContent = word.kana;
        gloss.appendChild(document.createTextNode(' ' + word.meaning));
        replay(gloss, 'is-on');
        return 1000;
      });
    });
    steps.push(function complete() {
      root.classList.add('is-complete');
      status.className = 'pv-kc-status is-done';
      status.textContent = 'Hoàn thành!';
      gloss.classList.remove('is-on');
      return 1600;
    });
    steps.push(function reset() {
      root.classList.remove('is-complete');
      foundSnakes.forEach(function (g) { g.remove(); });
      foundSnakes = [];
      setActive([]);
      tiles.forEach(function (tile) { tile.classList.remove('is-picked', 'is-found'); });
      rows.forEach(function (row) { row.forEach(function (slot) { slot.textContent = ''; slot.classList.remove('is-found'); }); });
      found = 0;
      showStatus();
      return 700;
    });

    showStatus();
    return { loop: new Loop(steps) };
  }

  var SCENES = { neko: neko, memory: memory, 'air-defense': air, 'kana-connect': kanaConnect };

  function mount(root) {
    var build = SCENES[root.dataset.gamePreview];
    if (!build) return null;
    root.innerHTML = '';
    root.classList.add('ar-preview--' + root.dataset.gamePreview);
    return build(root);
  }

  var scenes = [];

  function setRunning(item, running) {
    item.root.classList.toggle('is-paused', !running);
    if (running) item.scene.loop.start();
    else item.scene.loop.stop();
  }

  var observer = 'IntersectionObserver' in window
    ? new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var item = scenes.find(function (candidate) { return candidate.root === entry.target; });
        if (item) setRunning(item, entry.isIntersecting && document.visibilityState === 'visible');
      });
    }, { threshold: 0.2 })
    : null;

  /** Dựng cảnh cho mọi [data-game-preview] chưa dựng trong container (trang nào vẽ thẻ game bằng JS thì gọi lại). */
  function mountAll(container) {
    (container || document).querySelectorAll('[data-game-preview]').forEach(function (root) {
      if (root.dataset.previewMounted) return;
      var scene = mount(root);
      if (!scene) return;
      root.dataset.previewMounted = 'true';
      var item = { root: root, scene: scene };
      scenes.push(item);
      if (observer) observer.observe(root);
      else setRunning(item, true);
    });
  }

  // Ẩn tab thì dừng hết cho nhẹ máy; quay lại thì chạy lại những cảnh đang hiện.
  document.addEventListener('visibilitychange', function () {
    scenes.forEach(function (item) {
      var box = item.root.getBoundingClientRect();
      var onScreen = box.bottom > 0 && box.top < window.innerHeight;
      setRunning(item, document.visibilityState === 'visible' && onScreen);
    });
  });

  window.JlasGamePreviews = { mountAll: mountAll };
  mountAll(document);
})();
