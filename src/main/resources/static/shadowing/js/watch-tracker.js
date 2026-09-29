/*
 * Reports real listening time to POST /api/videos/{id}/watch. The server counts a view after 30s of
 * playback (seeks excluded), at most once per account per video every 24h.
 */
window.attachWatchTracker = function attachWatchTracker(media, videoId, onResult) {
    const PING_MS = 5000;
    // Normal playback advances ~0.25s per timeupdate (0.5s at 2×); a larger jump is a seek.
    const MAX_STEP_SEC = 2.5;
    let lastTime = null;
    let pendingSec = 0;
    let lastPing = 0;
    let inFlight = false;
    let done = false;

    function headers() {
        return window.authHeaders ? window.authHeaders(true) : { 'Content-Type': 'application/json' };
    }

    async function send() {
        if (done || inFlight || pendingSec <= 0 || !(media.duration > 0)) return;
        inFlight = true;
        lastPing = Date.now();
        const listenedSec = pendingSec;
        pendingSec = 0;
        try {
            const res = await fetch('/api/videos/' + encodeURIComponent(videoId) + '/watch', {
                method: 'POST',
                headers: headers(),
                body: JSON.stringify({ currentTime: media.currentTime, duration: media.duration, listenedSec })
            });
            if (!res.ok) {
                pendingSec += listenedSec;
                return;
            }
            const data = await res.json();
            if (data.trackingDone) done = true;
            if (onResult) onResult(data);
        } catch (err) {
            pendingSec += listenedSec;
        } finally {
            inFlight = false;
        }
    }

    media.addEventListener('seeking', () => { lastTime = null; });
    media.addEventListener('timeupdate', () => {
        if (done) return;
        const now = media.currentTime;
        if (!media.paused && !media.seeking && lastTime !== null) {
            const step = now - lastTime;
            if (step > 0 && step < MAX_STEP_SEC) pendingSec += step;
        }
        lastTime = now;
        if (Date.now() - lastPing >= PING_MS) send();
    });
    media.addEventListener('pause', send);
    media.addEventListener('ended', send);
};
