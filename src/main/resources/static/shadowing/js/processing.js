document.addEventListener('DOMContentLoaded', () => {
    const videoId = document.getElementById('video-id').value;
    const progressFill = document.getElementById('progress-fill');
    const progressText = document.getElementById('progress-text');
    const stepStatus = document.getElementById('step-status');
    const videoTitle = document.getElementById('video-title');
    const errorMsg = document.getElementById('error-msg');
    const retryBtn = document.getElementById('retry-btn');
    const doneButton = document.getElementById('processing-done-button');
    const steps = document.querySelectorAll('.progress-steps li');
    const kitsuTitle = document.querySelector('#processingKitsuGuide .kitsu-guide__title');
    const kitsuMessage = document.querySelector('#processingKitsuGuide .kitsu-guide__message');

    let lastProgress = -1;
    let stuckSince = Date.now();
    let currentStep = 'UPLOADED';
    let furthestStepIndex = 0;
    let complete = false;
    const startedAt = Date.now();

    const STUCK_MS_BY_STEP = {
        YOUTUBE_DOWNLOAD: 2400000,
        SUBTITLE_DOWNLOAD: 300000,
        EXTRACTING_AUDIO: 600000,
        TRANSCRIBING: 900000,
        AI_PROCESSING: 1800000
    };
    const DEFAULT_STUCK_MS = 300000;

    function stuckThresholdMs(step) {
        return STUCK_MS_BY_STEP[step] || DEFAULT_STUCK_MS;
    }

    function updateSteps(currentStep) {
        const activeStep = currentStep || 'UPLOADED';
        const requestedIndex = activeStep === 'DONE'
            ? steps.length
            : Array.from(steps).findIndex(li => li.dataset.step === activeStep);
        if (requestedIndex >= 0) furthestStepIndex = Math.max(furthestStepIndex, requestedIndex);
        steps.forEach((li, index) => {
            const step = li.dataset.step;
            const icon = li.querySelector('.step-icon');
            li.classList.remove('done', 'active');
            icon.className = 'step-icon';
            // Keep the visible checklist sequential even when a server stage
            // optimizes away an internal operation (for example, existing captions).
            if (activeStep === 'DONE' || index < furthestStepIndex) {
                li.classList.add('done');
                icon.classList.add('fa-solid', 'fa-circle-check');
            } else if (index === furthestStepIndex) {
                li.classList.add('active');
                icon.classList.add('fa-solid', 'fa-circle-notch', 'fa-spin');
            } else {
                icon.classList.add('fa-regular', 'fa-circle');
            }
        });
    }

    function updateKitsu(step, failed, languageRejected = false) {
        if (!kitsuTitle || !kitsuMessage) return;
        if (failed) {
            kitsuTitle.textContent = languageRejected ? 'Kitsu-chan xin lỗi' : 'Kitsu-chan cần bạn giúp một chút';
            kitsuMessage.textContent = languageRejected
                ? 'Đây không phải video tiếng Nhật nên mình không thể tạo bài shadowing.'
                : 'Quá trình xử lý gặp trục trặc. Bạn có thể bấm Thử lại; video gốc vẫn được giữ nguyên.';
            return;
        }
        const messages = {
            UPLOADED: 'Mình đang kiểm tra video và chuẩn bị các bước cần thiết.',
            YOUTUBE_DOWNLOAD: 'Mình đang mang video về thư viện. Video dài sẽ cần thêm một chút thời gian.',
            SUBTITLE_DOWNLOAD: 'Mình đang tìm phụ đề phù hợp để tạo bài luyện nghe.',
            EXTRACTING_AUDIO: 'Mình đang tách phần âm thanh để nhận diện lời thoại.',
            TRANSCRIBING: 'Mình đang nghe và chia lời thoại thành từng câu shadowing.',
            AI_PROCESSING: 'Mình đang thêm furigana và bản dịch. Bạn có thể rời trang rồi quay lại sau.',
            DONE: 'Bài học đã sẵn sàng. Bạn có thể trở về thư viện Shadowing.'
        };
        kitsuTitle.textContent = step === 'DONE'
            ? 'Kitsu-chan đã chuẩn bị xong'
            : 'Kitsu-chan đang chuẩn bị bài học';
        kitsuMessage.textContent = messages[step] || messages.UPLOADED;
    }

    function isNonJapaneseFailure(message) {
        const text = String(message || '').toLowerCase();
        return text.includes('không được nhận diện là tiếng nhật')
            || text.includes('không phải video tiếng nhật');
    }

    function authHeaders() {
        if (window.JlasAuth && typeof JlasAuth.authHeaders === 'function') {
            return JlasAuth.authHeaders();
        }
        const h = { 'Content-Type': 'application/json' };
        const t = localStorage.getItem('accessToken');
        if (t) h['Authorization'] = 'Bearer ' + t;
        return h;
    }

    function redirectToLogin() {
        const redirect = window.location.pathname + window.location.search;
        if (window.JlasAuth && typeof JlasAuth.clearSession === 'function') JlasAuth.clearSession();
        window.location.replace('/login?redirect=' + encodeURIComponent(redirect));
    }

    function showComplete(data) {
        if (complete) return;
        complete = true;
        currentStep = 'DONE';
        progressFill.style.width = '100%';
        progressText.textContent = '100%';
        updateSteps('DONE');
        updateKitsu('DONE', false);
        stepStatus.textContent = 'Bài học đã sẵn sàng.';
        errorMsg.style.display = 'none';
        retryBtn.style.display = 'none';
        if (data && data.title) videoTitle.textContent = `Đã tạo xong: "${data.title}"`;
        if (doneButton) doneButton.hidden = false;
    }

    function showDraftReady(data) {
        if (complete) return;
        complete = true;
        currentStep = 'DONE';
        progressFill.style.width = '100%';
        progressText.textContent = '100%';
        updateSteps('DONE');
        stepStatus.textContent = 'Đã tạo bản nháp — sẵn sàng chỉnh sửa phụ đề.';
        errorMsg.style.display = 'none';
        retryBtn.style.display = 'none';
        if (data && data.title) videoTitle.textContent = `Bản nháp: "${data.title}"`;
        if (doneButton) {
            doneButton.hidden = false;
            doneButton.innerHTML = '<i class="fa-solid fa-pen-to-square"></i> Mở trình chỉnh sửa phụ đề';
            doneButton.onclick = () => { window.location.href = `/shadowing/editor/${videoId}`; };
        }
    }

    function showReadyState(data) {
        if (data?.status === 'READY' || data?.videoStatus === 'READY') showComplete(data);
        else showDraftReady(data);
    }

    async function poll() {
        if (complete) return;
        try {
            const headers = authHeaders();
            const [ytRes, videoRes] = await Promise.all([
                fetch(`/api/youtube/import/${videoId}`, { headers }),
                fetch(`/api/videos/${videoId}/status`, { headers })
            ]);
            if (ytRes.status === 401 || ytRes.status === 403 || videoRes.status === 401 || videoRes.status === 403) {
                stepStatus.textContent = 'Phiên đăng nhập đã hết hạn. Đang đưa bạn về trang đăng nhập...';
                redirectToLogin();
                return;
            }
            const ytData = ytRes.ok ? await ytRes.json() : null;
            if (!videoRes.ok) {
                // Prefer YouTube job status when video status is unavailable
                if (ytData) {
                    const pct = ytData.progress ?? 0;
                    progressFill.style.width = pct + '%';
                    progressText.textContent = pct + '%';
                    stepStatus.textContent = ytData.status || 'Đang xử lý...';
                    const ytDone = ytData.videoStatus === 'READY'
                        || (ytData.videoStatus === 'DRAFT' && ytData.processingStep === 'DONE');
                    if (ytDone) {
                        showReadyState(ytData);
                        return;
                    }
                    if (ytData.videoStatus === 'FAILED') {
                        const failureMessage = ytData.errorMessage || 'Xử lý thất bại.';
                        updateKitsu(currentStep, true, isNonJapaneseFailure(failureMessage));
                        errorMsg.style.display = 'block';
                        errorMsg.textContent = failureMessage;
                        retryBtn.style.display = 'inline-block';
                        retryBtn.disabled = false;
                        return;
                    }
                }
                setTimeout(poll, 2000);
                return;
            }
            const data = await videoRes.json();

            if (data.title) {
                videoTitle.textContent = `Đang xử lý: "${data.title}"`;
                videoTitle.title = data.title;
            }

            const pct = ytData?.progress ?? data.progressPercent ?? 0;
            progressFill.style.width = pct + '%';
            progressText.textContent = pct + '%';
            const elapsedMin = Math.floor((Date.now() - startedAt) / 60000);
            const step = data.processingStep || ytData?.processingStep || 'UPLOADED';
            currentStep = step;
            updateSteps(step);
            updateKitsu(step, false);

            // Prefer step-based label; never show "Hoàn thành" while still mid-pipeline
            let statusText = ytData?.status || '';
            if (data.status === 'PROCESSING' || (data.status === 'DRAFT' && step !== 'DONE' && pct < 100)) {
                const stepLabels = {
                    UPLOADED: 'Đang bắt đầu...',
                    YOUTUBE_DOWNLOAD: 'Đang tải video...',
                    SUBTITLE_DOWNLOAD: 'Đang tải phụ đề...',
                    EXTRACTING_AUDIO: 'Đang tách audio...',
                    TRANSCRIBING: 'Đang nhận dạng giọng nói...',
                    AI_PROCESSING: 'Đang xử lý AI (dịch / furigana)...',
                    DONE: 'Hoàn thành'
                };
                statusText = stepLabels[step] || statusText || 'Đang xử lý...';
            } else if (data.status === 'DRAFT' && step === 'DONE') {
                statusText = 'Đã tạo bản nháp — sẵn sàng chỉnh sửa';
            } else if (data.status === 'READY') {
                statusText = 'Bài học đã sẵn sàng';
            }
            stepStatus.textContent = statusText + (elapsedMin > 0 ? ` (${elapsedMin} phút)` : '');

            if (data.status === 'READY' || (data.status === 'DRAFT' && step === 'DONE')) {
                showReadyState(data);
                return;
            }
            if (data.status === 'FAILED') {
                const failureMessage = data.errorMessage || ytData?.errorMessage || 'Xử lý thất bại.';
                updateKitsu(step, true, isNonJapaneseFailure(failureMessage));
                errorMsg.style.display = 'block';
                errorMsg.textContent = failureMessage;
                retryBtn.style.display = 'inline-block';
                retryBtn.disabled = false;
                return;
            }

            if (data.status === 'PROCESSING') {
                retryBtn.style.display = 'none';
                retryBtn.disabled = true;
            }
            if (data.status === 'READY') {
                showComplete(data);
                return;
            }

            if (pct !== lastProgress) {
                lastProgress = pct;
                stuckSince = Date.now();
                errorMsg.style.display = 'none';
            } else if (Date.now() - stuckSince > stuckThresholdMs(currentStep)) {
                errorMsg.style.display = 'block';
                const stepHint = {
                    YOUTUBE_DOWNLOAD: 'Đang tải file video (~24 phút nội dung = 10–30 phút chờ). Không phải treo.',
                    SUBTITLE_DOWNLOAD: 'Đang tải phụ đề từ YouTube (ja + vi/en)...',
                    AI_PROCESSING: 'AI đang xử lý nhiều câu — video dài sẽ lâu hơn.'
                }[currentStep] || 'Xử lý có vẻ bị treo thật.';
                errorMsg.textContent = stepHint + ' Nếu quá lâu, bấm Thử lại hoặc xem log server.';
                retryBtn.style.display = 'inline-block';
            }

            setTimeout(poll, 2000);
        } catch {
            setTimeout(poll, 3000);
        }
    }

    let retryInFlight = false;

    retryBtn.addEventListener('click', async () => {
        if (retryInFlight) return;
        retryInFlight = true;
        retryBtn.disabled = true;
        retryBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang thử lại...';
        errorMsg.style.display = 'none';
        try {
            await fetch(`/api/videos/${videoId}/retry`, { method: 'POST', headers: authHeaders() });
            lastProgress = -1;
            stuckSince = Date.now();
            poll();
        } finally {
            retryInFlight = false;
            retryBtn.innerHTML = '<i class="fa-solid fa-rotate-right"></i> Thử lại';
        }
    });

    poll();
});
