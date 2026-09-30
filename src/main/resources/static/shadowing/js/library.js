document.addEventListener('DOMContentLoaded', () => {
    const videoGrid = document.getElementById('video-grid');
    const searchInput = document.getElementById('search-input');
    const sortSelect = document.getElementById('sort-select');
    const categoryFilter = document.getElementById('category-filter');
    const levelFilter = document.getElementById('level-filter');
    const scopeBtns = document.querySelectorAll('[data-scope]');
    const statusTabs = document.getElementById('status-tabs');
    const statusBtns = document.querySelectorAll('[data-status]');
    const videoEmptyState = document.getElementById('video-empty-state');
    const videoEmptyTitle = videoEmptyState?.querySelector('.kitsu-guide__title');
    const videoEmptyMessage = videoEmptyState?.querySelector('.kitsu-guide__message');
    const videoEmptyAction = document.getElementById('video-empty-action');

    const clientId = getClientId();

    let currentScope = 'public'; // public | my | favorites
    let currentStatus = '';     // '' | READY | DRAFT | PROCESSING | FAILED
    let allVideos = [];
    let searchTimer = null;

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
            if (scope === 'my' && !isLoggedIn()) {
                showToast('Đăng nhập để xem video của bạn');
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

    statusBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            statusBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentStatus = btn.dataset.status || '';
            render();
        });
    });

    searchInput?.addEventListener('input', () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(loadVideos, 300);
    });

    sortSelect?.addEventListener('change', loadVideos);
    categoryFilter?.addEventListener('change', loadVideos);
    levelFilter?.addEventListener('change', loadVideos);

    async function loadVideos() {
        const q = (searchInput?.value || '').trim();
        const sort = sortSelect?.value || 'newest';
        const category = categoryFilter?.value || '';
        const params = new URLSearchParams({ clientId, sort });
        if (q) params.set('q', q);
        if (category) params.set('category', category);
        if (levelFilter?.value) params.set('level', levelFilter.value);

        if (currentScope === 'favorites') {
            params.set('scope', 'public');
            params.set('favoritesOnly', 'true');
        } else if (currentScope === 'my') {
            params.set('scope', 'my');
        } else {
            params.set('scope', 'public');
        }

        try {
            const res = await fetch(`/api/videos?${params}`, { headers: authHeaders() });
            if (!res.ok) throw new Error('load failed');
            allVideos = await res.json();
            render();
        } catch (e) {
            videoGrid.innerHTML = '<p class="lead" style="grid-column:1/-1;text-align:center;">Không tải được thư viện.</p>';
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
        loadVideos();
    }

    async function shareCard(e, v) {
        e.stopPropagation();
        const result = await shareVideo(v.id, v.title);
        if (result === 'copied') {
            window.showToast('Đã sao chép link!');
        }
    }

    function render() {
        let videos = allVideos;
        if (currentScope === 'my' && currentStatus) {
            videos = videos.filter(v => v.status === currentStatus);
        }

        videoGrid.innerHTML = '';
        if (videoEmptyState) videoEmptyState.hidden = true;
        if (!videos.length) {
            const emptyContent = {
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
            if (videoEmptyState) videoEmptyState.hidden = false;
            return;
        }

        videos.forEach(v => {
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
                <div class="video-card-stats">
                    <div class="card-avg-rating" data-avg-rating title="Độ nổi bật (trung bình sao)"></div>
                    <span class="caption" title="Lượt xem: nghe thật ≥ 30 giây, mỗi tài khoản tối đa 1 lượt/24 giờ"><i class="fa-solid fa-eye"></i> ${v.viewCount || 0} lượt xem</span>
                    ${v.commentCount ? `<span class="caption"><i class="fa-solid fa-comment"></i> ${v.commentCount}</span>` : ''}
                </div>
                ${v.status === 'PROCESSING' ? `
                    <div class="card-progress">
                        <div class="progress-bar progress-bar-lg"><div class="progress-fill" style="width:${v.progressPercent || 0}%"></div></div>
                        <span class="caption"><i class="fa-solid fa-spinner"></i> Đang xử lý ${v.progressPercent || 0}%</span>
                    </div>` : ''}
                <div class="card-actions">
                    <button class="text-link card-open-btn" title="Mở bài học" aria-label="Mở bài học"><i class="fa-solid fa-play"></i></button>
                    <button class="text-link card-share-btn" title="Chia sẻ" aria-label="Chia sẻ"><i class="fa-solid fa-share-nodes"></i></button>
                    <button class="text-link card-vis-btn" title="${v.visibility === 'PUBLIC' ? 'Đặt riêng tư' : 'Đặt công khai'}" aria-label="${v.visibility === 'PUBLIC' ? 'Đặt riêng tư' : 'Đặt công khai'}" ${canManage ? '' : 'style="display:none"'}>
                        <i class="fa-solid fa-eye"></i>
                    </button>
                    <button class="text-link card-edit-btn" title="Sửa" aria-label="Sửa" ${canManage ? '' : 'style="display:none"'}><i class="fa-solid fa-pen"></i></button>
                    <button class="text-link card-delete-btn" title="Xóa" aria-label="Xóa" style="color:var(--red);${canManage ? '' : 'display:none'}"><i class="fa-solid fa-trash"></i></button>
                </div>
            `;

            const avgEl = card.querySelector('[data-avg-rating]');
            if (avgEl) avgEl.appendChild(renderAvgStars(v.avgRating, v.ratingCount));

            card.querySelector('.video-card-thumb').addEventListener('click', () => navigate(v));
            card.querySelector('.card-open-btn').addEventListener('click', e => { e.stopPropagation(); navigate(v); });
            card.querySelector('.card-fav-btn').addEventListener('click', e => toggleFavorite(e, v, e.currentTarget));
            card.querySelector('.card-share-btn').addEventListener('click', e => shareCard(e, v));

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
                    loadVideos();
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

    loadVideos();
});
