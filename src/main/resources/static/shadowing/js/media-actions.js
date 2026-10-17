(function () {
    'use strict';
    document.querySelectorAll('[data-media-action]').forEach(button => {
        const media = document.getElementById(button.dataset.mediaTarget);
        if (!media) return;
        button.addEventListener('click', async () => {
            if (button.dataset.mediaAction === 'mute') {
                media.muted = !media.muted;
                button.setAttribute('aria-pressed', String(media.muted));
                button.setAttribute('aria-label', media.muted ? 'Bật âm thanh' : 'Tắt âm thanh');
                button.firstElementChild.className = 'fa-solid ' + (media.muted ? 'fa-volume-xmark' : 'fa-volume-high');
            } else {
                try {
                    if (document.fullscreenElement) await document.exitFullscreen();
                    else if (media.requestFullscreen) await media.requestFullscreen();
                    else if (media.webkitEnterFullscreen) media.webkitEnterFullscreen();
                } catch (_) { /* Keep inline playback available if fullscreen is denied. */ }
            }
        });
    });
})();
