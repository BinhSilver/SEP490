document.addEventListener('DOMContentLoaded', async () => {
    const root = document.getElementById('series-detail');
    const id = root?.dataset.seriesId;
    if (!root) return;
    const ui = window.ShadowingLibraryUi;
    const esc = text => { const element = document.createElement('div'); element.textContent = text || ''; return element.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;'); };
    const copy = (text, compact) => `<span class="series-copy-full">${esc(text)}</span><span class="series-copy-compact">${esc(compact)}</span>`;
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
            <p class="lead">${copy(series.description || '', ui.compactDescription(series.description))}</p>
            <div class="video-card-meta">${series.estimatedLevel ? `<span class="badge badge-primary">${esc(series.estimatedLevel)}</span>` : '<span class="badge badge-category">Chưa xác định trình độ</span>'}<span class="badge badge-lesson">${videos.length} video</span><span class="badge ${series.visibility === 'PUBLIC' ? 'badge-ready' : 'badge-draft'}">${series.visibility === 'PUBLIC' ? 'Công khai' : 'Riêng tư'}</span></div>
            </header><div class="series-video-list"></div>`;
        const list = root.querySelector('.series-video-list');
        if (!videos.length) list.innerHTML = '<p class="caption">Danh sách chưa có video sẵn sàng để bạn luyện tập.</p>';
        videos.forEach((video, index) => {
            const item = document.createElement('article');
            item.className = 'store-utility-card series-video-row';
            item.innerHTML = `<span class="series-position">${index + 1}</span>
                <div class="series-video-main"><h3>${esc(video.title)}</h3><div class="video-card-meta"><span class="badge badge-lesson">${video.subtitleCount || 0} câu</span><span class="caption"><i class="fa-solid fa-eye"></i> ${video.viewCount || 0}</span></div></div>
                <button class="button-primary btn-sm" aria-label="Mở video ${esc(video.title)}"><i class="fa-solid fa-play series-video-open-icon" aria-hidden="true"></i><span class="series-video-open-label">Mở</span></button>`;
            item.querySelector('button').onclick = () => { location.href = `/shadowing/practice/${video.id}`; };
            list.appendChild(item);
        });
    } catch (error) {
        const message = document.createElement('p');
        message.className = 'lead';
        message.textContent = error.message || 'Không tải được danh sách. Vui lòng thử lại.';
        root.replaceChildren(message);
    }
});
