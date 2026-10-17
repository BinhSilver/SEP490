/* Sentence-by-sentence listening: microphone access always needs a user click. */
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.ShadowingListenRecord = api;
}(typeof window === 'object' ? window : globalThis, function () {
    'use strict';

    function recordingSeconds(context, rate) {
        const duration = Number(context?.endTime) - Number(context?.startTime);
        const speed = Number(rate) > 0 ? Number(rate) : 1;
        return Number.isFinite(duration) && duration > 0 ? duration / speed + 3 : null;
    }

    function create(options) {
        const video = options.video;
        let enabled = false;
        let context = null;
        let phase = 'off';
        let message = '';
        let generation = 0;
        const busy = () => ['requesting', 'recording', 'assessing'].includes(phase);
        const publish = () => options.onChange?.({ enabled, context, phase, message, busy: busy() });
        function update(nextPhase, text, force = false) {
            if (!force && phase === nextPhase && message === text) return;
            phase = nextPhase;
            message = text;
            publish();
        }
        function cancel() {
            generation++;
            options.cancelRecording?.();
        }
        function select(next) {
            if (String(context?.subtitleId) === String(next?.subtitleId)) return;
            if (enabled || busy()) cancel();
            context = next;
            update(enabled ? (video.paused ? 'ready' : 'listening') : 'off', enabled
                ? 'Nghe câu mẫu, rồi bấm mic để đọc. Bản thu sẽ tự chấm.' : '', true);
        }
        function seek(next) {
            if (busy()) return false;
            if (enabled) cancel();
            context = next;
            update(enabled ? (video.paused ? 'ready' : 'listening') : 'off', enabled
                ? 'Đã chuyển đoạn luyện. Nghe câu mẫu rồi bấm mic để đọc.' : '', true);
            return true;
        }
        function setEnabled(value) {
            if (enabled === Boolean(value)) return;
            cancel();
            enabled = Boolean(value);
            video.pause();
            update(enabled ? 'ready' : 'off', enabled
                ? 'Bấm Phát để nghe từng câu. Sau đó bấm mic; bạn có thêm 3 giây để đọc và bản thu sẽ tự chấm.' : '');
        }
        function beforePlay() {
            if (busy()) { video.pause(); return false; }
            if (!enabled) return true;
            if (context && ['waiting', 'result', 'error'].includes(phase)) {
                video.currentTime = Number(context.startTime);
            }
            update('listening', 'Đang nghe mẫu · hết câu sẽ dừng để bạn thu âm.');
            return true;
        }
        function timeUpdate() {
            if (!enabled || !context) return null;
            if (phase === 'listening' && (video.ended || video.currentTime >= Number(context.endTime))) {
                // Hold the completed sentence even when timeupdate skips into a gap/the next cue.
                update('waiting', 'Bấm mic để đọc câu #' + context.sentenceNumber + ' · tự dừng và chấm sau khi thu.');
                video.pause();
                video.currentTime = Math.max(Number(context.startTime), Number(context.endTime) - 0.001);
            }
            return video.paused && ['waiting', 'requesting', 'recording', 'assessing', 'result', 'error'].includes(phase) ? context : null;
        }
        async function record() {
            if (!context || busy()) return;
            video.pause();
            const seconds = recordingSeconds(context, video.playbackRate);
            if (seconds === null || seconds > (options.maxSeconds || 30)) {
                update('error', 'Câu này cần hơn 30 giây để thu. Tăng tốc độ nghe để vẫn dành đủ 3 giây đọc thêm, rồi bấm mic lại.');
                return;
            }
            const operation = ++generation;
            update('requesting', 'Đang xin quyền dùng micro…');
            try {
                const result = await options.record(context.sentenceNumber - 1, {
                    durationSeconds: seconds,
                    playReference: false,
                    autoAssess: true,
                    onState(nextPhase, remaining) {
                        if (operation !== generation) return;
                        update(nextPhase, nextPhase === 'recording'
                            ? 'Đang thu · còn ' + Math.ceil(remaining) + ' giây · tự chấm khi hết giờ.'
                            : 'Đang chấm phát âm…');
                    }
                });
                if (operation !== generation) return;
                if (result?.status === 'completed') {
                    const score = result.attempt?.pronunciationScore;
                    const label = score !== null && score !== undefined && Number.isFinite(Number(score))
                        ? Math.round(Number(score)) + '/100' : 'đã có kết quả';
                    update('result', 'Câu #' + context.sentenceNumber + ': ' + label + ' · xem góp ý bên dưới hoặc bấm Câu tiếp theo.');
                } else update('error', result?.message || 'Chưa chấm được bản thu. Hãy thử lại.');
            } catch (_) {
                if (operation === generation) update('error', 'Không thể thu âm. Hãy kiểm tra quyền micro rồi thử lại.');
            }
        }
        return { select, seek, setEnabled, beforePlay, timeUpdate, record, busy,
            isEnabled: () => enabled,
            cancel() { cancel(); update(enabled ? 'ready' : 'off', enabled
                ? 'Bản thu đã dừng. Bấm Phát để nghe lại hoặc bấm mic để thu.' : ''); }
        };
    }
    return { create, recordingSeconds };
}));
