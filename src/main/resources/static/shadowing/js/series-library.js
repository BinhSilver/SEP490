document.addEventListener('DOMContentLoaded', () => {
    const tab = document.getElementById('series-tab');
    const videoGrid = document.getElementById('video-grid');
    const seriesGrid = document.getElementById('series-grid');
    const dialog = document.getElementById('series-dialog');
    const form = document.getElementById('series-form');
    const picker = document.getElementById('series-video-picker');
    const createBtn = document.getElementById('create-series-btn');
    const videoEmptyState = document.getElementById('video-empty-state');
    const seriesEmptyState = document.getElementById('series-empty-state');
    const emptyCreateBtn = document.getElementById('empty-create-series-btn');
    if (!tab || !seriesGrid) return;
    let loadVersion = 0;
    let currentPage = 1;
    const pager = window.ShadowingLibraryUi.createPager(document.getElementById('series-pagination'), page => loadSeries(page));

    const esc = s => { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;'); };
    const headers = () => window.authHeaders ? authHeaders() : { 'Content-Type': 'application/json' };
    const loggedIn = () => !!localStorage.getItem('accessToken') || !!(window.JlasAuth && JlasAuth.isLoggedIn?.());

    async function loadSeries(page = 1) {
        const version = ++loadVersion;
        const q = document.getElementById('search-input')?.value?.trim() || '';
        const sort = document.getElementById('sort-select')?.value || 'popular';
        const params = new URLSearchParams({ sort, page: String(page), size: '12' });
        if (q) params.set('q', q);
        const level = document.getElementById('level-filter')?.value;
        const category = document.getElementById('category-filter')?.value;
        const genre = document.getElementById('genre-filter')?.value;
        if (level) params.set('level', level);
        if (category) params.set('category', category);
        if (genre) params.set('genre', genre);
        pager.setBusy(true);
        seriesGrid.setAttribute('aria-busy', 'true');
        if (seriesEmptyState) seriesEmptyState.hidden = true;
        seriesGrid.innerHTML = '<p class="lead">Series đang tải...</p>';
        try {
            const res = await fetch(`/api/series/public/page?${params}`, { headers: headers() });
            if (!res.ok) throw new Error(res.status === 401 ? 'Vui lòng đăng nhập lại để xem danh sách.' : 'Không tải được danh sách. Hãy thử lại.');
            const body = await res.json();
            const data = body.data;
            const rows = data?.items;
            if (version !== loadVersion) return;
            if (!body.success || !Array.isArray(rows)) throw new Error('Không tải được danh sách. Hãy thử lại.');
            seriesGrid.innerHTML = '';
            if (seriesEmptyState) seriesEmptyState.hidden = rows.length > 0 || seriesGrid.hidden;
            rows.forEach(renderSeries);
            const pageChanged = currentPage !== data.page;
            currentPage = data.page;
            pager.render(data);
            if (seriesGrid.hidden) pager.hide();
            if (pageChanged && !seriesGrid.hidden) seriesGrid.scrollIntoView({ block: 'start', behavior: 'auto' });
        } catch (error) {
            if (version !== loadVersion) return;
            pager.hide();
            seriesGrid.innerHTML = `<p class="lead library-load-error" role="alert">${esc(error.message || 'Không tải được danh sách. Hãy thử lại.')}</p>`;
        } finally {
            if (version === loadVersion) { pager.setBusy(false); seriesGrid.setAttribute('aria-busy', 'false'); }
        }
    }

    /** Thẻ danh sách kiểu playlist: ảnh bìa xếp chồng + số video, rồi tên và đánh giá. */
    function renderSeries(s) {
        const card = document.createElement('article');
        card.className = 'store-utility-card video-card series-card';
        const ui = window.ShadowingLibraryUi;
        const href = `/shadowing/series/${encodeURIComponent(s.id)}`;
        const cover = s.coverVideoId
            ? `<img src="/api/videos/${encodeURIComponent(s.coverVideoId)}/thumbnail" alt="" loading="lazy" decoding="async">`
            : '<i class="fa-solid fa-list-ul" aria-hidden="true"></i>';
        const level = s.estimatedLevel ? `<span class="card-level-badge" title="Cấp độ JLPT">${esc(s.estimatedLevel)}</span>` : '';
        const rating = s.avgRating ? ` · <span class="series-card-rating">★ ${Number(s.avgRating).toFixed(1)}</span>` : '';
        card.innerHTML = `<a class="series-card-cover" href="${href}" aria-label="Mở danh sách ${esc(ui.compactTitle(s.title))}">
                <span class="video-card-thumb">${cover}${level}
                    <span class="series-card-count"><i class="fa-solid fa-list-ul" aria-hidden="true"></i> ${Number(s.videoCount) || 0} video</span>
                </span>
            </a>
            <a class="video-card-title series-card-title" href="${href}" title="${esc(s.title)}"><span class="series-copy-full">${esc(s.title)}</span><span class="series-copy-compact">${esc(ui.compactTitle(s.title))}</span></a>
            <p class="caption series-card-kind">Danh sách phát · <i class="fa-solid fa-eye" aria-hidden="true"></i> ${Number(s.viewCount) || 0} lượt xem${rating}</p>`;
        seriesGrid.appendChild(card);
    }

    async function openCreate() {
        if (!loggedIn()) { location.href = '/login'; return; }
        const res = await fetch('/api/videos?scope=my&sort=newest', { headers: headers() });
        const videos = res.ok ? await res.json() : [];
        picker.innerHTML = videos.filter(v => v.status === 'READY').map(v => `<label class="series-video-option"><input type="checkbox" value="${v.id}"><span>${esc(v.title)}</span><small>${v.subtitleCount || 0} câu</small></label>`).join('') || '<p class="caption">Bạn chưa có video READY để đưa vào series.</p>';
        dialog.showModal();
    }

    tab.addEventListener('click', () => {
        document.querySelectorAll('[data-scope]').forEach(b => b.classList.remove('active'));
        tab.classList.add('active'); videoGrid.hidden = true; seriesGrid.hidden = false;
        document.getElementById('video-pagination').hidden = true;
        if (videoEmptyState) videoEmptyState.hidden = true;
        document.getElementById('status-tabs').hidden = true;
        loadSeries();
    });
    document.querySelectorAll('[data-scope]').forEach(b => b.addEventListener('click', () => {
        videoGrid.hidden = false;
        seriesGrid.hidden = true;
        pager.hide();
        if (seriesEmptyState) seriesEmptyState.hidden = true;
        tab.classList.remove('active');
    }));
    document.getElementById('search-input')?.addEventListener('input', () => { if (!seriesGrid.hidden) { clearTimeout(window.seriesSearchTimer); window.seriesSearchTimer = setTimeout(loadSeries, 300); } });
    document.getElementById('sort-select')?.addEventListener('change', () => { if (!seriesGrid.hidden) loadSeries(); });
    document.getElementById('level-filter')?.addEventListener('change', () => { if (!seriesGrid.hidden) loadSeries(); });
    document.getElementById('category-filter')?.addEventListener('change', () => { if (!seriesGrid.hidden) loadSeries(); });
    document.getElementById('genre-filter')?.addEventListener('change', () => { if (!seriesGrid.hidden) loadSeries(); });
    createBtn?.addEventListener('click', openCreate);
    emptyCreateBtn?.addEventListener('click', openCreate);
    document.getElementById('series-dialog-close')?.addEventListener('click', () => dialog.close());
    form?.addEventListener('submit', async e => {
        e.preventDefault();
        const videoIds = [...picker.querySelectorAll('input:checked')].map(x => Number(x.value));
        const res = await fetch('/api/series', { method: 'POST', headers: headers(), body: JSON.stringify({ title: document.getElementById('series-title').value, description: document.getElementById('series-description').value, estimatedLevel: document.getElementById('series-level').value, levelSource: 'CREATOR', visibility: document.getElementById('series-visibility').value, videoIds }) });
        if (!res.ok) { const body = await res.json().catch(() => ({})); alert(body.error || 'Không tạo được series'); return; }
        dialog.close(); tab.click();
    });
});
