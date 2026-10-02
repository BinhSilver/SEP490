import React, { useEffect, useState } from 'react';
import { assetUrl } from '../assets';
import type { CatSkinId } from '../graphics/CatSpriteSheet';

/**
 * Dải khung (sprites/lobby/*.webp, xem make_lobby_cats.py). 0–7: F1 F2 nằm ngủ, F3 tỉnh dậy, F4 ngồi, F5 vươn người,
 * F6 ngáp, F7 ngồi, F8 liếm chân. 8–16: khung chuyển tiếp T1–T9. 17–26: bộ liếm chân L1–L10 (mèo cao bồi, phù thủy).
 */
const SHEETS: Record<CatSkinId, string> = { 0: 'classic', 1: 'cowboy', 2: 'wizard' };
const FRAME_COUNT: Record<CatSkinId, number> = { 0: 17, 1: 27, 2: 27 };
const F = (n: number) => n - 1;
const T = (n: number) => n + 7;
const L = (n: number) => n + 16;
const SLEEP = new Set([F(1), F(2)]);
const SIT = new Set([F(4), F(7), L(10)]);

type Step = [number, number];

/** Ngủ → ngồi dậy → vươn, ngáp → gần ngồi thẳng (T7). */
const WAKE_AND_STRETCH: Step[] = [
  [F(1), 900], [F(2), 900], [F(1), 900], [F(2), 900],
  [T(1), 220], [T(2), 220], [F(3), 450], [T(3), 200], [F(4), 800],
  [T(4), 200], [T(5), 220], [F(5), 500], [F(6), 900], [T(6), 220], [T(7), 220],
];
const LIE_DOWN: Step[] = [[F(3), 300], [T(2), 220], [T(1), 220]];

/** Liếm lông cũ (chỉ có F8 ↔ T9), dùng cho mèo chưa có bộ L1–L10. */
const OLD_LICK: Step[] = [
  [F(7), 600],
  [T(8), 420], [T(9), 380],
  [F(8), 560], [T(9), 480], [F(8), 560], [T(9), 480], [F(8), 560], [T(9), 900],
  [F(8), 560], [T(9), 480], [F(8), 560], [T(9), 600],
  [T(8), 420], [F(7), 900],
];

/**
 * Liếm lông L1–L10: giơ chân (L1–L3) → vòng liếm L4 chạm đệm thịt, L5–L6 vuốt lên, L7 thu lưỡi, L3 cúi lại
 * (lặp 4 lần, lần thứ 3 dừng ngắm chân) → hạ chân (L8–L10). Nồi/xô đặt bên cạnh như T7, nên đi thẳng T7 → L1.
 */
const LICK_CYCLE: Step[] = [[L(4), 190], [L(5), 210], [L(6), 260], [L(7), 190], [L(3), 170]];
const NEW_LICK: Step[] = [
  [L(1), 240], [L(2), 220], [L(3), 200],
  ...LICK_CYCLE, ...LICK_CYCLE,
  ...LICK_CYCLE.slice(0, 4), [L(7), 700], [L(3), 170],
  ...LICK_CYCLE,
  [L(8), 220], [L(9), 220], [L(10), 1000],
];

const TIMELINES: Record<CatSkinId, Step[]> = {
  0: [...WAKE_AND_STRETCH, ...OLD_LICK, ...LIE_DOWN],
  1: [...WAKE_AND_STRETCH, ...NEW_LICK, ...LIE_DOWN],
  2: [...WAKE_AND_STRETCH, ...NEW_LICK, ...LIE_DOWN],
};

// Tải trước cả 3 dải để đổi trang phục không bị chớp trắng.
Object.values(SHEETS).forEach(name => { new Image().src = assetUrl(`/sprites/lobby/${name}.webp`); });

/**
 * Mèo ở màn cài đặt trước trận: chạy chuỗi động tác sinh hoạt lặp lại. Các khung chồng lên nhau, đổi khung bằng hoà mờ
 * rất ngắn giữa các khung; ngủ thì thở phập phồng, ngồi thì lắc lư khẽ để đỡ cảm giác "nhảy hình".
 */
export const LobbyCat: React.FC<{ skinId: CatSkinId; className?: string }> = ({ skinId, className = '' }) => {
  const [step, setStep] = useState(0);
  const timeline = TIMELINES[skinId] ?? TIMELINES[0];
  const frames = FRAME_COUNT[skinId] ?? FRAME_COUNT[0];
  // Đổi trang phục giữa chừng: các chuỗi dài ngắn khác nhau, nên giữ vị trí trong giới hạn chuỗi mới.
  const [frame, holdMs] = timeline[step % timeline.length];

  useEffect(() => {
    const timer = window.setTimeout(() => setStep(current => (current % timeline.length + 1) % timeline.length), holdMs);
    return () => window.clearTimeout(timer);
  }, [step, timeline.length, holdMs]);
  // Đã có khung chuyển tiếp: hoà mờ rất ngắn, chỉ để làm mềm chỗ đổi khung.
  const fadeMs = Math.min(110, Math.round(holdMs * 0.4));
  const sheet = `url(${assetUrl(`/sprites/lobby/${SHEETS[skinId] ?? SHEETS[0]}.webp`)})`;
  const motion = SLEEP.has(frame) ? 'lobby-cat-breathe 2.8s ease-in-out infinite'
    : SIT.has(frame) ? 'lobby-cat-sway 2.4s ease-in-out infinite' : 'none';

  return (
    <div role="img" aria-label="Chú mèo đang vươn vai, liếm lông" className={`relative ${className}`}
      style={{ aspectRatio: '300 / 260' }}>
      <div className="absolute inset-0" style={{ transformOrigin: '50% 96%', animation: motion }}>
        {Array.from({ length: frames }, (_, index) => (
          <div
            key={index}
            aria-hidden="true"
            className="absolute inset-0"
            style={{
              backgroundImage: sheet,
              backgroundSize: `${frames * 100}% 100%`,
              backgroundPosition: `${(index * 100) / (frames - 1)}% 0`,
              backgroundRepeat: 'no-repeat',
              opacity: index === frame ? 1 : 0,
              transition: `opacity ${fadeMs}ms linear`,
            }}
          />
        ))}
      </div>
    </div>
  );
};
