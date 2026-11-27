/* Nút "Báo cáo video" ở trang player: chọn lý do (+ mô tả) rồi gửi cho quản trị viên.
   Video bị ẩn tạm thời cho đến khi quản trị viên quyết định; báo cáo đúng thì video bị gỡ và link YouTube gốc bị chặn. */
(function (root) {
    'use strict';

    const REASONS = [
        { code: 'COPYRIGHT', label: 'Vi phạm bản quyền', hint: 'Video, nhạc hoặc phim được đăng khi chưa có phép của chủ sở hữu.' },
        { code: 'INAPPROPRIATE', label: 'Nội dung không phù hợp', hint: 'Bạo lực, nội dung người lớn, thù ghét hoặc gây hại.' },
        { code: 'OTHER', label: 'Lý do khác', hint: 'Hãy mô tả ngắn bên dưới.' }
    ];
    const MAX_DETAIL = 500;

    function esc(text) {
        const el = document.createElement('div');
        el.textContent = text;
        return el.innerHTML;
    }

    function isLoggedIn() {
        return !!(root.JlasAuth && JlasAuth.isLoggedIn && JlasAuth.isLoggedIn()) || !!root.localStorage?.getItem('accessToken');
    }

    /** Gửi báo cáo; trả về {message, hidden} của máy chủ hoặc ném Error với lý do dễ hiểu. */
    async function submit(videoId, reason, detail) {
        const headers = typeof root.authHeaders === 'function' ? root.authHeaders() : { 'Content-Type': 'application/json' };
        let res;
        try {
            res = await fetch(`/api/videos/${encodeURIComponent(videoId)}/report`, {
                method: 'POST', headers, body: JSON.stringify({ reason, detail })
            });
        } catch (_) {
            throw new Error('Không kết nối được tới máy chủ. Hãy thử lại.');
        }
        const body = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(body.message || body.error || 'Không gửi được báo cáo. Hãy thử lại.');
        return { message: body.message || 'Đã gửi báo cáo.', hidden: !!(body.data && body.data.hidden) };
    }

    /** Mở hộp thoại; resolve kết quả {message, hidden}, hoặc null nếu người dùng đóng. */
    function openDialog(videoId) {
        return new Promise(resolve => {
            const overlay = document.createElement('div');
            overlay.className = 'confirm-modal-overlay';
            overlay.setAttribute('aria-hidden', 'true');
            const dialog = document.createElement('form');
            dialog.className = 'confirm-modal confirm-modal-wide report-dialog';
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.setAttribute('aria-labelledby', 'report-dialog-title');
            dialog.noValidate = true;
            dialog.innerHTML = `
                <div class="confirm-modal-icon danger"><i class="fa-solid fa-flag" aria-hidden="true"></i></div>
                <h2 class="confirm-modal-title" id="report-dialog-title">Báo cáo video</h2>
                <fieldset class="report-reasons">
                    <legend class="sr-only">Lý do báo cáo</legend>
                    ${REASONS.map((r, i) => `
                        <label class="report-reason"><input type="radio" name="report-reason" value="${r.code}"${i === 0 ? ' checked' : ''}>
                            <span><strong>${esc(r.label)}</strong><small>${esc(r.hint)}</small></span></label>`).join('')}
                </fieldset>
                <label class="report-detail-label" for="report-detail">Mô tả thêm <span class="caption">(bắt buộc nếu chọn «Lý do khác»)</span></label>
                <textarea id="report-detail" class="form-input report-detail" rows="3" maxlength="${MAX_DETAIL}" placeholder="Ví dụ: đây là tập phim X của hãng Y, link gốc..."></textarea>
                <p class="report-error" role="alert" hidden></p>
                <div class="confirm-modal-actions">
                    <button type="button" class="button-secondary report-cancel">Hủy</button>
                    <button type="submit" class="button-primary confirm-modal-danger report-send">Gửi báo cáo</button>
                </div>`;
            document.body.appendChild(overlay);
            document.body.appendChild(dialog);
            document.body.classList.add('confirm-modal-open');

            const errorEl = dialog.querySelector('.report-error');
            const sendBtn = dialog.querySelector('.report-send');
            let busy = false;

            function close(result) {
                document.removeEventListener('keydown', onKey);
                overlay.classList.remove('is-open');
                dialog.classList.remove('is-open');
                setTimeout(() => {
                    overlay.remove();
                    dialog.remove();
                    document.body.classList.remove('confirm-modal-open');
                    resolve(result);
                }, 300);
            }
            function onKey(e) { if (e.key === 'Escape' && !busy) close(null); }
            document.addEventListener('keydown', onKey);
            dialog.querySelector('.report-cancel').addEventListener('click', () => { if (!busy) close(null); });
            overlay.addEventListener('click', () => { if (!busy) close(null); });

            dialog.addEventListener('submit', async event => {
                event.preventDefault();
                if (busy) return;
                const reason = dialog.querySelector('input[name="report-reason"]:checked')?.value;
                const detail = dialog.querySelector('#report-detail').value.trim();
                errorEl.hidden = true;
                if (reason === 'OTHER' && !detail) {
                    errorEl.textContent = 'Hãy mô tả ngắn lý do báo cáo.';
                    errorEl.hidden = false;
                    dialog.querySelector('#report-detail').focus();
                    return;
                }
                busy = true;
                sendBtn.disabled = true;
                sendBtn.textContent = 'Đang gửi...';
                try {
                    close(await submit(videoId, reason, detail));
                } catch (error) {
                    busy = false;
                    sendBtn.disabled = false;
                    sendBtn.textContent = 'Gửi báo cáo';
                    errorEl.textContent = error.message;
                    errorEl.hidden = false;
                }
            });

            requestAnimationFrame(() => {
                overlay.classList.add('is-open');
                dialog.classList.add('is-open');
                dialog.querySelector('input[name="report-reason"]:checked')?.focus();
            });
        });
    }

    root.ShadowingVideoReport = { REASONS, submit, openDialog };
    if (typeof module === 'object' && module.exports) module.exports = root.ShadowingVideoReport;

    if (typeof document === 'undefined') return;
    document.addEventListener('DOMContentLoaded', () => {
        const button = document.getElementById('report-btn');
        const idInput = document.getElementById('video-id');
        if (!button || !idInput) return;
        button.addEventListener('click', async () => {
            if (!isLoggedIn()) { location.href = '/login'; return; }
            const result = await openDialog(idInput.value);
            if (!result) return;
            if (typeof root.showAlert === 'function') {
                await root.showAlert({ title: 'Đã gửi báo cáo', message: result.message, variant: 'success' });
            }
            // Báo cáo này làm video bị ẩn: người báo cáo cũng không mở lại được nữa, nên quay về thư viện.
            if (result.hidden) location.href = '/shadowing';
        });
    });
})(typeof window !== 'undefined' ? window : globalThis);
