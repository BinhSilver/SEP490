(function () {
  'use strict';
  var $ = function (id) { return document.getElementById('km-' + id); };
  if (!$('board')) return;
  var model = window.KanjiMapModel;
  var state = { graph: null, activeGraph: null, projections: new Map(), data: null, filtered: null, layout: null, selected: null,
    collapsed: new Set(), limits: new Map(), mastered: new Set(),
    detailVersion: 0, expanded: false, view: { x: 0, y: 0, scale: 1 }, animations: [], progressReady: false,
    compareBase: null, study: null };
  var geometry = model.geometry;
  var board = $('board'), detail = $('detail'), pointers = new Map(), gesture = null;
  var pronunciation = null, pronunciationButton = null, pronunciationTimer = null;
  var diagramMotion = null;
  var compareDiagramMotions = [];
  var notebook = window.KanjiMapNotebook.create({request:request, headers:headers, loggedIn:loggedIn, notice:notice, onChange:updateNotebookButton});
  function updateNotebookButton() {
    var button = detail.querySelector('[data-action="notebook"]');
    if (button) { button.textContent = notebook.has(state.selected) ? '✓ Đã lưu · Chọn sổ' : 'Lưu vào sổ tay'; button.disabled = notebook.busy(); }
    var studyButton = $('study-content').querySelector('[data-study-notebook]');
    if (studyButton && state.study) {
      var allSaved = state.study.wrong.every(function(ch) { return notebook.has(ch); });
      studyButton.disabled = notebook.busy();
      studyButton.textContent = allSaved ? '✓ Đã lưu · Chọn sổ' : 'Lưu chữ sai vào sổ tay';
    }
  }
  function stopDiagramMotion() { if (diagramMotion) diagramMotion.destroy(); diagramMotion = null; }
  function stopCompareDiagrams() { compareDiagramMotions.forEach(function (motion) { motion.destroy(); }); compareDiagramMotions = []; }
  function stopPronunciation() {
    var active = pronunciation;
    pronunciation = null;
    window.clearTimeout(pronunciationTimer); pronunciationTimer = null;
    if (pronunciationButton) {
      pronunciationButton.classList.remove('is-speaking');
      pronunciationButton.setAttribute('aria-pressed', 'false');
    }
    pronunciationButton = null;
    if (active && window.speechSynthesis) window.speechSynthesis.cancel();
  }
  function speakVocabulary(button) {
    var same = pronunciationButton === button;
    stopPronunciation();
    var status = $('pronunciation-status');
    if (status) status.textContent = '';
    if (same) return;
    if (!window.speechSynthesis || !window.SpeechSynthesisUtterance) {
      if (status) status.textContent = 'Trình duyệt này chưa hỗ trợ phát âm.';
      return;
    }
    var text = button.dataset.reading || button.dataset.expression;
    if (!text) return;
    var voices = window.speechSynthesis.getVoices();
    var japanese = voices.find(function (voice) { return /^ja(?:-|_|$)/i.test(voice.lang); });
    if (voices.length && !japanese) {
      if (status) status.textContent = 'Chưa có giọng tiếng Nhật. Hãy thêm giọng tiếng Nhật trong thiết bị hoặc thử trình duyệt khác.';
      return;
    }
    var utterance = new window.SpeechSynthesisUtterance(text);
    utterance.lang = 'ja-JP'; utterance.rate = .85;
    if (japanese) utterance.voice = japanese;
    pronunciation = utterance; pronunciationButton = button;
    button.classList.add('is-speaking'); button.setAttribute('aria-pressed', 'true');
    utterance.onstart = function () {
      if (pronunciation !== utterance) return;
      window.clearTimeout(pronunciationTimer); pronunciationTimer = null;
      if (status) status.textContent = 'Đang phát: ' + button.dataset.expression;
    };
    utterance.onend = function () {
      if (pronunciation !== utterance) return;
      stopPronunciation(); if (status) status.textContent = '';
    };
    utterance.onerror = function (event) {
      if (pronunciation !== utterance) return;
      stopPronunciation();
      if (status) status.textContent = event.error === 'canceled' || event.error === 'interrupted' ? '' : 'Chưa phát được tiếng Nhật. Vui lòng kiểm tra giọng đọc của thiết bị rồi thử lại.';
    };
    pronunciationTimer = window.setTimeout(function () {
      if (pronunciation !== utterance) return;
      stopPronunciation(); if (status) status.textContent = 'Giọng đọc chưa sẵn sàng. Vui lòng bấm loa để thử lại.';
    }, 8000);
    try { window.speechSynthesis.cancel(); window.speechSynthesis.speak(utterance); }
    catch (error) {
      stopPronunciation(); if (status) status.textContent = 'Chưa phát được tiếng Nhật. Vui lòng thử lại.';
    }
  }
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]; }); }
  function loggedIn() { return !!(window.JlasAuth && window.JlasAuth.isLoggedIn()); }
  function headers() { return window.JlasAuth ? window.JlasAuth.authHeaders() : {}; }
  async function request(path, options) {
    var response = await fetch(path, Object.assign({ headers: headers() }, options || {}));
    var body = await response.json();
    if (!response.ok || body.success === false) throw new Error(body.message || 'Chưa thể hoàn tất. Vui lòng thử lại.');
    return body.data === undefined ? body : body.data;
  }
  function notice(message, login) {
    $('notice').textContent = message;
    $('notice').hidden = !message;
    if (login) {
      var link = document.createElement('a');
      link.href = '/login?redirect=' + encodeURIComponent(location.pathname + location.search);
      link.textContent = ' Đăng nhập →'; $('notice').appendChild(link);
    }
  }
  function typeLabel(node) { return node.isRadicalVariant ? 'Dạng bộ thủ' : node.isKanji && node.isRadical ? 'Kanji · Bộ thủ' : node.isKanji ? 'Kanji' : node.isRadical ? 'Bộ thủ' : 'Thành phần'; }
  function nameOf(node) { return model.displayName(node); }
  function meaning(node) { return model.vietnameseMeaning(node); }
  function transform() {
    var v = state.view;
    $('world').style.transform = 'translate(' + Math.round(v.x) + 'px,' + Math.round(v.y) + 'px) scale(' + v.scale + ')';
    $('zoom-value').textContent = Math.round(v.scale * 100) + '%';
  }
  function zoom(factor, x, y) {
    var old = state.view.scale, next = Math.max(.005, Math.min(2.4, old * factor));
    x = x == null ? board.clientWidth / 2 : x; y = y == null ? board.clientHeight / 2 : y;
    state.view.x = x - (x - state.view.x) * next / old;
    state.view.y = y - (y - state.view.y) * next / old; state.view.scale = next; transform();
  }
  function fit() {
    if (!state.layout || !board.clientWidth || !board.clientHeight) return;
    var l = state.layout, scale = Math.max(.005, Math.min(1.1, (board.clientWidth - 100) / l.width, (board.clientHeight - 100) / l.height));
    state.view = { x: (board.clientWidth - 64 - l.width * scale) / 2, y: (board.clientHeight - l.height * scale) / 2, scale: scale }; transform();
  }
  // Start at a readable size. Fit remains available for the entire forest.
  function home() {
    if (!state.layout) return;
    var root = state.layout.nodes[0], scale = board.clientWidth < 500 ? .68 : 1;
    state.view = { x: board.clientWidth < 700 ? 10 : 50, y: $('level').value && !$('branch').value ? 85 : root ? board.clientHeight / 2 - (root.y + geometry.height / 2) * scale : 74, scale: scale }; transform();
  }
  function center(character) {
    var item = state.layout.nodes.find(function (n) { return n.node.character === character; });
    if (!item) return;
    var scale = Math.min(1.25, board.clientWidth < 400 ? .95 : 1.1);
    var availableWidth = board.clientWidth;
    var compactDetail = !detail.hidden && window.matchMedia('(max-width:900px)').matches;
    state.view = {
      x: Math.max(100, availableWidth) / 2 - (item.x + geometry.width / 2) * scale,
      y: board.clientHeight * (compactDetail ? .22 : .5) - (item.y + geometry.height / 2) * scale,
      scale: scale
    }; transform();
  }
  function options() { return { level: $('level').value, branch: $('branch').value, status: $('status').value, mastered: state.mastered, levelRoots: true }; }
  function scopeLabel() {
    return ($('branch').value ? 'Nhánh ' + $('branch').value : $('level').value ? state.filtered.roots.length + ' nhánh khởi đầu' : 'Tất cả các nhánh') + ' · ' + ($('level').value === 'UNCLASSIFIED' ? 'Chưa xếp cấp' : $('level').value || 'Tất cả cấp độ');
  }
  function activeGraph() {
    var branch = $('branch').value;
    if (!branch) return state.graph;
    if (!state.projections.has(branch)) {
      if (state.projections.size >= 8) state.projections.delete(state.projections.keys().next().value);
      state.projections.set(branch, model.project(state.graph, branch));
    }
    return state.projections.get(branch);
  }
  function chooseBranch(character) {
    if (character && !Array.from($('branch').options).some(function (o) { return o.value === character; })) {
      var option = document.createElement('option'); option.value = character; option.textContent = 'Nhánh ' + character; $('branch').appendChild(option);
    }
    $('branch').value = character || '';
  }
  function refreshBranches() {
    var level = $('level').value;
    if (state.branchLevel === level) return;
    var selected = $('branch').value;
    state.branchLevel = level;
    $('branch').innerHTML = '<option value="">' + (level ? 'Các nhánh khởi đầu · ' + (level === 'UNCLASSIFIED' ? 'Chưa xếp cấp' : level) : 'Tất cả các nhánh') + '</option>';
    var roots;
    if (level) roots = model.entryRoots(state.graph, level);
    else {
      var characters = new Set(['一', '口', '人', '木', '日', '月', '十', '土', '田', '女', '水', '火', '心', '手', '金', '言', '目', '耳', '山', '糸', '竹', '艹']);
      state.graph.roots.forEach(function (root) { characters.add(root.character); });
      roots = Array.from(characters).map(function (ch) { return state.graph.nodes.get(ch); }).filter(function (node) {
        return node && (node.isKanji || Array.from(model.reachable(state.graph, node.character)).some(function (ch) { return state.graph.nodes.get(ch).isKanji; }));
      });
    }
    roots.forEach(function (root) {
      var option = document.createElement('option'); option.value = root.character;
      option.textContent = root.character + ' · ' + nameOf(root); $('branch').appendChild(option);
    });
    chooseBranch(selected);
  }
  function resetHint() { $('hint').textContent = ''; }
  function initialBranches() {
    resetHint();
    state.collapsed.clear(); state.limits.clear(); state.expanded = false;
    var graph = activeGraph();
    if ($('level').value && !$('branch').value) {
      graph.children.forEach(function (_, ch) { state.collapsed.add(ch); });
      return;
    }
    graph.children.forEach(function (_, ch) { if ((graph.depths.get(ch) || 0) >= 1 && !(graph.root === '一' && ch === '十')) state.collapsed.add(ch); });
    if (graph.root) state.limits.set(graph.root, 6);
    if (graph.root === '一') state.limits.set('十', 6);
  }
  function anchoredRender(character, mutate) {
    var before = state.layout && state.layout.nodes.find(function (n) { return n.node.character === character; });
    mutate(); render(false);
    var after = state.layout.nodes.find(function (n) { return n.node.character === character; });
    if (before && after) {
      state.view.x += (before.x - after.x) * state.view.scale;
      state.view.y += (before.y - after.y) * state.view.scale; transform();
    }
  }
  function render(refit) {
    if (!state.graph) return;
    refreshBranches();
    state.activeGraph = activeGraph();
    state.filtered = model.filter(state.activeGraph, options());
    // Richer branches first so the opening view shows connections, even across levels.
    state.filtered.roots.sort(function (a, b) {
      function size(n) { return Array.from(model.descendants(state.activeGraph, n.character)).filter(function (ch) { return state.filtered.included.has(ch); }).length; }
      return size(b) - size(a) || a.character.codePointAt(0) - b.character.codePointAt(0);
    });
    // A searched atomic component can have no descendant kanji: still show it.
    if (state.selected && !state.graph.nodes.get(state.selected).isKanji) {
      model.ancestors(state.activeGraph, state.selected).forEach(function (ch) { state.filtered.included.add(ch); });
      state.filtered.roots = state.activeGraph.roots.filter(function (n) { return state.filtered.included.has(n.character); });
    }
    state.layout = model.layout(state.activeGraph, state.filtered, state.collapsed, state.limits);
    var l = state.layout, f = state.filtered;
    var route = new Set(state.selected ? model.ancestors(state.activeGraph, state.selected) : []);
    $('world').style.width = l.width + 'px'; $('world').style.height = l.height + 'px';
    $('edges').setAttribute('width', l.width); $('edges').setAttribute('height', l.height);
    $('edges').innerHTML = l.edges.map(function (edge) {
      var a = edge.from, b = edge.to, x = a.x + geometry.width, y = a.y + geometry.height / 2, end = b.x, ey = b.y + geometry.height / 2;
      return '<path' + (route.has(a.node.character) && route.has(b.node.character) ? ' class="is-route"' : '') + ' d="M' + x + ',' + y + ' L' + end + ',' + ey + '"/>';
    }).join('');
    $('nodes').innerHTML = l.nodes.map(function (item) {
      var n = item.node, mastered = state.mastered.has(n.character);
      var totalChildren = (state.activeGraph.children.get(n.character) || []).length;
      var otherLinks = !totalChildren && (state.graph.outgoing.get(n.character) || []).length;
      var hiddenByLevel = !item.children && totalChildren && $('level').value && !$('status').value;
      var toggleLabel = otherLinks ? 'Khám phá liên kết từ ' + n.character + ' bằng cách chọn làm gốc' : hiddenByLevel ? 'Mở nhánh ' + n.character + ' ở tất cả cấp độ' : (item.collapsed ? 'Mở' : 'Thu gọn') + ' nhánh ' + n.character;
      var badges = (n.level ? '<span class="km-level-badge km-level-' + esc(n.level) + '" title="JLPT ' + esc(n.level) + '">' + esc(n.level) + '</span>' : '') +
        (n.isRadical || n.isRadicalVariant || !n.isKanji ? '<span class="km-type-badge" title="' + esc(typeLabel(n)) + '">' + (n.isRadical || n.isRadicalVariant ? n.isKanji ? 'BC' : 'BT' : 'TP') + '</span>' : '');
      return '<button type="button" class="km-node' + (n.isRadical || !n.isKanji ? ' is-component' : '') + (mastered ? ' is-mastered' : '') + (!f.matched.has(n.character) ? ' is-context' : '') + (route.has(n.character) ? ' is-route' : '') + (state.selected === n.character ? ' is-selected' : '') +
        '" style="left:' + item.x + 'px;top:' + item.y + 'px" data-character="' + esc(n.character) + '" title="' + esc(n.character + ' · ' + nameOf(n) + ' · ' + meaning(n)) + '" aria-label="' + esc(n.character + ', ' + nameOf(n) + ', ' + (n.level || 'chưa xếp cấp') + (mastered ? ', đã thành thạo' : '')) + '">' +
        '<span class="km-node-glyph' + (n.character.startsWith('CDP-') ? ' is-cdp' : '') + '" lang="ja">' + esc(n.character) + '</span>' +
        '<span class="km-node-meta"><strong>' + esc(nameOf(n)) + '</strong><small>' + esc(meaning(n)) + '</small>' +
        (badges ? '<span class="km-node-badges">' + badges + '</span>' : '') + '</span>' +
        (mastered ? '<span class="km-mastered-check">✓</span>' : '') + '</button>' +
        (item.children || hiddenByLevel || otherLinks ? '<button type="button" class="km-toggle" style="left:' + (item.x + geometry.width - 10) + 'px;top:' + (item.y + geometry.height / 2 - 11) + 'px" data-toggle="' + esc(n.character) + '"' + (hiddenByLevel ? ' data-all-levels="true"' : '') + (otherLinks ? ' data-reroot="true"' : '') + ' aria-expanded="' + (!hiddenByLevel && !otherLinks && !item.collapsed) + '" aria-label="' + esc(toggleLabel) + '" title="' + esc(toggleLabel + ' · ' + (otherLinks || totalChildren) + ' nhánh con') + '">' + (otherLinks ? '↗' : hiddenByLevel || item.collapsed ? '+' : '−') + '</button>' : '') +
        (item.children && item.collapsed ? '<span class="km-branch-count" style="left:' + (item.x + geometry.width - 6) + 'px;top:' + (item.y - 7) + 'px" title="' + item.children + ' nhánh con">+' + item.children + '</span>' : '');
    }).join('') + l.more.map(function (item) {
      return '<button type="button" class="km-more" style="left:' + item.x + 'px;top:' + item.y + 'px" data-more="' + esc(item.character) + '">+ ' + item.remaining + ' nút</button>';
    }).join('');
    $('empty').hidden = l.nodes.length > 0;
    if (!l.nodes.length) $('empty').innerHTML = '<span lang="ja">空</span><h2>Chưa có chữ phù hợp</h2><p>Thử đổi cấp độ, nhánh hoặc bộ lọc tiến độ.</p>';
    $('stat-kanji').textContent = f.matched.size;
    $('stat-mastered').textContent = Array.from(f.matched).filter(function (ch) { return state.mastered.has(ch); }).length;
    $('stat-context').textContent = (f.included.size - f.matched.size) + ' nút trung gian được giữ để thể hiện cấu tạo.';
    $('stat-levels').innerHTML = ['N5', 'N4', 'N3', 'N2', 'N1'].map(function (level) {
      var count = Array.from(f.included).filter(function (ch) { return state.graph.nodes.get(ch).level === level; }).length;
      return '<span><b class="km-level-badge km-level-' + level + '">' + level + '</b> ' + count + '</span>';
    }).join('');
    var expandLabel = state.expanded ? 'Thu gọn các nhánh' : 'Mở rộng các nhánh';
    $('expand').setAttribute('aria-label', expandLabel); $('expand').title = expandLabel; $('expand').setAttribute('aria-pressed', String(state.expanded));
    board.setAttribute('aria-busy', 'false');
    if (refit) requestAnimationFrame(home);
  }
  function cancelAnimation() { state.animations.forEach(function (animation) { animation.cancel(); }); state.animations = []; }
  function animateStrokes() {
    cancelAnimation();
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    detail.querySelectorAll('.km-stroke-wrap path').forEach(function (path, i) {
      var length = path.getTotalLength();
      state.animations.push(path.animate([{ strokeDasharray: length + ' ' + length, strokeDashoffset: length }, { strokeDasharray: length + ' ' + length, strokeDashoffset: 0 }],
        { duration: 340, delay: i * 250, fill: 'both', easing: 'ease-in-out' }));
    });
  }
  function componentDiagramHtml(character, context) {
    var diagram = model.componentDiagram(state.graph, character);
    if (!diagram) return '';
    var marker = 'km-diagram-arrow-' + (context || 'detail');
    return '<section class="km-detail-connections" aria-label="Sơ đồ liên kết chữ ' + esc(character) + '"><div class="km-detail-connections-heading"><h3>Sơ đồ liên kết</h3><button type="button" class="km-diagram-motion" data-diagram-motion aria-label="Tạm dừng chuyển động sơ đồ" aria-pressed="false"></button></div>' +
      '<div class="km-detail-graph" data-diagram-character="' + esc(character) + '" style="aspect-ratio:' + diagram.width + '/' + diagram.height + '">' +
      '<svg viewBox="0 0 ' + diagram.width + ' ' + diagram.height + '" aria-hidden="true"><defs><marker id="' + marker + '" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="6" markerHeight="6" orient="auto"><polygon points="0,0 10,5 0,10"/></marker></defs>' +
      diagram.edges.map(function (edge) { return '<path data-from="' + esc(edge.from) + '" data-to="' + esc(edge.to) + '" marker-end="url(#' + marker + ')" d="M' + edge.points[0].x + ',' + edge.points[0].y + ' L' + edge.points[1].x + ',' + edge.points[1].y + '"/>'; }).join('') + '</svg>' +
      diagram.nodes.map(function (item) { var n = item.node; return '<button type="button" class="km-detail-graph-node' + (item.selected ? ' is-current' : '') + (n.character.startsWith('CDP-') ? ' is-cdp' : '') + '" data-part="' + esc(n.character) + '" style="left:' + item.x / diagram.width * 100 + '%;top:' + item.y / diagram.height * 100 + '%" title="' + esc(n.character + ' · ' + nameOf(n)) + '" aria-label="Xem chữ ' + esc(n.character + ', ' + nameOf(n)) + '"' + (item.selected ? ' aria-current="true"' : '') + ' lang="ja">' + esc(n.character) + '</button>'; }).join('') +
      '</div></section>';
  }
  function detailHtml(n) {
    var positions = { left: 'trái', right: 'phải', top: 'trên', bottom: 'dưới', kamae: 'bao quanh', nyo: 'trái / dưới', tare: 'trái / trên' };
    var linked = (state.graph.outgoing.get(n.character) || []).map(function (r) { return state.graph.nodes.get(r.child); });
    var hasExtra = n.on.length || n.kun.length || n.memoryHint || n.meaningsEn.length;
    var path = model.ancestors(state.activeGraph, n.character).reverse().filter(function (ch) { return state.filtered.included.has(ch); });
    return '<div class="km-detail-top"><span>CHI TIẾT KANJI</span><button class="km-button" type="button" data-action="close" aria-label="Đóng chi tiết">×</button></div>' +
      '<nav class="km-route" aria-label="Đường từ gốc tới chữ đang chọn">' + path.map(function (ch, i) { return (i ? '<span aria-hidden="true">→</span>' : '') + '<button class="km-button" type="button" data-part="' + esc(ch) + '"' + (ch === n.character ? ' aria-current="true"' : '') + '>' + esc(ch) + '</button>'; }).join('') + '</nav>' +
      '<div class="km-detail-identity"><div class="km-detail-glyph' + (n.character.startsWith('CDP-') ? ' is-cdp' : '') + '" lang="ja">' + esc(n.character) + '</div><div><span class="km-identity-caption">' + esc(typeLabel(n)) + '</span><h2 id="km-detail-title">' + esc(nameOf(n)) + '</h2></div></div>' +
      '<p class="km-detail-meaning" id="km-detail-meaning" lang="vi">' + esc(meaning(n)) + '</p>' +
      '<div class="km-detail-badges"><span>' + esc(n.level || 'Chưa xếp cấp') + '</span><span>' + (n.strokeCount || '—') + ' nét</span></div>' +
      (n.radicalNameVi ? '<p class="km-radical-label">Bộ ' + esc(n.radicalNameVi) + ' · số ' + n.radicalNumber + (n.isRadicalVariant ? ' · dạng của <span lang="ja">' + esc(n.radicalBase) + '</span>' : '') + ((n.radicalAliases || []).length ? '<br>Tên khác: ' + n.radicalAliases.map(esc).join(' · ') : '') + '</p>' : '') +
      componentDiagramHtml(n.character) +
      (n.isKanji ? '<button type="button" class="km-button km-compare-open" data-action="compare">So sánh với chữ khác</button>' : '') +
      (linked.length ? '<h3>Chữ phát triển từ ' + esc(n.character) + ' · ' + linked.length + ' liên kết</h3><div class="km-linked">' + linked.slice(0, 12).map(function (child) {
        return '<button class="km-button" type="button" data-part="' + esc(child.character) + '" title="' + esc(nameOf(child)) + '">' + esc(child.character) + '</button>';
      }).join('') + '</div>' : '') +
      '<h3>Thành phần trực tiếp</h3><div class="km-parts">' + (n.components.length ? n.components.map(function (part) {
        return '<button type="button" class="km-button' + (part.character.startsWith('CDP-') ? ' is-cdp' : '') + '" data-part="' + esc(part.character) + '"><span lang="ja">' + esc(part.character) + '</span><small>' + (part.character.startsWith('CDP-') ? 'Mã thành phần KanjiVG' : esc(componentName(part))) + '</small><small>' + esc(positions[part.position] || '') + (part.partial ? ' · một phần' : '') + '</small></button>';
      }).join('') : '<p class="km-caption">Chưa tách thành phần nhỏ hơn trong nguồn dữ liệu.</p>') + '</div>' +
      (n.components.some(function (p) { return p.original && p.original !== p.character; }) ? '<p class="km-caption">' + n.components.filter(function (p) { return p.original && p.original !== p.character; }).map(function (p) { return esc(p.character) + ' là dạng của ' + esc(p.original); }).join(' · ') + '</p>' : '') +
      (hasExtra ? '<details class="km-detail-extra"><summary>Âm đọc &amp; nghĩa tham khảo</summary>' : '') +
      (n.on.length ? '<h3>Âm On</h3><div class="km-reading" lang="ja">' + n.on.map(esc).join(' · ') + '</div>' : '') +
      (n.kun.length ? '<h3>Âm Kun</h3><div class="km-reading" lang="ja">' + n.kun.map(esc).join(' · ') + '</div>' : '') +
      (n.memoryHint ? '<h3>Gợi ý ghi nhớ</h3><p class="km-detail-note">' + esc(n.memoryHint) + '</p>' : '') +
      (n.meaningsEn.length ? '<h3>Nghĩa gốc · tiếng Anh</h3><p class="km-detail-note" lang="en">' + n.meaningsEn.map(esc).join('; ') + '</p>' : '') +
      (hasExtra ? '</details>' : '') +
      (n.strokes.length ? '<h3>Thứ tự nét · KanjiVG</h3><div class="km-stroke-wrap"><svg viewBox="0 0 109 109" role="img" aria-label="Thứ tự nét chữ ' + esc(n.character) + '">' + n.strokes.map(function (d) { return '<path d="' + esc(d) + '"/>'; }).join('') + '</svg><div><button type="button" class="km-text-button" data-action="replay">↻ Phát lại nét viết</button></div></div>' : '') +
      (n.isKanji ? '<div class="km-detail-actions"><button type="button" class="km-button" id="km-master" data-action="master" aria-pressed="' + state.mastered.has(n.character) + '">' + (state.mastered.has(n.character) ? '✓ Đã thành thạo' : 'Đánh dấu thành thạo') + '</button>' +
        '<button type="button" class="km-button km-primary" data-action="notebook">' + (notebook.has(n.character) ? '✓ Đã lưu · Chọn sổ' : 'Lưu vào sổ tay') + '</button>' +
        '<a class="km-button" href="/dictionary/index.html?q=' + encodeURIComponent(n.character) + '" target="_blank" rel="noopener">Tra từ điển đầy đủ ↗</a></div><div id="km-related"></div>' : '<p class="km-caption">Đây là thành phần cấu tạo, không phải thẻ Kanji để lưu tiến độ.</p>');
  }
  async function select(character) {
    var n = state.graph.nodes.get(character); if (!n) return;
    stopPronunciation(); stopDiagramMotion(); state.selected = character; var version = ++state.detailVersion; cancelAnimation();
    render(false);
    detail.innerHTML = detailHtml(n); detail.hidden = false; detail.scrollTop = 0; $('workspace').classList.add('has-detail');
    var diagramElement = detail.querySelector('.km-detail-graph');
    if (diagramElement && window.KanjiMapDiagram) diagramMotion = window.KanjiMapDiagram.mount(diagramElement, model.componentDiagram(state.graph, character));
    else if (diagramElement) detail.querySelector('[data-diagram-motion]').hidden = true;
    $('filters').classList.remove('is-open'); $('filters-toggle').setAttribute('aria-expanded', 'false');
    requestAnimationFrame(function () { if (state.selected === character) animateStrokes(); });
    if (n.isKanji) {
      try {
        var data = await request('/api/dictionary/kanji/' + encodeURIComponent(character));
        if (version !== state.detailVersion || state.selected !== character) return;
        // Legacy dictionary seeds put English glosses in meaning_vi. Keep the
        // bundled, attributed Vietnamese translation and use this API for words only.
        var related = (data.relatedVocabulary || []).slice(0, 5);
        if (related.length) $('related').innerHTML = '<h3>Từ vựng liên quan</h3>' + related.map(function (word) {
          return '<div class="km-vocabulary-row"><a class="km-vocabulary" href="/dictionary/index.html?q=' + encodeURIComponent(word.expression) + '" target="_blank" rel="noopener"><span class="km-vocabulary-expression" lang="ja">' + esc(word.expression) + '</span><small><span lang="ja">' + esc(word.reading || '') + '</span> · <span class="km-vocabulary-meaning">' + esc(word.meaning || '') + '</span></small></a>' +
            '<button type="button" class="km-vocabulary-speak" data-action="pronounce" data-reading="' + esc(word.reading || '') + '" data-expression="' + esc(word.expression) + '" aria-label="Nghe phát âm ' + esc(word.expression) + '" title="Nghe phát âm ' + esc(word.expression) + '" aria-pressed="false"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/></svg></button></div>';
        }).join('') + '<p id="km-pronunciation-status" class="km-caption" role="status" aria-live="polite"></p>';
      } catch (error) { /* Bundled readings, meanings and strokes remain fully usable without a DB entry. */ }
    }
  }
  function closeDetail() { stopPronunciation(); stopDiagramMotion(); state.selected = null; state.detailVersion++; cancelAnimation(); detail.hidden = true; $('workspace').classList.remove('has-detail'); render(false); }
  var partPositions = { left: 'trái', right: 'phải', top: 'trên', bottom: 'dưới', kamae: 'bao quanh', nyo: 'trái / dưới', tare: 'trái / trên' };
  function componentName(part) {
    var node = state.graph.catalog.get(part.character), original = state.graph.catalog.get(part.original);
    if (node && node.isRadicalVariant && node.radicalBase === part.original) return node.radicalNameVi;
    if (original && original.isRadical && part.original !== part.character) return original.radicalNameVi + ' (dạng ' + part.character + ')';
    return node && node.radicalNameVi || '';
  }
  function componentChips(parts) {
    return parts.length ? parts.map(function (part) { var name = componentName(part); return '<span class="km-component-chip"><b lang="ja">' + esc(part.character) + '</b>' + (name ? '<small>' + esc(name) + '</small>' : '') + '<small>' + (part.count ? '×' + part.count : esc(partPositions[part.position] || '')) + '</small></span>'; }).join('') : '<span class="km-caption">Không có</span>';
  }
  function compareWith(character) {
    stopCompareDiagrams();
    var left = state.graph.catalog.get(state.compareBase), right = state.graph.catalog.get(character.trim());
    $('compare-error').hidden = true;
    if (!right || !right.isKanji || right.character === left.character) {
      $('compare-result').innerHTML = '';
      $('compare-error').textContent = 'Nhập một chữ Kanji khác có trong dữ liệu.'; $('compare-error').hidden = false; return;
    }
    $('compare-input').value = right.character;
    var diff = model.compareComponents(left, right);
    $('compare-result').innerHTML = '<div class="km-compare-cards km-compare-kanji-cards">' + [left, right].map(function (node, i) {
      return '<article><div class="km-compare-glyph" lang="ja">' + esc(node.character) + '</div><h3>' + esc(node.hanViet || node.character) + '</h3><p>' + esc(meaning(node)) + '</p><strong>' + node.strokeCount + ' nét · ' + esc(node.level || 'Chưa xếp cấp') + '</strong>' + componentDiagramHtml(node.character, i ? 'compare-right' : 'compare-left') + '</article>';
    }).join('') + '</div><div class="km-compare-diff"><h3>Thành phần chung</h3><div class="km-component-list is-shared">' + componentChips(diff.shared) + '</div><div class="km-compare-cards"><section><h3>Phần riêng của ' + esc(left.character) + '</h3><div class="km-component-list">' + componentChips(diff.leftOnly) + '</div></section><section><h3>Phần riêng của ' + esc(right.character) + '</h3><div class="km-component-list">' + componentChips(diff.rightOnly) + '</div></section></div></div>';
    $('compare-result').querySelectorAll('.km-detail-graph').forEach(function (element) {
      if (window.KanjiMapDiagram) compareDiagramMotions.push(window.KanjiMapDiagram.mount(element, model.componentDiagram(state.graph, element.dataset.diagramCharacter)));
      else element.closest('section').querySelector('[data-diagram-motion]').hidden = true;
    });
  }
  function openCompare() {
    stopCompareDiagrams();
    state.compareBase = state.selected; $('compare-input').value = ''; $('compare-result').innerHTML = ''; $('compare-error').hidden = true;
    var suggestions = model.similarKanji(state.graph, state.compareBase, 5);
    $('compare-suggestions').innerHTML = '<p>Đang chọn: <b lang="ja">' + esc(state.compareBase) + '</b></p>' + (suggestions.length ? '<span class="km-caption">Có chung thành phần: </span>' + suggestions.map(function (node) { return '<button type="button" class="km-button" data-compare-target="' + esc(node.character) + '">' + esc(node.character) + '</button>'; }).join('') : '');
    $('compare-dialog').showModal();
  }
  function studyRender() {
    var session = state.study, content = $('study-content');
    if (session.index >= session.questions.length) {
      content.innerHTML = '<div class="km-study-summary"><strong>' + session.correct + '/' + session.questions.length + '</strong><h3>Đã hoàn thành lượt luyện</h3><p>Điểm của lượt này; trạng thái thành thạo vẫn do bạn tự đánh dấu.</p></div>' + (session.wrong.length ? '<p>Chữ cần xem lại: <span lang="ja">' + session.wrong.map(esc).join(' · ') + '</span></p><button type="button" class="km-button" data-study-notebook>Lưu chữ sai vào sổ tay</button>' : '') + '<button type="button" class="km-button km-primary" data-study-restart>Luyện lượt khác</button>';
      content.querySelector('[data-study-restart]').focus(); return;
    }
    var q = session.questions[session.index];
    content.innerHTML = '<div class="km-study-progress"><span>Câu ' + (session.index + 1) + '/' + session.questions.length + '</span><span>Đúng ' + session.correct + ' câu</span></div><progress max="' + session.questions.length + '" value="' + session.index + '" aria-label="Tiến độ lượt luyện"></progress><h3>Chữ nào có các thành phần này?</h3><div class="km-study-parts">' + componentChips(q.components) + '</div><p class="km-caption">Gợi ý: ' + q.strokeCount + ' nét. Vị trí được ghi khi có trong nguồn.</p><div class="km-study-answers">' + q.choices.map(function (ch) { return '<button type="button" class="km-button" data-answer="' + esc(ch) + '" aria-label="Chọn ' + esc(ch) + '">' + esc(ch) + '</button>'; }).join('') + '</div><div id="km-study-feedback" role="status"></div><button id="km-study-next" type="button" class="km-button km-primary" data-study-next hidden>' + (session.index + 1 === session.questions.length ? 'Xem kết quả' : 'Câu tiếp theo') + '</button>';
    content.querySelector('[data-answer]').focus();
  }
  function startStudy() {
    if (!state.filtered) return;
    var questions = model.studyQuestions(state.graph, state.filtered.matched, 5);
    state.study = { questions: questions, index: 0, correct: 0, wrong: [], answered: false };
    $('study-scope').textContent = scopeLabel() + ' · Dựa trên các chữ phù hợp bộ lọc. Kết quả chỉ tính trong lượt này.';
    if (!$('study-dialog').open) $('study-dialog').showModal();
    if (questions.length) studyRender();
    else $('study-content').innerHTML = '<p>Nhánh hoặc bộ lọc này chưa có đủ hai chữ với cấu tạo phân biệt được để luyện. Hãy chọn nhánh rộng hơn hoặc cấp độ khác.</p>';
  }
  function answerStudy(character) {
    var session = state.study; if (!session || session.answered) return;
    var q = session.questions[session.index]; if (!q.choices.includes(character)) return;
    session.answered = true;
    var correct = character === q.character, node = state.graph.catalog.get(q.character);
    if (correct) session.correct++; else session.wrong.push(q.character);
    $('study-content').querySelector('.km-study-progress span:last-child').textContent = 'Đúng ' + session.correct + ' câu';
    $('study-content').querySelector('progress').value = session.index + 1;
    $('study-content').querySelectorAll('[data-answer]').forEach(function (button) {
      button.disabled = true;
      if (button.dataset.answer === q.character) button.classList.add('is-correct');
      else if (button.dataset.answer === character) button.classList.add('is-wrong');
    });
    $('study-feedback').innerHTML = '<p class="' + (correct ? 'km-answer-correct' : 'km-answer-wrong') + '">' + (correct ? 'Đúng rồi! ' : 'Chưa đúng. Đáp án: ') + '<b lang="ja">' + esc(q.character) + '</b> · ' + esc(node.hanViet || '') + ' · ' + esc(meaning(node)) + '</p>';
    $('study-next').hidden = false; $('study-next').focus();
  }
  function focus(character) {
    resetHint();
    var graph = activeGraph();
    if (!graph.nodes.has(character)) {
      var originalPath = model.ancestors(state.graph, character); chooseBranch(originalPath[originalPath.length - 1]); graph = activeGraph();
    }
    var path = model.ancestors(graph, character);
    $('level').value = ''; $('status').value = '';
    path.forEach(function (ch) {
      state.collapsed.delete(ch);
      var parent = graph.nodes.get(ch).parent, siblings = graph.children.get(parent) || [], position = siblings.findIndex(function (n) { return n.character === ch; });
      if (parent) state.limits.set(parent, Math.max(state.limits.get(parent) || 12, position + 1));
    });
    $('search-results').hidden = true; $('filters').classList.remove('is-open'); $('filters-toggle').setAttribute('aria-expanded', 'false');
    select(character);
    requestAnimationFrame(function () { center(character); });
  }
  function searchResults() {
    if (!state.graph) return [];
    var rows = model.search(state.graph, $('search').value);
    $('search-results').hidden = !$('search').value.trim();
    $('search-results').innerHTML = rows.length ? rows.map(function (n) {
      return '<button type="button" data-search-character="' + esc(n.character) + '"><b lang="ja">' + esc(n.character) + '</b><span>' + esc(nameOf(n)) + '<small>' + esc(meaning(n)) + '</small></span></button>';
    }).join('') : '<p class="km-caption">Không tìm thấy. Thử một chữ Kanji hoặc Hán Việt.</p>';
    return rows;
  }
  async function loadProgress() {
    if (!loggedIn()) return;
    $('progress-state').textContent = 'Đang tải tiến độ của bạn…';
    try {
      state.mastered = new Set(await request('/api/kanji-map/progress')); state.progressReady = true;
      $('progress-state').textContent = 'Tiến độ được lưu theo tài khoản của bạn.'; render(false);
    } catch (error) { $('progress-state').textContent = 'Chưa tải được tiến độ. Bạn vẫn có thể khám phá lược đồ.'; }
  }
  async function master() {
    if (!loggedIn()) { notice('Đăng nhập để lưu tiến độ và tiếp tục học trên thiết bị khác.', true); return; }
    var character = state.selected, button = $('master'); if (!button) return;
    var mastered = !state.mastered.has(character); button.disabled = true;
    try {
      await request('/api/kanji-map/progress/' + encodeURIComponent(character), { method: 'PUT', headers: Object.assign({}, headers(), { 'Content-Type': 'application/json' }), body: JSON.stringify({ mastered: mastered }) });
      if (mastered) state.mastered.add(character); else state.mastered.delete(character);
      notice(mastered ? 'Đã lưu chữ ' + character + ' là thành thạo.' : 'Đã đưa chữ ' + character + ' về danh sách cần học.');
      if (state.selected === character) { button.textContent = mastered ? '✓ Đã thành thạo' : 'Đánh dấu thành thạo'; button.setAttribute('aria-pressed', String(mastered)); }
      $('progress-state').textContent = 'Tiến độ được lưu theo tài khoản của bạn.'; render(false);
    } catch (error) { notice('Chưa lưu được tiến độ: ' + error.message); }
    finally { button.disabled = false; }
  }
  // Board gestures are bound once; rendering only replaces nodes and connectors.
  function pointerPoint(event) { var r = board.getBoundingClientRect(); return { x: event.clientX - r.left, y: event.clientY - r.top }; }
  function resetGesture() {
    var points = Array.from(pointers.values());
    gesture = points.length > 1 ? { distance: Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y),
      x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2 } : points[0] || null;
  }
  board.addEventListener('pointerdown', function (event) {
    if (event.target.closest('button') || (event.pointerType === 'mouse' && event.button !== 0)) return;
    pointers.set(event.pointerId, pointerPoint(event)); board.setPointerCapture(event.pointerId); board.classList.add('is-dragging'); resetGesture();
  });
  board.addEventListener('pointermove', function (event) {
    if (!pointers.has(event.pointerId) || !gesture) return;
    pointers.set(event.pointerId, pointerPoint(event)); var points = Array.from(pointers.values());
    if (points.length > 1) {
      var distance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y), x = (points[0].x + points[1].x) / 2, y = (points[0].y + points[1].y) / 2;
      if (gesture.distance > 0) zoom(distance / gesture.distance, gesture.x, gesture.y);
      state.view.x += x - gesture.x; state.view.y += y - gesture.y;
    } else { state.view.x += points[0].x - gesture.x; state.view.y += points[0].y - gesture.y; }
    resetGesture(); transform();
  });
  function release(event) { pointers.delete(event.pointerId); resetGesture(); if (!pointers.size) board.classList.remove('is-dragging'); }
  board.addEventListener('pointerup', release); board.addEventListener('pointercancel', release);
  board.addEventListener('wheel', function (event) {
    event.preventDefault();
    var p = pointerPoint(event);
    var delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? board.clientHeight : 1);
    // Faster wheel zoom, with a bounded step for high-resolution mice and trackpads.
    zoom(Math.exp(-Math.max(-120, Math.min(120, delta)) * .003), p.x, p.y);
  }, { passive: false });
  board.addEventListener('keydown', function (event) {
    if (event.target !== board) return;
    var direction = { ArrowLeft: [50, 0], ArrowRight: [-50, 0], ArrowUp: [0, 50], ArrowDown: [0, -50] }[event.key];
    if (direction) { event.preventDefault(); state.view.x += direction[0]; state.view.y += direction[1]; transform(); }
    if (event.key === '+' || event.key === '=') { event.preventDefault(); zoom(1.2); }
    if (event.key === '-') { event.preventDefault(); zoom(1 / 1.2); }
  });
  $('nodes').addEventListener('click', function (event) {
    var button = event.target.closest('button'); if (!button) return;
    if (button.dataset.character) select(button.dataset.character);
    if (button.dataset.toggle) {
      var ch = button.dataset.toggle;
      if (button.dataset.reroot) { chooseBranch(ch); $('level').value = ''; $('status').value = ''; closeDetail(); initialBranches(); render(true); return; }
      anchoredRender(ch, function () {
        if (button.dataset.allLevels) { if (!$('branch').value) chooseBranch(ch); $('level').value = ''; state.collapsed.delete(ch); $('hint').textContent = 'Đã mở đủ cấp độ từ ' + ch + ' · Chọn lại N5–N1 trong Bộ lọc khi cần'; }
        else if (state.collapsed.has(ch)) state.collapsed.delete(ch); else state.collapsed.add(ch);
      });
    }
    if (button.dataset.more) { var parent = button.dataset.more; anchoredRender(parent, function () { state.limits.set(parent, (state.limits.get(parent) || 12) + 12); }); }
  });
  detail.addEventListener('click', function (event) {
    var button = event.target.closest('button'); if (!button) return;
    if (button.dataset.action === 'pronounce') { speakVocabulary(button); return; }
    if (button.dataset.part) { focus(button.dataset.part); return; }
    if (button.dataset.action === 'close') closeDetail();
    if (button.dataset.action === 'compare') openCompare();
    if (button.dataset.action === 'replay') animateStrokes();
    if (button.dataset.action === 'master') master();
    if (button.dataset.action === 'notebook' && state.selected) notebook.save([state.selected]);
  });
  window.addEventListener('pagehide', stopPronunciation);
  window.addEventListener('pagehide', stopDiagramMotion);
  window.addEventListener('pagehide', stopCompareDiagrams);
  if (window.speechSynthesis) window.speechSynthesis.getVoices();
  $('search').addEventListener('input', searchResults);
  $('search-form').addEventListener('submit', function (event) { event.preventDefault(); var rows = searchResults(); if (rows.length) focus(rows[0].character); });
  $('search-results').addEventListener('click', function (event) { var button = event.target.closest('[data-search-character]'); if (button) focus(button.dataset.searchCharacter); });
  ['level', 'branch', 'status'].forEach(function (id) { $(id).addEventListener('change', function () {
    resetHint();
    if (id === 'level') { chooseBranch(''); refreshBranches(); }
    closeDetail(); if (id === 'branch' || id === 'level') initialBranches(); render(true);
  }); });
  $('clear-filters').addEventListener('click', function () { resetHint(); $('level').value = ''; $('branch').value = ''; $('status').value = ''; $('search').value = ''; $('search-results').hidden = true; closeDetail(); initialBranches(); render(true); });
  $('filters-toggle').addEventListener('click', function () { var open = $('filters').classList.toggle('is-open'); if (open) closeDetail(); $('filters-toggle').setAttribute('aria-expanded', String(open)); });
  $('filters-close').addEventListener('click', function () { $('filters').classList.remove('is-open'); $('filters-toggle').setAttribute('aria-expanded', 'false'); $('filters-toggle').focus(); });
  $('theme').addEventListener('click', function () { var light = $('workspace').classList.toggle('is-map-light'); $('theme').textContent = light ? 'Sáng' : 'Tối'; $('theme').setAttribute('aria-pressed', String(!light)); });
  $('home').addEventListener('click', home);
  $('zoom-in').addEventListener('click', function () { zoom(1.2); }); $('zoom-out').addEventListener('click', function () { zoom(1 / 1.2); }); $('fit').addEventListener('click', fit);
  $('expand').addEventListener('click', function () {
    if (!state.graph) return;
    state.expanded = !state.expanded; state.collapsed.clear(); state.limits.clear();
    if (!state.expanded) activeGraph().children.forEach(function (_, ch) { state.collapsed.add(ch); });
    else activeGraph().children.forEach(function (_, ch) { state.limits.set(ch, 10000); });
    render(false); fit();
  });
  $('fullscreen').addEventListener('click', async function () { try { if (document.fullscreenElement) await document.exitFullscreen(); else await $('workspace').requestFullscreen(); } catch (error) { notice('Trình duyệt hiện tại chưa hỗ trợ toàn màn hình.'); } });
  $('compare-close').addEventListener('click', function () { $('compare-dialog').close(); });
  $('compare-dialog').addEventListener('close', stopCompareDiagrams);
  $('compare-result').addEventListener('click', function (event) {
    var button = event.target.closest('button[data-part]'); if (!button) return;
    stopCompareDiagrams(); $('compare-dialog').close(); focus(button.dataset.part);
  });
  $('compare-form').addEventListener('submit', function (event) { event.preventDefault(); compareWith($('compare-input').value); });
  $('compare-suggestions').addEventListener('click', function (event) { var button = event.target.closest('[data-compare-target]'); if (button) compareWith(button.dataset.compareTarget); });
  $('study-close').addEventListener('click', function () { $('study-dialog').close(); });
  $('study-content').addEventListener('click', async function (event) {
    var button = event.target.closest('button'); if (!button) return;
    if (button.dataset.answer) answerStudy(button.dataset.answer);
    if (button.hasAttribute('data-study-next') && state.study.answered) { state.study.index++; state.study.answered = false; studyRender(); }
    if (button.hasAttribute('data-study-restart')) startStudy();
    if (button.hasAttribute('data-study-notebook')) await notebook.save(state.study.wrong);
  });
  document.addEventListener('keydown', function (event) { if (event.key === 'Escape' && !document.querySelector('.km-dialog[open]')) { closeDetail(); $('filters').classList.remove('is-open'); $('filters-toggle').setAttribute('aria-expanded', 'false'); } });
  new ResizeObserver(function () { if (state.selected) center(state.selected); else home(); }).observe(board);
  async function load() {
    try {
      state.data = await request('/api/dictionary/kanji-map/catalog'); state.graph = model.index(state.data);
      refreshBranches();
      chooseBranch('一'); $('level').value = ''; initialBranches();
      render(true); if ($('search').value.trim()) searchResults(); await loadProgress();
      try { await notebook.load(); } catch (_) { /* The picker retries loading on demand. */ }
      var initial = new URLSearchParams(location.search).get('kanji'); if (initial && state.graph.nodes.has(initial)) { $('search').value = initial; focus(initial); }
    } catch (error) {
      board.setAttribute('aria-busy', 'false'); $('empty').hidden = false;
      $('empty').innerHTML = '<span lang="ja">木</span><h2>Chưa tải được lược đồ</h2><p>' + esc(error.message) + '</p><button type="button" class="km-button" id="km-retry">Thử lại</button>';
      $('retry').addEventListener('click', load, { once: true });
    }
  }
  load();
})();
