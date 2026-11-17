(function () {
    let activeModal = null;

    function esc(s) {
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }

    function closeModal(confirmed) {
        if (!activeModal) return;
        const { overlay, dialog, resolve } = activeModal;
        activeModal = null;

        overlay.classList.remove('is-open');
        dialog.classList.remove('is-open');

        const finish = () => {
            overlay.remove();
            dialog.remove();
            document.body.classList.remove('confirm-modal-open');
            resolve(confirmed);
        };

        const onEnd = e => {
            if (e.target !== overlay) return;
            overlay.removeEventListener('transitionend', onEnd);
            finish();
        };

        overlay.addEventListener('transitionend', onEnd);
        setTimeout(finish, 320);
    }

    window.showConfirm = function showConfirm(options = {}) {
        if (activeModal) {
            return Promise.resolve(false);
        }

        const {
            title = 'Xác nhận',
            message = '',
            confirmText = 'Xác nhận',
            cancelText = 'Hủy',
            variant = 'default',
            acknowledge = ''
        } = options;

        return new Promise(resolve => {
            const overlay = document.createElement('div');
            overlay.className = 'confirm-modal-overlay';
            overlay.setAttribute('aria-hidden', 'true');

            const dialog = document.createElement('div');
            dialog.className = 'confirm-modal';
            dialog.setAttribute('role', 'alertdialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.setAttribute('aria-labelledby', 'confirm-modal-title');
            dialog.setAttribute('aria-describedby', 'confirm-modal-message');

            const iconClass = variant === 'danger'
                ? 'fa-solid fa-trash-can'
                : 'fa-solid fa-circle-question';

            dialog.innerHTML = `
                <div class="confirm-modal-icon ${variant}">
                    <i class="${iconClass}"></i>
                </div>
                <h2 class="confirm-modal-title" id="confirm-modal-title">${esc(title)}</h2>
                ${message ? `<p class="confirm-modal-message" id="confirm-modal-message">${esc(message)}</p>` : ''}
                ${acknowledge ? `<label class="confirm-modal-acknowledge"><input type="checkbox" class="confirm-modal-acknowledge-input"><span>${esc(acknowledge)}</span></label>` : ''}
                <div class="confirm-modal-actions">
                    <button type="button" class="button-secondary confirm-modal-cancel">${esc(cancelText)}</button>
                    <button type="button" class="button-primary confirm-modal-confirm ${variant === 'danger' ? 'confirm-modal-danger' : ''}">${esc(confirmText)}</button>
                </div>
            `;

            document.body.appendChild(overlay);
            document.body.appendChild(dialog);
            document.body.classList.add('confirm-modal-open');

            const cancelBtn = dialog.querySelector('.confirm-modal-cancel');
            const confirmBtn = dialog.querySelector('.confirm-modal-confirm');

            activeModal = { overlay, dialog, resolve };

            // Có ô cam kết thì chỉ cho xác nhận sau khi đã tick.
            const acknowledgeInput = dialog.querySelector('.confirm-modal-acknowledge-input');
            if (acknowledgeInput) {
                confirmBtn.disabled = true;
                acknowledgeInput.addEventListener('change', () => { confirmBtn.disabled = !acknowledgeInput.checked; });
            }

            cancelBtn.addEventListener('click', () => closeModal(false));
            confirmBtn.addEventListener('click', () => { if (!confirmBtn.disabled) closeModal(true); });
            overlay.addEventListener('click', () => closeModal(false));

            const onKeyDown = e => {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeModal(false);
                }
            };
            document.addEventListener('keydown', onKeyDown, { once: true });

            requestAnimationFrame(() => {
                overlay.classList.add('is-open');
                dialog.classList.add('is-open');
                (acknowledgeInput || cancelBtn).focus();
            });
        });
    };

    function closeInfoModal() {
        if (!activeModal) return;
        const { overlay, dialog, resolve } = activeModal;
        activeModal = null;

        overlay.classList.remove('is-open');
        dialog.classList.remove('is-open');

        const finish = () => {
            overlay.remove();
            dialog.remove();
            document.body.classList.remove('confirm-modal-open');
            if (resolve) resolve();
        };

        const onEnd = e => {
            if (e.target !== overlay) return;
            overlay.removeEventListener('transitionend', onEnd);
            finish();
        };

        overlay.addEventListener('transitionend', onEnd);
        setTimeout(finish, 320);
    }

    function openInfoModal(dialogHtml, focusSelector, onReady) {
        if (activeModal) return Promise.resolve();

        return new Promise(resolve => {
            const overlay = document.createElement('div');
            overlay.className = 'confirm-modal-overlay';
            overlay.setAttribute('aria-hidden', 'true');

            const dialog = document.createElement('div');
            dialog.className = 'confirm-modal confirm-modal-wide';
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.innerHTML = dialogHtml;

            document.body.appendChild(overlay);
            document.body.appendChild(dialog);
            document.body.classList.add('confirm-modal-open');

            activeModal = { overlay, dialog, resolve };

            const closeBtn = dialog.querySelector('[data-modal-close]');
            closeBtn?.addEventListener('click', closeInfoModal);
            overlay.addEventListener('click', closeInfoModal);

            const onKeyDown = e => {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeInfoModal();
                }
            };
            document.addEventListener('keydown', onKeyDown, { once: true });

            requestAnimationFrame(() => {
                overlay.classList.add('is-open');
                dialog.classList.add('is-open');
                onReady?.(dialog);
                (dialog.querySelector(focusSelector) || closeBtn)?.focus();
            });
        });
    }

    window.showAlert = function showAlert(options = {}) {
        if (typeof options === 'string') options = { message: options };

        const {
            title = 'Thông báo',
            message = '',
            variant = 'default'
        } = options;

        const icons = {
            default: 'fa-solid fa-circle-info',
            warning: 'fa-solid fa-triangle-exclamation',
            error: 'fa-solid fa-circle-xmark',
            success: 'fa-solid fa-circle-check'
        };

        const html = `
            <div class="confirm-modal-icon ${variant}">
                <i class="${icons[variant] || icons.default}"></i>
            </div>
            <h2 class="confirm-modal-title">${esc(title)}</h2>
            ${message ? `<p class="confirm-modal-message confirm-modal-alert-message">${esc(message)}</p>` : ''}
            <div class="confirm-modal-actions">
                <button type="button" class="button-primary" data-modal-close>Đóng</button>
            </div>
        `;

        return openInfoModal(html, '[data-modal-close]');
    };

    window.showToast = function showToast(msg, duration = 2000) {
        const toast = document.createElement('div');
        toast.className = 'toast-notify';
        toast.textContent = msg;
        document.body.appendChild(toast);
        requestAnimationFrame(() => toast.classList.add('show'));
        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 300);
        }, duration);
    };

    window.showCopyLinkModal = function showCopyLinkModal(url) {
        const html = `
            <div class="confirm-modal-icon default">
                <i class="fa-solid fa-link"></i>
            </div>
            <h2 class="confirm-modal-title">Sao chép liên kết</h2>
            <p class="confirm-modal-message">Nhấn nút bên dưới hoặc chọn link để sao chép.</p>
            <input type="text" class="form-input copy-link-input" readonly value="${esc(url)}">
            <div class="confirm-modal-actions">
                <button type="button" class="button-primary" data-copy-link>Sao chép</button>
                <button type="button" class="button-secondary" data-modal-close>Đóng</button>
            </div>
        `;

        return openInfoModal(html, '.copy-link-input', dialog => {
            const input = dialog.querySelector('.copy-link-input');
            const copyBtn = dialog.querySelector('[data-copy-link]');
            input?.addEventListener('click', () => input.select());
            copyBtn?.addEventListener('click', async () => {
                try {
                    await navigator.clipboard.writeText(url);
                    showToast('Đã sao chép link!');
                    closeInfoModal();
                } catch {
                    input?.select();
                    showToast('Không sao chép tự động được — hãy Ctrl+C');
                }
            });
        });
    };

    window.showValidationModal = function showValidationModal(data = {}) {
        const { valid, errors = [], warnings = [] } = data;
        const hasErrors = errors.length > 0;
        const hasWarnings = warnings.length > 0;

        let variant = 'success';
        let icon = 'fa-solid fa-circle-check';
        let title = 'Subtitle hợp lệ';
        let subtitle = 'Bài học sẵn sàng để xuất bản.';

        if (hasErrors) {
            variant = 'error';
            icon = 'fa-solid fa-circle-xmark';
            title = 'Phát hiện lỗi';
            subtitle = 'Sửa các lỗi bên dưới trước khi xuất bản.';
        } else if (hasWarnings) {
            variant = 'warning';
            icon = 'fa-solid fa-triangle-exclamation';
            title = 'Hợp lệ — có gợi ý';
            subtitle = 'Cảnh báo không chặn xuất bản. Bấm «Xuất bản» vẫn được.';
        }

        const errorsHtml = hasErrors
            ? `<div class="validation-block validation-errors">
                <p class="validation-block-title"><i class="fa-solid fa-xmark"></i> Lỗi (${errors.length})</p>
                <ul>${errors.map(e => `<li>${esc(e)}</li>`).join('')}</ul>
               </div>`
            : '';

        const warningsHtml = hasWarnings
            ? `<div class="validation-block validation-warnings">
                <p class="validation-block-title"><i class="fa-solid fa-exclamation"></i> Cảnh báo (${warnings.length})</p>
                <ul>${warnings.map(w => `<li>${esc(w)}</li>`).join('')}</ul>
               </div>`
            : '';

        const summaryHtml = !hasErrors && !hasWarnings
            ? '<p class="validation-ok-msg">Tất cả câu có nội dung, thời gian hợp lệ.</p>'
            : '';

        const html = `
            <div class="confirm-modal-icon ${variant}">
                <i class="${icon}"></i>
            </div>
            <h2 class="confirm-modal-title">${esc(title)}</h2>
            <p class="confirm-modal-message">${esc(subtitle)}</p>
            <div class="validation-modal-body">
                ${summaryHtml}
                ${errorsHtml}
                ${warningsHtml}
            </div>
            <div class="confirm-modal-actions">
                <button type="button" class="button-primary" data-modal-close>Đóng</button>
            </div>
        `;

        return openInfoModal(html, '[data-modal-close]');
    };

    /**
     * Popup "thùng rác ăn nhãn" trong lúc đang xoá: chặn cả trang nên không bấm xoá thêm lần nữa khi máy chủ chậm.
     * const bin = showDeleting({ title }); … await bin.done('Đã xoá') hoặc bin.fail();
     * Hoạt cảnh lặp lại cho tới khi người gọi báo xong; lúc đó nó chạy nốt nhịp đang dở (CYCLE_MS, khớp với animation
     * trong styles.css) rồi mới hiện dấu tích, nên không bị cắt giữa chừng và luôn có ít nhất một nhịp "ăn".
     */
    window.showDeleting = function showDeleting(options = {}) {
        const CYCLE_MS = 1600;
        const DONE_MS = 650;
        const { title = '', message = 'Đang xoá…' } = options;
        const opened = Date.now();

        const overlay = document.createElement('div');
        overlay.className = 'confirm-modal-overlay';
        overlay.setAttribute('aria-hidden', 'true');

        const dialog = document.createElement('div');
        dialog.className = 'confirm-modal bin-modal';
        dialog.setAttribute('role', 'status');
        dialog.setAttribute('aria-live', 'polite');
        dialog.setAttribute('aria-busy', 'true');
        dialog.innerHTML = `
            <svg class="bin-modal-art" viewBox="0 0 120 120" aria-hidden="true">
                <g class="bin-modal-label">
                    <rect x="38" y="6" width="44" height="24" rx="6"></rect>
                    <circle cx="46" cy="18" r="2.6" class="bin-modal-label-hole"></circle>
                    <rect x="52" y="13" width="24" height="3.4" rx="1.7" class="bin-modal-label-line"></rect>
                    <rect x="52" y="20" width="16" height="3.4" rx="1.7" class="bin-modal-label-line"></rect>
                </g>
                <g class="bin-modal-body">
                    <path d="M36 56h48l-4.5 46a6 6 0 0 1-6 5.4H46.5a6 6 0 0 1-6-5.4z"></path>
                    <rect x="50" y="64" width="3.4" height="34" rx="1.7" class="bin-modal-stripe"></rect>
                    <rect x="58.3" y="64" width="3.4" height="34" rx="1.7" class="bin-modal-stripe"></rect>
                    <rect x="66.6" y="64" width="3.4" height="34" rx="1.7" class="bin-modal-stripe"></rect>
                </g>
                <g class="bin-modal-lid">
                    <rect x="31" y="46" width="58" height="8" rx="4"></rect>
                    <rect x="52" y="40" width="16" height="7" rx="3"></rect>
                </g>
                <g class="bin-modal-check">
                    <circle cx="60" cy="60" r="34"></circle>
                    <path d="M45 61l10 10 20-22"></path>
                </g>
            </svg>
            <h2 class="confirm-modal-title bin-modal-title">${esc(message)}</h2>
            ${title ? `<p class="confirm-modal-message bin-modal-name">${esc(title)}</p>` : ''}
        `;

        const content = document.getElementById('jlabBaseContent');
        document.body.appendChild(overlay);
        document.body.appendChild(dialog);
        document.body.classList.add('confirm-modal-open');
        // Nothing behind the popup can be clicked or reached with Tab while it is up.
        if (content) content.inert = true;
        requestAnimationFrame(() => {
            overlay.classList.add('is-open');
            dialog.classList.add('is-open');
        });

        let closed = false;
        function close() {
            if (closed) return;
            closed = true;
            overlay.classList.remove('is-open');
            dialog.classList.remove('is-open');
            setTimeout(() => {
                overlay.remove();
                dialog.remove();
                if (content) content.inert = false;
                if (!document.querySelector('.confirm-modal')) document.body.classList.remove('confirm-modal-open');
            }, 320);
        }

        // Until the end of the cycle that is playing (at least the first one).
        const waitMin = () => {
            const elapsed = Date.now() - opened;
            const cycles = Math.max(1, Math.ceil(elapsed / CYCLE_MS));
            return new Promise(resolve => setTimeout(resolve, cycles * CYCLE_MS - elapsed));
        };

        return {
            /** Shows the tick and the text for a moment, then closes. */
            done(text = 'Đã xoá') {
                return waitMin().then(() => new Promise(resolve => {
                    dialog.classList.add('is-done');
                    dialog.setAttribute('aria-busy', 'false');
                    dialog.querySelector('.bin-modal-title').textContent = text;
                    setTimeout(() => { close(); resolve(); }, DONE_MS);
                }));
            },
            /** Closes at once; the caller says what went wrong. */
            fail() {
                close();
            }
        };
    };
})();
