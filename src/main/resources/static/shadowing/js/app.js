document.addEventListener('DOMContentLoaded', () => {
    const videoIdInput = document.getElementById('video-id');
    if (!videoIdInput) return;

    const videoId = videoIdInput.value;
    // Lịch sử: mở trình phát một video là một lần ghé (bỏ qua khi chưa đăng nhập hay lỗi mạng).
    fetch('/api/videos/' + encodeURIComponent(videoId) + '/visit', { method: 'POST', headers: headers() }).catch(() => {});
    const playerContainer = document.getElementById('player-container');
    const video = document.getElementById('main-video');
    const subtitleList = document.getElementById('subtitle-list');
    const transcriptColumn = document.querySelector('.transcript-column');

    const prevBtn = document.getElementById('prev-btn');
    const replayBtn = document.getElementById('replay-btn');
    const playBtn = document.getElementById('play-btn');
    const nextBtn = document.getElementById('next-btn');
    const playIcon = document.getElementById('play-icon');
    const translationToggleBtn = document.getElementById('translation-toggle-btn');
    const furiganaToggleBtn = document.getElementById('furigana-toggle-btn');
    const viewerJapanese = document.getElementById('viewer-japanese');
    const viewerTranslation = document.getElementById('viewer-translation');
    const speedToggleBtn = document.getElementById('speed-toggle-btn');
    const speedMenu = document.getElementById('speed-menu');
    const speedToggleLabel = document.getElementById('speed-toggle-label');
    const speedBtns = document.querySelectorAll('.speed-menu .speed-btn');
    const lessonTitle = document.getElementById('lesson-title');
    const lessonDurationBadge = document.getElementById('lesson-duration-badge');
    const lessonCategoryBadge = document.getElementById('lesson-category-badge');
    const lessonViewsBadge = document.getElementById('lesson-views-badge');
    const lessonAgeBadge = document.getElementById('lesson-age-badge');
    const ageRatingSelect = document.getElementById('age-rating-select');
    const ageRatingStatus = document.getElementById('age-rating-status');
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

    /** Age badge for everyone; the picker exists only for the owner and moderators (canRateAge). */
    function applyAgeRating(minAge) {
        const age = Number(minAge) || 0;
        if (lessonAgeBadge) {
            lessonAgeBadge.hidden = !age;
            lessonAgeBadge.textContent = age ? age + '+' : '';
            lessonAgeBadge.title = age ? `Dành cho người từ ${age} tuổi trở lên` : '';
        }
        if (ageRatingSelect) {
            ageRatingSelect.value = String(age);
            ageRatingSelect.dataset.saved = String(age);
        }
    }

    ageRatingSelect?.addEventListener('change', async () => {
        ageRatingSelect.disabled = true;
        ageRatingStatus.textContent = 'Đang lưu…';
        try {
            const res = await fetch(`/api/videos/${videoId}/age-rating`, {
                method: 'PUT',
                headers: headers(),
                body: JSON.stringify({ minAge: Number(ageRatingSelect.value) })
            });
            const body = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(body.message || body.error || 'Chưa lưu được nhãn độ tuổi.');
            applyAgeRating(body.minAge);
            ageRatingStatus.textContent = 'Đã lưu';
        } catch (e) {
            ageRatingSelect.value = ageRatingSelect.dataset.saved || '0';
            ageRatingStatus.textContent = e.message;
        } finally {
            ageRatingSelect.disabled = false;
        }
    });

    let videoMeta = null;
    let isFavorited = false;
    let isAudioOnly = false;

    let subtitles = [];
    let currentActiveIndex = -1;
    let pronunciationSubtitleIndex = -1;
    let listenRecord = null;

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
        listenRecord?.select(context);
        window.dispatchEvent(new CustomEvent('shadowing:pronunciation-context-change', {
            detail: { ...context, source }
        }));
    }
    window.selectShadowingPronunciationSubtitle = selectPronunciationSubtitle;
    let isFuriganaVisible = true;
    let isTranslationVisible = true;
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

    /** Reactions a learner can leave on a comment (same list as CommentThreadRepository.REACTIONS). */
    const COMMENT_REACTIONS = [
        { key: 'LIKE', emoji: '👍', label: 'Thích' },
        { key: 'LOVE', emoji: '❤️', label: 'Yêu thích' },
        { key: 'HAHA', emoji: '😂', label: 'Haha' },
        { key: 'WOW', emoji: '😮', label: 'Wow' },
        { key: 'SAD', emoji: '😢', label: 'Buồn' },
        { key: 'CLAP', emoji: '👏', label: 'Hoan hô' }
    ];
    const reactionOf = key => COMMENT_REACTIONS.find(r => r.key === key);

    function renderComments(comments) {
        if (!commentList) return;
        commentList.innerHTML = '';
        const total = comments.reduce((n, c) => n + 1 + (c.replies || []).length, 0);
        if (commentCountEl) commentCountEl.textContent = `(${total})`;
        if (!comments.length) {
            commentList.innerHTML = '<p class="caption">Chưa có bình luận. Hãy là người đầu tiên!</p>';
            return;
        }
        comments.forEach(c => {
            const thread = document.createElement('div');
            thread.className = 'comment-thread';
            thread.appendChild(commentItem(c, c));
            const replies = document.createElement('div');
            replies.className = 'comment-replies';
            (c.replies || []).forEach(reply => replies.appendChild(commentItem(reply, c)));
            thread.appendChild(replies);
            commentList.appendChild(thread);
        });
        if (window.JlasAvatarFrame) window.JlasAvatarFrame.mountAll(commentList);
    }

    /** One comment (top-level or reply) with its reactions, "Trả lời" and, for the author, "Xóa". */
    function commentItem(c, root) {
        const item = document.createElement('div');
        item.className = 'comment-item' + (c.parentId ? ' comment-item--reply' : '');
        item.dataset.commentId = c.id;
        item.innerHTML = `
            <div class="comment-item-header">
                ${commentAvatar(c)}
                <strong${c.nameStyle && /^[a-z]+$/.test(c.nameStyle) ? ` data-name-style="${c.nameStyle}"` : ''}>${escHtml(c.displayName || 'Người dùng')}</strong>
                <span class="caption">${formatCommentDate(c.createdAt)}</span>
                ${c.own ? '<button class="text-link comment-delete-btn" style="color:var(--red);font-size:13px;">Xóa</button>' : ''}
            </div>
            <p class="comment-content">${escHtml(c.content)}</p>
            <div class="comment-actions">
                <div class="comment-react">
                    <button type="button" class="comment-action comment-react-btn" aria-haspopup="true" aria-expanded="false"></button>
                    <div class="comment-react-picker" role="group" aria-label="Thả cảm xúc" hidden>
                        ${COMMENT_REACTIONS.map(r => `<button type="button" data-reaction="${r.key}" title="${r.label}" aria-label="${r.label}">${r.emoji}</button>`).join('')}
                    </div>
                </div>
                <button type="button" class="comment-action comment-reply-btn">Trả lời</button>
                <span class="comment-reaction-summary"></span>
            </div>
        `;
        if (c.rating && !c.parentId) {
            const ratingWrap = document.createElement('div');
            ratingWrap.className = 'comment-rating';
            ratingWrap.appendChild(renderStars(c.rating, false));
            item.insertBefore(ratingWrap, item.querySelector('.comment-content'));
        }
        paintReactions(item, c.reactions || {}, c.myReaction || null);

        const reactBtn = item.querySelector('.comment-react-btn');
        const picker = item.querySelector('.comment-react-picker');
        const togglePicker = open => {
            picker.hidden = !open;
            reactBtn.setAttribute('aria-expanded', String(open));
        };
        // Click toggles the picker; picking a reaction again removes it (handled by the server).
        reactBtn.addEventListener('click', () => togglePicker(picker.hidden));
        picker.addEventListener('click', async event => {
            const button = event.target.closest('[data-reaction]');
            if (!button) return;
            togglePicker(false);
            const res = await fetch(`/api/comments/${c.id}/reactions`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reaction: button.dataset.reaction })
            });
            if (!res.ok) {
                showAlert({ title: 'Lỗi', message: 'Không thả được cảm xúc. Thử lại sau.', variant: 'error' });
                return;
            }
            const data = await res.json();
            paintReactions(item, data.reactions || {}, data.myReaction || null);
        });
        item.addEventListener('keydown', event => {
            if (event.key === 'Escape' && !picker.hidden) { togglePicker(false); reactBtn.focus(); }
        });

        item.querySelector('.comment-reply-btn').addEventListener('click', () => {
            // A reply to a reply goes into the same thread and starts with @name, like comments under a post.
            openReplyForm(root, c.parentId ? `@${c.displayName || 'Người dùng'} ` : '');
        });
        item.querySelector('.comment-delete-btn')?.addEventListener('click', async () => {
            await fetch(`/api/comments/${c.id}?clientId=${clientId}`, { method: 'DELETE' });
            loadComments();
        });
        return item;
    }

    function paintReactions(item, reactions, mine) {
        const button = item.querySelector('.comment-react-btn');
        const chosen = reactionOf(mine);
        button.textContent = chosen ? `${chosen.emoji} ${chosen.label}` : '👍 Thích';
        button.classList.toggle('is-active', Boolean(chosen));
        item.querySelectorAll('.comment-react-picker [data-reaction]').forEach(b => {
            b.setAttribute('aria-pressed', String(b.dataset.reaction === mine));
        });
        const total = Object.values(reactions).reduce((n, v) => n + Number(v || 0), 0);
        const top = Object.entries(reactions).sort((a, b) => b[1] - a[1]).slice(0, 3)
            .map(([key]) => reactionOf(key)?.emoji || '').join('');
        const summary = item.querySelector('.comment-reaction-summary');
        summary.textContent = total ? `${top} ${total}` : '';
        summary.title = Object.entries(reactions).map(([key, n]) => `${reactionOf(key)?.label || key}: ${n}`).join(' · ');
    }

    /** Inline reply box under a thread; one open at a time. */
    function openReplyForm(root, prefill) {
        commentList.querySelectorAll('.comment-reply-form').forEach(form => form.remove());
        const thread = commentList.querySelector(`.comment-item[data-comment-id="${root.id}"]`)?.closest('.comment-thread');
        if (!thread) return;
        const form = document.createElement('div');
        form.className = 'comment-reply-form';
        form.innerHTML = `
            <textarea class="form-input" rows="2" placeholder="Trả lời ${escHtml(root.displayName || 'bình luận')}..."></textarea>
            <div class="comment-reply-form__actions">
                <button type="button" class="text-link comment-reply-cancel">Hủy</button>
                <button type="button" class="button-primary btn-sm comment-reply-send"><i class="fa-solid fa-paper-plane"></i> Trả lời</button>
            </div>
        `;
        thread.appendChild(form);
        const input = form.querySelector('textarea');
        input.value = prefill;
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
        form.querySelector('.comment-reply-cancel').addEventListener('click', () => form.remove());
        form.querySelector('.comment-reply-send').addEventListener('click', async () => {
            const content = input.value.trim();
            if (!content) return;
            const res = await fetch(`/api/videos/${videoId}/comments`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ clientId, content, parentId: root.id })
            });
            if (!res.ok) {
                showAlert({ title: 'Lỗi', message: 'Không thể gửi trả lời. Thử lại sau.', variant: 'error' });
                return;
            }
            loadComments();
        });
    }

    // Clicking anywhere else closes an open reaction picker.
    document.addEventListener('click', event => {
        if (!commentList || event.target.closest('.comment-react')) return;
        commentList.querySelectorAll('.comment-react-picker:not([hidden])').forEach(picker => {
            picker.hidden = true;
            picker.previousElementSibling?.setAttribute('aria-expanded', 'false');
        });
    });

    /**
     * Ảnh đại diện người bình luận + khung avatar mua ở Cửa hàng. Khung được gắn bởi avatar-frame.js
     * (shell tự nạp; nạp xong cũng tự gắn cho các [data-frame-host] đã có trên trang).
     */
    function commentAvatar(c) {
        const name = String(c.displayName || 'Người dùng').trim();
        const face = c.avatarUrl
            ? `<img src="${escHtml(c.avatarUrl).replace(/"/g, '&quot;')}" alt="" loading="lazy" referrerpolicy="no-referrer">`
            : escHtml(name.charAt(0).toUpperCase() || '?');
        const frame = /^frame-[a-z]+$/.test(c.avatarFrame || '') ? ` data-frame-host="${c.avatarFrame}"` : '';
        return `<span class="af-host comment-avatar-host"${frame}><span class="comment-avatar" aria-hidden="true">${face}</span></span>`;
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
            video.controls = false;
            video.setAttribute('playsinline', '');
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
                if (lessonTitle && videoMeta.title) {
                    const titleText = document.getElementById('lesson-title-text');
                    if (titleText) titleText.textContent = videoMeta.title;
                    else lessonTitle.textContent = `Tiêu đề: ${videoMeta.title}`;
                    lessonTitle.title = videoMeta.title;
                }
                const cat = extractCategory(videoMeta);
                if (lessonCategoryBadge) lessonCategoryBadge.innerHTML = `<i class="fa-solid fa-folder"></i> ${getCategoryLabel(cat)}`;
                if (lessonViewsBadge) lessonViewsBadge.innerHTML = `<i class="fa-solid fa-eye"></i> ${videoMeta.viewCount || 0} lượt xem`;
                applyAgeRating(videoMeta.minAge);
                applyAudioMode(videoMeta);
            } else {
                mountMediaEngine(false);
                if (videoResponse.status === 401 || videoResponse.status === 403) {
                    const err = await videoResponse.json().catch(() => ({}));
                    const msg = err.message || err.error || 'Video riêng tư hoặc chưa xuất bản. Đăng nhập bằng tài khoản chủ sở hữu để xem.';
                    // Age-rated video: age-gate.js shows its own dialog (birth date or age notice) instead.
                    const ageGated = window.ShadowingAgeGate?.isGateCode(err.code);
                    if (!ageGated && typeof showAlert === 'function') {
                        await showAlert({ title: 'Không xem được video', message: msg, variant: 'error' });
                    } else if (!ageGated) {
                        console.warn(msg);
                    }
                }
            }
            // Video YouTube phát bằng trình phát nhúng của YouTube; chỉ video tải lên mới phát từ file trên server.
            await window.ShadowingYoutube.load(video, videoId, { meta: videoMeta, streamUrl: streamUrl() });
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
            playSentence(index);
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
                    <span class="sub-time" title="Câu bắt đầu ở ${formatTime(sub.startTime)} trong video">${formatTime(sub.startTime)}</span>
                </div>
                <div class="sub-jp">${formatFurigana(sub.furigana || sub.text)}</div>
                <div class="sub-tr">${window.ShadowingSubtitleDisplay.escape(sub.translation || '')}</div>
                <div class="sub-card-actions">
                    <button class="sub-action-btn" data-action="replay" title="Phát lại"><i class="fa-solid fa-play"></i></button>
                    <button class="sub-action-btn" data-action="favorite" title="Yêu thích"><i class="fa-regular fa-star"></i></button>
                    <button class="sub-action-btn sub-pron-record-btn" data-action="record" title="Thu câu này" aria-label="Thu câu này"><i class="fa-solid fa-microphone"></i></button>
                    <button class="sub-pron-score-badge" data-action="score" title="Chấm câu này" disabled aria-label="Chưa có điểm phát âm">--</button>
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
                // Highlighting words to look them up (js/inline-dictionary.js) must not jump the video.
                if (!window.getSelection().isCollapsed) return;
                playSentence(index);
            });
            bindSubtitleActions(div, sub, index);
            subtitleList.appendChild(div);
        });
        window.dispatchEvent(new CustomEvent('shadowing:subtitles-rendered'));
        if (transcriptColumn) transcriptColumn.scrollTop = 0;
    }

    function formatFurigana(text) {
        return window.ShadowingSubtitleDisplay.furigana(text);
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

    const modeButton = document.getElementById('listen-record-mode');
    const micButton = document.getElementById('listen-record-mic');
    const modeStatus = document.getElementById('listen-record-status');
    listenRecord = window.ShadowingListenRecord.create({
        video,
        record: (index, options) => window.ShadowingRecorder.record(index, options),
        cancelRecording: () => window.ShadowingRecorder?.cancel(),
        onChange(state) {
            modeButton?.classList.toggle('active', state.enabled);
            modeButton?.setAttribute('aria-pressed', String(state.enabled));
            if (micButton) {
                micButton.disabled = state.busy || !state.context || (state.enabled && state.phase === 'listening');
                micButton.classList.toggle('recording', state.phase === 'recording');
                micButton.setAttribute('aria-busy', String(state.busy));
            }
            if (modeStatus) { modeStatus.hidden = !state.message; modeStatus.textContent = state.message; }
            if (currentActiveIndex === -1) viewerJapanese.textContent = window.ShadowingSubtitleDisplay.gapLabel(video.currentTime, subtitles, state.enabled);
            [prevBtn, nextBtn, replayBtn, playBtn, speedToggleBtn].forEach(button => { if (button) button.disabled = state.busy; });
            const timeline = document.getElementById('timeline-track');
            if (timeline) timeline.disabled = state.busy || !Number.isFinite(video.duration) || video.duration <= 0;
        }
    });
    window.shadowingListenRecord = listenRecord;
    modeButton?.addEventListener('click', () => {
        listenRecord.setEnabled(!listenRecord.isEnabled());
        listenRecord.select(window.getShadowingPronunciationContext());
    });
    micButton?.addEventListener('click', () => listenRecord.record());
    playBtn.addEventListener('click', () => {
        if (listenRecord.busy()) return;
        if (video.paused) video.play()?.catch(() => {}); else video.pause();
    });
    function updatePlayIcon() {
        if (!playIcon) return;
        playIcon.className = video.paused ? 'fa-solid fa-play' : 'fa-solid fa-pause';
    }

    video.addEventListener('play', () => { listenRecord.beforePlay(); updatePlayIcon(); });
    video.addEventListener('seeking', () => {
        if (!listenRecord.isEnabled()) return;
        const index = subtitles.findIndex(sub => video.currentTime >= sub.startTime && video.currentTime < sub.endTime);
        if (index >= 0 && index !== currentActiveIndex) {
            currentActiveIndex = index;
            updateActiveSubtitle(index);
        }
    });
    video.addEventListener('pause', updatePlayIcon);
    video.addEventListener('ended', () => listenRecord.timeUpdate());

    // Explicit timeline seeks release the held sentence, even within the same cue or in a gap.
    window.seekShadowingVideo = function (time) {
        if (listenRecord.busy()) return;
        const index = subtitles.findIndex(sub => time >= sub.startTime && time < sub.endTime);
        currentActiveIndex = index;
        pronunciationSubtitleIndex = -1;
        video.currentTime = time;
        updateActiveSubtitle(index);
        listenRecord.seek(window.getShadowingPronunciationContext());
    };

    function playSentence(index) {
        if (!subtitles[index] || listenRecord.busy()) return;
        currentActiveIndex = index;
        selectPronunciationSubtitle(index, 'user');
        updateActiveSubtitle(index);
        video.currentTime = subtitles[index].startTime;
        video.play()?.catch(() => {});
    }
    replayBtn.addEventListener('click', () => playSentence(currentActiveIndex));
    prevBtn.addEventListener('click', () => playSentence(currentActiveIndex - 1));
    nextBtn.addEventListener('click', () => playSentence(currentActiveIndex + 1));

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
        const held = listenRecord.timeUpdate();
        if (held) return;
        const currentTime = video.currentTime;
        let newIndex = -1;
        for (let i = 0; i < subtitles.length; i++) {
            if (currentTime >= subtitles[i].startTime && currentTime <= subtitles[i].endTime) {
                newIndex = i; break;
            }
        }
        if (newIndex !== currentActiveIndex || newIndex === -1) {
            currentActiveIndex = newIndex;
            updateActiveSubtitle(newIndex);
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
            viewerJapanese.textContent = window.ShadowingSubtitleDisplay.gapLabel(video.currentTime, subtitles, listenRecord.isEnabled());
            viewerJapanese.classList.add('is-gap');
            viewerJapanese.classList.remove('viewer-fade');
            viewerTranslation.classList.remove('viewer-fade');
            viewerJapanese.classList.remove('is-short', 'is-medium', 'is-long', 'is-extra-long');
            viewerJapanese.style.fontSize = '';
            viewerTranslation.textContent = '';
            selectPronunciationSubtitle(subtitles.findIndex(s => s.startTime > video.currentTime), 'playback');
        }
    }

    function fitSubtitleViewer() {
        const viewer = document.getElementById('large-subtitle-viewer');
        if (!viewer || !viewerJapanese) return;

        viewerJapanese.style.fontSize = '';
        viewerTranslation.style.fontSize = '';
        if (window.matchMedia('(max-width: 900px)').matches) return;

        requestAnimationFrame(() => {
            const layout = getComputedStyle(viewer);
            const maxTotalH = Math.max(60, viewer.clientHeight - parseFloat(layout.paddingTop) - parseFloat(layout.paddingBottom));
            const blocks = [
                { el: viewerJapanese, min: 16 },
                { el: viewerTranslation, min: 13 }
            ].filter(({ el }) => el?.textContent?.trim() && getComputedStyle(el).display !== 'none');

            blocks.forEach(({ el, min }) => {
                if (!el?.textContent?.trim()) return;
                let size = parseFloat(getComputedStyle(el).fontSize);
                el.style.fontSize = size + 'px';
                let guard = 0;
                // Both DOM widths use the same pixel rounding; tolerate one pixel.
                while (el.scrollWidth > el.clientWidth + 1 && size > min && guard++ < 80) {
                    size -= 1;
                    el.style.fontSize = size + 'px';
                }
            });

            const totalH = blocks.reduce((height, { el }) => {
                const style = getComputedStyle(el);
                return height + el.offsetHeight + parseFloat(style.marginTop) + parseFloat(style.marginBottom);
            }, 0);
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
        const length = Array.from((sub.text || '').replace(/\s/g, '')).length;
        viewerJapanese.classList.remove('is-gap');
        viewerJapanese.classList.toggle('is-short', length <= 25);
        viewerJapanese.classList.toggle('is-medium', length > 25 && length < 40);
        viewerJapanese.classList.toggle('is-long', length >= 40);
        viewerJapanese.classList.toggle('is-extra-long', length >= 70);
        viewerJapanese.classList.add('viewer-fade');
        viewerTranslation.classList.add('viewer-fade');
        requestAnimationFrame(() => {
            if (currentActiveIndex !== index) return;
            viewerJapanese.innerHTML = formatFurigana(sub.furigana || sub.text);
            viewerTranslation.textContent = sub.translation || '';
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
