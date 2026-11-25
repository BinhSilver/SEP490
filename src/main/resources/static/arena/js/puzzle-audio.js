const memoryAudio = '/arena/audio/memory/';
const effects = {
    round: memoryAudio + 'flip.mp3',
    correct: memoryAudio + 'match.mp3',
    wrong: memoryAudio + 'mismatch.mp3',
    hint: memoryAudio + 'combo.mp3',
    finish: memoryAudio + 'complete.mp3',
    countdown: '/arena/app/neko/sound/countdown-beep-377323.mp3'
};

export function mountPuzzleAudio(button) {
    const storageKey = 'jlas.arena.puzzle.audio';
    let enabled = true;
    try { enabled = localStorage.getItem(storageKey) !== 'off'; } catch { /* Optional preference. */ }
    const music = new Audio(memoryAudio + 'background.mp3');
    music.loop = true;
    music.volume = 0.32;
    music.preload = 'auto';
    const icon = button.querySelector('i');
    const label = button.querySelector('span');
    const sounds = new Map();
    let unlocked = false;
    let finished = false;

    function syncMusic() {
        if (enabled && !finished && !document.hidden) {
            void music.play().catch(updateButton);
        } else {
            music.pause();
        }
    }

    function updateButton() {
        const playing = enabled && (finished || !music.paused);
        button.setAttribute('aria-pressed', String(playing));
        button.title = playing ? 'Tắt âm thanh trò chơi' : 'Bật âm thanh trò chơi';
        icon.className = 'fa-solid ' + (playing ? 'fa-volume-high' : 'fa-volume-xmark');
        label.textContent = playing ? 'Tắt nhạc' : 'Bật nhạc';
    }

    function unlock(event) {
        if (event.target instanceof Node && button.contains(event.target)) return;
        unlocked = true;
        if (music.paused) syncMusic();
    }

    music.addEventListener('playing', () => {
        unlocked = true;
        updateButton();
    });
    music.addEventListener('pause', updateButton);
    window.addEventListener('click', unlock);
    window.addEventListener('keydown', unlock);
    document.addEventListener('visibilitychange', syncMusic);
    window.addEventListener('pagehide', () => {
        music.pause();
        sounds.forEach(sound => sound.pause());
    });
    window.addEventListener('pageshow', syncMusic);

    button.addEventListener('click', () => {
        unlocked = true;
        enabled = !(enabled && (finished || !music.paused));
        try { localStorage.setItem(storageKey, enabled ? 'on' : 'off'); } catch { /* Optional preference. */ }
        if (!enabled) sounds.forEach(sound => sound.pause());
        syncMusic();
        updateButton();
    });
    updateButton();
    syncMusic();

    return {
        play(effect) {
            if (!enabled || !unlocked || document.hidden) return;
            if (!sounds.has(effect)) {
                const sound = new Audio(effects[effect]);
                sound.volume = effect === 'countdown' ? 0.26 : 0.42;
                sounds.set(effect, sound);
            }
            const sound = sounds.get(effect);
            sound.currentTime = 0;
            void sound.play().catch(() => {});
        },
        finish() {
            finished = true;
            music.pause();
            sounds.get('countdown')?.pause();
            this.play('finish');
        }
    };
}
