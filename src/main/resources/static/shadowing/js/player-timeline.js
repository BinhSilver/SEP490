(function () {
    'use strict';
    const video = document.getElementById('main-video');
    const range = document.getElementById('timeline-track');
    const time = document.getElementById('timeline-time');
    if (!video || !range) return;
    function format(seconds) {
        const value = Math.max(0, Math.floor(seconds || 0));
        return Math.floor(value / 60) + ':' + String(value % 60).padStart(2, '0');
    }
    function update() {
        const ready = Number.isFinite(video.duration) && video.duration > 0;
        range.disabled = !ready || Boolean(window.shadowingListenRecord?.busy());
        const percent = ready ? Math.min(100, Math.max(0, video.currentTime / video.duration * 100)) : 0;
        range.value = String(percent);
        range.style.setProperty('--seek-progress', percent + '%');
        const label = format(video.currentTime) + ' / ' + format(ready ? video.duration : 0);
        range.setAttribute('aria-valuetext', label);
        if (time) time.textContent = label;
    }
    range.addEventListener('input', function () {
        if (window.shadowingListenRecord?.busy()) return;
        if (!Number.isFinite(video.duration) || video.duration <= 0) return;
        const target = Math.max(0, Math.min(100, Number(range.value))) / 100 * video.duration;
        if (window.seekShadowingVideo) window.seekShadowingVideo(target);
        else video.currentTime = target;
        update();
    });
    ['loadedmetadata', 'durationchange', 'timeupdate', 'seeked', 'emptied', 'ended'].forEach(function (event) {
        video.addEventListener(event, update);
    });
    update();
})();
