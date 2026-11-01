document.addEventListener('DOMContentLoaded', () => {
    const videoGrid = document.getElementById('video-grid');
    const searchInput = document.getElementById('search-input');
    const sortSelect = document.getElementById('sort-select');
    const categoryFilter = document.getElementById('category-filter');
    const genreFilter = document.getElementById('genre-filter');
    const levelFilter = document.getElementById('level-filter');
    const scopeBtns = document.querySelectorAll('[data-scope]');
    const statusTabs = document.getElementById('status-tabs');
    const statusBtns = document.querySelectorAll('[data-status]');
    const videoEmptyState = document.getElementById('video-empty-state');
    const videoEmptyTitle = videoEmptyState?.querySelector('.kitsu-guide__title');
    const videoEmptyMessage = videoEmptyState?.querySelector('.kitsu-guide__message');
    const videoEmptyAction = document.getElementById('video-empty-action');

    const clientId = getClientId();

    let currentScope = 'public'; // public | my | favorites | history
    const historyClear = document.getElementById('history-clear');
    let currentStatus = '';     // '' | READY | DRAFT | PROCESSING | FAILED
    let allVideos = [];
    let searchTimer = null;
    let loadVersion = 0;
    let currentPage = 1;
    const pager = window.ShadowingLibraryUi.createPager(document.getElementById('video-pagination'), page => loadVideos(page));

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

    function isLoggedIn() {
        return !!(window.JlasAuth && JlasAuth.isLoggedIn && JlasAuth.isLoggedIn())
            || !!localStorage.getItem('accessToken');
    }

    scopeBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const scope = btn.dataset.scope;
            if ((scope === 'my' || scope === 'history') && !isLoggedIn()) {
                showToast(scope === 'my' ? 'Đăng nhập để xem video của bạn' : 'Đăng nhập để xem lịch sử');
                setTimeout(() => { location.href = '/login'; }, 500);
                return;
            }
            scopeBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentScope = scope;
            statusTabs.hidden = scope !== 'my';
            if (scope !== 'my') currentStatus = '';
            loadVideos();
        });
    });

    const initial = new URLSearchParams(window.location?.search || '');
    const initialScope = initial.get('scope');
    if (((initialScope === 'my' || initialScope === 'history') && isLoggedIn()) || initialScope === 'favorites') {
        currentScope = initialScope;
        scopeBtns.forEach(b => b.classList.toggle('active', b.dataset.scope === initialScope));
        statusTabs.hidden = initialScope !== 'my';
        const initialStatus = initial.get('status') || '';
        if (initialScope === 'my' && Array.from(statusBtns).some(b => (b.dataset.status || '') === initialStatus)) {
            currentStatus = initialStatus;
            statusBtns.forEach(b => b.classList.toggle('active', (b.dataset.status || '') === initialStatus));
        }
    }
    const initialPage = Math.max(1, Math.floor(Number(initial.get('page'))) || 1);

    statusBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            statusBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentStatus = btn.dataset.status || '';
            loadVideos();
        });
    });

    searchInput?.addEventListener('input', () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(reloadVisibleVideos, 300);
    });

    function reloadVisibleVideos() {
        if (document.getElementById('series-grid')?.hidden !== false) loadVideos();
    }
    sortSelect?.addEventListener('change', reloadVisibleVideos);
    categoryFilter?.addEventListener('change', reloadVisibleVideos);
    genreFilter?.addEventListener('change', reloadVisibleVideos);
    levelFilter?.addEventListener('change', reloadVisibleVideos);

    /** "Hôm nay" / "Hôm qua" / ngày cụ thể của một lần ghé. */
    function historyDay(value) {
        const start = new Date(value);
        start.setHours(0, 0, 0, 0);
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const diff = Math.round((today - start) / 86400000);
        if (diff === 0) return 'Hôm nay';
        if (diff === 1) return 'Hôm qua';
        return start.toLocaleDateString('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
    }

    historyClear?.addEventListener('click', async () => {
        const confirmed = await showConfirm({
            title: 'Xoá toàn bộ lịch sử?',
            message: 'Danh sách video bạn đã mở sẽ bị xoá. Tiến độ luyện tập vẫn được giữ.',
            confirmText: 'Xoá',
            cancelText: 'Hủy',
            variant: 'danger'
        });
        if (!confirmed) return;
        const res = await fetch('/api/videos/history', { method: 'DELETE', headers: authHeaders() });
        if (res.ok) loadVideos(1);
        else showToast('Không xoá được lịch sử');
    });

    async function loadVideos(page = 1) {
        const version = ++loadVersion;
        const q = (searchInput?.value || '').trim();
        const sort = sortSelect?.value || 'popular';
        const category = categoryFilter?.value || '';
        const genre = genreFilter?.value || '';
        const params = new URLSearchParams({ sort, page: String(page), size: '12' });
        if (currentScope === 'my' && currentStatus) params.set('status', currentStatus);
        if (q) params.set('q', q);
        if (category) params.set('category', category);
        if (genre) params.set('genre', genre);
        if (levelFilter?.value) params.set('level', levelFilter.value);

        if (currentScope === 'favorites') {
            params.set('scope', 'public');
            params.set('favoritesOnly', 'true');
        } else if (currentScope === 'my') {
            params.set('scope', 'my');
        } else if (currentScope === 'history') {
            params.set('scope', 'history');
        } else {
            params.set('scope', 'public');
        }

        pager.setBusy(true);
        videoGrid.setAttribute('aria-busy', 'true');
        if (videoEmptyState) videoEmptyState.hidden = true;
        try {
            const res = await fetch(`/api/videos/library?${params}`, { headers: authHeaders() });
            if (!res.ok) throw new Error(res.status === 401 ? 'Vui lòng đăng nhập lại để xem thư viện.' : 'Không tải được thư viện. Hãy thử lại.');
            const body = await res.json();
            const data = body.data;
            if (!body.success || !data || !Array.isArray(data.items)) throw new Error('Không tải được thư viện. Hãy thử lại.');
            if (version !== loadVersion) return;
            allVideos = data.items;
            const pageChanged = currentPage !== data.page;
            currentPage = data.page;
            window.ShadowingLibraryUi.remember?.({
                scope: currentScope === 'public' ? '' : currentScope,
                status: currentScope === 'my' ? currentStatus : '',
                page: currentPage > 1 ? String(currentPage) : ''
            });
            render();
            watchProcessing();
            pager.render(data);
            if (videoGrid.hidden) pager.hide();
            if (pageChanged && !videoGrid.hidden) videoGrid.scrollIntoView({ block: 'start', behavior: 'auto' });
        } catch (e) {
            if (version !== loadVersion) return;
            pager.hide();
            videoGrid.innerHTML = `<p class="lead library-load-error" role="alert">${esc(e.message || 'Không tải được thư viện. Hãy thử lại.')}</p>`;
        } finally {
            if (version === loadVersion) { pager.setBusy(false); videoGrid.setAttribute('aria-busy', 'false'); }
        }
    }

    function navigate(v) {
        if (v.status === 'PROCESSING' || v.status === 'FAILED') window.location.href = `/shadowing/create/${v.id}/processing`;
        else if (v.status === 'DRAFT') window.location.href = `/shadowing/editor/${v.id}`;
        else window.location.href = `/shadowing/practice/${v.id}`;
    }

    async function toggleFavorite(e, v, btn) {
        e.stopPropagation();
        const res = await fetch(`/api/videos/${v.id}/favorite?clientId=${clientId}`, {
            method: 'POST',
            headers: authHeaders()
        });
        const data = await res.json();
        v.favorited = data.favorited;
        btn.classList.toggle('favorited', v.favorited);
        btn.querySelector('i').className = v.favorited ? 'fa-solid fa-star' : 'fa-regular fa-star';
        if (currentScope === 'favorites' && !v.favorited) loadVideos(currentPage);
    }

    async function toggleVisibility(e, v) {
        e.stopPropagation();
        if (!v.ownedByMe) return;
        const next = (v.visibility === 'PUBLIC') ? 'PRIVATE' : 'PUBLIC';
        const res = await fetch(`/api/videos/${v.id}`, {
            method: 'PUT',
            headers: authHeaders(),
            body: JSON.stringify({ visibility: next })
        });
        if (!res.ok) {
            showToast('Không đổi được chế độ hiển thị (cần đăng nhập)');
            return;
        }
        v.visibility = next;
        showToast(next === 'PUBLIC' ? 'Đã đặt công khai' : 'Đã đặt riêng tư');
        loadVideos(currentPage);
    }

    async function shareCard(e, v) {
        e.stopPropagation();
        const result = await shareVideo(v.id, v.title);
        if (result === 'copied') {
            window.showToast('Đã sao chép link!');
        }
    }

    function render() {
        const videos = allVideos;

        videoGrid.innerHTML = '';
        if (historyClear) historyClear.hidden = currentScope !== 'history' || !videos.length;
        if (videoEmptyState) videoEmptyState.hidden = true;
        if (!videos.length) {
            const emptyContent = {
                history: {
                    title: 'Kitsu-chan chưa thấy lịch sử',
                    message: 'Video bạn mở để luyện sẽ hiện ở đây, lần gần nhất ở trên.',
                    action: 'Khám phá video công khai',
                    href: '/shadowing'
                },
                favorites: {
                    title: 'Kitsu-chan chưa thấy video yêu thích',
                    message: 'Khi gặp bài luyện hay, hãy bấm ngôi sao để lưu lại tại đây.',
                    action: 'Khám phá video công khai',
                    href: '/shadowing'
                },
                my: {
                    title: 'Kitsu-chan đang chờ video đầu tiên',
                    message: 'Tải file lên hoặc nhập video YouTube để bắt đầu tạo bài Shadowing.',
                    action: 'Tạo video đầu tiên',
                    href: '/shadowing/upload'
                },
                public: {
                    title: 'Kitsu-chan chưa tìm thấy video công khai',
                    message: 'Bạn có thể trở thành người đầu tiên chia sẻ một bài Shadowing với cộng đồng.',
                    action: 'Tạo video',
                    href: '/shadowing/upload'
                }
            }[currentScope];
            if (videoEmptyTitle) videoEmptyTitle.textContent = emptyContent.title;
            if (videoEmptyMessage) videoEmptyMessage.textContent = emptyContent.message;
            if (videoEmptyAction) {
                videoEmptyAction.textContent = emptyContent.action;
                videoEmptyAction.href = emptyContent.href;
            }
            if (videoEmptyState) videoEmptyState.hidden = videoGrid.hidden;
            return;
        }

        let lastDay = null;
        videos.forEach(v => {
            // Lịch sử: một dòng tiêu đề mỗi ngày ghé, như lịch sử YouTube.
            if (currentScope === 'history' && v.viewedAt) {
                const day = historyDay(v.viewedAt);
                if (day !== lastDay) {
                    const heading = document.createElement('h3');
                    heading.className = 'history-day';
                    heading.textContent = day;
                    videoGrid.appendChild(heading);
                }
                lastDay = day;
            }
            const card = document.createElement('div');
            card.className = 'store-utility-card video-card';

            const thumbSrc = v.thumbnailPath ? `/api/videos/${v.id}/thumbnail` : null;
            const thumbHtml = thumbSrc ? `<img src="${thumbSrc}" alt="">` : '<i class="fa-solid fa-play"></i>';

            const statusBadge = {
                PROCESSING: '<span class="badge badge-processing">Đang xử lý</span>',
                DRAFT: '<span class="badge badge-draft">Bản nháp</span>',
                READY: '<span class="badge badge-ready">Sẵn sàng</span>',
                FAILED: '<span class="badge badge-failed">Đã dừng / lỗi</span>'
            }[v.status] || '';

            const visBadge = v.visibility === 'PUBLIC'
                ? '<span class="badge badge-ready" title="Công khai"><i class="fa-solid fa-globe"></i> Công khai</span>'
                : '<span class="badge badge-draft" title="Riêng tư"><i class="fa-solid fa-lock"></i> Riêng tư</span>';

            const category = getCategoryLabel(v.category || (v.tags && v.tags[0]) || 'Daily');
            const levelBadge = v.estimatedLevel
                ? `<span class="card-level-badge" title="Cấp độ JLPT">${esc(v.estimatedLevel)}</span>`
                : '';
            const canManage = !!v.ownedByMe;

            card.innerHTML = `
                <div class="video-card-thumb">
                    ${thumbHtml}
                    ${levelBadge}
                    <button class="card-fav-btn ${v.favorited ? 'favorited' : ''}" title="Yêu thích">
                        <i class="${v.favorited ? 'fa-solid' : 'fa-regular'} fa-star"></i>
                    </button>
                </div>
                <div class="video-card-title">${esc(v.title)}</div>
                <div class="video-card-meta">
                    ${statusBadge}
                    ${canManage || currentScope === 'public' ? visBadge : ''}
                    <span class="badge badge-category"><i class="fa-solid fa-folder"></i> ${esc(category)}</span>
                    <span class="badge badge-lesson"><i class="fa-solid fa-closed-captioning"></i> ${v.subtitleCount || 0} câu</span>
                </div>
                ${v.genres && v.genres.length ? `<div class="video-card-genres" aria-label="Thể loại">${window.ShadowingGenres.badges(v.genres, 3)}</div>` : ''}
                <div class="video-card-stats">
                    <div class="card-avg-rating" data-avg-rating title="Độ nổi bật (trung bình sao)"></div>
                    <span class="caption" title="Lượt xem: nghe thật ≥ 30 giây, mỗi tài khoản tối đa 1 lượt/24 giờ"><i class="fa-solid fa-eye"></i> ${v.viewCount || 0} lượt xem</span>
                    ${v.commentCount ? `<span class="caption"><i class="fa-solid fa-comment"></i> ${v.commentCount}</span>` : ''}
                </div>
                ${v.status === 'PROCESSING' ? `
                    <div class="card-progress" data-processing-id="${Number(v.id)}">
                        <div class="progress-bar progress-bar-lg"><div class="progress-fill" style="width:${v.progressPercent || 0}%"></div></div>
                        <span class="caption"><i class="fa-solid fa-spinner fa-spin"></i> <span data-processing-text>${esc(processingText(v))}</span></span>
                    </div>` : ''}
                <div class="card-actions">
                    <button class="text-link card-open-btn" title="Mở bài học" aria-label="Mở bài học"><i class="fa-solid fa-play"></i></button>
                    <button class="text-link card-share-btn" title="Chia sẻ" aria-label="Chia sẻ"><i class="fa-solid fa-share-nodes"></i></button>
                    ${currentScope === 'history' ? '<button class="text-link card-history-remove-btn" title="Xoá khỏi lịch sử" aria-label="Xoá khỏi lịch sử"><i class="fa-solid fa-xmark"></i></button>' : ''}
                    <button class="text-link card-vis-btn" title="${v.visibility === 'PUBLIC' ? 'Đặt riêng tư' : 'Đặt công khai'}" aria-label="${v.visibility === 'PUBLIC' ? 'Đặt riêng tư' : 'Đặt công khai'}" ${canManage ? '' : 'style="display:none"'}>
                        <i class="fa-solid fa-eye"></i>
                    </button>
                    <button class="text-link card-edit-btn" title="Sửa" aria-label="Sửa" ${canManage ? '' : 'style="display:none"'}><i class="fa-solid fa-pen"></i></button>
                    <button class="text-link card-delete-btn" title="Xóa" aria-label="Xóa" style="color:var(--red);${canManage ? '' : 'display:none'}"><i class="fa-solid fa-trash"></i></button>
                    ${window.ShadowingLibraryUi.ratingHtml(v.avgRating, v.ratingCount)}
                </div>
            `;

            const avgEl = card.querySelector('[data-avg-rating]');
            if (avgEl) avgEl.appendChild(renderAvgStars(v.avgRating, v.ratingCount));

            card.querySelector('.video-card-thumb').addEventListener('click', () => navigate(v));
            card.querySelector('.card-open-btn').addEventListener('click', e => { e.stopPropagation(); navigate(v); });
            card.querySelector('.card-fav-btn').addEventListener('click', e => toggleFavorite(e, v, e.currentTarget));
            card.querySelector('.card-share-btn').addEventListener('click', e => shareCard(e, v));
            card.querySelector('.card-history-remove-btn')?.addEventListener('click', async e => {
                e.stopPropagation();
                const res = await fetch('/api/videos/history/' + v.id, { method: 'DELETE', headers: authHeaders() });
                if (res.ok) loadVideos(currentPage);
                else showToast('Không xoá được khỏi lịch sử');
            });

            const visBtn = card.querySelector('.card-vis-btn');
            if (visBtn && canManage) {
                visBtn.addEventListener('click', e => toggleVisibility(e, v));
            }

            const editBtn = card.querySelector('.card-edit-btn');
            if (canManage && (v.status === 'DRAFT' || v.status === 'READY')) {
                editBtn.addEventListener('click', e => {
                    e.stopPropagation();
                    window.location.href = `/shadowing/editor/${v.id}`;
                });
            } else if (editBtn) {
                editBtn.style.display = 'none';
            }

            const delBtn = card.querySelector('.card-delete-btn');
            if (delBtn && canManage) {
                delBtn.addEventListener('click', async e => {
                    e.stopPropagation();
                    const confirmed = await showConfirm({
                        title: `Xóa "${v.title}"?`,
                        message: 'Hành động này không thể hoàn tác. Video và toàn bộ phụ đề sẽ bị xóa vĩnh viễn.',
                        confirmText: 'Xóa',
                        cancelText: 'Hủy',
                        variant: 'danger'
                    });
                    if (!confirmed) return;
                    const res = await fetch(`/api/videos/${v.id}`, {
                        method: 'DELETE',
                        headers: authHeaders()
                    });
                    if (!res.ok) {
                        showToast('Không thể xóa video. Thử lại sau.');
                        return;
                    }
                    loadVideos(currentPage);
                });
            }

            videoGrid.appendChild(card);
        });
    }

    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }

    const PROCESSING_POLL_MS = 5000;
    const PROCESSING_STEPS = {
        UPLOADED: 'Đang bắt đầu',
        YOUTUBE_DOWNLOAD: 'Đang hoàn tất',
        SUBTITLE_DOWNLOAD: 'Đang tải phụ đề',
        EXTRACTING_AUDIO: 'Đang tách âm thanh',
        TRANSCRIBING: 'Đang nhận dạng giọng nói',
        AI_PROCESSING: 'AI đang thêm furigana và bản dịch'
    };
    let processingTimer = null;

    function processingText(v) {
        return (PROCESSING_STEPS[v.processingStep] || 'Đang xử lý') + ' · ' + (v.progressPercent || 0) + '%';
    }

    // Videos being processed update in place, so the owner sees progress without reloading the page.
    function watchProcessing() {
        clearTimeout(processingTimer);
        processingTimer = null;
        if (currentScope !== 'my' || !allVideos.some(v => v.status === 'PROCESSING')) return;
        processingTimer = setTimeout(refreshProcessing, PROCESSING_POLL_MS);
    }

    async function refreshProcessing() {
        if (document.hidden) { watchProcessing(); return; }
        const version = loadVersion;
        const finished = [];
        await Promise.all(allVideos.filter(v => v.status === 'PROCESSING').map(async v => {
            try {
                const res = await fetch(`/api/videos/${v.id}/status`, { headers: authHeaders() });
                if (!res.ok || version !== loadVersion) return;
                const s = await res.json();
                v.processingStep = s.processingStep;
                v.progressPercent = s.progressPercent;
                if (s.status !== 'PROCESSING') { finished.push({ video: v, status: s.status }); return; }
                const box = videoGrid.querySelector(`[data-processing-id="${Number(v.id)}"]`);
                if (!box) return;
                box.querySelector('.progress-fill').style.width = (s.progressPercent || 0) + '%';
                box.querySelector('[data-processing-text]').textContent = processingText(v);
            } catch (e) { /* the next tick retries */ }
        }));
        if (version !== loadVersion) return;
        if (finished.length) {
            finished.forEach(f => showToast(f.status === 'FAILED'
                ? `「${f.video.title}」 bị dừng khi tạo phụ đề`
                : `「${f.video.title}」 đã tạo xong phụ đề`, 5000));
            loadVideos(currentPage);
            return;
        }
        watchProcessing();
    }

    loadVideos(initialPage);
});
