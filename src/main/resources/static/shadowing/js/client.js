window.CATEGORY_LABELS = {
    Podcast: 'Podcast',
    Anime: 'Anime',
    Drama: 'Phim kịch',
    News: 'Tin tức',
    Speech: 'Bài phát biểu',
    Daily: 'Hàng ngày',
    Story: 'Truyện',
    JLPT: 'JLPT'
};

/** Genre chips rendered by templates/shadowing/genre-picker.html: several per video, at most MAX. */
window.ShadowingGenres = {
    MAX: 5,
    read(root) {
        return root ? Array.from(root.querySelectorAll('input[type="checkbox"]:checked')).map(input => input.value) : [];
    },
    write(root, codes) {
        if (!root) return;
        const wanted = new Set(codes || []);
        root.querySelectorAll('input[type="checkbox"]').forEach(input => { input.checked = wanted.has(input.value); });
        this.limit(root);
    },
    limit(root) {
        const boxes = Array.from(root.querySelectorAll('input[type="checkbox"]'));
        const full = boxes.filter(input => input.checked).length >= this.MAX;
        boxes.forEach(input => { input.disabled = full && !input.checked; });
    },
    bind(root, onChange) {
        if (!root) return;
        root.addEventListener('change', () => { this.limit(root); if (onChange) onChange(this.read(root)); });
        this.limit(root);
    },
    /** Compact badges for a card: the first few genres and "+n" for the rest. */
    badges(genres, shown) {
        const list = Array.isArray(genres) ? genres : [];
        const visible = list.slice(0, shown || 2);
        const escape = text => String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
        const rest = list.length - visible.length;
        return visible.map(g => `<span class="badge badge-genre">${escape(g.label)}</span>`).join('')
            + (rest > 0 ? `<span class="badge badge-genre" title="${escape(list.slice(visible.length).map(g => g.label).join(', '))}">+${rest}</span>` : '');
    }
};

window.truncateTitle = function truncateTitle(text, maxLen) {
    if (!text) return '';
    const limit = maxLen || 48;
    if (text.length <= limit) return text;
    return text.slice(0, limit - 1).trimEnd() + '…';
};

window.getCategoryLabel = function getCategoryLabel(cat) {
    return window.CATEGORY_LABELS[cat] || cat || 'Hàng ngày';
};

window.getClientId = function getClientId() {
    let id = localStorage.getItem('jlas_client_id');
    if (!id) {
        id = 'client_' + Math.random().toString(36).slice(2);
        localStorage.setItem('jlas_client_id', id);
    }
    return id;
};

/** JWT headers for shadowing API calls (private / owner drafts). */
window.authHeaders = function authHeaders(json) {
    if (window.JlasAuth && typeof JlasAuth.authHeaders === 'function') {
        const h = JlasAuth.authHeaders();
        if (json === false) {
            delete h['Content-Type'];
        }
        return h;
    }
    const h = {};
    if (json !== false) h['Content-Type'] = 'application/json';
    const t = localStorage.getItem('accessToken');
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
};

window.getAccessToken = function getAccessToken() {
    if (window.JlasAuth && typeof JlasAuth.getAccessToken === 'function') {
        return JlasAuth.getAccessToken();
    }
    return localStorage.getItem('accessToken');
};

/**
 * Stream URL for <video>/<audio>. Browsers cannot attach Authorization headers
 * on media element requests, so the JWT is passed as access_token when present.
 */
window.mediaStreamUrl = function mediaStreamUrl(videoId) {
    const base = `/api/videos/${videoId}/stream`;
    const t = window.getAccessToken && window.getAccessToken();
    if (!t) return base;
    return `${base}?access_token=${encodeURIComponent(t)}`;
};

window.shareVideo = async function shareVideo(videoId, title) {
    const url = `${location.origin}/shadowing/practice/${videoId}`;
    const shareData = { title: title || 'JLAS — Luyện shadowing', text: title, url };
    if (navigator.share) {
        try {
            await navigator.share(shareData);
            return true;
        } catch (e) {
            if (e.name === 'AbortError') return false;
        }
    }
    try {
        await navigator.clipboard.writeText(url);
        return 'copied';
    } catch {
        if (typeof window.showCopyLinkModal === 'function') {
            await window.showCopyLinkModal(url);
        }
        return false;
    }
};

window.renderAvgStars = function renderAvgStars(avg, count) {
    const wrap = document.createElement('div');
    wrap.className = 'avg-star-display';
    const value = Number(avg) || 0;
    const stars = document.createElement('span');
    stars.className = 'avg-star-icons';
    for (let i = 1; i <= 5; i++) {
        const icon = document.createElement('i');
        if (value >= i - 0.25) icon.className = 'fa-solid fa-star';
        else icon.className = 'fa-regular fa-star';
        stars.appendChild(icon);
    }
    wrap.appendChild(stars);
    const label = document.createElement('span');
    label.className = 'avg-star-label';
    label.textContent = value > 0
        ? `${value.toFixed(1)} · ${count || 0} đánh giá`
        : 'Chưa có đánh giá';
    wrap.appendChild(label);
    return wrap;
};

window.renderStars = function renderStars(rating, interactive, onRate) {
    const wrap = document.createElement('div');
    wrap.className = 'star-rating' + (interactive ? ' interactive' : '');
    for (let i = 1; i <= 5; i++) {
        const star = document.createElement('button');
        star.type = 'button';
        star.className = 'star-btn' + (rating >= i ? ' filled' : '');
        star.innerHTML = '<i class="fa-solid fa-star"></i>';
        star.title = `${i} sao`;
        if (interactive && onRate) {
            star.addEventListener('click', e => { e.stopPropagation(); onRate(i); });
        } else {
            star.disabled = true;
        }
        wrap.appendChild(star);
    }
    return wrap;
};
