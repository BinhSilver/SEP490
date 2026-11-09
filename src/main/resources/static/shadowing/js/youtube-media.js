/* Phát video YouTube bằng trình phát nhúng của YouTube thay vì file đã tải về server.
   attach() làm cho thẻ <video> sẵn có "đóng vai" một HTMLMediaElement thật (currentTime, play, pause, tốc độ,
   âm lượng và các sự kiện timeupdate/ended...), nên player, editor, bài tập và chuẩn bị bài tập dùng lại nguyên
   code cũ. Thẻ <video> gốc bị ẩn và không bao giờ tải gì. */
(function (root) {
    'use strict';

    const ID_PATTERN = /(?:youtube\.com\/(?:watch\?v=|embed\/|shorts\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/;
    const POLL_MS = 100;
    const STATE = { ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3 };
    let apiPromise = null;

    /** ID 11 ký tự của video YouTube trong một link bất kỳ, hoặc null. */
    function idFromUrl(url) {
        const match = ID_PATTERN.exec(String(url || ''));
        return match ? match[1] : null;
    }

    function loadApi() {
        if (root.YT && root.YT.Player) return Promise.resolve(root.YT);
        if (apiPromise) return apiPromise;
        apiPromise = new Promise((resolve, reject) => {
            const previous = root.onYouTubeIframeAPIReady;
            root.onYouTubeIframeAPIReady = () => {
                if (typeof previous === 'function') previous();
                resolve(root.YT);
            };
            const script = document.createElement('script');
            script.src = 'https://www.youtube.com/iframe_api';
            script.async = true;
            script.onerror = () => { apiPromise = null; reject(new Error('Không tải được trình phát YouTube.')); };
            document.head.appendChild(script);
        });
        return apiPromise;
    }

    function safe(read, fallback) {
        try {
            const value = read();
            return Number.isFinite(value) ? value : fallback;
        } catch (_) {
            return fallback;
        }
    }

    /** Tốc độ gần nhất mà YouTube hỗ trợ (0.25, 0.5 ... 2). */
    function nearestRate(rates, wanted) {
        const list = Array.isArray(rates) && rates.length ? rates : [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
        return list.reduce((best, rate) => Math.abs(rate - wanted) < Math.abs(best - wanted) ? rate : best, list[0]);
    }

    const STYLE = '.shadowing-youtube-surface{position:relative;width:100%;aspect-ratio:16/9;background:#000;'
        + 'border-radius:12px;overflow:hidden}'
        + '.shadowing-youtube-surface iframe{position:absolute;inset:0;width:100%;height:100%;border:0}'
        + '.shadowing-youtube-paused{position:absolute;left:50%;top:50%;width:72px;height:72px;margin:-36px 0 0 -36px;'
        + 'z-index:1;border-radius:50%;background:#000;pointer-events:none}'
        + '.shadowing-youtube-paused::after{content:"";position:absolute;inset:22px 20px 22px 26px;background:#fff;'
        + 'clip-path:polygon(0 0,100% 50%,0 100%)}'
        + '.shadowing-youtube-shield{position:absolute;inset:0;z-index:1;cursor:pointer}'
        + '.shadowing-youtube-error{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;'
        + 'justify-content:center;gap:8px;padding:16px;color:#fff;text-align:center;font-size:14px;line-height:1.5}'
        + '.shadowing-youtube-error a{color:#fff;font-weight:600;text-decoration:underline}';

    function ensureStyles() {
        if (document.getElementById('shadowing-youtube-style')) return;
        const style = document.createElement('style');
        style.id = 'shadowing-youtube-style';
        style.textContent = STYLE;
        document.head.appendChild(style);
    }

    function attach(media, youtubeId, options) {
        if (media.__youtube) { media.__youtube.load(youtubeId); return media.__youtube; }
        const opts = options || {};
        ensureStyles();
        const host = document.createElement('div');
        host.className = 'shadowing-youtube-surface';
        const mount = document.createElement('div');
        host.appendChild(mount);
        // Keeps the pointer off the iframe, so YouTube's hover buttons stay away; playback goes through our controls.
        // Touch browsers may only start a video from a tap on the iframe, so there it waits for the first play.
        const shield = document.createElement('div');
        const touch = typeof root.matchMedia === 'function' && root.matchMedia('(pointer: coarse)').matches;
        shield.className = 'shadowing-youtube-shield';
        shield.setAttribute('aria-hidden', 'true');
        shield.hidden = touch;
        shield.addEventListener('click', () => {
            if (media.paused) media.play().catch(() => { }); else media.pause();
        });
        // YouTube always draws a play button on a paused video and no player option removes it, so this sits on top.
        const pausedMark = document.createElement('div');
        pausedMark.className = 'shadowing-youtube-paused';
        pausedMark.setAttribute('aria-hidden', 'true');
        pausedMark.hidden = true;
        host.appendChild(pausedMark);
        host.appendChild(shield);
        media.insertAdjacentElement('afterend', host);
        media.style.setProperty('display', 'none', 'important');

        let player = null, ready = false, state = -1, wantPlay = false;
        let pendingSeek = null, lastSeek = null, rate = 1, muted = false, volume = 1, lastError = null;
        let knownDuration = Number(opts.duration) > 0 ? Number(opts.duration) : NaN;
        let timer = null, currentId = youtubeId, lastDuration = NaN;
        const waiters = [];
        const emit = name => media.dispatchEvent(new Event(name));
        const define = (name, descriptor) => Object.defineProperty(media, name, Object.assign({ configurable: true }, descriptor));

        function whenReady() {
            if (ready) return Promise.resolve();
            return new Promise((resolve, reject) => {
                waiters.push(resolve);
                setTimeout(() => reject(new Error('Trình phát YouTube chưa sẵn sàng.')), 15000);
            });
        }

        function startTimer() {
            if (timer) return;
            timer = setInterval(() => {
                const duration = media.duration;
                if (duration !== lastDuration && Number.isFinite(duration)) { lastDuration = duration; emit('durationchange'); }
                emit('timeupdate');
            }, POLL_MS);
        }
        function stopTimer() {
            if (timer) { clearInterval(timer); timer = null; }
        }

        function showError(code) {
            stopTimer();
            lastError = { code: Number(code) || 4, message: 'YouTube không phát được video này.' };
            host.textContent = '';
            const box = document.createElement('div');
            box.className = 'shadowing-youtube-error';
            box.setAttribute('role', 'alert');
            const embedBlocked = code === 101 || code === 150;
            box.textContent = embedBlocked
                ? 'Chủ video không cho phép phát video này ở trang khác. '
                : 'Không phát được video YouTube này (có thể đã bị xóa hoặc để riêng tư). ';
            const link = document.createElement('a');
            link.href = 'https://www.youtube.com/watch?v=' + encodeURIComponent(currentId);
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            link.textContent = 'Xem trên YouTube';
            box.appendChild(link);
            host.appendChild(box);
            emit('error');
        }

        function onState(event) {
            const previous = state;
            state = event.data;
            if (state === STATE.PLAYING) {
                shield.hidden = false;
                pausedMark.hidden = true;
                startTimer();
                if (previous !== STATE.PLAYING) emit('play');
                emit('playing');
            } else if (state === STATE.PAUSED) {
                stopTimer(); wantPlay = false;
                pausedMark.hidden = false;
                emit('timeupdate'); emit('pause');
            } else if (state === STATE.ENDED) {
                stopTimer(); wantPlay = false;
                pausedMark.hidden = true;
                emit('timeupdate'); emit('pause'); emit('ended');
            } else if (state === STATE.BUFFERING) {
                emit('waiting');
            }
        }

        function onReady() {
            ready = true;
            if (pendingSeek) player.seekTo(pendingSeek, true);
            player.setPlaybackRate(nearestRate(safeRates(), rate));
            if (muted) player.mute();
            player.setVolume(volume * 100);
            waiters.splice(0).forEach(resolve => resolve());
            emit('loadedmetadata'); emit('durationchange'); emit('canplay'); emit('timeupdate');
        }

        function safeRates() {
            try { return player.getAvailablePlaybackRates(); } catch (_) { return null; }
        }

        define('currentTime', {
            get() {
                if (lastSeek && Date.now() - lastSeek.at < 250) return lastSeek.time;
                return ready ? safe(() => player.getCurrentTime(), pendingSeek || 0) : (pendingSeek || 0);
            },
            set(value) {
                const time = Math.max(0, Number(value) || 0);
                pendingSeek = time;
                lastSeek = { time, at: Date.now() };
                if (!ready) return;
                emit('seeking');
                player.seekTo(time, true);
                setTimeout(() => { emit('seeked'); emit('timeupdate'); }, 0);
            }
        });
        define('duration', {
            get() {
                const real = ready ? safe(() => player.getDuration(), 0) : 0;
                return real > 0 ? real : knownDuration;
            }
        });
        define('paused', { get: () => !(state === STATE.PLAYING || (state === STATE.BUFFERING && wantPlay)) });
        define('ended', { get: () => state === STATE.ENDED });
        define('seeking', { get: () => false });
        define('readyState', { get: () => ready ? 4 : 0 });
        define('networkState', { get: () => 1 });
        define('error', { get: () => lastError });
        define('src', { get: () => '', set: () => { } });
        define('currentSrc', { get: () => '' });
        define('playbackRate', {
            get: () => rate,
            set(value) {
                const wanted = Number(value) > 0 ? Number(value) : 1;
                rate = ready ? nearestRate(safeRates(), wanted) : wanted;
                if (ready) player.setPlaybackRate(rate);
                emit('ratechange');
            }
        });
        define('muted', {
            get: () => muted,
            set(value) {
                muted = Boolean(value);
                if (ready) { if (muted) player.mute(); else player.unMute(); }
                emit('volumechange');
            }
        });
        define('volume', {
            get: () => volume,
            set(value) {
                volume = Math.min(1, Math.max(0, Number(value)));
                if (ready) player.setVolume(volume * 100);
                emit('volumechange');
            }
        });
        media.load = () => { };
        media.play = () => {
            wantPlay = true;
            return whenReady().then(() => { player.playVideo(); });
        };
        media.pause = () => {
            wantPlay = false;
            if (ready) player.pauseVideo();
        };
        media.requestFullscreen = () => host.requestFullscreen ? host.requestFullscreen() : Promise.reject(new Error('unsupported'));
        media.webkitEnterFullscreen = media.requestFullscreen;

        const controller = {
            host,
            get youtubeId() { return currentId; },
            load(id) {
                currentId = id;
                state = -1; wantPlay = false; pendingSeek = null; lastSeek = null;
                shield.hidden = touch;
                pausedMark.hidden = true;
                stopTimer();
                if (ready) player.cueVideoById(id);
            },
            destroy() {
                stopTimer();
                try { if (player) player.destroy(); } catch (_) { /* ignore */ }
                host.remove();
                media.style.removeProperty('display');
                delete media.__youtube;
            }
        };
        media.__youtube = controller;

        loadApi().then(YT => {
            player = new YT.Player(mount, {
                videoId: currentId,
                playerVars: { controls: 0, disablekb: 1, rel: 0, modestbranding: 1, playsinline: 1, iv_load_policy: 3, fs: 0, origin: root.location && root.location.origin },
                events: { onReady, onStateChange: onState, onError: event => showError(event.data) }
            });
        }).catch(() => showError(0));
        return controller;
    }

    /**
     * Chọn nguồn phát cho {@code media}: video có file thì phát file qua streamUrl, file không mở được thì chuyển sang
     * trình phát YouTube nếu video có nguồn YouTube; video YouTube không có file (server báo youtubeEmbed) phát nhúng.
     * Nếu không truyền {@code opts.meta} thì tự lấy thông tin video từ API.
     * @returns Promise<boolean> true khi đang dùng YouTube
     */
    async function load(media, videoId, opts) {
        const options = opts || {};
        let meta = options.meta;
        if (!meta) {
            try {
                const headers = typeof root.authHeaders === 'function' ? root.authHeaders() : {};
                const res = await fetch('/api/videos/' + encodeURIComponent(videoId), { headers });
                meta = res.ok ? await res.json() : null;
            } catch (_) {
                meta = null;
            }
        }
        const id = meta ? idFromUrl(meta.sourceUrl) : null;
        if (media.__fileFallback) media.removeEventListener('error', media.__fileFallback);
        media.__fileFallback = null;
        if (id && meta.youtubeEmbed === true) {
            attach(media, id, { duration: meta.duration });
            return true;
        }
        if (id && options.streamUrl) {
            // Only a file that never opened falls back; a network blip mid-video must not restart it on YouTube.
            media.__fileFallback = () => { if (!media.readyState) attach(media, id, { duration: meta.duration }); };
            media.addEventListener('error', media.__fileFallback, { once: true });
        }
        if (options.streamUrl) media.src = options.streamUrl;
        return false;
    }

    root.ShadowingYoutube = { idFromUrl, attach, load, nearestRate };
    if (typeof module === 'object' && module.exports) module.exports = root.ShadowingYoutube;
})(typeof window !== 'undefined' ? window : globalThis);
