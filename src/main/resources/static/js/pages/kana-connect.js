(function () {
    'use strict';
    var root = document.getElementById('kanaChallenge');
    if (!root || !window.KanaConnect || !window.JlasAuth) return;
    // Dedicated page: the board loads straight away (the Goals page only links here from its missions).
    var board = document.getElementById('kanaBoard'), message = document.getElementById('kanaMessage');
    var challenge, state, busy = false, activePointer = null, dragged = false, pointerStart;
    var suppressClick = false, expiryTimer, pendingCheck;
    // True after pressing Gợi ý: the empty slots of the word list become buttons until one is picked.
    var picking = false;
    // The board stays locked (no kana, no clock) until Bắt đầu; a tab switch resumes on return only if it was running.
    var resumeOnReturn = false;
    function isLocked() { return !!challenge && !challenge.completed && !challenge.running; }
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
    // Each level keeps its own play time on the server; it only runs while that board is on screen.
    function accept(data) {
        var next = KanaConnect.normalizeChallenge(data);
        next.receivedAt = Date.now();
        return next;
    }
    function playedMs() {
        return Math.max(0, (challenge.playedMs || 0) + (challenge.running ? Date.now() - challenge.receivedAt : 0));
    }
    function pauseClock() {
        if (!challenge || challenge.completed || !challenge.running) return;
        resumeOnReturn = true;
        challenge.playedMs = playedMs(); challenge.running = false; challenge.receivedAt = Date.now();
        try {
            fetch('/api/mission/kana-connect/pause', {
                method: 'POST', keepalive: true, body: JSON.stringify({ challengeId: challenge.id }),
                headers: Object.assign({ 'Content-Type': 'application/json' }, JlasAuth.authHeaders())
            }).catch(function () { /* the next open resumes from the server's own state */ });
        } catch (e) { /* ignore */ }
    }
    async function resumeClock() {
        if (!challenge || challenge.completed || busy || !resumeOnReturn) return;
        resumeOnReturn = false;
        if (Date.now() >= Date.parse(challenge.expiresAt)) { load(); return; }
        try {
            var fresh = accept(await api('start', { challengeId: challenge.id }));
            if (fresh.id !== challenge.id) { load(); return; }
            ['playedMs', 'running', 'receivedAt', 'nextHintAt'].forEach(function (key) { challenge[key] = fresh[key]; });
            clock();
        } catch (error) { /* keep the paused clock; the next move resumes it */ }
    }
    function clock() {
        if (!challenge) return;
        var seconds = challenge.completed ? challenge.elapsedSeconds : Math.floor(playedMs() / 1000);
        document.getElementById('kanaClock').textContent = String(Math.floor(seconds / 60)).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0');
        hintButton();
    }
    // One hint every 30 seconds of play: the server decides; the button only mirrors its next opening time.
    function hintButton() {
        var button = document.getElementById('kanaHint'), label = document.getElementById('kanaHintLabel');
        if (!challenge || challenge.completed || !challenge.nextHintAt) {
            if (picking) setPicking(false);
            button.disabled = true; label.textContent = challenge && challenge.completed ? 'Gợi ý' : 'Hết gợi ý'; return;
        }
        // A locked board has no running clock, so its hint countdown would only stand still.
        var wait = isLocked() ? 0 : Math.ceil((Date.parse(challenge.nextHintAt) - Date.now()) / 1000);
        button.disabled = busy || wait > 0 || isLocked();
        button.setAttribute('aria-pressed', String(picking));
        label.textContent = picking ? 'Hủy chọn' : wait > 0 ? 'Gợi ý ' + Math.floor(wait / 60) + ':' + String(wait % 60).padStart(2, '0') : 'Gợi ý';
        button.setAttribute('aria-label', picking ? 'Hủy chọn ô gợi ý' : wait > 0 ? 'Gợi ý mở sau ' + wait + ' giây' : 'Chọn một ô trống để mở chữ');
    }
    // Picking mode: every empty slot of an unsolved word turns into a button (see wordList).
    function setPicking(on) {
        picking = on;
        document.getElementById('kanaWords').classList.toggle('is-picking', on);
        if (challenge && state) wordList();
    }
    function togglePicking() {
        if (busy || !challenge || challenge.completed) return;
        setPicking(!picking); hintButton();
        say(picking ? 'Chạm vào một ô trống bất kỳ trong danh sách từ để mở chữ ở ô đó.' : '');
        if (picking) { var first = document.querySelector('#kanaWords [data-pick-index]'); if (first) first.focus({ preventScroll: true }); }
    }
    async function openSlot(wordId, index) {
        if (busy || !challenge || challenge.completed) return;
        busy = true; hintButton();
        var keep = state.path.slice();
        try {
            challenge = accept(await api('hint', { challengeId: challenge.id, wordId: wordId, index: index }));
            state.sync(challenge); state.restore(keep); save(); say('');
        } catch (error) { say(error.message, true); }
        finally { busy = false; setPicking(false); draw(); hintButton(); }
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
            button.disabled = state.completed || solved || isLocked();
        });
        var svg = board.querySelector('svg'); svg.replaceChildren();
        state.paths.forEach(function (solution) { snake(svg, solution.path, 'found'); });
        snake(svg, state.path, 'active');
        document.getElementById('kanaSelected').textContent = state.text() || '\u00a0';
        document.getElementById('kanaWordProgress').textContent = state.solvedWordIds.length + '/' + state.words.length + ' từ';
        board.setAttribute('aria-busy', String(busy)); clock();
    }
    function wordList() {
        var list = document.getElementById('kanaWords'); list.replaceChildren();
        challenge.words.forEach(function (word) {
            var solution = state.paths.find(function (s) { return s.wordId === word.id; });
            var found = !!(solution && word.expression);
            var item = document.createElement('li');
            item.className = 'kc-word' + (found ? ' is-found' : '');
            var slots = document.createElement('span'); slots.className = 'kc-slots'; slots.lang = 'ja';
            // While picking a hint the empty slots are buttons, so the row is no longer decoration.
            if (found || !picking) slots.setAttribute('aria-hidden', 'true');
            for (var i = 0; i < word.kanaCount; i++) {
                var kana = found && solution.path[i] != null ? state.cells[solution.path[i]].kana : '';
                var hint = found ? null : challenge.hints.find(function (h) { return h.wordId === word.id && h.index === i; });
                var slot;
                if (!found && !hint && picking) {
                    slot = document.createElement('button'); slot.type = 'button'; slot.className = 'kc-slot is-pickable';
                    slot.dataset.pickWord = word.id; slot.dataset.pickIndex = i;
                    slot.setAttribute('aria-label', 'Mở chữ thứ ' + (i + 1) + ' của từ ' + word.kanaCount + ' kana');
                } else {
                    slot = document.createElement('span'); slot.className = 'kc-slot';
                }
                if (hint) { kana = hint.kana; slot.classList.add('is-hint'); }
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
        wordList(); draw(); gate(); clearTimeout(expiryTimer);
        expiryTimer = setTimeout(load, Math.max(1000, Math.min(2147483647, Date.parse(challenge.expiresAt) - Date.now() + 100)));
    }
    /** Bắt đầu on a fresh board, Tiếp tục on one already played; hidden once the clock runs or the board is done. */
    function gate() {
        var locked = isLocked(), box = document.getElementById('kanaGate');
        board.classList.toggle('is-locked', locked);
        box.hidden = !locked;
        box.classList.remove('is-unlocking');
        box.querySelector('.kc-lock i').className = 'fa-solid fa-lock';
        if (locked) document.getElementById('kanaStartLabel').textContent = (challenge.playedMs || 0) > 0 ? 'Tiếp tục' : 'Bắt đầu';
    }
    // The lock springs open and the chains slide off while the board is fetched; the board shows once both are done.
    var UNLOCK_MS = 700;
    function unlockAnimation() {
        var box = document.getElementById('kanaGate');
        box.classList.add('is-unlocking');
        box.querySelector('.kc-lock i').className = 'fa-solid fa-lock-open';
        return new Promise(function (resolve) { setTimeout(resolve, UNLOCK_MS); });
    }
    async function start() {
        if (busy || !isLocked()) return;
        busy = true; document.getElementById('kanaStart').disabled = true;
        try {
            var results = await Promise.all([api('start', { challengeId: challenge.id }), unlockAnimation()]);
            challenge = accept(results[0]);
            render(); say('');
            var first = board.querySelector('[data-cell]:not([disabled])');
            if (first) first.focus({ preventScroll: true });
        } catch (error) { say(error.message, true); gate(); }
        finally { busy = false; document.getElementById('kanaStart').disabled = false; draw(); }
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
    // Titles for the five fastest, highest tier first (rank 1 is "Chí tôn").
    var TITLES = [
        ['Chí tôn', 'Tồn tại ở đỉnh cao nhất, được vạn người kính sợ'],
        ['Vô thượng cường giả', 'Đứng trên gần như toàn bộ cường giả'],
        ['Tuyệt thế cường giả', 'Sức mạnh hiếm người sánh kịp'],
        ['Đỉnh cấp cường giả', 'Cao thủ hàng đầu trong một lĩnh vực'],
        ['Cường giả', 'Người sở hữu sức mạnh vượt trội']
    ];
    var PODIUM_ORDER = [4, 2, 1, 3, 5];
    function initials(name) {
        return String(name || '?').trim().split(/\s+/).slice(-2).map(function (part) { return part.charAt(0); }).join('').toUpperCase() || '?';
    }
    function avatar(entry) {
        var holder = document.createElement('span'); holder.className = 'kc-podium__avatar'; holder.setAttribute('aria-hidden', 'true');
        var fallback = function () { holder.replaceChildren(); holder.textContent = entry ? initials(entry.name) : '?'; };
        var url = entry && entry.avatarUrl;
        if (url && /^(https?:\/\/|\/)/.test(url)) {
            var image = document.createElement('img'); image.alt = ''; image.loading = 'lazy'; image.referrerPolicy = 'no-referrer';
            image.addEventListener('error', fallback); image.src = url; holder.append(image);
        } else fallback();
        return holder;
    }
    function podiumSpot(rank, entry) {
        var item = document.createElement('li');
        item.className = 'kc-podium__spot is-rank' + rank + (entry ? '' : ' is-empty') + (entry && entry.me ? ' is-me' : '');
        if (rank <= 3) {
            var crown = document.createElement('i'); crown.className = 'fa-solid fa-crown kc-podium__crown'; crown.setAttribute('aria-hidden', 'true');
            item.append(crown);
        }
        var badge = document.createElement('span'); badge.className = 'kc-podium__badge'; badge.textContent = rank;
        // Khung avatar + màu tên người chơi mua ở Cửa hàng (components/avatar-frame.js).
        var frames = window.JlasAvatarFrame;
        var framed = document.createElement('span'); framed.className = 'af-host'; framed.append(avatar(entry));
        var face = document.createElement('span'); face.className = 'kc-podium__face'; face.append(framed, badge);
        if (frames && entry) frames.mount(framed, entry.avatarFrame);
        var name = document.createElement('strong'); name.className = 'kc-podium__name';
        name.textContent = entry ? (entry.me ? entry.name + ' (bạn)' : entry.name) : 'Còn trống';
        if (frames && entry) frames.applyNameStyle(name, entry.nameStyle);
        var title = document.createElement('span'); title.className = 'kc-podium__title';
        title.textContent = TITLES[rank - 1][0]; title.title = TITLES[rank - 1][1];
        var time = document.createElement('time'); time.className = 'kc-podium__time';
        time.textContent = entry ? duration(entry.elapsedSeconds) : '--:--';
        item.append(face, name, title, time);
        item.setAttribute('aria-label', 'Hạng ' + rank + ', ' + TITLES[rank - 1][0] + ': '
            + (entry ? entry.name + ', ' + duration(entry.elapsedSeconds) : 'còn trống'));
        return item;
    }
    // Today's ranking for the board on screen; a late response for a previous level is ignored.
    async function ranking() {
        var forLevel = challenge.jlptLevel, podium = document.getElementById('kanaPodium');
        var count = document.getElementById('kanaRankCount'), me = document.getElementById('kanaRankMe');
        document.getElementById('kanaPodiumLevel').textContent = '· ' + (forLevel === 'MIX' ? 'Trộn' : forLevel);
        try {
            var data = await api('leaderboard?level=' + encodeURIComponent(forLevel));
            if (!challenge || challenge.jlptLevel !== forLevel) return;
            count.textContent = data.finishers ? data.finishers + ' người hoàn thành' : 'Chưa có ai hoàn thành, hãy là người đầu tiên!';
            podium.replaceChildren();
            PODIUM_ORDER.forEach(function (rank) { podium.append(podiumSpot(rank, data.top[rank - 1])); });
            // Only the top five are shown; a learner below them still sees their own rank.
            var outside = data.me && !data.top.slice(0, 5).some(function (entry) { return entry.me; });
            me.hidden = !outside;
            if (outside) me.textContent = 'Hạng của bạn: #' + data.me.rank + ' · ' + duration(data.me.elapsedSeconds);
        } catch (error) {
            if (challenge && challenge.jlptLevel === forLevel) { podium.replaceChildren(); count.textContent = 'Chưa tải được bảng xếp hạng'; me.hidden = true; }
        }
    }
    async function load() {
        if (busy) return;
        busy = true; board.hidden = true; document.getElementById('kanaPlay').hidden = true; setPicking(false);
        document.getElementById('kanaRetry').hidden = true; say('Đang chuẩn bị bàn chơi…');
        try {
            challenge = accept(await api('today' + (level ? '?level=' + encodeURIComponent(level) : '')));
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
            challenge = accept(result && result.challenge);
            if (result.correct) { state.sync(challenge); wordList(); save(); say(''); if (challenge.completed) ranking(); }
            else if (clearWrong) { state.reset(); save(); say('Thử một đường nối khác nhé.'); }
        } catch (error) { say(error.message, true); document.getElementById('kanaRetry').hidden = false; }
        finally {
            busy = false; draw();
            if (focusedCell != null) {
                var focused = board.querySelector('[data-cell="' + focusedCell + '"]');
                var next = focused && !focused.disabled ? focused : board.querySelector('button:not(:disabled)');
                if (next) next.focus({ preventScroll: true });
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
    document.getElementById('kanaRetry').addEventListener('click', load);
    document.getElementById('kanaHint').addEventListener('click', togglePicking);
    document.getElementById('kanaWords').addEventListener('click', function (event) {
        var slot = event.target.closest('[data-pick-index]');
        if (slot && picking) openSlot(Number(slot.dataset.pickWord), Number(slot.dataset.pickIndex));
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && picking && !busy) { setPicking(false); hintButton(); say(''); }
    });
    // Read-only hooks for the dev demo panel (kana-demo-panel.js); it never changes state directly.
    window.JlasKana = {
        current: function () { return challenge ? { id: challenge.id, level: challenge.jlptLevel, completed: challenge.completed } : null; },
        reload: function () { challenge = null; return load(); }
    };
    levels.addEventListener('click', function (event) {
        var button = event.target.closest('[data-level]');
        if (!button || busy || (challenge && button.dataset.level === level && !document.getElementById('kanaPlay').hidden)) return;
        clearTimeout(pendingCheck); level = button.dataset.level; markLevels(); load();
    });
    // Away from the page (other tab, minimised, closed): stop this board's clock; back: resume it.
    document.getElementById('kanaStart').addEventListener('click', start);
    document.addEventListener('visibilitychange', function () {
        if (document.hidden) { cancelPointer(); pauseClock(); } else resumeClock();
    });
    window.addEventListener('pagehide', pauseClock);
    setInterval(clock, 1000);
    load();
}());
