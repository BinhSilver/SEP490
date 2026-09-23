document.addEventListener('DOMContentLoaded', () => {
    const videoId = document.getElementById('video-id').value;
    const video = document.getElementById('editor-video');
    const listEl = document.getElementById('subtitle-editor-list');
    const saveIndicator = document.getElementById('save-indicator');
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
    let videoData = null;
    let saveTimer = null;
    let focusedId = null;
    let focusMode = false;
    let undoStack = [];
    let redoStack = [];
    const MAX_UNDO = 10;

    async function init() {
        const [vRes, sRes] = await Promise.all([
            fetch(`/api/videos/${videoId}`, { headers: headers() }),
            fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() })
        ]);
        if (!vRes.ok) {
            const err = await vRes.json().catch(() => ({}));
            const msg = err.message || err.error || 'Không tải được video (có thể do chưa đăng nhập hoặc không phải chủ sở hữu).';
            if (typeof showAlert === 'function') {
                await showAlert({ title: 'Không mở được bài', message: msg, variant: 'error' });
            } else {
                alert(msg);
            }
            if (vRes.status === 401 || vRes.status === 403) {
                window.location.href = '/login?redirect=' + encodeURIComponent(location.pathname);
            }
            return;
        }
        videoData = await vRes.json();
        subtitles = sRes.ok ? await sRes.json() : [];
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
        const res = await fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() });
        if (!res.ok) return pollAiPending();
        const fresh = await res.json();
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
        clearTimeout(saveTimer);
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
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = '#f0f0f0';
        ctx.fillRect(0, 30, w, 20);
        subtitles.forEach((sub, i) => {
            const x1 = (sub.startTime / dur) * w;
            const x2 = (sub.endTime / dur) * w;
            ctx.fillStyle = sub.id === focusedId ? '#0066cc' : '#99c2eb';
            ctx.fillRect(x1, 20, Math.max(x2 - x1, 2), 40);
            ctx.fillStyle = '#1d1d1f';
            ctx.font = '20px Inter';
            ctx.fillText(String(i + 1), x1 + 4, 18);
        });
        const curX = (video.currentTime / dur) * w;
        ctx.strokeStyle = '#ff3b30';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(curX, 0);
        ctx.lineTo(curX, h);
        ctx.stroke();
    }

    video.addEventListener('timeupdate', drawTimeline);

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

            item.innerHTML = `
                <div class="editor-sub-header">
                    <span class="caption drag-handle" title="Kéo để sắp xếp"><i class="fa-solid fa-grip-vertical"></i> #${index + 1} ${aiBadge}</span>
                    <div class="editor-time-inputs">
                        <input type="number" step="0.1" class="time-start" value="${sub.startTime}">
                        <input type="number" step="0.1" class="time-end" value="${sub.endTime}">
                        <button class="control-btn play-segment" title="Nghe" style="width:28px;height:28px;">
                            <i class="fa-solid fa-play" style="font-size:12px;"></i>
                        </button>
                        <button class="control-btn delete-btn" title="Xóa" style="width:28px;height:28px;"><i class="fa-solid fa-trash" style="font-size:12px;"></i></button>
                    </div>
                </div>
                <div class="editor-field"><label><i class="fa-solid fa-language"></i> Tiếng Nhật</label><textarea class="field-text" rows="2">${esc(sub.text || '')}</textarea></div>
                <div class="editor-field"><label><i class="fa-solid fa-book-open"></i> Cách đọc</label><textarea class="field-furigana" rows="2">${esc(sub.furigana || '')}</textarea></div>
                <div class="editor-field"><label><i class="fa-solid fa-font"></i> Bản dịch</label><textarea class="field-translation" rows="2">${esc(sub.translation || '')}</textarea></div>
                <div class="editor-actions">
                    <button class="button-secondary regenerate-btn btn-sm"><i class="fa-solid fa-arrows-rotate"></i> Tạo lại AI</button>
                    ${index < subtitles.length - 1 ? '<button class="button-secondary merge-btn btn-sm"><i class="fa-solid fa-object-group"></i> Gộp câu tiếp</button>' : ''}
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

            item.querySelector('.play-segment').addEventListener('click', () => {
                focusedId = sub.id;
                video.currentTime = parseFloat(item.querySelector('.time-start').value);
                video.play();
                renderList();
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
                pushUndo();
                const res = await fetch(`/api/subtitles/${sub.id}`, { method: 'DELETE', headers: headers() });
                if (!res.ok) {
                    setSaveStatus('error');
                    return;
                }
                await reloadSubs();
                setSaveStatus('saved');
            });

            item.querySelector('.regenerate-btn').addEventListener('click', async (e) => {
                const btn = e.target;
                btn.disabled = true;
                const text = item.querySelector('.field-text').value;
                const res = await fetch(`/api/subtitles/${sub.id}/regenerate`, {
                    method: 'POST',
                    headers: headers(),
                    body: JSON.stringify({ text })
                });
                const updated = await res.json();
                item.querySelector('.field-furigana').value = updated.furigana || '';
                item.querySelector('.field-translation').value = updated.translation || '';
                btn.disabled = false;
                setSaveStatus('saved');
            });

            const mergeBtn = item.querySelector('.merge-btn');
            if (mergeBtn) {
                mergeBtn.addEventListener('click', async () => {
                    pushUndo();
                    const next = subtitles[index + 1];
                    await fetch('/api/subtitles/merge', {
                        method: 'POST',
                        headers: headers(),
                        body: JSON.stringify({ id1: sub.id, id2: next.id })
                    });
                    await reloadSubs();
                });
            }

            item.querySelectorAll('textarea, input[type="number"]').forEach(el => {
                el.addEventListener('input', () => scheduleSave(sub.id, item));
                el.addEventListener('focus', () => { focusedId = sub.id; drawTimeline(); });
            });

            item.addEventListener('click', () => { focusedId = sub.id; if (focusMode) renderList(); drawTimeline(); });

            listEl.appendChild(item);
        });
        drawTimeline();
    }

    function scheduleSave(id, item) {
        setSaveStatus('unsaved');
        clearTimeout(saveTimer);
        saveTimer = setTimeout(async () => {
            setSaveStatus('saving');
            const body = {
                text: item.querySelector('.field-text').value,
                furigana: item.querySelector('.field-furigana').value,
                translation: item.querySelector('.field-translation').value,
                startTime: parseFloat(item.querySelector('.time-start').value),
                endTime: parseFloat(item.querySelector('.time-end').value)
            };
            try {
                await fetch(`/api/subtitles/${id}`, {
                    method: 'PUT',
                    headers: headers(),
                    body: JSON.stringify(body)
                });
                const idx = subtitles.findIndex(s => s.id === id);
                if (idx >= 0) Object.assign(subtitles[idx], body);
                setSaveStatus('saved');
                drawTimeline();
            } catch { setSaveStatus('error'); }
        }, 1500);
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

    async function reloadSubs() {
        const res = await fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() });
        subtitles = await res.json();
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

    playBtn.addEventListener('click', () => { if (video.paused) video.play(); else video.pause(); });
    video.addEventListener('play', () => { playIcon.className = 'fa-solid fa-pause'; });
    video.addEventListener('pause', () => { playIcon.className = 'fa-solid fa-play'; });

    init();
});
