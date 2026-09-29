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
    let seriesLoaded = false;

    const esc = s => { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; };
    const headers = () => window.authHeaders ? authHeaders() : { 'Content-Type': 'application/json' };
    const loggedIn = () => !!localStorage.getItem('accessToken') || !!(window.JlasAuth && JlasAuth.isLoggedIn?.());

    async function loadSeries() {
        const q = document.getElementById('search-input')?.value?.trim() || '';
        const sort = document.getElementById('sort-select')?.value || 'newest';
        const url = `/api/series/public?sort=${encodeURIComponent(sort)}${q ? `&q=${encodeURIComponent(q)}` : ''}`;
        if (seriesEmptyState) seriesEmptyState.hidden = true;
        seriesGrid.innerHTML = '<p class="lead">Series đang tải...</p>';
        const res = await fetch(url);
        if (!res.ok) { seriesGrid.innerHTML = '<p class="lead">Không tải được series.</p>'; return; }
        const level = document.getElementById('level-filter')?.value || '';
        const rows = (await res.json()).filter(s => !level || s.estimatedLevel === level);
        seriesGrid.innerHTML = '';
        if (seriesEmptyState) seriesEmptyState.hidden = rows.length > 0;
        rows.forEach(renderSeries);
        seriesLoaded = true;
    }

    function renderSeries(s) {
        const card = document.createElement('article');
        card.className = 'store-utility-card video-card series-card';
        const level = s.estimatedLevel ? `<span class="badge badge-primary">${esc(s.estimatedLevel)}</span>` : '<span class="badge badge-category">Chưa xác định trình độ</span>';
        card.innerHTML = `<div class="series-card-icon"><i class="fa-solid fa-list-ul"></i></div>
            <div class="video-card-title">${esc(s.title)}</div>
            <p class="caption series-description">${esc(s.description || 'Series luyện nghe shadowing tiếng Nhật')}</p>
            <div class="video-card-meta">${level}<span class="badge badge-lesson"><i class="fa-solid fa-film"></i> ${s.videoCount} video</span></div>
            <div class="video-card-stats"><span class="caption"><i class="fa-solid fa-eye"></i> ${s.viewCount || 0} lượt xem</span><span class="caption">${s.avgRating ? `★ ${Number(s.avgRating).toFixed(1)}` : 'Chưa có đánh giá'}</span></div>
            <div class="card-actions"><button class="text-link series-open"><i class="fa-solid fa-play"></i> Mở series</button></div>`;
        card.querySelector('.series-open').addEventListener('click', () => { location.href = `/shadowing/series/${s.id}`; });
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
        if (videoEmptyState) videoEmptyState.hidden = true;
        document.getElementById('status-tabs').hidden = true;
        if (!seriesLoaded) loadSeries();
    });
    document.querySelectorAll('[data-scope]').forEach(b => b.addEventListener('click', () => {
        videoGrid.hidden = false;
        seriesGrid.hidden = true;
        if (seriesEmptyState) seriesEmptyState.hidden = true;
        tab.classList.remove('active');
    }));
    document.getElementById('search-input')?.addEventListener('input', () => { if (!seriesGrid.hidden) { clearTimeout(window.seriesSearchTimer); window.seriesSearchTimer = setTimeout(loadSeries, 300); } });
    document.getElementById('sort-select')?.addEventListener('change', () => { if (!seriesGrid.hidden) loadSeries(); });
    document.getElementById('level-filter')?.addEventListener('change', () => { if (!seriesGrid.hidden) loadSeries(); });
    createBtn?.addEventListener('click', openCreate);
    emptyCreateBtn?.addEventListener('click', openCreate);
    document.getElementById('series-dialog-close')?.addEventListener('click', () => dialog.close());
    form?.addEventListener('submit', async e => {
        e.preventDefault();
        const videoIds = [...picker.querySelectorAll('input:checked')].map(x => Number(x.value));
        const res = await fetch('/api/series', { method: 'POST', headers: headers(), body: JSON.stringify({ title: document.getElementById('series-title').value, description: document.getElementById('series-description').value, estimatedLevel: document.getElementById('series-level').value, levelSource: 'CREATOR', visibility: document.getElementById('series-visibility').value, videoIds }) });
        if (!res.ok) { const body = await res.json().catch(() => ({})); alert(body.error || 'Không tạo được series'); return; }
        dialog.close(); tab.click(); loadSeries();
    });
});
