const colors = ['#262238', '#cb3446', '#216bc2', '#188554', '#c46b15', '#7550bf', '#843f29'];
const colorNames = ['Đen', 'Đỏ', 'Xanh dương', 'Xanh lá', 'Cam', 'Tím', 'Nâu'];

export function mountDrawing(root, send, refresh, showError) {
    const find = name => root.querySelector('[data-' + name + ']');
    const canvas = find('canvas');
    const ctx = canvas.getContext('2d');
    const tools = find('tools');
    const pen = find('pen');
    const eraser = find('eraser');
    const palette = find('colors');
    const clear = find('clear');
    const undo = find('undo');
    const redo = find('redo');
    let round = 0;
    let sequence = 0;
    let board = [];
    let pending = [];
    let sending;
    let command;
    let pointer;
    let editable = false;
    let canUndo = false;
    let canRedo = false;
    let strokeId = Date.now() * 1000;
    let color = 0;
    let width = 8;
    let eraserWidth = 18;
    let erasing = false;
    let openTool;

    function paint(segment) {
        const x = segment.x * canvas.width / 10000;
        const y = segment.y * canvas.height / 10000;
        ctx.globalCompositeOperation = segment.erase ? 'destination-out' : 'source-over';
        ctx.strokeStyle = ctx.fillStyle = colors[segment.color];
        ctx.lineWidth = segment.width;
        ctx.lineCap = 'round';
        ctx.beginPath();
        if (segment.x === segment.toX && segment.y === segment.toY) {
            ctx.arc(x, y, segment.width / 2, 0, Math.PI * 2);
            ctx.fill();
        } else {
            ctx.moveTo(x, y);
            ctx.lineTo(segment.toX * canvas.width / 10000, segment.toY * canvas.height / 10000);
            ctx.stroke();
        }
        ctx.globalCompositeOperation = 'source-over';
    }

    function redraw() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        board.forEach(paint);
        if (sending && sequence < sending.expected) sending.segments.forEach(paint);
        pending.forEach(item => paint(item.segment));
    }

    function updateActions() {
        const blocked = !editable || !!pointer || !!command || !!sending?.replacing;
        undo.disabled = blocked || (!canUndo && !pending.length && !sending?.segments.length);
        redo.disabled = blocked || !canRedo || !!pending.length || !!sending?.segments.length;
        clear.disabled = blocked || (!board.length && !pending.length && !sending?.segments.length);
        canvas.dataset.draw = String(editable && !command && !sending?.replacing);
        canvas.tabIndex = editable ? 0 : -1;
    }

    function restore(view) {
        const changedRound = round !== view.round;
        if (changedRound) {
            round = view.round;
            sequence = -1;
            pending = [];
            sending = command = pointer = undefined;
            showOptions();
        }
        editable = view.canDraw;
        if (!editable) {
            pending = [];
            command = pointer = undefined;
            if (openTool) showOptions();
        }
        if (view.inkSequence >= sequence) {
            sequence = view.inkSequence;
            board = view.segments;
            canUndo = view.canUndo;
            canRedo = view.canRedo;
            redraw();
        }
        updateActions();
    }

    function receive(delta, ownReply = false) {
        if (delta.round !== round || delta.sequence <= sequence) return;
        if (!ownReply && sending && delta.sequence === sending.expected) return;
        if (delta.sequence !== sequence + 1 && !delta.clear) {
            void refresh();
            return;
        }
        sequence = delta.sequence;
        board = delta.clear ? delta.segments : board.concat(delta.segments);
        canUndo = delta.canUndo;
        canRedo = delta.canRedo;
        redraw();
        updateActions();
    }

    function point(event) {
        const bounds = canvas.getBoundingClientRect();
        const scale = (value, size) => Math.min(10000, Math.max(0, Math.round(value * 10000 / size)));
        return { x: scale(event.clientX - bounds.left, bounds.width), y: scale(event.clientY - bounds.top, bounds.height) };
    }

    function add(from, to) {
        if (pending.length >= 240) return;
        const segment = { ...from, toX: to.x, toY: to.y, ...pointer.brush };
        pending.push({ id: pointer.stroke, segment });
        paint(segment);
    }

    canvas.addEventListener('pointerdown', event => {
        if (!editable || command || sending?.replacing || pointer || event.button !== 0) return;
        const start = point(event);
        pointer = {
            id: event.pointerId, last: start, stroke: ++strokeId,
            brush: { color, width: erasing ? eraserWidth : width, erase: erasing }
        };
        canvas.setPointerCapture(event.pointerId);
        canvas.focus({ preventScroll: true });
        showOptions();
        add(start, start);
        updateActions();
    });
    canvas.addEventListener('pointermove', event => {
        if (pointer?.id !== event.pointerId) return;
        const current = point(event);
        add(pointer.last, current);
        pointer.last = current;
    });
    for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) {
        canvas.addEventListener(name, event => {
            if (pointer?.id !== event.pointerId) return;
            pointer = undefined;
            updateActions();
            void flush();
        });
    }

    async function flush() {
        if (sending || !editable || (!pending.length && !command)) return;
        const action = { round, clear: false, segments: [] };
        if (pending.length) {
            const first = pending[0].id;
            const next = pending.findIndex(item => item.id !== first);
            action.strokeId = first;
            action.segments = pending.splice(0, next < 0 ? 48 : Math.min(48, next)).map(item => item.segment);
        } else {
            if (command === 'clear') action.clear = true;
            else action.edit = command;
            command = undefined;
        }
        const request = { expected: sequence + 1, segments: action.segments, replacing: action.clear || !!action.edit };
        sending = request;
        updateActions();
        try {
            const delta = await send(action);
            if (sending === request) receive(delta, true);
        } catch (error) {
            if (sending === request) {
                showError(error.message || 'Không gửi được nét vẽ.');
                command = undefined;
                await refresh();
            }
        } finally {
            if (sending === request) {
                sending = undefined;
                redraw();
                updateActions();
            }
        }
    }

    function queue(action, button) {
        if (button.disabled) return;
        command = action;
        updateActions();
        void flush();
    }

    undo.addEventListener('click', () => queue('UNDO', undo));
    redo.addEventListener('click', () => queue('REDO', redo));
    clear.addEventListener('click', () => queue('clear', clear));

    function showOptions(next) {
        openTool = next;
        for (const [name, button] of [['pen', pen], ['eraser', eraser]]) {
            find(name + '-options').hidden = next !== name;
            button.setAttribute('aria-expanded', String(next === name));
        }
    }

    function updateTools() {
        pen.dataset.active = String(!erasing);
        eraser.dataset.active = String(erasing);
        palette.querySelectorAll('button').forEach((button, index) => button.setAttribute('aria-pressed', String(color === index)));
        tools.querySelectorAll('[data-width]').forEach(button => {
            const selected = button.parentElement.hasAttribute('data-pen-widths') ? width : eraserWidth;
            button.setAttribute('aria-pressed', String(Number(button.dataset.width) === selected));
        });
    }

    pen.addEventListener('click', () => { erasing = false; updateTools(); showOptions(openTool === 'pen' ? undefined : 'pen'); });
    eraser.addEventListener('click', () => { erasing = true; updateTools(); showOptions(openTool === 'eraser' ? undefined : 'eraser'); });
    document.addEventListener('click', event => { if (openTool && !tools.contains(event.target)) showOptions(); });
    root.addEventListener('keydown', event => {
        if (!editable) return;
        if (event.key === 'Escape' && openTool) {
            (openTool === 'pen' ? pen : eraser).focus();
            showOptions();
            event.preventDefault();
        }
        const key = event.key.toLowerCase();
        if (!event.ctrlKey && !event.metaKey || (key !== 'z' && key !== 'y')) return;
        event.preventDefault();
        if (key === 'y' || event.shiftKey) queue('REDO', redo);
        else queue('UNDO', undo);
    });

    function chooseColor(index) {
        color = index;
        erasing = false;
        updateTools();
    }

    colors.forEach((swatch, index) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'ar-btn ar-btn--sm';
        const angle = index * 2 * Math.PI / colors.length - Math.PI / 2;
        button.style.setProperty('--swatch', swatch);
        button.style.setProperty('--swatch-x', 86 + Math.cos(angle) * 61 + 'px');
        button.style.setProperty('--swatch-y', 86 + Math.sin(angle) * 61 + 'px');
        button.dataset.color = index;
        button.setAttribute('aria-label', colorNames[index]);
        button.title = colorNames[index];
        button.addEventListener('click', () => chooseColor(index));
        palette.append(button);
    });
    function chooseAt(event) {
        const swatch = document.elementFromPoint(event.clientX, event.clientY)?.closest('[data-color]');
        if (swatch && palette.contains(swatch)) chooseColor(Number(swatch.dataset.color));
    }
    palette.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        chooseAt(event);
        palette.setPointerCapture(event.pointerId);
    });
    palette.addEventListener('pointermove', event => { if (palette.hasPointerCapture(event.pointerId)) chooseAt(event); });
    palette.addEventListener('pointerup', event => { if (palette.hasPointerCapture(event.pointerId)) chooseAt(event); });

    for (const [tool, sizes] of [['pen', [3, 8, 14, 22]], ['eraser', [6, 12, 18, 24]]]) {
        const group = find(tool + '-widths');
        for (const size of sizes) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'ar-btn ar-btn--sm';
            button.dataset.width = size;
            button.textContent = size + ' px';
            button.addEventListener('click', () => {
                if (tool === 'pen') width = size;
                else eraserWidth = size;
                updateTools();
            });
            group.append(button);
        }
    }
    updateTools();
    const tick = window.setInterval(() => { void flush(); }, 110);
    return { restore, receive, stop: () => window.clearInterval(tick) };
}
