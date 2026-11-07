(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.KanjiMapModel = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  function normal(value) { return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[đĐ]/g, 'd').toLowerCase().trim(); }
  function displayName(node) { return node.radicalNameVi || node.hanViet || (node.isRadical ? 'Bộ thủ' : node.isKanji ? 'Kanji' : 'Thành phần'); }
  function vietnameseMeaning(node) { return node.meaningVi || (node.radicalNameVi && !node.isKanji ? 'Bộ ' + node.radicalNameVi + (node.isRadicalVariant ? ' · dạng của ' + node.radicalBase : '') : node.isKanji ? 'Chưa có bản dịch tiếng Việt' : 'Thành phần cấu tạo'); }
  function order(a, b) {
    return (Number(b.level && b.level.slice(1)) || 0) - (Number(a.level && a.level.slice(1)) || 0)
      || (a.strokeCount || 0) - (b.strokeCount || 0) || a.character.codePointAt(0) - b.character.codePointAt(0);
  }
  function index(data) {
    var catalog = new Map(data.nodes.map(function (node) { return [node.character, node]; }));
    var outgoing = new Map(), incoming = new Map(), relations = [];
    function add(parent, child, kind) {
      if (parent === child || !catalog.has(parent) || !catalog.has(child)) return;
      if (!outgoing.has(parent)) outgoing.set(parent, []);
      var duplicate = outgoing.get(parent).find(function (r) { return r.child === child; });
      if (duplicate) return;
      var relation = { parent: parent, child: child, kind: kind };
      outgoing.get(parent).push(relation);
      if (!incoming.has(child)) incoming.set(child, []);
      incoming.get(child).push(relation); relations.push(relation);
    }
    data.nodes.forEach(function (node) {
      // Keep every complete physical component, rather than discarding all but one.
      (node.components || []).forEach(function (part) {
        var component = catalog.get(part.character);
        if (component && !part.partial && (component.strokeCount || 999) < (node.strokeCount || 0)) add(part.character, node.character, 'component');
      });
      if (node.parent) add(node.parent, node.character, node.parentRelation || 'component');
    });
    outgoing.forEach(function (list) { list.sort(function (a, b) {
      return order(catalog.get(a.child), catalog.get(b.child));
    }); });
    var source = { catalog: catalog, outgoing: outgoing, incoming: incoming, relations: relations };
    return project(source);
  }
  function reachable(graph, character) {
    var result = new Set(), pending = [character];
    while (pending.length) {
      var ch = pending.pop(); if (result.has(ch) || !graph.catalog.has(ch)) continue;
      result.add(ch);
      (graph.outgoing.get(ch) || []).forEach(function (r) { pending.push(r.child); });
    }
    return result;
  }
  // A readable projection of the full graph. Re-rooting recovers relations that
  // would otherwise be hidden by a single, globally assigned display parent.
  function project(graph, root) {
    var included = root ? reachable(graph, root) : new Set(graph.catalog.keys());
    var nodes = new Map(), children = new Map(), depths = new Map(), visiting = new Set();
    function resolve(ch) {
      if (depths.has(ch)) return depths.get(ch);
      if (visiting.has(ch)) throw new Error('Cycle in Kanji relations: ' + ch);
      visiting.add(ch);
      var choices = ch === root ? [] : (graph.incoming.get(ch) || []).filter(function (r) { return included.has(r.parent); });
      var best = null, score = -1;
      choices.forEach(function (r) {
        var depth = resolve(r.parent), parent = graph.catalog.get(r.parent);
        var rank = depth * 10000 + (parent.isRadical ? 1000 : 0) + (parent.strokeCount || 0);
        if (rank > score) { score = rank; best = r; }
      });
      var node = Object.assign({}, graph.catalog.get(ch), { parent: best ? best.parent : null, parentRelation: best ? best.kind : null });
      nodes.set(ch, node); var depth = best ? depths.get(best.parent) + 1 : 0; depths.set(ch, depth); visiting.delete(ch); return depth;
    }
    included.forEach(resolve);
    nodes.forEach(function (node) {
      if (!node.parent) return;
      if (!children.has(node.parent)) children.set(node.parent, []);
      children.get(node.parent).push(node);
    });
    children.forEach(function (list) { list.sort(order); });
    var roots = Array.from(nodes.values()).filter(function (node) { return !node.parent; }).sort(order);
    return Object.assign({}, graph, { nodes: nodes, children: children, roots: roots, depths: depths, root: root || null });
  }
  function ancestors(graph, character) {
    var result = [], seen = new Set(), node = graph.nodes.get(character);
    while (node && !seen.has(node.character)) {
      seen.add(node.character); result.push(node.character); node = graph.nodes.get(node.parent);
    }
    return result;
  }
  function descendants(graph, character) {
    var result = new Set(), pending = [character];
    while (pending.length) {
      var current = pending.pop();
      if (result.has(current)) continue;
      result.add(current);
      (graph.children.get(current) || []).forEach(function (node) { pending.push(node.character); });
    }
    return result;
  }
  function entryRoots(graph, level) {
    function matches(node) { return node.isKanji && (level === 'UNCLASSIFIED' ? !node.level : node.level === level); }
    return Array.from(graph.nodes.values()).filter(function (node) {
      return matches(node) && !ancestors(graph, node.character).slice(1).some(function (ch) { return matches(graph.nodes.get(ch)); });
    }).sort(order);
  }
  function filter(graph, options) {
    var branch = options.branch ? descendants(graph, options.branch) : null;
    var matched = new Set(), included = new Set(), mastered = options.mastered || new Set();
    graph.nodes.forEach(function (node) {
      if (!node.isKanji || (branch && !branch.has(node.character))) return;
      if (options.level === 'UNCLASSIFIED' ? !!node.level : (options.level && node.level !== options.level)) return;
      if (options.status === 'MASTERED' && !mastered.has(node.character)) return;
      if (options.status === 'NEW' && mastered.has(node.character)) return;
      matched.add(node.character);
      ancestors(graph, node.character).forEach(function (ch) { if (!branch || branch.has(ch)) included.add(ch); });
    });
    var roots = options.branch && included.has(options.branch) ? [graph.nodes.get(options.branch)] : graph.roots.filter(function (node) { return included.has(node.character); });
    if (options.levelRoots && options.level && !options.branch) {
      roots = entryRoots(graph, options.level).filter(function (node) { return included.has(node.character); });
      // Remove only the leading ancestors above each level's first kanji.
      // Keep actual intermediate components below it; never invent new edges.
      var retained = new Set();
      function retain(ch) {
        if (!included.has(ch) || retained.has(ch)) return;
        retained.add(ch); (graph.children.get(ch) || []).forEach(function (node) { retain(node.character); });
      }
      roots.forEach(function (node) { retain(node.character); }); included = retained;
    }
    return { matched: matched, included: included,
      roots: roots };
  }
  function search(graph, query, limit) {
    var q = normal(query);
    if (!q) return [];
    return Array.from(graph.nodes.values()).map(function (node) {
      var names = [node.hanViet, node.radicalNameVi].concat(node.radicalAliases || []);
      var haystack = normal(names.concat([node.meaningVi], node.meaningsEn || [], node.on || [], node.kun || []).join(' '));
      return { node: node, rank: node.character === query.trim() ? 0 : String(node.hanViet || '').toLowerCase() === query.trim().toLowerCase() ? 1 : names.some(function (name) { return name && normal(name) === q; }) ? 2 : haystack.includes(q) ? 3 : 99 };
    }).filter(function (item) { return item.rank < 99; }).sort(function (a, b) { return a.rank - b.rank || order(a.node, b.node); })
      .slice(0, limit || 12).map(function (item) { return item.node; });
  }
  function componentCounts(node) {
    var counts = new Map();
    (node.components || []).filter(function (part) { return !part.partial; }).forEach(function (part) {
      counts.set(part.character, (counts.get(part.character) || 0) + 1);
    });
    return counts;
  }
  function compareComponents(left, right) {
    var a = componentCounts(left), b = componentCounts(right), shared = [], leftOnly = [], rightOnly = [];
    new Set(Array.from(a.keys()).concat(Array.from(b.keys()))).forEach(function (ch) {
      var common = Math.min(a.get(ch) || 0, b.get(ch) || 0);
      if (common) shared.push({ character: ch, count: common });
      if ((a.get(ch) || 0) > common) leftOnly.push({ character: ch, count: a.get(ch) - common });
      if ((b.get(ch) || 0) > common) rightOnly.push({ character: ch, count: b.get(ch) - common });
    });
    return { shared: shared, leftOnly: leftOnly, rightOnly: rightOnly };
  }
  function similarKanji(graph, character, limit) {
    var base = graph.catalog.get(character); if (!base) return [];
    return Array.from(graph.catalog.values()).filter(function (node) { return node.isKanji && node.character !== character; }).map(function (node) {
      return { node: node, common: compareComponents(base, node).shared.reduce(function (sum, part) { return sum + part.count; }, 0) };
    }).filter(function (item) { return item.common > 0; }).sort(function (a, b) {
      return b.common - a.common || Math.abs(a.node.strokeCount - base.strokeCount) - Math.abs(b.node.strokeCount - base.strokeCount) || order(a.node, b.node);
    }).slice(0, limit || 5).map(function (item) { return item.node; });
  }
  function studyQuestions(graph, characters, limit, random) {
    random = random || Math.random;
    function shuffle(items) {
      var result = items.slice();
      for (var i = result.length - 1; i > 0; i--) { var j = Math.floor(random() * (i + 1)), temp = result[i]; result[i] = result[j]; result[j] = temp; }
      return result;
    }
    function signature(node) {
      var parts = node.components || [];
      if (!node.isKanji || parts.length < 2 || parts.some(function (p) { return p.partial || p.character === node.character || p.character.startsWith('CDP-'); })) return null;
      return parts.map(function (p) { return p.character + ':' + (p.position || ''); }).sort().join('|');
    }
    var counts = new Map();
    graph.catalog.forEach(function (node) { var key = signature(node); if (key) counts.set(key, (counts.get(key) || 0) + 1); });
    var pool = Array.from(characters).map(function (ch) { return graph.catalog.get(ch); }).filter(function (node) {
      return node && counts.get(signature(node)) === 1;
    });
    if (pool.length < 2) return [];
    return shuffle(pool).slice(0, limit || 5).map(function (node) {
      var alternatives = shuffle(pool.filter(function (other) { return other.character !== node.character; }));
      alternatives.sort(function (a, b) { return compareComponents(node, b).shared.length - compareComponents(node, a).shared.length; });
      return { character: node.character, components: node.components, strokeCount: node.strokeCount,
        choices: shuffle([node.character].concat(alternatives.slice(0, 3).map(function (other) { return other.character; }))) };
    });
  }
  var geometry = { width: 164, height: 76, column: 246, row: 104 };
  function componentDiagram(graph, character) {
    var selected = graph.catalog.get(character);
    if (!selected) return null;
    var included = new Set([character]);
    (graph.incoming.get(character) || []).forEach(function (r) { included.add(r.parent); });
    if (included.size < 2) return null;
    var parts = Array.from(included).filter(function (ch) { return ch !== character; })
      .map(function (ch) { return graph.catalog.get(ch); }).sort(function (a, b) {
        return (graph.depths.get(a.character) || 0) - (graph.depths.get(b.character) || 0) || order(a, b);
      });
    var width = 280, height = Math.max(160, (parts.length - 1) * 78 + 80);
    var nodes = parts.map(function (node, i) { return { node: node, x: 48, y: 40 + i * 78, selected: false }; });
    if (parts.length === 1) nodes[0].y = height / 2;
    nodes.push({ node: selected, x: 232, y: height / 2, selected: true });
    var positions = new Map(nodes.map(function (n) { return [n.node.character, n]; }));
    var edges = graph.relations.filter(function (r) { return included.has(r.parent) && included.has(r.child); }).map(function (r) {
      var a = positions.get(r.parent), b = positions.get(r.child);
      var dx = b.x - a.x, dy = b.y - a.y, distance = Math.hypot(dx, dy);
      return { from: r.parent, to: r.child, points: [
        { x: a.x + dx / distance * 26, y: a.y + dy / distance * 26 },
        { x: b.x - dx / distance * 28, y: b.y - dy / distance * 28 }
      ] };
    });
    return { nodes: nodes, edges: edges, width: width, height: height };
  }
  function layout(graph, filtered, collapsed, limits) {
    var positioned = [], edges = [], more = [], y = 24;
    function visit(node, depth, parent) {
      var start = y, children = (graph.children.get(node.character) || []).filter(function (n) { return filtered.included.has(n.character); });
      var shown = collapsed.has(node.character) ? [] : children.slice(0, limits.get(node.character) || 12);
      var item = { node: node, x: 24 + depth * geometry.column, y: start, children: children.length, collapsed: collapsed.has(node.character) };
      positioned.push(item);
      if (parent) edges.push({ from: parent, to: item, variant: node.parentRelation === 'variant' });
      shown.forEach(function (child) { visit(child, depth + 1, item); });
      if (shown.length && shown.length < children.length) {
        more.push({ character: node.character, x: 24 + (depth + 1) * geometry.column, y: y, remaining: children.length - shown.length }); y += geometry.row;
      }
      if (shown.length) item.y = (start + y - geometry.row) / 2;
      else y += geometry.row;
    }
    filtered.roots.forEach(function (node) { visit(node, 0, null); y += 24; });
    return { nodes: positioned, edges: edges, more: more,
      width: Math.max(180, ...positioned.map(function (n) { return n.x + geometry.width + 24; }), ...more.map(function (n) { return n.x + geometry.width + 24; })), height: Math.max(140, y) };
  }
  return { normal: normal, displayName: displayName, vietnameseMeaning: vietnameseMeaning, index: index, project: project, reachable: reachable, ancestors: ancestors, descendants: descendants, entryRoots: entryRoots, filter: filter, search: search, compareComponents: compareComponents, similarKanji: similarKanji, studyQuestions: studyQuestions, componentDiagram: componentDiagram, layout: layout, geometry: geometry };
});
