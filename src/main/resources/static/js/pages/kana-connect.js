(function () {
    'use strict';
    var root = document.getElementById('kanaChallenge');
    if (!root || !window.KanaConnect || !window.JlasAuth) return;
    var toggle = document.getElementById('kanaOpen'), panel = document.getElementById('kanaPanel');
    var board = document.getElementById('kanaBoard'), message = document.getElementById('kanaMessage');
    var challenge, state, busy = false, activePointer = null, dragged = false, pointerStart;
    var suppressClick = false, expiryTimer, pendingCheck, audioContext;
    // null until the first load: the server then opens the learner's learning-path level (MIX without one).
    var level = null, levels = document.getElementById('kanaLevels');
    function say(text, error) { message.textContent = text; message.classList.toggle('is-error', !!error); }
    function storageKey() { return 'jlas.kana-connect.v2.' + challenge.id; }
    function save() { try { localStorage.setItem(storageKey(), JSON.stringify(state.path)); } catch (e) { /* Optional. */ } }
    async function api(action, body) {
        var response = await fetch('/api/mission/kana-connect/' + action, {
            method: body ? 'POST' : 'GET', headers: Object.assign({}, JlasAuth.authHeaders(), body ? { 'Content-Type': 'application/json' } : {}),
            body: body ? JSON.stringify(body) : undefined
        });
        var payload = await response.json();
        if (!response.ok || !payload || !payload.success) throw new Error((payload && payload.message) || 'Chưa thể tải thử thách. Hãy thử lại.');
        return payload.data;
    }
    function tone() {
        if (document.getElementById('kanaSound').getAttribute('aria-pressed') !== 'true') return;
        try {
            if (!audioContext) audioContext = new (window.AudioContext || window.webkitAudioContext)();
            audioContext.resume().catch(function () {});
            var oscillator = audioContext.createOscillator(), gain = audioContext.createGain();
            oscillator.connect(gain); gain.connect(audioContext.destination); oscillator.frequency.value = 660;
            gain.gain.setValueAtTime(0.045, audioContext.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, audioContext.currentTime + .12);
            oscillator.start(); oscillator.stop(audioContext.currentTime + .13);
        } catch (e) { /* Optional sound. */ }
    }
    function clock() {
        if (!challenge) return;
        var seconds = challenge.completed ? challenge.elapsedSeconds : Math.max(0, Math.floor((Date.now() - Date.parse(challenge.startedAt)) / 1000));
        document.getElementById('kanaClock').textContent = String(Math.floor(seconds / 60)).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0');
    }
    function center(id) { var c = state.cells[id]; return [c.col * 100 + 50, c.row * 100 + 50]; }
    function svgNode(name, attrs) {
        var node = document.createElementNS('http://www.w3.org/2000/svg', name);
        Object.keys(attrs).forEach(function (key) { node.setAttribute(key, attrs[key]); });
        return node;
    }
    // A word path is drawn as a rounded "snake" behind the letters, with a chevron at each
    // cell boundary pointing in reading order.
    function snake(svg, path, kind) {
        if (!path.length) return;
        var pts = path.map(function (id) { return center(id).join(','); }).join(' ');
        // A lone point still needs a visible stroke, so repeat it as a zero-length segment.
        if (path.length === 1) pts += ' ' + pts;
        var group = svgNode('g', { 'class': 'kc-snake kc-snake--' + kind });
        group.append(svgNode('polyline', { 'class': 'kc-snake__edge', points: pts }), svgNode('polyline', { 'class': 'kc-snake__fill', points: pts }));
        for (var i = 1; i < path.length; i++) {
            var a = center(path[i - 1]), b = center(path[i]);
            var angle = Math.atan2(b[1] - a[1], b[0] - a[0]) * 180 / Math.PI;
            group.append(svgNode('path', { 'class': 'kc-snake__arrow', d: 'M-6,-9 L4,0 L-6,9',
                transform: 'translate(' + (a[0] + b[0]) / 2 + ',' + (a[1] + b[1]) / 2 + ') rotate(' + angle + ')' }));
        }
        svg.append(group);
    }
    function draw() {
        board.querySelectorAll('[data-cell]').forEach(function (button) {
            var id = Number(button.dataset.cell), selected = state.path.indexOf(id) >= 0, solved = state.locked.has(id);
            button.classList.toggle('is-selected', selected);
            button.classList.toggle('is-solved', solved);
            button.setAttribute('aria-pressed', String(selected || solved));
            // Keep the focused tile enabled while validating a partial keyboard path.
            button.disabled = state.completed || solved;
        });
        var svg = board.querySelector('svg'); svg.replaceChildren();
        state.paths.forEach(function (solution) { snake(svg, solution.path, 'found'); });
        snake(svg, state.path, 'active');
        document.getElementById('kanaSelected').textContent = state.text() || '\u00a0';
        document.getElementById('kanaWordProgress').textContent = state.solvedWordIds.length + '/' + state.words.length + ' từ';
        document.getElementById('kanaStatus').textContent = state.solvedWordIds.length + '/' + state.words.length + ' từ';
        toggle.innerHTML = '<i class="fa-solid ' + (state.completed ? 'fa-check' : 'fa-play') + '" aria-hidden="true"></i> ' + (state.completed ? 'Xem lại bàn chơi' : 'Chơi thử thách');
        board.setAttribute('aria-busy', String(busy)); clock();
    }
    function wordList() {
        var list = document.getElementById('kanaWords'); list.replaceChildren();
        challenge.words.forEach(function (word) {
            var solution = state.paths.find(function (s) { return s.wordId === word.id; });
            var found = !!(solution && word.expression);
            var item = document.createElement('li');
            item.className = 'kc-word' + (found ? ' is-found' : '');
            var slots = document.createElement('span'); slots.className = 'kc-slots'; slots.lang = 'ja'; slots.setAttribute('aria-hidden', 'true');
            for (var i = 0; i < word.kanaCount; i++) {
                var slot = document.createElement('span'); slot.className = 'kc-slot';
                var kana = found && solution.path[i] != null ? state.cells[solution.path[i]].kana : '';
                if (kana.length > 1) slot.classList.add('is-compound');
                slot.textContent = kana; slots.append(slot);
            }
            item.append(slots);
            if (found) {
                var gloss = document.createElement('span'); gloss.className = 'kc-gloss';
                var text = document.createElement('strong'); text.lang = 'ja'; text.textContent = word.expression;
                gloss.append(text, document.createTextNode(' ' + word.meaning));
                item.append(gloss);
                item.title = word.reading;
                item.setAttribute('aria-label', word.expression + ' (' + word.reading + '): ' + word.meaning);
            } else {
                item.setAttribute('aria-label', 'Từ ' + word.kanaCount + ' kana chưa tìm thấy');
            }
            list.append(item);
        });
    }
    function render() {
        if (challenge.words.length < 8 || challenge.cells.filter(function (c) { return c.kana !== null; }).length < challenge.gridSize * challenge.gridSize * .8) {
            throw new Error('Bàn chơi chưa đủ từ. Hãy tải lại thử thách.');
        }
        state = new KanaConnect.GameState(challenge.cells); state.sync(challenge);
        board.style.setProperty('--grid-size', challenge.gridSize); board.replaceChildren();
        challenge.cells.forEach(function (cell) {
            if (cell.kana === null) {
                var wall = document.createElement('span'); wall.className = 'kc-wall'; wall.setAttribute('aria-hidden', 'true'); board.append(wall); return;
            }
            var button = document.createElement('button'); button.type = 'button';
            button.className = 'kc-tile' + (cell.kana.length > 1 ? ' is-compound' : ''); button.dataset.cell = cell.id;
            button.setAttribute('aria-label', cell.kana + ', hàng ' + (cell.row + 1) + ', cột ' + (cell.col + 1));
            var letter = document.createElement('span'); letter.lang = 'ja'; letter.textContent = cell.kana;
            button.append(letter); board.append(button);
        });
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.classList.add('kc-lines');
        svg.setAttribute('viewBox', '0 0 ' + challenge.gridSize * 100 + ' ' + challenge.gridSize * 100);
        svg.setAttribute('aria-hidden', 'true'); board.append(svg);
        try { state.restore(JSON.parse(localStorage.getItem(storageKey()) || '[]')); } catch (e) { /* Optional. */ }
        wordList(); draw(); clearTimeout(expiryTimer);
        expiryTimer = setTimeout(function () { if (!panel.hidden) load(); else challenge = null; }, Math.max(1000, Math.min(2147483647, Date.parse(challenge.expiresAt) - Date.now() + 100)));
    }
    function markLevels() {
        levels.querySelectorAll('[data-level]').forEach(function (button) {
            var value = button.dataset.level, isDefault = challenge && value === challenge.defaultLevel;
            button.setAttribute('aria-pressed', String(value === level));
            button.classList.toggle('is-default', !!isDefault);
            button.title = isDefault ? 'Cấp độ theo lộ trình của bạn' : '';
        });
    }
    function duration(seconds) { return String(Math.floor(seconds / 60)).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0'); }
    function rankRow(entry) {
        var item = document.createElement('li'); item.className = 'kc-rank__row' + (entry.me ? ' is-me' : '');
        var position = document.createElement('span'); position.className = 'kc-rank__pos'; position.textContent = entry.rank;
        if (entry.rank <= 3) position.classList.add('is-top' + entry.rank);
        var name = document.createElement('span'); name.className = 'kc-rank__name'; name.textContent = entry.me ? entry.name + ' (bạn)' : entry.name;
        var time = document.createElement('time'); time.className = 'kc-rank__time'; time.textContent = duration(entry.elapsedSeconds);
        item.append(position, name, time); return item;
    }
    // Today's ranking for the board on screen; a late response for a previous level is ignored.
    async function ranking() {
        var forLevel = challenge.jlptLevel, list = document.getElementById('kanaRankList');
        var count = document.getElementById('kanaRankCount'), me = document.getElementById('kanaRankMe');
        try {
            var data = await api('leaderboard?level=' + encodeURIComponent(forLevel));
            if (!challenge || challenge.jlptLevel !== forLevel) return;
            count.textContent = data.finishers ? data.finishers + ' người hoàn thành' : '';
            list.replaceChildren();
            if (!data.top.length) {
                var empty = document.createElement('li'); empty.className = 'kc-rank__empty';
                empty.textContent = 'Chưa có ai hoàn thành bàn này. Hãy là người đầu tiên!'; list.append(empty);
            }
            data.top.forEach(function (entry) { list.append(rankRow(entry)); });
            var outside = data.me && !data.top.some(function (entry) { return entry.me; });
            me.hidden = !outside;
            if (outside) me.textContent = 'Hạng của bạn: #' + data.me.rank + ' · ' + duration(data.me.elapsedSeconds);
        } catch (error) {
            if (challenge && challenge.jlptLevel === forLevel) { list.replaceChildren(); count.textContent = 'Chưa tải được bảng xếp hạng'; me.hidden = true; }
        }
    }
    async function load() {
        if (busy) return;
        busy = true; board.hidden = true; document.getElementById('kanaPlay').hidden = true;
        document.getElementById('kanaRetry').hidden = true; say('Đang chuẩn bị bàn chơi…');
        try {
            challenge = KanaConnect.normalizeChallenge(await api('today' + (level ? '?level=' + encodeURIComponent(level) : '')));
            level = challenge.jlptLevel; markLevels(); render();
            board.hidden = false; document.getElementById('kanaPlay').hidden = false; say(''); ranking();
        }
        catch (error) { say(error.message, true); document.getElementById('kanaRetry').hidden = false; }
        finally { busy = false; if (state && board.querySelector('svg')) draw(); }
    }
    async function submit(clearWrong) {
        if (busy || !state || state.completed || state.path.length < 2) return;
        var focusedCell = board.contains(document.activeElement) ? document.activeElement.dataset.cell : null;
        busy = true; draw();
        try {
            var result = await api('submit', { challengeId: challenge.id, path: state.path.slice() });
            challenge = KanaConnect.normalizeChallenge(result && result.challenge);
            if (result.correct) { state.sync(challenge); wordList(); save(); tone(); say(''); if (challenge.completed) ranking(); }
            else if (clearWrong) { state.reset(); save(); say('Thử một đường nối khác nhé.'); }
        } catch (error) { say(error.message, true); document.getElementById('kanaRetry').hidden = false; }
        finally {
            busy = false; draw();
            if (focusedCell != null) {
                var focused = board.querySelector('[data-cell="' + focusedCell + '"]');
                var next = focused && !focused.disabled ? focused : board.querySelector('button:not(:disabled)');
                (next || toggle).focus({ preventScroll: true });
            }
        }
    }
    function select(id, restart) {
        clearTimeout(pendingCheck);
        if (busy || !state || state.completed || state.locked.has(id) || !state.cells[id] || state.cells[id].kana === null) return;
        if (restart && state.path.length && !state.path.includes(id) && !KanaConnect.adjacent(state.cells[state.path[state.path.length - 1]], state.cells[id])) state.reset();
        if (restart && state.path.length > 2 && state.path[0] === id) state.reset();
        if (state.select(id)) { save(); draw(); say(''); }
    }
    function cellAt(x, y) {
        var element = document.elementFromPoint(x, y), cell = element && element.closest('[data-cell]');
        return cell && board.contains(cell) ? Number(cell.dataset.cell) : null;
    }
    board.addEventListener('pointerdown', function (event) {
        if (busy || !state || state.completed || activePointer !== null || (event.pointerType === 'mouse' && event.button !== 0)) return;
        var id = cellAt(event.clientX, event.clientY); if (id === null || state.locked.has(id)) return;
        activePointer = event.pointerId; dragged = false; pointerStart = { x: event.clientX, y: event.clientY }; suppressClick = false;
        select(id, true); board.setPointerCapture(event.pointerId);
    });
    board.addEventListener('pointermove', function (event) {
        if (event.pointerId !== activePointer) return;
        if (Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) > 8) dragged = true;
        var id = cellAt(event.clientX, event.clientY); if (id !== null) select(id, false);
    });
    board.addEventListener('pointerup', function (event) {
        if (event.pointerId !== activePointer) return;
        activePointer = null; suppressClick = true;
        if (board.hasPointerCapture(event.pointerId)) board.releasePointerCapture(event.pointerId);
        if (dragged) submit(true); else pendingCheck = setTimeout(function () { submit(false); }, 180);
    });
    function cancelPointer(event) {
        if (activePointer === null || (event && event.pointerId !== activePointer)) return;
        activePointer = null; suppressClick = true; clearTimeout(pendingCheck); if (state) { save(); draw(); }
    }
    board.addEventListener('pointercancel', cancelPointer); board.addEventListener('lostpointercapture', cancelPointer);
    board.addEventListener('click', function (event) {
        var cell = event.target.closest('[data-cell]'); if (!cell) return;
        if (event.detail !== 0 && suppressClick) { suppressClick = false; return; }
        select(Number(cell.dataset.cell), true); pendingCheck = setTimeout(function () { submit(false); }, 180);
    });
    board.addEventListener('keydown', function (event) {
        var cell = event.target.closest('[data-cell]'); if (!cell || !state) return;
        if (event.key === 'Escape') { clearTimeout(pendingCheck); state.reset(); save(); draw(); return; }
        if (event.key === 'Backspace') { event.preventDefault(); clearTimeout(pendingCheck); state.undo(); save(); draw(); return; }
        var delta = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }[event.key];
        if (!delta) return; event.preventDefault();
        var from = state.cells[Number(cell.dataset.cell)];
        var next = state.cells.find(function (c) { return c.row === from.row + delta[0] && c.col === from.col + delta[1] && c.kana !== null && !state.locked.has(c.id); });
        if (next) board.querySelector('[data-cell="' + next.id + '"]').focus();
    });
    toggle.addEventListener('click', function () { var open = panel.hidden; panel.hidden = !open; toggle.setAttribute('aria-expanded', String(open)); if (open && (!challenge || Date.now() >= Date.parse(challenge.expiresAt))) load(); });
    document.getElementById('kanaSound').addEventListener('click', function () {
        var on = this.getAttribute('aria-pressed') !== 'true'; this.setAttribute('aria-pressed', String(on));
        this.setAttribute('aria-label', on ? 'Tắt âm thanh' : 'Bật âm thanh');
        this.querySelector('i').className = 'fa-solid ' + (on ? 'fa-volume-high' : 'fa-volume-xmark');
    });
    document.getElementById('kanaRetry').addEventListener('click', load);
    levels.addEventListener('click', function (event) {
        var button = event.target.closest('[data-level]');
        if (!button || busy || (challenge && button.dataset.level === level && !document.getElementById('kanaPlay').hidden)) return;
        clearTimeout(pendingCheck); level = button.dataset.level; markLevels(); load();
    });
    document.addEventListener('visibilitychange', function () { if (document.hidden) cancelPointer(); else if (!panel.hidden && challenge && Date.now() >= Date.parse(challenge.expiresAt)) load(); });
    setInterval(clock, 1000);
}());
