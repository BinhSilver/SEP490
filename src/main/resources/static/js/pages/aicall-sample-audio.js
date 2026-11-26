/* Authored voice clips, played in order. No text or voice IDs are sent to runtime TTS. */
(function (root, factory) {
    'use strict';
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else if (root.document) api.mount(root.document, root.fetch.bind(root));
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';
    const MANIFEST_URL = '/audio/ai-call/samples/manifest.json';
    const CLIP_PATH = /^\/audio\/ai-call\/samples\/clips\/[a-f0-9]{64}\.mp3$/;
    const finite = value => typeof value === 'number' && Number.isFinite(value);
    // Pause between turns at normal speed. The authored clips carry a 2-second gap, which made a sample drag; the
    // player keeps the shorter of the two and shortens it further at a faster speed.
    const TURN_PAUSE_SECONDS = 0.7;
    const turnPause = entry => Math.min(entry.gap, TURN_PAUSE_SECONDS);

    function validateEntry(entry, transcript) {
        if (!entry || typeof entry.ready !== 'boolean' || !/^[a-f0-9]{64}$/.test(entry.revision) ||
            !finite(entry.gap) || entry.gap < 0 || entry.gap > 2 || !Array.isArray(entry.lines) ||
            entry.lines.length !== transcript.length || !transcript.length) return null;
        let total = entry.gap * (entry.lines.length - 1);
        for (let i = 0; i < transcript.length; i++) {
            const line = entry.lines[i], source = transcript[i];
            if (!line || !['ai', 'learner'].includes(line.speaker) || line.speaker !== source.speaker ||
                line.text !== source.text || line.translation !== source.translation ||
                typeof line.speakerName !== 'string' || !line.speakerName.trim() || line.speakerName.length > 150 ||
                (line.speaker === 'ai' ? !/^AI\d{2}$/.test(line.speakerCode) : line.speakerCode !== 'LEARNER')) return null;
            if (entry.ready) {
                if (typeof line.audioUrl !== 'string' || !CLIP_PATH.test(line.audioUrl) ||
                    !finite(line.duration) || line.duration <= 0 || line.duration > 300) return null;
                total += line.duration;
            }
        }
        if (!entry.ready) return entry.duration === null ? entry : null;
        return finite(entry.duration) && entry.duration > 0 && entry.duration <= 3600 &&
            Math.abs(total - entry.duration) <= 0.001 ? entry : null;
    }

    function formatTime(seconds) {
        const value = finite(seconds) ? Math.max(0, Math.floor(seconds + 0.000001)) : 0;
        return Math.floor(value / 60) + ':' + String(value % 60).padStart(2, '0');
    }

    function createPlayback(audio, entry, update, beforePlay, timers = { set: (fn, delay) => setTimeout(fn, delay), clear: id => clearTimeout(id) }) {
        let phase = 'ready', wantPlay = false, token = 0, canSeek = false, index = 0, revealed = -1;
        let timer = null, nextPending = false, rate = 1, destroyed = false, autoAdvance = true;
        let pendingSeek = null;
        let offset = 0;
        const gap = turnPause(entry);
        const starts = entry.lines.map(line => { const start = offset; offset += line.duration + gap; return start; });
        const total = entry.lines.reduce((sum, line) => sum + line.duration, 0) + gap * (entry.lines.length - 1);
        // The next sentence is fetched while this one plays, so the turn starts without waiting for the network
        // (the clips are cached for a year, so the player then reads it from the browser cache).
        const preloader = typeof Audio === 'function' ? new Audio() : null;
        let warmed = -1;
        function warmNext() {
            const next = index + 1;
            if (!preloader || next >= entry.lines.length || warmed === next) return;
            warmed = next;
            preloader.preload = 'auto';
            preloader.src = entry.lines[next].audioUrl;
            try { preloader.load(); } catch (_) { /* Best effort only. */ }
        }
        const handlers = [];
        function emit() {
            const local = Math.min(entry.lines[index].duration, Math.max(0, pendingSeek ?? audio.currentTime ?? 0));
            update({ phase, index, revealed, count: entry.lines.length, canSeek: true,
                current: phase === 'ended' ? total : starts[index] + local,
                duration: total });
        }
        function listen(name, handler) { audio.addEventListener(name, handler); handlers.push([name, handler]); }
        function clearGap() { if (timer !== null) timers.clear(timer); timer = null; }
        function loadClip(next) {
            clearGap(); token++; wantPlay = false; nextPending = false; canSeek = false; pendingSeek = null;
            audio.pause(); index = next; audio.src = entry.lines[index].audioUrl; audio.load();
            audio.playbackRate = rate;
        }
        function pause() {
            clearGap(); wantPlay = false; token++;
            if (!['ended', 'error', 'blocked'].includes(phase)) phase = revealed < 0 ? 'ready' : 'paused';
            audio.pause(); emit();
        }
        async function play(continuous = true) {
            if (destroyed) return;
            beforePlay();
            if (phase === 'ended') { revealed = -1; loadClip(0); }
            else if (nextPending) loadClip(index + 1);
            else if (phase === 'error') loadClip(index);
            const request = ++token;
            autoAdvance = continuous;
            wantPlay = true; phase = 'loading'; emit();
            try {
                await audio.play();
                if (request !== token || !wantPlay || destroyed) {
                    if (!wantPlay) audio.pause();
                    return;
                }
                phase = 'playing'; revealed = Math.max(revealed, index); emit();
            } catch (error) {
                if (request === token && !destroyed) {
                    wantPlay = false;
                    phase = error && error.name === 'NotAllowedError' ? 'blocked' : 'error'; emit();
                }
            }
        }
        function jump(next, continuous = true) {
            if (destroyed || next < 0 || next >= entry.lines.length) return;
            loadClip(next); phase = 'ready'; return play(continuous);
        }
        listen('playing', () => {
            if (!wantPlay || destroyed) { audio.pause(); return; }
            phase = 'playing'; revealed = Math.max(revealed, index); emit();
            warmNext();
        });
        listen('waiting', () => { if (wantPlay) { phase = 'loading'; emit(); } });
        listen('loadedmetadata', () => {
            canSeek = true; audio.playbackRate = rate;
            if (pendingSeek !== null) { audio.currentTime = pendingSeek; pendingSeek = null; }
            emit();
        });
        listen('timeupdate', emit);
        listen('ended', () => {
            if (!wantPlay || destroyed || nextPending) return;
            token++;
            if (!autoAdvance) {
                wantPlay = false; nextPending = index < entry.lines.length - 1; phase = 'paused'; emit(); return;
            }
            if (index === entry.lines.length - 1) { wantPlay = false; phase = 'ended'; emit(); return; }
            nextPending = true; phase = 'gap'; emit();
            timer = timers.set(() => {
                timer = null;
                if (wantPlay && !destroyed) { loadClip(index + 1); play(); }
            }, gap * 1000 / rate);
        });
        listen('error', () => { clearGap(); wantPlay = false; token++; phase = 'error'; emit(); });
        audio.preload = 'none';
        loadClip(0); emit();
        return {
            toggle: () => wantPlay ? pause() : play(),
            pause,
            replay: () => { revealed = -1; return jump(0); },
            previous: () => jump(index - 1), next: () => jump(index + 1),
            playLine: value => { if (Number.isInteger(value)) return jump(value, false); },
            retry: () => { loadClip(index); phase = 'ready'; return play(); },
            seek: value => {
                if (!finite(value) || destroyed) return;
                const target = Math.min(total, Math.max(0, value));
                let next = starts.findIndex((start, i) => target < start + entry.lines[i].duration);
                if (next < 0) next = entry.lines.length - 1;
                const local = Math.min(entry.lines[next].duration, Math.max(0, target - starts[next]));
                const continuing = wantPlay;
                const changed = next !== index;
                clearGap(); nextPending = false;
                if (changed) loadClip(next);
                pendingSeek = local;
                try { audio.currentTime = local; if (canSeek) pendingSeek = null; } catch (_) { /* Applied after this clip's metadata arrives. */ }
                if (target === total) { wantPlay = false; token++; audio.pause(); phase = 'ended'; }
                else if (changed || phase === 'ended' || phase === 'gap') phase = revealed < 0 ? 'ready' : 'paused';
                emit();
                if (continuing && target < total && (changed || audio.paused)) return play();
            },
            speed: value => { if ([0.75, 1, 1.25].includes(value)) { rate = value; audio.playbackRate = value; } },
            destroy: () => {
                pause(); destroyed = true;
                handlers.forEach(pair => audio.removeEventListener(pair[0], pair[1]));
                audio.removeAttribute('src'); audio.load();
                if (preloader) { preloader.removeAttribute('src'); try { preloader.load(); } catch (_) { /* Nothing to free. */ } }
            }
        };
    }

    function mount(document, fetchManifest) {
        const sections = Array.from(document.querySelectorAll('[data-sample-scenario][data-sample-code]'));
        if (!sections.length) return;
        const players = new Map();
        let manifestPromise;
        function manifest() {
            if (!manifestPromise) manifestPromise = fetchManifest(MANIFEST_URL, { credentials: 'same-origin', cache: 'no-cache' })
                .then(response => { if (!response.ok) throw new Error('manifest'); return response.json(); })
                .then(value => {
                    if (!value || value.version !== 2 || !value.samples || typeof value.samples !== 'object' || Array.isArray(value.samples)) throw new Error('manifest');
                    return value.samples;
                }).catch(error => { manifestPromise = null; throw error; });
            return manifestPromise;
        }
        sections.forEach(section => {
            const select = name => section.querySelector('[data-sample-' + name + ']');
            const audio = select('audio'), button = select('play'), replay = select('replay');
            const previous = select('previous'), next = select('next'), counter = select('counter'), chat = select('chat');
            const speed = select('speed'), seek = select('seek'), status = select('status'), retry = select('retry');
            const elapsed = select('elapsed'), total = select('duration'), label = select('play-label'), icon = select('play-icon');
            const rows = Array.from(section.querySelectorAll('[data-sample-role]'));
            if (![audio, button, replay, previous, next, counter, chat, speed, seek, status, retry, elapsed, total, label, icon].every(Boolean)) return;
            const transcript = rows.map(row => ({ speaker: row.dataset.sampleRole,
                text: row.querySelector('[data-sample-text]').getAttribute('data-sample-text') || row.querySelector('[data-sample-text]').textContent.trim(),
                translation: (row.querySelector('[data-sample-translation]') || {}).textContent?.trim() || '' }));
            let controller = null, initialized = false, loading = false, highlighted = -1, shown = -1;
            function showChat(index) {
                if (index === shown) return;
                const follow = section.dataset.sampleAutoscroll === 'always' || chat.scrollHeight - chat.scrollTop - chat.clientHeight < 80;
                if (index < shown) { chat.replaceChildren(); shown = -1; }
                while (shown < index) chat.append(rows[++shown]);
                if (follow) chat.scrollTop = chat.scrollHeight;
            }
            function highlight(index) {
                if (highlighted === index) return;
                if (rows[highlighted]) { rows[highlighted].classList.remove('is-current'); rows[highlighted].removeAttribute('aria-current'); }
                highlighted = index;
                if (rows[index]) { rows[index].classList.add('is-current'); rows[index].setAttribute('aria-current', 'true'); }
            }
            function update(state) {
                const playing = ['playing', 'loading', 'gap'].includes(state.phase);
                label.textContent = playing ? 'Tạm dừng' : state.phase === 'paused' ? 'Tiếp tục' : state.phase === 'ended' ? 'Nghe lại' : 'Bắt đầu nghe';
                icon.className = 'fa-solid ' + (playing ? 'fa-pause' : 'fa-play');
                button.setAttribute('aria-label', label.textContent);
                status.textContent = ({ ready: 'Bấm bắt đầu để nghe và xem hội thoại hiện từng câu.',
                    loading: 'Đang tải câu ' + (state.index + 1) + '…', playing: 'Đang nghe câu ' + (state.index + 1) + '.',
                    gap: 'Chuẩn bị câu tiếp theo…', paused: '',
                    ended: 'Đã nghe hết hội thoại. Bạn có thể nghe lại hoặc bắt đầu luyện tập.',
                    blocked: 'Trình duyệt chưa cho phép phát. Hãy bấm bắt đầu nghe.',
                    error: 'Không tải được âm thanh của câu này. Bấm thử lại để tiếp tục.' })[state.phase];
                status.hidden = !['error', 'blocked'].includes(state.phase);
                retry.hidden = state.phase !== 'error';
                counter.textContent = 'Câu ' + (state.revealed < 0 ? 0 : state.index + 1) + ' / ' + state.count;
                previous.disabled = state.index === 0; next.disabled = state.index === state.count - 1;
                seek.disabled = !state.canSeek;
                seek.max = String(state.duration); seek.value = String(state.current);
                seek.setAttribute('aria-valuetext', formatTime(state.current) + ' trên ' + formatTime(state.duration));
                elapsed.textContent = formatTime(state.current); total.textContent = formatTime(state.duration);
                showChat(state.revealed);
                highlight(state.phase === 'playing' ? state.index : -1);
            }
            async function initialize() {
                if (initialized || loading) return;
                loading = true; retry.hidden = true; status.hidden = false; status.textContent = 'Đang tải hội thoại mẫu…';
                try {
                    const entries = await manifest();
                    const key = section.dataset.sampleScenario + '/' + section.dataset.sampleCode;
                    const entry = validateEntry(Object.hasOwn(entries, key) ? entries[key] : null, transcript);
                    initialized = true;
                    if (!entry) { status.textContent = 'Bản nghe chưa sẵn sàng cho nội dung này. Bạn có thể đọc hội thoại bên dưới.'; return; }
                    entry.lines.forEach((line, i) => { const name = rows[i].querySelector('[data-sample-speaker]'); if (name) name.textContent = line.speakerName; });
                    if (!entry.ready) { status.textContent = 'Bản nghe đang được chuẩn bị. Bạn có thể đọc hội thoại bên dưới.'; return; }
                    let timestamp = 0;
                    entry.lines.forEach((line, i) => {
                        const time = rows[i].querySelector('[data-sample-message-time]');
                        if (time) time.textContent = formatTime(timestamp);
                        timestamp += line.duration + turnPause(entry);
                    });
                    chat.replaceChildren(); chat.classList.add('is-sample-chat');
                    chat.setAttribute('role', 'log'); chat.setAttribute('aria-live', 'polite');
                    chat.setAttribute('aria-relevant', 'additions'); chat.setAttribute('tabindex', '0');
                    controller = createPlayback(audio, entry, update, () => {
                        players.forEach((other, owner) => { if (owner !== section) other.pause(); });
                    });
                    players.set(section, controller);
                    button.disabled = false; replay.disabled = false; speed.disabled = false;
                    rows.forEach((row, index) => {
                        const playLine = row.querySelector('[data-sample-line-play]');
                        if (playLine) { playLine.disabled = false; playLine.addEventListener('click', () => controller.playLine(index)); }
                    });
                } catch (_) {
                    status.textContent = 'Chưa tải được bản nghe. Hãy thử lại hoặc đọc hội thoại bên dưới.';
                    retry.hidden = false;
                } finally { loading = false; }
            }
            button.addEventListener('click', () => controller?.toggle());
            replay.addEventListener('click', () => controller?.replay());
            previous.addEventListener('click', () => controller?.previous());
            next.addEventListener('click', () => controller?.next());
            speed.addEventListener('change', () => controller?.speed(Number(speed.value)));
            seek.addEventListener('input', () => controller?.seek(Number(seek.value)));
            retry.addEventListener('click', () => { if (controller) controller.retry(); else { initialized = false; manifestPromise = null; initialize(); } });
            if (section.tagName === 'DETAILS') {
                section.addEventListener('toggle', () => { if (section.open) initialize(); else controller?.pause(); });
                if (section.open) initialize();
            } else initialize();
        });
        document.defaultView?.addEventListener('pagehide', () => players.forEach(player => player.pause()));
    }

    return { validateEntry, formatTime, createPlayback, mount, TURN_PAUSE_SECONDS };
});
