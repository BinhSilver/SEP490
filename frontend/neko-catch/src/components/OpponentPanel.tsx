import React from 'react';
import type { NekoPlayerView } from '../arena/types';
import { DISPLAY_FONT_STACK } from '../lib/text';
import { Avatar } from './Avatar';
import { HeartIcon as Heart } from './Icons';

export function finishReasonLabel(reason: NekoPlayerView['finishReason']): string {
  switch (reason) {
    case 'LIVES':
      return 'Hết tim';
    case 'TIME':
      return 'Hết giờ';
    case 'FORFEIT':
      return 'Rời trận';
    default:
      return '';
  }
}

/** Live HUD of the other VERSUS player, fed by NEKO_PROGRESS + SESSION_PRESENCE. */
export const OpponentPanel: React.FC<{ opponent: NekoPlayerView; compact?: boolean }> = ({ opponent, compact = false }) => {
  const reason = finishReasonLabel(opponent.finishReason);
  const hearts = Math.max(0, opponent.maxLives);
  return (
    <div
      className={`flex items-center gap-2.5 rounded-2xl pointer-events-auto ${compact ? 'px-3 py-2' : 'px-3.5 py-2.5'}`}
      style={{
        background: 'rgba(10,5,30,0.72)',
        backdropFilter: 'blur(12px)',
        border: '1px solid rgba(125,211,252,0.35)',
        boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
      }}
      role="group"
      aria-label={`Đối thủ ${opponent.displayName}: ${opponent.score} điểm, còn ${opponent.lives} tim${
        opponent.finished ? ', đã xong' : ''
      }${opponent.online ? '' : ', mất kết nối'}`}
    >
      <div className="relative">
        <Avatar name={opponent.displayName} url={opponent.avatarUrl} size={compact ? 30 : 34} ring="rgba(125,211,252,0.6)" />
        <span
          aria-hidden="true"
          className={`absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-slate-950 ${
            opponent.online ? 'bg-emerald-400' : 'bg-slate-500'
          }`}
        />
      </div>
      <div className="min-w-0 flex flex-col">
        <div className="flex items-center gap-1.5 min-w-0">
          <span className="text-[10px] font-black uppercase tracking-wider text-sky-300/90 shrink-0">Đối thủ</span>
          <span className="text-xs font-bold text-white truncate max-w-[120px]" title={opponent.displayName}>
            {opponent.displayName}
          </span>
        </div>
        <div className="flex items-center gap-2 mt-0.5">
          <span className="text-lg font-black text-white leading-none tabular-nums" style={{ fontFamily: DISPLAY_FONT_STACK }}>
            {opponent.score.toString().padStart(5, '0')}
          </span>
          <span className="flex items-center gap-0.5" aria-hidden="true">
            {Array.from({ length: hearts }).map((_, i) => (
              <Heart key={i} className={`w-3.5 h-3.5 ${i < opponent.lives ? 'text-red-400' : 'text-white/15'}`} />
            ))}
          </span>
          {opponent.combo >= 2 && !opponent.finished && (
            <span className="text-[10px] font-black text-white px-1.5 py-0.5 rounded-md bg-gradient-to-r from-rose-500 to-orange-500">
              ×{opponent.combo}
            </span>
          )}
        </div>
        {(opponent.finished || !opponent.online) && (
          <div className="flex flex-wrap gap-1 mt-1">
            {opponent.finished && (
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-md bg-emerald-500/20 border border-emerald-400/40 text-emerald-200">
                Đã xong{reason ? ` · ${reason}` : ''}
              </span>
            )}
            {!opponent.online && (
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-md bg-slate-500/30 border border-slate-400/40 text-slate-200">
                Mất kết nối
              </span>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
