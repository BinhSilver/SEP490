document.addEventListener('DOMContentLoaded', async () => {
    const root = document.getElementById('series-detail');
    const id = root?.dataset.seriesId;
    if (!id) return;
    const esc = s => { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; };
    try {
        const res = await fetch(`/api/series/${id}`, { headers: window.authHeaders ? authHeaders() : {} });
        if (!res.ok) throw new Error();
        const data = await res.json(), s = data.series, videos = data.videos || [];
        root.innerHTML = `<header class="series-detail-header"><span class="badge badge-primary"><i class="fa-solid fa-list"></i> Series shadowing</span><h1 class="hero-display">${esc(s.title)}</h1><p class="lead">${esc(s.description || '')}</p><div class="video-card-meta">${s.estimatedLevel ? `<span class="badge badge-primary">${esc(s.estimatedLevel)}</span>` : '<span class="badge badge-category">Chưa xác định trình độ</span>'}<span class="badge badge-lesson">${videos.length} video</span><span class="badge ${s.visibility === 'PUBLIC' ? 'badge-ready' : 'badge-draft'}">${s.visibility === 'PUBLIC' ? 'Công khai' : 'Riêng tư'}</span></div></header><div class="series-video-list"></div>`;
        const list = root.querySelector('.series-video-list');
        videos.forEach((v, i) => { const item = document.createElement('article'); item.className = 'store-utility-card series-video-row'; item.innerHTML = `<span class="series-position">${i + 1}</span><div class="series-video-main"><h3>${esc(v.title)}</h3><div class="video-card-meta"><span class="badge badge-lesson">${v.subtitleCount || 0} câu</span><span class="caption"><i class="fa-solid fa-eye"></i> ${v.viewCount || 0}</span></div></div><button class="button-primary btn-sm">Mở</button>`; item.querySelector('button').onclick = () => { location.href = `/shadowing/practice/${v.id}`; }; list.appendChild(item); });
    } catch { root.innerHTML = '<p class="lead">Không tải được series hoặc series đang ở chế độ riêng tư.</p>'; }
});
