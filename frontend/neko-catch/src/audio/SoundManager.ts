import { assetUrl } from '../assets';
import { SoundConfig } from '../types/game';

type LockListener = (locked: boolean) => void;

class SoundManager {
  private static readonly STORAGE_KEY = 'neko_catch_sound_config';
  private bgm: HTMLAudioElement | null = null;
  private sounds: Map<string, HTMLAudioElement> = new Map();
  private audioCtx: AudioContext | null = null;
  private config: SoundConfig = this.loadConfig();
  /** The game wants background music right now (between match start and finish). */
  private wantBgm = false;
  /** The browser refused autoplay; audio starts on the next user gesture. */
  private locked = false;
  private lockListeners = new Set<LockListener>();

  constructor() {
    this.init();
  }

  private loadConfig(): SoundConfig {
    const defaults: SoundConfig = { bgmVolume: 0.4, sfxVolume: 0.7, muted: false };
    if (typeof window === 'undefined') return defaults;
    try {
      const saved = window.localStorage.getItem(SoundManager.STORAGE_KEY);
      if (!saved) return defaults;
      const parsed = JSON.parse(saved) as Partial<SoundConfig>;
      return {
        bgmVolume: typeof parsed.bgmVolume === 'number' ? parsed.bgmVolume : defaults.bgmVolume,
        sfxVolume: typeof parsed.sfxVolume === 'number' ? parsed.sfxVolume : defaults.sfxVolume,
        muted: typeof parsed.muted === 'boolean' ? parsed.muted : defaults.muted,
      };
    } catch {
      return defaults;
    }
  }

  private init() {
    if (typeof window === 'undefined') return;

    // Sound files live in static/arena/app/neko/sound and are served next to the bundle (Vite base URL).
    const audioFiles = [
      { key: 'bgm', path: '/sound/bgm.mp3', loop: true },
      { key: 'correct', path: '/sound/correct.mp3' },
      { key: 'wrong', path: '/sound/wrong.mp3' },
      { key: 'impact', path: '/sound/impact.mp3' },
      { key: 'catch', path: '/sound/catch.mp3' },
      { key: 'levelup', path: '/sound/levelup.mp3' },
      { key: 'countdown', path: '/sound/countdown-beep-377323.mp3' },
      { key: 'victory', path: '/sound/victory.mp3' },
      { key: 'click', path: '/sound/click.mp3' },
      { key: 'freeze', path: '/sound/sfx_freeze_spread.mp3' },
    ];

    audioFiles.forEach(({ key, path, loop }) => {
      const audio = new Audio(assetUrl(path));
      audio.preload = key === 'bgm' ? 'metadata' : 'auto';
      if (loop) audio.loop = true;
      this.sounds.set(key, audio);
    });

    this.bgm = this.sounds.get('bgm') || null;

    // Browsers only allow audio after a user gesture. The match starts by itself (server countdown),
    // so the first click / key / touch unlocks the audio context and starts the pending music.
    const unlock = () => this.unlock();
    window.addEventListener('pointerdown', unlock, true);
    window.addEventListener('keydown', unlock, true);
    window.addEventListener('touchstart', unlock, true);
  }

  private setLocked(locked: boolean) {
    if (this.locked === locked) return;
    this.locked = locked;
    this.lockListeners.forEach(listener => listener(locked));
  }

