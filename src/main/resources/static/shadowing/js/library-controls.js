(function (root) {
    'use strict';
    const values = value => [...new Set(String(value || '').split(',').map(v => v.trim()).filter(Boolean))];
    const compactTitle = title => String(title || '').replace(/^\s*J[\s-]*LAS\s*[|:–—-]\s*/i, '');
    const compactDescription = description => String(description || '')
        .replace(/\s+của\s+J[\s-]*LAS\b/gi, '').replace(/\bJ[\s-]*LAS\s*[|:–—-]?\s*/gi, '').trim();

    function ratingHtml(average, count) {
        const number = Number(average);
        const rated = Number.isFinite(number) && number > 0 && number <= 5 && Number(count) > 0;
        const value = rated ? number.toFixed(1) : '—';
        const label = rated ? `${value} trên 5 sao · ${Math.max(0, Math.floor(Number(count)))} đánh giá` : 'Chưa có đánh giá';
        return `<span class="card-compact-rating" role="img" aria-label="${label}" title="${label}"><i class="fa-regular fa-star" aria-hidden="true"></i><span aria-hidden="true">${value}</span></span>`;
    }

    function pageNumbers(page, pages) {
        const start = Math.max(1, Math.min(page - 1, pages - 2));
        return Array.from({ length: Math.min(3, pages) }, (_, i) => start + i);
    }

    function createPager(element, onPage) {
        if (!element) return { render() {}, setBusy() {}, hide() {} };
        let current = 1, busy = false;
        element.addEventListener('click', event => {
            const button = event.target.closest('button[data-page]');
            if (!button || button.disabled || busy) return;
            const next = Number(button.dataset.page);
            if (next !== current) onPage(next);
        });
        return {
            hide() { element.hidden = true; },
            setBusy(value) {
                busy = value;
                element.setAttribute('aria-busy', String(value));
                element.querySelectorAll('button').forEach(button => { button.disabled = value || button.dataset.edge === 'true'; });
            },
            render(data) {
                const restoreFocus = element.contains(element.ownerDocument.activeElement);
                current = data.page;
                busy = false;
                element.hidden = data.totalItems === 0;
                element.setAttribute('aria-busy', 'false');
                const start = data.totalItems ? (data.page - 1) * data.size + 1 : 0;
                const end = Math.min(data.page * data.size, data.totalItems);
                const button = (page, content, label, disabled = false) => `<button type="button" data-page="${page}" data-edge="${disabled}" aria-label="${label}" ${page === current ? 'aria-current="page"' : ''} ${disabled ? 'disabled' : ''}>${content}</button>`;
                const numbers = pageNumbers(data.page, data.totalPages).map(page => button(page, page, `Trang ${page}`)).join('');
                element.innerHTML = `<span class="library-page-summary" role="status">${start}–${end} / ${data.totalItems}</span>` +
                    (data.totalPages > 1 ? `<div class="library-page-buttons">${button(current - 1, '<i class="fa-solid fa-chevron-left" aria-hidden="true"></i>', 'Trang trước', current === 1)}${numbers}${button(current + 1, '<i class="fa-solid fa-chevron-right" aria-hidden="true"></i>', 'Trang sau', current === data.totalPages)}</div><span class="library-page-position">Trang ${current}/${data.totalPages}</span>` : '');
                if (restoreFocus) element.querySelector('[aria-current="page"]')?.focus({ preventScroll: true });
            }
        };
    }

    /** Ghi trạng thái lọc vào URL (không thêm lịch sử) để quay lại trang vẫn đúng bộ lọc; giá trị rỗng thì bỏ khoá. */
    function remember(updates) {
        const location = root.location, history = root.history;
        if (!location || !history || typeof history.replaceState !== 'function') return;
        const url = new URL(location.href);
        Object.entries(updates).forEach(([key, value]) => {
            if (value) url.searchParams.set(key, value); else url.searchParams.delete(key);
        });
        history.replaceState(history.state, '', url);
    }

    function setChoice(document, select, group, selected) {
        select.querySelector('[data-combined-filter]')?.remove();
        if (group.multiple && selected.length > 1) {
            const combinedOption = document.createElement('option');
            combinedOption.value = selected.join(',');
            combinedOption.textContent = `${selected.length} ${group.unit}`;
            combinedOption.dataset.combinedFilter = 'true';
            select.appendChild(combinedOption);
        }
        select.value = group.multiple ? selected.join(',') : selected[0] || 'popular';
    }

    function mount(document) {
        const toggle = document.getElementById('library-filter-toggle');
        const panel = document.getElementById('library-filter-panel');
        if (!toggle || !panel) return;
        const groups = [
            { id: 'category-filter', param: 'category', label: 'Hình thức', multiple: true, unit: 'hình thức' },
            { id: 'genre-filter', param: 'genre', label: 'Thể loại', multiple: true, unit: 'thể loại' },
            { id: 'level-filter', param: 'level', label: 'Cấp độ', multiple: true, unit: 'cấp độ' },
            { id: 'sort-select', param: 'sort', label: 'Sắp xếp', multiple: false }
        ];
        const fields = [];
        const count = document.getElementById('library-filter-count');
        const search = document.getElementById('search-input');

        /** Đưa chip trong popup về đúng bộ lọc đang áp dụng (bỏ các thay đổi chưa bấm "Áp dụng"). */
        function paint() {
            fields.forEach(({ select, inputs }) => {
                const selected = values(select.value);
                inputs.forEach(input => { input.checked = input.value ? selected.includes(input.value) : !selected.length; });
            });
        }

        function sync() {
            let active = 0;
            const state = { q: search.value.trim() };
            paint();
            fields.forEach(({ group, select }) => {
                const selected = values(select.value);
                active += group.multiple ? selected.length : Number(select.value !== 'popular');
                state[group.param] = select.value === 'popular' ? '' : select.value;
            });
            count.textContent = String(active);
            count.hidden = active === 0;
            toggle.setAttribute('aria-label', active ? `Bộ lọc, ${active} điều kiện đang áp dụng` : 'Bộ lọc');
            remember(state);
        }

        groups.forEach(group => {
            const select = document.getElementById(group.id);
            const field = document.createElement('fieldset');
            const legend = document.createElement('legend');
            legend.textContent = group.label;
            field.appendChild(legend);
            const choices = document.createElement('div');
            choices.className = 'library-filter-choices';
            const inputs = [];
            Array.from(select.options).forEach(option => {
                const label = document.createElement('label');
                const input = document.createElement('input');
                input.type = group.multiple ? 'checkbox' : 'radio';
                input.name = 'mobile-' + group.id;
                input.value = option.value;
                const text = document.createElement('span');
                text.textContent = option.textContent;
                label.append(input, text);
                inputs.push(input);
                // Chỉ đổi lựa chọn nháp trong popup; danh sách chỉ tải lại khi bấm "Áp dụng".
                input.addEventListener('change', () => {
                    if (!group.multiple) return;
                    const all = inputs.find(item => !item.value);
                    if (!input.value) inputs.forEach(item => { item.checked = !item.value; });
                    else if (all) all.checked = !inputs.some(item => item.value && item.checked);
                });
                choices.appendChild(label);
            });
            field.appendChild(choices);
            panel.appendChild(field);
            fields.push({ group, select, inputs, known: inputs.map(input => input.value).filter(Boolean) });
            select.addEventListener('change', sync);
        });

        const footer = document.createElement('div');
        footer.className = 'library-filter-footer';
        const apply = document.createElement('button');
        apply.type = 'button';
        apply.id = 'library-filter-apply';
        apply.className = 'button-primary';
        apply.textContent = 'Áp dụng';
        footer.appendChild(apply);
        panel.appendChild(footer);

        function close(restoreFocus) {
            panel.hidden = true;
            toggle.setAttribute('aria-expanded', 'false');
            paint();
            if (restoreFocus) toggle.focus();
        }
        toggle.addEventListener('click', () => {
            if (!panel.hidden) { close(false); return; }
            paint();
            panel.hidden = false;
            toggle.setAttribute('aria-expanded', 'true');
        });
        document.getElementById('library-filter-close').addEventListener('click', () => close(true));
        // "Đặt lại" chỉ xoá lựa chọn nháp; người dùng vẫn phải bấm "Áp dụng".
        document.getElementById('library-filter-reset').addEventListener('click', () => {
            fields.forEach(({ group, inputs }) => inputs.forEach(input => {
                input.checked = group.multiple ? !input.value : input.value === 'popular';
            }));
        });
        // Ghi toàn bộ lựa chọn vào các <select> rồi phát đúng một sự kiện change → chỉ một request cho cả bộ lọc.
        apply.addEventListener('click', () => {
            fields.forEach(({ group, select, inputs }) => {
                const checked = inputs.filter(input => input.checked && input.value).map(input => input.value);
                setChoice(document, select, group, checked);
            });
            document.getElementById('sort-select').dispatchEvent(new Event('change', { bubbles: true }));
            close(true);
        });
        document.addEventListener('keydown', event => { if (event.key === 'Escape' && !panel.hidden) close(true); });
        document.addEventListener('pointerdown', event => {
            if (!panel.hidden && !panel.contains(event.target) && !toggle.contains(event.target)) close(false);
        });
        document.addEventListener('focusin', event => {
            if (!panel.hidden && !panel.contains(event.target) && !toggle.contains(event.target)) close(false);
        });
        const placeholder = search.placeholder;
        const mobile = root.matchMedia('(max-width: 900px)');
        const adapt = () => { search.placeholder = mobile.matches ? 'Tìm video, câu thoại…' : placeholder; };
        mobile.addEventListener('change', adapt);
        adapt();
        // Chạy trước library.js: lấy bộ lọc từ URL để danh sách tải lần đầu khớp với ô lọc khi quay lại trang.
        const params = new URLSearchParams(root.location?.search || '');
        search.value = params.get('q') || '';
        fields.forEach(({ group, select, known }) => {
            setChoice(document, select, group, values(params.get(group.param)).filter(value => known.includes(value)));
        });
        search.addEventListener('input', sync);
        sync();
    }

    root.ShadowingLibraryUi = { values, compactTitle, compactDescription, ratingHtml, pageNumbers, createPager, mount, remember };
    if (typeof module === 'object' && module.exports) module.exports = root.ShadowingLibraryUi;
    if (typeof document !== 'undefined') document.addEventListener('DOMContentLoaded', () => mount(document));
})(typeof window !== 'undefined' ? window : globalThis);
