(function () {
    'use strict';

    if (!window.JlasAuth || !window.JlasAuth.requireLogin('/progress')) return;

    var state = { analytics: null, metric: 'minutes', range: '7d', creditRange: '7d' };
    var root = document.getElementById('stitchProgressLive');
    var empty = document.getElementById('stitchProgressEmpty');
    var error = document.getElementById('stitchProgressError');

    function el(id) { return document.getElementById(id); }
    function text(id, value) { if (el(id)) el(id).textContent = value; }
    function clamp(value) { return Math.max(0, Math.min(100, value)); }
    function percent(current, target) { return target ? Math.round(clamp(current * 100 / target)) : 0; }
    function dateLabel(value) { return value ? new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(value + 'T00:00:00')) : ''; }
    function shortDay(value) { return new Intl.DateTimeFormat('vi-VN', { weekday: 'short' }).format(new Date(value + 'T00:00:00')).replace('.', '').slice(0, 2); }
    function money(value) { return Number(value || 0).toLocaleString('en-US'); }
    function creditReason(value) {
        var labels = {
            TARGET_COMPLETE: 'Hoàn thành mục tiêu',
            QUIZ_COMPLETE: 'Hoàn thành Quiz',
            ITEM_PURCHASE: 'Mua vật phẩm: Streak Freeze',
            WEEKLY_REWARD: 'Thưởng duy trì tuần',
            WELCOME_REWARD: 'Thưởng bắt đầu học'
        };
        return labels[value] || value || 'Hoạt động học tập';
    }

    var sampleRanges = {
        minutes: { '30d': [120, 145, 110, 155], '90d': [480, 620, 510] },
        words: { '30d': [85, 102, 90, 115], '90d': [320, 410, 360] },
        quiz: { '30d': [14, 18, 15, 21], '90d': [55, 68, 62] }
    };

    var sampleLabels = {
        '30d': ['Tuần 1', 'Tuần 2', 'Tuần 3', 'Tuần 4'],
        '90d': ['Tháng 7', 'Tháng 8', 'Tháng 9']
    };

    var creditSamples = {
        '30d': { line: 'M0 140 L150 110 L300 120 L450 70 L600 20', area: 'M0 140 L150 110 L300 120 L450 70 L600 20 L600 180 L0 180 Z', x: 600, y: 20 },
        '90d': { line: 'M0 150 L200 130 L400 80 L600 20', area: 'M0 150 L200 130 L400 80 L600 20 L600 180 L0 180 Z', x: 600, y: 20 }
    };

    async function api(path) {
        var response = await fetch(path, { headers: window.JlasAuth.authHeaders() });
        var payload = await response.json().catch(function () { return {}; });
        if (!response.ok || payload.success === false) throw new Error(payload.message || 'Không thể tải dữ liệu.');
        return payload.data;
    }

    function renderProgressBar(barId, current, target) {
        var bar = el(barId);
        if (bar) bar.style.width = percent(current, target) + '%';
    }

    function renderToday(data) {
        var today = data.days[data.days.length - 1] || { minutes: 0, vocabulary: 0, quizzes: 0 };
        var target = data.targets;
        var vocabPct = percent(today.vocabulary, target.dailyVocabulary);
        var quizPct = percent(today.quizzes, target.dailyQuizzes);
        var studyPct = percent(today.minutes, target.dailyMinutes);
        text('stitchTodayDate', dateLabel(data.to));
        text('stitchTodayVocabulary', today.vocabulary + ' / ' + target.dailyVocabulary + ' từ');
        text('stitchTodayVocabularyPct', vocabPct + '%');
        text('stitchTodayQuizzes', today.quizzes + ' / ' + target.dailyQuizzes + ' bài');
        text('stitchTodayQuizzesPct', quizPct + '%');
        text('stitchTodayStudy', today.minutes + ' / ' + target.dailyMinutes + ' phút');
        text('stitchTodayStudyPct', studyPct + '%');
        text('stitchTargetMinutes', target.dailyMinutes);
        renderProgressBar('stitchTodayVocabularyBar', today.vocabulary, target.dailyVocabulary);
        renderProgressBar('stitchTodayQuizzesBar', today.quizzes, target.dailyQuizzes);
        renderProgressBar('stitchTodayStudyBar', today.minutes, target.dailyMinutes);
        text('stitchTodayMessage', today.targetComplete ? 'Bạn đã hoàn thành mục tiêu hôm nay rồi. Tuyệt vời!' : 'Sắp hoàn thành mục tiêu hôm nay rồi! Cố lên nhé.');
    }

    function renderKpis(data, credit) {
        text('stitchCurrentStreak', data.streak.current);
        text('stitchLongestStreak', data.streak.longest);
        text('stitchCreditBalance', money(credit ? credit.balance : 0));
        var today = data.days[data.days.length - 1] || { minutes: 0 };
        text('stitchTodayMinutes', today.minutes);
        text('stitchCreditBadge', money(credit ? credit.balance : 0) + ' CR');
        text('stitchCreditSummary', money(credit ? credit.balance : 0) + ' CR');
    }

    function renderCredit(credit) {
        var list = el('stitchCreditList');
        if (!list || !credit || !credit.transactions || !credit.transactions.length) return;
        var transactions = credit.transactions.slice(0, 3);
        var earned = transactions.reduce(function (sum, value) { return sum + Math.max(0, Number(value.amount || value.points || 0)); }, 0);
        var spent = transactions.reduce(function (sum, value) { return sum + Math.min(0, Number(value.amount || value.points || 0)); }, 0);
        text('stitchCreditEarned', '+' + money(earned) + ' CR');
        text('stitchCreditSpent', money(spent) + ' CR');
        text('stitchCreditNet', (earned + spent >= 0 ? '+' : '') + money(earned + spent) + ' CR ròng');
        list.replaceChildren();
        transactions.forEach(function (transaction) {
            var amount = Number(transaction.amount || transaction.points || 0);
            var row = document.createElement('div');
            row.className = 'stitch-progress-page__credit-row' + (amount < 0 ? ' is-negative' : '');
            var body = document.createElement('div');
            var icon = document.createElement('i');
            icon.className = amount < 0 ? 'fa-solid fa-store' : 'fa-solid fa-award';
            var copy = document.createElement('div');
            var title = document.createElement('strong');
            title.textContent = creditReason(transaction.reason || transaction.description);
            var date = document.createElement('small');
            date.textContent = transaction.createdAt ? dateLabel(String(transaction.createdAt).slice(0, 10)) : 'Gần đây';
            copy.append(title, date);
            body.append(icon, copy);
            var value = document.createElement('b');
            value.textContent = (amount >= 0 ? '+' : '') + amount;
            row.append(body, value);
            list.appendChild(row);
        });
    }

    function points(values, width, height) {
        var max = Math.max.apply(null, values.concat([1]));
        var step = values.length > 1 ? width / (values.length - 1) : width;
        return values.map(function (value, index) {
            return { x: index * step, y: height - 18 - (value / max) * (height - 36), value: value };
        });
    }

    function smoothPath(coords) {
        if (!coords.length) return '';
        var path = 'M ' + coords[0].x + ' ' + coords[0].y;
        for (var i = 0; i < coords.length - 1; i += 1) {
            var current = coords[i];
            var next = coords[i + 1];
            var mid = (current.x + next.x) / 2;
            path += ' C ' + mid + ' ' + current.y + ', ' + mid + ' ' + next.y + ', ' + next.x + ' ' + next.y;
        }
        return path;
    }

    function renderLineChart(days) {
        var values = state.range === '7d'
            ? days.map(function (day) {
                if (state.metric === 'words') return day.vocabulary;
                if (state.metric === 'quiz') return day.quizzes;
                return day.minutes;
            })
            : sampleRanges[state.metric][state.range];
        var labelValues = state.range === '7d' ? days.map(function (day) { return shortDay(day.date); }) : sampleLabels[state.range];
        var coords = points(values, 700, 200);
        var line = smoothPath(coords);
        var area = line + ' L 700 200 L 0 200 Z';
        el('stitchActivityLine').setAttribute('d', line);
        el('stitchActivityArea').setAttribute('d', area);
        var group = el('stitchActivityPoints');
        group.replaceChildren();
        coords.forEach(function (point, index) {
            var circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
            circle.setAttribute('cx', point.x); circle.setAttribute('cy', point.y); circle.setAttribute('r', '5.5');
            circle.setAttribute('fill', '#fff'); circle.setAttribute('stroke', '#FF4F9A'); circle.setAttribute('stroke-width', '3');
            circle.setAttribute('aria-label', values[index] + '');
            group.appendChild(circle);
        });
        var labelsElement = el('stitchActivityLabels');
        labelsElement.replaceChildren();
        labelValues.forEach(function (value) { var label = document.createElement('span'); label.textContent = value; labelsElement.appendChild(label); });
        var unit = state.metric === 'words' ? ' từ' : state.metric === 'quiz' ? ' quiz' : ' phút';
        var average = values.length ? (values.reduce(function (sum, value) { return sum + value; }, 0) / values.length).toFixed(1) : '0';
        text('stitchActivityAverage', average + unit);
        text('stitchActivityConsistency', state.analytics.summary.consistencyPercent + '%');
    }

    function renderWeek(data) {
        var container = el('stitchWeekBars');
        container.replaceChildren();
        data.days.forEach(function (day) {
            var column = document.createElement('div');
            column.className = 'stitch-progress-page__week-bar' + (day.targetComplete ? ' is-complete' : '');
            var bar = document.createElement('i');
            bar.style.height = Math.max(4, percent(day.minutes, data.targets.dailyMinutes)) + '%';
            var label = document.createElement('span'); label.textContent = shortDay(day.date);
            column.append(bar, label); container.appendChild(column);
        });
        text('stitchWeekPercent', data.summary.consistencyPercent);
        text('stitchWeekPerformance', data.summary.consistencyPercent + '%');
        text('stitchWeekDays', data.summary.activeDays + '/7');
        var totalHours = Math.floor(data.summary.weeklyMinutes / 60);
        var totalMinutes = data.summary.weeklyMinutes % 60;
        text('stitchDonutTotal', totalHours + 'h ' + String(totalMinutes).padStart(2, '0') + 'm');
    }

    function renderCreditChart(credit) {
        if (state.creditRange !== '7d') {
            var sample = creditSamples[state.creditRange];
            el('stitchCreditLine').setAttribute('d', sample.line);
            el('stitchCreditArea').setAttribute('d', sample.area);
            el('stitchCreditPoint').setAttribute('cx', sample.x);
            el('stitchCreditPoint').setAttribute('cy', sample.y);
            return;
        }
        var values = (credit && credit.transactions ? credit.transactions.slice(0, 7).reverse() : []).map(function (value) { return Math.max(0, Number(value.amount || value.points || 0)); });
        if (!values.length) values = [0, 0, 0, 0, 0, 0, 0];
        var coords = points(values, 600, 180);
        var line = smoothPath(coords);
        el('stitchCreditLine').setAttribute('d', line);
        el('stitchCreditArea').setAttribute('d', line + ' L 600 180 L 0 180 Z');
        var last = coords[coords.length - 1];
        el('stitchCreditPoint').setAttribute('cx', last.x); el('stitchCreditPoint').setAttribute('cy', last.y);
    }

    function renderHeatmap(data) {
        var grid = el('stitchHeatmapGrid');
        grid.replaceChildren();
        var values = data.days.map(function (day) { return day.minutes + day.vocabulary + day.quizzes * 10; });
        for (var i = 0; i < 196; i += 1) {
            var cell = document.createElement('i');
            var recentIndex = i - 189;
            var value = recentIndex >= 0 ? values[recentIndex] || 0 : 0;
            var level = value === 0 ? 0 : value >= data.targets.dailyMinutes + data.targets.dailyVocabulary ? 4 : value >= 25 ? 3 : value >= 10 ? 2 : 1;
            cell.dataset.level = String(level);
            cell.title = level ? 'Có hoạt động học' : 'Ngày nghỉ';
            grid.appendChild(cell);
        }
    }

    function render(data, credit) {
        state.analytics = data;
        state.credit = credit;
        renderKpis(data, credit); renderToday(data); renderCredit(credit); renderLineChart(data.days); renderWeek(data); renderCreditChart(credit); renderHeatmap(data);
        empty.hidden = data.summary.activeDays > 0;
        root.hidden = false;
    }

    async function load() {
        error.hidden = true;
        try {
            var result = await Promise.all([api('/api/progress/analytics'), api('/api/credits/me').catch(function () { return null; })]);
            render(result[0], result[1]);
        } catch (exception) {
            root.hidden = true; error.hidden = false;
            if (window.JlasUI && typeof window.JlasUI.toast === 'function') window.JlasUI.toast(exception.message, 'error');
        }
    }

    document.querySelectorAll('[data-stitch-metric]').forEach(function (button) {
        button.addEventListener('click', function () { document.querySelectorAll('[data-stitch-metric]').forEach(function (item) { item.classList.toggle('is-active', item === button); }); state.metric = button.dataset.stitchMetric; if (state.analytics) renderLineChart(state.analytics.days); });
    });
    document.querySelectorAll('[data-stitch-range]').forEach(function (button) { button.addEventListener('click', function () { document.querySelectorAll('[data-stitch-range]').forEach(function (item) { item.classList.toggle('is-active', item === button); }); state.range = button.dataset.stitchRange; if (state.analytics) renderLineChart(state.analytics.days); }); });
    document.querySelectorAll('[data-stitch-credit-range]').forEach(function (button) { button.addEventListener('click', function () { document.querySelectorAll('[data-stitch-credit-range]').forEach(function (item) { item.classList.toggle('is-active', item === button); }); state.creditRange = button.dataset.stitchCreditRange; renderCreditChart(state.credit); }); });
    load();
}());
