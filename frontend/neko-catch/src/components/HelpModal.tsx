import React, { useRef } from 'react';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { DISPLAY_FONT_STACK } from '../lib/text';
import { XIcon as X } from './Icons';

export const HelpModal: React.FC<{
  open: boolean;
  onClose: () => void;
  questionLabel: string;
  answerLabel: string;
  choiceCount: number;
  versus: boolean;
  mobile: boolean;
}> = ({ open, onClose, questionLabel, answerLabel, choiceCount, versus, mobile }) => {
  const ref = useRef<HTMLDivElement | null>(null);
  useFocusTrap(ref, open, onClose);
  if (!open) return null;

  const keys = choiceCount > 1 ? `1–${Math.min(9, choiceCount)}` : '1';
  const tips = [
    {
      step: '01',
      title: 'Mục tiêu',
      desc: `Nhìn từ ở bảng giữa màn hình (${questionLabel}), di chuyển mèo để hứng con cá mang ${answerLabel.toLowerCase()} đúng. Tốc độ tăng sau mỗi 3 thẻ, đạt tối đa 2 lần sau 10 thẻ.`,
    },
    {
      step: '02',
      title: 'Điều khiển',
      desc: mobile
        ? 'Chạm và kéo ngang trên sân chơi để di chuyển nhân vật đến dưới con cá mang đáp án đúng.'
        : `Phím mũi tên hoặc A/D để di chuyển, rê chuột hoặc chạm để kéo mèo; phím số ${keys} (hoặc thanh đáp án bên dưới) để hứng ngay con cá tương ứng.`,
    },
    { step: '03', title: 'Hứng đúng', desc: 'Cộng 10 điểm và tăng combo. Cứ thêm 3 combo, mỗi câu đúng được cộng thêm 10 điểm.' },
    {
      step: '04',
      title: 'Hứng sai / lỡ cá',
      desc: 'Hứng nhầm hoặc để cả lượt cá rơi hết mà chưa hứng đúng: mất một tim, combo về 0 và sang từ mới. Cá sai chạm đất không làm mất lượt khi vẫn còn cá để hứng.',
    },
    {
      step: '05',
      title: 'Vật phẩm',
      desc: 'Cá vàng +50 điểm, đồng hồ +5 giây, khiên chặn một lỗi, băng đóng băng cá và đồng hồ trong 2 giây.',
    },
    {
      step: '06',
      title: versus ? 'Đối kháng' : 'Luyện tập',
      desc: versus
        ? 'Hai người chơi cùng dãy từ, song song. Hết giờ hoặc hết tim là xong lượt; điểm cao hơn thắng (bằng điểm thì xét số câu đúng, rồi số câu sai). Trận đối kháng không thể tạm dừng.'
        : `Còn tim khi hết giờ là thắng. Có thể tạm dừng bằng nút bánh răng${mobile ? '.' : ' hoặc phím Esc.'}`,
    },
  ];

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/80 backdrop-blur-md">
      <div
        ref={ref}
        role="dialog"
        data-game-ui-panel
        aria-modal="true"
        aria-labelledby="help-modal-title"
        className="p-5 sm:p-6 rounded-3xl w-full max-w-md max-h-[calc(100dvh-2rem)] overflow-y-auto custom-scrollbar"
        style={{ background: 'linear-gradient(160deg, rgba(30,15,70,0.98), rgba(15,5,40,0.99))', border: '1px solid rgba(255,159,67,0.3)' }}
      >
        <div className="flex items-center justify-between mb-4">
          <h3 id="help-modal-title" className="text-lg font-black text-white uppercase tracking-widest" style={{ fontFamily: DISPLAY_FONT_STACK }}>
            Hướng dẫn chơi
          </h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="Đóng hướng dẫn"
            className="w-11 h-11 rounded-xl flex items-center justify-center bg-white/10 hover:bg-white/20 transition-all focus-visible:ring-2 focus-visible:ring-amber-400 shrink-0"
          >
            <X className="w-5 h-5 text-white/60" />
          </button>
        </div>
        <div className="space-y-2.5">
          {tips.map(tip => (
            <div key={tip.step} className="flex gap-3 p-3 rounded-xl bg-white/5 border border-white/10">
              <span className="w-7 h-7 rounded-lg bg-amber-400/15 border border-amber-400/30 text-[10px] font-black text-amber-300 flex items-center justify-center flex-shrink-0">
                {tip.step}
              </span>
              <div>
                <span className="text-xs font-black text-amber-300 uppercase tracking-wider">{tip.title}</span>
                <p className="text-xs text-white/65 mt-0.5 leading-relaxed">{tip.desc}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
