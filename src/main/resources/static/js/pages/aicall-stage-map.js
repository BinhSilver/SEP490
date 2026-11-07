(() => {
    'use strict';
    const mobile = window.matchMedia('(max-width: 900px)');
    const MIN_SCALE = .35, MAX_SCALE = 2.4, NODE_WIDTH = 224; // NODE_WIDTH matches .stage-card in aicall-stages.css
    document.querySelectorAll('[data-stage-map]').forEach((map, index) => {
        const templates = new Map([...map.querySelectorAll('[data-stage]')].map(node => [node.dataset.stage, node.cloneNode(true)]));
        if (!templates.size) return;
        const viewport = map.querySelector('.stage-map-viewport'), canvas = map.querySelector('.stage-map-canvas');
        const frame = map.querySelector('.stage-map-frame'), svg = map.querySelector('svg');
        const story = map.querySelector('.stage-story'), storyViewport = story.querySelector('.stage-story-viewport');
        const zoomValue = map.querySelector('[data-stage-zoom-value]');
        story.id = `stage-story-${index}`;
        let cards = new Map(), edges = [], boxes = new Map(), parents = new Map(), width = 0, height = 0;
        let view = { x: 0, y: 0, scale: 1 }, selectedOccurrence = null;
        const pointers = new Map(); let gesture = null;
        let selectedCode = map.dataset.selected || map.dataset.recommended || templates.keys().next().value;
        let storyOpen = false;

        // CSS zoom instead of a scale transform: text is laid out again at its real size, so it stays sharp
        // at every level. An element's own zoom also multiplies its translate, hence the division;
        // whole pixels keep the glyphs on the pixel grid.
        function transform() {
            canvas.style.zoom = view.scale;
            canvas.style.transform = `translate(${Math.round(view.x) / view.scale}px, ${Math.round(view.y) / view.scale}px)`;
            if (zoomValue) zoomValue.textContent = `${Math.round(view.scale * 100)}%`;
        }
        // Zooms around a point of the viewport, so what sits under the pointer stays there.
        function zoom(factor, x = viewport.clientWidth / 2, y = viewport.clientHeight / 2) {
            if (mobile.matches) return;
            const next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, view.scale * factor));
            view = { x: x - (x - view.x) * next / view.scale, y: y - (y - view.y) * next / view.scale, scale: next };
            transform();
        }
        function fit() {
            const scale = Math.max(MIN_SCALE, Math.min(1, (viewport.clientWidth - 100) / width, (viewport.clientHeight - 60) / height));
            view = { x: (viewport.clientWidth - width * scale) / 2, y: (viewport.clientHeight - height * scale) / 2, scale };
            transform();
        }
        // Readable size with the selected stage in view; fit shows the whole map.
        function home() {
            const box = boxes.get(selectedOccurrence); if (!box) return;
            view = { x: Math.min(50, viewport.clientWidth / 2 - (box.x + box.width / 2)), y: viewport.clientHeight / 2 - (box.y + box.height / 2), scale: 1 };
            transform();
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
            selectedCode = card.dataset.stage; selectedOccurrence = occurrence;
            const route = new Set();
            for (let step = occurrence; step != null; step = parents.get(step)) route.add(step);
            cards.forEach((node, key) => {
                const active = key === occurrence, button = node.querySelector('[data-stage-select]');
                node.classList.toggle('is-selected', active);
                node.classList.toggle('is-route', !active && route.has(key));
                button.setAttribute('aria-pressed', String(active));
                if (mobile.matches) {
                    button.setAttribute('aria-expanded', String(active && storyOpen));
                    button.setAttribute('aria-controls', story.id);
                }
            });
            edges.forEach(edge => edge.path.classList.toggle('is-selected', route.has(edge.target)));
            renderStory(card);
            const detail = map.querySelector('[data-stage-detail]');
            if (detail) detail.href = `/scenario/${map.dataset.scenario}?stage=${encodeURIComponent(card.dataset.stage)}#case-context-heading`;
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
            canvas.style.zoom = ''; canvas.style.transform = ''; canvas.style.width = ''; canvas.style.height = '';
            cards = new Map(); edges = []; boxes = new Map(); parents = new Map();
            map.classList.toggle('stage-map--list', mobile.matches);
            viewport.setAttribute('aria-label', mobile.matches ? 'Danh sách các nhánh luyện hội thoại' : 'Bản đồ các màn. Kéo để di chuyển, lăn chuột hoặc dùng các nút để thu phóng.');
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
                const layout = tree.layout(forest, { nodeWidth: NODE_WIDTH, columnGap: 104, rowGap: 14, getHeight: code => measurements.get(code).offsetHeight });
                measurements.forEach(node => node.remove()); width = layout.width; height = layout.height;
                boxes = new Map(layout.nodes.map(box => [box.id, box]));
                layout.nodes.forEach(box => {
                    const card = templates.get(box.code).cloneNode(true);
                    card.dataset.occurrence = box.id; card.style.left = `${box.x}px`; card.style.top = `${box.y}px`;
                    card.style.height = `${box.height}px`; // a title may wrap differently per zoom level; never into the next row
                    canvas.append(card); cards.set(box.id, card);
                });
                canvas.style.width = `${width}px`; canvas.style.height = `${height}px`; svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
                edges = layout.edges;
                edges.forEach(edge => {
                    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                    path.setAttribute('d', tree.connector(boxes.get(edge.source), boxes.get(edge.target))); edge.path = path;
                    parents.set(edge.target, edge.source);
                    path.classList.toggle('is-locked', cards.get(edge.target).dataset.status === 'locked');
                    path.classList.toggle('is-passed', cards.get(edge.source).dataset.status === 'passed'); svg.append(path);
                });
            }
            cards.forEach((card, key) => card.querySelector('[data-stage-select]').addEventListener('click', () => select(key, true)));
            const initial = [...cards].find(([, card]) => card.dataset.stage === selectedCode) || cards.entries().next().value;
            select(initial[0]);
            if (!mobile.matches) home();
        }
        const point = event => {
            const rect = viewport.getBoundingClientRect();
            return { x: event.clientX - rect.left, y: event.clientY - rect.top };
        };
        function resetGesture() {
            const points = [...pointers.values()];
            gesture = !points.length ? null : points.length === 1 ? { ...points[0], distance: 0 } : {
                x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2,
                distance: Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y)
            };
        }
        viewport.addEventListener('pointerdown', event => {
            if (mobile.matches || event.target.closest('button, a') || (event.pointerType === 'mouse' && event.button !== 0)) return;
            pointers.set(event.pointerId, point(event)); viewport.setPointerCapture(event.pointerId);
            viewport.classList.add('is-dragging'); resetGesture();
        });
        viewport.addEventListener('pointermove', event => {
            if (!pointers.has(event.pointerId) || !gesture) return;
            pointers.set(event.pointerId, point(event));
            const points = [...pointers.values()];
            if (points.length > 1) {
                const x = (points[0].x + points[1].x) / 2, y = (points[0].y + points[1].y) / 2;
                const distance = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
                if (gesture.distance > 0) zoom(distance / gesture.distance, gesture.x, gesture.y);
                view.x += x - gesture.x; view.y += y - gesture.y;
            } else {
                view.x += points[0].x - gesture.x; view.y += points[0].y - gesture.y;
            }
            resetGesture(); transform();
        });
        const release = event => {
            pointers.delete(event.pointerId); resetGesture();
            if (!pointers.size) viewport.classList.remove('is-dragging');
        };
        viewport.addEventListener('pointerup', release); viewport.addEventListener('pointercancel', release);
        viewport.addEventListener('wheel', event => {
            if (mobile.matches) return;
            event.preventDefault();
            const at = point(event), delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? viewport.clientHeight : 1);
            // Bounded step, so high-resolution mice and trackpads do not jump.
            zoom(Math.exp(-Math.max(-120, Math.min(120, delta)) * .003), at.x, at.y);
        }, { passive: false });
        viewport.addEventListener('keydown', event => {
            if (mobile.matches || event.target !== viewport) return;
            const direction = { ArrowLeft: [50, 0], ArrowRight: [-50, 0], ArrowUp: [0, 50], ArrowDown: [0, -50] }[event.key];
            if (direction) { event.preventDefault(); view.x += direction[0]; view.y += direction[1]; transform(); }
            if (event.key === '+' || event.key === '=') { event.preventDefault(); zoom(1.2); }
            if (event.key === '-') { event.preventDefault(); zoom(1 / 1.2); }
        });
        map.querySelectorAll('[data-stage-zoom]').forEach(button => button.addEventListener('click', () => zoom(button.dataset.stageZoom === 'in' ? 1.2 : 1 / 1.2)));
        map.querySelector('[data-stage-view="fit"]')?.addEventListener('click', fit);
        map.querySelector('[data-stage-view="home"]')?.addEventListener('click', home);
        map.querySelector('[data-stage-view="fullscreen"]')?.addEventListener('click', () => {
            if (document.fullscreenElement) document.exitFullscreen(); else frame.requestFullscreen();
        });
        mobile.addEventListener('change', render);
        render();
        // Card heights were measured with fallback fonts; a title can wrap differently once the real ones load.
        document.fonts?.ready.then(render);
    });
    if (location.hash === '#case-context-heading') {
        const revealContext = () => requestAnimationFrame(() => document.getElementById('case-context-heading')?.scrollIntoView({ block: 'start' }));
        if (document.readyState === 'complete') revealContext();
        else window.addEventListener('load', revealContext, { once: true });
    }
})();
