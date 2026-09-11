(function () {
    'use strict';

    var scenes = Array.from(document.querySelectorAll('[data-demo-scene]'));
    var progress = document.getElementById('demoProgress');
    var counter = document.getElementById('demoStepCounter');
    var next = document.getElementById('demoNext');
    var back = document.getElementById('demoBack');
    var footerHint = document.getElementById('demoFooterHint');
    var liveOnboarding = document.getElementById('retentionLiveOnboarding');
    var liveMode = !!liveOnboarding;
    var demoRestart = document.getElementById('demoRestart');
    var selectedGoal = 7;
    var liveStreak = 1;
    var index = 0;
    var countFrame = null;

    function liveRequest(path, options) {
        var config = options || {};
        config.headers = Object.assign({},
            typeof JlasAuth !== 'undefined' ? JlasAuth.authHeaders() : {},
            config.headers || {});
        if (config.body) config.headers['Content-Type'] = 'application/json';
        return fetch(path, config).then(function (response) {
            return response.json().catch(function () { return {}; }).then(function (payload) {
                if (!response.ok || payload.success === false) {
                    throw new Error(payload.message || 'Không thể lưu thông tin Chuỗi');
                }
                return payload.data;
            });
        });
    }

    function namespaceFoxSvgIds() {
        document.querySelectorAll('.demo-fox svg').forEach(function (svg, index) {
            var ids = new Map();
            svg.querySelectorAll('[id]').forEach(function (element) {
                var oldId = element.id;
                var newId = oldId + '-demo-fox-' + index;
                ids.set(oldId, newId);
                element.id = newId;
            });

            [svg].concat(Array.from(svg.querySelectorAll('*'))).forEach(function (element) {
                Array.from(element.attributes).forEach(function (attribute) {
                    var value = attribute.value;
                    ids.forEach(function (newId, oldId) {
                        value = value.split('url(#' + oldId + ')').join('url(#' + newId + ')');
                        if ((attribute.name === 'href' || attribute.name === 'xlink:href') && value === '#' + oldId) {
                            value = '#' + newId;
                        }
                    });
                    attribute.value = value;
                });
            });
        });
    }

    var hints = [
        'Một khoảnh khắc đáng tự hào', 'Giữ ngọn lửa tiếp tục cháy', 'Một mục tiêu vừa sức',
        'Nhìn thấy bước tiếp theo', 'Bạn luôn có một safety net', 'Học cùng những người khác',
        'Hẹn gặp lại vào ngày mai'
    ];

    scenes.forEach(function (_, i) {
        var dot = document.createElement('button');
        dot.type = 'button';
        dot.setAttribute('aria-label', 'Đến màn hình ' + (i + 1));
        dot.addEventListener('click', function () { show(i); });
        progress.appendChild(dot);
    });

    function animateScene(scene, direction) {
        if (!scene) return;
        if (scene._motionTimer) window.clearTimeout(scene._motionTimer);
        scene.style.transition = 'none';
        scene.style.opacity = '.35';
        scene.style.transform = 'translateY(28px) scale(.94)';

        var animatedChildren = scene.querySelectorAll('.scene-eyebrow, h2, .scene-number, .freeze-number, .scene-copy, .scene-rewards, .result-progress, .week-strip, .streak-speech, .goal-speech, .goal-options, .milestone-path, .freeze-card, .leaderboard, .reminder-heading, .demo-time-field, .done-note');
        animatedChildren.forEach(function (element, childIndex) {
            element.style.transition = 'none';
            element.style.opacity = '.5';
            element.style.transform = 'translateY(' + (18 + childIndex * 2) + 'px) scale(.96)';
        });

        // Force the browser to commit the starting frame before applying the end frame.
        void scene.offsetWidth;
        window.requestAnimationFrame(function () {
            window.requestAnimationFrame(function () {
                scene.style.transition = 'opacity .52s ease, transform .65s cubic-bezier(.22, 1, .36, 1)';
                scene.style.opacity = '1';
                scene.style.transform = 'translateY(0) scale(1)';
                animatedChildren.forEach(function (element, childIndex) {
                    element.style.transition = 'opacity .48s ease ' + (90 + childIndex * 55) + 'ms, transform .58s cubic-bezier(.22, 1, .36, 1) ' + (90 + childIndex * 55) + 'ms';
                    element.style.opacity = '1';
                    element.style.transform = 'translateY(0) scale(1)';
                });
            });
        });

        scene._motionTimer = window.setTimeout(function () {
            scene.style.opacity = '1';
            scene.style.transform = 'none';
            animatedChildren.forEach(function (element) {
                element.style.opacity = '1';
                element.style.transform = 'none';
            });
        }, 1100);
    }

    function countTo(element, target, duration, format) {
        if (!element) return;
        if (countFrame) window.cancelAnimationFrame(countFrame);
        if (element._countTimer) window.clearTimeout(element._countTimer);
        var startedAt = null;
        function display(value) {
            element.textContent = format === 'plus' ? '+' + value : format === 'pad' ? String(value).padStart(2, '0') : String(value);
        }
        function tick(timestamp) {
            if (startedAt === null) startedAt = timestamp;
            var progressValue = Math.min(1, (timestamp - startedAt) / duration);
            var eased = 1 - Math.pow(1 - progressValue, 3);
            var value = Math.round(target * eased);
            display(value);
            if (progressValue < 1) countFrame = window.requestAnimationFrame(tick);
        }
        countFrame = window.requestAnimationFrame(tick);
        element._countTimer = window.setTimeout(function () {
            if (countFrame) window.cancelAnimationFrame(countFrame);
            display(target);
        }, duration + 120);
    }

    function runSceneDetails(sceneIndex, scene) {
        scenes.forEach(function (item) {
            item.classList.remove('scene-run', 'scene-settled');
            if (item._settleTimer) window.clearTimeout(item._settleTimer);
        });
        void scene.offsetWidth;
        window.requestAnimationFrame(function () {
            scene.classList.add('scene-run');
            if (sceneIndex === 1) countTo(scene.querySelector('.scene-number'), liveMode ? liveStreak : 1, 650, 'plain');
            if (sceneIndex === 4) countTo(scene.querySelector('.freeze-number'), selectedGoal === 30 ? 5 : selectedGoal === 14 ? 2 : 1, 720, 'plus');
            if (sceneIndex === 6) countTo(scene.querySelector('.reminder-count'), 12, 850, 'plain');
            scene._settleTimer = window.setTimeout(function () { scene.classList.add('scene-settled'); }, 2150);
        });
    }

    function show(nextIndex) {
        var nextValue = Math.max(0, Math.min(scenes.length - 1, nextIndex));
        var direction = nextValue >= index ? 1 : -1;
        index = nextValue;
        scenes.forEach(function (scene, i) {
            scene.classList.toggle('is-active', i === index);
        });
        animateScene(scenes[index], direction);
        runSceneDetails(index, scenes[index]);
        Array.from(progress.children).forEach(function (dot, i) {
            dot.classList.toggle('is-active', i === index);
            dot.classList.toggle('is-done', i < index);
        });
        counter.textContent = String(index + 1).padStart(2, '0') + ' / ' + String(scenes.length).padStart(2, '0');
        footerHint.textContent = hints[index];
        back.disabled = index === 0;
        back.style.opacity = index === 0 ? '.45' : '1';
        next.querySelector('span').textContent = index === scenes.length - 1 ? 'Xem lại demo' : index === 2 ? 'Chốt mục tiêu' : 'Tiếp tục';
        next.classList.remove('demo-next--enter');
        void next.offsetWidth;
        next.classList.add('demo-next--enter');
        updateGoalContent();
    }

    function updateGoalContent() {
        var timelineTitle = document.getElementById('demoTimelineTitle');
        var reward = document.getElementById('demoFreezeReward');
        if (timelineTitle) timelineTitle.textContent = 'Liệu bạn có thể đạt ' + selectedGoal + ' ngày?';
        if (reward) reward.textContent = '+' + (selectedGoal === 30 ? 5 : selectedGoal === 14 ? 2 : 1);
    }

    function hideLiveOnboarding() {
        if (!liveOnboarding) return;
        liveOnboarding.hidden = true;
        document.body.classList.remove('retention-live-open');
        if (typeof window.onRetentionOnboardingComplete === 'function') {
            window.onRetentionOnboardingComplete();
        }
    }

    function startLiveOnboarding(streak) {
        if (!liveOnboarding) return;
        selectedGoal = Number(streak && streak.selectedGoalDays) || 7;
        liveStreak = Number(streak && streak.currentStreak) || 1;
        document.querySelectorAll('[data-goal]').forEach(function (option) {
            option.classList.toggle('is-selected', Number(option.dataset.goal) === selectedGoal);
        });
        liveOnboarding.hidden = false;
        document.body.classList.add('retention-live-open');
        hydrateLiveLeaderboard();
        var requestedLiveStep = Number(new URLSearchParams(window.location.search).get('step'));
        var startIndex = requestedLiveStep >= 1 && requestedLiveStep <= scenes.length
            ? requestedLiveStep - 1 : 0;
        show(startIndex);
    }

    async function hydrateLiveLeaderboard() {
        if (!liveMode) return;
        var list = liveOnboarding.querySelector('.leaderboard');
        if (!list) return;
        try {
            var data = await liveRequest('/api/v1/retention/leaderboard');
            list.replaceChildren();
            (data.users || []).slice(0, 5).forEach(function (user) {
                var row = document.createElement('div');
                if (user.currentUser) row.className = 'leaderboard__you';
                var rank = document.createElement('b');
                rank.textContent = String(user.rank).padStart(2, '0');
                var avatar = document.createElement('span');
                avatar.className = 'rank-avatar rank-avatar--' + (user.rank % 2 ? 'pink' : 'blue');
                avatar.textContent = (user.displayName || '?').trim().charAt(0).toUpperCase();
                var name = document.createElement('strong');
                name.textContent = user.currentUser ? 'Bạn' : user.displayName;
                var points = document.createElement('em');
                points.textContent = user.points + ' điểm';
                row.append(rank, avatar, name, points);
                list.appendChild(row);
            });
        } catch (error) {
            // Dashboard bên dưới vẫn hiển thị lỗi trạng thái và bảng xếp hạng thật.
        }
    }

    document.getElementById('demoGoalOptions').addEventListener('click', function (event) {
        var button = event.target.closest('[data-goal]');
        if (!button) return;
        selectedGoal = Number(button.dataset.goal);
        document.querySelectorAll('[data-goal]').forEach(function (option) {
            option.classList.toggle('is-selected', option === button);
        });
        updateGoalContent();
    });

    next.addEventListener('click', async function () {
        if (liveMode && index === 2) {
            next.disabled = true;
            try {
                await liveRequest('/api/v1/user/streak-goal', {
                    method: 'POST',
                    body: JSON.stringify({ goalDays: selectedGoal })
                });
                show(index + 1);
            } catch (error) {
                if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(error.message, 'error');
            } finally {
                next.disabled = false;
            }
            return;
        }
        if (liveMode && index === scenes.length - 1) {
            next.disabled = true;
            try {
                var time = document.getElementById('demoReminder').value || '20:00';
                await liveRequest('/api/v1/user/learning-reminder', {
                    method: 'PUT',
                    body: JSON.stringify({ enabled: true, reminderTime: time })
                });
                await liveRequest('/api/v1/user/streak-onboarding/complete', { method: 'POST' });
                hideLiveOnboarding();
            } catch (error) {
                if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(error.message, 'error');
            } finally {
                next.disabled = false;
            }
            return;
        }
        if (index === scenes.length - 1) show(0);
        else show(index + 1);
    });
    back.addEventListener('click', function () { if (index > 0) show(index - 1); });
    if (demoRestart) demoRestart.addEventListener('click', function () { show(0); });
    document.addEventListener('keydown', function (event) {
        if (liveMode && liveOnboarding.hidden) return;
        if (event.key === 'ArrowRight') show(index === scenes.length - 1 ? 0 : index + 1);
        if (event.key === 'ArrowLeft' && index > 0) show(index - 1);
    });

    var requestedStep = Number(new URLSearchParams(window.location.search).get('step'));
    var initialIndex = requestedStep >= 1 && requestedStep <= scenes.length ? requestedStep - 1 : 0;
    namespaceFoxSvgIds();
    show(initialIndex);
    if (liveMode) {
        window.RetentionOnboarding = { start: startLiveOnboarding, close: hideLiveOnboarding };
    }
})();
