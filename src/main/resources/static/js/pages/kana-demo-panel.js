/*
 * Demo panel for the Kana Connect page. Like the goals/streak demo it appears only when the server
 * exposes /api/dev/mission (mission.dev-tools=true). It drives the real flow on the learner's own
 * board: open a hint now, solve words, add play time, restart, add demo players and move the day.
 */
(function () {
    'use strict';

    if (typeof JlasAuth === 'undefined' || !JlasAuth.isLoggedIn || !JlasAuth.isLoggedIn()) return;

    var offsetDays = 0, status;

    async function api(path, options) {
        var config = options || {};
        config.headers = Object.assign({ 'Content-Type': 'application/json' }, JlasAuth.authHeaders(), config.headers || {});
        var response = await fetch(path, config);
        var payload = await response.json().catch(function () { return {}; });
        if (!response.ok || payload.success === false) throw new Error(payload.message || ('HTTP ' + response.status));
        return payload.data;
    }
    function post(path, body) { return api(path, { method: 'POST', body: JSON.stringify(body || {}) }); }

    function setStatus(text) { if (status) status.textContent = text; }
    function board() {
        var current = window.JlasKana && window.JlasKana.current();
        if (!current) throw new Error('Bàn chơi chưa tải xong.');
        return current;
    }
    async function refresh(message) {
        if (window.JlasKana) await window.JlasKana.reload();
        setStatus(message);
    }

    function button(label, handler) {
        var node = document.createElement('button');
        node.type = 'button';
        node.textContent = label;
        node.addEventListener('click', async function () {
            node.disabled = true;
            try { await handler(node); } catch (error) { setStatus('Lỗi: ' + error.message); }
            finally { node.disabled = false; }
        });
        return node;
    }
    function label(text) {
        var node = document.createElement('div'); node.className = 'mission-demo__label'; node.textContent = text; return node;
    }
    function group(children) {
        var node = document.createElement('div'); node.className = 'mission-demo__group';
        children.forEach(function (child) { node.appendChild(child); });
        return node;
    }
    function dayText() {
        return offsetDays === 0 ? 'Đồng hồ: hôm nay' : 'Đồng hồ: ' + (offsetDays > 0 ? '+' : '') + offsetDays + ' ngày';
    }
    async function shiftDay(days) {
        await post('/api/dev/mission/clock', { offsetDays: days });
        offsetDays = days;
        await refresh(dayText() + ' · bàn của ngày mới đã tải');
    }

    function build(clock) {
        offsetDays = clock.offsetDays || 0;
        var panel = document.createElement('details');
        panel.className = 'mission-demo';
        var summary = document.createElement('summary');
        summary.textContent = '🧪 Demo Kana Connect';
        var body = document.createElement('div');
        body.className = 'mission-demo__body';
        status = document.createElement('div');
        status.className = 'mission-demo__status';
        status.textContent = dayText();

        body.append(
            label('Gợi ý'),
            button('Mở gợi ý ngay (bỏ chờ 30 giây)', async function () {
                await post('/api/dev/kana/hint-ready', { challengeId: board().id });
                await refresh('Gợi ý đã mở, bấm nút Gợi ý trên bàn để dùng');
            }),
            label('Giải hộ'),
            group([
                button('Giải 1 từ', async function () {
                    var result = await post('/api/dev/kana/solve', { challengeId: board().id, all: false });
                    await refresh(result.solved ? 'Đã giải thêm 1 từ' : 'Bàn này đã giải xong');
                }),
                button('Giải hết bàn', async function () {
                    var result = await post('/api/dev/kana/solve', { challengeId: board().id, all: true });
                    await refresh(result.solved ? 'Đã giải ' + result.solved + ' từ còn lại, xem Top 5' : 'Bàn này đã giải xong');
                })
            ]),
            label('Thời gian chơi (dời giờ bắt đầu)'),
            group([60, 300].map(function (seconds) {
                return button('+' + seconds / 60 + ' phút', async function () {
                    var current = board();
                    if (current.completed) { setStatus('Bàn đã giải xong, thời gian đã chốt.'); return; }
                    await post('/api/dev/kana/play-time', { challengeId: current.id, seconds: seconds });
                    await refresh('Đã cộng ' + seconds / 60 + ' phút vào đồng hồ của bạn');
                });
            })),
            label('Bàn chơi'),
            button('Chơi lại bàn này từ đầu', async function () {
                if (!window.confirm('Xoá tiến độ, gợi ý và thời gian của bạn trên bàn này rồi bắt đầu lại?')) return;
                await post('/api/dev/kana/restart', { challengeId: board().id });
                await refresh('Đã xoá tiến độ, đồng hồ bắt đầu lại từ 00:00');
            }),
            label('Xếp hạng'),
            group([
                button('Thêm 12 người chơi demo', async function () {
                    var level = board().level;
                    var result = await post('/api/dev/kana/players?level=' + encodeURIComponent(level));
                    await refresh(result.added ? 'Đã thêm ' + result.added + ' người chơi demo vào bàn ' + (level === 'MIX' ? 'Trộn' : level)
                        : 'Bàn này đã có đủ người chơi demo');
                }),
                button('Xoá người chơi demo', async function () {
                    var result = await api('/api/dev/kana/players', { method: 'DELETE' });
                    await refresh('Đã xoá ' + result.removed + ' người chơi demo');
                })
            ]),
            label('Ngày'),
            group([
                button('Sang ngày mai', function () { return shiftDay(offsetDays + 1); }),
                button('Về hôm nay', function () { return shiftDay(0); })
            ]),
            status
        );
        panel.append(summary, body);
        document.body.appendChild(panel);
    }

    api('/api/dev/mission/clock').then(build).catch(function () { /* demo tools disabled */ });
})();
