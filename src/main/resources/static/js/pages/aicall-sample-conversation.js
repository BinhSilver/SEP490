/* Local study preferences apply to existing and newly revealed sample messages. */
(function () {
    'use strict';
    const page = document.querySelector('.sample-conversation-page');
    if (!page) return;
    const chat = page.querySelector('[data-sample-chat]');
    const rows = Array.from(chat.querySelectorAll('[data-sample-role]'));
    const shell = page.querySelector('.sample-conversation-shell');
    function resizeFrame() {
        shell.style.setProperty('--sample-shell-height', Math.max(420, innerHeight - shell.getBoundingClientRect().top) + 'px');
        chat.scrollTop = chat.scrollHeight;
    }
    resizeFrame();
    window.addEventListener('resize', resizeFrame);
    const headerMount = document.getElementById('jlabBaseShellMount');
    if (headerMount && window.ResizeObserver) new ResizeObserver(resizeFrame).observe(headerMount);

    function pressed(button, enabled) {
        button.setAttribute('aria-pressed', String(enabled));
        button.classList.toggle(button.classList.contains('switch') ? 'on' : 'active', enabled);
    }
    for (const [control, preference] of [['furigana', 'furigana'], ['translation-toggle', 'translation']]) {
        const button = page.querySelector('[data-sample-' + control + ']');
        const key = 'jlas.sample.' + control;
        let enabled = button.getAttribute('aria-pressed') === 'true';
        try { const saved = localStorage.getItem(key); if (saved !== null) enabled = saved === 'true'; } catch (_) { /* Storage can be disabled. */ }
        function apply() {
            pressed(button, enabled);
            page.classList.toggle('show-' + preference, enabled);
            rows.forEach(row => {
                row.classList.remove('sample-show-' + preference, 'sample-hide-' + preference);
                pressed(row.querySelector('[data-sample-line-' + preference + ']'), enabled);
            });
            chat.scrollTop = chat.scrollHeight;
        }
        button.addEventListener('click', () => {
            enabled = !enabled;
            apply();
            try { localStorage.setItem(key, String(enabled)); } catch (_) { /* Keep the in-page preference. */ }
        });
        rows.forEach(row => {
            const lineButton = row.querySelector('[data-sample-line-' + preference + ']');
            lineButton.addEventListener('click', () => {
                const visible = lineButton.getAttribute('aria-pressed') !== 'true';
                pressed(lineButton, visible);
                row.classList.toggle('sample-show-' + preference, visible);
                row.classList.toggle('sample-hide-' + preference, !visible);
                chat.scrollTop = chat.scrollHeight;
            });
        });
        apply();
    }
    rows.forEach(row => {
        const copy = row.querySelector('[data-sample-copy]');
        copy.addEventListener('click', async () => {
            try {
                await navigator.clipboard.writeText(row.querySelector('[data-sample-text]').dataset.sampleText);
                copy.title = 'Đã sao chép';
            } catch (_) { copy.title = 'Chưa sao chép được. Hãy chọn và sao chép nội dung câu.'; }
        });
    });
    const frame = page.querySelector('[data-sample-background]');
    const url = frame?.dataset.sampleBackground;
    if (url && /^\/images\/[a-zA-Z0-9/_.-]+\.(webp|png|jpg)$/.test(url)) frame.style.setProperty('--scenario-chat-background-image', 'url("' + url + '")');
})();
