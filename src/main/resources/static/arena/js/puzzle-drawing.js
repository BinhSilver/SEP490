const colors = ['#262238', '#cb3446', '#216bc2', '#188554', '#c46b15', '#7550bf', '#843f29'];
const colorNames = ['Đen', 'Đỏ', 'Xanh dương', 'Xanh lá', 'Cam', 'Tím', 'Nâu'];
const sizes = { pen: [3, 8, 14, 22], eraser: [6, 12, 18, 24] };
const sizeNames = ['Nhỏ', 'Vừa', 'To', 'Rất to'];
// Colour distance (per channel, after blending with the white board) still counted as the same area: it swallows
// the soft edge of a stroke, so a fill reaches the line instead of leaving a pale rim.
const FILL_TOLERANCE = 48;

/** Paint-bucket fill on the canvas pixels at (x, y), as every player replays it in board order. */
export function floodFill(ctx, width, height, x, y, hex) {
    const px = Math.min(width - 1, Math.max(0, Math.round(x)));
    const py = Math.min(height - 1, Math.max(0, Math.round(y)));
    const image = ctx.getImageData(0, 0, width, height);
    const data = image.data;
    const fill = [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
    // Transparent pixels show the white board behind the canvas, so colours are compared as seen.
    const seen = index => {
        const alpha = data[index + 3] / 255;
        return [0, 1, 2].map(channel => data[index + channel] * alpha + 255 * (1 - alpha));
    };
    const target = seen((py * width + px) * 4);
    const close = (a, b) => Math.abs(a[0] - b[0]) <= FILL_TOLERANCE && Math.abs(a[1] - b[1]) <= FILL_TOLERANCE
        && Math.abs(a[2] - b[2]) <= FILL_TOLERANCE;
    if (close(target, fill)) return;
    const visited = new Uint8Array(width * height);
    const stack = [px, py];
    while (stack.length) {
        const sy = stack.pop();
        let sx = stack.pop();
        while (sx >= 0 && !visited[sy * width + sx] && close(seen((sy * width + sx) * 4), target)) sx--;
        sx++;
        let up = false;
        let down = false;
        while (sx < width && !visited[sy * width + sx] && close(seen((sy * width + sx) * 4), target)) {
            const pixel = sy * width + sx;
            visited[pixel] = 1;
            data[pixel * 4] = fill[0];
            data[pixel * 4 + 1] = fill[1];
            data[pixel * 4 + 2] = fill[2];
            data[pixel * 4 + 3] = 255;
            for (const [row, flag] of [[sy - 1, 'up'], [sy + 1, 'down']]) {
                if (row < 0 || row >= height) continue;
                const open = !visited[row * width + sx] && close(seen((row * width + sx) * 4), target);
                const marked = flag === 'up' ? up : down;
                if (open && !marked) stack.push(sx, row);
                if (flag === 'up') up = open; else down = open;
            }
            sx++;
        }
    }
    ctx.putImageData(image, 0, 0);
}

export function mountDrawing(root, send, refresh, showError) {
    const find = name => root.querySelector('[data-' + name + ']');
    const canvas = find('canvas');
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const tools = find('tools');
    const palette = find('colors');
    const widths = find('widths');
    const clear = find('clear');
    const undo = find('undo');
    const redo = find('redo');
    const toolButtons = [...tools.querySelectorAll('[data-tool]')];
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
    let tool = 'pen';
    const width = { pen: 8, eraser: 18 };

    function paint(segment) {
        const x = segment.x * canvas.width / 10000;
        const y = segment.y * canvas.height / 10000;
        if (segment.fill) {
            floodFill(ctx, canvas.width, canvas.height, x, y, colors[segment.color]);
            return;
        }
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
        canvas.dataset.tool = tool;
        canvas.tabIndex = editable ? 0 : -1;
    }

    function restore(view) {
        const changedRound = round !== view.round;
        if (changedRound) {
            round = view.round;
            sequence = -1;
            pending = [];
            sending = command = pointer = undefined;
        }
        editable = view.canDraw;
        if (!editable) {
            pending = [];
            command = pointer = undefined;
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

    function add(from, to, brush, id) {
        if (pending.length >= 240) return;
        const segment = { ...from, toX: to.x, toY: to.y, ...brush };
        pending.push({ id, segment });
        paint(segment);
    }

    canvas.addEventListener('pointerdown', event => {
        if (!editable || command || sending?.replacing || pointer || event.button !== 0) return;
        const start = point(event);
        canvas.focus({ preventScroll: true });
        if (tool === 'fill') {
            // A fill is one point and a stroke of its own, so undo takes it back on its own.
            add(start, start, { color, width: 2, erase: false, fill: true }, ++strokeId);
            updateActions();
            void flush();
            return;
        }
        pointer = {
            id: event.pointerId, last: start, stroke: ++strokeId,
            brush: { color, width: width[tool], erase: tool === 'eraser', fill: false }
        };
        canvas.setPointerCapture(event.pointerId);
        add(start, start, pointer.brush, pointer.stroke);
        updateActions();
    });
    canvas.addEventListener('pointermove', event => {
        if (pointer?.id !== event.pointerId) return;
        const current = point(event);
        add(pointer.last, current, pointer.brush, pointer.stroke);
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
    root.addEventListener('keydown', event => {
        if (!editable) return;
        const key = event.key.toLowerCase();
        if (!event.ctrlKey && !event.metaKey || (key !== 'z' && key !== 'y')) return;
        event.preventDefault();
        if (key === 'y' || event.shiftKey) queue('REDO', redo);
        else queue('UNDO', undo);
    });

    /** Sizes follow the tool: pen and eraser each keep their own; a fill has no size. */
    function renderWidths() {
        widths.hidden = tool === 'fill';
        if (tool === 'fill') return;
        widths.replaceChildren(...sizes[tool].map((size, index) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'ar-btn ar-btn--sm puzzle-width';
            button.dataset.width = size;
            button.style.setProperty('--dot', Math.max(4, Math.round(size * 0.9)) + 'px');
            button.setAttribute('aria-label', (tool === 'pen' ? 'Nét bút ' : 'Cỡ tẩy ') + sizeNames[index].toLowerCase());
            button.title = sizeNames[index];
            button.setAttribute('aria-pressed', String(width[tool] === size));
            button.addEventListener('click', () => {
                width[tool] = size;
                renderWidths();
            });
            return button;
        }));
    }

    function updateTools() {
        toolButtons.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.tool === tool)));
        palette.querySelectorAll('button').forEach((button, index) => {
            button.setAttribute('aria-pressed', String(tool !== 'eraser' && color === index));
        });
        renderWidths();
        updateActions();
    }

    toolButtons.forEach(button => button.addEventListener('click', () => {
        tool = button.dataset.tool;
        updateTools();
    }));

    colors.forEach((swatch, index) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'puzzle-swatch';
        button.style.setProperty('--swatch', swatch);
        button.dataset.color = index;
        button.setAttribute('aria-label', colorNames[index]);
        button.title = colorNames[index];
        button.addEventListener('click', () => {
            color = index;
            // Picking a colour while erasing goes back to the pen with that colour.
            if (tool === 'eraser') tool = 'pen';
            updateTools();
        });
        palette.append(button);
    });
    updateTools();
    const tick = window.setInterval(() => { void flush(); }, 110);
    return { restore, receive, stop: () => window.clearInterval(tick) };
}
