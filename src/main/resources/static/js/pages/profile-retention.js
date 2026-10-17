/*
 * Goals & streak panel (goals page and the profile "learning" tab).
 * Data: GET /api/mission/dashboard. Actions: mission claims, target changes, streak freeze decisions.
 * Reward animations are delegated to window.RewardFX.
 */
(function () {
    'use strict';

    var panel = document.getElementById('panel-learning') || document.getElementById('goalsApp');
    if (!panel || typeof JlasAuth === 'undefined') return;

    var ZONE_SYNC_KEY = 'jlas.mission.zoneSynced';
    var FREEZE_PROMPT_KEY = 'jlas.mission.freezePrompted.';

    var loading = document.getElementById('retentionLoading');
    var content = document.getElementById('retentionContent');
    var errorBox = document.getElementById('retentionError');
    var dashboardLoaded = false;
    var current = null;

    function byId(id) {
        return document.getElementById(id);
    }

    function setText(id, value) {
        var element = byId(id);
        if (element) element.textContent = value;
    }

    var lastClaimed = null;

    /** Counts a number shown on the page from its current value to the new one. */
    function animateNumber(id, to, suffix, locale) {
        var element = byId(id);
        if (!element) return;
        var format = function (value) { return (locale ? Number(value).toLocaleString('vi-VN') : value) + (suffix || ''); };
        var from = parseInt((element.textContent || '').replace(/[^0-9-]/g, ''), 10);
        if (isNaN(from) || from === to) { element.textContent = format(to); return; }
        var start = performance.now();
        (function frame(now) {
            var progress = Math.min(1, (now - start) / 800);
            var eased = 1 - Math.pow(1 - progress, 3);
            element.textContent = format(Math.round(from + (to - from) * eased));
            if (progress < 1) requestAnimationFrame(frame);
        })(start);
    }

    function notify(message, type) {
        if (window.JlasUI && typeof JlasUI.toast === 'function') {
            JlasUI.toast(message, type || 'info');
        } else if (window.RewardFX) {
            RewardFX.toast(message, '', { icon: type === 'error' ? 'fa-triangle-exclamation' : 'fa-circle-info' });
        }
    }

    async function api(path, options) {
        var config = options || {};
        config.headers = Object.assign({}, JlasAuth.authHeaders(), config.headers || {});
        if (config.body && !config.headers['Content-Type']) {
            config.headers['Content-Type'] = 'application/json';
        }
        var response = await fetch(path, config);
        var payload = await response.json().catch(function () { return {}; });
        if (!response.ok || payload.success === false) {
            throw new Error(payload.message || 'Không thể xử lý yêu cầu');
        }
        return payload.data;
    }

    async function syncTimeZone() {
        var zone;
        try { zone = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch (e) { zone = null; }
        if (!zone) return;
        try {
            if (sessionStorage.getItem(ZONE_SYNC_KEY) === zone) return;
        } catch (e) { /* storage unavailable */ }
        try {
            await api('/api/mission/timezone', { method: 'PUT', body: JSON.stringify({ zoneId: zone }) });
            try { sessionStorage.setItem(ZONE_SYNC_KEY, zone); } catch (e) { /* ignore */ }
        } catch (e) { /* keep the server default */ }
    }

    // ---------- rendering ----------
    function renderGoal(goal) {
        animateNumber('dailyGoalPercent', goal.percent, '%');
        setText('dailyVocabProgress', goal.vocabularyDone + '/' + goal.vocabularyTarget);
        setText('dailyQuizProgress', goal.quizDone + '/' + goal.quizTarget);
        setText('dailyMinutesProgress', goal.minutesDone + '/' + goal.minutesTarget);
        var source = byId('dailyGoalSource');
        if (source) {
            source.textContent = goal.source === 'ACTIVE_PATH' ? 'Theo bài học tiếp theo trong lộ trình'
                : goal.source === 'USER_TARGETS' ? 'Theo mục tiêu bạn đã đặt'
                    : 'Mục tiêu mặc định';
            source.title = goal.fallbackReason || '';
        }
        var ring = byId('dailyGoalRing');
        if (ring) {
            ring.setAttribute('aria-valuenow', goal.percent);
            var met = [goal.vocabularyDone >= goal.vocabularyTarget, goal.quizDone >= goal.quizTarget,
                goal.minutesDone >= goal.minutesTarget].filter(Boolean).length;
            var caption = ring.querySelector('div > span');
            if (caption) caption.textContent = goal.completed ? 'Đã đạt mục tiêu' : met + '/3 chỉ tiêu';
            // One ring for the overall progress; each metric row shows its own coloured bar.
            var values = ring.querySelectorAll('.ring-value');
            if (values.length) {
                values[0].style.strokeDashoffset = 314 * (1 - Math.max(0, Math.min(100, goal.percent)) / 100);
                if (values[1]) values[1].style.strokeDashoffset = 239;
                if (values[2]) values[2].style.strokeDashoffset = 163;
            }
        }
        // Each metric row gets a bar in its ring's colour, so the rings read like a legend.
        var rows = document.querySelectorAll('.goals-retention-metrics > div');
        [[goal.vocabularyDone, goal.vocabularyTarget], [goal.quizDone, goal.quizTarget],
            [goal.minutesDone, goal.minutesTarget]].forEach(function (pair, index) {
            if (!rows[index]) return;
            rows[index].classList.add('goals-metric');
            rows[index].classList.toggle('is-done', goal.completed || pair[0] >= pair[1]);
            rows[index].style.setProperty('--metric-progress', Math.round(ratio(pair[0], pair[1], goal.completed) * 100) + '%');
        });
        var status = document.querySelector('.goals-retention-status');
        if (status) {
            status.textContent = goal.completed ? 'Đã hoàn thành · +' + goal.reward + ' CP' : 'Đang thực hiện';
            status.classList.toggle('is-complete', goal.completed);
        }
        setValue('dailyVocabTarget', goal.vocabularyTarget);
        setValue('dailyQuizTarget', goal.quizTarget);
        setValue('dailyStudyMinutes', goal.minutesTarget);
    }

    function ratio(done, target, completed) {
        if (completed) return 1;
        return target > 0 ? Math.min(1, done / target) : 1;
    }

    function setValue(id, value) {
        var input = byId(id);
        if (!input) return;
        input.value = value;
        input.dispatchEvent(new Event('input'));
    }

    function renderStreak(streak) {
        setText('currentStreak', streak.currentStreak + ' ngày');
        setText('longestStreak', streak.longestStreak);
        setText('freezeCount', streak.freezeCount + '/' + streak.maxFreezes);
        if (streak.status === 'AT_RISK' && streak.freezeOffer) {
            setText('streakMilestone', 'Chuỗi ' + streak.freezeOffer.streakAtRisk + ' ngày đang gặp nguy!');
        } else if (streak.nextMilestoneDays) {
            setText('streakMilestone', 'Còn ' + (streak.nextMilestoneDays - streak.currentStreak)
                + ' ngày để nhận ' + streak.nextMilestoneReward + ' CP.');
        } else if (streak.currentStreak > 0) {
            setText('streakMilestone', 'Bạn đã chinh phục toàn bộ mốc chuỗi ngày hiện tại.');
        } else {
            setText('streakMilestone', 'Hoàn thành mục tiêu hôm nay để bắt đầu chuỗi.');
        }
        var flame = document.querySelector('.goals-retention-flame, #streakFlame');
        if (flame) {
            flame.classList.toggle('is-cold', streak.currentStreak === 0 || streak.status === 'AT_RISK');
            var level = streak.currentStreak >= 30 ? 4 : streak.currentStreak >= 15 ? 3
                : streak.currentStreak >= 7 ? 2 : streak.currentStreak >= 3 ? 1 : 0;
            for (var t = 0; t <= 4; t++) flame.classList.toggle('flame-tier-' + t, t === level);
            mountFlame(flame, level, !flame.classList.contains('is-cold'));
        }

        var milestoneBar = byId('streakMilestoneBar');
        if (milestoneBar) {
            milestoneBar.style.width = (streak.nextMilestoneDays
                ? Math.round(Math.min(1, streak.currentStreak / streak.nextMilestoneDays) * 100) : 100) + '%';
        }

        renderWeek(streak.week);
        renderFreezeOffer(streak);

        // .btn sets display, which beats the [hidden] attribute, so hide through style.
        var freezeButton = byId('useFreezeBtn');
        if (freezeButton) {
            var offer = streak.freezeOffer;
            freezeButton.style.display = offer ? '' : 'none';
            freezeButton.disabled = !offer || (!offer.canUseOwned && !offer.canBuy);
        }
        var journeyButton = byId('retentionJourneyBtn');
        if (journeyButton) journeyButton.style.display = 'none';
    }

    /** Goals page: the live canvas flame from RewardFX (same as the streak celebration), rebuilt when the tier changes. */
    function mountFlame(flame, level, lit) {
        if (!flame.classList.contains('gp-flame') || !window.RewardFX || !RewardFX.fireEngine) return;
        var key = level + (lit ? ':lit' : ':cold');
        if (flame.dataset.fire === key) return;
        flame.dataset.fire = key;
        if (!lit) {
            // unlit: the grey artwork from CSS reads better at card size than the tiny canvas pilot flame
            flame.replaceChildren();
            flame.classList.remove('has-canvas');
            return;
        }
        var canvas = document.createElement('canvas');
        canvas.className = 'gp-flame__canvas';
        flame.replaceChildren(canvas);
        flame.classList.add('has-canvas');
        RewardFX.fireEngine(canvas, level).ignite();
    }

    function renderWeek(week) {
        var card = byId('streakCard');
        if (!card || !week) return;
        var strip = byId('streakWeek');
        if (!strip) {
            strip = document.createElement('div');
            strip.id = 'streakWeek';
            strip.className = 'goals-streak-week';
            strip.setAttribute('aria-label', '7 ngày gần nhất');
            var stats = byId('streakStats');
            if (stats) stats.parentNode.insertBefore(strip, stats); else card.appendChild(strip);
        }
        var labels = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
        var names = { COMPLETED: 'đã hoàn thành', FROZEN: 'được bảo vệ', MISSED: 'bỏ lỡ', TODAY: 'hôm nay' };
        strip.replaceChildren();
        week.forEach(function (day) {
            var date = new Date(day.date + 'T00:00:00');
            var node = document.createElement('span');
            node.className = 'goals-streak-day is-' + day.state.toLowerCase();
            node.textContent = labels[date.getDay()];
            node.title = day.date + ' · ' + (names[day.state] || '');
            strip.appendChild(node);
        });
    }

    function renderFreezeOffer(streak) {
        var card = byId('streakCard');
        if (!card) return;
        var box = byId('streakFreezeOffer');
        var offer = streak.freezeOffer;
        if (!offer) {
            if (box) box.remove();
            return;
        }
        if (!box) {
            box = document.createElement('div');
            box.id = 'streakFreezeOffer';
            box.className = 'goals-freeze-offer';
            box.setAttribute('role', 'alert');
            var stats = byId('streakStats');
            if (stats && stats.nextSibling) card.insertBefore(box, stats.nextSibling); else card.appendChild(box);
        }
        var deadline = new Date(offer.deadline);
        box.replaceChildren();
        var title = document.createElement('strong');
        title.textContent = 'Bạn đã lỡ ngày ' + formatDate(offer.missedDate);
        var text = document.createElement('p');
        text.textContent = 'Dùng thẻ bảo vệ trước ' + deadline.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
            + ' ' + deadline.toLocaleDateString('vi-VN') + ' để giữ chuỗi ' + offer.streakAtRisk + ' ngày.'
            + (doneAfterMiss(streak) ? ' Hôm nay bạn đã học xong, chuỗi sẽ thành ' + (offer.streakAtRisk + 1) + ' ngày.'
                : ' Hôm nay vẫn cần hoàn thành mục tiêu để chuỗi lên ' + (offer.streakAtRisk + 1) + ' ngày.');
        var actions = document.createElement('div');
        actions.className = 'goals-freeze-offer__actions';
        actions.append(
            freezeButton(offer.canUseOwned ? 'Dùng 1 thẻ (còn ' + streak.freezeCount + ')' : 'Hết thẻ bảo vệ', 'use', !offer.canUseOwned, 'btn--primary'),
            freezeButton('Mua 1 thẻ với ' + offer.price + ' CP', 'buy', !offer.canBuy, 'btn--secondary'),
            freezeButton('Bỏ qua', 'decline', false, 'btn--ghost'));
        box.append(title, text, actions);
    }

    /** True when today's goal was already finished after the missed day (ISO dates compare as text). */
    function doneAfterMiss(streak) {
        var offer = streak.freezeOffer;
        return !!(offer && streak.lastCompletedDate && streak.lastCompletedDate > offer.missedDate);
    }

    function freezeButton(label, action, disabled, variant) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn--sm ' + variant;
        button.dataset.freezeAction = action;
        button.disabled = disabled;
        button.textContent = label;
        return button;
    }

    function formatDate(value) {
        var date = new Date(value + 'T00:00:00');
        return date.toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit' });
    }

    function renderMissions(missions) {
        var list = byId('dailyMissionList');
        if (!list) return;
        var summary = byId('missionSummary');
        if (summary) {
            var claimedCount = missions.filter(function (mission) { return mission.claimed; }).length;
            summary.textContent = 'Đã nhận ' + claimedCount + '/' + missions.length;
            summary.classList.toggle('is-complete', missions.length > 0 && claimedCount === missions.length);
        }
        list.replaceChildren();
        missions.forEach(function (mission) {
            var row = document.createElement('article');
            row.className = 'retention-mission' + (mission.completed ? ' is-complete' : '') + (mission.claimed ? ' is-claimed' : '');

            var iconBox = document.createElement('div');
            iconBox.className = 'retention-mission__icon';
            var iconElement = document.createElement('i');
            iconElement.className = missionIcon(mission.code);
            iconElement.setAttribute('aria-hidden', 'true');
            iconBox.appendChild(iconElement);

            var body = document.createElement('div');
            var title = document.createElement('h3');
            title.textContent = mission.title;
            var detail = document.createElement('p');
            detail.textContent = mission.progress + '/' + mission.target + ' · +' + mission.reward + ' CP';
            var bar = document.createElement('div');
            bar.className = 'retention-mission__bar';
            var fill = document.createElement('span');
            fill.style.width = Math.min(100, Math.round(mission.progress * 100 / Math.max(1, mission.target))) + '%';
            bar.appendChild(fill);
            body.append(title, detail, bar);

            var action = document.createElement('div');
            action.className = 'retention-mission__action';
            if (mission.claimed) {
                var claimed = document.createElement('span');
                claimed.className = 'retention-mission__reward' + (mission.code === lastClaimed ? ' is-fresh' : '');
                claimed.textContent = 'Đã nhận';
                action.appendChild(claimed);
            } else if (mission.completed) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'btn btn--primary btn--sm';
                button.dataset.missionCode = mission.code;
                button.dataset.missionTitle = mission.title;
                button.textContent = 'Nhận +' + mission.reward;
                action.appendChild(button);
            } else {
                var pending = document.createElement('span');
                pending.className = 'retention-mission__reward';
                pending.textContent = 'Đang thực hiện';
                action.appendChild(pending);
            }
            row.append(iconBox, body, action);
            list.appendChild(row);
        });
    }

    function missionIcon(code) {
        if (code === 'DAILY_CHECK_IN') return 'fa-solid fa-calendar-check';
        if (code === 'COMPLETE_LESSON') return 'fa-solid fa-book-open-reader';
        if (code === 'COMPLETE_QUIZ') return 'fa-solid fa-circle-question';
        if (code === 'PUBLISH_SHADOWING') return 'fa-solid fa-microphone-lines';
        return 'fa-solid fa-star';
    }

    var REASON_LABELS = {
        DAILY_CHECKIN: 'Điểm danh',
        STUDY_REWARD: 'Hoàn thành bài học',
        QUIZ_REWARD: 'Làm bài quiz',
        SHADOWING_REWARD: 'Đăng Shadowing',
        DAILY_GOAL_REWARD: 'Hoàn thành mục tiêu ngày',
        STREAK_MILESTONE_REWARD: 'Cột mốc chuỗi ngày',
        STREAK_FREEZE: 'Mua thẻ bảo vệ',
        CREATOR_REWARD: 'Thưởng người sáng tạo',
        WORD_CONTRIBUTION_REWARD: 'Đóng góp từ điển',
        VIDEO_UNLOCK: 'Mở khoá video',
        ADMIN_GRANT: 'Quản trị viên tặng',
        ADMIN_DEDUCT: 'Quản trị viên trừ CP',
        CREDIT_PURCHASE: 'Mua CP',
        REFUND: 'Hoàn điểm',
        GAME_ITEM_PURCHASE: 'Mua đồ trong Trò chơi'
    };

    async function loadCreditHistory() {
        var list = byId('creditHistoryList');
        if (!list) return;
        try {
            var account = await api('/api/credits/me');
            list.replaceChildren();
            var items = (account.transactions || []).slice(0, 12);
            if (!items.length) {
                var empty = document.createElement('li');
                empty.className = 'goals-credit-history__empty';
                empty.textContent = 'Chưa có giao dịch nào. Hoàn thành nhiệm vụ để nhận CP đầu tiên!';
                list.appendChild(empty);
                return;
            }
            items.forEach(function (item) {
                var row = document.createElement('li');
                row.className = 'goals-credit-history__item' + (item.amount < 0 ? ' is-spend' : '');
                var label = document.createElement('span');
                label.textContent = item.note || REASON_LABELS[item.reason] || item.reason;
                var time = document.createElement('small');
                time.textContent = new Date(item.createdAt).toLocaleString('vi-VN',
                    { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' });
                var amount = document.createElement('b');
                amount.textContent = (item.amount > 0 ? '+' : '') + item.amount + ' CP';
                var text = document.createElement('div');
                text.append(label, time);
                row.append(text, amount);
                list.appendChild(row);
            });
        } catch (error) {
            list.replaceChildren();
        }
    }

    function render(data) {
        current = data;
        animateNumber('retentionCredit', Number(data.creditBalance), '', true);
        renderGoal(data.dailyGoal);
        renderStreak(data.streak);
        renderMissions(data.missions);
        renderCheer(data);
    }

    /** Kitsu's line on the goals page: the most useful next step for today. */
    function renderCheer(data) {
        var cheer = byId('goalsCheer');
        if (!cheer) return;
        var goal = data.dailyGoal;
        var claimable = data.missions.filter(function (mission) { return mission.completed && !mission.claimed; }).length;
        var message;
        if (data.streak.status === 'AT_RISK' && data.streak.freezeOffer) {
            message = 'Ôi! Chuỗi ' + data.streak.freezeOffer.streakAtRisk + ' ngày đang gặp nguy, dùng thẻ bảo vệ để giữ lửa nhé.';
        } else if (claimable > 0) {
            message = 'Bạn có ' + claimable + ' phần thưởng đang chờ nhận bên dưới!';
        } else if (goal.completed) {
            message = 'Tuyệt vời! Bạn đã hoàn thành mục tiêu hôm nay. Hẹn gặp lại ngày mai nhé!';
        } else if (goal.percent > 0) {
            message = 'Cố lên! Còn ' + (100 - goal.percent) + '% nữa là đạt mục tiêu hôm nay.';
        } else {
            message = 'Chào bạn! Học một chút hôm nay để giữ lửa nhé.';
        }
        cheer.textContent = message;
    }

    async function loadDashboard(options) {
        var quiet = options && options.quiet;
        if (!quiet && loading) loading.hidden = false;
        if (errorBox) errorBox.hidden = true;
        try {
            var data = await api('/api/mission/dashboard');
            render(data);
            dashboardLoaded = true;
            if (content) content.hidden = false;
            loadCreditHistory();
            if (window.RewardFX) {
                // The chip is set after the celebrations: they count it up from the old balance.
                await RewardFX.checkPending({ week: data.streak.week });
                RewardFX.setChip({ credits: data.creditBalance, streak: data.streak.currentStreak }, false);
            }
            maybePromptFreeze(data.streak);
            return data;
        } catch (error) {
            if (errorBox) {
                errorBox.textContent = error.message;
                errorBox.hidden = false;
            } else {
                notify(error.message, 'error');
            }
            return null;
        } finally {
            if (loading) loading.hidden = true;
        }
    }

    // ---------- freeze decision ----------
    function maybePromptFreeze(streak) {
        var offer = streak && streak.freezeOffer;
        if (!offer || !window.RewardFX) return;
        var fromLink = new URLSearchParams(window.location.search).get('freeze') === offer.missedDate;
        var key = FREEZE_PROMPT_KEY + offer.missedDate;
        try {
            if (!fromLink && sessionStorage.getItem(key)) return;
            sessionStorage.setItem(key, '1');
        } catch (e) { /* ignore */ }
        promptFreeze(streak);
    }

    async function promptFreeze(streak) {
        var offer = streak.freezeOffer;
        var choice = await RewardFX.prompt({
            variant: 'ice',
            eyebrow: 'Chuỗi đang gặp nguy',
            title: 'Giữ chuỗi ' + offer.streakAtRisk + ' ngày?',
            message: 'Bạn chưa hoàn thành mục tiêu ' + formatDate(offer.missedDate)
                + '. Dùng thẻ bảo vệ trước khi hết hôm nay để chuỗi không bị mất'
                + (doneAfterMiss(streak) ? '.' : ', rồi hoàn thành mục tiêu hôm nay để chuỗi lên '
                    + (offer.streakAtRisk + 1) + ' ngày.'),
            actions: [
                { label: offer.canUseOwned ? 'Dùng 1 thẻ bảo vệ (còn ' + streak.freezeCount + ')' : 'Bạn đã hết thẻ bảo vệ',
                    value: 'use', variant: 'ice', disabled: !offer.canUseOwned },
                { label: offer.canBuy ? 'Mua 1 thẻ với ' + offer.price + ' CP' : 'Chưa đủ ' + offer.price + ' CP để mua',
                    value: 'buy', disabled: !offer.canBuy },
                { label: 'Để sau', value: 'dismiss', variant: 'ghost' }
            ]
        });
        if (choice === 'use' || choice === 'buy') await decideFreeze(choice, null);
    }

    async function decideFreeze(action, source) {
        if (action === 'decline') {
            var confirmed = window.confirm('Bỏ qua sẽ làm mất chuỗi hiện tại. Bạn chắc chứ?');
            if (!confirmed) return;
            try {
                await api('/api/mission/streak/freeze/decline', { method: 'POST' });
                notify('Đã bỏ qua. Hãy bắt đầu chuỗi mới hôm nay nhé!', 'info');
            } catch (error) {
                notify(error.message, 'error');
            }
            await loadDashboard({ quiet: true });
            return;
        }
        try {
            var result = await api('/api/mission/streak/freeze/use', {
                method: 'POST',
                body: JSON.stringify({ purchase: action === 'buy' })
            });
            if (window.RewardFX) await RewardFX.freezeUsed(result, source);
        } catch (error) {
            notify(error.message, 'error');
        }
        await loadDashboard({ quiet: true });
    }

    // ---------- events ----------
    var missionList = byId('dailyMissionList');
    if (missionList) {
        missionList.addEventListener('click', async function (event) {
            var button = event.target.closest('[data-mission-code]');
            if (!button) return;
            button.disabled = true;
            try {
                var result = await api('/api/mission/missions/' + encodeURIComponent(button.dataset.missionCode) + '/claim',
                    { method: 'POST' });
                if (result.alreadyClaimed) {
                    notify('Phần thưởng này đã được nhận trước đó.', 'info');
                } else if (window.RewardFX) {
                    await RewardFX.claim(result);
                }
                lastClaimed = button.dataset.missionCode;
                await loadDashboard({ quiet: true });
                lastClaimed = null;
            } catch (error) {
                notify(error.message, 'error');
                button.disabled = false;
            }
        });
    }

    var streakCard = byId('streakCard');
    if (streakCard) {
        streakCard.addEventListener('click', function (event) {
            var button = event.target.closest('[data-freeze-action]');
            if (!button) return;
            button.disabled = true;
            decideFreeze(button.dataset.freezeAction, button).finally(function () { button.disabled = false; });
        });
    }

    var useFreezeButton = byId('useFreezeBtn');
    if (useFreezeButton) {
        useFreezeButton.addEventListener('click', function () {
            if (current && current.streak && current.streak.freezeOffer) promptFreeze(current.streak);
        });
    }

    var targetForm = byId('dailyTargetForm');
    if (targetForm) {
        targetForm.addEventListener('submit', async function (event) {
            event.preventDefault();
            var form = event.currentTarget;
            if (!form.checkValidity()) {
                form.reportValidity();
                return;
            }
            var button = byId('saveTargetBtn');
            if (button) button.disabled = true;
            try {
                await api('/api/mission/targets', {
                    method: 'PUT',
                    body: JSON.stringify({
                        dailyVocabTarget: Number(byId('dailyVocabTarget').value),
                        dailyQuizTarget: Number(byId('dailyQuizTarget').value),
                        dailyStudyMinutes: Number(byId('dailyStudyMinutes').value)
                    })
                });
                var dialog = form.closest('dialog');
                if (dialog && dialog.open) dialog.close('saved');
                notify('Đã cập nhật mục tiêu học tập.', 'success');
                await loadDashboard({ quiet: true });
            } catch (error) {
                notify(error.message, 'error');
            } finally {
                if (button) button.disabled = false;
            }
        });
    }

    async function start() {
        if (dashboardLoaded) return;
        await syncTimeZone();
        await loadDashboard();
    }

    window.JlasGoals = { reload: function () { return loadDashboard({ quiet: true }); } };

    document.querySelectorAll('[data-tab="learning"]').forEach(function (button) {
        button.addEventListener('click', start);
    });
    if (window.INITIAL_TAB === 'learning' || panel.classList.contains('is-active') || byId('goalsApp')) {
        start();
    }
})();
