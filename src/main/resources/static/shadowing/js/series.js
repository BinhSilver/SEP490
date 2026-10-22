document.addEventListener('DOMContentLoaded', async () => {
    const root = document.getElementById('series-detail');
    const id = root?.dataset.seriesId;
    if (!root) return;
    const ui = window.ShadowingLibraryUi;
    const esc = text => { const element = document.createElement('div'); element.textContent = text || ''; return element.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;'); };
    const copy = (text, compact) => `<span class="series-copy-full">${esc(text)}</span><span class="series-copy-compact">${esc(compact)}</span>`;
    const clock = seconds => {
        const total = Math.max(0, Math.round(seconds || 0));
        const h = Math.floor(total / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
        return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0');
    };
    try {
        if (!/^[1-9]\d*$/.test(id || '')) throw new Error('Đường dẫn danh sách không hợp lệ. Hãy mở lại từ thư viện.');
        const res = await fetch(`/api/series/${encodeURIComponent(id)}`, { headers: window.authHeaders ? authHeaders() : {} });
        if (!res.ok) {
            const messages = {
                401: 'Vui lòng đăng nhập lại để xem danh sách.',
                403: 'Bạn không có quyền xem danh sách riêng tư này.',
                404: 'Không tìm thấy danh sách này.',
                400: 'Đường dẫn danh sách không hợp lệ. Hãy mở lại từ thư viện.'
            };
            throw new Error(messages[res.status] || 'Không tải được danh sách. Vui lòng thử lại.');
        }
        const data = await res.json();
        const series = data.series;
        const videos = data.videos || [];
        root.innerHTML = `<header class="series-detail-header">
            <span class="badge badge-primary"><i class="fa-solid fa-list"></i> Series shadowing</span>
            <h1 class="hero-display">${copy(series.title, ui.compactTitle(series.title))}</h1>
            <div class="video-card-meta">${series.estimatedLevel ? `<span class="badge badge-primary">${esc(series.estimatedLevel)}</span>` : '<span class="badge badge-category">Chưa xác định trình độ</span>'}<span class="badge badge-lesson">${videos.length} video</span><span class="badge ${series.visibility === 'PUBLIC' ? 'badge-ready' : 'badge-draft'}">${series.visibility === 'PUBLIC' ? 'Công khai' : 'Riêng tư'}</span></div>
            </header><div class="series-video-list"></div>`;
        const list = root.querySelector('.series-video-list');
        if (!videos.length) list.innerHTML = '<p class="caption">Danh sách chưa có video sẵn sàng để bạn luyện tập.</p>';
        videos.forEach((video, index) => {
            const item = document.createElement('article');
            item.className = 'store-utility-card series-video-row';
            const thumbHtml = video.thumbnailPath
                ? `<img src="/api/videos/${video.id}/thumbnail" alt="" loading="lazy">`
                : '<i class="fa-solid fa-play" aria-hidden="true"></i>';
            const durationHtml = video.duration > 0 ? `<span class="series-video-duration">${clock(video.duration)}</span>` : '';
            item.innerHTML = `<div class="series-video-thumb"><span class="series-position">${index + 1}</span>${thumbHtml}${durationHtml}</div>
                <div class="series-video-main"><h3>${esc(video.title)}</h3><div class="video-card-meta"><span class="badge badge-lesson">${video.subtitleCount || 0} câu</span><span class="caption"><i class="fa-solid fa-eye"></i> ${video.viewCount || 0}</span><span class="card-avg-rating" data-avg-rating></span></div></div>
                <button class="button-primary btn-sm" aria-label="Mở video ${esc(video.title)}"><i class="fa-solid fa-play series-video-open-icon" aria-hidden="true"></i><span class="series-video-open-label">Mở</span></button>`;
            item.querySelector('button').onclick = () => { location.href = `/shadowing/practice/${video.id}`; };
            const avgEl = item.querySelector('[data-avg-rating]');
            if (avgEl && window.renderAvgStars) avgEl.appendChild(window.renderAvgStars(video.avgRating, video.ratingCount));
            list.appendChild(item);
        });
    } catch (error) {
        const message = document.createElement('p');
        message.className = 'lead';
        message.textContent = error.message || 'Không tải được danh sách. Vui lòng thử lại.';
        root.replaceChildren(message);
    }
});
