/*
 * Demo panel for the goals page. It appears only when the server exposes /api/dev/mission
 * (mission.dev-tools=true), and drives the real flow with mock learning events, a movable clock
 * and a simulated credit outage.
 */
(function () {
    'use strict';

    if (typeof JlasAuth === 'undefined' || !JlasAuth.isLoggedIn || !JlasAuth.isLoggedIn()) return;

    var lastEventId = null;
    var failureOn = false;

    async function api(path, options) {
        var config = options || {};
        config.headers = Object.assign({ 'Content-Type': 'application/json' }, JlasAuth.authHeaders(), config.headers || {});
        var response = await fetch(path, config);
        var payload = await response.json().catch(function () { return {}; });
        if (!response.ok || payload.success === false) throw new Error(payload.message || ('HTTP ' + response.status));
        return payload.data;
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
        var node = document.createElement('div');
        node.className = 'mission-demo__label';
        node.textContent = text;
        return node;
    }

    function group(children) {
        var node = document.createElement('div');
        node.className = 'mission-demo__group';
        children.forEach(function (child) { node.appendChild(child); });
        return node;
    }

    var status;
    function setStatus(text) {
        if (status) status.textContent = text;
    }

    async function afterChange(message) {
        setStatus(message);
        if (window.JlasGoals) await window.JlasGoals.reload();
        if (window.RewardFX) await RewardFX.refreshChip(true);
    }

    async function send(type, extra) {
        var body = Object.assign({ type: type, referenceId: Math.floor(Math.random() * 100000) }, extra || {});
        var result = await api('/api/dev/mission/events', { method: 'POST', body: JSON.stringify(body) });
        lastEventId = { id: result.eventId, body: body };
        await afterChange('Đã phát ' + type);
    }

    function clockText(state) {
        var date = new Date(state.today + 'T00:00:00');
        return 'Hôm nay (demo): ' + date.toLocaleDateString('vi-VN', { weekday: 'short', day: '2-digit', month: '2-digit' })
            + (state.offsetDays ? ' (' + (state.offsetDays > 0 ? '+' : '') + state.offsetDays + ' ngày)' : ' (thật)');
    }

    async function shiftClock(days) {
        var state = await api('/api/dev/mission/clock', { method: 'POST', body: JSON.stringify({ offsetDays: days }) });
        await afterChange(clockText(state));
        return state;
    }

    var offsetDays = 0;

    /**
     * Completes the daily goal on {@code days} consecutive demo days with the learner's own targets.
     * Starting from a clean account this reaches the matching milestone (3, 7, 15 or 30 days).
     */
    async function runStreak(days) {
        var dashboard = await api('/api/mission/dashboard');
        var goal = dashboard.dailyGoal;
        var start = goal.completed ? offsetDays + 1 : offsetDays;
        for (var i = 0; i < days; i++) {
            offsetDays = start + i;
            setStatus('Đang chạy ngày ' + (i + 1) + '/' + days + '…');
            await api('/api/dev/mission/clock', { method: 'POST', body: JSON.stringify({ offsetDays: offsetDays }) });
            await api('/api/dev/mission/events', { method: 'POST', body: JSON.stringify({
                type: 'LESSON_COMPLETED', referenceId: Math.floor(Math.random() * 100000),
                minutes: Math.min(600, goal.minutesTarget), vocabularyCount: Math.min(500, goal.vocabularyTarget) }) });
            for (var q = 0; q < goal.quizTarget; q++) {
                await api('/api/dev/mission/events', { method: 'POST', body: JSON.stringify({
                    type: 'LESSON_QUIZ_COMPLETED', referenceId: Math.floor(Math.random() * 100000) }) });
            }
        }
        var state = await api('/api/dev/mission/clock');
        await afterChange('Đã hoàn thành mục tiêu ' + days + ' ngày liên tiếp · ' + clockText(state));
    }

    /**
     * Moves to the day after exactly one missed day, so the freeze offer for yesterday appears. The
     * learner then protects the missed day and still has to finish today's goal to extend the streak.
     */
    async function missOneDay() {
        var before = (await api('/api/mission/dashboard')).streak;
        if (!before || !before.currentStreak) {
            setStatus('Chưa có chuỗi nào để bảo vệ. Hãy chạy "Kịch bản nhanh" trước.');
            return;
        }
        if (before.status === 'AT_RISK') {
            setStatus('Đang có 1 ngày bị lỡ rồi: dùng thẻ bảo vệ trong thẻ Chuỗi học liên tục.');
            return;
        }
        offsetDays += before.status === 'DONE_TODAY' ? 2 : 1;
        var state = await shiftClock(offsetDays);
        setStatus('Đã lỡ 1 ngày · chuỗi ' + before.currentStreak + ' ngày đang gặp nguy · ' + clockText(state));
    }

    /**
     * Skips two whole days without learning: the older missed day is past its freeze deadline, so the
     * streak breaks by the real rule (no freeze offer) while the longest streak is kept.
     */
    async function loseStreak() {
        var before = (await api('/api/mission/dashboard')).streak;
        if (!before || !before.currentStreak) {
            setStatus('Chưa có chuỗi nào để mất. Hãy chạy "Kịch bản nhanh" trước.');
            return;
        }
        var start = offsetDays;
        offsetDays = start + (before.status === 'DONE_TODAY' ? 3 : 2);
        var state = await shiftClock(offsetDays);
        var after = (await api('/api/mission/dashboard')).streak;
        setStatus('Chuỗi ' + before.currentStreak + ' ngày → ' + after.currentStreak
            + ' (lỡ 2 ngày, quá hạn dùng thẻ bảo vệ) · kỷ lục ' + after.longestStreak + ' ngày · ' + clockText(state));
    }

    function build(clock) {
        offsetDays = clock.offsetDays || 0;
        var panel = document.createElement('details');
        panel.className = 'mission-demo';
        var summary = document.createElement('summary');
        summary.textContent = '🧪 Demo nhiệm vụ & chuỗi';
        var body = document.createElement('div');
        body.className = 'mission-demo__body';
        status = document.createElement('div');
        status.className = 'mission-demo__status';
        status.textContent = clockText(clock);

        body.append(
            label('Event học tập (mock)'),
            group([
                button('Hoàn thành bài học', function () {
                    return send('LESSON_COMPLETED', { minutes: 20, vocabularyCount: 15 });
                }),
                button('Nộp quiz', function () { return send('LESSON_QUIZ_COMPLETED'); }),
                button('Học thêm 10 phút', function () {
                    return send('LESSON_PROGRESS_UPDATED', { minutes: 10 });
                }),
                button('Đăng Shadowing', function () { return send('SHADOWING_PUBLISHED'); })
            ]),
            button('Gửi lại event vừa rồi (trùng)', async function () {
                if (!lastEventId) { setStatus('Chưa có event nào để gửi lại.'); return; }
                var body = Object.assign({}, lastEventId.body, { eventId: lastEventId.id });
                await api('/api/dev/mission/events', { method: 'POST', body: JSON.stringify(body) });
                await afterChange('Đã gửi lại ' + lastEventId.id + ' — không được cộng lần 2');
            }),
            label('Kịch bản nhanh: chạy chuỗi'),
            group([3, 7, 15, 30].map(function (days) {
                return button(days + ' ngày', function () { return runStreak(days); });
            })),
            label('Thời gian'),

            group([
                button('Sang ngày mai', function () { offsetDays += 1; return shiftClock(offsetDays); }),
                button('Bỏ qua 1 ngày', function () { offsetDays += 2; return shiftClock(offsetDays); }),
                button('Về hôm nay', function () { offsetDays = 0; return shiftClock(0); })
            ]),
            group([
                button('Lỡ 1 ngày (dùng thẻ bảo vệ)', missOneDay),
                button('Mất chuỗi (lỡ 2 ngày)', loseStreak)
            ]),
            label('Sự cố'),
            button('Giả lập CP service lỗi: TẮT', async function (node) {
                failureOn = !failureOn;
                var state = await api('/api/dev/mission/credit-failure',
                    { method: 'POST', body: JSON.stringify({ enabled: failureOn }) });
                node.textContent = 'Giả lập CP service lỗi: ' + (failureOn ? 'BẬT' : 'TẮT');
                node.classList.toggle('is-on', failureOn);
                await afterChange(failureOn
                    ? 'CP service đang lỗi — thưởng sẽ xếp hàng chờ'
                    : 'CP service đã hồi phục — còn ' + state.openAwards + ' thưởng chờ');
            }),
            label('Dữ liệu'),
            button('Xoá dữ liệu demo của tôi', async function () {
                if (!window.confirm('Xoá toàn bộ tiến độ, chuỗi, nhiệm vụ và CP kiếm được từ nhiệm vụ của tài khoản này, '
                    + 'rồi đưa đồng hồ về hôm nay?')) return;
                var result = await api('/api/dev/mission/reset', { method: 'POST' });
                offsetDays = 0; lastEventId = null; failureOn = false;
                var failureButton = Array.prototype.find.call(panel.querySelectorAll('button'),
                    function (b) { return b.textContent.indexOf('Giả lập CP service lỗi') === 0; });
                if (failureButton) { failureButton.textContent = 'Giả lập CP service lỗi: TẮT'; failureButton.classList.remove('is-on'); }
                try { Object.keys(sessionStorage).forEach(function (k) {
                    if (k.indexOf('jlas.mission.freezePrompted.') === 0) sessionStorage.removeItem(k); }); } catch (e) { /* ignore */ }
                await afterChange('Đã xoá dữ liệu demo · hoàn ' + (-result.creditsReverted) + ' CP · số dư ' + result.creditBalance);
            }),
            status
        );
        panel.append(summary, body);
        document.body.appendChild(panel);
    }

    api('/api/dev/mission/clock').then(build).catch(function () { /* demo tools disabled */ });
})();
