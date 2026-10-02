import cowboy from '@neko-static/sprites/actions/cowboy/manifest.json';
import wizard from '@neko-static/sprites/actions/wizard/manifest.json';
import { assetUrl } from '../assets';
import type { CatPlayer } from '../types/game';

type Action = 'idle' | 'run' | 'catch_success' | 'catch_wrong' | 'win' | 'lose';
const bundles = { 1: cowboy, 2: wizard };
const images = new Map<string, HTMLImageElement>();
const clocks = new WeakMap<CatPlayer, { key: string; start: number; last: number }>();

export function hasCatActions(skin: number): skin is keyof typeof bundles {
  return skin === 1 || skin === 2;
}

export function preloadCatActions() {
  if (typeof Image === 'undefined') return;
  for (const bundle of Object.values(bundles)) {
    for (const action of Object.values(bundle.actions)) {
      if (images.has(action.path)) continue;
      const image = new Image();
      image.src = assetUrl(action.path);
      images.set(action.path, image);
    }
  }
}

/** True once every sheet of the skin has decoded (callers draw a fallback cat until then). */
export function catActionsReady(skin: keyof typeof bundles): boolean {
  preloadCatActions();
  return Object.values(bundles[skin].actions).every(action => {
    const image = images.get(action.path);
    return !!image && image.complete && image.naturalWidth > 0;
  });
}

/** State-local playback: feedback starts on frame zero and holds its final pose.
 * `previewLoop` loops one-shot actions (used by small preview canvases).
 */
export function drawCatAction(
  ctx: CanvasRenderingContext2D, cat: CatPlayer, time: number,
  skin: keyof typeof bundles, previewLoop = false,
) {
  preloadCatActions();
  const bundle = bundles[skin];
  const action: Action = cat.state === 'run_left' || cat.state === 'run_right' ? 'run' : cat.state;
  const config = bundle.actions[action];
  const image = images.get(config.path)!;
  const key = `${skin}:${action}`;
  let clock = clocks.get(cat);
  if (!clock || clock.key !== key || time < clock.last) {
    clock = { key, start: time, last: time };
    clocks.set(cat, clock);
  }
  clock.last = time;
  const elapsed = time - clock.start;
  const step = Math.floor(elapsed / config.frameDurationMs);
  const frame = config.loop || previewLoop
    ? step % config.frameCount : Math.min(step, config.frameCount - 1);
  const size = cat.height * 1.7;
  // Every action uses the same source scale and feet line, including crouching.
  const feet = cat.height * 0.42;
  const hop = action === 'win' ? Math.max(0, Math.sin(elapsed * Math.PI / 450)) * 13 : 0;
  ctx.save();
  ctx.translate(cat.x, cat.y);
  ctx.fillStyle = 'rgba(0,0,0,0.18)';
  ctx.beginPath();
  ctx.ellipse(0, feet, cat.width * 0.38, 7, 0, 0, Math.PI * 2);
  ctx.fill();
  if (cat.shieldActive) {
    ctx.strokeStyle = '#34d399';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.ellipse(0, -cat.height * 0.15, cat.width * 0.7, cat.height * 0.85, 0, 0, Math.PI * 2);
    ctx.stroke();
  }
  if (cat.state === 'run_left') ctx.scale(-1, 1);
  if (image.complete && image.naturalWidth > 0) {
    ctx.drawImage(image,
      (frame % config.columns) * bundle.cellSize,
      Math.floor(frame / config.columns) * bundle.cellSize,
      bundle.cellSize, bundle.cellSize,
      -size / 2, feet - size * bundle.baseline / bundle.cellSize - hop, size, size);
  } else {
    ctx.fillStyle = '#FF9F43';
    ctx.font = '14px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(image.complete ? 'Không tải được trang phục' : 'Đang tải trang phục…', 0, 0);
  }
  ctx.restore();
}
