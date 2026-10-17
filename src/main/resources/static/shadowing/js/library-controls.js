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

    function mount(document) {
        const toggle = document.getElementById('library-filter-toggle');
        const panel = document.getElementById('library-filter-panel');
        if (!toggle || !panel) return;
        const groups = [
            { id: 'category-filter', label: 'Thể loại', multiple: true, unit: 'thể loại' },
            { id: 'level-filter', label: 'Cấp độ', multiple: true, unit: 'cấp độ' },
            { id: 'sort-select', label: 'Sắp xếp', multiple: false }
        ];
        const fields = [];
        const count = document.getElementById('library-filter-count');

        function sync() {
            let active = 0;
            fields.forEach(({ group, select, inputs }) => {
                const selected = values(select.value);
                inputs.forEach(input => { input.checked = input.value ? selected.includes(input.value) : !selected.length; });
                active += group.multiple ? selected.length : Number(select.value !== 'popular');
            });
            count.textContent = String(active);
            count.hidden = active === 0;
            toggle.setAttribute('aria-label', active ? `Bộ lọc, ${active} điều kiện đang áp dụng` : 'Bộ lọc');
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
                input.addEventListener('change', () => {
                    let selected = values(select.value);
                    if (!group.multiple) selected = [input.value];
                    else if (!input.value) selected = [];
                    else selected = input.checked ? [...new Set([...selected, input.value])] : selected.filter(v => v !== input.value);
                    const combined = selected.join(',');
                    select.querySelector('[data-combined-filter]')?.remove();
                    if (selected.length > 1) {
                        const combinedOption = document.createElement('option');
                        combinedOption.value = combined;
                        combinedOption.textContent = `${selected.length} ${group.unit}`;
                        combinedOption.dataset.combinedFilter = 'true';
                        select.appendChild(combinedOption);
                    }
                    select.value = combined;
                    select.dispatchEvent(new Event('change', { bubbles: true }));
                });
                choices.appendChild(label);
            });
            field.appendChild(choices);
            panel.appendChild(field);
            fields.push({ group, select, inputs });
            select.addEventListener('change', sync);
        });

        function close(restoreFocus) {
            panel.hidden = true;
            toggle.setAttribute('aria-expanded', 'false');
            if (restoreFocus) toggle.focus();
        }
        toggle.addEventListener('click', () => {
            panel.hidden = !panel.hidden;
            toggle.setAttribute('aria-expanded', String(!panel.hidden));
        });
        document.getElementById('library-filter-close').addEventListener('click', () => close(true));
        document.getElementById('library-filter-reset').addEventListener('click', () => {
            fields.forEach(({ group, select }) => {
                select.querySelector('[data-combined-filter]')?.remove();
                select.value = group.multiple ? '' : 'popular';
            });
            document.getElementById('sort-select').dispatchEvent(new Event('change', { bubbles: true }));
        });
        document.addEventListener('keydown', event => { if (event.key === 'Escape' && !panel.hidden) close(true); });
        document.addEventListener('pointerdown', event => {
            if (!panel.hidden && !panel.contains(event.target) && !toggle.contains(event.target)) close(false);
        });
        document.addEventListener('focusin', event => {
            if (!panel.hidden && !panel.contains(event.target) && !toggle.contains(event.target)) close(false);
        });
        const search = document.getElementById('search-input');
        const placeholder = search.placeholder;
        const mobile = root.matchMedia('(max-width: 900px)');
        const adapt = () => { search.placeholder = mobile.matches ? 'Tìm video, câu thoại…' : placeholder; if (!mobile.matches) close(false); };
        mobile.addEventListener('change', adapt);
        adapt();
        sync();
    }

    root.ShadowingLibraryUi = { values, compactTitle, compactDescription, ratingHtml, pageNumbers, createPager, mount };
    if (typeof module === 'object' && module.exports) module.exports = root.ShadowingLibraryUi;
    if (typeof document !== 'undefined') document.addEventListener('DOMContentLoaded', () => mount(document));
})(typeof window !== 'undefined' ? window : globalThis);
