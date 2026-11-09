document.addEventListener('DOMContentLoaded', () => {
    const returnPath = window.location.pathname + window.location.search;
    if (window.JlasAuth && typeof JlasAuth.requireLogin === 'function') {
        if (!JlasAuth.requireLogin(returnPath)) return;
    } else if (!localStorage.getItem('accessToken')) {
        window.location.href = '/login?redirect=' + encodeURIComponent(returnPath);
        return;
    }

    const modeTabs = document.querySelectorAll('.create-mode-tab');
    const panelFile = document.getElementById('panel-file');
    const panelYoutube = document.getElementById('panel-youtube');

    // --- File upload ---
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('video-file');
    const fileInfo = document.getElementById('file-info');
    const titleInput = document.getElementById('title-input');
    const fileCategorySelect = document.getElementById('file-category-select');
    window.ShadowingGenres.bind(document.getElementById('file-genres'));
    window.ShadowingGenres.bind(document.getElementById('yt-genres'));
    const fileLangSelect = document.getElementById('file-lang-select');
    const uploadBtn = document.getElementById('upload-btn');
    const fileStatus = document.getElementById('file-status');
    const thumbDropzone = document.getElementById('thumb-dropzone');
    const thumbInput = document.getElementById('thumbnail-file');
    const thumbPreview = document.getElementById('thumb-preview');
    const thumbPreviewImg = document.getElementById('thumb-preview-img');
    const thumbInfo = document.getElementById('thumb-info');

    /** When furigana/dịch AI is ON — all paths (file + YouTube). */
    const AI_MAX_DURATION_SEC = 1200; // 20 minutes
    let selectedFile = null;
    let selectedThumb = null;
    let fileDurationSec = 0;

    // --- YouTube ---
    const urlInput = document.getElementById('youtube-url-input');
    const analyzeBtn = document.getElementById('analyze-btn');
    const importBtn = document.getElementById('import-btn');
    const youtubeStatus = document.getElementById('youtube-status');
    const resultPanel = document.getElementById('analyze-result');
    const previewThumb = document.getElementById('preview-thumb');
    const previewTitle = document.getElementById('preview-title');
    const previewChannel = document.getElementById('preview-channel');
    const previewDuration = document.getElementById('preview-duration');
    const previewBadges = document.getElementById('preview-badges');
    const languageCheckPanel = document.getElementById('language-check-panel');
    const languageCheckTitle = document.getElementById('language-check-title');
    const languageCheckMessage = document.getElementById('language-check-message');
    const ytCategorySelect = document.getElementById('yt-category-select');
    const ytLangSelect = document.getElementById('yt-lang-select');
    const fileMinAgeSelect = document.getElementById('file-min-age-select');
    const ytMinAgeSelect = document.getElementById('yt-min-age-select');
    const ytMinAgeHint = document.getElementById('yt-min-age-hint');

    /** YouTube's own age restriction is the floor: lower ratings are disabled (the server keeps the stricter one). */
    function applyYoutubeAgeLimit(youtubeMinAge) {
        const floor = Number(youtubeMinAge) || 0;
        Array.from(ytMinAgeSelect.options).forEach(option => { option.disabled = Number(option.value) < floor; });
        if (Number(ytMinAgeSelect.value) < floor) ytMinAgeSelect.value = String(floor);
        ytMinAgeHint.hidden = !floor;
        ytMinAgeHint.textContent = floor
            ? `YouTube giới hạn video này cho người từ ${floor} tuổi, nên nhãn không thấp hơn ${floor}+.` : '';
    }

    let analyzedUrl = null;
    let analyzeData = null;
    let analyzeAbortController = null;

    if (new URLSearchParams(window.location.search).has('cancelled')
            && window.JlasUI && typeof window.JlasUI.toast === 'function') {
        window.JlasUI.toast('Đã dừng xử lý. Bạn có thể thay đổi thiết lập rồi tạo bài mới.', 'info');
    }

    const YT_ID_RE = /(?:youtube\.com\/(?:watch\?v=|embed\/|shorts\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/;

    function authHeaders(json) {
        if (window.JlasAuth && typeof JlasAuth.authHeaders === 'function') {
            return JlasAuth.authHeaders();
        }
        const h = {};
        if (json !== false) h['Content-Type'] = 'application/json';
        const t = localStorage.getItem('accessToken');
        if (t) h['Authorization'] = 'Bearer ' + t;
        return h;
    }

    modeTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            const mode = tab.dataset.mode;
            modeTabs.forEach(t => {
                const active = t.dataset.mode === mode;
                t.classList.toggle('active', active);
                t.setAttribute('aria-selected', active ? 'true' : 'false');
            });
            panelFile.hidden = mode !== 'file';
            panelYoutube.hidden = mode !== 'youtube';
        });
    });

    function formatDuration(sec) {
        const s = Math.max(0, Math.floor(sec || 0));
        const m = Math.floor(s / 60);
        const r = s % 60;
        if (m >= 60) {
            const h = Math.floor(m / 60);
            const mm = m % 60;
            return `${h} giờ ${mm} phút ${r} giây`;
        }
        return m > 0 ? `${m} phút ${r} giây` : `${r} giây`;
    }

    function formatFileDuration(sec) {
        const m = Math.floor(sec / 60);
        const s = Math.floor(sec % 60);
        return `${m}:${String(s).padStart(2, '0')}`;
    }

    // ----- File upload flow -----
    function resetFile() {
        selectedFile = null;
        fileDurationSec = 0;
        dropzone.classList.remove('has-file', 'has-video-preview');
        dropzone.style.removeProperty('--upload-preview-image');
        fileInfo.textContent = '';
        uploadBtn.disabled = true;
        fileInput.value = '';
    }

    dropzone.addEventListener('click', () => fileInput.click());
    dropzone.addEventListener('dragover', e => { e.preventDefault(); dropzone.classList.add('dragover'); });
    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
    dropzone.addEventListener('drop', e => {
        e.preventDefault();
        dropzone.classList.remove('dragover');
        if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]);
    });
    fileInput.addEventListener('change', () => { if (fileInput.files[0]) handleFile(fileInput.files[0]); });

    const AUDIO_EXT_RE = /\.(mp3|wav|m4a|ogg|flac|aac|opus)$/i;
    const VIDEO_EXT_RE = /\.(mp4|mov|webm|mkv|avi|m4v)$/i;

    function isAudioFile(file) {
        if (file.type && file.type.startsWith('audio/')) return true;
        if (file.type && file.type.startsWith('video/')) return false;
        return AUDIO_EXT_RE.test(file.name || '');
    }

    function isVideoFile(file) {
        if (file.type && file.type.startsWith('video/')) return true;
        if (file.type && file.type.startsWith('audio/')) return false;
        return VIDEO_EXT_RE.test(file.name || '');
    }

    function handleFile(file) {
        const audio = isAudioFile(file);
        const video = isVideoFile(file);
        if (!audio && !video) {
            showAlert('Vui lòng chọn file video (MP4, MOV, WebM) hoặc audio (MP3, WAV, M4A, OGG).');
            return;
        }
        if (file.size > 100 * 1024 * 1024) { showAlert('File quá lớn (tối đa 100MB)'); return; }

        selectedFile = file;
        const sizeMB = (file.size / 1024 / 1024).toFixed(1);
        const kind = audio ? 'audio' : 'video';
        fileInfo.textContent = `${file.name} — ${sizeMB} MB — đang đọc thời lượng...`;
        uploadBtn.disabled = true;

        dropzone.classList.remove('has-video-preview');
        dropzone.style.removeProperty('--upload-preview-image');
        const el = document.createElement(audio ? 'audio' : 'video');
        const objectUrl = URL.createObjectURL(file);
        el.preload = 'metadata';
        el.onloadedmetadata = () => {
            fileDurationSec = el.duration;

            if (!Number.isFinite(fileDurationSec) || fileDurationSec <= 0) {
                URL.revokeObjectURL(objectUrl);
                showAlert({ title: 'Lỗi', message: `Không đọc được thời lượng ${kind}.`, variant: 'error' });
                resetFile();
                return;
            }
            // No hard duration reject here — AI 20-min limit checked at upload if furigana is on

            dropzone.classList.add('has-file');
            fileInfo.textContent = `${file.name} — ${sizeMB} MB — ${formatFileDuration(fileDurationSec)}${audio ? ' · audio' : ''}`;
            if (!titleInput.value) titleInput.value = file.name.replace(/\.[^.]+$/, '');
            uploadBtn.disabled = false;

            if (video) {
                captureFirstVideoFrame(el, objectUrl, file);
            } else {
                URL.revokeObjectURL(objectUrl);
            }
        };
        el.onerror = () => {
            URL.revokeObjectURL(objectUrl);
            showAlert({ title: 'Lỗi', message: `Không đọc được file ${kind}.`, variant: 'error' });
            resetFile();
        };
        el.src = objectUrl;
    }

    function captureFirstVideoFrame(video, objectUrl, file) {
        let captured = false;
        const finish = () => {
            if (captured) return;
            captured = true;
            try {
                if (selectedFile !== file) return;
                const width = video.videoWidth;
                const height = video.videoHeight;
                if (width && height) {
                    const canvas = document.createElement('canvas');
                    canvas.width = width;
                    canvas.height = height;
                    canvas.getContext('2d').drawImage(video, 0, 0, width, height);
                    dropzone.style.setProperty('--upload-preview-image', `url("${canvas.toDataURL('image/jpeg', 0.82)}")`);
                    dropzone.classList.add('has-video-preview');
                }
            } catch (_) {
                // A preview is only a convenience; the selected video remains valid without it.
            } finally {
                URL.revokeObjectURL(objectUrl);
            }
        };

        video.addEventListener('seeked', finish, { once: true });
        const firstVisibleMoment = Math.min(0.1, Math.max(0, (video.duration || 0) / 20));
        if (firstVisibleMoment > 0) video.currentTime = firstVisibleMoment;
        else finish();
    }

    thumbDropzone.addEventListener('click', () => thumbInput.click());
    thumbInput.addEventListener('change', () => {
        if (thumbInput.files[0]) handleThumb(thumbInput.files[0]);
    });

    function handleThumb(file) {
        if (!file.type.startsWith('image/')) {
            showAlert('Vui lòng chọn file ảnh (JPG, PNG, WebP)');
            return;
        }
        if (file.size > 5 * 1024 * 1024) {
            showAlert('Ảnh bìa quá lớn (tối đa 5MB)');
            return;
        }
        selectedThumb = file;
        const sizeKB = (file.size / 1024).toFixed(0);
        thumbInfo.textContent = `${file.name} — ${sizeKB} KB`;
        thumbPreviewImg.src = URL.createObjectURL(file);
        thumbPreview.style.display = 'block';
        thumbDropzone.classList.add('has-file');
    }

    uploadBtn.addEventListener('click', async () => {
        if (!selectedFile) return;
        if (!titleInput.value.trim()) { showAlert('Vui lòng nhập tiêu đề'); return; }

        const fileFuri = document.getElementById('file-furigana-check');
        const wantAi = !!(fileFuri && fileFuri.checked);
        if (wantAi && fileDurationSec > AI_MAX_DURATION_SEC) {
            showAlert({
                title: 'Quá dài cho AI',
                message: `File dài ${formatFileDuration(fileDurationSec)}. Khi bật furigana/dịch AI tối đa 20 phút. Tắt AI để upload file dài hơn.`,
                variant: 'warning'
            });
            return;
        }

        const formData = new FormData();
        formData.append('file', selectedFile);
        if (selectedThumb) formData.append('thumbnail', selectedThumb);
        formData.append('title', titleInput.value.trim());
        formData.append('category', fileCategorySelect.value);
        window.ShadowingGenres.read(document.getElementById('file-genres')).forEach(code => formData.append('genres', code));
        formData.append('targetLanguage', fileLangSelect.value);
        formData.append('generateFurigana', wantAi ? 'true' : 'false');
        formData.append('minAge', fileMinAgeSelect.value);

        fileStatus.textContent = 'Đang upload...';
        uploadBtn.disabled = true;

        try {
            const headers = {};
            const t = (window.JlasAuth && JlasAuth.getAccessToken && JlasAuth.getAccessToken())
                || localStorage.getItem('accessToken');
            if (t) headers['Authorization'] = 'Bearer ' + t;
            const res = await fetch('/api/videos/upload', { method: 'POST', headers, body: formData });
            const data = await res.json().catch(() => ({}));
            if (res.ok || res.status === 202) {
                window.location.href = `/shadowing/create/${data.id}/processing`;
            } else {
                fileStatus.textContent = data.error || 'Lỗi upload.';
                uploadBtn.disabled = false;
            }
        } catch {
            fileStatus.textContent = 'Lỗi kết nối.';
            uploadBtn.disabled = false;
        }
    });

    // ----- YouTube flow -----
    function looksLikeYoutube(url) {
        const t = (url || '').trim();
        return t.includes('youtube.com') || t.includes('youtu.be');
    }

    function extractVideoId(url) {
        const m = (url || '').trim().match(YT_ID_RE);
        return m ? m[1] : null;
    }

    function resetYoutubeResult() {
        analyzedUrl = null;
        analyzeData = null;
        resultPanel.hidden = true;
        importBtn.disabled = false;
    }

    function setAnalyzing(isAnalyzing) {
        analyzeBtn.disabled = false;
        analyzeBtn.innerHTML = isAnalyzing
            ? '<i class="fa-solid fa-stop"></i> Dừng phân tích'
            : '<i class="fa-solid fa-magnifying-glass"></i> Phân tích video';
        analyzeBtn.setAttribute('aria-label', isAnalyzing ? 'Dừng phân tích video' : 'Phân tích video');
    }

    function validateClientUrl(url) {
        if (!url.trim()) return 'Vui lòng nhập link YouTube.';
        if (!looksLikeYoutube(url)) return 'Link không phải YouTube. Dùng youtube.com hoặc youtu.be.';
        if (!extractVideoId(url)) return 'Không nhận diện được ID video trong link.';
        return null;
    }

    function renderAnalyzeResult(data) {
        analyzeData = data;
        analyzedUrl = data.url || urlInput.value.trim();

        const fullTitle = data.title || 'YouTube Video';
        const shortTitle = typeof window.truncateTitle === 'function'
            ? window.truncateTitle(fullTitle, 56)
            : (fullTitle.length > 56 ? fullTitle.slice(0, 55) + '…' : fullTitle);
        previewTitle.textContent = shortTitle;
        previewTitle.title = fullTitle;
        previewChannel.textContent = data.channel ? `Kênh: ${data.channel}` : '';
        previewDuration.textContent = `Thời lượng: ${formatDuration(data.duration)}`;

        renderLanguageCheck(data);
        applyYoutubeAgeLimit(data.youtubeMinAge);

        if (data.thumbnail) {
            previewThumb.src = data.thumbnail;
            previewThumb.hidden = false;
        } else {
            previewThumb.hidden = true;
        }

        previewBadges.innerHTML = '';
        if (data.hasSubtitle) {
            previewBadges.innerHTML += '<li class="badge-ok"><i class="fa-solid fa-circle-check"></i> Có phụ đề tiếng Nhật</li>';
        } else if (data.hasAutoSubtitle) {
            previewBadges.innerHTML += '<li class="badge-ok"><i class="fa-solid fa-circle-check"></i> Có phụ đề tự động tiếng Nhật</li>';
        } else {
            previewBadges.innerHTML += '<li class="badge-warn"><i class="fa-solid fa-circle-xmark"></i> Không có phụ đề</li>';
        }

        const hasVi = data.viManual || data.viAuto;
        const hasEn = data.enManual || data.enAuto;
        if (hasVi) {
            previewBadges.innerHTML += '<li class="badge-ok"><i class="fa-solid fa-language"></i> Có phụ đề tiếng Việt (YouTube)</li>';
        }
        if (hasEn) {
            previewBadges.innerHTML += '<li class="badge-ok"><i class="fa-solid fa-language"></i> Có phụ đề tiếng Anh (YouTube)</li>';
        }

        if (data.requiresAiStt && !data.blockedNoSubtitleTooLong) {
            previewBadges.innerHTML += '<li class="badge-warn"><i class="fa-solid fa-microphone"></i> Sẽ dùng AI tạo phụ đề (Deepgram)</li>';
        }
        if (data.blockedNoSubtitleTooLong) {
            previewBadges.innerHTML += '<li class="badge-err"><i class="fa-solid fa-ban"></i> Quá dài để dùng AI STT (>5 phút)</li>';
        }

        importBtn.disabled = !!data.blockedNoSubtitleTooLong || !!data.blockedNonJapanese;
        resultPanel.hidden = false;
    }

    function renderLanguageCheck(data) {
        if (!languageCheckPanel) return;
        const blocked = !!data.blockedNonJapanese;
        if (!blocked) {
            languageCheckPanel.hidden = true;
            return;
        }

        languageCheckPanel.hidden = false;
        languageCheckPanel.className = 'language-check-panel language-check-blocked';
        languageCheckTitle.textContent = 'Kitsu-chan xin lỗi';
        languageCheckMessage.textContent = 'Đây không phải video tiếng Nhật nên mình không thể tạo bài shadowing.';
    }

    analyzeBtn.addEventListener('click', async () => {
        if (analyzeAbortController) {
            analyzeAbortController.abort();
            return;
        }
        const url = urlInput.value.trim();
        const err = validateClientUrl(url);
        if (err) {
            showAlert({ title: 'Link không hợp lệ', message: err, variant: 'warning' });
            return;
        }

        resetYoutubeResult();
        youtubeStatus.textContent = 'Đang phân tích video...';
        analyzeAbortController = new AbortController();
        setAnalyzing(true);

        try {
            const res = await fetch('/api/youtube/analyze', {
                method: 'POST',
                headers: authHeaders(),
                body: JSON.stringify({ url }),
                signal: analyzeAbortController.signal
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                const message = data.error || 'Không phân tích được video. Hãy kiểm tra link hoặc chọn video khác.';
                youtubeStatus.textContent = message;
                youtubeStatus.classList.add('is-error');
                showAlert({ title: 'Không thể phân tích video', message, variant: 'error' });
                return;
            }
            youtubeStatus.classList.remove('is-error');
            youtubeStatus.textContent = 'Phân tích xong — xem kết quả bên dưới.';
            renderAnalyzeResult(data);
        } catch (error) {
            youtubeStatus.textContent = error.name === 'AbortError'
                ? 'Đã dừng phân tích. Bạn có thể thay đổi link hoặc thiết lập.'
                : 'Lỗi kết nối máy chủ.';
        } finally {
            analyzeAbortController = null;
            setAnalyzing(false);
        }
    });

    importBtn.addEventListener('click', async () => {
        if (!analyzedUrl || !analyzeData) {
            showAlert('Hãy phân tích video trước.');
            return;
        }
        if (analyzeData.blockedNoSubtitleTooLong) {
            showAlert({ title: 'Không thể import', message: 'Video quá dài và không có phụ đề. Chọn video ngắn hơn 5 phút.', variant: 'warning' });
            return;
        }
        if (analyzeData.blockedNonJapanese) {
            showAlert({ title: 'Đã chặn video', message: analyzeData.languageMessage || 'KotsuKotsu chỉ nhận nội dung tiếng Nhật.', variant: 'warning' });
            return;
        }
        const ytFuri = document.getElementById('yt-furigana-check');
        const wantAi = !!(ytFuri && ytFuri.checked);
        const ytDur = Number(analyzeData.duration) || 0;
        if (wantAi && ytDur > AI_MAX_DURATION_SEC) {
            showAlert({
                title: 'Quá dài cho AI',
                message: `Video ~${Math.round(ytDur / 60)} phút. Khi bật furigana/dịch AI tối đa 20 phút. Tắt AI để import (chỉ phụ đề YouTube).`,
                variant: 'warning'
            });
            return;
        }

        const confirmed = typeof window.showConfirm === 'function'
            ? await window.showConfirm({
                title: 'Tạo bài Shadowing?',
                message: 'Hệ thống sẽ bắt đầu tạo bài với video và thiết lập bạn đã chọn.',
                confirmText: 'Tạo Shadowing',
                cancelText: 'Quay lại chỉnh sửa',
                variant: 'primary'
            })
            : window.confirm('Tạo bài Shadowing với thiết lập hiện tại?');
        if (!confirmed) return;

        youtubeStatus.textContent = 'Đang bắt đầu tạo shadowing...';
        importBtn.disabled = true;
        analyzeBtn.disabled = true;

        try {
            const res = await fetch('/api/youtube/import', {
                method: 'POST',
                headers: authHeaders(),
                body: JSON.stringify({
                    url: analyzedUrl,
                    category: ytCategorySelect.value,
                    genres: window.ShadowingGenres.read(document.getElementById('yt-genres')),
                    targetLanguage: ytLangSelect.value,
                    generateFurigana: wantAi,
                    minAge: Number(ytMinAgeSelect.value)
                })
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok || res.status === 202) {
                const id = data.lessonId || data.jobId;
                window.location.href = `/shadowing/create/${id}/processing`;
            } else {
                const message = data.error || 'Không thể tạo bài học. Hãy kiểm tra link hoặc chọn video khác.';
                youtubeStatus.textContent = message;
                youtubeStatus.classList.add('is-error');
                showAlert({ title: 'Không thể tạo bài shadowing', message, variant: 'error' });
                importBtn.disabled = false;
                analyzeBtn.disabled = false;
            }
        } catch {
            youtubeStatus.textContent = 'Lỗi kết nối.';
            importBtn.disabled = false;
            analyzeBtn.disabled = false;
        }
    });

    urlInput.addEventListener('input', () => {
        resetYoutubeResult();
        youtubeStatus.textContent = '';
        youtubeStatus.classList.remove('is-error');
    });
});
