/*
 * Kana Connect beginner tutorial: a multi-step popup with a small practice board.
 * Opens by itself on the first visit (remembered per browser), then from the "Cách chơi" button.
 * The practice board follows the real rules: drag or tap neighbouring tiles (no diagonals),
 * drag back to step back, a word counts only in reading order.
 */
(function () {
    'use strict';
    var dialog = document.getElementById('kanaTutorial');
    if (!dialog || typeof dialog.showModal !== 'function') return;
    var SEEN_KEY = 'jlas.kana-connect.tutorial.v1';
    var title = document.getElementById('kanaTutTitle'), body = document.getElementById('kanaTutBody');
    var stepLabel = document.getElementById('kanaTutStep'), dots = document.getElementById('kanaTutDots');
    var back = document.getElementById('kanaTutBack'), next = document.getElementById('kanaTutNext');
    var index = 0;

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }
    function slots(kana, filled, extraClass) {
        var row = el('span', 'kc-tut-slots' + (extraClass ? ' ' + extraClass : ''));
        row.lang = 'ja';
        kana.forEach(function (k, i) {
            var slot = el('span', 'kc-tut-slot' + (filled[i] ? ' is-filled' : '') + (k.length > 1 ? ' is-compound' : ''), filled[i] ? k : '');
            row.append(slot);
        });
        return row;
    }
    function list(items) {
        var ul = el('ul', 'kc-tut-list');
        items.forEach(function (item) {
            var li = el('li');
            var icon = el('i', item[0]); icon.setAttribute('aria-hidden', 'true');
            var text = el('span'); text.innerHTML = item[1];
            li.append(icon, text); ul.append(li);
        });
        return ul;
    }

    /* ---------- shared 3 x 3 board: さくと / いらも / ぬちだ ---------- */
    var PRACTICE_KANA = ['さ', 'く', 'と', 'い', 'ら', 'も', 'ぬ', 'ち', 'だ'];
    function gridCells(kana) {
        return kana.map(function (k, i) { return { id: i, row: Math.floor(i / 3), col: i % 3, kana: k }; });
    }

    /* ---------- bending-path demo (step 2): と → も → だ → ち drawn over the board ---------- */
    function bendDemo() {
        var path = [2, 5, 8, 7], cells = gridCells(PRACTICE_KANA);
        var frame = el('div', 'kc-tut-board is-demo'), stage = el('div', 'kc-tut-stage');
        cells.forEach(function (cell) {
            // The letter sits in its own layer above the drawn path, like the real board.
            var tile = el('span', 'kc-tut-cell is-demo' + (path.indexOf(cell.id) >= 0 ? ' is-on-path' : ''));
            var letter = el('span', 'kc-tut-letter', cell.kana); letter.lang = 'ja';
            tile.append(letter); stage.append(tile);
        });
        var points = path.map(function (id) { return (cells[id].col * 100 + 50) + ',' + (cells[id].row * 100 + 50); }).join(' ');
        var numbers = path.map(function (id, i) {
            return '<circle cx="' + (cells[id].col * 100 + 82) + '" cy="' + (cells[id].row * 100 + 18) + '" r="13" class="kc-tut-num"/>' +
                '<text x="' + (cells[id].col * 100 + 82) + '" y="' + (cells[id].row * 100 + 23) + '" class="kc-tut-num-text">' + (i + 1) + '</text>';
        }).join('');
        stage.insertAdjacentHTML('beforeend', '<svg class="kc-tut-path" viewBox="0 0 300 300" aria-hidden="true">' +
            '<polyline class="kc-tut-path__edge" points="' + points + '" pathLength="1"/>' +
            '<polyline class="kc-tut-path__fill" points="' + points + '" pathLength="1"/>' + numbers + '</svg>');
        frame.setAttribute('role', 'img');
        frame.setAttribute('aria-label', 'Ví dụ: nối と, も, だ đi xuống rồi rẽ trái sang ち để được ともだち');
        frame.append(stage);

        var rules = el('ul', 'kc-tut-rules');
        [['is-yes', 'fa-solid fa-check', 'Rẽ góc, uốn khúc như con rắn: được.'],
         ['is-yes', 'fa-solid fa-check', 'Mỗi bước sang một ô <b>kề bên</b> (trên, dưới, trái, phải).'],
         ['is-no', 'fa-solid fa-xmark', 'Đi chéo hoặc nhảy cóc qua ô khác: không được.']].forEach(function (rule) {
            var li = el('li', rule[0]), icon = el('i', rule[1]); icon.setAttribute('aria-hidden', 'true');
            var text = el('span'); text.innerHTML = rule[2];
            li.append(icon, text); rules.append(li);
        });
        var caption = el('p', 'kc-tut-note', 'ともだち (友達 · bạn bè): と → も → だ đi xuống, rồi rẽ trái sang ち.');
        var wrap = el('div', 'kc-tut-bend');
        wrap.append(frame, caption, rules);
        return wrap;
    }

    /* ---------- practice board (step 3) ---------- */
    function practiceBoard() {
        // Two of the three words turn a corner, so learners see that paths bend.
        var cells = gridCells(PRACTICE_KANA);
        var words = [
            { path: [0, 1, 4], kana: ['さ', 'く', 'ら'], word: '桜', reading: 'さくら', meaning: 'hoa anh đào' },
            { path: [2, 5, 8, 7], kana: ['と', 'も', 'だ', 'ち'], word: '友達', reading: 'ともだち', meaning: 'bạn bè' },
            { path: [3, 6], kana: ['い', 'ぬ'], word: '犬', reading: 'いぬ', meaning: 'con chó' }
        ];
        var path = [], found = [], pointer = null, dragged = false, start = null;
        var wrap = el('div', 'kc-tut-practice');
        var board = el('div', 'kc-tut-board'); board.setAttribute('role', 'group');
        board.setAttribute('aria-label', 'Bàn tập: nối các ô kana liền nhau để tìm 3 từ');
        var note = el('p', 'kc-tut-note', 'Bắt đầu với さ → く rồi rẽ xuống ら. Kéo qua các ô hoặc chạm lần lượt từng ô.');
        note.setAttribute('role', 'status');
        var wordList = el('ul', 'kc-tut-words');

        function adjacent(a, b) { return Math.abs(a.row - b.row) + Math.abs(a.col - b.col) === 1; }
        function locked(id) { return found.some(function (w) { return w.path.indexOf(id) >= 0; }); }
        function render() {
            board.querySelectorAll('[data-cell]').forEach(function (button) {
                var id = Number(button.dataset.cell);
                button.classList.toggle('is-selected', path.indexOf(id) >= 0);
                button.classList.toggle('is-found', locked(id));
                button.disabled = locked(id);
            });
            wordList.replaceChildren();
            words.forEach(function (w) {
                var done = found.indexOf(w) >= 0, li = el('li', 'kc-tut-word' + (done ? ' is-found' : ''));
                li.append(slots(w.kana, w.kana.map(function () { return done; })));
                if (done) {
                    var gloss = el('span', 'kc-tut-gloss'), kanji = el('strong', null, w.word); kanji.lang = 'ja';
                    gloss.append(kanji, document.createTextNode(' ' + w.reading + ' · ' + w.meaning));
                    li.append(gloss);
                }
                wordList.append(li);
            });
        }
        function check(final) {
            var match = words.filter(function (w) { return found.indexOf(w) < 0 && w.path.join() === path.join(); })[0];
            if (match) {
                found.push(match); path = [];
                var left = words.filter(function (w) { return found.indexOf(w) < 0; });
                note.textContent = !left.length
                    ? 'Tuyệt! Bạn đã tìm đủ 3 từ, kể cả hai từ phải rẽ góc. Trong game, tìm hết mọi từ là hoàn thành bàn.'
                    : 'Đúng rồi: ' + match.reading + ' (' + match.meaning + '). Còn ' + left.length + ' từ: '
                      + left.map(function (w) { return w.kana.length + ' ô'; }).join(', ') + '. Gợi ý: ' + left[0].kana.join(' → ') + '.';
                note.classList.add('is-good');
            } else if (path.length && (final || !words.some(function (w) {
                // A tapped path that no longer starts any word can never become one: stop it now.
                return found.indexOf(w) < 0 && w.path.slice(0, path.length).join() === path.join();
            }))) {
                var reversed = words.some(function (w) { return w.path.slice().reverse().join() === path.join(); });
                note.textContent = reversed ? 'Gần đúng! Từ phải nối theo thứ tự đọc, từ chữ đầu đến chữ cuối.'
                    : path.length === 1 ? 'Ô ' + cells[path[0]].kana + ' không phải chữ đầu của từ nào còn lại, thử ô khác nhé.'
                    : 'Chưa đúng, thử lại nhé.';
                note.classList.remove('is-good');
                path = [];
            }
            render();
        }
        function select(id, restart) {
            var cell = cells[id];
            if (!cell || cell.kana === null || locked(id)) return;
            if (path.length > 1 && path[path.length - 2] === id) { path.pop(); render(); return; }
            if (path.indexOf(id) >= 0) return;
            if (path.length && !adjacent(cells[path[path.length - 1]], cell)) {
                if (!restart) return;
                path = [];
            }
            path.push(id); render();
        }
        function cellAt(x, y) {
            var target = document.elementFromPoint(x, y), button = target && target.closest('[data-cell]');
            return button && board.contains(button) ? Number(button.dataset.cell) : null;
        }
        cells.forEach(function (cell) {
            if (cell.kana === null) { var wall = el('span', 'kc-tut-wall'); wall.setAttribute('aria-hidden', 'true'); board.append(wall); return; }
            var button = el('button', 'kc-tut-cell', cell.kana); button.type = 'button'; button.lang = 'ja';
            button.dataset.cell = cell.id; button.setAttribute('aria-label', cell.kana);
            board.append(button);
        });
        board.addEventListener('pointerdown', function (event) {
            var id = cellAt(event.clientX, event.clientY);
            if (id === null || pointer !== null) return;
            pointer = event.pointerId; dragged = false; start = { x: event.clientX, y: event.clientY };
            select(id, true); board.setPointerCapture(event.pointerId);
        });
        board.addEventListener('pointermove', function (event) {
            if (event.pointerId !== pointer) return;
            if (Math.hypot(event.clientX - start.x, event.clientY - start.y) > 8) dragged = true;
            var id = cellAt(event.clientX, event.clientY);
            if (id !== null) select(id, false);
        });
        board.addEventListener('pointerup', function (event) {
            if (event.pointerId !== pointer) return;
            pointer = null;
            if (board.hasPointerCapture(event.pointerId)) board.releasePointerCapture(event.pointerId);
            check(dragged);
        });
        board.addEventListener('pointercancel', function () { pointer = null; path = []; render(); });
        // Keyboard: Enter/Space on a tile works like a tap.
        board.addEventListener('click', function (event) {
            if (event.detail !== 0) return;
            var button = event.target.closest('[data-cell]');
            if (button) { select(Number(button.dataset.cell), true); check(false); }
        });
        render();
        wrap.append(board, note, wordList);
        return wrap;
    }

    /* ---------- hint demo (step 5): press Gợi ý, then pick any empty slot ---------- */
    function hintDemo() {
        var kana = ['ゆ', 'う', 'び', 'ん', 'きょ', 'く'], shown = kana.map(function () { return false; }), picking = false;
        var box = el('div', 'kc-tut-hint');
        var row = el('div', 'kc-tut-hint__row');
        var button = el('button', 'kc-hint', null); button.type = 'button';
        var note = el('p', 'kc-tut-note', 'Từ 6 ô này chưa giải. Bấm Gợi ý, rồi chọn ô bạn muốn mở.');
        note.setAttribute('role', 'status');
        function render() {
            var word = el('span', 'kc-tut-slots is-hint-demo'); word.lang = 'ja';
            kana.forEach(function (k, i) {
                var slot;
                if (picking && !shown[i]) {
                    slot = el('button', 'kc-tut-slot is-pickable'); slot.type = 'button';
                    slot.setAttribute('aria-label', 'Mở chữ thứ ' + (i + 1));
                    slot.addEventListener('click', function () {
                        shown[i] = true; picking = false;
                        note.textContent = shown.indexOf(false) < 0
                            ? 'Đã hiện hết: ゆうびんきょく (郵便局 · bưu điện).'
                            : 'Đã mở chữ ở ô thứ ' + (i + 1) + '. Trong game, lượt gợi ý sau mở lại sau 30 giây; ở đây bạn bấm thử tiếp được ngay.';
                        render(); button.focus();
                    });
                } else {
                    slot = el('span', 'kc-tut-slot' + (shown[i] ? ' is-filled' : ''), shown[i] ? k : '');
                }
                if (k.length > 1) slot.classList.add('is-compound');
                word.append(slot);
            });
            button.disabled = shown.indexOf(false) < 0;
            button.innerHTML = '<i class="fa-regular fa-lightbulb" aria-hidden="true"></i> ' + (picking ? 'Hủy chọn' : 'Bấm thử Gợi ý');
            row.replaceChildren(word, button);
        }
        button.addEventListener('click', function () {
            picking = !picking;
            note.textContent = picking ? 'Chọn một ô trống bất kỳ, ví dụ ô ở giữa từ.' : 'Từ 6 ô này chưa giải. Bấm Gợi ý, rồi chọn ô bạn muốn mở.';
            render();
            if (picking) row.querySelector('.is-pickable').focus();
        });
        render();
        box.append(row, note);
        return box;
    }

    /* ---------- the five steps ---------- */
    var STEPS = [
        { title: 'Chào mừng đến Kana Connect', render: function () {
            return [el('p', 'kc-tut-lead', 'Mỗi tuần có một bàn chữ kana chung cho mọi người. Nhiệm vụ của bạn: tìm hết các từ tiếng Nhật đang ẩn trên bàn.'),
                list([['fa-solid fa-table-cells', 'Mỗi bàn có <b>ít nhất 8 từ</b>, mỗi ô kana thuộc đúng <b>một</b> từ.'],
                    ['fa-solid fa-hand-pointer', 'Nối các ô liền nhau để ghép thành từ; từ đúng sẽ hiện kanji và nghĩa.'],
                    ['fa-solid fa-trophy', 'Giải càng nhanh, thứ hạng trong <b>Top 5 tuần này</b> càng cao.']])];
        } },
        { title: 'Đường nối có thể rẽ', render: function () {
            return [el('p', 'kc-tut-lead', 'Một từ không nhất thiết nằm trên một hàng hay một cột. Đường nối được rẽ góc nhiều lần, miễn là mỗi bước đi sang ô ngay cạnh.'),
                bendDemo()];
        } },
        { title: 'Thử nối từ', render: function () {
            return [el('p', 'kc-tut-lead', 'Tìm 3 từ trên bàn tập; hai từ trong đó phải rẽ góc. Kéo qua các ô hoặc chạm lần lượt; kéo ngược lại ô trước để lùi một bước.'),
                practiceBoard()];
        } },
        { title: 'Ô từ và âm ghép', render: function () {
            var example = el('div', 'kc-tut-example');
            var compound = el('div', 'kc-tut-compound');
            var tile = el('span', 'kc-tut-cell is-static is-compound', 'きゃ'); tile.lang = 'ja';
            compound.append(tile, el('span', 'kc-tut-caption', 'Âm ghép như きゃ, しゅ, ちょ nằm chung một ô.'));
            var word = el('div', 'kc-tut-compound');
            word.append(slots(['きゃ', 'く'], [true, true]), el('span', 'kc-tut-caption', 'きゃく (客 · khách) chỉ có 2 ô.'));
            example.append(compound, word);
            return [el('p', 'kc-tut-lead', 'Bên cạnh bàn chơi là danh sách từ cần tìm. Số ô vuông cho biết từ đó có bao nhiêu kana; tìm đúng thì ô được điền và hiện nghĩa.'),
                example];
        } },
        { title: 'Gợi ý khi bí', render: function () {
            return [el('p', 'kc-tut-lead', 'Cứ mỗi 30 giây chơi, nút Gợi ý mở một lần. Bấm Gợi ý rồi chạm vào bất kỳ ô trống nào trong danh sách từ, kể cả ô ở giữa một từ dài: chữ ở ô đó sẽ hiện ra.'),
                hintDemo()];
        } },
        { title: 'Cấp độ và xếp hạng', render: function () {
            return [list([['fa-solid fa-layer-group', 'Chọn bàn <b>N5 → N1</b> hoặc <b>Trộn</b>. Game mở trước cấp theo lộ trình của bạn (nút có chấm xanh).'],
                ['fa-regular fa-clock', 'Mỗi cấp có đồng hồ riêng và <b>chỉ chạy khi bạn đang chơi cấp đó</b>; rời trang là tạm dừng.'],
                ['fa-solid fa-crown', 'Top 5 mỗi bàn nhận danh hiệu: <b>Chí tôn</b>, Vô thượng cường giả, Tuyệt thế cường giả, Đỉnh cấp cường giả, Cường giả.'],
                ['fa-solid fa-calendar-day', 'Bàn mới mở lúc 0:00 thứ Hai hằng tuần. Mở lại hướng dẫn này bằng nút <b>Cách chơi</b>.']])];
        } }
    ];

    function show(i) {
        index = Math.max(0, Math.min(STEPS.length - 1, i));
        var step = STEPS[index];
        stepLabel.textContent = 'Bước ' + (index + 1) + '/' + STEPS.length;
        title.textContent = step.title;
        body.replaceChildren.apply(body, step.render());
        dots.replaceChildren();
        STEPS.forEach(function (_, d) { dots.append(el('i', d === index ? 'is-on' : '')); });
        back.hidden = index === 0;
        next.textContent = index === STEPS.length - 1 ? 'Bắt đầu chơi' : 'Tiếp';
    }
    function remember() { try { localStorage.setItem(SEEN_KEY, '1'); } catch (e) { /* optional */ } }
    function open() { show(0); if (!dialog.open) dialog.showModal(); next.focus(); }
    function close() { remember(); if (dialog.open) dialog.close(); }

    back.addEventListener('click', function () { show(index - 1); });
    next.addEventListener('click', function () { if (index === STEPS.length - 1) close(); else show(index + 1); });
    dialog.querySelector('[data-tut-close]').addEventListener('click', close);
    dialog.addEventListener('cancel', remember);   // Esc
    dialog.addEventListener('click', function (event) { if (event.target === dialog) close(); });  // backdrop
    var help = document.getElementById('kanaHelp');
    if (help) help.addEventListener('click', open);

    var seen = false;
    try { seen = localStorage.getItem(SEEN_KEY) === '1'; } catch (e) { /* show it */ }
    if (!seen) setTimeout(open, 400);
}());
