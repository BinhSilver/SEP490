/* Horizontal presentation trees. Repeated codes keep their identity but have
 * separate visual occurrences, so branches never need a joining/return wire. */
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.AiCallBranchTree = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    function fromDependencies(definitions) {
        const byCode = new Map(definitions.map(d => [d.code, d]));
        const children = new Map(definitions.map(d => [d.code, []]));
        definitions.forEach(d => (d.prerequisites || []).forEach(parent => {
            if (!byCode.has(parent)) throw new Error(`Unknown prerequisite: ${parent}`);
            children.get(parent).push(d.code);
        }));
        let serial = 0;
        const visited = new Set();
        function expand(code, ancestors) {
            if (ancestors.has(code)) throw new Error(`Circular prerequisite: ${code}`);
            visited.add(code);
            const next = new Set(ancestors); next.add(code);
            return { id: `branch-${serial++}`, code,
                children: children.get(code).map(child => expand(child, next)) };
        }
        const forest = definitions.filter(d => !(d.prerequisites || []).length)
            .map(d => expand(d.code, new Set()));
        if (visited.size !== byCode.size) throw new Error('Unreachable prerequisite cycle');
        return forest;
    }

    // Merge only identical prefixes. A step reached by two different branches,
    // or repeated later in a route, becomes a new visual occurrence.
    function fromPaths(paths) {
        const forest = [];
        let serial = 0;
        paths.forEach((path, index) => {
            let siblings = forest;
            path.forEach(code => {
                let node = siblings.find(n => n.code === code);
                if (!node) {
                    node = { id: `step-${serial++}`, code, children: [], paths: [] };
                    siblings.push(node);
                }
                node.paths.push(index);
                siblings = node.children;
            });
        });
        return forest;
    }

    function layout(forest, options = {}) {
        const nodeWidth = options.nodeWidth || 232;
        const columnGap = options.columnGap || 88;
        const rowGap = options.rowGap || 36;
        const padding = options.padding || 40;
        const getHeight = options.getHeight || (() => 122);
        const nodes = [], edges = [], spans = new Map(), heights = new Map();
        function measure(node) {
            const height = getHeight(node.code);
            heights.set(node.id, height);
            const childrenHeight = node.children.reduce((sum, child) => sum + measure(child), 0)
                + Math.max(0, node.children.length - 1) * rowGap;
            const span = Math.max(height, childrenHeight);
            spans.set(node.id, span);
            return span;
        }
        forest.forEach(measure);
        function place(node, depth, top, parent) {
            const height = heights.get(node.id), span = spans.get(node.id);
            const placed = { id: node.id, code: node.code, paths: node.paths,
                x: padding + depth * (nodeWidth + columnGap),
                y: top + (span - height) / 2, width: nodeWidth, height };
            nodes.push(placed);
            if (parent) edges.push({ source: parent.id, target: node.id });
            const childrenHeight = node.children.reduce((sum, child) => sum + spans.get(child.id), 0)
                + Math.max(0, node.children.length - 1) * rowGap;
            let childTop = top + (span - childrenHeight) / 2;
            node.children.forEach(child => {
                place(child, depth + 1, childTop, placed);
                childTop += spans.get(child.id) + rowGap;
            });
        }
        let top = padding;
        forest.forEach(node => { place(node, 0, top, null); top += spans.get(node.id) + rowGap; });
        const naturalHeight = Math.max(0, ...nodes.map(n => n.y + n.height)) + padding;
        const height = Math.max(naturalHeight, options.minHeight || 0);
        nodes.forEach(n => { n.y += (height - naturalHeight) / 2; });
        return { nodes, edges,
            width: Math.max(0, ...nodes.map(n => n.x + n.width)) + padding,
            height };
    }

    function connector(source, target) {
        // Leave room for the card's selection/focus outline at both ports.
        const x1 = source.x + source.width + 6, y1 = source.y + source.height / 2;
        const x2 = target.x - 6, y2 = target.y + target.height / 2;
        return `M ${x1} ${y1} L ${x2} ${y2}`;
    }
    return { fromDependencies, fromPaths, layout, connector };
});
