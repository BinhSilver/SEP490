document.addEventListener('DOMContentLoaded', () => {
    const videoIdInput = document.getElementById('video-id');
    if (!videoIdInput) return;

    const videoId = videoIdInput.value;
    const playerContainer = document.getElementById('player-container');
    const video = document.getElementById('main-video');
    const subtitleList = document.getElementById('subtitle-list');
    const transcriptColumn = document.querySelector('.transcript-column');

    const prevBtn = document.getElementById('prev-btn');
    const replayBtn = document.getElementById('replay-btn');
    const playBtn = document.getElementById('play-btn');
    const nextBtn = document.getElementById('next-btn');
    const loopBtn = document.getElementById('loop-btn');
    const loopCountSelect = document.getElementById('loop-count');
    const playIcon = document.getElementById('play-icon');
    const translationToggleBtn = document.getElementById('translation-toggle-btn');
    const furiganaToggleBtn = document.getElementById('furigana-toggle-btn');
    const viewerJapanese = document.getElementById('viewer-japanese');
    const viewerTranslation = document.getElementById('viewer-translation');
    const speedToggleBtn = document.getElementById('speed-toggle-btn');
    const speedMenu = document.getElementById('speed-menu');
    const speedToggleLabel = document.getElementById('speed-toggle-label');
    const speedBtns = document.querySelectorAll('.speed-menu .speed-btn');
    const playerControls = document.getElementById('player-controls');
    const playerControlsSlot = document.getElementById('player-controls-slot');
    const playerControlsToggle = document.getElementById('player-controls-toggle');
    const timelineFill = document.getElementById('timeline-fill');
    const timelineTime = document.getElementById('timeline-time');
    const timelineTrack = document.getElementById('timeline-track');
    const lessonTitle = document.getElementById('lesson-title');
    const lessonDurationBadge = document.getElementById('lesson-duration-badge');
    const lessonCategoryBadge = document.getElementById('lesson-category-badge');
    const lessonViewsBadge = document.getElementById('lesson-views-badge');
    const transcriptCount = document.getElementById('transcript-count');
    const favoriteBtn = document.getElementById('favorite-btn');
    const shareBtn = document.getElementById('share-btn');
    const ratingSummary = document.getElementById('rating-summary');
    const commentList = document.getElementById('comment-list');
    const commentInput = document.getElementById('comment-input');
    const commentSubmitBtn = document.getElementById('comment-submit-btn');
    const commentCountEl = document.getElementById('comment-count');
    const commentStarsEl = document.getElementById('comment-stars');
    let commentRating = 0;

    const clientId = getClientId();

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

    let videoMeta = null;
    let isFavorited = false;
    let isAudioOnly = false;

    let subtitles = [];
    let currentActiveIndex = -1;
    let pronunciationSubtitleIndex = -1;

    window.getShadowingPronunciationContext = function (requestedIndex) {
        const index = Number.isInteger(requestedIndex) ? requestedIndex : pronunciationSubtitleIndex;
        if (index < 0 || !subtitles[index]) return null;
        return {
            subtitleId: subtitles[index].id,
            sentenceNumber: index + 1,
            referenceText: subtitles[index].text || '',
            readingText: subtitles[index].furigana || subtitles[index].text || '',
            translation: subtitles[index].translation || '',
            startTime: subtitles[index].startTime,
            endTime: subtitles[index].endTime
        };
    };

    function selectPronunciationSubtitle(index, source = 'user') {
        if (index < 0 || !subtitles[index] || pronunciationSubtitleIndex === index) return;
        pronunciationSubtitleIndex = index;
        const context = window.getShadowingPronunciationContext();
        window.dispatchEvent(new CustomEvent('shadowing:pronunciation-context-change', {
            detail: { ...context, source }
        }));
    }
    window.selectShadowingPronunciationSubtitle = selectPronunciationSubtitle;
    let isFuriganaVisible = true;
    let isTranslationVisible = true;
    let isLoopEnabled = false;
    let loopCountMax = 0;
    let currentLoopCount = 0;
    let scrollAnimFrame = null;
    const FOLLOW_ANCHOR_RATIO = 0.32;
    playerContainer.classList.add('show-furigana');
    video.preservesPitch = true;

    function cancelTranscriptScroll() {
        if (scrollAnimFrame) {
            cancelAnimationFrame(scrollAnimFrame);
            scrollAnimFrame = null;
        }
    }

    function scrollActiveToAnchor(instant = false, index = currentActiveIndex) {
        if (!transcriptColumn || index < 0) return;
        const item = document.getElementById(`sub-${index}`);
        if (!item) return;

        cancelTranscriptScroll();

        const cRect = transcriptColumn.getBoundingClientRect();
        const iRect = item.getBoundingClientRect();
        const anchorY = cRect.top + cRect.height * FOLLOW_ANCHOR_RATIO;
        const itemMidY = iRect.top + iRect.height / 2;
        const delta = itemMidY - anchorY;

        if (Math.abs(delta) < 6) return;
        if (delta < 0 && transcriptColumn.scrollTop <= 1) return;

        const startTop = transcriptColumn.scrollTop;
        const maxTop = transcriptColumn.scrollHeight - transcriptColumn.clientHeight;
        const targetTop = Math.max(0, Math.min(maxTop, startTop + delta));
        const distance = targetTop - startTop;

        if (Math.abs(distance) < 2) return;

        if (instant) {
            transcriptColumn.scrollTop = targetTop;
            return;
        }

        const duration = Math.min(480, Math.max(200, Math.abs(distance) * 0.55));
        const startTime = performance.now();
        const easeOutCubic = t => 1 - Math.pow(1 - t, 3);

        const step = now => {
            const progress = Math.min(1, (now - startTime) / duration);
            transcriptColumn.scrollTop = startTop + distance * easeOutCubic(progress);
            if (progress < 1) scrollAnimFrame = requestAnimationFrame(step);
            else scrollAnimFrame = null;
        };
        scrollAnimFrame = requestAnimationFrame(step);
    }

    transcriptColumn?.addEventListener('wheel', cancelTranscriptScroll, { passive: true });
    transcriptColumn?.addEventListener('touchmove', cancelTranscriptScroll, { passive: true });
    transcriptColumn?.addEventListener('mousedown', cancelTranscriptScroll);

    function formatTime(sec) {
        const s = Math.floor(sec || 0);
        const m = Math.floor(s / 60);
        const r = s % 60;
        return `${m}:${String(r).padStart(2, '0')}`;
    }

    function updateLessonMeta() {
        if (transcriptCount) transcriptCount.textContent = `${subtitles.length} câu`;
        const updateDuration = () => {
            if (!lessonDurationBadge || !video.duration) return;
            const mins = Math.max(1, Math.round(video.duration / 60));
            lessonDurationBadge.innerHTML = `<i class="fa-regular fa-clock"></i> ${mins} phút`;
        };
        if (video.duration) updateDuration();
        else video.addEventListener('loadedmetadata', updateDuration, { once: true });
    }

    function extractCategory(meta) {
        const cats = ['Podcast','Anime','Drama','News','Speech','Daily','Story','JLPT'];
        const tags = (meta.tags || []).map(t => t.name || t);
        return tags.find(t => cats.includes(t)) || 'Daily';
    }

    function updateFavoriteUI() {
        if (!favoriteBtn) return;
        favoriteBtn.classList.toggle('active', isFavorited);
        const icon = favoriteBtn.querySelector('i');
        if (icon) icon.className = isFavorited ? 'fa-solid fa-star' : 'fa-regular fa-star';
    }

    function updateRatingUI(data) {
        if (!ratingSummary) return;
        ratingSummary.innerHTML = '';
        const averageStars = renderAvgStars(data.avgRating, data.ratingCount);
        if (!(Number(data.avgRating) > 0)) {
            averageStars.querySelector('.avg-star-label')?.remove();
        }
        ratingSummary.appendChild(averageStars);
    }

    function renderCommentStars() {
        if (!commentStarsEl) return;
        commentStarsEl.innerHTML = '';
        commentStarsEl.appendChild(renderStars(commentRating, true, r => {
            commentRating = commentRating === r ? 0 : r;
            renderCommentStars();
        }));
    }

    function formatCommentDate(dt) {
        if (!dt) return '';
        const d = new Date(dt);
        return d.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    }

    function renderComments(comments) {
        if (!commentList) return;
        commentList.innerHTML = '';
        if (commentCountEl) commentCountEl.textContent = `(${comments.length})`;
        if (!comments.length) {
            commentList.innerHTML = '<p class="caption">Chưa có bình luận. Hãy là người đầu tiên!</p>';
            return;
        }
        comments.forEach(c => {
            const item = document.createElement('div');
            item.className = 'comment-item';
            item.innerHTML = `
                <div class="comment-item-header">
                    <strong>${escHtml(c.displayName || 'Người dùng')}</strong>
                    <span class="caption">${formatCommentDate(c.createdAt)}</span>
                    ${c.own ? '<button class="text-link comment-delete-btn" style="color:var(--red);font-size:13px;">Xóa</button>' : ''}
                </div>
                <p class="comment-content">${escHtml(c.content)}</p>
            `;
            if (c.rating) {
                const ratingWrap = document.createElement('div');
                ratingWrap.className = 'comment-rating';
                ratingWrap.appendChild(renderStars(c.rating, false));
                item.insertBefore(ratingWrap, item.querySelector('.comment-content'));
            }
            item.querySelector('.comment-delete-btn')?.addEventListener('click', async () => {
                await fetch(`/api/comments/${c.id}?clientId=${clientId}`, { method: 'DELETE' });
                loadComments();
            });
            commentList.appendChild(item);
        });
    }

    function escHtml(s) {
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }

    async function loadComments() {
        const res = await fetch(`/api/videos/${videoId}/comments?clientId=${clientId}`);
        if (res.ok) renderComments(await res.json());
    }

    async function loadInteractions() {
        const [favRes, ratingRes] = await Promise.all([
            fetch(`/api/videos/${videoId}/favorite?clientId=${clientId}`),
            fetch(`/api/videos/${videoId}/rating?clientId=${clientId}`)
        ]);
        if (favRes.ok) isFavorited = (await favRes.json()).favorited;
        updateFavoriteUI();
        if (ratingRes.ok) updateRatingUI(await ratingRes.json());
        loadComments();
    }

    function detectAudioOnly(meta) {
        if (!meta) return false;
        if (meta.audioOnly === true) return true;
        const path = (meta.videoPath || '').toLowerCase().replace(/\\/g, '/');
        return /\.(mp3|wav|m4a|ogg|flac|aac|opus)(\?|$)/.test(path);
    }

    function mountMediaEngine(audioOnly) {
        const surface = document.getElementById('video-surface');
        const shell = document.getElementById('player-shell');
        if (!video) return;

        if (audioOnly) {
            // Không gắn vào khung video — engine ẩn ngoài layout, chỉ phát tiếng
            if (video.parentElement !== document.body) {
                document.body.prepend(video);
            }
            video.classList.add('media-engine', 'media-engine--audio');
            video.classList.remove('media-engine--video');
            video.removeAttribute('controls');
            if (surface) surface.innerHTML = '';
            if (shell) shell.classList.add('audio-shell');
        } else {
            video.classList.add('media-engine', 'media-engine--video');
            video.classList.remove('media-engine--audio');
            if (surface && video.parentElement !== surface) {
                surface.appendChild(video);
            }
            if (shell) shell.classList.remove('audio-shell');
        }
    }

    function applyAudioMode(meta) {
        isAudioOnly = detectAudioOnly(meta);
        document.body.classList.toggle('audio-mode', isAudioOnly);
        playerContainer.classList.toggle('audio-mode', isAudioOnly);
        mountMediaEngine(isAudioOnly);

        // Audio: toolbar ngang dưới lyrics (kiểu music player)
        const controls = document.getElementById('player-controls');
        if (controls) {
            controls.classList.toggle('floating-toolbar', !isAudioOnly);
            controls.classList.toggle('audio-control-bar', isAudioOnly);
        }
    }

    function setupPlayerControlsToggle() {
        if (!playerControls || !playerControlsToggle || isAudioOnly) return;
        const storageKey = 'jlas-shadowing-player-controls-collapsed';

        const setCollapsed = collapsed => {
            playerControls.classList.toggle('is-collapsed', collapsed);
            playerControlsSlot?.classList.toggle('is-collapsed', collapsed);
            playerControlsToggle.setAttribute('aria-expanded', String(!collapsed));
            playerControlsToggle.setAttribute('aria-label', collapsed ? 'Mở công cụ phát' : 'Thu gọn công cụ');
            playerControlsToggle.title = collapsed ? 'Mở công cụ' : 'Thu gọn công cụ';
            const icon = playerControlsToggle.querySelector('i');
            if (icon) icon.className = collapsed
                ? 'fa-solid fa-chevron-left'
                : 'fa-solid fa-chevron-right';
        };

        setCollapsed(localStorage.getItem(storageKey) === '1');
        playerControlsToggle.addEventListener('click', event => {
            event.stopPropagation();
            const collapsed = !playerControls.classList.contains('is-collapsed');
            setCollapsed(collapsed);
            localStorage.setItem(storageKey, collapsed ? '1' : '0');
        });
    }

    async function initPlayer() {
        try {
            const [subResponse, videoResponse] = await Promise.all([
                fetch(`/api/videos/${videoId}/subtitles`, { headers: headers() }),
                fetch(`/api/videos/${videoId}`, { headers: headers() })
            ]);
            subtitles = subResponse.ok ? await subResponse.json() : [];
            if (videoResponse.ok) {
                videoMeta = await videoResponse.json();
                if (lessonTitle && videoMeta.title) lessonTitle.textContent = `Tiêu đề: ${videoMeta.title}`;
                const cat = extractCategory(videoMeta);
                if (lessonCategoryBadge) lessonCategoryBadge.innerHTML = `<i class="fa-solid fa-folder"></i> ${getCategoryLabel(cat)}`;
                if (lessonViewsBadge) lessonViewsBadge.innerHTML = `<i class="fa-solid fa-eye"></i> ${videoMeta.viewCount || 0} lượt xem`;
                applyAudioMode(videoMeta);
                setupPlayerControlsToggle();
            } else {
                mountMediaEngine(false);
                if (videoResponse.status === 401 || videoResponse.status === 403) {
                    const err = await videoResponse.json().catch(() => ({}));
                    const msg = err.message || err.error || 'Video riêng tư hoặc chưa xuất bản. Đăng nhập bằng tài khoản chủ sở hữu để xem.';
                    if (typeof showAlert === 'function') {
                        await showAlert({ title: 'Không xem được video', message: msg, variant: 'error' });
                    } else {
                        console.warn(msg);
                    }
                }
            }
            video.src = streamUrl();
            renderSubtitles();
            if (typeof window.loadPronunciationAttempts === 'function') {
                window.loadPronunciationAttempts(videoId);
            }
            updateLessonMeta();
            updatePlayIcon();
            loadInteractions();
            if (subtitles.length) {
                currentActiveIndex = 0;
                updateActiveSubtitle(0);
            }
        } catch (e) { console.error('Error loading player:', e); }
    }

    function bindSubtitleActions(div, sub, index) {
        div.querySelector('[data-action="replay"]')?.addEventListener('click', e => {
            e.stopPropagation();
            video.currentTime = sub.startTime;
            video.play();
        });
        div.querySelector('[data-action="favorite"]')?.addEventListener('click', async e => {
            e.stopPropagation();
            const res = await fetch(`/api/videos/${videoId}/favorite?clientId=${clientId}`, { method: 'POST' });
            const data = await res.json();
            isFavorited = data.favorited;
            updateFavoriteUI();
            e.currentTarget.classList.toggle('favorited', isFavorited);
            e.currentTarget.querySelector('i').className = isFavorited ? 'fa-solid fa-star' : 'fa-regular fa-star';
        });
    }

    function renderSubtitles() {
        subtitleList.innerHTML = '';
        subtitles.forEach((sub, index) => {
            const div = document.createElement('div');
            div.className = 'subtitle-item';
            div.id = `sub-${index}`;
            div.dataset.subtitleId = sub.id;
            div.dataset.subtitleIndex = index;
            div.innerHTML = `
                <div class="sub-card-header">
                    <span class="sub-num">#${index + 1}</span>
                    <span class="sub-time">${formatTime(sub.startTime)}</span>
                </div>
                <div class="sub-jp">${formatFurigana(sub.furigana || sub.text)}</div>
                <div class="sub-tr">${sub.translation || ''}</div>
                <div class="sub-card-actions">
                    <button class="sub-action-btn" data-action="replay" title="Phát lại"><i class="fa-solid fa-play"></i></button>
                    <button class="sub-action-btn" data-action="favorite" title="Yêu thích"><i class="fa-regular fa-star"></i></button>
                    <button class="sub-action-btn sub-pron-record-btn" data-action="record" title="Thu câu này" aria-label="Thu câu này"><i class="fa-solid fa-microphone"></i></button>
                    <button class="sub-pron-score-badge" data-action="score" title="Chấm câu này" disabled>--</button>
                </div>
                <div class="sub-pronunciation-panel" data-pronunciation-panel hidden>
                    <span class="sub-pronunciation-status" data-record-status>Sẵn sàng</span>
                    <div class="sub-pronunciation-playback" data-pronunciation-playback hidden>
                        <button class="sub-pronunciation-play-btn" data-action="play-recording" title="Phát lại bản thu" aria-label="Phát lại bản thu"><i class="fa-solid fa-play"></i></button>
                        <input class="sub-pronunciation-seek" data-action="recording-seek" type="range" min="0" max="100" value="0" step="0.1" aria-label="Tiến độ bản thu">
                        <span class="sub-pronunciation-time" data-recording-time>0:00 / 0:00</span>
                        <audio data-pronunciation-audio preload="metadata"></audio>
                    </div>
                    <div class="sub-pronunciation-result" data-pronunciation-result hidden>
                        <span class="sub-pronunciation-score-label">Điểm câu</span>
                        <strong data-pronunciation-score>--</strong>
                        <button class="sub-pronunciation-details-btn" data-action="score-details" type="button">Xem lỗi</button>
                    </div>
                    <div class="sub-pronunciation-details" data-pronunciation-details hidden>
                        <p data-pronunciation-reason></p>
                        <div class="sub-pronunciation-metrics" data-pronunciation-metrics aria-label="Các thành phần điểm">
                            <span><small>Chính xác</small><strong data-pronunciation-metric="accuracy">--</strong></span>
                            <span><small>Trôi chảy</small><strong data-pronunciation-metric="fluency">--</strong></span>
                            <span><small>Đầy đủ</small><strong data-pronunciation-metric="completeness">--</strong></span>
                        </div>
                        <div class="sub-pronunciation-word-scores" data-pronunciation-word-scores role="list" aria-label="Điểm theo từng từ"></div>
                        <section class="sub-pronunciation-focus" data-pronunciation-focus hidden aria-live="polite">
                            <div class="sub-pronunciation-focus-heading">
                                <div>
                                    <small>Từ đang xem</small>
                                    <strong data-pronunciation-focus-word>--</strong>
                                </div>
                                <span data-pronunciation-focus-score>--</span>
                            </div>
                            <div class="sub-pronunciation-mora-track" data-pronunciation-mora-track aria-label="Điểm ước tính theo mora"></div>
                            <p class="sub-pronunciation-confidence" data-pronunciation-confidence></p>
                            <div class="sub-pronunciation-tip">
                                <i class="fa-solid fa-paw" aria-hidden="true"></i>
                                <p data-pronunciation-tip></p>
                            </div>
                            <div class="sub-pronunciation-compare-actions">
                                <button type="button" data-action="play-reference" title="Nghe lại cả câu mẫu">
                                    <i class="fa-solid fa-volume-high"></i><span>Nghe câu mẫu</span>
                                </button>
                                <button type="button" data-action="play-focus-recording" title="Nghe bản thu của bạn">
                                    <i class="fa-solid fa-headphones"></i><span>Nghe bản thân</span>
                                </button>
                                <button type="button" data-action="record" title="Thu lại câu này">
                                    <i class="fa-solid fa-microphone"></i><span>Thu lại</span>
                                </button>
                            </div>
                        </section>
                    </div>
                    <div class="sub-pronunciation-history" data-pronunciation-history hidden></div>
                </div>
            `;
            div.addEventListener('click', event => {
                if (event.target.closest('[data-action]')) return;
                selectPronunciationSubtitle(index);
                video.currentTime = sub.startTime;
                video.play();
            });
            bindSubtitleActions(div, sub, index);
            makeWordsClickable(div.querySelector('.sub-jp'));
            subtitleList.appendChild(div);
        });
        window.dispatchEvent(new CustomEvent('shadowing:subtitles-rendered'));
        if (transcriptColumn) transcriptColumn.scrollTop = 0;
    }

    function formatFurigana(text) {
        return text.replace(/([^\x00-\x7F\s、。！？]+)[\(（]([^）\)]+)[\)）]/g, '<ruby>$1<rt>$2</rt></ruby>');
    }

    function makeWordsClickable(el) {
        if (!el) return;
        el.querySelectorAll('ruby').forEach(ruby => {
            ruby.style.cursor = 'pointer';
            ruby.addEventListener('click', e => {
                e.stopPropagation();
                lookupWord(ruby.childNodes[0].textContent);
            });
        });
    }

    async function lookupWord(word) {
        document.querySelectorAll('.dict-popup, .dict-overlay').forEach(e => e.remove());
        const overlay = document.createElement('div');
        overlay.className = 'dict-overlay';
        overlay.onclick = () => { overlay.remove(); popup.remove(); };
        document.body.appendChild(overlay);

        const popup = document.createElement('div');
        popup.className = 'dict-popup';
        popup.style.top = '50%';
        popup.style.left = '50%';
        popup.style.transform = 'translate(-50%, -50%)';
        popup.innerHTML = '<p>Đang tra...</p>';
        document.body.appendChild(popup);

        try {
            const res = await fetch(`/api/dictionary?q=${encodeURIComponent(word)}`);
            const data = await res.json();
            let html = `<h4>${word}</h4>`;
            if (data.results?.length) {
                data.results.forEach(r => {
                    html += `<div class="reading">${r.reading || r.word}</div><ul>`;
                    (r.meanings || []).slice(0, 3).forEach(m => html += `<li>${m}</li>`);
                    html += '</ul>';
                });
            } else {
                html += '<p class="caption">Không tìm thấy.</p>';
            }
            popup.innerHTML = html;
        } catch {
            popup.innerHTML = '<p>Lỗi tra từ.</p>';
        }
    }

    furiganaToggleBtn.addEventListener('click', () => {
        isFuriganaVisible = !isFuriganaVisible;
        furiganaToggleBtn.classList.toggle('active', isFuriganaVisible);
        playerContainer.classList.toggle('hide-furigana', !isFuriganaVisible);
        playerContainer.classList.toggle('show-furigana', isFuriganaVisible);
        fitSubtitleViewer();
    });

    translationToggleBtn.addEventListener('click', () => {
        isTranslationVisible = !isTranslationVisible;
        translationToggleBtn.classList.toggle('active', isTranslationVisible);
        playerContainer.classList.toggle('hide-translation', !isTranslationVisible);
        fitSubtitleViewer();
    });

    playBtn.addEventListener('click', () => { if (video.paused) video.play(); else video.pause(); });
    function updatePlayIcon() {
        if (!playIcon) return;
        playIcon.className = video.paused ? 'fa-solid fa-play' : 'fa-solid fa-pause';
    }

    function updateTimeline() {
        if (!video.duration) return;
        const pct = (video.currentTime / video.duration) * 100;
        if (timelineFill) timelineFill.style.width = `${pct}%`;
        if (timelineTime) {
            timelineTime.textContent = `${formatTime(video.currentTime)} / ${formatTime(video.duration)}`;
        }
    }

    timelineTrack?.addEventListener('click', e => {
        const rect = timelineTrack.getBoundingClientRect();
        const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
        if (video.duration) video.currentTime = ratio * video.duration;
    });
    video.addEventListener('play', updatePlayIcon);
    video.addEventListener('pause', updatePlayIcon);

    replayBtn.addEventListener('click', () => {
        if (currentActiveIndex !== -1) {
            video.currentTime = subtitles[currentActiveIndex].startTime;
            video.play();
        }
    });
    prevBtn.addEventListener('click', () => {
        if (currentActiveIndex > 0) {
            video.currentTime = subtitles[currentActiveIndex - 1].startTime;
            video.play();
        }
    });
    nextBtn.addEventListener('click', () => {
        if (currentActiveIndex < subtitles.length - 1) {
            video.currentTime = subtitles[currentActiveIndex + 1].startTime;
            video.play();
        }
    });

    loopBtn.addEventListener('click', () => {
        isLoopEnabled = !isLoopEnabled;
        loopBtn.classList.toggle('active', isLoopEnabled);
        currentLoopCount = 0;
    });

    loopCountSelect?.addEventListener('change', () => {
        loopCountMax = parseInt(loopCountSelect.value, 10);
        currentLoopCount = 0;
    });

    function closeSpeedMenu() {
        if (!speedMenu) return;
        speedMenu.hidden = true;
        speedToggleBtn?.classList.remove('open');
        speedToggleBtn?.setAttribute('aria-expanded', 'false');
    }

    speedToggleBtn?.addEventListener('click', e => {
        e.stopPropagation();
        if (!speedMenu) return;
        const willOpen = speedMenu.hidden;
        speedMenu.hidden = !willOpen;
        speedToggleBtn.classList.toggle('open', willOpen);
        speedToggleBtn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    });

    speedBtns.forEach(btn => {
        btn.addEventListener('click', e => {
            e.stopPropagation();
            speedBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            video.playbackRate = parseFloat(btn.dataset.speed);
            if (speedToggleLabel) speedToggleLabel.textContent = btn.textContent.trim();
            closeSpeedMenu();
        });
    });

    document.addEventListener('click', e => {
        if (speedToggleBtn?.contains(e.target) || speedMenu?.contains(e.target)) return;
        closeSpeedMenu();
    });

    favoriteBtn?.addEventListener('click', async () => {
        const res = await fetch(`/api/videos/${videoId}/favorite?clientId=${clientId}`, { method: 'POST' });
        const data = await res.json();
        isFavorited = data.favorited;
        updateFavoriteUI();
    });

    shareBtn?.addEventListener('click', async () => {
        const result = await shareVideo(videoId, videoMeta?.title || lessonTitle?.textContent);
        if (result === 'copied') showToast('Đã sao chép link chia sẻ!');
    });

    commentSubmitBtn?.addEventListener('click', async () => {
        const content = commentInput?.value?.trim();
        if (!content) return;
        const payload = {
            clientId,
            content
        };
        if (commentRating > 0) payload.rating = commentRating;
        const res = await fetch(`/api/videos/${videoId}/comments`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!res.ok) {
            showAlert({ title: 'Lỗi', message: 'Không thể gửi bình luận. Thử lại sau.', variant: 'error' });
            return;
        }
        if (commentInput) commentInput.value = '';
        commentRating = 0;
        renderCommentStars();
        if (payload.rating) {
            const ratingRes = await fetch(`/api/videos/${videoId}/rating?clientId=${clientId}`);
            if (ratingRes.ok) {
                const ratingData = await ratingRes.json();
                updateRatingUI(ratingData);
                if (videoMeta) {
                    videoMeta.avgRating = ratingData.avgRating;
                    videoMeta.ratingCount = ratingData.ratingCount;
                }
            }
        }
        loadComments();
    });

    renderCommentStars();

    video.addEventListener('timeupdate', () => {
        updateTimeline();
        const currentTime = video.currentTime;
        let newIndex = -1;
        for (let i = 0; i < subtitles.length; i++) {
            if (currentTime >= subtitles[i].startTime && currentTime <= subtitles[i].endTime) {
                newIndex = i; break;
            }
        }
        if (newIndex !== currentActiveIndex) {
            currentActiveIndex = newIndex;
            updateActiveSubtitle(newIndex);
            currentLoopCount = 0;
        }

        const pronunciationRecording = typeof window.isShadowingPronunciationRecording === 'function'
            && window.isShadowingPronunciationRecording();
        if (isLoopEnabled && currentActiveIndex >= 0 && !pronunciationRecording) {
            const sub = subtitles[currentActiveIndex];
            if (currentTime >= sub.endTime - 0.05) {
                if (loopCountMax > 0) {
                    currentLoopCount++;
                    if (currentLoopCount >= loopCountMax) {
                        currentLoopCount = 0;
                        isLoopEnabled = false;
                        loopBtn.classList.remove('active');
                        if (currentActiveIndex < subtitles.length - 1) {
                            video.currentTime = subtitles[currentActiveIndex + 1].startTime;
                        }
                        return;
                    }
                }
                video.currentTime = sub.startTime;
            }
        }
    });

    function updateActiveSubtitle(index) {
        document.querySelectorAll('.subtitle-item').forEach(item => {
            item.classList.remove('active');
            item.classList.remove('error');
        });
        if (index !== -1) {
            selectPronunciationSubtitle(index, 'playback');
            const item = document.getElementById(`sub-${index}`);
            if (item) {
                item.classList.add('active');
                requestAnimationFrame(() => scrollActiveToAnchor(false, index));
            }
            updateViewer(index);
        } else {
            viewerJapanese.innerHTML = '';
            viewerTranslation.innerHTML = '';
        }
    }

    function fitSubtitleViewer() {
        const viewer = document.getElementById('large-subtitle-viewer');
        if (!viewer || !viewerJapanese) return;

        viewerJapanese.style.fontSize = '';
        viewerTranslation.style.fontSize = '';

        requestAnimationFrame(() => {
            const padX = 40;
            const maxW = Math.max(160, viewer.clientWidth - padX);
            const maxTotalH = Math.max(120, viewer.clientHeight - 28);
            const blocks = [
                { el: viewerJapanese, min: 14 },
                { el: viewerTranslation, min: 14 }
            ];

            blocks.forEach(({ el, min }) => {
                if (!el?.textContent?.trim()) return;
                let size = parseFloat(getComputedStyle(el).fontSize);
                el.style.fontSize = size + 'px';
                let guard = 0;
                while (el.scrollWidth > maxW && size > min && guard++ < 80) {
                    size -= 1;
                    el.style.fontSize = size + 'px';
                }
            });

            const gap = viewerTranslation.textContent?.trim() ? 16 : 0;
            const totalH = viewerJapanese.offsetHeight + viewerTranslation.offsetHeight + gap;
            if (totalH > maxTotalH) {
                const ratio = maxTotalH / totalH;
                blocks.forEach(({ el, min }) => {
                    if (!el?.textContent?.trim()) return;
                    const cur = parseFloat(el.style.fontSize || getComputedStyle(el).fontSize);
                    el.style.fontSize = Math.max(min, Math.floor(cur * ratio)) + 'px';
                });
            }
        });
    }

    function updateViewer(index) {
        const sub = subtitles[index];
        viewerJapanese.classList.add('viewer-fade');
        viewerTranslation.classList.add('viewer-fade');
        requestAnimationFrame(() => {
            viewerJapanese.innerHTML = formatFurigana(sub.furigana || sub.text);
            viewerTranslation.innerHTML = sub.translation || '';
            makeWordsClickable(viewerJapanese);
            viewerJapanese.classList.remove('viewer-fade');
            viewerTranslation.classList.remove('viewer-fade');
            fitSubtitleViewer();
        });
    }

    let fitViewerTimer;
    window.addEventListener('resize', () => {
        clearTimeout(fitViewerTimer);
        fitViewerTimer = setTimeout(fitSubtitleViewer, 120);
    });

    window.attachWatchTracker(video, videoId, data => {
        if (lessonViewsBadge) lessonViewsBadge.innerHTML = `<i class="fa-solid fa-eye"></i> ${data.viewCount} lượt xem`;
    });

    initPlayer();
});
