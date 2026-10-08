import React, { useEffect, useRef } from 'react';
import { CatSpriteSheet, CatSkinId } from '../graphics/CatSpriteSheet';
import { CatPlayer } from '../types/game';

/** Small animated cat used by the pre-game skin preview and the result screen. */
export const ResultCatCanvas: React.FC<{ skinId: CatSkinId; state: CatPlayer['state']; compact?: boolean }> = ({
  skinId,
  state,
  compact = false,
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    let animId = 0;
    const startTime = performance.now();
    const mockCat: CatPlayer = {
      x: skinId >= 3 ? 138 : 110, y: skinId >= 3 ? 116 : 92.5,
      width: 100, height: skinId >= 3 ? 80 : 100, speed: 10,
      state, frameIndex: 0, basketWidth: 70, basketHeight: 32,
      basketOffsetX: 0, basketOffsetY: -8, shieldActive: false,
    };

    const render = () => {
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (canvas && ctx) {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        CatSpriteSheet.draw(ctx, mockCat, performance.now() - startTime, skinId);
      }
      animId = requestAnimationFrame(render);
    };

    render();
    return () => cancelAnimationFrame(animId);
  }, [skinId, state]);

  return (
    <div className="relative mx-auto my-1 flex justify-center items-center">
      <canvas
        ref={canvasRef}
        width={220}
        height={165}
        aria-hidden="true"
        className={`${compact ? 'w-[176px] h-[132px]' : 'w-[220px] h-[165px]'} drop-shadow-2xl`}
      />
    </div>
  );
};
