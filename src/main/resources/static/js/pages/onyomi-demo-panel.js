/*
 * Demo panel for the Onyomi practice and final-test pages. Like the goals/streak and Kana Connect demos it
 * appears only when the server exposes /api/dev/mission (mission.dev-tools=true). It drives the real attempt
 * through window.JlasOnyomiQuiz (onyomi-quiz.js): finish it right or wrong, open a new one, and for a final
 * test move the clock or count a leave.
 */
(function () {
    'use strict';

    if (typeof JlasAuth === 'undefined' || !JlasAuth.isLoggedIn || !JlasAuth.isLoggedIn()) return;

    var status;

    function quiz() {
        if (!window.JlasOnyomiQuiz) throw new Error('Bài chưa tải xong.');
        return window.JlasOnyomiQuiz;
    }
    function setStatus(text) { if (status) status.textContent = text; }

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
    function scoreText(result) {
        return 'Đã nộp: ' + Math.round(Number(result.scorePercent)) + ' điểm, ' + result.correctCount + '/' + result.totalCount + ' câu đúng'
            + (result.passed ? ', đã qua' : ', chưa qua');
    }

    function build() {
        var exam = quiz().exam;
        var panel = document.createElement('details');
        panel.className = 'mission-demo';
        var summary = document.createElement('summary');
        summary.textContent = '🧪 Demo Onyomi';
        var body = document.createElement('div');
        body.className = 'mission-demo__body';
        status = document.createElement('div');
        status.className = 'mission-demo__status';
        status.textContent = exam ? 'Bài kiểm tra: bấm "Bắt đầu làm bài" trước.' : 'Luyện tập: thao tác trên lượt đang mở.';

        body.append(
            label(exam ? 'Bài kiểm tra' : 'Bài luyện tập'),
            group([
                button('Làm đúng hết và nộp', async function () {
                    setStatus(scoreText(await quiz().finish(true)));
                }),
                button('Làm sai hết và nộp', async function () {
                    setStatus(scoreText(await quiz().finish(false)));
                })
            ]),
            label('Lượt làm'),
            button('Bỏ lượt này, mở lượt mới', async function () {
                if (!window.confirm('Bỏ lượt đang làm (không chấm điểm) và mở lượt mới với bộ câu khác?')) return;
                await quiz().newAttempt();
                setStatus('Đã mở lượt mới');
            })
        );
        if (exam) {
            body.append(
                label('Đồng hồ (dời giờ bắt đầu)'),
                group([
                    button('Còn 1 phút', async function () {
                        await quiz().secondsLeft(60);
                        setStatus('Đồng hồ còn 1 phút');
                    }),
                    button('Còn 10 giây', async function () {
                        await quiz().secondsLeft(10);
                        setStatus('Đồng hồ còn 10 giây, hết giờ bài tự nộp');
                    })
                ]),
                label('Rời bài'),
                button('Tính 1 lần rời bài', async function () {
                    var leaves = await quiz().leave();
                    setStatus('Đã tính ' + leaves + ' lần rời bài');
                })
            );
        }
        body.append(status);
        panel.append(summary, body);
        document.body.appendChild(panel);
    }

    fetch('/api/dev/mission/clock', { headers: JlasAuth.authHeaders() })
        .then(function (response) { if (response.ok) build(); })
        .catch(function () { /* demo tools disabled */ });
})();
