import React, { useState } from 'react';
import { soundManager } from '../audio/SoundManager';

/** Music / effects volume and mute, persisted by SoundManager. */
export const SoundSettings: React.FC = () => {
  const [config, setConfig] = useState(soundManager.getConfig());
  const update = (patch: Parameters<typeof soundManager.setConfig>[0]) => {
    soundManager.setConfig(patch);
    setConfig(soundManager.getConfig());
  };

  const slider = (id: string, label: string, value: number, onChange: (value: number) => void) => (
    <div>
      <div className="flex justify-between mb-1">
        <label htmlFor={id} className="text-xs font-semibold text-white/75">{label}</label>
        <span className="text-xs font-black text-amber-400 tabular-nums">{Math.round(value * 100)}%</span>
      </div>
      <div className="relative h-2.5 rounded-full" style={{ background: 'rgba(255,255,255,0.1)' }}>
        <div className="absolute h-full rounded-full" style={{ width: `${value * 100}%`, background: 'linear-gradient(90deg, #FF9F43, #FF6B35)' }} />
        <input
          id={id}
          type="range"
          min="0"
          max="1"
          step="0.05"
          value={value}
          onChange={event => onChange(parseFloat(event.target.value))}
          className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
        />
      </div>
    </div>
  );

  return (
    <div className="space-y-3">
      {slider('bgm-vol-slider', 'Nhạc nền', config.bgmVolume, bgmVolume => update({ bgmVolume }))}
      {slider('sfx-vol-slider', 'Hiệu ứng âm thanh', config.sfxVolume, sfxVolume => update({ sfxVolume }))}
      <button
        type="button"
        onClick={() => {
          soundManager.toggleMute();
          setConfig(soundManager.getConfig());
        }}
        aria-pressed={config.muted}
        className="w-full py-2.5 min-h-[44px] rounded-xl text-xs font-black uppercase tracking-widest transition-all mt-1 flex items-center justify-center focus-visible:ring-2 focus-visible:ring-amber-400"
        style={{
          background: config.muted ? 'rgba(220,38,38,0.25)' : 'rgba(255,255,255,0.08)',
          border: `1px solid ${config.muted ? 'rgba(220,38,38,0.5)' : 'rgba(255,255,255,0.15)'}`,
          color: config.muted ? '#FCA5A5' : 'rgba(255,255,255,0.75)',
        }}
      >
        {config.muted ? 'Đang tắt tiếng · bấm để bật' : 'Đang bật tiếng · bấm để tắt'}
      </button>
    </div>
  );
};
