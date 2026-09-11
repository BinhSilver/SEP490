(function () {
    'use strict';

    var panel = document.getElementById('panel-learning') || document.getElementById('goalsApp');
    if (!panel || typeof JlasAuth === 'undefined') return;

    var loading = document.getElementById('retentionLoading');
    var content = document.getElementById('retentionContent');
    var errorBox = document.getElementById('retentionError');
    var dashboardLoaded = false;
    var onboardingOpened = false;

    window.onRetentionOnboardingComplete = function () {
        dashboardLoaded = false;
        loadDashboard();
    };

    function notify(message, type) {
        if (window.JlasUI && typeof JlasUI.toast === 'function') {
            JlasUI.toast(message, type || 'info');
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

    function setText(id, value) {
        var element = document.getElementById(id);
        if (element) element.textContent = value;
    }

    function renderDashboard(data) {
        setText('retentionCredit', data.creditBalance);
        setText('dailyGoalPercent', data.progress.overallPercent + '%');
        setText('dailyVocabProgress', data.progress.vocabularyLearned + '/' + data.targets.dailyVocabTarget);
        setText('dailyQuizProgress', data.progress.quizzesCompleted + '/' + data.targets.dailyQuizTarget);
        setText('dailyMinutesProgress', data.progress.studyMinutes + '/' + data.targets.dailyStudyMinutes);
        var ring = document.getElementById('dailyGoalRing');
        ring.style.setProperty('--goal', (data.progress.overallPercent * 3.6) + 'deg');
        ring.setAttribute('aria-valuenow', data.progress.overallPercent);
        var ringValues = ring.querySelectorAll('.ring-value');
        if (ringValues.length >= 3) {
            var percent = Math.max(0, Math.min(100, data.progress.overallPercent)) / 100;
            ringValues[0].style.strokeDashoffset = 314 * (1 - percent);
            ringValues[1].style.strokeDashoffset = 239 * (1 - Math.min(1, data.progress.quizPercent / 100));
            ringValues[2].style.strokeDashoffset = 163 * (1 - Math.min(1, data.progress.vocabularyPercent / 100));
        }

        setText('currentStreak', data.streak.currentStreak + ' ngày');
        setText('longestStreak', data.streak.longestStreak);
        setText('freezeCount', data.streak.freezeCount);
        if (data.streak.nextMilestone > 0) {
            setText('streakMilestone', 'Còn ' + (data.streak.nextMilestone - data.streak.currentStreak)
                + ' ngày để nhận ' + data.streak.nextMilestoneReward + ' điểm.');
        } else {
            setText('streakMilestone', 'Bạn đã chinh phục toàn bộ mốc chuỗi ngày hiện tại.');
        }
        var freezeButton = document.getElementById('useFreezeBtn');
        freezeButton.disabled = data.streak.currentStreak <= 0 || data.streak.freezeCount <= 0 || data.progress.completed
            || data.streak.protectedDate === data.date;

        document.getElementById('dailyVocabTarget').value = data.targets.dailyVocabTarget;
        document.getElementById('dailyQuizTarget').value = data.targets.dailyQuizTarget;
        document.getElementById('dailyStudyMinutes').value = data.targets.dailyStudyMinutes;

        renderMissions(data.missions);
        loadLeaderboard();
        if (!data.streak.onboardingCompleted && !onboardingOpened && window.RetentionOnboarding) {
            onboardingOpened = true;
            window.RetentionOnboarding.start(data.streak);
        }
    }

    async function loadLeaderboard() {
        var list = document.getElementById('retentionLeaderboardList');
        var meta = document.getElementById('retentionLeaderboardMeta');
        if (!list) return;
        try {
            var data = await api('/api/v1/retention/leaderboard');
            list.replaceChildren();
            if (!data.users || !data.users.length) {
                var empty = document.createElement('p');
                empty.className = 'retention-leaderboard__empty';
                empty.textContent = 'Chưa có dữ liệu xếp hạng.';
                list.appendChild(empty);
                return;
            }
            data.users.forEach(function (user) {
                var row = document.createElement('div');
                row.className = 'retention-leaderboard__row' + (user.currentUser ? ' is-current' : '');
                var rank = document.createElement('span');
                rank.className = 'retention-leaderboard__rank';
                rank.textContent = '#' + user.rank;
                var avatar = document.createElement('span');
                avatar.className = 'retention-leaderboard__avatar';
                if (user.avatar) {
                    var image = document.createElement('img');
                    image.src = user.avatar;
                    image.alt = '';
                    avatar.appendChild(image);
                } else {
                    avatar.textContent = (user.displayName || '?').trim().charAt(0).toUpperCase();
                }
                var name = document.createElement('span');
                name.className = 'retention-leaderboard__name';
                name.textContent = user.displayName + (user.currentUser ? ' (Bạn)' : '');
                var points = document.createElement('span');
                points.className = 'retention-leaderboard__points';
                points.textContent = user.points + ' điểm';
                row.append(rank, avatar, name, points);
                list.appendChild(row);
            });
            if (meta) {
                meta.textContent = data.currentRank === 1
                    ? 'Bạn đang dẫn đầu bảng xếp hạng.'
                    : 'Hạng của bạn: #' + data.currentRank + '. Cố thêm ' + data.pointsToNextRank + ' điểm để bứt phá.';
            }
        } catch (error) {
            list.replaceChildren();
            if (meta) meta.textContent = 'Chưa thể tải bảng xếp hạng.';
        }
    }

    function renderMissions(missions) {
        var list = document.getElementById('dailyMissionList');
        list.replaceChildren();
        missions.forEach(function (mission) {
            var row = document.createElement('article');
            row.className = 'retention-mission' + (mission.completed ? ' is-complete' : '');

            var icon = document.createElement('div');
            icon.className = 'retention-mission__icon';
            var iconElement = document.createElement('i');
            iconElement.className = missionIcon(mission.code);
            iconElement.setAttribute('aria-hidden', 'true');
            icon.appendChild(iconElement);

            var body = document.createElement('div');
            var title = document.createElement('h3');
            title.textContent = mission.title;
            var detail = document.createElement('p');
            detail.textContent = mission.progress + '/' + mission.target + ' · +' + mission.rewardPoints + ' điểm';
            body.append(title, detail);

            var action = document.createElement('div');
            action.className = 'retention-mission__action';
            if (mission.claimed) {
                var claimed = document.createElement('span');
                claimed.className = 'retention-mission__reward';
                claimed.textContent = 'Đã nhận';
                action.appendChild(claimed);
            } else if (mission.completed || mission.code === 'DAILY_CHECK_IN') {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'btn btn--primary btn--sm';
                button.dataset.missionCode = mission.code;
                button.textContent = 'Nhận +' + mission.rewardPoints;
                action.appendChild(button);
            } else {
                var pending = document.createElement('span');
                pending.className = 'retention-mission__reward';
                pending.textContent = 'Đang thực hiện';
                action.appendChild(pending);
            }
            row.append(icon, body, action);
            list.appendChild(row);
        });
    }

    function missionIcon(code) {
        if (code === 'DAILY_CHECK_IN') return 'fa-solid fa-calendar-check';
        if (code === 'COMPLETE_LESSON') return 'fa-solid fa-book-open-reader';
        if (code === 'COMPLETE_QUIZ') return 'fa-solid fa-circle-question';
        return 'fa-solid fa-microphone-lines';
    }

    function showCelebrationOnce(date, streak) {
        var key = 'jlas-retention-celebrated-' + date;
        if (localStorage.getItem(key)) return;
        localStorage.setItem(key, '1');
        var overlay = document.createElement('div');
        overlay.id = 'retentionCelebration';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', 'Kitsu-chan chúc mừng hoàn thành mục tiêu');
        var card = document.createElement('div');
        card.id = 'retentionCelebrationCard';
        var template = document.getElementById('retentionKitsuTemplate');
        var guide = template && template.content.firstElementChild
            ? template.content.firstElementChild.cloneNode(true)
            : document.createElement('div');
        var title = guide.querySelector && guide.querySelector('.kitsu-guide__title');
        var message = guide.querySelector && guide.querySelector('.kitsu-guide__message');
        if (title) title.textContent = 'Kitsu-chan chúc mừng!';
        if (message) message.textContent = 'Bạn đã hoàn thành mục tiêu hôm nay. Chuỗi học hiện là ' + streak + ' ngày!';
        var close = document.createElement('button');
        close.type = 'button';
        close.className = 'btn btn--primary';
        close.textContent = 'Tiếp tục học';
        close.addEventListener('click', function () { overlay.remove(); });
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay) overlay.remove();
        });
        overlay.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') overlay.remove();
        });
        card.append(guide, close);
        overlay.appendChild(card);
        document.body.appendChild(overlay);
        close.focus();
    }

    async function loadDashboard() {
        loading.hidden = false;
        content.hidden = true;
        errorBox.hidden = true;
        try {
            var data = await api('/api/v1/user/retention-dashboard');
            renderDashboard(data);
            dashboardLoaded = true;
            content.hidden = false;
        } catch (error) {
            errorBox.textContent = error.message;
            errorBox.hidden = false;
        } finally {
            loading.hidden = true;
        }
    }

    document.getElementById('dailyMissionList').addEventListener('click', async function (event) {
        var button = event.target.closest('[data-mission-code]');
        if (!button) return;
        var missionRow = button.closest('.retention-mission');
        var missionTitle = missionRow && missionRow.querySelector('h3')
            ? missionRow.querySelector('h3').textContent : 'Nhiệm vụ hoàn thành!';
        button.disabled = true;
        try {
            var result = await api('/api/v1/users/claim-quest-reward', {
                method: 'POST',
                body: JSON.stringify({ missionCode: button.dataset.missionCode })
            });
            if (!result.alreadyClaimed && window.RetentionReward) {
                window.RetentionReward.show({
                    points: result.rewardPoints,
                    balance: result.creditBalance,
                    missionTitle: missionTitle
                });
            }
            notify(result.alreadyClaimed ? 'Phần thưởng này đã được nhận trước đó.'
                : 'Đã nhận +' + result.rewardPoints + ' điểm.', 'success');
            await loadDashboard();
        } catch (error) {
            notify(error.message, 'error');
            button.disabled = false;
        }
    });

    document.getElementById('dailyTargetForm').addEventListener('submit', async function (event) {
        event.preventDefault();
        if (!event.currentTarget.checkValidity()) {
            event.currentTarget.reportValidity();
            return;
        }
        var button = document.getElementById('saveTargetBtn');
        button.disabled = true;
        try {
            await api('/api/v1/user/targets', {
                method: 'POST',
                body: JSON.stringify({
                    dailyVocabTarget: Number(document.getElementById('dailyVocabTarget').value),
                    dailyQuizTarget: Number(document.getElementById('dailyQuizTarget').value),
                    dailyStudyMinutes: Number(document.getElementById('dailyStudyMinutes').value)
                })
            });
            notify('Đã cập nhật mục tiêu học tập.', 'success');
            await loadDashboard();
        } catch (error) {
            notify(error.message, 'error');
        } finally {
            button.disabled = false;
        }
    });

    document.getElementById('useFreezeBtn').addEventListener('click', async function (event) {
        event.currentTarget.disabled = true;
        try {
            await api('/api/v1/user/streak-freeze', { method: 'POST', body: '{}' });
            notify('Chuỗi ngày hôm nay đã được bảo vệ.', 'success');
            await loadDashboard();
        } catch (error) {
            notify(error.message, 'error');
            event.currentTarget.disabled = false;
        }
    });

    var journeyButton = document.getElementById('retentionJourneyBtn');
    if (journeyButton) {
        journeyButton.addEventListener('click', function () {
            if (window.RetentionOnboarding) {
                onboardingOpened = true;
                window.RetentionOnboarding.start({ selectedGoalDays: 7 });
            }
        });
    }

    function loadDashboardOnce() {
        if (!dashboardLoaded) loadDashboard();
    }

    document.querySelectorAll('[data-tab="learning"]').forEach(function (button) {
        button.addEventListener('click', loadDashboardOnce);
    });
    if (window.INITIAL_TAB === 'learning' || panel.classList.contains('is-active') || document.getElementById('goalsApp')) {
        loadDashboardOnce();
    }
})();
