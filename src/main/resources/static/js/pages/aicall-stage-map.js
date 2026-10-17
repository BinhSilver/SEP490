(() => {
    'use strict';
    const mobile = window.matchMedia('(max-width: 900px)');
    document.querySelectorAll('[data-stage-map]').forEach((map, index) => {
        const templates = new Map([...map.querySelectorAll('[data-stage]')].map(node => [node.dataset.stage, node.cloneNode(true)]));
        if (!templates.size) return;
        const viewport = map.querySelector('.stage-map-viewport'), canvas = map.querySelector('.stage-map-canvas');
        const space = map.querySelector('.stage-map-space'), svg = map.querySelector('svg');
        const story = map.querySelector('.stage-story'), storyViewport = story.querySelector('.stage-story-viewport');
        story.id = `stage-story-${index}`;
        let cards = new Map(), edges = [], width = 0, height = 0, scale = 1;
        let selectedCode = map.dataset.selected || map.dataset.recommended || templates.keys().next().value;
        let storyOpen = false;

        function zoom(value) {
            if (mobile.matches) return;
            scale = Math.max(.6, Math.min(1.5, value));
            canvas.style.transform = `scale(${scale})`;
            space.style.width = `${width * scale}px`;
            space.style.height = `${height * scale}px`;
        }
        function renderStory(card) {
            const list = map.querySelector('[data-stage-story-steps]');
            map.querySelector('[data-stage-story-title]').textContent = `Diễn biến: ${card.dataset.title}`;
            list.replaceChildren();
            [...card.querySelectorAll('[data-story-code]')].forEach((step, stepIndex) => {
                const data = step.dataset, li = document.createElement('li'), item = document.createElement('div');
                item.className = 'stage-story-step';
                if (data.storyEnding === 'true') item.classList.add('is-ending');
                item.title = data.storySummary || data.storyTitle;
                const icon = document.createElement('i');
                icon.className = data.storyIcon || 'fa-solid fa-comment';
                icon.setAttribute('aria-hidden', 'true');
                const title = document.createElement('span'); title.textContent = data.storyTitle;
                const number = document.createElement('small');
                number.textContent = data.storyEnding === 'true' ? 'Kết thúc' : `Bước ${stepIndex + 1}`;
                if (data.storySpeaker) number.textContent += ` · ${data.storySpeaker}`;
                item.append(icon, title, number); li.append(item); list.append(li);
            });
            storyViewport.scrollLeft = 0; storyViewport.scrollTop = 0;
        }
        function select(occurrence, toggleStory = false) {
            const card = cards.get(occurrence); if (!card) return;
            if (mobile.matches && toggleStory) storyOpen = selectedCode === card.dataset.stage ? !storyOpen : true;
            selectedCode = card.dataset.stage;
            cards.forEach((node, key) => {
                const active = key === occurrence, button = node.querySelector('[data-stage-select]');
                node.classList.toggle('is-selected', active);
                button.setAttribute('aria-pressed', String(active));
                if (mobile.matches) {
                    button.setAttribute('aria-expanded', String(active && storyOpen));
                    button.setAttribute('aria-controls', story.id);
                }
            });
            edges.forEach(edge => edge.path.classList.toggle('is-selected', edge.target === occurrence || edge.source === occurrence));
            renderStory(card);
            story.hidden = mobile.matches && !storyOpen;
            if (mobile.matches) card.after(story);
            const data = card.dataset, locked = data.status === 'locked', reviewer = map.dataset.reviewer === 'true';
            document.querySelectorAll(`[data-stage-selected-title][data-scenario="${map.dataset.scenario}"]`).forEach(node => { node.textContent = data.title; });
            document.querySelectorAll(`[data-stage-start][data-scenario="${map.dataset.scenario}"]`).forEach(link => {
                link.hidden = locked;
                link.href = data.resume ? `/conversation/${data.resume}` : `/scenario/${map.dataset.scenario}/start?stage=${encodeURIComponent(data.stage)}`;
                const actionLabel = data.resume ? 'Tiếp tục' : reviewer ? 'Kiểm tra màn' : data.status === 'passed' ? 'Luyện lại' : 'Bắt đầu màn';
                const label = link.querySelector('[data-stage-start-label]'); if (label) label.textContent = actionLabel;
                link.setAttribute('aria-label', `${actionLabel}: ${data.title}`); link.title = `${actionLabel}: ${data.title}`;
            });
            document.querySelectorAll(`[data-stage-sample][data-scenario="${map.dataset.scenario}"]`).forEach(link => {
                link.href = `/scenario/${map.dataset.scenario}/sample?stage=${encodeURIComponent(data.stage)}`;
                link.setAttribute('aria-label', `Xem hội thoại mẫu: ${data.title}`); link.title = `Xem hội thoại mẫu: ${data.title}`;
            });
        }
        function render() {
            // Shared graph nodes occur once in the mobile list; desktop keeps every branch occurrence.
            map.append(story); canvas.replaceChildren(svg); svg.replaceChildren();
            canvas.style.transform = ''; canvas.style.width = ''; canvas.style.height = '';
            space.style.width = ''; space.style.height = '';
            cards = new Map(); edges = [];
            map.classList.toggle('stage-map--list', mobile.matches);
            viewport.scrollLeft = 0; viewport.scrollTop = 0;
            viewport.setAttribute('aria-label', mobile.matches ? 'Danh sách các nhánh luyện hội thoại' : 'Bản đồ mở khóa màn, cuộn ngang để xem tiếp');
            storyViewport.setAttribute('aria-label', mobile.matches ? 'Diễn biến từ mở đầu đến kết thúc' : 'Các bước từ mở đầu đến kết thúc, cuộn ngang để xem tiếp');
            if (mobile.matches) {
                viewport.removeAttribute('tabindex'); storyViewport.removeAttribute('tabindex');
                templates.forEach((template, code) => {
                    const card = template.cloneNode(true); canvas.append(card); cards.set(code, card);
                });
            } else {
                viewport.setAttribute('tabindex', '0'); storyViewport.setAttribute('tabindex', '0');
                const tree = window.AiCallBranchTree;
                const forest = tree.fromDependencies([...templates].map(([code, node]) => ({ code, prerequisites: (node.dataset.prerequisites || '').split(',').filter(Boolean) })));
                const measurements = new Map();
                templates.forEach((template, code) => {
                    const node = template.cloneNode(true); canvas.append(node); measurements.set(code, node);
                });
                const layout = tree.layout(forest, { minHeight: viewport.clientHeight, getHeight: code => measurements.get(code).offsetHeight });
                measurements.forEach(node => node.remove()); width = layout.width; height = layout.height;
                layout.nodes.forEach(box => {
                    const card = templates.get(box.code).cloneNode(true);
                    card.dataset.occurrence = box.id; card.style.left = `${box.x}px`; card.style.top = `${box.y}px`;
                    canvas.append(card); cards.set(box.id, card);
                });
                canvas.style.width = `${width}px`; canvas.style.height = `${height}px`; svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
                const make = (tag, attributes) => {
                    const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
                    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, value)); return element;
                };
                const id = `stage-arrow-${index}`, defs = make('defs', {});
                const marker = make('marker', { id, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 10, markerHeight: 10, markerUnits: 'userSpaceOnUse', orient: 'auto' });
                marker.append(make('path', { d: 'M 0 1 L 9 5 L 0 9 Z', fill: 'context-stroke' })); defs.append(marker); svg.append(defs);
                const byId = new Map(layout.nodes.map(box => [box.id, box])); edges = layout.edges;
                edges.forEach(edge => {
                    const path = make('path', { d: tree.connector(byId.get(edge.source), byId.get(edge.target)), 'marker-end': `url(#${id})` }); edge.path = path;
                    path.classList.toggle('is-locked', cards.get(edge.target).dataset.status === 'locked');
                    path.classList.toggle('is-passed', cards.get(edge.source).dataset.status === 'passed'); svg.append(path);
                });
                zoom(1);
            }
            cards.forEach((card, key) => card.querySelector('[data-stage-select]').addEventListener('click', () => select(key, true)));
            const initial = [...cards].find(([, card]) => card.dataset.stage === selectedCode) || cards.entries().next().value;
            select(initial[0]);
            if (!mobile.matches) {
                const card = initial[1];
                viewport.scrollTo({ left: Math.max(0, card.offsetLeft - 32), top: Math.max(0, card.offsetTop + card.offsetHeight / 2 - viewport.clientHeight / 2), behavior: 'auto' });
            }
        }
        map.querySelectorAll('[data-stage-zoom]').forEach(button => button.addEventListener('click', () => zoom(scale + (button.dataset.stageZoom === 'in' ? .15 : -.15))));
        mobile.addEventListener('change', render);
        render();
    });
    if (location.hash === '#case-context-heading') {
        const revealContext = () => requestAnimationFrame(() => document.getElementById('case-context-heading')?.scrollIntoView({ block: 'start' }));
        if (document.readyState === 'complete') revealContext();
        else window.addEventListener('load', revealContext, { once: true });
    }
})();
