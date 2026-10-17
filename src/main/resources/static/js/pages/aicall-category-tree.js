/* Connect the actual node positions, including wrapped text and mobile layout. */
(() => {
    'use strict';
    const tree = document.querySelector('[data-scenario-tree]');
    if (!tree) return;
    const svg = tree.querySelector('.tree-connections');
    const topic = tree.querySelector('[data-tree-topic]');
    const branches = [...tree.querySelectorAll('.tree-branch')];
    const ns = 'http://www.w3.org/2000/svg';
    let frame = 0;

    function element(name, attributes) {
        const node = document.createElementNS(ns, name);
        for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
        return node;
    }

    function draw() {
        frame = 0;
        const bounds = tree.getBoundingClientRect();
        const originX = bounds.left + tree.clientLeft;
        const originY = bounds.top + tree.clientTop;
        const box = node => {
            const rect = node.getBoundingClientRect();
            return { x: rect.left - originX, y: rect.top - originY, w: rect.width, h: rect.height };
        };
        const topicBox = box(topic);
        const narrow = window.matchMedia('(max-width: 600px)').matches;
        const stacked = window.matchMedia('(max-width: 960px)').matches;
        const content = document.createDocumentFragment();
        const marker = element('marker', { id: 'aicall-tree-arrow', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '6', markerHeight: '6', orient: 'auto' });
        marker.append(element('path', { d: 'M 0 1 L 9 5 L 0 9 Z', class: 'tree-arrow' }));
        const defs = element('defs', {}); defs.append(marker); content.append(defs);

        function path(d) {
            content.append(element('path', { d, 'marker-end': 'url(#aicall-tree-arrow)' }));
        }
        function horizontal(source, target) {
            const x1 = source.x + source.w, y1 = source.y + source.h / 2;
            const x2 = target.x - 3, y2 = target.y + target.h / 2;
            const bend = (x1 + x2) / 2;
            path(`M ${x1} ${y1} C ${bend} ${y1}, ${bend} ${y2}, ${x2} ${y2}`);
        }
        for (const branch of branches) {
            const scenario = box(branch.querySelector('[data-tree-scenario]'));
            if (narrow) {
                const x = scenario.x - 9;
                path(`M ${topicBox.x + 10} ${topicBox.y + topicBox.h} L ${x} ${topicBox.y + topicBox.h + 12} L ${x} ${scenario.y + scenario.h / 2} L ${scenario.x - 3} ${scenario.y + scenario.h / 2}`);
            } else {
                horizontal(topicBox, scenario);
            }
            const cases = [...branch.querySelectorAll('[data-tree-case]')].map(box);
            for (const practiceCase of cases) {
                if (stacked) {
                    const x = practiceCase.x - (narrow ? 8 : 14);
                    path(`M ${scenario.x + 10} ${scenario.y + scenario.h} L ${x} ${scenario.y + scenario.h + 10} L ${x} ${practiceCase.y + practiceCase.h / 2} L ${practiceCase.x - 3} ${practiceCase.y + practiceCase.h / 2}`);
                } else {
                    horizontal(scenario, practiceCase);
                }
            }
            if (!stacked && cases.length) {
                content.append(element('circle', { cx: scenario.x + scenario.w, cy: scenario.y + scenario.h / 2, r: 3 }));
            }
        }
        svg.setAttribute('viewBox', `0 0 ${tree.clientWidth} ${tree.clientHeight}`);
        svg.replaceChildren(content);
    }
    function schedule() {
        if (!frame) frame = window.requestAnimationFrame(draw);
    }
    if ('ResizeObserver' in window) {
        const observer = new ResizeObserver(schedule);
        observer.observe(tree);
        tree.querySelectorAll('[data-tree-topic], [data-tree-scenario], [data-tree-case]').forEach(node => observer.observe(node));
    }
    window.addEventListener('resize', schedule, { passive: true });
    if (document.fonts) document.fonts.ready.then(schedule);
    schedule();
})();
