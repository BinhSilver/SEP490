document.addEventListener('DOMContentLoaded', () => {
    const videoId = document.getElementById('video-id').value;
    const video = document.getElementById('editor-video');
    const listEl = document.getElementById('subtitle-editor-list');
    const saveIndicator = document.getElementById('save-indicator');
    const prepareLink = document.getElementById('prepare-exercises-link');
    const publishBtn = document.getElementById('publish-btn');
    const validateBtn = document.getElementById('validate-btn');

    const playBtn = document.getElementById('editor-play-btn');
    const playIcon = document.getElementById('editor-play-icon');
    const editorTitle = document.getElementById('editor-title');

    function headers(json) {
        if (typeof window.authHeaders === 'function') return window.authHeaders(json);
        const h = {};
        if (json !== false) h['Content-Type'] = 'application/json';
        const t = localStorage.getItem('accessToken');
        if (t) h['Authorization'] = 'Bearer ' + t;
        return h;
    }

    function streamUrl() {
        if (typeof window.mediaStreamUrl === 'function') return window.mediaStreamUrl(videoId);
        const t = localStorage.getItem('accessToken');
        return t
            ? `/api/videos/${videoId}/stream?access_token=${encodeURIComponent(t)}`
            : `/api/videos/${videoId}/stream`;
    }

    function formatTitleDisplay(title) {
        const name = title?.trim();
        return name ? `Tiêu đề: ${name}` : 'Tiêu đề: Chưa đặt tên';
    }

    function updateEditorTitle(title) {
        const display = formatTitleDisplay(title);
        editorTitle.textContent = display;
        const name = title?.trim();
        editorTitle.title = name
            ? `${name}\n(Nhấn để sửa tiêu đề)`
            : 'Nhấn để sửa tiêu đề';
    }
    const undoBtn = document.getElementById('undo-btn');
    const redoBtn = document.getElementById('redo-btn');
    const focusModeBtn = document.getElementById('focus-mode-btn');
    const regenerateAllBtn = document.getElementById('regenerate-all-btn');
    const metadataPanel = document.getElementById('metadata-panel');
    const metaTitle = document.getElementById('meta-title');
    const metaCategory = document.getElementById('meta-category');
    const saveMetaBtn = document.getElementById('save-meta-btn');
    const metaGenres = document.getElementById('meta-genres');
    window.ShadowingGenres.bind(metaGenres);
    const waveformCanvas = document.getElementById('waveform-canvas');
    const editorLayout = document.getElementById('editor-layout');

    let subtitles = [];
    let qualityReviews = {};
    let videoData = null;
    let saveTimer = null;
    let focusedId = null;
    let focusMode = false;
    let undoStack = [];
    let redoStack = [];
    const MAX_UNDO = 10;
    const operations = window.ShadowingEditorOperations;
    const aiDialog = document.getElementById('editor-ai-dialog');
    const aiPrimary = document.getElementById('editor-ai-primary');
    const aiProgress = document.getElementById('editor-ai-progress');
    let regeneratingId = null;
    let retrySubtitleId = null;
    const saves = operations.saves(async (id, body) => {
        await operations.response(await fetch(`/api/subtitles/${id}`, {
            method: 'PUT', headers: headers(), body: JSON.stringify(body)
        }));
    });
    const playback = operations.playback(video, syncPlayback, message => {
        showAlert({ title: 'Không phát được đoạn', message, variant: 'error' });
    });

    function syncPlayback() {
        playIcon.className = video.paused ? 'fa-solid fa-play' : 'fa-solid fa-pause';
        playBtn.setAttribute('aria-label', video.paused ? 'Phát video' : 'Dừng video');
        listEl.querySelectorAll('.editor-sub-item').forEach(item => {
            const btn = item.querySelector('.play-segment');
            const active = playback.playing(Number(item.dataset.id));
            btn.title = active ? 'Dừng đoạn này' : 'Phát đoạn này';
            btn.setAttribute('aria-label', btn.title);
            btn.setAttribute('aria-pressed', String(active));
            btn.querySelector('i').className = active ? 'fa-solid fa-stop' : 'fa-solid fa-play';
        });
    }

    function openAiDialog() {
        if (!aiDialog.open) aiDialog.showModal();
        document.documentElement.classList.add('editor-ai-open');
        aiProgress.hidden = true;
    }
    function aiState(state, message) {
        aiDialog.dataset.state = state;
        document.getElementById('editor-ai-status').textContent = state === 'success'
            ? 'Đã tạo lại AI' : state === 'error' ? 'Chưa tạo lại được' : 'Đang tạo cách đọc và bản dịch…';
        document.getElementById('editor-ai-detail').textContent = message;
        aiPrimary.disabled = state === 'loading';
        aiPrimary.textContent = state === 'success' ? 'Xong' : state === 'error' ? 'Thử lại' : 'Đang xử lý…';
        aiProgress.textContent = state === 'loading' ? 'Xem tiến trình AI' : 'Xem kết quả AI';
    }
    aiDialog.querySelectorAll('[data-ai-close]').forEach(button => button.addEventListener('click', () => aiDialog.close()));
    aiDialog.addEventListener('close', () => {
        document.documentElement.classList.remove('editor-ai-open');
        aiProgress.hidden = false;
        aiProgress.focus();
    });
    aiProgress.addEventListener('click', openAiDialog);
    aiPrimary.addEventListener('click', () => {
        if (aiDialog.dataset.state === 'error') regenerateSubtitle(retrySubtitleId);
        else aiDialog.close();
    });

    function syncAiControls() {
        const busy = regeneratingId !== null;
        undoBtn.disabled = busy || undoStack.length === 0;
        redoBtn.disabled = busy || redoStack.length === 0;
        regenerateAllBtn.disabled = busy;
        if (addBtn) addBtn.disabled = busy;
        publishBtn.disabled = busy;
        listEl.querySelectorAll('.editor-sub-item').forEach(item => {
            const current = Number(item.dataset.id) === regeneratingId;
            item.draggable = !busy && !focusMode;
            item.setAttribute('aria-busy', String(current));
            item.querySelectorAll('textarea, input').forEach(input => { input.disabled = current; });
            item.querySelector('.regenerate-btn').disabled = busy;
            item.querySelector('.delete-btn').disabled = busy;
            item.querySelectorAll('.quality-use-source, .quality-use-audio, .quality-approve').forEach(button => { button.disabled = busy; });
        });
    }

    async function flushSaves() {
        clearTimeout(saveTimer);
        if (!saves.dirty) return;
        setSaveStatus('saving');
        try { await saves.flush(); setSaveStatus('saved'); }
        catch (error) { setSaveStatus('error'); throw error; }
    }

    async function regenerateSubtitle(id) {
        if (regeneratingId !== null) return;
        const sub = subtitles.find(s => s.id === id);
        if (!sub) { aiState('error', 'Câu phụ đề này không còn tồn tại.'); return; }
        retrySubtitleId = id;
        regeneratingId = id;
        document.getElementById('editor-ai-title').textContent = 'Tạo lại AI · Câu #' + (subtitles.indexOf(sub) + 1);
        document.getElementById('editor-ai-source').textContent = sub.text || '';
        aiState('loading', 'Đang lưu thay đổi trước khi gửi câu phụ đề cho AI.');
        openAiDialog();
        syncAiControls();
        try {
            await flushSaves();
            pushUndo();
            syncAiControls();
            aiState('loading', 'AI đang xử lý câu phụ đề bạn đã chọn. Bạn có thể xem trang trong khi chờ.');
            const updated = await operations.response(await fetch(`/api/subtitles/${id}/regenerate`, {
                method: 'POST', headers: headers(), body: JSON.stringify({ text: sub.text })
            }));
            if (updated.id !== id || typeof updated.furigana !== 'string' || !updated.translation?.trim()) {
                throw new Error('AI chưa trả về cách đọc và bản dịch hợp lệ. Hãy thử lại.');
            }
            Object.assign(sub, updated);
            renderList();
            setSaveStatus(saves.dirty ? 'unsaved' : 'saved');
            aiState('success', 'Cách đọc và bản dịch đã được cập nhật và lưu vào câu phụ đề.');
        } catch (error) {
            aiState('error', error.message || 'Không kết nối được tới AI. Hãy thử lại.');
        } finally {
            regeneratingId = null;
            syncAiControls();
        }
    }

    prepareLink?.addEventListener('click', async (event) => {
        if (saveIndicator.classList.contains('synced') || saveIndicator.classList.contains('saved')) return;
        event.preventDefault();
        const message = saveIndicator.classList.contains('error')
            ? 'Phụ đề chưa lưu được. Hãy kiểm tra kết nối và lưu lại trước khi chuyển trang.'
            : 'Phụ đề đang được lưu. Hãy đợi thông báo “Đã lưu” rồi mở trang chuẩn bị bài tập.';
        if (typeof showAlert === 'function') {
            await showAlert({ title: 'Phụ đề chưa sẵn sàng', message, variant: 'info' });
        } else {
            alert(message);
        }
    });

    async function init() {
        const [vRes, sRes, qRes] = await Promise.all([
            fetch(`/api/videos/${videoId}`, { headers: headers() }),
            fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() }),
            fetch(`/api/videos/${videoId}/subtitle-quality`, { headers: headers() })
        ]);
        if (!vRes.ok) {
            const err = await vRes.json().catch(() => ({}));
            const msg = err.message || err.error || 'Không tải được video (có thể do chưa đăng nhập hoặc không phải chủ sở hữu).';
            if (typeof showAlert === 'function') {
                await showAlert({ title: 'Không mở được bài', message: msg, variant: 'error' });
            } else {
                alert(msg);
            }
            if (vRes.status === 401) {
                window.location.href = '/login?redirect=' + encodeURIComponent(location.pathname);
            } else if (vRes.status === 403) {
                window.location.href = '/shadowing';
            }
            return;
        }
        // This owner-only endpoint also protects already-open editor tabs after access changes.
        // Never render editing controls when ownership could not be verified.
        if (!qRes.ok) {
            const err = await qRes.json().catch(() => ({}));
            const msg = err.message || 'Không xác nhận được quyền chỉnh sửa phụ đề. Hãy tải lại trang.';
            if (typeof showAlert === 'function') {
                await showAlert({ title: 'Không thể chỉnh sửa phụ đề', message: msg, variant: 'error' });
            } else {
                alert(msg);
            }
            if (qRes.status === 401) {
                window.location.href = '/login?redirect=' + encodeURIComponent(location.pathname);
            } else if (qRes.status === 403) {
                window.location.href = `/shadowing/player/${videoId}?mode=shadowing`;
            }
            return;
        }
        videoData = await vRes.json();
        subtitles = sRes.ok ? await sRes.json() : [];
        qualityReviews = qRes.ok ? await qRes.json() : {};
        updateEditorTitle(videoData.title);
        metaTitle.value = videoData.title || '';
        const cat = videoData.category
            || (videoData.tags || []).map(t => t.name || t).find(t =>
                ['Podcast','Anime','Drama','News','Speech','Daily','Story','JLPT'].includes(t))
            || 'Daily';
        if (metaCategory) metaCategory.value = cat;
        window.ShadowingGenres.write(metaGenres, (videoData.tags || [])
            .map(tag => tag && tag.code || '').filter(code => code.startsWith('VIDEO_GENRE_'))
            .map(code => code.slice('VIDEO_GENRE_'.length)));
        if (videoData.status === 'READY') {
            publishBtn.innerHTML = '<i class="fa-solid fa-floppy-disk"></i> Lưu thay đổi';
        }

        const path = (videoData.videoPath || '').toLowerCase().replace(/\\/g, '/');
        const audioOnly = videoData.audioOnly === true
            || /\.(mp3|wav|m4a|ogg|flac|aac|opus)(\?|$)/.test(path);
        if (audioOnly) {
            editorLayout?.classList.add('audio-mode');
            document.body.classList.add('audio-mode');
        }

        video.addEventListener('loadedmetadata', drawTimeline);
        await window.ShadowingYoutube.load(video, videoId, { meta: videoData, streamUrl: streamUrl() });
        renderList();
        pollAiPending();
        setSaveStatus('synced');
    }

    async function pollAiPending() {
        const hasPending = subtitles.some(s => s.aiStatus === 'PENDING');
        if (!hasPending) return;
        await new Promise(r => setTimeout(r, 2500));
        if (regeneratingId !== null || saves.dirty) return pollAiPending();
        const res = await fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() });
        if (!res.ok) return pollAiPending();
        const fresh = await res.json();
        if (regeneratingId !== null || saves.dirty) return pollAiPending();
        const stillPending = fresh.some(s => s.aiStatus === 'PENDING');
        subtitles = fresh;
        renderList();
        if (stillPending) pollAiPending();
    }

    function snapshot() {
        return JSON.parse(JSON.stringify(subtitles));
    }

    function pushUndo() {
        undoStack.push(snapshot());
        if (undoStack.length > MAX_UNDO) undoStack.shift();
        redoStack = [];
        undoBtn.disabled = undoStack.length === 0;
        redoBtn.disabled = true;
    }

    async function applySnapshot(targetSubs) {
        await flushSaves();
        setSaveStatus('saving');

        const res = await fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() });
        const dbSubs = await res.json();
        const targetIds = new Set(targetSubs.map(s => s.id));
        const dbIds = new Set(dbSubs.map(s => s.id));

        for (const sub of dbSubs) {
            if (!targetIds.has(sub.id)) {
                await fetch(`/api/subtitles/${sub.id}`, { method: 'DELETE', headers: headers() });
            }
        }

        for (let i = 0; i < targetSubs.length; i++) {
            const sub = targetSubs[i];
            const body = {
                text: sub.text || '',
                furigana: sub.furigana || '',
                translation: sub.translation || '',
                startTime: sub.startTime,
                endTime: sub.endTime
            };
            if (dbIds.has(sub.id)) {
                await fetch(`/api/subtitles/${sub.id}`, {
                    method: 'PUT',
                    headers: headers(),
                    body: JSON.stringify(body)
                });
            } else {
                await fetch(`/api/videos/${videoId}/subtitles`, {
                    method: 'POST',
                    headers: headers(),
                    body: JSON.stringify({ ...body, sortOrder: i })
                });
            }
        }

        await reloadSubs();
        setSaveStatus('saved');
    }

    async function runHistoryAction(stack, otherStack, popFrom) {
        if (!stack.length) return;
        undoBtn.disabled = true;
        redoBtn.disabled = true;
        otherStack.push(snapshot());
        const target = popFrom.call(stack);
        await applySnapshot(target);
        undoBtn.disabled = undoStack.length === 0;
        redoBtn.disabled = redoStack.length === 0;
    }

    undoBtn.addEventListener('click', () => runHistoryAction(undoStack, redoStack, undoStack.pop));
    redoBtn.addEventListener('click', () => runHistoryAction(redoStack, undoStack, redoStack.pop));

    editorTitle.addEventListener('click', () => {
        metadataPanel.style.display = metadataPanel.style.display === 'none' ? 'block' : 'none';
    });

    saveMetaBtn.addEventListener('click', async () => {
        const res = await fetch(`/api/videos/${videoId}`, {
            method: 'PUT',
            headers: headers(),
            body: JSON.stringify({
                title: metaTitle.value,
                category: metaCategory?.value || 'Daily',
                genres: window.ShadowingGenres.read(metaGenres)
            })
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            await showAlert({ title: 'Chưa lưu được thông tin', message: err.message || 'Hãy kiểm tra lại rồi thử lại.', variant: 'error' });
            return;
        }
        updateEditorTitle(metaTitle.value);
        metadataPanel.style.display = 'none';
        setSaveStatus('saved');
    });

    focusModeBtn.addEventListener('click', () => {
        focusMode = !focusMode;
        focusModeBtn.classList.toggle('active', focusMode);
        editorLayout.classList.toggle('focus-mode', focusMode);
        if (focusMode && !focusedId && subtitles.length) focusedId = subtitles[0].id;
        renderList();
    });

    regenerateAllBtn.addEventListener('click', async () => {
        const ok = await showConfirm({
            title: 'Tạo lại phụ đề',
            message: 'Các câu hiện tại sẽ bị thay thế.',
            confirmText: 'Tạo lại',
            cancelText: 'Hủy',
            variant: 'danger'
        });
        if (!ok) return;
        await flushSaves();
        pushUndo();
        await fetch(`/api/videos/${videoId}/regenerate-subtitles`, { method: 'POST', headers: headers() });
        regenerateAllBtn.disabled = true;
        regenerateAllBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang xử lý...';
        pollRegenerate();
    });

    async function pollRegenerate() {
        const res = await fetch(`/api/videos/${videoId}/status`, { headers: headers() });
        const data = await res.json();
        if (data.status === 'PROCESSING') {
            setTimeout(pollRegenerate, 2000);
            return;
        }
        regenerateAllBtn.disabled = false;
        regenerateAllBtn.innerHTML = '<i class="fa-solid fa-arrows-rotate"></i> Tạo lại phụ đề';
        await reloadSubs();
        pollAiPending();
    }

    // Zoomable view of the timeline (CapCut-style): zoomLevel = whole duration / visible seconds.
    const zoomSlider = document.getElementById('timeline-zoom');
    const zoomLabel = document.getElementById('timeline-zoom-label');
    const timelineScroll = document.getElementById('timeline-scroll');
    const MIN_VISIBLE_SECONDS = 4;
    const ZOOM_STEP = 1.6;
    const RULER_STEPS = [0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1200];
    let zoomLevel = 1;
    let viewStart = 0;
    let lastRevealedId = null;
    let draftRange = null; // the line being added, drawn on the timeline

    function maxZoom() { return Math.max(1, (video.duration || 0) / MIN_VISIBLE_SECONDS); }
    function visibleSpan() { return video.duration / zoomLevel; }
    function clampView() {
        viewStart = Math.min(Math.max(0, viewStart), Math.max(0, video.duration - visibleSpan()));
    }
    function timeAt(clientX) {
        const rect = waveformCanvas.getBoundingClientRect();
        return viewStart + Math.min(1, Math.max(0, (clientX - rect.left) / rect.width)) * visibleSpan();
    }
    function formatClock(seconds) {
        const s = Math.max(0, seconds);
        const m = Math.floor(s / 60);
        const rest = s - m * 60;
        return m + ':' + (rest < 10 ? '0' : '') + (Number.isInteger(rest) ? rest : rest.toFixed(1));
    }

    function formatSpan(seconds) {
        const s = Math.round(seconds);
        return s < 60 ? s + ' giây' : Math.floor(s / 60) + ' phút' + (s % 60 ? ' ' + (s % 60) + ' giây' : '');
    }

    function syncZoomControls() {
        if (!video.duration) return;
        const zoomed = zoomLevel > 1.01;
        if (zoomSlider) zoomSlider.value = maxZoom() > 1 ? Math.round(Math.log(zoomLevel) / Math.log(maxZoom()) * 100) : 0;
        if (zoomLabel) zoomLabel.textContent = zoomed ? 'Khung ' + formatSpan(visibleSpan()) : 'Toàn bộ';
        if (timelineScroll) {
            timelineScroll.hidden = !zoomed;
            const room = video.duration - visibleSpan();
            timelineScroll.value = room > 0 ? Math.round(viewStart / room * 1000) : 0;
        }
    }

    /** Zooms keeping {@code anchor} (seconds) at the same spot on screen; defaults to the playhead. */
    function setZoom(level, anchor) {
        if (!video.duration) return;
        const at = anchor ?? video.currentTime;
        const ratio = Math.min(1, Math.max(0, (at - viewStart) / visibleSpan()));
        zoomLevel = Math.min(maxZoom(), Math.max(1, level));
        viewStart = at - ratio * visibleSpan();
        clampView();
        syncZoomControls();
        drawTimeline();
    }

    function panBy(seconds) {
        viewStart += seconds;
        clampView();
        syncZoomControls();
        drawTimeline();
    }

    /** Keeps what the learner works on in view: the playing position, and a newly selected subtitle. */
    function followView() {
        if (zoomLevel <= 1.01) { lastRevealedId = focusedId; return; }
        const span = visibleSpan();
        if (!video.paused && (video.currentTime < viewStart || video.currentTime > viewStart + span)) {
            viewStart = video.currentTime - span * 0.1;
        }
        if (focusedId !== lastRevealedId) {
            lastRevealedId = focusedId;
            const sub = subtitles.find(s => s.id === focusedId);
            if (sub && (sub.endTime < viewStart || sub.startTime > viewStart + span)) {
                viewStart = (sub.startTime + sub.endTime) / 2 - span / 2;
            }
        }
        clampView();
        syncZoomControls();
    }

    function drawTimeline() {
        if (!waveformCanvas || !subtitles.length || !video.duration) return;
        followView();
        const ctx = waveformCanvas.getContext('2d');
        const w = waveformCanvas.width = waveformCanvas.offsetWidth * 2;
        const h = waveformCanvas.height = 112;
        const span = visibleSpan();
        const viewEnd = viewStart + span;
        const xOf = t => (t - viewStart) / span * w;
        const timelineStyle = getComputedStyle(waveformCanvas);
        const timelineColor = (name, fallback) => timelineStyle.getPropertyValue(name).trim() || fallback;
        const textColor = timelineColor('--timeline-text', '#1d1d1f');
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = timelineColor('--timeline-bg', '#f0f0f0');
        ctx.fillRect(0, 32, w, 20);

        // Numbers are skipped where they would overlap; the selected one is always drawn on top.
        ctx.font = '20px Inter';
        let labelEnd = -Infinity;
        let focusedLabel = null;
        subtitles.forEach((sub, i) => {
            if (sub.endTime < viewStart || sub.startTime > viewEnd) return;
            const x1 = xOf(sub.startTime);
            const x2 = xOf(sub.endTime);
            const focused = sub.id === focusedId;
            ctx.fillStyle = focused
                ? timelineColor('--timeline-active', '#0066cc')
                : timelineColor('--timeline-segment', '#99c2eb');
            ctx.fillRect(x1, 22, Math.max(x2 - x1, 2), 40);
            const label = String(i + 1);
            const labelX = Math.max(x1 + 4, 0);
            if (focused) { focusedLabel = { label, x: labelX }; return; }
            if (labelX < labelEnd + 6) return;
            ctx.fillStyle = textColor;
            ctx.fillText(label, labelX, 18);
            labelEnd = labelX + ctx.measureText(label).width;
        });
        if (focusedLabel) {
            ctx.font = 'bold 20px Inter';
            const width = ctx.measureText(focusedLabel.label).width;
            ctx.clearRect(focusedLabel.x - 3, 0, width + 6, 21);
            ctx.fillStyle = timelineColor('--timeline-active', '#0066cc');
            ctx.fillText(focusedLabel.label, focusedLabel.x, 18);
        }

        // The line being added: green when it can be saved, red when it overlaps or is invalid.
        if (draftRange && draftRange.end >= viewStart && draftRange.start <= viewEnd) {
            const x1 = xOf(draftRange.start);
            const width = Math.max(xOf(draftRange.end) - x1, 2);
            ctx.fillStyle = draftRange.ok ? 'rgba(20, 150, 90, 0.28)' : 'rgba(200, 40, 70, 0.28)';
            ctx.fillRect(x1, 22, width, 40);
            ctx.strokeStyle = draftRange.ok ? '#16935a' : '#c8284a';
            ctx.lineWidth = 3;
            ctx.setLineDash([8, 6]);
            ctx.strokeRect(x1, 22, width, 40);
            ctx.setLineDash([]);
        }

        // Time ruler: about one tick per 70 CSS pixels.
        const step = RULER_STEPS.find(s => span / s <= w / 140) || RULER_STEPS[RULER_STEPS.length - 1];
        ctx.strokeStyle = textColor;
        ctx.fillStyle = textColor;
        ctx.globalAlpha = 0.55;
        ctx.lineWidth = 1;
        ctx.font = '17px Inter';
        ctx.beginPath();
        ctx.moveTo(0, 72);
        ctx.lineTo(w, 72);
        for (let t = Math.ceil(viewStart / step) * step; t <= viewEnd; t += step) {
            const x = xOf(t);
            ctx.moveTo(x, 72);
            ctx.lineTo(x, 82);
            const text = formatClock(t);
            const textX = Math.min(Math.max(0, x - ctx.measureText(text).width / 2), w - ctx.measureText(text).width);
            ctx.fillText(text, textX, 104);
        }
        ctx.stroke();
        ctx.globalAlpha = 1;

        if (video.currentTime >= viewStart && video.currentTime <= viewEnd) {
            const curX = xOf(video.currentTime);
            ctx.strokeStyle = timelineColor('--timeline-playhead', '#ff3b30');
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(curX, 0);
            ctx.lineTo(curX, h);
            ctx.stroke();
        }
    }

    video.addEventListener('timeupdate', drawTimeline);
    video.addEventListener('loadedmetadata', () => { clampView(); syncZoomControls(); drawTimeline(); });
    window.addEventListener('resize', drawTimeline);
    // Canvas pixels must be repainted when the theme changes, including while paused.
    new MutationObserver(drawTimeline).observe(document.documentElement, {
        attributes: true, attributeFilter: ['data-theme']
    });

    zoomSlider?.addEventListener('input', () => setZoom(Math.pow(maxZoom(), Number(zoomSlider.value) / 100)));
    document.getElementById('timeline-zoom-in')?.addEventListener('click', () => setZoom(zoomLevel * ZOOM_STEP));
    document.getElementById('timeline-zoom-out')?.addEventListener('click', () => setZoom(zoomLevel / ZOOM_STEP));
    document.getElementById('timeline-zoom-fit')?.addEventListener('click', () => setZoom(1));
    timelineScroll?.addEventListener('input', () => {
        viewStart = Number(timelineScroll.value) / 1000 * Math.max(0, video.duration - visibleSpan());
        clampView();
        drawTimeline();
    });

    // Ctrl/⌘ + wheel zooms around the pointer; once zoomed, the wheel pans like a horizontal scroll.
    waveformCanvas?.addEventListener('wheel', e => {
        if (!video.duration) return;
        if (e.ctrlKey || e.metaKey) {
            e.preventDefault();
            setZoom(zoomLevel * Math.exp(-e.deltaY * 0.0015), timeAt(e.clientX));
            return;
        }
        if (zoomLevel <= 1.01) return;
        e.preventDefault();
        const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
        panBy(delta / waveformCanvas.getBoundingClientRect().width * visibleSpan());
    }, { passive: false });

    // Drag pans the zoomed view; a click without dragging seeks there as before.
    let timelineDrag = null;
    waveformCanvas?.addEventListener('pointerdown', e => {
        if (e.button !== 0) return;
        timelineDrag = { x: e.clientX, start: viewStart, moved: false };
        waveformCanvas.setPointerCapture(e.pointerId);
    });
    waveformCanvas?.addEventListener('pointermove', e => {
        if (!timelineDrag) return;
        const dx = e.clientX - timelineDrag.x;
        if (Math.abs(dx) > 4) timelineDrag.moved = true;
        if (!timelineDrag.moved || zoomLevel <= 1.01) return;
        waveformCanvas.classList.add('is-panning');
        viewStart = timelineDrag.start - dx / waveformCanvas.getBoundingClientRect().width * visibleSpan();
        clampView();
        syncZoomControls();
        drawTimeline();
    });
    const endTimelineDrag = e => {
        if (!timelineDrag) return;
        const seek = !timelineDrag.moved && e.type === 'pointerup';
        timelineDrag = null;
        waveformCanvas.classList.remove('is-panning');
        if (!seek) return;
        video.currentTime = timeAt(e.clientX);
        const sub = subtitles.find(s => video.currentTime >= s.startTime && video.currentTime <= s.endTime);
        if (sub) { focusedId = sub.id; lastRevealedId = sub.id; renderList(); }
        drawTimeline();
    };
    waveformCanvas?.addEventListener('pointerup', endTimelineDrag);
    waveformCanvas?.addEventListener('pointercancel', endTimelineDrag);

    // ===== Add a subtitle: timing first, checked like publishing, so the new line never blocks it =====
    const addBtn = document.getElementById('add-subtitle-btn');
    const addDialog = document.getElementById('editor-add-dialog');
    const addForm = document.getElementById('editor-add-form');
    const addStart = document.getElementById('add-start');
    const addEnd = document.getElementById('add-end');
    const addText = document.getElementById('add-text');
    const addFurigana = document.getElementById('add-furigana');
    const addTranslation = document.getElementById('add-translation');
    const addAi = document.getElementById('add-ai');
    const addSubmit = document.getElementById('add-submit');
    const addFit = document.getElementById('add-fit');
    const addStatus = document.getElementById('add-time-status');
    const addRangeLabel = document.getElementById('add-range-label');
    const newRange = operations.newRange;
    const PREVIEW_ID = -1;
    let addFitRange = null;
    let addAiTouched = false;

    function mediaDuration() {
        return Number.isFinite(video.duration) && video.duration > 0 ? video.duration : Number(videoData?.duration) || 0;
    }
    function readAddRange() {
        return { start: parseFloat(addStart.value), end: parseFloat(addEnd.value) };
    }
    function revealRange(range) {
        if (zoomLevel <= 1.01 || !video.duration) return;
        if (range.start < viewStart || range.end > viewStart + visibleSpan()) {
            viewStart = (range.start + range.end) / 2 - visibleSpan() / 2;
            clampView();
            syncZoomControls();
        }
    }

    function updateAddRange() {
        const { start, end } = readAddRange();
        const result = newRange.check(subtitles, mediaDuration(), start, end);
        draftRange = Number.isFinite(start) && Number.isFinite(end) && end > start ? { start, end, ok: result.ok } : null;
        addFitRange = result.fit;
        addFit.hidden = !result.fit;
        addStatus.innerHTML = '';
        addStatus.classList.toggle('is-ok', result.ok);
        (result.ok ? ['Thời gian hợp lệ, không chồng lên câu nào.'] : result.errors).forEach(message => {
            const line = document.createElement('li');
            line.textContent = message;
            addStatus.appendChild(line);
        });
        addRangeLabel.textContent = draftRange
            ? newRange.clock(start) + ' → ' + newRange.clock(end) + ' · dài ' + (end - start).toFixed(1).replace('.', ',') + ' giây'
            : '';
        [addStart, addEnd].forEach(input => input.setAttribute('aria-invalid', String(!result.ok)));
        addSubmit.disabled = !result.ok || !addText.value.trim();
        if (draftRange) revealRange(draftRange);
        drawTimeline();
        return result;
    }

    function openAddDialog(at) {
        if (regeneratingId !== null || !subtitles) return;
        const range = newRange.suggest(subtitles, mediaDuration(), at ?? video.currentTime);
        if (!range) {
            showAlert({ title: 'Không còn khoảng trống', variant: 'info',
                message: 'Dòng thời gian đã kín. Hãy rút ngắn một câu hiện có rồi thêm câu mới.' });
            return;
        }
        addStart.value = range.start;
        addEnd.value = range.end;
        addText.value = addFurigana.value = addTranslation.value = '';
        addAi.checked = true;
        addAiTouched = false;
        addSubmit.textContent = 'Thêm câu';
        addDialog.showModal();
        document.documentElement.classList.add('editor-ai-open');
        updateAddRange();
        addText.focus();
    }

    addBtn?.addEventListener('click', () => openAddDialog());
    // Double-click on an empty part of the timeline adds a line right there.
    waveformCanvas?.addEventListener('dblclick', e => {
        const at = timeAt(e.clientX);
        if (subtitles.some(s => at >= s.startTime && at < s.endTime)) return;
        openAddDialog(at);
    });
    [addStart, addEnd].forEach(input => input.addEventListener('input', updateAddRange));
    addText.addEventListener('input', () => { addSubmit.disabled = !draftRange?.ok || !addText.value.trim(); });
    [addFurigana, addTranslation].forEach(input => input.addEventListener('input', () => {
        // Both filled by hand: AI is no longer needed unless the learner asks for it.
        if (!addAiTouched) addAi.checked = !(addFurigana.value.trim() && addTranslation.value.trim());
    }));
    addAi.addEventListener('change', () => { addAiTouched = true; });
    addDialog.querySelectorAll('[data-take]').forEach(button => button.addEventListener('click', () => {
        const target = button.dataset.take === 'start' ? addStart : addEnd;
        target.value = Math.round(video.currentTime * 100) / 100;
        updateAddRange();
    }));
    document.getElementById('add-preview').addEventListener('click', () => {
        const { start, end } = readAddRange();
        playback.toggle(PREVIEW_ID, start, end);
    });
    addFit.addEventListener('click', () => {
        if (!addFitRange) return;
        addStart.value = addFitRange.start;
        addEnd.value = addFitRange.end;
        updateAddRange();
    });
    addDialog.querySelectorAll('[data-add-close]').forEach(button => button.addEventListener('click', () => addDialog.close()));
    addDialog.addEventListener('close', () => {
        if (playback.playing(PREVIEW_ID)) playback.stop();
        draftRange = null;
        document.documentElement.classList.remove('editor-ai-open');
        drawTimeline();
        addBtn?.focus();
    });

    addForm.addEventListener('submit', async e => {
        e.preventDefault();
        const text = addText.value.trim();
        if (!updateAddRange().ok || !text) return;
        const { start, end } = readAddRange();
        addSubmit.disabled = true;
        addSubmit.textContent = 'Đang thêm…';
        try {
            await flushSaves();
            pushUndo();
            // Append with a free order first (sort order is unique), then move it to its place in time.
            const nextOrder = subtitles.reduce((max, s) => Math.max(max, Number(s.sortOrder) || 0), -1) + 1;
            const created = await operations.response(await fetch(`/api/videos/${videoId}/subtitles`, {
                method: 'POST', headers: headers(), body: JSON.stringify({
                    text, furigana: addFurigana.value.trim() || null, translation: addTranslation.value.trim() || null,
                    startTime: start, endTime: end, sortOrder: nextOrder, manual: true
                })
            }));
            const order = subtitles.map(s => s.id);
            order.splice(newRange.insertIndex(subtitles, start), 0, created.id);
            subtitles = await operations.response(await fetch(`/api/videos/${videoId}/subtitles/reorder`, {
                method: 'PUT', headers: headers(), body: JSON.stringify({ order })
            }));
            const runAi = addAi.checked;
            addDialog.close();
            focusedId = created.id;
            renderList();
            drawTimeline();
            listEl.querySelector(`.editor-sub-item[data-id="${created.id}"]`)?.scrollIntoView({ block: 'center' });
            setSaveStatus('saved');
            notify('Đã thêm câu #' + (subtitles.findIndex(s => s.id === created.id) + 1), 'success');
            if (runAi) regenerateSubtitle(created.id);
        } catch (error) {
            addStatus.classList.remove('is-ok');
            addStatus.innerHTML = '';
            const line = document.createElement('li');
            line.textContent = error.message || 'Không thêm được câu. Hãy thử lại.';
            addStatus.appendChild(line);
            addSubmit.disabled = false;
        } finally {
            addSubmit.textContent = 'Thêm câu';
        }
    });

    function renderList() {
        listEl.innerHTML = '';
        const visible = focusMode
            ? subtitles.filter(s => s.id === focusedId)
            : subtitles;

        visible.forEach((sub) => {
            const index = subtitles.indexOf(sub);
            const item = document.createElement('div');
            item.className = 'editor-sub-item' + (sub.id === focusedId ? ' focused' : '');
            item.dataset.id = sub.id;
            item.draggable = !focusMode;

            const aiBadge = sub.aiStatus === 'PENDING' ? '<span class="badge badge-processing"><i class="fa-solid fa-spinner fa-spin"></i> AI đang xử lý</span>'
                : sub.aiStatus === 'FAILED' ? '<span class="badge badge-failed"><i class="fa-solid fa-circle-xmark"></i> AI lỗi</span>' : '';

            const qualityReview = qualityReviews[sub.id];

            item.innerHTML = `
                <div class="editor-sub-header">
                    <span class="caption drag-handle" title="Kéo để sắp xếp"><i class="fa-solid fa-grip-vertical"></i> #${index + 1} ${aiBadge}</span>
                    <div class="editor-time-inputs">
                        <input type="number" step="0.1" class="time-start" value="${sub.startTime}">
                        <input type="number" step="0.1" class="time-end" value="${sub.endTime}">
                        <button class="control-btn play-segment" title="Phát đoạn này" aria-label="Phát đoạn này" aria-pressed="false">
                            <i class="fa-solid fa-play" style="font-size:12px;"></i>
                        </button>
                        <button class="control-btn delete-btn" title="Xóa" style="width:28px;height:28px;"><i class="fa-solid fa-trash" style="font-size:12px;"></i></button>
                    </div>
                </div>
                <div class="editor-field"><label><i class="fa-solid fa-language"></i> Tiếng Nhật</label><textarea class="field-text" rows="2">${esc(sub.text || '')}</textarea></div>
                <div class="editor-field"><label><i class="fa-solid fa-book-open"></i> Cách đọc</label><textarea class="field-furigana" rows="2">${esc(sub.furigana || '')}</textarea></div>
                <div class="editor-field"><label><i class="fa-solid fa-font"></i> Bản dịch</label><textarea class="field-translation" rows="2">${esc(sub.translation || '')}</textarea></div>
                ${qualityReviewPanel(qualityReview)}
                <div class="editor-actions">
                    <button class="button-secondary regenerate-btn btn-sm"><i class="fa-solid fa-arrows-rotate"></i> Tạo lại AI</button>
                </div>
            `;

            if (!focusMode) {
                item.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', sub.id); item.classList.add('dragging'); });
                item.addEventListener('dragend', () => item.classList.remove('dragging'));
                item.addEventListener('dragover', e => { e.preventDefault(); item.classList.add('drag-over'); });
                item.addEventListener('dragleave', () => item.classList.remove('drag-over'));
                item.addEventListener('drop', async e => {
                    e.preventDefault();
                    item.classList.remove('drag-over');
                    const draggedId = parseInt(e.dataTransfer.getData('text/plain'));
                    if (draggedId === sub.id) return;
                    if (regeneratingId !== null) return;
                    await flushSaves();
                    pushUndo();
                    const order = subtitles.map(s => s.id);
                    const from = order.indexOf(draggedId);
                    const to = order.indexOf(sub.id);
                    order.splice(from, 1);
                    order.splice(to, 0, draggedId);
                    const res = await fetch(`/api/videos/${videoId}/subtitles/reorder`, {
                        method: 'PUT',
                        headers: headers(),
                        body: JSON.stringify({ order })
                    });
                    subtitles = await res.json();
                    renderList();
                    drawTimeline();
                });
            }

            item.querySelector('.play-segment').addEventListener('click', e => {
                e.stopPropagation();
                focusedId = sub.id;
                listEl.querySelectorAll('.editor-sub-item').forEach(card => card.classList.toggle('focused', Number(card.dataset.id) === sub.id));
                playback.toggle(sub.id, Number(item.querySelector('.time-start').value), Number(item.querySelector('.time-end').value));
                drawTimeline();
            });

            item.querySelector('.delete-btn').addEventListener('click', async e => {
                e.stopPropagation();
                const confirmed = await showConfirm({
                    title: 'Xóa câu này?',
                    message: 'Câu phụ đề sẽ bị xóa vĩnh viễn khỏi bài học.',
                    confirmText: 'Xóa',
                    cancelText: 'Hủy',
                    variant: 'danger'
                });
                if (!confirmed) return;
                await flushSaves();
                if (playback.playing(sub.id)) playback.stop();
                pushUndo();
                const res = await fetch(`/api/subtitles/${sub.id}`, { method: 'DELETE', headers: headers() });
                if (!res.ok) {
                    setSaveStatus('error');
                    return;
                }
                await reloadSubs();
                setSaveStatus('saved');
            });

            item.querySelector('.regenerate-btn').addEventListener('click', e => {
                e.stopPropagation();
                regenerateSubtitle(sub.id);
            });

            const sourceChoice = item.querySelector('.quality-use-source');
            const audioChoice = item.querySelector('.quality-use-audio');
            const approveChoice = item.querySelector('.quality-approve');
            sourceChoice?.addEventListener('click', () => approveQualityReview(sub, item, qualityReview.sourceText));
            audioChoice?.addEventListener('click', () => approveQualityReview(sub, item, qualityReview.transcriptionText));
            approveChoice?.addEventListener('click', () => approveQualityReview(
                sub, item, item.querySelector('.field-text').value));

            item.querySelectorAll('textarea, input[type="number"]').forEach(el => {
                el.addEventListener('input', () => scheduleSave(sub.id, item));
                el.addEventListener('focus', () => { focusedId = sub.id; drawTimeline(); });
            });

            item.addEventListener('click', () => { focusedId = sub.id; if (focusMode) renderList(); drawTimeline(); });

            listEl.appendChild(item);
        });
        syncPlayback();
        syncAiControls();
        drawTimeline();
    }

    function scheduleSave(id, item) {
        setSaveStatus('unsaved');
        clearTimeout(saveTimer);
        const body = {
                text: item.querySelector('.field-text').value,
                furigana: item.querySelector('.field-furigana').value,
                translation: item.querySelector('.field-translation').value,
                startTime: parseFloat(item.querySelector('.time-start').value),
                endTime: parseFloat(item.querySelector('.time-end').value)
        };
        const sub = subtitles.find(s => s.id === id);
        if (sub) Object.assign(sub, body);
        saves.queue(id, body);
        saveTimer = setTimeout(() => { flushSaves().catch(() => {}); }, 1500);
        drawTimeline();
    }

    const SAVE_STATES = {
        synced: {
            icon: 'fa-solid fa-cloud-arrow-up',
            text: 'Đã lưu',
            title: 'Mọi thay đổi đã được lưu'
        },
        unsaved: {
            icon: 'fa-solid fa-cloud',
            text: 'Chưa lưu',
            title: 'Có thay đổi chưa được lưu — đợi vài giây hoặc tiếp tục chỉnh sửa'
        },
        saving: {
            icon: 'fa-solid fa-spinner fa-spin',
            text: 'Đang lưu...',
            title: 'Đang lưu thay đổi...'
        },
        saved: {
            icon: 'fa-solid fa-circle-check',
            text: 'Đã lưu',
            title: 'Đã lưu thành công'
        },
        error: {
            icon: 'fa-solid fa-circle-exclamation',
            text: 'Lỗi lưu',
            title: 'Không thể lưu — thử lại hoặc kiểm tra kết nối'
        }
    };

    function setSaveStatus(state) {
        if (!saveIndicator) return;
        const cfg = SAVE_STATES[state] || SAVE_STATES.synced;
        saveIndicator.className = 'save-indicator ' + state;
        saveIndicator.title = cfg.title;
        const iconEl = saveIndicator.querySelector('.save-indicator-icon');
        const textEl = saveIndicator.querySelector('.save-indicator-text');
        if (iconEl) iconEl.className = cfg.icon + ' save-indicator-icon';
        if (textEl) textEl.textContent = cfg.text;
    }

    function qualityReviewPanel(review) {
        if (!review) return '';
        const score = Math.round((Number(review.similarityScore) || 0) * 100);
        if (review.status === 'NEEDS_REVIEW') {
            return `<section class="subtitle-quality-review" aria-label="Xác nhận phụ đề theo audio">
                <p class="subtitle-quality-review__title"><i class="fa-solid fa-triangle-exclamation"></i> Cần xác nhận câu này</p>
                <p class="subtitle-quality-review__copy">Phụ đề và phần nghe chỉ khớp ${score}%. Hãy nghe đoạn này rồi chọn nội dung đúng.</p>
                <div class="subtitle-quality-review__source"><strong>Phụ đề YouTube</strong><span>${esc(review.sourceText || '')}</span></div>
                <div class="subtitle-quality-review__source"><strong>Nhận dạng từ audio</strong><span>${esc(review.transcriptionText || 'Không nhận được nội dung trùng thời điểm.')}</span></div>
                <div class="subtitle-quality-review__actions">
                    <button type="button" class="button-secondary btn-sm quality-use-source">Dùng phụ đề YouTube</button>
                    <button type="button" class="button-secondary btn-sm quality-use-audio" ${review.transcriptionText ? '' : 'disabled'}>Dùng phần nghe</button>
                    <button type="button" class="button-primary btn-sm quality-approve">Xác nhận nội dung đã sửa</button>
                </div>
            </section>`;
        }
        const label = review.status === 'APPROVED' ? 'Đã xác nhận' : 'Đã đối chiếu';
        return `<p class="subtitle-quality-status"><i class="fa-solid fa-circle-check"></i> ${label} với audio (${score}%)</p>`;
    }

    async function approveQualityReview(subtitle, item, text) {
        const buttons = item.querySelectorAll('.subtitle-quality-review button');
        buttons.forEach(button => { button.disabled = true; });
        try {
            const response = await fetch(`/api/subtitles/${subtitle.id}/quality-review/approve`, {
                method: 'PUT', headers: headers(), body: JSON.stringify({ text })
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(data.message || data.error || 'Không thể xác nhận câu phụ đề.');
            subtitle.text = text;
            qualityReviews[subtitle.id] = data;
            notify('Đã xác nhận câu phụ đề.', 'success');
            renderList();
        } catch (error) {
            notify(error.message || 'Không thể xác nhận câu phụ đề.', 'error');
            buttons.forEach(button => { button.disabled = false; });
        }
    }

    function notify(message, type) {
        if (window.JlasUI && typeof window.JlasUI.toast === 'function') {
            window.JlasUI.toast(message, type);
        }
    }

    async function reloadSubs() {
        const [subtitlesResponse, reviewsResponse] = await Promise.all([
            fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() }),
            fetch(`/api/videos/${videoId}/subtitle-quality`, { headers: headers() })
        ]);
        subtitles = await subtitlesResponse.json();
        qualityReviews = reviewsResponse.ok ? await reviewsResponse.json() : qualityReviews;
        renderList();
    }

    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }

    async function runValidation() {
        const res = await fetch(`/api/videos/${videoId}/subtitles/validate`, { headers: headers() });
        const data = await res.json();
        await showValidationModal(data);
        return data;
    }

    validateBtn.addEventListener('click', runValidation);

    publishBtn.addEventListener('click', async () => {
        const isPublished = videoData?.status === 'READY';
        // Pre-check: only hard errors block; warnings (gaps) are OK
        let warnings = [];
        try {
            const vRes = await fetch(`/api/videos/${videoId}/subtitles/validate`, { headers: headers() });
            const vData = await vRes.json();
            if (vData.valid === false || (vData.errors && vData.errors.length)) {
                await showValidationModal(vData);
                return;
            }
            warnings = vData.warnings || [];
        } catch (_) { /* continue to publish */ }

        // Lần xuất bản đầu: bắt buộc tick cam kết bản quyền, không tick thì không xuất bản được.
        // Cảnh báo (nếu có) nằm chung hộp thoại để người dùng chỉ phải xác nhận một lần.
        if (warnings.length || !isPublished) {
            const proceed = await showConfirm({
                title: warnings.length ? 'Có cảnh báo (không chặn xuất bản)' : 'Xác nhận xuất bản',
                message: warnings.length
                    ? warnings.slice(0, 5).join('\n') + (warnings.length > 5 ? '\n…' : '') + '\n\nVẫn xuất bản?'
                    : 'Bài học sẽ được lưu và sẵn sàng để luyện tập.',
                acknowledge: isPublished ? '' : 'Tôi xác nhận có quyền sử dụng nội dung này và chịu hoàn toàn trách nhiệm về việc đăng tải này.',
                confirmText: 'Xuất bản',
                cancelText: 'Hủy'
            });
            if (!proceed) return;
        }

        publishBtn.disabled = true;
        const publishLabel = publishBtn.innerHTML;
        publishBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang xuất bản...';

        let res;
        try {
            res = await fetch(`/api/videos/${videoId}/publish`, { method: 'PUT', headers: headers() });
        } catch (_) {
            publishBtn.disabled = false;
            publishBtn.innerHTML = publishLabel;
            const message = 'Không kết nối được tới máy chủ. Hãy kiểm tra Spring Boot đang chạy rồi thử lại.';
            if (typeof showAlert === 'function') {
                await showAlert({ title: 'Không xuất bản được', message, variant: 'error' });
            } else {
                alert(message);
            }
            return;
        }
        if (res.ok) {
            if (isPublished) {
                if (typeof showAlert === 'function') {
                    await showAlert({
                        title: 'Đã lưu thay đổi',
                        message: 'Bài học đã được cập nhật.',
                        variant: 'success'
                    });
                }
                window.location.href = `/shadowing/practice/${videoId}`;
            } else {
                if (typeof showAlert === 'function') {
                    await showAlert({
                        title: 'Xuất bản thành công',
                        message: 'Bài học đã sẵn sàng.',
                        variant: 'success'
                    });
                }
                window.location.href = `/shadowing/practice/${videoId}`;
            }
        } else {
            const errBody = await res.json().catch(() => ({}));
            const msg = errBody.error || errBody.message || 'Xuất bản thất bại';
            const isModerationError = errBody.code === 'CONTENT_BLOCKED'
                || errBody.code === 'CONTENT_PENDING_REVIEW';
            if (typeof showAlert === 'function') {
                await showAlert({
                    title: isModerationError
                        ? 'Cần sửa nội dung trước khi công khai'
                        : 'Không xuất bản được',
                    message: msg,
                    variant: 'error'
                });
            } else {
                alert(msg);
            }
            publishBtn.disabled = false;
            publishBtn.innerHTML = publishLabel;
        }
    });

    playBtn.addEventListener('click', () => {
        if (video.paused) {
            playback.full();
            video.play().catch(() => showAlert({ title: 'Không phát được video', message: 'Hãy kiểm tra video rồi thử lại.', variant: 'error' }));
        } else playback.stop();
    });

    init();
});
