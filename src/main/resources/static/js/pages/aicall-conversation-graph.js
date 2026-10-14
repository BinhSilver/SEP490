(() => {
    'use strict';
    document.querySelectorAll('[data-conversation-graph]').forEach((graph, graphIndex) => {
        function initialize() {
            if (graph.dataset.initialized) return;
            graph.dataset.initialized = 'true';
            const tree = window.AiCallBranchTree;
            const viewport = graph.querySelector('.graph-viewport');
            const canvas = graph.querySelector('.graph-canvas');
            const space = graph.querySelector('.graph-space');
            const svg = graph.querySelector('.graph-edges');
            const templates = new Map([...graph.querySelectorAll('[data-code]')].map(n => [n.dataset.code, n]));
            const heights = new Map([...templates].map(([code, node]) => [code, node.offsetHeight]));
            const buttons = [...graph.querySelectorAll('[data-graph-path]')];
            const routes = buttons.map(b => b.dataset.path.split(',').filter(Boolean));
            const forest = tree.fromPaths(routes.length ? routes : [[graph.dataset.initial]]);
            const layout = tree.layout(forest, { nodeWidth: 180, columnGap: 80, minHeight: viewport.clientHeight,
                getHeight: code => heights.get(code) });
            const nodes = new Map(), boxes = new Map(layout.nodes.map(n => [n.id, n]));
            layout.nodes.forEach(box => {
                const node = templates.get(box.code).cloneNode(true);
                node.dataset.occurrence = box.id;
                node.style.left = `${box.x}px`; node.style.top = `${box.y}px`;
                canvas.append(node); nodes.set(box.id, node);
            });
            templates.forEach(node => node.remove());
            canvas.style.width = `${layout.width}px`; canvas.style.height = `${layout.height}px`;
            svg.setAttribute('viewBox', `0 0 ${layout.width} ${layout.height}`);
            const ns = 'http://www.w3.org/2000/svg';
            const make = (tag, attrs) => {
                const node = document.createElementNS(ns, tag);
                Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
                return node;
            };
            const markerId = `conversation-arrow-${graphIndex}`, defs = make('defs', {});
            const marker = make('marker', { id: markerId, viewBox: '0 0 10 10', refX: 9,
                refY: 5, markerWidth: 10, markerHeight: 10, markerUnits: 'userSpaceOnUse', orient: 'auto' });
            marker.append(make('path', { d: 'M 0 1 L 9 5 L 0 9 Z', class: 'graph-arrow' }));
            defs.append(marker); svg.append(defs);
            const edges = layout.edges;
            edges.forEach(edge => {
                edge.path = make('path', { d: tree.connector(boxes.get(edge.source), boxes.get(edge.target)),
                    'marker-end': `url(#${markerId})` });
                svg.append(edge.path);
            });
            const labels = new Map([...graph.querySelectorAll('[data-graph-edges] span')]
                .map(n => [`${n.dataset.source}:${n.dataset.target}`, n.dataset.label]));
            let scale = 1, selected = layout.nodes[0].id, currentRoute = null, currentLayout = layout;
            function zoom(value) {
                scale = Math.max(.65, Math.min(1.6, value));
                canvas.style.transform = `scale(${scale})`;
                space.style.width = `${currentLayout.width * scale}px`; space.style.height = `${currentLayout.height * scale}px`;
            }
            function renderRoute(index) {
                currentRoute = index;
                const filter = siblings => siblings.filter(n => n.paths.includes(index))
                    .map(n => ({ ...n, children: filter(n.children) }));
                currentLayout = index === null ? layout : tree.layout(filter(forest), {
                    nodeWidth: 180, columnGap: 80, minHeight: viewport.clientHeight, getHeight: code => heights.get(code)
                });
                const visible = new Map(currentLayout.nodes.map(n => [n.id, n]));
                nodes.forEach((node, id) => {
                    const box = visible.get(id); node.hidden = !box;
                    if (box) { node.style.left = `${box.x}px`; node.style.top = `${box.y}px`; }
                });
                edges.forEach(e => {
                    const a = visible.get(e.source), b = visible.get(e.target);
                    e.path.classList.toggle('is-hidden', !a || !b);
                    if (a && b) e.path.setAttribute('d', tree.connector(a, b));
                });
                canvas.style.width = `${currentLayout.width}px`; canvas.style.height = `${currentLayout.height}px`;
                svg.setAttribute('viewBox', `0 0 ${currentLayout.width} ${currentLayout.height}`);
                zoom(scale);
            }
            function reveal(node) {
                viewport.scrollTo({ left: Math.max(0, node.offsetLeft * scale - 36),
                    top: Math.max(0, (node.offsetTop + node.offsetHeight / 2) * scale - viewport.clientHeight / 2),
                    behavior: 'auto' });
            }
            function clearHighlights() {
                nodes.forEach(n => { n.classList.remove('is-muted', 'is-related', 'is-selected'); n.setAttribute('aria-pressed', 'false'); });
                edges.forEach(e => e.path.classList.remove('is-muted', 'is-highlighted'));
            }
            function select(id, scroll = false) {
                const node = nodes.get(id); if (!node) return;
                selected = id; clearHighlights();
                buttons.forEach((b, i) => { b.classList.toggle('is-active', i === currentRoute); b.setAttribute('aria-pressed', String(i === currentRoute)); });
                const all = graph.querySelector('[data-graph-all]');
                all.classList.toggle('is-active', currentRoute === null); all.setAttribute('aria-pressed', String(currentRoute === null));
                node.classList.add('is-selected'); node.setAttribute('aria-pressed', 'true');
                edges.filter(e => !e.path.classList.contains('is-hidden') && (e.source === id || e.target === id)).forEach(e => {
                    e.path.classList.add('is-highlighted');
                    nodes.get(e.source).classList.add('is-related'); nodes.get(e.target).classList.add('is-related');
                });
                graph.querySelector('[data-graph-title]').textContent = node.querySelector('span').textContent;
                graph.querySelector('[data-graph-summary]').textContent = node.dataset.summary || '';
                graph.querySelector('[data-graph-example]').textContent = node.dataset.example || '';
                const next = graph.querySelector('[data-graph-next]'); next.replaceChildren();
                edges.filter(e => e.source === id && !e.path.classList.contains('is-hidden')).forEach(e => {
                    const target = nodes.get(e.target), button = document.createElement('button');
                    button.type = 'button';
                    const label = document.createElement('span');
                    label.textContent = labels.get(`${node.dataset.code}:${target.dataset.code}`) || 'Tiếp tục';
                    button.append(label, document.createTextNode(': ' + target.querySelector('span').textContent));
                    button.addEventListener('click', () => select(e.target, true)); next.append(button);
                });
                if (scroll) reveal(node);
            }
            nodes.forEach((node, id) => node.addEventListener('click', () => select(id)));
            graph.querySelector('[data-graph-all]').addEventListener('click', () => { renderRoute(null); select(selected, true); });
            buttons.forEach((button, index) => button.addEventListener('click', () => {
                clearHighlights();
                renderRoute(index);
                graph.querySelectorAll('.graph-branches button').forEach(b => {
                    b.classList.toggle('is-active', b === button); b.setAttribute('aria-pressed', String(b === button));
                });
                const active = layout.nodes.filter(n => n.paths.includes(index));
                const ids = new Set(active.map(n => n.id));
                nodes.forEach((node, id) => node.classList.toggle('is-muted', !ids.has(id)));
                edges.forEach(e => {
                    const onPath = ids.has(e.source) && ids.has(e.target);
                    e.path.classList.toggle('is-highlighted', onPath); e.path.classList.toggle('is-muted', !onPath);
                });
                graph.querySelector('[data-graph-title]').textContent = button.textContent;
                graph.querySelector('[data-graph-summary]').textContent = active.map(n => nodes.get(n.id).querySelector('span').textContent).join(' → ');
                graph.querySelector('[data-graph-example]').textContent = '';
                graph.querySelector('[data-graph-next]').replaceChildren();
                const first = active.find(n => edges.filter(e => e.source === n.id).length > 1);
                const focus = first ? active[active.indexOf(first) + 1] : active[0];
                if (focus) reveal(nodes.get(focus.id));
            }));
            graph.querySelectorAll('[data-graph-zoom]').forEach(b => b.addEventListener('click', () => {
                if (b.dataset.graphZoom === 'reset') { zoom(1); reveal(nodes.get(selected)); }
                else zoom(scale + (b.dataset.graphZoom === 'in' ? .15 : -.15));
            }));
            const practice = graph.querySelector('.graph-practice');
            if (practice && practice.getAttribute('href') === location.pathname) practice.hidden = true;
            zoom(1); select(selected); reveal(nodes.get(selected));
        }
        const details = graph.closest('details');
        if (details && !details.open) details.addEventListener('toggle', () => { if (details.open) initialize(); });
        else initialize();
    });
})();