  /** Called on any user gesture: resumes Web Audio and retries music the browser blocked. */
  public unlock() {
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume().catch(() => {});
    }
    if (this.wantBgm && !this.config.muted && this.bgm && this.bgm.paused) {
      this.bgm.volume = this.config.bgmVolume;
      this.bgm.play().then(() => this.setLocked(false)).catch(() => {});
    } else if (this.locked) {
      this.setLocked(false);
    }
  }

  public isLocked(): boolean {
    return this.locked && !this.config.muted;
  }

  public onLockChange(listener: LockListener): () => void {
    this.lockListeners.add(listener);
    return () => this.lockListeners.delete(listener);
  }

  private getAudioContext(): AudioContext | null {
    if (typeof window === 'undefined') return null;
    if (!this.audioCtx) {
      const AudioCtxClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (AudioCtxClass) {
        this.audioCtx = new AudioCtxClass();
      }
    }
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume().catch(() => {});
    }
    return this.audioCtx;
  }

  // ─────────────────────────────────────────────────────────
  // SYNTHESIZE CUTE CAT MEOW SOUNDS (Web Audio API)
  // ─────────────────────────────────────────────────────────
  public playCatMeow(type: 'happy' | 'sad' | 'combo' | 'chirp' | 'victory') {
    if (this.config.muted) return;
    const ctx = this.getAudioContext();
    if (!ctx) return;

    const masterGain = ctx.createGain();
    masterGain.gain.value = this.config.sfxVolume;
    masterGain.connect(ctx.destination);

    const now = ctx.currentTime;

    if (type === 'happy') {
      // 🐱 Happy Meow ("Nyaa~! ✨")
      const osc = ctx.createOscillator();
      const osc2 = ctx.createOscillator();
      const filter = ctx.createBiquadFilter();
      const gain = ctx.createGain();

      osc.type = 'triangle';
      osc2.type = 'sine';

      // Pitch glide: 520Hz -> 880Hz -> 460Hz
      osc.frequency.setValueAtTime(520, now);
      osc.frequency.exponentialRampToValueAtTime(880, now + 0.12);
      osc.frequency.exponentialRampToValueAtTime(460, now + 0.28);

      osc2.frequency.setValueAtTime(1040, now);
      osc2.frequency.exponentialRampToValueAtTime(1760, now + 0.12);
      osc2.frequency.exponentialRampToValueAtTime(920, now + 0.28);

      filter.type = 'bandpass';
      filter.frequency.setValueAtTime(600, now);
      filter.frequency.linearRampToValueAtTime(1600, now + 0.12);
      filter.frequency.linearRampToValueAtTime(500, now + 0.28);
      filter.Q.value = 3.5;

      gain.gain.setValueAtTime(0.01, now);
      gain.gain.linearRampToValueAtTime(0.4, now + 0.05);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);

      osc.connect(filter);
      osc2.connect(filter);
      filter.connect(gain);
      gain.connect(masterGain);

      osc.start(now);
      osc2.start(now);
      osc.stop(now + 0.32);
      osc2.stop(now + 0.32);

      // Layer cute chime bell
      const chime = ctx.createOscillator();
      const chimeGain = ctx.createGain();
      chime.type = 'sine';
      chime.frequency.setValueAtTime(1318.51, now + 0.05); // E6
      chimeGain.gain.setValueAtTime(0.2, now + 0.05);
      chimeGain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);
      chime.connect(chimeGain);
      chimeGain.connect(masterGain);
      chime.start(now + 0.05);
      chime.stop(now + 0.35);

    } else if (type === 'sad') {
      // 😿 Sad Meow ("Mroww... 😿")
      const osc = ctx.createOscillator();
      const filter = ctx.createBiquadFilter();
      const gain = ctx.createGain();

      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(420, now);
      osc.frequency.exponentialRampToValueAtTime(260, now + 0.38);

      filter.type = 'lowpass';
      filter.frequency.setValueAtTime(700, now);
      filter.frequency.linearRampToValueAtTime(300, now + 0.38);

      gain.gain.setValueAtTime(0.01, now);
      gain.gain.linearRampToValueAtTime(0.35, now + 0.06);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.4);

      osc.connect(filter);
      filter.connect(gain);
      gain.connect(masterGain);

      osc.start(now);
      osc.stop(now + 0.42);

    } else if (type === 'combo') {
      // 🐾 Double Meow ("Nya-Nyaa~! 🐾🎉")
      [0, 0.14].forEach((delay, idx) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'triangle';

        const baseF = idx === 0 ? 600 : 750;
        const peakF = idx === 0 ? 950 : 1200;

        osc.frequency.setValueAtTime(baseF, now + delay);
        osc.frequency.exponentialRampToValueAtTime(peakF, now + delay + 0.08);
        osc.frequency.exponentialRampToValueAtTime(baseF * 0.8, now + delay + 0.18);

        gain.gain.setValueAtTime(0.01, now + delay);
        gain.gain.linearRampToValueAtTime(0.4, now + delay + 0.03);
        gain.gain.exponentialRampToValueAtTime(0.001, now + delay + 0.2);

        osc.connect(gain);
        gain.connect(masterGain);

        osc.start(now + delay);
        osc.stop(now + delay + 0.22);
      });

    } else if (type === 'chirp') {
      // 🐾 Short Cat Chirp ("Mew!")
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(650, now);
      osc.frequency.exponentialRampToValueAtTime(920, now + 0.08);

      gain.gain.setValueAtTime(0.3, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.1);

      osc.connect(gain);
      gain.connect(masterGain);

      osc.start(now);
      osc.stop(now + 0.12);

    } else if (type === 'victory') {
      // 🐱 Chorus of Victory Meows (C5 -> E5 -> G5 -> C6)
      const notes = [523.25, 659.25, 783.99, 1046.50];
      notes.forEach((freq, idx) => {
        const delay = idx * 0.12;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'triangle';

        osc.frequency.setValueAtTime(freq * 0.9, now + delay);
        osc.frequency.exponentialRampToValueAtTime(freq * 1.15, now + delay + 0.08);
        osc.frequency.exponentialRampToValueAtTime(freq, now + delay + 0.2);

        gain.gain.setValueAtTime(0.01, now + delay);
        gain.gain.linearRampToValueAtTime(0.4, now + delay + 0.04);
        gain.gain.exponentialRampToValueAtTime(0.001, now + delay + 0.28);

        osc.connect(gain);
        gain.connect(masterGain);

        osc.start(now + delay);
        osc.stop(now + delay + 0.3);
      });
    }
  }

  public playBGM() {
    this.wantBgm = true;
    if (this.config.muted || !this.bgm) return;
    this.bgm.volume = this.config.bgmVolume;
    this.bgm.play().then(() => this.setLocked(false)).catch((error: DOMException) => {
      if (error && error.name === 'NotAllowedError') this.setLocked(true);
    });
  }

  public pauseBGM() {
    this.wantBgm = false;
    if (this.bgm) this.bgm.pause();
  }

  public stopBGM() {
    this.wantBgm = false;
    if (this.bgm) {
      this.bgm.pause();
      try {
        this.bgm.currentTime = 0;
      } catch {
        // Metadata not loaded yet; nothing to rewind.
      }
    }
  }

  public playSFX(key: string) {
    if (this.config.muted) return;

    // Direct playback for film countdown sound
    if (key === 'countdown') {
      const baseAudio = this.sounds.get('countdown');
      if (baseAudio) {
        const clone = baseAudio.cloneNode() as HTMLAudioElement;
        clone.volume = this.config.sfxVolume;
        clone.currentTime = 0;
        clone.play().catch(() => {});
      }
      return;
    }

    // Trigger cat sound synthesis
    if (key === 'correct') {
      this.playCatMeow('happy');
    } else if (key === 'wrong') {
      this.playCatMeow('sad');
    } else if (key === 'levelup') {
      this.playCatMeow('combo');
    } else if (key === 'click') {
      this.playCatMeow('chirp');
    } else if (key === 'victory') {
      this.playCatMeow('victory');
    }

    // Also play audio file if available as layer
    const baseAudio = this.sounds.get(key);
    if (baseAudio) {
      const clone = baseAudio.cloneNode() as HTMLAudioElement;
      clone.volume = this.config.sfxVolume * 0.85; // Crisp audible volume for user's sound effects
      clone.play().catch(() => {});
    }
  }

  public setConfig(newConfig: Partial<SoundConfig>) {
    this.config = { ...this.config, ...newConfig };
    try {
      window.localStorage.setItem(SoundManager.STORAGE_KEY, JSON.stringify(this.config));
    } catch {
      // Storage can be unavailable in private/embedded browser contexts.
    }
    if (this.bgm) {
      this.bgm.volume = this.config.bgmVolume;
      if (this.config.muted) this.bgm.pause();
      else if (this.wantBgm && this.bgm.paused) this.playBGM();
    }
    if (this.config.muted) this.setLocked(false);
  }

  public getConfig(): SoundConfig {
    return { ...this.config };
  }

  public toggleMute(): boolean {
    this.config.muted = !this.config.muted;
    this.setConfig({ muted: this.config.muted });
    return this.config.muted;
  }
}

export const soundManager = new SoundManager();
