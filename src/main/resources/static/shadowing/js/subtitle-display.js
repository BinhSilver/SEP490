(function (root) {
    'use strict';
    const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    function furigana(value) {
        // Token boundaries in stored readings are structural, not Japanese word spaces.
        const source = String(value ?? '');
        const pattern = /([^\s()（）]+)[(（]([ぁ-ゖァ-ヺー・\s]+)[)）]/g;
        let html = '', cursor = 0, match;
        while ((match = pattern.exec(source))) {
            html += escape(source.slice(cursor, match.index));
            html += '<ruby>' + escape(match[1]) + '<rt>' + escape(match[2]) + '</rt></ruby>';
            cursor = match.index + match[0].length;
        }
        return (html + escape(source.slice(cursor))).replace(/(?<=[\u3040-\u30ff\u3400-\u9fff>。！？、])\s+(?=[\u3040-\u30ff\u3400-\u9fff<。！？、])/g, '');
    }
    function gapLabel(time, subtitles, showCountdown = false) {
        const next = subtitles.find(s => Number(s.startTime) > time);
        if (next) {
            if (!showCountdown) return '';
            const seconds = Math.max(1, Math.ceil(Number(next.startTime) - time));
            return 'Câu luyện tiếp theo sau ' + seconds + ' giây';
        }
        return subtitles.length ? 'Đã hết phần lời thoại.' : 'Chưa có phụ đề cho bài học này.';
    }
    root.ShadowingSubtitleDisplay = {escape, furigana, gapLabel};
    if (typeof module !== 'undefined') module.exports = root.ShadowingSubtitleDisplay;
})(typeof window !== 'undefined' ? window : globalThis);
