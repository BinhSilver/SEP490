(function () {
    'use strict';

    function text(value) {
        return value === null || value === undefined ? '' : String(value);
    }

    function closeOverlay(overlay, previousFocus) {
        if (overlay && overlay.parentNode) overlay.remove();
        if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
    }

    function showReward(data) {
        var previousFocus = document.activeElement;
        var old = document.getElementById('retentionRewardOverlay');
        if (old) old.remove();

        var overlay = document.createElement('div');
        overlay.id = 'retentionRewardOverlay';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-labelledby', 'retentionRewardTitle');

        var colors = ['#ff684d', '#ffc857', '#5ec9a6', '#7c83fd', '#f28bb3'];
        for (var burst = 0; burst < 5; burst++) {
            var originX = (16 + burst * 19) + '%';
            var originY = (20 + (burst % 2) * 11) + '%';
            for (var particle = 0; particle < 8; particle++) {
                var angle = (Math.PI * 2 * particle) / 8;
                var distance = 42 + ((burst * 13 + particle * 7) % 32);
                var spark = document.createElement('span');
                spark.className = 'retention-firework';
                spark.style.setProperty('--origin-x', originX);
                spark.style.setProperty('--origin-y', originY);
                spark.style.setProperty('--particle-x', Math.round(Math.cos(angle) * distance) + 'px');
                spark.style.setProperty('--particle-y', Math.round(Math.sin(angle) * distance) + 'px');
                spark.style.setProperty('--particle-delay', (burst * .09 + particle * .025) + 's');
                spark.style.setProperty('--particle-color', colors[(burst + particle) % colors.length]);
                overlay.appendChild(spark);
            }
        }

        var card = document.createElement('section');
        card.id = 'retentionRewardCard';
        var icon = document.createElement('div');
        icon.className = 'retention-reward__icon';
        icon.innerHTML = '<i class="fa-solid fa-wand-magic-sparkles" aria-hidden="true"></i>';
        var eyebrow = document.createElement('p');
        eyebrow.className = 'retention-reward__eyebrow';
        eyebrow.textContent = 'Pháo hoa phần thưởng';
        var points = document.createElement('p');
        points.className = 'retention-reward__points';
        points.textContent = '+' + text(data.points || data.rewardPoints) + ' điểm';
        var title = document.createElement('h2');
        title.id = 'retentionRewardTitle';
        title.className = 'retention-reward__title';
        title.textContent = text(data.missionTitle) || 'Nhiệm vụ hoàn thành!';
        var message = document.createElement('p');
        message.className = 'retention-reward__message';
        message.textContent = 'Chúc mừng! Bạn vừa nhận được phần thưởng.';
        var balance = document.createElement('p');
        balance.className = 'retention-reward__balance';
        balance.textContent = 'Số dư hiện tại: ' + text(data.balance || data.creditBalance) + ' điểm';
        var close = document.createElement('button');
        close.id = 'retentionRewardClose';
        close.type = 'button';
        close.className = 'btn btn--primary';
        close.textContent = 'Tiếp tục học';
        close.addEventListener('click', function () { closeOverlay(overlay, previousFocus); });

        card.append(icon, eyebrow, points, title, message, balance, close);
        overlay.appendChild(card);
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay) closeOverlay(overlay, previousFocus);
        });
        overlay.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') closeOverlay(overlay, previousFocus);
        });
        document.body.appendChild(overlay);
        close.focus();
    }

    window.RetentionReward = { show: showReward };
}());
