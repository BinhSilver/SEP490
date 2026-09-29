document.addEventListener('DOMContentLoaded', () => {
    const videoId = document.getElementById('video-id').value;
    const progressFill = document.getElementById('progress-fill');
    const progressText = document.getElementById('progress-text');
    const stepStatus = document.getElementById('step-status');
    const videoTitle = document.getElementById('video-title');
    const errorMsg = document.getElementById('error-msg');
    const retryBtn = document.getElementById('retry-btn');
    const doneButton = document.getElementById('processing-done-button');
    const stopButton = document.getElementById('stop-processing-button');
    const resumeButton = document.getElementById('resume-processing-button');
    const abandonButton = document.getElementById('abandon-processing-button');
    const steps = document.querySelectorAll('.progress-steps li');
    const kitsuTitle = document.querySelector('#processingKitsuGuide .kitsu-guide__title');
    const kitsuMessage = document.querySelector('#processingKitsuGuide .kitsu-guide__message');

    let lastProgress = -1;
    let stuckSince = Date.now();
    let currentStep = 'UPLOADED';
    let furthestStepIndex = 0;
    let complete = false;
    let stopped = false;
    let retryLabel = 'Thử lại';
    const startedAt = Date.now();

    // The video status endpoint is authoritative. An imported lesson is done
    // at DRAFT + DONE; it must be reviewed in the editor before publication.
    function isProcessingFinished(data) {
        return (data?.status === 'DRAFT' || data?.status === 'READY')
            && data?.processingStep === 'DONE'
            && Number(data?.progressPercent) >= 100;
    }

    if (doneButton) doneButton.style.display = 'none';

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
        const isDraft = data?.status === 'DRAFT';
        stepStatus.textContent = isDraft
            ? 'Đã xử lý xong. Hãy kiểm tra phụ đề trước khi xuất bản.'
            : 'Bài học đã sẵn sàng.';
        errorMsg.style.display = 'none';
        retryBtn.style.display = 'none';
        if (stopButton) stopButton.hidden = true;
        if (resumeButton) resumeButton.hidden = true;
        if (abandonButton) abandonButton.hidden = true;
        if (data && data.title) videoTitle.textContent = `Đã tạo xong: "${data.title}"`;
        if (doneButton) {
            doneButton.hidden = false;
            doneButton.style.display = 'inline-flex';
            doneButton.innerHTML = isDraft
                ? '<i class="fa-solid fa-pen-to-square"></i> Kiểm tra và chỉnh sửa phụ đề'
                : '<i class="fa-solid fa-check"></i> Bài học đã hoàn tất — về thư viện Shadowing';
            doneButton.onclick = () => {
                window.location.href = isDraft ? `/shadowing/editor/${videoId}` : '/shadowing';
            };
        }
    }

    function isUserCancelled(message) {
        return String(message || '').includes('Đã dừng xử lý theo yêu cầu');
    }

    function isUserAbandoned(message) {
        return String(message || '').includes('Đã bỏ tiến trình tạo bài');
    }

    function showCancelled(message) {
        stopped = true;
        stepStatus.textContent = 'Đã dừng xử lý bài học.';
        errorMsg.style.display = 'none';
        retryBtn.style.display = 'none';
        if (stopButton) stopButton.hidden = true;
        if (resumeButton) {
            resumeButton.hidden = false;
            resumeButton.style.display = 'inline-flex';
        }
        if (abandonButton) abandonButton.hidden = false;
    }

    function showAbandoned(message) {
        stopped = true;
        stepStatus.textContent = message;
        errorMsg.style.display = 'none';
        if (stopButton) stopButton.hidden = true;
        if (resumeButton) resumeButton.hidden = true;
        if (abandonButton) abandonButton.hidden = true;
        retryLabel = 'Tạo lại từ đầu';
        retryBtn.innerHTML = '<i class="fa-solid fa-rotate-right"></i> Tạo lại từ đầu';
        retryBtn.style.display = 'inline-block';
        retryBtn.disabled = false;
    }

    async function poll() {
        if (complete || stopped) return;
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
                    if (ytData.videoStatus === 'FAILED') {
                        const failureMessage = ytData.errorMessage || 'Xử lý thất bại.';
                        if (isUserCancelled(failureMessage)) {
                            showCancelled(failureMessage);
                            return;
                        }
                        if (isUserAbandoned(failureMessage)) {
                            showAbandoned(failureMessage);
                            return;
                        }
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

            const pct = data.progressPercent ?? ytData?.progress ?? 0;
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
                statusText = 'Đã xử lý xong — cần kiểm tra phụ đề';
            } else if (data.status === 'READY') {
                statusText = 'Bài học đã sẵn sàng';
            }
            stepStatus.textContent = statusText + (elapsedMin > 0 ? ` (${elapsedMin} phút)` : '');

            if (isProcessingFinished(data)) {
                showComplete(data);
                return;
            }
            if (data.status === 'FAILED') {
                const failureMessage = data.errorMessage || ytData?.errorMessage || 'Xử lý thất bại.';
                if (isUserCancelled(failureMessage)) {
                    showCancelled(failureMessage);
                    return;
                }
                if (isUserAbandoned(failureMessage)) {
                    showAbandoned(failureMessage);
                    return;
                }
                if (stopButton) stopButton.hidden = true;
                if (abandonButton) abandonButton.hidden = true;
                updateKitsu(step, true, isNonJapaneseFailure(failureMessage));
                errorMsg.style.display = 'block';
                errorMsg.textContent = failureMessage;
                retryLabel = 'Thử lại';
                retryBtn.innerHTML = '<i class="fa-solid fa-rotate-right"></i> Thử lại';
                retryBtn.style.display = 'inline-block';
                retryBtn.disabled = false;
                return;
            }

            if (data.status === 'PROCESSING') {
                retryBtn.style.display = 'none';
                retryBtn.disabled = true;
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
        retryBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang tạo lại...';
        errorMsg.style.display = 'none';
        try {
            await fetch(`/api/videos/${videoId}/retry`, { method: 'POST', headers: authHeaders() });
            lastProgress = -1;
            stuckSince = Date.now();
            poll();
        } finally {
            retryInFlight = false;
            retryBtn.innerHTML = `<i class="fa-solid fa-rotate-right"></i> ${retryLabel}`;
        }
    });

    stopButton?.addEventListener('click', async () => {
        const confirmed = typeof window.showConfirm === 'function'
            ? await window.showConfirm({
                title: 'Dừng xử lý bài học?',
                message: 'Bài học sẽ tạm dừng. Bạn có thể tiếp tục từ phần đã lưu nếu không thay đổi thiết lập.',
                confirmText: 'Dừng xử lý',
                cancelText: 'Tiếp tục xử lý',
                variant: 'danger'
            })
            : window.confirm('Dừng xử lý bài học?');
        if (!confirmed) return;

        stopButton.disabled = true;
        stopButton.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang dừng...';
        try {
            const response = await fetch(`/api/videos/${videoId}/cancel`, {
                method: 'POST', headers: authHeaders()
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(data.message || data.error || 'Không thể dừng xử lý.');
            showCancelled(data.message || 'Đã dừng xử lý bài học.');
        } catch (error) {
            stopButton.disabled = false;
            stopButton.innerHTML = '<i class="fa-solid fa-stop"></i> Dừng xử lý';
            errorMsg.style.display = 'block';
            errorMsg.textContent = error.message || 'Không thể dừng xử lý.';
        }
    });

    abandonButton?.addEventListener('click', async () => {
        const confirmed = typeof window.showConfirm === 'function'
            ? await window.showConfirm({
                title: 'Bỏ tiến trình tạo bài?',
                message: 'Bài này sẽ không thể tiếp tục từ bước đang lưu. Bản nháp vẫn được giữ lại để bạn tạo lại từ đầu.',
                confirmText: 'Bỏ tiến trình',
                cancelText: 'Giữ tiến trình',
                variant: 'danger'
            })
            : window.confirm('Bỏ tiến trình tạo bài? Bản nháp sẽ được giữ lại để tạo lại từ đầu.');
        if (!confirmed) return;

        abandonButton.disabled = true;
        abandonButton.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang bỏ...';
        try {
            const response = await fetch(`/api/videos/${videoId}/abandon`, {
                method: 'POST', headers: authHeaders()
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(data.message || data.error || 'Không thể bỏ tiến trình.');
            window.location.href = '/shadowing';
        } catch (error) {
            abandonButton.disabled = false;
            abandonButton.innerHTML = '<i class="fa-solid fa-ban"></i> Bỏ tiến trình';
            errorMsg.style.display = 'block';
            errorMsg.textContent = error.message || 'Không thể bỏ tiến trình.';
        }
    });

    let resumeInFlight = false;
    resumeButton?.addEventListener('click', async () => {
        if (resumeInFlight) return;
        resumeInFlight = true;
        resumeButton.disabled = true;
        resumeButton.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang tiếp tục...';
        try {
            const response = await fetch(`/api/videos/${videoId}/resume`, {
                method: 'POST', headers: authHeaders()
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(data.message || data.error || 'Không thể tiếp tục xử lý.');
            stopped = false;
            retryLabel = 'Thử lại';
            lastProgress = -1;
            stuckSince = Date.now();
            resumeButton.hidden = true;
            if (stopButton) {
                stopButton.hidden = false;
                stopButton.disabled = false;
                stopButton.innerHTML = '<i class="fa-solid fa-stop"></i> Dừng xử lý';
            }
            if (abandonButton) abandonButton.hidden = false;
            stepStatus.textContent = data.message || 'Đang tiếp tục xử lý...';
            poll();
        } catch (error) {
            errorMsg.style.display = 'block';
            errorMsg.textContent = error.message || 'Không thể tiếp tục xử lý.';
            resumeButton.disabled = false;
        } finally {
            resumeInFlight = false;
            if (!stopped) resumeButton.innerHTML = '<i class="fa-solid fa-play"></i> Tiếp tục xử lý';
        }
    });

    poll();
});
