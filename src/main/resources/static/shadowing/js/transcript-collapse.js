// Thu cột phụ đề thành một dải hẹp để phần video bên trái rộng hơn (chỉ màn hình có hai cột).
(function (root) {
    'use strict';

    const STORAGE_KEY = 'shadowing.transcriptCollapsed';
    const SETTLE_MS = 320; // dài hơn transition của cột trong styles.css

    function readSaved(storage) {
        try { return storage.getItem(STORAGE_KEY) === '1'; } catch (error) { return false; }
    }

    function save(storage, collapsed) {
        try { storage.setItem(STORAGE_KEY, collapsed ? '1' : '0'); } catch (error) { /* chỉ là tiện ích */ }
    }

    function init(doc, win) {
        const layout = doc.querySelector('.player-layout');
        const button = doc.getElementById('transcript-collapse-btn');
        if (!layout || !button) return;
        const column = doc.querySelector('.transcript-column');

        function render(collapsed) {
            const label = collapsed ? 'Mở rộng phụ đề' : 'Thu gọn phụ đề';
            layout.classList.toggle('transcript-collapsed', collapsed);
            button.title = label;
            button.setAttribute('aria-label', label);
            button.setAttribute('aria-expanded', String(!collapsed));
            button.textContent = collapsed ? 'Hiện' : 'Ẩn';
        }

        function revealActiveSubtitle() {
            const active = column.querySelector('.subtitle-item.active');
            if (!active) return;
            column.scrollTop += active.getBoundingClientRect().top - column.getBoundingClientRect().top
                - (column.clientHeight - active.offsetHeight) / 2;
        }

        render(readSaved(win.localStorage));
        button.addEventListener('click', function () {
            const collapsed = !layout.classList.contains('transcript-collapsed');
            layout.classList.add('transcript-animated');
            render(collapsed);
            save(win.localStorage, collapsed);
            win.setTimeout(function () {
                if (!collapsed) revealActiveSubtitle();
                win.dispatchEvent(new win.Event('resize')); // phụ đề lớn tự canh lại theo chiều rộng mới
            }, SETTLE_MS);
        });
    }

    if (typeof module !== 'undefined' && module.exports) module.exports = { init, STORAGE_KEY };
    else init(root.document, root);
})(typeof window !== 'undefined' ? window : globalThis);
