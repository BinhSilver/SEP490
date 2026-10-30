import {create} from 'zustand';

export interface VisualSettings {
  reducedMotion: boolean;
  screenShake: boolean;
  particles: 'high' | 'low' | 'off';
  showFps: boolean;
}
const STORAGE_KEY = 'jlas-air-defense-visuals';
function initialSettings(): VisualSettings {
  const defaults: VisualSettings = {reducedMotion: typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    screenShake: true, particles: 'high', showFps: false};
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
    for (const key of ['reducedMotion', 'screenShake', 'showFps'] as const) {
      if (typeof saved[key] === 'boolean') defaults[key] = saved[key];
    }
    if (['high', 'low', 'off'].includes(saved.particles)) defaults.particles = saved.particles;
  } catch { /* Unavailable storage must not stop a match. */ }
  return defaults;
}
export const useVisualSettings = create<VisualSettings & {update: (patch: Partial<VisualSettings>) => void}>((set, get) => ({
  ...initialSettings(),
  update: patch => {
    set(patch);
    const {reducedMotion, screenShake, particles, showFps} = get();
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify({reducedMotion, screenShake, particles, showFps})); } catch { /* Keep in memory. */ }
  }
}));
