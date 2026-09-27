import React, { useEffect, useRef, useState } from 'react';
import type { PlayMode } from '../arena/types';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { DISPLAY_FONT_STACK } from '../lib/text';
import { HelpCircleIcon as HelpCircle, PlayIcon as Play, SettingsIcon as Settings, XIcon as X } from './Icons';
import { SoundSettings } from './Settings';

interface MenuModalProps {
  open: boolean;
  mode: PlayMode;
  /** SOLO match is paused on the server (resume runs the 3-2-1 countdown). */
  paused: boolean;
  /** The local player already finished (leaving no longer changes the result). */
  finished: boolean;
  /** Close button / Escape: SOLO resumes, VERSUS just hides the menu. */
  onClose: () => void;
  onHelp: () => void;
  onLeave: () => Promise<void>;
  /** Another dialog (help) is stacked on top: stop trapping keys until it closes. */
  suspended?: boolean;
}

/** Settings / pause menu. SOLO: real pause through the API. VERSUS: an overlay only, the match keeps running. */
export const MenuModal: React.FC<MenuModalProps> = ({
  open,
  mode,
  paused,
  finished,
  onClose,
  onHelp,
  onLeave,
  suspended = false,
}) => {
  const ref = useRef<HTMLDivElement | null>(null);
  const stayRef = useRef<HTMLButtonElement | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [leaveError, setLeaveError] = useState<string | null>(null);
  const versus = mode === 'VERSUS';

  useFocusTrap(ref, open && !suspended, () => {
    if (leaving) return;
    if (confirming) setConfirming(false);
    else onClose();
  });

  useEffect(() => {
    if (!open) {
      setConfirming(false);
      setLeaveError(null);
    }
  }, [open]);

  useEffect(() => {
    if (confirming) window.setTimeout(() => stayRef.current?.focus(), 30);
  }, [confirming]);

  if (!open) return null;

  const title = versus ? 'Menu trận đấu' : paused ? 'Tạm dừng' : 'Cài đặt';
  const confirmText = finished
    ? 'Bạn đã hoàn thành lượt chơi. Rời đi bây giờ không làm thay đổi kết quả của bạn.'
    : versus
      ? 'Rời trận khi đang đấu sẽ bị tính là THUA. Bạn chắc chắn muốn rời?'
      : 'Lượt luyện tập này sẽ kết thúc ngay và không được tính là hoàn thành. Bạn chắc chắn muốn rời?';

  const leave = async () => {
    setLeaving(true);
    setLeaveError(null);
    try {
      await onLeave();
    } catch (error) {
      setLeaveError(error instanceof Error && error.message ? error.message : 'Không rời trận được, thử lại.');
      setLeaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-md animate-fade-in">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby="menu-modal-title"
        className="p-5 sm:p-6 rounded-3xl w-full max-w-md max-h-[calc(100dvh-2rem)] overflow-y-auto custom-scrollbar"
        style={{
          background: 'linear-gradient(160deg, rgba(30,15,70,0.98), rgba(15,5,40,0.99))',
          border: '2px solid rgba(255,159,67,0.4)',
          boxShadow: '0 0 60px rgba(255,159,67,0.3)',
        }}
      >
        <div className="flex items-center justify-between pb-3 mb-4 border-b border-white/10">
          <div className="flex items-center gap-2">
            <Settings className="w-5 h-5 text-amber-400" />
            <h3 id="menu-modal-title" className="text-lg font-black text-white uppercase tracking-widest" style={{ fontFamily: DISPLAY_FONT_STACK }}>
              {title}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={leaving}
            aria-label={versus ? 'Đóng menu, quay lại trận' : 'Đóng menu và tiếp tục'}
            className="w-11 h-11 rounded-xl flex items-center justify-center bg-white/10 hover:bg-white/20 transition-all focus-visible:ring-2 focus-visible:ring-amber-400 shrink-0 disabled:opacity-40"
          >
            <X className="w-5 h-5 text-white/70" />
          </button>
        </div>

        {versus && !finished && (
          <p className="mb-4 text-xs font-semibold text-sky-100 bg-sky-500/15 border border-sky-400/30 rounded-xl px-3 py-2" role="note">
            Trận đối kháng không thể tạm dừng — cá vẫn rơi và đồng hồ vẫn chạy trong lúc mở menu.
          </p>
        )}

        <div className="space-y-5">
          <div>
            <p className="block text-xs font-bold text-amber-300/80 uppercase tracking-widest mb-2">Âm thanh</p>
            <SoundSettings />
          </div>

          {!confirming ? (
            <div className="pt-3 border-t border-white/10 flex flex-wrap gap-2">
              <button
                type="button"
                data-autofocus
                onClick={onClose}
                className="flex-1 min-w-[140px] py-3.5 min-h-[44px] rounded-2xl font-black text-white text-sm flex items-center justify-center gap-2 uppercase tracking-widest transition-all hover:scale-[1.02] focus-visible:ring-2 focus-visible:ring-amber-300"
                style={{ fontFamily: DISPLAY_FONT_STACK, background: 'linear-gradient(135deg, #2ECC71, #27AE60)', boxShadow: '0 0 20px rgba(46,204,113,0.4)' }}
              >
                <Play className="w-5 h-5 fill-current" />
                {versus ? 'Quay lại trận' : paused ? 'Tiếp tục' : 'Đóng'}
              </button>
              <button
                type="button"
                onClick={onHelp}
                aria-label="Mở hướng dẫn chơi"
                className="px-4 py-3.5 min-h-[44px] rounded-2xl font-bold text-xs text-amber-300 bg-white/10 border border-white/15 hover:bg-white/20 transition-all flex items-center justify-center gap-1.5 focus-visible:ring-2 focus-visible:ring-amber-400"
              >
                <HelpCircle className="w-4 h-4" />
                Hướng dẫn
              </button>
              <button
                type="button"
                onClick={() => setConfirming(true)}
                className="px-4 py-3.5 min-h-[44px] rounded-2xl font-bold text-xs text-rose-200 bg-rose-500/15 border border-rose-400/40 hover:bg-rose-500/30 transition-all focus-visible:ring-2 focus-visible:ring-rose-300"
              >
                Rời trận
              </button>
            </div>
          ) : (
            <div className="pt-3 border-t border-white/10" role="alertdialog" aria-labelledby="leave-confirm-title" aria-describedby="leave-confirm-text">
              <p id="leave-confirm-title" className="text-sm font-black text-rose-200 uppercase tracking-wider">Rời trận?</p>
              <p id="leave-confirm-text" className="text-xs text-white/75 mt-1 leading-relaxed">{confirmText}</p>
              {leaveError && (
                <p className="mt-2 text-xs font-semibold text-rose-200 bg-rose-500/15 border border-rose-400/40 rounded-lg px-3 py-2" role="alert">
                  {leaveError}
                </p>
              )}
              <div className="mt-3 flex gap-2">
                <button
                  ref={stayRef}
                  type="button"
                  disabled={leaving}
                  onClick={() => setConfirming(false)}
                  className="flex-1 py-3 min-h-[44px] rounded-2xl font-bold text-xs text-white bg-white/10 border border-white/15 hover:bg-white/20 transition-all disabled:opacity-40 focus-visible:ring-2 focus-visible:ring-amber-400"
                >
                  Ở lại
                </button>
                <button
                  type="button"
                  disabled={leaving}
                  onClick={leave}
                  className="flex-1 py-3 min-h-[44px] rounded-2xl font-black text-xs text-white uppercase tracking-wider bg-gradient-to-r from-rose-500 to-red-600 hover:brightness-110 transition-all disabled:opacity-60 focus-visible:ring-2 focus-visible:ring-rose-300"
                >
                  {leaving ? 'Đang rời…' : 'Rời trận'}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
