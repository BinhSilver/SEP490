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

        video.src = streamUrl();
        video.addEventListener('loadedmetadata', drawTimeline);
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
        await fetch(`/api/videos/${videoId}`, {
            method: 'PUT',
            headers: headers(),
            body: JSON.stringify({ title: metaTitle.value, category: metaCategory?.value || 'Daily' })
        });
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

    function drawTimeline() {
        if (!waveformCanvas || !subtitles.length || !video.duration) return;
        const ctx = waveformCanvas.getContext('2d');
        const w = waveformCanvas.width = waveformCanvas.offsetWidth * 2;
        const h = waveformCanvas.height = 80;
        const dur = video.duration;
        const timelineStyle = getComputedStyle(waveformCanvas);
        const timelineColor = (name, fallback) => timelineStyle.getPropertyValue(name).trim() || fallback;
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = timelineColor('--timeline-bg', '#f0f0f0');
        ctx.fillRect(0, 30, w, 20);
        subtitles.forEach((sub, i) => {
            const x1 = (sub.startTime / dur) * w;
            const x2 = (sub.endTime / dur) * w;
            ctx.fillStyle = sub.id === focusedId
                ? timelineColor('--timeline-active', '#0066cc')
                : timelineColor('--timeline-segment', '#99c2eb');
            ctx.fillRect(x1, 20, Math.max(x2 - x1, 2), 40);
            ctx.fillStyle = timelineColor('--timeline-text', '#1d1d1f');
            ctx.font = '20px Inter';
            ctx.fillText(String(i + 1), x1 + 4, 18);
        });
        const curX = (video.currentTime / dur) * w;
        ctx.strokeStyle = timelineColor('--timeline-playhead', '#ff3b30');
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(curX, 0);
        ctx.lineTo(curX, h);
        ctx.stroke();
    }

    video.addEventListener('timeupdate', drawTimeline);
    // Canvas pixels must be repainted when the theme changes, including while paused.
    new MutationObserver(drawTimeline).observe(document.documentElement, {
        attributes: true, attributeFilter: ['data-theme']
    });

    waveformCanvas?.addEventListener('click', e => {
        const rect = waveformCanvas.getBoundingClientRect();
        const x = (e.clientX - rect.left) / rect.width;
        video.currentTime = x * video.duration;
        const sub = subtitles.find(s => video.currentTime >= s.startTime && video.currentTime <= s.endTime);
        if (sub) { focusedId = sub.id; renderList(); }
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
        try {
            const vRes = await fetch(`/api/videos/${videoId}/subtitles/validate`, { headers: headers() });
            const vData = await vRes.json();
            if (vData.valid === false || (vData.errors && vData.errors.length)) {
                await showValidationModal(vData);
                return;
            }
            if (vData.warnings && vData.warnings.length) {
                const proceed = await showConfirm({
                    title: 'Có cảnh báo (không chặn xuất bản)',
                    message: vData.warnings.slice(0, 5).join('\n')
                        + (vData.warnings.length > 5 ? '\n…' : '')
                        + '\n\nVẫn xuất bản?',
                    confirmText: 'Xuất bản',
                    cancelText: 'Hủy'
                });
                if (!proceed) return;
            }
        } catch (_) { /* continue to publish */ }

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
